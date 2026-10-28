package com.jw6ventures.headlink

import android.annotation.SuppressLint
import android.app.Application
import android.app.KeyguardManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
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
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * W2 (docs/connecting.md, "The sequence"; spec §5.2, the W2 amendment): one wireless
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
 * Standby (spec §5.2, the standby amendment; [WirelessStandby]): a link to the truck the
 * app is linked to ([WirelessAuto]) does not end with its projection while the truck's
 * Bluetooth is still connected. It keeps the RFCOMM connection, or makes a new one, and
 * waits for the truck's next WifiStartRequest, which is how the Android Auto button on the
 * truck's screen reaches the phone. While it waits it holds no foreground service and sends
 * nothing unprompted; a start it accepts takes the same path as any other.
 *
 * Main-thread API. RFCOMM runs on its own thread, the network and TCP on [Link.thread].
 */
@SuppressLint("MissingPermission")
object WirelessLink {
    /** The first request includes the operator reading Android's prompt. */
    private const val NETWORK_TIMEOUT_MS = 60_000
    private const val TCP_TIMEOUT_MS = 10_000
    /**
     * W3: an automatic start comes as the truck's Bluetooth connects, which may be before
     * SYNC's bootstrap service listens, so its RFCOMM connect is tried this many times.
     */
    private const val AUTO_RFCOMM_TRIES = 4
    private const val AUTO_RFCOMM_GAP_MS = 3_000L
    /**
     * SYNC 4.0 sometimes ends the bootstrap before its Wi-Fi details: it goes quiet after
     * our version reply (reports 9912ea14, e6fa815f) or closes RFCOMM at once (309f4dcc).
     * A later connection has always worked, so the link starts over this many more times,
     * this far apart (docs/connecting.md, "Bootstrap retry").
     */
    private const val BOOTSTRAP_RETRIES = 2
    private const val BOOTSTRAP_RETRY_GAP_MS = 15_000L
    /** How long a session start waits for the previous session to finish releasing. */
    private const val IDLE_WAIT_MS = 5_000L
    /** Standing by, a frame from the truck holds the phone awake this long, to answer it. */
    private const val FRAME_WAKE_MS = 5_000L
    /** From the truck's accepted start request until the session starts, at most. */
    private const val SETUP_WAKE_MS = 90_000L
    /** How long a session start waits for the source identity, as a plug-in start does. */
    private const val IDENTITY_WAIT_MS = AutoStart.WAIT_MS
    /** How long the Wi-Fi request waits for [WirelessLinkService] to hold the foreground. */
    private const val FOREGROUND_WAIT_MS = 5_000L
    /**
     * A refusal this soon after the request is Android's foreground check, not the operator
     * or a missing network; it is tried once more ([Link.joinWifi]).
     */
    private const val EARLY_REFUSAL_MS = 2_000L
    /** How often the Wi-Fi link is read and noted while a session runs. */
    private const val SNAPSHOT_MS = 30_000L
    /** A dotted IPv4 literal: the keep-awake never resolves a name. */
    private val IPV4 = Regex("[0-9]{1,3}([.][0-9]{1,3}){3}")
    private const val SALT_FILE = "wireless-salt"
    private const val SALT_BYTES = 16
    private const val DEVICE_ID_FILE = "wireless-device-id"

    private val main = Handler(Looper.getMainLooper())
    private var link: Link? = null

    /**
     * Progress in words for whichever screens are showing (Home's Connect wirelessly and
     * the probe can both be open); main thread.
     */
    private val listeners = mutableSetOf<(String) -> Unit>()

