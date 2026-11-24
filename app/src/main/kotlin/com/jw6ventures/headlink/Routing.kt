package com.jw6ventures.headlink

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.Locale

/**
 * The app's one routing engine (P6.3): requests go to [RoutePlanner]'s thread, results come
 * back to the main thread, and the report reads the last one.
 *
 * Main-thread state, like [SessionController], and independent of any attempt: a route
 * can be computed with no head unit attached, which is how its cost on the phone is
 * measured, and one computed during projection is what shows it stays off the scene
 * thread. The newest good route is published as [line] for the map to draw (P6.4) and
 * handed to [Guide] to follow (P6.5).
 */
object Routing {
    /** Nice 4: behind the default-priority render threads, short of the background group. */
    /** A route search's own hold on OsmAnd's maps ([ObfLibrary.hold]). */
    private const val MAPS_HOLDER = "routing"

    private const val THREAD_PRIORITY = Process.THREAD_PRIORITY_DEFAULT + 4 * Process.THREAD_PRIORITY_LESS_FAVORABLE

    private val main = Handler(Looper.getMainLooper())
    private var planner: RoutePlanner? = null

    var requests = 0
        private set

    /** Sequence of the request in flight, or 0. */
    var pending = 0L
        private set

    /**
     * Which source set the last request's destination: a [DestinationSource] label,
     * `reroute` when [Guide] asked because the vehicle left the route (P6.5), or [CLOSURE]
     * when live traffic found the route ahead closed (P9.5).
     */
    var origin = "-"
        private set

    /** [origin] for a re-route because the vehicle left the route. */
    const val REROUTE = "reroute"

    /** [origin] for a re-route round a closure on the route ahead. */
    const val CLOSURE = "closure"

    /** [origin] for asking again after a request found no route and there was none to keep ([Navigation.retry]). */
    const val RETRY = "retry"

    /** Routes computed with live traffic's no-go points (P9.5), and those that had to open a closure. */
    var trafficRoutes = 0
        private set
    var closuresOpened = 0
        private set

    /** Where the last request started: `vehicle`, `phone`, `typed` or `lab`. */
    var start = "-"
        private set

    var superseded = 0
        private set

    /** The Routing settings the last request was made with, or null before the first. */
    var options: RouteOptions? = null
        private set

    /** The last request itself: what the route preview asks alternatives of. Never reported. */
    private var lastRequest: RouteRequest? = null

    /** Whether the request in flight is [Navigation]'s, for its held destination. */
    private var tripRequest = false

    /**
     * The last answered request when no map covered it ([OsmAndRouter.coverage]), with the
     * reason, or null: what [coverageText] words. Its own request's, never the held
     * destination's, so a two-point request off the maps names its own end.
     */
    private var uncovered: Pair<RouteRequest, String>? = null
    private var uncoveredTrip = false

    /**
     * Names the route on show: changes whenever [line] does. The route preview is handed
     * it and hands it back with a choice, so a choice made about a route that has since
     * been replaced (a re-route, a new destination) is refused, not applied.
     */
    var token = 0L
        private set

    /** Alternatives taken from the route preview. */
    var alternativesChosen = 0
        private set

    /** The newest request's result; superseded ones are counted, never shown. */
    var last: RouteResult? = null
        private set

    /**
     * The route the map draws, read by the scene thread each frame. Replaced by the next
     * good route and cleared by a failed one: once a new destination was asked for, the
     * old line is a route to somewhere the driver no longer wants to go. A failed re-route
     * keeps it: the destination is the same, and the line is still what [Guide] follows.
     */
    @Volatile
    var line: Route? = null
        private set

    /**
     * Debug builds only, set through the lab intent's `lab_routes_fail` extra: while that
     * attempt runs, every search fails with an ordinary routing error, never a coverage
     * reason, so the emulator lab's failed re-route (LAB_REROUTE=fail) is one [Guide] keeps
     * on asking for and the vehicle rejoins the old route by itself, as where OsmAnd cannot
     * place it. Moving the lab's map away instead would read as "outside the maps", which
     * stops re-routes. The attempt it was set for, so it never outlives it ([labFailExpired]).
     */
    private var labFailAttempt: Attempt? = null

