package com.jw6ventures.headlink

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The operator's speech model file (Settings → Voice input → Speech model) and the
 * recognizer loaded from it (spec §2.1 as amended 11 November 2026). The file is picked
 * with the Storage Access Framework, kept as a persisted read grant on that one document,
 * and read in place: it is never copied, downloaded or bundled. HeadLink says nothing about
 * where to get one beyond "a whisper.cpp model file".
 *
 * Loading takes a second or more and holds the model's memory (about 150 MB for base.en)
 * until [release]; call [engine] off the main thread.
 */
object SpeechModel {
    sealed class Loaded {
        data class Ready(val engine: Recognizer, val loadMs: Long) : Loaded()
        data class Unavailable(val problem: SpeechProblem) : Loaded()
    }

    private val lock = Object()
    private var engine: Recognizer? = null
    private var engineUri: Uri? = null

    /** Report counters: no words, no names, no audio (spec §10). */
    private val stats = SpeechStats()

    /** The chosen model file, or null. */
    fun file(context: Context): Uri? =
        context.contentResolver.persistedUriPermissions
            .firstOrNull { ObfRules.isSpeechModelGrant(it.isReadPermission, DocumentsContract.isTreeUri(it.uri)) }
            ?.uri

    /**
     * Makes [document] the model file, giving up the previous one's grant, and lets go of
     * a loaded model so the next [engine] reads the new file. Main thread; throws
     * [SecurityException] when the grant cannot be kept.
     */
    fun choose(context: Context, document: Uri) {
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(document, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        resolver.persistedUriPermissions
            .filter { it.uri != document && ObfRules.isSpeechModelGrant(it.isReadPermission, DocumentsContract.isTreeUri(it.uri)) }
            .forEach { resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        releaseLater()
        SessionController.note("speech model chosen")
    }

    /** Forgets the model file and frees a loaded model. Main thread. */
    fun forget(context: Context) {
        val resolver = context.contentResolver
        file(context)?.let { resolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        releaseLater()
        SessionController.note("speech model removed")
    }

    /** The file's display name, for the card. Null when the provider will not say. */
    fun name(context: Context, document: Uri): String? =
        try {
            context.contentResolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * The recognizer, loading the chosen file if it is not loaded yet. Blocking; never on
     * the main thread. A file that changed grant since the last load is read again.
     */
    fun engine(context: Context): Loaded {
        val app = context.applicationContext
        val uri = file(app) ?: return unavailable(SpeechProblem.NO_FILE)
        synchronized(lock) {
            engine?.let { if (engineUri == uri) return Loaded.Ready(it, 0) }
            engine?.close()
            engine = null
            engineUri = null
            if (SpeechCpu.missing(cpuinfo()).isNotEmpty()) return unavailable(SpeechProblem.CPU)
            if (WhisperNative.loadError != null) return unavailable(SpeechProblem.LIBRARY)
            val started = SystemClock.elapsedRealtime()
            val loaded = try {
                val descriptor = app.contentResolver.openFileDescriptor(uri, "r") ?: return unavailable(SpeechProblem.UNREADABLE)
                descriptor.use { WhisperEngine.load(it.fd) } ?: return unavailable(SpeechProblem.NOT_A_MODEL)
            } catch (e: IOException) {
                return unavailable(SpeechProblem.UNREADABLE)
            } catch (e: SecurityException) {
                return unavailable(SpeechProblem.UNREADABLE)
            } catch (e: IllegalArgumentException) {
                return unavailable(SpeechProblem.UNREADABLE)
            }
            val loadMs = SystemClock.elapsedRealtime() - started
            engine = CountingEngine(loaded, stats)
            engineUri = uri
            stats.loaded(loaded.model, loadMs)
            SessionController.note("speech model loaded: ${loaded.model.key} in $loadMs ms")
            return Loaded.Ready(engine!!, loadMs)
        }
    }

    /** [release] off the main thread: it waits for a load or a recognition in progress. */
    private fun releaseLater() {
        thread(name = "speech-model-release", isDaemon = true) { release() }
    }

    /** Frees a loaded model; the next [engine] reads the file again. Any thread; may wait. */
    fun release() {
        val old = synchronized(lock) {
            val current = engine
            engine = null
            engineUri = null
            current
        }
        old?.close()
    }

    /**
     * Settings → Voice input → Check model: loads the file and recognises [CHECK_SECONDS]
     * of silence, so the card can say what the model is and how long a short message
     * will take on this phone. Blocking; never on the main thread.
     */
    fun check(context: Context): SpeechCheck {
        // A model loaded only for the check is let go after it, so Settings does not leave
        // its memory held; one already in use stays.
        val wasLoaded = synchronized(lock) { engine != null }
        val loaded = engine(context)
        if (loaded !is Loaded.Ready) return SpeechCheck.Unavailable((loaded as Loaded.Unavailable).problem)
        try {
            val silence = ShortArray(CHECK_SECONDS * Recognizer.SAMPLE_RATE)
            val elapsed = when (val outcome = loaded.engine.transcribe(silence)) {
                is Transcript.Text -> outcome.elapsedMs
                is Transcript.Silence -> outcome.elapsedMs
                else -> return SpeechCheck.Failed(loaded.engine.model)
            }
            return SpeechCheck.Ready(loaded.engine.model, loaded.loadMs, elapsed)
        } finally {
            if (!wasLoaded) release()
        }
    }

    /** `key=value` lines for the session report's [speech_input] section. */
    fun report(context: Context): String =
        "speech_model_chosen=${file(context) != null} " + stats.report()

    private fun unavailable(problem: SpeechProblem): Loaded {
        stats.unavailable(problem)
        return Loaded.Unavailable(problem)
    }

    private fun cpuinfo(): String =
        try {
            File("/proc/cpuinfo").readText()
        } catch (e: IOException) {
            ""
        }

    const val CHECK_SECONDS = 5
}

/** What Check model found. */
sealed class SpeechCheck {
    data class Ready(val model: SpeechModelInfo, val loadMs: Long, val fiveSecondsMs: Long) : SpeechCheck()
    data class Unavailable(val problem: SpeechProblem) : SpeechCheck()
    data class Failed(val model: SpeechModelInfo) : SpeechCheck()
}

/** Counts every transcription for the report, and nothing else. */
private class CountingEngine(private val inner: Recognizer, private val stats: SpeechStats) : Recognizer by inner {
    override fun transcribe(samples: ShortArray, count: Int, prompt: String?): Transcript =
        inner.transcribe(samples, count, prompt).also(stats::record)
}
