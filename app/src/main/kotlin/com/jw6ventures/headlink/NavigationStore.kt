package com.jw6ventures.headlink

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * What is being navigated to, kept across the app's process being closed: the destination,
 * the chosen alternative's via point, and the route itself.
 *
 * Before this, all three lived in memory only. Setting a route on the phone and then going
 * out to the truck lost it whenever Android closed the app in between, and with it an
 * alternative chosen in the preview.
 *
 * The route is kept whole, in the preview's file format ([RoutePreview]), rather than as an
 * identifier to recompute from: a route depends on where it started, which a recompute
 * from the truck would not share. What does identify a chosen alternative is its via point,
 * so that is kept too, and [Navigation] routes through it until the vehicle has passed it.
 * A restored route starting somewhere else (the couch) is joined by [RouteFollower] or
 * re-routed like any other.
 *
 * The file is app-private and the app allows no backup. It holds a place, so nothing of it
 * reaches the report or the event log beyond that something was restored and how old it was.
 * Kept until the vehicle arrives or the route is cleared.
 */
object NavigationStore {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "navigation-store").apply { isDaemon = true } }
    private var file: File? = null

    /** What was restored at start, for the report: `-`, `destination` or `route`. */
    var restored = "-"
        private set

    /**
     * Main process only, from [SourceApplication]: remembers where to save, then reads what
     * the last process left and hands it to [Navigation] and [Routing] on the main thread,
     * unless something has been set since.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val target = NavigationFile.file(app.filesDir)
        file = target
        io.execute {
            val saved = try {
                if (target.isFile) NavigationFile.read(target) else null
            } catch (e: IOException) {
                target.delete()
                null
            } ?: return@execute
            main.post { restore(app, saved) }
        }
    }

    /**
     * Saves the destination, via and route as they are now, or removes the file when there
     * is no destination or the vehicle has arrived at it ([Navigation.arrived]).
     */
    fun changed() {
        val target = file ?: return
        val destination = Navigation.destination?.takeUnless { Navigation.arrived }
        val saved = destination?.let {
            NavigationFile.Saved(System.currentTimeMillis(), it, Navigation.source ?: DestinationSource.TYPED, Navigation.via, Routing.line)
        }
        io.execute {
            try {
                if (saved == null) target.delete() else NavigationFile.write(target, saved)
            } catch (e: IOException) {
                // A route that is not saved is the old behaviour, not a failure to drive with.
            }
        }
    }

    private fun restore(context: Context, saved: NavigationFile.Saved) {
        if (Navigation.destination != null || Routing.line != null || Routing.pending != 0L) return
        Navigation.restore(saved.destination, saved.source, saved.via)
        val minutes = (System.currentTimeMillis() - saved.savedAtMillis) / 60_000L
        val route = saved.route
        if (route != null) {
            restored = "route"
            Routing.restore(context, route)
            SessionController.note("restored the saved destination and route, saved $minutes min ago")
        } else {
            restored = "destination"
            SessionController.note("restored the saved destination, saved $minutes min ago; routing to it")
            Navigation.route(context)
        }
    }
}

/**
 * [NavigationStore]'s file: pure JVM with no Android type, so the host test reads and writes
 * it. The preview's format ([RoutePreview]) and version, with the destination around it.
 */
object NavigationFile {
    fun file(filesDir: File): File = File(File(filesDir, "navigation"), "current.bin")

    class Saved(
        val savedAtMillis: Long,
        val destination: Destination,
        val source: DestinationSource,
        val via: DoubleArray?,
        val route: Route?,
    )

    @Throws(IOException::class)
    fun write(target: File, saved: Saved) = RoutePreview.write(target) { out ->
        out.writeLong(saved.savedAtMillis)
        out.writeDouble(saved.destination.latitude)
        out.writeDouble(saved.destination.longitude)
        out.writeBoolean(saved.destination.label != null)
        saved.destination.label?.let { out.writeUTF(it) }
        out.writeUTF(saved.source.label)
        out.writeDouble(saved.via?.get(0) ?: Double.NaN)
        out.writeDouble(saved.via?.get(1) ?: Double.NaN)
        out.writeBoolean(saved.route != null)
        saved.route?.let { RoutePreview.writeRoute(out, it) }
    }

    @Throws(IOException::class)
    fun read(target: File): Saved = RoutePreview.read(target) { input ->
        val at = input.readLong()
        val lat = input.readDouble()
        val lon = input.readDouble()
        if (!(lat in -90.0..90.0 && lon in -180.0..180.0)) throw IOException("saved destination out of range")
        val label = if (input.readBoolean()) input.readUTF() else null
        val sourceLabel = input.readUTF()
        val source = DestinationSource.entries.firstOrNull { it.label == sourceLabel } ?: DestinationSource.TYPED
        val viaLat = input.readDouble()
        val viaLon = input.readDouble()
        val via = if (viaLat.isNaN() || viaLon.isNaN()) null else doubleArrayOf(viaLat, viaLon)
        val route = if (input.readBoolean()) RoutePreview.readRoute(input) else null
        Saved(at, Destination(lat, lon, label), source, via, route)
    }
}
