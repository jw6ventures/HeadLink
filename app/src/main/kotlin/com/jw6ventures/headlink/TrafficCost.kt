package com.jw6ventures.headlink

/**
 * What a jam's or an opened closure's no-go points ([TrafficNogos]) weigh. A weight is
 * seconds per metre of road inside the circle: [OsmAndRouter] adds each step's metres
 * inside every circle it crosses, times that circle's weight, to the step's own time. Pure
 * Kotlin, no state, callable from any thread.
 *
 * **Per metre.** A road through the middle of a circle of radius r has 2r inside it, so n
 * points on a stretch with d seconds of delay, each weighing d ÷ (n × 2r), charge a route
 * along the stretch d in all. A road off-centre has less inside, so that is the most a
 * point charges; a road crossing at a junction or overpass is charged only its own metres
 * inside, and [TrafficNogos] keeps points clear of those anyway. A second of delay is the
 * same whatever the top speed.
 *
 * **Why more than the delay** ([classFactor]). OsmAnd does not choose routes by their
 * seconds alone. Its car profile weighs each piece of road by its length ÷ (speed ×
 * priority), no faster than 130 km/h, where the priority is 1.1 on a motorway, 0.65 on its
 * ramps, 0.9 to 1.0 on primary to tertiary roads and 0.7 on residential streets, and adds
 * 7.5 to 30 s for a turn; none of that is in a route's time. A way round a jam leaves a
 * main road for ramps and lesser roads and turns twice, so it costs the search more than
 * it costs the driver, most of all off a motorway. Charged exactly its delay, a jam was left
 * whole only at 2.25 and 3.34 times what the way round really took on two interstates, and
 * at 1.22 and 1.18 on a highway and an arterial (NogoRoutingTest, 24 November 2026, points
 * placed by [TrafficNogos]). [MOTORWAY_FACTOR] and [ROAD_FACTOR] are empirical defaults from
 * those four stretches, not a fit validated across road classes or cities;
 * docs/navigation.md, "Live traffic (HERE)", has the measurements with them in place.
 *
 * Until 0.12 the weights were in BRouter's kinematic cost (19.0 a second at 121 km/h,
 * moving with the top speed, and ×1.6 on classes 1 and 2 for the circles BRouter skipped
 * on long links). OsmAnd charges every step, in seconds, so none of that carries over.
 */
object TrafficCost {
    /** [classFactor] on HERE's functional class 1 (motorways and the like). */
    const val MOTORWAY_FACTOR = 2.8

    /** [classFactor] on HERE's classes 2 to 5. */
    const val ROAD_FACTOR = 1.2

    /**
     * What each point of a closure costs to pass, in seconds, when [OsmAndRouter] opens
     * closures to find any route at all (a vehicle already on a closed road, or a
     * destination inside one). Enough that an open road is taken wherever there is one.
     */
    const val OPENED_CLOSURE_SECONDS = 300.0

    /**
     * How much more than its delay a jam on a road of HERE [functionalClass] (1 to 5) is
     * charged, for what OsmAnd adds to a way round beyond its time (class notes).
     */
    fun classFactor(functionalClass: Int): Double = if (functionalClass <= 1) MOTORWAY_FACTOR else ROAD_FACTOR

    /**
     * The weight of each of [points] circles of [radiusM] laid on a stretch of HERE
     * [functionalClass] with [delaySeconds] of delay, so that together, a road through each
     * one's middle, they charge the delay times [classFactor]. 0 for no points or no delay.
     */
    @JvmOverloads
    fun pointWeight(delaySeconds: Double, points: Int, functionalClass: Int, radiusM: Double = TrafficNogos.RADIUS_M): Double {
        if (points <= 0 || !(delaySeconds > 0.0)) return 0.0
        return circleWeight(delaySeconds * classFactor(functionalClass) / points, radiusM)
    }

    /** The weight of a circle of [radiusM] that charges [seconds] to a road through its middle. */
    @JvmOverloads
    fun circleWeight(seconds: Double, radiusM: Double = TrafficNogos.RADIUS_M): Double = seconds / (2.0 * radiusM)

    /** The weight that makes a closure's circle of [radiusM] passable at [OPENED_CLOSURE_SECONDS]. */
    fun openedClosureWeight(radiusM: Double): Double = circleWeight(OPENED_CLOSURE_SECONDS, radiusM)
}
