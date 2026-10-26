package com.jw6ventures.headlink

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The phone screen's building blocks (P7.7, plan §6): cards, short labels with an ⓘ
 * that opens the long explanation, section titles and the tab bar. Platform views only —
 * spec §2.2 keeps AndroidX at zero — styled from the app theme and `res/values*`
 * colours, so the light and dark screens come from the same code.
 *
 * Nothing here holds session state. [ConnectionActivity] owns every control and what it
 * does; this only decides how they sit on the screen.
 */
class PhoneViews(private val activity: Activity) {
    private val density = activity.resources.displayMetrics.density

    fun dp(value: Int): Int = (value * density).toInt()

    /**
     * The theme's primary text colour. A platform TextView built in code starts in the
     * theme's secondary grey, which is right for captions and too faint for anything a
     * driver reads first: the status, labels, place names.
     */
    fun primary(view: TextView): TextView = view.apply {
        val attributes = activity.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        attributes.getColorStateList(0)?.let { setTextColor(it) }
        attributes.recycle()
    }

    /** A section's heading inside a page. */
    fun title(text: Int): TextView = primary(TextView(activity)).apply {
        setText(text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(12), 0, dp(4))
    }

    /** Ordinary text, selectable where it is something an operator may want to copy. */
    fun body(selectable: Boolean = false, monospace: Boolean = false): TextView = primary(TextView(activity)).apply {
        setTextIsSelectable(selectable)
        if (monospace) {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        setPadding(0, dp(4), 0, dp(4))
    }

    /** Secondary text under a control: smaller and in the theme's secondary colour. */
    fun caption(): TextView = TextView(activity).apply {
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
        setPadding(0, dp(2), 0, dp(6))
    }

    /** A rounded panel grouping one section's controls. */
    fun card(vararg views: View): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_background)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(6), 0, dp(6)) }
        views.forEach { addView(it) }
    }

    /**
     * The ⓘ that opens [help] in a dialog titled [label] (plan §6.3): the careful
     * explanations every control used to carry inline are kept word for word, one tap
     * away instead of in the way. A 48 dp target, as Android's accessibility guidance asks.
     */
    fun helpButton(label: Int, help: CharSequence): TextView = TextView(activity).apply {
        setText(R.string.help_icon)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        minWidth = dp(48)
        minHeight = dp(48)
        contentDescription = activity.getString(R.string.help_about, activity.getString(label))
        setOnClickListener { showHelp(label, help) }
    }

    fun showHelp(label: Int, help: CharSequence) {
        val text = TextView(activity).apply {
            setTextIsSelectable(true)
            setText(help)
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        AlertDialog.Builder(activity)
            .setTitle(label)
            .setView(ScrollView(activity).apply { addView(text) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * The ⓘ slot at the end of every row: the ⓘ itself, or a blank of the same 48 dp
     * when the row has no help, so the value column lines up down a whole card.
     */
    private fun helpSlot(label: Int, help: Int?): View =
        if (help != null) helpButton(label, activity.getText(help)) else View(activity).apply { minimumWidth = dp(48) }

    /** A control that carries its own short label (a switch or checkbox), then its ⓘ slot. */
    fun withHelp(control: View, label: Int, help: Int?): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(control, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(helpSlot(label, help), LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * A short [label] and its ⓘ, and [control] (a spinner, usually). Returned whole so a
     * caller can hide the row with the control.
     *
     * Inline by default, the spinner taking a little more than half the row: a spinner
     * sized to its own longest choice squeezed its label to a few letters, and an even
     * split cut "automatic" short. Every row reserves the ⓘ slot, help or not, so the
     * label and value columns are the same widths on every row of a card. [stacked] puts the spinner on a line of its own under
     * the label, for Diagnostics, whose choices run to `legacy-1.5-any-minor`.
     */
    fun labelled(label: Int, help: Int?, control: View, stacked: Boolean = false): LinearLayout {
        val text = primary(TextView(activity)).apply {
            setText(label)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!stacked) addView(control, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            addView(helpSlot(label, help), LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (!stacked) return line
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(line)
            addView(control, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Views side by side, equal widths. */
    fun row(vararg views: View): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    /**
     * The one large button on Home: Connect, Stop or the status's own action. A flat tint
     * would replace the platform's disabled look, so disabled gets its own colours: the
     * primary text colour faint on a faint fill, as Material's disabled buttons are.
     */
    fun primaryButton(action: () -> Unit): Button = Button(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        minHeight = dp(64)
        val attributes = activity.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val onSurface = attributes.getColorStateList(0)?.defaultColor ?: Color.BLACK
        attributes.recycle()
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        backgroundTintList = ColorStateList(
            arrayOf(disabled, intArrayOf()),
            intArrayOf(withAlpha(onSurface, 0x1F), activity.getColor(R.color.accent)),
        )
        setTextColor(
            ColorStateList(
                arrayOf(disabled, intArrayOf()),
                intArrayOf(withAlpha(onSurface, 0x61), activity.getColor(R.color.on_accent)),
            ),
        )
        setOnClickListener { action() }
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /**
     * The tab bar along the bottom, where a thumb reaches it: equal-width labels, the
     * selected one bold and in the accent colour. [onSelect] receives the tab's index.
     */
    fun tabBar(labels: List<Int>, onSelect: (Int) -> Unit): Pair<LinearLayout, List<TextView>> {
        val tabs = labels.mapIndexed { index, label ->
            TextView(activity).apply {
                setText(label)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                // Five labels across a 360 dp phone: "Diagnostics" fits at 12 sp, not 13.
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                minHeight = dp(56)
                setPadding(dp(2), 0, dp(2), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { onSelect(index) }
                // Read by TalkBack as a tab, "selected" from isSelected, and its place in the bar.
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = TAB_CLASS
                        info.collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo.Builder()
                            .setRowIndex(0).setRowSpan(1).setColumnIndex(index).setColumnSpan(1)
                            .setSelected(host.isSelected)
                            .build()
                    }
                }
            }
        }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.color.tab_bar)
            tabs.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = TAB_BAR_CLASS
                    val shown = tabs.count { it.visibility == View.VISIBLE }
                    info.collectionInfo = AccessibilityNodeInfo.CollectionInfo(1, shown, false)
                }
            }
        }
        return bar to tabs
    }

    /** Marks [selected] in the bar [tabs]; hidden tabs keep their slot free. */
    fun select(tabs: List<TextView>, selected: Int) {
        tabs.forEachIndexed { index, tab ->
            val on = index == selected
            tab.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tab.setTextColor(activity.getColor(if (on) R.color.accent else R.color.tab_text))
            tab.isSelected = on
        }
    }

    private companion object {
        /** The class names TalkBack reads as a tab and a tab bar; these views only stand in for them. */
        const val TAB_CLASS = "android.app.ActionBar\$Tab"
        const val TAB_BAR_CLASS = "android.widget.TabWidget"
    }
}
