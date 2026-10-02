// P9.2 on a desktop JVM (docs/p9-plan.md sections 4 to 7 and 9): the boxes asked of HERE
// around the route ahead, HERE's roads laid on the route, the delay they add, and where the
// no-go points go and how heavy they are. TrafficArea, TrafficMatch and TrafficNogos are pure
// Kotlin.
//
// Every HERE stretch here is hand-written in HERE's shape (points to five decimals, roughly
// 20 m apart, spans laid end to end by their lengths) and laid along a route: never a real
// response (plan section 1, 6.4 h). The scenario - the same carriageway a few metres off, the other
// carriageway 30 m away and reversed, a frontage road 25 m away, a cross street on an
// overpass, a stretch with subSegments, a no-data gap, a closure - runs on a curving
// synthetic route always, and with a directory holding W100_N30.rd5 as the first argument
// also on BRouter's route along I-35E (Stemmons Freeway) in Dallas.
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import kotlin.Pair;
import org.openautoandroid.source.Congestion;
import org.openautoandroid.source.FlowSpan;
import org.openautoandroid.source.FlowStretch;
import org.openautoandroid.source.Nogo;
import org.openautoandroid.source.Route;
import org.openautoandroid.source.RouteFollower;
import org.openautoandroid.source.RouteOptions;
import org.openautoandroid.source.RoutePlanner;
import org.openautoandroid.source.RouteRequest;
import org.openautoandroid.source.RouteTraffic;
import org.openautoandroid.source.Router;
import org.openautoandroid.source.RoutingData;
import org.openautoandroid.source.TrafficArea;
import org.openautoandroid.source.TrafficBox;
import org.openautoandroid.source.TrafficCost;
import org.openautoandroid.source.TrafficIncident;
import org.openautoandroid.source.TrafficMatch;
import org.openautoandroid.source.TrafficNogos;
import org.openautoandroid.source.TrafficRun;
import org.openautoandroid.source.TrafficSnapshot;
import org.openautoandroid.source.Traversability;

public final class TrafficMatchTest {
    private static int passed;
    private static final TrafficArea AREA = TrafficArea.INSTANCE;
    private static final TrafficMatch MATCH = TrafficMatch.INSTANCE;
    private static final TrafficNogos NOGOS = TrafficNogos.INSTANCE;
    private static final double M = 6_371_000.0 * Math.PI / 180.0;

    public static void main(String[] args) throws Exception {
        theBoxIsPaddedOnEverySide();
        theLookAheadStopsAtTheDestination();
        aBoxTooBigIsCutIntoCells();
        aLongRouteAsksOnlyForTheCellsItCrosses();
        coveringTheRouteAhead();
        scenario(synthetic(), 3_000.0, "synthetic");
        aRouteWithNoTrafficHasNoRuns();
        aFreeRunSlowerThanBRouterIsNoDelay();
        matchingIsQuickEnough();
        pointsAreSpacedAndKeptFromTheEnds();
        theWeightSumsToTheDelay();
        closuresAreHardAndJamsWeighted();
        aShortClosureStillBlocks();
        closuresOnOneRoadAreNotDoubled();
        aPointIsSlidOffACrossingRoad();
        theCapKeepsClosuresAndHeavyJams();
        withTheRouteTheJamOnItIsKept();
        reviewReversibleLanesAndShortClosures();
        reviewAStoppedQueueIsADelay();
        String real = "real route skipped (no W100_N30.rd5)";
        if (args.length > 0 && !args[0].isEmpty() && new File(args[0], "W100_N30.rd5").isFile()) {
            real = realRoute(new File(args[0]));
        }
        System.out.println("jni-host-test: ok " + passed + " traffic match checks; " + real);
    }

    // ---- Boxes (plan section 4) ----

    private static void theBoxIsPaddedOnEverySide() {
        Route north = line(30.0, -97.0, 0.0, 10_000.0, 100.0, 30.0);
        TrafficBox box = AREA.around(north, 0.0, 30.0);
        double end = north.getLatitudes()[north.getPoints() - 1];
        check(Math.abs((30.0 - box.getSouth()) * M - 2000.0) < 0.5, "2 km south of the start: " + (30.0 - box.getSouth()) * M);
        check(Math.abs((box.getNorth() - end) * M - 2000.0) < 0.5, "2 km north of the destination");
        double westward = RouteFollower.Companion.distance(box.getNorth(), box.getWest(), box.getNorth(), -97.0);
        double eastward = RouteFollower.Companion.distance(box.getNorth(), -97.0, box.getNorth(), box.getEast());
        check(westward >= 1999.0 && westward < 2010.0 && eastward >= 1999.0 && eastward < 2010.0,
                "and 2 km either side, even at the box's northern edge: " + westward + ", " + eastward);
        check(AREA.boxes(north, 0.0, 30.0).size() == 1 && AREA.acceptable(box), "one box, and one HERE takes");
        check(box.query().startsWith("bbox:-97.02") && box.query().split(",").length == 4, "written west, south, east, north: " + box.query());
        check(AREA.around(new Route(new double[0], new double[0], 0, 0, new int[0], new int[0], new int[0]), 0.0, 30.0) == null
                && AREA.boxes(new Route(new double[0], new double[0], 0, 0, new int[0], new int[0], new int[0]), 0.0, 30.0).isEmpty(),
                "a route with no points asks for nothing");
    }

    private static void theLookAheadStopsAtTheDestination() {
        Route north = line(30.0, -97.0, 0.0, 10_000.0, 100.0, 30.0);
        TrafficBox part = AREA.around(north, 2_000.0, 3.0);
        check(Math.abs((part.getSouth() - 30.0) * M - 0.0) < 1.0, "from 2 km along, less the padding: " + (part.getSouth() - 30.0) * M);
        check(Math.abs((part.getNorth() - 30.0) * M - 7_000.0) < 1.0, "to 5 km along, plus the padding: " + (part.getNorth() - 30.0) * M);
        TrafficBox rest = AREA.around(north, 8_000.0, 30.0);
        check(Math.abs((rest.getNorth() - 30.0) * M - 12_000.0) < 1.0 && Math.abs((rest.getSouth() - 30.0) * M - 6_000.0) < 1.0,
                "30 km ahead of a point 2 km from the end is only the 2 km left");
        TrafficBox between = AREA.around(north, 2_050.0, 1.0);
        check(Math.abs((between.getSouth() - 30.0) * M - 50.0) < 1.0 && Math.abs((between.getNorth() - 30.0) * M - 5_050.0) < 1.0,
                "ends between two points are placed where they fall");
    }

