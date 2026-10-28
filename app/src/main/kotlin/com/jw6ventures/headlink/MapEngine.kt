package com.jw6ventures.headlink

import android.opengl.GLES20
import org.oscim.android.canvas.AndroidGraphics
import org.oscim.android.gl.AndroidGL
import org.oscim.backend.CanvasAdapter
import org.oscim.backend.DateTime
import org.oscim.backend.DateTimeAdapter
import org.oscim.backend.GLAdapter
import org.oscim.backend.canvas.Paint.Cap
import org.oscim.core.GeoPoint
import org.oscim.core.MapPosition
import org.oscim.core.Tile
import org.oscim.layers.PathLayer
import org.oscim.layers.marker.ItemizedLayer
import org.oscim.layers.marker.MarkerInterface
import org.oscim.layers.marker.MarkerItem
import org.oscim.layers.marker.MarkerSymbol
import org.oscim.layers.tile.MapTile
import org.oscim.layers.tile.TileSet
import org.oscim.layers.tile.buildings.BuildingLayer
import org.oscim.layers.tile.vector.VectorTileLayer
import org.oscim.layers.tile.vector.labeling.LabelLayer
import org.oscim.map.Map
import org.oscim.renderer.GLState
import org.oscim.renderer.MapRenderer
import org.oscim.theme.IRenderTheme
import org.oscim.theme.StreamRenderTheme
import org.oscim.theme.ThemeLoader
import org.oscim.theme.internal.VtmThemes
import org.oscim.theme.styles.LineStyle
import org.oscim.tiling.TileSource
import org.oscim.tiling.source.mapfile.MapFileTileSource
import org.oscim.tiling.source.mapfile.MultiMapFileTileSource
import org.oscim.utils.ThreadUtils
import java.io.File

/**
 * Draws an owned vector map into an off-screen texture with VTM (P5.5), for the
 * dashboard's map pane to composite.
 *
 * **This is original glue, not a fork.** `org.oscim.renderer.MapRenderer` has no Android
 * imports and reaches the GPU only through [GLAdapter], so the whole integration is:
 * make our EGL context current, install the backends, drive [MapRenderer]'s three hooks
 * by hand, and subclass [Map] so its redraw requests land on our render loop instead of
 * a `GLSurfaceView`'s. No VTM source is changed.
 *
 * Everything here runs on the scene thread that called [start], which becomes VTM's
 * "main thread" — [ThreadUtils.init] simply records whichever thread calls it, and
 * [Map.prepareFrame] asserts against that. Our loop pulls frames at a fixed rate rather
 * than being pushed by `requestRender`, so [PaneMap] only records that a redraw is due.
 *
 * The map renders into its own framebuffer rather than the window surface for three
 * reasons: [MapRenderer] issues an unscoped `glClear`, which on the window surface would
 * wipe the dashboard chrome; it only rasterises the pane's pixels instead of the whole
 * 1920x1080 frame; and a stationary vehicle can re-composite the last texture without
 * redrawing the map at all.
 */
class MapEngine(width: Int, height: Int) {

    /** The pane's size in pixels; changed only by [resize]. */
    private var width = width
    private var height = height

    /** Times [resize] rebuilt the framebuffer for a new pane size. */
    var resizes = 0L
        private set

    /** Set once the map has a tile source with a readable file behind it. */
    var mapName: String = "-"
        private set

    /** First failure, for the session report; the dashboard degrades rather than dies. */
    var failure: String? = null
        private set

    private var map: PaneMap? = null
    private var routeLayer: PathLayer? = null

    /**
     * A dark edge under the route line, shown with the day theme only.
     *
     * The route's cyan was chosen against the night ground, where it is the brightest
     * thing on the map. On the day theme's white carriageways and light grey ground it
     * is about 2:1 against both, which is visible when looked for and easy to lose at a
     * glance. A casing restores the edge without changing the colour itself, which
     * tools/route-line-pixels.py and the P6.4 lab check count by value. At night the
     * line needs no help, so the night picture stays exactly what the P6 evidence saw.
     */
    private var routeCasing: PathLayer? = null

    /**
     * The live alternatives' lines ([LiveAlternatives]), grey on a darker edge, two layers
     * each, under the route's casing so the route stays on top where they share roads.
     */
    private val alternativeLayers = ArrayList<PathLayer>()

    /** The route on the map now, for [TrafficLines]'s check that traffic belongs to it. */
    private var drawnRoute: Route? = null

    /**
     * The traffic pieces over the route ahead (P9.3): an edge layer and a line layer each,
     * edges all below lines, just above the route line and below the labels. Made as they
     * are first needed and kept: a piece that goes has its layers emptied and disabled,
     * which the renderer skips, so the layer list changes only when it grows, at most to
     * [TrafficLines.MAX_PIECES] pairs.
     */
    private val trafficEdges = ArrayList<PathLayer>()
    private val trafficLines = ArrayList<PathLayer>()
    private val traffic = TrafficLines()

    /** The offer's proposal (P9.6), in the alternatives' style: edge and line, or null. */
    private var offerEdge: PathLayer? = null
    private var offerLine: PathLayer? = null
    private var offerShown = false
    private var offerChanges = 0L

    /**
     * P11.5: the Gas, Food and Shop markers (docs/navigation.md), one VTM [ItemizedLayer]
     * just under the labels, so above the route, its traffic and the buildings, and street
     * names still read over a marker. Made on first use; its symbols per kind and size.
     */
    private var poiLayer: ItemizedLayer? = null
    private val poiSymbols = HashMap<String, MarkerSymbol>()
    private var poiShown: List<ObfPoi> = emptyList()
    var poiMarkers = 0
        private set
    private var poiChanges = 0L

    /** Alternatives drawn now, and the most at once this session; counts, never the geometry. */
    var alternativesShown = 0
        private set
    var alternativesShownMax = 0
        private set

    /** The base layer, whose tile manager says when a theme switch has finished loading. */
    private var baseLayer: VectorTileLayer? = null

    /** Retries tiles VTM read badly; see [MapReadGuard]. Null with no map data. */
    private var readGuard: MapReadGuard? = null

