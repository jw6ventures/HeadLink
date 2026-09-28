// Dashboard part 2a on a desktop JVM (P7.4, P7.5): the turn card over the map pane, the
// trip panel that takes the favourites tile during a trip, End route, the idle media pane's
// player rows with Resume, and the empty favourites text. Since P8.2 also the voice toggle
// beside End route: its box at every display both ways round, its press, and its state. Dashboard, DashboardLayout,
// MapControl and Maneuvers are pure Kotlin with no Android API, so these are the classes
// the app draws from. Text widths are estimated at DashboardLayout.EM_PER_CHARACTER (a
// generous 0.62 em a character), not measured by a real font: the frames are the check of
// the drawing itself.
import btools.router.VoiceHintReader;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.openautoandroid.source.AlternativeBubble;
import org.openautoandroid.source.AlternativeMark;
import org.openautoandroid.source.Box;
import org.openautoandroid.source.Dashboard;
import org.openautoandroid.source.DashboardChoices;
import org.openautoandroid.source.DashboardLayout;
import org.openautoandroid.source.DayNightMode;
import org.openautoandroid.source.Favorite;
import org.openautoandroid.source.GlyphShape;
import org.openautoandroid.source.LaterStep;
import org.openautoandroid.source.Maneuver;
import org.openautoandroid.source.ManeuverGlyph;
import org.openautoandroid.source.Maneuvers;
import org.openautoandroid.source.MapCamera;
import org.openautoandroid.source.MapControl;
import org.openautoandroid.source.MapOrientation;
import org.openautoandroid.source.MarkerColour;
import org.openautoandroid.source.PhoneSnapshot;
import org.openautoandroid.source.Player;
import org.openautoandroid.source.ResumeResult;
import org.openautoandroid.source.RouteFollower;
import org.openautoandroid.source.SpeedUnit;
import org.openautoandroid.source.TripPanel;
import org.openautoandroid.source.TurnCard;
import org.openautoandroid.source.UpcomingTurn;

public final class DashboardTripTest {
    private static int passed;

    /** 2026-09-24 12:00:00 UTC, so arrival times are easy to read. */
    private static final long NOON = Instant.parse("2026-09-24T12:00:00Z").toEpochMilli();

    public static void main(String[] args) {
        for (DashboardLayout layout : layouts()) {
            theTurnCardSitsAcrossTheTopOfTheMap(layout);
            theTripPanelFillsTheFavouritesTile(layout);
            thePlayerRowsFillTheMediaPane(layout);
            theEmptyFavouritesTextFits(layout);
        }
        everyManeuverHasAGlyphOnTheRightSide();
        theCardAndPanelFollowGuidance();
        theArrivalTimeIsTheClockPlusTheRouteTime();
        endRouteIsAPressAndRelease();
        theFavouritesComeBackWhereTheyWere();
        theMarkerMovesDownUnderTheCard();
        aFingerOnTheCardDoesNotPanTheMap();
        anAlternativesBubbleIsATapAndNotAPan();
        theIdlePaneListsPlayersAndResumes();
        aPausedSessionKeepsItsPane();
        theReportCountsAndNamesNothing();
        for (DashboardLayout layout : everyDisplay()) theVoiceToggleSitsBesideEndRoute(layout);
        theVoiceToggleIsAPressAndRelease();
        theVoiceToggleStartsAsSavedAndWarns();
        System.out.println("jni-host-test: ok " + passed + " dashboard trip, turn card and player checks");
    }

    /** 1080p both ways round, Ford's 726x480 content rectangle, and the 800x480 minimum. */
    private static List<DashboardLayout> layouts() {
        return List.of(
            new DashboardLayout(0, 0, 1920, 1080, true),
            new DashboardLayout(0, 0, 1920, 1080, false),
            new DashboardLayout(74, 0, 726, 480, true),
            new DashboardLayout(0, 0, 800, 480, true));
    }

    /**
     * P8.2: every display the project renders, both ways round: 1080p, Ford's 726x480
     * content rectangle and the 800x480 minimum, each with the map on the left and on
     * the right.
     */
    private static List<DashboardLayout> everyDisplay() {
        return List.of(
            new DashboardLayout(0, 0, 1920, 1080, true),
            new DashboardLayout(0, 0, 1920, 1080, false),
            new DashboardLayout(74, 0, 726, 480, true),
            new DashboardLayout(74, 0, 726, 480, false),
            new DashboardLayout(0, 0, 800, 480, true),
            new DashboardLayout(0, 0, 800, 480, false));
    }

    private static String size(DashboardLayout l) { return l.getWidth() + "x" + l.getHeight() + (l.getMapOnLeft() ? "L" : "R"); }

    // --- layout ----------------------------------------------------------------------

    private static void theTurnCardSitsAcrossTheTopOfTheMap(DashboardLayout l) {
        String at = size(l) + ": ";
        Box map = l.getMap();
        Box card = l.getTurnCard();
        int m = l.getMargin();
        check(card.getX() == map.getX() + m && card.getY() == map.getY() + m, at + "the card is inset a margin from the pane's top left");
        check(card.getRight() == map.getRight() - m, at + "the card runs the pane's width");
        check(card.getHeight() == map.getHeight() / 5, at + "the card is a fifth of the pane: " + card.getHeight());
        Box icon = l.getTurnIcon();
        check(icon.getWidth() == icon.getHeight() && inside(icon, card), at + "the icon is a square inside the card");
        // Distance in bold at 0.4 of the card, beside the icon: "0.3 mi" and "12 mi" fit.
        float distance = card.getHeight() * 0.4f;
        check(icon.getRight() + m + 7 * 0.65f * distance < card.getRight(), at + "a seven-character distance fits beside the icon");
        check(distance >= 18, at + "the distance is at least 18 px: " + distance);
        // Nothing floating over the map is under the card.
        for (int i = 0; i < DashboardLayout.Companion.getMAP_ACTIONS().size(); i++) {
            check(!overlaps(card, l.mapBubble(i)), at + "the card clears map bubble " + i);
        }
        for (int i = 0; i < DashboardLayout.Companion.getZOOM_ACTIONS().size(); i++) {
            check(!overlaps(card, l.zoomBubble(i)), at + "the card clears zoom bubble " + i);
        }
        // The marker moves down by half the card: under it, and above both stacks.
        int markerY = map.getCenterY() + l.getMarkerBelow();
        float arrow = map.getHeight() / 60f * 2.2f;
        check(l.getMarkerBelow() == (card.getBottom() - map.getY()) / 2, at + "half the card's height from the top");
        check(markerY - arrow > card.getBottom(), at + "the arrow's tip is below the card");
        check(markerY + arrow < l.zoomBubble(0).getY(), at + "and above the zoom stack's top");
        int uncoveredMiddle = (card.getBottom() + map.getBottom()) / 2;
        check(Math.abs(markerY - uncoveredMiddle) <= 1, at + "the marker is centred in the map left showing");
    }

