// P11.5a on a desktop JVM (docs/navigation.md, "OsmAnd maps: search and places"):
// search ahead on the route. The
// corridor - places projected onto the route ahead of the vehicle as metres ahead, metres off
// and the side of the road, behind and off-corridor ones dropped, one per place - its query
// boxes and how they end at the destination; the detour's A and B and their clamping; the
// detour arithmetic and the two short routes asked for each place; the best-stop rule; what
// is said and shown in miles and kilometres, with and without a name or a stop; the via
// point's release on reaching or passing it; and that the report holds counts only.
//
// ROUTING_SEGMENTS (W100_N30.rd5) and OBF_DIR (Us_texas_austin_northamerica.obf) add a real
// search: a route north on I-35 from downtown Austin toward Round Rock, the gas stations in
// its corridor, the detours BRouter gives the nearest five, and the time each part took.
// AheadSearcher's threads, the speaker and Navigation.addStop need Android: the lab.
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import com.jw6ventures.headlink.AheadCorridor;
import com.jw6ventures.headlink.AheadCounts;
import com.jw6ventures.headlink.AheadDetours;
import com.jw6ventures.headlink.AheadHit;
import com.jw6ventures.headlink.AheadHits;
import com.jw6ventures.headlink.AheadSearch;
import com.jw6ventures.headlink.AheadStop;
import com.jw6ventures.headlink.DetourLeg;
import com.jw6ventures.headlink.GeoBox;
import com.jw6ventures.headlink.LegRouter;
import com.jw6ventures.headlink.ObfFile;
import com.jw6ventures.headlink.ObfPoi;
import com.jw6ventures.headlink.ObfSource;
import com.jw6ventures.headlink.PoiKind;
import com.jw6ventures.headlink.RoadSide;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteOptions;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.Router;
import com.jw6ventures.headlink.RoutingData;
import com.jw6ventures.headlink.RoutingException;
import com.jw6ventures.headlink.SpeedUnit;
import com.jw6ventures.headlink.ViaRelease;

public final class SearchAheadTest {
    private static int passed;

    /** Metres per degree of latitude, on RouteFollower's sphere. */
    private static final double M_PER_DEG = 6_371_000.0 * Math.PI / 180.0;
    private static final double LAT0 = 30.26;
    private static final double LON0 = -97.736;
    private static final double SPEED = 25.0; // m/s on the synthetic road
    private static final String NAME = "Placemarker Fuel";

    public static void main(String[] args) throws Exception {
        projectionAheadBehindOffAndSide();
        theCorridorKeepsOnePerPlaceNearestFirst();
        piecesCoverTheCorridorAndStopAtTheDestination();
        aAndBAreTwoKilometresEitherSideAndClamped();
        theDetourIsThereAndOnLessTheRoutesOwnTime();
        twoShortRoutesPerPlaceAndFailuresDropped();
        theBestStopRule();
        wordsInMilesAndKilometres();
        noStopWording();
        theViaPointIsLetGo();
        theReportHoldsCountsOnly();
        String segments = args.length > 0 ? args[0] : "";
        String obf = args.length > 1 ? args[1] : "";
        if (!segments.isEmpty() && new File(segments, "W100_N30.rd5").isFile()
            && !obf.isEmpty() && new File(obf, "Us_texas_austin_northamerica.obf").isFile()) {
            realSearchNorthOnI35(new File(segments), new File(obf, "Us_texas_austin_northamerica.obf"));
            System.out.println("jni-host-test: SearchAheadTest passed " + passed + " checks (with the real I-35 search)");
        } else {
            System.out.println("jni-host-test: SearchAheadTest passed " + passed
                + " checks (real I-35 search skipped: ROUTING_SEGMENTS with W100_N30.rd5 and OBF_DIR with the Austin file not given)");
        }
    }

    // --- A synthetic road: due north from LAT0, a point every 100 m, 25 m/s. ---------------

