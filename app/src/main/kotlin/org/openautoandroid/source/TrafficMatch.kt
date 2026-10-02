package org.openautoandroid.source

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * HERE's roads laid on our route (P9 plan §5), and the delay they add (§6).
 *
 * HERE describes each stretch by its own geometry and the route is BRouter's OSM geometry,
 * so matching is by shape: the route is cut into pieces of at most [PIECE_M], and each
 * piece takes the flow of the closest HERE stretch that passes within [NEAR_M] of its
 * middle **and** runs the same way to within [HEADING_DEG]. Direction is what keeps a jam
 * on one carriageway of a Texas interstate off the other (20 to 50 m away), and heading is
 * also what keeps a cross street on an overpass off the road beneath. Distance keeps the
 * frontage road off. A piece nothing agrees with has no data: [Congestion.NONE], which is
 * drawn as the route's normal colour and is not [Congestion.FREE].
 *
 * The three thresholds are first guesses (plan §12, "Matching quality"), for the lab and a
 * drive to settle; they are named here so that is one edit.
 *
 * Pure Kotlin, no Android, no state: call it from any thread. The traffic loop should call
 * it off the main thread, because it indexes every stretch of the snapshot first. A grid
 * of [CELL_M] squares holds HERE's segments, so each piece looks only at the few that pass
 * near it rather than at all of them.
 */
object TrafficMatch {
    /** Plan §5: a HERE stretch this near a piece of the route may be the road it is on. */
    const val NEAR_M = 15.0

    /** Plan §5: and must run the same way to within this, degrees. */
    const val HEADING_DEG = 30.0

    /** The route is matched in pieces no longer than this, metres. */
    const val PIECE_M = 10.0

    /**
     * A slow or queuing span HERE gives 0 m/s (a queue that has stopped) is taken at this,
     * m/s, walking pace: time it has, but not an infinite one.
     */
    const val STOPPED_MPS = 1.0

    /**
     * Heading is taken over this far each side of the place, on both the route and the
     * stretch, not from one segment: HERE's points have five decimals (about a metre), so
     * a two-metre segment's own heading can be tens of degrees out.
     */
    const val HEADING_WINDOW_M = 10.0

    /** Side of the index's grid squares, metres. */
    internal const val CELL_M = 100.0

