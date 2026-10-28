package com.jw6ventures.headlink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.widget.Toast
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * W3 (spec §5.2, the W3 amendment; docs/connecting.md, "The companion-device link and auto-start"): start wireless
 * projection when the truck connects over Bluetooth, with the phone left in a pocket.
 *
 * The app is linked to the truck once through Android's companion-device dialog
 * ([link]), and asks Android to watch for it ([observe]). From then on Android binds
 * [WirelessCompanionService] when the truck's Bluetooth connects, even with no screen and
 * no process, and that calls [appeared], which decides by [WirelessAutoStart] and starts
 * the same link Home's Connect wirelessly does. The link holds the foreground
 * ([WirelessLinkService]) while it sets up, which the Wi-Fi request needs; the companion
 * link is also what lets a background app start that service at all.
 *
 * The link itself is Settings' "Link the truck for wireless": on while one exists, off once
 * [unlink] removes it, and Android sends nothing for a truck the app is not linked to.
 *
 * Standby (spec §5.2, the standby amendment; [WirelessStandby]): every arrival of the linked
 * truck starts a link that waits for the truck's own start request, which is how the
 * Android Auto button on its screen reaches the phone. "Start wirelessly when the truck
 * connects" ([SavedSettings.wirelessAutoStart]) decides only whether the truck's automatic
 * starts are taken too ([acceptAuto]). Main thread, except where noted.
 */
@SuppressLint("MissingPermission")
object WirelessAuto {
    /** Whether a link exists, as last read: [refresh] re-reads it, since Settings can remove it too. */
    @Volatile
    private var on: Boolean? = null

    /**
     * The linked truck's Bluetooth address while its Bluetooth is connected, as far as the
     * app has seen: set when Android reports it connected or an RFCOMM connection to it
     * works, cleared when it goes. Upper case. Any thread.
     */
    @Volatile
    private var present: String? = null

    /** The operator stopped or cancelled a wireless session from the phone since the truck connected. Any thread. */
    @Volatile
    var operatorStopped = false

    /** Automatic starts in a row whose projection ended quickly ([WirelessStandby.quickAfter]). Any thread. */
    @Volatile
    var quickAutoStarts = 0
        private set

    fun isPresent(address: String): Boolean = present.equals(address, ignoreCase = true)

    /** Any thread: [address] is connected, if it is the linked truck. */
    fun markPresent(context: Context, address: String) {
        if (isLinked(context, address)) present = address.uppercase(Locale.ROOT)
    }

    /** Any thread: [address] has disconnected. */
    fun markAbsent(address: String) {
        if (isPresent(address)) present = null
    }

    /** Whether the app is linked to the device at [address]. Any thread. */
    fun isLinked(context: Context, address: String): Boolean =
        associations(context).any { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }

    /**
     * Whether a link to [address] may stand by between projections: it is the linked truck,
     * its Bluetooth is connected, Nearby devices is granted and Bluetooth is on. Any thread.
     */
    fun standbyFor(context: Context, address: String): Boolean {
        if (!isPresent(address) || !isLinked(context, address)) return false
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        return context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }

    /** Whether the truck's own start requests are taken now ([WirelessStandby.acceptAuto]). Any thread. */
    fun acceptAuto(context: Context): Boolean =
        WirelessStandby.acceptAuto(AppSettings.load(context).wirelessAutoStart, operatorStopped, quickAutoStarts)

    /** A start the link accepted has ended, [ms] after it was accepted. Any thread. */
    fun projectionEnded(reason: Long?, ms: Long) {
        if (WirelessStandby.isUser(reason)) return
        quickAutoStarts = WirelessStandby.quickAfter(quickAutoStarts, ms)
        if (quickAutoStarts >= WirelessStandby.QUICK_AUTO_LIMIT) {
            SessionController.note("wireless standby: $quickAutoStarts automatic starts in a row ended within ${WirelessStandby.QUICK_PROJECTION_MS / 1000} s; only the car's button starts now")
        }
    }

    fun isOn(context: Context): Boolean = on ?: refresh(context)

    fun refresh(context: Context): Boolean = associations(context).isNotEmpty().also { on = it }

