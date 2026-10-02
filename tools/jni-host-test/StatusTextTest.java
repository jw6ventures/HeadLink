// P7.7's status in words on a desktop JVM: every row of the plan's section 6.2 table, the
// raw-name fallback for categories it does not know, the route summary, and the Setup
// checklist. StatusText and SetupCheck are pure Kotlin; the Home card and the buttons
// that act on them need a phone.
import java.util.List;
import java.util.Set;
import kotlin.Pair;
import org.openautoandroid.source.RouteFigures;
import org.openautoandroid.source.SetupCheck;
import org.openautoandroid.source.SetupItem;
import org.openautoandroid.source.SpeedUnit;
import org.openautoandroid.source.Stage;
import org.openautoandroid.source.Status;
import org.openautoandroid.source.StatusAction;
import org.openautoandroid.source.StatusText;
import org.openautoandroid.source.StatusTone;

public final class StatusTextTest {
    private static int passed;
    private static final StatusText TEXT = StatusText.INSTANCE;
    private static final SetupCheck SETUP = SetupCheck.INSTANCE;

    public static void main(String[] args) {
        theTable();
        unknownCategories();
        unplugOverridesTheEnding();
        everyStageSaysSomething();
        notification();
        routeSummary();
        durations();
        routeFigures();
        setupChecklist();
        System.out.println("jni-host-test: ok " + passed + " status text checks");
    }

    private static Status idle(String category, boolean autoStart) {
        return TEXT.of(Stage.IDLE, category, null, autoStart, null, false, false);
    }

    private static void is(Status status, String sentence, StatusAction action, String row) {
        check(sentence.equals(status.getSentence()), row + ": says \"" + status.getSentence() + "\"");
        check(status.getAction() == action, row + ": action " + status.getAction());
    }

    /** The plan's section 6.2 table, one row at a time, in its order and wording. */
    private static void theTable() {
        is(idle("-", true), "Plug in to start projecting", StatusAction.NONE, "IDLE, auto-start on");
        is(idle("-", false), "Ready to connect", StatusAction.CONNECT, "IDLE, auto-start off");
        is(TEXT.of(Stage.ARMED, "-", null, true, null, false, false),
                "Waiting for the head unit \u2014 plug in the USB cable", StatusAction.STOP, "ARMED");
        is(TEXT.of(Stage.PERMISSION, "-", null, true, null, false, false),
                "Allow USB access on the prompt", StatusAction.NONE, "PERMISSION");
        for (Stage stage : List.of(Stage.VERSION, Stage.TLS, Stage.AUTH, Stage.DISCOVERY, Stage.READY, Stage.SETUP)) {
            is(TEXT.of(stage, "-", null, true, null, false, false),
                    "Connecting to the head unit\u2026", StatusAction.STOP, "handshake stage " + stage);
        }
        is(TEXT.of(Stage.PROJECTING, "-", null, true, "Home", false, false),
                "Projecting \u00b7 routing to Home", StatusAction.STOP, "PROJECTING with a destination");
        is(TEXT.of(Stage.PROJECTING, "-", null, true, null, false, false),
                "Projecting", StatusAction.STOP, "PROJECTING with none");
        is(TEXT.of(Stage.PROJECTING, "-", null, true, " ", false, false),
                "Projecting", StatusAction.STOP, "PROJECTING with a blank destination");
        is(idle("USB_PERMISSION_DENIED", true), "USB access was refused", StatusAction.TRY_AGAIN, "USB_PERMISSION_DENIED");
        is(TEXT.of(Stage.IDLE, "AUTH_FAILED", "-3", true, null, false, false),
                "The head unit didn't accept this phone's identity", StatusAction.OPEN_DIAGNOSTICS, "AUTH_FAILED -3");
        is(idle("NO_ACCESSORY", false), "Unplugged", StatusAction.NONE, "NO_ACCESSORY");
        is(idle("TLS_CERT_POLICY", true), "This head unit isn't trusted yet", StatusAction.TRUST_AND_CONNECT,
                "certificate policy failure");
        check(StatusText.CERT_POLICY.equals("TLS_CERT_POLICY"), "the native category name for a pin failure");
        // Beyond the table: an armed attempt that found only a non-matching accessory.
        is(TEXT.of(Stage.ARMED, "-", null, true, null, false, true),
                "A USB device is connected, but it doesn't look like a head unit", StatusAction.USE_LISTED,
                "ARMED with a mismatch");
        is(TEXT.of(Stage.STOPPING, "-", null, true, null, false, false), "Stopping\u2026", StatusAction.NONE, "STOPPING");
        // Ordinary endings go back to what happens next.
        for (String ending : List.of("CANCELLED", "PROJECTION_COMPLETE", "PEER_SHUTDOWN", "HOLD_COMPLETE", "STAGE_LIMIT")) {
            is(idle(ending, true), "Plug in to start projecting", StatusAction.NONE, ending + " with auto-start");
            is(idle(ending, false), "Ready to connect", StatusAction.CONNECT, ending + " without");
            check(idle(ending, true).getTone() == StatusTone.QUIET, ending + " is not a problem");
        }
        check(TEXT.of(Stage.PROJECTING, "-", null, true, null, false, false).getTone() == StatusTone.GOOD, "projecting is good");
        check(idle("USB_PERMISSION_DENIED", true).getTone() == StatusTone.PROBLEM, "a refusal is a problem");
        check(TEXT.of(Stage.ARMED, "-", null, true, null, false, false).getTone() == StatusTone.BUSY, "waiting is busy");
    }