    private static void aBoxTooBigIsCutIntoCells() {
        TrafficBox wide = new TrafficBox(31.0, -99.0, 31.5, -96.5);
        List<TrafficBox> cells = AREA.cells(wide);
        check(cells.size() == 3, "2.5 deg wide is three cells: " + cells.size());
        for (TrafficBox cell : cells) check(AREA.acceptable(cell), "each one HERE takes: " + cell.query());
        check(cells.get(0).getWest() == -99.0 && cells.get(2).getEast() == -96.5 && cells.get(0).getEast() == cells.get(1).getWest()
                && cells.get(1).getEast() == cells.get(2).getWest(), "edge to edge, the whole box and no more");
        check(!AREA.acceptable(new TrafficBox(31.0, -97.0, 32.00001, -96.5)), "a box over 1 deg is refused, as HERE refuses it");
        check(AREA.acceptable(new TrafficBox(31.0, -97.0, 31.999, -96.001)), "one just under is taken");
        check(AREA.cells(new TrafficBox(31.0, -97.0, 32.0, -96.5)).size() == 2, "exactly 1 deg is cut too: the five decimals must not round it over");
        // 50 km of look-ahead on a diagonal in Texas never needs cutting.
        Route diagonal = line(32.7, -96.8, 45.0, 60_000.0, 50.0, 30.0);
        List<TrafficBox> texas = AREA.boxes(diagonal, 0.0, 50.0);
        check(texas.size() == 1 && AREA.acceptable(texas.get(0)), "50 km on a diagonal near Dallas is one box: " + texas.get(0).query());
        // Far enough north that 50 km east is nearly a degree of longitude, it does.
        Route arctic = line(62.0, 10.0, 90.0, 60_000.0, 50.0, 30.0);
        List<TrafficBox> north = AREA.boxes(arctic, 0.0, 50.0);
        check(north.size() == 2, "50 km east at 62 deg N with its padding is two cells: " + north.size());
        for (TrafficBox cell : north) check(AREA.acceptable(cell), "and HERE takes each: " + cell.query());
        check(coveredAhead(north, arctic, 0.0, 50_000.0), "which hold the whole 50 km");
    }

    private static void aLongRouteAsksOnlyForTheCellsItCrosses() {
        Route far = line(30.0, -100.0, 45.0, 400_000.0, 200.0, 30.0);
        List<TrafficBox> boxes = AREA.boxes(far, 0.0, 400.0);
        TrafficBox around = AREA.around(far, 0.0, 400.0);
        int grid = AREA.cells(around).size();
        for (TrafficBox box : boxes) check(AREA.acceptable(box), "every cell is one HERE takes");
        check(coveredAhead(boxes, far, 0.0, 400_000.0), "the cells hold the whole route");
        check(boxes.size() < grid, "a diagonal leaves out the empty corners: " + boxes.size() + " of " + grid);
    }

    private static void coveringTheRouteAhead() {
        Route north = line(30.0, -97.0, 0.0, 60_000.0, 100.0, 30.0);
        List<TrafficBox> fetched = AREA.boxes(north, 0.0, 30.0);
        check(AREA.covers(fetched, north, 0.0, 30_000.0), "a fetch covers what it was for");
        check(AREA.covers(fetched, north, 20_000.0, 10_000.0), "and still the rest of it 20 km on");
        check(!AREA.covers(fetched, north, 20_000.0, 30_000.0), "but not 30 km from there");
        Route east = line(30.0, -97.0, 90.0, 30_000.0, 100.0, 30.0);
        check(!AREA.covers(fetched, east, 0.0, 15_000.0), "nor a new route that turns away from it");
    }

    // ---- Matching (plan sections 5 and 6) on a route with a hand-written HERE response laid along it ----

