package com.jw6ventures.headlink

/**
 * Settings → Map → Adaptive zoom: the follow view's zoom level for each speed band, and
 * how long a new speed must hold before the map moves to it (docs/dashboard.md, "Adaptive
 * zoom"). Pure, so the host test needs nothing saved; where it is saved is `AppSettings`'.
 *
 * The bands are fixed, in mph because that is how the driver who asked for this thinks:
 * under 20, 20 to 40, 40 to 55, and over 55. Only the level each one shows and the three
 * times are choices, so they can be tuned on the road.
 */
data class AdaptiveZoomSettings(
    val enabled: Boolean = true,
    /** One zoom level per band, slowest first; each one of [LEVELS]. */
    val levels: List<Int> = DEFAULT_LEVELS,
    /** How long a faster band must hold before the map zooms out to it: one of [OUT_SECONDS]. */
    val zoomOutSeconds: Int = 3,
    /**
     * How long a slower band must hold before the map zooms in to it: one of [IN_SECONDS].
     * Longer than [zoomOutSeconds] by default, so a red light or a brief slowdown does not
     * pump the map in and out.
     */
    val zoomInSeconds: Int = 6,
    /** How long a change of level takes to ease in, milliseconds: one of [GLIDE_MILLIS]. */
    val glideMillis: Int = 1500,
) {
    /** This value with anything no list offers any more put back to its default. */
    fun repaired(): AdaptiveZoomSettings = copy(
        levels = if (levels.size == BANDS) {
            levels.mapIndexed { i, level -> level.takeIf { it in LEVELS } ?: DEFAULT_LEVELS[i] }
        } else {
            DEFAULT_LEVELS
        },
        zoomOutSeconds = zoomOutSeconds.takeIf { it in OUT_SECONDS } ?: 3,
        zoomInSeconds = zoomInSeconds.takeIf { it in IN_SECONDS } ?: 6,
        glideMillis = glideMillis.takeIf { it in GLIDE_MILLIS } ?: 1500,
    )

    companion object {
        /**
         * Where each band starts, mph: a speed at or above the n-th is in band n + 1. Kept
         * as mph, the unit the bands are named in, and turned into metres a second once.
         */
        val BAND_STARTS_MPH = listOf(20, 40, 55)

        val BANDS = BAND_STARTS_MPH.size + 1

        /**
         * Under 20 mph, level 17: about 500 m across a typical pane. 20 to 40, today's
         * fixed level 16. 40 to 55, 15. Over 55, 14: about 4 km across.
         */
        val DEFAULT_LEVELS = listOf(17, 16, 15, 14)

        /** The levels a band may show: the street-level end of what the pinch reaches. */
        val LEVELS = (12..18).toList()

        val OUT_SECONDS = listOf(0, 1, 2, 3, 5, 8, 10, 15)
        val IN_SECONDS = listOf(0, 2, 4, 6, 8, 10, 15, 20, 30)
        val GLIDE_MILLIS = listOf(0, 500, 1000, 1500, 2000, 3000)

        /**
         * Off, for a dashboard built without saved settings (host tests, lab runs). Last in
         * the companion: its defaults read the lists above, which must be set first.
         */
        val OFF = AdaptiveZoomSettings(enabled = false)

        fun levelLabel(level: Int): String = when (level) {
            16 -> "$level (the old fixed view)"
            LEVELS.first() -> "$level (farthest)"
            LEVELS.last() -> "$level (closest)"
            else -> level.toString()
        }

        fun secondsLabel(seconds: Int): String = if (seconds == 0) "at once" else "$seconds s"

        fun glideLabel(millis: Int): String = if (millis == 0) "no glide, jump" else "%.1f s".format(java.util.Locale.ROOT, millis / 1000.0)
    }
}

/**
 * Which band's level the follow view should be at, given the speed now (docs/dashboard.md,
 * "Adaptive zoom").
 *
 * A band changes only once the new speed has held for [AdaptiveZoomSettings.zoomOutSeconds]
 * (faster) or [AdaptiveZoomSettings.zoomInSeconds] (slower), and only once the speed is
 * [MARGIN_MPH] past the edge it crosses, so a car holding 40 mph does not flick between
 * two bands. Accelerating through several bands keeps one timer: the wait is for the
 * direction, not for each band passed.
 *
 * Pure JVM, like [MapControl], which does the easing; [Dashboard] holds the only one.
 */
class AdaptiveZoom(val settings: AdaptiveZoomSettings) {
    /** The band on show, or -1 before the first speed. */
    var band: Int = -1
        private set

    private var candidate = -1
    private var candidateSince = 0L

    /** Band changes this session, for the report. */
    var changes = 0L
        private set

    /** The zoom level for [speedMps] at [nowMillis], or null when off or nothing is known yet. */
    fun level(speedMps: Double?, nowMillis: Long): Int? {
        if (!settings.enabled) return null
        if (band < 0) {
            // The first speed decides at once: there is nothing on show to hold.
            band = bandOf(speedMps ?: return null)
            return settings.levels[band]
        }
        // A source gone quiet holds what is on show.
        val speed = speedMps ?: return settings.levels[band].also { candidate = -1 }
        val up = bandOf(speed - MARGIN_MPS)
        val down = bandOf(speed + MARGIN_MPS)
        val next = when {
            up > band -> up
            down < band -> down
            else -> band
        }
        if (next == band) {
            candidate = -1
        } else {
            // Same direction as the band already waited for: keep its timer.
            val sameWay = candidate >= 0 && (candidate > band) == (next > band)
            if (!sameWay) candidateSince = nowMillis
            candidate = next
            val wait = (if (next > band) settings.zoomOutSeconds else settings.zoomInSeconds) * 1000L
            if (nowMillis - candidateSince >= wait) {
                band = next
                candidate = -1
                changes++
            }
        }
        return settings.levels[band]
    }

    companion object {
        private const val MPS_PER_MPH = 0.44704

        /** How far past a band's edge the speed must be to cross it, mph. */
        const val MARGIN_MPH = 2.0
        private const val MARGIN_MPS = MARGIN_MPH * MPS_PER_MPH

        private val STARTS_MPS = AdaptiveZoomSettings.BAND_STARTS_MPH.map { it * MPS_PER_MPH }

        /** The band a speed is in, with no margin. */
        fun bandOf(speedMps: Double): Int = STARTS_MPS.count { speedMps >= it }
    }
}
