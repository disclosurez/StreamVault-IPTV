package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class OkHttpStalkerApiServiceTest {

    @Test
    fun authenticate_reads_token_and_profile_from_js_wrapper() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "handshake" to """{"js":{"token":"token-123"}}""",
                "get_profile" to """{"js":{"name":"Living Room","status":"1","max_online":"2"}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.first.token).isEqualTo("token-123")
        assertThat(success.data.second.accountName).isEqualTo("Living Room")
        assertThat(success.data.second.maxConnections).isEqualTo(2)
    }

    @Test
    fun createLink_reads_cmd_from_js_wrapper() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "create_link" to """{"js":{"cmd":"ffmpeg http://cdn.example.com/live/stream.ts"}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            kind = StalkerStreamKind.LIVE,
            cmd = "ffmpeg http://placeholder"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data).isEqualTo("http://cdn.example.com/live/stream.ts")
    }

    @Test
    fun authenticate_reads_json_from_callback_wrapper_and_control_char_noise() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "handshake" to "\u0000callback({\"js\":{\"token\":\"token-123\"}});",
                "get_profile" to "\u0000callback({\"js\":{\"name\":\"Living Room\",\"status\":\"1\"}});"
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.first.token).isEqualTo("token-123")
        assertThat(success.data.second.accountName).isEqualTo("Living Room")
    }

    @Test
    fun authenticate_reports_access_denied_html_clearly() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "handshake" to """<!DOCTYPE html><html><body>Access Denied.</body></html>"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.message).isEqualTo("Portal denied the request for handshake.")
    }

    @Test
    fun getLiveCategories_retries_alternate_endpoint_for_authenticated_requests() = runTest {
        val requestedUrls = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val request = chain.request()
                    requestedUrls += request.url.toString()
                    val body = when (request.url.encodedPath) {
                        "/server/load.php" -> if (request.url.queryParameter("action") == "get_genres") {
                            throw java.io.IOException("\\n not found: limit=1 content=0d…")
                        } else {
                            """{"js":{"token":"token-123"}}"""
                        }
                        "/portal.php" -> """{"js":[{"id":"10","title":"News"}]}"""
                        else -> error("Unexpected path ${request.url.encodedPath}")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                })
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getLiveCategories(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.map { it.name }).containsExactly("News")
        assertThat(requestedUrls).containsAtLeast(
            "https://portal.example.com/server/load.php?type=itv&action=get_genres&JsHttpRequest=1-xml",
            "https://portal.example.com/portal.php?type=itv&action=get_genres&JsHttpRequest=1-xml"
        )
    }

    private fun fakeClient(vararg responses: Pair<String, String>): OkHttpClient {
        val byAction = responses.toMap()
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val action = request.url.queryParameter("action").orEmpty()
                val body = byAction[action] ?: error("Missing fake response for action '$action'")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }
}