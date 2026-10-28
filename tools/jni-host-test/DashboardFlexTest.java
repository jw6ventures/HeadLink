// Dashboard part 2b on a desktop JVM (P7.8, P7.9): the flex tile's priority and label, its
// notification rows with the favourites' scroll rules and the read-aloud stub, the call
// card's buttons on both call routes, the pop-up keypad over the half the map does not
// take, and that no report, note or printed snapshot can carry a caller, a notification or
// a keypad digit. Dashboard, DashboardLayout, FlexRules and TouchList are pure Kotlin with
// no Android API, so these are the classes the app draws from. Text widths are estimated
// at DashboardLayout.EM_PER_CHARACTER, not measured by a real font: the lab frames are the
// check of the drawing itself.
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import com.jw6ventures.headlink.Box;
import com.jw6ventures.headlink.CallAction;
import com.jw6ventures.headlink.CallActionKind;
import com.jw6ventures.headlink.CallPhase;
import com.jw6ventures.headlink.CallSource;
import com.jw6ventures.headlink.CallState;
import com.jw6ventures.headlink.Dashboard;
import com.jw6ventures.headlink.DashboardChoices;
import com.jw6ventures.headlink.DashboardLayout;
import com.jw6ventures.headlink.DashboardPalette;
import com.jw6ventures.headlink.DashNotification;
import com.jw6ventures.headlink.DayNightMode;
import com.jw6ventures.headlink.Favorite;
import com.jw6ventures.headlink.FlexRules;
import com.jw6ventures.headlink.FlexSource;
import com.jw6ventures.headlink.MapOrientation;
import com.jw6ventures.headlink.MarkerColour;
import com.jw6ventures.headlink.MediaSessions;
import com.jw6ventures.headlink.Player;
import com.jw6ventures.headlink.SpeedUnit;
import com.jw6ventures.headlink.TouchList;
import com.jw6ventures.headlink.UpcomingTurn;

public final class DashboardFlexTest {
    private static int passed;

    private static final long NOON = Instant.parse("2026-09-24T12:00:00Z").toEpochMilli();
    private static final String CALLER = "Callermarker Person";

    public static void main(String[] args) {
        for (DashboardLayout layout : layouts()) {
            theNotificationListSitsInTheFlexTile(layout);
            theCallButtonsFillTheTile(layout);
            theKeypadCoversTheOtherHalf(layout);
        }
        theTileShowsACallThenNotificationsThenNothing();
        theTouchListKeepsTheFavouritesRules();
        notificationRowsScrollAndReadAloud();
        aRowGoesWhenItGoesFromThePhone();
        theRingingCardAnswersAndDeclines();
        theInCallRowMutesAndHangsUp();
        theNotificationRouteHasNoMuteOrKeypad();
        theDurationRuns();
        telecomsHeadsetSessionIsNotAPlayer();
        theKeypadClaimsTheGestureAndPlaysTones();
        theKeypadClosesWithTheCall();
        aTripDoesNotDisplaceTheFlexTile();
        bothPalettesHaveLegibleCallColours();
        nothingPrintedCarriesACallerANotificationOrADigit();
        System.out.println("jni-host-test: ok " + passed + " flex tile, call card and keypad checks");
    }

    private static List<DashboardLayout> layouts() {
        return List.of(
            new DashboardLayout(0, 0, 1920, 1080, true),
            new DashboardLayout(0, 0, 1920, 1080, false),
            new DashboardLayout(74, 0, 726, 480, true),
            new DashboardLayout(0, 0, 800, 480, true));
    }

    private static String size(DashboardLayout l) { return l.getWidth() + "x" + l.getHeight() + (l.getMapOnLeft() ? "L" : "R"); }

    // --- layout ----------------------------------------------------------------------

    private static void theNotificationListSitsInTheFlexTile(DashboardLayout l) {
        String at = size(l) + ": ";
        Box tile = l.getFlexTile();
        Box list = l.getNotificationsList();
        check(tile.equals(l.getTiles().get(DashboardLayout.FLEX_TILE)), at + "the flex tile is the second tile");
        check(inside(list, tile) && list.getY() >= tile.getY() + l.tileTitleHeight(tile), at + "the list is in the tile, under its label");
        int row = l.getNotificationRowHeight();
        check(row * DashboardLayout.NOTIFICATION_ROWS <= list.getHeight(), at + "two rows fit");
        // Line 1 (who) at 0.28 of a row across it, line 2 ("App - text") at 0.2.
        check(row * 0.28f >= 20, at + "the large line is at least 20 px: " + row * 0.28f);
        check(row * 0.2f >= 14, at + "the small line is at least 14 px: " + row * 0.2f);
        if (l.getHeight() == 1080) {
            // A sender of ordinary length, "Alice Example", fits line 1 whole at 0.6 em a character.
            check(13 * 0.6f * row * 0.28f <= list.getWidth() - 2 * l.getMargin(), at + "a full name fits line 1 at 1080p");
        }
        check(l.getNotificationSlop() == Math.max(4, row / 5), at + "the slop is a fifth of a row, as the favourites'");
        check(l.hitNotification(list.getCenterX(), list.getY(), 0, 3) == 0, at + "the top row hits");
        check(l.hitNotification(list.getCenterX(), list.getY() + row, 0, 3) == 1, at + "the next row is the second");
        check(l.hitNotification(list.getCenterX(), list.getY() + row, row, 3) == 2, at + "scrolled a row, it is the third");
        check(l.hitNotification(tile.getX() + 2, tile.getY() + 2, 0, 3) < 0, at + "the label strip is no row");
        check(l.notificationsMaxScroll(5) == 5 * row - list.getHeight(), at + "five rows scroll by what does not fit");
        check(!overlaps(list, l.getFavoritesList()), at + "and it is not the favourites");
    }

