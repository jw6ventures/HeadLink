package org.openautoandroid.source

import btools.router.ProfileCache
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import btools.router.VoiceHintReader
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Two points to route between, in degrees.
 *
 * A route is from somewhere to somewhere and nothing more: via points, no-go areas and
 * round trips are all things BRouter can do and none of them is in P6.3.
 *
 * [startBearing] (P6.5) is the way the vehicle is already travelling, degrees clockwise
 * from north. A re-route sets it, because BRouter otherwise has no idea which way the
 * vehicle faces. It is a weak hint with `car-vario`: the profile's turncost of 90 prices a
 * U-turn at about 180 m, so it only tips routes whose way ahead is nearly as short as
 * turning round. In the P6.5 lab drive it did not: the first re-route after the missed
 * turn still began by going back, and a second re-route followed six seconds later.
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
) {
    init {
        require(valid(fromLat, fromLon) && valid(toLat, toLon)) { "coordinates out of range" }
        require(alternative in 0..MAX_ALTERNATIVE) { "alternative out of range" }
        require((viaLat == null) == (viaLon == null)) { "a via point needs both coordinates" }
        require(viaLat == null || valid(viaLat, viaLon!!)) { "via point out of range" }
        require(startBearing == null || startBearing.isFinite()) { "bearing out of range" }
    }

    companion object {
        /** BRouter's own limit: `RoutingContext.getAlternativeIdx(0, 3)`. */
        const val MAX_ALTERNATIVE = 3

        private fun valid(lat: Double, lon: Double): Boolean =
            lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

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
) {
    val points: Int get() = latitudes.size

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
        )
    }

    /** This route with the road names [StreetNames] found. */
    fun withRoads(starts: IntArray, names: Array<String?>): Route {
        require(starts.size == names.size) { "one name per stretch" }
        return Route(
            latitudes, longitudes, distanceM, seconds, maneuverIndices, exitNumbers, commands,
            pointSeconds, starts, names, topSpeedKmh, via,
        )
    }

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
            topSpeedKmh, via,
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

class RoutingException(message: String) : Exception(message)

/**
 * BRouter over one segment directory and one profile. Blocking, and not thread-safe:
 * BRouter keeps its parsed profile in a process-wide cache, so everything goes through a
 * single [RoutePlanner] thread. Pure JVM, so the host tests exercise this same class.
 */
class Router(
    private val segments: File,
    private val profile: File,
    /** BRouter's node-cache budget in MiB; 64 is upstream's own default for its service. */
    private val memoryClassMb: Int = 64,
) {
    /**
     * Computes one route or throws [RoutingException] with BRouter's own message.
     *
     * [started] receives the engine before the search begins, which is the only handle
     * another thread has to stop it.
     */
    @Throws(RoutingException::class)
    fun compute(request: RouteRequest, maxRunningMs: Long, started: (RoutingEngine) -> Unit = {}): Route {
        if (!profile.isFile) throw RoutingException("no routing profile at ${profile.name}")
        if (!segments.isDirectory) throw RoutingException("no routing data installed")
        // BRouter's last pass re-walks the route it found in detail and gives up when that
        // costs more than 5,000 over the search's own figure. Above the profile's 90 km/h
        // the kinematic model's two figures drift apart over long trips: Austin to Lubbock
        // and to Amarillo failed at 113 km/h and above, Austin to Denver at 105, and every
        // one succeeded at a lower speed. So that failure, and only it, is tried again one
        // speed lower, down to the profile's own 90, which never failed; the route says
        // which speed it was timed at.
        var options = request.options
        while (true) {
            try {
                return computeAt(request, options, maxRunningMs, started)
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
        maxRunningMs: Long,
        started: (RoutingEngine) -> Unit,
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
        request.startBearing?.let { bearing ->
            context.startDirection = (((bearing % 360.0) + 360.0) % 360.0).roundToInt() % 360
            context.forceUseStartDirection = true
        }
        val via = request.viaLat != null && request.viaLon != null
        val waypoints = RoutingParamCollector().readPositions(
            if (via) doubleArrayOf(request.fromLon, request.viaLon!!, request.toLon) else doubleArrayOf(request.fromLon, request.toLon),
            if (via) doubleArrayOf(request.fromLat, request.viaLat!!, request.toLat) else doubleArrayOf(request.fromLat, request.toLat),
        )
        val engine = RoutingEngine(null, null, segments, waypoints, context)
        // Otherwise a route with no output file is printed to stdout as GPX.
        engine.quite = true
        started(engine)
        engine.doRun(maxRunningMs)
        engine.getErrorMessage()?.let { throw RoutingException(it) }
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
        /** BRouter's `osmand` style, the one its own parameter collector maps car clients to. */
        const val TURN_INSTRUCTION_MODE = 3

        /** BRouter's message when its final detailed pass cannot follow the route it found. */
        const val RETRACK_FAILED = "error re-tracking track"
        private const val MICRODEGREES = 1_000_000.0
        private const val LAT_OFFSET = 90_000_000
        private const val LON_OFFSET = 180_000_000

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
    private val router: () -> Router,
    private val maxRunningMs: Long = MAX_RUNNING_MS,
    /** Runs first on the routing thread; the app lowers its scheduling priority here. */
    threadSetup: () -> Unit = {},
    /**
     * Runs on the routing thread on every route found, before [RoutePlanner.request]'s
     * callback: the app names its streets here ([StreetNames]). What it throws is dropped
     * with the route kept as found, because a name is never worth losing a route over.
     */
    private val annotate: (Route) -> Route = { it },
) : AutoCloseable {
    private val latest = AtomicLong()

    @Volatile
    private var running: RoutingEngine? = null

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
     */
    fun explore(work: (router: Router, started: (RoutingEngine) -> Unit, cancelled: () -> Boolean) -> Unit) {
        val at = latest.get()
        executor.execute {
            if (latest.get() != at) return@execute
            try {
                work(
                    router(),
                    { engine ->
                        running = engine
                        if (latest.get() != at) engine.terminate()
                    },
                    { latest.get() != at },
                )
            } catch (e: RuntimeException) {
                // The alternatives are extras; a failure here costs them and nothing else.
            } finally {
                running = null
            }
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
                    annotate(route)
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
