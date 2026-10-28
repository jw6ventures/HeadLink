// P9.4 and P9.5 at run time on a desktop JVM (docs/navigation.md, "Live traffic (HERE)"): the
// traffic loop, TrafficFeed, run whole with an injected clock, injected threads (queues this
// test drains) and the real TrafficClient over an injected HTTP layer. Nothing is sent unless
// a route is followed with traffic on and a key saved; the first fetch, the cadence, the
// retry, the one fetch after a re-route and its debounce; a response replaced whole, never
// kept, and dropped when stale, off, keyless or at the route's end; the time as paced
// BRouter plus the delay ahead, with free-flowing runs adding nothing and the pace never
// counting the delay; the no-go points on every request, including points on the route's
// own line for a closure HERE draws 12 m off it; a closure ahead re-routing once, and never
// on a route that had to open closures; the offer at 3 minutes, Keep's suppression and the
// 3 more minutes that lift it, withdrawal past the split or when the saving goes, and Take;
// and a report and event log with no place, road, reason or key in them.
//
// Every HERE response is hand-written in HERE's JSON shape and laid along a route: never a
// real one (HERE's terms, 6.4 h). With a directory holding W100_N30.rd5 as the first
// argument it also drives BRouter's real route up I-35E in Dallas with a closure laid on it,
// which must re-route round it, and a queue laid on it, which must bring an offer.
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import com.jw6ventures.headlink.AlternativeSearch;
import com.jw6ventures.headlink.ArrivalTime;
import com.jw6ventures.headlink.Congestion;
import com.jw6ventures.headlink.FollowResult;
import com.jw6ventures.headlink.FollowState;
import com.jw6ventures.headlink.LiveAlternative;
import com.jw6ventures.headlink.MemoryTrafficLedger;
import com.jw6ventures.headlink.NextStep;
import com.jw6ventures.headlink.Nogo;
import com.jw6ventures.headlink.PaceTracker;
import com.jw6ventures.headlink.PreviewRequest;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteFigures;
import com.jw6ventures.headlink.RouteFollower;
import com.jw6ventures.headlink.RouteOptions;
import com.jw6ventures.headlink.RoutePlanner;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.RouteTraffic;
import com.jw6ventures.headlink.Router;
import com.jw6ventures.headlink.RoutingData;
import com.jw6ventures.headlink.SpeedUnit;
import com.jw6ventures.headlink.StatusText;
import com.jw6ventures.headlink.TrafficArea;
import com.jw6ventures.headlink.TrafficBox;
import com.jw6ventures.headlink.TrafficClient;
import com.jw6ventures.headlink.TrafficCost;
import com.jw6ventures.headlink.TrafficDisplay;
import com.jw6ventures.headlink.TrafficFeed;
import com.jw6ventures.headlink.TrafficFetch;
import com.jw6ventures.headlink.TrafficHost;
import com.jw6ventures.headlink.TrafficHttp;
import com.jw6ventures.headlink.TrafficMatch;
import com.jw6ventures.headlink.TrafficNogos;
import com.jw6ventures.headlink.TrafficOffer;
import com.jw6ventures.headlink.TrafficResponse;
import com.jw6ventures.headlink.TrafficRun;
import com.jw6ventures.headlink.TrafficSettings;

