package org.openautoandroid.source

/** Where a call stands, as the flex tile needs it (P7.9, plan §5.8). */
enum class CallPhase {
    /** No call: the tile shows notifications or its empty state. */
    NONE,

    /** Incoming and not answered: Answer and Decline. */
    RINGING,

    /** Outgoing and not yet answered: Hang up. */
    DIALING,

    /** Connected: Mute, Keypad and Hang up, and a running duration. */
    ACTIVE,

    /** Connected but on hold: Hang up. */
    HOLDING,

    /** Disconnected; about to be removed. The tile may show "Call ended" briefly. */
    ENDED,
}

/** Which route a [CallState] came through, and so which controls work. */
enum class CallSource {
    NONE,

    /** The companion `InCallService`: every control works. */
    TELECOM,

    /**
     * The dialer's `CallStyle` notification (plan §5.8's fallback): Answer, Decline and
     * Hang up through its own intents; no mute, no keypad.
     */
    NOTIFICATION,
}

/**
 * One call as the dashboard draws it. Pure JVM: host-tested (CallStateTest) and safe to
 * hand to the render thread.
 *
 * **Privacy.** [caller] is a name or a number. It is drawn on the tile and nowhere else:
 * never logged, never in a report (spec §10, plan §9 item 4).
 */
data class CallState(
    val phase: CallPhase = CallPhase.NONE,
    /** Contact name, else the network's caller name, else the number, else a fixed phrase. */
    val caller: String = "",
    /**
     * Wall-clock milliseconds when the call connected, or -1 before it has. The duration
     * is drawn from this against the phone clock, so no per-second update is needed.
     */
    val connectedSinceMillis: Long = -1,
    /** The phone's microphone mute, from Telecom; always false from [CallSource.NOTIFICATION]. */
    val muted: Boolean = false,
    val source: CallSource = CallSource.NONE,
) {
    /** Whether Mute and the keypad can work for this call. */
    val canMute: Boolean get() = source == CallSource.TELECOM && phase == CallPhase.ACTIVE
    val canDial: Boolean get() = source == CallSource.TELECOM && phase == CallPhase.ACTIVE

    companion object {
        val NONE = CallState()
    }
}

/**
 * Which way Call controls reach calls this session (P7.9). Reported by name, never with
 * anything that identifies a call.
 */
enum class CallRoute {
    /** The Call controls setting is off: nothing is enabled and nothing is read. */
    OFF,

    /**
     * The companion in-call service, because `MANAGE_ONGOING_CALLS` is allowed: answer,
     * decline, hang up, mute and keypad.
     */
    TELECOM,

    /** The dialer's `CallStyle` notification: answer, decline and hang up only. */
    NOTIFICATION,

    /** On, but neither the app-op nor notification access is granted: no route at all. */
    UNAVAILABLE,
}

/** The pure mapping from Telecom's and the notification's numbers to [CallState]. */
object CallRules {
    // android.telecom.Call.STATE_*, mirrored so this object stays free of Android classes.
    // Public, stable constants of the in-call API.
    const val STATE_NEW = 0
    const val STATE_DIALING = 1
    const val STATE_RINGING = 2
    const val STATE_HOLDING = 3
    const val STATE_ACTIVE = 4
    const val STATE_DISCONNECTED = 7
    const val STATE_SELECT_PHONE_ACCOUNT = 8
    const val STATE_CONNECTING = 9
    const val STATE_DISCONNECTING = 10
    const val STATE_PULLING_CALL = 11
    const val STATE_AUDIO_PROCESSING = 12
    const val STATE_SIMULATED_RINGING = 13

    // Notification.CallStyle.CALL_TYPE_*.
    const val CALL_TYPE_INCOMING = 1
    const val CALL_TYPE_ONGOING = 2
    const val CALL_TYPE_SCREENING = 3

    // TelecomManager.PRESENTATION_*: whether the network let the number through.
    const val PRESENTATION_ALLOWED = 1
    const val PRESENTATION_RESTRICTED = 2
    const val PRESENTATION_UNKNOWN = 3
    const val PRESENTATION_PAYPHONE = 4
    const val PRESENTATION_UNAVAILABLE = 5

    /** The keys a keypad may send. */
    const val DTMF_KEYS = "0123456789*#"

    /**
     * Telecom's call state as a phase.
     *
     * Everything before the far end answers an outgoing call is DIALING (new, connecting,
     * choosing a SIM, pulling from another device). Audio processing is a call being
     * screened or held by an app before it rings, and simulated ringing is that app
     * letting it ring: the driver can answer only the second, so only it is RINGING.
     */
    fun phaseOf(telecomState: Int): CallPhase = when (telecomState) {
        STATE_RINGING, STATE_SIMULATED_RINGING -> CallPhase.RINGING
        STATE_NEW, STATE_DIALING, STATE_CONNECTING, STATE_SELECT_PHONE_ACCOUNT, STATE_PULLING_CALL -> CallPhase.DIALING
        STATE_ACTIVE, STATE_AUDIO_PROCESSING -> CallPhase.ACTIVE
        STATE_HOLDING -> CallPhase.HOLDING
        STATE_DISCONNECTED, STATE_DISCONNECTING -> CallPhase.ENDED
        else -> CallPhase.NONE
    }

    /**
     * Who is calling, in the order a phone's own call screen uses: the contact's name,
     * then the name the network supplied, then the number. A withheld number says so
     * rather than showing an empty tile.
     */
    fun caller(contactName: String?, callerDisplayName: String?, number: String?, presentation: Int): String {
        contactName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        callerDisplayName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (presentation == PRESENTATION_RESTRICTED) return "Private number"
        if (presentation == PRESENTATION_PAYPHONE) return "Payphone"
        number?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return "Unknown caller"
    }

    /**
     * The call the tile shows when there are several: one that is ringing first, because
     * it is the one that needs a decision now; then the connected one; then one being
     * dialled; then one on hold. [ENDED][CallPhase.ENDED] only when nothing else is left.
     */
    fun pick(calls: List<CallState>): CallState {
        val rank = listOf(CallPhase.RINGING, CallPhase.ACTIVE, CallPhase.DIALING, CallPhase.HOLDING, CallPhase.ENDED)
        for (phase in rank) calls.firstOrNull { it.phase == phase }?.let { return it }
        return CallState.NONE
    }

    /** A `CallStyle` notification's call type as a phase, for the fallback. */
    fun phaseOfCallType(callType: Int): CallPhase = when (callType) {
        CALL_TYPE_INCOMING -> CallPhase.RINGING
        // A call being screened is still waiting for the driver's decision.
        CALL_TYPE_SCREENING -> CallPhase.RINGING
        CALL_TYPE_ONGOING -> CallPhase.ACTIVE
        else -> CallPhase.NONE
    }

    /**
     * The route for a session. The in-call service is enabled only for [CallRoute.TELECOM]:
     * without the app-op Telecom would not bind it, and an enabled component that is never
     * bound is exactly the kind of idle exposure the setting exists to avoid. Notification
     * access is not needed for the Telecom route, which is its own authorisation.
     */
    fun route(settingOn: Boolean, ongoingCallsAllowed: Boolean, notificationAccess: Boolean): CallRoute = when {
        !settingOn -> CallRoute.OFF
        ongoingCallsAllowed -> CallRoute.TELECOM
        notificationAccess -> CallRoute.NOTIFICATION
        else -> CallRoute.UNAVAILABLE
    }

    /** Whether [key] is a key the keypad sends. */
    fun isDtmf(key: Char): Boolean = key in DTMF_KEYS
}
