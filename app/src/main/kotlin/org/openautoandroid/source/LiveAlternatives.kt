package org.openautoandroid.source

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt

/**
 * One other way to the destination, as the projected map shows it: its line, and a bubble
 * saying how much longer it is than the rest of the route being followed.
 */
class LiveAlternative(
    /** Distinct across every alternative shown, so a tap names this one and no other. */
    val id: Long,
    val route: Route,
    /** Its time less the followed route's time still to go, from the same point; BRouter's seconds. */
    val deltaSeconds: Int,
    /** Where the bubble goes: a little way into the stretch it does not share with the followed route. */
    val labelLat: Double,
    val labelLon: Double,
    /** The last point it shares with the followed route before leaving it. */
    val splitLat: Double,
    val splitLon: Double,
) {
    /** "+6 min", "−2 min", or "same" within half a minute. */
    val label: String
        get() {
            val minutes = (deltaSeconds / 60.0).roundToInt()
            return when {
                minutes > 0 -> "+$minutes min"
                minutes < 0 -> "−${-minutes} min"
                else -> "same"
            }
        }

    companion object {
        /**
         * [alternative] set against [ahead], the followed route from the same point on, or
         * null when there is nothing to show: it shares every point, or it is so much longer
         * ([worthShowing]) that it is not a choice anyone driving would make.
         */
        fun of(id: Long, ahead: Route, alternative: Route): LiveAlternative? {
            val shared = HashSet<Long>(ahead.points * 2)
            for (i in 0 until ahead.points) shared += key(ahead.latitudes[i], ahead.longitudes[i])
            // BRouter's first and last points are the ends snapped onto the road, between two
            // of its nodes, so they are never points of the other route even where the two
            // are one road. Counted as the alternative's own, they made every alternative
            // "leave" where the vehicle already was, and it was gone on the next fix (the
            // first lab drive). Points that near either end are left out of the comparison.
            val n = alternative.points
            val own = (0 until n).filter { i ->
                key(alternative.latitudes[i], alternative.longitudes[i]) !in shared &&
                    RouteFollower.distance(alternative.latitudes[i], alternative.longitudes[i], alternative.latitudes[0], alternative.longitudes[0]) > END_M &&
                    RouteFollower.distance(alternative.latitudes[i], alternative.longitudes[i], alternative.latitudes[n - 1], alternative.longitudes[n - 1]) > END_M
            }
            if (own.isEmpty()) return null
            val delta = alternative.seconds - ahead.seconds
            if (!worthShowing(delta, ahead.seconds)) return null
            val split = (own.first() - 1).coerceAtLeast(0)
            // Near the turn that takes it, not the middle of it: at street zoom the middle of
            // a Boston alternative was 5 to 6 km off the pane, and a bubble is wanted most as
            // the vehicle comes up to where it would turn. [LABEL_M] into its own stretch.
            val ownSet = own.toHashSet()
            var label = own.first()
            var metres = 0.0
            while (label + 1 in ownSet && metres < LABEL_M) {
                metres += RouteFollower.distance(
                    alternative.latitudes[label], alternative.longitudes[label], alternative.latitudes[label + 1], alternative.longitudes[label + 1],
                )
                label++
            }
            return LiveAlternative(
                id, alternative, delta,
                alternative.latitudes[label], alternative.longitudes[label],
                alternative.latitudes[split], alternative.longitudes[split],
            )
        }

        /**
         * No more than ten minutes longer, or a quarter of the time still to go when that is
         * more: "+45 min" on a half-hour trip is noise on the map, not a choice.
         */
        fun worthShowing(deltaSeconds: Int, aheadSeconds: Int): Boolean =
            deltaSeconds <= maxOf(MAX_DELTA_S, aheadSeconds / 4)

        private fun key(lat: Double, lon: Double): Long =
            (Math.round(lat * 1e6) shl 32) xor (Math.round(lon * 1e6) and 0xFFFFFFFFL)

        const val MAX_DELTA_S = 600

        /** How near either end a point is the snapped end rather than the road itself. */
        const val END_M = 50.0

        /** How far into its own stretch an alternative's bubble goes. */
        const val LABEL_M = 300.0

        /**
         * The point of [fromVehicle] to look for alternatives from: [LEAD_S] of the route's own
         * time ahead and at least [LEAD_M], or its last point but one on a route shorter than
         * that.
         */
        fun leadIndex(fromVehicle: Route): Int {
            var metres = 0.0
            for (i in 1 until fromVehicle.points) {
                metres += RouteFollower.distance(
                    fromVehicle.latitudes[i - 1], fromVehicle.longitudes[i - 1], fromVehicle.latitudes[i], fromVehicle.longitudes[i],
                )
                val seconds = fromVehicle.pointSeconds.getOrElse(i) { (metres / FALLBACK_MPS).toFloat() }
                if (metres >= LEAD_M && seconds >= LEAD_S) return i
            }
            return (fromVehicle.points - 2).coerceAtLeast(0)
        }

        /** How far ahead alternatives are looked for from: time enough to read a bubble and choose. */
        const val LEAD_S = 45f
        const val LEAD_M = 400.0

        /** The pace a route with no point times is taken at, for [leadIndex] only. */
        private const val FALLBACK_MPS = 13.0
    }
}

