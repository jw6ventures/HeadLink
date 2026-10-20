package com.jw6ventures.headlink

import android.content.Context
import android.net.Network
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress

/**
 * What the wireless keep-awake ([WirelessKeepAwake]) saw, for the `[wireless_connect]`
 * report: echoes sent and answered, and how long the answers took, kept apart for the
 * screen on and off, since the screen is what changes the phone's Wi-Fi sleep.
 *
 * Reply times go into fixed buckets, so a drive of any length costs the same memory;
 * the percentiles are read off the buckets and reported as their upper bounds.
 *
 * Pure Kotlin, host-tested (`WirelessKeepAwakeTest`). Thread-safe.
 */
class KeepAwakeStats(val on: Boolean, val intervalMs: Long) {
    private class Side {
        var sent = 0
        var replies = 0
        var over100 = 0
        var maxMs = -1L
        val buckets = IntArray(BOUNDS_MS.size + 1)
    }

    private val screenOn = Side()
    private val screenOff = Side()

    @get:Synchronized @set:Synchronized
    var error: String? = null

    private var sendFailures = 0
    private var lastSendFailure: String? = null
    private var unmatched = 0

    @Synchronized
    fun sent(screen: Boolean) {
        side(screen).sent++
    }

    @Synchronized
    fun sendFailed(why: String) {
        sendFailures++
        lastSendFailure = why
    }

    /** A reply to an echo sent while the screen was [screen], [rttMs] after it was sent. */
    @Synchronized
    fun replied(screen: Boolean, rttMs: Long) {
        val side = side(screen)
        side.replies++
        if (rttMs > 100) side.over100++
        if (rttMs > side.maxMs) side.maxMs = rttMs
        val index = BOUNDS_MS.indexOfFirst { rttMs <= it }
        side.buckets[if (index < 0) BOUNDS_MS.size else index]++
    }

    /** A reply matching no outstanding echo: a duplicate, or another socket's. */
    @Synchronized
    fun unmatched() {
        unmatched++
    }

    @Synchronized
    fun replies(screen: Boolean): Int = side(screen).replies

    private fun side(screen: Boolean) = if (screen) screenOn else screenOff

    /** The report's `keep_awake…` lines. */
    @Synchronized
    fun lines(): List<String> = buildList {
        add("keep_awake=${if (on) "on" else "off"} interval_ms=$intervalMs")
        add("keep_awake_error=${error ?: "none"}")
        add("keep_awake_send_failures=$sendFailures last=${lastSendFailure ?: "none"} unmatched_replies=$unmatched")
        for ((name, side) in listOf("screen_on" to screenOn, "screen_off" to screenOff)) {
            add(
                "keep_awake_$name=sent=${side.sent} replies=${side.replies} " +
                    "p50_ms=${percentile(side, 50)} p95_ms=${percentile(side, 95)} " +
                    "max_ms=${if (side.maxMs < 0) "-" else side.maxMs} over_100ms=${side.over100}",
            )
            add("keep_awake_${name}_buckets=" + side.buckets.indices.joinToString(" ") { "${label(it)}:${side.buckets[it]}" })
        }
    }

    private fun percentile(side: Side, percent: Int): String {
        if (side.replies == 0) return "-"
        // The smallest bucket holding at least [percent]% of the replies.
        val wanted = (side.replies * percent + 99) / 100
        var seen = 0
        for (i in side.buckets.indices) {
            seen += side.buckets[i]
            if (seen >= wanted) return label(i)
        }
        return label(side.buckets.size - 1)
    }

    private fun label(index: Int) = if (index < BOUNDS_MS.size) "<=${BOUNDS_MS[index]}" else ">${BOUNDS_MS.last()}"

    companion object {
        /** Bucket upper bounds; the last bucket holds anything slower. */
        val BOUNDS_MS = longArrayOf(5, 10, 20, 50, 100, 200, 500, 1000, 2000)
        /** On: often enough that the Wi-Fi chip never dozes between video frames. */
        const val ON_INTERVAL_MS = 50L
        /** Off: one echo a second, only to measure how long replies take. */
        const val OFF_INTERVAL_MS = 1_000L
    }
}

