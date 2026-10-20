// P6.5's guidance loop on a desktop JVM: BRouter's commands read as cluster maneuvers, and
// RouteFollower snapping fixes to a route, counting down, leaving it and arriving. Both are
// pure Kotlin, so these are the classes the app runs. Nothing here is the vehicle: the
// thresholds are checked against themselves, not against a real GPS.
//
// With a directory holding W75_N40.rd5 as the first argument it also follows a real
// cross-town route from end to end; without one those checks are skipped and the line says
// so.
import btools.router.VoiceHintReader;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import com.jw6ventures.headlink.FollowResult;
import com.jw6ventures.headlink.FollowState;
import com.jw6ventures.headlink.Maneuver;
import com.jw6ventures.headlink.Maneuvers;
import com.jw6ventures.headlink.Nogo;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteFollower;
import com.jw6ventures.headlink.RouteOptions;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.Router;
import com.jw6ventures.headlink.RoutingData;

public final class GuidanceTest {
    private static int passed;

    private static final double LAT0 = 42.0;
    private static final double LON0 = -71.0;
    private static final double M_PER_DEG = 6_371_000.0 * Math.PI / 180.0;

    public static void main(String[] args) throws Exception {
        commandsReadAsManeuvers();
        aRouteIsFollowedFromJoiningToArrival();
        oneBadFixIsNotLeavingTheRoute();
        leavingTheRouteTakesTimeAndFixesAndCanBeUndone();
        aRouteAlwaysEndsAtADestination();
        stepIdentitiesNeverRepeatAcrossRoutes();
        String segments = args.length > 0 ? args[0] : "";
        if (!segments.isEmpty() && new File(segments, "W75_N40.rd5").isFile()) {
            aRealRouteIsFollowedToItsEnd(new File(segments));
            aReRouteStartsTheWayTheVehicleIsGoing(new File(segments));
            System.out.println("jni-host-test: ok " + passed + " guidance checks");
        } else {
            System.out.println("jni-host-test: ok " + passed + " guidance checks (real-route checks skipped: no W75_N40.rd5)");
        }
    }

    // --- the maneuver reading ----------------------------------------------------------

    private static void commandsReadAsManeuvers() {
        int[][] table = {
            {VoiceHintReader.CONTINUE, Maneuvers.STRAIGHT},
            {VoiceHintReader.TURN_LEFT, Maneuvers.TURN_NORMAL_LEFT},
            {VoiceHintReader.TURN_SLIGHT_LEFT, Maneuvers.TURN_SLIGHT_LEFT},
            {VoiceHintReader.TURN_SHARP_LEFT, Maneuvers.TURN_SHARP_LEFT},
            {VoiceHintReader.TURN_RIGHT, Maneuvers.TURN_NORMAL_RIGHT},
            {VoiceHintReader.TURN_SLIGHT_RIGHT, Maneuvers.TURN_SLIGHT_RIGHT},
            {VoiceHintReader.TURN_SHARP_RIGHT, Maneuvers.TURN_SHARP_RIGHT},
            {VoiceHintReader.KEEP_LEFT, Maneuvers.KEEP_LEFT},
            {VoiceHintReader.KEEP_RIGHT, Maneuvers.KEEP_RIGHT},
            {VoiceHintReader.U_TURN_LEFT, Maneuvers.U_TURN_LEFT},
            {VoiceHintReader.U_TURN_180, Maneuvers.U_TURN_LEFT},
            {VoiceHintReader.U_TURN_RIGHT, Maneuvers.U_TURN_RIGHT},
            {VoiceHintReader.EXIT_LEFT, Maneuvers.OFF_RAMP_SLIGHT_LEFT},
            {VoiceHintReader.EXIT_RIGHT, Maneuvers.OFF_RAMP_SLIGHT_RIGHT},
            {VoiceHintReader.END, Maneuvers.DESTINATION},
        };
        for (int[] row : table) {
            Maneuver m = Maneuvers.INSTANCE.of(row[0], 0);
            check(m != null && m.getType() == row[1] && m.getExit() == 0, "command " + row[0] + " is maneuver " + row[1]);
            check(!m.getInstruction().isEmpty(), "command " + row[0] + " has an instruction");
        }
        // BRouter's own numbering, pinned: a re-sync that renumbers fails here, not on a drive.
        check(VoiceHintReader.TURN_LEFT == 2 && VoiceHintReader.TURN_RIGHT == 5 && VoiceHintReader.ROUNDABOUT == 13
            && VoiceHintReader.END == 100, "BRouter's command numbers are the pinned ones");
        // Right-hand traffic goes round counter-clockwise, and says which exit.
        Maneuver r = Maneuvers.INSTANCE.of(VoiceHintReader.ROUNDABOUT, 3);
        check(r.getType() == Maneuvers.ROUNDABOUT_ENTER_AND_EXIT_CCW && r.getExit() == 3, "a roundabout, third exit");
        check(r.getInstruction().contains("exit 3"), "the exit is in the instruction");
        Maneuver l = Maneuvers.INSTANCE.of(VoiceHintReader.ROUNDABOUT_LEFT, -2);
        check(l.getType() == Maneuvers.ROUNDABOUT_ENTER_AND_EXIT_CW && l.getExit() == 2, "a left-traffic roundabout");
        check(Maneuvers.INSTANCE.of(VoiceHintReader.ROUNDABOUT, 0).getExit() == 0, "no exit number, none claimed");
        // Not things to do.
        check(Maneuvers.INSTANCE.of(VoiceHintReader.OFF_ROUTE, 0) == null, "off-route is not a maneuver");
        check(Maneuvers.INSTANCE.of(VoiceHintReader.BEELINE, 0) == null, "a beeline is not a maneuver");
        check(Maneuvers.INSTANCE.of(99, 0) == null, "an unknown command is not guessed at");
    }

