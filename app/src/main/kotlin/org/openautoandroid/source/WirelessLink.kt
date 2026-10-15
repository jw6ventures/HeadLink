package org.openautoandroid.source

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * W2 of docs/wireless-investigation.md (spec §5.2, the W2 amendment): one wireless
 * connection to the head unit, end to end.
 *
 * 1. RFCOMM to the bootstrap service, running [WirelessBootstrap.Session] in connect mode:
 *    the W1 conversation, plus WifiStartResponse as Android Auto 17.6 sends it. The link
 *    stays open for the whole session, answering pings.
 * 2. A local-only Wi-Fi request ([WifiNetworkSpecifier], no internet capability) for the
 *    network the head unit named. Android asks the operator once; the phone keeps its
 *    default network for everything else.
 * 3. TCP to the endpoint from WifiStartRequest, over that network only. Then WifiConnectStatus 0
 *    over RFCOMM, and an ordinary attempt with the saved settings and pins, which
 *    [ProjectionService] runs over this socket instead of the USB accessory.
 *
 * The Wi-Fi key lives in [WirelessBootstrap.Credentials] for the one request and is never
 * written down. The report (`[wireless_connect]`) is rewritten at each step and records
 * Bluetooth A2DP and HFP changes for the truck while the link is up.
 *
 * Main-thread API. RFCOMM runs on its own thread, the network and TCP on [Link.thread].
 */
@SuppressLint("MissingPermission")
object WirelessLink {
    /** The first request includes the operator reading Android's prompt. */
    private const val NETWORK_TIMEOUT_MS = 60_000
    private const val TCP_TIMEOUT_MS = 10_000
    private const val SALT_FILE = "wireless-salt"
    private const val SALT_BYTES = 16
    private const val DEVICE_ID_FILE = "wireless-device-id"

    private val main = Handler(Looper.getMainLooper())
    private var link: Link? = null

    /** Progress in words for whatever screen is showing; main thread. */
    var listener: ((String) -> Unit)? = null

    val active: Boolean
        get() = link != null

    /** The connected TCP socket while a link has one. Any thread. */
    fun socket(): Socket? = link?.tcp

    /** Starts a link to [device]. False when one is already running. Main thread. */
    fun connect(context: Context, device: BluetoothDevice): Boolean {
        if (link != null) return false
        val created = Link(context.applicationContext as Application, device)
        link = created
        created.start()
        return true
    }

    /**
     * Debug builds' lab run only (ConnectionActivity's `lab_wireless_tcp`): TCP straight to
     * the lab receiver on the default network, then the same session start as W2. There is
     * no Bluetooth and no Wi-Fi join, so this proves the wireless transport and its
     * cleanup, not the bootstrap. Main thread.
     */
    fun labConnect(context: Context, host: String, port: Int, settings: AttemptSettings): Boolean {
        if (link != null) return false
        val created = Link(context.applicationContext as Application, null, host to port.toLong(), settings)
        link = created
        created.start()
        return true
    }

    /** Ends the link, from any thread. Safe to call more than once. */
    fun end(why: String) {
        main.post { link?.finish(why) }
    }

