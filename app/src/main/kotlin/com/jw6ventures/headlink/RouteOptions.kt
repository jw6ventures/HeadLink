package com.jw6ventures.headlink

import kotlin.math.roundToInt

/**
 * Maps & routing → Routing: which roads a route may use and how its times are estimated.
 * Pure, so [OsmAndRouter], [PaceTracker] and the host tests need nothing saved; where they
 * are saved is `AppSettings`' business.
 *
 * **Roads.** Each switch becomes one of OsmAnd's car-profile parameters
 * ([OsmAndRouter.profileParameters]). Avoiding highways or tolls makes those roads much
 * more costly, not banned, so a destination that can only be reached that way is still
 * reached. Avoiding unpaved roads is a ban: OsmAnd's profile closes tracks and unpaved
 * surfaces outright, so a destination reached only over one gets no route
 * (docs/navigation.md, "Route preferences").
 *
 * **Time.** The router drives no faster than [topSpeedKmh] anywhere a road has no lower
 * limit. [etaPercent] then scales every time the route carries, for whatever the speed
 * model still gets wrong, and [learnPace] lets the drive itself correct the time still to
 * go ([PaceTracker]).
 */
data class RouteOptions(
    val avoidHighways: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidUnpaved: Boolean = false,
    /** Go round cities and big towns where it can. OsmAnd's router does not yet apply it. */
    val bypassTowns: Boolean = false,
    /** Also route over tracks and roads of unknown class. OsmAnd's router does not yet apply it. */
    val allowTracks: Boolean = false,
    /** The fastest the router may assume on roads with no lower limit, km/h: one of [TOP_SPEEDS_KMH]. */
    val topSpeedKmh: Int = DEFAULT_TOP_SPEED_KMH,
    /** Added to every estimated time, percent: one of [ETA_PERCENTS]. */
    val etaPercent: Int = 0,
    /** Scale the time still to go by how the drive so far compared with its estimate. */
    val learnPace: Boolean = true,
    /** Look for other routes now and then while driving and draw them on the projected map ([LiveAlternatives]). */
    val showAlternatives: Boolean = true,
) {
    /** This value with a speed or percentage no list offers any more put back to its default. */
    fun repaired(): RouteOptions = copy(
        topSpeedKmh = topSpeedKmh.takeIf { it in TOP_SPEEDS_KMH } ?: DEFAULT_TOP_SPEED_KMH,
        etaPercent = etaPercent.takeIf { it in ETA_PERCENTS } ?: 0,
    )

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
            "routing_eta_percent=$etaPercent routing_learn_pace=$learnPace routing_show_alternatives=$showAlternatives"

    companion object {
        /**
         * 56 to 85 mph in steps of five: the speeds a US driver thinks in, stored as km/h.
         * 90 was the old default, kept so the old estimate can be had back. Texas
         * interstates are posted 70 to 80 mph; the tagged limit still caps a slower road,
         * so a higher choice only speeds up roads posted that fast or not posted at all.
         */
        val TOP_SPEEDS_KMH = listOf(90, 97, 105, 113, 121, 129, 137)

        /**
         * 75 mph. When it was chosen it put Austin to Dallas at 2 h 56 min and Austin to
         * Temple at 65 min, against 3 h 42 min and 79 min at 90 km/h.
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
 * vehicle is going than the router thought. [factor] puts that ratio on the time still to go.
 *
 * - **Stretches.** Only consecutive fixes on the route count. Off the route, a re-route
 *   (a new [generation]), or a gap over [GAP_MS] with no fix at all ends a stretch without
 *   counting the gap: a lost signal says nothing about the pace.
 * - **Not before moving.** A stretch's time starts once the estimate has fallen by
 *   [START_FALL_S] within [START_WINDOW_MS], so sitting in a car park with a route up is
 *   not read as a slow drive. Traffic lights and jams after that count in full, which is
 *   the point. Up to 0.10.3 the first fall of any size started it: on 2026-11-19 the
 *   position wandering 4 s along the route in a parked car started the clock, and the
 *   next 417 parked seconds were counted as driving (`guidance_pace_estimated_s=4
 *   guidance_pace_actual_s=421`), which carried into the drive and pushed the trip
 *   panel's time towards [MAX_FACTOR] times the estimate.
 * - **Earned slowly.** Under [MIN_ESTIMATE_S] of estimated driving the factor is 1; it
 *   then carries more of the measured ratio until [FULL_WEIGHT_S], and is held to
 *   [MIN_FACTOR]..[MAX_FACTOR] so one long jam cannot make a nonsense of the arrival.
 *
 * A re-route keeps what was learned: it is the same drive. A new destination resets it.
 * Pure and single-threaded, driven from the main thread like [RouteFollower].
 *
 * **Against the learned pace.** Since 0.11 [Guide] feeds it the time to go at the pace
 * learned across drives ([PaceBook]), so what it measures is how this drive differs from
 * the usual (a jam, the weather), and gives the router's own fall alongside, which
 * [countedRawS] and [countedActualS] hand on for [PaceBook] to learn from.
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
    private var lastRawS: Int? = null
    private var lastMs = 0L
    private var moving = false

    /** Before [moving]: how far the estimate has fallen since [pendingMs], in seconds. */
    private var pendingFall = 0
    private var pendingMs = 0L

    /**
     * A fix on route [generation] at [nowMs] (any monotonic clock). [remainingS] is the
     * route's estimate of the seconds still to go from it, or null when the fix is not on
     * the route.
     */
    fun onFix(generation: Long, remainingS: Int?, nowMs: Long) = onFix(generation, remainingS, nowMs, remainingS)

    /**
     * [onFix], with [remainingS] at the learned pace and [rawRemainingS] the route's own
     * estimate from the same point: the start rule and the factor read [remainingS]; what
     * was counted is left in [countedRawS] and [countedActualS] until the next fix.
     */
    fun onFix(generation: Long, remainingS: Int?, nowMs: Long, rawRemainingS: Int?) {
        countedRawS = 0.0
        countedActualS = 0.0
        val last = lastRemainingS
        val lastRaw = lastRawS
        if (remainingS == null || rawRemainingS == null || generation != this.generation || last == null || lastRaw == null ||
            nowMs - lastMs > GAP_MS || nowMs < lastMs
        ) {
            // A new stretch: nothing between the last fix and this one is counted.
            this.generation = generation
            lastRemainingS = remainingS
            lastRawS = rawRemainingS
            lastMs = nowMs
            moving = false
            pendingFall = 0
            pendingMs = nowMs
            return
        }
        val fell = last - remainingS
        if (!moving) {
            if (nowMs - pendingMs > START_WINDOW_MS) {
                pendingFall = 0
                pendingMs = lastMs
            }
            pendingFall = (pendingFall + fell).coerceAtLeast(0)
            // This fix's own interval is not counted: the stretch starts from here.
            if (pendingFall >= START_FALL_S) moving = true
        } else {
            estimatedS += fell
            val passed = (nowMs - lastMs) / 1000.0
            actualS += passed
            countedRawS = (lastRaw - rawRemainingS).toDouble()
            countedActualS = passed
        }
        lastRemainingS = remainingS
        lastRawS = rawRemainingS
        lastMs = nowMs
    }

    /** The route's own estimate's fall over the last fix's interval, if it was counted; else 0. */
    var countedRawS = 0.0
        private set

    /** The seconds that passed over the last fix's interval, if it was counted; else 0. */
    var countedActualS = 0.0
        private set

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
        lastRawS = null
        lastMs = 0L
        countedRawS = 0.0
        countedActualS = 0.0
        moving = false
        pendingFall = 0
        pendingMs = 0L
    }

    companion object {
        /** Longer than any fix gap a moving vehicle has; the head unit sends about one a second. */
        const val GAP_MS = 30_000L

        /** The fall in the estimate that says the vehicle is under way, and the time it must happen in. */
        const val START_FALL_S = 20
        const val START_WINDOW_MS = 60_000L
        const val MIN_ESTIMATE_S = 120.0
        const val FULL_WEIGHT_S = 600.0
        const val MIN_FACTOR = 0.67
        const val MAX_FACTOR = 1.5
    }
}
