// P6.3's routing engine on a desktop JVM: tile naming, the profile copy, the streamed and
// checked .rd5 import, and the routing thread's supersession rule. RoutingData, Router and
// RoutePlanner are pure Kotlin over the vendored BRouter tree, so these are the classes the
// app runs. It proves nothing about ART, Android storage, or the phone's speed.
//
// With a directory holding W75_N40.rd5 as the first argument it also computes real routes
// over it; without one those checks are skipped and the line says so, because the tile is
// 119 MB and is not in the repository.
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RoutePlanner;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.RouteResult;
import com.jw6ventures.headlink.Router;
import com.jw6ventures.headlink.RoutingData;
import com.jw6ventures.headlink.RoutingException;

public final class RoutingTest {
    private static int passed;

    /** The lab receiver's fixed position (Lexington/Waltham, MA) and Boston Common. */
    private static final double LAB_LAT = 42.4242;
    private static final double LAB_LON = -71.2121;
    private static final double COMMON_LAT = 42.3551;
    private static final double COMMON_LON = -71.0656;

    /**
     * A destination snapped into a large cut-off network (a fairground's lots behind a
     * gate) is found only with BRouter's island check widened past its 500 links; the
     * vendored engine is unmodified, so the widening is reflection on two private fields,
     * pinned here so a re-sync that renames either fails this test rather than routing.
     */
    private static void theIslandCheckIsWidened(File work) throws Exception {
        btools.router.RoutingContext context = new btools.router.RoutingContext();
        // The engine parses its profile on construction, so it needs a real one.
        context.localFunction = RoutingData.INSTANCE.installProfile(new File(work, "island-profiles")).getAbsolutePath();
        btools.router.RoutingEngine engine = new btools.router.RoutingEngine(null, null, work, new ArrayList<>(), context);
        java.lang.reflect.Field limit = btools.router.RoutingEngine.class.getDeclaredField(Router.ISLAND_LIMIT_FIELD);
        java.lang.reflect.Field pairs = btools.router.RoutingEngine.class.getDeclaredField(Router.ISLAND_PAIRS_FIELD);
        limit.setAccessible(true);
        pairs.setAccessible(true);
        check(limit.getInt(engine) == 500, "upstream's island check is 500 links: " + limit.getInt(engine));
        Object before = pairs.get(engine);
        check(Router.widenIslandCheck(engine), "the island check can be widened");
        check(limit.getInt(engine) == Router.ISLAND_CHECK_LINKS, "to " + Router.ISLAND_CHECK_LINKS + " links");
        check(pairs.get(engine) != before && pairs.get(engine) instanceof btools.mapaccess.OsmNodePairSet,
            "with a pair set sized to match");
    }

    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("routing-test").toFile();
        try {
            aTileKeepsItsNameAndNothingElseIsATile();
            aCoordinateOpensTheTileBRouterWould();
            aRouteRequestIsTwoPointsOrNothing();
            theProfileIsCopiedOnceAndThenLeftAlone(new File(work, "profiles"));
            anImportWithNoTileNameIsRefused(work);
            aDamagedTileNeverReplacesAnything(work);
            noDataIsAnErrorNotACrash(work);
            theIslandCheckIsWidened(work);
            aNewerRequestSupersedesOneInProgress(work);
            String segments = args.length > 0 ? args[0] : "";
            if (!segments.isEmpty() && new File(segments, "W75_N40.rd5").isFile()) {
                File tile = new File(segments, "W75_N40.rd5");
                aTileFromAnotherLookupTableIsRefused(work, tile);
                File installed = aRealTileStreamsInAndIsChecked(work, tile);
                aCrossTownRouteIsComputedOffTheCallingThread(work, installed.getParentFile());
                aSecondDestinationStopsTheFirstSearch(work, installed.getParentFile());
                if (new File(segments, "W100_N30.rd5").isFile() && new File(segments, "W105_N30.rd5").isFile()
                    && new File(segments, "W105_N35.rd5").isFile()) {
                    aLongTripCrossesTilesWithNothingToSwitch(work, new File(segments));
                }
                System.out.println("jni-host-test: ok " + passed + " routing checks");
            } else {
                System.out.println("jni-host-test: ok " + passed + " routing checks (real-route checks skipped: no W75_N40.rd5)");
            }
        } finally {
            delete(work);
        }
    }

    private static void aTileKeepsItsNameAndNothingElseIsATile() {
        check("W75_N40.rd5".equals(RoutingData.INSTANCE.tileName("W75_N40.rd5")), "the canonical name is kept");
        check("W75_N40.rd5".equals(RoutingData.INSTANCE.tileName("W75_N40 (1).rd5")),
            "a browser's second download is still that tile");
        check("W75_N40.rd5".equals(RoutingData.INSTANCE.tileName("w75_n40.rd5")), "case is normalised");
        check("E0_N0.rd5".equals(RoutingData.INSTANCE.tileName("E0_N0.rd5")), "the origin tile");
        check("W180_S90.rd5".equals(RoutingData.INSTANCE.tileName("W180_S90.rd5")), "the south-west corner");
        check(RoutingData.INSTANCE.tileName("W73_N40.rd5") == null, "not on the 5-degree grid");
        check(RoutingData.INSTANCE.tileName("E180_N0.rd5") == null, "180 east is 180 west");
        check(RoutingData.INSTANCE.tileName("W5_N90.rd5") == null, "no tile starts at the pole");
        check(RoutingData.INSTANCE.tileName("region.rd5") == null, "a name that says nothing is not a tile");
        check(RoutingData.INSTANCE.tileName("W75_N40.map") == null, "a map file is not a tile");
        check(RoutingData.INSTANCE.tileName("W75_N40.rd5.part") == null, "a staging file is not a tile");
        check(RoutingData.INSTANCE.tileName("XW75_N40.rd5") == null, "a tile name inside a word is not one");
        check(RoutingData.INSTANCE.tileName("W75_N405.rd5") == null, "nor is one with a digit too many");
    }

    private static void aCoordinateOpensTheTileBRouterWould() {
        check("W75_N40.rd5".equals(RoutingData.INSTANCE.tileFor(LAB_LAT, LAB_LON)), "the lab position");
        check("E0_N0.rd5".equals(RoutingData.INSTANCE.tileFor(0.5, 0.5)), "just north-east of the origin");
        check("W5_S5.rd5".equals(RoutingData.INSTANCE.tileFor(-0.1, -0.1)), "just south-west of the origin");
        check("E150_S35.rd5".equals(RoutingData.INSTANCE.tileFor(-33.87, 151.21)), "Sydney");
        check("E175_N85.rd5".equals(RoutingData.INSTANCE.tileFor(90.0, 180.0)), "the far corner clamps");
    }

    private static void aRouteRequestIsTwoPointsOrNothing() {
        RouteRequest spaced = RouteRequest.Companion.parse("42.4242,-71.2121 42.3551,-71.0656");
        check(spaced != null && spaced.getFromLat() == LAB_LAT && spaced.getToLon() == COMMON_LON, "lat,lon lat,lon");
        check(RouteRequest.Companion.parse(" 42.4242,-71.2121;\n42.3551,-71.0656 ") != null, "semicolon and newline");
        check(RouteRequest.Companion.parse("42.4242,-71.2121") == null, "one point is not a route");
        check(RouteRequest.Companion.parse("42.4,-71.2 42.3,-71.0 42.2,-71.1") == null, "three points are not P6.3's");
        check(RouteRequest.Companion.parse("42.4242,-71.2121 95.0,-71.0656") == null, "an impossible latitude");
        check(RouteRequest.Companion.parse("42.4242 -71.2121 42.3551 -71.0656") == null, "no commas is ambiguous");
        check(RouteRequest.Companion.parse("NaN,0 0,0") == null, "not a number");
        boolean threw = false;
        try {
            new RouteRequest(91.0, 0.0, 0.0, 0.0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "the constructor refuses what parse refuses");
    }

    private static void theProfileIsCopiedOnceAndThenLeftAlone(File profiles) throws Exception {
        File profile = RoutingData.INSTANCE.installProfile(profiles);
        File lookups = new File(profiles, "lookups.dat");
        check(profile.getName().equals("car-vario.brf") && profile.isFile(), "the profile is written");
        check(lookups.isFile(), "lookups.dat is written beside it, where BRouter looks");
        check(profile.length() == 11_891 && lookups.length() == 31_604, "byte counts match the vendored files");
        check(same(profile, resource("btools/profiles/car-vario.brf")), "the profile is byte-identical");
        check(same(lookups, resource("btools/profiles/lookups.dat")), "lookups.dat is byte-identical");
        // BRouter's profile cache keys on the modification time, so an identical copy must
        // not be rewritten; a changed one must be.
        long old = 1_000_000_000_000L;
        check(profile.setLastModified(old) && lookups.setLastModified(old), "back-date both files");
        RoutingData.INSTANCE.installProfile(profiles);
        check(profile.lastModified() == old && lookups.lastModified() == old, "identical copies are left alone");
        Files.write(profile.toPath(), "damaged".getBytes(StandardCharsets.UTF_8));
        RoutingData.INSTANCE.installProfile(profiles);
        check(same(profile, resource("btools/profiles/car-vario.brf")), "a differing copy is replaced");
        check(new File(profiles, "car-vario.brf.part").exists() == false, "no staging file is left");
    }

    private static void anImportWithNoTileNameIsRefused(File work) throws Exception {
        File segments = new File(work, "named");
        String message = importFails(segments, new File(work, "profiles"), "routing.rd5", new byte[4096]);
        check(message != null && message.contains("not a routing tile name"), "the refusal says why: " + message);
        check(!segments.exists() || segments.list().length == 0, "nothing is written for an unnamed file");
    }

    private static void aDamagedTileNeverReplacesAnything(File work) throws Exception {
        File segments = new File(work, "damaged");
        File profiles = new File(work, "profiles");
        check(segments.mkdirs(), "segment directory");
        File existing = new File(segments, "W75_N40.rd5");
        Files.write(existing.toPath(), "the tile already there".getBytes(StandardCharsets.UTF_8));

        String tooSmall = importFails(segments, profiles, "W75_N40.rd5", new byte[100]);
        check(tooSmall != null && tooSmall.contains("too small"), "a stub is refused: " + tooSmall);
        // Zeros make an index claiming lookup version 0, which BRouter's own read refuses.
        String garbage = importFails(segments, profiles, "W75_N40.rd5", new byte[64 * 1024]);
        check(garbage != null && garbage.contains("not a usable routing tile"), "garbage is refused: " + garbage);
        check(new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8).equals("the tile already there"),
            "a refused import leaves the installed tile untouched");
        check(!new File(segments, "W75_N40.rd5.part").exists(), "and leaves no staging file");
        check(RoutingData.INSTANCE.installed(segments).size() == 1, "installed lists only real tile names");
    }

    private static void noDataIsAnErrorNotACrash(File work) throws Exception {
        File profile = new File(work, "profiles/car-vario.brf");
        File empty = new File(work, "empty");
        check(empty.mkdirs(), "empty segment directory");
        String message = null;
        try {
            new Router(empty, profile, 64).compute(labToCommon(), 10_000, engine -> kotlin.Unit.INSTANCE);
        } catch (RoutingException e) {
            message = e.getMessage();
        }
        check(message != null && message.contains("W75_N40.rd5"), "a missing tile is named in the error: " + message);
        String missing = null;
        try {
            new Router(new File(work, "absent"), profile, 64).compute(labToCommon(), 10_000, engine -> kotlin.Unit.INSTANCE);
        } catch (RoutingException e) {
            missing = e.getMessage();
        }
        check("no routing data installed".equals(missing), "no directory at all: " + missing);
    }

    /**
     * The first request is held inside the router factory until the second has been made,
     * so the order is deterministic without any routing data.
     */
    private static void aNewerRequestSupersedesOneInProgress(File work) throws Exception {
        File profile = new File(work, "profiles/car-vario.brf");
        File empty = new File(work, "empty");
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> factoryThreads = Collections.synchronizedList(new ArrayList<>());
        LinkedBlockingQueue<RouteResult> results = new LinkedBlockingQueue<>();
        RoutePlanner planner = new RoutePlanner(() -> {
            factoryThreads.add(Thread.currentThread().getName());
            if (firstInside.getCount() > 0) {
                firstInside.countDown();
                await(release);
            }
            return new Router(empty, profile, 64);
        }, 10_000, () -> kotlin.Unit.INSTANCE);
        try {
            long first = planner.request(labToCommon(), result -> { results.add(result); return kotlin.Unit.INSTANCE; });
            check(firstInside.await(10, TimeUnit.SECONDS), "the first request reached the routing thread");
            long second = planner.request(labToCommon(), result -> { results.add(result); return kotlin.Unit.INSTANCE; });
            long third = planner.request(labToCommon(), result -> { results.add(result); return kotlin.Unit.INSTANCE; });
            release.countDown();
            RouteResult a = results.poll(30, TimeUnit.SECONDS);
            RouteResult b = results.poll(30, TimeUnit.SECONDS);
            RouteResult c = results.poll(30, TimeUnit.SECONDS);
            check(a != null && b != null && c != null, "every request is answered exactly once");
            check(results.poll(200, TimeUnit.MILLISECONDS) == null, "and no more than once");
            check(first < second && second < third, "sequences ascend");
            check(a.getSequence() == first && a.getSuperseded(), "the one in progress is marked superseded");
            check(b.getSequence() == second && b.getSuperseded() && b.getComputeMs() == 0,
                "a queued one that was overtaken never starts");
            check(c.getSequence() == third && !c.getSuperseded(), "only the newest counts");
            for (RouteResult r : new RouteResult[] {a, b, c}) {
                check(RoutePlanner.THREAD.equals(r.getThread()), "answered on " + r.getThread());
            }
            check(factoryThreads.size() == 2 && factoryThreads.stream().allMatch(RoutePlanner.THREAD::equals),
                "the engine is only ever built on the routing thread");
        } finally {
            planner.close();
        }
    }

    private static void aTileFromAnotherLookupTableIsRefused(File work, File tile) throws Exception {
        File other = new File(work, "other-lookups");
        check(other.mkdirs(), "second profile directory");
        String lookups = new String(resource("btools/profiles/lookups.dat"), StandardCharsets.UTF_8);
        check(lookups.contains("---lookupversion:11"), "the vendored table is version 11");
        Files.write(new File(other, "lookups.dat").toPath(),
            lookups.replace("---lookupversion:11", "---lookupversion:10").getBytes(StandardCharsets.UTF_8));
        String message = null;
        try {
            RoutingData.INSTANCE.validate(tile, new File(other, "lookups.dat"));
        } catch (IOException e) {
            message = e.getMessage();
        }
        check(message != null && message.contains("lookup version mismatch"), "an old table is caught at import: " + message);
    }

    private static File aRealTileStreamsInAndIsChecked(File work, File tile) throws Exception {
        File segments = new File(work, "segments");
        long start = System.nanoTime();
        File installed;
        try (InputStream in = new FileInputStream(tile)) {
            installed = RoutingData.INSTANCE.install(segments, new File(work, "profiles"), "W75_N40 (1).rd5", in);
        }
        long ms = (System.nanoTime() - start) / 1_000_000L;
        check(installed.getName().equals("W75_N40.rd5"), "installed under the tile name");
        check(installed.length() == tile.length(), "every byte arrived");
        check(!new File(segments, "W75_N40.rd5.part").exists(), "the staging file is gone");
        System.out.println("routing-test: import bytes=" + installed.length() + " ms=" + ms);
        // A truncated copy of a real tile: the index is intact, the trailer is not.
        byte[] head = new byte[1 << 20];
        try (InputStream in = new FileInputStream(tile)) {
            check(in.readNBytes(head, 0, head.length) == head.length, "read a megabyte of the tile");
        }
        String truncated = importFails(segments, new File(work, "profiles"), "W75_N40.rd5", head);
        check(truncated != null && truncated.contains("too short"), "a cut-short import is refused: " + truncated);
        check(installed.length() == tile.length(), "and the good copy survives it");
        return installed;
    }

    private static void aCrossTownRouteIsComputedOffTheCallingThread(File work, File segments) throws Exception {
        File profile = new File(work, "profiles/car-vario.brf");
        LinkedBlockingQueue<RouteResult> results = new LinkedBlockingQueue<>();
        RoutePlanner planner = new RoutePlanner(() -> new Router(segments, profile, 64), 60_000, () -> kotlin.Unit.INSTANCE);
        try {
            planner.request(labToCommon(), result -> { results.add(result); return kotlin.Unit.INSTANCE; });
            RouteResult result = results.poll(120, TimeUnit.SECONDS);
            check(result != null, "a result within two minutes");
            check(result.getError() == null, "no error: " + result.getError());
            Route route = result.getRoute();
            check(route != null, "a route");
            check(RoutePlanner.THREAD.equals(result.getThread()) && !result.getThread().equals(Thread.currentThread().getName()),
                "computed on the routing thread");
            double crow = metres(LAB_LAT, LAB_LON, COMMON_LAT, COMMON_LON);
            check(route.getDistanceM() > crow && route.getDistanceM() < 2.5 * crow,
                "road distance " + route.getDistanceM() + " m against " + (int) crow + " m as the crow flies");
            double speed = route.getDistanceM() / (double) Math.max(1, route.getSeconds());
            check(speed > 5 && speed < 30, "an average of " + (int) (speed * 3.6) + " km/h is a car in a town");
            int n = route.getPoints();
            check(n >= 50, n + " points of geometry");
            check(metres(route.getLatitudes()[0], route.getLongitudes()[0], LAB_LAT, LAB_LON) < 500,
                "it starts near the start");
            check(metres(route.getLatitudes()[n - 1], route.getLongitudes()[n - 1], COMMON_LAT, COMMON_LON) < 500,
                "it ends near the destination");
            int[] maneuvers = route.getManeuverIndices();
            check(maneuvers.length >= 3, maneuvers.length + " maneuvers derived");
            check(maneuvers.length == route.getExitNumbers().length, "one exit slot per maneuver");
            for (int i = 0; i < maneuvers.length; i++) {
                check(maneuvers[i] >= 0 && maneuvers[i] < n && (i == 0 || maneuvers[i] > maneuvers[i - 1]),
                    "maneuver " + i + " is on the geometry, in order");
            }
            System.out.println("routing-test: cross-town compute_ms=" + result.getComputeMs() + " distance_m="
                + route.getDistanceM() + " seconds=" + route.getSeconds() + " points=" + n + " maneuvers=" + maneuvers.length);
            // Again, warm: the profile is cached and the tile's index is already read.
            planner.request(labToCommon(), r -> { results.add(r); return kotlin.Unit.INSTANCE; });
            RouteResult warm = results.poll(120, TimeUnit.SECONDS);
            check(warm != null && warm.getRoute() != null, "a second identical route");
            check(warm.getRoute().getDistanceM() == route.getDistanceM() && warm.getRoute().getPoints() == n,
                "the same answer twice");
            System.out.println("routing-test: warm compute_ms=" + warm.getComputeMs());
        } finally {
            planner.close();
        }
    }

    /** Long enough to still be searching when the second request lands: Boston to New Haven. */
    private static void aSecondDestinationStopsTheFirstSearch(File work, File segments) throws Exception {
        File profile = new File(work, "profiles/car-vario.brf");
        LinkedBlockingQueue<RouteResult> results = new LinkedBlockingQueue<>();
        CountDownLatch started = new CountDownLatch(1);
        RoutePlanner planner = new RoutePlanner(() -> {
            started.countDown();
            return new Router(segments, profile, 64);
        }, 60_000, () -> kotlin.Unit.INSTANCE);
        try {
            planner.request(new RouteRequest(COMMON_LAT, COMMON_LON, 41.3083, -72.9279),
                r -> { results.add(r); return kotlin.Unit.INSTANCE; });
            check(started.await(10, TimeUnit.SECONDS), "the long search began");
            Thread.sleep(300);
            planner.request(labToCommon(), r -> { results.add(r); return kotlin.Unit.INSTANCE; });
            RouteResult first = results.poll(120, TimeUnit.SECONDS);
            RouteResult second = results.poll(120, TimeUnit.SECONDS);
            check(first != null && first.getSuperseded(), "the first search is marked superseded");
            check(second != null && second.getRoute() != null && !second.getSuperseded(), "the second is answered");
            System.out.println("routing-test: superseded after ms=" + first.getComputeMs() + " error=" + first.getError());
        } finally {
            planner.close();
        }
    }

    /**
     * Austin to Denver: four 5-degree tiles read from one directory, which is what makes a
     * trip across regions seamless. Run against the operator's directory in place.
     */
    private static void aLongTripCrossesTilesWithNothingToSwitch(File work, File segments) throws Exception {
        File profile = new File(work, "profiles/car-vario.brf");
        double austinLat = 30.2672, austinLon = -97.7431, denverLat = 39.7392, denverLon = -104.9903;
        long start = System.nanoTime();
        Route route = new Router(segments, profile, 64).compute(
            new RouteRequest(austinLat, austinLon, denverLat, denverLon), RoutePlanner.MAX_RUNNING_MS, e -> kotlin.Unit.INSTANCE);
        long ms = (System.nanoTime() - start) / 1_000_000L;
        double crow = metres(austinLat, austinLon, denverLat, denverLon);
        check(route.getDistanceM() > crow && route.getDistanceM() < 1.8 * crow,
            "road distance " + route.getDistanceM() + " m against " + (int) crow + " m as the crow flies");
        int n = route.getPoints();
        check(metres(route.getLatitudes()[n - 1], route.getLongitudes()[n - 1], denverLat, denverLon) < 1000,
            "it ends in Denver");
        System.out.println("routing-test: austin-denver compute_ms=" + ms + " distance_m=" + route.getDistanceM()
            + " seconds=" + route.getSeconds() + " points=" + n + " maneuvers=" + route.getManeuverIndices().length);
    }

    private static RouteRequest labToCommon() {
        return new RouteRequest(LAB_LAT, LAB_LON, COMMON_LAT, COMMON_LON);
    }

    private static String importFails(File segments, File profiles, String name, byte[] bytes) {
        try {
            RoutingData.INSTANCE.install(segments, profiles, name, new ByteArrayInputStream(bytes));
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = RoutingTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException(name + " is not on the classpath");
            return in.readAllBytes();
        }
    }

    private static boolean same(File file, byte[] bytes) throws IOException {
        return java.util.Arrays.equals(Files.readAllBytes(file.toPath()), bytes);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("routing: " + what);
        passed++;
    }
}