    /**
     * From [c] metres along the route: the same carriageway a few metres off it, in spans
     * (free, queuing, slow), a gap with no data, then more of it, slow, with a closure on it;
     * alongside, the other carriageway reversed 30 m away and queuing, a closed frontage
     * road 25 m away the whole length, and a queuing cross street on an overpass.
     */
    private static void scenario(Route route, double c, String what) {
        double[] along = AREA.metres(route);
        check(along[along.length - 1] > c + 3_000.0, what + ": the route is long enough");
        List<FlowStretch> flow = new ArrayList<>();
        double[][] a = laid(route, along, c, c + 1_200.0, 3.0, false);
        flow.add(new FlowStretch("I-35E", 1_200.0, a[0], a[1], 1, List.of(
                new FlowSpan(0.0, 400.0, 40.0, 40.0, 0.5, Traversability.OPEN),
                new FlowSpan(400.0, 800.0, 2.0, 30.0, 9.5, Traversability.OPEN),
                new FlowSpan(800.0, 1_200.0, 10.0, 30.0, 6.0, Traversability.OPEN))));
        double[][] b = laid(route, along, c + 1_500.0, c + 2_500.0, -4.0, false);
        flow.add(new FlowStretch("I-35E", 1_000.0, b[0], b[1], 1, List.of(span(0.0, 1_000.0, 12.0, 30.0, 5.0))));
        double[][] other = laid(route, along, c - 200.0, c + 2_700.0, -30.0, true);
        flow.add(new FlowStretch("I-35E SB", 2_900.0, other[0], other[1], 1, List.of(span(0.0, 2_900.0, 1.0, 30.0, 10.0))));
        double[][] frontage = laid(route, along, c, c + 2_500.0, 25.0, false);
        flow.add(new FlowStretch("Frontage", 2_500.0, frontage[0], frontage[1], 3, List.of(
                new FlowSpan(0.0, 2_500.0, null, 15.0, 10.0, Traversability.CLOSED))));
        double[] centre = at(route, along, c + 2_000.0);
        double h = headingAt(route, along, c + 2_000.0);
        double[] left = move(centre[0], centre[1], h - 90.0, 210.0);
        double[][] cross = straight(left[0], left[1], h + 90.0, 420.0, 20.0);
        flow.add(new FlowStretch("Overpass", 420.0, cross[0], cross[1], 3, List.of(span(0.0, 420.0, 1.0, 15.0, 10.0))));
        List<TrafficIncident> incidents = new ArrayList<>();
        double[][] works = laid(route, along, c + 100.0, c + 300.0, 2.0, false);
        incidents.add(new TrafficIncident("works", "construction", "minor", false, "Lane closed", works[0], works[1]));
        double[][] shut = laid(route, along, c + 2_200.0, c + 2_300.0, 2.0, false);
        incidents.add(new TrafficIncident("shut", "roadClosure", "critical", true, "Closed", shut[0], shut[1]));
        double[][] shutOther = laid(route, along, c + 600.0, c + 900.0, -30.0, true);
        incidents.add(new TrafficIncident("shut-sb", "roadClosure", "critical", true, "Closed", shutOther[0], shutOther[1]));

        RouteTraffic traffic = MATCH.match(route, new TrafficSnapshot(new TrafficBox(0, 0, 1, 1), 0L, flow, incidents));
        List<TrafficRun> runs = traffic.getRuns();
        String shape = describe(runs, c);
        check(runs.size() == 6, what + ": six runs: " + shape);
        if (runs.size() != 6) return;
        expect(runs.get(0), Congestion.FREE, c, c + 400.0, what + " free span", shape);
        expect(runs.get(1), Congestion.QUEUING, c + 400.0, c + 800.0, what + " queuing span", shape);
        expect(runs.get(2), Congestion.SLOW, c + 800.0, c + 1_200.0, what + " slow span", shape);
        expect(runs.get(3), Congestion.SLOW, c + 1_500.0, c + 2_200.0, what + " after the gap, across the overpass", shape);
        expect(runs.get(4), Congestion.CLOSED, c + 2_200.0, c + 2_300.0, what + " the closure", shape);
        expect(runs.get(5), Congestion.SLOW, c + 2_300.0, c + 2_500.0, what + " after it", shape);
        for (TrafficRun run : runs) {
            check("I-35E".equals(run.getRoad()), what + ": only the route's own carriageway matched, never the other, the frontage road or the overpass: " + run.getRoad());
            check(along[run.getFromIndex()] <= run.getFromM() + 1e-6 && along[run.getToIndex()] >= run.getToM() - 1e-6
                    && (run.getFromIndex() + 1 >= along.length || along[run.getFromIndex() + 1] > run.getFromM() - 1e-6),
                    what + ": a run's points are the ones around its metres");
        }
        for (int i = 1; i < runs.size(); i++) check(runs.get(i).getFromM() >= runs.get(i - 1).getToM() - 1e-6, what + ": runs do not overlap");
        check(nothingFrom(runs, c + 1_250.0, c + 1_450.0), what + ": the gap has no run, frontage road beside it or not (no data, not free)");

        // Delay (plan section 6): HERE's time less BRouter's, positive only; closures none.
        TrafficRun free = runs.get(0);
        check(free.getDelaySeconds() == 0.0 && Math.abs(free.getSpeedMps() - 40.0) < 1e-6, what + ": faster than BRouter is no delay, never negative");
        double total = 0.0;
        for (int i = 1; i < runs.size(); i++) {
            TrafficRun run = runs.get(i);
            if (run.getCongestion() == Congestion.CLOSED) continue;
            double speed = i <= 2 ? (i == 1 ? 2.0 : 10.0) : 12.0;
            double expected = Math.max(0.0, (run.getToM() - run.getFromM()) / speed - (seconds(route, along, run.getToM()) - seconds(route, along, run.getFromM())));
            check(Math.abs(run.getDelaySeconds() - expected) < 0.01 && expected > 0.0, what + ": delay is length at HERE's speed less BRouter's time: "
                    + run.getDelaySeconds() + " against " + expected);
            total += run.getDelaySeconds();
        }
        TrafficRun closed = runs.get(4);
        check(closed.getDelaySeconds() == 0.0 && closed.getSpeedMps() == null, what + ": a closure has no speed and no delay; routing handles it");
        check(Math.abs(traffic.delayAheadSeconds(0.0) - total) < 1e-6, what + ": the delay ahead of the start is every run's");
        check(traffic.delayAheadSeconds(c + 2_600.0) == 0.0, what + ": and past the last run, none");
        TrafficRun queue = runs.get(1);
        double half = (queue.getFromM() + queue.getToM()) / 2.0;
        check(Math.abs(traffic.delayAheadSeconds(half) - (total - queue.getDelaySeconds() / 2.0)) < 1e-6, what + ": half a run behind counts half");
        check(traffic.closureAhead(c) == closed && traffic.closureAhead(c + 2_400.0) == null, what + ": the closure is ahead until passed");
        List<String> ids = new ArrayList<>();
        for (TrafficIncident incident : traffic.getIncidents()) ids.add(incident.getId());
        check(ids.equals(List.of("works", "shut")), what + ": incidents on the route, in route order, not the other carriageway's: " + ids);

        Pair<double[], double[]> cut = MATCH.line(route, queue.getFromM(), queue.getToM());
        double[] start = at(route, along, queue.getFromM());
        double[] end = at(route, along, queue.getToM());
        double[] lats = cut.getFirst();
        double[] lons = cut.getSecond();
        check(RouteFollower.Companion.distance(lats[0], lons[0], start[0], start[1]) < 0.01
                && RouteFollower.Companion.distance(lats[lats.length - 1], lons[lons.length - 1], end[0], end[1]) < 0.01,
                what + ": a run's own line starts and ends at its metres");

        // No-go points (plan section 7) for the same response.
        List<Nogo> nogos = NOGOS.of(flow, incidents, route);
        int touching = 0;
        int hardTouching = 0;
        for (Nogo nogo : nogos) {
            check(nogo.getRadiusM() == TrafficNogos.RADIUS_M, what + ": every point is 8 m");
            double d = fromRoute(route, nogo.getLatitude(), nogo.getLongitude());
            if (d < TrafficNogos.RADIUS_M) {
                touching++;
                if (nogo.getHard()) hardTouching++;
                double m = alongNearest(route, along, nogo.getLatitude(), nogo.getLongitude());
                check((m > c + 400.0 && m < c + 1_200.0) || (m > c + 1_500.0 && m < c + 2_500.0),
                        what + ": only the route's own jams and closure reach it: " + (m - c));
            }
        }
        // Queuing 400 m: 3; slow 400 m less the far margin: 3; slow 1,000 m less both: 7; the 100 m closure: 1.
        check(touching == 14, what + ": 14 points on the route itself: " + touching);
        check(hardTouching == 1, what + ": one of them the closure's, hard");
        for (Nogo nogo : nogos) {
            double[] c2 = {nogo.getLatitude(), nogo.getLongitude()};
            double toCross = fromLine(cross, c2[0], c2[1]);
            if (toCross < 1.0) {
                check(fromRoute(route, c2[0], c2[1]) > TrafficNogos.CLEAR_M - 5.0, what + ": the overpass's points keep off the road beneath");
            }
        }
    }

