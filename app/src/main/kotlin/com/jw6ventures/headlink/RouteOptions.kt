package com.jw6ventures.headlink

import kotlin.math.roundToInt

/**
 * Maps & routing → Routing: which roads a route may use and how its times are estimated.
 * Pure, so [Router], [PaceTracker] and the host tests need nothing saved; where they are
 * saved is `AppSettings`' business.
 *
 * **Roads.** Each switch is one of the `car-vario` profile's own parameters, passed to
 * BRouter as a key value ([profileParameters]); nothing here edits the vendored profile.
 * They are the profile's weights, not bans: an avoided highway is made much more costly,
 * an avoided toll road or toll booth is all but closed, and a destination that can
 * only be reached one way is still reached. Each was checked on the Texas tiles to change
 * a route (docs/navigation.md, "Route preferences").
 *
 * **Time.** BRouter times a route with the profile's kinematic model, which drives no
 * faster than [topSpeedKmh] anywhere a road has no lower limit. The profile ships with
 * 90 km/h (56 mph), which prices every Texas interstate mile at 56 mph: Austin to Dallas
 * came out at 3 h 42 min. [etaPercent] then scales every time the route carries, for
 * whatever the model still gets wrong, and [learnPace] lets the drive itself correct the
 * time still to go ([PaceTracker]).
 */
data class RouteOptions(
    val avoidHighways: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidUnpaved: Boolean = false,
    /** `consider_town`: go round cities and big towns where the profile can. */
    val bypassTowns: Boolean = false,
    /** `use_offroad`: also route over tracks and roads of unknown class. */
    val allowTracks: Boolean = false,
    /** The profile's `vmax`, km/h: one of [TOP_SPEEDS_KMH]. */
    val topSpeedKmh: Int = DEFAULT_TOP_SPEED_KMH,
    /** Added to every estimated time, percent: one of [ETA_PERCENTS]. */
    val etaPercent: Int = 0,
    /** Scale the time still to go by how the drive so far compared with its estimate. */
    val learnPace: Boolean = true,
    /** Look for other routes now and then while driving and draw them on the projected map ([LiveAlternatives]). */
    val showAlternatives: Boolean = true,
    /**
     * Route with OsmAnd's router over the `.obf` maps ([OsmAndRouter]) rather than BRouter
     * over `.rd5` tiles. Off by default while it is tried against BRouter on real drives.
     */
    val osmAndRouting: Boolean = false,
) {
    /** This value with a speed or percentage no list offers any more put back to its default. */
    fun repaired(): RouteOptions = copy(
        topSpeedKmh = topSpeedKmh.takeIf { it in TOP_SPEEDS_KMH } ?: DEFAULT_TOP_SPEED_KMH,
        etaPercent = etaPercent.takeIf { it in ETA_PERCENTS } ?: 0,
    )

    /**
     * What BRouter is given as `RoutingContext.keyValues`, every parameter every time, so
     * a switch turned off is sent off rather than left to whatever was parsed before.
     * Booleans go as `1` and `0`: BRouter parses each value as a number.
     */
    fun profileParameters(): Map<String, String> {
        fun flag(on: Boolean) = if (on) "1" else "0"
        return linkedMapOf(
            "avoid_motorways" to flag(avoidHighways),
            "avoid_toll" to flag(avoidTolls),
            "avoid_unpaved" to flag(avoidUnpaved),
            "consider_town" to flag(bypassTowns),
            "use_offroad" to flag(allowTracks),
            "vmax" to topSpeedKmh.toString(),
        )
    }

    /** The factor [etaPercent] puts on every time. */
    val timeFactor: Double get() = 1.0 + etaPercent / 100.0

    /** Whether going from this to [other] can change the route itself, not just its times' display. */
    fun routeDiffers(other: RouteOptions): Boolean =
        copy(learnPace = other.learnPace, showAlternatives = other.showAlternatives) != other

    /** One line for the report: choices only, nothing about any place. */
    fun report(): String =
        "routing_avoid_highways=$avoidHighways routing_avoid_tolls=$avoidTolls " +
            "routing_avoid_unpaved=$avoidUnpaved routing_bypass_towns=$bypassTowns " +
            "routing_allow_tracks=$allowTracks routing_top_speed_kmh=$topSpeedKmh " +
            "routing_eta_percent=$etaPercent routing_learn_pace=$learnPace routing_show_alternatives=$showAlternatives " +
            "routing_osmand=$osmAndRouting"

    companion object {
        /**
         * 56 to 85 mph in steps of five: the speeds a US driver thinks in, stored as the
         * km/h BRouter reads. 90 is the profile's own value, kept so the old estimate can
         * be had back. Texas interstates are posted 70 to 80 mph; the tagged limit still
         * caps a slower road, so a higher choice only speeds up roads posted that fast or
         * not posted at all.
         */
        val TOP_SPEEDS_KMH = listOf(90, 97, 105, 113, 121, 129, 137)

        /**
         * 75 mph. On the Texas tiles it put Austin to Dallas at 2 h 56 min and Austin to
         * Temple at 65 min, against 3 h 42 min and 79 min at the profile's 90 km/h.
         */
        const val DEFAULT_TOP_SPEED_KMH = 121

        val ETA_PERCENTS = listOf(-15, -10, -5, 0, 5, 10, 15, 20, 30)

        /** [TOP_SPEEDS_KMH]' labels, in its order: mph first, as the dashboard's default unit is. */
        fun topSpeedLabel(kmh: Int): String =
            "${(kmh / KMH_PER_MPH).roundToInt()} mph · $kmh km/h"

        /** [ETA_PERCENTS]' labels, in its order. */
        fun etaLabel(percent: Int): String = when {
            percent == 0 -> "as estimated"
            percent > 0 -> "+$percent% longer"
            else -> "${-percent}% shorter"
        }

        private const val KMH_PER_MPH = 1.609344
    }
}

