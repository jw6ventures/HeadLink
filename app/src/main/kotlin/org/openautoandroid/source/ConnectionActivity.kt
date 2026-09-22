package org.openautoandroid.source

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

/**
 * User-initiated connection: profile, stop stage, TLS profile, and certificate-trial
 * controls; Connect/Stop; accessory enumeration and filter diagnostics; USB and
 * notification permission; and report export. The activity never touches the accessory
 * descriptor; after permission it hands the attempt to [ProjectionService]. Only small
 * binder calls and file reads run on the UI thread.
 */
class ConnectionActivity : Activity() {
    private lateinit var usb: UsbManager
    private lateinit var filter: AccessoryFilter
    private val main = Handler(Looper.getMainLooper())
    private val render: () -> Unit = { renderState() }

    private lateinit var stageText: TextView
    private lateinit var notificationText: TextView
    private lateinit var accessoryText: TextView
    private lateinit var resultText: TextView
    private lateinit var savedText: TextView
    private lateinit var trustText: TextView
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
    private lateinit var waitForFocusCheck: CheckBox
    private lateinit var keyframeSpinner: Spinner
    private lateinit var setupFieldSpinner: Spinner
    private lateinit var sensorsSpinner: Spinner
    private lateinit var sensorLocationCheck: CheckBox
    private lateinit var mediaInfoCheck: CheckBox
    private lateinit var audioCheck: CheckBox
    private lateinit var navigationCheck: CheckBox
    private lateinit var navigationUnitsSpinner: Spinner
    private lateinit var navigationForceCheck: CheckBox
    private lateinit var navigationScriptCheck: CheckBox
    private lateinit var mediaAccessText: TextView
    private lateinit var mediaAccessButton: Button
    private lateinit var softwareEncoderCheck: CheckBox
    private lateinit var videoPreferenceSpinner: Spinner
    private lateinit var encoderFpsSpinner: Spinner
    private lateinit var sceneAreaSpinner: Spinner
    private lateinit var sceneSpinner: Spinner
    private lateinit var mapSideSpinner: Spinner
    private lateinit var speedUnitSpinner: Spinner