    private static Route north(int points) {
        double[] lat = new double[points];
        double[] lon = new double[points];
        float[] seconds = new float[points];
        for (int i = 0; i < points; i++) {
            lat[i] = LAT0 + i * 100.0 / M_PER_DEG;
            lon[i] = LON0;
            seconds[i] = (float) (i * 100.0 / SPEED);
        }
        return new Route(lat, lon, (points - 1) * 100, (int) ((points - 1) * 100 / SPEED), new int[0], new int[0], new int[0], seconds);
    }

    /** A place [along] metres up the road and [east] metres east of it (west when negative). */
    private static ObfPoi place(long id, double along, double east, String name, PoiKind kind) {
        double lat = LAT0 + along / M_PER_DEG;
        double lon = LON0 + east / (M_PER_DEG * Math.cos(Math.toRadians(lat)));
        String category = kind == PoiKind.GAS ? "transportation" : kind == PoiKind.FOOD ? "sustenance" : "shop";
        String sub = kind == PoiKind.GAS ? "fuel" : kind == PoiKind.FOOD ? "fast_food" : "supermarket";
        return new ObfPoi(id, lat, lon, name, null, category, sub, kind, null, null, "lab.obf");
    }

    private static void projectionAheadBehindOffAndSide() {
        AheadCorridor c = new AheadCorridor(north(201), 1000.0, 10_000.0); // 20 km of road, vehicle at 1 km
        check(near(c.getStartM(), 1000, 0.5) && near(c.getEndM(), 11_000, 0.5), "the corridor runs 1 km to 11 km along");
        check(!c.getReachesDestination(), "a 10 km look-ahead on a 20 km route stops short of the destination");
        AheadCorridor.Projection east = c.project(LAT0 + 5000 / M_PER_DEG, LON0 + 100 / (M_PER_DEG * Math.cos(Math.toRadians(LAT0 + 0.045))));
        check(near(east.getAheadM(), 4000, 2), "a place at 5 km is 4 km ahead of a vehicle at 1 km: " + east.getAheadM());
        check(near(east.getOffsetM(), 100, 2), "and 100 m off the line: " + east.getOffsetM());
        check(east.getSide() == RoadSide.RIGHT, "east of a road going north is on the right");
        ObfPoi west = place(2, 5000, -100, NAME, PoiKind.GAS);
        check(c.project(west.getLatitude(), west.getLongitude()).getSide() == RoadSide.LEFT, "west of it is on the left");
        ObfPoi behind = place(3, 400, 20, NAME, PoiKind.GAS);
        AheadCorridor.Projection back = c.project(behind.getLatitude(), behind.getLongitude());
        check(back.getAheadM() == 0.0 && near(back.getOffsetM(), Math.hypot(600, 20), 3),
            "a place behind the vehicle projects onto the corridor's start, 0 ahead: " + back.getAheadM());
        // A road going east: north of it is the left.
        Route eastward = new Route(new double[] {LAT0, LAT0}, new double[] {LON0, LON0 + 0.05}, 4800, 200, new int[0], new int[0], new int[0]);
        AheadCorridor e = new AheadCorridor(eastward, 0.0, 10_000.0);
        check(e.project(LAT0 + 0.001, LON0 + 0.02).getSide() == RoadSide.LEFT, "north of a road going east is on the left");
        check(e.project(LAT0 - 0.001, LON0 + 0.02).getSide() == RoadSide.RIGHT, "south of it is on the right");
        check(e.getReachesDestination(), "a 10 km look-ahead on a 4.8 km route reaches the destination");
    }

