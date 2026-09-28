package org.openautoandroid.source

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Where a route follower thinks the vehicle is. */
enum class FollowState(val label: String) {
    /** Not yet on the route: the vehicle starts wherever it is, the route at a road. */
    JOINING("joining"),
    ON_ROUTE("on-route"),
    /** Far enough from the route for long enough that it is not noise. */
    OFF_ROUTE("off-route"),
    /** At the destination. Nothing more to guide. */
    ARRIVED("arrived"),
}

/**
 * The next maneuver, and how far and how long to it and to the destination — all from one
 * position along the route, so they cannot disagree (P6.2's rule, now over a real route).
 */
class NextStep(
    /** Unique across routes: a change of this is a change of maneuver on the cluster. */
    val id: Long,
    val maneuver: Maneuver,
    /** What the driver reads: the maneuver, with the road it turns onto when the map names it. */
    val instruction: String,
    /** The road the maneuver turns onto, or null when the map names none. */
    val onto: String?,
    /** The road being driven now, or null when unnamed or not yet on the route. */
    val road: String?,
    val metres: Double,
    val seconds: Int,
    val destinationMetres: Double,
    val destinationSeconds: Int,
    /**
     * The next [RouteFollower.LATER_STEPS] maneuvers after this one, in order, for the
     * dashboard's trip panel (P7.4). Measured from the same position as [metres], so the
     * panel and the card cannot disagree about where the vehicle is. Never sent on the
     * wire: the cluster is told one maneuver at a time.
     */
    val later: List<LaterStep> = emptyList(),
)

/** A maneuver past the next one, and the metres along the route from the vehicle to it. */
class LaterStep(val maneuver: Maneuver, val metres: Double)

/** What one fix said. [step] is null only once arrived. */
class FollowResult(
    val state: FollowState,
    /** Metres from the fix to the nearest point of the route. */
    val offsetM: Double,
    /** Metres along the route the vehicle has got to. */
    val alongM: Double,
    val step: NextStep?,
)

/**
 * Follows one computed [Route] from a stream of fixes (P6.5): snaps each fix to the route,
 * measures progress along it, names the next maneuver with its distance and time, and
 * decides when the vehicle has left the route or arrived.
 *
 * Pure JVM and single-threaded — the app drives it from the main thread, where vehicle
 * fixes arrive, and the host tests drive it directly. It never re-routes by itself: leaving
 * the route is a [FollowState] the caller acts on, because a re-route is a request to the
 * routing thread and belongs with the rest of routing ([Guide]).
 *
 * **Why these thresholds.** A fix is on the route within [ON_ROUTE_M]: consumer GPS is good
 * to 5–15 m in the open and worse between buildings, and BRouter's geometry is the OSM
 * centreline, which on a four-lane road is 10 m from the kerb lane. Leaving the route needs
 * [OFF_ROUTE_MS] **and** [OFF_ROUTE_FIXES] beyond it, so neither one bad fix nor a burst
 * of fast fixes from one bad second re-routes the drive; at 13 m/s that is about 50 m of
 * driving past a missed turn, which is roughly when a driver has noticed too. None of this
 * is measured on the vehicle; the report carries the largest offset seen on the route so a
 * drive can say whether [ON_ROUTE_M] was generous or tight.
 */
class RouteFollower(val route: Route, private val generation: Long) {
    private val count = route.points
    /** Metres from the start to each point, on the same flat projection the snap uses. */
    private val along = DoubleArray(count)
    val totalM: Double

    /** Point index and maneuver of each step, in route order, with non-maneuvers dropped. */
    private val steps: List<Pair<Int, Maneuver>>

    var state = FollowState.JOINING
        private set

    private var progress = 0.0
    /**
     * Index into [steps] of the next maneuver. Only moves forward while the vehicle stays
     * on the route: a fix that jitters back across a turn just taken must not put that
     * turn back on the cluster. Re-found from scratch after joining or leaving the route,
     * where the vehicle may genuinely be anywhere along it.
     */
    private var cursor = 0
    private var offSince: Long? = null
    private var offFixes = 0