/**
 * Other routes to the destination, looked for now and then while driving, for the projected
 * map: grey lines under the route with a "+6 min" bubble each, and a tap on a bubble takes
 * that route.
 *
 * **When.** Only while a dashboard projects, a destination is set, guidance is on the route
 * and not re-routing, more than [MIN_AHEAD_M] is left, and Routing → Show alternatives is on.
 * The first look is [FIRST_DELAY_MS] after a route starts being followed; then every
 * [INTERVAL_MS]. A new route (re-route, new destination, a choice) drops what is shown and
 * starts the clock again.
 *
 * **How.** [AlternativeFinder] over the rest of the followed route from a point
 * [LiveAlternative.LEAD_S] and at least [LiveAlternative.LEAD_M] ahead of the vehicle
 * ([LiveAlternative.leadIndex]), so an alternative leaves the route far enough ahead to be
 * read and chosen. On the routing thread through [Routing.explore],
 * so any route request stops it at once. Each is set against the followed route's own
 * time from the same point.
 *
 * **Expiry.** An alternative goes once the vehicle is past the point where it leaves the
 * followed route: it is no longer a way the driver can go.
 *
 * Main-thread state, like [Guide]. [shown] is read by the scene thread.
 */
object LiveAlternatives {
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** What the map draws now. Replaced whole, never changed in place. */
    @Volatile
    var shown: List<LiveAlternative> = emptyList()
        private set

    /** Told whenever [shown] changes, installed by [ProjectionService] for the dashboard's bubbles. */
    var listener: ((List<LiveAlternative>) -> Unit)? = null

    private var token = -1L
    private var followedSince = 0L
    private var lastLookMs = 0L
    private var looking = false
    private var nextId = 1L
    /** Metres along the followed route to each shown alternative's split point, by id. */
    private val splitAlong = HashMap<Long, Double>()

    var looks = 0
        private set
    var found = 0
        private set
    var chosen = 0
        private set
    var lastLookMsTaken = 0L
        private set

    /** Every fix [Guide] follows, after it has placed it. Main thread. */
    fun onFix(context: Context, result: FollowResult, nowMs: Long) {
        if (Routing.token != token) {
            // A route request is what changes the token, and it stops or skips any look
            // in flight (RoutePlanner.explore); that look's answer is for a route now gone.
            token = Routing.token
            followedSince = nowMs
            lastLookMs = 0L
            looking = false
            publish(emptyList())
        }
        if (shown.isNotEmpty()) {
            val left = shown.filter { alternative -> result.alongM <= (splitAlong[alternative.id] ?: Double.MAX_VALUE) + PASSED_M }
            if (left.size != shown.size) publish(left)
        }
        if (looking || Navigation.vehicleFix == null || Navigation.destination == null) return
        if (!AppSettings.load(context).routing.showAlternatives) {
            if (shown.isNotEmpty()) publish(emptyList())
            return
        }
        if (nowMs - followedSince < FIRST_DELAY_MS) return
        if (lastLookMs != 0L && nowMs - lastLookMs < INTERVAL_MS) return
        val (follower, onRoute) = Guide.position() ?: return
        if (follower.totalM - onRoute.alongM < MIN_AHEAD_M) return
        look(context, follower, onRoute, nowMs)
    }

