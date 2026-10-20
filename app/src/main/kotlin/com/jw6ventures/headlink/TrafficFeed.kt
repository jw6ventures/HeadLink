package com.jw6ventures.headlink

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the traffic loop ([TrafficFeed]) needs from the app, injected so that the host test
 * runs the loop itself with a fake clock, fake threads and a [TrafficClient] over a fake
 * HTTP layer, and the app runs it with the real ones ([Traffic]).
 */
interface TrafficHost {
    /** The Traffic card's settings as saved now. Main thread; read at every tick. */
    fun settings(): TrafficSettings

    /** One [TrafficClient.fetch]. Called on the [network] thread only: it blocks. */
    fun fetch(box: TrafficBox): TrafficFetch

    /** Requests counted this calendar month, as the card shows them. Any thread. */
    fun requestsThisMonth(): Int

    /** A monotonic clock, milliseconds: `SystemClock.elapsedRealtime` in the app. */
    fun now(): Long

    /** Runs [task] on the thread that talks to HERE. */
    fun network(task: () -> Unit)

    /** Runs [task] on the thread that matches and places points: never the routing thread. */
    fun compute(task: () -> Unit)

    /** Runs [task] on the main thread, later, in order. */
    fun main(task: () -> Unit)

    /** The top speed of the Routing settings in force now, which the points' weights are priced at. */
    fun topSpeedKmh(): Int

    /**
     * Whether a dashboard projects now. A vehicle stopped in a queue sends the same fix over
     * and over, and the session passes on only fixes that change, so while projecting the
     * last fix still says where the vehicle is however old it is. Without a projection only
     * a recent fix keeps a route followed ([TrafficFeed.FIX_FRESH_MS]).
     */
    fun projecting(): Boolean

    /** A closure ahead on the route: re-route now, as leaving the route does. True if one was started. */
    fun reroute(): Boolean

    /** A traffic update worth looking round: run the live alternatives' search now, once. */
    fun look()

    /** **Take** was pressed: follow [offer]'s route, as a live alternative's bubble does. */
    fun take(offer: TrafficOffer)

    /** The delay in the time to the destination changed with no fix to carry it: publish guidance again. */
    fun changed()

    /** The dashboard's view of traffic changed. */
    fun display(display: TrafficDisplay)

    /** A line for the event log. Counts, minutes and booleans only (P6 plan §9.6). */
    fun note(text: String)
}

/**
 * The traffic loop (P9 plan §4, §6, §7): fetches HERE's flow and incidents for the route
 * ahead while a route is followed, lays them on the route, adds the delay to the time to
 * the destination, hands the closures and jams to every route search as no-go points,
 * re-routes at once round a closure ahead, and offers a faster way round a jam.
 *
 * Main-thread state, like [Guide] and [LiveAlternatives], which feed it: every public
 * function is called on the main thread. The fetch runs on the host's [TrafficHost.network]
 * thread, and matching and placing points on its [TrafficHost.compute] thread, never on the
 * routing thread, which is BRouter's. Their answers come back through [TrafficHost.main]
 * and are used only if nothing was dropped or replaced in the meantime.
 *
 * **When it fetches** (plan §4, decision 5). Only while a route is being followed: a route,
 * and a fix that placed the vehicle on it or joining it (not after arriving). The first
 * fetch is at the first such fix, then every [TrafficSettings.refreshMinutes]. After the
 * followed route changes (a re-route, a new destination, a choice) the last fetch's boxes
 * are checked against the new route's next [COVER_SHARE] of the look-ahead
 * ([TrafficArea.covers]); when they still hold it nothing is fetched, and when they do not
 * one fetch is made, no sooner than [REFETCH_GAP_MS] after the last started, so a burst of
 * re-routes is one fetch. With traffic off, or on with no key, nothing is fetched and
 * [TrafficHost.fetch] is never called: that is the client's gate too, and the host test
 * holds both to it.
 *
 * **What is kept** (plan §1, §4). One response set (a [TrafficSnapshot] per box, one box in
 * every case the plan expects), used until the next replaces it whole, never merged with
 * it, and never re-labelled for another box. A failed or refused refresh keeps it, but
 * only until [staleMs] after it arrived (twice the refresh interval): old traffic is worse
 * than none. Everything is dropped the moment the route ends, traffic is turned off or the
 * key is removed. Nothing is written anywhere.
 *
 * **A failed refresh** is tried again after [RETRY_MS] when it may pass (no connection,
 * HERE's 5xx, an answer that could not be read), and at the next refresh when it will not
 * by itself (HERE's 4xx: a bad key or a refused box), so a bad key costs one counted request
 * a refresh, not one a minute. At the monthly limit the client sends nothing, and the loop
 * asks again at the next refresh.
 *
 * **The time** (P9.4, plan §6): [delaySeconds] is the followed route's delay ahead of the
 * vehicle ([RouteTraffic.delayAheadSeconds]), which [Guide] adds to BRouter's time after
 * the pace factor ([ArrivalTime]); the display's delay is the same number.
 *
 * **Routing** (P9.5, plan §7): [withNogos] puts the points on every new route, re-route and
 * alternatives search while traffic is on and a response is held ([TrafficNogos.forRoute],
 * priced at the request's own top speed). A closure matched on the route ahead starts a
 * re-route at once, once for each closure a trip, and never on a route BRouter could only
 * find by opening the closures ([Route.closuresOpened]), which would loop.
 *
 * **The offer** (plan §7, P9.6): see [assess].
 *
 * Nothing about where the vehicle is, the route, HERE's roads or the offer's reason reaches
 * [report] or [TrafficHost.note]: counts, minutes, seconds and booleans only.
 */