    /**
     * [flow] and [incidents] laid on [route]. See [RouteTraffic] for what the runs mean.
     *
     * Runs are made of consecutive pieces on the same HERE stretch at the same
     * [Congestion]; a stretch's `subSegments` ([FlowSpan]s) give each piece the figures of
     * the span it falls in, so one stretch can give a free run and then a queue. Pieces
     * with no data make no run.
     *
     * **Delay** (plan §6): a run's [TrafficRun.delaySeconds] is its length at HERE's speed,
     * less BRouter's own time for the same metres from [Route.pointSeconds], and never less
     * than 0 — on [Congestion.SLOW] and [Congestion.QUEUING] runs only. A free-flowing run
     * has no delay even where HERE's free-flow speed is below BRouter's model: on P9.0's
     * real Dallas routes that difference alone came to 17 and 36 s on empty roads, which
     * would have shown "+1 min traffic" with no traffic at all (docs/p9-2-results.md §3).
     * This departs from the plan's formula, which counts every matched stretch; the
     * orchestrator asked for it (docs/p9-4-5-runtime-results.md §4).
     *
     * **Closures.** A closed run (a span HERE marks `closed` or `reversibleNotRoutable`, or
     * a piece matched by an incident that is `roadClosed` or of type `roadClosure`) has no
     * finite speed, so its speed is null and its delay 0: a closure is not a wait but a
     * road that cannot be driven, and routing handles it ([RouteTraffic.closureAhead],
     * [TrafficNogos]).
     *
     * Every incident is matched the same way (against its own shape), and those that match
     * any piece are [RouteTraffic.incidents], in route order. The rest still count for
     * routing: [TrafficNogos] takes the whole snapshot.
     */
    fun match(route: Route, flow: List<FlowStretch>, incidents: List<TrafficIncident>): RouteTraffic {
        val n = route.points
        if (n < 2 || (flow.isEmpty() && incidents.isEmpty())) return RouteTraffic(route, emptyList(), emptyList())
        val lats = ArrayList<DoubleArray>(flow.size + incidents.size)
        val lons = ArrayList<DoubleArray>(flow.size + incidents.size)
        flow.forEach { lats += it.latitudes; lons += it.longitudes }
        incidents.forEach { lats += it.latitudes; lons += it.longitudes }
        val index = ShapeIndex(lats, lons, NEAR_M)
        val closing = BooleanArray(incidents.size) { closes(incidents[it]) }

        val along = TrafficArea.metres(route)
        val rx = DoubleArray(n) { index.x(route.longitudes[it]) }
        val ry = DoubleArray(n) { index.y(route.latitudes[it]) }
        val line = Polyline(rx, ry, along)
        val clock = RouteClock(route, along)

        val builder = RunBuilder(route, flow, clock)
        val firstPiece = HashMap<Int, Double>()
        for (segment in 0 until n - 1) {
            val length = along[segment + 1] - along[segment]
            val count = max(1, ceil(length / PIECE_M).toInt())
            for (k in 0 until count) {
                val fromM = along[segment] + length * k / count
                val toM = along[segment] + length * (k + 1) / count
                val mid = (fromM + toM) / 2.0
                val x = line.xAt(mid)
                val y = line.yAt(mid)
                val heading = line.headingAt(mid, HEADING_WINDOW_M)
                var best = -1
                var bestDistance = Double.MAX_VALUE
                var bestAlong = 0.0
                var closed = false
                index.near(x, y, NEAR_M) { shape, distance, shapeAlong ->
                    if (!agrees(heading, index.headingAt(shape, shapeAlong, HEADING_WINDOW_M))) return@near
                    if (shape < flow.size) {
                        // A reversible or managed lane HERE calls not routable runs beside the
                        // general lanes, the same way: it says nothing about the road driven,
                        // so it is no data here, not a closure of the interstate. It still
                        // keeps routes off itself as HERE's own hard points ([TrafficNogos.of]).
                        val here = spanAt(flow[shape], shapeAlong, index.length(shape))
                        if (here?.traversability == Traversability.REVERSIBLE_NOT_ROUTABLE) return@near
                        if (distance < bestDistance) {
                            best = shape
                            bestDistance = distance
                            bestAlong = shapeAlong
                        }
                    } else {
                        val incident = shape - flow.size
                        firstPiece.putIfAbsent(incident, fromM)
                        if (closing[incident]) closed = true
                    }
                }
                val span = if (best >= 0) spanAt(flow[best], bestAlong, index.length(best)) else null
                builder.piece(segment, fromM, toM, best, span, closed)
            }
        }
        val matched = firstPiece.entries.sortedBy { it.value }.map { incidents[it.key] }
        return RouteTraffic(route, builder.finish(), matched)
    }

    /** [snapshot]'s flow and incidents laid on [route]; see the other [match]. */
    fun match(route: Route, snapshot: TrafficSnapshot): RouteTraffic = match(route, snapshot.flow, snapshot.incidents)

    /**
     * The part of [route] from [fromM] to [toM] metres along, cut exactly there, as
     * latitudes and longitudes: a run's own line, for drawing it when its ends fall between
     * two of the route's points. [TrafficRun.fromIndex] to [TrafficRun.toIndex] may reach a
     * little past each end; this does not.
     */
    fun line(route: Route, fromM: Double, toM: Double): Pair<DoubleArray, DoubleArray> {
        val n = route.points
        if (n == 0) return DoubleArray(0) to DoubleArray(0)
        if (n == 1) return doubleArrayOf(route.latitudes[0]) to doubleArrayOf(route.longitudes[0])
        val along = TrafficArea.metres(route)
        val a = fromM.coerceIn(0.0, along[n - 1])
        val b = toM.coerceIn(a, along[n - 1])
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        fun at(metres: Double) {
            val i = segmentAt(along, metres)
            val length = along[i + 1] - along[i]
            val t = if (length > 0.0) ((metres - along[i]) / length).coerceIn(0.0, 1.0) else 0.0
            lats += route.latitudes[i] + t * (route.latitudes[i + 1] - route.latitudes[i])
            lons += route.longitudes[i] + t * (route.longitudes[i + 1] - route.longitudes[i])
        }
        at(a)
        for (i in 0 until n) if (along[i] > a && along[i] < b) {
            lats += route.latitudes[i]
            lons += route.longitudes[i]
        }
        at(b)
        return lats.toDoubleArray() to lons.toDoubleArray()
    }

