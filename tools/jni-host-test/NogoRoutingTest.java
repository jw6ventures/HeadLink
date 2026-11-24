// P9.5's routing half on a desktop JVM (docs/navigation.md, "Live traffic (HERE)"):
// traffic no-go points through RouteRequest and OsmAndRouter into OsmAnd's router, and their
// weights in seconds per metre (TrafficCost). Both are pure Kotlin over the vendored OsmAnd jar, so
// these are the classes the app runs; nothing here says how fast a phone is.
//
// With a directory of OsmAnd maps (OBF_DIR) holding Us_texas_austin_northamerica.obf as the
// first argument it also routes for real, on I-35 and RM 2222 in Austin, and with
// Us_texas_victoria_northamerica.obf on US-290 east of Bastrop and with
// Us_texas_dallas_northamerica.obf on US-75 in Dallas: that the points reach the router, that
// a closure blocks and a jam weighs about what its delay says, the way out for a vehicle
// already on a closed road, that a jam beside a closure does not open it, that nothing leaks
// into the next request, and the overpass and frontage-road cases (docs/navigation.md, "Live
// traffic (HERE)"). Without the maps those checks are skipped and the summary line says so.
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import com.jw6ventures.headlink.Congestion;
import com.jw6ventures.headlink.FlowSpan;
import com.jw6ventures.headlink.FlowStretch;
import com.jw6ventures.headlink.Nogo;
import com.jw6ventures.headlink.OsmAndRouter;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteOptions;
import com.jw6ventures.headlink.RoutePlanner;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.RouteTraffic;
import com.jw6ventures.headlink.TrafficArea;
import com.jw6ventures.headlink.TrafficCost;
import com.jw6ventures.headlink.TrafficIncident;
import com.jw6ventures.headlink.TrafficNogos;
import com.jw6ventures.headlink.TrafficRun;
import com.jw6ventures.headlink.Traversability;
import net.osmand.util.MapUtils;

public final class NogoRoutingTest {
    private static int passed;

    private static final double RADIUS_M = TrafficNogos.RADIUS_M;

    /** I-35 northbound from downtown Austin to Round Rock: 30.6 km, 25 km of it interstate. */
    private static final double[] I35 = {30.2700, -97.7400, 30.5080, -97.6780};

    public static void main(String[] args) throws Exception {
        aRequestTakesOnlySoundPoints();
        aSecondOfDelayIsASecond();
        aRouteCarriesItsTrafficMarksThroughEveryCopy();
        String dir = args.length > 0 ? args[0] : "";
        String real = "real routes skipped (need OBF_DIR with Us_texas_austin_northamerica.obf)";
        File austin = new File(dir, "Us_texas_austin_northamerica.obf");
        if (!dir.isEmpty() && austin.isFile()) {
            List<OsmAndRouter.MapFile> maps = new ArrayList<>();
            maps.add(OsmAndRouter.MapFile.of(austin));
            File dallas = new File(dir, "Us_texas_dallas_northamerica.obf");
            if (dallas.isFile()) maps.add(OsmAndRouter.MapFile.of(dallas));
            // Bastrop and US-290 east of it are in OsmAnd's Victoria region, not Austin's.
            File victoria = new File(dir, "Us_texas_victoria_northamerica.obf");
            if (victoria.isFile()) maps.add(OsmAndRouter.MapFile.of(victoria));
            OsmAndRouter router = new OsmAndRouter(() -> maps);
            try {
                Route first = route(router, request(I35));
                Route base = route(router, request(I35, Collections.emptyList()));
                nogosReachTheRouter(router, base);
                aClosureOnTheRouteIsDrivenRound(router, base);
                aJamWeighsAboutItsDelay(router, maps);
                aVehicleOnAClosedRoadStillGetsOut(router, base);
                aJamCannotOpenAClosureBesideIt(router, base);
                aCrossStreetOverTheInterstateIsNotCharged(router, base);
                theFrontageRoadAndTheOtherCarriagewayAreNotCharged(router, base);
                manyPointsSlowTheSearch(router, base);
                nothingCarriesToTheNextRequest(router, first);
            } finally {
                router.close();
            }
            real = "real routes over " + maps.size() + " OsmAnd map(s)";
        }
        System.out.println("jni-host-test: ok " + passed + " nogo routing checks; " + real);
    }

    // ---- without maps ----