/**
 * The wireless keep-awake (spec §5.2, the keep-awake amendment). While a wireless session
 * runs, sends a small ICMP echo to the head unit's own address over the head unit's Wi-Fi,
 * every [KeepAwakeStats.ON_INTERVAL_MS] when on, and once a second when off.
 *
 * Why: with the screen off, the Pixel's Wi-Fi driver listens for the access point's
 * "frames waiting" beacon only every ~925 ms (bcmdhd `ENABLE_MAX_DTIM_IN_SUSPEND`), and
 * Android's own DTIM multiplier for an IPv4-only network is 9. The chip stays awake while
 * it transmits, though (power-save mode PM2, which Pixel builds keep through suspend). When
 * the video window fills, the phone stops transmitting and waits for the head unit's
 * acknowledgements; asleep, it may not collect them for most of a second. Echoes keep it
 * transmitting, and the replies time the delay. Google's Android Auto instead holds a
 * Wi-Fi lock, which Android honours with the screen off only for the car-projection
 * permission holder (AOSP WifiLockManager), so that route is closed to this app.
 *
 * Uses an unprivileged ICMP datagram socket, as libcore's `InetAddress.isReachable` does;
 * the kernel fills in the identifier and checksum. If the head unit never replies, the
 * echoes still keep the chip awake; the report then shows no replies.
 */
class WirelessKeepAwake(
    private val context: Context,
    private val network: Network?,
    private val target: InetAddress,
    val stats: KeepAwakeStats,
) {
    @Volatile
    private var running = true
    private val thread = Thread({ run() }, "wireless-keep-awake").apply { isDaemon = true }

    fun start() = thread.start()

    /** Stops within one interval; safe from any thread, more than once. */
    fun stop() {
        running = false
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        val power = context.getSystemService(PowerManager::class.java)
        val fd: FileDescriptor = try {
            Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
        } catch (e: ErrnoException) {
            stats.error = "socket: ${e.message}"
            return
        }
        try {
            network?.bindSocket(fd)
            loop(fd, power)
        } catch (e: IOException) {
            stats.error = "bind: ${e.message}"
        } catch (e: ErrnoException) {
            stats.error = "${e.message}"
        } finally {
            try {
                Os.close(fd)
            } catch (e: ErrnoException) {
                // Closing.
            }
        }
    }

    private fun loop(fd: FileDescriptor, power: PowerManager) {
        val interval = stats.intervalMs
        // By sequence number (16 bits): when each echo went, and with the screen on or off.
        val sentAt = LongArray(65_536)
        val sentScreen = BooleanArray(65_536)
        val packet = ByteArray(16)
        val buffer = ByteArray(128)
        val poll = arrayOf(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() })
        var seq = 0
        var next = SystemClock.elapsedRealtime()
        while (running) {
            val now = SystemClock.elapsedRealtime()
            if (now >= next) {
                // Echo request: type 8, code 0; identifier and checksum are the kernel's.
                packet.fill(0)
                packet[0] = 8
                packet[6] = (seq shr 8).toByte()
                packet[7] = seq.toByte()
                val screen = power.isInteractive
                try {
                    Os.sendto(fd, packet, 0, packet.size, 0, target, 0)
                    sentAt[seq] = now
                    sentScreen[seq] = screen
                    stats.sent(screen)
                } catch (e: ErrnoException) {
                    stats.sendFailed(e.message ?: e.toString())
                } catch (e: java.net.SocketException) {
                    stats.sendFailed(e.message ?: e.toString())
                }
                seq = (seq + 1) and 0xFFFF
                next += interval
                if (next <= now) next = now + interval
            }
            // Wait for a reply until the next echo is due, then read every reply waiting:
            // each read only after a poll says one is there, so none blocks.
            var wait = (next - SystemClock.elapsedRealtime()).coerceIn(0, interval).toInt()
            while (true) {
                poll[0].revents = 0
                val ready = try {
                    Os.poll(poll, wait)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) 0 else throw e
                }
                if (ready <= 0) break
                wait = 0
                val count = try {
                    Os.recvfrom(fd, buffer, 0, buffer.size, 0, null)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    // A queued ICMP error (unreachable, say): reading it clears it.
                    stats.sendFailed("receive: ${e.message}")
                    break
                } catch (e: java.net.SocketException) {
                    break
                }
                // Echo reply: type 0. The socket delivers the ICMP header onward.
                if (count < 8 || buffer[0].toInt() != 0) continue
                val replySeq = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
                val at = sentAt[replySeq]
                if (at == 0L) {
                    stats.unmatched()
                } else {
                    stats.replied(sentScreen[replySeq], SystemClock.elapsedRealtime() - at)
                    sentAt[replySeq] = 0L
                }
            }
        }
    }
}

