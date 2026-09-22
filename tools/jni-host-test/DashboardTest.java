// The projected dashboard's rules on a desktop JVM: layout geometry, hit testing, the
// sensor scale inference, and what the report is allowed to contain. Dashboard,
// DashboardLayout and VehicleReading are pure Kotlin with no Android API, so these are
// the same classes the app draws from. It proves nothing about rendering or about VTM.
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.openautoandroid.source.Box;
import org.openautoandroid.source.Dashboard;
import org.openautoandroid.source.DashboardLayout;
import org.openautoandroid.source.MapCamera;
import org.openautoandroid.source.MapOrientation;
import org.openautoandroid.source.PhoneSnapshot;
import org.openautoandroid.source.Scaled;
import org.openautoandroid.source.SpeedUnit;
import org.openautoandroid.source.TouchPoint;
import org.openautoandroid.source.VehicleReading;

public final class DashboardTest {
    private static int passed;

    public static void main(String[] args) {
        paneAndTilesTileTheContentRectangle();
        mediaButtonsHitOnlyInsideTheMediaTile();
        theMapSideIsConfigurable();
        fordsMeasuredSensorValuesScaleIntoRange();
        theHeaderSpeedIsInTheChosenUnit();
        anUnpopulatedFixIsNotFollowed();
        theReportCarriesNoPosition();
        mediaPressesReturnTheirAction();
        theFloatingMapControlsSitInTheBottomLeftOfThePane();
        aDragPansTheMapAndRecentreBringsItBack();
        aPinchZoomsAboutTheVehicleAndATwistRotates();
        theCompassSwitchesBetweenNorthUpAndTravelUp();
        theOrientationChoiceIsHandedOutAndTakenBack();
        theMarkerIsAnArrowOnlyWhenMoving();
        aPressOnAControlIsNotADragOfTheMap();
        everyPointerSurvivesTheWireFormat();
        aMovingVehicleCarriesTheMapWithIt();
        theScaleComesFromTheDriveNotFromOneMessage();
        aLongPressPicksThePlaceUnderTheFinger();
        System.out.println("jni-host-test: ok " + passed + " dashboard checks");
    }

    /** Header across the top, map one half, four tiles the other, nothing overlapping. */
    private static void paneAndTilesTileTheContentRectangle() {
        DashboardLayout layout = new DashboardLayout(0, 0, 1920, 1080, true);
        check(layout.getHeader() == 120, "header is a ninth of the height");
        check(layout.getMargin() == 20, "margin is a 96th of the width");

        Box map = layout.getMap();
        check(map.getX() == 20 && map.getY() == 140, "map sits below the header on the left");
        check(map.getRight() <= 1920 && map.getBottom() <= 1080, "map stays inside the frame");

        // The media pane takes the whole top row of the half the map does not occupy.
        Box media = layout.getMedia();
        check(media.getX() >= map.getRight(), "media pane is clear of the map");
        check(media.getWidth() == layout.getPaneWidth(), "media pane spans the full half");
        check(media.getRight() <= 1920 && media.getBottom() <= 1080, "media pane inside the frame");

        for (int i = 0; i < DashboardLayout.TILES; i++) {
            Box tile = layout.getTiles().get(i);
            check(tile.getX() >= map.getRight(), "tile " + i + " is clear of the map");
            check(tile.getBottom() <= 1080 && tile.getRight() <= 1920, "tile " + i + " inside frame");
            check(tile.getY() >= media.getBottom(), "tile " + i + " sits under the media pane");
        }
        check(DashboardLayout.TILES == 2, "two configurable tiles under the media pane");
        check(layout.getTiles().get(0).getRight() <= layout.getTiles().get(1).getX(), "tiles disjoint");
        // The two rows together fill the half without overrunning it.
        check(layout.getTiles().get(0).getBottom() <= map.getBottom() + 1, "tiles end with the map");

        // Square cover art inside the media pane, left of everything else.
        Box art = layout.getAlbumArt();
        check(art.getWidth() == art.getHeight() && art.getWidth() > 0, "cover art is a square");
        check(art.getX() >= media.getX() && art.getBottom() <= media.getBottom(),
                "cover art is inside the media pane");
        check(layout.mediaTextX() >= art.getRight(), "text starts clear of the art");
        check(layout.mediaTextWidth() > 0, "there is room for text beside the art");
    }

    private static void mediaButtonsHitOnlyInsideTheMediaTile() {
        DashboardLayout layout = new DashboardLayout(0, 0, 1920, 1080, true);
        Box media = layout.getMedia();
        for (int i = 0; i < DashboardLayout.Companion.getMEDIA_ACTIONS().size(); i++) {
            Box button = layout.mediaButton(i);
            check(button.getX() >= media.getX() && button.getRight() <= media.getRight(),
                    "media button " + i + " is inside its tile horizontally");
            check(button.getBottom() <= media.getBottom(), "media button " + i + " fits vertically");
            String action = layout.hitMedia(button.getCenterX(), button.getCenterY());
            check(DashboardLayout.Companion.getMEDIA_ACTIONS().get(i).equals(action),
                    "centre of button " + i + " selects " + DashboardLayout.Companion.getMEDIA_ACTIONS().get(i));
        }
        // The now-playing text above the buttons, and the cover art, are not targets.
        check(layout.hitMedia(media.getX() + 5, media.getY() + 5) == null, "text area is not a button");
        Box art = layout.getAlbumArt();
        check(layout.hitMedia(art.getCenterX(), art.getCenterY()) == null, "cover art is not a button");
        check(layout.hitMedia(layout.getMap().getCenterX(), layout.getMap().getCenterY()) == null,
                "the map is not a media button");
    }