public final class TrafficFeedTest {
    private static int passed;
    private static final double M = 6_371_000.0 * Math.PI / 180.0;
    private static final String KEY = "feed-test-key-7Q";
    private static final long MIN = 60_000L;
    private static final long WALL = ZonedDateTime.of(2026, 9, 15, 17, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();

    public static void main(String[] args) throws Exception {
        nothingIsSentWithoutARouteTrafficOrAKey();
        theFirstFetchTheCadenceAndRetries();
        aReRouteFetchesOnlyWhenTheBoxNoLongerCoversAndNotInABurst();
        aResponseIsReplacedNeverKeptAndDroppedWhenItShouldBe();
        theTimeIsPacedBRouterPlusTheDelayAhead();
        thePointsReachEveryRequest();
        aClosureAheadReRoutesOnce();
        theOffer();
        theReportSaysNothingOfWhere();
        homesRouteLineSaysTheDelay();
        reviewFixes();
        String real = "real routes skipped (no W100_N30.rd5)";
        if (args.length > 0 && !args[0].isEmpty() && new File(args[0], "W100_N30.rd5").isFile()) {
            real = realRoutes(new File(args[0]));
        }
        System.out.println("jni-host-test: ok " + passed + " traffic feed checks; " + real);
    }

    // ---- the first item, end to end through the loop -----------------------------------

    private static void nothingIsSentWithoutARouteTrafficOrAKey() {
        Route route = northbound(40_000.0, "I-35E");
        // Off (the default), on with an empty key, on with a blank one: a route followed for an hour.
        TrafficSettings[] closed = {new TrafficSettings(), settings(true, ""), settings(true, "   ")};
        for (TrafficSettings s : closed) {
            Host host = new Host(s);
            host.http.flow = flowJson(List.of(jam(route, 5_000.0, 8_000.0, 2.0, 5.0, 9.5, "x")));
            TrafficFeed feed = new TrafficFeed(host);
            feed.onRoute(route);
            for (int i = 0; i <= 60; i++) {
                host.now = i * MIN;
                feed.onPosition(route, on(i * 500.0));
                feed.tick();
                host.drain();
            }
            check(host.http.urls.isEmpty(), "no request at all while traffic is " + s + ": " + host.http.urls.size());
            check(host.ledger.status().getRequests() == 0, "and nothing counted");
            RouteRequest request = new RouteRequest(32.7, -96.8, 33.0, -96.8);
            check(feed.withNogos(request) == request, "a request goes as it came, with no points");
            check(feed.getDelaySeconds() == 0 && !feed.getDisplay().getAttributed(), "no delay, nothing to credit HERE for");
        }
        // On with a key, but no route followed: nothing, however long.
        Host host = new Host(settings(true, KEY));
        TrafficFeed feed = new TrafficFeed(host);
        for (int i = 0; i <= 60; i++) {
            host.now = i * MIN;
            feed.tick();
            host.drain();
        }
        check(host.http.urls.isEmpty(), "no route, no request");
        // A route, but no fix on it yet: not yet followed.
        feed.onRoute(route);
        for (int i = 0; i <= 10; i++) {
            host.now = (61 + i) * MIN;
            feed.tick();
            host.drain();
        }
        check(host.http.urls.isEmpty(), "a route with no fix placing the vehicle on it is not being followed");
        // Arrived is not following either.
        feed.onPosition(route, new FollowResult(FollowState.ARRIVED, 0.0, route.getDistanceM(), null));
        host.drain();
        check(host.http.urls.isEmpty(), "nor is a route arrived at");
    }

    // ---- when -----------------------------------------------------------

    private static void theFirstFetchTheCadenceAndRetries() {
        Route route = northbound(60_000.0, "I-35E");
        Host host = new Host(settings(true, KEY));
        TrafficFeed feed = new TrafficFeed(host);
        feed.onRoute(route);
        host.drain();
        check(host.http.urls.isEmpty(), "nothing before the first fix");
        feed.onPosition(route, on(0.0));
        host.drain();
        check(host.http.urls.size() == 2, "the first fix fetches at once, flow and incidents: " + host.http.urls);
        TrafficBox box = TrafficArea.INSTANCE.boxes(route, 0.0, 30.0).get(0);
        check(host.http.urls.get(0).contains("/v7/flow?in=" + box.query()) && host.http.urls.get(1).contains("/v7/incidents?in=" + box.query()),
                "for the box around the next 30 km from the vehicle");
        // Fixes and ticks through the next ten minutes: nothing more.
        for (int s = 5; s < 600; s += 5) {
            host.now = s * 1000L;
            feed.onPosition(route, on(s * 25.0));
            feed.tick();
            host.drain();
        }
        check(host.http.urls.size() == 2, "nothing more inside the refresh interval: " + host.http.urls.size());
        host.now = 10 * MIN;
        feed.onPosition(route, on(PARKED_M));
        host.drain();
        check(host.http.urls.size() == 4, "the refresh at 10 minutes: " + host.http.urls.size());
        TrafficBox moved = TrafficArea.INSTANCE.boxes(route, 595 * 25.0, 30.0).get(0);
        check(host.http.urls.get(2).contains(moved.query()) && !moved.equals(box), "the box moves with the vehicle");

        // No connection: one request (flow fails, incidents are not asked), then again in 2 minutes.
        host.http.failWith = new IOException("no route to host");
        host.now = 20 * MIN;
        feed.onPosition(route, on(PARKED_M));
        host.drain();
        check(host.http.urls.size() == 5, "a failed refresh sends flow only: " + host.http.urls.size());
        host.now = 21 * MIN;
        feed.onPosition(route, on(PARKED_M));
        host.drain();
        check(host.http.urls.size() == 5, "not again within a minute");
        host.now = 22 * MIN + 1;
        feed.onPosition(route, on(PARKED_M));
        host.drain();
        check(host.http.urls.size() == 6, "again after " + TrafficFeed.RETRY_MS / 1000 + " s, as it may pass by itself");
        check(feed.report().contains("traffic_last_error=network"), "the report says what kind of failure, never HERE's words");
        // HERE refuses the key: not again until the next refresh, so a bad key costs one request a refresh.
        host.http.failWith = null;
        host.http.status = 401;
        host.now = 24 * MIN + 2;
        feed.onPosition(route, on(PARKED_M));
        host.drain();
        check(host.http.urls.size() == 7, "a 401 is one request");
        host.now = 30 * MIN;
        feed.onPosition(route, on(PARKED_M));
        host.drain();
        check(host.http.urls.size() == 7, "and not retried within the refresh interval");
        check(feed.report().contains("traffic_last_error=http_401"), "reported as its status only");
        host.now = 34 * MIN + 3;
        feed.onPosition(route, on(PARKED_M));
        host.drain();
        check(host.http.urls.size() == 8, "tried again at the refresh");
        // At the monthly limit the client sends nothing, and the report says the cap is reached.
        host.http.status = 200;
        host.settings = new TrafficSettings(true, KEY, 10, 30, host.ledger.status().getRequests() + 1, true, "");
        host.now = 45 * MIN;
        feed.onPosition(route, on(PARKED_M));
        host.drain();
        check(host.http.urls.size() == 8, "at the limit nothing is sent");
        check(feed.report().contains("traffic_cap_reached=true") && feed.report().contains("traffic_last_error=limit"), "and the report says so");

        // Review fix 1: followed means a recent fix on the route or joining it. The
        // projection ends and the phone is put away with the route still up: ticks alone, the
        // refresh long due, fetch nothing; nor do fixes that place the vehicle off the route.
        host.settings = settings(true, KEY);
        int sent = host.http.urls.size();
        host.now = 60 * MIN;
        feed.onPosition(route, on(PARKED_M));
        host.drain();
        check(host.http.urls.size() == sent + 2, "a fix on the route at the refresh fetches: " + (host.http.urls.size() - sent));
        sent = host.http.urls.size();
        for (int m = 61; m <= 120; m++) {
            host.now = m * MIN;
            feed.tick();
            host.drain();
        }
        check(host.http.urls.size() == sent, "no fix for an hour, ticking all along: nothing fetched: " + (host.http.urls.size() - sent));
        check(feed.report().contains("traffic_following=false"), "and the report says the route is not followed");
        for (int m = 121; m <= 140; m++) {
            host.now = m * MIN;
            feed.onPosition(route, new FollowResult(FollowState.OFF_ROUTE, 300.0, PARKED_M, null));
            host.drain();
        }
        check(host.http.urls.size() == sent, "fixes off the route fetch nothing either");
        host.now = 141 * MIN;
        feed.onPosition(route, on(PARKED_M));
        host.drain();
        check(host.http.urls.size() == sent + 2, "back on the route with a fresh fix, the refresh due goes at once");
        // But while a dashboard projects, a vehicle stopped in a queue (whose unchanged fixes
        // the session does not pass on) is still following: the refresh goes on the tick.
        sent = host.http.urls.size();
        host.projecting = true;
        host.now = 170 * MIN;
        feed.tick();
        host.drain();
        check(host.http.urls.size() == sent + 2, "projecting, a stationary vehicle's route still refreshes");
        check(feed.report().contains("traffic_following=true"), "and is reported followed");
    }

    private static void aReRouteFetchesOnlyWhenTheBoxNoLongerCoversAndNotInABurst() {
        Route route = northbound(60_000.0, "I-35E");
        Host host = new Host(settings(true, KEY));
        host.http.flow = flowJson(List.of(jam(route, 10_000.0, 13_000.0, 3.0, 5.0, 9.5, "x")));
        TrafficFeed feed = new TrafficFeed(host);
        feed.onRoute(route);
        feed.onPosition(route, on(0.0));
        host.drain();
        check(host.http.urls.size() == 2, "fetched for the first route");
        // A re-route onto the same roads, still inside the box: laid on at once, nothing fetched.
        host.now = 20_000L;
        Route same = route.remainder(20);
        feed.onRoute(same);
        host.drain();
        feed.onPosition(same, on(0.0));
        host.drain();
        check(host.http.urls.size() == 2, "a new route the box still covers fetches nothing");
        RouteTraffic laid = feed.getDisplay().getTraffic();
        check(laid != null && laid.getRoute() == same && !laid.getRuns().isEmpty(), "the response held is matched to the new route itself");
        // Re-routes that leave the box: one fetch, no sooner than a minute after the last began.
        Route away = northbound(32.95, -95.90, 60_000.0, "US-69");
        host.now = 30_000L;
        feed.onRoute(away);
        feed.onPosition(away, on(0.0));
        host.drain();
        check(host.http.urls.size() == 2, "a route out of the box waits out the minute since the last fetch");
        host.now = 59_000L;
        feed.tick();
        host.drain();
        check(host.http.urls.size() == 2, "still waiting at 59 s");
        host.now = 60_000L;
        feed.tick();
        host.drain();
        check(host.http.urls.size() == 4, "one fetch once it is up: " + host.http.urls.size());
        TrafficBox fresh = TrafficArea.INSTANCE.boxes(away, 0.0, 30.0).get(0);
        check(host.http.urls.get(2).contains(fresh.query()), "for the new route's own box");
        // A burst: two more re-routes out of the box within the next minute are one fetch.
        Route third = northbound(33.40, -97.40, 60_000.0, "SH-114");
        Route fourth = northbound(32.30, -97.60, 60_000.0, "US-67");
        host.now = 70_000L;
        feed.onRoute(third);
        feed.onPosition(third, on(0.0));
        host.drain();
        host.now = 80_000L;
        feed.onRoute(fourth);
        feed.onPosition(fourth, on(0.0));
        host.drain();
        host.now = 110_000L;
        feed.tick();
        host.drain();
        check(host.http.urls.size() == 4, "no fetch within a minute of the last, however many re-routes");
        host.now = 120_000L;
        feed.tick();
        host.drain();
        check(host.http.urls.size() == 6, "then one, for the route followed now");
        check(host.http.urls.get(4).contains(TrafficArea.INSTANCE.boxes(fourth, 0.0, 30.0).get(0).query()), "the last route's box");
        host.now = 200_000L;
        feed.tick();
        host.drain();
        check(host.http.urls.size() == 6, "and no more until the refresh");
    }

    // ---- what is kept -------------------------------------------

    private static void aResponseIsReplacedNeverKeptAndDroppedWhenItShouldBe() {
        Route route = northbound(60_000.0, "I-35E");
        Host host = new Host(settings(true, KEY));
        host.http.flow = flowJson(List.of(jam(route, 10_000.0, 13_000.0, 3.0, 5.0, 9.5, "x")));
        TrafficFeed feed = new TrafficFeed(host);
        feed.onRoute(route);
        feed.onPosition(route, on(0.0));
        host.drain();
        check(feed.getDelaySeconds() > 300, "the jam is in the delay: " + feed.getDelaySeconds());
        RouteRequest request = new RouteRequest(32.7, -96.8, 33.2, -96.8);
        check(!feed.withNogos(request).getNogos().isEmpty(), "and in the points");
        // The next response has nothing: nothing of the last survives it.
        host.http.flow = flowJson(List.of());
        host.now = 10 * MIN;
        feed.onPosition(route, on(0.0));
        host.drain();
        check(feed.getDelaySeconds() == 0, "the next response replaces the last whole: no delay left");
        check(feed.withNogos(request) == request, "no points left");
        check(feed.getDisplay().getTraffic() != null && feed.getDisplay().getTraffic().getRuns().isEmpty(), "no runs left to draw");
        // A response for another box never stands in for this one: each fetch was for the box of its moment.
        host.http.flow = flowJson(List.of(jam(route, 30_000.0, 33_000.0, 3.0, 5.0, 9.5, "x")));
        host.now = 20 * MIN;
        feed.onPosition(route, on(15_000.0));
        host.drain();
        check(host.http.urls.get(host.http.urls.size() - 2).contains(TrafficArea.INSTANCE.boxes(route, 15_000.0, 30.0).get(0).query()),
                "fetched for the box around the vehicle now");
        int delay = feed.getDelaySeconds();
        check(delay > 300, "the new jam is in the delay: " + delay);

        // Failed refreshes keep it, until twice the refresh interval after it arrived.
        host.http.failWith = new IOException("offline");
        host.now = 30 * MIN;
        feed.onPosition(route, on(15_000.0));
        host.drain();
        check(feed.getDelaySeconds() == delay, "a failed refresh keeps the last response");
        host.now = 39 * MIN;
        feed.onPosition(route, on(15_000.0));
        host.drain();
        check(feed.getDelaySeconds() == delay, "still, at 19 minutes");
        host.now = 40 * MIN + 1;
        feed.onPosition(route, on(15_000.0));
        host.drain();
        check(feed.getDelaySeconds() == 0 && feed.withNogos(request) == request, "dropped once 20 minutes old: old traffic is worse than none");
        check(feed.report().contains("traffic_stale_drops=1") && feed.report().contains("traffic_held=false"), "and counted");

        // Off, keyless, or the route ended: dropped at once.
        host.http.failWith = null;
        host.now = 42 * MIN + 2;
        feed.onPosition(route, on(15_000.0));
        host.drain();
        check(feed.getDelaySeconds() > 0, "fetched again");
        host.settings = settings(false, KEY);
        feed.onPosition(route, on(15_000.0));
        host.drain();
        check(feed.getDelaySeconds() == 0 && feed.withNogos(request) == request && feed.getDisplay().getTraffic() == null,
                "turned off: everything dropped");
        host.settings = settings(true, KEY);
        host.now = 43 * MIN;
        feed.onPosition(route, on(15_000.0));
        host.drain();
        check(feed.getDelaySeconds() > 0, "on again: fetched at once");
        host.settings = settings(true, "");
        feed.onPosition(route, on(15_000.0));
        host.drain();
        check(feed.getDelaySeconds() == 0 && feed.getDisplay().getTraffic() == null, "the key removed: dropped");
        host.settings = settings(true, KEY);
        host.now = 44 * MIN;
        feed.onPosition(route, on(15_000.0));
        host.drain();
        check(feed.getDelaySeconds() > 0, "a key again: fetched");
        int sent = host.http.urls.size();
        feed.onRouteEnded();
        host.drain();
        check(feed.getDisplay().getTraffic() == null && feed.getDisplay().getDelaySeconds() == 0 && feed.withNogos(request) == request,
                "the route ended: dropped");
        for (int i = 0; i < 30; i++) {
            host.now = (45 + i) * MIN;
            feed.tick();
            host.drain();
        }
        check(host.http.urls.size() == sent, "and nothing fetched after it");
        // A fetch that comes back after its route ended is not used.
        feed.onRoute(route);
        feed.onPosition(route, on(0.0));
        host.drainNetworkOnly();
        feed.onRouteEnded();
        host.drain();
        check(feed.getDisplay().getTraffic() == null && feed.getDelaySeconds() == 0, "an answer for a route that ended is thrown away");
    }

    // ---- the time --------------------------------------------------------

    private static void theTimeIsPacedBRouterPlusTheDelayAhead() {
        Route route = northbound(40_000.0, "I-35E");
        Host host = new Host(settings(true, KEY));
        // A queue from 10 to 13 km at 5 m/s where BRouter drives 30; free flow at 20 m/s from
        // 20 to 25 km, slower than BRouter but free-flowing, which is no delay.
        host.http.flow = flowJson(List.of(jam(route, 10_000.0, 13_000.0, 2.0, 5.0, 9.5, "x"),
                span(route, 20_000.0, 25_000.0, 2.0, 20.0, 20.0, 1.0, "open", "y")));
        TrafficFeed feed = new TrafficFeed(host);
        feed.onRoute(route);
        feed.onPosition(route, on(0.0));
        host.drain();
        RouteTraffic traffic = feed.getDisplay().getTraffic();
        check(traffic != null && traffic.getRoute() == route, "the display's traffic is for the followed route itself");
        double expected = 3_000.0 / 5.0 - 3_000.0 / 30.0;
        check(Math.abs(feed.getDelaySeconds() - expected) <= 10.0, "the delay is the queue's, " + expected + " s: " + feed.getDelaySeconds());
        boolean free = false;
        for (TrafficRun run : traffic.getRuns()) if (run.getCongestion() == Congestion.FREE) free = true;
        check(free, "the slow free flow was matched, as free");
        check(feed.getDisplay().getDelaySeconds() == feed.getDelaySeconds(), "the display's delay is the one in the time");
        check(feed.delaySeconds(route) == feed.getDelaySeconds() && feed.delaySeconds(route.remainder(3)) == 0,
                "and only for the followed route");
        check(host.changed > 0, "guidance is told to publish again when a response lands with no fix to carry it");

        // Drive it: through the queue at 5 m/s, elsewhere at BRouter's 30 m/s.
        RouteFollower follower = new RouteFollower(route, 1L);
        PaceTracker pace = new PaceTracker();
        PaceTracker plain = new PaceTracker();
        double[] along = TrafficArea.INSTANCE.metres(route);
        double m = 0.0;
        long t = 0L;
        int shownBefore = -1;
        while (m < 30_000.0) {
            double[] at = at(route, along, m);
            FollowResult result = follower.update(at[0], at[1], t);
            host.now = t;
            feed.onPosition(route, result);
            host.drain();
            NextStep step = result.getStep();
            int brouter = step.getDestinationSeconds();
            // Guide gives the pace BRouter's own seconds, never the shown ones.
            pace.onFix(1L, brouter, t);
            plain.onFix(1L, brouter, t);
            int shown = ArrivalTime.INSTANCE.shown(brouter, pace, true, feed.getDelaySeconds());
            if (shown != pace.apply(brouter) + feed.getDelaySeconds()) {
                check(false, "shown is paced BRouter plus the delay, at " + m);
            }
            if (m == 0.0) shownBefore = shown;
            double speed = m >= 10_000.0 && m < 13_000.0 ? 5.0 : 30.0;
            m += speed;
            t += 1000L;
        }
        passed++;
        check(Math.abs(shownBefore - (route.getSeconds() + expected)) <= 12.0, "before moving: BRouter's time plus the queue's delay: " + shownBefore);
        check(feed.getDelaySeconds() == 0, "past the queue the delay is gone");
        check(pace.factor() == plain.factor() && pace.factor() > 1.0,
                "the pace learned the queue from BRouter's estimate alone, and it is now in the pace, not the delay: " + pace.factor());
        check(ArrivalTime.INSTANCE.shown(600, pace, false, 90) == 690, "Learn my pace off: BRouter's plus the delay");

        // A response of free flow alone: no delay, nothing to credit.
        Host quiet = new Host(settings(true, KEY));
        quiet.http.flow = flowJson(List.of(span(route, 5_000.0, 25_000.0, 2.0, 20.0, 20.0, 2.0, "open", "y")));
        TrafficFeed calm = new TrafficFeed(quiet);
        calm.onRoute(route);
        calm.onPosition(route, on(0.0));
        quiet.drain();
        check(calm.getDelaySeconds() == 0 && !calm.getDisplay().getAttributed(),
                "free flow slower than BRouter's model adds nothing, and owes no credit (an orchestrator's change to the time rule)");
    }

    // ---- the points ------------------------------------------------------

    private static void thePointsReachEveryRequest() {
        Route route = northbound(40_000.0, "I-35E");
        Host host = new Host(settings(true, KEY));
        // HERE draws the closure 12 m off the OSM line: its own 8 m circles miss the road.
        host.http.flow = flowJson(List.of(jam(route, 10_000.0, 13_000.0, 2.0, 5.0, 9.5, "x")));
        host.http.incidents = incidentsJson(List.<double[][]>of(closure(route, 20_000.0, 20_400.0, 12.0)));
        host.reroutes = false;
        TrafficFeed feed = new TrafficFeed(host);
        feed.onRoute(route);
        feed.onPosition(route, on(0.0));
        host.drain();
        RouteOptions options = new RouteOptions();
        RouteRequest request = new RouteRequest(32.70, -96.80, 33.00, -96.80, 0.0, options);
        RouteRequest with = feed.withNogos(request);
        List<Nogo> nogos = with.getNogos();
        check(!nogos.isEmpty() && with.getToLat() == request.getToLat() && with.getOptions() == options, "a new route or re-route gets the points, all else the same");
        int hardOnLine = 0;
        int hardOff = 0;
        int jamOnLine = 0;
        int jamNearOff = 0;
        for (Nogo nogo : nogos) {
            double d = fromRoute(route, nogo.getLatitude(), nogo.getLongitude());
            if (nogo.getHard()) {
                if (d < 1.0) hardOnLine++; else if (d > 10.0) hardOff++;
            } else {
                if (d < 1.0) jamOnLine++; else if (d < TrafficMatch.NEAR_M) jamNearOff++;
            }
        }
        check(hardOnLine >= 2, "the closure matched to the route is blocked on the route's own line: " + hardOnLine);
        check(hardOff >= 1, "as well as on HERE's shape 12 m off: " + hardOff);
        check(jamOnLine >= 15 && jamNearOff == 0, "the queue's points are on the route's line, in place of HERE's 2 m off: " + jamOnLine + "/" + jamNearOff);
        boolean hardLast = true;
        boolean seenHard = false;
        for (Nogo nogo : nogos) {
            if (nogo.getHard()) seenHard = true; else if (seenHard) hardLast = false;
        }
        check(hardLast, "hard points after weighted, as Router keeps them");
        // Priced at the request's own top speed.
        RouteOptions slow = options.copy(false, false, false, false, false, 90, options.getEtaPercent(), options.getLearnPace(), options.getShowAlternatives());
        List<Nogo> cheaper = feed.nogos(slow);
        double scale = TrafficCost.INSTANCE.secondPrice(90) / TrafficCost.INSTANCE.secondPrice(RouteOptions.DEFAULT_TOP_SPEED_KMH);
        boolean scaled = cheaper.size() == nogos.size();
        for (int i = 0; scaled && i < nogos.size(); i++) {
            Double a = nogos.get(i).getWeight();
            Double b = cheaper.get(i).getWeight();
            scaled = (a == null && b == null) || (a != null && b != null && Math.abs(b - a * scale) < 1e-9);
        }
        check(scaled && scale < 1.0, "a search at 90 km/h gets the same points at 90 km/h's price");
        // The alternatives search: every request it makes carries them.
        PreviewRequest trip = new PreviewRequest(1L, 32.71, -96.80, 33.0, -96.80, null, options, route, feed.nogos(options));
        check(trip.alternative(1).getNogos().equals(nogos) && trip.through(32.8, -96.79).getNogos().equals(nogos)
                && trip.alternative(0).getNogos().equals(nogos), "BRouter's alternatives and every via route get them too");
        check(feed.getDisplay().getRouteShaped() == route.getTrafficShaped(), "the display credits HERE for a route traffic shaped");
        Route shaped = route.withNogos(nogos.size(), false);
        feed.onRoute(shaped);
        host.drain();
        check(feed.getDisplay().getRouteShaped() && feed.getDisplay().getAttributed(), "a route made with the points is credited to HERE from the start");
        // Turned off, a request carries none.
        host.settings = settings(false, KEY);
        check(feed.withNogos(request) == request, "off: none");
    }

    private static void aClosureAheadReRoutesOnce() {
        Route route = northbound(40_000.0, "I-35E");
        Host host = new Host(settings(true, KEY));
        host.http.incidents = incidentsJson(List.<double[][]>of(closure(route, 20_000.0, 20_400.0, 3.0)));
        TrafficFeed feed = new TrafficFeed(host);
        feed.onRoute(route);
        feed.onPosition(route, on(0.0));
        host.drain();
        check(host.rerouteCalls == 1, "a closure ahead on the route re-routes at once: " + host.rerouteCalls);
        for (int i = 1; i < 20; i++) {
            feed.onPosition(route, on(i * 100.0));
            host.drain();
        }
        check(host.rerouteCalls == 1, "once, not every fix");
        // The re-route came back over the same closure (say the only way): not again.
        Route again = route.remainder(10);
        feed.onRoute(again);
        feed.onPosition(again, on(0.0));
        host.drain();
        check(host.rerouteCalls == 1, "the same closure on the new route does not re-route again");
        // A route BRouter found only by opening closures: never, even for a closure not yet seen.
        Host opened = new Host(settings(true, KEY));
        opened.http.incidents = incidentsJson(List.<double[][]>of(closure(route, 20_000.0, 20_400.0, 3.0)));
        TrafficFeed through = new TrafficFeed(opened);
        Route open = route.withNogos(12, true);
        through.onRoute(open);
        through.onPosition(open, on(0.0));
        opened.drain();
        check(opened.rerouteCalls == 0, "a route through an opened closure never re-routes for it (it would loop)");
        // A closure behind the vehicle is not ahead.
        Host behind = new Host(settings(true, KEY));
        behind.http.incidents = incidentsJson(List.<double[][]>of(closure(route, 2_000.0, 2_300.0, 3.0)));
        TrafficFeed passed = new TrafficFeed(behind);
        passed.onRoute(route);
        passed.onPosition(route, on(5_000.0));
        behind.drain();
        check(behind.rerouteCalls == 0, "a closure already passed is not a reason");
        // A re-route refused (one already in flight) is asked again at the next fix.
        Host busy = new Host(settings(true, KEY));
        busy.http.incidents = incidentsJson(List.<double[][]>of(closure(route, 20_000.0, 20_400.0, 3.0)));
        busy.reroutes = false;
        TrafficFeed waiting = new TrafficFeed(busy);
        waiting.onRoute(route);
        waiting.onPosition(route, on(0.0));
        busy.drain();
        busy.reroutes = true;
        waiting.onPosition(route, on(100.0));
        busy.drain();
        check(busy.rerouteCalls == 2 && waiting.report().contains("traffic_closure_reroutes=1"), "a refused re-route is asked again, and counted once done");
    }

    // ---- the offer (P9.6) ------------------------------------------------

    private static void theOffer() {
        Route route = northbound(40_000.0, "I-35E");
        Host host = new Host(settings(true, KEY));
        // HERE names the stretch by its exit; the reason must come from the map.
        host.http.flow = flowJson(List.of(jam(route, 15_000.0, 25_000.0, 2.0, 5.0, 9.5, "Oak Lawn Ave/Exit 430")));
        TrafficFeed feed = new TrafficFeed(host);
        feed.onRoute(route);
        feed.onPosition(route, on(0.0));
        host.drain();
        check(host.looks == 1, "a response with enough delay ahead asks for one search: " + host.looks);
        int delay = feed.getDelaySeconds();
        check(delay > 1_500, "a heavy queue: " + delay);

        Look look = new Look(route, 0.0);
        // One saving 170 s (BRouter +x less the queue): not enough.
        Route short1 = aside(look, 10_000.0, look.ahead.getSeconds() + delay - 170);
        Assessed a = look.assess(feed, host, List.of(short1));
        check(a.offered == null && feed.getDisplay().getOffer() == null, "under 3 minutes saved is no offer");
        check(a.extras.length == 1 && Math.abs(a.extras[0] + delay) <= 2, "its bubble counts both routes' traffic: " + a.extras[0]);
        // One saving 190 s: offered.
        Route round = aside(look, 10_000.0, look.ahead.getSeconds() + delay - 190);
        a = look.assess(feed, host, List.of(round));
        TrafficOffer offer = feed.getDisplay().getOffer();
        check(offer != null && a.offered == round && offer.getRoute() == round, "3 minutes saved is an offer, kept out of the bubbles");
        check(offer != null && Math.abs(offer.getSavingSeconds() - 190) <= 1, "saving " + (offer == null ? "-" : offer.getSavingSeconds()));
        check(offer != null && "Queue on I-35E".equals(offer.getReason()), "the reason names the road from the map, not HERE's exit: " + (offer == null ? "-" : offer.getReason()));
        check(offer != null && RouteFollower.Companion.distance(offer.getSplitLat(), offer.getSplitLon(), at(route, TrafficArea.INSTANCE.metres(route), 10_000.0)[0],
                at(route, TrafficArea.INSTANCE.metres(route), 10_000.0)[1]) < 1.0, "split where it leaves the route");
        long id = offer.getId();
        // The same way round again, saving a little more: the same offer, not a new card.
        Route roundAgain = aside(look, 10_000.0, look.ahead.getSeconds() + delay - 250);
        look.assess(feed, host, List.of(roundAgain));
        check(feed.getDisplay().getOffer() != null && feed.getDisplay().getOffer().getId() == id, "the same way round keeps its id");
        // Keep: gone, and not offered again unless it saves 3 minutes more.
        feed.answer(id, false);
        host.drain();
        check(feed.getDisplay().getOffer() == null, "Keep takes it down");
        look.assess(feed, host, List.of(aside(look, 10_000.0, look.ahead.getSeconds() + delay - 250)));
        check(feed.getDisplay().getOffer() == null, "kept: not offered again");
        look.assess(feed, host, List.of(aside(look, 10_000.0, look.ahead.getSeconds() + delay - 400)));
        check(feed.getDisplay().getOffer() == null, "nor with 2.5 minutes more");
        look.assess(feed, host, List.of(aside(look, 10_000.0, look.ahead.getSeconds() + delay - 440)));
        TrafficOffer regrown = feed.getDisplay().getOffer();
        check(regrown != null && regrown.getId() != id, "with 3 minutes more it is offered again, as a new offer");
        // Another way round, splitting elsewhere, is not the kept one.
        // Withdrawn once the vehicle is past the split.
        feed.onPosition(route, on(10_020.0));
        host.drain();
        check(feed.getDisplay().getOffer() != null, "not withdrawn short of the split and its margin");
        feed.onPosition(route, on(10_040.0));
        host.drain();
        check(feed.getDisplay().getOffer() == null, "withdrawn past the split");
        // Splits behind the vehicle are never offered.
        a = look.assess(feed, host, List.of(aside(look, 10_000.0, look.ahead.getSeconds() + delay - 900)));
        check(a.offered == null && feed.getDisplay().getOffer() == null, "a way round leaving behind the vehicle is no offer");

        // Take follows it, through the host.
        TrafficFeed second = fresh(route, host);
        Look again = new Look(route, 0.0);
        again.assess(second, host, List.of(aside(again, 12_000.0, again.ahead.getSeconds())));
        TrafficOffer taking = second.getDisplay().getOffer();
        check(taking != null, "offered");
        second.answer(taking.getId() + 100, true);
        check(host.taken.isEmpty(), "an answer to another offer does nothing");
        second.answer(taking.getId(), true);
        host.drain();
        check(host.taken.size() == 1 && host.taken.get(0) == taking && second.getDisplay().getOffer() == null, "Take follows the offer and takes it down");

        // The saving gone at the next update: withdrawn, and no search spent on it.
        TrafficFeed third = fresh(route, host);
        Look third1 = new Look(route, 0.0);
        third1.assess(third, host, List.of(aside(third1, 12_000.0, third1.ahead.getSeconds())));
        check(third.getDisplay().getOffer() != null, "offered");
        int looks = host.looks;
        host.http.flow = flowJson(List.of());
        host.now += 10 * MIN;
        third.onPosition(route, on(0.0));
        host.drain();
        check(third.getDisplay().getOffer() == null && host.looks == looks, "the queue cleared: withdrawn at once, no search");
        // A route merely BRouter-faster, with no traffic on the followed one: never "faster for traffic".
        Route quicker = aside(third1, 12_000.0, third1.ahead.getSeconds() - 600);
        Assessed none = third1.assess(third, host, List.of(quicker));
        check(none.offered == null && third.getDisplay().getOffer() == null, "no traffic on the route, no traffic offer");
        // A way round with a closure of its own is not offered.
        host.http.flow = flowJson(List.of(jam(route, 15_000.0, 25_000.0, 2.0, 5.0, 9.5, "x")));
        Route blocked = aside(third1, 12_000.0, third1.ahead.getSeconds());
        host.http.incidents = incidentsJson(List.<double[][]>of(closureOn(blocked, 0.6)));
        host.now += 10 * MIN;
        third.onPosition(route, on(0.0));
        host.drain();
        Assessed shut = third1.assess(third, host, List.of(blocked));
        check(shut.offered == null && third.getDisplay().getOffer() == null, "a way round that is closed itself is not offered");
        host.http.incidents = incidentsJson(List.of());
        check(host.looks == looks + 1, "one search for each traffic update worth one: " + (host.looks - looks));
    }

    // ---- the code review's fixes (docs/verification.md) -------------------------------------

    private static void reviewFixes() {
        // Fix 6: an answer for a dropped generation must not mark the new generation's fetch
        // finished, or the next tick starts it again.
        Route route = northbound(40_000.0, "I-35E");
        Host host = new Host(settings(true, KEY));
        TrafficFeed feed = new TrafficFeed(host);
        feed.onRoute(route);
        feed.onPosition(route, on(0.0));
        check(host.network.size() == 1, "the first fix queues one fetch");
        host.settings = settings(false, KEY);
        feed.tick();
        host.settings = settings(true, KEY);
        host.now = 5_000L;
        feed.onPosition(route, on(0.0));
        check(host.network.size() == 2, "off and on again: a fetch for the new generation is queued behind the old one");
        host.network.poll().invoke();
        while (!host.main.isEmpty()) host.main.poll().invoke();
        host.now = 10_000L;
        feed.onPosition(route, on(0.0));
        check(host.network.size() == 1, "the old answer lands and the new fetch is still in flight: not started again: " + host.network.size());
        host.drain();
        check(host.http.urls.size() == 4 && feed.getDelaySeconds() >= 0, "two fetches in all: " + host.http.urls.size());

        // Fix 7: only a way round faster because of traffic is offered. 100 s of
        // queue ahead and a way round BRouter makes 400 s quicker saves 500 s, but only 100 s
        // of it is traffic's: not an offer. With 300 s of queue, it is.
        Host light = new Host(settings(true, KEY));
        light.http.flow = flowJson(List.of(jam(route, 15_000.0, 16_000.0, 2.0, 7.5, 9.5, "x")));
        TrafficFeed small = new TrafficFeed(light);
        small.onRoute(route);
        small.onPosition(route, on(0.0));
        light.drain();
        int delay = small.getDelaySeconds();
        check(Math.abs(delay - 100) <= 3, "a 100 s queue: " + delay);
        Look look = new Look(route, 0.0);
        Assessed a = look.assess(small, light, List.of(aside(look, 10_000.0, look.ahead.getSeconds() - 400)));
        check(a.offered == null && small.getDisplay().getOffer() == null, "BRouter-faster with a little traffic is left to the bubbles");
        Host heavy = new Host(settings(true, KEY));
        heavy.http.flow = flowJson(List.of(jam(route, 15_000.0, 16_000.0, 2.0, 3.0, 9.5, "x")));
        TrafficFeed big = new TrafficFeed(heavy);
        big.onRoute(route);
        big.onPosition(route, on(0.0));
        heavy.drain();
        Look lookHeavy = new Look(route, 0.0);
        Assessed b = lookHeavy.assess(big, heavy, List.of(aside(lookHeavy, 10_000.0, lookHeavy.ahead.getSeconds() - 400)));
        TrafficOffer offer = big.getDisplay().getOffer();
        check(b.offered != null && offer != null && Math.abs(offer.getSavingSeconds() - 400 - big.getDelaySeconds()) <= 2,
                "with 300 s of queue it saves, the same way round is offered: " + (offer == null ? "-" : offer.getSavingSeconds()));

        // Fix 3: a point off the globe never reaches a route request.
        Host bad = new Host(settings(true, KEY));
        bad.http.flow = "{\"results\":[{\"location\":{\"length\":1000.0,\"shape\":{\"links\":[{\"points\":["
                + "{\"lat\":1e999,\"lng\":-96.8},{\"lat\":\"NaN\",\"lng\":-96.8}],\"length\":1000.0,\"functionalClass\":1}]}},"
                + "\"currentFlow\":{\"speed\":2.0,\"freeFlow\":30.0,\"jamFactor\":9.5,\"traversability\":\"open\"}}]}";
        TrafficFeed odd = new TrafficFeed(bad);
        odd.onRoute(route);
        odd.onPosition(route, on(0.0));
        bad.drain();
        RouteRequest asked = odd.withNogos(new RouteRequest(32.7, -96.8, 33.0, -96.8));
        check(asked.getNogos().stream().allMatch(n -> Double.isFinite(n.getLatitude()) && Math.abs(n.getLatitude()) <= 90.0),
                "no point off the globe reaches a request");
    }

    private static TrafficFeed fresh(Route route, Host host) {
        host.http.flow = flowJson(List.of(jam(route, 15_000.0, 25_000.0, 2.0, 5.0, 9.5, "x")));
        host.http.incidents = incidentsJson(List.of());
        TrafficFeed feed = new TrafficFeed(host);
        feed.onRoute(route);
        feed.onPosition(route, on(0.0));
        host.drain();
        return feed;
    }

    // ---- the report ------------------------------------------------------------------------

    private static void theReportSaysNothingOfWhere() {
        Route route = northbound(40_000.0, "I-35E");
        Host host = new Host(settings(true, KEY));
        host.http.flow = flowJson(List.of(jam(route, 15_000.0, 25_000.0, 2.0, 5.0, 9.5, "Oak Lawn Ave/Exit 430")));
        host.http.incidents = incidentsJson(List.<double[][]>of(closure(route, 30_000.0, 30_300.0, 3.0)));
        TrafficFeed feed = new TrafficFeed(host);
        feed.onRoute(route);
        feed.onPosition(route, on(0.0));
        host.drain();
        Look look = new Look(route, 0.0);
        look.assess(feed, host, List.of(aside(look, 12_000.0, look.ahead.getSeconds())));
        check(feed.getDisplay().getOffer() != null, "an offer is up while the report is written");
        String report = feed.report();
        StringBuilder all = new StringBuilder(report);
        for (String note : host.notes) all.append('\n').append(note);
        String text = all.toString();
        for (String secret : List.of(KEY, "I-35E", "Oak Lawn", "Queue", "Faster", "32.7", "32.8", "96.8", "-96", "bbox", "Closed")) {
            check(!text.contains(secret), "the report and the event log never carry \"" + secret + "\"");
        }
        for (String field : List.of("traffic_enabled=true", "traffic_key=true", "traffic_fetches=1", "traffic_boxes=1",
                "traffic_requests_session=2", "traffic_requests_month=2", "traffic_cap_reached=false", "traffic_last_fetch_age_s=0",
                "traffic_matched_m=", "traffic_delay_min=", "traffic_nogos=", "traffic_nogos_hard=", "traffic_nogos_on_route=",
                "traffic_closure_reroutes=1", "traffic_offers_made=1", "traffic_offers_taken=0", "traffic_offers_kept=0",
                "traffic_offers_withdrawn=0", "traffic_offer_up=true", "traffic_last_error=none")) {
            check(report.contains(field), "the report has " + field + ": " + report);
        }
    }

    // ---- Home's route line (P9.4) ------------------------------------------------------------

    private static void homesRouteLineSaysTheDelay() {
        StatusText text = StatusText.INSTANCE;
        // The trip panel's rounding (Dashboard.trafficDelayLabel): nearest minute, nothing under half of one.
        int[][] rows = {{0, 0}, {29, 0}, {30, 1}, {89, 1}, {90, 2}, {420, 7}};
        for (int[] row : rows) {
            String label = text.trafficLabel(row[0]);
            check(row[1] == 0 ? label == null : ("+" + row[1] + " min traffic").equals(label), row[0] + " s reads " + label);
        }
        String line = text.routeSummary("Home", false, false, false, 19_312.0, 25 * 60, "6:40 PM", SpeedUnit.MPH, 420);
        check(line != null && line.endsWith(" \u00b7 arrive 6:40 PM \u00b7 +7 min traffic"), "after the arrival: " + line);
        check(!text.routeSummary("Home", false, false, false, 19_312.0, 25 * 60, "6:40 PM", SpeedUnit.MPH, 20).contains("traffic"),
                "20 s says nothing");
        check(text.routeSummary("Home", false, false, false, 19_312.0, 25 * 60, "6:40 PM", SpeedUnit.MPH)
                .equals(text.routeSummary("Home", false, false, false, 19_312.0, 25 * 60, "6:40 PM", SpeedUnit.MPH, 0)), "none is as before");
        RouteFigures live = RouteFigures.Companion.choose(new kotlin.Pair<>(10_000.0, 1_500), 12_000, 1_000, -1L, 0L, 420);
        check(live != null && live.getSeconds() == 1_500 && live.getTrafficSeconds() == 420 && live.getArrivalMillis() == 1_500_000L,
                "the live time already holds the delay, and the arrival is now plus it");
        RouteFigures totals = RouteFigures.Companion.choose(null, 12_000, 1_000, 0L, 0L, 420);
        check(totals != null && totals.getTrafficSeconds() == 0, "the route's totals carry no traffic");
    }

    // ---- real roads: Dallas, I-35E ------------------------------------------------------------

    private static String realRoutes(File segments) throws Exception {
        File work = Files.createTempDirectory("traffic-feed-test").toFile();
        Router router = new Router(segments, RoutingData.INSTANCE.installProfile(new File(work, "profiles")), 64);
        double toLat = 32.8450;
        double toLon = -96.8850;
        RouteOptions options = new RouteOptions();
        Route route = router.compute(new RouteRequest(32.7800, -96.8050, toLat, toLon, null, options), RoutePlanner.MAX_RUNNING_MS, e -> Unit.INSTANCE);
        double[] along = TrafficArea.INSTANCE.metres(route);
        // The first 3 km BRouter drives at freeway speed, at least 2 km in (past the lead point).
        double c = -1.0;
        for (double m = 2_000.0; m + 3_000.0 < along[along.length - 1]; m += 100.0) {
            if (3_000.0 / (seconds(route, along, m + 3_000.0) - seconds(route, along, m)) > 22.0) {
                c = m;
                break;
            }
        }
        check(c >= 0.0, "the route has 3 km of freeway");
        double[] closed = at(route, along, c + 1_150.0);

        // A closure laid on it, HERE's shape 3 m off: the loop must re-route round it at once.
        Host host = new Host(settings(true, KEY));
        host.http.incidents = incidentsJson(List.<double[][]>of(closure(route, c + 1_000.0, c + 1_300.0, 3.0)));
        TrafficFeed feed = new TrafficFeed(host);
        Route[] followed = {route};
        long[] rerouteMs = {0L};
        host.onReroute = () -> {
            long begun = System.nanoTime();
            RouteRequest request = feed.withNogos(new RouteRequest(route.getLatitudes()[0], route.getLongitudes()[0], toLat, toLon, null, options));
            try {
                Route next = router.compute(request, RoutePlanner.MAX_RUNNING_MS, e -> Unit.INSTANCE);
                rerouteMs[0] = (System.nanoTime() - begun) / 1_000_000L;
                check(request.getNogos().size() > 0, "the re-route carries the points");
                check(!next.getClosuresOpened() && next.getTrafficShaped(), "a way round was found without opening the closure");
                check(Router.Companion.closuresCrossed(next, request, request.getNogos()).isEmpty(), "and it passes through no closure point");
                check(fromRoute(next, closed[0], closed[1]) > TrafficNogos.RADIUS_M, "it keeps off the closed road: " + fromRoute(next, closed[0], closed[1]));
                followed[0] = next;
            } catch (Exception e) {
                throw new AssertionError("re-route failed: " + e.getMessage());
            }
        };
        feed.onRoute(route);
        feed.onPosition(route, on(0.0));
        host.drain();
        check(host.rerouteCalls == 1 && followed[0] != route, "the closure ahead re-routed once");
        feed.onRoute(followed[0]);
        feed.onPosition(followed[0], on(0.0));
        host.drain();
        check(host.rerouteCalls == 1, "and the new route is not re-routed again");
        check(feed.getDisplay().getRouteShaped(), "the new route is credited to HERE");

        // A long queue laid on it: the search with the points must find a way round worth offering.
        Host jammed = new Host(settings(true, KEY));
        jammed.http.flow = flowJson(List.of(jam(route, c, c + 3_000.0, 3.0, 2.0, 10.0, "x")));
        TrafficFeed queue = new TrafficFeed(jammed);
        List<Route> found = new ArrayList<>();
        long[] lookMs = {0L};
        Look look = new Look(route, 0.0);
        jammed.onLook = () -> {
            long begun = System.nanoTime();
            PreviewRequest trip = new PreviewRequest(1L, look.ahead.getLatitudes()[0], look.ahead.getLongitudes()[0], toLat, toLon, null,
                    options, look.ahead, queue.nogos(options));
            AlternativeSearch.INSTANCE.find(router, trip, queue.worthLooking(), RoutePlanner.MAX_RUNNING_MS, e -> Unit.INSTANCE, () -> false,
                    r -> { found.add(r); return Unit.INSTANCE; });
            lookMs[0] = (System.nanoTime() - begun) / 1_000_000L;
        };
        queue.onRoute(route);
        queue.onPosition(route, on(0.0));
        jammed.drain();
        check(jammed.looks == 1 && !found.isEmpty(), "one search for the update, which found ways round: " + found.size());
        Assessed a = look.assess(queue, jammed, found);
        TrafficOffer offer = queue.getDisplay().getOffer();
        check(offer != null && offer.getSavingSeconds() >= TrafficFeed.OFFER_SAVING_S, "a way round the queue is offered: "
                + (offer == null ? "none" : offer.getSavingSeconds() + " s"));
        check(offer != null && a.offered == offer.getRoute(), "and kept out of the bubbles");
        // Laid on the same response, the way round misses most of the queue: it takes the
        // frontage road or another street, perhaps within metres of the main lanes.
        List<com.jw6ventures.headlink.FlowStretch> flowHeld = com.jw6ventures.headlink.TrafficJson.INSTANCE.flow(new java.io.StringReader(jammed.http.flow));
        double itsDelay = offer == null ? -1.0 : TrafficMatch.INSTANCE.match(offer.getRoute(), flowHeld, List.of()).delayAheadSeconds(0.0);
        check(offer != null && itsDelay < queue.getDelaySeconds() / 4.0, "the way round misses the queue: " + itsDelay + " s of it against " + queue.getDelaySeconds());
        System.out.println(String.format(Locale.ROOT, "traffic-feed-test: real route distance_m=%d jam_from_m=%d closure_reroute_ms=%d "
                + "delay_s=%d nogos=%d look_ms=%d found=%d saving_s=%d offer_delay_s=%d offer_via=%b", route.getDistanceM(), Math.round(c), rerouteMs[0],
                queue.getDelaySeconds(), queue.nogos(options).size(), lookMs[0], found.size(), offer == null ? -1 : offer.getSavingSeconds(),
                Math.round(itsDelay), offer != null && offer.getRoute().getVia() != null));
        return "real routes over W100_N30 (Dallas, I-35E: a closure re-routed round, a queue offered a way round)";
    }

    // ---- fakes ------------------------------------------------------------------------------

    /** The app's side of the loop: settings, the real client over a fake HTTP layer, a clock and three queues. */
    private static final class Host implements TrafficHost {
        TrafficSettings settings;
        long now;
        final FakeHttp http = new FakeHttp();
        final MemoryTrafficLedger ledger = new MemoryTrafficLedger();
        final TrafficClient client;
        final ArrayDeque<Function0<Unit>> network = new ArrayDeque<>();
        final ArrayDeque<Function0<Unit>> compute = new ArrayDeque<>();
        final ArrayDeque<Function0<Unit>> main = new ArrayDeque<>();
        final List<String> notes = new ArrayList<>();
        final List<TrafficOffer> taken = new ArrayList<>();
        boolean reroutes = true;
        boolean projecting;
        int rerouteCalls;
        int looks;
        int changed;
        int displays;
        Runnable onReroute;
        Runnable onLook;

        Host(TrafficSettings settings) {
            this.settings = settings;
            this.client = new TrafficClient(() -> this.settings, ledger, http, () -> now, () -> WALL, false, ZoneOffset.UTC);
        }

        void drain() {
            while (!network.isEmpty() || !compute.isEmpty() || !main.isEmpty()) {
                if (!network.isEmpty()) network.poll().invoke();
                else if (!compute.isEmpty()) compute.poll().invoke();
                else main.poll().invoke();
            }
        }

        /** Runs only the fetches queued now, leaving their answers on the main queue. */
        void drainNetworkOnly() {
            while (!network.isEmpty()) network.poll().invoke();
        }

        @Override public TrafficSettings settings() { return settings; }
        @Override public TrafficFetch fetch(TrafficBox box) { return client.fetch(box); }
        @Override public int requestsThisMonth() { return ledger.status().getRequests(); }
        @Override public long now() { return now; }
        @Override public void network(Function0<Unit> task) { network.add(task); }
        @Override public void compute(Function0<Unit> task) { compute.add(task); }
        @Override public void main(Function0<Unit> task) { main.add(task); }
        @Override public int topSpeedKmh() { return RouteOptions.DEFAULT_TOP_SPEED_KMH; }
        @Override public boolean projecting() { return projecting; }
        @Override public boolean reroute() {
            rerouteCalls++;
            if (reroutes && onReroute != null) onReroute.run();
            return reroutes;
        }
        @Override public void look() {
            looks++;
            if (onLook != null) onLook.run();
        }
        @Override public void take(TrafficOffer offer) { taken.add(offer); }
        @Override public void changed() { changed++; }
        @Override public void display(TrafficDisplay display) { displays++; }
        @Override public void note(String text) { notes.add(text); }
    }

    /** HERE, hand-written: the flow and incidents bodies set, and every URL asked for. */
    private static final class FakeHttp implements TrafficHttp {
        final List<String> urls = new ArrayList<>();
        String flow = "{\"results\":[]}";
        String incidents = "{\"results\":[]}";
        int status = 200;
        IOException failWith;

        @Override
        public TrafficResponse get(String url, Map<String, String> headers) throws IOException {
            urls.add(url);
            if (failWith != null) throw failWith;
            String body = status != 200 ? "{\"error\":\"Unauthorized\",\"error_description\":\"apiKey invalid\"}"
                    : url.contains("/v7/flow?") ? flow : incidents;
            return new TrafficResponse(status, null, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), () -> Unit.INSTANCE);
        }
    }

