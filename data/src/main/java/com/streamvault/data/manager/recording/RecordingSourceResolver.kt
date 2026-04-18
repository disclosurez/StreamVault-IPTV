package com.streamvault.data.manager.recording

import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.remote.xtream.ResolvedStreamUrl
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingSourceType
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Request

data class ResolvedRecordingSource(
    val url: String,
    val sourceType: RecordingSourceType,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val expirationTime: Long? = null,
    val providerLabel: String? = null,
    val failureCategory: RecordingFailureCategory = RecordingFailureCategory.NONE
)

@Singleton
class RecordingSourceResolver @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val providerDao: ProviderDao,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver
) {

    suspend fun resolveLiveSource(
        providerId: Long,
        channelId: Long,
        logicalUrl: String
    ): ResolvedRecordingSource {
        val resolved = xtreamStreamUrlResolver.resolveWithMetadata(
            url = logicalUrl,
            fallbackProviderId = providerId,
            fallbackStreamId = channelId,
            fallbackContentType = ContentType.LIVE,
            // Recording jobs can run for minutes or hours; prefer the stable
            // portal URL over an expiring tokenized direct-source CDN URL.
            preferStableUrl = true
        ) ?: throw IOException("Recording stream URL could not be resolved.")

        val providerLabel = providerDao.getById(providerId)?.let { provider ->
            when {
                provider.type == com.streamvault.domain.model.ProviderType.XTREAM_CODES -> "${provider.name} • Xtream"
                provider.type == com.streamvault.domain.model.ProviderType.M3U -> "${provider.name} • M3U"
                else -> provider.name
            }
        }
        val inferred = sniffSourceType(resolved)
        return ResolvedRecordingSource(
            url = resolved.url,
            sourceType = inferred,
            expirationTime = resolved.expirationTime,
            providerLabel = providerLabel
        )
    }

    private fun sniffSourceType(resolved: ResolvedStreamUrl): RecordingSourceType {
        val url = resolved.url.lowercase(Locale.ROOT)
        return when {
            url.contains(".mpd") || url.contains("ext=mpd") -> RecordingSourceType.DASH
            url.contains(".ts") || url.contains("ext=ts") -> RecordingSourceType.TS
            url.contains(".m3u8") || url.contains("ext=m3u8") || url.contains("/hd") || url.contains("/sd") -> {
                probeAdaptiveType(resolved.url)
            }
            else -> probeAdaptiveType(resolved.url)
        }
    }

    private fun probeAdaptiveType(url: String): RecordingSourceType {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-2047")
            .get()
            .build()

        val bodyPrefix = runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                when {
                    "application/vnd.apple.mpegurl" in contentType || "application/x-mpegurl" in contentType -> return RecordingSourceType.HLS
                    "application/dash+xml" in contentType -> return RecordingSourceType.DASH
                }
                response.body?.string().orEmpty().take(1024)
            }
        }.getOrDefault("")

        return when {
            bodyPrefix.contains("#EXTM3U", ignoreCase = true) -> RecordingSourceType.HLS
            bodyPrefix.contains("<MPD", ignoreCase = true) -> RecordingSourceType.DASH
            else -> RecordingSourceType.TS
        }
    }
}