    /** Keeps a theme switch's loader pause from closing map files; see [MapLoaderInterrupts]. */
    private var loaderInterrupts: MapLoaderInterrupts? = null

    /** Points in the route line on show, 0 for none. A count, never the geometry. */
    var routePoints = 0
        private set

    /** Times a route line was put on the map or taken off it. */
    var routeChanges = 0L
        private set
    private var renderer: MapRenderer? = null

    /** The report's name for the theme on show, or empty before one is applied. */
    private var theme = ""

    /**
     * Which theme is applied: [UNTHEMED], [DAY] or [NIGHT]. An Int rather than a
     * `Boolean?`, so the per-frame "nothing changed" test in [setNight] is one integer
     * compare with nothing boxed.
     */
    private var appliedMode = UNTHEMED

    /** Each theme parsed once, on first use, and kept: see [themeFor]. */
    private var dayTheme: LoadedTheme? = null
    private var nightTheme: LoadedTheme? = null
    private var fallbackTheme: LoadedTheme? = null
    private var currentTheme: IRenderTheme? = null

    /** Times the theme changed after the first, i.e. day/night switches. */
    var themeSwitches = 0L
        private set

    /** Longest [setNight] call that switched, ms: VTM pausing its loaders, on this thread. */
    private var switchCallMsMax = 0L

    /** Longest theme parse, ms. Paid once per theme per session, on the first switch to it. */
    private var parseMsMax = 0L

    /**
     * When the current switch began, from [System.nanoTime], or 0 when no switch is
     * waiting for its tiles. See [drawFrame] for why a switch holds the old picture.
     */
    private var holdStartNs = 0L

    /** The visible tiles a waiting switch is watching; locked while held, released after. */
    private val holdTiles = TileSet()

    /** Frames that kept the previous map picture while a switch loaded. */
    var held = 0L
        private set
    private var holdMsLast = -1L
    private var holdMsMax = 0L

    /** Switches that gave up waiting at [SWITCH_HOLD_MAX_MS] and drew what had loaded. */
    private var holdTimeouts = 0L

    /** Times a waiting switch asked VTM again for tiles a failed read had dropped. */
    private var holdRequeues = 0L
    private var lastRequeueNs = 0L

    /** Rasterisations the frame that ends a hold needs; see [drawFrame]. */
    private var settlePasses = 1

    /**
     * The ground colour, ARGB: the applied theme's `map-background`, or the no-data grey.
     *
     * Set as the GL clear colour by [drawFrame] itself, every frame, because VTM's own
     * setting of it does not survive sharing the context. `MapRenderer` hands its clear
     * colour to `GLState.setClearColor`, which skips the GL call when its cache says the
     * colour is already set, and the dashboard's compositor clears the window to black
     * with a raw `glClearColor` between map frames. So from the second frame on, VTM's
     * `glClear` painted the ground black whatever the theme said. At night that was
     * nearly invisible (the dark theme's ground is #151a2b); the first day-theme lab frame
     * showed white roads on black, and every frame before P7.6 has had a black ground.
     */
    private var background = NO_DATA_BACKGROUND

    /**
     * Whether a tile source was installed, so the map has vector layers to theme.
     *
     * VTM's `Map.setTheme` throws `IllegalStateException("No vector layers set")` on a map
     * with no base layer, and [setNight] is called once per frame from the render thread —
     * so on a dashboard with no map data the first frame killed the process (P6.2 lab run,
     * 21 September 2026). [start] already knew not to theme an empty map; nothing else
     * did, because [theme] stays empty forever in that case and is not the same question.
     * Now that [setNight] re-themes on every change of the head unit's night flag rather
     * than once, this guard is what every one of those calls passes through first.
     */
    private var layered = false

    private var framebuffer = 0
    private var colorTexture = 0
    private var depthStencil = 0

    /** Frames the map actually rasterised, against those that reused the texture. */
    var drawn = 0L
        private set
    var reused = 0L
        private set

    /**
     * How many times the camera was moved to somewhere it had not been.
     *
     * Rasterisations alone cannot say whether the map followed anything: tiles arriving
     * on VTM's loader threads raise [drawn] on their own, and a map that never moved
     * again would keep drawing while they landed. This counts applied *changes* of
     * position, which is the thing a moving vehicle is supposed to produce and a
     * stationary one is supposed not to.
     */
    var moved = 0L
        private set

    /** Set by the map when something changed; cleared once that frame is rasterised. */
    private var dirty = true

    /** Latitude, longitude, scale and rotation of the last applied position. */
    private var lastPosition: DoubleArray? = null

    /** The texture the pane samples, or 0 before [start] succeeds. */
    fun texture(): Int = colorTexture

    /**
     * VTM's tile size once [start] has set it, or 0.
     *
     * The pan arithmetic in [MapControl] turns pixels into a distance with it, so it is
     * read back from VTM rather than assumed: it follows [CanvasAdapter.dpi] and is not
     * the 256 a reading of the format would suggest.
     */
    fun tileSize(): Int = if (map == null) 0 else Tile.SIZE