    private static void theCallButtonsFillTheTile(DashboardLayout l) {
        String at = size(l) + ": ";
        Box tile = l.getFlexTile();
        int m = l.getMargin();
        Box name = l.getCallName();
        check(inside(name, tile) && name.getY() == tile.getY() + l.tileTitleHeight(tile), at + "the caller is under the label");
        // Ringing: each half the tile's width, the whole height under the name.
        Box answer = l.ringButton(0);
        Box decline = l.ringButton(1);
        check(inside(answer, tile) && inside(decline, tile), at + "Answer and Decline are in the tile");
        check(Math.abs(answer.getWidth() * 2 + 3 * m - tile.getWidth()) <= 1 && answer.getWidth() == decline.getWidth(),
            at + "each is half the tile's width, a margin between");
        check(answer.getY() == name.getBottom() + m && answer.getBottom() == tile.getBottom() - m,
            at + "and the full height under the name");
        check(!overlaps(answer, decline) && answer.getX() < decline.getX(), at + "Answer left of Decline, apart");
        check(answer.getHeight() >= l.getFavoriteRowHeight() && answer.getHeight() >= l.getEndRoute().getHeight() / 2,
            at + "bigger than a favourite's row: " + answer.getHeight());
        check(l.hitCallAction(answer.getCenterX(), answer.getCenterY(), FlexRules.INSTANCE.getRING_ACTIONS()).equals("answer"),
            at + "Answer hits");
        check(l.hitCallAction(decline.getX(), decline.getBottom() - 1, FlexRules.INSTANCE.getRING_ACTIONS()).equals("decline"),
            at + "Decline's corner hits");
        check(l.hitCallAction(answer.getRight() + m / 2, answer.getCenterY(), FlexRules.INSTANCE.getRING_ACTIONS()) == null,
            at + "the gap between them misses");
        check(l.hitCallAction(name.getCenterX(), name.getCenterY(), FlexRules.INSTANCE.getRING_ACTIONS()) == null,
            at + "the name is not a button");
        // The word is drawn at 0.26 of the smaller side and brought down until "Decline", in
        // bold at a generous 0.65 em a character, fits: what that leaves must still be big.
        float text = Math.min(Math.min(answer.getWidth(), answer.getHeight()) * 0.26f, (answer.getWidth() - m) / (7 * 0.65f));
        check(text >= (l.getHeight() >= 1080 ? 30 : 14), at + "\"Decline\" fits its button at " + text + " px");

        // In a call: three across under the duration, or Hang up across the row.
        List<String> full = FlexRules.INSTANCE.getCALL_ACTIONS();
        Box duration = l.getCallDuration();
        check(duration.getY() == name.getBottom() && inside(duration, tile), at + "the duration is under the name");
        for (int i = 0; i < 3; i++) {
            Box b = l.callActionBox(full, i);
            check(inside(b, tile) && b.getY() == duration.getBottom() + m, at + "call button " + i + " is in the row under the duration");
            check(b.getWidth() >= 40 && b.getHeight() >= 40, at + "call button " + i + " is at least 40 px square: " + b.getWidth() + "x" + b.getHeight());
            check(full.get(i).equals(l.hitCallAction(b.getCenterX(), b.getCenterY(), full)), at + full.get(i) + " hits");
            if (i > 0) check(!overlaps(b, l.callActionBox(full, i - 1)), at + "call buttons " + (i - 1) + " and " + i + " are apart");
        }
        Box alone = l.callActionBox(FlexRules.INSTANCE.getHANG_UP_ONLY(), 0);
        check(alone.getX() == tile.getX() + m && alone.getRight() == tile.getRight() - m, at + "Hang up alone spans the row");
        check("hang-up".equals(l.hitCallAction(alone.getX() + 1, alone.getCenterY(), FlexRules.INSTANCE.getHANG_UP_ONLY())),
            at + "and hits end to end");
        // "Keypad", the row's longest word, fitted the same way.
        Box keypad = l.callActionBox(full, 1);
        float small = Math.min(Math.min(keypad.getWidth(), keypad.getHeight()) * 0.26f, (keypad.getWidth() - m) / (6 * 0.65f));
        check(small >= (l.getHeight() >= 1080 ? 24 : 10), at + "\"Keypad\" fits its button at " + small + " px");
    }

