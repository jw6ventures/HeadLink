package com.jw6ventures.headlink

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * What the route preview is handed: the route being followed, the trip it was asked for,
 * and a [token] naming that route, so a choice made after the route changed is refused.
 *
 * The preview runs in a process of its own ([PreviewActivity]), so this crosses as a file
 * in app-private storage rather than as an object. It holds coordinates, as every route
 * does, and never reaches the report.
 *
 * [nogos] are live traffic's no-go points (P9.5), which the dashboard's live alternatives
 * search with ([LiveAlternatives]). They are never written to the preview's file: HERE's
 * data stays in memory (P9), so the preview's own alternatives are without traffic.
 */
class PreviewRequest @JvmOverloads constructor(
    val token: Long,
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
    val startBearing: Double?,
    val options: RouteOptions,
    val route: Route,
    val nogos: List<Nogo> = emptyList(),
) {
    /** The request for BRouter's alternative [index] over the same trip. */
    fun alternative(index: Int): RouteRequest =
        RouteRequest(fromLat, fromLon, toLat, toLon, startBearing, options, index, nogos = nogos)

    /** The best route over the same trip that passes through one point. */
    fun through(lat: Double, lon: Double): RouteRequest =
        RouteRequest(fromLat, fromLon, toLat, toLon, startBearing, options, 0, lat, lon, nogos)
}

/**
 * The preview's alternatives: routes that leave the route on show where they have to and
 * rejoin it where that is best, rather than BRouter's own alternatives, which avoid every
 * road the best route uses.
 *
 * BRouter finds its alternative *k* by searching again with each road an earlier route
 * used costing its length over again. That penalty is fixed in the engine (`OsmPath`, in
 * the `final` kinematic path), and it is strong: around Austin its alternatives shared
 * under a tenth of the best route's points and ran 30 to 100 minutes longer. Austin to
 * Temple offered 91 and 101 minutes against 65; a driver would rather leave I 35 for a
 * stretch and come back to it.
 *
 * So each of BRouter's alternatives is used only to find **a different corridor**: a few
 * of its points ([FRACTIONS] of the way along) that are at least [minimumDeviation] from
 * the route on show become candidate via points, and the ordinary best route through each
 * one is computed. The fastest that is not a U-turn at its via point and not a
 * [RoutePreview.duplicate] of a route already offered is the alternative. When none is,
 * BRouter's own alternative is offered instead, if it is not a duplicate itself. Around
 * Austin this gave Temple 71 minutes sharing 55% with the best route, College Station 134
 * against 131 sharing 58%, and Dallas 204 against 176 sharing 70%.
 *
 * Costly: BRouter's alternative *k* is *k*+1 searches, and each candidate is another. Each
 * alternative is handed to [find]'s callback as soon as it is chosen.
 */
