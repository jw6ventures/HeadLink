package org.openautoandroid.source

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import java.util.Locale

/**
 * The destination, and where a route to it starts from (P6.4).
 *
 * Main-thread state, like [Routing], which it feeds. A destination arrives from a share,
 * the connection screen or a long press on the projected map; the start is the vehicle's
 * own position while projecting, else the phone's recent fix. With neither, the
 * destination waits and is routed as soon as a vehicle fix arrives.
 *
 * **Nothing about the destination reaches the report or the event log** except which
 * source set it (P6 plan §5.1, §9.6): the label is often a street address, and the
 * coordinates are the same thing in another form.
 */
object Navigation {
    /** How old a phone fix may be and still start a route; a vehicle moves. */
    private const val PHONE_FIX_MAX_AGE_MS = 120_000L

    var destination: Destination? = null
        private set

    var source: DestinationSource? = null
        private set

    /** Set when a destination is held for want of a position to route from. */
    var waiting = false
        private set

    var refused = 0
        private set

    var lastRefusal: String = "-"
        private set

    /** Asked the phone for a fresh fix and not yet heard back. */
    private var locating = false

    /**
     * The vehicle's own position while a dashboard projects, installed by
     * [ProjectionService] for the life of the pipeline. Null when nothing projects.
     */
    var vehicleFix: (() -> Pair<Double, Double>?)? = null

    /**
     * Takes [destination] from [source] and routes to it, replacing any route already
     * shown: a driver who picks a second place does not want the first. Returns what the
     * phone should say; that text may name the place, the report never does.
     */
    fun set(context: Context, destination: Destination, source: DestinationSource): String {
        val tiles = Routing.installed(context).map { it.name }.toSet()
        val needed = RoutingData.tileFor(destination.latitude, destination.longitude)
        if (needed !in tiles) {
            refuse(Refusal.NO_ROUTING_DATA, source)
            val mirrored = DestinationParser.mirroredLongitude(destination.latitude, destination.longitude, tiles)
            return if (mirrored != null) {
                context.getString(R.string.destination_mirrored, describe(destination), coordinates(destination.latitude, mirrored))
            } else {
                context.getString(R.string.destination_no_tile, describe(destination), needed)
            }
        }
        this.destination = destination
        this.source = source
        return route(context)
    }

    /** A share that was read and not taken; the reason is all that is recorded. */
    fun refuse(reason: Refusal, source: DestinationSource) {
        refused++
        lastRefusal = reason.label
        SessionController.note("destination from ${source.label} refused: ${reason.label}")
    }

    /**
     * Routes to the held destination from wherever the start can be found now. [reroute]
     * marks a request [Guide] made because the vehicle left the route, and [bearing] is
     * the way the vehicle is travelling, so the new route starts ahead of it.
     */
    fun route(context: Context, fresh: Location? = null, reroute: Boolean = false, bearing: Double? = null): String {
        val destination = destination ?: return context.getString(R.string.destination_none)
        val source = source ?: DestinationSource.TYPED
        val start = start(context, fresh)
        if (start == null) {
            if (!waiting) SessionController.note("destination from ${source.label} is waiting for a position")
            waiting = true
            locate(context)
            return context.getString(R.string.destination_waiting, describe(destination))
        }
        waiting = false
        val (point, from) = start
        Routing.request(
            context,
            RouteRequest(point.first, point.second, destination.latitude, destination.longitude, bearing),
            if (reroute) "reroute" else source.label,
            from,
        )
        return context.getString(R.string.destination_routing, describe(destination))
    }

    /** A vehicle fix arrived: the one thing a waiting destination was waiting for. */
    fun onVehicleFix(context: Context) {
        if (waiting && vehicleFix?.invoke() != null) route(context)
    }

    /** Drops the destination and takes the route line off the map. */
    fun clear() {
        destination = null
        source = null
        waiting = false
        Routing.clear()
        Guide.clear()
    }

    /** What the phone calls the destination: its label, else its coordinates. */
    fun describe(destination: Destination): String =
        destination.label ?: coordinates(destination.latitude, destination.longitude)

    private fun coordinates(lat: Double, lon: Double): String =
        String.format(Locale.ROOT, "%.5f, %.5f", lat, lon)

    /**
     * Where a route starts, and which source said so: the vehicle while it has a fix,
     * since the dashboard already follows it, else a phone fix no older than
     * [PHONE_FIX_MAX_AGE_MS]. Null when neither is available.
     */
    private fun start(context: Context, fresh: Location?): Pair<Pair<Double, Double>, String>? {
        vehicleFix?.invoke()?.let { return it to "vehicle" }
        val fix = fresh ?: lastPhoneFix(context) ?: return null
        return (fix.latitude to fix.longitude) to "phone"
    }

    private fun hasLocation(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun lastPhoneFix(context: Context): Location? {
        if (!hasLocation(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .filter { age(it) <= PHONE_FIX_MAX_AGE_MS }
                .maxByOrNull { it.elapsedRealtimeNanos }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun age(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L

    /**
     * Asks the phone for one fresh fix when none is recent. Only with a grant the
     * operator has already made: this app never asks for location on its own (APP09).
     */
    private fun locate(context: Context) {
        if (locating || !hasLocation(context)) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { it in manager.getProviders(true) } ?: return
        val app = context.applicationContext
        locating = true
        try {
            manager.getCurrentLocation(provider, null, app.mainExecutor) { location ->
                locating = false
                if (location != null && waiting) route(app, location)
            }
        } catch (e: SecurityException) {
            locating = false
        }
    }

    /** The `[routing]` lines this adds. No destination, no label, no coordinates. */
    fun report(): String =
        "routing_destination_source=${source?.label ?: "-"} routing_destination_waiting=$waiting " +
            "routing_destinations_refused=$refused routing_last_refusal=$lastRefusal\n"
}