    private static void theTripPanelFillsTheFavouritesTile(DashboardLayout l) {
        String at = size(l) + ": ";
        Box tile = l.getTiles().get(DashboardLayout.FAVORITES_TILE);
        Box end = l.getEndRoute();
        int m = l.getMargin();
        check(end.getHeight() == l.mediaButton(0).getHeight(), at + "End route is a media button tall: " + end.getHeight());
        Box voice = l.getVoiceToggle();
        check(end.getX() == tile.getX() + m && end.getRight() == voice.getX() - m, at + "End route runs from the tile's left to a margin short of the voice toggle");
        check(end.getBottom() == tile.getBottom() - m, at + "End route runs along the bottom");
        check(l.hitEndRoute(end.getCenterX(), end.getCenterY()), at + "End route's centre hits");
        check(l.hitEndRoute(end.getX(), end.getY()) && l.hitEndRoute(end.getRight() - 1, end.getBottom() - 1), at + "and its corners");
        check(!l.hitEndRoute(end.getCenterX(), end.getY() - 1), at + "above it misses");
        check(!l.hitEndRoute(l.getTiles().get(DashboardLayout.FLEX_TILE).getCenterX(), end.getCenterY()),
            at + "the flex tile beside it is not End route");
        Box summary = l.getTripSummary();
        check(inside(summary, tile) && summary.getBottom() <= end.getY() - m, at + "the summary is above End route");
        for (int i = 0; i < RouteFollower.LATER_STEPS; i++) {
            Box cell = l.tripLater(i);
            check(inside(cell, tile), at + "later maneuver " + i + " is in the tile");
            check(cell.getY() >= summary.getBottom() && cell.getBottom() <= end.getY() - m, at + "between the summary and End route");
            check(cell.getHeight() >= 20, at + "later maneuver " + i + " is at least 20 px tall: " + cell.getHeight());
            // The icon, then "0.8 mi" at 0.36 of the cell. Digits and "mi" come to about
            // 2.8 em in the platform's sans-serif; 3.2 is allowed.
            float text = cell.getHeight() * 0.36f;
            int icon = Math.min(cell.getHeight() * 3 / 4, cell.getWidth() / 3);
            check(icon + m / 2 + 3.2f * text <= cell.getWidth(),
                at + "an icon and \"0.8 mi\" fit in later cell " + i + ": " + (icon + m / 2 + 3.2f * text) + " of " + cell.getWidth());
        }
        check(!overlaps(l.tripLater(0), l.tripLater(1)), at + "the later maneuvers sit side by side");
        // "18:40" in bold at 0.52 of the summary and "12 min" at 0.3, on one row.
        float h = summary.getHeight();
        float row = 5 * 0.65f * 0.52f * h + m + 6 * DashboardLayout.EM_PER_CHARACTER * 0.3f * h;
        check(row <= summary.getWidth(), at + "the arrival time and the time remaining fit side by side: " + row + " of " + summary.getWidth());
        check(0.52f * h >= 20, at + "the arrival time is at least 20 px: " + 0.52f * h);
        // About 4.5 em in bold; 5 allowed. Since P8.2 the size is the smaller of 0.28 of the
        // height and a fifth of the width less its margins, and must stay readable.
        float endText = Math.min(end.getHeight() * 0.28f, (end.getWidth() - 2f * m) / 5f);
        check(5f * endText <= end.getWidth() - 2 * m, at + "\"End route\" fits its button");
        check(endText >= 16f, at + "\"End route\" is at least 16 px: " + endText);
    }

    private static void thePlayerRowsFillTheMediaPane(DashboardLayout l) {
        String at = size(l) + ": ";
        Box media = l.getMedia();
        int button = l.mediaButton(0).getHeight();
        for (boolean hint : new boolean[] {false, true}) {
            String how = at + (hint ? "with the hint, " : "without it, ");
            for (int i = 0; i < DashboardLayout.PLAYER_ROWS; i++) {
                Box row = l.playerRow(i, hint);
                check(inside(row, media), how + "row " + i + " is in the pane");
                check(row.getHeight() * 100 >= button * 80, how + "row " + i + " is at least 80% of a media button: " + row.getHeight() + "/" + button);
                check(row.getHeight() >= l.getFavoriteRowHeight(), how + "and no smaller than a favourite's row");
                Box resume = l.resumeButton(row);
                check(inside(resume, row) && resume.getRight() == row.getRight(), how + "Resume sits at row " + i + "'s right");
                check(l.hitPlayer(row.getCenterX(), row.getCenterY(), 5, hint) == i, how + "row " + i + " hits");
                check(l.hitPlayer(row.getX(), row.getCenterY(), 5, hint) == i, how + "the name end of row " + i + " hits too");
                if (hint) check(row.getBottom() <= l.getPlayerHint().getY(), how + "row " + i + " is above the hint");
            }
            Box first = l.playerRow(0, hint);
            Box second = l.playerRow(1, hint);
            check(second.getY() - first.getBottom() == l.getMargin(), how + "the rows are a margin apart");
            check(l.hitPlayer(first.getCenterX(), first.getBottom() + l.getMargin() / 2, 5, hint) == -1, how + "the gap misses");
            check(l.hitPlayer(second.getCenterX(), second.getCenterY(), 1, hint) == -1, how + "a row with no player misses");
        }
        check(inside(l.getPlayerHint(), media), at + "the hint is in the pane");
        // "Resume" and its triangle at 0.24 of the button, in three eighths of the row.
        Box resume = l.resumeButton(l.playerRow(0, true));
        float text = resume.getHeight() * 0.24f;
        check(text * (6 * DashboardLayout.EM_PER_CHARACTER + 1) <= resume.getWidth() - 2 * l.getMargin(), at + "\"Resume\" fits its button");
    }