    /** The app-private salt for wireless hashes: random once per install. */
    fun salt(context: Context): ByteArray {
        val file = File(context.filesDir, SALT_FILE)
        try {
            val existing = file.readBytes()
            if (existing.size == SALT_BYTES) return existing
        } catch (e: IOException) {
            // First run.
        }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            file.writeBytes(salt)
        } catch (e: IOException) {
            // Hashes still work for this run; they just will not match the next one.
        }
        return salt
    }

    /**
     * The device id sent in WifiVersionResponse: a random UUID made once per install and
     * kept, as Android Auto 17.6 keeps its `DEVICE_ID`. It names this app on this phone.
     */
    fun deviceId(context: Context): String {
        val file = File(context.filesDir, DEVICE_ID_FILE)
        try {
            val existing = file.readText(Charsets.UTF_8).trim()
            if (existing.isNotEmpty()) return existing
        } catch (e: IOException) {
            // First run.
        }
        val id = UUID.randomUUID().toString()
        try {
            file.writeText(id, Charsets.UTF_8)
        } catch (e: IOException) {
            // Still usable for this run.
        }
        return id
    }

    private fun status(text: String) {
        main.post { listener?.invoke(text) }
    }

    private class Link(
        val app: Application,
        val device: BluetoothDevice?,
        val labEndpoint: Pair<String, Long>? = null,
        val labSettings: AttemptSettings? = null,
    ) {
        val utc: Instant = Instant.now()
        val reportName = WirelessProbe.reportName(utc, UUID.randomUUID().toString())
        val thread = HandlerThread("wireless-link").apply { start() }
        val handler = Handler(thread.looper)
        val outbox = LinkedBlockingQueue<WirelessBootstrap.Frame>()
        val session = WirelessBootstrap.Session(WirelessBootstrap.Redactor(salt(app)), deviceId(app), connect = true)
        val deviceClass: Int? = device?.bluetoothClass?.deviceClass
        val ended = AtomicBoolean(false)

        @Volatile var rfcomm: BluetoothSocket? = null
        @Volatile var tcp: Socket? = null
        @Volatile var origin = 0L
        @Volatile var connectMs = 0L
        @Volatile var connectError: String? = null
        @Volatile var network = "not_requested"
        @Volatile var networkMs: Long? = null
        @Volatile var tcpState = "not_attempted"
        @Volatile var tcpMs: Long? = null
        @Volatile var generation: Long? = null
        @Volatile var endedWhy: String? = null
        @Volatile var outcome: String? = null
        var callback: ConnectivityManager.NetworkCallback? = null
        var joinRequested = false

        val profiles = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val from = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (from?.address != device?.address) return
                val which = if (intent.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) "a2dp" else "hfp"
                val state = when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                    BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
                    BluetoothProfile.STATE_CONNECTING -> "connecting"
                    BluetoothProfile.STATE_CONNECTED -> "connected"
                    BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
                    else -> "unknown"
                }
                note("bluetooth $which $state")
            }
        }

        fun now() = if (origin == 0L) 0L else SystemClock.elapsedRealtime() - origin

        /** Ends this link, not whichever one is current by the time the post runs. */
        fun endSelf(why: String) {
            main.post { finish(why) }
        }

        fun note(text: String) {
            synchronized(session) { session.note(now(), text) }
            save()
        }

        fun start() {
            labEndpoint?.let { endpoint ->
                origin = SystemClock.elapsedRealtime()
                network = "lab_default_network"
                note("lab: TCP to ${endpoint.first}:${endpoint.second} on the default network, no Bluetooth or Wi-Fi (debug build)")
                handler.post { connectTcp(null, endpoint) }
                return
            }
            // The Bluetooth stack's own process sends these protected broadcasts.
            val filter = IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            app.registerReceiver(profiles, filter, null, handler, Context.RECEIVER_EXPORTED)
            status(app.getString(R.string.wireless_link_bootstrap))
            Thread({ rfcommLoop() }, "wireless-rfcomm").start()
        }

        /** RFCOMM thread: connect, then run the session until the link ends. */
        fun rfcommLoop() {
            val started = SystemClock.elapsedRealtime()
            val device = device ?: return
            val s = try {
                device.createRfcommSocketToServiceRecord(UUID.fromString(WirelessProbe.BOOTSTRAP_UUID))
            } catch (e: IOException) {
                connectError = e.toString()
                null
            }
            if (s != null) {
                rfcomm = s
                val connected = AtomicBoolean(false)
                val watchdog = Thread({
                    try {
                        Thread.sleep(WirelessBootstrap.CONNECT_TIMEOUT_MS)
                        if (!connected.get()) s.close()
                    } catch (e: InterruptedException) {
                        // Connected (or failed) in time.
                    } catch (e: IOException) {
                        // Closing an unconnected socket; connect reports the failure.
                    }
                }, "wireless-rfcomm-watchdog").apply { isDaemon = true }
                watchdog.start()
                try {
                    s.connect()
                    connected.set(true)
                } catch (e: IOException) {
                    connectError = e.toString()
                } finally {
                    watchdog.interrupt()
                }
            }
            connectMs = SystemClock.elapsedRealtime() - started
            if (connectError != null || s == null) {
                outcome = WirelessBootstrap.Outcome.CONNECT_FAILED.key
                endSelf("rfcomm connect failed")
                return
            }
            origin = SystemClock.elapsedRealtime()
            save()
            val queue = LinkedBlockingQueue<Any>()
            val input = s.inputStream
            val output = s.outputStream
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
            }, "wireless-rfcomm-reader").apply { isDaemon = true }.start()
            while (!ended.get()) {
                val item = queue.poll(100, TimeUnit.MILLISECONDS)
                val out = mutableListOf<WirelessBootstrap.Frame>()
                var credentials: WirelessBootstrap.Credentials? = null
                var endpoint: Pair<String, Long>? = null
                var done = false
                synchronized(session) {
                    when (item) {
                        null -> out += session.tick(now())
                        is WirelessBootstrap.Frame -> out += session.received(item, now()) + session.tick(now())
                        else -> session.closed(now(), item.toString())
                    }
                    while (true) out += session.send(outbox.poll() ?: break, now())
                    credentials = session.credentials
                    endpoint = session.start ?: session.versionEndpointSeen
                    done = session.done
                }
                for (frame in out) {
                    try {
                        output.write(WirelessBootstrap.encode(frame))
                        output.flush()
                    } catch (e: IOException) {
                        synchronized(session) { session.closed(now(), "write failed: $e") }
                        done = true
                        break
                    }
                }
                if (out.isNotEmpty()) save()
                val creds = credentials
                val target = endpoint
                if (creds != null && !joinRequested) {
                    joinRequested = true
                    if (target == null) {
                        outcome = "no_endpoint"
                        endSelf("Wi-Fi details arrived without an endpoint")
                    } else {
                        handler.post { joinWifi(creds, target) }
                    }
                }
                if (done) {
                    if (credentials == null) {
                        if (outcome == null) outcome = session.outcome().key
                        endSelf("bootstrap link closed before the Wi-Fi details")
                    } else {
                        // Once the head unit has handed over its Wi-Fi, a closed bootstrap link
                        // is recorded, not fatal: the join and the session carry on.
                        note("bootstrap link closed; the Wi-Fi join and the session carry on")
                    }
                    break
                }
            }
        }

        /** Link thread: the local-only network request for the head unit's Wi-Fi. */
        fun joinWifi(credentials: WirelessBootstrap.Credentials, endpoint: Pair<String, Long>) {
            if (ended.get()) return
            val builder = WifiNetworkSpecifier.Builder().setSsid(credentials.ssid)
            credentials.bssid?.let { text ->
                try {
                    builder.setBssid(MacAddress.fromString(text))
                } catch (e: IllegalArgumentException) {
                    note("bssid not usable; requesting by SSID alone")
                }
            }
            when (credentials.securityMode) {
                1L -> {}
                4L, 8L, 12L, null -> builder.setWpa2Passphrase(credentials.key)
                else -> {
                    network = "unsupported_security"
                    outcome = "unsupported_security"
                    endSelf("the head unit's Wi-Fi security mode ${credentials.securityMode} is not supported")
                    return
                }
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(builder.build())
                .build()
            val requested = SystemClock.elapsedRealtime()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(available: Network) {
                    if (networkMs != null) return
                    network = "available"
                    networkMs = SystemClock.elapsedRealtime() - requested
                    note("network available after ${networkMs}ms")
                    connectTcp(available, endpoint)
                }

                override fun onUnavailable() {
                    network = "unavailable"
                    outcome = "network_unavailable"
                    endSelf("the network request was declined or timed out")
                }

                override fun onLost(lost: Network) {
                    network = "lost"
                    note("network lost")
                    if (outcome == null) outcome = "network_lost"
                    endSelf("the head unit's Wi-Fi was lost")
                }
            }
            callback = cb
            network = "requested"
            note("network requested (local-only)")
            status(app.getString(R.string.wireless_link_wifi))
            try {
                app.getSystemService(ConnectivityManager::class.java)
                    .requestNetwork(request, cb, handler, NETWORK_TIMEOUT_MS)
            } catch (e: RuntimeException) {
                // SecurityException without CHANGE_NETWORK_STATE, or a malformed request.
                callback = null
                network = "request_failed"
                outcome = "network_request_failed"
                endSelf("requestNetwork threw $e")
            }
        }

        /** Link thread: TCP to the head unit over [network] only (the default network in the lab). */
        fun connectTcp(network: Network?, endpoint: Pair<String, Long>) {
            if (ended.get()) return
            status(app.getString(R.string.wireless_link_tcp))
            val started = SystemClock.elapsedRealtime()
            val socket = try {
                network?.socketFactory?.createSocket() ?: Socket()
            } catch (e: IOException) {
                tcpFailed("createSocket: $e")
                return
            }
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(endpoint.first, endpoint.second.toInt()), TCP_TIMEOUT_MS)
            } catch (e: IOException) {
                try {
                    socket.close()
                } catch (closing: IOException) {
                    // Never connected.
                }
                tcpFailed(e.toString())
                return
            }
            tcpMs = SystemClock.elapsedRealtime() - started
            tcpState = "connected"
            tcp = socket
            note("tcp connected after ${tcpMs}ms")
            // As 17.6 reports success over the bootstrap link once it is connected.
            outbox.put(WirelessBootstrap.Frame(WirelessBootstrap.CONNECT_STATUS, WirelessBootstrap.connectStatus(0, null)))
            main.post { startSession() }
        }

        fun tcpFailed(why: String) {
            tcpState = "failed"
            outcome = "tcp_failed"
            note("tcp failed: $why")
            endSelf("TCP to the head unit failed")
        }

        /** Main thread: an ordinary attempt with the saved settings, over this socket. */
        fun startSession() {
            if (ended.get()) return
            if (!SessionController.identityReady) {
                outcome = "identity_not_ready"
                return finish("the source identity is not ready")
            }
            val settings = labSettings?.copy(labSocket = null, wireless = true)
                ?: AppSettings.load(app).attempt(IdentityWork.trust(app).pins()).copy(wireless = true)
            val attempt = SessionController.begin(settings)
            if (attempt == null) {
                outcome = "session_busy"
                return finish("another session is active")
            }
            generation = attempt.generation
            SessionController.note("wireless attempt over TCP to the head unit (W2)")
            SessionController.completed(attempt, "accessory")
            SessionController.move(Stage.PERMISSION, "wireless transport; no USB accessory")
            SessionController.completed(attempt, "permission")
            attempt.serviceRequested = true
            try {
                app.startForegroundService(ProjectionService.startIntent(app, attempt.generation))
            } catch (e: RuntimeException) {
                attempt.serviceRequested = false
                SessionController.finish(attempt, "FGS_START_FAILED", "startForegroundService threw $e")
                outcome = "service_start_failed"
                return finish("the projection service did not start")
            }
            outcome = "session_started"
            note("projection session generation ${attempt.generation} started")
            status(app.getString(R.string.wireless_link_projecting))
        }

        /** Main thread. Closes everything, in the order that lets the head unit see it. */
        fun finish(why: String) {
            if (!ended.compareAndSet(false, true)) return
            endedWhy = why
            synchronized(session) { session.note(now(), "link ended: $why") }
            // A session still running over the socket is stopped first; closing our copy of
            // the descriptor alone would leave native's duplicate open.
            val gen = generation
            if (gen != null && SessionController.attempt?.generation == gen && SessionController.stage != Stage.IDLE) {
                app.startService(ProjectionService.stopIntent(app))
            }
            tcp?.let { socket ->
                try {
                    socket.shutdownInput()
                    socket.shutdownOutput()
                } catch (e: IOException) {
                    // Already closed by the peer.
                }
                try {
                    socket.close()
                } catch (e: IOException) {
                    // Closing.
                }
            }
            callback?.let { cb ->
                try {
                    app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
                } catch (e: IllegalArgumentException) {
                    // Never registered.
                }
            }
            try {
                rfcomm?.close()
            } catch (e: IOException) {
                // Closing.
            }
            if (labEndpoint == null) {
                try {
                    app.unregisterReceiver(profiles)
                } catch (e: IllegalArgumentException) {
                    // Not registered.
                }
            }
            thread.quitSafely()
            save()
            if (link === this) link = null
            status(app.getString(R.string.wireless_link_ended, outcome ?: "ended", why))
        }

        fun save() {
            val w2 = WirelessBootstrap.W2Summary(network, networkMs, tcpState, tcpMs, generation, endedWhy, outcome)
            val text = synchronized(session) {
                WirelessBootstrap.report(utc, versionName(), Build.VERSION.SDK_INT, deviceClass, connectMs, connectError, session, w2)
            }
            ReportPersistence.store?.save(reportName, text)
        }

        fun versionName(): String =
            try {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                "unknown"
            }
    }
}