    private static void theMapSideIsConfigurable() {
        DashboardLayout left = new DashboardLayout(0, 0, 1920, 1080, true);
        DashboardLayout right = new DashboardLayout(0, 0, 1920, 1080, false);
        check(left.getMap().getX() < left.getTiles().get(0).getX(), "map left puts tiles right");
        check(right.getMap().getX() > right.getTiles().get(0).getX(), "map right puts tiles left");
        check(left.getMap().getWidth() == right.getMap().getWidth(), "the pane is the same size either way");
    }

    /**
     * The three values P5.2 recorded as not reading sensibly. Two of them do, under a
     * scale or an enum the first reading did not apply; this pins that interpretation
     * so a later change has to disagree on purpose.
     */
    private static void fordsMeasuredSensorValuesScaleIntoRange() {
        Map<String, String> fields = new HashMap<>();
        fields.put("bearing", "103553459");
        fields.put("gear", "101");
        fields.put("car_speed", "0");
        fields.put("latitude", "377749000");
        fields.put("longitude", "-1224194000");
        VehicleReading reading = VehicleReading.Companion.parse(fields);

        Scaled bearing = reading.getBearing();
        check(bearing != null && bearing.getValidated(), "Ford's bearing scales into range");
        check(bearing != null && Math.abs(bearing.getValue() - 103.553459) < 1e-6,
                "103553459 is 103.55 degrees at 1e6");
        Dashboard ford = dashboard();
        ford.vehicle(reading);
        check(!ford.nextFrame(0L).getUnvalidated(), "Ford's 1e6 bearing is confirmed, so not marked");
        Map<String, String> labFields = new HashMap<>();
        labFields.put("bearing", "1790");
        labFields.put("latitude", "377749000");
        labFields.put("longitude", "-1224194000");
        Dashboard inferred = dashboard();
        inferred.vehicle(VehicleReading.Companion.parse(labFields));
        check(inferred.nextFrame(0L).getUnvalidated(), "the lab's 1e3 bearing is still marked inferred");
        check(bearing != null && bearing.getDivisor() == 1e6, "and the divisor is recorded, not hidden");

        check("P".equals(reading.gearName()), "gear 101 is Park, not an out-of-range number");

        check(reading.position() != null, "a scaled 1e7 position is followed");
        check(Math.abs(reading.position().getFirst() - 37.7749) < 1e-4, "latitude at 1e7");

        // The lab receiver's bearing, which is where the largest-divisor-first ordering
        // went wrong: 1790 over 1e6 is 0.0018, in range and completely wrong.
        Map<String, String> lab = new HashMap<>();
        lab.put("bearing", "1790");
        Scaled labBearing = VehicleReading.Companion.parse(lab).getBearing();
        check(labBearing != null && labBearing.getDivisor() == 1e3,
                "1790 is 1.79 degrees at 1e3, not 0.0018 at 1e6");
        check(labBearing != null && Math.abs(labBearing.getValue() - 1.79) < 1e-9, "and the value follows");

        // A small raw value that is already in its own unit keeps a divisor of 1.
        Map<String, String> plain = new HashMap<>();
        plain.put("bearing", "45");
        Scaled plainBearing = VehicleReading.Companion.parse(plain).getBearing();
        check(plainBearing != null && plainBearing.getDivisor() == 1.0 && plainBearing.getValue() == 45.0,
                "an unscaled 45 degrees stays 45 degrees");

        // A value no candidate explains is still shown, and still marked.
        Map<String, String> wild = new HashMap<>();
        wild.put("bearing", "999999999");
        Scaled unexplained = VehicleReading.Companion.parse(wild).getBearing();
        check(unexplained != null && !unexplained.getValidated(), "an unexplainable bearing is not validated");
    }

    /**
     * The header's speed is drawn in the unit the operator chose, out of metres per
     * second, and says which one it is. Miles per hour unless something asked for
     * kilometres: this is a US vehicle project, and the wire carries no unit at all.
     */
    private static void theHeaderSpeedIsInTheChosenUnit() {
        check(SpeedUnit.values()[0] == SpeedUnit.MPH, "the settings screen opens on mph");
        check(dashboard().nextFrame(0L).getSpeedUnit() == SpeedUnit.MPH, "and an unset dashboard draws mph");

        // 26.8224 m/s is exactly 60 mph, and 27.7778 m/s is exactly 100 km/h.
        check("60 mph".equals(SpeedUnit.MPH.format(26.8224)), "26.8224 m/s is 60 mph");
        check("97 km/h".equals(SpeedUnit.KMH.format(26.8224)), "the same speed is 97 km/h");
        check("100 km/h".equals(SpeedUnit.KMH.format(27.77778)), "27.78 m/s is 100 km/h");
        check("0 mph".equals(SpeedUnit.MPH.format(0.0)), "a stationary vehicle reads zero, not blank");
        // A source that has reported nothing must not look like a vehicle at a standstill.
        // The escaped character is the em dash the header draws, written that way
        // because javac reads this file as ASCII.
        check("\u2014 mph".equals(SpeedUnit.MPH.format(null)), "no reading draws a dash beside the unit");
        check("\u2014 km/h".equals(SpeedUnit.KMH.format(null)), "in either unit");

        // The chosen unit reaches the drawn frame and the report, so evidence from a
        // drive says which unit the screen in the photograph was in.
        Dashboard metric = new Dashboard(
                new DashboardLayout(0, 0, 1920, 1080, true), "123456", ZoneId.of("UTC"), SpeedUnit.KMH);
        check(metric.nextFrame(0L).getSpeedUnit() == SpeedUnit.KMH, "the choice reaches the snapshot");
        check(metric.report().contains("dashboard_speed_unit=km/h"), "and the report");
        check(dashboard().report().contains("dashboard_speed_unit=mph"), "the default is reported too");

        Map<String, String> moving = new HashMap<>();
        moving.put("car_speed", "30");
        metric.vehicle(VehicleReading.Companion.parse(moving));
        Double speed = metric.nextFrame(0L).getSpeed();
        check(speed != null && speed == 30.0, "the snapshot keeps metres per second");
        check("108 km/h".equals(SpeedUnit.KMH.format(speed)), "and the unit is applied at drawing time");
    }