    // --- a synthetic route: 600 m east, then left and 600 m north ----------------------

    private static double[] east(double m) { return new double[] {LAT0, LON0 + m / (M_PER_DEG * Math.cos(Math.toRadians(LAT0)))}; }
    private static double[] north(double m) {
        double[] corner = east(600);
        return new double[] {corner[0] + m / M_PER_DEG, corner[1]};
    }

    /** Offset [m] metres west of [point], or south when [south]. */
    private static double[] off(double[] point, double m, boolean south) {
        return south ? new double[] {point[0] - m / M_PER_DEG, point[1]}
            : new double[] {point[0], point[1] - m / (M_PER_DEG * Math.cos(Math.toRadians(point[0])))};
    }

    private static Route lShape(int[] commands) {
        double[] lat = new double[13];
        double[] lon = new double[13];
        float[] seconds = new float[13];
        for (int i = 0; i <= 6; i++) {
            double[] p = east(i * 100);
            lat[i] = p[0];
            lon[i] = p[1];
            seconds[i] = i * 10f;
        }
        for (int i = 1; i <= 6; i++) {
            double[] p = north(i * 100);
            lat[6 + i] = p[0];
            lon[6 + i] = p[1];
            seconds[6 + i] = (6 + i) * 10f;
        }
        int[] indices = commands.length == 2 ? new int[] {6, 12} : new int[] {3, 6};
        return new Route(lat, lon, 1200, 120, indices, new int[commands.length], commands, seconds);
    }

    private static FollowResult at(RouteFollower f, double[] p, long ms) { return f.update(p[0], p[1], ms); }

