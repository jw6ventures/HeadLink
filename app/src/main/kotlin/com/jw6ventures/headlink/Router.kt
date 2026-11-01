package com.jw6ventures.headlink

import btools.mapaccess.OsmNodePairSet
import btools.router.OsmNodeNamed
import btools.router.ProfileCache
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import btools.router.VoiceHintReader
import btools.util.CheapRuler
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Two points to route between, in degrees.
 *
 * A route is from somewhere to somewhere and nothing more: via points, no-go areas and
 * round trips are all things BRouter can do and none of them was in P6.3. One via point
 * came with the route preview, and no-go points with live traffic (P9.5).
 *
 * [startBearing] (P6.5) is the way the vehicle is already travelling, degrees clockwise
 * from north. A re-route sets it, because BRouter otherwise has no idea which way the
 * vehicle faces. On its own it is a weak hint with `car-vario`: turning round at the start
 * costs a flat 40 s, so in the P6.5 lab drive the first re-route after the missed turn
 * still began by going back, and a second re-route followed six seconds later. So
 * [Router] also prices the road just behind the vehicle ([Router.behind]), which makes
 * turning back cost about three and a half minutes in all.
 *
 * [options] are the Routing settings in force when the request was made, so a re-route
 * takes whatever the screen says now.
 *
 * [alternative] asks for BRouter's alternative route of that number, 0 being the best.
 * BRouter finds alternative k by searching k+1 times, each search pricing the roads the
 * earlier ones used, so the second costs about twice the first and the third three times.
 * Only the route preview asks for one.
 *
 * [viaLat] and [viaLon], both or neither, put one point the route must pass through
 * between the two ends. The preview's own alternatives are made this way ([AlternativeFinder]).
 *
 * [nogos] (P9.5) are live traffic's closures and jams as BRouter no-go points
 * ([TrafficNogos]): a hard one is a circle the route may not enter, a weighted one charges
 * the metres of road inside it times its weight. The weights are BRouter cost at [options]'
 * top speed ([TrafficCost]); [Router.compute] rescales them if it has to time the route
 * slower. Empty, the default, is a route exactly as before P9.
 */
class RouteRequest @JvmOverloads constructor(
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
    val startBearing: Double? = null,
    val options: RouteOptions = RouteOptions(),
    val alternative: Int = 0,
    val viaLat: Double? = null,
    val viaLon: Double? = null,
    val nogos: List<Nogo> = emptyList(),
) {
    init {
        require(valid(fromLat, fromLon) && valid(toLat, toLon)) { "coordinates out of range" }
        require(alternative in 0..MAX_ALTERNATIVE) { "alternative out of range" }
        require((viaLat == null) == (viaLon == null)) { "a via point needs both coordinates" }
        require(viaLat == null || valid(viaLat, viaLon!!)) { "via point out of range" }
        require(startBearing == null || startBearing.isFinite()) { "bearing out of range" }
        require(nogos.all { valid(it) }) { "no-go point out of range" }
    }

    /** This request with [nogos] in place of its own, everything else the same. */
    fun withNogos(nogos: List<Nogo>): RouteRequest =
        RouteRequest(fromLat, fromLon, toLat, toLon, startBearing, options, alternative, viaLat, viaLon, nogos)

    companion object {
        /** BRouter's own limit: `RoutingContext.getAlternativeIdx(0, 3)`. */
        const val MAX_ALTERNATIVE = 3

        private fun valid(lat: Double, lon: Double): Boolean =
            lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

        /** Whether [nogo] is one BRouter can be given: on the globe, a finite radius, a finite weight or none. */
        fun valid(nogo: Nogo): Boolean =
            valid(nogo.latitude, nogo.longitude) && nogo.radiusM.isFinite() && nogo.radiusM > 0.0 &&
                (nogo.weight == null || (nogo.weight.isFinite() && nogo.weight >= 0.0))

        /**
         * Reads `lat,lon lat,lon` — the order a person copies out of any map — with a space,
         * a semicolon or a newline between the two points. Null for anything else rather
         * than a guess: a route to a mistyped destination is worse than no route.
         */
        fun parse(text: String): RouteRequest? {
            val points = text.trim().split(Regex("[\\s;]+")).filter { it.isNotEmpty() }
            if (points.size != 2) return null
            val numbers = points.map { point ->
                val parts = point.split(',')
                if (parts.size != 2) return null
                val lat = parts[0].toDoubleOrNull() ?: return null
                val lon = parts[1].toDoubleOrNull() ?: return null
                lat to lon
            }
            if (numbers.any { (lat, lon) -> !valid(lat, lon) }) return null
            return RouteRequest(numbers[0].first, numbers[0].second, numbers[1].first, numbers[1].second)
        }
    }
}

