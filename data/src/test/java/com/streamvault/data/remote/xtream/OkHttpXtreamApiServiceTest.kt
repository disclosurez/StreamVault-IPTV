package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class OkHttpXtreamApiServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `get classifies 401 as authentication error`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(statusCode = 401, body = "{}"),
            json = json
        )

        val failure = runCatching {
            service.getLiveCategories("https://example.test/player_api.php")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamAuthenticationException::class.java)
        assertThat(failure).hasMessageThat().contains("HTTP 401")
    }

    @Test
    fun `get classifies malformed JSON as parsing error`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(statusCode = 200, body = "{not-json}"),
            json = json
        )

        val failure = runCatching {
            service.getLiveCategories("https://example.test/player_api.php")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamParsingException::class.java)
        assertThat(failure).hasMessageThat().contains("malformed")
    }

    @Test
    fun `get classifies transport failures as network errors`() = runTest {
        val service = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { throw SocketTimeoutException("timed out") }
                .build(),
            json = json
        )

        val failure = runCatching {
            service.getLiveCategories("https://example.test/player_api.php")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamNetworkException::class.java)
        assertThat(failure).hasMessageThat().contains("timed out")
    }

    private fun clientReturning(statusCode: Int, body: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(Request.Builder().url(chain.request().url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("test")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }
}