    private static void aRequestTakesOnlySoundPoints() {
        RouteRequest plain = new RouteRequest(30.27, -97.74, 30.51, -97.68);
        check(plain.getNogos().isEmpty(), "a request has no nogos unless given them");
        List<Nogo> two = List.of(new Nogo(30.3, -97.7, 8, null), new Nogo(30.31, -97.71, 8, 0.0));
        RouteRequest with = plain.withNogos(two);
        check(with.getNogos().equals(two) && with.getToLat() == 30.51 && with.getOptions().equals(plain.getOptions()),
                "withNogos changes the nogos and nothing else; a hard point and a zero weight are both sound");
        refused(new Nogo(Double.NaN, -97.7, 8, null), "a point with no latitude");
        refused(new Nogo(30.3, 181, 8, null), "a point off the globe");
        refused(new Nogo(30.3, -97.7, 0, null), "a point with no radius");
        refused(new Nogo(30.3, -97.7, Double.POSITIVE_INFINITY, null), "an endless radius");
        refused(new Nogo(30.3, -97.7, 8, -1.0), "a negative weight, which would pay the route for driving through");
        refused(new Nogo(30.3, -97.7, 8, Double.NaN), "a NaN weight, neither a closure nor a price");
        refused(new Nogo(30.3, -97.7, 8, Double.POSITIVE_INFINITY), "an infinite weight");
    }

    private static void refused(Nogo nogo, String what) {
        try {
            new RouteRequest(30.27, -97.74, 30.51, -97.68, null, new RouteOptions(), 0, null, null, List.of(nogo));
        } catch (IllegalArgumentException expected) {
            passed++;
            return;
        }
        throw new AssertionError("accepted " + what);
    }

    private static void aSecondOfDelayIsASecond() {
        check(TrafficCost.INSTANCE.classFactor(1) == TrafficCost.MOTORWAY_FACTOR && TrafficCost.INSTANCE.classFactor(2) == TrafficCost.ROAD_FACTOR
                && TrafficCost.INSTANCE.classFactor(5) == TrafficCost.ROAD_FACTOR, "class 1 carries the motorway factor, 2 to 5 the road factor");
        // A typical jam: 1 km, 3 minutes, 7 points of 8 m.
        near(TrafficCost.INSTANCE.pointWeight(180, 7, 3), 180 * TrafficCost.ROAD_FACTOR / 112, 1e-9,
                "a 3-minute arterial jam's 7 points weigh 180 s x the factor / (7 x 16 m) each");
        near(7 * 2 * RADIUS_M * TrafficCost.INSTANCE.pointWeight(180, 7, 1), 180 * TrafficCost.MOTORWAY_FACTOR, 1e-9,
                "the points sum to the delay times the factor when each is crossed through its middle");
        check(TrafficCost.INSTANCE.pointWeight(180, 0, 1) == 0.0 && TrafficCost.INSTANCE.pointWeight(-5, 7, 1) == 0.0
                && TrafficCost.INSTANCE.pointWeight(Double.NaN, 7, 1) == 0.0, "no points or no delay weighs nothing");
        near(2 * RADIUS_M * TrafficCost.INSTANCE.openedClosureWeight(RADIUS_M), TrafficCost.OPENED_CLOSURE_SECONDS, 1e-9,
                "an opened closure's point costs OPENED_CLOSURE_SECONDS to pass");
    }

    private static void aRouteCarriesItsTrafficMarksThroughEveryCopy() {
        Route plain = new Route(new double[]{30.0, 30.001, 30.002}, new double[]{-97.0, -97.0, -97.0}, 222, 20,
                new int[0], new int[0], new int[0], new float[]{0, 10, 20});
        check(plain.getNogoCount() == 0 && !plain.getTrafficShaped() && !plain.getClosuresOpened(), "a route has no traffic marks by default");
        Route marked = plain.withNogos(12, true);
        check(marked.getNogoCount() == 12 && marked.getTrafficShaped() && marked.getClosuresOpened(), "withNogos marks it");
        Route scaled = marked.withTimeScale(1.2);
        Route rest = marked.remainder(1);
        check(scaled.getNogoCount() == 12 && scaled.getClosuresOpened() && rest.getNogoCount() == 12 && rest.getClosuresOpened(),
                "the time scale and the remainder keep the marks");
    }

    // ---- real routes ----

    /** Weighted points on I-35 far enough north that the way round is the frontage road. */
    private static void nogosReachTheRouter(OsmAndRouter router, Route base) throws Exception {
        List<Nogo> heavy = points(base, 18000, 19500, 1800.0, 1);
        Route moved = route(router, request(I35, heavy));
        check(!same(moved, base), "half an hour of jam on I-35 moves the route");
        check(moved.getNogoCount() == heavy.size() + 1 && moved.getTrafficShaped() && !moved.getClosuresOpened(),
                "and the route says it was made with " + heavy.size() + " traffic nogos");
        List<Nogo> light = points(base, 18000, 19500, 5.0, 1);
        Route kept = route(router, request(I35, light));
        check(same(kept, base) && kept.getSeconds() == base.getSeconds(), "five seconds of jam leaves it where it was, at the same time");
        check(kept.getTrafficShaped(), "still marked: HERE's data was in the search either way");
        System.out.println("nogo-routing-test: i35 base_s=" + base.getSeconds() + " base_m=" + base.getDistanceM()
                + " heavy_jam_s=" + moved.getSeconds() + " light_jam_s=" + kept.getSeconds());
    }

