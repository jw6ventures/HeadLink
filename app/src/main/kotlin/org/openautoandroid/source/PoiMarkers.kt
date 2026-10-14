package org.openautoandroid.source

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executor

/**
 * P11.5 (docs/p11-plan.md §6): the map's Gas, Food and Shop markers. Pure JVM, so the host
 * test (DashboardPoiTest) checks the rules, the query cadence and the capping with a fake
 * [ObfSource]; the VTM layer that draws them is [MapEngine.setPois], and the buttons and
 * the card are [Dashboard]'s.
 *
 * Names in [ObfPoi] are places near the driver: drawn on the card and nowhere else. Nothing
 * here logs or reports one (plan §8).
 */
object PoiRules {
    /** The buttons left to right, and the order markers are listed in. */
    val ORDER: List<PoiKind> = listOf(PoiKind.GAS, PoiKind.FOOD, PoiKind.SHOP)

    /** A button's word. */
    fun buttonLabel(kind: PoiKind): String = when (kind) {
        PoiKind.GAS -> "Gas"
        PoiKind.FOOD -> "Food"
        PoiKind.SHOP -> "Shop"
    }

    /**
     * Each kind's marker colour, the same in both palettes: saturated, and away from the
     * map themes' own colours, the route's cyan and the chrome's accent, so a marker reads
     * on the day and the night ground alike and the lab can count it by colour
     * (tools/lab-receiver: --poi). The glyph on it is white, ringed white then dark.
     */
    fun colour(kind: PoiKind): Int = when (kind) {
        PoiKind.GAS -> 0xFFD500F9.toInt() // violet
        PoiKind.FOOD -> 0xFFFF6D00.toInt() // orange
        PoiKind.SHOP -> 0xFF00B050.toInt() // green
    }

    /** "gas,food,shop" (the lab's LAB_POI) as kinds; unknown words are ignored. */
    fun parseKinds(text: String?): Set<PoiKind> =
        text.orEmpty().split(',', ' ', ';').mapNotNull { word ->
            PoiKind.entries.firstOrNull { it.label == word.trim().lowercase() }
        }.toSet()

    /** Great-circle metres between two points. */
    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    /**
     * The markers to draw from [found]: only [kinds], one per id and kind (neighbouring
     * files overlap at their borders), at most [limit] per kind, nearest ([lat], [lon])
     * first, listed in [ORDER] of kind.
     *
     * One place mapped twice is drawn once: OSM often has a fuel station as a point and
     * again as its forecourt's outline, and the lab's first card listed "Gulf" twice, 350
     * and 400 ft away. A second place of the same kind and the same name within
     * [SAME_PLACE_M] of one already kept is left out. Unnamed places are all kept.
     */
    fun nearestFirst(found: List<ObfPoi>, kinds: Set<PoiKind>, limit: Int, lat: Double, lon: Double): List<ObfPoi> {
        val out = ArrayList<ObfPoi>()
        for (kind in ORDER) {
            if (kind !in kinds) continue
            val seen = HashSet<Long>()
            val kept = ArrayList<ObfPoi>()
            for (poi in found.filter { it.kind == kind }.sortedBy { metres(lat, lon, it.latitude, it.longitude) }) {
                if (kept.size >= limit) break
                if (!seen.add(poi.id)) continue
                if (kept.any { samePlace(it, poi) }) continue
                kept += poi
            }
            out += kept
        }
        return out
    }

    /** Two places of one kind and one name closer than this are one place mapped twice. */
    const val SAME_PLACE_M = 75.0

    /**
     * Whether [a] and [b] are one place mapped twice: the same name, not empty, within
     * [SAME_PLACE_M]. The markers and search ahead both drop the second (callers compare
     * places of one kind only).
     */
    fun samePlace(a: ObfPoi, b: ObfPoi): Boolean {
        val name = a.name.trim().lowercase()
        return name.isNotEmpty() && b.name.trim().lowercase() == name &&
            metres(a.latitude, a.longitude, b.latitude, b.longitude) <= SAME_PLACE_M
    }

    /** [box] grown by [fraction] of its size on every side. */
    fun padded(box: GeoBox, fraction: Double): GeoBox {
        val dLat = (box.north - box.south) * fraction
        val dLon = (box.east - box.west) * fraction
        return GeoBox(
            (box.south - dLat).coerceAtLeast(-85.0), (box.west - dLon).coerceAtLeast(-180.0),
            (box.north + dLat).coerceAtMost(85.0), (box.east + dLon).coerceAtMost(180.0),
        )
    }