    private static void theCorridorKeepsOnePerPlaceNearestFirst() {
        AheadCorridor c = new AheadCorridor(north(201), 1000.0, 10_000.0);
        double width = 0.5 * AheadSearch.METRES_PER_MILE;
        List<ObfPoi> found = List.of(
            place(10, 8000, 300, "Far", PoiKind.GAS),
            place(11, 3000, -200, "Near", PoiKind.GAS),
            place(11, 3000, -200, "Near", PoiKind.GAS), // the same id again (two files overlap)
            place(12, 3030, -190, "Near", PoiKind.GAS), // one station mapped twice, 30 m apart
            place(13, 3030, 190, "Other", PoiKind.GAS), // another name across the road: kept
            place(14, 600, 0, "Behind", PoiKind.GAS), // behind the vehicle
            place(15, 1200, 50, "TooClose", PoiKind.GAS), // 200 m ahead: too late to turn
            place(16, 6000, 1000, "OffCorridor", PoiKind.GAS), // 1 km off a 0.5 mile corridor
            place(17, 6000, 700, "InCorridor", PoiKind.GAS), // 700 m off: inside
            place(18, 4000, 0, "Burger", PoiKind.FOOD), // another kind
            place(19, 12_000, 0, "PastTheEnd", PoiKind.GAS), // 1 km past the corridor's end
            place(20, 9000, 0, "", PoiKind.GAS), // unnamed
            place(21, 9010, 0, "", PoiKind.GAS)); // unnamed, 10 m on: two places, kept
        List<AheadHit> hits = AheadSearch.INSTANCE.inCorridor(c, found, PoiKind.GAS, width);
        List<String> names = new ArrayList<>();
        for (AheadHit h : hits) names.add(h.getPoi().getName());
        check(names.equals(List.of("Near", "Other", "InCorridor", "Far", "", "")),
            "ahead, inside the corridor, one per place, nearest first: " + names);
        for (int i = 1; i < hits.size(); i++) check(hits.get(i).getAheadM() >= hits.get(i - 1).getAheadM(), "in order of distance ahead");
        check(hits.get(0).getSide() == RoadSide.LEFT && hits.get(1).getSide() == RoadSide.RIGHT, "each with its side");
    }

    private static void piecesCoverTheCorridorAndStopAtTheDestination() {
        AheadCorridor c = new AheadCorridor(north(201), 1000.0, 10_000.0);
        double pad = 800;
        List<GeoBox> boxes = c.pieces(AheadSearch.PIECE_M, pad);
        check(boxes.size() == 4, "10 km in 3 km pieces is four boxes: " + boxes.size());
        double padLat = pad / M_PER_DEG;
        check(near(boxes.get(0).getSouth(), LAT0 + 1000 / M_PER_DEG - padLat, 1e-6), "the first starts at the vehicle, padded");
        check(near(boxes.get(3).getNorth(), LAT0 + 11_000 / M_PER_DEG + padLat, 1e-6), "the last ends at the corridor's end, padded");
        double width = boxes.get(0).getEast() - boxes.get(0).getWest();
        check(near(width * M_PER_DEG * Math.cos(Math.toRadians(LAT0)), 2 * pad, 5), "each box is the corridor's width either side");
        for (int i = 1; i < boxes.size(); i++) check(boxes.get(i).getSouth() < boxes.get(i - 1).getNorth(), "neighbouring pieces overlap");
        // Near the destination: 1.9 km left of the route, with 50 miles asked for.
        AheadCorridor end = new AheadCorridor(north(201), 18_100.0, 50 * AheadSearch.METRES_PER_MILE);
        check(end.getReachesDestination() && near(end.getLengthM(), 1900, 1), "50 miles asked, 1.9 km left: to the destination");
        List<GeoBox> last = end.pieces(AheadSearch.PIECE_M, pad);
        check(last.size() == 1 && near(last.get(0).getNorth(), LAT0 + 20_000 / M_PER_DEG + padLat, 1e-6), "one piece, ending at the destination");
        AheadCorridor arrived = new AheadCorridor(north(201), 20_000.0, 50 * AheadSearch.METRES_PER_MILE);
        check(arrived.pieces(AheadSearch.PIECE_M, pad).isEmpty() && arrived.project(LAT0, LON0) == null, "nothing left: no box, no projection");
    }

    private static AheadHit hitAt(AheadCorridor c, double along) {
        ObfPoi p = place(30, along, 80, NAME, PoiKind.GAS);
        AheadCorridor.Projection pr = c.project(p.getLatitude(), p.getLongitude());
        return new AheadHit(p, pr.getAheadM(), pr.getOffsetM(), pr.getSide(), pr.getAlongM());
    }

