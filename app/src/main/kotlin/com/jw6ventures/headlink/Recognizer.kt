package com.jw6ventures.headlink

import java.io.Closeable

/**
 * Turns one spoken utterance into text, on the phone (spec §2.1 as amended 11 November
 * 2026). The microphone side (the Bluetooth capture) hands over a whole utterance as
 * [SAMPLE_RATE] Hz mono 16-bit signed PCM held in memory; nothing is streamed and nothing
 * is written anywhere. See docs/audio-and-speech.md, "Speech recognition".
 *
 * [transcribe] blocks for about a second per few seconds of speech: call it off the main
 * thread. One utterance at a time; a second caller waits for the first.
 */
interface Recognizer : Closeable {
    /** What was loaded, for the Settings card and the report. */
    val model: SpeechModelInfo

    /**
     * Recognises the first [count] samples of [samples]. [prompt] primes the recognizer
     * with words it should expect (contact names, "Ready to send?" answers); it is never
     * kept. The caller should clear [samples] once this returns.
     */
    fun transcribe(samples: ShortArray, count: Int = samples.size, prompt: String? = null): Transcript

    /** Stops a running [transcribe], which then returns [Transcript.Cancelled]. Any thread. */
    fun cancel()

    /** Frees the model. A [transcribe] still running finishes first. */
    override fun close()

    companion object {
        /** Whisper's own rate, and the rate the hands-free link delivers in wideband. */
        const val SAMPLE_RATE = 16_000

        /** Whisper reads 30 s windows; longer audio is refused rather than cut silently. */
        const val MAX_SAMPLES = 30 * SAMPLE_RATE

        /** Shorter than this is a tap, not an utterance. */
        const val MIN_SAMPLES = SAMPLE_RATE * 3 / 10
    }
}

/** What a model file turned out to be: whisper.cpp's size name and whether it is English-only. */
data class SpeechModelInfo(val size: String, val englishOnly: Boolean) {
    /** For the report: `base.en`, `small` and so on, as the model files are usually named. */
    val key: String get() = if (englishOnly) "$size.en" else size

    companion object {
        /** Reads WhisperNative.nativeDescribe's `"<size> en|multi"`. */
        fun parse(described: String?): SpeechModelInfo {
            val parts = described?.trim()?.split(' ').orEmpty()
            val size = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: "unknown"
            return SpeechModelInfo(size, parts.getOrNull(1) != "multi")
        }
    }
}

/** The outcome of one [Recognizer.transcribe]. Only [Text] carries words. */
sealed class Transcript {
    /** Words were heard. [audioMs] is the utterance's length, [elapsedMs] how long recognising it took. */
    data class Text(val text: String, val audioMs: Long, val elapsedMs: Long) : Transcript() {
        override fun toString() = "Text(${text.length} chars, audioMs=$audioMs, elapsedMs=$elapsedMs)"
    }

    /** The recognizer ran and heard no words (silence, noise, a cough). */
    data class Silence(val audioMs: Long, val elapsedMs: Long) : Transcript()

    /** [Recognizer.cancel] stopped it. */
    object Cancelled : Transcript()

    /** The audio was refused before recognising it, or whisper.cpp failed. [reason] is a report key. */
    data class Failed(val reason: String) : Transcript()

    companion object {
        const val TOO_SHORT = "too_short"
        const val TOO_LONG = "too_long"
        const val CLOSED = "closed"
        const val ENGINE = "engine"
    }
}

/** Why there is no recognizer. [key] goes in the report; Settings says it in words. */
enum class SpeechProblem(val key: String) {
    NO_FILE("no_file"),
    UNREADABLE("unreadable"),
    NOT_A_MODEL("not_a_model"),
    CPU("cpu_unsupported"),
    LIBRARY("library_missing"),
}