    /** Read on the routing thread; set and cleared on the main thread with [labFailAttempt]. */
    @Volatile
    private var labFail = false

    /** Makes every search fail while [attempt], the current lab attempt, runs; null restores routing. */
    fun labFailFor(attempt: Attempt?) {
        labFailAttempt = attempt
        labFail = attempt != null
    }

    /** Drops the lab hook once its attempt is over, before any search is handed to the routing thread. */
    private fun labFailExpired() {
        val attempt = labFailAttempt ?: return
        if (!SessionController.isCurrent(attempt)) labFailFor(null)
    }

    /** The saved route preferences, as a request uses them. */
    fun options(context: Context): RouteOptions = AppSettings.load(context).routing

    /**
     * Starts [request] on the routing thread; the result arrives through [SessionController.note].
     * [trip] marks [Navigation]'s requests for its held destination: only their failures
     * are that destination's.
     */
    fun request(context: Context, request: RouteRequest, from: String, startFrom: String, trip: Boolean = false) {
        labFailExpired()
        val planner = planner(context)
        requests++
        origin = from
        start = startFrom
        options = request.options
        lastRequest = request
        tripRequest = trip
        pending = planner.request(request) { result -> main.post { finished(result) } }
        SessionController.note("route requested from $from (request $pending)")
    }

    /** Runs [RoutePlanner.explore] work on the routing thread; see there for how it gives way. */
    fun explore(
        context: Context,
        done: () -> Unit = {},
        work: (RouteEngine, (SearchHandle) -> Unit, () -> Boolean) -> Unit,
    ) {
        labFailExpired()
        planner(context).explore(done, work)
    }

    /** The routing thread's planner, made on first use. */
    private fun planner(context: Context): RoutePlanner {
        planner?.let { return it }
        // The planner outlives this call, so it keeps the application, never an activity.
        val app = context.applicationContext
        // One for the planner's life, so OsmAnd's readers stay open between routes.
        val router = OsmAndRouter { ObfLibrary.routingMaps(app) }
        // [ObfLibrary] opens its files only while something holds them: a projection, the
        // connection screen, the preview. A destination shared from OsmAnd with neither up
        // (DestinationActivity has no window) found nothing open, and OsmAnd's router said
        // "no OsmAnd maps open" for every request. A search holds the files itself; its
        // readers keep their own handles once opened, so letting go afterwards is safe.
        val engine = object : RouteEngine {
            override fun compute(request: RouteRequest, maxRunningMs: Long, started: (SearchHandle) -> Unit): Route {
                if (labFail) throw RoutingException("lab: routing failed on purpose (debug build)")
                ObfLibrary.hold(MAPS_HOLDER, true)
                try {
                    return router.compute(request, maxRunningMs, started)
                } finally {
                    ObfLibrary.hold(MAPS_HOLDER, false)
                }
            }
        }
        return RoutePlanner(
            router = { engine },
            // Below the scene and encoder threads, which run at the default and have a
            // frame deadline this does not — but deliberately not THREAD_PRIORITY_BACKGROUND.
            // Android moves a thread at that level into the background scheduling group,
            // which keeps it on the phone's little cores: the first build used it, and a
            // 94 km route took 23.9 s warm on the Pixel against 2.3 s on one desktop core.
            threadSetup = { Process.setThreadPriority(THREAD_PRIORITY) },
        ).also { planner = it }
    }

