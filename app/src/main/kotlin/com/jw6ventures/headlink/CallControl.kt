package com.jw6ventures.headlink

import android.app.ActivityOptions
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The optional call controls (P7.9): answer, decline, hang up, mute and keypad
 * tones for the flex tile, through [CallCompanionService].
 *
 * **Two routes, chosen per session by [CallRules.route]** (docs/dashboard.md):
 *
 *  * **Telecom**, when the operator has allowed `MANAGE_ONGOING_CALLS` once over adb
 *    ([GRANT_COMMAND], [fullControlsGranted]). The in-call service, declared disabled, is
 *    enabled by [setEnabled] for the projecting session and disabled at its end; Telecom
 *    binds it and every control works. Current Telecom binds a third-party in-call
 *    service on nothing less.
 *  * **The call notification**, otherwise. The component stays disabled, so Telecom has
 *    nothing to bind, and [MediaAccessService] forwards call notifications — and only
 *    those — to [fallbackPosted]: Answer, Decline and Hang up through the dialer's own
 *    intents. Mute and the keypad report themselves unavailable through
 *    [CallState.canMute] and [CallState.canDial]. Needs notification access; reads nothing
 *    unless Call controls are on. It also covers the Telecom route until the companion is
 *    bound, e.g. a call already ringing when the session started.
 *
 * Wiring the setting and the session to [setEnabled] is the settings slice's job; this
 * object takes a plain flag.
 *
 * **Threads.** State changes and listener calls happen on the main thread. The actions may
 * be called from any thread (the dashboard's touch path included); they post to main.
 *
 * **Privacy.** The caller's name or number is in [state] for the tile to draw, and nowhere
 * else. No digit, number or name is ever logged; [report] counts presses only.
 */
object CallControl {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(CallState) -> Unit>()

    /** The call the tile shows now, or [CallState.NONE]. Read from any thread. */
    @Volatile
    var state: CallState = CallState.NONE
        private set

    /** Whether the operator's setting and a session have [setEnabled] this on. */
    @Volatile
    var wanted = false
        private set

    /** The route [setEnabled] chose for this session; [CallRoute.OFF] between sessions. */
    @Volatile
    var route: CallRoute = CallRoute.OFF
        private set

    /**
     * What the operator runs once, from a computer with adb, to allow mute and the keypad.
     * For the settings screen: "To enable mute and keypad, run once from a computer: …".
     * `MANAGE_ONGOING_CALLS` is signature|appop, so there is no prompt an app can show
     * for it, and this app does not pose as a watch companion to be given it.
     * `--user 0` names the phone's owner, because a phone with more than one user (the
     * Pixel this was built for) otherwise leaves it to adb to guess.
     */
    const val GRANT_COMMAND = "adb shell appops set --user 0 com.jw6ventures.headlink MANAGE_ONGOING_CALLS allow"

    /** The permission whose app-op [GRANT_COMMAND] allows. */
    private const val MANAGE_ONGOING_CALLS = "android.permission.MANAGE_ONGOING_CALLS"

    /**
     * Whether `MANAGE_ONGOING_CALLS` is allowed for this app, i.e. whether Telecom will
     * bind the in-call service and mute and the keypad can work.
     *
     * The op is looked up with `AppOpsManager.permissionToOp` rather than named: its
     * `OPSTR_` constant is not in the public SDK on API 33–36, and a platform that has no
     * op for the permission (null) is treated as not granted.
     *
     * **The op decides, not the permission** (docs/dashboard.md). An app that is
     * not platform-signed is never granted `signature|appop` `MANAGE_ONGOING_CALLS`
     * itself — `pm` lists it as requested and nothing more, and `pm grant` refuses it —
     * yet with the op allowed over adb Telecom binds the in-call service all the same,
     * because `PermissionChecker` reads an app-op permission's op first: `MODE_ALLOWED` is
     * granted whatever the permission's state, and only `MODE_DEFAULT` falls back to the
     * permission. This does the same. (Until part 2b it also required
     * `checkSelfPermission`, so on the emulator it chose the notification route while
     * Telecom stood ready to bind.)
     */
    fun fullControlsGranted(context: Context): Boolean {
        val op = AppOpsManager.permissionToOp(MANAGE_ONGOING_CALLS) ?: return false
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = try {
            ops.unsafeCheckOpNoThrow(op, android.os.Process.myUid(), context.packageName)
        } catch (e: IllegalArgumentException) {
            // An op name this release does not know.
            return false
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT ->
                context.checkSelfPermission(MANAGE_ONGOING_CALLS) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    // Main thread only.
    private var service: CallCompanionService? = null
    private var dtmfCall: Call? = null

    /** The microphone mute as Telecom last reported it; it outlives any one call. */
    private var micMuted = false
    private var fallbackKey: String? = null
    private var answerIntent: PendingIntent? = null
    private var declineIntent: PendingIntent? = null
    private var hangUpIntent: PendingIntent? = null

    @Volatile private var bindings = 0L
    @Volatile private var callsSeen = 0L
    @Volatile private var fallbackCalls = 0L
    @Volatile private var answered = 0L
    @Volatile private var declined = 0L
    @Volatile private var hungUp = 0L
    @Volatile private var muteToggles = 0L
    @Volatile private var keypadPresses = 0L
    @Volatile private var noCall = 0L
    @Volatile private var intentFailures = 0L
    @Volatile private var componentChanges = 0L

    fun component(context: Context): ComponentName = ComponentName(context, CallCompanionService::class.java)

    fun addListener(listener: (CallState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (CallState) -> Unit) {
        listeners.remove(listener)
    }

    /** Whether Android has the in-call component enabled right now. */
    fun componentEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /**
     * Turns Call controls on or off for a session. Call with true when a session starts
     * projecting with Call controls on, and false when it ends or the setting goes off.
     * Returns the route chosen.
     *
     * The in-call component is enabled only for [CallRoute.TELECOM]. Off, or any other
     * route, returns it to its manifest default, which is disabled, so the manifest stays
     * the one statement of what an idle app exposes. `DONT_KILL_APP`, because this runs
     * inside a live session. Any thread; quick binder calls.
     */
    fun setEnabled(context: Context, on: Boolean): CallRoute {
        val chosen = CallRules.route(on, on && fullControlsGranted(context), on && MediaAccessService.enabled(context))
        wanted = on
        route = chosen
        val target = if (chosen == CallRoute.TELECOM) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        val packages = context.packageManager
        if (packages.getComponentEnabledSetting(component(context)) != target) {
            packages.setComponentEnabledSetting(component(context), target, PackageManager.DONT_KILL_APP)
            componentChanges++
        }
        if (on) {
            // A call already ringing when the session starts is in the shade, not in a
            // callback yet; the listener reads it once if the fallback is the route.
            NotificationFeed.source?.requestActive()
        } else {
            main.post { clearFallback() }
        }
        return chosen
    }

    /**
     * From [SourceApplication] on every process start: no session can be running in a
     * process that has just started, so a component left enabled — a crash mid-drive, or
     * the process killed — is turned off. Covers "no background cost".
     */
    fun resetAtStartup(context: Context) {
        if (SessionController.stage != Stage.IDLE) return
        try {
            if (context.packageManager.getComponentEnabledSetting(component(context)) !=
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            ) {
                setEnabled(context, false)
            }
        } catch (e: IllegalArgumentException) {
            // The component is missing from this build; nothing to reset.
        }
    }

    /** Answers the ringing call. */
    fun answer() {
        main.post {
            val ringing = ringingCall()
            when {
                ringing != null -> {
                    ringing.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
                    answered++
                }
                sendFallback(answerIntent) -> answered++
                else -> noCall++
            }
        }
    }

    /** Declines the ringing call, without a text reply. */
    fun decline() {
        main.post {
            val ringing = ringingCall()
            when {
                ringing != null -> {
                    ringing.reject(false, null)
                    declined++
                }
                sendFallback(declineIntent) -> declined++
                else -> noCall++
            }
        }
    }

    /** Hangs up the call the tile shows (connected, dialling or held). */
    fun hangUp() {
        main.post {
            val call = shownCall()
            when {
                call != null -> {
                    call.disconnect()
                    hungUp++
                }
                sendFallback(hangUpIntent) -> hungUp++
                else -> noCall++
            }
        }
    }

    /**
     * Mutes or unmutes the phone's microphone. The state the tile draws comes back
     * through `onCallAudioStateChanged`, so a mute pressed on the phone or the truck
     * shows too; this does not set [CallState.muted] itself.
     */
    fun setMuted(muted: Boolean) {
        main.post {
            val service = service
            if (service == null || !state.canMute) {
                noCall++
            } else {
                service.setMuted(muted)
                muteToggles++
            }
        }
    }

    /**
     * Starts a keypad tone for [key], one of `0-9 * #`. The tone lasts until [dtmfUp], so
     * a held key is a held tone, as on a real keypad. **The key is never logged.**
     */
    fun dtmfDown(key: Char) {
        main.post {
            val call = shownCall()
            if (call == null || !state.canDial || !CallRules.isDtmf(key)) {
                noCall++
            } else {
                dtmfCall?.stopDtmfTone()
                call.playDtmfTone(key)
                dtmfCall = call
                keypadPresses++
            }
        }
    }

    /** Stops the tone [dtmfDown] started; also call it when a press is cancelled. */
    fun dtmfUp() {
        main.post {
            dtmfCall?.stopDtmfTone()
            dtmfCall = null
        }
    }

    // --- From CallCompanionService, main thread. ---

    internal fun bound(service: CallCompanionService) {
        this.service = service
        bindings++
        // Telecom is authoritative once it is here; the notification's view is dropped.
        clearFallback()
    }

    internal fun unbound(service: CallCompanionService) {
        if (this.service !== service) return
        this.service = null
        dtmfCall = null
        micMuted = false
        publish(CallState.NONE)
    }

    internal fun callAdded() {
        callsSeen++
    }

    internal fun callsChanged(service: CallCompanionService) {
        if (this.service !== service) return
        val calls = service.liveCalls().map { stateOf(it, micMuted) }
        publish(CallRules.pick(calls))
    }

    internal fun muteChanged(muted: Boolean) {
        micMuted = muted
        if (state.source == CallSource.TELECOM && state.muted != muted) publish(state.copy(muted = muted))
    }

    // --- From MediaAccessService, main thread: the CallStyle fallback. ---

    /**
     * Whether call notifications should be forwarded: Call controls on and no companion
     * bound, which is the notification route, or the Telecom route before Telecom binds.
     */
    val fallbackWanted: Boolean
        get() = wanted && route != CallRoute.UNAVAILABLE && service == null

    /**
     * A call notification was posted or updated. [callType] is `EXTRA_CALL_TYPE`; the
     * intents are the notification's own Answer, Decline and Hang up.
     */
    internal fun fallbackPosted(
        key: String,
        callType: Int,
        caller: String,
        connectedSinceMillis: Long,
        answer: PendingIntent?,
        decline: PendingIntent?,
        hangUp: PendingIntent?,
    ) {
        if (!fallbackWanted) return
        val phase = CallRules.phaseOfCallType(callType)
        if (phase == CallPhase.NONE) return
        if (fallbackKey != key) fallbackCalls++
        fallbackKey = key
        answerIntent = answer
        declineIntent = decline
        hangUpIntent = hangUp
        publish(
            CallState(
                phase = phase,
                caller = caller,
                connectedSinceMillis = if (phase == CallPhase.ACTIVE) connectedSinceMillis else -1,
                muted = false,
                source = CallSource.NOTIFICATION,
            ),
        )
    }

    internal fun fallbackRemoved(key: String) {
        if (key == fallbackKey) clearFallback()
    }

    private fun clearFallback() {
        if (fallbackKey == null) return
        fallbackKey = null
        answerIntent = null
        declineIntent = null
        hangUpIntent = null
        if (state.source == CallSource.NOTIFICATION) publish(CallState.NONE)
    }

    /**
     * Sends one of the dialer's own intents. They usually reach a receiver or service in
     * the dialer, but may open its activity, so background activity starts are allowed
     * on the send where Android lets the sender say so.
     */
    private fun sendFallback(intent: PendingIntent?): Boolean {
        if (intent == null || state.source != CallSource.NOTIFICATION) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Deprecated in 36 for ALLOW_ALWAYS, which 34 and 35 do not have; the
                // same value on every release this app supports.
                @Suppress("DEPRECATION")
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                intent.send(null, 0, null, null, null, null, options.toBundle())
            } else {
                intent.send()
            }
            true
        } catch (e: PendingIntent.CanceledException) {
            intentFailures++
            false
        }
    }

    private fun ringingCall(): Call? =
        service?.liveCalls()?.firstOrNull { CallRules.phaseOf(it.details.state) == CallPhase.RINGING }

    private fun shownCall(): Call? {
        val service = service ?: return null
        val shown = state
        if (shown.source != CallSource.TELECOM) return null
        return service.liveCalls().firstOrNull {
            val phase = CallRules.phaseOf(it.details.state)
            phase == shown.phase && phase != CallPhase.ENDED && phase != CallPhase.NONE
        }
    }

    private fun stateOf(call: Call, muted: Boolean): CallState {
        val details = call.details
        val phase = CallRules.phaseOf(details.state)
        return CallState(
            phase = phase,
            caller = CallRules.caller(
                details.contactDisplayName,
                details.callerDisplayName,
                details.handle?.schemeSpecificPart,
                details.handlePresentation,
            ),
            connectedSinceMillis = if (phase == CallPhase.ACTIVE && details.connectTimeMillis > 0) {
                details.connectTimeMillis
            } else {
                -1
            },
            muted = muted,
            source = CallSource.TELECOM,
        )
    }

    private fun publish(next: CallState) {
        if (next == state) return
        state = next
        if (next.phase == CallPhase.NONE || next.phase == CallPhase.ENDED) {
            dtmfCall?.stopDtmfTone()
            dtmfCall = null
        }
        for (listener in listeners) listener(next)
    }

    /** `key=value` lines for the session report: counts only, never a caller or a digit. */
    fun report(): String =
        "call_controls_wanted=$wanted call_route=${route.name.lowercase()} call_companion_bindings=$bindings call_calls_seen=$callsSeen " +
            "call_fallback_calls=$fallbackCalls call_answered=$answered call_declined=$declined " +
            "call_hung_up=$hungUp call_mute_toggles=$muteToggles call_keypad_presses=$keypadPresses " +
            "call_no_call=$noCall call_intent_failures=$intentFailures " +
            "call_component_changes=$componentChanges call_phase=${state.phase.name.lowercase()} " +
            "call_source=${state.source.name.lowercase()}\n"
}
