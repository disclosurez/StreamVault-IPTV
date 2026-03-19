package com.streamvault.app.cast

data class CastMediaRequest(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val mimeType: String? = null,
    val isLive: Boolean = false,
    val startPositionMs: Long = 0L
)

enum class CastConnectionState {
    UNAVAILABLE,
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

enum class CastStartResult {
    STARTED,
    ROUTE_SELECTION_REQUIRED,
    UNAVAILABLE,
    UNSUPPORTED
}