class TrafficFeed(private val host: TrafficHost) {
    // The route being followed, and where the vehicle is on it.
    private var route: Route? = null
    private var routeAlong = DoubleArray(0)
    private var alongM = 0.0
    /** The last fix's state on [route], or null before the first. */
    private var state: FollowState? = null
    /** [TrafficHost.now] at the last fix placed on [route]: a route no fix has placed lately is not being followed. */
    private var lastPositionAt = 0L
    /** The route [TrafficArea.covers] was last asked about, so it is asked once per route. */
    private var coveredRoute: Route? = null

    // The response in use.
    private var snapshots: List<TrafficSnapshot> = emptyList()
    private var boxes: List<TrafficBox> = emptyList()
    private var flow: List<FlowStretch> = emptyList()
    private var incidents: List<TrafficIncident> = emptyList()
    private var receivedAt = 0L
    private var traffic: RouteTraffic? = null
    private var nogos: List<Nogo> = emptyList()
    private var nogosOnRoute = 0
    /** The top speed [nogos] were priced at. */
    private var nogoSpeedKmh = RouteOptions.DEFAULT_TOP_SPEED_KMH
    /** Bumped whenever everything is dropped: an answer for an older one is not used. */
    private var generation = 0L

    // Cadence.
    private var fetching = false
    private var lastFetchAt: Long? = null
    private var nextFetchAt: Long? = null

    /** Seconds of traffic delay ahead on the followed route at the last fix; what [Guide] adds. */
    var delaySeconds = 0
        private set

    // The offer.
    private var offer: TrafficOffer? = null
    private var offerSplitM = 0.0
    private var nextOfferId = 1L
    private val kept = ArrayList<Kept>()
    /** Closures re-routed round this trip, by where they are; never reported. */
    private val closures = ArrayList<DoubleArray>()

    /** What the dashboard was last given. */
    var display: TrafficDisplay = TrafficDisplay.EMPTY
        private set

    // Report counts: numbers only.
    private var fetches = 0
    private var boxesFetched = 0
    private var lastBoxes = 0
    private var requestsSession = 0
    private var failures = 0
    private var skipped = 0
    private var staleDrops = 0
    private var capReached = false
    private var lastError = "none"
    private var matchMs = 0L
    private var requestsWithNogos = 0
    private var closureReroutes = 0
    private var assessed = 0
    private var offersMade = 0
    private var offersTaken = 0
    private var offersKept = 0
    private var offersWithdrawn = 0

    /** Whether a route is followed now, so the app keeps ticking. */
    val following: Boolean get() = route != null

    /**
     * [Guide] has a new route to follow: a first route, a re-route, a new destination or a
     * chosen alternative, all alike. The response held is laid on the new route at once,
     * with no fetch unless its boxes no longer cover it ([tick]).
     */
    fun onRoute(route: Route) {
        if (route === this.route) return
        this.route = route
        routeAlong = TrafficArea.metres(route)
        alongM = 0.0
        state = null
        traffic = null
        delaySeconds = 0
        // An offer was about the route now gone; Take has already cleared its own.
        withdraw()
        if (snapshots.isNotEmpty()) rematch(update = false)
        publish()
    }

    /**
     * One fix [Guide] placed on [route]: its [FollowResult]. Keeps the delay current, takes
     * back an offer the vehicle has passed, re-routes round a closure ahead, and fetches when
     * due.
     */
    fun onPosition(route: Route, result: FollowResult) {
        if (route !== this.route) return
        state = result.state
        lastPositionAt = host.now()
        if (result.state != FollowState.OFF_ROUTE) alongM = result.alongM
        var changed = updateDelay()
        val up = offer
        if (up != null && (result.state == FollowState.ARRIVED || alongM > offerSplitM + LiveAlternatives.PASSED_M)) {
            withdraw()
            changed = true
        }
        if (changed) publish()
        checkClosure()
        tick()
    }

