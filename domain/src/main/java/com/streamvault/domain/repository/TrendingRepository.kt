package com.streamvault.domain.repository

import com.streamvault.domain.model.Result

interface TrendingRepository {
    suspend fun getTrendingMovieTitles(): Result<List<String>>
    suspend fun getTrendingSeriesTitles(): Result<List<String>>
}