/**
 * A computed route: the geometry, BRouter's own totals and timings, and the maneuvers it
 * derived along the geometry.
 *
 * What each maneuver *is* comes from BRouter's `VoiceHint` command, read through
 * [VoiceHintReader] (P6.5) because the vendored tree keeps it package-private. Nothing here
 * guesses a turn from the geometry: BRouter already weighed the roads it did not take.
 */
class Route @JvmOverloads constructor(
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    /** BRouter's track length, metres. */
    val distanceM: Int,
    /** BRouter's own travel-time estimate from the profile's speed model, seconds. */
    val seconds: Int,
    /** Index into the geometry of each maneuver BRouter derived, ascending. */
    val maneuverIndices: IntArray,
    /** Roundabout exit for the maneuver at the same position, or 0 when it is not one. */
    val exitNumbers: IntArray,
    /**
     * BRouter's command for the maneuver at the same position — a [VoiceHintReader]
     * constant. [Maneuvers] says what it means. There is no destination among them:
     * BRouter's `END` never survives its own post-processing, so [RouteFollower] adds one.
     */
    val commands: IntArray = IntArray(maneuverIndices.size) { VoiceHintReader.CONTINUE },
    /**
     * Seconds from the start to each point, from the profile's speed model: the time to a
     * maneuver is a difference of two of these, so it moves with the route rather than
     * with the vehicle's momentary speed. Empty when not known.
     */
    val pointSeconds: FloatArray = FloatArray(0),
    /**
     * Point index where each named stretch of road begins, ascending: the start and every
     * maneuver. Empty until [StreetNames] has read the map; BRouter's data has no names.
     */
    val roadStarts: IntArray = IntArray(0),
    /** The road each stretch in [roadStarts] drives, or null where the map names none. */
    val roadNames: Array<String?> = emptyArray(),
    /**
     * The top speed the route was timed at, km/h: the one asked for, or lower when BRouter
     * could not finish at it ([Router.compute]). 0 when not known.
     */
    val topSpeedKmh: Int = 0,
    /**
     * The point the route was made to pass through, [latitude, longitude], or null: set on
     * an alternative the preview or the dashboard offered, so re-routes and a restart can
     * keep to it ([Navigation.via]).
     */
    val via: DoubleArray? = null,
    /**
     * How many traffic no-go points BRouter was given for this route (P9.5), 0 when none.
     * Above 0, HERE's data helped choose it, so "Traffic © HERE" is owed while it is shown
     * (HERE's Platform Terms, 6.4a): whether the points actually moved it cannot be known
     * without a second search, and the attribution does not wait for one.
     */
    val nogoCount: Int = 0,
    /**
     * No route existed that kept out of every closure, so the closures were made passable
     * at a high price and this one goes through at least one ([Router.compute]): the
     * vehicle is on a closed road, or the destination is. A closure ahead on this route is
     * then not a reason to re-route again, which would only find it again.
     */
    val closuresOpened: Boolean = false,
    /**
     * The exit sign for the maneuver at the same position in [maneuverIndices], or null
     * where there is none or none is known. Empty until [ExitSigns] has read OsmAnd's
     * routing data; BRouter's has no exit numbers or destinations.
     */
    val exitSigns: Array<ExitSign?> = emptyArray(),
) {
    val points: Int get() = latitudes.size

    /** The sign for the maneuver at position [k] in [maneuverIndices], or null. */
    fun exitSign(k: Int): ExitSign? = exitSigns.getOrNull(k)

    /** Traffic nogos were in the search that made this route: see [nogoCount]. */
    val trafficShaped: Boolean get() = nogoCount > 0

    /**
     * This route with every time multiplied by [factor]: the Routing screen's time
     * adjustment ([RouteOptions.etaPercent]), applied once, where the route is made, so the
     * cluster, the trip panel, Home and the prompts' pace all read the same times.
     */
    fun withTimeScale(factor: Double): Route {
        if (factor == 1.0) return this
        return Route(
            latitudes, longitudes, distanceM, (seconds * factor).roundToInt(), maneuverIndices, exitNumbers, commands,
            FloatArray(pointSeconds.size) { (pointSeconds[it] * factor).toFloat() }, roadStarts, roadNames, topSpeedKmh, via,
            nogoCount, closuresOpened, exitSigns,
        )
    }

    /** This route with the road names [StreetNames] found. */
    fun withRoads(starts: IntArray, names: Array<String?>): Route {
        require(starts.size == names.size) { "one name per stretch" }
        return Route(
            latitudes, longitudes, distanceM, seconds, maneuverIndices, exitNumbers, commands,
            pointSeconds, starts, names, topSpeedKmh, via, nogoCount, closuresOpened, exitSigns,
        )
    }

    /** This route with the exit signs [ExitSigns] found, one per maneuver. */
    fun withExitSigns(signs: Array<ExitSign?>): Route {
        require(signs.size == maneuverIndices.size) { "one sign per maneuver" }
        return Route(
            latitudes, longitudes, distanceM, seconds, maneuverIndices, exitNumbers, commands,
            pointSeconds, roadStarts, roadNames, topSpeedKmh, via, nogoCount, closuresOpened, signs,
        )
    }

    /** This route marked as made with [count] traffic nogos, and whether closures were opened for it. */
    fun withNogos(count: Int, opened: Boolean): Route = Route(
        latitudes, longitudes, distanceM, seconds, maneuverIndices, exitNumbers, commands,
        pointSeconds, roadStarts, roadNames, topSpeedKmh, via, count, opened, exitSigns,
    )

    /**
     * The rest of this route from point [from] on: what is still ahead of the vehicle, as a
     * route of its own, with times counted from there. Names and maneuvers are kept where
     * they fall inside it.
     */
    fun remainder(from: Int): Route {
        val start = from.coerceIn(0, points - 2)
        if (start == 0) return this
        val count = points - start
        val keep = maneuverIndices.indices.filter { maneuverIndices[it] >= start }
        val base = pointSeconds.getOrElse(start) { 0f }
        val times = if (pointSeconds.size == points) FloatArray(count) { pointSeconds[start + it] - base } else FloatArray(0)
        val roads = roadStarts.indices.filter { k -> k + 1 >= roadStarts.size || roadStarts[k + 1] > start }
        val total = if (times.isNotEmpty()) times.last().roundToInt() else (seconds.toLong() * count / points).toInt()
        var metres = 0.0
        for (i in start until points - 1) {
            metres += RouteFollower.distance(latitudes[i], longitudes[i], latitudes[i + 1], longitudes[i + 1])
        }
        return Route(
            latitudes.copyOfRange(start, points), longitudes.copyOfRange(start, points), metres.roundToInt(), total,
            IntArray(keep.size) { maneuverIndices[keep[it]] - start }, IntArray(keep.size) { exitNumbers[keep[it]] },
            IntArray(keep.size) { commands[keep[it]] }, times,
            IntArray(roads.size) { maxOf(0, roadStarts[roads[it]] - start) }, Array(roads.size) { roadNames[roads[it]] },
            topSpeedKmh, via, nogoCount, closuresOpened,
            if (exitSigns.size == maneuverIndices.size) Array(keep.size) { exitSigns[keep[it]] } else emptyArray(),
        )
    }

    /** The road the stretch containing point [index] drives, or null when unnamed. */
    fun roadAt(index: Int): String? {
        var name: String? = null
        for (k in roadStarts.indices) {
            if (roadStarts[k] > index) break
            name = roadNames[k]
        }
        return name
    }
}

