package com.streamvault.data.repository

import android.content.Context
import com.streamvault.data.remote.justwatch.JustwatchClient
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.TrendingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrendingRepositoryImpl @Inject constructor(
    private val justwatchClient: JustwatchClient,
    @ApplicationContext private val context: Context
) : TrendingRepository {

    private val cacheFile: File by lazy {
        File(context.cacheDir, "trending_cache.txt")
    }

    private val persistedTimestamp: Long
        get() = cacheFile.takeIf { it.exists() }?.let { readPersistedTimestamp() } ?: 0L

    private var moviesCache: TitlesCache? = null
    private var seriesCache: TitlesCache? = null

    override suspend fun getTrendingMovieTitles(): Result<List<String>> {
        val now = System.currentTimeMillis()
        if (now - persistedTimestamp < CACHE_TTL_MS) {
            val persisted = readPersistedMovieTitles()
            if (persisted.isNotEmpty()) return Result.success(persisted)
        }
        moviesCache?.let { if (now - it.timestamp < CACHE_TTL_MS) return Result.success(it.titles) }
        return try {
            val titles = justwatchClient.getPopularMovieTitles()
                .filterNot { it.contains("podcast", ignoreCase = true) }
            moviesCache = TitlesCache(titles, now)
            persist(titles = titles, seriesTitles = null, timestamp = now)
            Result.success(titles)
        } catch (e: Exception) {
            val persisted = readPersistedMovieTitles()
            if (persisted.isNotEmpty()) return Result.success(persisted)
            Result.success(moviesCache?.titles ?: emptyList())
        }
    }

    override suspend fun getTrendingSeriesTitles(): Result<List<String>> {
        val now = System.currentTimeMillis()
        if (now - persistedTimestamp < CACHE_TTL_MS) {
            val persisted = readPersistedSeriesTitles()
            if (persisted.isNotEmpty()) return Result.success(persisted)
        }
        seriesCache?.let { if (now - it.timestamp < CACHE_TTL_MS) return Result.success(it.titles) }
        return try {
            val titles = justwatchClient.getPopularSeriesTitles()
                .filterNot { it.contains("podcast", ignoreCase = true) }
            seriesCache = TitlesCache(titles, now)
            persist(titles = null, seriesTitles = titles, timestamp = now)
            Result.success(titles)
        } catch (e: Exception) {
            val persisted = readPersistedSeriesTitles()
            if (persisted.isNotEmpty()) return Result.success(persisted)
            Result.success(seriesCache?.titles ?: emptyList())
        }
    }

    private fun persist(titles: List<String>?, seriesTitles: List<String>?, timestamp: Long) {
        try {
            val existing = if (cacheFile.exists()) {
                cacheFile.readLines()
            } else emptyList()
            val movieLine = titles?.joinToString(",") ?: existing.getOrNull(0) ?: ""
            val seriesLine = seriesTitles?.joinToString(",") ?: existing.getOrNull(1) ?: ""
            cacheFile.writeText("$movieLine\n$seriesLine\n$timestamp")
        } catch (_: Exception) { }
    }

    private fun readPersistedMovieTitles(): List<String> =
        try { cacheFile.readLines().getOrNull(0)?.split(",")?.filter { it.isNotBlank() } ?: emptyList() } catch (_: Exception) { emptyList() }

    private fun readPersistedSeriesTitles(): List<String> =
        try { cacheFile.readLines().getOrNull(1)?.split(",")?.filter { it.isNotBlank() } ?: emptyList() } catch (_: Exception) { emptyList() }

    private fun readPersistedTimestamp(): Long =
        try { cacheFile.readLines().getOrNull(2)?.toLongOrNull() ?: 0L } catch (_: Exception) { 0L }

    private data class TitlesCache(
        val titles: List<String>,
        val timestamp: Long
    )

    companion object {
        private const val CACHE_TTL_MS = 86_400_000L
    }
}