    private static void aClosureOnTheRouteIsDrivenRound(OsmAndRouter router, Route base) throws Exception {
        List<Nogo> closed = points(base, 18000, 19500, null, 1);
        RouteRequest asked = request(I35, closed);
        Route round = route(router, asked);
        check(!same(round, base) && round.getSeconds() > base.getSeconds() && !round.getClosuresOpened(),
                "the way round is slower, and was found with the closure shut");
        double nearest = Double.MAX_VALUE;
        for (Nogo n : closed) nearest = Math.min(nearest, nearest(round, n.getLatitude(), n.getLongitude()));
        check(nearest > RADIUS_M, "it keeps out of every circle: nearest " + Math.round(nearest) + " m");
        System.out.println("nogo-routing-test: closure base_s=" + base.getSeconds() + " round_s=" + round.getSeconds()
                + " round_m=" + round.getDistanceM() + " nearest_point_m=" + Math.round(nearest));
    }

    /**
     * The way round a stretch costs some seconds (a closure there shows how many). A jam of
     * well under that must leave the route alone, and one of well over it must leave all of
     * the stretch. In between, the multiple of the way round at which the route leaves part
     * of the stretch, and all of it, is found by bisection: the table in docs/navigation.md,
     * "Live traffic (HERE)", which is what TrafficCost's class factors rest on. Every point
     * is placed and weighed by TrafficNogos.forRoute, as the app places a jam matched to the
     * route it follows.
     */
    private static void aJamWeighsAboutItsDelay(OsmAndRouter router, List<OsmAndRouter.MapFile> maps) throws Exception {
        Object[][] stretches = {
            {"I-35 north of US-183", I35, 18000.0, 19500.0, 1, "austin"},
            {"US-290 Bastrop to Giddings", new double[]{30.1100, -97.3200, 30.1830, -96.9360}, 20000.0, 21500.0, 2, "victoria"},
            // A city-grid arterial (S Congress Ave) has a way round within seconds, too
            // little to tell a jam's price from noise; RM 2222 has none so close.
            {"RM 2222 west of MoPac, Austin", new double[]{30.3498, -97.7568, 30.3860, -97.8340}, 2000.0, 3000.0, 3, "austin"},
            {"US-75 north of downtown Dallas", new double[]{32.7900, -96.7980, 33.0200, -96.7000}, 12000.0, 13500.0, 1, "dallas"},
        };
        List<String> misses = new ArrayList<>();
        for (Object[] s : stretches) {
            String region = "Us_texas_" + s[5] + "_northamerica.obf";
            if (maps.stream().noneMatch(m -> m.getName().equals(region))) {
                System.out.println("nogo-routing-test: jam " + s[0] + " skipped (no " + region + ")");
                continue;
            }
            double[] ends = (double[]) s[1];
            double from = (Double) s[2];
            double to = (Double) s[3];
            int functionalClass = (Integer) s[4];
            Route base = route(router, request(ends, Collections.emptyList()));
            Route round = route(router, request(ends, points(base, from, to, null, functionalClass)));
            double wayRound = round.getSeconds() - base.getSeconds();
            check(wayRound > 20, s[0] + ": the way round costs time: " + wayRound + " s");
            Probe small = probe(router, ends, base, from, to, functionalClass, 0.3 * wayRound);
            Probe large = probe(router, ends, base, from, to, functionalClass, 3 * wayRound);
            if (!(same(small.route, base) && small.route.getSeconds() == base.getSeconds())) {
                misses.add(s[0] + ": a jam of 0.3 x the way round (" + Math.round(0.3 * wayRound) + " s) moves the route");
            }
            if (large.out != large.points) {
                misses.add(s[0] + ": a jam of 3 x the way round (" + Math.round(3 * wayRound) + " s) leaves only " + large.out + " of " + large.points + " points");
            }
            Leaves part = leavesAt(router, ends, base, from, to, functionalClass, wayRound, false);
            Leaves all = leavesAt(router, ends, base, from, to, functionalClass, wayRound, true);
            System.out.println(String.format(Locale.ROOT, "nogo-routing-test: jam %s class=%d points=%d way_round_s=%.0f leaves_all_at_multiple=%s"
                    + " leaves_part_at_multiple=%s part_points=%d part_extra_s=%.0f part_missed_delay_s=%.0f part_price_ratio=%.2f",
                    s[0], functionalClass, large.points, wayRound, all.describe(), part.describe(), part.at.out, part.at.extraS,
                    part.at.missedDelayS, part.at.missedDelayS / part.at.extraS));
            // Every stretch is measured and printed before any tolerance fails the test.
            if (!all.bracketed()) misses.add(s[0] + ": leaving all of it is not bracketed by " + LOW_MULTIPLE + " and " + HIGH_MULTIPLE + ": " + all.describe());
            if (!part.bracketed()) misses.add(s[0] + ": leaving part of it is not bracketed by " + LOW_MULTIPLE + " and " + HIGH_MULTIPLE + ": " + part.describe());
            if (all.bracketed() && !(all.leaves >= ALL_MIN && all.leaves <= ALL_MAX)) {
                misses.add(s[0] + ": all of it is left at " + all.describe() + " x the way round, not within " + ALL_MIN + " to " + ALL_MAX);
            }
            double ratio = part.at.missedDelayS / part.at.extraS;
            if (part.bracketed() && !(ratio >= PART_RATIO_MIN && ratio <= PART_RATIO_MAX)) {
                misses.add(String.format(Locale.ROOT, "%s: the part left first misses %.0f s of delay for %.0f s more, a ratio of %.2f, not within %s to %s",
                        s[0], part.at.missedDelayS, part.at.extraS, ratio, PART_RATIO_MIN, PART_RATIO_MAX));
            }
        }
        check(misses.isEmpty(), "the class factors hold every stretch to its tolerances: " + String.join("; ", misses));
    }

