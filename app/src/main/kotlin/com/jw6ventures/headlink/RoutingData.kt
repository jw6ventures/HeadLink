package com.jw6ventures.headlink

import btools.codec.DataBuffers
import btools.expressions.BExpressionMetaData
import btools.mapaccess.PhysicalFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.floor

/**
 * The routing engine's data: BRouter `.rd5` segment files and the profile it evaluates
 * them with, both in app-private storage.
 *
 * Segments are operator-supplied exactly as Mapsforge `.map` files are ([MapData]); the
 * app declares no `INTERNET` permission (APP10) and has no download path. The profile
 * travels inside the APK as a classpath resource and is copied out here, because
 * BRouter reads it — and the `lookups.dat` beside it — from a `File`.
 *
 * Everything but [root] is pure JVM and takes a directory, so the host tests exercise
 * the same import the connection screen uses.
 */
object RoutingData {
    fun root(filesDir: File): File = File(filesDir, "routing")

    fun segments(root: File): File = File(root, "segments")

    fun profiles(root: File): File = File(root, "profiles")

    /**
     * The canonical tile name inside [name] — `W75_N40.rd5` — or null.
     *
     * BRouter finds a segment **by its file name** and nothing else: the header does not
     * say which 5°×5° square it covers. So an import keeps the tile name, found anywhere
     * in what the picker offers ("W75_N40 (1).rd5" from a browser's second download is
     * still that tile), and refuses a file whose name says nothing — installing it under
     * a made-up name would give a directory BRouter silently never reads.
     */
    fun tileName(name: String): String? {
        if (!name.endsWith(SUFFIX, ignoreCase = true)) return null
        val match = TILE.find(name) ?: return null
        val (ew, lonText, ns, latText) = match.destructured
        val lon = lonText.toInt() * if (ew.equals("W", ignoreCase = true)) -1 else 1
        val lat = latText.toInt() * if (ns.equals("S", ignoreCase = true)) -1 else 1
        if (lon % 5 != 0 || lat % 5 != 0 || lon !in -180..175 || lat !in -90..85) return null
        return format(lon, lat)
    }

    /** The tile BRouter would open for a coordinate — the same arithmetic as its `NodesCache`. */
    fun tileFor(lat: Double, lon: Double): String {
        val lonDegree = floor(lon + 180.0).toInt().coerceIn(0, 359)
        val latDegree = floor(lat + 90.0).toInt().coerceIn(0, 179)
        return format(lonDegree - 180 - lonDegree % 5, latDegree - 90 - latDegree % 5)
    }

    private fun format(lon: Int, lat: Int): String =
        (if (lon < 0) "W${-lon}" else "E$lon") + "_" + (if (lat < 0) "S${-lat}" else "N$lat") + SUFFIX

    /** Every installed segment, by name. */
    fun installed(segments: File): List<File> =
        segments.listFiles()
            ?.filter { it.isFile && tileName(it.name) == it.name }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Writes the packaged profile and its `lookups.dat` into [profiles] unless identical
     * copies are already there, and returns the profile.
     *
     * Rewritten only on a difference: BRouter's profile cache is keyed on the file's
     * modification time, so an unconditional copy would re-parse the profile on every
     * start, and an upgrade that changes either file still takes effect.
     */
    @Throws(IOException::class)
    fun installProfile(profiles: File): File {
        if (!profiles.isDirectory && !profiles.mkdirs()) throw IOException("cannot create profile directory")
        for (name in listOf(LOOKUPS, PROFILE)) {
            val packaged = RoutingData::class.java.classLoader
                ?.getResourceAsStream("$RESOURCES/$name")
                ?.use { it.readBytes() }
                ?: throw IOException("$name is not packaged")
            val target = File(profiles, name)
            if (target.isFile && target.length() == packaged.size.toLong() && target.readBytes().contentEquals(packaged)) {
                continue
            }
            val staged = File(profiles, "$name.part")
            try {
                staged.writeBytes(packaged)
                if (!staged.renameTo(target)) throw IOException("cannot install $name")
            } finally {
                staged.delete()
            }
        }
        return File(profiles, PROFILE)
    }

    /**
     * Streams supplied data into [segments] under its tile name, replacing an existing
     * copy only once the new one has been read to the end and checked.
     *
     * A tile is over a hundred megabytes, so it is never held in memory, and it is staged
     * and renamed for the same reason [MapData.install] is: an import cut short by a
     * detached cable must not leave a truncated file where BRouter will look.
     *
     * The check is BRouter's own header read, against the installed `lookups.dat`
     * ([profiles]): it rejects a file too short for its own index, a corrupt index
     * checksum, and a tile built for a different lookup table. The last one matters most:
     * segments are regenerated upstream every few weeks, and a tile from another lookup
     * version fails on the first route with nothing pointing back at the import.
     */
    @Throws(IOException::class)
    fun install(segments: File, profiles: File, name: String, input: InputStream): File {
        val tile = tileName(name)
            ?: throw IOException("\"$name\" is not a routing tile name like W75_N40$SUFFIX")
        if (!segments.isDirectory && !segments.mkdirs()) throw IOException("cannot create routing directory")
        val staged = File(segments, "$tile.part")
        try {
            var copied = 0L
            staged.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > SIZE_LIMIT) throw IOException("routing tile exceeds ${SIZE_LIMIT / (1 shl 20)} MiB")
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
            validate(staged, File(profiles, LOOKUPS))
            val target = File(segments, tile)
            if (!staged.renameTo(target)) throw IOException("cannot install $tile")
            return target
        } finally {
            staged.delete()
        }
    }

    /** BRouter's own header read of [segment] against [lookups]; throws on any mismatch. */
    @Throws(IOException::class)
    fun validate(segment: File, lookups: File) {
        if (!lookups.isFile) throw IOException("routing profile is not installed")
        val meta = BExpressionMetaData()
        meta.readMetaData(lookups)
        // A version of -1 would make BRouter skip its own comparison rather than fail it.
        if (meta.lookupVersion < 0) throw IOException("routing profile has no lookup version")
        if (segment.length() < HEADER_MINIMUM) throw IOException("routing tile is too small to be valid")
        val file = try {
            PhysicalFile(segment, DataBuffers(), meta.lookupVersion.toInt(), meta.lookupMinorVersion.toInt())
        } catch (e: IOException) {
            throw IOException("not a usable routing tile: ${e.message}")
        }
        file.close()
    }

    const val SUFFIX = ".rd5"
    const val PROFILE = "car-vario.brf"
    const val LOOKUPS = "lookups.dat"

    /** Where the vendored module keeps the two files, relocated from upstream `misc/profiles2`. */
    private const val RESOURCES = "btools/profiles"

    /** The tile name standing on its own somewhere in a file name; the suffix is checked apart. */
    private val TILE = Regex("(?<![A-Za-z0-9])([EWew])(\\d{1,3})_([NSns])(\\d{1,2})(?!\\d)")

    /**
     * Over four times the one tile measured (W75_N40, 119 MB): it stops a mis-picked file
     * from filling the phone, and is not a judgement about how big a tile may be.
     */
    const val SIZE_LIMIT = 512L * 1024 * 1024

    /** BRouter reads a 200-byte index before anything else. */
    private const val HEADER_MINIMUM = 200
}
