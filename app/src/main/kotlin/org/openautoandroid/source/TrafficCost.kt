package org.openautoandroid.source

/**
 * What a second of traffic delay is worth in BRouter's currency (P9.5, plan §7 "How
 * heavy"), so a jam's no-go points ([TrafficNogos]) weigh what its delay would. Measured on
 * the Texas tiles on 28 September 2026 (docs/p9-5-routing-results.md); pure Kotlin, no
 * state, callable from any thread.
 *
 * **BRouter's cost is not seconds.** `car-vario` is a kinematic profile: a piece of road
 * costs (pw × seconds + energy) ÷ cost0, where pw and cost0 follow from the top speed
 * (`KinematicModel.init`), and a stop or a turn's extra seconds cost pw ÷ cost0 each
 * (`KinematicPath.processWaySection`). Cost per second over whole tracks, by the class of
 * road driven, at the default 121 km/h (ten routes in Dallas, Austin and rural Texas):
 *
 * | Road | Cost/s | km/h |
 * | --- | --- | --- |
 * | motorway | 29.4 | 103 |
 * | trunk | 27.0 | 83 |
 * | primary | 24.8 | 66 |
 * | secondary | 21.9 | 39 |
 * | tertiary | 22.3 | 47 |
 * | residential | 19.9 | 15 |
 *
 * Above the floor of pw ÷ cost0 (19.0 at 121 km/h) is the energy of the distance driven,
 * which a queue does not add: it is time, not distance. What matters to a route choice is
 * how BRouter trades cost against time *between* two routes, and across seven real
 * detours (a stretch blocked, the way round compared) that came to 14.6 to 19.1 a second at
 * 121 km/h, with one 34 where the way round was 4 km longer: pw ÷ cost0 again. So a
 * second of delay is priced as BRouter prices its own stops, [secondPrice], and it moves
 * with the top speed (12.5 at 90 km/h, 22.3 at 137).
 *
 * **One charge per link.** BRouter keeps only the last circle a link passes through
 * (`RoutingContext.calcDistance`; [Router]), and interstate links in Texas run 45 m to
 * 1.6 km (median 180 m), longer than the placement's 150 m spacing: on classes 1 and 2
 * some points are never charged. With points placed as [TrafficNogos] places them and
 * weighed at [secondPrice], BRouter left a jammed interstate or trunk only when the delay
 * reached 0.6 to 3.2 times what the way round actually took (median 1.6, ten stretches);
 * on arterials, whose links are mostly shorter than the spacing, 0.75 to 1.15. [linkFactor]
 * puts that median back.
 *
 * A typical jam, at the default top speed: a 1 km interstate stretch with 3 minutes of
 * delay has 7 points (every 150 m, none within 30 m of either end), each with 16 m of road
 * inside its 8 m circle, so each weighs 180 × [costPerSecond] (30.4) ÷ (7 × 16) = 48.8; on
 * an arterial, 30.5.
 */
object TrafficCost {
    /** `car-vario`'s rolling resistance, air drag and standby power: the profile's own values, which the app never overrides. */
    private const val F_ROLL = 232.0
    private const val F_AIR = 0.4
    private const val P_STANDBY = 250.0

    /**
     * How much further a jam on a class 1 or 2 road is weighed, for the points BRouter
     * never charges there (class notes): the median of the measured shortfall.
     */
    const val MAJOR_ROAD_LINK_FACTOR = 1.6

    /**
     * What each point of a closure costs to pass when [Router] has to open closures to find
     * any route at all, in seconds of delay. Enough that an open road is always taken where
     * there is one; not so much that BRouter's search, which spreads until it has spent
     * that cost, takes seconds: a destination inside a closure took 0.65 s at 300 s a point
     * and 3.5 s at 1,800, for the same route.
     */
    const val OPENED_CLOSURE_SECONDS = 300.0

    /**
     * BRouter's cost for one second standing still at a top speed of [topSpeedKmh]:
     * `KinematicModel`'s pw ÷ cost0 for `car-vario`. 19.0 at the default 121 km/h.
     */
    fun secondPrice(topSpeedKmh: Int): Double {
        val v = topSpeedKmh / 3.6
        val pw = 2.0 * F_AIR * v * v * v - P_STANDBY
        val cost0 = (pw + P_STANDBY) / v + F_ROLL + F_AIR * v * v
        return pw / cost0
    }

    /** [MAJOR_ROAD_LINK_FACTOR] for HERE functional classes 1 and 2, else 1. */
    fun linkFactor(functionalClass: Int): Double = if (functionalClass <= 2) MAJOR_ROAD_LINK_FACTOR else 1.0

    /**
     * BRouter cost per second of delay on a HERE stretch of [functionalClass] (1 to 5), for
     * a route timed at [topSpeedKmh]: what [TrafficNogos.of]' `costPerSecond` wants. The
     * request's own top speed belongs here; [Router] rescales if it times the route slower.
     */
    @JvmOverloads
    fun costPerSecond(functionalClass: Int, topSpeedKmh: Int = RouteOptions.DEFAULT_TOP_SPEED_KMH): Double =
        secondPrice(topSpeedKmh) * linkFactor(functionalClass)

    /**
     * The weight of each of [points] circles of [radiusM] laid on a stretch with
     * [delaySeconds] of delay, so that together, a road through each one's middle, they
     * charge the delay's cost. 0 for no points or no delay.
     */
    @JvmOverloads
    fun pointWeight(
        delaySeconds: Double,
        points: Int,
        functionalClass: Int,
        topSpeedKmh: Int = RouteOptions.DEFAULT_TOP_SPEED_KMH,
        radiusM: Double = TrafficNogos.RADIUS_M,
    ): Double {
        if (points <= 0 || !(delaySeconds > 0.0)) return 0.0
        return delaySeconds * costPerSecond(functionalClass, topSpeedKmh) / (points * 2.0 * radiusM)
    }

    /** The weight that makes a closure's circle of [radiusM] passable at [OPENED_CLOSURE_SECONDS] ([Router]). */
    fun openedClosureWeight(radiusM: Double, topSpeedKmh: Int): Double =
        OPENED_CLOSURE_SECONDS * secondPrice(topSpeedKmh) / (2.0 * radiusM)
}