    /** An incident that means the road cannot be driven: `roadClosed`, or of type `roadClosure`. */
    fun closes(incident: TrafficIncident): Boolean = incident.roadClosed || incident.type == "roadClosure"

    /**
     * The span of [stretch] at [alongM] metres along its drawn shape, [shapeM] long. Spans
     * are laid by HERE's own lengths, which differ from the drawn shape's by rounding, so the
     * place is scaled from one to the other first.
     */
    internal fun spanAt(stretch: FlowStretch, alongM: Double, shapeM: Double): FlowSpan? {
        val spans = stretch.spans
        if (spans.isEmpty()) return null
        val total = spans.last().toM
        val at = if (shapeM > 0.0) alongM * total / shapeM else 0.0
        return spans.firstOrNull { at < it.toM } ?: spans.last()
    }

    /** Whether two headings, degrees, are within [HEADING_DEG] of each other, the same way. */
    internal fun agrees(a: Double, b: Double): Boolean = angle(a, b) <= HEADING_DEG

    /** The angle between two headings, 0 to 180 degrees. */
    internal fun angle(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /** The segment (by its first point) containing [metres] along [along], clamped. */
    internal fun segmentAt(along: DoubleArray, metres: Double): Int {
        var lo = 0
        var hi = along.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (along[mid] <= metres) lo = mid else hi = mid
        }
        return lo.coerceAtMost(along.size - 2).coerceAtLeast(0)
    }

    /** BRouter's own time at a place along the route, interpolated as [RouteFollower] does. */
    private class RouteClock(private val route: Route, private val along: DoubleArray) {
        private val total = along.last()

        fun secondsAt(metres: Double): Double {
            if (total <= 0.0) return 0.0
            val times = route.pointSeconds
            if (times.size != along.size) return route.seconds * (metres / total).coerceIn(0.0, 1.0)
            val i = segmentAt(along, metres)
            val length = along[i + 1] - along[i]
            val t = if (length > 0.0) ((metres - along[i]) / length).coerceIn(0.0, 1.0) else 0.0
            return times[i] + t * (times[i + 1] - times[i])
        }
    }

    /** Gathers pieces, in route order, into runs. */
    private class RunBuilder(private val route: Route, private val flow: List<FlowStretch>, private val clock: RouteClock) {
        private val runs = ArrayList<TrafficRun>()
        private var open = false
        private var level = Congestion.NONE
        private var stretch = -1
        private var fromSegment = 0
        private var toSegment = 0
        private var fromM = 0.0
        private var toM = 0.0
        private var hereSeconds = 0.0
        private var brouterSeconds = 0.0

        fun piece(segment: Int, from: Double, to: Double, stretchIndex: Int, span: FlowSpan?, closed: Boolean) {
            val congestion = when {
                closed -> Congestion.CLOSED
                span != null -> Congestion.of(span.jamFactor, span.traversability)
                else -> Congestion.NONE
            }
            if (open && (congestion != level || stretchIndex != stretch)) close()
            if (congestion == Congestion.NONE) return
            if (!open) {
                open = true
                level = congestion
                stretch = stretchIndex
                fromSegment = segment
                fromM = from
                hereSeconds = 0.0
                brouterSeconds = 0.0
            }
            toSegment = segment
            toM = to
            val own = clock.secondsAt(to) - clock.secondsAt(from)
            brouterSeconds += own
            val speed = span?.speedMps
            val waiting = congestion == Congestion.SLOW || congestion == Congestion.QUEUING
            // An open span without a speed (not seen, but optional in HERE's schema) says
            // nothing about time: it takes BRouter's. A slow or queuing one at 0 m/s is a
            // queue that has stopped, not no data: it is taken at [STOPPED_MPS], so it adds
            // its delay rather than none.
            hereSeconds += when {
                speed != null && waiting -> (to - from) / max(speed, STOPPED_MPS)
                speed != null && speed > 0.0 -> (to - from) / speed
                else -> own
            }
        }

