package org.openautoandroid.source

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * One stretch of the route line ahead drawn in a traffic colour (P9.3, docs/p9-plan.md §6):
 * its level, its points, and where it lies along the route. Built by [TrafficLines], drawn
 * by [MapEngine] as one `PathLayer` over the route line.
 */
class TrafficPiece(
    val congestion: Congestion,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    /** Metres along the route where the piece begins and ends. */
    val fromM: Double,
    val toM: Double,
)

/**
 * Which parts of the route line ahead are drawn in a traffic colour, and when that has to
 * change (P9.3). Pure Kotlin, so tools/jni-host-test checks the splitting, the cut at the
 * vehicle and the colours without a GL context; [MapEngine] owns the only instance and calls
 * it from the scene thread.
 *
 * **What is drawn.** Only [Congestion.SLOW], [Congestion.QUEUING] and [Congestion.CLOSED]
 * runs get a piece. Free-flowing and no-data runs are left to the route line itself, in its
 * normal colour (plan §6 and §12: "no data" is never drawn as free-flowing, and free-flowing
 * is not a colour of its own). Adjacent runs of one level are merged, and at most
 * [MAX_PIECES] are drawn, nearest first, so the layer count is bounded whatever HERE sends.
 *
 * **Only on the route it was matched to.** A [RouteTraffic] whose route is not the one the
 * map is drawing, by identity, draws nothing: a match made for the last route is never laid
 * over a new one, where its metres would mean different roads.
 *
 * **Only ahead of the vehicle.** Each piece starts no earlier than the vehicle's place on
 * the route, so the line behind it is the plain route line, as before P9. Moving that cut is
 * the only reason to rebuild while the traffic is unchanged, and it is moved in steps of
 * [CUT_STEP_M], and only when a piece is actually reached: driving towards a jam rebuilds
 * nothing; driving through one rebuilds its layers every [CUT_STEP_M]. So a jam's colour
 * may linger up to that far behind the marker, and never goes missing ahead of it.
 */
class TrafficLines {
    /** The route [along] was measured for. */
    private var route: Route? = null

    /** Metres from the route's start to each of its points. */
    private var along = DoubleArray(0)

    /** The traffic the pieces on show were made from, or null. */
    private var traffic: RouteTraffic? = null

    /** Metres along the route where the pieces on show were cut; -1 before the first fix. */
    private var cutM = -1.0
    private var lastLatitude = Double.NaN
    private var lastLongitude = Double.NaN

    /** The pieces on show. */
    var pieces: List<TrafficPiece> = emptyList()
        private set

    // Counters for the map's report line; never a place.
    var rebuilds = 0L
        private set
    var piecesMax = 0
        private set

    /** Traffic handed over for a route other than the one drawn, and so not drawn. */
    var stale = 0L
        private set

    /** Pieces left out by [MAX_PIECES] at the last rebuild. */
    var dropped = 0
        private set
    private var staleSeen: RouteTraffic? = null

    /**
     * The pieces to draw now, or null when they are the ones already on show. [drawn] is the
     * route on the map; [latitude] and [longitude] the position it follows, or null.
     */
    fun update(traffic: RouteTraffic?, drawn: Route?, latitude: Double?, longitude: Double?): List<TrafficPiece>? {
        val usable = traffic?.takeIf { drawn != null && it.route === drawn && drawn.points >= 2 }
        if (traffic != null && usable == null && traffic !== staleSeen) {
            stale++
            staleSeen = traffic
        }
        if (usable == null) {
            this.traffic = null
            if (pieces.isEmpty()) return null
            return publish(emptyList(), 0)
        }
        if (usable.route !== route) {
            route = usable.route
            along = cumulative(usable.route)
            cutM = -1.0
            lastLatitude = Double.NaN
            lastLongitude = Double.NaN
        }
        val fresh = usable !== this.traffic
        this.traffic = usable
        var cut = cutM
        if (latitude != null && longitude != null && (latitude != lastLatitude || longitude != lastLongitude)) {
            lastLatitude = latitude
            lastLongitude = longitude
            snap(usable.route, along, latitude, longitude, cutM)?.let { cut = it }
        }
        if (!fresh) {
            if (cutM < 0.0 && cut < 0.0) return null
            val forward = cut - cutM
            // Behind the old cut by more than a step is a new start on this route (a jump,
            // or the first fix), which may bring back pieces the old cut had dropped.
            if (forward > -CUT_STEP_M && forward < CUT_STEP_M) return null
            // Towards a jam, nothing drawn changes until the cut reaches it.
            val reaches = forward < 0.0 || pieces.firstOrNull()?.let { it.fromM < cut } == true
            if (!reaches) {
                cutM = cut
                return null
            }
        }
        cutM = cut
        val (made, left) = pieces(usable, along, maxOf(cut, 0.0))
        return publish(made, left)
    }

