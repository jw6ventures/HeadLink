package org.openautoandroid.source

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.util.UUID

/**
 * W0 of docs/wireless-investigation.md, debug builds only: pick a paired device, run a
 * fresh SDP query on it, and save whether it advertises the wireless Android Auto
 * bootstrap service. The verdict and report are [WirelessProbe]'s. The report goes into
 * the ordinary report store, so Export saved reports on the main screen carries it.
 *
 * Read-only: nothing is connected to or sent to the head unit beyond the SDP query
 * Android makes on our behalf. Device names appear on this screen only, to pick the
 * truck. The report has neither names nor addresses (spec §10).
 */
class WirelessProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var result: TextView
    private var adapter: BluetoothAdapter? = null
    private var hfp: BluetoothProfile? = null
    private var a2dp: BluetoothProfile? = null
    private var probe: Probe? = null

    /** One probe in flight: the device, what was read before the query, and the answers so far. */
    private inner class Probe(val device: BluetoothDevice, val before: WirelessProbe.Device, val utc: Instant) {
        val startedAt = SystemClock.elapsedRealtime()
        val answers = mutableListOf<List<String>?>()
        var started = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val from = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (from == null || from.address != device.address || probe !== this@Probe) return
                val uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                answers += uuids?.map { it.uuid.toString() }
                if (answers.size == 1) {
                    main.removeCallbacks(timeout)
                    main.postDelayed(timeout, WirelessProbe.SDP_GRACE_MS)
                }
            }
        }
        val timeout = Runnable { complete(this@Probe) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply { setText(R.string.wireless_probe_intro) })
        status = TextView(this).apply { setPadding(0, pad, 0, pad) }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        result = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, pad, 0, 0)
        }
        column.addView(status)
        column.addView(devices)
        column.addView(result)
        setContentView(ScrollView(this).apply {
            addView(column)
            // Edge to edge, as on the main screen: clear the bars and the cutout.
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (granted()) ready() else requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CONNECT)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CONNECT) {
            if (granted()) ready() else status.setText(R.string.wireless_probe_no_permission)
        }
    }

    override fun onDestroy() {
        probe?.let { stop(it) }
        probe = null
        adapter?.let { a ->
            hfp?.let { a.closeProfileProxy(BluetoothProfile.HEADSET, it) }
            a2dp?.let { a.closeProfileProxy(BluetoothProfile.A2DP, it) }
        }
        super.onDestroy()
    }

    private fun granted() = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Called only once BLUETOOTH_CONNECT is granted; every Bluetooth call below relies on that. */
    @SuppressLint("MissingPermission")
    private fun ready() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            status.setText(R.string.wireless_probe_no_bluetooth)
            return
        }
        // The profile states go in the report: they say whether the truck was really up.
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                when (profile) {
                    BluetoothProfile.HEADSET -> hfp = proxy
                    BluetoothProfile.A2DP -> a2dp = proxy
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                when (profile) {
                    BluetoothProfile.HEADSET -> hfp = null
                    BluetoothProfile.A2DP -> a2dp = null
                }
            }
        }
        a.getProfileProxy(this, listener, BluetoothProfile.HEADSET)
        a.getProfileProxy(this, listener, BluetoothProfile.A2DP)
        val bonded = try {
            a.bondedDevices.orEmpty()
        } catch (e: SecurityException) {
            status.text = getString(R.string.wireless_probe_failed, e.toString())
            return
        }
        // Car audio first: that is the truck on every head unit seen so far.
        val sorted = bonded.sortedWith(
            compareBy<BluetoothDevice>({ it.bluetoothClass?.deviceClass != BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO }, { it.name.orEmpty() }),
        )
        devices.removeAllViews()
        if (sorted.isEmpty()) {
            status.setText(R.string.wireless_probe_no_devices)
            return
        }
        status.setText(R.string.wireless_probe_pick)
        for (device in sorted) {
            devices.addView(Button(this).apply {
                isAllCaps = false
                text = getString(R.string.wireless_probe_device, device.name ?: getString(R.string.wireless_probe_unnamed))
                setOnClickListener { start(device) }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun start(device: BluetoothDevice) {
        if (probe != null || !granted()) return
        val before = WirelessProbe.Device(
            deviceClass = device.bluetoothClass?.deviceClass,
            type = device.type,
            hfpState = hfp?.getConnectionState(device),
            a2dpState = a2dp?.getConnectionState(device),
            cached = device.uuids?.map { it.uuid.toString() },
        )
        val p = Probe(device, before, Instant.now())
        probe = p
        setButtons(false)
        result.text = null
        status.text = getString(R.string.wireless_probe_running, device.name ?: getString(R.string.wireless_probe_unnamed))
        // ACTION_UUID is a protected broadcast sent by the Bluetooth stack's own process, not
        // system_server, so the receiver must be exported to hear it; no app can forge it.
        registerReceiver(p.receiver, IntentFilter(BluetoothDevice.ACTION_UUID), Context.RECEIVER_EXPORTED)
        p.started = try {
            device.fetchUuidsWithSdp()
        } catch (e: SecurityException) {
            false
        }
        main.postDelayed(p.timeout, if (p.started) WirelessProbe.SDP_TIMEOUT_MS else 0L)
    }

    private fun stop(p: Probe) {
        main.removeCallbacks(p.timeout)
        try {
            unregisterReceiver(p.receiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    private fun complete(p: Probe) {
        if (probe !== p) return
        stop(p)
        probe = null
        val sdp = WirelessProbe.Sdp(p.started, p.answers.toList(), SystemClock.elapsedRealtime() - p.startedAt)
        val text = WirelessProbe.report(p.utc, versionName(), Build.VERSION.SDK_INT, p.before, sdp)
        val name = WirelessProbe.reportName(p.utc, UUID.randomUUID().toString())
        val store = ReportPersistence.store
        store?.save(name, text)
        status.text = if (store != null) {
            getString(R.string.wireless_probe_saved, WirelessProbe.verdict(p.before.cached, sdp.answers).key, name)
        } else {
            getString(R.string.wireless_probe_unsaved, WirelessProbe.verdict(p.before.cached, sdp.answers).key)
        }
        result.text = text
        setButtons(true)
    }

    private fun setButtons(enabled: Boolean) {
        for (i in 0 until devices.childCount) devices.getChildAt(i).isEnabled = enabled
    }

    private fun versionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }

    private companion object {
        const val REQUEST_CONNECT = 1
    }
}
