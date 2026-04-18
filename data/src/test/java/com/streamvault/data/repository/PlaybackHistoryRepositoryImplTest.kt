package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.PlaybackWatchedStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PlaybackHistoryRepositoryImplTest {

    private val historyDao: PlaybackHistoryDao = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val movieDao: MovieDao = mock()
    private val episodeDao: EpisodeDao = mock()
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private fun repository() = PlaybackHistoryRepositoryImpl(
        dao = historyDao,
        preferencesRepository = preferencesRepository,
        movieDao = movieDao,
        episodeDao = episodeDao,
        transactionRunner = transactionRunner
    )

    private fun movieHistory(
        resumePositionMs: Long = 1_000L,
        totalDurationMs: Long = 10_000L
    ) = PlaybackHistory(
        contentId = 10L,
        contentType = ContentType.MOVIE,
        providerId = 5L,
        title = "Movie",
        streamUrl = "https://provider.example.com/movie",
        resumePositionMs = resumePositionMs,
        totalDurationMs = totalDurationMs,
        watchedStatus = PlaybackWatchedStatus.IN_PROGRESS
    )

    @Test
    fun `clearAllHistory resets denormalized movie and episode progress directly`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val repository = repository()

        val result = repository.clearAllHistory()

        assertThat(result.isSuccess).isTrue()
        verify(historyDao).deleteAll()
        verify(movieDao).resetAllWatchProgress()
        verify(episodeDao).resetAllWatchProgress()
    }

    @Test
    fun `updateResumePosition buffers writes until later flush point`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val repository = repository()

        val result = repository.updateResumePosition(movieHistory())

        assertThat(result.isSuccess).isTrue()
        verify(historyDao, never()).insertOrUpdate(org.mockito.kotlin.any())
        verify(movieDao, never()).syncWatchProgressFromHistory(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `recordPlayback flushes buffered resume update as one explicit write`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        val repository = repository()

        repository.updateResumePosition(movieHistory(resumePositionMs = 2_000L))
        val result = repository.recordPlayback(movieHistory(resumePositionMs = 3_000L))

        assertThat(result.isSuccess).isTrue()
        verify(historyDao).insertOrUpdate(org.mockito.kotlin.check {
            assertThat(it.resumePositionMs).isEqualTo(3_000L)
            assertThat(it.watchCount).isEqualTo(2)
        })
        verify(movieDao).syncWatchProgressFromHistory(10L, 5L)
    }

    @Test
    fun `getPlaybackHistory falls back to shared tmdb movie history across providers`() = runTest {
        whenever(preferencesRepository.isIncognitoMode).thenReturn(flowOf(false))
        whenever(historyDao.get(10L, ContentType.MOVIE.name, 5L)).thenReturn(null)
        whenever(historyDao.getLatestMovieHistoryBySharedTmdb(10L, 5L)).thenReturn(
            PlaybackHistoryEntity(
                contentId = 42L,
                contentType = ContentType.MOVIE,
                providerId = 9L,
                title = "Movie",
                resumePositionMs = 4_000L,
                totalDurationMs = 10_000L,
                lastWatchedAt = 123L
            )
        )

        val result = repository().getPlaybackHistory(10L, ContentType.MOVIE, 5L)

        assertThat(result).isNotNull()
        assertThat(result?.providerId).isEqualTo(9L)
        assertThat(result?.resumePositionMs).isEqualTo(4_000L)
    }
}