class AlternativeFinder(
    private val router: Router,
    private val trip: PreviewRequest,
    private val maxRunningMs: Long = RoutePlanner.MAX_RUNNING_MS,
    /** Receives each engine before its search, so the caller can stop it. */
    private val started: (btools.router.RoutingEngine) -> Unit = {},
    /** Checked between searches; true stops the search for more. */
    private val cancelled: () -> Boolean = { false },
) {
    /**
     * Finds up to [RoutePreview.ALTERNATIVES] alternatives to [PreviewRequest.route], each
     * given to [found] as it is chosen. Returns the last routing failure, or null.
     */
    fun find(found: (Route) -> Unit): String? {
        val best = trip.route
        val offered = mutableListOf(best)
        var failure: String? = null
        for (k in 1..RoutePreview.ALTERNATIVES) {
            if (cancelled()) return failure
            val strict = try {
                router.compute(trip.alternative(k), maxRunningMs, started)
            } catch (e: RoutingException) {
                failure = e.message
                continue
            }
            var chosen: Route? = null
            for (point in candidates(strict, best)) {
                if (cancelled()) return failure
                val via = try {
                    router.compute(trip.through(strict.latitudes[point], strict.longitudes[point]), maxRunningMs, started)
                } catch (e: RoutingException) {
                    failure = e.message
                    continue
                }
                if (uTurnAt(via, strict.latitudes[point], strict.longitudes[point])) continue
                if (RoutePreview.duplicate(via, offered)) continue
                if (chosen == null || via.seconds < chosen.seconds) chosen = via
            }
            val offer = chosen ?: strict.takeUnless { RoutePreview.duplicate(it, offered) } ?: continue
            offered += offer
            found(offer)
        }
        return failure
    }

    /** Points of [strict] at [FRACTIONS] of the way along that are far enough off [best]. */
    private fun candidates(strict: Route, best: Route): List<Int> {
        val minimum = minimumDeviation(best)
        return FRACTIONS.map { (it * (strict.points - 1)).toInt() }
            .distinct()
            .filter { index -> offset(best, strict.latitudes[index], strict.longitudes[index]) >= minimum }
    }

    companion object {
        /** Where along BRouter's alternative a via point is tried. */
        val FRACTIONS = listOf(0.3, 0.5, 0.7)

        /**
         * How far off the route on show a via point must be: a point on it only gives the
         * same route back. 2% of the trip, and never under a kilometre.
         */
        fun minimumDeviation(best: Route): Double = maxOf(1_000.0, best.distanceM * 0.02)

        /** Metres from ([lat], [lon]) to the nearest point of [route]. */
        fun offset(route: Route, lat: Double, lon: Double): Double {
            var nearest = Double.MAX_VALUE
            for (i in 0 until route.points) {
                nearest = minOf(nearest, RouteFollower.distance(route.latitudes[i], route.longitudes[i], lat, lon))
            }
            return nearest
        }

        /**
         * Whether [route] turns back on itself at its via point: a via point on the wrong
         * carriageway, or a spur, can make the best route drive in and come back out. Judged
         * by the direction of travel over [TURN_BASE_M] either side of the route's nearest
         * point to the via point.
         */
        fun uTurnAt(route: Route, lat: Double, lon: Double): Boolean {
            var at = 0
            var nearest = Double.MAX_VALUE
            for (i in 0 until route.points) {
                val d = RouteFollower.distance(route.latitudes[i], route.longitudes[i], lat, lon)
                if (d < nearest) {
                    nearest = d
                    at = i
                }
            }
            val before = walk(route, at, -1)
            val after = walk(route, at, 1)
            if (before == at || after == at) return false
            val inbound = RouteFollower.bearing(route.latitudes[before], route.longitudes[before], route.latitudes[at], route.longitudes[at])
            val outbound = RouteFollower.bearing(route.latitudes[at], route.longitudes[at], route.latitudes[after], route.longitudes[after])
            val turn = Math.abs(((outbound - inbound) % 360.0 + 540.0) % 360.0 - 180.0)
            return turn > U_TURN_DEGREES
        }

        /** The first point [TURN_BASE_M] or more from [from] going [step], or the end reached. */
        private fun walk(route: Route, from: Int, step: Int): Int {
            var i = from
            while (i + step in 0 until route.points) {
                i += step
                if (RouteFollower.distance(route.latitudes[i], route.longitudes[i], route.latitudes[from], route.longitudes[from]) >= TURN_BASE_M) break
            }
            return i
        }

        const val TURN_BASE_M = 150.0

        /** A turn sharper than this, degrees, counts as turning back. */
        const val U_TURN_DEGREES = 150.0
    }
}

/**
 * The preview's two files and their format: [PreviewRequest] in, the chosen route out.
 * Pure JVM, so the host test checks the round trip. A version number leads each file, so
 * a file left by another build is refused rather than misread.
 */
object RoutePreview {
    /** How many of BRouter's alternatives the preview asks for, beyond the route itself. */
    const val ALTERNATIVES = 2

    /**
     * An alternative sharing more than this fraction of its points with a route already
     * on offer is dropped as the same route. BRouter prices the roads earlier searches
     * used, so on the Texas trips measured they shared under a fifth.
     */
    const val DUPLICATE_SHARE = 0.8

    fun directory(filesDir: File): File = File(filesDir, "preview")

    fun requestFile(filesDir: File): File = File(directory(filesDir), "request.bin")

    fun choiceFile(filesDir: File): File = File(directory(filesDir), "choice.bin")

    @Throws(IOException::class)
    fun writeRequest(file: File, request: PreviewRequest) = write(file) { out ->
        out.writeLong(request.token)
        out.writeDouble(request.fromLat)
        out.writeDouble(request.fromLon)
        out.writeDouble(request.toLat)
        out.writeDouble(request.toLon)
        out.writeDouble(request.startBearing ?: Double.NaN)
        val o = request.options
        for (flag in listOf(o.avoidHighways, o.avoidTolls, o.avoidUnpaved, o.bypassTowns, o.allowTracks, o.learnPace, o.showAlternatives)) {
            out.writeBoolean(flag)
        }
        out.writeInt(o.topSpeedKmh)
        out.writeInt(o.etaPercent)
        writeRoute(out, request.route)
    }

