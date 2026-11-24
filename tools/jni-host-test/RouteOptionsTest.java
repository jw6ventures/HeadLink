// Maps & routing -> Route preferences on a desktop JVM: repair, labels and time scaling,
// the Learn my pace tracker over synthetic drives, and the stored keys, and since 0.11 the
// pace learned across drives (PaceRoad's kinds of road, PaceBook's buckets, fading, text
// form and route times). RouteOptions, PaceTracker and PaceBook are pure Kotlin; the
// switches and spinners around them need a device. How each switch becomes one of OsmAnd's
// profile parameters is OsmAndRoutingTest's (theSettingsAreTheProfilesParameters).
//
// With a directory of OsmAnd maps as the first argument (OBF_DIR) it also routes for real
// with OsmAndRouter: with Us_texas_austin_northamerica.obf a toll road the default route
// takes and Avoid toll roads leaves, and that a route's switches reach the router every
// time, not just the first time a configuration is built.
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.jw6ventures.headlink.ExitSign;
import com.jw6ventures.headlink.OsmAndRouter;
import com.jw6ventures.headlink.PaceBook;
import com.jw6ventures.headlink.PaceRoad;
import com.jw6ventures.headlink.PaceTracker;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteOptions;
import com.jw6ventures.headlink.RoutePlanner;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.SavedSettings;