    private static void theEmptyFavouritesTextFits(DashboardLayout l) {
        String at = size(l) + ": ";
        Box list = l.getFavoritesList();
        float size = l.getEmptyFavoritesTextSize();
        for (String line : DashboardLayout.Companion.getEMPTY_FAVORITES()) {
            check(line.length() * DashboardLayout.EM_PER_CHARACTER * size <= list.getWidth() + 0.5f,
                at + "\"" + line + "\" fits the list's width uncut at " + size + " px");
        }
        check(size * 1.3f + size <= list.getHeight(), at + "both lines fit its height");
        check(size >= 10, at + "and are at least 10 px: " + size);
        check(DashboardLayout.Companion.getEMPTY_FAVORITES().equals(List.of("No favourites yet", "Add them on the phone")),
            "the plan's wording");
    }

    // --- glyphs ----------------------------------------------------------------------

    private static void everyManeuverHasAGlyphOnTheRightSide() {
        int[] commands = {
            VoiceHintReader.CONTINUE, VoiceHintReader.TURN_LEFT, VoiceHintReader.TURN_SLIGHT_LEFT,
            VoiceHintReader.TURN_SHARP_LEFT, VoiceHintReader.TURN_RIGHT, VoiceHintReader.TURN_SLIGHT_RIGHT,
            VoiceHintReader.TURN_SHARP_RIGHT, VoiceHintReader.KEEP_LEFT, VoiceHintReader.KEEP_RIGHT,
            VoiceHintReader.U_TURN_LEFT, VoiceHintReader.U_TURN_180, VoiceHintReader.U_TURN_RIGHT,
            VoiceHintReader.EXIT_LEFT, VoiceHintReader.EXIT_RIGHT, VoiceHintReader.ROUNDABOUT,
            VoiceHintReader.ROUNDABOUT_LEFT, VoiceHintReader.END,
        };
        for (int command : commands) {
            Maneuver maneuver = Maneuvers.INSTANCE.of(command, 3);
            check(maneuver != null, "command " + command + " is a maneuver");
            ManeuverGlyph glyph = Maneuvers.INSTANCE.glyph(maneuver.getType(), maneuver.getExit());
            check(glyph.getShape() != GlyphShape.NONE, "maneuver " + maneuver.getType() + " has a glyph");
            String words = maneuver.getInstruction().toLowerCase();
            // The instruction the cluster gets and the icon the card draws agree on a side.
            if (glyph.getShape() != GlyphShape.ROUNDABOUT && glyph.getShape() != GlyphShape.U_TURN) {
                if (words.contains("left")) check(glyph.getDegrees() < 0, maneuver.getType() + " draws to the left");
                if (words.contains("right")) check(glyph.getDegrees() > 0, maneuver.getType() + " draws to the right");
            }
        }
        check(glyph(Maneuvers.STRAIGHT).getDegrees() == 0 && glyph(Maneuvers.STRAIGHT).getShape() == GlyphShape.TURN, "straight on is up");
        check(glyph(Maneuvers.TURN_NORMAL_LEFT).getDegrees() == -90 && glyph(Maneuvers.TURN_NORMAL_RIGHT).getDegrees() == 90, "a turn is square");
        check(Math.abs(glyph(Maneuvers.TURN_SLIGHT_RIGHT).getDegrees()) < 90 && Math.abs(glyph(Maneuvers.TURN_SHARP_RIGHT).getDegrees()) > 90,
            "slight is less than square, sharp more");
        check(glyph(Maneuvers.KEEP_LEFT).getShape() == GlyphShape.KEEP && glyph(Maneuvers.OFF_RAMP_SLIGHT_RIGHT).getShape() == GlyphShape.RAMP,
            "a fork and a ramp are their own shapes");
        check(glyph(Maneuvers.U_TURN_LEFT).getDegrees() < 0 && glyph(Maneuvers.U_TURN_RIGHT).getDegrees() > 0, "U-turns keep their side");
        ManeuverGlyph ccw = Maneuvers.INSTANCE.glyph(Maneuvers.ROUNDABOUT_ENTER_AND_EXIT_CCW, 2);
        check(ccw.getShape() == GlyphShape.ROUNDABOUT && ccw.getExit() == 2 && ccw.getDegrees() == -1,
            "the US roundabout is counter-clockwise and carries its exit");
        check(Maneuvers.INSTANCE.glyph(Maneuvers.ROUNDABOUT_ENTER_AND_EXIT_CW, 1).getDegrees() == 1, "the other way round is clockwise");
        check(glyph(Maneuvers.DESTINATION).getShape() == GlyphShape.DESTINATION, "the destination is a pin");
        check(glyph(Maneuvers.UNKNOWN).getShape() == GlyphShape.NONE && glyph(99).getShape() == GlyphShape.NONE,
            "a value nobody named is not guessed at");
    }

    private static ManeuverGlyph glyph(int type) { return Maneuvers.INSTANCE.glyph(type, 0); }

    // --- state -----------------------------------------------------------------------

    private static UpcomingTurn guiding(String street, double metres, int seconds) {
        List<LaterStep> later = List.of(
            new LaterStep(Maneuvers.INSTANCE.of(VoiceHintReader.TURN_LEFT, 0), 1300.0),
            new LaterStep(Maneuvers.INSTANCE.of(VoiceHintReader.ROUNDABOUT, 2), 2600.0));
        return new UpcomingTurn(street == null ? "Turn right" : "Turn right onto " + street, 480.0,
            Maneuvers.TURN_NORMAL_RIGHT, 0, street, metres, seconds, later);
    }

