// Road names on the guidance loop's maneuvers: the road each stretch of a route lies on, the
// instruction that names the road turned onto, the road being driven, and the turn card's
// lines. The names come with the route from the engine (Route.roadStarts and roadNames):
// OsmAndRouter labels each stretch by its road's name, else its ref, and nothing for a blank
// name, checked over SyntheticObf's roads; the follower's route here carries its own.
//
// With a directory of OsmAnd maps as the argument (OBF_DIR) holding
// Us_massachusetts_northamerica.obf, it also routes across town from the lab receiver's
// position to Boston Common with OsmAndRouter and checks that the route names its roads;
// without it those checks are skipped and the line says so.
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import com.jw6ventures.headlink.Dashboard;
import com.jw6ventures.headlink.DashboardLayout;
import com.jw6ventures.headlink.FollowResult;
import com.jw6ventures.headlink.FollowState;
import com.jw6ventures.headlink.Maneuver;
import com.jw6ventures.headlink.Maneuvers;
import com.jw6ventures.headlink.OsmAndRouter;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteFollower;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.SpeedUnit;
import com.jw6ventures.headlink.TurnCommand;
import com.jw6ventures.headlink.UpcomingTurn;

public final class RoadNamesTest {
    private static int passed;

    private static final double LAT0 = 42.0;
    private static final double LON0 = -71.0;
    private static final double M_PER_DEG = 6_371_000.0 * Math.PI / 180.0;

    public static void main(String[] args) throws Exception {
        aStretchIsOnTheRoadTheRouteNames();
        anInstructionNamesTheRoadItTurnsOnto();
        theFollowerSaysWhichRoadIsNextAndWhichIsNow();
        theCardSpellsTheTurnInTheDriversUnits();
        osmAndLabelsARoadByNameThenRef();
        String dir = args.length > 0 ? args[0] : "";
        Path massachusetts = dir.isEmpty() ? null : Path.of(dir, "Us_massachusetts_northamerica.obf");
        if (massachusetts != null && Files.isRegularFile(massachusetts)) {
            aRealRouteNamesItsRoads(massachusetts);
            System.out.println("jni-host-test: ok " + passed + " road name checks (with a real route)");
        } else {
            System.out.println("jni-host-test: ok " + passed
                + " road name checks (real-route checks skipped: need OBF_DIR with Us_massachusetts_northamerica.obf)");
        }
    }

