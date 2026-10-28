// P9.3, P9.4 and P9.6 on a desktop JVM (docs/navigation.md, "Live traffic (HERE)"): what live traffic
// puts on the projected dashboard. The route line's traffic pieces (which runs are coloured,
// in what, merged and capped, cut at the vehicle, and never on a route they were not matched
// to), "Traffic (c) HERE" and where it sits, the trip panel's "+N min traffic", the divided
// turn card in both of its layouts with Take and Keep, the map's fit to both routes, and the
// return to following. TrafficLines, Dashboard, DashboardLayout and MapControl are pure
// Kotlin, so these are the classes the app draws from; the VTM layers themselves, the
// stipple of a closure and the real text widths are for the lab's renders. Text widths are
// estimated at DashboardLayout.EM_PER_CHARACTER, as DashboardTripTest does.
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import com.jw6ventures.headlink.Box;
import com.jw6ventures.headlink.Congestion;
import com.jw6ventures.headlink.Dashboard;
import com.jw6ventures.headlink.DashboardLayout;
import com.jw6ventures.headlink.DashboardPalette;
import com.jw6ventures.headlink.LaterStep;
import com.jw6ventures.headlink.Maneuvers;
import com.jw6ventures.headlink.MapCamera;
import com.jw6ventures.headlink.MapControl;
import com.jw6ventures.headlink.MapEngine;
import com.jw6ventures.headlink.MapOrientation;
import com.jw6ventures.headlink.OfferAnswer;
import com.jw6ventures.headlink.OfferCard;
import com.jw6ventures.headlink.OfferLayout;
import com.jw6ventures.headlink.PhoneSnapshot;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteTraffic;
import com.jw6ventures.headlink.TouchPoint;
import com.jw6ventures.headlink.TrafficDisplay;
import com.jw6ventures.headlink.TrafficIncident;
import com.jw6ventures.headlink.TrafficLines;
import com.jw6ventures.headlink.TrafficOffer;
import com.jw6ventures.headlink.TrafficPiece;
import com.jw6ventures.headlink.TrafficRun;
import com.jw6ventures.headlink.TripPanel;
import com.jw6ventures.headlink.UpcomingTurn;

public final class DashboardTrafficTest {
    private static int passed;

    private static final long NOON = Instant.parse("2026-09-28T12:00:00Z").toEpochMilli();

    /** Metres in one step of the test route: 0.001 degrees of latitude. */
    private static final double STEP_M = 111.19492664455873;

    /** Night and day map grounds, from dashboard-dark.xml and dashboard-light.xml. */
    private static final int NIGHT_GROUND = 0xFF151A2B;
    private static final int DAY_GROUND = 0xFFE9E7E1;

    private static final String MINUS = "\u2212";

    public static void main(String[] args) {
        onlyTheSlowQueuingAndClosedRunsAreColoured();
        adjacentRunsMergeAndThePiecesAreCapped();
        theCutAtTheVehicleMovesInSteps();
        trafficForAnotherRouteIsNeverDrawn();
        theColoursReadInBothPalettes();
        theAttributionIsOwedByTheRule();
        theDelayLabelRounds();
        for (DashboardLayout layout : everyDisplay()) {
            theAttributionSitsClearOfEverything(layout);
            theDelayLabelFitsTheTripPanel(layout);
            theDividedCardFits(layout);
        }
        sideBySideFitsTheTrucksPanesBest();
        theFitFramesEveryPoint();
        theFitHoldsItsRotation();
        theFitIsClampedAndNeverZoomsIn();
        theFitEndsBackOnTheVehicle();
        anOfferDividesTheCardAndFitsTheMap();
        takeAndKeepAnswerTheOfferAndRefollow();
        aWithdrawnOfferRefollows();
        aDriversOwnPanIsLeftAlone();
        aPressOnTheCardIsNotAPanAndNeedsTheSameTarget();
        theReportCountsAndNamesNothing();
        System.out.println("jni-host-test: ok " + passed + " dashboard traffic, attribution and offer checks");
    }

    /**
     * 1080p, Ford's 726x480 content rectangle and the 800x480 minimum, each with the map on
     * both sides, in both of the offer's layouts.
     */
    private static List<DashboardLayout> everyDisplay() {
        List<DashboardLayout> all = new ArrayList<>();
        for (OfferLayout offer : OfferLayout.values()) {
            for (boolean left : new boolean[] {true, false}) {
                all.add(new DashboardLayout(0, 0, 1920, 1080, left, offer));
                all.add(new DashboardLayout(74, 0, 726, 480, left, offer));
                all.add(new DashboardLayout(0, 0, 800, 480, left, offer));
            }
        }
        return all;
    }

    private static String size(DashboardLayout l) {
        return l.getWidth() + "x" + l.getHeight() + (l.getMapOnLeft() ? "L" : "R") + " " + l.getOfferLayout().getKey() + ": ";
    }

    // --- the route's traffic pieces (P9.3) --------------------------------------------

    /** A route north along -97.0 from 30.0, [count] points 0.001 degrees apart. */
    private static Route straight(int count) {
        double[] lats = new double[count];
        double[] lons = new double[count];
        for (int i = 0; i < count; i++) {
            lats[i] = 30.0 + i * 0.001;
            lons[i] = -97.0;
        }
        return new Route(lats, lons, (int) Math.round((count - 1) * STEP_M), (count - 1) * 8, new int[0], new int[0]);
    }

    private static TrafficRun run(double fromM, double toM, Congestion congestion) {
        return new TrafficRun((int) (fromM / STEP_M), (int) Math.ceil(toM / STEP_M), fromM, toM, congestion,
            congestion == Congestion.NONE ? null : 5.0, 0.0, "I-35E");
    }

    private static RouteTraffic traffic(Route route, TrafficRun... runs) {
        return new RouteTraffic(route, List.of(runs), List.<TrafficIncident>of());
    }

    /** NONE, FREE, SLOW twice, QUEUING, a gap, CLOSED: the test route's usual traffic. */
    private static RouteTraffic usual(Route route) {
        return traffic(route,
            run(0, 200, Congestion.NONE), run(200, 400, Congestion.FREE), run(400, 600, Congestion.SLOW),
            run(600, 800, Congestion.SLOW), run(800, 1000, Congestion.QUEUING), run(1000, 1200, Congestion.FREE),
            run(1200, 1400, Congestion.CLOSED));
    }

