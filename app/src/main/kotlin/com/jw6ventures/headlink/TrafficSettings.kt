package com.jw6ventures.headlink

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Maps & routing → Traffic (P9, docs/phone-app.md, "Live traffic"): the one opt-in integration that uses the
 * network (APP10 as amended).
 *
 * Off by default, and it cannot be on without a key: [repaired] turns it off whenever the
 * key is blank, so no stored combination can have it on and keyless. [TrafficClient]
 * checks both again before every request.
 *
 * Pure Kotlin, stored keys included, like [SavedSettings]: [TrafficPrefs] only moves
 * [toStored]'s map in and out of its own preferences file, so the host test checks the
 * defaults, the repair and the round trip. Kept apart from [SavedSettings] because the
 * key is a secret: it lives in a file of its own, and it never reaches the report or the
 * event log.
 */
data class TrafficSettings(
    /** Live traffic on. Needs [apiKey]; see [repaired]. */
    val enabled: Boolean = false,
    /** The operator's own HERE API key (HERE's Exhibit 2 §1.2), or "" for none. */
    val apiKey: String = "",
    /** Refresh every, minutes: one of [REFRESH_MINUTES] (decision 2). */
    val refreshMinutes: Int = DEFAULT_REFRESH_MINUTES,
    /** Look ahead, km of route: one of [LOOK_AHEAD_KM] (decision 1). */
    val lookAheadKm: Int = DEFAULT_LOOK_AHEAD_KM,
    /** Monthly request limit (decision 2): fetching stops at it until the next month. */
    val monthlyLimit: Int = DEFAULT_MONTHLY_LIMIT,
    /**
     * The operator has seen what traffic sends and HERE's terms, which the card shows the
     * first time the switch is turned on, and accepted by turning it on.
     */
    val disclosed: Boolean = false,
    /**
     * The lab's stand-in server in place of HERE, e.g. `http://10.0.2.2:8765`,
     * or "" for HERE. Honoured only by a debuggable build ([TrafficClient]); a release build
     * ignores whatever is stored here. Set only by the lab intent, never by the card.
     */
    val baseUrl: String = "",
) {
    /** Can a request be made at all: on, with a key. */
    val usable: Boolean get() = enabled && apiKey.isNotBlank()

    /** This value with anything out of range put back to its default, and off when keyless. */
    fun repaired(): TrafficSettings {
        val d = TrafficSettings()
        val key = cleanKey(apiKey) ?: ""
        return copy(
            enabled = enabled && key.isNotEmpty(),
            apiKey = key,
            refreshMinutes = if (refreshMinutes in REFRESH_MINUTES) refreshMinutes else d.refreshMinutes,
            lookAheadKm = if (lookAheadKm in LOOK_AHEAD_KM) lookAheadKm else d.lookAheadKm,
            monthlyLimit = if (monthlyLimit in LIMIT_RANGE) monthlyLimit else d.monthlyLimit,
            baseUrl = baseUrl.trim().trimEnd('/').takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: "",
        )
    }

    /** Every field under its stored key. Never renamed once shipped. */
    fun toStored(): Map<String, Any> {
        val s = repaired()
        return linkedMapOf(
            ENABLED to s.enabled,
            API_KEY to s.apiKey,
            REFRESH to s.refreshMinutes,
            LOOK_AHEAD to s.lookAheadKm,
            LIMIT to s.monthlyLimit,
            DISCLOSED to s.disclosed,
            BASE_URL to s.baseUrl,
        )
    }

    /** Hides the key in `toString`, so a stray log line or exception cannot print it. */
    override fun toString(): String =
        "TrafficSettings(enabled=$enabled, apiKey=${if (apiKey.isEmpty()) "none" else "set"}, " +
            "refreshMinutes=$refreshMinutes, lookAheadKm=$lookAheadKm, monthlyLimit=$monthlyLimit, " +
            "disclosed=$disclosed, baseUrl=$baseUrl)"

    companion object {
        val REFRESH_MINUTES = listOf(5, 10, 15, 30)
        const val DEFAULT_REFRESH_MINUTES = 10
        val LOOK_AHEAD_KM = listOf(15, 30, 50)
        const val DEFAULT_LOOK_AHEAD_KM = 30
        /** Under HERE's 5,000 free transactions a month, with room to spare. */
        const val DEFAULT_MONTHLY_LIMIT = 4_500
        /** A fetch is two requests, so fewer than two would never allow one. */
        val LIMIT_RANGE = 2..1_000_000

        /**
         * A key as typed or pasted, trimmed, or null when it cannot be one: empty, longer
         * than any HERE key, or holding a space or control character (a paste that caught
         * more than the key).
         */
        fun cleanKey(text: String): String? {
            val key = text.trim()
            if (key.isEmpty() || key.length > 256) return null
            if (key.any { it.isWhitespace() || it.isISOControl() }) return null
            return key
        }

        /** The limit typed on the card, or null when it is not a number in [LIMIT_RANGE]. */
        fun parseLimit(text: String): Int? =
            text.filter { it.isDigit() }.takeIf { it.isNotEmpty() && it.length <= 9 }?.toInt()?.takeIf { it in LIMIT_RANGE }

        /** Reads what [toStored] wrote; a missing or mistyped value reads as its default. */
        fun fromStored(stored: Map<String, *>): TrafficSettings {
            val d = TrafficSettings()
            return TrafficSettings(
                enabled = stored[ENABLED] as? Boolean ?: d.enabled,
                apiKey = stored[API_KEY] as? String ?: d.apiKey,
                refreshMinutes = stored[REFRESH] as? Int ?: d.refreshMinutes,
                lookAheadKm = stored[LOOK_AHEAD] as? Int ?: d.lookAheadKm,
                monthlyLimit = stored[LIMIT] as? Int ?: d.monthlyLimit,
                disclosed = stored[DISCLOSED] as? Boolean ?: d.disclosed,
                baseUrl = stored[BASE_URL] as? String ?: d.baseUrl,
            ).repaired()
        }

        // Stored keys.
        const val ENABLED = "traffic_enabled"
        const val API_KEY = "traffic_api_key"
        const val REFRESH = "traffic_refresh_minutes"
        const val LOOK_AHEAD = "traffic_look_ahead_km"
        const val LIMIT = "traffic_monthly_limit"
        const val DISCLOSED = "traffic_disclosed"
        const val BASE_URL = "traffic_base_url"
    }
}

