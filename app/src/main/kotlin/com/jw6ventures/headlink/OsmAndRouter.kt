package com.jw6ventures.headlink

import btools.router.VoiceHintReader
import net.osmand.binary.BinaryMapIndexReader
import net.osmand.binary.RouteDataObject
import net.osmand.data.LatLon
import net.osmand.router.GeneralRouter
import net.osmand.router.HHRouteDataStructure
import net.osmand.router.RouteCalculationProgress
import net.osmand.router.RouteResultPreparation
import net.osmand.router.RoutePlannerFrontEnd
import net.osmand.router.RouteSegmentResult
import net.osmand.router.RoutingConfiguration
import net.osmand.router.TurnType
import net.osmand.util.MapUtils
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.BitSet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * OsmAnd's own router (vendor/osmand) over OsmAnd's `.obf` maps: the same maps address
 * search and exit signs already read, so a route needs no `.rd5` tiles. Pure JVM, like
 * [Router], so the host tests run this same class.
 *
 * **What it gives that BRouter does not.** OsmAnd's data keeps road names, refs, exit
 * numbers, `destination` signs and turn lanes, and its router derives its turns from them,
 * so a route arrives already named and signed ([toRoute]).
 *
 * **Search.** With nothing to steer round, the maps' precomputed highway hierarchy (HH)
 * does the long-distance work, which is what lets a phone route across a state in seconds;
 * OsmAnd falls back to its plain search where a map has no HH section. Anything that
 * prices roads per request (live traffic, the roads an earlier alternative used) is
 * invisible to HH's precomputed shortcuts, so such a request uses the plain search, which
 * asks [PenaltyRouter] about every step it takes.
 *
 * **Traffic** ([RouteRequest.nogos]). A hard point closes every step of road inside its
 * circle. A weighted one adds time to each step in proportion to the metres of it inside
 * the circle: its weight is BRouter cost per metre at the request's top speed
 * ([TrafficCost]), turned into seconds by [TrafficCost.secondPrice]. OsmAnd adds every
 * step's time, so overlapping circles add up, and none of BRouter's one-point-per-link
 * workarounds in [Router] is needed. A request that finds no route with its closures
 * closed is tried once more with each closure passable at a high price, as [Router] does,
 * so a vehicle already on a closed road still gets its way out.
 *
 * **Direction.** [RouteRequest.startBearing] goes to OsmAnd as the initial direction, and
 * the car profile charges 300 s for setting off the other way, which is what [Router]'s
 * point behind the vehicle approximates for BRouter.
 *
 * **Alternatives** ([RouteRequest.alternative]) are found the way BRouter finds them:
 * alternative *k* is searched with every road the routes before it used costing its time
 * over again, so it looks for a different corridor.
 *
 * Not thread-safe: one routing thread, as for [Router]. Readers stay open between requests
 * and are closed once their file drops out of [maps].
 */