    private fun publish(made: List<TrafficPiece>, left: Int): List<TrafficPiece> {
        pieces = made
        dropped = left
        rebuilds++
        piecesMax = maxOf(piecesMax, made.size)
        return made
    }

    companion object {
        /** The most pieces drawn at once; each is two layers (edge and line). */
        const val MAX_PIECES = 8

        /** Runs of one level this close together along the route are drawn as one piece. */
        const val MERGE_GAP_M = 5.0

        /** How far the vehicle moves along a coloured stretch before its piece is cut again. */
        const val CUT_STEP_M = 50.0

        /** A piece shorter than this is not worth a layer. */
        const val MIN_PIECE_M = 1.0

        /** A fix this close to the route is on it, as [RouteFollower.ON_ROUTE_M] says. */
        private const val ON_ROUTE_M = 40.0

        /** Further than this from the whole route, the fix says nothing about where on it. */
        private const val OFF_ROUTE_M = 250.0
        private const val BACK_M = 50.0
        private const val AHEAD_M = 1_000.0
        private const val METRES_PER_DEGREE = 6_371_000.0 * PI / 180.0

        /**
         * Slow (jam factor 4 to 8, plan §6): amber. Brighter and more orange than either
         * theme's arterial yellow (`#f7d57e` day, `#c9b583` night), and nowhere near the
         * route's cyan or the alternatives' grey.
         */
        const val SLOW_COLOUR = 0xFFF59A00.toInt()

        /**
         * Queuing (above 8): red. Deliberately red, as every traffic map has taught, which
         * puts it near both themes' motorway red; [EDGE_COLOUR] is what tells it apart where
         * the route runs along a motorway. Light enough for 3:1 on the night ground.
         */
        const val QUEUING_COLOUR = 0xFFF2362B.toInt()

        /** Closed: dark red dashes. */
        const val CLOSED_COLOUR = 0xFF7A0A0A.toInt()

        /**
         * Between a closure's dashes: near-white, so the dashes read as a broken line on the
         * night ground, where dark red alone is under 2:1, and as a barrier's red and white
         * on the day ground.
         */
        const val CLOSED_GAP_COLOUR = 0xFFF4EFEA.toInt()

        /**
         * The edge under every traffic piece, in both palettes: near-black, a casing's width
         * wider than the line. It gives amber its edge on the day theme's white roads, and
         * separates a red piece from a red motorway under it.
         */
        const val EDGE_COLOUR = 0xFF140A08.toInt()

        /** Dash length of a closure, pixels at the map's 240 dpi: about two line widths. */
        const val CLOSED_DASH = 16

        /** The colour a level is drawn in over the route line, or null for the route's own. */
        @JvmStatic
        fun colour(congestion: Congestion): Int? = when (congestion) {
            Congestion.NONE, Congestion.FREE -> null
            Congestion.SLOW -> SLOW_COLOUR
            Congestion.QUEUING -> QUEUING_COLOUR
            Congestion.CLOSED -> CLOSED_COLOUR
        }

        /** Whether a level is drawn dashed. */
        @JvmStatic
        fun dashed(congestion: Congestion): Boolean = congestion == Congestion.CLOSED

        /**
         * The pieces of [traffic] ahead of [cutM] metres along its route, nearest first, and
         * how many [MAX_PIECES] left out. [along] is [cumulative] of the route.
         */
        @JvmStatic
        fun pieces(traffic: RouteTraffic, along: DoubleArray, cutM: Double): Pair<List<TrafficPiece>, Int> {
            val route = traffic.route
            if (route.points < 2 || along.size != route.points) return emptyList<TrafficPiece>() to 0
            val total = along.last()
            val runs = traffic.runs
                .filter { colour(it.congestion) != null && it.toM > cutM && it.toM > it.fromM }
                .sortedBy { it.fromM }
            val spans = ArrayList<Triple<Congestion, Double, Double>>()
            for (run in runs) {
                val from = maxOf(run.fromM, cutM, 0.0)
                val to = minOf(run.toM, total)
                if (to <= from) continue
                val last = spans.lastOrNull()
                if (last != null && last.first == run.congestion && from - last.third <= MERGE_GAP_M) {
                    spans[spans.size - 1] = Triple(last.first, last.second, maxOf(last.third, to))
                } else {
                    spans += Triple(run.congestion, from, to)
                }
            }
            val kept = spans.filter { it.third - it.second >= MIN_PIECE_M }
            val made = kept.take(MAX_PIECES).map { (congestion, from, to) ->
                val (lats, lons) = geometry(route, along, from, to)
                TrafficPiece(congestion, lats, lons, from, to)
            }
            return made to (kept.size - made.size)
        }

        /** Metres from [route]'s start to each point, as [RouteFollower] measures them. */
        @JvmStatic
        fun cumulative(route: Route): DoubleArray {
            val along = DoubleArray(route.points)
            for (i in 1 until route.points) {
                along[i] = along[i - 1] + RouteFollower.distance(
                    route.latitudes[i - 1], route.longitudes[i - 1], route.latitudes[i], route.longitudes[i],
                )
            }
            return along
        }

        /**
         * The points of [route] from [fromM] to [toM] metres along it: both ends interpolated
         * onto their segments, and every point of the route between them.
         */
        @JvmStatic
        fun geometry(route: Route, along: DoubleArray, fromM: Double, toM: Double): Pair<DoubleArray, DoubleArray> {
            val lats = ArrayList<Double>()
            val lons = ArrayList<Double>()
            fun add(lat: Double, lon: Double) {
                if (lats.isNotEmpty() && lats.last() == lat && lons.last() == lon) return
                lats += lat
                lons += lon
            }
            val first = segmentAt(along, fromM)
            val last = segmentAt(along, toM)
            point(route, along, first, fromM).let { add(it.first, it.second) }
            for (i in first + 1..last) add(route.latitudes[i], route.longitudes[i])
            point(route, along, last, toM).let { add(it.first, it.second) }
            return lats.toDoubleArray() to lons.toDoubleArray()
        }

        /** The point [metres] along segment [i] (its first point) of [route]. */
        private fun point(route: Route, along: DoubleArray, i: Int, metres: Double): Pair<Double, Double> {
            val length = along[i + 1] - along[i]
            val t = if (length > 0.0) ((metres - along[i]) / length).coerceIn(0.0, 1.0) else 0.0
            return (route.latitudes[i] + t * (route.latitudes[i + 1] - route.latitudes[i])) to
                (route.longitudes[i] + t * (route.longitudes[i + 1] - route.longitudes[i]))
        }

        /** The segment (by its first point) that [metres] along falls in, clamped to the route. */
        private fun segmentAt(along: DoubleArray, metres: Double): Int {
            var lo = 0
            var hi = along.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (along[mid] <= metres) lo = mid else hi = mid
            }
            return lo.coerceIn(0, maxOf(0, along.size - 2))
        }