    /** The bisection's bounds, multiples of the way round: both are probed before it starts. */
    private static final double LOW_MULTIPLE = 0.1;
    private static final double HIGH_MULTIPLE = 10;

    /**
     * The tolerances the class factors are held to (docs/navigation.md, "Live traffic
     * (HERE)"): the multiple at which the route leaves all of a stretch, and, where it leaves
     * part of it first, the delay it then misses per second it adds.
     */
    private static final double ALL_MIN = 0.75;
    private static final double ALL_MAX = 1.35;
    private static final double PART_RATIO_MIN = 0.75;
    private static final double PART_RATIO_MAX = 1.35;

    /** One jam's route: the points, how many the route keeps out of, what that adds, and the delay of those it misses. */
    private static final class Probe {
        Route route;
        int points;
        int out;
        double extraS;
        double missedDelayS;
    }

    /** A jam of [delay] s from [from] to [to] metres along [base], placed and weighed by TrafficNogos, routed. */
    private static Probe probe(OsmAndRouter router, double[] ends, Route base, double from, double to, int functionalClass,
            double delay) throws Exception {
        List<Nogo> jam = points(base, from, to, delay, functionalClass);
        Probe p = new Probe();
        p.route = route(router, request(ends, jam));
        p.points = jam.size();
        p.extraS = p.route.getSeconds() - base.getSeconds();
        for (Nogo n : jam) {
            if (nearest(p.route, n.getLatitude(), n.getLongitude()) > RADIUS_M) {
                p.out++;
                p.missedDelayS += n.getWeight() * 2 * RADIUS_M / TrafficCost.INSTANCE.classFactor(functionalClass);
            }
        }
        return p;
    }

    /** [leavesAt]'s answer: the highest multiple measured to stay and the lowest to leave, or why there is none. */
    private static final class Leaves {
        double stays = Double.NaN;
        double leaves = Double.NaN;
        String unbracketed;
        Probe at;

        boolean bracketed() {
            return unbracketed == null;
        }

        String describe() {
            return bracketed() ? String.format(Locale.ROOT, "%.2f", leaves) : "unbracketed(" + unbracketed + ")";
        }
    }

    /**
     * Bisects, on a log scale between [LOW_MULTIPLE] and [HIGH_MULTIPLE] of [wayRound], for
     * the jam multiple at which the route keeps out of at least one point of the stretch or,
     * with [all], every point. Both bounds are probed first: a route that already leaves at
     * the low one, or still stays at the high one, is reported unbracketed, not as a bound.
     * Eight halvings leave the answer within 2%.
     */
    private static Leaves leavesAt(OsmAndRouter router, double[] ends, Route base, double from, double to, int functionalClass,
            double wayRound, boolean all) throws Exception {
        Leaves found = new Leaves();
        Probe low = probe(router, ends, base, from, to, functionalClass, LOW_MULTIPLE * wayRound);
        Probe high = probe(router, ends, base, from, to, functionalClass, HIGH_MULTIPLE * wayRound);
        if (left(low, all)) {
            found.unbracketed = "already left at " + LOW_MULTIPLE + "x: " + low.out + " of " + low.points;
            found.at = low;
            return found;
        }
        if (!left(high, all)) {
            found.unbracketed = "still on it at " + HIGH_MULTIPLE + "x: " + high.out + " of " + high.points + " left";
            found.at = high;
            return found;
        }
        double stays = LOW_MULTIPLE;
        double leaves = HIGH_MULTIPLE;
        Probe at = high;
        for (int step = 0; step < 8; step++) {
            double m = Math.sqrt(stays * leaves);
            Probe p = probe(router, ends, base, from, to, functionalClass, m * wayRound);
            if (left(p, all)) {
                leaves = m;
                at = p;
            } else {
                stays = m;
            }
        }
        found.stays = stays;
        found.leaves = leaves;
        found.at = at;
        return found;
    }

