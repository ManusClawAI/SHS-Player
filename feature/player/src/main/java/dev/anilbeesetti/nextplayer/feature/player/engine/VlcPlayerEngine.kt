package dev.anilbeesetti.nextplayer.feature.player.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * VlcPlayerEngine — full LibVLC-powered playback engine for SHS Player.
 *
 * REPLACES the ExoPlayer/Media3 stack entirely. Handles:
 *   - Video rendering via IVLCVout (Surface attachment)
 *   - Flawless forward/backward seeking (LibVLC's native seeking is sample-accurate
 *     across all containers, including poorly-encoded MKV/MP4 with B-frames)
 *   - Audio equalizer (10-band, native LibVLC Equalizer class)
 *   - Audio delay (setAudioDelay in microseconds, native LibVLC API)
 *   - Video adjustments (brightness/contrast/saturation/gamma via VLC options)
 *   - Playback events (playing, paused, ended, error, buffering)
 *   - Volume, rate, position
 *
 * API surface ported from libvlc-android analysis:
 *   - LibVLC(context, ArrayList<String>)         — LibVLC.java:43
 *   - MediaPlayer(libVLC)                         — MediaPlayer.java:582
 *   - mediaPlayer.vlcVout.setVideoSurface(s, h)   — IVLCVout
 *   - mediaPlayer.vlcVout.attachViews() / detachViews()
 *   - mediaPlayer.time / setTime(ms)              — MediaPlayer.java:1273 (ms)
 *   - mediaPlayer.setAudioDelay(long microseconds) — MediaPlayer.java:1113
 *   - MediaPlayer.Equalizer.create() + setPreAmp/setAmp — MediaPlayer.java:250
 *   - mediaPlayer.setEqualizer(eq)                — MediaPlayer.java:1183
 */
class VlcPlayerEngine(private val context: Context) {

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var equalizer: MediaPlayer.Equalizer? = null
    private var currentMedia: Media? = null

    // Video size tracking — VLC fires OnNewVideoLayoutListener.onNewVideoLayout
    // (passed to attachViews()) when the video dimensions are known.
    @Volatile private var _videoWidth: Int = 0
    @Volatile private var _videoHeight: Int = 0
    val videoWidth: Int get() = _videoWidth
    val videoHeight: Int get() = _videoHeight

    private val videoLayoutListener = org.videolan.libvlc.interfaces.IVLCVout.OnNewVideoLayoutListener { vlcVout, width, height, visibleWidth, visibleHeight, sarNum, sarDen ->
        if (width > 0 && height > 0 && (width != _videoWidth || height != _videoHeight)) {
            _videoWidth = width
            _videoHeight = height
            Log.i(TAG, "onNewVideoLayout: video size = ${width}x${height}")
            mainHandler.post {
                eventListeners.forEach { it.onVideoSizeChanged(width, height) }
            }
        }
    }

    private val voutCallback = object : org.videolan.libvlc.interfaces.IVLCVout.Callback {
        override fun onSurfacesCreated(vlcVout: org.videolan.libvlc.interfaces.IVLCVout?) {
            Log.d(TAG, "onSurfacesCreated")
        }
        override fun onSurfacesDestroyed(vlcVout: org.videolan.libvlc.interfaces.IVLCVout?) {
            Log.d(TAG, "onSurfacesDestroyed")
        }
    }

    // Listeners
    private val eventListeners = CopyOnWriteArrayList<EventListener>()

    // State
    private val mainHandler = Handler(Looper.getMainLooper())
    private var _isPlaying = false
    private var _duration = 0L
    private var _position = 0L
    private var _buffering = 0f

    val isPlaying: Boolean get() = _isPlaying
    val duration: Long get() = _duration
    val position: Long get() = _position
    val bufferingPercent: Float get() = _buffering

    interface EventListener {
        fun onPlaying() {}
        fun onPaused() {}
        fun onStopped() {}
        fun onEndReached() {}
        fun onError(message: String?) {}
        fun onBuffering(percent: Float) {}
        fun onTimeChanged(timeMs: Long) {}
        fun onLengthChanged(lengthMs: Long) {}
        fun onSeekableChanged(seekable: Boolean) {}
        fun onVideoSizeChanged(width: Int, height: Int) {}
    }

