package com.jw6ventures.headlink

/**
 * Breezy's provider as [WeatherFeed] asks it, so the host tests can stand in
 * for it: [WeatherProvider] is the ContentResolver behind it on a phone. Every call may
 * block, and may throw; the feed calls them off the main thread, one read at a time.
 */
interface WeatherSource {
    /** The package that owns [WeatherRules.AUTHORITY], or null when nothing does. */
    fun owner(): String?

    /** Whether this app holds [WeatherRules.PERMISSION] now. */
    fun permitted(): Boolean

    /** `content://…/version`: major and minor, or null for no row. */
    fun version(): Pair<Int, Int>?

    /** `content://…/locations?limit=10`. */
    fun places(): List<WeatherPlace>

    /** `content://…/weather?…` with `selection = "id=<id>"`: the `weather` blob, or null for no row. */
    fun blob(id: String): ByteArray?
}

/** Why a read was started, counted apart in the report. */
enum class WeatherCue(val key: String) {
    START("start"),
    NOTIFIER("notifier"),
    TIMER("timer"),
    LAB("lab"),
}

/**
 * P10's read loop, with no Android in it: [Weather] gives it the provider, a
 * worker, the main thread and a clock. Main thread only, apart from [WeatherSource] calls,
 * which run on [work] and hand their answer back through [post].
 *
 * **Off means no read at all.** With [WeatherSettings.enabled] false, [cue] returns before
 * touching [source] (host-tested with a source that fails the test if called).
 *
 * **One read at a time.** A cue while a read runs is skipped and counted, not queued: a
 * read is a few kilobytes from a local provider, and the next cue is at most 15 min off.
 *
 * **Held in memory only.** [state] is replaced by each read and dropped by [stop].
 */
