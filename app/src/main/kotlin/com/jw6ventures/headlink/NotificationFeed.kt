package com.jw6ventures.headlink

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A plain description of one posted notification: exactly what the filter and the flex
 * tile need, and nothing else (P7.8, plan §5.8).
 *
 * [MediaAccessService] builds it from a `StatusBarNotification` and its ranking, and only
 * while [NotificationFeed.active]. It is pure JVM so the filter is host-tested
 * (NotificationFeedTest) against the same class the app runs.
 */
data class NotificationFacts(
    /** `StatusBarNotification.getKey()`: stable across updates, what removal names. */
    val key: String,
    val packageName: String,
    /** The posting app's user-visible name, or its package name. */
    val appLabel: String,
    /** The channel's importance from the ranking; `NotificationManager.IMPORTANCE_*`. */
    val importance: Int,
    /** `FLAG_ONGOING_EVENT` or `FLAG_FOREGROUND_SERVICE`: a status, not a message. */
    val ongoing: Boolean,
    /** `FLAG_GROUP_SUMMARY`: a stand-in for its children, which are posted as well. */
    val groupSummary: Boolean,
    /** `Notification.category`, e.g. `call`, `msg`. */
    val category: String?,
    /** `Notification.EXTRA_TEMPLATE`, the style's class name, e.g. `android.app.Notification$CallStyle`. */
    val template: String?,
    /** Whether `EXTRA_MEDIA_SESSION` is set: a player's notification. */
    val hasMediaSession: Boolean,
    /** `EXTRA_TITLE`. */
    val title: String?,
    /** `EXTRA_TEXT`, else the big text. */
    val text: String?,
    /** The last `MessagingStyle` message's sender, when there is one and it is not the user. */
    val sender: String?,
    /** The last `MessagingStyle` message's text. */
    val messageText: String?,
    /** `StatusBarNotification.getPostTime()`, wall-clock milliseconds. */
    val postedMillis: Long,
)

/** Why a notification is or is not on the tile; each is counted in the report. */
enum class NotificationVerdict {
    SHOWN,

    /** This app's own session notification. */
    OWN,

    /** Category `call` or `CallStyle`: calls have their own tile source (P7.9). */
    CALL,

    /** A player's notification: the media pane already shows what is playing. */
    MEDIA,
    ONGOING,
    GROUP_SUMMARY,

    /** Below `IMPORTANCE_DEFAULT`: silent, minimum, or unknown. */
    NOT_ALERTING,

    /** Nothing to draw: no title, sender or text. */
    EMPTY,

    /** An app the operator excluded in Settings. */
    EXCLUDED,
}

/**
 * One row of the flex tile's notification list: who and which app, large; one line of
 * text, small (plan §5.8). Held in memory only, for the session, and never written or
 * logged anywhere (plan §9 item 6).
 */
data class DashNotification(
    val key: String,
    val appLabel: String,
    /** The message's sender, else the notification's title, else the app's name. */
    val title: String,
    /** The first line of the text, or empty. The full text is not kept. */
    val text: String,
    /** Wall-clock milliseconds, for "5 min ago" and the newest-first order. */
    val postedMillis: Long,
    /** The posting package, for the exclusion list. Not drawn and not reported. */
    val packageName: String,
)

/** The filter itself: pure, so every rule has a host test. */
object NotificationRules {
    // NotificationManager.IMPORTANCE_DEFAULT: the lowest importance that makes a sound
    // or vibrates, which is Android's own line between its "Alerting" and "Silent" shade
    // sections. Mirrored to keep this object free of Android classes.
    const val IMPORTANCE_DEFAULT = 3
    const val CATEGORY_CALL = "call"
    const val TEMPLATE_CALL = "android.app.Notification\$CallStyle"
    const val TEMPLATE_MEDIA = "android.app.Notification\$MediaStyle"
    const val TEMPLATE_MEDIA_CUSTOM = "android.app.Notification\$DecoratedMediaCustomViewStyle"

    /** Title and text bounds: a row shows one line of each, so more is never needed. */
    const val TITLE_MAX = 80
    const val TEXT_MAX = 160

    /** Whether this is a call notification: category `call` or `CallStyle`. */
    fun isCall(facts: NotificationFacts): Boolean =
        facts.category == CATEGORY_CALL || facts.template == TEMPLATE_CALL

