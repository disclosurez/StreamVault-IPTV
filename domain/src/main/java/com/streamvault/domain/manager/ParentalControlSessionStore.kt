package com.streamvault.domain.manager

data class ParentalControlSessionState(
    val unlockedCategoryExpirationsByProvider: Map<Long, Map<Long, Long>> = emptyMap(),
    val unlockTimeoutMs: Long = DEFAULT_UNLOCK_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_UNLOCK_TIMEOUT_MS: Long = 30 * 60 * 1000L
        const val MIN_UNLOCK_TIMEOUT_MS: Long = 60 * 1000L
    }
}

interface ParentalControlSessionStore {
    fun readSessionState(): ParentalControlSessionState
    fun writeSessionState(state: ParentalControlSessionState)
}