package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.VirtualGroupDao
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteRepositoryImplTest {

    private val favoriteDao: FavoriteDao = mock()
    private val virtualGroupDao: VirtualGroupDao = mock {
        on { getByType(any(), any()) } doAnswer { emptyFlow() }
    }
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private val repository = FavoriteRepositoryImpl(
        favoriteDao = favoriteDao,
        virtualGroupDao = virtualGroupDao,
        transactionRunner = transactionRunner
    )

    @Test
    fun `addFavorite runs max-position lookup and insert in one transaction`() = runTest {
        var inTransaction = false
        var getMaxInsideTransaction = false
        var insertInsideTransaction = false

        val transactionRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                check(!inTransaction)
                inTransaction = true
                return try {
                    block()
                } finally {
                    inTransaction = false
                }
            }
        }

        whenever(favoriteDao.getMaxPosition(7L, null)).thenAnswer {
            getMaxInsideTransaction = inTransaction
            4
        }
        whenever(favoriteDao.insert(any())).thenAnswer {
            insertInsideTransaction = inTransaction
            Unit
        }

        val repository = FavoriteRepositoryImpl(
            favoriteDao = favoriteDao,
            virtualGroupDao = virtualGroupDao,
            transactionRunner = transactionRunner
        )

        val result = repository.addFavorite(
            providerId = 7L,
            contentId = 42L,
            contentType = ContentType.LIVE,
            groupId = null
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(getMaxInsideTransaction).isTrue()
        assertThat(insertInsideTransaction).isTrue()

        val favoriteCaptor = argumentCaptor<FavoriteEntity>()
        verify(favoriteDao).insert(favoriteCaptor.capture())
        verify(favoriteDao).getMaxPosition(7L, null)
        assertThat(favoriteCaptor.firstValue.providerId).isEqualTo(7L)
        assertThat(favoriteCaptor.firstValue.position).isEqualTo(5)
    }

    @Test
    fun `reorderFavorites updates only affected favorites when sparse gap is available`() = runTest {
        val favorites = listOf(
            favorite(id = 1, contentId = 101, position = 0),
            favorite(id = 2, contentId = 102, position = 1_024),
            favorite(id = 3, contentId = 103, position = 2_048),
            favorite(id = 4, contentId = 104, position = 3_072)
        )

        val result = repository.reorderFavorites(
            listOf(favorites[0], favorites[2], favorites[1], favorites[3])
        )

        assertThat(result.isSuccess).isTrue()
        val favoritesCaptor = argumentCaptor<List<FavoriteEntity>>()
        verify(favoriteDao).updateAll(favoritesCaptor.capture())
        assertThat(favoritesCaptor.firstValue.map(FavoriteEntity::id)).containsExactly(3L, 2L).inOrder()
        assertThat(favoritesCaptor.firstValue.map(FavoriteEntity::position)).containsExactly(341, 682).inOrder()
    }

    @Test
    fun `reorderFavorites normalizes whole list when dense positions leave no gap`() = runTest {
        val favorites = listOf(
            favorite(id = 1, contentId = 101, position = 0),
            favorite(id = 2, contentId = 102, position = 1),
            favorite(id = 3, contentId = 103, position = 2),
            favorite(id = 4, contentId = 104, position = 3)
        )

        val result = repository.reorderFavorites(
            listOf(favorites[1], favorites[0], favorites[2], favorites[3])
        )

        assertThat(result.isSuccess).isTrue()
        val favoritesCaptor = argumentCaptor<List<FavoriteEntity>>()
        verify(favoriteDao).updateAll(favoritesCaptor.capture())
        assertThat(favoritesCaptor.firstValue.map(FavoriteEntity::id)).containsExactly(2L, 1L, 3L, 4L).inOrder()
        assertThat(favoritesCaptor.firstValue.map(FavoriteEntity::position))
            .containsExactly(0, 1_024, 2_048, 3_072)
            .inOrder()
    }

    @Test
    fun `reorderFavorites skips writes when order is unchanged`() = runTest {
        val favorites = listOf(
            favorite(id = 1, contentId = 101, position = 0),
            favorite(id = 2, contentId = 102, position = 1_024),
            favorite(id = 3, contentId = 103, position = 2_048)
        )

        val result = repository.reorderFavorites(favorites)

        assertThat(result.isSuccess).isTrue()
        verify(favoriteDao, never()).updateAll(any())
    }

    private fun favorite(id: Long, contentId: Long, position: Int) = Favorite(
        id = id,
        providerId = 7L,
        contentId = contentId,
        contentType = ContentType.LIVE,
        position = position
    )
}
