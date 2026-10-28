package com.jw6ventures.headlink

import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Counts for the report: never a typed word, a place name or a position. Shared
 * across threads; every method is synchronized.
 */
class SearchStats {
    /** One frozen copy of the counts. [p50Ms] and [p95Ms] are over the last [WINDOW] finished queries. */
    data class Snapshot(
        val queries: Int,
        val cancelled: Int,
        val empty: Int,
        val sourcesFailed: Int,
        val results: Map<SuggestionKind, Int>,
        val p50Ms: Long,
        val p95Ms: Long,
        val maxMs: Long,
    ) {
        /** One report line, counts only. */
        fun line(): String =
            "search queries=$queries cancelled=$cancelled empty=$empty failed_sources=$sourcesFailed " +
                SuggestionKind.values().joinToString(" ") { "${it.name.lowercase()}=${results[it] ?: 0}" } +
                " p50_ms=$p50Ms p95_ms=$p95Ms max_ms=$maxMs"
    }

    private var queries = 0
    private var cancelled = 0
    private var empty = 0
    private var sourcesFailed = 0
    private val results = IntArray(SuggestionKind.values().size)
    private val times = LongArray(WINDOW)
    private var timesCount = 0
    private var maxMs = 0L

    @Synchronized internal fun failed() { sourcesFailed++ }

    @Synchronized internal fun cancelledQuery() { queries++; cancelled++ }

    @Synchronized internal fun finished(found: List<Suggestion>, ms: Long) {
        queries++
        if (found.isEmpty()) empty++
        for (s in found) results[s.kind.ordinal]++
        times[timesCount % WINDOW] = ms
        timesCount++
        maxMs = max(maxMs, ms)
    }

    @Synchronized fun snapshot(): Snapshot {
        val n = min(timesCount, WINDOW)
        val sorted = times.copyOf(n).also { it.sort() }
        fun pct(p: Double): Long = if (n == 0) 0 else sorted[min(n - 1, (p * n).toInt())]
        return Snapshot(
            queries, cancelled, empty, sourcesFailed,
            SuggestionKind.values().associateWith { results[it.ordinal] },
            pct(0.50), pct(0.95), maxMs,
        )
    }

    companion object {
        const val WINDOW = 200
    }
}

/**
 * Typed address search over OsmAnd's files (docs/navigation.md, "OsmAnd maps: search and
 * places"). Pure JVM: the files come in as [ObfSource]s, so the host tests drive it with a
 * fake and with the real reader.
 *
 * What was typed is read every plausible way ([AddressQuery.parse]); for each reading the
 * candidate cities are found by name (or ZIP, or nearest the phone when no city was typed),
 * the street by name inside them, and a house number on the street: an exact building, an
 * interpolation range that holds it, or the nearest numbers below and above. With no house
 * number and no street, POI names are searched near the phone too.
 *
 * Blocking file I/O through the sources: never call it on the main thread. One instance may
 * be used from several threads at once (an old keystroke's search still running when the
 * next starts); [suggest] checks `cancelled` between every file read.
 */
class AddressSearch(private val sources: () -> List<ObfSource>) {
    /** Counts only, for the report. */
    val stats = SearchStats()

