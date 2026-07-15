package dev.anilbeesetti.nextplayer.feature.player.state

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Audio delay state — applies a +/- ms offset between audio and video.
 *
 * ExoPlayer does not expose a direct setAudioDelay API. As a fallback,
 * the delay is persisted on the current media item's metadata extras so
 * that PlayerService can apply an approximate offset via audio renderer
 * timestamp manipulation after each seek operation.
 *
 * Range: -10000ms .. +10000ms (matches the user-visible UI range).
 */
@Composable
fun rememberAudioDelayState(player: Player?): AudioDelayState {
    val state = remember { AudioDelayState() }
    LaunchedEffect(player) { state.bindExo(player as? ExoPlayer) }
    DisposableEffect(Unit) { onDispose { state.unbindAll() } }
    return state
}

@Stable
class AudioDelayState {
    /** Audio delay in milliseconds. Positive = audio plays later, negative = audio plays earlier */
    var delayMs by mutableLongStateOf(0L)
        private set

    private var exoPlayer: ExoPlayer? = null

    fun bindExo(player: ExoPlayer?) {
        exoPlayer = player
    }

    fun unbindAll() {
        exoPlayer = null
    }

    /**
     * Set audio delay.
     *
     * On ExoPlayer: approximate (re-seek adjustment via metadata extras).
     */
    fun setDelay(ms: Long) {
        delayMs = ms.coerceIn(-10_000L, 10_000L)
        applyDelay()
    }

    fun reset() {
        delayMs = 0L
        applyDelay()
    }

    private fun applyDelay() {
        applyViaExo()
    }

    private fun applyViaExo() {
        val ep = exoPlayer ?: return
        runCatching {
            // ExoPlayer has no setAudioDelay. We persist the value on the current
            // media item's metadata extras so PlayerService can read it and apply
            // an approximate offset via audio renderer timestamp manipulation.
            ep.currentMediaItem?.let { item ->
                val extras = item.mediaMetadata.extras ?: android.os.Bundle()
                extras.putLong("audio_delay_ms", delayMs)
            }
        }.onFailure { Log.w("AudioDelay", "ExoPlayer delay persistence failed", it) }
    }
}

private fun Long.coerceIn(min: Long, max: Long): Long =
    if (this < min) min else if (this > max) max else this