    /**
     * Builds the framebuffer, the map and its layers on the calling thread, which must
     * already have our EGL context current. Returns null on success or the reason.
     */
    fun start(mapFiles: List<File>, night: Boolean): String? {
        if (!MapAssets.shadersPresent()) {
            return fail("VTM shaders are not on the classpath; check the vtm dependency")
        }
        val native = loadNative()
        if (native != null) return fail(native)

        MapAssets.install()
        AndroidGraphics.init()
        DateTimeAdapter.init(DateTime())
        // The head unit's panel, not the phone's screen: the map is rasterised for the
        // projected frame and never appears on the phone, so the phone's density would
        // size every label and icon wrongly.
        //
        // This scales line widths *and* fonts, which makes it the main control over how
        // many street names appear. VTM drops a path label whose text is wider than the
        // way it runs along (LabelPlacement: `ti.width > ti.length * scale`), so an
        // over-large font silently strips the names off every short residential street
        // — which is exactly what an xhdpi-sized 320 did. 240 keeps labels legible at
        // arm's length while leaving most suburban segments long enough to carry one.
        CanvasAdapter.dpi = TARGET_DPI
        Tile.SIZE = Tile.calculateTileSize()

        ThreadUtils.init()
        GLAdapter.init(AndroidGL())

        val fboError = createFramebuffer()
        if (fboError != null) return fail(fboError)

        val paneMap = PaneMap()
        map = paneMap
        val mapRenderer = MapRenderer(paneMap)
        renderer = mapRenderer
        mapRenderer.onSurfaceCreated()
        mapRenderer.onSurfaceChanged(width, height)
        paneMap.viewport().setViewSize(width, height)

        // Absent or unreadable map data is a degraded dashboard, not a failed session:
        // the pane draws its background and the chrome around it still works. The app
        // never fetches map data (APP10), so there is no download to fall back on
        // and the operator has to supply the file — which they can only be told about
        // if this keeps running and reports why.
        val source = openTileSource(mapFiles)?.let(::MapReadGuard)
        readGuard = source
        // Outermost, so it sees the loader thread's interrupt status around the whole
        // query, MapReadGuard's retry judgement included.
        val interrupts = source?.let(::MapLoaderInterrupts)
        loaderInterrupts = interrupts
        val base: VectorTileLayer? = interrupts?.let { paneMap.setBaseMap(it) }
        baseLayer = base
        if (base != null) {
            paneMap.layers().add(BuildingLayer(paneMap, base))
        } else {
            MapRenderer.setBackgroundColor(NO_DATA_BACKGROUND)
            background = NO_DATA_BACKGROUND
        }
        // The casing goes in first so it draws under the line. Off until a day theme is
        // applied; a disabled layer is skipped by the renderer, so at night it costs
        // nothing but holding the points.
        val casing = PathLayer(paneMap, ROUTE_CASING_COLOR, ROUTE_WIDTH + ROUTE_CASING_EXTRA)
        casing.isEnabled = false
        paneMap.layers().add(casing)
        routeCasing = casing
        // Above the roads and buildings and below the labels, so street names stay
        // readable along the route rather than being painted over by it. Present with no
        // map data too: a route over an empty pane still says which way to go.
        val route = PathLayer(paneMap, ROUTE_COLOR, ROUTE_WIDTH)
        paneMap.layers().add(route)
        routeLayer = route
        if (base != null) {
            paneMap.layers().add(LabelLayer(paneMap, base))
            layered = true
            setNight(night)
        }
        paneMap.clearMap()
        return null
    }

    /**
     * Applies the day or the night map theme, whichever `night` asks for, every time it
     * changes.
     *
     * `night` is the head unit's `is_night` (NIGHT_DATA), carried through
     * `Dashboard.restriction` on every sensor event that has one. Until P7.6 this applied
     * one dark theme and ignored the flag, on the grounds that Ford "sends is_night=true
     * once per session and never updates it, even in daylight". That reading was wrong
     * about its cause: every one of those sessions was with the truck's display pinned to
     * dark mode, so what they recorded was the operator's setting, not SYNC 4.0's
     * behaviour (docs/dashboard.md, "Day and night"). A Ford whose display follows its light sensor is
     * expected to send a second NIGHT_DATA at dusk; no recorded session has one yet,
     * which is why the switch is proven only on the lab receiver so far.
     *
     * Called once per frame from the scene thread, so the case that matters for cost is
     * "nothing changed", and that is a single integer compare before anything else.
     */
    fun setNight(night: Boolean) {
        val mode = if (night) NIGHT else DAY
        if (mode == appliedMode) return
        val paneMap = map ?: return
        // A map with no data has no vector layer to theme, and VTM answers a theme for
        // one with an `IllegalStateException`. This runs once per frame, so without this
        // line a dashboard whose operator has imported no map data dies on its first
        // frame — with the pane drawing its "no map data" background, which is exactly
        // the case [start] goes out of its way to keep running. [appliedMode] stays
        // UNTHEMED in that case, so every frame comes back here, and returns here.
        if (!layered) return

        val started = System.nanoTime()
        val switching = appliedMode != UNTHEMED
        val chosen = themeFor(night)
        // Two failed themes share the one fallback; re-applying it would drop every
        // tile to redraw an identical picture.
        if (chosen.theme !== currentTheme) {
            // VTM's `Map.setTheme` pauses the tile loaders (waiting out any tile being
            // read), installs the theme, sets the clear colour to the theme's own
            // map-background, and clears the map, so every tile is read and tessellated
            // again. That last part is the cost of a switch, and why [drawFrame] holds
            // the old picture until the new one is ready.
            paneMap.setTheme(chosen.theme)
            currentTheme = chosen.theme
            background = chosen.theme.mapBackground
            // Only once something has been drawn: the dashboard reads night as false
            // until the first NIGHT_DATA arrives, so a session can switch before its
            // first map frame, and holding then would hold a texture never written.
            if (switching && drawn > 0) beginHold(started)
        }
        routeCasing?.isEnabled = !night
        theme = chosen.name
        appliedMode = mode
        dirty = true
        if (switching) {
            themeSwitches++
            switchCallMsMax = maxOf(switchCallMsMax, (System.nanoTime() - started) / 1_000_000L)
        }
    }

