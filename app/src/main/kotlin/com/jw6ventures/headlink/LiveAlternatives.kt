package com.jw6ventures.headlink

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
    /**
     * Its time less the followed route's time still to go, from the same point: BRouter's
     * seconds, and while live traffic is held each route's own traffic delay too (P9.6), so
     * a bubble and the offer never disagree about which is quicker.
     */
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
         * [extraSeconds] is its traffic delay less [ahead]'s (P9.6), 0 without traffic.
         */
        @JvmOverloads
        fun of(id: Long, ahead: Route, alternative: Route, extraSeconds: Int = 0): LiveAlternative? {
            val own = own(ahead, alternative)
            if (own.isEmpty()) return null
            val delta = alternative.seconds - ahead.seconds + extraSeconds
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
         * The points of [alternative] that are its own, not [ahead]'s, in order.
         *
         * BRouter's first and last points are the ends snapped onto the road, between two of
         * its nodes, so they are never points of the other route even where the two are one
         * road. Counted as the alternative's own, they made every alternative "leave" where
         * the vehicle already was, and it was gone on the next fix (the first lab drive).
         * Points that near either end are left out of the comparison.
         */
        private fun own(ahead: Route, alternative: Route): List<Int> {
            val shared = HashSet<Long>(ahead.points * 2)
            for (i in 0 until ahead.points) shared += key(ahead.latitudes[i], ahead.longitudes[i])
            val n = alternative.points
            return (0 until n).filter { i ->
                key(alternative.latitudes[i], alternative.longitudes[i]) !in shared &&
                    RouteFollower.distance(alternative.latitudes[i], alternative.longitudes[i], alternative.latitudes[0], alternative.longitudes[0]) > END_M &&
                    RouteFollower.distance(alternative.latitudes[i], alternative.longitudes[i], alternative.latitudes[n - 1], alternative.longitudes[n - 1]) > END_M
            }
        }

        /**
         * The index in [alternative] of the last point it shares with [ahead] before leaving
         * it, as [of] finds it; null when it never leaves. The traffic offer's split (P9.6).
         */
        fun splitIndex(ahead: Route, alternative: Route): Int? =
            own(ahead, alternative).firstOrNull()?.let { (it - 1).coerceAtLeast(0) }

        /**
         * [alternative] made to pass through the middle of its own stretch: a via point for
         * one found with no via of its own (live traffic's way round, [AlternativeSearch]),
         * so that following it from the vehicle keeps to it as a bubble's alternative does
         * ([LiveAlternatives.choose]). [alternative] itself when it has a via point already or
         * never leaves [ahead].
         */
        fun throughOwn(ahead: Route, alternative: Route): Route {
            if (alternative.via != null) return alternative
            val own = own(ahead, alternative)
            if (own.isEmpty()) return alternative
            val middle = own[own.size / 2]
            return Route(
                alternative.latitudes, alternative.longitudes, alternative.distanceM, alternative.seconds,
                alternative.maneuverIndices, alternative.exitNumbers, alternative.commands, alternative.pointSeconds,
                alternative.roadStarts, alternative.roadNames, alternative.topSpeedKmh,
                doubleArrayOf(alternative.latitudes[middle], alternative.longitudes[middle]),
                alternative.nogoCount, alternative.closuresOpened, alternative.exitSigns,
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
 * The live alternatives' search (P6.6's [AlternativeFinder]) with live traffic's no-go
 * points (P9.5, P9.6). Pure JVM over a [Router], so the host test runs it on real tiles.
 */
object AlternativeSearch {
    /**
     * Alternatives to [trip]'s route, each given to [found] as it is chosen, none a
     * [RoutePreview.duplicate] of another or of the route itself. [trip]'s points go into
     * every search ([PreviewRequest.nogos]).
     *
     * With [aroundTraffic], the first search is BRouter's own best route over the same trip
     * with the points: the way round the traffic that BRouter would take, which the finder's
     * corridors, built to differ from the route on show, may not include. It is made to pass
     * through its own stretch ([LiveAlternative.throughOwn]) so that taking it from the
     * vehicle keeps to it. One search more, asked only when the traffic ahead is heavy enough
     * for a way round to be offered ([Traffic.worthLooking]). Returns the last failure, or null.
     */
    fun find(
        router: Router,
        trip: PreviewRequest,
        aroundTraffic: Boolean,
        maxRunningMs: Long,
        started: (btools.router.RoutingEngine) -> Unit,
        cancelled: () -> Boolean,
        found: (Route) -> Unit,
    ): String? {
        val given = mutableListOf(trip.route)
        var failure: String? = null
        if (aroundTraffic && trip.nogos.isNotEmpty() && !cancelled()) {
            try {
                val around = router.compute(trip.alternative(0), maxRunningMs, started)
                if (!RoutePreview.duplicate(around, given)) {
                    given += around
                    found(LiveAlternative.throughOwn(trip.route, around))
                }
            } catch (e: RoutingException) {
                failure = e.message
            }
        }
        if (cancelled()) return failure
        val finderFailure = AlternativeFinder(router, trip, maxRunningMs, started, cancelled).find { route ->
            if (!RoutePreview.duplicate(route, given)) {
                given += route
                found(route)
            }
        }
        return finderFailure ?: failure
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
 * **Live traffic (P9.5, P9.6).** While traffic is held, every search carries its no-go
 * points ([AlternativeSearch]), and each alternative is laid on the traffic too
 * ([Traffic.assess], off the main thread) before it is shown: its bubble's "+N min" counts
 * both routes' delays, and one that saves enough becomes the traffic offer on the divided
 * turn card, and is then not also a bubble. A traffic update heavy enough to be worth it
 * asks for one look at once ([lookNow]), whether or not the bubbles are on, in place of
 * the next timed one; that is the only extra search traffic makes.
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
    /** A traffic look was asked for while one was running: look again when it ends. */
    private var lookAgain = false
    /**
     * A traffic look was asked for before it could run: no fix had yet placed the vehicle on
     * the route it was asked for (the first fetch often lands while joining), or that route
     * was newer than the last fix here. Taken at the next fix on the route, bubbles on or off.
     */
    private var trafficPending = false
    private var nextId = 1L
    /** Metres along the followed route to each shown alternative's split point, by id. */
    private val splitAlong = HashMap<Long, Double>()

    var looks = 0
        private set
    /** Looks asked for by a traffic update (P9.6) rather than by the clock. */
    var trafficLooks = 0
        private set
    var found = 0
        private set
    var chosen = 0
        private set
    var lastLookMsTaken = 0L
        private set

    /** Every fix [Guide] follows, after it has placed it. Main thread. */
    fun onFix(context: Context, result: FollowResult, nowMs: Long) {
        if (Routing.token != token) adopt(nowMs)
        if (shown.isNotEmpty()) {
            val left = shown.filter { alternative -> result.alongM <= (splitAlong[alternative.id] ?: Double.MAX_VALUE) + PASSED_M }
            if (left.size != shown.size) publish(left)
        }
        if (looking || Navigation.vehicleFix == null || Navigation.destination == null) return
        if (trafficPending && result.state == FollowState.ON_ROUTE) {
            trafficPending = false
            // Still worth it: the traffic may have eased, or gone, while waiting for this fix.
            if (Traffic.worthLooking()) {
                val (follower, onRoute) = Guide.position() ?: return
                if (follower.totalM - onRoute.alongM >= MIN_AHEAD_M) {
                    look(context, follower, onRoute, nowMs, traffic = true)
                    return
                }
            }
        }
        if (!AppSettings.load(context).routing.showAlternatives) {
            if (shown.isNotEmpty()) publish(emptyList())
            return
        }
        if (nowMs - followedSince < FIRST_DELAY_MS) return
        if (lastLookMs != 0L && nowMs - lastLookMs < INTERVAL_MS) return
        val (follower, onRoute) = Guide.position() ?: return
        if (follower.totalM - onRoute.alongM < MIN_AHEAD_M) return
        look(context, follower, onRoute, nowMs, traffic = false)
    }

    /**
     * A traffic update found enough delay ahead to be worth a way round (P9.6): look now,
     * with the new points, bubbles on or off, and put the next timed look an interval off.
     * One already running is followed by this one when it ends. Main thread.
     */
    fun lookNow(context: Context) {
        if (Navigation.destination == null) return
        if (looking && token == Routing.token) {
            lookAgain = true
            return
        }
        // Guide follows the route now requested (a vehicle stopped in a queue sends no new
        // fix, so the one it placed on the route may be the only one): take it up here too.
        // With no fix placing the vehicle on it yet, look at the next that does.
        val position = Guide.position()
        if (position == null) {
            trafficPending = true
            return
        }
        if (token != Routing.token) adopt(android.os.SystemClock.elapsedRealtime())
        val (follower, onRoute) = position
        if (follower.totalM - onRoute.alongM < MIN_AHEAD_M) return
        look(context, follower, onRoute, android.os.SystemClock.elapsedRealtime(), traffic = true)
    }

    /** A new route: what was shown or asked for was about the last one. */
    private fun adopt(nowMs: Long) {
        // A route request is what changes the token, and it stops or skips any look in
        // flight (RoutePlanner.explore); that look's answer is for a route now gone.
        token = Routing.token
        followedSince = nowMs
        lastLookMs = 0L
        looking = false
        lookAgain = false
        publish(emptyList())
    }

    private fun look(context: Context, follower: RouteFollower, onRoute: FollowResult, nowMs: Long, traffic: Boolean) {
        val destination = Navigation.destination ?: return
        // From a point a little way ahead, not from the vehicle: an alternative that leaves
        // at the very next junction is behind the vehicle before the search has finished.
        // The first lab drive's two both did, and were gone as soon as they were shown.
        val fromVehicle = follower.route.remainder(follower.indexAt(onRoute.alongM) + 1)
        val ahead = fromVehicle.remainder(LiveAlternative.leadIndex(fromVehicle))
        val aheadAlongM = follower.alongNearest(ahead.latitudes[0], ahead.longitudes[0])
        val options = AppSettings.load(context).routing
        val bearing = Guide.fix()?.second
        val trip = PreviewRequest(
            token, ahead.latitudes[0], ahead.longitudes[0], destination.latitude, destination.longitude,
            bearing, options, ahead, Traffic.nogos(options),
        )
        val aroundTraffic = Traffic.worthLooking()
        val forToken = token
        looking = true
        lookAgain = false
        lastLookMs = nowMs
        looks++
        if (traffic) trafficLooks++
        val maps = Routing.nameMaps
        val app = context.applicationContext
        // Answered in done, which runs even when the planner skips the look for a route
        // request: a re-route that fails keeps the token, and nothing else would clear
        // looking for it.
        val routes = ArrayList<Route>()
        var begun = System.nanoTime()
        var ran = false
        Routing.explore(
            context,
            done = {
                // Named, so a route taken from a bubble says its roads on the cluster.
                val named = routes.map { route ->
                    val withNames = try {
                        if (maps != null) StreetNames.name(route, maps) else route
                    } catch (e: RuntimeException) {
                        route
                    }
                    Routing.signed(app, withNames)
                }
                val took = (System.nanoTime() - begun) / 1_000_000L
                val looked = ran
                main.post { finished(app, forToken, follower, ahead, aheadAlongM, named, took, looked) }
            },
        ) { router, started, cancelled ->
            ran = true
            begun = System.nanoTime()
            AlternativeSearch.find(router, trip, aroundTraffic, RoutePlanner.MAX_RUNNING_MS, started, cancelled) { routes += it }
        }
    }

    private fun finished(
        context: Context,
        forToken: Long,
        follower: RouteFollower,
        ahead: Route,
        aheadAlongM: Double,
        routes: List<Route>,
        tookMs: Long,
        looked: Boolean,
    ) {
        looking = false
        if (!looked) {
            // Skipped for a route request: what is on show stays, and the next fix may look.
            if (lookAgain && forToken == token && forToken == Routing.token) lookNow(context)
            return
        }
        lastLookMsTaken = tookMs
        // The same token is the same route, so the follower that was asked about is still the one following it.
        if (forToken != token || forToken != Routing.token) return
        // Laid on the traffic held, if any, before anything is shown; the offer is decided there.
        Traffic.assess(follower.route, ahead, aheadAlongM, routes) { extras, offered ->
            if (forToken != token || forToken != Routing.token) return@assess
            val made = routes.indices
                .filter { routes[it] !== offered }
                .mapNotNull { LiveAlternative.of(nextId++, ahead, routes[it], extras[it]) }
            splitAlong.clear()
            for (alternative in made) splitAlong[alternative.id] = follower.alongNearest(alternative.splitLat, alternative.splitLon)
            found += made.size
            // How many and how much longer, never where: the event log is part of the report.
            SessionController.note(
                "looked for other routes in $tookMs ms: ${made.size} shown" +
                    made.joinToString("") { " ${it.label}" } + if (offered != null) "; one offered for traffic" else "",
            )
            publish(if (AppSettings.load(context).routing.showAlternatives) made else emptyList())
            if (lookAgain) lookNow(context)
        }
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
        follow(context, alternative.route)
    }

    /**
     * **Take** on the traffic offer (P9.6): follow its route exactly as a tapped
     * bubble's ([choose]). Every offered route has a via point ([AlternativeSearch]), so the
     * route is asked for from the vehicle through it, with the traffic's points.
     */
    fun take(context: Context, offer: TrafficOffer) {
        follow(context, offer.route)
    }

    private fun follow(context: Context, route: Route) {
        if (route.via != null) {
            Navigation.chose(route)
            Navigation.route(context, reroute = false, bearing = Guide.fix()?.second)
        } else {
            Routing.adopt(route, Routing.token)
        }
    }

    /** No route any more. */
    fun clear() {
        token = -1L
        looking = false
        lookAgain = false
        trafficPending = false
        splitAlong.clear()
        publish(emptyList())
    }

    private fun publish(list: List<LiveAlternative>) {
        shown = list
        listener?.invoke(list)
    }

    /** The report's line: counts and a time, no places. */
    fun report(): String =
        "live_alternatives_looks=$looks live_alternatives_traffic_looks=$trafficLooks live_alternatives_found=$found " +
            "live_alternatives_chosen=$chosen live_alternatives_shown=${shown.size} live_alternatives_last_ms=$lastLookMsTaken\n"

    const val FIRST_DELAY_MS = 30_000L
    const val INTERVAL_MS = 5 * 60_000L
    const val MIN_AHEAD_M = 3_000.0

    /** How far past the split point counts as past it, so a fix jittering at it does not. */
    const val PASSED_M = 30.0
}