    private fun manager(context: Context): CompanionDeviceManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            context.getSystemService(CompanionDeviceManager::class.java)
        } else {
            null
        }

    /** This app's links to a Bluetooth device. */
    private fun associations(context: Context): List<AssociationInfo> =
        try {
            manager(context)?.myAssociations.orEmpty().filter { it.deviceMacAddress != null }
        } catch (e: RuntimeException) {
            emptyList()
        }

    fun association(context: Context, id: Int): AssociationInfo? = associations(context).firstOrNull { it.id == id }

    /**
     * Links the app to [device]: one truck at a time, so any other link goes first. Android
     * shows its own dialog naming the device; [done] runs once it is answered or refused,
     * and [linkResult] takes the dialog's answer in [activity]'s onActivityResult.
     */
    fun link(activity: Activity, device: BluetoothDevice, requestCode: Int, done: () -> Unit) {
        val manager = manager(activity)
        if (manager == null) {
            Toast.makeText(activity, activity.getString(R.string.wireless_auto_failed, "no companion-device support"), Toast.LENGTH_LONG).show()
            return
        }
        val existing = associations(activity)
        existing.firstOrNull { it.deviceMacAddress?.toString().equals(device.address, ignoreCase = true) }?.let {
            // Already linked to this truck: make sure Android is watching for it.
            existing.filter { other -> other.id != it.id }.forEach { other -> remove(manager, other) }
            observe(activity, it)
            return done()
        }
        existing.forEach { remove(manager, it) }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(device.address).build())
            .setSingleDevice(true)
            .build()
        val app = activity.applicationContext
        manager.associate(request, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                try {
                    activity.startIntentSenderForResult(intentSender, requestCode, null, 0, 0, 0)
                } catch (e: IntentSender.SendIntentException) {
                    failed(app, e.toString())
                    done()
                } catch (e: RuntimeException) {
                    failed(app, e.toString())
                    done()
                }
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                observe(app, associationInfo)
                done()
            }

            override fun onFailure(error: CharSequence?) {
                failed(app, error?.toString() ?: "refused")
                done()
            }
        })
    }

    /** The companion dialog's answer; the association also arrives through [link]'s callback. */
    fun linkResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                ?.let { observe(context, it) }
        } else {
            SessionController.note("start wirelessly when the car connects: Android's link dialog was cancelled")
        }
        refresh(context)
    }

    /** Asks Android to wake the app when [info]'s device connects. Safe to repeat. */
    private fun observe(context: Context, info: AssociationInfo) {
        val manager = manager(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build())
            } else {
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(info.deviceMacAddress.toString())
            }
            on = true
            // The address is the operator's own head unit and stays out of the note (§10).
            SessionController.note("start wirelessly when the car connects: on, linked and watching")
        } catch (e: Exception) {
            failed(context, "watching for the car failed: $e")
        }
    }

    /** Debug builds' lab only: watches every existing link (ConnectionActivity's `lab_wireless_observe`). */
    fun observeAll(context: Context) {
        val all = associations(context)
        all.forEach { observe(context, it) }
        SessionController.note("lab: watching ${all.size} companion link(s) (debug build)")
    }

    /** Removes every link, so Android stops waking the app for any truck, and ends standing by. */
    fun unlink(context: Context) {
        val manager = manager(context) ?: return
        associations(context).forEach { remove(manager, it) }
        on = false
        present = null
        WirelessLink.stopWaiting("the car link was removed in Settings")
    }

    private fun remove(manager: CompanionDeviceManager, info: AssociationInfo) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                manager.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build())
            } else {
                @Suppress("DEPRECATION")
                manager.stopObservingDevicePresence(info.deviceMacAddress.toString())
            }
        } catch (e: Exception) {
            // Not watched, or already gone; removing the link below is what matters.
        }
        try {
            manager.disassociate(info.id)
        } catch (e: RuntimeException) {
            SessionController.note("removing the wireless link failed: $e")
        }
    }

    private fun failed(context: Context, why: String) {
        SessionController.note("start wirelessly when the car connects: $why")
        Toast.makeText(context, context.getString(R.string.wireless_auto_failed, why), Toast.LENGTH_LONG).show()
        refresh(context)
    }

    /**
     * The linked truck connected over Bluetooth ([how] says which callback said so). Decides
     * by [WirelessAutoStart] and, on START, starts the link as Home's button would, marked
     * automatic so it retries an early RFCOMM connect.
     */
    fun appeared(context: Context, address: String?, how: String) {
        val app = context.applicationContext
        if (address == null) return
        // A new arrival: the truck is present, and the last drive's stops count no more.
        present = address.uppercase(Locale.ROOT)
        operatorStopped = false
        quickAutoStarts = 0
        val saved = AppSettings.load(app)
        val bluetooth = app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val wired = app.getSystemService(UsbManager::class.java)?.let { Accessories.list(it).isNotEmpty() } == true
        val identity = when {
            SessionController.identityReady -> AutoStart.Identity.READY
            SessionController.identityBusy || SessionController.identityReport.isEmpty() -> AutoStart.Identity.LOADING
            else -> AutoStart.Identity.FAILED
        }
        val stage = SessionController.stage
        val linking = WirelessLink.active
        val pins = IdentityWork.trust(app).pins().size
        val projection = saved.stopAfter == SessionSettings.STOP_AFTER_PROJECTION
        val decision = WirelessAutoStart.decide(stage, linking, wired, bluetooth, pins, projection, identity)
        val autoStart = saved.wirelessAutoStart
        // Saved on its own: the session's events are cleared when an attempt begins, and a
        // wake that starts nothing has no other trace in an export.
        val utc = Instant.now()
        val version = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
        ReportPersistence.store?.save(
            ReportStore.fileName(utc, UUID.randomUUID().toString()),
            WirelessAutoStart.report(utc, version, Build.VERSION.SDK_INT, how, decision, stage, linking, wired, bluetooth, pins, projection, identity, autoStart),
        )
        val word = if (decision != WirelessAutoStart.START) "no" else if (autoStart) "yes" else "standby, button only"
        SessionController.note("wireless auto-start $word: ${decision.reason} ($how)")
        if (decision != WirelessAutoStart.START) return
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            SessionController.note("wireless auto-start no: Bluetooth is off")
            return
        }
        // The link's MacAddress prints in lower case, which getRemoteDevice refuses: every
        // wake on the truck on 2026-10-16 ended here until it was upper-cased.
        val device = try {
            adapter.getRemoteDevice(address.uppercase(Locale.ROOT))
        } catch (e: IllegalArgumentException) {
            SessionController.note("wireless auto-start no: the linked address is not usable")
            return
        }
        AppSettings.saveWirelessDevice(app, device.address)
        WirelessLink.connect(app, device, auto = true)
    }

    /** The linked truck's Bluetooth disconnected ([how] says which callback said so). */
    fun disappeared(address: String?, how: String) {
        if (address == null) return
        markAbsent(address)
        SessionController.note("wireless standby: the linked head unit disconnected over Bluetooth ($how)")
        WirelessLink.truckLeft(address)
    }
}

