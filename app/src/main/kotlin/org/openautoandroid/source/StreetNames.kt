package org.openautoandroid.source

import org.oscim.core.MercatorProjection
import org.oscim.core.Tag
import org.oscim.core.Tile
import org.oscim.tiling.source.mapfile.MapFile
import org.oscim.tiling.source.mapfile.MapFileTileSource
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A named road from the map, as a polyline in degrees. */
class NamedWay(val name: String, val latitudes: DoubleArray, val longitudes: DoubleArray)

/**
 * Street names for a computed [Route], read from the Mapsforge map the dashboard draws.
 *
 * BRouter's data has no names — its `lookups.dat` carries no `name` tag — so the route
 * says *what* each maneuver is and never *onto what*. The map file does carry them, on the
 * same OSM ways: BRouter's geometry is the OSM nodes themselves, so a stretch of route lies
 * on the centreline of the way it drives, to within the rounding of two microdegree grids.
 *
 * A route is cut into stretches at its start and at every maneuver BRouter derived, and each
 * stretch gets the name the most of its first [SPAN_M] lies on. Sampling starts [FIRST_M]
 * past the stretch's start, so a sample is off the junction where the road being left is as
 * close as the road being entered; and a name needs a majority of the samples, so a turn
 * onto an unnamed driveway is not named after the street it leaves.
 *
 * **Best effort, by design.** A name that cannot be found is null, never a guess, and a
 * failure to read the map leaves the route unnamed rather than failing it: the maneuver is
 * what the driver needs, the name is what makes it easy to read. Pure JVM over VTM's own
 * reader, so the host tests drive it against a real map file.
 */
object StreetNames {
    /** Zoom the map is read at: every road the default writer keeps appears by here. */
    private const val ZOOM: Byte = 16
    /** Metres past a stretch's start the first sample is taken. */
    private const val FIRST_M = 12.0
    private const val STEP_M = 20.0
    /** How much of a stretch is sampled; a name is what the driver sees at the turn. */
    private const val SPAN_M = 200.0
    /** How far a sample may be from a way's centreline and still be on it. */
    const val MATCH_M = 6.0

    /**
     * [route] with a name for every stretch the [maps] name; [route] itself when no map
     * opens. Blocking file I/O: the routing thread's work, never the main thread's.
     */
    fun name(route: Route, maps: List<File>): Route {
        if (maps.isEmpty() || route.points < 2) return route
        val readers = maps.mapNotNull(::open)
        if (readers.isEmpty()) return route
        try {
            val cache = HashMap<Long, List<NamedWay>>()
            val ways = { lat: Double, lon: Double -> waysNear(readers, cache, lat, lon) }
            val starts = stretchStarts(route)
            val names = Array(starts.size) { k ->
                val end = if (k + 1 < starts.size) starts[k + 1] else route.points - 1
                nameOf(samples(route, starts[k], end), ways)
            }
            return route.withRoads(starts, names)
        } finally {
            readers.forEach { (source, file) ->
                file.dispose()
                source.close()
            }
        }
    }

    /** Point indices where a stretch begins: the start, then every maneuver, ascending. */
    fun stretchStarts(route: Route): IntArray =
        (sequenceOf(0) + route.maneuverIndices.asSequence())
            .map { it.coerceIn(0, route.points - 1) }
            .distinct()
            .sorted()
            .toList()
            .toIntArray()

    /**
     * Points along the route from point [start] towards point [end]: every [STEP_M] from
     * [FIRST_M] up to [SPAN_M], or the stretch's midpoint when it is shorter than that.
     */
    fun samples(route: Route, start: Int, end: Int): List<Pair<Double, Double>> {
        val lats = route.latitudes
        val lons = route.longitudes
        var length = 0.0
        for (i in start until end) length += RouteFollower.distance(lats[i], lons[i], lats[i + 1], lons[i + 1])
        if (length <= 0.0) return emptyList()
        val wanted = if (length < FIRST_M * 2) {
            listOf(length / 2)
        } else {
            generateSequence(FIRST_M) { it + STEP_M }.takeWhile { it <= min(SPAN_M, length - FIRST_M / 2) }.toList()
        }
        val out = ArrayList<Pair<Double, Double>>(wanted.size)
        var i = start
        var at = 0.0
        for (target in wanted) {
            while (i < end) {
                val segment = RouteFollower.distance(lats[i], lons[i], lats[i + 1], lons[i + 1])
                if (at + segment >= target) {
                    val f = if (segment > 0) (target - at) / segment else 0.0
                    out += (lats[i] + (lats[i + 1] - lats[i]) * f) to (lons[i] + (lons[i + 1] - lons[i]) * f)
                    break
                }
                at += segment
                i++
            }
        }
        return out
    }

