package org.openautoandroid.source

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Installs report persistence for the whole process, before any activity or service runs,
 * and prepares the source identity so no key is generated while a head unit waits.
 */
class SourceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ReportPersistence.install(this)
        IdentityWork.ensure(this, replace = false)
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