    private fun look(context: Context, follower: RouteFollower, onRoute: FollowResult, nowMs: Long) {
        val destination = Navigation.destination ?: return
        // From a point a little way ahead, not from the vehicle: an alternative that leaves
        // at the very next junction is behind the vehicle before the search has finished.
        // The first lab drive's two both did, and were gone as soon as they were shown.
        val fromVehicle = follower.route.remainder(follower.indexAt(onRoute.alongM) + 1)
        val ahead = fromVehicle.remainder(LiveAlternative.leadIndex(fromVehicle))
        val options = AppSettings.load(context).routing
        val bearing = Guide.fix()?.second
        val trip = PreviewRequest(
            token, ahead.latitudes[0], ahead.longitudes[0], destination.latitude, destination.longitude,
            bearing, options, ahead,
        )
        val forToken = token
        looking = true
        lastLookMs = nowMs
        looks++
        val maps = Routing.nameMaps
        Routing.explore(context) { router, started, cancelled ->
            val begun = System.nanoTime()
            val routes = ArrayList<Route>()
            try {
                AlternativeFinder(router, trip, RoutePlanner.MAX_RUNNING_MS, started, cancelled).find { routes += it }
            } finally {
                // Named, so a route taken from a bubble says its roads on the cluster.
                val named = routes.map { route ->
                    try {
                        if (maps != null) StreetNames.name(route, maps) else route
                    } catch (e: RuntimeException) {
                        route
                    }
                }
                val took = (System.nanoTime() - begun) / 1_000_000L
                main.post { finished(forToken, follower, ahead, named, took) }
            }
        }
    }

    private fun finished(forToken: Long, follower: RouteFollower, ahead: Route, routes: List<Route>, tookMs: Long) {
        looking = false
        lastLookMsTaken = tookMs
        // The same token is the same route, so the follower that was asked about is still the one following it.
        if (forToken != token || forToken != Routing.token) return
        val made = routes.mapNotNull { LiveAlternative.of(nextId++, ahead, it) }
        splitAlong.clear()
        for (alternative in made) splitAlong[alternative.id] = follower.alongNearest(alternative.splitLat, alternative.splitLon)
        found += made.size
        // How many and how much longer, never where: the event log is part of the report.
        SessionController.note(
            "looked for other routes in $tookMs ms: ${made.size} shown" +
                made.joinToString("") { " ${it.label}" },
        )
        publish(made)
    }

    /**
     * A bubble was tapped on the dashboard: take that way, if it is still on show.
     *
     * The alternative starts a little ahead of where the vehicle now is, so it is not
     * followed as it is. Its via point becomes the chosen route's ([Navigation.via]) and a
     * route is asked for from the vehicle through it: the road ahead and then the
     * alternative. One with no via point (BRouter's own, when no via route qualified) is
     * taken as it is, and the follower joins it or re-routes.
     */
    fun choose(context: Context, id: Long) {
        val alternative = shown.firstOrNull { it.id == id } ?: return
        chosen++
        SessionController.note("dashboard alternative chosen: ${alternative.label}")
        if (alternative.route.via != null) {
            Navigation.chose(alternative.route)
            Navigation.route(context, reroute = false, bearing = Guide.fix()?.second)
        } else {
            Routing.adopt(alternative.route, Routing.token)
        }
    }

    /** No route any more. */
    fun clear() {
        token = -1L
        looking = false
        splitAlong.clear()
        publish(emptyList())
    }

    private fun publish(list: List<LiveAlternative>) {
        shown = list
        listener?.invoke(list)
    }

    /** The report's line: counts and a time, no places. */
    fun report(): String =
        "live_alternatives_looks=$looks live_alternatives_found=$found live_alternatives_chosen=$chosen " +
            "live_alternatives_shown=${shown.size} live_alternatives_last_ms=$lastLookMsTaken\n"

    const val FIRST_DELAY_MS = 30_000L
    const val INTERVAL_MS = 5 * 60_000L
    const val MIN_AHEAD_M = 3_000.0

    /** How far past the split point counts as past it, so a fix jittering at it does not. */
    const val PASSED_M = 30.0
}