    /** No route any more (ended, cleared or arrived and held): drop everything (plan §4). */
    fun onRouteEnded() {
        route = null
        routeAlong = DoubleArray(0)
        state = null
        coveredRoute = null
        stop()
        kept.clear()
        closures.clear()
        lastFetchAt = null
        nextFetchAt = null
        publish()
    }

    /** A new destination: a way round kept or a closure re-routed round belongs to the last trip. */
    fun newTrip() {
        kept.clear()
        closures.clear()
    }

    /**
     * Checks the settings and the clock: drops everything when traffic is off or keyless,
     * drops a response past [staleMs], and fetches when one is due. The app calls it every
     * few seconds while a route is followed, and every fix does.
     */
    fun tick() {
        val settings = host.settings()
        if (!settings.usable) {
            if (holding()) {
                stop()
                publish()
                host.changed()
                // After the drop, so the report this line writes already says nothing is held.
                host.note(if (!settings.enabled) "live traffic off; traffic dropped" else "no HERE key; traffic dropped")
            }
            nextFetchAt = null
            return
        }
        val route = route ?: return
        val now = host.now()
        if (snapshots.isNotEmpty() && now - receivedAt > staleMs(settings)) {
            staleDrops++
            host.note("traffic response too old; dropped")
            forget()
            publish()
            host.changed()
        }
        val at = state ?: return
        // Followed (plan §4, decision 5): on the route or joining it, at a fix recent enough to
        // say the vehicle is still there. Off the route, arrived, or with no fix for
        // [FIX_FRESH_MS] (the projection ended and the phone was put away, say), nothing is
        // fetched: the route is kept, and a fix that places the vehicle on it again resumes.
        if (at != FollowState.ON_ROUTE && at != FollowState.JOINING) return
        if (now - lastPositionAt > FIX_FRESH_MS && !host.projecting()) return
        if (boxes.isNotEmpty() && coveredRoute !== route) {
            coveredRoute = route
            val ahead = settings.lookAheadKm * 1000.0 * COVER_SHARE
            if (!TrafficArea.covers(boxes, route, alongM, ahead)) {
                // Not before the refresh already due, and not in a burst of re-routes.
                val soonest = (lastFetchAt ?: Long.MIN_VALUE / 2) + REFETCH_GAP_MS
                nextFetchAt = minOf(nextFetchAt ?: Long.MAX_VALUE, max(now, soonest))
            }
        }
        if (fetching) return
        val due = nextFetchAt
        if (due != null && now < due) return
        fetch(settings, route, now)
    }

    /** Seconds of delay in the time shown for [route]: [delaySeconds] if it is the followed route, else 0. */
    fun delaySeconds(route: Route): Int = if (route === this.route) delaySeconds else 0

    /**
     * [request] with the points for the traffic held (P9.5, plan §7), priced at its own top
     * speed; [request] itself when traffic is off or nothing is held.
     */
    fun withNogos(request: RouteRequest): RouteRequest {
        val points = nogos(request.options)
        if (points.isEmpty()) return request
        requestsWithNogos++
        return request.withNogos(points)
    }

    /** The points for a search made with [options], or none. */
    fun nogos(options: RouteOptions): List<Nogo> {
        if (nogos.isEmpty() || !host.settings().usable) return emptyList()
        val scale = TrafficCost.secondPrice(options.topSpeedKmh) / TrafficCost.secondPrice(nogoSpeedKmh)
        return if (scale == 1.0) nogos else nogos.map { it.copy(weight = it.weight?.times(scale)) }
    }

    /**
     * Whether the traffic on the followed route could make a way round worth offering: at
     * least [OFFER_SAVING_S] of delay ahead. Below that no alternative can save it through
     * traffic, so no search is spent looking.
     */
    fun worthLooking(): Boolean = delaySeconds >= OFFER_SAVING_S

