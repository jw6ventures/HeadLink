package com.jw6ventures.headlink

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.io.IOException
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
     * The maps street names are read from: the ones the dashboard draws while it projects,
     * installed by [ProjectionService], else every installed map. Read on the routing thread.
     */
    @Volatile
    var nameMaps: List<File>? = null

    fun root(context: Context): File = RoutingData.root(context.applicationContext.filesDir)

    fun installed(context: Context): List<File> = RoutingData.installed(RoutingData.segments(root(context)))

    /** Starts [request] on the routing thread; the result arrives through [SessionController.note]. */
    fun request(context: Context, request: RouteRequest, from: String, startFrom: String) {
        val planner = planner(context)
        requests++
        origin = from
        start = startFrom
        options = request.options
        lastRequest = request
        pending = planner.request(request) { result -> main.post { finished(result) } }
        SessionController.note("route requested from $from (request $pending)")
    }

    /** Runs [RoutePlanner.explore] work on the routing thread; see there for how it gives way. */
    fun explore(
        context: Context,
        done: () -> Unit = {},
        work: (Router, (btools.router.RoutingEngine) -> Unit, () -> Boolean) -> Unit,
    ) {
        planner(context).explore(done, work)
    }

    /** The routing thread's planner, made on first use. */
    private fun planner(context: Context): RoutePlanner {
        planner?.let { return it }
        val root = root(context)
        // The planner outlives this call, so it keeps the application, never an activity.
        val app = context.applicationContext
        return RoutePlanner(
            router = {
                // On the routing thread: the profile copy is file I/O too.
                val profile = try {
                    RoutingData.installProfile(RoutingData.profiles(root))
                } catch (e: IOException) {
                    throw RoutingException("routing profile: ${e.message}")
                }
                Router(RoutingData.segments(root), profile)
            },
            // Below the scene and encoder threads, which run at the default and have a
            // frame deadline this does not — but deliberately not THREAD_PRIORITY_BACKGROUND.
            // Android moves a thread at that level into the background scheduling group,
            // which keeps it on the phone's little cores: the first build used it, and a
            // 94 km route took 23.9 s warm on the Pixel against 2.3 s on one desktop core.
            threadSetup = { Process.setThreadPriority(THREAD_PRIORITY) },
            annotate = { route -> signed(app, StreetNames.name(route, nameMaps ?: MapData.installed(app))) },
        ).also { planner = it }
    }

    /**
     * [route] with the exit signs OsmAnd's maps give it ([ExitSigns]); [route] itself with
     * no maps or when reading them fails. The routing thread's work: it opens files.
     */
    fun signed(context: Context, route: Route): Route = try {
        ExitSigns.sign(route, ObfLibrary.sources(context))
    } catch (e: RuntimeException) {
        route
    }

    private fun finished(result: RouteResult) {
        if (result.sequence != pending || result.superseded) {
            superseded++
            return
        }
        pending = 0
        val route = result.route
        if (route == null && (origin == CLOSURE || origin == REROUTE) && line != null) {
            // A re-route that found nothing: the destination has not changed, so the route
            // the vehicle had is still the way there. It stays on the map and in guidance
            // rather than vanishing, so a driver who finds their own way back onto it is
            // guided along it again; one still off it is re-routed again on Guide's clock.
            val what = if (origin == CLOSURE) "re-route round a closure" else "re-route"
            SessionController.note("$what failed after ${result.computeMs} ms: ${result.error}")
            Guide.onRouteFailed()
            return
        }
        last = result
        line = route
        token++
        if (route != null && route.trafficShaped) trafficRoutes++
        if (route != null && route.closuresOpened) closuresOpened++
        route?.let { Guide.onRoute(it) } ?: Guide.onRouteFailed()
        NavigationStore.changed()
        // No coordinates, only totals: the event log is part of the saved report.
        SessionController.note(
            result.route?.let { route ->
                "route computed in ${result.computeMs} ms on ${result.thread}: ${route.distanceM} m, " +
                    "${route.seconds} s, ${route.points} points, ${route.maneuverIndices.size} maneuvers" +
                    lowered(route) + traffic(route)
            } ?: "route failed after ${result.computeMs} ms on ${result.thread}: ${result.error}",
        )
    }

    /** How many traffic points the search had (P9.5), and whether it had to open a closure: counts, not places. */
    private fun traffic(route: Route): String = when {
        route.closuresOpened -> "; ${route.nogoCount} traffic points, through a closure (no way round it)"
        route.trafficShaped -> "; ${route.nogoCount} traffic points"
        else -> ""
    }

    /** Said when BRouter could not finish at the chosen top speed and the route was timed lower. */
    private fun lowered(route: Route): String {
        val asked = options?.topSpeedKmh ?: return ""
        return if (route.topSpeedKmh in 1 until asked) "; timed at ${route.topSpeedKmh} km/h, as $asked km/h failed" else ""
    }

    /**
     * Forgets the route and takes its line off the map. A search still running finishes
     * on its own thread and is then counted as superseded, never shown.
     */
    fun clear() {
        pending = 0
        last = null
        line = null
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
     * made about a route that is gone. A re-route later is BRouter's best route from
     * wherever the vehicle is, not this alternative: nothing keeps a re-route to it.
     */
    fun adopt(route: Route, chosenToken: Long): Boolean {
        if (chosenToken != token || pending != 0L || line == null) return false
        val previous = last ?: return false
        alternativesChosen++
        last = RouteResult(previous.sequence, route, null, 0, "preview", false)
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
        val options = AppSettings.load(context).routing
        Guide.learnPace = options.learnPace
        lastRequest = RouteRequest(
            route.latitudes[0], route.longitudes[0], destination.latitude, destination.longitude, null, options,
            viaLat = Navigation.via?.get(0), viaLon = Navigation.via?.get(1),
        )
        this.options = options
        origin = "restored"
        start = "saved"
        last = RouteResult(0, route, null, 0, "restored", false)
        line = route
        token++
        Guide.onRoute(route)
    }

    /** One line for the connection screen, which is the operator's own and may say more. */
    fun summary(): String? {
        if (pending != 0L) return "computing…"
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
     * how long it took and where, and how big the answer was. Tile names say which 5°
     * square data was installed for, as the map line already names the map file.
     */
    fun report(context: Context): String = buildString {
        val root = root(context)
        val segments = installed(context)
        appendLine(
            "routing_engine=brouter-1.7.10 profile=${RoutingData.PROFILE} " +
                "turn_instruction_mode=${Router.TURN_INSTRUCTION_MODE} routing_thread_priority=$THREAD_PRIORITY",
        )
        appendLine(
            "routing_profile_installed=${File(RoutingData.profiles(root), RoutingData.PROFILE).isFile} " +
                "routing_segments=${segments.joinToString(",") { "${it.name}:${it.length()}" }.ifEmpty { "-" }}",
        )
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
            appendLine(
                "routing_named_roads=${route.roadNames.count { it != null }}/${route.roadStarts.size} " +
                    "routing_naming_ms=${result.namingMs}",
            )
        } else {
            appendLine("routing_error=${result.error}")
        }
    }
}