    private static void theCardAndPanelFollowGuidance() {
        Dashboard d = dashboard(1920, 1080);
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check(s.getTurnCard() == null && s.getTrip() == null, "no route: no card, no trip");

        d.upcomingTurn(guiding("Main Street", 4023.0, 600));
        s = d.nextFrame(NOON);
        TurnCard card = s.getTurnCard();
        check(card != null && card.getManeuver() == Maneuvers.TURN_NORMAL_RIGHT, "the card draws the cluster's value");
        check("0.3 mi".equals(card.getDistance()), "the distance in the driver's unit: " + card.getDistance());
        check("Main Street".equals(card.getLine()), "the street on its own line: " + card.getLine());
        TripPanel trip = s.getTrip();
        check(trip != null && !trip.getArrived() && !trip.getRerouting(), "a trip");
        check("2.5 mi".equals(trip.getDistance()) && "10 min".equals(trip.getRemaining()), "distance and time remaining: " + trip.getDistance() + " " + trip.getRemaining());
        check(trip.getLater().size() == 2 && trip.getLater().get(0).getManeuver() == Maneuvers.TURN_NORMAL_LEFT
            && "0.8 mi".equals(trip.getLater().get(0).getDistance()), "the next two after it, with their distances");
        check(trip.getLater().get(1).getExit() == 2, "a later roundabout keeps its exit");

        d.upcomingTurn(guiding(null, 4023.0, 600));
        check("Turn right".equals(d.nextFrame(NOON).getTurnCard().getLine()), "no street: the instruction instead");

        d.upcomingTurn(new UpcomingTurn(UpcomingTurn.REROUTING, null));
        s = d.nextFrame(NOON);
        check(s.getTurnCard().getDistance() == null && UpcomingTurn.REROUTING.equals(s.getTurnCard().getLine()), "rerouting takes the line");
        check(s.getTrip().getRerouting() && s.getTrip().getArrival() == null && s.getTrip().getRemaining() == null
            && s.getTrip().getLater().isEmpty(), "and the panel has no stale figures");

        d.upcomingTurn(new UpcomingTurn(UpcomingTurn.ARRIVED, null, Maneuvers.DESTINATION, 0, null, 0.0, 0, List.of()));
        s = d.nextFrame(NOON);
        check(UpcomingTurn.ARRIVED.equals(s.getTurnCard().getLine()) && s.getTurnCard().getManeuver() == Maneuvers.DESTINATION,
            "arrived takes the line, under the pin");
        check(s.getTrip().getArrived(), "the panel says arrived, with End route still there");

        d.upcomingTurn(new UpcomingTurn(UpcomingTurn.WAITING, null));
        s = d.nextFrame(NOON);
        check(s.getTurnCard() == null && s.getTrip() != null && s.getTrip().getArrival() == null,
            "a route with no fix yet: no card, but the panel and End route");

        d.upcomingTurn(null);
        s = d.nextFrame(NOON);
        check(s.getTurnCard() == null && s.getTrip() == null, "the card and the panel go with the route");

        Dashboard metric = new Dashboard(new DashboardLayout(0, 0, 1920, 1080, true), "t", ZoneId.of("UTC"), SpeedUnit.KMH);
        metric.upcomingTurn(guiding("Main Street", 4023.0, 600));
        check("500 m".equals(metric.nextFrame(NOON).getTurnCard().getDistance()) && "4.0 km".equals(metric.nextFrame(NOON).getTrip().getDistance()),
            "in the driver's own units");
    }

    private static void theArrivalTimeIsTheClockPlusTheRouteTime() {
        Dashboard d = dashboard(1920, 1080);
        d.upcomingTurn(guiding("Main Street", 4023.0, 600));
        check("12:10".equals(d.nextFrame(NOON).getTrip().getArrival()), "noon plus ten minutes: " + d.nextFrame(NOON).getTrip().getArrival());
        // Parked, the route time stands still and the clock does not: arrival slips.
        check("12:15".equals(d.nextFrame(NOON + 5 * 60_000L).getTrip().getArrival()), "five minutes parked is five minutes later");
        // Rounded to the nearest minute, like "10 min": 12:10:31 reads 12:11.
        check("12:11".equals(d.nextFrame(NOON + 31_000L).getTrip().getArrival()), "arrival rounds to the nearest minute");
        // Within a minute and a few metres, nothing drawn changes, so the chrome is not repainted.
        Dashboard.Snapshot a = d.nextFrame(NOON + 1_000);
        d.upcomingTurn(guiding("Main Street", 4015.0, 599));
        Dashboard.Snapshot b = d.nextFrame(NOON + 2_000);
        check(a.getTrip().equals(b.getTrip()) && a.getTurnCard().equals(b.getTurnCard()), "a second later reads the same");
        check("under 1 min".equals(tripAt(d, 20).getRemaining()), "seconds from the end say so");
        check("1 h 5 min".equals(tripAt(d, 3900).getRemaining()), "hours and minutes");
    }

    private static TripPanel tripAt(Dashboard d, int seconds) {
        d.upcomingTurn(guiding("Main Street", 4023.0, seconds));
        return d.nextFrame(NOON).getTrip();
    }

    private static void endRouteIsAPressAndRelease() {
        for (DashboardLayout layout : layouts()) {
            String at = size(layout) + ": ";
            Dashboard d = new Dashboard(layout, "t", ZoneId.of("UTC"));
            Box end = layout.getEndRoute();
            tap(d, end.getCenterX(), end.getCenterY());
            check(!d.takeEndRoute(), at + "no route, no End route");

            d.upcomingTurn(guiding("Main Street", 4023.0, 600));
            d.touch("down", end.getCenterX(), end.getCenterY());
            check(d.nextFrame(NOON).getTrip().getEndPressed(), at + "the button lights while pressed");
            check(!d.takeEndRoute(), at + "and does nothing until the release");
            d.touch("up", end.getCenterX(), end.getCenterY());
            check(!d.nextFrame(NOON).getTrip().getEndPressed(), at + "unlit after");
            check(d.takeEndRoute(), at + "a press and release ends the route");
            check(!d.takeEndRoute(), at + "once");

            d.touch("down", end.getCenterX(), end.getCenterY());
            d.touch("up", end.getCenterX(), end.getY() - layout.getMargin() * 2);
            check(!d.takeEndRoute(), at + "sliding off is not a press");
            d.touch("down", end.getCenterX(), end.getCenterY());
            d.touch("cancel", end.getCenterX(), end.getCenterY());
            check(!d.takeEndRoute(), at + "nor is a cancel");
            d.touch("down", layout.getMap().getCenterX(), layout.getMap().getCenterY());
            d.touch("up", end.getCenterX(), end.getCenterY());
            check(!d.takeEndRoute(), at + "nor a drag that ends on it");
            check(d.report().contains("dashboard_end_route_presses=1 "), at + "one press counted");
        }
    }

