package com.jw6ventures.headlink

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * P11.5a's search ahead at run time (docs/navigation.md), one per dashboard projection:
 * a press that lights Gas, Food or Shop while a route is followed starts it; the rules are
 * [AheadSearch]'s.
 *
 * **Threads.** Main thread here. The corridor's OBF queries run on the markers' own thread
 * ([executor], `aap-poi`), never the routing thread, so reading files cannot hold up a
 * re-route. The detours then go to the routing thread through [Routing.explore], which runs
 * only when no route request is waiting and stops at once for one that arrives: guidance
 * always comes first, and a re-route waits at most for one short search to be terminated.
 * The answer comes back here, and is dropped if the route on show has changed
 * ([Routing.token]), the button was turned off, or a newer press replaced it.
 *
 * **What it does with the answer.** Says the best stop through [Speaker.announce] (the trip
 * panel's voice toggle, a call and the speech gate apply as to a turn prompt, and it never
 * cuts one off), puts up to three stops on the flex tile with **Add stop** (or **Replace
 * stop** while the route already has a via point), and draws the corridor's places as
 * extra markers with the best one larger.
 *
 * Nothing it notes or reports names a place: counts, minutes and milliseconds only.
 */
class AheadSearcher(
    context: Context,
    private val executor: Executor,
    private val unit: SpeedUnit,
    private val feed: () -> PoiFeed?,
    private val dashboard: () -> Dashboard?,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    val counts = AheadCounts()

    /** The newest search; any other's answer is stale. Read on the routing thread too. */
    private val generation = AtomicLong()

    /** The kind being searched now, or null. */
    private var searching: PoiKind? = null

    /** The kind whose places are drawn as extra markers, or null. */
    private var shownKind: PoiKind? = null

    /** The destination they were found on the way to. */
    private var shownFor: Destination? = null

    /** The short route running on the routing thread, so turning the button off can stop it. */
    @Volatile
    private var engine: btools.router.RoutingEngine? = null

    /**
     * A POI button was pressed and left [on]. On, while guidance follows a route with a
     * destination, searches ahead for [kind]; with no route it does nothing more than the
     * button already did. Off stops a search for that kind and takes its markers
     * and card away.
     */
    fun pressed(kind: PoiKind, on: Boolean) {
        if (!on) {
            if (searching == kind) cancel()
            if (shownKind == kind) takeDown()
            return
        }
        val (follower, alongM) = Guide.aheadPosition() ?: return
        if (Navigation.destination == null || Navigation.arrived) return
        counts.presses++
        if (searching != null) cancel()
        start(kind, follower.route, alongM)
    }

    /**
     * Guidance published [turn]; main thread. The extra markers and their card belong to
     * one trip, so they go when it ends, arrives or heads somewhere else. A re-route, or a
     * stop added from the card, is the same trip and keeps them.
     */
    fun guidance(turn: UpcomingTurn?) {
        if (shownKind == null) return
        if (turn == null || turn.text == UpcomingTurn.ARRIVED || Navigation.destination !== shownFor) takeDown()
    }

    private fun takeDown() {
        feed()?.extra(emptyList())
        shownKind = null
        shownFor = null
        dashboard()?.let { board -> if (board.poiCard()?.tag == PoiCardContent.TAG_AHEAD) board.closePoiCard() }
    }

    /** Projection is ending: nothing in flight may speak or draw after it. */
    fun stop() {
        if (searching != null) cancel()
    }

    private fun cancel() {
        generation.incrementAndGet()
        engine?.terminate()
        searching = null
        counts.cancelled++
    }

    private fun start(kind: PoiKind, route: Route, alongM: Double) {
        val gen = generation.incrementAndGet()
        val token = Routing.token
        val values = PoiSettings.load(app)
        val options = AppSettings.load(app).routing
        val nogos = Traffic.nogos(options)
        val corridor = AheadCorridor(route, alongM, values.aheadMiles * AheadSearch.METRES_PER_MILE)
        val widthM = values.corridorMiles * AheadSearch.METRES_PER_MILE
        val pressedAt = SystemClock.elapsedRealtime()
        searching = kind
        counts.searches++
        SessionController.note("search ahead for ${kind.label} started")
        try {
            executor.execute {
                val begun = System.nanoTime()
                val found = try {
                    AheadSearch.hits(ObfLibrary.sources(app), corridor, kind, widthM)
                } catch (e: Exception) {
                    null
                }
                val ms = (System.nanoTime() - begun) / 1_000_000L
                main.post { corridorDone(gen, token, kind, corridor, found, ms, values.aheadCandidates, options, nogos, pressedAt) }
            }
        } catch (e: RejectedExecutionException) {
            searching = null
        }
    }

    private fun corridorDone(
        gen: Long,
        token: Long,
        kind: PoiKind,
        corridor: AheadCorridor,
        found: AheadHits?,
        ms: Long,
        candidates: Int,
        options: RouteOptions,
        nogos: List<Nogo>,
        pressedAt: Long,
    ) {
        if (gen != generation.get()) return
        if (token != Routing.token) {
            stale()
            return
        }
        counts.corridor(ms)
        if (found == null) {
            // The library itself failed (a folder gone): nothing honest to say.
            counts.queryFailures++
            searching = null
            SessionController.note("search ahead for ${kind.label}: the maps could not be read")
            return
        }
        counts.queries += found.queries
        counts.queryFailures += found.failures
        counts.candidates += found.hits.size
        if (found.hits.isEmpty()) {
            finish(gen, token, kind, corridor, found.hits, AheadDetours(emptyList(), 0, 0, false), pressedAt)
            return
        }
        val nearest = found.hits.take(candidates)
        // Cancelled until the detours say otherwise: work the planner skips for a route
        // request, or that throws, still ends here as stale rather than never.
        var outcome = AheadDetours(emptyList(), 0, 0, true)
        Routing.explore(
            app,
            done = {
                engine = null
                val result = outcome
                main.post { finish(gen, token, kind, corridor, found.hits, result, pressedAt) }
            },
        ) { router, started, cancelled ->
            val stop = { cancelled() || gen != generation.get() }
            outcome = AheadSearch.detours(
                { request ->
                    router.compute(request, LEG_MAX_MS) { running ->
                        engine = running
                        started(running)
                        if (gen != generation.get()) running.terminate()
                    }
                },
                corridor, nearest, options, nogos, stop,
            )
        }
    }

    private fun stale() {
        searching = null
        counts.cancelled++
        SessionController.note("search ahead dropped: the route changed")
    }

    private fun finish(
        gen: Long,
        token: Long,
        kind: PoiKind,
        corridor: AheadCorridor,
        hits: List<AheadHit>,
        detours: AheadDetours,
        pressedAt: Long,
    ) {
        if (gen != generation.get()) return
        if (token != Routing.token || detours.cancelled) {
            stale()
            return
        }
        searching = null
        counts.routed += detours.routed
        counts.failed += detours.failed
        val best = AheadSearch.best(detours.stops)
        if (best == null) counts.none++ else counts.found++
        // Every place in the corridor on the map, the best larger; nearest first, capped as
        // the pane's own markers are.
        val limit = runCatching { PoiSettings.markersPerKind(app) }.getOrDefault(PoiValues.DEFAULT_MARKERS)
        val extra = (listOfNotNull(best?.hit?.poi) + hits.map { it.poi }).distinctBy { it.id }.take(limit)
        feed()?.extra(extra, best?.hit?.poi?.id)
        shownKind = kind
        shownFor = Navigation.destination
        val stops = AheadSearch.cardStops(detours.stops)
        // Nothing ahead, or nothing routed: said, and shown too, as a notice with Close alone.
        val failedAll = hits.isNotEmpty() && best == null
        if (stops.isEmpty()) {
            dashboard()?.showPoiCard(
                PoiCardContent(
                    AheadSearch.cardLabel(kind), emptyList(), "", PoiCardContent.TAG_AHEAD,
                    AheadSearch.noneLine(unit, corridor.lengthM, corridor.reachesDestination, failedAll),
                ),
            )
        } else {
            val replacing = Navigation.via != null
            dashboard()?.showPoiCard(
                PoiCardContent(
                    AheadSearch.cardLabel(kind),
                    stops.map { PoiEntry(it.hit.poi, AheadSearch.detail(it, unit), openNow = false) },
                    if (replacing) PoiCardContent.REPLACE_STOP else PoiCardContent.ADD_STOP,
                    PoiCardContent.TAG_AHEAD,
                ),
            )
        }
        Speaker.announce(
            GuidePrompt(AheadSearch.spoken(kind, best, unit, corridor.lengthM, corridor.reachesDestination, failedAll), STAGE),
        )
        val took = SystemClock.elapsedRealtime() - pressedAt
        counts.announced(took)
        // Counts and minutes only: the event log is part of the saved report.
        SessionController.note(
            "search ahead for ${kind.label}: ${hits.size} in the corridor, ${detours.routed} detours routed, " +
                "${detours.failed} failed, " +
                (best?.let { "best ${AheadSearch.minutes(it.detourSeconds)} min detour" } ?: "none") +
                "; announced $took ms after the press",
        )
    }

    /**
     * **Add stop** on the card: the place becomes the route's via point and the route to the
     * same destination is asked for again from the vehicle. A via point already there (a
     * chosen alternative's, or an earlier stop) is replaced, which the button said.
     */
    fun addStop(entry: PoiEntry) {
        val poi = entry.poi
        val replaced = Navigation.addStop(app, poi.latitude, poi.longitude) ?: return
        counts.addStops++
        if (replaced) counts.viaReplaced++
    }

    fun report(): String = counts.report()

    companion object {
        /** The stage a search-ahead announcement is counted under in the speech report. */
        const val STAGE = "poi_ahead"

        /** A short route that takes longer than this is not worth waiting for. */
        const val LEG_MAX_MS = 30_000L
    }
}
