// P11.5 on a desktop JVM (docs/navigation.md, "OsmAnd maps: search and places"):
// the map's Gas, Food and Shop buttons
// along the top of the map pane at 1080p, Ford's 726x480 and 800x480 with the map on either
// side, clear of the bubbles, the credits and both cards; their presses; a tapped marker's
// card in the flex tile with Go and Close, and the three-place list P11.5a builds on with
// its own primary button; where the card sits among the tile's sources; the query over the
// OBF files, capped per kind nearest the pane's centre, with a fake ObfSource; the refresh
// cadence (at once on a press, 500 ms after the map stops, at most every 10 s while
// following) and stale results dropped; the open-now line; the marker colours on both map
// grounds; and that no report carries a name or a place. Dashboard, DashboardLayout,
// PoiRules, PoiSearch and PoiFeed are pure Kotlin; the VTM markers and the drawing itself
// are for the lab's renders. Text widths are estimated at DashboardLayout.EM_PER_CHARACTER.
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import com.jw6ventures.headlink.AlertLevel;
import com.jw6ventures.headlink.Box;
import com.jw6ventures.headlink.CallPhase;
import com.jw6ventures.headlink.CallSource;
import com.jw6ventures.headlink.CallState;
import com.jw6ventures.headlink.Dashboard;
import com.jw6ventures.headlink.DashboardLayout;
import com.jw6ventures.headlink.DashboardPalette;
import com.jw6ventures.headlink.FlexRules;
import com.jw6ventures.headlink.FlexSource;
import com.jw6ventures.headlink.GeoBox;
import com.jw6ventures.headlink.LaterStep;
import com.jw6ventures.headlink.Maneuvers;
import com.jw6ventures.headlink.ObfBuilding;
import com.jw6ventures.headlink.ObfCity;
import com.jw6ventures.headlink.ObfPoi;
import com.jw6ventures.headlink.ObfSource;
import com.jw6ventures.headlink.ObfStreet;
import com.jw6ventures.headlink.OfferLayout;
import com.jw6ventures.headlink.PhoneSnapshot;
import com.jw6ventures.headlink.PoiAction;
import com.jw6ventures.headlink.PoiButtons;
import com.jw6ventures.headlink.PoiCardContent;
import com.jw6ventures.headlink.PoiCardView;
import com.jw6ventures.headlink.PoiEntry;
import com.jw6ventures.headlink.PoiFeed;
import com.jw6ventures.headlink.PoiKind;
import com.jw6ventures.headlink.PoiRules;
import com.jw6ventures.headlink.PoiSearch;
import com.jw6ventures.headlink.PoiView;
import com.jw6ventures.headlink.UpcomingTurn;

public final class DashboardPoiTest {
    private static int passed;

    private static final long NOON = Instant.parse("2026-10-03T17:00:00Z").toEpochMilli(); // a Saturday, 12:00 in Austin
    private static final ZoneId AUSTIN = ZoneId.of("America/Chicago");
    private static final double LAT = 30.2672;
    private static final double LON = -97.7431;
    private static final String NAME = "Placemarker Fuel";

    /** Night and day map grounds, from dashboard-dark.xml and dashboard-light.xml. */
    private static final int NIGHT_GROUND = 0xFF151A2B;
    private static final int DAY_GROUND = 0xFFE9E7E1;
    /** MapEngine's route cyan. */
    private static final int ROUTE = 0xFF22C7F0;

    public static void main(String[] args) {
        for (DashboardLayout layout : everyDisplay()) {
            theButtonsRunDownTheLeftOfThePane(layout);
            theButtonsStayClearOfEverything(layout);
            theCardFitsTheFlexTile(layout);
        }
        theButtonsAreHitWhereTheyAreDrawn();
        aPressFlipsAButtonAndIsHandedOn();
        theButtonsMoveUnderTheTurnCard();
        aTappedMarkerOpensItsCard();
        aDragOrALongPressOnAMarkerIsTheMaps();
        goRoutesToTheChosenPlaceAndCloseCloses();
        anUnnamedPlaceIsCalledWhatItIs();
        theListTakesThreeAndItsOwnButton();
        aNoticeHasItsLineAndCloseAlone();
        aCallWinsAndTheWeatherViewTakesTurns();
        anUntouchedCardCloses();
        theSearchCapsEachKindNearestFirst();
        theSearchCountsAFailedFileAndSkipsFarOnes();
        theFeedAsksAtOnceOnAPress();
        theFeedWaitsForTheMapToStop();
        theFeedRefreshesAtMostEveryTenSecondsWhileFollowing();
        theFeedDropsStaleResults();
        theFeedTakesExtraMarkersForSearchAhead();
        openingHoursOnlyWhenTheyParse();
        theMarkerColoursReadOnBothGrounds();
        theReportCountsAndNamesNothing();
        System.out.println("jni-host-test: ok " + passed + " dashboard POI button, marker, card and feed checks");
    }

    // --- layout ---------------------------------------------------------------------

    /** 1080p, Ford's 726x480 content rectangle and the 800x480 minimum, map on both sides, both offer layouts. */
    private static List<DashboardLayout> everyDisplay() {
        List<DashboardLayout> all = new ArrayList<>();
        for (OfferLayout offer : OfferLayout.values()) {
            for (boolean left : new boolean[] {true, false}) {
                all.add(new DashboardLayout(0, 0, 1920, 1080, left, offer));
                all.add(new DashboardLayout(74, 0, 726, 480, left, offer));
                all.add(new DashboardLayout(0, 0, 800, 480, left, offer));
            }
        }
        return all;
    }

    private static String size(DashboardLayout l) {
        return l.getWidth() + "x" + l.getHeight() + (l.getMapOnLeft() ? "L" : "R") + " " + l.getOfferLayout().getKey() + ": ";
    }

    private static void theButtonsRunDownTheLeftOfThePane(DashboardLayout l) {
        String at = size(l);
        Box map = l.getMap();
        int top = l.poiButtonsTop(false, false);
        check(top == map.getY() + l.getMargin(), at + "a margin into the pane with no card");
        for (int i = 0; i < 3; i++) {
            Box b = l.poiButton(i, top);
            check(inside(b, map), at + "button " + i + " inside the map pane");
            check(b.getHeight() == l.getBubbleSize() && b.getWidth() == l.getBubbleSize(), at + "a round bubble: " + b.getWidth() + "x" + b.getHeight());
            check(b.getX() == l.mapBubble(0).getX(), at + "in the left bubbles' column");
            if (i > 0) {
                Box prev = l.poiButton(i - 1, top);
                check(b.getY() - prev.getBottom() == l.getMargin() && b.getX() == prev.getX(), at + "a margin apart, one above the other");
            }
        }
        check(l.getPoiMarkerSize() >= 16 && l.getPoiMarkerSize() < l.getBubbleSize(), at + "markers smaller than a bubble: " + l.getPoiMarkerSize());
        check(l.getPoiHitRadius() * 2 >= l.getBubbleSize() - 1, at + "but as easy to hit as one");
    }

