package org.openautoandroid.source

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

/**
 * The optional call controls' companion in-call service (P7.9, plan §5.8).
 *
 * **Draws nothing.** It declares neither `METADATA_IN_CALL_SERVICE_UI` nor the car-mode UI
 * metadata, so Telecom never treats it as the phone's call screen: the real dialer stays
 * the dialer, and this service only receives each [Call] to control it from the car.
 *
 * **Disabled unless the operator turned Call controls on, and only while projecting.** The
 * manifest declares it `android:enabled="false"`; [CallControl.setEnabled] enables it for
 * a session and disables it at the end, and [CallControl.resetAtStartup] disables it on
 * any launch with no session, which covers a crash that left it on. With it disabled
 * Telecom has nothing to bind, so the app never sees a call.
 *
 * **Telecom binds it only with `MANAGE_ONGOING_CALLS` allowed** (docs/p7-backends-results.md
 * §4.2). Current Telecom binds a third-party non-UI in-call service only when its app holds
 * that signature|appop permission (or the privileged `CONTROL_INCALL_EXPERIENCE`); the
 * `CALL_COMPANION_APP` route was removed from `InCallController` in Android 11. The app-op
 * is allowed once over adb ([CallControl.GRANT_COMMAND]); without it [CallControl] never
 * enables this component and uses the call-notification route instead.
 *
 * Every callback here arrives on the main thread, and all state lives in [CallControl].
 * Nothing about a call — number, name, digit — is logged (plan §5.8, spec §10).
 */
class CallCompanionService : InCallService() {
    private val callbacks = HashMap<Call, Call.Callback>()

    override fun onCreate() {
        super.onCreate()
        CallControl.bound(this)
    }

    override fun onDestroy() {
        for ((call, callback) in callbacks) call.unregisterCallback(callback)
        callbacks.clear()
        CallControl.unbound(this)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = CallControl.callsChanged(this@CallCompanionService)

            override fun onDetailsChanged(call: Call, details: Call.Details) =
                CallControl.callsChanged(this@CallCompanionService)
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        CallControl.callAdded()
        CallControl.callsChanged(this)
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let(call::unregisterCallback)
        CallControl.callsChanged(this)
    }

    /**
     * The mute state on API 33, where [onMuteStateChanged] does not exist yet. Deprecated
     * from 34 in favour of it, and still delivered there; both land in the same place.
     */
    @Deprecated("Replaced by onMuteStateChanged from API 34; kept for API 33")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        CallControl.muteChanged(audioState.isMuted)
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        CallControl.muteChanged(isMuted)
    }

    /** The calls Telecom has given this service, oldest first. */
    fun liveCalls(): List<Call> = calls
}