    @Throws(IOException::class)
    fun readRequest(file: File): PreviewRequest = read(file) { input ->
        val token = input.readLong()
        val fromLat = input.readDouble()
        val fromLon = input.readDouble()
        val toLat = input.readDouble()
        val toLon = input.readDouble()
        val bearing = input.readDouble().takeUnless { it.isNaN() }
        val flags = BooleanArray(7) { input.readBoolean() }
        val options = RouteOptions(
            avoidHighways = flags[0],
            avoidTolls = flags[1],
            avoidUnpaved = flags[2],
            bypassTowns = flags[3],
            allowTracks = flags[4],
            learnPace = flags[5],
            showAlternatives = flags[6],
            topSpeedKmh = input.readInt(),
            etaPercent = input.readInt(),
        ).repaired()
        PreviewRequest(token, fromLat, fromLon, toLat, toLon, bearing, options, readRoute(input))
    }

    /** The route chosen, and which [PreviewRequest.token] it was chosen against. */
    @Throws(IOException::class)
    fun writeChoice(file: File, token: Long, route: Route) = write(file) { out ->
        out.writeLong(token)
        writeRoute(out, route)
    }

    @Throws(IOException::class)
    fun readChoice(file: File): Pair<Long, Route> = read(file) { input -> input.readLong() to readRoute(input) }

    internal fun writeRoute(out: DataOutputStream, route: Route) {
        out.writeInt(route.points)
        for (i in 0 until route.points) {
            out.writeDouble(route.latitudes[i])
            out.writeDouble(route.longitudes[i])
            out.writeFloat(route.pointSeconds.getOrElse(i) { Float.NaN })
        }
        out.writeInt(route.distanceM)
        out.writeInt(route.seconds)
        out.writeInt(route.topSpeedKmh)
        out.writeDouble(route.via?.get(0) ?: Double.NaN)
        out.writeDouble(route.via?.get(1) ?: Double.NaN)
        out.writeInt(route.maneuverIndices.size)
        for (k in route.maneuverIndices.indices) {
            out.writeInt(route.maneuverIndices[k])
            out.writeInt(route.exitNumbers[k])
            out.writeInt(route.commands[k])
        }
        out.writeInt(route.roadStarts.size)
        for (k in route.roadStarts.indices) {
            out.writeInt(route.roadStarts[k])
            val name = route.roadNames[k]
            out.writeBoolean(name != null)
            if (name != null) out.writeUTF(name)
        }
        // Version 3 (P9): whether traffic's no-go points shaped it, so "Traffic © HERE" is
        // still owed after a restart, and whether it goes through a closure. Two
        // flags; nothing of HERE's.
        out.writeBoolean(route.trafficShaped)
        out.writeBoolean(route.closuresOpened)
    }

    internal fun readRoute(input: DataInputStream): Route {
        val points = count(input.readInt(), MAX_POINTS)
        val latitudes = DoubleArray(points)
        val longitudes = DoubleArray(points)
        val seconds = FloatArray(points)
        for (i in 0 until points) {
            latitudes[i] = input.readDouble()
            longitudes[i] = input.readDouble()
            seconds[i] = input.readFloat()
        }
        val distance = input.readInt()
        val total = input.readInt()
        val topSpeed = input.readInt()
        val viaLat = input.readDouble()
        val viaLon = input.readDouble()
        val via = if (viaLat.isNaN() || viaLon.isNaN()) null else doubleArrayOf(viaLat, viaLon)
        val maneuvers = count(input.readInt(), points)
        val indices = IntArray(maneuvers)
        val exits = IntArray(maneuvers)
        val commands = IntArray(maneuvers)
        for (k in 0 until maneuvers) {
            indices[k] = input.readInt()
            exits[k] = input.readInt()
            commands[k] = input.readInt()
        }
        val roads = count(input.readInt(), points)
        val starts = IntArray(roads)
        val names = arrayOfNulls<String>(roads)
        for (k in 0 until roads) {
            starts[k] = input.readInt()
            if (input.readBoolean()) names[k] = input.readUTF()
        }
        if (points < 2 || indices.any { it !in 0 until points } || starts.any { it !in 0 until points }) {
            throw IOException("route file is not a route")
        }
        // Version 2 files, written before P9, have neither flag.
        val version = (input as? VersionedInput)?.version ?: VERSION
        val shaped = version >= 3 && input.readBoolean()
        val opened = version >= 3 && input.readBoolean()
        val timed = if (seconds.any { it.isNaN() }) FloatArray(0) else seconds
        return Route(
            latitudes, longitudes, distance, total, indices, exits, commands, timed, starts, names, topSpeed, via,
            if (shaped) 1 else 0, opened,
        )
    }

