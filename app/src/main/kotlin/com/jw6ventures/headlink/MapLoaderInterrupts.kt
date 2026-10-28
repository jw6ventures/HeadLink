package com.jw6ventures.headlink

import org.oscim.layers.tile.MapTile
import org.oscim.tiling.ITileDataSink
import org.oscim.tiling.ITileDataSource
import org.oscim.tiling.TileSource
import java.util.concurrent.atomic.AtomicLong

/**
 * Wraps the map's tile source so that a theme switch cannot permanently break a tile
 * loader (P7.6).
 *
 * **VTM interrupts its loader threads to pause them, and an interrupt closes a file
 * channel.** `Map.setTheme` pauses every tile loader first (`TileLayer.pauseLoaders`),
 * and `PausableThread.pause` does that with `Thread.interrupt()`. A loader that is inside
 * a map-file read at that moment gets `ClosedByInterruptException`, and NIO closes the
 * channel for good: each loader owns one `MapFile` with its own `FileChannel`, and every
 * read that loader makes for the rest of the session fails with `ClosedChannelException`.
 * Before P7.6 the theme was set once, before any tile was read, so this never happened.
 * The first lab run with day/night switching hit it on the switch at session start, when
 * the loaders were busy with the first tiles: 108 failed reads from one loader over the
 * following twelve seconds (docs/verification.md).
 *
 * Two things, both on the loader thread around each query:
 *
 * - **Before the query, a stale interrupt is cleared.** A pause that lands between two
 *   tiles leaves the flag set, and the next read would close the channel on it. Clearing
 *   it is safe: `PausableThread` decides to pause and stop from its own flags, and the
 *   interrupt only exists to wake it from `wait()`, which it has already left if it is
 *   here.
 * - **After an interrupted query, the loader gets a fresh data source.** An interrupt
 *   that arrived mid-read is still set when the read returns (that is how
 *   `ClosedByInterruptException` leaves it), so the loader's source is replaced by a
 *   new one, with a new channel, before its next tile. The tile that was being
 *   read is lost, and VTM reads it again as it does any failed tile; [MapReadGuard]
 *   sees the reader's warning and reports it as a failed read rather than a loaded
 *   empty tile.
 *
 * No VTM source is changed.
 */
class MapLoaderInterrupts(private val inner: TileSource) : TileSource(
    inner.zoomLevelMin, inner.zoomLevelMax, inner.overZoom,
) {
    /** Data sources replaced after an interrupt. A count for the report. */
    val reopened = AtomicLong()

    /** Replacements that could not be opened; the old source was kept and retried. */
    val failedRenewals = AtomicLong()

    override fun getDataSource(): ITileDataSource = Reopening(inner.dataSource)

    override fun open(): OpenResult = inner.open()

    override fun close() = inner.close()

    override fun getAlpha(): Float = inner.alpha

    override fun getFadeSteps() = inner.fadeSteps

    override fun getName(): String? = inner.name

    override fun getTileSize(): Int = inner.tileSize

    /** One loader's data source; each loader asks [getDataSource] for its own. */
    private inner class Reopening(initial: ITileDataSource) : ITileDataSource {
        /** Replaced on the loader thread; disposed or cancelled from others. */
        @Volatile
        private var source = initial

        /** Set when a query ended interrupted: its channel may be closed. Loader thread only. */
        private var stale = false

        override fun query(tile: MapTile, sink: ITileDataSink) {
            // Cleared before anything else: a file channel opened, or read, on a thread
            // whose interrupt flag is set fails at once with ClosedByInterruptException,
            // so the renewal below has to come after this too.
            Thread.interrupted()
            if (stale) renew()
            try {
                source.query(tile, sink)
            } finally {
                if (Thread.currentThread().isInterrupted) stale = true
            }
        }

        /**
         * Swaps in a fresh source, disposing the old one only once the new one exists.
         *
         * Done at the start of the loader's next query rather than straight after the
         * interrupted one, because by then the interrupt has been cleared: the lab's first
         * run of this renewed at once, on the still-interrupted thread, and VTM's
         * `getDataSource` failed to open the file and answered null. If a new source still
         * cannot be had, the old one is kept and the next query tries again.
         */
        private fun renew() {
            val fresh: ITileDataSource? = try {
                inner.dataSource
            } catch (e: RuntimeException) {
                // A null from VTM's source becomes an NPE in a Kotlin wrapper's non-null
                // parameter; either way there is nothing to swap in.
                null
            }
            if (fresh == null) {
                failedRenewals.incrementAndGet()
                return
            }
            val old = source
            source = fresh
            stale = false
            reopened.incrementAndGet()
            try {
                old.dispose()
            } catch (e: RuntimeException) {
                // Closing an already-closed channel is the expected case, not a failure.
            }
        }

        override fun dispose() = source.dispose()

        override fun cancel() = source.cancel()
    }
}
