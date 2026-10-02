package org.openautoandroid.source

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns a started session: the connectedDevice foreground promotion and notification,
 * the accessory descriptor, the native handle, and, while projecting, the encoder and
 * scene renderer. All native calls run in order on one control thread; state changes
 * happen on the main thread. The native transport is the only reader and writer of the
 * descriptor; this service only keeps the original descriptor open and closes it after
 * the native session is released.
 */
/** What the head unit was last told is playing, as the projected scene also shows it. */
private data class NowPlaying(
    /** Whether notification access was held at the poll that produced this. */
    val access: Boolean,
    val state: String,
    val source: String,
    val title: String,
    val artist: String,
    /** App-owned, already scaled cover art, or null. Never sent on the wire. */
    val art: android.graphics.Bitmap? = null,
)

class ProjectionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val control: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "aap-native-control") }
    private var running: Running? = null
    private var detachRegistered = false
    /** The call phase last noted, so the event log gets a line per change, not per update. */
    private var lastCallPhase: CallPhase = CallPhase.NONE

    /** The notification's sentence as last posted, so it is re-posted only when it changes. */
    private var notifiedText: String? = null
    private val renderNotification: () -> Unit = { updateNotification() }

    /** Every change to the saved places reaches the dashboard being projected, if any. */
    private val favoritesChanged: (List<Favorite>) -> Unit = { list -> running?.pipeline?.favorites(list) }

    /** Main-thread record of the one session this service owns. */
    private class Running(val attempt: Attempt, val accessory: UsbAccessory?) {
        var handle = 0L
        var descriptor: ParcelFileDescriptor? = null
        /** Lab transport for the emulator run; never used with a head unit. */
        var labSocket: LocalSocket? = null
        var pipeline: ProjectionPipeline? = null
        /** Present only while the media integration is enabled for this attempt. */
        var media: MediaControl? = null
        /** Present between the audio start and stop events (P5.3). */
        var audio: AudioSource? = null
        /** Phone battery, network and position fallback for the dashboard (P5.5). */
        var phone: PhoneStatus? = null
        /** The idle media pane's player apps (P7.5), beside [phone] and for as long. */
        var players: MediaPlayers? = null
        /** The flex tile's feeds (P7.8, P7.9), registered with [phone] and removed with it. */
        var notificationsListener: ((List<DashNotification>) -> Unit)? = null
        var callListener: ((CallState) -> Unit)? = null
        /** P8.2: tells the dashboard once a second whether speech can happen, for the toggle. */
        var speechCheck: Runnable? = null
        /** P10: weather's report, kept when projection stopped and the feed was reset. */
        var weatherReport: String? = null
        /**
         * Latest vehicle position and motion, and the latest restriction.
         *
         * Held for the same reason as [nowPlaying]: the sensor channel opens and the
         * head unit answers it **before** the video start indication arrives, so the
         * first readings land while there is still no pipeline to give them to. The
         * native side only emits a vehicle event when a value *changes*, so a dropped
         * first reading is not resent — a stationary vehicle would leave the map
         * blank for the whole drive.
         */
        var vehicle: VehicleReading? = null
        var restriction: Pair<String, String>? = null
        /**
         * Latest published now-playing. Media control starts with the session but the
         * encoder only at video start, so without this the scene would show nothing until
         * the next poll after projection began.
         */
        var nowPlaying: NowPlaying? = null
        var nativeFinished = false
        var stopRequested = false
        var shutdownRequested = false
        var releaseScheduled = false
        var finalReport: Map<String, String> = emptyMap()
    }

    private val detachReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_ACCESSORY_DETACHED) return
                val detached = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                val session = running ?: return
                val accessory = session.accessory ?: return
                if (detached == null || Accessories.same(detached, accessory)) requestStop(session, "accessory detached")
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getLongExtra(EXTRA_GENERATION, 0L))
            ACTION_STOP -> running?.let { requestStop(it, "stop requested") } ?: stopIfIdle()
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun start(generation: Long) {
        val attempt = SessionController.attempt
        // The lab transport has no connected device, and the platform refuses a
        // connectedDevice foreground service without one, so that debug-only path runs as
        // an ordinary started service while its activity is in the foreground.
        val foreground = attempt?.settings?.labSocket == null
        // Promote first: startForegroundService obliges it even for a stale request.
        if (foreground) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } catch (e: RuntimeException) {
                // ForegroundServiceStartNotAllowedException, SecurityException
                // (connectedDevice prerequisites), or invalid type exceptions.
                if (attempt != null && attempt.generation == generation) {
                    SessionController.finish(attempt, "FGS_START_FAILED", e.toString())
                }
                stopIfIdle()
                return
            }
        }
        if (attempt == null || attempt.generation != generation || !SessionController.isCurrent(attempt) ||
            SessionController.stage != Stage.PERMISSION || running != null
        ) {
            SessionController.note("ignored stale service start for generation $generation")
            stopIfIdle()
            return
        }
        if (foreground) {
            SessionController.completed(attempt, "foreground")
        } else {
            SessionController.note("lab transport: no foreground promotion (no connected device)")
        }
        val usb = getSystemService(UsbManager::class.java)
        val lab = attempt.settings.labSocket
        val accessory = if (lab == null) Accessories.current(usb, attempt.accessory) else null
        if (lab == null && accessory == null) {
            SessionController.finish(attempt, "NO_ACCESSORY", "accessory no longer enumerated before open")
            return stopIfIdle()
        }
        val session = Running(attempt, accessory)
        running = session
        if (!detachRegistered) {
            registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), RECEIVER_NOT_EXPORTED)
            detachRegistered = true
        }
        SessionController.addListener(renderNotification)
        Favorites.addListener(favoritesChanged)
        val callback = SessionCallback { eventGeneration, kind, report ->
            // Native session thread: post only. Stale generations are dropped on main.
            main.post { onNativeEvent(session, eventGeneration, kind, report) }
        }
        control.execute {
            val result = openAndStart(usb, attempt, accessory, callback)
            main.post { onStarted(session, result) }
        }
    }

    private sealed interface StartResult {
        data class Started(
            val descriptor: ParcelFileDescriptor,
            val handle: Long,
            val labSocket: LocalSocket?,
        ) : StartResult

        data class Failed(val category: String, val detail: String) : StartResult
    }

    /**
     * Control thread. Opens the accessory (or, in a debug build asked to use the lab
     * transport, an abstract local socket) and transfers exactly one duplicate to native.
     */
    private fun openAndStart(
        usb: UsbManager,
        attempt: Attempt,
        accessory: UsbAccessory?,
        callback: SessionCallback,
    ): StartResult {
        NativeBridge.loadError?.let { return StartResult.Failed("NATIVE_START_FAILED", "library not loaded: $it") }
        val lab = attempt.settings.labSocket
        var labSocket: LocalSocket? = null
        val descriptor: ParcelFileDescriptor
        if (lab != null) {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(lab, LocalSocketAddress.Namespace.ABSTRACT))
                descriptor = ParcelFileDescriptor.dup(socket.fileDescriptor)
            } catch (e: IOException) {
                socket.close()
                return StartResult.Failed("USB_OPEN_FAILED", "lab socket $lab failed: $e")
            }
            labSocket = socket
        } else {
            if (accessory == null) {
                return StartResult.Failed("NO_ACCESSORY", "no accessory at open")
            }
            if (!usb.hasPermission(accessory)) {
                return StartResult.Failed("USB_PERMISSION_DENIED", "permission no longer held at open")
            }
            descriptor =
                try {
                    usb.openAccessory(accessory)
                } catch (e: RuntimeException) {
                    return StartResult.Failed("USB_OPEN_FAILED", "openAccessory threw $e")
                } ?: return StartResult.Failed("USB_OPEN_FAILED", "openAccessory returned null")
        }
        val duplicate =
            try {
                descriptor.dup()
            } catch (e: IOException) {
                descriptor.close()
                labSocket?.close()
                return StartResult.Failed("USB_OPEN_FAILED", "descriptor dup failed: $e")
            }
        val settings = attempt.settings
        val config = NativeBridge.config(
            attempt.generation,
            attempt.sessionUuid,
            SessionSettings(
                profile = settings.profile,
                stopAfter = settings.stopAfter,
                tlsProfile = settings.tlsProfile,
                identityDir = IdentityWork.directory(this).absolutePath,
                firstUse = settings.armedFirstUse,
                pins = settings.pins,
                captureDiscovery = settings.captureDiscovery,
                captureUnhandled = settings.captureUnhandled,
                openAllChannels = settings.openAllChannels,
                requestFocus = settings.requestFocus,
                setupField = settings.setupField,
                keepalive = settings.keepalive,
                projectionMs = settings.projectionMs,
                sensors = settings.sensors,
                sensorLocation = settings.sensorLocation,
                mediaInfo = settings.mediaInfo,
                audio = settings.audio,
                navigation = settings.navigation,
                navigationUnits = settings.navigationUnits,
                navigationForce = settings.navigationForce,
                navigationScript = settings.navigationScript,
                videoPreference = settings.videoPreference,
                encoderFps = settings.encoderFps,
            ),
        )
        // Ownership of the detached number passes to native on this call.
        val handle = NativeBridge.start(duplicate.detachFd(), config, callback)
        if (handle <= 0) {
            descriptor.close()
            labSocket?.close()
            return StartResult.Failed("NATIVE_START_FAILED", NativeBridge.startResultName(handle))
        }
        return StartResult.Started(descriptor, handle, labSocket)
    }

    private fun onStarted(session: Running, result: StartResult) {
        when (result) {
            is StartResult.Failed -> {
                running = null
                SessionController.finish(session.attempt, result.category, result.detail)
                stopIfIdle()
            }
            is StartResult.Started -> {
                session.handle = result.handle
                session.descriptor = result.descriptor
                session.labSocket = result.labSocket
                // Now-playing is independent of video: it starts with the session, not
                // with the encoder, and keeps publishing while the head unit shows its
                // own screen. Publications before the channel opens are simply held.
                if (session.attempt.settings.mediaInfo) {
                    val media = MediaControl(
                        this,
                        result.handle,
                        { SessionController.note(it) },
                    ) { access, state, source, title, artist, art ->
                        // Media thread: the pipeline and the scene are owned on main.
                        main.post {
                            if (running === session) {
                                session.nowPlaying = NowPlaying(access, state, source, title, artist, art)
                                session.pipeline?.nowPlaying(access, state, source, title, artist, art)
                            }
                        }
                    }
                    session.media = media
                    media.start()
                    SessionController.note(
                        if (MediaAccessService.enabled(this)) {
                            getString(R.string.media_access_granted)
                        } else {
                            getString(R.string.media_access_none)
                        },
                    )
                }
                if (SessionController.isCurrent(session.attempt) && SessionController.stage == Stage.PERMISSION) {
                    SessionController.completed(session.attempt, "descriptor")
                    SessionController.move(Stage.VERSION, "native session ${result.handle} started")
                    SessionController.syncNativeStage(session.attempt)
                }
                releaseIfDone(session)
            }
        }
    }

    private fun onNativeEvent(session: Running, generation: Long, kind: Int, report: ByteArray) {
        if (running !== session || generation != session.attempt.generation) return
        when (kind) {
            NativeBridge.EVENT_PROGRESS, NativeBridge.EVENT_FINISHED -> {
                SessionController.nativeProgress(session.attempt, report)
                if (kind == NativeBridge.EVENT_FINISHED) {
                    session.nativeFinished = true
                    session.finalReport = parseReport(report)
                    releaseIfDone(session)
                }
            }
            else -> projectionEvent(session, kind, parseReport(report))
        }
    }

    /** Main thread: projection events from the native session drive the app's encoder. */
    private fun projectionEvent(session: Running, kind: Int, fields: Map<String, String>) {
        when (kind) {
            NativeBridge.EVENT_VIDEO_START -> startProjection(session, fields)
            NativeBridge.EVENT_VIDEO_GEOMETRY -> {
                val pipeline = session.pipeline
                val updated = VideoStartParams.parse(fields)
                if (pipeline == null || updated == null) {
                    SessionController.note("video geometry event before the encoder started; ignored")
                } else {
                    pipeline.relayout(updated)
                    SessionController.note(
                        "content rectangle moved to ${updated.contentX},${updated.contentY}" +
                            "+${updated.contentWidth}x${updated.contentHeight}",
                    )
                }
            }
            NativeBridge.EVENT_VIDEO_STOP -> {
                SessionController.note("video stopped: ${fields["reason"] ?: "-"}")
                stopProjection(session)
            }
            NativeBridge.EVENT_KEYFRAME_REQUEST -> session.pipeline?.requestKeyframe()
            NativeBridge.EVENT_FOCUS -> {
                val projected = fields["projected"] == "true"
                SessionController.note("video focus projected=$projected mode=${fields["mode"]}")
                session.pipeline?.setFocused(projected)
            }
            NativeBridge.EVENT_TOUCH -> {
                val pipeline = session.pipeline ?: return
                val action = fields["action"] ?: return
                // Every pointer the event carried, not just the first: the map pane's
                // pinch and twist are the difference between one pointer and two, and
                // the native side has validated and mapped them all already.
                val pointers = TouchPoint.parse(fields["pointers"])
                if (pointers.isEmpty()) return
                // An on-screen media button is dispatched exactly as a factory
                // transport button is, through the same permission-free path (P5.1).
                val pressed = pipeline.touch(action, pointers)
                if (pressed != null) {
                    session.media?.transport(pressed)
                    pipeline.transport(pressed)
                    SessionController.note("dashboard media button $pressed")
                }
                // P6.4: a long press on the map is a destination, routed like a share.
                // What the phone would have said goes nowhere: the driver is looking at
                // the head unit, and the route line appearing is the answer.
                pipeline.takeMapPick()?.let { (lat, lon) ->
                    Navigation.set(this, Destination(lat, lon), DestinationSource.MAP)
                }
                // A tap on the favorites tile is a destination too, with the place's
                // own name as its label; the note says only that one was tapped.
                pipeline.takeFavoritePick()?.let { favorite ->
                    SessionController.note("dashboard favorite tapped")
                    Navigation.set(this, Destination(favorite.latitude, favorite.longitude, favorite.name), DestinationSource.FAVORITE)
                }
                // P7.4: End route on the trip panel is the phone's Clear route, pressed
                // from the car: the route line, the card and the panel go, and the cluster
                // is told INACTIVE through Guide.clear(). The note names no destination.
                // A live alternative's bubble: follow that route instead, as the phone's
                // preview would. The note says how much longer it was, never where.
                pipeline.takeAlternativePick()?.let { LiveAlternatives.choose(this, it) }
                if (pipeline.takeEndRoute()) {
                    SessionController.note("dashboard end route pressed")
                    Navigation.clear()
                }
                // P8.2: the voice toggle beside End route. Saved at once like day/night, so
                // the next route and the next session start as it was left; off stops a
                // prompt being spoken, on says the current step's prompt as confirmation.
                pipeline.takeVoiceToggle()?.let { on ->
                    AppSettings.update(this) { it.withVoiceGuidance(on) }
                    Guide.voiceToggled(on)
                    pipeline.speechAvailable(Speaker.canSpeak())
                    // Noted last (P8.3): the note redraws the phone's screen, whose Speak turn
                    // prompts switch then shows the saved value to a Speaker already told, so
                    // the phone passes nothing back. On, the confirming prompt is noted first.
                    SessionController.note("dashboard voice guidance ${if (on) "on" else "off"}")
                }
                // P7.8, P8.1: a notification row tapped to be read aloud, or stopped by a
                // second tap. Speaker notes what it did, never which one or what it said.
                pipeline.takeReadAloud()?.let { notification -> Speaker.read(notification) }
                // P7.9: the call card and the keypad, in the order they were pressed (a
                // keypad tap is a tone start, then its stop). Every control goes through
                // CallControl from here, on main, never from the render thread; a key's
                // note is null, so no digit reaches the event log.
                for (callAction in pipeline.takeCallActions()) {
                    callAction.note?.let { SessionController.note(it) }
                    when (callAction.kind) {
                        CallActionKind.ANSWER -> CallControl.answer()
                        CallActionKind.DECLINE -> CallControl.decline()
                        CallActionKind.HANG_UP -> CallControl.hangUp()
                        CallActionKind.MUTE -> CallControl.setMuted(true)
                        CallActionKind.UNMUTE -> CallControl.setMuted(false)
                        CallActionKind.KEY_DOWN -> CallControl.dtmfDown(callAction.key)
                        CallActionKind.KEY_UP -> CallControl.dtmfUp()
                    }
                }
                // P7.5: a Resume row. The answer comes back on aap-players and is posted,
                // like everything else the pipeline hears, to main; which app it was is
                // never noted (Player's KDoc).
                pipeline.takeResumePick()?.let { player ->
                    val players = session.players
                    if (players == null) {
                        pipeline.resumeResult(player.key, ResumeResult.NOT_FOUND)
                    } else {
                        players.resume(player) { result ->
                            main.post { if (running === session) session.pipeline?.resumeResult(player.key, result) }
                        }
                    }
                }
            }
            NativeBridge.EVENT_KEY -> {
                val pipeline = session.pipeline ?: return
                val keycode = fields["keycode"]?.toIntOrNull() ?: return
                pipeline.key(keycode, fields["down"] == "true")
            }
            NativeBridge.EVENT_VEHICLE -> {
                val reading = VehicleReading.parse(fields)
                session.vehicle = reading
                session.pipeline?.vehicle(reading)
                Navigation.onVehicleFix(this)
                // P6.5: every vehicle fix moves guidance along the route, if there is one.
                // P8.2: with the speed its prompts are timed by, read at the scale the drive
                // supports when a dashboard is keeping that evidence.
                reading.position()?.let { (lat, lon) ->
                    Guide.onFix(this, lat, lon, "vehicle", session.pipeline?.guidanceSpeed() ?: reading.speed())
                }
            }
            NativeBridge.EVENT_RESTRICTION -> {
                val restriction = (fields["driving_status"] ?: "-") to (fields["night"] ?: "-")
                session.restriction = restriction
                session.pipeline?.restriction(restriction.first, restriction.second)
            }
            // Audio focus is granted and the audio start indication is out, so the head
            // unit's mixer already has a route open for what this produces (P5.3).
            NativeBridge.EVENT_AUDIO_START -> startAudio(session, fields)
            NativeBridge.EVENT_AUDIO_STOP -> {
                SessionController.note("audio stopped: ${fields["reason"] ?: "-"}")
                stopAudio(session)
            }
            NativeBridge.EVENT_TRANSPORT -> {
                val action = fields["action"] ?: return
                // The button goes to whatever is playing; the scene only records it, so
                // the projected picture shows that the press arrived.
                session.media?.transport(action)
                session.pipeline?.transport(action)
                SessionController.note("transport button $action")
            }
        }
    }

    private fun startProjection(session: Running, fields: Map<String, String>) {
        if (session.pipeline != null) return
        val params = VideoStartParams.parse(fields)
        if (params == null) {
            SessionController.note("video start event was incomplete; no encoder started")
            return
        }
        // P7.2, P7.3, P7.6: the dashboard's display choices are the driver's, so they are
        // read from the saved settings even on a lab attempt, whose AttemptSettings come
        // from its extras. Nothing here reaches the wire.
        val saved = AppSettings.load(this)
        val dashboardScene = session.attempt.settings.scene == SessionSettings.SCENE_DASHBOARD
        val pipeline = ProjectionPipeline(
            session.handle, params, session.attempt.sessionUuid, session.attempt.settings.softwareEncoder,
            session.attempt.settings.encoderFps, session.attempt.settings.sceneArea,
            if (session.attempt.settings.captureVideo) {
                java.io.File(filesDir, "video/session-${session.attempt.generation}.h264")
            } else {
                null
            },
            session.attempt.settings.keyframeSeconds,
            session.attempt.settings.rebuildOnRegain,
            session.attempt.settings.scene,
            session.attempt.settings.mapOnLeft,
            session.attempt.settings.speedUnit,
            MapPreferences.orientation(this),
            { MapPreferences.saveOrientation(this, it) },
            DashboardChoices(
                markerColour = saved.markerColour,
                dayNight = saved.dayNight,
                diagnostics = saved.diagnostics,
                notificationsPaused = saved.notificationsPaused,
                rememberedNight = AppSettings.lastNight(this),
                voiceGuidance = saved.voice.enabled,
                weather = saved.weather.enabled,
                alertLevel = saved.weather.alertLevel,
                clockFormat = saved.clockFormat,
            ),
            dashboardSaves(),
            MapData.resolveAll(this, session.attempt.settings.mapFile),
            java.time.ZoneId.systemDefault(),
        ) { detail ->
            // Encoder thread: tell the session, which ends with ENCODER_FAILED.
            NativeBridge.videoFailed(session.handle, detail)
        }
        // What the sensor channel already said about night, before the first frame: it
        // opens before the video start indication, so NIGHT_DATA has normally arrived by
        // now, and handing it over after start() themed the map from the fallback and
        // then switched it, reloading every tile, a moment later (P7.6).
        session.restriction?.let { pipeline.restriction(it.first, it.second) }
        val failure = pipeline.start()
        session.pipeline = pipeline
        // Where a route starts while this projects: the same fix the map follows.
        Navigation.vehicleFix = { pipeline.vehiclePosition() }
        // Street names come from the maps being drawn, and the header shows the next turn.
        Routing.nameMaps = MapData.resolveAll(this, session.attempt.settings.mapFile)
        Guide.turnListener = { pipeline.upcomingTurn(it) }
        // The live alternatives' bubbles, starting with any already found.
        LiveAlternatives.listener = { list ->
            pipeline.alternatives(list.map { AlternativeMark(it.id, it.labelLat, it.labelLon, it.label) })
        }
        LiveAlternatives.listener?.invoke(LiveAlternatives.shown)
        // P9.3, P9.4, P9.6: live traffic's colours, delay and offer, starting with what the
        // loop already holds, and Take or Keep on the divided turn card back to it. Both
        // on main: the loop publishes there, and touch() calls the listener there.
        Traffic.listener = { display -> pipeline.traffic(display) }
        pipeline.traffic(Traffic.display())
        pipeline.offerListener = { id, take -> Traffic.answer(id, take) }
        // P8.3: the phone's Voice guidance switch, shown on the trip panel's toggle.
        Guide.voiceListener = { on ->
            SessionController.note("phone voice guidance ${if (on) "on" else "off"}")
            pipeline.voiceGuidance(on)
            pipeline.speechAvailable(Speaker.canSpeak())
        }
        // The saved places, so the first projected frame lists them.
        pipeline.favorites(Favorites.list(this))
        // The cluster hears what guidance says from here on, starting with what it
        // already knows (P6.5).
        Guide.attach(session.handle)
        // Whatever was last published, so the first projected frame already shows it.
        session.nowPlaying?.let {
            pipeline.nowPlaying(it.access, it.state, it.source, it.title, it.artist, it.art)
        }
        // The same for anything the sensor channel already reported: it opens before
        // the video start indication, so these normally arrive before this pipeline
        // exists and are never resent. (The restriction went in before start().)
        session.vehicle?.let { pipeline.vehicle(it) }
        // A destination set before projecting was waiting for exactly this fix, and a
        // parked vehicle sends no second one to wake it.
        Navigation.onVehicleFix(this)
        // The phone's own contribution to the header. Started with the encoder rather
        // than with the session because nothing draws it until there is a scene, and
        // stopped in stopProjection for the same reason.
        if (dashboardScene) {
            val phone = PhoneStatus(applicationContext) { snapshot ->
                session.pipeline?.phone(snapshot)
                // The phone guides only while the vehicle has no fix of its own, the same
                // order the dashboard follows for the map.
                if (session.vehicle?.position() == null) {
                    val lat = snapshot.latitude
                    val lon = snapshot.longitude
                    if (lat != null && lon != null) {
                        Guide.onFix(this, lat, lon, "phone", session.pipeline?.guidanceSpeed() ?: snapshot.speedMetresPerSecond)
                    }
                }
            }
            session.phone = phone
            phone.start()
            // P7.5's player list for the idle media pane, running with the dashboard.
            val players = MediaPlayers(applicationContext) { list ->
                // aap-players thread: the pipeline is owned on main.
                main.post { if (running === session) session.pipeline?.players(list) }
            }
            session.players = players
            players.start()
            // Whether the idle pane should say the phone could list more (P7.5). Media
            // info, when it runs, updates this with every poll.
            pipeline.mediaAccess(MediaAccessService.enabled(this))
            // P7.8, P7.9: the flex tile follows the feed and the call. Registered before
            // the feed is turned on, so the first read of the shade reaches the tile. Both
            // may call from main or from whichever thread changed a flag, so both post.
            val notificationsListener: (List<DashNotification>) -> Unit = { list ->
                main.post { if (running === session) session.pipeline?.notifications(list) }
            }
            val callListener: (CallState) -> Unit = { state ->
                main.post {
                    if (running === session) {
                        // The phase only, for the event log and the lab: never the caller.
                        if (state.phase != lastCallPhase) {
                            lastCallPhase = state.phase
                            SessionController.note("call ${state.phase.name.lowercase()} (${state.source.name.lowercase()})")
                        }
                        session.pipeline?.call(state)
                    }
                }
            }
            lastCallPhase = CallPhase.NONE
            session.notificationsListener = notificationsListener
            session.callListener = callListener
            NotificationFeed.addListener(notificationsListener)
            CallControl.addListener(callListener)
            pipeline.notifications(NotificationFeed.items())
            pipeline.call(CallControl.state)
            // P7.8: the listener reads notifications only while this is on, and only for
            // the length of the projection; stopProjection turns it off again.
            NotificationFeed.exclusions = saved.notificationExclusions
            NotificationFeed.paused = saved.notificationsPaused
            NotificationFeed.enabled = saved.notificationsOnDashboard
            // P8.1: the speech engine, bound for the length of the projection (plan §3)
            // and released in stopProjection. The row being read stays lit.
            Speaker.readingListener = { key -> if (running === session) session.pipeline?.reading(key) }
            Speaker.bind(this, saved)
            // P8.2: turn prompts, with the saved voice settings (the toggle is Speaker's,
            // set by bind) in the header's speed unit; and the toggle's warning mark kept
            // current, since the engine readies and Bluetooth comes and goes on their own.
            Guide.voiceSettings(saved.voice, session.attempt.settings.speedUnit)
            val speechCheck = object : Runnable {
                override fun run() {
                    if (running !== session) return
                    session.pipeline?.speechAvailable(Speaker.canSpeak())
                    main.postDelayed(this, SPEECH_CHECK_MS)
                }
            }
            session.speechCheck = speechCheck
            speechCheck.run()
            // P10: weather from Breezy (docs/p10-plan.md §3), read now, on its notifier and
            // every 15 min, only while this projects and only with Show weather on. After
            // Speaker.bind, since an alert in the first read may be spoken.
            Weather.listener = { state -> if (running === session) session.pipeline?.weather(state) }
            Weather.choicesListener = { settings ->
                if (running === session) session.pipeline?.weatherChoices(settings.enabled, settings.alertLevel)
            }
            session.weatherReport = null
            Weather.start(this, saved.weather) { session.pipeline?.followPosition() }
            // P7.9: the in-call component is enabled only while projecting, and only with
            // the setting on (plan §5.8); releaseIfDone turns it off at the session's end.
            if (saved.callControls) {
                val route = CallControl.setEnabled(this, true)
                SessionController.note("call controls on: ${route.name.lowercase()}")
            }
        }
        SessionController.projectionPipelineReport(projectionReport(session, pipeline))
        // Debug builds: the lab's `lab_report` extra brings the dashboard's and the map's part
        // of the saved report up to date mid-projection (P9 lab); otherwise it is taken only at
        // the start and the end. The note after it writes the report out.
        labReport = {
            main.post {
                val live = session.pipeline
                if (running === session && live != null) {
                    SessionController.projectionPipelineReport(projectionReport(session, live))
                    SessionController.note("lab report refreshed (debug build)")
                }
            }
        }
        if (failure != null) {
            SessionController.note("encoder unavailable: $failure")
            NativeBridge.videoFailed(session.handle, failure)
            return
        }
        SessionController.note(
            "projecting ${params.width}x${params.height}@${pipeline.fps} token=${pipeline.scene?.token ?: pipeline.dashboard?.token} " +
                "content=${params.contentX},${params.contentY}+${params.contentWidth}x${params.contentHeight}",
        )
    }

    private fun startAudio(session: Running, fields: Map<String, String>) {
        if (session.audio != null) return
        val params = AudioStartParams.parse(fields)
        if (params == null) {
            SessionController.note("audio start event was incomplete; no audio started")
            return
        }
        val source = AudioSource(session.handle, params) { detail ->
            // Audio thread: tell the session, which stops audio and keeps projecting.
            NativeBridge.audioFailed(session.handle, detail)
        }
        session.audio = source
        source.start()
        SessionController.note(
            "audio ${params.sampleRate}/${params.bitDepth}/${params.channels} on channel " +
                "${params.channel}, ${params.bufferBytes} bytes a buffer",
        )
    }

    private fun stopAudio(session: Running) {
        val source = session.audio ?: return
        session.audio = null
        // Keep the app-side evidence before the source is torn down.
        SessionController.projectionAudioReport(source.report())
        control.execute { source.release() }
    }

    private fun stopProjection(session: Running) {
        // P10: weather's counts into the report before its reading is dropped.
        session.weatherReport = Weather.report()
        Weather.listener = null
        Weather.choicesListener = null
        Weather.stop()
        // Speech first, so its report counts what projection's end cut off and shows the
        // focus grant given back (P8.1, plan §4). Its counts outlive the release.
        Speaker.readingListener = null
        Speaker.release()
        session.speechCheck?.let(main::removeCallbacks)
        session.speechCheck = null
        // The phone-side sources' evidence, kept before they are released.
        val backends = backendsReport(session)
        session.phone?.release()
        session.phone = null
        session.players?.let { players -> control.execute { players.release() } }
        session.players = null
        // Between drives the listener reads nothing (plan §5.8).
        NotificationFeed.enabled = false
        session.notificationsListener?.let(NotificationFeed::removeListener)
        session.notificationsListener = null
        session.callListener?.let(CallControl::removeListener)
        session.callListener = null
        stopAudio(session)
        val pipeline = session.pipeline ?: return
        session.pipeline = null
        Navigation.vehicleFix = null
        Routing.nameMaps = null
        Guide.turnListener = null
        LiveAlternatives.listener = null
        Traffic.listener = null
        labReport = null
        pipeline.offerListener = null
        Guide.voiceListener = null
        Guide.detach(session.handle)
        // Keep the app-side evidence before the pipeline is torn down.
        SessionController.projectionPipelineReport(pipeline.report() + backends)
        control.execute { pipeline.release() }
    }

    /** The pipeline's section of the report, then the phone-side sources'. */
    private fun projectionReport(session: Running, pipeline: ProjectionPipeline): String =
        pipeline.report() + backendsReport(session)

    /**
     * `key=value` lines from the phone-side sources the dashboard draws from: signal and
     * battery, the player list, the notification feed, speech (P8.1) and call controls.
     * Counts and levels only; each source's own report keeps names, text and numbers out
     * (spec §10). Speech names the engine's package, as plan §9 asks, and nothing said.
     */
    private fun backendsReport(session: Running): String = buildString {
        session.phone?.let { append(it.report()) }
        session.players?.let { append(it.report()) }
        append(NotificationFeed.report())
        append(Speaker.report())
        append(CallControl.report())
        // P10: counts only; the report taken as it stopped, once it has.
        append(session.weatherReport ?: Weather.report())
    }

    /**
     * Where the dashboard's own choices go (P7.3, P7.6): ☀/☾ and Ⓟ into the saved
     * settings, one field each, and the palette in use for the next session's first
     * frame. Posted, so the settings write happens after the dashboard's lock is released
     * and never under it; each is an in-memory read and an `apply()`, as the compass's
     * orientation save is.
     */
    private fun dashboardSaves(): DashboardSaves = DashboardSaves(
        dayNight = { mode -> main.post { AppSettings.update(this) { it.copy(dayNight = mode) } } },
        notificationsPaused = { paused ->
            main.post {
                NotificationFeed.paused = paused
                AppSettings.update(this) { it.copy(notificationsPaused = paused) }
            }
        },
        resolvedNight = { night -> main.post { AppSettings.saveLastNight(this, night) } },
    )

    /**
     * Stop from the notification, the activity, or a detach. After TLS, in the hold or a
     * projection stage, the session first stops the stream and asks the head unit to shut
     * down, so its own interface comes back; the hard release follows either the native
     * end or [SHUTDOWN_GRACE_MS].
     */
    private fun requestStop(session: Running, reason: String) {
        if (session.stopRequested) return
        val graceful = session.handle > 0 && !session.nativeFinished &&
            SessionController.stage in GRACEFUL_STAGES
        if (graceful && !session.shutdownRequested) {
            session.shutdownRequested = true
            SessionController.stopping(session.attempt, "$reason; asking the head unit to shut down")
            val handle = session.handle
            control.execute { NativeBridge.requestShutdown(handle) }
            main.postDelayed({ if (running === session) forceStop(session, "shutdown grace expired") }, SHUTDOWN_GRACE_MS)
            return
        }
        forceStop(session, reason)
    }

    private fun forceStop(session: Running, reason: String) {
        if (session.stopRequested) return
        session.stopRequested = true
        SessionController.stopping(session.attempt, reason)
        releaseIfDone(session)
    }

    /** Stops and releases the native session once it has started and is finished or stopping. */
    private fun releaseIfDone(session: Running) {
        if (session.handle <= 0 || session.releaseScheduled || !(session.nativeFinished || session.stopRequested)) return
        session.releaseScheduled = true
        SessionController.stopping(session.attempt, "releasing native session")
        stopProjection(session)
        // P7.9: the in-call component goes back to disabled with the session, so nothing
        // binds it between drives.
        if (CallControl.wanted) CallControl.setEnabled(this, false)
        session.media?.let { media ->
            session.media = null
            // Kept before the handle goes away, like the pipeline's evidence.
            SessionController.projectionMediaReport(media.report())
            control.execute { media.release() }
        }
        val handle = session.handle
        val descriptor = session.descriptor
        val labSocket = session.labSocket
        control.execute {
            // Joins the worker, which has delivered Finished before this returns.
            NativeBridge.stop(handle)
            descriptor?.close()
            labSocket?.close()
            main.post { onReleased(session) }
        }
    }

    private fun onReleased(session: Running) {
        if (running === session) running = null
        val report = session.finalReport
        // A lab-socket attempt never pins: its peer is the lab receiver, not a head unit.
        if (session.attempt.settings.armedFirstUse && session.attempt.settings.labSocket == null) {
            pinFirstUse(report)
        }
        val category = report["final_category"] ?: "CANCELLED"
        val detail = report["final_detail"] ?: "native session released without a final report"
        SessionController.finish(session.attempt, category, detail)
        stopIfIdle()
    }

    /**
     * Trial C2 → C3: an attempt armed for first use pins the head-unit certificate it
     * accepted, once the TLS handshake completed. Later attempts require that pin unless
     * first use is explicitly armed again.
     */
    private fun pinFirstUse(report: Map<String, String>) {
        val fingerprint = TrustStore.acceptedFirstUse(report)
        if (fingerprint == null) {
            SessionController.note("first-use trust was armed; no head-unit certificate was pinned")
            return
        }
        try {
            val added = IdentityWork.trust(this).addPin(fingerprint)
            SessionController.note("head-unit certificate $fingerprint ${if (added) "pinned" else "already pinned"}")
        } catch (e: IOException) {
            SessionController.note("pinning head-unit certificate failed: $e")
        }
    }

    private fun stopIfIdle() {
        if (running != null) return
        SessionController.removeListener(renderNotification)
        Favorites.removeListener(favoritesChanged)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        SessionController.removeListener(renderNotification)
        if (detachRegistered) unregisterReceiver(detachReceiver)
        running?.let { session ->
            // Destroyed with a live session (not the normal path): release in order.
            if (session.handle > 0 && !session.releaseScheduled) {
                val handle = session.handle
                val descriptor = session.descriptor
                val labSocket = session.labSocket
                val pipeline = session.pipeline
                val media = session.media
                val players = session.players
                session.phone?.release()
                Speaker.readingListener = null
                Speaker.release()
                Weather.listener = null
                Weather.choicesListener = null
                Weather.stop()
                NotificationFeed.enabled = false
                session.notificationsListener?.let(NotificationFeed::removeListener)
                session.callListener?.let(CallControl::removeListener)
                if (CallControl.wanted) CallControl.setEnabled(this, false)
                control.execute {
                    players?.release()
                    media?.release()
                    pipeline?.release()
                    NativeBridge.stop(handle)
                    descriptor?.close()
                    labSocket?.close()
                }
            }
            SessionController.finish(session.attempt, "CANCELLED", "service destroyed")
        }
        running = null
        control.shutdown()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ConnectionActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProjectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            // The Home card's sentence (P7 plan §6.2), not the stage's enum name. The
            // destination's label may appear: this notification is the operator's own.
            .setContentText(notificationText())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification), getString(R.string.stop), stop,
                ).build(),
            )
            .build()
    }

    private fun notificationText(): String =
        StatusText.notification(SessionController.stage, Navigation.destination?.let(Navigation::describe))

    private fun updateNotification() {
        // Keyed on the sentence rather than the stage, since a new destination changes it
        // too; every note (a route requested or cleared among them) brings this here.
        val text = notificationText()
        if (running == null || notifiedText == text) return
        notifiedText = text
        // Without POST_NOTIFICATIONS the session continues; Stop remains in the activity.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        /** Refreshes the projection's part of the saved report, while one projects; see [ConnectionActivity]'s `lab_report`. */
        @Volatile
        var labReport: (() -> Unit)? = null

        const val ACTION_START = "org.openautoandroid.source.action.START_SESSION"
        const val ACTION_STOP = "org.openautoandroid.source.action.STOP_SESSION"
        const val EXTRA_GENERATION = "org.openautoandroid.source.extra.GENERATION"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1

        /** How often the voice toggle's warning mark is brought up to date (P8.2). */
        private const val SPEECH_CHECK_MS = 1_000L

        /** How long a graceful stop may take before the session is released anyway. */
        private const val SHUTDOWN_GRACE_MS = 3_000L
        private val GRACEFUL_STAGES = setOf(Stage.READY, Stage.SETUP, Stage.PROJECTING)

        fun startIntent(context: Context, generation: Long): Intent =
            Intent(context, ProjectionService::class.java).setAction(ACTION_START).putExtra(EXTRA_GENERATION, generation)

        fun stopIntent(context: Context): Intent =
            Intent(context, ProjectionService::class.java).setAction(ACTION_STOP)
    }
}