    /**
     * The verdict, rules in a fixed order so each rejection is counted once, under the
     * most specific reason. Exclusion is last on purpose: an excluded app that would
     * otherwise have been shown is still an app that "has posted one", which is what the
     * settings list offers (plan §5.8).
     */
    fun verdict(facts: NotificationFacts, ownPackage: String, excluded: Set<String>): NotificationVerdict = when {
        facts.packageName == ownPackage -> NotificationVerdict.OWN
        isCall(facts) -> NotificationVerdict.CALL
        facts.hasMediaSession || facts.template == TEMPLATE_MEDIA || facts.template == TEMPLATE_MEDIA_CUSTOM ->
            NotificationVerdict.MEDIA
        facts.ongoing -> NotificationVerdict.ONGOING
        facts.groupSummary -> NotificationVerdict.GROUP_SUMMARY
        facts.importance < IMPORTANCE_DEFAULT -> NotificationVerdict.NOT_ALERTING
        row(facts) == null -> NotificationVerdict.EMPTY
        facts.packageName in excluded -> NotificationVerdict.EXCLUDED
        else -> NotificationVerdict.SHOWN
    }

    /**
     * The row for [facts], or null when there is nothing to draw.
     *
     * A messaging notification's title is often the conversation's name and its text a
     * count ("3 new messages"), so the last message's sender and text win where they
     * exist. With neither a sender nor a title, the row still says which app, as long as
     * there is text.
     */
    fun row(facts: NotificationFacts): DashNotification? {
        val who = clean(facts.sender, TITLE_MAX) ?: clean(facts.title, TITLE_MAX)
        val what = clean(facts.messageText, TEXT_MAX) ?: clean(facts.text, TEXT_MAX)
        if (who == null && what == null) return null
        return DashNotification(
            key = facts.key,
            appLabel = facts.appLabel,
            title = who ?: facts.appLabel,
            text = what.orEmpty(),
            postedMillis = facts.postedMillis,
            packageName = facts.packageName,
        )
    }

    /** First non-blank line, whitespace collapsed, cut to [max]; null when empty. */
    fun clean(value: String?, max: Int): String? {
        val line = value?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        val collapsed = line.replace(Regex("\\s+"), " ")
        return if (collapsed.length <= max) collapsed else collapsed.take(max - 1).trimEnd() + "…"
    }
}

/**
 * The flex tile's notification source (P7.8, plan §5.8): alerting notifications, newest
 * first, in memory only.
 *
 * **Off by default, and off means read nothing.** [MediaAccessService] forwards a posted
 * notification only while [active] — [enabled] and not [paused] — so with the setting off
 * the listener stays what it was before P7.8, an authorisation for media sessions that
 * reads no notification. Turning it off also drops every row and the list of apps seen.
 *
 * **Pause keeps nothing either.** While paused nothing is read; un-pausing asks the
 * listener for the phone's active notifications again ([reread]), so whatever is still
 * in the shade appears and nothing that arrived during the pause is lost (plan §5.8).
 *
 * Wiring these flags to saved settings and the Ⓟ icon is the settings and dashboard
 * slices' job; this object takes plain values.
 *
 * **Threads.** Every method is safe from any thread. [MediaAccessService] calls in on the
 * main thread, so listeners are called on the main thread when a notification arrives or
 * goes, and on whichever thread changed a flag otherwise.
 */
object NotificationFeed {
    /** Rows held. A tile shows four or five; the rest are for scrolling back a little. */
    const val MAX = 20

    /** The listener side: re-reads the phone's active notifications. */
    fun interface Source {
        /** Asynchronous. Answers through [replaceAll] on the listener's thread. */
        fun requestActive()
    }

    private val lock = Any()
    private val rows = ArrayList<DashNotification>()
    private val posters = LinkedHashSet<String>()
    private val listeners = CopyOnWriteArrayList<(List<DashNotification>) -> Unit>()
    private val verdicts = IntArray(NotificationVerdict.entries.size)
    private var removedCount = 0L
    private var rereads = 0L

    /** Set by [MediaAccessService] while the listener is connected. */
    @Volatile
    var source: Source? = null

    /** This app's package, whose own notifications are never shown. */
    @Volatile
    var ownPackage: String = ""

