package com.jw6ventures.headlink

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.time.ZoneId
import java.util.Date

/**
 * User-initiated connection: profile, stop stage, TLS profile, and certificate-trial
 * controls, saved across launches in [AppSettings] (P7.1); Connect/Stop, and the
 * plug-in start that does what Connect does for an already-pinned head unit; accessory
 * enumeration and filter diagnostics; USB and notification permission; and report
 * export. The activity never touches the accessory descriptor; after permission it hands
 * the attempt to [ProjectionService]. Only small binder calls and file reads run on the
 * UI thread.
 *
 * **P7.7: one activity, five tabs.** Home (the status in words, one large
 * Connect/Stop, the route and the favorites), Setup (what is missing, each with the button
 * that fixes it), Maps & routing, Settings, and Diagnostics, shown only with the
 * Diagnostics switch on. Every control that existed before is still here: the protocol
 * and diagnostic ones, identity and pins, report export and the live report moved to
 * Diagnostics rather than going away. The tabs change where controls sit, never what
 * they do — the USB permission and attach paths, the plug-in start and its identity hold,
 * [loadSaved]'s one-field-per-write saving and the per-attempt options below are the
 * P7.1 code unchanged — and one [SessionController] listener still renders every tab.
 */
class ConnectionActivity : Activity() {
    private lateinit var usb: UsbManager
    private lateinit var filter: AccessoryFilter
    private val main = Handler(Looper.getMainLooper())
    private val render: () -> Unit = {
        renderState()
        leaveAfterAutoStart()
        retryAutoStart()
    }

    /** Generation of the attempt a plug-in started, until the activity has stepped back. */
    private var autoStarted: Long? = null

    /**
     * Set while a plug-in start is held for the source identity ([AutoStart.WAIT]); the
     * uptime by which it gives up. Only ever one: a second attach while held decides
     * again and keeps the first deadline.
     */
    private var autoStartWaitUntil: Long? = null

    private val autoStartGiveUp = Runnable {
        if (autoStartWaitUntil != null) {
            autoStartWaitUntil = null
            SessionController.note(
                "auto-start no: the source identity was not ready within ${AutoStart.WAIT_MS / 1000} s; gave up",
            )
        }
    }

    private lateinit var stageText: TextView
    private lateinit var notificationText: TextView
    private lateinit var accessoryText: TextView
    private lateinit var resultText: TextView
    private lateinit var savedText: TextView
    private lateinit var trustText: TextView
    private lateinit var identityStatusText: TextView
    private lateinit var reportText: TextView
    private lateinit var projectionText: TextView
    private lateinit var profileSpinner: Spinner
    private lateinit var stopAfterSpinner: Spinner
    private lateinit var tlsProfileSpinner: Spinner
    private lateinit var firstUseCheck: CheckBox
    private lateinit var captureDiscoveryCheck: CheckBox
    private lateinit var captureUnhandledCheck: CheckBox
    private lateinit var captureVideoCheck: CheckBox
    private lateinit var openAllChannelsCheck: CheckBox
    private lateinit var resumeInPlaceCheck: CheckBox
    private lateinit var congestionRestartCheck: CheckBox
    private lateinit var wirelessKeepAwakeCheck: CheckBox
    private lateinit var waitForFocusCheck: CheckBox
    private lateinit var keyframeSpinner: Spinner
    private lateinit var setupFieldSpinner: Spinner
    private lateinit var sensorsSpinner: Spinner
    private lateinit var sensorLocationCheck: CheckBox
    // P7.7: the Settings tab's choices are switches; the Diagnostics tab keeps checkboxes.
    private lateinit var mediaInfoCheck: CompoundButton
    private lateinit var audioCheck: CompoundButton
    private lateinit var navigationCheck: CompoundButton
    private lateinit var navigationUnitsSpinner: Spinner
    private lateinit var navigationForceCheck: CompoundButton
    private lateinit var navigationScriptCheck: CompoundButton
    private lateinit var mediaAccessText: TextView
    private lateinit var mediaAccessButton: Button
    private lateinit var softwareEncoderCheck: CheckBox
    private lateinit var videoPreferenceSpinner: Spinner
    private lateinit var encoderFpsSpinner: Spinner
    private lateinit var sceneAreaSpinner: Spinner
    private lateinit var sceneSpinner: Spinner
    private lateinit var mapSideSpinner: Spinner
    private lateinit var speedUnitSpinner: Spinner
    private lateinit var autoStartCheck: CompoundButton
    private lateinit var diagnosticsCheck: CompoundButton
    private lateinit var markerColourSpinner: Spinner
    private lateinit var clockFormatSpinner: Spinner
    private lateinit var dayNightSpinner: Spinner
    private lateinit var notificationsOnDashboardCheck: CompoundButton
    private lateinit var callControlsCheck: CompoundButton
    private lateinit var readAloudCheck: CompoundButton
    private lateinit var readAloudRow: View
    private lateinit var speechMediaSpinner: Spinner
    // P8.3: Maps & routing → Voice guidance.
    private lateinit var voiceGuidanceCheck: CompoundButton
    private lateinit var voiceEarlyCheck: CheckBox
    private lateinit var voicePrepareCheck: CheckBox
    private lateinit var voiceAtTurnCheck: CheckBox
    private lateinit var voiceTimingSpinner: Spinner
    private lateinit var voiceDetailSpinner: Spinner
    private lateinit var voiceStreetNamesCheck: CompoundButton
    private lateinit var voiceRerouteCheck: CompoundButton
    private lateinit var voiceArrivalCheck: CompoundButton
    private lateinit var sampleButton: Button
    private lateinit var sampleText: TextView
    // Maps & routing → Route preferences (docs/navigation.md, "Route preferences").
    private lateinit var avoidHighwaysCheck: CompoundButton
    private lateinit var avoidTollsCheck: CompoundButton
    private lateinit var avoidUnpavedCheck: CompoundButton
    private lateinit var bypassTownsCheck: CompoundButton
    private lateinit var allowTracksCheck: CompoundButton
    private lateinit var topSpeedSpinner: Spinner
    private lateinit var etaSpinner: Spinner
    private lateinit var learnPaceCheck: CompoundButton
    private lateinit var showAlternativesCheck: CompoundButton
    // P9.1: Maps & routing → Live traffic (docs/phone-app.md, "Live traffic").
    private lateinit var trafficCheck: CompoundButton
    private lateinit var trafficKeyInput: EditText
    private lateinit var trafficKeyText: TextView
    private lateinit var trafficRemoveKeyButton: Button
    private lateinit var trafficTestButton: Button
    private lateinit var trafficTestText: TextView
    private lateinit var trafficRefreshSpinner: Spinner
    private lateinit var trafficLookAheadSpinner: Spinner
    private lateinit var trafficLimitInput: EditText
    private lateinit var trafficLimitText: TextView
    private lateinit var trafficStatusText: TextView

    /** Set while the screen moves the switch itself, so its listener saves nothing. */
    private var trafficSwitchQuiet = false

    /** A Test is out; the button stays off until it answers. */
    private var trafficTesting = false

    /** The last Save key was refused; the line under the field says so until the next. */
    private var trafficKeyInvalid = false

    // P10.1: Settings → Weather (docs/phone-app.md, "Settings").
    private lateinit var weatherCheck: CompoundButton
    private lateinit var weatherStatusText: TextView
    private lateinit var weatherGrantButton: Button
    private lateinit var weatherSpeakCheck: CompoundButton
    private lateinit var weatherLevelSpinner: Spinner
    /** Hidden with Show weather off, as the notification rows hide with theirs. */
    private lateinit var weatherRows: List<View>

    /** Set while the screen or the lab moves the switch, so its listener asks for nothing. */
    private var weatherSwitchQuiet = false

    /**
     * The card's own last read ([Weather.readOnce]), shown while no projection holds one.
     * On the phone only: the place name in it never reaches a note or the report.
     */
    private var weatherCardState: WeatherState? = null

    /** A card read is out; another asked for meanwhile runs once it answers. */
    private var weatherReading = false
    private var weatherReadAgain = false

    /** Held, unlike most rows, because these hide with the scene, the cluster or the sensors. */
    private lateinit var speedUnitRow: View
    private lateinit var mapSideRow: View
    private lateinit var navigationUnitsRow: View
    private lateinit var navigationForceRow: View
    private lateinit var navigationScriptRow: View
    private lateinit var sensorLocationRow: View
    private lateinit var mapText: TextView
    private lateinit var importMapButton: Button
    private lateinit var routingText: TextView
    private lateinit var folderText: TextView
    private lateinit var chooseFolderButton: Button
    private lateinit var checkFolderButton: Button
    private lateinit var importRoutingButton: Button
    private lateinit var removeDataButton: Button
    private lateinit var routeInput: EditText
    /** P11.4: live suggestions from OsmAnd's maps under [routeInput]. */
    private lateinit var searchSuggestions: SearchSuggestions
    /** P11.1: Settings → OsmAnd maps. */
    private lateinit var osmAndMapsCard: OsmAndMapsCard
    private lateinit var homeAssistantCard: HomeAssistantCard
    private lateinit var routeButton: Button
    private lateinit var clearRouteButton: Button
    private lateinit var previewButton: Button
    private lateinit var favoritesText: TextView
    private lateinit var addFavoriteButton: Button
    private lateinit var keepaliveSpinner: Spinner
    private lateinit var forgetPinsButton: Button
    private lateinit var replaceIdentityButton: Button
    private lateinit var importIdentityButton: Button
    private lateinit var connectButton: Button
    private lateinit var stopButton: Button
    private lateinit var useListedButton: Button

    // P7.7: the tabs, and what Home, Setup and Settings add.
    private lateinit var ui: PhoneViews
    private lateinit var scroll: ScrollView
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>
    private var currentTab = TAB_HOME

    /**
     * Open Diagnostics on the status card shows the tab for this screen without turning
     * the saved switch on, which would also put the debug line in the dashboard's header.
     */
    private var diagnosticsRevealed = false
    private lateinit var statusCard: LinearLayout
    private lateinit var statusSentence: TextView
    private lateinit var statusActionButton: Button
    private lateinit var primaryButton: Button
    private var primaryAction = StatusAction.CONNECT
    /** Home's Connect wirelessly (W2 from the main screen), and its progress line under it. */
    private lateinit var wirelessButton: Button
    private lateinit var wirelessText: TextView
    private val wirelessListener: (String) -> Unit = { renderState() }
    /** What to do once Nearby devices is granted: the wireless action that asked for it. */
    private var afterBluetoothGrant: (() -> Unit)? = null
    /** W3's Settings switch: Link the truck for wireless (the companion-device link). */
    private lateinit var wirelessLinkCheck: CompoundButton
    /** Standby's: Start wirelessly when the truck connects, as well as on its button. */
    private lateinit var wirelessAutoCheck: CompoundButton
    private var cardAction = StatusAction.NONE
    private lateinit var routeSummaryText: TextView
    private lateinit var favoritesList: LinearLayout
    private lateinit var saveDestinationButton: Button
    private var shownFavorites: List<Favorite>? = null
    private lateinit var setupList: LinearLayout
    private var shownSetup: List<SetupItem>? = null
    private var shownHereTile: String? = null
    private val setupButtons = mutableMapOf<SetupItem, Button>()
    private lateinit var callControlsText: TextView
    /** P8.1: Home's "Speech: …" line, re-read in onResume, since an engine may have come or gone. */
    private lateinit var speechText: TextView
    private var speechEngine: SpeechEngine? = null
    private lateinit var exclusionsRow: View
    private lateinit var exclusionsText: TextView
    private lateinit var versionText: TextView
    private lateinit var routeDetailText: TextView