    /** The live alternatives' side: the followed route from its lead point, as LiveAlternatives cuts it. */
    private static final class Look {
        final Route followed;
        final Route ahead;
        final double aheadAlongM;

        Look(Route followed, double vehicleM) {
            this.followed = followed;
            RouteFollower follower = new RouteFollower(followed, 9L);
            Route fromVehicle = followed.remainder(follower.indexAt(vehicleM) + 1);
            this.ahead = fromVehicle.remainder(LiveAlternative.Companion.leadIndex(fromVehicle));
            this.aheadAlongM = follower.alongNearest(ahead.getLatitudes()[0], ahead.getLongitudes()[0]);
        }

        Assessed assess(TrafficFeed feed, Host host, List<Route> alternatives) {
            Assessed result = new Assessed();
            feed.assess(followed, ahead, aheadAlongM, alternatives, (extras, offered) -> {
                result.extras = extras;
                result.offered = offered;
                result.done = true;
                return Unit.INSTANCE;
            });
            host.drain();
            check(result.done, "the assessment always answers");
            return result;
        }
    }

    private static final class Assessed {
        int[] extras = new int[0];
        Route offered;
        boolean done;
    }

    // ---- HERE's shape --------------------------------------------------------------------------

    /** One hand-written flow result: a line and its spans ([from, to, speed or NaN, free, jam, traversability]). */
    private static final class Stretch {
        final String description;
        final double[][] line;
        final List<Object[]> spans;

