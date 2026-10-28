package com.jw6ventures.headlink

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * P11.5a (docs/navigation.md, "OsmAnd maps: search and places"): search ahead on the
 * route. While a route is followed, a press that lights Gas, Food or Shop also looks
 * along the road ahead, works out the detour to the nearest few places with BRouter,
 * says the best one and lists up to three on the flex tile with **Add stop**.
 *
 * Everything here is pure JVM, for the host test (SearchAheadTest): the corridor and its
 * query boxes, projecting a place onto the route, the detour's two short routes and its
 * arithmetic, the best-stop rule, the words, the via point's release, and the counts. The
 * threads, the speaker and the card are [AheadSearcher]'s.
 *
 * Names in [ObfPoi] are places near the driver: spoken and drawn, never reported.
 */

/** Which side of the road a place is on, facing along the route. */
enum class RoadSide(val word: String) { LEFT("left"), RIGHT("right") }

/** A place in the corridor ahead, where it falls on the route. */
class AheadHit(
    val poi: ObfPoi,
    /** Metres along the route from the vehicle to the place's projection. */
    val aheadM: Double,
    /** Metres from the route line to the place. */
    val offsetM: Double,
    val side: RoadSide,
    /** Metres along the whole route to the projection: where A and B are measured from. */
    val alongM: Double,
) {
    override fun toString(): String = "AheadHit(ahead=${aheadM.roundToInt()} m, off=${offsetM.roundToInt()} m, $side)"
}

/** A place whose detour was routed. */
class AheadStop(val hit: AheadHit, val detourSeconds: Int) {
    override fun toString(): String = "AheadStop($hit, detour=$detourSeconds s)"
}

/**
 * The stretch of [route] still ahead: from [fromAlongM] (the vehicle's progress, as
 * [RouteFollower] measures it) for [lengthM], or to the destination if that is nearer.
 * Distances are on [RouteFollower.distance]'s flat measure, so they agree with guidance.
 */
class AheadCorridor(val route: Route, fromAlongM: Double, lengthM: Double) {
    private val count = route.points
    private val along = DoubleArray(count)
    val totalM: Double
    val startM: Double
    val endM: Double

    init {
        for (i in 1 until count) {
            along[i] = along[i - 1] + RouteFollower.distance(
                route.latitudes[i - 1], route.longitudes[i - 1], route.latitudes[i], route.longitudes[i],
            )
        }
        totalM = if (count > 0) along[count - 1] else 0.0
        startM = fromAlongM.coerceIn(0.0, totalM)
        endM = min(totalM, startM + max(0.0, lengthM))
    }

    /** How far the corridor reaches ahead of the vehicle. */
    val lengthM: Double get() = endM - startM

    /** Whether it runs out at the destination rather than at the look-ahead. */
    val reachesDestination: Boolean get() = endM >= totalM - 1.0

    /** The segment (by its first point) holding [metres] along the route. */
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

    /** The point [metres] along the route, [latitude, longitude]. */
    fun point(metres: Double): DoubleArray {
        if (count == 0) return doubleArrayOf(0.0, 0.0)
        if (count == 1) return doubleArrayOf(route.latitudes[0], route.longitudes[0])
        val m = metres.coerceIn(0.0, totalM)
        val i = segmentAt(m)
        val length = along[i + 1] - along[i]
        val t = if (length > 0.0) ((m - along[i]) / length).coerceIn(0.0, 1.0) else 0.0
        return doubleArrayOf(
            route.latitudes[i] + t * (route.latitudes[i + 1] - route.latitudes[i]),
            route.longitudes[i] + t * (route.longitudes[i + 1] - route.longitudes[i]),
        )
    }

    /** BRouter's own time from the start to [metres] along, interpolated; by distance when the route has no point times. */
    fun secondsAt(metres: Double): Double {
        if (totalM <= 0.0) return 0.0
        val m = metres.coerceIn(0.0, totalM)
        val times = route.pointSeconds
        if (times.size != count) return route.seconds * (m / totalM)
        val i = segmentAt(m)
        val length = along[i + 1] - along[i]
        val t = if (length > 0.0) ((m - along[i]) / length).coerceIn(0.0, 1.0) else 0.0
        return times[i] + t * (times[i + 1] - times[i])
    }

    /** The way the route runs at [metres] along, degrees clockwise from north. */
    fun bearingAt(metres: Double): Double {
        val m = metres.coerceIn(0.0, totalM)
        val from = point(max(0.0, min(m, totalM - BEARING_BASE_M)))
        val to = point(min(totalM, max(m, 0.0) + BEARING_BASE_M))
        return RouteFollower.bearing(from[0], from[1], to[0], to[1])
    }

