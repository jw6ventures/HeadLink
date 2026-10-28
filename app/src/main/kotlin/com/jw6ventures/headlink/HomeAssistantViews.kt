package com.jw6ventures.headlink

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch

/**
 * Settings → Home Assistant: the switch, the server address, the token (typed into a masked
 * field and never put back in it, as traffic's key is), the service and the target, Save,
 * and Check. Everything is saved through [HomeAssistantPrefs]; the switch can only be
 * turned on once [HomeAssistantSettings.configured].
 *
 * Nothing typed here is noted or reported: the notes say only that something was saved.
 */
class HomeAssistantCard(
    private val activity: Activity,
    private val ui: PhoneViews,
) {
    private val enabledSwitch = Switch(activity).apply {
        setText(R.string.ha_switch)
        minHeight = ui.dp(48)
    }
    private val urlInput = input(R.string.ha_url_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    private val tokenInput = input(
        R.string.ha_token_hint,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
    ).apply {
        // Not offered to an autofill service or password manager to keep.
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    private val serviceInput = input(R.string.ha_service_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    private val targetInput = input(R.string.ha_target_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    private val tokenText = ui.caption()
    private val removeTokenButton = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
        setText(R.string.ha_token_remove)
        setOnClickListener { removeToken() }
    }
    private val saveText = ui.caption().apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val checkButton = Button(activity).apply {
        setText(R.string.ha_check)
        setOnClickListener { check() }
    }
    private val checkText = ui.caption().apply {
        setTextIsSelectable(true)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private var switchQuiet = false
    private var tokenInvalid = false
    private var checking = false

    val view = ui.card(
        ui.title(R.string.ha_title),
        ui.caption().apply { setText(R.string.ha_caption) },
        ui.withHelp(enabledSwitch, R.string.ha_switch, R.string.ha_switch_help),
        ui.labelled(R.string.ha_url_label, R.string.ha_url_help, urlInput, stacked = true),
        ui.labelled(R.string.ha_token_label, R.string.ha_token_help, tokenInput, stacked = true),
        tokenText,
        ui.row(
            Button(activity).apply {
                setText(R.string.ha_token_save)
                setOnClickListener { saveToken() }
            },
            removeTokenButton,
        ),
        ui.labelled(R.string.ha_service_label, R.string.ha_service_help, serviceInput, stacked = true),
        ui.labelled(R.string.ha_target_label, R.string.ha_target_help, targetInput, stacked = true),
        Button(activity).apply {
            setText(R.string.ha_save)
            setOnClickListener { save() }
        },
        saveText,
        ui.withHelp(checkButton, R.string.ha_check, R.string.ha_check_help),
        checkText,
    )

    init {
        val saved = HomeAssistantPrefs.load(activity)
        urlInput.setText(saved.serverUrl)
        serviceInput.setText(saved.service)
        targetInput.setText(saved.target)
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (switchQuiet) return@setOnCheckedChangeListener
            val stored = HomeAssistantPrefs.update(activity) { it.copy(enabled = checked && it.configured) }
            SessionController.note("home assistant button ${if (stored.enabled) "on" else "off"}")
            render()
        }
        render()
    }

    /** The switch, the token line and Check from what is saved; the fields keep what is typed. */
    fun render() {
        val saved = HomeAssistantPrefs.load(activity)
        if (enabledSwitch.isChecked != saved.enabled) {
            switchQuiet = true
            enabledSwitch.isChecked = saved.enabled
            switchQuiet = false
        }
        enabledSwitch.isEnabled = saved.configured || saved.enabled
        tokenText.setText(
            when {
                tokenInvalid -> R.string.ha_token_invalid
                saved.token.isEmpty() -> R.string.ha_token_none
                else -> R.string.ha_token_saved
            },
        )
        removeTokenButton.isEnabled = saved.token.isNotEmpty()
        checkButton.isEnabled = saved.configured && !checking
    }

    /** Saves the address, the service and the target together, or says which is wrong and saves none. */
    private fun save() {
        val url = HomeAssistantSettings.cleanUrl(urlInput.text.toString())
        val service = HomeAssistantSettings.cleanService(serviceInput.text.toString())
        val target = HomeAssistantSettings.cleanTarget(targetInput.text.toString())
        when {
            url == null -> saveText.setText(R.string.ha_url_invalid)
            service == null -> saveText.setText(R.string.ha_service_invalid)
            target == null -> saveText.setText(R.string.ha_target_invalid)
        }
        if (url == null || service == null || target == null) return
        // A token typed but not yet saved goes in with the rest, so one Save is enough.
        val typedToken = tokenInput.text.toString()
        val token = if (typedToken.isBlank()) null else HomeAssistantSettings.cleanToken(typedToken)
        tokenInvalid = typedToken.isNotBlank() && token == null
        val stored = HomeAssistantPrefs.update(activity) { s ->
            s.copy(serverUrl = url, service = service, target = target, token = token ?: s.token)
        }
        if (token != null) tokenInput.text.clear()
        urlInput.setText(stored.serverUrl)
        serviceInput.setText(stored.service)
        targetInput.setText(stored.target)
        saveText.setText(R.string.ha_saved)
        checkText.text = null
        SessionController.note("home assistant settings saved${if (token != null) ", token saved" else ""}")
        render()
    }

    private fun saveToken() {
        val token = HomeAssistantSettings.cleanToken(tokenInput.text.toString())
        tokenInvalid = token == null
        if (token != null) {
            HomeAssistantPrefs.update(activity) { it.copy(token = token) }
            SessionController.note("home assistant token saved")
            tokenInput.text.clear()
            checkText.text = null
        }
        render()
    }

    /** Removes the saved token, which also turns the button off. */
    private fun removeToken() {
        tokenInvalid = false
        HomeAssistantPrefs.update(activity) { it.copy(token = "", enabled = false) }
        SessionController.note("home assistant token removed; button off")
        tokenInput.text.clear()
        checkText.text = null
        render()
    }

    /** Check, off the main thread. */
    private fun check() {
        checking = true
        checkText.setText(R.string.ha_checking)
        render()
        val client = HomeAssistantPrefs.client(activity)
        Thread({
            val message = try {
                activity.getString(R.string.ha_check_result, client.check().message)
            } catch (e: RuntimeException) {
                activity.getString(R.string.ha_check_crashed, e.javaClass.simpleName)
            }
            activity.runOnUiThread {
                checking = false
                if (activity.isDestroyed) return@runOnUiThread
                checkText.text = message
                render()
            }
        }, "home-assistant-check").start()
    }

    private fun input(hint: Int, type: Int): EditText = EditText(activity).apply {
        setHint(hint)
        inputType = type
        maxLines = 1
    }
}