    init {
        for (i in 1 until count) {
            along[i] = along[i - 1] + distance(
                route.latitudes[i - 1], route.longitudes[i - 1], route.latitudes[i], route.longitudes[i],
            )
        }
        totalM = if (count > 0) along[count - 1] else 0.0
        steps = route.maneuverIndices.indices.mapNotNull { k ->
            val index = route.maneuverIndices[k]
            val command = route.commands.getOrElse(k) { btools.router.VoiceHintReader.CONTINUE }
            Maneuvers.of(command, route.exitNumbers.getOrElse(k) { 0 })?.let { index.coerceIn(0, count - 1) to it }
        }.let { found ->
            // The destination is a step of its own at the last point. BRouter does not
            // supply it: its END marker is internal to voice-hint processing and never
            // survives into the hints a track carries (measured on two Massachusetts
            // routes, P6.5). If a future BRouter does supply one, it is not doubled.
            if (found.lastOrNull()?.second?.type == Maneuvers.DESTINATION) {
                found
            } else {
                found + ((count - 1) to Maneuvers.of(btools.router.VoiceHintReader.END, 0)!!)
            }
        }
    }

    /** The maneuvers this route will put on the cluster, in order: for the report and tests. */
    fun maneuverTypes(): List<Int> = steps.map { it.second.type }

    /** One fix, [nowMs] on any monotonic clock. */
    fun update(latitude: Double, longitude: Double, nowMs: Long): FollowResult {
        if (state == FollowState.ARRIVED) return FollowResult(state, snap(latitude, longitude, 0, count - 1).offset, totalM, null)
        // Near where the vehicle was, when it was on the route; the whole route otherwise.
        val windowed = if (state == FollowState.ON_ROUTE) {
            snap(latitude, longitude, segmentAt(progress - BACK_M), segmentAt(progress + AHEAD_M) + 1)
        } else {
            null
        }
        val snapped = windowed?.takeIf { it.offset <= ON_ROUTE_M } ?: snap(latitude, longitude, 0, count - 1)
        if (snapped.offset <= ON_ROUTE_M) {
            offSince = null
            offFixes = 0
            // Backwards only within the window: a fix a few metres behind the last one is
            // GPS noise, and letting it through would restate a maneuver already passed.
            val continuing = state == FollowState.ON_ROUTE
            progress = if (continuing) max(snapped.along, progress - BACK_M) else snapped.along
            if (!continuing) cursor = 0
            state = FollowState.ON_ROUTE
            if (totalM - progress <= ARRIVE_M) {
                state = FollowState.ARRIVED
                return FollowResult(state, snapped.offset, totalM, null)
            }
        } else if (state != FollowState.OFF_ROUTE) {
            val limit = if (state == FollowState.JOINING) JOIN_GIVE_UP_M else ON_ROUTE_M
            if (snapped.offset > limit) {
                val since = offSince ?: nowMs.also { offSince = it }
                offFixes++
                if (offFixes >= OFF_ROUTE_FIXES && nowMs - since >= OFF_ROUTE_MS) state = FollowState.OFF_ROUTE
            } else {
                offSince = null
                offFixes = 0
            }
        }
        // While joining, the way to the first maneuver starts with the way to the route.
        val lead = if (state == FollowState.JOINING) snapped.offset else 0.0
        val from = if (state == FollowState.JOINING) 0.0 else progress
        return FollowResult(state, snapped.offset, from, next(from, lead, state == FollowState.ON_ROUTE))
    }

    private fun next(from: Double, lead: Double, onRoute: Boolean): NextStep {
        while (cursor < steps.size - 1 && along[steps[cursor].first] <= from) cursor++
        val k = cursor
        val (index, maneuver) = steps[k]
        val now = secondsAt(from)
        val stepM = along[index] - from + lead
        val destinationM = totalM - from + lead
        val onto = if (maneuver.type == Maneuvers.DESTINATION) null else route.roadAt(index)
        return NextStep(
            id = generation * STEP_ID_STRIDE + k,
            maneuver = maneuver,
            instruction = Maneuvers.instruction(maneuver, onto, route.roadAt(index - 1)),
            onto = onto,
            // Joining or off it, the vehicle is on some road the route does not know.
            road = if (onRoute) route.roadAt(segmentAt(from)) else null,
            metres = stepM,
            seconds = (secondsAt(along[index]) - now).roundToInt(),
            destinationMetres = destinationM,
            destinationSeconds = (secondsAt(totalM) - now).roundToInt(),
            later = (k + 1 until minOf(steps.size, k + 1 + LATER_STEPS)).map { j ->
                LaterStep(steps[j].second, along[steps[j].first] - from + lead)
            },
        )
    }