        Stretch(String description, double[][] line, List<Object[]> spans) {
            this.description = description;
            this.line = line;
            this.spans = spans;
        }
    }

    private static Stretch jam(Route route, double from, double to, double lateral, double speed, double jamFactor, String description) {
        return span(route, from, to, lateral, speed, 30.0, jamFactor, "open", description);
    }

    private static Stretch span(Route route, double from, double to, double lateral, double speed, double free, double jamFactor,
            String traversability, String description) {
        double[][] line = laid(route, TrafficArea.INSTANCE.metres(route), from, to, lateral);
        return new Stretch(description, line, List.<Object[]>of(new Object[] {to - from, speed, free, jamFactor, traversability}));
    }

    private static double[][] closure(Route route, double from, double to, double lateral) {
        return laid(route, TrafficArea.INSTANCE.metres(route), from, to, lateral);
    }

    /** A closure on [route]'s own stretch, [share] of the way along it, 300 m long. */
    private static double[][] closureOn(Route route, double share) {
        double[] along = TrafficArea.INSTANCE.metres(route);
        double at = along[along.length - 1] * share;
        return laid(route, along, at, at + 300.0, 2.0);
    }

    private static String flowJson(List<Stretch> stretches) {
        StringBuilder json = new StringBuilder("{\"sourceUpdated\":\"2026-09-15T17:00:00Z\",\"results\":[");
        for (int k = 0; k < stretches.size(); k++) {
            Stretch s = stretches.get(k);
            double length = 0.0;
            for (Object[] span : s.spans) length += (Double) span[0];
            if (k > 0) json.append(',');
            json.append("{\"location\":{\"description\":\"").append(s.description).append("\",\"length\":").append(number(length))
                    .append(",\"shape\":{\"links\":[{\"points\":").append(points(s.line)).append(",\"length\":").append(number(length))
                    .append(",\"functionalClass\":1}]}},\"currentFlow\":");
            Object[] first = s.spans.get(0);
            json.append(flowFields(first, false));
            json.append('}');
        }
        return json.append("]}").toString();
    }