class WeatherFeed(
    private val source: WeatherSource,
    /** This app is a debug build: Breezy's debug package is accepted. */
    private val debuggable: Boolean,
    private val work: (Runnable) -> Unit,
    private val post: (Runnable) -> Unit,
    private val clock: () -> Long,
) {
    var settings = WeatherSettings()
        private set

    /** Where the vehicle or phone is now, for choosing a location. Main thread. */
    var fix: () -> Pair<Double, Double>? = { null }

    /** Told every new state, on the main thread. */
    var listener: ((WeatherState) -> Unit)? = null

    /**
     * Told an alert to speak: the first time each id qualifies this session, and
     * only with Speak weather alerts on. [Speaker] applies the voice toggle and the gate.
     */
    var speak: ((WeatherAlert) -> Unit)? = null

    var state = WeatherState.NONE
        private set

    private var running = false
    private var generation = 0
    private var active = false

    /** Alert ids that have qualified this session, spoken or not: each is asked for once. */
    private val announced = HashSet<String>()

    private var reads = 0
    private var failures = 0
    private var skipped = 0
    private val cues = IntArray(WeatherCue.entries.size)
    private var lastStatus = WeatherStatus.NOT_READ
    private var permission: Boolean? = null
    private var alertsQualified = 0
    private var alertsAsked = 0

    /** A projection started with [settings]: counts reset, and a read if weather is on. */
    fun start(settings: WeatherSettings) {
        generation++
        this.settings = settings
        active = true
        running = false
        state = WeatherState.NONE
        announced.clear()
        reads = 0
        failures = 0
        skipped = 0
        cues.fill(0)
        lastStatus = WeatherStatus.NOT_READ
        permission = null
        alertsQualified = 0
        alertsAsked = 0
        listener?.invoke(state)
        cue(WeatherCue.START)
    }

    /**
     * Show weather or the alert choices changed mid-session (the lab, or the phone's card):
     * off drops the reading at once, on reads.
     */
    fun settings(updated: WeatherSettings) {
        val was = settings.enabled
        settings = updated
        if (!active) return
        if (!updated.enabled) {
            generation++
            running = false
            state = WeatherState.NONE
            listener?.invoke(state)
        } else {
            if (!was) cue(WeatherCue.START)
            checkAlerts()
        }
    }

    /** The projection ended: the reading is dropped, and a read still running is ignored. */
    fun stop() {
        generation++
        active = false
        running = false
        state = WeatherState.NONE
        listener?.invoke(state)
    }

    /** Read now, for [why], unless weather is off, no projection is attached, or a read is running. */
    fun cue(why: WeatherCue) {
        if (!active || !settings.enabled) return
        cues[why.ordinal]++
        if (running) {
            skipped++
            return
        }
        running = true
        val bound = generation
        val where = fix()
        val now = clock()
        work(Runnable {
            val result = read(where, now)
            post(Runnable { finished(bound, result) })
        })
    }

    /**
     * A minute passed: an alert may have started or ended without a new read. The
     * dashboard re-filters at each frame by itself; this is only for speaking one.
     */
    fun tick() {
        if (active && settings.enabled) checkAlerts()
    }

    private class Result(val status: WeatherStatus, val reading: WeatherReading?, val major: Int?, val permitted: Boolean?)

    /** Worker thread: one whole read, never throwing. */
    private fun read(fix: Pair<Double, Double>?, nowMillis: Long): Result {
        var permitted: Boolean? = null
        return try {
            val owner = source.owner() ?: return Result(WeatherStatus.MISSING, null, null, null)
            if (!WeatherRules.ownerAccepted(owner, debuggable)) return Result(WeatherStatus.WRONG_PACKAGE, null, null, null)
            permitted = source.permitted()
            if (permitted != true) return Result(WeatherStatus.NO_PERMISSION, null, null, false)
            val version = source.version() ?: return Result(WeatherStatus.UNREADABLE, null, null, true)
            if (version.first != WeatherRules.SUPPORTED_MAJOR) {
                return Result(WeatherStatus.UNSUPPORTED_MAJOR, null, version.first, true)
            }
            val place = WeatherRules.choose(source.places(), fix) ?: return Result(WeatherStatus.NO_LOCATIONS, null, null, true)
            val blob = source.blob(place.id) ?: return Result(WeatherStatus.UNREADABLE, null, null, true)
            val data = WeatherJson.parse(blob, nowMillis)
            Result(WeatherStatus.OK, WeatherReading(place, data, nowMillis), null, true)
        } catch (e: WeatherReadException) {
            Result(e.status, null, e.major, permitted)
        } catch (e: SecurityException) {
            // The grant was taken back between the check and the query.
            Result(WeatherStatus.NO_PERMISSION, null, null, false)
        } catch (e: RuntimeException) {
            Result(WeatherStatus.UNREADABLE, null, null, permitted)
        }
    }

    private fun finished(bound: Int, result: Result) {
        if (bound != generation) return
        running = false
        if (!active || !settings.enabled) return
        reads++
        if (result.status != WeatherStatus.OK) failures++
        lastStatus = result.status
        result.permitted?.let { permission = it }
        // A failure keeps the last good reading, so its age still shows; a refused
        // provider (not installed, wrong owner, no permission, unsupported) drops it, because
        // what it said can no longer be trusted to be Breezy's.
        val keep = result.status == WeatherStatus.UNREADABLE || result.status == WeatherStatus.NO_LOCATIONS
        state = WeatherState(result.status, result.reading ?: if (keep) state.reading else null, result.major)
        listener?.invoke(state)
        checkAlerts()
    }

    private fun checkAlerts() {
        val reading = state.reading ?: return
        // Asked in the tile's order (most severe, then ending soonest). Speech never lets one
        // alert cut another off: those asked together wait and are said in this order.
        for (alert in WeatherRules.qualifying(reading.data.alerts, clock(), settings.alertLevel)) {
            if (!announced.add(alert.id)) continue
            alertsQualified++
            if (settings.speakAlerts) {
                alertsAsked++
                speak?.invoke(alert)
            }
        }
    }

    /**
     * `key=value` lines for the session report: counts, the provider's state and
     * the reading's age in minutes. Never a place, a coordinate, a headline, a temperature
     * or a condition.
     */
    fun report(): String {
        val age = state.reading?.data?.refreshMillis?.let { ((clock() - it) / 60_000L).coerceAtLeast(0) }
        return "weather_enabled=${settings.enabled} weather_permission=${permission?.let { if (it) "granted" else "denied" } ?: "-"} " +
            "weather_provider=${lastStatus.key} weather_reads=$reads weather_read_failures=$failures " +
            "weather_reads_skipped=$skipped weather_notifier_cues=${cues[WeatherCue.NOTIFIER.ordinal]} " +
            "weather_timer_cues=${cues[WeatherCue.TIMER.ordinal]} weather_lab_cues=${cues[WeatherCue.LAB.ordinal]} " +
            "weather_reading=${state.reading != null} weather_reading_age_min=${age ?: "-"} " +
            "weather_hours=${state.reading?.data?.hours?.size ?: 0} weather_alerts_held=${state.reading?.data?.alerts?.size ?: 0} " +
            "weather_alerts_qualified=$alertsQualified weather_alerts_asked=$alertsAsked " +
            "weather_speak_alerts=${settings.speakAlerts} weather_alert_level=${settings.alertLevel.key}\n"
    }
}