    private static void theButtonsStayClearOfEverything(DashboardLayout l) {
        String at = size(l);
        int[] tops = {l.poiButtonsTop(false, false), l.poiButtonsTop(true, false), l.poiButtonsTop(true, true)};
        for (int t = 0; t < tops.length; t++) {
            Box row = l.poiButtonRow(tops[t]);
            check(inside(row, l.getMap()), at + "row " + t + " in the pane");
            for (int i = 0; i < DashboardLayout.Companion.getMAP_ACTIONS().size(); i++) {
                check(!overlaps(row, l.mapBubble(i)), at + "row " + t + " clear of map bubble " + i);
            }
            for (int i = 0; i < DashboardLayout.Companion.getZOOM_ACTIONS().size(); i++) {
                check(!overlaps(row, l.zoomBubble(i)), at + "row " + t + " clear of zoom bubble " + i);
            }
            check(row.getBottom() < l.getAttributionTop(), at + "row " + t + " above the ODbL credit");
            check(!overlaps(row, l.getTrafficAttribution()), at + "row " + t + " clear of the HERE credit");
        }
        check(!overlaps(l.poiButtonRow(tops[1]), l.getTurnCard()), at + "under the turn card");
        check(!overlaps(l.poiButtonRow(tops[2]), l.getOfferCard()), at + "under the divided card");
        // Where the column would reach the compass (the stacked card at 1080p) the three sit
        // side by side under the card instead, still from the left edge, a margin apart.
        for (int t = 0; t < tops.length; t++) {
            Box first = l.poiButton(0, tops[t]);
            Box second = l.poiButton(1, tops[t]);
            boolean column = second.getX() == first.getX();
            check(column ? second.getY() - first.getBottom() == l.getMargin() : second.getX() - first.getRight() == l.getMargin() && second.getY() == first.getY(),
                at + "row " + t + " a margin apart either way");
            check(first.getX() == l.mapBubble(0).getX(), at + "row " + t + " from the left bubbles' edge");
        }
        // The vehicle under the card sits at the pane's centre plus markerBelow: the column, at
        // the left edge, stays clear of it.
        int vehicleX = l.getMap().getCenterX();
        check(l.poiButtonRow(tops[1]).getRight() < vehicleX - l.getBubbleSize() / 2, at + "and clear of the vehicle marker on a route");
    }

    private static void theCardFitsTheFlexTile(DashboardLayout l) {
        String at = size(l);
        Box tile = l.getFlexTile();
        Box go = l.poiCardButton(0);
        Box close = l.poiCardButton(1);
        check(inside(go, tile) && inside(close, tile), at + "Go and Close in the tile");
        check(!overlaps(go, close) && close.getX() - go.getRight() == l.getMargin(), at + "a margin apart");
        check(go.getWidth() == close.getWidth(), at + "the same size");
        check(go.getHeight() >= Math.min(l.getBubbleSize(), tile.getHeight() / 4), at + "a bubble tall where the tile allows: " + go.getHeight());
        Box list = l.getPoiCardList();
        check(inside(list, tile) && list.getBottom() < go.getY(), at + "the places above the buttons");
        for (int count = 1; count <= PoiCardContent.MAX_ENTRIES; count++) {
            for (int i = 0; i < count; i++) {
                Box row = l.poiCardRow(i, count);
                check(inside(row, list), at + count + " rows: row " + i + " in the list");
            }
        }
        double nameSize = l.poiCardRow(0, 3).getHeight() * 0.38;
        check(nameSize >= 12, at + "a three-place list's names at least 12 px: " + nameSize);
        check(l.hitPoiCardButton(go.getCenterX(), go.getCenterY()).equals("primary"), at + "Go is hit where drawn");
        check(l.hitPoiCardButton(close.getCenterX(), close.getCenterY()).equals("close"), at + "Close is hit where drawn");
        check(l.hitPoiCardButton(go.getRight() + 1, go.getCenterY()) == null, at + "not in the gap");
        Box second = l.poiCardRow(1, 3);
        check(l.hitPoiCardRow(second.getCenterX(), second.getCenterY(), 3) == 1, at + "rows are hit where drawn");
    }

    private static void theButtonsAreHitWhereTheyAreDrawn() {
        for (DashboardLayout l : everyDisplay()) {
            int top = l.poiButtonsTop(false, false);
            for (int i = 0; i < 3; i++) {
                Box b = l.poiButton(i, top);
                check(l.hitPoiButton(b.getCenterX(), b.getCenterY(), top) == PoiRules.INSTANCE.getORDER().get(i), size(l) + "button " + i);
                check(l.hitPoiButton(b.getCenterX(), b.getBottom(), top) == null, size(l) + "not in the gap below " + i);
            }
            check(l.hitPoiButton(l.getMap().getCenterX(), l.getMap().getCenterY(), top) == null, size(l) + "not on the map's middle");
        }
    }

