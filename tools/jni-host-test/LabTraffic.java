// Writes the stand-in HERE server's scenarios for the emulator lab's LAB_TRAFFIC runs (P9
// plan §8), and the drive the lab receiver plays: BRouter's route from the lab's parked
// position to Boston Common, and hand-written flow and incident responses in HERE's shape
// (plan §4) laid along the OSM roads that route takes. Computed with the app's own Router,
// TrafficMatch, TrafficNogos and AlternativeSearch over W75_N40.rd5, so the lab's numbers can
// be checked against what the same code finds on the host:
//
//   java -cp <host-test classpath> LabTraffic <dir holding W75_N40.rd5> <tools/here-standin/scenarios> <drive.csv>
//
// Not a test: it prints what it chose, checks that each scenario does what the lab relies on
// (a queue a real way round saves at least 3 minutes on, a heavier one saving 3 minutes
// more, a side-road closure that touches neither, a closure on the route BRouter can route
// round) and writes the files. Nothing in them came from HERE: the geometry is OSM's
// (ODbL), through BRouter, and every speed, jam factor and closure is made up here.
import java.io.File;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kotlin.Unit;
import com.jw6ventures.headlink.AlternativeSearch;
import com.jw6ventures.headlink.FlowStretch;
import com.jw6ventures.headlink.LiveAlternative;
import com.jw6ventures.headlink.Nogo;
import com.jw6ventures.headlink.PreviewRequest;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteFollower;
import com.jw6ventures.headlink.RouteOptions;
import com.jw6ventures.headlink.RoutePlanner;
import com.jw6ventures.headlink.RouteRequest;
import com.jw6ventures.headlink.RouteTraffic;
import com.jw6ventures.headlink.Router;
import com.jw6ventures.headlink.RoutingData;
import com.jw6ventures.headlink.TrafficArea;
import com.jw6ventures.headlink.TrafficCost;
import com.jw6ventures.headlink.TrafficIncident;
import com.jw6ventures.headlink.TrafficJson;
import com.jw6ventures.headlink.TrafficMatch;
import com.jw6ventures.headlink.TrafficNogos;

public final class LabTraffic {
    /** Where the lab vehicle is parked (LabSensorHandler, LabDrive), and Boston Common. */
    private static final double START_LAT = 42.4242, START_LON = -71.2121;
    private static final double DEST_LAT = 42.3551, DEST_LON = -71.0656;
    /** Metres per degree of latitude, as the tests use it. */
    private static final double M = 111_195.0;
    /** How far HERE's line is laid to the right of OSM's: HERE's geometry is a few metres off. */
    private static final double LATERAL_M = 3.0;
    /** The slow stretch near the parked vehicle, so its colour is in the street-zoom picture. */
    private static final double SLOW_FROM_M = 120.0, SLOW_TO_M = 520.0;
    /** The queue: this long, on the first fast stretch at least this far along. */
    private static final double QUEUE_M = 2_500.0, QUEUE_AFTER_M = 2_000.0;
    /** Scenario A's queue speed, and scenario A2's heavier one (m/s). */
    private static final double QUEUE_SPEED = 4.0, HEAVY_SPEED = 2.0;
    /** The closure moved onto the route (scenario B): close enough to be in the picture. */
    private static final double CLOSED_FROM_M = 250.0, CLOSED_TO_M = 480.0;
    /** A side road is this far from every route the lab drives or is offered, at least. */
    private static final double SIDE_CLEAR_M = 80.0;
    /** The drive the lab receiver plays for LAB_TRAFFIC=off: the route's first stretch. */
    private static final double DRIVE_M = 3_000.0;