    private static void theFavouritesComeBackWhereTheyWere() {
        DashboardLayout layout = new DashboardLayout(0, 0, 1920, 1080, true);
        Dashboard d = new Dashboard(layout, "t", ZoneId.of("UTC"));
        List<Favorite> places = new ArrayList<>();
        for (int i = 0; i < 8; i++) places.add(new Favorite("Place " + i, 30.2 + i * 0.01, -97.7));
        d.favorites(places);
        Box list = layout.getFavoritesList();
        // Scroll the list by a row and a half.
        int x = list.getCenterX();
        int y = list.getBottom() - 5;
        int by = layout.getFavoriteRowHeight() * 3 / 2;
        d.touch("down", x, y);
        d.touch("move", x, y - by);
        d.touch("up", x, y - by);
        int scrolled = d.nextFrame(NOON).getFavoritesScroll();
        check(scrolled == by, "scrolled by a row and a half: " + scrolled);

        // A finger resting on a favourite as the trip arrives does not become a tap on a place.
        d.touch("down", x, list.getY() + layout.getFavoriteRowHeight() / 2);
        d.upcomingTurn(guiding("Main Street", 4023.0, 600));
        d.touch("up", x, list.getY() + layout.getFavoriteRowHeight() / 2);
        check(d.takeFavoritePick() == null, "the trip took the tile out from under the finger");
        // During the trip the tile is the panel: a tap where a row was picks nothing.
        tap(d, x, layout.tripLater(0).getCenterY());
        check(d.takeFavoritePick() == null, "no favourite is picked during a trip");
        check(d.nextFrame(NOON).getTrip() != null, "the trip is drawn in their place");

        d.upcomingTurn(null);
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check(s.getTrip() == null && s.getFavoritesScroll() == scrolled, "the favourites come back scrolled where they were");
        tap(d, x, list.getY() + layout.getFavoriteRowHeight() / 2);
        check(d.takeFavoritePick() != null, "and take taps again");
    }

    private static void theMarkerMovesDownUnderTheCard() {
        DashboardLayout layout = new DashboardLayout(0, 0, 1920, 1080, true);
        Box pane = layout.getMap();
        Dashboard d = new Dashboard(layout, "t", ZoneId.of("UTC"));
        d.phone(new PhoneSnapshot(60, false, 3, "wifi", 30.27, -97.74, null, null, null, true));
        d.mapCamera();
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check(s.getMapMarker().getFirst() == pane.getCenterX() && s.getMapMarker().getSecond() == pane.getCenterY(),
            "no card: the marker is the pane's centre");
        d.upcomingTurn(guiding("Main Street", 4023.0, 600));
        MapCamera camera = d.mapCamera();
        s = d.nextFrame(NOON);
        check(s.getMapMarker().getFirst() == pane.getCenterX()
            && Math.abs(s.getMapMarker().getSecond() - (pane.getCenterY() + layout.getMarkerBelow())) <= 1,
            "with the card, the marker is half a card lower: " + s.getMapMarker());
        check(camera.getLatitude() > 30.27, "so the camera looks north of the vehicle, north up");
        // A route with no fix yet has no card, so nothing moves.
        d.upcomingTurn(new UpcomingTurn(UpcomingTurn.WAITING, null));
        d.mapCamera();
        check(d.nextFrame(NOON).getMapMarker().getSecond() == pane.getCenterY(), "no card, no offset");

        // In travel-up with the vehicle heading east, "down the screen" is west of it.
        for (double bearing : new double[] {0, 90, 200, 333}) {
            MapControl control = new MapControl(MapOrientation.HEADING_UP);
            kotlin.Pair<Double, Double> at = new kotlin.Pair<>(42.4242, -71.2121);
            MapCamera c = control.camera(at, bearing, 150);
            kotlin.Pair<Integer, Integer> marker = control.marker(pane, at);
            check(marker != null && Math.abs(marker.getFirst() - pane.getCenterX()) <= 1
                && Math.abs(marker.getSecond() - (pane.getCenterY() + 150)) <= 1,
                "heading " + bearing + ": the marker is 150 px under the centre: " + marker);
            check(c.getLatitude() != 42.4242 || c.getLongitude() != -71.2121, "and the camera is ahead of the vehicle");
        }
        // A map the driver has moved is not moved again by the card.
        MapControl free = new MapControl(MapOrientation.NORTH_UP);
        kotlin.Pair<Double, Double> at = new kotlin.Pair<>(42.4242, -71.2121);
        free.camera(at, null, 0);
        free.touch("down", List.of(new org.openautoandroid.source.TouchPoint(0, 500, 500, 500, 500)), pane, 0);
        free.touch("move", List.of(new org.openautoandroid.source.TouchPoint(0, 400, 400, 400, 400)), pane, 10);
        free.touch("up", List.of(new org.openautoandroid.source.TouchPoint(0, 400, 400, 400, 400)), pane, 20);
        MapCamera before = free.camera(at, null, 0);
        MapCamera after = free.camera(at, null, 150);
        check(!free.getFollowing() && before.equals(after), "a panned map ignores the offset");
    }