    /**
     * The parsed theme for `night`, loading it on first use.
     *
     * Each is parsed once per session and kept. A drive past dusk switches a few times at
     * most, but the operator's day/night toggle can be pressed repeatedly, and parsing
     * XML on the scene thread for every press would be a frame dropped each time for no
     * reason: VTM's `Map.setTheme(ThemeFile)` is nothing more than [ThemeLoader.load]
     * followed by `setTheme(IRenderTheme)`, and an [IRenderTheme] is not consumed by
     * being applied.
     *
     * The project's own themes, not VTM's. VTM's bundled set is drawn for a phone in the
     * hand: NEWTRON is high-contrast neon and DEFAULT is a paper map, and neither reads
     * well as a panel a driver glances at. `dashboard-dark.xml` is a dark navy scheme
     * with the carriageway as a light ribbon; `dashboard-light.xml` is its daylight
     * partner, a light grey ground with white carriageways, with the same rules in the
     * same order so no feature class appears or disappears at dusk. Both are original
     * work against the rendertheme schema, so no VTM theme is vendored or modified.
     */
    private fun themeFor(night: Boolean): LoadedTheme {
        (if (night) nightTheme else dayTheme)?.let { return it }
        val started = System.nanoTime()
        val asset = if (night) THEME_NIGHT_ASSET else THEME_DAY_ASSET
        val own = loadOwnTheme(asset)
        parseMsMax = maxOf(parseMsMax, (System.nanoTime() - started) / 1_000_000L)
        // A theme that will not parse leaves a blank map, which is worse than a stock
        // one; fall back rather than project nothing. Per theme: a broken day file costs
        // the day map, not the night one that still parses.
        val loaded = if (own != null) {
            LoadedTheme(own, if (night) THEME_DARK else THEME_LIGHT)
        } else {
            fallbackTheme ?: LoadedTheme(ThemeLoader.load(VtmThemes.NEWTRON), THEME_FALLBACK)
                .also { fallbackTheme = it }
        }
        if (night) nightTheme = loaded else dayTheme = loaded
        return loaded
    }

    /** Parses one packaged theme; null if it is missing or will not parse. */
    private fun loadOwnTheme(asset: String): IRenderTheme? {
        return try {
            val stream = MapAssets.openFileAsStream(asset)
            if (stream == null) {
                fail("map theme $asset is not packaged")
                return null
            }
            // A fresh stream per parse: a theme file is consumed as it is read.
            ThemeLoader.load(StreamRenderTheme("", stream))
        } catch (e: RuntimeException) {
            // ThemeException is an IllegalArgumentException, so this covers a file that
            // is packaged but malformed as well as one VTM cannot apply.
            fail("map theme $asset failed to load: $e")
            null
        }
    }

    private fun beginHold(now: Long) {
        // A switch during a switch keeps the first start: the picture on show is still
        // the one from before both, and the hold is measured from when it went stale.
        if (holdStartNs == 0L) holdStartNs = now
        lastRequeueNs = now
    }

    /**
     * Whether the switch being held has its visible tiles loaded, or has waited long
     * enough. Scene thread, after [PaneMap.prepare], which is what fires VTM's clear
     * and hands the tile manager the new visible set to watch.
     *
     * A tile a failed read dropped (VTM clears a FAILED tile back to NONE, see
     * [MapReadGuard]) is only queued again on the next position change, and a parked
     * vehicle makes none. So while held, a dropped tile gets the position event it is
     * waiting for, at most every [REQUEUE_INTERVAL_NS]. Without that a switch while
     * parked over a bad block would sit here until the timeout and then draw with a hole.
     */
    private fun switchLoaded(paneMap: PaneMap): Boolean {
        val manager = baseLayer?.manager
        val now = System.nanoTime()
        if (manager == null) return endHold(now, timedOut = false)
        manager.getActiveTiles(holdTiles)
        var waiting = 0
        var dropped = 0
        for (i in 0 until holdTiles.cnt) {
            val tile = holdTiles.tiles[i] ?: continue
            if (tile.state(LOADED_STATES)) continue
            waiting++
            if (tile.state(MapTile.State.NONE.toInt())) dropped++
        }
        if (waiting == 0) return endHold(now, timedOut = false)
        if (now - holdStartNs >= SWITCH_HOLD_MAX_MS * 1_000_000L) return endHold(now, timedOut = true)
        if (dropped > 0 && now - lastRequeueNs >= REQUEUE_INTERVAL_NS) {
            paneMap.events.fire(Map.POSITION_EVENT, paneMap.mapPosition)
            holdRequeues++
            lastRequeueNs = now
        }
        return false
    }

    /** Ends a hold: records it, unlocks the watched tiles, and sizes the settling frame. */
    private fun endHold(now: Long, timedOut: Boolean): Boolean {
        val ms = (now - holdStartNs) / 1_000_000L
        holdMsLast = ms
        holdMsMax = maxOf(holdMsMax, ms)
        if (timedOut) holdTimeouts++
        settlePasses = ((holdTiles.cnt + TILE_UPLOADS_PER_PASS - 1) / TILE_UPLOADS_PER_PASS)
            .coerceIn(1, MAX_SETTLE_PASSES)
        holdTiles.releaseTiles()
        holdStartNs = 0L
        return true
    }

    /**
     * Where the map is centred, in degrees, with `scale` as VTM's linear scale and
     * `rotation` in degrees clockwise from north (0 leaves the map north-up).
     */
    fun setPosition(latitude: Double, longitude: Double, scale: Double, rotation: Float) {
        val paneMap = map ?: return
        // Unchanged position means an unchanged picture, and the scene loop calls this
        // every frame. Without the comparison a parked vehicle rasterises the whole map
        // 30 times a second to produce the same pixels.
        val next = doubleArrayOf(latitude, longitude, scale, rotation.toDouble())
        if (next.contentEquals(lastPosition)) return
        lastPosition = next
        moved++
        val position = MapPosition(latitude, longitude, scale)
        position.setBearing(-rotation)
        // Tilt stays flat: a pitched map costs fill rate and buys nothing the operator
        // has asked for, and the frame rate is the open question on this slice.
        paneMap.setMapPosition(position)
        dirty = true
    }

    /**
     * Puts [route] on the map as a line, replacing any before it, or takes the line off
     * when it is null. Scene thread.
     *
     * Every point goes to VTM unthinned. Its path worker already clips the line to the
     * view and drops points closer together than a pixel at the current zoom, on its own
     * thread; whether an 8,677-point Austin–Denver route still needs thinning here is a
     * measurement for the frame counters, not an assumption made in advance.
     */
    fun setRoute(route: Route?) {
        val layer = routeLayer ?: return
        if (route == null) {
            layer.clearPath()
            routeCasing?.clearPath()
            routePoints = 0
        } else {
            val points = List(route.points) { GeoPoint(route.latitudes[it], route.longitudes[it]) }
            layer.setPoints(points)
            // Kept in step at night too, so switching to day shows the casing at once
            // rather than a line without its edge until the next route.
            routeCasing?.setPoints(points)
            routePoints = route.points
        }
        drawnRoute = route
        routeChanges++
        dirty = true
    }