    private static void onlyTheSlowQueuingAndClosedRunsAreColoured() {
        check(TrafficLines.colour(Congestion.NONE) == null, "no data is the route's own colour");
        check(TrafficLines.colour(Congestion.FREE) == null, "free-flowing is the route's own colour too, never green");
        check(TrafficLines.colour(Congestion.SLOW) == TrafficLines.SLOW_COLOUR, "slow is amber");
        check(TrafficLines.colour(Congestion.QUEUING) == TrafficLines.QUEUING_COLOUR, "queuing is red");
        check(TrafficLines.colour(Congestion.CLOSED) == TrafficLines.CLOSED_COLOUR, "closed is dark red");
        for (Congestion c : Congestion.values()) {
            check(TrafficLines.dashed(c) == (c == Congestion.CLOSED), c + (c == Congestion.CLOSED ? " is dashed" : " is solid"));
        }

        Route route = straight(30);
        double[] along = TrafficLines.cumulative(route);
        check(Math.abs(along[1] - STEP_M) < 1e-6 && Math.abs(along[29] - 29 * STEP_M) < 1e-6, "metres along, as RouteFollower measures them");
        List<TrafficPiece> pieces = TrafficLines.pieces(usual(route), along, 0.0).getFirst();
        check(pieces.size() == 3, "slow, queuing and closed: three pieces, not " + pieces.size());
        check(pieces.get(0).getCongestion() == Congestion.SLOW && near(pieces.get(0).getFromM(), 400) && near(pieces.get(0).getToM(), 800),
            "the two slow runs are one piece, 400 to 800 m");
        check(pieces.get(1).getCongestion() == Congestion.QUEUING && near(pieces.get(1).getFromM(), 800) && near(pieces.get(1).getToM(), 1000),
            "then queuing, 800 to 1000 m");
        check(pieces.get(2).getCongestion() == Congestion.CLOSED && near(pieces.get(2).getFromM(), 1200),
            "and the closure after the free-flowing gap, which stays the route's colour");
        // The geometry is the route's own, from end to end of the piece.
        TrafficPiece slow = pieces.get(0);
        double[] lats = slow.getLatitudes();
        check(Math.abs(lats[0] - (30.0 + 400 / STEP_M * 0.001)) < 1e-9, "the piece starts 400 m along, interpolated");
        check(Math.abs(lats[lats.length - 1] - (30.0 + 800 / STEP_M * 0.001)) < 1e-9, "and ends 800 m along");
        check(lats.length == 2 + (7 - 4 + 1) - 0, "every route point in between is in it: " + lats.length);
        for (int i = 1; i < lats.length; i++) check(lats[i] > lats[i - 1], "in route order, point " + i);
        for (double lon : slow.getLongitudes()) check(lon == -97.0, "on the route's own line");

        // Cut at the vehicle: nothing behind it is coloured.
        pieces = TrafficLines.pieces(usual(route), along, 500.0).getFirst();
        check(pieces.size() == 3 && near(pieces.get(0).getFromM(), 500), "cut at 500 m, the slow piece starts there");
        check(Math.abs(pieces.get(0).getLatitudes()[0] - (30.0 + 500 / STEP_M * 0.001)) < 1e-9, "at the vehicle's place on the line");
        pieces = TrafficLines.pieces(usual(route), along, 900.0).getFirst();
        check(pieces.size() == 2 && pieces.get(0).getCongestion() == Congestion.QUEUING && near(pieces.get(0).getFromM(), 900),
            "past the slow stretch it is gone, and the queue starts at the vehicle");
        check(TrafficLines.pieces(usual(route), along, 1500.0).getFirst().isEmpty(), "past everything, nothing is coloured");
    }

    private static void adjacentRunsMergeAndThePiecesAreCapped() {
        Route route = straight(80);
        double[] along = TrafficLines.cumulative(route);
        // Runs of one level that meet, or nearly (the matcher's metres are not exact), merge.
        kotlin.Pair<List<TrafficPiece>, Integer> merged = TrafficLines.pieces(traffic(route,
            run(100, 200, Congestion.QUEUING), run(203, 300, Congestion.QUEUING), run(300, 400, Congestion.QUEUING)), along, 0.0);
        check(merged.getFirst().size() == 1 && near(merged.getFirst().get(0).getToM(), 400), "three queuing runs a few metres apart are one piece");
        List<TrafficPiece> apart = TrafficLines.pieces(traffic(route,
            run(100, 200, Congestion.QUEUING), run(260, 300, Congestion.QUEUING)), along, 0.0).getFirst();
        check(apart.size() == 2, "runs with a real gap between them stay apart");
        // Twenty alternating runs: at most MAX_PIECES are drawn, the nearest.
        List<TrafficRun> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add(run(100 + i * 300, 300 + i * 300, i % 2 == 0 ? Congestion.SLOW : Congestion.QUEUING));
        kotlin.Pair<List<TrafficPiece>, Integer> capped = TrafficLines.pieces(new RouteTraffic(route, many, List.<TrafficIncident>of()), along, 0.0);
        check(capped.getFirst().size() == TrafficLines.MAX_PIECES, "capped at " + TrafficLines.MAX_PIECES + ": " + capped.getFirst().size());
        check(capped.getSecond() == 20 - TrafficLines.MAX_PIECES, "and the rest counted as dropped: " + capped.getSecond());
        check(near(capped.getFirst().get(0).getFromM(), 100), "the nearest are the ones kept");
        check(TrafficLines.MAX_PIECES <= 8, "at most 16 layers for traffic");
        // A run too short to see, and one out of order, are handled.
        List<TrafficPiece> tiny = TrafficLines.pieces(traffic(route, run(500, 500.5, Congestion.SLOW), run(900, 1000, Congestion.CLOSED),
            run(100, 150, Congestion.SLOW)), along, 0.0).getFirst();
        check(tiny.size() == 2 && near(tiny.get(0).getFromM(), 100) && tiny.get(1).getCongestion() == Congestion.CLOSED,
            "half a metre is not a piece, and pieces come in route order");
    }