    private static String flowFields(Object[] span, boolean withLength) {
        StringBuilder f = new StringBuilder("{");
        if (withLength) f.append("\"length\":").append(number((Double) span[0])).append(',');
        double speed = (Double) span[1];
        if (!Double.isNaN(speed)) f.append("\"speed\":").append(number(speed)).append(',');
        f.append("\"freeFlow\":").append(number((Double) span[2])).append(",\"jamFactor\":").append(number((Double) span[3]))
                .append(",\"confidence\":0.9,\"traversability\":\"").append(span[4]).append("\"}");
        return f.toString();
    }

    private static String incidentsJson(List<double[][]> closures) {
        StringBuilder json = new StringBuilder("{\"sourceUpdated\":\"2026-09-15T17:00:00Z\",\"results\":[");
        for (int k = 0; k < closures.size(); k++) {
            if (k > 0) json.append(',');
            json.append("{\"location\":{\"shape\":{\"links\":[{\"points\":").append(points(closures.get(k)))
                    .append("}]}},\"incidentDetails\":{\"id\":\"c").append(k)
                    .append("\",\"type\":\"roadClosure\",\"criticality\":\"critical\",\"roadClosed\":true,")
                    .append("\"summary\":{\"value\":\"Closed\"}}}");
        }
        return json.append("]}").toString();
    }

