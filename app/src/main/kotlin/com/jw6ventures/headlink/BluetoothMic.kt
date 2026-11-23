package com.jw6ventures.headlink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Voice input's microphone: the car's hands-free microphone over the phone's ordinary
 * Bluetooth link to the car (HFP/SCO), the path a hands-free call uses (spec §2.1 and
 * §5.2 as amended 11 November 2026). The phone's own microphone is never used: if the
 * car's link is missing, refused, slow to open, or drops while listening, capture fails
 * with a reason and returns no audio, rather than falling back.
 *
 * One push-to-talk press gives one [VoiceClip]: 16 kHz mono 16-bit signed PCM, held in
 * memory only, at most 30 s, which the speech engine takes as it is. Nothing here writes
 * audio anywhere, and the report keeps counts, durations, levels and outcomes only.
 *
 * Everything below the Android glue ([BluetoothMic]) is pure Kotlin and host-tested
 * (MicCaptureTest). Whether a given phone and car open the route, and how long it takes,
 * only a car can say (docs/audio-and-speech.md, "Voice input: the car's microphone").
 */

/** One utterance as captured: 16 kHz mono 16-bit signed PCM, in memory only. */
class VoiceClip(val samples: ShortArray, val sampleRate: Int = MicRules.SAMPLE_RATE) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
}

/** Why listening ended with audio worth transcribing (or, for [NO_SPEECH], with none). */
enum class MicEnd {
    /** Speech, then enough silence to call the utterance over. */
    UTTERANCE_END,

    /** The caller ended it ([BluetoothMic.Capture.finish]): a button released or tapped again. */
    FINISHED,

    /** The 30 s cap, the speech engine's own limit. */
    MAX_LENGTH,

    /** No speech before the no-speech timeout. The clip is empty. */
    NO_SPEECH,
}

/** Why listening could not start or stopped without a clip. Each is said to the operator. */
enum class MicFailure {
    /** The Microphone grant is missing. */
    NO_PERMISSION,

    /** A call, or another app's call audio, holds the hands-free link. */
    IN_CALL,

    /** No car hands-free microphone is connected: Bluetooth off, or the car not linked for calls. */
    NO_BLUETOOTH_MIC,

    /** Android refused to route to the car's link. */
    ROUTE_REFUSED,

    /** The car's link did not open in time. */
    ROUTE_TIMEOUT,

    /** The recorder opened on some other microphone (the phone's own), so it was closed unheard. */
    NOT_BLUETOOTH,

    /** The car's link dropped while listening. What was heard is discarded. */
    LOST,

    /** The platform recorder would not start, or stopped delivering audio. */
    RECORDER_FAILED,

    /** Another capture is already running. */
    BUSY,

    /** The operator cancelled. */
    CANCELLED,
}

sealed class MicResult {
    data class Heard(val clip: VoiceClip, val end: MicEnd, val stats: MicStats) : MicResult()
    data class Failed(val failure: MicFailure, val stats: MicStats?) : MicResult()
}

/**
 * Numbers about one capture, for the report and the Diagnostics test. Levels are dBFS of
 * 20 ms frames. No audio, transcript, device name or address.
 */
data class MicStats(
    /** From the press to the car's link reporting open. */
    val routeMs: Long,
    /** Whether the link only opened after switching the phone to call mode (see [BluetoothMic]). */
    val neededCallMode: Boolean,
    /** Which platform source delivered, [MicRules.SOURCE_RECOGNITION] or [MicRules.SOURCE_COMMUNICATION]. */
    val source: String,
    /** Audio kept, in ms. */
    val heardMs: Long,
    /** Frames judged speech. */
    val speechMs: Long,
    /** From recording start to the first frame that was not digital silence; -1 if none was. */
    val firstSoundMs: Long,
    val peakDbfs: Double,
    val noiseDbfs: Double,
)

/** A microphone port as Android lists it, reduced to what the choice needs. */
data class MicPort(val type: Int, val id: Int)

object MicRules {
    const val SAMPLE_RATE = 16_000

    /** 20 ms frames: the unit the end-of-speech logic counts in. */
    const val FRAME_SAMPLES = SAMPLE_RATE / 50
    const val FRAME_MS = 20L

    /** The speech engine refuses more than 30 s (480 000 samples), so capture stops there. */
    const val MAX_SAMPLES = SAMPLE_RATE * 30