    private fun finished(result: RouteResult) {
        if (result.sequence != pending || result.superseded) {
            superseded++
            return
        }
        pending = 0
        val route = result.route
        val request = lastRequest
        val coverage = OsmAndRouter.coverage(result.error)
        uncovered = if (coverage && request != null) request to result.error!! else null
        uncoveredTrip = coverage && tripRequest
        if (route == null && (origin == CLOSURE || origin == REROUTE) && line != null) {
            // A re-route that found nothing: the destination has not changed, so the route
            // the vehicle had is still the way there. It stays on the map and in guidance
            // rather than vanishing, so a driver who finds their own way back onto it is
            // guided along it again; one still off it is re-routed again on Guide's clock,
            // unless no map covers the trip, which no re-route can mend ([Navigation.uncovered]).
            val what = if (origin == CLOSURE) "re-route round a closure" else "re-route"
            SessionController.note("$what failed after ${result.computeMs} ms: ${result.error}")
            if (tripRequest) Navigation.coverage(result.error)
            Guide.onRouteFailed()
            return
        }
        last = result
        line = route
        token++
        if (route != null && route.trafficShaped) trafficRoutes++
        if (route != null && route.closuresOpened) closuresOpened++
        if (route != null) {
            Navigation.routed()
            Guide.onRoute(route)
        } else {
            // Nothing to follow: the destination is held and asked for again as the vehicle
            // moves ([Navigation.retry]) unless no map covers it, and the dashboard says so.
            // A two-point request's coverage failure is its own, not the held destination's.
            Navigation.routeFailed(if (tripRequest) result.error else null)
            Guide.onRouteLost()
        }
        NavigationStore.changed()
        // No coordinates, only totals: the event log is part of the saved report.
        SessionController.note(
            result.route?.let { route ->
                "route computed in ${result.computeMs} ms on ${result.thread}: ${route.distanceM} m, " +
                    "${route.seconds} s, ${route.points} points, ${route.maneuverIndices.size} maneuvers" +
                    traffic(route)
            } ?: "route failed after ${result.computeMs} ms on ${result.thread}: ${result.error}",
        )
    }

    /** How many traffic points the search had (P9.5), and whether it had to open a closure: counts, not places. */
    private fun traffic(route: Route): String = when {
        route.closuresOpened -> "; ${route.nogoCount} traffic points, through a closure (no way round it)"
        route.trafficShaped -> "; ${route.nogoCount} traffic points"
        else -> ""
    }

    /**
     * Forgets the route and takes its line off the map. A search still running finishes
     * on its own thread and is then counted as superseded, never shown.
     */
    fun clear() {
        pending = 0
        last = null
        line = null
        uncovered = null
        token++
        SessionController.note("route cleared")
    }

    /**
     * What the route preview needs to show the route on show and look for others, or null
     * when there is none (or one is being computed).
     */
    fun previewRequest(): PreviewRequest? {
        if (pending != 0L) return null
        val route = line ?: return null
        val request = lastRequest ?: return null
        return PreviewRequest(
            token, request.fromLat, request.fromLon, request.toLat, request.toLon, request.startBearing,
            request.options, route,
        )
    }

    /**
     * Follows [route], an alternative the preview offered, in place of the one on show.
     * Refused, returning false, when [chosenToken] no longer names the route on show: it
     * was re-routed, replaced or cleared while the preview was open, and the choice was
     * made about a route that is gone. A re-route later is the engine's best route from
     * wherever the vehicle is, not this alternative: nothing keeps a re-route to it.
     */
    fun adopt(route: Route, chosenToken: Long): Boolean {
        if (chosenToken != token || pending != 0L || line == null) return false
        val previous = last ?: return false
        alternativesChosen++
        last = RouteResult(previous.sequence, route, null, 0, "preview", false)
        uncovered = null
        line = route
        token++
        Guide.onRoute(route)
        Navigation.chose(route)
        SessionController.note(
            "route alternative chosen in the preview: ${route.distanceM} m, ${route.seconds} s, " +
                "${route.points} points, ${route.maneuverIndices.size} maneuvers",
        )
        return true
    }

    /**
     * Puts back the route [NavigationStore] saved before the process was closed, as the
     * route on show, followed as if just computed. Its request is rebuilt from the route's
     * own start and the restored destination, so the preview can still look for others.
     */
    fun restore(context: Context, route: Route) {
        val destination = Navigation.destination ?: return
        val options = Routing.options(context)
        Guide.learnPace = options.learnPace
        lastRequest = RouteRequest(
            route.latitudes[0], route.longitudes[0], destination.latitude, destination.longitude, null, options,
            viaLat = Navigation.via?.get(0), viaLon = Navigation.via?.get(1),
        )
        this.options = options
        origin = "restored"
        start = "saved"
        last = RouteResult(0, route, null, 0, "restored", false)
        uncovered = null
        line = route
        token++
        Guide.onRoute(route)
    }

