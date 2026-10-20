// The projected test scene's rules on a desktop JVM: layout, hit testing, tap counting,
// and the session token. DemoScene is pure Kotlin with no Android API, so the same class
// the app draws is checked here. It proves nothing about rendering or encoding.
import java.util.List;
import com.jw6ventures.headlink.DemoScene;
import com.jw6ventures.headlink.SceneLayout;

public final class DemoSceneTest {
    private static int passed;

    public static void main(String[] args) {
        layoutMatchesTheSharedContract();
        tapsCountOnlyWhenPressAndReleaseShareAButton();
        keysAndRestrictionsShowInTheScene();
        calibrationRecordsTheRawCoordinateOfEachTargetPress();
        tokensComeFromTheSessionUuid();
        System.out.println("jni-host-test: ok " + passed + " scene checks");
    }

    /** The layout the lab receiver samples: header, margin, 2x2 grid, clean patches. */
    private static void layoutMatchesTheSharedContract() {
        SceneLayout layout = new SceneLayout(0, 0, 800, 480);
        check(layout.getHeader() == 96, "header is a fifth of the height");
        check(layout.getMargin() == 20, "margin is a fortieth of the width");
        check(layout.getButtonWidth() == 370 && layout.getButtonHeight() == 162, "button size");
        check(layout.centerX(0) == 205 && layout.centerY(0) == 197, "button 1 center");
        check(layout.centerX(3) == 595 && layout.centerY(3) == 379, "button 4 center");
        check(layout.samplePoint(0).getFirst() == 112 && layout.samplePoint(0).getSecond() == 237,
                "button 1 sample point");
        check(layout.hit(205, 197) == 0 && layout.hit(595, 379) == 3, "centers hit their buttons");
        check(layout.hit(205, 100) == -1 && layout.hit(-5, 197) == -1, "header and outside miss");
        // Sample points are inside their own button and away from its edges.
        for (int index = 0; index < SceneLayout.BUTTONS; index++) {
            check(layout.hit(layout.samplePoint(index).getFirst(), layout.samplePoint(index).getSecond()) == index,
                    "sample point " + index + " is inside its button");
        }
        // A Ford-like content rectangle (margins 74x0) keeps the same rules.
        SceneLayout ford = new SceneLayout(37, 0, 726, 480);
        check(ford.buttonX(0) == 55 && ford.buttonY(0) == 114, "inset content shifts the grid");
        check(ford.hit(ford.centerX(1), ford.centerY(1)) == 1, "inset centers hit");
        // Ford's 1920x1080 configuration, the one this source now prefers: the content
        // rectangle starts 99 rows down rather than 37 columns right, so the grid is
        // offset vertically, which no configuration exercised before.
        SceneLayout tall = new SceneLayout(0, 99, 1334, 882);
        check(tall.buttonX(0) == 33 && tall.buttonY(0) == 308, "a content y offset shifts the grid down");
        check(tall.hit(tall.centerX(2), tall.centerY(2)) == 2, "offset centers hit");
        check(tall.hit(tall.centerX(0), 99) == -1, "the content top is still header, not a button");
        for (int index = 0; index < SceneLayout.BUTTONS; index++) {
            check(tall.hit(tall.samplePoint(index).getFirst(), tall.samplePoint(index).getSecond()) == index,
                    "1080p sample point " + index + " is inside its button");
        }
    }

    private static void tapsCountOnlyWhenPressAndReleaseShareAButton() {
        SceneLayout layout = new SceneLayout(0, 0, 800, 480);
        DemoScene scene = new DemoScene(layout, "123456");
        scene.touch("down", layout.centerX(0), layout.centerY(0), 1, 2);
        DemoScene.Snapshot pressed = scene.nextFrame(0);
        check(pressed.getPressed() == 0, "press marks the button");
        check(pressed.getTaps().equals(List.of(0, 0, 0, 0)), "press alone does not count");
        scene.touch("up", layout.centerX(0), layout.centerY(0), 1, 2);
        check(scene.nextFrame(10).getTaps().equals(List.of(1, 0, 0, 0)), "press and release count once");

        // Release on another button, release outside, and a cancelled press count nothing.
        scene.touch("down", layout.centerX(1), layout.centerY(1), 0, 0);
        scene.touch("up", layout.centerX(2), layout.centerY(2), 0, 0);
        scene.touch("down", layout.centerX(3), layout.centerY(3), 0, 0);
        scene.touch("cancel", layout.centerX(3), layout.centerY(3), 0, 0);
        scene.touch("up", layout.centerX(3), layout.centerY(3), 0, 0);
        scene.touch("down", layout.centerX(2), layout.centerY(2), 0, 0);
        scene.touch("up", 5, 5, 0, 0);
        DemoScene.Snapshot after = scene.nextFrame(20);
        check(after.getTaps().equals(List.of(1, 0, 0, 0)), "only matching press and release count: " + after.getTaps());
        check(after.getFrame() == 3, "frames advance with each drawn frame");
        check(after.getLastTouchX() == 5 && after.getLastRawX() == 0, "last touch is kept for the crosshair");
    }