    private static String points(double[][] line) {
        StringBuilder p = new StringBuilder("[");
        for (int i = 0; i < line[0].length; i++) {
            if (i > 0) p.append(',');
            p.append("{\"lat\":").append(String.format(Locale.ROOT, "%.5f", line[0][i]))
                    .append(",\"lng\":").append(String.format(Locale.ROOT, "%.5f", line[1][i])).append('}');
        }
        return p.append(']').toString();
    }

    private static String number(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    // ---- routes ------------------------------------------------------------------------------

    private static TrafficSettings settings(boolean on, String key) {
        return new TrafficSettings(on, key, 10, 30, 4_500, true, "");
    }

    /** Where theFirstFetchTheCadenceAndRetries leaves the vehicle parked, metres along. */
    private static final double PARKED_M = 595 * 25.0;

    private static FollowResult on(double alongM) {
        return new FollowResult(FollowState.ON_ROUTE, 0.0, alongM, null);
    }

    private static Route northbound(double length, String road) {
        return northbound(32.70, -96.80, length, road);
    }

    /** A straight route north, a point every 100 m, driven at 30 m/s, one road the whole way. */
    private static Route northbound(double lat, double lon, double length, String road) {
        int n = (int) Math.round(length / 100.0) + 1;
        double[] lats = new double[n];
        double[] lons = new double[n];
        for (int i = 0; i < n; i++) {
            double[] q = move(lat, lon, 0.0, i * 100.0);
            lats[i] = q[0];
            lons[i] = q[1];
        }
        return timed(lats, lons, 30.0, -1, road);
    }

    private static Route timed(double[] lats, double[] lons, double speed, int seconds, String road) {
        float[] times = new float[lats.length];
        double metres = 0.0;
        for (int i = 1; i < lats.length; i++) {
            metres += RouteFollower.Companion.distance(lats[i - 1], lons[i - 1], lats[i], lons[i]);
            times[i] = (float) (metres / speed);
        }
        int total = seconds >= 0 ? seconds : Math.round(times[times.length - 1]);
        return new Route(lats, lons, (int) Math.round(metres), total, new int[0], new int[0], new int[0], times,
                new int[] {0}, new String[] {road});
    }

    /**
     * A way round: [look]'s route ahead up to [splitM] metres along the followed route, then a
     * line 1 km east of it to near its end, then its last point; [seconds] as BRouter's total.
     */
    private static Route aside(Look look, double splitM, int seconds) {
        Route ahead = look.ahead;
        double[] along = TrafficArea.INSTANCE.metres(ahead);
        double split = splitM - look.aheadAlongM;
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < ahead.getPoints() && along[i] <= split + 1e-6; i++) {
            points.add(new double[] {ahead.getLatitudes()[i], ahead.getLongitudes()[i]});
        }
        for (double m = split + 200.0; m < along[along.length - 1] - 300.0; m += 100.0) {
            double[] q = at(ahead, along, m);
            points.add(move(q[0], q[1], 90.0, 1_000.0));
        }
        points.add(new double[] {ahead.getLatitudes()[ahead.getPoints() - 1], ahead.getLongitudes()[ahead.getPoints() - 1]});
        double[] lats = new double[points.size()];
        double[] lons = new double[points.size()];
        for (int k = 0; k < points.size(); k++) {
            lats[k] = points.get(k)[0];
            lons[k] = points.get(k)[1];
        }
        return timed(lats, lons, 30.0, seconds, "Parallel Rd");
    }

