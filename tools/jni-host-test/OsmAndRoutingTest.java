// OsmAnd's router (vendor/osmand) behind HeadLink's RouteEngine, on a desktop JVM
// (docs/navigation.md, "Routing on OsmAnd maps").
//
// Over SyntheticObf's .obf: a motorway north, an exit ramp east off it with
// junction 23A and its destination signs, a primary road across the ramp's end, and an
// on-ramp from that road back onto the motorway further north. Two ways reach the primary
// road from the motorway's south end: the exit ramp, and on up the motorway and back down
// the on-ramp. So traffic on the ramp has somewhere to send the route, and the second
// alternative has another corridor to find.
//
// What it shows: OsmAnd's file reader and router run on HeadLink's own synthetic file; a
// route comes back as HeadLink's Route, with its exit maneuver, number and sign words from
// the same data, kept through scaling and traffic marking, handed by RouteFollower to the
// card, the cluster and the voice, and carried by the route file; a closure and a costly jam
// move it, a cheap jam does not, and closures with no way round are opened rather than
// failing; alternative 1 avoids alternative 0's roads; a handle stops a search; no maps is
// an error, not a crash; OsmAnd's own errors lose their coordinates before they reach a
// report; a destination or via point no map's routing data holds is refused before any
// search, and a dropped minus sign is told apart.
//
// With a directory of real OsmAnd maps as the argument (OBF_DIR), run under the phone's
// 256 MB heap (jni-host-test.sh passes -Xmx256m), it also routes over them: Taylor to
// downtown Austin with a jam on the way, which the plain search used to give up on with
// OsmAnd's "not enough memory" (2026-11-13, the phone's maps; docs/navigation.md, "Routing
// on OsmAnd maps"); and, when the Austin and San Antonio maps are different OsmAnd builds,
// that the highway hierarchy is skipped and the route still comes back. It proves nothing
// about ART or the phone's speed.
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.jw6ventures.headlink.ExitSign;
import com.jw6ventures.headlink.Maneuver;
import com.jw6ventures.headlink.Maneuvers;
import com.jw6ventures.headlink.NextStep;
import com.jw6ventures.headlink.Nogo;
import com.jw6ventures.headlink.ObfReaderFailures;
import com.jw6ventures.headlink.OsmAndRouter;
import com.jw6ventures.headlink.PositionalFile;
import com.jw6ventures.headlink.PaceRoad;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteFollower;
import com.jw6ventures.headlink.RoutePreview;
import com.jw6ventures.headlink.RouteOptions;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.RoutingException;
import com.jw6ventures.headlink.SearchHandle;
import com.jw6ventures.headlink.SpokenText;
import com.jw6ventures.headlink.TurnCommand;

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
            Files.write(file, SyntheticObf.syntheticObf());
            OsmAndRouter router = new OsmAndRouter(() -> List.of(OsmAndRouter.MapFile.of(file.toFile())));
            try {
                theExitIsAManeuverWithItsSign(router);
                theTimesRunAlongTheRoute(router);
                aClosureOnTheRampSendsItRoundByTheOnRamp(router);
                aJamMovesItOnlyWhenItCostsEnough(router);
                aDestinationOffTheMapsIsRefused(router);
                closuresWithNoWayRoundAreOpened(router);
                theSecondAlternativeTakesTheOtherWay(router);
                aHandleStopsTheSearch(router);
                stayingOnTheMotorwayIsNotTheExit(router);
                anOnRampDrivenBackwardsSaysWhereItGoes(router);
            } finally {
                router.close();
            }
        } finally {
            Files.deleteIfExists(file);
        }
        aFileWithOnlyADescriptorRoutes();
        noMapsIsAnError();
        theSettingsAreTheProfilesParameters();
        theExitWords();
        osmAndErrorsLoseTheirCoordinates();
        String dir = args.length > 0 ? args[0] : "";
        Path austin = dir.isEmpty() ? null : Path.of(dir, "Us_texas_austin_northamerica.obf");
        if (austin == null || !Files.isRegularFile(austin)) {
            System.out.println("jni-host-test: ok " + passed
                + " OsmAnd routing checks (real-file checks skipped: need OBF_DIR with Us_texas_austin_northamerica.obf)");
            return;
        }
        aLongPricedRouteFitsThePhonesMemory(austin);
        Path sanAntonio = Path.of(dir, "Us_texas_san-antonio_northamerica.obf");
        if (Files.isRegularFile(sanAntonio)) mixedBuildsSkipTheHierarchy(austin, sanAntonio);
        System.out.println("jni-host-test: ok " + passed + " OsmAnd routing checks (with real files)");
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
        check(route.getCommands()[0] == TurnCommand.EXIT_RIGHT, "a keep right off the motorway onto its link is the exit");
        int at = route.getManeuverIndices()[0];
        check(near(route.getLatitudes()[at], route.getLongitudes()[at], LAT + 0.005, LON, 5), "at the junction");
        ExitSign sign = route.exitSign(0);
        check(sign != null && "23A".equals(sign.getNumber()), "with the junction's number: " + sign);
        check("I 35 South / Waco / Temple".equals(sign.getToward()), "and the ramp's signs, destination:ref first: " + sign);
        check(route.getRoadStarts().length == 2 && route.getRoadStarts()[0] == 0 && route.getRoadStarts()[1] == at,
            "a stretch of road from the start and from the maneuver");
        check(!route.getTrafficShaped() && !route.getClosuresOpened(), "no traffic was asked about");
        check(route.getTopSpeedKmh() == RouteOptions.DEFAULT_TOP_SPEED_KMH, "timed at the top speed asked for");
        check(route.withTimeScale(1.2).exitSign(0) == sign && route.withNogos(3, false).exitSign(0) == sign,
            "the sign is kept through scaling and traffic marking");
        check(route.remainder(2).exitSign(0) == sign, "and in what is left of the route ahead of it");
        check(route.remainder(at).getManeuverIndices().length == 1 && route.remainder(at).exitSign(0) == sign,
            "including from the exit itself");

        RouteFollower follower = new RouteFollower(route, 7);
        NextStep step = follower.update(LAT + 0.001, LON, 0).getStep();
        check(step != null && step.getSign() == sign, "the follower hands the sign to the step");
        check("Take exit 23A toward I 35 South / Waco / Temple".equals(step.getInstruction()),
            "the cluster's instruction (" + step.getInstruction() + ")");
        check("Exit 23A: I 35 South / Waco / Temple".equals(step.getOnto()), "and its road name, which the card shows: " + step.getOnto());
        check("Take exit 23A toward Interstate 35 South, Waco, Temple".equals(SpokenText.INSTANCE.instruction(step, true)),
            "spoken as road names are, the slashes a pause (" + SpokenText.INSTANCE.instruction(step, true) + ")");
        check("Take exit 23A".equals(SpokenText.INSTANCE.instruction(step, false)), "and with street names off, the number alone");
    }

    /**
     * Keeping on the motorway past the junction is not the exit: OsmAnd does give a maneuver
     * at the junction (the fork with the ramp), and it carries no sign.
     */
    private static void stayingOnTheMotorwayIsNotTheExit(OsmAndRouter router) throws Exception {
        Route straightOn = route(router, new RouteRequest(FROM_LAT, FROM_LON, LAT + 0.0098, LON));
        check(!passesNear(straightOn, RAMP_LAT, RAMP_LON, 10), "staying on the motorway does not take the ramp");
        int atJunction = -1;
        for (int k = 0; k < straightOn.getManeuverIndices().length; k++) {
            int i = straightOn.getManeuverIndices()[k];
            if (near(straightOn.getLatitudes()[i], straightOn.getLongitudes()[i], LAT + 0.005, LON, 5)) atJunction = k;
        }
        check(atJunction >= 0, "there is a maneuver at the junction to judge: commands "
            + java.util.Arrays.toString(straightOn.getCommands()));
        int command = straightOn.getCommands()[atJunction];
        check(command == TurnCommand.CONTINUE || command == TurnCommand.KEEP_LEFT,
            "it keeps to the motorway: command " + command);
        check(straightOn.exitSign(atJunction) == null, "and carries no sign: " + straightOn.exitSign(atJunction));
    }

    /**
     * The on-ramp is stored from the motorway end, so a route from the primary road drives it
     * against its stored order: its sign is destination:backward ("Dallas"), never
     * destination:forward ("Nowhere"), and it has no exit number.
     */
    private static void anOnRampDrivenBackwardsSaysWhereItGoes(OsmAndRouter router) throws Exception {
        Route route = route(router, new RouteRequest(TO_LAT, TO_LON, LAT + 0.0099, LON));
        check(passesNear(route, ON_RAMP_LAT, ON_RAMP_LON, 10), "from the primary road the on-ramp is taken");
        int onto = -1;
        for (int k = 0; k < route.getManeuverIndices().length; k++) {
            int i = route.getManeuverIndices()[k];
            if (near(route.getLatitudes()[i], route.getLongitudes()[i], LAT + 0.0075, LON + 0.008, 5)) onto = k;
        }
        check(onto >= 0, "a maneuver onto the on-ramp where it leaves the primary road: commands "
            + java.util.Arrays.toString(route.getCommands()));
        ExitSign sign = route.exitSign(onto);
        check(sign != null && "Dallas".equals(sign.getToward()), "its destination:backward: " + sign);
        check(sign.getNumber() == null, "and no number, as no junction is on it");
        boolean nowhere = false;
        for (int k = 0; k < route.getManeuverIndices().length; k++) {
            ExitSign s = route.exitSign(k);
            nowhere |= s != null && s.getToward() != null && s.getToward().contains("Nowhere");
        }
        check(!nowhere, "destination:forward is never read for it");
    }

    /** The sign's words and the route file, without a router. */
    private static void theExitWords() throws IOException {
        Maneuver right = Maneuvers.INSTANCE.of(TurnCommand.EXIT_RIGHT, 0);
        Maneuver left = Maneuvers.INSTANCE.of(TurnCommand.EXIT_LEFT, 0);
        Maneuver keepRight = Maneuvers.INSTANCE.of(TurnCommand.KEEP_RIGHT, 0);
        ExitSign both = new ExitSign("12", "El Paso");
        ExitSign number = new ExitSign("12", null);
        ExitSign toward = new ExitSign(null, "El Paso");
        check("Take exit 12 toward El Paso".equals(Maneuvers.INSTANCE.exitInstruction(right, both, both.getToward())), "number and place");
        check("Take exit 12 on the left".equals(Maneuvers.INSTANCE.exitInstruction(left, number, null)), "a left exit says so");
        check("Keep right toward El Paso".equals(Maneuvers.INSTANCE.exitInstruction(keepRight, toward, toward.getToward())),
            "no number: the maneuver's words and the place");
        check("Exit 12".equals(number.getLabel()) && "El Paso".equals(toward.getLabel()), "labels with one part");
        check("Exit 12: El Paso".equals(both.getLabel()), "and with both");
        try {
            new ExitSign(null, null);
            check(false, "an empty sign is refused");
        } catch (IllegalArgumentException expected) {
            check(true, "an empty sign is refused");
        }

        // The route file carries signs, and an unsigned maneuver stays unsigned.
        Route r = new Route(new double[] {LAT, LAT + 0.01, LAT + 0.02}, new double[] {LON, LON, LON}, 2224, 120,
            new int[] {1, 2}, new int[] {0, 0}, new int[] {TurnCommand.EXIT_RIGHT, TurnCommand.TURN_LEFT},
            new float[] {0f, 60f, 120f}, new int[0], new String[0], 121, null, 0, false,
            new ExitSign[] {both, null});
        File dir = Files.createTempDirectory("exitsigns").toFile();
        try {
            File file = RoutePreview.INSTANCE.choiceFile(dir);
            RoutePreview.INSTANCE.writeChoice(file, 3L, r);
            Route back = RoutePreview.INSTANCE.readChoice(file).getSecond();
            check(both.equals(back.exitSign(0)) && back.exitSign(1) == null, "signs cross the route file");
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
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
        // 0.11: each stretch's kind of road, for the learned pace: the motorway, then its exit ramp.
        byte[] kinds = route.getPaceClasses();
        check(kinds.length == route.getPoints(), "a kind of road for every point");
        boolean highway = false, ramp = false;
        for (byte k : kinds) {
            highway |= PaceRoad.INSTANCE.road(k) == PaceRoad.HIGHWAY;
            ramp |= PaceRoad.INSTANCE.road(k) == PaceRoad.RAMP;
        }
        check(highway && ramp, "the motorway and its ramp are told apart: " + java.util.Arrays.toString(kinds));
        RouteRequest slower = new RouteRequest(FROM_LAT, FROM_LON, TO_LAT, TO_LON, null,
            new RouteOptions(false, false, false, false, false, RouteOptions.DEFAULT_TOP_SPEED_KMH, 20, true, true));
        Route scaled = route(router, slower);
        check(Math.abs(scaled.getSeconds() - route.getSeconds() * 1.2) <= 1.0, "the time adjustment scales OsmAnd's time: "
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
        // Cost per metre: a few seconds' delay over the circle does not beat the
        // longer way round; an hour's does.
        Route cheap = route(router, trip(List.of(new Nogo(RAMP_LAT, RAMP_LON, 40, 1.0)), 0));
        check(passesNear(cheap, RAMP_LAT, RAMP_LON, 10), "a slight jam leaves the route on the ramp");
        Route costly = route(router, trip(List.of(new Nogo(RAMP_LAT, RAMP_LON, 40, 100_000.0)), 0));
        check(!passesNear(costly, RAMP_LAT, RAMP_LON, 10), "a bad one sends it round");
        check(costly.getNogoCount() == 1 && costly.getTrafficShaped(), "and the route says traffic shaped it");
    }

    /** The router refuses, before any search, a destination no map's routing data holds. */
    private static void aDestinationOffTheMapsIsRefused(OsmAndRouter router) {
        try {
            route(router, new RouteRequest(FROM_LAT, FROM_LON, LAT + 1.0, LON - 1.0));
            check(false, "a destination off the maps gives no route");
        } catch (RoutingException e) {
            check(!e.getStopped() && OsmAndRouter.NOT_COVERED.equals(e.getMessage()), "a destination off the maps: " + e.getMessage());
        }
        try {
            route(router, new RouteRequest(FROM_LAT, FROM_LON, TO_LAT, -TO_LON));
            check(false, "a mirrored destination gives no route");
        } catch (RoutingException e) {
            check(OsmAndRouter.MIRRORED.equals(e.getMessage()), "a dropped minus sign is told apart: " + e.getMessage());
        }
        try {
            route(router, new RouteRequest(FROM_LAT, FROM_LON, TO_LAT, TO_LON, null, new RouteOptions(), 0, LAT - 1.0, LON, List.of()));
            check(false, "a via point off the maps gives no route");
        } catch (RoutingException e) {
            check(OsmAndRouter.STOP_NOT_COVERED.equals(e.getMessage()), "and a via point off them, with the destination on them: " + e.getMessage());
        }
        // The check allows a 250 m box round each point: a destination 100 m past the routing
        // section's box is not refused as uncovered, 1 km past it is.
        double west = LON - 0.01 - 100 / (111_320 * Math.cos(Math.toRadians(LAT)));
        try {
            route(router, new RouteRequest(FROM_LAT, FROM_LON, LAT + 0.005, west));
            check(true, "a destination 100 m off the box routes");
        } catch (RoutingException e) {
            check(!OsmAndRouter.NOT_COVERED.equals(e.getMessage()), "a destination 100 m off the box is not called uncovered: " + e.getMessage());
        }
        double farWest = LON - 0.01 - 1_000 / (111_320 * Math.cos(Math.toRadians(LAT)));
        try {
            route(router, new RouteRequest(FROM_LAT, FROM_LON, LAT + 0.005, farWest));
            check(false, "a destination 1 km off the box gives no route");
        } catch (RoutingException e) {
            check(OsmAndRouter.NOT_COVERED.equals(e.getMessage()), "1 km off it is: " + e.getMessage());
        }
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

    /**
     * The maps folder's files: a descriptor and no path this app may open (PositionalFile).
     * The path given is one that cannot be opened, so the route can only come through the
     * opener. The phone's source reads with pread; this one with a RandomAccessFile.
     */
    private static void aFileWithOnlyADescriptorRoutes() throws Exception {
        Path file = Files.createTempFile("osmand-descriptor", ".obf");
        FileSource[] source = new FileSource[1];
        try {
            Files.write(file, SyntheticObf.syntheticObf());
            String nowhere = file + ".not-here";
            OsmAndRouter router = new OsmAndRouter(() -> List.of(new OsmAndRouter.MapFile("descriptor", "synthetic.obf", nowhere, () -> {
                try {
                    source[0] = new FileSource(file);
                    return new PositionalFile(source[0]);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            })));
            try {
                Route route = route(router, trip(List.of(), 0));
                check(route.getPoints() >= 2, "a file opened only through its descriptor routes: " + route.getPoints() + " points");
            } finally {
                router.close();
            }
            check(source[0] != null && source[0].closed, "closing the router closes the descriptor");
            String before = ObfReaderFailures.INSTANCE.report();
            OsmAndRouter refused = new OsmAndRouter(() -> List.of(new OsmAndRouter.MapFile("refused", "synthetic.obf", nowhere)));
            try {
                route(refused, trip(List.of(), 0));
                check(false, "a file that cannot be opened gives no route");
            } catch (RoutingException e) {
                check(OsmAndRouter.NOT_COVERED.equals(e.getMessage()), "a file that cannot be opened is no map: " + e.getMessage());
            }
            String after = ObfReaderFailures.INSTANCE.report();
            check(!after.equals(before) && after.contains("obf_reader_failure_last=FileNotFoundException")
                && !after.contains(nowhere), "the failure is counted, by class only: " + after);
        } finally {
            Files.deleteIfExists(file);
        }
        aPositionalFileReadsLikeAFile();
    }

    /** What OsmAnd's reader asks of its RandomAccessFile, through a source. */
    private static void aPositionalFileReadsLikeAFile() throws Exception {
        Path file = Files.createTempFile("positional-file", ".bin");
        try {
            byte[] bytes = new byte[300];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
            Files.write(file, bytes);
            try (PositionalFile f = new PositionalFile(new FileSource(file))) {
                check(f.length() == 300 && f.getFilePointer() == 0, "length and start");
                byte[] got = new byte[10];
                f.seek(250);
                f.readFully(got, 0, 10);
                check(got[0] == (byte) 250 && got[9] == (byte) 3 && f.getFilePointer() == 260, "seek then readFully");
                check(f.read() == 4 && f.getFilePointer() == 261, "a single byte, unsigned");
                f.seek(295);
                try {
                    f.readFully(got, 0, 10);
                    check(false, "reading past the end fails");
                } catch (java.io.EOFException e) {
                    check(true, "reading past the end is EOF");
                }
                f.seek(300);
                check(f.read() == -1, "-1 at the end");
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** A positional source over a path, standing in for the phone's descriptor. */
    private static final class FileSource implements PositionalFile.Source {
        private final RandomAccessFile file;
        boolean closed;

        FileSource(Path path) throws IOException {
            file = new RandomAccessFile(path.toFile(), "r");
        }

        @Override
        public long size() throws IOException {
            return file.length();
        }

        @Override
        public synchronized int read(byte[] b, int off, int len, long position) throws IOException {
            file.seek(position);
            return file.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            file.close();
        }
    }

    private static void noMapsIsAnError() {
        OsmAndRouter none = new OsmAndRouter(List::of);
        try {
            route(none, trip(List.of(), 0));
            check(false, "no maps, no route");
        } catch (RoutingException e) {
            check(!e.getStopped() && OsmAndRouter.NOT_COVERED.equals(e.getMessage()), "no maps: the destination is outside them: " + e.getMessage());
        }
    }

    private static void theSettingsAreTheProfilesParameters() {
        Map<String, String> plain = OsmAndRouter.profileParameters(new RouteOptions());
        check(plain.size() == 1 && plain.containsKey("max_speed"), "by default only the top speed: " + plain);
        check(Math.abs(Double.parseDouble(plain.get("max_speed")) - RouteOptions.DEFAULT_TOP_SPEED_KMH / 3.6) < 1e-9,
            "in metres a second");
        Map<String, String> all = OsmAndRouter.profileParameters(
            new RouteOptions(true, true, true, true, true, 105, 0, true, true));
        check("true".equals(all.get("avoid_motorway")) && "true".equals(all.get("avoid_toll"))
            && "true".equals(all.get("avoid_unpaved")), "the three switches OsmAnd's car profile has: " + all);
        check(all.size() == 4, "and nothing for going round towns or allowing tracks, which it has not");
    }

    private static void osmAndErrorsLoseTheirCoordinates() {
        String said = OsmAndRouter.placeless("There is not enough memory 30.56981, -97.40961 -> 30.26721, -97.74313 - limit  85  MB");
        check(!said.matches(".*\\d+\\.\\d{3,}.*"), "no coordinate survives: " + said);
        check(said.contains("not enough memory") && said.contains("85"), "the rest of the message does: " + said);
    }

    /** Taylor to downtown Austin, 58 km, with a jam in Round Rock: the plain search's route. */
    private static void aLongPricedRouteFitsThePhonesMemory(Path austin) throws Exception {
        check(Runtime.getRuntime().maxMemory() <= 300L * 1024 * 1024, "run under the phone's heap, not the desktop's");
        OsmAndRouter router = new OsmAndRouter(() -> List.of(OsmAndRouter.MapFile.of(austin.toFile())));
        try {
            RouteRequest request = new RouteRequest(30.5698, -97.4097, 30.2672, -97.7431, null, new RouteOptions(), 0, null, null,
                List.of(new Nogo(30.5083, -97.6789, 200, 2.0)));
            Route route = route(router, request);
            check(route.getDistanceM() > 50_000 && route.getDistanceM() < 70_000, "the whole way: " + route.getDistanceM() + " m");
            check(near(route.getLatitudes()[route.getPoints() - 1], route.getLongitudes()[route.getPoints() - 1], 30.2672, -97.7431, 250),
                "ending downtown");
        } finally {
            router.close();
        }
    }

    /** Downtown Austin to downtown San Antonio over the two maps. */
    private static void mixedBuildsSkipTheHierarchy(Path austin, Path sanAntonio) throws Exception {
        List<OsmAndRouter.MapFile> maps = List.of(OsmAndRouter.MapFile.of(austin.toFile()), OsmAndRouter.MapFile.of(sanAntonio.toFile()));
        java.util.Set<Long> editions = new java.util.HashSet<>();
        List<net.osmand.binary.BinaryMapIndexReader> readers = new java.util.ArrayList<>();
        try {
            for (OsmAndRouter.MapFile map : maps) {
                java.io.File file = new java.io.File(map.getPath());
                net.osmand.binary.BinaryMapIndexReader reader =
                    new net.osmand.binary.BinaryMapIndexReader(new java.io.RandomAccessFile(file, "r"), file);
                readers.add(reader);
                for (var region : reader.getHHRoutingIndexes()) if (region.profile.equals("car")) editions.add(region.edition);
            }
            List<net.osmand.data.LatLon> ends = List.of(new net.osmand.data.LatLon(30.2672, -97.7431), new net.osmand.data.LatLon(29.4241, -98.4936));
            check(OsmAndRouter.hierarchyCovers(readers, ends) == (editions.size() == 1),
                "the hierarchy only when one build covers both ends (" + editions.size() + " builds)");
            check(OsmAndRouter.hierarchyCovers(readers, List.of(ends.get(0), new net.osmand.data.LatLon(30.5083, -97.6789))),
                "and a trip inside one map has it");
        } finally {
            for (var reader : readers) reader.close();
        }
        OsmAndRouter router = new OsmAndRouter(() -> maps);
        try {
            long started = System.nanoTime();
            Route route = route(router, new RouteRequest(30.2672, -97.7431, 29.4241, -98.4936, null, new RouteOptions(), 0, null, null, List.of()));
            long ms = (System.nanoTime() - started) / 1_000_000;
            check(route.getDistanceM() > 110_000 && route.getDistanceM() < 150_000, "Austin to San Antonio: " + route.getDistanceM() + " m");
            check(editions.size() == 1 || ms < 15_000, "without the hierarchy's dead end first: " + ms + " ms");
        } finally {
            router.close();
        }
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
