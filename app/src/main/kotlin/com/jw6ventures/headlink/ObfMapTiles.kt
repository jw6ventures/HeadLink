package com.jw6ventures.headlink

import gnu.trove.list.TLongList
import gnu.trove.list.array.TLongArrayList
import net.osmand.binary.BinaryMapDataObject
import net.osmand.binary.BinaryMapIndexReader.MapIndex
import net.osmand.util.MapAlgorithms
import org.oscim.core.GeometryBuffer.GeometryType
import org.oscim.core.MapElement
import org.oscim.core.Tag
import org.oscim.core.Tile
import org.oscim.utils.geom.TileClipper
import org.oscim.utils.geom.TileSeparator

/**
 * One VTM tile's map elements from OsmAnd's map sections (docs/navigation.md, "Drawing the
 * map from OsmAnd maps"). Pure JVM, so the host tests run it over objects built in memory.
 *
 * **Coordinates.** OsmAnd keeps every point as a 31-bit Mercator tile number, the same
 * projection VTM draws in, so a tile's pixels are a shift and a scale of them: no
 * latitude or longitude is computed on the way.
 *
 * **Tags.** OsmAnd's map types are OSM tag and value pairs (`highway=motorway`,
 * `natural=water`), the same keys the project's render themes match, so they are passed
 * through. Additional types (`oneway`, `toll`, `bridge`) become tags too, and names become
 * `name`, `ref` and the other name tags they were stored under.
 *
 * **Sea.** OsmAnd stores the coast as lines, not as sea polygons: each tile's coastline
 * pieces are clipped to the tile, joined and closed along its edges into water and land
 * rings ([coast]), and a tile with no coastline that OsmAnd's index marks as ocean is water
 * whole. Water is tagged `natural=sea` and land `natural=land`, both drawn by the themes.
 */
object ObfMapTiles {
    /** A tile's bounds in OsmAnd's 31-bit numbers, top-left inclusive. */
    class Box(val zoom: Int, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        /** 31-bit units per tile pixel, as a divisor. */
        val unitsPerPixel: Double = (1L shl (31 - zoom)).toDouble() / Tile.SIZE

        fun x(x31: Int): Float = ((x31.toLong() - left) / unitsPerPixel).toFloat()

        fun y(y31: Int): Float = ((y31.toLong() - top) / unitsPerPixel).toFloat()
    }

