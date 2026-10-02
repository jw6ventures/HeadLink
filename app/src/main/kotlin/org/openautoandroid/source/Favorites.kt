package org.openautoandroid.source

import android.content.Context

/**
 * The driver's saved places, kept across sessions and app restarts, and the dashboard's
 * live view of them.
 *
 * Main-thread state, like [Navigation]. The list is edited on the connection screen and
 * read by the projected dashboard, which may be running at the time: a place added
 * while the phone is plugged in shows up on the head unit without a reconnect, which is
 * what the listener is for. [ProjectionService] hands each change to the pipeline.
 */
object Favorites {
    private const val FILE = "favorites"
    private const val PLACES = "places"

    private var loaded: List<Favorite>? = null
    private val listeners = mutableListOf<(List<Favorite>) -> Unit>()

    /** Every saved place: added ones at the end, places arrived at on top ([addFirst]). */
    fun list(context: Context): List<Favorite> =
        loaded ?: Favorite.decode(prefs(context).getString(PLACES, null)).also { loaded = it }

    /**
     * Appends [favorite]. False when the list is full: the caller says so, and nothing is
     * silently dropped to make room.
     */
    fun add(context: Context, favorite: Favorite): Boolean {
        val current = list(context)
        if (current.size >= Favorite.MAX) return false
        store(context, current + favorite)
        return true
    }

    /**
     * Puts [favorite] at the top, where the dashboard tile shows it first: the place the
     * vehicle just arrived at ([Navigation.arrived]). False when the list is full, as [add].
     */
    fun addFirst(context: Context, favorite: Favorite): Boolean {
        val current = list(context)
        if (current.size >= Favorite.MAX) return false
        store(context, listOf(favorite) + current)
        return true
    }

    /** Replaces the whole list; the lab's way of starting with rows to draw. */
    fun replace(context: Context, favorites: List<Favorite>) {
        store(context, favorites.take(Favorite.MAX))
    }

    /** Removes the place at [index], if there is one. */
    fun remove(context: Context, index: Int) {
        val current = list(context)
        if (index !in current.indices) return
        store(context, current.filterIndexed { i, _ -> i != index })
    }

    /** Told after every change, on the main thread, with the whole list. */
    fun addListener(listener: (List<Favorite>) -> Unit) {
        if (listener !in listeners) listeners += listener
    }

    fun removeListener(listener: (List<Favorite>) -> Unit) {
        listeners -= listener
    }

    private fun store(context: Context, favorites: List<Favorite>) {
        loaded = favorites
        prefs(context).edit().putString(PLACES, Favorite.encode(favorites)).apply()
        listeners.toList().forEach { it(favorites) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