    private static void theKeypadCoversTheOtherHalf(DashboardLayout l) {
        String at = size(l) + ": ";
        Box pad = l.getKeypad();
        Box media = l.getMedia();
        check(pad.getX() == media.getX() && pad.getY() == media.getY() && pad.getWidth() == media.getWidth(), at + "the keypad starts at the media pane");
        check(pad.getBottom() == l.getTiles().get(0).getBottom() && pad.getBottom() == l.getFlexTile().getBottom(), at + "and runs to the tiles' bottom");
        check(!overlaps(pad, l.getMap()), at + "the map stays in view");
        check(inside(l.getKeypadEcho(), pad) && inside(l.getKeypadClose(), pad), at + "the strip and Close are on the keypad");
        check(l.getKeypadEcho().getRight() < l.getKeypadClose().getX(), at + "the echo is left of Close");
        check(l.getKeypadClose().getWidth() >= pad.getWidth() / 4 - 1, at + "Close is a quarter of the keypad");
        String keys = FlexRules.KEYPAD_KEYS;
        check(keys.equals("123456789*0#"), "the keys in the telephone's order");
        for (int i = 0; i < keys.length(); i++) {
            Box k = l.keypadKey(i);
            check(inside(k, pad) && k.getY() > l.getKeypadEcho().getBottom(), at + "key " + keys.charAt(i) + " is on the keypad, under the strip");
            check(l.hitKeypadKey(k.getCenterX(), k.getCenterY()) == i, at + "key " + keys.charAt(i) + " hits");
            check(l.hitKeypadKey(k.getX(), k.getY()) == i && l.hitKeypadKey(k.getRight() - 1, k.getBottom() - 1) == i,
                at + "at its corners too");
            for (int j = 0; j < i; j++) check(!overlaps(k, l.keypadKey(j)), at + "keys " + j + " and " + i + " are apart");
        }
        check(l.keypadKey(1).getY() == l.keypadKey(0).getY() && l.keypadKey(3).getY() > l.keypadKey(0).getY()
            && l.keypadKey(10).getX() == l.keypadKey(1).getX(), at + "three columns, 0 under 8");
        check(l.hitKeypadKey(l.keypadKey(0).getRight() + l.getMargin() / 2, l.keypadKey(0).getCenterY()) == -1, at + "the gap misses");
        // Much bigger than twelve keys inside the tile would be.
        Box k = l.keypadKey(4);
        check(k.getWidth() * 2 > l.getFlexTile().getWidth() / 3 * 2 && k.getHeight() > l.getFlexTile().getHeight() / 5,
            at + "keys are far bigger than in the tile: " + k.getWidth() + "x" + k.getHeight());
        if (l.getHeight() == 1080) check(k.getWidth() >= 280 && k.getHeight() >= 170, at + "about 290 x 180 at 1080p: " + k.getWidth() + "x" + k.getHeight());
    }

    // --- priority --------------------------------------------------------------------

    private static void theTileShowsACallThenNotificationsThenNothing() {
        Dashboard d = dashboard(false);
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check(s.getFlex() == FlexSource.EMPTY && "notifications".equals(s.getFlexLabel()), "nothing: the empty state, labelled notifications");
        check(!s.getNotificationsPaused(), "not paused, so it says No notifications");
        check(!"vehicle".equals(s.getFlexLabel()) && !DashboardLayout.Companion.getTILE_LABELS().contains("vehicle"), "never \"vehicle, not configured\"");

        d.notifications(rows(3));
        s = d.nextFrame(NOON);
        check(s.getFlex() == FlexSource.NOTIFICATIONS && s.getNotifications().size() == 3, "notifications when there are any");

        d.call(ringing(CallSource.TELECOM));
        s = d.nextFrame(NOON);
        check(s.getFlex() == FlexSource.CALL && "incoming call".equals(s.getFlexLabel()), "a call comes first");
        check(s.getNotifications().isEmpty(), "and no rows are drawn under it");
        check(CALLER.equals(s.getCall().getCaller()), "the caller is drawn");

        d.call(CallState.Companion.getNONE());
        check(d.nextFrame(NOON).getFlex() == FlexSource.NOTIFICATIONS, "the call over, the notifications are back");

        Dashboard paused = dashboard(true);
        paused.notifications(rows(3));
        s = paused.nextFrame(NOON);
        check(s.getFlex() == FlexSource.EMPTY && s.getNotificationsPaused() && s.getNotifications().isEmpty(),
            "paused with the pause: nothing drawn, and the tile says Notifications paused");
        paused.call(ringing(CallSource.TELECOM));
        check(paused.nextFrame(NOON).getFlex() == FlexSource.CALL, "the pause does not hide a call");

        check(FlexRules.INSTANCE.source(CallState.Companion.getNONE(), false, 0) == FlexSource.EMPTY, "the rule: nothing");
        check(FlexRules.INSTANCE.label(FlexSource.CALL, new CallState(CallPhase.ACTIVE, CALLER, NOON, true, CallSource.TELECOM)).equals("Muted"),
            "a muted call's label says Muted");
        check("No notifications".equals(FlexRules.NO_NOTIFICATIONS) && "Notifications paused".equals(FlexRules.PAUSED), "the documented words");
    }

    // --- notifications ---------------------------------------------------------------

    private static void theTouchListKeepsTheFavouritesRules() {
        TouchList list = new TouchList();
        list.down(500, 2);
        check(list.getPressed() == 2 && list.getActive(), "a press lights its row");
        list.move(500 - 20, 20, 1000);
        check(list.getPressed() == 2 && list.getScroll() == 0, "within the slop it is still a press");
        list.move(500 - 150, 20, 1000);
        check(list.getPressed() == -1 && list.getScroll() == 150, "past it, a scroll that follows the finger");
        list.move(500, 20, 1000);
        check(list.getScroll() == 0 && list.getPressed() == -1, "back past the start is still a scroll, never a tap again");
        check(list.up(2) == -1 && list.getScrolls() == 1 && list.getTaps() == 0, "a scroll picks nothing and is counted");
        list.move(0, 20, 1000);
        check(list.getScroll() == 0, "with no finger down nothing moves");
        list.down(100, 1);
        check(list.up(0) == -1, "a lift on another row picks nothing");
        list.down(100, 1);
        check(list.up(1) == 1 && list.getTaps() == 1, "press and release on a row picks it");
        list.down(100, -1);
        check(list.up(-1) == -1, "the empty space past the last row picks nothing");
        list.down(100, 0);
        list.move(100 - 5000, 20, 300);
        check(list.getScroll() == 300, "the scroll stops at the last row");
        list.replaced(120);
        check(list.getScroll() == 120 && !list.getActive() && list.getPressed() == -1, "a shorter list clamps and drops the finger");
        list.down(100, 0);
        list.cancel();
        check(list.up(0) == -1, "a cancel picks nothing");
    }