    /**
     * Up to [limit] suggestions for [text], best first: the street as typed, then a full
     * name, then a prefix; within each, exact number, then estimated, then closest; a typed
     * city before a nearby one; then
     * distance from ([nearLat], [nearLon]). Empty when nothing matches or [cancelled] says a
     * newer keystroke has superseded this one. A source that fails to read is counted in
     * [stats] and skipped; this never throws.
     */
    fun suggest(
        text: String,
        nearLat: Double?,
        nearLon: Double?,
        limit: Int = 8,
        cancelled: () -> Boolean = { false },
    ): List<Suggestion> {
        val started = System.nanoTime()
        val near = if (nearLat != null && nearLon != null && nearLat.isFinite() && nearLon.isFinite()) Point(nearLat, nearLon) else null
        val run = Run(near, cancelled)
        try {
            val readings = AddressQuery.parse(text)
            if (readings.isEmpty() || limit <= 0) {
                stats.finished(emptyList(), 0)
                return emptyList()
            }
            val files = try { sources() } catch (e: Exception) { stats.failed(); emptyList() }
            for (source in files) {
                run.check()
                // OsmAnd names US files "Us_texas_austin_northamerica.obf", "Us_new-york_northamerica.obf".
                val key = source.name.lowercase().replace('-', '_')
                val mine = readings.filter { it.state == null || key.startsWith("us_${it.state}_") }
                if (mine.isEmpty()) continue
                try {
                    searchSource(source, mine, run)
                } catch (e: Cancelled) {
                    throw e
                } catch (e: IOException) {
                    stats.failed()
                } catch (e: RuntimeException) {
                    // A reader bug on one file must not take the search box down; counted like a failed read.
                    stats.failed()
                }
            }
            val wantPois = near != null && readings.all { it.houseNumber == null } && run.found.values.none { it.isStreetOrBetter }
            if (wantPois) searchPois(files, readings.first(), limit, run)
            val out = run.found.values.sortedWith(ORDER).take(limit).map { it.suggestion }
            stats.finished(out, (System.nanoTime() - started) / 1_000_000)
            return out
        } catch (e: Cancelled) {
            stats.cancelledQuery()
            return emptyList()
        }
    }

    /**
     * Reads ahead what the first keystrokes will need: each file's cities and the streets of
     * the cities nearest ([nearLat], [nearLon]), with their names folded. For when the search
     * screen opens; blocking file I/O like [suggest], and like it never throws.
     */
    fun warm(nearLat: Double?, nearLon: Double?, cancelled: () -> Boolean = { false }) {
        val files = try { sources() } catch (e: Exception) { stats.failed(); return }
        for (source in files) {
            if (cancelled()) return
            try {
                val index = cityIndex(source)
                val origin = if (nearLat != null && nearLon != null) Point(nearLat, nearLon)
                    else source.bounds?.let { Point((it.south + it.north) / 2, (it.west + it.east) / 2) } ?: continue
                for (c in nearestPlaces(index, origin)) {
                    if (cancelled()) return
                    streetIndex(source, c)
                }
            } catch (e: IOException) {
                stats.failed()
            } catch (e: RuntimeException) {
                stats.failed()
            }
        }
    }

    // ---- one source ------------------------------------------------------------------------

    private fun searchSource(source: ObfSource, readings: List<AddressQuery>, run: Run) {
        val index = cityIndex(source)
        run.check()
        val origin = run.near ?: source.bounds?.let { Point((it.south + it.north) / 2, (it.west + it.east) / 2) }
        for (q in readings) {
            run.check()
            when {
                q.street.isEmpty() && q.houseNumber == null && q.city != null -> suggestCities(source, index, q.city, origin, run)
                q.street.isEmpty() && q.houseNumber == null && q.zip != null -> suggestCities(source, index, q.zip, origin, run)
                q.street.isNotEmpty() -> searchStreets(source, index, q, origin, run)
            }
        }
    }

    /** CITY and POSTCODE suggestions for a city part on its own ("Austin", "Au", "78701", "787"). */
    private fun suggestCities(source: ObfSource, index: CityIndex, typed: String, origin: Point?, run: Run) {
        for ((city, level) in matchCities(index, typed, origin, MAX_CITIES_SHOWN)) {
            val postcode = city.type == CityType.POSTCODE
            val detail = when (city.type) {
                CityType.CITY -> "city"
                CityType.VILLAGE -> "village"
                CityType.POSTCODE -> "ZIP code"
            }
            val streetLevel = if (level == CITY_FULL) LEVEL_EXACT else LEVEL_PREFIX
            // A postcode's own point in the file can be far from it (78701, downtown Austin, sits
            // 8 km north); the middle of its streets is where it is.
            val at = if (postcode) postcodeMiddle(source, city, run) else Point(city.latitude, city.longitude)
            run.add(
                Suggestion(city.name, detail, at.lat, at.lon,
                    if (postcode) SuggestionKind.POSTCODE else SuggestionKind.CITY, source.name),
                RANK_STREET, streetLevel, level, 0, run.distance(at.lat, at.lon), isStreetOrBetter = false,
            )
        }
    }

