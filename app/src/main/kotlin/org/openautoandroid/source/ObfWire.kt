package org.openautoandroid.source

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sinh
import kotlin.math.tan
import kotlin.math.cos

/**
 * Where an [ObfWire] reads its bytes from. Reads are positional, so one source may be
 * read by many walkers on many threads at once.
 */
interface ObfBytes {
    val size: Long

    /** Reads up to [length] bytes at [position] into [into] from [offset]; returns how many (0 at the end). */
    fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int
}

/** A file read with positional [FileChannel.read] calls: no mapping, no shared position. */
class ChannelBytes(private val channel: FileChannel) : ObfBytes {
    override val size: Long = channel.size()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return 0
        val buffer = ByteBuffer.wrap(into, offset, length)
        var at = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, at)
            if (n <= 0) break
            at += n
        }
        return (at - position).toInt()
    }
}

/** Bytes in memory: the host tests' synthetic messages. */
class ArrayBytes(private val bytes: ByteArray) : ObfBytes {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return 0
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), into, offset, n)
        return n
    }
}

/**
 * A protobuf wire walker over an OBF file, written from OsmAnd's `BinaryMapIndexReader`
 * (not generated: no generator knows OsmAnd's framing).
 *
 * OsmAnd adds **wire type 6**: a length-delimited field whose length is 4 bytes
 * big-endian, or 8 when the first byte's top bit is set ([readBigLength]). The `shiftTo…`
 * offsets the proto calls `fixed32` are read the same big-endian way, not as protobuf's
 * little-endian fixed32. Unknown fields of every wire type are skipped.
 *
 * Not thread-safe: one walker per query. Positions are absolute file offsets. [limit] is
 * where the current message ends; [readTag] returns 0 there, as protobuf's own reader does.
 */
class ObfWire(private val source: ObfBytes, bufferSize: Int = 8192) {
    private val buffer = ByteArray(bufferSize)
    private var bufferStart = 0L
    private var bufferLength = 0

    /** The next byte to read. */
    var position = 0L
        private set

    /** Where the current message ends. */
    var limit: Long = source.size
        private set

    fun seek(to: Long) {
        position = to
    }

    /** Narrows [limit] to [length] bytes from here; returns the old limit for [popLimit]. */
    @Throws(IOException::class)
    fun pushLimit(length: Long): Long {
        if (length < 0) throw IOException("negative length $length at $position")
        val end = position + length
        if (end > limit) throw IOException("message of $length bytes at $position runs past its parent ($limit)")
        val old = limit
        limit = end
        return old
    }

    fun popLimit(old: Long) {
        limit = old
    }

    val remaining: Long get() = limit - position

    @Throws(IOException::class)
    fun readByte(): Int {
        if (position >= limit) throw EOFException("read past the message end at $position")
        val inBuffer = position - bufferStart
        if (inBuffer < 0 || inBuffer >= bufferLength) {
            bufferStart = position
            bufferLength = source.read(position, buffer, 0, buffer.size)
            if (bufferLength <= 0) throw EOFException("short read at $position of ${source.size}")
        }
        val b = buffer[(position - bufferStart).toInt()].toInt() and 0xff
        position++
        return b
    }