    /** Mirrors `AudioDeviceInfo.TYPE_BLUETOOTH_SCO`, the car's hands-free link. */
    const val TYPE_BLUETOOTH_SCO = 7

    /** Mirrors `AudioDeviceInfo.TYPE_BUILTIN_MIC`, named only so tests can show it is refused. */
    const val TYPE_BUILTIN_MIC = 15

    const val SOURCE_RECOGNITION = "voice_recognition"
    const val SOURCE_COMMUNICATION = "voice_communication"

    /** Wait for the link this long before also switching the phone to call mode. */
    const val ROUTE_PLAIN_MS = 1_500L

    /** Then give up this long after the press. Cars have taken one to three seconds for a call. */
    const val ROUTE_TIMEOUT_MS = 4_000L

    /** The recorder delivered nothing at all for this long: treat as failed rather than silence. */
    const val STALL_MS = 1_000L

    /**
     * The car's hands-free link among the ports offered for communication, or null if there
     * is none. Only SCO: the phone's microphone, a wired headset or USB never qualify, so
     * there is no fallback to choose (spec §2.1 as amended).
     */
    fun pickRoute(ports: List<MicPort>): MicPort? = ports.firstOrNull { it.type == TYPE_BLUETOOTH_SCO }

    /** True when the recorder is actually reading from the car's link. Anything else is closed unheard. */
    fun routedToCar(routedType: Int?): Boolean = routedType == TYPE_BLUETOOTH_SCO

    /** The order capture tries platform sources in: recognition first (least processing), then communication. */
    val SOURCES = listOf(SOURCE_RECOGNITION, SOURCE_COMMUNICATION)

    /** Root-mean-square level of [count] samples from [offset], in dBFS; -120 for digital silence. */
    fun dbfs(samples: ShortArray, offset: Int = 0, count: Int = samples.size - offset): Double {
        if (count <= 0) return SILENCE_DB
        var sum = 0.0
        for (i in offset until offset + count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / count) / 32768.0
        return if (rms <= 0.0) SILENCE_DB else max(SILENCE_DB, 20.0 * log10(rms))
    }

    /** Peak absolute sample of a frame, in dBFS. */
    fun peakDbfs(samples: ShortArray, offset: Int, count: Int): Double {
        var peak = 0
        for (i in offset until offset + count) {
            val v = samples[i].toInt()
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        return if (peak == 0) SILENCE_DB else max(SILENCE_DB, 20.0 * log10(peak / 32768.0))
    }

    const val SILENCE_DB = -120.0
}

/** End-of-speech tuning. The defaults are a first guess; the car will say whether they hold. */
data class UtteranceTuning(
    /** Speech is this far above the running noise floor. */
    val speechAboveNoiseDb: Double = 10.0,
    /** And at least this loud, so a silent cabin's hiss never counts. */
    val speechFloorDb: Double = -50.0,
    /** Frames in a row above the threshold before speech is said to have started. */
    val startFrames: Int = 3,
    /** Quiet after speech that ends the utterance. */
    val endSilenceMs: Long = 900,
    /** No speech by then: stop with [MicEnd.NO_SPEECH]. */
    val noSpeechMs: Long = 6_000,
)

/**
 * Decides, frame by frame, when an utterance has ended. Pure: fed 20 ms frame levels in
 * dBFS, it takes the noise floor from the quietest of the first [CALIBRATION_FRAMES]
 * (the route has opened and the driver has not started yet), then follows it: quickly
 * down, steadily up through quiet frames so changing road noise settles it, and at most
 * [RISE_DB_PER_LOUD_FRAME] through loud ones, so a long sentence never becomes the floor.
 * Speech is a run of frames over the floor; quiet after speech ends the utterance.
 */
class UtteranceDetector(private val tuning: UtteranceTuning = UtteranceTuning()) {
    var noiseDb: Double = Double.NaN
        private set
    var speechFrames: Int = 0
        private set
    var started: Boolean = false
        private set
    private var frames = 0
    private var run = 0
    private var quietMs = 0L