    /**
     * The live alternatives' search found [alternatives] from [ahead], the followed route
     * from [aheadAlongM] metres along it on (P9.6, plan §7). Each is laid on the traffic
     * held, off the main thread; then, on the main thread, [done] is given each one's
     * traffic delay less [ahead]'s (for its bubble's "+N min", in seconds), and the
     * alternative now on offer if it is one of them, to be left out of the bubbles.
     *
     * **The offer.** An alternative is offered when, against the followed route with its
     * traffic delay, with its own:
     * - it saves at least [OFFER_SAVING_S];
     * - traffic is part of why (the followed route's delay ahead is more than its own), so
     *   a route merely BRouter-faster is never called "faster" for traffic;
     * - it leaves the followed route ahead of the vehicle and has no closure of its own;
     * - it is not one the driver kept away from this trip ([answer]), unless its saving has
     *   grown by [OFFER_REGROW_S] since.
     *
     * The best of those is offered. The same way round (splitting within [SAME_SPLIT_M] and
     * mostly the same route) keeps its offer's id, so the card is not re-fitted; another
     * replaces it. None withdraws the offer up. Nothing re-routes by itself for a jam.
     */
    fun assess(followed: Route, ahead: Route, aheadAlongM: Double, alternatives: List<Route>, done: (IntArray, Route?) -> Unit) {
        val none = IntArray(alternatives.size)
        val held = traffic
        if (followed !== route || held == null || held.route !== followed || snapshots.isEmpty() || alternatives.isEmpty()) {
            if (followed === route && held != null && alternatives.isEmpty() && offer != null) {
                withdraw()
                publish()
            }
            done(none, null)
            return
        }
        assessed++
        val gen = generation
        val snaps = snapshots
        val flow = flow
        val incidents = incidents
        host.compute {
            val matched = try {
                alternatives.map { TrafficMatch.match(it, flow, incidents) }
            } catch (e: RuntimeException) {
                null
            }
            host.main {
                if (matched == null || gen != generation || snaps !== snapshots || followed !== route || traffic !== held) {
                    if (matched == null) lastError = "match"
                    done(none, null)
                    return@main
                }
                done(extras(held, aheadAlongM, matched), decide(followed, held, ahead, aheadAlongM, alternatives, matched))
            }
        }
    }

    /**
     * **Take** or **Keep** on offer [id] (plan §7). Take follows it through the host, as a
     * live alternative's bubble is followed. Keep records the way round, so it is not offered
     * again this trip unless it saves [OFFER_REGROW_S] more. Either way it goes from the
     * display. An answer to an offer no longer up does nothing.
     */
    fun answer(id: Long, take: Boolean) {
        val up = offer ?: return
        if (up.id != id) return
        offer = null
        val minutes = (up.savingSeconds / 60.0).roundToInt()
        if (take) {
            offersTaken++
            host.note("traffic offer taken: $minutes min faster")
            publish()
            host.take(up)
        } else {
            offersKept++
            kept += Kept(up.splitLat, up.splitLon, up.route, up.savingSeconds)
            host.note("traffic offer kept to the route: $minutes min faster")
            publish()
        }
    }

    /** The `[traffic]` report lines: counts, minutes, seconds and booleans. No place, road, reason or key. */
    fun report(): String = buildString {
        val s = host.settings()
        val now = host.now()
        appendLine(
            "traffic_enabled=${s.enabled} traffic_key=${s.apiKey.isNotBlank()} traffic_refresh_min=${s.refreshMinutes} " +
                "traffic_look_ahead_km=${s.lookAheadKm} traffic_limit=${s.monthlyLimit}",
        )
        val followed = route != null && (state == FollowState.ON_ROUTE || state == FollowState.JOINING) &&
            (now - lastPositionAt <= FIX_FRESH_MS || host.projecting())
        appendLine(
            "traffic_following=$followed traffic_fetches=$fetches traffic_boxes=$boxesFetched " +
                "traffic_last_boxes=$lastBoxes traffic_requests_session=$requestsSession " +
                "traffic_requests_month=${host.requestsThisMonth()} traffic_cap_reached=$capReached",
        )
        appendLine(
            "traffic_held=${snapshots.isNotEmpty()} " +
                "traffic_last_fetch_age_s=${lastFetchAt?.let { ((now - it) / 1000L).coerceAtLeast(0) } ?: "-"} " +
                "traffic_response_age_s=${if (snapshots.isEmpty()) "-" else ((now - receivedAt) / 1000L).coerceAtLeast(0)} " +
                "traffic_failures=$failures traffic_skipped=$skipped traffic_stale_drops=$staleDrops " +
                "traffic_last_error=$lastError",
        )
        val t = traffic?.takeIf { it.route === route }
        appendLine(
            "traffic_matched_m=${t?.runs?.sumOf { it.toM - it.fromM }?.roundToInt() ?: 0} " +
                "traffic_runs=${t?.runs?.size ?: 0} " +
                "traffic_jam_runs=${t?.runs?.count { it.congestion == Congestion.SLOW || it.congestion == Congestion.QUEUING } ?: 0} " +
                "traffic_closed_runs=${t?.runs?.count { it.congestion == Congestion.CLOSED } ?: 0} " +
                "traffic_delay_s=$delaySeconds traffic_delay_min=${(delaySeconds / 60.0).roundToInt()} traffic_match_ms=$matchMs",
        )
        appendLine(
            "traffic_nogos=${nogos.size} traffic_nogos_hard=${nogos.count { it.hard }} traffic_nogos_on_route=$nogosOnRoute " +
                "traffic_requests_with_nogos=$requestsWithNogos traffic_route_shaped=${route?.trafficShaped == true} " +
                "traffic_route_closures_opened=${route?.closuresOpened == true} traffic_closure_reroutes=$closureReroutes",
        )
        appendLine(
            "traffic_assessed=$assessed traffic_offers_made=$offersMade traffic_offers_taken=$offersTaken " +
                "traffic_offers_kept=$offersKept traffic_offers_withdrawn=$offersWithdrawn traffic_offer_up=${offer != null} " +
                "traffic_offer_saving_min=${offer?.let { (it.savingSeconds / 60.0).roundToInt() } ?: "-"} " +
                "traffic_attributed=${display.attributed}",
        )
    }