    private static void theCutAtTheVehicleMovesInSteps() {
        Route route = straight(30);
        RouteTraffic t = usual(route);
        TrafficLines lines = new TrafficLines();
        List<TrafficPiece> first = lines.update(t, route, null, null);
        check(first != null && first.size() == 3, "new traffic is drawn at once, whole with no fix");
        check(lines.update(t, route, null, null) == null, "the same traffic again changes nothing");
        // Towards the jam: nothing drawn changes.
        check(lines.update(t, route, lat(100), -97.0) == null, "100 m along, the pieces ahead are as they were");
        check(lines.update(t, route, lat(350), -97.0) == null, "350 m along, still short of the slow stretch");
        // Into it: cut at the vehicle, then again only every CUT_STEP_M.
        List<TrafficPiece> in = lines.update(t, route, lat(450), -97.0);
        check(in != null && Math.abs(in.get(0).getFromM() - 450) < 1, "450 m along, the slow piece is cut at the vehicle: " + (in == null ? "null" : in.get(0).getFromM()));
        check(lines.update(t, route, lat(470), -97.0) == null, "20 m later, nothing is rebuilt");
        check(lines.update(t, route, lat(470), -97.0 + 0.0001) == null, "nor for a fix a few metres to the side");
        List<TrafficPiece> on = lines.update(t, route, lat(505), -97.0);
        check(on != null && Math.abs(on.get(0).getFromM() - 505) < 1, "55 m later it is: " + (on == null ? "null" : on.get(0).getFromM()));
        check(lines.getRebuilds() == 3, "three rebuilds so far: " + lines.getRebuilds());
        // A fix far off the route says nothing about where on it the vehicle is.
        check(lines.update(t, route, 30.01, -96.9) == null, "a fix 9 km off the route moves nothing");
        // Back to the start of the route (a new start on it) brings the passed pieces back.
        List<TrafficPiece> back = lines.update(t, route, lat(0), -97.0);
        check(back != null && back.size() == 3 && near(back.get(0).getFromM(), 400), "back at the start, the whole slow piece is back");
        // New traffic for the same route is drawn at once, from where the vehicle is.
        List<TrafficPiece> next = lines.update(traffic(route, run(0, 1000, Congestion.QUEUING)), route, lat(0), -97.0);
        check(next != null && next.size() == 1 && next.get(0).getCongestion() == Congestion.QUEUING, "new traffic replaces the old at once");
        check(TrafficLines.CUT_STEP_M == 50.0, "the cut moves in 50 m steps");
    }

    private static double lat(double metres) { return 30.0 + metres / STEP_M * 0.001; }

    private static void trafficForAnotherRouteIsNeverDrawn() {
        Route route = straight(30);
        Route same = straight(30);
        TrafficLines lines = new TrafficLines();
        check(lines.update(usual(route), same, null, null) == null, "traffic matched to another route draws nothing, even over the same roads");
        check(lines.getStale() == 1, "and is counted as stale");
        check(lines.update(usual(route), null, null, null) == null && lines.getPieces().isEmpty(), "nor with no route on the map");
        check(lines.update(usual(route), route, null, null).size() == 3, "on its own route it draws");
        List<TrafficPiece> gone = lines.update(usual(route), same, null, null);
        check(gone != null && gone.isEmpty(), "a new route on the map takes the old route's pieces off at once");
        check(lines.update(null, same, null, null) == null, "and no traffic is nothing to change");
    }

    private static void theColoursReadInBothPalettes() {
        int[] traffic = {TrafficLines.SLOW_COLOUR, TrafficLines.QUEUING_COLOUR, TrafficLines.CLOSED_COLOUR};
        for (int colour : traffic) {
            check(distance(colour, MapEngine.ROUTE_COLOR) > 150, hex(colour) + " is not the route's cyan");
            check(distance(colour, MapEngine.ALTERNATIVE_COLOR) > 150, hex(colour) + " is not the alternatives' grey");
        }
        check(distance(TrafficLines.SLOW_COLOUR, TrafficLines.QUEUING_COLOUR) > 100, "amber and red are told apart");
        check(distance(TrafficLines.QUEUING_COLOUR, TrafficLines.CLOSED_COLOUR) > 100, "red and dark red are told apart");
        // Night: the lines themselves on the navy ground. Day: the dark edge under every piece
        // on the light ground, as the route's own casing does for its cyan.
        for (int colour : new int[] {TrafficLines.SLOW_COLOUR, TrafficLines.QUEUING_COLOUR, TrafficLines.CLOSED_GAP_COLOUR}) {
            double night = DashboardPalette.Companion.contrast(colour, NIGHT_GROUND);
            check(night >= 3.0, hex(colour) + " reads on the night ground: " + night);
        }
        check(DashboardPalette.Companion.contrast(TrafficLines.EDGE_COLOUR, DAY_GROUND) >= 3.0, "the edge reads on the day ground");
        for (int colour : new int[] {TrafficLines.SLOW_COLOUR, TrafficLines.QUEUING_COLOUR, TrafficLines.CLOSED_GAP_COLOUR}) {
            check(DashboardPalette.Companion.contrast(colour, TrafficLines.EDGE_COLOUR) >= 3.0, hex(colour) + " stands off its edge");
        }
        check(DashboardPalette.Companion.contrast(TrafficLines.CLOSED_COLOUR, TrafficLines.CLOSED_GAP_COLOUR) >= 3.0,
            "a closure's dashes show against their gaps");
        check(TrafficLines.CLOSED_DASH >= 8, "dashes long enough to see as dashes");
    }

    // --- the attribution (P9.3, HERE's terms, clause 6.4 a) ----------------------------------

    private static void theAttributionIsOwedByTheRule() {
        Route route = straight(30);
        check(TrafficDisplay.ATTRIBUTION.equals("Traffic \u00a9 HERE"), "the required words");
        check(!TrafficDisplay.Companion.getEMPTY().getAttributed(), "nothing to credit with no traffic");
        check(!new TrafficDisplay(traffic(route, run(0, 500, Congestion.NONE), run(500, 900, Congestion.FREE)), 0, null, false).getAttributed(),
            "free-flowing and no data draw no traffic colour, so nothing is owed");
        check(new TrafficDisplay(traffic(route, run(0, 500, Congestion.SLOW)), 0, null, false).getAttributed(), "a traffic colour is");
        check(new TrafficDisplay(null, 120, null, false).getAttributed(), "a delay in the time is");
        check(new TrafficDisplay(null, 0, offer(1, route), false).getAttributed(), "an offer is");
        check(new TrafficDisplay(null, 0, null, true).getAttributed(), "a route HERE shaped is");

        Dashboard d = dashboard(new DashboardLayout(74, 0, 726, 480, true));
        check(!d.nextFrame(NOON).getTrafficAttributed(), "no credit before any traffic");
        d.traffic(new TrafficDisplay(null, 120, null, false));
        check(d.nextFrame(NOON).getTrafficAttributed(), "the dashboard draws it when owed");
        d.traffic(TrafficDisplay.Companion.getEMPTY());
        check(!d.nextFrame(NOON).getTrafficAttributed(), "and stops when it is not");
    }

