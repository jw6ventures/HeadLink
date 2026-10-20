package com.jw6ventures.headlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Breezy Weather's content provider through the ContentResolver (plan §1, §3): the Android
 * half of [WeatherSource]. Each call is one query, closed before it returns; nothing is
 * cached here. Worker thread only.
 */
class WeatherProvider(context: Context) : WeatherSource {
    private val app = context.applicationContext

    override fun owner(): String? =
        try {
            app.packageManager.resolveContentProvider(WeatherRules.AUTHORITY, PackageManager.ComponentInfoFlags.of(0))?.packageName
        } catch (e: RuntimeException) {
            null
        }

    override fun permitted(): Boolean = app.checkSelfPermission(WeatherRules.PERMISSION) == PackageManager.PERMISSION_GRANTED

    override fun version(): Pair<Int, Int>? = query(uri("version"), null) { cursor ->
        if (!cursor.moveToFirst()) null else {
            val major = cursor.getInt(cursor.getColumnIndexOrThrow("major"))
            val minor = cursor.getColumnIndex("minor").takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: 0
            major to minor
        }
    }

    override fun places(): List<WeatherPlace> = query(uri("locations").buildUpon().appendQueryParameter("limit", "${WeatherRules.LOCATION_LIMIT}").build(), null) { cursor ->
        val list = ArrayList<WeatherPlace>()
        val id = cursor.getColumnIndexOrThrow("id")
        val lat = cursor.getColumnIndexOrThrow("latitude")
        val lon = cursor.getColumnIndexOrThrow("longitude")
        val current = cursor.getColumnIndex("is_current_position")
        val names = listOf("custom_name", "city", "district").map { cursor.getColumnIndex(it) }
        while (cursor.moveToNext()) {
            val key = if (cursor.isNull(id)) continue else cursor.getString(id)
            val latitude = cursor.getDouble(lat)
            val longitude = cursor.getDouble(lon)
            if (!latitude.isFinite() || !longitude.isFinite()) continue
            val name = names.firstNotNullOfOrNull { index ->
                if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)?.trim()?.takeIf { it.isNotEmpty() }
            } ?: ""
            list += WeatherPlace(key, latitude, longitude, current >= 0 && !cursor.isNull(current) && cursor.getInt(current) > 0, name)
        }
        list
    } ?: emptyList()

    override fun blob(id: String): ByteArray? = query(weatherUri(), "id=$id") { cursor ->
        if (!cursor.moveToFirst()) null else {
            val column = cursor.getColumnIndexOrThrow("weather")
            if (cursor.isNull(column)) null else cursor.getBlob(column)
        }
    }

    private fun <T> query(uri: Uri, selection: String?, read: (Cursor) -> T): T? =
        app.contentResolver.query(uri, null, selection, null, null)?.use(read)

    companion object {
        fun uri(path: String): Uri = Uri.parse("content://${WeatherRules.AUTHORITY}/$path")

        /** Plan §1: no minute-by-minute rain and no normals; units left at Breezy's own. */
        fun weatherUri(): Uri = uri("weather").buildUpon()
            .appendQueryParameter("withMinutely", "false")
            .appendQueryParameter("withNormals", "false")
            .build()
    }
}

/**
 * Breezy's update notifier (plan §3): a cue to read again, with no weather in it. Exported
 * and guarded by nothing, since Breezy sends it with no permission; a forged one can only
 * cause a harmless extra read, and only while a projection is attached with weather on.
 */
class WeatherNotifierReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WeatherRules.NOTIFIER_ACTION) return
        // Delivered on the main thread; the feed ignores it between projections.
        Weather.cue(WeatherCue.NOTIFIER)
    }
}

/**
 * The one [WeatherFeed] (plan §3), running while a projection is attached: [start] reads
 * when it begins and then every [WeatherRules.READ_EVERY_MS], [cue] on Breezy's notifier,
 * [stop] at its end. Main thread only.
 */