        fun finish(): List<TrafficRun> {
            if (open) close()
            return runs
        }

        private fun close() {
            open = false
            val closedRun = level == Congestion.CLOSED
            val length = toM - fromM
            runs += TrafficRun(
                fromIndex = fromSegment,
                toIndex = (toSegment + 1).coerceAtMost(route.points - 1),
                fromM = fromM,
                toM = toM,
                congestion = level,
                speedMps = if (closedRun || hereSeconds <= 0.0) null else length / hereSeconds,
                // Only a slow or queuing run is a wait (see [match]): free flow slower than
                // BRouter's model is the model's error, not traffic.
                delaySeconds = if (level == Congestion.SLOW || level == Congestion.QUEUING) max(0.0, hereSeconds - brouterSeconds) else 0.0,
                road = if (stretch >= 0) flow[stretch].description else null,
            )
        }
    }
}

/**
 * A line in the flat metres of a [ShapeIndex], with its metres along: where a place along it
 * is, and which way it runs there.
 */
internal class Polyline(val x: DoubleArray, val y: DoubleArray, val along: DoubleArray) {
    val length: Double get() = if (along.isEmpty()) 0.0 else along.last()

    fun xAt(metres: Double): Double = at(metres, x)

    fun yAt(metres: Double): Double = at(metres, y)

    /** Degrees clockwise from north, from [window] metres before [metres] to as far after, clamped to the line. */
    fun headingAt(metres: Double, window: Double): Double {
        if (x.size < 2) return 0.0
        val a = (metres - window).coerceIn(0.0, length)
        val b = (metres + window).coerceIn(0.0, length)
        val dx = xAt(b) - xAt(a)
        val dy = yAt(b) - yAt(a)
        if (dx == 0.0 && dy == 0.0) {
            val i = TrafficMatch.segmentAt(along, metres)
            return (Math.toDegrees(atan2(x[i + 1] - x[i], y[i + 1] - y[i])) + 360.0) % 360.0
        }
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    private fun at(metres: Double, values: DoubleArray): Double {
        if (values.size == 1) return values[0]
        val i = TrafficMatch.segmentAt(along, metres)
        val length = along[i + 1] - along[i]
        val t = if (length > 0.0) ((metres - along[i]) / length).coerceIn(0.0, 1.0) else 0.0
        return values[i] + t * (values[i + 1] - values[i])
    }
}

/**
 * HERE's shapes (flow stretches, incidents) in flat metres around their middle, with every
 * segment filed in a grid of [TrafficMatch.CELL_M] squares, so a question about one place
 * looks only at the segments near it. Shared by [TrafficMatch] and [TrafficNogos].
 *
 * One flat projection for the whole snapshot: a HERE box is at most 1° across, so a metre
 * anywhere in it is within about half a percent of a metre at its middle — 15 m becomes at
 * worst 15.1 m.
 *
 * A segment is filed in every square its bounds, grown by [reachM], touch; [near] may then
 * be asked for anything up to [reachM] away.
 */
internal class ShapeIndex(lats: List<DoubleArray>, lons: List<DoubleArray>, private val reachM: Double) {
    private val originLat: Double
    private val originLon: Double
    private val scaleX: Double
    private val shapes: List<Polyline>
    /** Grid square key to the segments filed there, each `shape shl 32 or segment`. */
    private val grid = HashMap<Long, LongArrayList>()