    private static void theAttributionSitsClearOfEverything(DashboardLayout l) {
        String at = size(l);
        Box box = l.getTrafficAttribution();
        Box map = l.getMap();
        int text = l.getAttributionTextSize();
        check(inside(box, map), at + "the HERE credit is in the map pane");
        check(box.getWidth() >= DashboardLayout.EM_PER_CHARACTER * text * DashboardLayout.TRAFFIC_ATTRIBUTION.length(),
            at + "wide enough for its words: " + box.getWidth());
        check(box.getHeight() >= text, at + "tall enough for its size");
        check(box.getBottom() <= l.getAttributionTop(), at + "above the ODbL credit");
        check(box.getRight() < l.zoomBubble(1).getX(), at + "left of the zoom stack");
        for (int i = 0; i < DashboardLayout.Companion.getMAP_ACTIONS().size(); i++) {
            check(!overlaps(box, l.mapBubble(i)), at + "clear of map bubble " + i);
        }
        for (int i = 0; i < DashboardLayout.Companion.getZOOM_ACTIONS().size(); i++) {
            check(!overlaps(box, l.zoomBubble(i)), at + "clear of zoom bubble " + i);
        }
        check(!overlaps(box, l.getTurnCard()) && !overlaps(box, l.getOfferCard()), at + "and of the turn card, whole or divided");
        check(text >= 10, at + "in the ODbL credit's size, at least 10 px: " + text);
    }

    // --- the arrival time's label (P9.4) ----------------------------------------------

    private static void theDelayLabelRounds() {
        check(Dashboard.trafficDelayLabel(0) == null, "no delay, no label");
        check(Dashboard.trafficDelayLabel(29) == null, "under half a minute rounds to none");
        check("+1 min traffic".equals(Dashboard.trafficDelayLabel(30)), "half a minute rounds up to one");
        check("+1 min traffic".equals(Dashboard.trafficDelayLabel(89)), "89 s is one minute");
        check("+2 min traffic".equals(Dashboard.trafficDelayLabel(90)), "90 s is two");
        check("+7 min traffic".equals(Dashboard.trafficDelayLabel(420)), "seven minutes: " + Dashboard.trafficDelayLabel(420));
        check("+12 min traffic".length() == DashboardLayout.TRAFFIC_LABEL_CHARACTERS, "sized for the two-digit label");

        Dashboard d = dashboard(new DashboardLayout(0, 0, 1920, 1080, true));
        d.upcomingTurn(guiding(600));
        check(d.nextFrame(NOON).getTrip().getTraffic() == null, "no traffic, no label");
        d.traffic(new TrafficDisplay(null, 420, null, false));
        TripPanel trip = d.nextFrame(NOON).getTrip();
        check("+7 min traffic".equals(trip.getTraffic()), "the trip panel labels the delay: " + trip.getTraffic());
        check("12:10".equals(trip.getArrival()), "and leaves the time alone: the loop has already added the delay to it");
        d.traffic(new TrafficDisplay(null, 20, null, false));
        check(d.nextFrame(NOON).getTrip().getTraffic() == null, "a delay that rounds to nothing is not labelled");
        d.traffic(new TrafficDisplay(null, 420, null, false));
        d.upcomingTurn(new UpcomingTurn(UpcomingTurn.REROUTING, null));
        check(d.nextFrame(NOON).getTrip().getTraffic() == null, "re-routing has no figures, and no label");
    }

    private static void theDelayLabelFitsTheTripPanel(DashboardLayout l) {
        String at = size(l);
        Box summary = l.getTripSummary();
        float h = summary.getHeight();
        float size = l.getTripTrafficTextSize();
        float label = DashboardLayout.TRAFFIC_LABEL_CHARACTERS * DashboardLayout.EM_PER_CHARACTER * size;
        float remaining = 6 * DashboardLayout.EM_PER_CHARACTER * 0.3f * h;
        check(label + l.getMargin() + remaining <= summary.getWidth() + 0.5f,
            at + "\"+12 min traffic\" fits beside \"12 min\": " + (label + l.getMargin() + remaining) + " of " + summary.getWidth());
        check(size <= 0.22f * h + 0.01f, at + "no bigger than the caption it replaces");
        check(size >= 9f, at + "and at least 9 px: " + size);
        // It is on the caption line: End route, the voice toggle and the later maneuvers
        // keep the boxes they had.
        check(summary.getBottom() <= l.getEndRoute().getY() - l.getMargin(), at + "End route is where it was");
        check(l.getVoiceToggle().getY() == l.getEndRoute().getY(), at + "and so is the voice toggle");
    }

    // --- the divided card (P9.6) ------------------------------------------------------

