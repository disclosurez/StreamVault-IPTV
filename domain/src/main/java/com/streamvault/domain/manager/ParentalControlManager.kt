package com.streamvault.domain.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ParentalControlManager @Inject constructor(
    private val sessionStore: ParentalControlSessionStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private var expirationJob: Job? = null
    private val _sessionState = MutableStateFlow(normalizeState(sessionStore.readSessionState(), currentTimeMs()))
    private val _unlockedCategoriesByProvider = MutableStateFlow<Map<Long, Set<Long>>>(emptyMap())
    val unlockedCategoriesByProvider: StateFlow<Map<Long, Set<Long>>> =
        _unlockedCategoriesByProvider.asStateFlow()

    val unlockTimeoutMs: StateFlow<Long>
        get() = MutableStateFlow(_sessionState.value.unlockTimeoutMs).asStateFlow()

    init {
        publishState(_sessionState.value, persist = true, nowMs = currentTimeMs())
    }

    fun unlockedCategoriesForProvider(providerId: Long) =
        unlockedCategoriesByProvider.map { it[providerId] ?: emptySet() }

    fun unlockCategory(providerId: Long, categoryId: Long, nowMs: Long = currentTimeMs()) {
        synchronized(stateLock) {
            val state = refreshLocked(nowMs)
            val providerEntries = state.unlockedCategoryExpirationsByProvider[providerId].orEmpty() +
                (categoryId to (nowMs + state.unlockTimeoutMs))
            publishState(
                state.copy(
                    unlockedCategoryExpirationsByProvider = state.unlockedCategoryExpirationsByProvider +
                        (providerId to providerEntries)
                ),
                nowMs = nowMs
            )
        }
    }

    fun isCategoryUnlocked(providerId: Long, categoryId: Long, nowMs: Long = currentTimeMs()): Boolean {
        synchronized(stateLock) {
            val state = refreshLocked(nowMs)
            return state.unlockedCategoryExpirationsByProvider[providerId]?.get(categoryId)?.let { it > nowMs } == true
        }
    }

    fun setUnlockTimeout(timeoutMs: Long) {
        synchronized(stateLock) {
            val state = refreshLocked(currentTimeMs())
            publishState(state.copy(unlockTimeoutMs = normalizeTimeout(timeoutMs)), nowMs = currentTimeMs())
        }
    }

    fun refreshSessionState(nowMs: Long = currentTimeMs()) {
        synchronized(stateLock) {
            publishState(normalizeState(sessionStore.readSessionState(), nowMs), persist = true, nowMs = nowMs)
        }
    }

    fun clearUnlockedCategories(providerId: Long? = null) {
        synchronized(stateLock) {
            val state = refreshLocked(currentTimeMs())
            val updatedUnlocks = if (providerId == null) {
                emptyMap()
            } else {
                state.unlockedCategoryExpirationsByProvider - providerId
            }
            publishState(state.copy(unlockedCategoryExpirationsByProvider = updatedUnlocks), nowMs = currentTimeMs())
        }
    }

    private fun refreshLocked(nowMs: Long): ParentalControlSessionState {
        val normalized = normalizeState(_sessionState.value, nowMs)
        if (normalized != _sessionState.value) {
            publishState(normalized, nowMs = nowMs)
        }
        return normalized
    }

    private fun publishState(
        state: ParentalControlSessionState,
        persist: Boolean = true,
        nowMs: Long = currentTimeMs()
    ) {
        val normalized = normalizeState(state, nowMs)
        _sessionState.value = normalized
        _unlockedCategoriesByProvider.value = normalized.unlockedCategoryExpirationsByProvider
            .mapValues { (_, categories) -> categories.keys }
            .filterValues { it.isNotEmpty() }

        if (persist) {
            sessionStore.writeSessionState(normalized)
        }
        scheduleExpiration(normalized)
    }

    private fun scheduleExpiration(state: ParentalControlSessionState) {
        expirationJob?.cancel()
        val nextExpiration = state.unlockedCategoryExpirationsByProvider.values
            .flatMap { it.values }
            .minOrNull()
            ?: return
        val delayMs = nextExpiration - currentTimeMs()
        if (delayMs <= 0L) {
            refreshSessionState()
            return
        }
        expirationJob = scope.launch {
            delay(delayMs)
            refreshSessionState()
        }
    }

    private fun normalizeState(state: ParentalControlSessionState, nowMs: Long): ParentalControlSessionState {
        val prunedUnlocks = state.unlockedCategoryExpirationsByProvider
            .mapValues { (_, categories) ->
                categories.filterValues { expiresAt -> expiresAt > nowMs }
            }
            .filterValues { it.isNotEmpty() }

        return state.copy(
            unlockedCategoryExpirationsByProvider = prunedUnlocks,
            unlockTimeoutMs = normalizeTimeout(state.unlockTimeoutMs)
        )
    }

    private fun normalizeTimeout(timeoutMs: Long): Long =
        timeoutMs.coerceAtLeast(ParentalControlSessionState.MIN_UNLOCK_TIMEOUT_MS)

    private fun currentTimeMs(): Long = System.currentTimeMillis()
}