    /**
     * Over SyntheticObf's roads: the motorway has only a ref (I 35), the exit ramp no name,
     * the primary road a name and a ref (Main Street, FM 1825), the on-ramp a blank name.
     */
    private static void osmAndLabelsARoadByNameThenRef() throws Exception {
        Path file = Files.createTempFile("road-names", ".obf");
        try {
            Files.write(file, SyntheticObf.syntheticObf());
            OsmAndRouter router = new OsmAndRouter(() -> List.of(OsmAndRouter.MapFile.of(file.toFile())));
            try {
                double lat = SyntheticObf.LAT, lon = SyntheticObf.LON;
                Route exit = router.compute(new RouteRequest(lat + 0.0005, lon, lat + 0.0075, lon + 0.011), 30_000,
                    e -> kotlin.Unit.INSTANCE);
                String[] names = exit.getRoadNames();
                check(names.length == 2, "the motorway and the ramp: " + java.util.Arrays.toString(names));
                check("I 35".equals(names[0]), "a road with only a ref is its ref: " + names[0]);
                check(names[1] == null, "a road with neither is unnamed: " + names[1]);
                Route onRamp = router.compute(new RouteRequest(lat + 0.0075, lon + 0.011, lat + 0.0099, lon), 30_000,
                    e -> kotlin.Unit.INSTANCE);
                names = onRamp.getRoadNames();
                check(names.length >= 2, "the primary road and the on-ramp: " + java.util.Arrays.toString(names));
                check("Main Street".equals(names[0]), "the name wins over the ref: " + names[0]);
                check(names[1] == null, "a blank name is no name: '" + names[1] + "'");
            } finally {
                router.close();
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // --- a synthetic route: 600 m east on Elm Avenue, then left and 600 m north on Main Street

    private static double[] east(double m) { return new double[] {LAT0, LON0 + m / (M_PER_DEG * Math.cos(Math.toRadians(LAT0)))}; }
    private static double[] north(double m) {
        double[] corner = east(600);
        return new double[] {corner[0] + m / M_PER_DEG, corner[1]};
    }

    /** The L-shaped route, with [names] for its two stretches or none. */
    private static Route lShape(String[] names) {
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
        return new Route(lat, lon, 1200, 120, new int[] {6}, new int[1], new int[] {TurnCommand.TURN_LEFT}, seconds,
            names == null ? new int[0] : new int[] {0, 6}, names == null ? new String[0] : names);
    }

    private static final String[] NAMES = {"Elm Avenue", "Main Street"};

    private static void aStretchIsOnTheRoadTheRouteNames() {
        Route named = lShape(NAMES);
        check("Elm Avenue".equals(named.roadAt(0)) && "Elm Avenue".equals(named.roadAt(5)), "Elm Avenue up to the turn");
        check("Main Street".equals(named.roadAt(6)) && "Main Street".equals(named.roadAt(12)), "Main Street from it");
        check(lShape(null).roadAt(3) == null, "a route with no names has no roads");
        Route unnamedStretch = new Route(named.getLatitudes(), named.getLongitudes(), 1200, 120, new int[] {6}, new int[1],
            new int[] {TurnCommand.TURN_LEFT}, named.getPointSeconds(), new int[] {0, 6}, new String[] {"Elm Avenue", null});
        check(unnamedStretch.roadAt(8) == null, "a stretch the map names none of does not borrow the one left");
    }

    private static void anInstructionNamesTheRoadItTurnsOnto() {
        Maneuvers m = Maneuvers.INSTANCE;
        Maneuver right = m.of(TurnCommand.TURN_RIGHT, 0);
        check("Turn right onto Main Street".equals(m.instruction(right, "Main Street", "Elm Avenue")), "onto the new road");
        check("Turn right".equals(m.instruction(right, null, "Elm Avenue")), "no name, no claim");
        check("Keep left to stay on I 95".equals(m.instruction(m.of(TurnCommand.KEEP_LEFT, 0), "I 95", "I 95")),
            "a road that keeps its name is stayed on");
        check("Make a U-turn".equals(m.instruction(m.of(TurnCommand.U_TURN_180, 0), "Elm Avenue", "Elm Avenue")),
            "a U-turn back along the same road says only that");
        check("At the roundabout, take exit 2 onto Quarry Lane".equals(
            m.instruction(m.of(TurnCommand.ROUNDABOUT, 2), "Quarry Lane", null)), "a roundabout names its exit road");
        check("Arrive at the destination".equals(m.instruction(m.of(TurnCommand.END, 0), "Main Street", "Main Street")),
            "the destination turns onto nothing");
    }

    private static void theFollowerSaysWhichRoadIsNextAndWhichIsNow() {
        RouteFollower f = new RouteFollower(lShape(NAMES), 1);
        // 100 m south of the start: joining, so the road under the vehicle is unknown.
        double[] start = east(0);
        FollowResult r = f.update(start[0] - 100 / M_PER_DEG, start[1], 0);
        check(r.getState() == FollowState.JOINING && r.getStep().getRoad() == null, "joining is on no known road");
        check("Main Street".equals(r.getStep().getOnto()), "the first turn is onto Main Street");
        double[] p = east(200);
        r = f.update(p[0], p[1], 1000);
        check(r.getState() == FollowState.ON_ROUTE, "on the route");
        check("Elm Avenue".equals(r.getStep().getRoad()), "driving Elm Avenue: " + r.getStep().getRoad());
        check("Turn left onto Main Street".equals(r.getStep().getInstruction()), "the instruction names it: "
            + r.getStep().getInstruction());
        p = north(100);
        r = f.update(p[0], p[1], 2000);
        check(r.getStep().getManeuver().getType() == Maneuvers.DESTINATION, "past the turn, the destination is next");
        check("Main Street".equals(r.getStep().getRoad()), "now on Main Street");
        check(r.getStep().getOnto() == null && "Arrive at the destination".equals(r.getStep().getInstruction()),
            "the destination turns onto nothing");

        // An unnamed route reads plainly.
        RouteFollower plain = new RouteFollower(lShape(null), 2);
        r = plain.update(east(200)[0], east(200)[1], 0);
        check(r.getStep().getOnto() == null && r.getStep().getRoad() == null && "Turn left".equals(r.getStep().getInstruction()),
            "no names, the plain instruction");
    }

    private static void theCardSpellsTheTurnInTheDriversUnits() {
        check("450 ft".equals(SpeedUnit.MPH.distance(137.0)), "449 ft in steps of 50: " + SpeedUnit.MPH.distance(137.0));
        check("0.3 mi".equals(SpeedUnit.MPH.distance(480.0)), "1575 ft is 0.3 mi");
        check("12 mi".equals(SpeedUnit.MPH.distance(19_400.0)), "whole miles from ten");
        check("150 m".equals(SpeedUnit.KMH.distance(137.0)), "metres in steps of 50");
        check("2.5 km".equals(SpeedUnit.KMH.distance(2_480.0)), "tenths of a kilometre");
        check("0 ft".equals(SpeedUnit.MPH.distance(Double.NaN)) && "0 ft".equals(SpeedUnit.MPH.distance(-5)),
            "nothing sensible is zero");

        // Since P7.4 the turn is on the card over the map, not in the header: the distance
        // large, and the street on its own line (DashboardTripTest has the rest of it).
        DashboardLayout layout = new DashboardLayout(0, 0, 1920, 1080, true);
        Dashboard dashboard = new Dashboard(layout, "t", ZoneId.of("UTC"));
        check(dashboard.nextFrame(0).getTurnCard() == null, "no route, no turn");
        dashboard.upcomingTurn(new UpcomingTurn("Turn right onto Main Street", 480.0, 8, 0, "Main Street", null, null, List.of()));
        Dashboard.Snapshot a = dashboard.nextFrame(0);
        check("0.3 mi".equals(a.getTurnCard().getDistance()) && "Main Street".equals(a.getTurnCard().getLine()),
            "distance, then the street: " + a.getTurnCard());
        // A few metres closer reads the same, so the chrome is not repainted for it.
        dashboard.upcomingTurn(new UpcomingTurn("Turn right onto Main Street", 470.0, 8, 0, "Main Street", null, null, List.of()));
        check(a.getTurnCard().equals(dashboard.nextFrame(0).getTurnCard()), "10 m closer is the same card");
        dashboard.upcomingTurn(new UpcomingTurn(UpcomingTurn.REROUTING, null));
        check(UpcomingTurn.REROUTING.equals(dashboard.nextFrame(0).getTurnCard().getLine())
            && dashboard.nextFrame(0).getTurnCard().getDistance() == null, "re-routing has no distance");
        dashboard.upcomingTurn(null);
        check(dashboard.nextFrame(0).getTurnCard() == null, "a cleared route clears the card");
        Dashboard metric = new Dashboard(layout, "t", ZoneId.of("UTC"), SpeedUnit.KMH);
        metric.upcomingTurn(new UpcomingTurn("Keep left", 2_480.0));
        check("2.5 km".equals(metric.nextFrame(0).getTurnCard().getDistance())
            && "Keep left".equals(metric.nextFrame(0).getTurnCard().getLine()), "in the driver's own units, unnamed");
    }

    // --- a real route ------------------------------------------------------------------

    private static void aRealRouteNamesItsRoads(Path massachusetts) throws Exception {
        OsmAndRouter router = new OsmAndRouter(() -> List.of(OsmAndRouter.MapFile.of(massachusetts.toFile())));
        try {
            Route route = router.compute(new RouteRequest(42.4242, -71.2121, 42.3551, -71.0656), 120_000, e -> kotlin.Unit.INSTANCE);
            int stretches = route.getRoadStarts().length;
            int found = 0;
            List<String> seen = new ArrayList<>();
            for (String name : route.getRoadNames()) {
                if (name == null) continue;
                found++;
                if (!seen.contains(name)) seen.add(name);
            }
            check(stretches == route.getManeuverIndices().length + 1 && route.getRoadNames().length == stretches,
                "a stretch from the start and from each maneuver: " + stretches);
            // A cross-town drive in a city: nearly every stretch is on a named street.
            check(found * 10 >= stretches * 7, "most stretches named: " + found + "/" + stretches);
            check(seen.size() >= 3, "more than one or two roads: " + seen.size());
            int at = route.getManeuverIndices()[0];
            check(java.util.Objects.equals(route.roadAt(at), route.getRoadNames()[1]), "a road from the first maneuver on");
            System.out.println("road-names-test: " + found + "/" + stretches + " stretches named, " + seen.size() + " roads");
            if (System.getenv("ROAD_NAMES_VERBOSE") != null) {
                for (int k = 0; k < stretches; k++) {
                    System.out.println("  " + route.getRoadStarts()[k] + " " + route.getRoadNames()[k]);
                }
            }
        } finally {
            router.close();
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("road names: " + what);
        passed++;
    }
}