class OsmAndRouter(
    /** The maps to route over, asked again for every request. */
    private val maps: () -> List<MapFile>,
) : RouteEngine {
    /**
     * One `.obf` file as OsmAnd's reader opens it: [path] to open, [name] as the file is
     * called (OsmAnd reads region names from it), and [id], which changes whenever the
     * file does, so an open reader is reused only for the same file.
     */
    class MapFile(val id: String, val name: String, val path: String) {
        companion object {
            @JvmStatic
            fun of(file: File): MapFile = MapFile("${file.absolutePath}|${file.length()}|${file.lastModified()}", file.name, file.absolutePath)
        }
    }

    private class Open(val file: RandomAccessFile, val reader: BinaryMapIndexReader)

    private val open = LinkedHashMap<String, Open>()

    @Throws(RoutingException::class)
    override fun compute(request: RouteRequest, maxRunningMs: Long, started: (SearchHandle) -> Unit): Route {
        val readers = readers()
        if (readers.isEmpty()) throw RoutingException("no OsmAnd maps open")
        if (request.alternative > 0) return alternative(request, readers, maxRunningMs, started)
        return withTraffic(request, readers, maxRunningMs, started, used = null)
    }

    /** Closes every open map. */
    fun close() {
        for (entry in open.values) closeQuietly(entry)
        open.clear()
    }

    private fun readers(): List<BinaryMapIndexReader> {
        val wanted = maps()
        val ids = wanted.map { it.id }.toSet()
        val gone = open.keys.filter { it !in ids }
        for (id in gone) open.remove(id)?.let(::closeQuietly)
        for (map in wanted) {
            if (map.id in open) continue
            var file: RandomAccessFile? = null
            try {
                file = RandomAccessFile(map.path, "r")
                // A File only for its name: OsmAnd reads the region from it, and the path
                // may be a /proc/self/fd link whose own name says nothing.
                val named = object : File(map.path) {
                    override fun getName(): String = map.name
                }
                val reader = BinaryMapIndexReader(file, named)
                if (reader.routingIndexes.isEmpty()) {
                    // An address- or POI-only file: nothing to route on.
                    file.close()
                    continue
                }
                open[map.id] = Open(file, reader)
            } catch (e: IOException) {
                file?.let { closeQuietly(it) }
            } catch (e: RuntimeException) {
                // A malformed file is skipped, never a crash on the routing thread.
                file?.let { closeQuietly(it) }
            }
        }
        return open.values.map { it.reader }
    }

    /**
     * The route with [request]'s traffic, and with closures opened at a price when no route
     * keeps out of them (class notes).
     */
    private fun withTraffic(
        request: RouteRequest,
        readers: List<BinaryMapIndexReader>,
        maxRunningMs: Long,
        started: (SearchHandle) -> Unit,
        used: UsedRoads?,
    ): Route {
        val nogos = request.nogos
        try {
            val route = search(request, readers, nogos, used, maxRunningMs, started)
            // OsmAnd ends a route on the nearest road it can reach, however far from the
            // destination that is. With closures in the search, an end that far off means
            // they cut the destination off, which is the same as no route.
            if (nogos.none { it.hard } || endsNear(route, request)) return route.withNogos(nogos.size, false)
        } catch (e: RoutingException) {
            if (e.stopped || nogos.none { it.hard }) throw e
        }
        val opened = nogos.map { nogo ->
            if (nogo.hard) nogo.copy(weight = TrafficCost.openedClosureWeight(nogo.radiusM, request.options.topSpeedKmh)) else nogo
        }
        return search(request, readers, opened, used, maxRunningMs, started).withNogos(opened.size, true)
    }

    /**
     * BRouter's alternative *k*: routes 0 to *k* in turn, each searched with the roads of
     * the ones before it costing their time twice over ([UsedRoads]).
     */
    private fun alternative(
        request: RouteRequest,
        readers: List<BinaryMapIndexReader>,
        maxRunningMs: Long,
        started: (SearchHandle) -> Unit,
    ): Route {
        val used = UsedRoads()
        var route: Route? = null
        for (k in 0..request.alternative) {
            val asked = RouteRequest(
                request.fromLat, request.fromLon, request.toLat, request.toLon, request.startBearing, request.options,
                0, request.viaLat, request.viaLon, request.nogos,
            )
            route = withTraffic(asked, readers, maxRunningMs, started, used.takeIf { k > 0 })
            used.add(lastSegments)
        }
        return route!!
    }

    /** The last search's segments, for [alternative] to price on the next one. */
    private var lastSegments: List<RouteSegmentResult> = emptyList()

    private fun search(
        request: RouteRequest,
        readers: List<BinaryMapIndexReader>,
        nogos: List<Nogo>,
        used: UsedRoads?,
        maxRunningMs: Long,
        started: (SearchHandle) -> Unit,
    ): Route {
        val options = request.options
        val parameters = profileParameters(options)
        val direction = request.startBearing?.let { Math.toRadians(it) }
        val config = builder().build(PROFILE, direction, memoryLimits(), parameters)
        val penalties = StepPenalty.of(nogos, options.topSpeedKmh)
        val priced = penalties != null || used != null
        if (priced) config.router = PenaltyRouter(config.router, penalties, used)
        val frontEnd = RoutePlannerFrontEnd()
        if (!priced) frontEnd.setDefaultHHRoutingConfig()
        val context = frontEnd.buildRoutingContext(config, null, readers.toTypedArray(), RoutePlannerFrontEnd.RouteCalculationMode.NORMAL)
        context.leftSideNavigation = false
        val progress = RouteCalculationProgress()
        context.calculationProgress = progress
        started(SearchHandle { progress.isCancelled = true })
        val timer = TIMER.schedule({ progress.isCancelled = true }, maxRunningMs, TimeUnit.MILLISECONDS)
        val via = request.viaLat?.let { listOf(LatLon(it, request.viaLon!!)) }
        val result = try {
            frontEnd.searchRoute(context, LatLon(request.fromLat, request.fromLon), LatLon(request.toLat, request.toLon), via)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RoutingException("routing interrupted", true)
        } catch (e: IOException) {
            throw RoutingException("map read failed: ${e.message}")
        } finally {
            timer.cancel(false)
        }
        if (progress.isCancelled) throw RoutingException(if (progress.memoryLimitExceeded) "out of routing memory" else "routing stopped", true)
        if (result == null || !result.isCorrect) throw RoutingException(result?.error ?: "no route found")
        lastSegments = result.list
        return toRoute(result.list, options, request)
    }

    companion object {
        private const val PROFILE = "car"

        /**
         * How far from the destination a route with closures may end and still count as
         * reaching it, metres: about how far BRouter will look for a road from a waypoint.
         */
        const val END_SNAP_M = 250.0

        private fun endsNear(route: Route, request: RouteRequest): Boolean {
            val last = route.points - 1
            return MapUtils.getDistance(route.latitudes[last], route.longitudes[last], request.toLat, request.toLon) <= END_SNAP_M
        }

        /** Stops searches that outlive their time; one daemon thread for every router. */
        private val TIMER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "osmand-route-timer").apply { isDaemon = true }
        }

        /** `routing.xml` parsed once: the profile file never changes at run time. */
        private var parsed: RoutingConfiguration.Builder? = null

        @Synchronized
        private fun builder(): RoutingConfiguration.Builder {
            parsed?.let { return it }
            // A process-wide switch upstream. Telling which maps a route lacks needs OsmAnd's
            // region outlines, which are not shipped (vendor/osmand/README.md, "Regions"), and
            // a missing map shows here as no route.
            RoutePlannerFrontEnd.CALCULATE_MISSING_MAPS = false
            // Upstream prints each route's summary to standard output, start and destination
            // included, which on a phone is logcat. HeadLink keeps places out of every log.
            RouteResultPreparation.PRINT_TO_CONSOLE_ROUTE_INFORMATION = false
            HHRouteDataStructure.HHRoutingConfig.STATS_VERBOSE_LEVEL = 0
            return RoutingConfiguration.getDefault().also { parsed = it }
        }

        /**
         * OsmAnd's own memory guard, MiB: a third of the heap, the rest being the app's,
         * and never more than upstream's native default.
         */
        private fun memoryLimits(): RoutingConfiguration.RoutingMemoryLimits {
            val heapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
            val limit = (heapMb / 3).coerceIn(RoutingConfiguration.DEFAULT_MEMORY_LIMIT, RoutingConfiguration.DEFAULT_NATIVE_MEMORY_LIMIT)
            return RoutingConfiguration.RoutingMemoryLimits(limit, RoutingConfiguration.DEFAULT_NATIVE_MEMORY_LIMIT)
        }

        /**
         * The Routing settings as the car profile's parameters. Going round towns and
         * allowing tracks have no counterpart in OsmAnd's car profile and are left out; the
         * Routing screen says so. The top speed is OsmAnd's `max_speed`, in metres a second.
         */
        @JvmStatic
        fun profileParameters(options: RouteOptions): MutableMap<String, String> {
            val parameters = LinkedHashMap<String, String>()
            if (options.avoidHighways) parameters[GeneralRouter.AVOID_MOTORWAY] = "true"
            if (options.avoidTolls) parameters[GeneralRouter.AVOID_TOLL] = "true"
            if (options.avoidUnpaved) parameters[GeneralRouter.AVOID_UNPAVED] = "true"
            parameters[GeneralRouter.MAX_SPEED] = (options.topSpeedKmh / 3.6).toString()
            return parameters
        }

        /**
         * [segments] as a [Route]: the geometry point by point, each point's time spread
         * along its segment by distance, a maneuver wherever OsmAnd put a turn after the
         * first segment, the road each stretch drives, and the exit sign at a junction that
         * has one.
         */
        @JvmStatic
        fun toRoute(segments: List<RouteSegmentResult>, options: RouteOptions, request: RouteRequest): Route {
            val lats = ArrayList<Double>()
            val lons = ArrayList<Double>()
            val times = ArrayList<Float>()
            val indices = ArrayList<Int>()
            val exits = ArrayList<Int>()
            val commands = ArrayList<Int>()
            val signs = ArrayList<ExitSign?>()
            val roadStarts = ArrayList<Int>()
            val roadNames = ArrayList<String?>()
            var clock = 0.0
            var metres = 0.0
            for ((s, segment) in segments.withIndex()) {
                val road = segment.getObject()
                val step = if (segment.startPointIndex <= segment.endPointIndex) 1 else -1
                val first = lats.size
                var point = segment.startPointIndex
                var lengths = 0.0
                val pieces = ArrayList<Double>()
                var lastLat = Double.NaN
                var lastLon = Double.NaN
                while (true) {
                    val lat = MapUtils.get31LatitudeY(road.getPoint31YTile(point))
                    val lon = MapUtils.get31LongitudeX(road.getPoint31XTile(point))
                    if (!lastLat.isNaN()) {
                        val piece = MapUtils.getDistance(lastLat, lastLon, lat, lon)
                        pieces += piece
                        lengths += piece
                    }
                    lastLat = lat
                    lastLon = lon
                    if (point == segment.endPointIndex) break
                    point += step
                }
                // The segment's own first point is the last one's end: keep one of the two.
                val shared = first > 0
                val seconds = segment.segmentTime.toDouble()
                var walked = 0.0
                point = segment.startPointIndex
                var k = -1
                while (true) {
                    if (k >= 0) walked += pieces[k]
                    val lat = MapUtils.get31LatitudeY(road.getPoint31YTile(point))
                    val lon = MapUtils.get31LongitudeX(road.getPoint31XTile(point))
                    if (!(k < 0 && shared)) {
                        lats += lat
                        lons += lon
                        times += (clock + if (lengths > 0) seconds * walked / lengths else 0.0).toFloat()
                    }
                    if (point == segment.endPointIndex) break
                    point += step
                    k++
                }
                clock += seconds
                metres += lengths
                val at = if (shared) first - 1 else first
                val turn = segment.turnType
                val command = if (s > 0 && turn != null) command(turn, segments.getOrNull(s - 1), segment) else null
                if (s == 0 || command != null) {
                    roadStarts += at
                    roadNames += roadName(road)
                }
                if (command != null) {
                    indices += at
                    exits += if (turn!!.isRoundAbout) turn.exitOut else 0
                    commands += command
                    signs += exitSign(segments, s)
                }
            }
            if (lats.size < 2) throw RoutingException("no route found")
            val total = segments.sumOf { it.segmentTime.toDouble() }
            return Route(
                lats.toDoubleArray(),
                lons.toDoubleArray(),
                metres.roundToInt(),
                total.roundToInt(),
                indices.toIntArray(),
                exits.toIntArray(),
                commands.toIntArray(),
                times.toFloatArray(),
                roadStarts.toIntArray(),
                roadNames.toTypedArray(),
                options.topSpeedKmh,
                request.viaLat?.let { doubleArrayOf(it, request.viaLon!!) },
                0,
                false,
                if (signs.any { it != null }) signs.toTypedArray() else emptyArray(),
            ).withTimeScale(options.timeFactor)
        }

        /**
         * OsmAnd's [turn] as the [VoiceHintReader] command the rest of the app reads. A keep
         * from a motorway or trunk onto its link is the exit it is, as BRouter calls it.
         * Null for a turn that is no maneuver: OsmAnd's off-route marker.
         */
        @JvmStatic
        fun command(turn: TurnType, before: RouteSegmentResult?, segment: RouteSegmentResult): Int? = when (turn.value) {
            TurnType.C -> VoiceHintReader.CONTINUE
            TurnType.TL -> VoiceHintReader.TURN_LEFT
            TurnType.TSLL -> VoiceHintReader.TURN_SLIGHT_LEFT
            TurnType.TSHL -> VoiceHintReader.TURN_SHARP_LEFT
            TurnType.TR -> VoiceHintReader.TURN_RIGHT
            TurnType.TSLR -> VoiceHintReader.TURN_SLIGHT_RIGHT
            TurnType.TSHR -> VoiceHintReader.TURN_SHARP_RIGHT
            TurnType.KL -> if (offRamp(before, segment)) VoiceHintReader.EXIT_LEFT else VoiceHintReader.KEEP_LEFT
            TurnType.KR -> if (offRamp(before, segment)) VoiceHintReader.EXIT_RIGHT else VoiceHintReader.KEEP_RIGHT
            TurnType.TU -> VoiceHintReader.U_TURN_180
            TurnType.TRU -> VoiceHintReader.U_TURN_RIGHT
            TurnType.RNDB -> VoiceHintReader.ROUNDABOUT
            TurnType.RNLB -> VoiceHintReader.ROUNDABOUT_LEFT
            else -> null
        }

        private fun offRamp(before: RouteSegmentResult?, segment: RouteSegmentResult): Boolean {
            val from = before?.getObject()?.highway ?: return false
            val to = segment.getObject().highway ?: return false
            return (from == "motorway" || from == "trunk") && to.endsWith("_link")
        }

        /** The road's name, else its ref ("I 35"), else null. */
        private fun roadName(road: RouteDataObject): String? =
            road.name?.takeIf { it.isNotBlank() } ?: road.getRef("", false, true)?.takeIf { it.isNotBlank() }

        /**
         * The exit number and sign text where the segment at [s] begins. The text is
         * OsmAnd's own destination for the maneuver. The number is the link's
         * `junction:ref` where the map gives it one, else the `ref` of the
         * `motorway_junction` node the route leaves the motorway at, read from either road
         * at that point: OsmAnd's maps keep the node on the motorway, and only some keep it
         * on the link too.
         */
        private fun exitSign(segments: List<RouteSegmentResult>, s: Int): ExitSign? {
            val segment = segments[s]
            val before = segments.getOrNull(s - 1)
            val number = (
                segment.getObject().junctionRef
                    ?: before?.let { junctionRef(it.getObject(), it.endPointIndex) }
                    ?: junctionRef(segment.getObject(), segment.startPointIndex)
                )?.takeIf { it.isNotBlank() }
            // The sign as ExitSigns words it: the road first, then the places, one " / " apart.
            val ref = segment.getObject().getDestinationRef("", false, segment.isForwardDirection)
            val places = segment.getDestinationName("", false, segments, s, false)
            val toward = listOfNotNull(ref, places)
                .flatMap { it.split(';') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" / ")
                .takeIf { it.isNotEmpty() }
            return if (number != null || toward != null) ExitSign(number, toward) else null
        }

        /** The `ref` of a `motorway_junction` node at [point] of [road], or null. */
        private fun junctionRef(road: RouteDataObject, point: Int): String? {
            if (point < 0 || point >= road.pointsLength) return null
            val types = road.getPointTypes(point) ?: return null
            if (types.none { road.region.quickGetEncodingRule(it)?.value == "motorway_junction" }) return null
            val names = road.getPointNames(point) ?: return null
            val nameTypes = road.getPointNameTypes(point) ?: return null
            for (k in names.indices) {
                if (road.region.quickGetEncodingRule(nameTypes[k])?.tag == "ref") return names[k]
            }
            return null
        }

        private fun closeQuietly(open: Open) {
            closeQuietly(open.file)
        }

        private fun closeQuietly(file: RandomAccessFile) {
            try {
                file.close()
            } catch (e: IOException) {
                // Nothing to do: the file was only read.
            }
        }
    }

    /**
     * The pieces of road earlier alternatives drove, each to cost its time again: every step
     * between two neighbouring points of a road, either way, as BRouter prices the links an
     * earlier route used. Not whole roads: a motorway both routes start on would then be
     * dearer for the alternative's every kilometre of it, including the ones only it drives.
     */
    private class UsedRoads {
        /** Per road id, the lower point index of every step driven. */
        private val steps = HashMap<Long, BitSet>()

        fun add(segments: List<RouteSegmentResult>) {
            for (segment in segments) {
                val driven = steps.getOrPut(segment.getObject().id) { BitSet() }
                val step = if (segment.startPointIndex <= segment.endPointIndex) 1 else -1
                var point = segment.startPointIndex
                while (point != segment.endPointIndex) {
                    driven.set(min(point, point + step))
                    point += step
                }
            }
        }

        /** Whether the step from [from] to the neighbouring [to] of road [id] was driven. */
        fun drove(id: Long, from: Int, to: Int): Boolean = steps[id]?.get(min(from, to)) == true
    }

    /**
     * Traffic as per-step prices: [hard] circles close a step that enters them, [weighted]
     * ones charge seconds per metre inside. Circles are kept in metres around their centres,
     * with a box in OsmAnd's 31-bit tile units to skip the far ones cheaply.
     */
    private class StepPenalty(private val circles: List<Circle>) {
        class Circle(
            val lat: Double,
            val lon: Double,
            val radiusM: Double,
            /** Seconds per metre inside, or null for a closure. */
            val secondsPerMetre: Double?,
            val minX: Int,
            val maxX: Int,
            val minY: Int,
            val maxY: Int,
        )

        private val minX = circles.minOf { it.minX }
        private val maxX = circles.maxOf { it.maxX }
        private val minY = circles.minOf { it.minY }
        private val maxY = circles.maxOf { it.maxY }

        /** Seconds to add for the step from (x1, y1) to (x2, y2), or a negative for a closed one. */
        fun seconds(x1: Int, y1: Int, x2: Int, y2: Int): Float {
            if (max(x1, x2) < minX || min(x1, x2) > maxX || max(y1, y2) < minY || min(y1, y2) > maxY) return 0f
            var added = 0.0
            var lat1 = Double.NaN
            var lon1 = 0.0
            var lat2 = 0.0
            var lon2 = 0.0
            for (c in circles) {
                if (max(x1, x2) < c.minX || min(x1, x2) > c.maxX || max(y1, y2) < c.minY || min(y1, y2) > c.maxY) continue
                if (lat1.isNaN()) {
                    lat1 = MapUtils.get31LatitudeY(y1)
                    lon1 = MapUtils.get31LongitudeX(x1)
                    lat2 = MapUtils.get31LatitudeY(y2)
                    lon2 = MapUtils.get31LongitudeX(x2)
                }
                val inside = metresInside(lat1, lon1, lat2, lon2, c)
                if (inside <= 0.0) continue
                val price = c.secondsPerMetre ?: return -1f
                added += inside * price
            }
            return added.toFloat()
        }

        companion object {
            private const val METRES_PER_DEGREE = 6_371_000.0 * Math.PI / 180.0

            fun of(nogos: List<Nogo>, topSpeedKmh: Int): StepPenalty? {
                if (nogos.isEmpty()) return null
                val price = TrafficCost.secondPrice(topSpeedKmh)
                return StepPenalty(
                    nogos.map { nogo ->
                        val dLat = nogo.radiusM / METRES_PER_DEGREE
                        val dLon = nogo.radiusM / (METRES_PER_DEGREE * cos(Math.toRadians(nogo.latitude)).coerceAtLeast(0.01))
                        Circle(
                            nogo.latitude, nogo.longitude, nogo.radiusM, nogo.weight?.let { it / price },
                            MapUtils.get31TileNumberX(nogo.longitude - dLon), MapUtils.get31TileNumberX(nogo.longitude + dLon),
                            // Tile y grows southward.
                            MapUtils.get31TileNumberY(nogo.latitude + dLat), MapUtils.get31TileNumberY(nogo.latitude - dLat),
                        )
                    },
                )
            }

            /**
             * Metres of the straight step from point 1 to point 2 inside circle [c], on a
             * flat projection about its centre: steps are metres long, circles at most a few
             * hundred, so the earth's curve does not show.
             */
            fun metresInside(lat1: Double, lon1: Double, lat2: Double, lon2: Double, c: Circle): Double {
                val kx = METRES_PER_DEGREE * cos(Math.toRadians(c.lat))
                val ax = (lon1 - c.lon) * kx
                val ay = (lat1 - c.lat) * METRES_PER_DEGREE
                val dx = (lon2 - c.lon) * kx - ax
                val dy = (lat2 - c.lat) * METRES_PER_DEGREE - ay
                val a = dx * dx + dy * dy
                val r2 = c.radiusM * c.radiusM
                if (a == 0.0) return 0.0
                val b = 2 * (ax * dx + ay * dy)
                val cc = ax * ax + ay * ay - r2
                val disc = b * b - 4 * a * cc
                if (disc <= 0) return 0.0
                val root = sqrt(disc)
                val t1 = ((-b - root) / (2 * a)).coerceIn(0.0, 1.0)
                val t2 = ((-b + root) / (2 * a)).coerceIn(0.0, 1.0)
                return (t2 - t1) * sqrt(a)
            }
        }
    }

    /**
     * OsmAnd's car router with HeadLink's per-request prices on top: [penalties]' traffic,
     * and the time of every road in [used] charged again. OsmAnd asks
     * [defineRoutingObstacle] for each step its plain search takes, from the point before to
     * [point]; seconds come back and are added to the step, and a negative closes it.
     */
    private class PenaltyRouter(
        base: GeneralRouter,
        private val penalties: StepPenalty?,
        private val used: UsedRoads?,
    ) : GeneralRouter(base, base.parameterValues) {
        override fun defineRoutingObstacle(road: RouteDataObject, point: Int, isBackwardDir: Boolean): Float {
            val own = super.defineRoutingObstacle(road, point, isBackwardDir)
            if (own < 0) return own
            val previous = if (isBackwardDir) point + 1 else point - 1
            if (previous < 0 || previous >= road.pointsLength) return own
            val x1 = road.getPoint31XTile(previous)
            val y1 = road.getPoint31YTile(previous)
            val x2 = road.getPoint31XTile(point)
            val y2 = road.getPoint31YTile(point)
            var added = 0f
            if (penalties != null) {
                val traffic = penalties.seconds(x1, y1, x2, y2)
                if (traffic < 0) return -1f
                added += traffic
            }
            if (used != null && used.drove(road.id, previous, point)) {
                val speed = defineRoutingSpeed(road, !isBackwardDir) * defineSpeedPriority(road, !isBackwardDir)
                if (speed > 0) added += (MapUtils.squareRootDist31(x1, y1, x2, y2) / min(speed, maxSpeed)).toFloat()
            }
            return own + added
        }
    }
}
