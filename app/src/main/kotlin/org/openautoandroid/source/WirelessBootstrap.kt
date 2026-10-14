package org.openautoandroid.source

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/**
 * W1 of docs/wireless-investigation.md: connect to the head unit's wireless bootstrap
 * service over RFCOMM, record what it sends, answer only as far as its Wi-Fi details,
 * and stop. The phone joins no network and opens no TCP connection.
 *
 * Everything here is pure JVM, host-tested against scripted head units in
 * WirelessBootstrapTest. WirelessProbeActivity owns the socket and feeds [Session].
 *
 * The message ids and fields come from two head-unit-side projects
 * (WirelessAndroidAutoDongle, aa-proxy-rs; docs/wireless-investigation.md). Their field
 * names are readings, not evidence, so every inbound field is recorded by number as well
 * as interpreted.
 *
 * Redaction (spec §10). The Wi-Fi key is never kept, only its length. The SSID and BSSID
 * are kept as salted hashes, comparable between runs on this install and nowhere else.
 * Any string that looks like a serial, VIN, UUID or MAC is hashed the same way.
 */
object WirelessBootstrap {
    const val START_REQUEST = 1
    const val INFO_REQUEST = 2
    const val INFO_RESPONSE = 3
    const val VERSION_REQUEST = 4
    const val VERSION_RESPONSE = 5
    const val CONNECT_STATUS = 6
    const val START_RESPONSE = 7
    const val PING_REQUEST = 8
    const val PING_RESPONSE = 9
    const val SETUP_INFO = 11

    /** Nothing from the head unit by then: send one WifiInfoRequest unprompted, to learn whether that wakes it. */
    const val NUDGE_MS = 5_000L

    /** Wait for WifiInfoResponse before asking again; Android Auto 17.6 retries the same way. */
    const val INFO_RETRY_MS = 3_000L
    const val INFO_ATTEMPTS = 3

    /** After a WifiVersionResponse, how long to wait for WifiStartRequest before asking for Wi-Fi details anyway. */
    const val START_WAIT_MS = 3_000L

    /** Keep listening this long after WifiInfoResponse, to see what the head unit sends next. */
    const val LINGER_MS = 2_000L

    /** The whole conversation, from connect. */
    const val DEADLINE_MS = 20_000L

    /** RFCOMM connect, separately bounded: a stack that never answers must not hold the screen. */
    const val CONNECT_TIMEOUT_MS = 15_000L

    fun name(id: Int): String =
        when (id) {
            START_REQUEST -> "WifiStartRequest"
            INFO_REQUEST -> "WifiInfoRequest"
            INFO_RESPONSE -> "WifiInfoResponse"
            VERSION_REQUEST -> "WifiVersionRequest"
            VERSION_RESPONSE -> "WifiVersionResponse"
            CONNECT_STATUS -> "WifiConnectStatus"
            START_RESPONSE -> "WifiStartResponse"
            PING_REQUEST -> "WifiPingRequest"
            PING_RESPONSE -> "WifiPingResponse"
            SETUP_INFO -> "WifiSetupInfo"
            else -> "unknown"
        }

    class Frame(val id: Int, val payload: ByteArray)

    /** `u16 BE payload length | u16 BE message id | payload`. */
    fun encode(frame: Frame): ByteArray {
        require(frame.payload.size <= 0xffff) { "payload too long" }
        val out = ByteArray(4 + frame.payload.size)
        out[0] = (frame.payload.size ushr 8).toByte()
        out[1] = frame.payload.size.toByte()
        out[2] = (frame.id ushr 8).toByte()
        out[3] = frame.id.toByte()
        frame.payload.copyInto(out, 4)
        return out
    }

    /** Reassembles frames from whatever chunks RFCOMM delivers. */
    class Deframer {
        private val buffer = ByteArrayOutputStream()