    private static void notificationRowsScrollAndReadAloud() {
        DashboardLayout l = new DashboardLayout(0, 0, 1920, 1080, true);
        Dashboard d = new Dashboard(l, "t", ZoneId.of("UTC"));
        List<DashNotification> five = rows(5);
        d.notifications(five);
        Box list = l.getNotificationsList();
        int row = l.getNotificationRowHeight();
        int x = list.getCenterX();
        int second = list.getY() + row + row / 2;

        d.touch("down", x, second);
        check(d.nextFrame(NOON).getPressedNotification() == 1, "a press lights the row under it");
        check(d.takeReadAloud() == null, "nothing until the release");
        d.touch("up", x, second, NOON);
        DashNotification read = d.takeReadAloud();
        check(read != null && read.getKey().equals(five.get(1).getKey()), "press and release asks for that row to be read aloud");
        check(d.takeReadAloud() == null, "once");
        Dashboard.Snapshot s = d.nextFrame(NOON + 100);
        check(five.get(1).getKey().equals(s.getFlashNotification()) && s.getPressedNotification() == -1, "the row flashes");
        check(d.nextFrame(NOON + Dashboard.READ_FLASH_MS + 1).getFlashNotification() == null, "for a moment");

        d.touch("down", x, second);
        d.touch("up", x, second - row);
        check(d.takeReadAloud() == null, "lifting on another row reads nothing");

        int near = list.getBottom() - 10;
        d.touch("down", x, near);
        d.touch("move", x, near - l.getNotificationSlop());
        check(d.nextFrame(NOON).getNotificationsScroll() == 0, "within the slop it is still a press");
        d.touch("move", x, near - 150);
        s = d.nextFrame(NOON);
        check(s.getNotificationsScroll() == 150 && s.getPressedNotification() == -1, "the rows follow the finger: " + s.getNotificationsScroll());
        d.touch("move", x, near - 5000);
        d.touch("up", x, near - 5000);
        check(d.nextFrame(NOON).getNotificationsScroll() == l.notificationsMaxScroll(5), "and stop at the last row");
        check(d.takeReadAloud() == null, "a scroll reads nothing");
        check(d.nextFrame(NOON).getMapFollowing(), "a drag on the tile leaves the map alone");

        d.touch("down", x, near);
        d.touch("cancel", x, near);
        d.touch("up", x, near);
        check(d.takeReadAloud() == null, "a cancelled press reads nothing");
        String report = d.report();
        check(report.contains("dashboard_notification_taps=1 ") && report.contains("dashboard_notification_scrolls=1\n"),
            "the report counts the tap and the scroll");
        check(report.contains("dashboard_flex=notifications ") && report.contains("dashboard_notifications=5 "), "and what the tile showed");

        // Paused, the rows are not drawn, so they take no taps either.
        Dashboard paused = dashboard(true);
        paused.notifications(rows(2));
        tap(paused, x, list.getY() + row / 2);
        check(paused.takeReadAloud() == null, "a paused tile's empty state is not a row");
    }

    private static void aRowGoesWhenItGoesFromThePhone() {
        DashboardLayout l = new DashboardLayout(0, 0, 1920, 1080, true);
        Dashboard d = new Dashboard(l, "t", ZoneId.of("UTC"));
        List<DashNotification> three = rows(3);
        d.notifications(three);
        Box list = l.getNotificationsList();
        int x = list.getCenterX();
        int y = list.getY() + l.getNotificationRowHeight() / 2;
        d.touch("down", x, y);
        // Removed on the phone while a finger rests on it: the feed sends the rest.
        d.notifications(three.subList(1, 3));
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check(s.getNotifications().size() == 2 && s.getNotifications().get(0).getKey().equals(three.get(1).getKey()),
            "the removed row is gone, the next one takes its place");
        check(s.getPressedNotification() == -1, "the finger on it was dropped");
        d.touch("up", x, y);
        check(d.takeReadAloud() == null, "so its lift reads nothing: the row under it is another one now");
        d.notifications(new ArrayList<>());
        check(d.nextFrame(NOON).getFlex() == FlexSource.EMPTY, "the last gone, the tile is quiet");
    }

    // --- the call card ---------------------------------------------------------------

    private static void theRingingCardAnswersAndDeclines() {
        for (DashboardLayout l : layouts()) {
            String at = size(l) + ": ";
            Dashboard d = new Dashboard(l, "t", ZoneId.of("UTC"));
            Box answer = l.ringButton(0);
            tap(d, answer.getCenterX(), answer.getCenterY());
            check(d.takeCallActions().isEmpty(), at + "no call, no Answer");

            d.call(ringing(CallSource.TELECOM));
            check(d.nextFrame(NOON).getCallActions().equals(List.of("answer", "decline")), at + "ringing: Answer and Decline");
            d.touch("down", answer.getCenterX(), answer.getCenterY());
            check("answer".equals(d.nextFrame(NOON).getPressedCallAction()), at + "Answer lights while pressed");
            check(d.takeCallActions().isEmpty(), at + "and does nothing until the release");
            d.touch("up", answer.getCenterX(), answer.getCenterY());
            check(d.takeCallActions().equals(List.of(new CallAction(CallActionKind.ANSWER, ' '))), at + "press and release answers");
            check(d.takeCallActions().isEmpty(), at + "once");

            Box decline = l.ringButton(1);
            d.touch("down", decline.getCenterX(), decline.getCenterY());
            d.touch("up", answer.getCenterX(), answer.getCenterY());
            check(d.takeCallActions().isEmpty(), at + "sliding from Decline to Answer does neither");
            d.touch("down", decline.getCenterX(), decline.getCenterY());
            d.touch("cancel", decline.getCenterX(), decline.getCenterY());
            check(d.takeCallActions().isEmpty() && d.nextFrame(NOON).getPressedCallAction() == null, at + "nor does a cancel");
            tap(d, decline.getCenterX(), decline.getCenterY());
            check(d.takeCallActions().equals(List.of(new CallAction(CallActionKind.DECLINE, ' '))), at + "press and release declines");
            check(d.report().contains("dashboard_answer_presses=1 dashboard_decline_presses=1 "), at + "both counted");
        }
    }

