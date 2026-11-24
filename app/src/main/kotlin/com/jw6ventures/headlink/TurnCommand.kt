package com.jw6ventures.headlink

/**
 * The turn-command numbering HeadLink's guidance reads: each [Route.commands] entry is one
 * of these, and [Maneuvers] says what it means. The routing engines map their own turn
 * types onto it.
 *
 * These numbers are persisted raw in saved routes (`RoutePreview.writeRoute`,
 * `NavigationStore`) and in the preview IPC file, so they must never be renumbered.
 */
object TurnCommand {
    const val CONTINUE = 1
    const val TURN_LEFT = 2
    const val TURN_SLIGHT_LEFT = 3
    const val TURN_SHARP_LEFT = 4
    const val TURN_RIGHT = 5
    const val TURN_SLIGHT_RIGHT = 6
    const val TURN_SHARP_RIGHT = 7
    const val KEEP_LEFT = 8
    const val KEEP_RIGHT = 9
    const val U_TURN_LEFT = 10
    const val U_TURN_RIGHT = 11
    /** Not a maneuver: [Maneuvers.of] answers null for it. */
    const val OFF_ROUTE = 12
    const val ROUNDABOUT = 13
    const val ROUNDABOUT_LEFT = 14
    const val U_TURN_180 = 15
    /** Not a maneuver: [Maneuvers.of] answers null for it. */
    const val BEELINE = 16
    const val EXIT_LEFT = 17
    const val EXIT_RIGHT = 18
    const val END = 100
}
