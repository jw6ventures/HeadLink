package com.jw6ventures.headlink

import java.util.Locale
import kotlin.math.cos

/**
 * The POI half of [ObfFile] (plan §3.2), after OsmAnd's `BinaryMapPoiReaderAdapter`.
 *
 * - **Tables, once per file:** each section's category table (a key and its subcategory
 *   keys, in file order) and its subtype table (the additional tags: `brand`,
 *   `opening_hours`, …). A POI's category id is `(subcategory << 7) | category`, indexes
 *   into those tables, so ids mean nothing across files and are never hard-coded.
 * - **The box tree:** each `OsmAndPoiBox` is a tile, its zoom a delta from its parent's and
 *   its left/top `x = dx + (px << (zoom − pzoom))`. A box outside the query, or whose
 *   category list holds none of the wanted ids, is skipped with everything under it. A
 *   box's `shiftToData` is a big-endian offset from the section's start to its data.
 * - **Atoms:** `x31 = (dx + (tileX << (24 − zoom))) << 7` from the data box's tile, then,
 *   when `precisionXY` is set, two more bits per axis to zoom 26 (OsmAnd's
 *   `calculateFinalXYFromBaseAndPrecisionXY`).
 */
internal class ObfPoiReader(
    private val bytes: ObfBytes,
    private val file: String,
    private val sections: List<PoiSectionHeader>,
) {
    private class Subtype(val name: String, val text: Boolean, val values: List<String>)

    private class Tables(
        val categories: List<String>,
        val subcategories: List<List<String>>,
        val subtypes: List<Subtype>,
    ) {
        /** Kind of each id, by category index then subcategory index; null for other places. */
        val kinds: Array<Array<PoiKind?>> = Array(categories.size) { c ->
            val key = categories[c].lowercase(Locale.ROOT)
            Array(subcategories[c].size) { s -> kindOf(key, subcategories[c][s].lowercase(Locale.ROOT)) }
        }

        fun category(id: Int): Pair<Int, Int>? {
            val c = id and CATEGORY_MASK
            val s = id ushr CATEGORY_SHIFT
            return if (c < categories.size && s < subcategories[c].size) c to s else null
        }

        fun kind(id: Int): PoiKind? = category(id)?.let { (c, s) -> kinds[c][s] }

        /** OsmAnd's `getSubtypeFromId`: a tag, and for a non-text tag its value. */
        fun subtype(id: Int): Pair<Subtype, String?>? {
            val (tl, sl) = if (id % 2 == 0) ((id shr 1) and 0x1f) to (id ushr 6) else ((id shr 1) and 0x7fff) to (id ushr 16)
            val st = subtypes.getOrNull(tl) ?: return null
            return when {
                st.text -> st to null
                sl < st.values.size -> st to st.values[sl]
                else -> null
            }
        }
    }

    private val lock = Any()
    private val tables = HashMap<PoiSectionHeader, Tables>()

    private fun tables(section: PoiSectionHeader): Tables {
        synchronized(lock) { tables[section]?.let { return it } }
        val wire = ObfWire(bytes, 16384)
        wire.seek(section.offset)
        val old = wire.pushLimit(section.length)
        val categories = ArrayList<String>()
        val subcategories = ArrayList<MutableList<String>>()
        val subtypes = ArrayList<Subtype>()
        loop@ while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                3 -> {
                    val o = wire.pushLimit(wire.readLength(tag))
                    while (true) {
                        val t = wire.readTag()
                        if (t == 0) break
                        when (t ushr 3) {
                            1 -> { categories.add(wire.readString()); subcategories.add(ArrayList()) }
                            3 -> subcategories.last() += wire.readString()
                            else -> wire.skipField(t)
                        }
                    }
                    wire.popLimit(o)
                }
                5 -> {
                    val o = wire.pushLimit(wire.readLength(tag))
                    while (true) {
                        val t = wire.readTag()
                        if (t == 0) break
                        if (t ushr 3 == 4) {
                            val so = wire.pushLimit(wire.readLength(t))
                            var name = ""
                            var text = false
                            val values = ArrayList<String>()
                            while (true) {
                                val st = wire.readTag()
                                if (st == 0) break
                                when (st ushr 3) {
                                    1 -> name = wire.readString()
                                    3 -> text = wire.readVarint64() != 0L
                                    8 -> values += wire.readString()
                                    else -> wire.skipField(st)
                                }
                            }
                            wire.popLimit(so)
                            subtypes += Subtype(name, text, values)
                        } else {
                            wire.skipField(t)
                        }
                    }
                    wire.popLimit(o)
                }
                6, 9 -> break@loop // the boxes: the tables come before them
                else -> wire.skipField(tag)
            }
        }
        wire.popLimit(old)
        val read = Tables(categories, subcategories, subtypes)
        synchronized(lock) { return tables.getOrPut(section) { read } }
    }

    fun categoryKeys(): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (section in sections) {
            val t = tables(section)
            t.categories.forEachIndexed { i, key -> out.getOrPut(key) { ArrayList() }.addAll(t.subcategories[i]) }
        }
        return out
    }

    fun subtypeNames(): List<String> = sections.flatMap { s -> tables(s).subtypes.map { it.name } }.distinct()

    private class Hit(val poi: ObfPoi, val distance: Double)

    fun pois(
        box: GeoBox,
        kinds: Set<PoiKind>,
        limit: Int,
        centreLat: Double,
        centreLon: Double,
        nameQuery: String?,
    ): List<ObfPoi> {
        if (limit <= 0) return emptyList()
        val query = nameQuery?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        if (kinds.isEmpty() && query == null) return emptyList()
        val left = ObfTiles.x31(box.west)
        val right = ObfTiles.x31(box.east)
        val top = ObfTiles.y31(box.north)
        val bottom = ObfTiles.y31(box.south)
        val hits = ArrayList<Hit>()
        val cosLat = cos(Math.toRadians(centreLat))
        for (section in sections) {
            if (section.bounds != null && !section.bounds.intersects(box)) continue
            val t = tables(section)
            val wanted: (Int) -> Boolean = if (kinds.isEmpty()) { _ -> true } else { id -> t.kind(id) in kinds }
            val wire = ObfWire(bytes, 16384)
            val offsets = ArrayList<Long>()
            wire.seek(section.offset)
            val old = wire.pushLimit(section.length)
            loop@ while (true) {
                val tag = wire.readTag()
                if (tag == 0) break
                when (tag ushr 3) {
                    6 -> {
                        val o = wire.pushLimit(wire.readLength(tag))
                        readBox(wire, left, right, top, bottom, 0, 0, 0, wanted, offsets)
                        wire.seek(wire.limit)
                        wire.popLimit(o)
                    }
                    9 -> break@loop
                    else -> wire.skipField(tag)
                }
            }
            wire.popLimit(old)
            offsets.sort()
            for (offset in offsets) {
                wire.seek(section.offset + offset)
                val o = wire.pushLimit(wire.readBigLength())
                readData(wire, t, left, right, top, bottom, kinds, query) { poi ->
                    val dy = poi.latitude - centreLat
                    val dx = (poi.longitude - centreLon) * cosLat
                    hits += Hit(poi, dx * dx + dy * dy)
                }
                wire.popLimit(o)
            }
        }
        hits.sortBy { it.distance }
        // A place with several categories is stored once per category under one id: keep
        // the copy with the best kind (gas, food, shop, then none), at its first position.
        val best = HashMap<Long, Hit>()
        for (hit in hits) {
            if (hit.poi.id == 0L) continue
            val seen = best[hit.poi.id]
            if (seen == null || rank(hit.poi.kind) < rank(seen.poi.kind)) best[hit.poi.id] = hit
        }
        val perKind = HashMap<PoiKind?, Int>()
        val out = ArrayList<ObfPoi>()
        val taken = HashSet<Long>()
        for (hit in hits) {
            if (hit.poi.id != 0L && (best[hit.poi.id] !== hit || !taken.add(hit.poi.id))) continue
            val n = perKind[hit.poi.kind] ?: 0
            if (n >= limit) continue
            perKind[hit.poi.kind] = n + 1
            out += hit.poi
        }
        return out
    }

    private fun rank(kind: PoiKind?): Int = kind?.ordinal ?: PoiKind.entries.size

    private fun readBox(
        wire: ObfWire,
        left: Long, right: Long, top: Long, bottom: Long,
        px: Long, py: Long, pzoom: Int,
        wanted: (Int) -> Boolean,
        offsets: MutableList<Long>,
    ) {
        var zoom = pzoom
        var dx = 0L
        var x = -1L
        var y = -1L
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) return
            when (tag ushr 3) {
                1 -> zoom = pzoom + wire.readVarint32()
                2 -> dx = wire.readSInt32().toLong()
                3 -> {
                    val dy = wire.readSInt32().toLong()
                    x = dx + (px shl (zoom - pzoom))
                    y = dy + (py shl (zoom - pzoom))
                    val shift = 31 - zoom
                    val xl = x shl shift
                    val xr = ((x + 1) shl shift) - 1
                    val yt = y shl shift
                    val yb = ((y + 1) shl shift) - 1
                    if (left > xr || xl > right || bottom < yt || yb < top) { wire.seek(wire.limit); return }
                }
                4 -> {
                    val o = wire.pushLimit(wire.readLength(tag))
                    var any = false
                    while (true) {
                        val t = wire.readTag()
                        if (t == 0) break
                        if (t ushr 3 == 3) {
                            if (t and 7 == ObfWire.WIRE_LENGTH) {
                                val po = wire.pushLimit(wire.readVarint64())
                                while (wire.remaining > 0) if (wanted(wire.readVarint32())) any = true
                                wire.popLimit(po)
                            } else if (wanted(wire.readVarint32())) {
                                any = true
                            }
                        } else {
                            wire.skipField(t)
                        }
                    }
                    wire.popLimit(o)
                    if (!any) { wire.seek(wire.limit); return }
                }
                10 -> {
                    val o = wire.pushLimit(wire.readLength(tag))
                    readBox(wire, left, right, top, bottom, x, y, zoom, wanted, offsets)
                    wire.seek(wire.limit)
                    wire.popLimit(o)
                }
                14 -> offsets += wire.readBigLength()
                else -> wire.skipField(tag)
            }
        }
    }

    private fun readData(
        wire: ObfWire,
        t: Tables,
        left: Long, right: Long, top: Long, bottom: Long,
        kinds: Set<PoiKind>,
        query: String?,
        found: (ObfPoi) -> Unit,
    ) {
        var zoom = 0
        var tileX = 0L
        var tileY = 0L
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) return
            when (tag ushr 3) {
                1 -> zoom = wire.readVarint32()
                2 -> tileX = wire.readVarint64() and 0xffffffffL
                3 -> tileY = wire.readVarint64() and 0xffffffffL
                5 -> {
                    val o = wire.pushLimit(wire.readLength(tag))
                    readAtom(wire, t, tileX, tileY, zoom, left, right, top, bottom, kinds, query)?.let(found)
                    wire.seek(wire.limit)
                    wire.popLimit(o)
                }
                else -> wire.skipField(tag)
            }
        }
    }

    private fun readAtom(
        wire: ObfWire,
        t: Tables,
        tileX: Long, tileY: Long, zoom: Int,
        left: Long, right: Long, top: Long, bottom: Long,
        kinds: Set<PoiKind>,
        query: String?,
    ): ObfPoi? {
        var x = 0L
        var y = 0L
        var precision = 0
        var chosen = -1
        var chosenKind: PoiKind? = null
        var name = ""
        var nameEn: String? = null
        var id = 0L
        var tags: MutableMap<String, String>? = null
        // Field 14's tags, in order, for field 15's values to pair with; null holds the slot
        // of a tag that is not text or not in the table, so the values after it still pair
        // with their own tags, as in OsmAnd's reader.
        val pendingText = ArrayDeque<String?>()
        while (true) {
            val tag = wire.readTag()
            val field = tag ushr 3
            // Categories come before every other field but the position, as in OsmAnd's reader.
            if (chosen < 0 && (tag == 0 || field > 4)) return null
            if (tag == 0) break
            when (field) {
                2 -> x = (wire.readSInt32() + (tileX shl (24 - zoom))) shl 7
                3 -> {
                    y = (wire.readSInt32() + (tileY shl (24 - zoom))) shl 7
                    if (x < left || x > right || y < top || y > bottom) return null
                }
                4 -> {
                    val cat = wire.readVarint32()
                    val kind = t.kind(cat)
                    val accept = if (kinds.isEmpty()) t.category(cat) != null else kind in kinds
                    if (accept && chosen < 0) { chosen = cat; chosenKind = kind }
                }
                5 -> {
                    val st = t.subtype(wire.readVarint32())
                    if (st != null && st.second != null) (tags ?: HashMap<String, String>().also { tags = it })[st.first.name] = st.second!!
                }
                6 -> name = wire.readString()
                7 -> nameEn = wire.readString()
                8 -> id = wire.readVarint64()
                14 -> pendingText.addLast(t.subtype(wire.readVarint32())?.takeIf { it.first.text }?.first?.name)
                15 -> {
                    val value = wire.readString()
                    val key = if (pendingText.isEmpty()) null else pendingText.removeFirst()
                    key?.let { (tags ?: HashMap<String, String>().also { tags = it })[it] = value }
                }
                16 -> precision = wire.readVarint32()
                else -> wire.skipField(tag)
            }
        }
        if (precision != 0) {
            var fx = x shr 7
            var fy = y shr 7
            var p = precision
            repeat(FINAL_ZOOM - BASE_ZOOM) {
                fy = fy * 2 + (p and 1)
                fx = fx * 2 + ((p and 2) shr 1)
                p = p shr 2
            }
            x = fx shl (31 - FINAL_ZOOM)
            y = fy shl (31 - FINAL_ZOOM)
        }
        // Since at least June 2026 the names are text tags like any other, not fields 6 and 7.
        tags?.get("name")?.let { if (name.isEmpty()) name = unzip(it) }
        tags?.get("name:en")?.let { if (nameEn.isNullOrEmpty()) nameEn = unzip(it) }
        val brand = tags?.get("brand")?.let(::unzip)
        if (query != null) {
            val match = name.lowercase(Locale.ROOT).contains(query) ||
                nameEn?.lowercase(Locale.ROOT)?.contains(query) == true ||
                brand?.lowercase(Locale.ROOT)?.contains(query) == true
            if (!match) return null
        }
        val (c, s) = t.category(chosen) ?: return null
        return ObfPoi(
            id = id,
            latitude = ObfTiles.latitude31(y),
            longitude = ObfTiles.longitude31(x),
            name = name,
            nameEn = nameEn?.takeIf { it.isNotEmpty() && it != name },
            category = t.categories[c],
            subcategory = t.subcategories[c][s],
            kind = chosenKind,
            brand = brand,
            openingHours = tags?.get("opening_hours")?.let(::unzip),
            file = file,
        )
    }

    companion object {
        private const val CATEGORY_SHIFT = 7
        private const val CATEGORY_MASK = (1 shl CATEGORY_SHIFT) - 1
        private const val BASE_ZOOM = 24
        private const val FINAL_ZOOM = 26

        private fun kindOf(category: String, subcategory: String) = ObfPoiKinds.kindOf(category, subcategory)
        private fun unzip(value: String) = ObfPoiKinds.unzip(value)
    }
}