    /** Nothing gets less visible than it was: an unknown category keeps its raw name. */
    private static void unknownCategories() {
        for (String category : List.of("TLS_TIMEOUT", "IO_EOF", "FGS_START_FAILED", "USB_OPEN_FAILED", "NATIVE_START_FAILED",
                "VIDEO_FOCUS_DENIED", "SOMETHING_NEW")) {
            Status status = idle(category, true);
            check(status.getSentence().contains(category), category + " is named: " + status.getSentence());
            check(status.getAction() == StatusAction.OPEN_DIAGNOSTICS, category + " offers Open Diagnostics");
            check(status.getTone() == StatusTone.PROBLEM, category + " is a problem");
        }
        // AUTH_FAILED is only the table's row with the -3 status; any other is unknown.
        Status other = TEXT.of(Stage.IDLE, "AUTH_FAILED", "-1", true, null, false, false);
        check(other.getSentence().contains("AUTH_FAILED"), "AUTH_FAILED without -3 keeps its raw name");
        check(other.getAction() == StatusAction.OPEN_DIAGNOSTICS, "and offers Open Diagnostics");
        Status none = TEXT.of(Stage.IDLE, "AUTH_FAILED", null, true, null, false, false);
        check(none.getSentence().contains("AUTH_FAILED"), "AUTH_FAILED with no status keeps its raw name");
    }

    /** An unplug mid-session ends as a stop or an I/O error; to the driver it is an unplug. */
    private static void unplugOverridesTheEnding() {
        for (String category : List.of("CANCELLED", "IO_EOF", "IO_ERROR", "-")) {
            is(TEXT.of(Stage.IDLE, category, null, true, null, true, false), "Unplugged", StatusAction.NONE,
                    category + " after a detach");
        }
        // Only when idle: a detach mid-session still shows the stage it is in.
        is(TEXT.of(Stage.STOPPING, "-", null, true, null, true, false), "Stopping\u2026", StatusAction.NONE, "stopping after a detach");
    }

    private static void everyStageSaysSomething() {
        for (Stage stage : Stage.values()) {
            for (boolean auto : new boolean[] {true, false}) {
                Status status = TEXT.of(stage, "-", null, auto, null, false, false);
                check(!status.getSentence().isEmpty(), stage + " says something");
                check(!status.getSentence().contains("Stage:"), stage + " is not the old stage line");
            }
        }
    }