/** How one request ended. Exactly one of [route] and [error] is set. */
class RouteResult(
    /** The request's place in the order they were made; a later one supersedes it. */
    val sequence: Long,
    val route: Route?,
    val error: String?,
    /** Wall time on the routing thread, from picking the request up to having an answer. */
    val computeMs: Long,
    /** The thread that did the work, so a report can show it was not the render thread. */
    val thread: String,
    /** A newer request arrived first, so this one was abandoned or never started. */
    val superseded: Boolean,
    /** Wall time of the street-name pass after the search, within [computeMs]. */
    val namingMs: Long = 0,
)

/**
 * A route could not be had. [stopped] when the search was stopped rather than failed: a
 * newer request terminated it, or it ran out of time.
 */
class RoutingException @JvmOverloads constructor(message: String, val stopped: Boolean = false) : Exception(message)

/** Stops one search in progress, from any thread. Each engine hands one out as a search begins. */
fun interface SearchHandle {
    fun terminate()
}

/**
 * Something that computes a [Route]: BRouter over `.rd5` tiles ([Router]) or OsmAnd's router
 * over `.obf` maps ([OsmAndRouter]). Blocking, and used from one thread at a time.
 */
interface RouteEngine {
    /**
     * Computes one route or throws [RoutingException]. [started] receives a handle before
     * each search begins, which is the only way another thread can stop it; an engine may
     * search more than once for one request, and the handle is always the running search's.
     */
    @Throws(RoutingException::class)
    fun compute(request: RouteRequest, maxRunningMs: Long, started: (SearchHandle) -> Unit = {}): Route
}

/**
 * The engine each request asked for ([RouteOptions.osmAndRouting]), so a change on the
 * Routing screen takes effect with the next route and nothing has to be rebuilt. Each
 * engine is made on first use, on the routing thread.
 */
class EngineChoice(
    private val brouter: () -> RouteEngine,
    private val osmand: () -> RouteEngine,
) : RouteEngine {
    override fun compute(request: RouteRequest, maxRunningMs: Long, started: (SearchHandle) -> Unit): Route =
        (if (request.options.osmAndRouting) osmand() else brouter()).compute(request, maxRunningMs, started)
}

