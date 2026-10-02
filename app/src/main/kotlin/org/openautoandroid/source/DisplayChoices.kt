package org.openautoandroid.source

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The colour of the vehicle's dot and arrow on the owned map (P7 plan §4.2).
 *
 * A small fixed set rather than a free colour, because every entry needs a rim that stays
 * visible against it *and* against whatever the map under it is: the arrow's thin tip is
 * what disappears into a busy map first, and a colour picker would let the driver pick one
 * with no rim that works. Light fills get a dark rim and dark fills a light one.
 *
 * [key] is what [AppSettings] stores, so it never changes once shipped; [label] is only
 * what the settings screen shows. Pure Kotlin with no Android API, so it is host-tested.
 */
enum class MarkerColour(val key: String, val label: String, val fill: Int, val rim: Int) {
    /**
     * Today's marker exactly: the day palette's accent, rimmed with the bubbles' own
     * translucent edge, so a first launch after P7.1 draws the vehicle as before. That rim
     * is close to the fill in brightness; the review found the outline visible enough
     * (P7 plan §1 item 12), so it is kept rather than "fixed" into a different look.
     */
    BLUE("blue", "blue", 0xFF4E9AF1.toInt(), 0xB39AA5B1.toInt()),
    WHITE("white", "white", 0xFFF2F4F6.toInt(), 0xFF12161A.toInt()),
    YELLOW("yellow", "yellow", 0xFFFFD60A.toInt(), 0xFF12161A.toInt()),
    ORANGE("orange", "orange", 0xFFFF8C1A.toInt(), 0xFF12161A.toInt()),
    GREEN("green", "green", 0xFF34C759.toInt(), 0xFF12161A.toInt()),
    RED("red", "red", 0xFFE5484D.toInt(), 0xFFF2F4F6.toInt()),
    MAGENTA("magenta", "magenta", 0xFFE040FB.toInt(), 0xFFF2F4F6.toInt()),
    ;

    companion object {
        val DEFAULT = BLUE

        /** The choices in the order the settings screen offers them; the first is the default. */
        val LABELS: List<String> = entries.map { it.label }

        /**
         * The colour a stored [key] names, or [DEFAULT] for none or one this build does not
         * know: a colour a later build dropped must not leave the vehicle undrawn.
         */
        fun of(key: String?): MarkerColour = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Which palette the dashboard and the map use (P7 plan §5.6): follow the head unit, or
 * hold day or night whatever it says.
 *
 * Automatic is the default because the head unit's `is_night` is the signal that tracks
 * the headlights and the dusk. Day and Night exist because a display pinned to one mode by
 * its owner (as the truck's is, §1 item 16) says so for the whole drive, and the driver
 * should not have to change the truck's setting to get the other palette on the phone's
 * picture.
 *
 * [key] is what [AppSettings] stores; [label] is the settings screen's. Pure Kotlin, so
 * host-tested.
 */
enum class DayNightMode(val key: String, val label: String) {
    AUTO("auto", "automatic"),
    DAY("day", "day"),
    NIGHT("night", "night"),
    ;

    /** Whether to draw the night palette, given what the head unit last said. */
    fun resolve(headUnitNight: Boolean): Boolean = when (this) {
        AUTO -> headUnitNight
        DAY -> false
        NIGHT -> true
    }

    /**
     * The mode one tap on the dashboard's ☀/☾ icon moves to: Automatic → Day → Night →
     * Automatic. A cycle rather than a toggle, so Automatic is always two taps away at
     * most and never lost for the rest of the drive.
     */
    fun next(): DayNightMode = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = AUTO

        /** The choices in the order the settings screen offers them; the first is the default. */
        val LABELS: List<String> = entries.map { it.label }

        /** The mode a stored [key] names, or [DEFAULT] for none or one this build does not know. */
        fun of(key: String?): DayNightMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * How every clock time the app draws is written: the dashboard's header clock and arrival
 * time, the weather view's hours and an alert's "until", and the route line on the phone.
 *
 * 24-hour is the default because it is what the dashboard drew before the choice existed.
 * 12-hour writes "6:40 PM", except the header's own clock ([clock]), which leaves the
 * marker off as car clocks do: the time now is never ambiguous, and Ford's 726x480 header
 * with the weather item has room for "23:59", the speed and the bearing but not a marker
 * as well (WeatherTest). The trip panel's arrival draws its " PM" smaller ([split]).
 *
 * The patterns are fixed and the AM/PM marker is always English, rather than taken from
 * the phone's locale: the dashboard is a JVM model with no locale of its own, and the
 * phone's line should read the same as the dashboard's.
 *
 * [key] is what [AppSettings] stores; [label] is the settings screen's. Pure Kotlin, so
 * host-tested.
 */
enum class ClockFormat(val key: String, val label: String, time: String, clock: String, hour: String) {
    H24("24", "24-hour (18:40)", "HH:mm", "HH:mm", "HH:00"),
    H12("12", "12-hour (6:40 PM)", "h:mm a", "h:mm", "h a"),
    ;

    private val timePattern: DateTimeFormatter = DateTimeFormatter.ofPattern(time, Locale.US)
    private val clockPattern: DateTimeFormatter = DateTimeFormatter.ofPattern(clock, Locale.US)
    private val hourPattern: DateTimeFormatter = DateTimeFormatter.ofPattern(hour, Locale.US)

    /** A clock time: "18:40" or "6:40 PM". */
    fun time(millis: Long, zone: ZoneId): String = timePattern.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** The header's clock: "18:40", or "6:40" with no marker. */
    fun clock(millis: Long, zone: ZoneId): String = clockPattern.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** The hour [millis] falls in: "18:00" or "6 PM". */
    fun hour(millis: Long, zone: ZoneId): String = hourPattern.format(Instant.ofEpochMilli(millis).atZone(zone))

    companion object {
        val DEFAULT = H24

        /** The choices in the order the settings screen offers them; the first is the default. */
        val LABELS: List<String> = entries.map { it.label }

        /** The format a stored [key] names, or [DEFAULT] for none or one this build does not know. */
        fun of(key: String?): ClockFormat = entries.firstOrNull { it.key == key } ?: DEFAULT

        /**
         * A [time] as the digits and the AM/PM marker, "6:40" and "PM", for drawing the
         * marker smaller; a 24-hour time, or anything else, is all digits and no marker.
         */
        fun split(time: String): Pair<String, String?> {
            val space = time.lastIndexOf(' ')
            val marker = time.substring(space + 1)
            return if (space > 0 && (marker == "AM" || marker == "PM")) time.substring(0, space) to marker else time to null
        }
    }
}
