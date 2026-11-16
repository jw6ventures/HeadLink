package com.jw6ventures.headlink

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * A [RandomAccessFile] that reads through a [Source], for OsmAnd's `.obf` reader, which
 * takes only a `RandomAccessFile`. Pure JVM, so the host tests run it.
 *
 * **Why.** A file in the maps folder comes from Android's document provider as a
 * descriptor, never a path. Up to 0.10.1 OsmAnd's readers reopened the descriptor's
 * `/proc/self/fd` link instead, and on the phone that open was refused: the link reopens
 * the file by its real place in shared storage, which this app may not read. Every map
 * was skipped, and the reports said "no OsmAnd maps open" and "no OsmAnd map with map
 * data" with all 11 files listed and open. [ObfLibrary] gives each reader a [Source] over
 * its own duplicate of the provider's descriptor instead.
 *
 * **Not a FileChannel.** A thread interrupt closes a `FileChannel` for good, and VTM
 * interrupts its tile loaders to pause them ([MapLoaderInterrupts]). The Android source
 * reads with `pread`, which an interrupt does not close.
 *
 * The base class opens [anchor] only because it has no constructor without a file; no
 * byte is read from it. OsmAnd's reader calls [seek], [getFilePointer], [length],
 * `readFully` (final, built on [read]) and [close]; reads are positional, so nothing is
 * shared between readers. [close] closes [source]. One reader each.
 */
class PositionalFile @JvmOverloads constructor(
    private val source: Source,
    anchor: File = NULL_DEVICE,
) : RandomAccessFile(anchor, "r") {
    /** Bytes at a position, moving nothing shared. */
    interface Source : Closeable {
        @Throws(IOException::class)
        fun size(): Long

        /** Up to [len] bytes at [position] into [b] from [off]; -1 at the end. */
        @Throws(IOException::class)
        fun read(b: ByteArray, off: Int, len: Int, position: Long): Int
    }

    private var position = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray): Int = read(b, 0, b.size)

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = source.read(b, off, len, position)
        if (n > 0) position += n
        return n
    }

    override fun seek(pos: Long) {
        if (pos < 0) throw IOException("negative seek")
        position = pos
    }

    override fun getFilePointer(): Long = position

    override fun length(): Long = source.size()

    override fun skipBytes(n: Int): Int {
        if (n <= 0) return 0
        val skipped = minOf(n.toLong(), maxOf(0L, source.size() - position)).toInt()
        position += skipped
        return skipped
    }

    override fun close() {
        try {
            source.close()
        } finally {
            super.close()
        }
    }

    companion object {
        private val NULL_DEVICE = File("/dev/null")
    }
}

/**
 * OsmAnd readers that could not be opened over a file the maps folder listed, by the router
 * or the map, for the `[routing]` report: how many, and the last one's exception class
 * only (a message can carry a path).
 */
object ObfReaderFailures {
    private val count = AtomicLong()

    @Volatile
    private var last = "-"

    fun note(e: Throwable) {
        count.incrementAndGet()
        last = e.javaClass.simpleName
    }

    fun report(): String = "obf_reader_failures=${count.get()} obf_reader_failure_last=$last"
}