public final class RouteOptionsTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        theDefaultsAreTheTexasOnes();
        anOptionNoLongerOfferedIsRepaired();
        onlyRouteChangesRecompute();
        theLabelsReadInBothUnits();
        theTimeAdjustmentScalesEveryTime();
        theOptionsAreStoredWhole();
        paceIsLearnedOnlyWhileMovingOnTheRoute();
        aParkedFixWanderingIsNotDriving();
        paceIsHeldToItsLimits();
        roadsAreSortedIntoKinds();
        thePaceIsLearnedAcrossDrivesByKindAndArea();
        oldDrivingFades();
        theLearnedPaceIsKeptAsText();
        aRouteIsTimedAtTheLearnedPace();
        theTrackerHandsOnTheRoutesOwnFall();
        String dir = args.length > 0 ? args[0] : "";
        String real = "real routes skipped (need OBF_DIR with Us_texas_austin_northamerica.obf)";
        File austin = new File(dir, "Us_texas_austin_northamerica.obf");
        if (!dir.isEmpty() && austin.isFile()) {
            List<OsmAndRouter.MapFile> maps = new ArrayList<>();
            maps.add(OsmAndRouter.MapFile.of(austin));
            File sanAntonio = new File(dir, "Us_texas_san-antonio_northamerica.obf");
            if (sanAntonio.isFile()) maps.add(OsmAndRouter.MapFile.of(sanAntonio));
            OsmAndRouter router = new OsmAndRouter(() -> maps);
            try {
                avoidTollsLeavesSh130(router);
                switchesReachTheRouterEveryTime(router, 30.4394, -97.6200, 30.0600, -97.7000);
            } finally {
                router.close();
            }
            real = "real routes over " + maps.size() + " OsmAnd map(s) round Austin";
        }
        System.out.println("jni-host-test: ok " + passed + " route preference checks; " + real);
    }

    private static RouteOptions options(boolean highways, boolean tolls, boolean unpaved, boolean towns, boolean tracks,
            int kmh, int percent, boolean pace) {
        return new RouteOptions(highways, tolls, unpaved, towns, tracks, kmh, percent, pace, true);
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
        check(!d.routeDiffers(new RouteOptions(false, false, false, false, false, 121, 0, true, false)),
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
        check(timed.withTimeScale(1.1).getTopSpeedKmh() == 105, "the speed a route was timed at survives scaling");
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

    /**
     * 2026-11-19: parked with the route up, the position wandered 4 s along the route and
     * the next 417 parked seconds were counted as driving (guidance_pace_estimated_s=4,
     * guidance_pace_actual_s=421). The drive that followed, a little faster than the
     * estimate, then showed close to 1.5 times the time still to go.
     */
    private static void aParkedFixWanderingIsNotDriving() {
        PaceTracker pace = new PaceTracker();
        long now = 0;
        int remaining = 3354;
        pace.onFix(1, remaining, now += 1000);
        // Seven minutes parked: the fix drifts forward and back by a few seconds' worth.
        int[] drift = {0, -2, -4, -1, 0, 2, -3, -4, -1, 1};
        for (int i = 0; i < 420; i++) pace.onFix(1, remaining + drift[i % drift.length], now += 1000);
        check(pace.getActualS() == 0.0 && pace.getEstimatedS() == 0.0, "a parked fix wandering a few seconds is not driving: "
            + pace.getEstimatedS() + "/" + pace.getActualS());
        // Then ten minutes driving 30% faster than the estimate.
        double left = remaining;
        for (int i = 0; i < 600; i++) {
            left -= 1.3;
            pace.onFix(1, (int) Math.round(left), now += 1000);
        }
        check(pace.factor() < 1.0, "a drive faster than estimated shows less time, not more: " + pace.factor());
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

    private static final PaceRoad R = PaceRoad.INSTANCE;
    /** Austin, and Hico (about 110 miles north-west): different grid squares. */
    private static final double AUSTIN_LAT = 30.27, AUSTIN_LON = -97.74, HICO_LAT = 31.98, HICO_LON = -98.03;

    private static void roadsAreSortedIntoKinds() {
        check(R.roadOf("motorway") == PaceRoad.HIGHWAY && R.roadOf("trunk") == PaceRoad.HIGHWAY, "motorway and trunk are highways");
        check(R.roadOf("primary") == PaceRoad.MAJOR, "primary is a major road");
        check(R.roadOf("secondary") == PaceRoad.MINOR && R.roadOf("tertiary") == PaceRoad.MINOR, "secondary and tertiary are minor roads");
        check(R.roadOf("residential") == PaceRoad.LOCAL && R.roadOf("service") == PaceRoad.LOCAL, "streets are local");
        check(R.roadOf("motorway_link") == PaceRoad.RAMP && R.roadOf("primary_link") == PaceRoad.RAMP, "links are ramps");
        check(R.roadOf(null) == PaceRoad.UNKNOWN && R.roadOf("busway") == PaceRoad.UNKNOWN, "anything else is not known");
        check(!R.countsAsJunction("service") && !R.countsAsJunction("track") && R.countsAsJunction("residential"),
                "driveways and tracks are not junctions");
        byte code = R.code(PaceRoad.MINOR, PaceRoad.RURAL);
        check(R.road(code) == PaceRoad.MINOR && R.density(code) == PaceRoad.RURAL && R.valid(code), "a code packs both");
        check(!R.valid((byte) 99), "and a strange one is not valid");
        // Two kilometres with a junction every 100 m, and with one every kilometre.
        double[] along = new double[21];
        boolean[] grid = new boolean[21];
        boolean[] country = new boolean[21];
        for (int i = 0; i < 21; i++) {
            along[i] = i * 100.0;
            grid[i] = true;
            country[i] = i % 10 == 5;
        }
        int[] urban = R.densities(along, grid);
        int[] rural = R.densities(along, country);
        check(urban[10] == PaceRoad.URBAN, "a junction every block is urban: " + urban[10]);
        check(rural[10] == PaceRoad.RURAL, "one a kilometre is rural: " + rural[10]);
        boolean[] suburb = new boolean[21];
        for (int i = 0; i < 21; i++) suburb[i] = i % 3 == 0;
        check(R.densities(along, suburb)[10] == PaceRoad.SUBURBAN, "one every 300 m is suburban");
        check(R.densities(new double[] {0, 100}, new boolean[] {true, true})[0] == PaceRoad.DENSITY_UNKNOWN,
                "a route too short says nothing");
        check(!R.cell(AUSTIN_LAT, AUSTIN_LON).equals(R.cell(HICO_LAT, HICO_LON)), "Austin and Hico are different squares");
        check(R.cell(AUSTIN_LAT, AUSTIN_LON).equals(R.cell(AUSTIN_LAT + 0.05, AUSTIN_LON + 0.05)), "and nearby Austin is the same one");
    }

    /** [minutes] of driving taking [ratio] times the estimate, one minute at a time. */
    private static void drive(PaceBook book, Byte code, double lat, double lon, int minutes, double ratio) {
        for (int i = 0; i < minutes; i++) book.add(code, lat, lon, 60.0, 60.0 * ratio);
    }

    private static void thePaceIsLearnedAcrossDrivesByKindAndArea() {
        PaceBook book = new PaceBook();
        Byte ruralMinor = R.code(PaceRoad.MINOR, PaceRoad.RURAL);
        Byte urbanMinor = R.code(PaceRoad.MINOR, PaceRoad.URBAN);
        Byte highway = R.code(PaceRoad.HIGHWAY, PaceRoad.RURAL);
        check(book.factor(ruralMinor, HICO_LAT, HICO_LON, 1.1) == 1.1, "nothing learned is the time adjustment");
        drive(book, ruralMinor, HICO_LAT, HICO_LON, 5, 0.8);
        check(book.factor(ruralMinor, HICO_LAT, HICO_LON, 1.0) == 1.0, "five minutes is not enough to go on");
        // Two hours on rural FM roads near Hico at 0.85, and two hours in Austin's grid at 1.2.
        drive(book, ruralMinor, HICO_LAT, HICO_LON, 115, 0.85);
        drive(book, urbanMinor, AUSTIN_LAT, AUSTIN_LON, 120, 1.2);
        double hico = book.factor(ruralMinor, HICO_LAT, HICO_LON, 1.0);
        double austin = book.factor(urbanMinor, AUSTIN_LAT, AUSTIN_LON, 1.0);
        check(Math.abs(hico - 0.85) < 0.02, "rural FM roads near Hico are learned as faster: " + hico);
        check(Math.abs(austin - 1.2) < 0.02, "the same class of road in Austin as slower: " + austin);
        // A rural minor road somewhere never driven: no square of its own, so the rural one.
        double elsewhere = book.factor(ruralMinor, 33.0, -101.0, 1.0);
        check(Math.abs(elsewhere - 0.85) < 0.05, "a rural road never driven gets the rural pace: " + elsewhere);
        // A rural minor road inside Austin's square: the square's own pace for minor roads wins.
        double ruralInAustin = book.factor(ruralMinor, AUSTIN_LAT, AUSTIN_LON, 1.0);
        check(ruralInAustin > 1.1, "a square driven for an hour or more has the last word: " + ruralInAustin);
        // A highway never driven: the overall pace, between the two.
        double never = book.factor(highway, HICO_LAT, HICO_LON, 1.0);
        check(never > 0.9 && never < 1.15, "a kind of road never driven gets the overall pace: " + never);
        check(book.factor(null, HICO_LAT, HICO_LON, 1.0) == never, "and so does a stretch of unknown kind");
        PaceBook jam = new PaceBook();
        drive(jam, ruralMinor, HICO_LAT, HICO_LON, 120, 3.0);
        check(Math.abs(jam.factor(ruralMinor, HICO_LAT, HICO_LON, 1.0) - PaceTracker.MAX_FACTOR) < 1e-9, "held to its limits");
        check(book.report().contains("pace_learned_squares=2") && !book.report().contains(","), "the report counts squares and names none");
    }

    private static void oldDrivingFades() {
        PaceBook book = new PaceBook();
        Byte code = R.code(PaceRoad.HIGHWAY, PaceRoad.RURAL);
        drive(book, code, HICO_LAT, HICO_LON, 120, 1.4);
        // Then ten hours at the estimate: the old slow drives have faded away.
        drive(book, code, HICO_LAT, HICO_LON, 600, 1.0);
        double f = book.factor(code, HICO_LAT, HICO_LON, 1.0);
        check(f < 1.05, "recent driving counts more than old: " + f);
    }

    private static void theLearnedPaceIsKeptAsText() {
        PaceBook book = new PaceBook();
        Byte code = R.code(PaceRoad.MAJOR, PaceRoad.SUBURBAN);
        drive(book, code, AUSTIN_LAT, AUSTIN_LON, 90, 1.25);
        check(book.getDirty(), "learning marks the book to save");
        String text = book.encode();
        check(!book.getDirty(), "and saving clears the mark");
        PaceBook back = new PaceBook();
        back.decode(text + "nonsense line\ng9 x y\n");
        check(back.getSize() == book.getSize(), "every bucket reads back, a bad line is skipped: " + back.getSize());
        check(Math.abs(back.factor(code, AUSTIN_LAT, AUSTIN_LON, 1.0) - book.factor(code, AUSTIN_LAT, AUSTIN_LON, 1.0)) < 1e-3,
                "with the same pace");
        PaceBook strange = new PaceBook();
        strange.decode("something else 9\nall 100 100\n");
        check(strange.getSize() == 0, "a file of another kind is not read");
        back.clear();
        check(back.getSize() == 0 && back.getDirty(), "forgetting empties it, to be saved");
    }

    private static void aRouteIsTimedAtTheLearnedPace() {
        Byte slow = R.code(PaceRoad.LOCAL, PaceRoad.URBAN);
        Byte fast = R.code(PaceRoad.HIGHWAY, PaceRoad.RURAL);
        byte[] classes = {slow, fast, fast, fast};
        // 100 s on a city street, then 200 s of highway, timed with a 10% time adjustment.
        Route route = new Route(new double[] {AUSTIN_LAT, AUSTIN_LAT + 0.01, AUSTIN_LAT + 0.02, AUSTIN_LAT + 0.03},
                new double[] {AUSTIN_LON, AUSTIN_LON, AUSTIN_LON, AUSTIN_LON}, 3336, 300, new int[0], new int[0], new int[0],
                new float[] {0f, 100f, 200f, 300f}, new int[0], new String[0], 121, null, 0, false, new ExitSign[0],
                classes, 1.0).withTimeScale(1.1);
        check(Math.abs(route.getTimeScale() - 1.1) < 1e-9 && route.getPaceClasses().length == 4, "the route keeps its kinds and scale");
        PaceBook book = new PaceBook();
        float[] none = book.times(route);
        check(Math.abs(none[3] - 330f) < 0.1, "with nothing learned, the route's own times: " + none[3]);
        drive(book, slow, AUSTIN_LAT, AUSTIN_LON, 120, 1.3);
        drive(book, fast, AUSTIN_LAT, AUSTIN_LON, 120, 0.9);
        float[] learned = book.times(route);
        double street = learned[1], highwayPart = learned[3] - learned[1];
        check(Math.abs(street - 130) < 3, "the street at the street's pace: " + street);
        check(Math.abs(highwayPart - 180) < 4, "the highway at the highway's pace: " + highwayPart);
        Route remainder = route.remainder(1);
        check(remainder.getPaceClasses().length == 3 && remainder.getPaceClasses()[0] == fast, "what is still ahead keeps its kinds");
        check(book.times(new Route(new double[] {0, 0}, new double[] {0, 0.001}, 111, 10, new int[0], new int[0])) == null,
                "a route with no point times has none at the learned pace");
    }

    private static void theTrackerHandsOnTheRoutesOwnFall() {
        PaceTracker pace = new PaceTracker();
        long now = 0;
        int learned = 2000, raw = 1000;
        pace.onFix(1, learned, now += 1000, raw);
        // Under way: the learned time falls 2 s a second, the route's own 1 s.
        for (int i = 0; i < 30; i++) pace.onFix(1, learned -= 2, now += 1000, raw -= 1);
        check(pace.getCountedRawS() == 1.0 && pace.getCountedActualS() == 1.0, "the last interval's own fall and time are handed on");
        check(Math.abs(pace.getActualS() / pace.getEstimatedS() - 0.5) < 0.05, "the drive is measured against the learned time");
        pace.onFix(1, null, now += 1000, null);
        check(pace.getCountedActualS() == 0.0, "off the route nothing is handed on");
    }

    private static Route route(OsmAndRouter router, double fromLat, double fromLon, double toLat, double toLon, RouteOptions o) throws Exception {
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
    private static void avoidTollsLeavesSh130(OsmAndRouter router) throws Exception {
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
     * The router builds a configuration per search from the options' parameters. The same
     * options must give the same route whatever was routed just before, and avoid highways
     * and avoid tolls must give different ones here.
     */
    private static void switchesReachTheRouterEveryTime(OsmAndRouter router, double fromLat, double fromLon, double toLat, double toLon) throws Exception {
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