    // ---- fetching ---------------------------------------------------------------------

    private fun fetch(settings: TrafficSettings, route: Route, now: Long) {
        val asked = TrafficArea.boxes(route, alongM, settings.lookAheadKm.toDouble())
        if (asked.isEmpty()) return
        fetching = true
        lastFetchAt = now
        nextFetchAt = null
        coveredRoute = route
        val gen = generation
        host.network {
            val before = host.requestsThisMonth()
            val got = ArrayList<TrafficSnapshot>(asked.size)
            var stopped: TrafficFetch? = null
            for (box in asked) {
                val result = try {
                    host.fetch(box)
                } catch (e: RuntimeException) {
                    TrafficFetch.Failed("traffic fetch threw ${e.javaClass.simpleName}", null)
                }
                if (result is TrafficFetch.Fetched) {
                    got += result.snapshot
                } else {
                    stopped = result
                    break
                }
            }
            val sent = (host.requestsThisMonth() - before).coerceAtLeast(0)
            host.main { fetched(gen, asked, got, stopped, sent) }
        }
    }

    private fun fetched(gen: Long, asked: List<TrafficBox>, got: List<TrafficSnapshot>, stopped: TrafficFetch?, sent: Int) {
        requestsSession += sent
        // An answer for what was dropped: [stop] already cleared [fetching] for it, and a fetch
        // of the new generation may be in flight now, which this must not mark finished.
        if (gen != generation) return
        fetching = false
        val settings = host.settings()
        val now = host.now()
        when (stopped) {
            null -> {
                // The new response replaces the old whole: nothing of the last is kept.
                snapshots = got
                boxes = asked
                flow = got.flatMap { it.flow }
                incidents = got.flatMap { it.incidents }
                receivedAt = now
                fetches++
                boxesFetched += asked.size
                lastBoxes = asked.size
                capReached = false
                lastError = "none"
                nextFetchAt = now + refreshMs(settings)
                host.note("traffic fetched: ${asked.size} box${if (asked.size == 1) "" else "es"}, ${flow.size} stretches, ${incidents.size} incidents")
                // Nothing made from the last response is used past this point; what the new
                // one makes lands when it has been matched, a few milliseconds on.
                traffic = null
                nogos = emptyList()
                nogosOnRoute = 0
                rematch(update = true)
            }
            is TrafficFetch.Skipped -> {
                skipped++
                lastError = stopped.why.name.lowercase()
                if (stopped.why == TrafficSkip.LIMIT) {
                    capReached = true
                    nextFetchAt = now + refreshMs(settings)
                    host.note("traffic: monthly request limit reached")
                } else {
                    stop()
                    publish()
                    host.changed()
                }
            }
            is TrafficFetch.Failed -> {
                failures++
                lastError = errorClass(stopped)
                val lasting = stopped.httpStatus?.let { it in 400..499 } == true
                nextFetchAt = now + if (lasting) refreshMs(settings) else minOf(RETRY_MS, refreshMs(settings))
                host.note("traffic fetch failed: $lastError")
            }
            else -> {}
        }
    }

    // ---- matching ---------------------------------------------------------------------