    /**
     * What the operator is told when the last request's destination or stop is outside
     * every OsmAnd map, or null: the place and the map to get, or the mirrored longitude
     * for a dropped minus sign. Names the place, so it is for the screen, never the report.
     */
    fun coverageText(context: Context): String? {
        if (pending != 0L) return null
        val (request, error) = uncovered ?: return null
        val held = Navigation.destination?.takeIf { uncoveredTrip }
        val place = held?.let(Navigation::describe) ?: Navigation.coordinates(request.toLat, request.toLon)
        return when (error) {
            OsmAndRouter.MIRRORED -> context.getString(
                R.string.destination_mirrored, place, Navigation.coordinates(request.toLat, -request.toLon),
            )
            OsmAndRouter.STOP_NOT_COVERED -> context.getString(
                R.string.stop_no_osmand_map, Navigation.coordinates(request.viaLat ?: return null, request.viaLon ?: return null),
            )
            else -> context.getString(R.string.destination_no_osmand_map, place)
        }
    }

    /**
     * One line for the connection screen, which is the operator's own and may say more:
     * for a destination no map covers, the place and the map to get.
     */
    fun summary(context: Context): String? {
        if (pending != 0L) return "computing…"
        coverageText(context)?.let { return it }
        val result = last ?: return null
        val route = result.route ?: return "failed: ${result.error}"
        return String.format(
            Locale.ROOT,
            "%.1f km, %d min, %d maneuvers — computed in %.1f s",
            route.distanceM / 1000.0,
            (route.seconds + 30) / 60,
            route.maneuverIndices.size,
            result.computeMs / 1000.0,
        )
    }

    /**
     * The `[routing]` report section. **No coordinates, no geometry and no destination**,
     * on the footing navigation evidence takes (docs/navigation.md): what was computed,
     * how long it took and where, and how big the answer was.
     */
    fun report(): String = buildString {
        appendLine("routing_engine=osmand profile=car routing_thread_priority=$THREAD_PRIORITY")
        appendLine(
            "routing_requests=$requests routing_origin=$origin routing_start=$start " +
                "routing_pending=${pending != 0L} routing_superseded=$superseded " +
                "routing_alternatives_chosen=$alternativesChosen routing_restored=${NavigationStore.restored} " +
                "routing_traffic_routes=$trafficRoutes routing_closures_opened=$closuresOpened",
        )
        appendLine(options?.report() ?: "routing_options=-")
        append(Navigation.report())
        // P11: OsmAnd's maps and typed search, counts only (docs/navigation.md).
        append(ObfLibrary.report())
        append(Guide.report())
        append(LiveAlternatives.report())
        val result = last
        if (result == null) {
            appendLine("routing_result=-")
            return@buildString
        }
        val route = result.route
        appendLine(
            "routing_result=${if (route != null) "ok" else "error"} routing_thread=${result.thread} " +
                "routing_compute_ms=${result.computeMs}",
        )
        if (route != null) {
            appendLine(
                "routing_distance_m=${route.distanceM} routing_seconds=${route.seconds} " +
                    "routing_points=${route.points} routing_maneuvers=${route.maneuverIndices.size} " +
                    "routing_roundabout_exits=${route.exitNumbers.count { it != 0 }} " +
                "routing_top_speed_used_kmh=${route.topSpeedKmh} routing_traffic_points=${route.nogoCount} " +
                "routing_through_closure=${route.closuresOpened}",
            )
            // How many stretches the map named, never which names: a road name says where.
            appendLine("routing_named_roads=${route.roadNames.count { it != null }}/${route.roadStarts.size}")
        } else {
            appendLine("routing_error=${result.error}")
        }
    }
}
