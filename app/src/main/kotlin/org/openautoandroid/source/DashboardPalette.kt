package org.openautoandroid.source

/**
 * The dashboard chrome's colours, one set for day and one for night (P7 plan §5.6).
 *
 * Pure Kotlin with plain ARGB integers, out of [DashboardDrawing] so the host tests can
 * hold both sets to the same contrast rules without Android's graphics classes.
 *
 * Every role exists in both sets and means the same thing in both: a drawing never asks
 * which palette it has, so a role missing from one set would be a colour silently
 * borrowed from the other.
 */
data class DashboardPalette(
    val background: Int,
    val header: Int,
    val tile: Int,
    val tilePressed: Int,
    val button: Int,
    val text: Int,
    val dim: Int,
    val accent: Int,
    val warning: Int,
    val attribution: Int,
    /** Backing plate, so the credit stays legible over any map colour. */
    val attributionPlate: Int,
    /** The floating map controls: mostly opaque, so the map still shows under them. */
    val bubble: Int,
    val bubblePressed: Int,
    /** Their edge, so the plate has one over a map that happens to match it. */
    val bubbleRim: Int,
    /**
     * P7.9: Answer's fill, green. The one place the chrome uses colour to mean something
     * a word also says: a ringing call is answered by reflex, and green and red are what
     * every phone's call screen has taught that reflex. The word is drawn on it anyway,
     * for a driver who cannot tell the two apart.
     */
    val answer: Int,
    /** P7.9: Decline and Hang up, red; the same reasoning as [answer]. */
    val decline: Int,
    /** The words on [answer] and [decline]: held to 4.5:1 on both (DashboardTest). */
    val onAction: Int,
) {
    companion object {
        /**
         * A light scheme for daylight, new in P7.6.
         *
         * Before P7.6 "day" was a slightly lighter dark scheme, and every recorded Ford
         * session drew the night one anyway, because the truck's display was pinned dark
         * (§1 item 16). In sunlight a dark screen is a mirror, which is the case for a
         * light one.
         *
         * Not white: the tiles are the lightest thing, a cool grey under them, so the
         * cards still read as cards, and nothing is paper-bright next to the map's own
         * warm day ground (`dashboard-light.xml`, `#e9e7e1`). The accent and the warning
         * are darker than their night versions because a colour that reads on navy
         * washes out on white; both keep ≥ 4.5:1 against the tiles (DashboardTest).
         */
        val DAY = DashboardPalette(
            background = 0xFFD5DAE0.toInt(),
            header = 0xFFE6E9ED.toInt(),
            tile = 0xFFF8F9FA.toInt(),
            tilePressed = 0xFFC3CEDA.toInt(),
            button = 0xFFE3E8EE.toInt(),
            text = 0xFF14181C.toInt(),
            dim = 0xFF505B66.toInt(),
            accent = 0xFF1D64C0.toInt(),
            warning = 0xFFA35300.toInt(),
            attribution = 0xFF12161A.toInt(),
            attributionPlate = 0xCCF2F4F6.toInt(),
            bubble = 0xE6F8F9FA.toInt(),
            bubblePressed = 0xF2C3CEDA.toInt(),
            bubbleRim = 0xB3505B66.toInt(),
            // Dark enough for white words at 4.5:1, and still plainly green and red
            // against the light tiles (≥ 3:1, WCAG's floor for a control's edge).
            answer = 0xFF1B7F3B.toInt(),
            decline = 0xFFB3261E.toInt(),
            onAction = 0xFFFFFFFF.toInt(),
        )

        /**
         * The dark scheme: until P7.6 this was `DAY`, and the plan (§5.6) keeps it as the
         * night baseline.
         *
         * The pre-P7.6 `NIGHT` set was dimmer still (text `#D8DEE4`, dim `#6C7884` on
         * `#141A20`, about 3.6:1) and it is what the truck actually showed on every drive,
         * because its display was pinned dark. It is not kept: 3.6:1 for the tile labels
         * is below what a glance at arm's length needs, and the review found nothing too
         * bright about the frames it read. Its one better choice is kept, the dark
         * attribution plate: the old day set's near-white plate is the brightest thing on
         * a night map, and a credit does not need to be.
         */
        val NIGHT = DashboardPalette(
            background = 0xFF14171A.toInt(),
            header = 0xFF1F2429.toInt(),
            tile = 0xFF262C33.toInt(),
            tilePressed = 0xFF39424C.toInt(),
            button = 0xFF39424C.toInt(),
            text = 0xFFF2F4F6.toInt(),
            dim = 0xFF9AA5B1.toInt(),
            accent = 0xFF4E9AF1.toInt(),
            warning = 0xFFE2B33C.toInt(),
            attribution = 0xFFE8EDF2.toInt(),
            attributionPlate = 0xCC0A0D10.toInt(),
            bubble = 0xE62B323A.toInt(),
            bubblePressed = 0xF24A5666.toInt(),
            bubbleRim = 0xB39AA5B1.toInt(),
            // Lighter than the day pair, with dark words on them: a day-strength green on
            // these tiles is under 3:1 and reads as a hole in the tile, not a button.
            answer = 0xFF45B86B.toInt(),
            decline = 0xFFEF6A60.toInt(),
            onAction = 0xFF0E1114.toInt(),
        )

        /** The set for a resolved night value. */
        fun of(night: Boolean): DashboardPalette = if (night) NIGHT else DAY

        /**
         * WCAG contrast ratio of two opaque colours, 1..21, for the host test's checks of
         * both sets. Alpha is ignored: the checked roles are opaque.
         */
        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }

        private fun luminance(argb: Int): Double {
            fun channel(shift: Int): Double {
                val c = ((argb shr shift) and 0xFF) / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
    }
}