    /**
     * Lays the response held on the followed route and places the points, on the compute
     * thread. [update] is a new response rather than a new route: only that is worth a look
     * for a way round.
     */
    private fun rematch(update: Boolean) {
        val r = route ?: return
        val snaps = snapshots
        if (snaps.isEmpty()) return
        val gen = generation
        val flow = flow
        val incidents = incidents
        val speed = host.topSpeedKmh()
        host.compute {
            val began = System.nanoTime()
            val result = try {
                val matched = TrafficMatch.match(r, flow, incidents)
                matched to TrafficNogos.forRoute(flow, incidents, matched, { fc -> TrafficCost.costPerSecond(fc, speed) })
            } catch (e: RuntimeException) {
                null
            }
            val took = (System.nanoTime() - began) / 1_000_000L
            host.main { matched(gen, snaps, r, result, speed, took, update) }
        }
    }

    private fun matched(
        gen: Long,
        snaps: List<TrafficSnapshot>,
        r: Route,
        result: Pair<RouteTraffic, TrafficNogos.RouteNogos>?,
        speed: Int,
        took: Long,
        update: Boolean,
    ) {
        if (gen != generation || snaps !== snapshots || r !== route) return
        if (result == null) {
            lastError = "match"
            return
        }
        traffic = result.first
        // Every point checked as RouteRequest checks it, so a bad one is left out here rather
        // than failing a route request on the main thread.
        nogos = result.second.nogos.filter { RouteRequest.valid(it) }
        nogosOnRoute = result.second.onRoute
        nogoSpeedKmh = speed
        matchMs = took
        updateDelay()
        // Counts and minutes only. It is also what brings the saved report up to date with
        // the delay the time now carries: the report is written on each event.
        host.note(
            "traffic laid on the route: ${traffic?.runs?.size ?: 0} runs, ${(delaySeconds / 60.0).roundToInt()} min of delay ahead, " +
                "${nogos.size} points",
        )
        if (!worthLooking() && offer != null) withdraw()
        publish()
        host.changed()
        checkClosure()
        // A new response with enough delay ahead to save the offer's worth: one search.
        if (update && worthLooking()) host.main { host.look() }
    }

    /** Recomputes [delaySeconds]; true if it changed. */
    private fun updateDelay(): Boolean {
        val r = route
        val t = traffic
        val delay = if (r != null && t != null && t.route === r && state != FollowState.ARRIVED && state != null) {
            t.delayAheadSeconds(alongM).roundToInt()
        } else {
            0
        }
        if (delay == delaySeconds) return false
        delaySeconds = delay
        return true
    }

    /**
     * A closure matched on the followed route ahead of the vehicle: re-route now (plan §7),
     * unless this route was found only by opening closures, or this closure was already
     * re-routed round this trip.
     */
    private fun checkClosure() {
        val r = route ?: return
        val t = traffic?.takeIf { it.route === r } ?: return
        if (r.closuresOpened) return
        if (state != FollowState.ON_ROUTE && state != FollowState.JOINING) return
        val run = t.closureAhead(alongM) ?: return
        val point = pointAt(r, routeAlong, (run.fromM + run.toM) / 2.0)
        if (closures.any { RouteFollower.distance(it[0], it[1], point[0], point[1]) < SAME_CLOSURE_M }) return
        host.main {
            // Still the same route and closure: a fix in between may have replaced either.
            if (route !== r || traffic !== t) return@main
            if (closures.any { RouteFollower.distance(it[0], it[1], point[0], point[1]) < SAME_CLOSURE_M }) return@main
            if (host.reroute()) {
                closures += point
                closureReroutes++
                host.note("closure ahead on the route; re-routing round it")
            }
        }
    }

    // ---- the offer ---------------------------------------------------------------------

    /** Each alternative's traffic delay less [ahead]'s, seconds. */
    private fun extras(held: RouteTraffic, aheadAlongM: Double, matched: List<RouteTraffic>): IntArray {
        val aheadDelay = held.delayAheadSeconds(aheadAlongM)
        return IntArray(matched.size) { (matched[it].delayAheadSeconds(0.0) - aheadDelay).roundToInt() }
    }

    private class Candidate(val route: Route, val saving: Int, val splitLat: Double, val splitLon: Double, val splitM: Double)

    /** A way round the driver kept away from: where it split, the route, and what it saved then. */
    private class Kept(val splitLat: Double, val splitLon: Double, val route: Route, val saving: Int)