    /**
     * The corridor cut into pieces of about [pieceM], each the box around its stretch of
     * line grown by [padM] on every side: what is asked of the OBF files, one query each,
     * so a winding road is not one huge box. The last piece ends at the corridor's end.
     */
    fun pieces(pieceM: Double, padM: Double): List<GeoBox> {
        if (count < 2 || lengthM < MIN_LENGTH_M) return emptyList()
        val out = ArrayList<GeoBox>()
        var from = startM
        while (from < endM - 0.5) {
            val to = min(endM, from + pieceM)
            var south = Double.MAX_VALUE
            var north = -Double.MAX_VALUE
            var west = Double.MAX_VALUE
            var east = -Double.MAX_VALUE
            fun take(lat: Double, lon: Double) {
                south = min(south, lat)
                north = max(north, lat)
                west = min(west, lon)
                east = max(east, lon)
            }
            point(from).let { take(it[0], it[1]) }
            for (i in segmentAt(from) + 1..segmentAt(to)) {
                if (along[i] > from && along[i] < to) take(route.latitudes[i], route.longitudes[i])
            }
            point(to).let { take(it[0], it[1]) }
            val padLat = padM / METRES_PER_DEGREE
            val padLon = padM / (METRES_PER_DEGREE * cos((south + north) / 2.0 * PI / 180.0))
            out += GeoBox(south - padLat, west - padLon, north + padLat, east + padLon)
            from = to
        }
        return out
    }

    /**
     * Where ([latitude], [longitude]) falls on the corridor: the nearest point of its line,
     * as metres ahead of the vehicle, metres off the line and the side. A place nearest the
     * corridor's very start is beside or behind the vehicle and has 0 ahead.
     */
    fun project(latitude: Double, longitude: Double): Projection? {
        if (count < 2 || lengthM < MIN_LENGTH_M) return null
        val scaleY = METRES_PER_DEGREE
        val scaleX = METRES_PER_DEGREE * cos(latitude * PI / 180.0)
        val first = segmentAt(startM)
        val last = segmentAt(endM)
        var best: Projection? = null
        for (i in first..last) {
            val segment = along[i + 1] - along[i]
            if (segment <= 0.0) continue
            // This segment's share of the corridor, as fractions of it.
            val t0 = ((startM - along[i]) / segment).coerceIn(0.0, 1.0)
            val t1 = ((endM - along[i]) / segment).coerceIn(0.0, 1.0)
            // Flat around the place: the place at the origin.
            val ax = (route.longitudes[i] - longitude) * scaleX
            val ay = (route.latitudes[i] - latitude) * scaleY
            val bx = (route.longitudes[i + 1] - longitude) * scaleX
            val by = (route.latitudes[i + 1] - latitude) * scaleY
            val dx = bx - ax
            val dy = by - ay
            val lengthSq = dx * dx + dy * dy
            val t = if (lengthSq > 0.0) (-(ax * dx + ay * dy) / lengthSq).coerceIn(t0, t1) else t0
            val px = ax + t * dx
            val py = ay + t * dy
            val offset = hypot(px, py)
            if (best == null || offset < best.offsetM) {
                // From the line to the place is (-px, -py); left when the turn from the
                // route's direction to it is anticlockwise.
                val cross = dx * (-py) - dy * (-px)
                best = Projection(along[i] + t * segment - startM, offset, if (cross > 0) RoadSide.LEFT else RoadSide.RIGHT, along[i] + t * segment)
            }
        }
        return best
    }

    class Projection(val aheadM: Double, val offsetM: Double, val side: RoadSide, val alongM: Double)

    companion object {
        private const val METRES_PER_DEGREE = 6_371_000.0 * PI / 180.0

        /** A corridor shorter than this is nothing: the vehicle is at the destination. */
        const val MIN_LENGTH_M = 1.0

        /** Metres the route's direction is measured over at a point. */
        const val BEARING_BASE_M = 30.0
    }
}

/**
 * The detour's ends for one place: **A** about [AheadSearch.LEG_M] before its projection
 * and **B** about as far after it, clamped to the vehicle and the destination, with the
 * route's own time between them and its direction at A.
 */
class DetourLeg(
    val aAlongM: Double,
    val bAlongM: Double,
    val aLat: Double,
    val aLon: Double,
    val bLat: Double,
    val bLon: Double,
    /** The route's way at A, so the first short route does not start by turning round. */
    val aBearing: Double,
    /** The followed route's own seconds from A to B. */
    val baseSeconds: Double,
)

