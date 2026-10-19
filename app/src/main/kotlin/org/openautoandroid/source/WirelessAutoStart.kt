package org.openautoandroid.source

/**
 * W3 (docs/wireless-w3-procedure.md): whether the linked truck connecting over Bluetooth
 * starts wireless projection. The wireless counterpart of [AutoStart], and pure for the
 * same reason: the host test covers every branch, and [WirelessAuto] only gathers inputs.
 *
 * Being linked is the switch: Android tells the app about the truck only while the
 * operator has the truck linked, so there is no separate "off" here. START starts a link
 * that stands by for the truck ([WirelessStandby]); Start wirelessly when the truck
 * connects only decides whether the truck's automatic starts are taken as well.
 */
enum class WirelessAutoStart(val reason: String) {
    START("the linked head unit connected over Bluetooth"),
    /** A session, wired or wireless, or a link still setting up, keeps it. */
    BUSY("a session or a wireless link is already running"),
    /** The cable is in: the plug-in start, or Connect, has it. */
    WIRED("a USB accessory is attached; the cable takes it"),
    NO_BLUETOOTH("Nearby devices is not granted"),
    /** First use stays wired and manual, as for a plug-in start. */
    NO_PINS("no head unit is pinned yet; first use stays manual"),
    NOT_PROJECTION("the saved stop-after is not projection; use Connect for a diagnostic run"),
    IDENTITY_FAILED("the source identity failed to load or self-test"),
    ;

    companion object {
        /**
         * The rule, in the order the reasons are checked. An identity still loading is a
         * start: the link takes seconds to reach its session, which waits for it.
         */
        fun decide(
            stage: Stage,
            linking: Boolean,
            wired: Boolean,
            bluetooth: Boolean,
            pins: Int,
            projection: Boolean,
            identity: AutoStart.Identity,
        ): WirelessAutoStart =
            when {
                stage != Stage.IDLE || linking -> BUSY
                wired -> WIRED
                !bluetooth -> NO_BLUETOOTH
                pins <= 0 -> NO_PINS
                !projection -> NOT_PROJECTION
                identity == AutoStart.Identity.FAILED -> IDENTITY_FAILED
                else -> START
            }

        /**
         * One wake's saved report (`[wireless_auto]`): which callback, what was decided and
         * from what, and whether the truck's automatic starts are taken ([autoStart]). A START also leaves the link's own `[wireless_connect]` report. Never a
         * device name or address (spec §10).
         */
        fun report(
            utc: java.time.Instant,
            appVersion: String,
            sdkInt: Int,
            how: String,
            decision: WirelessAutoStart,
            stage: Stage,
            linking: Boolean,
            wired: Boolean,
            bluetooth: Boolean,
            pins: Int,
            projection: Boolean,
            identity: AutoStart.Identity,
            autoStart: Boolean,
        ): String = buildString {
            appendLine("[wireless_auto]")
            appendLine("probe=w3_wake")
            appendLine("wake_utc=$utc")
            appendLine("app_version=$appVersion")
            appendLine("android_sdk=$sdkInt")
            appendLine("callback=$how")
            appendLine("decision=${decision.name.lowercase()}")
            appendLine("reason=${decision.reason}")
            appendLine("stage=${stage.name}")
            appendLine("link_running=$linking")
            appendLine("usb_attached=$wired")
            appendLine("nearby_devices=$bluetooth")
            appendLine("pins=$pins")
            appendLine("stop_after_projection=$projection")
            appendLine("identity=${identity.name.lowercase()}")
            // Standby: off, a START waits for the truck's button alone.
            appendLine("auto_start=${if (autoStart) "on" else "off"}")
            appendLine("attempt_complete=true")
        }
    }
}