    private static void aAndBAreTwoKilometresEitherSideAndClamped() {
        AheadCorridor c = new AheadCorridor(north(201), 1000.0, 50 * AheadSearch.METRES_PER_MILE);
        DetourLeg mid = AheadSearch.INSTANCE.leg(c, hitAt(c, 8000));
        check(near(mid.getAAlongM(), 6000, 1) && near(mid.getBAlongM(), 10_000, 1), "A 2 km before and B 2 km after: " + mid.getAAlongM() + " " + mid.getBAlongM());
        check(near(mid.getALat(), LAT0 + 6000 / M_PER_DEG, 1e-7) && near(mid.getBLat(), LAT0 + 10_000 / M_PER_DEG, 1e-7), "at those points of the line");
        check(near(mid.getBaseSeconds(), 4000 / SPEED, 0.5), "the route's own 4 km takes 160 s: " + mid.getBaseSeconds());
        check(mid.getABearing() < 1 || mid.getABearing() > 359, "the way at A is north: " + mid.getABearing());
        DetourLeg early = AheadSearch.INSTANCE.leg(c, hitAt(c, 1800));
        check(near(early.getAAlongM(), 1000, 1), "A no further back than the vehicle: " + early.getAAlongM());
        check(near(early.getBaseSeconds(), (3800 - 1000) / SPEED, 0.5), "and the base time from there");
        DetourLeg late = AheadSearch.INSTANCE.leg(c, hitAt(c, 19_200));
        check(near(late.getBAlongM(), 20_000, 1), "B no further than the destination: " + late.getBAlongM());
        check(late.getABearing() < 1 || late.getABearing() > 359, "the way at A near the end still measured: " + late.getABearing());
    }

    private static void theDetourIsThereAndOnLessTheRoutesOwnTime() {
        check(AheadSearch.INSTANCE.detourSeconds(100, 150, 160.0) == 90, "100 + 150 - 160 = 90 s");
        check(AheadSearch.INSTANCE.detourSeconds(70, 80, 160.0) == 0, "never below 0, when the short routes beat the route");
        check(AheadSearch.INSTANCE.detourSeconds(100, 100, 19.6) == 180, "rounded to the second");
        check(AheadSearch.INSTANCE.minutes(20) == 1 && AheadSearch.INSTANCE.minutes(0) == 1, "a detour is said as at least 1 minute");
        check(AheadSearch.INSTANCE.minutes(150) == 3 && AheadSearch.INSTANCE.minutes(149) == 2, "minutes rounded");
    }

    private static void twoShortRoutesPerPlaceAndFailuresDropped() {
        AheadCorridor c = new AheadCorridor(north(201), 1000.0, 50 * AheadSearch.METRES_PER_MILE);
        List<AheadHit> hits = List.of(hitAt(c, 5000), hitAt(c, 9000), hitAt(c, 13_000));
        List<RouteRequest> asked = new ArrayList<>();
        LegRouter router = request -> {
            asked.add(request);
            if (asked.size() == 3) throw new RoutingException("no route found");
            int seconds = request.getStartBearing() != null ? 100 : 130;
            return new Route(new double[] {request.getFromLat(), request.getToLat()}, new double[] {request.getFromLon(), request.getToLon()},
                1000, seconds, new int[0], new int[0], new int[0]);
        };
        AheadDetours out = AheadSearch.INSTANCE.detours(router, c, hits, new RouteOptions(), List.of(), () -> false);
        // The second place's first route fails: its second is never asked for.
        check(asked.size() == 5, "two routes a place, none after a failure: " + asked.size());
        check(out.getRouted() == 2 && out.getFailed() == 1 && out.getStops().size() == 2 && !out.getCancelled(),
            "two routed, one failed and dropped");
        RouteRequest first = asked.get(0);
        DetourLeg leg = AheadSearch.INSTANCE.leg(c, hits.get(0));
        check(first.getFromLat() == leg.getALat() && first.getToLat() == hits.get(0).getPoi().getLatitude()
            && first.getStartBearing() != null, "A to the place, heading the route's way");
        RouteRequest second = asked.get(1);
        check(second.getFromLat() == hits.get(0).getPoi().getLatitude() && second.getToLat() == leg.getBLat()
            && second.getStartBearing() == null, "then the place to B");
        check(out.getStops().get(0).getDetourSeconds() == 100 + 130 - 160, "the detour from the two: "
            + out.getStops().get(0).getDetourSeconds());
        check(first.getViaLat() == null, "no via point on a short route");
        int[] calls = {0};
        AheadDetours stopped = AheadSearch.INSTANCE.detours(request -> {
            calls[0]++;
            return new Route(new double[] {30, 30.01}, new double[] {-97, -97}, 1000, 60, new int[0], new int[0], new int[0]);
        }, c, hits, new RouteOptions(), List.of(), () -> calls[0] >= 1);
        check(stopped.getCancelled() && calls[0] == 1, "a cancel stops before the next route");
    }

