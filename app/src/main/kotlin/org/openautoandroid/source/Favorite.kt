package org.openautoandroid.source

/**
 * A saved place the dashboard lists for one-tap routing: what the driver called it, and
 * where it is.
 *
 * The name and the coordinates are the operator's own, like a shared destination's
 * label (P6 plan §5.1): they are shown on the phone and drawn on the head unit, and
 * never reach the report or the event log. Pure JVM, so tools/jni-host-test can hold the
 * stored form to its rules.
 */
data class Favorite(val name: String, val latitude: Double, val longitude: Double) {
    companion object {
        /** Longest name kept; the tile truncates what it cannot fit anyway. */
        const val NAME_MAX = 60

        /**
         * Most places kept. Enough for every regular destination a driver has, and few
         * enough that scrolling the tile stays a way of finding one rather than a chore.
         */
        const val MAX = 50

        /**
         * A destination this close to a saved place is that place: a share of the same
         * address from another app lands a few metres from where it was saved, and within
         * this a second row would only repeat the first.
         */
        const val SAME_PLACE_M = 50.0

        /** Whether one of [favorites] is at ([latitude], [longitude]), within [SAME_PLACE_M]. */
        fun saved(favorites: List<Favorite>, latitude: Double, longitude: Double): Boolean =
            favorites.any { RouteFollower.distance(it.latitude, it.longitude, latitude, longitude) <= SAME_PLACE_M }

        /**
         * What the dashboard tile lists: [recent], the last place arrived at, on top of the
         * saved [favorites], unless there is none or it is one of them by now (saved since,
         * within [SAME_PLACE_M]): the tile's one-tap way back to the last trip, which never
         * joins the saved list.
         */
        fun tile(favorites: List<Favorite>, recent: Favorite?): List<Favorite> =
            if (recent == null || saved(favorites, recent.latitude, recent.longitude)) favorites
            else listOf(recent) + favorites

        /**
         * [favorites] with the place at [from] taken out and put back at [to], the rest
         * keeping their order. The same list when either index is not a row or nothing moves.
         */
        fun moved(favorites: List<Favorite>, from: Int, to: Int): List<Favorite> {
            if (from !in favorites.indices || to !in favorites.indices || from == to) return favorites
            return favorites.toMutableList().apply { add(to, removeAt(from)) }
        }

        /**
         * The stored form: one place a line, `latitude<TAB>longitude<TAB>name`. The name
         * is last because it is the one field that can hold spaces, and it can hold no
         * tab or line break because [cleanName] removes every control character.
         */
        fun encode(favorites: List<Favorite>): String =
            favorites.joinToString("\n") { "${it.latitude}\t${it.longitude}\t${it.name}" }

        /**
         * Reads the stored form back. A line that does not parse is dropped rather than
         * failing the rest: one bad entry must not empty the tile.
         */
        fun decode(text: String?): List<Favorite> {
            if (text.isNullOrEmpty()) return emptyList()
            return text.lineSequence().mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size != 3) return@mapNotNull null
                val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val lon = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                of(parts[2], lat, lon)
            }.take(MAX).toList()
        }

        /**
         * A favorite, or null when the numbers cannot be a place or the name is empty
         * once cleaned. The same bounds a shared destination is held to.
         */
        fun of(name: String, latitude: Double, longitude: Double): Favorite? {
            if (!latitude.isFinite() || !longitude.isFinite()) return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            if (latitude == 0.0 && longitude == 0.0) return null
            val cleaned = cleanName(name) ?: return null
            return Favorite(cleaned, latitude, longitude)
        }

        /** Printable, trimmed and bounded, or null when nothing is left. */
        fun cleanName(name: String): String? =
            name.filter { !it.isISOControl() }.trim().take(NAME_MAX).ifEmpty { null }
    }
}