    private static void aPressFlipsAButtonAndIsHandedOn() {
        DashboardLayout l = new DashboardLayout(74, 0, 726, 480, true);
        Dashboard d = new Dashboard(l, "t", AUSTIN);
        check(d.poiKinds().isEmpty(), "every button off at the start");
        Box gas = l.poiButton(0, l.poiButtonsTop(false, false));
        Box food = l.poiButton(1, l.poiButtonsTop(false, false));
        tap(d, gas, NOON);
        check(d.poiKinds().equals(EnumSet.of(PoiKind.GAS)), "a press lights Gas");
        PoiButtons buttons = d.nextFrame(NOON).getPoiButtons();
        check(buttons != null && buttons.getLit().contains(PoiKind.GAS), "and the frame draws it lit");
        tap(d, food, NOON);
        check(d.poiKinds().equals(EnumSet.of(PoiKind.GAS, PoiKind.FOOD)), "more than one can be on");
        tap(d, gas, NOON);
        check(d.poiKinds().equals(EnumSet.of(PoiKind.FOOD)), "a second press puts it out");
        d.touch("down", gas.getCenterX(), gas.getCenterY(), NOON);
        check(d.nextFrame(NOON).getPoiButtons().getPressed() == PoiKind.GAS, "a finger on it is drawn");
        d.touch("up", food.getCenterX(), food.getCenterY(), NOON);
        check(d.poiKinds().equals(EnumSet.of(PoiKind.FOOD)), "a press lifted on another button does nothing");
        List<kotlin.Pair<PoiKind, Boolean>> toggles = d.takePoiToggles();
        check(toggles.size() == 3 && toggles.get(0).getFirst() == PoiKind.GAS && toggles.get(0).getSecond()
            && toggles.get(2).getFirst() == PoiKind.GAS && !toggles.get(2).getSecond(), "each press handed on in order, with its state");
        check(d.takePoiToggles().isEmpty(), "once");
        // A press on a button is not a pan or a long press of the map under it.
        d.touch("down", gas.getCenterX(), gas.getCenterY(), NOON);
        d.touch("up", gas.getCenterX(), gas.getCenterY(), NOON + 2000);
        check(d.takeMapPick() == null, "a long press on a button picks no destination");
        check(d.takePoiToggles().size() == 1, "and is a press of the button");
        d.poiKinds(EnumSet.of(PoiKind.SHOP));
        check(d.poiKinds().equals(EnumSet.of(PoiKind.SHOP)) && d.takePoiToggles().isEmpty(), "the lab's lighting is not a press");
    }

    private static void theButtonsMoveUnderTheTurnCard() {
        DashboardLayout l = new DashboardLayout(74, 0, 726, 480, true);
        Dashboard d = new Dashboard(l, "t", AUSTIN);
        d.upcomingTurn(guiding());
        PoiButtons buttons = d.nextFrame(NOON).getPoiButtons();
        check(buttons.getTop() == l.poiButtonsTop(true, false), "on a route the row moves under the turn card");
        Box gas = l.poiButton(0, buttons.getTop());
        tap(d, gas, NOON);
        check(d.poiKinds().contains(PoiKind.GAS), "and is hit there");
        Box old = l.poiButton(1, l.poiButtonsTop(false, false));
        tap(d, old, NOON);
        check(!d.poiKinds().contains(PoiKind.FOOD), "not where it was, which is the card's now");
    }

    // --- markers and the card -------------------------------------------------------

    /** A dashboard following the phone at the test point, its camera placed, with these markers. */
    private static Dashboard placed(DashboardLayout l, List<ObfPoi> pois) {
        Dashboard d = new Dashboard(l, "t", AUSTIN);
        d.phone(new PhoneSnapshot(60, false, 3, "cell", LAT, LON, null, null, null, true));
        d.mapCamera();
        d.pois(pois);
        return d;
    }

    private static void aTappedMarkerOpensItsCard() {
        DashboardLayout l = new DashboardLayout(74, 0, 726, 480, true);
        ObfPoi here = poi(1, LAT, LON, NAME, PoiKind.GAS, "fuel", "24/7");
        PoiView view = placed(l, List.of()).poiView();
        double east = LON + (view.getBox().getEast() - view.getBox().getWest()) * 0.3;
        ObfPoi there = poi(2, LAT, east, "Othermarker Cafe", PoiKind.FOOD, "cafe", null);
        Dashboard d = placed(l, List.of(here, there));
        Box map = l.getMap();
        check(d.nextFrame(NOON).getFlex() == FlexSource.EMPTY, "no card before a tap");
        d.touch("down", map.getCenterX() + 3, map.getCenterY() - 2, NOON);
        d.touch("up", map.getCenterX() + 3, map.getCenterY() - 2, NOON + 150);
        Dashboard.Snapshot s = d.nextFrame(NOON + 200);
        check(s.getFlex() == FlexSource.POI, "a tap on a marker shows the card");
        check(s.getFlexLabel().equals("gas"), "labelled with its kind: " + s.getFlexLabel());
        PoiCardView card = s.getPoiCard();
        check(card != null && card.getRows().size() == 1, "one place");
        check(card.getRows().get(0).getName().equals(NAME), "its name");
        check(card.getRows().get(0).getDetail().startsWith("Gas station"), "what it is: " + card.getRows().get(0).getDetail());
        check(card.getRows().get(0).getDetail().endsWith("0 ft"), "and how far, in the speed unit's distance: " + card.getRows().get(0).getDetail());
        check(PoiRules.OPEN_ALL_DAY.equals(card.getRows().get(0).getOpen()), "and the open line its hours give");
        check(card.getPrimary().equals("Go"), "with Go");
        // The other marker, 30% of the pane to the east.
        int x = map.getCenterX() + (int) Math.round(map.getWidth() * 0.3);
        d.touch("down", x, map.getCenterY(), NOON + 1000);
        d.touch("up", x, map.getCenterY(), NOON + 1100);
        s = d.nextFrame(NOON + 1200);
        check(s.getPoiCard().getRows().get(0).getName().equals("Othermarker Cafe"), "another tap replaces the card");
        check(s.getPoiCard().getRows().get(0).getOpen() == null, "with no open line when there are no hours");
        check(s.getFlexLabel().equals("food"), "and its label");
        // Nowhere near a marker: nothing changes.
        d.touch("down", map.getX() + 5, map.getCenterY() + 60, NOON + 2000);
        d.touch("up", map.getX() + 5, map.getCenterY() + 60, NOON + 2100);
        check(d.nextFrame(NOON + 2200).getPoiCard().getRows().get(0).getName().equals("Othermarker Cafe"), "a tap on the bare map leaves it");
        check(s.toString().indexOf("Othermarker") < 0 && s.getPoiCard().toString().indexOf("Othermarker") < 0, "a printed snapshot names no place");
        // A fuel station and its own shop on one spot, and two more under the finger: a list.
        ObfPoi shop = poi(3, LAT, LON + 2e-5, "Shopmarker", PoiKind.SHOP, "convenience", null);
        ObfPoi near = poi(4, LAT, LON + 4e-5, "Nearmarker", PoiKind.FOOD, "cafe", null);
        ObfPoi more = poi(5, LAT, LON + 6e-5, "Moremarker", PoiKind.FOOD, "cafe", null);
        d.pois(List.of(near, shop, here, more, there));
        d.touch("down", map.getCenterX(), map.getCenterY(), NOON + 3000);
        d.touch("up", map.getCenterX(), map.getCenterY(), NOON + 3100);
        s = d.nextFrame(NOON + 3200);
        card = s.getPoiCard();
        check(card.getRows().size() == 3, "a tap on a pile lists three of it: " + card.getRows().size());
        check(card.getRows().get(0).getName().equals(NAME) && card.getRows().get(1).getName().equals("Shopmarker"),
            "nearest first: " + card.getRows().get(0).getName() + ", " + card.getRows().get(1).getName());
        check(s.getFlexLabel().equals(PoiRules.PLACES), "labelled places when the kinds differ: " + s.getFlexLabel());
        check(card.getSelected() == 0, "the nearest chosen");
    }