/** What one corridor query found. */
class AheadHits(
    /** In the corridor and ahead, nearest first, one per place. */
    val hits: List<AheadHit>,
    val queries: Int,
    /** File reads that threw: counted, the rest still count. */
    val failures: Int,
)

/** What the detour routing made of the candidates. */
class AheadDetours(val stops: List<AheadStop>, val routed: Int, val failed: Int, val cancelled: Boolean)

/** One short route, on the routing thread; throws [RoutingException] when there is none. */
fun interface LegRouter {
    @Throws(RoutingException::class)
    fun route(request: RouteRequest): Route
}

object AheadSearch {
    /** Metres per mile, for the settings' miles. */
    const val METRES_PER_MILE = 1609.344

    /** Corridor pieces about this long: a few kilometres, so each box stays close to the road. */
    const val PIECE_M = 3_000.0

    /** At most this many places of the kind per piece, nearest its middle. */
    const val PIECE_LIMIT = 200

    /**
     * Places nearer than this along the route are dropped: beside or behind the vehicle
     * already, or too close to turn off for.
     */
    const val MIN_AHEAD_M = 300.0

    /** A and B are this far before and after the place's projection (about 2 km). */
    const val LEG_M = 2_000.0

    /**
     * A nearer stop whose detour is within this of the smallest beats the smallest further
     * on; it also settles a tie.
     */
    const val NEARER_WITHIN_S = 120

    /** How much nearer a stop must be to come before a smaller detour further on. */
    const val NEARER_BY_M = 1_000.0

    /** Places on the card, best first. */
    const val CARD_STOPS = 3

    /**
     * The places of [kind] in [corridor], at most [widthM] off its line and at least
     * [MIN_AHEAD_M] ahead, one per place (one id, or one name of one kind within
     * [PoiRules.SAME_PLACE_M]), nearest first. Synchronous file reads.
     */
    fun hits(sources: List<ObfSource>, corridor: AheadCorridor, kind: PoiKind, widthM: Double): AheadHits {
        val boxes = corridor.pieces(PIECE_M, widthM)
        val found = ArrayList<ObfPoi>()
        var failures = 0
        for (box in boxes) {
            val result = PoiSearch.query(
                sources, box, setOf(kind), PIECE_LIMIT, (box.south + box.north) / 2, (box.west + box.east) / 2,
            )
            failures += result.failures
            found += result.pois
        }
        return AheadHits(inCorridor(corridor, found, kind, widthM), boxes.size, failures)
    }

    /** [hits]' filter and order over places already read: the host test's way in. */
    fun inCorridor(corridor: AheadCorridor, found: List<ObfPoi>, kind: PoiKind, widthM: Double): List<AheadHit> {
        val hits = found.asSequence()
            .filter { it.kind == kind }
            .mapNotNull { poi ->
                val p = corridor.project(poi.latitude, poi.longitude) ?: return@mapNotNull null
                if (p.offsetM > widthM || p.aheadM < MIN_AHEAD_M) null else AheadHit(poi, p.aheadM, p.offsetM, p.side, p.alongM)
            }
            .sortedBy { it.aheadM }
            .toList()
        val kept = ArrayList<AheadHit>()
        val ids = HashSet<Long>()
        for (hit in hits) {
            if (!ids.add(hit.poi.id)) continue
            if (kept.any { PoiRules.samePlace(it.poi, hit.poi) }) continue
            kept += hit
        }
        return kept
    }

    /** A and B for [hit]: [LEG_M] either side of its projection, inside the corridor's start and the route's end. */
    fun leg(corridor: AheadCorridor, hit: AheadHit): DetourLeg {
        val a = max(corridor.startM, hit.alongM - LEG_M)
        val b = min(corridor.totalM, hit.alongM + LEG_M)
        val pa = corridor.point(a)
        val pb = corridor.point(b)
        return DetourLeg(a, b, pa[0], pa[1], pb[0], pb[1], corridor.bearingAt(a), corridor.secondsAt(b) - corridor.secondsAt(a))
    }

    /** The detour in whole seconds: there and on again, less the route's own time; never below 0. */
    fun detourSeconds(toPlaceS: Int, onwardS: Int, baseSeconds: Double): Int =
        max(0, (toPlaceS + onwardS - baseSeconds).roundToInt())