object Weather {
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "aap-weather").apply { priority = Thread.MIN_PRIORITY } }
    private var feed: WeatherFeed? = null

    /** The dashboard's view of it, or null between projections. */
    var listener: ((WeatherState) -> Unit)? = null

    /** Told the settings changed mid-projection, for the dashboard's item and alert level. */
    var choicesListener: ((WeatherSettings) -> Unit)? = null

    private val timer = object : Runnable {
        override fun run() {
            feed?.cue(WeatherCue.TIMER)
            main.postDelayed(this, WeatherRules.READ_EVERY_MS)
        }
    }

    private val minute = object : Runnable {
        override fun run() {
            feed?.tick()
            main.postDelayed(this, 60_000L)
        }
    }

    private fun feed(context: Context): WeatherFeed = feed ?: WeatherFeed(
        WeatherProvider(context),
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        { worker.execute(it) },
        { main.post(it) },
        { System.currentTimeMillis() },
    ).also { created ->
        created.listener = { state -> listener?.invoke(state) }
        created.speak = { alert -> Speaker.alert(alert) }
        feed = created
    }

    /** A projection started: read now if weather is on, then on the timer. */
    fun start(context: Context, settings: WeatherSettings, fix: () -> Pair<Double, Double>?) {
        val feed = feed(context)
        feed.fix = fix
        main.removeCallbacks(timer)
        main.removeCallbacks(minute)
        feed.start(settings)
        main.postDelayed(timer, WeatherRules.READ_EVERY_MS)
        main.postDelayed(minute, 60_000L)
    }

    /** The settings changed mid-projection (the lab, or the phone's card). */
    fun settings(settings: WeatherSettings) {
        feed?.settings(settings)
        choicesListener?.invoke(settings)
    }

    /** The projection ended: the reading goes, and nothing is read until the next. */
    fun stop() {
        main.removeCallbacks(timer)
        main.removeCallbacks(minute)
        feed?.stop()
        feed?.fix = { null }
    }

    fun cue(why: WeatherCue) {
        feed?.cue(why)
    }

    /** The latest state, for the phone's card. */
    val state: WeatherState get() = feed?.state ?: WeatherState.NONE

    fun report(): String = feed?.report() ?: "weather_enabled=- weather_reads=0\n"

    /**
     * One read for the phone's Weather card (plan §4), whatever Show weather says and
     * whether or not a projection is attached: the card's status line, "Waco · updated 12
     * min ago". [done] is told on the main thread. Its own feed, so the session's counts and
     * reading are left alone; the reading is shown once and not kept.
     */
    fun readOnce(context: Context, fix: Pair<Double, Double>?, done: (WeatherState) -> Unit) {
        val once = WeatherFeed(
            WeatherProvider(context),
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            { worker.execute(it) },
            { main.post(it) },
            { System.currentTimeMillis() },
        )
        once.fix = { fix }
        var first = true
        once.listener = { state ->
            // start() publishes the empty state before the read; the answer is the second.
            if (first) first = false else done(state)
        }
        once.start(WeatherSettings(enabled = true, speakAlerts = false))
    }

    /**
     * Debug builds, P10.0 (plan §12): the provider's answers as this app receives them,
     * written under `files/weather-capture/` for `run-as` to fetch. `adb shell content query`
     * cannot do it, because the shell cannot hold Breezy's permission. Worker thread; the
     * blobs are the operator's own weather and are never reported.
     */
    fun capture(context: Context, done: (String) -> Unit) {
        worker.execute {
            val dir = File(context.filesDir, "weather-capture").apply { deleteRecursively(); mkdirs() }
            val provider = WeatherProvider(context)
            val summary = try {
                val lines = ArrayList<String>()
                lines += "owner=${provider.owner()} permitted=${provider.permitted()}"
                lines += "version=${provider.version()}"
                val places = provider.places()
                places.forEachIndexed { index, place ->
                    lines += "place.$index current=${place.current} name_present=${place.name.isNotEmpty()}"
                    provider.blob(place.id)?.let { File(dir, "weather-$index.json.gz").writeBytes(it) }
                }
                if (places.none { it.current }) {
                    provider.blob(WeatherRules.CURRENT_POSITION_ID)?.let { File(dir, "weather-current.json.gz").writeBytes(it) }
                }
                File(dir, "places.txt").writeText(places.joinToString("\n") { "${it.id}\t${it.latitude}\t${it.longitude}\t${it.current}\t${it.name}" } + "\n")
                lines.joinToString("; ")
            } catch (e: RuntimeException) {
                "failed: ${e.javaClass.simpleName}"
            }
            File(dir, "summary.txt").writeText(summary + "\n")
            main.post { done(summary) }
        }
    }
}