    private static void theDividedCardFits(DashboardLayout l) {
        String at = size(l);
        Box card = l.getOfferCard();
        Box turn = l.getOfferTurn();
        Box part = l.getOfferPart();
        Box icon = l.getOfferTurnIcon();
        Box text = l.getOfferText();
        Box map = l.getMap();
        int m = l.getMargin();
        int bubble = l.getBubbleSize();
        check(card.getX() == l.getTurnCard().getX() && card.getY() == l.getTurnCard().getY()
            && card.getWidth() == l.getTurnCard().getWidth(), at + "the divided card is where the turn card is, as wide");
        check(card.getHeight() >= l.getTurnCard().getHeight(), at + "and at least as tall");
        check(inside(card, map), at + "inside the pane");
        check(inside(turn, card) && inside(part, card) && !overlaps(turn, part), at + "the turn and the offer each have a part");
        check(inside(icon, turn) && icon.getWidth() == icon.getHeight() && icon.getWidth() >= 40,
            at + "the turn icon stays, square and at least 40 px: " + icon.getWidth());
        if (l.getOfferLayout() == OfferLayout.STACKED) {
            check(turn.equals(l.getTurnCard()) && icon.equals(l.getTurnIcon()) && l.getOfferTurnStreet(),
                at + "stacked, the turn keeps the whole card as it is, street and all");
        } else {
            check(!l.getOfferTurnStreet(), at + "side by side, the street is dropped");
            // The distance under the icon: "0.3 mi" in bold, brought down to fit the part.
            float below = turn.getBottom() - Math.max(1, m / 2) - icon.getBottom();
            float room = turn.getWidth() - 2 * Math.max(1, m / 2);
            float sizeFit = Math.min(below * 0.75f, room / (6 * 0.65f));
            check(sizeFit >= 18, at + "the distance under the icon is at least 18 px: " + sizeFit);
        }
        List<String> actions = DashboardLayout.Companion.getOFFER_ACTIONS();
        check(actions.equals(List.of("take", "keep")), "Take and Keep, left to right");
        Box take = l.offerButton(0);
        Box keep = l.offerButton(1);
        for (Box button : new Box[] {take, keep}) {
            check(inside(button, part), at + "the button is in the offer's part");
            check(button.getHeight() >= bubble && button.getWidth() >= bubble * 3 / 2,
                at + "a bubble tall and half as wide again: " + button.getWidth() + "x" + button.getHeight() + " against " + bubble);
            check(!overlaps(button, icon) && !overlaps(button, turn), at + "clear of the turn and its icon");
            check(!overlaps(button, text), at + "clear of the offer's words");
        }
        check(keep.getX() - take.getRight() >= m, at + "Take and Keep are a margin apart");
        check(l.hitOfferButton(take.getCenterX(), take.getCenterY()).equals("take")
            && l.hitOfferButton(keep.getCenterX(), keep.getCenterY()).equals("keep"), at + "each centre hits its own");
        check("take".equals(l.hitOfferButton(take.getX(), take.getY())) && "keep".equals(l.hitOfferButton(keep.getRight() - 1, keep.getBottom() - 1)),
            at + "and so do their corners");
        check(l.hitOfferButton((take.getRight() + keep.getX()) / 2, take.getCenterY()) == null, at + "the gap between them misses");
        check(l.hitOfferButton(icon.getCenterX(), icon.getCenterY()) == null && l.hitOfferButton(text.getCenterX(), text.getCenterY()) == null,
            at + "the icon and the words are not targets");
        // "Faster: -12 min" and "Queue on I-35E" fit their box at the drawing's smallest size.
        float th = text.getHeight();
        check(inside(text, part), at + "the words are in the offer's part");
        check(15 * DashboardLayout.EM_PER_CHARACTER * 0.28f * th <= text.getWidth(),
            at + "\"Faster: -12 min\" fits at its smallest: " + 15 * DashboardLayout.EM_PER_CHARACTER * 0.28f * th + " of " + text.getWidth());
        check(0.28f * th >= 11f, at + "the reason is at least 11 px: " + 0.28f * th);
        check(0.4f * th + 0.28f * th <= th, at + "both lines fit the words' height");
        // Nothing floating over the map is under the card, and the view it frames is below it.
        for (int i = 0; i < DashboardLayout.Companion.getMAP_ACTIONS().size(); i++) {
            check(!overlaps(card, l.mapBubble(i)), at + "the card clears map bubble " + i);
        }
        for (int i = 0; i < DashboardLayout.Companion.getZOOM_ACTIONS().size(); i++) {
            check(!overlaps(card, l.zoomBubble(i)), at + "the card clears zoom bubble " + i);
        }
        Box view = l.getOfferView();
        check(inside(view, map) && view.getY() >= card.getBottom() + m, at + "the fitted view is under the card, a margin clear");
        check(view.getHeight() * 2 >= map.getHeight(), at + "and at least half the pane tall: " + view.getHeight() + " of " + map.getHeight());
    }

    /**
     * Why side by side is the default (it was left to the lab's renders): at the truck's
     * 726x480 and the 800x480 minimum it leaves more of the pane for the two routes and gives
     * Take and Keep more area. Stacked keeps the turn exactly as it is, street and all.
     */
    private static void sideBySideFitsTheTrucksPanesBest() {
        for (int[] d : new int[][] {{74, 726, 480}, {0, 800, 480}}) {
            DashboardLayout side = new DashboardLayout(d[0], 0, d[1], d[2], true, OfferLayout.SIDE_BY_SIDE);
            DashboardLayout stacked = new DashboardLayout(d[0], 0, d[1], d[2], true, OfferLayout.STACKED);
            String at = d[1] + "x" + d[2] + ": ";
            check(side.getOfferView().getHeight() > stacked.getOfferView().getHeight(),
                at + "side by side leaves more map under the card: " + side.getOfferView().getHeight() + " against " + stacked.getOfferView().getHeight());
            Box a = side.offerButton(0);
            Box b = stacked.offerButton(0);
            check(a.getWidth() * a.getHeight() > b.getWidth() * b.getHeight(),
                at + "and bigger targets: " + a.getWidth() + "x" + a.getHeight() + " against " + b.getWidth() + "x" + b.getHeight());
            check(stacked.getOfferTurnStreet() && !side.getOfferTurnStreet(), at + "at the cost of the street, which stacked keeps");
        }
        check(new DashboardLayout(0, 0, 800, 480, true).getOfferLayout() == DashboardLayout.Companion.getDefaultOfferLayout(),
            "a layout takes the default unless asked");
    }

    // --- the fit to both routes (P9.6) ------------------------------------------------

    /** The followed route and a way round it, east of the jam. */
    private static Route detour() {
        return new Route(new double[] {30.002, 30.002, 30.020, 30.028}, new double[] {-97.0, -96.99, -96.99, -97.0}, 4000, 300,
            new int[0], new int[0]);
    }

    private static void theFitFramesEveryPoint() {
        for (DashboardLayout l : everyDisplay()) {
            String at = size(l);
            MapControl control = new MapControl(MapOrientation.NORTH_UP);
            kotlin.Pair<Double, Double> vehicle = new kotlin.Pair<>(30.0005, -97.0);
            control.camera(vehicle, null, 0);
            double before = control.getScale();
            Route route = straight(30);
            Route detour = detour();
            check(control.fit(List.of(route.getLatitudes(), detour.getLatitudes()), List.of(route.getLongitudes(), detour.getLongitudes()),
                l.getMap(), l.getOfferView()), at + "the fit is made");
            check(!control.getFollowing() && control.getFitted(), at + "the map leaves following for it");
            control.camera(vehicle, null, 0);
            check(control.getScale() <= before && control.getScale() >= MapControl.MIN_SCALE, at + "an overview, within the pinch's range");
            int[] extent = everyPointInside(control, l, route, detour, at);
            Box view = l.getOfferView();
            check(extent[0] >= view.getWidth() - 3 || extent[1] >= view.getHeight() - 3,
                at + "and the fit is tight on one side: " + extent[0] + "x" + extent[1] + " in " + view.getWidth() + "x" + view.getHeight());
        }
    }