    /** The median of a postcode's street positions; its own point when it has no streets. */
    private fun postcodeMiddle(source: ObfSource, city: ObfCity, run: Run): Point {
        run.check()
        val streets = streetIndex(source, city).list
        run.check()
        if (streets.isEmpty()) return Point(city.latitude, city.longitude)
        val lats = streets.map { it.latitude }.sorted()
        val lons = streets.map { it.longitude }.sorted()
        return Point(lats[lats.size / 2], lons[lons.size / 2])
    }

    private fun searchStreets(source: ObfSource, index: CityIndex, q: AddressQuery, origin: Point?, run: Run) {
        // Candidate cities, each with how well it matched what was typed.
        val candidates = LinkedHashMap<ObfCity, Int>()
        if (q.city != null) {
            for ((c, level) in matchCities(index, q.city, origin, MAX_CITIES_SEARCHED)) candidates.merge(c, level, ::max)
        }
        if (q.zip != null) {
            for (c in index.postcodes[q.zip].orEmpty()) candidates.merge(c, CITY_FULL, ::max)
        }
        if (q.city == null && q.zip == null && origin != null) {
            nearestPlaces(index, origin).forEach { candidates.merge(it, CITY_NEARBY, ::max) }
        }
        if (candidates.isEmpty()) return
        val qn = QueryName(q)
        if (qn.isBlank) return

        // Matching streets, one logical street per (city, normalised name): split stretches are one.
        val groups = ArrayList<StreetGroup>()
        for ((city, cityLevel) in candidates) {
            run.check()
            val streets = streetIndex(source, city)
            run.check()
            val byName = LinkedHashMap<String, StreetGroup>()
            for (s in streets.names) {
                val level = qn.level(s)
                if (level == 0) continue
                val g = byName.getOrPut(s.norm) { StreetGroup(city, cityLevel, s.norm, s.base) }
                g.level = max(g.level, level)
                g.segments.add(s.street)
            }
            groups.addAll(byName.values)
        }
        if (groups.isEmpty()) return
        groups.sortWith(compareByDescending<StreetGroup> { it.level }.thenByDescending { it.cityLevel }
            .thenBy { g -> g.segments.minOf { run.distance(it.latitude, it.longitude) } })

        val number = q.houseNumber
        for (g in groups.take(if (number == null) MAX_STREETS_SHOWN else MAX_STREETS_NUMBERED)) {
            run.check()
            if (number == null) {
                val at = nearestSegment(g, run)
                run.add(
                    Suggestion("${g.name}, ${g.city.name}", area(at), at.latitude, at.longitude, SuggestionKind.STREET, source.name),
                    RANK_STREET, g.level, g.cityLevel, 0, run.distance(at.latitude, at.longitude), isStreetOrBetter = true,
                )
            } else {
                numberOn(source, g, number, run)
            }
        }
    }

