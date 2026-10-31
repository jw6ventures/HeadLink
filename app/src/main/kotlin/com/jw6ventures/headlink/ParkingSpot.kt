package com.jw6ventures.headlink

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/**
 * Where the car was when the last drive ended, for walking back to it.
 *
 * [ProjectionService] keeps the drive's last position (the car's own fix, else the
 * phone's while the car had none, the same order guidance follows) and hands it here when
 * the session ends: unplugged, the wireless link dropped, or stopped from the phone. One
 * slot, replaced by each drive that had a position and kept by one that had none, so a
 * connection that failed before any fix never wipes a good spot. Home shows it with a
 * button that opens OsmAnd's walking directions to it ([openWalking]).
 *
 * Stays on the phone like the saved places (PRIVACY.md), and is never put in a report.
 */
object ParkingSpot {
    private const val FILE = "parking"
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
    private const val SAVED_AT = "saved_at"

    /**
     * OsmAnd's packages, the paid build first. Each opens `osmand.net/map` links itself
     * (its manifest's VIEW filter), so a direct launch needs no `<queries>` entry.
     */
    private val OSMAND_PACKAGES = listOf("net.osmand.plus", "net.osmand")

    data class Spot(val latitude: Double, val longitude: Double, val savedAtMillis: Long)

    fun load(context: Context): Spot? {
        val prefs = prefs(context)
        if (!prefs.contains(LATITUDE) || !prefs.contains(LONGITUDE)) return null
        val latitude = prefs.getString(LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(LONGITUDE, null)?.toDoubleOrNull() ?: return null
        if (!valid(latitude, longitude)) return null
        return Spot(latitude, longitude, prefs.getLong(SAVED_AT, 0L))
    }

    /** Replaces the saved spot. Main thread or any: a single preferences write. */
    fun save(context: Context, latitude: Double, longitude: Double, savedAtMillis: Long) {
        if (!valid(latitude, longitude)) return
        prefs(context).edit()
            // As text: a float would round a position by up to a couple of metres.
            .putString(LATITUDE, latitude.toString())
            .putString(LONGITUDE, longitude.toString())
            .putLong(SAVED_AT, savedAtMillis)
            .apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * OsmAnd's own navigation link: `end` is the destination, `profile` the routing
     * profile by its key, and with no `start` OsmAnd plans from its current location.
     * Read from OsmAnd's `IntentHelper.parseNavigationIntent` (osmandapp/OsmAnd master,
     * October 2026); "pedestrian" is `ApplicationMode.PEDESTRIAN`'s key.
     */
    fun walkingUri(spot: Spot): Uri =
        Uri.parse("https://osmand.net/map?end=${coordinates(spot)}&profile=pedestrian")

    /** A plain `geo:` point with a label, for any maps app when OsmAnd is not installed. */
    fun geoUri(spot: Spot, label: String): Uri =
        Uri.parse("geo:${coordinates(spot)}?q=${coordinates(spot)}(${Uri.encode(label)})")

    /**
     * Opens OsmAnd with walking directions to [spot]. Without OsmAnd, any maps app gets the
     * point instead ([geoUri]). False when nothing on the phone can open either.
     */
    fun openWalking(context: Context, spot: Spot, label: String): Boolean {
        for (pkg in OSMAND_PACKAGES) {
            val intent = Intent(Intent.ACTION_VIEW, walkingUri(spot)).setPackage(pkg)
            if (start(context, intent)) return true
        }
        return start(context, Intent(Intent.ACTION_VIEW, geoUri(spot, label)))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun coordinates(spot: Spot): String =
        String.format(Locale.US, "%.6f,%.6f", spot.latitude, spot.longitude)

    /** A real position: (0, 0) is what an unset sensor field reads as, not a car park. */
    private fun valid(latitude: Double, longitude: Double): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0)

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