/** Whisper's output made into what the operator said: no bracketed sound tags, no stray spaces. */
object SpeechText {
    // Whisper writes non-speech as [BLANK_AUDIO], (music), *coughs*, ♪ and the like.
    private val TAGS = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*|[♪♫]+""")
    private val SPACES = Regex("""\s+""")

    /** The cleaned text, or "" when nothing but tags and punctuation was heard. */
    fun clean(raw: String): String {
        val text = TAGS.replace(raw, " ").replace(SPACES, " ").trim()
        // A lone "." or "-" left from a tag is not speech.
        return if (text.none { it.isLetterOrDigit() }) "" else text
    }
}

/**
 * [Recognizer] over whisper.cpp. Build one with [load]; [SpeechModel] does so from the
 * operator's model file. Plain JVM, so the host test drives it with the x86_64 library.
 */
class WhisperEngine private constructor(
    @Volatile private var handle: Long,
    override val model: SpeechModelInfo,
    private val threads: Int,
    private val clock: () -> Long,
) : Recognizer {
    private val lock = Object()

    /** Held to free the handle, and by [cancel], which never waits for [lock]. */
    private val freeLock = Object()

    @Volatile
    private var running = false

    @Volatile
    private var cancelled = false

    override fun transcribe(samples: ShortArray, count: Int, prompt: String?): Transcript {
        require(count in 0..samples.size) { "count $count outside 0..${samples.size}" }
        if (count < Recognizer.MIN_SAMPLES) return Transcript.Failed(Transcript.TOO_SHORT)
        if (count > Recognizer.MAX_SAMPLES) return Transcript.Failed(Transcript.TOO_LONG)
        synchronized(lock) {
            if (handle == 0L) return Transcript.Failed(Transcript.CLOSED)
            cancelled = false
            running = true
            val started = clock()
            val raw = try {
                WhisperNative.nativeTranscribe(handle, samples, count, prompt?.take(MAX_PROMPT), LANGUAGE, threads)
            } finally {
                running = false
            }
            val elapsed = clock() - started
            if (cancelled) return Transcript.Cancelled
            raw ?: return Transcript.Failed(Transcript.ENGINE)
            val audioMs = count * 1000L / Recognizer.SAMPLE_RATE
            val text = SpeechText.clean(raw)
            return if (text.isEmpty()) Transcript.Silence(audioMs, elapsed) else Transcript.Text(text, audioMs, elapsed)
        }
    }

    override fun cancel() {
        cancelled = true
        synchronized(freeLock) {
            if (running && handle != 0L) WhisperNative.nativeCancel(handle)
        }
    }

    override fun close() {
        cancel()
        synchronized(lock) {
            synchronized(freeLock) {
                if (handle != 0L) WhisperNative.nativeFree(handle)
                handle = 0L
            }
        }
    }

    companion object {
        /** English only for the first version (spec §2.1 as amended); a multilingual model is told so. */
        const val LANGUAGE = "en"

        /** Whisper keeps at most about 224 prompt tokens; a long contact list is cut, not refused. */
        const val MAX_PROMPT = 800

        /**
         * Loads the model readable at [fd] (from its current position; not closed here).
         * Null when the library is missing or the file is not a model whisper.cpp reads.
         */
        fun load(fd: Int, threads: Int = defaultThreads(), clock: () -> Long = System::currentTimeMillis): WhisperEngine? {
            if (WhisperNative.loadError != null) return null
            val handle = WhisperNative.nativeLoad(fd)
            if (handle == 0L) return null
            val info = SpeechModelInfo.parse(WhisperNative.nativeDescribe(handle))
            return WhisperEngine(handle, info, threads.coerceAtLeast(1), clock)
        }

        /** Big cores only, about: whisper.cpp slows down when a thread lands on a little core. */
        fun defaultThreads(cores: Int = Runtime.getRuntime().availableProcessors()): Int = (cores / 2).coerceIn(1, 4)
    }
}

/**
 * The speech report line's counts (spec §2.1 as amended, §10): which model size, load and
 * recognition times, outcomes. Never a word, a name or a sample. Thread-safe.
 */
class SpeechStats {
    private var model = "-"
    private var loads = 0
    private var lastLoadMs = -1L
    private var unavailable = 0
    private var lastProblem = "-"
    private var heard = 0
    private var nothing = 0
    private var cancelled = 0
    private val failed = sortedMapOf<String, Int>()
    private var audioMs = 0L
    private var elapsedMs = 0L
    private var slowestMs = 0L

    @Synchronized
    fun loaded(info: SpeechModelInfo, loadMs: Long) {
        model = info.key
        loads++
        lastLoadMs = loadMs
    }

    @Synchronized
    fun unavailable(problem: SpeechProblem) {
        unavailable++
        lastProblem = problem.key
    }

    @Synchronized
    fun record(outcome: Transcript) {
        when (outcome) {
            is Transcript.Text -> {
                heard++
                timed(outcome.audioMs, outcome.elapsedMs)
            }
            is Transcript.Silence -> {
                nothing++
                timed(outcome.audioMs, outcome.elapsedMs)
            }
            Transcript.Cancelled -> cancelled++
            is Transcript.Failed -> failed.merge(outcome.reason, 1, Int::plus)
        }
    }

    private fun timed(audio: Long, elapsed: Long) {
        audioMs += audio
        elapsedMs += elapsed
        slowestMs = maxOf(slowestMs, elapsed)
    }

    @Synchronized
    fun report(): String =
        "speech_model=$model speech_model_loads=$loads speech_model_load_ms=${if (lastLoadMs < 0) "-" else lastLoadMs} " +
            "speech_unavailable=$unavailable speech_unavailable_last=$lastProblem " +
            "speech_heard=$heard speech_nothing=$nothing speech_cancelled=$cancelled " +
            "speech_failed=${if (failed.isEmpty()) "0" else failed.entries.joinToString(",") { "${it.key}:${it.value}" }} " +
            "speech_audio_ms=$audioMs speech_recognise_ms=$elapsedMs speech_slowest_ms=$slowestMs"
}