    private val permissionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) onPermissionResult(intent)
            }
        }

    private val detachReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_ACCESSORY_DETACHED) return
                if (autoStartWaitUntil != null) {
                    clearAutoStartWait()
                    SessionController.note("auto-start no: detached while waiting for the source identity")
                }
                val attempt = SessionController.attempt ?: return
                if (!SessionController.isCurrent(attempt) || attempt.serviceRequested) return
                val detached = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                SessionController.note("detach broadcast while ${SessionController.stage.name}")
                if (Accessories.same(detached, attempt.mismatchCandidate)) {
                    attempt.mismatchCandidate = null
                    renderState()
                }
                if (SessionController.stage == Stage.PERMISSION && (detached == null || Accessories.same(detached, attempt.accessory))) {
                    SessionController.finish(attempt, "NO_ACCESSORY", "accessory detached before descriptor open")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(UsbManager::class.java)
        filter = AccessoryFilter.load(resources)
        RouteClock.install()
        currentTab = savedInstanceState?.getInt(STATE_TAB, TAB_HOME) ?: TAB_HOME
        buildViews()
        registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_NOT_EXPORTED)
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), RECEIVER_NOT_EXPORTED)
        SessionController.addListener(render)
        WirelessLink.addListener(wirelessListener)
        showHomeOnAttach(intent)
        if (!handleLabIntent(intent)) handleAttachIntent(intent)
        renderState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showHomeOnAttach(intent)
        if (!handleLabIntent(intent)) handleAttachIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    /** A plug-in shows Home, whose status says what the attach did. */
    private fun showHomeOnAttach(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) selectTab(TAB_HOME)
    }

    /**
     * Notification access may have been granted or withdrawn in Settings meanwhile, and
     * files may have landed in the drop folder.
     */
    override fun onResume() {
        super.onResume()
        DropFolder.sweep(this)
        // The dashboard's ☀/☾ saves day/night from the head unit while this screen is
        // behind it, so the row is re-read rather than trusted. Selecting
        // the same position again fires nothing, and a changed one stores what it read.
        val saved = AppSettings.load(this)
        dayNightSpinner.setSelection(saved.dayNight.ordinal)
        // P8.3: likewise the voice toggle beside End route, which is this screen's switch.
        voiceGuidanceCheck.isChecked = saved.voice.enabled
        // P8.1: the engine is chosen in Android's settings, which this screen links to, so
        // it is looked up again whenever the screen comes back.
        speechEngine = SpeechEngines.default(this)
        // P10: Breezy may have been installed, granted or refreshed meanwhile.
        refreshWeather()
        // W3: the link to the truck can be removed in Android's settings too.
        WirelessAuto.refresh(this)
        renderState()
    }

    /**
     * Lab transport for the emulator projection run: with an explicit
     * `lab_socket` extra, a debug build connects to that abstract local socket instead of a
     * USB accessory and starts an attempt at once. Release builds and vehicle testing never
     * take this path, and such an attempt never pins a head-unit certificate.
     */
    private fun handleLabIntent(intent: Intent?): Boolean {
        // P6.3: a route, on its own or alongside an attempt. On its own is how the lab asks
        // for one mid-projection, which is the case that shows it stays off the scene thread.
        val route = intent?.getStringExtra(EXTRA_LAB_ROUTE)
        if (route != null && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            RouteRequest.parse(route)?.let { Routing.request(this, it, "lab", "lab") }
                ?: SessionController.note("lab route \"$route\" is not lat,lon lat,lon; ignored")
        }
        // Saved places for the favorites tile, `name@lat,lon;name@lat,lon`, replacing the
        // list: how the lab gets a tile with rows in it to draw and scroll. A recent
        // destination, `name@lat,lon`, goes above them, or none: the tile lists only what
        // the lab gave it.
        val favorites = intent?.getStringExtra(EXTRA_LAB_FAVORITES)
        if (favorites != null && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Favorites.replace(this, labFavorites(favorites))
            val recent = intent.getStringExtra(EXTRA_LAB_RECENT)?.let { labFavorites(it).firstOrNull() }
            if (recent != null) Favorites.setRecent(this, recent) else Favorites.clearRecent(this)
            SessionController.note("lab favorites: ${Favorites.list(this).size} saved, ${if (recent != null) "a" else "no"} recent one")
        }
        // P8.1: the emulator has no A2DP output, so the lab lets Speaker's Bluetooth gate
        // pass on any output. Debug builds only; the report says speech_gate=lab.
        val speechGate = intent?.hasExtra(EXTRA_LAB_SPEECH_BLUETOOTH) == true
        if (speechGate && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Speaker.labBluetooth = intent.getBooleanExtra(EXTRA_LAB_SPEECH_BLUETOOTH, false)
            SessionController.note("lab speech Bluetooth gate ${if (Speaker.labBluetooth) "overridden" else "restored"} (debug build)")
        }
        // P9: live traffic's settings for the lab, pointing it at the stand-in server.
        val trafficLab = intent != null && labTraffic(intent)
        // P10: Show weather and its choices, a read now, or P10.0's capture.
        val weatherLab = intent != null && labWeather(intent)
        // P9.6: the divided card's layout for this process's next projection. Read when the
        // dashboard's layout is made, so it goes before the attempt below starts one.
        val offerLayout = intent?.getStringExtra(EXTRA_LAB_OFFER_LAYOUT)
        if (offerLayout != null && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val chosen = OfferLayout.entries.firstOrNull { it.key == offerLayout }
            if (chosen != null) DashboardLayout.defaultOfferLayout = chosen
            SessionController.note(
                if (chosen != null) "lab offer layout ${chosen.key} (debug build)" else "lab offer layout \"$offerLayout\" unknown; ignored",
            )
        }
        // P9 lab: the dashboard's and the map's figures in the saved report, as they are now.
        // W3 lab: watch every companion link, as the Settings switch does after Android's
        // dialog. The emulator has no paired head unit to link through the dialog, so the
        // lab makes the link with `cmd companiondevice associate` and this watches it.
        val wirelessObserve = intent?.hasExtra(EXTRA_LAB_WIRELESS_OBSERVE) == true
        if (wirelessObserve && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WirelessAuto.observeAll(this)
        }
        val reportNow = intent?.hasExtra(EXTRA_LAB_REPORT) == true
        if (reportNow && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            ProjectionService.labReport?.invoke() ?: SessionController.note("lab report: not projecting")
        }
        val socket = intent?.getStringExtra(EXTRA_LAB_SOCKET)
        if (socket == null) {
            if (route == null && favorites == null && !speechGate && !trafficLab && !weatherLab && offerLayout == null && !reportNow && !wirelessObserve) return false
            setIntent(Intent(this, ConnectionActivity::class.java))
            return true
        }
        setIntent(Intent(this, ConnectionActivity::class.java))
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            SessionController.note("lab socket requested in a non-debuggable build; ignored")
            return true
        }
        if (SessionController.stage != Stage.IDLE || !SessionController.identityReady) {
            SessionController.note("lab socket requested while ${SessionController.stage.name}; ignored")
            return true
        }
        val settings = AttemptSettings(
            profile = intent.getStringExtra(EXTRA_LAB_PROFILE) ?: PROFILES[0],
            stopAfter = intent.getStringExtra(EXTRA_LAB_STOP_AFTER) ?: SessionSettings.STOP_AFTER_PROJECTION,
            tlsProfile = SessionSettings.TLS_PROFILES[0],
            armedFirstUse = true,
            pins = emptyList(),
            captureDiscovery = false,
            captureUnhandled = intent.getBooleanExtra(EXTRA_LAB_CAPTURE_UNHANDLED, false),
            captureVideo = intent.getBooleanExtra(EXTRA_LAB_CAPTURE_VIDEO, false),
            openAllChannels = intent.getBooleanExtra(EXTRA_LAB_OPEN_ALL_CHANNELS, false),
            rebuildOnRegain = !intent.getBooleanExtra(EXTRA_LAB_RESUME_IN_PLACE, false),
            congestionRestart = intent.getBooleanExtra(EXTRA_LAB_CONGESTION_RESTART, true),
            requestFocus = !intent.getBooleanExtra(EXTRA_LAB_WAIT_FOR_FOCUS, false),
            keyframeSeconds = intent.getIntExtra(EXTRA_LAB_KEYFRAME_SECONDS, 1),
            setupField = intent.getStringExtra(EXTRA_LAB_SETUP_FIELD) ?: SessionSettings.SETUP_FIELD_CODEC,
            keepalive = intent.getStringExtra(EXTRA_LAB_KEEPALIVE) ?: SessionSettings.KEEPALIVE_MIRROR,
            projectionMs = intent.getLongExtra(EXTRA_LAB_PROJECTION_MS, 0L).takeIf { it > 0 },
            sensors = intent.getStringExtra(EXTRA_LAB_SENSORS) ?: SessionSettings.SENSORS_ALL,
            sensorLocation = intent.getBooleanExtra(EXTRA_LAB_SENSOR_LOCATION, false),
            audio = intent.getBooleanExtra(EXTRA_LAB_AUDIO, false),
            navigation = intent.getBooleanExtra(EXTRA_LAB_NAVIGATION, false),
            navigationForce = intent.getBooleanExtra(EXTRA_LAB_NAVIGATION_FORCE, false),
            navigationScript = intent.getBooleanExtra(EXTRA_LAB_NAVIGATION_SCRIPT, false),
            softwareEncoder = intent.getBooleanExtra(EXTRA_LAB_SOFTWARE_ENCODER, false),
            videoPreference = intent.getStringExtra(EXTRA_LAB_VIDEO_PREFERENCE)
                ?: SessionSettings.VIDEO_PREFERENCE_LARGEST,
            encoderFps = intent.getIntExtra(EXTRA_LAB_ENCODER_FPS, 0),
            sceneArea = intent.getStringExtra(EXTRA_LAB_SCENE_AREA) ?: SessionSettings.SCENE_AREA_CONTENT,
            labSocket = socket,
        )
        // W2 lab: the same settings over a TCP socket through WirelessLink's wireless path.
        intent.getStringExtra(EXTRA_LAB_WIRELESS_TCP)?.let { target ->
            val host = target.substringBeforeLast(':')
            val port = target.substringAfterLast(':').toIntOrNull()
            if (port == null || !WirelessLink.labConnect(this, host, port, settings)) {
                SessionController.note("lab wireless TCP $target not started")
            }
            return true
        }
        val attempt = SessionController.begin(settings) ?: return true
        SessionController.note("lab socket attempt on abstract socket $socket (debug build)")
        SessionController.completed(attempt, "accessory")
        SessionController.move(Stage.PERMISSION, "lab socket transport; no USB accessory")
        SessionController.completed(attempt, "permission")
        attempt.serviceRequested = true
        try {
            // Started, not foreground: a connectedDevice service needs a connected device.
            startService(ProjectionService.startIntent(this, attempt.generation))
        } catch (e: RuntimeException) {
            attempt.serviceRequested = false
            SessionController.finish(attempt, "FGS_START_FAILED", "startService threw $e")
        }
        return true
    }

    /** The lab extra's places; an entry that does not parse is left out, not fatal. */
    private fun labFavorites(text: String): List<Favorite> =
        text.split(';').mapNotNull { entry ->
            val name = entry.substringBefore('@', "")
            val point = entry.substringAfter('@', "").split(',')
            if (point.size != 2) return@mapNotNull null
            val lat = point[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val lon = point[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            Favorite.of(name, lat, lon)
        }

    /** P11.1: OsmAnd's maps stay open while this screen is in front, for typed search. */
    override fun onStart() {
        super.onStart()
        ObfLibrary.listener = { if (!isDestroyed) render() }
        ObfLibrary.hold(MAPS_HOLDER, true)
    }

    override fun onStop() {
        searchSuggestions.hide()
        ObfLibrary.hold(MAPS_HOLDER, false)
        super.onStop()
    }

    override fun onDestroy() {
        ObfLibrary.listener = null
        SessionController.removeListener(render)
        WirelessLink.removeListener(wirelessListener)
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(detachReceiver)
        main.removeCallbacks(autoStartGiveUp)
        super.onDestroy()
    }

    private fun handleAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        // Consume it so a recreated activity does not act on the same attach again.
        setIntent(Intent(this, ConnectionActivity::class.java))
        val extra = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        val current = Accessories.current(usb, extra)
        val attempt = SessionController.attempt
        if (SessionController.stage == Stage.IDLE) {
            SessionController.note("attach intent while IDLE; enumerated=${current != null}")
            autoStart()
            return
        }
        if (attempt == null || SessionController.stage != Stage.ARMED) {
            SessionController.note("attach intent while ${SessionController.stage.name}; enumerated=${current != null}")
            return
        }
        if (current == null) {
            // Never trust an intent extra that is not currently enumerated.
            SessionController.note("attach intent accessory is not currently enumerated; ignored")
            return
        }
        SessionController.note("attach intent for enumerated accessory")
        scan(attempt)
    }

    /**
     * Plug-in-and-go (P7): an attach with no attempt in progress starts one with
     * the saved settings, exactly as Connect would, when [AutoStart.decide] allows it —
     * the setting is on, the accessory matches the filter, a head unit is already pinned,
     * and the identity is ready. Anything else leaves the attach doing what it always
     * did, which is nothing but the note.
     *
     * First-use trust is never armed here ([PerAttempt.NONE]): with no pins this path does
     * not start at all, and with pins an unknown head unit is refused by the pin check
     * rather than trusted. Plugging into an unknown USB host must never pin it.
     *
     * From cold — the process was killed since the last drive, which is the usual case —
     * the attach arrives before [IdentityWork] has loaded the identity on its worker
     * thread. That is [AutoStart.WAIT]: the start is held, [retryAutoStart] decides again
     * when the identity settles, and it gives up after [AutoStart.WAIT_MS]. A detach, or
     * any attempt beginning meanwhile, ends the hold. Each decision is made afresh, so a
     * held start still needs the accessory enumerated and matching when it goes.
     *
     * The notification permission is not asked for. The attach may have launched this
     * activity behind the keyguard with the phone in a pocket, and a prompt no one sees
     * would hold the attempt at ARMED; the session runs without its notification, as a
     * denied one already does, and [notificationText] says so on screen.
     */
    private fun autoStart() {
        val saved = AppSettings.load(this)
        val attached = when (Accessories.enumerate(usb, filter)) {
            Enumeration.None -> AutoStart.Attached.NONE
            is Enumeration.Mismatch -> AutoStart.Attached.MISMATCH
            is Enumeration.Match -> AutoStart.Attached.MATCH
        }
        val pins = IdentityWork.trust(this).pins()
        val decision = AutoStart.decide(
            SessionController.stage, saved.autoStart, attached, pins.size,
            saved.stopAfter == SessionSettings.STOP_AFTER_PROJECTION, identityState(),
        )
        if (decision == AutoStart.WAIT) {
            if (autoStartWaitUntil == null) {
                SessionController.note("auto-start held: ${decision.reason}")
                autoStartWaitUntil = SystemClock.uptimeMillis() + AutoStart.WAIT_MS
                main.postDelayed(autoStartGiveUp, AutoStart.WAIT_MS)
            }
            return
        }
        // Cleared before the note, whose listener would otherwise decide again.
        clearAutoStartWait()
        SessionController.note("auto-start ${if (decision == AutoStart.START) "yes" else "no"}: ${decision.reason}")
        if (decision != AutoStart.START) return
        val attempt = SessionController.begin(saved.attempt(pins)) ?: return
        autoStarted = attempt.generation
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            SessionController.note("notification permission not granted; auto-start does not prompt")
        }
        scan(attempt)
    }

    /**
     * Decides a held plug-in start again once the identity has settled (loaded or failed)
     * or the stage has left IDLE. Called from the session listener, which the identity's
     * arrival notifies; while nothing that matters has changed it does nothing, so the
     * notes it causes cannot loop.
     */
    private fun retryAutoStart() {
        if (autoStartWaitUntil == null) return
        if (SessionController.stage == Stage.IDLE && identityState() == AutoStart.Identity.LOADING) return
        autoStart()
    }

    private fun clearAutoStartWait() {
        autoStartWaitUntil = null
        main.removeCallbacks(autoStartGiveUp)
    }

    /**
     * Loading covers both "busy" and "not yet started": a cold process handles the attach
     * before the worker has even reported. Failed is a finished load with no passing
     * self-test, which no amount of waiting fixes.
     */
    private fun identityState(): AutoStart.Identity = when {
        SessionController.identityReady -> AutoStart.Identity.READY
        SessionController.identityBusy || SessionController.identityReport.isEmpty() -> AutoStart.Identity.LOADING
        else -> AutoStart.Identity.FAILED
    }

    /**
     * Moves the activity behind whatever was in front once the attempt it auto-started
     * has its foreground service running, so the phone is not left showing the settings
     * screen for a session that needs nothing from it.
     *
     * Not at the moment the service is requested: `startForegroundService` is allowed
     * because this activity is in front, and whether the platform still counts it as in
     * front by the time the service promotes itself is exactly what the locked-phone
     * experiment (docs/connecting.md, "Start when plugged in") has to find out. Leaving only after the session has moved
     * past PERMISSION — the service promotes itself before that — keeps this step out of
     * that question. A failed attempt leaves the screen up, so the failure is seen.
     */
    private fun leaveAfterAutoStart() {
        val generation = autoStarted ?: return
        val attempt = SessionController.attempt
        if (attempt == null || attempt.generation != generation || SessionController.stage == Stage.IDLE) {
            autoStarted = null
            return
        }
        if (SessionController.stage.ordinal <= Stage.PERMISSION.ordinal) return
        autoStarted = null
        SessionController.note("auto-started attempt running; moving to the back")
        moveTaskToBack(true)
    }

    private fun connect() {
        if (SessionController.identityBusy) return
        // Every control saved itself as it changed, so what is saved is what is on screen.
        val saved = AppSettings.load(this)
        if (saved.stopAfter != SessionSettings.STOP_AFTER_VERSION && !SessionController.identityReady) {
            // The identity is prepared before attachment, never while a head unit waits.
            Toast.makeText(this, R.string.identity_not_ready, Toast.LENGTH_LONG).show()
            return
        }
        val once = PerAttempt(
            firstUse = firstUseCheck.isChecked,
            captureDiscovery = captureDiscoveryCheck.isChecked,
            captureUnhandled = captureUnhandledCheck.isChecked,
            captureVideo = captureVideoCheck.isChecked,
            sensorLocation = sensorLocationCheck.isChecked,
        )
        val settings = saved.attempt(IdentityWork.trust(this).pins(), once)
        val attempt = SessionController.begin(settings) ?: return
        // First-use trust and position in the report are scoped to this one attempt.
        firstUseCheck.isChecked = false
        sensorLocationCheck.isChecked = false
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Asked at most once per attempt; the result continues arming either way.
            attempt.notificationPrompted = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        scan(attempt)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WIRELESS) {
            val after = afterBluetoothGrant
            afterBluetoothGrant = null
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                after?.invoke()
            } else {
                Toast.makeText(this, R.string.home_wireless_no_permission, Toast.LENGTH_LONG).show()
                // Refused for good: Android shows no prompt again, so go where it can be allowed.
                if (grantResults.isNotEmpty() && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
                    openAppSettings()
                }
            }
            return
        }
        // P7.7's Setup buttons ask for these outside any attempt; they only change the list.
        if (requestCode == REQUEST_SETUP_LOCATION) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            SessionController.note("location permission ${if (granted) "granted" else "denied"} from Setup")
            // Asked before and refused for good: Android shows no prompt, so the button
            // goes where the operator can still allow it.
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) openAppSettings()
            renderState()
            return
        }
        // P10: Breezy's permission, from turning Show weather on or from a Grant button.
        if (requestCode == REQUEST_WEATHER || requestCode == REQUEST_WEATHER_GRANT) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            SessionController.note("weather permission ${if (granted) "granted" else "denied"}")
            if (granted) {
                // A projection under way reads now rather than at its next 15-minute cue.
                Weather.cue(WeatherCue.START)
            } else if (requestCode == REQUEST_WEATHER_GRANT && grantResults.isNotEmpty() &&
                !shouldShowRequestPermissionRationale(WeatherRules.PERMISSION)
            ) {
                // Refused for good, as for location: the button goes where it can still be allowed.
                openAppSettings()
            }
            weatherCardState = null
            refreshWeather()
            renderState()
            return
        }
        if (requestCode != REQUEST_NOTIFICATIONS) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        SessionController.note("notification permission ${if (granted) "granted" else "denied"}")
        val attempt = SessionController.attempt ?: return
        if (SessionController.isCurrent(attempt) && SessionController.stage == Stage.ARMED) scan(attempt)
    }

    /** Armed: enumerate now. A match proceeds; a mismatch is shown, never claimed automatically. */
    private fun scan(attempt: Attempt) {
        when (val found = Accessories.enumerate(usb, filter)) {
            Enumeration.None -> {
                attempt.mismatchCandidate = null
                SessionController.note("no accessory enumerated; connect the head unit")
            }
            is Enumeration.Mismatch -> {
                attempt.mismatchCandidate = found.accessory
                SessionController.note("accessory does not match filter ($filter); manual use required")
            }
            is Enumeration.Match -> acquire(attempt, found.accessory, filterMatch = true)
        }
    }

    private fun useListed() {
        val attempt = SessionController.attempt ?: return
        if (!SessionController.isCurrent(attempt) || SessionController.stage != Stage.ARMED) return
        val current = Accessories.current(usb, attempt.mismatchCandidate)
        if (current == null) {
            attempt.mismatchCandidate = null
            SessionController.note("listed accessory is no longer enumerated")
            return
        }
        attempt.manualOverride = true
        acquire(attempt, current, filterMatch = false)
    }

    private fun acquire(attempt: Attempt, accessory: UsbAccessory, filterMatch: Boolean) {
        attempt.accessory = accessory
        attempt.mismatchCandidate = null
        attempt.filterMatch = filterMatch
        SessionController.completed(attempt, "accessory")
        SessionController.move(Stage.PERMISSION, "filter_match=$filterMatch manual_override=${attempt.manualOverride}")
        if (usb.hasPermission(accessory)) {
            SessionController.note("usb permission already held")
            return permissionGranted(attempt, accessory)
        }
        if (attempt.permissionRequested) {
            return SessionController.finish(attempt, "USB_PERMISSION_DENIED", "permission already requested for this attempt")
        }
        attempt.permissionRequested = true
        // Package-scoped explicit intent; mutable so the system can add the result extras.
        val request = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val pending = PendingIntent.getBroadcast(this, 0, request, PendingIntent.FLAG_MUTABLE)
        try {
            usb.requestPermission(accessory, pending)
            SessionController.note("usb permission requested")
        } catch (e: RuntimeException) {
            SessionController.finish(attempt, "USB_PERMISSION_DENIED", "requestPermission threw $e")
        }
    }

    private fun onPermissionResult(intent: Intent) {
        val attempt = SessionController.attempt
        if (attempt == null || !SessionController.isCurrent(attempt) || SessionController.stage != Stage.PERMISSION ||
            attempt.serviceRequested
        ) {
            SessionController.note("late usb permission result ignored")
            return
        }
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val extra = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        val accessory = Accessories.current(usb, attempt.accessory)
        when {
            extra != null && !Accessories.same(extra, attempt.accessory) ->
                SessionController.finish(attempt, "USB_PERMISSION_DENIED", "result named a different accessory")
            accessory == null ->
                SessionController.finish(attempt, "NO_ACCESSORY", "accessory not enumerated at permission result")
            !granted || !usb.hasPermission(accessory) ->
                SessionController.finish(attempt, "USB_PERMISSION_DENIED", "user denied or permission not held")
            else -> permissionGranted(attempt, accessory)
        }
    }

    private fun permissionGranted(attempt: Attempt, accessory: UsbAccessory) {
        if (Accessories.current(usb, accessory) == null) {
            return SessionController.finish(attempt, "NO_ACCESSORY", "accessory not enumerated after permission")
        }
        SessionController.completed(attempt, "permission")
        attempt.serviceRequested = true
        try {
            startForegroundService(ProjectionService.startIntent(this, attempt.generation))
        } catch (e: RuntimeException) {
            attempt.serviceRequested = false
            SessionController.finish(attempt, "FGS_START_FAILED", "startForegroundService threw $e")
        }
    }

    /**
     * Home's Connect wirelessly: W2 ([WirelessLink]) without the probe, to the head unit
     * chosen last time. The first time, or on a long press ([choose]), it asks which paired
     * device, unless only one is a vehicle. While the link is still setting up (Bluetooth,
     * then Android's Wi-Fi prompt), the same button cancels it; once projecting, the large
     * Stop ends it as it ends a wired session. Nearby devices is asked for on the first tap
     * and not before.
     */
    private fun connectWirelessly(choose: Boolean = false) {
        if (WirelessLink.settingUp) {
            if (SessionController.stage == Stage.IDLE) WirelessLink.end("cancelled from Home", operator = true)
            return
        }
        // Standing by for the truck is not busy: this tap replaces it (startWireless).
        if (WirelessLink.active && !WirelessLink.waiting) return
        if (SessionController.stage != Stage.IDLE || SessionController.identityBusy) return
        if (!SessionController.identityReady) {
            Toast.makeText(this, R.string.identity_not_ready, Toast.LENGTH_LONG).show()
            return
        }
        withBluetooth { pickWirelessDevice(choose) { startWireless(it) } }
    }

    /** Runs [action] now if Nearby devices is granted, else asks for it and runs it on a grant. */
    private fun withBluetooth(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) return action()
        afterBluetoothGrant = action
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_WIRELESS)
    }

    /**
     * The head unit for a wireless action: the one used last, else the only paired vehicle,
     * else the operator's pick from the paired devices, vehicles first. [choose] always
     * asks. Only after [withBluetooth].
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun pickWirelessDevice(choose: Boolean, then: (BluetoothDevice) -> Unit) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, R.string.wireless_probe_no_bluetooth, Toast.LENGTH_LONG).show()
            return
        }
        val bonded = try {
            WirelessLink.vehiclesFirst(adapter.bondedDevices.orEmpty())
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.wireless_probe_failed, e.toString()), Toast.LENGTH_LONG).show()
            return
        }
        if (bonded.isEmpty()) {
            Toast.makeText(this, R.string.wireless_probe_no_devices, Toast.LENGTH_LONG).show()
            return
        }
        val saved = AppSettings.wirelessDevice(this)?.let { address -> bonded.firstOrNull { it.address == address } }
        val device = if (choose) null else saved ?: bonded.filter(WirelessLink::isVehicle).singleOrNull()
        if (device != null) return then(device)
        val names = bonded.map { it.name ?: getString(R.string.wireless_probe_unnamed) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.home_wireless_pick)
            .setItems(names) { _, which -> then(bonded[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startWireless(device: BluetoothDevice) {
        // A link standing by for the truck gives way: this one asks the truck now.
        if (SessionController.stage == Stage.IDLE) WirelessLink.stopWaiting("replaced by Home's Connect wirelessly")
        // The world may have moved on while a dialog was open.
        if (WirelessLink.active || SessionController.stage != Stage.IDLE) return
        AppSettings.saveWirelessDevice(this, device.address)
        if (WirelessLink.connect(this, device)) SessionController.note("wireless connect from Home (W2)")
        renderState()
    }

    /**
     * W3's switch, Link the truck for wireless. On: Nearby devices, the head unit (as Home
     * picks it), then Android's own companion-device dialog, after which Android wakes the
     * app whenever that head unit connects over Bluetooth ([WirelessAuto]) and the app stands
     * by for it. Off: the link to it is removed, so Android no longer tells the app at all.
     * The switch shows the link as it is, never a wish.
     */
    private fun onWirelessLinkSwitch() {
        val wanted = wirelessLinkCheck.isChecked
        wirelessLinkCheck.isChecked = WirelessAuto.isOn(this)
        if (!wanted) {
            WirelessAuto.unlink(this)
            SessionController.note("link the car for wireless: off")
            renderState()
            return
        }
        withBluetooth {
            pickWirelessDevice(choose = false) { device ->
                AppSettings.saveWirelessDevice(this, device.address)
                WirelessAuto.link(this, device, REQUEST_WIRELESS_LINK) { renderState() }
            }
        }
    }

    private fun stop() {
        val attempt = SessionController.attempt ?: return
        if (!SessionController.isCurrent(attempt)) return
        if (attempt.serviceRequested) {
            try {
                startService(ProjectionService.stopIntent(this))
            } catch (e: RuntimeException) {
                SessionController.note("stop request to service failed: $e")
            }
        } else {
            SessionController.finish(attempt, "CANCELLED", "stopped before descriptor open")
        }
    }

    /**
     * Opens the system notification-access screen. A notification listener binding cannot
     * be requested at runtime the way a permission can, so this is the whole of what the
     * app is able to do; the operator enables (or later withdraws) it there, and
     * [onResume] picks up whichever they chose.
     */
    private fun openNotificationAccess() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            Toast.makeText(this, "Cannot open notification access settings", Toast.LENGTH_LONG).show()
        }
    }

    private fun forgetPins() {
        AlertDialog.Builder(this)
            .setMessage(R.string.forget_pins_confirm)
            .setPositiveButton(R.string.forget_pins) { _, _ ->
                try {
                    IdentityWork.trust(this).clearPins()
                    SessionController.note("pinned head-unit certificates cleared by the operator")
                } catch (e: IOException) {
                    SessionController.note("clearing head-unit pins failed: $e")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun replaceIdentity() {
        AlertDialog.Builder(this)
            .setMessage(R.string.replace_identity_confirm)
            .setPositiveButton(R.string.replace_identity) { _, _ ->
                if (IdentityWork.ensure(this, replace = true)) {
                    SessionController.note("source identity replacement requested by the operator")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNotices() {
        val text =
            try {
                assets.open(NOTICES_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: IOException) {
                getString(R.string.export_failed, e.toString())
            }
        val view = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setText(text)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.notices)
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun exportReport(requestCode: Int) {
        val name = when (requestCode) {
            REQUEST_EXPORT_SAVED -> "headlink-saved-reports.txt"
            else -> SessionController.attempt?.let(ReportPersistence::reportName) ?: "headlink-report.txt"
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, name)
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: RuntimeException) {
            Toast.makeText(this, getString(R.string.export_failed, e.toString()), Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PREVIEW) {
            if (resultCode == RESULT_OK && data != null) adoptPreviewChoice(data.getLongExtra(PreviewActivity.EXTRA_TOKEN, -1L))
            return
        }
        // W3: Android's companion-device dialog, answered.
        if (requestCode == REQUEST_WIRELESS_LINK) {
            WirelessAuto.linkResult(this, resultCode, data)
            renderState()
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == REQUEST_IMPORT_MAP) {
            importMap(uri)
            return
        }
        if (requestCode == REQUEST_IMPORT_ROUTING) {
            importRouting(uri)
            return
        }
        if (requestCode == REQUEST_MAPS_FOLDER) {
            val message = try {
                if (ObfLibrary.choose(this, uri)) null else getString(R.string.osmand_maps_is_drop_folder)
            } catch (e: SecurityException) {
                getString(R.string.osmand_maps_failed, e.message ?: e.toString())
            }
            message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            render()
            return
        }
        if (requestCode == REQUEST_DROP_FOLDER) {
            val message = try {
                if (DropFolder.choose(this, uri)) null else getString(R.string.drop_folder_is_maps_folder)
            } catch (e: SecurityException) {
                getString(R.string.drop_folder_failed, e.message ?: e.toString())
            }
            message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            render()
            return
        }
        if (requestCode == REQUEST_IMPORT_IDENTITY) {
            if (!IdentityWork.importIdentity(this, uri)) {
                Toast.makeText(this, "Stop the session and wait for identity preparation before importing", Toast.LENGTH_LONG).show()
            }
            return
        }
        // The current report is composed here, on the main thread; saved reports are read
        // on the export thread after flushing the latest snapshot.
        val text: () -> String = when (requestCode) {
            REQUEST_EXPORT -> SessionReport.compose(this, usb, filter).let { report -> { report } }
            REQUEST_EXPORT_SAVED -> ReportPersistence.store?.let { store -> { store.flush(); store.exportAll() } } ?: return
            else -> return
        }
        Thread({ writeExport(uri, text) }, "report-export").start()
    }

    /**
     * Opens the route preview on the route on show. The request is a small file (Austin to
     * Denver is under 200 KB), written here because the preview runs in another process.
     */
    private fun openPreview() {
        val request = Routing.previewRequest() ?: return
        try {
            RoutePreview.writeRequest(RoutePreview.requestFile(filesDir), request)
        } catch (e: IOException) {
            Toast.makeText(this, getString(R.string.preview_unreadable, e.message ?: e.toString()), Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(Intent(this, PreviewActivity::class.java), REQUEST_PREVIEW)
    }

    /** The preview chose another route: follow it, unless the route it replaces is gone. */
    private fun adoptPreviewChoice(token: Long) {
        val adopted = try {
            val (chosenToken, route) = RoutePreview.readChoice(RoutePreview.choiceFile(filesDir))
            chosenToken == token && Routing.adopt(route, chosenToken)
        } catch (e: IOException) {
            false
        }
        Toast.makeText(this, if (adopted) R.string.preview_adopted else R.string.preview_stale, Toast.LENGTH_LONG).show()
        render()
    }

    /**
     * Copies a picked Mapsforge file into the app's map directory on a worker thread.
     *
     * A region extract is tens of megabytes, so this cannot run on the main thread, and
     * the result has to come back to it to refresh the label.
     */
    private fun importMap(uri: Uri) {
        val name = queryName(uri) ?: "region${MapData.SUFFIX}"
        Thread({
            val message = try {
                val installed = contentResolver.openInputStream(uri)?.use { stream ->
                    MapData.install(this, name, stream)
                } ?: throw IOException("no input stream")
                getString(R.string.map_imported, installed.name)
            } catch (e: IOException) {
                getString(R.string.map_import_failed, e.message ?: e.toString())
            }
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                SessionController.note(message)
                render()
            }
        }, "map-import").start()
    }

    /**
     * Streams a picked BRouter `.rd5` tile into app-private storage on a worker thread.
     *
     * A tile is over a hundred megabytes; [RoutingData.install] never holds it in memory,
     * and checks it against the packaged profile before it replaces anything.
     */
    private fun importRouting(uri: Uri) {
        val name = queryName(uri) ?: uri.lastPathSegment ?: "-"
        val root = Routing.root(this)
        Thread({
            val message = try {
                RoutingData.installProfile(RoutingData.profiles(root))
                val installed = contentResolver.openInputStream(uri)?.use { stream ->
                    RoutingData.install(RoutingData.segments(root), RoutingData.profiles(root), name, stream)
                } ?: throw IOException("no input stream")
                getString(R.string.routing_imported, installed.name)
            } catch (e: IOException) {
                getString(R.string.routing_import_failed, e.message ?: e.toString())
            }
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                SessionController.note(message)
                render()
            }
        }, "routing-import").start()
    }

    /**
     * Lists every installed map and routing tile and deletes the one the operator picks.
     *
     * One file per confirmation, never a batch: a region's map or tile is a long transfer
     * to put back, so each removal names exactly what goes. Idle and with no route being
     * computed, because a session reads the maps it started with and the router reads
     * the tiles.
     */
    private fun removeData() {
        val files = MapData.installed(this) + Routing.installed(this)
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.remove_data_none, Toast.LENGTH_LONG).show()
            return
        }
        val labels = files.map { "${it.name} (${it.length() / (1L shl 20)} MiB)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_data)
            .setItems(labels) { _, which ->
                val file = files[which]
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.remove_data_confirm, file.name))
                    .setPositiveButton(R.string.remove_data_delete) { _, _ ->
                        val message =
                            if (file.delete()) getString(R.string.data_removed, file.name)
                            else getString(R.string.data_remove_failed, file.name)
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        SessionController.note(message)
                        render()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Two points route between them, as in P6.3. One point — typed, or a `geo:` URI or
     * OsmAnd link pasted in — is a destination, routed from wherever the vehicle or the
     * phone is, the same as a share.
     */
    private fun computeRoute() {
        val text = routeInput.text.toString()
        val request = RouteRequest.parse(text)
        if (request != null) {
            Routing.request(this, request, DestinationSource.TYPED.label, "typed")
            render()
            return
        }
        val message = when (val parsed = DestinationParser.parseText(text)) {
            is ParsedDestination.Found -> Navigation.set(this, parsed.destination, DestinationSource.TYPED)
            is ParsedDestination.Refused -> {
                Navigation.refuse(parsed.reason, DestinationSource.TYPED)
                getString(if (parsed.reason == Refusal.ADDRESS_ONLY) R.string.destination_address_only else R.string.route_input_invalid)
            }
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        render()
    }

    /**
     * A place for the dashboard's favorites tile: a name and a location in any form the
     * destination box takes. Opens on the current destination when there is one, since
     * a place just shared from OsmAnd is the likeliest thing to want kept.
     *
     * The name and the coordinates may be shown here and on the head unit; the event
     * log records only that the list changed and how long it is.
     */
    private fun addFavorite() {
        val margin = (16 * resources.displayMetrics.density).toInt()
        val destination = Navigation.destination
        val nameInput = EditText(this).apply {
            setHint(R.string.favorite_name_hint)
            isSingleLine = true
            destination?.label?.let { setText(it) }
        }
        val locationInput = EditText(this).apply {
            setHint(R.string.favorite_location_hint)
            isSingleLine = true
            destination?.let { setText(String.format(java.util.Locale.ROOT, "%.5f, %.5f", it.latitude, it.longitude)) }
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin / 2, margin, 0)
            addView(TextView(this@ConnectionActivity).apply { setText(R.string.add_favorite_description) })
            addView(nameInput)
            addView(locationInput)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_favorite)
            .setView(form)
            .setPositiveButton(R.string.add_favorite) { _, _ ->
                val message = when (val parsed = DestinationParser.parseText(locationInput.text.toString())) {
                    is ParsedDestination.Found -> {
                        val place = parsed.destination
                        // No name typed: the place's own label, else its coordinates.
                        val name = nameInput.text.toString().ifBlank { Navigation.describe(place) }
                        val favorite = Favorite.of(name, place.latitude, place.longitude)
                        when {
                            favorite == null -> getString(R.string.favorite_name_missing)
                            !Favorites.add(this, favorite) -> getString(R.string.favorites_full, Favorite.MAX)
                            else -> {
                                SessionController.note("favorite added (${Favorites.list(this).size} saved)")
                                getString(R.string.favorite_added, favorite.name)
                            }
                        }
                    }
                    is ParsedDestination.Refused ->
                        getString(if (parsed.reason == Refusal.ADDRESS_ONLY) R.string.destination_address_only else R.string.route_input_invalid)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * P11.1: Settings → OsmAnd maps → Choose folder. A read-only grant ([ObfLibrary.choose]);
     * the picker opens wherever it last was, and the card's help says where OsmAnd keeps them.
     */
    private fun chooseMapsFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_MAPS_FOLDER)
        } catch (e: android.content.ActivityNotFoundException) {
            SessionController.note("no folder picker available: $e")
        }
    }

    /**
     * Where typed search ranks from: the vehicle while projecting, else the
     * phone's newest cached fix. Read on the main thread when a search starts; it goes to
     * the search engine only, never a note or the report.
     */
    private fun searchPoint(): Pair<Double, Double>? =
        Navigation.vehicleFix?.invoke() ?: lastKnownLocation()?.let { it.latitude to it.longitude }

    /**
     * A tapped suggestion routes there as a share does (P11.4): the same [Navigation.set]
     * and toast. The log says only that it came from search; the place stays on the phone.
     */
    private fun routeToSuggestion(suggestion: Suggestion) {
        SessionController.note("search suggestion picked")
        val message = Navigation.set(this, Destination(suggestion.latitude, suggestion.longitude, suggestion.label), DestinationSource.SEARCH)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        render()
    }

    /**
     * A tap on a favorite routes there, exactly as a tap on the dashboard's tile does.
     * The log says only that it came from the phone.
     */
    private fun routeToFavorite(favorite: Favorite) {
        SessionController.note("phone favorite tapped")
        val message = Navigation.set(this, Destination(favorite.latitude, favorite.longitude, favorite.name), DestinationSource.FAVORITE)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        render()
    }

    /** A long press: Rename, Move to top or Remove, each naming exactly the place it changes. */
    private fun favoriteActions(favorite: Favorite) {
        val actions = arrayOf(getString(R.string.rename_favorite), getString(R.string.favorite_to_top), getString(R.string.remove_favorite))
        AlertDialog.Builder(this)
            .setTitle(favorite.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> renameFavorite(favorite)
                    1 -> moveFavorite(favorite, 0)
                    else -> removeFavorite(favorite)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Puts one saved place at row [to], the dashboard's tile following at once. The place
     * is looked up again here, as [removeFavorite] does: the list may have changed since
     * the drag or the menu began.
     */
    private fun moveFavorite(favorite: Favorite, to: Int) {
        val from = Favorites.list(this).indexOf(favorite)
        if (from < 0 || from == to) return
        Favorites.move(this, from, to)
        SessionController.note("favorite moved (${Favorites.list(this).size} saved)")
        render()
    }

    /**
     * Drops one saved place after a confirmation. The index is looked up again when the
     * operator confirms, since the list may have changed behind the dialog (a lab intent,
     * or a second screen), and a stale index would remove the wrong place.
     */
    private fun removeFavorite(favorite: Favorite) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_favorite_confirm, favorite.name))
            .setPositiveButton(R.string.remove_favorite_remove) { _, _ ->
                val index = Favorites.list(this).indexOf(favorite)
                if (index < 0) return@setPositiveButton
                Favorites.remove(this, index)
                SessionController.note("favorite removed (${Favorites.list(this).size} saved)")
                Toast.makeText(this, getString(R.string.favorite_removed, favorite.name), Toast.LENGTH_LONG).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * A new name for one saved place, held to [Favorite.of]'s rules like a new one, and
     * written back in the same position so the dashboard's order does not change.
     */
    private fun renameFavorite(favorite: Favorite) {
        val margin = ui.dp(16)
        val input = EditText(this).apply {
            setHint(R.string.favorite_name_hint)
            isSingleLine = true
            setText(favorite.name)
            setSelection(favorite.name.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_favorite)
            .setView(LinearLayout(this).apply {
                setPadding(margin, margin / 2, margin, 0)
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton(R.string.rename_favorite) { _, _ ->
                val renamed = Favorite.of(input.text.toString(), favorite.latitude, favorite.longitude)
                val current = Favorites.list(this)
                val index = current.indexOf(favorite)
                val message = when {
                    renamed == null -> getString(R.string.favorite_name_missing)
                    index < 0 -> return@setPositiveButton
                    else -> {
                        Favorites.replace(this, current.mapIndexed { i, f -> if (i == index) renamed else f })
                        SessionController.note("favorite renamed (${current.size} saved)")
                        getString(R.string.favorite_renamed, renamed.name)
                    }
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Saves the destination in one tap: a place just shared from OsmAnd is the
     * likeliest one to keep. Named by its own label, else its coordinates; Rename is a
     * long press away. A place already saved at the same point is not saved twice.
     */
    private fun saveDestination() {
        val destination = Navigation.destination ?: return
        val current = Favorites.list(this)
        current.firstOrNull { it.latitude == destination.latitude && it.longitude == destination.longitude }?.let {
            Toast.makeText(this, getString(R.string.favorite_exists, it.name), Toast.LENGTH_LONG).show()
            return
        }
        val favorite = Favorite.of(Navigation.describe(destination), destination.latitude, destination.longitude)
        val message = when {
            favorite == null -> getString(R.string.favorite_name_missing)
            !Favorites.add(this, favorite) -> getString(R.string.favorites_full, Favorite.MAX)
            else -> {
                SessionController.note("favorite added (${Favorites.list(this).size} saved)")
                getString(R.string.favorite_added, favorite.name)
            }
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        render()
    }

    /** The picked document's display name, for the installed file to keep. */
    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun writeExport(uri: Uri, text: () -> String) {
        val message =
            try {
                val bytes = text().toByteArray(Charsets.UTF_8)
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw IOException("no output stream")
                getString(R.string.export_done)
            } catch (e: Exception) {
                // I/O, provider, or saved-report flush failures all end the export.
                getString(R.string.export_failed, e.toString())
            }
        main.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun renderState() {
        val stage = SessionController.stage
        val attempt = SessionController.attempt
        stageText.text = getString(R.string.stage_line, stage.name, SessionController.lastCompleted)
        val notificationsGranted =
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notificationText.visibility = if (notificationsGranted) View.GONE else View.VISIBLE
        val shown = attempt?.accessory ?: attempt?.mismatchCandidate ?: Accessories.list(usb).firstOrNull()
        accessoryText.text = getString(R.string.accessory_line, Accessories.describe(shown), filter.toString())
        resultText.text = getString(R.string.result_line, SessionController.finalCategory, SessionController.finalDetail)
        if (SessionController.finalCategory == "AUTH_FAILED" &&
            parseReport(SessionController.nativeReport.toByteArray())["auth_status"] == "-3"
        ) {
            resultText.append("\n" + getString(R.string.source_auth_rejected))
        }
        val saved = ReportPersistence.store?.list().orEmpty()
        savedText.text = getString(
            R.string.saved_line,
            saved.size,
            saved.firstOrNull() ?: "-",
            ReportPersistence.previousIncomplete ?: "-",
        )
        val idle = stage == Stage.IDLE
        connectButton.isEnabled = idle && !SessionController.identityBusy
        profileSpinner.isEnabled = idle
        stopAfterSpinner.isEnabled = idle
        tlsProfileSpinner.isEnabled = idle
        firstUseCheck.isEnabled = idle && stopAfterSpinner.selectedItem != SessionSettings.STOP_AFTER_VERSION
        captureDiscoveryCheck.isEnabled =
            idle && stopAfterSpinner.selectedItem == SessionSettings.STOP_AFTER_DISCOVERY
        // Unhandled messages arrive at any stage past TLS, so this is not stage-scoped.
        captureUnhandledCheck.isEnabled =
            idle && stopAfterSpinner.selectedItem != SessionSettings.STOP_AFTER_VERSION
        for (view in listOf(
            captureVideoCheck, openAllChannelsCheck, resumeInPlaceCheck, congestionRestartCheck, wirelessKeepAwakeCheck,
            waitForFocusCheck, keyframeSpinner, setupFieldSpinner,
        )) {
            view.isEnabled =
                idle && stopAfterSpinner.selectedItem == SessionSettings.STOP_AFTER_PROJECTION
        }
        val projection = stopAfterSpinner.selectedItem == SessionSettings.STOP_AFTER_PROJECTION
        sensorsSpinner.isEnabled = idle && projection
        // Nothing to redact until a sensor set is chosen, so the option only appears with
        // one; it stays checked but inert otherwise, and `connect` clears it too.
        sensorLocationCheck.isEnabled =
            idle && projection && sensorsSpinner.selectedItem != SessionSettings.SENSORS_NONE
        sensorLocationRow.visibility =
            if (sensorsSpinner.selectedItem == SessionSettings.SENSORS_NONE) View.GONE else View.VISIBLE
        mediaInfoCheck.isEnabled =
            idle && stopAfterSpinner.selectedItem == SessionSettings.STOP_AFTER_PROJECTION
        audioCheck.isEnabled =
            idle && stopAfterSpinner.selectedItem == SessionSettings.STOP_AFTER_PROJECTION
        navigationCheck.isEnabled =
            idle && stopAfterSpinner.selectedItem == SessionSettings.STOP_AFTER_PROJECTION
        // The unit choice only means something when the cluster is being driven.
        navigationUnitsSpinner.isEnabled = navigationCheck.isEnabled && navigationCheck.isChecked
        navigationUnitsRow.visibility =
            if (navigationCheck.isChecked) View.VISIBLE else View.GONE
        navigationForceCheck.isEnabled = navigationUnitsSpinner.isEnabled
        navigationForceRow.visibility = navigationUnitsRow.visibility
        navigationScriptCheck.isEnabled = navigationUnitsSpinner.isEnabled
        navigationScriptRow.visibility = navigationUnitsRow.visibility
        // The grant is made and withdrawn in Settings, so it is re-read on every render
        // rather than remembered. Transport buttons never depend on it.
        val mediaAccess = MediaAccessService.enabled(this)
        mediaAccessText.text = getString(
            if (mediaAccess) R.string.media_access_granted else R.string.media_access_none,
        )
        mediaAccessText.visibility = if (mediaInfoCheck.isChecked) View.VISIBLE else View.GONE
        mediaAccessButton.visibility =
            if (mediaInfoCheck.isChecked && !mediaAccess) View.VISIBLE else View.GONE
        softwareEncoderCheck.isEnabled =
            idle && stopAfterSpinner.selectedItem == SessionSettings.STOP_AFTER_PROJECTION
        // The selection happens at discovery, so it applies to a discovery-only run too.
        videoPreferenceSpinner.isEnabled =
            idle && stopAfterSpinner.selectedItem != SessionSettings.STOP_AFTER_VERSION
        encoderFpsSpinner.isEnabled =
            idle && stopAfterSpinner.selectedItem == SessionSettings.STOP_AFTER_PROJECTION
        sceneAreaSpinner.isEnabled =
            idle && stopAfterSpinner.selectedItem == SessionSettings.STOP_AFTER_PROJECTION
        sceneSpinner.isEnabled =
            idle && stopAfterSpinner.selectedItem == SessionSettings.STOP_AFTER_PROJECTION
        val dashboard = sceneSpinner.selectedItem == SessionSettings.SCENE_DASHBOARD
        mapSideSpinner.isEnabled = sceneSpinner.isEnabled && dashboard
        importMapButton.isEnabled = idle
        mapSideRow.visibility = if (dashboard) View.VISIBLE else View.GONE
        // The unit is the dashboard header's; the demo scene draws no speed at all.
        speedUnitSpinner.isEnabled = sceneSpinner.isEnabled && dashboard
        speedUnitRow.visibility = if (dashboard) View.VISIBLE else View.GONE
        // P7.7: the map rows now live on their own tab and stay visible with the demo
        // scene, which only hides what the maps are for, not whether they are installed.
        // Every installed map is drawn, so every one is listed.
        val maps = MapData.installed(this)
        mapText.text = maps.takeIf { it.isNotEmpty() }?.let {
            getString(R.string.map_installed, it.joinToString(", ") { map -> map.name }, it.sumOf { map -> map.length() } / (1L shl 20))
        } ?: getString(R.string.map_none)
        // Routing is not tied to an attempt or a scene: a route can be computed with no head
        // unit attached, which is how its cost on the phone is measured.
        val tiles = Routing.installed(this)
        routingText.text =
            if (tiles.isEmpty()) getString(R.string.routing_none)
            else getString(R.string.routing_installed, tiles.joinToString(", ") { it.name })
        // The destination's name is the operator's own and may be shown here; the report
        // beside it carries only which source set it. Home says the same in words; this
        // is the pre-P7.7 detail, with where the destination came from and the search time.
        routeDetailText.text = buildString {
            Navigation.destination?.let { destination ->
                append(
                    getString(
                        if (Navigation.waiting) R.string.destination_line_waiting else R.string.destination_line,
                        Navigation.describe(destination),
                        Navigation.source?.label ?: "-",
                    ),
                )
            }
            Routing.summary()?.let {
                if (isNotEmpty()) append("\n")
                append(getString(R.string.route_line, it))
            }
            if (isEmpty()) append(getString(R.string.destination_none))
        }
        clearRouteButton.isEnabled = Navigation.destination != null || Routing.line != null || Routing.pending != 0L
        previewButton.isEnabled = Routing.previewRequest() != null
        importRoutingButton.isEnabled = Routing.pending == 0L
        removeDataButton.isEnabled = idle && Routing.pending == 0L && !DropFolder.busy
        folderText.text = DropFolder.folder(this)?.let { folder ->
            getString(
                R.string.drop_folder_line,
                DropFolder.describe(folder),
                if (DropFolder.busy) getString(R.string.drop_folder_checking) else DropFolder.lastSweep ?: "-",
            )
        } ?: getString(R.string.drop_folder_none)
        checkFolderButton.isEnabled = DropFolder.folder(this) != null && !DropFolder.busy
        routeButton.isEnabled = tiles.isNotEmpty() && Routing.pending == 0L
        keepaliveSpinner.isEnabled = idle
        forgetPinsButton.isEnabled = idle
        replaceIdentityButton.isEnabled = idle && !SessionController.identityBusy
        importIdentityButton.isEnabled = idle && !SessionController.identityBusy
        val identity = parseReport(SessionController.identityReport.toByteArray())
        val pins = IdentityWork.trust(this).pins()
        trustText.text = getString(
            R.string.trust_line,
            when {
                SessionController.identityBusy -> getString(R.string.identity_busy)
                identity["identity_error"] != null -> "error: ${identity["identity_error"]}"
                else -> identity["source_identity_fingerprint_sha256"] ?: "-"
            },
            identity["tls_self_test"] ?: "-",
            if (pins.isEmpty()) getString(R.string.no_pins) else pins.joinToString("\n"),
        )
        val fingerprint = identity["source_identity_fingerprint_sha256"]
        identityStatusText.text = when {
            SessionController.identityBusy -> getString(R.string.identity_status_busy)
            identity["identity_error"] != null -> getString(R.string.identity_status_error, identity["identity_error"])
            fingerprint != null -> getString(R.string.identity_status_ready, fingerprint.take(16))
            else -> getString(R.string.identity_status_busy)
        }
        stopButton.isEnabled = stage != Stage.IDLE && stage != Stage.STOPPING
        useListedButton.visibility =
            if (stage == Stage.ARMED && attempt?.mismatchCandidate != null) View.VISIBLE else View.GONE
        // The live report is composed only while it is on screen: it is the one costly
        // part of a render, and every session event renders.
        if (currentTab == TAB_DIAGNOSTICS) reportText.text = SessionReport.compose(this, usb, filter)
        projectionText.text = SessionController.projectionReport.ifEmpty { getString(R.string.no_projection) }
        renderHome(stage, attempt, pins.size)
        renderSetup(idle, maps.size, tiles.map { it.name }.toSet(), pins.size, notificationsGranted, mediaAccess)
        renderSettings()
        renderTraffic()
        renderWeather()
        osmAndMapsCard.render(AppSettings.load(this).speedUnit)
        homeAssistantCard.render()
        renderTabs()
    }

    /**
     * Home: the status in words, the one large button, the route in words and the
     * favorites. The status card's own button carries only the actions the large button
     * does not: the large one is Connect (or Try again, or Trust and connect with no head
     * unit trusted yet) while idle and Stop otherwise.
     */
    private fun renderHome(stage: Stage, attempt: Attempt?, pins: Int) {
        val status = StatusText.of(
            stage,
            SessionController.finalCategory,
            parseReport(SessionController.nativeReport.toByteArray())["auth_status"],
            autoStartCheck.isChecked,
            Navigation.destination?.let(Navigation::describe),
            // Events are cleared when an attempt begins, so this is this attempt's unplug.
            unplugged = SessionController.events.any { it.contains("accessory detached") },
            mismatch = stage == Stage.ARMED && attempt?.mismatchCandidate != null,
            identityPreparing = identityState() == AutoStart.Identity.LOADING,
            trusted = pins > 0,
        )
        // A live region announces every setText, and every session event renders.
        if (statusSentence.text.toString() != status.sentence) statusSentence.text = status.sentence
        statusCard.backgroundTintList = getColorStateList(
            when (status.tone) {
                StatusTone.QUIET -> R.color.status_quiet
                StatusTone.BUSY -> R.color.status_busy
                StatusTone.GOOD -> R.color.status_good
                StatusTone.PROBLEM -> R.color.status_problem
            },
        )
        cardAction = when (status.action) {
            StatusAction.OPEN_DIAGNOSTICS, StatusAction.USE_LISTED, StatusAction.IMPORT_IDENTITY -> status.action
            else -> StatusAction.NONE
        }
        statusActionButton.visibility = if (cardAction == StatusAction.NONE) View.GONE else View.VISIBLE
        statusActionButton.text = cardAction.label
        primaryAction = when {
            stage != Stage.IDLE -> StatusAction.STOP
            status.action == StatusAction.TRY_AGAIN -> StatusAction.TRY_AGAIN
            // No head unit trusted yet, or this one isn't: the first-use path is the large
            // button, since a plain Connect or a plug-in would only fail the pin check.
            status.action == StatusAction.TRUST_AND_CONNECT -> StatusAction.TRUST_AND_CONNECT
            else -> StatusAction.CONNECT
        }
        primaryButton.text = when (primaryAction) {
            StatusAction.STOP -> getString(R.string.stop)
            StatusAction.CONNECT -> getString(R.string.connect)
            else -> primaryAction.label
        }
        // The same conditions as Diagnostics' own Connect and Stop, computed above.
        primaryButton.isEnabled = if (primaryAction == StatusAction.STOP) stopButton.isEnabled else connectButton.isEnabled
        // Connect wirelessly while idle, Cancel while the link sets up; hidden once a session
        // runs, when the large Stop ends either transport. Its line says how the link went.
        // Standing by for the truck's button reads as Connect wirelessly: a tap asks the truck now.
        val linking = WirelessLink.active
        val settingUp = WirelessLink.settingUp
        wirelessLinkCheck.isChecked = WirelessAuto.isOn(this)
        wirelessAutoCheck.isEnabled = wirelessLinkCheck.isChecked
        // Wireless only works with a head unit already trusted over USB, so until one is
        // pinned the button is hidden and its line says to connect over USB first.
        wirelessButton.visibility = if (stage == Stage.IDLE && (pins > 0 || linking)) View.VISIBLE else View.GONE
        wirelessButton.setText(if (settingUp) R.string.home_wireless_cancel else R.string.home_wireless_connect)
        wirelessButton.isEnabled = settingUp || connectButton.isEnabled
        wirelessText.visibility = if (stage == Stage.IDLE || linking) View.VISIBLE else View.GONE
        wirelessText.text = WirelessLink.lastStatus
            ?: getString(if (pins > 0) R.string.home_wireless_hint else R.string.home_wireless_needs_usb)

        // P8.1: which engine speaks, flagged if it can reach the network.
        val engine = speechEngine
        speechText.text = when {
            engine == null -> getString(R.string.speech_no_engine)
            engine.internet -> getString(R.string.speech_engine_networked, engine.label)
            else -> getString(R.string.speech_engine, engine.label)
        }

        val destination = Navigation.destination
        val result = Routing.last
        val route = result?.route
        // Guide's live distance and time to go while following, else the route's totals.
        val figures = RouteClock.figures(System.currentTimeMillis())
        val clock = ClockFormat.entries.getOrElse(clockFormatSpinner.selectedItemPosition) { ClockFormat.DEFAULT }
        val arrival = figures?.arrivalMillis?.let { clock.time(it, ZoneId.systemDefault()) }
        routeSummaryText.text = StatusText.routeSummary(
            destination?.let(Navigation::describe),
            Navigation.waiting,
            Routing.pending != 0L,
            result != null && route == null,
            figures?.metres,
            figures?.seconds,
            arrival,
            SpeedUnit.entries.getOrElse(speedUnitSpinner.selectedItemPosition) { SpeedUnit.MPH },
            figures?.trafficSeconds ?: 0,
        ) ?: getString(R.string.route_none)
        saveDestinationButton.visibility = if (destination != null) View.VISIBLE else View.GONE

        // The places are the operator's own and may be listed here, as the destination is.
        val favorites = Favorites.list(this)
        favoritesText.text = getString(R.string.favorites_title, favorites.size, Favorite.MAX)
        addFavoriteButton.isEnabled = favorites.size < Favorite.MAX
        if (favorites == shownFavorites) return
        shownFavorites = favorites
        favoritesList.removeAllViews()
        if (favorites.isEmpty()) {
            favoritesList.addView(ui.caption().apply { setText(R.string.favorites_none) })
        }
        favorites.forEach { favoritesList.addView(favoriteRow(it)) }
    }

    /**
     * One saved place: its name large, its coordinates small, the whole row a touch
     * target. Tap routes there; a long press offers Rename, Move to top and Remove; the
     * handle at the end drags the row to another position.
     */
    private fun favoriteRow(favorite: Favorite): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        minimumHeight = ui.dp(56)
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
        val attributes = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        background = attributes.getDrawable(0)
        attributes.recycle()
        isClickable = true
        isLongClickable = true
        setOnClickListener { routeToFavorite(favorite) }
        setOnLongClickListener {
            favoriteActions(favorite)
            true
        }
        val text = LinearLayout(this@ConnectionActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.primary(TextView(this@ConnectionActivity)).apply {
                text = favorite.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            })
            addView(ui.caption().apply {
                text = String.format(java.util.Locale.ROOT, "%.5f, %.5f", favorite.latitude, favorite.longitude)
                setPadding(0, 0, 0, 0)
            })
        }
        addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(favoriteHandle(this, favorite), LinearLayout.LayoutParams(ui.dp(48), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /**
     * The drag handle of [row]. The row follows the finger and the rows it passes step
     * aside, all by translation: the views are not re-ordered until the finger lifts,
     * because taking the row out of its parent mid-gesture would end the gesture. The
     * drop is then one [moveFavorite], and the render that follows rebuilds the list.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun favoriteHandle(row: View, favorite: Favorite): View = ui.caption().apply {
        text = "≡"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        gravity = android.view.Gravity.CENTER
        setPadding(0, 0, 0, 0)
        contentDescription = getString(R.string.favorite_drag_handle)
        var startY = 0f
        var target = -1
        fun settle() {
            for (i in 0 until favoritesList.childCount) favoritesList.getChildAt(i).translationY = 0f
            row.translationZ = 0f
            row.alpha = 1f
            target = -1
        }
        setOnTouchListener { handle, event ->
            val from = favoritesList.indexOfChild(row)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (from < 0) return@setOnTouchListener false
                    // The page scrolls; while a row is held, the drag is the row's.
                    handle.parent.requestDisallowInterceptTouchEvent(true)
                    startY = event.rawY
                    target = from
                    row.translationZ = ui.dp(4).toFloat()
                    row.alpha = 0.85f
                }
                MotionEvent.ACTION_MOVE -> if (target >= 0 && from >= 0) {
                    val dy = (event.rawY - startY).coerceIn(-row.top.toFloat(), (favoritesList.height - row.bottom).toFloat())
                    row.translationY = dy
                    // The row takes the place of each neighbour whose middle its edge has passed.
                    target = from
                    for (i in 0 until favoritesList.childCount) {
                        val other = favoritesList.getChildAt(i)
                        if (other === row) continue
                        val middle = other.top + other.height / 2
                        val passed = if (i > from) row.bottom + dy > middle else row.top + dy < middle
                        other.translationY = if (!passed) 0f else if (i > from) -row.height.toFloat() else row.height.toFloat()
                        if (passed && (i > from && i > target || i < from && i < target)) target = i
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val to = target
                    settle()
                    if (to >= 0) moveFavorite(favorite, to)
                }
                MotionEvent.ACTION_CANCEL -> settle()
            }
            true
        }
    }

    /**
     * Setup: what is missing, each with the one button that fixes it, or Ready.
     * Rebuilt only when the list changes; the buttons' enabled state follows every render,
     * because the imports and trust are only allowed while idle, as they are elsewhere.
     */
    private fun renderSetup(idle: Boolean, maps: Int, tiles: Set<String>, pins: Int, sessionNotifications: Boolean, access: Boolean) {
        val location = hasLocation()
        val here = if (location && tiles.isNotEmpty()) lastKnownLocation()?.let { RoutingData.tileFor(it.latitude, it.longitude) } else null
        val wanted = SetupCheck.notificationAccessWanted(
            mediaInfoCheck.isChecked, notificationsOnDashboardCheck.isChecked, callControlsCheck.isChecked,
            CallControl.fullControlsGranted(this),
        )
        val items = SetupCheck.items(
            maps, tiles, here, wanted, access, location, pins, sessionNotifications,
            // Saved rather than the switch: the lab may have changed it before this render.
            weatherWanted = AppSettings.load(this).weather.enabled, weatherPermission = weatherPermitted(),
        )
        // Only what a drive needs counts; optional items wait under their own heading.
        val required = items.count { !it.optional }
        tabs[TAB_SETUP].text = if (required == 0) getString(R.string.tab_setup) else getString(R.string.tab_setup_count, required)
        if (items != shownSetup || here != shownHereTile) {
            shownSetup = items
            shownHereTile = here
            setupList.removeAllViews()
            setupButtons.clear()
            if (required == 0) {
                setupList.addView(ui.card(ui.title(R.string.setup_ready), ui.caption().apply { setText(R.string.setup_ready_detail) }))
            }
            for ((index, item) in items.withIndex()) {
                if (item.optional && (index == 0 || !items[index - 1].optional)) {
                    setupList.addView(ui.title(R.string.setup_optional))
                }
                val fix = Button(this).apply {
                    text = item.fix
                    setOnClickListener { fix(item) }
                }
                setupButtons[item] = fix
                setupList.addView(
                    ui.card(
                        ui.primary(TextView(this)).apply {
                            text = item.title
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                            typeface = Typeface.DEFAULT_BOLD
                        },
                        ui.caption().apply {
                            text = if (item == SetupItem.NO_ROUTING_HERE && here != null) {
                                getString(R.string.setup_tile_needed, item.detail, here)
                            } else {
                                item.detail
                            }
                        },
                        fix,
                    ),
                )
            }
        }
        setupButtons.forEach { (item, button) ->
            button.isEnabled = when (item) {
                SetupItem.NO_MAP -> importMapButton.isEnabled
                SetupItem.NO_ROUTING, SetupItem.NO_ROUTING_HERE -> importRoutingButton.isEnabled
                SetupItem.NO_PINS -> idle && !SessionController.identityBusy
                else -> true
            }
        }
    }

    private fun fix(item: SetupItem) {
        when (item) {
            SetupItem.NO_MAP -> importMapButton.performClick()
            SetupItem.NO_ROUTING, SetupItem.NO_ROUTING_HERE -> importRoutingButton.performClick()
            SetupItem.NOTIFICATION_ACCESS -> grantNotificationAccess()
            SetupItem.LOCATION -> requestLocation()
            SetupItem.WEATHER_PERMISSION -> requestWeatherPermission(REQUEST_WEATHER_GRANT)
            SetupItem.NO_PINS -> trustAndConnect()
            SetupItem.SESSION_NOTIFICATIONS -> openSessionNotificationSettings()
        }
    }

    /** What Settings shows beside its switches, re-read each render like the media grant. */
    private fun renderSettings() {
        callControlsText.visibility = if (callControlsCheck.isChecked) View.VISIBLE else View.GONE
        callControlsText.text =
            if (CallControl.fullControlsGranted(this)) getString(R.string.call_controls_full)
            else getString(R.string.call_controls_partial, CallControl.GRANT_COMMAND)
        exclusionsRow.visibility = if (notificationsOnDashboardCheck.isChecked) View.VISIBLE else View.GONE
        readAloudRow.visibility = if (notificationsOnDashboardCheck.isChecked) View.VISIBLE else View.GONE
        val saved = AppSettings.load(this)
        val excluded = saved.notificationExclusions
        // P8.3: the trip panel's toggle may have been pressed since, with this screen up.
        // Setting the switch stores the value it already has and tells Guide nothing new.
        if (voiceGuidanceCheck.isChecked != saved.voice.enabled) voiceGuidanceCheck.isChecked = saved.voice.enabled
        exclusionsText.text =
            if (excluded.isEmpty()) getString(R.string.notification_exclusions_nothing)
            else getString(R.string.notification_exclusions_count, excluded.size)
    }

    /** Diagnostics is a tab only with its switch on, or once the status card opened it. */
    private fun renderTabs() {
        val diagnostics = diagnosticsCheck.isChecked || diagnosticsRevealed
        tabs[TAB_DIAGNOSTICS].visibility = if (diagnostics) View.VISIBLE else View.GONE
        if (!diagnostics && currentTab == TAB_DIAGNOSTICS) selectTab(TAB_SETTINGS)
    }

    /** Shows one page. Rendering is the caller's: a tap renders after it, [onCreate] before. */
    private fun selectTab(index: Int) {
        currentTab = index
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        ui.select(tabs, index)
        scroll.scrollTo(0, 0)
    }

    private fun onPrimary() {
        when (primaryAction) {
            StatusAction.STOP -> stop()
            StatusAction.TRUST_AND_CONNECT -> trustAndConnect()
            else -> connect()
        }
    }

    private fun onCardAction() {
        when (cardAction) {
            StatusAction.OPEN_DIAGNOSTICS -> {
                diagnosticsRevealed = true
                selectTab(TAB_DIAGNOSTICS)
                renderState()
            }
            StatusAction.USE_LISTED -> useListed()
            StatusAction.IMPORT_IDENTITY -> importIdentityButton.performClick()
            else -> {}
        }
    }

    /**
     * "Trust and connect" (and Setup's no-pins item): Diagnostics' "Trust the next
     * head unit" box, ticked for one attempt, then Connect — the same C2 first-use path,
     * behind a confirmation that says what it pins. [connect] clears the box once an
     * attempt begins; if none began (the identity was still loading), it is cleared here
     * too, so it cannot arm a later Connect nobody meant to trust with.
     */
    private fun trustAndConnect() {
        if (SessionController.stage != Stage.IDLE) return
        AlertDialog.Builder(this)
            .setTitle(R.string.trust_title)
            .setMessage(R.string.trust_confirm)
            .setPositiveButton(R.string.trust_and_connect) { _, _ ->
                val before = SessionController.attempt
                firstUseCheck.isChecked = true
                connect()
                if (SessionController.attempt === before) firstUseCheck.isChecked = false
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * APP09: the integration is offered on its own, and the permission it needs is
     * explained before the operator is sent to Settings to grant it. Nothing here can
     * request the grant directly — a notification listener binding is made in Settings —
     * so this only opens that screen.
     */
    private fun grantNotificationAccess() {
        val seconds = resources.getQuantityString(
            R.plurals.media_access_seconds, MediaControl.SEEK_SECONDS, MediaControl.SEEK_SECONDS,
        )
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.media_access_prompt, seconds))
            .setPositiveButton(R.string.media_access_grant) { _, _ -> openNotificationAccess() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Location only when the operator asks for it from Setup, after saying what it is
     * for (APP09): the app still never asks on its own. Both are requested so Android can
     * offer "approximate", which the position fallback also works with.
     */
    private fun requestLocation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.location_title)
            .setMessage(R.string.location_prompt)
            .setPositiveButton(R.string.location_allow) { _, _ ->
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQUEST_SETUP_LOCATION,
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * **Play a sample** (P8.3): the words a Prepare prompt would say, through
     * [Speaker.sample], which takes a prompt's path, binding the engine for the sample alone
     * when no projection has one. The button waits for the outcome, which says why when
     * nothing was heard.
     */
    private fun playSample() {
        val saved = AppSettings.load(this)
        sampleText.setText(R.string.voice_sample_playing)
        sampleButton.isEnabled = false
        val text = SpokenText.sample(saved.speedUnit, saved.voice.streetNames)
        val started = Speaker.sample(this, saved, text) { outcome ->
            sampleButton.isEnabled = true
            sampleText.text = sampleMessage(outcome, AppSettings.load(this).voice.enabled)
        }
        if (!started) sampleText.setText(R.string.voice_sample_busy)
    }

    /** What the phone says about a sample: heard, or why not; and whether prompts are off. */
    private fun sampleMessage(outcome: SampleOutcome, guidanceOn: Boolean): String {
        val said = when (outcome) {
            SampleOutcome.SPOKEN ->
                getString(if (Speaker.labBluetooth) R.string.voice_sample_spoken_lab else R.string.voice_sample_spoken)
            SampleOutcome.NO_ENGINE -> getString(R.string.voice_sample_no_engine)
            SampleOutcome.NO_ENGLISH -> getString(R.string.voice_sample_no_english)
            SampleOutcome.ENGINE_TIMEOUT -> (SpeechQueue.SAMPLE_INIT_TIMEOUT_MS / 1000).toInt().let { seconds ->
                resources.getQuantityString(R.plurals.voice_sample_engine_timeout, seconds, seconds)
            }
            SampleOutcome.NO_BLUETOOTH -> getString(R.string.voice_sample_no_bluetooth)
            SampleOutcome.IN_CALL -> getString(R.string.voice_sample_in_call)
            SampleOutcome.NO_FOCUS -> getString(R.string.voice_sample_no_focus)
            SampleOutcome.INTERRUPTED -> getString(R.string.voice_sample_interrupted)
            SampleOutcome.BLUETOOTH_LOST -> getString(R.string.voice_sample_bluetooth_lost)
            SampleOutcome.FOCUS_LOST -> getString(R.string.voice_sample_focus_lost)
            SampleOutcome.STOPPED -> getString(R.string.voice_sample_stopped)
            SampleOutcome.ERROR -> getString(R.string.voice_sample_error)
        }
        return if (guidanceOn) said else said + "\n" + getString(R.string.voice_sample_guidance_off)
    }

    /**
     * Where maps and routing tiles come from, opened in the phone's browser. The download
     * is the browser's; this app still fetches no map or routing data itself (APP10).
     */
    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_browser, url), Toast.LENGTH_LONG).show()
        }
    }

    /** Android's text-to-speech settings: engine, voice and rate are chosen there. */
    private fun openSpeechSettings() {
        try {
            startActivity(Intent(SpeechEngines.SETTINGS_ACTION))
        } catch (e: RuntimeException) {
            SessionController.note("cannot open text-to-speech settings: $e")
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: RuntimeException) {
            SessionController.note("cannot open app settings: $e")
        }
    }

    /** The session notification's own switch, which a denied prompt no longer offers. */
    private fun openSessionNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (e: RuntimeException) {
            openAppSettings()
        }
    }

    private fun hasLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * The phone's newest cached fix of any age, for Setup's "no routing data for where you
     * are" only: which 5° tile the phone is in changes over hundreds of kilometres, so an
     * old fix still answers it. Also where typed search ranks from (P11.4) when nothing
     * projects. Never requests a fix, and never leaves the phone.
     */
    private fun lastKnownLocation(): Location? {
        if (!hasLocation()) return null
        val manager = getSystemService(LocationManager::class.java) ?: return null
        return try {
            manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.elapsedRealtimeNanos }
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * The per-app exclusion list: every app seen posting a notification the tile
     * would show this session ([NotificationFeed.posters]), plus the ones already
     * excluded, by name. Saved through [AppSettings] like any other choice, and handed to
     * the feed at once so a change applies to a session already running. The event log
     * gets the count only; which apps are excluded never leaves the phone's settings.
     */
    private fun chooseExclusions() {
        val excluded = AppSettings.load(this).notificationExclusions
        val packages = (NotificationFeed.posters() + excluded).map { it to appLabel(it) }
            .sortedBy { it.second.lowercase(java.util.Locale.ROOT) }
        if (packages.isEmpty()) {
            ui.showHelp(R.string.notification_exclusions_label, getText(R.string.notification_exclusions_none))
            return
        }
        val checked = BooleanArray(packages.size) { packages[it].first in excluded }
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_exclusions_label)
            .setMultiChoiceItems(packages.map { it.second }.toTypedArray(), checked) { _, which, on -> checked[which] = on }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = packages.filterIndexed { i, _ -> checked[i] }.mapTo(HashSet()) { it.first }
                AppSettings.update(this) { it.copy(notificationExclusions = chosen) }
                NotificationFeed.exclusions = chosen
                SessionController.note("notification exclusions changed (${chosen.size} apps)")
                renderState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** An app's name for the exclusion list, or its package when it cannot be seen. */
    private fun appLabel(app: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(app, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            app
        }

    private fun versionLine(): String {
        val info = packageManager.getPackageInfo(packageName, 0)
        return getString(R.string.version_line, info.versionName ?: "-", info.longVersionCode)
    }

    /**
     * P9 lab extras, debug builds only: live traffic's settings seeded from the intent, so
     * `tools/emulator-lab.sh` can point it at its stand-in HERE server. They are
     * written to the traffic preferences like the lab favourites are, and stay until
     * changed. False when the intent carries none.
     */
    private fun labTraffic(intent: Intent): Boolean {
        val extras = listOf(
            EXTRA_LAB_TRAFFIC, EXTRA_LAB_TRAFFIC_URL, EXTRA_LAB_TRAFFIC_KEY,
            EXTRA_LAB_TRAFFIC_LIMIT, EXTRA_LAB_TRAFFIC_REFRESH, EXTRA_LAB_TRAFFIC_RESET,
        )
        if (extras.none(intent::hasExtra)) return false
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            SessionController.note("lab traffic settings in a non-debuggable build; ignored")
            return true
        }
        val stored = TrafficPrefs.update(this) { s ->
            var next = s
            intent.getStringExtra(EXTRA_LAB_TRAFFIC_URL)?.let { next = next.copy(baseUrl = it) }
            intent.getStringExtra(EXTRA_LAB_TRAFFIC_KEY)?.let { next = next.copy(apiKey = it) }
            if (intent.hasExtra(EXTRA_LAB_TRAFFIC_LIMIT)) {
                next = next.copy(monthlyLimit = intent.getIntExtra(EXTRA_LAB_TRAFFIC_LIMIT, next.monthlyLimit))
            }
            if (intent.hasExtra(EXTRA_LAB_TRAFFIC_REFRESH)) {
                next = next.copy(refreshMinutes = intent.getIntExtra(EXTRA_LAB_TRAFFIC_REFRESH, next.refreshMinutes))
            }
            if (intent.hasExtra(EXTRA_LAB_TRAFFIC)) {
                val on = intent.getBooleanExtra(EXTRA_LAB_TRAFFIC, false)
                next = next.copy(enabled = on, disclosed = next.disclosed || on)
            }
            next
        }
        if (intent.getBooleanExtra(EXTRA_LAB_TRAFFIC_RESET, false) && !TrafficPrefs.resetStatus(this)) {
            SessionController.note("lab traffic status could not be reset")
        }
        // The settings' own toString, which says whether a key is set but never shows it.
        SessionController.note("lab traffic settings: $stored (debug build)")
        renderState()
        return true
    }

    /** Puts live traffic's saved settings on the card, then makes each control save its own. */
    private fun loadTraffic() {
        val saved = TrafficPrefs.load(this)
        setTrafficSwitch(saved.enabled)
        trafficRefreshSpinner.setSelection(TrafficSettings.REFRESH_MINUTES.indexOf(saved.refreshMinutes))
        trafficLookAheadSpinner.setSelection(TrafficSettings.LOOK_AHEAD_KM.indexOf(saved.lookAheadKm))
        // ASCII digits, which is what the field's number keyboard types and parseLimit reads.
        trafficLimitInput.setText(String.format(java.util.Locale.ROOT, "%d", saved.monthlyLimit))
        trafficCheck.setOnCheckedChangeListener { _, checked ->
            if (trafficSwitchQuiet) return@setOnCheckedChangeListener
            val now = TrafficPrefs.load(this)
            when {
                !checked -> TrafficPrefs.update(this) { it.copy(enabled = false) }
                // The switch is disabled without a key; this is the belt to that brace.
                now.apiKey.isEmpty() -> setTrafficSwitch(false)
                // The first time, what it sends and HERE's terms, before anything is on.
                !now.disclosed -> {
                    setTrafficSwitch(false)
                    confirmTraffic()
                }
                else -> TrafficPrefs.update(this) { it.copy(enabled = true) }
            }
            renderState()
        }
        fun Spinner.savesTraffic(change: (TrafficSettings, Int) -> TrafficSettings) {
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    TrafficPrefs.update(this@ConnectionActivity) { change(it, position) }
                    renderState()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    renderState()
                }
            }
        }
        trafficRefreshSpinner.savesTraffic { s, i -> s.copy(refreshMinutes = TrafficSettings.REFRESH_MINUTES[i]) }
        trafficLookAheadSpinner.savesTraffic { s, i -> s.copy(lookAheadKm = TrafficSettings.LOOK_AHEAD_KM[i]) }
        // Saved as typed whenever it reads as a limit; otherwise the line under it says why
        // and the saved limit stands.
        trafficLimitInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: android.text.Editable?) {
                val limit = TrafficSettings.parseLimit(s?.toString().orEmpty())
                if (limit != null) TrafficPrefs.update(this@ConnectionActivity) { it.copy(monthlyLimit = limit) }
                trafficLimitText.visibility = if (limit == null) View.VISIBLE else View.GONE
                renderTraffic()
            }
        })
        trafficLimitText.setText(R.string.traffic_limit_invalid)
        trafficLimitText.visibility = View.GONE
    }

    private fun setTrafficSwitch(on: Boolean) {
        trafficSwitchQuiet = true
        trafficCheck.isChecked = on
        trafficSwitchQuiet = false
    }

    /**
     * The first turn-on: the paragraph on what is sent, and HERE's End
     * User Terms and Privacy Policy. Only **Turn on** turns it on.
     */
    private fun confirmTraffic() {
        val message = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(16), ui.dp(24), 0)
            addView(ui.body().apply { setText(R.string.traffic_disclosure) })
            addView(termsText().apply { setPadding(0, ui.dp(12), 0, 0) })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.traffic_disclosure_title)
            .setView(ScrollView(this).apply { addView(message) })
            .setPositiveButton(R.string.traffic_turn_on) { _, _ ->
                val stored = TrafficPrefs.update(this) { it.copy(enabled = true, disclosed = true) }
                SessionController.note("live traffic turned on (${if (stored.enabled) "on" else "no key"})")
                renderState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** HERE's terms, linked, at the foot of the card. */
    private fun termsText(): TextView = ui.caption().apply {
        autoLinkMask = android.text.util.Linkify.WEB_URLS
        movementMethod = android.text.method.LinkMovementMethod.getInstance()
        setText(R.string.traffic_terms)
    }

    /** Saves a typed or pasted key, or with null removes the saved one (which turns traffic off). */
    private fun saveTrafficKey(text: String?) {
        if (text != null) {
            val key = TrafficSettings.cleanKey(text)
            trafficKeyInvalid = key == null
            if (key == null) {
                renderTraffic()
                return
            }
            TrafficPrefs.update(this) { it.copy(apiKey = key) }
            SessionController.note("HERE API key saved")
        } else {
            trafficKeyInvalid = false
            TrafficPrefs.update(this) { it.copy(apiKey = "") }
            SessionController.note("HERE API key removed; live traffic off")
        }
        trafficKeyInput.text.clear()
        trafficTestText.text = null
        renderState()
    }

    /** One incidents request, off the main thread, for the card's Test. */
    private fun testTraffic() {
        trafficTesting = true
        trafficTestText.setText(R.string.traffic_testing)
        renderTraffic()
        val client = TrafficPrefs.client(this)
        Thread({
            // Nothing thrown here may take the app down: the client turns its own failures
            // into a message, and anything else (the preferences' commit, say) says so too.
            val message = try {
                getString(R.string.traffic_test_result, client.test().message)
            } catch (e: RuntimeException) {
                getString(R.string.traffic_test_crashed, e.javaClass.simpleName)
            }
            runOnUiThread {
                trafficTesting = false
                if (isDestroyed) return@runOnUiThread
                trafficTestText.text = message
                renderState()
            }
        }, "traffic-test").start()
    }

    /**
     * The card from what is saved: the switch (which needs a key), whether a key is saved,
     * Test (only with traffic on), and the Status. Re-read on every render, since
     * the route's refreshes write the status from another thread.
     */
    private fun renderTraffic() {
        val saved = TrafficPrefs.load(this)
        if (trafficCheck.isChecked != saved.enabled) setTrafficSwitch(saved.enabled)
        trafficCheck.isEnabled = saved.apiKey.isNotEmpty() || saved.enabled
        trafficKeyText.setText(
            when {
                trafficKeyInvalid -> R.string.traffic_key_invalid
                saved.apiKey.isEmpty() -> R.string.traffic_key_none
                else -> R.string.traffic_key_saved
            },
        )
        trafficRemoveKeyButton.isEnabled = saved.apiKey.isNotEmpty()
        trafficTestButton.isEnabled = saved.usable && !trafficTesting
        val status = TrafficPrefs.status(this)
        val month = java.time.YearMonth.now(java.time.ZoneOffset.UTC)
        val lastFetch = status.lastFetchMs.takeIf { it > 0 }?.let {
            val date = Date(it)
            "${DateFormat.getDateFormat(this).format(date)} ${DateFormat.getTimeFormat(this).format(date)}"
        }
        val described = status.describe(month, saved.monthlyLimit, lastFetch)
        // A lab override in force is said on the card, so one left over from a lab run on the
        // phone is never silent (only the lab hosts are honoured: TrafficClient.labBase).
        val lab = TrafficClient.labBase(saved, applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        trafficStatusText.text = if (lab == null) described else described + "\n" + getString(R.string.traffic_lab_override, lab)
    }

    /**
     * P10 lab extras, debug builds only: Show weather and its two choices
     * saved as the card saves them and handed to a projection under way, a read now, and
     * P10.0's capture on the Pixel. `lab_weather` never asks for Breezy's permission, which
     * the lab grants with `pm grant`. Each is noted for the lab to find in the event log,
     * by setting only: a place, a temperature or a headline is never noted. False when the
     * intent carries none.
     */
    private fun labWeather(intent: Intent): Boolean {
        val extras = listOf(
            EXTRA_LAB_WEATHER, EXTRA_LAB_WEATHER_LEVEL, EXTRA_LAB_WEATHER_SPEAK,
            EXTRA_LAB_WEATHER_CUE, EXTRA_LAB_WEATHER_CAPTURE,
        )
        if (extras.none(intent::hasExtra)) return false
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            SessionController.note("lab weather settings in a non-debuggable build; ignored")
            return true
        }
        val levelKey = intent.getStringExtra(EXTRA_LAB_WEATHER_LEVEL)
        val level = levelKey?.let { key -> AlertLevel.entries.firstOrNull { it.key == key } }
        if (levelKey != null && level == null) SessionController.note("lab weather level \"$levelKey\" unknown; ignored")
        val settingsChanged = intent.hasExtra(EXTRA_LAB_WEATHER) || intent.hasExtra(EXTRA_LAB_WEATHER_SPEAK) || level != null
        if (settingsChanged) {
            val stored = AppSettings.update(this) { s ->
                var next = s.weather
                if (intent.hasExtra(EXTRA_LAB_WEATHER)) next = next.copy(enabled = intent.getBooleanExtra(EXTRA_LAB_WEATHER, false))
                if (intent.hasExtra(EXTRA_LAB_WEATHER_SPEAK)) {
                    next = next.copy(speakAlerts = intent.getBooleanExtra(EXTRA_LAB_WEATHER_SPEAK, true))
                }
                if (level != null) next = next.copy(alertLevel = level)
                s.copy(weather = next)
            }
            Weather.settings(stored.weather)
            if (intent.hasExtra(EXTRA_LAB_WEATHER)) SessionController.note("lab weather ${if (stored.weather.enabled) "on" else "off"}")
            if (intent.hasExtra(EXTRA_LAB_WEATHER_SPEAK)) {
                SessionController.note("lab weather speak ${if (stored.weather.speakAlerts) "on" else "off"}")
            }
            if (level != null) SessionController.note("lab weather level ${stored.weather.alertLevel.key}")
        }
        if (intent.getBooleanExtra(EXTRA_LAB_WEATHER_CUE, false)) {
            Weather.cue(WeatherCue.LAB)
            SessionController.note("lab weather cue")
        }
        if (intent.getBooleanExtra(EXTRA_LAB_WEATHER_CAPTURE, false)) {
            // The summary is the provider's owner, the grant, its version, and for each
            // place whether it is the current position and has a name: never the name.
            Weather.capture(this) { summary -> SessionController.note("weather capture: $summary") }
        }
        // The card follows, switch moved quietly; with weather off it reads nothing.
        weatherCardState = null
        refreshWeather()
        renderState()
        return true
    }

    /** Whether a package answers for Breezy's authority: without one there is nothing to grant. */
    private fun breezyPresent(): Boolean =
        try {
            packageManager.resolveContentProvider(WeatherRules.AUTHORITY, PackageManager.ComponentInfoFlags.of(0)) != null
        } catch (e: RuntimeException) {
            false
        }

    private fun weatherPermitted(): Boolean = checkSelfPermission(WeatherRules.PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Breezy's own prompt ("read your weather data"), from the card's Grant or Setup's.
     * With no Breezy installed there is nothing to ask, and the card already says so.
     */
    private fun requestWeatherPermission(requestCode: Int) {
        if (!breezyPresent()) {
            Toast.makeText(this, WeatherText.status(WeatherState(WeatherStatus.MISSING), System.currentTimeMillis()), Toast.LENGTH_LONG).show()
            return
        }
        requestPermissions(arrayOf(WeatherRules.PERMISSION), requestCode)
    }

    /**
     * The card's status line. A projection's own reading is shown while it holds
     * one; otherwise one read of the card's own, only with Show weather on, since off means
     * nothing is read (the lab's `off` run checks the stand-in saw no query at all).
     */
    private fun refreshWeather() {
        if (!AppSettings.load(this).weather.enabled || Weather.state.status != WeatherStatus.NOT_READ) {
            renderWeather()
            return
        }
        if (weatherReading) {
            weatherReadAgain = true
            return
        }
        weatherReading = true
        Weather.readOnce(this, lastKnownLocation()?.let { it.latitude to it.longitude }) { state ->
            weatherReading = false
            if (isDestroyed) return@readOnce
            weatherCardState = state
            if (weatherReadAgain) {
                weatherReadAgain = false
                refreshWeather()
            }
            renderWeather()
        }
    }

    /**
     * The Weather card as saved and as last read, re-read on every render like the traffic
     * card, since the lab and a projection change it with this screen up. The switch is
     * moved quietly, so following the saved value never asks for the permission.
     */
    private fun renderWeather() {
        val saved = AppSettings.load(this).weather
        if (weatherCheck.isChecked != saved.enabled) setWeatherSwitch(saved.enabled)
        if (weatherSpeakCheck.isChecked != saved.speakAlerts) weatherSpeakCheck.isChecked = saved.speakAlerts
        if (weatherLevelSpinner.selectedItemPosition != saved.alertLevel.ordinal) weatherLevelSpinner.setSelection(saved.alertLevel.ordinal)
        weatherRows.forEach { it.visibility = if (saved.enabled) View.VISIBLE else View.GONE }
        // Off asks the package manager nothing either: every session event renders.
        if (!saved.enabled) return
        val state = Weather.state.takeIf { it.status != WeatherStatus.NOT_READ } ?: weatherCardState
        val ungranted = !weatherPermitted() && breezyPresent()
        weatherStatusText.text = when {
            // Taken back since the last read: said now, before the next read answers.
            ungranted -> WeatherText.status(WeatherState(WeatherStatus.NO_PERMISSION), System.currentTimeMillis())
            state == null -> getString(R.string.weather_reading)
            else -> WeatherText.status(state, System.currentTimeMillis())
        }
        weatherGrantButton.visibility = if (ungranted) View.VISIBLE else View.GONE
    }

    private fun setWeatherSwitch(on: Boolean) {
        weatherSwitchQuiet = true
        weatherCheck.isChecked = on
        weatherSwitchQuiet = false
    }

    /**
     * Puts the Weather card's saved choices on screen, then makes each save its own field,
     * as [loadSaved] does, and hands the result to a projection under way ([Weather.settings]).
     * Turning Show weather on asks for Breezy's permission when it is not yet held.
     */
    private fun loadWeather() {
        val saved = AppSettings.load(this).weather
        setWeatherSwitch(saved.enabled)
        weatherSpeakCheck.isChecked = saved.speakAlerts
        weatherLevelSpinner.setSelection(saved.alertLevel.ordinal)
        fun saveWeather(change: (WeatherSettings) -> WeatherSettings): WeatherSettings {
            val stored = AppSettings.update(this) { it.copy(weather = change(it.weather)) }
            Weather.settings(stored.weather)
            return stored.weather
        }
        weatherCheck.setOnCheckedChangeListener { _, checked ->
            if (weatherSwitchQuiet) return@setOnCheckedChangeListener
            saveWeather { it.copy(enabled = checked) }
            SessionController.note("weather turned ${if (checked) "on" else "off"}")
            weatherCardState = null
            if (checked && !weatherPermitted() && breezyPresent()) requestWeatherPermission(REQUEST_WEATHER)
            refreshWeather()
            renderState()
        }
        weatherSpeakCheck.setOnCheckedChangeListener { _, checked ->
            saveWeather { it.copy(speakAlerts = checked) }
            renderState()
        }
        weatherLevelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveWeather { it.copy(alertLevel = AlertLevel.entries[position]) }
                renderState()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                renderState()
            }
        }
    }

    private fun buildViews() {
        ui = PhoneViews(this)
        val margin = ui.dp(16)
        fun text(monospace: Boolean = false) = ui.primary(TextView(this)).apply {
            setTextIsSelectable(true)
            if (monospace) typeface = Typeface.MONOSPACE
            setPadding(0, margin / 2, 0, margin / 2)
        }
        fun button(label: Int, action: () -> Unit) = Button(this).apply {
            setText(label)
            setOnClickListener { action() }
        }
        /** A secondary action: the platform's borderless button. */
        fun quietButton(label: Int, action: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(label)
            setOnClickListener { action() }
        }
        fun switch(label: Int) = Switch(this).apply {
            setText(label)
            minHeight = ui.dp(48)
        }
        stageText = text().apply { textSize = 18f }
        trustText = text(monospace = true)
        identityStatusText = ui.body(selectable = true)
        notificationText = text().apply { setText(R.string.notifications_denied) }
        accessoryText = text(monospace = true)
        resultText = text()
        savedText = text()
        projectionText = text(monospace = true)
        reportText = text(monospace = true)
        fun spinner(items: List<String>) = Spinner(this).apply {
            adapter = ArrayAdapter(this@ConnectionActivity, android.R.layout.simple_spinner_dropdown_item, items)
        }
        profileSpinner = spinner(PROFILES)
        stopAfterSpinner = spinner(SessionSettings.STOP_AFTER)
        tlsProfileSpinner = spinner(SessionSettings.TLS_PROFILES)
        firstUseCheck = CheckBox(this).apply { setText(R.string.first_use_label) }
        captureDiscoveryCheck = CheckBox(this).apply { setText(R.string.capture_discovery_label) }
        captureUnhandledCheck = CheckBox(this).apply { setText(R.string.capture_unhandled_label) }
        captureVideoCheck = CheckBox(this).apply { setText(R.string.capture_video_label) }
        openAllChannelsCheck = CheckBox(this).apply { setText(R.string.open_all_channels_label) }
        resumeInPlaceCheck = CheckBox(this).apply { setText(R.string.resume_in_place_label) }
        congestionRestartCheck = CheckBox(this).apply { setText(R.string.congestion_restart_label) }
        wirelessKeepAwakeCheck = CheckBox(this).apply { setText(R.string.wireless_keep_awake_label) }
        waitForFocusCheck = CheckBox(this).apply { setText(R.string.wait_for_focus_label) }
        keyframeSpinner = spinner(SessionSettings.KEYFRAME_SECONDS.map { "$it s" })
        setupFieldSpinner = spinner(SessionSettings.SETUP_FIELDS)
        // Its saved default is the whole advertised set rather than the head of the list:
        // the operator chooses `none` for an attempt that must change nothing.
        sensorsSpinner = spinner(SessionSettings.SENSOR_SETS)
        // APP09 and spec §10: position is offered on its own, with what it means stated,
        // and it is never on unless the operator turned it on for this attempt. Never
        // saved (P7.1's PerAttempt), and cleared once the attempt begins.
        sensorLocationCheck = CheckBox(this).apply { setText(R.string.sensor_location_label) }
        // On by default (SavedSettings); it adds no Android permission of its own, and
        // the grant it can use is still asked for separately below.
        mediaInfoCheck = switch(R.string.media_info_label)
        // APP09 again: audio out is offered on its own. It adds no Android permission —
        // the audio is this app's own and goes out over USB — so there is nothing to send
        // the operator to Settings for, only a choice to make.
        audioCheck = switch(R.string.audio_out_label)
        // APP09 once more: turn-by-turn on the vehicle's own cluster is its own choice. It
        // needs no Android permission — the maneuvers are the followed route's (P6.5), or
        // P6.2's script — and a head unit that refuses the channel affects nothing else.
        // On by default (SavedSettings), like media_info: no permission of its own, and
        // G9/G10 passed with it on.
        navigationCheck = switch(R.string.navigation_cluster_label)
        navigationUnitsSpinner = spinner(SessionSettings.NAVIGATION_UNITS)
        // Offered beside the cluster box rather than on its own, because on its own it
        // means nothing: it changes what happens when the head unit declines to answer,
        // and a head unit that answers never reaches it. On by default (spec §8.1): Ford
        // SYNC 4.0 never sends InstrumentClusterStart, so waiting for it sends nothing.
        // Lab runs still take it from EXTRA_LAB_NAVIGATION_FORCE, off unless asked.
        navigationForceCheck = switch(R.string.navigation_force_label)
        navigationScriptCheck = switch(R.string.navigation_script_label)
        mediaAccessText = ui.caption()
        mediaAccessButton = button(R.string.media_access_grant) { grantNotificationAccess() }
        softwareEncoderCheck = CheckBox(this).apply { setText(R.string.software_encoder_label) }
        videoPreferenceSpinner = spinner(SessionSettings.VIDEO_PREFERENCES)
        encoderFpsSpinner = spinner(
            SessionSettings.ENCODER_RATES.map { if (it == 0) getString(R.string.encoder_fps_negotiated) else "$it" },
        )
        sceneAreaSpinner = spinner(SessionSettings.SCENE_AREAS)
        sceneSpinner = spinner(SessionSettings.SCENES)
        mapSideSpinner = spinner(MAP_SIDES)
        // Opens on the first entry, which is why [SpeedUnit] lists mph first.
        speedUnitSpinner = spinner(SpeedUnit.LABELS)
        // P7.1's settings, on the Settings tab since P7.7.
        autoStartCheck = switch(R.string.auto_start_label)
        wirelessLinkCheck = switch(R.string.wireless_link_label).apply { setOnClickListener { onWirelessLinkSwitch() } }
        wirelessAutoCheck = switch(R.string.wireless_auto_label)
        diagnosticsCheck = switch(R.string.diagnostics)
        markerColourSpinner = spinner(MarkerColour.LABELS)
        clockFormatSpinner = spinner(ClockFormat.LABELS)
        dayNightSpinner = spinner(DayNightMode.LABELS)
        notificationsOnDashboardCheck = switch(R.string.notifications_on_dashboard)
        callControlsCheck = switch(R.string.call_controls)
        // P8.1: under the dashboard switch, and only shown with it on.
        readAloudCheck = switch(R.string.read_aloud_label)
        readAloudRow = ui.withHelp(readAloudCheck, R.string.read_aloud_label, R.string.read_aloud_help)
        speechMediaSpinner = spinner(SpeechMedia.LABELS)
        speechText = ui.body()
        // P8.3: voice guidance. The switch is the trip panel's toggle; the rest are what
        // P8.2 saved with their defaults and read at every projection start.
        voiceGuidanceCheck = switch(R.string.voice_guidance_label)
        voiceEarlyCheck = CheckBox(this).apply { setText(R.string.voice_early) }
        voicePrepareCheck = CheckBox(this).apply { setText(R.string.voice_prepare) }
        voiceAtTurnCheck = CheckBox(this).apply { setText(R.string.voice_at_turn) }
        // In PromptTiming's and PromptDetail's order, which is how the rows below read them.
        voiceTimingSpinner = spinner(
            listOf(R.string.voice_timing_earlier, R.string.voice_timing_normal, R.string.voice_timing_later).map(::getString),
        )
        voiceDetailSpinner = spinner(listOf(R.string.voice_detail_brief, R.string.voice_detail_full).map(::getString))
        voiceStreetNamesCheck = switch(R.string.voice_street_names_label)
        voiceRerouteCheck = switch(R.string.voice_announce_reroute_label)
        voiceArrivalCheck = switch(R.string.voice_announce_arrival_label)
        sampleButton = button(R.string.voice_sample) { playSample() }
        // Announced when it changes, so the outcome is heard as well as seen.
        sampleText = ui.caption().apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        // Route preferences: the car-vario profile's switches, its top speed, and how the
        // times are shown. In RouteOptions' list orders, which is how the rows read them.
        avoidHighwaysCheck = switch(R.string.route_avoid_highways)
        avoidTollsCheck = switch(R.string.route_avoid_tolls)
        avoidUnpavedCheck = switch(R.string.route_avoid_unpaved)
        bypassTownsCheck = switch(R.string.route_bypass_towns)
        allowTracksCheck = switch(R.string.route_allow_tracks)
        topSpeedSpinner = spinner(RouteOptions.TOP_SPEEDS_KMH.map(RouteOptions::topSpeedLabel))
        etaSpinner = spinner(RouteOptions.ETA_PERCENTS.map(RouteOptions::etaLabel))
        learnPaceCheck = switch(R.string.route_learn_pace_label)
        showAlternativesCheck = switch(R.string.route_show_alternatives_label)
        // P9.1: live traffic. The key is typed into a masked field and never put back in
        // it: once saved, the card only says that a key is saved.
        trafficCheck = switch(R.string.traffic_switch)
        trafficKeyInput = EditText(this).apply {
            setHint(R.string.traffic_key_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // Not offered to an autofill service or password manager to keep.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            maxLines = 1
        }
        trafficKeyText = ui.caption()
        trafficRemoveKeyButton = quietButton(R.string.traffic_key_remove) { saveTrafficKey(null) }
        trafficTestButton = button(R.string.traffic_test) { testTraffic() }
        trafficTestText = ui.caption().apply {
            setTextIsSelectable(true)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        trafficRefreshSpinner = spinner(TrafficSettings.REFRESH_MINUTES.map { "$it min" })
        trafficLookAheadSpinner = spinner(TrafficSettings.LOOK_AHEAD_KM.map { "$it km (${Math.round(it / 1.609344)} mi)" })
        trafficLimitInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            minEms = 5
        }
        trafficLimitText = ui.caption()
        trafficStatusText = ui.body(selectable = true)
        // P10.1: weather. Off by default (APP09); the status line is the phone's
        // own words for what the last read found, place name included, shown here only.
        weatherCheck = switch(R.string.weather_switch)
        weatherStatusText = ui.body(selectable = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        weatherGrantButton = button(R.string.weather_grant) { requestWeatherPermission(REQUEST_WEATHER_GRANT) }
        weatherSpeakCheck = switch(R.string.weather_speak_label)
        weatherLevelSpinner = spinner(AlertLevel.LABELS)
        callControlsText = ui.caption().apply { setTextIsSelectable(true) }
        exclusionsText = ui.body()
        exclusionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(exclusionsText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(quietButton(R.string.notification_exclusions_choose) { chooseExclusions() })
        }
        mapText = ui.body()
        importMapButton = button(R.string.import_map) {
            AlertDialog.Builder(this)
                .setMessage(R.string.import_map_description)
                .setPositiveButton(R.string.import_map) { _, _ ->
                    try {
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("*/*"),
                            REQUEST_IMPORT_MAP,
                        )
                    } catch (e: android.content.ActivityNotFoundException) {
                        SessionController.note("no document picker available: $e")
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        routingText = ui.body()
        importRoutingButton = button(R.string.import_routing) {
            AlertDialog.Builder(this)
                .setMessage(R.string.import_routing_description)
                .setPositiveButton(R.string.import_routing) { _, _ ->
                    try {
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("*/*"),
                            REQUEST_IMPORT_ROUTING,
                        )
                    } catch (e: android.content.ActivityNotFoundException) {
                        SessionController.note("no document picker available: $e")
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        removeDataButton = button(R.string.remove_data) { removeData() }
        folderText = ui.body()
        chooseFolderButton = button(R.string.choose_drop_folder) {
            AlertDialog.Builder(this)
                .setMessage(R.string.choose_drop_folder_description)
                .setPositiveButton(R.string.choose_drop_folder) { _, _ ->
                    try {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_DROP_FOLDER)
                    } catch (e: android.content.ActivityNotFoundException) {
                        SessionController.note("no folder picker available: $e")
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        checkFolderButton = button(R.string.check_drop_folder) { DropFolder.sweep(this) }
        routeInput = EditText(this).apply {
            setHint(R.string.route_input_hint)
            // One line of input, but the hint may wrap to a second on a narrow phone
            // rather than be cut off mid-word.
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setHorizontallyScrolling(false)
            maxLines = 2
        }
        searchSuggestions = SearchSuggestions(this, ui, routeInput, ::searchPoint) { routeToSuggestion(it) }
        osmAndMapsCard = OsmAndMapsCard(this, ui) { chooseMapsFolder() }
        homeAssistantCard = HomeAssistantCard(this, ui)
        routeButton = button(R.string.compute_route) { computeRoute() }
        clearRouteButton = button(R.string.clear_route) {
            Navigation.clear()
            render()
        }
        previewButton = button(R.string.preview_route) { openPreview() }
        favoritesText = ui.title(R.string.favorites_label)
        favoritesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        saveDestinationButton = button(R.string.save_destination) { saveDestination() }
        addFavoriteButton = quietButton(R.string.add_favorite_coordinates) { addFavorite() }
        keepaliveSpinner = spinner(SessionSettings.KEEPALIVE_PROFILES)
        forgetPinsButton = button(R.string.forget_pins) { forgetPins() }
        replaceIdentityButton = button(R.string.replace_identity) { replaceIdentity() }
        importIdentityButton = button(R.string.import_identity) {
            AlertDialog.Builder(this)
                .setMessage(R.string.import_identity_description)
                .setPositiveButton(R.string.import_identity) { _, _ ->
                    try {
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                            REQUEST_IMPORT_IDENTITY,
                        )
                    } catch (e: RuntimeException) {
                        Toast.makeText(this, "Cannot open document picker", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        val noticesButton = button(R.string.notices) { showNotices() }
        // W0 (docs/connecting.md, "Wireless"): its own screen, which asks for Nearby devices
        // when it opens and not before.
        val wirelessProbeButton = button(R.string.wireless_probe_open) {
            startActivity(Intent(this, WirelessProbeActivity::class.java))
        }
        connectButton = button(R.string.connect) { connect() }
        stopButton = button(R.string.stop) { stop() }
        useListedButton = button(R.string.use_listed) { useListed() }
        val scanButton = button(R.string.scan) {
            val attempt = SessionController.attempt
            if (attempt != null && SessionController.isCurrent(attempt) && SessionController.stage == Stage.ARMED) {
                scan(attempt)
            } else {
                SessionController.note("manual enumeration: ${Accessories.enumerate(usb, filter).summary()}")
            }
        }
        val exportButton = button(R.string.export) { exportReport(REQUEST_EXPORT) }
        val exportSavedButton = button(R.string.export_saved) { exportReport(REQUEST_EXPORT_SAVED) }
        loadSaved()

        // Home: what someone who is not debugging sees.
        statusSentence = ui.primary(TextView(this)).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            // Connecting, projecting and failures are announced, not only drawn.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusActionButton = Button(this).apply { setOnClickListener { onCardAction() } }
        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.status_background)
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(16))
            addView(statusSentence)
            addView(statusActionButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        primaryButton = ui.primaryButton { onPrimary() }
        wirelessButton = button(R.string.home_wireless_connect) { connectWirelessly() }.apply {
            setOnLongClickListener {
                if (!WirelessLink.settingUp) connectWirelessly(choose = true)
                true
            }
        }
        wirelessText = ui.caption()
        routeSummaryText = ui.primary(TextView(this)).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, ui.dp(4), 0, ui.dp(4))
        }
        val favoritesHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(favoritesText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.helpButton(R.string.favorites_label, getText(R.string.favorites_help)))
        }
        val home = page(
            statusCard,
            primaryButton.also {
                it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, ui.dp(12), 0, ui.dp(6)) }
            },
            wirelessButton.also {
                it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            },
            wirelessText,
            ui.card(
                ui.title(R.string.route_title), routeSummaryText, routeInput, searchSuggestions.view, ui.row(routeButton, clearRouteButton),
                previewButton.also {
                    it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                },
            ),
            // P8.1: the engine that speaks, and where it is chosen.
            ui.card(speechText, quietButton(R.string.speech_settings) { openSpeechSettings() }),
            ui.card(
                favoritesHeader,
                ui.caption().apply { setText(R.string.favorites_hint) },
                favoritesList,
                saveDestinationButton,
                addFavoriteButton,
            ),
        )

        // Setup: the checklist, filled by renderSetup.
        setupList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val setup = page(setupList)

        // Maps & routing: import, the drop folder, the installed files, removal.
        val mapsPage = page(
            ui.card(
                ui.title(R.string.maps_title), mapText, importMapButton,
                quietButton(R.string.maps_where) { openLink(MAPS_DOWNLOAD) },
            ),
            ui.card(
                ui.title(R.string.routing_title), routingText, importRoutingButton,
                quietButton(R.string.routing_where) { openLink(ROUTING_DOWNLOAD) },
            ),
            ui.card(ui.title(R.string.drop_folder_title), folderText, ui.row(chooseFolderButton, checkFolderButton)),
            ui.card(
                ui.title(R.string.route_prefs_title),
                ui.caption().apply { setText(R.string.route_prefs_caption) },
                ui.withHelp(avoidHighwaysCheck, R.string.route_avoid_highways, R.string.route_avoid_highways_help),
                ui.withHelp(avoidTollsCheck, R.string.route_avoid_tolls, R.string.route_avoid_tolls_help),
                ui.withHelp(avoidUnpavedCheck, R.string.route_avoid_unpaved, R.string.route_avoid_unpaved_help),
                ui.withHelp(bypassTownsCheck, R.string.route_bypass_towns, R.string.route_bypass_towns_help),
                ui.withHelp(allowTracksCheck, R.string.route_allow_tracks, R.string.route_allow_tracks_help),
                ui.labelled(R.string.route_top_speed_label, R.string.route_top_speed_help, topSpeedSpinner),
                ui.labelled(R.string.route_eta_label, R.string.route_eta_help, etaSpinner),
                ui.withHelp(learnPaceCheck, R.string.route_learn_pace_label, R.string.route_learn_pace_help),
                ui.withHelp(showAlternativesCheck, R.string.route_show_alternatives_label, R.string.route_show_alternatives_help),
            ),
            // P9.1: live traffic, beside the route preferences it adds to.
            ui.card(
                ui.title(R.string.traffic_title),
                ui.caption().apply { setText(R.string.traffic_caption) },
                ui.withHelp(trafficCheck, R.string.traffic_switch, R.string.traffic_switch_help),
                ui.labelled(R.string.traffic_key_label, R.string.traffic_key_help, trafficKeyInput, stacked = true),
                trafficKeyText,
                ui.row(button(R.string.traffic_key_save) { saveTrafficKey(trafficKeyInput.text.toString()) }, trafficRemoveKeyButton),
                ui.withHelp(trafficTestButton, R.string.traffic_test, R.string.traffic_test_help),
                trafficTestText,
                ui.labelled(R.string.traffic_refresh_label, R.string.traffic_refresh_help, trafficRefreshSpinner),
                ui.labelled(R.string.traffic_look_ahead_label, R.string.traffic_look_ahead_help, trafficLookAheadSpinner),
                ui.labelled(R.string.traffic_limit_label, R.string.traffic_limit_help, trafficLimitInput),
                trafficLimitText,
                trafficStatusText,
                termsText(),
            ),
            // P8.3: Maps & routing → Voice guidance.
            ui.card(
                ui.title(R.string.voice_title),
                ui.withHelp(voiceGuidanceCheck, R.string.voice_guidance_label, R.string.voice_guidance_help),
                ui.labelled(
                    R.string.voice_prompts_label, R.string.voice_prompts_help,
                    ui.row(voiceEarlyCheck, voicePrepareCheck, voiceAtTurnCheck), stacked = true,
                ),
                ui.labelled(R.string.voice_timing_label, R.string.voice_timing_help, voiceTimingSpinner),
                ui.labelled(R.string.voice_detail_label, R.string.voice_detail_help, voiceDetailSpinner),
                ui.withHelp(voiceStreetNamesCheck, R.string.voice_street_names_label, R.string.voice_street_names_help),
                ui.withHelp(voiceRerouteCheck, R.string.voice_announce_reroute_label, R.string.voice_announce_reroute_help),
                ui.withHelp(voiceArrivalCheck, R.string.voice_announce_arrival_label, R.string.voice_announce_arrival_help),
                ui.withHelp(sampleButton, R.string.voice_sample, R.string.voice_sample_help),
                sampleText,
            ),
            ui.card(removeDataButton),
        )

        // Settings: the everyday choices, each a few words with its ⓘ.
        speedUnitRow = ui.labelled(R.string.speed_unit_label, R.string.speed_unit_help, speedUnitSpinner)
        mapSideRow = ui.labelled(R.string.map_side_label, null, mapSideSpinner)
        navigationUnitsRow = ui.labelled(R.string.navigation_units_label, null, navigationUnitsSpinner)
        navigationForceRow = ui.withHelp(navigationForceCheck, R.string.navigation_force_label, R.string.navigation_force_help)
        navigationScriptRow = ui.withHelp(navigationScriptCheck, R.string.navigation_script_label, R.string.navigation_script_help)
        weatherRows = listOf(
            weatherStatusText,
            weatherGrantButton,
            ui.withHelp(weatherSpeakCheck, R.string.weather_speak_label, R.string.weather_speak_help),
            ui.caption().apply { setText(R.string.weather_speak_note) },
            ui.labelled(R.string.weather_level_label, R.string.weather_level_help, weatherLevelSpinner),
            ui.caption().apply { text = WeatherText.NOTIFIER_HELP },
        )
        val settings = page(
            ui.card(
                ui.title(R.string.settings_dashboard),
                speedUnitRow,
                ui.labelled(R.string.clock_format_label, R.string.clock_format_help, clockFormatSpinner),
                mapSideRow,
                ui.labelled(R.string.marker_colour_label, null, markerColourSpinner),
                ui.labelled(R.string.day_night_label, R.string.day_night_help, dayNightSpinner),
            ),
            ui.card(
                ui.title(R.string.settings_connecting),
                ui.withHelp(autoStartCheck, R.string.auto_start_label, R.string.auto_start_help),
                ui.withHelp(wirelessLinkCheck, R.string.wireless_link_label, R.string.wireless_link_help),
                ui.withHelp(wirelessAutoCheck, R.string.wireless_auto_label, R.string.wireless_auto_help),
            ),
            // The identity is the user's to supply where a car refuses the generated one, so
            // its import is a setting; replacing it and the pins stay under Diagnostics.
            ui.card(
                ui.title(R.string.settings_identity),
                ui.caption().apply { setText(R.string.settings_identity_caption) },
                identityStatusText,
                importIdentityButton,
            ),
            ui.card(
                ui.title(R.string.settings_media),
                ui.withHelp(mediaInfoCheck, R.string.media_info_label, R.string.media_info_help),
                mediaAccessText,
                mediaAccessButton,
                ui.withHelp(audioCheck, R.string.audio_out_label, R.string.audio_out_help),
            ),
            ui.card(
                ui.title(R.string.settings_cluster),
                ui.withHelp(navigationCheck, R.string.navigation_cluster_label, R.string.navigation_cluster_help),
                navigationUnitsRow,
                navigationForceRow,
                navigationScriptRow,
            ),
            ui.card(
                ui.title(R.string.settings_notifications),
                ui.withHelp(notificationsOnDashboardCheck, R.string.notifications_on_dashboard, R.string.notifications_on_dashboard_help),
                exclusionsRow,
                readAloudRow,
                ui.withHelp(callControlsCheck, R.string.call_controls, R.string.call_controls_help),
                callControlsText,
            ),
            // P10.1: Settings → Weather, after the notifications an alert outranks
            // and before the speech choice its spoken alerts share.
            ui.card(
                ui.title(R.string.weather_title),
                ui.caption().apply { setText(R.string.weather_caption) },
                ui.withHelp(weatherCheck, R.string.weather_switch, R.string.weather_switch_help),
                *weatherRows.toTypedArray(),
            ),
            // Settings → Home Assistant: the dashboard's one Home Assistant button.
            homeAssistantCard.view,
            // P11.1 (docs/navigation.md): the maps folder, its status, markers and search ahead.
            osmAndMapsCard.view,
            // P8.1: applies to notification reading and, from P8.2, turn prompts.
            ui.card(
                ui.title(R.string.settings_speech),
                // Stacked: "Pause media" is cut to "Pause me…" beside its label at 360 dp.
                ui.labelled(R.string.speech_media_label, R.string.speech_media_help, speechMediaSpinner, stacked = true),
            ),
            ui.card(ui.withHelp(diagnosticsCheck, R.string.diagnostics, R.string.diagnostics_help)),
        )

        // Diagnostics: everything else, moved rather than removed.
        versionText = ui.body(selectable = true).apply { text = versionLine() }
        routeDetailText = text()
        sensorLocationRow = ui.withHelp(sensorLocationCheck, R.string.sensor_location_label, R.string.sensor_location_help)
        val diagnostics = page(
            ui.card(
                versionText, stageText, resultText, notificationText, accessoryText,
                ui.row(connectButton, stopButton), ui.row(scanButton, useListedButton),
            ),
            ui.card(
                ui.title(R.string.diagnostics_session),
                ui.labelled(R.string.profile_label, R.string.profile_help, profileSpinner, stacked = true),
                ui.labelled(R.string.stop_after_label, R.string.stop_after_help, stopAfterSpinner, stacked = true),
                ui.labelled(R.string.tls_profile_label, R.string.tls_profile_help, tlsProfileSpinner, stacked = true),
                ui.labelled(R.string.keepalive_label, R.string.keepalive_help, keepaliveSpinner, stacked = true),
                ui.withHelp(firstUseCheck, R.string.first_use_label, R.string.first_use_help),
                ui.withHelp(captureDiscoveryCheck, R.string.capture_discovery_label, R.string.capture_discovery_help),
                ui.withHelp(captureUnhandledCheck, R.string.capture_unhandled_label, R.string.capture_unhandled_help),
                ui.withHelp(captureVideoCheck, R.string.capture_video_label, R.string.capture_video_help),
                ui.withHelp(openAllChannelsCheck, R.string.open_all_channels_label, R.string.open_all_channels_help),
                ui.withHelp(resumeInPlaceCheck, R.string.resume_in_place_label, R.string.resume_in_place_help),
                ui.withHelp(congestionRestartCheck, R.string.congestion_restart_label, R.string.congestion_restart_help),
                ui.withHelp(wirelessKeepAwakeCheck, R.string.wireless_keep_awake_label, R.string.wireless_keep_awake_help),
                ui.withHelp(waitForFocusCheck, R.string.wait_for_focus_label, R.string.wait_for_focus_help),
                ui.labelled(R.string.keyframe_label, R.string.keyframe_help, keyframeSpinner, stacked = true),
                ui.labelled(R.string.setup_field_label, R.string.setup_field_help, setupFieldSpinner, stacked = true),
            ),
            ui.card(
                ui.title(R.string.diagnostics_projection),
                ui.labelled(R.string.scene_label, null, sceneSpinner, stacked = true),
                ui.labelled(R.string.scene_area_label, R.string.scene_area_help, sceneAreaSpinner, stacked = true),
                ui.labelled(R.string.sensors_label, R.string.sensors_help, sensorsSpinner, stacked = true),
                sensorLocationRow,
                ui.withHelp(softwareEncoderCheck, R.string.software_encoder_label, R.string.software_encoder_help),
                ui.labelled(R.string.video_preference_label, R.string.video_preference_help, videoPreferenceSpinner, stacked = true),
                ui.labelled(R.string.encoder_fps_label, R.string.encoder_fps_help, encoderFpsSpinner, stacked = true),
            ),
            ui.card(ui.title(R.string.route_title), routeDetailText),
            ui.card(ui.title(R.string.diagnostics_reports), savedText, ui.row(exportButton, exportSavedButton), projectionText),
            ui.card(
                ui.title(R.string.wireless_probe_title),
                ui.caption().apply { setText(R.string.wireless_probe_card) },
                wirelessProbeButton,
            ),
            ui.card(
                ui.title(R.string.diagnostics_identity),
                trustText,
                ui.row(forgetPinsButton, replaceIdentityButton),
                noticesButton,
            ),
            reportText,
        )

        pages = listOf(home, setup, mapsPage, settings, diagnostics)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            pages.forEach { addView(it) }
        }
        scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(content)
        }
        val (bar, tabViews) = ui.tabBar(
            listOf(R.string.tab_home, R.string.tab_setup, R.string.tab_maps, R.string.tab_settings, R.string.diagnostics),
        ) { index ->
            selectTab(index)
            renderState()
        }
        tabs = tabViews
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Edge to edge, as before P7.7: the content clears the status bar and cutout,
            // and the tab bar clears the navigation bar, or the keyboard while it is up.
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                scroll.setPadding(bars.left + margin, bars.top + margin, bars.right + margin, margin)
                bar.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
                insets
            }
        }
        setContentView(root)
        selectTab(currentTab)
    }

    /** One tab's content: a column the tab bar shows or hides whole. */
    private fun page(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it) }
    }

    /**
     * Puts every saved choice on screen, then makes each control save its own field as it
     * changes.
     *
     * One field per write, through [AppSettings.update], rather than the whole screen at
     * once: the dashboard saves day/night and the notification pause from the head unit
     * while this screen sits behind it, and a whole-screen write would put back whatever
     * this screen last showed. Listeners are attached only after every control holds its
     * saved value, so loading writes nothing; a spinner's first layout reports its
     * selection once, which stores the value it already had.
     *
     * First-use trust, the capture boxes and position in the report are not here: they
     * stay per attempt.
     */
    private fun loadSaved() {
        val saved = AppSettings.load(this)
        profileSpinner.setSelection(PROFILES.indexOf(saved.profile))
        stopAfterSpinner.setSelection(SessionSettings.STOP_AFTER.indexOf(saved.stopAfter))
        tlsProfileSpinner.setSelection(SessionSettings.TLS_PROFILES.indexOf(saved.tlsProfile))
        keepaliveSpinner.setSelection(SessionSettings.KEEPALIVE_PROFILES.indexOf(saved.keepalive))
        openAllChannelsCheck.isChecked = saved.openAllChannels
        resumeInPlaceCheck.isChecked = saved.resumeInPlace
        congestionRestartCheck.isChecked = saved.congestionRestart
        wirelessKeepAwakeCheck.isChecked = saved.wirelessKeepAwake
        waitForFocusCheck.isChecked = saved.waitForFocus
        keyframeSpinner.setSelection(SessionSettings.KEYFRAME_SECONDS.indexOf(saved.keyframeSeconds))
        setupFieldSpinner.setSelection(SessionSettings.SETUP_FIELDS.indexOf(saved.setupField))
        sensorsSpinner.setSelection(SessionSettings.SENSOR_SETS.indexOf(saved.sensors))
        mediaInfoCheck.isChecked = saved.mediaInfo
        audioCheck.isChecked = saved.audio
        navigationCheck.isChecked = saved.navigation
        navigationUnitsSpinner.setSelection(SessionSettings.NAVIGATION_UNITS.indexOf(saved.navigationUnits))
        navigationForceCheck.isChecked = saved.navigationForce
        navigationScriptCheck.isChecked = saved.navigationScript
        softwareEncoderCheck.isChecked = saved.softwareEncoder
        videoPreferenceSpinner.setSelection(SessionSettings.VIDEO_PREFERENCES.indexOf(saved.videoPreference))
        encoderFpsSpinner.setSelection(SessionSettings.ENCODER_RATES.indexOf(saved.encoderFps))
        sceneAreaSpinner.setSelection(SessionSettings.SCENE_AREAS.indexOf(saved.sceneArea))
        sceneSpinner.setSelection(SessionSettings.SCENES.indexOf(saved.scene))
        mapSideSpinner.setSelection(if (saved.mapOnLeft) 0 else 1)
        speedUnitSpinner.setSelection(saved.speedUnit.ordinal)
        autoStartCheck.isChecked = saved.autoStart
        wirelessAutoCheck.isChecked = saved.wirelessAutoStart
        diagnosticsCheck.isChecked = saved.diagnostics
        markerColourSpinner.setSelection(saved.markerColour.ordinal)
        clockFormatSpinner.setSelection(saved.clockFormat.ordinal)
        dayNightSpinner.setSelection(saved.dayNight.ordinal)
        notificationsOnDashboardCheck.isChecked = saved.notificationsOnDashboard
        callControlsCheck.isChecked = saved.callControls
        readAloudCheck.isChecked = saved.readAloud
        speechMediaSpinner.setSelection(saved.speechMedia.ordinal)
        voiceGuidanceCheck.isChecked = saved.voice.enabled
        voiceEarlyCheck.isChecked = saved.voice.early
        voicePrepareCheck.isChecked = saved.voice.prepare
        voiceAtTurnCheck.isChecked = saved.voice.atTurn
        voiceTimingSpinner.setSelection(saved.voice.timing.ordinal)
        voiceDetailSpinner.setSelection(saved.voice.detail.ordinal)
        voiceStreetNamesCheck.isChecked = saved.voice.streetNames
        voiceRerouteCheck.isChecked = saved.voice.announceReroute
        voiceArrivalCheck.isChecked = saved.voice.announceArrival
        avoidHighwaysCheck.isChecked = saved.routing.avoidHighways
        avoidTollsCheck.isChecked = saved.routing.avoidTolls
        avoidUnpavedCheck.isChecked = saved.routing.avoidUnpaved
        bypassTownsCheck.isChecked = saved.routing.bypassTowns
        allowTracksCheck.isChecked = saved.routing.allowTracks
        topSpeedSpinner.setSelection(RouteOptions.TOP_SPEEDS_KMH.indexOf(saved.routing.topSpeedKmh))
        etaSpinner.setSelection(RouteOptions.ETA_PERCENTS.indexOf(saved.routing.etaPercent))
        learnPaceCheck.isChecked = saved.routing.learnPace
        showAlternativesCheck.isChecked = saved.routing.showAlternatives

        fun Spinner.saves(change: (SavedSettings, Int) -> SavedSettings) {
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    AppSettings.update(this@ConnectionActivity) { change(it, position) }
                    renderState()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    renderState()
                }
            }
        }
        fun CompoundButton.saves(change: (SavedSettings, Boolean) -> SavedSettings) {
            setOnCheckedChangeListener { _, checked ->
                AppSettings.update(this@ConnectionActivity) { change(it, checked) }
                renderState()
            }
        }
        profileSpinner.saves { s, i -> s.copy(profile = PROFILES[i]) }
        stopAfterSpinner.saves { s, i -> s.copy(stopAfter = SessionSettings.STOP_AFTER[i]) }
        tlsProfileSpinner.saves { s, i -> s.copy(tlsProfile = SessionSettings.TLS_PROFILES[i]) }
        keepaliveSpinner.saves { s, i -> s.copy(keepalive = SessionSettings.KEEPALIVE_PROFILES[i]) }
        openAllChannelsCheck.saves { s, on -> s.copy(openAllChannels = on) }
        resumeInPlaceCheck.saves { s, on -> s.copy(resumeInPlace = on) }
        congestionRestartCheck.saves { s, on -> s.copy(congestionRestart = on) }
        wirelessKeepAwakeCheck.saves { s, on -> s.copy(wirelessKeepAwake = on) }
        waitForFocusCheck.saves { s, on -> s.copy(waitForFocus = on) }
        keyframeSpinner.saves { s, i -> s.copy(keyframeSeconds = SessionSettings.KEYFRAME_SECONDS[i]) }
        setupFieldSpinner.saves { s, i -> s.copy(setupField = SessionSettings.SETUP_FIELDS[i]) }
        sensorsSpinner.saves { s, i -> s.copy(sensors = SessionSettings.SENSOR_SETS[i]) }
        mediaInfoCheck.saves { s, on -> s.copy(mediaInfo = on) }
        audioCheck.saves { s, on -> s.copy(audio = on) }
        navigationCheck.saves { s, on -> s.copy(navigation = on) }
        navigationUnitsSpinner.saves { s, i -> s.copy(navigationUnits = SessionSettings.NAVIGATION_UNITS[i]) }
        navigationForceCheck.saves { s, on -> s.copy(navigationForce = on) }
        navigationScriptCheck.saves { s, on -> s.copy(navigationScript = on) }
        softwareEncoderCheck.saves { s, on -> s.copy(softwareEncoder = on) }
        videoPreferenceSpinner.saves { s, i -> s.copy(videoPreference = SessionSettings.VIDEO_PREFERENCES[i]) }
        encoderFpsSpinner.saves { s, i -> s.copy(encoderFps = SessionSettings.ENCODER_RATES[i]) }
        sceneAreaSpinner.saves { s, i -> s.copy(sceneArea = SessionSettings.SCENE_AREAS[i]) }
        sceneSpinner.saves { s, i -> s.copy(scene = SessionSettings.SCENES[i]) }
        mapSideSpinner.saves { s, i -> s.copy(mapOnLeft = i == 0) }
        speedUnitSpinner.saves { s, i -> s.copy(speedUnit = SpeedUnit.entries[i]) }
        autoStartCheck.saves { s, on -> s.copy(autoStart = on) }
        wirelessAutoCheck.saves { s, on -> s.copy(wirelessAutoStart = on) }
        // Turning the switch either way ends a reveal from the status card: the switch
        // is the operator's answer to whether the tab should be there.
        diagnosticsCheck.saves { s, on ->
            diagnosticsRevealed = false
            s.copy(diagnostics = on)
        }
        markerColourSpinner.saves { s, i -> s.copy(markerColour = MarkerColour.entries[i]) }
        clockFormatSpinner.saves { s, i -> s.copy(clockFormat = ClockFormat.entries[i]) }
        dayNightSpinner.saves { s, i -> s.copy(dayNight = DayNightMode.entries[i]) }
        notificationsOnDashboardCheck.saves { s, on -> s.copy(notificationsOnDashboard = on) }
        callControlsCheck.saves { s, on -> s.copy(callControls = on) }
        readAloudCheck.saves { s, on -> s.copy(readAloud = on) }
        speechMediaSpinner.saves { s, i -> s.copy(speechMedia = SpeechMedia.entries[i]) }
        // P8.3: the voice settings, saved the same way and handed to Guide as they change,
        // so a projection under way takes them without a restart. Guide passes the on/off
        // to Speaker and the trip panel only when it differs from what they already have.
        fun CompoundButton.savesVoice(change: (VoiceSettings, Boolean) -> VoiceSettings) {
            setOnCheckedChangeListener { _, checked ->
                val stored = AppSettings.update(this@ConnectionActivity) { it.copy(voice = change(it.voice, checked)) }
                Guide.voiceChanged(stored.voice)
                renderState()
            }
        }
        fun Spinner.savesVoice(change: (VoiceSettings, Int) -> VoiceSettings) {
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val stored = AppSettings.update(this@ConnectionActivity) { it.copy(voice = change(it.voice, position)) }
                    Guide.voiceChanged(stored.voice)
                    renderState()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    renderState()
                }
            }
        }
        voiceGuidanceCheck.savesVoice { v, on -> v.copy(enabled = on) }
        voiceEarlyCheck.savesVoice { v, on -> v.copy(early = on) }
        voicePrepareCheck.savesVoice { v, on -> v.copy(prepare = on) }
        voiceAtTurnCheck.savesVoice { v, on -> v.copy(atTurn = on) }
        voiceTimingSpinner.savesVoice { v, i -> v.copy(timing = PromptTiming.entries[i]) }
        voiceDetailSpinner.savesVoice { v, i -> v.copy(detail = PromptDetail.entries[i]) }
        voiceStreetNamesCheck.savesVoice { v, on -> v.copy(streetNames = on) }
        voiceRerouteCheck.savesVoice { v, on -> v.copy(announceReroute = on) }
        voiceArrivalCheck.savesVoice { v, on -> v.copy(announceArrival = on) }
        // Route preferences, saved the same way. A change that can alter the route
        // recomputes the one shown (Navigation.optionsChanged); a spinner's first
        // selection on layout stores what was already saved and so recomputes nothing.
        fun saveRouting(change: (RouteOptions) -> RouteOptions) {
            val previous = AppSettings.load(this).routing
            val stored = AppSettings.update(this) { it.copy(routing = change(it.routing)) }
            Navigation.optionsChanged(this, previous, stored.routing)
            renderState()
        }
        fun CompoundButton.savesRouting(change: (RouteOptions, Boolean) -> RouteOptions) {
            setOnCheckedChangeListener { _, checked -> saveRouting { change(it, checked) } }
        }
        fun Spinner.savesRouting(change: (RouteOptions, Int) -> RouteOptions) {
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    saveRouting { change(it, position) }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    renderState()
                }
            }
        }
        avoidHighwaysCheck.savesRouting { r, on -> r.copy(avoidHighways = on) }
        avoidTollsCheck.savesRouting { r, on -> r.copy(avoidTolls = on) }
        avoidUnpavedCheck.savesRouting { r, on -> r.copy(avoidUnpaved = on) }
        bypassTownsCheck.savesRouting { r, on -> r.copy(bypassTowns = on) }
        allowTracksCheck.savesRouting { r, on -> r.copy(allowTracks = on) }
        topSpeedSpinner.savesRouting { r, i -> r.copy(topSpeedKmh = RouteOptions.TOP_SPEEDS_KMH[i]) }
        etaSpinner.savesRouting { r, i -> r.copy(etaPercent = RouteOptions.ETA_PERCENTS[i]) }
        learnPaceCheck.savesRouting { r, on -> r.copy(learnPace = on) }
        showAlternativesCheck.savesRouting { r, on -> r.copy(showAlternatives = on) }
        loadTraffic()
        loadWeather()
    }

    companion object {
        /** Package-scoped action for the USB permission result broadcast. */
        const val ACTION_USB_PERMISSION = "com.jw6ventures.headlink.action.USB_PERMISSION"
        /** The version profiles; see [SavedSettings.PROFILES], which owns the list since P7.1. */
        val PROFILES = SavedSettings.PROFILES
        const val NOTICES_ASSET = "NOTICES.txt"

        /** Lab transport extras; honored only in a debug build (see [handleLabIntent]). */
        const val EXTRA_LAB_SOCKET = "lab_socket"
        const val EXTRA_LAB_WIRELESS_TCP = "lab_wireless_tcp"
        const val EXTRA_LAB_ROUTE = "lab_route"
        const val EXTRA_LAB_FAVORITES = "lab_favorites"
        const val EXTRA_LAB_RECENT = "lab_recent"
        const val EXTRA_LAB_STOP_AFTER = "lab_stop_after"
        const val EXTRA_LAB_PROFILE = "lab_profile"
        const val EXTRA_LAB_KEEPALIVE = "lab_keepalive"
        const val EXTRA_LAB_PROJECTION_MS = "lab_projection_ms"
        const val EXTRA_LAB_SENSORS = "lab_sensors"
        const val EXTRA_LAB_SENSOR_LOCATION = "lab_sensor_location"
        const val EXTRA_LAB_AUDIO = "lab_audio"
        const val EXTRA_LAB_NAVIGATION = "lab_navigation"
        const val EXTRA_LAB_NAVIGATION_FORCE = "lab_navigation_force"
        const val EXTRA_LAB_NAVIGATION_SCRIPT = "lab_navigation_script"
        const val EXTRA_LAB_SOFTWARE_ENCODER = "lab_software_encoder"
        const val EXTRA_LAB_VIDEO_PREFERENCE = "lab_video_preference"
        const val EXTRA_LAB_ENCODER_FPS = "lab_encoder_fps"
        const val EXTRA_LAB_SCENE_AREA = "lab_scene_area"
        const val EXTRA_LAB_CAPTURE_UNHANDLED = "lab_capture_unhandled"
        const val EXTRA_LAB_CAPTURE_VIDEO = "lab_capture_video"
        const val EXTRA_LAB_OPEN_ALL_CHANNELS = "lab_open_all_channels"
        const val EXTRA_LAB_RESUME_IN_PLACE = "lab_resume_in_place"
        const val EXTRA_LAB_CONGESTION_RESTART = "lab_congestion_restart"
        const val EXTRA_LAB_WAIT_FOR_FOCUS = "lab_wait_for_focus"
        const val EXTRA_LAB_KEYFRAME_SECONDS = "lab_keyframe_seconds"
        const val EXTRA_LAB_SETUP_FIELD = "lab_setup_field"
        const val EXTRA_LAB_SPEECH_BLUETOOTH = "lab_speech_bluetooth"
        /** P9 lab: live traffic on or off (boolean); on also marks what it sends as seen. */
        const val EXTRA_LAB_TRAFFIC = "lab_traffic"
        /** P9 lab: the stand-in server's base URL, e.g. `http://10.0.2.2:8765`; "" for HERE. */
        const val EXTRA_LAB_TRAFFIC_URL = "lab_traffic_url"
        /** P9 lab: the key to send; the stand-in's own, never a real one. */
        const val EXTRA_LAB_TRAFFIC_KEY = "lab_traffic_key"
        /** P9 lab: the monthly request limit (int). */
        const val EXTRA_LAB_TRAFFIC_LIMIT = "lab_traffic_limit"
        /** P9 lab: refresh every, minutes (int, one of the card's choices). */
        const val EXTRA_LAB_TRAFFIC_REFRESH = "lab_traffic_refresh"
        /** P9 lab: clear the month's count, the last fetch and the last error (boolean). */
        const val EXTRA_LAB_TRAFFIC_RESET = "lab_traffic_reset"
        /**
         * P9.6 lab: how the turn card divides for a traffic offer, `stacked` or
         * `side-by-side` ([OfferLayout.key]), set before the projection's layout is made so
         * the lab can render both for James. Debug builds only.
         */
        const val EXTRA_LAB_OFFER_LAYOUT = "lab_offer_layout"
        /** P9 lab: refresh the projection's part of the saved report now (boolean, any value). Debug builds only. */
        const val EXTRA_LAB_REPORT = "lab_report"
        const val EXTRA_LAB_WIRELESS_OBSERVE = "lab_wireless_observe"
        /** P10 lab: Show weather on or off (boolean). Never asks for Breezy's permission. */
        const val EXTRA_LAB_WEATHER = "lab_weather"
        /** P10 lab: alerts shown from, by [AlertLevel.key]: minor, moderate, severe or extreme. */
        const val EXTRA_LAB_WEATHER_LEVEL = "lab_weather_level"
        /** P10 lab: speak weather alerts (boolean). */
        const val EXTRA_LAB_WEATHER_SPEAK = "lab_weather_speak"
        /** P10 lab: read now ([WeatherCue.LAB]), as the notifier would (boolean true). */
        const val EXTRA_LAB_WEATHER_CUE = "lab_weather_cue"
        /** P10.0: the provider's answers into `files/weather-capture/` for `run-as` (boolean true). */
        const val EXTRA_LAB_WEATHER_CAPTURE = "lab_weather_capture"
        private const val REQUEST_NOTIFICATIONS = 1
        private const val REQUEST_EXPORT = 2
        private const val REQUEST_EXPORT_SAVED = 3
        private const val REQUEST_IMPORT_IDENTITY = 4
        private const val REQUEST_IMPORT_MAP = 5
        private const val REQUEST_IMPORT_ROUTING = 6
        private const val REQUEST_DROP_FOLDER = 7
        private const val REQUEST_SETUP_LOCATION = 8
        private const val REQUEST_PREVIEW = 9
        private const val REQUEST_WEATHER = 10
        private const val REQUEST_WEATHER_GRANT = 11
        private const val REQUEST_MAPS_FOLDER = 12
        private const val REQUEST_WIRELESS = 13
        private const val REQUEST_WIRELESS_LINK = 14
        /** This screen's hold on OsmAnd's open maps ([ObfLibrary.hold]). */
        private const val MAPS_HOLDER = "screen"

        /** The tabs, in the bar's order (P7.7). */
        private const val TAB_HOME = 0
        private const val TAB_SETUP = 1
        private const val TAB_MAPS = 2
        private const val TAB_SETTINGS = 3
        private const val TAB_DIAGNOSTICS = 4
        private const val STATE_TAB = "tab"
        private const val MAPS_DOWNLOAD = "https://download.mapsforge.org/maps/v5/"
        private const val ROUTING_DOWNLOAD = "https://brouter.de/brouter/segments4/"

        /** Map side choices, in the order [AttemptSettings.mapOnLeft] reads them. */
        val MAP_SIDES = listOf("map left", "map right")
    }
}
