// Maps & routing -> Route preferences on a desktop JVM: the options' profile parameters,
// repair, labels and time scaling, the Learn my pace tracker over synthetic drives, and
// the stored keys. RouteOptions and PaceTracker are pure Kotlin; the switches and spinners
// around them need a device.
//
// With a directory of routing tiles as the first argument it also routes for real: with
// W100_N30.rd5 (Austin) a toll road the default route takes and Avoid toll roads leaves,
// and with that or W75_N40.rd5 that a route's switches reach BRouter every time, not just
// the first time a profile is parsed.
import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import com.jw6ventures.headlink.PaceTracker;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteOptions;
import com.jw6ventures.headlink.RoutePlanner;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.Router;
import com.jw6ventures.headlink.RoutingData;
import com.jw6ventures.headlink.SavedSettings;

public final class RouteOptionsTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        theDefaultsAreTheTexasOnes();
        everySwitchReachesTheProfile();
        anOptionNoLongerOfferedIsRepaired();
        onlyRouteChangesRecompute();
        theLabelsReadInBothUnits();
        theTimeAdjustmentScalesEveryTime();
        theOptionsAreStoredWhole();
        paceIsLearnedOnlyWhileMovingOnTheRoute();
        paceIsHeldToItsLimits();
        String segments = args.length > 0 ? args[0] : "";
        String real = "real routes skipped (no tile directory given)";
        if (!segments.isEmpty()) {
            File work = Files.createTempDirectory("route-options-test").toFile();
            File profile = RoutingData.INSTANCE.installProfile(new File(work, "profiles"));
            Router router = new Router(new File(segments), profile, 64);
            if (new File(segments, "W100_N30.rd5").isFile()) {
                avoidTollsLeavesSh130(router);
                switchesReachBRouterEveryTime(router, 30.4394, -97.6200, 30.0600, -97.7000);
                real = "real routes over W100_N30";
                if (new File(segments, "W105_N30.rd5").isFile()) {
                    aLongTripTheEngineCannotFinishIsTimedLower(router);
                    real += " and Austin to Lubbock";
                }
            } else if (new File(segments, "W75_N40.rd5").isFile()) {
                switchesReachBRouterEveryTime(router, 42.4242, -71.2121, 42.3551, -71.0656);
                real = "real routes over W75_N40";
            }
        }
        System.out.println("jni-host-test: ok " + passed + " route preference checks; " + real);
    }

    private static RouteOptions options(boolean highways, boolean tolls, boolean unpaved, boolean towns, boolean tracks,
            int kmh, int percent, boolean pace) {
        return new RouteOptions(highways, tolls, unpaved, towns, tracks, kmh, percent, pace, true, false);
    }

    private static void theDefaultsAreTheTexasOnes() {
        RouteOptions d = new RouteOptions();
        check(!d.getAvoidHighways() && !d.getAvoidTolls() && !d.getAvoidUnpaved() && !d.getBypassTowns()
                && !d.getAllowTracks(), "no road is avoided or added by default");
        check(d.getTopSpeedKmh() == 121, "top speed 75 mph");
        check(d.getEtaPercent() == 0 && d.getTimeFactor() == 1.0, "times as estimated");
        check(d.getLearnPace(), "learn my pace on");
        check(RouteOptions.Companion.getTOP_SPEEDS_KMH().contains(90), "the profile's own 90 km/h is still offered");
        check(new SavedSettings().getRouting().equals(d), "a first launch saves the model's defaults");
    }

    private static void everySwitchReachesTheProfile() {
        Map<String, String> off = new RouteOptions().profileParameters();
        check(off.size() == 6, "six parameters, every time: " + off);
        check("0".equals(off.get("avoid_motorways")) && "0".equals(off.get("avoid_toll"))
                && "0".equals(off.get("avoid_unpaved")) && "0".equals(off.get("consider_town"))
                && "0".equals(off.get("use_offroad")), "a switch off is sent as 0, never left out");
        check("121".equals(off.get("vmax")), "vmax in km/h");
        Map<String, String> on = options(true, true, true, true, true, 90, 0, true).profileParameters();
        check("1".equals(on.get("avoid_motorways")) && "1".equals(on.get("avoid_toll"))
                && "1".equals(on.get("avoid_unpaved")) && "1".equals(on.get("consider_town"))
                && "1".equals(on.get("use_offroad")) && "90".equals(on.get("vmax")), "every switch on: " + on);
        for (String value : on.values()) Float.parseFloat(value);
        check(true, "every value parses as the number BRouter reads it as");
    }

    private static void anOptionNoLongerOfferedIsRepaired() {
        RouteOptions odd = options(true, false, false, false, false, 200, 7, false).repaired();
        check(odd.getTopSpeedKmh() == 121 && odd.getEtaPercent() == 0, "an unknown speed or percentage reads as the default");
        check(odd.getAvoidHighways() && !odd.getLearnPace(), "and the rest is kept");
    }

    private static void onlyRouteChangesRecompute() {
        RouteOptions d = new RouteOptions();
        check(!d.routeDiffers(options(false, false, false, false, false, 121, 0, false)), "learn my pace alone recomputes nothing");
        check(d.routeDiffers(options(false, true, false, false, false, 121, 0, true)), "a road switch recomputes");
        check(d.routeDiffers(options(false, false, false, false, false, 105, 0, true)), "a top speed recomputes");
        check(d.routeDiffers(options(false, false, false, false, false, 121, 10, true)), "a time adjustment recomputes");
        check(!d.routeDiffers(d), "the same options recompute nothing");
        check(!d.routeDiffers(new RouteOptions(false, false, false, false, false, 121, 0, true, false, false)),
                "nor does hiding the map's alternatives");
        check(d.getShowAlternatives(), "the map's alternatives are on by default");
    }

    private static void theLabelsReadInBothUnits() {
        check("75 mph \u00b7 121 km/h".equals(RouteOptions.Companion.topSpeedLabel(121)), "121 km/h is 75 mph");
        check("56 mph \u00b7 90 km/h".equals(RouteOptions.Companion.topSpeedLabel(90)), "90 km/h is 56 mph");
        java.util.Set<String> mph = new java.util.HashSet<>();
        for (int kmh : RouteOptions.Companion.getTOP_SPEEDS_KMH()) mph.add(RouteOptions.Companion.topSpeedLabel(kmh));
        check(mph.size() == RouteOptions.Companion.getTOP_SPEEDS_KMH().size(), "every speed has its own label");
        check("as estimated".equals(RouteOptions.Companion.etaLabel(0)) && "+10% longer".equals(RouteOptions.Companion.etaLabel(10))
                && "5% shorter".equals(RouteOptions.Companion.etaLabel(-5)), "percentages read as words");
        check(RouteOptions.Companion.getETA_PERCENTS().contains(0), "as estimated is offered");
    }

    private static void theTimeAdjustmentScalesEveryTime() {
        Route route = new Route(new double[] {0, 0, 0}, new double[] {0, 0.001, 0.002}, 222, 100,
                new int[] {1}, new int[] {0}, new int[] {1}, new float[] {0f, 40f, 100f});
        check(route.withTimeScale(1.0) == route, "a factor of 1 is the same route");
        Route timed = new Route(new double[] {0, 0}, new double[] {0, 0.001}, 111, 10, new int[0], new int[0], new int[0],
                new float[] {0f, 10f}, new int[0], new String[0], 105);
        check(timed.withTimeScale(1.1).getTopSpeedKmh() == 105 && timed.withRoads(new int[0], new String[0]).getTopSpeedKmh() == 105,
                "the speed a route was timed at survives scaling and naming");
        Route longer = route.withTimeScale(options(false, false, false, false, false, 121, 20, true).getTimeFactor());
        check(longer.getSeconds() == 120, "the total scales: " + longer.getSeconds());
        check(Math.abs(longer.getPointSeconds()[1] - 48f) < 1e-3 && Math.abs(longer.getPointSeconds()[2] - 120f) < 1e-3,
                "and every point's time, so the time to a turn scales too");
        check(longer.getDistanceM() == 222 && longer.getPoints() == 3 && longer.getManeuverIndices()[0] == 1,
                "and nothing else changes");
    }

    private static void theOptionsAreStoredWhole() {
        RouteOptions changed = options(true, true, true, true, true, 105, -10, false);
        Map<String, Object> form = SavedSettings.Companion.fromStored(java.util.Map.of(
                "route_avoid_highways", true, "route_avoid_tolls", true, "route_avoid_unpaved", true,
                "route_bypass_towns", true, "route_allow_tracks", true, "route_top_speed_kmh", 105,
                "route_eta_percent", -10, "route_learn_pace", false, "route_show_alternatives", true)).toStored();
        SavedSettings back = SavedSettings.Companion.fromStored(form);
        check(back.getRouting().equals(changed), "every routing key read and written: " + back.getRouting());
        check(Integer.valueOf(105).equals(form.get("route_top_speed_kmh")) && Integer.valueOf(-10).equals(form.get("route_eta_percent")),
                "speed and percentage stored as numbers, not list positions");
        SavedSettings odd = SavedSettings.Companion.fromStored(java.util.Map.of(
                "route_top_speed_kmh", 300, "route_eta_percent", "ten", "route_avoid_tolls", "yes"));
        check(odd.getRouting().equals(new RouteOptions()), "unknown or mistyped routing values read as the defaults");
        check(back.withVoiceGuidance(false).getRouting().equals(changed), "a voice toggle press leaves the routing options alone");
        SavedSettings osmAnd = SavedSettings.Companion.fromStored(java.util.Map.of("route_osmand", true));
        check(osmAnd.getRouting().getOsmAndRouting() && Boolean.TRUE.equals(osmAnd.toStored().get("route_osmand"))
                && !new RouteOptions().getOsmAndRouting(), "Route with OsmAnd maps is stored, and off by default");
        check(osmAnd.getRouting().report().contains("routing_osmand=true"), "and the report says which router ran");
    }

    /** One fix a second along a route whose estimate falls [perSecond] a second. */
    private static void paceIsLearnedOnlyWhileMovingOnTheRoute() {
        PaceTracker pace = new PaceTracker();
        long now = 1_000_000L;
        // A minute parked at the start with the route up: the estimate does not fall.
        for (int i = 0; i < 60; i++) pace.onFix(1, 3600, now += 1000);
        check(pace.getActualS() == 0.0, "time parked before moving is not counted");
        // Five minutes at 80% of the estimated speed: each second takes 0.8 s off.
        double remaining = 3600;
        for (int i = 0; i < 300; i++) {
            remaining -= 0.8;
            pace.onFix(1, (int) Math.round(remaining), now += 1000);
        }
        check(Math.abs(pace.getActualS() / pace.getEstimatedS() - 1.25) < 0.02,
                "slower than estimated reads as 1.25: " + pace.getActualS() + "/" + pace.getEstimatedS());
        double weight = pace.getEstimatedS() / 600.0;
        check(Math.abs(pace.factor() - (1 + weight * 0.25)) < 0.02, "carried in part until ten minutes: " + pace.factor());
        check(pace.apply(1000) > 1000, "so the time still to go grows");
        // A red light counts: stopped for a minute mid-drive.
        double before = pace.getActualS();
        for (int i = 0; i < 60; i++) pace.onFix(1, (int) Math.round(remaining), now += 1000);
        check(pace.getActualS() - before > 59, "a stop after moving counts in full");
        // Off the route, then a fix gap: neither is counted, and a stretch needs moving again.
        double estimated = pace.getEstimatedS(), actual = pace.getActualS();
        pace.onFix(1, null, now += 1000);
        pace.onFix(1, (int) Math.round(remaining -= 1), now += 60_000);
        pace.onFix(1, (int) Math.round(remaining), now += 45_000);
        check(pace.getEstimatedS() == estimated && pace.getActualS() == actual, "off-route time and a 45 s gap are not counted");
        // A re-route is a new generation: a new stretch, but the pace so far stands.
        pace.onFix(2, 3000, now += 1000);
        check(pace.getActualS() == actual, "a re-route starts a new stretch");
        pace.reset();
        check(pace.factor() == 1.0 && pace.getActualS() == 0.0, "a new destination starts afresh");
    }

    private static void paceIsHeldToItsLimits() {
        PaceTracker fast = new PaceTracker();
        long now = 0;
        double remaining = 7200;
        // A minute and a half of estimate is not enough to go on.
        for (int i = 0; i < 90; i++) fast.onFix(1, (int) Math.round(remaining -= 1), now += 1000);
        check(fast.factor() == 1.0, "under two minutes of estimate the factor is 1");
        for (int i = 0; i < 1200; i++) fast.onFix(1, (int) Math.round(remaining -= 3), now += 1000);
        check(Math.abs(fast.factor() - PaceTracker.MIN_FACTOR) < 1e-9, "three times faster is held at " + PaceTracker.MIN_FACTOR + ": " + fast.factor());
        PaceTracker jam = new PaceTracker();
        remaining = 7200;
        now = 0;
        for (int i = 0; i < 700; i++) jam.onFix(1, (int) Math.round(remaining -= 1), now += 1000);
        for (int i = 0; i < 3000; i++) jam.onFix(1, (int) Math.round(remaining), now += 1000);
        check(Math.abs(jam.factor() - PaceTracker.MAX_FACTOR) < 1e-9, "a long jam is held at " + PaceTracker.MAX_FACTOR + ": " + jam.factor());
        PaceTracker backwards = new PaceTracker();
        backwards.onFix(1, 100, 10_000);
        backwards.onFix(1, 99, 5_000);
        check(backwards.getActualS() == 0.0, "a clock going backwards starts a new stretch");
    }

    private static Route route(Router router, double fromLat, double fromLon, double toLat, double toLon, RouteOptions o) throws Exception {
        return router.compute(new RouteRequest(fromLat, fromLon, toLat, toLon, null, o), RoutePlanner.MAX_RUNNING_MS,
                e -> kotlin.Unit.INSTANCE);
    }

    private static double maxLongitude(Route route) {
        double max = -180;
        for (double lon : route.getLongitudes()) max = Math.max(max, lon);
        return max;
    }

    /**
     * Pflugerville to Mustang Ridge: SH 130, the toll road east of Austin, is the fast way
     * and runs east of -97.59; the free way is I 35 and the roads west of it.
     */
    private static void avoidTollsLeavesSh130(Router router) throws Exception {
        Route plain = route(router, 30.4394, -97.6200, 30.0600, -97.7000, new RouteOptions());
        Route noTolls = route(router, 30.4394, -97.6200, 30.0600, -97.7000,
                options(false, true, false, false, false, 121, 0, true));
        check(maxLongitude(plain) > -97.59, "the default takes SH 130: east to " + maxLongitude(plain));
        check(maxLongitude(noTolls) < -97.60, "avoid toll roads stays west of it: " + maxLongitude(noTolls));
        check(noTolls.getSeconds() > plain.getSeconds(), "and is slower, as the toll road is the fast way");
        Route slow = route(router, 30.4394, -97.6200, 30.0600, -97.7000, options(false, false, false, false, false, 90, 0, true));
        check(slow.getSeconds() > plain.getSeconds() * 1.1, "56 mph times the same trip over 10% longer than 75 mph: "
                + slow.getSeconds() + " s against " + plain.getSeconds() + " s");
        Route later = route(router, 30.4394, -97.6200, 30.0600, -97.7000, options(false, false, false, false, false, 121, 20, true));
        check(Math.abs(later.getSeconds() - plain.getSeconds() * 1.2) <= 1, "+20% is 20% on the same route");
        System.out.println("route-options-test: sh130 default_s=" + plain.getSeconds() + " no_tolls_s=" + noTolls.getSeconds()
                + " at_56mph_s=" + slow.getSeconds());
    }

    /**
     * Austin to Lubbock, 598 km: at 113 km/h and above BRouter's final pass fails with
     * "error re-tracking track"; at 105 it finishes. The route must still come back.
     */
    private static void aLongTripTheEngineCannotFinishIsTimedLower(Router router) throws Exception {
        Route route = route(router, 30.2672, -97.7431, 33.5779, -101.8552, new RouteOptions());
        check(route.getTopSpeedKmh() < 121 && route.getTopSpeedKmh() >= 90,
                "the route comes back, timed at a lower speed: " + route.getTopSpeedKmh());
        check(route.getDistanceM() > 500_000, "and it is the whole trip: " + route.getDistanceM() + " m");
        Route slow = route(router, 30.2672, -97.7431, 33.5779, -101.8552, options(false, false, false, false, false, 90, 0, true));
        check(slow.getTopSpeedKmh() == 90, "the profile's own speed needs no step down");
        System.out.println("route-options-test: lubbock timed_at_kmh=" + route.getTopSpeedKmh() + " seconds=" + route.getSeconds()
                + " at_56mph_s=" + slow.getSeconds());
    }

    /**
     * BRouter keys its parsed profile on a sum of the parameters' hash codes, which cannot
     * tell avoid highways from avoid tolls when one is on and the other off. The same
     * options must give the same route whatever was routed just before.
     */
    private static void switchesReachBRouterEveryTime(Router router, double fromLat, double fromLon, double toLat, double toLon) throws Exception {
        RouteOptions highways = options(true, false, false, false, false, 121, 0, true);
        RouteOptions tolls = options(false, true, false, false, false, 121, 0, true);
        Route tollsFresh = route(router, fromLat, fromLon, toLat, toLon, tolls);
        Route highwaysRoute = route(router, fromLat, fromLon, toLat, toLon, highways);
        Route tollsAfterHighways = route(router, fromLat, fromLon, toLat, toLon, tolls);
        check(highwaysRoute.getSeconds() != tollsFresh.getSeconds(), "the two switches give different routes here: "
                + highwaysRoute.getSeconds() + " s and " + tollsFresh.getSeconds() + " s");
        check(tollsAfterHighways.getSeconds() == tollsFresh.getSeconds()
                && tollsAfterHighways.getDistanceM() == tollsFresh.getDistanceM(),
                "avoid tolls routed straight after avoid highways is still avoid tolls");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
