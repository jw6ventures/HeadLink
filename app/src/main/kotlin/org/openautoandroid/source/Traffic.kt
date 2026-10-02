package org.openautoandroid.source

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Live traffic in the app (P9): the one [TrafficFeed], with the real clock, threads, client
 * and settings, and the calls [Guide], [Navigation], [LiveAlternatives] and
 * [ProjectionService] make into it.
 *
 * Main-thread state. Installed for the process by [SourceApplication], in the process that
 * runs sessions only; before that, and in the route preview's process, every call is a
 * no-op and every answer is "no traffic". Like [Guide], it runs whether or not a dashboard
 * projects: a route followed from vehicle fixes still fetches, and Home's route line shows
 * the delay. [listener] is how a projecting dashboard hears of it.
 *
 * Two threads of its own, both at the routing thread's nice 4 and both idle between
 * refreshes: `traffic-fetch`, which waits on HERE for up to 45 s a request, and
 * `traffic-match`, which lays a response on a route in tens of milliseconds. Kept apart so
 * a slow fetch never holds up matching a re-route.
 */
object Traffic {
    /** How often the loop checks the settings and the clock while a route is followed. */
    private const val TICK_MS = 5_000L

    /** Nice 4, as [Routing]'s thread: behind the render threads, short of the background group. */
    private const val THREAD_PRIORITY = Process.THREAD_PRIORITY_DEFAULT + 4 * Process.THREAD_PRIORITY_LESS_FAVORABLE

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var feed: TrafficFeed? = null
    private var ticking = false

    /**
     * Told whenever the dashboard's view of traffic changes: installed by [ProjectionService]
     * while a dashboard projects, as `pipeline.traffic`. Main thread.
     */
    var listener: ((TrafficDisplay) -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            val feed = feed
            if (feed == null || !feed.following) {
                ticking = false
                return
            }
            feed.tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Main process, from [SourceApplication]: builds the loop over the app's own client and settings. */
    fun install(context: Context) {
        if (feed != null) return
        val app = context.applicationContext
        val client = TrafficPrefs.client(app)
        val fetchThread = worker("traffic-fetch")
        val matchThread = worker("traffic-match")
        feed = TrafficFeed(object : TrafficHost {
            override fun settings(): TrafficSettings = TrafficPrefs.load(app)

            override fun fetch(box: TrafficBox): TrafficFetch = client.fetch(box)

            override fun requestsThisMonth(): Int =
                TrafficPrefs.status(app).requestsIn(YearMonth.now(ZoneOffset.UTC))

            override fun now(): Long = SystemClock.elapsedRealtime()

            override fun network(task: () -> Unit) = fetchThread.execute { task() }

            override fun compute(task: () -> Unit) = matchThread.execute { task() }

            override fun main(task: () -> Unit) {
                handler.post { task() }
            }

            override fun topSpeedKmh(): Int = AppSettings.load(app).routing.topSpeedKmh

            override fun projecting(): Boolean = Guide.attached

            override fun reroute(): Boolean = Guide.rerouteForClosure(app)

            override fun look() = LiveAlternatives.lookNow(app)

            override fun take(offer: TrafficOffer) = LiveAlternatives.take(app, offer)

            override fun changed() = Guide.trafficChanged()

            override fun display(display: TrafficDisplay) {
                listener?.invoke(display)
            }

            override fun note(text: String) = SessionController.note(text)
        })
    }

    private fun worker(name: String): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(THREAD_PRIORITY)
            runnable.run()
        }, name).apply { isDaemon = true }
    }

    /** [Guide] follows a new route. */
    fun onRoute(route: Route) {
        val feed = feed ?: return
        feed.onRoute(route)
        if (!ticking) {
            ticking = true
            handler.postDelayed(ticker, TICK_MS)
        }
    }

    /** One fix [Guide] placed on [route]. */
    fun onPosition(route: Route, result: FollowResult) {
        feed?.onPosition(route, result)
    }

    /** No route any more. */
    fun onRouteEnded() {
        feed?.onRouteEnded()
    }

    /** A new destination was set: a new trip. */
    fun newTrip() {
        feed?.newTrip()
    }

    /** Seconds of traffic delay in the time shown for [route]. */
    fun delaySeconds(route: Route): Int = feed?.delaySeconds(route) ?: 0

    /** [request] with the traffic's no-go points, when traffic is on and a response is held. */
    fun withNogos(request: RouteRequest): RouteRequest = feed?.withNogos(request) ?: request

    /** The points for an alternatives search made with [options]. */
    fun nogos(options: RouteOptions): List<Nogo> = feed?.nogos(options) ?: emptyList()

    /** Whether the traffic ahead is enough for a way round to be worth a search of its own. */
    fun worthLooking(): Boolean = feed?.worthLooking() ?: false

    /** See [TrafficFeed.assess]; with no loop, every delay is 0 and nothing is offered. */
    fun assess(followed: Route, ahead: Route, aheadAlongM: Double, alternatives: List<Route>, done: (IntArray, Route?) -> Unit) {
        val feed = feed
        if (feed == null) done(IntArray(alternatives.size), null) else feed.assess(followed, ahead, aheadAlongM, alternatives, done)
    }

    /** Take or Keep on the divided turn card. */
    fun answer(id: Long, take: Boolean) {
        feed?.answer(id, take)
    }

    /** What the dashboard should show now, for a projection that is just starting. */
    fun display(): TrafficDisplay = feed?.display ?: TrafficDisplay.EMPTY

    /** The `[traffic]` report section. */
    fun report(): String = feed?.report() ?: "traffic=-\n"
}