    private fun decide(
        followed: Route,
        held: RouteTraffic,
        ahead: Route,
        aheadAlongM: Double,
        alternatives: List<Route>,
        matched: List<RouteTraffic>,
    ): Route? {
        val aheadDelay = held.delayAheadSeconds(aheadAlongM)
        val candidates = alternatives.indices.mapNotNull { i ->
            val alternative = alternatives[i]
            val own = matched[i]
            if (own.closureAhead(0.0) != null) return@mapNotNull null
            val split = LiveAlternative.splitIndex(ahead, alternative) ?: return@mapNotNull null
            val splitLat = alternative.latitudes[split]
            val splitLon = alternative.longitudes[split]
            val splitM = nearestAlong(followed, routeAlong, splitLat, splitLon)
            if (splitM <= alongM + LiveAlternatives.PASSED_M) return@mapNotNull null
            val ownDelay = own.delayAheadSeconds(0.0)
            val saving = (ahead.seconds + aheadDelay - alternative.seconds - ownDelay).roundToInt()
            // Plan §7: the pop-up is only for a way round faster *because of traffic*. The part
            // of the saving traffic accounts for (the delay it misses, but never more than it
            // saves) must itself be worth an offer, so a route merely faster in BRouter's model,
            // with a little traffic besides, is left to the bubbles.
            if (minOf(saving.toDouble(), aheadDelay - ownDelay) < OFFER_SAVING_S) return@mapNotNull null
            if (kept.any { same(it.splitLat, it.splitLon, it.route, splitLat, splitLon, alternative) && saving < it.saving + OFFER_REGROW_S }) {
                return@mapNotNull null
            }
            Candidate(alternative, saving, splitLat, splitLon, splitM)
        }
        val best = candidates.maxByOrNull { it.saving }
        if (best == null) {
            if (offer != null) {
                withdraw()
                publish()
            }
            return null
        }
        val up = offer
        val sameWay = up != null && same(up.splitLat, up.splitLon, up.route, best.splitLat, best.splitLon, best.route)
        if (up != null && !sameWay) offersWithdrawn++
        val id = if (sameWay) up!!.id else nextOfferId++
        if (!sameWay) {
            offersMade++
            host.note("traffic offer: ${(best.saving / 60.0).roundToInt()} min faster")
        }
        offer = TrafficOffer(id, best.route, best.saving, reason(followed, held, best.splitM), best.splitLat, best.splitLon)
        offerSplitM = best.splitM
        publish()
        return best.route
    }

    /** Whether two ways round are the same one: splitting within [SAME_SPLIT_M], and mostly one route. */
    private fun same(latA: Double, lonA: Double, routeA: Route, latB: Double, lonB: Double, routeB: Route): Boolean =
        RouteFollower.distance(latA, lonA, latB, lonB) <= SAME_SPLIT_M &&
            (RoutePreview.duplicate(routeA, listOf(routeB)) || RoutePreview.duplicate(routeB, listOf(routeA)))

    /**
     * Why, in words (plan §7): what is ahead on the followed route past where the way round
     * leaves it. A closure first, else the heaviest jam. The road is named from the map
     * ([Route.roadAt], [StreetNames]), not from HERE's `description`, which is as often an
     * exit or a cross street as the road (docs/p9-2-results.md §3).
     */
    private fun reason(followed: Route, held: RouteTraffic, splitM: Double): String {
        val from = max(splitM, alongM)
        val ahead = held.runs.filter { it.toM > from }
        fun road(run: TrafficRun): String? = followed.roadAt((run.fromIndex + run.toIndex) / 2)?.takeIf { it.isNotBlank() }
        ahead.firstOrNull { it.closes }?.let { closed ->
            return road(closed)?.let { "Closed: $it" } ?: GENERIC_REASON
        }
        val worst = ahead.filter { it.congestion == Congestion.SLOW || it.congestion == Congestion.QUEUING }
            .maxByOrNull { it.delaySeconds } ?: return GENERIC_REASON
        val name = road(worst) ?: return GENERIC_REASON
        return if (worst.congestion == Congestion.QUEUING) "Queue on $name" else "Traffic on $name"
    }

    // ---- bookkeeping ------------------------------------------------------------------

    /** Takes the offer up down, counted as withdrawn. The caller publishes. */
    private fun withdraw() {
        if (offer == null) return
        offer = null
        offersWithdrawn++
    }

    /** Whether anything from HERE is held or shown. */
    private fun holding(): Boolean = snapshots.isNotEmpty() || traffic != null || nogos.isNotEmpty() || offer != null || fetching

    /** Drops the response and everything made from it; a fetch in flight still arrives. */
    private fun forget() {
        snapshots = emptyList()
        boxes = emptyList()
        flow = emptyList()
        incidents = emptyList()
        traffic = null
        nogos = emptyList()
        nogosOnRoute = 0
        delaySeconds = 0
        withdraw()
    }

    /** Drops everything, and makes any answer still in flight worthless. */
    private fun stop() {
        generation++
        fetching = false
        forget()
    }

