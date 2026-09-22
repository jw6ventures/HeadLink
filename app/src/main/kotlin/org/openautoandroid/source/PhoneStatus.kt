package org.openautoandroid.source

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
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
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
 */
class PhoneStatus(private val context: Context, private val onChange: (PhoneSnapshot) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var snapshot = PhoneSnapshot()

    private var telephonyCallback: TelephonyCallback? = null
    private var locationListener: LocationListener? = null

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
    }

    /** Re-reads the values that are polled rather than pushed. Cheap; call about 1 Hz. */
    fun refresh() {
        readBattery()
        readNetwork()
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
     * Cellular signal level, when `READ_PHONE_STATE` is held. Android's own level scale
     * is 0..4, which is the bar count, so nothing has to be reinterpreted.
     */
    private fun startSignal() {
        if (!hasPhoneState) return
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
            "phone_location_permission=$hasLocation phone_fine_location=$hasFineLocation phone_phone_state_permission=$hasPhoneState " +
            "phone_fix=${current.latitude != null} " +
            "phone_accuracy=${current.accuracyMetres?.let { Math.round(it) } ?: "-"}\n"
    }

    private companion object {
        /** Fast enough to follow a vehicle, slow enough not to dominate the battery. */
        const val LOCATION_INTERVAL_MS = 1_000L
    }
}
