package dev.anilbeesetti.nextplayer.feature.player.renderers

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import dev.anilbeesetti.nextplayer.feature.player.audio.DelayAudioProcessor

/**
 * Phase 6.2 — Renderers factory that injects [DelayAudioProcessor] into the
 * ExoPlayer audio chain. The delay value is set live from the player UI's
 * audio-sync dialog by calling [DelayAudioProcessor.setDelayMs].
 *
 * The companion [delayAudioProcessor] is a process-singleton so that the
 * MediaPlayerScreen can hold a stable reference and call `setDelayMs` on
 * slider changes without needing a handle to the renderer instance.
 */
class ShsRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        // Inject our delay processor BEFORE the default silence-skipping + channel-mixing processors.
        return DefaultAudioSink.Builder(context)
            .setAudioProcessors(
                arrayOf<AudioProcessor>(
                    delayAudioProcessor,
                    // Standard processors in the default order
                    DefaultAudioSink.DEFAULT_AUDIO_PROCESSOR_CHAIN.first(),
                ),
            )
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }

    companion object {
        /**
         * Singleton [DelayAudioProcessor] — the player UI mutates this directly.
         * ExoPlayer picks it up via the [ShsRenderersFactory] when the player
         * is built in [PlayerService].
         */
        @JvmStatic
        val delayAudioProcessor: DelayAudioProcessor = DelayAudioProcessor()
    }
}
