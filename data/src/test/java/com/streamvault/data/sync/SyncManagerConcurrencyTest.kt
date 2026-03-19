package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.parser.M3uParser
import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamEpgResponse
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.dto.XtreamVodInfoResponse
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SyncManagerConcurrencyTest {

    @Test
    fun `sync serializes concurrent provider refreshes`() = runTest {
        val providerDao = MultiProviderDao(
            mapOf(
                1L to sampleProvider(1L, "alpha"),
                2L to sampleProvider(2L, "beta")
            )
        )
        val xtreamApi = BlockingXtreamApiService()
        val channelDao: ChannelDao = mock()
        val movieDao: MovieDao = mock()
        val seriesDao: SeriesDao = mock()
        val categoryDao: CategoryDao = mock()
        val epgRepository: EpgRepository = mock()
        val syncMetadataRepository: SyncMetadataRepository = mock()
        val okHttpClient: OkHttpClient = mock()

        whenever(syncMetadataRepository.getMetadata(1L)).thenReturn(null)
        whenever(syncMetadataRepository.getMetadata(2L)).thenReturn(null)
        doNothing().whenever(syncMetadataRepository).updateMetadata(org.mockito.kotlin.any())
        whenever(epgRepository.refreshEpg(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(Result.success(Unit))

        val manager = SyncManager(
            providerDao = providerDao,
            channelDao = channelDao,
            movieDao = movieDao,
            seriesDao = seriesDao,
            categoryDao = categoryDao,
            xtreamApiService = xtreamApi,
            m3uParser = M3uParser(),
            epgRepository = epgRepository,
            okHttpClient = okHttpClient,
            syncMetadataRepository = syncMetadataRepository
        )

        val firstSync = async { manager.sync(1L, force = true) }
        xtreamApi.firstLiveCategoriesStarted.await()

        val secondSync = async { manager.sync(2L, force = true) }
        kotlinx.coroutines.yield()

        assertThat(xtreamApi.liveCategoryRequests).containsExactly("alpha")

        xtreamApi.allowFirstLiveCategories.complete(Unit)

        assertThat(firstSync.await().isSuccess).isTrue()
        assertThat(secondSync.await().isSuccess).isTrue()
        assertThat(xtreamApi.liveCategoryRequests).containsExactly("alpha", "beta").inOrder()
    }

    private class MultiProviderDao(
        private val providers: Map<Long, ProviderEntity>
    ) : ProviderDao() {
        override suspend fun getById(id: Long): ProviderEntity? = providers[id]
        override suspend fun updateSyncTime(id: Long, timestamp: Long) = Unit
        override fun getAll() = kotlinx.coroutines.flow.flowOf(providers.values.toList())
        override fun getActive() = kotlinx.coroutines.flow.flowOf(providers.values.firstOrNull())
        override suspend fun insert(entity: ProviderEntity): Long = entity.id
        override suspend fun update(entity: ProviderEntity) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun deactivateAll() = Unit
        override suspend fun activate(id: Long) = Unit
        override suspend fun getByUrlAndUser(url: String, user: String): ProviderEntity? = null
        override suspend fun updateEpgUrl(id: Long, epgUrl: String) = Unit
        override suspend fun setActive(id: Long) = Unit
    }

    private class BlockingXtreamApiService : XtreamApiService {
        val firstLiveCategoriesStarted = CompletableDeferred<Unit>()
        val allowFirstLiveCategories = CompletableDeferred<Unit>()
        val liveCategoryRequests = mutableListOf<String>()

        override suspend fun authenticate(endpoint: String): XtreamAuthResponse {
            throw UnsupportedOperationException()
        }

        override suspend fun getLiveCategories(endpoint: String): List<XtreamCategory> {
            val marker = when {
                endpoint.contains("alpha") -> "alpha"
                endpoint.contains("beta") -> "beta"
                else -> endpoint
            }
            liveCategoryRequests += marker
            if (liveCategoryRequests.size == 1) {
                firstLiveCategoriesStarted.complete(Unit)
                allowFirstLiveCategories.await()
            }
            return emptyList()
        }

        override suspend fun getLiveStreams(endpoint: String): List<XtreamStream> = emptyList()

        override suspend fun getVodCategories(endpoint: String): List<XtreamCategory> = emptyList()

        override suspend fun getVodStreams(endpoint: String): List<XtreamStream> = emptyList()

        override suspend fun getVodInfo(endpoint: String): XtreamVodInfoResponse {
            throw UnsupportedOperationException()
        }

        override suspend fun getSeriesCategories(endpoint: String): List<XtreamCategory> = emptyList()

        override suspend fun getSeriesList(endpoint: String): List<XtreamSeriesItem> = emptyList()

        override suspend fun getSeriesInfo(endpoint: String): XtreamSeriesInfoResponse {
            throw UnsupportedOperationException()
        }

        override suspend fun getShortEpg(endpoint: String): XtreamEpgResponse {
            throw UnsupportedOperationException()
        }

        override suspend fun getFullEpg(endpoint: String): XtreamEpgResponse {
            throw UnsupportedOperationException()
        }
    }

    private companion object {
        fun sampleProvider(id: Long, username: String) = ProviderEntity(
            id = id,
            name = "Provider $username",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://$username.example.com",
            username = username,
            password = "password",
            status = ProviderStatus.UNKNOWN
        )
    }
}