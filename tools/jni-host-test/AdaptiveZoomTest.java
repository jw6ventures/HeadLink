// Adaptive zoom (docs/dashboard.md, "Adaptive zoom") on a desktop JVM: which speed band's
// level the follow view is at, the waits before zooming out and in, the margin at a band's
// edge, the glide MapControl eases through, that a pinch or +/- holds the zoom until
// recentre, that a panned or fitted map is left alone, and the settings' repair. All pure
// Kotlin; the settings screen's spinners need a device.
import java.util.Arrays;
import java.util.List;
import com.jw6ventures.headlink.AdaptiveZoom;
import com.jw6ventures.headlink.AdaptiveZoomSettings;
import com.jw6ventures.headlink.Box;
import com.jw6ventures.headlink.MapCamera;
import com.jw6ventures.headlink.MapControl;
import com.jw6ventures.headlink.MapOrientation;
import com.jw6ventures.headlink.TouchPoint;
import kotlin.Pair;

public final class AdaptiveZoomTest {
    private static int passed;
    private static final double MPH = 0.44704;
    private static final Pair<Double, Double> HERE = new Pair<>(30.2672, -97.7431);
    private static final Box PANE = new Box(0, 0, 800, 480);

    public static void main(String[] args) {
        theDefaultsAreTheAgreedOnes();
        theFirstSpeedDecidesAtOnce();
        zoomingOutWaitsItsTime();
        zoomingInWaitsLonger();
        aBriefSlowdownChangesNothing();
        theEdgeHasAMargin();
        acceleratingThroughBandsKeepsOneTimer();
        aQuietSourceHoldsTheBand();
        offChangesNothing();
        theGlideEasesInZoomLevels();
        aManualZoomHoldsUntilRecentre();
        aPannedMapIsLeftAlone();
        repairPutsBackWhatNoListOffers();
        System.out.println("jni-host-test: ok " + passed + " adaptive zoom checks");
    }

    private static void theDefaultsAreTheAgreedOnes() {
        AdaptiveZoomSettings d = new AdaptiveZoomSettings();
        check(d.getEnabled(), "on by default");
        check(d.getLevels().equals(Arrays.asList(17, 16, 15, 14)), "17, 16, 15, 14: " + d.getLevels());
        check(d.getZoomOutSeconds() == 3 && d.getZoomInSeconds() == 6 && d.getGlideMillis() == 1500, "3 s out, 6 s in, 1.5 s glide");
        AdaptiveZoomSettings.Companion c = AdaptiveZoomSettings.Companion;
        check(c.getOUT_SECONDS().contains(3) && c.getIN_SECONDS().contains(6) && c.getGLIDE_MILLIS().contains(1500),
                "every default is one the lists offer");
        for (int level : d.getLevels()) check(c.getLEVELS().contains(level), "default level offered: " + level);
        check(!AdaptiveZoomSettings.Companion.getOFF().getEnabled(), "OFF is off");
        check(AdaptiveZoom.Companion.bandOf(0) == 0 && AdaptiveZoom.Companion.bandOf(30 * MPH) == 1
                && AdaptiveZoom.Companion.bandOf(50 * MPH) == 2 && AdaptiveZoom.Companion.bandOf(70 * MPH) == 3,
                "bands: under 20, 20 to 40, 40 to 55, over 55");
    }

    private static void theFirstSpeedDecidesAtOnce() {
        AdaptiveZoom zoom = new AdaptiveZoom(new AdaptiveZoomSettings());
        check(zoom.level(null, 0) == null, "nothing before a speed");
        check(zoom.level(70 * MPH, 0) == 14, "a highway start is at the highway level at once");
    }

    private static void zoomingOutWaitsItsTime() {
        AdaptiveZoom zoom = new AdaptiveZoom(new AdaptiveZoomSettings());
        check(zoom.level(10 * MPH, 0) == 17, "city level");
        check(zoom.level(30 * MPH, 1_000) == 17, "a faster band is not taken at once");
        check(zoom.level(30 * MPH, 3_999) == 17, "nor before three seconds");
        check(zoom.level(30 * MPH, 4_000) == 16, "after three seconds");
        check(zoom.getChanges() == 1, "one change counted");
    }

    private static void zoomingInWaitsLonger() {
        AdaptiveZoom zoom = new AdaptiveZoom(new AdaptiveZoomSettings());
        zoom.level(70 * MPH, 0);
        check(zoom.level(45 * MPH, 1_000) == 14, "slower is held");
        check(zoom.level(45 * MPH, 6_999) == 14, "for six seconds");
        check(zoom.level(45 * MPH, 7_000) == 15, "then taken");
    }

    private static void aBriefSlowdownChangesNothing() {
        AdaptiveZoom zoom = new AdaptiveZoom(new AdaptiveZoomSettings());
        zoom.level(35 * MPH, 0);
        check(zoom.level(0.0, 1_000) == 16, "a red light");
        check(zoom.level(0.0, 5_000) == 16, "four seconds of it");
        check(zoom.level(30 * MPH, 6_000) == 16, "and moving again: no change");
        check(zoom.level(0.0, 7_000) == 16, "the next stop starts its own wait");
        check(zoom.level(0.0, 12_999) == 16 && zoom.level(0.0, 13_000) == 17, "and is taken six seconds on");
    }

    private static void theEdgeHasAMargin() {
        AdaptiveZoom zoom = new AdaptiveZoom(new AdaptiveZoomSettings(true, Arrays.asList(17, 16, 15, 14), 0, 0, 0));
        check(zoom.level(39 * MPH, 0) == 16, "39 mph is in 20 to 40");
        check(zoom.level(41 * MPH, 1) == 16, "41 is not far enough past 40 to leave it");
        check(zoom.level(42.5 * MPH, 2) == 15, "42.5 is");
        check(zoom.level(39 * MPH, 3) == 15, "and 39 is not far enough back to return");
        check(zoom.level(37.5 * MPH, 4) == 16, "37.5 is");
    }

