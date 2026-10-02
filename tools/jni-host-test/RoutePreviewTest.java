// The route preview on a desktop JVM: the files that carry a route between the app's two
// processes (request and choice, round trip, refusals), how an option is described (its
// main road), when an alternative is the same route, the box the map fits, and the
// request for an alternative. RoutePreview is pure Kotlin; PreviewActivity's map, its
// process and the choice coming back to Routing need a device.
//
// With a directory holding W100_N30.rd5 as the first argument it also asks BRouter for
// real alternatives around Austin and checks they are different roads.
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import kotlin.Pair;
import java.util.ArrayList;
import org.openautoandroid.source.AlternativeFinder;
import org.openautoandroid.source.Destination;
import org.openautoandroid.source.DestinationSource;
import org.openautoandroid.source.LiveAlternative;
import org.openautoandroid.source.NavigationFile;
import org.openautoandroid.source.PreviewRequest;
import org.openautoandroid.source.Route;
import org.openautoandroid.source.RouteOptions;
import org.openautoandroid.source.RoutePlanner;
import org.openautoandroid.source.RoutePreview;
import org.openautoandroid.source.RouteRequest;
import org.openautoandroid.source.Router;
import org.openautoandroid.source.RoutingData;

public final class RoutePreviewTest {
    private static int passed;
    private static final RoutePreview P = RoutePreview.INSTANCE;

    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("route-preview-test").toFile();
        aRequestReadsBackWhole(work);
        aChoiceReadsBackWhole(work);
        aStrangeFileIsRefused(work);
        anOptionIsNamedForItsLongestRoad();
        theSameRoadsAreTheSameRoute();
        theBoxHoldsEveryRoute();
        anAlternativeIsAskedForByNumber();
        aTurnBackIsSeenAndAStraightIsNot();
        aViaPointCrossesTheFile(work);
        theNavigationIsSavedAndReadBack(work);
        theRestOfARouteIsARouteOfItsOwn();
        anAlternativeSaysHowMuchLonger();
        String real = "real alternatives skipped (no W100_N30.rd5)";
        if (args.length > 0 && new File(args[0], "W100_N30.rd5").isFile()) {
            realAlternativesAreOtherRoads(work, new File(args[0]));
            alternativesLeaveAndRejoinTheRoute(work, new File(args[0]));
            real = "real alternatives over W100_N30";
        }
        System.out.println("jni-host-test: ok " + passed + " route preview checks; " + real);
    }

    /** Three points north along a meridian, a turn at the middle, two named stretches. */
    private static Route sample() {
        return new Route(new double[] {30.0, 30.01, 30.02}, new double[] {-97.0, -97.0, -97.0}, 2224, 120,
                new int[] {1}, new int[] {2}, new int[] {5}, new float[] {0f, 60.5f, 120f},
                new int[] {0, 1}, new String[] {"Main Street", null}, 105);
    }

    private static void sameRoute(Route a, Route b, String what) {
        check(Arrays.equals(a.getLatitudes(), b.getLatitudes()) && Arrays.equals(a.getLongitudes(), b.getLongitudes())
                && a.getDistanceM() == b.getDistanceM() && a.getSeconds() == b.getSeconds()
                && Arrays.equals(a.getManeuverIndices(), b.getManeuverIndices())
                && Arrays.equals(a.getExitNumbers(), b.getExitNumbers()) && Arrays.equals(a.getCommands(), b.getCommands())
                && Arrays.equals(a.getPointSeconds(), b.getPointSeconds()) && Arrays.equals(a.getRoadStarts(), b.getRoadStarts())
                && Arrays.equals(a.getRoadNames(), b.getRoadNames()) && a.getTopSpeedKmh() == b.getTopSpeedKmh(), what);
    }

    private static void aRequestReadsBackWhole(File work) throws IOException {
        RouteOptions options = new RouteOptions(true, false, true, false, true, 105, 10, false, false);
        PreviewRequest request = new PreviewRequest(42L, 30.0, -97.0, 31.0, -97.5, 123.0, options, sample());
        File file = P.requestFile(work);
        P.writeRequest(file, request);
        check(!new File(file.getPath() + ".part").exists(), "no staging file is left behind");
        PreviewRequest back = P.readRequest(file);
        check(back.getToken() == 42L && back.getFromLat() == 30.0 && back.getFromLon() == -97.0 && back.getToLat() == 31.0
                && back.getToLon() == -97.5, "the token and both ends read back");
        check(back.getStartBearing() != null && back.getStartBearing() == 123.0, "and the bearing");
        check(back.getOptions().equals(options), "and every routing option: " + back.getOptions());
        sameRoute(back.getRoute(), request.getRoute(), "and the route, names and timings included");
        PreviewRequest noBearing = new PreviewRequest(1L, 30.0, -97.0, 31.0, -97.5, null, new RouteOptions(), sample());
        P.writeRequest(file, noBearing);
        check(P.readRequest(file).getStartBearing() == null, "no bearing reads back as none");
    }

    private static void aChoiceReadsBackWhole(File work) throws IOException {
        File file = P.choiceFile(work);
        P.writeChoice(file, 7L, sample());
        Pair<Long, Route> back = P.readChoice(file);
        check(back.getFirst() == 7L, "the choice names its token");
        sameRoute(back.getSecond(), sample(), "and carries the route whole");
        Route untimed = new Route(new double[] {30.0, 30.01}, new double[] {-97.0, -97.0}, 1112, 60,
                new int[0], new int[0], new int[0]);
        P.writeChoice(file, 8L, untimed);
        check(P.readChoice(file).getSecond().getPointSeconds().length == 0, "a route with no point times reads back with none");
    }

    private static void aStrangeFileIsRefused(File work) throws IOException {
        File file = new File(work, "strange.bin");
        Files.write(file.toPath(), new byte[] {0, 0, 0, 9, 1, 2, 3});
        check(refused(file), "another version is refused, not misread");
        P.writeChoice(file, 1L, sample());
        byte[] bytes = Files.readAllBytes(file.toPath());
        Files.write(file.toPath(), Arrays.copyOf(bytes, bytes.length / 2));
        check(refused(file), "a file cut short is refused");
        // A count that says two billion points.
        byte[] huge = Arrays.copyOf(bytes, bytes.length);
        huge[12] = 0x7F;
        Files.write(file.toPath(), huge);
        check(refused(file), "an impossible count is refused before anything is allocated");
    }

    private static boolean refused(File file) {
        try {
            P.readChoice(file);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static void anOptionIsNamedForItsLongestRoad() {
        check("Main Street".equals(P.via(sample())), "the one named stretch names it");
        // Short named start, long named middle: the long one wins.
        Route route = new Route(new double[] {30.0, 30.001, 30.05, 30.051}, new double[] {-97, -97, -97, -97}, 5700, 300,
                new int[] {1, 2}, new int[] {0, 0}, new int[] {2, 5}, new float[0],
                new int[] {0, 1, 2}, new String[] {"Driveway Lane", "I 35", "Driveway Lane"}, 121);
        check("I 35".equals(P.via(route)), "the road driven furthest names the route: " + P.via(route));
        Route mainRoute = new Route(new double[] {30.0, 30.05}, new double[] {-97, -97}, 5600, 300,
                new int[0], new int[0], new int[0], new float[0], new int[] {0}, new String[] {"I 35"}, 121);
        check("Driveway Lane".equals(P.via(route, List.of(mainRoute))), "an alternative is named for the road the others do not use");
        check("I 35".equals(P.via(mainRoute, List.of(route))), "and falls back to its longest road when all are shared");
        Route unnamed = new Route(new double[] {30.0, 30.01}, new double[] {-97.0, -97.0}, 1112, 60, new int[0], new int[0], new int[0]);
        check(P.via(unnamed) == null, "a route the map names nothing of has no via");
    }

    private static void theSameRoadsAreTheSameRoute() {
        Route a = sample();
        Route shifted = new Route(new double[] {30.0, 30.01, 30.02}, new double[] {-97.001, -97.001, -97.001}, 2224, 120,
                new int[0], new int[0], new int[0]);
        check(P.shared(a, a) == 1.0 && P.duplicate(a, List.of(a)), "a route is a duplicate of itself");
        check(P.shared(shifted, a) == 0.0 && !P.duplicate(shifted, List.of(a)), "a parallel road is not");
        check(!P.duplicate(a, List.of()), "nothing offered, nothing duplicated");
    }

    private static void theBoxHoldsEveryRoute() {
        Route east = new Route(new double[] {29.5, 30.5}, new double[] {-96.0, -95.5}, 1, 1, new int[0], new int[0], new int[0]);
        double[] box = P.bounds(List.of(sample(), east));
        check(box[0] == 29.5 && box[1] == -97.0 && box[2] == 30.5 && box[3] == -95.5, "south, west, north, east: " + Arrays.toString(box));
    }

    private static void anAlternativeIsAskedForByNumber() {
        PreviewRequest request = new PreviewRequest(1L, 30.0, -97.0, 31.0, -97.5, 90.0, new RouteOptions(), sample());
        RouteRequest second = request.alternative(2);
        check(second.getAlternative() == 2 && second.getToLat() == 31.0 && second.getStartBearing() == 90.0
                && second.getOptions().equals(new RouteOptions()), "the same trip, bearing and options, alternative 2");
        check(new RouteRequest(30.0, -97.0, 31.0, -97.5).getAlternative() == 0, "a plain request is the best route");
        try {
            new RouteRequest(30.0, -97.0, 31.0, -97.5, null, new RouteOptions(), 4);
            check(false, "BRouter has no fifth route");
        } catch (IllegalArgumentException expected) {
            check(true, "an alternative BRouter cannot give is refused");
        }
    }

    private static void aTurnBackIsSeenAndAStraightIsNot() {
        // North 1 km along a meridian, then back south: the via point at the far end.
        double[] lats = new double[21];
        double[] lons = new double[21];
        for (int i = 0; i <= 10; i++) lats[i] = 30.0 + i * 0.0009;
        for (int i = 11; i <= 20; i++) lats[i] = 30.009 - (i - 10) * 0.0009;
        Arrays.fill(lons, -97.0);
        Route outAndBack = new Route(lats, lons, 2000, 100, new int[0], new int[0], new int[0]);
        check(AlternativeFinder.Companion.uTurnAt(outAndBack, 30.009, -97.0), "driving in and back out is a U-turn");
        double[] north = new double[21];
        for (int i = 0; i <= 20; i++) north[i] = 30.0 + i * 0.0009;
        Route straight = new Route(north, lons.clone(), 2000, 100, new int[0], new int[0], new int[0]);
        check(!AlternativeFinder.Companion.uTurnAt(straight, 30.009, -97.0), "passing straight through is not");
        // A right-angle turn at the via point is an ordinary turn.
        double[] cornerLats = new double[21];
        double[] cornerLons = new double[21];
        for (int i = 0; i <= 10; i++) { cornerLats[i] = 30.0 + i * 0.0009; cornerLons[i] = -97.0; }
        for (int i = 11; i <= 20; i++) { cornerLats[i] = 30.009; cornerLons[i] = -97.0 + (i - 10) * 0.00104; }
        Route corner = new Route(cornerLats, cornerLons, 2000, 100, new int[0], new int[0], new int[0]);
        check(!AlternativeFinder.Companion.uTurnAt(corner, 30.009, -97.0), "a right-angle turn is not");
        check(Math.abs(AlternativeFinder.Companion.offset(straight, 30.009, -96.99) - 961) < 15,
                "offset is the distance to the nearest point: " + AlternativeFinder.Companion.offset(straight, 30.009, -96.99));
        check(AlternativeFinder.Companion.minimumDeviation(sample()) == 1000.0, "a short trip still needs a kilometre off");
        Route far = new Route(new double[] {30, 31}, new double[] {-97, -97}, 300_000, 1, new int[0], new int[0], new int[0]);
        check(AlternativeFinder.Companion.minimumDeviation(far) == 6000.0, "a long one 2% of it");
        check(new RouteRequest(30.0, -97.0, 31.0, -97.5, null, new RouteOptions(), 0, 30.5, -97.2).getViaLat() == 30.5,
                "a request carries its via point");
        try {
            new RouteRequest(30.0, -97.0, 31.0, -97.5, null, new RouteOptions(), 0, 30.5, null);
            check(false, "half a via point is refused");
        } catch (IllegalArgumentException expected) {
            check(true, "half a via point is refused");
        }
    }

    private static void aViaPointCrossesTheFile(File work) throws IOException {
        Route viaRoute = new Route(new double[] {30.0, 30.01}, new double[] {-97.0, -97.0}, 1112, 60, new int[0], new int[0],
                new int[0], new float[] {0f, 60f}, new int[0], new String[0], 121, new double[] {30.005, -97.001});
        File file = P.choiceFile(work);
        P.writeChoice(file, 9L, viaRoute);
        Route back = P.readChoice(file).getSecond();
        check(back.getVia() != null && back.getVia()[0] == 30.005 && back.getVia()[1] == -97.001, "the via point crosses the file");
        check(viaRoute.withTimeScale(1.1).getVia() == viaRoute.getVia() && viaRoute.withRoads(new int[0], new String[0]).getVia() != null,
                "and survives scaling and naming");
        P.writeChoice(file, 9L, sample());
        check(P.readChoice(file).getSecond().getVia() == null, "a route with none reads back with none");

        // P9 (review): whether traffic shaped a route, and whether it goes through a closure,
        // cross the file, so "Traffic © HERE" is still owed after a restart.
        Route shaped = new Route(new double[] {30.0, 30.01}, new double[] {-97.0, -97.0}, 1112, 60, new int[0], new int[0],
                new int[0], new float[] {0f, 60f}, new int[0], new String[0], 121, null, 12, true);
        P.writeChoice(file, 9L, shaped);
        Route shapedBack = P.readChoice(file).getSecond();
        check(shapedBack.getTrafficShaped() && shapedBack.getClosuresOpened(), "a traffic-shaped route through a closure reads back as one");
        check(!P.readChoice(writeAndRead(file, sample())).getSecond().getTrafficShaped(), "one without reads back without");
        // A version 2 file, written before P9 (the same bytes less the two flags), is still read.
        P.writeChoice(file, 9L, shaped);
        byte[] bytes = Files.readAllBytes(file.toPath());
        byte[] old = Arrays.copyOf(bytes, bytes.length - 2);
        old[3] = 2;
        Files.write(file.toPath(), old);
        Route oldBack = P.readChoice(file).getSecond();
        check(!oldBack.getTrafficShaped() && !oldBack.getClosuresOpened() && oldBack.getDistanceM() == 1112,
                "a version 2 file reads back, without the flags it never had");
    }

    private static File writeAndRead(File file, Route route) throws IOException {
        P.writeChoice(file, 9L, route);
        return file;
    }

    /**
     * NavigationFile: the destination, its label and source, the via point and the route
     * read back whole; with no route, just the destination; a strange file is refused.
     */
    private static void theNavigationIsSavedAndReadBack(File work) throws IOException {
        File file = NavigationFile.INSTANCE.file(work);
        NavigationFile.Saved saved = new NavigationFile.Saved(1234L, new Destination(30.5, -97.6, "Home"),
                DestinationSource.FAVORITE, new double[] {30.3, -97.65}, sample());
        NavigationFile.INSTANCE.write(file, saved);
        NavigationFile.Saved back = NavigationFile.INSTANCE.read(file);
        check(back.getSavedAtMillis() == 1234L && back.getDestination().getLatitude() == 30.5
                && back.getDestination().getLongitude() == -97.6 && "Home".equals(back.getDestination().getLabel()),
                "the destination and its label read back");
        check(back.getSource() == DestinationSource.FAVORITE, "and where it came from");
        check(back.getVia() != null && back.getVia()[0] == 30.3, "and the via point");
        check(back.getRoute() != null, "and the route");
        sameRoute(back.getRoute(), sample(), "whole");
        NavigationFile.INSTANCE.write(file, new NavigationFile.Saved(5L, new Destination(30.5, -97.6, null),
                DestinationSource.TYPED, null, null));
        NavigationFile.Saved bare = NavigationFile.INSTANCE.read(file);
        check(bare.getRoute() == null && bare.getVia() == null && bare.getDestination().getLabel() == null,
                "a destination waiting for a route saves on its own");
        Files.write(file.toPath(), new byte[] {0, 0, 0, 1, 0, 0});
        try {
            NavigationFile.INSTANCE.read(file);
            check(false, "an old or broken file is refused");
        } catch (IOException expected) {
            check(true, "an old or broken file is refused");
        }
    }

    private static void theRestOfARouteIsARouteOfItsOwn() {
        // Four points north, 60 s apart, a maneuver at 1 and at 3, roads from 0 and 2.
        Route route = new Route(new double[] {30.0, 30.01, 30.02, 30.03}, new double[] {-97, -97, -97, -97}, 3336, 180,
                new int[] {1, 3}, new int[] {0, 2}, new int[] {2, 5}, new float[] {0f, 60f, 120f, 180f},
                new int[] {0, 2}, new String[] {"A Street", "B Road"}, 121, new double[] {30.02, -97.0});
        Route rest = route.remainder(2);
        check(rest.getPoints() == 2 && rest.getLatitudes()[0] == 30.02, "it starts at the point given");
        check(rest.getSeconds() == 60 && rest.getPointSeconds()[0] == 0f && rest.getPointSeconds()[1] == 60f,
                "with times counted from there");
        check(Math.abs(rest.getDistanceM() - 1112) <= 2, "and its own length: " + rest.getDistanceM());
        check(rest.getManeuverIndices().length == 1 && rest.getManeuverIndices()[0] == 1 && rest.getExitNumbers()[0] == 2,
                "keeping only the maneuvers ahead, renumbered");
        check(rest.getRoadStarts().length == 1 && rest.getRoadStarts()[0] == 0 && "B Road".equals(rest.getRoadNames()[0]),
                "and the road it is on");
        check(rest.getVia() != null, "and its via point");
        check(route.remainder(0) == route, "from the start it is the route itself");
        check(route.remainder(10).getPoints() == 2, "past the end it is the last segment");
    }

    private static void anAlternativeSaysHowMuchLonger() {
        Route ahead = new Route(new double[] {30.0, 30.01, 30.02, 30.03}, new double[] {-97, -97, -97, -97}, 3336, 600,
                new int[0], new int[0], new int[0]);
        // Shares the first point, leaves to the east, comes back at the last.
        Route other = new Route(new double[] {30.0, 30.01, 30.02, 30.03}, new double[] {-97, -96.99, -96.99, -97}, 3600, 960,
                new int[0], new int[0], new int[0]);
        LiveAlternative a = LiveAlternative.Companion.of(1L, ahead, other);
        check(a != null && a.getDeltaSeconds() == 360 && "+6 min".equals(a.getLabel()), "six minutes longer says +6 min");
        check(a.getSplitLat() == 30.0 && a.getSplitLon() == -97.0, "it splits at the last point it shares");
        check(a.getLabelLon() == -96.99 && a.getLabelLat() == 30.02,
                "and its bubble sits a little way into the part it does not share: " + a.getLabelLat());
        check(LiveAlternative.Companion.of(2L, ahead, ahead) == null, "the same route is not an alternative");
        Route slow = new Route(other.getLatitudes(), other.getLongitudes(), 3600, 600 + 601, new int[0], new int[0], new int[0]);
        check(LiveAlternative.Companion.of(3L, ahead, slow) == null, "over ten minutes longer on a short trip is not shown");
        Route faster = new Route(other.getLatitudes(), other.getLongitudes(), 3600, 480, new int[0], new int[0], new int[0]);
        check("\u22122 min".equals(LiveAlternative.Companion.of(4L, ahead, faster).getLabel()), "a faster one says so");
        Route even = new Route(other.getLatitudes(), other.getLongitudes(), 3600, 620, new int[0], new int[0], new int[0]);
        check("same".equals(LiveAlternative.Companion.of(5L, ahead, even).getLabel()), "within half a minute is the same");
        check(LiveAlternative.Companion.worthShowing(3000, 14400), "on a four-hour trip up to an hour is shown");
        // BRouter's snapped start is between two nodes, so never a point of the other route:
        // it must not count as where the alternative leaves.
        Route snapped = new Route(new double[] {29.99995, 30.0, 30.01, 30.02, 30.03}, new double[] {-97, -97, -97, -96.99, -97},
                3336, 700, new int[0], new int[0], new int[0]);
        LiveAlternative s = LiveAlternative.Companion.of(6L, ahead, snapped);
        check(s != null && s.getSplitLat() == 30.01, "a snapped start is not the split; the last shared node is: " + (s == null ? null : s.getSplitLat()));
        check(s.getLabelLon() == -96.99, "and the bubble is on the road it does not share");
    }

    /**
     * Austin to Temple: BRouter's own alternatives left I 35 entirely (91 and 101 minutes
     * against 65). The finder's must share part of the best route, be faster than
     * BRouter's own first alternative, and never repeat an option.
     */
    private static void alternativesLeaveAndRejoinTheRoute(File work, File segments) throws Exception {
        File profile = RoutingData.INSTANCE.installProfile(new File(work, "profiles"));
        Router router = new Router(segments, profile, 64);
        PreviewRequest shape = new PreviewRequest(1L, 30.2672, -97.7431, 31.0982, -97.3428, null, new RouteOptions(), sample());
        Route best = router.compute(shape.alternative(0), RoutePlanner.MAX_RUNNING_MS, e -> kotlin.Unit.INSTANCE);
        Route strict = router.compute(shape.alternative(1), RoutePlanner.MAX_RUNNING_MS, e -> kotlin.Unit.INSTANCE);
        PreviewRequest trip = new PreviewRequest(1L, 30.2672, -97.7431, 31.0982, -97.3428, null, new RouteOptions(), best);
        List<Route> found = new ArrayList<>();
        long start = System.nanoTime();
        String failure = new AlternativeFinder(router, trip, RoutePlanner.MAX_RUNNING_MS, e -> kotlin.Unit.INSTANCE, () -> false)
                .find(r -> { found.add(r); return kotlin.Unit.INSTANCE; });
        long ms = (System.nanoTime() - start) / 1_000_000L;
        check(failure == null, "no search failed: " + failure);
        check(!found.isEmpty() && found.size() <= RoutePreview.ALTERNATIVES, "one or two alternatives: " + found.size());
        List<Route> offered = new ArrayList<>(List.of(best));
        StringBuilder line = new StringBuilder();
        for (Route r : found) {
            check(!P.duplicate(r, offered), "each is a route not already offered");
            offered.add(r);
            line.append(" ").append(r.getSeconds() / 60).append("min/").append(Math.round(100 * P.shared(r, best))).append("%");
        }
        Route first = found.get(0);
        check(P.shared(first, best) > 0.2, "the first shares part of the best route: " + P.shared(first, best));
        check(first.getSeconds() < strict.getSeconds(), "and is faster than BRouter's own alternative: "
                + first.getSeconds() + " s against " + strict.getSeconds() + " s");
        System.out.println("route-preview-test: temple best=" + best.getSeconds() / 60 + "min strict=" + strict.getSeconds() / 60
                + "min found=" + line + " find_ms=" + ms);
    }

    /** Austin to Round Rock: BRouter's first and second alternatives against its best. */
    private static void realAlternativesAreOtherRoads(File work, File segments) throws Exception {
        File profile = RoutingData.INSTANCE.installProfile(new File(work, "profiles"));
        Router router = new Router(segments, profile, 64);
        PreviewRequest trip = new PreviewRequest(1L, 30.2672, -97.7431, 30.5083, -97.6789, null, new RouteOptions(), sample());
        Route best = router.compute(trip.alternative(0), RoutePlanner.MAX_RUNNING_MS, e -> kotlin.Unit.INSTANCE);
        Route first = router.compute(trip.alternative(1), RoutePlanner.MAX_RUNNING_MS, e -> kotlin.Unit.INSTANCE);
        Route second = router.compute(trip.alternative(2), RoutePlanner.MAX_RUNNING_MS, e -> kotlin.Unit.INSTANCE);
        check(!P.duplicate(first, List.of(best)) && !P.duplicate(second, List.of(best, first)),
                "each alternative is mostly other roads: " + P.shared(first, best) + ", " + P.shared(second, best));
        check(first.getSeconds() >= best.getSeconds() && second.getSeconds() >= best.getSeconds(), "and none is faster than the best");
        check(first.getTopSpeedKmh() == 121 && first.getPoints() > 1, "timed at the chosen top speed");
        File file = P.choiceFile(work);
        P.writeChoice(file, 3L, second);
        sameRoute(P.readChoice(file).getSecond(), second, "a real alternative crosses the file whole");
        System.out.println("route-preview-test: round-rock best_s=" + best.getSeconds() + " alt1_s=" + first.getSeconds()
                + " alt2_s=" + second.getSeconds() + " file_bytes=" + file.length());
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
