package com.jw6ventures.headlink

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

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
 * Two points to route between, in degrees.
 *
 * A route is from somewhere to somewhere and nothing more: via points, no-go areas and
 * round trips were none of them in P6.3. One via point came with the route preview, and
 * no-go points with live traffic (P9.5).
 *
 * [startBearing] (P6.5) is the way the vehicle is already travelling, degrees clockwise
 * from north. A re-route sets it, because the engine otherwise has no idea which way the
 * vehicle faces; OsmAnd's car profile then charges for setting off the other way.
 *
 * [options] are the Routing settings in force when the request was made, so a re-route
 * takes whatever the screen says now.
 *
 * [alternative] asks for the engine's alternative route of that number, 0 being the best.
 * Alternative k is found by searching k+1 times, each search pricing the roads the
 * earlier ones used, so the second costs about twice the first and the third three times.
 * Only the route preview asks for one.
 *
 * [viaLat] and [viaLon], both or neither, put one point the route must pass through
 * between the two ends. The preview's own alternatives are made this way ([AlternativeFinder]).
 *
 * [nogos] (P9.5) are live traffic's closures and jams as no-go points ([TrafficNogos]): a
 * hard one is a circle the route may not enter, a weighted one charges the metres of road
 * inside it times its weight, in seconds per metre ([TrafficCost]). Empty, the default, is
 * a route exactly as before P9.
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
        /**
         * Each alternative costs one more full search ([alternative]), so past the third
         * the preview would wait longer than the choice is worth.
         */
        const val MAX_ALTERNATIVE = 3

        private fun valid(lat: Double, lon: Double): Boolean =
            lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

        /** Whether [nogo] is one the engine can be given: on the globe, a finite radius, a finite weight or none. */
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
 * A computed route: the geometry, the routing engine's own totals and timings, and the
 * maneuvers it derived along the geometry.
 *
 * What each maneuver *is* comes from the engine's turn command, a [TurnCommand]. Nothing here
 * guesses a turn from the geometry: the engine already weighed the roads it did not take.
 */
