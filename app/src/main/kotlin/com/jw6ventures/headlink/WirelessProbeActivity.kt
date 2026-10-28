package com.jw6ventures.headlink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
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
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * W0 (docs/connecting.md, "Diagnostics: the wireless probe"), opened from Diagnostics: pick a paired device,
 * run a fresh SDP query on it, and save whether it advertises the wireless Android Auto
 * bootstrap service. The only code that uses BLUETOOTH_CONNECT, asked for when this
 * screen opens. The verdict and report are [WirelessProbe]'s. The report goes into
 * the ordinary report store, so Export saved reports under Diagnostics carries it.
 *
 * W0 is read-only: nothing is connected to or sent to the head unit beyond the SDP query
 * Android makes on our behalf. After an `advertised` verdict, W1 (spec §5.2, the W1
 * amendment of 14 October 2026) connects to that service over RFCOMM and runs
 * [WirelessBootstrap.Session] as far as the head unit's Wi-Fi details, then closes; it
 * joins no network. Device names
 * appear on this screen only, to pick the truck. Neither report has names or addresses
 * (spec §10), and W1's keeps no Wi-Fi key.
 */
class WirelessProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var result: TextView
    private lateinit var bootstrapButton: Button
    private lateinit var connectButton: Button
    private val linkListener: (String) -> Unit = { text ->
        status.text = text
        if (!WirelessLink.active || WirelessLink.waiting) setButtons(true)
    }
    private var adapter: BluetoothAdapter? = null
    private var hfp: BluetoothProfile? = null
    private var a2dp: BluetoothProfile? = null
    private var probe: Probe? = null
    private var bootstrap: Thread? = null

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
        bootstrapButton = Button(this).apply {
            isAllCaps = false
            visibility = View.GONE
        }
        connectButton = Button(this).apply {
            isAllCaps = false
            visibility = View.GONE
        }
        column.addView(status)
        column.addView(bootstrapButton)
        column.addView(connectButton)
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
        WirelessLink.addListener(linkListener)
        if (granted()) ready() else requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CONNECT)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CONNECT) {
            if (granted()) ready() else status.setText(R.string.wireless_probe_no_permission)
        }
    }

    override fun onDestroy() {
        WirelessLink.removeListener(linkListener)
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
        val sorted = WirelessLink.vehiclesFirst(bonded)
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
        // The SDP query needs no RFCOMM, so a link standing by for the truck stays.
        if (probe != null || bootstrap != null || (WirelessLink.active && !WirelessLink.waiting) || !granted()) return
        bootstrapButton.visibility = View.GONE
        connectButton.visibility = View.GONE
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
        if (WirelessProbe.verdict(p.before.cached, sdp.answers) == WirelessProbe.Verdict.ADVERTISED) offerBootstrap(p.device)
    }

    /** W1 is offered only for a device that has just advertised the bootstrap service. */
    @SuppressLint("MissingPermission")
    private fun offerBootstrap(device: BluetoothDevice) {
        val label = device.name ?: getString(R.string.wireless_probe_unnamed)
        bootstrapButton.text = getString(R.string.wireless_bootstrap_button, label)
        bootstrapButton.setOnClickListener { runBootstrap(device, label) }
        bootstrapButton.visibility = View.VISIBLE
        connectButton.text = getString(R.string.wireless_connect_button, label)
        connectButton.setOnClickListener { runConnect(device) }
        connectButton.visibility = View.VISIBLE
    }

    /**
     * W2: the whole wireless connection, handed to [WirelessLink], which outlives this
     * screen. Progress arrives through its listener while the screen is open.
     */
    private fun runConnect(device: BluetoothDevice) {
        // Standing by for the truck gives way to the probe, which needs the same RFCOMM service.
        if (probe == null && bootstrap == null && granted()) WirelessLink.stopWaiting("replaced by the wireless probe")
        if (probe != null || bootstrap != null || WirelessLink.active || !granted()) return
        result.text = null
        if (!WirelessLink.connect(this, device)) return
        // Home's Connect wirelessly goes to the same head unit from now on.
        AppSettings.saveWirelessDevice(this, device.address)
        setButtons(false)
    }

    @SuppressLint("MissingPermission")
    private fun runBootstrap(device: BluetoothDevice, label: String) {
        // Standing by for the truck gives way to the probe, which needs the same RFCOMM service.
        if (probe == null && bootstrap == null && granted()) WirelessLink.stopWaiting("replaced by the wireless probe")
        if (probe != null || bootstrap != null || WirelessLink.active || !granted()) return
        setButtons(false)
        result.text = null
        status.text = getString(R.string.wireless_bootstrap_running, label)
        val utc = Instant.now()
        val deviceClass = device.bluetoothClass?.deviceClass
        val worker = Thread({
            val (text, outcome) = converse(device, utc, deviceClass)
            // Saved from here, so the report survives the screen being closed mid-run.
            val name = WirelessProbe.reportName(utc, UUID.randomUUID().toString())
            val store = ReportPersistence.store
            store?.save(name, text)
            main.post {
                bootstrap = null
                if (isDestroyed) return@post
                status.text = if (store != null) {
                    getString(R.string.wireless_bootstrap_saved, outcome, name)
                } else {
                    getString(R.string.wireless_bootstrap_unsaved, outcome)
                }
                result.text = text
                setButtons(true)
            }
        }, "wireless-bootstrap")
        bootstrap = worker
        worker.start()
    }

    /**
     * Worker thread. Connects, then feeds the session from a reader thread's frames and a
     * 100 ms tick until it is done, writing whatever it answers. Returns the report and the
     * outcome key.
     */
    @SuppressLint("MissingPermission")
    private fun converse(device: BluetoothDevice, utc: Instant, deviceClass: Int?): Pair<String, String> {
        val session = WirelessBootstrap.Session(WirelessBootstrap.Redactor(WirelessLink.salt(this)), WirelessLink.deviceId(this))
        val connectStart = SystemClock.elapsedRealtime()
        var connectError: String? = null
        var socket: BluetoothSocket? = null
        try {
            val s = device.createRfcommSocketToServiceRecord(UUID.fromString(WirelessProbe.BOOTSTRAP_UUID))
            socket = s
            val connected = AtomicBoolean(false)
            val watchdog = Thread({
                try {
                    Thread.sleep(WirelessBootstrap.CONNECT_TIMEOUT_MS)
                    if (!connected.get()) s.close()
                } catch (e: InterruptedException) {
                    // Connected (or failed) in time.
                } catch (e: IOException) {
                    // Closing an unconnected socket; the connect reports the failure.
                }
            }, "wireless-bootstrap-watchdog").apply { isDaemon = true }
            watchdog.start()
            try {
                s.connect()
                connected.set(true)
            } finally {
                watchdog.interrupt()
            }
        } catch (e: IOException) {
            connectError = e.toString()
        } catch (e: SecurityException) {
            connectError = e.toString()
        }
        val connectMs = SystemClock.elapsedRealtime() - connectStart
        val s = socket
        if (connectError == null && s != null) {
            try {
                talk(s, session)
            } finally {
                try {
                    s.close()
                } catch (e: IOException) {
                    // Closing; nothing left to report.
                }
            }
        } else {
            try {
                s?.close()
            } catch (e: IOException) {
                // Never connected.
            }
        }
        val text = WirelessBootstrap.report(utc, versionName(), Build.VERSION.SDK_INT, deviceClass, connectMs, connectError, session)
        val outcome = if (connectError != null) WirelessBootstrap.Outcome.CONNECT_FAILED else session.outcome()
        return text to outcome.key
    }

    private fun talk(socket: BluetoothSocket, session: WirelessBootstrap.Session) {
        val queue = LinkedBlockingQueue<Any>()
        val input = socket.inputStream
        val output = socket.outputStream
        Thread({
            val buffer = ByteArray(1024)
            val deframer = WirelessBootstrap.Deframer()
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        queue.put("end of stream")
                        break
                    }
                    deframer.push(buffer, count).forEach { queue.put(it) }
                }
            } catch (e: IOException) {
                queue.put("read failed: $e")
            }
        }, "wireless-bootstrap-reader").apply { isDaemon = true }.start()
        val origin = SystemClock.elapsedRealtime()
        fun now() = SystemClock.elapsedRealtime() - origin
        while (!session.done) {
            val item = queue.poll(100, TimeUnit.MILLISECONDS)
            val out = when (item) {
                null -> session.tick(now())
                is WirelessBootstrap.Frame -> session.received(item, now()) + session.tick(now())
                else -> {
                    session.closed(now(), item.toString())
                    emptyList<WirelessBootstrap.Frame>()
                }
            }
            for (frame in out) {
                try {
                    output.write(WirelessBootstrap.encode(frame))
                    output.flush()
                } catch (e: IOException) {
                    session.closed(now(), "write failed: $e")
                    break
                }
            }
        }
    }

    private fun setButtons(enabled: Boolean) {
        for (i in 0 until devices.childCount) devices.getChildAt(i).isEnabled = enabled
        bootstrapButton.isEnabled = enabled
        connectButton.isEnabled = enabled
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