    private static void aDragOrALongPressOnAMarkerIsTheMaps() {
        DashboardLayout l = new DashboardLayout(0, 0, 800, 480, true);
        Dashboard d = placed(l, List.of(poi(1, LAT, LON, NAME, PoiKind.GAS, "fuel", null)));
        Box map = l.getMap();
        int cx = map.getCenterX();
        int cy = map.getCenterY();
        d.touch("down", cx, cy, NOON);
        d.touch("move", cx + 40, cy, NOON + 50);
        d.touch("move", cx + 80, cy, NOON + 100);
        d.touch("up", cx + 80, cy, NOON + 150);
        check(d.nextFrame(NOON + 200).getFlex() != FlexSource.POI, "a drag that starts on a marker pans, and opens nothing");
        d = placed(l, List.of(poi(1, LAT, LON, NAME, PoiKind.GAS, "fuel", null)));
        d.touch("down", cx, cy, NOON);
        d.touch("up", cx, cy, NOON + 1000);
        check(d.nextFrame(NOON + 1100).getFlex() != FlexSource.POI, "a long press on a marker opens no card");
        check(d.takeMapPick() != null, "it is the map's long press: a destination there");
    }

    private static void goRoutesToTheChosenPlaceAndCloseCloses() {
        DashboardLayout l = new DashboardLayout(74, 0, 726, 480, false);
        ObfPoi place = poi(7, LAT, LON, NAME, PoiKind.GAS, "fuel", null);
        Dashboard d = placed(l, List.of(place));
        openAtCentre(d, l);
        Box go = l.poiCardButton(0);
        d.touch("down", go.getCenterX(), go.getCenterY(), NOON);
        check("primary".equals(d.nextFrame(NOON).getPoiCard().getPressed()), "a finger on Go is drawn");
        d.touch("up", go.getCenterX(), go.getCenterY(), NOON + 100);
        PoiAction action = d.takePoiAction();
        check(action != null && action.getEntry().getPoi() == place, "Go hands on the place");
        check(action.getPrimary().equals("Go") && action.getTag().equals(PoiCardContent.TAG_MAP), "as a map tap's Go");
        check(d.takePoiAction() == null, "once");
        check(d.poiCard() == null && d.nextFrame(NOON + 200).getFlex() != FlexSource.POI, "and the card closes");
        check(!action.toString().contains(NAME), "a printed action names no place");
        openAtCentre(d, l);
        Box close = l.poiCardButton(1);
        d.touch("down", close.getCenterX(), close.getCenterY(), NOON + 300);
        d.touch("up", go.getCenterX(), go.getCenterY(), NOON + 400);
        check(d.poiCard() != null && d.takePoiAction() == null, "a press slid from Close onto Go does nothing");
        tap(d, close, NOON + 500);
        check(d.poiCard() == null && d.takePoiAction() == null, "Close closes, and routes nowhere");
    }

    private static void anUnnamedPlaceIsCalledWhatItIs() {
        check(PoiRules.INSTANCE.title(poi(1, LAT, LON, "", PoiKind.GAS, "fuel", null)).equals("Gas station"), "an unnamed fuel station");
        check(PoiRules.INSTANCE.title(poi(1, LAT, LON, "", PoiKind.FOOD, "fast_food", null)).equals("Fast food"), "unnamed fast food");
        check(PoiRules.INSTANCE.title(poi(1, LAT, LON, " ", PoiKind.SHOP, "supermarket", null)).equals("Supermarket"), "an unnamed supermarket");
        check(PoiRules.INSTANCE.title(poi(1, LAT, LON, "", PoiKind.SHOP, "shop", null)).equals("Shop"), "a shop of no sort");
        ObfPoi branded = new ObfPoi(1, LAT, LON, "", null, "transportation", "fuel", PoiKind.GAS, "Brandmarker", null, "f.obf");
        check(PoiRules.INSTANCE.title(branded).equals("Brandmarker"), "its brand before its kind");
        check(PoiRules.INSTANCE.cardLabel(PoiKind.SHOP).equals("shop") && PoiRules.INSTANCE.cardLabel(null).equals("place"), "tile labels");
    }