    /**
     * Draws [routes] as the live alternatives, replacing any before them; empty takes them
     * off. Scene thread.
     */
    fun setAlternatives(routes: List<Route>) {
        val paneMap = map ?: return
        val layers = paneMap.layers()
        alternativeLayers.forEach { layers.remove(it) }
        alternativeLayers.clear()
        val below = routeCasing?.let { layers.indexOf(it) }?.takeIf { it >= 0 } ?: layers.size
        for (route in routes) {
            val points = List(route.points) { GeoPoint(route.latitudes[it], route.longitudes[it]) }
            val edge = PathLayer(paneMap, ALTERNATIVE_EDGE_COLOR, ALTERNATIVE_WIDTH + ROUTE_CASING_EXTRA)
            val line = PathLayer(paneMap, ALTERNATIVE_COLOR, ALTERNATIVE_WIDTH)
            edge.setPoints(points)
            line.setPoints(points)
            alternativeLayers += edge
            alternativeLayers += line
        }
        layers.addAll(below, alternativeLayers)
        alternativesShown = routes.size
        alternativesShownMax = maxOf(alternativesShownMax, routes.size)
        dirty = true
    }

    /**
     * Colours the route ahead of the vehicle by [traffic] (P9.3, docs/navigation.md), given
     * the position the map follows. Scene thread, every frame; [TrafficLines] says whether
     * anything drawn has to change, and only then are layers touched: traffic for a route
     * other than the one on the map draws nothing, and the cut at the vehicle moves in steps.
     */
    fun setTraffic(traffic: RouteTraffic?, position: Pair<Double, Double>?) {
        val paneMap = map ?: return
        val pieces = this.traffic.update(traffic, drawnRoute, position?.first, position?.second) ?: return
        if (pieces.size > trafficLines.size) growTraffic(paneMap, pieces.size)
        for (i in trafficLines.indices) {
            val edge = trafficEdges[i]
            val line = trafficLines[i]
            val piece = pieces.getOrNull(i)
            if (piece == null) {
                edge.clearPath()
                line.clearPath()
                edge.isEnabled = false
                line.isEnabled = false
                continue
            }
            val points = List(piece.latitudes.size) { GeoPoint(piece.latitudes[it], piece.longitudes[it]) }
            line.setStyle(trafficStyle(piece.congestion))
            edge.setPoints(points)
            line.setPoints(points)
            edge.isEnabled = true
            line.isEnabled = true
        }
        dirty = true
    }