/**
 * BRouter over one segment directory and one profile. Blocking, and not thread-safe:
 * BRouter keeps its parsed profile in a process-wide cache, so everything goes through a
 * single [RoutePlanner] thread. Pure JVM, so the host tests exercise this same class.
 *
 * **Traffic no-go points** (P9.5) go to BRouter as `RoutingContext.nogopoints`,
 * made afresh for every search, so nothing of one request's traffic reaches the next.
 * What the vendored BRouter does with them, read from its code and measured on the Texas
 * tiles (docs/navigation.md, "Live traffic (HERE)"), and what is done about it here:
 *
 * - **One charge per link.** BRouter charges a nogo while it walks one link, the stretch
 *   of way between two junctions of its graph, and keeps only the *last* circle that
 *   link passed through, not their sum (`RoutingContext.calcDistance` sets `nogoCost`,
 *   `OsmPath` adds it once at the link's end). Interstate links run 45 m to 1.6 km, so
 *   points every 150 m are partly lost there; [TrafficCost] measures by how much and
 *   weighs accordingly. It cannot be summed here: `RoutingContext` is final.
 * - **A jam can open a closure.** For the same reason, a weighted circle after a hard one
 *   on the same link overwrites its block: a closure followed by a queue on one link
 *   was driven straight through. Hard points go last in the list, which settles it
 *   within one piece of the link, and every route is checked for passing through a
 *   closure it was given ([closuresCrossed]). One that does is searched again without the
 *   weighted points near that closure, and failing that without any.
 * - **A closure at either end.** BRouter drops any point whose circle holds the start or
 *   the destination as given, refuses a destination that lands in a hard one ("last wpt
 *   in restricted area"), and finds nothing for a vehicle already on a closed stretch,
 *   whose every way out is blocked. When a search with closures fails for any reason but
 *   being stopped, it is tried once more with every closure passable at
 *   [TrafficCost.OPENED_CLOSURE_SECONDS] a point: the vehicle stuck in a closure still
 *   gets its way out, and the route says so ([Route.closuresOpened]).
 */
