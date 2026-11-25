// A synthetic OsmAnd .obf file with a routing section, built from the vendored OBF.proto
// and OsmAnd's BinaryMapRouteReaderAdapter, for OsmAndRoutingTest and ObfMapTilesTest; and
// the same file with, before it, an address section whose one city record will not decode.
//
// Rules numbered in order, a root box whose own data block holds the roads, a child box
// elsewhere whose shiftToData is nonsense (following it would fail, so a query near the roads
// must prune it), points as deltas at zoom 27, point types and point names, text tags through
// the block's string table. The roads: a motorway north from 30.30,-97.70 with only a ref
// (I 35), an exit ramp east off it at its middle (exit 23A, "Main Street", to I 35 South, Waco
// and Temple) with no name, a primary road across the ramp's end with a name and a ref
// (Main Street, FM 1825), and an on-ramp from that road back onto the motorway with a blank
// name, stored from the motorway end (destination:backward Dallas, destination:forward Nowhere).
// Past the primary road's east end, a track straight on east to a corner, the only short way
// there: the other is an unclassified loop 2.8 km north, across and back. And 5 km south of
// the motorway, in a root box and data block of its own, a short residential road no other
// road joins.
//
// Two variants for OsmAndRoutingTest: the ramp with no destination signs, with or without a
// junction:name of its own; and a far track, in a root box and data block of its own, on
// east 6.7 km from the corner, its far end 3 km from any other road.
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
        DEST_FWD = 9, DEST_BWD = 10, TRACK = 11, UNCLASSIFIED = 12, RESIDENTIAL = 13, JUNCTION_NAME = 14;

    /** The ramp's text tags in the usual file: destination:ref I 35 South, destination Waco;Temple. */
    private static final int[][] SIGNED_RAMP = {{DEST_REF, 2}, {DEST, 3}};

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

    /** The track: from the primary road's east end, 770 m east to the corner. */
    static double[][] track() {
        return new double[][] {{LAT + 0.0075, LON + 0.012}, {LAT + 0.0075, LON + 0.016}, {LAT + 0.0075, LON + 0.020}};
    }

    /** The long way to the track's corner: north, east and back south, 6.3 km. */
    static double[][] loop() {
        return new double[][] {{LAT + 0.0075, LON + 0.012}, {LAT + 0.0325, LON + 0.012}, {LAT + 0.0325, LON + 0.020},
            {LAT + 0.0075, LON + 0.020}};
    }

    /** The island: 290 m of residential road 5 km south of the motorway, joined to nothing. */
    static double[][] island() {
        return new double[][] {{LAT - 0.045, LON}, {LAT - 0.045, LON + 0.003}};
    }

    /** The far track: on east from the track's corner, 6.7 km, in a box of its own. */
    static double[][] farTrack() {
        return new double[][] {{LAT + 0.0075, LON + 0.020}, {LAT + 0.0075, LON + 0.050}, {LAT + 0.0075, LON + 0.080},
            {LAT + 0.0075, LON + 0.090}};
    }

    /** The whole file. */
    static byte[] syntheticObf() throws IOException {
        return file(null, SIGNED_RAMP, false);
    }

    /**
     * The whole file with no destination signs on the ramp; with [junctionName], the ramp
     * carries junction:name Lamar Boulevard instead.
     */
    static byte[] syntheticObfWithUnsignedRamp(boolean junctionName) throws IOException {
        return file(null, junctionName ? new int[][] {{JUNCTION_NAME, 9}} : new int[0][], false);
    }

    /** The whole file and the far track. */
    static byte[] syntheticObfWithFarTrack() throws IOException {
        return file(null, SIGNED_RAMP, true);
    }

    /**
     * The whole file with an address section ahead of the routing one, holding one block of
     * cities and towns whose one record, a town, carries a field of wire type 7, which no
     * protobuf reader can skip. Ahead, so every route read lies past the bad record.
     */
    static byte[] syntheticObfWithBrokenTowns() throws IOException {
        ByteArrayOutputStream city = new ByteArrayOutputStream();
        tag(city, 1, 0); varint(city, 1);                      // CityIndex.city_type: town
        tag(city, 9, 7); varint(city, 0);                      // no such wire type
        ByteArrayOutputStream cities = new ByteArrayOutputStream();
        tag(cities, 2, 0); varint(cities, 1);                  // CitiesIndex.type: cities and towns
        message(cities, 5, city.toByteArray());
        ByteArrayOutputStream address = new ByteArrayOutputStream();
        string(address, 1, "Synthetic");
        tag(address, 6, 6); be4(address, cities.size()); address.write(cities.toByteArray(), 0, cities.size());
        return file(address.toByteArray(), SIGNED_RAMP, false);
    }

    private static byte[] file(byte[] address, int[][] rampNames, boolean farTrack) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        tag(f, 1, 0); varint(f, 2);
        if (address != null) {
            tag(f, 7, 6); be4(f, address.length); f.write(address, 0, address.length);
        }
        byte[] routing = routingSection(rampNames, farTrack);
        tag(f, 9, 6); be4(f, routing.length); f.write(routing, 0, routing.length);
        tag(f, 32, 0); varint(f, 2);
        return f.toByteArray();
    }

    private static final List<String> STRINGS = List.of("23A", "Main Street", "I 35 South", "Waco;Temple", "Dallas", "Nowhere",
        "I 35", "FM 1825", " ", "Lamar Boulevard");

    private static byte[] routingSection(int[][] rampNames, boolean farTrack) throws IOException {
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
        rule(head, "highway", "track");
        rule(head, "highway", "unclassified");
        rule(head, "highway", "residential");
        rule(head, "junction:name", "");

        // Each root box with its data block: {left, right, top, bottom} and the block.
        List<long[]> boxes = new java.util.ArrayList<>();
        List<byte[]> blocks = new java.util.ArrayList<>();
        long left = ObfTiles.INSTANCE.x31(LON - 0.01), right = ObfTiles.INSTANCE.x31(LON + 0.03);
        long top = ObfTiles.INSTANCE.y31(LAT + 0.04), bottom = ObfTiles.INSTANCE.y31(LAT - 0.01);
        boxes.add(new long[] {left, right, top, bottom});
        blocks.add(block(left, top, rampNames));
        // The island has a root box and a data block of its own, so a search near it loads
        // only its road: OsmAnd snaps a point to any road in the blocks it loads.
        long islandLeft = ObfTiles.INSTANCE.x31(LON - 0.001), islandRight = ObfTiles.INSTANCE.x31(LON + 0.004);
        long islandTop = ObfTiles.INSTANCE.y31(LAT - 0.044), islandBottom = ObfTiles.INSTANCE.y31(LAT - 0.046);
        ByteArrayOutputStream island = new ByteArrayOutputStream();
        message(island, 6, road(islandLeft, islandTop, island(), new int[] {RESIDENTIAL}, new int[0][], new int[0][], new int[0][], 7));
        boxes.add(new long[] {islandLeft, islandRight, islandTop, islandBottom});
        blocks.add(island.toByteArray());
        if (farTrack) {
            // Likewise, so a search near its far end loads only the track.
            long farLeft = ObfTiles.INSTANCE.x31(LON + 0.019), farRight = ObfTiles.INSTANCE.x31(LON + 0.091);
            long farTop = ObfTiles.INSTANCE.y31(LAT + 0.0085), farBottom = ObfTiles.INSTANCE.y31(LAT + 0.0065);
            ByteArrayOutputStream far = new ByteArrayOutputStream();
            message(far, 6, road(farLeft, farTop, farTrack(), new int[] {TRACK}, new int[0][], new int[0][], new int[0][], 8));
            boxes.add(new long[] {farLeft, farRight, farTop, farBottom});
            blocks.add(far.toByteArray());
        }
        // Two passes: a root box's shiftToData is fixed-width, so its length does not move.
        // Only the first box has the nonsense child.
        int n = boxes.size();
        byte[][] roots = new byte[n][];
        for (int i = 0; i < n; i++) roots[i] = rootBox(boxes.get(i), 0, i == 0);
        int[] rootBodies = new int[n], blockAts = new int[n];
        int at = head.size();
        for (int i = 0; i < n; i++) {
            rootBodies[i] = at + 1 + 4;                        // tag(3, 6), 4-byte length
            at = rootBodies[i] + roots[i].length;
        }
        for (int i = 0; i < n; i++) {
            blockAts[i] = at + 1;                              // tag(5, 2), then the block's length varint
            at = blockAts[i] + varintSize(blocks.get(i).length) + blocks.get(i).length;
        }
        for (int i = 0; i < n; i++) roots[i] = rootBox(boxes.get(i), blockAts[i] - rootBodies[i], i == 0);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(head.toByteArray(), 0, head.size());
        for (byte[] root : roots) {
            tag(s, 3, 6); be4(s, root.length); s.write(root, 0, root.length);
        }
        for (int i = 0; i < n; i++) {
            if (s.size() + 1 != blockAts[i]) throw new AssertionError("block layout moved");
            message(s, 5, blocks.get(i));
        }
        return s.toByteArray();
    }

    private static int varintSize(long v) {
        int n = 1;
        while ((v >>>= 7) != 0) n++;
        return n;
    }

    private static void rule(ByteArrayOutputStream b, String tag, String value) throws IOException {
        ByteArrayOutputStream r = new ByteArrayOutputStream();
        string(r, 3, tag); string(r, 5, value);
        message(b, 2, r.toByteArray());
    }

    private static byte[] rootBox(long[] box, int shift, boolean nonsenseChild) throws IOException {
        long left = box[0], right = box[1], top = box[2], bottom = box[3];
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        tag(b, 1, 0); varint(b, zig((int) left)); tag(b, 2, 0); varint(b, zig((int) right));
        tag(b, 3, 0); varint(b, zig((int) top)); tag(b, 4, 0); varint(b, zig((int) bottom));
        tag(b, 5, 5); be4(b, shift);
        if (!nonsenseChild) return b.toByteArray();
        // A child a degree east, with nonsense for its data: a query here never goes there.
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        int dx = (int) (ObfTiles.INSTANCE.x31(LON + 1.0) - left);
        tag(c, 1, 0); varint(c, zig(dx)); tag(c, 2, 0); varint(c, zig(dx + 1000));
        tag(c, 3, 0); varint(c, zig(0)); tag(c, 4, 0); varint(c, zig(0));
        tag(c, 5, 5); be4(c, 0x7ffffff0);
        tag(b, 7, 6); be4(b, c.size()); b.write(c.toByteArray(), 0, c.size());
        return b.toByteArray();
    }

    private static byte[] block(long left, long top, int[][] rampNames) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        // the motorway, its junction at point 5 with a number and a name
        message(b, 6, road(left, top, motorway(), new int[] {MOTORWAY}, new int[][] {{REF, 6}},
            new int[][] {{5, JUNCTION}}, new int[][] {{5, REF, 0}, {5, NAME, 1}}, 1));
        message(b, 6, road(left, top, ramp(), new int[] {LINK}, rampNames,
            new int[0][], new int[0][], 2));
        message(b, 6, road(left, top, primary(), new int[] {PRIMARY}, new int[][] {{NAME, 1}, {REF, 7}}, new int[0][],
            new int[0][], 3));
        message(b, 6, road(left, top, onRampStored(), new int[] {LINK}, new int[][] {{DEST_BWD, 4}, {DEST_FWD, 5}, {NAME, 8}},
            new int[0][], new int[0][], 4));
        message(b, 6, road(left, top, track(), new int[] {TRACK}, new int[0][], new int[0][], new int[0][], 5));
        message(b, 6, road(left, top, loop(), new int[] {UNCLASSIFIED}, new int[0][], new int[0][], new int[0][], 6));
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