    /** Whether [outer] holds all of [inner]. */
    fun covers(outer: GeoBox, inner: GeoBox): Boolean =
        inner.south >= outer.south && inner.north <= outer.north && inner.west >= outer.west && inner.east <= outer.east

    /** What a place is, in words: "Gas station", "Fast food", "Supermarket". */
    fun kindWord(poi: ObfPoi): String {
        val sub = poi.subcategory.lowercase()
        return when (poi.kind) {
            PoiKind.GAS -> "Gas station"
            PoiKind.FOOD -> when (sub) {
                "restaurant" -> "Restaurant"
                "fast_food" -> "Fast food"
                "cafe" -> "Café"
                "food_court" -> "Food court"
                else -> words(sub) ?: "Food"
            }
            PoiKind.SHOP -> words(sub)?.takeUnless { it.equals("shop", ignoreCase = true) } ?: "Shop"
            null -> words(sub) ?: words(poi.category.lowercase()) ?: "Place"
        }
    }

    /** The place's name, else its English name or brand, else empty: what the card and the voice call it. */
    fun name(poi: ObfPoi): String =
        poi.name.trim().ifEmpty { poi.nameEn?.trim().orEmpty() }.ifEmpty { poi.brand?.trim().orEmpty() }

    /** The card's name line: [name], else what it is. */
    fun title(poi: ObfPoi): String = name(poi).ifEmpty { kindWord(poi) }

    /** The flex tile's label for a tap that found places of more than one kind. */
    const val PLACES = "places"

    /** The flex tile's label while it shows a marker of [kind]. */
    fun cardLabel(kind: PoiKind?): String = when (kind) {
        PoiKind.GAS -> "gas"
        PoiKind.FOOD -> "food"
        PoiKind.SHOP -> "shop"
        null -> "place"
    }

    private fun words(key: String): String? {
        val text = key.replace('_', ' ').replace(';', ' ').trim()
        if (text.isEmpty()) return null
        return text.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * "Open now" or "Closed now" when [hours] is in one of the plain shapes OSM's
     * `opening_hours` mostly takes — `24/7`, or rules like `Mo-Fr 07:00-22:00; Sa,Su
     * 08:00-20:00`, a time range with no days meaning every day — else null, and the card
     * leaves the line out. Holidays, `off`, sunrise, open ends and comments are not
     * guessed at: a wrong "Open now" sends a driver to a locked door.
     */
    fun openNow(hours: String?, nowMillis: Long, zone: ZoneId): String? {
        val text = hours?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text == "24/7") return OPEN_ALL_DAY
        val rules = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { parseRule(it) ?: return null }
        if (rules.isEmpty()) return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val day = now.dayOfWeek.value - 1 // Mo = 0
        val minute = now.hour * 60 + now.minute
        val yesterday = (day + 6) % 7
        // OSM's own rule: a later rule naming a day replaces what earlier ones said about it.
        val today = rules.lastOrNull { day in it.days }
        val before = rules.lastOrNull { yesterday in it.days }
        val open = today?.ranges?.any { (from, to) -> if (to > from) minute in from until to else minute >= from } == true ||
            before?.ranges?.any { (from, to) -> to <= from && minute < to } == true
        return if (open) OPEN else CLOSED
    }

    private class Rule(val days: Set<Int>, val ranges: List<Pair<Int, Int>>)

    private fun parseRule(rule: String): Rule? {
        val parts = rule.split(' ').filter { it.isNotEmpty() }
        val (dayPart, timePart) = when (parts.size) {
            1 -> null to parts[0]
            2 -> parts[0] to parts[1]
            else -> return null
        }
        val days = if (dayPart == null) (0..6).toSet() else parseDays(dayPart) ?: return null
        val ranges = timePart.split(',').map { range ->
            val m = TIME_RANGE.matchEntire(range) ?: return null
            val from = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            val to = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
            if (from > 24 * 60 || to > 24 * 60 || m.groupValues[2].toInt() > 59 || m.groupValues[4].toInt() > 59) return null
            from to to
        }
        return Rule(days, ranges)
    }

    private fun parseDays(text: String): Set<Int>? {
        val out = HashSet<Int>()
        for (piece in text.split(',')) {
            val ends = piece.split('-')
            val first = DAYS.indexOf(ends[0]).takeIf { it >= 0 } ?: return null
            when (ends.size) {
                1 -> out += first
                2 -> {
                    val last = DAYS.indexOf(ends[1]).takeIf { it >= 0 } ?: return null
                    var d = first
                    while (true) {
                        out += d
                        if (d == last) break
                        d = (d + 1) % 7
                    }
                }
                else -> return null
            }
        }
        return out
    }

