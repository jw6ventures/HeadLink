// Exit numbers and sign text from OsmAnd's routing data, on a desktop JVM (docs/navigation.md,
// "Exit signs").
//
// A synthetic .obf file built here from the vendored OBF.proto and OsmAnd's
// BinaryMapRouteReaderAdapter: rules numbered in order, a root box whose own data block holds
// the roads, a child box elsewhere whose shiftToData is nonsense (following it would fail, so a
// query near the roads must prune it), points as deltas at zoom 27, point types and point
// names, text tags through the block's string table. Then the matching on routes over those
// roads: an exit with its number and destinations, a "keep left" that stays on the highway at
// the same junction and gets nothing, an on-ramp with a destination and no number, a ramp
// stored against the way it is driven (its destination:backward), and the words on the card,
// the cluster and the voice, through RouteFollower and the route file.
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import btools.router.VoiceHintReader;
import com.jw6ventures.headlink.ExitSign;
import com.jw6ventures.headlink.ExitSigns;
import com.jw6ventures.headlink.GeoBox;
import com.jw6ventures.headlink.Maneuver;
import com.jw6ventures.headlink.Maneuvers;
import com.jw6ventures.headlink.NextStep;
import com.jw6ventures.headlink.ObfFile;
import com.jw6ventures.headlink.ObfRoadWay;
import com.jw6ventures.headlink.ObfSource;
import com.jw6ventures.headlink.ObfTiles;
import com.jw6ventures.headlink.Route;
import com.jw6ventures.headlink.RouteFollower;
import com.jw6ventures.headlink.RoutePreview;
import com.jw6ventures.headlink.SpokenText;

public final class ExitSignsTest {
    private static int passed;

    private static final double LAT = 30.30, LON = -97.70;