    private static void anUnpopulatedFixIsNotFollowed() {
        Map<String, String> zero = new HashMap<>();
        zero.put("latitude", "0");
        zero.put("longitude", "0");
        check(VehicleReading.Companion.parse(zero).position() == null, "0,0 is not a fix");

        Map<String, String> absent = new HashMap<>();
        absent.put("latitude", "-");
        absent.put("longitude", "-");
        check(VehicleReading.Companion.parse(absent).position() == null, "an absent fix is not a fix");

        // With no vehicle fix the phone is what the map follows.
        Dashboard dashboard = dashboard();
        dashboard.vehicle(VehicleReading.Companion.parse(zero));
        check(dashboard.followPosition() == null, "nothing to follow without either source");
        dashboard.phone(new PhoneSnapshot(60, false, 3, "cell", 51.5, -0.12, null, null, null, true));
        check(dashboard.followPosition() != null, "the phone is the fallback");
        check(Math.abs(dashboard.followPosition().getFirst() - 51.5) < 1e-9, "and it is the phone's fix");
    }

    /** Spec section 10: position and listening history stay out of ordinary evidence. */
    private static void theReportCarriesNoPosition() {
        Dashboard dashboard = dashboard();
        Map<String, String> fields = new HashMap<>();
        fields.put("latitude", "377749000");
        fields.put("longitude", "-1224194000");
        fields.put("bearing", "103553459");
        dashboard.vehicle(VehicleReading.Companion.parse(fields));
        dashboard.nowPlaying(true, "playing", "Some App", "A Private Track", "An Artist");
        String report = dashboard.report();

        check(!report.contains("377749"), "no latitude in the report");
        check(!report.contains("1224194"), "no longitude in the report");
        check(!report.contains("A Private Track"), "no track title in the report");
        check(!report.contains("An Artist"), "no artist in the report");
        // What it must say instead: which source won and whether its scale was inferred.
        check(report.contains("dashboard_position_source=vehicle"), "the source is recorded");
        check(report.contains("dashboard_vehicle_fix=true"), "the fix is recorded as a boolean");
        check(report.contains("dashboard_bearing_divisor=1000000.0"), "the inferred divisor is recorded");
        // The map view line says how the driver has left the map, never where it is.
        check(report.contains("dashboard_map_view=orientation=north-up following=true"),
                "how the map is oriented is recorded");
        check(report.contains("zoom=16.0"), "and how far in it is zoomed");

        // Cover art is recorded as present or absent and never by content: the image is
        // as much the operator listening history as the title is.
        check(report.contains("dashboard_album_art=false"), "no art reported when none was supplied");
        dashboard.nowPlaying(true, "playing", "Some App", "A Private Track", "An Artist", "key|600x600");
        check(dashboard.report().contains("dashboard_album_art=true"), "art presence is reported");
        check(!dashboard.report().contains("600x600"), "the art identity itself stays out of the report");
    }

    private static void mediaPressesReturnTheirAction() {
        Dashboard dashboard = dashboard();
        Box button = dashboard.getLayout().mediaButton(1);
        int x = button.getCenterX();
        int y = button.getCenterY();
        check(dashboard.touch("down", x, y) == null, "a press alone dispatches nothing");
        check("play-pause".equals(dashboard.touch("up", x, y)), "press then release dispatches");

        // A press that wanders off the button before lifting must not dispatch.
        check(dashboard.touch("down", x, y) == null, "second press");
        check(dashboard.touch("up", dashboard.getLayout().getMap().getCenterX(), y) == null,
                "releasing off the button dispatches nothing");

        // Nor may a drag that starts outside a tile count as a tap on the tile it
        // happens to end in. A fresh dashboard, because the presses above already
        // tapped the media tile: the transport buttons live inside it.
        Dashboard fresh = dashboard();
        Box tile = fresh.getLayout().getTiles().get(1);
        fresh.touch("down", fresh.getLayout().getMap().getCenterX(), tile.getCenterY());
        fresh.touch("up", tile.getCenterX(), tile.getCenterY());
        check(taps(fresh)[1] == 0, "a drag from outside does not tap the tile it ends in");

        // The same tile does count when the press starts in it.
        fresh.touch("down", tile.getCenterX(), tile.getCenterY());
        fresh.touch("up", tile.getCenterX(), tile.getCenterY());
        check(taps(fresh)[1] == 1, "a press and release inside the tile is a tap");
    }