    private static boolean left(Probe p, boolean all) {
        return all ? p.out == p.points : p.out > 0;
    }

    /**
     * A closure on I-35 from 10 to 13 km along. A vehicle already on it, between two of its
     * points, and a destination at the centre of one of its circles, must both still get a
     * route: either one marked as passing a closure (opened, or entered at an end and
     * carrying just the closures entered there), or one that keeps out of every circle
     * (OsmAnd starts the way out on the parallel road 13 m away and ends the way in there,
     * leaving the circles shut). A route from outside is still kept out.
     */
    private static void aVehicleOnAClosedRoadStillGetsOut(OsmAndRouter router, Route base) throws Exception {
        List<Nogo> closed = points(base, 10000, 13000, null, 1);
        double[] inside = pointAt(base, 11155);
        Route out = route(router, new RouteRequest(inside[0], inside[1], I35[2], I35[3], null, new RouteOptions(), 0, null, null, closed));
        check(out.getNogoCount() == closed.size() && marked(out, closed),
                "a vehicle on the closed stretch still gets a route, marked if it passes a closure: opened=" + out.getClosuresOpened()
                + " end_closures=" + out.getEndClosures().size() + " nearest_closure_m=" + Math.round(nearestNogo(out, closed)));
        double startOff = metres(out.getLatitudes()[0], out.getLongitudes()[0], inside[0], inside[1]);
        check(startOff < 30, "and its first point is where the vehicle is: " + Math.round(startOff) + " m");
        Nogo held = closed.get(closed.size() / 2);
        Route to = route(router, new RouteRequest(I35[0], I35[1], held.getLatitude(), held.getLongitude(), null, new RouteOptions(), 0, null, null, closed));
        int last = to.getPoints() - 1;
        double endOff = metres(to.getLatitudes()[last], to.getLongitudes()[last], held.getLatitude(), held.getLongitude());
        check(endOff <= OsmAndRouter.END_SNAP_M && marked(to, closed),
                "a destination inside a closure's circle is still reached, marked if it passes a closure: end "
                + Math.round(endOff) + " m off, opened=" + to.getClosuresOpened() + " end_closures=" + to.getEndClosures().size());
        Route past = route(router, request(I35, closed));
        check(!past.getClosuresOpened() && past.getEndClosures().isEmpty() && nearestNogo(past, closed) > RADIUS_M, "from outside, the closure stays shut");
        System.out.println("nogo-routing-test: stuck out_m=" + out.getDistanceM() + " out_opened=" + out.getClosuresOpened()
                + " out_end_closures=" + out.getEndClosures().size() + " out_start_off_m=" + Math.round(startOff) + " to_m=" + to.getDistanceM()
                + " to_end_off_m=" + Math.round(endOff) + " to_opened=" + to.getClosuresOpened() + " to_end_closures=" + to.getEndClosures().size()
                + " closure_points=" + closed.size() + " past_s=" + past.getSeconds() + " base_s=" + base.getSeconds());
    }

    /**
     * Whether [route] is marked as it should be against [closed]: through a closure only if
     * opened or entered at an end; the end closures, when found with them shut, only some of
     * [closed] and each at the route's first or last step, never the whole closure.
     */
    private static boolean marked(Route route, List<Nogo> closed) {
        List<Nogo> ends = route.getEndClosures();
        if (route.getClosuresOpened() && !ends.isEmpty()) return false;
        if (nearestNogo(route, closed) <= RADIUS_M && !route.getClosuresOpened() && ends.isEmpty()) return false;
        if (!closed.containsAll(ends) || (!ends.isEmpty() && ends.size() >= closed.size())) return false;
        int n = route.getPoints();
        for (Nogo nogo : ends) {
            double first = metres(route.getLatitudes()[1], route.getLongitudes()[1], nogo.getLatitude(), nogo.getLongitude())
                    + metres(route.getLatitudes()[0], route.getLongitudes()[0], nogo.getLatitude(), nogo.getLongitude());
            double lastStep = metres(route.getLatitudes()[n - 2], route.getLongitudes()[n - 2], nogo.getLatitude(), nogo.getLongitude())
                    + metres(route.getLatitudes()[n - 1], route.getLongitudes()[n - 1], nogo.getLatitude(), nogo.getLongitude());
            double firstLen = metres(route.getLatitudes()[0], route.getLongitudes()[0], route.getLatitudes()[1], route.getLongitudes()[1]);
            double lastLen = metres(route.getLatitudes()[n - 2], route.getLongitudes()[n - 2], route.getLatitudes()[n - 1], route.getLongitudes()[n - 1]);
            // Inside the step's ellipse widened by the circle: the circle reaches that step.
            if (first > firstLen + 2 * nogo.getRadiusM() && lastStep > lastLen + 2 * nogo.getRadiusM()) return false;
        }
        return true;
    }

