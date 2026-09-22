package org.openautoandroid.source

import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Bounded, app-private report files so an attempt's evidence survives an app crash or
 * process death. Pure JVM with no Android API, so tools/jni-host-test exercises it.
 *
 * [save] is nonblocking and coalesces: only the latest queued text per file is written.
 * Writes go to a temporary file that is renamed over the report, so a reader never sees
 * a partial report. Only the newest [retain] reports are kept.
 */
class ReportStore(private val directory: File, private val retain: Int = DEFAULT_RETAIN) {
    private val queueLock = Any()
    private val writeLock = Any()
    private val pending = LinkedHashMap<String, String>()
    private val writer: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "report-store").apply { isDaemon = true } }

    /** Last write failure, for display; null after a successful write. */
    @Volatile
    var lastError: String? = null
        private set

    /** Queues [text] as the latest content of report [name]. */
    fun save(name: String, text: String) {
        requireName(name)
        val schedule = synchronized(queueLock) {
            val idle = pending.isEmpty()
            pending[name] = text
            idle
        }
        if (schedule) writer.execute { drain() }
    }

    /** Writes everything queued, then [text] for [name], on the calling thread. For crash handlers. */
    fun saveNow(name: String, text: String) {
        requireName(name)
        drain()
        synchronized(writeLock) {
            synchronized(queueLock) { pending.remove(name) }
            write(name, text)
        }
    }

    /** Waits until queued writes are done. */
    fun flush() {
        writer.submit { drain() }.get()
    }

    /** Saved report names, newest first. */
    fun list(): List<String> =
        directory.listFiles { file -> NAME.matches(file.name) }.orEmpty().map { it.name }.sortedDescending()

    fun read(name: String): String? {
        requireName(name)
        return try {
            File(directory, name).readText(Charsets.UTF_8)
        } catch (e: IOException) {
            null
        }
    }

    /** All saved reports, newest first, each under a header line. */
    fun exportAll(): String =
        buildString {
            val names = list()
            appendLine("saved_reports=${names.size}")
            for (name in names) {
                appendLine()
                appendLine("===== $name =====")
                append(read(name) ?: "unreadable\n")
            }
        }

    private fun drain() {
        while (true) {
            synchronized(writeLock) {
                val (name, text) = synchronized(queueLock) {
                    val next = pending.entries.firstOrNull() ?: return
                    pending.remove(next.key)
                    next.key to next.value
                }
                write(name, text)
            }
        }
    }

    /** Caller holds [writeLock], so writes for one name happen in queue order. */
    private fun write(name: String, text: String) {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")
            val temporary = File(directory, "$name.tmp")
            temporary.writeText(text, Charsets.UTF_8)
            if (!temporary.renameTo(File(directory, name))) {
                temporary.delete()
                throw IOException("rename failed for $name")
            }
            list().drop(retain).forEach { File(directory, it).delete() }
            lastError = null
        } catch (e: IOException) {
            lastError = e.toString()
        } catch (e: SecurityException) {
            lastError = e.toString()
        }
    }

    companion object {
        const val DEFAULT_RETAIN = 50

        /** Longest crash section appended to a report. */
        const val CRASH_LIMIT = 32 * 1024

        /** `report-<UTC start>-<8 id chars>.txt`; names sort chronologically. */
        val NAME = Regex("""report-\d{8}T\d{6}Z-[0-9a-z]{8}\.txt""")
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        fun fileName(started: Instant, id: String): String {
            val suffix = id.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(8).padEnd(8, '0')
            return "report-${STAMP.format(started)}-$suffix.txt"
        }

        /** Report section for an uncaught exception, bounded to [CRASH_LIMIT] characters. */
        fun crashSection(threadName: String, error: Throwable, at: Instant): String {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val body = "[crash]\ncrash_utc=$at\ncrash_thread=$threadName\n$trace"
            return if (body.length <= CRASH_LIMIT) body else body.take(CRASH_LIMIT) + "\n[crash truncated]\n"
        }

        private fun requireName(name: String) {
            require(NAME.matches(name)) { "invalid report name: $name" }
        }
    }
}