    /**
     * Two round targets stacked in the map pane's bottom-left corner, inside it, clear
     * of each other, and reachable only from there.
     */
    private static void theFloatingMapControlsSitInTheBottomLeftOfThePane() {
        DashboardLayout layout = new DashboardLayout(0, 0, 1920, 1080, true);
        Box map = layout.getMap();
        List<String> actions = DashboardLayout.Companion.getMAP_ACTIONS();
        check(actions.size() == 2, "a recentre control and a compass");
        check("recentre".equals(actions.get(0)) && "compass".equals(actions.get(1)),
                "recentre nearest the corner, compass above it");

        Box recentre = layout.mapBubble(0);
        Box compass = layout.mapBubble(1);
        check(recentre.getWidth() == recentre.getHeight(), "the bubbles are round targets");
        check(recentre.getWidth() >= 64, "and big enough to hit at arm's length at 1080p");
        for (int i = 0; i < actions.size(); i++) {
            Box bubble = layout.mapBubble(i);
            check(bubble.getX() >= map.getX() && bubble.getRight() <= map.getRight(),
                    "bubble " + i + " is inside the pane horizontally");
            check(bubble.getY() >= map.getY() && bubble.getBottom() <= map.getBottom(),
                    "bubble " + i + " is inside the pane vertically");
            check(bubble.getCenterX() < map.getCenterX() && bubble.getCenterY() > map.getCenterY(),
                    "bubble " + i + " is in the bottom-left quarter");
            check(actions.get(i).equals(layout.hitMapBubble(bubble.getCenterX(), bubble.getCenterY())),
                    "the centre of bubble " + i + " selects " + actions.get(i));
        }
        check(compass.getBottom() <= recentre.getY(), "the two bubbles are disjoint");
        check(layout.hitMapBubble(map.getCenterX(), map.getCenterY()) == null,
                "the middle of the map is not a control");
        check(layout.hitMapBubble(recentre.getCenterX(), recentre.getY() - 1) == null,
                "the gap between the bubbles is not a control");
        // A control drawn over the map must not also be a media button or a tile.
        check(layout.hitMedia(recentre.getCenterX(), recentre.getCenterY()) == null,
                "the recentre bubble is not a transport button");
        check(layout.hitTile(compass.getCenterX(), compass.getCenterY()) < 0,
                "the compass bubble is not a tile");

        // The smallest frame this head unit selects still has to be able to draw them.
        DashboardLayout ford = new DashboardLayout(37, 0, 726, 480, true);
        check(ford.mapBubble(1).getY() >= ford.getMap().getY(),
                "both bubbles fit the pane in Ford's 800x480 configuration");
    }

    /**
     * A drag moves the map by the distance the finger travelled, lets go of the vehicle,
     * and leaves the marker where the fingers put it. The recentre control undoes it.
     *
     * The numbers are the arithmetic VTM does in `ViewController.moveMap`: a pixel is
     * `1 / (scale * Tile.SIZE)` of the world, so at the follow view's 2^16 scale and the
     * 384px tiles the map engine's 240dpi produces, 100px is 100/25165824 of the world
     * and so 360 * that many degrees of longitude.
     */
    private static void aDragPansTheMapAndRecentreBringsItBack() {
        Dashboard dashboard = following();
        Box map = dashboard.getLayout().getMap();
        int cx = map.getCenterX();
        int cy = map.getCenterY();

        MapCamera start = dashboard.mapCamera();
        check(start != null, "a fix gives the map somewhere to look");
        check(start.getScale() == 65536.0, "the follow view starts at about zoom 16");
        check(start.getRotation() == 0f, "and north-up");
        check(Math.abs(start.getLongitude() + 0.12) < 1e-12, "centred on the fix");
        check(centre(dashboard).equals(marker(dashboard)), "the marker is the pane centre while following");

        dashboard.touch("down", points(point(0, cx, cy)));
        dashboard.touch("move", points(point(0, cx - 100, cy)));
        MapCamera panned = dashboard.mapCamera();
        // Dragging the picture west looks further east, by exactly the pixels dragged.
        double expected = -0.12 + 360.0 * 100.0 / (65536.0 * 384.0);
        check(Math.abs(panned.getLongitude() - expected) < 1e-9,
                "100px west is 360*100/(scale*tile) degrees east, not " + panned.getLongitude());
        check(Math.abs(panned.getLatitude() - 51.5) < 1e-9, "a horizontal drag does not move latitude");
        check(!dashboard.report().contains("following=true"), "the drag let go of the vehicle");
        // The marker follows the ground, which is where the finger left it.
        check(marker(dashboard).getFirst() == cx - 100 && marker(dashboard).getSecond() == cy,
                "the vehicle is drawn where the drag put it, not at the centre");
        dashboard.touch("up", points(point(0, cx - 100, cy)));

        // A drag shorter than the slop is a wobble, not a pan: a pinch never lands and
        // lifts both fingers together, and a vehicle is not a still place to point.
        Dashboard steady = following();
        steady.mapCamera();
        steady.touch("down", points(point(0, cx, cy)));
        steady.touch("move", points(point(0, cx + 4, cy + 3)));
        check(steady.report().contains("following=true"), "a few pixels of wobble keeps the vehicle");
        steady.touch("up", points(point(0, cx + 4, cy + 3)));

        press(dashboard, 0);
        MapCamera home = dashboard.mapCamera();
        check(Math.abs(home.getLongitude() + 0.12) < 1e-12, "recentre goes back to the fix");
        check(dashboard.report().contains("following=true"), "and follows it again");
        check(centre(dashboard).equals(marker(dashboard)), "so the marker is the pane centre again");
        check(home.getScale() == panned.getScale(), "recentre is not a zoom reset");
        check(dashboard.report().contains("recentres=1"), "the press is recorded");
    }

