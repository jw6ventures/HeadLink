package org.openautoandroid.source

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.nio.ByteBuffer

/** Video configuration the native session negotiated, from its video start event. */
data class VideoStartParams(
    val session: Int,
    val channel: Int,
    val display: Int,
    val config: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val contentX: Int,
    val contentY: Int,
    val contentWidth: Int,
    val contentHeight: Int,
    val touch: String,
) {
    companion object {
        /** Parses the `key=value` payload of a video start event; null if it is incomplete. */
        fun parse(payload: Map<String, String>): VideoStartParams? {
            fun int(key: String): Int? = payload[key]?.toIntOrNull()
            val width = int("width") ?: return null
            val height = int("height") ?: return null
            return VideoStartParams(
                session = int("session") ?: return null,
                channel = int("channel") ?: return null,
                display = int("display") ?: 0,
                config = int("config") ?: return null,
                width = width,
                height = height,
                fps = int("fps") ?: 30,
                contentX = int("content_x") ?: 0,
                contentY = int("content_y") ?: 0,
                contentWidth = int("content_width") ?: width,
                contentHeight = int("content_height") ?: height,
                touch = payload["touch"] ?: "-",
            )
        }
    }
}

/**
 * The app side of projection (spec §8.2–8.3): an EGL renderer draws the owned scene into
 * the encoder's input Surface on its own thread, the encoder's output goes straight to the
 * native session, and head-unit input updates the scene that the next frame shows.
 *
 * Started when the native session reports video start and released when it stops, so every
 * attempt has fresh encoder, EGL, and scene state.
 */