    /**
     * Adds layer pairs until there are [count], keeping every edge below every line so a
     * piece's edge never cuts across the next piece's colour where two meet.
     */
    private fun growTraffic(paneMap: PaneMap, count: Int) {
        val layers = paneMap.layers()
        trafficEdges.forEach { layers.remove(it) }
        trafficLines.forEach { layers.remove(it) }
        while (trafficLines.size < count) {
            trafficEdges += PathLayer(paneMap, TrafficLines.EDGE_COLOUR, ROUTE_WIDTH + ROUTE_CASING_EXTRA)
            trafficLines += PathLayer(paneMap, TrafficLines.SLOW_COLOUR, ROUTE_WIDTH)
        }
        // Straight above the route line: over it, and still under the labels.
        val above = routeLayer?.let { layers.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: layers.size
        layers.addAll(above, trafficEdges + trafficLines)
    }

    /**
     * P11.5: [pois] as markers, replacing those before; empty takes them off. [size] is the
     * drawn diameter in pane pixels ([DashboardLayout.poiMarkerSize]); [emphasised] is drawn
     * half as large again (P11.5a's best stop). Scene thread.
     */
    fun setPois(pois: List<ObfPoi>, size: Int, emphasised: Long? = null) {
        val paneMap = map ?: return
        if (pois.isEmpty() && poiLayer == null) return
        val layer = poiLayer ?: ItemizedLayer(paneMap, ArrayList<MarkerInterface>(), symbol(PoiKind.GAS, size), null).also {
            val layers = paneMap.layers()
            // Under the labels, over everything else the map draws.
            val labels = layers.indexOfFirst { l -> l is LabelLayer }
            if (labels >= 0) layers.add(labels, it) else layers.add(it)
            poiLayer = it
        }
        layer.removeAllItems(false)
        val items = pois.map { poi ->
            MarkerItem(poi.id, "", "", GeoPoint(poi.latitude, poi.longitude)).apply {
                val big = emphasised != null && poi.id == emphasised
                setMarker(symbol(poi.kind ?: PoiKind.SHOP, if (big) size * 3 / 2 else size))
            }
        }
        layer.addItems(items)
        layer.update()
        poiShown = pois
        poiMarkers = pois.size
        poiChanges++
        dirty = true
    }

    /** A kind's marker at [size] px: its colour in a disc, a white glyph, ringed white then dark. */
    private fun symbol(kind: PoiKind, size: Int): MarkerSymbol = poiSymbols.getOrPut("${kind.label}:$size") {
        MarkerSymbol(org.oscim.android.canvas.AndroidBitmap(PoiMarkerArt.draw(kind, size)), MarkerSymbol.HotspotPlace.CENTER, true)
    }

    /**
     * Draws the offer's proposed route (P9.6) in the alternatives' style, under the route's
     * casing like them; null takes it off. Scene thread.
     */
    fun setOffer(route: Route?) {
        val paneMap = map ?: return
        if (route == null) {
            offerEdge?.let { it.clearPath(); it.isEnabled = false }
            offerLine?.let { it.clearPath(); it.isEnabled = false }
        } else {
            if (offerLine == null) {
                val layers = paneMap.layers()
                val edge = PathLayer(paneMap, ALTERNATIVE_EDGE_COLOR, ALTERNATIVE_WIDTH + ROUTE_CASING_EXTRA)
                val line = PathLayer(paneMap, ALTERNATIVE_COLOR, ALTERNATIVE_WIDTH)
                val below = routeCasing?.let { layers.indexOf(it) }?.takeIf { it >= 0 } ?: layers.size
                layers.addAll(below, listOf(edge, line))
                offerEdge = edge
                offerLine = line
            }
            val points = List(route.points) { GeoPoint(route.latitudes[it], route.longitudes[it]) }
            offerEdge?.let { it.setPoints(points); it.isEnabled = true }
            offerLine?.let { it.setPoints(points); it.isEnabled = true }
        }
        offerShown = route != null
        offerChanges++
        dirty = true
    }

    /**
     * Rasterises one frame into the map's own framebuffer, if anything changed.
     *
     * The caller's framebuffer, viewport and vertex state are all restored before this
     * returns. Both [GLState.bindFramebuffer] and [GLState.viewport] issue their GL call
     * unconditionally rather than consulting VTM's state cache, so handing the context
     * back and forth this way keeps that cache truthful.
     *
     * **A day/night switch holds the previous picture until the new one is loaded.** A
     * theme change clears every tile (see [setNight]), and VTM draws a tile it has no data
     * for as the bare background. Rasterised straight through, a switch is a pane that
     * blinks to an empty ground and then fills in tile by tile as the loaders finish, with
     * a moment of old-theme labels over new-theme ground on the way. Instead, while a
     * switch waits, VTM's per-frame update still runs (that is what fires the clear and
     * queues the new tiles) but nothing is rasterised, so the texture keeps the last
     * finished picture; the frame that ends the hold draws the new theme whole. The map
     * pauses for as long as the reload takes, which is the lab's measured switch cost,
     * and [SWITCH_HOLD_MAX_MS] bounds it, so a tile that will not load can cost a hole,
     * never a frozen map.
     */
    fun drawFrame() {
        val paneMap = map ?: return
        val mapRenderer = renderer ?: return
        // Tiles are read and tessellated on VTM's own worker threads, which ask for a
        // redraw through the map when they finish. Picking that request up *before* the
        // early return is what makes asynchronously loaded tiles ever appear: checking
        // it only after a rasterised frame means a map that has gone quiet never
        // notices the tiles it was waiting for, and the pane stays empty over real
        // data with no error anywhere to say why.
        if (paneMap.takeRedrawRequest()) dirty = true
        if (!dirty) {
            reused++
            return
        }
        // VTM's position, animation and event update. It issues no GL, so it runs before
        // the framebuffer is bound, where a held frame can stop without touching it.
        paneMap.prepare()
        if (holdStartNs != 0L && !switchLoaded(paneMap)) {
            // Still dirty, so the next frame comes back to check again.
            held++
            return
        }
        dirty = false
        drawn++

        GLState.bindFramebuffer(framebuffer)
        GLState.viewport(width, height)
        // See [background]: VTM's cached clear colour is stale by now.
        GLES20.glClearColor(
            ((background shr 16) and 0xFF) / 255f,
            ((background shr 8) and 0xFF) / 255f,
            (background and 0xFF) / 255f,
            ((background ushr 24) and 0xFF) / 255f,
        )
        // VTM uploads at most [TILE_UPLOADS_PER_PASS] newly loaded tiles per pass and
        // draws the rest as background until the next. Normally that is invisible,
        // because tiles arrive a few at a time; at the end of a hold they have all
        // arrived at once, so the settling frame takes enough passes to upload them all
        // and the picture it leaves in the texture is the whole map, not part of it.
        val passes = settlePasses
        settlePasses = 1
        repeat(passes) { mapRenderer.onDrawFrame() }
        GLState.bindFramebuffer(0)
    }

    /**
     * Resizes the map to a pane of `newWidth` x `newHeight`: a new framebuffer, and VTM's
     * viewport told the new size. Scene thread, with our EGL context current.
     *
     * The pane changes size when the head unit moves the content rectangle mid-session
     * (VIDEO_GEOMETRY). The chrome follows the new layout at once. Before this, the map
     * kept the size it was started with, so a wider pane showed the old map in its left
     * part and black past it, a strip that stayed put when the map was panned or zoomed
     * (seen on the truck, 24 September 2026).
     */
    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth == width && newHeight == height) return
        if (newWidth <= 0 || newHeight <= 0) return
        width = newWidth
        height = newHeight
        val paneMap = map ?: return
        deleteFramebuffer()
        val fboError = createFramebuffer()
        if (fboError != null) fail(fboError)
        renderer?.onSurfaceChanged(width, height)
        paneMap.viewport().setViewSize(width, height)
        resizes++
        dirty = true
    }

    /** Marks the next [drawFrame] as needing a real rasterisation. */
    fun invalidate() {
        dirty = true
    }

    fun release() {
        if (holdStartNs != 0L) {
            holdTiles.releaseTiles()
            holdStartNs = 0L
        }
        map?.destroy()
        map = null
        baseLayer = null
        readGuard = null
        loaderInterrupts = null
        routeLayer = null
        routeCasing = null
        drawnRoute = null
        trafficEdges.clear()
        trafficLines.clear()
        offerEdge = null
        offerLine = null
        poiLayer = null
        poiSymbols.clear()
        poiShown = emptyList()
        poiMarkers = 0
        routePoints = 0
        layered = false
        appliedMode = UNTHEMED
        currentTheme = null
        dayTheme = null
        nightTheme = null
        fallbackTheme = null
        renderer = null
        deleteFramebuffer()
    }

    private fun deleteFramebuffer() {
        if (colorTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(colorTexture), 0)
        if (depthStencil != 0) GLES20.glDeleteRenderbuffers(1, intArrayOf(depthStencil), 0)
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        colorTexture = 0
        depthStencil = 0
        framebuffer = 0
    }

    /**
     * `key=value` lines for the session report (spec §10). No position is recorded.
     *
     * The `theme_*` fields are the day/night switch (P7.6): how many, how long the
     * scene thread spent in the switching call, and how long the pane held its previous
     * picture while the new theme's tiles loaded (last and worst; -1 before any switch).
     */
    fun report(): String =
        "map_engine=vtm-$VTM_VERSION pane=${width}x$height resizes=$resizes theme=${theme.ifEmpty { "-" }} " +
            "data=$mapName drawn=$drawn reused=$reused moved=$moved " +
            "route_points=$routePoints route_changes=$routeChanges map_alternatives=$alternativesShown map_alternatives_max=$alternativesShownMax " +
            "${readGuard?.report() ?: "map_reads=0"} error=${failure ?: "-"}\n" +
            "map_theme_switches=$themeSwitches map_theme_call_ms_max=$switchCallMsMax " +
            "map_theme_parse_ms_max=$parseMsMax map_theme_held_frames=$held " +
            "map_theme_hold_ms_last=$holdMsLast map_theme_hold_ms_max=$holdMsMax " +
            "map_theme_hold_timeouts=$holdTimeouts map_theme_hold_requeues=$holdRequeues " +
            "map_loader_reopened=${loaderInterrupts?.reopened?.get() ?: 0} " +
            "map_loader_reopen_failed=${loaderInterrupts?.failedRenewals?.get() ?: 0}\n" +
            // P9.3, P9.6: how much of the route traffic coloured and how often it was redrawn;
            // counts only, never where.
            "map_traffic_pieces=${traffic.pieces.size} map_traffic_pieces_max=${traffic.piecesMax} " +
            "map_traffic_rebuilds=${traffic.rebuilds} map_traffic_stale=${traffic.stale} " +
            "map_traffic_dropped=${traffic.dropped} map_traffic_layers=${trafficEdges.size + trafficLines.size} " +
            "map_offer_line=$offerShown map_offer_changes=$offerChanges\n" +
            // P11.5: how many POI markers are drawn and how often they changed; never where.
            "map_poi_markers=$poiMarkers map_poi_changes=$poiChanges\n"

    private fun fail(detail: String): String {
        if (failure == null) failure = detail
        return detail
    }

    /**
     * A tile source over every operator-supplied Mapsforge file, or null when none opens.
     * Never throws: a corrupt file is a named degradation, and one bad file among several
     * costs only its own region.
     *
     * Several files are drawn as one map ([MultiMapFileTileSource]), each only where it
     * has data, so crossing from one region's extract into the next needs nothing to
     * switch. Neighbouring extracts overlap along their border; de-duplication stops the
     * roads there being drawn, and labelled, twice.
     */
    private fun openTileSource(mapFiles: List<File>): TileSource? {
        val opened = mapFiles.mapNotNull(::openMapFile)
        if (opened.isEmpty()) return null
        mapName = opened.joinToString(",") { it.first.name }
        if (opened.size == 1) return opened[0].second
        val multi = MultiMapFileTileSource()
        multi.setDeduplicate(true)
        opened.forEach { multi.add(it.second) }
        return multi
    }

    private fun openMapFile(mapFile: File): Pair<File, MapFileTileSource>? {
        if (!mapFile.isFile) {
            fail("no map data at ${mapFile.absolutePath}")
            return null
        }
        return try {
            val source = MapFileTileSource()
            if (!source.setMapFile(mapFile.absolutePath)) {
                fail("not a readable Mapsforge file: ${mapFile.name}")
                return null
            }
            val result = source.open()
            if (!result.isSuccess) {
                fail("map open failed: ${mapFile.name}: ${result.errorMessage}")
                source.close()
                return null
            }
            // Left open on purpose. The tile layer reads through this same source, and
            // closing it after the header check makes every subsequent tile read fail
            // — silently, per tile, with no error on the source and an empty pane over
            // perfectly good data.
            mapFile to source
        } catch (e: RuntimeException) {
            fail("map open threw: ${mapFile.name}: $e")
            null
        }
    }

    /**
     * Colour texture plus a packed depth/stencil renderbuffer. The stencil is not
     * optional: VTM fills polygons through it, and the window surface this app creates
     * asks for neither depth nor stencil, so the map needs its own.
     */
    private fun createFramebuffer(): String? {
        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        colorTexture = texture[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val buffer = IntArray(1)
        GLES20.glGenRenderbuffers(1, buffer, 0)
        depthStencil = buffer[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthStencil)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GL_DEPTH24_STENCIL8_OES, width, height)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)

        val names = IntArray(1)
        GLES20.glGenFramebuffers(1, names, 0)
        framebuffer = names[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, colorTexture, 0,
        )
        // OES_packed_depth_stencil attaches one renderbuffer to both points.
        GLES20.glFramebufferRenderbuffer(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depthStencil,
        )
        GLES20.glFramebufferRenderbuffer(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_STENCIL_ATTACHMENT, GLES20.GL_RENDERBUFFER, depthStencil,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            return "map framebuffer incomplete: 0x${Integer.toHexString(status)}"
        }
        return null
    }

    /**
     * A [Map] whose redraw requests set a flag instead of waking a `GLSurfaceView`.
     *
     * The scene loop already runs at the negotiated frame rate, so there is nothing to
     * wake; what VTM's asynchronous tile loaders need is a way to say "the picture would
     * change now", which is exactly what the flag carries to the next [drawFrame].
     */
    private inner class PaneMap : Map() {
        /** Set from tile-loader threads as well as the scene thread. */
        @Volatile
        private var redrawRequested = false

        override fun getWidth(): Int = this@MapEngine.width

        override fun getHeight(): Int = this@MapEngine.height

        // No phone screen is involved; the projected frame is the whole world here.
        override fun getScreenWidth(): Int = this@MapEngine.width

        override fun getScreenHeight(): Int = this@MapEngine.height

        override fun updateMap() = updateMap(true)

        override fun updateMap(redraw: Boolean) {
            redrawRequested = true
        }

        override fun render() {
            redrawRequested = true
        }

        override fun post(action: Runnable): Boolean = sceneHandler?.post(action) ?: false

        override fun postDelayed(action: Runnable, delay: Long): Boolean =
            sceneHandler?.postDelayed(action, delay) ?: false

        override fun beginFrame() = Unit

        override fun doneFrame(needsRedraw: Boolean) {
            if (needsRedraw) redrawRequested = true
        }

        /** Runs VTM's per-frame position and animation update on the scene thread. */
        fun prepare() = prepareFrame()

        fun takeRedrawRequest(): Boolean {
            val requested = redrawRequested
            redrawRequested = false
            return requested
        }
    }

    /** Where [PaneMap.post] sends work; the scene thread's handler. */
    var sceneHandler: android.os.Handler? = null

    /** A parsed theme and the name the report gives it. */
    private class LoadedTheme(val theme: IRenderTheme, val name: String)

    companion object {
        const val VTM_VERSION = "0.25.0"

        /** `GL_DEPTH24_STENCIL8_OES`, core in GLES3 and an extension everywhere else. */
        private const val GL_DEPTH24_STENCIL8_OES = 0x88F0

        private const val TARGET_DPI = 240f

        /**
         * The route line: a saturated cyan that no road in either owned theme uses, so it
         * cannot be mistaken for one. Bright against the navy ground at night; on the day
         * theme it gets [ROUTE_CASING_COLOR] under it. The same in both, because the lab's
         * route-line check (tools/route-line-pixels.py) counts it by value.
         */
        const val ROUTE_COLOR = 0xFF22C7F0.toInt()

        /** Pixels at the map's 240 dpi; wider than a motorway casing, so it reads at a glance. */
        private const val ROUTE_WIDTH = 9f

        /**
         * The day casing: a deep teal, darker than anything in `dashboard-light.xml` but
         * the labels, so the line has an edge against white roads and grey ground alike,
         * and still reads as the route's own colour family rather than as a road class.
         */
        private const val ROUTE_CASING_COLOR = 0xFF0B4F6C.toInt()

        /** Casing overhang, pixels at 240 dpi: two each side, a visible edge without bulk. */
        private const val ROUTE_CASING_EXTRA = 4f

        /**
         * The live alternatives: a mid grey no road in either theme uses, on a darker edge so it
         * reads on the day theme's white roads and the night theme's navy alike, and narrower
         * than the route so the route is still the line to follow.
         */
        const val ALTERNATIVE_COLOR = 0xFF9AA5B1.toInt()
        private const val ALTERNATIVE_EDGE_COLOR = 0xFF4A5560.toInt()
        private const val ALTERNATIVE_WIDTH = 6f

        /**
         * A traffic piece's line (P9.3): the route's width, so it reads as the route changing
         * colour, not as a second line. A closure is dashed with VTM's own stipple, the same
         * mechanism the themes draw paths and railways with: [TrafficLines.CLOSED_COLOUR]
         * dashes [TrafficLines.CLOSED_DASH] pixels long over [TrafficLines.CLOSED_GAP_COLOUR].
         * VTM's stipple shader mixes the two colours along the line (`linetex_layer.glsl`),
         * so the "gaps" are that light colour rather than holes, which is also what makes
         * them show on the night ground.
         */
        private fun trafficStyle(congestion: Congestion): LineStyle =
            if (TrafficLines.dashed(congestion)) {
                LineStyle(
                    0, "", TrafficLines.CLOSED_GAP_COLOUR, ROUTE_WIDTH, Cap.BUTT, true, 1.0,
                    TrafficLines.CLOSED_DASH, TrafficLines.CLOSED_COLOUR, 1f, -1, 0f, false, null, true, null,
                    LineStyle.REPEAT_START_DEFAULT * CanvasAdapter.getScale(),
                    LineStyle.REPEAT_GAP_DEFAULT * CanvasAdapter.getScale(), false,
                )
            } else {
                LineStyle(TrafficLines.colour(congestion) ?: ROUTE_COLOR, ROUTE_WIDTH, Cap.BUTT)
            }

        /** Packaged at `app/src/main/resources/assets/oaa/`, read through [MapAssets]. */
        private const val THEME_NIGHT_ASSET = "oaa/dashboard-dark.xml"
        private const val THEME_DAY_ASSET = "oaa/dashboard-light.xml"
        private const val THEME_DARK = "oaa-dark"
        private const val THEME_LIGHT = "oaa-light"
        private const val THEME_FALLBACK = "vtm-fallback"

        /** Values of [appliedMode]. */
        private const val UNTHEMED = -1
        private const val DAY = 0
        private const val NIGHT = 1

        /**
         * The longest a switch holds the previous picture, ms. The lab measured a switch
         * at well under this (docs/verification.md); it is a bound for a tile that
         * will not load, so a stuck read costs a hole in the new picture rather than a
         * map that stops following the vehicle.
         */
        private const val SWITCH_HOLD_MAX_MS = 1500L

        /** How often a held switch re-asks for tiles a failed read dropped. */
        private const val REQUEUE_INTERVAL_NS = 250_000_000L

        /**
         * VTM's `TileRenderer.MAX_TILE_LOAD`: newly loaded tiles uploaded per render pass.
         * It is `protected` there, so it is restated here; if VTM raised it, the settling
         * frame would just take a pass or two more than it needs.
         */
        private const val TILE_UPLOADS_PER_PASS = 8

        /** Bounds the settling frame; a pane at 1080p shows well under 32 tiles. */
        private const val MAX_SETTLE_PASSES = 4

        /** A tile whose data is loaded: uploaded, or waiting to be on the next pass. */
        private val LOADED_STATES = MapTile.State.NEW_DATA.toInt() or MapTile.State.READY.toInt()

        /** Distinct from both themes, so "no data" never reads as an empty region. */
        private const val NO_DATA_BACKGROUND = 0xFF23282E.toInt()

        @Volatile
        private var nativeLoaded = false

        /**
         * Loads VTM's tessellator once per process. It is the only native code VTM
         * brings, 45 KB for the one target ABI.
         */
        @Synchronized
        private fun loadNative(): String? {
            if (nativeLoaded) return null
            return try {
                System.loadLibrary("vtm-jni")
                nativeLoaded = true
                null
            } catch (e: UnsatisfiedLinkError) {
                "libvtm-jni.so did not load: $e"
            }
        }
    }
}