    /** The box of VTM tile [tileX], [tileY] at [zoom]; VTM and OsmAnd number tiles alike. */
    fun box(tileX: Int, tileY: Int, zoom: Int): Box {
        val shift = 31 - zoom
        val left = tileX.toLong() shl shift
        val top = tileY.toLong() shl shift
        // The far edges stop one short of 2^31 so the world's last tile stays in Int.
        val right = minOf((tileX.toLong() + 1) shl shift, MAX_31)
        val bottom = minOf((tileY.toLong() + 1) shl shift, MAX_31)
        return Box(zoom, left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    /** The tags [coast]'s water and land polygons carry, which the themes fill. */
    val SEA = Tag("natural", "sea")
    val LAND = Tag("natural", "land")

    /**
     * Fills [out] with [o] drawn into [box], tags included; false when nothing of it falls
     * in the tile or it has no tag a theme could match.
     */
    fun element(o: BinaryMapDataObject, box: Box, out: MapElement, clipper: TileClipper, separator: TileSeparator): Boolean {
        out.clear()
        val index = o.mapIndex ?: return false
        if (!addTags(o, index, out)) return false
        val n = o.pointsLength
        if (n == 0) return false
        val area = o.isArea && n >= 3
        when {
            n == 1 -> {
                out.startPoints()
                out.addPoint(box.x(o.getPoint31XTile(0)), box.y(o.getPoint31YTile(0)))
            }
            area -> {
                out.startPolygon()
                ring(out, box, o.coordinates)
                o.polygonInnerCoordinates?.forEach { inner ->
                    if (inner.size >= 6) {
                        out.startHole()
                        ring(out, box, inner)
                    }
                }
            }
            else -> {
                out.startLine()
                for (i in 0 until n) out.addPoint(box.x(o.getPoint31XTile(i)), box.y(o.getPoint31YTile(i)))
            }
        }
        if (out.type == GeometryType.POINT) {
            // Points are not clipped: a label just outside is the next tile's.
            val x = out.points[0]
            val y = out.points[1]
            if (x < 0 || y < 0 || x >= Tile.SIZE || y >= Tile.SIZE) return false
        } else {
            if (out.isPoly && out.index[0] < 6) return false
            if (o.isLabelSpecified) {
                out.setLabelPosition(box.x(o.labelX), box.y(o.labelY))
            }
            val building = out.tags.containsKey(Tag.KEY_BUILDING) || out.tags.containsKey(Tag.KEY_BUILDING_PART)
            // As VTM's own MapFile does: buildings whole into the tile that holds them, so
            // extruded walls are never cut at a tile edge; everything else clipped.
            if (building && box.zoom >= BUILDING_ZOOM) {
                if (!separator.separate(out)) return false
            } else if (!building || box.zoom < BUILDING_ZOOM) {
                if (!clipper.clip(out)) return false
            }
            out.simplify(1f, true)
        }
        out.setLayer(LAYER_GROUND + o.simpleLayer)
        return true
    }

    /** A ring of 31-bit pairs, without its closing point, which VTM does not want. */
    private fun ring(out: MapElement, box: Box, c: IntArray) {
        var n = c.size / 2
        if (n > 1 && c[0] == c[2 * n - 2] && c[1] == c[2 * n - 1]) n--
        for (i in 0 until n) out.addPoint(box.x(c[2 * i]), box.y(c[2 * i + 1]))
    }

    private fun addTags(o: BinaryMapDataObject, index: MapIndex, out: MapElement): Boolean {
        var any = false
        for (type in o.types) {
            val pair = index.decodeType(type) ?: continue
            out.tags.add(tag(pair.tag, pair.value))
            any = true
        }
        if (!any) return false
        o.additionalTypes?.forEach { type ->
            val pair = index.decodeType(type) ?: return@forEach
            if (!out.tags.containsKey(pair.tag)) out.tags.add(tag(pair.tag, pair.value))
        }
        val names = o.objectNames
        if (names != null) {
            val keys = names.keys()
            for (type in keys) {
                val pair = index.decodeType(type) ?: continue
                val value = names.get(type) ?: continue
                if (value.isEmpty() || out.tags.containsKey(pair.tag)) continue
                // Names are many and short-lived: not interned, unlike tag values.
                out.tags.add(Tag(pair.tag, value, false))
            }
        }
        return true
    }

    private fun tag(key: String, value: String?): Tag = Tag(key, value ?: "")

    /**
     * The water and land polygons for [box] from the coastline pieces read there, adapted
     * from OsmAnd's `MapRenderRepositories.processCoastlines` (GPL-3.0-or-later, OsmAnd BV;
     * OsmAnd app commit 91d9bd6). Each piece is clipped to the box, pieces are joined end to
     * end, and rings left open are closed clockwise along the box's edges. A ring OsmAnd
     * finds clockwise is water, any other land; with only land rings, the rest is water.
     *
     * Returns null when [coastlines] gives no ring at all, which leaves the tile to
     * [wholeTile]. Rings that cannot be closed are dropped rather than drawn wrong.
     */
    fun coast(coastlines: List<BinaryMapDataObject>, box: Box): List<SeaRing>? {
        val completed = ArrayList<TLongList>()
        val uncompleted = ArrayList<TLongList>()
        val leftX = box.left
        val rightX = box.right
        val topY = box.top
        val bottomY = box.bottom
        for (o in coastlines) {
            val len = o.pointsLength
            if (len < 2) continue
            var coordinates: TLongList = TLongArrayList(len)
            var px = o.getPoint31XTile(0)
            var py = o.getPoint31YTile(0)
            var pinside = leftX <= px && px <= rightX && py >= topY && py <= bottomY
            if (pinside) coordinates.add(combine(px, py))
            for (i in 1 until len) {
                val x = o.getPoint31XTile(i)
                val y = o.getPoint31YTile(i)
                val inside = leftX <= x && x <= rightX && y >= topY && y <= bottomY
                if (lineCoordinates(inside, x, y, pinside, px, py, box, coordinates)) {
                    combine(completed, uncompleted, coordinates)
                    coordinates = TLongArrayList()
                }
                px = x
                py = y
                pinside = inside
            }
            combine(completed, uncompleted, coordinates)
        }
        if (completed.isEmpty() && uncompleted.isEmpty()) return null
        if (uncompleted.isNotEmpty()) unify(uncompleted, completed, box)
        val rings = ArrayList<SeaRing>()
        var waterFound = false
        for (ring in completed) {
            val coordinates = IntArray(ring.size() * 2)
            for (j in 0 until ring.size()) {
                coordinates[2 * j] = (ring.get(j) shr 32).toInt()
                coordinates[2 * j + 1] = (ring.get(j) and MASK).toInt()
            }
            val water = MapAlgorithms.isClockwiseWay(ring)
            waterFound = waterFound || water
            rings += SeaRing(water, coordinates)
        }
        if (!waterFound && uncompleted.isEmpty()) {
            // Only islands: the sea is everything else, drawn first, under them.
            rings.add(0, SeaRing(true, wholeTile(box)))
        }
        // Water first, land on it, so an island in a bay is drawn over the bay.
        return rings.sortedBy { if (it.water) 0 else 1 }
    }

    /** A water or land polygon from [coast], in 31-bit pairs, closed. */
    class SeaRing(val water: Boolean, val coordinates: IntArray)

    /** The box's outline as a closed ring. */
    fun wholeTile(box: Box): IntArray =
        intArrayOf(box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom, box.left, box.top)

    /** Fills [out] with a [coast] ring for [box]; false when it falls outside. */
    fun seaElement(ring: SeaRing, box: Box, out: MapElement, clipper: TileClipper): Boolean {
        out.clear()
        out.tags.add(if (ring.water) SEA else LAND)
        out.startPolygon()
        ring(out, box, ring.coordinates)
        if (out.index[0] < 6) return false
        if (!clipper.clip(out)) return false
        // Below everything else on the ground, whatever their layer.
        out.setLayer(0)
        return true
    }

    private fun combine(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and MASK)

    private fun combine(completed: MutableList<TLongList>, incompleted: MutableList<TLongList>, start: TLongList) {
        var coordinates = start
        if (coordinates.size() == 0) return
        if (coordinates.get(0) == coordinates.get(coordinates.size() - 1)) {
            completed.add(coordinates)
            return
        }
        var add = true
        var k = 0
        while (k < incompleted.size) {
            var remove = false
            val i = incompleted[k]
            if (coordinates.get(0) == i.get(i.size() - 1)) {
                i.addAll(coordinates.subList(1, coordinates.size()))
                remove = true
                coordinates = i
            } else if (coordinates.get(coordinates.size() - 1) == i.get(0)) {
                coordinates.addAll(i.subList(1, i.size()))
                remove = true
            }
            if (remove) incompleted.removeAt(k) else k++
            if (coordinates.get(0) == coordinates.get(coordinates.size() - 1)) {
                completed.add(coordinates)
                add = false
                break
            }
        }
        if (add) incompleted.add(coordinates)
    }

    private fun unify(toProcess: MutableList<TLongList>, completed: MutableList<TLongList>, box: Box) {
        val leftX = box.left
        val rightX = box.right
        val topY = box.top
        val bottomY = box.bottom
        val uncompleted = ArrayList(toProcess)
        toProcess.clear()
        val nonVisited = LinkedHashSet<Int>()
        for (j in uncompleted.indices) {
            val i = uncompleted[j]
            val x = (i.get(i.size() - 1) shr 32).toInt()
            val y = (i.get(i.size() - 1) and MASK).toInt()
            val sx = (i.get(0) shr 32).toInt()
            val sy = (i.get(0) and MASK).toInt()
            val st = y == topY || x == rightX || y == bottomY || x == leftX
            val end = sy == topY || sx == rightX || sy == bottomY || sx == leftX
            // A piece that neither starts nor ends on the edge is broken data: kept aside.
            if (!end || !st) toProcess.add(i) else nonVisited.add(j)
        }
        val evalDelta = 6 shl maxOf(0, 23 - box.zoom)
        val undefined = -1 - evalDelta
        for (j in uncompleted.indices) {
            val i = uncompleted[j]
            if (j !in nonVisited) continue
            var x = (i.get(i.size() - 1) shr 32).toInt()
            var y = (i.get(i.size() - 1) and MASK).toInt()
            var guard = 0
            while (true) {
                if (++guard > uncompleted.size + 8) {
                    // Cannot happen with consistent data; never loop forever on a bad file.
                    toProcess.add(i)
                    break
                }
                val st = when {
                    y == topY -> 0
                    x == rightX -> 1
                    y == bottomY -> 2
                    x == leftX -> 3
                    else -> 0
                }
                var next = -1
                for (h in st until st + 4) {
                    var minDiff = undefined
                    for (ni in nonVisited) {
                        val cni = uncompleted[ni]
                        val csx = (cni.get(0) shr 32).toInt()
                        val csy = (cni.get(0) and MASK).toInt()
                        when (h % 4) {
                            0 -> if (csy == topY && csx >= safeAdd(x, -evalDelta) && (minDiff == undefined || csx - x <= minDiff)) {
                                minDiff = csx - x
                                next = ni
                            }
                            1 -> if (csx == rightX && csy >= safeAdd(y, -evalDelta) && (minDiff == undefined || csy - y <= minDiff)) {
                                minDiff = csy - y
                                next = ni
                            }
                            2 -> if (csy == bottomY && csx <= safeAdd(x, evalDelta) && (minDiff == undefined || x - csx <= minDiff)) {
                                minDiff = x - csx
                                next = ni
                            }
                            else -> if (csx == leftX && csy <= safeAdd(y, evalDelta) && (minDiff == undefined || y - csy <= minDiff)) {
                                minDiff = y - csy
                                next = ni
                            }
                        }
                    }
                    if (minDiff != undefined) break
                    when (h % 4) {
                        0 -> { y = topY; x = rightX }
                        1 -> { y = bottomY; x = rightX }
                        2 -> { y = bottomY; x = leftX }
                        else -> { y = topY; x = leftX }
                    }
                    i.add(combine(x, y))
                }
                if (next == -1) {
                    toProcess.add(i)
                    break
                } else if (next == j) {
                    i.add(i.get(0))
                    nonVisited.remove(j)
                    completed.add(i)
                    break
                } else {
                    i.addAll(uncompleted[next])
                    nonVisited.remove(next)
                    x = (i.get(i.size() - 1) shr 32).toInt()
                    y = (i.get(i.size() - 1) and MASK).toInt()
                }
            }
        }
    }

    private fun safeAdd(number: Int, delta: Int): Int {
        val res = number.toLong() + delta
        return res.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun lineCoordinates(
        inside: Boolean, x: Int, y: Int, pinside: Boolean, px: Int, py: Int, box: Box, coordinates: TLongList,
    ): Boolean {
        var ended = false
        if (pinside) {
            if (!inside) {
                var crossing = MapAlgorithms.calculateIntersection(x, y, px, py, box.left, box.right, box.bottom, box.top)
                if (crossing == -1L) crossing = combine(px, py)
                coordinates.add(crossing)
                ended = true
            } else {
                coordinates.add(combine(x, y))
            }
        } else {
            val crossing = MapAlgorithms.calculateIntersection(x, y, px, py, box.left, box.right, box.bottom, box.top)
            if (inside) {
                coordinates.add(crossing)
                coordinates.add(combine(x, y))
            } else if (crossing != -1L) {
                val bx = (crossing shr 32).toInt()
                val by = (crossing and MASK).toInt()
                coordinates.add(crossing)
                coordinates.add(MapAlgorithms.calculateIntersection(x, y, bx, by, box.left, box.right, box.bottom, box.top))
                ended = true
            }
        }
        return ended
    }

    private const val MASK = 0xffffffffL
    private const val MAX_31 = (1L shl 31) - 1

    /** VTM's layer for OSM layer 0; OsmAnd's simple layer is -1, 0 or 1. */
    const val LAYER_GROUND = 5

    /** VTM's BuildingLayer.MIN_ZOOM, from which buildings are kept whole per tile. */
    const val BUILDING_ZOOM = 17
}
