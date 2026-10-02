package org.openautoandroid.source

import java.util.Locale

/**
 * What the Home status card does next to its sentence (P7 plan §6.2). At most one per
 * state: a status with two buttons is a status the driver has to read twice.
 */
enum class StatusAction(val label: String) {
    NONE(""),
    CONNECT("Connect"),
    STOP("Stop"),
    /** Another attempt, for a refusal the operator can answer differently this time. */
    TRY_AGAIN("Try again"),
    OPEN_DIAGNOSTICS("Open Diagnostics"),
    /** Arms first-use trust for one attempt and connects, after a confirmation. */
    TRUST_AND_CONNECT("Trust and connect"),
    /** Claims an accessory that did not match the filter: still the operator's choice. */
    USE_LISTED("Use it anyway"),
}

/** How the card is coloured: the sentence says what, the colour says whether to worry. */
enum class StatusTone { QUIET, BUSY, GOOD, PROBLEM }

class Status(val sentence: String, val action: StatusAction, val tone: StatusTone)

/**
 * The phone's status in plain words (P7 plan §6.2): one sentence and at most one action
 * for the Home card, and the same sentence for the session notification.
 *
 * Pure JVM, so tools/jni-host-test holds every row of the plan's table to its wording,
 * and so the notification (which [ProjectionService] builds, outside this slice) can call
 * the same builder rather than a second copy of the table.
 *
 * **Nothing becomes less visible than it was.** Before P7.7 the screen's first line was
 * `Stage: X (last completed: Y)` and the result line named the final category. A category
 * this table does not know is shown by its raw name with Open Diagnostics beside it, and
 * Diagnostics still carries both of those lines word for word.
 *
 * The destination name may appear in the sentence. It is the operator's own and is shown
 * only on the phone and in its notification, as the connection screen always showed it;
 * nothing here reaches the report.
 */
object StatusText {
    /**
     * Endings that are not failures: Stop pressed, a lab or diagnostic run reaching its
     * planned end, or the head unit closing the session (turning the vehicle off). After
     * one, Home goes back to saying what happens next rather than what just happened.
     */
    val ORDINARY_ENDINGS = setOf("-", "CANCELLED", "PROJECTION_COMPLETE", "PEER_SHUTDOWN", "HOLD_COMPLETE", "STAGE_LIMIT")

    /** The native session's final category for a certificate that failed the pin check. */
    const val CERT_POLICY = "TLS_CERT_POLICY"

    /**
     * `auth_status` for the head unit refusing the source's identity after TLS; the same
     * test [ConnectionActivity] has made since P3 to show `source_auth_rejected`.
     */
    const val AUTH_REJECTED = "-3"

    /**
     * [stage] and [finalCategory] are [SessionController]'s; [authStatus] is the native
     * report's `auth_status`, or null. [unplugged] says the current or last attempt saw its
     * accessory detached, which is how an unplug mid-session ends (as a stop, so its
     * category alone cannot say so). [mismatch] is an armed attempt holding an accessory
     * that did not match the filter. [destination] is what the route is to, if any.
     */
    fun of(
        stage: Stage,
        finalCategory: String,
        authStatus: String?,
        autoStart: Boolean,
        destination: String?,
        unplugged: Boolean = false,
        mismatch: Boolean = false,
    ): Status = when (stage) {
        Stage.IDLE -> idle(finalCategory, authStatus, autoStart, unplugged)
        Stage.ARMED ->
            if (mismatch) {
                Status("A USB device is connected, but it doesn't look like a head unit", StatusAction.USE_LISTED, StatusTone.PROBLEM)
            } else {
                Status("Waiting for the head unit — plug in the USB cable", StatusAction.STOP, StatusTone.BUSY)
            }
        Stage.PERMISSION -> Status("Allow USB access on the prompt", StatusAction.NONE, StatusTone.BUSY)
        Stage.VERSION, Stage.TLS, Stage.AUTH, Stage.DISCOVERY, Stage.READY, Stage.SETUP ->
            Status("Connecting to the head unit…", StatusAction.STOP, StatusTone.BUSY)
        Stage.PROJECTING -> Status(projecting(destination), StatusAction.STOP, StatusTone.GOOD)
        Stage.STOPPING -> Status("Stopping…", StatusAction.NONE, StatusTone.BUSY)
    }

