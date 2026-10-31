package com.jw6ventures.headlink

import java.io.IOException

/**
 * One road from OsmAnd's routing data: its tags, its points in degrees and the tags on
 * single points (a `motorway_junction` node and its `ref`, for one).
 *
 * [tags] holds both kinds of OsmAnd routing tag: the typed ones (`highway=motorway_link`)
 * and the text ones (`destination=Waco`, `junction:ref=23A`), which the file keeps apart
 * and this merges. The same for each point's tags in [pointTags].
 */
class ObfRoadWay(
    val tags: Map<String, String>,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    /** Tags on single points, by point index: only the points that carry any. */
    val pointTags: Map<Int, Map<String, String>>,
) {
    val highway: String? get() = tags["highway"]
}

/** Where one routing section is in the file; read when first asked. */
internal class RoutingSectionHeader(val offset: Long, val length: Long)

/**
 * The routing half of [ObfFile], after OsmAnd's `BinaryMapRouteReaderAdapter` at `b75433b`
 * and the vendored `OBF.proto` (`OsmAndRoutingIndex`). Read for exit numbers and sign text
 * only ([ExitSigns]): HeadLink routes with BRouter, whose data has neither.
 *
 * - **Rules, once per section:** each `RouteEncodingRule` is a tag and a value, numbered
 *   from 1 in file order unless it carries its own id. A road's `types` and a point's
 *   types are rule ids; its `stringNames` and `pointNames` pair a rule id (whose tag is the
 *   key) with an index into the block's string table (the value).
 * - **The box tree:** root boxes hold absolute zoom-31 bounds, children deltas from their
 *   parent's. Boxes outside the query are skipped with everything under them. A box's
 *   `shiftToData` is a big-endian offset from the start of that box's own body to a
 *   length-prefixed `RouteDataBlock`. The `basemapBoxes` (a coarse copy for long routes)
 *   are never read.
 * - **Points** are zoom-31 positions with the low 4 bits dropped: deltas from the data
 *   box's left and top shifted right by 4, each from the one before.
 *
 * Box headers and decoded blocks are cached under [lock]; reading is done outside it, each
 * query on its own [ObfWire].
 */
