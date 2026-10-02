// Writes the drive the lab receiver's guidance script plays (P6.5): a real route from the
// lab's parked position to Lexington's Battle Green, followed until it turns, then a missed
// turn - the drive carries on along another route for a few hundred metres - and a way from
// there to the same destination. Computed with the app's own Router over W75_N40.rd5, so
// the drive stays on roads the app's router knows.
//
//   java -cp <host-test classpath> LabDrive <dir holding W75_N40.rd5> <out.csv> [return]
//
// With `return` the drive does not reach the destination another way: it turns round at
// the end of the wrong way, drives back to where it left the route, and follows the
// planned route from there to the destination. That is the drive for a re-route that
// fails (the lab moves the routing tile away once the first route is computed): the
// vehicle finds its own way back onto the route it had (tools/lab-receiver/drives/
// lexington-return.csv).
//
// Not a test: it prints what it chose and writes the file. The file is a lab fixture of
// public-road coordinates derived from OpenStreetMap data (ODbL), not anybody's trip.
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.openautoandroid.source.Route;
import org.openautoandroid.source.RouteFollower;
import org.openautoandroid.source.RouteRequest;
import org.openautoandroid.source.Router;
import org.openautoandroid.source.RoutingData;

public final class LabDrive {
    /** Where the lab vehicle is parked, and where it is sent (the P6.4 share's Battle Green). */
    private static final double START_LAT = 42.4242, START_LON = -71.2121;
    private static final double DEST_LAT = 42.44950, DEST_LON = -71.23100;
    /** A drive further than this from the route has left it. */
    private static final double APART_M = 25;
    /** How far the drive keeps going the wrong way before heading back toward the destination. */
    private static final double WRONG_WAY_M = 250;

    public static void main(String[] args) throws Exception {
        File segments = new File(args[0]);
        File work = Files.createTempDirectory("lab-drive").toFile();
        File profile = RoutingData.INSTANCE.installProfile(new File(work, "profiles"));
        Router router = new Router(segments, profile, 64);
        Route planned = route(router, START_LAT, START_LON, DEST_LAT, DEST_LON);
        System.out.println("lab-drive: planned " + planned.getDistanceM() + " m, " + planned.getManeuverIndices().length + " maneuvers");

        // A second destination whose route shares the first part of the planned one and then
        // goes another way: following it is missing a turn.
        double[] best = null;
        double bestScore = Double.MAX_VALUE;
        Route bestOther = null;
        int bestSplit = -1;
        for (int ring = 1; ring <= 3; ring++) {
            for (int bearing = 0; bearing < 360; bearing += 30) {
                double km = 0.8 * ring;
                double lat = START_LAT + km / 111.2 * Math.cos(Math.toRadians(bearing));
                double lon = START_LON + km / (111.2 * Math.cos(Math.toRadians(START_LAT))) * Math.sin(Math.toRadians(bearing));
                Route other;
                try {
                    other = route(router, START_LAT, START_LON, lat, lon);
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
        boolean returning = args.length > 2 && args[2].equals("return");
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
            Route back = route(router, bestOther.getLatitudes()[wrong], bestOther.getLongitudes()[wrong], DEST_LAT, DEST_LON);
            System.out.println("lab-drive: then " + back.getDistanceM() + " m to the destination");
            for (int i = 1; i < back.getPoints(); i++) drive.add(new double[] {back.getLatitudes()[i], back.getLongitudes()[i]});
        }
        double total = 0;
        for (int i = 1; i < drive.size(); i++) {
            total += RouteFollower.Companion.distance(drive.get(i - 1)[0], drive.get(i - 1)[1], drive.get(i)[0], drive.get(i)[1]);
        }
        try (PrintWriter out = new PrintWriter(args[1], StandardCharsets.US_ASCII)) {
            out.println("# P6.5 lab drive, written by tools/jni-host-test/LabDrive.java from W75_N40.rd5");
            out.println("# (OpenStreetMap data, ODbL). Follows the route from the lab position to Battle Green,");
            out.println(String.format(Locale.ROOT, returning
                    ? "# leaves it %.0f m in, turns round and rejoins it, and reaches the destination on it: %d points, %.0f m."
                    : "# leaves it %.0f m in, and reaches the destination another way: %d points, %.0f m.",
                splitAlong, drive.size(), total));
            for (double[] p : drive) out.println(String.format(Locale.ROOT, "%.6f,%.6f", p[0], p[1]));
        }
        System.out.println(String.format(Locale.ROOT, "lab-drive: %d points, %.0f m, written to %s", drive.size(), total, args[1]));
        delete(work);
    }

    private static Route route(Router router, double fromLat, double fromLon, double toLat, double toLon) throws Exception {
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

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