    /**
     * The notification's line while a session runs: the Home sentence, since the plan
     * wants the two to say the same thing. A session's notification only exists between
     * ARMED and IDLE, so the idle rows never reach it.
     */
    fun notification(stage: Stage, destination: String?): String =
        of(stage, "-", null, autoStart = false, destination = destination).sentence

    private fun projecting(destination: String?): String =
        if (destination.isNullOrBlank()) "Projecting" else "Projecting · routing to $destination"

    private fun idle(category: String, authStatus: String?, autoStart: Boolean, unplugged: Boolean): Status = when {
        // An unplug ends a session as a stop, an I/O error, or a missing accessory,
        // depending on when it lands; all of them are the same thing to the driver.
        unplugged || category == "NO_ACCESSORY" -> Status("Unplugged", StatusAction.NONE, StatusTone.QUIET)
        category in ORDINARY_ENDINGS ->
            if (autoStart) {
                Status("Plug in to start projecting", StatusAction.NONE, StatusTone.QUIET)
            } else {
                Status("Ready to connect", StatusAction.CONNECT, StatusTone.QUIET)
            }
        category == "USB_PERMISSION_DENIED" -> Status("USB access was refused", StatusAction.TRY_AGAIN, StatusTone.PROBLEM)
        category == "AUTH_FAILED" && authStatus == AUTH_REJECTED ->
            Status("The head unit didn't accept this phone's identity", StatusAction.OPEN_DIAGNOSTICS, StatusTone.PROBLEM)
        category == CERT_POLICY -> Status("This head unit isn't trusted yet", StatusAction.TRUST_AND_CONNECT, StatusTone.PROBLEM)
        else -> Status("Ended: $category", StatusAction.OPEN_DIAGNOSTICS, StatusTone.PROBLEM)
    }

    /**
     * The route in words, "Routing to Home · 12 mi · 18 min · arrive 6:40" (§6.5), or
     * null with no destination and no route.
     *
     * [metres] and [seconds] are the route's, [arrival] the clock time already formatted
     * in the saved 12- or 24-hour style ([ClockFormat]), or null when
     * it is not known — the phrase is then left out rather than guessed. [waiting] is a
     * destination held for want of a position, [computing] a search in flight, and
     * [failed] a route that did not come back.
     *
     * [trafficSeconds] is live traffic's delay inside [seconds] (P9.4): "+N min traffic"
     * follows the arrival, rounded as the dashboard's trip panel rounds it
     * ([trafficLabel]), and nothing under half a minute.
     */
    @JvmOverloads
    fun routeSummary(
        destination: String?,
        waiting: Boolean,
        computing: Boolean,
        failed: Boolean,
        metres: Double?,
        seconds: Int?,
        arrival: String?,
        unit: SpeedUnit,
        trafficSeconds: Int = 0,
    ): String? {
        val head = if (destination.isNullOrBlank()) "Route" else "Routing to $destination"
        return when {
            destination == null && metres == null && !computing && !failed -> null
            waiting -> "$head · waiting for a position to route from"
            computing -> "$head · finding a route…"
            failed -> "$head · no route found"
            metres == null || seconds == null -> head
            else -> buildString {
                append(head).append(" · ").append(unit.distance(metres)).append(" · ").append(duration(seconds))
                if (arrival != null) append(" · arrive ").append(arrival)
                trafficLabel(trafficSeconds)?.let { append(" · ").append(it) }
            }
        }
    }

    /**
     * "+7 min traffic" for [seconds] of traffic delay, or null under half a minute: the
     * nearest minute, the same rule as the trip panel's label (`Dashboard.trafficDelayLabel`),
     * so the phone and the car say the same.
     */
    fun trafficLabel(seconds: Int): String? {
        val minutes = Math.round(seconds / 60.0)
        return if (minutes >= 1) "+$minutes min traffic" else null
    }

