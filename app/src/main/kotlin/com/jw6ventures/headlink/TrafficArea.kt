package com.jw6ventures.headlink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The area to ask HERE about (P9): the box around the route from the vehicle to
 * [lookAheadKm][boxes] further along it, or to the destination if that is nearer, padded by
 * [PAD_M] on every side.
 *
 * Pure Kotlin over [Route], so the host tests load it. "Metres along" is measured the way
 * [RouteFollower] measures it (the same flat distance, point to point), so the traffic loop
 * can pass a [FollowResult.alongM] straight in.
 *
 * **HERE's size limit.** A box wider or taller than 1° is refused with `400` and code
 * `E608006` (P9.0). The 30 km default never comes near it, and neither does 50 km on a
 * diagonal in Texas (at most 54 km across with the padding, where 1° of longitude is 94 to
 * 96 km), but nothing here assumes that: [boxes] splits any box that would be too big into
 * cells no bigger than [MAX_SPAN_DEG], and keeps only the cells the padded route passes
 * through. Each cell is one flow and one incidents request.
 */
object TrafficArea {
    /** Padding on every side of the route ahead. */
    const val PAD_M = 2_000.0

    /** HERE's box-size limit, degrees of latitude and of longitude (P9.0, `E608006`). */
    const val HERE_LIMIT_DEG = 1.0

    /**
     * The most a returned box spans either way: a thousandth of a degree under HERE's limit,
     * so the five decimals of [TrafficBox.query] can never round a box over it.
     */
    const val MAX_SPAN_DEG = 0.999

    /** The Look ahead setting's default. */
    const val DEFAULT_LOOK_AHEAD_KM = 30.0

    private const val METRES_PER_DEGREE = 6_371_000.0 * PI / 180.0

    /**
     * Metres from [route]'s first point to each of its points, as [RouteFollower] measures
     * them. The traffic runs ([TrafficMatch]) and the boxes use the same figures.
     */
    fun metres(route: Route): DoubleArray {
        val along = DoubleArray(route.points)
        for (i in 1 until route.points) {
            along[i] = along[i - 1] + RouteFollower.distance(
                route.latitudes[i - 1], route.longitudes[i - 1], route.latitudes[i], route.longitudes[i],
            )
        }
        return along
    }

    /**
     * The boxes to fetch for [route] from [alongM] metres along it to [lookAheadKm] further:
     * one box in every case the design expects, several only when one would be bigger than
     * HERE allows. Empty for a route with no points.
     */
    fun boxes(route: Route, alongM: Double, lookAheadKm: Double = DEFAULT_LOOK_AHEAD_KM): List<TrafficBox> {
        val (lats, lons) = ahead(route, alongM, lookAheadKm) ?: return emptyList()
        val box = pad(bounds(lats, lons))
        val cells = cells(box)
        if (cells.size == 1) return cells
        // Only the cells the padded route passes through: a long diagonal would otherwise
        // ask for the empty corners too. Each segment is taken as its own padded box.
        val pieces = (0 until lats.size - 1).map { i ->
            pad(TrafficBox(min(lats[i], lats[i + 1]), min(lons[i], lons[i + 1]), max(lats[i], lats[i + 1]), max(lons[i], lons[i + 1])))
        }.ifEmpty { listOf(box) }
        return cells.filter { cell -> pieces.any { overlaps(it, cell) } }
    }

    /**
     * The single padded box around the stretch ahead, before any splitting, or null for a
     * route with no points. [boxes] is what to fetch; this is for the status line and tests.
     */
    fun around(route: Route, alongM: Double, lookAheadKm: Double = DEFAULT_LOOK_AHEAD_KM): TrafficBox? {
        val (lats, lons) = ahead(route, alongM, lookAheadKm) ?: return null
        return pad(bounds(lats, lons))
    }

