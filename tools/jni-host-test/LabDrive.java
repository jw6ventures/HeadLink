// Writes the drive the lab receiver's guidance script plays (P6.5): a real route from the
// lab's parked position to the region's guidance destination (Lexington's Battle Green; with
// `austin`, Guadalupe Street by the university), followed until it turns, then a missed
// turn - the drive carries on along another route for a few hundred metres - and a way from
// there to the same destination. Computed with the app's own OsmAndRouter over the region's
// OsmAnd map, so the drive stays on roads the app's router knows.
//
//   java -Xmx256m -cp <host-test classpath> LabDrive <OBF_DIR holding the region's .obf> <out.csv> [return] [austin]
//
// With `return` the drive does not reach the destination another way: it turns round at
// the end of the wrong way, drives back to where it left the route, and follows the
// planned route from there to the destination. That is the drive for a re-route that
// fails: the vehicle finds its own way back onto the route it had (tools/lab-receiver/drives/
// lexington-return.csv, austin-return.csv).
//
// Not a test: it prints what it chose and writes the file. The file is a lab fixture of
// public-road coordinates derived from OpenStreetMap data (ODbL), not anybody's trip.
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.jw6ventures.headlink.OsmAndRouter;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteFollower;
import com.jw6ventures.headlink.RouteRequest;

public final class LabDrive {
    /**
     * A lab region: the map the drive is routed over (in the directory given), where the lab
     * vehicle is parked, and where it is sent (the guidance script's share).
     */
    private record Region(String map, String place, double startLat, double startLon, double destLat, double destLon) {}

    private static final Region MASSACHUSETTS =
        new Region("Us_massachusetts_northamerica.obf", "Battle Green", 42.4242, -71.2121, 42.44950, -71.23100);
    private static final Region AUSTIN =
        new Region("Us_texas_austin_northamerica.obf", "Guadalupe Street by the university", 30.2660, -97.7360, 30.28750, -97.74170);
    /** A drive further than this from the route has left it. */
    private static final double APART_M = 25;
    /** How far the drive keeps going the wrong way before heading back toward the destination. */
    private static final double WRONG_WAY_M = 250;
    /**
     * The planned route's last turn must be at least this far before its end: inside the
     * follower's arrival radius ([RouteFollower.ARRIVE_M]) the vehicle arrives before the
     * destination step reaches the cluster, and the receiver's check that it did fails.
     */
    private static final double LAST_TURN_M = 2 * RouteFollower.ARRIVE_M;

    public static void main(String[] args) throws Exception {
        List<String> flags = List.of(args).subList(2, args.length);
        Region r = flags.contains("austin") ? AUSTIN : MASSACHUSETTS;
        OsmAndRouter router = new OsmAndRouter(() -> List.of(OsmAndRouter.MapFile.of(new File(args[0], r.map()))));
        try {
            write(router, r, args[1], flags.contains("return"));
        } finally {
            router.close();
        }
    }