    private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private val TIME_RANGE = Regex("(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})")
    const val OPEN = "Open now"
    const val CLOSED = "Closed now"
    const val OPEN_ALL_DAY = "Open 24 hours"
    private const val EARTH_RADIUS_M = 6_371_008.8
}

/**
 * One POI query over every open file: what the markers are refreshed with, and the hook
 * P11.5a's search ahead uses for each piece of its route corridor. Synchronous; call it off
 * the main and render threads.
 */
object PoiSearch {
    class Result(
        /** [PoiRules.nearestFirst] of everything found: capped per kind, nearest the centre first. */
        val pois: List<ObfPoi>,
        /** Files asked, of those whose bounds meet the box. */
        val sourcesAsked: Int,
        /** Files whose read threw; the rest still count. */
        val failures: Int,
    )

    fun query(
        sources: List<ObfSource>,
        box: GeoBox,
        kinds: Set<PoiKind>,
        limitPerKind: Int,
        centreLat: Double,
        centreLon: Double,
    ): Result {
        if (kinds.isEmpty() || limitPerKind <= 0) return Result(emptyList(), 0, 0)
        val found = ArrayList<ObfPoi>()
        var asked = 0
        var failures = 0
        for (source in sources) {
            val bounds = source.bounds
            if (bounds != null && !bounds.intersects(box)) continue
            asked++
            try {
                found += source.pois(box, kinds, limitPerKind, centreLat, centreLon, null)
            } catch (e: Exception) {
                // A short read, a file replaced mid-read: that file's markers are missing
                // this once, and the count says so. Never the file's name (plan §8).
                failures++
            }
        }
        return Result(PoiRules.nearestFirst(found, kinds, limitPerKind, centreLat, centreLon), asked, failures)
    }
}

/**
 * Where the map pane is looking and which buttons are lit, from [Dashboard.poiView] each
 * frame: what [PoiFeed] decides by.
 */
data class PoiView(
    val kinds: Set<PoiKind>,
    /** The pane's ground, corner to corner. */
    val box: GeoBox,
    val centreLat: Double,
    val centreLon: Double,
    /** VTM's zoom level, log2 of its scale. */
    val zoom: Double,
    /** The map is following the vehicle. */
    val following: Boolean,
)

/** The markers on the map now, replaced whole: the scene thread compares by reference. */
class PoiShown(
    val pois: List<ObfPoi>,
    /** A marker drawn larger (P11.5a's best stop), or null. */
    val emphasised: Long? = null,
) {
    companion object {
        val EMPTY = PoiShown(emptyList())
    }
}

/**
 * The marker refresh loop (plan §6). Fed by the scene thread every frame through [frame],
 * which only decides; queries run one at a time on [executor], and their result is
 * published in [shown] unless something newer has been asked for since, in which case it is
 * dropped and counted.
 *
 * When to ask again:
 * - at once when a button is turned on or off;
 * - when the pane has stopped moving for [SETTLE_MS] and no longer lies inside the last
 *   query's box, its centre has moved a quarter of the pane, or the zoom a whole level;
 * - while following the vehicle and still moving, at most every [FOLLOW_MS].
 *
 * The box asked for is the pane's grown by [PAD] on each side, so a drive does not empty
 * the map between refreshes; markers are capped per kind nearest the pane's centre.
 */