    /**
     * Routes the detour of each of [candidates] (the nearest few, in order): A → place with
     * the route's way at A, then place → B. One that cannot be routed is dropped and
     * counted. [options] and [nogos] are the followed route's own, as the live alternatives
     * use them. Stops at [cancelled] between routes.
     */
    fun detours(
        router: LegRouter,
        corridor: AheadCorridor,
        candidates: List<AheadHit>,
        options: RouteOptions,
        nogos: List<Nogo>,
        cancelled: () -> Boolean,
    ): AheadDetours {
        val stops = ArrayList<AheadStop>()
        var routed = 0
        var failed = 0
        for (hit in candidates) {
            if (cancelled()) return AheadDetours(stops, routed, failed, true)
            val leg = leg(corridor, hit)
            try {
                val there = router.route(
                    RouteRequest(leg.aLat, leg.aLon, hit.poi.latitude, hit.poi.longitude, leg.aBearing, options, nogos = nogos),
                )
                if (cancelled()) return AheadDetours(stops, routed, failed, true)
                val onward = router.route(
                    RouteRequest(hit.poi.latitude, hit.poi.longitude, leg.bLat, leg.bLon, null, options, nogos = nogos),
                )
                routed++
                stops += AheadStop(hit, detourSeconds(there.seconds, onward.seconds, leg.baseSeconds))
            } catch (e: RoutingException) {
                if (e.stopped && cancelled()) return AheadDetours(stops, routed, failed, true)
                failed++
            } catch (e: RuntimeException) {
                // A malformed request or BRouter's own failure: this place only.
                failed++
            }
        }
        return AheadDetours(stops, routed, failed, false)
    }

    /**
     * The stop to name: the smallest detour, the nearer of equal ones; except that
     * a stop at least [NEARER_BY_M] nearer whose detour is within [NEARER_WITHIN_S] of it
     * comes first, the nearest such. "Further on" means a real distance: two stations at
     * one exit are not one nearer than the other (the first lab run named a 2-minute
     * Chevron over a 1-minute Shell a few metres beyond it).
     */
    fun best(stops: List<AheadStop>): AheadStop? {
        val least = stops.minWithOrNull(compareBy<AheadStop>({ it.detourSeconds }, { it.hit.aheadM })) ?: return null
        return stops.filter {
            it.detourSeconds <= least.detourSeconds + NEARER_WITHIN_S && it.hit.aheadM <= least.hit.aheadM - NEARER_BY_M
        }.minByOrNull { it.hit.aheadM } ?: least
    }

    /** The card's stops: the best first, then the others nearest first, [CARD_STOPS] at most. */
    fun cardStops(stops: List<AheadStop>): List<AheadStop> {
        val best = best(stops) ?: return emptyList()
        return (listOf(best) + stops.filter { it !== best }.sortedBy { it.hit.aheadM }).take(CARD_STOPS)
    }

    /** Whole minutes of a detour as said and shown: rounded, and never less than 1. */
    fun minutes(seconds: Int): Int = max(1, (seconds / 60.0).roundToInt())

    /** One card row's detail: "12 mi ahead · 3 min detour · right". */
    fun detail(stop: AheadStop, unit: SpeedUnit): String =
        "${unit.distance(stop.hit.aheadM)} ahead · ${minutes(stop.detourSeconds)} min detour · ${stop.hit.side.word}"

    /** The card's label: "gas ahead". */
    fun cardLabel(kind: PoiKind): String = "${PoiRules.cardLabel(kind)} ahead"

    /**
     * What is said: "Gas along your route in 12 miles: Buc-ee's, about a 3-minute detour."
     * With no name, "a gas station", "a restaurant" or "a store". With no stop, "No gas
     * along your route in the next 50 miles." ([searchedM], in [unit]'s words), or "… before
     * your destination." when the corridor ran out there. With places found and every detour
     * failed ([detoursFailed]), "Gas along your route, but no detour could be worked out."
     */
    @JvmOverloads
    fun spoken(
        kind: PoiKind,
        stop: AheadStop?,
        unit: SpeedUnit,
        searchedM: Double,
        reachesDestination: Boolean,
        detoursFailed: Boolean = false,
    ): String {
        if (stop == null && detoursFailed) {
            val subject = when (kind) {
                PoiKind.GAS -> "Gas"
                PoiKind.FOOD -> "Food"
                PoiKind.SHOP -> "Shopping"
            }
            return "$subject along your route, but no detour could be worked out."
        }
        if (stop == null) {
            val what = when (kind) {
                PoiKind.GAS -> "gas"
                PoiKind.FOOD -> "food"
                PoiKind.SHOP -> "shops"
            }
            return if (reachesDestination) {
                "No $what along your route before your destination."
            } else {
                "No $what along your route in the next ${SpokenText.distance(searchedM, unit)}."
            }
        }
        val subject = when (kind) {
            PoiKind.GAS -> "Gas"
            PoiKind.FOOD -> "Food"
            PoiKind.SHOP -> "Shopping"
        }
        return "$subject along your route in ${SpokenText.distance(stop.hit.aheadM, unit)}: " +
            "${spokenName(stop.hit.poi, kind)}, about a ${minutes(stop.detourSeconds)}-minute detour."
    }

