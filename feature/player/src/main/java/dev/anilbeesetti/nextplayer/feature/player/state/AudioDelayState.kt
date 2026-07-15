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
import dev.anilbeesetti.nextplayer.feature.player.engine.VlcPlayerAdapter

/**
 * Audio delay state — applies a +/- ms offset between audio and video.
 *
 * LibVLC exposes a native, sample-accurate setAudioDelay(microseconds) API
 * (libvlc-android MediaPlayer.java:1113-1130). VlcPlayerAdapter.getVlcEngine()
 * gives us direct access to it.
 *
 * Range: -10000ms .. +10000ms (matches the user-visible UI range).
 */
@Composable
fun rememberAudioDelayState(player: Player?): AudioDelayState {
    val state = remember { AudioDelayState() }
    LaunchedEffect(player) {
        val engine = (player as? VlcPlayerAdapter)?.getVlcEngine()
        state.bindVlc(engine)
    }
    DisposableEffect(Unit) { onDispose { state.unbindAll() } }
    return state
}

@Stable
class AudioDelayState {
    /** Audio delay in milliseconds. Positive = audio plays later, negative = audio plays earlier */
    var delayMs by mutableLongStateOf(0L)
        private set

    private var vlcEngine: dev.anilbeesetti.nextplayer.feature.player.engine.VlcPlayerEngine? = null

    fun bindVlc(engine: dev.anilbeesetti.nextplayer.feature.player.engine.VlcPlayerEngine?) {
        vlcEngine = engine
        // VLC resets audio delay to 0 on media change — re-apply current value when rebinding.
        if (engine != null && delayMs != 0L) {
            applyViaVlc()
        }
    }

    fun unbindAll() {
        vlcEngine = null
    }

    /**
     * Set audio delay. Native VLC: sample-accurate, microseconds.
     */
    fun setDelay(ms: Long) {
        delayMs = ms.coerceIn(-10_000L, 10_000L)
        applyViaVlc()
    }

    fun reset() {
        delayMs = 0L
        applyViaVlc()
    }

    private fun applyViaVlc() {
        val engine = vlcEngine ?: return
        runCatching {
            // VlcEngine.setAudioDelay(ms) already converts ms→μs internally.
            engine.setAudioDelay(delayMs)
        }.onFailure { Log.w("AudioDelay", "VLC setAudioDelay failed", it) }
    }
}

private fun Long.coerceIn(min: Long, max: Long): Long =
    if (this < min) min else if (this > max) max else this