    /**
     * Two fingers: the span is the zoom and the angle between them is the rotation.
     * Neither costs the driver the vehicle, because neither moves the focal point.
     */
    private static void aPinchZoomsAboutTheVehicleAndATwistRotates() {
        Dashboard dashboard = following();
        Box map = dashboard.getLayout().getMap();
        int cx = map.getCenterX();
        int cy = map.getCenterY();
        dashboard.mapCamera();

        dashboard.touch("down", points(point(0, cx - 50, cy)));
        dashboard.touch("pointer-down", points(point(0, cx - 50, cy), point(1, cx + 50, cy)));
        dashboard.touch("move", points(point(0, cx - 100, cy), point(1, cx + 100, cy)));
        MapCamera zoomed = dashboard.mapCamera();
        check(zoomed.getScale() == 131072.0, "fingers twice as far apart is twice the scale");
        check(Math.abs(zoomed.getLongitude() + 0.12) < 1e-12,
                "a pinch centred on the pane keeps the vehicle under the marker");
        check(dashboard.report().contains("following=true"), "so it costs no recentre press");
        check(centre(dashboard).equals(marker(dashboard)), "and the marker stays at the centre");
        dashboard.touch("up", points(point(0, cx - 100, cy), point(1, cx + 100, cy)));

        // Fingers turning counter-clockwise turn the picture counter-clockwise, which
        // brings what was on the right round to the top: with north up that is east.
        dashboard.touch("down", points(point(0, cx, cy - 50)));
        dashboard.touch("pointer-down", points(point(0, cx, cy - 50), point(1, cx, cy + 50)));
        dashboard.touch("move", points(point(0, cx - 50, cy), point(1, cx + 50, cy)));
        MapCamera turned = dashboard.mapCamera();
        check(Math.abs(turned.getRotation() - 90f) < 1e-3,
                "a quarter turn anticlockwise puts east at the top, not " + turned.getRotation());
        check(turned.getScale() == 131072.0, "a twist that holds the span is not a zoom");
        dashboard.touch("up", points(point(0, cx - 50, cy), point(1, cx + 50, cy)));

        // With the map turned, a drag moves it along the picture and not along north.
        // Dragging the picture up uncovers what was below the bottom edge, and the
        // bottom of an east-up map is west, so this is the check that a rotated map
        // pans in the direction the driver is actually looking.
        dashboard.touch("down", points(point(0, cx, cy)));
        dashboard.touch("move", points(point(0, cx, cy - 100)));
        MapCamera dragged = dashboard.mapCamera();
        double west = -0.12 - 360.0 * 100.0 / (131072.0 * 384.0);
        check(Math.abs(dragged.getLongitude() - west) < 1e-9,
                "dragging up an east-up map goes west, not " + dragged.getLongitude());
        check(Math.abs(dragged.getLatitude() - 51.5) < 1e-9, "and does not change latitude");
        dashboard.touch("up", points(point(0, cx, cy - 100)));

        String report = dashboard.report();
        check(report.contains("pointers_max=2"), "the report says two fingers arrived");
        check(report.contains("multi_pointer=2"), "and how many gestures used them");
    }

    /** North-up and direction-of-travel-up, and one press to square the map up. */
    private static void theCompassSwitchesBetweenNorthUpAndTravelUp() {
        Dashboard dashboard = following();
        dashboard.phone(new PhoneSnapshot(60, false, 3, "wifi", 51.5, -0.12,
                Double.valueOf(135.0), null, null, true));
        check(dashboard.mapCamera().getRotation() == 0f, "north-up until it is switched");
        check(dashboard.nextFrame(0L).getMapOrientation() == MapOrientation.NORTH_UP,
                "and the chrome is told which it is");

        press(dashboard, 1);
        check(Math.abs(dashboard.mapCamera().getRotation() - 135f) < 1e-3,
                "travel-up turns the map to the bearing");
        check(dashboard.nextFrame(0L).getMapOrientation() == MapOrientation.HEADING_UP,
                "and says so");

        // The heading is held rather than snapping north when the fix goes quiet: losing
        // it at a junction is exactly when the map is being read.
        dashboard.phone(new PhoneSnapshot(60, false, 3, "wifi", 51.5, -0.12, null, null, null, true));
        check(Math.abs(dashboard.mapCamera().getRotation() - 135f) < 1e-3,
                "a lost bearing holds the last heading");

        press(dashboard, 0);
        check(Math.abs(dashboard.mapCamera().getRotation() - 135f) < 1e-3,
                "recentring is not a rotation");
        press(dashboard, 1);
        check(dashboard.mapCamera().getRotation() == 0f, "and the compass puts north back up");

        // A twist leaves the map askew; the compass is the one press that squares it.
        Box map = dashboard.getLayout().getMap();
        int cx = map.getCenterX();
        int cy = map.getCenterY();
        dashboard.touch("down", points(point(0, cx, cy - 50)));
        dashboard.touch("pointer-down", points(point(0, cx, cy - 50), point(1, cx, cy + 50)));
        dashboard.touch("move", points(point(0, cx - 50, cy), point(1, cx + 50, cy)));
        dashboard.touch("up", points(point(0, cx - 50, cy), point(1, cx + 50, cy)));
        check(dashboard.mapCamera().getRotation() != 0f, "the twist turned it");
        press(dashboard, 1);
        press(dashboard, 1);
        check(dashboard.mapCamera().getRotation() == 0f, "two presses is north-up, square");
    }

    /** A finger that lands on a control is that control's, however far it then slides. */
    private static void aPressOnAControlIsNotADragOfTheMap() {
        Dashboard dashboard = following();
        Box map = dashboard.getLayout().getMap();
        Box bubble = dashboard.getLayout().mapBubble(0);
        dashboard.mapCamera();

        dashboard.touch("down", points(point(0, bubble.getCenterX(), bubble.getCenterY())));
        dashboard.touch("move", points(point(0, map.getCenterX(), map.getCenterY())));
        dashboard.touch("up", points(point(0, map.getCenterX(), map.getCenterY())));
        check(dashboard.report().contains("following=true"), "sliding off a control does not pan");
        check(dashboard.report().contains("gestures=0"), "and never becomes a map gesture");
        check(dashboard.report().contains("recentres=0"),
                "nor does releasing elsewhere press the control");

        // A press that starts outside the pane is not the map's either.
        Box tile = dashboard.getLayout().getTiles().get(0);
        dashboard.touch("down", points(point(0, tile.getCenterX(), tile.getCenterY())));
        dashboard.touch("move", points(point(0, map.getCenterX(), map.getCenterY())));
        dashboard.touch("up", points(point(0, map.getCenterX(), map.getCenterY())));
        check(dashboard.report().contains("following=true"), "a drag in from the tiles does not pan");
    }

