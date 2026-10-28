package com.jw6ventures.headlink

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings → OsmAnd maps: the map's POI markers and search ahead (docs/navigation.md).
 *
 * Pure Kotlin, stored keys included, like [TrafficSettings]: [PoiSettings] only moves
 * [toStored]'s map in and out of its own preferences file, so the host test checks the
 * defaults, the repair and the round trip. Distances are miles; the corridor
 * is stored in tenths of a mile, since preferences hold no double.
 */
data class PoiValues(
    /** Markers per category (Gas, Food, Shop), nearest the centre first. */
    val markersPerKind: Int = DEFAULT_MARKERS,
    /** Search ahead: how far along the route, miles. */
    val aheadMiles: Int = DEFAULT_AHEAD_MILES,
    /** Search ahead: the corridor's half-width, tenths of a mile (5 = 0.5 mile). */
    val corridorTenths: Int = DEFAULT_CORRIDOR_TENTHS,
    /** Search ahead: how many of the nearest candidates get a detour routed. */
    val aheadCandidates: Int = DEFAULT_CANDIDATES,
) {
    val corridorMiles: Double get() = corridorTenths / 10.0

    /** This value with anything out of range put back to its default. */
    fun repaired(): PoiValues {
        val d = PoiValues()
        return PoiValues(
            markersPerKind = if (markersPerKind in MARKERS_RANGE) markersPerKind else d.markersPerKind,
            aheadMiles = if (aheadMiles in AHEAD_RANGE) aheadMiles else d.aheadMiles,
            corridorTenths = if (corridorTenths in CORRIDOR_TENTHS_RANGE) corridorTenths else d.corridorTenths,
            aheadCandidates = if (aheadCandidates in CANDIDATES_RANGE) aheadCandidates else d.aheadCandidates,
        )
    }

    /** Every field under its stored key. Never renamed once shipped. */
    fun toStored(): Map<String, Int> {
        val s = repaired()
        return linkedMapOf(
            MARKERS to s.markersPerKind,
            AHEAD to s.aheadMiles,
            CORRIDOR to s.corridorTenths,
            CANDIDATES to s.aheadCandidates,
        )
    }

    companion object {
        const val DEFAULT_MARKERS = 25
        val MARKERS_RANGE = 5..100
        const val DEFAULT_AHEAD_MILES = 50
        val AHEAD_RANGE = 10..150
        const val DEFAULT_CORRIDOR_TENTHS = 5
        /** 0.1 to 2.0 miles. */
        val CORRIDOR_TENTHS_RANGE = 1..20
        const val DEFAULT_CANDIDATES = 5
        val CANDIDATES_RANGE = 1..10

        /** The corridor setter's input in miles, clamped into range, in tenths. */
        fun corridorTenths(miles: Double): Int =
            if (miles.isNaN()) DEFAULT_CORRIDOR_TENTHS
            else Math.round(miles.coerceIn(0.1, 2.0) * 10).toInt().coerceIn(CORRIDOR_TENTHS_RANGE)

        /** Reads what [toStored] wrote; a missing, mistyped or out-of-range value reads as its default. */
        fun fromStored(stored: Map<String, *>): PoiValues {
            val d = PoiValues()
            return PoiValues(
                markersPerKind = stored[MARKERS] as? Int ?: d.markersPerKind,
                aheadMiles = stored[AHEAD] as? Int ?: d.aheadMiles,
                corridorTenths = stored[CORRIDOR] as? Int ?: d.corridorTenths,
                aheadCandidates = stored[CANDIDATES] as? Int ?: d.aheadCandidates,
            ).repaired()
        }

        // Stored keys.
        const val MARKERS = "poi_markers_per_kind"
        const val AHEAD = "poi_ahead_miles"
        const val CORRIDOR = "poi_corridor_tenths"
        const val CANDIDATES = "poi_ahead_candidates"
    }
}

/**
 * [PoiValues] in the app's private `poi` preferences file. Reads are served from memory
 * after the first, so any thread may call the getters; setters clamp their input into
 * range rather than refuse it.
 */
object PoiSettings {
    private const val FILE = "poi"

    fun load(context: Context): PoiValues = PoiValues.fromStored(prefs(context).all)

    /** Reads, changes, stores; the same pattern as [AppSettings.update]. */
    fun update(context: Context, change: (PoiValues) -> PoiValues): PoiValues = synchronized(this) {
        val next = change(load(context)).repaired()
        val editor = prefs(context).edit()
        for ((key, value) in next.toStored()) editor.putInt(key, value)
        editor.apply()
        next
    }

    /** Default 25, 5..100. */
    fun markersPerKind(context: Context): Int = load(context).markersPerKind

    /** Default 50, 10..150. */
    fun aheadMiles(context: Context): Int = load(context).aheadMiles

    /** Default 0.5, 0.1..2.0. */
    fun corridorMiles(context: Context): Double = load(context).corridorMiles

    /** Default 5, 1..10. */
    fun aheadCandidates(context: Context): Int = load(context).aheadCandidates

    fun setMarkersPerKind(context: Context, value: Int): PoiValues =
        update(context) { it.copy(markersPerKind = value.coerceIn(PoiValues.MARKERS_RANGE)) }

    fun setAheadMiles(context: Context, value: Int): PoiValues =
        update(context) { it.copy(aheadMiles = value.coerceIn(PoiValues.AHEAD_RANGE)) }

    fun setCorridorMiles(context: Context, value: Double): PoiValues =
        update(context) { it.copy(corridorTenths = PoiValues.corridorTenths(value)) }

    fun setAheadCandidates(context: Context, value: Int): PoiValues =
        update(context) { it.copy(aheadCandidates = value.coerceIn(PoiValues.CANDIDATES_RANGE)) }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