    /** A HERE-shaped line along [route] from [from] to [to] metres, a point every 20 m, [lateral] metres to the right. */
    private static double[][] laid(Route route, double[] along, double from, double to, double lateral) {
        int n = (int) Math.ceil((to - from) / 20.0) + 1;
        double[][] p = new double[2][n];
        for (int i = 0; i < n; i++) {
            double m = Math.min(from + i * 20.0, to);
            double[] q = at(route, along, m);
            double[] a = at(route, along, Math.max(0.0, m - 5.0));
            double[] b = at(route, along, Math.min(along[along.length - 1], m + 5.0));
            double[] r = move(q[0], q[1], RouteFollower.Companion.bearing(a[0], a[1], b[0], b[1]) + 90.0, lateral);
            p[0][i] = Math.round(r[0] * 1e5) / 1e5;
            p[1][i] = Math.round(r[1] * 1e5) / 1e5;
        }
        return p;
    }

    private static double[] move(double lat, double lon, double bearing, double metres) {
        double b = Math.toRadians(bearing);
        return new double[] {lat + metres * Math.cos(b) / M, lon + metres * Math.sin(b) / (M * Math.cos(Math.toRadians(lat)))};
    }

    private static double[] at(Route route, double[] along, double m) {
        int i = segment(along, m);
        double length = along[i + 1] - along[i];
        double t = length > 0.0 ? Math.max(0.0, Math.min(1.0, (m - along[i]) / length)) : 0.0;
        return new double[] {
            route.getLatitudes()[i] + t * (route.getLatitudes()[i + 1] - route.getLatitudes()[i]),
            route.getLongitudes()[i] + t * (route.getLongitudes()[i + 1] - route.getLongitudes()[i]),
        };
    }