    /** Exact, then an interpolation range holding the number, then the nearest below and above. */
    private fun numberOn(source: ObfSource, g: StreetGroup, n: HouseNumber, run: Run) {
        val name = g.name
        val city = g.city.name
        val all = ArrayList<ObfBuilding>()
        for (s in g.segments) {
            run.check()
            all.addAll(run.buildings.getOrPut(s) { source.buildings(s) })
        }
        run.check()
        fun add(label: String, detail: String, lat: Double, lon: Double, kind: SuggestionKind, rank: Int, gap: Int) =
            run.add(Suggestion(label, detail, lat, lon, kind, source.name), rank, g.level, g.cityLevel, gap,
                run.distance(lat, lon), isStreetOrBetter = true)

        if (all.isEmpty()) {
            val at = nearestSegment(g, run)
            add("$name, $city (no house numbers)", area(at), at.latitude, at.longitude, SuggestionKind.STREET, RANK_STREET, 0)
            return
        }
        // 1. An exact building.
        var exact = false
        for (b in all) {
            if (b.interpolation != Interpolation.NONE) continue
            val hn = HouseNumber.parse(b.number) ?: continue
            if (!hn.sameAs(n)) continue
            add("${n.text} $name, $city", b.postcode ?: "", b.latitude, b.longitude, SuggestionKind.EXACT, RANK_EXACT, 0)
            exact = true
        }
        if (exact) return
        // 2. A range that holds it.
        val ranges = all.mapNotNull(::rangeOf)
        var estimated = false
        for (r in ranges) {
            val f = r.fractionOf(n) ?: continue
            add("${n.text} $name (estimated)", city, r.lat(f), r.lon(f), SuggestionKind.INTERPOLATED, RANK_INTERPOLATED, 0)
            estimated = true
        }
        if (estimated) return
        // 3. The nearest numbers below and above, same side of the street (odd or even) first.
        val known = ArrayList<Known>()
        for (b in all) {
            if (b.interpolation == Interpolation.NONE) {
                HouseNumber.parse(b.number)?.let { known.add(Known(it, b.latitude, b.longitude, false)) }
            }
        }
        for (r in ranges) {
            r.nearestBelow(n)?.let { known.add(it) }
            r.nearestAbove(n)?.let { known.add(it) }
        }
        val below = known.filter { it.number.compareTo(n) < 0 }
        val above = known.filter { it.number.compareTo(n) > 0 }
        fun sameSide(k: Known) = k.number.value % 2 == n.value % 2
        val bestBelow = (below.filter(::sameSide).ifEmpty { below }).maxWithOrNull { a, b -> a.number.compareTo(b.number) }
        val bestAbove = (above.filter(::sameSide).ifEmpty { above }).minWithOrNull { a, b -> a.number.compareTo(b.number) }
        for ((k, word) in listOf(bestBelow to "below", bestAbove to "above")) {
            if (k == null) continue
            val notes = buildList {
                add(word)
                if (!sameSide(k)) add("other side")
                if (k.estimated) add("estimated")
            }.joinToString(", ")
            add("No ${n.text} on $name — ${k.number.text} $name", "$city · $notes", k.lat, k.lon,
                SuggestionKind.CLOSEST, RANK_CLOSEST, abs(k.number.value - n.value))
        }
    }

    /** OsmAnd's neighbourhood for one stretch of a split street ("Downtown"), or "". */
    private fun area(s: ObfStreet): String = PAREN.find(s.name)?.groupValues?.get(1)?.trim() ?: ""

    private fun nearestSegment(g: StreetGroup, run: Run): ObfStreet =
        if (run.near == null) g.segments.first() else g.segments.minBy { run.distance(it.latitude, it.longitude) }

    // ---- POIs ------------------------------------------------------------------------------