    /**
     * The card's one line when there is no stop to name: "None in the next 50 mi" ([searchedM]
     * in [unit]'s card form), "None before your destination", or, when places were found and
     * every detour failed, "No detour could be worked out".
     */
    fun noneLine(unit: SpeedUnit, searchedM: Double, reachesDestination: Boolean, detoursFailed: Boolean): String = when {
        detoursFailed -> "No detour could be worked out"
        reachesDestination -> "None before your destination"
        else -> "None in the next ${unit.distance(searchedM)}"
    }

    /** [PoiRules.name], else what it is with "a". */
    fun spokenName(poi: ObfPoi, kind: PoiKind): String =
        PoiRules.name(poi).ifEmpty {
            when (kind) {
                PoiKind.GAS -> "a gas station"
                PoiKind.FOOD -> "a restaurant"
                PoiKind.SHOP -> "a store"
            }
        }
}

/**
 * When the route's via point is let go (P11.5a): once the vehicle has come within
 * [REACHED_M] of it, or is on the route more than [PASSED_M] beyond it. Either way a
 * re-route from then on must not send the driver back to it: after a stop for gas the
 * vehicle often leaves the station by another road than the route came in on, which is off
 * the route, and the re-route that follows used to keep the via point.
 */
object ViaRelease {
    const val REACHED_M = 50.0
    const val PASSED_M = 50.0

    /**
     * [viaAlongM] is the via point's metres along the followed route, or null when it is not
     * on it (nothing to pass); [metresToVia] the fix's straight-line distance to it.
     */
    fun passed(viaAlongM: Double?, onRoute: Boolean, alongM: Double, metresToVia: Double): Boolean =
        metresToVia <= REACHED_M || (onRoute && viaAlongM != null && alongM > viaAlongM + PASSED_M)
}

/**
 * The search-ahead counts for the report: presses, candidates, detours routed
 * and failed, and the time from a press to what was said. **Never a name or a place.**
 */
class AheadCounts {
    var presses = 0
    var searches = 0
    var cancelled = 0
    var candidates = 0
    var routed = 0
    var failed = 0
    var queries = 0
    var queryFailures = 0
    var found = 0
    var none = 0
    var addStops = 0
    var viaReplaced = 0
    private val announceMs = ArrayList<Long>()
    private val corridorMs = ArrayList<Long>()

    @Synchronized
    fun announced(ms: Long) {
        announceMs += ms
        if (announceMs.size > KEPT) announceMs.removeAt(0)
    }

    @Synchronized
    fun corridor(ms: Long) {
        corridorMs += ms
        if (corridorMs.size > KEPT) corridorMs.removeAt(0)
    }

    @Synchronized
    fun report(): String {
        fun pct(list: List<Long>, p: Int): String {
            val sorted = list.sorted()
            return if (sorted.isEmpty()) "-" else sorted[min(sorted.size - 1, (sorted.size - 1) * p / 100)].toString()
        }
        return "poi_ahead_presses=$presses poi_ahead_searches=$searches poi_ahead_cancelled=$cancelled " +
            "poi_ahead_queries=$queries poi_ahead_query_failures=$queryFailures poi_ahead_candidates=$candidates " +
            "poi_ahead_detours_routed=$routed poi_ahead_detours_failed=$failed poi_ahead_found=$found " +
            "poi_ahead_none=$none poi_ahead_announced=${announceMs.size} " +
            "poi_ahead_ms_p50=${pct(announceMs, 50)} poi_ahead_ms_p95=${pct(announceMs, 95)} " +
            "poi_ahead_ms_max=${announceMs.maxOrNull() ?: "-"} poi_ahead_corridor_ms_p50=${pct(corridorMs, 50)} " +
            "poi_ahead_add_stops=$addStops poi_ahead_via_replaced=$viaReplaced\n"
    }

    companion object {
        private const val KEPT = 256
    }
}
