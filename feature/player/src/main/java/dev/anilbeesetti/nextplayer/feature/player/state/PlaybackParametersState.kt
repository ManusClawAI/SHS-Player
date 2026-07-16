package dev.anilbeesetti.nextplayer.feature.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@UnstableApi
@Composable
fun rememberPlaybackParametersState(player: Player): PlaybackParametersState {
    val scope = rememberCoroutineScope()
    val playbackParametersState = remember { PlaybackParametersState(player, scope) }
    LaunchedEffect(player) { playbackParametersState.observe() }
    return playbackParametersState
}

@UnstableApi
class PlaybackParametersState(
    private val player: Player,
    private val scope: CoroutineScope,
) {
    var speed by mutableFloatStateOf(1f)
        private set
    var skipSilenceEnabled by mutableStateOf(false)
        private set

    fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    /**
     * Skip silence — delegates to VlcPlayerAdapter which uses VLC's
     * :input-fast-seek + :clock-synchro=0 options.
     */
    fun setIsSkipSilenceEnabled(enabled: Boolean) {
        skipSilenceEnabled = enabled
        (player as? dev.anilbeesetti.nextplayer.feature.player.engine.VlcPlayerAdapter)
            ?.setSkipSilenceEnabled(enabled)
    }

    suspend fun observe() {
        updateSpeed()
        skipSilenceEnabled = (player as? dev.anilbeesetti.nextplayer.feature.player.engine.VlcPlayerAdapter)
            ?.getSkipSilenceEnabled() ?: false

        player.listen { events ->
            if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) {
                updateSpeed()
            }
            if (events.contains(Player.EVENT_SKIP_SILENCE_ENABLED_CHANGED)) {
                skipSilenceEnabled = (player as? dev.anilbeesetti.nextplayer.feature.player.engine.VlcPlayerAdapter)
                    ?.getSkipSilenceEnabled() ?: false
            }
        }
    }

    private fun updateSpeed() {
        speed = player.playbackParameters.speed
    }
}