    /**
     * The orchestrator's change to plan section 6 (docs/p9-4-5-runtime-results.md): HERE's free
     * flow a little under BRouter's model is not traffic. On P9.0's real Dallas routes that alone
     * made 17 and 36 s, a "+1 min traffic" on empty roads. Only slow and queuing runs carry delay.
     */
    private static void aFreeRunSlowerThanBRouterIsNoDelay() {
        // BRouter drives it at 30 m/s; HERE says 20 m/s everywhere, free-flowing then slow.
        Route route = line(32.9, -96.9, 0.0, 3_000.0, 50.0, 30.0);
        double[] along = AREA.metres(route);
        double[][] a = laid(route, along, 0.0, 1_000.0, 2.0, false);
        double[][] b = laid(route, along, 1_000.0, 2_000.0, 2.0, false);
        List<FlowStretch> flow = List.of(
                new FlowStretch("Free", 1_000.0, a[0], a[1], 1, List.of(span(0.0, 1_000.0, 20.0, 20.0, 1.0))),
                new FlowStretch("Slow", 1_000.0, b[0], b[1], 1, List.of(span(0.0, 1_000.0, 20.0, 30.0, 5.0))));
        RouteTraffic traffic = MATCH.match(route, flow, List.of());
        List<TrafficRun> runs = traffic.getRuns();
        check(runs.size() == 2 && runs.get(0).getCongestion() == Congestion.FREE && runs.get(1).getCongestion() == Congestion.SLOW,
                "a free run then a slow one: " + describe(runs, 0.0));
        if (runs.size() != 2) return;
        check(runs.get(0).getDelaySeconds() == 0.0 && Math.abs(runs.get(0).getSpeedMps() - 20.0) < 0.5,
                "free flow slower than BRouter's model is no delay: " + runs.get(0).getDelaySeconds());
        double slow = runs.get(1).getToM() - runs.get(1).getFromM();
        check(Math.abs(runs.get(1).getDelaySeconds() - (slow / 20.0 - slow / 30.0)) < 0.5,
                "the same speed on a slow run is: " + runs.get(1).getDelaySeconds());
        check(Math.abs(traffic.delayAheadSeconds(0.0) - runs.get(1).getDelaySeconds()) < 1e-9,
                "so the delay ahead is the slow run's alone");
    }

    private static void aRouteWithNoTrafficHasNoRuns() {
        Route route = synthetic();
        RouteTraffic none = MATCH.match(route, List.of(), List.of());
        check(none.getRuns().isEmpty() && none.getIncidents().isEmpty() && none.getRoute() == route, "no response, no runs");
        double[] along = AREA.metres(route);
        double[][] far = laid(route, along, 1_000.0, 2_000.0, 200.0, false);
        RouteTraffic away = MATCH.match(route, List.of(new FlowStretch("Elsewhere", 1_000.0, far[0], far[1], 2, List.of(span(0.0, 1_000.0, 2.0, 30.0, 9.0)))), List.of());
        check(away.getRuns().isEmpty(), "a jam 200 m away is not on the route");
        check(TrafficMatch.NEAR_M == 15.0 && TrafficMatch.HEADING_DEG == 30.0, "the plan's first guesses, 15 m and 30 deg");
    }