    init {
        var south = Double.MAX_VALUE
        var north = -Double.MAX_VALUE
        var west = Double.MAX_VALUE
        var east = -Double.MAX_VALUE
        for (k in lats.indices) {
            for (v in lats[k]) { if (v < south) south = v; if (v > north) north = v }
            for (v in lons[k]) { if (v < west) west = v; if (v > east) east = v }
        }
        originLat = if (south <= north) (south + north) / 2.0 else 0.0
        originLon = if (west <= east) (west + east) / 2.0 else 0.0
        scaleX = METRES_PER_DEGREE * cos(originLat * PI / 180.0)
        shapes = lats.indices.map { k ->
            val count = minOf(lats[k].size, lons[k].size)
            val xs = DoubleArray(count) { x(lons[k][it]) }
            val ys = DoubleArray(count) { y(lats[k][it]) }
            val along = DoubleArray(count)
            for (i in 1 until count) along[i] = along[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            Polyline(xs, ys, along)
        }
        for ((k, shape) in shapes.withIndex()) {
            for (i in 0 until shape.x.size - 1) {
                val x0 = cell(minOf(shape.x[i], shape.x[i + 1]) - reachM)
                val x1 = cell(maxOf(shape.x[i], shape.x[i + 1]) + reachM)
                val y0 = cell(minOf(shape.y[i], shape.y[i + 1]) - reachM)
                val y1 = cell(maxOf(shape.y[i], shape.y[i + 1]) + reachM)
                val entry = (k.toLong() shl 32) or i.toLong()
                for (cx in x0..x1) for (cy in y0..y1) grid.getOrPut(key(cx, cy)) { LongArrayList() }.add(entry)
            }
        }
    }

    val size: Int get() = shapes.size

    fun x(lon: Double): Double = (lon - originLon) * scaleX

    fun y(lat: Double): Double = (lat - originLat) * METRES_PER_DEGREE

    fun shape(k: Int): Polyline = shapes[k]

    /** Length of shape [k]'s drawn line, metres. */
    fun length(k: Int): Double = shapes[k].length

    fun headingAt(k: Int, metres: Double, window: Double): Double = shapes[k].headingAt(metres, window)

    /** Latitude and longitude of the place [metres] along shape [k]. */
    fun latLonAt(k: Int, metres: Double): Pair<Double, Double> {
        val shape = shapes[k]
        return (originLat + shape.yAt(metres) / METRES_PER_DEGREE) to (originLon + shape.xAt(metres) / scaleX)
    }

    /**
     * Each shape with a segment within [maxM] (at most [reachM]) of ([x], [y]), once, with
     * its nearest distance and the metres along it of the nearest place.
     */
    fun near(x: Double, y: Double, maxM: Double, found: (shape: Int, distance: Double, along: Double) -> Unit) {
        val entries = grid[key(cell(x), cell(y))] ?: return
        var bestShape = -1
        var bestDistance = 0.0
        var bestAlong = 0.0
        // Entries are filed shape by shape, segment by segment, so one shape's are together.
        for (j in 0 until entries.size) {
            val entry = entries[j]
            val k = (entry ushr 32).toInt()
            val i = (entry and 0xFFFFFFFFL).toInt()
            if (k != bestShape && bestShape >= 0) {
                if (bestDistance <= maxM) found(bestShape, bestDistance, bestAlong)
                bestShape = -1
            }
            val shape = shapes[k]
            val ax = shape.x[i]
            val ay = shape.y[i]
            val dx = shape.x[i + 1] - ax
            val dy = shape.y[i + 1] - ay
            val lengthSq = dx * dx + dy * dy
            val t = if (lengthSq > 0.0) (((x - ax) * dx + (y - ay) * dy) / lengthSq).coerceIn(0.0, 1.0) else 0.0
            val distance = hypot(ax + t * dx - x, ay + t * dy - y)
            if (bestShape < 0 || distance < bestDistance) {
                bestShape = k
                bestDistance = distance
                bestAlong = shape.along[i] + t * (shape.along[i + 1] - shape.along[i])
            }
        }
        if (bestShape >= 0 && bestDistance <= maxM) found(bestShape, bestDistance, bestAlong)
    }

    private fun cell(v: Double): Int = floor(v / TrafficMatch.CELL_M).toInt()

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    private companion object {
        const val METRES_PER_DEGREE = 6_371_000.0 * PI / 180.0
    }
}

/** A growable list of longs, so the grid does not box a million entries. */
internal class LongArrayList {
    private var values = LongArray(4)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    operator fun get(index: Int): Long = values[index]
}