    /**
     * The `pointers=` field the native session writes, read back whole.
     *
     * The format is `aap_session::input::Touch`'s Display, one `id:x:y:rawx:rawy` per
     * pointer: the exact string in core/aap-session/tests/projection.rs. Nothing about a
     * misparse here would look broken - the first pointer would still work and every
     * button with it - it would just silently turn every pinch back into a drag.
     */
    private static void everyPointerSurvivesTheWireFormat() {
        List<TouchPoint> one = TouchPoint.Companion.parse("7:400:240:400:240");
        check(one.size() == 1, "one pointer parses");
        check(one.get(0).getId() == 7, "the pointer id is the head unit's, not the index");
        check(one.get(0).getX() == 400 && one.get(0).getY() == 240, "into frame pixels");
        check(one.get(0).getRawX() == 400 && one.get(0).getRawY() == 240,
                "with the head unit's own coordinates kept for the calibration");

        // Two fingers, which is what a pinch is and what nothing else in this app needs.
        List<TouchPoint> two = TouchPoint.Companion.parse("0:400:240:960:441,1:500:300:1060:501");
        check(two.size() == 2, "both pointers of a pinch survive");
        check(two.get(1).getId() == 1 && two.get(1).getX() == 500, "in the order they were sent");
        check(two.get(1).getRawX() == 1060 && two.get(1).getRawY() == 501, "raw coordinates and all");

        // Ford's touch space maps negative-free coordinates, but a truncated or empty
        // field has to be survivable: it arrives from another process.
        check(TouchPoint.Companion.parse(null).isEmpty(), "an absent field is no pointers");
        check(TouchPoint.Companion.parse("").isEmpty(), "an empty field is no pointers");
        check(TouchPoint.Companion.parse("0:400:240").isEmpty(), "a truncated pointer is dropped");
        List<TouchPoint> partial = TouchPoint.Companion.parse("0:1:2:3:4,nonsense,2:5:6:7:8");
        check(partial.size() == 2, "a bad pointer does not take the good ones with it");
        check(partial.get(1).getId() == 2, "and the rest keep their identity");
    }

    /** A dashboard with a phone fix, which is what the map follows. */
    /**
     * P6.4: one finger held still on the map for 800 ms picks the ground under it as a
     * destination, once. A tap, a drag, a pinch, or a press that started on a control
     * picks nothing, and the report counts presses without saying where they were.
     */
    private static void aLongPressPicksThePlaceUnderTheFinger() {
        Dashboard dashboard = following();
        Box map = dashboard.getLayout().getMap();
        int cx = map.getCenterX();
        int cy = map.getCenterY();
        dashboard.mapCamera();

        dashboard.touch("down", points(point(0, cx + 100, cy)), 10_000L);
        dashboard.touch("up", points(point(0, cx + 100, cy)), 10_900L);
        kotlin.Pair<Double, Double> pick = dashboard.takeMapPick();
        check(pick != null, "a long press picks a place");
        double east = -0.12 + 360.0 * 100.0 / (65536.0 * 384.0);
        check(Math.abs(pick.getSecond() - east) < 1e-9, "100px right of centre is that far east, not " + pick.getSecond());
        check(Math.abs(pick.getFirst() - 51.5) < 1e-9, "and on the centre's latitude");
        check(dashboard.takeMapPick() == null, "a pick is taken once");
        check(dashboard.mapCamera().getLongitude() == -0.12, "and the map stays on the vehicle");

        dashboard.touch("down", points(point(0, cx, cy - 50)), 20_000L);
        dashboard.touch("up", points(point(0, cx, cy - 50)), 20_000L + 799L);
        check(dashboard.takeMapPick() == null, "799 ms is a tap, not a long press");

        dashboard.touch("down", points(point(0, cx, cy)), 30_000L);
        dashboard.touch("move", points(point(0, cx - 60, cy)), 30_400L);
        dashboard.touch("up", points(point(0, cx - 60, cy)), 31_000L);
        check(dashboard.takeMapPick() == null, "a slow drag pans and picks nothing");

        Dashboard pinch = following();
        pinch.mapCamera();
        pinch.touch("down", points(point(0, cx - 40, cy)), 40_000L);
        pinch.touch("pointer-down", points(point(0, cx - 40, cy), point(1, cx + 40, cy)), 40_050L);
        pinch.touch("pointer-up", points(point(0, cx - 40, cy), point(1, cx + 40, cy)), 41_000L);
        pinch.touch("up", points(point(0, cx - 40, cy)), 41_100L);
        check(pinch.takeMapPick() == null, "two fingers resting are not a long press");

        Box recentre = pinch.getLayout().mapBubble(0);
        pinch.touch("down", points(point(0, recentre.getCenterX(), recentre.getCenterY())), 50_000L);
        pinch.touch("up", points(point(0, recentre.getCenterX(), recentre.getCenterY())), 51_000L);
        check(pinch.takeMapPick() == null, "holding a map control is not a pick");

        Dashboard nowhere = dashboard();
        nowhere.mapCamera();
        nowhere.touch("down", points(point(0, cx, cy)), 60_000L);
        nowhere.touch("up", points(point(0, cx, cy)), 61_000L);
        check(nowhere.takeMapPick() == null, "with no map on show there is nothing to pick");

        String report = dashboard.report();
        check(report.contains("long_presses=1 "), "the pick is counted");
        check(!report.contains("51.5") && !report.contains("-0.11"), "and not located");
    }