    /** The latest progress line, kept after the link ends so Home can still show why. Main thread. */
    var lastStatus: String? = null
        private set

    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners -= listener
    }

    val active: Boolean
        get() = link != null

    /**
     * True while a link is still setting up, before it has started a session:
     * [WirelessLinkService] holds the foreground only for this part. Main thread.
     */
    val settingUp: Boolean
        get() = link?.let { it.generation == null && !it.ended.get() && !it.waiting } ?: false

    /** True while a link stands by for the truck's start request ([WirelessStandby]). Main thread. */
    val waiting: Boolean
        get() = link?.let { it.waiting && !it.ended.get() } ?: false

    /**
     * Android accepts the local-only Wi-Fi request only from a foreground app or service
     * (`WifiNetworkFactory`); being woken by the companion-device service (W3) is not
     * enough. [WirelessLinkService] says when it has asked for the foreground, and when it
     * holds it, and the join waits for that. Any thread.
     */
    @Volatile
    var foregroundRequested = false

    @Volatile
    var foregroundHeld = false

    /** The connected TCP socket while a link has one. Any thread. */
    fun socket(): Socket? = link?.tcp

    /**
     * Starts a link to [device]. False when one is already running. [auto] is W3's start
     * when the truck's Bluetooth connects: it retries the RFCOMM connect and says so in the
     * report. Main thread.
     */
    fun connect(context: Context, device: BluetoothDevice, auto: Boolean = false): Boolean {
        if (link != null) return false
        val app = context.applicationContext as Application
        // Standby needs the truck link and its Bluetooth up; an automatic start then waits
        // for the truck's own start request instead of asking for one.
        val standby = WirelessAuto.standbyFor(app, device.address)
        val created = Link(app, device, auto = auto, standby = standby, waitFirst = auto && standby)
        link = created
        if (!created.waiting) WirelessLinkService.start(created.app)
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
        WirelessLinkService.start(created.app)
        created.start()
        return true
    }

    /**
     * Ends the link, from any thread. Safe to call more than once. [operator] when the
     * operator cancelled it: a standby link then goes back to waiting for the button only.
     */
    fun end(why: String, operator: Boolean = false) {
        main.post { link?.finish(why, operator) }
    }

    /**
     * The projection session [generation] has ended (ProjectionService), from any thread.
     * Only the link that started it is touched: it may already have moved on to standing by.
     */
    fun sessionEnded(generation: Long, why: String, operator: Boolean) {
        main.post { link?.takeIf { it.generation == generation }?.finish(why, operator) }
    }

    /** Main thread: ends a link that is standing by, at once, so another can start. */
    fun stopWaiting(why: String) {
        link?.takeIf { it.waiting }?.finish(why, final = true)
    }

    /**
     * The truck at [address] is gone over Bluetooth, from any thread. A link standing by
     * for it ends; one that is projecting carries on over Wi-Fi and ends with its session.
     */
    fun truckLeft(address: String) {
        main.post {
            val current = link ?: return@post
            if (!current.device?.address.equals(address, ignoreCase = true)) return@post
            if (current.waiting) current.finish("the car's Bluetooth disconnected", final = true)
        }
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

    /**
     * Paired devices with vehicles first: car audio, or hands-free, which is what Ford
     * SYNC 4 calls itself (0x0408); then by name.
     */
    fun vehiclesFirst(bonded: Collection<BluetoothDevice>): List<BluetoothDevice> {
        val vehicle = setOf(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE)
        return bonded.sortedWith(compareBy({ it.bluetoothClass?.deviceClass !in vehicle }, { it.name.orEmpty() }))
    }

    /** True for a car-audio or hands-free device, as [vehiclesFirst] sorts them. */
    fun isVehicle(device: BluetoothDevice): Boolean =
        device.bluetoothClass?.deviceClass in setOf(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE)

    private fun status(text: String) {
        main.post {
            lastStatus = text
            listeners.toList().forEach { it(text) }
        }
    }

    /** How a conversation over one RFCOMM connection ended ([Link.converse]). */
    private enum class Conversation {
        /** The link ended. */
        ENDED,
        /** The connection closed, or the conversation gave up, before the Wi-Fi details. */
        BEFORE_DETAILS,
        /** The connection closed after the Wi-Fi details; the join and the session carry on. */
        AFTER_DETAILS,
    }

    private class Link(
        val app: Application,
        val device: BluetoothDevice?,
        val labEndpoint: Pair<String, Long>? = null,
        val labSettings: AttemptSettings? = null,
        val auto: Boolean = false,
        /** Standby ([WirelessStandby]): the link outlives its projections while the truck is connected. */
        standby: Boolean = false,
        /** Standby from the start: wait for the truck's start request rather than setting up. */
        waitFirst: Boolean = false,
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
        @Volatile var joinRequested = false
        /** Standing by: no projection is being set up or running ([WirelessStandby]). */
        @Volatile var waiting = waitFirst
        /**
         * One projection's setup and session: [rearm] moves it on, so work still in flight
         * for the last one (a TCP connect, a network callback) finds itself stale and stops.
         */
        @Volatile var epoch = 0
        /** Start requests the conversation has accepted, as this link has acted on them. */
        var acceptsSeen = 0
        /** The accepted start's reason and when, for [WirelessAuto.projectionEnded]; main thread reads. */
        @Volatile var acceptedReason: Long? = null
        @Volatile var acceptedAt = 0L
        @Volatile var projections = 0
        /** The current start was the truck's, accepted while standing by. */
        @Volatile var fromWaiting = false
        /** While a start the truck asked for sets up, so a dozing phone carries it through. */
        val wake: PowerManager.WakeLock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeadLink:wireless-link")
            .apply { setReferenceCounted(false) }
        /** The keep-awake, from the TCP connection to the link's end. */
        @Volatile var keepAwake: WirelessKeepAwake? = null
        /** The head unit's network once TCP is up, for the Wi-Fi readings; null in the lab. */
        @Volatile var joined: Network? = null
        val wifi = WifiLinkStats()
        /**
         * Standing by is possible ([WirelessStandby]). Set at the start, or once an RFCOMM
         * connection proves the linked truck is there (a link from Home before Android said so).
         */
        @Volatile var standby = standby
        @Volatile var screenRegistered = false
        /** Uptime by which [startSession] stops waiting for the source identity; main thread. */
        var identityWaitUntil = 0L
        /** Uptime by which [startSession] stops waiting for the last session to finish; main thread. */
        var idleWaitUntil = 0L

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

        /**
         * The truck's Bluetooth connection itself. Gone, a standby link has nothing to wait
         * on; back, it is present again ([WirelessAuto.present]).
         */
        val acl = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val from = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                val address = device?.address ?: return
                if (!from?.address.equals(address, ignoreCase = true)) return
                if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                    note("bluetooth acl disconnected")
                    WirelessAuto.markAbsent(address)
                    truckLeft(address)
                } else {
                    note("bluetooth acl connected")
                    WirelessAuto.markPresent(app, address)
                }
            }
        }

        /** Screen and lock changes while TCP is up: the screen is what changes the Wi-Fi's sleep. */
        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val what = when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> "screen off"
                    Intent.ACTION_SCREEN_ON -> "screen on"
                    Intent.ACTION_USER_PRESENT -> "phone unlocked"
                    else -> return
                }
                val reading = readWifi()
                note("$what; $reading")
                sessionNote("$what (wireless; $reading)")
            }
        }

        fun now() = if (origin == 0L) 0L else SystemClock.elapsedRealtime() - origin

        /**
         * Ends this link, not whichever one is current by the time the post runs, and only
         * if it is still on projection [at] ([epoch]); [final] skips standing by.
         */
        fun endSelf(why: String, at: Int = epoch, final: Boolean = false) {
            main.post { if (at == epoch) finish(why, final = final) }
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
            val aclFilter = IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            aclFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            app.registerReceiver(acl, aclFilter, null, handler, Context.RECEIVER_EXPORTED)
            if (auto) note("started automatically: the linked head unit connected over Bluetooth (W3)")
            if (standby) note("standby: this link waits for the car between projections; automatic starts ${if (acceptAuto()) "taken" else "held"}")
            status(app.getString(if (waiting) R.string.wireless_link_waiting else R.string.wireless_link_bootstrap))
            Thread({ rfcommLoop() }, "wireless-rfcomm").start()
        }

        /** Whether the truck's own starts are taken now ([WirelessStandby.acceptAuto]). */
        fun acceptAuto(): Boolean = WirelessAuto.acceptAuto(app)

        /**
         * RFCOMM thread: connect, then run the session until the link ends. A bootstrap that
         * ends before the Wi-Fi details starts over on a new connection, [BOOTSTRAP_RETRIES]
         * times. Standing by, it starts over for as long as the truck is connected: a
         * connection the truck closes is made again ([WirelessStandby.gapMs]), never under a
         * projection, and an automatic start's first [BOOTSTRAP_RETRIES] silent waits still
         * end at the deadline, since a new connection has unstuck SYNC before.
         */
        fun rfcommLoop() {
            val device = device ?: return
            var round = 0
            var silentWaits = 0
            var shortInARow = 0
            var failedInARow = 0
            while (!ended.get()) {
                if (round > 0) {
                    // Never a second connection under a projection: the next one waits for it to end.
                    while (!ended.get() && (joinRequested || generation != null)) {
                        if (!pause(1_000)) return
                    }
                    // Standing by, a start that did not reach the Wi-Fi details goes back to
                    // waiting: at once if the truck asked for it, after the retries if Home did.
                    if (standby && !waiting && (fromWaiting || round > BOOTSTRAP_RETRIES)) {
                        backToWaiting("the car did not finish its wireless setup")
                    }
                    if (!standby && round > BOOTSTRAP_RETRIES) break
                    val outcomeKey = synchronized(session) { session.outcome().key }
                    if (waiting) {
                        val gap = maxOf(WirelessStandby.gapMs(shortInARow), afterProjectionGap())
                        note("standby: bootstrap link closed ($outcomeKey); connecting again in ${gap / 1000} s")
                        status(app.getString(R.string.wireless_link_waiting))
                        if (!pause(gap)) return
                        if (!WirelessAuto.isPresent(device.address)) {
                            endSelf("the car's Bluetooth is not connected", final = true)
                            return
                        }
                    } else {
                        note("bootstrap ended before the Wi-Fi details ($outcomeKey); trying again in ${BOOTSTRAP_RETRY_GAP_MS / 1000} s, retry $round of $BOOTSTRAP_RETRIES")
                        status(app.getString(R.string.wireless_link_retry, BOOTSTRAP_RETRY_GAP_MS / 1000, round, BOOTSTRAP_RETRIES))
                        if (!pause(BOOTSTRAP_RETRY_GAP_MS)) return
                        status(app.getString(R.string.wireless_link_bootstrap))
                    }
                }
                round++
                val s = rfcommConnectTries(device, patient = auto || standby || round > 1, endOnFailure = !standby)
                if (s == null) {
                    if (ended.get() || !standby) return
                    failedInARow++
                    shortInARow++
                    if (failedInARow >= WirelessStandby.CONNECT_FAILURE_LIMIT) {
                        outcome = WirelessBootstrap.Outcome.CONNECT_FAILED.key
                        endSelf("standby: the car's bootstrap service did not answer $failedInARow times in a row", final = true)
                        return
                    }
                    continue
                }
                failedInARow = 0
                WirelessAuto.markPresent(app, device.address)
                if (!standby && WirelessAuto.standbyFor(app, device.address)) {
                    standby = true
                    note("standby: the linked car answered; this link will wait for it between projections")
                }
                val began = SystemClock.elapsedRealtime()
                val deadline = waiting && acceptAuto() && silentWaits < BOOTSTRAP_RETRIES
                val result = converse(s, deadline)
                try {
                    s.close()
                } catch (e: IOException) {
                    // The head unit closed it already.
                }
                if (result == Conversation.ENDED) return
                // Not standing by, a link that has the Wi-Fi details needs RFCOMM no more.
                if (result == Conversation.AFTER_DETAILS && !standby) return
                if (synchronized(session) { session.doneWhy } == "deadline") silentWaits++
                shortInARow = if (SystemClock.elapsedRealtime() - began < WirelessStandby.SHORT_CONNECTION_MS) shortInARow + 1 else 0
            }
            if (ended.get()) return
            if (outcome == null) outcome = synchronized(session) { session.outcome().key }
            endSelf("bootstrap link closed before the Wi-Fi details")
        }

        /** RFCOMM thread: a start that never reached a session; the link waits for the truck again. */
        fun backToWaiting(why: String) {
            projectionEnded()
            fromWaiting = false
            synchronized(session) { waiting = true }
            if (wake.isHeld) wake.release()
            note("standby: $why; waiting for the car's start request")
            status(app.getString(R.string.wireless_link_waiting))
        }

        /** Uptime when the last projection ended, so the next connection lets the head unit settle. */
        @Volatile var projectionEndedAt = 0L

        fun afterProjectionGap(): Long =
            if (projectionEndedAt == 0L) 0L
            else maxOf(0L, projectionEndedAt + WirelessStandby.AFTER_PROJECTION_MS - SystemClock.elapsedRealtime())

        /** Sleeps [ms] unless the link ends first; false if it ended. RFCOMM thread. */
        fun pause(ms: Long): Boolean {
            val until = SystemClock.elapsedRealtime() + ms
            while (!ended.get()) {
                val left = until - SystemClock.elapsedRealtime()
                if (left <= 0) return true
                try {
                    Thread.sleep(minOf(left, 100L))
                } catch (e: InterruptedException) {
                    return false
                }
            }
            return false
        }

        /**
         * RFCOMM thread: one connect, or when [patient] (an automatic start, standby, or any
         * start over) [AUTO_RFCOMM_TRIES] of them. Null when they all failed (the link is then
         * ended, unless [endOnFailure] is false) or the link ended meanwhile.
         */
        fun rfcommConnectTries(device: BluetoothDevice, patient: Boolean, endOnFailure: Boolean = true): BluetoothSocket? {
            val started = SystemClock.elapsedRealtime()
            val tries = if (patient) AUTO_RFCOMM_TRIES else 1
            var s: BluetoothSocket? = null
            for (attempt in 1..tries) {
                if (ended.get()) return null
                connectError = null
                s = rfcommConnect(device)
                if (connectError == null && s != null) break
                if (attempt < tries) {
                    note("rfcomm connect $attempt of $tries failed: $connectError; trying again")
                    if (!pause(AUTO_RFCOMM_GAP_MS)) return null
                }
            }
            connectMs = SystemClock.elapsedRealtime() - started
            if (connectError != null || s == null) {
                if (!endOnFailure) {
                    note("rfcomm connect failed $tries times: $connectError")
                    return null
                }
                outcome = WirelessBootstrap.Outcome.CONNECT_FAILED.key
                endSelf("rfcomm connect failed")
                return null
            }
            if (ended.get()) {
                // Cancelled while connecting: finish may have closed the previous socket, not this one.
                try {
                    s.close()
                } catch (e: IOException) {
                    // Closing.
                }
                return null
            }
            return s
        }

        /** One RFCOMM connect to the bootstrap service; sets [connectError] when it fails. */
        fun rfcommConnect(device: BluetoothDevice): BluetoothSocket? {
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
                    // A retry makes a new socket; this one is done.
                    try {
                        s.close()
                    } catch (closing: IOException) {
                        // Never connected.
                    }
                } finally {
                    watchdog.interrupt()
                }
            }
            return s
        }

        /**
         * RFCOMM thread: the bootstrap conversation over a connected socket, until the link
         * ends or the connection closes. Standing by ([waiting]), the conversation waits for
         * the truck's start request, ending at the deadline only when [deadline].
         */
        fun converse(s: BluetoothSocket, deadline: Boolean): Conversation {
            if (origin == 0L) {
                origin = SystemClock.elapsedRealtime()
            } else {
                synchronized(session) { session.restart(now()) }
                note("rfcomm connected again after ${connectMs}ms")
            }
            if (waiting) synchronized(session) { session.standBy(now(), deadline) }
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
                var startNow = false
                var fromWait = false
                var reason: Long? = null
                // Only a start request needs the setting, and reading it costs a preferences load.
                val autoNow = if (item is WirelessBootstrap.Frame && item.id == WirelessBootstrap.START_REQUEST) acceptAuto() else null
                // A frame while standing by wakes the phone just long enough to answer it.
                if (item is WirelessBootstrap.Frame && waiting) wake.acquire(FRAME_WAKE_MS)
                val at = epoch
                synchronized(session) {
                    if (autoNow != null) session.acceptAuto = autoNow
                    when (item) {
                        null -> out += session.tick(now())
                        is WirelessBootstrap.Frame -> out += session.received(item, now()) + session.tick(now())
                        else -> session.closed(now(), item.toString())
                    }
                    while (true) out += session.send(outbox.poll() ?: break, now())
                    credentials = session.credentials
                    endpoint = session.start ?: session.versionEndpointSeen
                    done = session.done
                    // Under the session's lock, as [rearm] sets [waiting], so the two agree.
                    if (session.startsAccepted > acceptsSeen) {
                        acceptsSeen = session.startsAccepted
                        startNow = true
                        fromWait = waiting
                        waiting = false
                        reason = session.startReason
                    }
                }
                if (startNow) accepted(reason, at, fromWait)
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
                if (creds != null && !joinRequested && at == epoch) {
                    joinRequested = true
                    if (target == null) {
                        outcome = "no_endpoint"
                        endSelf("Wi-Fi details arrived without an endpoint", at)
                    } else {
                        handler.post { joinWifi(creds, target, at) }
                    }
                }
                if (done) {
                    if (ended.get()) return Conversation.ENDED
                    // Without the Wi-Fi details the caller starts over or ends the link.
                    if (credentials == null) return Conversation.BEFORE_DETAILS
                    // Once the head unit has handed over its Wi-Fi, a closed bootstrap link
                    // is recorded, not fatal: the join and the session carry on.
                    note("bootstrap link closed; the Wi-Fi join and the session carry on")
                    return Conversation.AFTER_DETAILS
                }
            }
            return Conversation.ENDED
        }

        /**
         * RFCOMM thread: the conversation has accepted a start request, on projection [at].
         * Standing by, that is the truck asking: hold the phone awake and the foreground
         * (the Wi-Fi request needs it) until the session starts.
         */
        fun accepted(reason: Long?, at: Int, fromWait: Boolean) {
            acceptedReason = reason
            acceptedAt = SystemClock.elapsedRealtime()
            if (!fromWait) return
            fromWaiting = true
            wake.acquire(SETUP_WAKE_MS)
            val user = WirelessStandby.isUser(reason)
            note("standby: the car asked to start (${WirelessStandby.reasonName(reason)}); setting up")
            status(app.getString(if (user) R.string.wireless_link_button else R.string.wireless_link_bootstrap))
            main.post { if (at == epoch && !ended.get()) WirelessLinkService.start(app) }
        }

        /** Uptime by which the join stops waiting for the foreground; link thread. */
        var foregroundWaitUntil = 0L
        /** Whether an early refusal ([EARLY_REFUSAL_MS]) has been tried again; link thread. */
        var retriedRefusal = false

        /** Link thread: the local-only network request for the head unit's Wi-Fi, for projection [at]. */
        fun joinWifi(credentials: WirelessBootstrap.Credentials, endpoint: Pair<String, Long>, at: Int) {
            if (ended.get() || at != epoch) return
            if (foregroundRequested && !foregroundHeld) {
                if (foregroundWaitUntil == 0L) foregroundWaitUntil = SystemClock.uptimeMillis() + FOREGROUND_WAIT_MS
                if (SystemClock.uptimeMillis() < foregroundWaitUntil) {
                    handler.postDelayed({ joinWifi(credentials, endpoint, at) }, 100)
                    return
                }
                note("the link service did not reach the foreground; requesting the network anyway")
            }
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
                    endSelf("the head unit's Wi-Fi security mode ${credentials.securityMode} is not supported", at)
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
                    if (networkMs != null || at != epoch) return
                    network = "available"
                    networkMs = SystemClock.elapsedRealtime() - requested
                    note("network available after ${networkMs}ms")
                    connectTcp(available, endpoint, at)
                }

                override fun onUnavailable() {
                    if (at != epoch) return
                    if (!retriedRefusal && SystemClock.elapsedRealtime() - requested < EARLY_REFUSAL_MS) {
                        retriedRefusal = true
                        note("network request refused after ${SystemClock.elapsedRealtime() - requested}ms; trying once more")
                        callback = null
                        handler.postDelayed({ joinWifi(credentials, endpoint, at) }, 1_000)
                        return
                    }
                    network = "unavailable"
                    outcome = "network_unavailable"
                    endSelf("the network request was declined or timed out", at)
                }

                override fun onLost(lost: Network) {
                    if (at != epoch) return
                    network = "lost"
                    note("network lost")
                    if (outcome == null) outcome = "network_lost"
                    endSelf("the head unit's Wi-Fi was lost", at)
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
                endSelf("requestNetwork threw $e", at)
            }
        }

        /** Link thread: TCP to the head unit over [network] only (the default network in the lab), for projection [at]. */
        fun connectTcp(network: Network?, endpoint: Pair<String, Long>, at: Int = epoch) {
            if (ended.get() || at != epoch) return
            status(app.getString(R.string.wireless_link_tcp))
            val started = SystemClock.elapsedRealtime()
            val socket = try {
                network?.socketFactory?.createSocket() ?: Socket()
            } catch (e: IOException) {
                tcpFailed("createSocket: $e", at)
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
                tcpFailed(e.toString(), at)
                return
            }
            if (ended.get() || at != epoch) {
                // Moved on while connecting: this socket belongs to no projection.
                try {
                    socket.close()
                } catch (closing: IOException) {
                    // Closing.
                }
                return
            }
            tcpMs = SystemClock.elapsedRealtime() - started
            tcpState = "connected"
            tcp = socket
            note("tcp connected after ${tcpMs}ms")
            watch(network, endpoint)
            // As 17.6 reports success over the bootstrap link once it is connected.
            outbox.put(WirelessBootstrap.Frame(WirelessBootstrap.CONNECT_STATUS, WirelessBootstrap.connectStatus(0, null)))
            main.post { startSession(at) }
        }

        /**
         * Link thread, once TCP is up: the keep-awake (spec §5.2, the keep-awake amendment),
         * and notes of the screen, the lock and the Wi-Fi link for the report.
         */
        fun watch(network: Network?, endpoint: Pair<String, Long>) {
            joined = network
            val on = AppSettings.load(app).wirelessKeepAwake
            val stats = KeepAwakeStats(on, if (on) KeepAwakeStats.ON_INTERVAL_MS else KeepAwakeStats.OFF_INTERVAL_MS)
            if (IPV4.matches(endpoint.first)) {
                keepAwake = WirelessKeepAwake(app, network, InetAddress.getByName(endpoint.first), stats).also { it.start() }
                note("keep-awake ${if (on) "on" else "off (measuring only)"}: an echo every ${stats.intervalMs}ms")
            } else {
                note("keep-awake not started: the endpoint is not an IPv4 address")
            }
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            app.registerReceiver(screen, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
            screenRegistered = true
            note("${screenState()}; ${readWifi()}")
            handler.postDelayed({ snapshot(epoch) }, SNAPSHOT_MS)
        }

        /** Link thread: the Wi-Fi link and the keep-awake's replies, every [SNAPSHOT_MS]. */
        fun snapshot(at: Int) {
            if (ended.get() || at != epoch) return
            val stats = keepAwake?.stats
            val replies = stats?.let { " keep-awake replies on/off ${it.replies(true)}/${it.replies(false)}" } ?: ""
            note("${readWifi()};$replies")
            handler.postDelayed({ snapshot(at) }, SNAPSHOT_MS)
        }

        /** Reads the head unit's Wi-Fi link into [wifi] and describes it. Any thread. */
        fun readWifi(): String {
            val net = joined ?: return "wifi: default network (lab)"
            val connectivity = app.getSystemService(ConnectivityManager::class.java)
            val iface = connectivity.getLinkProperties(net)?.interfaceName
            val info = connectivity.getNetworkCapabilities(net)?.transportInfo as? WifiInfo
            if (info == null) {
                wifi.sampleInterfaceOnly(iface)
            } else {
                wifi.sample(iface, info.frequency, info.wifiStandard, info.rssi, info.txLinkSpeedMbps, info.rxLinkSpeedMbps)
            }
            return wifi.describe()
        }

        fun screenState(): String {
            val interactive = app.getSystemService(PowerManager::class.java)?.isInteractive
            val locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
            val screenWord = when (interactive) {
                true -> "on"
                false -> "off"
                null -> "unknown"
            }
            val lockWord = when (locked) {
                true -> "locked"
                false -> "unlocked"
                null -> "lock unknown"
            }
            return "screen $screenWord, $lockWord"
        }

        /** A note in this link's session's events too, once it has one. */
        fun sessionNote(text: String) {
            main.post {
                val gen = generation ?: return@post
                if (SessionController.attempt?.generation == gen) SessionController.note(text)
            }
        }

        fun tcpFailed(why: String, at: Int) {
            tcpState = "failed"
            outcome = "tcp_failed"
            note("tcp failed: $why")
            endSelf("TCP to the head unit failed", at)
        }

        /** Main thread: an ordinary attempt with the saved settings, over this socket, for projection [at]. */
        fun startSession(at: Int = epoch) {
            if (ended.get() || at != epoch) return
            if (!SessionController.identityReady) {
                // A process the truck's arrival started (W3) is often still loading the
                // identity; wait for it, bounded, as a plug-in start does.
                if (identityWaitUntil == 0L) identityWaitUntil = SystemClock.uptimeMillis() + IDENTITY_WAIT_MS
                if (SessionController.identityBusy && SystemClock.uptimeMillis() < identityWaitUntil) {
                    main.postDelayed({ startSession(at) }, 250)
                    return
                }
                outcome = "identity_not_ready"
                return finish("the source identity is not ready")
            }
            if (SessionController.stage != Stage.IDLE) {
                // Standing by, the truck's button can come while the last session is still
                // releasing; give it a moment rather than refusing the press.
                if (idleWaitUntil == 0L) idleWaitUntil = SystemClock.uptimeMillis() + IDLE_WAIT_MS
                if (SystemClock.uptimeMillis() < idleWaitUntil) {
                    main.postDelayed({ startSession(at) }, 250)
                    return
                }
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
            val keepAwakeWord = keepAwake?.stats?.let { if (it.on) "on" else "off (measuring only)" } ?: "not running"
            SessionController.note("wireless: ${screenState()}; keep-awake $keepAwakeWord")
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
            projections++
            note("projection session generation ${attempt.generation} started")
            status(app.getString(R.string.wireless_link_projecting))
            // The session's own foreground service carries it from here.
            if (wake.isHeld) wake.release()
        }

        /**
         * Main thread. Closes everything, in the order that lets the head unit see it; a
         * standby link whose truck is still connected closes only the projection ([rearm]).
         * [operator] when the operator stopped or cancelled it from the phone; [final] to
         * end even a standby link.
         */
        fun finish(why: String, operator: Boolean = false, final: Boolean = false) {
            if (ended.get()) return
            if (operator) WirelessAuto.operatorStopped = true
            val address = device?.address
            if (standby && !final && address != null && WirelessAuto.standbyFor(app, address)) return rearm(why)
            if (!ended.compareAndSet(false, true)) return
            projectionEnded()
            endedWhy = why
            synchronized(session) { session.note(now(), "link ended: $why") }
            closeProjection()
            try {
                rfcomm?.close()
            } catch (e: IOException) {
                // Closing.
            }
            if (labEndpoint == null) {
                for (receiver in listOf(profiles, acl)) {
                    try {
                        app.unregisterReceiver(receiver)
                    } catch (e: IllegalArgumentException) {
                        // Not registered.
                    }
                }
            }
            if (wake.isHeld) wake.release()
            thread.quitSafely()
            save()
            if (link === this) link = null
            status(app.getString(R.string.wireless_link_ended, outcome ?: "ended", why))
        }

        /**
         * Main thread, standing by: the projection ends and the link waits for the truck's
         * next start request, on the same RFCOMM connection if it is still up (the RFCOMM
         * thread makes a new one otherwise). Work still in flight for this projection finds
         * [epoch] moved on.
         */
        fun rearm(why: String) {
            epoch++
            projectionEnded()
            note("projection ended: $why; standby: waiting for the car's next start request")
            closeProjection()
            handler.removeCallbacksAndMessages(null)
            lastExtra = projectionExtra()
            tcp = null
            joined = null
            callback = null
            keepAwake = null
            generation = null
            identityWaitUntil = 0L
            idleWaitUntil = 0L
            foregroundWaitUntil = 0L
            retriedRefusal = false
            network = "not_requested"
            networkMs = null
            tcpState = "not_attempted"
            tcpMs = null
            outcome = null
            endedWhy = null
            acceptedReason = null
            acceptedAt = 0L
            fromWaiting = false
            projectionEndedAt = SystemClock.elapsedRealtime()
            synchronized(session) {
                session.rearm(now())
                if (!session.done) session.standBy(now(), deadline = false)
                waiting = true
            }
            // Only now: the conversation no longer holds the last start's Wi-Fi details.
            joinRequested = false
            if (wake.isHeld) wake.release()
            save()
            // WirelessLinkService sees the link is no longer setting up and goes.
            status(app.getString(R.string.wireless_link_waiting))
        }

        /** The automatic-start loop guard's bookkeeping, once per accepted start ([WirelessStandby.quickAfter]). */
        fun projectionEnded() {
            val at = acceptedAt
            if (at == 0L) return
            acceptedAt = 0L
            WirelessAuto.projectionEnded(acceptedReason, SystemClock.elapsedRealtime() - at)
        }

        /** Main thread: stops the session, the keep-awake, TCP and the Wi-Fi request of the current projection. */
        fun closeProjection() {
            keepAwake?.stop()
            if (screenRegistered) {
                screenRegistered = false
                try {
                    app.unregisterReceiver(screen)
                } catch (e: IllegalArgumentException) {
                    // Not registered.
                }
            }
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
        }

        /** The last projection's keep-awake and Wi-Fi lines, kept in the report once it has ended. */
        var lastExtra: List<String> = emptyList()

        fun projectionExtra(): List<String> =
            if (tcp == null) lastExtra else (keepAwake?.stats?.lines() ?: emptyList()) + wifi.lines()

        fun save() {
            val standbyLines = if (standby) listOf("standby=on", "standby_projections=$projections", "standby_waiting=$waiting") else emptyList()
            val w2 = WirelessBootstrap.W2Summary(network, networkMs, tcpState, tcpMs, generation, endedWhy, outcome, projectionExtra() + standbyLines)
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