    /**
     * The live alternatives' "+6 min" bubbles: drawn where the map puts their point, kept
     * inside the pane, a press and release picks that one, a finger on one does not pan the
     * map, and one whose point is off the pane is neither drawn nor pressed.
     */
    private static void anAlternativesBubbleIsATapAndNotAPan() {
        for (int[] size : new int[][] {{1920, 1080}, {726, 480}, {800, 480}}) {
            DashboardLayout layout = new DashboardLayout(0, 0, size[0], size[1], true);
            Box pane = layout.getMap();
            Dashboard d = new Dashboard(layout, "t", ZoneId.of("UTC"));
            d.phone(new PhoneSnapshot(60, false, 3, "wifi", 30.27, -97.74, null, null, null, true));
            d.mapCamera();
            d.nextFrame(NOON);
            d.alternatives(List.of(
                new AlternativeMark(7L, 30.272, -97.738, "+6 min"),
                new AlternativeMark(8L, 31.5, -97.74, "+2 min")));
            MapCamera before = d.mapCamera();
            Dashboard.Snapshot s = d.nextFrame(NOON);
            check(s.getAlternativeBubbles().size() == 1, size[0] + ": the one on the pane is drawn, the one off it is not");
            AlternativeBubble bubble = s.getAlternativeBubbles().get(0);
            check(bubble.getId() == 7L && "+6 min".equals(bubble.getLabel()) && inside(bubble.getBox(), pane),
                size[0] + ": inside the pane: " + bubble.getBox());
            check(bubble.getBox().getHeight() >= 24, size[0] + ": at least a finger tall");
            int x = bubble.getBox().getCenterX();
            int y = bubble.getBox().getCenterY();
            d.touch("down", x, y);
            check(d.nextFrame(NOON).getAlternativeBubbles().get(0).getPressed(), size[0] + ": lit while pressed");
            d.touch("move", x - 40, y - 40);
            d.touch("up", x, y);
            check(d.takeAlternativePick() == 7L, size[0] + ": press and release takes it");
            check(d.takeAlternativePick() == null, size[0] + ": once");
            check(d.mapCamera().equals(before), size[0] + ": and the map did not pan under the finger");
            // A press that ends off the bubble takes nothing.
            d.touch("down", x, y);
            d.touch("up", pane.getX() + 2, pane.getY() + 2);
            check(d.takeAlternativePick() == null, size[0] + ": a press dragged off it takes nothing");
            d.alternatives(List.of());
            check(d.nextFrame(NOON).getAlternativeBubbles().isEmpty(), size[0] + ": and they go when the list does");
        }
    }

    private static void aFingerOnTheCardDoesNotPanTheMap() {
        DashboardLayout layout = new DashboardLayout(0, 0, 1920, 1080, true);
        Dashboard d = new Dashboard(layout, "t", ZoneId.of("UTC"));
        d.phone(new PhoneSnapshot(60, false, 3, "wifi", 30.27, -97.74, null, null, null, true));
        d.mapCamera();
        d.upcomingTurn(guiding("Main Street", 4023.0, 600));
        d.mapCamera();
        Box card = layout.getTurnCard();
        d.touch("down", card.getCenterX(), card.getCenterY());
        d.touch("move", card.getCenterX() + 200, card.getCenterY() + 200);
        d.touch("up", card.getCenterX() + 200, card.getCenterY() + 200);
        check(d.nextFrame(NOON).getMapFollowing(), "a drag from the card leaves the map following");
        // Without a card the same place is map, and the same drag pans it.
        d.upcomingTurn(null);
        d.mapCamera();
        d.touch("down", card.getCenterX(), card.getCenterY());
        d.touch("move", card.getCenterX() + 200, card.getCenterY() + 200);
        d.touch("up", card.getCenterX() + 200, card.getCenterY() + 200);
        check(!d.nextFrame(NOON).getMapFollowing(), "with no card it pans, as before");
    }

    private static List<Player> players(int count) {
        List<Player> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(new Player("com.example.player" + i, "Player Label " + i, i == 0, true, 100 - i));
        return list;
    }

    private static void theIdlePaneListsPlayersAndResumes() {
        DashboardLayout layout = new DashboardLayout(0, 0, 1920, 1080, true);
        Dashboard d = new Dashboard(layout, "t", ZoneId.of("UTC"));
        d.players(players(3));
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check(s.getMediaIdle(), "nothing ever played: the pane lists players");
        check(s.getPlayers().size() == 3, "all three are carried; the pane draws what fits");
        check(d.report().contains("dashboard_player_rows=2 "), "two rows fit");

        // The transport buttons are not drawn, so they are not pressed: the same spot is
        // the second player's row now.
        Box play = layout.mediaButton(1);
        d.touch("down", play.getCenterX(), play.getCenterY());
        check(d.touch("up", play.getCenterX(), play.getCenterY()) == null, "no transport action from an idle pane");
        Player under = d.takeResumePick();
        check(under != null && "com.example.player1".equals(under.getKey()), "the press went to the row drawn there");
        d.resumeResult("com.example.player1", ResumeResult.PLAYED_RECENT, NOON);

        Box row = layout.playerRow(1, false);
        d.touch("down", row.getCenterX(), row.getCenterY());
        check(d.nextFrame(NOON).getPressedPlayer() == 1, "the row lights while pressed");
        check(d.takeResumePick() == null, "nothing until the release");
        d.touch("up", row.getCenterX(), row.getCenterY());
        Player picked = d.takeResumePick();
        check(picked != null && "com.example.player1".equals(picked.getKey()), "a press and release picks that row's player");
        check(d.takeResumePick() == null, "once");
        check("com.example.player1".equals(d.nextFrame(NOON).getResumePending()), "and it is pending until answered");

        d.resumeResult("com.example.player1", ResumeResult.REFUSED, NOON);
        s = d.nextFrame(NOON + 1_000);
        check(s.getResumePending() == null && "com.example.player1".equals(s.getResumeFailed()), "a refusal says so on the row");
        check(d.nextFrame(NOON + Dashboard.RESUME_FAILED_MS + 1).getResumeFailed() == null, "for a few seconds");

        tap(d, layout.playerRow(0, false).getCenterX(), layout.playerRow(0, false).getCenterY());
        d.takeResumePick();
        d.resumeResult("com.example.player0", ResumeResult.PLAYED_SESSION, NOON);
        s = d.nextFrame(NOON);
        check(s.getResumePending() == null && s.getResumeFailed() == null, "a played one simply stops being pending");

        d.touch("down", row.getCenterX(), row.getCenterY());
        d.touch("up", row.getCenterX(), row.getY() - layout.getMargin());
        check(d.takeResumePick() == null, "sliding off a row is not a press");

        // No access: the hint takes the bottom, and the rows move up to make room.
        d.nowPlaying(false, "stopped", "", "", "");
        s = d.nextFrame(NOON);
        check(s.getMediaIdle() && !s.getMediaAccess(), "without access, still idle, with the hint");
        Box hinted = layout.playerRow(1, true);
        int gap = hinted.getY() + layout.getMargin() / 2;
        check(layout.hitPlayer(hinted.getCenterX(), gap, 3, false) == -1, "that point is between the rows without the hint");
        tap(d, hinted.getCenterX(), gap);
        Player second = d.takeResumePick();
        check(second != null && "com.example.player1".equals(second.getKey()), "the rows with the hint are the ones hit");

        // Something plays: the pane is now-playing again, and its buttons work.
        d.nowPlaying(true, "playing", "app", "A Title", "An Artist");
        s = d.nextFrame(NOON);
        check(!s.getMediaIdle(), "playing: not idle");
        tap(d, row.getCenterX(), row.getCenterY());
        check(d.takeResumePick() == null, "and the rows are gone");
        d.touch("down", play.getCenterX(), play.getCenterY());
        check("play-pause".equals(d.touch("up", play.getCenterX(), play.getCenterY())), "the transport buttons are back");
        d.nowPlaying(true, "stopped", "", "", "");
        check(d.nextFrame(NOON).getMediaIdle(), "stopped with nothing to show is idle again");
    }