    private static void theInCallRowMutesAndHangsUp() {
        DashboardLayout l = new DashboardLayout(0, 0, 1920, 1080, true);
        Dashboard d = new Dashboard(l, "t", ZoneId.of("UTC"));
        d.call(active(CallSource.TELECOM, false));
        Dashboard.Snapshot s = d.nextFrame(NOON + 42_000);
        List<String> row = FlexRules.INSTANCE.getCALL_ACTIONS();
        check(s.getCallActions().equals(row), "in a call with full controls: Mute, Keypad, Hang up");
        check("call".equals(s.getFlexLabel()), "labelled call");
        Box mute = l.callActionBox(row, 0);
        tap(d, mute.getCenterX(), mute.getCenterY());
        check(d.takeCallActions().equals(List.of(new CallAction(CallActionKind.MUTE, ' '))), "Mute asks to mute");
        // Telecom says it is muted; the card follows what Telecom says, not the press.
        check(!d.nextFrame(NOON).getCall().getMuted(), "not drawn muted until Telecom says so");
        d.call(active(CallSource.TELECOM, true));
        s = d.nextFrame(NOON);
        check(s.getCall().getMuted() && "Muted".equals(s.getFlexLabel()), "muted: lit, and the tile says Muted");
        tap(d, mute.getCenterX(), mute.getCenterY());
        check(d.takeCallActions().equals(List.of(new CallAction(CallActionKind.UNMUTE, ' '))), "pressed again, unmute");
        Box hangUp = l.callActionBox(row, 2);
        tap(d, hangUp.getCenterX(), hangUp.getCenterY());
        check(d.takeCallActions().equals(List.of(new CallAction(CallActionKind.HANG_UP, ' '))), "Hang up hangs up");

        d.call(new CallState(CallPhase.DIALING, CALLER, -1, false, CallSource.TELECOM));
        s = d.nextFrame(NOON);
        check(s.getCallActions().equals(FlexRules.INSTANCE.getHANG_UP_ONLY()) && "calling".equals(s.getFlexLabel()), "dialling: Hang up only");
        d.call(new CallState(CallPhase.HOLDING, CALLER, NOON, false, CallSource.TELECOM));
        check(d.nextFrame(NOON).getCallActions().equals(FlexRules.INSTANCE.getHANG_UP_ONLY()), "on hold: Hang up only");
        d.call(new CallState(CallPhase.ENDED, CALLER, -1, false, CallSource.TELECOM));
        s = d.nextFrame(NOON);
        check(s.getCallActions().isEmpty() && "call ended".equals(s.getFlexLabel()), "ended: no buttons");
        check(d.report().contains("dashboard_mute_presses=2 ") && d.report().contains("dashboard_hang_up_presses=1 "), "counted");
    }

    private static void theNotificationRouteHasNoMuteOrKeypad() {
        DashboardLayout l = new DashboardLayout(0, 0, 1920, 1080, true);
        Dashboard d = new Dashboard(l, "t", ZoneId.of("UTC"));
        d.call(ringing(CallSource.NOTIFICATION));
        check(d.nextFrame(NOON).getCallActions().equals(List.of("answer", "decline")), "the notification route answers and declines");
        CallState fallback = active(CallSource.NOTIFICATION, false);
        check(!fallback.getCanMute() && !fallback.getCanDial(), "and cannot mute or dial");
        d.call(fallback);
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check(s.getCallActions().equals(List.of("hang-up")), "connected on it: Hang up alone, no Mute, no Keypad");
        // Where Mute and Keypad would be is Hang up now, and nothing opens the keypad.
        Box first = l.callActionBox(FlexRules.INSTANCE.getCALL_ACTIONS(), 1);
        tap(d, first.getCenterX(), first.getCenterY());
        check(!d.nextFrame(NOON).getKeypadOpen(), "no keypad on the notification route");
        check(d.takeCallActions().equals(List.of(new CallAction(CallActionKind.HANG_UP, ' '))), "the press is Hang up");
    }

    private static void theDurationRuns() {
        CallState call = active(CallSource.TELECOM, false);
        check("0:00".equals(FlexRules.INSTANCE.duration(call, NOON)), "connected now: 0:00");
        check("0:42".equals(FlexRules.INSTANCE.duration(call, NOON + 42_999)), "whole seconds");
        check("12:05".equals(FlexRules.INSTANCE.duration(call, NOON + 725_000)), "minutes");
        check("1:02:03".equals(FlexRules.INSTANCE.duration(call, NOON + 3_723_000)), "hours");
        check(FlexRules.INSTANCE.duration(ringing(CallSource.TELECOM), NOON) == null, "none while ringing");
        check(FlexRules.INSTANCE.duration(new CallState(CallPhase.ACTIVE, CALLER, -1, false, CallSource.TELECOM), NOON) == null,
            "none without a connect time");
        Dashboard d = dashboard(false);
        d.call(call);
        check("0:42".equals(d.nextFrame(NOON + 42_000).getCallDuration()), "the card carries it");
        check(d.nextFrame(NOON + 42_100).getCallDuration().equals(d.nextFrame(NOON + 42_900).getCallDuration()),
            "within a second it reads the same, so the chrome repaints once a second");
    }

