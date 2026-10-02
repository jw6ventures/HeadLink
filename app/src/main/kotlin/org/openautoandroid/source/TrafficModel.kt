package org.openautoandroid.source

/*
 * Live traffic (P9, docs/p9-plan.md): the values passed between its parts. Pure Kotlin and
 * no Android, so the host tests load it.
 *
 * Nothing here is ever written to storage, the report or the event log: HERE's terms allow
 * no keeping of its data beyond the response it came in (plan §1, 6.4 j), and where the
 * vehicle is stays out of the report (P6 plan §9.6).
 */

/** A box on the map, degrees, as HERE's `in=bbox:W,S,E,N` takes it. */
data class TrafficBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    /** HERE's `bbox:` value, west, south, east, north. */
    fun query(): String = "bbox:%.5f,%.5f,%.5f,%.5f".format(java.util.Locale.ROOT, west, south, east, north)

    fun contains(lat: Double, lon: Double): Boolean = lat in south..north && lon in west..east
}

/** Whether HERE says a stretch can be driven. */
enum class Traversability { OPEN, CLOSED, REVERSIBLE_NOT_ROUTABLE }

/**
 * One part of a flow stretch with a single set of figures, [fromM] to [toM] metres along the
 * stretch's shape. A stretch without `subSegments` has one span covering all of it; one with
 * them has a span for each, in order, laid end to end by their lengths.
 */
class FlowSpan(
    val fromM: Double,
    val toM: Double,
    /** HERE's `speed`, m/s; null where HERE omits it (seen only on closed stretches). */
    val speedMps: Double?,
    /** HERE's `freeFlow`, m/s, or null. */
    val freeFlowMps: Double?,
    /** HERE's `jamFactor`, 0 to 10. */
    val jamFactor: Double,
    val traversability: Traversability,
)

/**
 * One HERE flow result: a stretch of road in HERE's own geometry (its `shape.links` joined
 * in order, repeated joins dropped) with the flow on it.
 */
class FlowStretch(
    /** HERE's `location.description`, a road name like "I-35E", or null. */
    val description: String?,
    /** HERE's `location.length`, metres. */
    val lengthM: Double,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    /** The lowest (most major) `functionalClass` among its links, 1 to 5. */
    val functionalClass: Int,
    val spans: List<FlowSpan>,
)

/** One HERE incident: where it is, in HERE's geometry, and what it is. */
class TrafficIncident(
    val id: String,
    /** HERE's `type`: `roadClosure`, `construction`, `congestion`, `plannedEvent`, … */
    val type: String,
    /** `critical`, `major`, `minor`, or null. */
    val criticality: String?,
    val roadClosed: Boolean,
    /** `summary.value`, else `description.value`, or null. */
    val summary: String?,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
)

/**
 * One fetch's answer, for the one box it was asked for (plan §4: never reused for another
 * box, and replaced, not kept, by the next).
 */
class TrafficSnapshot(
    val box: TrafficBox,
    /** `SystemClock.elapsedRealtime()` when it arrived. */
    val receivedAtMs: Long,
    val flow: List<FlowStretch>,
    val incidents: List<TrafficIncident>,
)

/**
 * How the route line is drawn over one run of it (plan §6). [NONE] is "HERE has no data",
 * which is drawn as the route's normal colour and is **not** the same as [FREE].
 */
enum class Congestion { NONE, FREE, SLOW, QUEUING, CLOSED;
    companion object {
        /** Plan §6: slow for jam factor 4 to 8, queuing above 8. */
        fun of(jamFactor: Double, traversability: Traversability): Congestion = when {
            traversability != Traversability.OPEN -> CLOSED
            jamFactor > 8.0 -> QUEUING
            jamFactor >= 4.0 -> SLOW
            else -> FREE
        }
    }
}

/**
 * One run of a route with one congestion level: the route's points [fromIndex] to [toIndex]
 * inclusive, [fromM] to [toM] metres along the route from its first point.
 */
class TrafficRun(
    val fromIndex: Int,
    val toIndex: Int,
    val fromM: Double,
    val toM: Double,
    val congestion: Congestion,
    /** HERE's speed over the run, m/s, or null for [Congestion.NONE] and closed runs without one. */
    val speedMps: Double?,
    /**
     * Seconds HERE's speed takes over the run less BRouter's own time for it, never below 0
     * (plan §6: positive delays only). 0 for [Congestion.NONE].
     */
    val delaySeconds: Double,
    /** The HERE stretch's road name, for the offer's reason ("Queue on I-35E"), or null. */
    val road: String?,
) {
    /** A closed run long enough to be the road closed ([RouteTraffic.MIN_CLOSED_M]). */
    val closes: Boolean get() = congestion == Congestion.CLOSED && toM - fromM >= RouteTraffic.MIN_CLOSED_M
}