    private static void aPausedSessionKeepsItsPane() {
        Dashboard d = dashboard(1920, 1080);
        d.players(players(2));
        d.nowPlaying(true, "paused", "app", "A Title", "");
        check(!d.nextFrame(NOON).getMediaIdle(), "paused with a title keeps now-playing");
        d.nowPlaying(true, "paused", "", "", "");
        check(!d.nextFrame(NOON).getMediaIdle(), "paused with nothing but its state still does");
    }

    private static void theReportCountsAndNamesNothing() {
        Dashboard d = dashboard(1920, 1080);
        d.players(players(3));
        d.upcomingTurn(guiding("Labmarker Street", 4023.0, 600));
        d.nextFrame(NOON);
        DashboardLayout layout = d.getLayout();
        tap(d, layout.getEndRoute().getCenterX(), layout.getEndRoute().getCenterY());
        tap(d, layout.playerRow(0, false).getCenterX(), layout.playerRow(0, false).getCenterY());
        d.resumeResult("com.example.player0", ResumeResult.TIMED_OUT, NOON);
        String report = d.report();
        for (String want : new String[] {
            "dashboard_trip=true ", "dashboard_trips=1 ", "dashboard_turn_card=true ", "dashboard_end_route_presses=1 ",
            "dashboard_media_idle=true ", "dashboard_player_rows=2 ", "dashboard_resume_presses=1 ",
            "dashboard_resume_played=0 ", "dashboard_resume_failures=1\n",
        }) {
            check(report.contains(want), "the report has " + want.trim());
        }
        for (String never : new String[] {"Labmarker", "Player Label", "com.example", "12:10", "0.3 mi", "Turn right"}) {
            check(!report.contains(never), "the report never carries " + never);
        }
    }

    // --- P8.2: the voice toggle ----------------------------------------------------------

    private static void theVoiceToggleSitsBesideEndRoute(DashboardLayout l) {
        String at = size(l) + ": ";
        Box tile = l.getTiles().get(DashboardLayout.FAVORITES_TILE);
        Box end = l.getEndRoute();
        Box voice = l.getVoiceToggle();
        int m = l.getMargin();
        check(inside(voice, tile), at + "the toggle is in the tile");
        check(voice.getHeight() == end.getHeight() && voice.getY() == end.getY(), at + "End route's height, on its row");
        check(voice.getRight() == tile.getRight() - m, at + "at the row's right end");
        check(voice.getWidth() <= voice.getHeight(), at + "no wider than it is tall");
        check(voice.getWidth() >= 40, at + "at least 40 px wide: " + voice.getWidth());
        check(voice.getWidth() == voice.getHeight() || end.getWidth() >= 2 * voice.getWidth(),
            at + "square, or End route keeps twice its width");
        check(end.getWidth() > voice.getWidth(), at + "End route is still the wider target: " + end.getWidth() + " > " + voice.getWidth());
        check(!overlaps(voice, end), at + "the toggle and End route never overlap");
        check(voice.getX() - end.getRight() >= m, at + "a margin between them: " + (voice.getX() - end.getRight()));
        // A finger on one can never reach the other: every column of the row belongs to
        // at most one, and the gap to neither.
        int y = end.getCenterY();
        for (int x = tile.getX(); x < tile.getRight(); x++) {
            check(!(l.hitEndRoute(x, y) && l.hitVoiceToggle(x, y)), at + "no point is both, at x=" + x);
        }
        check(l.hitVoiceToggle(voice.getCenterX(), voice.getCenterY()), at + "the toggle's centre hits");
        check(l.hitVoiceToggle(voice.getX(), voice.getY()) && l.hitVoiceToggle(voice.getRight() - 1, voice.getBottom() - 1),
            at + "and its corners");
        check(!l.hitVoiceToggle(end.getCenterX(), end.getCenterY()), at + "End route's centre is not the toggle");
        check(!l.hitEndRoute(voice.getCenterX(), voice.getCenterY()), at + "the toggle's centre is not End route");
        check(!l.hitVoiceToggle(voice.getRight() + 1, voice.getCenterY()), at + "right of it misses");
        check(!l.hitVoiceToggle(voice.getCenterX(), voice.getY() - 1), at + "above it misses");
        for (int i = 0; i < RouteFollower.LATER_STEPS; i++) {
            check(!overlaps(voice, l.tripLater(i)), at + "clear of later maneuver " + i);
        }
        check(!overlaps(voice, l.getTripSummary()), at + "and of the summary");
        check(!overlaps(voice, l.getTiles().get(DashboardLayout.FLEX_TILE)), at + "and of the flex tile beside it");
    }