    private static void notification() {
        check("Projecting \u00b7 routing to Home".equals(TEXT.notification(Stage.PROJECTING, "Home")),
                "the notification says what Home says");
        check("Connecting to the head unit\u2026".equals(TEXT.notification(Stage.TLS, null)), "while connecting too");
        check("Waiting for the head unit \u2014 plug in the USB cable".equals(TEXT.notification(Stage.ARMED, null)),
                "and while armed");
    }

    private static void routeSummary() {
        SpeedUnit mph = SpeedUnit.MPH;
        String full = TEXT.routeSummary("Home", false, false, false, 19312.0, 18 * 60, "6:40 PM", mph);
        check("Routing to Home \u00b7 12 mi \u00b7 18 min \u00b7 arrive 6:40 PM".equals(full), "the plan's example: " + full);
        String noClock = TEXT.routeSummary("Home", false, false, false, 19312.0, 18 * 60, null, mph);
        check("Routing to Home \u00b7 12 mi \u00b7 18 min".equals(noClock), "no arrival when the time is unknown: " + noClock);
        String metric = TEXT.routeSummary("Home", false, false, false, 19312.0, 18 * 60, null, SpeedUnit.KMH);
        check("Routing to Home \u00b7 19 km \u00b7 18 min".equals(metric), "kilometres beside km/h: " + metric);
        check(TEXT.routeSummary(null, false, false, false, null, null, null, mph) == null, "nothing to say, nothing said");
        check("Routing to Work \u00b7 finding a route\u2026".equals(TEXT.routeSummary("Work", false, true, false, null, null, null, mph)),
                "computing");
        check("Routing to Work \u00b7 waiting for a position to route from"
                .equals(TEXT.routeSummary("Work", true, false, false, null, null, null, mph)), "waiting");
        check("Routing to Work \u00b7 no route found".equals(TEXT.routeSummary("Work", false, false, true, null, null, null, mph)),
                "failed");
        // A new search replaces the old one's failure in the words.
        check("Routing to Work \u00b7 finding a route\u2026".equals(TEXT.routeSummary("Work", false, true, true, null, null, null, mph)),
                "computing wins over an older failure");
        // The lab's two-point route has no destination.
        String lab = TEXT.routeSummary(null, false, false, false, 800.0, 90, null, mph);
        check("Route \u00b7 0.5 mi \u00b7 2 min".equals(lab), "a route with no destination: " + lab);
        check("Route \u00b7 finding a route\u2026".equals(TEXT.routeSummary(null, false, true, false, null, null, null, mph)),
                "a lab route computing");
    }

    private static void durations() {
        check("under 1 min".equals(TEXT.duration(20)), "under a minute");
        check("1 min".equals(TEXT.duration(45)), "rounded to the nearest minute");
        check("18 min".equals(TEXT.duration(18 * 60)), "minutes");
        check("1 h".equals(TEXT.duration(3600)), "a whole hour");
        check("1 h 5 min".equals(TEXT.duration(3900)), "hours and minutes");
        check("under 1 min".equals(TEXT.duration(-5)), "never negative");
    }

    /** Home's numbers: Guide's live figures while following, else the route's totals. */
    private static void routeFigures() {
        RouteFigures.Companion f = RouteFigures.Companion;
        long now = 1_000_000L;
        RouteFigures live = f.choose(new Pair<>(1500.0, 120), 4059, 518, now - 60_000L, now);
        check(live != null && live.getMetres() == 1500.0 && live.getSeconds() == 120, "live figures win over the totals");
        check(live.getArrivalMillis() == now + 120_000L, "and the arrival is now plus the time to go");
        RouteFigures totals = f.choose(null, 4059, 518, now - 60_000L, now);
        check(totals != null && totals.getMetres() == 4059.0 && totals.getSeconds() == 518, "no live figures: the totals");
        check(totals.getArrivalMillis() == now - 60_000L + 518_000L, "arriving the estimate after the route came back");
        check(f.choose(null, 4059, 518, -1L, now).getArrivalMillis() == null, "an unseen finish time: no arrival");
        check(f.choose(null, 4059, 518, now - 600_000L, now).getArrivalMillis() == null, "a spent estimate: no arrival");
        check(f.choose(null, null, null, now, now) == null, "no route, no live figures: nothing");
        RouteFigures clamped = f.choose(new Pair<>(-3.0, -5), null, null, -1L, now);
        check(clamped.getMetres() == 0.0 && clamped.getSeconds() == 0 && clamped.getArrivalMillis() == now, "never negative");
    }