/** The rules [ObfPoiReader] decodes by, public for the host tests. */
object ObfPoiKinds {
    private val FOOD = setOf("restaurant", "fast_food", "cafe", "food_court", "barbecue")

    /** Where OsmAnd's `poi_types.xml` and all three Texas files put `fuel`. */
    private val FUEL_CATEGORIES = setOf("transportation")

    /**
     * The three buttons (plan §6, §12 decision 3), by the file's own keys: `fuel` only (not
     * `charging_station`), four `sustenance` kinds, every `shop` subcategory.
     */
    fun kindOf(category: String, subcategory: String): PoiKind? = when {
        subcategory == "fuel" && category in FUEL_CATEGORIES -> PoiKind.GAS
        category == "sustenance" && subcategory in FOOD -> PoiKind.FOOD
        category == "shop" -> PoiKind.SHOP
        else -> null
    }

    /**
     * OsmAnd's `MapObject.unzipContent`: a long text value may be stored as " gz " and
     * gzip bytes, each byte as a char 160 above it. Anything else is returned as it is.
     */
    fun unzip(value: String): String {
        if (!value.startsWith(" gz ")) return value
        val bytes = ByteArray(value.length - 4) { i -> (value[i + 4].code - 128 - 32).toByte() }
        return try {
            java.util.zip.GZIPInputStream(bytes.inputStream()).reader(Charsets.UTF_8).readText().trim()
        } catch (e: java.io.IOException) {
            value
        }
    }
}