    private static AheadStop stop(double aheadM, int detour) {
        ObfPoi p = place((long) aheadM, aheadM, 50, NAME, PoiKind.GAS);
        return new AheadStop(new AheadHit(p, aheadM, 50, RoadSide.RIGHT, aheadM), detour);
    }

    private static void theBestStopRule() {
        AheadStop near = stop(4000, 300);
        AheadStop far = stop(9000, 200);
        check(AheadSearch.INSTANCE.best(List.of(far, near)) == near, "a nearer stop within 2 min of the least detour comes first");
        AheadStop nearSlow = stop(4000, 330);
        check(AheadSearch.INSTANCE.best(List.of(nearSlow, far)) == far, "but not one 2 min 10 s worse");
        AheadStop tieFar = stop(9000, 200);
        AheadStop tieNear = stop(6000, 200);
        check(AheadSearch.INSTANCE.best(List.of(tieFar, tieNear)) == tieNear, "of equal detours, the nearer");
        check(AheadSearch.INSTANCE.best(List.of()) == null, "none of none");
        AheadStop sameExit = stop(4000, 107);
        AheadStop sameExitBetter = stop(4030, 52);
        check(AheadSearch.INSTANCE.best(List.of(sameExit, sameExitBetter)) == sameExitBetter,
            "two stations at one exit: the smaller detour, though the other is 30 m nearer");
        AheadStop halfKmNearer = stop(4000, 300);
        AheadStop better = stop(4500, 200);
        check(AheadSearch.INSTANCE.best(List.of(halfKmNearer, better)) == better, "500 m nearer is not nearer enough");
        AheadStop a = stop(2000, 600);
        AheadStop b = stop(3000, 100);
        AheadStop c = stop(5000, 150);
        AheadStop d = stop(7000, 500);
        List<AheadStop> card = AheadSearch.INSTANCE.cardStops(List.of(d, c, b, a));
        check(card.size() == 3 && card.get(0) == b && card.get(1) == a && card.get(2) == c,
            "the card: the best, then the rest nearest first, three at most");
    }

