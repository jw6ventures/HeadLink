package com.jw6ventures.headlink

import kotlin.math.PI
import kotlin.math.cos

/**
 * What the sign at a highway exit says: the exit [number] ("23A") and where the ramp goes
 * ([toward], "I 35 South / Waco"). Either may be null, never both.
 */
data class ExitSign(val number: String?, val toward: String?) {
    init {
        require(number != null || toward != null) { "an exit sign says something" }
    }

    /** The card's line and the cluster's road name: "Exit 23A: I 35 South / Waco", "Exit 23A", or "I 35 South / Waco". */
    val label: String
        get() = when {
            number != null && toward != null -> "Exit $number: $toward"
            number != null -> "Exit $number"
            else -> toward!!
        }
}

/**
 * Exit numbers and sign text for a computed [Route], read from OsmAnd's routing data.
 *
 * Neither of HeadLink's other sources has them. BRouter's `lookups.dat` keeps no
 * `motorway_junction` and no `destination`, and the Mapsforge writer keeps only a point's
 * `name`, dropping the junction's `ref`, which is the exit number (VTM's `MapFile` reads a
 * point's name, house number and elevation, nothing else). OsmAnd's routing section keeps
 * all of it, on the same OSM ways BRouter drives, so this matches the route onto those
 * ways the way [StreetNames] matches it onto the Mapsforge map.
 *
 * For each maneuver:
 * - **The ramp.** The route just past the maneuver ([StreetNames.samples]) must lie,
 *   by a majority of samples, on one `*_link` road. No ramp, no sign: a "keep left" that
 *   stays on the highway at an exit is not that exit.
 * - **Where it goes** is the ramp's `destination:ref` and `destination` (the `:forward`
 *   or `:backward` form for the way it is driven, when the ramp has one), OSM's `;`
 *   between places read as " / ".
 * - **The number** is the ramp's `junction:ref`, else the `ref` of a `motorway_junction`
 *   point within [JUNCTION_M] of the maneuver or of the ramp's first point: OsmAnd's
 *   `getExitRef` order. An on-ramp from a surface street has no junction near it, so no
 *   number.
 * - With no destination, the junction's `exit_to` or `name` stands in for it, as an exit
 *   named "Main Street" on its sign.
 *
 * **Best effort, like the names.** A failure to read a file leaves that maneuver unsigned
 * rather than failing the route. Pure JVM, so the host tests drive it.
 */
object ExitSigns {
    /** How far a junction point may be from the maneuver or the ramp's start. */
    const val JUNCTION_M = 40.0
    /** How far the route may be from a ramp's centreline and still be on it. */
    const val MATCH_M = 8.0
    /** Half the side of the box read around a maneuver: it holds the samples past it. */
    private const val QUERY_M = 260.0

    /**
     * [route] with a sign for each maneuver [ways] can sign; [route] itself when it signs
     * none. [ways] gives OsmAnd's roads in a box, from every file that covers it. Blocking
     * file I/O: the routing thread's work.
     */
    fun sign(route: Route, ways: (GeoBox) -> List<ObfRoadWay>): Route {
        if (route.points < 2 || route.maneuverIndices.isEmpty()) return route
        val signs = Array(route.maneuverIndices.size) { k -> signAt(route, k, ways) }
        return if (signs.all { it == null }) route else route.withExitSigns(signs)
    }