    /** "Notifications on the dashboard" (off by default). */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            val was = active
            field = value
            if (!value) forget(keepPosters = false)
            changed(was)
        }

    /** Ⓟ: nothing is read or shown while paused. Does not touch Do Not Disturb. */
    @Volatile
    var paused: Boolean = false
        set(value) {
            val was = active
            field = value
            if (value) forget(keepPosters = true)
            changed(was)
        }

    /** Packages the operator excluded. Setting it drops their rows at once. */
    @Volatile
    var exclusions: Set<String> = emptySet()
        set(value) {
            val copy = value.toSet()
            field = copy
            val removed = synchronized(lock) { rows.removeAll { it.packageName in copy } }
            if (removed) notifyListeners()
        }

    /** Whether posted notifications are read at all. */
    val active: Boolean
        get() = enabled && !paused

    fun addListener(listener: (List<DashNotification>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (List<DashNotification>) -> Unit) {
        listeners.remove(listener)
    }

    /** The rows now, newest first. A copy: the caller may keep it on any thread. */
    fun items(): List<DashNotification> = synchronized(lock) { rows.toList() }

    /**
     * Packages that have posted a notification the tile would show, excluded ones
     * included, for the settings exclusion list (plan §5.8). This session only.
     */
    fun posters(): Set<String> = synchronized(lock) { posters.toSet() }

    /**
     * A notification was posted or updated. Returns what the filter said. Ignored, and
     * [NotificationVerdict] of the rejection not counted, while not [active]; the service
     * checks [active] before it reads anything, so this is a second line.
     */
    fun posted(facts: NotificationFacts): NotificationVerdict? {
        if (!active) return null
        val verdict = NotificationRules.verdict(facts, ownPackage, exclusions)
        val change: Boolean
        synchronized(lock) {
            verdicts[verdict.ordinal]++
            if (verdict == NotificationVerdict.SHOWN || verdict == NotificationVerdict.EXCLUDED) {
                posters.add(facts.packageName)
            }
            // An update that no longer passes (it became ongoing, say) takes its row away.
            val hadRow = rows.removeAll { it.key == facts.key }
            val row = if (verdict == NotificationVerdict.SHOWN) NotificationRules.row(facts) else null
            if (row != null) insert(row)
            change = hadRow || row != null
        }
        if (change) notifyListeners()
        return verdict
    }

    /** A notification was removed on the phone: its row goes too (plan §5.8). */
    fun removed(key: String) {
        val change = synchronized(lock) {
            rows.removeAll { it.key == key }.also { if (it) removedCount++ }
        }
        if (change) notifyListeners()
    }

    /**
     * The listener's answer to [Source.requestActive]: every active notification. Replaces
     * the rows wholesale; the verdicts are counted as if each had just been posted.
     */
    fun replaceAll(all: List<NotificationFacts>) {
        if (!active) return
        synchronized(lock) {
            rows.clear()
            for (facts in all) {
                val verdict = NotificationRules.verdict(facts, ownPackage, exclusions)
                verdicts[verdict.ordinal]++
                if (verdict == NotificationVerdict.SHOWN || verdict == NotificationVerdict.EXCLUDED) {
                    posters.add(facts.packageName)
                }
                if (verdict == NotificationVerdict.SHOWN) NotificationRules.row(facts)?.let(::insert)
            }
        }
        notifyListeners()
    }

    /** Asks the listener, if connected, for the phone's active notifications. */
    fun reread() {
        if (!active) return
        val source = source ?: return
        synchronized(lock) { rereads++ }
        source.requestActive()
    }

    /** Drops everything: rows, posters and counters. For tests and a fresh session. */
    fun clear() {
        synchronized(lock) {
            rows.clear()
            posters.clear()
            verdicts.fill(0)
            removedCount = 0
            rereads = 0
        }
    }

    /** `key=value` lines for the session report: counts only (spec §10, plan §9 item 4). */
    fun report(): String = synchronized(lock) {
        val byVerdict = NotificationVerdict.entries.joinToString(" ") {
            "notifications_${it.name.lowercase()}=${verdicts[it.ordinal]}"
        }
        "notifications_enabled=$enabled notifications_paused=$paused " +
            "notifications_held=${rows.size} notifications_removed=$removedCount " +
            "notifications_rereads=$rereads notifications_posting_apps=${posters.size} " +
            "notifications_excluded_apps=${exclusions.size}\n$byVerdict\n"
    }

    /** Newest first, by the phone's post time; bounded. Called under [lock]. */
    private fun insert(row: DashNotification) {
        var at = rows.indexOfFirst { it.postedMillis < row.postedMillis }
        if (at < 0) at = rows.size
        rows.add(at, row)
        while (rows.size > MAX) rows.removeAt(rows.size - 1)
    }

    private fun forget(keepPosters: Boolean) {
        val had = synchronized(lock) {
            val any = rows.isNotEmpty()
            rows.clear()
            if (!keepPosters) posters.clear()
            any
        }
        if (had) notifyListeners()
    }

    /** Becoming active re-reads the shade; nothing else needs to happen here. */
    private fun changed(was: Boolean) {
        if (!was && active) reread()
    }

    private fun notifyListeners() {
        val now = items()
        for (listener in listeners) listener(now)
    }
}
