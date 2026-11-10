package com.jw6ventures.headlink

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which files in the maps folder are OsmAnd maps worth opening, what identifies one
 * version of a file, and the card's status line (docs/navigation.md). Pure Kotlin, so the
 * host test checks the rules; [ObfLibrary] carries them out through Android's document API.
 */
object ObfRules {
    /**
     * An OsmAnd map with addresses or POIs: `*.obf` directly in the folder, not contour
     * lines (`*.srtmf.obf`), Wikipedia (`*.wiki.obf`) or the world basemap, which holds
     * no addresses. A name not ending `.obf` — OsmAnd's `.obf.download`, a browser's
     * `.crdownload` — is never opened, so a half-written file is never read.
     */
    fun readable(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        if (!lower.endsWith(".obf") || lower.length <= ".obf".length) return false
        if (lower.startsWith(".")) return false
        if (lower.endsWith(".srtmf.obf") || lower.endsWith(".srtm.obf") || lower.endsWith(".wiki.obf")) return false
        if (lower.startsWith("world_basemap")) return false
        return true
    }

    /**
     * One version of one file. OsmAnd replaces a map file whole when it updates it, so a
     * changed size or modified time means a different file, and its index is rebuilt.
     */
    data class FileKey(val name: String, val size: Long, val modifiedMs: Long)

    /**
     * The region a file covers, from OsmAnd's naming: `Us_texas_austin_northamerica.obf`
     * is "Texas", `Netherlands_noord-holland_europe.obf` is "Netherlands". Null when the
     * name does not follow it.
     */
    fun region(name: String): String? {
        val base = name.substringBefore('.')
        val parts = base.split('_').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val word = if (parts[0].equals("us", ignoreCase = true) || parts[0].equals("ca", ignoreCase = true)) parts[1] else parts[0]
        return word.split('-').joinToString(" ") { part ->
            part.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }.ifEmpty { null }
    }

    /** What the last look at the folder found: names never leave the phone, but counts do. */
    data class FolderState(
        /** A folder is granted, or (debug) lab files are present. */
        val chosen: Boolean,
        /** The folder could not be listed: deleted, or its grant withdrawn. */
        val unreadable: Boolean = false,
        /** Files that pass [readable]. */
        val candidates: Int = 0,
        /** Files opened and in use. */
        val open: Int = 0,
        /** Files in a newer OBF layout than the reader knows. */
        val newer: Int = 0,
        /** Files that could not be opened or read for any other reason. */
        val failed: Int = 0,
        /** Regions of the open files, [region]'s names. */
        val regions: List<String> = emptyList(),
        /** The newest open file's date, ms since the epoch, or 0 for none known. */
        val newestMs: Long = 0,
    )

    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

    /** The card's status line (docs/navigation.md, "OsmAnd maps: search and places"). */
    fun status(state: FolderState, zone: ZoneId): String {
        if (!state.chosen) return "No folder chosen"
        if (state.unreadable) return "Folder unreadable"
        if (state.candidates == 0) return "No OsmAnd maps in this folder"
        val parts = ArrayList<String>()
        if (state.open > 0) {
            parts += maps(state.open)
            val regions = state.regions.distinct().sorted()
            when {
                regions.isEmpty() -> Unit
                regions.size <= 2 -> parts += regions.joinToString(", ")
                else -> parts += "${regions.size} regions"
            }
            if (state.newestMs > 0) {
                parts += "newest " + DATE.format(Instant.ofEpochMilli(state.newestMs).atZone(zone))
            }
        }
        if (state.newer > 0) parts += "${maps(state.newer)} in a newer OBF format than this app reads"
        if (state.failed > 0) parts += "${maps(state.failed)} could not be read"
        return parts.joinToString(" · ")
    }

    private fun maps(n: Int): String = if (n == 1) "1 map" else "$n maps"

    /**
     * Which persisted grant is which: the drop folder's reads and writes, the
     * maps folder's only reads. Each folder's choice gives up only earlier grants of its
     * own kind, so choosing one never drops the other.
     */
    fun isDropGrant(read: Boolean, write: Boolean): Boolean = read && write

    fun isMapsGrant(read: Boolean, write: Boolean): Boolean = read && !write

    /** How long a listing is trusted before [ObfLibrary.sources] looks at the folder again. */
    const val RELIST_MS = 15_000L
}

