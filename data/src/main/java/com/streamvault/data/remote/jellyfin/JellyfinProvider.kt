package com.streamvault.data.remote.jellyfin

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.streamvault.data.local.entity.EpisodeEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.Result
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JellyfinProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    private companion object {
        private const val MOVIE_CATEGORY_ID = 1L
        private const val SERIES_CATEGORY_ID = 2L
        private const val REQUEST_TIMEOUT_SECONDS = 60L
        private const val QUICK_CONNECT_TIMEOUT_MILLIS = 120_000L
        private const val QUICK_CONNECT_POLL_INTERVAL_MILLIS = 2_000L
        private val FIELDS = listOf(
            "Overview",
            "ProviderIds",
            "ProductionYear",
            "PremiereDate",
            "RunTimeTicks",
            "Genres",
            "CommunityRating",
            "ImageTags",
            "BackdropImageTags",
            "MediaSources",
            "ParentId",
            "SeriesId",
            "SeriesName",
            "ParentIndexNumber",
            "IndexNumber",
            "DateCreated",
            "DateLastMediaAdded",
            "Path"
        )
    }

    private val itemsResponseType = object : TypeToken<JellyfinItemsResponseDto>() {}.type
    private val seriesResponseType = object : TypeToken<JellyfinSeriesEpisodesResponseDto>() {}.type
    private val authResultType = object : TypeToken<JellyfinAuthenticationResultDto>() {}.type
    private val quickConnectInitiateResponseType = object : TypeToken<JellyfinQuickConnectInitiateResponseDto>() {}.type
    private val quickConnectStatusResponseType = object : TypeToken<JellyfinQuickConnectStatusResponseDto>() {}.type

    suspend fun authenticate(serverUrl: String, username: String, password: String): Result<String> = try {
        val session = authenticateSession(serverUrl, username, password)
        Result.success(session.accessToken)
    } catch (e: Exception) {
        Result.error("Failed to authenticate Jellyfin user: ${e.message}", e)
    }

    suspend fun authenticateQuickConnect(
        serverUrl: String,
        onCode: ((String) -> Unit)? = null,
        onProgress: ((String) -> Unit)? = null
    ): Result<JellyfinQuickConnectAuthenticationResult> {
        return try {
            if (!isQuickConnectEnabled(serverUrl)) {
                return Result.error("Quick Connect is disabled on this Jellyfin server")
            }
            onProgress?.invoke("Requesting Quick Connect code...")
            val initiation = initiateQuickConnect(serverUrl)
            val secret = initiation.secret?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Jellyfin Quick Connect did not return a secret")
            val code = initiation.code?.takeIf { it.isNotBlank() } ?: secret
            onCode?.invoke(code)
            onProgress?.invoke("Waiting for Jellyfin approval...")

            val deadline = System.currentTimeMillis() + QUICK_CONNECT_TIMEOUT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                when (val state = pollQuickConnectState(serverUrl, secret)) {
                    is Result.Success -> {
                        if (state.data.authenticated) {
                            return authenticateWithQuickConnect(serverUrl, secret)
                        }
                    }
                    is Result.Error -> {
                        if (state.message.contains("not yet authorized", ignoreCase = true) ||
                            state.message.contains("pending", ignoreCase = true)
                        ) {
                            // Continue polling.
                        } else {
                            return Result.error("Quick Connect failed: ${state.message}", state.exception)
                        }
                    }
                    is Result.Loading -> Unit
                }
                delay(QUICK_CONNECT_POLL_INTERVAL_MILLIS)
            }
            Result.error("Quick Connect timed out waiting for approval")
        } catch (e: Exception) {
            Result.error("Failed to complete Jellyfin Quick Connect: ${e.message}", e)
        }
    }

    suspend fun fetchMovies(provider: Provider): Result<List<MovieEntity>> = try {
        val items = fetchItems(
            provider = provider,
            path = "/Items",
            query = mapOf(
                "IncludeItemTypes" to "Movie",
                "Recursive" to "true",
                "EnableImages" to "false",
                "EnableUserData" to "false",
                "Fields" to FIELDS.joinToString(",")
            )
        )
        Result.success(
            items.map { item ->
                val remoteId = item.id.orEmpty()
                MovieEntity(
                    streamId = stableRemoteId(remoteId),
                    name = item.name.orEmpty(),
                    posterUrl = buildJellyfinImageUrl(
                        baseUrl = provider.serverUrl,
                        itemId = remoteId,
                        imageType = "Primary",
                        tag = item.imageTags?.get("Primary"),
                        apiKey = provider.password
                    ),
                    backdropUrl = item.backdropImageTags?.firstOrNull()?.let { tag ->
                        buildJellyfinImageUrl(
                            baseUrl = provider.serverUrl,
                            itemId = remoteId,
                            imageType = "Backdrop",
                            imageIndex = 0,
                            tag = tag,
                            apiKey = provider.password
                        )
                    },
                    categoryId = MOVIE_CATEGORY_ID,
                    categoryName = "Movies",
                    streamUrl = buildStreamUrl(provider.serverUrl, remoteId),
                    containerExtension = item.primaryMediaSource?.container?.takeIf { it.isNotBlank() },
                    plot = item.overview,
                    cast = null,
                    director = null,
                    genre = item.genres?.joinToString(", "),
                    releaseDate = item.premiereDate?.take(10),
                    duration = item.runTimeTicks?.let(::ticksToDurationString),
                    durationSeconds = item.runTimeTicks?.let(::ticksToSeconds)?.toInt() ?: 0,
                    rating = item.communityRating?.toFloat() ?: 0f,
                    year = item.productionYear?.toString(),
                    tmdbId = item.providerIds?.get("Tmdb")?.toLongOrNull(),
                    youtubeTrailer = null,
                    providerId = provider.id,
                    isAdult = false,
                    addedAt = item.dateCreated?.let(::parseJellyfinDateMillis) ?: System.currentTimeMillis()
                )
            }
        )
    } catch (e: Exception) {
        Result.error("Failed to load Jellyfin movies: ${e.message}", e)
    }

    suspend fun fetchSeries(provider: Provider): Result<List<SeriesEntity>> = try {
        val items = fetchItems(
            provider = provider,
            path = "/Items",
            query = mapOf(
                "IncludeItemTypes" to "Series",
                "Recursive" to "true",
                "EnableImages" to "false",
                "EnableUserData" to "false",
                "Fields" to FIELDS.joinToString(",")
            )
        )
        Result.success(
            items.map { item ->
                val remoteId = item.id.orEmpty()
                SeriesEntity(
                    seriesId = stableRemoteId(remoteId),
                    providerSeriesId = remoteId,
                    name = item.name.orEmpty(),
                    posterUrl = buildJellyfinImageUrl(
                        baseUrl = provider.serverUrl,
                        itemId = remoteId,
                        imageType = "Primary",
                        tag = item.imageTags?.get("Primary"),
                        apiKey = provider.password
                    ),
                    backdropUrl = item.backdropImageTags?.firstOrNull()?.let { tag ->
                        buildJellyfinImageUrl(
                            baseUrl = provider.serverUrl,
                            itemId = remoteId,
                            imageType = "Backdrop",
                            imageIndex = 0,
                            tag = tag,
                            apiKey = provider.password
                        )
                    },
                    categoryId = SERIES_CATEGORY_ID,
                    categoryName = "Series",
                    plot = item.overview,
                    cast = null,
                    director = null,
                    genre = item.genres?.joinToString(", "),
                    releaseDate = item.premiereDate?.take(10),
                    rating = item.communityRating?.toFloat() ?: 0f,
                    tmdbId = item.providerIds?.get("Tmdb")?.toLongOrNull(),
                    youtubeTrailer = null,
                    episodeRunTime = item.runTimeTicks?.let(::ticksToDurationString),
                    lastModified = item.dateLastMediaAdded?.let(::parseJellyfinDateMillis)
                        ?: item.dateCreated?.let(::parseJellyfinDateMillis)
                        ?: System.currentTimeMillis(),
                    providerId = provider.id,
                    isAdult = false
                )
            }
        )
    } catch (e: Exception) {
        Result.error("Failed to load Jellyfin series: ${e.message}", e)
    }

    suspend fun fetchEpisodes(
        provider: Provider,
        seriesRemoteId: String,
        seriesLocalId: Long
    ): Result<List<EpisodeEntity>> = try {
        val response = fetchSeriesEpisodes(provider, seriesRemoteId)
        Result.success(
            response.map { item ->
                val remoteId = item.id.orEmpty()
                EpisodeEntity(
                    episodeId = stableRemoteId(remoteId),
                    title = item.name.orEmpty(),
                    episodeNumber = item.indexNumber ?: 0,
                    seasonNumber = item.parentIndexNumber ?: 0,
                    streamUrl = buildStreamUrl(provider.serverUrl, remoteId),
                    containerExtension = item.primaryMediaSource?.container?.takeIf { it.isNotBlank() },
                    coverUrl = buildJellyfinImageUrl(
                        baseUrl = provider.serverUrl,
                        itemId = remoteId,
                        imageType = "Primary",
                        tag = item.imageTags?.get("Primary"),
                        apiKey = provider.password
                    ),
                    plot = item.overview,
                    duration = item.runTimeTicks?.let(::ticksToDurationString),
                    durationSeconds = item.runTimeTicks?.let(::ticksToSeconds)?.toInt() ?: 0,
                    rating = item.communityRating?.toFloat() ?: 0f,
                    releaseDate = item.premiereDate?.take(10),
                    seriesId = seriesLocalId,
                    providerId = provider.id,
                    isAdult = false
                )
            }
        )
    } catch (e: Exception) {
        Result.error("Failed to load Jellyfin episodes: ${e.message}", e)
    }

    private fun fetchItems(
        provider: Provider,
        path: String,
        query: Map<String, String>
    ): List<JellyfinItemDto> {
        val url = buildUrl(provider.serverUrl, path, query)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", buildJellyfinAuthorizationHeader(provider.serverUrl, provider.username, provider.password))
            .header("Accept", "application/json")
            .get()
            .build()
        okHttpClient.newBuilder()
            .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Jellyfin request failed with HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return emptyList()
                val parsed = gson.fromJson<JellyfinItemsResponseDto>(body, itemsResponseType)
                return parsed.items.orEmpty().filter { !it.id.isNullOrBlank() }
            }
    }

    private fun fetchSeriesEpisodes(provider: Provider, seriesRemoteId: String): List<JellyfinItemDto> {
        val url = buildUrl(
            provider.serverUrl,
            "/Shows/$seriesRemoteId/Episodes",
            mapOf(
                "Fields" to FIELDS.joinToString(","),
                "EnableImages" to "false",
                "EnableUserData" to "false"
            )
        )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", buildJellyfinAuthorizationHeader(provider.serverUrl, provider.username, provider.password))
            .header("Accept", "application/json")
            .get()
            .build()
        okHttpClient.newBuilder()
            .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Jellyfin request failed with HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return emptyList()
                val parsed = gson.fromJson<JellyfinSeriesEpisodesResponseDto>(body, seriesResponseType)
                return parsed.items.orEmpty().filter { !it.id.isNullOrBlank() }
            }
    }

    private fun authenticateSession(serverUrl: String, username: String, password: String): JellyfinAuthenticatedSession {
        val url = "${serverUrl.trimEnd('/')}/Users/AuthenticateByName"
        val payload = gson.toJson(
            JellyfinAuthenticateRequestDto(
                username = username,
                password = password
            )
        )
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", buildJellyfinAuthorizationHeader(serverUrl, username))
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        okHttpClient.newBuilder()
            .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Jellyfin login failed with HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    throw IllegalStateException("Jellyfin login returned an empty response")
                }
                val parsed = gson.fromJson<JellyfinAuthenticationResultDto>(body, authResultType)
                val token = parsed.accessToken?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Jellyfin login did not return an access token")
                val userId = parsed.user?.id?.takeIf { it.isNotBlank() }
                    ?: parsed.sessionInfo?.userId?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Jellyfin login did not return a user id")
                return JellyfinAuthenticatedSession(
                    accessToken = token,
                    userId = userId,
                    userName = parsed.user?.name?.takeIf { it.isNotBlank() } ?: username
                )
            }
    }

    private fun initiateQuickConnect(serverUrl: String): JellyfinQuickConnectInitiateResponseDto {
        val url = buildUrl(serverUrl, "/QuickConnect/Initiate", emptyMap())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", buildJellyfinAuthorizationHeader(serverUrl, "StreamVault Quick Connect"))
            .header("Accept", "application/json")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return executeJsonRequest(request, quickConnectInitiateResponseType, "Jellyfin quick connect initiation failed")
    }

    private fun isQuickConnectEnabled(serverUrl: String): Boolean {
        val url = buildUrl(serverUrl, "/QuickConnect/Enabled", emptyMap())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", buildJellyfinAuthorizationHeader(serverUrl, "StreamVault Quick Connect"))
            .header("Accept", "application/json")
            .get()
            .build()
        val body = executeRawRequest(request, "Jellyfin quick connect enablement lookup failed")
        return runCatching { gson.fromJson(body, Boolean::class.javaObjectType) }.getOrDefault(false)
    }

    private fun pollQuickConnectState(
        serverUrl: String,
        secret: String
    ): Result<JellyfinQuickConnectStatusResponseDto> {
        val url = buildUrl(
            serverUrl,
            "/QuickConnect/Connect",
            mapOf("Secret" to secret)
        )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", buildJellyfinAuthorizationHeader(serverUrl, "StreamVault Quick Connect"))
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            Result.success(executeJsonRequest(request, quickConnectStatusResponseType, "Jellyfin quick connect status failed"))
        } catch (e: Exception) {
            Result.error(e.message ?: "Jellyfin quick connect status failed", e)
        }
    }

    private fun authenticateWithQuickConnect(
        serverUrl: String,
        secret: String
    ): Result<JellyfinQuickConnectAuthenticationResult> {
        val url = buildUrl(serverUrl, "/Users/AuthenticateWithQuickConnect", emptyMap())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", buildJellyfinAuthorizationHeader(serverUrl, "StreamVault Quick Connect"))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(gson.toJson(JellyfinQuickConnectAuthenticateRequestDto(secret)).toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val auth = executeJsonRequest<JellyfinAuthenticationResultDto>(
                request = request,
                responseType = authResultType,
                errorPrefix = "Jellyfin quick connect authentication failed"
            )
            val accessToken = auth.accessToken?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Jellyfin quick connect did not return an access token")
            val userId = auth.user?.id?.takeIf { it.isNotBlank() }
                ?: auth.sessionInfo?.userId?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Jellyfin quick connect did not return a user id")
            Result.success(
                JellyfinQuickConnectAuthenticationResult(
                    accessToken = accessToken,
                    userId = userId,
                    userName = auth.user?.name?.takeIf { it.isNotBlank() } ?: ""
                )
            )
        } catch (e: Exception) {
            Result.error(e.message ?: "Jellyfin quick connect authentication failed", e)
        }
    }

    private fun <T> executeJsonRequest(
        request: Request,
        responseType: java.lang.reflect.Type,
        errorPrefix: String
    ): T {
        val body = executeRawRequest(request, errorPrefix)
        @Suppress("UNCHECKED_CAST")
        return gson.fromJson<T>(body, responseType)
    }

    private fun executeRawRequest(
        request: Request,
        errorPrefix: String
    ): String {
        okHttpClient.newBuilder()
            .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("$errorPrefix with HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    throw IllegalStateException("$errorPrefix returned an empty response")
                }
                return body
            }
    }

    private fun buildUrl(baseUrl: String, path: String, query: Map<String, String>): String {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val queryString = query.entries.joinToString("&") { (key, value) ->
            "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
        }
        return buildString {
            append(normalizedBaseUrl)
            append(path)
            if (queryString.isNotBlank()) {
                append("?")
                append(queryString)
            }
        }
    }

    private fun buildStreamUrl(baseUrl: String, itemId: String): String =
        "${baseUrl.trimEnd('/')}/Videos/$itemId/stream"

    private fun buildJellyfinImageUrl(
        baseUrl: String,
        itemId: String,
        imageType: String,
        tag: String? = null,
        imageIndex: Int? = null,
        apiKey: String? = null
    ): String {
        val query = buildList {
            apiKey?.takeIf { it.isNotBlank() }?.let { add("api_key=${encodeQueryComponent(it)}") }
            tag?.takeIf { it.isNotBlank() }?.let { add("tag=${encodeQueryComponent(it)}") }
        }.joinToString("&")
        val imagePath = when (imageIndex) {
            null -> "/Items/$itemId/Images/$imageType"
            else -> "/Items/$itemId/Images/$imageType/$imageIndex"
        }
        return buildString {
            append(baseUrl.trimEnd('/'))
            append(imagePath)
            if (query.isNotBlank()) {
                append("?")
                append(query)
            }
        }
    }

    private fun stableRemoteId(value: String): Long {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (digest[i].toLong() and 0xff)
        }
        return result and Long.MAX_VALUE
    }

    private fun encodeQueryComponent(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun ticksToSeconds(ticks: Long): Long = ticks / 10_000_000L

    private fun ticksToDurationString(ticks: Long): String {
        val totalSeconds = ticksToSeconds(ticks)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun parseJellyfinDateMillis(value: String): Long =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
}

private data class JellyfinAuthenticateRequestDto(
    @SerializedName("Username")
    val username: String,
    @SerializedName("Pw")
    val password: String
)

private data class JellyfinAuthenticationResultDto(
    @SerializedName("AccessToken")
    val accessToken: String? = null,
    @SerializedName("User")
    val user: JellyfinAuthenticatedUserDto? = null,
    @SerializedName("SessionInfo")
    val sessionInfo: JellyfinSessionInfoDto? = null
)

private data class JellyfinItemsResponseDto(
    @SerializedName("Items")
    val items: List<JellyfinItemDto>? = emptyList()
)

private data class JellyfinSeriesEpisodesResponseDto(
    @SerializedName("Items")
    val items: List<JellyfinItemDto>? = emptyList()
)

private data class JellyfinItemDto(
    @SerializedName("Id")
    val id: String? = null,
    @SerializedName("Name")
    val name: String? = null,
    @SerializedName("Type")
    val type: String? = null,
    @SerializedName("SeriesId")
    val seriesId: String? = null,
    @SerializedName("SeriesName")
    val seriesName: String? = null,
    @SerializedName("ParentId")
    val parentId: String? = null,
    @SerializedName("ParentIndexNumber")
    val parentIndexNumber: Int? = null,
    @SerializedName("IndexNumber")
    val indexNumber: Int? = null,
    @SerializedName("Overview")
    val overview: String? = null,
    @SerializedName("PremiereDate")
    val premiereDate: String? = null,
    @SerializedName("ProductionYear")
    val productionYear: Int? = null,
    @SerializedName("RunTimeTicks")
    val runTimeTicks: Long? = null,
    @SerializedName("CommunityRating")
    val communityRating: Double? = null,
    @SerializedName("ProviderIds")
    val providerIds: Map<String, String>? = null,
    @SerializedName("Genres")
    val genres: List<String>? = null,
    @SerializedName("DateCreated")
    val dateCreated: String? = null,
    @SerializedName("DateLastMediaAdded")
    val dateLastMediaAdded: String? = null,
    @SerializedName("ImageTags")
    val imageTags: Map<String, String>? = null,
    @SerializedName("BackdropImageTags")
    val backdropImageTags: List<String>? = null,
    @SerializedName("MediaSources")
    val mediaSources: List<JellyfinMediaSourceDto>? = null
) {
    val primaryMediaSource: JellyfinMediaSourceDto?
        get() = mediaSources?.firstOrNull()
}

private data class JellyfinMediaSourceDto(
    @SerializedName("Id")
    val id: String? = null,
    @SerializedName("Container")
    val container: String? = null
)

private data class JellyfinAuthenticatedSession(
    val accessToken: String,
    val userId: String,
    val userName: String
)

private data class JellyfinAuthenticatedUserDto(
    @SerializedName("Id")
    val id: String? = null,
    @SerializedName("Name")
    val name: String? = null
)

private data class JellyfinSessionInfoDto(
    @SerializedName("UserId")
    val userId: String? = null
)

private data class JellyfinQuickConnectInitiateResponseDto(
    @SerializedName("Code")
    val code: String? = null,
    @SerializedName("Secret")
    val secret: String? = null
)

private data class JellyfinQuickConnectStatusResponseDto(
    @SerializedName("Authenticated")
    val authenticated: Boolean = false
)

private data class JellyfinQuickConnectAuthenticateRequestDto(
    @SerializedName("Secret")
    val secret: String
)

data class JellyfinQuickConnectAuthenticationResult(
    val accessToken: String,
    val userId: String,
    val userName: String
)