    /** BRouter's own time at [metres] along, interpolated between the two points around it. */
    private fun secondsAt(metres: Double): Double {
        if (totalM <= 0.0) return 0.0
        val times = route.pointSeconds
        if (times.size != count) return route.seconds * (metres / totalM).coerceIn(0.0, 1.0)
        val i = segmentAt(metres)
        val length = along[i + 1] - along[i]
        val t = if (length > 0.0) ((metres - along[i]) / length).coerceIn(0.0, 1.0) else 0.0
        return times[i] + t * (times[i + 1] - times[i])
    }

    /** The segment (its first point) containing [metres] along, clamped to the route. */
    /** The point index of the segment [metres] along the route falls in. */
    fun indexAt(metres: Double): Int = segmentAt(metres)

    /** Metres along the route to its point nearest ([latitude], [longitude]). */
    fun alongNearest(latitude: Double, longitude: Double): Double {
        var best = 0
        var nearest = Double.MAX_VALUE
        for (i in 0 until count) {
            val d = distance(route.latitudes[i], route.longitudes[i], latitude, longitude)
            if (d < nearest) {
                nearest = d
                best = i
            }
        }
        return along[best]
    }

    private fun segmentAt(metres: Double): Int {
        if (count < 2) return 0
        var lo = 0
        var hi = count - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (along[mid] <= metres) lo = mid else hi = mid
        }
        return lo.coerceAtMost(count - 2)
    }

    private class Snap(val offset: Double, val along: Double)

    /** Nearest point on segments [first]..[last] (by first point), flat around the fix. */
    private fun snap(latitude: Double, longitude: Double, first: Int, last: Int): Snap {
        if (count < 2) return Snap(Double.MAX_VALUE, 0.0)
        val scaleY = METRES_PER_DEGREE
        val scaleX = METRES_PER_DEGREE * cos(latitude * PI / 180.0)
        var best = Snap(Double.MAX_VALUE, 0.0)
        for (i in first.coerceIn(0, count - 2)..last.coerceIn(0, count - 2)) {
            val ax = (route.longitudes[i] - longitude) * scaleX
            val ay = (route.latitudes[i] - latitude) * scaleY
            val bx = (route.longitudes[i + 1] - longitude) * scaleX
            val by = (route.latitudes[i + 1] - latitude) * scaleY
            val dx = bx - ax
            val dy = by - ay
            val lengthSq = dx * dx + dy * dy
            val t = if (lengthSq > 0.0) (-(ax * dx + ay * dy) / lengthSq).coerceIn(0.0, 1.0) else 0.0
            val offset = hypot(ax + t * dx, ay + t * dy)
            // Ties go to the earlier segment: at a shared point, the one being left.
            if (offset < best.offset) best = Snap(offset, along[i] + t * (along[i + 1] - along[i]))
        }
        return best
    }

    companion object {
        /** A fix this close to the route is on it. */
        const val ON_ROUTE_M = 40.0
        /** While joining, only this far from the route gives up on it. */
        const val JOIN_GIVE_UP_M = 250.0
        const val OFF_ROUTE_MS = 4_000L
        const val OFF_ROUTE_FIXES = 3
        /** This close to the end, along the route, is arrived. */
        const val ARRIVE_M = 30.0
        private const val BACK_M = 50.0
        private const val AHEAD_M = 1_000.0
        /**
         * Maneuvers past the next one that the trip panel shows (plan §5.4): two, which is
         * what fits beside each other in a tile at 800x480, and as far ahead as a glance
         * needs — the rest of the route is on the map.
         */
        const val LATER_STEPS = 2
        /** Step identities are `generation * STRIDE + index`, so routes never share one. */
        const val STEP_ID_STRIDE = 1_000_000L
        private const val METRES_PER_DEGREE = 6_371_000.0 * PI / 180.0

        /** Degrees clockwise from north, from the first point toward the second, flat. */
        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val x = (lon2 - lon1) * cos((lat1 + lat2) / 2.0 * PI / 180.0)
            val y = lat2 - lat1
            return (Math.toDegrees(kotlin.math.atan2(x, y)) + 360.0) % 360.0
        }

        /** Metres between two points, flat around their mean latitude: fine at route scale. */
        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val x = (lon2 - lon1) * METRES_PER_DEGREE * cos((lat1 + lat2) / 2.0 * PI / 180.0)
            val y = (lat2 - lat1) * METRES_PER_DEGREE
            return hypot(x, y)
        }
    }
}
