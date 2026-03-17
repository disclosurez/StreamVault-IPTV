package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.VirtualGroupDao
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteRepositoryImplTest {

    private val favoriteDao: FavoriteDao = mock()
    private val virtualGroupDao: VirtualGroupDao = mock {
        on { getByType(any()) } doAnswer { emptyFlow() }
    }

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

        whenever(favoriteDao.getMaxPosition(null)).thenAnswer {
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
            contentId = 42L,
            contentType = ContentType.LIVE,
            groupId = null
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(getMaxInsideTransaction).isTrue()
        assertThat(insertInsideTransaction).isTrue()

        val favoriteCaptor = argumentCaptor<FavoriteEntity>()
        verify(favoriteDao).insert(favoriteCaptor.capture())
        assertThat(favoriteCaptor.firstValue.position).isEqualTo(5)
    }
}