    private fun searchPois(files: List<ObfSource>, q: AddressQuery, limit: Int, run: Run) {
        val near = run.near ?: return
        val words = q.nameText.split(' ').filter { it.isNotEmpty() }
        // Digits alone are a ZIP or a house number being typed, never a place's name.
        if (words.isEmpty() || q.nameText.length < MIN_POI_CHARS || q.nameText.all { it.isDigit() || it == ' ' }) return
        val dLat = POI_RADIUS_M / M_PER_DEG
        val dLon = dLat / max(0.01, cos(Math.toRadians(near.lat)))
        val box = GeoBox(near.lat - dLat, near.lon - dLon, near.lat + dLat, near.lon + dLon)
        val inBox = files.filter { f -> f.bounds.let { it == null || it.intersects(box) } }
        fun ask(kinds: Set<PoiKind>, perKind: Int, query: String?): List<Pair<ObfSource, ObfPoi>> {
            val out = ArrayList<Pair<ObfSource, ObfPoi>>()
            for (f in inBox) {
                run.check()
                try {
                    f.pois(box, kinds, perKind, near.lat, near.lon, query).forEach { out.add(f to it) }
                } catch (e: IOException) {
                    stats.failed()
                } catch (e: RuntimeException) {
                    stats.failed()
                }
            }
            return out
        }
        // Each space-separated word of the name, brand and English name, punctuation dropped.
        fun tokens(p: ObfPoi): List<String> =
            listOf(p.name, p.brand ?: "", p.nameEn ?: "").flatMap { it.split(' ') }.map(::compact).filter { it.isNotEmpty() }
        fun holds(p: ObfPoi, word: String): Boolean = compact(word).let { w -> tokens(p).any { it.startsWith(w) } }
        val hits = LinkedHashMap<Long, Pair<ObfSource, ObfPoi>>()
        ask(emptySet(), limit, q.nameText).forEach { hits[it.second.id] = it }
        // Places, not entries: one stop is often a platform, a stop position and a bench.
        val places = hits.values.map { (_, p) -> "${p.name.lowercase()}|${Math.round(p.latitude * 1000)}|${Math.round(p.longitude * 1000)}" }.toSet()
        if (places.size < limit) {
            // The reader matches the text as typed: "HEB" is "H-E-B" in the file, and "HEB Lamar"
            // has a word the name hasn't. So also the first word alone, and the three buttons'
            // kinds with each name word compared without punctuation.
            if (words.size > 1) ask(emptySet(), limit, words[0]).forEach { hits.putIfAbsent(it.second.id, it) }
            ask(PoiKind.values().toSet(), POI_SCAN_PER_KIND, null)
                .filter { holds(it.second, words[0]) }
                .forEach { hits.putIfAbsent(it.second.id, it) }
        }
        // More typed words in the name first; a place of the buttons' kinds (a store, not its bus stop) next.
        fun score(p: ObfPoi): Int = 2 * words.count { holds(p, it) } + if (p.kind != null) 1 else 0
        for ((f, p) in hits.values) {
            val label = p.name.ifEmpty { p.brand ?: p.nameEn ?: p.subcategory.replace('_', ' ') }
            run.add(
                Suggestion(label, p.subcategory.replace('_', ' '), p.latitude, p.longitude, SuggestionKind.POI, f.name),
                RANK_POI, score(p), 0, 0, run.distance(p.latitude, p.longitude), isStreetOrBetter = false,
            )
        }
    }

    // ---- name indexes, cached per file and per city -----------------------------------------

    /** A file's cities with their normalised names; rebuilt when the reader hands back a new list. */
    private class CityIndex(val list: List<ObfCity>) {
        val entries = list.map { NamedCity(it) }
        /** Cities and villages, not postcodes: what "near the phone" means. */
        val places = entries.filter { it.city.type != CityType.POSTCODE }
        val postcodes: Map<String, List<ObfCity>> =
            list.filter { it.type == CityType.POSTCODE }.groupBy { it.name.trim().take(5) }
    }

    private class NamedCity(val city: ObfCity) {
        val norm = AddressText.normalize(city.name)
        val raw = AddressText.words(AddressText.fold(city.name)).joinToString(" ")
        val normEn = city.nameEn?.let(AddressText::normalize)
    }

    private class StreetIndex(val list: List<ObfStreet>) {
        val names = list.map { StreetName(it) }
    }

    /** A street's name in every form the matcher compares. */
    private class StreetName(val street: ObfStreet) {
        /** The name without OsmAnd's "(Downtown)": the stretches of one street share it. */
        val base = baseName(street.name)
        val norm = AddressText.normalize(base)
        val forms: List<NameForms> = buildList {
            add(NameForms(base))
            street.nameEn?.takeIf { it.isNotBlank() && it != street.name }?.let { add(NameForms(baseName(it))) }
        }
    }

