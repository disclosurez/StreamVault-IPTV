package com.streamvault.domain.manager

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class ParentalControlManagerTest {

    private lateinit var manager: ParentalControlManager
    private lateinit var store: FakeParentalControlSessionStore
    private var baseNow: Long = 0L

    @Before
    fun setup() {
        store = FakeParentalControlSessionStore()
        manager = ParentalControlManager(store)
        baseNow = System.currentTimeMillis() + 10_000L
    }

    @Test
    fun `initially no categories are unlocked`() {
        assertThat(manager.isCategoryUnlocked(1L, 1L)).isFalse()
        assertThat(manager.unlockedCategoriesByProvider.value).isEmpty()
    }

    @Test
    fun `unlockCategory makes category accessible`() {
        manager.unlockCategory(1L, 100L, nowMs = baseNow)
        assertThat(manager.isCategoryUnlocked(1L, 100L, nowMs = baseNow + 1)).isTrue()
    }

    @Test
    fun `unlockCategory is scoped to provider`() {
        manager.unlockCategory(1L, 100L, nowMs = baseNow)
        assertThat(manager.isCategoryUnlocked(1L, 100L, nowMs = baseNow + 1)).isTrue()
        assertThat(manager.isCategoryUnlocked(2L, 100L, nowMs = baseNow + 1)).isFalse()
    }

    @Test
    fun `multiple categories can be unlocked per provider`() {
        manager.unlockCategory(1L, 100L, nowMs = baseNow)
        manager.unlockCategory(1L, 200L, nowMs = baseNow)
        assertThat(manager.isCategoryUnlocked(1L, 100L, nowMs = baseNow + 1)).isTrue()
        assertThat(manager.isCategoryUnlocked(1L, 200L, nowMs = baseNow + 1)).isTrue()
    }

    @Test
    fun `clearUnlockedCategories with provider clears that provider only`() {
        manager.unlockCategory(1L, 100L, nowMs = baseNow)
        manager.unlockCategory(2L, 200L, nowMs = baseNow)

        manager.clearUnlockedCategories(1L)

        assertThat(manager.isCategoryUnlocked(1L, 100L, nowMs = baseNow + 1)).isFalse()
        assertThat(manager.isCategoryUnlocked(2L, 200L, nowMs = baseNow + 1)).isTrue()
    }

    @Test
    fun `clearUnlockedCategories without provider clears all`() {
        manager.unlockCategory(1L, 100L, nowMs = baseNow)
        manager.unlockCategory(2L, 200L, nowMs = baseNow)

        manager.clearUnlockedCategories()

        assertThat(manager.isCategoryUnlocked(1L, 100L, nowMs = baseNow + 1)).isFalse()
        assertThat(manager.isCategoryUnlocked(2L, 200L, nowMs = baseNow + 1)).isFalse()
        assertThat(manager.unlockedCategoriesByProvider.value).isEmpty()
    }

    @Test
    fun `unlockedCategoriesForProvider emits correct set`() = runTest {
        manager.unlockCategory(1L, 100L, nowMs = baseNow)
        manager.unlockCategory(1L, 200L, nowMs = baseNow)

        val unlocked = manager.unlockedCategoriesForProvider(1L).first()
        assertThat(unlocked).containsExactly(100L, 200L)
    }

    @Test
    fun `unlockedCategoriesForProvider returns empty for unknown provider`() = runTest {
        val unlocked = manager.unlockedCategoriesForProvider(99L).first()
        assertThat(unlocked).isEmpty()
    }

    @Test
    fun `unlocking same category twice is idempotent`() {
        manager.unlockCategory(1L, 100L, nowMs = baseNow)
        manager.unlockCategory(1L, 100L, nowMs = baseNow + 1_000L)
        assertThat(manager.isCategoryUnlocked(1L, 100L, nowMs = baseNow + 1_001L)).isTrue()
        assertThat(manager.unlockedCategoriesByProvider.value[1L]).hasSize(1)
    }

    @Test
    fun `refreshSessionState prunes expired unlocks from persisted store`() {
        store.state = ParentalControlSessionState(
            unlockedCategoryExpirationsByProvider = mapOf(
                1L to mapOf(
                    100L to (baseNow - 500L),
                    200L to (baseNow + 2_000L)
                )
            )
        )

        manager.refreshSessionState(nowMs = baseNow)

        assertThat(manager.isCategoryUnlocked(1L, 100L, nowMs = baseNow)).isFalse()
        assertThat(manager.isCategoryUnlocked(1L, 200L, nowMs = baseNow)).isTrue()
        assertThat(store.state.unlockedCategoryExpirationsByProvider[1L]).containsKey(200L)
        assertThat(store.state.unlockedCategoryExpirationsByProvider[1L]).doesNotContainKey(100L)
    }

    @Test
    fun `setUnlockTimeout persists minimum normalized timeout`() {
        manager.setUnlockTimeout(5_000L)

        assertThat(store.state.unlockTimeoutMs).isEqualTo(ParentalControlSessionState.MIN_UNLOCK_TIMEOUT_MS)
    }

    @Test
    fun `unlockCategory expires after timeout`() {
        manager.setUnlockTimeout(60_000L)
        manager.unlockCategory(1L, 100L, nowMs = baseNow)

        assertThat(manager.isCategoryUnlocked(1L, 100L, nowMs = baseNow + 59_999L)).isTrue()
        assertThat(manager.isCategoryUnlocked(1L, 100L, nowMs = baseNow + 60_000L)).isFalse()
    }

    private class FakeParentalControlSessionStore : ParentalControlSessionStore {
        var state: ParentalControlSessionState = ParentalControlSessionState()

        override fun readSessionState(): ParentalControlSessionState = state

        override fun writeSessionState(state: ParentalControlSessionState) {
            this.state = state
        }
    }
}
