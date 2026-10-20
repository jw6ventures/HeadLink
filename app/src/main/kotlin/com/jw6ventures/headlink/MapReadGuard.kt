package com.jw6ventures.headlink

import org.oscim.backend.canvas.Bitmap
import org.oscim.core.MapElement
import org.oscim.layers.tile.MapTile
import org.oscim.tiling.ITileDataSink
import org.oscim.tiling.ITileDataSource
import org.oscim.tiling.QueryResult
import org.oscim.tiling.TileSource
import org.oscim.tiling.source.mapfile.MapFile
import org.oscim.tiling.source.mapfile.ReadBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Wraps the map's tile source so a tile whose map-file read went wrong is retried
 * instead of shown as empty.
 *
 * **VTM reports a failed block read as a successful, empty tile.** Every early exit in
 * `MapFile.processBlocks` (a short read, a block pointer out of range, a buffer it could
 * not allocate) logs a warning and returns, and `query` then completes the tile with
 * `SUCCESS`. The tile manager caches that as a loaded tile and never asks again, so a
 * read that fails once leaves a patch of empty ground with only the route line drawn
 * over it until the tile leaves the cache. That is what the first Texas drive showed
 * (21 September 2026). A tile completed with `FAILED` is different: VTM clears it and
 * queues it again on the next position update, which on a moving vehicle is the next fix.
 *
 * The warning is the only signal VTM gives, so this listens on the reader's loggers.
 * A tile query runs start to finish on one loader thread, so a warning logged on that
 * thread during the query belongs to that tile. No VTM source is changed.
 *
 * A block that fails every time (bad data, or one over VTM's 10 MB block limit) would
 * be read again on every fix, so each tile gets [MAX_ATTEMPTS] tries. After that the
 * partial result is let through and counted, which is what VTM did before on the
 * first try.
 */
class MapReadGuard(private val inner: TileSource) : TileSource(
    inner.zoomLevelMin, inner.zoomLevelMax, inner.overZoom,
) {
    /** Tile queries answered, failed reads turned into retries, and tiles given up on. */
    val reads = AtomicLong()
    val failures = AtomicLong()
    val gaveUp = AtomicLong()

    /** The first warning VTM logged for a failed read: block sizes and offsets, no position. */
    val firstWarning = AtomicReference<String?>(null)

    /** Failed tries per tile, keyed by [tileKey]; cleared once a tile loads cleanly. */
    private val attempts = ConcurrentHashMap<Long, Int>()

    /** Set while a guarded query is running on this thread; true once VTM warned. */
    private val warned = ThreadLocal<BooleanArray?>()

    // Held for as long as the handler is attached: java.util.logging keeps loggers
    // weakly, and one collected and recreated would silently lose the handler.
    private val loggers = READER_CLASSES.map { Logger.getLogger(it.name) }

    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            if (record.level.intValue() < Level.WARNING.intValue()) return
            val slot = warned.get() ?: return
            slot[0] = true
            firstWarning.compareAndSet(null, record.message)
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    init {
        loggers.forEach { it.addHandler(handler) }
    }

    override fun getDataSource(): ITileDataSource = GuardedSource(inner.dataSource)

    override fun open(): OpenResult = inner.open()

    override fun close() {
        loggers.forEach { it.removeHandler(handler) }
        inner.close()
    }

    override fun getAlpha(): Float = inner.alpha

    override fun getFadeSteps() = inner.fadeSteps

    override fun getName(): String? = inner.name

    override fun getTileSize(): Int = inner.tileSize

    /** `key=value` fields for the session report. */
    fun report(): String =
        "map_reads=${reads.get()} map_read_failures=${failures.get()} " +
            "map_read_gave_up=${gaveUp.get()} map_read_first=${firstWarning.get() ?: "-"}"

    private inner class GuardedSource(private val source: ITileDataSource) : ITileDataSource {
        override fun query(tile: MapTile, sink: ITileDataSink) {
            val slot = BooleanArray(1)
            warned.set(slot)
            try {
                source.query(tile, GuardedSink(tile, sink, slot))
            } finally {
                warned.set(null)
            }
        }

        override fun dispose() = source.dispose()

        override fun cancel() = source.cancel()
    }

    private inner class GuardedSink(
        private val tile: MapTile,
        private val sink: ITileDataSink,
        private val slot: BooleanArray,
    ) : ITileDataSink {
        override fun process(element: MapElement) = sink.process(element)

        override fun setTileImage(bitmap: Bitmap) = sink.setTileImage(bitmap)

        override fun completed(result: QueryResult) {
            reads.incrementAndGet()
            sink.completed(judge(tileKey(tile), result, slot[0]))
        }
    }

    /** What VTM is told about a tile whose query ended with [result]. */
    private fun judge(key: Long, result: QueryResult, readFailed: Boolean): QueryResult {
        if (result != QueryResult.SUCCESS || !readFailed) {
            if (result == QueryResult.SUCCESS) attempts.remove(key)
            return result
        }
        val tries = (attempts[key] ?: 0) + 1
        if (tries >= MAX_ATTEMPTS) {
            attempts.remove(key)
            gaveUp.incrementAndGet()
            return result
        }
        // A tile that never loads cleanly and is never tried again would otherwise stay
        // here for the whole session; a drive across a state touches a few thousand.
        if (attempts.size > MAX_TRACKED) attempts.clear()
        attempts[key] = tries
        failures.incrementAndGet()
        return QueryResult.FAILED
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val MAX_TRACKED = 4096

        /** The classes whose warnings mean a block was skipped. */
        private val READER_CLASSES = listOf(MapFile::class.java, ReadBuffer::class.java)

        private fun tileKey(tile: MapTile): Long =
            (tile.zoomLevel.toLong() shl 58) or (tile.tileX.toLong() shl 29) or tile.tileY.toLong()
    }
}