    private class NameForms(name: String) {
        val rawWords: List<String> = AddressText.words(AddressText.fold(name))
        val words: List<String> = AddressText.canonical(rawWords)
        val norm: String = words.joinToString(" ")
        val dirs = words.filter(AddressText::isDirection).toSet()
        /** Without direction words: "s congress ave" is "congress ave". */
        val core = words.filterNot(AddressText::isDirection).joinToString(" ")
        /** [core] without its last type word: "congress". */
        val coreNoType = words.filterNot(AddressText::isDirection).let { w ->
            if (w.size > 1 && AddressText.isType(w.last())) w.dropLast(1) else w
        }.joinToString(" ")
        /** As written but folded, direction words dropped, for a word half typed ("congress aven"). */
        val rawCore = rawWords.filterNot { AddressText.isDirection(AddressText.canonical(listOf(it)).first()) }.joinToString(" ")
    }

    /** The typed street in the same forms. */
    private class QueryName(q: AddressQuery) {
        val words: List<String> = q.street.split(' ').filter { it.isNotEmpty() }
        val norm: String = words.joinToString(" ")
        val dirs = words.filter(AddressText::isDirection).toSet()
        val core = words.filterNot(AddressText::isDirection).joinToString(" ")
        val rawCore = q.streetRaw.split(' ').filter { it.isNotEmpty() }
            .filterNot { AddressText.isDirection(AddressText.canonical(listOf(it)).first()) }.joinToString(" ")
        val isBlank = core.isEmpty() && norm.isEmpty()

        fun level(s: StreetName): Int = s.forms.maxOf(::level)

        private fun level(f: NameForms): Int {
            if (f.norm == norm) return LEVEL_EXACT
            if (core.isEmpty() || !f.dirs.containsAll(dirs)) return 0
            if (f.core == core || f.coreNoType == core) return LEVEL_FULL
            if (f.core.startsWith(core) || f.norm.startsWith(norm) || (rawCore.isNotEmpty() && f.rawCore.startsWith(rawCore))) {
                return LEVEL_PREFIX
            }
            return 0
        }
    }

    private class StreetGroup(val city: ObfCity, val cityLevel: Int, val norm: String, val name: String) {
        var level = 0
        val segments = ArrayList<ObfStreet>()
    }