        /**
         * Metres along [route] to the nearest place on it to a fix, or null when the fix is
         * nowhere near it. Near [hintM] first (the last cut), as [RouteFollower] searches, and
         * the whole route when that finds nothing on the route.
         */
        @JvmStatic
        fun snap(route: Route, along: DoubleArray, latitude: Double, longitude: Double, hintM: Double): Double? {
            val n = route.points
            if (n < 2) return null
            if (hintM >= 0.0) {
                val near = nearest(route, along, latitude, longitude, segmentAt(along, hintM - BACK_M), segmentAt(along, hintM + AHEAD_M))
                if (near.first <= ON_ROUTE_M) return near.second
            }
            val whole = nearest(route, along, latitude, longitude, 0, n - 2)
            return whole.second.takeIf { whole.first <= OFF_ROUTE_M }
        }

        /** Offset from, and metres along, the nearest place on segments [first]..[last]. */
        private fun nearest(route: Route, along: DoubleArray, latitude: Double, longitude: Double, first: Int, last: Int): Pair<Double, Double> {
            val scaleX = METRES_PER_DEGREE * cos(latitude * PI / 180.0)
            var bestOffset = Double.MAX_VALUE
            var bestAlong = 0.0
            for (i in first..last) {
                val ax = (route.longitudes[i] - longitude) * scaleX
                val ay = (route.latitudes[i] - latitude) * METRES_PER_DEGREE
                val dx = (route.longitudes[i + 1] - longitude) * scaleX - ax
                val dy = (route.latitudes[i + 1] - latitude) * METRES_PER_DEGREE - ay
                val lengthSq = dx * dx + dy * dy
                val t = if (lengthSq > 0.0) (-(ax * dx + ay * dy) / lengthSq).coerceIn(0.0, 1.0) else 0.0
                val offset = hypot(ax + t * dx, ay + t * dy)
                if (offset < bestOffset) {
                    bestOffset = offset
                    bestAlong = along[i] + t * (along[i + 1] - along[i])
                }
            }
            return bestOffset to bestAlong
        }

        /** The index of [route]'s point nearest a fix: where "the rest of the route" starts. */
        @JvmStatic
        fun nearestIndex(route: Route, latitude: Double, longitude: Double): Int {
            var best = 0
            var nearest = Double.MAX_VALUE
            for (i in 0 until route.points) {
                val d = RouteFollower.distance(route.latitudes[i], route.longitudes[i], latitude, longitude)
                if (d < nearest) {
                    nearest = d
                    best = i
                }
            }
            return best
        }
    }
}
