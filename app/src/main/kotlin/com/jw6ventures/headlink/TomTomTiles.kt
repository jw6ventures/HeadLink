package com.jw6ventures.headlink

import java.io.IOException
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/*
 * Live traffic (P9, docs/navigation.md, "Live traffic (HERE)"): TomTom's vector flow tiles
 * read into TrafficModel's types.
 *
 * A tile is a Mapbox Vector Tile, which is protobuf. No protobuf or vector tile library is
 * used, for the reason JsonStream exists: nothing new on the app's classpath, and plain
 * Kotlin the host tests load. Only the few fields a flow tile carries are read; any other
 * field is stepped over by its wire type.
 *
 * Each linestring in TomTom's flow layer is one direction of travel, its points in the
 * order it is driven, so a stretch made from it matches the route by heading
 * (TrafficMatch) exactly as one of HERE's does.
 */

/** One web map tile: [zoom], [x] west to east, [y] north to south. */
data class TileId(val zoom: Int, val x: Int, val y: Int)

/** TomTom's flow tiles: which cover a box, and what one says. */
object TomTomTiles {
    /** The layer flow is in; every other layer is ignored. */
    const val LAYER = "Traffic flow"

    /** Web Mercator's latitude limit: the square world stops here. */
    private const val MAX_LATITUDE = 85.05112878

    /**
     * The tiles at [zoom] that [box] touches, rows north to south, each row west to east.
     * At least one.
     */
    fun covering(box: TrafficBox, zoom: Int): List<TileId> {
        val west = tile(worldX(box.west), zoom)
        val east = tile(worldX(box.east), zoom)
        val north = tile(worldY(box.north), zoom)
        val south = tile(worldY(box.south), zoom)
        val tiles = ArrayList<TileId>()
        for (y in minOf(north, south)..maxOf(north, south)) {
            for (x in minOf(west, east)..maxOf(west, east)) tiles.add(TileId(zoom, x, y))
        }
        return tiles
    }

    /** Web Mercator's x, 0 at 180° W to 1 at 180° E. */
    private fun worldX(lon: Double): Double = (lon + 180.0) / 360.0