    private static void aRouteIsFollowedFromJoiningToArrival() {
        Route route = lShape(new int[] {VoiceHintReader.TURN_LEFT, VoiceHintReader.END});
        RouteFollower f = new RouteFollower(route, 1);
        check(Math.abs(f.getTotalM() - 1200) < 2, "the route is 1200 m long: " + f.getTotalM());
        check(f.maneuverTypes().equals(List.of(Maneuvers.TURN_NORMAL_LEFT, Maneuvers.DESTINATION)), "left, then arrive");

        // The vehicle starts 100 m south of the route: joining, and the way to the route
        // counts toward the first maneuver.
        FollowResult r = at(f, off(east(0), 100, true), 0);
        check(r.getState() == FollowState.JOINING, "not on the route yet: " + r.getState());
        check(near(r.getStep().getMetres(), 700, 3), "600 m of route plus 100 m to reach it: " + r.getStep().getMetres());
        check(r.getStep().getManeuver().getType() == Maneuvers.TURN_NORMAL_LEFT, "the first maneuver while joining");

        r = at(f, east(0), 1000);
        check(r.getState() == FollowState.ON_ROUTE, "on the route at its start");
        check(near(r.getStep().getMetres(), 600, 2) && r.getStep().getSeconds() == 60, "600 m and 60 s to the turn");
        check(near(r.getStep().getDestinationMetres(), 1200, 2) && r.getStep().getDestinationSeconds() == 120,
            "1200 m and 120 s to the destination");
        // P7.4's trip panel: what comes after the next maneuver, from the same position.
        check(r.getStep().getLater().size() == 1
            && r.getStep().getLater().get(0).getManeuver().getType() == Maneuvers.DESTINATION
            && near(r.getStep().getLater().get(0).getMetres(), 1200, 2), "after the turn, the destination 1200 m on");
        long firstId = r.getStep().getId();

        double previous = r.getStep().getMetres();
        for (int m = 50; m < 600; m += 50) {
            // Ten metres to one side of the centreline, as a kerb lane is.
            r = at(f, off(east(m), 10, true), 1000 + m * 100L);
            check(r.getState() == FollowState.ON_ROUTE, "still on the route at " + m + " m");
            check(r.getStep().getId() == firstId, "the same maneuver until it is passed");
            check(r.getStep().getMetres() < previous, "the countdown falls at " + m + " m");
            check(near(r.getStep().getMetres(), 600 - m, 2), "distance to the turn at " + m + " m: " + r.getStep().getMetres());
            // Distance, time and destination are three views of one position.
            check(Math.abs(r.getStep().getSeconds() - (600 - m) / 10.0) <= 1, "time to the turn at " + m + " m");
            check(near(r.getStep().getDestinationMetres() - r.getStep().getMetres(), 600, 2), "the turn is 600 m before the end");
            previous = r.getStep().getMetres();
        }
        r = at(f, north(20), 70_000);
        check(r.getState() == FollowState.ON_ROUTE, "round the corner");
        check(r.getStep().getId() != firstId && r.getStep().getManeuver().getType() == Maneuvers.DESTINATION,
            "the turn is passed and the destination is next");
        check(near(r.getStep().getMetres(), 580, 2), "580 m to go: " + r.getStep().getMetres());
        check(r.getStep().getLater().isEmpty(), "nothing after the destination");
        check(near(r.getAlongM(), 620, 2), "620 m along");

        r = at(f, north(575), 120_000);
        check(r.getState() == FollowState.ARRIVED && r.getStep() == null, "arrived 25 m out");
        r = at(f, off(north(575), 500, false), 130_000);
        check(r.getState() == FollowState.ARRIVED, "arrived stays arrived, wherever the vehicle parks");
    }

    private static void oneBadFixIsNotLeavingTheRoute() {
        RouteFollower f = new RouteFollower(lShape(new int[] {VoiceHintReader.TURN_LEFT, VoiceHintReader.END}), 1);
        at(f, east(100), 0);
        FollowResult r = at(f, off(east(110), 150, true), 1000);
        check(r.getState() == FollowState.ON_ROUTE, "one fix 150 m off is noise");
        check(near(r.getAlongM(), 100, 2), "and moves nothing");
        r = at(f, east(120), 2000);
        check(r.getState() == FollowState.ON_ROUTE && near(r.getAlongM(), 120, 2), "the next good fix carries on");
        // Nor does a fix a little behind the last one restate a passed maneuver.
        at(f, north(30), 3000);
        r = at(f, east(595), 4000);
        check(r.getStep().getManeuver().getType() == Maneuvers.DESTINATION, "GPS jitter back over a passed turn");
    }

    private static void leavingTheRouteTakesTimeAndFixesAndCanBeUndone() {
        RouteFollower f = new RouteFollower(lShape(new int[] {VoiceHintReader.TURN_LEFT, VoiceHintReader.END}), 1);
        at(f, east(200), 0);
        // Missed the turn: carries straight on east past the corner, a fix a second.
        FollowResult r = null;
        long t = 0;
        long firstOff = -1;
        for (int m = 620; m <= 800; m += 20) {
            t += 1000;
            r = at(f, east(m), t);
            if (firstOff < 0 && r.getOffsetM() > RouteFollower.ON_ROUTE_M) firstOff = t;
            if (firstOff < 0 || t - firstOff < RouteFollower.OFF_ROUTE_MS) {
                check(r.getState() == FollowState.ON_ROUTE, "not off the route yet at " + t + " ms, " + (int) r.getOffsetM() + " m");
            }
        }
        check(r.getState() == FollowState.OFF_ROUTE, "off the route after driving on past the turn: " + r.getState());
        check(r.getOffsetM() > 100, "and says how far: " + r.getOffsetM());

        // Many fixes in one bad second are still one second.
        RouteFollower g = new RouteFollower(lShape(new int[] {VoiceHintReader.TURN_LEFT, VoiceHintReader.END}), 1);
        at(g, east(200), 0);
        for (int i = 0; i < 20; i++) r = at(g, off(east(300), 200, true), 1000 + i * 50L);
        check(r.getState() == FollowState.ON_ROUTE, "twenty fixes in a second are not four seconds");

        // Coming back before a re-route lands is being on the route again.
        r = at(f, north(100), t + 1000);
        check(r.getState() == FollowState.ON_ROUTE, "back on the route");
    }

