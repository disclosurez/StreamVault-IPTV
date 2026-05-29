package com.streamvault.player.playback

internal data class PlaybackBufferPolicy(
    val label: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferMs: Int
)

internal object PlaybackBufferPolicies {
    private const val LOW_MEMORY_LIVE_MIN_BUFFER_MS = 4_000
    private const val LOW_MEMORY_LIVE_MAX_BUFFER_MS = 12_000
    private const val LOW_MEMORY_COMPAT_LIVE_MIN_BUFFER_MS = 6_000
    private const val LOW_MEMORY_COMPAT_LIVE_MAX_BUFFER_MS = 15_000
    private const val LOW_MEMORY_VOD_MIN_BUFFER_MS = 15_000
    private const val LOW_MEMORY_VOD_MAX_BUFFER_MS = 45_000
    private const val LOW_MEMORY_PLAYBACK_BUFFER_MS = 1_000
    private const val LOW_MEMORY_REBUFFER_MS = 3_000
    private const val LIVE_MIN_BUFFER_MS = 8_000
    private const val LIVE_MAX_BUFFER_MS = 30_000
    private const val COMPAT_LIVE_MIN_BUFFER_MS = 15_000
    private const val COMPAT_LIVE_MAX_BUFFER_MS = 45_000
    private const val VOD_MIN_BUFFER_MS = 90_000
    private const val VOD_MAX_BUFFER_MS = 240_000
    private const val PLAYBACK_BUFFER_MS = 1_500
    private const val REBUFFER_MS = 5_000
    private const val VOD_PLAYBACK_BUFFER_MS = 8_000
    private const val VOD_REBUFFER_MS = 18_000

    fun forPlayback(isLive: Boolean, compatibilityMode: Boolean, lowMemoryDevice: Boolean): PlaybackBufferPolicy = when {
        lowMemoryDevice && compatibilityMode && isLive ->
            PlaybackBufferPolicy("lowmem-compat-live", LOW_MEMORY_COMPAT_LIVE_MIN_BUFFER_MS, LOW_MEMORY_COMPAT_LIVE_MAX_BUFFER_MS, LOW_MEMORY_PLAYBACK_BUFFER_MS, LOW_MEMORY_REBUFFER_MS)
        lowMemoryDevice && isLive ->
            PlaybackBufferPolicy("lowmem-live", LOW_MEMORY_LIVE_MIN_BUFFER_MS, LOW_MEMORY_LIVE_MAX_BUFFER_MS, LOW_MEMORY_PLAYBACK_BUFFER_MS, LOW_MEMORY_REBUFFER_MS)
        lowMemoryDevice ->
            PlaybackBufferPolicy("lowmem-vod", LOW_MEMORY_VOD_MIN_BUFFER_MS, LOW_MEMORY_VOD_MAX_BUFFER_MS, LOW_MEMORY_PLAYBACK_BUFFER_MS, LOW_MEMORY_REBUFFER_MS)
        compatibilityMode && isLive ->
            PlaybackBufferPolicy("compat-live", COMPAT_LIVE_MIN_BUFFER_MS, COMPAT_LIVE_MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
        isLive ->
            PlaybackBufferPolicy("stable-live", LIVE_MIN_BUFFER_MS, LIVE_MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
        else ->
            PlaybackBufferPolicy("stable-vod", VOD_MIN_BUFFER_MS, VOD_MAX_BUFFER_MS, VOD_PLAYBACK_BUFFER_MS, VOD_REBUFFER_MS)
    }
}
