// P7.9's call state mapping on a desktop JVM: Telecom's states and the CallStyle
// notification's call types as tile phases, which call the tile shows when there are
// several, the caller text, the keypad's keys, and when Mute and the keypad are offered.
// CallRules is pure Kotlin; whether Telecom binds the companion at all is for the Pixel
// (docs/dashboard.md, "Calls and the keypad").
import java.util.List;
import com.jw6ventures.headlink.CallPhase;
import com.jw6ventures.headlink.CallRoute;
import com.jw6ventures.headlink.CallRules;
import com.jw6ventures.headlink.CallSource;
import com.jw6ventures.headlink.CallState;

public final class CallStateTest {
    private static int passed;
    private static final CallRules RULES = CallRules.INSTANCE;

    public static void main(String[] args) {
        telecomStates();
        callStyleTypes();
        theTileShowsTheCallThatNeedsTheDriver();
        callerText();
        keypadKeys();
        controlsFollowTheRoute();
        theRouteIsChosenPerSession();
        System.out.println("jni-host-test: ok " + passed + " call state checks");
    }

    private static void telecomStates() {
        check(RULES.phaseOf(CallRules.STATE_RINGING) == CallPhase.RINGING, "ringing");
        check(RULES.phaseOf(CallRules.STATE_SIMULATED_RINGING) == CallPhase.RINGING, "a screening app letting it ring is ringing");
        check(RULES.phaseOf(CallRules.STATE_DIALING) == CallPhase.DIALING, "dialing");
        check(RULES.phaseOf(CallRules.STATE_CONNECTING) == CallPhase.DIALING, "connecting is still dialing");
        check(RULES.phaseOf(CallRules.STATE_NEW) == CallPhase.DIALING, "so is new");
        check(RULES.phaseOf(CallRules.STATE_SELECT_PHONE_ACCOUNT) == CallPhase.DIALING, "and choosing a SIM");
        check(RULES.phaseOf(CallRules.STATE_ACTIVE) == CallPhase.ACTIVE, "active");
        check(RULES.phaseOf(CallRules.STATE_HOLDING) == CallPhase.HOLDING, "holding");
        check(RULES.phaseOf(CallRules.STATE_DISCONNECTING) == CallPhase.ENDED, "disconnecting has ended for the tile");
        check(RULES.phaseOf(CallRules.STATE_DISCONNECTED) == CallPhase.ENDED, "disconnected");
        check(RULES.phaseOf(99) == CallPhase.NONE, "an unknown state shows nothing");
        // The mirrored values, against android.telecom.Call's documented constants.
        check(CallRules.STATE_ACTIVE == 4 && CallRules.STATE_RINGING == 2 && CallRules.STATE_DISCONNECTED == 7,
                "the mirrored constants are Telecom's");
    }

    private static void callStyleTypes() {
        check(RULES.phaseOfCallType(CallRules.CALL_TYPE_INCOMING) == CallPhase.RINGING, "incoming rings");
        check(RULES.phaseOfCallType(CallRules.CALL_TYPE_SCREENING) == CallPhase.RINGING, "screening still waits for the driver");
        check(RULES.phaseOfCallType(CallRules.CALL_TYPE_ONGOING) == CallPhase.ACTIVE, "ongoing is active");
        check(RULES.phaseOfCallType(0) == CallPhase.NONE, "unknown shows nothing");
    }

    private static CallState call(CallPhase phase, String caller) {
        return new CallState(phase, caller, -1, false, CallSource.TELECOM);
    }

    private static void theTileShowsTheCallThatNeedsTheDriver() {
        CallState waiting = call(CallPhase.RINGING, "B");
        CallState active = call(CallPhase.ACTIVE, "A");
        CallState held = call(CallPhase.HOLDING, "C");
        check(RULES.pick(List.of(active, waiting, held)) == waiting, "a call waiting beats the connected one");
        check(RULES.pick(List.of(held, active)) == active, "the connected call beats the held one");
        check(RULES.pick(List.of(held, call(CallPhase.DIALING, "D"))).getPhase() == CallPhase.DIALING, "dialling beats held");
        check(RULES.pick(List.of(call(CallPhase.ENDED, "E"), held)) == held, "an ended call only when nothing else is left");
        check(RULES.pick(List.of()).getPhase() == CallPhase.NONE, "no calls is none");
    }

    private static void callerText() {
        check("Mom".equals(RULES.caller("Mom", "MOBILE CALLER", "+15125550100", CallRules.PRESENTATION_ALLOWED)), "the contact first");
        check("ACME CORP".equals(RULES.caller(" ", "ACME CORP", "+15125550100", CallRules.PRESENTATION_ALLOWED)), "then the network's name");
        check("+15125550100".equals(RULES.caller(null, null, "+15125550100", CallRules.PRESENTATION_ALLOWED)), "then the number");
        check("Private number".equals(RULES.caller(null, null, "", CallRules.PRESENTATION_RESTRICTED)), "a withheld number says so");
        check("Unknown caller".equals(RULES.caller(null, null, null, CallRules.PRESENTATION_UNKNOWN)), "never an empty tile");
    }

    private static void keypadKeys() {
        for (char c : "0123456789*#".toCharArray()) check(RULES.isDtmf(c), "key " + (int) c);
        check(!RULES.isDtmf('A') && !RULES.isDtmf(',') && !RULES.isDtmf(' '), "nothing else is sent as a tone");
    }

    private static void controlsFollowTheRoute() {
        CallState telecom = new CallState(CallPhase.ACTIVE, "A", 1_000, false, CallSource.TELECOM);
        check(telecom.getCanMute() && telecom.getCanDial(), "a Telecom call in progress offers Mute and the keypad");
        CallState ringing = new CallState(CallPhase.RINGING, "A", -1, false, CallSource.TELECOM);
        check(!ringing.getCanMute() && !ringing.getCanDial(), "a ringing call does not");
        CallState fallback = new CallState(CallPhase.ACTIVE, "A", 1_000, false, CallSource.NOTIFICATION);
        check(!fallback.getCanMute() && !fallback.getCanDial(), "the notification fallback has no mute and no keypad");
        check(CallState.Companion.getNONE().getPhase() == CallPhase.NONE, "NONE is no call");
    }

    /** Every combination of the setting, the adb-granted app-op and notification access. */
    private static void theRouteIsChosenPerSession() {
        for (boolean granted : new boolean[] {false, true}) {
            for (boolean access : new boolean[] {false, true}) {
                check(RULES.route(false, granted, access) == CallRoute.OFF,
                        "setting off is off whatever is granted (" + granted + "," + access + ")");
            }
        }
        check(RULES.route(true, true, true) == CallRoute.TELECOM, "the app-op wins: mute and keypad");
        check(RULES.route(true, true, false) == CallRoute.TELECOM, "and needs no notification access");
        check(RULES.route(true, false, true) == CallRoute.NOTIFICATION, "without it, the call notification");
        check(RULES.route(true, false, false) == CallRoute.UNAVAILABLE, "with neither, no route");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