    private fun publish() {
        val r = route
        val next = TrafficDisplay(traffic?.takeIf { r != null && it.route === r }, delaySeconds, offer, r?.trafficShaped == true)
        val last = display
        if (next.traffic === last.traffic && next.delaySeconds == last.delaySeconds && next.offer === last.offer &&
            next.routeShaped == last.routeShaped
        ) {
            return
        }
        display = next
        host.display(next)
    }

    companion object {
        /** A failed fetch that may pass by itself is tried again after this. */
        const val RETRY_MS = 2 * 60_000L

        /**
         * After the followed route changes, the last fetch's boxes must hold this share of
         * the look-ahead on the new route, or it is time to fetch.
         */
        const val COVER_SHARE = 0.5

        /** No fetch for a new route sooner than this after the last began: a burst of re-routes is one fetch. */
        const val REFETCH_GAP_MS = 60_000L

        /** Plan §7: an alternative is offered when it saves at least this, seconds. */
        const val OFFER_SAVING_S = 180

        /** Plan §7: a way round kept away from is offered again only when it saves this much more. */
        const val OFFER_REGROW_S = 180

        /** Two ways round leaving the route within this, metres, may be the same one. */
        const val SAME_SPLIT_M = 200.0

        /** Two closures within this, metres, are one: the matcher's run ends move with the route. */
        const val SAME_CLOSURE_M = 150.0

        /** The reason when the map names no road there. */
        const val GENERIC_REASON = "Traffic ahead"

        /** A fix older than this does not keep a route followed: nothing is fetched for it (plan §4). */
        const val FIX_FRESH_MS = 2 * 60_000L

        /** A response is dropped this long after it arrived, whatever happened since: twice the refresh interval. */
        fun staleMs(settings: TrafficSettings): Long = 2 * refreshMs(settings)

        fun refreshMs(settings: TrafficSettings): Long = settings.refreshMinutes * 60_000L

        /**
         * A failed fetch as a class for the report, never HERE's text: `http_401`, `network`,
         * `parse`, `count` or `other`.
         */
        fun errorClass(failed: TrafficFetch.Failed): String {
            failed.httpStatus?.let { if (failed.message.startsWith("HERE ")) return "http_$it" }
            return when {
                failed.message.startsWith("Could not reach HERE") -> "network"
                failed.message.startsWith("HERE's answer could not be read") -> "parse"
                failed.message.startsWith("Could not count") -> "count"
                else -> "other"
            }
        }

        /** The place [metres] along [route], [along] being its metres ([TrafficArea.metres]). */
        internal fun pointAt(route: Route, along: DoubleArray, metres: Double): DoubleArray {
            if (route.points == 1) return doubleArrayOf(route.latitudes[0], route.longitudes[0])
            val i = TrafficMatch.segmentAt(along, metres)
            val length = along[i + 1] - along[i]
            val t = if (length > 0.0) ((metres - along[i]) / length).coerceIn(0.0, 1.0) else 0.0
            return doubleArrayOf(
                route.latitudes[i] + t * (route.latitudes[i + 1] - route.latitudes[i]),
                route.longitudes[i] + t * (route.longitudes[i + 1] - route.longitudes[i]),
            )
        }

        /** Metres along [route] to its point nearest ([lat], [lon]), as [RouteFollower.alongNearest] finds it. */
        internal fun nearestAlong(route: Route, along: DoubleArray, lat: Double, lon: Double): Double {
            var best = 0
            var nearest = Double.MAX_VALUE
            for (i in 0 until route.points) {
                val d = RouteFollower.distance(route.latitudes[i], route.longitudes[i], lat, lon)
                if (d < nearest) {
                    nearest = d
                    best = i
                }
            }
            return if (along.isEmpty()) 0.0 else along[best]
        }
    }
}

/**
 * The time to the destination as shown everywhere (the cluster, the trip panel, Home): BRouter's
 * time ahead, with the drive's pace when Learn my pace is on, and then the traffic delay
 * ahead (P9.4, plan §6).
 *
 * The pace is learned from BRouter's own estimate only ([PaceTracker.onFix] is given
 * BRouter's seconds, never these), and the delay is added after it, not scaled by it: a jam
 * ahead is in the delay, and once the vehicle is in it the pace sees it and the jam falls
 * behind, so the two never count the same stretch at the same time.
 */
object ArrivalTime {
    fun shown(brouterSeconds: Int, pace: PaceTracker, learnPace: Boolean, delaySeconds: Int): Int =
        (if (learnPace) pace.apply(brouterSeconds) else brouterSeconds) + delaySeconds.coerceAtLeast(0)
}
