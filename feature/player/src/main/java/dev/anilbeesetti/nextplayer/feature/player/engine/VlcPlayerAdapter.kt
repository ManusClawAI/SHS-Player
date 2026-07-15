package dev.anilbeesetti.nextplayer.feature.player.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Size
import java.util.concurrent.CopyOnWriteArrayList

/**
 * VlcPlayerAdapter — Media3 Player implementation backed by VlcPlayerEngine.
 *
 * Wraps VlcPlayerEngine so that MediaPlayerScreen (which takes a Player?)
 * can use VLC for playback with all the rich UI features (overlays, gestures,
 * audio/subtitle selectors, equalizer dialogs, AB repeat, sleep timer,
 * playlist, bookmarks, decoder selector, video zoom, voice changer, etc.).
 *
 * Methods that don't have a VLC equivalent (skip silence, audio session id,
 * shuffle, repeat mode, multi-item playlist) are no-ops or return defaults.
 */
@UnstableApi
class VlcPlayerAdapter(
    context: Context,
) : Player {

    private val engine = VlcPlayerEngine(context).also { it.init() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile private var playWhenReady = false
    @Volatile private var playbackState = STATE_IDLE
    @Volatile private var currentMediaItem: MediaItem? = null
    @Volatile private var mediaItemCount = 0
    @Volatile private var volume = 1f
    @Volatile private var videoSize = VideoSize.UNKNOWN
    @Volatile private var playbackParameters = PlaybackParameters(1f, 1f)
    @Volatile private var playerError: PlaybackException? = null

    private val engineListener = object : VlcPlayerEngine.EventListener {
        override fun onPlaying() {
            mainHandler.post {
                playWhenReady = true
                setPlaybackState(STATE_READY)
                notifyListeners { it.onPlaybackStateChanged(STATE_READY); it.onIsPlayingChanged(true); it.onPlayWhenReadyChanged(true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
            }
        }
        override fun onPaused() {
            mainHandler.post {
                playWhenReady = false
                setPlaybackState(STATE_READY)
                notifyListeners { it.onPlaybackStateChanged(STATE_READY); it.onIsPlayingChanged(false); it.onPlayWhenReadyChanged(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
            }
        }
        override fun onStopped() {
            mainHandler.post {
                setPlaybackState(STATE_IDLE)
                notifyListeners { it.onPlaybackStateChanged(STATE_IDLE); it.onIsPlayingChanged(false) }
            }
        }
        override fun onEndReached() {
            mainHandler.post {
                setPlaybackState(STATE_ENDED)
                notifyListeners { it.onPlaybackStateChanged(STATE_ENDED); it.onIsPlayingChanged(false) }
            }
        }
        override fun onError(message: String?) {
            mainHandler.post {
                playerError = PlaybackException(message ?: "VLC playback error", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                setPlaybackState(STATE_IDLE)
                notifyListeners { it.onPlayerErrorChanged(playerError) }
            }
        }
        override fun onBuffering(percent: Float) {
            mainHandler.post {
                if (percent in 1f..99f && playbackState != STATE_BUFFERING) {
                    setPlaybackState(STATE_BUFFERING)
                    notifyListeners { it.onPlaybackStateChanged(STATE_BUFFERING) }
                } else if (percent >= 100f && playbackState == STATE_BUFFERING) {
                    setPlaybackState(STATE_READY)
                    notifyListeners { it.onPlaybackStateChanged(STATE_READY) }
                }
            }
        }
        override fun onTimeChanged(timeMs: Long) {
            // Position changes — no specific event needed (UI polls getCurrentPosition)
        }
        override fun onLengthChanged(lengthMs: Long) {
            mainHandler.post {
                notifyListeners { it.onMediaItemTransition(0, MEDIA_ITEM_TRANSITION_REASON_REPEAT) }
            }
        }
        override fun onSeekableChanged(seekable: Boolean) {
            // No direct Player event
        }
    }

    init {
        engine.addListener(engineListener)
    }

    private fun setPlaybackState(state: Int) {
        playbackState = state
    }

    private fun notifyListeners(block: (Listener) -> Unit) {
        listeners.forEach(block)
    }

    // ── Playback control ────────────────────────────────────────────────
    override fun play() {
        playWhenReady = true
        engine.play()
    }
    override fun pause() {
        playWhenReady = false
        engine.pause()
    }
    override fun stop() {
        engine.stop()
        playWhenReady = false
    }
    override fun release() {
        engine.removeListener(engineListener)
        engine.release()
        listeners.clear()
    }
    override fun prepare() {
        // VLC doesn't have a prepare step — setDataSource + play is enough
        setPlaybackState(STATE_BUFFERING)
        notifyListeners { it.onPlaybackStateChanged(STATE_BUFFERING) }
    }

    // ── Media items ─────────────────────────────────────────────────────
    override fun setMediaItem(mediaItem: MediaItem) {
        setMediaItems(listOf(mediaItem))
    }
    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        if (mediaItems.isEmpty()) return
        currentMediaItem = mediaItems[0]
        mediaItemCount = mediaItems.size
        val uri = mediaItems[0].localConfiguration?.uri ?: mediaItems[0].uri ?: return
        engine.setDataSource(uri)
        mainHandler.post {
            notifyListeners {
                it.onTimelineChanged(Timeline.EMPTY, TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                it.onMediaItemTransition(0, MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            }
        }
    }
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        setMediaItems(mediaItems)
    }
    override fun addMediaItem(mediaItem: MediaItem) {
        // Single-item only for now — replace current
        setMediaItem(mediaItem)
    }
    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        if (mediaItems.isNotEmpty()) setMediaItem(mediaItems[0])
    }
    override fun removeMediaItem(index: Int) { /* no-op */ }
    override fun clearMediaItems() {
        currentMediaItem = null
        mediaItemCount = 0
        engine.stop()
    }
    override fun moveMediaItem(currentIndex: Int, newIndex: Int) { /* no-op */ }
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        if (index == 0) setMediaItem(mediaItem)
    }
    override fun seekTo(positionMs: Long) {
        engine.seekTo(positionMs)
        mainHandler.post {
            notifyListeners { it.onPositionDiscontinuity(DISCONTINUITY_REASON_SEEK) }
        }
    }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex == 0) seekTo(positionMs)
    }
    override fun seekBack() { engine.seekRelative(-10_000L) }
    override fun seekForward() { engine.seekRelative(10_000L) }
    override fun seekToNext() { /* no-op */ }
    override fun seekToPrevious() { /* no-op */ }
    override fun seekToNextMediaItem() { /* no-op */ }
    override fun seekToPreviousMediaItem() { /* no-op */ }
    override fun hasPreviousMediaItem(): Boolean = false
    override fun hasNextMediaItem(): Boolean = false
    override fun getMediaItemAt(index: Int): MediaItem = currentMediaItem ?: MediaItem.EMPTY
    override fun getCurrentMediaItem(): MediaItem? = currentMediaItem
    override fun getCurrentMediaItemIndex(): Int = 0
    override fun getMediaItemCount(): Int = mediaItemCount
    override fun getDuration(): Long = engine.duration
    override fun getCurrentPosition(): Long = engine.position
    override fun getBufferedPosition(): Long = engine.position
    override fun getTotalBufferedDuration(): Long = engine.duration
    override fun isCurrentMediaItemSeekable(): Boolean = true
    override fun isCurrentMediaItemLive(): Boolean = false
    override fun isCurrentMediaItemDynamic(): Boolean = false
    override fun getMediaMetadata(): MediaMetadata = currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY
    override fun setMediaMetadata(mediaMetadata: MediaMetadata) { /* stored on MediaItem */ }
    override fun getPlaylistMetadata(): MediaMetadata = MediaMetadata.EMPTY
    override fun setPlaylistMetadata(playlistMetadata: MediaMetadata) { /* no-op */ }

    // ── Timeline ────────────────────────────────────────────────────────
    override fun getCurrentTimeline(): Timeline = Timeline.EMPTY
    override fun getCurrentPeriodIndex(): Int = 0
    override fun getCurrentWindowIndex(): Int = 0
    override fun getNextWindowIndex(): Int = C.INDEX_UNSET
    override fun getPreviousWindowIndex(): Int = C.INDEX_UNSET

    // ── State ───────────────────────────────────────────────────────────
    override fun getPlaybackState(): Int = playbackState
    override fun getPlayWhenReady(): Boolean = playWhenReady
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }
    override fun isPlaying(): Boolean = engine.isPlaying
    override fun isLoading(): Boolean = playbackState == STATE_BUFFERING
    override fun getPlayerError(): PlaybackException? = playerError
    override fun getPlaybackSuppressionReason(): Int = PLAYBACK_SUPPRESSION_REASON_NONE
    override fun setHandleAudioBecomingNoisy(handleAudioBecomingNoisy: Boolean) { /* no-op */ }

    // ── Playback parameters ─────────────────────────────────────────────
    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
        engine.setRate(playbackParameters.speed)
        mainHandler.post { notifyListeners { it.onPlaybackParametersChanged(playbackParameters) } }
    }
    override fun setPlaybackSpeed(speed: Float) {
        setPlaybackParameters(PlaybackParameters(speed, 1f))
    }

    // ── Volume / Audio ──────────────────────────────────────────────────
    override fun getVolume(): Float = volume
    override fun setVolume(volume: Float) {
        this.volume = volume
        engine.setVolume((volume * 100).toInt().coerceIn(0, 200))
        mainHandler.post { notifyListeners { it.onVolumeChanged(volume) } }
    }
    override fun getAudioSessionId(): Int = C.AUDIO_SESSION_ID_UNSET
    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) { /* no-op */ }
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) { /* no-op */ }
    override fun getSkipSilenceEnabled(): Boolean = false

    // ── Video ───────────────────────────────────────────────────────────
    override fun getVideoSize(): VideoSize = videoSize
    override fun setVideoSurface(surface: Surface?) {
        engine.setSurface(surface)
    }
    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        engine.setSurface(surfaceHolder?.surface)
    }
    override fun setVideoTextureView(textureView: TextureView?) {
        Log.w(TAG, "TextureView not supported by VLC, use SurfaceView")
        // Could extract bitmap from textureView but for now no-op
    }
    override fun clearVideoSurface() {
        engine.setSurface(null)
    }
    override fun clearVideoSurface(surface: Surface?) { clearVideoSurface() }
    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) { clearVideoSurface() }
    override fun clearVideoTextureView(textureView: TextureView?) { /* no-op */ }
    override fun getSurfaceSize(): Size = Size.UNKNOWN

    // ── Tracks / Subtitles ──────────────────────────────────────────────
    override fun getCurrentTracks(): Tracks = Tracks.EMPTY
    override fun getTrackSelectionParameters(): TrackSelectionParameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) { /* VLC has own track selection */ }
    override fun getCurrentCues(): CueGroup = CueGroup(emptyList(), 0L)
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int) {
        setMediaItems(mediaItems)
    }

    // ── Repeat / Shuffle ────────────────────────────────────────────────
    override fun getRepeatMode(): Int = REPEAT_MODE_OFF
    override fun setRepeatMode(repeatMode: Int) { /* no-op */ }
    override fun getShuffleModeEnabled(): Boolean = false
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) { /* no-op */ }

    // ── Seek parameters ─────────────────────────────────────────────────
    override fun setSeekParameters(seekParameters: androidx.media3.exoplayer.SeekParameters?) {
        // VLC has sample-accurate seeking by default
    }

    // ── Listeners ───────────────────────────────────────────────────────
    override fun addListener(listener: Listener) {
        listeners.add(listener)
    }
    override fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // ── Misc ────────────────────────────────────────────────────────────
    override fun getApplicationLooper(): Looper = Looper.getMainLooper()
    override fun getAvailableCommands(): Player.Commands {
        return Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_PREPARE,
                COMMAND_STOP,
                COMMAND_SEEK_TO_DEFAULT,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SET_SPEED_AND_PITCH,
                COMMAND_SET_VOLUME,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_METADATA,
                COMMAND_SET_VIDEO_SURFACE,
                COMMAND_CHANGE_MEDIA_ITEMS,
            )
            .build()
    }
    override fun isCommandAvailable(command: Int): Boolean = getAvailableCommands().contains(command)

    // ExoPlayer-internal methods that throw (we don't support them)
    override fun getMediaClock(): androidx.media3.common.util.Clock {
        throw IllegalStateException("VlcPlayerAdapter does not support getMediaClock")
    }

    // ── Public VLC-specific API (for advanced features) ────────────────
    /** Direct access to the underlying VLC engine for VLC-specific features (audio delay, equalizer, video adjust). */
    fun getVlcEngine(): VlcPlayerEngine = engine

    companion object {
        private const val TAG = "VlcPlayerAdapter"
    }
}
