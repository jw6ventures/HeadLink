package com.jw6ventures.headlink

import android.content.Context

/**
 * The driver's saved places, kept across sessions and app restarts, the one place last
 * arrived at, and the dashboard's live view of both.
 *
 * Main-thread state, like [Navigation]. The list is edited on the connection screen and
 * read by the projected dashboard, which may be running at the time: a place added
 * while the phone is plugged in shows up on the head unit without a reconnect, which is
 * what the listener is for. [ProjectionService] hands each change to the pipeline.
 */
object Favorites {
    private const val FILE = "favorites"
    private const val PLACES = "places"
    private const val RECENT = "recent"

    private var loaded: List<Favorite>? = null
    private var recentLoaded = false
    private var recent: Favorite? = null
    private val listeners = mutableListOf<(List<Favorite>, Favorite?) -> Unit>()

    /** Every saved place, in the driver's order; added ones at the end. */
    fun list(context: Context): List<Favorite> =
        loaded ?: Favorite.decode(prefs(context).getString(PLACES, null)).also { loaded = it }

    /**
     * The place last arrived at ([Navigation.arrived]), or null. The dashboard tile lists
     * it above the saved places unless it is one of them ([Favorite.tile]).
     */
    fun recent(context: Context): Favorite? {
        if (!recentLoaded) {
            recent = Favorite.decode(prefs(context).getString(RECENT, null)).firstOrNull()
            recentLoaded = true
        }
        return recent
    }

    /**
     * Makes [place] the recent destination, replacing the last one: one slot, never a
     * history, and never added to the saved places.
     */
    fun setRecent(context: Context, place: Favorite) {
        recent = place
        recentLoaded = true
        prefs(context).edit().putString(RECENT, Favorite.encode(listOf(place))).apply()
        notifyListeners(context)
    }

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

    /** Replaces the whole list; the lab's way of starting with rows to draw. */
    fun replace(context: Context, favorites: List<Favorite>) {
        store(context, favorites.take(Favorite.MAX))
    }

    /** Empties the recent slot, so the lab's tile lists only the rows it was given. */
    fun clearRecent(context: Context) {
        recent = null
        recentLoaded = true
        prefs(context).edit().remove(RECENT).apply()
        notifyListeners(context)
    }

    /** Removes the place at [index], if there is one. */
    fun remove(context: Context, index: Int) {
        val current = list(context)
        if (index !in current.indices) return
        store(context, current.filterIndexed { i, _ -> i != index })
    }

    /** Moves the place at [from] to [to] ([Favorite.moved]); the driver's own order for the tile. */
    fun move(context: Context, from: Int, to: Int) {
        val current = list(context)
        val moved = Favorite.moved(current, from, to)
        if (moved !== current) store(context, moved)
    }

    /** Told after every change, on the main thread, with every saved place and the recent one. */
    fun addListener(listener: (List<Favorite>, Favorite?) -> Unit) {
        if (listener !in listeners) listeners += listener
    }

    fun removeListener(listener: (List<Favorite>, Favorite?) -> Unit) {
        listeners -= listener
    }

    private fun store(context: Context, favorites: List<Favorite>) {
        loaded = favorites
        prefs(context).edit().putString(PLACES, Favorite.encode(favorites)).apply()
        notifyListeners(context)
    }

    private fun notifyListeners(context: Context) {
        val saved = list(context)
        val recent = recent(context)
        listeners.toList().forEach { it(saved, recent) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