    /** [sign] over the open OBF files' routing data; a file that fails to read is passed over. */
    fun sign(route: Route, sources: List<ObfSource>): Route {
        val files = sources.mapNotNull { it as? ObfFile }.filter { it.hasRouting }
        if (files.isEmpty()) return route
        return sign(route) { box ->
            files.filter { file -> file.bounds?.intersects(box) ?: true }.flatMap { file ->
                try {
                    file.roadWays(box)
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
    }

    /** The sign for the maneuver at [k], or null. */
    fun signAt(route: Route, k: Int, ways: (GeoBox) -> List<ObfRoadWay>): ExitSign? {
        val command = route.commands.getOrElse(k) { btools.router.VoiceHintReader.CONTINUE }
        val maneuver = Maneuvers.of(command, route.exitNumbers.getOrElse(k) { 0 }) ?: return null
        if (!signable(maneuver.type)) return null
        val index = route.maneuverIndices[k].coerceIn(0, route.points - 1)
        val end = route.maneuverIndices.getOrElse(k + 1) { route.points - 1 }.coerceIn(index, route.points - 1)
        val samples = StreetNames.samples(route, index, end)
        if (samples.isEmpty()) return null
        val lat = route.latitudes[index]
        val lon = route.longitudes[index]
        val roads = ways(around(lat, lon, QUERY_M))
        if (roads.isEmpty()) return null
        val ramp = ramp(samples, roads) ?: return null
        val forward = forward(samples, ramp)
        val junction = junction(roads, lat, lon, ramp, forward)
        val number = clean(ramp.tags["junction:ref"]) ?: junction?.let { clean(it["ref"]) }
        val toward = destination(ramp.tags, forward)
            ?: junction?.get("exit_to")?.let(::clean)?.let(::places)
            ?: clean(ramp.tags["junction:name"])
            ?: junction?.let { clean(it["name"]) }
        if (number == null && toward == null) return null
        return ExitSign(number, toward)
    }

    /** Maneuvers that can take a ramp: not a destination, a roundabout or a U-turn. */
    private fun signable(type: Int): Boolean = when (type) {
        Maneuvers.DESTINATION, Maneuvers.U_TURN_LEFT, Maneuvers.U_TURN_RIGHT,
        Maneuvers.ROUNDABOUT_ENTER_AND_EXIT_CCW, Maneuvers.ROUNDABOUT_ENTER_AND_EXIT_CW, Maneuvers.UNKNOWN -> false
        else -> true
    }

    /** The ramp most of [samples] lie on, if a majority of them lie on one. */
    private fun ramp(samples: List<Pair<Double, Double>>, roads: List<ObfRoadWay>): ObfRoadWay? {
        val votes = LinkedHashMap<ObfRoadWay, Int>()
        for ((lat, lon) in samples) {
            val nearest = roads
                .map { it to offset(lat, lon, it) }
                .filter { it.second <= MATCH_M }
                .minByOrNull { it.second }
                ?.first ?: continue
            votes[nearest] = (votes[nearest] ?: 0) + 1
        }
        val best = votes.entries.maxByOrNull { it.value } ?: return null
        if (best.value * 2 <= samples.size) return null
        return best.key.takeIf { it.highway?.endsWith("_link") == true }
    }

    /** Whether the route drives [way] in the order its points are stored. */
    private fun forward(samples: List<Pair<Double, Double>>, way: ObfRoadWay): Boolean {
        val first = samples.first()
        val last = samples.last()
        return position(first.first, first.second, way) <= position(last.first, last.second, way)
    }

    /**
     * The tags of the `motorway_junction` point nearest the maneuver or the ramp's first
     * point driven, within [JUNCTION_M] of either.
     */
    private fun junction(roads: List<ObfRoadWay>, lat: Double, lon: Double, ramp: ObfRoadWay, forward: Boolean): Map<String, String>? {
        val startAt = if (forward) 0 else ramp.latitudes.size - 1
        val rampLat = ramp.latitudes.getOrNull(startAt)
        val rampLon = ramp.longitudes.getOrNull(startAt)
        var best: Map<String, String>? = null
        var bestM = Double.MAX_VALUE
        for (road in roads) {
            for ((p, tags) in road.pointTags) {
                if (tags["highway"] != "motorway_junction") continue
                val pLat = road.latitudes[p]
                val pLon = road.longitudes[p]
                var m = metres(lat, lon, pLat, pLon)
                if (rampLat != null && rampLon != null) m = minOf(m, metres(rampLat, rampLon, pLat, pLon))
                if (m <= JUNCTION_M && m < bestM) {
                    best = tags
                    bestM = m
                }
            }
        }
        return best
    }

    /** The ramp's destination as its sign reads: route numbers first, then places. */
    fun destination(tags: Map<String, String>, forward: Boolean): String? {
        val way = if (forward) "forward" else "backward"
        val ref = clean(tags["destination:ref:$way"]) ?: clean(tags["destination:ref"])
        val to = clean(tags["destination:$way"]) ?: clean(tags["destination"])
        val parts = (listOfNotNull(ref, to)).flatMap { split(it) }.distinct()
        return parts.joinToString(" / ").ifEmpty { null }
    }

    private fun places(value: String): String = split(value).distinct().joinToString(" / ")

    private fun split(value: String): List<String> = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }

    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** A box [halfM] metres either side of a point. */
    fun around(lat: Double, lon: Double, halfM: Double): GeoBox {
        val dLat = halfM / METRES_PER_DEGREE
        val dLon = halfM / (METRES_PER_DEGREE * cos(lat * PI / 180.0).coerceAtLeast(0.01))
        return GeoBox(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
    }

    private fun offset(lat: Double, lon: Double, way: ObfRoadWay): Double =
        StreetNames.offset(lat, lon, NamedWay("", way.latitudes, way.longitudes))

    /** How far along [way] the nearest point to (lat, lon) is, in segments: 2.5 is halfway along the third. */
    private fun position(lat: Double, lon: Double, way: ObfRoadWay): Double {
        val n = way.latitudes.size
        if (n < 2) return 0.0
        val kx = cos(lat * PI / 180.0) * METRES_PER_DEGREE
        var best = Double.MAX_VALUE
        var at = 0.0
        for (i in 0 until n - 1) {
            val ax = (way.longitudes[i] - lon) * kx
            val ay = (way.latitudes[i] - lat) * METRES_PER_DEGREE
            val dx = (way.longitudes[i + 1] - lon) * kx - ax
            val dy = (way.latitudes[i + 1] - lat) * METRES_PER_DEGREE - ay
            val len = dx * dx + dy * dy
            val t = if (len > 0) (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0) else 0.0
            val ex = ax + t * dx
            val ey = ay + t * dy
            val d = ex * ex + ey * ey
            if (d < best) {
                best = d
                at = i + t
            }
        }
        return at
    }

    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        RouteFollower.distance(lat1, lon1, lat2, lon2)

    private const val METRES_PER_DEGREE = 6_371_000.0 * PI / 180.0
}
