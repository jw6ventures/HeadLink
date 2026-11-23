package com.jw6ventures.headlink

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.widget.Button
import android.widget.Switch

/**
 * Voice input's saved switch (spec §2.1 as amended 11 November 2026): off by default, and
 * with it off the app holds no microphone. The later voice streams add their own parts
 * (the model file, texting, replies, destinations) beside it.
 */
object VoiceInputPrefs {
    private const val FILE = "voice_input"
    private const val ENABLED = "enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, on).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/** What the Settings card says about one capture: a short headline and the numbers behind it. */
object MicTestText {
    fun failure(context: Context, failure: MicFailure): String = context.getString(
        when (failure) {
            MicFailure.NO_PERMISSION -> R.string.mic_fail_permission
            MicFailure.IN_CALL -> R.string.mic_fail_in_call
            MicFailure.NO_BLUETOOTH_MIC -> R.string.mic_fail_no_bluetooth
            MicFailure.ROUTE_REFUSED -> R.string.mic_fail_route_refused
            MicFailure.ROUTE_TIMEOUT -> R.string.mic_fail_route_timeout
            MicFailure.NOT_BLUETOOTH -> R.string.mic_fail_not_bluetooth
            MicFailure.LOST -> R.string.mic_fail_lost
            MicFailure.RECORDER_FAILED -> R.string.mic_fail_recorder
            MicFailure.BUSY -> R.string.mic_fail_busy
            MicFailure.CANCELLED -> R.string.mic_fail_cancelled
        },
    )

    fun result(context: Context, result: MicResult): String = when (result) {
        is MicResult.Failed -> failure(context, result.failure)
        is MicResult.Heard -> {
            val s = result.stats
            if (result.end == MicEnd.NO_SPEECH) {
                context.getString(R.string.mic_test_no_speech, s.routeMs, s.peakDbfs.toInt(), s.noiseDbfs.toInt())
            } else {
                context.getString(
                    R.string.mic_test_heard,
                    result.clip.durationMs / 1000.0,
                    s.speechMs / 1000.0,
                    s.routeMs,
                    s.peakDbfs.toInt(),
                    s.noiseDbfs.toInt(),
                )
            }
        }
    }
}

/**
 * Settings → Voice input: the switch, which asks for Microphone when turned on, the speech
 * model rows ([SpeechModelRows]), and **Test car microphone**, which listens once through the car's hands-free link, as a
 * push-to-talk press will, and says what it heard in numbers. The audio is dropped as
 * soon as the numbers are taken; nothing is played back, saved or reported.
 */
class VoiceInputCard(
    private val activity: Activity,
    private val ui: PhoneViews,
    private val requestMicrophone: () -> Unit,
    private val model: SpeechModelRows,
) {
    private val enabledSwitch = Switch(activity).apply {
        setText(R.string.voice_input_switch)
        minHeight = ui.dp(48)
    }
    private val statusText = ui.caption().apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val testButton = Button(activity).apply {
        setText(R.string.mic_test)
        setOnClickListener { test() }
    }
    private val testText = ui.caption().apply {
        setTextIsSelectable(true)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val testRow = ui.withHelp(testButton, R.string.mic_test, R.string.mic_test_help)
    private var switchQuiet = false
    private var capture: BluetoothMic.Capture? = null

    val view = ui.card(
        ui.title(R.string.voice_input_title),
        ui.caption().apply { setText(R.string.voice_input_caption) },
        ui.withHelp(enabledSwitch, R.string.voice_input_switch, R.string.voice_input_switch_help),
        statusText,
        model.view,
        testRow,
        testText,
    )

    init {
        setSwitch(VoiceInputPrefs.enabled(activity))
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (switchQuiet) return@setOnCheckedChangeListener
            VoiceInputPrefs.setEnabled(activity, checked)
            SessionController.note("voice input turned ${if (checked) "on" else "off"}")
            if (!checked) {
                capture?.cancel()
                testText.text = ""
            }
            if (checked && !permitted()) requestMicrophone()
            render()
        }
        render()
    }

    /** The Microphone answer, from the activity's permission callback. */
    fun onMicrophoneResult(granted: Boolean) {
        SessionController.note("microphone permission ${if (granted) "granted" else "denied"}")
        render()
    }

    /** Re-read on every render: the grant can be taken back in system Settings with this screen up. */
    fun render() {
        val on = VoiceInputPrefs.enabled(activity)
        if (enabledSwitch.isChecked != on) setSwitch(on)
        statusText.visibility = if (on) View.VISIBLE else View.GONE
        testRow.visibility = if (on) View.VISIBLE else View.GONE
        testText.visibility = if (on) View.VISIBLE else View.GONE
        model.view.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        model.render()
        statusText.text = when {
            !permitted() -> activity.getString(R.string.mic_fail_permission)
            BluetoothMic.available(activity) -> activity.getString(R.string.voice_input_car_mic_ready)
            else -> activity.getString(R.string.mic_fail_no_bluetooth)
        }
        testButton.setText(if (capture != null) R.string.mic_test_stop else R.string.mic_test)
    }

    /** Stops a test still listening, as leaving the screen does. */
    fun stop() {
        capture?.cancel()
    }

    private fun test() {
        capture?.let {
            it.finish()
            return
        }
        if (!permitted()) {
            requestMicrophone()
            return
        }
        testText.setText(R.string.mic_test_listening)
        capture = BluetoothMic.start(activity) { result ->
            capture = null
            if (!activity.isDestroyed) {
                testText.text = MicTestText.result(activity, result)
                render()
            }
        }
        render()
    }

    private fun permitted() =
        activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun setSwitch(on: Boolean) {
        switchQuiet = true
        enabledSwitch.isChecked = on
        switchQuiet = false
    }
}