    private static void wordsInMilesAndKilometres() {
        ObfPoi bucees = new ObfPoi(1, 30.5, -97.7, "Buc-ee's", null, "transportation", "fuel", PoiKind.GAS, null, null, "lab.obf");
        AheadStop s = new AheadStop(new AheadHit(bucees, 12 * AheadSearch.METRES_PER_MILE, 60, RoadSide.RIGHT, 0), 180);
        String said = AheadSearch.INSTANCE.spoken(PoiKind.GAS, s, SpeedUnit.MPH, 80_000, false);
        check(said.equals("Gas along your route in 12 miles: Buc-ee's, about a 3-minute detour."), said);
        check(AheadSearch.INSTANCE.detail(s, SpeedUnit.MPH).equals("12 mi ahead · 3 min detour · right"), AheadSearch.INSTANCE.detail(s, SpeedUnit.MPH));
        AheadStop k = new AheadStop(new AheadHit(bucees, 19_000, 60, RoadSide.LEFT, 0), 20);
        check(AheadSearch.INSTANCE.spoken(PoiKind.GAS, k, SpeedUnit.KMH, 80_000, false)
            .equals("Gas along your route in 19 kilometres: Buc-ee's, about a 1-minute detour."), AheadSearch.INSTANCE.spoken(PoiKind.GAS, k, SpeedUnit.KMH, 80_000, false));
        check(AheadSearch.INSTANCE.detail(k, SpeedUnit.KMH).equals("19 km ahead · 1 min detour · left"), AheadSearch.INSTANCE.detail(k, SpeedUnit.KMH));
        AheadStop close = new AheadStop(new AheadHit(bucees, 400, 60, RoadSide.RIGHT, 0), 90);
        check(AheadSearch.INSTANCE.detail(close, SpeedUnit.MPH).equals("0.2 mi ahead · 2 min detour · right"),
            AheadSearch.INSTANCE.detail(close, SpeedUnit.MPH));
        check(AheadSearch.INSTANCE.spoken(PoiKind.GAS, close, SpeedUnit.MPH, 80_000, false).startsWith("Gas along your route in a quarter mile: "),
            AheadSearch.INSTANCE.spoken(PoiKind.GAS, close, SpeedUnit.MPH, 80_000, false));
        ObfPoi unnamedGas = new ObfPoi(2, 30.5, -97.7, "", null, "transportation", "fuel", PoiKind.GAS, null, null, "lab.obf");
        ObfPoi unnamedFood = new ObfPoi(3, 30.5, -97.7, " ", null, "sustenance", "fast_food", PoiKind.FOOD, null, null, "lab.obf");
        ObfPoi unnamedShop = new ObfPoi(4, 30.5, -97.7, "", null, "shop", "supermarket", PoiKind.SHOP, null, null, "lab.obf");
        ObfPoi branded = new ObfPoi(5, 30.5, -97.7, "", null, "transportation", "fuel", PoiKind.GAS, "Shell", null, "lab.obf");
        check(AheadSearch.INSTANCE.spoken(PoiKind.GAS, new AheadStop(new AheadHit(unnamedGas, 3218.688, 0, RoadSide.RIGHT, 0), 240), SpeedUnit.MPH, 1, false)
            .equals("Gas along your route in 2 miles: a gas station, about a 4-minute detour."), "an unnamed station is a gas station");
        check(AheadSearch.INSTANCE.spoken(PoiKind.FOOD, new AheadStop(new AheadHit(unnamedFood, 3218.688, 0, RoadSide.RIGHT, 0), 240), SpeedUnit.MPH, 1, false)
            .equals("Food along your route in 2 miles: a restaurant, about a 4-minute detour."), "an unnamed place to eat is a restaurant");
        check(AheadSearch.INSTANCE.spoken(PoiKind.SHOP, new AheadStop(new AheadHit(unnamedShop, 3218.688, 0, RoadSide.RIGHT, 0), 240), SpeedUnit.MPH, 1, false)
            .equals("Shopping along your route in 2 miles: a store, about a 4-minute detour."), "an unnamed shop is a store");
        check(AheadSearch.INSTANCE.spokenName(branded, PoiKind.GAS).equals("Shell"), "no name: the brand");
        check(AheadSearch.INSTANCE.cardLabel(PoiKind.GAS).equals("gas ahead"), "the card's label");
    }

    private static void noStopWording() {
        double fifty = 50 * AheadSearch.METRES_PER_MILE;
        check(AheadSearch.INSTANCE.spoken(PoiKind.GAS, null, SpeedUnit.MPH, fifty, false).equals("No gas along your route in the next 50 miles."),
            AheadSearch.INSTANCE.spoken(PoiKind.GAS, null, SpeedUnit.MPH, fifty, false));
        check(AheadSearch.INSTANCE.spoken(PoiKind.FOOD, null, SpeedUnit.KMH, 80_000, false).equals("No food along your route in the next 80 kilometres."),
            AheadSearch.INSTANCE.spoken(PoiKind.FOOD, null, SpeedUnit.KMH, 80_000, false));
        check(AheadSearch.INSTANCE.spoken(PoiKind.SHOP, null, SpeedUnit.MPH, 9000, true).equals("No shops along your route before your destination."),
            "a corridor that reached the destination says so");
        check(AheadSearch.INSTANCE.spoken(PoiKind.GAS, null, SpeedUnit.MPH, fifty, false, true)
            .equals("Gas along your route, but no detour could be worked out."), "every detour failed: said so, not none");
        check(AheadSearch.INSTANCE.noneLine(SpeedUnit.MPH, fifty, false, false).equals("None in the next 50 mi"),
            AheadSearch.INSTANCE.noneLine(SpeedUnit.MPH, fifty, false, false));
        check(AheadSearch.INSTANCE.noneLine(SpeedUnit.KMH, 80_000, false, false).equals("None in the next 80 km"), "the card line in km");
        check(AheadSearch.INSTANCE.noneLine(SpeedUnit.MPH, 9000, true, false).equals("None before your destination"), "to the destination");
        check(AheadSearch.INSTANCE.noneLine(SpeedUnit.MPH, fifty, false, true).equals("No detour could be worked out"), "every detour failed");
    }