    public static void main(String[] args) throws Exception {
        File segments = new File(args[0]);
        File scenarios = new File(args[1]);
        File work = Files.createTempDirectory("lab-traffic").toFile();
        Router router = new Router(segments, RoutingData.INSTANCE.installProfile(new File(work, "profiles")), 64);
        RouteOptions options = new RouteOptions();

        // The route the app will compute: a shared destination is routed from the parked
        // position with no start bearing (Navigation.set), as here.
        Route route = router.compute(new RouteRequest(START_LAT, START_LON, DEST_LAT, DEST_LON, null, options),
                RoutePlanner.MAX_RUNNING_MS, e -> Unit.INSTANCE);
        double[] along = TrafficArea.INSTANCE.metres(route);
        double total = along[along.length - 1];
        say("route %d m, %d s, %d points", route.getDistanceM(), route.getSeconds(), route.getPoints());

        // The queue: the first QUEUE_M BRouter drives at motorway speed, past QUEUE_AFTER_M.
        double queueFrom = -1.0;
        for (double m = QUEUE_AFTER_M; m + QUEUE_M < total - 3_000.0; m += 50.0) {
            if (QUEUE_M / (seconds(route, along, m + QUEUE_M) - seconds(route, along, m)) >= 18.0) {
                queueFrom = m;
                break;
            }
        }
        if (queueFrom < 0) throw new IllegalStateException("no fast stretch for the queue");
        double queueTo = queueFrom + QUEUE_M;
        double brouterQueueS = seconds(route, along, queueTo) - seconds(route, along, queueFrom);
        say("queue %.0f-%.0f m, BRouter %.0f s over it (%.1f m/s)", queueFrom, queueTo, brouterQueueS, QUEUE_M / brouterQueueS);

        // Scenario A: slow near the vehicle, the queue, free flow after it, the other
        // carriageway's traffic laid 25 m to the left the other way (it must not match), and a
        // closure on a side road chosen below.
        List<String> free = List.of(
                result("Lab free flow", laid(route, along, queueTo + 60.0, Math.min(queueTo + 1_500.0, total - 200.0), LATERAL_M), 1, 24.0, 25.0, 1.0, "open"));
        String slow = result("Lab slow", laid(route, along, SLOW_FROM_M, SLOW_TO_M, LATERAL_M), 3, 5.0, 13.0, 6.0, "open");
        String opposite = result("Lab other carriageway", reversed(laid(route, along, queueFrom, queueTo, -25.0)), 1, 2.0, 25.0, 9.8, "open");
        String queueA = result("Lab queue", laid(route, along, queueFrom, queueTo, LATERAL_M), 1, QUEUE_SPEED, 25.0, 8.8, "open");
        String queueA2 = result("Lab queue", laid(route, along, queueFrom, queueTo, LATERAL_M), 1, HEAVY_SPEED, 25.0, 9.6, "open");
        List<String> flowA = new ArrayList<>(List.of(slow, queueA, opposite));
        flowA.addAll(free);
        List<String> flowA2 = new ArrayList<>(List.of(slow, queueA2, opposite));
        flowA2.addAll(free);

        // What the app will see for A and A2, before any side road: the delay and the offer.
        Offer a = offer(router, route, options, flowJson(flowA), incidentsJson(List.of()));
        Offer a2 = offer(router, route, options, flowJson(flowA2), incidentsJson(List.of()));
        say("A: delay %.0f s (queue %.0f, slow %.0f), %d nogos, %d ways round, best saves %d s, split %.0f m, its delay %.0f s, %d ms",
                a.delay, a.queueDelay, a.slowDelay, a.nogos, a.found.size(), a.saving, a.splitM, a.ownDelay, a.ms);
        say("A2: delay %.0f s, best saves %d s (A + %d), split %.0f m, same split %b", a2.delay, a2.saving, a2.saving - a.saving,
                a2.splitM, a.best != null && a2.best != null && Math.abs(a.splitM - a2.splitM) < 200.0);
        say("A ways round: %s; A2: %s", String.join("; ", a.each), String.join("; ", a2.each));
        if (a.saving < 180) throw new IllegalStateException("A offers no way round saving 3 minutes");
        if (a2.saving < a.saving + 180) throw new IllegalStateException("A2 does not save 3 minutes more than A");

        // The side road: a piece of a street near the route that neither the route, nor any
        // way round A or A2 found, nor the drive comes within SIDE_CLEAR_M of.
        List<Route> clear = new ArrayList<>(List.of(route));
        clear.addAll(a.found);
        clear.addAll(a2.found);
        double[][] side = sideRoad(router, route, along, options, clear);
        String sideClosure = closure("lab-side", side);
        say("side road closure %d points, %.0f m from the route", side[0].length, nearest(route, side));
        Offer aSide = offer(router, route, options, flowJson(flowA), incidentsJson(List.of(sideClosure)));
        if (aSide.saving < 180 || Math.abs(aSide.delay - a.delay) > 1.0 || aSide.closedRuns != 0) {
            throw new IllegalStateException("the side closure changes A: saving " + aSide.saving + " delay " + aSide.delay);
        }
        say("A with the side closure: saves %d s, closed runs on the route %d, %d nogos (%d hard)", aSide.saving, aSide.closedRuns,
                aSide.nogos, aSide.hard);

        // Scenario B: the side closure, and a closure on the route itself close ahead, in both
        // HERE's flow (closed, no speed) and its incidents. BRouter must route round it from
        // where the vehicle is parked, without opening it.
        double[][] onRoute = laid(route, along, CLOSED_FROM_M, CLOSED_TO_M, LATERAL_M);
        List<String> flowB = List.of(result("Lab closed", onRoute, 3, Double.NaN, 13.0, 10.0, "closed"));
        String incidentsB = incidentsJson(List.of(sideClosure, closure("lab-route", onRoute)));
        List<FlowStretch> fB = TrafficJson.INSTANCE.flow(new StringReader(flowJson(flowB)));
        List<TrafficIncident> iB = TrafficJson.INSTANCE.incidents(new StringReader(incidentsB));
        RouteTraffic tB = TrafficMatch.INSTANCE.match(route, fB, iB);
        if (tB.closureAhead(0.0) == null) throw new IllegalStateException("B's closure does not match the route");
        List<Nogo> nB = TrafficNogos.INSTANCE.forRoute(fB, iB, tB, fc -> TrafficCost.INSTANCE.costPerSecond(fc, options.getTopSpeedKmh()),
                TrafficNogos.MAX_NOGOS).getNogos();
        Route round = router.compute(new RouteRequest(START_LAT, START_LON, DEST_LAT, DEST_LON, null, options, 0, null, null, nB),
                RoutePlanner.MAX_RUNNING_MS, e -> Unit.INSTANCE);
        double[] closed = at(route, along, (CLOSED_FROM_M + CLOSED_TO_M) / 2.0);
        say("B: closure run %.0f-%.0f m, %d nogos (%d hard); round it %d m, %d s, opened %b, %.0f m from the closed road",
                tB.closureAhead(0.0).getFromM(), tB.closureAhead(0.0).getToM(), nB.size(), nB.stream().filter(Nogo::getHard).count(),
                round.getDistanceM(), round.getSeconds(), round.getClosuresOpened(), nearest(round, new double[][] {{closed[0]}, {closed[1]}}));
        if (round.getClosuresOpened() || nearest(round, new double[][] {{closed[0]}, {closed[1]}}) < 20.0) {
            throw new IllegalStateException("BRouter cannot route round B's closure");
        }

        // The side scenario alone (the closure run's first answer): the slow stretch, which
        // is well short of an offer's worth, and the side closure, which must not re-route.
        List<String> flowSide = List.of(slow);
        Offer s = offer(router, route, options, flowJson(flowSide), incidentsJson(List.of(sideClosure)));
        say("side: delay %.0f s, closed runs %d", s.delay, s.closedRuns);

        write(scenarios, "a", flowJson(flowA), incidentsJson(List.of(sideClosure)));
        write(scenarios, "a2", flowJson(flowA2), incidentsJson(List.of(sideClosure)));
        write(scenarios, "side", flowJson(flowSide), incidentsJson(List.of(sideClosure)));
        write(scenarios, "b", flowJson(flowB), incidentsB);
        write(scenarios, "empty", flowJson(List.of()), incidentsJson(List.of()));
        try (PrintWriter out = new PrintWriter(new File(scenarios, "expected.txt"), StandardCharsets.US_ASCII)) {
            out.println("# Written by tools/jni-host-test/LabTraffic.java over W75_N40.rd5; what the app's own code finds on");
            out.println("# the host for each scenario, which the lab run checks the emulator against.");
            out.printf(Locale.ROOT, "route_m=%d route_s=%d%n", route.getDistanceM(), route.getSeconds());
            out.printf(Locale.ROOT, "a_delay_s=%.0f a_queue_delay_s=%.0f a_slow_delay_s=%.0f a_saving_s=%d%n", a.delay, a.queueDelay, a.slowDelay, a.saving);
            out.printf(Locale.ROOT, "a2_delay_s=%.0f a2_saving_s=%d%n", a2.delay, a2.saving);
            out.printf(Locale.ROOT, "side_delay_s=%.0f%n", s.delay);
            out.printf(Locale.ROOT, "b_closure_from_m=%.0f b_closure_to_m=%.0f%n", CLOSED_FROM_M, CLOSED_TO_M);
        }

        // The drive: the route's own first DRIVE_M, so the parked fix is its start.
        File drive = new File(args[2]);
        int end = 0;
        while (end < route.getPoints() - 1 && along[end] < DRIVE_M) end++;
        try (PrintWriter out = new PrintWriter(drive, StandardCharsets.US_ASCII)) {
            out.println("# P9 lab drive, written by tools/jni-host-test/LabTraffic.java from W75_N40.rd5 (OpenStreetMap");
            out.println(String.format(Locale.ROOT, "# data, ODbL): the first %.0f m of the lab position to Boston Common, %d points.", along[end], end + 1));
            for (int i = 0; i <= end; i++) {
                out.println(String.format(Locale.ROOT, "%.6f,%.6f", route.getLatitudes()[i], route.getLongitudes()[i]));
            }
        }
        say("drive %.0f m, %d points, written to %s", along[end], end + 1, drive);
        delete(work);
    }

