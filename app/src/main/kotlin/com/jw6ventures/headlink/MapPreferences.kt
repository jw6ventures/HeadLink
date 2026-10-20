package com.jw6ventures.headlink

import android.content.Context

/**
 * The map pane's choices that belong to the driver rather than to one session, kept
 * across sessions and app restarts.
 *
 * Only the orientation for now: it is changed on the head unit by the compass bubble,
 * so unlike the settings screen's spinners there is nowhere else for it to live.
 */
object MapPreferences {

    private const val FILE = "map"
    private const val ORIENTATION = "orientation"

    /** The orientation last chosen, or north-up when none has been. */
    fun orientation(context: Context): MapOrientation =
        MapOrientation.of(prefs(context).getString(ORIENTATION, null)) ?: MapOrientation.NORTH_UP

    /** Stores [orientation]. Asynchronous, so safe to call from under the dashboard lock. */
    fun saveOrientation(context: Context, orientation: MapOrientation) {
        prefs(context).edit().putString(ORIENTATION, orientation.label).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