    /** Feeds one frame's level; returns the end, or null to keep listening. */
    fun feed(levelDb: Double): MicEnd? {
        frames++
        if (frames <= CALIBRATION_FRAMES) {
            noiseDb = if (noiseDb.isNaN()) levelDb else minOf(noiseDb, levelDb)
            return null
        }
        val threshold = max(noiseDb + tuning.speechAboveNoiseDb, tuning.speechFloorDb)
        val loud = levelDb >= threshold
        noiseDb = when {
            levelDb < noiseDb -> noiseDb + (levelDb - noiseDb) * 0.5
            loud -> noiseDb + minOf(RISE_DB_PER_LOUD_FRAME, levelDb - noiseDb)
            else -> noiseDb + (levelDb - noiseDb) * 0.05
        }
        if (loud) {
            run++
            quietMs = 0
            if (started) speechFrames++
            if (!started && run >= tuning.startFrames) {
                started = true
                speechFrames += run
            }
        } else {
            run = 0
            if (started) quietMs += MicRules.FRAME_MS
        }
        if (started && quietMs >= tuning.endSilenceMs) return MicEnd.UTTERANCE_END
        if (!started && frames * MicRules.FRAME_MS >= tuning.noSpeechMs) return MicEnd.NO_SPEECH
        return null
    }

    companion object {
        /** 100 ms: long enough to hear the cabin, short enough to miss nothing said. */
        const val CALIBRATION_FRAMES = 5

        /** 1 dB a second while loud. */
        const val RISE_DB_PER_LOUD_FRAME = 0.02
    }
}

/**
 * Counts for the report's `[voice_mic]` section: how many captures, each outcome, and the
 * slowest route open. Never audio, text, names or addresses.
 */
class MicTally {
    private val outcomes = sortedMapOf<String, Int>()
    var captures = 0
        private set
    var callModeNeeded = 0
        private set
    var lastRouteMs = -1L
        private set
    var maxRouteMs = -1L
        private set
    var heardMs = 0L
        private set
    var lastSource = "-"
        private set

    @Synchronized
    fun record(result: MicResult) {
        captures++
        val stats = when (result) {
            is MicResult.Heard -> {
                bump(result.end.name.lowercase())
                heardMs += result.clip.durationMs
                result.stats
            }
            is MicResult.Failed -> {
                bump(result.failure.name.lowercase())
                result.stats
            }
        }
        if (stats != null && stats.routeMs >= 0) {
            lastRouteMs = stats.routeMs
            maxRouteMs = max(maxRouteMs, stats.routeMs)
            if (stats.neededCallMode) callModeNeeded++
            if (stats.source != "-") lastSource = stats.source
        }
    }

    private fun bump(key: String) {
        outcomes[key] = (outcomes[key] ?: 0) + 1
    }

    @Synchronized
    fun report(): String = buildString {
        appendLine("mic_captures=$captures")
        appendLine("mic_outcomes=${outcomes.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "-" }}")
        appendLine("mic_route_ms_last=${if (lastRouteMs < 0) "-" else lastRouteMs}")
        appendLine("mic_route_ms_max=${if (maxRouteMs < 0) "-" else maxRouteMs}")
        appendLine("mic_call_mode_needed=$callModeNeeded")
        appendLine("mic_source_last=$lastSource")
        appendLine("mic_heard_ms_total=$heardMs")
    }
}

/**
 * The Android side: routes capture to the car's hands-free link, records 16 kHz mono,
 * stops at the end of the utterance, and always puts the route back so music returns to
 * A2DP and turn prompts play.
 *
 * Routing is `AudioManager.setCommunicationDevice` (API 31+, the replacement for
 * `startBluetoothSco`). Some phones open SCO only once the audio mode is
 * `MODE_IN_COMMUNICATION`, so if the link has not opened after [MicRules.ROUTE_PLAIN_MS]
 * the mode is switched too, and the report says it was needed. The recorder asks for the
 * SCO input as its preferred device and, once started, is checked to be reading from it;
 * it is checked again every quarter second while listening, so a link that drops cannot
 * quietly turn into the phone's microphone.
 *
 * Holds transient exclusive audio focus while listening, so music pauses and nothing else
 * talks over the driver; Speaker treats the call mode as a call and stays quiet meanwhile.
 */
object BluetoothMic {
    val tally = MicTally()

    @Volatile
    private var current: Capture? = null

    /** A running capture. [finish] keeps what was heard; [cancel] drops it. Either is safe twice. */
    class Capture internal constructor() {
        @Volatile internal var finishing = false

        @Volatile internal var cancelled = false

        fun finish() {
            finishing = true
        }

        fun cancel() {
            cancelled = true
        }
    }