/**
 * What traffic has done (the card's Status): the requests counted in one calendar month,
 * the last fetch and the last error. Numbers and HERE's error text only — never a box, a
 * position or anything HERE returned: it counts only numbers, never areas.
 *
 * Months are UTC calendar months, as HERE bills (an assumption; HERE's pricing page says
 * "per month"), so the count resets at 00:00 UTC on the 1st: early evening of the last
 * day in Texas. Counting by the phone's zone instead could count the same hours twice.
 */
data class TrafficStatus(
    /** The month [requests] counts, `2026-09`, or "" before the first request. */
    val month: String = "",
    val requests: Int = 0,
    /** Wall-clock milliseconds of the last whole fetch, or 0 for never. */
    val lastFetchMs: Long = 0,
    /** HERE's error, or why it could not be reached, from the last attempt; null after a good one. */
    val lastError: String? = null,
) {
    /**
     * Requests counted in [month]: 0 once the stored month has passed (the 1st-of-month
     * reset). A stored month *later* than [month] still counts: the phone's clock was set
     * back, and setting it back must not give the cap a fresh month.
     */
    fun requestsIn(month: YearMonth): Int {
        val stored = storedMonth() ?: return 0
        return if (stored >= month) requests else 0
    }

    /**
     * This status with [count] more requests in [month], or null when fewer than [need]
     * are left under [limit]. A new month starts again from 0; a stored month later than
     * [month] (the clock set back) is kept, with its count.
     */
    fun reserve(month: YearMonth, limit: Int, count: Int, need: Int = count): TrafficStatus? {
        val used = requestsIn(month)
        if (used + need > limit) return null
        val stored = storedMonth()
        val counted = if (stored != null && stored > month) stored else month
        return copy(month = counted.toString(), requests = used + count)
    }

    private fun storedMonth(): YearMonth? = try {
        if (month.isEmpty()) null else YearMonth.parse(month)
    } catch (e: java.time.format.DateTimeParseException) {
        null
    }

    /** Whether a whole fetch (two requests) no longer fits under [limit] this month. */
    fun atLimit(month: YearMonth, limit: Int): Boolean = requestsIn(month) + TrafficClient.FETCH_REQUESTS > limit

    /**
     * The card's Status, in lines: last fetch ([lastFetch], formatted by the caller in the
     * phone's own style, or null for never), requests this month against the limit, the
     * cap when reached, and the last error.
     */
    fun describe(month: YearMonth, limit: Int, lastFetch: String?): String = buildString {
        append("Last fetch: ").append(lastFetch ?: "never")
        append("\nRequests this month: ")
            .append(String.format(Locale.UK, "%,d of %,d", requestsIn(month), limit))
        if (atLimit(month, limit)) append("\n").append(limitReached(month))
        lastError?.let { append("\nLast error: ").append(it) }
    }

    companion object {
        /** "Limit reached until 1 October": when fetching resumes. */
        fun limitReached(month: YearMonth): String =
            "Limit reached until 1 " + month.plusMonths(1).month.getDisplayName(TextStyle.FULL, Locale.UK)

        fun fromStored(stored: Map<String, *>): TrafficStatus = TrafficStatus(
            month = stored[MONTH] as? String ?: "",
            requests = (stored[REQUESTS] as? Int ?: 0).coerceAtLeast(0),
            lastFetchMs = stored[LAST_FETCH] as? Long ?: 0L,
            lastError = stored[LAST_ERROR] as? String,
        )

        const val MONTH = "traffic_month"
        const val REQUESTS = "traffic_requests"
        const val LAST_FETCH = "traffic_last_fetch_ms"
        const val LAST_ERROR = "traffic_last_error"
    }
}

