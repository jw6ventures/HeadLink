package com.jw6ventures.headlink

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Finds and installs the Mapsforge `.map` file the owned map renders from.
 *
 * The app declares no `INTERNET` permission (APP10), so there is no download path and
 * the data is always operator-supplied — either imported through the connection screen
 * or pushed into the app's own directory. A region is built offline from an `.osm.pbf`
 * extract with `mapsforge-map-writer`; nothing here fetches anything.
 */
object MapData {
    /** Where imported data lives, inside the app's private storage. */
    fun directory(context: Context): File = File(context.filesDir, "maps")

    /**
     * Every map to render: the named one alone if given, otherwise all installed maps.
     *
     * All of them rather than one, so a drive that leaves one region's extract for the
     * next — Texas into Colorado — carries on without anything switching: the map pane
     * draws every file as one map, and each only where it has data.
     *
     * The flip side: a newer extract replaces an older one only under the same name.
     * `texas.map` over `texas.map` is a replacement; `texas-october.map` beside
     * `texas.map` is two maps of the same ground, drawn together.
     */
    fun resolveAll(context: Context, named: String?): List<File> {
        if (named != null && named.isNotBlank()) {
            return listOfNotNull(File(named).takeIf { it.isFile })
        }
        return installed(context)
    }

    /** Every installed map, newest first, for the connection screen to list. */
    fun installed(context: Context): List<File> =
        directory(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Copies supplied data into the map directory under [name], replacing any file of
     * the same name only once the copy has completed.
     *
     * Staged and renamed rather than written in place: a map file is tens of megabytes
     * and an import interrupted by a detached cable would otherwise leave a truncated
     * file that VTM reports as corrupt on the next drive.
     */
    @Throws(IOException::class)
    fun install(context: Context, name: String, input: InputStream): File = installInto(directory(context), name, input)

    /** [install] into an explicit directory; pure JVM, so the host tests run it. */
    @Throws(IOException::class)
    fun installInto(directory: File, name: String, input: InputStream): File {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("cannot create map directory")
        }
        val safe = safeName(name)
        val staged = File(directory, "$safe.part")
        try {
            var copied = 0L
            staged.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > SIZE_LIMIT) throw IOException("map exceeds ${SIZE_LIMIT / (1 shl 20)} MiB")
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
            if (copied < HEADER_MINIMUM) throw IOException("map file is too small to be valid")
            checkHeader(staged)
            val target = File(directory, safe)
            if (!staged.renameTo(target)) throw IOException("cannot install map")
            return target
        } finally {
            staged.delete()
        }
    }

    /** The name a picked or dropped file installs under. */
    fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.isLetterOrDigit() || it in "-_." }
            .ifEmpty { "region$SUFFIX" }
            .let { if (it.endsWith(SUFFIX)) it else "$it$SUFFIX" }

    /**
     * Refuses a file that is not Mapsforge data, or is not all there.
     *
     * The header declares the file's own length, so a copy cut short — a download still
     * in progress when the drop folder was swept, a cable pulled mid-import — is caught
     * here rather than by VTM at the start of a drive, where it shows as an empty pane.
     */
    @Throws(IOException::class)
    fun checkHeader(file: File) {
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(MAGIC.length)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != MAGIC) throw IOException("not a Mapsforge map file")
            raf.readInt() // remaining header size
            raf.readInt() // file version
            val declared = raf.readLong()
            val actual = raf.length()
            if (declared != actual) {
                throw IOException("map file is incomplete: $actual of $declared bytes")
            }
        }
    }

    private const val MAGIC = "mapsforge binary OSM"

    const val SUFFIX = ".map"

    /**
     * A ceiling rather than a judgement about regions: it stops a mis-picked file from
     * filling the phone, and a country extract fits well inside it.
     */
    const val SIZE_LIMIT = 4L * 1024 * 1024 * 1024

    /** Below this there cannot be a Mapsforge header, let alone tiles. */
    private const val HEADER_MINIMUM = 64
}