    // ---- what the app would do with a scenario --------------------------------------------

    private static final class Offer {
        double delay, queueDelay, slowDelay, ownDelay, splitM;
        int saving = -1, nogos, hard, closedRuns;
        long ms;
        Route best;
        List<Route> found = new ArrayList<>();
        /** Each way round's saving and split, in the order found, for the log. */
        List<String> each = new ArrayList<>();
    }

    /** The delay and the best way round the app would offer, parked at the start (TrafficFeed.decide). */
    private static Offer offer(Router router, Route route, RouteOptions options, String flow, String incidents) throws Exception {
        List<FlowStretch> f = TrafficJson.INSTANCE.flow(new StringReader(flow));
        List<TrafficIncident> inc = TrafficJson.INSTANCE.incidents(new StringReader(incidents));
        RouteTraffic held = TrafficMatch.INSTANCE.match(route, f, inc);
        Offer o = new Offer();
        o.delay = held.delayAheadSeconds(0.0);
        o.slowDelay = held.getRuns().stream().filter(r -> r.getToM() <= 1_000.0).mapToDouble(r -> r.getDelaySeconds()).sum();
        o.queueDelay = o.delay - o.slowDelay;
        o.closedRuns = (int) held.getRuns().stream().filter(r -> r.getCongestion().name().equals("CLOSED")).count();
        List<Nogo> nogos = TrafficNogos.INSTANCE.forRoute(f, inc, held, fc -> TrafficCost.INSTANCE.costPerSecond(fc, options.getTopSpeedKmh()),
                TrafficNogos.MAX_NOGOS).getNogos();
        o.nogos = nogos.size();
        o.hard = (int) nogos.stream().filter(Nogo::getHard).count();
        if (o.delay < 180.0) return o;
        // LiveAlternatives.look: from the lead point ahead of the vehicle.
        RouteFollower follower = new RouteFollower(route, 9L);
        Route fromVehicle = route.remainder(follower.indexAt(0.0) + 1);
        Route ahead = fromVehicle.remainder(LiveAlternative.Companion.leadIndex(fromVehicle));
        double aheadAlongM = follower.alongNearest(ahead.getLatitudes()[0], ahead.getLongitudes()[0]);
        PreviewRequest trip = new PreviewRequest(1L, ahead.getLatitudes()[0], ahead.getLongitudes()[0], DEST_LAT, DEST_LON, null,
                options, ahead, nogos);
        long begun = System.nanoTime();
        AlternativeSearch.INSTANCE.find(router, trip, true, RoutePlanner.MAX_RUNNING_MS, e -> Unit.INSTANCE, () -> false,
                r -> { o.found.add(r); return Unit.INSTANCE; });
        o.ms = (System.nanoTime() - begun) / 1_000_000L;
        double aheadDelay = held.delayAheadSeconds(aheadAlongM);
        double[] along = TrafficArea.INSTANCE.metres(route);
        for (Route alternative : o.found) {
            RouteTraffic own = TrafficMatch.INSTANCE.match(alternative, f, inc);
            if (own.closureAhead(0.0) != null) continue;
            Integer split = LiveAlternative.Companion.splitIndex(ahead, alternative);
            if (split == null) continue;
            double ownDelay = own.delayAheadSeconds(0.0);
            if (aheadDelay - ownDelay <= 0.0) continue;
            int saving = (int) Math.round(ahead.getSeconds() + aheadDelay - alternative.getSeconds() - ownDelay);
            o.each.add(String.format(Locale.ROOT, "%d s (own delay %.0f, via %b, split %.0f m)", saving, ownDelay, alternative.getVia() != null,
                    alongNearest(route, along, alternative.getLatitudes()[split], alternative.getLongitudes()[split])));
            if (saving > o.saving) {
                o.saving = saving;
                o.best = alternative;
                o.ownDelay = ownDelay;
                o.splitM = alongNearest(route, along, alternative.getLatitudes()[split], alternative.getLongitudes()[split]);
            }
        }
        return o;
    }