    /**
     * A closure on I-35 at about 10.4 km along and a light jam just beyond it on the same
     * road: the jam's price must not open the closure.
     */
    private static void aJamCannotOpenAClosureBesideIt(OsmAndRouter router, Route base) throws Exception {
        List<Nogo> closure = points(base, 10370, 10730, null, 1);
        List<Nogo> both = new ArrayList<>(closure);
        both.addAll(points(base, 10850, 11500, 20.0, 1));
        Route kept = route(router, request(I35, both));
        check(nearestNogo(kept, closure) > RADIUS_M && !kept.getClosuresOpened(),
                "the route keeps out of the closure all the same: nearest " + Math.round(nearestNogo(kept, closure)) + " m");
    }

    /**
     * A street crossing over I-35 about 3.3 km along, and another about 2.3 km along, with no
     * junction on the main lanes. A HERE stretch ends at each; with the 30 m margins no point
     * reaches either street. Were a point to land on a crossing (a stretch not ending there),
     * the street would be charged: how often that happens with 150 m spacing is measured.
     */
    private static void aCrossStreetOverTheInterstateIsNotCharged(OsmAndRouter router, Route base) throws Exception {
        double[] streetEnds = {30.2900, -97.7350, 30.2900, -97.6850};
        double[] otherEnds = {30.2800, -97.7350, 30.2800, -97.6850};
        Route street = route(router, request(streetEnds, Collections.emptyList()));
        Route other = route(router, request(otherEnds, Collections.emptyList()));
        double over = crossing(base, street);
        double under = crossing(base, other);
        check(over > 0 && under > 0 && over > under, "both streets cross I-35 in the plane: at " + Math.round(under) + " and " + Math.round(over) + " m");
        check(sharesNoPoint(base, street) && sharesNoPoint(base, other), "neither shares a point with the main lanes: they are grade-separated");
        List<Nogo> jam = points(base, under, over, 180.0, 1);
        double[][] ends = {streetEnds, otherEnds};
        Route[] crosses = {street, other};
        for (int i = 0; i < crosses.length; i++) {
            double nearest = nearestNogo(crosses[i], jam);
            check(nearest > RADIUS_M, "with the stretch ending at the crossing, the street is " + Math.round(nearest) + " m from the nearest point");
            Route again = route(router, request(ends[i], jam));
            check(same(again, crosses[i]) && again.getSeconds() == crosses[i].getSeconds(), "and it is routed exactly as without the jam");
        }
        // The worst case: a point right on the crossing.
        double[] at = pointAt(base, over);
        List<Nogo> onIt = List.of(new Nogo(at[0], at[1], RADIUS_M, TrafficCost.INSTANCE.pointWeight(180, 7, 1)));
        check(nearestNogo(street, onIt) < RADIUS_M, "a point right on the crossing reaches the street, as circles are flat");
        Route charged = route(router, request(streetEnds, onIt));
        // How often a stretch running through the crossing puts a point on it.
        int hits = 0;
        for (int offset = 0; offset < TrafficNogos.SPACING_M; offset++) {
            for (Nogo n : points(base, over - 1000 + offset, over + 1000 + offset, 60.0, 1)) {
                if (nearestNogo(street, List.of(n)) < RADIUS_M) {
                    hits++;
                    break;
                }
            }
        }
        System.out.println("nogo-routing-test: overpass crossings_m=" + Math.round(under) + "," + Math.round(over)
                + " point_on_crossing_diverts=" + !same(charged, street) + " stretches_through_it_charging_the_street="
                + hits + "/" + (int) TrafficNogos.SPACING_M);
    }

    /**
     * A jam on the northbound main lanes between two interchanges, 4.4 to 7.6 km along, and
     * two routes beside it: the northbound frontage road and the southbound main lanes.
     * Neither is inside a circle, so neither is charged or moved.
     */
    private static void theFrontageRoadAndTheOtherCarriagewayAreNotCharged(OsmAndRouter router, Route base) throws Exception {
        List<Nogo> jam = points(base, 4364, 7656, 180.0, 1);
        StringBuilder line = new StringBuilder("nogo-routing-test: beside");
        double[][] beside = {{30.2965, -97.7170, 30.3165, -97.7065}, {30.3150, -97.7080, 30.2950, -97.7190}};
        String[] names = {"northbound frontage road", "southbound main lanes"};
        for (int i = 0; i < beside.length; i++) {
            Route plain = route(router, request(beside[i], Collections.emptyList()));
            Route with = route(router, request(beside[i], jam));
            double nearest = nearestNogo(plain, jam);
            check(nearest > RADIUS_M, "the " + names[i] + " passes " + Math.round(nearest * 10) / 10.0 + " m from the nearest point");
            check(same(with, plain) && with.getSeconds() == plain.getSeconds(), "and is routed exactly as without the jam");
            line.append(' ').append(names[i].replace(' ', '_')).append("_nearest_m=").append(Math.round(nearest * 10) / 10.0);
        }
        Route on = route(router, request(new double[]{30.2960, -97.7180, 30.3160, -97.7075}, Collections.emptyList()));
        check(nearestNogo(on, jam) < RADIUS_M, "a northbound trip on the main lanes is the one that passes through them");
        System.out.println(line);
    }

