// A synthetic OsmAnd .obf file with only a routing section, built from the vendored OBF.proto
// and OsmAnd's BinaryMapRouteReaderAdapter, for OsmAndRoutingTest and ObfMapTilesTest.
//
// Rules numbered in order, a root box whose own data block holds the roads, a child box
// elsewhere whose shiftToData is nonsense (following it would fail, so a query near the roads
// must prune it), points as deltas at zoom 27, point types and point names, text tags through
// the block's string table. The roads: a motorway north from 30.30,-97.70 with only a ref
// (I 35), an exit ramp east off it at its middle (exit 23A, "Main Street", to I 35 South, Waco
// and Temple) with no name, a primary road across the ramp's end with a name and a ref
// (Main Street, FM 1825), and an on-ramp from that road back onto the motorway with a blank
// name, stored from the motorway end (destination:backward Dallas, destination:forward Nowhere).
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.jw6ventures.headlink.ObfTiles;

final class SyntheticObf {
    private SyntheticObf() {}

    static final double LAT = 30.30, LON = -97.70;

    // Rules, numbered from 1 in file order.
    private static final int MOTORWAY = 1, LINK = 2, JUNCTION = 3, REF = 4, NAME = 5, DEST = 6, DEST_REF = 7, PRIMARY = 8,
        DEST_FWD = 9, DEST_BWD = 10;

    static double[][] motorway() {
        double[][] p = new double[11][];
        for (int i = 0; i <= 10; i++) p[i] = new double[] {LAT + 0.001 * i, LON};
        return p;
    }

    static double[][] ramp() {
        return new double[][] {
            {LAT + 0.005, LON}, {LAT + 0.0058, LON + 0.0006}, {LAT + 0.0066, LON + 0.0014},
            {LAT + 0.0072, LON + 0.0024}, {LAT + 0.0075, LON + 0.0035},
        };
    }

    static double[][] primary() {
        return new double[][] {{LAT + 0.0075, LON + 0.012}, {LAT + 0.0075, LON + 0.008}, {LAT + 0.0075, LON + 0.0035}};
    }

    /** The on-ramp as stored: from the motorway end back to the primary road. */
    static double[][] onRampStored() {
        return new double[][] {{LAT + 0.010, LON}, {LAT + 0.0095, LON + 0.0005}, {LAT + 0.0085, LON + 0.004}, {LAT + 0.0075, LON + 0.008}};
    }

    /** The whole file. */
    static byte[] syntheticObf() throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        tag(f, 1, 0); varint(f, 2);
        byte[] routing = routingSection();
        tag(f, 9, 6); be4(f, routing.length); f.write(routing, 0, routing.length);
        tag(f, 32, 0); varint(f, 2);
        return f.toByteArray();
    }

    private static final List<String> STRINGS = List.of("23A", "Main Street", "I 35 South", "Waco;Temple", "Dallas", "Nowhere",
        "I 35", "FM 1825", " ");

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
        message(b, 6, road(left, top, motorway(), new int[] {MOTORWAY}, new int[][] {{REF, 6}},
            new int[][] {{5, JUNCTION}}, new int[][] {{5, REF, 0}, {5, NAME, 1}}, 1));
        message(b, 6, road(left, top, ramp(), new int[] {LINK}, new int[][] {{DEST_REF, 2}, {DEST, 3}},
            new int[0][], new int[0][], 2));
        message(b, 6, road(left, top, primary(), new int[] {PRIMARY}, new int[][] {{NAME, 1}, {REF, 7}}, new int[0][],
            new int[0][], 3));
        message(b, 6, road(left, top, onRampStored(), new int[] {LINK}, new int[][] {{DEST_BWD, 4}, {DEST_FWD, 5}, {NAME, 8}},
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
}
