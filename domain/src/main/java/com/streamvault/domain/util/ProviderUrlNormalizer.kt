package com.streamvault.domain.util

object ProviderUrlNormalizer {
    private const val DEFAULT_REMOTE_SCHEME = "https"

    fun normalizeRemoteUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return trimmed
        if (hasScheme(trimmed)) return trimmed
        return when {
            trimmed.startsWith("//") -> "$DEFAULT_REMOTE_SCHEME:$trimmed"
            else -> "$DEFAULT_REMOTE_SCHEME://$trimmed"
        }
    }

    private fun hasScheme(url: String): Boolean {
        return runCatching { java.net.URI(url).scheme }
            .getOrNull()
            ?.isNotBlank() == true
    }
}
