package com.jw6ventures.headlink

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

/**
 * The live suggestions under the connection screen's destination box (P11.4):
 * up to eight rows, a label and a detail line each, refreshed [PhoneSearch.DEBOUNCE_MS]
 * after the last keystroke. A tap hands the place to [onPick]. What is typed and shown
 * stays in these views: nothing is noted or reported but counts ([PhoneSearch.report]).
 */
class SearchSuggestions(
    private val activity: Activity,
    private val ui: PhoneViews,
    private val input: EditText,
    private val near: () -> Pair<Double, Double>?,
    private val onPick: (Suggestion) -> Unit,
) {
    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    init {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                PhoneSearch.typed(activity, s?.toString().orEmpty(), near, ::show)
            }
        })
    }

    /** Hides the rows and stops any search; the box keeps its text. */
    fun hide() {
        PhoneSearch.cancel()
        show(emptyList())
    }

    private fun show(suggestions: List<Suggestion>) {
        if (activity.isDestroyed) return
        view.removeAllViews()
        if (suggestions.isEmpty()) {
            // Text worth searching found nothing, with no maps to search: say where they come from.
            val text = input.text.toString().trim()
            if (text.length >= PhoneSearch.MIN_CHARS && PhoneSearch.looksLikeSearch(text) && !ObfLibrary.hasSources()) {
                view.addView(ui.caption().apply { setText(R.string.search_no_maps) })
                view.visibility = View.VISIBLE
            } else {
                view.visibility = View.GONE
            }
            return
        }
        suggestions.forEach { view.addView(row(it)) }
        view.visibility = View.VISIBLE
    }

    private fun row(suggestion: Suggestion): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = ui.dp(48)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
        val attributes = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        background = attributes.getDrawable(0)
        attributes.recycle()
        isClickable = true
        setOnClickListener {
            PhoneSearch.picked()
            hide()
            onPick(suggestion)
        }
        addView(ui.primary(TextView(activity)).apply {
            text = suggestion.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        if (suggestion.detail.isNotEmpty()) addView(ui.caption().apply { text = suggestion.detail })
    }
}

/**
 * Settings → OsmAnd maps (P11.1, docs/navigation.md, "OsmAnd maps: search and places"): the folder, its status,
 * where OsmAnd's own setting is, and the marker and search-ahead choices ([PoiSettings]).
 * Distances are stored in miles and shown in the dashboard's speed unit.
 */
class OsmAndMapsCard(
    private val activity: Activity,
    private val ui: PhoneViews,
    chooseFolder: () -> Unit,
) {
    private val statusText = ui.body(selectable = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val markers = ChoiceRow(listOf(5, 10, 15, 25, 40, 60, 100))
    private val ahead = ChoiceRow(listOf(10, 25, 50, 75, 100, 150))
    private val corridor = ChoiceRow(listOf(1, 2, 3, 5, 10, 15, 20))
    private val candidates = ChoiceRow((1..10).toList())
    private var unit = SpeedUnit.MPH

    val view: LinearLayout = ui.card(
        ui.title(R.string.osmand_maps_title),
        ui.caption().apply { setText(R.string.osmand_maps_caption) },
        statusText,
        ui.withHelp(
            Button(activity).apply {
                setText(R.string.osmand_maps_choose)
                setOnClickListener { chooseFolder() }
            },
            R.string.osmand_maps_choose, R.string.osmand_maps_choose_help,
        ),
        ui.caption().apply { setText(R.string.osmand_maps_where) },
        ui.labelled(R.string.poi_markers_label, R.string.poi_markers_help, markers.spinner),
        ui.labelled(R.string.poi_ahead_label, R.string.poi_ahead_help, ahead.spinner),
        ui.labelled(R.string.poi_corridor_label, R.string.poi_corridor_help, corridor.spinner),
        ui.labelled(R.string.poi_candidates_label, R.string.poi_candidates_help, candidates.spinner),
    )

    init {
        markers.onPick = { PoiSettings.setMarkersPerKind(activity, it) }
        ahead.onPick = { PoiSettings.setAheadMiles(activity, it) }
        corridor.onPick = { PoiSettings.setCorridorMiles(activity, it / 10.0) }
        candidates.onPick = { PoiSettings.setAheadCandidates(activity, it) }
    }

    /** The saved choices and the folder's status, re-read on every render. */
    fun render(speedUnit: SpeedUnit) {
        unit = speedUnit
        val saved = PoiSettings.load(activity)
        markers.show(saved.markersPerKind) { "$it" }
        ahead.show(saved.aheadMiles) { miles(it.toDouble()) }
        corridor.show(saved.corridorTenths) { miles(it / 10.0) }
        candidates.show(saved.aheadCandidates) { "$it" }
        statusText.text = ObfLibrary.status(activity)
    }

    private fun miles(value: Double): String = when (unit) {
        SpeedUnit.MPH -> if (value == 1.0) "1 mile" else "${trim(value)} miles"
        SpeedUnit.KMH -> "${trim(value * KM_PER_MILE)} km"
    }

    private fun trim(value: Double): String =
        if (value >= 10) String.format(Locale.ROOT, "%.0f", value)
        else String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")

    /**
     * A spinner over fixed [choices]; a saved value not among them (set by the lab, or a
     * default changed later) is shown as an extra choice rather than lost.
     */
    private inner class ChoiceRow(private val choices: List<Int>) {
        val spinner = Spinner(activity)
        var onPick: (Int) -> Unit = {}
        private var values: List<Int> = emptyList()
        private var labels: List<String> = emptyList()
        /** The value last put on screen; a selection callback reporting it is not a pick. */
        private var shown: Int? = null

        init {
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val value = values.getOrNull(position) ?: return
                    if (value == shown) return
                    shown = value
                    onPick(value)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        fun show(current: Int, label: (Int) -> String) {
            val nextValues = (choices + current).distinct().sorted()
            val nextLabels = nextValues.map(label)
            shown = current
            if (nextValues != values || nextLabels != labels) {
                values = nextValues
                labels = nextLabels
                spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
            }
            val position = values.indexOf(current)
            if (spinner.selectedItemPosition != position) spinner.setSelection(position, false)
        }
    }

    private companion object {
        const val KM_PER_MILE = 1.609344
    }
}
