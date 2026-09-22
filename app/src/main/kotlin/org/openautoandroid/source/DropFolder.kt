package org.openautoandroid.source

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A folder the operator drops map and routing files into; the app moves them into its
 * own storage. [DropRules] decides, this carries it out through Android's document API.
 *
 * The folder is one the operator picked and granted — no storage permission, and the
 * grant itself is the only record of which folder it is: Android keeps it across
 * restarts, so there is no setting to fall out of step with it. It is swept when the
 * connection screen comes to the front, when it is first chosen, and again shortly after
 * a sweep that found a file still arriving. Only files directly in the folder are read.
 */
object DropFolder {
    private val main = Handler(Looper.getMainLooper())
    private val queued = AtomicBoolean()
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread({
                // Copying a few hundred megabytes must not compete with a projection.
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "drop-folder").apply { isDaemon = true }
        }

    /** A sweep is queued or running. Main thread. */
    var busy = false
        private set

    /** One line about the last sweep, for the connection screen. Main thread. */
    var lastSweep: String? = null
        private set

    /** The granted folder, or null. */
    fun folder(context: Context): Uri? =
        context.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission && it.isWritePermission }
            ?.uri

    /** A readable name for the folder: the provider's own path, such as `primary:Download/OpenAuto`. */
    fun describe(uri: Uri): String =
        try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: IllegalArgumentException) {
            uri.toString()
        }

    /** Makes [tree] the drop folder, letting go of any earlier one, and sweeps it. */
    fun choose(context: Context, tree: Uri) {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.persistedUriPermissions
            .filter { it.uri != tree }
            .forEach { resolver.releasePersistableUriPermission(it.uri, flags) }
        resolver.takePersistableUriPermission(tree, flags)
        SessionController.note("drop folder set to ${describe(tree)}")
        sweep(context)
    }

    /** Sweeps the folder on the worker thread, unless a sweep is already waiting to start. */
    fun sweep(context: Context) {
        val app = context.applicationContext
        if (folder(app) == null) return
        if (!queued.compareAndSet(false, true)) return
        busy = true
        worker.execute {
            queued.set(false)
            val outcome = run(app)
            main.post {
                busy = queued.get()
                lastSweep = outcome.summary
                outcome.messages.forEach(SessionController::note)
                SessionController.note("drop folder: ${outcome.summary}")
                // Something was still arriving; look again once it has had time to settle.
                if (outcome.waiting > 0) main.postDelayed({ sweep(app) }, DropRules.QUIET_MS + 1_000L)
            }
        }
    }

    private class Outcome(val summary: String, val messages: List<String>, val waiting: Int)

    private fun run(context: Context): Outcome {
        val tree = folder(context) ?: return Outcome("no folder chosen", emptyList(), 0)
        val resolver = context.contentResolver
        val messages = ArrayList<String>()
        var imported = 0
        var failed = 0
        var waiting = 0
        val maps = MapData.directory(context)
        val root = Routing.root(context)
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val entries = ArrayList<Entry>()
            resolver.query(children, COLUMNS, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (name != null && cursor.getString(3) != DocumentsContract.Document.MIME_TYPE_DIR) {
                        entries += Entry(
                            cursor.getString(0),
                            name,
                            if (cursor.isNull(2)) -1L else cursor.getLong(2),
                            if (cursor.isNull(4)) 0L else cursor.getLong(4),
                        )
                    }
                }
            } ?: return Outcome("folder unavailable: ${describe(tree)}", emptyList(), 0)
            val now = System.currentTimeMillis()
            for (entry in entries) {
                val kind = DropRules.kind(entry.name) ?: continue
                val target = DropRules.installedName(kind, entry.name)?.let {
                    File(if (kind == DropKind.MAP) maps else RoutingData.segments(root), it)
                }
                val document = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
                when (DropRules.decide(entry.name, entry.size, entry.modifiedMs, now, target)) {
                    DropAction.IGNORE -> Unit
                    DropAction.WAIT -> waiting++
                    DropAction.ALREADY -> remove(resolver, document, entry.name, messages)
                    DropAction.IMPORT -> try {
                        val installed = resolver.openInputStream(document)?.use { stream ->
                            when (kind) {
                                DropKind.MAP -> MapData.installInto(maps, entry.name, stream)
                                DropKind.ROUTING -> {
                                    RoutingData.installProfile(RoutingData.profiles(root))
                                    RoutingData.install(RoutingData.segments(root), RoutingData.profiles(root), entry.name, stream)
                                }
                            }
                        } ?: throw IOException("no input stream")
                        imported++
                        messages += "drop folder: installed ${installed.name} (${installed.length()} bytes)"
                        remove(resolver, document, entry.name, messages)
                    } catch (e: IOException) {
                        failed++
                        messages += "drop folder: ${entry.name} left in place: ${e.message}"
                    }
                }
            }
        } catch (e: RuntimeException) {
            // A folder deleted or its grant withdrawn in Settings: say so, keep running.
            return Outcome("folder unavailable: ${e.message}", messages, 0)
        }
        val summary = buildString {
            append("$imported installed")
            if (failed > 0) append(", $failed refused")
            if (waiting > 0) append(", $waiting still arriving")
        }
        return Outcome(summary, messages, waiting)
    }

    /** Deletes the dropped original once its copy is installed; a refusal is noted, not fatal. */
    private fun remove(resolver: android.content.ContentResolver, document: Uri, name: String, messages: MutableList<String>) {
        val deleted = try {
            DocumentsContract.deleteDocument(resolver, document)
        } catch (e: Exception) {
            // FileNotFoundException, or a provider's SecurityException.
            false
        }
        if (!deleted) messages += "drop folder: $name installed but could not be removed from the folder"
    }

    private class Entry(val id: String, val name: String, val size: Long, val modifiedMs: Long)

    private val COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
}
