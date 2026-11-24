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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

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