    private static List<SetupItem> items(int maps, Set<String> tiles, String here, boolean wanted, boolean access,
            boolean location, int pins, boolean notifications) {
        return SETUP.items(maps, tiles, here, wanted, access, location, pins, notifications);
    }

    /** Everything else in place, and P10's two weather parameters as given. */
    private static List<SetupItem> weather(boolean location, boolean on, boolean permission) {
        return SETUP.items(1, Set.of("W100_N30.rd5"), null, true, true, location, 1, true, on, permission);
    }

    private static void setupChecklist() {
        Set<String> tiles = Set.of("W100_N30.rd5");
        check(items(1, tiles, "W100_N30.rd5", true, true, true, 1, true).isEmpty(), "everything in place is Ready");
        check(items(0, Set.of(), null, true, false, false, 0, false).equals(List.of(
                SetupItem.NO_MAP, SetupItem.NO_ROUTING, SetupItem.NOTIFICATION_ACCESS, SetupItem.LOCATION,
                SetupItem.NO_PINS, SetupItem.SESSION_NOTIFICATIONS)), "everything missing, in the plan's order");
        check(items(1, tiles, "W75_N40.rd5", true, true, true, 1, true).equals(List.of(SetupItem.NO_ROUTING_HERE)),
                "tiles, but none for where the phone is");
        check(items(1, tiles, null, true, true, true, 1, true).isEmpty(), "no position: nothing to say about it");
        check(items(1, Set.of(), "W100_N30.rd5", true, true, true, 1, true).equals(List.of(SetupItem.NO_ROUTING)),
                "no tiles at all is the one item, not two");
        check(items(1, tiles, null, false, false, true, 1, true).isEmpty(), "access is only missing when something wants it");
        // P10 (docs/p10-plan.md section 4): only while Show weather is on, after location.
        check(weather(true, true, false).equals(List.of(SetupItem.WEATHER_PERMISSION)),
                "weather on without Breezy's permission is the one item");
        check(weather(true, true, true).isEmpty(), "weather on with the permission is Ready");
        check(weather(true, false, false).isEmpty(), "weather off adds nothing, permission or not");
        check(weather(true, false, true).isEmpty(), "weather off with the permission adds nothing either");
        check(weather(false, true, false).equals(List.of(SetupItem.LOCATION, SetupItem.WEATHER_PERMISSION)),
                "it follows location");
        check(SETUP.items(0, Set.of(), null, true, false, false, 0, false, true, false).equals(List.of(
                SetupItem.NO_MAP, SetupItem.NO_ROUTING, SetupItem.NOTIFICATION_ACCESS, SetupItem.LOCATION,
                SetupItem.WEATHER_PERMISSION, SetupItem.NO_PINS, SetupItem.SESSION_NOTIFICATIONS)),
                "everything missing with weather on, in order");
        check(SetupItem.WEATHER_PERMISSION.getTitle().equals("Weather permission not granted")
                && SetupItem.WEATHER_PERMISSION.getFix().equals("Grant"), "the plan's words and one Grant button");
        check(SETUP.notificationAccessWanted(true, false, false, false), "media info wants it");
        check(SETUP.notificationAccessWanted(false, true, false, false), "dashboard notifications want it");
        check(SETUP.notificationAccessWanted(false, false, true, false), "call controls without the grant want it");
        check(!SETUP.notificationAccessWanted(false, false, true, true), "call controls with the grant do not");
        check(!SETUP.notificationAccessWanted(false, false, false, false), "nothing on wants nothing");
        for (SetupItem item : SetupItem.values()) {
            check(!item.getTitle().isEmpty() && !item.getDetail().isEmpty() && !item.getFix().isEmpty(),
                    item + " has a title, a detail and one fixing button");
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