    /**
     * The name most [samples] lie on, if it holds a majority of them; ties go to the name
     * seen first, which is the one nearer the maneuver. [ways] gives the named ways near a
     * point.
     */
    fun nameOf(samples: List<Pair<Double, Double>>, ways: (Double, Double) -> List<NamedWay>): String? {
        if (samples.isEmpty()) return null
        val votes = LinkedHashMap<String, Int>()
        for ((lat, lon) in samples) {
            val nearest = ways(lat, lon)
                .map { it to offset(lat, lon, it) }
                .filter { it.second <= MATCH_M }
                .minByOrNull { it.second }
                ?.first ?: continue
            votes[nearest.name] = (votes[nearest.name] ?: 0) + 1
        }
        val best = votes.entries.maxByOrNull { it.value } ?: return null
        return best.key.takeIf { best.value * 2 > samples.size }
    }

    /** Metres from a point to the nearest part of [way], on a flat local projection. */
    fun offset(lat: Double, lon: Double, way: NamedWay): Double {
        val kx = cos(lat * PI / 180.0) * METRES_PER_DEGREE
        var best = Double.MAX_VALUE
        val n = way.latitudes.size
        if (n == 1) return hypot((way.longitudes[0] - lon) * kx, (way.latitudes[0] - lat) * METRES_PER_DEGREE)
        for (i in 0 until n - 1) {
            val ax = (way.longitudes[i] - lon) * kx
            val ay = (way.latitudes[i] - lat) * METRES_PER_DEGREE
            val bx = (way.longitudes[i + 1] - lon) * kx
            val by = (way.latitudes[i + 1] - lat) * METRES_PER_DEGREE
            val dx = bx - ax
            val dy = by - ay
            val len = dx * dx + dy * dy
            val t = if (len > 0) (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0) else 0.0
            best = min(best, hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    /**
     * The name a driver reads for a way: its `name`, else its `ref` (a numbered highway
     * with no name); null for anything that is not a road.
     */
    fun label(tags: List<Tag>): String? {
        if (tags.none { it.key == Tag.KEY_HIGHWAY }) return null
        val name = tags.firstOrNull { it.key == Tag.KEY_NAME }?.value?.trim()
        if (!name.isNullOrEmpty()) return name
        // OSM separates a road's several route numbers with semicolons.
        return tags.firstOrNull { it.key == Tag.KEY_REF }?.value
            ?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.joinToString(" / ")?.ifEmpty { null }
    }

    private fun open(file: File): Pair<MapFileTileSource, MapFile>? {
        val source = MapFileTileSource()
        return try {
            if (!source.setMapFile(file.absolutePath) || !source.open().isSuccess) {
                source.close()
                null
            } else {
                source to MapFile(source)
            }
        } catch (e: Exception) {
            source.close()
            null
        }
    }

    /**
     * Named roads in the tile under the point and the eight around it: a sample near a
     * tile's edge can be nearest to a way that only the neighbour holds.
     */
    private fun waysNear(
        readers: List<Pair<MapFileTileSource, MapFile>>,
        cache: HashMap<Long, List<NamedWay>>,
        lat: Double,
        lon: Double,
    ): List<NamedWay> {
        val x = MercatorProjection.longitudeToTileX(lon, ZOOM)
        val y = MercatorProjection.latitudeToTileY(lat, ZOOM)
        val span = 1 shl ZOOM.toInt()
        val out = ArrayList<NamedWay>()
        for (tx in max(0, x - 1)..min(span - 1, x + 1)) {
            for (ty in max(0, y - 1)..min(span - 1, y + 1)) {
                out += cache.getOrPut(tx.toLong() * span + ty) { read(readers, Tile(tx, ty, ZOOM)) }
            }
        }
        return out
    }

    private fun read(readers: List<Pair<MapFileTileSource, MapFile>>, tile: Tile): List<NamedWay> {
        val out = ArrayList<NamedWay>()
        for ((_, file) in readers) {
            val result = try {
                file.readNamedItems(tile)
            } catch (e: RuntimeException) {
                null
            } ?: continue
            for (way in result.ways) {
                val name = label(way.tags) ?: continue
                for (line in way.geoPoints) {
                    if (line.isEmpty()) continue
                    out += NamedWay(
                        name,
                        DoubleArray(line.size) { line[it].latitude },
                        DoubleArray(line.size) { line[it].longitude },
                    )
                }
            }
        }
        return out
    }

    /** The same flat earth [RouteFollower] measures the route on. */
    private const val METRES_PER_DEGREE = 6_371_000.0 * PI / 180.0
}
