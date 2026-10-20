// Wireless standby's rules on a desktop JVM (spec 5.2, the standby amendment;
// docs/wireless-w3-procedure.md, "Standby"): the start reasons decoded from Android Auto
// 17.6, which start requests start projection, the automatic-start loop guard and the
// reconnect pacing. Holding the RFCOMM link open on the truck, the button press itself and
// the wake lock need a phone and the truck.
import com.jw6ventures.headlink.WirelessStandby;

public final class WirelessStandbyTest {
    private static int passed;
    private static final WirelessStandby WS = WirelessStandby.INSTANCE;

    public static void main(String[] args) {
        reasons();
        accepts();
        loopGuard();
        pacing();
        System.out.println("jni-host-test: ok " + passed + " wireless standby checks");
    }

    private static void reasons() {
        is(WS.reasonName(0L), "user_request", "0 is the button");
        is(WS.reasonName(1L), "auto_launch", "1 is the head unit launching");
        is(WS.reasonName(2L), "automatic_restart", "2 is the restart after a drop");
        is(WS.reasonName(7L), "reason_7", "unknown reasons by number");
        is(WS.reasonName(null), "none", "absent");
        check(WS.isUser(0L) && WS.isUser(null), "the button, and a request without a reason, are the operator's");
        check(!WS.isUser(1L) && !WS.isUser(2L) && !WS.isUser(7L), "the rest are the head unit's own");
    }

    private static void accepts() {
        for (boolean auto : new boolean[] {false, true}) {
            check(WS.accepts(0L, auto), "the button always starts (auto " + auto + ")");
            check(WS.accepts(null, auto), "no reason starts too (auto " + auto + ")");
            check(WS.accepts(1L, auto) == auto, "auto-launch only when automatic starts are taken (auto " + auto + ")");
            check(WS.accepts(2L, auto) == auto, "a restart likewise (auto " + auto + ")");
            check(WS.accepts(9L, auto) == auto, "an unknown reason likewise (auto " + auto + ")");
        }
        check(WS.acceptAuto(true, false, 0), "setting on, nothing stopped: taken");
        check(!WS.acceptAuto(false, false, 0), "setting off: held");
        check(!WS.acceptAuto(true, true, 0), "stopped from the phone: held");
        check(WS.acceptAuto(true, false, WirelessStandby.QUICK_AUTO_LIMIT - 1), "below the quick-start limit: taken");
        check(!WS.acceptAuto(true, false, WirelessStandby.QUICK_AUTO_LIMIT), "at the limit: held");
    }

    private static void loopGuard() {
        int count = 0;
        for (int i = 0; i < WirelessStandby.QUICK_AUTO_LIMIT; i++) {
            count = WS.quickAfter(count, 30_000L);
        }
        is(count, WirelessStandby.QUICK_AUTO_LIMIT, "three quick automatic starts in a row");
        check(!WS.acceptAuto(true, false, count), "then automatic starts are held");
        is(WS.quickAfter(2, WirelessStandby.QUICK_PROJECTION_MS), 0, "a projection that lasts resets the count");
        is(WS.quickAfter(0, WirelessStandby.QUICK_PROJECTION_MS - 1), 1, "just short of it counts");
    }

    private static void pacing() {
        is(WS.gapMs(0), WirelessStandby.GAP_MS, "the usual gap");
        is(WS.gapMs(WirelessStandby.SLOW_AFTER - 1), WirelessStandby.GAP_MS, "still the usual gap");
        is(WS.gapMs(WirelessStandby.SLOW_AFTER), WirelessStandby.SLOW_GAP_MS, "slower after short connections in a row");
        check(WirelessStandby.GAP_MS == 15_000L && WirelessStandby.SLOW_GAP_MS == 60_000L, "15 s, then 60 s, as the spec says");
        check(WirelessStandby.CONNECT_FAILURE_LIMIT * WirelessStandby.SLOW_GAP_MS >= 5 * 60_000L,
                "failed connects give up only after minutes");
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