/**
 * The drive's own pace against its route's estimate, for the time still to go: the "Learn
 * my pace" setting.
 *
 * Fed every fix [Guide] follows, with the route's own estimate of the seconds still to go
 * from that point. Between two fixes on the same route the estimate should fall by
 * exactly the time that passed; the ratio of the time that actually passed to the
 * estimate's fall, summed over the drive, is how much slower (above 1) or faster the
 * vehicle is going than BRouter thought. [factor] puts that ratio on the time still to go.
 *
 * - **Stretches.** Only consecutive fixes on the route count. Off the route, a re-route
 *   (a new [generation]), or a gap over [GAP_MS] with no fix at all ends a stretch without
 *   counting the gap: a lost signal says nothing about the pace.
 * - **Not before moving.** A stretch's time starts once the estimate first falls, so
 *   sitting in a car park with a route up is not read as a slow drive. Traffic lights and
 *   jams after that count in full, which is the point.
 * - **Earned slowly.** Under [MIN_ESTIMATE_S] of estimated driving the factor is 1; it
 *   then carries more of the measured ratio until [FULL_WEIGHT_S], and is held to
 *   [MIN_FACTOR]..[MAX_FACTOR] so one long jam cannot make a nonsense of the arrival.
 *
 * A re-route keeps what was learned: it is the same drive. A new destination resets it.
 * Pure and single-threaded, driven from the main thread like [RouteFollower].
 */
class PaceTracker {
    /** Seconds the estimate fell by, over every counted stretch. */
    var estimatedS = 0.0
        private set

    /** Seconds that actually passed over the same stretches. */
    var actualS = 0.0
        private set

    private var generation = -1L
    private var lastRemainingS: Int? = null
    private var lastMs = 0L
    private var moving = false

    /**
     * A fix on route [generation] at [nowMs] (any monotonic clock). [remainingS] is the
     * route's estimate of the seconds still to go from it, or null when the fix is not on
     * the route.
     */
    fun onFix(generation: Long, remainingS: Int?, nowMs: Long) {
        val last = lastRemainingS
        if (remainingS == null || generation != this.generation || last == null || nowMs - lastMs > GAP_MS || nowMs < lastMs) {
            // A new stretch: nothing between the last fix and this one is counted.
            this.generation = generation
            lastRemainingS = remainingS
            lastMs = nowMs
            moving = false
            return
        }
        val fell = last - remainingS
        if (!moving && fell > 0) moving = true
        if (moving) {
            estimatedS += fell
            actualS += (nowMs - lastMs) / 1000.0
        }
        lastRemainingS = remainingS
        lastMs = nowMs
    }

    /** The factor on the time still to go; 1 until enough of the drive is known. */
    fun factor(): Double {
        if (estimatedS < MIN_ESTIMATE_S) return 1.0
        val ratio = (actualS / estimatedS).coerceIn(MIN_FACTOR, MAX_FACTOR)
        val weight = (estimatedS / FULL_WEIGHT_S).coerceAtMost(1.0)
        return 1.0 + weight * (ratio - 1.0)
    }

    /** [seconds] of estimate, scaled by [factor]. */
    fun apply(seconds: Int): Int = (seconds * factor()).roundToInt()

    /** A new destination: nothing learned carries over. */
    fun reset() {
        estimatedS = 0.0
        actualS = 0.0
        generation = -1L
        lastRemainingS = null
        lastMs = 0L
        moving = false
    }

    companion object {
        /** Longer than any fix gap a moving vehicle has; the head unit sends about one a second. */
        const val GAP_MS = 30_000L
        const val MIN_ESTIMATE_S = 120.0
        const val FULL_WEIGHT_S = 600.0
        const val MIN_FACTOR = 0.67
        const val MAX_FACTOR = 1.5
    }
}
