package com.jw6ventures.headlink

import java.time.Instant
import java.util.Locale

/**
 * W0 (docs/connecting.md, "Diagnostics: the wireless probe"): does a paired head unit advertise the wireless
 * Android Auto bootstrap service over Bluetooth SDP? Read-only. Nothing here connects or
 * sends anything to the head unit.
 *
 * The verdict and the report are pure JVM, so tools/jni-host-test exercises them. The
 * screen that gathers the inputs, [WirelessProbeActivity], is the only code that uses
 * BLUETOOTH_CONNECT (spec §5.2 as amended 14 October 2026).
 *
 * The report never takes a device name or address (spec §10). It records the Bluetooth
 * class, the profile states, and the service UUIDs, which name services rather than the
 * vehicle.
 */
object WirelessProbe {
    /** The SDP service a wireless head unit serves its RFCOMM bootstrap on; the phone connects to it. */
    const val BOOTSTRAP_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"

    /** Longest wait for an SDP answer. Paging a head unit that is not connected takes several seconds. */
    const val SDP_TIMEOUT_MS = 15_000L

    /** Wait after the first answer, for a second. Android can answer once from its cache and again from the query. */
    const val SDP_GRACE_MS = 2_000L

    enum class Verdict(val key: String, val note: String) {
        ADVERTISED("advertised", "an SDP answer lists the bootstrap service; W1 (RFCOMM bootstrap probe) is worth building"),
        CACHED_ONLY("cached_only", "only the list kept from pairing has it; no fresh answer arrived, so repeat with the head unit on"),
        ABSENT("absent", "an SDP answer came back without the bootstrap service; this head unit offers no wireless bootstrap to this phone as configured"),
        NO_ANSWER("no_answer", "no SDP answer and nothing cached; inconclusive, repeat with the head unit on and in range"),
    }

    /** What the screen read about the device before the query. */
    class Device(
        /** BluetoothClass.getDeviceClass(), or null when it could not be read. */
        val deviceClass: Int?,
        /** BluetoothDevice.getType(): 0 unknown, 1 classic, 2 LE, 3 dual. */
        val type: Int,
        /** BluetoothProfile connection state for HFP, or null when the proxy was not ready. */
        val hfpState: Int?,
        /** The same for A2DP. */
        val a2dpState: Int?,
        /** BluetoothDevice.getUuids(): the list kept from the last SDP, null when there is none. */
        val cached: List<String>?,
    )

    /** What the SDP query returned. Each answer is null when Android delivered no UUIDs. */
    class Sdp(val started: Boolean, val answers: List<List<String>?>, val waitedMs: Long)

    fun normalise(uuid: String): String = uuid.trim().lowercase(Locale.ROOT)

    fun verdict(cached: List<String>?, answers: List<List<String>?>): Verdict {
        val fresh = answers.filterNotNull()
        return when {
            fresh.any { list -> list.any { normalise(it) == BOOTSTRAP_UUID } } -> Verdict.ADVERTISED
            fresh.isNotEmpty() -> Verdict.ABSENT
            cached.orEmpty().any { normalise(it) == BOOTSTRAP_UUID } -> Verdict.CACHED_ONLY
            else -> Verdict.NO_ANSWER
        }
    }

    /**
     * One UUID as `short:label` for a Bluetooth SIG base UUID (`111f:hfp_gateway`), or the
     * full UUID with its label (or `unknown`) otherwise.
     */
    fun describe(uuid: String): String {
        val value = normalise(uuid)
        if (value == BOOTSTRAP_UUID) return "$value:aa_wireless_bootstrap"
        val short = if (value.length == 36 && value.startsWith("0000") && value.endsWith(SIG_BASE_SUFFIX)) {
            value.substring(4, 8)
        } else {
            null
        }
        return if (short != null) "$short:${SIG_NAMES[short] ?: "unknown"}" else "$value:unknown"
    }