    private static void theVoiceToggleIsAPressAndRelease() {
        for (DashboardLayout layout : everyDisplay()) {
            String at = size(layout) + ": ";
            Dashboard d = new Dashboard(layout, "t", ZoneId.of("UTC"));
            Box voice = layout.getVoiceToggle();
            Box end = layout.getEndRoute();
            tap(d, voice.getCenterX(), voice.getCenterY());
            check(d.takeVoiceToggle() == null, at + "no route, no toggle");

            d.upcomingTurn(guiding("Main Street", 4023.0, 600));
            check(d.nextFrame(NOON).getTrip().getVoiceOn(), at + "on by default");
            d.touch("down", voice.getCenterX(), voice.getCenterY());
            check(d.nextFrame(NOON).getTrip().getVoicePressed(), at + "lit while pressed");
            check(d.takeVoiceToggle() == null, at + "and nothing until the release");
            d.touch("up", voice.getCenterX(), voice.getCenterY());
            TripPanel off = d.nextFrame(NOON).getTrip();
            check(!off.getVoicePressed() && !off.getVoiceOn(), at + "a press and release turns it off, drawn at once");
            check(Boolean.FALSE.equals(d.takeVoiceToggle()), at + "handed over as off");
            check(d.takeVoiceToggle() == null, at + "once");
            check(!d.takeEndRoute(), at + "and End route was not pressed");

            tap(d, voice.getCenterX(), voice.getCenterY());
            check(d.nextFrame(NOON).getTrip().getVoiceOn(), at + "a second press turns it back on");
            check(Boolean.TRUE.equals(d.takeVoiceToggle()), at + "handed over as on");

            d.touch("down", voice.getCenterX(), voice.getCenterY());
            d.touch("up", end.getCenterX(), end.getCenterY());
            check(d.takeVoiceToggle() == null && !d.takeEndRoute(), at + "sliding from the toggle onto End route is neither");
            d.touch("down", end.getCenterX(), end.getCenterY());
            d.touch("up", voice.getCenterX(), voice.getCenterY());
            check(d.takeVoiceToggle() == null && !d.takeEndRoute(), at + "nor from End route onto the toggle");
            d.touch("down", voice.getCenterX(), voice.getCenterY());
            d.touch("cancel", voice.getCenterX(), voice.getCenterY());
            check(d.takeVoiceToggle() == null && !d.nextFrame(NOON).getTrip().getVoicePressed(), at + "nor a cancel");

            tap(d, voice.getCenterX(), voice.getCenterY());
            tap(d, voice.getCenterX(), voice.getCenterY());
            tap(d, voice.getCenterX(), voice.getCenterY());
            check(Boolean.FALSE.equals(d.takeVoiceToggle()), at + "three presses between reads hand over the state they left");
            tap(d, end.getCenterX(), end.getCenterY());
            check(d.takeEndRoute() && d.takeVoiceToggle() == null, at + "End route still ends the route and leaves the toggle alone");
            check(d.report().contains("dashboard_voice_toggle_presses=5 dashboard_voice_guidance=off\n"),
                at + "presses and the final state counted: " + d.report());
            d.upcomingTurn(null);
            check(d.nextFrame(NOON).getTrip() == null, at + "the route gone, so is the panel");
            tap(d, voice.getCenterX(), voice.getCenterY());
            check(d.takeVoiceToggle() == null, at + "and the toggle with it");
        }
    }

    private static void theVoiceToggleStartsAsSavedAndWarns() {
        DashboardLayout layout = new DashboardLayout(0, 0, 1920, 1080, true);
        Dashboard muted = new Dashboard(layout, "t", ZoneId.of("UTC"), SpeedUnit.MPH, MapOrientation.NORTH_UP, o -> kotlin.Unit.INSTANCE,
            new DashboardChoices(MarkerColour.BLUE, DayNightMode.AUTO, false, false, null, false));
        muted.upcomingTurn(guiding("Main Street", 4023.0, 600));
        check(!muted.nextFrame(NOON).getTrip().getVoiceOn(), "a route started muted stays muted (decision 6)");
        check(muted.report().contains("dashboard_voice_guidance=off\n"), "and the report says so");

        Dashboard d = dashboard(1920, 1080);
        d.upcomingTurn(guiding("Main Street", 4023.0, 600));
        check(d.nextFrame(NOON).getTrip().getVoiceWarning(), "until told otherwise, speech is not assumed: the warning shows");
        d.speechAvailable(true);
        check(!d.nextFrame(NOON).getTrip().getVoiceWarning(), "an engine and Bluetooth: no warning");
        Box voice = layout.getVoiceToggle();
        tap(d, voice.getCenterX(), voice.getCenterY());
        d.speechAvailable(false);
        TripPanel off = d.nextFrame(NOON).getTrip();
        check(off.getVoiceWarning() && !off.getVoiceOn(), "the warning shows whichever way it is set");
        check(d.nextFrame(NOON).getTrip().equals(off), "an unchanged panel compares equal, so the chrome is not repainted");

        // P8.3: the phone's switch, shown on the panel without being a press.
        d.takeVoiceToggle();
        d.voiceGuidance(true);
        check(d.nextFrame(NOON).getTrip().getVoiceOn(), "the phone turning guidance on lights the toggle");
        check(d.takeVoiceToggle() == null, "and hands nothing back: the phone saved it already");
        check(d.report().contains("dashboard_voice_toggle_presses=1 dashboard_voice_guidance=on\n"),
            "not counted as a press, and the final state reported: " + d.report());
        d.voiceGuidance(false);
        check(!d.nextFrame(NOON).getTrip().getVoiceOn(), "and off slashes it");
        tap(d, voice.getCenterX(), voice.getCenterY());
        check(Boolean.TRUE.equals(d.takeVoiceToggle()), "a press after it flips from what the phone set");
    }

    // --- helpers ---------------------------------------------------------------------

    private static Dashboard dashboard(int width, int height) {
        return new Dashboard(new DashboardLayout(0, 0, width, height, true), "t", ZoneId.of("UTC"));
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