    private static void theViaPointIsLetGo() {
        check(ViaRelease.INSTANCE.passed(null, false, 0, 49), "within 50 m of it, on the route or not, is reaching it");
        check(!ViaRelease.INSTANCE.passed(5000.0, true, 5049, 500), "49 m past it on the route is not yet past");
        check(ViaRelease.INSTANCE.passed(5000.0, true, 5051, 500), "51 m past it on the route is");
        check(!ViaRelease.INSTANCE.passed(5000.0, false, 6000, 500), "progress off the route does not count");
        check(!ViaRelease.INSTANCE.passed(null, true, 9999, 60), "a via point not on the followed route is let go only on reaching it");
        // A drive past a stop: in on the route, out by another road (off it), then a re-route.
        Double viaAlong = 8000.0;
        double[][] fixes = {{7800, 200, 1}, {7950, 60, 1}, {7990, 30, 1}}; // along, metres to via, on route
        boolean released = false;
        for (double[] f : fixes) released |= ViaRelease.INSTANCE.passed(viaAlong, f[2] == 1, f[0], f[1]);
        check(released, "pulling into the station lets it go before the vehicle leaves by another road");
    }

    private static void theReportHoldsCountsOnly() {
        AheadCounts counts = new AheadCounts();
        counts.setPresses(2);
        counts.setSearches(2);
        counts.setCandidates(9);
        counts.setRouted(5);
        counts.setFailed(1);
        counts.announced(1800);
        counts.announced(2400);
        counts.corridor(120);
        String report = counts.report();
        check(report.startsWith("poi_ahead_presses=2 ") && report.contains(" poi_ahead_candidates=9 ")
            && report.contains(" poi_ahead_detours_routed=5 ") && report.contains(" poi_ahead_detours_failed=1 ")
            && report.contains(" poi_ahead_announced=2 ") && report.contains(" poi_ahead_ms_p50=1800 ")
            && report.contains(" poi_ahead_ms_max=2400 "), report);
        for (String field : report.trim().split(" ")) {
            check(field.matches("poi_ahead_[a-z0-9_]+=([0-9]+|-)"), "a count or a time, nothing else: " + field);
        }
        AheadStop s = stop(4000, 300);
        check(!s.toString().contains(NAME) && !s.toString().contains("30."), "a stop printed names no place: " + s);
    }

    // --- Real data ---------------------------------------------------------------------------