    /**
     * The search checks every point for every piece of road it weighs: relative host timings,
     * not the phone's. Every request carries [CONTROL] too, so "0" is the plain search alone.
     */
    private static void manyPointsSlowTheSearch(OsmAndRouter router, Route base) throws Exception {
        StringBuilder line = new StringBuilder("nogo-routing-test: timing_ms");
        for (int n : new int[]{0, 50, 200, 400}) {
            List<Nogo> off = new ArrayList<>();
            // About 70 m north-east or south-west of the route, whichever keeps 30 m clear of
            // it where it bends back: in every link's neighbourhood, charging none.
            for (int k = 0; k < n; k++) {
                double[] p = pointAt(base, 100 + (base.getDistanceM() - 200.0) * k / n);
                for (double side : new double[]{0.0005, -0.0005}) {
                    if (nearest(base, p[0] + side, p[1] + side) > 30) {
                        off.add(new Nogo(p[0] + side, p[1] + side, RADIUS_M, 1.0));
                        break;
                    }
                }
            }
            long best = Long.MAX_VALUE;
            Route last = null;
            for (int run = 0; run < 2; run++) {
                long start = System.nanoTime();
                last = route(router, request(I35, off));
                best = Math.min(best, (System.nanoTime() - start) / 1_000_000);
            }
            check(same(last, base) && last.getSeconds() == base.getSeconds(), n + " points beside the route leave it alone");
            line.append(' ').append(n).append('=').append(best);
        }
        System.out.println(line);
    }

    /** [first] is the route asked for before any other, with no nogos at all (the highway hierarchy's). */
    private static void nothingCarriesToTheNextRequest(OsmAndRouter router, Route first) throws Exception {
        Route after = route(router, request(I35));
        check(Arrays.equals(after.getLatitudes(), first.getLatitudes()) && Arrays.equals(after.getLongitudes(), first.getLongitudes())
                && after.getSeconds() == first.getSeconds() && after.getNogoCount() == 0 && !after.getTrafficShaped(),
                "a request without nogos after all of these is routed as the first one was");
    }

    // ---- helpers ----

    /**
     * A weighted point far from every route here. OsmAndRouter takes the highway hierarchy
     * for a request with no nogos and its plain search for one with any, and the two can
     * choose different lanes of the same road; [request] adds this to every list, so a route
     * with traffic is compared with one from the same search.
     */
    private static final Nogo CONTROL = new Nogo(31.5, -99.5, RADIUS_M, 1.0);

    private static RouteRequest request(double[] ends, List<Nogo> nogos) {
        List<Nogo> all = new ArrayList<>(nogos);
        all.add(CONTROL);
        return new RouteRequest(ends[0], ends[1], ends[2], ends[3], null, new RouteOptions(), 0, null, null, all);
    }

    /** The trip with no nogos at all, as a route without traffic is asked for. */
    private static RouteRequest request(double[] ends) {
        return new RouteRequest(ends[0], ends[1], ends[2], ends[3]);
    }

    private static Route route(OsmAndRouter router, RouteRequest request) throws Exception {
        return router.compute(request, RoutePlanner.MAX_RUNNING_MS, e -> kotlin.Unit.INSTANCE);
    }

    /**
     * The points TrafficNogos.forRoute puts on [route] for one run of it from [from] to [to]
     * metres, as the app places them on the route it follows: a closure's hard points with
     * no [delay], else a queue's weighted ones charging [delay] s at HERE [functionalClass]'s
     * factor. The one HERE stretch handed with it ([FAR]) is free-flowing and far away, so
     * nothing crosses the run and nothing else is placed.
     */
    private static List<Nogo> points(Route route, double from, double to, Double delay, int functionalClass) {
        double[] along = TrafficArea.INSTANCE.metres(route);
        TrafficRun run = new TrafficRun(index(along, from), index(along, to) + 1, from, to,
                delay == null ? Congestion.CLOSED : Congestion.QUEUING, delay == null ? null : 1.0,
                delay == null ? 0.0 : delay, "test", functionalClass);
        RouteTraffic traffic = new RouteTraffic(route, List.of(run), List.<TrafficIncident>of());
        return TrafficNogos.INSTANCE.forRoute(List.of(FAR), List.<TrafficIncident>of(), traffic, TrafficNogos.MAX_NOGOS).getNogos();
    }

