package com.streamvault.app.ui.screens.player

import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerStats

internal fun shouldRenewAdoptedPreviewOnFullscreen(
    playbackState: PlaybackState,
    playerStats: PlayerStats
): Boolean {
    if (playerStats.ttffMs <= 0L) return true
    return playbackState != PlaybackState.READY && playbackState != PlaybackState.BUFFERING
}