    // Rules, numbered from 1 in file order.
    private static final int MOTORWAY = 1, LINK = 2, JUNCTION = 3, REF = 4, NAME = 5, DEST = 6, DEST_REF = 7, PRIMARY = 8,
        DEST_FWD = 9, DEST_BWD = 10;

    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("exitsigns", ".obf");
        try {
            Files.write(file, syntheticObf());
            try (ObfFile obf = ObfFile.Companion.open(file.toFile())) {
                theReader(obf);
                List<ObfSource> sources = List.of(obf);
                anExit(sources);
                keepingLeftIsNotTheExit(sources);
                anOnRampSaysWhereItGoes(sources);
            }
        } finally {
            Files.deleteIfExists(file);
        }
        theWords();
        System.out.println("jni-host-test: ok " + passed + " exit sign checks");
    }

    // --- geometry: a motorway north, an exit ramp east off it at its middle, a primary road
    // across the ramp's end, and an on-ramp from that road back onto the motorway, stored
    // from the motorway end.

    private static double[][] motorway() {
        double[][] p = new double[11][];
        for (int i = 0; i <= 10; i++) p[i] = new double[] {LAT + 0.001 * i, LON};
        return p;
    }

    private static double[][] ramp() {
        return new double[][] {
            {LAT + 0.005, LON}, {LAT + 0.0058, LON + 0.0006}, {LAT + 0.0066, LON + 0.0014},
            {LAT + 0.0072, LON + 0.0024}, {LAT + 0.0075, LON + 0.0035},
        };
    }

    private static double[][] primary() {
        return new double[][] {{LAT + 0.0075, LON + 0.012}, {LAT + 0.0075, LON + 0.008}, {LAT + 0.0075, LON + 0.0035}};
    }

    /** The on-ramp as stored: from the motorway end back to the primary road. */
    private static double[][] onRampStored() {
        return new double[][] {{LAT + 0.010, LON}, {LAT + 0.0095, LON + 0.0005}, {LAT + 0.0085, LON + 0.004}, {LAT + 0.0075, LON + 0.008}};
    }

    // --- the file

    private static byte[] syntheticObf() throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        tag(f, 1, 0); varint(f, 2);
        byte[] routing = routingSection();
        tag(f, 9, 6); be4(f, routing.length); f.write(routing, 0, routing.length);
        tag(f, 32, 0); varint(f, 2);
        return f.toByteArray();
    }

    private static final List<String> STRINGS = List.of("23A", "Main Street", "I 35 South", "Waco;Temple", "Dallas", "Nowhere");

    private static byte[] routingSection() throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        string(head, 1, "Synthetic");
        rule(head, "highway", "motorway");
        rule(head, "highway", "motorway_link");
        rule(head, "highway", "motorway_junction");
        rule(head, "ref", "");
        rule(head, "name", "");
        rule(head, "destination", "");
        rule(head, "destination:ref", "");
        rule(head, "highway", "primary");
        rule(head, "destination:forward", "");
        rule(head, "destination:backward", "");

        long left = ObfTiles.INSTANCE.x31(LON - 0.01), right = ObfTiles.INSTANCE.x31(LON + 0.02);
        long top = ObfTiles.INSTANCE.y31(LAT + 0.02), bottom = ObfTiles.INSTANCE.y31(LAT - 0.01);
        byte[] block = block(left, top);
        // Two passes: the root box's shiftToData is fixed-width, so its length does not move.
        byte[] root = rootBox(left, right, top, bottom, 0);
        int rootBody = head.size() + 1 + 4;                    // tag(3, 6), 4-byte length
        int blockAt = rootBody + root.length + 1;              // tag(5, 2), then the block's length varint
        root = rootBox(left, right, top, bottom, blockAt - rootBody);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(head.toByteArray(), 0, head.size());
        tag(s, 3, 6); be4(s, root.length); s.write(root, 0, root.length);
        if (s.size() + 1 != blockAt) throw new AssertionError("block layout moved");
        message(s, 5, block);
        return s.toByteArray();
    }

    private static void rule(ByteArrayOutputStream b, String tag, String value) throws IOException {
        ByteArrayOutputStream r = new ByteArrayOutputStream();
        string(r, 3, tag); string(r, 5, value);
        message(b, 2, r.toByteArray());
    }

    private static byte[] rootBox(long left, long right, long top, long bottom, int shift) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        tag(b, 1, 0); varint(b, zig((int) left)); tag(b, 2, 0); varint(b, zig((int) right));
        tag(b, 3, 0); varint(b, zig((int) top)); tag(b, 4, 0); varint(b, zig((int) bottom));
        tag(b, 5, 5); be4(b, shift);
        // A child a degree east, with nonsense for its data: a query here never goes there.
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        int dx = (int) (ObfTiles.INSTANCE.x31(LON + 1.0) - left);
        tag(c, 1, 0); varint(c, zig(dx)); tag(c, 2, 0); varint(c, zig(dx + 1000));
        tag(c, 3, 0); varint(c, zig(0)); tag(c, 4, 0); varint(c, zig(0));
        tag(c, 5, 5); be4(c, 0x7ffffff0);
        tag(b, 7, 6); be4(b, c.size()); b.write(c.toByteArray(), 0, c.size());
        return b.toByteArray();
    }

    private static byte[] block(long left, long top) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        // the motorway, its junction at point 5 with a number and a name
        message(b, 6, road(left, top, motorway(), new int[] {MOTORWAY}, new int[0][],
            new int[][] {{5, JUNCTION}}, new int[][] {{5, REF, 0}, {5, NAME, 1}}, 1));
        message(b, 6, road(left, top, ramp(), new int[] {LINK}, new int[][] {{DEST_REF, 2}, {DEST, 3}},
            new int[0][], new int[0][], 2));
        message(b, 6, road(left, top, primary(), new int[] {PRIMARY}, new int[0][], new int[0][], new int[0][], 3));
        message(b, 6, road(left, top, onRampStored(), new int[] {LINK}, new int[][] {{DEST_BWD, 4}, {DEST_FWD, 5}},
            new int[0][], new int[0][], 4));
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        for (String v : STRINGS) string(table, 1, v);
        message(b, 8, table.toByteArray());
        return b.toByteArray();
    }

    private static byte[] road(long left, long top, double[][] points, int[] types, int[][] names, int[][] pointTypes,
        int[][] pointNames, int id) throws IOException {
        ByteArrayOutputStream r = new ByteArrayOutputStream();
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        int px = (int) (left >> 4), py = (int) (top >> 4);
        for (double[] pt : points) {
            int x = (int) (ObfTiles.INSTANCE.x31(pt[1]) >> 4), y = (int) (ObfTiles.INSTANCE.y31(pt[0]) >> 4);
            varint(p, zig(x - px)); varint(p, zig(y - py));
            px = x; py = y;
        }
        message(r, 1, p.toByteArray());
        if (pointTypes.length > 0) {
            ByteArrayOutputStream t = new ByteArrayOutputStream();
            for (int[] pt : pointTypes) {
                varint(t, pt[0]);
                ByteArrayOutputStream ids = new ByteArrayOutputStream();
                for (int i = 1; i < pt.length; i++) varint(ids, pt[i]);
                varint(t, ids.size()); t.write(ids.toByteArray(), 0, ids.size());
            }
            message(r, 4, t.toByteArray());
        }
        if (pointNames.length > 0) {
            ByteArrayOutputStream n = new ByteArrayOutputStream();
            for (int[] pn : pointNames) { varint(n, pn[0]); varint(n, pn[1]); varint(n, pn[2]); }
            message(r, 5, n.toByteArray());
        }
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        for (int type : types) varint(t, type);
        message(r, 7, t.toByteArray());
        tag(r, 12, 0); varint(r, id);
        if (names.length > 0) {
            ByteArrayOutputStream n = new ByteArrayOutputStream();
            for (int[] nm : names) { varint(n, nm[0]); varint(n, nm[1]); }
            message(r, 14, n.toByteArray());
        }
        return r.toByteArray();
    }

    // --- the reader

    private static void theReader(ObfFile obf) throws IOException {
        check(obf.getHasRouting(), "the routing section is found");
        check(obf.getToc().getSkippedSections() == 1 && obf.getToc().getSections().isEmpty(),
            "and still counted as a section the table of contents passes over");
        List<ObfRoadWay> ways = obf.roadWays(ExitSigns.INSTANCE.around(LAT + 0.005, LON, 200));
        check(ways.size() == 4, "the block's four roads, and the far child box pruned unread (" + ways.size() + ")");
        ObfRoadWay mw = ways.get(0);
        check("motorway".equals(mw.getHighway()) && mw.getLatitudes().length == 11, "the motorway and its points");
        // Zoom 27 is about 0.3 m a step here, and each point is truncated to it.
        check(Math.abs(mw.getLatitudes()[5] - (LAT + 0.005)) < 5e-6 && Math.abs(mw.getLongitudes()[5] - LON) < 5e-6,
            "points decoded to within half a metre (" + mw.getLatitudes()[5] + ", " + mw.getLongitudes()[5] + ")");
        Map<String, String> junction = mw.getPointTags().get(5);
        check(junction != null && "motorway_junction".equals(junction.get("highway")) && "23A".equals(junction.get("ref"))
            && "Main Street".equals(junction.get("name")), "the junction point's type, number and name");
        check(mw.getPointTags().size() == 1, "and no tags on any other point");
        ObfRoadWay link = ways.get(1);
        check("motorway_link".equals(link.getHighway()) && "Waco;Temple".equals(link.getTags().get("destination"))
            && "I 35 South".equals(link.getTags().get("destination:ref")), "the ramp's text tags through the string table");
        check(obf.roadWays(new GeoBox(LAT + 5, LON + 5, LAT + 5.1, LON + 5.1)).isEmpty(), "nothing far away");
    }

    // --- matching

    /** A route over [points], with one maneuver of [command] at [at]. */
    private static Route route(List<double[]> points, int at, int command) {
        double[] lats = new double[points.size()], lons = new double[points.size()];
        for (int i = 0; i < points.size(); i++) { lats[i] = points.get(i)[0]; lons[i] = points.get(i)[1]; }
        return new Route(lats, lons, 1000, 60, new int[] {at}, new int[] {0}, new int[] {command});
    }

    private static List<double[]> join(double[][] a, int aFrom, int aTo, double[][] b, int bFrom, int bTo, boolean bReversed) {
        List<double[]> out = new ArrayList<>();
        for (int i = aFrom; i <= aTo; i++) out.add(a[i]);
        if (!bReversed) for (int i = bFrom; i <= bTo; i++) out.add(b[i]);
        else for (int i = bTo; i >= bFrom; i--) out.add(b[i]);
        return out;
    }

    private static void anExit(List<ObfSource> sources) {
        Route r = route(join(motorway(), 0, 5, ramp(), 1, 4, false), 5, VoiceHintReader.EXIT_RIGHT);
        Route signed = ExitSigns.INSTANCE.sign(r, sources);
        ExitSign sign = signed.exitSign(0);
        check(sign != null && "23A".equals(sign.getNumber()) && "I 35 South / Waco / Temple".equals(sign.getToward()),
            "an exit: the junction's number and the ramp's destinations (" + sign + ")");
        check("Exit 23A: I 35 South / Waco / Temple".equals(sign.getLabel()), "the card's line");
        check(signed.withTimeScale(1.2).exitSign(0) == sign && signed.withRoads(new int[] {0}, new String[] {"I 35"}).exitSign(0) == sign
            && signed.withNogos(3, false).exitSign(0) == sign, "kept through scaling, naming and traffic marking");
        check(signed.remainder(2).exitSign(0) == sign, "and in what is left of the route ahead of it");
        check(signed.remainder(5).getManeuverIndices().length == 1 && signed.remainder(5).exitSign(0) == sign,
            "including from the exit itself");

        RouteFollower follower = new RouteFollower(signed, 7);
        NextStep step = follower.update(LAT + 0.001, LON, 0).getStep();
        check(step != null && step.getSign() == sign, "the follower hands the sign to the step");
        check("Take exit 23A toward I 35 South / Waco / Temple".equals(step.getInstruction()),
            "the cluster's instruction (" + step.getInstruction() + ")");
        check("Exit 23A: I 35 South / Waco / Temple".equals(step.getOnto()), "and its road name, which the card shows");
        check("Take exit 23A toward Interstate 35 South, Waco, Temple".equals(SpokenText.INSTANCE.instruction(step, true)),
            "spoken as road names are, the slashes a pause (" + SpokenText.INSTANCE.instruction(step, true) + ")");
        check("Take exit 23A".equals(SpokenText.INSTANCE.instruction(step, false)), "and with street names off, the number alone");

        check(ExitSigns.INSTANCE.sign(r, List.of()) == r, "no maps: the route unchanged");
    }

    private static void keepingLeftIsNotTheExit(List<ObfSource> sources) {
        Route r = route(join(motorway(), 0, 10, ramp(), 0, -1, false), 5, VoiceHintReader.KEEP_LEFT);
        Route signed = ExitSigns.INSTANCE.sign(r, sources);
        check(signed == r && signed.exitSign(0) == null, "keeping left on the motorway at the junction is not the exit");
    }

    private static void anOnRampSaysWhereItGoes(List<ObfSource> sources) {
        Route r = route(join(primary(), 0, 1, onRampStored(), 0, 2, true), 1, VoiceHintReader.TURN_RIGHT);
        ExitSign sign = ExitSigns.INSTANCE.sign(r, sources).exitSign(0);
        check(sign != null && sign.getNumber() == null && "Dallas".equals(sign.getToward()),
            "an on-ramp driven against its stored order: destination:backward, and no number (" + sign + ")");
        check("Dallas".equals(sign.getLabel()), "its card line is where it goes");
    }

    // --- the words

    private static void theWords() throws IOException {
        Maneuver right = Maneuvers.INSTANCE.of(VoiceHintReader.EXIT_RIGHT, 0);
        Maneuver left = Maneuvers.INSTANCE.of(VoiceHintReader.EXIT_LEFT, 0);
        Maneuver keepRight = Maneuvers.INSTANCE.of(VoiceHintReader.KEEP_RIGHT, 0);
        ExitSign both = new ExitSign("12", "El Paso");
        ExitSign number = new ExitSign("12", null);
        ExitSign toward = new ExitSign(null, "El Paso");
        check("Take exit 12 toward El Paso".equals(Maneuvers.INSTANCE.exitInstruction(right, both, both.getToward())), "number and place");
        check("Take exit 12 on the left".equals(Maneuvers.INSTANCE.exitInstruction(left, number, null)), "a left exit says so");
        check("Keep right toward El Paso".equals(Maneuvers.INSTANCE.exitInstruction(keepRight, toward, toward.getToward())),
            "no number: the maneuver's words and the place");
        check("Exit 12".equals(number.getLabel()) && "El Paso".equals(toward.getLabel()), "labels with one part");
        try {
            new ExitSign(null, null);
            check(false, "an empty sign is refused");
        } catch (IllegalArgumentException expected) {
            check(true, "an empty sign is refused");
        }

        // The route file carries signs, and an unsigned maneuver stays unsigned.
        Route r = new Route(new double[] {LAT, LAT + 0.01, LAT + 0.02}, new double[] {LON, LON, LON}, 2224, 120,
            new int[] {1, 2}, new int[] {0, 0}, new int[] {VoiceHintReader.EXIT_RIGHT, VoiceHintReader.TURN_LEFT},
            new float[] {0f, 60f, 120f}, new int[0], new String[0], 121, null, 0, false,
            new ExitSign[] {both, null});
        File dir = Files.createTempDirectory("exitsigns").toFile();
        try {
            File file = RoutePreview.INSTANCE.choiceFile(dir);
            RoutePreview.INSTANCE.writeChoice(file, 3L, r);
            Route back = RoutePreview.INSTANCE.readChoice(file).getSecond();
            check(both.equals(back.exitSign(0)) && back.exitSign(1) == null, "signs cross the route file");
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    // --- encoding

    private static long zig(int n) { return ((long) (n << 1) ^ (n >> 31)) & 0xffffffffL; }

    private static void tag(ByteArrayOutputStream b, int field, int wire) { varint(b, ((long) field << 3) | wire); }

    private static void varint(ByteArrayOutputStream b, long v) {
        while ((v & ~0x7fL) != 0) { b.write((int) ((v & 0x7f) | 0x80)); v >>>= 7; }
        b.write((int) v);
    }

    private static void be4(ByteArrayOutputStream b, int v) {
        b.write(v >>> 24); b.write(v >>> 16); b.write(v >>> 8); b.write(v);
    }

    private static void string(ByteArrayOutputStream b, int field, String s) {
        byte[] u = s.getBytes(StandardCharsets.UTF_8);
        tag(b, field, 2); varint(b, u.length); b.write(u, 0, u.length);
    }

    private static void message(ByteArrayOutputStream b, int field, byte[] m) {
        tag(b, field, 2); varint(b, m.length); b.write(m, 0, m.length);
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("exit signs: " + what);
        passed++;
    }
}
