package org.openautoandroid.source

/**
 * Wireless standby (spec §5.2, the standby amendment; docs/wireless-w3-procedure.md,
 * "Standby"): the rules for holding the Bluetooth bootstrap link open to the linked truck
 * and waiting for it to ask for projection, as Android Auto does.
 *
 * The phone is always the RFCOMM client: Android Auto 17.6 never listens for the head unit
 * (dexdump of all four dex files, 18 October 2026). So the Android Auto button on the
 * truck's screen can only reach a phone that already holds the link, and it arrives as a
 * WifiStartRequest whose field 3 says why ([reasonName]). The link therefore stays up while
 * the truck is connected, between sessions too, and each start request is accepted or held
 * by [accepts].
 *
 * Pure, so WirelessStandbyTest covers every rule; [WirelessLink] and [WirelessAuto] only
 * gather the inputs.
 */
object WirelessStandby {
    /**
     * WifiStartRequest field 3, as Android Auto 17.6 names it (`pgy.a`, enum map `a.ao`):
     * 0 the operator asked on the head unit, 1 the head unit launching by itself, 2 the
     * head unit restarting after a drop. SYNC 4.0 has sent 1 on every start but one, which
     * sent 2 after it reset the previous session (docs/wireless-w1-results.md).
     */
    const val REASON_USER_REQUEST = 0L
    const val REASON_AUTO_LAUNCH = 1L
    const val REASON_AUTOMATIC_RESTART = 2L

    /**
     * Automatic starts that each end within [QUICK_PROJECTION_MS] are allowed this many times
     * in a row per truck connection; after that only the button starts, so a start that keeps
     * failing cannot loop all drive.
     */
    const val QUICK_AUTO_LIMIT = 3
    const val QUICK_PROJECTION_MS = 120_000L

    /** Between RFCOMM connections while standing by; [SLOW_GAP_MS] after [SLOW_AFTER] short ones in a row. */
    const val GAP_MS = 15_000L
    const val SLOW_GAP_MS = 60_000L
    const val SLOW_AFTER = 3

    /** A connection that lasts less than this counts as short for [gapMs]. */
    const val SHORT_CONNECTION_MS = 30_000L

    /** Standing by ends after this many failed connects in a row (each one four tries). */
    const val CONNECT_FAILURE_LIMIT = 10

    /** After a projection ends, the next RFCOMM connection waits this long for the head unit to settle. */
    const val AFTER_PROJECTION_MS = 5_000L

    fun reasonName(reason: Long?): String =
        when (reason) {
            null -> "none"
            REASON_USER_REQUEST -> "user_request"
            REASON_AUTO_LAUNCH -> "auto_launch"
            REASON_AUTOMATIC_RESTART -> "automatic_restart"
            else -> "reason_$reason"
        }

    /**
     * The operator pressed the button. A request without field 3 counts too: every one SYNC
     * has sent carried it, and a held button press would be the worse mistake.
     */
    fun isUser(reason: Long?): Boolean = reason == null || reason == REASON_USER_REQUEST

    /** Whether a start request with [reason] starts projection; [acceptAuto] from [acceptAuto]. */
    fun accepts(reason: Long?, acceptAuto: Boolean): Boolean = isUser(reason) || acceptAuto

    /**
     * Whether the head unit's own starts are taken: Start wirelessly when the truck connects
     * is on, the operator has not stopped or cancelled a wireless session from the phone
     * since the truck connected, and automatic starts are not failing in a row.
     */
    fun acceptAuto(setting: Boolean, operatorStopped: Boolean, quickAutoStarts: Int): Boolean =
        setting && !operatorStopped && quickAutoStarts < QUICK_AUTO_LIMIT

    /** The next value of the quick-start count after an automatic start's projection lasted [ms]. */
    fun quickAfter(count: Int, ms: Long): Int = if (ms < QUICK_PROJECTION_MS) count + 1 else 0

    /** How long to wait before the next RFCOMM connection, after [shortInARow] short ones. */
    fun gapMs(shortInARow: Int): Long = if (shortInARow >= SLOW_AFTER) SLOW_GAP_MS else GAP_MS
}
