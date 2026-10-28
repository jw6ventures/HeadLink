package com.jw6ventures.headlink

import java.time.ZoneId

/**
 * The header's weather item (P10), in words and flags, so the chrome repaints only
 * when what is drawn changes. [temperature] is "78°F" or [WeatherText.NO_READING].
 */
data class WeatherHeader(
    val code: WeatherCode?,
    val night: Boolean,
    val temperature: String,
    /** No reading yet, the provider refused, or the reading is past the stale limit. */
    val dim: Boolean,
    /** The most severe qualifying alert's colour, for the glyph's warning mark; else null. */
    val alertColour: Int?,
    /** A finger is on it (never during a call). */
    val pressed: Boolean,
)

/** One hour on the weather view's row: "15:00", its glyph, "74°", and "40%" or null. */
data class WeatherHourCell(val hour: String, val code: WeatherCode?, val night: Boolean, val temperature: String, val rain: String?)

/** The alert strip over the weather view's "Now" block. */
data class WeatherStrip(val colour: Int?, val headline: String)

/**
 * The tapped weather view. With no reading, only [status] is set: "Permission
 * not granted", "Breezy Weather not installed"… so the tap always says something.
 */
data class WeatherPanel(
    val status: String?,
    val code: WeatherCode? = null,
    val night: Boolean = false,
    val temperature: String = WeatherText.NO_READING,
    val text: String? = null,
    /** "Feels 84° · H 91° L 74°", whichever parts there are, or null. */
    val detail: String? = null,
    val hours: List<WeatherHourCell> = emptyList(),
    val strip: WeatherStrip? = null,
    /** "Waco · 40 min ago", or "old · 62 km away · Waco · 4 h ago" ([WeatherText.placeLine]). */
    val place: String? = null,
    val dim: Boolean = false,
    val pressed: Boolean = false,
)

/** The alert view: the bar's colour, the headline, "until 21:45", "+1 more". */
data class WeatherAlertCard(
    val id: String,
    val colour: Int?,
    val headline: String,
    val until: String?,
    val more: Int,
    val place: String,
    val pressed: Boolean,
)

/** Builds [WeatherHeader], [WeatherPanel] and [WeatherAlertCard] from a [WeatherState]. Pure. */
object WeatherDisplay {
    /**
     * [fit] picks the temperature's form: "78°F", or "78°" when the item has no room for the
     * unit ([DashboardLayout.headerWeatherText]).
     */
    fun header(
        state: WeatherState,
        nowMillis: Long,
        level: AlertLevel,
        pressed: Boolean,
        fit: (String, String) -> String = { full, _ -> full },
    ): WeatherHeader {
        val reading = state.reading
        val data = reading?.data
        // The warning mark stays while any qualifying alert is in force, dismissed or not:
        // dismissing takes the alert off the tile, not the weather off the header.
        val alert = data?.let { WeatherRules.qualifying(it.alerts, nowMillis, level).firstOrNull() }
        return WeatherHeader(
            code = data?.code,
            night = data?.daylight == false,
            temperature = fit(WeatherText.temperature(data?.temperature), WeatherText.shortTemperature(data?.temperature)),
            dim = data == null || WeatherText.stale(data, nowMillis),
            alertColour = alert?.let { it.colour ?: DEFAULT_ALERT_COLOUR },
            pressed = pressed,
        )
    }

    fun panel(
        state: WeatherState,
        nowMillis: Long,
        fix: Pair<Double, Double>?,
        zone: ZoneId,
        pressed: Boolean,
        clock: ClockFormat = ClockFormat.DEFAULT,
    ): WeatherPanel {
        val reading = state.reading ?: return WeatherPanel(status = WeatherText.status(state, nowMillis), pressed = pressed)
        val data = reading.data
        val detail = listOfNotNull(WeatherText.feels(data), WeatherText.highLow(data)).joinToString(" · ").ifEmpty { null }
        // The hours after the current one, [WeatherRules.VIEW_HOURS] of them.
        val later = data.hours.filter { it.timeMillis > nowMillis }.take(WeatherRules.VIEW_HOURS)
        val strip = WeatherRules.active(data.alerts, nowMillis).firstOrNull()?.let { WeatherStrip(it.colour ?: DEFAULT_ALERT_COLOUR, it.headline) }
        return WeatherPanel(
            status = null,
            code = data.code,
            night = !data.daylight,
            temperature = WeatherText.temperature(data.temperature),
            text = data.text,
            detail = detail,
            hours = later.map {
                WeatherHourCell(WeatherText.hour(it.timeMillis, zone, clock), it.code, !it.daylight, WeatherText.shortTemperature(it.temperature), WeatherText.rain(it.rain))
            },
            strip = strip,
            place = WeatherText.placeLine(reading, fix, nowMillis),
            dim = WeatherText.stale(data, nowMillis),
            pressed = pressed,
        )
    }

    /** The alert to show over notifications, or null: the first of [WeatherRules.qualifying]. */
    fun alert(
        state: WeatherState,
        nowMillis: Long,
        fix: Pair<Double, Double>?,
        zone: ZoneId,
        level: AlertLevel,
        dismissed: Set<String>,
        pressed: Boolean,
        clock: ClockFormat = ClockFormat.DEFAULT,
    ): WeatherAlertCard? {
        val reading = state.reading ?: return null
        val alerts = WeatherRules.qualifying(reading.data.alerts, nowMillis, level, dismissed)
        val first = alerts.firstOrNull() ?: return null
        return WeatherAlertCard(
            id = first.id,
            colour = first.colour ?: DEFAULT_ALERT_COLOUR,
            headline = first.headline,
            until = WeatherText.until(first, zone, clock),
            more = alerts.size - 1,
            place = WeatherText.placeLine(reading, fix, nowMillis),
            pressed = pressed,
        )
    }

    /** An alert Breezy sent no colour for: the orange of a warning, opaque. */
    const val DEFAULT_ALERT_COLOUR = 0xFFFF8C00.toInt()
}
