package org.openautoandroid.source

import android.content.Context
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The guidance loop (P6.5): follows the route [Routing] computed, turns each fix into the
 * next maneuver for the vehicle's own cluster, re-routes when the vehicle leaves the route,
 * and says when it has arrived.
 *
 * Main-thread state, like [Routing] and [Navigation]: vehicle fixes and route results both
 * arrive there. The work per fix is one snap over a window of the route, so it stays off
 * the routing thread and never waits for it; a re-route is a request to that thread like
 * any other, and the vehicle is told `REROUTING` until the answer comes back.
 *
 * What it publishes goes to the session through [NativeBridge.submitGuidance] and is sent
 * on the `navigation` channel only when that channel is route-driven and the head unit
 * gave this source its cluster. With no projection it still follows and counts, so a
 * drive's report says what guidance did even when the cluster showed nothing.
 *
 * **Nothing about where the vehicle is or is going reaches the report or the event log**
 * (P6 plan §9.6): no coordinates, no instruction text. Offsets and distances are how far,
 * not where.
 */
object Guide {
    /** How long a re-route may take before another is asked for, if still off the route. */
    private const val REROUTE_RETRY_MS = 15_000L
    /**
     * Metres of travel a heading is measured over. The direction of travel comes from the
     * fixes themselves rather than the head unit's bearing field, whose scale is still
     * inferred ([ScaleRule.BEARING]); over this distance GPS noise of a few metres moves
     * it by a few degrees at most.
     */
    private const val HEADING_BASE_M = 15.0

    /** Session to publish to while one projects; 0 for none. */
    private var handle = 0L
    private var follower: RouteFollower? = null
    private var generation = 0L
    private var rerouting = false
    private var rerouteAt = 0L

    var fixes = 0L
        private set
    var routesFollowed = 0
        private set
    var reroutes = 0
        private set
    var reroutesDone = 0
        private set
    var rerouteFailures = 0
        private set
    var arrivals = 0
        private set
    var published = 0L
        private set
    var joined = 0
        private set
    /** Largest distance from the route line of a fix snapped to it, metres. */
    var maxOnRouteOffsetM = 0.0
        private set
    private var last: FollowResult? = null
    private var lastSource = "-"
    /** The newest fix, kept only to place a new route at once; never reported. */
    private var lastFix: Pair<Double, Double>? = null
    /** A fix at least [HEADING_BASE_M] behind the newest, and the travel bearing between them. */
    private var headingFrom: Pair<Double, Double>? = null
    private var heading: Double? = null

    /** A session is projecting: publish to it, starting with what is already known. */
    fun attach(handle: Long) {
        this.handle = handle
        publish()
    }

    fun detach(handle: Long) {
        if (this.handle == handle) this.handle = 0L
    }

    /** [Routing] has a new route to follow: a first route, a new destination, or a re-route. */
    fun onRoute(route: Route) {
        generation++
        routesFollowed++
        val follower = RouteFollower(route, generation)
        this.follower = follower
        last = null
        if (rerouting) {
            rerouting = false
            reroutesDone++
        }
        // Placed on the new route from the fix already in hand, so a re-route goes
        // straight from `REROUTING` to its first maneuver instead of through a moment of
        // having nothing to say.
        lastFix?.let { (lat, lon) ->
            val result = follower.update(lat, lon, SystemClock.elapsedRealtime())
            observe(null, result)
            last = result
        }
        publish()
    }

    /** The route asked for failed. Still off the route, so the next retry is on the clock. */
    fun onRouteFailed() {
        if (rerouting) rerouteFailures++
    }

    /** No route any more: the destination was cleared. */
    fun clear() {
        follower = null
        last = null
        rerouting = false
        publish()
    }

    /**
     * One position fix, from the vehicle (or the phone, while the vehicle has none).
     * [source] is `vehicle` or `phone`, for the report.
     */
    fun onFix(context: Context, latitude: Double, longitude: Double, source: String) {
        lastFix = latitude to longitude
        val from = headingFrom
        if (from == null) {
            headingFrom = latitude to longitude
        } else if (RouteFollower.distance(from.first, from.second, latitude, longitude) >= HEADING_BASE_M) {
            heading = RouteFollower.bearing(from.first, from.second, latitude, longitude)
            headingFrom = latitude to longitude
        }
        val follower = follower ?: return
        val now = SystemClock.elapsedRealtime()
        fixes++
        lastSource = source
        val previous = last?.state
        val result = follower.update(latitude, longitude, now)
        last = result
        observe(previous, result)
        if (result.state == FollowState.OFF_ROUTE) reroute(context, result, now)
        publish()
    }