/**
 * The head unit's Wi-Fi link as Android reports it during a wireless session, for the
 * `[wireless_connect]` report: the interface (a second `wlan` means Android gave the
 * local-only network its own station), band, standard, signal and link rates. Only
 * numbers and an interface name; never the SSID or BSSID (§10).
 *
 * Android polls the signal only on its primary station, so on a secondary one the
 * signal may sit unchanged; the interface line says which this was.
 *
 * Pure Kotlin, host-tested (`WirelessKeepAwakeTest`). Thread-safe.
 */
class WifiLinkStats {
    private var samples = 0
    private var iface: String? = null
    private var frequencyMhz = 0
    private var standard = 0
    private val rssi = Range()
    private val tx = Range()
    private val rx = Range()

    private class Range {
        var last: Int? = null
        var min: Int? = null
        var max: Int? = null

        fun add(value: Int?) {
            if (value == null) return
            last = value
            min = minOf(min ?: value, value)
            max = maxOf(max ?: value, value)
        }

        override fun toString() = "last=${last ?: "-"} min=${min ?: "-"} max=${max ?: "-"}"
    }

    /**
     * One reading. Android's "unknown" values (an RSSI of -127 or less, a rate below 1)
     * are skipped, so they never pull a minimum down.
     */
    @Synchronized
    fun sample(iface: String?, frequencyMhz: Int, standard: Int, rssiDbm: Int, txMbps: Int, rxMbps: Int) {
        samples++
        if (iface != null) this.iface = iface
        if (frequencyMhz > 0) this.frequencyMhz = frequencyMhz
        if (standard > 0) this.standard = standard
        rssi.add(rssiDbm.takeIf { it > INVALID_RSSI })
        tx.add(txMbps.takeIf { it > 0 })
        rx.add(rxMbps.takeIf { it > 0 })
    }

    /** A reading with no Wi-Fi details: the network's capabilities were not available. */
    @Synchronized
    fun sampleInterfaceOnly(iface: String?) {
        samples++
        if (iface != null) this.iface = iface
    }

    /** The latest reading in a few words, for the event notes. */
    @Synchronized
    fun describe(): String =
        if (samples == 0) {
            "wifi: no reading"
        } else {
            "wifi ${iface ?: "?"} ${if (frequencyMhz > 0) "$frequencyMhz MHz" else "? MHz"} " +
                "rssi ${rssi.last ?: "?"} tx ${tx.last ?: "?"} rx ${rx.last ?: "?"} Mbps"
        }

    /** The report's `wifi_link…` lines. */
    @Synchronized
    fun lines(): List<String> = listOf(
        "wifi_link=samples=$samples iface=${iface ?: "-"} frequency_mhz=${if (frequencyMhz > 0) frequencyMhz else "-"} " +
            "standard=${standardName(standard)}",
        "wifi_link_rssi_dbm=$rssi",
        "wifi_link_tx_mbps=$tx",
        "wifi_link_rx_mbps=$rx",
    )

    companion object {
        /** `WifiInfo.INVALID_RSSI`. */
        const val INVALID_RSSI = -127

        /** `ScanResult.WIFI_STANDARD_*` in words. */
        fun standardName(standard: Int): String = when (standard) {
            0 -> "-"
            1 -> "legacy"
            4 -> "11n"
            5 -> "11ac"
            6 -> "11ax"
            7 -> "11ad"
            8 -> "11be"
            else -> "standard_$standard"
        }
    }
}