    /** A street near the route, clear of every route in [clear] by SIDE_CLEAR_M, 150 to 300 m of it. */
    private static double[][] sideRoad(Router router, Route route, double[] along, RouteOptions options, List<Route> clear) throws Exception {
        for (double m = 1_200.0; m < along[along.length - 1] - 1_000.0; m += 400.0) {
            for (int sideSign : new int[] {-1, 1}) {
                double[] p = at(route, along, m);
                double[] b = at(route, along, m + 20.0);
                double bearing = RouteFollower.Companion.bearing(p[0], p[1], b[0], b[1]);
                double[] from = move(p[0], p[1], bearing + 90.0 * sideSign, 350.0);
                double[] to = move(from[0], from[1], bearing, 500.0);
                Route street;
                try {
                    street = router.compute(new RouteRequest(from[0], from[1], to[0], to[1], null, options), RoutePlanner.MAX_RUNNING_MS,
                            e -> Unit.INSTANCE);
                } catch (Exception e) {
                    continue;
                }
                List<double[]> kept = new ArrayList<>();
                for (int i = 0; i < street.getPoints(); i++) {
                    double[] q = {street.getLatitudes()[i], street.getLongitudes()[i]};
                    boolean ok = true;
                    for (Route r : clear) {
                        if (nearest(r, new double[][] {{q[0]}, {q[1]}}) < SIDE_CLEAR_M) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) kept.add(q);
                    else if (!kept.isEmpty()) break;
                }
                double length = 0.0;
                for (int i = 1; i < kept.size(); i++) {
                    length += RouteFollower.Companion.distance(kept.get(i - 1)[0], kept.get(i - 1)[1], kept.get(i)[0], kept.get(i)[1]);
                }
                if (length < 150.0) continue;
                // At most 300 m of it, laid LATERAL_M off as HERE's line is.
                List<double[]> piece = new ArrayList<>();
                double run = 0.0;
                piece.add(kept.get(0));
                for (int i = 1; i < kept.size() && run < 300.0; i++) {
                    run += RouteFollower.Companion.distance(kept.get(i - 1)[0], kept.get(i - 1)[1], kept.get(i)[0], kept.get(i)[1]);
                    piece.add(kept.get(i));
                }
                double[][] line = new double[2][piece.size()];
                for (int i = 0; i < piece.size(); i++) {
                    line[0][i] = Math.round(piece.get(i)[0] * 1e5) / 1e5;
                    line[1][i] = Math.round(piece.get(i)[1] * 1e5) / 1e5;
                }
                return line;
            }
        }
        throw new IllegalStateException("no side road clear of every route");
    }