    /** Counts what one result changed; [previous] is null for a route's first fix. */
    private fun observe(previous: FollowState?, result: FollowResult) {
        when (result.state) {
            FollowState.ON_ROUTE -> {
                if (previous != FollowState.ON_ROUTE) {
                    joined++
                    if (previous == FollowState.OFF_ROUTE) SessionController.note("back on the route")
                }
                // Only fixes the follower actually snapped: one on its way off the route
                // is still reported on it until it has been off long enough.
                if (result.offsetM <= RouteFollower.ON_ROUTE_M) {
                    maxOnRouteOffsetM = max(maxOnRouteOffsetM, result.offsetM)
                }
            }
            FollowState.ARRIVED -> if (previous != FollowState.ARRIVED) {
                arrivals++
                SessionController.note("arrived at the destination")
            }
            FollowState.OFF_ROUTE, FollowState.JOINING -> {}
        }
    }

    private fun reroute(context: Context, result: FollowResult, now: Long) {
        if (rerouting && now - rerouteAt < REROUTE_RETRY_MS) return
        if (Navigation.destination == null) return
        rerouting = true
        rerouteAt = now
        reroutes++
        // How far off, never where: the event log is part of the saved report.
        SessionController.note("left the route by ${result.offsetM.roundToInt()} m; re-routing")
        Navigation.route(context, reroute = true, bearing = heading)
    }

    /** Puts the current state on its way to the cluster, if a session is listening. */
    private fun publish() {
        if (handle <= 0L) return
        val result = last
        val step = result?.step
        val code = when {
            follower == null -> NativeBridge.submitGuidance(handle, NativeBridge.GUIDANCE_IDLE)
            // Off the route with no destination to re-route to (P6.3's lab route) keeps
            // counting down from where the vehicle left it rather than claiming a re-route.
            rerouting -> NativeBridge.submitGuidance(handle, NativeBridge.GUIDANCE_REROUTING)
            result?.state == FollowState.ARRIVED -> NativeBridge.submitGuidance(handle, NativeBridge.GUIDANCE_ARRIVED)
            // A route and no fix on it yet: nothing honest to count down from.
            step == null -> NativeBridge.submitGuidance(handle, NativeBridge.GUIDANCE_IDLE)
            else -> NativeBridge.submitGuidance(
                handle,
                NativeBridge.GUIDANCE_GUIDING,
                step = step.id,
                maneuver = step.maneuver.type,
                exit = step.maneuver.exit,
                instruction = step.maneuver.instruction,
                stepMetres = step.metres,
                stepSeconds = step.seconds,
                destinationMetres = step.destinationMetres,
                destinationSeconds = step.destinationSeconds,
            )
        }
        if (code == NativeBridge.NOW_PLAYING_CHANGED) published++
    }

    /**
     * The `[guidance]` report lines. Counts, states, raw maneuver values and distances —
     * no coordinates, no instruction text, no destination.
     */
    fun report(): String = buildString {
        val result = last
        appendLine(
            "guidance_state=${if (follower == null) "idle" else if (rerouting) "rerouting" else result?.state?.label ?: "waiting"} " +
                "guidance_fix_source=$lastSource guidance_fixes=$fixes guidance_published=$published " +
                "guidance_routes=$routesFollowed guidance_joined=$joined",
        )
        appendLine(
            "guidance_reroutes=$reroutes guidance_reroutes_done=$reroutesDone " +
                "guidance_reroute_failures=$rerouteFailures guidance_arrivals=$arrivals " +
                "guidance_max_on_route_offset_m=${maxOnRouteOffsetM.roundToInt()}",
        )
        val follower = follower
        val step = result?.step
        appendLine(
            "guidance_route_maneuvers=${follower?.maneuverTypes()?.joinToString(",")?.ifEmpty { "-" } ?: "-"} " +
                "guidance_next=${step?.let { "maneuver=${it.maneuver.type} exit=${it.maneuver.exit} step_m=${it.metres.roundToInt()} to_step_s=${it.seconds} to_destination_m=${it.destinationMetres.roundToInt()}" } ?: "-"}",
        )
    }
}
