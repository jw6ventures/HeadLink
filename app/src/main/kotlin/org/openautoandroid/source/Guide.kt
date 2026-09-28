package org.openautoandroid.source

import android.content.Context
import android.os.Handler
import android.os.Looper
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
 *
 * **Spoken prompts (P8.2).** Everything published also goes through [VoicePrompts], which
 * decides whether this moment gets a prompt; one it returns goes to [Speaker.guide], whose
 * gate (the trip panel's toggle, a call, the engine, Bluetooth) decides whether it is
 * heard. The speed each fix came with is for that timing only and is never reported.
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

    /**
     * How long "You have arrived" stays on the dashboard before the route ends itself
     * (P7.4). The plan has the favourites come back when a trip is ended **or reached**
     * (§5.4) and the turn card go away with the route (§5.3), while End route is never
     * hidden while a route exists; ending the route a little after arrival is what makes
     * all three true. Long enough to read while pulling in and parking; the cluster
     * already went `INACTIVE` at arrival, so ending the route changes nothing on the wire.
     */
    const val ARRIVAL_HOLD_MS = 30_000L

    /** How far past the via point counts as having passed it, so jitter at it does not. */
    private const val VIA_PASSED_M = 50.0

    /**
     * Told what the dashboard's turn card and trip panel should say about the route each
     * time guidance publishes (P7.4): installed by [ProjectionService] while a dashboard
     * projects. It is the driver's own screen, so it carries the instruction the cluster
     * gets, road names and all; the report still never does.
     */
    var turnListener: ((UpcomingTurn?) -> Unit)? = null

    /**
     * Told when the phone's Voice guidance switch turns guidance on or off (P8.3), so the
     * trip panel's toggle shows the same saved value: installed by [ProjectionService]
     * while a dashboard projects, like [turnListener].
     */
    var voiceListener: ((Boolean) -> Unit)? = null

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
    /** Routes that ended themselves [ARRIVAL_HOLD_MS] after arriving. */
    var arrivalEnds = 0
        private set
    /** Largest distance from the route line of a fix snapped to it, metres. */
    var maxOnRouteOffsetM = 0.0
        private set
    private var last: FollowResult? = null
    private var lastSource = "-"
    /** The newest fix, kept only to place a new route at once; never reported. */
    private var lastFix: Pair<Double, Double>? = null
    /** The newest fix's speed, metres a second, for prompt timing only (P8 §5.1); never reported. */
    private var lastSpeed: Double? = null

    /**
     * When turn prompts are spoken and what they say (P8.2). Its own
     * [VoiceSettings.enabled] stays on: the trip panel's toggle is [Speaker]'s gate, which
     * counts every prompt it holds back as `toggle_off` (plan §9), and stage progress is
     * tracked either way, so turning the toggle on releases no backlog.
     */
    private val voice = VoicePrompts()

    /**
     * The drive's pace against the route's estimate (Routing → Learn my pace), put on the
     * time to the destination everywhere it is shown: the cluster, the trip panel and
     * Home. The time to the next maneuver is left as BRouter gave it; the prompts time
     * themselves from the vehicle's speed.
     */
    private val pace = PaceTracker()

    /** The Routing screen's Learn my pace, set by [Navigation] at every request and change. */
    var learnPace = RouteOptions().learnPace
    /** A fix at least [HEADING_BASE_M] behind the newest, and the travel bearing between them. */
    private var headingFrom: Pair<Double, Double>? = null
    private var heading: Double? = null

    /** Created on first use: the host tests load this class with no Android looper. */
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var arrivalEnd: Runnable? = null

    /** Metres along the followed route to its via point while that is still ahead, else null. */
    private var viaAlongM: Double? = null

    /**
     * Where the vehicle is on the followed route, for [LiveAlternatives]: the follower, and
     * the last fix's result, while following and on the route. Main thread.
     */
    fun position(): Pair<RouteFollower, FollowResult>? {
        val follower = follower ?: return null
        val result = last ?: return null
        if (rerouting || result.state != FollowState.ON_ROUTE) return null
        return follower to result
    }

    /** The newest fix and the travel bearing, for a route that starts where the vehicle is. */
    fun fix(): Pair<Pair<Double, Double>, Double?>? = lastFix?.let { it to heading }

    /** A session is projecting: publish to it, starting with what is already known. */
    fun attach(handle: Long) {
        this.handle = handle
        publish()
    }

    fun detach(handle: Long) {
        if (this.handle == handle) this.handle = 0L
    }

    /**
     * The voice settings in force (P8.2), from the saved settings when a projection starts,
     * and the header's speed unit, whose system the spoken distances use. The toggle is
     * left to [Speaker]; see [voice].
     */
    fun voiceSettings(settings: VoiceSettings, unit: SpeedUnit) {
        voice.settings = settings.copy(enabled = true)
        voice.unit = unit
    }

    /**
     * The phone's Voice guidance screen saved [settings] (P8.3, plan §7): taken at once by
     * the prompts, in the unit the projection started with, so a change mid-drive needs no
     * restart. On or off is the trip panel's toggle too: it goes to [Speaker]'s gate, and
     * to the dashboard through [voiceListener], only when it changed. Unlike a press on the
     * head unit, turning it on from the phone says no confirming prompt; the phone has Play
     * a sample for that, and whoever is holding the phone is often not the driver.
     */
    fun voiceChanged(settings: VoiceSettings) {
        voice.settings = settings.copy(enabled = true)
        if (Speaker.guidanceEnabled == settings.enabled) return
        Speaker.guidanceOn(settings.enabled)
        voiceListener?.invoke(settings.enabled)
    }

    /**
     * The trip panel's voice toggle was pressed (plan §6). Off stops a prompt being spoken
     * at once. On says the current step's prompt straight away, as confirmation, and
     * prompts carry on as normal from there; with no step to confirm (no route, re-routing,
     * arrived) it says nothing.
     */
    fun voiceToggled(on: Boolean) {
        Speaker.guidanceOn(on)
        if (!on) return
        val step = last?.step?.takeIf { follower != null && !rerouting && last?.state != FollowState.ARRIVED }
        voice.confirm(step, lastSpeed, SystemClock.elapsedRealtime())?.let(::speak)
    }

    /** [Routing] has a new route to follow: a first route, a new destination, or a re-route. */
    fun onRoute(route: Route) {
        generation++
        routesFollowed++
        cancelArrivalEnd()
        // A re-route is the same drive, so what it taught about the pace stands.
        if (!rerouting) pace.reset()
        val follower = RouteFollower(route, generation)
        this.follower = follower
        viaAlongM = route.via?.takeIf { Navigation.via != null }?.let { follower.alongNearest(it[0], it[1]) }
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

    /**
     * Metres and seconds still to go, from the last fix on the route, or null when there
     * is no route, no fix on it yet, a re-route in flight, or the drive has arrived. The
     * live figures, not the route's totals: for the phone's Home route line (P7.7), from
     * the same position the trip panel and the cluster count from. Main thread.
     */
    fun remaining(): Pair<Double, Int>? =
        if (follower == null || rerouting) null else last?.step?.let { it.destinationMetres to paced(it.destinationSeconds) }

    /** [seconds] of BRouter's estimate to the destination, as shown: with the pace learned, if asked for. */
    private fun paced(seconds: Int): Int = if (learnPace) pace.apply(seconds) else seconds

    /** The route asked for failed. Still off the route, so the next retry is on the clock. */
    fun onRouteFailed() {
        if (rerouting) rerouteFailures++
    }

    /**
     * No route any more: the destination was cleared, by End route, the phone's Clear
     * route, or the hold after arriving. Published as ended rather than idle (P7.4), so the
     * session hands the cluster back to the head unit's own navigation until the next
     * route asks for it again. Everything else that has no route to publish (a session
     * starting, a route with no fix on it yet) stays idle and keeps the cluster.
     */
    fun clear() {
        cancelArrivalEnd()
        follower = null
        last = null
        rerouting = false
        pace.reset()
        voice.reset()
        LiveAlternatives.clear()
        publish(ended = true)
    }

    /**
     * One position fix, from the vehicle (or the phone, while the vehicle has none).
     * [source] is `vehicle` or `phone`, for the report. [speedMps] is the head unit's
     * `car_speed`, else its `gps_speed`, else the phone's own, else null (P8 plan §5.1):
     * used to time spoken prompts, never reported.
     */
    fun onFix(context: Context, latitude: Double, longitude: Double, source: String, speedMps: Double? = null) {
        lastFix = latitude to longitude
        lastSpeed = speedMps
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
        // Learned whether or not it is shown, so turning it on mid-drive has a pace to use.
        pace.onFix(generation, result.step?.destinationSeconds?.takeIf { result.state == FollowState.ON_ROUTE }, now)
        // Past the chosen alternative's via point, the best route is the chosen one again:
        // a re-route from here must not be sent back to it.
        viaAlongM?.let { at ->
            if (result.state == FollowState.ON_ROUTE && result.alongM > at + VIA_PASSED_M) {
                viaAlongM = null
                Navigation.viaPassed()
            }
        }
        LiveAlternatives.onFix(context, result, now)
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
                scheduleArrivalEnd()
            }
            FollowState.OFF_ROUTE, FollowState.JOINING -> {}
        }
    }

    /**
     * Ends this route [ARRIVAL_HOLD_MS] from now, through the same [Navigation.clear] the
     * phone's Clear route and the dashboard's End route use, unless something has replaced
     * or cleared it in the meantime.
     */
    private fun scheduleArrivalEnd() {
        cancelArrivalEnd()
        val routeGeneration = generation
        val end = Runnable {
            arrivalEnd = null
            if (generation == routeGeneration && follower != null && last?.state == FollowState.ARRIVED) {
                arrivalEnds++
                SessionController.note("route ended after arriving")
                Navigation.clear()
            }
        }
        arrivalEnd = end
        main.postDelayed(end, ARRIVAL_HOLD_MS)
    }

    private fun cancelArrivalEnd() {
        arrivalEnd?.let { main.removeCallbacks(it) }
        arrivalEnd = null
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

    /**
     * Puts the current state on its way to the cluster, if a session is listening.
     * [ended] is set only by [clear]; with no route it says ended instead of idle.
     */
    private fun publish(ended: Boolean = false) {
        val result = last
        val step = result?.step
        turnListener?.invoke(
            when {
                follower == null -> null
                rerouting -> UpcomingTurn(UpcomingTurn.REROUTING, null)
                result?.state == FollowState.ARRIVED -> UpcomingTurn(
                    UpcomingTurn.ARRIVED, null, Maneuvers.DESTINATION,
                    destinationMetres = 0.0, destinationSeconds = 0,
                )
                // A route and no fix on it yet: the trip panel (and its End route) shows,
                // the card has nothing honest to say.
                step == null -> UpcomingTurn(UpcomingTurn.WAITING, null)
                // The cluster's own values: the icon is drawn from the type it is sent.
                else -> UpcomingTurn(
                    step.instruction, step.metres, step.maneuver.type, step.maneuver.exit, step.onto,
                    step.destinationMetres, paced(step.destinationSeconds), step.later,
                )
            },
        )
        // P8.2: at most one prompt for what this publish says, handed to the speaker.
        when {
            follower == null -> null
            rerouting -> voice.onRerouting()
            result?.state == FollowState.ARRIVED -> voice.onArrived()
            step == null -> null
            else -> voice.onStep(step, lastSpeed, SystemClock.elapsedRealtime())
        }?.let(::speak)
        if (handle <= 0L) return
        val code = when {
            follower == null -> NativeBridge.submitGuidance(
                handle,
                if (ended) NativeBridge.GUIDANCE_ENDED else NativeBridge.GUIDANCE_IDLE,
            )
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
                instruction = step.instruction,
                onto = step.onto.orEmpty(),
                road = step.road.orEmpty(),
                stepMetres = step.metres,
                stepSeconds = step.seconds,
                destinationMetres = step.destinationMetres,
                destinationSeconds = paced(step.destinationSeconds),
            )
        }
        if (code == NativeBridge.NOW_PLAYING_CHANGED) published++
    }

    /** Hands [prompt] to [Speaker]: the words go to the engine, the stage to the report. */
    private fun speak(prompt: VoicePrompt) {
        Speaker.guide(GuidePrompt(prompt.text, prompt.stage.label))
    }

    /**
     * The `[guidance]` report lines. Counts, states, raw maneuver values and distances —
     * no coordinates, no instruction text, no road names, no destination.
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
                "guidance_arrival_ends=$arrivalEnds " +
                "guidance_max_on_route_offset_m=${maxOnRouteOffsetM.roundToInt()}",
        )
        // Seconds of driving, not where: how far the estimate was off, and what was done about it.
        appendLine(
            "guidance_learn_pace=$learnPace guidance_pace_estimated_s=${pace.estimatedS.roundToInt()} " +
                "guidance_pace_actual_s=${pace.actualS.roundToInt()} " +
                "guidance_pace_factor=${String.format(java.util.Locale.ROOT, "%.3f", pace.factor())}",
        )
        val follower = follower
        val step = result?.step
        appendLine(
            "guidance_route_maneuvers=${follower?.maneuverTypes()?.joinToString(",")?.ifEmpty { "-" } ?: "-"} " +
                "guidance_next=${step?.let { "maneuver=${it.maneuver.type} exit=${it.maneuver.exit} step_m=${it.metres.roundToInt()} to_step_s=${it.seconds} to_destination_m=${it.destinationMetres.roundToInt()}" } ?: "-"}",
        )
    }
}

/**
 * What guidance says about the route, for the dashboard's turn card and trip panel
 * (P7.4): the next thing the driver does, and how the trip stands.
 *
 * Null when there is no route at all; the trip panel shows exactly while one of these
 * exists, so End route is never hidden while a route does (plan §5.4).
 *
 * `@JvmOverloads` so the host tests can build the plain two-argument form.
 */
class UpcomingTurn @JvmOverloads constructor(
    /** The instruction the cluster gets, or [REROUTING], [ARRIVED] or [WAITING]. */
    val text: String,
    /** Metres to the maneuver, or null when there is none to count down to. */
    val metres: Double?,
    /** Raw `ManeuverType`, exactly as sent to the cluster ([Maneuvers.glyph] draws it). */
    val maneuver: Int = Maneuvers.UNKNOWN,
    /** Roundabout exit, 0 for none. */
    val exit: Int = 0,
    /** The road the maneuver turns onto (P6.7), or null when the map names none. */
    val street: String? = null,
    val destinationMetres: Double? = null,
    /**
     * Time to the destination from here: BRouter's, with the Routing screen's adjustment
     * and, when Learn my pace is on, the drive's pace. The arrival clock is now plus this.
     */
    val destinationSeconds: Int? = null,
    /** The maneuvers after this one ([RouteFollower.LATER_STEPS] at most). */
    val later: List<LaterStep> = emptyList(),
) {
    companion object {
        const val REROUTING = "Rerouting…"
        const val ARRIVED = "You have arrived"

        /** A route with no fix on it yet. Blank: the card draws nothing for it. */
        const val WAITING = ""
    }
}
