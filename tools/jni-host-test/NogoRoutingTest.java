// P9.5's routing half on a desktop JVM (docs/navigation.md, "Live traffic (HERE)"):
// traffic no-go points through RouteRequest and Router into the vendored BRouter, and the
// cost conversion in TrafficCost. Router and TrafficCost are pure Kotlin over the BRouter
// jar, so these are the classes the app runs; nothing here says how fast a phone is.
//
// With a directory holding W100_N30.rd5 as the first argument it also routes for real, on
// I-35 through Austin and on roads in Dallas and rural Texas: that the points reach BRouter,
// that a closure blocks and a jam weighs about what its delay says, the way out for a
// vehicle already on a closed road, that nothing leaks into the next request, BRouter's
// habit of letting a jam's point overwrite a closure's, and the overpass and
// frontage-road cases (docs/navigation.md, "Live traffic (HERE)"). With W105_N30.rd5 as well, that the top-speed step-down keeps the
// points. Without the tiles those checks are skipped and the summary line says so.
import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.router.RoutingParamCollector;
import btools.util.CheapRuler;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import com.jw6ventures.headlink.Nogo;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteOptions;
import com.jw6ventures.headlink.RoutePlanner;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.Router;
import com.jw6ventures.headlink.RoutingData;
import com.jw6ventures.headlink.TrafficCost;

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
        aSecondOfDelayIsPricedAsBRouterPricesItsOwnStops();
        aRouteCarriesItsTrafficMarksThroughEveryCopy();
        aClosureIsCrossedOnlyInsideItsCircle();
        String segments = args.length > 0 ? args[0] : "";
        String real = "real routes skipped (no tile directory given)";
        if (!segments.isEmpty() && new File(segments, "W100_N30.rd5").isFile()) {
            File work = Files.createTempDirectory("nogo-routing-test").toFile();
            File profile = RoutingData.INSTANCE.installProfile(new File(work, "profiles"));
            Router router = new Router(new File(segments), profile, 64);
            Route base = route(router, request(I35, Collections.emptyList()));
            nogosReachBRouter(router, base);
            aClosureOnTheRouteIsDrivenRound(router, base);
            aJamWeighsAboutItsDelay(router);
            aVehicleOnAClosedRoadStillGetsOut(router, base);
            aJamCannotOpenAClosureOnTheSameLink(router, base, profile, new File(segments));
            aCrossStreetOverTheInterstateIsNotCharged(router, base);
            theFrontageRoadAndTheOtherCarriagewayAreNotCharged(router, base);
            manyPointsSlowTheSearch(router, base);
            nothingCarriesToTheNextRequest(router, base);
            real = "real routes over W100_N30";
            if (new File(segments, "W105_N30.rd5").isFile()) {
                theSpeedStepDownKeepsThePoints(router);
                real += " and Austin to Lubbock";
            }
        }
        System.out.println("jni-host-test: ok " + passed + " nogo routing checks; " + real);
    }

    // ---- without tiles ----

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
        refused(new Nogo(30.3, -97.7, 8, -1.0), "a negative weight, which BRouter would pay the route for");
        refused(new Nogo(30.3, -97.7, 8, Double.NaN), "a NaN weight, which BRouter reads as a closure");
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

    private static void aSecondOfDelayIsPricedAsBRouterPricesItsOwnStops() {
        // KinematicModel.init with car-vario's f_roll 232, f_air 0.4, p_standby 250.
        near(TrafficCost.INSTANCE.secondPrice(121), 18.98, 0.01, "a second costs pw / cost0 = 18.98 at 121 km/h");
        near(TrafficCost.INSTANCE.secondPrice(90), 12.47, 0.01, "12.47 at the profile's own 90 km/h");
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
        Route named = marked.withRoads(new int[]{0}, new String[]{"I 35"});
        Route rest = marked.remainder(1);
        check(scaled.getNogoCount() == 12 && scaled.getClosuresOpened() && named.getNogoCount() == 12 && named.getClosuresOpened()
                && rest.getNogoCount() == 12 && rest.getClosuresOpened(), "the time scale, the names and the remainder keep the marks");
    }

    private static void aClosureIsCrossedOnlyInsideItsCircle() {
        // A straight 222 m route north along -97.0.
        Route line = new Route(new double[]{30.0, 30.001, 30.002}, new double[]{-97.0, -97.0, -97.0}, 222, 20,
                new int[0], new int[0], new int[0], new float[]{0, 10, 20});
        RouteRequest asked = new RouteRequest(30.0, -97.0, 30.002, -97.0);
        double eastPerMetre = 1 / (111_320 * Math.cos(Math.toRadians(30.0)));
        Nogo on = new Nogo(30.0015, -97.0, 8, null);
        Nogo beside = new Nogo(30.0015, -97.0 + 10 * eastPerMetre, 8, null);
        Nogo weighted = new Nogo(30.0015, -97.0, 8, 50.0);
        Nogo atStart = new Nogo(30.0, -97.0 + 3 * eastPerMetre, 8, null);
        List<Nogo> crossed = Router.Companion.closuresCrossed(line, asked, List.of(on, beside, weighted, atStart));
        check(crossed.equals(List.of(on)), "only the closure the route passes inside is crossed; one 10 m off is not, a jam "
                + "is not a closure, and one holding the start was set aside by BRouter itself: " + crossed);
    }

    // ---- real routes ----

    /** Weighted points on I-35 far enough north that the way round is the frontage road. */
    private static void nogosReachBRouter(Router router, Route base) throws Exception {
        List<Nogo> heavy = placed(base, 18000, 19500, TrafficCost.INSTANCE.pointWeight(1800, count(18000, 19500), 1));
        Route moved = route(router, request(I35, heavy));
        check(!same(moved, base), "half an hour of jam on I-35 moves the route");
        check(moved.getNogoCount() == heavy.size() && moved.getTrafficShaped() && !moved.getClosuresOpened(),
                "and the route says it was made with " + heavy.size() + " traffic nogos");
        List<Nogo> light = placed(base, 18000, 19500, TrafficCost.INSTANCE.pointWeight(5, count(18000, 19500), 1));
        Route kept = route(router, request(I35, light));
        check(same(kept, base) && kept.getSeconds() == base.getSeconds(), "five seconds of jam leaves it where it was");
        check(kept.getTrafficShaped(), "still marked: HERE's data was in the search either way");
    }

    private static void aClosureOnTheRouteIsDrivenRound(Router router, Route base) throws Exception {
        List<Nogo> closed = placed(base, 18000, 19500, null);
        RouteRequest asked = request(I35, closed);
        Route round = route(router, asked);
        check(Router.Companion.closuresCrossed(round, asked, closed).isEmpty(), "a closure on I-35 is not driven through");
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
     * TrafficCost's conversion within the measured spread (docs/navigation.md, "Live traffic (HERE)")
     * (the delay at which BRouter left came to 0.4 to 2.1 times the way round's time).
     */
    private static void aJamWeighsAboutItsDelay(Router router) throws Exception {
        Object[][] stretches = {
            {"I-35 north of US-183", I35, 18000.0, 19500.0, 1},
            {"US-290 Bastrop to Giddings", new double[]{30.1100, -97.3200, 30.1830, -96.9360}, 20000.0, 21500.0, 2},
            {"S Congress Ave, Austin", new double[]{30.2450, -97.7505, 30.2150, -97.7610}, 1200.0, 2200.0, 3},
            {"US-75 north of downtown Dallas", new double[]{32.7900, -96.7980, 33.0200, -96.7000}, 12000.0, 13500.0, 1},
        };
        for (Object[] s : stretches) {
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
            check(same(small, base), s[0] + ": a jam of 0.3 x the way round (" + Math.round(0.3 * wayRound) + " s) stays on the road");
            check(!same(large, base), s[0] + ": a jam of 3 x the way round (" + Math.round(3 * wayRound) + " s) goes round");
            System.out.println("nogo-routing-test: jam " + s[0] + " class=" + functionalClass + " points=" + n
                    + " way_round_s=" + Math.round(wayRound) + " stays_at_s=" + Math.round(0.3 * wayRound) + " leaves_at_s=" + Math.round(3 * wayRound));
        }
    }

    /**
     * A closure on I-35 from 10 to 13 km along. A vehicle already on it, between two of its
     * points, has no way out that keeps out of every circle, and a destination on it lands
     * inside one; both still get a route, marked as passing a closure. A route from outside
     * is still kept out.
     */
    private static void aVehicleOnAClosedRoadStillGetsOut(Router router, Route base) throws Exception {
        List<Nogo> closed = placed(base, 10000, 13000, null);
        double[] inside = pointAt(base, 11155);
        Route out = route(router, new RouteRequest(inside[0], inside[1], I35[2], I35[3], null, new RouteOptions(), 0, null, null, closed));
        check(out.getClosuresOpened() && out.getNogoCount() == closed.size(), "a vehicle on the closed stretch still gets a route, marked");
        check(nearest(out, inside[0], inside[1]) < 30, "and it starts where the vehicle is");
        Route to = route(router, new RouteRequest(I35[0], I35[1], inside[0], inside[1], null, new RouteOptions(), 0, null, null, closed));
        check(to.getClosuresOpened() && to.getDistanceM() > 5000, "a destination on the closed stretch is still reached");
        Route past = route(router, request(I35, closed));
        check(!past.getClosuresOpened() && Router.Companion.closuresCrossed(past, request(I35, closed), closed).isEmpty(),
                "from outside, the closure stays shut");
        System.out.println("nogo-routing-test: stuck out_m=" + out.getDistanceM() + " to_m=" + to.getDistanceM()
                + " past_s=" + past.getSeconds() + " base_s=" + base.getSeconds());
    }

    /**
     * I-35 northbound from about 10.4 km along is one BRouter link for over a kilometre. A
     * closure at its start and a light jam further along the same link: BRouter alone
     * keeps only the jam's charge and drives through the closure. Router must not.
     */
    private static void aJamCannotOpenAClosureOnTheSameLink(Router router, Route base, File profile, File segments) {
        List<Nogo> closure = placed(base, 10370, 10730, null);
        List<Nogo> both = new ArrayList<>(closure);
        both.addAll(placed(base, 10850, 11500, TrafficCost.INSTANCE.pointWeight(20, count(10850, 11500), 1)));
        RouteRequest asked = request(I35, both);
        OsmTrack raw = brouterAlone(profile, segments, both);
        Route rawRoute = new Route(lats(raw), lons(raw), raw.distance, raw.getTotalSeconds(), new int[0], new int[0]);
        boolean overwritten = !Router.Companion.closuresCrossed(rawRoute, asked, both).isEmpty();
        check(overwritten, "the vendored BRouter still lets a later jam on the same link open a closure; if this fails, "
                + "BRouter changed and Router's closure check can be revisited");
        Route kept;
        try {
            kept = route(router, asked);
        } catch (Exception e) {
            throw new AssertionError("routing with a closure and a jam failed: " + e);
        }
        check(Router.Companion.closuresCrossed(kept, asked, both).isEmpty() && !kept.getClosuresOpened(),
                "Router's route keeps out of the closure all the same");
    }

    /**
     * A street crossing over I-35 about 3.3 km along, and another about 2.3 km along, with no
     * junction on the main lanes. A HERE stretch ends at each; with the 30 m margins no point
     * reaches either street. Were a point to land on a crossing (a stretch not ending there),
     * the street would be charged: how often that happens with 150 m spacing is measured.
     */
    private static void aCrossStreetOverTheInterstateIsNotCharged(Router router, Route base) throws Exception {
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
        check(nearestNogo(street, onIt) < RADIUS_M, "a point right on the crossing reaches the street, 2D as BRouter is");
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
    private static void theFrontageRoadAndTheOtherCarriagewayAreNotCharged(Router router, Route base) throws Exception {
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

    /** The search checks every point for every piece of road it weighs: relative host timings, not the phone's. */
    private static void manyPointsSlowTheSearch(Router router, Route base) throws Exception {
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
            check(same(last, base), n + " points beside the route leave it alone");
            line.append(' ').append(n).append('=').append(best);
        }
        System.out.println(line);
    }

    private static void nothingCarriesToTheNextRequest(Router router, Route base) throws Exception {
        Route after = route(router, request(I35, Collections.emptyList()));
        check(same(after, base) && after.getSeconds() == base.getSeconds() && after.getNogoCount() == 0 && !after.getTrafficShaped(),
                "a request without nogos after all of these is routed as the first one was");
    }

    /**
     * Austin to Lubbock is timed lower than asked (docs/navigation.md, "Route preferences"). A closure
     * on the route it takes must still be kept out of after the step-down.
     */
    private static void theSpeedStepDownKeepsThePoints(Router router) throws Exception {
        double[] lubbock = {30.2672, -97.7431, 33.5779, -101.8552};
        Route plain = route(router, request(lubbock, Collections.emptyList()));
        check(plain.getTopSpeedKmh() < 121, "Austin to Lubbock steps down: " + plain.getTopSpeedKmh());
        double middle = plain.getDistanceM() / 2.0;
        List<Nogo> closed = placed(plain, middle, middle + 1500, null);
        RouteRequest asked = request(lubbock, closed);
        Route round = route(router, asked);
        check(round.getTopSpeedKmh() < 121 && round.getTrafficShaped(), "with a closure it still steps down: " + round.getTopSpeedKmh());
        check(Router.Companion.closuresCrossed(round, asked, closed).isEmpty() && !same(round, plain),
                "and the closure went with it to the lower speed");
        System.out.println("nogo-routing-test: lubbock timed_at_kmh=" + round.getTopSpeedKmh() + " plain_s=" + plain.getSeconds()
                + " round_s=" + round.getSeconds());
    }

    // ---- helpers ----

    private static RouteRequest request(double[] ends, List<Nogo> nogos) {
        return new RouteRequest(ends[0], ends[1], ends[2], ends[3], null, new RouteOptions(), 0, null, null, nogos);
    }

    private static Route route(Router router, RouteRequest request) throws Exception {
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

    /** Metres between two points as BRouter measures them, so distances along a route match its own. */
    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        return CheapRuler.distance((int) Math.round((lon1 + 180) * 1e6), (int) Math.round((lat1 + 90) * 1e6),
                (int) Math.round((lon2 + 180) * 1e6), (int) Math.round((lat2 + 90) * 1e6));
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

    private static boolean same(Route a, Route b) {
        return java.util.Arrays.equals(a.getLatitudes(), b.getLatitudes()) && java.util.Arrays.equals(a.getLongitudes(), b.getLongitudes());
    }

    /** I-35 northbound through BRouter directly, with Router's own conversion of the points but none of its checks. */
    private static OsmTrack brouterAlone(File profile, File segments, List<Nogo> nogos) {
        RoutingContext context = new RoutingContext();
        context.localFunction = profile.getAbsolutePath();
        context.memoryclass = 64;
        context.turnInstructionMode = Router.TURN_INSTRUCTION_MODE;
        context.keyValues = new HashMap<>(new RouteOptions().profileParameters());
        List<OsmNodeNamed> points = new ArrayList<>();
        for (Nogo n : nogos) {
            OsmNodeNamed p = new OsmNodeNamed();
            p.ilon = (int) ((n.getLongitude() + 180) * 1e6 + 0.5);
            p.ilat = (int) ((n.getLatitude() + 90) * 1e6 + 0.5);
            p.radius = n.getRadiusM();
            p.nogoWeight = n.getWeight() == null ? Double.NaN : n.getWeight();
            p.isNogo = true;
            p.name = "nogo8";
            points.add(p);
        }
        // Hard last, as Router orders them, so only the link-level overwrite is left.
        points.sort((a, b) -> Boolean.compare(Double.isNaN(a.nogoWeight), Double.isNaN(b.nogoWeight)));
        context.nogopoints = points;
        RoutingEngine engine = new RoutingEngine(null, null, segments,
                new RoutingParamCollector().readPositions(new double[]{I35[1], I35[3]}, new double[]{I35[0], I35[2]}), context);
        engine.quite = true;
        engine.doRun(RoutePlanner.MAX_RUNNING_MS);
        if (engine.getErrorMessage() != null) throw new AssertionError("BRouter alone failed: " + engine.getErrorMessage());
        return engine.getFoundTrack();
    }

    private static double[] lats(OsmTrack track) {
        double[] out = new double[track.nodes.size()];
        for (int i = 0; i < out.length; i++) out[i] = (track.nodes.get(i).getILat() - 90_000_000) / 1e6;
        return out;
    }

    private static double[] lons(OsmTrack track) {
        double[] out = new double[track.nodes.size()];
        for (int i = 0; i < out.length; i++) out[i] = (track.nodes.get(i).getILon() - 180_000_000) / 1e6;
        return out;
    }

    private static void near(double actual, double expected, double tolerance, String what) {
        check(Math.abs(actual - expected) <= tolerance, what + ": " + actual);
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