    /** Web Mercator's y, 0 at the top of the square world to 1 at its foot. */
    private fun worldY(lat: Double): Double {
        val phi = Math.toRadians(lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
        return (1.0 - ln(tan(phi) + 1.0 / cos(phi)) / PI) / 2.0
    }

    private fun tile(world: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor(world * n).toInt().coerceIn(0, n - 1)
    }

    /**
     * The flow in one tile's [body] (already decompressed), each road clipped to [tile]'s own
     * square and, when given, to [within]. TomTom draws a 10% margin of its neighbours' roads
     * round every tile, and a road read twice at a tile edge would count its jam twice
     * (TrafficNogos), so the margin is dropped; clipping to [within] as well means boxes that
     * do not overlap, fetched apart, never hold the same road either.
     *
     * A line with neither `relative_speed` nor `road_closure` is no data, and is dropped.
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun flow(body: ByteArray, tile: TileId, within: TrafficBox? = null): List<FlowStretch> {
        val stretches = ArrayList<FlowStretch>()
        val reader = Protobuf(body, 0, body.size)
        while (reader.more()) {
            val tag = reader.tag()
            if (tag == LAYERS) layer(reader.message(), tile, within, stretches) else reader.skip()
        }
        return stretches
    }

    /**
     * HERE's functional class for TomTom's `road_category`: 1 the most major, 5 a street.
     * The route's matching and pricing ([TrafficCost.classFactor]) read only the class.
     */
    fun functionalClass(category: String?): Int = when (category) {
        "motorway", "motorway_link" -> 1
        "trunk", "trunk_link" -> 2
        "primary", "primary_link" -> 3
        "secondary", "secondary_link", "tertiary", "tertiary_link" -> 4
        else -> 5
    }

    /** The rectangle lines are kept within, tile units. */
    private class Window(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double) {
        val empty: Boolean get() = !(xMin < xMax && yMin < yMax)
    }

    /** [tile]'s square, `[0, extent]²`, cut down to [within] when given. */
    private fun window(tile: TileId, extent: Double, within: TrafficBox?): Window {
        if (within == null) return Window(0.0, extent, 0.0, extent)
        val n = (1 shl tile.zoom).toDouble()
        fun px(lon: Double) = (worldX(lon) * n - tile.x) * extent
        fun py(lat: Double) = (worldY(lat) * n - tile.y) * extent
        return Window(
            xMin = maxOf(0.0, px(within.west)),
            xMax = minOf(extent, px(within.east)),
            yMin = maxOf(0.0, py(within.north)),
            yMax = minOf(extent, py(within.south)),
        )
    }

    /** One layer: read whole before its features, since its keys and values may follow them. */
    private fun layer(reader: Protobuf, tile: TileId, within: TrafficBox?, out: MutableList<FlowStretch>) {
        var name: String? = null
        var extent = 4096L
        val features = ArrayList<Protobuf>()
        val keys = ArrayList<String>()
        val values = ArrayList<Any?>()
        while (reader.more()) {
            when (reader.tag()) {
                LAYER_NAME -> name = reader.string()
                LAYER_FEATURES -> features.add(reader.message())
                LAYER_KEYS -> keys.add(reader.string())
                LAYER_VALUES -> values.add(value(reader.message()))
                LAYER_EXTENT -> extent = reader.varint()
                else -> reader.skip()
            }
        }
        if (name != LAYER) return
        if (extent <= 0 || extent > Int.MAX_VALUE) malformed("extent $extent")
        val size = extent.toDouble()
        val window = window(tile, size, within)
        if (window.empty) return
        for (feature in features) feature(feature, keys, values, size, window, tile, out)
    }

    /** A `Value`: a string, a number as a double, a boolean, or null for none of these. */
    private fun value(reader: Protobuf): Any? {
        var value: Any? = null
        while (reader.more()) {
            when (reader.tag()) {
                VALUE_STRING -> value = reader.string()
                VALUE_FLOAT -> value = java.lang.Float.intBitsToFloat(reader.fixed32()).toDouble()
                VALUE_DOUBLE -> value = java.lang.Double.longBitsToDouble(reader.fixed64())
                VALUE_INT -> value = reader.varint().toDouble()
                // uint64: the varint's bits read unsigned.
                VALUE_UINT -> value = reader.varint().toULong().toDouble()
                VALUE_SINT -> value = zigzag(reader.varint()).toDouble()
                VALUE_BOOL -> value = reader.varint() != 0L
                else -> reader.skip()
            }
        }
        return value
    }

    private fun feature(
        reader: Protobuf,
        keys: List<String>,
        values: List<Any?>,
        extent: Double,
        window: Window,
        tile: TileId,
        out: MutableList<FlowStretch>,
    ) {
        // Repeated fields may come packed or one by one, in any number of records: each is
        // added in the order it came.
        val tags = Longs()
        val geometry = Longs()
        var type = 0L
        while (reader.more()) {
            when (reader.tag()) {
                FEATURE_TAGS -> reader.message().packed(tags)
                FEATURE_TAGS_ONE -> tags.add(reader.varint())
                FEATURE_TYPE -> type = reader.varint()
                FEATURE_GEOMETRY -> reader.message().packed(geometry)
                FEATURE_GEOMETRY_ONE -> geometry.add(reader.varint())
                else -> reader.skip()
            }
        }
        if (type != LINESTRING) return
        var category: String? = null
        var relative: Double? = null
        var absolute: Double? = null
        var closed = false
        if (tags.size % 2 != 0) malformed("odd tags")
        for (i in 0 until tags.size step 2) {
            val k = tags[i]
            val v = tags[i + 1]
            if (k < 0 || k >= keys.size || v < 0 || v >= values.size) malformed("tag out of range")
            val value = values[v.toInt()]
            when (keys[k.toInt()]) {
                "road_category" -> category = value as? String
                "relative_speed" -> relative = (value as? Double)?.takeIf { it.isFinite() }
                "absolute_speed" -> absolute = (value as? Double)?.takeIf { it.isFinite() }
                "road_closure" -> closed = value == true
            }
        }
        val rel = relative
        if (!closed && rel == null) return
        val abs = absolute
        val functionalClass = functionalClass(category)
        val speed = abs?.div(3.6)
        val freeFlow = if (abs != null && rel != null && rel > 0) abs / rel / 3.6 else null
        val jamFactor = rel?.let { 10.0 * (1.0 - it.coerceIn(0.0, 1.0)) } ?: 10.0
        val traversability = if (closed) Traversability.CLOSED else Traversability.OPEN
        for (line in lines(geometry)) {
            for (piece in clip(line, window)) {
                // Tiles own their edges half-open: a piece along the east or south seam is the neighbour's, at 0.
                if ((0 until piece.size).all { piece.x(it) == extent } || (0 until piece.size).all { piece.y(it) == extent }) continue
                val (lats, lons, length) = stretch(piece, extent, tile) ?: continue
                out.add(
                    FlowStretch(
                        description = null,
                        lengthM = length,
                        latitudes = lats,
                        longitudes = lons,
                        functionalClass = functionalClass,
                        spans = listOf(FlowSpan(0.0, length, speed, freeFlow, jamFactor, traversability)),
                    ),
                )
            }
        }
    }

    /**
     * A linestring feature's geometry as lines in tile units. Each line is a MoveTo of one
     * point then a LineTo of at least one; anything else (a ClosePath, an unknown command,
     * a count or parameter past 32 bits, a missing parameter) is malformed. The cursor
     * starts at (0, 0) and every coordinate is a zigzag delta from the one before.
     */
    private fun lines(geometry: Longs): List<Points> {
        val lines = ArrayList<Points>()
        var at = 0
        fun next(): Long {
            if (at >= geometry.size) malformed("geometry ends early")
            val value = geometry[at++]
            if (value < 0 || value > UINT32_MAX) malformed("geometry integer $value past 32 bits")
            return value
        }
        var x = 0L
        var y = 0L
        while (at < geometry.size) {
            val move = next()
            if ((move and 7L).toInt() != MOVE_TO || move ushr 3 != 1L) malformed("line not started by one MoveTo")
            x += zigzag(next())
            y += zigzag(next())
            val line = Points().also { lines.add(it) }
            line.add(x.toDouble(), y.toDouble())
            val draw = next()
            var count = draw ushr 3
            if ((draw and 7L).toInt() != LINE_TO || count < 1) malformed("MoveTo not followed by LineTo")
            while (count-- > 0) {
                x += zigzag(next())
                y += zigzag(next())
                line.add(x.toDouble(), y.toDouble())
            }
        }
        return lines
    }

    /**
     * [line] cut to [window], one segment at a time (Liang–Barsky): a line that leaves and
     * comes back is split where it leaves.
     */
    private fun clip(line: Points, window: Window): List<Points> {
        val pieces = ArrayList<Points>()
        var piece: Points? = null
        for (i in 0 until line.size - 1) {
            val x0 = line.x(i)
            val y0 = line.y(i)
            val x1 = line.x(i + 1)
            val y1 = line.y(i + 1)
            val dx = x1 - x0
            val dy = y1 - y0
            var t0 = 0.0
            var t1 = 1.0
            var inside = true
            val p = doubleArrayOf(-dx, dx, -dy, dy)
            val q = doubleArrayOf(x0 - window.xMin, window.xMax - x0, y0 - window.yMin, window.yMax - y0)
            for (edge in 0 until 4) {
                if (p[edge] == 0.0) {
                    if (q[edge] < 0.0) { inside = false; break }
                } else {
                    val r = q[edge] / p[edge]
                    if (p[edge] < 0.0) t0 = maxOf(t0, r) else t1 = minOf(t1, r)
                }
            }
            if (!inside || t0 > t1) {
                piece = null
                continue
            }
            // The ends exactly where the segment is not cut, so joined segments meet exactly.
            val ax = if (t0 == 0.0) x0 else x0 + t0 * dx
            val ay = if (t0 == 0.0) y0 else y0 + t0 * dy
            val bx = if (t1 == 1.0) x1 else x0 + t1 * dx
            val by = if (t1 == 1.0) y1 else y0 + t1 * dy
            val open = piece
            val current = if (open != null && open.size > 0 && open.lastX() == ax && open.lastY() == ay) {
                open
            } else {
                Points().also { it.add(ax, ay); pieces.add(it) }
            }
            current.add(bx, by)
            piece = if (t1 < 1.0) null else current
        }
        return pieces
    }

    /**
     * A clipped piece in degrees, repeated points dropped, with its length in metres; null
     * when fewer than two points or no length are left.
     */
    private fun stretch(piece: Points, extent: Double, tile: TileId): Triple<DoubleArray, DoubleArray, Double>? {
        val n = (1 shl tile.zoom).toDouble()
        val points = Points()
        for (i in 0 until piece.size) {
            val px = piece.x(i)
            val py = piece.y(i)
            if (points.size > 0 && points.lastX() == px && points.lastY() == py) continue
            points.add(px, py)
        }
        if (points.size < 2) return null
        val latitudes = DoubleArray(points.size)
        val longitudes = DoubleArray(points.size)
        for (i in 0 until points.size) {
            val worldX = (tile.x + points.x(i) / extent) / n
            val worldY = (tile.y + points.y(i) / extent) / n
            longitudes[i] = 360.0 * worldX - 180.0
            latitudes[i] = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * worldY))))
        }
        var length = 0.0
        for (i in 1 until latitudes.size) {
            length += RouteFollower.distance(latitudes[i - 1], longitudes[i - 1], latitudes[i], longitudes[i])
        }
        if (length <= 0.0) return null
        return Triple(latitudes, longitudes, length)
    }

    private fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1L)

    private fun malformed(why: String): Nothing = throw TrafficParseException("not TomTom's tile: $why")

    /** Pairs of coordinates, grown as needed, so a long road is not boxed point by point. */
    private class Points {
        private var values = DoubleArray(32)
        var size = 0
            private set

        fun add(x: Double, y: Double) {
            if (size * 2 == values.size) values = values.copyOf(values.size * 2)
            values[size * 2] = x
            values[size * 2 + 1] = y
            size++
        }

        fun x(i: Int): Double = values[i * 2]
        fun y(i: Int): Double = values[i * 2 + 1]
        fun lastX(): Double = x(size - 1)
        fun lastY(): Double = y(size - 1)
    }

    /** A growable array of varints. */
    private class Longs {
        private var values = LongArray(16)
        var size = 0
            private set

        fun add(value: Long) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        operator fun get(i: Int): Long = values[i]
    }

    /**
     * Protobuf's wire format over [bytes] from [start] to [end]: tags, varints, fixed numbers
     * and length-delimited parts, each checked against the end. Anything malformed throws
     * a [TrafficParseException].
     */
    private class Protobuf(private val bytes: ByteArray, start: Int, private val end: Int) {
        private var pos = start
        private var wireType = -1

        fun more(): Boolean = pos < end

        /** The next field's (number shl 3 or wire type), its wire type kept for [skip]. */
        fun tag(): Long {
            val tag = varint()
            wireType = (tag and 7L).toInt()
            return tag
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (pos >= end) malformed("truncated varint")
                val b = bytes[pos++].toInt() and 0xff
                // The tenth byte holds only bit 63.
                if (shift == 63 && b > 1) malformed("varint too long")
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            malformed("varint too long")
        }

        fun fixed32(): Int {
            need(4)
            var result = 0
            for (i in 0 until 4) result = result or ((bytes[pos + i].toInt() and 0xff) shl (8 * i))
            pos += 4
            return result
        }

        fun fixed64(): Long {
            need(8)
            var result = 0L
            for (i in 0 until 8) result = result or ((bytes[pos + i].toLong() and 0xff) shl (8 * i))
            pos += 8
            return result
        }

        /** A length-delimited part as a reader of its own. */
        fun message(): Protobuf {
            if (wireType != 2) malformed("expected a length-delimited field, found wire type $wireType")
            val length = varint()
            if (length < 0 || length > end - pos) malformed("length $length past the end")
            val part = Protobuf(bytes, pos, pos + length.toInt())
            pos += length.toInt()
            return part
        }

        fun string(): String {
            val part = message()
            return String(bytes, part.pos, part.end - part.pos, Charsets.UTF_8)
        }

        /** The rest of this reader as packed varints, added to [into]. */
        fun packed(into: Longs) {
            while (more()) into.add(varint())
        }

        /** Steps over the field whose [tag] was just read. */
        fun skip() {
            when (wireType) {
                0 -> varint()
                1 -> { need(8); pos += 8 }
                2 -> message()
                5 -> { need(4); pos += 4 }
                else -> malformed("wire type $wireType")
            }
        }

        private fun need(count: Int) {
            if (end - pos < count) malformed("truncated")
        }
    }

    // Field tags, (number shl 3) or wire type.
    private const val LAYERS = (3L shl 3) or 2L
    private const val LAYER_NAME = (1L shl 3) or 2L
    private const val LAYER_FEATURES = (2L shl 3) or 2L
    private const val LAYER_KEYS = (3L shl 3) or 2L
    private const val LAYER_VALUES = (4L shl 3) or 2L
    private const val LAYER_EXTENT = (5L shl 3) or 0L
    private const val FEATURE_TAGS = (2L shl 3) or 2L
    private const val FEATURE_TAGS_ONE = (2L shl 3) or 0L
    private const val FEATURE_TYPE = (3L shl 3) or 0L
    private const val FEATURE_GEOMETRY = (4L shl 3) or 2L
    private const val FEATURE_GEOMETRY_ONE = (4L shl 3) or 0L
    private const val VALUE_STRING = (1L shl 3) or 2L
    private const val VALUE_FLOAT = (2L shl 3) or 5L
    private const val VALUE_DOUBLE = (3L shl 3) or 1L
    private const val VALUE_INT = (4L shl 3) or 0L
    private const val VALUE_UINT = (5L shl 3) or 0L
    private const val VALUE_SINT = (6L shl 3) or 0L
    private const val VALUE_BOOL = (7L shl 3) or 0L

    private const val LINESTRING = 2L
    private const val MOVE_TO = 1
    private const val LINE_TO = 2
    private const val UINT32_MAX = 0xFFFF_FFFFL
}
