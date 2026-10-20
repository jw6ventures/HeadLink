package com.jw6ventures.headlink

/**
 * A media player app the idle media pane can offer to resume (P7.5, plan §5.5).
 *
 * Pure JVM, like [Favorite], so the ordering the pane shows is host-tested. The Android
 * side that finds players and resumes them is [MediaPlayers].
 *
 * **The label is listening history.** It is drawn on the dashboard and nowhere else:
 * never logged, never put in a report (spec §10). [key] is the package name, which is
 * just as private, and is held only to find the app again when Resume is pressed.
 */
data class Player(
    /** The package name. Stable across a session, and unique: one row per app. */
    val key: String,
    /** The app's user-visible name, or the package name when it cannot be resolved. */
    val label: String,
    /**
     * Whether the app has a live media session right now. Resume then goes to that
     * session; without one it goes through the app's `MediaBrowserService`.
     */
    val hasSession: Boolean,
    /** Whether the app exports a `MediaBrowserService`, i.e. could be resumed cold. */
    val hasBrowser: Boolean,
    /**
     * `SystemClock.elapsedRealtime()` milliseconds when the app was last seen playing,
     * or 0 when this process has never seen it play. Elapsed rather than wall time so a
     * clock change mid-drive cannot reorder the list.
     */
    val lastActiveMillis: Long,
)

/** The pure rules for the player list. */
object Players {
    /**
     * More rows than any pane can fit at media-button height (about 100 px on a 1080p
     * pane), so the pane decides how many to draw; the bound only stops a phone with
     * dozens of podcast apps from building a long list every refresh.
     */
    const val MAX = 12

    /**
     * Browser-service apps never seen playing that the list keeps, at most. Every app that
     * exports a `MediaBrowserService` can be listed, and on a phone that includes system
     * components and apps that browse media without playing it; the pane shows two rows,
     * and they should be players the driver actually uses (the part-2a lab frame offered
     * "Bluetooth" and "Android Open Source…", neither of them a player).
     */
    const val UNPLAYED_MAX = 3

    /**
     * Merges apps with a live session and apps with a browser service into one list,
     * one row per package, **most recently active first** (plan §5.5).
     *
     * [played] is the packages that have really had a playing session on this phone,
     * most recent first, as [MediaPlayers] remembers them between sessions. It orders
     * what this process has not seen play yet (every app, at the start of a drive), so a
     * cold list starts with the players actually used rather than in alphabetical order,
     * and apps never seen playing at all are capped at [UNPLAYED_MAX].
     *
     * Ties put a live session first, because Resume on a session is certain where a cold
     * resume depends on the app; then the remembered order; then the label, so the order
     * is stable from one refresh to the next and a row does not jump under a finger.
     */
    @JvmOverloads
    fun merge(sessions: List<Player>, browsers: List<Player>, played: List<String> = emptyList()): List<Player> {
        val byKey = LinkedHashMap<String, Player>()
        for (player in sessions + browsers) {
            val known = byKey[player.key]
            byKey[player.key] = if (known == null) {
                player
            } else {
                known.copy(
                    hasSession = known.hasSession || player.hasSession,
                    hasBrowser = known.hasBrowser || player.hasBrowser,
                    lastActiveMillis = maxOf(known.lastActiveMillis, player.lastActiveMillis),
                    // A session's label came from the same resolution; either will do,
                    // but a resolved name beats a package name.
                    label = if (known.label == known.key) player.label else known.label,
                )
            }
        }
        val rank = played.withIndex().associate { (index, key) -> key to index }
        var unplayed = 0
        return byKey.values
            .filter { it.hasSession || it.hasBrowser }
            .sortedWith(
                compareByDescending<Player> { it.lastActiveMillis }
                    .thenByDescending { it.hasSession }
                    .thenBy { rank[it.key] ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                    .thenBy { it.key },
            )
            .filter { player ->
                val used = player.hasSession || player.lastActiveMillis > 0 || player.key in rank
                used || unplayed++ < UNPLAYED_MAX
            }
            .take(MAX)
    }
}

/** How a Resume press ended, for the caller and the report. */
enum class ResumeResult {
    /** `play()` went to a live session. */
    PLAYED_SESSION,

    /** The app's browser service answered the recent-media hint and was told to play. */
    PLAYED_RECENT,

    /** The app is gone, or has neither a session nor a browser service. */
    NOT_FOUND,

    /** The browser service refused the connection or offered no session. */
    REFUSED,

    /** Nothing answered within [MediaPlayers.RESUME_TIMEOUT_MS]. */
    TIMED_OUT,
}