/**
 * The read-only maps folder (docs/navigation.md, P11.1): OsmAnd's `.obf` files, read in
 * place through a persisted **read-only** grant, kept apart from [DropFolder]'s read+write
 * one. Opens each file through [ParcelFileDescriptor] as a [FileChannel] for positional
 * reads, and keeps it open while a projection is attached or the connection screen is in
 * front ([hold]); closes everything otherwise.
 *
 * Open sources are cached by [ObfRules.FileKey]: a listing is cheap, and a file whose
 * name, size and modified time are unchanged is not reopened. A file OsmAnd replaced gets
 * a new key and is opened afresh. A file that cannot be read — a newer layout, a short
 * file — is counted and shown on the card, never thrown.
 *
 * Debug builds also read `filesDir/lab-obf/` the same way: the emulator lab pushes files
 * there because a folder grant cannot be scripted.
 *
 * File names stay on the phone: [report] carries counts only.
 */
object ObfLibrary {
    private const val LAB_DIR = "lab-obf"

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "obf-library").apply { isDaemon = true } }
    private val refreshQueued = AtomicBoolean()

    private class Opened(
        val key: ObfRules.FileKey,
        val source: ObfSource,
        val handle: Closeable,
        val dateMs: Long,
        val region: String?,
        /** Where OsmAnd's own reader can open the same file: see [routingMaps]. */
        val path: String,
    )

    private enum class Rejection { NEWER, FAILED }

    /**
     * Held for the whole of a listing, so only one runs at a time and nothing is closed
     * halfway through one. Taken before [lock], never while holding it.
     */
    private val scanLock = Any()

    /** Held only briefly, for the fields below: the main thread takes it. */
    private val lock = Any()
    // Guarded by [lock].
    private val opened = LinkedHashMap<ObfRules.FileKey, Opened>()
    private val rejected = HashMap<ObfRules.FileKey, Rejection>()
    private var state: ObfRules.FolderState? = null
    private var listedAt = 0L
    private val holders = HashSet<String>()

    // Report counts, guarded by [lock].
    private var listings = 0
    private var opens = 0
    private var openFailures = 0
    private var newerFiles = 0
    private var closes = 0

    /** Called on the main thread after a listing changed what [status] says. */
    @Volatile
    var listener: (() -> Unit)? = null

    /** The granted maps folder: a persisted grant to read that does not also write. */
    fun folder(context: Context): Uri? =
        context.contentResolver.persistedUriPermissions
            .firstOrNull { ObfRules.isMapsGrant(it.isReadPermission, it.isWritePermission) }
            ?.uri

    /**
     * Makes [tree] the maps folder: takes a persisted read-only grant on it, lets go of
     * any earlier read-only grant (never the drop folder's read+write one), and reads it.
     * False, changing nothing, when [tree] is already the drop folder: its read+write
     * grant would hide the read-only one. Main thread; throws [SecurityException] when
     * the grant cannot be kept.
     */
    fun choose(context: Context, tree: Uri): Boolean {
        val app = context.applicationContext
        val resolver = app.contentResolver
        val grants = resolver.persistedUriPermissions
        if (grants.any { it.uri == tree && ObfRules.isDropGrant(it.isReadPermission, it.isWritePermission) }) return false
        grants
            .filter { it.uri != tree && ObfRules.isMapsGrant(it.isReadPermission, it.isWritePermission) }
            .forEach { resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        SessionController.note("maps folder chosen")
        // Everything open came from the old folder; the listing after it opens the new one.
        // One task, so a refresh queued before the choice cannot list the old folder
        // after the close and leave the card counting files that are shut.
        worker.execute {
            close()
            relist(app)
        }
        return true
    }

    /**
     * The card's status line. The last listing's; before the first one it
     * starts a listing off the main thread and says so, and [listener] fires when done.
     */
    fun status(context: Context): String {
        val current = synchronized(lock) { state }
        if (current == null) {
            refresh(context)
            return if (folder(context) == null && labFiles(context).isEmpty()) "No folder chosen" else context.getString(R.string.osmand_maps_reading)
        }
        return ObfRules.status(current, ZoneId.systemDefault())
    }

    /**
     * The open sources, listing the folder again when the last listing is older than
     * [ObfRules.RELIST_MS]. Opens files, so call it off the main thread; on the main
     * thread it returns what is already open and lists in the background. With no
     * [hold] it opens nothing: a query left queued when a projection ends must not reopen
     * the files its release just closed.
     */
    fun sources(context: Context): List<ObfSource> {
        val app = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refresh(app)
            return synchronized(lock) { opened.values.map { it.source } }
        }
        // The listing itself runs outside [lock], so the main thread's [status] and
        // [hasSources] never wait on the provider or on a file being opened.
        if (wanted()) synchronized(scanLock) { if (wanted()) scan(app) }
        return synchronized(lock) { opened.values.map { it.source } }
    }

    /**
     * The open maps as [OsmAndRouter] opens them, listed by the same rule as [sources]; off
     * the main thread. OsmAnd's reader takes a `RandomAccessFile`, which needs a path, and a
     * file from the maps folder has only a descriptor: its `/proc/self/fd` link is a path to
     * the same file for as long as this library keeps the descriptor open. A lab file is its
     * own path. The id is the file's key, so a reader opened for one file is never reused for
     * another that was later given the same descriptor number.
     */
    fun routingMaps(context: Context): List<OsmAndRouter.MapFile> {
        val app = context.applicationContext
        if (wanted()) synchronized(scanLock) { if (wanted()) scan(app) }
        return synchronized(lock) {
            opened.values.map { OsmAndRouter.MapFile("${it.key.name}|${it.key.size}|${it.key.modifiedMs}", it.key.name, it.path) }
        }
    }

    /** Held, and the last listing is missing or old. */
    private fun wanted(): Boolean = synchronized(lock) {
        holders.isNotEmpty() &&
            (state == null || listedAt == 0L || SystemClock.elapsedRealtime() - listedAt >= ObfRules.RELIST_MS)
    }

    /** Whether any file is open now. Never lists or opens; any thread. */
    fun hasSources(): Boolean = synchronized(lock) { opened.isNotEmpty() }

    /** Lists the folder again on the worker thread, then tells [listener]. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        if (!refreshQueued.compareAndSet(false, true)) return
        worker.execute {
            refreshQueued.set(false)
            relist(app)
        }
    }

    // On the worker thread.
    private fun relist(app: Context) {
        synchronized(scanLock) { scan(app) }
        main.post { listener?.invoke() }
    }

    /**
     * Marks [holder] (`projection`, `screen`) as wanting the files open, or no longer.
     * When the last holder lets go, every file is closed off the main thread.
     */
    fun hold(holder: String, on: Boolean) {
        val empty = synchronized(lock) {
            if (on) holders.add(holder) else holders.remove(holder)
            holders.isEmpty()
        }
        if (!on && empty) worker.execute { close(unlessHeld = true) }
    }

    /**
     * Closes every open file, or none when [unlessHeld] and a holder has come back since.
     * The next [sources] lists and opens them again; [status] keeps saying what the last
     * listing found, so a render never reopens anything. Waits for a listing in progress,
     * so it never runs between a file being opened and being recorded; off the main thread.
     */
    fun close(unlessHeld: Boolean = false) {
        synchronized(scanLock) {
            synchronized(lock) {
                if (unlessHeld && holders.isNotEmpty()) return
                for (entry in opened.values) closeQuietly(entry.handle)
                if (opened.isNotEmpty()) closes++
                opened.clear()
                rejected.clear()
                listedAt = 0L
            }
        }
    }

    /** `[routing]` lines: counts only, never a file name or a region. */
    fun report(): String = synchronized(lock) {
        "obf_files_open=${opened.size} obf_newer_format=${state?.newer ?: 0} obf_unreadable=${state?.failed ?: 0} " +
            "obf_open_failures=$openFailures obf_listings=$listings obf_opens=$opens obf_newer_seen=$newerFiles " +
            "obf_closes=$closes\n"
    } + PhoneSearch.report()

    // Under [scanLock], not [lock]: the provider query and the opening run unlocked, and
    // [lock] is taken only to read and change the fields.
    private fun scan(context: Context) {
        synchronized(lock) {
            listings++
            listedAt = SystemClock.elapsedRealtime()
        }
        val tree = folder(context)
        val found = ArrayList<Pair<ObfRules.FileKey, () -> Triple<FileChannel, Closeable, String>>>()
        var unreadable = false
        if (tree != null) {
            try {
                val resolver = context.contentResolver
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val cursor = resolver.query(children, COLUMNS, null, null, null)
                if (cursor == null) unreadable = true
                cursor?.use {
                    while (it.moveToNext()) {
                        val name = it.getString(1) ?: continue
                        if (it.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        if (!ObfRules.readable(name)) continue
                        val id = it.getString(0)
                        val key = ObfRules.FileKey(
                            name,
                            if (it.isNull(2)) -1L else it.getLong(2),
                            if (it.isNull(4)) 0L else it.getLong(4),
                        )
                        val document = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        found += key to {
                            val pfd = resolver.openFileDescriptor(document, "r") ?: throw IOException("no descriptor")
                            val stream = FileInputStream(pfd.fileDescriptor)
                            Triple(
                                stream.channel,
                                Closeable {
                                    closeQuietly(stream)
                                    closeQuietly(pfd)
                                },
                                "/proc/self/fd/${pfd.fd}",
                            )
                        }
                    }
                }
            } catch (e: RuntimeException) {
                // A folder deleted, or its grant withdrawn in Settings: say so, keep running.
                unreadable = true
            }
        }
        for (file in labFiles(context)) {
            val key = ObfRules.FileKey(file.name, file.length(), file.lastModified())
            found += key to {
                val stream = FileInputStream(file)
                Triple(stream.channel, Closeable { closeQuietly(stream) }, file.absolutePath)
            }
        }
        val wanted = found.map { it.first }.toSet()
        val fresh = synchronized(lock) {
            // Files gone or replaced: their key no longer appears.
            val gone = opened.keys.filter { it !in wanted }
            for (key in gone) opened.remove(key)?.let { closeQuietly(it.handle) }
            rejected.keys.retainAll(wanted)
            found.filter { (key, _) -> key !in opened && key !in rejected }
        }
        // Only this thread changes [opened] and [rejected] while [scanLock] is held, so the
        // files are opened unlocked and recorded after.
        val added = ArrayList<Opened>()
        val refused = ArrayList<Pair<ObfRules.FileKey, Rejection>>()
        for ((key, open) in fresh) {
            var handle: Closeable? = null
            try {
                val (channel, closer, path) = open()
                handle = closer
                val source = ObfReaders.open(channel, key.name)
                added += Opened(key, source, closer, ObfReaders.dateMs(source).takeIf { it > 0 } ?: key.modifiedMs, ObfRules.region(key.name), path)
            } catch (e: ObfNewerFormatException) {
                handle?.let(::closeQuietly)
                refused += key to Rejection.NEWER
            } catch (e: Exception) {
                // IOException, a provider's SecurityException, a malformed file.
                handle?.let(::closeQuietly)
                refused += key to Rejection.FAILED
                SessionController.note("maps folder: a file could not be opened: ${e.javaClass.simpleName}")
            }
        }
        val labOnly = tree == null
        synchronized(lock) {
            for (entry in added) opened[entry.key] = entry
            opens += added.size
            for ((key, why) in refused) {
                rejected[key] = why
                if (why == Rejection.NEWER) newerFiles++ else openFailures++
            }
            state = state(tree != null, labOnly, unreadable, found.size)
        }
    }

    // Under [lock].
    private fun state(granted: Boolean, labOnly: Boolean, unreadable: Boolean, candidates: Int): ObfRules.FolderState =
        ObfRules.FolderState(
            chosen = granted || candidates > 0,
            unreadable = unreadable && !labOnly && candidates == 0,
            candidates = candidates,
            open = opened.size,
            newer = rejected.values.count { it == Rejection.NEWER },
            failed = rejected.values.count { it == Rejection.FAILED },
            regions = opened.values.mapNotNull { it.region },
            newestMs = opened.values.maxOfOrNull { it.dateMs } ?: 0L,
        )

    /** Debug builds only: the `.obf` files in `filesDir/lab-obf`, which the emulator lab pushes. */
    private fun labFiles(context: Context): List<File> {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return emptyList()
        val dir = File(context.filesDir, LAB_DIR)
        return dir.listFiles()?.filter { it.isFile && ObfRules.readable(it.name) }?.sortedBy { it.name } ?: emptyList()
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (e: IOException) {
            // Closing a read-only descriptor; nothing to save.
        }
    }

    private val COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
}

/** A file in an OBF layout newer than the reader knows: counted and shown, never misread. */
class ObfNewerFormatException(message: String) : IOException(message)

/** Where [ObfLibrary] meets the reader: kept to one place so the reader's API is named once. */
private object ObfReaders {
    /** The OBF version OsmAnd's own reader accepts (`BinaryMapIndexReader`); a later one is refused, not misread. */
    private const val KNOWN_VERSION = 2

    /**
     * Opens [channel] as a source named [name]: the table of contents is read here. A file
     * whose closing version does not match its opening one is cut short, and refused.
     */
    fun open(channel: FileChannel, name: String): ObfSource {
        val file = ObfFile(channel, name)
        val toc = file.toc
        if (toc.version > KNOWN_VERSION) throw ObfNewerFormatException("OBF version ${toc.version}")
        if (!toc.complete) throw IOException("OBF file incomplete")
        return file
    }

    /** The file's own creation date, ms, or 0 when the reader gives none. */
    fun dateMs(source: ObfSource): Long = (source as? ObfFile)?.toc?.dateCreated ?: 0L
}