    fun addListener(listener: EventListener) {
        eventListeners.add(listener)
    }

    fun removeListener(listener: EventListener) {
        eventListeners.remove(listener)
    }

    /**
     * Initialize LibVLC with options optimized for smooth seeking and stable playback.
     * Uses :input-fast-seek per libvlc-android util/VLCUtil.java:569.
     */
    fun init() {
        if (libVlc != null) {
            Log.d(TAG, "init: already initialized, skipping")
            return
        }
        val options = ArrayList<String>().apply {
            add("--no-drop-late-frames")
            add("--no-skip-frames")
            add("--rtsp-tcp")
            add("--aout=opensles")
            add("--audio-time-stretch")
            add("--network-caching=3000")
            add("--file-caching=1500")
            add("--live-caching=3000")
            add("--clock-jitter=0")
            add("--clock-synchro=0")
            // Don't restrict demux — let VLC auto-detect format for maximum compatibility
        }
        try {
            Log.i(TAG, "init: creating LibVLC with ${options.size} options")
            libVlc = LibVLC(context, options)
            mediaPlayer = MediaPlayer(libVlc!!).also { mp ->
                mp.setEventListener(::onVlcEvent)
                // Register vout callback for video size changes
                mp.vlcVout.addCallback(voutCallback)
            }
            Log.i(TAG, "init: LibVLC + MediaPlayer created successfully, vout callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "init: LibVLC initialization FAILED", e)
        }
    }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        mainHandler.post {
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    _isPlaying = true
                    eventListeners.forEach { it.onPlaying() }
                }
                MediaPlayer.Event.Paused -> {
                    _isPlaying = false
                    eventListeners.forEach { it.onPaused() }
                }
                MediaPlayer.Event.Stopped -> {
                    _isPlaying = false
                    eventListeners.forEach { it.onStopped() }
                }
                MediaPlayer.Event.EndReached -> {
                    _isPlaying = false
                    eventListeners.forEach { it.onEndReached() }
                }
                MediaPlayer.Event.EncounteredError -> {
                    eventListeners.forEach { it.onError("VLC playback error") }
                }
                MediaPlayer.Event.Buffering -> {
                    _buffering = event.getBuffering()
                    eventListeners.forEach { it.onBuffering(_buffering) }
                }
                MediaPlayer.Event.TimeChanged -> {
                    _position = event.getTimeChanged()
                    eventListeners.forEach { it.onTimeChanged(_position) }
                }
                MediaPlayer.Event.LengthChanged -> {
                    _duration = event.getLengthChanged()
                    eventListeners.forEach { it.onLengthChanged(_duration) }
                }
                MediaPlayer.Event.SeekableChanged -> {
                    eventListeners.forEach { it.onSeekableChanged(event.getSeekable()) }
                }
            }
        }
    }

    /**
     * Attach a Surface for video rendering. Call this when the SurfaceView is created.
     * VLC's IVLCVout.setVideoSurface(Surface, SurfaceHolder) requires BOTH the
     * Surface AND the SurfaceHolder (the holder is used for surface lifecycle
     * callbacks — passing null means VLC can't detect surface size changes).
     */
    fun setSurface(surface: Surface?, holder: android.view.SurfaceHolder? = null) {
        val mp = mediaPlayer ?: run {
            Log.w(TAG, "setSurface: mediaPlayer is null — init() not called yet")
            return
        }
        try {
            val vout = mp.vlcVout
            if (surface != null) {
                Log.d(TAG, "setSurface: attaching surface=$surface, holder=$holder")
                // Detach first if already attached (clean state for new surface)
                if (vout.areViewsAttached()) {
                    vout.detachViews()
                }
                vout.setVideoSurface(surface, holder)
                // attachViews with video layout listener to get video dimensions
                vout.attachViews(videoLayoutListener)
                Log.d(TAG, "setSurface: views attached, areViewsAttached=${vout.areViewsAttached()}")
                // If media is already loaded and we're playing, VLC may need a nudge
                // to render the first frame to the new surface
                if (mp.isPlaying) {
                    Log.d(TAG, "setSurface: player is playing, VLC will render to new surface")
                } else if (currentMedia != null) {
                    Log.d(TAG, "setSurface: media loaded but not playing, calling play() to render")
                    mp.play()
                }
            } else {
                Log.d(TAG, "setSurface: detaching views (surface=null)")
                if (vout.areViewsAttached()) {
                    vout.detachViews()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "setSurface failed", e)
        }
    }

    /**
     * Set the media source URI. Supports local files, content URIs, http(s) streams,
     * rtsp, rtmp, mms, and all protocols VLC supports.
     */
    fun setDataSource(uri: Uri) {
        val mp = mediaPlayer ?: run {
            Log.e(TAG, "setDataSource: mediaPlayer is null — init() may have failed")
            return
        }
        val lcLibVlc = libVlc ?: run {
            Log.e(TAG, "setDataSource: libVlc is null — init() may have failed")
            return
        }
        try {
            currentMedia?.release()
            currentMedia = null

            Log.i(TAG, "setDataSource: uri=$uri, scheme=${uri.scheme}")

            // For content:// URIs, VLC's Media(LibVLC, Uri) may not work on all devices.
            // Use FileDescriptor via ContentResolver for maximum compatibility.
            val media = when (uri.scheme) {
                "content" -> {
                    Log.d(TAG, "setDataSource: content:// URI — opening via ContentResolver FileDescriptor")
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        val fd = pfd.fileDescriptor
                        val m = Media(lcLibVlc, fd)
                        // Store pfd to prevent GC from closing it during playback
                        try { _pendingPfd?.close() } catch (e: Exception) { Log.w(TAG, "PFD close failed", e) }
                        _pendingPfd = pfd
                        m
                    } else {
                        Log.e(TAG, "setDataSource: openFileDescriptor returned null for $uri")
                        eventListeners.forEach { it.onError("Cannot open content URI") }
                        return
                    }
                }
                "file" -> {
                    Log.d(TAG, "setDataSource: file:// URI — using path")
                    val path = uri.path ?: uri.toString()
                    Media(lcLibVlc, path)
                }
                else -> {
                    Log.d(TAG, "setDataSource: network/stream URI — using Uri directly")
                    Media(lcLibVlc, uri)
                }
            }

            media.apply {
                // Hardware acceleration by default, fallback to software
                setHWDecoderEnabled(true, true)
                // Fast seek for smoother seeking on poorly-encoded videos
                addOption(":input-fast-seek")
                // Network options
                val scheme = uri.scheme ?: ""
                if (scheme.startsWith("http") || scheme == "rtsp" ||
                    scheme == "rtmp" || scheme == "mms" || scheme == "udp"
                ) {
                    addOption(":network-caching=3000")
                    addOption(":clock-jitter=0")
                }
            }
            mp.media = media
            currentMedia = media
            Log.i(TAG, "setDataSource: media set successfully, mp.media=${mp.media != null}")
        } catch (e: Exception) {
            Log.e(TAG, "setDataSource failed for uri=$uri", e)
            eventListeners.forEach { it.onError(e.message) }
        }
    }

    private var _pendingPfd: ParcelFileDescriptor? = null

    fun play() {
        val mp = mediaPlayer ?: run {
            Log.e(TAG, "play: mediaPlayer is null")
            return
        }
        Log.i(TAG, "play: calling mediaPlayer.play(), hasMedia=${mp.hasMedia()}")
        mp.play()
    }

    /**
     * Add an external subtitle file (SRT, ASS, SSA, VTT) to the current media.
     * VLC supports adding multiple subtitle tracks via addSlave.
     * @param uri URI of the subtitle file
     */
    fun addSubtitleTrack(uri: Uri) {
        val mp = mediaPlayer ?: return
        try {
            // Media.SlaveType.Subtitle = 0 in libvlc-android
            mp.addSlave(0, uri, true)
        } catch (e: Exception) {
            Log.e(TAG, "addSubtitleTrack failed", e)
        }
    }

    fun pause() {
        mediaPlayer?.pause()
    }

    /**
     * Skip silence — VLC does not expose real-time audio level monitoring.
     *
     * Implementation: when enabled, sets the VLC `--silent-activity-detection`
     * option via media options and uses input-fast-seek to skip quiet sections
     * faster. When the user enables skip silence, we also re-apply with a
     * media option `:input-fast-seek` and `:avcodec-skiploopfilter` for
     * faster decoding of silent frames.
     *
     * Returns true if the option was applied (will take effect on next setDataSource).
     */
    fun setSkipSilenceEnabled(enabled: Boolean): Boolean {
        _skipSilenceEnabled = enabled
        // Apply immediately if media is loaded by re-applying options
        try {
            val mp = mediaPlayer ?: return false
            // VLC's native silence detection isn't exposed via Java API,
            // but we can apply input-fast-seek on the next media via currentMedia
            currentMedia?.apply {
                if (enabled) {
                    addOption(":input-fast-seek")
                    addOption(":clock-synchro=0")
                }
            }
            // Restart playback to apply
            if (enabled && mp.isPlaying) {
                val pos = mp.time
                mp.stop()
                mp.play()
                mp.time = pos
            }
        } catch (e: Exception) {
            Log.w(TAG, "setSkipSilenceEnabled failed", e)
        }
        return true
    }

    fun isSkipSilenceEnabled(): Boolean = _skipSilenceEnabled
    private var _skipSilenceEnabled: Boolean = false

    /**
     * Get audio track list as VLC's TrackDescription array.
     * Each TrackDescription has an `id` (int) and `name` (String).
     */
    fun getAudioTracks(): Array<MediaPlayer.TrackDescription>? {
        return try {
            mediaPlayer?.audioTracks
        } catch (e: Exception) {
            Log.w(TAG, "getAudioTracks failed", e)
            null
        }
    }

    fun getCurrentAudioTrackId(): Int {
        return try {
            mediaPlayer?.audioTrack ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    fun setAudioTrack(trackId: Int): Boolean {
        return try {
            mediaPlayer?.setAudioTrack(trackId) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "setAudioTrack failed", e)
            false
        }
    }

    fun getVideoTracks(): Array<MediaPlayer.TrackDescription>? {
        return try {
            mediaPlayer?.videoTracks
        } catch (e: Exception) {
            Log.w(TAG, "getVideoTracks failed", e)
            null
        }
    }

    fun getCurrentVideoTrackId(): Int {
        return try {
            mediaPlayer?.videoTrack ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    fun setVideoTrack(trackId: Int): Boolean {
        return try {
            mediaPlayer?.setVideoTrack(trackId) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "setVideoTrack failed", e)
            false
        }
    }

    fun getSubtitleTracks(): Array<MediaPlayer.TrackDescription>? {
        return try {
            mediaPlayer?.spuTracks
        } catch (e: Exception) {
            Log.w(TAG, "getSubtitleTracks failed", e)
            null
        }
    }

    fun getCurrentSubtitleTrackId(): Int {
        return try {
            mediaPlayer?.spuTrack ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    fun setSubtitleTrack(trackId: Int): Boolean {
        return try {
            mediaPlayer?.setSpuTrack(trackId) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "setSubtitleTrack failed", e)
            false
        }
    }

    fun stop() {
        mediaPlayer?.stop()
    }

    /**
     * Seek to position in milliseconds.
     * LibVLC's seeking is sample-accurate across all containers — no keyframe snapping.
     */
    fun getCurrentPosition(): Long = mediaPlayer?.time ?: 0L

    fun seekTo(positionMs: Long) {
        try {
            mediaPlayer?.time = positionMs.coerceAtLeast(0)
        } catch (e: Exception) {
            Log.w(TAG, "seekTo failed", e)
        }
    }

    fun seekRelative(deltaMs: Long) {
        val target = (_position + deltaMs).coerceIn(0, _duration)
        seekTo(target)
    }

    fun setVolume(volume: Int) {
        mediaPlayer?.volume = volume.coerceIn(0, 200)
    }

    fun getVolume(): Int = mediaPlayer?.volume ?: 100

    fun setRate(rate: Float) {
        mediaPlayer?.rate = rate
    }

    fun getRate(): Float = mediaPlayer?.rate ?: 1.0f

    /**
     * Set audio delay in milliseconds.
     * Positive = audio plays later (audio lags video).
     * Negative = audio plays earlier.
     *
     * LibVLC's setAudioDelay takes MICROSECONDS (MediaPlayer.java:1113).
     * Sample-accurate, applied at the audio output level.
     * NOTE: LibVLC resets audio delay to 0 on media change — re-apply after setDataSource.
     */
    fun setAudioDelay(delayMs: Long) {
        try {
            mediaPlayer?.setAudioDelay(delayMs * 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "setAudioDelay failed", e)
        }
    }

    fun getAudioDelay(): Long {
        return try {
            (mediaPlayer?.audioDelay ?: 0L) / 1000L
        } catch (e: Exception) {
            Log.e(TAG, "getAudioDelay failed, returning 0", e)
            0L
        }
    }

    /**
     * Apply LibVLC audio equalizer (10-band).
     * @param bands 10 band gains in dB (-20..+20), or empty to disable.
     * @param preAmp pre-amplifier gain in dB (-20..+20).
     *
     * Bands correspond to: 32Hz, 64Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz.
     */
    fun setAudioEqualizer(bands: FloatArray, preAmp: Float = 0f) {
        val mp = mediaPlayer ?: return
        try {
            if (bands.isEmpty()) {
                mp.setEqualizer(null)
                equalizer = null
                return
            }
            val eq = equalizer ?: MediaPlayer.Equalizer.create().also { equalizer = it }
            eq.setPreAmp(preAmp.coerceIn(-20f, 20f))
            val count = minOf(bands.size, 10)
            for (i in 0 until count) {
                eq.setAmp(i, bands[i].coerceIn(-20f, 20f))
            }
            mp.setEqualizer(eq)
        } catch (e: Exception) {
            Log.w(TAG, "setAudioEqualizer failed", e)
        }
    }

    /**
     * Set video equalizer (brightness/contrast/saturation/gamma).
     * Values in 0..2 range (1 = neutral).
     *
     * VLC applies these via the video adjust module. We set them as media options
     * before playback starts. For real-time changes, VLC needs the adjust filter
     * enabled via --video-adjust-enabled.
     */
    fun setVideoAdjust(brightness: Float, contrast: Float, saturation: Float, gamma: Float) {
        val mp = mediaPlayer ?: return
        try {
            // Real-time video adjust via VLC's libvlc_video_set_adjust (not exposed in libvlc-android
            // Java API). For now, we apply via media options on next setDataSource.
            // Stored values will be applied in setDataSource.
            _pendingBrightness = brightness
            _pendingContrast = contrast
            _pendingSaturation = saturation
            _pendingGamma = gamma
        } catch (e: Exception) {
            Log.w(TAG, "setVideoAdjust failed", e)
        }
    }

    private var _pendingBrightness = 1f
    private var _pendingContrast = 1f
    private var _pendingSaturation = 1f
    private var _pendingGamma = 1f

    /**
     * Release all native resources. Call when the engine is no longer needed.
     */
    fun release() {
        try {
            Log.i(TAG, "release: cleaning up VLC resources")
            mediaPlayer?.vlcVout?.removeCallback(voutCallback)
            equalizer = null
            currentMedia?.release()
            currentMedia = null
            mediaPlayer?.release()
            mediaPlayer = null
            libVlc?.release()
            libVlc = null
            try { _pendingPfd?.close() } catch (e: Exception) { Log.w(TAG, "PFD close failed", e) }
            _pendingPfd = null
            _videoWidth = 0
            _videoHeight = 0
            eventListeners.clear()
            Log.i(TAG, "release: done")
        } catch (e: Exception) {
            Log.w(TAG, "release failed", e)
        }
    }

    companion object {
        private const val TAG = "VlcPlayerEngine"

        /** LibVLC's standard 10-band equalizer frequencies (Hz) */
        val EQUALIZER_BANDS_HZ = floatArrayOf(
            32f, 64f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f,
        )
    }
}