/**
 * Where [TrafficClient] keeps [TrafficStatus]. [update] is atomic across every client in
 * the process, so the card's Test and the route's refresh cannot both take the last
 * request under the limit.
 */
interface TrafficLedger {
    fun status(): TrafficStatus

    /**
     * Applies [change] and stores the result before returning it; when [change] returns
     * null nothing is stored and null is returned.
     */
    fun update(change: (TrafficStatus) -> TrafficStatus?): TrafficStatus?
}

/** A ledger in memory: the host tests', and nothing else's. */
class MemoryTrafficLedger(private var current: TrafficStatus = TrafficStatus()) : TrafficLedger {
    @Synchronized
    override fun status(): TrafficStatus = current

    @Synchronized
    override fun update(change: (TrafficStatus) -> TrafficStatus?): TrafficStatus? =
        change(current)?.also { current = it }
}

/**
 * [TrafficSettings] and [TrafficStatus] in the app's private `traffic` preferences file.
 *
 * Kept out of Android backup and device transfer, as every file of this app is: the
 * manifest's `allowBackup="false"` and `data_extraction_rules.xml`, which excludes every
 * domain (tools/verify-apk.sh pins both). The key is the operator's alone and
 * must never leave the phone.
 *
 * The count is written with `commit`, synchronously, before the request it counts is sent
 * (docs/navigation.md, "Live traffic (HERE)"): a request the process dies during is still counted. Callers are off the main
 * thread by contract ([TrafficClient]).
 */
object TrafficPrefs {
    private const val FILE = "traffic"

    fun load(context: Context): TrafficSettings = TrafficSettings.fromStored(prefs(context).all)

    /** Reads, changes, stores; the same pattern as [AppSettings.update]. */
    fun update(context: Context, change: (TrafficSettings) -> TrafficSettings): TrafficSettings = synchronized(this) {
        val next = change(load(context)).repaired()
        val editor = prefs(context).edit()
        for ((key, value) in next.toStored()) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
            }
        }
        editor.apply()
        next
    }

    fun status(context: Context): TrafficStatus = TrafficStatus.fromStored(prefs(context).all)

    /**
     * Clears the count, the last fetch and the last error: the lab's reset, and nothing
     * else's. False when the file could not be written.
     */
    fun resetStatus(context: Context): Boolean = synchronized(this) {
        prefs(context).edit()
            .remove(TrafficStatus.MONTH).remove(TrafficStatus.REQUESTS)
            .remove(TrafficStatus.LAST_FETCH).remove(TrafficStatus.LAST_ERROR)
            .commit()
    }

    fun ledger(context: Context): TrafficLedger {
        val app = context.applicationContext
        return object : TrafficLedger {
            override fun status(): TrafficStatus = status(app)

            override fun update(change: (TrafficStatus) -> TrafficStatus?): TrafficStatus? = synchronized(this@TrafficPrefs) {
                val next = change(status(app)) ?: return@synchronized null
                val editor = prefs(app).edit()
                    .putString(TrafficStatus.MONTH, next.month)
                    .putInt(TrafficStatus.REQUESTS, next.requests)
                    .putLong(TrafficStatus.LAST_FETCH, next.lastFetchMs)
                if (next.lastError != null) editor.putString(TrafficStatus.LAST_ERROR, next.lastError)
                else editor.remove(TrafficStatus.LAST_ERROR)
                // Not stored means not counted, so the request must not be sent.
                check(editor.commit()) { "the request count could not be stored" }
                next
            }
        }
    }

    /**
     * The client the app uses: settings read afresh for every request, the real
     * `HttpURLConnection` transport, and the base URL override only in a debuggable build.
     */
    fun client(context: Context): TrafficClient {
        val app = context.applicationContext
        return TrafficClient(
            settings = { load(app) },
            ledger = ledger(app),
            http = UrlConnectionHttp(),
            elapsed = SystemClock::elapsedRealtime,
            debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
