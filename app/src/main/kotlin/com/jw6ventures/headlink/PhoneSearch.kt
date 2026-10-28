package com.jw6ventures.headlink

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Typed search on the phone (docs/navigation.md, P11.4): the connection screen's
 * destination box asks for suggestions as James types, 250 ms after the last keystroke,
 * and a newer keystroke cancels a search still running.
 *
 * [AddressSearch] does the work on one background thread over [ObfLibrary]'s open files;
 * this only debounces, cancels and counts. **Nothing typed, suggested or picked is kept
 * or reported** (P6.4): [report] carries counts only, and there is no history.
 */
object PhoneSearch {
    /** After the last keystroke, before a search starts. */
    const val DEBOUNCE_MS = 250L

    /** Rows shown under the box. */
    const val SHOWN = 8

    /** Shorter text is not searched: one letter matches half of Texas. */
    const val MIN_CHARS = 3

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "phone-search").apply { isDaemon = true }
        }

    /** Bumped by every keystroke; a search whose number is no longer current stops. */
    private val generation = AtomicLong()

    @Volatile
    private var engine: AddressSearch? = null

    private var pending: Runnable? = null

    // Counts, for the report. Main thread writes these except [failures] and [cancelled].
    @Volatile private var queries = 0
    @Volatile private var cancelled = 0
    @Volatile private var failures = 0
    @Volatile private var suggestionsShown = 0
    @Volatile private var emptyResults = 0
    @Volatile private var picks = 0

    /**
     * The box's text changed: forget any search under way and, unless [text] is too
     * short, search it [DEBOUNCE_MS] from now. [near] is read when the search starts.
     * [deliver] gets up to [SHOWN] suggestions on the main thread, or an empty list for
     * text too short or nothing found; it is never called for a search overtaken by a
     * newer keystroke. Main thread.
     */
    fun typed(context: Context, text: String, near: () -> Pair<Double, Double>?, deliver: (List<Suggestion>) -> Unit) {
        val app = context.applicationContext
        val mine = generation.incrementAndGet()
        pending?.let(main::removeCallbacks)
        pending = null
        val query = text.trim()
        if (query.length < MIN_CHARS || !looksLikeSearch(query)) {
            deliver(emptyList())
            return
        }
        val start = Runnable {
            pending = null
            if (generation.get() != mine) return@Runnable
            queries++
            val point = near()
            worker.execute {
                val stale = { generation.get() != mine }
                if (stale()) {
                    cancelled++
                    return@execute
                }
                val found = try {
                    engine(app).suggest(query, point?.first, point?.second, SHOWN, stale)
                } catch (e: Exception) {
                    // A file replaced mid-read, a malformed one: no suggestions this time.
                    failures++
                    emptyList()
                }
                if (stale()) {
                    cancelled++
                    return@execute
                }
                main.post {
                    if (generation.get() != mine) return@post
                    val shown = found.take(SHOWN)
                    suggestionsShown += shown.size
                    if (shown.isEmpty()) emptyResults++
                    deliver(shown)
                }
            }
        }
        pending = start
        main.postDelayed(start, DEBOUNCE_MS)
    }

    /** Stops any search waiting or under way: the box was cleared or the screen left. */
    fun cancel() {
        generation.incrementAndGet()
        pending?.let(main::removeCallbacks)
        pending = null
    }

    /** A suggestion was tapped; the place itself is not recorded. */
    fun picked() {
        picks++
    }

    /**
     * Text the old box already reads — coordinates, a `geo:` URI, an OsmAnd link, two
     * points — is not searched: it routes as before when Route is pressed.
     */
    fun looksLikeSearch(text: String): Boolean {
        if (RouteRequest.parse(text) != null) return false
        return DestinationParser.parseText(text) !is ParsedDestination.Found
    }

    private fun engine(context: Context): AddressSearch =
        engine ?: synchronized(this) {
            engine ?: AddressSearch { ObfLibrary.sources(context) }.also { engine = it }
        }

    /** `[routing]` line: counts only, never what was typed or chosen. */
    fun report(): String =
        "search_queries=$queries search_cancelled=$cancelled search_failures=$failures " +
            "search_suggestions_shown=$suggestionsShown search_empty=$emptyResults search_picks=$picks\n" +
            // The engine's own counts: results by kind and query times.
            (engine?.stats?.snapshot()?.line()?.let { "$it\n" } ?: "")
}