    // ---- HERE's shape (plan §4), as TrafficFeedTest writes it --------------------------------

    private static String result(String description, double[][] line, int functionalClass, double speed, double free, double jamFactor,
            String traversability) {
        double length = 0.0;
        for (int i = 1; i < line[0].length; i++) {
            length += RouteFollower.Companion.distance(line[0][i - 1], line[1][i - 1], line[0][i], line[1][i]);
        }
        StringBuilder json = new StringBuilder();
        json.append("{\"location\":{\"description\":\"").append(description).append("\",\"length\":").append(number(length))
                .append(",\"shape\":{\"links\":[{\"points\":").append(points(line)).append(",\"length\":").append(number(length))
                .append(",\"functionalClass\":").append(functionalClass).append("}]}},\"currentFlow\":{");
        if (!Double.isNaN(speed)) {
            json.append("\"speed\":").append(number(speed)).append(",\"speedUncapped\":").append(number(speed)).append(',');
        }
        json.append("\"freeFlow\":").append(number(free)).append(",\"jamFactor\":").append(number(jamFactor))
                .append(",\"confidence\":0.9,\"traversability\":\"").append(traversability).append("\"}}");
        return json.toString();
    }

    private static String flowJson(List<String> results) {
        return "{\"sourceUpdated\":\"2026-09-28T17:00:00Z\",\"results\":[" + String.join(",", results) + "]}";
    }