/**
 * The traffic laid on one route: every run of it that HERE's data reached, in order and not
 * overlapping. Points in no run have no data. [route] is the route the runs index, so a
 * run is never drawn on a route it was not matched to.
 */
class RouteTraffic(
    val route: Route,
    val runs: List<TrafficRun>,
    /** Incidents that matched the route, closures among them. */
    val incidents: List<TrafficIncident>,
) {
    /** Sum of the delays of the runs ahead of [alongM] metres along [route], the part of a run behind it pro rata. */
    fun delayAheadSeconds(alongM: Double): Double = runs.sumOf { run ->
        when {
            run.toM <= alongM -> 0.0
            run.fromM >= alongM || run.toM <= run.fromM -> run.delaySeconds
            else -> run.delaySeconds * (run.toM - alongM) / (run.toM - run.fromM)
        }
    }

    /**
     * The first closed run wholly or partly ahead of [alongM], or null. Only a run of at least
     * [MIN_CLOSED_M] counts: a piece or two matched to a closure beside the road (a ramp, a
     * managed lane, the other side of a junction) is not the road closed, and must not
     * re-route the vehicle or block its own road ([TrafficNogos.forRoute]).
     */
    fun closureAhead(alongM: Double): TrafficRun? =
        runs.firstOrNull { it.congestion == Congestion.CLOSED && it.toM > alongM && it.closes }

    companion object {
        /** A closed run shorter than this, metres, is not taken as the road closed. */
        const val MIN_CLOSED_M = 30.0
    }
}

/**
 * One BRouter no-go point (plan §7): a circle of [radiusM] metres BRouter must not enter
 * when [weight] is null (a closure), and otherwise charges distance-inside × [weight].
 * Handed to `RoutingContext.nogopoints` as an `OsmNodeNamed`, whose `nogoWeight` of NaN is
 * the hard block.
 */
data class Nogo(val latitude: Double, val longitude: Double, val radiusM: Double, val weight: Double?) {
    val hard: Boolean get() = weight == null
}

/**
 * A faster way round, found because of traffic (plan §7), for the divided turn card and the
 * map's view of both routes.
 */
class TrafficOffer(
    /** Distinct across every offer made, so Take and Keep name this one and no other. */
    val id: Long,
    /** The whole proposed route, from near the vehicle to the destination. */
    val route: Route,
    /** Seconds it saves against the followed route with its traffic delay; positive. */
    val savingSeconds: Int,
    /** Why, in words: "Queue on I-35E", "Closed: I-35E", or "Traffic ahead". */
    val reason: String,
    /** The last point it shares with the followed route before leaving it. */
    val splitLat: Double,
    val splitLon: Double,
) {
    /** "Faster: −7 min". */
    val label: String get() = "Faster: −${Math.round(savingSeconds / 60.0).coerceAtLeast(1)} min"
}

/**
 * What the projected map shows of traffic at one moment (plan §6): the followed route's
 * runs, whether the attribution is owed, the delay in the arrival time, and the offer if
 * one is up. Published by the traffic loop on the main thread; read by the dashboard.
 */
class TrafficDisplay(
    /** The followed route's traffic, or null when there is none to draw. */
    val traffic: RouteTraffic?,
    /** Seconds of traffic delay in the arrival time now shown, 0 when none. */
    val delaySeconds: Int,
    /** An offer, or null. */
    val offer: TrafficOffer?,
    /** The followed route was computed with traffic nogos (plan §7), so HERE shaped it. */
    val routeShaped: Boolean = false,
) {
    /**
     * "Traffic © HERE" is owed whenever a traffic colour is drawn, or traffic is in the time
     * or shaped the route (plan §1, 6.4 a; §6). Free-flowing is drawn in the normal colour.
     */
    val attributed: Boolean
        get() = delaySeconds > 0 || offer != null || routeShaped ||
            traffic?.runs?.any { it.congestion != Congestion.NONE && it.congestion != Congestion.FREE } == true

    companion object {
        const val ATTRIBUTION = "Traffic © HERE"
        val EMPTY = TrafficDisplay(null, 0, null)
    }
}
