package org.openautoandroid.source

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Audio format the native session negotiated, from its audio start event (P5.3). */
data class AudioStartParams(
    val session: Int,
    val channel: Int,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val bufferBytes: Int,
) {
    val frameBytes: Int get() = (bitDepth / 8) * channels
    val framesPerBuffer: Int get() = (bufferBytes / frameBytes).coerceAtLeast(1)

    companion object {
        /** Parses the `key=value` payload of an audio start event; null if incomplete. */
        fun parse(payload: Map<String, String>): AudioStartParams? {
            fun int(key: String): Int? = payload[key]?.toIntOrNull()
            val bitDepth = int("bit_depth") ?: return null
            val channels = int("channels") ?: return null
            val bufferBytes = int("buffer_bytes") ?: return null
            // The native side already refused anything else, but the app produces the
            // samples and must not be told to write a format it cannot write.
            if (bitDepth != 16 || channels !in 1..2 || bufferBytes <= 0) return null
            return AudioStartParams(
                session = int("session") ?: return null,
                channel = int("channel") ?: return null,
                sampleRate = int("sample_rate") ?: return null,
                bitDepth = bitDepth,
                channels = channels,
                bufferBytes = bufferBytes,
            )
        }
    }
}

/**
 * App-owned audio for the head unit's speakers (spec §2.2, P5.3).
 *
 * **This app owns the audio it sends.** Capturing or routing another application's
 * playback is a separate platform question the project has not answered, and spec §5.2
 * forbids the MediaProjection route to it; so what goes down the USB audio channel is a
 * signal this app generated itself. That also makes G7 checkable by ear and by a receiver
 * dump at the same time: the left channel is a 440 Hz sine and the right an 880 Hz sine,
 * so an operator hears a chord from both speakers and a swapped pair is audible.
 *
 * Runs on its own thread with its own clock. The buffer size and the pacing come from the
 * head unit's advertised configuration, not from a phone-side preference, and nothing here
 * is tied to the video frame rate: at 48 kHz stereo a buffer is 2048 sample frames ≈
 * 42.7 ms, and falling behind that budget is what the head unit reports as an underflow.
 *
 * Presentation timestamps come from the sample counter rather than the wall clock, so a
 * buffer delayed by scheduling still says when it belongs.
 */
class AudioSource(
    private val handle: Long,
    val params: AudioStartParams,
    private val onFailure: (String) -> Unit,
) {
    private val thread = Thread(::pump, "aap-audio")

    @Volatile
    private var running = false

    @Volatile
    private var submitted = 0L
    private val results = java.util.concurrent.atomic.AtomicLongArray(SUBMIT_RESULTS)

    @Volatile
    private var late = 0L

    @Volatile
    private var failure: String? = null

    fun start() {
        running = true
        thread.start()
    }

    /** Stops the thread and waits briefly for it; safe to call more than once. */
    fun release() {
        running = false
        thread.join(RELEASE_WAIT_MS)
    }

    private fun pump() {
        val frames = params.framesPerBuffer
        val buffer = ByteBuffer.allocateDirect(frames * params.frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        val rate = params.sampleRate.toDouble()
        val periodNs = (frames * 1_000_000_000L) / params.sampleRate.coerceAtLeast(1)
        var nextNs = SystemClock.elapsedRealtimeNanos()
        var sample = 0L
        try {
            while (running) {
                val now = SystemClock.elapsedRealtimeNanos()
                if (now < nextNs) {
                    val waitNs = nextNs - now
                    Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                } else if (now > nextNs + periodNs) {
                    late++
                }
                nextNs += periodNs
                buffer.clear()
                for (frame in 0 until frames) {
                    val t = (sample + frame).toDouble() / rate
                    for (channel in 0 until params.channels) {
                        val hz = if (channel == 0) LEFT_HZ else RIGHT_HZ
                        buffer.putShort((AMPLITUDE * sin(2.0 * PI * hz * t) * Short.MAX_VALUE).toInt().toShort())
                    }
                }
                val presentationUs = sample * 1_000_000L / params.sampleRate.coerceAtLeast(1)
                sample += frames
                submitted++
                val result = NativeBridge.submitAudio(handle, presentationUs, buffer, 0, buffer.capacity())
                val index = if (result in 0 until SUBMIT_RESULTS) result else SUBMIT_RESULTS - 1
                results.incrementAndGet(index)
                // The session has ended; nothing more will be accepted.
                if (result == SUBMIT_CLOSED) break
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: RuntimeException) {
            failure = e.toString()
            onFailure(e.toString())
        }
    }

    /** `key=value` lines for the session report (spec §10). */
    fun report(): String = buildString {
        appendLine(
            "projection_audio=${params.sampleRate}/${params.bitDepth}/${params.channels} " +
                "channel=${params.channel} session=${params.session} " +
                "buffer_bytes=${params.bufferBytes} frames=${params.framesPerBuffer} " +
                "signal=${LEFT_HZ.toInt()}hz/${RIGHT_HZ.toInt()}hz",
        )
        appendLine(
            "projection_audio_submitted=$submitted projection_audio_accepted=${results.get(0)} " +
                "projection_audio_dropped_congested=${results.get(1)} " +
                "projection_audio_dropped_not_streaming=${results.get(3)} " +
                "projection_audio_invalid=${results.get(4)} projection_audio_closed=${results.get(5)} " +
                "projection_audio_refused=${results.get(6)} projection_audio_late=$late",
        )
        appendLine("projection_audio_error=${failure ?: "-"}")
    }

    private companion object {
        const val LEFT_HZ = 440.0
        const val RIGHT_HZ = 880.0

        /** A quarter of full scale, so a head unit applying its own gain has headroom. */
        const val AMPLITUDE = 0.25

        /** Submit results 0..5 from the native contract, plus one slot for anything else. */
        const val SUBMIT_RESULTS = 7
        const val SUBMIT_CLOSED = 5
        const val RELEASE_WAIT_MS = 2_000L
    }
}
