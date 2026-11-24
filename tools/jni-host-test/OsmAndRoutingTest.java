// OsmAnd's router (vendor/osmand) behind HeadLink's RouteEngine, on a desktop JVM
// (docs/navigation.md, "Routing on OsmAnd maps").
//
// Over SyntheticObf's .obf: a motorway north, an exit ramp east off it with
// junction 23A and its destination signs, a primary road across the ramp's end, and an
// on-ramp from that road back onto the motorway further north. Two ways reach the primary
// road from the motorway's south end: the exit ramp, and on up the motorway and back down
// the on-ramp. So traffic on the ramp has somewhere to send the route, and the second
// alternative has another corridor to find. Past the primary road, a track is the only
// short way to a corner a long unclassified loop also reaches.
//
// What it shows: OsmAnd's file reader and router run on HeadLink's own synthetic file; a
// route comes back as HeadLink's Route, with its exit maneuver, number and sign words from
// the same data, kept through scaling and traffic marking, handed by RouteFollower to the
// card, the cluster and the voice, and carried by the route file; a closure and a costly jam
// move it, a cheap jam does not, and closures with no way round are opened rather than
// failing; alternative 1 avoids alternative 0's roads; a handle stops a search; no maps is
// an error, not a crash; OsmAnd's own errors lose their coordinates before they reach a
// report; a destination or via point no map's routing data holds is refused before any
// search, and a dropped minus sign is told apart; the track is driven only when tracks
// are allowed.
//
// With a directory of real OsmAnd maps as the argument (OBF_DIR), run under the phone's
// 256 MB heap (jni-host-test.sh passes -Xmx256m), it also routes over them, each section
// saying what it ran or which maps it lacked: Taylor to downtown Austin with a jam on the
// way, which the plain search used to give up on with OsmAnd's "not enough memory"
// (2026-11-13, the phone's maps; docs/navigation.md, "Routing on OsmAnd maps"); when the
// Austin and San Antonio maps are different OsmAnd builds, that the highway hierarchy is
// skipped and the route still comes back; a Hill Country route that takes a track only
// when tracks are allowed; Austin to Temple going round the towns on I-35; Waco to Dallas,
// whose maps do not meet without Tyler's; and a closure that moves a route whose
// destination lies far from any road without being called a cut-off. It proves nothing
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
import com.jw6ventures.headlink.TrafficCost;
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
                aTrackIsTakenOnlyWhenAllowed(router);
                anEndOnlyATrackReachesIsRefused(router);
                aStopOnAnIslandIsNotConnected(router);
                aStopDuringALaterSearchStillStops(router);
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
        aDisplacedEndIsJudgedPerSnap();
        aBrokenTownListLeavesRoutingAlone();
        String dir = args.length > 0 ? args[0] : "";
        if (dir.isEmpty()) {
            System.out.println("jni-host-test: ok " + passed + " OsmAnd routing checks (real-file checks skipped: no OBF_DIR given)");
            return;
        }
        Path austin = texas(dir, "austin"), sanAntonio = texas(dir, "san-antonio"), waco = texas(dir, "waco"),
            dallas = texas(dir, "dallas"), tyler = texas(dir, "tyler");
        if (real("a long priced route in the phone's memory", austin)) aLongPricedRouteFitsThePhonesMemory(austin);
        if (real("mixed builds skip the hierarchy", austin, sanAntonio)) mixedBuildsSkipTheHierarchy(austin, sanAntonio);
        if (real("a Hill Country track only when allowed", austin)) aRealTrackIsTakenOnlyWhenAllowed(austin);
        if (real("Austin to Temple round the towns", austin, waco)) austinToTempleGoesRoundTheTowns(austin, waco);
        if (real("Waco to Dallas across maps that do not meet", waco, dallas, tyler)) wacoToDallasNeedsTheMapBetween(waco, dallas, tyler);
        if (real("a closure far from an off-road destination", dallas)) aClosureIsNotBlamedForAnOffRoadDestination(dallas);
        System.out.println("jni-host-test: ok " + passed + " OsmAnd routing checks (with real files)");
    }

    private static Path texas(String dir, String region) {
        return Path.of(dir, "Us_texas_" + region + "_northamerica.obf");
    }

    /** Says which real-file section runs, or which of [files] it lacks; true when it runs. */
    private static boolean real(String what, Path... files) {
        List<String> missing = new java.util.ArrayList<>();
        for (Path f : files) if (!Files.isRegularFile(f)) missing.add(f.getFileName().toString());
        System.out.println("jni-host-test: OsmAnd routing, " + what
            + (missing.isEmpty() ? "" : ": skipped, OBF_DIR lacks " + String.join(", ", missing)));
        return missing.isEmpty();
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
        // Seconds per metre (TrafficCost): five seconds' delay over the circle does not beat
        // the longer way round; an hour's does.
        Route cheap = route(router, trip(List.of(new Nogo(RAMP_LAT, RAMP_LON, 40, TrafficCost.INSTANCE.pointWeight(5, 1, 3, 40))), 0));
        check(passesNear(cheap, RAMP_LAT, RAMP_LON, 10), "a slight jam leaves the route on the ramp");
        Route costly = route(router, trip(List.of(new Nogo(RAMP_LAT, RAMP_LON, 40, TrafficCost.INSTANCE.pointWeight(3600, 1, 3, 40))), 0));
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
        check(all.size() == 4, "and nothing for going round towns or allowing tracks, which the router applies itself");
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
                List.of(new Nogo(30.5083, -97.6789, 200, TrafficCost.INSTANCE.pointWeight(40, 1, 3, 200))));
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

    private static RouteOptions options(boolean bypassTowns, boolean allowTracks) {
        return new RouteOptions(false, false, false, bypassTowns, allowTracks, RouteOptions.DEFAULT_TOP_SPEED_KMH, 0, true, true);
    }

    private static RouteRequest trip(double fromLat, double fromLon, double toLat, double toLon, RouteOptions options, List<Nogo> nogos) {
        return new RouteRequest(fromLat, fromLon, toLat, toLon, null, options, 0, null, null, nogos);
    }

    /** [route], with [searches] counting the searches it took (one handle each). */
    private static Route counted(OsmAndRouter router, RouteRequest request, int[] searches) throws RoutingException {
        searches[0] = 0;
        return router.compute(request, 30_000, handle -> {
            searches[0]++;
            return kotlin.Unit.INSTANCE;
        });
    }

    /** The track is the only short way to its corner: refused by default, driven when allowed. */
    private static void aTrackIsTakenOnlyWhenAllowed(OsmAndRouter router) throws Exception {
        double cornerLat = LAT + 0.0075, cornerLon = LON + 0.020;
        Route refused = route(router, trip(FROM_LAT, FROM_LON, cornerLat, cornerLon, options(false, false), List.of()));
        Route allowed = route(router, trip(FROM_LAT, FROM_LON, cornerLat, cornerLon, options(false, true), List.of()));
        check(!passesNear(refused, LAT + 0.0075, LON + 0.016, 20) && passesNear(refused, LAT + 0.0325, LON + 0.016, 20),
            "tracks off: the long way round the loop, " + refused.getDistanceM() + " m");
        check(passesNear(allowed, LAT + 0.0075, LON + 0.016, 5), "tracks allowed: the track, " + allowed.getDistanceM() + " m");
        check(allowed.getDistanceM() + 4_000 < refused.getDistanceM(), "which is the short way");
        int last = refused.getPoints() - 1;
        check(near(refused.getLatitudes()[last], refused.getLongitudes()[last], cornerLat, cornerLon, 30), "both reach the corner");
    }

    /**
     * The track's middle is 384 m from any other road. With tracks refused, OsmAnd would
     * snap there and the follower announce arrival 384 m short; instead the route is refused,
     * saying why, and the same for a start there. Allowed, both are routed.
     */
    private static void anEndOnlyATrackReachesIsRefused(OsmAndRouter router) throws Exception {
        double midLat = LAT + 0.0075, midLon = LON + 0.016;
        try {
            route(router, trip(FROM_LAT, FROM_LON, midLat, midLon, options(false, false), List.of()));
            check(false, "a destination only a track reaches is not routed with tracks off");
        } catch (RoutingException e) {
            check(!e.getStopped() && OsmAndRouter.DESTINATION_BY_TRACK.equals(e.getMessage()),
                "a destination only a track reaches, tracks off: " + e.getMessage());
        }
        try {
            route(router, trip(midLat, midLon, TO_LAT, TO_LON, options(false, false), List.of()));
            check(false, "nor is a start only a track reaches");
        } catch (RoutingException e) {
            check(!e.getStopped() && OsmAndRouter.START_BY_TRACK.equals(e.getMessage()), "a start only a track reaches: " + e.getMessage());
        }
        Route to = route(router, trip(FROM_LAT, FROM_LON, midLat, midLon, options(false, true), List.of()));
        int last = to.getPoints() - 1;
        check(near(to.getLatitudes()[last], to.getLongitudes()[last], midLat, midLon, 30), "tracks allowed, the destination is reached");
        Route from = route(router, trip(midLat, midLon, TO_LAT, TO_LON, options(false, true), List.of()));
        check(near(from.getLatitudes()[0], from.getLongitudes()[0], midLat, midLon, 30), "and the start set off from");
    }

    /** A stop on the island, which no road joins: no route, said without blaming the start and destination alone. */
    private static void aStopOnAnIslandIsNotConnected(OsmAndRouter router) {
        try {
            route(router, new RouteRequest(FROM_LAT, FROM_LON, TO_LAT, TO_LON, null, new RouteOptions(), 0, LAT - 0.045, LON + 0.0015, List.of()));
            check(false, "a stop no road reaches gives no route");
        } catch (RoutingException e) {
            check(!e.getStopped() && OsmAndRouter.NOT_CONNECTED.equals(e.getMessage()) && e.getMessage().contains("all the route's points"),
                "a stop no road reaches: " + e.getMessage());
        }
    }

    /**
     * Both ramps closed: the closed search, the check of it without closures and the search
     * with them opened each hand out a handle. A stop on the second still stops the request,
     * whatever that search was for.
     */
    private static void aStopDuringALaterSearchStillStops(OsmAndRouter router) {
        List<Nogo> both = List.of(new Nogo(RAMP_LAT, RAMP_LON, 40, null), new Nogo(ON_RAMP_LAT, ON_RAMP_LON, 40, null));
        int[] handles = {0};
        try {
            router.compute(trip(both, 0), 30_000, handle -> {
                if (++handles[0] == 2) handle.terminate();
                return kotlin.Unit.INSTANCE;
            });
            check(false, "a request stopped on its second search returns no route");
        } catch (RoutingException e) {
            check(e.getStopped() && handles[0] == 2, "a stop on the second search stops it: " + e.getMessage() + ", " + handles[0] + " handles");
        }
    }

    /**
     * The rule [cutOff] and the track check judge an end by: a reference within the snap
     * makes any miss past it the rule's doing; a reference already past it, only a further
     * miss. The review case: closed 300 m off, open 100 m, is a cut-off.
     */
    private static void aDisplacedEndIsJudgedPerSnap() {
        check(OsmAndRouter.displaced(300, 100), "300 m off where the open route is 100 m off is displaced");
        check(!OsmAndRouter.displaced(240, 100), "240 m is within the snap");
        check(!OsmAndRouter.displaced(456 + 200, 456), "where even the open route ends 456 m off, 200 m more is not");
        check(OsmAndRouter.displaced(456 + 260, 456), "260 m more is");
        check(!OsmAndRouter.displaced(100, 600), "nearer than the reference never is");
    }

    /**
     * A map whose town list will not decode: going round towns skips it, and routing over
     * the same map works, then and on later requests, with the failure counted.
     */
    private static void aBrokenTownListLeavesRoutingAlone() throws Exception {
        Path file = Files.createTempFile("osmand-broken-towns", ".obf");
        try {
            Files.write(file, SyntheticObf.syntheticObfWithBrokenTowns());
            OsmAndRouter router = new OsmAndRouter(() -> List.of(OsmAndRouter.MapFile.of(file.toFile())));
            try {
                String before = ObfReaderFailures.INSTANCE.report();
                Route round = route(router, trip(FROM_LAT, FROM_LON, TO_LAT, TO_LON, options(true, false), List.of()));
                check(round.getPoints() >= 2, "a route going round towns comes back over a map whose town list is broken");
                check(!ObfReaderFailures.INSTANCE.report().equals(before), "the broken list is counted");
                Route plain = route(router, trip(FROM_LAT, FROM_LON, TO_LAT, TO_LON, options(false, false), List.of()));
                check(plain.getPoints() >= 2 && passesNear(plain, RAMP_LAT, RAMP_LON, 10), "and a later route over it is the usual one");
            } finally {
                router.close();
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * In the Hill Country south-west of Johnson City, OsmAnd's own route between two county
     * roads takes 1.2 km of a farm track (7.4 km in all; 2026-11-24 maps). Allowed, the
     * hierarchy's route stands; refused, the hierarchy's route is searched again plainly
     * and goes the long way. A route with no track near is searched once either way.
     */
    private static void aRealTrackIsTakenOnlyWhenAllowed(Path austin) throws Exception {
        double trackLat = 30.23755, trackLon = -98.48473;
        OsmAndRouter router = new OsmAndRouter(() -> List.of(OsmAndRouter.MapFile.of(austin.toFile())));
        try {
            int[] searches = {0};
            Route allowed = counted(router, trip(30.2309, -98.4762, 30.2677, -98.4995, options(false, true), List.of()), searches);
            int allowedSearches = searches[0];
            Route refused = counted(router, trip(30.2309, -98.4762, 30.2677, -98.4995, options(false, false), List.of()), searches);
            int refusedSearches = searches[0];
            System.out.println("jni-host-test: Hill Country track: allowed " + allowed.getDistanceM() + " m, " + allowed.getSeconds()
                + " s, " + allowedSearches + " search; refused " + refused.getDistanceM() + " m, " + refused.getSeconds() + " s, "
                + refusedSearches + " searches");
            check(passesNear(allowed, trackLat, trackLon, 15), "allowed, the route takes the track");
            check(allowedSearches == 1, "over the hierarchy alone: " + allowedSearches);
            check(!passesNear(refused, trackLat, trackLon, 50), "refused, it does not");
            check(refusedSearches == 2, "the hierarchy's route drove the track, so a plain search followed: " + refusedSearches);
            check(refused.getDistanceM() > allowed.getDistanceM() + 5_000, "and goes the long way round");
            counted(router, trip(30.2672, -97.7431, 30.5083, -97.6789, options(false, false), List.of()), searches);
            check(searches[0] == 1, "downtown Austin to Round Rock, no track near: one search, " + searches[0]);
        } finally {
            router.close();
        }
    }

    /**
     * Downtown Austin to downtown Temple, I-35 through Round Rock, Georgetown and Belton,
     * going round the towns: far less of it inside their circles, a sane length (BRouter's
     * went 109 to 132 km), and still to downtown Temple, inside Temple's own circle.
     */
    private static void austinToTempleGoesRoundTheTowns(Path austin, Path waco) throws Exception {
        double fromLat = 30.2672, fromLon = -97.7431, toLat = 31.0982, toLon = -97.3428;
        List<OsmAndRouter.MapFile> maps = List.of(OsmAndRouter.MapFile.of(austin.toFile()), OsmAndRouter.MapFile.of(waco.toFile()));
        List<double[]> circles = townCircles(maps, new double[][] {{fromLat, fromLon}, {toLat, toLon}});
        OsmAndRouter router = new OsmAndRouter(() -> maps);
        try {
            Route through = route(router, trip(fromLat, fromLon, toLat, toLon, options(false, false), List.of()));
            long started = System.nanoTime();
            Route round = route(router, trip(fromLat, fromLon, toLat, toLon, options(true, false), List.of()));
            long ms = (System.nanoTime() - started) / 1_000_000;
            double inThrough = metresInside(through, circles), inRound = metresInside(round, circles);
            System.out.printf("jni-host-test: Austin to Temple, %d town circles on the way: through %.1f km, %d min, %.1f km inside;"
                    + " round %.1f km, %d min, %.1f km inside, searched in %d ms%n", circles.size(), through.getDistanceM() / 1000.0,
                through.getSeconds() / 60, inThrough / 1000, round.getDistanceM() / 1000.0, round.getSeconds() / 60, inRound / 1000, ms);
            check(inRound < inThrough / 4, "round the towns, far less inside them");
            check(round.getDistanceM() > through.getDistanceM() && round.getDistanceM() < through.getDistanceM() * 1.4,
                "at a sane length");
            int last = round.getPoints() - 1;
            check(near(round.getLatitudes()[last], round.getLongitudes()[last], toLat, toLon, 250), "still reaching downtown Temple");
        } finally {
            router.close();
        }
    }

    /**
     * The router's town circles round a trip: every city (10 km) and town (4 km) of [maps],
     * less those holding one of its [ends], as {lat, lon, radius}.
     */
    private static List<double[]> townCircles(List<OsmAndRouter.MapFile> maps, double[][] ends) throws IOException {
        List<double[]> circles = new java.util.ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (OsmAndRouter.MapFile map : maps) {
            File file = new File(map.getPath());
            net.osmand.binary.BinaryMapIndexReader reader = new net.osmand.binary.BinaryMapIndexReader(new RandomAccessFile(file, "r"), file);
            try {
                for (net.osmand.data.City city : reader.getCities(null,
                    net.osmand.binary.BinaryMapAddressReaderAdapter.CityBlocks.CITY_TOWN_TYPE, null, null)) {
                    if (city.getType() != net.osmand.data.City.CityType.CITY && city.getType() != net.osmand.data.City.CityType.TOWN) continue;
                    if (!seen.add(city.getId())) continue;
                    double lat = city.getLocation().getLatitude(), lon = city.getLocation().getLongitude(), r = city.getType().getRadius();
                    boolean holdsAnEnd = false;
                    for (double[] end : ends) holdsAnEnd |= near(end[0], end[1], lat, lon, r);
                    if (!holdsAnEnd) circles.add(new double[] {lat, lon, r});
                }
            } finally {
                reader.close();
            }
        }
        return circles;
    }

    /** Metres of [route] whose pieces lie in some circle (by the piece's middle). */
    private static double metresInside(Route route, List<double[]> circles) {
        double inside = 0;
        for (int i = 0; i + 1 < route.getPoints(); i++) {
            double lat = (route.getLatitudes()[i] + route.getLatitudes()[i + 1]) / 2, lon = (route.getLongitudes()[i] + route.getLongitudes()[i + 1]) / 2;
            for (double[] c : circles) {
                if (near(lat, lon, c[0], c[1], c[2])) {
                    double[] d = local(route.getLatitudes()[i + 1], route.getLongitudes()[i + 1], route.getLatitudes()[i], route.getLongitudes()[i]);
                    inside += Math.hypot(d[0], d[1]);
                    break;
                }
            }
        }
        return inside;
    }

    /**
     * Waco to Dallas with only the Waco and Dallas maps: their routing data stops 8 km apart,
     * the Tyler map holding the strip between, so no road joins them and the failure says
     * so, without OsmAnd's exception's name; short routes inside either map still work.
     * With Tyler's map too, the route comes back.
     */
    private static void wacoToDallasNeedsTheMapBetween(Path waco, Path dallas, Path tyler) throws Exception {
        double wacoNorth = -90, dallasSouth = 90;
        for (Path p : List.of(waco, dallas)) {
            net.osmand.binary.BinaryMapIndexReader reader = new net.osmand.binary.BinaryMapIndexReader(new RandomAccessFile(p.toFile(), "r"), p.toFile());
            try {
                for (var index : reader.getRoutingIndexes()) {
                    for (var box : index.getSubregions()) {
                        if (p == waco) wacoNorth = Math.max(wacoNorth, net.osmand.util.MapUtils.get31LatitudeY(box.top));
                        else dallasSouth = Math.min(dallasSouth, net.osmand.util.MapUtils.get31LatitudeY(box.bottom));
                    }
                }
            } finally {
                reader.close();
            }
        }
        System.out.printf("jni-host-test: the Waco map's roads end at %.4f N, the Dallas map's begin at %.4f N%n", wacoNorth, dallasSouth);
        check(wacoNorth < dallasSouth, "the two maps do not meet");
        OsmAndRouter two = new OsmAndRouter(() -> List.of(OsmAndRouter.MapFile.of(waco.toFile()), OsmAndRouter.MapFile.of(dallas.toFile())));
        try {
            for (double[] t : new double[][] {{31.55, -97.15, 32.78, -96.80}, {32.78, -96.80, 31.55, -97.15}}) {
                try {
                    route(two, new RouteRequest(t[0], t[1], t[2], t[3]));
                    check(false, "no route across maps that do not meet");
                } catch (RoutingException e) {
                    check(!e.getStopped() && OsmAndRouter.NOT_CONNECTED.equals(e.getMessage()), "no road joins them, and it says so: " + e.getMessage());
                }
            }
            check(route(two, new RouteRequest(31.55, -97.15, 31.50, -97.20)).getDistanceM() > 5_000, "inside the Waco map a route comes back");
            check(route(two, new RouteRequest(32.78, -96.80, 32.85, -96.85)).getDistanceM() > 5_000, "and inside the Dallas map");
        } finally {
            two.close();
        }
        OsmAndRouter three = new OsmAndRouter(() -> List.of(OsmAndRouter.MapFile.of(waco.toFile()), OsmAndRouter.MapFile.of(dallas.toFile()),
            OsmAndRouter.MapFile.of(tyler.toFile())));
        try {
            Route route = route(three, new RouteRequest(31.55, -97.15, 32.78, -96.80));
            System.out.println("jni-host-test: Waco to Dallas with the Tyler map: " + route.getDistanceM() + " m, " + route.getSeconds() + " s");
            check(route.getDistanceM() > 140_000 && route.getDistanceM() < 175_000, "with the Tyler map between, Waco to Dallas comes back");
        } finally {
            three.close();
        }
    }

    /**
     * 2026-11-24: from downtown Dallas to a destination 455 m from the nearest road the route
     * may end on, a closure on I-35E, drawn 3 m off the road (circles of 8 m every 50 m, as
     * HERE's are placed), moved the route off it, and the route came back with its closures
     * opened: it ended far from the destination, which was taken for the closures cutting it
     * off. The route without them ends just as far off, so they cut nothing off.
     */
    private static void aClosureIsNotBlamedForAnOffRoadDestination(Path dallas) throws Exception {
        double fromLat = 32.7800, fromLon = -96.8050, toLat = 32.8450, toLon = -96.8850;
        OsmAndRouter router = new OsmAndRouter(() -> List.of(OsmAndRouter.MapFile.of(dallas.toFile())));
        try {
            Route plain = route(router, new RouteRequest(fromLat, fromLon, toLat, toLon));
            double plainEnd = endDistance(plain, toLat, toLon);
            check(plainEnd > OsmAndRouter.END_SNAP_M, "the plain route ends " + Math.round(plainEnd) + " m from the destination");
            int n = plain.getPoints();
            double[] along = new double[n];
            for (int i = 1; i < n; i++) {
                double[] d = local(plain.getLatitudes()[i], plain.getLongitudes()[i], plain.getLatitudes()[i - 1], plain.getLongitudes()[i - 1]);
                along[i] = along[i - 1] + Math.hypot(d[0], d[1]);
            }
            // c: the first place 2 km or more along where the next 3 km is all freeway.
            int c = -1;
            for (int i = 0; i < n && c < 0; i++) {
                if (along[i] < 2_000) continue;
                boolean freeway = along[n - 1] >= along[i] + 3_000;
                for (int j = i; j < n && along[j] <= along[i] + 3_000; j++) {
                    freeway &= PaceRoad.INSTANCE.road(plain.getPaceClasses()[j]) == PaceRoad.HIGHWAY;
                }
                if (freeway) c = i;
            }
            check(c >= 0, "3 km of freeway on the way");
            List<Nogo> closure = new java.util.ArrayList<>();
            for (double s = along[c] + 1_000; s <= along[c] + 1_300; s += 50) {
                int i = c;
                while (along[i + 1] < s) i++;
                double t = (s - along[i]) / (along[i + 1] - along[i]);
                double lat = plain.getLatitudes()[i] + t * (plain.getLatitudes()[i + 1] - plain.getLatitudes()[i]);
                double lon = plain.getLongitudes()[i] + t * (plain.getLongitudes()[i + 1] - plain.getLongitudes()[i]);
                // 3 m to the right of the way driven.
                double[] d = local(plain.getLatitudes()[i + 1], plain.getLongitudes()[i + 1], plain.getLatitudes()[i], plain.getLongitudes()[i]);
                double length = Math.hypot(d[0], d[1]), m = 6_371_000.0 * Math.PI / 180.0;
                lat += -3 * d[0] / length / m;
                lon += 3 * d[1] / length / (m * Math.cos(Math.toRadians(lat)));
                closure.add(new Nogo(lat, lon, 8, null));
            }
            Route closed = route(router, trip(fromLat, fromLon, toLat, toLon, new RouteOptions(), closure));
            boolean clear = true;
            for (Nogo nogo : closure) clear &= !passesNear(closed, nogo.getLatitude(), nogo.getLongitude(), nogo.getRadiusM());
            System.out.println("jni-host-test: Dallas closure: plain route " + plain.getDistanceM() + " m ending " + Math.round(plainEnd)
                + " m off; with " + closure.size() + " closure points " + closed.getDistanceM() + " m ending "
                + Math.round(endDistance(closed, toLat, toLon)) + " m off, closures opened " + closed.getClosuresOpened());
            check(clear, "the route keeps out of the closure");
            check(!closed.getClosuresOpened(), "and keeps it closed: the destination's distance from the road is not the closure's doing");
            check(Math.abs(endDistance(closed, toLat, toLon) - plainEnd) < OsmAndRouter.END_SNAP_M, "ending where the plain route does");
        } finally {
            router.close();
        }
    }

    private static double endDistance(Route route, double lat, double lon) {
        int last = route.getPoints() - 1;
        double[] d = local(route.getLatitudes()[last], route.getLongitudes()[last], lat, lon);
        return Math.hypot(d[0], d[1]);
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