class Router(
    private val segments: File,
    private val profile: File,
    /** BRouter's node-cache budget in MiB; 64 is upstream's own default for its service. */
    private val memoryClassMb: Int = 64,
) : RouteEngine {
    /**
     * Computes one route or throws [RoutingException] with BRouter's own message.
     *
     * [started] receives the engine before the search begins, which is the only handle
     * another thread has to stop it. With [RouteRequest.nogos] it may be called for more
     * than one search (see the class notes); it is always the running one.
     */
    @Throws(RoutingException::class)
    override fun compute(request: RouteRequest, maxRunningMs: Long, started: (SearchHandle) -> Unit): Route {
        if (!profile.isFile) throw RoutingException("no routing profile at ${profile.name}")
        if (!segments.isDirectory) throw RoutingException("no routing data installed")
        if (request.nogos.isEmpty()) return computeStepping(request, emptyList(), maxRunningMs, started)
        return try {
            computeKeepingClosures(request, maxRunningMs, started)
        } catch (e: RoutingException) {
            if (e.stopped || request.nogos.none { it.hard }) throw e
            val opened = request.nogos.map { nogo ->
                if (nogo.hard) nogo.copy(weight = TrafficCost.openedClosureWeight(nogo.radiusM, request.options.topSpeedKmh)) else nogo
            }
            computeStepping(request, opened, maxRunningMs, started).withNogos(opened.size, true)
        }
    }

    /**
     * The route with every closure kept closed. BRouter may let a jam's point overwrite a
     * closure's on the same link (class notes), so a route through a closure is searched
     * again: first without the weighted points within [CLOSURE_GUARD_M] of the closures it
     * crossed, then without any weighted point, when nothing is left to overwrite them.
     */
    private fun computeKeepingClosures(request: RouteRequest, maxRunningMs: Long, started: (SearchHandle) -> Unit): Route {
        var nogos = request.nogos
        for (attempt in 0 until 2) {
            val route = computeStepping(request, nogos, maxRunningMs, started)
            val crossed = closuresCrossed(route, request, nogos)
            if (crossed.isEmpty()) return route.withNogos(nogos.size, false)
            nogos = if (attempt == 0) {
                nogos.filter { nogo -> nogo.hard || crossed.none { metres(it.latitude, it.longitude, nogo.latitude, nogo.longitude) <= CLOSURE_GUARD_M } }
            } else {
                nogos.filter { it.hard }
            }
        }
        return computeStepping(request, nogos, maxRunningMs, started).withNogos(nogos.size, false)
    }

    /**
     * BRouter's last pass re-walks the route it found in detail and gives up when that
     * costs more than 5,000 over the search's own figure. Above the profile's 90 km/h the
     * kinematic model's two figures drift apart over long trips: Austin to Lubbock and to
     * Amarillo failed at 113 km/h and above, Austin to Denver at 105, and every one
     * succeeded at a lower speed. So that failure, and only it, is tried again one speed
     * lower, down to the profile's own 90, which never failed; the route says which speed
     * it was timed at. The [nogos]' weights go down with the speed: they are BRouter cost,
     * and a second costs less at a lower top speed ([TrafficCost.secondPrice]).
     */
    private fun computeStepping(
        request: RouteRequest,
        nogos: List<Nogo>,
        maxRunningMs: Long,
        started: (SearchHandle) -> Unit,
    ): Route {
        var options = request.options
        while (true) {
            try {
                val scale = TrafficCost.secondPrice(options.topSpeedKmh) / TrafficCost.secondPrice(request.options.topSpeedKmh)
                val scaled = if (scale == 1.0) nogos else nogos.map { it.copy(weight = it.weight?.times(scale)) }
                return computeAt(request, options, scaled, maxRunningMs, started)
            } catch (e: RoutingException) {
                val lower = RouteOptions.TOP_SPEEDS_KMH.lastOrNull { it < options.topSpeedKmh }
                if (e.message != RETRACK_FAILED || lower == null) throw e
                options = options.copy(topSpeedKmh = lower)
            }
        }
    }

    private fun computeAt(
        request: RouteRequest,
        options: RouteOptions,
        nogos: List<Nogo>,
        maxRunningMs: Long,
        started: (SearchHandle) -> Unit,
    ): Route {
        val context = RoutingContext()
        context.localFunction = profile.absolutePath
        context.memoryclass = memoryClassMb
        // car-vario asks for mode 1, "choose from the client", which leaves this value
        // alone; without a non-zero mode BRouter derives no maneuvers at all. Beyond
        // that, mode 3 changes only how BRouter *formats* a command — the beeline hints
        // are added for modes 2, 4, 8 and 9 alone — and the command is what [Maneuvers]
        // reads.
        context.turnInstructionMode = TURN_INSTRUCTION_MODE
        context.alternativeIdx = request.alternative
        // The Routing settings, as the profile's own parameters. A copy, because BRouter
        // writes the kinematic model's values back into the map it is given.
        val parameters = options.profileParameters()
        forgetProfileOnChange(parameters)
        context.keyValues = HashMap(parameters)
        // The vehicle's direction of travel, applied to the first step as if the route
        // began 1 km behind it on that heading: turning back then costs what a U-turn
        // costs in the profile, rather than nothing. BRouter only honours it when forced.
        // That is a flat 40 s, too little to stop a re-route turning round on the spot,
        // so the road just behind the vehicle is priced as well ([behind]).
        request.startBearing?.let { bearing ->
            context.startDirection = (((bearing % 360.0) + 360.0) % 360.0).roundToInt() % 360
            context.forceUseStartDirection = true
        }
        // Weighted first and hard last, so that on a piece of way inside both kinds the
        // closure is the circle BRouter keeps (class notes); the point behind the vehicle
        // after traffic's weighted ones, so a jam on the same link does not wipe it out.
        // Fresh nodes every time: BRouter adds to the list it is given, and nothing may
        // carry to the next search.
        val placed = nogos.filter { !it.hard } + listOfNotNull(behind(request, options)) + nogos.filter { it.hard }
        if (placed.isNotEmpty()) context.nogopoints = ArrayList(placed.map { brouterNogo(it) })
        val via = request.viaLat != null && request.viaLon != null
        val waypoints = RoutingParamCollector().readPositions(
            if (via) doubleArrayOf(request.fromLon, request.viaLon!!, request.toLon) else doubleArrayOf(request.fromLon, request.toLon),
            if (via) doubleArrayOf(request.fromLat, request.viaLat!!, request.toLat) else doubleArrayOf(request.fromLat, request.toLat),
        )
        val engine = RoutingEngine(null, null, segments, waypoints, context)
        widenIslandCheck(engine)
        // Otherwise a route with no output file is printed to stdout as GPX.
        engine.quite = true
        started(SearchHandle { engine.terminate() })
        engine.doRun(maxRunningMs)
        engine.getErrorMessage()?.let { throw RoutingException(it, engine.isTerminated() || it.contains(TIMED_OUT)) }
        val track = engine.getFoundTrack() ?: throw RoutingException("no route found")
        val nodes = track.nodes
        if (nodes.size < 2) throw RoutingException("no route found")
        val latitudes = DoubleArray(nodes.size) { (nodes[it].getILat() - LAT_OFFSET) / MICRODEGREES }
        val longitudes = DoubleArray(nodes.size) { (nodes[it].getILon() - LON_OFFSET) / MICRODEGREES }
        val indices = ArrayList<Int>()
        val exits = ArrayList<Int>()
        val commands = ArrayList<Int>()
        for (i in nodes.indices) {
            val hint = track.getVoiceHint(i) ?: continue
            indices += i
            exits += hint.getExitNumber()
            commands += VoiceHintReader.command(hint)
        }
        val start = nodes[0].getTime()
        val seconds = FloatArray(nodes.size) { nodes[it].getTime() - start }
        return Route(
            latitudes,
            longitudes,
            track.distance,
            track.getTotalSeconds(),
            indices.toIntArray(),
            exits.toIntArray(),
            commands.toIntArray(),
            seconds,
            topSpeedKmh = options.topSpeedKmh,
            via = if (via) doubleArrayOf(request.viaLat!!, request.viaLon!!) else null,
        ).withTimeScale(options.timeFactor)
    }

    companion object {
        /**
         * Links BRouter may visit before a road network the start or destination snapped to
         * stops counting as cut off. Upstream's 500 catches a lone parking aisle; a
         * fairground's or a campus's lots and drives, joined to the public road only through a
         * gate or a private way the car profile will not use, run to thousands, so the
         * destination stayed snapped inside them and the search failed with "no route"
         * (a pin on the Travis County Expo Center, 2026-10-31). Found cut off, BRouter
         * excludes every link it visited and snaps again, to the nearest road the vehicle
         * can actually reach: the route ends there, at the gate.
         *
         * The cost is the check's own search, run from the destination before every route:
         * at most this many links, a fraction of a second on the phone, where a route across
         * town visits hundreds of thousands.
         */
        const val ISLAND_CHECK_LINKS = 20_000

        /**
         * Sets [ISLAND_CHECK_LINKS] on [engine]. The vendored BRouter is held unmodified
         * (vendor/brouter/README.md) and keeps the limit and its pair set in private fields
         * sized at construction, so they are set by reflection; the host test pins both
         * names, so a re-sync that renames them fails there. False, and upstream's limit
         * kept, if either cannot be set. The pair set goes first: a limit raised over a set
         * still sized for 500 would record only part of a network it found cut off.
         */
        @JvmStatic
        fun widenIslandCheck(engine: RoutingEngine): Boolean = try {
            RoutingEngine::class.java.getDeclaredField(ISLAND_PAIRS_FIELD).apply { isAccessible = true }
                .set(engine, OsmNodePairSet(ISLAND_CHECK_LINKS))
            RoutingEngine::class.java.getDeclaredField(ISLAND_LIMIT_FIELD).apply { isAccessible = true }
                .setInt(engine, ISLAND_CHECK_LINKS)
            true
        } catch (e: ReflectiveOperationException) {
            false
        } catch (e: RuntimeException) {
            false
        }

        /** BRouter's private fields [widenIslandCheck] sets. */
        const val ISLAND_PAIRS_FIELD = "islandNodePairs"
        const val ISLAND_LIMIT_FIELD = "MAXNODES_ISLAND_CHECK"

        /** BRouter's `osmand` style, the one its own parameter collector maps car clients to. */
        const val TURN_INSTRUCTION_MODE = 3

        /** BRouter's message when its final detailed pass cannot follow the route it found. */
        const val RETRACK_FAILED = "error re-tracking track"

        /**
         * How near a closure the route crossed a weighted point must be to be left out of
         * the second search, metres: the longest interstate link measured on the Texas tiles
         * was 1.6 km (docs/navigation.md, "Live traffic (HERE)"), and a point can only overwrite a
         * closure on the same link.
         */
        const val CLOSURE_GUARD_M = 2_000.0

        /**
         * Where the turn-back point sits, metres behind the start on the reverse of its
         * heading, and its radius: the circle covers the road from 2 m to 22 m behind,
         * clear of the start itself, which BRouter would otherwise drop the point for.
         */
        const val BEHIND_CENTRE_M = 12.0
        const val BEHIND_RADIUS_M = 10.0

        /**
         * Seconds of delay that turning back the way the vehicle came is priced at, on
         * top of BRouter's own 40 s, for a road straight through the circle. A way ahead up
         * to about three and a half minutes longer is taken instead; a dead end or a
         * country road with no way round still turns back.
         */
        const val TURN_BACK_SECONDS = 180.0

        /**
         * A weighted no-go point just behind the start of a request with a heading, or
         * null without one. BRouter tests a start segment against no-go points only from
         * the point the start was snapped to onward, "in order not to cut our escape-way
         * if we placed a nogo just in front of where we are" (`RoutingContext.calcDistance`),
         * so setting off forward never pays it and setting off backward does. Weighted
         * rather than hard: a hard one fails the route in a dead end. It is BRouter cost at
         * [options]' top speed, so a search timed slower prices it at that speed.
         */
        fun behind(request: RouteRequest, options: RouteOptions): Nogo? {
            val bearing = request.startBearing ?: return null
            val back = Math.toRadians(bearing + 180.0)
            val metresPerDegree = 6_371_000.0 * Math.PI / 180.0
            val lat = request.fromLat + BEHIND_CENTRE_M * cos(back) / metresPerDegree
            val lon = request.fromLon + BEHIND_CENTRE_M * sin(back) / (metresPerDegree * cos(Math.toRadians(request.fromLat)))
            val weight = TURN_BACK_SECONDS * TrafficCost.secondPrice(options.topSpeedKmh) / (2.0 * BEHIND_RADIUS_M)
            return Nogo(lat, lon, BEHIND_RADIUS_M, weight)
        }

        /** In BRouter's message when a search ran out of time rather than failed. */
        private const val TIMED_OUT = "timeout after"
        private const val MICRODEGREES = 1_000_000.0
        private const val LAT_OFFSET = 90_000_000
        private const val LON_OFFSET = 180_000_000

        /**
         * [nogo] as BRouter's own parser makes one (`RoutingParamCollector.readNogo`):
         * microdegrees offset to be positive, the radius in metres, and NaN weight for a
         * hard block. The radius is set directly, so the name is only for BRouter's logs.
         */
        private fun brouterNogo(nogo: Nogo): OsmNodeNamed = OsmNodeNamed().apply {
            ilon = ((nogo.longitude + 180.0) * MICRODEGREES + 0.5).toInt()
            ilat = ((nogo.latitude + 90.0) * MICRODEGREES + 0.5).toInt()
            radius = nogo.radiusM
            nogoWeight = nogo.weight ?: Double.NaN
            isNogo = true
            name = "nogo" + nogo.radiusM.roundToInt()
        }

        /**
         * The hard points among [nogos] that [route] passes inside, leaving out any BRouter
         * itself set aside because its circle holds the start, the via point or the
         * destination as asked for.
         */
        fun closuresCrossed(route: Route, request: RouteRequest, nogos: List<Nogo>): List<Nogo> {
            val ends = listOfNotNull(
                doubleArrayOf(request.fromLat, request.fromLon),
                doubleArrayOf(request.toLat, request.toLon),
                request.viaLat?.let { doubleArrayOf(it, request.viaLon!!) },
            )
            return nogos.filter { nogo ->
                nogo.hard &&
                    ends.none { metres(it[0], it[1], nogo.latitude, nogo.longitude) < nogo.radiusM } &&
                    passesWithin(route, nogo.latitude, nogo.longitude, nogo.radiusM)
            }
        }

        /**
         * Whether any piece of [route] comes nearer than [radiusM] to the point, measured as
         * BRouter measures its circles: `CheapRuler`'s metres per microdegree at the point.
         */
        private fun passesWithin(route: Route, lat: Double, lon: Double, radiusM: Double): Boolean {
            val scales = CheapRuler.getLonLatToMeterScales(((lat + 90.0) * MICRODEGREES).roundToInt())
            val kx = scales[0] * MICRODEGREES
            val ky = scales[1] * MICRODEGREES
            var x1 = (route.longitudes[0] - lon) * kx
            var y1 = (route.latitudes[0] - lat) * ky
            for (i in 1 until route.points) {
                val x2 = (route.longitudes[i] - lon) * kx
                val y2 = (route.latitudes[i] - lat) * ky
                val dx = x2 - x1
                val dy = y2 - y1
                val length2 = dx * dx + dy * dy
                val t = if (length2 == 0.0) 0.0 else (-(x1 * dx + y1 * dy) / length2).coerceIn(0.0, 1.0)
                if (hypot(x1 + t * dx, y1 + t * dy) < radiusM) return true
                x1 = x2
                y1 = y2
            }
            return false
        }

        /** Metres between two points, by `CheapRuler` as BRouter measures them. */
        private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = CheapRuler.distance(
            ((lon1 + 180.0) * MICRODEGREES).roundToInt(), ((lat1 + 90.0) * MICRODEGREES).roundToInt(),
            ((lon2 + 180.0) * MICRODEGREES).roundToInt(), ((lat2 + 90.0) * MICRODEGREES).roundToInt(),
        )

        private var lastParameters: Map<String, String>? = null

        /**
         * Drops BRouter's parsed profile when the parameters differ from the last route's.
         *
         * BRouter caches the parsed profile process-wide, keyed on the file's modification
         * time plus a *sum* of the parameters' key and value hash codes. A sum cannot tell
         * `avoid_toll=1 avoid_motorways=0` from `avoid_toll=0 avoid_motorways=1`, so the
         * second route would silently be computed with the first one's switches. Parsing
         * again costs a few milliseconds, and only when a setting changed.
         */
        @Synchronized
        private fun forgetProfileOnChange(parameters: Map<String, String>) {
            if (parameters == lastParameters) return
            ProfileCache.setSize(1)
            lastParameters = parameters
        }
    }
}

