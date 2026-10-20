// P11.2-P11.3's OBF reader on a desktop JVM (docs/p11-plan.md §3, docs/p11-2-3-results.md).
//
// Always: the wire walker on hand-made bytes (varints, zigzag, OsmAnd's wire type 6 with a
// 4-byte and an 8-byte big-endian length, unknown fields of every wire type skipped, limits,
// short reads), OsmAnd's tile arithmetic, the button rules, the " gz " text values, and a
// whole synthetic .obf file built here from the vendored OBF.proto and OsmAnd's reader: the
// table of contents, cities, a postcode, streets, an odd interpolation range, the POI box
// tree pruned by area and by category, precisionXY, text-tag names, and one place stored
// under two categories.
//
// With a directory holding OsmAnd's Texas files as the argument (OBF_DIR), it also reads the
// real Austin, Amarillo and Lubbock (older layout) files and checks decoded positions against
// OpenStreetMap's own (Nominatim, 3 October 2026) in metres, and times the reader. Without
// it those checks are skipped and the summary line says so. The files are never committed.
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPOutputStream;
import com.jw6ventures.headlink.ArrayBytes;
import com.jw6ventures.headlink.CityType;
import com.jw6ventures.headlink.GeoBox;
import com.jw6ventures.headlink.Interpolation;
import com.jw6ventures.headlink.ObfBuilding;
import com.jw6ventures.headlink.ObfCity;
import com.jw6ventures.headlink.ObfFile;
import com.jw6ventures.headlink.ObfPoi;
import com.jw6ventures.headlink.ObfPoiKinds;
import com.jw6ventures.headlink.ObfSection;
import com.jw6ventures.headlink.ObfSectionType;
import com.jw6ventures.headlink.ObfStreet;
import com.jw6ventures.headlink.ObfTiles;
import com.jw6ventures.headlink.ObfWire;
import com.jw6ventures.headlink.PoiKind;

