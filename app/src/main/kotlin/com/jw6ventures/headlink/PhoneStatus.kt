package com.jw6ventures.headlink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager

/**
 * The phone's own contribution to the dashboard header: battery, network, and a position
 * fallback for when the vehicle has no fix.
 *
 * **Every part of this degrades rather than fails.** Following APP09, each integration is
 * separate and each explains the permission it needs: battery needs none, network type
 * needs the normal `ACCESS_NETWORK_STATE`, signal bars need `READ_PHONE_STATE` and the
 * position fallback needs `ACCESS_FINE_LOCATION` — both runtime grants this app never
 * asks for on its own. Without a grant the field reads `—` and the rest keeps working,
 * which is the same shape P5.1 used for notification access.
 *
 * The vehicle's own position is preferred wherever it exists, so on a head unit that
 * populates LOCATION this class is only ever supplying the supplementary fields.
 *
 * **Signal is per subscription (P7.3, plan §5.2).** A dual-SIM or SIM-plus-eSIM phone has
 * one radio state per subscription, and a single `SignalStrength` only ever described the
 * default one. With `READ_PHONE_STATE`, every active subscription gets its own
 * `TelephonyManager.createForSubscriptionId` and its own [TelephonyCallback], and the set
 * follows `OnSubscriptionsChangedListener` so a SIM added or switched off mid-drive gains or
 * loses its row. [SimSignal] decides which one leads and how the rest are ordered. Only
 * opaque ids, slots and levels are read: never a carrier name or a number (spec §10).
 */