    /** Held, unlike the other spinners' labels, because this row hides with the scene. */
    private lateinit var speedUnitLabel: TextView
    private lateinit var mapText: TextView
    private lateinit var importMapButton: Button
    private lateinit var routingText: TextView
    private lateinit var folderText: TextView
    private lateinit var chooseFolderButton: Button
    private lateinit var checkFolderButton: Button
    private lateinit var importRoutingButton: Button
    private lateinit var removeDataButton: Button
    private lateinit var routeInput: EditText
    private lateinit var routeButton: Button
    private lateinit var clearRouteButton: Button
    private lateinit var keepaliveSpinner: Spinner
    private lateinit var forgetPinsButton: Button
    private lateinit var replaceIdentityButton: Button
    private lateinit var importIdentityButton: Button
    private lateinit var connectButton: Button
    private lateinit var stopButton: Button
    private lateinit var useListedButton: Button

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
        buildViews()
        registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_NOT_EXPORTED)
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), RECEIVER_NOT_EXPORTED)
        SessionController.addListener(render)
        if (!handleLabIntent(intent)) handleAttachIntent(intent)
        renderState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!handleLabIntent(intent)) handleAttachIntent(intent)
    }

    /**
     * Notification access may have been granted or withdrawn in Settings meanwhile, and
     * files may have landed in the drop folder.
     */
    override fun onResume() {
        super.onResume()
        DropFolder.sweep(this)
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
        val socket = intent?.getStringExtra(EXTRA_LAB_SOCKET)
        if (socket == null) {
            if (route == null) return false
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

    override fun onDestroy() {
        SessionController.removeListener(render)
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(detachReceiver)
        super.onDestroy()
    }

    private fun handleAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        // Consume it so a recreated activity does not act on the same attach again.
        setIntent(Intent(this, ConnectionActivity::class.java))
        val extra = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        val current = Accessories.current(usb, extra)
        val attempt = SessionController.attempt
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

    private fun connect() {
        if (SessionController.identityBusy) return
        val stopAfter = stopAfterSpinner.selectedItem as String
        if (stopAfter != SessionSettings.STOP_AFTER_VERSION && !SessionController.identityReady) {
            // The identity is prepared before attachment, never while a head unit waits.
            Toast.makeText(this, R.string.identity_not_ready, Toast.LENGTH_LONG).show()
            return
        }
        val armed = firstUseCheck.isChecked && stopAfter != SessionSettings.STOP_AFTER_VERSION
        // The discovery response only arrives when the attempt runs discovery.
        val capture = captureDiscoveryCheck.isChecked && stopAfter == SessionSettings.STOP_AFTER_DISCOVERY
        val settings = AttemptSettings(
            profile = profileSpinner.selectedItem as String,
            stopAfter = stopAfter,
            tlsProfile = tlsProfileSpinner.selectedItem as String,
            armedFirstUse = armed,
            pins = IdentityWork.trust(this).pins(),
            captureDiscovery = capture,
            captureUnhandled = captureUnhandledCheck.isChecked && stopAfter != SessionSettings.STOP_AFTER_VERSION,
            captureVideo = captureVideoCheck.isChecked && stopAfter == SessionSettings.STOP_AFTER_PROJECTION,
            openAllChannels = openAllChannelsCheck.isChecked && stopAfter == SessionSettings.STOP_AFTER_PROJECTION,
            rebuildOnRegain = !resumeInPlaceCheck.isChecked,
            requestFocus = !(waitForFocusCheck.isChecked && stopAfter == SessionSettings.STOP_AFTER_PROJECTION),
            keyframeSeconds = SessionSettings.KEYFRAME_SECONDS[keyframeSpinner.selectedItemPosition],
            setupField = setupFieldSpinner.selectedItem as String,
            keepalive = keepaliveSpinner.selectedItem as String,
            sensors = if (stopAfter == SessionSettings.STOP_AFTER_PROJECTION) {
                sensorsSpinner.selectedItem as String
            } else {
                SessionSettings.SENSORS_NONE
            },
            sensorLocation = sensorLocationCheck.isChecked &&
                stopAfter == SessionSettings.STOP_AFTER_PROJECTION &&
                sensorsSpinner.selectedItem != SessionSettings.SENSORS_NONE,
            mediaInfo = mediaInfoCheck.isChecked && stopAfter == SessionSettings.STOP_AFTER_PROJECTION,
            audio = audioCheck.isChecked && stopAfter == SessionSettings.STOP_AFTER_PROJECTION,
            navigation = navigationCheck.isChecked && stopAfter == SessionSettings.STOP_AFTER_PROJECTION,
            navigationUnits = SessionSettings.NAVIGATION_UNITS[navigationUnitsSpinner.selectedItemPosition],
            navigationForce = navigationForceCheck.isChecked && navigationCheck.isChecked &&
                stopAfter == SessionSettings.STOP_AFTER_PROJECTION,
            navigationScript = navigationScriptCheck.isChecked && navigationCheck.isChecked,
            softwareEncoder = softwareEncoderCheck.isChecked && stopAfter == SessionSettings.STOP_AFTER_PROJECTION,
            videoPreference = SessionSettings.VIDEO_PREFERENCES[videoPreferenceSpinner.selectedItemPosition],
            encoderFps = if (stopAfter == SessionSettings.STOP_AFTER_PROJECTION) {
                SessionSettings.ENCODER_RATES[encoderFpsSpinner.selectedItemPosition]
            } else {
                0
            },
            sceneArea = sceneAreaSpinner.selectedItem as String,
            scene = sceneSpinner.selectedItem as String,
            mapOnLeft = mapSideSpinner.selectedItemPosition == 0,
            speedUnit = SpeedUnit.entries[speedUnitSpinner.selectedItemPosition],
        )
        val attempt = SessionController.begin(settings) ?: return
        // First-use trust is scoped to this one attempt.
        firstUseCheck.isChecked = false
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
            REQUEST_EXPORT_SAVED -> "openautoandroid-saved-reports.txt"
            else -> SessionController.attempt?.let(ReportPersistence::reportName) ?: "openautoandroid-report.txt"
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
        if (requestCode == REQUEST_DROP_FOLDER) {
            try {
                DropFolder.choose(this, uri)
            } catch (e: SecurityException) {
                Toast.makeText(this, getString(R.string.drop_folder_failed, e.message ?: e.toString()), Toast.LENGTH_LONG).show()
            }
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
            captureVideoCheck, openAllChannelsCheck, resumeInPlaceCheck, waitForFocusCheck,
            keyframeSpinner, setupFieldSpinner,
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
        sensorLocationCheck.visibility =
            if (sensorsSpinner.selectedItem == SessionSettings.SENSORS_NONE) View.GONE else View.VISIBLE
        mediaInfoCheck.isEnabled =
            idle && stopAfterSpinner.selectedItem == SessionSettings.STOP_AFTER_PROJECTION
        audioCheck.isEnabled =
            idle && stopAfterSpinner.selectedItem == SessionSettings.STOP_AFTER_PROJECTION
        navigationCheck.isEnabled =
            idle && stopAfterSpinner.selectedItem == SessionSettings.STOP_AFTER_PROJECTION
        // The unit choice only means something when the cluster is being driven.
        navigationUnitsSpinner.isEnabled = navigationCheck.isEnabled && navigationCheck.isChecked
        navigationUnitsSpinner.visibility =
            if (navigationCheck.isChecked) View.VISIBLE else View.GONE
        navigationForceCheck.isEnabled = navigationUnitsSpinner.isEnabled
        navigationForceCheck.visibility = navigationUnitsSpinner.visibility
        navigationScriptCheck.isEnabled = navigationUnitsSpinner.isEnabled
        navigationScriptCheck.visibility = navigationUnitsSpinner.visibility
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
        mapSideSpinner.visibility = if (dashboard) View.VISIBLE else View.GONE
        // The unit is the dashboard header's; the demo scene draws no speed at all.
        speedUnitSpinner.isEnabled = sceneSpinner.isEnabled && dashboard
        speedUnitSpinner.visibility = if (dashboard) View.VISIBLE else View.GONE
        speedUnitLabel.visibility = speedUnitSpinner.visibility
        mapText.visibility = if (dashboard) View.VISIBLE else View.GONE
        importMapButton.visibility = if (dashboard) View.VISIBLE else View.GONE
        // Every installed map is drawn, so every one is listed.
        mapText.text = MapData.installed(this).takeIf { it.isNotEmpty() }?.let { maps ->
            getString(R.string.map_installed, maps.joinToString(", ") { it.name }, maps.sumOf { it.length() } / (1L shl 20))
        } ?: getString(R.string.map_none)
        // Routing is not tied to an attempt or a scene: a route can be computed with no head
        // unit attached, which is how its cost on the phone is measured.
        val tiles = Routing.installed(this)
        routingText.text = buildString {
            append(
                if (tiles.isEmpty()) getString(R.string.routing_none)
                else getString(R.string.routing_installed, tiles.joinToString(", ") { it.name }),
            )
            // The destination's name is the operator's own and may be shown here; the
            // report beside it carries only which source set it.
            Navigation.destination?.let { destination ->
                append("\n").append(
                    getString(
                        if (Navigation.waiting) R.string.destination_line_waiting else R.string.destination_line,
                        Navigation.describe(destination),
                        Navigation.source?.label ?: "-",
                    ),
                )
            }
            Routing.summary()?.let { append("\n").append(getString(R.string.route_line, it)) }
        }
        clearRouteButton.isEnabled = Navigation.destination != null || Routing.line != null || Routing.pending != 0L
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
        stopButton.isEnabled = stage != Stage.IDLE && stage != Stage.STOPPING
        useListedButton.visibility =
            if (stage == Stage.ARMED && attempt?.mismatchCandidate != null) View.VISIBLE else View.GONE
        reportText.text = SessionReport.compose(this, usb, filter)
        projectionText.text = SessionController.projectionReport.ifEmpty { getString(R.string.no_projection) }
    }

    private fun buildViews() {
        val margin = (16 * resources.displayMetrics.density).toInt()
        fun text(monospace: Boolean = false) = TextView(this).apply {
            setTextIsSelectable(true)
            if (monospace) typeface = Typeface.MONOSPACE
            setPadding(0, margin / 2, 0, margin / 2)
        }
        fun button(label: Int, action: () -> Unit) = Button(this).apply {
            setText(label)
            setOnClickListener { action() }
        }
        stageText = text().apply { textSize = 20f }
        trustText = text(monospace = true)
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
        stopAfterSpinner = spinner(SessionSettings.STOP_AFTER).apply {
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    renderState()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    renderState()
                }
            }
        }
        tlsProfileSpinner = spinner(SessionSettings.TLS_PROFILES)
        firstUseCheck = CheckBox(this).apply { setText(R.string.first_use) }
        captureDiscoveryCheck = CheckBox(this).apply { setText(R.string.capture_discovery) }
        captureUnhandledCheck = CheckBox(this).apply { setText(R.string.capture_unhandled) }
        captureVideoCheck = CheckBox(this).apply { setText(R.string.capture_video) }
        openAllChannelsCheck = CheckBox(this).apply { setText(R.string.open_all_channels) }
        resumeInPlaceCheck = CheckBox(this).apply { setText(R.string.resume_in_place) }
        waitForFocusCheck = CheckBox(this).apply { setText(R.string.wait_for_focus) }
        keyframeSpinner = spinner(SessionSettings.KEYFRAME_SECONDS.map { "$it s" })
        setupFieldSpinner = spinner(SessionSettings.SETUP_FIELDS)
        sensorsSpinner = spinner(SessionSettings.SENSOR_SETS).apply {
            // Starts on the whole advertised set rather than the head of the list: the
            // operator chooses `none` for an attempt that must change nothing.
            setSelection(SessionSettings.SENSOR_SETS.indexOf(SessionSettings.SENSORS_ALL))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    renderState()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    renderState()
                }
            }
        }
        // APP09 and spec §10: position is offered on its own, with what it means stated,
        // and it is never on unless the operator turned it on for this attempt.
        sensorLocationCheck = CheckBox(this).apply { setText(R.string.sensor_location) }
        mediaInfoCheck = CheckBox(this).apply {
            setText(R.string.media_info)
            // On by default; it adds no Android permission of its own, and the grant it can
            // use is still asked for separately below.
            isChecked = true
            setOnCheckedChangeListener { _, _ -> renderState() }
        }
        // APP09 again: audio out is offered on its own. It adds no Android permission —
        // the audio is this app's own and goes out over USB — so there is nothing to send
        // the operator to Settings for, only a choice to make.
        audioCheck = CheckBox(this).apply { setText(R.string.audio_out) }
        // APP09 once more: turn-by-turn on the vehicle's own cluster is its own choice. It
        // needs no Android permission — the maneuvers are the followed route's (P6.5), or
        // P6.2's script — and a head unit that refuses the channel affects nothing else.
        navigationCheck = CheckBox(this).apply {
            setText(R.string.navigation_cluster)
            setOnCheckedChangeListener { _, _ -> renderState() }
        }
        navigationUnitsSpinner = spinner(SessionSettings.NAVIGATION_UNITS)
        // Offered beside the cluster box rather than on its own, because on its own it
        // means nothing: it changes what happens when the head unit declines to answer,
        // and a head unit that answers never reaches it.
        navigationForceCheck = CheckBox(this).apply { setText(R.string.navigation_force) }
        navigationScriptCheck = CheckBox(this).apply { setText(R.string.navigation_script) }
        mediaAccessText = TextView(this)
        // APP09: the integration is offered on its own, and the permission it needs is
        // explained before the operator is sent to Settings to grant it. Nothing here can
        // request the grant directly — a notification listener binding is made in
        // Settings — so the button only opens that screen.
        mediaAccessButton = button(R.string.media_access_grant) {
            val seconds = resources.getQuantityString(
                R.plurals.media_access_seconds, MediaControl.SEEK_SECONDS, MediaControl.SEEK_SECONDS,
            )
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.media_access_prompt, seconds))
                .setPositiveButton(R.string.media_access_grant) { _, _ -> openNotificationAccess() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        softwareEncoderCheck = CheckBox(this).apply { setText(R.string.software_encoder) }
        videoPreferenceSpinner = spinner(SessionSettings.VIDEO_PREFERENCES)
        encoderFpsSpinner = spinner(
            SessionSettings.ENCODER_RATES.map { if (it == 0) getString(R.string.encoder_fps_negotiated) else "$it" },
        )
        sceneAreaSpinner = spinner(SessionSettings.SCENE_AREAS)
        sceneSpinner = spinner(SessionSettings.SCENES)
        mapSideSpinner = spinner(MAP_SIDES)
        // Opens on the first entry, which is why [SpeedUnit] lists mph first.
        speedUnitSpinner = spinner(SpeedUnit.LABELS)
        speedUnitLabel = TextView(this).apply { setText(R.string.speed_unit_label) }
        mapText = TextView(this)
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
        routingText = TextView(this)
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
        folderText = TextView(this)
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
            isSingleLine = true
        }
        routeButton = button(R.string.compute_route) { computeRoute() }
        clearRouteButton = button(R.string.clear_route) {
            Navigation.clear()
            render()
        }
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
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(stageText)
            addView(TextView(this@ConnectionActivity).apply { setText(R.string.profile_label) })
            addView(profileSpinner)
            addView(TextView(this@ConnectionActivity).apply { setText(R.string.stop_after_label) })
            addView(stopAfterSpinner)
            addView(TextView(this@ConnectionActivity).apply { setText(R.string.tls_profile_label) })
            addView(tlsProfileSpinner)
            addView(TextView(this@ConnectionActivity).apply { setText(R.string.keepalive_label) })
            addView(keepaliveSpinner)
            addView(firstUseCheck)
            addView(captureDiscoveryCheck)
            addView(captureUnhandledCheck)
            addView(captureVideoCheck)
            addView(openAllChannelsCheck)
            addView(resumeInPlaceCheck)
            addView(waitForFocusCheck)
            addView(TextView(this@ConnectionActivity).apply { setText(R.string.keyframe_label) })
            addView(keyframeSpinner)
            addView(TextView(this@ConnectionActivity).apply { setText(R.string.setup_field_label) })
            addView(setupFieldSpinner)
            addView(TextView(this@ConnectionActivity).apply { setText(R.string.sensors_label) })
            addView(sensorsSpinner)
            addView(sensorLocationCheck)
            addView(mediaInfoCheck)
            addView(audioCheck)
            addView(navigationCheck)
            addView(navigationUnitsSpinner)
            addView(navigationForceCheck)
            addView(navigationScriptCheck)
            addView(mediaAccessText)
            addView(mediaAccessButton)
            addView(softwareEncoderCheck)
            addView(TextView(this@ConnectionActivity).apply { setText(R.string.video_preference_label) })
            addView(videoPreferenceSpinner)
            addView(TextView(this@ConnectionActivity).apply { setText(R.string.encoder_fps_label) })
            addView(encoderFpsSpinner)
            addView(TextView(this@ConnectionActivity).apply { setText(R.string.scene_area_label) })
            addView(sceneAreaSpinner)
            addView(TextView(this@ConnectionActivity).apply { setText(R.string.scene_label) })
            addView(sceneSpinner)
            addView(TextView(this@ConnectionActivity).apply { setText(R.string.map_side_label) })
            addView(mapSideSpinner)
            addView(speedUnitLabel)
            addView(speedUnitSpinner)
            addView(mapText)
            addView(importMapButton)
            addView(folderText)
            addView(row(chooseFolderButton, checkFolderButton))
            addView(routingText)
            addView(row(importRoutingButton, removeDataButton))
            addView(routeInput)
            addView(row(routeButton, clearRouteButton))
            addView(row(connectButton, stopButton))
            addView(row(scanButton, useListedButton))
            addView(notificationText)
            addView(resultText)
            addView(accessoryText)
            addView(savedText)
            addView(row(exportButton, exportSavedButton))
            addView(projectionText)
            addView(trustText)
            addView(row(forgetPinsButton, replaceIdentityButton))
            addView(importIdentityButton)
            addView(noticesButton)
            addView(reportText)
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(column)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left + margin, bars.top + margin, bars.right + margin, bars.bottom + margin)
                insets
            }
        }
        setContentView(scroll)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    companion object {
        /** Package-scoped action for the USB permission result broadcast. */
        const val ACTION_USB_PERMISSION = "org.openautoandroid.source.action.USB_PERMISSION"
        /**
         * `legacy-1.5-any-minor` only after `legacy-1.5` refused a recorded 1.6+ request;
         * `match-1.7` answers a 1.6-or-later request at 1.7, as Google's Android Auto does
         * and as Ford SYNC 4.0 accepts, and is the default. The legacy profiles downgrade
         * to 1.5; `match-1.6` answers at 1.6.
         */
        val PROFILES = listOf("match-1.7", "legacy-1.5", "legacy-1.5-any-minor", "match-1.6")
        const val NOTICES_ASSET = "NOTICES.txt"

        /** Lab transport extras; honored only in a debug build (see [handleLabIntent]). */
        const val EXTRA_LAB_SOCKET = "lab_socket"
        const val EXTRA_LAB_ROUTE = "lab_route"
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
        const val EXTRA_LAB_WAIT_FOR_FOCUS = "lab_wait_for_focus"
        const val EXTRA_LAB_KEYFRAME_SECONDS = "lab_keyframe_seconds"
        const val EXTRA_LAB_SETUP_FIELD = "lab_setup_field"
        private const val REQUEST_NOTIFICATIONS = 1
        private const val REQUEST_EXPORT = 2
        private const val REQUEST_EXPORT_SAVED = 3
        private const val REQUEST_IMPORT_IDENTITY = 4
        private const val REQUEST_IMPORT_MAP = 5
        private const val REQUEST_IMPORT_ROUTING = 6
        private const val REQUEST_DROP_FOLDER = 7

        /** Map side choices, in the order [AttemptSettings.mapOnLeft] reads them. */
        val MAP_SIDES = listOf("map left", "map right")
    }
}