    private static void write(OsmAndRouter router, Region r, String file, boolean returning) throws Exception {
        Route planned = route(router, r.startLat(), r.startLon(), r.destLat(), r.destLon());
        int[] maneuvers = planned.getManeuverIndices();
        double lastTurn = maneuvers.length == 0 ? planned.getDistanceM() : planned.getDistanceM() - alongTo(planned, maneuvers[maneuvers.length - 1]);
        System.out.println(String.format(Locale.ROOT, "lab-drive: planned %d m, %d maneuvers, the last %.0f m before the end",
            planned.getDistanceM(), maneuvers.length, lastTurn));
        if (lastTurn < LAST_TURN_M) throw new IllegalStateException("the planned route's last turn is inside the arrival radius");

        // A second destination whose route shares the first part of the planned one and then
        // goes another way: following it is missing a turn.
        double[] best = null;
        double bestScore = Double.MAX_VALUE;
        Route bestOther = null;
        int bestSplit = -1;
        for (int ring = 1; ring <= 3; ring++) {
            for (int bearing = 0; bearing < 360; bearing += 30) {
                double km = 0.8 * ring;
                double lat = r.startLat() + km / 111.2 * Math.cos(Math.toRadians(bearing));
                double lon = r.startLon() + km / (111.2 * Math.cos(Math.toRadians(r.startLat()))) * Math.sin(Math.toRadians(bearing));
                Route other;
                try {
                    other = route(router, r.startLat(), r.startLon(), lat, lon);
                } catch (Exception e) {
                    continue;
                }
                int split = split(planned, other);
                if (split < 0) continue;
                double along = alongTo(other, split);
                // Far enough in that the drive is on the route first; with at least one of
                // the planned maneuvers still ahead of the split.
                if (along < 700 || along > 2500) continue;
                double score = Math.abs(along - 1200);
                if (score < bestScore) {
                    bestScore = score;
                    best = new double[] {lat, lon};
                    bestOther = other;
                    bestSplit = split;
                }
            }
        }
        if (best == null) throw new IllegalStateException("no route diverges from the planned one where wanted");
        double splitAlong = alongTo(bestOther, bestSplit);
        int wrong = indexAt(bestOther, splitAlong + WRONG_WAY_M);
        System.out.println(String.format(Locale.ROOT, "lab-drive: leaves the route %.0f m in, drives %.0f m the wrong way",
            splitAlong, alongTo(bestOther, wrong) - splitAlong));
        List<double[]> drive = new ArrayList<>();
        for (int i = 0; i <= wrong; i++) drive.add(new double[] {bestOther.getLatitudes()[i], bestOther.getLongitudes()[i]});
        if (returning) {
            // Back the way it came to the split, then the planned route on from the point of
            // it nearest the split.
            for (int i = wrong - 1; i >= bestSplit; i--) drive.add(new double[] {bestOther.getLatitudes()[i], bestOther.getLongitudes()[i]});
            int rejoin = nearest(planned, bestOther.getLatitudes()[bestSplit], bestOther.getLongitudes()[bestSplit]);
            for (int i = rejoin + 1; i < planned.getPoints(); i++) drive.add(new double[] {planned.getLatitudes()[i], planned.getLongitudes()[i]});
            System.out.println(String.format(Locale.ROOT, "lab-drive: then back and on along the planned route from %.0f m in",
                alongTo(planned, rejoin)));
        } else {
            Route back = route(router, bestOther.getLatitudes()[wrong], bestOther.getLongitudes()[wrong], r.destLat(), r.destLon());
            System.out.println("lab-drive: then " + back.getDistanceM() + " m to the destination");
            for (int i = 1; i < back.getPoints(); i++) drive.add(new double[] {back.getLatitudes()[i], back.getLongitudes()[i]});
        }
        double total = 0;
        for (int i = 1; i < drive.size(); i++) {
            total += RouteFollower.Companion.distance(drive.get(i - 1)[0], drive.get(i - 1)[1], drive.get(i)[0], drive.get(i)[1]);
        }
        try (PrintWriter out = new PrintWriter(file, StandardCharsets.US_ASCII)) {
            out.println("# P6.5 lab drive, written by tools/jni-host-test/LabDrive.java from " + r.map());
            out.println("# (OpenStreetMap data, ODbL). Follows the route from the lab position to " + r.place() + ",");
            out.println(String.format(Locale.ROOT, returning
                    ? "# leaves it %.0f m in, turns round and rejoins it, and reaches the destination on it: %d points, %.0f m."
                    : "# leaves it %.0f m in, and reaches the destination another way: %d points, %.0f m.",
                splitAlong, drive.size(), total));
            for (double[] p : drive) out.println(String.format(Locale.ROOT, "%.6f,%.6f", p[0], p[1]));
        }
        System.out.println(String.format(Locale.ROOT, "lab-drive: %d points, %.0f m, written to %s", drive.size(), total, file));
    }

    private static Route route(OsmAndRouter router, double fromLat, double fromLon, double toLat, double toLon) throws Exception {
        return router.compute(new RouteRequest(fromLat, fromLon, toLat, toLon), 120_000, e -> kotlin.Unit.INSTANCE);
    }

    /** The last index of [other] still on [planned] before it leaves it for good, or -1. */
    private static int split(Route planned, Route other) {
        for (int i = 0; i < other.getPoints(); i++) {
            if (offset(planned, other.getLatitudes()[i], other.getLongitudes()[i]) > APART_M) {
                // Still apart 150 m later, so this is a different road and not a kink.
                int later = indexAt(other, alongTo(other, i) + 150);
                if (later > i && offset(planned, other.getLatitudes()[later], other.getLongitudes()[later]) > APART_M) {
                    return i > 0 ? i - 1 : -1;
                }
            }
        }
        return -1;
    }

    /** The index of the point of [route] nearest the given one. */
    private static int nearest(Route route, double lat, double lon) {
        int best = 0;
        for (int i = 1; i < route.getPoints(); i++) {
            if (RouteFollower.Companion.distance(lat, lon, route.getLatitudes()[i], route.getLongitudes()[i])
                < RouteFollower.Companion.distance(lat, lon, route.getLatitudes()[best], route.getLongitudes()[best])) {
                best = i;
            }
        }
        return best;
    }

    private static double offset(Route route, double lat, double lon) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < route.getPoints(); i++) {
            best = Math.min(best, RouteFollower.Companion.distance(lat, lon, route.getLatitudes()[i], route.getLongitudes()[i]));
        }
        // Points only, not segments: long straight segments get a generous margin below.
        for (int i = 1; i < route.getPoints(); i++) {
            double[] mid = {(route.getLatitudes()[i - 1] + route.getLatitudes()[i]) / 2, (route.getLongitudes()[i - 1] + route.getLongitudes()[i]) / 2};
            best = Math.min(best, RouteFollower.Companion.distance(lat, lon, mid[0], mid[1]));
        }
        return best;
    }

    private static double alongTo(Route route, int index) {
        double total = 0;
        for (int i = 1; i <= index; i++) {
            total += RouteFollower.Companion.distance(route.getLatitudes()[i - 1], route.getLongitudes()[i - 1],
                route.getLatitudes()[i], route.getLongitudes()[i]);
        }
        return total;
    }

    private static int indexAt(Route route, double metres) {
        double total = 0;
        for (int i = 1; i < route.getPoints(); i++) {
            total += RouteFollower.Companion.distance(route.getLatitudes()[i - 1], route.getLongitudes()[i - 1],
                route.getLatitudes()[i], route.getLongitudes()[i]);
            if (total >= metres) return i;
        }
        return route.getPoints() - 1;
    }
}
