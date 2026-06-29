package dev.anilbeesetti.nextplayer.feature.player.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * Phase 6.2 — Real, sample-accurate audio delay for ExoPlayer.
 *
 * Background: ExoPlayer has no `setAudioDelay(ms)` API. The video player's
 * audio-sync UI was previously a no-op (the delay value was written to media
 * metadata extras but never applied to the audio pipeline). This processor
 * finally hooks the UI slider to actual audio offset.
 *
 * How it works:
 *  - Positive `delayMs` → prepend N ms of silence before audio starts.
 *    Audio "plays later", which makes the video appear to lead.
 *  - Negative `delayMs` → skip N ms of audio at the start so audio
 *    effectively plays earlier (advances relative to video).
 *
 * Range: -10000 ms .. +10000 ms (matches the UI range).
 *
 * Bug fix: the previous implementation had inverted logic in drainOutput() —
 * the bytesToPad branch was advancing past audio bytes (skipping audio) instead
 * of prepending silence. It now correctly outputs zero-filled silence chunks
 * BEFORE passing real audio through.
 */
class DelayAudioProcessor : BaseAudioProcessor() {

    companion object {
        private const val TAG = "DelayAudioProcessor"
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        /** Max silence chunk size emitted per drainOutput() call to avoid huge allocations. */
        private const val SILENCE_CHUNK_BYTES = 8192
    }

    @Volatile
    private var requestedDelayMs: Long = 0L

    /** Bytes of silence still to be output before passing real audio (positive delay). */
    private var bytesToPad: Long = 0L
    /** Bytes of real audio still to be skipped from input (negative delay). */
    private var bytesToSkip: Long = 0L

    private val inputBuffers: ArrayDeque<ByteBuffer> = ArrayDeque()
    private var currentOutput: ByteBuffer = EMPTY_BUFFER

    @Volatile
    var pendingAudioFormat: AudioProcessor.AudioFormat? = null
        private set

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingAudioFormat = inputAudioFormat
        recomputeDelayBytes(inputAudioFormat)
        return inputAudioFormat
    }

    fun setDelayMs(delayMs: Long) {
        val clamped = delayMs.coerceIn(-10_000L, 10_000L)
        synchronized(this) {
            requestedDelayMs = clamped
            pendingAudioFormat?.let { recomputeDelayBytes(it) }
        }
        Log.d(TAG, "setDelayMs($delayMs) → $clamped ms")
    }

    fun getDelayMs(): Long = requestedDelayMs

    private fun recomputeDelayBytes(format: AudioProcessor.AudioFormat) {
        val sampleRate = format.sampleRate
        val channels = format.channelCount
        val bytesPerSample = 2 // PCM_16BIT
        val frameSize = bytesPerSample * channels
        val ms = requestedDelayMs
        val absBytes = Math.abs(ms) * sampleRate * frameSize / 1000L
        if (ms >= 0L) {
            bytesToPad = absBytes
            bytesToSkip = 0L
        } else {
            bytesToPad = 0L
            bytesToSkip = absBytes
        }
    }

    /**
     * Queue input audio. Applies skip immediately for negative delay so
     * audio arrives at drainOutput already trimmed.
     */
    override fun queueInput(inputBuffer: ByteBuffer) {
        synchronized(this) {
            var src = ByteBuffer.allocateDirect(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
            src.put(inputBuffer.duplicate()).flip()
            inputBuffer.position(inputBuffer.limit())

            // Negative delay: discard leading audio bytes so audio appears earlier
            if (bytesToSkip > 0L && src.hasRemaining()) {
                val skipNow = minOf(bytesToSkip, src.remaining().toLong()).toInt()
                bytesToSkip -= skipNow
                src.position(src.position() + skipNow)
            }

            if (src.hasRemaining()) {
                inputBuffers.add(src)
            }
            drainOutput()
        }
    }

    private fun drainOutput() {
        if (currentOutput.hasRemaining()) return

        // Positive delay: emit silence chunks BEFORE real audio.
        // ByteBuffer.allocateDirect is zero-filled by the JVM — no manual fill needed.
        if (bytesToPad > 0L) {
            val silenceSize = minOf(bytesToPad, SILENCE_CHUNK_BYTES.toLong()).toInt()
            bytesToPad -= silenceSize
            val silence = ByteBuffer.allocateDirect(silenceSize).order(ByteOrder.nativeOrder())
            silence.position(silenceSize) // mark all bytes as written
            silence.flip()               // set limit=silenceSize, position=0
            currentOutput = silence
            return
        }

        if (inputBuffers.isEmpty()) {
            currentOutput = EMPTY_BUFFER
            return
        }

        val totalBytes = inputBuffers.sumOf { it.remaining() }
        val merged = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.nativeOrder())
        while (inputBuffers.isNotEmpty()) merged.put(inputBuffers.removeFirst())
        merged.flip()
        currentOutput = merged
    }

    override fun getOutput(): ByteBuffer {
        synchronized(this) {
            if (!currentOutput.hasRemaining()) drainOutput()
            return currentOutput
        }
    }

    override fun isActive(): Boolean = pendingAudioFormat != null

    override fun onFlush() {
        synchronized(this) {
            inputBuffers.clear()
            currentOutput = EMPTY_BUFFER
            pendingAudioFormat?.let { recomputeDelayBytes(it) }
        }
    }

    override fun onReset() {
        synchronized(this) {
            requestedDelayMs = 0L
            bytesToPad = 0L
            bytesToSkip = 0L
            inputBuffers.clear()
            currentOutput = EMPTY_BUFFER
            pendingAudioFormat = null
        }
    }
}