    @Throws(IOException::class)
    fun readBytes(length: Int): ByteArray {
        if (length < 0 || position + length > limit) throw EOFException("$length bytes at $position run past $limit")
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val inBuffer = position - bufferStart
            if (inBuffer >= 0 && inBuffer < bufferLength) {
                val n = minOf(length - done, (bufferLength - inBuffer).toInt())
                System.arraycopy(buffer, inBuffer.toInt(), out, done, n)
                done += n
                position += n
            } else if (length - done >= buffer.size) {
                val n = source.read(position, out, done, length - done)
                if (n <= 0) throw EOFException("short read at $position")
                done += n
                position += n
            } else {
                bufferStart = position
                bufferLength = source.read(position, buffer, 0, buffer.size)
                if (bufferLength <= 0) throw EOFException("short read at $position")
            }
        }
        return out
    }

    @Throws(IOException::class)
    fun skip(length: Long) {
        if (length < 0 || position + length > limit) throw EOFException("skip of $length at $position runs past $limit")
        position += length
    }

    /** The next field's tag, or 0 at the end of the current message. */
    @Throws(IOException::class)
    fun readTag(): Int {
        if (position >= limit) return 0
        val tag = readVarint64()
        if (tag <= 0 || tag > Int.MAX_VALUE) throw IOException("bad tag $tag at $position")
        if ((tag ushr 3) == 0L) throw IOException("field 0 at $position")
        return tag.toInt()
    }

    @Throws(IOException::class)
    fun readVarint64(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val b = readByte()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IOException("varint longer than 10 bytes at $position")
    }

    /** A varint as protobuf's `uint32`/`int32` reads it: the low 32 bits. */
    @Throws(IOException::class)
    fun readVarint32(): Int = readVarint64().toInt()

    @Throws(IOException::class)
    fun readSInt32(): Int = zigzag32(readVarint32())

    @Throws(IOException::class)
    fun readSInt64(): Long = zigzag64(readVarint64())

    @Throws(IOException::class)
    fun readFixed32(): Int {
        val b0 = readByte(); val b1 = readByte(); val b2 = readByte(); val b3 = readByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    @Throws(IOException::class)
    fun readFixed64(): Long = (readFixed32().toLong() and 0xffffffffL) or (readFixed32().toLong() shl 32)

    /**
     * OsmAnd's `readInt`: 4 bytes big-endian, or, when the first byte's top bit is set,
     * that bit cleared and 8 bytes big-endian. Wire-type-6 lengths and `shiftTo…` offsets.
     */
    @Throws(IOException::class)
    fun readBigLength(): Long {
        var value = readByte().toLong()
        val eight = value > 0x7f
        if (eight) value = value and 0x7f
        repeat(if (eight) 7 else 3) { value = (value shl 8) + readByte() }
        return value
    }

    /** A length-delimited field's length, by its wire type: 2 a varint, 6 big-endian. */
    @Throws(IOException::class)
    fun readLength(tag: Int): Long = when (tag and 7) {
        WIRE_LENGTH -> readVarint64()
        WIRE_BIG_LENGTH -> readBigLength()
        else -> throw IOException("field ${tag ushr 3} has wire type ${tag and 7}, not a length at $position")
    }

    @Throws(IOException::class)
    fun readString(): String {
        val length = readVarint64()
        if (length < 0 || length > Int.MAX_VALUE) throw IOException("string of $length bytes at $position")
        return String(readBytes(length.toInt()), Charsets.UTF_8)
    }

    /** Skips the field whose tag was just read, whatever its wire type. */
    @Throws(IOException::class)
    fun skipField(tag: Int) {
        when (tag and 7) {
            WIRE_VARINT -> readVarint64()
            WIRE_FIXED64 -> skip(8)
            WIRE_LENGTH -> skip(readVarint64())
            WIRE_FIXED32 -> skip(4)
            WIRE_BIG_LENGTH -> skip(readBigLength())
            else -> throw IOException("field ${tag ushr 3} has unsupported wire type ${tag and 7} at $position")
        }
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5
        /** OsmAnd's own: length-delimited with a big-endian length. */
        const val WIRE_BIG_LENGTH = 6

        fun zigzag32(n: Int): Int = (n ushr 1) xor -(n and 1)
        fun zigzag64(n: Long): Long = (n ushr 1) xor -(n and 1)
    }
}

/**
 * OsmAnd's tile arithmetic (`MapUtils`): positions are Web Mercator tile numbers, at zoom
 * 31 for whole positions and zoom 24 for the address and POI deltas.
 */
object ObfTiles {
    fun longitude(zoom: Int, x: Double): Double = x / (1L shl zoom).toDouble() * 360.0 - 180.0

    fun latitude(zoom: Int, y: Double): Double {
        val sign = if (y < 0) -1 else 1
        return atan(sign * sinh(PI * (1 - 2 * y / (1L shl zoom).toDouble()))) * 180.0 / PI
    }

    fun longitude31(x: Long): Double = longitude(31, x.toDouble())
    fun latitude31(y: Long): Double = latitude(31, y.toDouble())

    fun tileX(zoom: Int, longitude: Double): Double {
        val lon = longitude.coerceIn(-180.0, 179.9999999)
        return (lon + 180.0) / 360.0 * (1L shl zoom).toDouble()
    }

    fun tileY(zoom: Int, latitude: Double): Double {
        val lat = latitude.coerceIn(-85.0511, 85.0511)
        val rad = lat * PI / 180.0
        val eval = ln(tan(rad) + 1 / cos(rad))
        return (1 - eval / PI) / 2 * (1L shl zoom).toDouble()
    }

    fun x31(longitude: Double): Long = tileX(31, longitude).toLong()
    fun y31(latitude: Double): Long = tileY(31, latitude).toLong()

    /** The zoom-24 tile a position decoded from a zoom-24 tile came from (exact for those). */
    fun x24Of(longitude: Double): Long = tileX(24, longitude).roundToLong()
    fun y24Of(latitude: Double): Long = tileY(24, latitude).roundToLong()
}
