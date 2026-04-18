package com.streamvault.data.manager.recording

import android.content.ContentResolver
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingSourceType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request

data class CaptureProgress(
    val bytesWritten: Long,
    val averageThroughputBytesPerSecond: Long,
    val lastProgressAtMs: Long,
    val retryCount: Int = 0
)

interface RecordingCaptureEngine {
    val sourceType: RecordingSourceType

    suspend fun capture(
        source: ResolvedRecordingSource,
        outputTarget: RecordingOutputTarget,
        contentResolver: ContentResolver,
        scheduledEndMs: Long,
        onProgress: suspend (CaptureProgress) -> Unit
    )
}

@Singleton
class TsPassThroughCaptureEngine @Inject constructor(
    private val okHttpClient: OkHttpClient
) : RecordingCaptureEngine {
    override val sourceType: RecordingSourceType = RecordingSourceType.TS

    override suspend fun capture(
        source: ResolvedRecordingSource,
        outputTarget: RecordingOutputTarget,
        contentResolver: ContentResolver,
        scheduledEndMs: Long,
        onProgress: suspend (CaptureProgress) -> Unit
    ) {
        val startMs = System.currentTimeMillis()
        var bytesWritten = 0L
        val request = Request.Builder().url(source.url).apply {
            source.userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
            source.headers.forEach { (key, value) -> header(key, value) }
        }.build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Recording stream failed with HTTP ${response.code}")
            val body = response.body ?: throw IOException("Recording stream returned an empty body")
            val output = outputTarget.openOutputStream(contentResolver)
                ?: throw IOException("Could not open recording output target")
            output.use { sink ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (scheduledEndMs <= System.currentTimeMillis()) break
                        val read = input.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        bytesWritten += read
                        val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                        onProgress(
                            CaptureProgress(
                                bytesWritten = bytesWritten,
                                averageThroughputBytesPerSecond = (bytesWritten * 1000L) / elapsedMs,
                                lastProgressAtMs = System.currentTimeMillis()
                            )
                        )
                    }
                    sink.flush()
                }
            }
        }
    }
}

