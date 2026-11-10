// OsmAnd's router (vendor/osmand) behind HeadLink's RouteEngine, on a desktop JVM
// (docs/navigation.md, "Routing on OsmAnd maps").
//
// Over ExitSignsTest's synthetic .obf: a motorway north, an exit ramp east off it with
// junction 23A and its destination signs, a primary road across the ramp's end, and an
// on-ramp from that road back onto the motorway further north. Two ways reach the primary
// road from the motorway's south end: the exit ramp, and on up the motorway and back down
// the on-ramp. So traffic on the ramp has somewhere to send the route, and the second
// alternative has another corridor to find.
//
// What it shows: OsmAnd's file reader and router run on HeadLink's own synthetic file; a
// route comes back as HeadLink's Route, with its exit maneuver, number and sign words from
// the same data; a closure and a costly jam move it, a cheap jam does not, and closures with
// no way round are opened rather than failing; alternative 1 avoids alternative 0's roads;
// a handle stops a search; no maps is an error, not a crash. It proves nothing about real
// maps, ART or the phone's speed.
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import btools.router.VoiceHintReader;
import com.jw6ventures.headlink.ExitSign;
import com.jw6ventures.headlink.Nogo;
import com.jw6ventures.headlink.OsmAndRouter;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteOptions;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.RoutingException;
import com.jw6ventures.headlink.SearchHandle;

public final class OsmAndRoutingTest {
    private static int passed;

    private static final double LAT = 30.30, LON = -97.70;

    /** Near the motorway's south end, and on the primary road east of the ramp. */
    private static final double FROM_LAT = LAT + 0.0005, FROM_LON = LON;
    private static final double TO_LAT = LAT + 0.0075, TO_LON = LON + 0.011;