class PoiFeed(
    /** Every readable OBF file; called on [executor]. [ObfLibrary.sources] on the phone. */
    private val sources: () -> List<ObfSource>,
    /** Markers per kind ([PoiSettings.markersPerKind]); read at each query. */
    private val limit: () -> Int,
    private val executor: Executor,
    /** Monotonic milliseconds, for the query timings. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()

    @Volatile
    var shown: PoiShown = PoiShown.EMPTY
        private set

    // Scene-thread decision state, under [lock] because the worker reads the generation.
    private var lastView: PoiView? = null
    private var movedAt = 0L
    private var queriedKinds: Set<PoiKind> = emptySet()
    private var queriedBox: GeoBox? = null
    private var queriedView: PoiView? = null
    private var lastQueryAt = Long.MIN_VALUE / 2
    private var retryAt = 0L
    private var inFlight = false
    private var generation = 0L
    private var base: List<ObfPoi> = emptyList()
    private var extra: List<ObfPoi> = emptyList()
    private var emphasised: Long? = null

    // Counts for the report: never a place.
    private var queries = 0L
    private var failures = 0L
    private var sourceFailures = 0L
    private var stale = 0L
    private var markersMax = 0
    private var sourcesMax = 0
    private val timings = ArrayList<Long>()

    /** Scene thread, every frame: the pane as drawn and the buttons lit, or null with no camera. */
    fun frame(view: PoiView?, nowMillis: Long) {
        if (view == null) return
        var ask: Ask? = null
        synchronized(lock) {
            val last = lastView
            if (last == null || last.box != view.box || last.zoom != view.zoom) movedAt = nowMillis
            lastView = view
            if (view.kinds.isEmpty()) {
                if (queriedKinds.isNotEmpty() || base.isNotEmpty()) {
                    // Off at once: nothing in flight may bring them back.
                    generation++
                    queriedKinds = emptySet()
                    queriedBox = null
                    base = emptyList()
                    publish()
                }
                return
            }
            val kindsChanged = view.kinds != queriedKinds
            if (kindsChanged) {
                // A button turned off takes its markers away before the new query returns.
                val kept = base.filter { it.kind in view.kinds }
                if (kept.size != base.size) {
                    base = kept
                    publish()
                }
            }
            if (!kindsChanged && !needs(view)) return
            if (!kindsChanged && (inFlight || nowMillis < retryAt)) return
            val settled = nowMillis - movedAt >= SETTLE_MS
            val due = view.following && nowMillis - lastQueryAt >= FOLLOW_MS
            if (!kindsChanged && !settled && !due) return
            generation++
            inFlight = true
            lastQueryAt = nowMillis
            queriedKinds = view.kinds
            queriedView = view
            val box = PoiRules.padded(view.box, PAD)
            queriedBox = box
            ask = Ask(generation, box, view.kinds, view.centreLat, view.centreLon)
        }
        ask?.let { run(it, nowMillis) }
    }

    /** Whether the last query no longer serves [view]: off its box, a quarter-pane away, or a zoom level apart. */
    private fun needs(view: PoiView): Boolean {
        val box = queriedBox ?: return true
        val asked = queriedView ?: return true
        if (!PoiRules.covers(box, view.box)) return true
        if (Math.abs(view.zoom - asked.zoom) >= 1.0) return true
        val quarterLat = (view.box.north - view.box.south) / 4
        val quarterLon = (view.box.east - view.box.west) / 4
        return Math.abs(view.centreLat - asked.centreLat) > quarterLat || Math.abs(view.centreLon - asked.centreLon) > quarterLon
    }

    private class Ask(val generation: Long, val box: GeoBox, val kinds: Set<PoiKind>, val lat: Double, val lon: Double)

    private fun run(ask: Ask, askedAt: Long) {
        try {
            executor.execute {
                val start = clock()
                var result: PoiSearch.Result? = null
                try {
                    result = PoiSearch.query(sources(), ask.box, ask.kinds, limit(), ask.lat, ask.lon)
                } catch (e: Exception) {
                    // The library itself failed (a folder gone); tried again later.
                }
                val ms = clock() - start
                synchronized(lock) {
                    inFlight = false
                    queries++
                    timings += ms
                    if (timings.size > TIMINGS_KEPT) timings.removeAt(0)
                    if (result == null) {
                        failures++
                        retryAt = askedAt + FOLLOW_MS
                        if (ask.generation == generation) queriedBox = null
                        return@synchronized
                    }
                    sourceFailures += result.failures
                    sourcesMax = maxOf(sourcesMax, result.sourcesAsked)
                    if (ask.generation != generation) {
                        stale++
                        return@synchronized
                    }
                    base = result.pois
                    publish()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            synchronized(lock) { inFlight = false }
        }
    }

    /** Under [lock]: [base] and [extra] together, as the map and the dashboard draw them. */
    private fun publish() {
        val pois = if (extra.isEmpty()) {
            base
        } else {
            val ids = base.mapTo(HashSet()) { it.id }
            base + extra.filter { ids.add(it.id) }
        }
        markersMax = maxOf(markersMax, pois.size)
        shown = PoiShown(pois, emphasised)
    }

    /** The markers of [kind] on the map now (P11.5a's hook): nearest the pane's centre first. */
    fun current(kind: PoiKind): List<ObfPoi> = shown.pois.filter { it.kind == kind }

    /**
     * Markers to draw beside the pane's own (P11.5a: those along the route ahead), with
     * [emphasised] drawn larger; empty takes them away. Kept until replaced, whatever the
     * buttons do.
     */
    fun extra(pois: List<ObfPoi>, emphasised: Long? = null) {
        synchronized(lock) {
            extra = pois
            this.emphasised = emphasised
            publish()
        }
    }

    /** `poi_*` counts for the report: queries, failures, timings; never a place or a file. */
    fun report(): String = synchronized(lock) {
        val sorted = timings.sorted()
        fun pct(p: Int): String = if (sorted.isEmpty()) "-" else sorted[minOf(sorted.size - 1, (sorted.size - 1) * p / 100)].toString()
        "poi_queries=$queries poi_query_failures=$failures poi_source_failures=$sourceFailures " +
            "poi_stale_dropped=$stale poi_query_ms_p50=${pct(50)} poi_query_ms_p95=${pct(95)} " +
            "poi_query_ms_max=${sorted.lastOrNull() ?: "-"} poi_markers=${shown.pois.size} poi_markers_max=$markersMax " +
            "poi_sources_max=$sourcesMax poi_extra=${extra.size}\n"
    }

    companion object {
        /** The pane must be still this long before a refresh (plan §6). */
        const val SETTLE_MS = 500L

        /** While following and moving, no more often than this (plan §6). */
        const val FOLLOW_MS = 10_000L

        /** The query's box is the pane's grown by this much of its size on each side. */
        const val PAD = 0.5

        private const val TIMINGS_KEPT = 512
    }
}

/**
 * One place on the flex tile's POI card. [detail] null composes "Gas station · 0.4 mi" from
 * the place and the vehicle each frame; P11.5a passes its own ("12 mi ahead · 3 min detour
 * · right"). [openNow] adds the open-now line where the hours parse ([PoiRules.openNow]).
 */
class PoiEntry @JvmOverloads constructor(
    val poi: ObfPoi,
    val detail: String? = null,
    val openNow: Boolean = true,
)

/**
 * What the POI card shows (plan §6, §6a): up to [MAX_ENTRIES] places, the first chosen,
 * a row tap choosing another, and two buttons under them, [primary] ("Go", or P11.5a's "Add
 * stop") for the chosen place and Close. [tag] comes back in [PoiAction] so the caller
 * knows which flow the press belongs to; [label] is the tile's label.
 *
 * With no entries and a [message], it is a notice instead (P11.5a: "None in the next 50
 * mi"): the one line and Close alone, so a driver with the voice off still gets the answer.
 */
class PoiCardContent @JvmOverloads constructor(
    val label: String,
    val entries: List<PoiEntry>,
    val primary: String = GO,
    val tag: String = TAG_MAP,
    val message: String? = null,
) {
    /** A notice: no places, only [message] and Close. */
    val notice: Boolean get() = entries.isEmpty() && message != null

    companion object {
        const val MAX_ENTRIES = 3
        const val GO = "Go"
        const val ADD_STOP = "Add stop"

        /** P11.5a's primary word while the route already has a via point, which a stop replaces. */
        const val REPLACE_STOP = "Replace stop"

        /** P11.5a: a search-ahead card, whose primary adds the chosen place as the route's stop. */
        const val TAG_AHEAD = "ahead"

        /** A marker tapped on the map. */
        const val TAG_MAP = "map"
    }
}

/** The primary button pressed on the card, for the place chosen on it: taken once by the service. */
class PoiAction(val entry: PoiEntry, val primary: String, val tag: String) {
    /** Leaves the place out, in case one is ever printed. */
    override fun toString(): String = "PoiAction($primary, $tag)"
}

/** One row as drawn: in words already, so the chrome repaints only when they change. */
data class PoiRowView(val name: String, val detail: String, val open: String?, val kind: PoiKind?)

/** The card as drawn this frame. */
data class PoiCardView @JvmOverloads constructor(
    val rows: List<PoiRowView>,
    val selected: Int,
    val primary: String,
    /** The card button a finger is on, [DashboardLayout.POI_CARD_ACTIONS], or null. */
    val pressed: String?,
    /** The row a finger is on, or -1. */
    val pressedRow: Int,
    /** A notice's one line ([PoiCardContent.notice]): no rows, no primary button, Close only. */
    val message: String? = null,
) {
    override fun toString(): String = "PoiCardView(rows=${rows.size} selected=$selected)"
}

/** The three map buttons as drawn: which are lit, which a finger is on, and their top edge. */
data class PoiButtons(val lit: Set<PoiKind>, val pressed: PoiKind?, val top: Int)
