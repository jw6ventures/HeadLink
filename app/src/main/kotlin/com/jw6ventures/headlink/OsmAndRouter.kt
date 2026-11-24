package com.jw6ventures.headlink

import net.osmand.ResultMatcher
import net.osmand.binary.BinaryMapAddressReaderAdapter
import net.osmand.binary.BinaryMapIndexReader
import net.osmand.binary.RouteDataObject
import net.osmand.data.City
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * OsmAnd's own router (vendor/osmand) over OsmAnd's `.obf` maps: the same maps address
 * search reads, and the app's one routing engine (P6.3). Pure JVM, so the host tests run
 * this same class.
 *
 * **Names and signs.** OsmAnd's data keeps road names, refs, exit numbers, `destination`
 * signs and turn lanes, and its router derives its turns from them, so a route arrives
 * already named and signed ([toRoute]).
 *
 * **Search.** With nothing to steer round, the maps' precomputed highway hierarchy (HH)
 * does the long-distance work, which is what lets a phone route across a state in seconds;
 * OsmAnd falls back to its plain search where a map has no HH section. Anything that
 * prices roads per request (live traffic, towns to go round, the roads an earlier
 * alternative used) is invisible to HH's precomputed shortcuts, so such a request uses the
 * plain search, which asks [RequestRouter] about every road and step it takes.
 *
 * **Tracks** ([RouteOptions.allowTracks]). OsmAnd's car profile routes over tracks and
 * unclassified-yet `highway=road`; HeadLink's switch refuses them unless allowed
 * ([RequestRouter.acceptLine]). HH's shortcuts were built with them, so an HH route is
 * kept only when it drives none, and searched again plainly otherwise: a route that never
 * goes near a track costs nothing more. A start or destination only a track reaches is
 * refused ([START_BY_TRACK], [DESTINATION_BY_TRACK]) rather than routed to a road well
 * short of it.
 *
 * **Towns** ([RouteOptions.bypassTowns]). Every step inside a city's or town's circle
 * costs seconds per metre on top of its own time ([Town]), so the search goes round where
 * the way round costs less than the town; a destination inside one is still reached.
 *
 * **Traffic** ([RouteRequest.nogos]). A hard point closes every step of road inside its
 * circle. A weighted one adds time to each step in proportion to the metres of it inside
 * the circle: its weight is cost per metre at the request's top speed ([TrafficCost]),
 * turned into seconds by [TrafficCost.secondPrice]. OsmAnd adds every step's time, so
 * overlapping circles add up. A request that finds no route with its closures closed is
 * tried once more with each closure passable at a high price, so a vehicle already on a
 * closed road still gets its way out ([cutOff]).
 *
 * **Direction.** [RouteRequest.startBearing] goes to OsmAnd as the initial direction, and
 * the car profile charges 300 s for setting off the other way, so a re-route does not
 * begin by turning back.
 *
 * **Alternatives** ([RouteRequest.alternative]): alternative *k* is searched with every
 * road the routes before it used costing its time over again, so it looks for a different
 * corridor.
 *
 * **Regions.** OsmAnd's regional maps meet only where their builder cut them, and some do
 * not meet at all without a third: a route between two maps that no road joins fails
 * with [NOT_CONNECTED].
 *
 * Not thread-safe: one routing thread ([RoutePlanner]). Readers stay open between requests
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
    class MapFile @JvmOverloads constructor(
        val id: String,
        val name: String,
        val path: String,
        private val opener: (() -> RandomAccessFile)? = null,
    ) {
        /**
         * A new handle on the file, the caller's to close: [opener]'s when given (a file
         * from the maps folder, which has a descriptor and no path this app may open; see
         * [PositionalFile]), else [path] opened.
         */
        @Throws(IOException::class)
        fun open(): RandomAccessFile = opener?.invoke() ?: RandomAccessFile(path, "r")

        companion object {
            @JvmStatic
            fun of(file: File): MapFile = MapFile("${file.absolutePath}|${file.length()}|${file.lastModified()}", file.name, file.absolutePath)
        }
    }

    private class Open(val map: MapFile, val file: RandomAccessFile, val reader: BinaryMapIndexReader)

    /**
     * A diagnostic search (one only asked to judge a route already found) ran out of its
     * time: the route it was judging stands. Never thrown out of [compute].
     */
    private class Expired : Exception()

    /** A search's answer: the route, and OsmAnd's segments for [alternative] to price on the next one. */
    private class Found(val route: Route, val segments: List<RouteSegmentResult>)

    private val open = LinkedHashMap<String, Open>()

    /** Every city and town in the open maps ([Town.of]), and the ids of the maps they were read from. */
    private var towns: List<Town> = emptyList()
    private var townsFrom: Set<String>? = null

    @Throws(RoutingException::class)
    override fun compute(request: RouteRequest, maxRunningMs: Long, started: (SearchHandle) -> Unit): Route {
        val readers = readers()
        uncovered(request, readers)?.let { throw RoutingException(it) }
        if (request.alternative > 0) return alternative(request, readers, maxRunningMs, started)
        return withTraffic(request, readers, maxRunningMs, started, used = null).route
    }

    /**
     * Why no map's routing data holds the request's ends, or null when they are held:
     * [NOT_COVERED] or [MIRRORED] for the destination, [STOP_NOT_COVERED] for a via point
     * when the destination itself is held. Checked before any search: OsmAnd would
     * otherwise end the route on whatever road it reaches nearest, or search to its time
     * limit for nothing. Only the files with routing data are open here ([readers]), so a
     * basemap whose bounds span the planet never counts.
     *
     * Conservative: a point counts as held when the box [END_SNAP_M] round it meets any
     * top-level routing box, so a pin just outside a map's edge that OsmAnd would snap
     * onto a road inside it is routed, not refused. A miss here is certain; a hit can still
     * fail the search.
     */
    private fun uncovered(request: RouteRequest, readers: List<BinaryMapIndexReader>): String? {
        val boxes = readers.flatMap { reader -> reader.routingIndexes.flatMap { it.subregions.orEmpty() } }
        fun covered(lat: Double, lon: Double): Boolean {
            val dLat = END_SNAP_M / METRES_PER_DEGREE
            val dLon = dLat / max(cos(Math.toRadians(lat)), 0.01)
            val left = MapUtils.get31TileNumberX(max(lon - dLon, -180.0))
            val right = MapUtils.get31TileNumberX(min(lon + dLon, 180.0))
            val top = MapUtils.get31TileNumberY(min(lat + dLat, 85.0))
            val bottom = MapUtils.get31TileNumberY(max(lat - dLat, -85.0))
            return boxes.any { it.left <= right && it.right >= left && it.top <= bottom && it.bottom >= top }
        }
        if (!covered(request.toLat, request.toLon)) {
            val mirrored = DestinationParser.mirroredLongitude(request.toLat, request.toLon, ::covered)
            return if (mirrored != null) MIRRORED else NOT_COVERED
        }
        val viaLat = request.viaLat ?: return null
        return if (covered(viaLat, request.viaLon!!)) null else STOP_NOT_COVERED
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
                file = map.open()
                // A File only for its name: OsmAnd reads the region from it, and the path
                // may say nothing of the file's own name.
                val named = object : File(map.path) {
                    override fun getName(): String = map.name
                }
                val reader = BinaryMapIndexReader(file, named)
                if (reader.routingIndexes.isEmpty()) {
                    // An address- or POI-only file: nothing to route on.
                    file.close()
                    continue
                }
                open[map.id] = Open(map, file, reader)
            } catch (e: IOException) {
                ObfReaderFailures.note(e)
                file?.let { closeQuietly(it) }
            } catch (e: RuntimeException) {
                // A malformed file is skipped, never a crash on the routing thread.
                ObfReaderFailures.note(e)
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
    ): Found {
        val nogos = request.nogos
        try {
            val found = search(request, readers, nogos, used, maxRunningMs, started)
            if (nogos.none { it.hard } || !cutOff(found.route, request, readers, used, maxRunningMs, started)) {
                return Found(found.route.withNogos(nogos.size, false), found.segments)
            }
        } catch (e: RoutingException) {
            if (e.stopped || nogos.none { it.hard }) throw e
        }
        val opened = nogos.map { nogo ->
            if (nogo.hard) nogo.copy(weight = TrafficCost.openedClosureWeight(nogo.radiusM, request.options.topSpeedKmh)) else nogo
        }
        val found = search(request, readers, opened, used, maxRunningMs, started)
        return Found(found.route.withNogos(opened.size, true), found.segments)
    }

    /**
     * Whether [request]'s closures cut [route], searched with them, off from its start or
     * its destination. OsmAnd ends a route on the nearest road it can reach, however far
     * from the destination that is, and when no road out of the start gets anywhere it
     * starts the route on another road near it; so an end, or a start, more than
     * [END_SNAP_M] off can mean the closures cut it off, which is the same as no route. Or
     * the place is simply that far from any road the route may use: then the route without
     * the closures is as far off there ([displaced]), and the closures are not to blame.
     * That second search runs only for a route that far off, so the usual one costs
     * nothing more. It is a diagnostic: when it fails, or runs out of time, the closed
     * route stands; only the caller's own stop ends the request.
     */
    private fun cutOff(
        route: Route,
        request: RouteRequest,
        readers: List<BinaryMapIndexReader>,
        used: UsedRoads?,
        maxRunningMs: Long,
        started: (SearchHandle) -> Unit,
    ): Boolean {
        val start = startDistance(route, request)
        val end = endDistance(route, request)
        if (start <= END_SNAP_M && end <= END_SNAP_M) return false
        val free = try {
            search(request, readers, request.nogos.filter { !it.hard }, used, maxRunningMs, started, diagnostic = true).route
        } catch (e: Expired) {
            return false
        } catch (e: RoutingException) {
            if (e.stopped) throw e
            return false
        }
        return displaced(start, startDistance(free, request)) || displaced(end, endDistance(free, request))
    }

    /**
     * Alternative *k*: routes 0 to *k* in turn, each searched with the roads of
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
            val found = withTraffic(asked, readers, maxRunningMs, started, used.takeIf { k > 0 })
            used.add(found.segments)
            route = found.route
        }
        return route!!
    }

    /**
     * One route for [request] with [nogos] and [used] priced in. With nothing priced per
     * request the highway hierarchy is tried first. It knows nothing of [RequestRouter]'s
     * rules, so its route is kept only when it drives no road those rules refuse (tracks,
     * unless allowed); else, and whenever anything is priced, the plain search, which asks
     * [RequestRouter] about every road and step. A [diagnostic] search throws [Expired]
     * when it runs out of time.
     */
    private fun search(
        request: RouteRequest,
        readers: List<BinaryMapIndexReader>,
        nogos: List<Nogo>,
        used: UsedRoads?,
        maxRunningMs: Long,
        started: (SearchHandle) -> Unit,
        diagnostic: Boolean = false,
    ): Found {
        val options = request.options
        val townCircles = if (options.bypassTowns) townsAround(request, maxRunningMs, started, diagnostic) else emptyList()
        val penalties = StepPenalty.of(nogos, townCircles, options.topSpeedKmh)
        val from = LatLon(request.fromLat, request.fromLon)
        val to = LatLon(request.toLat, request.toLon)
        val via = request.viaLat?.let { listOf(LatLon(it, request.viaLon!!)) }
        var withTracks: Found? = null
        if (penalties == null && used == null && hierarchyCovers(readers, listOf(from) + via.orEmpty() + to)) {
            val found = searchOnce(request, readers, from, to, via, null, maxRunningMs, started, diagnostic)
            if (options.allowTracks || found.segments.none { it.getObject().highway in TRACKS }) return found
            withTracks = found
        }
        val found = searchOnce(request, readers, from, to, via, RequestRouter.Rules(!options.allowTracks, penalties, used), maxRunningMs, started, diagnostic)
        if (!options.allowTracks) {
            trackOnlyEnd(found.route, withTracks?.route, request, readers, from, to, via, penalties, used, maxRunningMs, started)
                ?.let { throw RoutingException(it) }
        }
        return found
    }

    /**
     * Why [refused], searched with tracks refused, does not reach [request]'s start or
     * destination, or null when it does: [START_BY_TRACK] or [DESTINATION_BY_TRACK]. A
     * refused track is not loaded at all, so an end on one is snapped to the nearest other
     * road, which can be hundreds of metres off, and the follower would announce arrival
     * there. Judged by [displaced] against the same request with tracks allowed: the
     * hierarchy's route when it was searched ([withTracks]), else one searched now, only for
     * a route whose start or end is that far off. That search is a diagnostic: if it fails
     * or runs out of time, [refused] stands.
     */
    private fun trackOnlyEnd(
        refused: Route,
        withTracks: Route?,
        request: RouteRequest,
        readers: List<BinaryMapIndexReader>,
        from: LatLon,
        to: LatLon,
        via: List<LatLon>?,
        penalties: StepPenalty?,
        used: UsedRoads?,
        maxRunningMs: Long,
        started: (SearchHandle) -> Unit,
    ): String? {
        val start = startDistance(refused, request)
        val end = endDistance(refused, request)
        if (start <= END_SNAP_M && end <= END_SNAP_M) return null
        val reference = withTracks ?: try {
            searchOnce(request, readers, from, to, via, RequestRouter.Rules(false, penalties, used), maxRunningMs, started, diagnostic = true).route
        } catch (e: Expired) {
            return null
        } catch (e: RoutingException) {
            if (e.stopped) throw e
            return null
        }
        return when {
            displaced(start, startDistance(reference, request)) -> START_BY_TRACK
            displaced(end, endDistance(reference, request)) -> DESTINATION_BY_TRACK
            else -> null
        }
    }

    /**
     * One OsmAnd search: over the highway hierarchy when [rules] is null, else the plain
     * search with [rules] applied through [RequestRouter]. Its handle's stop always ends
     * the request; running out of time or memory throws [Expired] instead when the search
     * is a [diagnostic].
     */
    private fun searchOnce(
        request: RouteRequest,
        readers: List<BinaryMapIndexReader>,
        from: LatLon,
        to: LatLon,
        via: List<LatLon>?,
        rules: RequestRouter.Rules?,
        maxRunningMs: Long,
        started: (SearchHandle) -> Unit,
        diagnostic: Boolean,
    ): Found {
        val options = request.options
        val direction = request.startBearing?.let { Math.toRadians(it) }
        val config = builder().build(PROFILE, direction, memoryLimits(), profileParameters(options))
        val frontEnd = RoutePlannerFrontEnd()
        if (rules == null) {
            frontEnd.setDefaultHHRoutingConfig()
        } else if (rules.any) {
            config.router = RequestRouter(config.router, rules)
        }
        // COMPLEX: past 18 km OsmAnd first routes over the maps' base roads and then searches
        // the detail only within 3 km of that line. NORMAL searches every road out to the
        // destination, and on the phone's 85 MB budget a 100 km plain search (traffic, an
        // alternative, or a hierarchy that cannot help) ran out of memory: no route at all.
        val context = frontEnd.buildRoutingContext(config, null, readers.toTypedArray(), RoutePlannerFrontEnd.RouteCalculationMode.COMPLEX)
        context.leftSideNavigation = false
        val progress = RouteCalculationProgress()
        context.calculationProgress = progress
        val stopped = AtomicBoolean()
        started(
            SearchHandle {
                stopped.set(true)
                progress.isCancelled = true
            },
        )
        val timer = TIMER.schedule({ progress.isCancelled = true }, maxRunningMs, TimeUnit.MILLISECONDS)
        val result = try {
            frontEnd.searchRoute(context, from, to, via)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RoutingException("routing interrupted", true)
        } catch (e: IOException) {
            throw RoutingException("map read failed: ${e.message}")
        } catch (e: RuntimeException) {
            // OsmAnd's memory guard throws IllegalStateException, and its message carries the
            // start and destination. Only the kind goes on: route errors reach the report.
            // Its search throws IllegalArgumentException when it has run out of road from
            // one end without meeting the other ([NOT_CONNECTED]).
            throw RoutingException(
                when (e) {
                    is IllegalStateException -> "out of routing memory"
                    is IllegalArgumentException -> if (e.message?.startsWith(OSMAND_NOT_FOUND) == true) NOT_CONNECTED else "routing failed: IllegalArgumentException"
                    else -> "routing failed: ${e.javaClass.simpleName}"
                },
            )
        } finally {
            timer.cancel(false)
        }
        if (progress.isCancelled) {
            if (stopped.get()) throw RoutingException("routing stopped", true)
            if (diagnostic) throw Expired()
            throw RoutingException(if (progress.memoryLimitExceeded) "out of routing memory" else "routing stopped", true)
        }
        if (result == null || !result.isCorrect) throw RoutingException(result?.error?.let(::placeless) ?: "no route found")
        return Found(toRoute(result.list, options, request), result.list)
    }

    /**
     * The cities and towns to go round on [request]'s way: those within [TOWN_MARGIN_M] of
     * the box round its ends, so a search's per-step check stays short, less any whose
     * circle holds the start, the stop or the destination. There is no going round the town
     * a trip starts or ends in, and pricing it would only make the search spread through
     * every street of it: Austin to Temple with the start's and the destination's cities
     * priced ran out of OsmAnd's 85 MB in 16 s; without them it took 1 s. Longitudes are
     * taken as offsets from the start's, so a trip or a town across 180° is boxed right.
     *
     * The open maps' list is read once per set of open maps, under a handle and the
     * request's time like a search, so a newer request need not wait for it; a list cut
     * short is never kept.
     */
    private fun townsAround(request: RouteRequest, maxRunningMs: Long, started: (SearchHandle) -> Unit, diagnostic: Boolean): List<Town> {
        val ids = open.keys.toSet()
        if (ids != townsFrom) {
            val stopped = AtomicBoolean()
            started(SearchHandle { stopped.set(true) })
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxRunningMs)
            val read = Town.of(open.values.toList()) { stopped.get() || System.nanoTime() > deadline }
            if (read == null) {
                if (!stopped.get() && diagnostic) throw Expired()
                throw RoutingException("routing stopped", true)
            }
            towns = read
            townsFrom = ids
        }
        val lats = listOfNotNull(request.fromLat, request.toLat, request.viaLat)
        val offsets = listOfNotNull(request.fromLon, request.toLon, request.viaLon).map { wrapDegrees(it - request.fromLon) }
        val dLat = TOWN_MARGIN_M / METRES_PER_DEGREE
        val dLon = dLat / max(cos(Math.toRadians(lats.maxOf { abs(it) })), 0.01)
        val south = lats.min() - dLat
        val north = lats.max() + dLat
        val west = offsets.min() - dLon
        val east = offsets.max() + dLon
        return towns.filter { town ->
            town.lat in south..north && wrapDegrees(town.lon - request.fromLon) in west..east &&
                lats.indices.none { MapUtils.getDistance(town.lat, town.lon, lats[it], request.fromLon + offsets[it]) < town.radiusM }
        }
    }

    companion object {
        private const val PROFILE = "car"

        /**
         * The failure for a destination no open map's routing data holds, no maps open at
         * all included. Place-free, as every route error is: it reaches the report.
         * [Routing.coverageText] tells the driver which map is missing.
         */
        const val NOT_COVERED = "destination outside the OsmAnd maps"

        /** [NOT_COVERED], with the destination's mirrored longitude inside one: a dropped minus sign. */
        const val MIRRORED = "destination outside the OsmAnd maps, its mirror inside"

        /** The destination is held, but the route's stop (its via point) is outside every map. */
        const val STOP_NOT_COVERED = "stop outside the OsmAnd maps"

        /** Whether [error] is one of the coverage failures above: no search was made. */
        fun coverage(error: String?): Boolean = error == NOT_COVERED || error == MIRRORED || error == STOP_NOT_COVERED

        /**
         * The failure when the search ran out of road on one leg (start to stop, stop to
         * destination, or start to destination) without meeting its other end: every point
         * is on the maps, but no road in them joins them all. OsmAnd does not say which leg,
         * so neither does this. Seen when a route crosses from one OsmAnd region to another
         * that does not adjoin it, the region between them not being among the maps:
         * OsmAnd's Waco and Dallas maps stop 8 km apart (32.13 and 32.20 °N), the Tyler map
         * holding the strip between, and Waco to Dallas failed with only the first two
         * (2026-11-24).
         */
        const val NOT_CONNECTED = "no road joins all the route's points in the OsmAnd maps; a map between them may be missing"

        /** How OsmAnd's own messages for [NOT_CONNECTED] begin ("...from selected start point", "...to selected target point"). */
        private const val OSMAND_NOT_FOUND = "Route is not found"

        /**
         * With tracks refused, the route could only start on another road well away from the
         * start, the start being on (or by) a track ([trackOnlyEnd]).
         */
        const val START_BY_TRACK = "start reachable only by a track; Allow tracks is off"

        /**
         * With tracks refused, the route could only end on another road well away from the
         * destination, which a track reaches: no route, rather than an arrival announced
         * short of it ([trackOnlyEnd]).
         */
        const val DESTINATION_BY_TRACK = "destination reachable only by a track; Allow tracks is off"

        private const val METRES_PER_DEGREE = 111_320.0

        /**
         * How far from the destination a route with closures may end and still count as
         * reaching it, metres: about how far a waypoint may lie from the road it is snapped to.
         */
        const val END_SNAP_M = 250.0

        /**
         * Whether a route's start or end [actualM] from its place has been pushed off by a
         * rule (closures, refused tracks), against [referenceM], the same request's distance
         * without the rule. Where the reference reaches the place (within [END_SNAP_M]), any
         * miss past [END_SNAP_M] is the rule's doing; where even the reference misses, only
         * a miss [END_SNAP_M] further than the reference's is.
         */
        @JvmStatic
        fun displaced(actualM: Double, referenceM: Double): Boolean =
            if (referenceM <= END_SNAP_M) actualM > END_SNAP_M else actualM > referenceM + END_SNAP_M

        /** [degrees] brought into -180..180, for a longitude difference across 180°. */
        private fun wrapDegrees(degrees: Double): Double = ((degrees % 360.0) + 540.0) % 360.0 - 180.0

        /**
         * The road classes "Allow tracks" opens: farm and forest tracks, and `highway=road`,
         * a road whose class nobody has mapped yet. OsmAnd's car profile routes over both.
         */
        private val TRACKS = setOf("track", "road")

        /**
         * How far past the box round a request's ends a town still counts for going round
         * towns, metres: a city's circle (10 km) and room for the detour round it.
         */
        private const val TOWN_MARGIN_M = 25_000.0

        /**
         * Whether OsmAnd's highway hierarchy can carry a route through [points]: some one
         * build of the maps (one HH edition) has road data at every point. Each build's
         * hierarchy is its own network, and maps downloaded at different times are
         * different builds. With none covering every point, OsmAnd still picks the newest
         * build near the route, fails to join it to the far end, retries every nearby
         * start, and only then falls back to the plain search: 30 to 50 s wasted on a
         * desktop for Austin to San Antonio over July and September maps, several times
         * that on the phone. Asked here, the answer costs a few index reads.
         */
        @JvmStatic
        fun hierarchyCovers(readers: List<BinaryMapIndexReader>, points: List<LatLon>): Boolean {
            val builds = HashMap<Long, MutableList<BinaryMapIndexReader>>()
            for (reader in readers) {
                for (region in reader.hhRoutingIndexes) {
                    if (region.profile == PROFILE) builds.getOrPut(region.edition) { ArrayList() } += reader
                }
            }
            return try {
                builds.values.any { build ->
                    points.all { point ->
                        val x = MapUtils.get31TileNumberX(point.longitude)
                        val y = MapUtils.get31TileNumberY(point.latitude)
                        build.any { it.containsActualRouteData(x, y, HashSet()) }
                    }
                }
            } catch (e: IOException) {
                // Unreadable here, so the plain search, which needs no hierarchy.
                false
            }
        }

        /**
         * [error] without coordinates. OsmAnd's own messages name the start and the
         * destination by latitude and longitude, and route errors reach the saved report.
         */
        @JvmStatic
        fun placeless(error: String): String = error.replace(Regex("-?\\d{1,3}\\.\\d{3,}"), "#")

        /** Metres from [route]'s start to [request]'s start. */
        private fun startDistance(route: Route, request: RouteRequest): Double =
            MapUtils.getDistance(route.latitudes[0], route.longitudes[0], request.fromLat, request.fromLon)

        /** Metres from [route]'s end to [request]'s destination. */
        private fun endDistance(route: Route, request: RouteRequest): Double {
            val last = route.points - 1
            return MapUtils.getDistance(route.latitudes[last], route.longitudes[last], request.toLat, request.toLon)
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
         * allowing tracks have no counterpart in OsmAnd's car profile and are left out:
         * [RequestRouter] applies them. The top speed is OsmAnd's `max_speed`, in metres a
         * second.
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
            // Per point, for the learned pace: the class of the road from it on, and whether
            // another road joins there (OsmAnd's attached roads).
            val classes = ArrayList<Int>()
            val junctions = ArrayList<Boolean>()
            var attachedKnown = false
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
                val roadClass = PaceRoad.roadOf(road.highway)
                var walked = 0.0
                point = segment.startPointIndex
                var k = -1
                while (true) {
                    if (k >= 0) walked += pieces[k]
                    val lat = MapUtils.get31LatitudeY(road.getPoint31YTile(point))
                    val lon = MapUtils.get31LongitudeX(road.getPoint31XTile(point))
                    val joined = joins(segment, point)
                    if (joined != null) attachedKnown = true
                    if (!(k < 0 && shared)) {
                        lats += lat
                        lons += lon
                        times += (clock + if (lengths > 0) seconds * walked / lengths else 0.0).toFloat()
                        classes += roadClass
                        junctions += joined == true
                    } else {
                        // The shared point starts this segment's stretch, and is a junction if either side says so.
                        classes[classes.size - 1] = roadClass
                        if (joined == true) junctions[junctions.size - 1] = true
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
            val paceClasses = paceClasses(lats, lons, classes, junctions, attachedKnown)
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
                paceClasses,
            ).withTimeScale(options.timeFactor)
        }

        /**
         * Whether a road other than the route's joins [segment] at its [point]: true or
         * false, or null where OsmAnd attached no roads to the segment at all (a segment
         * that never went through its result preparation).
         */
        private fun joins(segment: RouteSegmentResult, point: Int): Boolean? = try {
            segment.getAttachedRoutes(point).any { PaceRoad.countsAsJunction(it.getObject().highway) }
        } catch (e: NullPointerException) {
            // getAttachedRoutes reads an array that only result preparation creates.
            null
        } catch (e: ArrayIndexOutOfBoundsException) {
            null
        }

        /** Each point's [PaceRoad] code from its road class and the junctions around it. */
        @JvmStatic
        fun paceClasses(
            lats: List<Double>, lons: List<Double>, classes: List<Int>, junctions: List<Boolean>, attachedKnown: Boolean,
        ): ByteArray {
            val n = lats.size
            val along = DoubleArray(n)
            for (i in 1 until n) along[i] = along[i - 1] + MapUtils.getDistance(lats[i - 1], lons[i - 1], lats[i], lons[i])
            val densities = if (attachedKnown) PaceRoad.densities(along, junctions.toBooleanArray()) else IntArray(n)
            return ByteArray(n) { PaceRoad.code(classes[it], densities[it]) }
        }

        /**
         * OsmAnd's [turn] as the [TurnCommand] command the rest of the app reads. A keep
         * from a motorway or trunk onto its link is the exit it is.
         * Null for a turn that is no maneuver: OsmAnd's off-route marker.
         */
        @JvmStatic
        fun command(turn: TurnType, before: RouteSegmentResult?, segment: RouteSegmentResult): Int? = when (turn.value) {
            TurnType.C -> TurnCommand.CONTINUE
            TurnType.TL -> TurnCommand.TURN_LEFT
            TurnType.TSLL -> TurnCommand.TURN_SLIGHT_LEFT
            TurnType.TSHL -> TurnCommand.TURN_SHARP_LEFT
            TurnType.TR -> TurnCommand.TURN_RIGHT
            TurnType.TSLR -> TurnCommand.TURN_SLIGHT_RIGHT
            TurnType.TSHR -> TurnCommand.TURN_SHARP_RIGHT
            TurnType.KL -> if (offRamp(before, segment)) TurnCommand.EXIT_LEFT else TurnCommand.KEEP_LEFT
            TurnType.KR -> if (offRamp(before, segment)) TurnCommand.EXIT_RIGHT else TurnCommand.KEEP_RIGHT
            TurnType.TU -> TurnCommand.U_TURN_180
            TurnType.TRU -> TurnCommand.U_TURN_RIGHT
            TurnType.RNDB -> TurnCommand.ROUNDABOUT
            TurnType.RNLB -> TurnCommand.ROUNDABOUT_LEFT
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
         * The exit number and sign text where the segment at [s] begins, only when that
         * segment is a ramp (`*_link`): a route that keeps to the motorway past a junction
         * takes no exit, and its number would be a sign for a road not driven. The text is
         * OsmAnd's own destination for the maneuver. The number is the link's
         * `junction:ref` where the map gives it one, else the `ref` of the
         * `motorway_junction` node the route leaves the motorway at, read from either road
         * at that point: OsmAnd's maps keep the node on the motorway, and only some keep it
         * on the link too.
         */
        private fun exitSign(segments: List<RouteSegmentResult>, s: Int): ExitSign? {
            val segment = segments[s]
            if (segment.getObject().highway?.endsWith("_link") != true) return null
            val before = segments.getOrNull(s - 1)
            val number = (
                segment.getObject().junctionRef
                    ?: before?.let { junctionRef(it.getObject(), it.endPointIndex) }
                    ?: junctionRef(segment.getObject(), segment.startPointIndex)
                )?.takeIf { it.isNotBlank() }
            // The sign as the card words it: the road first, then the places, one " / " apart.
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
     * between two neighbouring points of a road, either way: the links an earlier route
     * used. Not whole roads: a motorway both routes start on would then be
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
     * A city or town from a map's address index, for going round towns: its place and the
     * circle OsmAnd itself gives its kind ([City.CityType.getRadius]: 10 km for a city,
     * 4 km for a town), and the seconds each metre of road inside costs.
     *
     * **Why a circle.** BRouter's car profile read a precomputed `estimated_town_class`
     * on every road, from how built up its surroundings were, and added 5 (the lightest
     * class) to 16 to its cost factor, which is cost per metre on top of a road's own.
     * OsmAnd's maps carry no such tag; what they do carry is every city and town with its
     * position, and OsmAnd's own address search already treats a city as reaching 10 km
     * from its centre and a town 4 km.
     *
     * **Why these prices.** One second of BRouter's cost was 19.0 at the default top speed,
     * so 5 per metre is 0.26 s a metre and 12, a mid class, 0.63: [TOWN_SECONDS_PER_M] and
     * [CITY_SECONDS_PER_M]. On the Austin and Waco maps (2026-11-24, OsmAndRoutingTest),
     * downtown Austin to downtown Temple went from 109.2 km and 70 min, 42.8 km of it
     * inside the circles on the way, to 132.4 km and 116 min with none inside, searched
     * in under a second: BRouter's own 109 to 132 km. Half those prices (0.30 and 0.13)
     * did worse, 146.9 km with 6.6 km still inside, so these were kept. Where circles
     * overlap, a step pays its dearest one only, so a metropolitan area's ring of suburbs
     * costs no more than its city.
     */
    private class Town(val lat: Double, val lon: Double, val radiusM: Double, val secondsPerMetre: Double) {
        companion object {
            /** Seconds added per metre inside a town's circle. */
            const val TOWN_SECONDS_PER_M = 0.26

            /** Seconds added per metre inside a city's circle. */
            const val CITY_SECONDS_PER_M = 0.63

            /**
             * The cities and towns of every map in [maps], each once (a place near a
             * region's edge is listed by both maps), or null when [stop] said to stop
             * before every map was read. Each map is read through a reader of its own on a
             * fresh handle: OsmAnd's address decoder leaves its input limits pushed when a
             * record fails to decode, and a routing reader left so would fail every route
             * read past the bad record. A map whose address index will not read is noted
             * and skipped; going round towns is never worth a failed route. The records
             * stream through OsmAnd's matcher, which also asks [stop] after each one, so no
             * map's whole list is held at once.
             */
            fun of(maps: List<Open>, stop: () -> Boolean): List<Town>? {
                val seen = HashSet<Long>()
                val towns = ArrayList<Town>()
                val matcher = BinaryMapIndexReader.buildAddressRequest(
                    object : ResultMatcher<City> {
                        override fun publish(city: City): Boolean {
                            val price = when (city.type) {
                                City.CityType.CITY -> CITY_SECONDS_PER_M
                                City.CityType.TOWN -> TOWN_SECONDS_PER_M
                                else -> return false
                            }
                            val at = city.location
                            if (at != null && seen.add(city.id)) towns += Town(at.latitude, at.longitude, city.type.radius, price)
                            return false
                        }

                        override fun isCancelled(): Boolean = stop()
                    },
                )
                for (map in maps) {
                    if (stop()) return null
                    var file: RandomAccessFile? = null
                    try {
                        file = map.map.open()
                        BinaryMapIndexReader(file, map.reader).getCities(matcher, BinaryMapAddressReaderAdapter.CityBlocks.CITY_TOWN_TYPE, null, null)
                    } catch (e: IOException) {
                        ObfReaderFailures.note(e)
                    } catch (e: RuntimeException) {
                        ObfReaderFailures.note(e)
                    } finally {
                        file?.let { closeQuietly(it) }
                    }
                }
                return if (stop()) null else towns
            }
        }
    }

    /**
     * Per-request prices on each step: traffic's [Nogo]s, where a hard circle closes a step
     * that enters it and a weighted one charges seconds per metre inside, all of them
     * adding up; and [Town]s, of which a step pays only the dearest. Circles are kept in
     * metres around their centres, with a box in OsmAnd's 31-bit tile units to skip the far
     * ones cheaply.
     */
    private class StepPenalty(private val circles: List<Circle>) {
        class Circle(
            val lat: Double,
            val lon: Double,
            val radiusM: Double,
            /** Seconds per metre inside, or null for a closure. */
            val secondsPerMetre: Double?,
            /** A [Town]'s circle rather than traffic's. */
            val town: Boolean,
        ) {
            private val dLat = radiusM / METRES_PER_DEGREE
            private val dLon = radiusM / (METRES_PER_DEGREE * cos(Math.toRadians(lat)).coerceAtLeast(0.01))

            // OsmAnd wraps a longitude past 180° round, so a circle across it has minX > maxX.
            val minX = MapUtils.get31TileNumberX(lon - dLon)
            val maxX = MapUtils.get31TileNumberX(lon + dLon)
            val wraps = minX > maxX

            // Tile y grows southward.
            val minY = MapUtils.get31TileNumberY(lat + dLat)
            val maxY = MapUtils.get31TileNumberY(lat - dLat)

            /** Whether the tile x range [lo]..[hi] of a step misses this circle's box, which may wrap round 180°. */
            fun missesX(lo: Int, hi: Int): Boolean = if (wraps) lo > maxX && hi < minX else hi < minX || lo > maxX
        }

        private val wraps = circles.any { it.wraps }
        private val minX = if (wraps) Int.MIN_VALUE else circles.minOf { it.minX }
        private val maxX = if (wraps) Int.MAX_VALUE else circles.maxOf { it.maxX }
        private val minY = circles.minOf { it.minY }
        private val maxY = circles.maxOf { it.maxY }

        /** Seconds to add for the step from (x1, y1) to (x2, y2), or a negative for a closed one. */
        fun seconds(x1: Int, y1: Int, x2: Int, y2: Int): Float {
            if (max(x1, x2) < minX || min(x1, x2) > maxX || max(y1, y2) < minY || min(y1, y2) > maxY) return 0f
            var traffic = 0.0
            var town = 0.0
            var lat1 = Double.NaN
            var lon1 = 0.0
            var lat2 = 0.0
            var lon2 = 0.0
            for (c in circles) {
                if (c.missesX(min(x1, x2), max(x1, x2)) || max(y1, y2) < c.minY || min(y1, y2) > c.maxY) continue
                if (lat1.isNaN()) {
                    lat1 = MapUtils.get31LatitudeY(y1)
                    lon1 = MapUtils.get31LongitudeX(x1)
                    lat2 = MapUtils.get31LatitudeY(y2)
                    lon2 = MapUtils.get31LongitudeX(x2)
                }
                val inside = metresInside(lat1, lon1, lat2, lon2, c)
                if (inside <= 0.0) continue
                val price = c.secondsPerMetre ?: return -1f
                if (c.town) town = max(town, inside * price) else traffic += inside * price
            }
            return (traffic + town).toFloat()
        }

        companion object {
            private const val METRES_PER_DEGREE = 6_371_000.0 * Math.PI / 180.0

            fun of(nogos: List<Nogo>, towns: List<Town>, topSpeedKmh: Int): StepPenalty? {
                if (nogos.isEmpty() && towns.isEmpty()) return null
                val price = TrafficCost.secondPrice(topSpeedKmh)
                return StepPenalty(
                    nogos.map { Circle(it.latitude, it.longitude, it.radiusM, it.weight?.let { w -> w / price }, false) } +
                        towns.map { Circle(it.lat, it.lon, it.radiusM, it.secondsPerMetre, true) },
                )
            }

            /**
             * Metres of the straight step from point 1 to point 2 inside circle [c], on a
             * flat projection about its centre: steps are metres long and circles at most
             * a city's 10 km, so the earth's curve does not show. Longitude differences are
             * taken the short way round, across 180° too.
             */
            fun metresInside(lat1: Double, lon1: Double, lat2: Double, lon2: Double, c: Circle): Double {
                val kx = METRES_PER_DEGREE * cos(Math.toRadians(c.lat))
                val ax = wrapDegrees(lon1 - c.lon) * kx
                val ay = (lat1 - c.lat) * METRES_PER_DEGREE
                val dx = wrapDegrees(lon2 - c.lon) * kx - ax
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
     * OsmAnd's car router with this request's own [Rules] on top. OsmAnd asks [acceptLine]
     * about every road its plain search loads, ends included, and [defineRoutingObstacle]
     * about each step it takes, from the point before to [point]; seconds come back and are
     * added to the step, and a negative closes it.
     */
    private class RequestRouter(base: GeneralRouter, private val rules: Rules) : GeneralRouter(base, base.parameterValues) {
        /**
         * [refuseTracks]: no [TRACKS] road at all, as BRouter's car profile had it unless
         * tracks were allowed. [penalties]: traffic and towns, per step. [used]: the roads
         * earlier alternatives drove, each step's time charged again.
         */
        class Rules(val refuseTracks: Boolean, val penalties: StepPenalty?, val used: UsedRoads?) {
            /** Whether any rule applies, so the router is worth wrapping. */
            val any: Boolean get() = refuseTracks || penalties != null || used != null
        }

        override fun acceptLine(way: RouteDataObject): Boolean =
            super.acceptLine(way) && !(rules.refuseTracks && way.highway in TRACKS)

        override fun defineRoutingObstacle(road: RouteDataObject, point: Int, isBackwardDir: Boolean): Float {
            val own = super.defineRoutingObstacle(road, point, isBackwardDir)
            if (own < 0 || (rules.penalties == null && rules.used == null)) return own
            val previous = if (isBackwardDir) point + 1 else point - 1
            if (previous < 0 || previous >= road.pointsLength) return own
            val x1 = road.getPoint31XTile(previous)
            val y1 = road.getPoint31YTile(previous)
            val x2 = road.getPoint31XTile(point)
            val y2 = road.getPoint31YTile(point)
            var added = 0f
            if (rules.penalties != null) {
                val priced = rules.penalties.seconds(x1, y1, x2, y2)
                if (priced < 0) return -1f
                added += priced
            }
            if (rules.used != null && rules.used.drove(road.id, previous, point)) {
                val speed = defineRoutingSpeed(road, !isBackwardDir) * defineSpeedPriority(road, !isBackwardDir)
                if (speed > 0) added += (MapUtils.squareRootDist31(x1, y1, x2, y2) / min(speed, maxSpeed)).toFloat()
            }
            return own + added
        }
    }
}