    private static void aRouteAlwaysEndsAtADestination() {
        // An off-route hint is dropped, and a route with no END still ends at one.
        RouteFollower f = new RouteFollower(lShape(new int[] {VoiceHintReader.OFF_ROUTE, VoiceHintReader.TURN_LEFT}), 1);
        check(f.maneuverTypes().equals(List.of(Maneuvers.TURN_NORMAL_LEFT, Maneuvers.DESTINATION)),
            "off-route skipped and a destination added: " + f.maneuverTypes());
    }

    private static void stepIdentitiesNeverRepeatAcrossRoutes() {
        Route route = lShape(new int[] {VoiceHintReader.TURN_LEFT, VoiceHintReader.END});
        long a = at(new RouteFollower(route, 1), east(0), 0).getStep().getId();
        long b = at(new RouteFollower(route, 2), east(0), 0).getStep().getId();
        check(a != b && b >= 2 * RouteFollower.STEP_ID_STRIDE, "the same turn on a second route is a new step");
    }

    // --- a real route ------------------------------------------------------------------

    private static void aRealRouteIsFollowedToItsEnd(File segments) throws Exception {
        File work = Files.createTempDirectory("guidance-test").toFile();
        try {
            File profile = RoutingData.INSTANCE.installProfile(new File(work, "profiles"));
            Route route = new Router(segments, profile, 64)
                .compute(new RouteRequest(42.4242, -71.2121, 42.3551, -71.0656), 120_000, e -> kotlin.Unit.INSTANCE);
            int n = route.getPoints();
            int[] commands = route.getCommands();
            check(commands.length == route.getManeuverIndices().length, "one command per maneuver");
            // BRouter's END is internal to its hint processing and never reaches a track, so
            // the destination is the follower's to add - once.
            for (int c : commands) check(c != VoiceHintReader.END, "no END among BRouter's hints");
            float[] seconds = route.getPointSeconds();
            check(seconds.length == n && seconds[0] == 0f, "a time for every point, from zero");
            for (int i = 1; i < n; i++) check(seconds[i] >= seconds[i - 1], "time never runs backwards at point " + i);
            check(Math.abs(seconds[n - 1] - route.getSeconds()) <= 1, "the last point's time is the route's");

            RouteFollower f = new RouteFollower(route, 7);
            List<Integer> types = f.maneuverTypes();
            check(types.get(types.size() - 1) == Maneuvers.DESTINATION
                && types.indexOf(Maneuvers.DESTINATION) == types.size() - 1, "exactly one destination, last");
            List<Integer> seen = new ArrayList<>();
            long lastId = -1;
            FollowResult r = null;
            long t = 0;
            double worst = 0;
            // One fix a second at the profile's own pace, sampled along the geometry.
            for (int i = 0; i < n; i++) {
                t += 1000;
                r = f.update(route.getLatitudes()[i], route.getLongitudes()[i], t);
                if (r.getState() == FollowState.ARRIVED) break;
                check(r.getState() == FollowState.ON_ROUTE, "on the route at point " + i + ": " + r.getState());
                if (r.getStep().getId() != lastId) {
                    check(r.getStep().getId() > lastId, "maneuvers in order at point " + i);
                    seen.add(r.getStep().getManeuver().getType());
                    lastId = r.getStep().getId();
                }
                worst = Math.max(worst, r.getOffsetM());
            }
            check(r != null && r.getState() == FollowState.ARRIVED, "arrived at the end of the geometry");
            check(seen.equals(f.maneuverTypes().subList(f.maneuverTypes().size() - seen.size(), f.maneuverTypes().size())),
                "every maneuver after the start was shown, in order: " + seen + " against " + f.maneuverTypes());
            check(worst < 1, "the route's own points lie on it: " + worst);
            System.out.println("guidance-test: real route points=" + n + " maneuvers=" + f.maneuverTypes());
        } finally {
            delete(work);
        }
    }

