package com.streamvault.data.repository

import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.PlaybackHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamUrlFactory

@Singleton
class PlaybackHistoryRepositoryImpl @Inject constructor(
    private val dao: PlaybackHistoryDao,
    private val preferencesRepository: PreferencesRepository
) : PlaybackHistoryRepository {

    override fun getRecentlyWatched(limit: Int): Flow<List<PlaybackHistory>> {
        return dao.getRecentlyWatched(limit).map { list -> list.map { it.toDomain() } }
    }

    override fun getRecentlyWatchedByProvider(providerId: Long, limit: Int): Flow<List<PlaybackHistory>> {
        return dao.getRecentlyWatchedByProvider(providerId, limit).map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getPlaybackHistory(contentId: Long, contentType: ContentType, providerId: Long): PlaybackHistory? {
        return dao.get(contentId, contentType.name, providerId)?.toDomain()
    }

    override suspend fun recordPlayback(history: PlaybackHistory): Result<Unit> {
        return try {
            if (preferencesRepository.isIncognitoMode.first()) {
                return Result.success(Unit)
            }

            val existing = dao.get(history.contentId, history.contentType.name, history.providerId)
            val updatedHistory = history.copy(
                streamUrl = XtreamUrlFactory.sanitizePersistedStreamUrl(history.streamUrl, history.providerId),
                resumePositionMs = history.resumePositionMs.takeIf { it > 0L } ?: existing?.resumePositionMs ?: 0L,
                totalDurationMs = history.totalDurationMs.takeIf { it > 0L } ?: existing?.totalDurationMs ?: 0L,
                watchCount = (existing?.watchCount ?: 0) + 1,
                lastWatchedAt = System.currentTimeMillis()
            )
            dao.insertOrUpdate(updatedHistory.toEntity())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Failed to record playback history", e)
        }
    }

    override suspend fun updateResumePosition(history: PlaybackHistory): Result<Unit> {
        return try {
            if (preferencesRepository.isIncognitoMode.first()) {
                return Result.success(Unit)
            }

            val existing = dao.get(history.contentId, history.contentType.name, history.providerId)

            val updatedHistory = history.copy(
                streamUrl = XtreamUrlFactory.sanitizePersistedStreamUrl(history.streamUrl, history.providerId),
                watchCount = existing?.watchCount ?: 1,
                lastWatchedAt = System.currentTimeMillis()
            )
            dao.insertOrUpdate(updatedHistory.toEntity())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error("Failed to update playback resume position", e)
        }
    }

    override suspend fun removeFromHistory(contentId: Long, contentType: ContentType, providerId: Long): Result<Unit> = try {
        dao.delete(contentId, contentType.name, providerId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to remove playback history item", e)
    }

    override suspend fun clearAllHistory(): Result<Unit> = try {
        dao.deleteAll()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to clear playback history", e)
    }

    override suspend fun clearHistoryForProvider(providerId: Long): Result<Unit> = try {
        dao.deleteByProvider(providerId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to clear provider playback history", e)
    }
}