class ProjectionPipeline(
    private val handle: Long,
    val params: VideoStartParams,
    sessionUuid: String,
    private val softwareEncoder: Boolean,
    encoderFps: Int,
    private val sceneArea: String,
    /** Where to write the encoded stream, or null. */
    private val videoOut: java.io.File?,
    /** Seconds between IDRs; Android Auto uses 60 where this source used 1. */
    private val keyframeSeconds: Int,
    /**
     * Rebuild the encoder and its EGL surface when the head unit hands the screen back,
     * instead of resuming the running one.
     *
     * Android Auto does exactly this — `doHandleVideoFocusGained` tears the encoder down
     * and re-creates it on **every** focus gain (Gearhead 17.4 logcat, 2026-09-19
     * 16:10:54) — and on Ford SYNC 4.0 the picture does not come back without it: Ford
     * re-runs the `UpdateUiConfigRequest`/reply handshake on each gain, assigning a new
     * display configuration id, and a source that merely resumes never gets the second
     * request and so is never re-attached, while the head unit goes on acknowledging every
     * frame it is not showing (reports 6d6fbb29 and 34c4a067, both `ui_config_updates=1`
     * against Gearhead's two).
     *
     * Clearing this restores the resume-in-place behaviour, for comparison on a vehicle.
     */
    private val rebuildOnRegain: Boolean,
    /** [SessionSettings.SCENE_DASHBOARD] or [SessionSettings.SCENE_DEMO]. */
    private val sceneKind: String,
    private val mapOnLeft: Boolean,
    /** The unit the dashboard header spells the speed in. */
    private val speedUnit: SpeedUnit,
    /** Which way is up in the map pane at the start; the driver's last choice. */
    private val mapOrientation: MapOrientation,
    /** Told when the compass bubble changes the orientation, so it can be remembered. */
    private val onMapOrientation: (MapOrientation) -> Unit,
    /**
     * Marker colour, day/night, diagnostics and the pause (P7.2, P7.3, P7.6), from the
     * saved settings, which a lab run reads too: what the dashboard looks like is the
     * driver's, not the attempt's.
     */
    private val choices: DashboardChoices,
    /** Where ☀/☾, Ⓟ and the palette in use are kept between sessions. */
    private val saves: DashboardSaves,
    /** Operator-supplied Mapsforge data, drawn as one map; empty runs the pane without. */
    private val mapFiles: List<java.io.File>,
    private val zone: java.time.ZoneId,
    private val onFailure: (String) -> Unit,
) : VideoEncoder.Listener {
    /**
     * Rate the encoder and renderer actually run at. The negotiated rate unless an
     * operator overrode it: the configuration index is what the head unit was told, and it
     * says nothing about frame rate, so encoding slower is legal and changes only the
     * H.264 level and bitrate we emit.
     */
    val fps: Int = if (encoderFps in 1..60) encoderFps else params.fps
    /**
     * Where the scene goes in the frame. Head-unit touches arrive mapped into frame pixels
     * either way, so button hit-testing follows the layout without further translation.
     */
    /** The rectangle the scene is laid out in: the whole frame, or the content area. */
    private fun area(from: VideoStartParams): IntArray =
        if (sceneArea == SessionSettings.SCENE_AREA_FRAME) {
            intArrayOf(0, 0, from.width, from.height)
        } else {
            intArrayOf(from.contentX, from.contentY, from.contentWidth, from.contentHeight)
        }

    private val dashboardMode = sceneKind == SessionSettings.SCENE_DASHBOARD

    /**
     * The P4 test scene, present only when it is the one being projected. It stays
     * available because the lab receiver samples its colour patches and the G5
     * calibration aims at its crosses; see [SessionSettings.scene].
     */
    val scene: DemoScene? = if (dashboardMode) {
        null
    } else {
        area(params).let { DemoScene(SceneLayout(it[0], it[1], it[2], it[3]), DemoScene.token(sessionUuid)) }
    }

    /** The P5.5 dashboard, present only when it is the one being projected. */
    val dashboard: Dashboard? = if (!dashboardMode) {
        null
    } else {
        area(params).let {
            Dashboard(
                DashboardLayout(it[0], it[1], it[2], it[3], mapOnLeft),
                Dashboard.token(sessionUuid),
                zone,
                speedUnit,
                mapOrientation,
                onMapOrientation,
                choices,
                saves,
            )
        }
    }

    private val thread = HandlerThread("aap-scene")
    private var handler: Handler? = null
    private var encoder: VideoEncoder? = null
    private var renderer: SceneRenderer? = null
    private var dashboardRenderer: DashboardRenderer? = null

    /** The route the map engine was last given. Scene thread; reset with the renderer. */
    private var shownRoute: Route? = null
    private var shownAlternatives: List<LiveAlternative>? = null

    /** The offer's proposed route the map engine was last given (P9.6). Scene thread. */
    private var shownOfferRoute: Route? = null

    /**
     * P11.5: the POI marker loop (docs/p11-plan.md §6), fed by the scene thread every frame;
     * null runs the dashboard without markers. Installed by the service before [start].
     */
    @Volatile
    var poiFeed: PoiFeed? = null

    /** The markers the map engine was last given. Scene thread; reset with the renderer. */
    private var shownPois: PoiShown? = null

    /**
     * Told when Take or Keep is pressed on the divided turn card (P9.6): the offer's id, and
     * true for Take. Called on the main thread, from [touch], after the dashboard has let go
     * of its lock, so it may start a route or record the refusal directly. Installed by the
     * service with the traffic loop; null drops the answer, and the card is whole again
     * either way.
     */
    var offerListener: ((id: Long, take: Boolean) -> Unit)? = null
    private var startedNs = 0L
    private var nextFrameNs = 0L

    @Volatile
    private var running = false

    /** Whether frames are drawn now. Written under [focusLock]; read by the draw loop. */
    @Volatile
    private var focused = true

    /**
     * What the head unit last said about the screen. Apart from [focused] while a rebuild
     * is queued or running, so a loss that arrives in that window is not overridden when
     * the rebuild finishes.
     */
    private var wantFocus = true
    private val focusLock = Any()

    @Volatile
    private var failure: String? = null

    // Counters; written on the renderer or encoder thread, read for the report.
    @Volatile
    private var rendered = 0L

    @Volatile
    private var submitted = 0L
    private val results = java.util.concurrent.atomic.AtomicLongArray(SUBMIT_RESULTS)

    @Volatile
    private var keyframeRequests = 0L

    @Volatile
    private var relayouts = 0L

    /**
     * Encoder rebuilds performed on a focus regain or a congestion restart, and the first
     * one that failed.
     */
    @Volatile
    private var rebuilds = 0L

    /** Congestion restarts the native session asked for ([restartEncoder]). */
    @Volatile
    private var congestionRestarts = 0L

    @Volatile
    private var rebuildError: String? = null

    @Volatile
    private var bitRate = 0

    /** Encoder thread only, between [start] and [release]. */
    private var capture: java.io.OutputStream? = null

    @Volatile
    private var capturedBytes = 0L

    @Volatile
    private var captureError: String? = null

    /** Starts the encoder and renderer. Returns null on success or the failure reason. */
    fun start(): String? {
        bitRate = VideoEncoder.bitRateFor(layoutWidth(), layoutHeight())
        val video = VideoEncoder(
            params.width, params.height, fps, bitRate, keyframeSeconds, this, softwareEncoder,
        )
        val surface = video.start()
        encoder = video
        if (surface == null) {
            val detail = "no usable H.264 encoder: ${video.description}"
            release()
            return detail
        }
        if (videoOut != null) {
            capture = try {
                videoOut.parentFile?.mkdirs()
                java.io.BufferedOutputStream(java.io.FileOutputStream(videoOut))
            } catch (e: java.io.IOException) {
                captureError = e.toString()
                null
            }
        }
        thread.start()
        val handler = Handler(thread.looper)
        this.handler = handler
        running = true
        val started = java.util.concurrent.CountDownLatch(1)
        var setup: String? = null
        handler.post {
            // Caught so the latch below always opens: an exception escaping here would
            // kill the process from the scene thread while main waited on it.
            setup = try {
                startRenderer(surface, handler)
            } catch (e: RuntimeException) {
                "renderer start failed: $e"
            }
            if (setup == null) {
                startedNs = SystemClock.elapsedRealtimeNanos()
                nextFrameNs = startedNs
                frame()
            }
            started.countDown()
        }
        started.await()
        return setup?.also { release() }
    }

    /**
     * Renderer thread: draws one frame and schedules the next at the negotiated rate. A
     * frame that throws (the map engine, a bitmap, EGL) ends the attempt with a named
     * reason, as an encoder failure does, rather than taking the process down from the
     * scene thread mid-drive; no further frame is scheduled.
     */
    private fun frame() {
        try {
            drawFrame()
        } catch (e: RuntimeException) {
            failScene("scene frame failed: $e")
        }
    }

    private fun drawFrame() {
        if (!running) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (focused) {
            val demo = scene
            val board = dashboard
            if (demo != null) {
                val renderer = renderer ?: return
                val snapshot = demo.nextFrame((now - startedNs) / 1_000_000)
                renderer.draw(demo, snapshot, now - startedNs)
                rendered++
            } else if (board != null) {
                val renderer = dashboardRenderer ?: return
                // The map is placed before the frame is drawn, so the pane and the
                // chrome over it never disagree about where the vehicle is: the marker
                // the chrome draws is worked out against this same camera.
                board.mapCamera()?.let { renderer.updateMap(it, board.isNight()) }
                // The route is computed on its own thread and published on main; this is
                // the scene thread's one read of it. A reference compare, so an unchanged
                // route costs nothing, and a pipeline started after the route was
                // computed picks it up on its first frame.
                val route = Routing.line
                if (route !== shownRoute) {
                    renderer.setRoute(route)
                    shownRoute = route
                    board.followedRoute(route)
                }
                // Likewise the live alternatives, a list replaced whole on the main thread.
                val alternatives = LiveAlternatives.shown
                if (alternatives !== shownAlternatives) {
                    renderer.setAlternatives(alternatives.map { it.route })
                    shownAlternatives = alternatives
                }
                // P9: the traffic colours on the route ahead, every frame, because where the
                // vehicle is decides which part is ahead; the map engine rebuilds its layers
                // only when that or the traffic changes (TrafficLines). After the route, so a
                // new route and the traffic matched to it land in the same frame.
                renderer.setTraffic(board.trafficDisplay().traffic, board.followPosition())
                val offerRoute = board.shownOffer()?.route
                if (offerRoute !== shownOfferRoute) {
                    renderer.setOffer(offerRoute)
                    shownOfferRoute = offerRoute
                }
                // P11.5: the POI markers. The feed only decides here; its queries run on its
                // own thread, and what they found is read back by reference, like the route.
                poiFeed?.let { feed ->
                    feed.frame(board.poiView(), SystemClock.elapsedRealtime())
                    val pois = feed.shown
                    if (pois !== shownPois) {
                        renderer.setPois(pois.pois, board.layout.poiMarkerSize, pois.emphasised)
                        board.pois(pois.pois)
                        shownPois = pois
                    }
                }
                val snapshot = board.nextFrame(System.currentTimeMillis())
                renderer.draw(board.layout, snapshot, now - startedNs)
                rendered++
            }
        }
        val interval = 1_000_000_000L / fps.coerceIn(1, 60)
        nextFrameNs = maxOf(nextFrameNs + interval, now)
        val delayMs = ((nextFrameNs - now) / 1_000_000L).coerceIn(0L, 1_000L)
        handler?.postDelayed(::frame, delayMs)
    }

    /** Encoder thread: hand the buffer to the native session, which copies it. */
    override fun onBuffer(flags: Int, presentationUs: Long, buffer: ByteBuffer, offset: Int, size: Int) {
        submitted++
        writeCapture(buffer, offset, size)
        val result = NativeBridge.submitVideo(handle, flags, presentationUs, buffer, offset, size)
        val index = if (result in 0 until SUBMIT_RESULTS) result else SUBMIT_RESULTS - 1
        results.incrementAndGet(index)
    }

    override fun onEncoderError(detail: String) {
        if (failure == null) {
            failure = detail
            onFailure(detail)
        }
    }

    /**
     * Encoder thread: append the unit to the capture file exactly as it was submitted, so
     * the file is the byte stream the head unit received. A failure stops capturing and is
     * reported; it never affects the session.
     */
    private fun writeCapture(buffer: ByteBuffer, offset: Int, size: Int) {
        val out = capture ?: return
        try {
            val copy = ByteArray(size)
            val duplicate = buffer.duplicate()
            duplicate.position(offset)
            duplicate.get(copy, 0, size)
            out.write(copy)
            capturedBytes += size
        } catch (e: java.io.IOException) {
            captureError = e.toString()
            try {
                out.close()
            } catch (_: java.io.IOException) {
            }
            capture = null
        }
    }

    /**
     * The head unit took the screen or handed it back.
     *
     * On a regain this rebuilds the encoder rather than resuming it, so a fresh SPS/PPS and
     * IDR go out from a new codec instance, as Android Auto does — see [rebuildOnRegain].
     * Drawing stays off until the rebuild finishes, so no frame is painted into a surface
     * that is about to be torn down.
     */
    fun setFocused(projected: Boolean) {
        synchronized(focusLock) {
            wantFocus = projected
            val handler = handler
            if (projected && !focused && rebuildOnRegain && handler != null) {
                // On the renderer thread, so it cannot interleave with a frame in flight: the
                // draw loop never observes the moment where the encoder and EGL surface are
                // gone. Drawing stays off until it finishes.
                handler.post(::rebuildIfWanted)
                return
            }
            focused = projected
        }
    }

    /**
     * The native session restarted the stream because the head unit kept falling behind
     * (spec §8.2): rebuild the encoder exactly as a focus regain does, so the restarted
     * stream opens with a fresh codec's configuration and IDR. Focus is unchanged, so a
     * restart while the head unit has the screen does nothing; with [rebuildOnRegain]
     * cleared only an IDR is asked for.
     */
    fun restartEncoder() {
        synchronized(focusLock) {
            val handler = handler
            congestionRestarts++
            if (!rebuildOnRegain || handler == null || !wantFocus || !focused) {
                encoder?.requestKeyframe()
                return
            }
            // Drawing stops until the rebuild brings it back, as on a regain.
            focused = false
            handler.post(::rebuildIfWanted)
        }
    }

    /**
     * Renderer thread. A queued rebuild whose regain was taken back before it ran (the
     * screen lost again) does nothing: the next regain queues its own. One queued behind a
     * rebuild that already brought drawing back does nothing either.
     */
    private fun rebuildIfWanted() {
        synchronized(focusLock) {
            if (!wantFocus || focused) return
        }
        try {
            rebuild()
        } catch (e: RuntimeException) {
            failRebuild("rebuild on focus regain failed: $e")
        }
    }

    /** Renderer thread only. Replaces the encoder and EGL surface, then resumes drawing. */
    private fun rebuild() {
        if (!running) return
        releaseRenderer()
        encoder?.release()
        encoder = null

        val video = VideoEncoder(
            params.width, params.height, fps, bitRate, keyframeSeconds, this, softwareEncoder,
        )
        val surface = video.start()
        if (surface == null) {
            return failRebuild("no usable H.264 encoder on focus regain: ${video.description}")
        }
        encoder = video
        val handler = handler ?: return failRebuild("no renderer thread on focus regain")
        val setup = startRenderer(surface, handler)
        if (setup != null) {
            return failRebuild("no EGL surface on focus regain: $setup")
        }
        rebuilds++
        // `startedNs` is deliberately not reset: presentation times must keep rising across
        // the rebuild or the head unit sees a timestamp regression on the same AV session.
        // Android Auto keeps the session too — its flow controller is initialised once per
        // connection, not per focus gain.
        // Only if the screen is still ours: a loss during the rebuild stands, and the
        // next regain finds drawing off and rebuilds again.
        synchronized(focusLock) { focused = wantFocus }
    }

    /**
     * A rebuild that could not produce an encoder leaves the stream dark, which is worse
     * than the resume it replaced, so it ends the attempt with a named reason rather than
     * silently projecting nothing.
     */
    private fun failRebuild(detail: String) {
        rebuildError = detail
        failScene(detail)
    }

    /** Renderer thread: stops drawing and ends the attempt with [detail], once. */
    private fun failScene(detail: String) {
        synchronized(focusLock) { focused = false }
        if (failure == null) {
            failure = detail
            onFailure(detail)
        }
    }

    /**
     * The head unit moved the content rectangle inside the same frame. The encoder and EGL
     * surface keep running, since only the rectangle changed, and an IDR follows so the
     * first picture at the new layout is complete.
     */
    fun relayout(updated: VideoStartParams) {
        val box = area(updated)
        scene?.relayout(SceneLayout(box[0], box[1], box[2], box[3]))
        // The dashboard renderer notices the new map pane on its next frame and resizes
        // the map to match (DashboardRenderer.fitMap).
        dashboard?.relayout(DashboardLayout(box[0], box[1], box[2], box[3], mapOnLeft))
        relayouts++
        // The head unit only ever shows the content rectangle, so the bitrate follows it
        // rather than the unchanged frame, as Android Auto's does.
        val bps = VideoEncoder.bitRateFor(layoutWidth(), layoutHeight())
        if (encoder?.setBitRate(bps) == true) bitRate = bps
        requestKeyframe()
    }

    fun requestKeyframe() {
        keyframeRequests++
        encoder?.requestKeyframe()
    }

    /**
     * A head-unit touch. Returns the transport action an on-screen media button press
     * completed, so the caller can dispatch it to whatever is playing; null otherwise.
     */
    fun touch(action: String, pointers: List<TouchPoint>): String? {
        val first = pointers.firstOrNull() ?: return null
        // The P4 scene is single-touch and is what the G5 calibration aims at, so it
        // keeps the first pointer and its unmapped coordinates; the dashboard's map
        // pane is the only thing here that has any use for a second finger.
        scene?.touch(action, first.x, first.y, first.rawX, first.rawY)
        val board = dashboard ?: return null
        val pressed = board.touch(action, pointers)
        // P9.6: Take or Keep, handed on outside the dashboard's lock.
        board.takeOfferAnswer()?.let { offerListener?.invoke(it.id, it.take) }
        return pressed
    }

    /**
     * What the projected map shows of traffic (P9.3, P9.4, P9.6), from the traffic loop on
     * the main thread, whole, whenever the traffic, the delay or the offer changes: the
     * route's colours, "Traffic © HERE", "+N min traffic" and the divided turn card.
     */
    fun traffic(display: TrafficDisplay) {
        dashboard?.traffic(display)
    }

    /** A destination a long press on the projected map picked, once, or null. */
    fun takeMapPick(): Pair<Double, Double>? = dashboard?.takeMapPick()

    /** A saved place a tap on the favorites tile chose, once, or null. */
    fun takeFavoritePick(): Favorite? = dashboard?.takeFavoritePick()

    /** P11.5: Go (or Add stop) on the POI card since the last call, once, or null. */
    fun takePoiAction(): PoiAction? = dashboard?.takePoiAction()

    /** P11.5: the POI buttons pressed since the last call, with the state each left, once. */
    fun takePoiToggles(): List<Pair<PoiKind, Boolean>> = dashboard?.takePoiToggles() ?: emptyList()

    /** P11.5: lights exactly these POI buttons (the lab's LAB_POI); not a press. */
    fun poiKinds(kinds: Set<PoiKind>) {
        dashboard?.poiKinds(kinds)
    }

    /** The live alternatives' bubbles, from the main thread. */
    fun alternatives(marks: List<AlternativeMark>) {
        dashboard?.alternatives(marks)
    }

    /** The live alternative whose bubble was tapped since the last call, once, or null. */
    fun takeAlternativePick(): Long? = dashboard?.takeAlternativePick()

    /** Whether End route was pressed on the trip panel since the last call (P7.4). */
    fun takeEndRoute(): Boolean = dashboard?.takeEndRoute() ?: false

    /** The voice toggle's new state if it was pressed since the last call, once; else null (P8.2). */
    fun takeVoiceToggle(): Boolean? = dashboard?.takeVoiceToggle()

    /** Voice guidance turned on or off from the phone, for the toggle's picture (P8.3). */
    fun voiceGuidance(on: Boolean) {
        dashboard?.voiceGuidance(on)
    }

    /** Whether speech can happen now, for the voice toggle's warning mark (P8.2). */
    fun speechAvailable(available: Boolean) {
        dashboard?.speechAvailable(available)
    }

    /** The player a Resume row chose, once, or null (P7.5). */
    fun takeResumePick(): Player? = dashboard?.takeResumePick()

    /** How the Resume for [key] ended, for its row's feedback (P7.5). */
    fun resumeResult(key: String, result: ResumeResult) {
        dashboard?.resumeResult(key, result)
    }

    /** The notifications the flex tile lists (P7.8), newest first; replaces the list whole. */
    fun notifications(list: List<DashNotification>) {
        dashboard?.notifications(list)
    }

    /** A notification row tapped to be read aloud, once, or null (P7.8). */
    fun takeReadAloud(): DashNotification? = dashboard?.takeReadAloud()

    /** The notification being read aloud, whose row stays lit, or null (P8.1). */
    fun reading(key: String?) {
        dashboard?.reading(key)
    }

    /** The call the flex tile's card draws (P7.9). */
    fun call(state: CallState) {
        dashboard?.call(state)
    }

    /** P10: the weather the header and the flex tile draw, from [Weather]; replaces it whole. */
    fun weather(state: WeatherState) {
        dashboard?.weather(state)
    }

    /** P10: Show weather and Alerts shown from, changed mid-session. */
    fun weatherChoices(on: Boolean, level: AlertLevel) {
        dashboard?.weatherChoices(on, level)
    }

    /** Where the vehicle, else the phone, is: the location weather is chosen by (P10 plan §2). */
    fun followPosition(): Pair<Double, Double>? = dashboard?.followPosition()

    /** The call card's and keypad's presses since the last call, in order, once (P7.9). */
    fun takeCallActions(): List<CallAction> = dashboard?.takeCallActions() ?: emptyList()

    /** Whether notification access is held, for the idle media pane's hint (P7.5). */
    fun mediaAccess(access: Boolean) {
        dashboard?.mediaAccess(access)
    }

    /** The saved places and the recent one the favorites tile lists; replaces the list whole. */
    fun favorites(list: List<Favorite>, recent: Favorite?) {
        dashboard?.favorites(list, recent)
    }

    /** What the turn card and trip panel say about the route (P7.4); null for no route. */
    fun upcomingTurn(turn: UpcomingTurn?) {
        dashboard?.upcomingTurn(turn)
    }

    /** The vehicle's own fix while a dashboard projects, or null. */
    fun vehiclePosition(): Pair<Double, Double>? = dashboard?.vehiclePosition()

    /** The speed spoken prompts are timed by while a dashboard projects, or null (P8.2). */
    fun guidanceSpeed(): Double? = dashboard?.guidanceSpeed()

    fun key(keycode: Int, down: Boolean) {
        scene?.key(keycode, down)
    }

    fun transport(action: String) {
        scene?.transport(action)
        dashboard?.transport(action)
    }

    fun nowPlaying(
        access: Boolean,
        state: String,
        source: String,
        title: String,
        artist: String,
        art: android.graphics.Bitmap? = null,
    ) {
        scene?.nowPlaying(access, state, source, title, artist)
        // The bitmap goes to the renderer, which owns drawing; the dashboard gets only
        // an identity for it, so its state stays free of Android types.
        dashboardRenderer?.setAlbumArt(art)
        dashboard?.nowPlaying(
            access, state, source, title, artist,
            art?.let { "$title|$artist|${it.width}x${it.height}|${it.generationId}" },
        )
    }

    fun restriction(drivingStatus: String, night: String) {
        scene?.restriction(drivingStatus, night)
        dashboard?.restriction(drivingStatus, night)
    }

    /** Latest vehicle position and motion from the sensor channel (P5.5). */
    fun vehicle(reading: VehicleReading) {
        dashboard?.vehicle(reading)
    }

    /** Latest phone battery, network and position fallback. */
    fun phone(snapshot: PhoneSnapshot) {
        dashboard?.phone(snapshot)
    }

    /** The player apps the idle media pane lists (P7.5). */
    fun players(list: List<Player>) {
        dashboard?.players(list)
    }

    /** Stops rendering and releases the encoder, EGL, and the renderer thread. */
    fun release() {
        running = false
        val handler = handler
        if (handler != null) {
            val done = java.util.concurrent.CountDownLatch(1)
            handler.post {
                releaseRenderer()
                done.countDown()
            }
            done.await(RELEASE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        this.handler = null
        thread.quitSafely()
        thread.join(RELEASE_WAIT_MS)
        encoder?.release()
        encoder = null
        // After the encoder released, so nothing is still appending.
        capture?.let {
            try {
                it.flush()
                it.close()
            } catch (e: java.io.IOException) {
                captureError = e.toString()
            }
        }
        capture = null
    }

    /** `key=value` lines for the session report (spec §10). */
    fun report(): String = buildString {
        appendLine("projection_video=${params.width}x${params.height}@$fps config=${params.config} " +
            "channel=${params.channel} display=${params.display} session=${params.session} " +
            "content=${params.contentX},${params.contentY}+${params.contentWidth}x${params.contentHeight} " +
            "touch=${params.touch} negotiated_fps=${params.fps} scene_area=$sceneArea " +
            "relayouts=$relayouts bitrate=$bitRate")
        appendLine(
            "projection_video_capture=${videoOut?.absolutePath ?: "-"} bytes=$capturedBytes " +
                "error=${captureError ?: "-"}",
        )
        appendLine("projection_encoder=${encoder?.description ?: "-"}")
        appendLine(
            "projection_frames_rendered=$rendered projection_submitted=$submitted " +
                "projection_accepted=${results.get(0)} projection_dropped_congested=${results.get(1)} " +
                "projection_dropped_awaiting_keyframe=${results.get(2)} " +
                "projection_dropped_not_streaming=${results.get(3)} projection_invalid=${results.get(4)} " +
                "projection_closed=${results.get(5)} projection_refused=${results.get(6)} " +
                "projection_keyframe_requests=$keyframeRequests projection_focused=$focused",
        )
        appendLine(
            "projection_rebuild_on_regain=$rebuildOnRegain projection_rebuilds=$rebuilds " +
                "projection_congestion_restarts=$congestionRestarts " +
                "projection_rebuild_error=${rebuildError ?: "-"}",
        )
        appendLine("projection_encoder_error=${failure ?: "-"}")
        appendLine("projection_scene=$sceneKind chrome_uploads=${dashboardRenderer?.chromeUploads ?: 0}")
        dashboardRenderer?.engine?.let { append(it.report()) }
        scene?.let { append(it.report()) }
        dashboard?.let { append(it.report()) }
        poiFeed?.let { append(it.report()) }
    }

    /**
     * Builds the renderer for the configured scene on the renderer thread. Returns null
     * on success or the failure reason, leaving nothing half-built either way.
     */
    private fun startRenderer(surface: android.view.Surface, handler: Handler): String? {
        val board = dashboard
        if (board != null) {
            val target = DashboardRenderer(params.width, params.height, mapFiles)
            val setup = target.start(surface, board.layout, board.isNight())
            if (setup != null) {
                target.release()
                return setup
            }
            // VTM's tile loaders post back to whichever thread drives the map, which
            // is this one.
            target.engine?.sceneHandler = handler
            board.mapStatus(target.engine?.failure ?: "ok")
            // VTM sizes its tiles from the canvas dpi, so the number the gestures scale
            // pixels by is only knowable once the engine has started.
            target.engine?.tileSize()?.let { board.mapTileSize(it) }
            dashboardRenderer = target
            return null
        }
        val target = SceneRenderer(params.width, params.height)
        val setup = target.start(surface)
        if (setup != null) {
            target.release()
            return setup
        }
        renderer = target
        return null
    }

    private fun releaseRenderer() {
        renderer?.release()
        renderer = null
        dashboardRenderer?.release()
        dashboardRenderer = null
        // A rebuilt renderer has a new map engine with no line on it, and none of the
        // alternatives or the offer's proposal either.
        shownRoute = null
        shownAlternatives = null
        shownOfferRoute = null
        shownPois = null
    }

    private fun layoutWidth(): Int = scene?.layout?.width ?: dashboard?.layout?.width ?: params.width

    private fun layoutHeight(): Int = scene?.layout?.height ?: dashboard?.layout?.height ?: params.height

    private companion object {
        /** Submit results 0..5 from the native contract, plus one slot for anything else. */
        const val SUBMIT_RESULTS = 7
        const val RELEASE_WAIT_MS = 2_000L
    }
}