    /** A file being read, and the version it was written at. */
    private class VersionedInput(stream: java.io.InputStream, val version: Int) : DataInputStream(stream)

    private fun count(value: Int, limit: Int): Int {
        if (value < 0 || value > limit) throw IOException("route file count out of range: $value")
        return value
    }

    internal fun <T> write(file: File, body: (DataOutputStream) -> T): T {
        val directory = file.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")
        // Staged and renamed, so the other process never reads half a file.
        val staged = File(file.path + ".part")
        try {
            val result = DataOutputStream(staged.outputStream().buffered()).use { out ->
                out.writeInt(VERSION)
                body(out)
            }
            if (!staged.renameTo(file)) throw IOException("cannot write ${file.name}")
            return result
        } finally {
            staged.delete()
        }
    }

    internal fun <T> read(file: File, body: (DataInputStream) -> T): T =
        DataInputStream(file.inputStream().buffered()).use { raw ->
            val version = raw.readInt()
            // A route or destination saved by the last version is still read, less what it lacks.
            if (version != VERSION && version != VERSION - 1) throw IOException("route file version $version, expected $VERSION")
            body(VersionedInput(raw, version))
        }

    /**
     * The named road a route drives furthest on that none of [others] use: how the preview
     * tells its options apart, "via I 35". Two alternatives that both leave the route on
     * the same road would otherwise both be "via" it. The road driven furthest when every
     * road it uses is also on another option; null when the map names none of it.
     */
    @JvmOverloads
    fun via(route: Route, others: List<Route> = emptyList()): String? {
        val taken = others.flatMapTo(HashSet()) { other -> other.roadNames.filterNotNull() }
        val metres = namedMetres(route) ?: return null
        return metres.filterKeys { it !in taken }.maxByOrNull { it.value }?.key ?: metres.maxByOrNull { it.value }?.key
    }

    /** Metres driven on each named road, or null when the route has no names. */
    private fun namedMetres(route: Route): Map<String, Double>? {
        if (route.roadStarts.isEmpty()) return null
        val metres = HashMap<String, Double>()
        for (k in route.roadStarts.indices) {
            val name = route.roadNames[k] ?: continue
            val end = if (k + 1 < route.roadStarts.size) route.roadStarts[k + 1] else route.points - 1
            var length = 0.0
            for (i in route.roadStarts[k] until end) {
                length += RouteFollower.distance(route.latitudes[i], route.longitudes[i], route.latitudes[i + 1], route.longitudes[i + 1])
            }
            metres[name] = (metres[name] ?: 0.0) + length
        }
        return metres.takeIf { it.isNotEmpty() }
    }

    /** The share of [candidate]'s points that are also points of [other]: BRouter reuses node positions exactly. */
    fun shared(candidate: Route, other: Route): Double {
        val points = HashSet<Long>(other.points * 2)
        for (i in 0 until other.points) points += key(other.latitudes[i], other.longitudes[i])
        var hits = 0
        for (i in 0 until candidate.points) if (key(candidate.latitudes[i], candidate.longitudes[i]) in points) hits++
        return hits.toDouble() / candidate.points
    }

    /** Whether [candidate] is a route already on offer under another name. */
    fun duplicate(candidate: Route, offered: List<Route>): Boolean = offered.any { shared(candidate, it) > DUPLICATE_SHARE }

    /** South, west, north and east edges around every route given, degrees. */
    fun bounds(routes: List<Route>): DoubleArray {
        var south = 90.0
        var west = 180.0
        var north = -90.0
        var east = -180.0
        for (route in routes) {
            for (i in 0 until route.points) {
                south = min(south, route.latitudes[i])
                north = max(north, route.latitudes[i])
                west = min(west, route.longitudes[i])
                east = max(east, route.longitudes[i])
            }
        }
        return doubleArrayOf(south, west, north, east)
    }

    private fun key(lat: Double, lon: Double): Long =
        (Math.round(lat * 1e6) shl 32) xor (Math.round(lon * 1e6) and 0xFFFFFFFFL)

    /** 2: routes carry their via point. 3: and whether traffic shaped them (P9). */
    private const val VERSION = 3

    /** Bounds a corrupt count: Austin to Denver was 8,677 points. */
    private const val MAX_POINTS = 2_000_000
}