    /**
     * [box] cut into an even grid of cells, none spanning more than [MAX_SPAN_DEG] either
     * way; the box itself, in a list of one, when it already fits.
     */
    fun cells(box: TrafficBox): List<TrafficBox> {
        val columns = max(1, ceil((box.east - box.west) / MAX_SPAN_DEG).toInt())
        val rows = max(1, ceil((box.north - box.south) / MAX_SPAN_DEG).toInt())
        if (columns == 1 && rows == 1) return listOf(box)
        val width = (box.east - box.west) / columns
        val height = (box.north - box.south) / rows
        val cells = ArrayList<TrafficBox>(columns * rows)
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                // The last row and column end exactly on the box's edge, not a rounding short.
                cells += TrafficBox(
                    box.south + r * height,
                    box.west + c * width,
                    if (r == rows - 1) box.north else box.south + (r + 1) * height,
                    if (c == columns - 1) box.east else box.west + (c + 1) * width,
                )
            }
        }
        return cells
    }

    /** Whether HERE would take [box]: in range, and no more than its limit either way once written as [TrafficBox.query]. */
    fun acceptable(box: TrafficBox): Boolean {
        val parts = box.query().removePrefix("bbox:").split(',').map { it.toDouble() }
        val (west, south, east, north) = parts
        return south >= -90.0 && north <= 90.0 && west >= -180.0 && east <= 180.0 &&
            south <= north && west <= east && north - south <= HERE_LIMIT_DEG && east - west <= HERE_LIMIT_DEG
    }

    /**
     * Whether [boxes] hold every point of [route] from [alongM] to [aheadM] metres further
     * (or to the destination). After a re-route the traffic loop can ask this of the boxes
     * its last fetch was for: the new route's traffic is only known where they cover it, so
     * when they do not cover, say, the next half of the look-ahead, it is time to fetch
     * again rather than wait for the next refresh.
     */
    fun covers(boxes: List<TrafficBox>, route: Route, alongM: Double, aheadM: Double): Boolean {
        val (lats, lons) = ahead(route, alongM, aheadM / 1000.0) ?: return false
        return lats.indices.all { i -> boxes.any { it.contains(lats[i], lons[i]) } }
    }

    /**
     * The route's points from [alongM] to [lookAheadKm] further, each end interpolated where
     * it falls between two points. Null for a route with no points.
     */
    private fun ahead(route: Route, alongM: Double, lookAheadKm: Double): Pair<DoubleArray, DoubleArray>? {
        val n = route.points
        if (n == 0) return null
        if (n == 1) return doubleArrayOf(route.latitudes[0]) to doubleArrayOf(route.longitudes[0])
        val along = metres(route)
        val from = alongM.coerceIn(0.0, along[n - 1])
        val to = (from + max(0.0, lookAheadKm) * 1000.0).coerceAtMost(along[n - 1])
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        fun at(metres: Double) {
            var i = 0
            while (i < n - 2 && along[i + 1] < metres) i++
            val length = along[i + 1] - along[i]
            val t = if (length > 0.0) ((metres - along[i]) / length).coerceIn(0.0, 1.0) else 0.0
            lats += route.latitudes[i] + t * (route.latitudes[i + 1] - route.latitudes[i])
            lons += route.longitudes[i] + t * (route.longitudes[i + 1] - route.longitudes[i])
        }
        at(from)
        for (i in 0 until n) if (along[i] > from && along[i] < to) {
            lats += route.latitudes[i]
            lons += route.longitudes[i]
        }
        at(to)
        return lats.toDoubleArray() to lons.toDoubleArray()
    }

    private fun bounds(lats: DoubleArray, lons: DoubleArray): TrafficBox =
        TrafficBox(lats.min(), lons.min(), lats.max(), lons.max())

    /**
     * [box] grown by [PAD_M] on every side, and clamped to the globe. The east–west padding
     * is worked out at the box's most poleward latitude, where a degree of longitude is
     * shortest, so it is never less than [PAD_M] anywhere in the box.
     */
    private fun pad(box: TrafficBox): TrafficBox {
        val latPad = PAD_M / METRES_PER_DEGREE
        val poleward = max(abs(box.south), abs(box.north)) + latPad
        val lonPad = PAD_M / (METRES_PER_DEGREE * max(cos(min(poleward, 89.0) * PI / 180.0), 0.01))
        return TrafficBox(
            (box.south - latPad).coerceAtLeast(-90.0),
            (box.west - lonPad).coerceAtLeast(-180.0),
            (box.north + latPad).coerceAtMost(90.0),
            (box.east + lonPad).coerceAtMost(180.0),
        )
    }

    private fun overlaps(a: TrafficBox, b: TrafficBox): Boolean =
        a.south <= b.north && b.south <= a.north && a.west <= b.east && b.west <= a.east
}
