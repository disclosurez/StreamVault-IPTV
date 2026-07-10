package com.streamvault.data.remote.justwatch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JustwatchClient @Inject constructor() {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private val titlePattern = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]+)\"")

    suspend fun getPopularMovieTitles(country: String = "us", limit: Int = 50): List<String> {
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val url = "https://www.justwatch.com/$country/movies?release_year_from=${currentYear - 1}"
        return fetchTitles(url, limit)
    }

    suspend fun getPopularSeriesTitles(country: String = "us", limit: Int = 50): List<String> {
        val url = "https://www.justwatch.com/$country/tv-shows"
        return fetchTitles(url, limit)
    }

    private suspend fun fetchTitles(url: String, limit: Int): List<String> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()

        val html = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext ""
                response.body?.string() ?: ""
            }
        }

        if (html.isBlank()) return emptyList()

        val matcher = titlePattern.matcher(html)
        val titles = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        while (matcher.find() && titles.size < limit) {
            val title = matcher.group(1)
            if (title !in seen) {
                seen.add(title)
                titles.add(title)
            }
        }
        return titles
    }
}