    /**
     * The lab drive's missed turn, where a re-route was measured to start by going back
     * the way the vehicle came: with the heading alone BRouter prices turning round at a
     * flat 40 s and still set off 180 degrees off. Router's point behind the vehicle must
     * make it set off forward, and a request without a heading must have no such point.
     */
    private static void aReRouteStartsTheWayTheVehicleIsGoing(File segments) throws Exception {
        File drive = new File("tools/lab-receiver/drives/lexington.csv");
        if (!drive.isFile()) return;
        List<double[]> path = new ArrayList<>();
        for (String line : Files.readAllLines(drive.toPath())) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] p = line.split(",");
            path.add(new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1])});
        }
        File work = Files.createTempDirectory("guidance-heading").toFile();
        try {
            Router router = new Router(segments, RoutingData.INSTANCE.installProfile(new File(work, "profiles")), 64);
            Route planned = router.compute(new RouteRequest(path.get(0)[0], path.get(0)[1], 42.44950, -71.23100),
                120_000, e -> kotlin.Unit.INSTANCE);
            RouteFollower f = new RouteFollower(planned, 1);
            // Drive the fixture until the follower says the vehicle has left the route.
            long t = 0;
            int at = -1;
            for (int i = 0; i < path.size() && at < 0; i++) {
                for (int k = 0; k < 5; k++) {
                    t += 1000;
                    if (f.update(path.get(i)[0], path.get(i)[1], t).getState() == FollowState.OFF_ROUTE) {
                        at = i;
                        break;
                    }
                }
            }
            check(at > 0, "the fixture drive leaves the planned route");
            double[] here = path.get(at);
            double[] behind = path.get(Math.max(0, at - 2));
            double heading = RouteFollower.Companion.bearing(behind[0], behind[1], here[0], here[1]);
            Route ahead = router.compute(new RouteRequest(here[0], here[1], 42.44950, -71.23100, heading),
                120_000, e -> kotlin.Unit.INSTANCE);
            int k = 1;
            while (k < ahead.getPoints() - 1
                && RouteFollower.Companion.distance(ahead.getLatitudes()[0], ahead.getLongitudes()[0],
                    ahead.getLatitudes()[k], ahead.getLongitudes()[k]) < 40) k++;
            double first = RouteFollower.Companion.bearing(ahead.getLatitudes()[0], ahead.getLongitudes()[0],
                ahead.getLatitudes()[k], ahead.getLongitudes()[k]);
            double turn = Math.abs(((first - heading) % 360 + 540) % 360 - 180);
            check(ahead.getPoints() >= 2, "a re-route with a heading is still a route");
            System.out.println(String.format("guidance-test: re-route with heading %.0f sets off at %.0f (%.0f degrees off)",
                heading, first, turn));
            check(turn < 90, "a re-route sets off the way the vehicle is going, not back");
            RouteOptions options = new RouteOptions();
            Nogo back = Router.Companion.behind(new RouteRequest(here[0], here[1], 42.44950, -71.23100, heading), options);
            check(back != null && !back.getHard(), "a heading puts a weighted point behind the start");
            double off = RouteFollower.Companion.distance(here[0], here[1], back.getLatitude(), back.getLongitude());
            double towards = RouteFollower.Companion.bearing(here[0], here[1], back.getLatitude(), back.getLongitude());
            check(near(off, Router.BEHIND_CENTRE_M, 0.1) && off > back.getRadiusM(), "the point is clear of the start");
            check(Math.abs(((towards - heading) % 360 + 540) % 360 - 180) > 179, "the point is straight behind");
            check(Router.Companion.behind(new RouteRequest(here[0], here[1], 42.44950, -71.23100), options) == null,
                "no heading, no point behind");
        } finally {
            delete(work);
        }
    }

    private static boolean near(double a, double b, double tolerance) { return Math.abs(a - b) <= tolerance; }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("guidance: " + what);
        passed++;
    }
}