    /**
     * Starts listening on its own thread and calls [onResult] once, on the main thread.
     * Returns null, after calling [onResult] with [MicFailure.BUSY], if a capture is already running.
     */
    fun start(context: Context, tuning: UtteranceTuning = UtteranceTuning(), onResult: (MicResult) -> Unit): Capture? {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val capture = Capture()
        synchronized(this) {
            if (current != null) {
                val busy = MicResult.Failed(MicFailure.BUSY, null)
                main.post { onResult(busy) }
                return null
            }
            current = capture
        }
        Thread({
            val result = try {
                run(app, capture, tuning)
            } catch (e: RuntimeException) {
                SessionController.note("voice mic: recorder failed (${e.javaClass.simpleName})")
                MicResult.Failed(MicFailure.RECORDER_FAILED, null)
            } finally {
                synchronized(this) { current = null }
            }
            tally.record(result)
            SessionController.note("voice mic: ${describe(result)}")
            main.post { onResult(result) }
        }, "voice-mic").start()
        return capture
    }

    /** Whether a car hands-free microphone is connected now. Cheap; for greying out a button. */
    fun available(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return MicRules.pickRoute(audio.availableCommunicationDevices.map { MicPort(it.type, it.id) }) != null
    }

    /** One line for the event log: the outcome and numbers, nothing heard. */
    fun describe(result: MicResult): String = when (result) {
        is MicResult.Heard -> with(result.stats) {
            "${result.end.name.lowercase()} heard=${heardMs}ms speech=${speechMs}ms route=${routeMs}ms " +
                "call_mode=$neededCallMode source=$source first_sound=${firstSoundMs}ms " +
                "peak=${peakDbfs.toInt()}dBFS noise=${noiseDbfs.toInt()}dBFS"
        }
        is MicResult.Failed -> result.failure.name.lowercase() +
            (result.stats?.let { " route=${it.routeMs}ms call_mode=${it.neededCallMode}" } ?: "")
    }

    @SuppressLint("MissingPermission")
    private fun run(context: Context, capture: Capture, tuning: UtteranceTuning): MicResult {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return MicResult.Failed(MicFailure.NO_PERMISSION, null)
        }
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return MicResult.Failed(MicFailure.RECORDER_FAILED, null)
        if (inCall(audio)) return MicResult.Failed(MicFailure.IN_CALL, null)
        val target = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: return MicResult.Failed(MicFailure.NO_BLUETOOTH_MIC, null)

