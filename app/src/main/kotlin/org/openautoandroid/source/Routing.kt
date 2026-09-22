package org.openautoandroid.source

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
     * Which source set the last request's destination: a [DestinationSource] label, or
     * `reroute` when [Guide] asked because the vehicle left the route (P6.5).
     */
    var origin = "-"
        private set

    /** Where the last request started: `vehicle`, `phone`, `typed` or `lab`. */
    var start = "-"
        private set

    var superseded = 0
        private set

    /** The newest request's result; superseded ones are counted, never shown. */
    var last: RouteResult? = null
        private set

    /**
     * The route the map draws, read by the scene thread each frame. Replaced by the next
     * good route and cleared by a failed one: once a new destination was asked for, the
     * old line is a route to somewhere the driver no longer wants to go.
     */
    @Volatile
    var line: Route? = null
        private set

    fun root(context: Context): File = RoutingData.root(context.applicationContext.filesDir)

    fun installed(context: Context): List<File> = RoutingData.installed(RoutingData.segments(root(context)))

    /** Starts [request] on the routing thread; the result arrives through [SessionController.note]. */
    fun request(context: Context, request: RouteRequest, from: String, startFrom: String) {
        val root = root(context)
        val planner = planner ?: RoutePlanner(
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
        ).also { planner = it }
        requests++
        origin = from
        start = startFrom
        pending = planner.request(request) { result -> main.post { finished(result) } }
        SessionController.note("route requested from $from (request $pending)")
    }

    private fun finished(result: RouteResult) {
        if (result.sequence != pending || result.superseded) {
            superseded++
            return
        }
        pending = 0
        last = result
        line = result.route
        result.route?.let { Guide.onRoute(it) } ?: Guide.onRouteFailed()
        // No coordinates, only totals: the event log is part of the saved report.
        SessionController.note(
            result.route?.let { route ->
                "route computed in ${result.computeMs} ms on ${result.thread}: ${route.distanceM} m, " +
                    "${route.seconds} s, ${route.points} points, ${route.maneuverIndices.size} maneuvers"
            } ?: "route failed after ${result.computeMs} ms on ${result.thread}: ${result.error}",
        )
    }

    /**
     * Forgets the route and takes its line off the map. A search still running finishes
     * on its own thread and is then counted as superseded, never shown.
     */
    fun clear() {
        pending = 0
        last = null
        line = null
        SessionController.note("route cleared")
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
     * on the footing the P6 plan §9.6 proposes for navigation evidence: what was computed,
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
                "routing_pending=${pending != 0L} routing_superseded=$superseded",
        )
        append(Navigation.report())
        append(Guide.report())
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
                    "routing_roundabout_exits=${route.exitNumbers.count { it != 0 }}",
            )
        } else {
            appendLine("routing_error=${result.error}")
        }
    }
}