    private static double seconds(Route route, double[] along, double m) {
        int i = segment(along, m);
        double length = along[i + 1] - along[i];
        double t = length > 0.0 ? Math.max(0.0, Math.min(1.0, (m - along[i]) / length)) : 0.0;
        float[] s = route.getPointSeconds();
        return s[i] + t * (s[i + 1] - s[i]);
    }

    private static int segment(double[] along, double m) {
        int i = 0;
        while (i < along.length - 2 && along[i + 1] <= m) i++;
        return i;
    }

    private static double fromRoute(Route route, double lat, double lon) {
        double best = Double.MAX_VALUE;
        double sx = M * Math.cos(Math.toRadians(lat));
        for (int i = 0; i + 1 < route.getPoints(); i++) {
            double ax = (route.getLongitudes()[i] - lon) * sx;
            double ay = (route.getLatitudes()[i] - lat) * M;
            double dx = (route.getLongitudes()[i + 1] - route.getLongitudes()[i]) * sx;
            double dy = (route.getLatitudes()[i + 1] - route.getLatitudes()[i]) * M;
            double lengthSq = dx * dx + dy * dy;
            double t = lengthSq > 0.0 ? Math.max(0.0, Math.min(1.0, -(ax * dx + ay * dy) / lengthSq)) : 0.0;
            best = Math.min(best, Math.hypot(ax + t * dx, ay + t * dy));
        }
        return best;
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
