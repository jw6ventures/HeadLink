package com.jw6ventures.headlink

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.io.File
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Installs report persistence for the whole process, before any activity or service runs,
 * and prepares the source identity so no key is generated while a head unit waits.
 *
 * It also turns the optional call controls' in-call service off (P7.9): a process that is
 * only now starting has no session, so a component still enabled was left on by a crash or
 * a killed process, and must not let Telecom wake this app for calls between drives.
 */
class SourceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The route preview's process ([PreviewActivity]) draws a map and nothing else. It
        // must not load or create the source identity, write reports, or touch the call
        // service: those belong to the one process that runs sessions.
        if (getProcessName().endsWith(PreviewActivity.PROCESS_SUFFIX)) return
        ReportPersistence.install(this)
        // A destination and route set before the process was last closed (on the phone,
        // before walking out to the truck) come back, off the main thread.
        NavigationStore.install(this)
        // Live traffic's loop (P9): idle, and making no connection, until a route is
        // followed with traffic on and a key saved.
        Traffic.install(this)
        IdentityWork.ensure(this, replace = false)
        CallControl.resetAtStartup(this)
        LegacyData.clear(this)
    }
}

/**
 * Clears what earlier versions kept for the map and routing data they read, now that
 * OsmAnd's maps folder ([ObfLibrary]) is the only source: the Mapsforge maps under
 * `files/maps`, BRouter's tiles and profile under `files/routing`, the drop folder's
 * read+write grant, and the settings keys of the engine and map source switches.
 *
 * Runs at every start of the main process, with no marker: each step finds nothing to do
 * once done, so a start cut short just finishes the job next time. The grant goes at once,
 * before any screen can open a folder chooser: a folder chosen again as the maps folder
 * adds its read to the same persisted entry, which a later release would take with it.
 * The files and settings keys go off the main thread. A failure is noted and left for the
 * next start; it never stops the app.
 */
object LegacyData {
    fun clear(context: Context) {
        val app = context.applicationContext
        val grants = try {
            releaseDropGrants(app)
        } catch (e: Exception) {
            SessionController.note("releasing the old drop folder grant failed: $e")
            0
        }
        Thread({
            try {
                val removed = buildList {
                    for (name in listOf("maps", "routing")) {
                        val directory = File(app.filesDir, name)
                        if (directory.exists() && directory.deleteRecursively()) add("files/$name")
                    }
                    if (grants > 0) add("drop folder grant")
                }
                AppSettings.forgetLegacy(app)
                if (removed.isNotEmpty()) SessionController.note("removed old map data: ${removed.joinToString(", ")}")
            } catch (e: Exception) {
                SessionController.note("removing old map data failed: $e")
            }
        }, "legacy-data").start()
    }

    /**
     * Lets go of every folder grant that reads and writes, and says how many. The drop
     * folder's is the only such grant: the maps folder's reads only, and the speech
     * model's is on a single file. A cheap listing and a binder call or two, so the main
     * thread can wait for it.
     */
    private fun releaseDropGrants(context: Context): Int {
        val resolver = context.contentResolver
        val drop = resolver.persistedUriPermissions
            .filter { DocumentsContract.isTreeUri(it.uri) && it.isReadPermission && it.isWritePermission }
        for (grant in drop) {
            resolver.releasePersistableUriPermission(
                grant.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        return drop.size
    }
}

/**
 * Source identity preparation (spec §7.4) off the main thread: load or create the
 * app-private identity and run the in-memory TLS self-test. Results land in
 * [SessionController] on the main thread. Replacement (trial C4) is refused while an
 * attempt is active.
 */
object IdentityWork {
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "source-identity").apply { isDaemon = true } }

    /** App-private directory holding the identity and head-unit pins; excluded from backup. */
    fun directory(context: Context): File = File(context.filesDir, "identity")

    fun trust(context: Context): TrustStore = TrustStore(directory(context))

    /** Imports only while idle; an invalid candidate leaves the existing identity intact. */
    fun importIdentity(context: Context, uri: android.net.Uri): Boolean {
        if (SessionController.identityBusy || SessionController.stage != Stage.IDLE) return false
        val app = context.applicationContext
        val dir = directory(app)
        SessionController.identityWorkStarted()
        worker.execute {
            val failure = try {
                app.contentResolver.openInputStream(uri)?.use { IdentityImport.install(dir, it) }
                    ?: throw java.io.IOException("cannot open identity")
                null
            } catch (e: Exception) {
                e.message?.replace('\n', ' ') ?: "identity import failed"
            }
            val report = NativeBridge.ensureIdentity(dir.absolutePath, false)
            main.post {
                SessionController.identityLoaded(report)
                SessionController.note(if (failure == null) "supplied source identity installed" else "identity import failed: $failure")
                android.widget.Toast.makeText(app, failure ?: "Source identity installed", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        return true
    }

    /** Main thread. Returns false if work is already running or an attempt is active. */
    fun ensure(context: Context, replace: Boolean): Boolean {
        if (SessionController.identityBusy || (replace && SessionController.stage != Stage.IDLE)) return false
        val dir = directory(context).absolutePath
        SessionController.identityWorkStarted()
        worker.execute {
            val report = NativeBridge.ensureIdentity(dir, replace)
            main.post { SessionController.identityLoaded(report) }
        }
        return true
    }
}

/**
 * Saves the current attempt's report to `files/reports/` whenever session state changes,
 * independently of whether the activity exists, and appends a crash section for any
 * uncaught JVM exception before the process dies. A native crash cannot be recorded;
 * the last saved snapshot remains. Reports are app-private and excluded from backup.
 */
object ReportPersistence {
    private var app: Application? = null
    private var usb: UsbManager? = null
    private var filter: AccessoryFilter? = null
    private var scheduled = false

    var store: ReportStore? = null
        private set

    /** Summary of the newest report saved before this process started, if it was left incomplete. */
    var previousIncomplete: String? = null
        private set

    fun reportName(attempt: Attempt): String = ReportStore.fileName(attempt.startedUtc, attempt.sessionUuid)

    fun install(application: Application) {
        if (store != null) return
        val reports = ReportStore(File(application.filesDir, "reports"))
        app = application
        usb = application.getSystemService(UsbManager::class.java)
        filter = AccessoryFilter.load(application.resources)
        store = reports
        previousIncomplete = reports.list().firstOrNull()?.let { name ->
            val text = reports.read(name).orEmpty()
            when {
                text.lineSequence().contains("[crash]") -> "$name (app crashed)"
                !text.lineSequence().contains("attempt_complete=true") -> "$name (app ended before the attempt finished)"
                else -> null
            }
        }
        val main = Handler(Looper.getMainLooper())
        // Coalesce a burst of state changes into one snapshot on the main thread.
        SessionController.addListener {
            if (!scheduled && SessionController.attempt != null) {
                scheduled = true
                main.post {
                    scheduled = false
                    snapshot()?.let { (name, text) -> reports.save(name, text) }
                }
            }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val (name, text) = snapshot() ?: (ReportStore.fileName(Instant.now(), "crash") to "")
                reports.saveNow(name, text + ReportStore.crashSection(thread.name, error, Instant.now()))
            } catch (ignored: Throwable) {
                // Never prevent the platform handler from running.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Current attempt's report name and text. Composed from main-thread state. */
    private fun snapshot(): Pair<String, String>? {
        val attempt = SessionController.attempt ?: return null
        val context = app ?: return null
        val text =
            try {
                SessionReport.compose(context, usb ?: return null, filter ?: return null)
            } catch (e: RuntimeException) {
                "report_compose_failed=$e\n"
            }
        return reportName(attempt) to text
    }
}
