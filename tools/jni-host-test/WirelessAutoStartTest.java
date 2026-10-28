// W3's start rule on a desktop JVM (docs/connecting.md, "The companion-device link and auto-start"):
// every reason the linked
// truck connecting over Bluetooth does or does not start wireless projection.
// WirelessAutoStart is pure Kotlin; Android's companion-device wake-up, the Nearby devices
// grant and the link itself need a phone and the truck.
import com.jw6ventures.headlink.AutoStart;
import com.jw6ventures.headlink.Stage;
import com.jw6ventures.headlink.WirelessAutoStart;

public final class WirelessAutoStartTest {
    private static int passed;

    public static void main(String[] args) {
        rule();
        order();
        report();
        System.out.println("jni-host-test: ok " + passed + " wireless auto-start checks");
    }

    private static WirelessAutoStart decide(
            Stage stage, boolean linking, boolean wired, boolean bluetooth, int pins, boolean projection,
            AutoStart.Identity identity) {
        return WirelessAutoStart.Companion.decide(stage, linking, wired, bluetooth, pins, projection, identity);
    }

    private static void rule() {
        AutoStart.Identity ready = AutoStart.Identity.READY;
        is(decide(Stage.IDLE, false, false, true, 1, true, ready), WirelessAutoStart.START, "everything in place");
        // A fresh process is usually still loading the identity; the link's session waits for it.
        is(decide(Stage.IDLE, false, false, true, 1, true, AutoStart.Identity.LOADING), WirelessAutoStart.START,
                "identity still loading starts");
        is(decide(Stage.IDLE, false, false, true, 1, true, AutoStart.Identity.FAILED), WirelessAutoStart.IDENTITY_FAILED,
                "identity failed");
        for (Stage stage : Stage.values()) {
            if (stage == Stage.IDLE) continue;
            is(decide(stage, false, false, true, 1, true, ready), WirelessAutoStart.BUSY, "busy while " + stage);
        }
        is(decide(Stage.IDLE, true, false, true, 1, true, ready), WirelessAutoStart.BUSY, "busy while a link sets up");
        is(decide(Stage.IDLE, false, true, true, 1, true, ready), WirelessAutoStart.WIRED, "cable attached");
        is(decide(Stage.IDLE, false, false, false, 1, true, ready), WirelessAutoStart.NO_BLUETOOTH, "no Nearby devices");
        is(decide(Stage.IDLE, false, false, true, 0, true, ready), WirelessAutoStart.NO_PINS, "nothing pinned");
        is(decide(Stage.IDLE, false, false, true, 3, true, ready), WirelessAutoStart.START, "several pins");
        is(decide(Stage.IDLE, false, false, true, 1, false, ready), WirelessAutoStart.NOT_PROJECTION, "diagnostic stop-after");
    }

    /** Every combination: START only when each input allows it, and the first failing reason wins. */
    private static void order() {
        boolean[] both = {false, true};
        int starts = 0;
        for (Stage stage : Stage.values()) {
            for (boolean linking : both) {
                for (boolean wired : both) {
                    for (boolean bluetooth : both) {
                        for (int pins : new int[] {0, 1}) {
                            for (boolean projection : both) {
                                for (AutoStart.Identity identity : AutoStart.Identity.values()) {
                                    WirelessAutoStart got = decide(stage, linking, wired, bluetooth, pins, projection, identity);
                                    WirelessAutoStart want;
                                    if (stage != Stage.IDLE || linking) want = WirelessAutoStart.BUSY;
                                    else if (wired) want = WirelessAutoStart.WIRED;
                                    else if (!bluetooth) want = WirelessAutoStart.NO_BLUETOOTH;
                                    else if (pins == 0) want = WirelessAutoStart.NO_PINS;
                                    else if (!projection) want = WirelessAutoStart.NOT_PROJECTION;
                                    else if (identity == AutoStart.Identity.FAILED) want = WirelessAutoStart.IDENTITY_FAILED;
                                    else want = WirelessAutoStart.START;
                                    if (got != want) throw new AssertionError(
                                            "order: " + stage + " " + linking + " " + wired + " " + bluetooth + " " + pins
                                                    + " " + projection + " " + identity + ": got " + got + ", want " + want);
                                    if (got == WirelessAutoStart.START) starts++;
                                }
                            }
                        }
                    }
                }
            }
        }
        passed++;
        // Only IDLE, no link, no cable, Bluetooth, pins, projection: READY and LOADING.
        is(starts, 2, "exactly two combinations start");
        for (WirelessAutoStart reason : WirelessAutoStart.values()) {
            check(!reason.getReason().isEmpty(), "reason text for " + reason);
        }
    }

    private static void report() {
        String text = WirelessAutoStart.Companion.report(
                java.time.Instant.parse("2026-10-16T12:00:00Z"), "0.4.5-lab", 36, "Bluetooth connected",
                WirelessAutoStart.NO_PINS, Stage.IDLE, false, false, true, 0, true, AutoStart.Identity.LOADING, false);
        check(text.startsWith("[wireless_auto]\n"), "report section");
        check(text.contains("\ndecision=no_pins\n"), "report decision");
        check(text.contains("\nreason=" + WirelessAutoStart.NO_PINS.getReason() + "\n"), "report reason");
        check(text.contains("\ncallback=Bluetooth connected\n"), "report callback");
        check(text.contains("\nidentity=loading\n"), "report identity");
        check(text.contains("\nauto_start=off\n"), "report says the truck's own starts are held");
        check(text.endsWith("attempt_complete=true\n"), "report ends complete");
        // No device address in any form (spec §10).
        check(!java.util.regex.Pattern.compile("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matcher(text).find(), "no MAC address");
    }

    private static void is(Object got, Object want, String what) {
        if (!got.equals(want)) throw new AssertionError(what + ": got " + got + ", want " + want);
        passed++;
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
