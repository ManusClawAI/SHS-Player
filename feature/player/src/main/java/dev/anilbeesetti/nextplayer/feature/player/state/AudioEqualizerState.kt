package dev.anilbeesetti.nextplayer.feature.player.state

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import dev.anilbeesetti.nextplayer.feature.player.engine.VlcPlayerAdapter

/**
 * Audio equalizer state — 10-band equalizer + pre-amp.
 *
 * VLC has a native 10-band equalizer (MediaPlayer.Equalizer) accessible via
 * VlcPlayerAdapter.getVlcEngine().setAudioEqualizer(bands, preAmp).
 *
 * Bands: 32Hz, 64Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz.
 * Range: -20dB .. +20dB per band and for pre-amp.
 */
@Composable
fun rememberAudioEqualizerState(player: Player?): AudioEqualizerState {
    val state = remember { AudioEqualizerState() }

    LaunchedEffect(player) {
        val adapter = player as? VlcPlayerAdapter
        state.bindAdapter(adapter)
    }

    DisposableEffect(Unit) { onDispose { state.release() } }
    return state
}

@Stable
class AudioEqualizerState {
    var isReady by mutableStateOf(false)
        private set

    var preAmp by mutableStateOf(0f)
        private set

    var bandLevels by mutableStateOf<List<Int>>(emptyList())
        private set

    var bandFrequencies by mutableStateOf<List<String>>(emptyList())
        private set

    /** Backward-compat aliases for existing UI code */
    val bandCount: Int get() = bandFrequencies.size
    val minLevel: Int get() = -20
    val maxLevel: Int get() = 20

    fun resetBands() {
        reset()
    }

    private var adapter: VlcPlayerAdapter? = null

    fun bindAdapter(adapter: VlcPlayerAdapter?) {
        this.adapter = adapter
        if (adapter != null) {
            // Initialize with VLC's band frequencies
            bandFrequencies = VLC_BAND_FREQS_HZ.map { formatFreq(it) }
            bandLevels = List(VLC_BAND_FREQS_HZ.size) { 0 }
            isReady = true
        } else {
            isReady = false
        }
    }

    fun setPreAmp(value: Float) {
        preAmp = value.coerceIn(-20f, 20f)
        applyToVlc()
    }

    fun setBandLevel(index: Int, levelDb: Int) {
        if (index !in bandLevels.indices) return
        bandLevels = bandLevels.toMutableList().also { it[index] = levelDb.coerceIn(-20, 20) }
        applyToVlc()
    }

    fun reset() {
        preAmp = 0f
        bandLevels = List(bandFrequencies.size) { 0 }
        applyToVlc()
    }

    private fun applyToVlc() {
        val adapter = adapter ?: return
        val bands = FloatArray(VLC_BAND_FREQS_HZ.size) { i ->
            (bandLevels.getOrNull(i) ?: 0).toFloat()
        }
        runCatching {
            adapter.getVlcEngine().setAudioEqualizer(bands, preAmp)
        }.onFailure { Log.w("AudioEq", "VLC setAudioEqualizer failed", it) }
    }

    fun release() {
        adapter = null
        isReady = false
    }

    private fun formatFreq(hz: Float): String =
        if (hz >= 1000f) "${(hz / 1000f).toInt()}kHz" else "${hz.toInt()}Hz"

    companion object {
        private val VLC_BAND_FREQS_HZ = floatArrayOf(
            32f, 64f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f,
        )
    }
}
