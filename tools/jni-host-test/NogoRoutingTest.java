// P9.5's routing half on a desktop JVM (docs/navigation.md, "Live traffic (HERE)"):
// traffic no-go points through RouteRequest and OsmAndRouter into OsmAnd's router, and the
// cost conversion in TrafficCost. Both are pure Kotlin over the vendored OsmAnd jar, so
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
import com.jw6ventures.headlink.Nogo;
import com.jw6ventures.headlink.OsmAndRouter;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteOptions;
import com.jw6ventures.headlink.RoutePlanner;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.TrafficCost;
import net.osmand.util.MapUtils;

public final class NogoRoutingTest {
    private static int passed;

    /** The placement rule (docs/navigation.md, "Live traffic (HERE)"), as TrafficNogos applies it. */
    private static final double SPACING_M = 150;
    private static final double RADIUS_M = 8;
    private static final double MARGIN_M = 30;

    /** I-35 northbound from downtown Austin to Round Rock: 30.6 km, 25 km of it interstate. */
    private static final double[] I35 = {30.2700, -97.7400, 30.5080, -97.6780};

    public static void main(String[] args) throws Exception {
        aRequestTakesOnlySoundPoints();
        aSecondOfDelayIsPricedAtTheTopSpeed();
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

    private static void aSecondOfDelayIsPricedAtTheTopSpeed() {
        // KinematicModel.init with car-vario's f_roll 232, f_air 0.4, p_standby 250: the units
        // TrafficCost still prices in, which OsmAndRouter turns back into seconds.
        near(TrafficCost.INSTANCE.secondPrice(121), 18.98, 0.01, "a second costs pw / cost0 = 18.98 at 121 km/h");
        near(TrafficCost.INSTANCE.secondPrice(90), 12.47, 0.01, "12.47 at 90 km/h");
        near(TrafficCost.INSTANCE.secondPrice(137), 22.26, 0.01, "22.26 at 137 km/h");
        check(TrafficCost.INSTANCE.costPerSecond(1) == TrafficCost.INSTANCE.secondPrice(121) * TrafficCost.MAJOR_ROAD_LINK_FACTOR
                && TrafficCost.INSTANCE.costPerSecond(2) == TrafficCost.INSTANCE.costPerSecond(1),
                "classes 1 and 2 carry the measured link factor, at the default top speed");
        check(TrafficCost.INSTANCE.costPerSecond(3) == TrafficCost.INSTANCE.secondPrice(121)
                && TrafficCost.INSTANCE.costPerSecond(5, 90) == TrafficCost.INSTANCE.secondPrice(90),
                "classes 3 to 5 are the plain price, at the speed asked for");
        // A typical jam: 1 km, 3 minutes, 7 points of 8 m.
        near(TrafficCost.INSTANCE.pointWeight(180, 7, 1), 48.8, 0.1, "a 3-minute interstate jam's 7 points weigh 48.8 each");
        near(TrafficCost.INSTANCE.pointWeight(180, 7, 3), 30.5, 0.1, "on an arterial, 30.5");
        near(7 * 2 * RADIUS_M * TrafficCost.INSTANCE.pointWeight(180, 7, 3), 180 * TrafficCost.INSTANCE.secondPrice(121), 1e-6,
                "the points sum to the delay's cost when each is crossed through its middle");
        check(TrafficCost.INSTANCE.pointWeight(180, 0, 1) == 0.0 && TrafficCost.INSTANCE.pointWeight(-5, 7, 1) == 0.0
                && TrafficCost.INSTANCE.pointWeight(Double.NaN, 7, 1) == 0.0, "no points or no delay weighs nothing");
        near(2 * RADIUS_M * TrafficCost.INSTANCE.openedClosureWeight(RADIUS_M, 121), 300 * 18.98, 5,
                "an opened closure's point costs five minutes to pass");
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
        List<Nogo> heavy = placed(base, 18000, 19500, TrafficCost.INSTANCE.pointWeight(1800, count(18000, 19500), 1));
        Route moved = route(router, request(I35, heavy));
        check(!same(moved, base), "half an hour of jam on I-35 moves the route");
        check(moved.getNogoCount() == heavy.size() + 1 && moved.getTrafficShaped() && !moved.getClosuresOpened(),
                "and the route says it was made with " + heavy.size() + " traffic nogos");
        List<Nogo> light = placed(base, 18000, 19500, TrafficCost.INSTANCE.pointWeight(5, count(18000, 19500), 1));
        Route kept = route(router, request(I35, light));
        check(same(kept, base) && kept.getSeconds() == base.getSeconds(), "five seconds of jam leaves it where it was, at the same time");
        check(kept.getTrafficShaped(), "still marked: HERE's data was in the search either way");
        System.out.println("nogo-routing-test: i35 base_s=" + base.getSeconds() + " base_m=" + base.getDistanceM()
                + " heavy_jam_s=" + moved.getSeconds() + " light_jam_s=" + kept.getSeconds());
    }

    private static void aClosureOnTheRouteIsDrivenRound(OsmAndRouter router, Route base) throws Exception {
        List<Nogo> closed = placed(base, 18000, 19500, null);
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
     * well under that must leave the route alone, and one of well over it must move it:
     * TrafficCost's conversion within the measured spread (docs/navigation.md, "Live traffic
     * (HERE)").
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
            Route round = route(router, request(ends, placed(base, from, to, null)));
            double wayRound = round.getSeconds() - base.getSeconds();
            check(wayRound > 20, s[0] + ": the way round costs time: " + wayRound + " s");
            int n = count(from, to);
            Route small = route(router, request(ends, placed(base, from, to, TrafficCost.INSTANCE.pointWeight(0.3 * wayRound, n, functionalClass))));
            Route large = route(router, request(ends, placed(base, from, to, TrafficCost.INSTANCE.pointWeight(3 * wayRound, n, functionalClass))));
            check(same(small, base) && small.getSeconds() == base.getSeconds(),
                    s[0] + ": a jam of 0.3 x the way round (" + Math.round(0.3 * wayRound) + " s) stays on the road");
            check(!same(large, base), s[0] + ": a jam of 3 x the way round (" + Math.round(3 * wayRound) + " s) goes round");
            System.out.println("nogo-routing-test: jam " + s[0] + " class=" + functionalClass + " points=" + n
                    + " way_round_s=" + Math.round(wayRound) + " stays_at_s=" + Math.round(0.3 * wayRound) + " leaves_at_s=" + Math.round(3 * wayRound));
        }
    }