    /** A free-flowing HERE stretch far from every route here, so [points] has a response to place against. */
    private static final FlowStretch FAR = new FlowStretch("far", 111.0, new double[]{31.6, 31.601}, new double[]{-99.6, -99.6}, 3,
            List.of(new FlowSpan(0.0, 111.0, 25.0, 25.0, 0.0, Traversability.OPEN)));

    /** The route point at or before [metres] along it. */
    private static int index(double[] along, double metres) {
        int i = 0;
        while (i + 2 < along.length && along[i + 1] <= metres) i++;
        return i;
    }

    /** The point [metres] along [route]. */
    private static double[] pointAt(Route route, double metres) {
        double[] lat = route.getLatitudes();
        double[] lon = route.getLongitudes();
        double at = 0;
        for (int i = 1; i < lat.length; i++) {
            double d = metres(lat[i - 1], lon[i - 1], lat[i], lon[i]);
            if (at + d >= metres && d > 0) {
                double f = (metres - at) / d;
                return new double[]{lat[i - 1] + f * (lat[i] - lat[i - 1]), lon[i - 1] + f * (lon[i] - lon[i - 1])};
            }
            at += d;
        }
        return new double[]{lat[lat.length - 1], lon[lon.length - 1]};
    }

    /** Metres between two points as OsmAnd measures them, so distances along a route match its own. */
    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        return MapUtils.getDistance(lat1, lon1, lat2, lon2);
    }

    /** Metres from the point to the nearest piece of [route]. */
    private static double nearest(Route route, double lat, double lon) {
        double[] la = route.getLatitudes();
        double[] lo = route.getLongitudes();
        double kx = 111_320 * Math.cos(Math.toRadians(lat));
        double best = Double.MAX_VALUE;
        for (int i = 1; i < la.length; i++) {
            double x1 = (lo[i - 1] - lon) * kx;
            double y1 = (la[i - 1] - lat) * 111_320;
            double dx = (lo[i] - lo[i - 1]) * kx;
            double dy = (la[i] - la[i - 1]) * 111_320;
            double l2 = dx * dx + dy * dy;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(x1 * dx + y1 * dy) / l2));
            best = Math.min(best, Math.hypot(x1 + t * dx, y1 + t * dy));
        }
        return best;
    }

    private static double nearestNogo(Route route, List<Nogo> nogos) {
        double best = Double.MAX_VALUE;
        for (Nogo n : nogos) best = Math.min(best, nearest(route, n.getLatitude(), n.getLongitude()));
        return best;
    }

    /** Metres along [main] where [cross] first crosses it in the plane, or -1. */
    private static double crossing(Route main, Route cross) {
        double[] ma = main.getLatitudes();
        double[] mo = main.getLongitudes();
        double[] ca = cross.getLatitudes();
        double[] co = cross.getLongitudes();
        double kx = 111_320 * Math.cos(Math.toRadians(ma[0]));
        double along = 0;
        for (int j = 1; j < ma.length; j++) {
            double ux = mo[j - 1] * kx;
            double uy = ma[j - 1] * 111_320;
            double vx = mo[j] * kx - ux;
            double vy = ma[j] * 111_320 - uy;
            for (int i = 1; i < ca.length; i++) {
                double px = co[i - 1] * kx;
                double py = ca[i - 1] * 111_320;
                double qx = co[i] * kx - px;
                double qy = ca[i] * 111_320 - py;
                double d = qx * vy - qy * vx;
                if (d == 0) continue;
                double s = ((ux - px) * vy - (uy - py) * vx) / d;
                double t = ((ux - px) * qy - (uy - py) * qx) / d;
                if (s > 0 && s < 1 && t > 0 && t < 1) return along + t * metres(ma[j - 1], mo[j - 1], ma[j], mo[j]);
            }
            along += metres(ma[j - 1], mo[j - 1], ma[j], mo[j]);
        }
        return -1;
    }

    private static boolean sharesNoPoint(Route a, Route b) {
        for (int i = 0; i < a.getPoints(); i++) {
            for (int j = 0; j < b.getPoints(); j++) {
                if (a.getLatitudes()[i] == b.getLatitudes()[j] && a.getLongitudes()[i] == b.getLongitudes()[j]) return false;
            }
        }
        return true;
    }

    /**
     * Whether two routes drive the same roads: every point of each within 1 m of the other.
     * Tight enough that a parallel lane or frontage road (10 m off or more here) is another
     * route; loose enough for the snapped start and end, which move with the search's prices.
     */
    private static boolean same(Route a, Route b) {
        return within(a, b, 1) && within(b, a, 1);
    }

    private static boolean within(Route a, Route b, double metres) {
        for (int i = 0; i < a.getPoints(); i++) {
            if (nearest(b, a.getLatitudes()[i], a.getLongitudes()[i]) > metres) return false;
        }
        return true;
    }

    private static void near(double actual, double expected, double tolerance, String what) {
        check(Math.abs(actual - expected) <= tolerance, what + ": " + actual);
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