internal class ObfRouteReader(
    private val bytes: ObfBytes,
    private val sections: List<RoutingSectionHeader>,
) {
    private class Rule(val tag: String, val value: String)

    private class Box(
        val left: Long,
        val right: Long,
        val top: Long,
        val bottom: Long,
        /** Where the box's own message body starts, and its length. */
        val body: Long,
        val length: Long,
        /** 0 when the box holds no data of its own. */
        val shiftToData: Long,
        /** Child boxes' bodies and lengths, read on the way down. */
        val children: List<Pair<Long, Long>>,
    ) {
        fun intersects(l: Long, r: Long, t: Long, b: Long): Boolean = !(left > r || right < l || top > b || bottom < t)
    }

    private class Section(val rules: Array<Rule?>, val roots: List<Box>)

    private val lock = Any()
    private var loaded: List<Section>? = null
    private val boxes = HashMap<Long, Box>()
    private val blocks = object : LinkedHashMap<Long, List<ObfRoadWay>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<ObfRoadWay>>?): Boolean = size > MAX_BLOCKS
    }

    val hasRouting: Boolean get() = sections.isNotEmpty()

    /** Every road in the data boxes that meet [box]; roads in those boxes but outside [box] are included. */
    @Throws(IOException::class)
    fun ways(box: GeoBox): List<ObfRoadWay> {
        if (sections.isEmpty()) return emptyList()
        val left = ObfTiles.x31(box.west)
        val right = ObfTiles.x31(box.east)
        val top = ObfTiles.y31(box.north)
        val bottom = ObfTiles.y31(box.south)
        val wire = ObfWire(bytes)
        val out = ArrayList<ObfRoadWay>()
        for (section in sections(wire)) {
            val data = ArrayList<Box>()
            for (root in section.roots) collect(wire, root, left, right, top, bottom, data)
            for (b in data.sortedBy { it.body + it.shiftToData }) {
                out += block(wire, section, b)
            }
        }
        return out
    }

    private fun sections(wire: ObfWire): List<Section> {
        synchronized(lock) { loaded?.let { return it } }
        val read = sections.map { readSection(wire, it) }
        synchronized(lock) {
            loaded?.let { return it }
            loaded = read
            return read
        }
    }

    private fun readSection(wire: ObfWire, header: RoutingSectionHeader): Section {
        wire.seek(header.offset)
        val old = wire.pushLimit(header.length)
        val rules = ArrayList<Rule?>()
        val roots = ArrayList<Box>()
        var next = 1
        loop@ while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                2 -> {
                    val o = wire.pushLimit(length(wire, tag))
                    var ruleTag = ""
                    var value = ""
                    var id = next
                    while (true) {
                        val t = wire.readTag()
                        if (t == 0) break
                        when (t ushr 3) {
                            3 -> ruleTag = wire.readString()
                            5 -> value = wire.readString()
                            7 -> id = wire.readVarint32()
                            else -> wire.skipField(t)
                        }
                    }
                    wire.popLimit(o)
                    next++
                    if (id in 0 until MAX_RULES) {
                        while (rules.size <= id) rules += null
                        rules[id] = Rule(ruleTag, value)
                    }
                }
                3 -> {
                    val length = length(wire, tag)
                    val body = wire.position
                    roots += readBox(wire, body, length, null)
                    wire.seek(body + length)
                }
                5 -> break@loop // the data blocks: read by offset from the boxes
                else -> wire.skipField(tag) // 1 the name, 4 the basemap boxes
            }
        }
        wire.popLimit(old)
        return Section(rules.toTypedArray(), roots)
    }

    /** Reads the box whose body is at [body]: its bounds, its data offset, where its children are. */
    private fun readBox(wire: ObfWire, body: Long, length: Long, parent: Box?): Box {
        wire.seek(body)
        val old = wire.pushLimit(length)
        var left = 0L; var right = 0L; var top = 0L; var bottom = 0L
        var shift = 0L
        val children = ArrayList<Pair<Long, Long>>()
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> left = wire.readSInt32().toLong()
                2 -> right = wire.readSInt32().toLong()
                3 -> top = wire.readSInt32().toLong()
                4 -> bottom = wire.readSInt32().toLong()
                5 -> shift = if (tag and 7 == ObfWire.WIRE_VARINT) wire.readVarint64() else wire.readBigLength()
                7 -> {
                    val childLength = length(wire, tag)
                    children += wire.position to childLength
                    wire.skip(childLength)
                }
                else -> wire.skipField(tag)
            }
        }
        wire.popLimit(old)
        return Box(
            left + (parent?.left ?: 0), right + (parent?.right ?: 0), top + (parent?.top ?: 0), bottom + (parent?.bottom ?: 0),
            body, length, shift, children,
        )
    }

    private fun collect(wire: ObfWire, box: Box, l: Long, r: Long, t: Long, b: Long, out: MutableList<Box>) {
        if (!box.intersects(l, r, t, b)) return
        if (box.shiftToData != 0L) out += box
        for ((body, length) in box.children) {
            val child = synchronized(lock) { boxes[body] } ?: readBox(wire, body, length, box).also { read ->
                synchronized(lock) { if (boxes.size < MAX_BOXES) boxes[body] = read }
            }
            collect(wire, child, l, r, t, b, out)
        }
    }

    private class Raw(
        val types: IntArray,
        val names: List<Pair<Int, Int>>,
        val x: IntArray,
        val y: IntArray,
        val pointTypes: Map<Int, IntArray>,
        val pointNames: List<Triple<Int, Int, Int>>,
    )

    private fun block(wire: ObfWire, section: Section, box: Box): List<ObfRoadWay> {
        val at = box.body + box.shiftToData
        synchronized(lock) { blocks[at]?.let { return it } }
        wire.seek(at)
        val old = wire.pushLimit(wire.readVarint64())
        val raws = ArrayList<Raw>()
        var strings: List<String> = emptyList()
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                6 -> {
                    val o = wire.pushLimit(wire.readVarint64())
                    raws += readRoad(wire, box)
                    wire.popLimit(o)
                }
                8 -> {
                    val o = wire.pushLimit(wire.readVarint64())
                    val list = ArrayList<String>()
                    while (true) {
                        val t = wire.readTag()
                        if (t == 0) break
                        if (t ushr 3 == 1) list += wire.readString() else wire.skipField(t)
                    }
                    wire.popLimit(o)
                    strings = list
                }
                else -> wire.skipField(tag) // 5 the id table, 7 restrictions
            }
        }
        wire.popLimit(old)
        val rules = section.rules
        val ways = raws.map { raw ->
            val tags = HashMap<String, String>()
            for (id in raw.types) rules.getOrNull(id)?.let { tags[it.tag] = it.value }
            for ((id, s) in raw.names) {
                val rule = rules.getOrNull(id) ?: continue
                strings.getOrNull(s)?.let { tags[rule.tag] = it }
            }
            val points = HashMap<Int, HashMap<String, String>>()
            for ((p, ids) in raw.pointTypes) {
                if (p !in raw.x.indices) continue
                val m = points.getOrPut(p) { HashMap() }
                for (id in ids) rules.getOrNull(id)?.let { m[it.tag] = it.value }
            }
            for ((p, id, s) in raw.pointNames) {
                if (p !in raw.x.indices) continue
                val rule = rules.getOrNull(id) ?: continue
                val value = strings.getOrNull(s) ?: continue
                points.getOrPut(p) { HashMap() }[rule.tag] = value
            }
            ObfRoadWay(
                tags,
                DoubleArray(raw.y.size) { ObfTiles.latitude31(raw.y[it].toLong() and 0xffffffffL) },
                DoubleArray(raw.x.size) { ObfTiles.longitude31(raw.x[it].toLong() and 0xffffffffL) },
                points,
            )
        }
        synchronized(lock) { blocks[at] = ways }
        return ways
    }

    private fun readRoad(wire: ObfWire, box: Box): Raw {
        val types = ArrayList<Int>()
        val names = ArrayList<Pair<Int, Int>>()
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        val pointTypes = HashMap<Int, IntArray>()
        val pointNames = ArrayList<Triple<Int, Int, Int>>()
        while (true) {
            val tag = wire.readTag()
            if (tag == 0) break
            when (tag ushr 3) {
                1 -> {
                    val o = wire.pushLimit(wire.readVarint64())
                    var px = (box.left ushr SHIFT).toInt()
                    var py = (box.top ushr SHIFT).toInt()
                    while (wire.remaining > 0) {
                        val x = wire.readSInt32() + px
                        val y = wire.readSInt32() + py
                        xs += x shl SHIFT
                        ys += y shl SHIFT
                        px = x
                        py = y
                    }
                    wire.popLimit(o)
                }
                4 -> {
                    val o = wire.pushLimit(wire.readVarint64())
                    while (wire.remaining > 0) {
                        val point = wire.readVarint32()
                        val inner = wire.pushLimit(wire.readVarint64())
                        val ids = ArrayList<Int>()
                        while (wire.remaining > 0) ids += wire.readVarint32()
                        wire.popLimit(inner)
                        pointTypes[point] = ids.toIntArray()
                    }
                    wire.popLimit(o)
                }
                5 -> {
                    val o = wire.pushLimit(wire.readVarint64())
                    while (wire.remaining > 0) {
                        pointNames += Triple(wire.readVarint32(), wire.readVarint32(), wire.readVarint32())
                    }
                    wire.popLimit(o)
                }
                7 -> {
                    val o = wire.pushLimit(wire.readVarint64())
                    while (wire.remaining > 0) types += wire.readVarint32()
                    wire.popLimit(o)
                }
                14 -> {
                    val o = wire.pushLimit(wire.readVarint64())
                    while (wire.remaining > 0) names += wire.readVarint32() to wire.readVarint32()
                    wire.popLimit(o)
                }
                else -> wire.skipField(tag) // 12 the route id
            }
        }
        return Raw(types.toIntArray(), names, xs.toIntArray(), ys.toIntArray(), pointTypes, pointNames)
    }

    /** A length-delimited field's length: a varint, OsmAnd's big-endian, or a `fixed32` read big-endian as OsmAnd does. */
    private fun length(wire: ObfWire, tag: Int): Long =
        if (tag and 7 == ObfWire.WIRE_FIXED32) wire.readBigLength() else wire.readLength(tag)

    companion object {
        /** OsmAnd's `SHIFT_COORDINATES` for routing points. */
        private const val SHIFT = 4
        /** Decoded data blocks kept: a route's exits are a few kilometres apart, a block a few hundred metres. */
        private const val MAX_BLOCKS = 64
        private const val MAX_BOXES = 50_000
        /** More rules than any real file has; a larger id is a corrupt one. */
        private const val MAX_RULES = 1 shl 20
    }
}