    /** Every point of both routes is drawn inside the offer's view; returns their pixel extent. */
    private static int[] everyPointInside(MapControl control, DashboardLayout l, Route a, Route b, String at) {
        Box view = l.getOfferView();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Route route : new Route[] {a, b}) {
            for (int i = 0; i < route.getPoints(); i++) {
                kotlin.Pair<Integer, Integer> px = control.marker(l.getMap(), new kotlin.Pair<>(route.getLatitudes()[i], route.getLongitudes()[i]));
                check(px != null, at + "point " + i + " is on the pane");
                int x = px.getFirst();
                int y = px.getSecond();
                check(x >= view.getX() - 1 && x <= view.getRight() + 1 && y >= view.getY() - 1 && y <= view.getBottom() + 1,
                    at + "point " + i + " is inside the view under the card: " + x + "," + y + " in " + view);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        return new int[] {maxX - minX, maxY - minY};
    }

    private static void theFitHoldsItsRotation() {
        DashboardLayout l = new DashboardLayout(74, 0, 726, 480, true);
        MapControl control = new MapControl(MapOrientation.HEADING_UP);
        kotlin.Pair<Double, Double> vehicle = new kotlin.Pair<>(30.0005, -97.0);
        control.camera(vehicle, 30.0, 0);
        check(Math.abs(control.getRotation() - 30f) < 1e-3, "direction-of-travel-up at 30 degrees");
        Route route = straight(30);
        Route detour = detour();
        control.fit(List.of(route.getLatitudes(), detour.getLatitudes()), List.of(route.getLongitudes(), detour.getLongitudes()),
            l.getMap(), l.getOfferView());
        MapCamera camera = control.camera(vehicle, 75.0, 0);
        check(Math.abs(camera.getRotation() - 30f) < 1e-3, "a new heading does not turn the fitted view: " + camera.getRotation());
        everyPointInside(control, l, route, detour, "rotated: ");
        control.unfit();
        check(Math.abs(control.camera(vehicle, 75.0, 0).getRotation() - 75f) < 1e-3, "back on the vehicle, the heading turns it again");
    }

    private static void theFitIsClampedAndNeverZoomsIn() {
        DashboardLayout l = new DashboardLayout(0, 0, 800, 480, true);
        MapControl control = new MapControl(MapOrientation.NORTH_UP);
        kotlin.Pair<Double, Double> vehicle = new kotlin.Pair<>(30.0, -97.0);
        control.camera(vehicle, null, 0);
        // Texas to Alaska is wider than the pane at the furthest the pinch goes out.
        control.fit(List.of(new double[] {30.0, 61.0}), List.of(new double[] {-97.0, -150.0}), l.getMap(), l.getOfferView());
        check(control.getScale() == MapControl.MIN_SCALE, "clamped to the pinch's range: " + control.getScale());
        control.unfit();
        check(control.getScale() == MapControl.DEFAULT_SCALE, "and the zoom given back");
        control.camera(vehicle, null, 0);
        // Ten metres apart: framing them would zoom in past the follow view, which a fit never does.
        control.fit(List.of(new double[] {30.0, 30.0001}), List.of(new double[] {-97.0, -97.0}), l.getMap(), l.getOfferView());
        check(control.getScale() == MapControl.DEFAULT_SCALE, "a fit never zooms in: " + control.getScale());
        check(!control.fit(List.of(new double[0]), List.of(new double[0]), l.getMap(), l.getOfferView()), "nothing to frame is no fit");
    }

    private static void theFitEndsBackOnTheVehicle() {
        DashboardLayout l = new DashboardLayout(74, 0, 726, 480, true);
        Box pane = l.getMap();
        kotlin.Pair<Double, Double> vehicle = new kotlin.Pair<>(30.0005, -97.0);
        Route route = straight(30);
        Route detour = detour();
        List<double[]> lats = List.of(route.getLatitudes(), detour.getLatitudes());
        List<double[]> lons = List.of(route.getLongitudes(), detour.getLongitudes());

        MapControl control = new MapControl(MapOrientation.NORTH_UP);
        control.camera(vehicle, null, 0);
        control.fit(lats, lons, pane, l.getOfferView());
        check(control.unfit() && control.getFollowing() && control.getScale() == MapControl.DEFAULT_SCALE, "unfit follows again at the old zoom");
        check(!control.unfit(), "and only once");

        // A drag after the fit makes the view the driver's: the offer's end leaves it.
        control.camera(vehicle, null, 0);
        control.fit(lats, lons, pane, l.getOfferView());
        control.camera(vehicle, null, 0);
        control.touch("down", List.of(new TouchPoint(0, pane.getCenterX(), pane.getCenterY() + 60, pane.getCenterX(), pane.getCenterY() + 60)), pane, 0L);
        control.touch("move", List.of(new TouchPoint(0, pane.getCenterX() - 80, pane.getCenterY() + 60, pane.getCenterX() - 80, pane.getCenterY() + 60)), pane, 10L);
        control.touch("up", List.of(new TouchPoint(0, pane.getCenterX() - 80, pane.getCenterY() + 60, pane.getCenterX() - 80, pane.getCenterY() + 60)), pane, 20L);
        check(!control.getFitted() && !control.unfit() && !control.getFollowing(), "a drag makes the view the driver's; unfit leaves it");

        // So does a zoom bubble; the recentre bubble from a fit gives the old zoom back.
        control.recentre();
        control.camera(vehicle, null, 0);
        control.fit(lats, lons, pane, l.getOfferView());
        control.zoomBy(1);
        check(!control.getFitted() && !control.unfit(), "a zoom press is the driver's too");
        control.recentre();
        control.camera(vehicle, null, 0);
        double before = control.getScale();
        control.fit(lats, lons, pane, l.getOfferView());
        check(control.getScale() < before, "the fit zoomed out");
        control.recentre();
        check(control.getFollowing() && control.getScale() == before && !control.getFitted(),
            "recentre from a fit follows at the old zoom");
        check(control.report().contains("fits=4 fitted=false"), "fits are counted: " + control.report());
    }

    // --- the offer on the dashboard (P9.6) --------------------------------------------

    private static TrafficOffer offer(long id, Route route) {
        return new TrafficOffer(id, route, 420, "Queue on I-35E", 30.002, -97.0);
    }

    /** A dashboard on the test route: a turn to show, the vehicle 55 m along, the route on the map. */
    private static Dashboard onRoute(DashboardLayout l) {
        Dashboard d = dashboard(l);
        d.phone(new PhoneSnapshot(60, false, 3, "cell", 30.0005, -97.0, null, null, null, true));
        d.followedRoute(straight(30));
        d.upcomingTurn(guiding(600));
        d.mapCamera();
        d.nextFrame(NOON);
        return d;
    }

    private static TrafficDisplay withOffer(Route route, long id) {
        return new TrafficDisplay(traffic(route, run(400, 800, Congestion.QUEUING)), 420, offer(id, detour()), false);
    }

    private static void anOfferDividesTheCardAndFitsTheMap() {
        for (DashboardLayout l : everyDisplay()) {
            String at = size(l);
            Dashboard d = onRoute(l);
            check(d.nextFrame(NOON).getOffer() == null && d.nextFrame(NOON).getMapFollowing(), at + "no offer: a whole card, following");
            d.traffic(withOffer(straight(30), 1));
            d.mapCamera();
            Dashboard.Snapshot s = d.nextFrame(NOON);
            OfferCard card = s.getOffer();
            check(card != null && card.getId() == 1, at + "the offer divides the card");
            check(("Faster: " + MINUS + "7 min").equals(card.getLabel()) && "Queue on I-35E".equals(card.getReason()),
                at + "with its label and reason: " + card.getLabel());
            check(s.getTurnCard() != null, at + "guidance never disappears while the offer is up");
            check(!s.getMapFollowing(), at + "the map leaves following");
            kotlin.Pair<Integer, Integer> marker = s.getMapMarker();
            Box view = l.getOfferView();
            check(marker != null && marker.getFirst() >= view.getX() - 1 && marker.getFirst() <= view.getRight() + 1
                && marker.getSecond() >= view.getY() - 1 && marker.getSecond() <= view.getBottom() + 1,
                at + "and the vehicle is in the view under the card: " + marker);
            check(d.shownOffer() != null && d.shownOffer().getId() == 1, at + "the scene thread sees the offer's route to draw");
            check(d.report().contains("dashboard_offers_shown=1 ") && d.report().contains("dashboard_offer_fits=1 "), at + "shown and fitted, counted");
        }
    }

    private static void takeAndKeepAnswerTheOfferAndRefollow() {
        for (String action : new String[] {"take", "keep"}) {
            for (DashboardLayout l : everyDisplay()) {
                String at = size(l) + action + ": ";
                Dashboard d = onRoute(l);
                MapCamera following = d.mapCamera();
                d.traffic(withOffer(straight(30), 7));
                d.mapCamera();
                d.nextFrame(NOON);
                Box button = l.offerButton(action.equals("take") ? 0 : 1);
                d.touch("down", button.getCenterX(), button.getCenterY());
                check(action.equals(d.nextFrame(NOON).getOffer().getPressed()), at + "the button lights while pressed");
                d.touch("up", button.getCenterX(), button.getCenterY());
                OfferAnswer answer = d.takeOfferAnswer();
                check(answer != null && answer.getId() == 7 && answer.getTake() == action.equals("take"),
                    at + "the listener is told the offer's id and the choice: " + answer);
                check(d.takeOfferAnswer() == null, at + "once");
                Dashboard.Snapshot s = d.nextFrame(NOON);
                check(s.getOffer() == null && s.getTurnCard() != null, at + "the card is whole again at once");
                check(s.getMapFollowing(), at + "and the map follows again");
                MapCamera back = d.mapCamera();
                check(back.getScale() == following.getScale() && Math.abs(back.getLatitude() - following.getLatitude()) < 1e-9,
                    at + "where it was, at the zoom it had");
                check(d.report().contains("pans=0 "), at + "the press did not pan the map");
                check(d.report().contains(action.equals("take") ? "dashboard_offers_taken=1 " : "dashboard_offers_kept=1 "), at + "counted");
                // The loop republishing the answered offer does not bring it back.
                d.traffic(withOffer(straight(30), 7));
                check(d.nextFrame(NOON).getOffer() == null && d.shownOffer() == null, at + "an answered offer stays down");
                d.traffic(new TrafficDisplay(null, 0, null, false));
                d.traffic(withOffer(straight(30), 8));
                check(d.nextFrame(NOON).getOffer() != null && d.nextFrame(NOON).getOffer().getId() == 8, at + "a new one is shown");
                check(d.report().contains("dashboard_offers_withdrawn=0 "), at + "an answer is not a withdrawal");
            }
        }
    }

    private static void aWithdrawnOfferRefollows() {
        DashboardLayout l = new DashboardLayout(74, 0, 726, 480, true);
        Dashboard d = onRoute(l);
        d.traffic(withOffer(straight(30), 3));
        check(!d.nextFrame(NOON).getMapFollowing(), "fitted");
        // Withdrawn by the loop: the vehicle passed the split, or the saving went.
        d.traffic(new TrafficDisplay(traffic(straight(30), run(400, 800, Congestion.QUEUING)), 420, null, false));
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check(s.getOffer() == null && s.getMapFollowing() && d.takeOfferAnswer() == null, "withdrawn: whole card, following, no answer");
        check(d.mapCamera().getScale() == MapControl.DEFAULT_SCALE, "at the zoom before the fit");
        check(d.report().contains("dashboard_offers_withdrawn=1 ") && d.report().contains("dashboard_offer_refollows=1\n"), "counted");
        // One offer replaced by another: the first is withdrawn, the second fitted afresh.
        d.traffic(withOffer(straight(30), 4));
        d.traffic(withOffer(straight(30), 5));
        check(d.nextFrame(NOON).getOffer().getId() == 5 && !d.nextFrame(NOON).getMapFollowing(), "a replacement is shown and fitted");
        check(d.report().contains("dashboard_offers_withdrawn=2 ") && d.report().contains("dashboard_offer_fits=3 "), "counted as such");
    }

    private static void aDriversOwnPanIsLeftAlone() {
        DashboardLayout l = new DashboardLayout(74, 0, 726, 480, true);
        Box view = l.getOfferView();
        // Panned during the offer: its end leaves the map where the driver put it.
        Dashboard d = onRoute(l);
        d.traffic(withOffer(straight(30), 1));
        d.mapCamera();
        d.touch("down", List.of(new TouchPoint(0, view.getCenterX(), view.getCenterY(), view.getCenterX(), view.getCenterY())));
        d.touch("move", List.of(new TouchPoint(0, view.getCenterX() - 90, view.getCenterY(), view.getCenterX() - 90, view.getCenterY())));
        d.touch("up", List.of(new TouchPoint(0, view.getCenterX() - 90, view.getCenterY(), view.getCenterX() - 90, view.getCenterY())));
        MapCamera panned = d.mapCamera();
        d.traffic(TrafficDisplay.Companion.getEMPTY());
        check(!d.nextFrame(NOON).getMapFollowing(), "panned during the offer: its end does not recentre");
        MapCamera after = d.mapCamera();
        check(after.getLatitude() == panned.getLatitude() && after.getLongitude() == panned.getLongitude(), "the map stays where they put it");
        check(d.report().contains("dashboard_offer_refollows=0\n"), "no refollow counted");

        // Panned before the offer: no fit, but the card still divides.
        Dashboard e = onRoute(l);
        Box map = l.getMap();
        e.touch("down", map.getCenterX(), map.getCenterY() + 80);
        e.touch("move", List.of(new TouchPoint(0, map.getCenterX() - 90, map.getCenterY() + 80, map.getCenterX() - 90, map.getCenterY() + 80)));
        e.touch("up", map.getCenterX() - 90, map.getCenterY() + 80);
        MapCamera own = e.mapCamera();
        e.traffic(withOffer(straight(30), 2));
        MapCamera still = e.mapCamera();
        check(e.nextFrame(NOON).getOffer() != null && still.equals(own), "a map the driver moved is not fitted: " + own + " " + still);
        check(e.report().contains("dashboard_offer_fits=0 "), "no fit counted");
    }

    private static void aPressOnTheCardIsNotAPanAndNeedsTheSameTarget() {
        for (DashboardLayout l : everyDisplay()) {
            String at = size(l);
            Dashboard d = onRoute(l);
            d.traffic(withOffer(straight(30), 9));
            MapCamera fitted = d.mapCamera();
            Box text = l.getOfferText();
            d.touch("down", text.getCenterX(), text.getCenterY());
            d.touch("move", List.of(new TouchPoint(0, text.getCenterX() - 100, text.getCenterY() + 40, text.getCenterX() - 100, text.getCenterY() + 40)));
            d.touch("up", text.getCenterX() - 100, text.getCenterY() + 40);
            check(d.mapCamera().equals(fitted), at + "a drag that starts on the card does not move the map");
            check(d.report().contains("pans=0 "), at + "and is not counted as a pan");
            Box take = l.offerButton(0);
            Box keep = l.offerButton(1);
            d.touch("down", take.getCenterX(), take.getCenterY());
            d.touch("up", keep.getCenterX(), keep.getCenterY());
            check(d.takeOfferAnswer() == null && d.nextFrame(NOON).getOffer() != null, at + "down on Take and up on Keep answers nothing");
            d.touch("down", take.getCenterX(), take.getCenterY());
            d.traffic(withOffer(straight(30), 10));
            d.touch("up", take.getCenterX(), take.getCenterY());
            check(d.takeOfferAnswer() == null, at + "an offer replaced under the finger is not taken");
            d.touch("down", take.getCenterX(), take.getCenterY());
            d.touch("cancel", take.getCenterX(), take.getCenterY());
            check(d.nextFrame(NOON).getOffer().getPressed() == null && d.takeOfferAnswer() == null, at + "a cancelled press answers nothing");
            // With no offer, the same place on the map is map again, below the whole card.
            d.traffic(TrafficDisplay.Companion.getEMPTY());
            check(d.nextFrame(NOON).getOffer() == null, at + "withdrawn");
            d.touch("down", take.getCenterX(), take.getCenterY());
            d.touch("up", take.getCenterX(), take.getCenterY());
            check(d.takeOfferAnswer() == null, at + "Take is not a target once the offer has gone");
        }
    }

    private static void theReportCountsAndNamesNothing() {
        Dashboard d = onRoute(new DashboardLayout(74, 0, 726, 480, true));
        d.traffic(withOffer(straight(30), 1));
        Box take = d.getLayout().offerButton(0);
        d.touch("down", take.getCenterX(), take.getCenterY());
        d.touch("up", take.getCenterX(), take.getCenterY());
        d.nextFrame(NOON);
        String report = d.report();
        for (String want : new String[] {
            "dashboard_traffic_displays=1 ", "dashboard_traffic_runs=1 ", "dashboard_traffic_attributed=true ",
            "dashboard_traffic_delay_min=7 ", "dashboard_offer=false ", "dashboard_offer_layout=side-by-side ",
            "dashboard_offers_shown=1 ", "dashboard_offers_taken=1 ", "dashboard_offers_kept=0 ",
            "dashboard_offers_withdrawn=0 ", "dashboard_offer_fits=1 ", "dashboard_offer_refollows=1\n",
        }) {
            check(report.contains(want), "the report has " + want.trim());
        }
        for (String never : new String[] {"I-35E", "Queue", "Faster", "30.00", "-97.0", "96.99", "HERE"}) {
            check(!report.contains(never), "the report never carries " + never);
        }
        check(DashboardLayout.Companion.getDefaultOfferLayout() == OfferLayout.SIDE_BY_SIDE, "side by side is the default layout");
    }

    // --- helpers ----------------------------------------------------------------------

    private static Dashboard dashboard(DashboardLayout layout) {
        return new Dashboard(layout, "t", ZoneId.of("UTC"));
    }

    private static UpcomingTurn guiding(int seconds) {
        return new UpcomingTurn("Turn right onto Main Street", 480.0, Maneuvers.TURN_NORMAL_RIGHT, 0, "Main Street",
            4023.0, seconds, List.<LaterStep>of());
    }

    private static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }

    private static double distance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static String hex(int colour) { return String.format("#%08X", colour); }

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