    /** The compass's choice is reported for keeping, and a new session starts in it. */
    private static void theOrientationChoiceIsHandedOutAndTakenBack() {
        List<MapOrientation> saved = new ArrayList<>();
        Dashboard dashboard = new Dashboard(new DashboardLayout(0, 0, 1920, 1080, true), "123456",
                ZoneId.of("UTC"), SpeedUnit.MPH, MapOrientation.NORTH_UP,
                o -> { saved.add(o); return kotlin.Unit.INSTANCE; });
        press(dashboard, 1);
        press(dashboard, 1);
        check(saved.size() == 2 && saved.get(0) == MapOrientation.HEADING_UP
                && saved.get(1) == MapOrientation.NORTH_UP, "every compass press is handed out");
        press(dashboard, 0);
        check(saved.size() == 2, "recentre is not an orientation change");

        Dashboard resumed = new Dashboard(new DashboardLayout(0, 0, 1920, 1080, true), "123456",
                ZoneId.of("UTC"), SpeedUnit.MPH, MapOrientation.HEADING_UP,
                o -> kotlin.Unit.INSTANCE);
        resumed.phone(new PhoneSnapshot(60, false, 3, "wifi", 51.5, -0.12,
                Double.valueOf(90.0), null, null, true));
        check(Math.abs(resumed.mapCamera().getRotation() - 90f) < 1e-3,
                "a session that starts in travel-up turns the map without a press");
        check(resumed.nextFrame(0L).getMapOrientation() == MapOrientation.HEADING_UP, "and draws the lit compass");

        check(MapOrientation.Companion.of("heading-up") == MapOrientation.HEADING_UP, "the stored label reads back");
        check(MapOrientation.Companion.of("sideways") == null, "an unknown one is refused, not guessed");
    }

    /** A dot when stopped or crawling, an arrow along the bearing above 5 mph. */
    private static void theMarkerIsAnArrowOnlyWhenMoving() {
        Dashboard dashboard = dashboard();
        dashboard.phone(moving(90.0, 1.0));
        dashboard.mapCamera();
        check(dashboard.nextFrame(0L).getMapArrow() == null, "a dot at walking pace");

        dashboard.phone(moving(90.0, 3.0));
        dashboard.mapCamera();
        Integer arrow = dashboard.nextFrame(0L).getMapArrow();
        check(arrow != null && arrow == 90, "an arrow pointing east above 5 mph, north-up");

        press(dashboard, 1);
        dashboard.mapCamera();
        arrow = dashboard.nextFrame(0L).getMapArrow();
        check(arrow != null && (arrow == 0 || arrow == 360), "straight up in travel-up");

        // 2.0 m/s is 4.5 mph: under the threshold but over the release, so it holds.
        dashboard.phone(moving(90.0, 2.0));
        dashboard.mapCamera();
        check(dashboard.nextFrame(0L).getMapArrow() != null, "slowing through 5 mph keeps the arrow");
        dashboard.phone(moving(90.0, 1.5));
        dashboard.mapCamera();
        check(dashboard.nextFrame(0L).getMapArrow() == null, "under 4 mph it is a dot again");
        dashboard.phone(moving(90.0, 2.0));
        dashboard.mapCamera();
        check(dashboard.nextFrame(0L).getMapArrow() == null, "and 4.5 mph does not bring the arrow back");

        dashboard.phone(new PhoneSnapshot(60, false, 3, "wifi", 51.5, -0.12,
                null, Double.valueOf(10.0), null, true));
        dashboard.mapCamera();
        check(dashboard.nextFrame(0L).getMapArrow() == null, "no bearing, nothing to point along");
    }

    private static PhoneSnapshot moving(double bearing, double metresPerSecond) {
        return new PhoneSnapshot(60, false, 3, "wifi", 51.5, -0.12,
                Double.valueOf(bearing), Double.valueOf(metresPerSecond), null, true);
    }

    private static Dashboard following() {
        Dashboard dashboard = dashboard();
        dashboard.phone(new PhoneSnapshot(60, false, 3, "wifi", 51.5, -0.12, null, null, null, true));
        return dashboard;
    }

    /** Presses and releases one of the floating map controls. */
    private static void press(Dashboard dashboard, int index) {
        Box bubble = dashboard.getLayout().mapBubble(index);
        dashboard.touch("down", points(point(0, bubble.getCenterX(), bubble.getCenterY())));
        dashboard.touch("up", points(point(0, bubble.getCenterX(), bubble.getCenterY())));
    }

    /**
     * P6.1. A sequence of fixes rather than one: the camera goes where the vehicle
     * goes, the marker stays under it, and in travel-up the map turns as the heading
     * does.
     *
     * The lab could not ask this before: it reported one fixed position for the whole
     * session, so every rule here had only ever been exercised on a parked vehicle,
     * where "follows the fix" and "ignores the fix and stays put" look identical.
     */
    private static void aMovingVehicleCarriesTheMapWithIt() {
        Dashboard dashboard = dashboard();
        press(dashboard, 1); // travel-up, so the heading is read as well as the position

        // The lab receiver's own scales: position at 1e7, bearing in thousandths of a
        // degree, speed in millimetres per second. Driving east, so longitude climbs.
        double previous = Double.NEGATIVE_INFINITY;
        for (int step = 0; step < 8; step++) {
            Map<String, String> fields = new HashMap<>();
            fields.put("latitude", "424242424");
            // 326 units of 1e-7 degrees is 2.7 m at this latitude, which is what 13.4
            // m/s covers in the 200 ms the lab receiver puts between two fixes.
            fields.put("longitude", Integer.toString(-712121212 + step * 326));
            fields.put("bearing", "90000");
            fields.put("gps_speed", "13400");
            fields.put("car_speed", "13400");
            dashboard.vehicle(VehicleReading.Companion.parse(fields));
            MapCamera camera = dashboard.mapCamera();
            check(camera != null, "a moving fix is still a fix");
            check(camera.getLongitude() > previous, "the camera followed the vehicle east");
            check(Math.abs(camera.getLatitude() - 42.4242424) < 1e-9, "and did not drift north");
            check(centre(dashboard).equals(marker(dashboard)), "the marker stays under the vehicle");
            previous = camera.getLongitude();
        }
        check(Math.abs(previous + 71.2118930) < 1e-7, "eight steps east of the lab position");

        // 90 degrees at 1e3, not 90000 at 1: the divisor is inferred per value, and the
        // map is turned by what came out of that rather than by the raw number.
        check(Math.abs(dashboard.mapCamera().getRotation() - 90f) < 1e-3,
                "travel-up turns the map to the heading being driven");
        check(dashboard.report().contains("dashboard_bearing_divisor=1000.0"), "at 1e3");
        check(dashboard.report().contains("dashboard_car_speed_divisor=1000.0"),
                "and 13400 is 13.4 m/s, not 13400 m/s");
        check("30 mph".equals(SpeedUnit.MPH.format(dashboard.nextFrame(0L).getSpeed())),
                "which the header draws as 30 mph");

        // A corner: the map turns with it, and nothing about the position changes.
        Map<String, String> corner = new HashMap<>();
        corner.put("latitude", "424242424");
        corner.put("longitude", "-712118930");
        corner.put("bearing", "350000");
        dashboard.vehicle(VehicleReading.Companion.parse(corner));
        check(Math.abs(dashboard.mapCamera().getRotation() + 10f) < 1e-3,
                "350 degrees is ten degrees west of north, not 350 clockwise");
        check(dashboard.report().contains("dashboard_vehicle_events=9"), "every fix is counted");
    }