    /**
     * A closure on I-35 from 10 to 13 km along. A vehicle already on it, between two of its
     * points, and a destination at the centre of one of its circles, must both still get a
     * route: either one marked as passing a closure, or one that keeps out of every circle
     * (OsmAnd starts the way out on the parallel road 13 m away and ends the way in there,
     * leaving the circles shut). A route from outside is still kept out.
     */
    private static void aVehicleOnAClosedRoadStillGetsOut(OsmAndRouter router, Route base) throws Exception {
        List<Nogo> closed = placed(base, 10000, 13000, null);
        double[] inside = pointAt(base, 11155);
        Route out = route(router, new RouteRequest(inside[0], inside[1], I35[2], I35[3], null, new RouteOptions(), 0, null, null, closed));
        check(out.getNogoCount() == closed.size() && (out.getClosuresOpened() || nearestNogo(out, closed) > RADIUS_M),
                "a vehicle on the closed stretch still gets a route, marked if it passes a closure: opened=" + out.getClosuresOpened()
                + " nearest_closure_m=" + Math.round(nearestNogo(out, closed)));
        double startOff = metres(out.getLatitudes()[0], out.getLongitudes()[0], inside[0], inside[1]);
        check(startOff < 30, "and its first point is where the vehicle is: " + Math.round(startOff) + " m");
        Nogo held = closed.get(closed.size() / 2);
        Route to = route(router, new RouteRequest(I35[0], I35[1], held.getLatitude(), held.getLongitude(), null, new RouteOptions(), 0, null, null, closed));
        int last = to.getPoints() - 1;
        double endOff = metres(to.getLatitudes()[last], to.getLongitudes()[last], held.getLatitude(), held.getLongitude());
        check(endOff <= OsmAndRouter.END_SNAP_M && (to.getClosuresOpened() || nearestNogo(to, closed) > RADIUS_M),
                "a destination inside a closure's circle is still reached, marked if it passes a closure: end "
                + Math.round(endOff) + " m off, opened=" + to.getClosuresOpened());
        Route past = route(router, request(I35, closed));
        check(!past.getClosuresOpened() && nearestNogo(past, closed) > RADIUS_M, "from outside, the closure stays shut");
        System.out.println("nogo-routing-test: stuck out_m=" + out.getDistanceM() + " out_opened=" + out.getClosuresOpened()
                + " out_start_off_m=" + Math.round(startOff) + " to_m=" + to.getDistanceM() + " to_end_off_m=" + Math.round(endOff)
                + " to_opened=" + to.getClosuresOpened() + " past_s=" + past.getSeconds() + " base_s=" + base.getSeconds());
    }