/**
 * The one thread routing runs on, and the rule that only the newest request matters.
 *
 * Routing takes seconds and a projected frame has 33 ms, so none of it may run on the
 * scene thread or the main thread. A newer request stops the running search rather than
 * queueing behind it: a driver who picks a second destination does not want the first
 * route, and a re-route (P6.5) that waits for a stale one is late by exactly that long.
 *
 * [done] is called on the routing thread, once per request, including superseded ones.
 */
class RoutePlanner @JvmOverloads constructor(
    private val router: () -> RouteEngine,
    private val maxRunningMs: Long = MAX_RUNNING_MS,
    /** Runs first on the routing thread; the app lowers its scheduling priority here. */
    threadSetup: () -> Unit = {},
    /**
     * Runs on the routing thread on every route found, before [RoutePlanner.request]'s
     * callback, with the request that made it: the app names its streets here
     * ([StreetNames]). What it throws is dropped with the route kept as found, because a
     * name is never worth losing a route over.
     */
    private val annotate: (RouteRequest, Route) -> Route = { _, route -> route },
) : AutoCloseable {
    private val latest = AtomicLong()

    @Volatile
    private var running: SearchHandle? = null

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread({
                threadSetup()
                runnable.run()
            }, THREAD).apply { isDaemon = true }
        }

    /** Queues [request], stopping any search in progress. Returns its sequence number. */
    fun request(request: RouteRequest, done: (RouteResult) -> Unit): Long {
        val sequence = latest.incrementAndGet()
        running?.terminate()
        executor.execute { run(sequence, request, done) }
        return sequence
    }

    /**
     * Background work on the routing thread that gives way to every route: the live
     * alternatives ([LiveAlternatives]). It runs only if no request arrives before it is
     * picked up, and any request made while it runs stops its current search and tells it,
     * through `cancelled`, to stop starting new ones. A re-route therefore waits for at most
     * the end of one terminated search, never for the alternatives.
     *
     * [done] runs on the routing thread once [work] is over, and also when it was skipped,
     * failed or never queued (a closed planner), so a caller waiting on it always hears.
     */
    fun explore(
        done: () -> Unit = {},
        work: (router: RouteEngine, started: (SearchHandle) -> Unit, cancelled: () -> Boolean) -> Unit,
    ) {
        val at = latest.get()
        try {
            executor.execute {
                try {
                    if (latest.get() != at) return@execute
                    work(
                        router(),
                        { engine ->
                            running = engine
                            if (latest.get() != at) engine.terminate()
                        },
                        { latest.get() != at },
                    )
                } catch (e: Exception) {
                    // The alternatives are extras; a failure here costs them and nothing else.
                    // Exception, not RuntimeException: router() throws RoutingException for a
                    // profile it cannot install, and nothing may escape onto this thread.
                } finally {
                    running = null
                    done()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            done()
        }
    }

    private fun run(sequence: Long, request: RouteRequest, done: (RouteResult) -> Unit) {
        val thread = Thread.currentThread().name
        if (sequence != latest.get()) {
            done(RouteResult(sequence, null, "superseded before it started", 0, thread, true))
            return
        }
        val start = System.nanoTime()
        val result = try {
            val route = router().compute(request, maxRunningMs) { engine ->
                running = engine
                // A newer request may have arrived between the check above and now.
                if (sequence != latest.get()) engine.terminate()
            }
            val naming = System.nanoTime()
            val named = if (sequence == latest.get()) {
                try {
                    annotate(request, route)
                } catch (e: RuntimeException) {
                    route
                }
            } else {
                route
            }
            RouteResult(sequence, named, null, elapsedMs(start), thread, sequence != latest.get(), elapsedMs(naming))
        } catch (e: RoutingException) {
            RouteResult(sequence, null, e.message ?: "routing failed", elapsedMs(start), thread, sequence != latest.get())
        } catch (e: RuntimeException) {
            // BRouter reports most failures as a message, but a malformed profile throws
            // from the engine's constructor; either way the app keeps running.
            RouteResult(sequence, null, e.toString(), elapsedMs(start), thread, sequence != latest.get())
        } finally {
            running = null
        }
        done(result)
    }

    override fun close() {
        latest.incrementAndGet()
        running?.terminate()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        const val THREAD = "aap-route"

        /**
         * Five times BRouter's own 60 s service default. Austin to Denver — 1,460 km over
         * four tiles — takes 15 s on a desktop JVM and 21 s held to one core, and a phone
         * core is slower than either, so 60 s would fail exactly the long trips worth
         * planning. It is a ceiling, not a wait: a newer request still stops the search.
         */
        const val MAX_RUNNING_MS = 300_000L

        private fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000L
    }
}
