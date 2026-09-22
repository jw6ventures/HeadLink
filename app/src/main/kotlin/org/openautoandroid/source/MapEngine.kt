package org.openautoandroid.source

import android.opengl.GLES20
import org.oscim.android.canvas.AndroidGraphics
import org.oscim.android.gl.AndroidGL
import org.oscim.backend.CanvasAdapter
import org.oscim.backend.DateTime
import org.oscim.backend.DateTimeAdapter
import org.oscim.backend.GLAdapter
import org.oscim.core.GeoPoint
import org.oscim.core.MapPosition
import org.oscim.core.Tile
import org.oscim.layers.PathLayer
import org.oscim.layers.tile.buildings.BuildingLayer
import org.oscim.layers.tile.vector.VectorTileLayer
import org.oscim.layers.tile.vector.labeling.LabelLayer
import org.oscim.map.Map
import org.oscim.renderer.GLState
import org.oscim.renderer.MapRenderer
import org.oscim.theme.StreamRenderTheme
import org.oscim.theme.internal.VtmThemes
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
class MapEngine(private val width: Int, private val height: Int) {

    /** Set once the map has a tile source with a readable file behind it. */
    var mapName: String = "-"
        private set

    /** First failure, for the session report; the dashboard degrades rather than dies. */
    var failure: String? = null
        private set

    private var map: PaneMap? = null
    private var routeLayer: PathLayer? = null

    /** Retries tiles VTM read badly; see [MapReadGuard]. Null with no map data. */
    private var readGuard: MapReadGuard? = null

    /** Points in the route line on show, 0 for none. A count, never the geometry. */
    var routePoints = 0
        private set

    /** Times a route line was put on the map or taken off it. */
    var routeChanges = 0L
        private set
    private var renderer: MapRenderer? = null
    private var theme = ""

    /**
     * Whether a tile source was installed, so the map has vector layers to theme.
     *
     * VTM's `Map.setTheme` throws `IllegalStateException("No vector layers set")` on a map
     * with no base layer, and [setNight] is called once per frame from the render thread —
     * so on a dashboard with no map data the first frame killed the process (P6.2 lab run,
     * 21 September 2026). [start] already knew not to theme an empty map; nothing else
     * did, because [theme] stays empty forever in that case and is not the same question.
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
        // has no INTERNET permission (APP10), so there is no download to fall back on
        // and the operator has to supply the file — which they can only be told about
        // if this keeps running and reports why.
        val source = openTileSource(mapFiles)?.let(::MapReadGuard)
        readGuard = source
        val base: VectorTileLayer? = source?.let { paneMap.setBaseMap(it) }
        if (base != null) {
            paneMap.layers().add(BuildingLayer(paneMap, base))
        } else {
            MapRenderer.setBackgroundColor(NO_DATA_BACKGROUND)
        }
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
     * Applies the map's render theme.
     *
     * There is one theme, and `night` currently changes nothing but the clear colour.
     * That is deliberate rather than unfinished: the only signal available is Ford's
     * `is_night`, which it sends true once per session and never updates even in
     * daylight, so switching a whole theme on it would leave a driver with a night map
     * at noon and no way to correct it. A day variant belongs with a signal worth
     * trusting.
     */
    fun setNight(night: Boolean) {
        val paneMap = map ?: return
        // A map with no data has no vector layer to theme, and VTM answers a theme for
        // one with an `IllegalStateException`. This runs once per frame, so without this
        // line a dashboard whose operator has imported no map data dies on its first
        // frame — with the pane drawing its "no map data" background, which is exactly
        // the case [start] goes out of its way to keep running.
        if (!layered || theme.isNotEmpty()) return

        // The project's own theme, not one of VTM's. Its bundled set is drawn for a
        // phone in the hand: NEWTRON is high-contrast neon and DEFAULT is a paper map,
        // and neither reads well as a panel a driver glances at. `dashboard-dark.xml`
        // is a dark navy scheme with the carriageway as a light ribbon and arterials
        // in warm tan. It is original work against the rendertheme schema, so no VTM
        // theme is vendored or modified.
        //
        // Ford sends is_night=true once per session and never updates it, even in
        // daylight, so this is not yet a light sensor worth trusting — see the
        // night-mode finding in the P5.2 record. Until that is settled the dark theme
        // is the one that gets used in practice.
        val applied = applyOwnTheme()
        if (!applied) {
            // A theme that will not parse leaves a blank map, which is worse than a
            // stock one; fall back rather than project nothing.
            paneMap.setTheme(VtmThemes.NEWTRON)
        }
        theme = if (applied) THEME_DARK else THEME_FALLBACK
        MapRenderer.setBackgroundColor(NIGHT_BACKGROUND)
        dirty = true
    }