    private static void theListTakesThreeAndItsOwnButton() {
        DashboardLayout l = new DashboardLayout(0, 0, 800, 480, true);
        Dashboard d = placed(l, List.of());
        List<PoiEntry> entries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            entries.add(new PoiEntry(poi(10 + i, LAT + i * 0.01, LON, "Stopmarker " + i, PoiKind.GAS, "fuel", null),
                (2 + i) + " mi ahead · " + (3 + i) + " min detour", false));
        }
        d.showPoiCard(new PoiCardContent("gas ahead", entries, PoiCardContent.ADD_STOP, "ahead"), NOON);
        Dashboard.Snapshot s = d.nextFrame(NOON);
        PoiCardView card = s.getPoiCard();
        check(s.getFlex() == FlexSource.POI && s.getFlexLabel().equals("gas ahead"), "a search-ahead card takes the tile with its label");
        check(card.getRows().size() == 3, "three places at most");
        check(card.getRows().get(1).getDetail().equals("3 mi ahead · 4 min detour"), "each with the detail it was given");
        check(card.getRows().get(0).getOpen() == null, "and no open line when asked for none");
        check(card.getSelected() == 0 && card.getPrimary().equals("Add stop"), "the first chosen, under Add stop");
        Box third = l.poiCardRow(2, 3);
        tap(d, third, NOON + 100);
        check(d.nextFrame(NOON + 100).getPoiCard().getSelected() == 2, "a row tap chooses another");
        check(d.takePoiAction() == null, "and starts nothing");
        tap(d, l.poiCardButton(0), NOON + 200);
        PoiAction action = d.takePoiAction();
        check(action != null && action.getEntry().getPoi().getId() == 12, "the primary button acts on the chosen place");
        check(action.getPrimary().equals("Add stop") && action.getTag().equals("ahead"), "with its own word and tag");
        d.showPoiCard(new PoiCardContent("gas ahead", List.of(), PoiCardContent.ADD_STOP, "ahead"), NOON);
        check(d.poiCard() == null, "an empty list shows no card");
    }

    /** P11.5a: nothing ahead is shown too, as a notice: its one line and Close alone. */
    private static void aNoticeHasItsLineAndCloseAlone() {
        for (DashboardLayout l : everyDisplay()) {
            Dashboard d = placed(l, List.of());
            PoiCardContent notice = new PoiCardContent("gas ahead", List.of(), "", "ahead", "None in the next 50 mi");
            check(notice.getNotice(), "no places and a line is a notice");
            d.showPoiCard(notice, NOON);
            Dashboard.Snapshot s = d.nextFrame(NOON);
            PoiCardView card = s.getPoiCard();
            check(s.getFlex() == FlexSource.POI && s.getFlexLabel().equals("gas ahead"), "a notice takes the tile with its label");
            check(card != null && card.getRows().isEmpty() && "None in the next 50 mi".equals(card.getMessage()),
                "with its line and no rows");
            check(!card.toString().contains("None"), "a printed card still says only its shape");
            tap(d, l.poiCardButton(0), NOON + 100);
            check(d.takePoiAction() == null && d.poiCard() != null, "where the primary would be, a tap does nothing");
            check(d.nextFrame(NOON + 100).getPoiCard().getPressed() == null, "and nothing is drawn pressed there");
            check(l.poiCardButton(1).getWidth() > 0 && inside(l.poiCardButton(1), l.getFlexTile()), "Close in its usual place, inside the tile");
            tap(d, l.poiCardButton(1), NOON + 200);
            check(d.poiCard() == null && d.takePoiAction() == null, "Close closes it and starts nothing");
            d.showPoiCard(notice, NOON);
            check(d.nextFrame(NOON + Dashboard.POI_CARD_TIMEOUT_MS + 1_000).getFlex() != FlexSource.POI, "and it times out as a card does");
        }
    }

    private static void aCallWinsAndTheWeatherViewTakesTurns() {
        check(FlexRules.INSTANCE.source(CallState.Companion.getNONE(), false, 2, true, true, true) == FlexSource.POI,
            "the card before the weather view, an alert and notifications");
        check(FlexRules.INSTANCE.source(new CallState(CallPhase.RINGING, "x", -1, false, CallSource.TELECOM), false, 0, false, false, true)
            == FlexSource.CALL, "a call before the card");
        DashboardLayout l = new DashboardLayout(0, 0, 1920, 1080, true);
        Dashboard d = placed(l, List.of(poi(1, LAT, LON, NAME, PoiKind.GAS, "fuel", null)));
        openAtCentre(d, l);
        d.call(new CallState(CallPhase.RINGING, "x", -1, false, CallSource.TELECOM));
        check(d.nextFrame(NOON).getFlex() == FlexSource.CALL, "a ringing call takes the tile");
        d.call(CallState.Companion.getNONE());
        check(d.nextFrame(NOON).getFlex() != FlexSource.POI, "and the card does not come back after it");
        d.weatherChoices(true, AlertLevel.SEVERE);
        openAtCentre(d, l);
        Box item = l.getHeaderWeather();
        tap(d, item, NOON + 10);
        check(d.weatherOpen() && d.poiCard() == null, "opening the weather view closes the card");
        check(d.nextFrame(NOON + 20).getFlex() == FlexSource.WEATHER, "and the tile shows the view");
        openAtCentre(d, l);
        check(!d.weatherOpen() && d.nextFrame(NOON + 30).getFlex() == FlexSource.POI, "a marker tap closes the weather view");
    }

    private static void anUntouchedCardCloses() {
        DashboardLayout l = new DashboardLayout(74, 0, 726, 480, true);
        Dashboard d = placed(l, List.of(poi(1, LAT, LON, NAME, PoiKind.GAS, "fuel", null)));
        openAtCentre(d, l);
        check(d.nextFrame(NOON + Dashboard.POI_CARD_TIMEOUT_MS - 1).getFlex() == FlexSource.POI, "open until the timeout");
        // A press on the card counts as a touch: the clock starts again.
        Box row = l.poiCardRow(0, 1);
        tap(d, row, NOON + Dashboard.POI_CARD_TIMEOUT_MS - 1);
        check(d.nextFrame(NOON + Dashboard.POI_CARD_TIMEOUT_MS + 10).getFlex() == FlexSource.POI, "a touch keeps it");
        check(d.nextFrame(NOON + 2 * Dashboard.POI_CARD_TIMEOUT_MS).getFlex() != FlexSource.POI, "untouched, it closes");
        check(d.report().contains("poi_card_timeouts=1 "), "and the report counts it");
    }

    // --- the query ------------------------------------------------------------------

    private static void theSearchCapsEachKindNearestFirst() {
        List<ObfPoi> a = new ArrayList<>();
        List<ObfPoi> b = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            // Further out with each one; every other one also in the neighbouring file.
            ObfPoi p = poi(100 + i, LAT + (40 - i) * 0.0005, LON, "Gasmarker " + i, PoiKind.GAS, "fuel", null);
            a.add(p);
            if (i % 2 == 0) b.add(poi(100 + i, p.getLatitude(), p.getLongitude(), p.getName(), PoiKind.GAS, "fuel", null));
        }
        for (int i = 0; i < 10; i++) a.add(poi(200 + i, LAT - i * 0.001, LON, "Foodmarker " + i, PoiKind.FOOD, "restaurant", null));
        GeoBox box = new GeoBox(LAT - 0.1, LON - 0.1, LAT + 0.1, LON + 0.1);
        PoiSearch.Result r = PoiSearch.INSTANCE.query(List.of(new FakeSource("a", a, null), new FakeSource("b", b, null)),
            box, EnumSet.of(PoiKind.GAS, PoiKind.FOOD), 25, LAT, LON);
        long gas = r.getPois().stream().filter(p -> p.getKind() == PoiKind.GAS).count();
        long food = r.getPois().stream().filter(p -> p.getKind() == PoiKind.FOOD).count();
        check(gas == 25, "gas capped at 25: " + gas);
        check(food == 10, "food all 10, its own cap");
        check(r.getPois().stream().map(ObfPoi::getId).distinct().count() == r.getPois().size(), "a place in both files once");
        double last = -1;
        boolean ordered = true;
        for (ObfPoi p : r.getPois()) {
            if (p.getKind() != PoiKind.GAS) continue;
            double m = PoiRules.INSTANCE.metres(LAT, LON, p.getLatitude(), p.getLongitude());
            ordered &= m >= last;
            last = m;
        }
        check(ordered, "nearest the centre first");
        check(r.getPois().stream().filter(p -> p.getKind() == PoiKind.GAS).allMatch(p -> p.getId() >= 115), "the 25 nearest, not the first 25 read");
        check(r.getPois().get(0).getKind() == PoiKind.GAS && r.getPois().get(r.getPois().size() - 1).getKind() == PoiKind.FOOD, "listed Gas, Food, Shop");
        // One station mapped as a point and as its outline, 40 m apart: drawn once. Another of
        // the same brand across town, and an unnamed pair, are kept.
        List<ObfPoi> twice = List.of(
            poi(300, LAT, LON, "Gulf", PoiKind.GAS, "fuel", null),
            poi(301, LAT + 0.00036, LON, "Gulf", PoiKind.GAS, "fuel", null),
            poi(302, LAT + 0.01, LON, "Gulf", PoiKind.GAS, "fuel", null),
            poi(303, LAT, LON + 0.0001, "", PoiKind.GAS, "fuel", null),
            poi(304, LAT, LON + 0.0002, "", PoiKind.GAS, "fuel", null));
        List<ObfPoi> once = PoiRules.INSTANCE.nearestFirst(twice, EnumSet.of(PoiKind.GAS), 25, LAT, LON);
        check(once.size() == 4 && once.stream().noneMatch(p -> p.getId() == 301),
            "a place mapped twice is drawn once; the same name far off and unnamed ones are kept: " + once.size());
        check(PoiRules.INSTANCE.nearestFirst(twice, EnumSet.of(PoiKind.GAS), 2, LAT, LON).size() == 2, "the cap still holds");
        PoiSearch.Result shopOnly = PoiSearch.INSTANCE.query(List.of(new FakeSource("a", a, null)), box, EnumSet.of(PoiKind.SHOP), 25, LAT, LON);
        check(shopOnly.getPois().isEmpty(), "only the kinds asked for");
        check(PoiSearch.INSTANCE.query(List.of(new FakeSource("a", a, null)), box, EnumSet.noneOf(PoiKind.class), 25, LAT, LON).getSourcesAsked() == 0,
            "no kinds, no file read");
    }

    private static void theSearchCountsAFailedFileAndSkipsFarOnes() {
        List<ObfPoi> a = List.of(poi(1, LAT, LON, "A", PoiKind.GAS, "fuel", null));
        FakeSource far = new FakeSource("far", a, new GeoBox(40, -80, 41, -79));
        FakeSource broken = new FakeSource("broken", a, null);
        broken.fail = true;
        GeoBox box = new GeoBox(LAT - 0.1, LON - 0.1, LAT + 0.1, LON + 0.1);
        PoiSearch.Result r = PoiSearch.INSTANCE.query(List.of(far, broken, new FakeSource("ok", a, null)), box, EnumSet.of(PoiKind.GAS), 25, LAT, LON);
        check(far.calls == 0, "a file whose bounds miss the box is not read");
        check(r.getSourcesAsked() == 2 && r.getFailures() == 1, "a file that throws is counted");
        check(r.getPois().size() == 1, "and the others still give their markers");
    }

    private static PoiView view(Set<PoiKind> kinds, double lat, double lon, double zoom, boolean following) {
        return view(kinds, lat, lon, zoom, following, 0.01);
    }

    private static PoiView view(Set<PoiKind> kinds, double lat, double lon, double zoom, boolean following, double half) {
        return new PoiView(kinds, new GeoBox(lat - half, lon - half, lat + half, lon + half), lat, lon, zoom, following);
    }

    private static final class Feed {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final List<ObfPoi> data = new ArrayList<>();
        final FakeSource source = new FakeSource("f", data, null);
        final PoiFeed feed = new PoiFeed(() -> List.of(source), () -> 25, queue::add, () -> 0L);

        void drain() {
            while (!queue.isEmpty()) queue.poll().run();
        }
    }

    private static void theFeedAsksAtOnceOnAPress() {
        Feed f = new Feed();
        f.data.add(poi(1, LAT, LON, "A", PoiKind.GAS, "fuel", null));
        f.data.add(poi(2, LAT, LON, "B", PoiKind.FOOD, "cafe", null));
        f.feed.frame(view(Set.of(), LAT, LON, 16, true), 0);
        check(f.queue.isEmpty(), "nothing asked with every button off");
        f.feed.frame(view(EnumSet.of(PoiKind.GAS), LAT, LON, 16, true), 10);
        check(f.queue.size() == 1, "a press asks at once, moving or not");
        f.drain();
        check(f.feed.getShown().getPois().size() == 1 && f.feed.current(PoiKind.GAS).size() == 1, "the gas marker is shown");
        f.feed.frame(view(EnumSet.of(PoiKind.GAS), LAT, LON, 16, true), 20);
        check(f.queue.isEmpty(), "and not again for the same view");
        f.feed.frame(view(EnumSet.of(PoiKind.GAS, PoiKind.FOOD), LAT, LON, 16, true), 30);
        f.drain();
        check(f.feed.getShown().getPois().size() == 2, "a second button adds its kind");
        f.feed.frame(view(EnumSet.of(PoiKind.FOOD), LAT, LON, 16, true), 40);
        check(f.feed.current(PoiKind.GAS).isEmpty(), "a button put out takes its markers at once, before the query returns");
        f.drain();
        f.feed.frame(view(Set.of(), LAT, LON, 16, true), 50);
        check(f.feed.getShown().getPois().isEmpty() && f.queue.isEmpty(), "all off clears the map and asks nothing");
    }

    private static void theFeedWaitsForTheMapToStop() {
        Feed f = new Feed();
        Set<PoiKind> gas = EnumSet.of(PoiKind.GAS);
        f.feed.frame(view(gas, LAT, LON, 16, false), 0);
        f.drain();
        int asked = f.source.calls;
        // A pan well off the queried box, moving every frame for two seconds.
        double at = LAT;
        long last = 0;
        for (long t = 0; t <= 2000; t += 33) {
            at = LAT + 0.02 + t * 1e-6;
            last = 100 + t;
            f.feed.frame(view(gas, at, LON, 16, false), last);
        }
        check(f.queue.isEmpty(), "no query while the driver is still panning");
        f.feed.frame(view(gas, at, LON, 16, false), last + PoiFeed.SETTLE_MS - 10);
        check(f.queue.isEmpty(), "nor before it has been still for 500 ms");
        f.feed.frame(view(gas, at, LON, 16, false), last + PoiFeed.SETTLE_MS + 40);
        check(f.queue.size() == 1, "then one");
        f.drain();
        check(f.source.calls == asked + 1, "and only one");
        f.feed.frame(view(gas, at + 0.001, LON, 16, false), 5000);
        f.feed.frame(view(gas, at + 0.001, LON, 16, false), 6000);
        check(f.queue.isEmpty(), "a nudge inside the queried box asks nothing");
        f.feed.frame(view(gas, at + 0.001, LON, 17, false), 7000);
        f.feed.frame(view(gas, at + 0.001, LON, 17, false), 7600);
        check(f.queue.size() == 1, "a zoom level apart asks again once still");
        f.drain();
    }

    private static void theFeedRefreshesAtMostEveryTenSecondsWhileFollowing() {
        Feed f = new Feed();
        Set<PoiKind> gas = EnumSet.of(PoiKind.GAS);
        // A pane about 440 m tall, as at the follow view's zoom.
        f.feed.frame(view(gas, LAT, LON, 16, true, 0.002), 0);
        f.drain();
        int queries = 0;
        // Driving north at about 80 km/h for a minute: the view moves every frame, 30 a second.
        for (long t = 33; t <= 60_000; t += 33) {
            f.feed.frame(view(gas, LAT + t * 2e-7, LON, 16, true, 0.002), t);
            if (!f.queue.isEmpty()) {
                queries++;
                f.drain();
            }
        }
        check(queries >= 4 && queries <= 7, "while following, about every 10 s: " + queries + " in a minute");
        check(f.feed.report().contains("poi_queries=" + (queries + 1) + " "), "each counted");
    }

    private static void theFeedDropsStaleResults() {
        Feed f = new Feed();
        f.data.add(poi(1, LAT, LON, "A", PoiKind.GAS, "fuel", null));
        f.data.add(poi(2, LAT, LON, "B", PoiKind.FOOD, "cafe", null));
        f.feed.frame(view(EnumSet.of(PoiKind.GAS), LAT, LON, 16, true), 0);
        f.feed.frame(view(EnumSet.of(PoiKind.FOOD), LAT, LON, 16, true), 10);
        check(f.queue.size() == 2, "two queries asked, the first still pending");
        f.queue.poll().run();
        check(f.feed.getShown().getPois().isEmpty(), "the first, for a button since put out, is dropped");
        f.queue.poll().run();
        check(f.feed.current(PoiKind.FOOD).size() == 1 && f.feed.current(PoiKind.GAS).isEmpty(), "the second is shown");
        check(f.feed.report().contains("poi_stale_dropped=1 "), "and the drop counted");
        f.feed.frame(view(EnumSet.of(PoiKind.GAS), LAT, LON, 16, true), 20);
        f.feed.frame(view(Set.of(), LAT, LON, 16, true), 30);
        f.drain();
        check(f.feed.getShown().getPois().isEmpty(), "a query returning after every button went out shows nothing");
    }

    private static void theFeedTakesExtraMarkersForSearchAhead() {
        Feed f = new Feed();
        f.data.add(poi(1, LAT, LON, "A", PoiKind.GAS, "fuel", null));
        f.feed.frame(view(EnumSet.of(PoiKind.GAS), LAT, LON, 16, true), 0);
        f.drain();
        f.feed.extra(List.of(poi(1, LAT, LON, "A", PoiKind.GAS, "fuel", null), poi(9, LAT + 0.2, LON, "Z", PoiKind.GAS, "fuel", null)), 9L);
        check(f.feed.getShown().getPois().size() == 2, "extra markers join the pane's, each place once");
        check(Long.valueOf(9L).equals(f.feed.getShown().getEmphasised()), "with the best one drawn larger");
        f.feed.extra(List.of(), null);
        check(f.feed.getShown().getPois().size() == 1 && f.feed.getShown().getEmphasised() == null, "and go when emptied");
    }

    // --- words and colours ----------------------------------------------------------

    private static void openingHoursOnlyWhenTheyParse() {
        PoiRules r = PoiRules.INSTANCE;
        // NOON is Saturday 12:00 in Austin.
        check(PoiRules.OPEN_ALL_DAY.equals(r.openNow("24/7", NOON, AUSTIN)), "24/7");
        check(PoiRules.OPEN.equals(r.openNow("Mo-Su 06:00-23:00", NOON, AUSTIN)), "every day, open");
        check(PoiRules.CLOSED.equals(r.openNow("Mo-Fr 07:00-19:00", NOON, AUSTIN)), "weekdays only, shut on Saturday");
        check(PoiRules.OPEN.equals(r.openNow("Mo-Fr 07:00-19:00; Sa,Su 10:00-16:00", NOON, AUSTIN)), "a weekend rule");
        check(PoiRules.CLOSED.equals(r.openNow("Mo-Su 06:00-23:00; Sa 14:00-18:00", NOON, AUSTIN)), "a later rule replaces an earlier one");
        check(PoiRules.OPEN.equals(r.openNow("11:00-14:00,17:00-22:00", NOON, AUSTIN)), "no days means every day, split hours");
        check(PoiRules.OPEN.equals(r.openNow("Fr 18:00-02:00", NOON - 11 * 3_600_000L, AUSTIN)), "past midnight, from the night before");
        check(PoiRules.CLOSED.equals(r.openNow("Fr 18:00-02:00", NOON - 6 * 3_600_000L, AUSTIN)), "and shut after it");
        for (String hard : new String[] {"Mo-Fr 08:00-17:00; PH off", "sunrise-sunset", "Mo-Fr 08:00+", "Mo-Fr 08:00-17:00 \"call\"",
            "Jan-Mar Mo 10:00-12:00", "", "  ", "Mo-Xx 10:00-12:00", "Mo 25:00-26:00"}) {
            check(r.openNow(hard, NOON, AUSTIN) == null, "not guessed: \"" + hard + "\"");
        }
        check(r.openNow(null, NOON, AUSTIN) == null, "no hours, no line");
        check(r.parseKinds("gas,food,shop").equals(EnumSet.allOf(PoiKind.class)) && r.parseKinds("Gas, fuel").equals(EnumSet.of(PoiKind.GAS))
            && r.parseKinds(null).isEmpty(), "LAB_POI's kinds");
        check(r.buttonLabel(PoiKind.FOOD).equals("Food"), "button words");
    }

    private static void theMarkerColoursReadOnBothGrounds() {
        for (PoiKind kind : PoiKind.values()) {
            int c = PoiRules.INSTANCE.colour(kind);
            check(DashboardPalette.Companion.contrast(c, NIGHT_GROUND) >= 2.5, kind + " stands off the night ground: " + DashboardPalette.Companion.contrast(c, NIGHT_GROUND));
            check(DashboardPalette.Companion.contrast(c, 0xFFFFFFFF) >= 2.5, kind + " holds a white glyph: " + DashboardPalette.Companion.contrast(c, 0xFFFFFFFF));
            check(distance(c, DAY_GROUND) > 150 && distance(c, NIGHT_GROUND) > 150, kind + " far from both grounds in colour");
            check(distance(c, ROUTE) > 150, kind + " far from the route's cyan");
            check(distance(c, DashboardPalette.Companion.getDAY().getAccent()) > 120
                && distance(c, DashboardPalette.Companion.getNIGHT().getAccent()) > 120, kind + " far from both accents, which light the buttons");
            for (PoiKind other : PoiKind.values()) {
                if (other != kind) check(distance(c, PoiRules.INSTANCE.colour(other)) > 150, kind + " apart from " + other);
            }
        }
    }

    private static void theReportCountsAndNamesNothing() {
        DashboardLayout l = new DashboardLayout(74, 0, 726, 480, true);
        Dashboard d = placed(l, List.of(poi(1, LAT, LON, NAME, PoiKind.GAS, "fuel", "Mo-Su 06:00-22:00")));
        tap(d, l.poiButton(0, l.poiButtonsTop(false, false)), NOON);
        openAtCentre(d, l);
        d.nextFrame(NOON + 500);
        tap(d, l.poiCardButton(0), NOON + 600);
        String report = d.report();
        check(report.contains("poi_on=gas "), "the buttons lit");
        check(report.contains("poi_button_presses=gas:1,food:0,shop:0 "), "presses per kind");
        check(report.contains("poi_taps=1 ") && report.contains("poi_primary_presses=1 ") && report.contains("poi_markers_shown=1 "),
            "taps, Go presses and markers: " + report.substring(report.indexOf("poi_on")));
        Box row = l.poiButtonRow(l.poiButtonsTop(false, false));
        check(report.contains("poi_buttons=" + row.getX() + "," + row.getY() + "+" + row.getWidth() + "x" + row.getHeight()), "where the buttons are, for the lab");
        Feed f = new Feed();
        f.data.add(poi(1, LAT, LON, NAME, PoiKind.GAS, "fuel", null));
        f.feed.frame(view(EnumSet.of(PoiKind.GAS), LAT, LON, 16, true), 0);
        f.drain();
        String feed = f.feed.report();
        check(feed.contains("poi_queries=1 ") && feed.contains("poi_query_ms_p50=0 ") && feed.contains("poi_query_ms_p95=0 "), "query counts and times: " + feed);
        for (String never : new String[] {"Placemarker", "Fuel", "30.26", "97.74", "fuel.obf", "Gas station"}) {
            check(!report.contains(never) && !feed.contains(never), "no report carries " + never);
        }
    }

    // --- helpers --------------------------------------------------------------------

    private static ObfPoi poi(long id, double lat, double lon, String name, PoiKind kind, String sub, String hours) {
        String category = kind == PoiKind.GAS ? "transportation" : kind == PoiKind.FOOD ? "sustenance" : "shop";
        return new ObfPoi(id, lat, lon, name, null, category, sub, kind, null, hours, "fuel.obf");
    }

    private static UpcomingTurn guiding() {
        return new UpcomingTurn("Turn right onto Main Street", 480.0, Maneuvers.TURN_NORMAL_RIGHT, 0, "Main Street",
            4023.0, 600, List.<LaterStep>of());
    }

    /** Taps the marker at the pane's centre (the followed position) and checks the card opened. */
    private static void openAtCentre(Dashboard d, DashboardLayout l) {
        Box map = l.getMap();
        d.touch("down", map.getCenterX(), map.getCenterY(), NOON);
        d.touch("up", map.getCenterX(), map.getCenterY(), NOON + 100);
        if (d.poiCard() == null) throw new AssertionError("no card opened at the centre");
    }

    private static void tap(Dashboard d, Box b, long at) {
        d.touch("down", b.getCenterX(), b.getCenterY(), at);
        d.touch("up", b.getCenterX(), b.getCenterY(), at + 50);
    }

    /** An ObfSource over a list, honouring the box, the kinds and the limit as the real reader does. */
    private static final class FakeSource implements ObfSource {
        private final String name;
        private final List<ObfPoi> pois;
        private final GeoBox bounds;
        int calls;
        boolean fail;

        FakeSource(String name, List<ObfPoi> pois, GeoBox bounds) {
            this.name = name;
            this.pois = pois;
            this.bounds = bounds;
        }

        @Override public String getName() { return name; }
        @Override public GeoBox getBounds() { return bounds; }
        @Override public List<ObfCity> cities() { return List.of(); }
        @Override public List<ObfStreet> streets(ObfCity city) { return List.of(); }
        @Override public List<ObfBuilding> buildings(ObfStreet street) { return List.of(); }

        @Override
        public List<ObfPoi> pois(GeoBox box, Set<? extends PoiKind> kinds, int limit, double lat, double lon, String query) {
            calls++;
            if (fail) throw new IllegalStateException("short read");
            List<ObfPoi> out = new ArrayList<>();
            for (PoiKind kind : PoiKind.values()) {
                if (!kinds.contains(kind)) continue;
                pois.stream().filter(p -> p.getKind() == kind && box.contains(p.getLatitude(), p.getLongitude()))
                    .sorted((x, y) -> Double.compare(PoiRules.INSTANCE.metres(lat, lon, x.getLatitude(), x.getLongitude()),
                        PoiRules.INSTANCE.metres(lat, lon, y.getLatitude(), y.getLongitude())))
                    .limit(limit).forEach(out::add);
            }
            return out;
        }
    }

    private static double distance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static boolean inside(Box inner, Box outer) {
        return inner.getX() >= outer.getX() && inner.getY() >= outer.getY()
            && inner.getRight() <= outer.getRight() && inner.getBottom() <= outer.getBottom();
    }

    private static boolean overlaps(Box a, Box b) {
        return a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom();
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