    /** Mid-ramp, and where the on-ramp leaves the motorway. */
    private static final double RAMP_LAT = LAT + 0.0066, RAMP_LON = LON + 0.0014;
    private static final double ON_RAMP_LAT = LAT + 0.0095, ON_RAMP_LON = LON + 0.0005;

    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("osmand-routing", ".obf");
        try {
            Files.write(file, ExitSignsTest.syntheticObf());
            OsmAndRouter router = new OsmAndRouter(() -> List.of(OsmAndRouter.MapFile.of(file.toFile())));
            try {
                theExitIsAManeuverWithItsSign(router);
                theTimesRunAlongTheRoute(router);
                aClosureOnTheRampSendsItRoundByTheOnRamp(router);
                aJamMovesItOnlyWhenItCostsEnough(router);
                closuresWithNoWayRoundAreOpened(router);
                theSecondAlternativeTakesTheOtherWay(router);
                aHandleStopsTheSearch(router);
            } finally {
                router.close();
            }
        } finally {
            Files.deleteIfExists(file);
        }
        noMapsIsAnError();
        theSettingsAreTheProfilesParameters();
        System.out.println("jni-host-test: ok " + passed + " OsmAnd routing checks");
    }

    private static Route route(OsmAndRouter router, RouteRequest request) throws RoutingException {
        return router.compute(request, 30_000, handle -> kotlin.Unit.INSTANCE);
    }

    private static RouteRequest trip(List<Nogo> nogos, int alternative) {
        return new RouteRequest(FROM_LAT, FROM_LON, TO_LAT, TO_LON, null, new RouteOptions(), alternative, null, null, nogos);
    }

    private static void theExitIsAManeuverWithItsSign(OsmAndRouter router) throws Exception {
        Route route = route(router, trip(List.of(), 0));
        check(route.getPoints() >= 2, "a route comes back: " + route.getPoints() + " points");
        check(near(route.getLatitudes()[0], route.getLongitudes()[0], FROM_LAT, FROM_LON, 30),
            "it starts where it was asked to");
        int last = route.getPoints() - 1;
        check(near(route.getLatitudes()[last], route.getLongitudes()[last], TO_LAT, TO_LON, 30),
            "and ends there too");
        check(route.getManeuverIndices().length == 1, "one maneuver, the exit: " + route.getManeuverIndices().length);
        check(route.getCommands()[0] == VoiceHintReader.EXIT_RIGHT, "a keep right off the motorway onto its link is the exit");
        int at = route.getManeuverIndices()[0];
        check(near(route.getLatitudes()[at], route.getLongitudes()[at], LAT + 0.005, LON, 5), "at the junction");
        ExitSign sign = route.exitSign(0);
        check(sign != null && "23A".equals(sign.getNumber()), "with the junction's number: " + sign);
        check("I 35 South / Waco / Temple".equals(sign.getToward()), "and the ramp's signs, worded as ExitSigns words them: " + sign);
        check(route.getRoadStarts().length == 2 && route.getRoadStarts()[0] == 0 && route.getRoadStarts()[1] == at,
            "a stretch of road from the start and from the maneuver");
        check(!route.getTrafficShaped() && !route.getClosuresOpened(), "no traffic was asked about");
        check(route.getTopSpeedKmh() == RouteOptions.DEFAULT_TOP_SPEED_KMH, "timed at the top speed asked for");
    }

    private static void theTimesRunAlongTheRoute(OsmAndRouter router) throws Exception {
        Route route = route(router, trip(List.of(), 0));
        float[] times = route.getPointSeconds();
        check(times.length == route.getPoints(), "a time for every point");
        boolean rising = times[0] == 0f;
        for (int i = 1; i < times.length; i++) rising &= times[i] >= times[i - 1];
        check(rising, "starting at 0 and never going back");
        check(Math.abs(times[times.length - 1] - route.getSeconds()) <= 1f, "ending at the route's own total: "
            + times[times.length - 1] + " against " + route.getSeconds());
        check(route.getDistanceM() > 1_000 && route.getDistanceM() < 2_500, "a plausible length: " + route.getDistanceM());
        RouteRequest slower = new RouteRequest(FROM_LAT, FROM_LON, TO_LAT, TO_LON, null,
            new RouteOptions(false, false, false, false, false, RouteOptions.DEFAULT_TOP_SPEED_KMH, 20, true, true, true));
        Route scaled = route(router, slower);
        check(Math.abs(scaled.getSeconds() - route.getSeconds() * 1.2) <= 1.0, "the time adjustment applies as with BRouter: "
            + scaled.getSeconds() + " against " + route.getSeconds());
    }

    private static void aClosureOnTheRampSendsItRoundByTheOnRamp(OsmAndRouter router) throws Exception {
        Nogo closed = new Nogo(RAMP_LAT, RAMP_LON, 40, null);
        Route route = route(router, trip(List.of(closed), 0));
        check(!passesNear(route, RAMP_LAT, RAMP_LON, 40), "a closed ramp is not driven");
        check(passesNear(route, ON_RAMP_LAT, ON_RAMP_LON, 60), "the way round is up the motorway and down the on-ramp");
        check(route.getNogoCount() == 1 && !route.getClosuresOpened(), "one traffic point, kept closed");
    }

    private static void aJamMovesItOnlyWhenItCostsEnough(OsmAndRouter router) throws Exception {
        // BRouter cost per metre: a few seconds' delay over the circle does not beat the
        // longer way round; an hour's does.
        Route cheap = route(router, trip(List.of(new Nogo(RAMP_LAT, RAMP_LON, 40, 1.0)), 0));
        check(passesNear(cheap, RAMP_LAT, RAMP_LON, 10), "a slight jam leaves the route on the ramp");
        Route costly = route(router, trip(List.of(new Nogo(RAMP_LAT, RAMP_LON, 40, 100_000.0)), 0));
        check(!passesNear(costly, RAMP_LAT, RAMP_LON, 10), "a bad one sends it round");
        check(costly.getNogoCount() == 1 && costly.getTrafficShaped(), "and the route says traffic shaped it");
    }

    private static void closuresWithNoWayRoundAreOpened(OsmAndRouter router) throws Exception {
        List<Nogo> both = List.of(new Nogo(RAMP_LAT, RAMP_LON, 40, null), new Nogo(ON_RAMP_LAT, ON_RAMP_LON, 40, null));
        Route route = route(router, trip(both, 0));
        check(route.getClosuresOpened(), "with every way closed, a route through a closure rather than none");
        check(route.getNogoCount() == 2, "carrying both points");
    }

    private static void theSecondAlternativeTakesTheOtherWay(OsmAndRouter router) throws Exception {
        Route best = route(router, trip(List.of(), 0));
        Route second = route(router, trip(List.of(), 1));
        check(passesNear(best, RAMP_LAT, RAMP_LON, 10), "the best route takes the exit");
        check(!passesNear(second, RAMP_LAT, RAMP_LON, 10) && passesNear(second, ON_RAMP_LAT, ON_RAMP_LON, 60),
            "alternative 1 pays again for the ramp and goes round");
        check(second.getSeconds() > best.getSeconds(), "and is the slower of the two");
    }

    private static void aHandleStopsTheSearch(OsmAndRouter router) {
        int[] handles = {0};
        try {
            router.compute(trip(List.of(), 0), 30_000, handle -> {
                handles[0]++;
                handle.terminate();
                return kotlin.Unit.INSTANCE;
            });
            check(false, "a stopped search returns no route");
        } catch (RoutingException e) {
            check(e.getStopped(), "a stopped search says it was stopped: " + e.getMessage());
        }
        check(handles[0] == 1, "one handle for one search");
    }

    private static void noMapsIsAnError() {
        OsmAndRouter none = new OsmAndRouter(List::of);
        try {
            route(none, trip(List.of(), 0));
            check(false, "no maps, no route");
        } catch (RoutingException e) {
            check(!e.getStopped() && e.getMessage().contains("no OsmAnd maps"), "no maps is said plainly: " + e.getMessage());
        }
    }

    private static void theSettingsAreTheProfilesParameters() {
        Map<String, String> plain = OsmAndRouter.profileParameters(new RouteOptions());
        check(plain.size() == 1 && plain.containsKey("max_speed"), "by default only the top speed: " + plain);
        check(Math.abs(Double.parseDouble(plain.get("max_speed")) - RouteOptions.DEFAULT_TOP_SPEED_KMH / 3.6) < 1e-9,
            "in metres a second");
        Map<String, String> all = OsmAndRouter.profileParameters(
            new RouteOptions(true, true, true, true, true, 105, 0, true, true, true));
        check("true".equals(all.get("avoid_motorway")) && "true".equals(all.get("avoid_toll"))
            && "true".equals(all.get("avoid_unpaved")), "the three switches OsmAnd's car profile has: " + all);
        check(all.size() == 4, "and nothing for going round towns or allowing tracks, which it has not");
    }

    /** Whether any point of [route] is within [metres] of the place, or any piece passes that near. */
    private static boolean passesNear(Route route, double lat, double lon, double metres) {
        for (int i = 0; i + 1 < route.getPoints(); i++) {
            double[] a = local(route.getLatitudes()[i], route.getLongitudes()[i], lat, lon);
            double[] b = local(route.getLatitudes()[i + 1], route.getLongitudes()[i + 1], lat, lon);
            double dx = b[0] - a[0], dy = b[1] - a[1];
            double length2 = dx * dx + dy * dy;
            double t = length2 == 0 ? 0 : Math.max(0, Math.min(1, -(a[0] * dx + a[1] * dy) / length2));
            if (Math.hypot(a[0] + t * dx, a[1] + t * dy) <= metres) return true;
        }
        return false;
    }

    private static boolean near(double lat1, double lon1, double lat2, double lon2, double metres) {
        double[] d = local(lat1, lon1, lat2, lon2);
        return Math.hypot(d[0], d[1]) <= metres;
    }

    private static double[] local(double lat, double lon, double lat0, double lon0) {
        double m = 6_371_000.0 * Math.PI / 180.0;
        return new double[] {(lon - lon0) * m * Math.cos(Math.toRadians(lat0)), (lat - lat0) * m};
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("OsmAnd routing: " + what);
        passed++;
    }
}
