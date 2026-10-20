package com.jw6ventures.headlink

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer

/**
 * The H.264 encoder for the projected scene (spec §8.2): `video/avc` from an input
 * Surface, baseline profile, no B-frames, roughly one keyframe a second. Output buffers
 * are drained on their own thread and handed straight to the native session, which sends
 * them as flow control allows; each buffer is released as soon as that call returns.
 *
 * The encoder never blocks the renderer: if the session refuses a buffer (congestion or
 * lost focus), that is counted and the next keyframe recovers the stream.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    /**
     * Bits per second to ask for. Sized from the **content** rectangle rather than the
     * frame, which is what Android Auto does — it logged `Updating H264 codec with
     * bitrate: 12313920 based on width: 1920, height: 882` for a 1920x1080 frame whose
     * content was 1920x882 — because the margins are never seen.
     */
    private var bitRate: Int,
    /**
     * Seconds between requested IDRs. Android Auto configures **60** on this vehicle
     * (`iframe interval: 60`); this source used 1, which resends parameter sets and a
     * keyframe every second.
     */
    private val keyframeIntervalSeconds: Int,
    private val listener: Listener,
    private val softwareOnly: Boolean = false,
) {
    interface Listener {
        /** Encoder thread. The buffer is valid only during the call. */
        fun onBuffer(flags: Int, presentationUs: Long, buffer: ByteBuffer, offset: Int, size: Int)

        /** Encoder thread. The session ends with `ENCODER_FAILED`. */
        fun onEncoderError(detail: String)
    }

    private var codec: MediaCodec? = null
    private var drain: Thread? = null

    @Volatile
    private var running = false

    /** Input surface for the renderer; valid between [start] and [release]. */
    var inputSurface: Surface? = null
        private set

    /** What the platform selected, for the report. */
    var description: String = "-"
        private set

    /**
     * Configures and starts the encoder. Returns the input surface, or null with
     * [description] explaining why no encoder could be configured.
     */
    fun start(): Surface? {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSeconds)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val name = if (softwareOnly) {
            codecs.codecInfos.firstOrNull { info ->
                info.isEncoder && info.isSoftwareOnly &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
                    try {
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFormatSupported(format)
                    } catch (_: IllegalArgumentException) {
                        false
                    }
            }?.name
        } else {
            codecs.findEncoderForFormat(format)
        }
        if (name == null) {
            description = "no ${if (softwareOnly) "software " else ""}encoder for ${width}x$height@$frameRate video/avc"
            return null
        }
        return try {
            val encoder = MediaCodec.createByCodecName(name)
            codec = encoder
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            inputSurface = surface
            encoder.start()
            running = true
            description = describe(encoder, name)
            drain = Thread({ drainLoop(encoder) }, "aap-encoder").apply { start() }
            surface
        } catch (e: Exception) {
            // IllegalArgumentException (format), IllegalStateException, CodecException,
            // IOException (codec creation).
            description = "encoder $name failed: $e"
            release()
            null
        }
    }

    private fun describe(encoder: MediaCodec, name: String): String {
        val info = encoder.codecInfo
        val capabilities =
            try {
                val avc = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val profiles = avc.profileLevels
                    .filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }
                    .maxOfOrNull { it.level } ?: 0
                val video = avc.videoCapabilities
                "baseline_level=$profiles sizes=${video.supportedWidths}x${video.supportedHeights} " +
                    "rate_supported=${video.areSizeAndRateSupported(width, height, frameRate.toDouble())}"
            } catch (e: IllegalArgumentException) {
                "capabilities unavailable: $e"
            }
        return "$name hardware=${info.isHardwareAccelerated} software=${info.isSoftwareOnly} vendor=${info.isVendor} " +
            "bitrate=$bitRate fps=$frameRate iframe_s=$keyframeIntervalSeconds $capabilities"
    }

    /**
     * Retargets the bitrate without reconfiguring, for a head unit that moves the content
     * rectangle mid-stream. Returns false if the encoder would not take it.
     */
    fun setBitRate(bps: Int): Boolean {
        val encoder = codec ?: return false
        bitRate = bps
        return try {
            encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
            true
        } catch (e: IllegalStateException) {
            // The encoder is stopping; the next start uses the new value anyway.
            false
        }
    }

    /** Asks the encoder for an IDR as soon as possible (congestion or focus recovery). */
    fun requestKeyframe() {
        val encoder = codec ?: return
        try {
            encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: IllegalStateException) {
            // The encoder is stopping; the next start sends a keyframe anyway.
        }
    }

    private fun drainLoop(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = encoder.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
                if (index < 0) continue
                val buffer = encoder.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    listener.onBuffer(info.flags, info.presentationTimeUs, buffer, info.offset, info.size)
                }
                encoder.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        } catch (e: IllegalStateException) {
            if (running) listener.onEncoderError("encoder output failed: $e")
        } catch (e: MediaCodec.CodecException) {
            if (running) {
                listener.onEncoderError(
                    "MediaCodec.CodecException recoverable=${e.isRecoverable} transient=${e.isTransient} $e",
                )
            }
        }
    }

    /** Stops the encoder and joins its drain thread; safe to call more than once. */
    fun release() {
        running = false
        drain?.join(RELEASE_JOIN_MS)
        drain = null
        inputSurface?.release()
        inputSurface = null
        codec?.let { encoder ->
            try {
                encoder.stop()
            } catch (e: IllegalStateException) {
                // Already stopped or never started.
            }
            encoder.release()
        }
        codec = null
    }

    companion object {
        private const val DRAIN_TIMEOUT_US = 20_000L
        private const val RELEASE_JOIN_MS = 2_000L

        /**
         * About 2 Mbps at 800x480 (spec §8.2), scaled by pixel count. Callers pass the
         * content rectangle, not the frame; see [bitRate].
         */
        fun bitRateFor(width: Int, height: Int): Int {
            val pixels = width.toLong() * height.toLong()
            val scaled = 2_000_000L * pixels / (800L * 480L)
            return scaled.coerceIn(500_000L, 12_000_000L).toInt()
        }
    }
}
