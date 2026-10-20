package com.jw6ventures.headlink

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * P10: weather from Breezy Weather (docs/p10-plan.md). This file is the pure half, with no
 * Android in it, so tools/jni-host-test checks all of it: the settings, what the provider's
 * cursor and gzipped JSON become, which location is read, which alerts count, and every
 * word the header and the flex tile show. [WeatherProvider] is the Android half.
 *
 * Written against Breezy's source (provider 0.2, docs/weather-research.md §1.2), not a
 * capture from the phone: plan §1 says every field name here is unconfirmed until P10.0
 * reads a real blob, and the parser is lenient about everything it does not need.
 */

/** Settings → Weather (plan §4). Off by default (APP09); stored with [SavedSettings]. */
data class WeatherSettings(
    /** Show weather: the header item, the tapped view and alerts. Needs Breezy's permission. */
    val enabled: Boolean = false,
    /** Speak weather alerts, heard only while the trip panel's voice toggle is on (plan §6). */
    val speakAlerts: Boolean = true,
    /** Alerts shown from: the lowest severity that takes the tile and is spoken. */
    val alertLevel: AlertLevel = AlertLevel.DEFAULT,
)

/**
 * Breezy's alert severities that can take the tile (plan §4): 1 minor to 4 extreme. Breezy's
 * 0, unknown, never qualifies by itself; it still shows in the weather view's strip.
 */
enum class AlertLevel(val key: String, val label: String, val severity: Int) {
    MINOR("minor", "Minor", 1),
    MODERATE("moderate", "Moderate", 2),
    SEVERE("severe", "Severe", 3),
    EXTREME("extreme", "Extreme", 4),
    ;

