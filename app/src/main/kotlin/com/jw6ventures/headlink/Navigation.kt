package com.jw6ventures.headlink

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
 * source set it (P6): the label is often a street address, and the
 * coordinates are the same thing in another form.
 */
object Navigation {
    /** How old a phone fix may be and still start a route; a vehicle moves. */
    private const val PHONE_FIX_MAX_AGE_MS = 120_000L

    var destination: Destination? = null
        private set

    var source: DestinationSource? = null
        private set

    /**
     * The point the chosen route passes through, [latitude, longitude], when an
     * alternative was chosen (the preview or the dashboard): every route to [destination]
     * goes through it, re-routes included, until the vehicle has passed it ([viaPassed]).
     * Null for BRouter's own best route. Never reported: it is a place.
     */
    var via: DoubleArray? = null
        private set

    /**
     * Set once the vehicle has reached [destination] ([arrived]): the trip is over, though
     * the route stays on show for [Guide.ARRIVAL_HOLD_MS] to say so. [NavigationStore]
     * saves nothing from then on, so a phone unplugged in that hold does not bring the
     * route back on the next drive.
     */
    var arrived = false
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
        // OsmAnd's router needs no tile: its maps cover what they cover, and a destination
        // outside them comes back as a route that failed, with the reason in the report.
        if (!AppSettings.load(context).routing.osmAndRouting && needed !in tiles) {
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
        via = null
        arrived = false
        // A new trip: a way round the driver kept away from on the last one may be right now.
        Traffic.newTrip()
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
     * marks a request [Guide] made because the vehicle left the route, or, with [closure],
     * because live traffic found it closed ahead (P9.5); [bearing] is the way the vehicle is
     * travelling, so the new route starts ahead of it.
     *
     * While live traffic is on and holds a response, every request carries its closures and
     * jams as no-go points ([Traffic.withNogos], docs/navigation.md, "Live traffic (HERE)"): new routes from the start,
     * re-routes, and a route through a chosen alternative's via point alike.
     */
    fun route(
        context: Context,
        fresh: Location? = null,
        reroute: Boolean = false,
        bearing: Double? = null,
        closure: Boolean = false,
    ): String {
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
        // A closure ahead (P9.5): the chosen route's via point may lie beyond it, on the closed
        // road, and a route kept to it could only go through the closure, which BRouter would
        // then open and this route could never leave again. The way round the closure is
        // BRouter's own; the chosen alternative no longer holds.
        if (closure && via != null) {
            via = null
            SessionController.note("closure ahead: the chosen route's via point dropped")
        }
        // Read at every request, re-routes included, so a change on the Routing screen
        // applies to the next route without a restart.
        val options = AppSettings.load(context).routing
        Guide.learnPace = options.learnPace
        Routing.request(
            context,
            Traffic.withNogos(
                RouteRequest(
                    point.first, point.second, destination.latitude, destination.longitude, bearing, options,
                    viaLat = via?.get(0), viaLon = via?.get(1),
                ),
            ),
            when {
                closure -> Routing.CLOSURE
                reroute -> Routing.REROUTE
                else -> source.label
            },
            from,
        )
        return context.getString(R.string.destination_routing, describe(destination))
    }

    /** A vehicle fix arrived: the one thing a waiting destination was waiting for. */
    fun onVehicleFix(context: Context) {
        if (waiting && vehicleFix?.invoke() != null) route(context)
    }

    /**
     * The Routing screen saved [options] after [previous]. A change that can alter the
     * route recomputes the one being shown, from wherever the vehicle or phone is now, so
     * what the screen says and what the map draws agree; Learn my pace alone only changes
     * how the time still to go is shown, and applies at once.
     */
    fun optionsChanged(context: Context, previous: RouteOptions, options: RouteOptions) {
        Guide.learnPace = options.learnPace
        if (!previous.routeDiffers(options) || destination == null || arrived) return
        SessionController.note("routing settings changed; recomputing the route")
        route(context)
    }

    /** Drops the destination and takes the route line off the map. */
    fun clear() {
        destination = null
        source = null
        via = null
        waiting = false
        arrived = false
        Routing.clear()
        Guide.clear()
        NavigationStore.changed()
    }

    /**
     * [Guide] saw the vehicle reach the destination: the trip ends here. It is no longer
     * saved, and the place becomes the recent destination the favorites tile lists on top,
     * so the next trip there is one tap, without joining the saved places: there is one
     * recent slot, and every arrival replaces it, a favorite's too. The tile does not list
     * a recent place that is a favorite twice ([Favorite.tile]), so arriving at one leaves
     * just the favorites. Named by its label, else its coordinates, as the connection
     * screen names a place added with no name. Only the outcome is noted, never the place.
     */
    fun arrived(context: Context) {
        val destination = destination ?: return
        if (arrived) return
        arrived = true
        NavigationStore.changed()
        val place = Favorite.of(describe(destination), destination.latitude, destination.longitude) ?: return
        Favorites.setRecent(context, place)
        SessionController.note(
            if (source == DestinationSource.FAVORITE || Favorite.saved(Favorites.list(context), place.latitude, place.longitude)) {
                "arrived at a favorite; it is the recent destination, listed once"
            } else {
                "arrived; destination is the recent one on top of the favorites tile"
            },
        )
    }

    /** An alternative was chosen: routes keep to its via point from now on, or to none. */
    fun chose(route: Route) {
        via = route.via
        NavigationStore.changed()
    }

    /**
     * P11.5a: **Add stop** on a search-ahead card. The place becomes the via point and the
     * route to the same destination is asked for again from the vehicle, heading as it is.
     * Returns whether a via point was replaced (a chosen alternative's, or an earlier stop),
     * or null, and nothing asked, with no destination or once arrived. Released like any via
     * point once the vehicle reaches or passes it ([ViaRelease]). The note names no place.
     */
    fun addStop(context: Context, latitude: Double, longitude: Double): Boolean? {
        if (destination == null || arrived) return null
        val replaced = via != null
        via = doubleArrayOf(latitude, longitude)
        NavigationStore.changed()
        SessionController.note(if (replaced) "stop added, replacing the route's via point" else "stop added as the route's via point")
        route(context, bearing = Guide.fix()?.second)
        return replaced
    }

    /** The vehicle has passed the via point: from here the best route is the chosen one. */
    fun viaPassed() {
        if (via == null) return
        via = null
        SessionController.note("passed the chosen route's via point")
        NavigationStore.changed()
    }

    /** What [NavigationStore] read back at start: the destination and via, with no route request. */
    fun restore(destination: Destination, source: DestinationSource, via: DoubleArray?) {
        this.destination = destination
        this.source = source
        this.via = via
        arrived = false
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
            "routing_via=${via != null} routing_arrived=$arrived " +
            "routing_destinations_refused=$refused routing_last_refusal=$lastRefusal\n"
}
