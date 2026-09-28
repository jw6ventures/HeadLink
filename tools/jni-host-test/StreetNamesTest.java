// Street names on the guidance loop's maneuvers: which road a stretch of route lies on,
// the instruction that names it, the road being driven, and the turn card's lines. The
// matching runs on synthetic ways here, so it is checked against itself.
//
// With a directory holding W75_N40.rd5 and a Mapsforge file covering Lexington to Boston
// as the two arguments, it also names a real cross-town route through VTM's own reader;
// without them those checks are skipped and the line says so.
import btools.router.VoiceHintReader;
import java.io.File;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.openautoandroid.source.Dashboard;
import org.openautoandroid.source.DashboardLayout;
import org.openautoandroid.source.FollowResult;
import org.openautoandroid.source.FollowState;
import org.openautoandroid.source.Maneuver;
import org.openautoandroid.source.Maneuvers;
import org.openautoandroid.source.NamedWay;
import org.openautoandroid.source.Route;
import org.openautoandroid.source.RouteFollower;
import org.openautoandroid.source.RouteRequest;
import org.openautoandroid.source.Router;
import org.openautoandroid.source.RoutingData;
import org.openautoandroid.source.SpeedUnit;
import org.openautoandroid.source.StreetNames;
import org.openautoandroid.source.UpcomingTurn;
import org.oscim.core.Tag;

public final class StreetNamesTest {
    private static int passed;

    private static final double LAT0 = 42.0;
    private static final double LON0 = -71.0;
    private static final double M_PER_DEG = 6_371_000.0 * Math.PI / 180.0;

    public static void main(String[] args) throws Exception {
        aWayIsLabelledByNameThenRef();
        aStretchIsNamedAfterTheRoadItLiesOn();
        anInstructionNamesTheRoadItTurnsOnto();
        theFollowerSaysWhichRoadIsNextAndWhichIsNow();
        theHeaderSpellsTheTurnInTheDriversUnits();
        String segments = args.length > 0 ? args[0] : "";
        String map = args.length > 1 ? args[1] : "";
        if (!segments.isEmpty() && new File(segments, "W75_N40.rd5").isFile() && new File(map).isFile()) {
            aRealRouteIsNamedFromTheMap(new File(segments), new File(map));
            System.out.println("jni-host-test: ok " + passed + " street name checks");
        } else {
            System.out.println("jni-host-test: ok " + passed
                + " street name checks (real-map checks skipped: need W75_N40.rd5 and MAP_FILE)");
        }
    }

    private static void aWayIsLabelledByNameThenRef() {
        StreetNames names = StreetNames.INSTANCE;
        check("Main Street".equals(names.label(List.of(new Tag("highway", "residential"), new Tag("name", "Main Street")))),
            "a named road is its name");
        check("Research Boulevard".equals(names.label(List.of(new Tag("highway", "trunk"), new Tag("name", "Research Boulevard"),
            new Tag("ref", "US 183")))), "the name wins over the number");
        check("US 183 / TX 45".equals(names.label(List.of(new Tag("highway", "motorway"), new Tag("ref", "US 183;TX 45")))),
            "an unnamed highway is its route numbers");
        check(names.label(List.of(new Tag("waterway", "river"), new Tag("name", "Charles River"))) == null,
            "a river is not a road");
        check(names.label(List.of(new Tag("highway", "service"), new Tag("name", "  "))) == null, "a blank name is no name");
    }

    // --- a synthetic route: 600 m east on Elm Avenue, then left and 600 m north on Main Street

    private static double[] east(double m) { return new double[] {LAT0, LON0 + m / (M_PER_DEG * Math.cos(Math.toRadians(LAT0)))}; }
    private static double[] north(double m) {
        double[] corner = east(600);
        return new double[] {corner[0] + m / M_PER_DEG, corner[1]};
    }

    private static Route lShape() {
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
        return new Route(lat, lon, 1200, 120, new int[] {6}, new int[1], new int[] {VoiceHintReader.TURN_LEFT}, seconds);
    }

    /** A straight way from [a] to [b]. */
    private static NamedWay way(String name, double[] a, double[] b) {
        return new NamedWay(name, new double[] {a[0], b[0]}, new double[] {a[1], b[1]});
    }

    private static final NamedWay ELM = way("Elm Avenue", east(-200), east(900));
    private static final NamedWay MAIN = way("Main Street", north(-300), north(900));