    private static void keysAndRestrictionsShowInTheScene() {
        SceneLayout layout = new SceneLayout(0, 0, 800, 480);
        DemoScene scene = new DemoScene(layout, "000001");
        scene.key(84, true);
        scene.key(84, false);
        DemoScene.Snapshot snapshot = scene.nextFrame(0);
        check(snapshot.getTaps().stream().mapToInt(Integer::intValue).sum() == 1, "a key press taps one button");
        check(snapshot.getLastKey() == 84, "the last keycode is shown");
        scene.restriction("31", "true");
        check(scene.nextFrame(0).getRestriction().equals("driving=31 night=true"), "restriction text");
        String report = scene.report();
        check(report.contains("scene_token=000001"), "report has the token");
        check(report.contains("scene_keys=2"), "report counts key events: " + report);
        check(report.contains("centers_samples=205:197/112:237"), "report has the layout");
        check(report.contains("scene_calibration=1:target=205,197:press=-:release=-"), "unpressed targets read -");

        // P5.1: a transport button changes what is playing, not the scene's own state, so
        // the scene only records that it arrived. Drawing it is what makes the press
        // visible on the factory display (G6).
        check(scene.nextFrame(0).getTransport() == null, "no transport line until a button arrives");
        scene.transport("play-pause");
        scene.transport("next");
        check(scene.nextFrame(0).getTransport().equals("next x2"), "the last transport button and count");
        check(scene.nextFrame(0).getTaps().stream().mapToInt(Integer::intValue).sum() == 1,
                "a transport button taps no scene button");
        String withTransport = scene.report();
        check(withTransport.contains("scene_transport=2 scene_last_transport=next"),
                "report counts transport buttons: " + withTransport);

        // The now-playing line is drawn so a head unit that ignores the media_info
        // channel still shows what is being published (G6).
        // Null until the integration publishes, so an absent line means it is off.
        check(scene.nextFrame(0).getNowPlaying() == null, "no line until the integration publishes");
        scene.nowPlaying(true, "playing", "Example Player", "Title", "Artist");
        // Escaped so this source file stays US-ASCII, as javac is invoked for it.
        check(scene.nextFrame(0).getNowPlaying().equals("playing \u00b7 Title \u2014 Artist"),
                "state, title and artist");
        // Granted but silent, and not granted at all, are different situations and the
        // only one an operator can act on from the vehicle must say so on the screen.
        scene.nowPlaying(true, "stopped", "", "", "");
        check(scene.nextFrame(0).getNowPlaying().equals(DemoScene.NO_MEDIA), "granted but nothing playing");
        scene.nowPlaying(false, "stopped", "", "", "");
        check(scene.nextFrame(0).getNowPlaying().equals(DemoScene.NO_ACCESS), "missing grant says so");
        check(!DemoScene.NO_ACCESS.equals(DemoScene.NO_MEDIA), "the two cases read differently");
        // A named app with no readable track still says which app.
        scene.nowPlaying(true, "playing", "Example Player", "", "");
        check(scene.nextFrame(0).getNowPlaying().contains("Example Player"), "app name without a track");
        // Track text is drawn but never reported (spec 10): it is listening history.
        String reported = scene.report();
        check(!reported.contains("Title") && !reported.contains("Example Player"),
                "the report carries no track or app name: " + reported);
    }

    /**
     * The calibration pairs a target the scene drew at a known frame pixel with the raw
     * coordinates the head unit reported for it, so one drive gives the scale and offset of
     * the head unit's grid instead of a guess from a photograph. The press is where the
     * operator aimed; the release is where they settled the crosshair onto the cross, which
     * is the measurement free of their aim.
     */
    private static void calibrationRecordsTheRawCoordinateOfEachTargetPress() {
        SceneLayout ford = new SceneLayout(37, 0, 726, 480);
        DemoScene scene = new DemoScene(ford, "123456");
        // A press aimed at button 1's target, reported 20 left and 6 up of it, then dragged
        // onto the target before lifting.
        scene.touch("down", ford.centerX(0) - 20, ford.centerY(0) - 6, 203, 190);
        scene.touch("up", ford.centerX(0), ford.centerY(0), 223, 196);
        String report = scene.report();
        check(report.contains("scene_calibration=1:target=223,196:press=203,190:release=223,196"),
                "press and release are both recorded: " + report);
        check(report.contains("2:target=577,196:press=-:release=-"), "untouched targets stay empty: " + report);
        // A press that misses every button records nothing.
        scene.touch("down", 5, 5, 1, 1);
        check(scene.report().contains("3:target=223,379:press=-:release=-"), "a press outside a button is not a pair");
        // A release that drifted out of the button is not a settled crosshair, so only the
        // press is kept; the newest of each replaces the previous one.
        scene.touch("down", ford.centerX(0), ford.centerY(0), 230, 200);
        scene.touch("up", 5, 5, 1, 1);
        check(scene.report().contains("scene_calibration=1:target=223,196:press=230,200:release=223,196"),
                "the newest press wins and a drifted release is not recorded: " + scene.report());
    }

    private static void tokensComeFromTheSessionUuid() {
        String token = DemoScene.Companion.token("c2407cff-5104-428c-844d-ae6b79683b4a");
        check(token.length() == 6 && token.chars().allMatch(Character::isDigit), "six digits: " + token);
        check(!token.equals(DemoScene.Companion.token("0f6b3a52-9a0c-4c1b-8d7e-2f7a1b3c4d5e")),
                "different sessions show different tokens");
        check(DemoScene.Companion.token("").equals("000000"), "an empty uuid still gives six digits");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
        passed++;
    }
}