class PhoneStatus(private val context: Context, private val onChange: (PhoneSnapshot) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var snapshot = PhoneSnapshot()

    private var telephonyCallback: TelephonyCallback? = null
    private var locationListener: LocationListener? = null

    /** Main thread only: every callback below is registered on the main executor. */
    private var subscriptionListener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private val watched = HashMap<Int, Watched>()
    private var released = false

    /** Active subscriptions at the last change, for the report. */
    @Volatile
    private var subscriptionCount = 0

    @Volatile
    private var subscriptionChanges = 0

    /** One subscription's telephony view and what its callback has reported so far. */
    private class Watched(val slot: Int, val manager: TelephonyManager) {
        var callback: TelephonyCallback? = null
        var level = -1
        var inService = true
        var networkType = 0
        var overrideType = 0
    }

    /** Whether each optional grant is held, for the report. */
    val hasLocation: Boolean
        get() = hasFineLocation || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Android lets the operator grant coarse only, which rules the GPS provider out. */
    val hasFineLocation: Boolean
        get() = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    val hasPhoneState: Boolean
        get() = granted(Manifest.permission.READ_PHONE_STATE)

    fun start() {
        readBattery()
        readNetwork()
        startSignal()
        startLocation()
        handler.postDelayed(::poll, REFRESH_MS)
    }

    /** Re-reads the values that are polled rather than pushed. Cheap; call about 1 Hz. */
    fun refresh() {
        readBattery()
        readNetwork()
        // The default data subscription can be switched in Settings mid-drive and no
        // callback registered here reports that, so the lead row is re-chosen too.
        handler.post { if (watched.isNotEmpty()) publishSignal() }
    }

    /**
     * Nothing called [refresh] before P7.3, so battery and the wifi/cell transport were
     * read once when projection started and never again; the header's battery sat at
     * its starting level for a whole drive. This keeps them current on its own.
     */
    private fun poll() {
        if (released) return
        refresh()
        handler.postDelayed(::poll, REFRESH_MS)
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** The sticky battery broadcast needs no permission and no registration. */
    private fun readBattery() {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        update(
            snapshot.copy(
                batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL,
            ),
        )
    }

    private fun readNetwork() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        val network = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            else -> "other"
        }
        update(snapshot.copy(network = network))
    }

    /**
     * Cellular signal, when `READ_PHONE_STATE` is held. Android's own level scale is 0..4,
     * which is the bar count, so nothing has to be reinterpreted.
     *
     * Per subscription where `SubscriptionManager` exists (every phone this app supports
     * has it; the single-callback path below is kept for one that does not). Without the
     * grant nothing is registered and the header shows `—`, as before P7.3.
     */
    private fun startSignal() {
        if (!hasPhoneState) return
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        if (subscriptions == null) {
            startDefaultSignal()
            return
        }
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                followSubscriptions(subscriptions)
            }
        }
        try {
            // Also fires once straight after registering; followSubscriptions is
            // idempotent, so the explicit call below and that one agree.
            subscriptions.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
            subscriptionListener = listener
        } catch (e: SecurityException) {
            SessionController.note("subscription changes unavailable: $e")
        }
        followSubscriptions(subscriptions)
    }

    /**
     * Main thread. Brings the registered callbacks in line with the active subscriptions:
     * unregisters the ones that went away, registers the new ones, then republishes.
     */
    private fun followSubscriptions(subscriptions: SubscriptionManager) {
        if (released) return
        subscriptionChanges++
        val active = try {
            subscriptions.activeSubscriptionInfoList.orEmpty()
        } catch (e: SecurityException) {
            // The grant can be withdrawn mid-drive; that is the same as having no SIM
            // to report, not a reason to end the session.
            emptyList()
        }
        val wanted = active.associate { it.subscriptionId to it.simSlotIndex }
        val gone = watched.keys.filter { it !in wanted }
        for (id in gone) watched.remove(id)?.let(::unwatch)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        if (telephony != null) {
            for ((id, slot) in wanted) {
                if (id !in watched) watch(telephony, id, slot)
            }
        }
        subscriptionCount = wanted.size
        publishSignal()
    }

    private fun watch(telephony: TelephonyManager, id: Int, slot: Int) {
        val entry = Watched(if (slot >= 0) slot else Int.MAX_VALUE, telephony.createForSubscriptionId(id))
        val callback = object :
            TelephonyCallback(),
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.DisplayInfoListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                entry.level = signalStrength.level
                publishSignal()
            }

            override fun onServiceStateChanged(serviceState: ServiceState) {
                entry.inService = serviceState.state == ServiceState.STATE_IN_SERVICE
                publishSignal()
            }

            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                entry.networkType = telephonyDisplayInfo.networkType
                entry.overrideType = telephonyDisplayInfo.overrideNetworkType
                publishSignal()
            }
        }
        try {
            entry.manager.registerTelephonyCallback(context.mainExecutor, callback)
            entry.callback = callback
        } catch (e: SecurityException) {
            // Still listed, as a hollow row: the subscription exists even if it cannot
            // be read, and a missing row must mean "no such SIM".
            SessionController.note("signal unavailable for one subscription: $e")
        }
        watched[id] = entry
    }

    private fun unwatch(entry: Watched) {
        entry.callback?.let { entry.manager.unregisterTelephonyCallback(it) }
        entry.callback = null
    }

    /** Main thread. Arranges the readings and publishes them if anything changed. */
    private fun publishSignal() {
        if (released) return
        val defaultData = SubscriptionManager.getDefaultDataSubscriptionId()
        val readings = watched.map { (id, entry) ->
            SubscriptionSignal(id, entry.slot, entry.level, entry.inService)
        }
        val rows = SimSignal.arrange(readings, defaultData)
        // The same choice arrange made, for the label: the default data subscription,
        // else the lowest slot.
        val lead = watched[defaultData]
            ?: watched.entries.minWithOrNull(compareBy({ it.value.slot }, { it.key }))?.value
        val label = if (lead == null || !lead.inService) {
            "-"
        } else {
            SimSignal.networkLabel(lead.networkType, lead.overrideType)
        }
        update(
            snapshot.copy(
                signalBars = rows.primary,
                secondarySignalBars = rows.secondary,
                cellNetworkType = label,
            ),
        )
    }

    /** Before P7.3: the default subscription's level only. */
    private fun startDefaultSignal() {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return
        val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                update(snapshot.copy(signalBars = signalStrength.level))
            }
        }
        telephonyCallback = callback
        try {
            telephony.registerTelephonyCallback(context.mainExecutor, callback)
        } catch (e: SecurityException) {
            // The grant can be revoked between the check and the call.
            telephonyCallback = null
            SessionController.note("signal strength unavailable: $e")
        }
    }

    /**
     * The position fallback, used only when the head unit has no fix of its own.
     *
     * Requested from both providers: GPS is the accurate one, and the fused/network
     * provider is what answers indoors and immediately after a cold start.
     */
    private fun startLocation() {
        update(snapshot.copy(locationEnabled = hasLocation))
        if (!hasLocation) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val listener = LocationListener { location -> onLocation(location) }
        locationListener = listener
        try {
            val providers = if (hasFineLocation) {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            } else {
                listOf(LocationManager.NETWORK_PROVIDER)
            }
            for (provider in providers) {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, LOCATION_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
                }
            }
        } catch (e: SecurityException) {
            locationListener = null
            SessionController.note("phone location unavailable: $e")
        }
    }

    private fun onLocation(location: Location) {
        update(
            snapshot.copy(
                latitude = location.latitude,
                longitude = location.longitude,
                // Android only fills these when it is confident; a stationary phone
                // reports no bearing rather than a stale one, which is the behaviour
                // the header wants anyway.
                bearingDegrees = if (location.hasBearing()) location.bearing.toDouble() else null,
                speedMetresPerSecond = if (location.hasSpeed()) location.speed.toDouble() else null,
                accuracyMetres = if (location.hasAccuracy()) location.accuracy else null,
            ),
        )
    }

    @Synchronized
    private fun update(next: PhoneSnapshot) {
        if (next == snapshot) return
        snapshot = next
        onChange(next)
    }

    fun release() {
        released = true
        subscriptionListener?.let {
            context.getSystemService(SubscriptionManager::class.java)?.removeOnSubscriptionsChangedListener(it)
        }
        subscriptionListener = null
        for (entry in watched.values) unwatch(entry)
        watched.clear()
        telephonyCallback?.let {
            context.getSystemService(TelephonyManager::class.java)?.unregisterTelephonyCallback(it)
        }
        telephonyCallback = null
        locationListener?.let {
            context.getSystemService(LocationManager::class.java)?.removeUpdates(it)
        }
        locationListener = null
        handler.removeCallbacksAndMessages(null)
    }

    /** `key=value` lines for the session report; no position (spec §10). */
    fun report(): String {
        val current = snapshot
        return "phone_battery=${current.batteryPercent} phone_charging=${current.charging} " +
            "phone_network=${current.network} phone_signal_bars=${current.signalBars}\n" +
            // Counts and levels only: no carrier, no number, no subscription id (spec §10).
            "phone_sim_count=$subscriptionCount phone_sim_secondary_bars=" +
            "${current.secondarySignalBars.joinToString(",").ifEmpty { "-" }} " +
            "phone_cell_type=${current.cellNetworkType} phone_sim_changes=$subscriptionChanges\n" +
            "phone_location_permission=$hasLocation phone_fine_location=$hasFineLocation phone_phone_state_permission=$hasPhoneState " +
            "phone_fix=${current.latitude != null} " +
            "phone_accuracy=${current.accuracyMetres?.let { Math.round(it) } ?: "-"}\n"
    }

    private companion object {
        /** Fast enough to follow a vehicle, slow enough not to dominate the battery. */
        const val LOCATION_INTERVAL_MS = 1_000L

        /** Battery moves a percent every few minutes; five seconds is ample and free. */
        const val REFRESH_MS = 5_000L
    }
}
