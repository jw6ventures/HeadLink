package com.jw6ventures.headlink

/**
 * The numbers Home's route line shows (P7 plan §6.5): distance and time to go, and the
 * wall-clock arrival, or null for no arrival. [trafficSeconds] is the live traffic delay
 * already inside [seconds] (P9.4), for its "+N min traffic"; 0 without it.
 */
class RouteFigures @JvmOverloads constructor(
    val metres: Double,
    val seconds: Int,
    val arrivalMillis: Long?,
    val trafficSeconds: Int = 0,
) {
    companion object {
        /**
         * Pure, so the host test holds the choice to its rules.
         *
         * [live] is [Guide.remaining]: metres and seconds still to go from the last fix on
         * the route. While it has an answer it wins, and the arrival is now plus its
         * seconds, so the line counts down through a drive and the arrival moves with
         * traffic the way the cluster's does.
         *
         * Without it — no fix on the route yet, a re-route in flight, arrived, or no
         * session at all — the route's own totals are shown ([totalMetres],
         * [totalSeconds]), and the arrival is the moment the route was computed
         * ([computedAtMillis], negative when not seen) plus BRouter's estimate. An arrival
         * already in the past is dropped: it says the estimate is spent, not when the
         * vehicle will get there. No route and no live figures is null.
         *
         * [trafficSeconds] is the traffic delay inside [live]'s seconds ([Guide.trafficDelay]);
         * it is carried only with the live figures, the only ones that have it.
         */
        @JvmOverloads
        fun choose(
            live: Pair<Double, Int>?,
            totalMetres: Int?,
            totalSeconds: Int?,
            computedAtMillis: Long,
            nowMillis: Long,
            trafficSeconds: Int = 0,
        ): RouteFigures? {
            if (live != null) {
                val seconds = live.second.coerceAtLeast(0)
                return RouteFigures(
                    live.first.coerceAtLeast(0.0), seconds, nowMillis + seconds * 1000L,
                    trafficSeconds.coerceIn(0, seconds),
                )
            }
            if (totalMetres == null || totalSeconds == null) return null
            val arrival = if (computedAtMillis < 0) null else (computedAtMillis + totalSeconds * 1000L).takeIf { it >= nowMillis }
            return RouteFigures(totalMetres.toDouble(), totalSeconds, arrival)
        }
    }
}

/**
 * When the current route came back, and so what Home's route line says (§6.5).
 *
 * [RouteResult] carries how long the search took but not when it finished. So this
 * watches [Routing.last] change, from the same session listener every route result
 * already notifies (Routing notes "route computed" through [SessionController]), and
 * remembers the wall clock at that moment: the fallback arrival when [Guide] has no live
 * figures ([RouteFigures.choose]).
 *
 * Main-thread state. Installed by the two activities, so it is watching whenever a route
 * can be asked for from the phone; a result that already existed when it was installed has
 * no known time, and Home then leaves the fallback arrival out rather than guessing it.
 *
 * Nothing here reaches the report: it holds a time and a reference, never a place.
 */
object RouteClock {
    private var installed = false
    private var known: RouteResult? = null
    private var knownAtMillis = -1L

    fun install() {
        if (installed) return
        installed = true
        known = Routing.last
        SessionController.addListener {
            val last = Routing.last
            if (last !== known) {
                known = last
                knownAtMillis = System.currentTimeMillis()
            }
        }
    }

    /**
     * Home's figures for the route [Routing] holds now: [Guide]'s live ones, with live
     * traffic's delay in them (P9.4), else the totals.
     */
    fun figures(nowMillis: Long): RouteFigures? {
        val result = Routing.last
        val route = result?.route
        val computedAt = if (result != null && result === known) knownAtMillis else -1L
        return RouteFigures.choose(
            if (route != null) Guide.remaining() else null,
            route?.distanceM,
            route?.seconds,
            computedAt,
            nowMillis,
            if (route != null) Guide.trafficDelay() else 0,
        )
    }
}
