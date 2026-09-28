package org.openautoandroid.source

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