class Route @JvmOverloads constructor(
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    /** The engine's track length, metres. */
    val distanceM: Int,
    /** The engine's own travel-time estimate from the profile's speed model, seconds. */
    val seconds: Int,
    /** Index into the geometry of each maneuver the engine derived, ascending. */
    val maneuverIndices: IntArray,
    /** Roundabout exit for the maneuver at the same position, or 0 when it is not one. */
    val exitNumbers: IntArray,
    /**
     * The turn command for the maneuver at the same position — a [TurnCommand]
     * constant. [Maneuvers] says what it means. There is no destination among them:
     * OsmAnd marks none, so [RouteFollower] adds one.
     */
    val commands: IntArray = IntArray(maneuverIndices.size) { TurnCommand.CONTINUE },
    /**
     * Seconds from the start to each point, from the profile's speed model: the time to a
     * maneuver is a difference of two of these, so it moves with the route rather than
     * with the vehicle's momentary speed. Empty when not known.
     */
    val pointSeconds: FloatArray = FloatArray(0),
    /**
     * Point index where each named stretch of road begins, ascending: the start and every
     * maneuver. Empty when not known.
     */
    val roadStarts: IntArray = IntArray(0),
    /** The road each stretch in [roadStarts] drives, or null where the map names none. */
    val roadNames: Array<String?> = emptyArray(),
    /** The top speed the route was timed at, km/h: the one [RouteRequest.options] asked for. 0 when not known. */
    val topSpeedKmh: Int = 0,
    /**
     * The point the route was made to pass through, [latitude, longitude], or null: set on
     * an alternative the preview or the dashboard offered, so re-routes and a restart can
     * keep to it ([Navigation.via]).
     */
    val via: DoubleArray? = null,
    /**
     * How many traffic no-go points the engine was given for this route (P9.5), 0 when none.
     * Above 0, HERE's data helped choose it, so "Traffic © HERE" is owed while it is shown
     * (HERE's Platform Terms, 6.4a): whether the points actually moved it cannot be known
     * without a second search, and the attribution does not wait for one.
     */
    val nogoCount: Int = 0,
    /**
     * No route existed that kept out of every closure, so the closures were made passable
     * at a high price and this one goes through at least one ([OsmAndRouter]): the
     * vehicle is on a closed road, or the destination is. A closure ahead on this route is
     * then not a reason to re-route again, which would only find it again.
     */
    val closuresOpened: Boolean = false,
    /**
     * The exit sign for the maneuver at the same position in [maneuverIndices], or null
     * where there is none or none is known. Empty when not known: routes saved before
     * exit signs were.
     */
    val exitSigns: Array<ExitSign?> = emptyArray(),
    /**
     * The road class and surroundings of the stretch from each point to the next, a
     * [PaceRoad] code, for the learned pace ([PaceBook]); one per point, the last unused.
     * Empty when not known: routes saved before 0.11.
     */
    val paceClasses: ByteArray = ByteArray(0),
    /**
     * The factor [withTimeScale] put on every time (the Routing screen's time adjustment),
     * 1 when none: [PaceBook] learns against the times as the router gave them.
     */
    val timeScale: Double = 1.0,
    /**
     * The closures (hard [Nogo]s) this route enters at its first or last step although it
     * was found with them closed ([OsmAndRouter]): the vehicle is on a closed road, or the
     * destination is, and OsmAnd joins the trip's ends to the road without pricing those
     * pieces. Those closures, and only those, are no reason to re-route ([TrafficFeed]); it
     * opened none ([closuresOpened]). Not saved with the route ([RoutePreview]): they are
     * HERE's places, and after a restart the closure is re-routed round at most once more.
     */
    val endClosures: List<Nogo> = emptyList(),
) {
    val points: Int get() = latitudes.size

    /** The [PaceRoad] code of the stretch starting at point [index], or null when not known. */
    fun paceClassAt(index: Int): Byte? = if (paceClasses.size == points) paceClasses.getOrNull(index) else null

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
            nogoCount, closuresOpened, exitSigns, paceClasses, timeScale * factor, endClosures,
        )
    }

    /**
     * This route marked as made with [count] traffic nogos, whether closures were opened for
     * it, and the closures it enters at its ends ([endClosures]).
     */
    @JvmOverloads
    fun withNogos(count: Int, opened: Boolean, endClosures: List<Nogo> = emptyList()): Route = Route(
        latitudes, longitudes, distanceM, seconds, maneuverIndices, exitNumbers, commands,
        pointSeconds, roadStarts, roadNames, topSpeedKmh, via, count, opened, exitSigns, paceClasses, timeScale, endClosures,
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
            if (paceClasses.size == points) paceClasses.copyOfRange(start, points) else ByteArray(0),
            timeScale, endClosures,
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
 * Something that computes a [Route]: OsmAnd's router over `.obf` maps ([OsmAndRouter]), or
 * a stand-in in a test. Blocking, and used from one thread at a time.
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
                    // Exception, not RuntimeException: an engine may throw RoutingException
                    // before its first search, and nothing may escape onto this thread.
                } catch (e: OutOfMemoryError) {
                    // As for a route: the searches' memory is released once this unwinds.
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
            RouteResult(sequence, route, null, elapsedMs(start), thread, sequence != latest.get())
        } catch (e: RoutingException) {
            RouteResult(sequence, null, e.message ?: "routing failed", elapsedMs(start), thread, sequence != latest.get())
        } catch (e: RuntimeException) {
            // An engine reports most failures as RoutingException, but a bug or bad map data
            // can throw anything; either way the app keeps running.
            RouteResult(sequence, null, e.toString(), elapsedMs(start), thread, sequence != latest.get())
        } catch (e: OutOfMemoryError) {
            // A search over a long trip can outgrow the heap. Its graph is garbage once
            // this frame unwinds, so fail the route rather than let the Error end the
            // routing thread, and with it the process.
            RouteResult(sequence, null, "route search ran out of memory", elapsedMs(start), thread, sequence != latest.get())
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
         * Five times the 60 s that BRouter, the engine before OsmAnd's, used as its service
         * default. Austin to Denver — 1,460 km — took 15 s on a desktop JVM and 21 s held to
         * one core, and a phone core is slower than either, so 60 s would fail exactly the
         * long trips worth planning. It is a ceiling, not a wait: a newer request still stops the search.
         */
        const val MAX_RUNNING_MS = 300_000L

        private fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000L
    }
}