@Singleton
class HlsLiveCaptureEngine @Inject constructor(
    private val okHttpClient: OkHttpClient
) : RecordingCaptureEngine {
    override val sourceType: RecordingSourceType = RecordingSourceType.HLS

    override suspend fun capture(
        source: ResolvedRecordingSource,
        outputTarget: RecordingOutputTarget,
        contentResolver: ContentResolver,
        scheduledEndMs: Long,
        onProgress: suspend (CaptureProgress) -> Unit
    ) {
        val startMs = System.currentTimeMillis()
        var bytesWritten = 0L
        var retryCount = 0
        val headers = buildMap {
            putAll(source.headers)
            source.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        val output = outputTarget.openOutputStream(contentResolver)
            ?: throw IOException("Could not open recording output target")
        output.use { sink ->
            var currentPlaylistUrl = source.url
            val seenSegments = linkedSetOf<String>()
            while (kotlinx.coroutines.currentCoroutineContext().isActive && System.currentTimeMillis() < scheduledEndMs) {
                val playlistText = fetchText(currentPlaylistUrl, headers)
                val playlist = parsePlaylist(currentPlaylistUrl, playlistText)
                when (playlist) {
                    is ParsedHlsPlaylist.Master -> {
                        currentPlaylistUrl = playlist.bestVariantUrl
                    }
                    is ParsedHlsPlaylist.Media -> {
                        // Validate that all encryption methods are supported
                        playlist.segments.mapNotNull { it.key }
                            .distinctBy { it.uri }
                            .forEach { key ->
                                if (!key.method.equals("NONE", ignoreCase = true) &&
                                    !key.method.equals("AES-128", ignoreCase = true)
                                ) {
                                    throw UnsupportedRecordingException(
                                        "This HLS stream uses unsupported DRM or encryption.",
                                        RecordingFailureCategory.DRM_UNSUPPORTED
                                    )
                                }
                            }
                        // Cache key bytes by URI so rotated keys are fetched once each
                        val keyCache = mutableMapOf<String, ByteArray>()
                        playlist.segments.forEach { segment ->
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (segment.uri in seenSegments) return@forEach
                            seenSegments += segment.uri
                            val bytes = fetchBytes(segment.uri, headers)
                            val segKey = segment.key?.takeIf { it.method.equals("AES-128", ignoreCase = true) }
                            val payload = if (segKey != null) {
                                val keyBytes = keyCache.getOrPut(segKey.uri) { fetchBytes(segKey.uri, headers) }
                                decryptAes128(bytes, keyBytes, segKey.iv)
                            } else {
                                bytes
                            }
                            sink.write(payload)
                            bytesWritten += payload.size
                            val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                            onProgress(
                                CaptureProgress(
                                    bytesWritten = bytesWritten,
                                    averageThroughputBytesPerSecond = (bytesWritten * 1000L) / elapsedMs,
                                    lastProgressAtMs = System.currentTimeMillis(),
                                    retryCount = retryCount
                                )
                            )
                            if (System.currentTimeMillis() >= scheduledEndMs) return@forEach
                        }
                        sink.flush()
                        retryCount = 0
                        if (playlist.endList) break
                        delay((playlist.targetDurationSeconds.coerceAtLeast(2) * 1000L) / 2L)
                    }
                }
            }
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Recording stream failed with HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Recording stream failed with HTTP ${response.code}")
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun decryptAes128(payload: ByteArray, keyBytes: ByteArray, ivHex: String?): ByteArray {
        val iv = ivHex?.removePrefix("0x")
            ?.chunked(2)
            ?.mapNotNull { it.toIntOrNull(16)?.toByte() }
            ?.toByteArray()
            ?.takeIf { it.size == 16 }
            ?: ByteArray(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(payload)
    }

    private fun parsePlaylist(baseUrl: String, rawText: String): ParsedHlsPlaylist {
        val lines = rawText.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
            val variants = mutableListOf<Pair<Int, String>>()
            var pendingBandwidth = 0
            lines.forEachIndexed { index, line ->
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    pendingBandwidth = Regex("""BANDWIDTH=(\d+)""")
                        .find(line)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 0
                    val next = lines.getOrNull(index + 1)?.takeIf { !it.startsWith("#") } ?: return@forEachIndexed
                    variants += pendingBandwidth to resolveRelativeUrl(baseUrl, next)
                }
            }
            val bestVariantUrl = variants.maxByOrNull { it.first }?.second
                ?: throw IOException("No playable HLS variants were available.")
            return ParsedHlsPlaylist.Master(bestVariantUrl)
        }

        var targetDuration = 6
        var endList = false
        var currentKey: HlsKey? = null
        val segments = mutableListOf<HlsSegment>()
        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true) -> {
                    targetDuration = line.substringAfter(':', "6").toIntOrNull() ?: 6
                }
                line.startsWith("#EXT-X-ENDLIST", ignoreCase = true) -> {
                    endList = true
                }
                line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                    val attrs = parseHlsAttributes(line.substringAfter(':'))
                    currentKey = HlsKey(
                        method = attrs["METHOD"].orEmpty(),
                        uri = attrs["URI"]?.let { resolveRelativeUrl(baseUrl, it) }.orEmpty(),
                        iv = attrs["IV"]
                    )
                }
                line.startsWith("#") -> Unit
                else -> {
                    segments += HlsSegment(
                        uri = resolveRelativeUrl(baseUrl, line),
                        key = currentKey?.copy()
                    )
                }
            }
        }
        return ParsedHlsPlaylist.Media(
            targetDurationSeconds = targetDuration,
            endList = endList,
            segments = segments
        )
    }

    private fun parseHlsAttributes(raw: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        val parts = raw.split(',')
        parts.forEach { part ->
            val key = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim().removeSurrounding("\"")
            if (key.isNotBlank()) {
                attrs[key.uppercase(Locale.ROOT)] = value
            }
        }
        return attrs
    }

    private fun resolveRelativeUrl(baseUrl: String, value: String): String {
        return runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)
    }
}

private sealed interface ParsedHlsPlaylist {
    data class Master(val bestVariantUrl: String) : ParsedHlsPlaylist
    data class Media(
        val targetDurationSeconds: Int,
        val endList: Boolean,
        val segments: List<HlsSegment>
    ) : ParsedHlsPlaylist
}

private data class HlsSegment(
    val uri: String,
    val key: HlsKey? = null
)

private data class HlsKey(
    val method: String,
    val uri: String,
    val iv: String? = null
)

class UnsupportedRecordingException(
    message: String,
    val category: RecordingFailureCategory
) : IOException(message)