/**
 * Bound by Android, never by this app, while the linked truck is present (W3). It starts
 * the link on the truck's arrival. When the truck leaves, a link standing by ends; a
 * session ends on its own when the head unit goes, as it does today.
 *
 * Android 16 sends both callbacks below for one arrival, and the newer one only on builds
 * with its feature flag on, so both are handled and a repeat within [REPEAT_MS] is
 * dropped. Being bound this way does not make the app foreground enough for the Wi-Fi
 * request; [WirelessLinkService] does that.
 */
class WirelessCompanionService : CompanionDeviceService() {
    /** Android 16 and later; Android calls it on no earlier release. */
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        val info = WirelessAuto.association(this, event.associationId)
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED -> arrived(info?.deviceMacAddress?.toString(), "Bluetooth connected")
            DevicePresenceEvent.EVENT_BT_DISCONNECTED ->
                WirelessAuto.disappeared(info?.deviceMacAddress?.toString(), "Bluetooth disconnected")
        }
    }

    @Deprecated("Android 16 also delivers onDevicePresenceEvent")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        arrived(associationInfo.deviceMacAddress?.toString(), "device appeared")
    }

    @Deprecated("Android 16 also delivers onDevicePresenceEvent")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        WirelessAuto.disappeared(associationInfo.deviceMacAddress?.toString(), "device disappeared")
    }

    private fun arrived(address: String?, how: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastArrival < REPEAT_MS) return
        lastArrival = now
        WirelessAuto.appeared(this, address, how)
    }

    private companion object {
        const val REPEAT_MS = 10_000L
        /** Process-wide, as Android may bind a fresh instance for the second callback. */
        var lastArrival = -REPEAT_MS
    }
}
