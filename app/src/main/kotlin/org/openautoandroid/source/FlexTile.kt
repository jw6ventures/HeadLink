package org.openautoandroid.source

/**
 * A list of rows that a finger scrolls and taps: the favourites tile's gesture (P6.6),
 * taken out of [Dashboard] so the flex tile's notifications (P7.8) use the same rules
 * rather than a second copy of them (plan §5.8).
 *
 * The rules, unchanged from P6.6: a press that comes down in the list either lifts on the
 * row it pressed, which is a tap, or moves past the slop, which makes the rest of the
 * gesture a scroll and nothing else; once it has scrolled it cannot become a tap again.
 * The list follows the finger, clamped to what it holds.
 *
 * Pure JVM and not thread safe: [Dashboard] owns one per list and calls it under its lock.
 * The geometry (the box, the row height, the slop) belongs to [DashboardLayout] and is
 * passed in, so a relayout never leaves a stale copy here.
 */
class TouchList {
    /** How far up the list is scrolled, in pixels; zero while it all fits. */
    var scroll = 0
        private set

    /** The row a finger is resting on, or -1. */
    var pressed = -1
        private set

    /** Completed taps and scroll gestures, for the report. */
    var taps = 0L
        private set
    var scrolls = 0L
        private set

    private class Gesture(val downY: Int, val startScroll: Int, val index: Int) {
        var scrolling = false
    }

    private var gesture: Gesture? = null

    /** Whether a finger that came down in this list is still on the glass. */
    val active: Boolean get() = gesture != null

    /** A press came down in the list at [py], on row [index] (-1 for the empty space past the last). */
    fun down(py: Int, index: Int) {
        gesture = Gesture(py, scroll, index)
        pressed = index
    }

    /**
     * The finger moved to [py]. Past [slop] the gesture becomes a scroll: the pressed row
     * goes dark and the list follows the finger, dragging up bringing later rows into view.
     */
    fun move(py: Int, slop: Int, maxScroll: Int) {
        val gesture = gesture ?: return
        val travelled = py - gesture.downY
        if (!gesture.scrolling && Math.abs(travelled) > slop) {
            gesture.scrolling = true
            pressed = -1
        }
        if (gesture.scrolling) scroll = (gesture.startScroll - travelled).coerceIn(0, maxScroll)
    }

    /**
     * The finger lifted over row [hitAtUp] (-1 for none). Returns the row a tap chose —
     * press and release on the same row, as with every other button — or -1.
     */
    fun up(hitAtUp: Int): Int {
        val gesture = gesture ?: return -1
        this.gesture = null
        pressed = -1
        if (gesture.scrolling) {
            scrolls++
            return -1
        }
        if (gesture.index >= 0 && hitAtUp == gesture.index) {
            taps++
            return gesture.index
        }
        return -1
    }

    /** A cancelled gesture, or one taken out from under the finger: nothing is chosen. */
    fun cancel() {
        gesture = null
        pressed = -1
    }

    /**
     * The rows were replaced. The scroll position is kept where it can be, so a row added
     * while projecting does not throw the list back to the top, and clamped where it
     * cannot, so a shortened list never shows an empty pane. A finger on the old rows is
     * dropped: the row under it may now be a different one.
     */
    fun replaced(maxScroll: Int) {
        scroll = scroll.coerceIn(0, maxScroll)
        cancel()
    }
}

/** What the flex tile is showing (plan §5.8), highest priority first. */
enum class FlexSource(val key: String) {
    /** A call, ringing or in progress (P7.9). */
    CALL("call"),

    /** P10: the weather view, opened by tapping the header's weather item (docs/p10-plan.md §5.2). */
    WEATHER("weather"),

    /** P10: a weather alert in force at or above the chosen level and not dismissed (§5.3). */
    WEATHER_ALERT("weather_alert"),

    /** Alerting notifications, turned on and not paused with Ⓟ (P7.8). */
    NOTIFICATIONS("notifications"),

    /** Nothing to say: "No notifications", or "Notifications paused". Never "not configured". */
    EMPTY("empty"),
}

/** One thing the call card or the keypad asks [CallControl] to do (P7.9). */
enum class CallActionKind {
    ANSWER,
    DECLINE,
    HANG_UP,
    MUTE,
    UNMUTE,

    /** A keypad key went down: its tone starts. */
    KEY_DOWN,

    /** The key came up, or the press was cancelled: the tone stops. */
    KEY_UP,
}

/**
 * A press on the call card or the keypad, handed from the dashboard to the service the way
 * a favourite is ([Dashboard.takeCallActions]): the dashboard runs on the touch path under
 * its own lock, and every call control goes through [CallControl] from the service, never
 * from the render thread.
 *
 * **[key] is a keypad digit, and digits are often PINs and account numbers** (plan §5.8).
 * It exists only to reach `Call.playDtmfTone`. [toString] leaves it out, so no log line or
 * string template can carry it by accident, and [note] never names it.
 */
