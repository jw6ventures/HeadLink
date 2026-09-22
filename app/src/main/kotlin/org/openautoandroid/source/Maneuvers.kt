package org.openautoandroid.source

import btools.router.VoiceHintReader as H

/**
 * One maneuver as the cluster is told it: a raw `ManeuverType` value, the roundabout exit
 * when there is one, and the instruction a driver would read.
 */
class Maneuver(
    /** Raw `ManeuverType` value, from the pinned `ManeuverTypeEnum.proto`. */
    val type: Int,
    /** Roundabout exit, 0 when the maneuver is not a roundabout. */
    val exit: Int,
    val instruction: String,
)

/**
 * BRouter's turn commands, read as the cluster's maneuvers (P6.5).
 *
 * **The mapping is a reading of two schemas, not an observation.** BRouter's side is its
 * own source; the cluster's is `ManeuverTypeEnum.proto`, which upstream marks
 * `confidence: unverified` and says maps 1:1 onto Android's `Maneuver.TYPE_*`. Nothing
 * branches on the names, the report carries the raw values, and whether Ford draws the
 * icon these values ask for is the P6 plan §10 question a drive answers.
 *
 * Choices worth stating:
 *
 * - **Roundabouts are counter-clockwise.** BRouter's `RNDB` carries a positive exit number
 *   and is the right-hand-traffic roundabout, which turns counter-clockwise seen from
 *   above; `RNLB` is the left-hand-traffic one. The vehicle is a US truck. (P6.2's script
 *   used the clockwise value; it was a demonstration and drove nowhere.)
 * - **A 180° U-turn is a left one**, for the same right-hand-traffic reason.
 * - **BRouter's `EL`/`ER` are motorway exits**, so they are the slight off-ramps.
 * - **`OFFR` and `BL` are not maneuvers.** Off-route is a hint for a client that is
 *   already lost, and beeline hints appear only in turn-instruction modes this app does not
 *   use; both are skipped rather than put on a cluster as something to do.
 * - **The instruction names no road**, because BRouter's data carries no names.
 */
object Maneuvers {
    const val UNKNOWN = 0
    const val KEEP_LEFT = 3
    const val KEEP_RIGHT = 4
    const val TURN_SLIGHT_LEFT = 5
    const val TURN_SLIGHT_RIGHT = 6
    const val TURN_NORMAL_LEFT = 7
    const val TURN_NORMAL_RIGHT = 8
    const val TURN_SHARP_LEFT = 9
    const val TURN_SHARP_RIGHT = 10
    const val U_TURN_LEFT = 11
    const val U_TURN_RIGHT = 12
    const val OFF_RAMP_SLIGHT_LEFT = 21
    const val OFF_RAMP_SLIGHT_RIGHT = 22
    const val ROUNDABOUT_ENTER_AND_EXIT_CW = 32
    const val ROUNDABOUT_ENTER_AND_EXIT_CCW = 34
    const val STRAIGHT = 36
    const val DESTINATION = 39

    /** The maneuver BRouter's [command] and [exit] describe, or null when it is not one. */
    fun of(command: Int, exit: Int): Maneuver? = when (command) {
        H.CONTINUE -> Maneuver(STRAIGHT, 0, "Continue straight")
        H.TURN_LEFT -> Maneuver(TURN_NORMAL_LEFT, 0, "Turn left")
        H.TURN_SLIGHT_LEFT -> Maneuver(TURN_SLIGHT_LEFT, 0, "Turn slightly left")
        H.TURN_SHARP_LEFT -> Maneuver(TURN_SHARP_LEFT, 0, "Turn sharp left")
        H.TURN_RIGHT -> Maneuver(TURN_NORMAL_RIGHT, 0, "Turn right")
        H.TURN_SLIGHT_RIGHT -> Maneuver(TURN_SLIGHT_RIGHT, 0, "Turn slightly right")
        H.TURN_SHARP_RIGHT -> Maneuver(TURN_SHARP_RIGHT, 0, "Turn sharp right")
        H.KEEP_LEFT -> Maneuver(KEEP_LEFT, 0, "Keep left")
        H.KEEP_RIGHT -> Maneuver(KEEP_RIGHT, 0, "Keep right")
        H.U_TURN_LEFT, H.U_TURN_180 -> Maneuver(U_TURN_LEFT, 0, "Make a U-turn")
        H.U_TURN_RIGHT -> Maneuver(U_TURN_RIGHT, 0, "Make a U-turn")
        H.EXIT_LEFT -> Maneuver(OFF_RAMP_SLIGHT_LEFT, 0, "Take the exit on the left")
        H.EXIT_RIGHT -> Maneuver(OFF_RAMP_SLIGHT_RIGHT, 0, "Take the exit on the right")
        H.ROUNDABOUT -> roundabout(ROUNDABOUT_ENTER_AND_EXIT_CCW, exit)
        H.ROUNDABOUT_LEFT -> roundabout(ROUNDABOUT_ENTER_AND_EXIT_CW, -exit)
        H.END -> Maneuver(DESTINATION, 0, "Arrive at the destination")
        else -> null
    }

    private fun roundabout(type: Int, exit: Int): Maneuver =
        if (exit > 0) {
            Maneuver(type, exit, "At the roundabout, take exit $exit")
        } else {
            Maneuver(type, 0, "Go through the roundabout")
        }
}
