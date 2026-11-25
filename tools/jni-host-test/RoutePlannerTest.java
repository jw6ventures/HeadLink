// The routing thread on a desktop JVM (docs/navigation.md, "Routing on OsmAnd maps"): how a
// typed request is read, that a newer request supersedes one in progress and stops its
// search, that running out of memory fails one route and not the thread, and that the engine
// is only ever built and run on the routing thread. RouteRequest and RoutePlanner are the
// classes the app runs; the engine is a stand-in, so none of this needs a map.
//
// With a directory of OsmAnd maps as the argument (OBF_DIR), under the phone's 256 MB heap
// (jni-host-test.sh passes -Xmx256m), it also plans real routes through the same planner over
// OsmAndRouter: across town from the lab receiver's position to Boston Common on
// Us_massachusetts_northamerica.obf, the same answer twice, a long search across the state
// stopped by a second destination; and with the Austin and Waco maps, Austin to Waco read
// from two files with nothing to switch. It proves nothing about ART or the
// phone's speed.
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.jw6ventures.headlink.OsmAndRouter;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteEngine;
import com.jw6ventures.headlink.RoutePlanner;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.RouteResult;
import com.jw6ventures.headlink.RoutingException;
import com.jw6ventures.headlink.SearchHandle;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public final class RoutePlannerTest {
    private static int passed;

    /** The lab receiver's fixed position (Lexington/Waltham, MA) and Boston Common. */
    private static final double LAB_LAT = 42.4242;
    private static final double LAB_LON = -71.2121;
    private static final double COMMON_LAT = 42.3551;
    private static final double COMMON_LON = -71.0656;

    public static void main(String[] args) throws Exception {
        aRouteRequestIsTwoPointsOrNothing();
        aNewerRequestSupersedesOneInProgress();
        aNewerRequestStopsTheRunningSearch();
        aFailingEngineFailsTheRouteNotTheThread();
        runningOutOfMemoryFailsTheRouteNotTheThread();
        String dir = args.length > 0 ? args[0] : "";
        List<String> ran = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        File massachusetts = map(dir, "Us_massachusetts_northamerica.obf");
        if (massachusetts != null) {
            aCrossTownRouteIsComputedOffTheCallingThread(massachusetts);
            aSecondDestinationStopsTheFirstSearch(massachusetts);
            ran.add("Lexington to Boston and the stopped search over Massachusetts");
        } else {
            skipped.add("Lexington to Boston and the stopped search need Us_massachusetts_northamerica.obf");
        }
        File austin = map(dir, "Us_texas_austin_northamerica.obf");
        File waco = map(dir, "Us_texas_waco_northamerica.obf");
        if (austin != null && waco != null) {
            aLongTripCrossesMapsWithNothingToSwitch(List.of(austin, waco));
            ran.add("Austin to Waco over two maps");
        } else {
            skipped.add("Austin to Waco needs Us_texas_austin_northamerica.obf and Us_texas_waco_northamerica.obf");
        }
        System.out.println("jni-host-test: ok " + passed + " route planner checks"
            + (ran.isEmpty() ? "" : "; real: " + String.join(", ", ran))
            + (skipped.isEmpty() ? "" : "; skipped (OBF_DIR): " + String.join("; ", skipped)));
    }

    /** [name] in [dir], or null when there is no such file. */
    private static File map(String dir, String name) {
        if (dir.isEmpty()) return null;
        File f = Path.of(dir, name).toFile();
        return f.isFile() ? f : null;
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

    /** A stand-in engine: an instant short route, or a failure, as [answer] says. */
    private static final class FakeEngine implements RouteEngine {
        final RoutingException failure;

        FakeEngine(RoutingException failure) {
            this.failure = failure;
        }

        @Override
        public Route compute(RouteRequest request, long maxRunningMs, Function1<? super SearchHandle, Unit> started)
            throws RoutingException {
            started.invoke((SearchHandle) () -> {});
            if (failure != null) throw failure;
            return straight(request);
        }
    }

    /** A search that runs until it is terminated, or 30 s pass. */
    private static final class StoppableEngine implements RouteEngine {
        final CountDownLatch searching = new CountDownLatch(1);
        final AtomicInteger terminated = new AtomicInteger();

        @Override
        public Route compute(RouteRequest request, long maxRunningMs, Function1<? super SearchHandle, Unit> started)
            throws RoutingException {
            CountDownLatch stop = new CountDownLatch(1);
            started.invoke((SearchHandle) () -> { terminated.incrementAndGet(); stop.countDown(); });
            searching.countDown();
            try {
                if (stop.await(30, TimeUnit.SECONDS)) throw new RoutingException("search stopped", true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return straight(request);
        }
    }

    private static Route straight(RouteRequest r) {
        return new Route(new double[] {r.getFromLat(), r.getToLat()}, new double[] {r.getFromLon(), r.getToLon()},
            (int) metres(r.getFromLat(), r.getFromLon(), r.getToLat(), r.getToLon()), 60, new int[0], new int[0]);
    }

    private static Function1<RouteResult, Unit> into(LinkedBlockingQueue<RouteResult> results) {
        return result -> { results.add(result); return Unit.INSTANCE; };
    }

    /**
     * The first request is held inside the engine factory until the second and third have
     * been made, so the order is deterministic.
     */
    private static void aNewerRequestSupersedesOneInProgress() throws Exception {
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
            return new FakeEngine(null);
        }, 10_000, () -> Unit.INSTANCE);
        try {
            long first = planner.request(labToCommon(), into(results));
            check(firstInside.await(10, TimeUnit.SECONDS), "the first request reached the routing thread");
            long second = planner.request(labToCommon(), into(results));
            long third = planner.request(labToCommon(), into(results));
            release.countDown();
            RouteResult a = results.poll(30, TimeUnit.SECONDS);
            RouteResult b = results.poll(30, TimeUnit.SECONDS);
            RouteResult c = results.poll(30, TimeUnit.SECONDS);
            check(a != null && b != null && c != null, "every request is answered exactly once");
            check(results.poll(200, TimeUnit.MILLISECONDS) == null, "and no more than once");
            check(first < second && second < third, "sequences ascend");
            check(a.getSequence() == first && a.getSuperseded(), "the one in progress is marked superseded");
            check(b.getSequence() == second && b.getSuperseded() && b.getComputeMs() == 0 && b.getRoute() == null,
                "a queued one that was overtaken never starts");
            check(c.getSequence() == third && !c.getSuperseded() && c.getRoute() != null && c.getError() == null,
                "only the newest counts, and it has its route");
            for (RouteResult r : new RouteResult[] {a, b, c}) {
                check(RoutePlanner.THREAD.equals(r.getThread()), "answered on " + r.getThread());
            }
            check(factoryThreads.size() == 2 && factoryThreads.stream().allMatch(RoutePlanner.THREAD::equals),
                "the engine is only ever built on the routing thread");
        } finally {
            planner.close();
        }
    }

    /** The running search's own handle is what a newer request terminates. */
    private static void aNewerRequestStopsTheRunningSearch() throws Exception {
        StoppableEngine engine = new StoppableEngine();
        LinkedBlockingQueue<RouteResult> results = new LinkedBlockingQueue<>();
        RoutePlanner planner = new RoutePlanner(() -> engine, 60_000, () -> Unit.INSTANCE);
        try {
            planner.request(labToCommon(), into(results));
            check(engine.searching.await(10, TimeUnit.SECONDS), "the first search is running");
            long started = System.nanoTime();
            planner.request(labToCommon(), into(results));
            RouteResult first = results.poll(10, TimeUnit.SECONDS);
            check(first != null && first.getSuperseded() && first.getRoute() == null && "search stopped".equals(first.getError()),
                "the running search was stopped and marked superseded: " + (first == null ? null : first.getError()));
            check((System.nanoTime() - started) / 1_000_000L < 5_000, "at once, not when it would have ended");
            check(engine.terminated.get() == 1, "through its handle, once: " + engine.terminated.get());
            RouteResult second = results.poll(10, TimeUnit.SECONDS);
            // The second search is itself stoppable: closing the planner stops it.
            check(second == null, "the second search is still running");
        } finally {
            planner.close();
        }
        RouteResult second = results.poll(10, TimeUnit.SECONDS);
        check(second != null && second.getSuperseded() && engine.terminated.get() == 2,
            "and closing the planner stops it too");
    }

    /** A RoutingException or any other exception from the engine is a failed route. */
    private static void aFailingEngineFailsTheRouteNotTheThread() throws Exception {
        LinkedBlockingQueue<RouteResult> results = new LinkedBlockingQueue<>();
        AtomicInteger calls = new AtomicInteger();
        RoutePlanner planner = new RoutePlanner(() -> {
            if (calls.incrementAndGet() == 1) return new FakeEngine(new RoutingException(OsmAndRouter.NOT_COVERED));
            throw new IllegalStateException("bad map data");
        }, 10_000, () -> Unit.INSTANCE);
        try {
            planner.request(labToCommon(), into(results));
            RouteResult refused = results.poll(10, TimeUnit.SECONDS);
            check(refused != null && refused.getRoute() == null && OsmAndRouter.NOT_COVERED.equals(refused.getError())
                && !refused.getSuperseded(), "the engine's refusal is the route's error");
            planner.request(labToCommon(), into(results));
            RouteResult broken = results.poll(10, TimeUnit.SECONDS);
            check(broken != null && broken.getRoute() == null && broken.getError().contains("bad map data"),
                "and anything else it throws: " + (broken == null ? null : broken.getError()));
            check(calls.get() == 2, "the same routing thread took both requests");
        } finally {
            planner.close();
        }
    }

    /**
     * A search that outgrows the heap is answered as a failed route, and the routing thread
     * goes on to answer the next request. The factory throws, as the engine would.
     */
    private static void runningOutOfMemoryFailsTheRouteNotTheThread() throws Exception {
        LinkedBlockingQueue<RouteResult> results = new LinkedBlockingQueue<>();
        AtomicInteger calls = new AtomicInteger();
        RoutePlanner planner = new RoutePlanner(() -> {
            calls.incrementAndGet();
            throw new OutOfMemoryError("test heap");
        }, 10_000, () -> Unit.INSTANCE);
        try {
            for (int i = 0; i < 2; i++) {
                planner.request(labToCommon(), into(results));
                RouteResult result = results.poll(10, TimeUnit.SECONDS);
                check(result != null, "request " + i + " is answered after running out of memory");
                check(result.getRoute() == null && "route search ran out of memory".equals(result.getError()),
                    "and fails with a message: " + result.getError());
            }
            check(calls.get() == 2, "the same routing thread took both requests");
        } finally {
            planner.close();
        }
    }

    private static OsmAndRouter router(List<File> files) {
        List<OsmAndRouter.MapFile> maps = new ArrayList<>();
        for (File f : files) maps.add(OsmAndRouter.MapFile.of(f));
        return new OsmAndRouter(() -> maps);
    }

    private static void aCrossTownRouteIsComputedOffTheCallingThread(File massachusetts) throws Exception {
        LinkedBlockingQueue<RouteResult> results = new LinkedBlockingQueue<>();
        OsmAndRouter router = router(List.of(massachusetts));
        RoutePlanner planner = new RoutePlanner(() -> router, 60_000, () -> Unit.INSTANCE);
        try {
            planner.request(labToCommon(), into(results));
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
            check(speed > 5 && speed < 30, "an average of " + (int) (speed * 3.6) + " km/h is a car in and around a town");
            int n = route.getPoints();
            check(n >= 50, n + " points of geometry");
            check(metres(route.getLatitudes()[0], route.getLongitudes()[0], LAB_LAT, LAB_LON) < 500,
                "it starts near the start");
            check(metres(route.getLatitudes()[n - 1], route.getLongitudes()[n - 1], COMMON_LAT, COMMON_LON) < 500,
                "it ends near the destination");
            int[] maneuvers = route.getManeuverIndices();
            check(maneuvers.length >= 3, maneuvers.length + " maneuvers derived");
            check(maneuvers.length == route.getExitNumbers().length && maneuvers.length == route.getCommands().length,
                "one exit slot and one command per maneuver");
            boolean ordered = true;
            for (int i = 0; i < maneuvers.length; i++) {
                ordered &= maneuvers[i] >= 0 && maneuvers[i] < n && (i == 0 || maneuvers[i] > maneuvers[i - 1]);
            }
            check(ordered, "every maneuver is on the geometry, in order");
            System.out.println("route-planner-test: cross-town compute_ms=" + result.getComputeMs() + " distance_m="
                + route.getDistanceM() + " seconds=" + route.getSeconds() + " points=" + n + " maneuvers=" + maneuvers.length);
            // Again, warm: the file's index is already read.
            planner.request(labToCommon(), into(results));
            RouteResult warm = results.poll(120, TimeUnit.SECONDS);
            check(warm != null && warm.getRoute() != null, "a second identical route");
            check(warm.getRoute().getDistanceM() == route.getDistanceM() && warm.getRoute().getPoints() == n,
                "the same answer twice");
            System.out.println("route-planner-test: warm compute_ms=" + warm.getComputeMs());
        } finally {
            planner.close();
            router.close();
        }
    }

    /**
     * Boston to Pittsfield stopped by a second destination. The first search's handle is
     * held, its search not yet begun, until the second request has been made, so the stop
     * always lands on a running search however fast it would have been.
     */
    private static void aSecondDestinationStopsTheFirstSearch(File massachusetts) throws Exception {
        LinkedBlockingQueue<RouteResult> results = new LinkedBlockingQueue<>();
        CountDownLatch handed = new CountDownLatch(1);
        CountDownLatch submitted = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean firstTerminated = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Boolean> firstStopped = new java.util.concurrent.atomic.AtomicReference<>();
        OsmAndRouter router = router(List.of(massachusetts));
        // The planner gets a handle that notes its stop. The first request's first search
        // waits, after the planner has that handle, for the second request: only then can the
        // newer request stop this search rather than overtake it.
        RouteEngine signalling = (request, maxRunningMs, started) -> {
            boolean first = requests.incrementAndGet() == 1;
            AtomicInteger handles = new AtomicInteger();
            try {
                return router.compute(request, maxRunningMs, handle -> {
                    if (!first) return started.invoke(handle);
                    started.invoke((SearchHandle) () -> {
                        firstTerminated.set(true);
                        handle.terminate();
                    });
                    if (handles.incrementAndGet() == 1) {
                        handed.countDown();
                        try {
                            if (!submitted.await(30, TimeUnit.SECONDS)) throw new AssertionError("the second request never came");
                        } catch (InterruptedException e) {
                            throw new AssertionError(e);
                        }
                    }
                    return Unit.INSTANCE;
                });
            } catch (RoutingException e) {
                if (first) firstStopped.set(e.getStopped());
                throw e;
            }
        };
        RoutePlanner planner = new RoutePlanner(() -> signalling, 60_000, () -> Unit.INSTANCE);
        try {
            planner.request(new RouteRequest(COMMON_LAT, COMMON_LON, 42.4501, -73.2454), into(results));
            check(handed.await(30, TimeUnit.SECONDS), "the long search handed out its handle");
            planner.request(labToCommon(), into(results));
            submitted.countDown();
            RouteResult first = results.poll(120, TimeUnit.SECONDS);
            RouteResult second = results.poll(120, TimeUnit.SECONDS);
            check(firstTerminated.get(), "the second request stopped the first search through its handle");
            check(first != null && first.getSuperseded() && first.getRoute() == null && Boolean.TRUE.equals(firstStopped.get()),
                "the first request ended stopped, superseded and with no route, not failed: "
                    + (first == null ? null : first.getError()) + ", stopped " + firstStopped.get());
            check(second != null && second.getRoute() != null && !second.getSuperseded(), "the second is answered");
            System.out.println("route-planner-test: stopped after ms=" + first.getComputeMs() + " error=" + first.getError());
        } finally {
            submitted.countDown();
            planner.close();
            router.close();
        }
    }

    /**
     * Austin to Waco: two neighbouring OsmAnd maps given together, which is what makes a trip
     * across regions seamless. (Waco to Dallas is not tried: the Dallas map does not border
     * the Waco one, and OsmAnd cannot route across the gap.)
     */
    private static void aLongTripCrossesMapsWithNothingToSwitch(List<File> maps) throws Exception {
        double austinLat = 30.2672, austinLon = -97.7431, wacoLat = 31.5493, wacoLon = -97.1467;
        OsmAndRouter router = router(maps);
        try {
            long start = System.nanoTime();
            Route route = router.compute(new RouteRequest(austinLat, austinLon, wacoLat, wacoLon),
                RoutePlanner.MAX_RUNNING_MS, e -> Unit.INSTANCE);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            double crow = metres(austinLat, austinLon, wacoLat, wacoLon);
            check(route.getDistanceM() > crow && route.getDistanceM() < 1.4 * crow,
                "road distance " + route.getDistanceM() + " m against " + (int) crow + " m as the crow flies");
            int n = route.getPoints();
            check(metres(route.getLatitudes()[0], route.getLongitudes()[0], austinLat, austinLon) < 1000,
                "it starts in Austin, on the first map");
            check(metres(route.getLatitudes()[n - 1], route.getLongitudes()[n - 1], wacoLat, wacoLon) < 1000,
                "and ends in Waco, on the second");
            // Temple is on the way up I-35.
            boolean temple = false;
            for (int i = 0; i < n && !temple; i++) temple = metres(route.getLatitudes()[i], route.getLongitudes()[i], 31.098, -97.343) < 10_000;
            check(temple, "by Temple, up I-35");
            System.out.println("route-planner-test: austin-waco compute_ms=" + ms + " distance_m=" + route.getDistanceM()
                + " seconds=" + route.getSeconds() + " points=" + n + " maneuvers=" + route.getManeuverIndices().length);
        } finally {
            router.close();
        }
    }

    private static RouteRequest labToCommon() {
        return new RouteRequest(LAB_LAT, LAB_LON, COMMON_LAT, COMMON_LON);
    }

    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("route planner: " + what);
        passed++;
    }
}
