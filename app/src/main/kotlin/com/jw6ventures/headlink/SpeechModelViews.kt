package com.jw6ventures.headlink

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Settings → Voice input (spec §2.1 as amended 11 November 2026): the speech model file
 * and a check of it. The listening itself arrives with the Bluetooth microphone and the
 * voice conversation; this card is where the operator supplies the model those use
 * ([SpeechModel]). It names the file format and nothing about where to get one.
 */
class SpeechModelCard(
    private val activity: Activity,
    private val ui: PhoneViews,
    chooseFile: () -> Unit,
) {
    private val statusText = ui.body(selectable = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val checkText = ui.body(selectable = true).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    private val checkButton = Button(activity).apply {
        setText(R.string.speech_model_check)
        setOnClickListener { check() }
    }
    private val removeButton = Button(activity).apply {
        setText(R.string.speech_model_remove)
        setOnClickListener {
            SpeechModel.forget(activity)
            checkText.visibility = View.GONE
            render()
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private var checking = false

    val view: LinearLayout = ui.card(
        ui.title(R.string.speech_model_title),
        ui.caption().apply { setText(R.string.speech_model_caption) },
        statusText,
        ui.withHelp(
            Button(activity).apply {
                setText(R.string.speech_model_choose)
                setOnClickListener { chooseFile() }
            },
            R.string.speech_model_choose, R.string.speech_model_choose_help,
        ),
        ui.withHelp(checkButton, R.string.speech_model_check, R.string.speech_model_check_help),
        checkText,
        removeButton,
    )

    /** The chosen file's name, or that there is none. */
    fun render() {
        val file = SpeechModel.file(activity)
        statusText.text = when (file) {
            null -> activity.getString(R.string.speech_model_none)
            else -> activity.getString(R.string.speech_model_file, SpeechModel.name(activity, file) ?: file.lastPathSegment.orEmpty())
        }
        checkButton.isEnabled = file != null && !checking
        removeButton.visibility = if (file == null) View.GONE else View.VISIBLE
    }

    /** After a new file was chosen: the old check's result no longer applies. */
    fun chosen() {
        checkText.visibility = View.GONE
        render()
    }

    private fun check() {
        if (checking) return
        checking = true
        checkButton.isEnabled = false
        checkText.visibility = View.VISIBLE
        checkText.setText(R.string.speech_model_checking)
        val app = activity.applicationContext
        worker.execute {
            val result = SpeechModel.check(app)
            main.post {
                checking = false
                if (activity.isDestroyed) return@post
                checkText.text = describe(result)
                render()
            }
        }
    }

    private fun describe(result: SpeechCheck): String = when (result) {
        is SpeechCheck.Ready -> activity.getString(
            if (result.model.englishOnly) R.string.speech_model_ready_en else R.string.speech_model_ready_multi,
            result.model.key,
            seconds(result.loadMs),
            seconds(result.fiveSecondsMs),
        )
        is SpeechCheck.Failed -> activity.getString(R.string.speech_model_check_failed, result.model.key)
        is SpeechCheck.Unavailable -> activity.getString(
            when (result.problem) {
                SpeechProblem.NO_FILE -> R.string.speech_model_none
                SpeechProblem.UNREADABLE -> R.string.speech_model_unreadable
                SpeechProblem.NOT_A_MODEL -> R.string.speech_model_not_a_model
                SpeechProblem.CPU -> R.string.speech_model_cpu
                SpeechProblem.LIBRARY -> R.string.speech_model_library
            },
        )
    }

    private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f", ms / 1000.0)

    private companion object {
        /** One check at a time, off the main thread; loading a model takes a second or more. */
        val worker = Executors.newSingleThreadExecutor { Thread(it, "speech-model-check").apply { isDaemon = true } }
    }
}