    /**
     * A closure on I-35 at about 10.4 km along and a light jam just beyond it on the same
     * road: the jam's price must not open the closure.
     */
    private static void aJamCannotOpenAClosureBesideIt(OsmAndRouter router, Route base) throws Exception {
        List<Nogo> closure = placed(base, 10370, 10730, null);
        List<Nogo> both = new ArrayList<>(closure);
        both.addAll(placed(base, 10850, 11500, TrafficCost.INSTANCE.pointWeight(20, count(10850, 11500), 1)));
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
        List<Nogo> jam = placed(base, under, over, TrafficCost.INSTANCE.pointWeight(180, count(under, over), 1));
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
        for (int offset = 0; offset < SPACING_M; offset++) {
            for (Nogo n : placed(base, over - 1000 + offset, over + 1000 + offset, 1.0)) {
                if (nearestNogo(street, List.of(n)) < RADIUS_M) {
                    hits++;
                    break;
                }
            }
        }
        System.out.println("nogo-routing-test: overpass crossings_m=" + Math.round(under) + "," + Math.round(over)
                + " point_on_crossing_diverts=" + !same(charged, street) + " stretches_through_it_charging_the_street="
                + hits + "/" + (int) SPACING_M);
    }

    /**
     * A jam on the northbound main lanes between two interchanges, 4.4 to 7.6 km along, and
     * two routes beside it: the northbound frontage road and the southbound main lanes.
     * Neither is inside a circle, so neither is charged or moved.
     */
    private static void theFrontageRoadAndTheOtherCarriagewayAreNotCharged(OsmAndRouter router, Route base) throws Exception {
        List<Nogo> jam = placed(base, 4364, 7656, TrafficCost.INSTANCE.pointWeight(180, count(4364, 7656), 1));
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
            // About 70 m north-east of the route: in every link's neighbourhood, charging none.
            for (int k = 0; k < n; k++) {
                double[] p = pointAt(base, 100 + (base.getDistanceM() - 200.0) * k / n);
                off.add(new Nogo(p[0] + 0.0005, p[1] + 0.0005, RADIUS_M, 1.0));
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

    /** How many points the placement rule puts on a stretch from [from] to [to] metres. */
    private static int count(double from, double to) {
        double room = to - from - 2 * MARGIN_M;
        return room <= 0 ? 0 : (int) Math.ceil(room / SPACING_M - 1e-9) + 1;
    }

    /** Points the way the placement rule puts them on a stretch of [route] from [from] to [to] metres: evenly, at most 150 m apart, 30 m clear of each end. */
    private static List<Nogo> placed(Route route, double from, double to, Double weight) {
        int n = count(from, to);
        List<Nogo> nogos = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            double at = n == 1 ? (from + to) / 2 : from + MARGIN_M + (to - from - 2 * MARGIN_M) * k / (n - 1);
            double[] p = pointAt(route, at);
            nogos.add(new Nogo(p[0], p[1], RADIUS_M, weight));
        }
        return nogos;
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