    fun reportName(probeUtc: Instant, id: String): String = ReportStore.fileName(probeUtc, id)

    /** The saved report: key=value lines under one section, ending with attempt_complete=true. */
    fun report(probeUtc: Instant, appVersion: String, sdkInt: Int, device: Device, sdp: Sdp): String {
        val verdict = verdict(device.cached, sdp.answers)
        val cachedSet = device.cached?.map(::normalise)?.toSet()
        return buildString {
            appendLine("[wireless_probe]")
            appendLine("probe=w0_sdp")
            appendLine("probe_utc=$probeUtc")
            appendLine("app_version=$appVersion")
            appendLine("android_sdk=$sdkInt")
            appendLine("bootstrap_uuid=$BOOTSTRAP_UUID")
            appendLine("device_class=${classText(device.deviceClass)}")
            appendLine("device_type=${TYPE_NAMES[device.type] ?: "type_${device.type}"}")
            appendLine("hfp=${stateText(device.hfpState)}")
            appendLine("a2dp=${stateText(device.a2dpState)}")
            appendLine("cached_uuids=${listText(device.cached)}")
            appendLine("sdp_started=${sdp.started}")
            appendLine("sdp_answers=${sdp.answers.size}")
            sdp.answers.forEachIndexed { index, answer ->
                val n = index + 1
                appendLine("sdp_answer_$n=${listText(answer)}")
                appendLine("sdp_answer_${n}_matches_cache=${answer != null && answer.map(::normalise).toSet() == cachedSet}")
            }
            appendLine("sdp_wait_ms=${sdp.waitedMs}")
            appendLine("aa_wireless_in_cache=${device.cached.orEmpty().any { normalise(it) == BOOTSTRAP_UUID }}")
            appendLine("aa_wireless_in_sdp=${sdp.answers.filterNotNull().any { list -> list.any { normalise(it) == BOOTSTRAP_UUID } }}")
            appendLine("verdict=${verdict.key}")
            appendLine("verdict_note=${verdict.note}")
            appendLine("attempt_complete=true")
        }
    }

    private fun listText(uuids: List<String>?): String =
        when {
            uuids == null -> "none"
            uuids.isEmpty() -> "empty"
            else -> uuids.map(::describe).distinct().joinToString(" ")
        }

    private fun stateText(state: Int?): String =
        when (state) {
            null -> "unknown"
            0 -> "disconnected"
            1 -> "connecting"
            2 -> "connected"
            3 -> "disconnecting"
            else -> "state_$state"
        }

    private fun classText(deviceClass: Int?): String {
        if (deviceClass == null) return "unknown"
        val hex = "0x" + Integer.toHexString(deviceClass).padStart(4, '0')
        return "$hex (${CLASS_NAMES[deviceClass] ?: "other"})"
    }

    private const val SIG_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /** Bluetooth SIG assigned numbers a head unit or phone is likely to list. */
    private val SIG_NAMES = mapOf(
        "1101" to "serial_port",
        "1105" to "obex_push",
        "1108" to "headset",
        "110a" to "a2dp_source",
        "110b" to "a2dp_sink",
        "110c" to "avrcp_target",
        "110e" to "avrcp",
        "110f" to "avrcp_controller",
        "1112" to "headset_gateway",
        "111e" to "hfp",
        "111f" to "hfp_gateway",
        "112e" to "pbap_client",
        "112f" to "pbap_server",
        "1132" to "map_server",
        "1133" to "map_notification",
        "1134" to "map",
        "1200" to "pnp_information",
        "1800" to "generic_access",
        "1801" to "generic_attribute",
    )

    /** BluetoothClass.Device values worth naming in a report. */
    private val CLASS_NAMES = mapOf(
        0x0420 to "car_audio",
        0x0408 to "handsfree",
        0x0404 to "headset",
        0x020c to "smartphone",
    )

    private val TYPE_NAMES = mapOf(0 to "unknown", 1 to "classic", 2 to "le", 3 to "dual")
}
