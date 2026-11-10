package com.jw6ventures.headlink

import net.osmand.binary.BinaryMapDataObject
import net.osmand.binary.BinaryMapIndexReader
import org.oscim.core.MapElement
import org.oscim.core.Tile
import org.oscim.layers.tile.MapTile
import org.oscim.tiling.ITileDataSink
import org.oscim.tiling.ITileDataSource
import org.oscim.tiling.OverzoomTileDataSource
import org.oscim.tiling.QueryResult
import org.oscim.tiling.TileSource
import org.oscim.utils.geom.TileClipper
import org.oscim.utils.geom.TileSeparator
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The map pane's tiles from OsmAnd's `.obf` maps instead of a Mapsforge `.map` file
 * (docs/navigation.md, "Drawing the map from OsmAnd maps"), behind Settings -> Map ->
 * Draw map from OsmAnd files. Pure JVM, so the host tests run it.
 *
 * **Readers.** Each of VTM's tile loader threads gets its own [Reader], and each opens its
 * own [BinaryMapIndexReader] per file: OsmAnd's reader keeps a stream position and fills
 * shared index structures lazily, so readers are never shared between threads. A reader
 * per thread costs one table-of-contents read per file, once.
 *
 * Above [overZoom] tiles are cut from the tile one level up, as VTM does for Mapsforge.
 */
class ObfMapSource(
    private val maps: () -> List<OsmAndRouter.MapFile>,
    zoomMin: Int = 0,
    zoomMax: Int = 20,
    overZoom: Int = ObfMapTiles.BUILDING_ZOOM,
) : TileSource(zoomMin, zoomMax, overZoom) {
    /** Tiles answered and tiles that failed, for the session report; no place in either. */
    val reads = AtomicLong()
    val failures = AtomicLong()

    /** The files found at [open], for the report's map name. */
    @Volatile
    var files: List<OsmAndRouter.MapFile> = emptyList()
        private set

    override fun open(): OpenResult {
        val found = try {
            maps().filter(::hasMapData)
        } catch (e: RuntimeException) {
            return OpenResult("OsmAnd maps could not be listed: ${e.javaClass.simpleName}")
        }
        files = found
        return if (found.isEmpty()) OpenResult("no OsmAnd map with map data") else OpenResult.SUCCESS
    }

    override fun close() = Unit

    override fun getDataSource(): ITileDataSource = OverzoomTileDataSource(Reader(files), mOverZoom)

    /** One loader thread's view of the files. */
    inner class Reader(private val wanted: List<OsmAndRouter.MapFile>) : ITileDataSource {
        private var opened: List<Pair<RandomAccessFile, BinaryMapIndexReader>>? = null
        private val element = MapElement()
        private val clipper = TileClipper(0f, 0f, 0f, 0f)
        private val separator = TileSeparator(0f, 0f, Tile.SIZE.toFloat(), Tile.SIZE.toFloat())

        @Volatile
        private var request: BinaryMapIndexReader.SearchRequest<BinaryMapDataObject>? = null

        private fun readers(): List<BinaryMapIndexReader> {
            opened?.let { list -> return list.map { it.second } }
            val list = wanted.mapNotNull(::openReader)
            opened = list
            return list.map { it.second }
        }

        override fun query(tile: MapTile, sink: ITileDataSink) {
            try {
                val box = ObfMapTiles.box(tile.tileX, tile.tileY, tile.zoomLevel.toInt())
                val buffer = if (box.zoom > ObfMapTiles.BUILDING_ZOOM) Tile.SIZE / 2f else CLIP_BUFFER
                clipper.setRect(-buffer, -buffer, Tile.SIZE + buffer, Tile.SIZE + buffer)
                val objects = ArrayList<BinaryMapDataObject>()
                val coastlines = ArrayList<BinaryMapDataObject>()
                var ocean = false
                var land = false
                val ids = HashSet<Long>()
                val req = BinaryMapIndexReader.buildSearchRequest(box.left, box.right, box.top, box.bottom, box.zoom, null)
                request = req
                for (reader in readers()) {
                    req.clearSearchResults()
                    val found = reader.searchMapIndex(req)
                    if (req.isCancelled) {
                        sink.completed(QueryResult.FAILED)
                        return
                    }
                    for (o in found) {
                        // The same way in two neighbouring regions' files is drawn once.
                        if (o.id > 0 && !ids.add(o.id)) continue
                        if (o.containsType(o.mapIndex.coastlineEncodingType)) coastlines += o else objects += o
                    }
                    ocean = ocean || req.isOcean
                    land = land || req.isLand
                }
                val sea = ObfMapTiles.coast(coastlines, box)
                    ?: if (ocean && !land) listOf(ObfMapTiles.SeaRing(true, ObfMapTiles.wholeTile(box))) else emptyList()
                for (ring in sea) {
                    if (ObfMapTiles.seaElement(ring, box, element, clipper)) sink.process(element)
                }
                for (o in objects) {
                    if (ObfMapTiles.element(o, box, element, clipper, separator)) sink.process(element)
                }
                reads.incrementAndGet()
                sink.completed(QueryResult.SUCCESS)
            } catch (e: IOException) {
                fail(sink, e)
            } catch (e: RuntimeException) {
                fail(sink, e)
            } finally {
                request = null
            }
        }

        private fun fail(sink: ITileDataSink, e: Exception) {
            failures.incrementAndGet()
            // The class only: a message from OsmAnd's reader can carry coordinates.
            LOG.log(Level.WARNING, "OsmAnd map tile failed: ${e.javaClass.simpleName}")
            sink.completed(QueryResult.FAILED)
        }

        override fun dispose() {
            opened?.forEach { closeQuietly(it.first) }
            opened = null
        }

        override fun cancel() {
            request?.setInterrupted(true)
        }
    }

    companion object {
        private val LOG = Logger.getLogger(ObfMapSource::class.java.name)

        /** VTM's MapFile clip margin below building zoom, in tile pixels. */
        private const val CLIP_BUFFER = 16f

        private fun hasMapData(map: OsmAndRouter.MapFile): Boolean =
            openReader(map)?.let { (file, reader) ->
                val has = reader.mapIndexes.isNotEmpty()
                closeQuietly(file)
                has
            } ?: false

        private fun openReader(map: OsmAndRouter.MapFile): Pair<RandomAccessFile, BinaryMapIndexReader>? {
            var file: RandomAccessFile? = null
            return try {
                file = RandomAccessFile(map.path, "r")
                val named = object : File(map.path) {
                    override fun getName(): String = map.name
                }
                val reader = BinaryMapIndexReader(file, named)
                if (reader.mapIndexes.isEmpty()) {
                    closeQuietly(file)
                    null
                } else {
                    file to reader
                }
            } catch (e: IOException) {
                file?.let(::closeQuietly)
                null
            } catch (e: RuntimeException) {
                file?.let(::closeQuietly)
                null
            }
        }

        private fun closeQuietly(file: RandomAccessFile) {
            try {
                file.close()
            } catch (_: IOException) {
            }
        }
    }
}
