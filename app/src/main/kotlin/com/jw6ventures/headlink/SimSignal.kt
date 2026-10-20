package com.jw6ventures.headlink

/**
 * One active subscription's signal, as [PhoneStatus] last heard it (P7.3, plan §5.2).
 *
 * Carries an opaque id and a slot, never a carrier name or a number: this is what the
 * header draws and what the report may count (spec §10), and nothing else about a SIM
 * is read.
 */
data class SubscriptionSignal(
    /** Android's subscription id. Only used to tell subscriptions apart. */
    val id: Int,
    /** SIM slot, or a large number for an eSIM that reports none; only used to order rows. */
    val slot: Int,
    /** 0..4 from `SignalStrength.getLevel`, or -1 before the first reading. */
    val level: Int,
    /** False while the radio reports out of service, emergency only or powered off. */
    val inService: Boolean,
)

/** What the header draws for signal: the primary's bars and one dot row per other SIM. */
data class SignalRows(
    /** 0..4, 0 when the primary is out of service, -1 when there is no SIM or no reading. */
    val primary: Int,
    /** Up to [SimSignal.MAX_SECONDARY] levels, each 0..4 or -1 for a hollow row. */
    val secondary: List<Int>,
    /** Every active subscription, the primary included; for the report. */
    val count: Int,
)

/**
 * The pure part of multi-SIM signal: which subscription leads, how the rest are ordered,
 * and how Android's network type becomes the two or three letters beside the bars.
 * Host-tested (SimSignalTest); [PhoneStatus] supplies the readings.
 */
object SimSignal {
    /**
     * Rows of dots under the bars. The plan's number (§1 item 9): a phone with more than
     * four subscriptions is not a case worth a taller header.
     */
    const val MAX_SECONDARY = 3

    /**
     * Arranges [subscriptions] for the header.
     *
     * The primary is [defaultData], because that is the subscription a phone's own status
     * bar leads with. If it is not among the active ones (data turned off on a
     * single-SIM phone reports -1), the lowest slot leads instead, so a phone with a SIM
     * never shows `—`.
     *
     * An out-of-service primary draws **empty bars** (0), as the phone's status bar does,
     * where `—` (-1) is kept for "no SIM or nothing read yet". A secondary has no such
     * distinction to make: out of service or unread is a **hollow** row (-1), and a
     * missing row always means "no such SIM" (plan §5.2).
     */
    fun arrange(subscriptions: List<SubscriptionSignal>, defaultData: Int): SignalRows {
        if (subscriptions.isEmpty()) return SignalRows(-1, emptyList(), 0)
        val ordered = subscriptions.sortedWith(compareBy({ it.slot }, { it.id }))
        val primary = ordered.firstOrNull { it.id == defaultData } ?: ordered.first()
        val primaryLevel = when {
            primary.level < 0 -> -1
            !primary.inService -> 0
            else -> primary.level.coerceIn(0, 4)
        }
        val secondary = ordered
            .filter { it.id != primary.id }
            .take(MAX_SECONDARY)
            .map { if (it.inService && it.level >= 0) it.level.coerceIn(0, 4) else -1 }
        return SignalRows(primaryLevel, secondary, subscriptions.size)
    }

    // TelephonyManager.NETWORK_TYPE_* and TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_*,
    // mirrored so this object stays free of Android classes and host-testable. They are
    // wire-stable public constants; NETWORK_TYPE_LTE_CA (19) is hidden but still reported
    // by some modems, which is why it is listed.
    const val NETWORK_TYPE_GPRS = 1
    const val NETWORK_TYPE_EDGE = 2
    const val NETWORK_TYPE_UMTS = 3
    const val NETWORK_TYPE_CDMA = 4
    const val NETWORK_TYPE_EVDO_0 = 5
    const val NETWORK_TYPE_EVDO_A = 6
    const val NETWORK_TYPE_1XRTT = 7
    const val NETWORK_TYPE_HSDPA = 8
    const val NETWORK_TYPE_HSUPA = 9
    const val NETWORK_TYPE_HSPA = 10
    const val NETWORK_TYPE_IDEN = 11
    const val NETWORK_TYPE_EVDO_B = 12
    const val NETWORK_TYPE_LTE = 13
    const val NETWORK_TYPE_EHRPD = 14
    const val NETWORK_TYPE_HSPAP = 15
    const val NETWORK_TYPE_GSM = 16
    const val NETWORK_TYPE_TD_SCDMA = 17
    const val NETWORK_TYPE_LTE_CA = 19
    const val NETWORK_TYPE_NR = 20
    const val OVERRIDE_NR_NSA = 3
    const val OVERRIDE_NR_NSA_MMWAVE = 4
    const val OVERRIDE_NR_ADVANCED = 5

    /**
     * The label beside the primary's bars, from `TelephonyDisplayInfo`.
     *
     * The override is read first because that is how Android reports non-standalone 5G:
     * the radio says LTE and the override says NR_NSA, and the phone's own status bar
     * shows 5G. Reading the network type alone would show `LTE` on a 5G phone.
     *
     * `-` for anything unknown, including IWLAN (Wi-Fi calling), where the header shows
     * `wifi` from the connectivity transport instead.
     */
    fun networkLabel(networkType: Int, overrideType: Int): String = when {
        overrideType == OVERRIDE_NR_NSA || overrideType == OVERRIDE_NR_NSA_MMWAVE ||
            overrideType == OVERRIDE_NR_ADVANCED || networkType == NETWORK_TYPE_NR -> "5G"
        networkType == NETWORK_TYPE_LTE || networkType == NETWORK_TYPE_LTE_CA -> "LTE"
        networkType == NETWORK_TYPE_HSPAP -> "H+"
        networkType == NETWORK_TYPE_HSDPA || networkType == NETWORK_TYPE_HSUPA ||
            networkType == NETWORK_TYPE_HSPA -> "H"
        networkType == NETWORK_TYPE_UMTS || networkType == NETWORK_TYPE_TD_SCDMA ||
            networkType == NETWORK_TYPE_EVDO_0 || networkType == NETWORK_TYPE_EVDO_A ||
            networkType == NETWORK_TYPE_EVDO_B || networkType == NETWORK_TYPE_EHRPD ||
            networkType == NETWORK_TYPE_CDMA -> "3G"
        networkType == NETWORK_TYPE_EDGE -> "E"
        networkType == NETWORK_TYPE_GPRS || networkType == NETWORK_TYPE_GSM ||
            networkType == NETWORK_TYPE_1XRTT || networkType == NETWORK_TYPE_IDEN -> "2G"
        else -> "-"
    }
}