    private val cityIndexes: MutableMap<ObfSource, CityIndex> = Collections.synchronizedMap(WeakHashMap())
    private val streetIndexes: MutableMap<ObfCity, StreetIndex> = Collections.synchronizedMap(
        object : LinkedHashMap<ObfCity, StreetIndex>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ObfCity, StreetIndex>?) = size > MAX_CACHED_CITIES
        },
    )

    private fun cityIndex(source: ObfSource): CityIndex {
        val list = source.cities()
        cityIndexes[source]?.let { if (it.list === list) return it }
        return CityIndex(list).also { cityIndexes[source] = it }
    }

    private fun streetIndex(source: ObfSource, city: ObfCity): StreetIndex {
        val list = source.streets(city)
        streetIndexes[city]?.let { if (it.list === list) return it }
        return StreetIndex(list).also { streetIndexes[city] = it }
    }

    private fun nearestPlaces(index: CityIndex, origin: Point): List<ObfCity> =
        index.places.sortedBy { PoiRules.metres(origin.lat, origin.lon, it.city.latitude, it.city.longitude) }
            .take(MAX_CITIES_NEARBY).map { it.city }

    /** Cities whose name is, or starts with, [typed]; ZIP codes only when [typed] is digits. Best first. */
    private fun matchCities(index: CityIndex, typed: String, origin: Point?, max: Int): List<Pair<ObfCity, Int>> {
        if (typed.isEmpty()) return emptyList()
        val digits = typed.all { it.isDigit() || it == ' ' }
        val out = ArrayList<Pair<ObfCity, Int>>()
        for (c in index.entries) {
            if ((c.city.type == CityType.POSTCODE) != digits) continue
            val level = when {
                c.norm == typed || c.normEn == typed -> CITY_FULL
                c.norm.startsWith(typed) || c.raw.startsWith(typed) || c.normEn?.startsWith(typed) == true -> CITY_PREFIX
                else -> continue
            }
            out.add(c.city to level)
        }
        fun d(c: ObfCity) = if (origin == null) 0.0 else PoiRules.metres(origin.lat, origin.lon, c.latitude, c.longitude)
        return out.sortedWith(compareByDescending<Pair<ObfCity, Int>> { it.second }
            .thenBy { it.first.type.ordinal }
            .thenBy { d(it.first) })
            .take(max)
    }

    // ---- house number ranges ------------------------------------------------------------------

    private class Known(val number: HouseNumber, val lat: Double, val lon: Double, val estimated: Boolean)

    /** An interpolation line from [a] at (lat1, lon1) to [b] at (lat2, lon2). */
    private class Range(
        val a: HouseNumber, val b: HouseNumber, val kind: Interpolation, val step: Int,
        val lat1: Double, val lon1: Double, val lat2: Double, val lon2: Double,
    ) {
        val lo = min(a.value, b.value)
        val hi = max(a.value, b.value)
        /** Numbers on it are those ≡ [residue] mod [modulus] in lo..hi. */
        val modulus = when (kind) {
            Interpolation.EVEN, Interpolation.ODD -> 2
            Interpolation.STEP -> max(1, step)
            else -> 1
        }
        val residue = when (kind) {
            Interpolation.EVEN -> 0
            Interpolation.ODD -> 1
            Interpolation.STEP -> Math.floorMod(a.value, modulus)
            else -> 0
        }
        /** Letters on one number: "12A" to "12F". */
        val alphabetic = kind == Interpolation.ALPHABETIC && a.value == b.value &&
            a.suffix.length == 1 && b.suffix.length == 1

        fun lat(f: Double) = lat1 + f * (lat2 - lat1)
        fun lon(f: Double) = lon1 + f * (lon2 - lon1)

        private fun fractionOfValue(v: Int): Double = if (b.value == a.value) 0.0 else (v - a.value).toDouble() / (b.value - a.value)

        /** Where along the line [n] lies, or null when the range doesn't hold it. */
        fun fractionOf(n: HouseNumber): Double? {
            if (alphabetic) {
                if (n.value != a.value || n.suffix.length != 1) return null
                val x = n.suffix[0]; val p = a.suffix[0]; val q = b.suffix[0]
                if (x < minOf(p, q) || x > maxOf(p, q)) return null
                return if (p == q) 0.0 else (x - p).toDouble() / (q - p)
            }
            if (n.value < lo || n.value > hi) return null
            if (Math.floorMod(n.value, modulus) != residue) return null
            return fractionOfValue(n.value)
        }

        fun nearestBelow(n: HouseNumber): Known? {
            if (alphabetic) return listOf(a to 0.0, b to 1.0).filter { it.first.compareTo(n) < 0 }
                .maxWithOrNull { x, y -> x.first.compareTo(y.first) }?.let { Known(it.first, lat(it.second), lon(it.second), false) }
            var v = min(hi, if (n.suffix.isEmpty()) n.value - 1 else n.value)
            v -= Math.floorMod(v - residue, modulus)
            return if (v < lo) null else known(v)
        }

        fun nearestAbove(n: HouseNumber): Known? {
            if (alphabetic) return listOf(a to 0.0, b to 1.0).filter { it.first.compareTo(n) > 0 }
                .minWithOrNull { x, y -> x.first.compareTo(y.first) }?.let { Known(it.first, lat(it.second), lon(it.second), false) }
            var v = max(lo, n.value + 1)
            v += Math.floorMod(residue - v, modulus)
            return if (v > hi) null else known(v)
        }

        private fun known(v: Int): Known {
            val f = fractionOfValue(v)
            val endpoint = v == a.value || v == b.value
            return Known(HouseNumber(v, ""), lat(f), lon(f), estimated = !endpoint)
        }
    }

    private fun rangeOf(b: ObfBuilding): Range? {
        if (b.interpolation == Interpolation.NONE) return null
        val n2 = b.number2 ?: return null
        val lat2 = b.latitude2 ?: return null
        val lon2 = b.longitude2 ?: return null
        val a = HouseNumber.parse(b.number) ?: return null
        val z = HouseNumber.parse(n2) ?: return null
        return Range(a, z, b.interpolation, b.step, b.latitude, b.longitude, lat2, lon2)
    }

    // ---- one query's state ------------------------------------------------------------------

    private class Point(val lat: Double, val lon: Double)

    private class Candidate(
        val suggestion: Suggestion,
        val kindRank: Int,
        val streetLevel: Int,
        val cityLevel: Int,
        val gap: Int,
        val distance: Double,
        val isStreetOrBetter: Boolean,
    )

    private class Cancelled : RuntimeException(null, null, false, false)

    private class Run(val near: Point?, private val cancelled: () -> Boolean) {
        val found = LinkedHashMap<String, Candidate>()
        val buildings = IdentityHashMap<ObfStreet, List<ObfBuilding>>()

        fun check() {
            if (cancelled()) throw Cancelled()
        }

        fun distance(lat: Double, lon: Double): Double =
            if (near == null) 0.0 else PoiRules.metres(near.lat, near.lon, lat, lon)

        fun add(s: Suggestion, kindRank: Int, streetLevel: Int, cityLevel: Int, gap: Int, distance: Double, isStreetOrBetter: Boolean) {
            val c = Candidate(s, kindRank, streetLevel, cityLevel, gap, distance, isStreetOrBetter)
            // One suggestion per label and place (~100 m): the same answer reached by two readings or two files.
            val key = "${s.kind}|${s.label.lowercase()}|${Math.round(s.latitude * 1000)}|${Math.round(s.longitude * 1000)}"
            val old = found[key]
            if (old == null || ORDER.compare(c, old) < 0) found[key] = c
        }
    }

    companion object {
        private const val RANK_EXACT = 0
        private const val RANK_INTERPOLATED = 1
        private const val RANK_CLOSEST = 2
        private const val RANK_STREET = 3
        private const val RANK_POI = 4

        private const val LEVEL_EXACT = 3
        private const val LEVEL_FULL = 2
        private const val LEVEL_PREFIX = 1

        private const val CITY_FULL = 3
        private const val CITY_PREFIX = 2
        private const val CITY_NEARBY = 1

        /** Cities a typed city part is looked for in ("Au" is Austin, Aubrey, Aurora...). */
        private const val MAX_CITIES_SEARCHED = 6
        private const val MAX_CITIES_SHOWN = 8
        /** Cities nearest the phone searched when no city was typed. */
        private const val MAX_CITIES_NEARBY = 6
        private const val MAX_STREETS_SHOWN = 12
        /** Streets whose house numbers are read for one reading. */
        private const val MAX_STREETS_NUMBERED = 8
        private const val MAX_CACHED_CITIES = 256

        private const val POI_RADIUS_M = 15_000.0
        private const val MIN_POI_CHARS = 2
        /** POIs per button kind scanned near the phone when the reader's own name match finds none. */
        private const val POI_SCAN_PER_KIND = 1000

        /** Letters and digits only, folded: "H-E-B" and "heb" are one. */
        private fun compact(text: String): String = AddressText.fold(text).filter { it.isLetterOrDigit() }
        private const val M_PER_DEG = 6_371_000.0 * Math.PI / 180.0

        /**
         * Addresses before places; then the street match (as typed, full, prefix) before the
         * kind, so a closest real number on the street typed beats an estimate on a street
         * that only shares its name (changed from the first design, for "1101 Congress Ave");
         * within one street match exact, estimated, closest, then streets and cities.
         */
        private val ORDER: Comparator<Candidate> = compareBy<Candidate> { if (it.kindRank == RANK_POI) 1 else 0 }
            .thenByDescending { it.streetLevel }
            .thenBy { it.kindRank }
            .thenByDescending { it.cityLevel }
            .thenBy { it.gap }
            .thenBy { it.distance }
            .thenBy { it.suggestion.label }

        /** A trailing "(Downtown)": OsmAnd's tag for one of several stretches with one name in one city. */
        private val PAREN = Regex("""\s*\(([^()]*)\)\s*$""")

        private fun baseName(name: String): String = PAREN.replace(name, "").ifBlank { name }
    }
}