        fun push(bytes: ByteArray, count: Int = bytes.size): List<Frame> {
            buffer.write(bytes, 0, count)
            val frames = mutableListOf<Frame>()
            var data = buffer.toByteArray()
            var at = 0
            while (data.size - at >= 4) {
                val length = ((data[at].toInt() and 0xff) shl 8) or (data[at + 1].toInt() and 0xff)
                if (data.size - at - 4 < length) break
                val id = ((data[at + 2].toInt() and 0xff) shl 8) or (data[at + 3].toInt() and 0xff)
                frames += Frame(id, data.copyOfRange(at + 4, at + 4 + length))
                at += 4 + length
            }
            buffer.reset()
            buffer.write(data, at, data.size - at)
            return frames
        }

        /** Bytes held back waiting for the rest of a frame. */
        fun pending(): Int = buffer.size()
    }

    /** One protobuf field: a varint value, or length-delimited bytes. Fixed-width fields keep their raw bytes. */
    class Field(val number: Int, val wire: Int, val varint: Long?, val bytes: ByteArray?)

    /** The top-level fields of [payload], or null when it is not well-formed protobuf. */
    fun fields(payload: ByteArray): List<Field>? {
        val out = mutableListOf<Field>()
        var at = 0
        fun varint(): Long? {
            var shift = 0
            var value = 0L
            while (at < payload.size && shift < 64) {
                val b = payload[at++].toInt() and 0xff
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
            return null
        }
        while (at < payload.size) {
            val key = varint() ?: return null
            val number = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            if (number <= 0) return null
            when (wire) {
                0 -> out += Field(number, wire, varint() ?: return null, null)
                1, 5 -> {
                    val size = if (wire == 1) 8 else 4
                    if (payload.size - at < size) return null
                    out += Field(number, wire, null, payload.copyOfRange(at, at + size))
                    at += size
                }
                2 -> {
                    val length = varint() ?: return null
                    if (length < 0 || length > payload.size - at) return null
                    out += Field(number, wire, null, payload.copyOfRange(at, at + length.toInt()))
                    at += length.toInt()
                }
                else -> return null
            }
        }
        return out
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun varintField(out: ByteArrayOutputStream, number: Int, value: Long) {
        writeVarint(out, (number.toLong() shl 3) or 0)
        writeVarint(out, value)
    }

    /**
     * Our WifiVersionResponse: the head unit's own major and minor echoed (fields 1 and 2)
     * and status 0 (field 4). No serial, no device id: aa-proxy-rs reads fields 3 and 6
     * as those, and this source sends neither.
     */
    fun versionResponse(major: Long?, minor: Long?): ByteArray {
        val out = ByteArrayOutputStream()
        if (major != null) varintField(out, 1, major)
        if (minor != null) varintField(out, 2, minor)
        varintField(out, 4, 0)
        return out.toByteArray()
    }

    /** Salted hashing for anything identifying. */
    class Redactor(private val salt: ByteArray) {
        fun hash(value: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(salt)
            digest.update(value)
            return "h:" + digest.digest().take(6).joinToString("") { "%02x".format(Locale.ROOT, it) }
        }

        fun hash(value: String): String = hash(value.toByteArray(Charsets.UTF_8))
    }

    /**
     * Serial-, VIN-, UUID- or MAC-shaped: once `-` and `:` are dropped, 8 or more letters and
     * digits with at least one of each, or 12 or more digits. Version strings with `.` or `_`
     * (`3.4.1`, `SYNC4_22198`) stay readable.
     */
    fun looksIdentifying(text: String): Boolean {
        val core = text.filter { it != '-' && it != ':' }
        if (!core.all { it.isLetterOrDigit() }) return false
        return core.length >= 8 && core.any { it.isDigit() } && core.any { it.isLetter() } ||
            core.length >= 12 && core.all { it.isDigit() }
    }

    private fun printable(bytes: ByteArray): String? {
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return null
        }
        return if (text.isNotEmpty() && text.all { it >= ' ' && it != '\u007f' }) text else null
    }

    /**
     * Every field of a message, by number, for the report: varints as values, strings as
     * text unless they look identifying (then hashed), nested messages one level down,
     * anything else as a length. Never used for WifiInfoResponse, which has its own form.
     */
    fun dump(payload: ByteArray, redactor: Redactor, depth: Int = 0): String {
        if (payload.isEmpty()) return "empty"
        val fields = fields(payload) ?: return "malformed len=${payload.size}"
        return fields.joinToString(" ") { field ->
            val n = field.number
            when {
                field.varint != null -> "$n=${field.varint}"
                field.wire != 2 -> "$n=fixed${field.bytes!!.size * 8}"
                else -> {
                    val bytes = field.bytes!!
                    // Nested messages carry key and length bytes below 0x20, so printable
                    // text is a string; anything else is tried as a message, one level down.
                    val text = printable(bytes)
                    val nested = if (text == null && depth == 0) fields(bytes)?.takeIf { it.isNotEmpty() } else null
                    when {
                        text != null -> stringText(n, text, redactor)
                        nested != null -> "$n={${dump(bytes, redactor, depth + 1)}}"
                        else -> "$n=bytes${bytes.size}"
                    }
                }
            }
        }
    }

    private fun stringText(n: Int, text: String, redactor: Redactor): String =
        if (looksIdentifying(text)) "$n=${redactor.hash(text)}" else "$n=\"${text.take(48).replace("\"", "'")}\""

    private val SECURITY_MODES = mapOf(
        0L to "unknown", 1L to "open", 2L to "wep_64", 3L to "wep_128", 4L to "wpa_personal",
        8L to "wpa2_personal", 12L to "wpa_wpa2_personal", 20L to "wpa_enterprise",
        24L to "wpa2_enterprise", 28L to "wpa_wpa2_enterprise",
    )
    private val AP_TYPES = mapOf(0L to "static", 1L to "dynamic")

    /** What the report keeps of a WifiInfoResponse. */
    class WifiDetails(
        val ssidHash: String?,
        val ssidLength: Int?,
        val ssidWifiDirect: Boolean?,
        val keyLength: Int?,
        val bssidHash: String?,
        /** The locally administered bit: true suggests a randomised address. */
        val bssidLocal: Boolean?,
        val securityMode: String?,
        val apType: String?,
        val otherFields: String,
    )

    fun wifiDetails(payload: ByteArray, redactor: Redactor): WifiDetails? {
        val fields = fields(payload) ?: return null
        var ssid: String? = null
        var key: ByteArray? = null
        var bssid: String? = null
        var security: Long? = null
        var ap: Long? = null
        val other = mutableListOf<String>()
        for (field in fields) {
            when {
                field.number == 1 && field.bytes != null -> ssid = String(field.bytes, Charsets.UTF_8)
                field.number == 2 && field.bytes != null -> key = field.bytes
                field.number == 3 && field.bytes != null -> bssid = String(field.bytes, Charsets.UTF_8)
                field.number == 4 && field.varint != null -> security = field.varint
                field.number == 5 && field.varint != null -> ap = field.varint
                // Unexpected fields: numbers and shapes only, never content.
                else -> other += "${field.number}:" + (field.varint?.let { "varint" } ?: "len${field.bytes?.size}")
            }
        }
        val mac = bssid?.trim()?.lowercase(Locale.ROOT)
        val firstOctet = mac?.split(':', '-')?.firstOrNull()?.toIntOrNull(16)
        return WifiDetails(
            ssidHash = ssid?.let { redactor.hash(it) },
            ssidLength = ssid?.length,
            ssidWifiDirect = ssid?.startsWith("DIRECT-"),
            keyLength = key?.size,
            bssidHash = mac?.let { redactor.hash(it) },
            bssidLocal = firstOctet?.let { it and 0x02 != 0 },
            securityMode = security?.let { SECURITY_MODES[it] ?: "mode_$it" },
            apType = ap?.let { AP_TYPES[it] ?: "type_$it" },
            otherFields = other.joinToString(" ").ifEmpty { "none" },
        )
    }

    /** WifiStartRequest and WifiVersionRequest's endpoint: field 1 an address, field 2 a port. */
    fun endpoint(payload: ByteArray): Pair<String, Long>? {
        val fields = fields(payload) ?: return null
        val ip = fields.firstOrNull { it.number == 1 && it.bytes != null }?.let { printable(it.bytes!!) } ?: return null
        val port = fields.firstOrNull { it.number == 2 && it.varint != null }?.varint ?: return null
        return if (IPV4.matches(ip) && port in 1..65535) ip to port else null
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** A WifiVersionRequest's endpoint, if one of its nested fields carries one (one revision puts it at field 5). */
    fun versionEndpoint(payload: ByteArray): Pair<String, Long>? =
        fields(payload)?.firstNotNullOfOrNull { field -> field.bytes?.takeIf { field.wire == 2 }?.let { endpoint(it) } }

    enum class Outcome(val key: String, val note: String) {
        WIFI_DETAILS("wifi_details", "the head unit sent its Wi-Fi details; W2 (join and TCP) is worth building"),
        START_ONLY("start_only", "the head unit sent where to connect but not its Wi-Fi details"),
        TALKED("talked", "the head unit sent something, but neither where to connect nor its Wi-Fi details"),
        SILENT("silent", "RFCOMM connected and the head unit sent nothing"),
        CONNECT_FAILED("connect_failed", "the RFCOMM connection to the bootstrap service failed"),
    }

    /**
     * One bootstrap conversation, without the socket. [received] and [tick] return frames
     * to send. Times are milliseconds since the RFCOMM connection completed.
     */
    class Session(private val redactor: Redactor) {
        val events = mutableListOf<String>()
        var huVersion: String? = null
            private set
        var start: Pair<String, Long>? = null
            private set
        var versionEndpointSeen: Pair<String, Long>? = null
            private set
        var wifi: WifiDetails? = null
            private set
        var done = false
            private set
        var lastAt = 0L
            private set
        private var received = 0
        private var nudged = false
        private var infoAttempts = 0
        private var lastInfoAt = -1L
        private var versionAnsweredAt = -1L
        private var lingerUntil = -1L
        private val counts = sortedMapOf<String, Int>()

        fun received(frame: Frame, at: Long): List<Frame> {
            if (done) return emptyList()
            lastAt = at
            received++
            counts.merge("in_${name(frame.id)}", 1, Int::plus)
            val detail = if (frame.id == INFO_RESPONSE) "redacted(see wifi_*)" else dump(frame.payload, redactor)
            events += "+${at}ms in ${frame.id} ${name(frame.id)} len=${frame.payload.size} fields=$detail"
            val out = mutableListOf<Frame>()
            when (frame.id) {
                VERSION_REQUEST -> {
                    val fields = fields(frame.payload)
                    val major = fields?.firstOrNull { it.number == 1 }?.varint
                    val minor = fields?.firstOrNull { it.number == 2 }?.varint
                    huVersion = if (major != null) "$major.${minor ?: "-"}" else "-"
                    versionEndpoint(frame.payload)?.let { versionEndpointSeen = it }
                    out += Frame(VERSION_RESPONSE, versionResponse(major, minor))
                    versionAnsweredAt = at
                }
                PING_REQUEST -> out += Frame(PING_RESPONSE, frame.payload)
                START_REQUEST -> {
                    endpoint(frame.payload)?.let { start = it }
                    // A repeated WifiStartRequest is answered too, but never past the cap.
                    if (wifi == null && infoAttempts < INFO_ATTEMPTS) out += askInfo(at)
                }
                INFO_RESPONSE -> {
                    val details = wifiDetails(frame.payload, redactor)
                    if (details == null) events += "+${at}ms WifiInfoResponse is not well-formed protobuf"
                    wifi = details ?: wifi
                    if (lingerUntil < 0) lingerUntil = at + LINGER_MS
                }
            }
            return sent(out, at)
        }

        fun tick(at: Long): List<Frame> {
            if (done) return emptyList()
            lastAt = at
            val out = mutableListOf<Frame>()
            when {
                at >= DEADLINE_MS -> finish(at, "deadline")
                lingerUntil in 0..at -> finish(at, "linger ended")
                wifi != null -> {}
                // Asked already: ask again, up to the limit.
                infoAttempts in 1 until INFO_ATTEMPTS && at - lastInfoAt >= INFO_RETRY_MS -> out += askInfo(at)
                infoAttempts > 0 -> {}
                // Version answered, no WifiStartRequest: ask for Wi-Fi details anyway.
                versionAnsweredAt >= 0 && start == null && at - versionAnsweredAt >= START_WAIT_MS -> out += askInfo(at)
                received == 0 && !nudged && at >= NUDGE_MS -> {
                    nudged = true
                    events += "+${at}ms nudge: nothing received, asking for Wi-Fi details unprompted"
                    out += askInfo(at)
                }
            }
            return sent(out, at)
        }

        /** The socket closed or failed under us. */
        fun closed(at: Long, why: String) {
            if (!done) {
                lastAt = at
                events += "+${at}ms closed: $why"
                done = true
            }
        }

        private fun finish(at: Long, why: String) {
            events += "+${at}ms done: $why"
            done = true
        }

        private fun askInfo(at: Long): Frame {
            infoAttempts++
            lastInfoAt = at
            return Frame(INFO_REQUEST, ByteArray(0))
        }

        private fun sent(frames: List<Frame>, at: Long): List<Frame> {
            for (frame in frames) {
                counts.merge("out_${name(frame.id)}", 1, Int::plus)
                // Our own bytes: nothing of the head unit's except an echoed ping or version.
                val hex = frame.payload.joinToString("") { "%02x".format(Locale.ROOT, it) }.ifEmpty { "-" }
                events += "+${at}ms out ${frame.id} ${name(frame.id)} len=${frame.payload.size} hex=$hex"
            }
            return frames
        }

        fun outcome(): Outcome =
            when {
                wifi != null -> Outcome.WIFI_DETAILS
                start != null || versionEndpointSeen != null -> Outcome.START_ONLY
                received > 0 -> Outcome.TALKED
                else -> Outcome.SILENT
            }

        fun countsText(): String = counts.entries.joinToString(" ") { "${it.key}=${it.value}" }.ifEmpty { "none" }
    }

    /** The saved report: key=value lines under one section, ending with attempt_complete=true. */
    fun report(
        probeUtc: Instant,
        appVersion: String,
        sdkInt: Int,
        deviceClass: Int?,
        connectMs: Long,
        connectError: String?,
        session: Session,
    ): String = buildString {
        val outcome = if (connectError != null) Outcome.CONNECT_FAILED else session.outcome()
        appendLine("[wireless_bootstrap]")
        appendLine("probe=w1_rfcomm")
        appendLine("probe_utc=$probeUtc")
        appendLine("app_version=$appVersion")
        appendLine("android_sdk=$sdkInt")
        appendLine("bootstrap_uuid=${WirelessProbe.BOOTSTRAP_UUID}")
        appendLine("device_class=${deviceClass?.let { "0x" + Integer.toHexString(it).padStart(4, '0') } ?: "unknown"}")
        appendLine("rfcomm_connect_ms=$connectMs")
        appendLine("rfcomm_connect_error=${connectError ?: "none"}")
        appendLine("conversation_ms=${session.lastAt}")
        appendLine("frames=${session.countsText()}")
        appendLine("hu_version=${session.huVersion ?: "none"}")
        appendLine("start_endpoint=${session.start?.let { "${it.first}:${it.second}" } ?: "none"}")
        appendLine("version_endpoint=${session.versionEndpointSeen?.let { "${it.first}:${it.second}" } ?: "none"}")
        val wifi = session.wifi
        appendLine("wifi_ssid=${wifi?.ssidHash ?: "none"}")
        appendLine("wifi_ssid_length=${wifi?.ssidLength ?: "none"}")
        appendLine("wifi_ssid_wifi_direct=${wifi?.ssidWifiDirect ?: "none"}")
        appendLine("wifi_bssid=${wifi?.bssidHash ?: "none"}")
        appendLine("wifi_bssid_locally_administered=${wifi?.bssidLocal ?: "none"}")
        appendLine("wifi_key_length=${wifi?.keyLength ?: "none"}")
        appendLine("wifi_security=${wifi?.securityMode ?: "none"}")
        appendLine("wifi_ap_type=${wifi?.apType ?: "none"}")
        appendLine("wifi_other_fields=${wifi?.otherFields ?: "none"}")
        session.events.forEachIndexed { index, event -> appendLine("event_${index + 1}=$event") }
        appendLine("outcome=${outcome.key}")
        appendLine("outcome_note=${outcome.note}")
        appendLine("attempt_complete=true")
    }
}