    private static String closure(String id, double[][] line) {
        return "{\"location\":{\"length\":0,\"shape\":{\"links\":[{\"points\":" + points(line) + ",\"functionalClass\":3}]}},"
                + "\"incidentDetails\":{\"id\":\"" + id + "\",\"type\":\"roadClosure\",\"criticality\":\"critical\",\"roadClosed\":true,"
                + "\"startTime\":\"2026-09-28T12:00:00Z\",\"endTime\":\"2026-09-29T12:00:00Z\","
                + "\"description\":{\"value\":\"Lab closure\"},\"summary\":{\"value\":\"Lab closure\"},\"junctionTraversability\":\"allOpen\"}}";
    }

    private static String incidentsJson(List<String> results) {
        return "{\"sourceUpdated\":\"2026-09-28T17:00:00Z\",\"results\":[" + String.join(",", results) + "]}";
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
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static void write(File dir, String name, String flow, String incidents) throws Exception {
        File d = new File(dir, name);
        d.mkdirs();
        Files.writeString(new File(d, "flow.json").toPath(), flow + "\n", StandardCharsets.US_ASCII);
        Files.writeString(new File(d, "incidents.json").toPath(), incidents + "\n", StandardCharsets.US_ASCII);
    }

    // ---- geometry ------------------------------------------------------------------------

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

    private static double[][] reversed(double[][] line) {
        int n = line[0].length;
        double[][] r = new double[2][n];
        for (int i = 0; i < n; i++) {
            r[0][i] = line[0][n - 1 - i];
            r[1][i] = line[1][n - 1 - i];
        }
        return r;
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

    private static double alongNearest(Route route, double[] along, double lat, double lon) {
        int best = 0;
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < route.getPoints(); i++) {
            double d = RouteFollower.Companion.distance(route.getLatitudes()[i], route.getLongitudes()[i], lat, lon);
            if (d < nearest) {
                nearest = d;
                best = i;
            }
        }
        return along[best];
    }

    /** The least distance from any point of [line] to [route]'s line, metres. */
    private static double nearest(Route route, double[][] line) {
        double best = Double.MAX_VALUE;
        for (int k = 0; k < line[0].length; k++) {
            double lat = line[0][k], lon = line[1][k];
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
        }
        return best;
    }

    private static void say(String format, Object... args) {
        System.out.println("lab-traffic: " + String.format(Locale.ROOT, format, args));
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