    companion object {
        val DEFAULT = SEVERE
        val LABELS: List<String> = entries.map { it.label }
        fun of(key: String?): AlertLevel = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Breezy's twelve weather codes (its `WeatherCode.id`), one glyph each (plan §5.1). */
enum class WeatherCode(val key: String) {
    CLEAR("clear"),
    PARTLY_CLOUDY("partly_cloudy"),
    CLOUDY("cloudy"),
    RAIN("rain"),
    SNOW("snow"),
    WIND("wind"),
    FOG("fog"),
    HAZE("haze"),
    SLEET("sleet"),
    HAIL("hail"),
    THUNDER("thunder"),
    THUNDERSTORM("thunderstorm"),
    ;

    /** Whether the glyph has a moon variant at night: only the two with a sun in them. */
    val hasNight: Boolean get() = this == CLEAR || this == PARTLY_CLOUDY

    companion object {
        /** Breezy matches ignoring case, so this does too; anything else is null (no glyph). */
        fun of(key: String?): WeatherCode? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/** A value and the unit tag Breezy sent with it (`f`, `c`, `mph`, `percent`…). Never converted. */
data class Tagged(val value: Double, val unit: String?)

/** One hour of `hourly[]`: what the weather view's row draws. */
data class WeatherHour(
    val timeMillis: Long,
    val daylight: Boolean,
    val code: WeatherCode?,
    val temperature: Tagged?,
    /** `precipitationProbability.total`, as sent (a percentage), or null. */
    val rain: Tagged?,
)

/** One of `alerts[]`, as much as P10 keeps. The description and instructions are never read. */
data class WeatherAlert(
    val id: String,
    val startMillis: Long?,
    val endMillis: Long?,
    val headline: String,
    /** 0 unknown, 1 minor, 2 moderate, 3 severe, 4 extreme. */
    val severity: Int,
    /** `#rrggbb` as an opaque ARGB int, or null when Breezy sent none or one that does not parse. */
    val colour: Int?,
)

/** What one `weather` blob says, trimmed to the fields plan §1 lists. */
data class WeatherData(
    val refreshMillis: Long?,
    val text: String?,
    val code: WeatherCode?,
    val temperature: Tagged?,
    /** `sourceFeelsLike`, else `computedApparent`. */
    val feelsLike: Tagged?,
    val wind: Tagged?,
    /** Today's high and low: `daily[today].day` and `.night` temperatures. */
    val high: Tagged?,
    val low: Tagged?,
    /** Whether it is day now, from the current hour's `isDaylight`; true when unknown. */
    val daylight: Boolean,
    /** The hours from the current one on, at most [WeatherJson.HOURS_KEPT]. */
    val hours: List<WeatherHour>,
    /** Every alert Breezy holds, active or not: [WeatherRules.qualifying] filters them. */
    val alerts: List<WeatherAlert>,
)

/** One row of `content://…/locations`: where Breezy has weather for. */
data class WeatherPlace(
    /** Good for one query only (the library says ids can change); never saved. */
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val current: Boolean,
    /** `custom_name`, else `city`, else `district`, else "". */
    val name: String,
)

/**
 * Why there is no reading, or [OK]. Each has the settings card's status line (plan §4) and
 * the report's `weather_provider` word (§7).
 */
enum class WeatherStatus(val key: String) {
    /** Show weather is off, or nothing has been read yet this session. */
    NOT_READ("not_read"),
    OK("ok"),
    MISSING("missing"),
    NO_PERMISSION("no_permission"),
    WRONG_PACKAGE("wrong_package"),
    UNSUPPORTED_MAJOR("unsupported_major"),
    NO_LOCATIONS("no_locations"),

    /** The provider answered, but not with anything that parsed. */
    UNREADABLE("unreadable"),
}

/**
 * The latest read (plan §3): held in memory only, replaced by the next, dropped when the
 * projection ends. [reading] is the last good one; a later failure keeps it and says why
 * in [status], so a truck that loses Breezy mid-drive still shows how old its weather is.
 */
data class WeatherState(
    val status: WeatherStatus = WeatherStatus.NOT_READ,
    val reading: WeatherReading? = null,
    /** The provider's major version when [status] is [WeatherStatus.UNSUPPORTED_MAJOR]. */
    val major: Int? = null,
) {
    companion object {
        val NONE = WeatherState()
    }
}

/** A good read: which place, where it is, and what its blob said. */
data class WeatherReading(val place: WeatherPlace, val data: WeatherData, val readAtMillis: Long)

/** Read failures a caller can tell apart; anything else is [WeatherStatus.UNREADABLE]. */
class WeatherReadException(val status: WeatherStatus, val major: Int? = null) : IOException(status.key)

/**
 * Breezy's `weather` column: gzipped JSON in the shape of the library's `BreezyWeather`,
 * read with P9's [JsonStream]. kotlinx.serialization on Breezy's side leaves out fields at
 * their defaults (`isDaylight` true, `severity` 0), so a missing one takes that default.
 */
object WeatherJson {
    /** The current hour and the next ones kept: the view shows 4 after the current (plan §5.2). */
    const val HOURS_KEPT = 7

    /** The largest blob taken: a year of hourly data is well under this. Larger is refused. */
    const val MAX_BYTES = 4 * 1024 * 1024

    /** [blob] gunzipped and parsed, or [WeatherReadException] (UNREADABLE); never anything else. */
    @Throws(WeatherReadException::class)
    fun parse(blob: ByteArray, nowMillis: Long): WeatherData {
        try {
            GZIPInputStream(ByteArrayInputStream(blob)).use { gz ->
                return parse(InputStreamReader(LimitedStream(gz, MAX_BYTES), Charsets.UTF_8), nowMillis)
            }
        } catch (e: WeatherReadException) {
            throw e
        } catch (e: IOException) {
            throw WeatherReadException(WeatherStatus.UNREADABLE)
        } catch (e: RuntimeException) {
            throw WeatherReadException(WeatherStatus.UNREADABLE)
        }
    }

    /** Plain JSON, for the host tests and [parse]. */
    fun parse(input: java.io.Reader, nowMillis: Long): WeatherData {
        val json = JsonStream(input)
        if (json.peek() != JsonStream.Token.BEGIN_OBJECT) throw WeatherReadException(WeatherStatus.UNREADABLE)
        var refresh: Long? = null
        var current: Current? = null
        val daily = ArrayList<Day>()
        val hourly = ArrayList<WeatherHour>()
        val alerts = ArrayList<WeatherAlert>()
        json.forEachField { name ->
            when (name) {
                "refreshTime" -> refresh = json.optDouble()?.toLong()
                "current" -> current = current(json)
                "daily" -> json.forEachItem { day(json)?.let(daily::add) }
                "hourly" -> json.forEachItem { hour(json)?.let(hourly::add) }
                "alerts" -> json.forEachItem { alert(json)?.let(alerts::add) }
                else -> json.skipValue()
            }
        }
        if (json.peek() != JsonStream.Token.END) throw WeatherReadException(WeatherStatus.UNREADABLE)
        hourly.sortBy { it.timeMillis }
        // The hour now falls in, and those after it. Breezy's hours start at local midnight.
        val first = hourly.indexOfLast { it.timeMillis <= nowMillis }.coerceAtLeast(0)
        val hours = hourly.drop(first).filter { it.timeMillis > nowMillis - HOUR_MS }.take(HOURS_KEPT)
        val daylight = hourly.lastOrNull { it.timeMillis <= nowMillis && it.timeMillis > nowMillis - HOUR_MS }?.daylight ?: true
        // Today: the day now falls in, else the first given. Days start at local midnight.
        val today = daily.lastOrNull { it.date <= nowMillis && nowMillis < it.date + DAY_MS } ?: daily.firstOrNull()
        val c = current
        return WeatherData(
            refreshMillis = refresh,
            text = c?.text,
            code = c?.code,
            temperature = c?.temperature,
            feelsLike = c?.feelsLike,
            wind = c?.wind,
            high = today?.high,
            low = today?.low,
            daylight = daylight,
            hours = hours,
            alerts = alerts,
        )
    }

    private class Current(val text: String?, val code: WeatherCode?, val temperature: Tagged?, val feelsLike: Tagged?, val wind: Tagged?)

    private class Day(val date: Long, val high: Tagged?, val low: Tagged?)

    private class Temperatures(val temperature: Tagged?, val feelsLike: Tagged?)

    private fun current(json: JsonStream): Current? {
        if (json.peek() != JsonStream.Token.BEGIN_OBJECT) return null.also { json.skipValue() }
        var text: String? = null
        var code: WeatherCode? = null
        var temperatures: Temperatures? = null
        var wind: Tagged? = null
        json.forEachField { name ->
            when (name) {
                "weatherText" -> text = json.optString()?.trim()?.takeIf { it.isNotEmpty() }
                "weatherCode" -> code = WeatherCode.of(json.optString())
                "temperature" -> temperatures = temperatures(json)
                "wind" -> json.forEachField { part -> if (part == "speed") wind = unit(json) else json.skipValue() }
                else -> json.skipValue()
            }
        }
        return Current(text, code, temperatures?.temperature, temperatures?.feelsLike, wind)
    }

    /** A `BreezyTemperature`: the temperature, and the source's feels-like else Breezy's own. */
    private fun temperatures(json: JsonStream): Temperatures {
        var temperature: Tagged? = null
        var source: Tagged? = null
        var apparent: Tagged? = null
        json.forEachField { name ->
            when (name) {
                "temperature" -> temperature = unit(json)
                "sourceFeelsLike" -> source = unit(json)
                "computedApparent" -> apparent = unit(json)
                else -> json.skipValue()
            }
        }
        return Temperatures(temperature, source ?: apparent)
    }

    private fun day(json: JsonStream): Day? {
        var date: Long? = null
        var high: Tagged? = null
        var low: Tagged? = null
        json.forEachField { name ->
            when (name) {
                "date" -> date = json.optDouble()?.toLong()
                "day" -> high = halfDay(json)
                "night" -> low = halfDay(json)
                else -> json.skipValue()
            }
        }
        return date?.let { Day(it, high, low) }
    }

    private fun halfDay(json: JsonStream): Tagged? {
        var temperature: Tagged? = null
        json.forEachField { name -> if (name == "temperature") temperature = temperatures(json).temperature else json.skipValue() }
        return temperature
    }

    private fun hour(json: JsonStream): WeatherHour? {
        var date: Long? = null
        var daylight = true
        var code: WeatherCode? = null
        var temperature: Tagged? = null
        var rain: Tagged? = null
        json.forEachField { name ->
            when (name) {
                "date" -> date = json.optDouble()?.toLong()
                "isDaylight" -> daylight = json.optBoolean() ?: true
                "weatherCode" -> code = WeatherCode.of(json.optString())
                "temperature" -> temperature = temperatures(json).temperature
                "precipitationProbability" -> json.forEachField { part -> if (part == "total") rain = unit(json) else json.skipValue() }
                else -> json.skipValue()
            }
        }
        return date?.let { WeatherHour(it, daylight, code, temperature, rain) }
    }

    private fun alert(json: JsonStream): WeatherAlert? {
        var id: String? = null
        var start: Long? = null
        var end: Long? = null
        var headline: String? = null
        var severity = 0
        var colour: Int? = null
        json.forEachField { name ->
            when (name) {
                "alertId" -> id = json.optString()
                "startDate" -> start = json.optDouble()?.toLong()
                "endDate" -> end = json.optDouble()?.toLong()
                "headline" -> headline = json.optString()?.replace(Regex("\\s+"), " ")?.trim()
                "severity" -> severity = json.optDouble()?.toInt() ?: 0
                "color" -> colour = colour(json.optString())
                else -> json.skipValue()
            }
        }
        val key = id?.takeIf { it.isNotEmpty() } ?: return null
        return WeatherAlert(key, start, end, headline?.takeIf { it.isNotEmpty() } ?: "Weather alert", severity, colour)
    }

    /** A `BreezyUnit`: its value, finite, and its unit tag; null without a value. */
    private fun unit(json: JsonStream): Tagged? {
        var value: Double? = null
        var unit: String? = null
        json.forEachField { name ->
            when (name) {
                "value" -> value = json.optDouble()
                "unit" -> unit = json.optString()
                else -> json.skipValue()
            }
        }
        return value?.let { Tagged(it, unit) }
    }

    /** Breezy's `#rrggbb` (its alpha set to 0 before formatting), made opaque. */
    fun colour(text: String?): Int? {
        val hex = text?.trim()?.removePrefix("#") ?: return null
        if (hex.isEmpty() || hex.length > 8 || hex.any { Character.digit(it, 16) < 0 }) return null
        return (hex.toLong(16) and 0xFFFFFF).toInt() or 0xFF000000.toInt()
    }

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    /** Refuses a blob that inflates past [limit] bytes, so a hostile one cannot fill memory. */
    private class LimitedStream(private val input: java.io.InputStream, private val limit: Int) : java.io.InputStream() {
        private var count = 0

        override fun read(): Int {
            val b = input.read()
            if (b >= 0 && ++count > limit) throw IOException("too large")
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = input.read(b, off, len)
            if (n > 0) {
                count += n
                if (count > limit) throw IOException("too large")
            }
            return n
        }

        override fun close() = input.close()
    }
}

/** P10's pure decisions: which place, which alerts, whose provider, and when to read. */
object WeatherRules {
    /** The provider major version this is written for (plan §1). Any other is refused. */
    const val SUPPORTED_MAJOR = 0

    /** Breezy's release package; the only owner of the authority accepted (plan §3). */
    const val PACKAGE = "org.breezyweather"

    /** Breezy's debug build, accepted only by a debug build of this app, for the lab. */
    const val DEBUG_PACKAGE = "org.breezyweather.debug"

    const val AUTHORITY = "org.breezyweather.provider.weather"
    const val PERMISSION = "org.breezyweather.READ_PROVIDER"
    const val NOTIFIER_ACTION = "org.breezyweather.ACTION_UPDATE_NOTIFIER"

    /** Breezy's own id for its current-position location. */
    const val CURRENT_POSITION_ID = "CURRENT_POSITION"

    /** The backstop re-read while projecting (plan §3). */
    const val READ_EVERY_MS = 15 * 60_000L

    /** A reading older than this is drawn dimmed with "old" (plan §2). */
    const val STALE_MS = 3 * 3_600_000L

    /** A place further than this from the vehicle is labelled with its distance (plan §2). */
    const val FAR_METRES = 50_000.0

    /** The tapped view closes by itself after this (plan §5.2). */
    const val VIEW_TIMEOUT_MS = 30_000L

    /** Hours in the view's row (plan §5.2). */
    const val VIEW_HOURS = 4

    /** A rain percentage is shown from here up (plan §5.2). */
    const val RAIN_SHOWN_PERCENT = 20

    /** "Feels 84°" is shown when it differs from the temperature by this or more (plan §5.2). */
    const val FEELS_DIFFERENCE = 3.0

    /** The locations asked for (plan §1). */
    const val LOCATION_LIMIT = 10

    /**
     * Whether a provider owned by [owner] may be read (plan §3): Breezy's release package;
     * its debug package only when this app is debuggable. Anything else, or none, is refused.
     */
    fun ownerAccepted(owner: String?, debuggable: Boolean): Boolean =
        owner == PACKAGE || (debuggable && owner == DEBUG_PACKAGE)

    /**
     * Which location to read (plan §2): Breezy's current position; else the one nearest the
     * vehicle's or phone's [fix]; else the first. Null only when there are none.
     */
    fun choose(places: List<WeatherPlace>, fix: Pair<Double, Double>?): WeatherPlace? {
        places.firstOrNull { it.current }?.let { return it }
        if (fix != null) {
            places.minByOrNull { metres(fix.first, fix.second, it.latitude, it.longitude) }?.let { return it }
        }
        return places.firstOrNull()
    }

    /**
     * The alerts that take the tile and are spoken (plan §5.3): started, not ended, at or
     * above [level], and not [dismissed]; the most severe first, then the one ending soonest.
     */
    fun qualifying(alerts: List<WeatherAlert>, nowMillis: Long, level: AlertLevel, dismissed: Set<String> = emptySet()): List<WeatherAlert> =
        active(alerts, nowMillis).filter { it.severity >= level.severity && it.id !in dismissed }

    /** Every alert in force now, whatever its severity, in [qualifying]'s order: the view's strip. */
    fun active(alerts: List<WeatherAlert>, nowMillis: Long): List<WeatherAlert> =
        alerts.filter { (it.startMillis == null || it.startMillis <= nowMillis) && (it.endMillis == null || it.endMillis > nowMillis) }
            .sortedWith(compareByDescending<WeatherAlert> { it.severity }.thenBy { it.endMillis ?: Long.MAX_VALUE })

    /** Great-circle distance in metres. */
    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180
        val p2 = lat2 * PI / 180
        val dp = p2 - p1
        val dl = (lon2 - lon1) * PI / 180
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/** Every word P10 puts on the dashboard and the settings card, as pure functions. */
object WeatherText {
    const val NO_READING = "—"
    const val OLD = "old"
    const val LABEL_VIEW = "weather"
    const val LABEL_ALERT = "weather alert"
    const val ALERT_LEAD = "Weather alert. "
    const val NOTIFIER_HELP = "In Breezy Weather, Settings → Modules → tick HeadLink under " +
        "Breezy Weather update notifier so the car hears about new weather straight away."

    /**
     * The settings card's status line (plan §4), and the tapped view's when there is no
     * reading. With a reading: "Waco · updated 12 min ago".
     */
    fun status(state: WeatherState, nowMillis: Long): String {
        val reading = state.reading
        return when {
            state.status == WeatherStatus.MISSING -> "Breezy Weather not installed"
            state.status == WeatherStatus.NO_PERMISSION -> "Permission not granted"
            state.status == WeatherStatus.WRONG_PACKAGE -> "Weather provider is not Breezy Weather's"
            state.status == WeatherStatus.UNSUPPORTED_MAJOR ->
                "Breezy Weather version not supported (provider ${state.major ?: "?"}.x)"
            state.status == WeatherStatus.NO_LOCATIONS -> "No locations in Breezy"
            reading != null -> "${placeName(reading.place)} · updated ${age(reading.data.refreshMillis, nowMillis)}"
            state.status == WeatherStatus.UNREADABLE -> "Weather unreadable"
            else -> "Not read yet"
        }
    }

    /** "just now", "12 min ago", "2 h ago", or "at an unknown time" with no refresh time. */
    fun age(refreshMillis: Long?, nowMillis: Long): String {
        refreshMillis ?: return "at an unknown time"
        val minutes = ((nowMillis - refreshMillis) / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            else -> "${minutes / 60} h ago"
        }
    }

    /** Whether the reading is past the stale limit (plan §2); one with no time counts as stale. */
    fun stale(data: WeatherData, nowMillis: Long): Boolean {
        val refresh = data.refreshMillis ?: return true
        return nowMillis - refresh > WeatherRules.STALE_MS
    }

    /** The place's distance from [fix] in whole km when it is past the far limit, else null. */
    fun farKm(place: WeatherPlace, fix: Pair<Double, Double>?): Int? {
        fix ?: return null
        val metres = WeatherRules.metres(fix.first, fix.second, place.latitude, place.longitude)
        return if (metres > WeatherRules.FAR_METRES) Math.round(metres / 1000.0).toInt() else null
    }

    /** The place as named, or "Weather" for a nameless one. */
    fun placeName(place: WeatherPlace): String = place.name.ifBlank { "Weather" }

    /**
     * The place and age line along the bottom of both views (plan §2): "Waco · 40 min ago",
     * led by "old" when stale and "62 km away" when far: "old · 62 km away · Waco · 4 h ago".
     * The flags go first so that a line too long for the tile loses the place's tail or the
     * age, never the words that say the weather may not be the truck's.
     */
    fun placeLine(reading: WeatherReading, fix: Pair<Double, Double>?, nowMillis: Long): String {
        val parts = ArrayList<String>(4)
        if (stale(reading.data, nowMillis)) parts += OLD
        farKm(reading.place, fix)?.let { parts += "$it km away" }
        parts += placeName(reading.place)
        parts += age(reading.data.refreshMillis, nowMillis)
        return parts.joinToString(PLACE_SEPARATOR)
    }

    /** What separates the place line's parts; the view breaks a two-line place line at one. */
    const val PLACE_SEPARATOR = " · "

    /** Breezy's temperature tags as drawn; any other tag is printed as it came (plan §1). */
    fun unitSuffix(unit: String?): String = when (unit?.lowercase()) {
        "f" -> "°F"
        "c" -> "°C"
        "k" -> " K"
        null, "" -> "°"
        else -> " $unit"
    }

    /** "78°F", rounded, with the tag it came with; [NO_READING] for none. */
    fun temperature(value: Tagged?): String =
        value?.let { "${Math.round(it.value)}${unitSuffix(it.unit)}" } ?: NO_READING

    /** "78°": the header's short form when "78°F" does not fit (plan §5.1). Kelvin keeps its K. */
    fun shortTemperature(value: Tagged?): String = value?.let {
        if (it.unit.equals("k", ignoreCase = true)) "${Math.round(it.value)} K" else "${Math.round(it.value)}°"
    } ?: NO_READING

    /** "Feels 84°" when it differs by [WeatherRules.FEELS_DIFFERENCE] or more, else null. */
    fun feels(data: WeatherData): String? {
        val t = data.temperature ?: return null
        val f = data.feelsLike ?: return null
        if (Math.abs(Math.round(f.value) - Math.round(t.value)) < WeatherRules.FEELS_DIFFERENCE) return null
        return "Feels ${shortTemperature(f)}"
    }

    /** "H 91° L 74°", either half left out when missing, or null for neither. */
    fun highLow(data: WeatherData): String? {
        val parts = listOfNotNull(data.high?.let { "H ${shortTemperature(it)}" }, data.low?.let { "L ${shortTemperature(it)}" })
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    /** "40%" when the chance of rain is [WeatherRules.RAIN_SHOWN_PERCENT] or more, else null. */
    fun rain(value: Tagged?): String? {
        value ?: return null
        val percent = Math.round(value.value)
        return if (percent >= WeatherRules.RAIN_SHOWN_PERCENT) "$percent%" else null
    }

    /** An hour on the view's row, in the dashboard clock's style: "15:00" or "3 PM". */
    @JvmOverloads
    fun hour(timeMillis: Long, zone: ZoneId, clock: ClockFormat = ClockFormat.DEFAULT): String = clock.hour(timeMillis, zone)

    /** "until 21:45" from an alert's end, in the dashboard clock's style; null with no end. */
    @JvmOverloads
    fun until(alert: WeatherAlert, zone: ZoneId, clock: ClockFormat = ClockFormat.DEFAULT): String? =
        alert.endMillis?.let { "until " + clock.time(it, zone) }

    /**
     * What is said for an alert (plan §6): "Weather alert. " and the headline, capped like a
     * read notification at [ReadAloudText.MAX]. The description and instructions never.
     */
    fun spoken(alert: WeatherAlert): String {
        val headline = alert.headline
        val cut = if (headline.length <= ReadAloudText.MAX) headline else {
            val head = headline.take(ReadAloudText.MAX)
            val space = head.lastIndexOf(' ')
            (if (space >= ReadAloudText.MAX / 2) head.substring(0, space) else head).trimEnd(' ', ',', ';', ':', '.', '-')
        }
        return ALERT_LEAD + cut
    }
}
