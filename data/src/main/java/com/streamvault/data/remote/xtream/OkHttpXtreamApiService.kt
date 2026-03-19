package com.streamvault.data.remote.xtream

import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamEpgResponse
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.dto.XtreamVodInfoResponse
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request

class OkHttpXtreamApiService(
    private val client: OkHttpClient,
    private val json: Json
) : XtreamApiService {

    override suspend fun authenticate(endpoint: String): XtreamAuthResponse = get(endpoint)

    override suspend fun getLiveCategories(endpoint: String): List<XtreamCategory> = get(endpoint)

    override suspend fun getLiveStreams(endpoint: String): List<XtreamStream> = get(endpoint)

    override suspend fun getVodCategories(endpoint: String): List<XtreamCategory> = get(endpoint)

    override suspend fun getVodStreams(endpoint: String): List<XtreamStream> = get(endpoint)

    override suspend fun getVodInfo(endpoint: String): XtreamVodInfoResponse = get(endpoint)

    override suspend fun getSeriesCategories(endpoint: String): List<XtreamCategory> = get(endpoint)

    override suspend fun getSeriesList(endpoint: String): List<XtreamSeriesItem> = get(endpoint)

    override suspend fun getSeriesInfo(endpoint: String): XtreamSeriesInfoResponse = get(endpoint)

    override suspend fun getShortEpg(endpoint: String): XtreamEpgResponse = get(endpoint)

    override suspend fun getFullEpg(endpoint: String): XtreamEpgResponse = get(endpoint)

    private suspend inline fun <reified T> get(endpoint: String): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val message = "HTTP ${response.code}"
                    when (response.code) {
                        401, 403 -> throw XtreamAuthenticationException(response.code, message)
                        in 500..599, 429 -> throw XtreamNetworkException(message)
                        else -> throw XtreamRequestException(response.code, message)
                    }
                }
                val body = response.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw XtreamParsingException("Empty response body")
                try {
                    json.decodeFromString<T>(body)
                } catch (e: SerializationException) {
                    throw XtreamParsingException("Server returned malformed data", e)
                }
            }
        } catch (e: XtreamApiException) {
            throw e
        } catch (e: IOException) {
            throw XtreamNetworkException(e.message ?: "Network request failed", e)
        }
    }
}