public final class ObfTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        varintsAndZigzag();
        osmAndsBigEndianLengths();
        unknownFieldsOfEveryTypeAreSkipped();
        limitsAndShortReads();
        tileArithmetic();
        theButtonsRules();
        zippedText();
        aSyntheticFile();
        File dir = args.length > 0 && !args[0].isEmpty() ? new File(args[0]) : null;
        File austin = dir == null ? null : new File(dir, "Us_texas_austin_northamerica.obf");
        if (austin != null && austin.isFile()) {
            int synthetic = passed;
            realAustin(austin);
            File amarillo = new File(dir, "Us_texas_amarillo_northamerica.obf");
            if (amarillo.isFile()) realAmarillo(amarillo);
            File lubbock = new File(dir, "Us_texas_lubbock_northamerica.old-2026-06.obf");
            if (lubbock.isFile()) realLubbockOlderLayout(lubbock);
            System.out.println("jni-host-test: ok " + passed + " OBF reader checks (" + synthetic + " synthetic, "
                + (passed - synthetic) + " on real Texas files)");
        } else {
            System.out.println("jni-host-test: ok " + passed
                + " OBF reader checks (real-file checks skipped: need OBF_DIR with Us_texas_austin_northamerica.obf)");
        }
    }

    // --- the wire walker

    private static ObfWire wire(byte[] bytes) {
        return new ObfWire(new ArrayBytes(bytes), 4); // a tiny buffer, so refills are exercised
    }

    private static void varintsAndZigzag() throws IOException {
        ObfWire w = wire(new byte[] {(byte) 0x96, 0x01, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x0f,
            (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01});
        check(w.readVarint64() == 150, "150 is 96 01");
        check(w.readVarint32() == -1, "five bytes of ones are uint32 0xffffffff");
        check(w.readVarint64() == Long.MIN_VALUE, "a ten-byte varint reaches bit 63");
        check(ObfWire.Companion.zigzag32(0) == 0 && ObfWire.Companion.zigzag32(1) == -1
            && ObfWire.Companion.zigzag32(2) == 1 && ObfWire.Companion.zigzag32(3) == -2, "zigzag 0, -1, 1, -2");
        check(ObfWire.Companion.zigzag32(0xfffffffe) == Integer.MAX_VALUE
            && ObfWire.Companion.zigzag32(0xffffffff) == Integer.MIN_VALUE, "zigzag at the int limits");
        check(ObfWire.Companion.zigzag64(-1L) == Long.MIN_VALUE, "zigzag64 at the long limit");
        ObfWire s = wire(new byte[] {0x03, (byte) 0xc4, 0x13});
        check(s.readSInt32() == -2 && s.readSInt32() == 1250, "sint32 reads zigzag varints");
        ObfWire f = wire(new byte[] {0x78, 0x56, 0x34, 0x12});
        check(f.readFixed32() == 0x12345678, "protobuf fixed32 is little-endian");
    }

    private static void osmAndsBigEndianLengths() throws IOException {
        ObfWire four = wire(new byte[] {0x00, 0x01, (byte) 0xb8, (byte) 0x8c});
        check(four.readBigLength() == 0x1b88c, "4-byte big-endian length (Amarillo's first map section starts 36 01 b8 8c 07)");
        ObfWire eight = wire(new byte[] {(byte) 0x80, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x05});
        check(eight.readBigLength() == 0x100000005L, "a set top bit means 8 bytes, the bit cleared");
        check(eight.getPosition() == 8, "all eight consumed");
        // a wire-type-6 field carrying 3 bytes, then a varint field: the tag of field 6 wire 6 is 0x36
        ObfWire field = wire(bytes(0x36, 0, 0, 0, 3, 'a', 'b', 'c', 0x08, 0x2a));
        int tag = field.readTag();
        check(tag >>> 3 == 6 && (tag & 7) == ObfWire.WIRE_BIG_LENGTH, "field 6, wire type 6");
        check(field.readLength(tag) == 3, "its length by its wire type");
        field.skip(3);
        int next = field.readTag();
        check(next >>> 3 == 1 && field.readVarint32() == 42, "the field after it");
        ObfWire bad = wire(bytes(0x08, 0x01));
        try {
            bad.readLength(bad.readTag());
            check(false, "a varint field has no length");
        } catch (IOException expected) {
            check(true, "a varint field has no length");
        }
    }

    private static void unknownFieldsOfEveryTypeAreSkipped() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        tag(b, 1, 0); varint(b, 300);                                     // varint
        tag(b, 2, 1); b.write(new byte[8], 0, 8);                          // fixed64
        tag(b, 3, 2); varint(b, 2); b.write('h'); b.write('i');            // length
        tag(b, 4, 5); b.write(new byte[4], 0, 4);                          // fixed32
        tag(b, 5, 6); be4(b, 2); b.write(9); b.write(9);                   // OsmAnd's big-endian length
        tag(b, 6, 6); b.write(0x80); b.write(new byte[6], 0, 6); b.write(1); b.write(7); // 8-byte length 1
        tag(b, 9, 0); varint(b, 7);                                        // the field we want
        ObfWire w = wire(b.toByteArray());
        int found = -1;
        int skipped = 0;
        while (true) {
            int t = w.readTag();
            if (t == 0) break;
            if (t >>> 3 == 9) found = w.readVarint32(); else { w.skipField(t); skipped++; }
        }
        check(skipped == 6 && found == 7, "wire types 0, 1, 2, 5, 6 and 6 with 8 bytes are skipped");
        ObfWire group = wire(bytes(0x0b));
        try {
            group.skipField(group.readTag());
            check(false, "a group is refused");
        } catch (IOException expected) {
            check(true, "a group (wire type 3) is refused, not misread");
        }
    }

    private static void limitsAndShortReads() throws IOException {
        ObfWire w = wire(bytes(0x08, 0x01, 0x10, 0x02, 0x18, 0x03));
        long old = w.pushLimit(4);
        int n = 0;
        while (w.readTag() != 0) { w.readVarint32(); n++; }
        w.popLimit(old);
        check(n == 2 && w.readTag() == (3 << 3), "a limit ends a message; popping it goes on");
        try {
            wire(bytes(0x08)).pushLimit(5);
            check(false, "a message longer than its parent");
        } catch (IOException expected) {
            check(true, "a message longer than its parent is refused");
        }
        ObfWire cut = wire(bytes(0x0a, 0x05, 'a'));
        cut.readTag();
        try {
            cut.readString();
            check(false, "a string cut short");
        } catch (EOFException expected) {
            check(true, "a string cut short is a short read, not garbage");
        }
    }

    private static void tileArithmetic() {
        check(near(ObfTiles.INSTANCE.longitude31(0L), -180.0, 1e-12) && near(ObfTiles.INSTANCE.latitude31(1L << 30), 0.0, 1e-9),
            "the zoom-31 origin and equator");
        double lat = 30.2747, lon = -97.7404;
        check(near(ObfTiles.INSTANCE.latitude31(ObfTiles.INSTANCE.y31(lat)), lat, 1e-6)
            && near(ObfTiles.INSTANCE.longitude31(ObfTiles.INSTANCE.x31(lon)), lon, 1e-6), "zoom 31 round trip");
        long x24 = (long) ObfTiles.INSTANCE.tileX(24, lon);
        long y24 = (long) ObfTiles.INSTANCE.tileY(24, lat);
        check(ObfTiles.INSTANCE.x24Of(ObfTiles.INSTANCE.longitude(24, x24)) == x24
            && ObfTiles.INSTANCE.y24Of(ObfTiles.INSTANCE.latitude(24, y24)) == y24, "a zoom-24 tile is recovered exactly");
    }

    private static void theButtonsRules() {
        ObfPoiKinds k = ObfPoiKinds.INSTANCE;
        check(k.kindOf("transportation", "fuel") == PoiKind.GAS, "fuel is gas");
        check(k.kindOf("transportation", "charging_station") == null, "EV charging is not gas (decision 3)");
        check(k.kindOf("shop", "gas") == PoiKind.SHOP, "shop=gas (bottled gas) is a shop, not gas");
        for (String s : List.of("restaurant", "fast_food", "cafe", "food_court")) {
            check(k.kindOf("sustenance", s) == PoiKind.FOOD, s + " is food");
        }
        check(k.kindOf("sustenance", "bar") == null && k.kindOf("sustenance", "pub") == null, "bars and pubs are not food");
        check(k.kindOf("shop", "vending_machine") == PoiKind.SHOP, "every shop subcategory is a shop (decision 3)");
    }

    private static void zippedText() throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write("Mo-Fr 07:00-19:00\n".getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder s = new StringBuilder(" gz ");
        for (byte x : gz.toByteArray()) s.append((char) ((x & 0xff) + 128 + 32));
        check(ObfPoiKinds.INSTANCE.unzip(s.toString()).equals("Mo-Fr 07:00-19:00"), "a \" gz \" value is unzipped");
        check(ObfPoiKinds.INSTANCE.unzip("24/7").equals("24/7"), "a plain value is left alone");
    }

    // --- a synthetic file

    /** A place by the Capitol, and its tiles. */
    private static final double LAT = 30.27465, LON = -97.74038;

    private static void aSyntheticFile() throws IOException {
        Path file = Files.createTempFile("obftest", ".obf");
        try {
            Files.write(file, syntheticObf());
            try (ObfFile obf = ObfFile.Companion.open(file.toFile())) {
                syntheticToc(obf);
                syntheticAddress(obf);
                syntheticPois(obf);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static long x(int zoom) { return (long) ObfTiles.INSTANCE.tileX(zoom, LON); }
    private static long y(int zoom) { return (long) ObfTiles.INSTANCE.tileY(zoom, LAT); }

    private static final int SHIFT_PLACEHOLDER = 0;

    private static byte[] syntheticObf() throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        tag(f, 1, 0); varint(f, 2);
        tag(f, 18, 0); varint(f, 1790812800000L);
        tag(f, 6, 6); be4(f, 5); f.write(new byte[] {1, 2, 3, 4, 5}, 0, 5); // a map section, never read
        byte[] address = addressSection();
        tag(f, 7, 6); be4(f, address.length); f.write(address, 0, address.length);
        byte[] poi = poiSection();
        tag(f, 8, 6); be4(f, poi.length); f.write(poi, 0, poi.length);
        tag(f, 9, 6); be4(f, 3); f.write(new byte[3], 0, 3);                 // routing, never read
        tag(f, 32, 0); varint(f, 2);
        return f.toByteArray();
    }

    // street and building offsets at zoom 24
    private static final int STREET_DX = 400, STREET_DY = -250, B1_DX = -30, B1_DY = 12, B1_DX2 = 90, B1_DY2 = -40, B2_DX = 5, B2_DY = 6;

    private static byte[] addressSection() throws IOException {
        long cx31 = (long) ObfTiles.INSTANCE.tileX(31, LON), cy31 = (long) ObfTiles.INSTANCE.tileY(31, LAT);
        // the city block: one street, two buildings
        ByteArrayOutputStream b1 = new ByteArrayOutputStream();
        string(b1, 1, "101"); string(b1, 3, "199"); tag(b1, 5, 0); varint(b1, zig(-3));
        tag(b1, 7, 0); varint(b1, zig(B1_DX)); tag(b1, 8, 0); varint(b1, zig(B1_DY));
        tag(b1, 9, 0); varint(b1, zig(B1_DX2)); tag(b1, 10, 0); varint(b1, zig(B1_DY2));
        tag(b1, 13, 0); varint(b1, 77); string(b1, 14, "78701");
        ByteArrayOutputStream b2 = new ByteArrayOutputStream();
        string(b2, 1, "150"); tag(b2, 7, 0); varint(b2, zig(B2_DX)); tag(b2, 8, 0); varint(b2, zig(B2_DY));
        string(b2, 14, "78702"); tag(b2, 99, 2); varint(b2, 1); b2.write(0); // an unknown field
        ByteArrayOutputStream street = new ByteArrayOutputStream();
        string(street, 1, "Congress Avenue (Downtown)");
        tag(street, 3, 0); varint(street, zig(STREET_DX)); tag(street, 4, 0); varint(street, zig(STREET_DY));
        ByteArrayOutputStream crossing = new ByteArrayOutputStream();
        string(crossing, 2, "East 11th Street"); tag(crossing, 4, 0); varint(crossing, 0); tag(crossing, 5, 0); varint(crossing, 0);
        message(street, 5, crossing.toByteArray());
        message(street, 12, b1.toByteArray());
        message(street, 12, b2.toByteArray());
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        message(block, 12, street.toByteArray());
        byte[] blockBytes = block.toByteArray();

        // the city, its shift patched once the layout is known (a fixed 4 bytes, so lengths don't move)
        byte[] city = city(1, "Austin", cx31, cy31, SHIFT_PLACEHOLDER, true);
        ByteArrayOutputStream cities = new ByteArrayOutputStream();
        tag(cities, 2, 0); varint(cities, 1);
        tag(cities, 5, 2); int cityFp = cities.size(); varint(cities, city.length); cities.write(city, 0, city.length);
        tag(cities, 7, 2); int blockAt = cities.size(); varint(cities, blockBytes.length); cities.write(blockBytes, 0, blockBytes.length);
        byte[] citiesFinal = withShift(cities.toByteArray(), cityFp, blockAt - cityFp);

        byte[] postcode = city(-1, "78701", cx31 + 1000, cy31, SHIFT_PLACEHOLDER, false);
        ByteArrayOutputStream postcodes = new ByteArrayOutputStream();
        tag(postcodes, 2, 0); varint(postcodes, 2);
        tag(postcodes, 5, 2); int pcFp = postcodes.size(); varint(postcodes, postcode.length); postcodes.write(postcode, 0, postcode.length);

        byte[] boundary = city(5, "Travis County", cx31, cy31, -1, false);
        ByteArrayOutputStream villages = new ByteArrayOutputStream();
        tag(villages, 2, 0); varint(villages, 3);
        message(villages, 5, boundary);

        ByteArrayOutputStream tags = new ByteArrayOutputStream();
        string(tags, 1, "name:en");
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        string(head, 1, "Synthetic");
        message(head, 4, tags.toByteArray());
        // order: postcodes, then cities (so the postcode's shift points forward), then villages
        int pcIndexStart = head.size() + 5;
        int cityIndexStart = pcIndexStart + postcodes.size() + 5;
        byte[] postcodesFinal = withShift(postcodes.toByteArray(), pcFp, cityIndexStart + blockAt - (pcIndexStart + pcFp));
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        a.write(head.toByteArray(), 0, head.size());
        tag(a, 6, 6); be4(a, postcodesFinal.length); a.write(postcodesFinal, 0, postcodesFinal.length);
        tag(a, 6, 6); be4(a, citiesFinal.length); a.write(citiesFinal, 0, citiesFinal.length);
        tag(a, 6, 6); be4(a, villages.size()); a.write(villages.toByteArray(), 0, villages.size());
        tag(a, 7, 6); be4(a, 2); a.write(0xde); a.write(0xad); // a name index: never read
        return a.toByteArray();
    }

    /** A CityIndex; with [shift] >= 0 its shiftToCityBlockIndex is the last 4 bytes. */
    private static byte[] city(int cityType, String name, long x31, long y31, int shift, boolean nameEn) throws IOException {
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        if (cityType >= 0) { tag(c, 1, 0); varint(c, cityType); }
        string(c, 2, name);
        tag(c, 5, 0); varint(c, x31); tag(c, 6, 0); varint(c, y31);
        if (nameEn) { tag(c, 7, 0); varint(c, 0); string(c, 8, "Austin EN"); }
        if (shift >= 0) { tag(c, 10, 5); be4(c, shift); }
        return c.toByteArray();
    }

    /** Patches the big-endian shift in the 4 last bytes of the city message whose length varint is at [fp]. */
    private static byte[] withShift(byte[] bytes, int fp, int shift) {
        int length = 0, i = fp, s = 0;
        while (true) { int v = bytes[i++] & 0xff; length |= (v & 0x7f) << s; s += 7; if (v < 0x80) break; }
        int at = i + length - 4;
        bytes[at] = (byte) (shift >>> 24); bytes[at + 1] = (byte) (shift >>> 16); bytes[at + 2] = (byte) (shift >>> 8); bytes[at + 3] = (byte) shift;
        return bytes;
    }

    // POI ids in this file's own table: transportation = 0 (charging_station 0, fuel 1), shop = 1 (supermarket 0)
    private static final int FUEL = (1 << 7) | 0, CHARGING = 0, SUPERMARKET = 1;
    /** Bits of zoom 25 and 26 for precisionXY, from the place's own position. */
    private static int precision() {
        long x26 = x(26), y26 = y(26);
        int bx25 = (int) ((x26 >> 1) & 1), by25 = (int) ((y26 >> 1) & 1), bx26 = (int) (x26 & 1), by26 = (int) (y26 & 1);
        return (1 << 4) | (bx26 << 3) | (by26 << 2) | (bx25 << 1) | by25;
    }

    private static byte[] poiSection() throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        string(head, 1, "Synthetic");
        ByteArrayOutputStream bounds = new ByteArrayOutputStream();
        tag(bounds, 1, 0); varint(bounds, (long) ObfTiles.INSTANCE.tileX(31, LON - 0.1));
        tag(bounds, 2, 0); varint(bounds, (long) ObfTiles.INSTANCE.tileX(31, LON + 0.1));
        tag(bounds, 3, 0); varint(bounds, (long) ObfTiles.INSTANCE.tileY(31, LAT + 0.1));
        tag(bounds, 4, 0); varint(bounds, (long) ObfTiles.INSTANCE.tileY(31, LAT - 0.1));
        message(head, 2, bounds.toByteArray());
        ByteArrayOutputStream t1 = new ByteArrayOutputStream();
        string(t1, 1, "transportation"); string(t1, 3, "charging_station"); string(t1, 3, "fuel");
        message(head, 3, t1.toByteArray());
        ByteArrayOutputStream t2 = new ByteArrayOutputStream();
        string(t2, 1, "shop"); string(t2, 3, "supermarket");
        message(head, 3, t2.toByteArray());
        ByteArrayOutputStream subtypes = new ByteArrayOutputStream();
        for (String n : List.of("name", "brand", "opening_hours")) {
            ByteArrayOutputStream st = new ByteArrayOutputStream();
            string(st, 1, n); tag(st, 3, 0); varint(st, 1);
            message(subtypes, 4, st.toByteArray());
        }
        message(head, 5, subtypes.toByteArray());

        // the data box: zoom 14, three atoms
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        tag(data, 1, 0); varint(data, 14); tag(data, 2, 0); varint(data, x(14)); tag(data, 3, 0); varint(data, y(14));
        long dx = x(24) - (x(14) << 10), dy = y(24) - (y(14) << 10);
        message(data, 5, atom(dx, dy, new int[] {FUEL}, 1000L, "Synth Fuel", "SynthOil", "24/7", precision()));
        message(data, 5, atom(dx, dy, new int[] {SUPERMARKET}, 1000L, "Synth Fuel", "SynthOil", null, precision()));
        message(data, 5, atom(dx + 40, dy, new int[] {CHARGING}, 2000L, "Synth Charger", null, null, 0));
        ByteArrayOutputStream shop = new ByteArrayOutputStream(); // an older-style name in field 6
        tag(shop, 2, 0); varint(shop, zig((int) dx - 80)); tag(shop, 3, 0); varint(shop, zig((int) dy));
        tag(shop, 4, 0); varint(shop, SUPERMARKET); string(shop, 6, "Field Six Market"); tag(shop, 8, 0); varint(shop, 3000);
        message(data, 5, shop.toByteArray());
        byte[] dataBytes = data.toByteArray();

        // boxes: root zoom 12 holding child A (zoom 14, the data) and child B (another tile, charging only,
        // whose shiftToData is nonsense: following it would fail, so a gas query must prune it)
        int boxesFixedLength;
        byte[] boxes = boxes(0);
        boxesFixedLength = boxes.length;
        int dataOffset = head.size() + 1 + 4 + boxesFixedLength + 1;
        boxes = boxes(dataOffset);
        if (boxes.length != boxesFixedLength) throw new AssertionError("box layout moved");
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(head.toByteArray(), 0, head.size());
        tag(p, 6, 6); be4(p, boxes.length); p.write(boxes, 0, boxes.length);
        tag(p, 9, 6); be4(p, dataBytes.length); p.write(dataBytes, 0, dataBytes.length);
        return p.toByteArray();
    }

    private static byte[] boxes(int dataOffset) throws IOException {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        tag(a, 1, 0); varint(a, 2);
        tag(a, 2, 0); varint(a, zig((int) (x(14) - (x(12) << 2)))); tag(a, 3, 0); varint(a, zig((int) (y(14) - (y(12) << 2))));
        a.write(categories(FUEL, CHARGING, SUPERMARKET), 0, categories(FUEL, CHARGING, SUPERMARKET).length);
        tag(a, 14, 5); be4(a, dataOffset);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        long otherX = (x(12) << 2) + ((x(14) & 3) ^ 1); // the neighbouring zoom-14 tile in the same zoom-12 tile
        tag(b, 1, 0); varint(b, 2);
        tag(b, 2, 0); varint(b, zig((int) (otherX - (x(12) << 2)))); tag(b, 3, 0); varint(b, zig((int) (y(14) - (y(12) << 2))));
        b.write(categories(CHARGING), 0, categories(CHARGING).length);
        tag(b, 14, 5); be4(b, 0x7ffffff0);
        ByteArrayOutputStream root = new ByteArrayOutputStream();
        tag(root, 1, 0); varint(root, 12);
        tag(root, 2, 0); varint(root, zig((int) x(12))); tag(root, 3, 0); varint(root, zig((int) y(12)));
        root.write(categories(FUEL, CHARGING, SUPERMARKET), 0, categories(FUEL, CHARGING, SUPERMARKET).length);
        tag(root, 10, 6); be4(root, a.size()); root.write(a.toByteArray(), 0, a.size());
        tag(root, 10, 6); be4(root, b.size()); root.write(b.toByteArray(), 0, b.size());
        return root.toByteArray();
    }

    private static byte[] categories(int... ids) throws IOException {
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        for (int id : ids) { tag(c, 3, 0); varint(c, id); }
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        message(f, 4, c.toByteArray());
        return f.toByteArray();
    }

    private static byte[] atom(long dx, long dy, int[] cats, long id, String name, String brand, String hours, int precision) throws IOException {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        tag(a, 2, 0); varint(a, zig((int) dx)); tag(a, 3, 0); varint(a, zig((int) dy));
        for (int c : cats) { tag(a, 4, 0); varint(a, c); }
        tag(a, 8, 0); varint(a, id);
        List<String> values = new ArrayList<>();
        tag(a, 14, 0); varint(a, 0 << 1); values.add(name);                // subtype 0: name
        if (brand != null) { tag(a, 14, 0); varint(a, 1 << 1); values.add(brand); }
        if (hours != null) { tag(a, 14, 0); varint(a, 2 << 1); values.add(hours); }
        for (String v : values) string(a, 15, v);
        if (precision != 0) { tag(a, 16, 0); varint(a, precision); }
        tag(a, 17, 2); varint(a, 1); a.write(25);                         // a tag group, ignored
        return a.toByteArray();
    }

    private static void syntheticToc(ObfFile obf) {
        check(obf.getToc().getVersion() == 2 && obf.getToc().getDateCreated() == 1790812800000L && obf.getToc().getComplete(),
            "version, date and the closing version");
        check(obf.getToc().getSkippedSections() == 2, "the map and routing sections are passed over");
        List<ObfSection> s = obf.getToc().getSections();
        check(s.size() == 2 && s.get(0).getType() == ObfSectionType.ADDRESS && s.get(1).getType() == ObfSectionType.POI
            && s.get(0).getName().equals("Synthetic"), "one address and one POI section, named");
        GeoBox b = obf.getBounds();
        check(b != null && near(b.getSouth(), LAT - 0.1, 1e-5) && near(b.getEast(), LON + 0.1, 1e-5), "the POI bounds are the file's");
    }

    private static void syntheticAddress(ObfFile obf) throws IOException {
        List<ObfCity> cities = obf.cities();
        check(cities.size() == 2 && obf.getSkippedCities() == 1, "a city and a postcode; the boundary is skipped");
        ObfCity austin = cities.stream().filter(c -> c.getType() == CityType.CITY).findFirst().orElseThrow();
        ObfCity zip = cities.stream().filter(c -> c.getType() == CityType.POSTCODE).findFirst().orElseThrow();
        check(austin.getName().equals("Austin") && "Austin EN".equals(austin.getNameEn()), "name and name:en from the attribute table");
        check(metres(austin.getLatitude(), austin.getLongitude(), LAT, LON) < 0.05, "the city's zoom-31 position");
        check(obf.cities() == cities, "cities are read once");
        List<ObfStreet> streets = obf.streets(austin);
        check(streets.size() == 1 && streets.get(0).getName().equals("Congress Avenue (Downtown)"), "the street");
        long sx = (x(31) >> 7) + STREET_DX, sy = (y(31) >> 7) + STREET_DY;
        ObfStreet street = streets.get(0);
        check(near(street.getLatitude(), ObfTiles.INSTANCE.latitude(24, sy), 1e-12)
            && near(street.getLongitude(), ObfTiles.INSTANCE.longitude(24, sx), 1e-12), "the street at the city's tile >> 7 plus its delta");
        check(obf.streets(austin) == streets, "streets are kept per city");
        List<ObfBuilding> buildings = obf.buildings(street);
        check(buildings.size() == 2, "two buildings, the intersection skipped");
        ObfBuilding range = buildings.get(0);
        check(range.getNumber().equals("101") && "199".equals(range.getNumber2()) && range.getInterpolation() == Interpolation.ODD
            && range.getStep() == 0 && "78701".equals(range.getPostcode()), "an odd range 101-199 with its postcode");
        check(near(range.getLatitude(), ObfTiles.INSTANCE.latitude(24, sy + B1_DY), 1e-12)
            && near(range.getLongitude(), ObfTiles.INSTANCE.longitude(24, sx + B1_DX), 1e-12)
            && near(range.getLatitude2(), ObfTiles.INSTANCE.latitude(24, sy + B1_DY2), 1e-12)
            && near(range.getLongitude2(), ObfTiles.INSTANCE.longitude(24, sx + B1_DX2), 1e-12), "both ends from the street's tile");
        ObfBuilding single = buildings.get(1);
        check(single.getInterpolation() == Interpolation.NONE && single.getNumber2() == null && single.getLatitude2() == null,
            "a plain number has no second end");
        List<ObfStreet> zipStreets = obf.streets(zip);
        check(zipStreets.size() == 1 && zipStreets.get(0).getCity() == zip, "the postcode's block, through a forward shift");
        List<ObfBuilding> zipBuildings = obf.buildings(zipStreets.get(0));
        check(zipBuildings.size() == 1 && zipBuildings.get(0).getNumber().equals("101"),
            "under a postcode only its own buildings");
    }

    private static void syntheticPois(ObfFile obf) throws IOException {
        GeoBox around = new GeoBox(LAT - 0.01, LON - 0.01, LAT + 0.01, LON + 0.01);
        List<ObfPoi> gas = obf.pois(around, EnumSet.of(PoiKind.GAS), 25, LAT, LON, null);
        check(gas.size() == 1, "one gas station; the charger is left out and the charging-only box pruned unread");
        ObfPoi fuel = gas.get(0);
        check(fuel.getName().equals("Synth Fuel") && "SynthOil".equals(fuel.getBrand()) && "24/7".equals(fuel.getOpeningHours())
            && fuel.getCategory().equals("transportation") && fuel.getSubcategory().equals("fuel") && fuel.getId() == 1000L,
            "name, brand and hours from the text tags; the file's own keys");
        check(metres(fuel.getLatitude(), fuel.getLongitude(), LAT, LON) < 0.8, "precisionXY puts it within a zoom-26 tile");
        double plain = metres(ObfTiles.INSTANCE.latitude(24, y(24)), ObfTiles.INSTANCE.longitude(24, x(24)), LAT, LON);
        check(plain >= metres(fuel.getLatitude(), fuel.getLongitude(), LAT, LON), "and closer than zoom 24 alone");
        List<ObfPoi> both = obf.pois(around, EnumSet.of(PoiKind.GAS, PoiKind.SHOP), 25, LAT, LON, null);
        check(both.size() == 2 && both.stream().filter(p -> p.getId() == 1000L).count() == 1
            && both.stream().anyMatch(p -> p.getId() == 1000L && p.getKind() == PoiKind.GAS),
            "a place under two categories is one result, as gas");
        ObfPoi market = both.stream().filter(p -> p.getId() == 3000L).findFirst().orElseThrow();
        check(market.getName().equals("Field Six Market") && market.getKind() == PoiKind.SHOP, "a name in field 6 still reads");
        check(Math.abs(market.getLongitude() - LON) > 1e-4, "without precisionXY a zoom-24 position");
        check(obf.pois(around, EnumSet.of(PoiKind.SHOP), 1, LAT, LON, null).size() == 1, "the limit per kind");
        check(obf.pois(around, EnumSet.of(PoiKind.SHOP), 25, LAT, LON, null).get(0).getId() == 1000L, "nearest first");
        check(obf.pois(around, EnumSet.of(PoiKind.SHOP), 25, LAT, LON, "SYNTHOIL").size() == 1, "a name query matches the brand, any case");
        check(obf.pois(around, EnumSet.of(PoiKind.GAS), 25, LAT, LON, "market").isEmpty(), "and filters within the kinds");
        check(obf.pois(around, EnumSet.of(PoiKind.FOOD), 25, LAT, LON, null).isEmpty(), "no food here");
        check(obf.pois(new GeoBox(LAT + 0.02, LON, LAT + 0.03, LON + 0.01), EnumSet.of(PoiKind.GAS), 25, LAT, LON, null).isEmpty(),
            "a box elsewhere finds nothing");
        check(obf.pois(around, Collections.emptySet(), 25, LAT, LON, null).isEmpty(), "no kinds and no name is no query");
    }

    // --- the real files (Nominatim's positions, 3 October 2026)

    private static void realAustin(File file) throws Exception {
        long t0 = System.nanoTime();
        ObfFile obf = ObfFile.Companion.open(file);
        long tOpen = System.nanoTime() - t0;
        try {
            check(obf.getToc().getVersion() == 2 && obf.getToc().getComplete(), "Austin: version 2, complete");
            check(obf.getToc().getSections().size() == 2 && obf.getToc().getSkippedSections() == 5,
                "Austin: address and POI read, map/routing/transport/2 HH passed over");
            t0 = System.nanoTime();
            List<ObfCity> cities = obf.cities();
            long tCities = System.nanoTime() - t0;
            Map<CityType, Integer> byType = new EnumMap<>(CityType.class);
            for (ObfCity c : cities) byType.merge(c.getType(), 1, Integer::sum);
            check(byType.getOrDefault(CityType.CITY, 0) > 20 && byType.getOrDefault(CityType.VILLAGE, 0) > 300
                && byType.getOrDefault(CityType.POSTCODE, 0) > 300, "Austin: cities, villages, postcodes " + byType);
            ObfCity austin = city(cities, "Austin", CityType.CITY);
            near("Austin", austin.getLatitude(), austin.getLongitude(), 30.2711286, -97.7436995, 5);
            near("Round Rock", city(cities, "Round Rock", CityType.CITY), 30.5085915, -97.6788056, 5);
            near("Pflugerville", city(cities, "Pflugerville", CityType.CITY), 30.4393696, -97.6200043, 5);
            near("Georgetown", city(cities, "Georgetown", CityType.CITY), 30.6370152, -97.6775634, 5);

            t0 = System.nanoTime();
            List<ObfStreet> streets = obf.streets(austin);
            long tStreets = System.nanoTime() - t0;
            check(streets.size() > 10000, "Austin: " + streets.size() + " streets");
            ObfStreet congress = streets.stream().filter(s -> s.getName().equals("Congress Avenue (Downtown)")).findFirst().orElseThrow();
            check(metres(congress.getLatitude(), congress.getLongitude(), 30.2700, -97.7431) < 1200,
                "Austin: Congress Avenue (Downtown) lies downtown");
            List<ObfBuilding> onCongress = obf.buildings(congress);
            ObfBuilding capitol = number(onCongress, "1100");
            near("1100 Congress Avenue", capitol.getLatitude(), capitol.getLongitude(), 30.2764850, -97.7397680, 15);
            ObfBuilding bullock = number(allNamed(obf, streets, "Congress Avenue"), "1800");
            // OSM has two objects numbered 1800: the Bob Bullock IMAX, which this matches, and an address node 35 m off
            near("1800 Congress Avenue (Bob Bullock IMAX)", bullock.getLatitude(), bullock.getLongitude(), 30.2805847, -97.7389369, 15);

            // TIGER-style ranges: 3050 Duval Street lies in the even 3000-3098 range
            ObfStreet duval = streets.stream().filter(s -> s.getName().equals("Duval Street")).findFirst().orElseThrow();
            ObfBuilding r = obf.buildings(duval).stream().filter(b -> b.getNumber().equals("3000") && "3098".equals(b.getNumber2())
                && b.getInterpolation() == Interpolation.EVEN).findFirst().orElseThrow();
            double f = (3050 - 3000) / 98.0;
            near("3050 Duval Street (interpolated)", r.getLatitude() + f * (r.getLatitude2() - r.getLatitude()),
                r.getLongitude() + f * (r.getLongitude2() - r.getLongitude()), 30.2923327, -97.7339343, 30);
            Map<Interpolation, Integer> kinds = new EnumMap<>(Interpolation.class);
            for (ObfStreet s : streets) for (ObfBuilding b : obf.buildings(s)) kinds.merge(b.getInterpolation(), 1, Integer::sum);
            System.out.println("jni-host-test: OBF Austin's buildings by interpolation: " + kinds);
            check(kinds.getOrDefault(Interpolation.EVEN, 0) > 1000 && kinds.getOrDefault(Interpolation.ODD, 0) > 1000,
                "Austin has interpolation ranges " + kinds);

            // ZIP 78701: its own position is wherever its first address is; its streets are downtown
            ObfCity zip = city(cities, "78701", CityType.POSTCODE);
            List<ObfStreet> zipStreets = obf.streets(zip);
            double[] lats = zipStreets.stream().mapToDouble(ObfStreet::getLatitude).sorted().toArray();
            double[] lons = zipStreets.stream().mapToDouble(ObfStreet::getLongitude).sorted().toArray();
            double zipLat = lats[lats.length / 2], zipLon = lons[lons.length / 2];
            System.out.printf("jni-host-test: OBF 78701: %d streets, median %.5f,%.5f; the postcode's own point %.5f,%.5f%n",
                zipStreets.size(), zipLat, zipLon, zip.getLatitude(), zip.getLongitude());
            check(zipStreets.size() > 50 && metres(zipLat, zipLon, 30.2700, -97.7431) < 1500,
                "78701: " + zipStreets.size() + " streets, their median " + Math.round(metres(zipLat, zipLon, 30.2700, -97.7431))
                + " m from downtown (the postcode's own point is " + Math.round(metres(zip.getLatitude(), zip.getLongitude(), 30.2700, -97.7431)) + " m)");

            // POIs downtown
            GeoBox downtown = new GeoBox(30.262, -97.752, 30.282, -97.735);
            t0 = System.nanoTime();
            List<ObfPoi> pois = obf.pois(downtown, EnumSet.allOf(PoiKind.class), 100000, 30.272, -97.7435, null);
            long tPoisCold = System.nanoTime() - t0;
            t0 = System.nanoTime();
            List<ObfPoi> again = obf.pois(downtown, EnumSet.allOf(PoiKind.class), 25, 30.272, -97.7435, null);
            long tPoisWarm = System.nanoTime() - t0;
            Map<PoiKind, Integer> n = new EnumMap<>(PoiKind.class);
            for (ObfPoi p : pois) n.merge(p.getKind(), 1, Integer::sum);
            System.out.println("jni-host-test: OBF downtown Austin POIs: " + n);
            // OpenStreetMap itself (Overpass, 3 October 2026): food 181, shop 94, fuel 6 in this box
            check(between(n.get(PoiKind.FOOD), 140, 260) && between(n.get(PoiKind.SHOP), 60, 140) && between(n.get(PoiKind.GAS), 3, 10),
                "downtown Austin: " + n + " (OSM today: food 181, shop 94, fuel 6)");
            check(again.size() <= 75 && again.stream().allMatch(p -> downtown.contains(p.getLatitude(), p.getLongitude())),
                "25 per kind, all inside the box");
            check(pois.stream().allMatch(p -> !p.getSubcategory().equals("charging_station")), "no EV charger is gas");
            poi(obf, "Whataburger, Barton Springs", "whataburger", 262312068L, true, 30.2580592, -97.7506844, PoiKind.FOOD);
            poi(obf, "UT Tower", "ut tower", 516187631L, true, 30.2862732, -97.7393655, null);
            poi(obf, "H-E-B, South Congress", "h-e-b", 1235247866L, true, 30.2387456, -97.7546883, PoiKind.SHOP);
            poi(obf, "Buc-ee's fuel, Temple", "buc-ee", 10879244160L, false, 31.1363799, -97.3293382, PoiKind.GAS);

            // the same queries from 8 threads at once give the same answers
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<String>> results = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    final int k = i;
                    results.add(pool.submit(() -> {
                        if (k % 2 == 0) return ids(obf.pois(downtown, EnumSet.allOf(PoiKind.class), 25, 30.272, -97.7435, null));
                        ObfCity c = cities.get((k * 37) % cities.size());
                        StringBuilder s = new StringBuilder();
                        for (ObfStreet st : obf.streets(c)) s.append(st.getName()).append(obf.buildings(st).size());
                        return c.getName() + s.length();
                    }));
                }
                String expect = ids(again);
                boolean same = true;
                for (int i = 0; i < results.size(); i += 2) same &= results.get(i).get().equals(expect);
                for (int i = 1; i < results.size(); i += 2) {
                    ObfCity c = cities.get((i * 37) % cities.size());
                    StringBuilder s = new StringBuilder();
                    for (ObfStreet st : obf.streets(c)) s.append(st.getName()).append(obf.buildings(st).size());
                    same &= results.get(i).get().equals(c.getName() + s.length());
                }
                check(same, "32 queries on 8 threads agree with one thread");
            } finally {
                pool.shutdown();
            }
            System.out.printf("jni-host-test: OBF Austin timings: open+TOC %.1f ms, cities() %.1f ms (%d), streets(Austin) %.1f ms (%d),"
                + " downtown POIs %.1f ms cold, %.1f ms warm%n", tOpen / 1e6, tCities / 1e6, cities.size(), tStreets / 1e6,
                streets.size(), tPoisCold / 1e6, tPoisWarm / 1e6);
        } finally {
            obf.close();
        }
    }

    private static void realAmarillo(File file) throws IOException {
        try (ObfFile obf = ObfFile.Companion.open(file)) {
            check(obf.getToc().getComplete() && obf.getToc().getSections().size() == 2, "Amarillo: the same layout as Austin");
            near("Amarillo", city(obf.cities(), "Amarillo", CityType.CITY), 35.2072900, -101.8371192, 5);
            List<ObfPoi> gas = obf.pois(new GeoBox(35.10, -101.95, 35.30, -101.75), EnumSet.of(PoiKind.GAS), 25, 35.207, -101.837, null);
            check(gas.size() == 25, "Amarillo: 25 gas stations nearest the centre");
        }
    }

    private static void realLubbockOlderLayout(File file) throws IOException {
        try (ObfFile obf = ObfFile.Companion.open(file)) {
            check(obf.getToc().getComplete() && obf.getToc().getDateCreated() < 1783000000000L, "Lubbock: the 30 June 2026 file");
            ObfCity lubbock = city(obf.cities(), "Lubbock", CityType.CITY);
            near("Lubbock", lubbock.getLatitude(), lubbock.getLongitude(), 33.5855677, -101.8470215, 5);
            ObfStreet q = obf.streets(lubbock).stream().filter(s -> s.getName().equals("Avenue Q")).findFirst().orElseThrow();
            check(obf.buildings(q).stream().anyMatch(b -> b.getInterpolation() != Interpolation.NONE), "Lubbock: ranges on Avenue Q");
            poi(obf, "Whataburger, Lubbock", "whataburger", 264959568L, true, 33.5875354, -101.8693635, PoiKind.FOOD);
        }
    }

    // --- helpers

    private static ObfCity city(List<ObfCity> cities, String name, CityType type) {
        return cities.stream().filter(c -> c.getName().equals(name) && c.getType() == type).findFirst()
            .orElseThrow(() -> new AssertionError("obf: no " + type + " " + name));
    }

    private static List<ObfBuilding> allNamed(ObfFile obf, List<ObfStreet> streets, String prefix) throws IOException {
        List<ObfBuilding> out = new ArrayList<>();
        for (ObfStreet s : streets) if (s.getName().startsWith(prefix)) out.addAll(obf.buildings(s));
        return out;
    }

    private static ObfBuilding number(List<ObfBuilding> buildings, String number) {
        return buildings.stream().filter(b -> b.getNumber().equals(number)).findFirst()
            .orElseThrow(() -> new AssertionError("obf: no number " + number));
    }

    /** A POI found by name near its OSM position, matched by OsmAnd's id (OSM id << 1, +1 for a way). */
    private static void poi(ObfFile obf, String what, String query, long osmId, boolean way, double lat, double lon, PoiKind kind)
            throws IOException {
        long id = (osmId << 1) | (way ? 1 : 0);
        GeoBox box = new GeoBox(lat - 0.01, lon - 0.01, lat + 0.01, lon + 0.01);
        ObfPoi p = obf.pois(box, Collections.emptySet(), 100, lat, lon, query).stream().filter(x -> x.getId() == id).findFirst()
            .orElseThrow(() -> new AssertionError("obf: " + what + " not found"));
        check(p.getKind() == kind, what + ": kind " + p.getKind());
        near(what, p.getLatitude(), p.getLongitude(), lat, lon, way ? 25 : 5);
    }

    private static String ids(List<ObfPoi> pois) {
        StringBuilder s = new StringBuilder();
        for (ObfPoi p : pois) s.append(p.getId()).append(',');
        return s.toString();
    }

    private static void near(String what, ObfCity c, double lat, double lon, double limit) {
        near(what, c.getLatitude(), c.getLongitude(), lat, lon, limit);
    }

    private static void near(String what, double lat, double lon, double trueLat, double trueLon, double limitMetres) {
        double m = metres(lat, lon, trueLat, trueLon);
        System.out.printf("jni-host-test: OBF %s: %.6f,%.6f vs OSM %.6f,%.6f = %.1f m%n", what, lat, lon, trueLat, trueLon, m);
        check(m <= limitMetres, what + " within " + limitMetres + " m (" + Math.round(m) + " m)");
    }

    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static boolean between(Integer n, int lo, int hi) { return n != null && n >= lo && n <= hi; }

    private static boolean near(double a, double b, double eps) { return Math.abs(a - b) <= eps; }

    private static boolean near(Double a, double b, double eps) { return a != null && Math.abs(a - b) <= eps; }

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

    private static byte[] bytes(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("obf: " + what);
        passed++;
    }
}