    private static void telecomsHeadsetSessionIsNotAPlayer() {
        MediaSessions rules = MediaSessions.INSTANCE;
        String own = "com.jw6ventures.headlink";
        // During a call: Telecom's HeadsetMediaButton first, with no state, then a paused player.
        check(rules.pick(List.of(MediaSessions.TELECOM, "com.example.music"), List.of(false, true), own) == 1,
            "Telecom's headset session is skipped for the player behind it");
        check(rules.pick(List.of(MediaSessions.TELECOM), List.of(true), own) == -1, "Telecom is never a player, even with a state");
        check(rules.pick(List.of("com.example.stateless", "com.example.music"), List.of(false, true), own) == 1,
            "nor is any session without a playback state");
        check(rules.pick(List.of(own, "com.example.music"), List.of(true, true), own) == 1, "nor this app");
        check(rules.pick(List.of("com.example.a", "com.example.b"), List.of(true, true), own) == 0, "otherwise the platform's order stands");
        check(rules.pick(List.of(), List.of(), own) == -1, "nothing: none");
    }

    // --- the keypad ------------------------------------------------------------------

    private static Dashboard inCallWithKeypad(DashboardLayout l) {
        Dashboard d = new Dashboard(l, "t", ZoneId.of("UTC"));
        d.players(List.of(new Player("com.example.player", "Player Label", false, true, 0)));
        List<Favorite> places = new ArrayList<>();
        for (int i = 0; i < 4; i++) places.add(new Favorite("Place " + i, 30.2, -97.7));
        d.favorites(places);
        d.call(active(CallSource.TELECOM, false));
        Box keypad = l.callActionBox(FlexRules.INSTANCE.getCALL_ACTIONS(), 1);
        tap(d, keypad.getCenterX(), keypad.getCenterY());
        return d;
    }

    private static void theKeypadClaimsTheGestureAndPlaysTones() {
        for (DashboardLayout l : layouts()) {
            String at = size(l) + ": ";
            Dashboard d = inCallWithKeypad(l);
            Dashboard.Snapshot s = d.nextFrame(NOON);
            check(s.getKeypadOpen() && s.getKeypadEcho().isEmpty(), at + "Keypad opens it, with an empty strip");
            check(d.takeCallActions().isEmpty(), at + "opening it sends nothing");

            // Under it: a player row, a favourite and the flex tile's own buttons. None hears a press.
            Box player = l.playerRow(0, false);
            tap(d, player.getX() + 2, player.getY() + 2);
            check(d.takeResumePick() == null, at + "the player row under the keypad takes no press");
            Box fav = l.favoriteRow(0, 0);
            tap(d, fav.getCenterX(), fav.getCenterY());
            check(d.takeFavoritePick() == null, at + "nor the favourite under it");
            Box hang = l.callActionBox(FlexRules.INSTANCE.getCALL_ACTIONS(), 2);
            d.takeCallActions();
            tap(d, hang.getCenterX(), hang.getCenterY());
            for (CallAction a : d.takeCallActions()) {
                check(a.getKind() != CallActionKind.HANG_UP, at + "nor Hang up under it");
            }

            // Those presses landed on keys or the strip; what they typed is the baseline.
            String base = d.nextFrame(NOON).getKeypadEcho();
            int basePresses = base.length();
            d.takeCallActions();
            Box nine = l.keypadKey(8);
            d.touch("down", nine.getCenterX(), nine.getCenterY());
            List<CallAction> down = d.takeCallActions();
            check(down.equals(List.of(new CallAction(CallActionKind.KEY_DOWN, '9'))), at + "the tone starts on the press: " + down);
            check(d.nextFrame(NOON).getPressedKey() == 8, at + "the key lights while held");
            check(base.equals(d.nextFrame(NOON).getKeypadEcho()), at + "the strip waits for the release");
            d.touch("up", nine.getCenterX(), nine.getCenterY());
            check(d.takeCallActions().equals(List.of(new CallAction(CallActionKind.KEY_UP, ' '))), at + "and stops on the release");
            check((base + "9").equals(d.nextFrame(NOON).getKeypadEcho()), at + "the key goes in the strip");

            Box hash = l.keypadKey(11);
            Box star = l.keypadKey(9);
            d.touch("down", hash.getCenterX(), hash.getCenterY());
            d.touch("move", star.getCenterX(), star.getCenterY());
            d.touch("up", star.getCenterX(), star.getCenterY());
            List<CallAction> slid = d.takeCallActions();
            check(slid.equals(List.of(new CallAction(CallActionKind.KEY_DOWN, '#'), new CallAction(CallActionKind.KEY_UP, ' '))),
                at + "sliding off a key still stops its tone on the lift");
            check((base + "9").equals(d.nextFrame(NOON).getKeypadEcho()), at + "but puts nothing in the strip");

            d.touch("down", star.getCenterX(), star.getCenterY());
            d.touch("cancel", star.getCenterX(), star.getCenterY());
            check(d.takeCallActions().size() == 2 && (base + "9").equals(d.nextFrame(NOON).getKeypadEcho()), at + "a cancel stops the tone, nothing echoed");

            for (int k : new int[] {9, 10, 11}) tap(d, l.keypadKey(k).getCenterX(), l.keypadKey(k).getCenterY());
            check((base + "9*0#").equals(d.nextFrame(NOON).getKeypadEcho()), at + "*, 0 and # echo too");
            List<CallAction> taps = d.takeCallActions();
            check(taps.size() == 6 && taps.get(0).getKey() == '*' && taps.get(2).getKey() == '0' && taps.get(4).getKey() == '#',
                at + "each a start and a stop, in order");

            // A press on the keypad's own background is claimed too: the map is not panned.
            d.touch("down", l.getKeypad().getX() + 1, l.getKeypad().getBottom() - 1);
            d.touch("up", l.getKeypad().getX() + 1, l.getKeypad().getBottom() - 1);
            check(d.takeCallActions().isEmpty() && d.nextFrame(NOON).getKeypadOpen(), at + "its background does nothing");

            // The map beside it is not the keypad's.
            d.touch("down", l.getMap().getCenterX(), l.getMap().getCenterY());
            d.touch("move", l.getMap().getCenterX() + 100, l.getMap().getCenterY());
            d.touch("up", l.getMap().getCenterX() + 100, l.getMap().getCenterY());
            check(d.takeCallActions().isEmpty() && d.nextFrame(NOON).getKeypadOpen(), at + "a drag on the map sends no key and leaves it open");

            Box close = l.getKeypadClose();
            d.touch("down", close.getCenterX(), close.getCenterY());
            check(d.nextFrame(NOON).getPressedKeypadClose(), at + "Close lights");
            d.touch("up", close.getX() - l.getMargin() * 3, close.getCenterY());
            check(d.nextFrame(NOON).getKeypadOpen(), at + "sliding off Close keeps it open");
            tap(d, close.getCenterX(), close.getCenterY());
            s = d.nextFrame(NOON);
            check(!s.getKeypadOpen(), at + "Close closes it");
            check((base + "9*0#").equals(s.getKeypadEcho()), at + "the strip keeps this call's keys");
            String report = d.report();
            check(report.contains("dashboard_keypad_opens=1 ") && report.contains("dashboard_keypad_closes=1 ")
                && report.contains("dashboard_keypad_presses=" + (basePresses + 4) + "\n"), at + "counted: " + report.substring(report.indexOf("dashboard_calls")));
        }
    }