class CallAction(val kind: CallActionKind, val key: Char = ' ') {
    /**
     * What the session's event log says about this press, or null for nothing. The kind
     * only: a keypad press is not noted at all (the report counts them), because even
     * "a key" per press would spell out a PIN's length and rhythm in the log.
     */
    val note: String?
        get() = when (kind) {
            CallActionKind.ANSWER -> "dashboard call answer pressed"
            CallActionKind.DECLINE -> "dashboard call decline pressed"
            CallActionKind.HANG_UP -> "dashboard call hang up pressed"
            CallActionKind.MUTE -> "dashboard call mute pressed"
            CallActionKind.UNMUTE -> "dashboard call unmute pressed"
            CallActionKind.KEY_DOWN, CallActionKind.KEY_UP -> null
        }

    override fun toString(): String = "CallAction(${kind.name})"

    override fun equals(other: Any?): Boolean = other is CallAction && other.kind == kind && other.key == key

    override fun hashCode(): Int = kind.hashCode() * 31 + key.hashCode()
}

/** The flex tile's pure rules: what shows, what it is called, and which buttons a call has. */
object FlexRules {
    /** A ringing call's buttons, left to right. */
    val RING_ACTIONS = listOf("answer", "decline")

    /** A connected call's row with full controls (the Telecom route), left to right. */
    val CALL_ACTIONS = listOf("mute", "keypad", "hang-up")

    /** A call that can only be hung up: dialling, on hold, or the notification route. */
    val HANG_UP_ONLY = listOf("hang-up")

    /** The keypad's keys in reading order, three to a row. */
    const val KEYPAD_KEYS = "123456789*0#"

    /** The empty tile's two sayings (plan §5.8). */
    const val NO_NOTIFICATIONS = "No notifications"
    const val PAUSED = "Notifications paused"

    /**
     * Which source the tile shows: a call first, because a missed call is costlier than a
     * missed message (and Ⓟ does not hide calls, plan §10 question 2); then, since P10, the
     * weather view the driver opened, which carries any alert in its strip, and then a
     * weather alert, which Ⓟ does not hide either, since it pauses notifications and not
     * safety information (docs/p10-plan.md §5.3); then notifications while not paused and
     * there are any; else the quiet empty state. Weather never fills an empty tile by itself.
     */
    @JvmOverloads
    fun source(
        call: CallState,
        paused: Boolean,
        notifications: Int,
        weatherView: Boolean = false,
        weatherAlert: Boolean = false,
    ): FlexSource = when {
        call.phase != CallPhase.NONE -> FlexSource.CALL
        weatherView -> FlexSource.WEATHER
        weatherAlert -> FlexSource.WEATHER_ALERT
        !paused && notifications > 0 -> FlexSource.NOTIFICATIONS
        else -> FlexSource.EMPTY
    }

    /**
     * The buttons a call shows. Mute and the keypad only where they can work
     * ([CallState.canMute], [CallState.canDial]): on the notification route the dialer's
     * notification offers answer, decline and hang up and nothing else, and a button that
     * does nothing when pressed from the driver's seat is worse than no button.
     */
    fun actions(call: CallState): List<String> = when (call.phase) {
        CallPhase.RINGING -> RING_ACTIONS
        CallPhase.ACTIVE -> if (call.canMute && call.canDial) CALL_ACTIONS else HANG_UP_ONLY
        CallPhase.DIALING, CallPhase.HOLDING -> HANG_UP_ONLY
        CallPhase.ENDED, CallPhase.NONE -> emptyList()
    }

    /**
     * The tile's label, which follows what it shows. "Muted" while the microphone is off,
     * because a forgotten mute is the common mistake (plan §5.8).
     */
    fun label(source: FlexSource, call: CallState): String = when (source) {
        FlexSource.CALL -> when (call.phase) {
            CallPhase.RINGING -> "incoming call"
            CallPhase.DIALING -> "calling"
            CallPhase.ACTIVE -> if (call.muted) "Muted" else "call"
            CallPhase.HOLDING -> "on hold"
            CallPhase.ENDED -> "call ended"
            CallPhase.NONE -> "call"
        }
        FlexSource.WEATHER -> WeatherText.LABEL_VIEW
        FlexSource.WEATHER_ALERT -> WeatherText.LABEL_ALERT
        FlexSource.NOTIFICATIONS, FlexSource.EMPTY -> "notifications"
    }

    /**
     * A connected call's running time, `0:42`, `12:05`, `1:02:03`; null before it has
     * connected or with no connect time. Whole seconds, so the chrome repaints once a
     * second during a call and not on every frame.
     */
    fun duration(call: CallState, nowMillis: Long): String? {
        if (call.phase != CallPhase.ACTIVE || call.connectedSinceMillis < 0) return null
        val seconds = ((nowMillis - call.connectedSinceMillis) / 1000L).coerceAtLeast(0)
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
    }
}