    /**
     * P6.1. A bearing and a speed are read against every value the drive has carried,
     * not against themselves alone.
     *
     * The lab found this by driving: its track ends a lap pointing north, and the run
     * that produced the first moving map reported the bearing scale as 1, because the
     * last fix of a session that had been sending thousandths of a degree all along was
     * `bearing=0`, which is zero degrees at every scale there is.
     */
    private static void theScaleComesFromTheDriveNotFromOneMessage() {
        Dashboard dashboard = dashboard();
        Map<String, String> moving = new HashMap<>();
        moving.put("bearing", "90000");
        moving.put("car_speed", "13400");
        dashboard.vehicle(VehicleReading.Companion.parse(moving));
        check(dashboard.report().contains("dashboard_bearing_divisor=1000.0"), "90000 is 90 degrees at 1e3");

        // The same vehicle a minute later, pointing north and creeping forward. Read on
        // their own these say whole degrees and 50 m/s; read against the drive they say
        // north and a walking pace.
        Map<String, String> creeping = new HashMap<>();
        creeping.put("bearing", "0");
        creeping.put("car_speed", "50");
        dashboard.vehicle(VehicleReading.Companion.parse(creeping));
        String report = dashboard.report();
        check(report.contains("dashboard_bearing_divisor=1000.0"),
                "a drive that ends pointing north keeps the scale it drove at");
        check(report.contains("dashboard_car_speed_divisor=1000.0"), "and the one its speed was in");
        check("0 mph".equals(SpeedUnit.MPH.format(dashboard.nextFrame(0L).getSpeed())),
                "50 thousandths of a metre per second is a crawl, not 112 mph");

        // Standalone, that same message really does say 50 m/s: the ambiguity is real,
        // and what settles it is the rest of the drive rather than a better guess.
        check(VehicleReading.Companion.parse(creeping).getCarSpeed().getDivisor() == 1.0,
                "one message on its own cannot tell the two apart");

        // A head unit at another scale is unaffected: Ford sends its bearing at 1e6, and
        // a session of those keeps 1e6 when the vehicle comes to rest.
        Dashboard ford = dashboard();
        Map<String, String> fordFields = new HashMap<>();
        fordFields.put("bearing", "103553459");
        ford.vehicle(VehicleReading.Companion.parse(fordFields));
        fordFields.put("bearing", "0");
        ford.vehicle(VehicleReading.Companion.parse(fordFields));
        check(ford.report().contains("dashboard_bearing_divisor=1000000.0"),
                "Ford's 1e6 bearing survives a stop");

        // One implausible reading does not rescale the drive around itself. Ten
        // thousandths-of-a-degree bearings and then a value only 1e6 could explain: the
        // drive keeps its own scale, and the outlier is the single thing marked.
        Dashboard driven = dashboard();
        Map<String, String> heading = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            heading.put("bearing", Integer.toString(45000 + i * 1000));
            driven.vehicle(VehicleReading.Companion.parse(heading));
        }
        heading.put("bearing", "900000");
        driven.vehicle(VehicleReading.Companion.parse(heading));
        String outlier = driven.report();
        check(outlier.contains("dashboard_bearing_divisor=1000.0"), "the drive keeps the scale it drove at");
        check(outlier.contains("dashboard_bearing_validated=false"),
                "and 900 degrees is out of range at that scale, and says so");
    }

    /** A pointer whose head-unit coordinates are its frame pixels, as Ford's are. */
    private static TouchPoint point(int id, int x, int y) {
        return new TouchPoint(id, x, y, x, y);
    }

    private static List<TouchPoint> points(TouchPoint... pointers) {
        List<TouchPoint> list = new ArrayList<>();
        for (TouchPoint pointer : pointers) list.add(pointer);
        return list;
    }

    /** Where the vehicle is drawn, in frame pixels. */
    private static kotlin.Pair<Integer, Integer> marker(Dashboard dashboard) {
        return dashboard.nextFrame(0L).getMapMarker();
    }

    private static kotlin.Pair<Integer, Integer> centre(Dashboard dashboard) {
        Box map = dashboard.getLayout().getMap();
        return new kotlin.Pair<>(map.getCenterX(), map.getCenterY());
    }

    /** The per-tile tap counts out of the report line that carries them. */
    private static int[] taps(Dashboard dashboard) {
        for (String line : dashboard.report().split("\n")) {
            int at = line.indexOf("dashboard_tile_taps=");
            if (at < 0) continue;
            String value = line.substring(at + "dashboard_tile_taps=".length()).split(" ")[0];
            String[] parts = value.split(",");
            int[] counts = new int[parts.length];
            for (int i = 0; i < parts.length; i++) counts[i] = Integer.parseInt(parts[i]);
            return counts;
        }
        throw new AssertionError("no dashboard_tile_taps in the report");
    }

    private static Dashboard dashboard() {
        return new Dashboard(
                new DashboardLayout(0, 0, 1920, 1080, true), "123456", ZoneId.of("UTC"));
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