        val pressed = SystemClock.elapsedRealtime()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        audio.requestAudioFocus(focus)
        val previousMode = audio.mode
        var changedMode = false
        try {
            if (!audio.setCommunicationDevice(target)) {
                return MicResult.Failed(MicFailure.ROUTE_REFUSED, null)
            }
            // Wait for the link to open, switching to call mode part way if it has not.
            while (!routeOpen(audio, target)) {
                val waited = SystemClock.elapsedRealtime() - pressed
                if (capture.cancelled) return MicResult.Failed(MicFailure.CANCELLED, null)
                if (waited >= MicRules.ROUTE_TIMEOUT_MS) {
                    return MicResult.Failed(MicFailure.ROUTE_TIMEOUT, stats(waited, changedMode, "-"))
                }
                if (!changedMode && waited >= MicRules.ROUTE_PLAIN_MS) {
                    audio.mode = AudioManager.MODE_IN_COMMUNICATION
                    changedMode = true
                }
                Thread.sleep(50)
            }
            val routeMs = SystemClock.elapsedRealtime() - pressed
            var lastFailure = MicFailure.NOT_BLUETOOTH
            for (source in MicRules.SOURCES) {
                val outcome = record(audio, capture, tuning, source, routeMs, changedMode)
                if (outcome is MicResult.Failed && outcome.failure == MicFailure.NOT_BLUETOOTH) {
                    lastFailure = outcome.failure
                    continue
                }
                return outcome
            }
            return MicResult.Failed(lastFailure, stats(routeMs, changedMode, "-"))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return MicResult.Failed(MicFailure.CANCELLED, null)
        } finally {
            // Put everything back, so music returns to A2DP and prompts play again.
            audio.clearCommunicationDevice()
            if (changedMode && audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = previousMode
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private fun routeOpen(audio: AudioManager, target: AudioDeviceInfo): Boolean =
        audio.communicationDevice?.id == target.id &&
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    @SuppressLint("MissingPermission")
    private fun record(
        audio: AudioManager,
        capture: Capture,
        tuning: UtteranceTuning,
        source: String,
        routeMs: Long,
        callMode: Boolean,
    ): MicResult {
        val platformSource = when (source) {
            MicRules.SOURCE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            MicRules.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return MicResult.Failed(MicFailure.RECORDER_FAILED, stats(routeMs, callMode, source))
        val recorder = AudioRecord(
            platformSource,
            MicRules.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, MicRules.FRAME_SAMPLES * 2 * 8),
        )
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return MicResult.Failed(MicFailure.RECORDER_FAILED, stats(routeMs, callMode, source))
            }
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?.let { recorder.setPreferredDevice(it) }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return MicResult.Failed(MicFailure.RECORDER_FAILED, stats(routeMs, callMode, source))
            }
            val buffer = ShortArray(MicRules.MAX_SAMPLES)
            var count = 0
            val detector = UtteranceDetector(tuning)
            var peak = MicRules.SILENCE_DB
            var firstSound = -1L
            var checked = false
            var sinceCheck = 0
            var end: MicEnd? = null
            val started = SystemClock.elapsedRealtime()
            while (end == null) {
                if (capture.cancelled) return MicResult.Failed(MicFailure.CANCELLED, null)
                if (capture.finishing) {
                    end = MicEnd.FINISHED
                    break
                }
                val want = minOf(MicRules.FRAME_SAMPLES, MicRules.MAX_SAMPLES - count)
                if (want <= 0) {
                    end = MicEnd.MAX_LENGTH
                    break
                }
                val read = recorder.read(buffer, count, want)
                if (read < 0) return MicResult.Failed(MicFailure.RECORDER_FAILED, stats(routeMs, callMode, source))
                if (read == 0) {
                    if (SystemClock.elapsedRealtime() - started > MicRules.STALL_MS && count == 0) {
                        return MicResult.Failed(MicFailure.RECORDER_FAILED, stats(routeMs, callMode, source))
                    }
                    continue
                }
                // The route is only known once audio flows; check the first frame, then every 250 ms.
                if (!checked || sinceCheck >= 12) {
                    if (!MicRules.routedToCar(recorder.routedDevice?.type)) {
                        // Heard from the wrong microphone: dropped before anything else sees it.
                        return MicResult.Failed(
                            if (checked) MicFailure.LOST else MicFailure.NOT_BLUETOOTH,
                            stats(routeMs, callMode, source),
                        )
                    }
                    checked = true
                    sinceCheck = 0
                }
                sinceCheck++
                val level = MicRules.dbfs(buffer, count, read)
                peak = max(peak, MicRules.peakDbfs(buffer, count, read))
                if (firstSound < 0 && level > MicRules.SILENCE_DB) firstSound = count * 1000L / MicRules.SAMPLE_RATE
                count += read
                end = detector.feed(level)
            }
            val heard = if (end == MicEnd.NO_SPEECH) 0 else count
            val clip = VoiceClip(buffer.copyOf(heard))
            return MicResult.Heard(
                clip,
                end ?: MicEnd.FINISHED,
                MicStats(
                    routeMs = routeMs,
                    neededCallMode = callMode,
                    source = source,
                    heardMs = clip.durationMs,
                    speechMs = detector.speechFrames * MicRules.FRAME_MS,
                    firstSoundMs = firstSound,
                    peakDbfs = peak,
                    noiseDbfs = if (detector.noiseDb.isNaN()) MicRules.SILENCE_DB else detector.noiseDb,
                ),
            )
        } finally {
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {
                // Never started; nothing to stop.
            }
            recorder.release()
        }
    }

    private fun stats(routeMs: Long, callMode: Boolean, source: String) = MicStats(
        routeMs = routeMs,
        neededCallMode = callMode,
        source = source,
        heardMs = 0,
        speechMs = 0,
        firstSoundMs = -1,
        peakDbfs = MicRules.SILENCE_DB,
        noiseDbfs = MicRules.SILENCE_DB,
    )

    /** A call, a ringing phone, or another app's voice call holds the link (as Speaker judges it). */
    private fun inCall(audio: AudioManager): Boolean {
        val phase = CallControl.state.phase
        if (phase != CallPhase.NONE && phase != CallPhase.ENDED) return true
        return when (audio.mode) {
            AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_CALL_SCREENING -> true
            else -> false
        }
    }
}