    private static void acceleratingThroughBandsKeepsOneTimer() {
        AdaptiveZoom zoom = new AdaptiveZoom(new AdaptiveZoomSettings());
        zoom.level(0.0, 0);
        zoom.level(30 * MPH, 1_000);
        zoom.level(50 * MPH, 2_500);
        check(zoom.level(65 * MPH, 4_000) == 14, "a merge waits once, from the first faster band");
    }

    private static void aQuietSourceHoldsTheBand() {
        AdaptiveZoom zoom = new AdaptiveZoom(new AdaptiveZoomSettings());
        zoom.level(70 * MPH, 0);
        check(zoom.level(null, 60_000) == 14, "no speed holds what is on show");
    }

    private static void offChangesNothing() {
        AdaptiveZoom zoom = new AdaptiveZoom(AdaptiveZoomSettings.Companion.getOFF());
        check(zoom.level(70 * MPH, 0) == null, "off gives no level");
        MapControl map = new MapControl(MapOrientation.NORTH_UP);
        map.adapt(null, 1500, 0);
        check(map.getScale() == MapControl.DEFAULT_SCALE, "and the map keeps its zoom");
    }

    private static void theGlideEasesInZoomLevels() {
        MapControl map = new MapControl(MapOrientation.NORTH_UP);
        map.adapt(14, 1000, 0);
        check(near(level(map), 16), "the glide starts where the map is: " + level(map));
        map.adapt(14, 1000, 500);
        check(near(level(map), 15), "half way in time is half way in levels: " + level(map));
        map.adapt(14, 1000, 1000);
        check(map.getScale() == Math.pow(2, 14), "and ends exactly on the level");
        map.adapt(14, 1000, 5000);
        check(map.getScale() == Math.pow(2, 14), "and stays");
        map.adapt(17, 0, 6000);
        check(map.getScale() == Math.pow(2, 17), "no glide is a jump");
    }

    private static void aManualZoomHoldsUntilRecentre() {
        MapControl map = new MapControl(MapOrientation.NORTH_UP);
        map.zoomBy(1);
        map.adapt(14, 0, 0);
        check(near(level(map), 17) && map.getAutoZoomPaused(), "+ holds the zoom the driver chose");
        map.recentre();
        map.adapt(14, 0, 1);
        check(near(level(map), 14) && !map.getAutoZoomPaused(), "recentre gives it back");

        MapControl pinched = new MapControl(MapOrientation.NORTH_UP);
        pinched.camera(HERE, null);
        List<TouchPoint> apart = Arrays.asList(new TouchPoint(0, 380, 240, 380, 240), new TouchPoint(1, 420, 240, 420, 240));
        List<TouchPoint> wider = Arrays.asList(new TouchPoint(0, 340, 240, 340, 240), new TouchPoint(1, 460, 240, 460, 240));
        pinched.touch("down", apart.subList(0, 1), PANE, 0);
        pinched.touch("pointer-down", apart, PANE, 10);
        pinched.touch("move", wider, PANE, 20);
        double afterPinch = pinched.getScale();
        pinched.adapt(14, 0, 30);
        check(pinched.getAutoZoomPaused() && pinched.getScale() == afterPinch, "so does a pinch");
        check(pinched.report().contains("auto_zoom_paused=true"), "and the report says so");
    }

    private static void aPannedMapIsLeftAlone() {
        MapControl map = new MapControl(MapOrientation.NORTH_UP);
        MapCamera start = map.camera(HERE, null);
        check(start != null, "a first frame");
        map.touch("down", Arrays.asList(new TouchPoint(0, 400, 240, 400, 240)), PANE, 0);
        map.touch("move", Arrays.asList(new TouchPoint(0, 500, 240, 500, 240)), PANE, 10);
        map.touch("up", Arrays.asList(new TouchPoint(0, 500, 240, 500, 240)), PANE, 20);
        check(!map.getFollowing(), "the drag let go of the car");
        map.adapt(14, 0, 30);
        check(map.getScale() == MapControl.DEFAULT_SCALE, "a panned map keeps its zoom");
        map.recentre();
        map.adapt(14, 0, 40);
        check(map.getScale() == Math.pow(2, 14), "and adapts again once recentred");
    }

    private static void repairPutsBackWhatNoListOffers() {
        AdaptiveZoomSettings odd = new AdaptiveZoomSettings(true, Arrays.asList(30, 16, 2, 14), 4, 7, 1234).repaired();
        check(odd.getLevels().equals(Arrays.asList(17, 16, 15, 14)), "each level no list offers goes back: " + odd.getLevels());
        check(odd.getZoomOutSeconds() == 3 && odd.getZoomInSeconds() == 6 && odd.getGlideMillis() == 1500, "and each time");
        AdaptiveZoomSettings short3 = new AdaptiveZoomSettings(true, Arrays.asList(12, 13, 14), 0, 0, 0).repaired();
        check(short3.getLevels().equals(Arrays.asList(17, 16, 15, 14)), "a list of the wrong length is replaced whole");
        AdaptiveZoomSettings mine = new AdaptiveZoomSettings(false, Arrays.asList(18, 17, 14, 12), 0, 30, 0);
        check(mine.repaired().equals(mine), "every offered choice is kept");
    }

    private static double level(MapControl map) {
        return Math.log(map.getScale()) / Math.log(2);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("adaptive zoom: " + what);
        passed++;
    }
}