    /** Minutes, rounded, with hours from an hour up; under a minute says so. */
    fun duration(seconds: Int): String {
        val minutes = (seconds.coerceAtLeast(0) + 30) / 60
        return when {
            minutes < 1 -> "under 1 min"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> String.format(Locale.ROOT, "%d h %d min", minutes / 60, minutes % 60)
        }
    }
}

/**
 * One thing missing before a drive works (§6.1's Setup checklist), in the order the
 * plan lists them. Each has exactly one button that fixes it, which [ConnectionActivity]
 * owns; this only says which are missing.
 */
enum class SetupItem(val title: String, val detail: String, val fix: String) {
    NO_MAP("No map installed", "The dashboard's map pane has nothing to draw.", "Import map"),
    NO_ROUTING("No routing data installed", "Routes and favorites need a BRouter tile.", "Import routing data"),
    /** Routing data is installed, but none for where the phone last was. */
    NO_ROUTING_HERE(
        "No routing data for where you are",
        "None of the installed tiles covers the phone's last known position.",
        "Import routing data",
    ),
    NOTIFICATION_ACCESS(
        "Notification access is off",
        "The car can't show what's playing or list players.",
        "Grant access",
    ),
    LOCATION(
        "Location is off",
        "Routes start from the vehicle's position. Allowed, the phone's own position works too.",
        "Allow location",
    ),
    /**
     * P10 (docs/p10-plan.md §4): Show weather is on, but Breezy Weather has not granted its
     * provider permission. Never listed with Show weather off: weather adds nothing then.
     */
    WEATHER_PERMISSION(
        "Weather permission not granted",
        "Weather is on, but Breezy Weather's data can't be read.",
        "Grant",
    ),
    NO_PINS("No head unit trusted yet", "Plug in and tap Trust.", "Trust and connect"),
    /** Not in the plan's list: the session's own notification, which holds its Stop. */
    SESSION_NOTIFICATIONS(
        "Notifications are blocked",
        "A session still runs, but its notification may be hidden; use Stop on Home.",
        "Allow notifications",
    ),
}

object SetupCheck {
    /**
     * Whether anything the operator turned on reads notifications: media info to show
     * what is playing, the dashboard's notifications, or call controls without the adb
     * grant (their fallback is the dialer's call notification).
     */
    fun notificationAccessWanted(mediaInfo: Boolean, dashboardNotifications: Boolean, callControls: Boolean, fullCalls: Boolean): Boolean =
        mediaInfo || dashboardNotifications || (callControls && !fullCalls)

    /**
     * What is missing, in the plan's order; empty means Ready.
     *
     * [tiles] are the installed routing tiles' names and [hereTile] the tile the phone's
     * last known position needs, or null when there is no position to ask about. Routing
     * from the vehicle's own position needs no phone location, so [location] is an item of
     * its own rather than a condition of the routing ones.
     *
     * [weatherWanted] is Show weather (P10) and [weatherPermission] whether Breezy
     * Weather's permission is held; the item follows the phone's own permissions, after
     * location. Both default to asking nothing, so a caller that predates P10 lists what
     * it always did.
     */
    @JvmOverloads
    fun items(
        maps: Int,
        tiles: Set<String>,
        hereTile: String?,
        notificationAccessWanted: Boolean,
        notificationAccess: Boolean,
        location: Boolean,
        pins: Int,
        sessionNotifications: Boolean,
        weatherWanted: Boolean = false,
        weatherPermission: Boolean = true,
    ): List<SetupItem> = buildList {
        if (maps == 0) add(SetupItem.NO_MAP)
        if (tiles.isEmpty()) add(SetupItem.NO_ROUTING)
        else if (hereTile != null && hereTile !in tiles) add(SetupItem.NO_ROUTING_HERE)
        if (notificationAccessWanted && !notificationAccess) add(SetupItem.NOTIFICATION_ACCESS)
        if (!location) add(SetupItem.LOCATION)
        if (weatherWanted && !weatherPermission) add(SetupItem.WEATHER_PERMISSION)
        if (pins == 0) add(SetupItem.NO_PINS)
        if (!sessionNotifications) add(SetupItem.SESSION_NOTIFICATIONS)
    }
}