    /** A 30 km route of 3,000 points against 500 stretches either way, 0 to 60 m off it. */
    private static void matchingIsQuickEnough() {
        Route route = line(32.8, -96.8, 30.0, 30_000.0, 10.0, 30.0);
        double[] along = AREA.metres(route);
        Random random = new Random(92);
        List<FlowStretch> flow = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            double from = random.nextDouble() * 29_000.0;
            double lateral = (random.nextDouble() - 0.5) * 120.0;
            double[][] s = laid(route, along, from, Math.min(from + 600.0, 30_000.0), lateral, random.nextBoolean());
            flow.add(new FlowStretch("S" + i, 600.0, s[0], s[1], 2, List.of(span(0.0, 600.0, 5.0 + 20.0 * random.nextDouble(), 30.0, 10.0 * random.nextDouble()))));
        }
        long best = Long.MAX_VALUE;
        RouteTraffic traffic = null;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            traffic = MATCH.match(route, flow, List.of());
            best = Math.min(best, System.nanoTime() - start);
        }
        long ms = best / 1_000_000L;
        check(!traffic.getRuns().isEmpty(), "the busy route has runs");
        check(ms < 1_000L, "and is matched in well under a second on the host: " + ms + " ms");
        long start = System.nanoTime();
        List<Nogo> nogos = NOGOS.of(flow, List.of(), route);
        long nogoMs = (System.nanoTime() - start) / 1_000_000L;
        check(nogos.size() <= TrafficNogos.MAX_NOGOS, "its nogos are capped");
        System.out.println("traffic-match-test: busy route points=" + route.getPoints() + " stretches=" + flow.size() + " runs="
                + traffic.getRuns().size() + " match_ms=" + ms + " nogos=" + nogos.size() + " nogo_ms=" + nogoMs);
    }

    // ---- No-go points (plan section 7) on plain geometry ----

    private static void pointsAreSpacedAndKeptFromTheEnds() {
        double[][] s = straight(30.0, -97.0, 0.0, 1_000.0, 20.0);
        FlowStretch jam = new FlowStretch("Jam", 1_000.0, s[0], s[1], 2, List.of(span(0.0, 1_000.0, 10.0, 25.0, 6.0)));
        List<Nogo> nogos = NOGOS.of(List.of(jam), List.of());
        check(nogos.size() == 7, "940 m between the margins is 7 points: " + nogos.size());
        double previous = -1.0;
        for (Nogo nogo : nogos) {
            double m = (nogo.getLatitude() - s[0][0]) * M;
            check(m >= TrafficNogos.END_MARGIN_M - 0.5 && m <= 1_000.0 - TrafficNogos.END_MARGIN_M + 0.5, "none within 30 m of either end: " + m);
            check(nogo.getRadiusM() == 8.0 && !nogo.getHard(), "8 m and weighted");
            if (previous >= 0.0) check(m - previous <= TrafficNogos.SPACING_M + 0.5, "no more than 150 m apart: " + (m - previous));
            previous = m;
        }
        FlowStretch tail = new FlowStretch("Tail", 1_000.0, s[0], s[1], 2, List.of(
                span(0.0, 980.0, 25.0, 25.0, 0.0), span(980.0, 1_000.0, 2.0, 25.0, 9.0)));
        check(NOGOS.of(List.of(tail), List.of()).isEmpty(), "a jam only in the last 20 m, at the junction, gets no point");
        FlowStretch free = new FlowStretch("Free", 1_000.0, s[0], s[1], 2, List.of(span(0.0, 1_000.0, 25.0, 25.0, 1.0)));
        FlowStretch fast = new FlowStretch("Fast", 1_000.0, s[0], s[1], 2, List.of(span(0.0, 1_000.0, 30.0, 25.0, 5.0)));
        check(NOGOS.of(List.of(free), List.of()).isEmpty() && NOGOS.of(List.of(fast), List.of()).isEmpty(),
                "free-flowing is no nogo, nor a slow level with no delay against free flow");
    }

    private static void theWeightSumsToTheDelay() {
        FlowSpan jam = span(0.0, 1_000.0, 10.0, 25.0, 6.0);
        check(Math.abs(NOGOS.delaySeconds(jam) - 60.0) < 1e-9, "off the route, delay is length at speed less length at free flow: " + NOGOS.delaySeconds(jam));
        check(NOGOS.delaySeconds(new FlowSpan(0.0, 100.0, null, 25.0, 10.0, Traversability.CLOSED)) == 0.0
                && NOGOS.delaySeconds(new FlowSpan(0.0, 100.0, 5.0, null, 7.0, Traversability.OPEN)) == 0.0, "and none without both speeds");
        double[][] s = straight(30.0, -97.0, 0.0, 1_000.0, 20.0);
        double[][] t = straight(30.1, -97.0, 0.0, 1_000.0, 20.0);
        List<FlowStretch> flow = List.of(new FlowStretch("One", 1_000.0, s[0], s[1], 1, List.of(jam)),
                new FlowStretch("Three", 1_000.0, t[0], t[1], 3, List.of(jam)));
        List<Nogo> nogos = NOGOS.of(flow, List.of(), null, fc -> fc == 1 ? 10.0 : 30.0, 300);
        double one = 0.0;
        double three = 0.0;
        for (Nogo nogo : nogos) {
            double charge = nogo.getWeight() * 2.0 * TrafficNogos.RADIUS_M;
            if (nogo.getLatitude() < 30.05) one += charge; else three += charge;
        }
        check(Math.abs(one - 60.0 * 10.0) < 1e-6, "the points on a span charge its delay in cost, crossed through their middles: " + one);
        check(Math.abs(three - 60.0 * 30.0) < 1e-6, "at the cost per second given for its road class: " + three);
        // Untold, the weights are P9.5's calibration at the default top speed (TrafficCost).
        List<Nogo> calibrated = NOGOS.of(flow, List.of());
        double oneDefault = 0.0;
        for (Nogo nogo : calibrated) if (nogo.getLatitude() < 30.05) oneDefault += nogo.getWeight() * 2.0 * TrafficNogos.RADIUS_M;
        check(Math.abs(oneDefault - 60.0 * TrafficCost.INSTANCE.costPerSecond(1, RouteOptions.DEFAULT_TOP_SPEED_KMH)) < 1e-6,
                "by default a second of delay costs TrafficCost's calibrated price: " + oneDefault);
    }

    private static void closuresAreHardAndJamsWeighted() {
        double[][] s = straight(30.0, -97.0, 0.0, 1_000.0, 20.0);
        FlowStretch shut = new FlowStretch("Shut", 1_000.0, s[0], s[1], 2, List.of(new FlowSpan(0.0, 1_000.0, null, 25.0, 10.0, Traversability.CLOSED)));
        FlowStretch reversible = new FlowStretch("Express", 1_000.0, s[0], s[1], 2,
                List.of(new FlowSpan(0.0, 1_000.0, null, 25.0, 10.0, Traversability.REVERSIBLE_NOT_ROUTABLE)));
        check(allHard(NOGOS.of(List.of(shut), List.of())) && allHard(NOGOS.of(List.of(reversible), List.of())),
                "a closed or reversible span is hard");
        double[][] far = straight(30.2, -97.0, 0.0, 500.0, 20.0);
        TrafficIncident closed = new TrafficIncident("a", "construction", "major", true, null, far[0], far[1]);
        TrafficIncident closure = new TrafficIncident("b", "roadClosure", "critical", false, null, far[0], far[1]);
        TrafficIncident works = new TrafficIncident("c", "construction", "minor", false, null, far[0], far[1]);
        check(allHard(NOGOS.of(List.of(), List.of(closed))) && allHard(NOGOS.of(List.of(), List.of(closure))),
                "an incident roadClosed, or of type roadClosure, is hard");
        check(NOGOS.of(List.of(), List.of(works)).isEmpty(), "roadworks with the road open are left to the flow");
        List<Nogo> both = NOGOS.of(List.of(new FlowStretch("Jam", 1_000.0, s[0], s[1], 2, List.of(span(0.0, 1_000.0, 10.0, 25.0, 6.0)))), List.of(closure));
        check(both.get(0).getHard() && !both.get(both.size() - 1).getHard(), "closures first, then jams");
    }

    private static void aShortClosureStillBlocks() {
        double[][] s = straight(30.0, -97.0, 0.0, 40.0, 20.0);
        FlowStretch shortShut = new FlowStretch("Short", 40.0, s[0], s[1], 5, List.of(new FlowSpan(0.0, 40.0, null, 10.0, 10.0, Traversability.CLOSED)));
        List<Nogo> nogos = NOGOS.of(List.of(shortShut), List.of());
        check(nogos.size() == 1 && nogos.get(0).getHard(), "a 40 m closure, all margin, still gets one hard point");
        check(Math.abs((nogos.get(0).getLatitude() - 30.0) * M - 20.0) < 0.5, "at its middle");
        FlowStretch shortJam = new FlowStretch("Short", 40.0, s[0], s[1], 5, List.of(span(0.0, 40.0, 2.0, 10.0, 9.0)));
        check(NOGOS.of(List.of(shortJam), List.of()).isEmpty(), "a 40 m jam gets none");
        double[][] l = straight(30.0, -97.0, 0.0, 500.0, 20.0);
        FlowStretch endShut = new FlowStretch("End", 500.0, l[0], l[1], 3, List.of(
                span(0.0, 480.0, 20.0, 20.0, 0.0), new FlowSpan(480.0, 500.0, null, 20.0, 10.0, Traversability.CLOSED)));
        List<Nogo> end = NOGOS.of(List.of(endShut), List.of());
        check(end.size() == 1 && end.get(0).getHard() && Math.abs((end.get(0).getLatitude() - 30.0) * M - 490.0) < 0.5,
                "a closure in the last 20 m gets its point in the middle of the closed part");
        TrafficIncident dot = new TrafficIncident("dot", "roadClosure", "critical", true, null, new double[] {30.3}, new double[] {-97.0});
        List<Nogo> single = NOGOS.of(List.of(), List.of(dot));
        check(single.size() == 1 && single.get(0).getHard() && single.get(0).getLatitude() == 30.3, "and a closure given as one point blocks there");
    }

    private static void closuresOnOneRoadAreNotDoubled() {
        double[][] s = straight(30.0, -97.0, 0.0, 1_000.0, 20.0);
        FlowStretch shut = new FlowStretch("Shut", 1_000.0, s[0], s[1], 2, List.of(new FlowSpan(0.0, 1_000.0, null, 25.0, 10.0, Traversability.CLOSED)));
        TrafficIncident closure = new TrafficIncident("b", "roadClosure", "critical", true, null, s[0], s[1]);
        List<Nogo> nogos = NOGOS.of(List.of(shut), List.of(closure));
        check(nogos.size() == 7, "a closed span and a closure on the same road give one set of points: " + nogos.size());
        for (int i = 0; i < nogos.size(); i++) for (int j = i + 1; j < nogos.size(); j++) {
            check(distance(nogos.get(i), nogos.get(j)) >= TrafficNogos.RADIUS_M, "no two hard points on top of each other");
        }
    }

    private static void aPointIsSlidOffACrossingRoad() {
        // A 420 m jam whose middle point falls exactly where a highway crosses beneath it.
        double[][] street = straight(30.0, -97.0, 90.0, 420.0, 20.0);
        double[] middle = {street[0][0], street[1][0] + 210.0 / (M * Math.cos(Math.toRadians(30.0)))};
        double[][] highway = straight(middle[0] - 1_000.0 / M, middle[1], 0.0, 2_000.0, 20.0);
        FlowStretch jam = new FlowStretch("Street", 420.0, street[0], street[1], 3, List.of(span(0.0, 420.0, 1.0, 15.0, 10.0)));
        FlowStretch road = new FlowStretch("Highway", 2_000.0, highway[0], highway[1], 1, List.of(span(0.0, 2_000.0, 30.0, 30.0, 0.0)));
        List<Nogo> alone = NOGOS.of(List.of(jam), List.of());
        List<Nogo> crossed = NOGOS.of(List.of(jam, road), List.of());
        check(alone.size() == 3 && crossed.size() == 3, "three points either way");
        boolean onCrossing = false;
        for (Nogo nogo : alone) onCrossing |= fromLine(highway, nogo.getLatitude(), nogo.getLongitude()) < 2.0;
        check(onCrossing, "alone, one would sit on the crossing");
        for (Nogo nogo : crossed) {
            check(fromLine(highway, nogo.getLatitude(), nogo.getLongitude()) >= TrafficNogos.CLEAR_M - 0.5,
                    "with the highway known, every point keeps 23 m off it: " + fromLine(highway, nogo.getLatitude(), nogo.getLongitude()));
        }
        double[][] back = reverse(street);
        FlowStretch otherWay = new FlowStretch("Street back", 420.0, back[0], back[1], 3, List.of(span(0.0, 420.0, 1.0, 15.0, 10.0)));
        check(NOGOS.of(List.of(jam, otherWay), List.of()).size() == 6, "the same road's other direction is not a crossing");
    }

    private static void theCapKeepsClosuresAndHeavyJams() {
        List<FlowStretch> flow = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            double[][] s = straight(30.0 + i * 0.01, -97.0, 0.0, 1_000.0, 20.0);
            // Delay 1,000/speed - 40, all different.
            flow.add(new FlowStretch("J" + i, 1_000.0, s[0], s[1], 2, List.of(span(0.0, 1_000.0, 2.0 + i * 0.1, 25.0, 9.0))));
        }
        List<TrafficIncident> incidents = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            double[][] s = straight(31.0 + i * 0.01, -97.0, 0.0, 400.0, 20.0);
            incidents.add(new TrafficIncident("x" + i, "roadClosure", "critical", true, null, s[0], s[1]));
        }
        List<Nogo> all = NOGOS.of(flow, incidents, null, fc -> 20.0, 100_000);
        List<Nogo> capped = NOGOS.of(flow, incidents, null, fc -> 20.0, 100);
        check(all.size() == 60 * 7 + 5 * 3 && capped.size() <= 100, "420 jam points and 15 closure points cut to at most 100: " + capped.size());
        int hard = 0;
        double lightestKept = Double.MAX_VALUE;
        Set<Double> kept = new HashSet<>();
        for (Nogo nogo : capped) {
            if (nogo.getHard()) hard++;
            else {
                lightestKept = Math.min(lightestKept, nogo.getWeight());
                kept.add(nogo.getWeight());
            }
        }
        check(hard == 15, "every closure kept");
        check(capped.size() - hard == 7 * ((100 - 15) / 7), "jams kept whole: " + (capped.size() - hard));
        double heaviestDropped = 0.0;
        for (Nogo nogo : all) if (!nogo.getHard() && !kept.contains(nogo.getWeight())) heaviestDropped = Math.max(heaviestDropped, nogo.getWeight());
        check(lightestKept > heaviestDropped, "and the heaviest ones: " + lightestKept + " > " + heaviestDropped);
        check(TrafficNogos.MAX_NOGOS == 300 && NOGOS.of(flow, incidents).size() <= 300, "the default cap");
    }

    private static void withTheRouteTheJamOnItIsKept() {
        Route route = line(30.0, -97.0, 0.0, 5_000.0, 50.0, 30.0);
        double[] along = AREA.metres(route);
        double[][] on = laid(route, along, 1_000.0, 2_000.0, 2.0, false);
        List<FlowStretch> flow = new ArrayList<>();
        flow.add(new FlowStretch("On route", 1_000.0, on[0], on[1], 2, List.of(span(0.0, 1_000.0, 20.0, 25.0, 4.5))));
        for (int i = 0; i < 60; i++) {
            double[][] s = straight(30.5 + i * 0.01, -97.0, 0.0, 1_000.0, 20.0);
            flow.add(new FlowStretch("Far " + i, 1_000.0, s[0], s[1], 2, List.of(span(0.0, 1_000.0, 2.0, 25.0, 9.0))));
        }
        List<Nogo> blind = NOGOS.of(flow, List.of(), null, fc -> 20.0, 100);
        List<Nogo> aware = NOGOS.of(flow, List.of(), route, fc -> 20.0, 100);
        check(countNear(blind, route) == 0, "without the route, a light jam on it loses to heavy ones far away");
        check(countNear(aware, route) == 7, "with it, the jam on the route is kept first: " + countNear(aware, route));
    }

    // ---- The real route ----

    private static String realRoute(File segments) throws Exception {
        File work = Files.createTempDirectory("traffic-match-test").toFile();
        Router router = new Router(segments, RoutingData.INSTANCE.installProfile(new File(work, "profiles")), 64);
        // Downtown Dallas north-west up Stemmons Freeway (I-35E) toward Love Field.
        Route route = router.compute(new RouteRequest(32.7800, -96.8050, 32.8450, -96.8850), RoutePlanner.MAX_RUNNING_MS, e -> kotlin.Unit.INSTANCE);
        double[] along = AREA.metres(route);
        // The scenario goes on the first 3 km BRouter drives at freeway speed.
        double c = -1.0;
        for (double m = 500.0; m + 3_000.0 < along[along.length - 1]; m += 100.0) {
            if (3_000.0 / (seconds(route, along, m + 3_000.0) - seconds(route, along, m)) > 22.0) {
                c = m;
                break;
            }
        }
        check(c >= 0.0, "the route has 3 km of freeway");
        scenario(route, c, "Dallas I-35E");
        List<TrafficBox> boxes = AREA.boxes(route, 0.0, 30.0);
        check(boxes.size() == 1 && AREA.acceptable(boxes.get(0)), "its 30 km is one box HERE takes: " + boxes.get(0).query());
        System.out.println("traffic-match-test: real route points=" + route.getPoints() + " distance_m=" + route.getDistanceM()
                + " seconds=" + route.getSeconds() + " scenario_from_m=" + Math.round(c));
        return "real route over W100_N30 (Dallas, I-35E)";
    }


    // ---- Code review fixes (docs/p9-lab-results.md) ----

    /**
     * Fix 4: a reversible or managed lane beside the route, the same way, is no data on the
     * route (not a closure of the road driven), and still hard points on its own shape; and a
     * closed piece shorter than [RouteTraffic.MIN_CLOSED_M] neither re-routes nor blocks the road.
     */
    private static void reviewReversibleLanesAndShortClosures() {
        Route route = line(32.80, -96.80, 0.0, 5_000.0, 50.0, 25.0);
        double[] along = AREA.metres(route);
        // HERE's line for it 12 m to the right: within the matcher's 15 m, as a managed lane's
        // separate carriageway beside the general lanes is.
        double[][] lane = laid(route, along, 1_000.0, 3_000.0, 12.0, false);
        FlowStretch express = new FlowStretch("Express", 2_000.0, lane[0], lane[1], 1,
                List.of(new FlowSpan(0.0, 2_000.0, null, 25.0, 10.0, Traversability.REVERSIBLE_NOT_ROUTABLE)));
        RouteTraffic t = MATCH.match(route, List.of(express), List.of());
        check(t.getRuns().stream().noneMatch(r -> r.getCongestion() == Congestion.CLOSED) && t.closureAhead(0.0) == null,
                "a reversible lane beside the route is no closure of it: " + describe(t.getRuns(), 0.0));
        check(allHard(NOGOS.of(List.of(express), List.of())) && !NOGOS.of(List.of(express), List.of()).isEmpty(),
                "but keeps routes off itself with hard points on its own shape");
        // The general lanes' own flow beside it still matches, whichever is nearer.
        double[][] general = laid(route, along, 1_000.0, 3_000.0, 2.0, false);
        FlowStretch lanes = new FlowStretch("I-35E", 2_000.0, general[0], general[1], 1, List.of(span(0.0, 2_000.0, 10.0, 25.0, 6.0)));
        RouteTraffic both = MATCH.match(route, List.of(express, lanes), List.of());
        check(both.getRuns().size() == 1 && both.getRuns().get(0).getCongestion() == Congestion.SLOW, "the general lanes' slow flow is what the route gets");
        List<Nogo> own = NOGOS.forRoute(List.of(express), List.of(), t, fc -> TrafficCost.INSTANCE.costPerSecond(fc, RouteOptions.DEFAULT_TOP_SPEED_KMH), TrafficNogos.MAX_NOGOS).getNogos();
        check(own.size() == NOGOS.of(List.of(express), List.of()).size()
                        && own.stream().allMatch(n -> fromRoute(route, n.getLatitude(), n.getLongitude()) > TrafficNogos.RADIUS_M),
                "and no hard point of its own on the route's line: " + own.size());

        // A closed ramp beside the road, 14 m off: within the matcher's reach for a piece or two
        // of the route only, against a closure on the road itself.
        double[][] stub = laid(route, along, 2_000.0, 2_010.0, 14.0, false);
        TrafficIncident ramp = new TrafficIncident("r", "roadClosure", "critical", true, null, stub[0], stub[1]);
        RouteTraffic shortOne = MATCH.match(route, List.of(), List.of(ramp));
        check(shortOne.getRuns().stream().anyMatch(r -> r.getCongestion() == Congestion.CLOSED && r.getToM() - r.getFromM() < RouteTraffic.MIN_CLOSED_M),
                "a closure matched for under 30 m is still a closed run to draw: " + describe(shortOne.getRuns(), 0.0));
        check(shortOne.closureAhead(0.0) == null, "but not a closure ahead to re-route round");
        List<Nogo> shortPoints = NOGOS.forRoute(List.of(), List.of(ramp), shortOne, fc -> TrafficCost.INSTANCE.costPerSecond(fc, RouteOptions.DEFAULT_TOP_SPEED_KMH),
                TrafficNogos.MAX_NOGOS).getNogos();
        check(shortPoints.size() == NOGOS.of(List.of(), List.of(ramp)).size(), "and no hard points of its own on the route's line");
        double[][] long1 = laid(route, along, 2_000.0, 2_200.0, 2.0, false);
        TrafficIncident shut = new TrafficIncident("s", "roadClosure", "critical", true, null, long1[0], long1[1]);
        RouteTraffic longOne = MATCH.match(route, List.of(), List.of(shut));
        check(longOne.closureAhead(0.0) != null && longOne.closureAhead(0.0).getToM() - longOne.closureAhead(0.0).getFromM() >= RouteTraffic.MIN_CLOSED_M,
                "a 200 m closure is one");
    }

    /** Fix 11: a slow or queuing span at 0 m/s is a queue that has stopped: a delay, and weighted points. */
    private static void reviewAStoppedQueueIsADelay() {
        Route route = line(32.80, -96.80, 0.0, 5_000.0, 50.0, 25.0);
        double[] along = AREA.metres(route);
        double[][] q = laid(route, along, 1_000.0, 2_000.0, 2.0, false);
        FlowStretch stopped = new FlowStretch("Stopped", 1_000.0, q[0], q[1], 1, List.of(span(0.0, 1_000.0, 0.0, 25.0, 10.0)));
        RouteTraffic t = MATCH.match(route, List.of(stopped), List.of());
        double delay = t.delayAheadSeconds(0.0);
        check(delay > 900.0, "a queue at 0 m/s is taken at walking pace, not as no delay: " + Math.round(delay));
        check(NOGOS.delaySeconds(stopped.getSpans().get(0)) > 900.0, "and weighed off the route too");
        check(!NOGOS.of(List.of(stopped), List.of()).isEmpty() && NOGOS.of(List.of(stopped), List.of()).stream().noneMatch(Nogo::getHard),
                "with weighted points");
    }

    // ---- Helpers ----

    private static FlowSpan span(double from, double to, double speed, double free, double jam) {
        return new FlowSpan(from, to, speed, free, jam, Traversability.OPEN);
    }

    private static void expect(TrafficRun run, Congestion level, double from, double to, String what, String shape) {
        check(run.getCongestion() == level && Math.abs(run.getFromM() - from) <= 25.0 && Math.abs(run.getToM() - to) <= 25.0,
                what + " is " + level + " from " + Math.round(from) + " to " + Math.round(to) + ": " + shape);
    }

    private static String describe(List<TrafficRun> runs, double c) {
        StringBuilder text = new StringBuilder();
        for (TrafficRun run : runs) {
            text.append(' ').append(run.getCongestion()).append(' ').append(Math.round(run.getFromM() - c)).append('-')
                    .append(Math.round(run.getToM() - c)).append(' ').append(run.getRoad()).append(';');
        }
        return text.toString();
    }

    private static boolean nothingFrom(List<TrafficRun> runs, double from, double to) {
        for (TrafficRun run : runs) if (run.getToM() > from && run.getFromM() < to) return false;
        return true;
    }

    private static boolean allHard(List<Nogo> nogos) {
        if (nogos.isEmpty()) return false;
        for (Nogo nogo : nogos) if (!nogo.getHard() || nogo.getWeight() != null) return false;
        return true;
    }

    private static int countNear(List<Nogo> nogos, Route route) {
        int count = 0;
        for (Nogo nogo : nogos) if (fromRoute(route, nogo.getLatitude(), nogo.getLongitude()) < TrafficNogos.RADIUS_M) count++;
        return count;
    }

    private static double distance(Nogo a, Nogo b) {
        return RouteFollower.Companion.distance(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
    }

    private static boolean coveredAhead(List<TrafficBox> boxes, Route route, double from, double ahead) {
        return AREA.covers(boxes, route, from, ahead);
    }

    /** A route from a start along a bearing, points [step] apart, at a steady [speed]. */
    private static Route line(double lat, double lon, double bearing, double length, double step, double speed) {
        double[][] p = straight(lat, lon, bearing, length, step);
        return timed(p[0], p[1], speed);
    }

    /**
     * A curving route near Dallas: 400 points 25 to 300 m apart, turning 0.8 deg at each, as
     * BRouter's are uneven (long straight freeway segments, short ones on curves).
     */
    private static Route synthetic() {
        double[] steps = {25.0, 60.0, 140.0, 300.0, 90.0};
        double[] lats = new double[400];
        double[] lons = new double[400];
        lats[0] = 32.80;
        lons[0] = -96.80;
        double bearing = 20.0;
        for (int i = 1; i < lats.length; i++) {
            double[] next = move(lats[i - 1], lons[i - 1], bearing, steps[i % steps.length]);
            lats[i] = next[0];
            lons[i] = next[1];
            bearing += 0.8;
        }
        return timed(lats, lons, 30.0);
    }

    private static Route timed(double[] lats, double[] lons, double speed) {
        float[] seconds = new float[lats.length];
        double metres = 0.0;
        for (int i = 1; i < lats.length; i++) {
            metres += RouteFollower.Companion.distance(lats[i - 1], lons[i - 1], lats[i], lons[i]);
            seconds[i] = (float) (metres / speed);
        }
        return new Route(lats, lons, (int) Math.round(metres), Math.round(seconds[seconds.length - 1]), new int[0], new int[0], new int[0], seconds);
    }

    /** Points from a start along a bearing, [step] apart, the last at [length]. */
    private static double[][] straight(double lat, double lon, double bearing, double length, double step) {
        int n = (int) Math.ceil(length / step) + 1;
        double[][] p = new double[2][n];
        for (int i = 0; i < n; i++) {
            double[] q = move(lat, lon, bearing, Math.min(i * step, length));
            p[0][i] = here(q[0]);
            p[1][i] = here(q[1]);
        }
        return p;
    }

    /**
     * A HERE-shaped line laid along [route] from [from] to [to] metres, a point every 20 m,
     * [lateral] metres to the right of the way it runs (negative: left), reversed when asked.
     */
    private static double[][] laid(Route route, double[] along, double from, double to, double lateral, boolean reversed) {
        int n = (int) Math.ceil((to - from) / 20.0) + 1;
        double[][] p = new double[2][n];
        for (int i = 0; i < n; i++) {
            double m = Math.min(from + i * 20.0, to);
            double[] q = at(route, along, m);
            double[] r = move(q[0], q[1], headingAt(route, along, m) + 90.0, lateral);
            p[0][i] = here(r[0]);
            p[1][i] = here(r[1]);
        }
        return reversed ? reverse(p) : p;
    }

    private static double[][] reverse(double[][] p) {
        int n = p[0].length;
        double[][] r = new double[2][n];
        for (int i = 0; i < n; i++) {
            r[0][i] = p[0][n - 1 - i];
            r[1][i] = p[1][n - 1 - i];
        }
        return r;
    }

    /** HERE gives five decimals. */
    private static double here(double degrees) {
        return Math.round(degrees * 1e5) / 1e5;
    }

    private static double[] move(double lat, double lon, double bearing, double metres) {
        double b = Math.toRadians(bearing);
        double dLat = metres * Math.cos(b) / M;
        double dLon = metres * Math.sin(b) / (M * Math.cos(Math.toRadians(lat)));
        return new double[] {lat + dLat, lon + dLon};
    }

    private static double[] at(Route route, double[] along, double m) {
        int i = segment(along, m);
        double length = along[i + 1] - along[i];
        double t = length > 0.0 ? Math.max(0.0, Math.min(1.0, (m - along[i]) / length)) : 0.0;
        return new double[] {
            route.getLatitudes()[i] + t * (route.getLatitudes()[i + 1] - route.getLatitudes()[i]),
            route.getLongitudes()[i] + t * (route.getLongitudes()[i + 1] - route.getLongitudes()[i]),
        };
    }

    private static double headingAt(Route route, double[] along, double m) {
        double[] a = at(route, along, Math.max(0.0, m - 5.0));
        double[] b = at(route, along, Math.min(along[along.length - 1], m + 5.0));
        return RouteFollower.Companion.bearing(a[0], a[1], b[0], b[1]);
    }

    private static double seconds(Route route, double[] along, double m) {
        int i = segment(along, m);
        double length = along[i + 1] - along[i];
        double t = length > 0.0 ? Math.max(0.0, Math.min(1.0, (m - along[i]) / length)) : 0.0;
        float[] s = route.getPointSeconds();
        return s[i] + t * (s[i + 1] - s[i]);
    }

    private static int segment(double[] along, double m) {
        int i = 0;
        while (i < along.length - 2 && along[i + 1] <= m) i++;
        return i;
    }

    private static double fromRoute(Route route, double lat, double lon) {
        return fromLine(new double[][] {route.getLatitudes(), route.getLongitudes()}, lat, lon);
    }

    private static double alongNearest(Route route, double[] along, double lat, double lon) {
        double best = Double.MAX_VALUE;
        double where = 0.0;
        for (int i = 0; i + 1 < route.getPoints(); i++) {
            double[] d = toSegment(lat, lon, route.getLatitudes()[i], route.getLongitudes()[i], route.getLatitudes()[i + 1], route.getLongitudes()[i + 1]);
            if (d[0] < best) {
                best = d[0];
                where = along[i] + d[1] * (along[i + 1] - along[i]);
            }
        }
        return where;
    }

    private static double fromLine(double[][] line, double lat, double lon) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < line[0].length; i++) {
            best = Math.min(best, toSegment(lat, lon, line[0][i], line[1][i], line[0][i + 1], line[1][i + 1])[0]);
        }
        return best;
    }

    /** Metres from a point to a segment, flat, and how far along the segment (0 to 1) the nearest place is. */
    private static double[] toSegment(double lat, double lon, double lat1, double lon1, double lat2, double lon2) {
        double sx = M * Math.cos(Math.toRadians(lat));
        double ax = (lon1 - lon) * sx;
        double ay = (lat1 - lat) * M;
        double dx = (lon2 - lon1) * sx;
        double dy = (lat2 - lat1) * M;
        double lengthSq = dx * dx + dy * dy;
        double t = lengthSq > 0.0 ? Math.max(0.0, Math.min(1.0, -(ax * dx + ay * dy) / lengthSq)) : 0.0;
        return new double[] {Math.hypot(ax + t * dx, ay + t * dy), t};
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