    private static void theKeypadClosesWithTheCall() {
        DashboardLayout l = new DashboardLayout(0, 0, 1920, 1080, true);
        Dashboard d = inCallWithKeypad(l);
        Box five = l.keypadKey(4);
        tap(d, five.getCenterX(), five.getCenterY());
        d.takeCallActions();
        // Put on hold with a finger on a key: the keypad goes, and the tone is stopped.
        d.touch("down", five.getCenterX(), five.getCenterY());
        d.takeCallActions();
        d.call(new CallState(CallPhase.HOLDING, CALLER, NOON, false, CallSource.TELECOM));
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check(!s.getKeypadOpen(), "the keypad closes when the call can no longer take tones");
        check(d.takeCallActions().equals(List.of(new CallAction(CallActionKind.KEY_UP, ' '))), "and the held tone is stopped");
        d.touch("up", five.getCenterX(), five.getCenterY());
        check(d.takeCallActions().isEmpty(), "the finger's lift then does nothing more");
        check("5".equals(s.getKeypadEcho()), "on hold, the strip is kept for the call");

        d.call(active(CallSource.TELECOM, false));
        tap(d, l.callActionBox(FlexRules.INSTANCE.getCALL_ACTIONS(), 1).getCenterX(), l.callActionBox(FlexRules.INSTANCE.getCALL_ACTIONS(), 1).getCenterY());
        check(d.nextFrame(NOON).getKeypadOpen() && "5".equals(d.nextFrame(NOON).getKeypadEcho()), "back from hold, it reopens with its keys");
        d.call(CallState.Companion.getNONE());
        s = d.nextFrame(NOON);
        check(!s.getKeypadOpen() && s.getKeypadEcho().isEmpty(), "the call over: closed, and the strip forgotten");
        check(d.report().contains("dashboard_keypad_auto_closes=2 "), "both closes counted as the call's");
        d.call(active(CallSource.TELECOM, false));
        check(d.nextFrame(NOON).getKeypadEcho().isEmpty(), "the next call starts with an empty strip");
    }

    private static void aTripDoesNotDisplaceTheFlexTile() {
        DashboardLayout l = new DashboardLayout(0, 0, 1920, 1080, true);
        Dashboard d = new Dashboard(l, "t", ZoneId.of("UTC"));
        d.upcomingTurn(new UpcomingTurn(UpcomingTurn.WAITING, null));
        d.call(ringing(CallSource.TELECOM));
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check(s.getTrip() != null && s.getFlex() == FlexSource.CALL, "a trip takes the favourites' tile; the call keeps the flex tile");
        tap(d, l.ringButton(0).getCenterX(), l.ringButton(0).getCenterY());
        check(d.takeCallActions().size() == 1 && !d.takeEndRoute(), "Answer is Answer, not End route");
        tap(d, l.getEndRoute().getCenterX(), l.getEndRoute().getCenterY());
        check(d.takeEndRoute() && d.takeCallActions().isEmpty(), "and End route is End route");
    }

    // --- palette and privacy ---------------------------------------------------------