    private static void realSearchNorthOnI35(File segments, File austin) throws Exception {
        File work = Files.createTempDirectory("search-ahead").toFile();
        File profile = RoutingData.INSTANCE.installProfile(new File(work, "profiles"));
        Router router = new Router(segments, profile, 64);
        RouteOptions options = new RouteOptions();
        // Downtown Austin by I-35 (the lab's position) to Round Rock: north on I-35.
        long t0 = System.nanoTime();
        Route route = router.compute(new RouteRequest(30.2660, -97.7360, 30.5086, -97.6788, null, options), 120_000, e -> kotlin.Unit.INSTANCE);
        long routeMs = (System.nanoTime() - t0) / 1_000_000;
        check(route.getDistanceM() > 20_000 && route.getDistanceM() < 40_000, "the route is 20-40 km: " + route.getDistanceM());
        try (ObfFile obf = ObfFile.Companion.open(austin)) {
            List<ObfSource> sources = List.of(obf);
            AheadCorridor corridor = new AheadCorridor(route, 0.0, 50 * AheadSearch.METRES_PER_MILE);
            check(corridor.getReachesDestination(), "50 miles reaches Round Rock");
            double width = 0.5 * AheadSearch.METRES_PER_MILE;
            // Cold: the file's table of contents and POI tables are read on the first query.
            long c0 = System.nanoTime();
            AheadHits cold = AheadSearch.INSTANCE.hits(sources, corridor, PoiKind.GAS, width);
            long coldMs = (System.nanoTime() - c0) / 1_000_000;
            long w0 = System.nanoTime();
            AheadHits found = AheadSearch.INSTANCE.hits(sources, corridor, PoiKind.GAS, width);
            long warmMs = (System.nanoTime() - w0) / 1_000_000;
            check(found.getFailures() == 0 && found.getHits().size() == cold.getHits().size(), "the same places twice, no file failure");
            check(found.getHits().size() >= 5, "gas stations along I-35 to Round Rock: " + found.getHits().size());
            for (AheadHit h : found.getHits()) {
                check(h.getOffsetM() <= width && h.getAheadM() >= AheadSearch.MIN_AHEAD_M, "each inside the corridor and ahead");
            }
            List<AheadHit> nearest = found.getHits().subList(0, Math.min(5, found.getHits().size()));
            long d0 = System.nanoTime();
            AheadDetours detours = AheadSearch.INSTANCE.detours(
                request -> router.compute(request, 60_000, e -> kotlin.Unit.INSTANCE), corridor, nearest, options, List.of(), () -> false);
            long detourMs = (System.nanoTime() - d0) / 1_000_000;
            check(detours.getRouted() >= 3, "most of the five detours routed: " + detours.getRouted() + " routed, " + detours.getFailed() + " failed");
            int least = Integer.MAX_VALUE;
            int most = 0;
            StringBuilder line = new StringBuilder();
            for (AheadStop s : detours.getStops()) {
                least = Math.min(least, s.getDetourSeconds());
                most = Math.max(most, s.getDetourSeconds());
                // Where it is on the route and how far off, never which place.
                DetourLeg leg = AheadSearch.INSTANCE.leg(corridor, s.getHit());
                Route ab = router.compute(new RouteRequest(leg.getALat(), leg.getALon(), leg.getBLat(), leg.getBLon(), leg.getABearing(), options),
                    60_000, e -> kotlin.Unit.INSTANCE);
                line.append(String.format(java.util.Locale.ROOT, " [%.1f km ahead, %d m %s, detour %d s, route A-B %.0f s vs BRouter A-B %d s]",
                    s.getHit().getAheadM() / 1000, Math.round(s.getHit().getOffsetM()), s.getHit().getSide().getWord(),
                    s.getDetourSeconds(), leg.getBaseSeconds(), ab.getSeconds()));
            }
            check(least <= 6 * 60, "the nearest stations include one a few minutes off I-35: least detour " + least + " s");
            check(most <= 15 * 60, "no detour to a station within half a mile of the road is 15 minutes or more: " + most + " s");
            AheadStop best = AheadSearch.INSTANCE.best(detours.getStops());
            check(best != null && best.getDetourSeconds() <= least + AheadSearch.NEARER_WITHIN_S, "a best stop by the rule");
            System.out.println("SearchAheadTest: I-35 north, route " + route.getDistanceM() + " m in " + routeMs + " ms; "
                + found.getHits().size() + " gas in the corridor from " + found.getQueries() + " queries, " + coldMs + " ms cold, "
                + warmMs + " ms warm; 5 detours in " + detourMs + " ms (" + detours.getRouted() + " routed, " + detours.getFailed()
                + " failed); best " + AheadSearch.INSTANCE.minutes(best.getDetourSeconds()) + " min at "
                + Math.round(best.getHit().getAheadM() / 100) / 10.0 + " km;" + line);
            // Food in the same corridor, for its count and time.
            long f0 = System.nanoTime();
            AheadHits food = AheadSearch.INSTANCE.hits(sources, corridor, PoiKind.FOOD, width);
            System.out.println("SearchAheadTest: food in the corridor " + food.getHits().size() + " in " + (System.nanoTime() - f0) / 1_000_000 + " ms");
            check(food.getHits().size() > found.getHits().size(), "more food than gas along I-35");
        }
    }

    private static boolean near(double a, double b, double tolerance) {
        return Math.abs(a - b) <= tolerance;
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
