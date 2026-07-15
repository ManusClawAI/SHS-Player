package dev.anilbeesetti.nextplayer.feature.player.extensions

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import dev.anilbeesetti.nextplayer.core.common.Logger

/**
 * Switches to selected track.
 *
 * Note: VLC has its own track selection system (not exposed via Media3 Player
 * interface). With VlcPlayerAdapter, currentTracks returns Tracks.EMPTY, so
 * this is a no-op until VLC track enumeration is implemented.
 *
 * @param trackType The type of track to switch.
 * @param trackIndex The index of the track to switch to, or null to enable the track.
 */
fun Player.switchTrack(trackType: @C.TrackType Int, trackIndex: Int) {
    val trackTypeText = when (trackType) {
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_TEXT -> "subtitle"
        else -> throw IllegalArgumentException("Invalid track type: $trackType")
    }
    Logger.logDebug("Player", "switchTrack($trackTypeText, $trackIndex) — no-op for VLC backend")
}

@UnstableApi
fun Player.getManuallySelectedTrackIndex(trackType: @C.TrackType Int): Int? {
    // VLC manages tracks internally — return null (no manual selection tracked)
    return null
}

fun Player.addAdditionalSubtitleConfiguration(subtitle: MediaItem.SubtitleConfiguration) {
    // VLC uses its own subtitle system via VlcPlayerEngine.addSubtitleTrack(uri)
    // This is handled directly in PlayerActivity.onSelectSubtitleClick
}

@OptIn(UnstableApi::class)
fun Player.setIsScrubbingModeEnabled(enabled: Boolean) {
    // VLC doesn't have a scrubbing mode concept — playback is continuous during seeks.
    // No-op.
}
