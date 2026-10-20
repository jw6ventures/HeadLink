package com.jw6ventures.headlink

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import btools.router.RoutingEngine
import org.oscim.android.MapView
import org.oscim.core.BoundingBox
import org.oscim.core.GeoPoint
import org.oscim.layers.Layer
import org.oscim.layers.PathLayer
import org.oscim.layers.tile.buildings.BuildingLayer
import org.oscim.layers.tile.vector.labeling.LabelLayer
import org.oscim.theme.StreamRenderTheme
import org.oscim.theme.ThemeLoader
import org.oscim.theme.internal.VtmThemes
import org.oscim.tiling.TileSource
import org.oscim.tiling.source.mapfile.MapFileTileSource
import org.oscim.tiling.source.mapfile.MultiMapFileTileSource
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The route preview: the route on show drawn whole on a map the driver can pan and zoom,
 * with up to [RoutePreview.ALTERNATIVES] other routes to the same place to choose from.
 *
 * **Its own process** (`android:process=":preview"`). VTM keeps its settings in statics —
 * the display density it scales every line and label by, the tile size, the thread it
 * treats as its own, and the GL state it caches — and the head unit's map ([MapEngine])
 * sets all of them for a 240 dpi panel drawn on the scene thread. A phone map in the same
 * process would re-set them under a projection that is running, so this one runs apart.
 * The two share nothing but files: [ConnectionActivity] writes a [PreviewRequest], and a
 * choice comes back as a file named by the activity result, which [Routing.adopt] applies
 * only if the route it was chosen against is still the one on show.
 *
 * The alternatives are computed here, on a thread of this process, so a re-route in the
 * main process never waits behind them. They cost more than the route did: BRouter's
 * second route searches twice and its third three times.
 */
class PreviewActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "preview-route").apply { isDaemon = true } }

    @Volatile
    private var running: RoutingEngine? = null

    @Volatile
    private var closed = false

    private lateinit var request: PreviewRequest
    private lateinit var mapView: MapView
    private lateinit var statusText: TextView
    private lateinit var optionsList: LinearLayout
    private lateinit var useButton: Button
    private var unit = SpeedUnit.MPH
    private var labelLayer: Layer? = null

    /** Shown only when no map is installed: the lines are then on a bare ground. */
    private lateinit var mapNoteText: TextView

    /** The route on show first, then each alternative in the order found, with its number. */
    private val options = ArrayList<Pair<Int, Route>>()
    private val routeLayers = ArrayList<Layer>()
    private var selected = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = try {
            RoutePreview.readRequest(RoutePreview.requestFile(filesDir))
        } catch (e: IOException) {
            Toast.makeText(this, getString(R.string.preview_unreadable, e.message ?: e.toString()), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        unit = AppSettings.load(this).speedUnit
        options += 0 to request.route
        setContentView(layout())
        startMap()
        drawRoutes()
        renderOptions()
        // Fitted once the view has its size: VTM fits a box to the viewport it has.
        mapView.post { showWholeRoute(animated = false) }
        findAlternatives()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun layout(): View {
        mapView = MapView(this)
        // MapView's constructor installs VTM's AndroidAssets, which cannot see the shaders
        // this app ships as classpath resources (see MapAssets); put ours back before the
        // GL surface is created and the shaders are read.
        MapAssets.install()
        val fit = Button(this).apply {
            setText(R.string.preview_fit)
            setOnClickListener { showWholeRoute(animated = true) }
        }
        val zoomIn = Button(this).apply {
            text = "+"
            contentDescription = getString(R.string.preview_zoom_in)
            setOnClickListener { zoom(2.0) }
        }
        val zoomOut = Button(this).apply {
            text = "−"
            contentDescription = getString(R.string.preview_zoom_out)
            setOnClickListener { zoom(0.5) }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(zoomIn, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(zoomOut, LinearLayout.LayoutParams(dp(56), dp(56)))
        }
        val mapFrame = FrameLayout(this).apply {
            addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                controls,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP)
                    .apply { setMargins(dp(8), dp(8), dp(8), dp(8)) },
            )
            addView(
                fit,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.TOP)
                    .apply { setMargins(dp(8), dp(8), dp(8), dp(8)) },
            )
        }
        statusText = TextView(this).apply {
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        optionsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mapNoteText = TextView(this).apply {
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            setText(R.string.preview_no_map)
            visibility = View.GONE
        }
        val close = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.preview_close)
            setOnClickListener { finish() }
        }
        useButton = Button(this).apply { setOnClickListener { use() } }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(close)
            addView(useButton)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(ScrollView(this@PreviewActivity).apply { addView(optionsList) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(mapNoteText)
            addView(statusText)
            addView(buttons)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(mapFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f))
            addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f))
        }
    }

    /** The installed maps under the app's own theme, day or night as the phone is. */
    private fun startMap() {
        val map = mapView.map()
        val source = tileSource()
        if (source == null) {
            mapNoteText.visibility = View.VISIBLE
            return
        }
        val base = map.setBaseMap(source)
        map.layers().add(BuildingLayer(map, base))
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val asset = if (night) THEME_NIGHT_ASSET else THEME_DAY_ASSET
        val theme = try {
            MapAssets.openFileAsStream(asset)?.let { ThemeLoader.load(StreamRenderTheme("", it)) }
        } catch (e: RuntimeException) {
            null
        } ?: ThemeLoader.load(VtmThemes.DEFAULT)
        map.setTheme(theme)
        val labels = LabelLayer(map, base)
        map.layers().add(labels)
        labelLayer = labels
    }

    private fun tileSource(): TileSource? {
        val opened = MapData.installed(this).mapNotNull { file ->
            val source = MapFileTileSource()
            if (source.setMapFile(file.absolutePath) && source.open().isSuccess) source else null
        }
        if (opened.size <= 1) return opened.firstOrNull()
        return MultiMapFileTileSource().apply {
            setDeduplicate(true)
            opened.forEach { add(it) }
        }
    }

    /**
     * Every option as a line: the others grey and under, the chosen one in the route's
     * own cyan on a dark casing and on top. Below the labels, so street names stay legible.
     */
    private fun drawRoutes() {
        val map = mapView.map()
        val layers = map.layers()
        routeLayers.forEach { layers.remove(it) }
        routeLayers.clear()
        val width = dp(5).toFloat()
        val order = options.indices.filter { it != selected } + selected
        for (index in order) {
            val points = options[index].second.let { route -> List(route.points) { GeoPoint(route.latitudes[it], route.longitudes[it]) } }
            val lines = if (index == selected) {
                listOf(PathLayer(map, CASING_COLOR, width + dp(3)), PathLayer(map, MapEngine.ROUTE_COLOR, width))
            } else {
                listOf(PathLayer(map, OTHER_CASING_COLOR, width + dp(2)), PathLayer(map, OTHER_COLOR, width - dp(1)))
            }
            lines.forEach { it.setPoints(points) }
            routeLayers += lines
        }
        val at = labelLayer?.let { layers.indexOf(it) }?.takeIf { it >= 0 } ?: layers.size
        layers.addAll(at, routeLayers)
        map.updateMap(true)
    }

    private fun showWholeRoute(animated: Boolean) {
        val (south, west, north, east) = RoutePreview.bounds(options.map { it.second }).toList()
        // A margin of a tenth each way, so the ends of the line are not under the buttons.
        val padLat = maxOf((north - south) * 0.1, 0.002)
        val padLon = maxOf((east - west) * 0.1, 0.002)
        val box = BoundingBox(south - padLat, west - padLon, north + padLat, east + padLon)
        val map = mapView.map()
        if (animated) {
            map.animator().animateTo(FIT_MS, box)
        } else {
            val position = map.getMapPosition()
            position.setByBoundingBox(box, map.getWidth(), map.getHeight())
            map.setMapPosition(position)
        }
    }

    private fun zoom(factor: Double) {
        val map = mapView.map()
        map.animator().animateZoom(ZOOM_MS, factor, 0f, 0f)
    }

    private fun renderOptions() {
        optionsList.removeAllViews()
        val fastest = options.minOf { it.second.seconds }
        options.forEachIndexed { index, (alternative, route) ->
            val row = TextView(this).apply {
                val title = if (alternative == 0) getString(R.string.preview_current) else getString(R.string.preview_alternative, alternative)
                val slower = route.seconds - fastest
                val parts = mutableListOf(duration(route.seconds))
                if (slower >= 60) parts += getString(R.string.preview_slower, duration(slower))
                parts += distance(route.distanceM)
                // The current route by its own main road; each alternative by the main road
                // the options above it do not use, which is where it differs.
                RoutePreview.via(route, options.take(index).map { it.second })?.let { parts += getString(R.string.preview_via, it) }
                text = getString(
                    if (index == selected) R.string.preview_option_selected else R.string.preview_option,
                    title,
                    parts.joinToString(getString(R.string.preview_separator)),
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minHeight = dp(56)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                isSelected = index == selected
                if (index == selected) setBackgroundResource(R.drawable.card_background)
                setOnClickListener {
                    if (selected != index) {
                        selected = index
                        drawRoutes()
                        renderOptions()
                    }
                }
            }
            optionsList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        useButton.setText(if (selected == 0) R.string.preview_keep else R.string.preview_use)
    }

    private fun duration(seconds: Int): String {
        val minutes = (seconds + 30) / 60
        return if (minutes < 60) getString(R.string.preview_minutes, minutes) else getString(R.string.preview_hours, minutes / 60, minutes % 60)
    }

    private fun distance(metres: Int): String =
        if (unit == SpeedUnit.MPH) {
            String.format(Locale.ROOT, "%.1f mi", metres / METRES_PER_MILE)
        } else {
            String.format(Locale.ROOT, "%.1f km", metres / 1000.0)
        }

    /**
     * Looks for alternatives with [AlternativeFinder], showing each as it comes. They are
     * numbered in the order found, and each row says how much longer it is.
     */
    private fun findAlternatives() {
        if (RoutingData.installed(RoutingData.segments(RoutingData.root(filesDir))).isEmpty()) return
        statusText.setText(R.string.preview_finding)
        val app = applicationContext
        worker.execute {
            val root = RoutingData.root(app.filesDir)
            val router = try {
                Router(RoutingData.segments(root), RoutingData.installProfile(RoutingData.profiles(root)))
            } catch (e: IOException) {
                main.post { if (!closed) statusText.text = getString(R.string.preview_failed, e.message ?: e.toString()) }
                return@execute
            }
            val finder = AlternativeFinder(router, request, started = { running = it }, cancelled = { closed })
            val failure = try {
                finder.find { found ->
                    val named = try {
                        StreetNames.name(found, MapData.installed(app))
                    } catch (e: RuntimeException) {
                        found
                    }
                    main.post {
                        if (closed || RoutePreview.duplicate(named, options.map { it.second })) return@post
                        options += options.size to named
                        drawRoutes()
                        renderOptions()
                    }
                }
            } catch (e: RuntimeException) {
                e.toString()
            } finally {
                running = null
            }
            if (closed) return@execute
            main.post {
                if (closed) return@post
                statusText.text = when {
                    options.size > 1 -> resources.getQuantityString(R.plurals.preview_found, options.size - 1, options.size - 1)
                    failure != null -> getString(R.string.preview_failed, failure)
                    else -> getString(R.string.preview_none)
                }
            }
        }
    }

    /** Hands the chosen route back to [ConnectionActivity], or nothing if it is the one on show. */
    private fun use() {
        if (selected == 0) {
            finish()
            return
        }
        try {
            RoutePreview.writeChoice(RoutePreview.choiceFile(filesDir), request.token, options[selected].second)
        } catch (e: IOException) {
            Toast.makeText(this, getString(R.string.preview_failed, e.message ?: e.toString()), Toast.LENGTH_LONG).show()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TOKEN, request.token))
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
    }

    override fun onPause() {
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        closed = true
        running?.terminate()
        worker.shutdownNow()
        if (::mapView.isInitialized) {
            mapView.onDestroy()
        }
        super.onDestroy()
    }

    companion object {
        /** The end of this activity's process name; [SourceApplication] skips its setup there. */
        const val PROCESS_SUFFIX = ":preview"

        /** The [PreviewRequest.token] a choice was made against. */
        const val EXTRA_TOKEN = "preview_token"

        private const val THEME_NIGHT_ASSET = "oaa/dashboard-dark.xml"
        private const val THEME_DAY_ASSET = "oaa/dashboard-light.xml"

        /** The head unit's day casing under the chosen route, so it has an edge on white roads. */
        private const val CASING_COLOR = 0xFF0B4F6C.toInt()

        /** The routes not chosen: a mid grey on a darker edge, legible on either theme. */
        private const val OTHER_COLOR = 0xFF9AA5B1.toInt()
        private const val OTHER_CASING_COLOR = 0xFF4A5560.toInt()

        private const val FIT_MS = 500L
        private const val ZOOM_MS = 250L
        private const val METRES_PER_MILE = 1609.344
    }
}