    private static void bothPalettesHaveLegibleCallColours() {
        DashboardPalette[] palettes = {DashboardPalette.Companion.getDAY(), DashboardPalette.Companion.getNIGHT()};
        String[] names = {"day: ", "night: "};
        for (int i = 0; i < 2; i++) {
            DashboardPalette p = palettes[i];
            String n = names[i];
            check(DashboardPalette.Companion.contrast(p.getOnAction(), p.getAnswer()) >= 4.5, n + "Answer's word is 4.5:1 on green");
            check(DashboardPalette.Companion.contrast(p.getOnAction(), p.getDecline()) >= 4.5, n + "Decline's word is 4.5:1 on red");
            check(DashboardPalette.Companion.contrast(p.getAnswer(), p.getTile()) >= 3.0, n + "green stands off the tile, 3:1");
            check(DashboardPalette.Companion.contrast(p.getDecline(), p.getTile()) >= 3.0, n + "red stands off the tile, 3:1");
            check(DashboardPalette.Companion.contrast(p.getTile(), p.getWarning()) >= 4.5, n + "a lit Mute's word is 4.5:1");
            // Green is green and red is red: the channel that names each one leads it.
            int g = p.getAnswer();
            int r = p.getDecline();
            check(((g >> 8) & 0xFF) > ((g >> 16) & 0xFF) && ((g >> 8) & 0xFF) > (g & 0xFF), n + "Answer is green");
            check(((r >> 16) & 0xFF) > ((r >> 8) & 0xFF) && ((r >> 16) & 0xFF) > (r & 0xFF), n + "Decline is red");
            check((g >>> 24) == 0xFF && (r >>> 24) == 0xFF && (p.getOnAction() >>> 24) == 0xFF, n + "all three are opaque");
        }
    }

    private static void nothingPrintedCarriesACallerANotificationOrADigit() {
        DashboardLayout l = new DashboardLayout(0, 0, 1920, 1080, true);
        Dashboard d = new Dashboard(l, "t", ZoneId.of("UTC"));
        d.notifications(rows(3));
        tap(d, l.getNotificationsList().getCenterX(), l.getNotificationsList().getY() + 5);
        d.takeReadAloud();
        d.call(ringing(CallSource.TELECOM));
        tap(d, l.ringButton(0).getCenterX(), l.ringButton(0).getCenterY());
        d.call(active(CallSource.TELECOM, false));
        tap(d, l.callActionBox(FlexRules.INSTANCE.getCALL_ACTIONS(), 1).getCenterX(), l.callActionBox(FlexRules.INSTANCE.getCALL_ACTIONS(), 1).getCenterY());
        // A PIN-like sequence, and every key once.
        String typed = "7294#*0135868";
        List<CallAction> sent = new ArrayList<>(d.takeCallActions());
        for (char c : typed.toCharArray()) {
            Box key = l.keypadKey(FlexRules.KEYPAD_KEYS.indexOf(c));
            tap(d, key.getCenterX(), key.getCenterY());
        }
        sent.addAll(d.takeCallActions());
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check(typed.equals(s.getKeypadEcho()), "the strip shows what was typed: " + s.getKeypadEcho());
        String report = d.report();
        for (String never : new String[] {CALLER, "Callermarker", "Sendermarker", "Appmarker", "Bodymarker", "com.example.app", "7294", "0135", "#", "*"}) {
            check(!report.contains(never), "the report never carries " + never);
        }
        String printed = s.toString();
        for (String never : new String[] {CALLER, "Sendermarker", "Bodymarker", "7294", "Player Label"}) {
            check(!printed.contains(never), "a printed snapshot never carries " + never + ": " + printed);
        }
        // The event log's lines: a key press is never noted, and nothing noted names a key.
        int keyNotes = 0;
        for (CallAction a : sent) {
            String note = a.getNote();
            if (note != null) {
                for (char c : FlexRules.KEYPAD_KEYS.toCharArray()) check(note.indexOf(c) < 0, "the note \"" + note + "\" holds no key");
                check(!note.contains(CALLER), "and no caller");
            }
            if (a.getKind() == CallActionKind.KEY_DOWN || a.getKind() == CallActionKind.KEY_UP) {
                check(note == null, "a key press is not noted");
                keyNotes++;
            }
            for (char c : FlexRules.KEYPAD_KEYS.toCharArray()) check(a.toString().indexOf(c) < 0, "a printed action holds no key: " + a);
        }
        check(keyNotes == typed.length() * 2, "every key sent a start and a stop: " + keyNotes);
        // Every kind with every key: no note or string can name one.
        for (CallActionKind kind : CallActionKind.values()) {
            for (char c : FlexRules.KEYPAD_KEYS.toCharArray()) {
                CallAction a = new CallAction(kind, c);
                check(a.getNote() == null || a.getNote().indexOf(c) < 0, kind + " never notes its key");
                check(a.toString().indexOf(c) < 0 || kind.name().indexOf(c) >= 0, kind + " never prints its key");
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------

    private static Dashboard dashboard(boolean paused) {
        DashboardChoices choices = new DashboardChoices(MarkerColour.BLUE, DayNightMode.AUTO, false, paused, null);
        return new Dashboard(new DashboardLayout(0, 0, 1920, 1080, true), "t", ZoneId.of("UTC"), SpeedUnit.MPH,
            MapOrientation.NORTH_UP, o -> kotlin.Unit.INSTANCE, choices);
    }

    private static List<DashNotification> rows(int count) {
        List<DashNotification> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new DashNotification("key" + i, "Appmarker", "Sendermarker " + i, "Bodymarker text " + i,
                NOON - i * 60_000L, "com.example.app"));
        }
        return list;
    }

    private static CallState ringing(CallSource source) {
        return new CallState(CallPhase.RINGING, CALLER, -1, false, source);
    }

    private static CallState active(CallSource source, boolean muted) {
        return new CallState(CallPhase.ACTIVE, CALLER, NOON, muted, source);
    }

    private static void tap(Dashboard d, int x, int y) {
        d.touch("down", x, y);
        d.touch("up", x, y);
    }

    private static boolean inside(Box inner, Box outer) {
        return inner.getX() >= outer.getX() && inner.getY() >= outer.getY()
            && inner.getRight() <= outer.getRight() && inner.getBottom() <= outer.getBottom();
    }

    private static boolean overlaps(Box a, Box b) {
        return a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom();
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