    /** Loads `dashboard-dark.xml`; false if it is missing or will not parse. */
    private fun applyOwnTheme(): Boolean {
        val paneMap = map ?: return false
        return try {
            val stream = MapAssets.openFileAsStream(THEME_ASSET)
            if (stream == null) {
                fail("map theme $THEME_ASSET is not packaged")
                return false
            }
            // A fresh stream per load: a theme file is consumed as it is parsed, so a
            // cached one cannot be applied twice.
            paneMap.setTheme(StreamRenderTheme("", stream))
            true
        } catch (e: RuntimeException) {
            fail("map theme failed to load: $e")
            false
        }
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
            routePoints = 0
        } else {
            layer.setPoints(List(route.points) { GeoPoint(route.latitudes[it], route.longitudes[it]) })
            routePoints = route.points
        }
        routeChanges++
        dirty = true
    }

    /**
     * Rasterises one frame into the map's own framebuffer, if anything changed.
     *
     * The caller's framebuffer, viewport and vertex state are all restored before this
     * returns. Both [GLState.bindFramebuffer] and [GLState.viewport] issue their GL call
     * unconditionally rather than consulting VTM's state cache, so handing the context
     * back and forth this way keeps that cache truthful.
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
        dirty = false
        drawn++

        GLState.bindFramebuffer(framebuffer)
        GLState.viewport(width, height)
        paneMap.prepare()
        mapRenderer.onDrawFrame()
        GLState.bindFramebuffer(0)
    }

    /** Marks the next [drawFrame] as needing a real rasterisation. */
    fun invalidate() {
        dirty = true
    }

    fun release() {
        map?.destroy()
        map = null
        readGuard = null
        routeLayer = null
        routePoints = 0
        layered = false
        renderer = null
        val names = intArrayOf(colorTexture)
        if (colorTexture != 0) GLES20.glDeleteTextures(1, names, 0)
        if (depthStencil != 0) GLES20.glDeleteRenderbuffers(1, intArrayOf(depthStencil), 0)
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        colorTexture = 0
        depthStencil = 0
        framebuffer = 0
    }

    /** `key=value` lines for the session report (spec §10). No position is recorded. */
    fun report(): String =
        "map_engine=vtm-$VTM_VERSION pane=${width}x$height theme=${theme.ifEmpty { "-" }} " +
            "data=$mapName drawn=$drawn reused=$reused moved=$moved " +
            "route_points=$routePoints route_changes=$routeChanges " +
            "${readGuard?.report() ?: "map_reads=0"} error=${failure ?: "-"}\n"

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

    companion object {
        const val VTM_VERSION = "0.25.0"

        /** `GL_DEPTH24_STENCIL8_OES`, core in GLES3 and an extension everywhere else. */
        private const val GL_DEPTH24_STENCIL8_OES = 0x88F0

        private const val TARGET_DPI = 240f

        /**
         * The route line: a saturated cyan that no road in `dashboard-dark.xml` uses, so
         * it cannot be mistaken for one, and bright against the navy ground at night.
         */
        const val ROUTE_COLOR = 0xFF22C7F0.toInt()

        /** Pixels at the map's 240 dpi; wider than a motorway casing, so it reads at a glance. */
        private const val ROUTE_WIDTH = 9f

        /** Packaged at `app/src/main/resources/assets/oaa/`, read through [MapAssets]. */
        private const val THEME_ASSET = "oaa/dashboard-dark.xml"
        private const val THEME_DARK = "oaa-dark"
        private const val THEME_FALLBACK = "vtm-fallback"

        /** Matches `map-background` in the owned theme, so a tile gap is invisible. */
        private const val NIGHT_BACKGROUND = 0xFF151A2B.toInt()

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