    private static void aStretchIsNamedAfterTheRoadItLiesOn() {
        StreetNames names = StreetNames.INSTANCE;
        Route route = lShape();
        int[] starts = names.stretchStarts(route);
        check(starts.length == 2 && starts[0] == 0 && starts[1] == 6, "a stretch from the start and one from the turn");

        List<kotlin.Pair<Double, Double>> after = names.samples(route, 6, 12);
        check(after.size() == 10, "every 20 m from 12 m to 200 m: " + after.size());
        check(names.nameOf(after, (lat, lon) -> List.of(ELM, MAIN)).equals("Main Street"), "north of the turn is Main Street");
        List<kotlin.Pair<Double, Double>> before = names.samples(route, 0, 6);
        check(names.nameOf(before, (lat, lon) -> List.of(ELM, MAIN)).equals("Elm Avenue"), "east of it is Elm Avenue");

        // A turn onto a road the map names none of: the street being left is close at the
        // junction, and must not lend the unnamed road its name.
        check(names.nameOf(after, (lat, lon) -> List.of(ELM)) == null, "an unnamed road is not named after the one left");
        // A parallel road a lane's width away is not the road.
        NamedWay beside = new NamedWay("Frontage Road", new double[] {north(-300)[0], north(900)[0]},
            new double[] {MAIN.getLongitudes()[0] + 15 / (M_PER_DEG * Math.cos(Math.toRadians(LAT0))),
                MAIN.getLongitudes()[1] + 15 / (M_PER_DEG * Math.cos(Math.toRadians(LAT0)))});
        check(names.nameOf(after, (lat, lon) -> List.of(beside)) == null, "a road 15 m off is not the one driven");
        check(names.nameOf(after, (lat, lon) -> List.of(beside, MAIN)).equals("Main Street"), "the nearer road wins");
        check(names.offset(north(100)[0], north(100)[1], MAIN) < 0.01, "a point on the way is on it");
        check(names.nameOf(List.of(), (lat, lon) -> List.of(MAIN)) == null, "no samples, no name");

        Route named = route.withRoads(starts, new String[] {"Elm Avenue", "Main Street"});
        check("Elm Avenue".equals(named.roadAt(0)) && "Elm Avenue".equals(named.roadAt(5)), "Elm Avenue up to the turn");
        check("Main Street".equals(named.roadAt(6)) && "Main Street".equals(named.roadAt(12)), "Main Street from it");
        check(route.roadAt(3) == null, "a route nobody named has no roads");
        check(named.getManeuverIndices() == route.getManeuverIndices() && named.getPointSeconds() == route.getPointSeconds(),
            "naming changes nothing else");
    }

    private static void anInstructionNamesTheRoadItTurnsOnto() {
        Maneuvers m = Maneuvers.INSTANCE;
        Maneuver right = m.of(VoiceHintReader.TURN_RIGHT, 0);
        check("Turn right onto Main Street".equals(m.instruction(right, "Main Street", "Elm Avenue")), "onto the new road");
        check("Turn right".equals(m.instruction(right, null, "Elm Avenue")), "no name, no claim");
        check("Keep left to stay on I 95".equals(m.instruction(m.of(VoiceHintReader.KEEP_LEFT, 0), "I 95", "I 95")),
            "a road that keeps its name is stayed on");
        check("Make a U-turn".equals(m.instruction(m.of(VoiceHintReader.U_TURN_180, 0), "Elm Avenue", "Elm Avenue")),
            "a U-turn back along the same road says only that");
        check("At the roundabout, take exit 2 onto Quarry Lane".equals(
            m.instruction(m.of(VoiceHintReader.ROUNDABOUT, 2), "Quarry Lane", null)), "a roundabout names its exit road");
        check("Arrive at the destination".equals(m.instruction(m.of(VoiceHintReader.END, 0), "Main Street", "Main Street")),
            "the destination turns onto nothing");
    }

    private static void theFollowerSaysWhichRoadIsNextAndWhichIsNow() {
        Route route = lShape();
        route = route.withRoads(StreetNames.INSTANCE.stretchStarts(route), new String[] {"Elm Avenue", "Main Street"});
        RouteFollower f = new RouteFollower(route, 1);
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

        // An unnamed route reads exactly as before.
        RouteFollower plain = new RouteFollower(lShape(), 2);
        r = plain.update(east(200)[0], east(200)[1], 0);
        check(r.getStep().getOnto() == null && r.getStep().getRoad() == null && "Turn left".equals(r.getStep().getInstruction()),
            "no names, the plain instruction");
    }

    private static void theHeaderSpellsTheTurnInTheDriversUnits() {
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

    private static void aRealRouteIsNamedFromTheMap(File segments, File map) throws Exception {
        File work = Files.createTempDirectory("street-names-test").toFile();
        try {
            File profile = RoutingData.INSTANCE.installProfile(new File(work, "profiles"));
            Route route = new Router(segments, profile, 64)
                .compute(new RouteRequest(42.4242, -71.2121, 42.3551, -71.0656), 120_000, e -> kotlin.Unit.INSTANCE);
            long t0 = System.nanoTime();
            Route named = StreetNames.INSTANCE.name(route, List.of(map));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int stretches = named.getRoadStarts().length;
            int found = 0;
            List<String> seen = new ArrayList<>();
            for (String name : named.getRoadNames()) {
                if (name == null) continue;
                found++;
                if (!seen.contains(name)) seen.add(name);
            }
            check(stretches == route.getManeuverIndices().length + 1 || stretches == route.getManeuverIndices().length,
                "a stretch per maneuver and the start: " + stretches);
            // A cross-town drive in a city: nearly every stretch is on a named street.
            check(found * 10 >= stretches * 7, "most stretches named: " + found + "/" + stretches);
            check(seen.size() >= 3, "more than one or two roads: " + seen.size());
            check(ms < 30_000, "naming takes seconds at most: " + ms + " ms");
            check(StreetNames.INSTANCE.name(route, List.of(new File(work, "missing.map"))) == route,
                "no map, the route as it was");
            if (System.getenv("STREET_NAMES_VERBOSE") != null) {
                System.out.println("street-names: " + found + "/" + stretches + " named in " + ms + " ms");
                for (int k = 0; k < named.getRoadStarts().length; k++) {
                    System.out.println("  " + named.getRoadStarts()[k] + " " + named.getRoadNames()[k]);
                }
            }
        } finally {
            delete(work);
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("street names: " + what);
        passed++;
    }
}
