package com.jw6ventures.headlink

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * HERE's closures and jams as BRouter no-go points (P9), from a whole snapshot: an
 * incident or a jam off the route still matters, because it is on the roads an alternative
 * might take.
 *
 * - **Closures** are hard ([Nogo.weight] null): an incident that is `roadClosed` or of type
 *   `roadClosure` ([TrafficMatch.closes]), or a span HERE marks `closed` or
 *   `reversibleNotRoutable`.
 * - **Jams** are weighted: spans at [Congestion.SLOW] or [Congestion.QUEUING] with a delay.
 *
 * **Where the points go.** Along the HERE stretch's own shape, evenly, no more than
 * [SPACING_M] apart, each of [RADIUS_M], and none within [END_MARGIN_M] of either end of the
 * shape, where the junctions are, so a cross street passing the end is not charged. The
 * same rules place points on an incident's shape.
 *
 * Two more rules, not in the original placement rule but for its stated aim ("a polyline
 * nogo would charge every road under an overpass"):
 *
 * - **Crossings.** A point that would land within [CLEAR_M] of another HERE road crossing
 *   this one (at more than [TrafficMatch.HEADING_DEG], either way) is slid along its share
 *   of the stretch to a clear place, or dropped if there is none. A jammed street over the
 *   interstate is then never charged on the interstate below. [CLEAR_M] is the point's
 *   radius plus the matching tolerance, the most HERE's shape is expected to be off OSM's.
 *   Only roads HERE sent are known: classes 4 and 5 are not fetched, so a side
 *   street crossing mid-stretch can still be charged, as it could at any junction.
 * - **A closure must block.** When the margins leave no room (a closed stretch under
 *   60 m, or a closed span within 30 m of an end), or no place along it is clear of
 *   crossings, one point still goes on it: at the place with the most room, which on a
 *   short plain stretch is its middle. Blocking a road beside a closure is the safer
 *   mistake than routing into one. Weighted points get no such exception.
 *
 * **What a point cannot tell.** A BRouter circle has no direction. On a divided highway
 * each carriageway is its own HERE stretch and 20 to 50 m from the other, beyond
 * [RADIUS_M]; on an undivided road both directions share the line, so a jam or closure one
 * way also charges or blocks the other.
 *
 * **How heavy.** The points on a span together charge roughly its delay, in BRouter cost:
 * a point charges (metres of road inside its circle) × weight, and a road through the
 * middle of a circle of [RADIUS_M] has 2 × [RADIUS_M] inside it, so each of n points weighs
 * delay × costPerSecond ÷ (n × 2 × [RADIUS_M]). A road passing off-centre has less inside,
 * so this is an upper figure. The delay of a span, which has no BRouter time of its own off
 * the route, is [delaySeconds]. `costPerSecond` is BRouter's cost per second of delay by
 * HERE functional class: [TrafficCost.costPerSecond], measured in P9.5, at the request's own
 * top speed; the default is the default top speed's.
 *
 * **Also in BRouter** (vendored `RoutingContext.calcDistance`): a segment of the OSM way
 * inside several circles is charged for the last one in the list only, not their sum, and
 * OSM segments on an interstate can be several hundred metres long. The calibration should
 * be measured with points placed this way, not worked out from the formula.
 *
 * Pure Kotlin, no Android, no state: call it from any thread.
 */
object TrafficNogos {
    /** [forRoute]'s answer: every point, and how many of them are on the followed route's own line. */
    class RouteNogos(val nogos: List<Nogo>, val onRoute: Int) {
        val hard: Int get() = nogos.count { it.hard }
    }

    /** Points no further apart than this along a stretch, metres. */
    const val SPACING_M = 150.0

    /** Each point's radius, so one carriageway's point does not reach the other or the frontage road. */
    const val RADIUS_M = 8.0

    /** No point this near either end of a HERE shape, where the junctions are. */
    const val END_MARGIN_M = 30.0

    /** How far a point keeps from a HERE road crossing its own: its radius and the matching tolerance. */
    const val CLEAR_M = RADIUS_M + TrafficMatch.NEAR_M

    /**
     * The most points handed to BRouter. `RoutingContext.calcDistance` checks every nogo for
     * every segment the search weighs, so the search slows in proportion to their number.
     * A first guess: P9.5 measures what they cost the search.
     *
     * The cap is reached in practice. The whole 1° Dallas cell P9.0 captured gives about
     * 2,800 points, 1,200 of them hard: 333 closure incidents, most on side streets, and
     * 77 km of HERE spans marked closed or reversible (managed lanes). A 30 km box holds
     * roughly a tenth of that. Which points are kept is in [of].
     */
    const val MAX_NOGOS = 300

    /** [TrafficCost.costPerSecond] at the default top speed: what [of] weighs a second of delay by when not told. */
    private val DEFAULT_COST_PER_SECOND: (Int) -> Double = { functionalClass -> TrafficCost.costPerSecond(functionalClass) }

    /**
     * Consecutive slow or queuing runs of the followed route no further apart than this,
     * metres, are one jam to [forRoute]: the matcher cuts a run wherever the level or the
     * HERE stretch changes, and that is not a junction.
     */
    private const val TOUCH_M = 5.0

    /** How far a point is slid at a time to find a place clear of crossings, metres. */
    private const val SLIDE_STEP_M = 5.0

    /** Route points closer together than this are skipped when measuring how near a nogo is to it. */
    private const val ROUTE_STEP_M = 100.0

    /**
     * The no-go points for [flow] and [incidents], no more than [limit] ([MAX_NOGOS]).
     * [costPerSecond] converts a second of delay into BRouter cost for a HERE functional
     * class, 1 to 5.
     *
     * Points are kept a span (or a closure incident) at a time. With [route], the one being
     * followed, the spans nearest it are kept first, closures before jams at the same
     * distance, then heavier jams before lighter: the jam on the route is what an
     * alternative has to beat, and the roads beside it are where alternatives go, so they
     * must never be the ones the cap drops. Without a route, every closure, then jams by
     * their delay, heaviest first. A jam is kept whole or not at all (part of one would
     * charge part of its delay); a closure may be cut short by the cap, since each of its
     * points blocks on its own.
     */
    @JvmOverloads
    fun of(
        flow: List<FlowStretch>,
        incidents: List<TrafficIncident>,
        route: Route? = null,
        costPerSecond: (functionalClass: Int) -> Double = DEFAULT_COST_PER_SECOND,
        limit: Int = MAX_NOGOS,
    ): List<Nogo> {
        if (flow.isEmpty() && incidents.isEmpty()) return emptyList()
        val lats = ArrayList<DoubleArray>(flow.size + incidents.size)
        val lons = ArrayList<DoubleArray>(flow.size + incidents.size)
        flow.forEach { lats += it.latitudes; lons += it.longitudes }
        incidents.forEach { lats += it.latitudes; lons += it.longitudes }
        val index = ShapeIndex(lats, lons, CLEAR_M)

        val groups = ArrayList<Group>()
        for ((k, stretch) in flow.withIndex()) {
            val length = index.length(k)
            if (length <= 0.0 || stretch.spans.isEmpty()) continue
            val scale = length / stretch.spans.last().toM.coerceAtLeast(1e-9)
            for (span in stretch.spans) {
                val from = span.fromM * scale
                val to = span.toM * scale
                when (Congestion.of(span.jamFactor, span.traversability)) {
                    Congestion.CLOSED -> groups += Group(place(index, k, from, to, true).map { nogo(index, k, it, null) }, 0.0)
                    Congestion.SLOW, Congestion.QUEUING -> {
                        val delay = delaySeconds(span)
                        if (delay <= 0.0) continue
                        val at = place(index, k, from, to, false)
                        if (at.isEmpty()) continue
                        val weight = delay * costPerSecond(stretch.functionalClass) / (at.size * 2.0 * RADIUS_M)
                        if (weight.isFinite() && weight > 0.0) groups += Group(at.map { nogo(index, k, it, weight) }, delay)
                    }
                    else -> {}
                }
            }
        }
        for ((j, incident) in incidents.withIndex()) {
            if (!TrafficMatch.closes(incident)) continue
            val k = flow.size + j
            if (incident.latitudes.isEmpty() || incident.longitudes.isEmpty()) continue
            groups += if (index.length(k) <= 0.0) {
                // A closure given as a single point still blocks there.
                Group(listOf(Nogo(incident.latitudes[0], incident.longitudes[0], RADIUS_M, null)), 0.0)
            } else {
                Group(place(index, k, 0.0, index.length(k), true).map { nogo(index, k, it, null) }, 0.0)
            }
        }

        groups.removeAll { it.points.isEmpty() }
        val order = if (route != null && route.points > 0) {
            val (routeLats, routeLons) = sparse(route)
            // Its ends and middle are near enough for ordering, and a long route times
            // thousands of points would not be.
            groups.forEach { group ->
                val p = group.points
                group.distance = listOf(p.first(), p[p.size / 2], p.last()).minOf { nearest(routeLats, routeLons, it) }
            }
            compareBy<Group> { it.distance }.thenBy { !it.hard }.thenByDescending { it.delay }
        } else {
            compareBy<Group> { !it.hard }.thenByDescending { it.delay }
        }
        val chosen = ArrayList<Nogo>()
        val hard = ArrayList<Nogo>()
        for (group in groups.sortedWith(order)) {
            if (group.hard) {
                for (point in group.points) {
                    if (chosen.size >= limit) break
                    // A closed span and a closure incident on the same road put points on top
                    // of each other; one blocks as well as two.
                    if (hard.none { RouteFollower.distance(it.latitude, it.longitude, point.latitude, point.longitude) < RADIUS_M }) {
                        chosen += point
                        hard += point
                    }
                }
            } else if (chosen.size + group.points.size <= limit) {
                chosen += group.points
            }
        }
        return chosen
    }

    /** [snapshot]'s nogos; see the other [of]. */
    @JvmOverloads
    fun of(
        snapshot: TrafficSnapshot,
        route: Route? = null,
        costPerSecond: (functionalClass: Int) -> Double = DEFAULT_COST_PER_SECOND,
        limit: Int = MAX_NOGOS,
    ): List<Nogo> = of(snapshot.flow, snapshot.incidents, route, costPerSecond, limit)

    /**
     * What every search around the followed route is given while traffic is on
     * (P9.5): [of] over the whole response, ordered by [traffic]'s route, and points on **the
     * route's own line** for what matched it. The traffic loop computes these once per
     * response and per followed route, off the main thread ([TrafficFeed]).
     *
     * **Why points on the route's line too.** HERE's geometry is not OSM's. Along I-35E in
     * Dallas it was 12.7 m off at the 90th percentile and 20.6 m at the 95th
     * (docs/navigation.md, "Live traffic (HERE)"), and an 8 m circle on a line 10 m off the OSM way charges
     * nothing and blocks nothing. What [TrafficMatch] matched to the route is known to be on
     * the route's own road, so:
     *
     * - **Closures** ([Congestion.CLOSED] runs) get hard points on the route's line, placed
     *   as [of] places them (every [SPACING_M] at most, [END_MARGIN_M] clear of each end,
     *   one in the middle of a closure too short for that, slid clear of HERE roads crossing
     *   it), **as well as** the points on HERE's own shape. A hard point that duplicates one
     *   within [RADIUS_M] is left out.
     * - **Jams** (touching [Congestion.SLOW] and [Congestion.QUEUING] runs, as one) get
     *   weighted points on the route's line **in place of** HERE's own points within
     *   [TrafficMatch.NEAR_M] of it, which are the same jam's. BRouter charges one circle per
     *   link, the last it passed (docs/navigation.md, "Live traffic (HERE)"), so two sets on one road
     *   would not add up; they would only cost the search, which slows by about 3 ms a point
     *   on the host. Fewer points, each where the road is, is the better trade. Their weight
     *   is the design's (delay × `costPerSecond` ÷ (points × 16 m)), with the run's own delay
     *   against BRouter's time rather than HERE's free flow, and the class of the HERE
     *   stretch it matched.
     *
     * Points for the route's own closures come first when [limit] bites, then its jams, then
     * [of]'s. In the list handed on, [of]'s come first and the route's own after them, so
     * that on a link both reach the route's own is the circle BRouter keeps.
     */
    fun forRoute(
        flow: List<FlowStretch>,
        incidents: List<TrafficIncident>,
        traffic: RouteTraffic,
        costPerSecond: (functionalClass: Int) -> Double = DEFAULT_COST_PER_SECOND,
        limit: Int = MAX_NOGOS,
    ): RouteNogos {
        val route = traffic.route
        if (flow.isEmpty() && incidents.isEmpty()) return RouteNogos(emptyList(), 0)
        if (route.points < 2 || traffic.runs.isEmpty()) return RouteNogos(of(flow, incidents, route, costPerSecond, limit), 0)
        val lats = ArrayList<DoubleArray>(flow.size + incidents.size)
        val lons = ArrayList<DoubleArray>(flow.size + incidents.size)
        flow.forEach { lats += it.latitudes; lons += it.longitudes }
        incidents.forEach { lats += it.latitudes; lons += it.longitudes }
        val index = ShapeIndex(lats, lons, CLEAR_M)
        val along = TrafficArea.metres(route)
        val line = Polyline(
            DoubleArray(route.points) { index.x(route.longitudes[it]) },
            DoubleArray(route.points) { index.y(route.latitudes[it]) },
            along,
        )

        val hardOwn = ArrayList<Nogo>()
        for (run in traffic.runs) {
            // Only a run long enough to be the road closed: see [RouteTraffic.closureAhead].
            if (!run.closes) continue
            for (at in placeOnLine(index, line, run.fromM, run.toM, true)) hardOwn += pointOn(route, along, at, null)
        }
        val jamOwn = ArrayList<Nogo>()
        val jamLines = ArrayList<Pair<DoubleArray, DoubleArray>>()
        val runs = traffic.runs
        var i = 0
        while (i < runs.size) {
            if (!jam(runs[i])) {
                i++
                continue
            }
            var last = i
            var delay = runs[i].delaySeconds
            while (last + 1 < runs.size && jam(runs[last + 1]) && runs[last + 1].fromM - runs[last].toM <= TOUCH_M) {
                last++
                delay += runs[last].delaySeconds
            }
            val from = runs[i].fromM
            val to = runs[last].toM
            i = last + 1
            if (delay <= 0.0) continue
            val at = placeOnLine(index, line, from, to, false)
            if (at.isEmpty()) continue
            val weight = delay * costPerSecond(classAt(index, flow.size, flow, line, (from + to) / 2.0)) / (at.size * 2.0 * RADIUS_M)
            if (!(weight.isFinite() && weight > 0.0)) continue
            at.forEach { jamOwn += pointOn(route, along, it, weight) }
            jamLines += TrafficMatch.line(route, from, to)
        }

        val own = (hardOwn + jamOwn).take(limit)
        val rest = of(flow, incidents, route, costPerSecond, (limit - own.size).coerceAtLeast(0)).filter { nogo ->
            if (nogo.hard) {
                hardOwn.none { RouteFollower.distance(it.latitude, it.longitude, nogo.latitude, nogo.longitude) < RADIUS_M }
            } else {
                jamLines.none { (la, lo) -> fromLine(la, lo, nogo.latitude, nogo.longitude) <= TrafficMatch.NEAR_M }
            }
        }
        // Weighted before hard, the route's own after HERE's of the same kind: Router keeps
        // weighted points first and hard last, in this order otherwise.
        val ownWeighted = own.filter { !it.hard }
        val ownHard = own.filter { it.hard }
        return RouteNogos(rest.filter { !it.hard } + ownWeighted + rest.filter { it.hard } + ownHard, own.size)
    }

    /** Whether a run of the followed route is part of a jam: slow or queuing. */
    private fun jam(run: TrafficRun): Boolean =
        run.congestion == Congestion.SLOW || run.congestion == Congestion.QUEUING

    /**
     * Metres along [line] where points go between [from] and [to]: [place]'s rule on the
     * route's own line, where there is no HERE shape of its own to leave out of the
     * crossing check.
     */
    private fun placeOnLine(index: ShapeIndex, line: Polyline, from: Double, to: Double, hard: Boolean): List<Double> {
        val lo = max(from + END_MARGIN_M, 0.0)
        val hi = min(to - END_MARGIN_M, line.length)
        val kept = ArrayList<Double>()
        var fallback = Double.NaN
        var fallbackRoom = -1.0
        fun consider(at: Double): Boolean {
            val room = roomOnLine(index, line, at)
            if (room > fallbackRoom) {
                fallbackRoom = room
                fallback = at
            }
            return room >= CLEAR_M
        }
        if (hi >= lo) {
            val count = max(1, ceil((hi - lo) / SPACING_M).toInt())
            val share = (hi - lo) / count
            for (k in 0 until count) {
                val nominal = lo + (k + 0.5) * share
                var slide = 0.0
                search@ while (slide <= share / 2.0 + 1e-9) {
                    for (at in if (slide == 0.0) listOf(nominal) else listOf(nominal + slide, nominal - slide)) {
                        if (consider(at)) {
                            kept += at
                            break@search
                        }
                    }
                    slide += SLIDE_STEP_M
                }
            }
        } else if (hard) {
            val a = from.coerceIn(0.0, line.length)
            val b = to.coerceIn(a, line.length)
            val middle = (a + b) / 2.0
            var slide = 0.0
            while (slide <= (b - a) / 2.0 + 1e-9) {
                if (consider(middle + slide) || consider(middle - slide)) break
                slide += SLIDE_STEP_M
            }
        }
        if (kept.isEmpty() && hard && !fallback.isNaN()) kept += fallback
        return kept
    }

    /** [room] for a place on the route's own line: metres to the nearest HERE road crossing it there. */
    private fun roomOnLine(index: ShapeIndex, line: Polyline, at: Double): Double {
        val x = line.xAt(at)
        val y = line.yAt(at)
        val heading = line.headingAt(at, TrafficMatch.HEADING_WINDOW_M)
        var nearest = Double.MAX_VALUE
        index.near(x, y, CLEAR_M) { other, distance, otherAlong ->
            val angle = TrafficMatch.angle(heading, index.headingAt(other, otherAlong, TrafficMatch.HEADING_WINDOW_M))
            if (min(angle, 180.0 - angle) > TrafficMatch.HEADING_DEG) nearest = min(nearest, distance)
        }
        return nearest
    }

    /**
     * The functional class of the HERE stretch running along [line] at [at] metres: the
     * nearest within [TrafficMatch.NEAR_M] the same way, as [TrafficMatch] chose it. 3 (no
     * link factor) if none is found, which a matched run should never be.
     */
    private fun classAt(index: ShapeIndex, flowCount: Int, flow: List<FlowStretch>, line: Polyline, at: Double): Int {
        val heading = line.headingAt(at, TrafficMatch.HEADING_WINDOW_M)
        var best = -1
        var bestDistance = Double.MAX_VALUE
        index.near(line.xAt(at), line.yAt(at), TrafficMatch.NEAR_M) { shape, distance, shapeAlong ->
            if (shape < flowCount && distance < bestDistance &&
                TrafficMatch.agrees(heading, index.headingAt(shape, shapeAlong, TrafficMatch.HEADING_WINDOW_M))
            ) {
                best = shape
                bestDistance = distance
            }
        }
        return if (best >= 0) flow[best].functionalClass else 3
    }

    /** A point [at] metres along [route], interpolated as [TrafficMatch.line] cuts it. */
    private fun pointOn(route: Route, along: DoubleArray, at: Double, weight: Double?): Nogo {
        val i = TrafficMatch.segmentAt(along, at)
        val length = along[i + 1] - along[i]
        val t = if (length > 0.0) ((at - along[i]) / length).coerceIn(0.0, 1.0) else 0.0
        return Nogo(
            route.latitudes[i] + t * (route.latitudes[i + 1] - route.latitudes[i]),
            route.longitudes[i] + t * (route.longitudes[i + 1] - route.longitudes[i]),
            RADIUS_M, weight,
        )
    }

    /** Metres from a point to the nearest place on the line through [lats], [lons], flat around the point. */
    private fun fromLine(lats: DoubleArray, lons: DoubleArray, lat: Double, lon: Double): Double {
        if (lats.isEmpty()) return Double.MAX_VALUE
        if (lats.size == 1) return RouteFollower.distance(lats[0], lons[0], lat, lon)
        val scaleX = METRES_PER_DEGREE * kotlin.math.cos(Math.toRadians(lat))
        var best = Double.MAX_VALUE
        for (k in 0 until lats.size - 1) {
            val ax = (lons[k] - lon) * scaleX
            val ay = (lats[k] - lat) * METRES_PER_DEGREE
            val dx = (lons[k + 1] - lons[k]) * scaleX
            val dy = (lats[k + 1] - lats[k]) * METRES_PER_DEGREE
            val lengthSq = dx * dx + dy * dy
            val t = if (lengthSq > 0.0) (-(ax * dx + ay * dy) / lengthSq).coerceIn(0.0, 1.0) else 0.0
            best = min(best, kotlin.math.hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    private const val METRES_PER_DEGREE = 6_371_000.0 * Math.PI / 180.0

    /** One span's or one closure's points, kept or dropped together. */
    private class Group(val points: List<Nogo>, val delay: Double) {
        val hard: Boolean get() = points.firstOrNull()?.hard == true
        var distance = 0.0
    }

    /** [route]'s points no nearer each other than [ROUTE_STEP_M], and its last. */
    private fun sparse(route: Route): Pair<DoubleArray, DoubleArray> {
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        for (i in 0 until route.points) {
            val keep = i == 0 || i == route.points - 1 ||
                RouteFollower.distance(lats.last(), lons.last(), route.latitudes[i], route.longitudes[i]) >= ROUTE_STEP_M
            if (keep) {
                lats += route.latitudes[i]
                lons += route.longitudes[i]
            }
        }
        return lats.toDoubleArray() to lons.toDoubleArray()
    }

    /** Metres from [point] to the nearest of the points given: good to about [ROUTE_STEP_M], which is all ordering needs. */
    private fun nearest(lats: DoubleArray, lons: DoubleArray, point: Nogo): Double {
        var best = Double.MAX_VALUE
        for (i in lats.indices) best = min(best, RouteFollower.distance(lats[i], lons[i], point.latitude, point.longitude))
        return best
    }

    /**
     * Seconds [span] costs over driving it at free flow: its length at HERE's speed less its
     * length at HERE's free-flow speed, never below 0, and 0 when either is missing. Off the
     * route there is no BRouter time to compare with, so free flow is the reference.
     */
    fun delaySeconds(span: FlowSpan): Double {
        val given = span.speedMps ?: return 0.0
        val free = span.freeFlowMps ?: return 0.0
        // A slow or queuing span at 0 m/s is a queue that has stopped: taken at walking pace,
        // as [TrafficMatch] takes it, so it is weighed rather than left out.
        val waiting = Congestion.of(span.jamFactor, span.traversability).let { it == Congestion.SLOW || it == Congestion.QUEUING }
        val speed = if (waiting) max(given, TrafficMatch.STOPPED_MPS) else given
        if (speed <= 0.0 || free <= 0.0) return 0.0
        val length = span.toM - span.fromM
        return max(0.0, length / speed - length / free)
    }

    /**
     * Metres along shape [k] where the points for its part [from]..[to] go (see the
     * rules above). [hard] keeps one point where the others would leave none.
     */
    private fun place(index: ShapeIndex, k: Int, from: Double, to: Double, hard: Boolean): List<Double> {
        val length = index.length(k)
        val lo = max(from, END_MARGIN_M)
        val hi = min(to, length - END_MARGIN_M)
        val kept = ArrayList<Double>()
        var fallback = Double.NaN
        var fallbackRoom = -1.0
        if (hi >= lo) {
            val count = max(1, ceil((hi - lo) / SPACING_M).toInt())
            val share = (hi - lo) / count
            for (i in 0 until count) {
                val nominal = lo + (i + 0.5) * share
                // Slid within its own share of the span, nearest the even place first.
                var slide = 0.0
                var placed = false
                while (slide <= share / 2.0 + 1e-9) {
                    for (at in if (slide == 0.0) listOf(nominal) else listOf(nominal + slide, nominal - slide)) {
                        val room = room(index, k, at)
                        if (room > fallbackRoom) {
                            fallbackRoom = room
                            fallback = at
                        }
                        if (room >= CLEAR_M) {
                            kept += at
                            placed = true
                            break
                        }
                    }
                    if (placed) break
                    slide += SLIDE_STEP_M
                }
            }
        } else if (hard) {
            // Margins leave no room: the middle of the closed part, or the roomiest place near it.
            val a = from.coerceIn(0.0, length)
            val b = to.coerceIn(a, length)
            val middle = (a + b) / 2.0
            var slide = 0.0
            while (slide <= (b - a) / 2.0 + 1e-9) {
                for (at in listOf(middle + slide, middle - slide)) {
                    val room = room(index, k, at)
                    if (room > fallbackRoom) {
                        fallbackRoom = room
                        fallback = at
                    }
                }
                if (fallbackRoom >= CLEAR_M) break
                slide += SLIDE_STEP_M
            }
        }
        if (kept.isEmpty() && hard && !fallback.isNaN()) kept += fallback
        return kept
    }

    /**
     * Metres from the place [at] along shape [k] to the nearest other HERE road that crosses
     * it there, up to [CLEAR_M]; [Double.MAX_VALUE] when none is that near. A road running
     * alongside, either way (the next stretch, the other direction of the same road, a
     * frontage road) is not a crossing.
     */
    private fun room(index: ShapeIndex, k: Int, at: Double): Double {
        val shape = index.shape(k)
        val x = shape.xAt(at)
        val y = shape.yAt(at)
        val heading = shape.headingAt(at, TrafficMatch.HEADING_WINDOW_M)
        var nearest = Double.MAX_VALUE
        index.near(x, y, CLEAR_M) { other, distance, otherAlong ->
            if (other == k) return@near
            val angle = TrafficMatch.angle(heading, index.headingAt(other, otherAlong, TrafficMatch.HEADING_WINDOW_M))
            if (min(angle, 180.0 - angle) > TrafficMatch.HEADING_DEG) nearest = min(nearest, distance)
        }
        return nearest
    }

    private fun nogo(index: ShapeIndex, k: Int, at: Double, weight: Double?): Nogo {
        val (lat, lon) = index.latLonAt(k, at)
        return Nogo(lat, lon, RADIUS_M, weight)
    }
}
