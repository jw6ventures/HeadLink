// TomTom live traffic beside HERE on a desktop JVM (docs/navigation.md, "Live traffic"): the
// provider settings and their repair, the z12 tiles that cover a box, the vector flow tile read
// by hand (layer, linestrings, every numeric value kind, packed or not, the clip to the tile's
// own square and to the box asked for that drops TomTom's margin, malformed geometry), TomTom's
// incidents and error shapes, the fetch as exactly its tiles then its incident GETs with the
// key only in a header and each request counted in its own meter before it is sent, both
// monthly caps, the incident box split under TomTom's area
// limit, HERE and TomTom combined or failing over, and the map's credit. The tiles are built
// here by a small Mapbox Vector Tile encoder and the JSON is hand-written in the shape of
// TomTom's documentation, never captured from TomTom. TomTomTiles, TomTomJson, TomTomClient and
// TrafficSources are pure Kotlin; HttpURLConnection against TomTom needs a phone.
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import kotlin.Unit;
import com.jw6ventures.headlink.FlowSpan;
import com.jw6ventures.headlink.FlowStretch;
import com.jw6ventures.headlink.MemoryTrafficLedger;
import com.jw6ventures.headlink.RouteFollower;
import com.jw6ventures.headlink.TileId;
import com.jw6ventures.headlink.TomTomClient;
import com.jw6ventures.headlink.TomTomJson;
import com.jw6ventures.headlink.TomTomTiles;
import com.jw6ventures.headlink.TrafficBox;
import com.jw6ventures.headlink.TrafficCheck;
import com.jw6ventures.headlink.TrafficClient;
import com.jw6ventures.headlink.TrafficDisplay;
import com.jw6ventures.headlink.TrafficFailure;
import com.jw6ventures.headlink.TrafficFetch;
import com.jw6ventures.headlink.TrafficHttp;
import com.jw6ventures.headlink.TrafficIncident;
import com.jw6ventures.headlink.TrafficParseException;
import com.jw6ventures.headlink.TrafficProvider;
import com.jw6ventures.headlink.TrafficProviderClient;
import com.jw6ventures.headlink.TrafficResponse;
import com.jw6ventures.headlink.TrafficSelection;
import com.jw6ventures.headlink.TrafficSettings;
import com.jw6ventures.headlink.TrafficSkip;
import com.jw6ventures.headlink.TrafficSnapshot;
import com.jw6ventures.headlink.TrafficSources;
import com.jw6ventures.headlink.TrafficStatus;
import com.jw6ventures.headlink.TrafficTandem;
import com.jw6ventures.headlink.Traversability;

public final class TomTomTrafficTest {
    private static int passed;
    private static final String HERE_KEY = "Test-Key_0123456789abcdefghijklmnopqrstuv";
    private static final String KEY = "TomTom-Key_9876543210zyxwvutsrqponmlkji";
    /** TrafficClientTest's box: z12 tiles x 946..947, y 1652..1653. */
    private static final TrafficBox DALLAS = new TrafficBox(32.75, -96.85, 32.80, -96.75);
    /** 0.999 degrees square at 30 N: about 10,690 km², over TomTom's incident area limit. */
    private static final TrafficBox BIG = new TrafficBox(30.0, -98.0, 30.999, -97.001);
    /** 28 September 2026, 15:00 UTC. */
    private static final long SEPTEMBER = Instant.parse("2026-09-28T15:00:00Z").toEpochMilli();
    private static final double EPS = 1e-9;
    private static final TileId TILE = new TileId(12, 946, 1652);
    /** TomTom's flow layer name, spelled here so a typo in TomTomTiles.LAYER fails the test. */
    private static final String FLOW_LAYER = "Traffic flow";

    /**
     * Incidents in TomTom's Orbis shape: a closure as a LineString (a repeated point and one off
     * the globe dropped, a blank first description), a Point jam, a null entry, one with no
     * geometry, and a legacy numeric iconCategory.
     */
    private static final String INCIDENTS = "{\"incidents\":["
        + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":"
        + "[[-96.80,32.78],[-96.801,32.781],[-96.801,32.781],[-200.0,32.781],[-96.802,32.782]]},"
        + "\"properties\":{\"id\":\"tt-1\",\"iconCategory\":\"roadClosed\",\"magnitudeOfDelay\":\"major\",\"somethingNew\":{\"a\":[1,null]},"
        + "\"events\":[{\"description\":\"  \",\"code\":401,\"iconCategory\":\"roadClosed\"},"
        + "{\"description\":\"Closed\",\"code\":401,\"iconCategory\":\"roadClosed\"}]}},"
        + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-96.79,32.77]},"
        + "\"properties\":{\"id\":\"tt-2\",\"iconCategory\":\"jam\",\"magnitudeOfDelay\":\"moderate\","
        + "\"events\":[{\"description\":\"Stationary traffic\",\"code\":101,\"iconCategory\":\"jam\"}]}},"
        + "null,"
        + "{\"type\":\"Feature\",\"properties\":{\"id\":\"tt-3\",\"iconCategory\":\"accident\"}},"
        + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-96.78,32.76]},"
        + "\"properties\":{\"id\":\"tt-4\",\"iconCategory\":8}}"
        + "]}";

    private static final String FORBIDDEN = "{\"detailedError\":{\"code\":\"FORBIDDEN\",\"message\":\"Invalid API key\"}}";

    public static void main(String[] args) throws IOException {
        settingsForBothProviders();
        tilesCoverTheBox();
        aFetchIsItsTilesThenItsIncidents();
        flowTilesAreRead();
        incidentsAreRead();
        errorsAreReadWithoutTheKey();
        bothCapsStopBeforeSending();
        largeBoxesAreSplitForIncidents();
        sourcesCombineAndFailOver();
        theCreditNamesWhoseDataIsShown();
        System.out.println("jni-host-test: ok " + passed + " TomTom traffic, combined sources and credit checks");
    }

    // ---- a Mapbox Vector Tile encoder ----------------------------------------------------

    /** Protobuf's wire format, as much of it as a vector tile needs, plus unknown fields. */
    private static final class Pb {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Pb varint(long v) {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
            return this;
        }

        Pb key(int field, int wire) {
            return varint(((long) field << 3) | wire);
        }

        Pb uint(int field, long v) {
            return key(field, 0).varint(v);
        }

        Pb bytes(int field, byte[] b) {
            key(field, 2).varint(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        Pb string(int field, String s) {
            return bytes(field, s.getBytes(StandardCharsets.UTF_8));
        }

        Pb fixed64(int field, long bits) {
            key(field, 1);
            for (int i = 0; i < 8; i++) out.write((int) (bits >>> (8 * i)) & 0xff);
            return this;
        }

        Pb fixed32(int field, int bits) {
            key(field, 5);
            for (int i = 0; i < 4; i++) out.write((bits >>> (8 * i)) & 0xff);
            return this;
        }

        Pb packed(int field, List<Integer> values) {
            Pb p = new Pb();
            for (int v : values) p.varint(v & 0xffffffffL);
            return bytes(field, p.bytes());
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    /** A uint64 Value (field 5), which no Java number type selects. */
    private static final class Uint {
        final long value;

        Uint(long value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return Long.toString(value);
        }
    }

    private static int zigzag(int n) {
        return (n << 1) ^ (n >> 31);
    }

    /** One layer: keys and values deduplicated, features in order, extent 4096, version 2. */
    private static final class Layer {
        final String name;
        final List<String> keys = new ArrayList<>();
        final List<String> valueIds = new ArrayList<>();
        final List<byte[]> values = new ArrayList<>();
        final List<byte[]> features = new ArrayList<>();

        Layer(String name) {
            this.name = name;
        }

        int keyIndex(String k) {
            int i = keys.indexOf(k);
            if (i >= 0) return i;
            keys.add(k);
            return keys.size() - 1;
        }

        /** String=1, float=2, double=3, int (Long)=4, uint (Uint)=5, sint (Integer)=6, bool=7. */
        int valueIndex(Object v) {
            String id = v.getClass().getSimpleName() + ":" + v;
            int i = valueIds.indexOf(id);
            if (i >= 0) return i;
            Pb p = new Pb();
            if (v instanceof String) p.string(1, (String) v);
            else if (v instanceof Float) p.fixed32(2, Float.floatToIntBits((Float) v));
            else if (v instanceof Double) p.fixed64(3, Double.doubleToLongBits((Double) v));
            else if (v instanceof Long) p.uint(4, (Long) v);
            else if (v instanceof Uint) p.uint(5, ((Uint) v).value);
            else if (v instanceof Integer) p.uint(6, zigzag((Integer) v) & 0xffffffffL);
            else if (v instanceof Boolean) p.uint(7, (Boolean) v ? 1 : 0);
            else throw new AssertionError("no value kind for " + v);
            valueIds.add(id);
            values.add(p.bytes());
            return values.size() - 1;
        }

        /** A feature of [type] (1 point, 2 linestring) through [points], tags as key, value pairs. */
        Layer feature(int type, int[][] points, Object... tags) {
            return add(type, geometry(points), false, tags);
        }

        /**
         * The same feature with its repeated fields as a decoder may meet them: tags one pair
         * packed then the rest one by one, geometry in two packed records then one by one.
         */
        Layer featureSplit(int type, int[][] points, Object... tags) {
            return add(type, geometry(points), true, tags);
        }

        /** A feature with [g] as its geometry integers, as given. */
        Layer featureRaw(int type, List<Integer> g, Object... tags) {
            return add(type, g, false, tags);
        }

        private Layer add(int type, List<Integer> g, boolean split, Object... tags) {
            List<Integer> t = new ArrayList<>();
            for (int i = 0; i < tags.length; i += 2) {
                t.add(keyIndex((String) tags[i]));
                t.add(valueIndex(tags[i + 1]));
            }
            // An id (1) and a field no tile has (9): both skipped.
            Pb f = new Pb().uint(1, features.size() + 1);
            if (!split) {
                f.packed(2, t).uint(3, type).packed(4, g);
            } else {
                f.packed(2, t.subList(0, 2));
                for (int v : t.subList(2, t.size())) f.uint(2, v & 0xffffffffL);
                f.uint(3, type).packed(4, g.subList(0, 3)).packed(4, g.subList(3, 5));
                for (int v : g.subList(5, g.size())) f.uint(4, v & 0xffffffffL);
            }
            features.add(f.uint(9, 7).bytes());
            return this;
        }

        private static List<Integer> geometry(int[][] points) {
            List<Integer> g = new ArrayList<>();
            int cx = 0;
            int cy = 0;
            for (int i = 0; i < points.length; i++) {
                if (i == 0) g.add(1 | (1 << 3));
                if (i == 1) g.add(2 | ((points.length - 1) << 3));
                g.add(zigzag(points[i][0] - cx));
                g.add(zigzag(points[i][1] - cy));
                cx = points[i][0];
                cy = points[i][1];
            }
            return g;
        }

        byte[] bytes() {
            Pb l = new Pb().uint(15, 2).string(1, name);
            for (byte[] f : features) l.bytes(2, f);
            for (String k : keys) l.string(3, k);
            for (byte[] v : values) l.bytes(4, v);
            return l.uint(5, 4096).fixed32(20, 12345).bytes();
        }
    }

    private static byte[] tile(Layer... layers) {
        Pb t = new Pb();
        for (Layer l : layers) t.bytes(3, l.bytes());
        return t.fixed64(9, 1L).bytes();
    }

    private static int[][] pts(int... xy) {
        int[][] p = new int[xy.length / 2][];
        for (int i = 0; i < p.length; i++) p[i] = new int[] {xy[2 * i], xy[2 * i + 1]};
        return p;
    }

    /** A tile of one queue on a motorway: what the fake server answers for every tile. */
    private static byte[] queueTile() {
        return tile(new Layer(FLOW_LAYER).feature(2, pts(100, 100, 1000, 100),
            "road_category", "motorway", "relative_speed", 0.14, "absolute_speed", 12.6));
    }

    private static double lon(TileId t, double px) {
        return 360.0 * ((t.getX() + px / 4096.0) / (1 << t.getZoom())) - 180.0;
    }

    private static double lat(TileId t, double py) {
        double y = (t.getY() + py / 4096.0) / (1 << t.getZoom());
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y))));
    }

    /** A longitude in [t]'s tile units. */
    private static double px(TileId t, double lon) {
        return ((lon + 180) / 360 * (1 << t.getZoom()) - t.getX()) * 4096;
    }

    /** A latitude in [t]'s tile units. */
    private static double py(TileId t, double lat) {
        double p = Math.toRadians(lat);
        double y = (1 - Math.log(Math.tan(p) + 1 / Math.cos(p)) / Math.PI) / 2;
        return (y * (1 << t.getZoom()) - t.getY()) * 4096;
    }

    /**
     * [t] with two queues across it: one inside [box], one in the part of the tile [box]
     * leaves out (above or below it), which a fetch for [box] must drop.
     */
    private static byte[] boxedTile(TileId t, TrafficBox box) {
        double x0 = Math.max(0, px(t, box.getWest()));
        double x1 = Math.min(4096, px(t, box.getEast()));
        double y0 = Math.max(0, py(t, box.getNorth()));
        double y1 = Math.min(4096, py(t, box.getSouth()));
        int in = (int) Math.round((y0 + y1) / 2);
        int out = y0 > 40 ? (int) (y0 / 2) : (int) ((y1 + 4096) / 2);
        return tile(new Layer(FLOW_LAYER)
            .feature(2, pts((int) Math.ceil(x0) + 10, in, (int) Math.floor(x1) - 10, in),
                "road_category", "motorway", "relative_speed", 0.14, "absolute_speed", 12.6)
            .feature(2, pts(100, out, 4000, out), "road_category", "motorway", "relative_speed", 0.14, "absolute_speed", 12.6));
    }

    /** The plan's tile of a point, computed independently of TomTomTiles. */
    private static int tileX(double lon) {
        return (int) Math.floor((lon + 180) / 360 * 4096);
    }

    private static int tileY(double lat) {
        double p = Math.toRadians(lat);
        return (int) Math.floor((1 - Math.log(Math.tan(p) + 1 / Math.cos(p)) / Math.PI) / 2 * 4096);
    }

    // ---- fakes ------------------------------------------------------------------------

    /** Answers by path: tiles, then incidents, then the error when one is set. */
    private static final class FakeHttp implements TrafficHttp {
        final List<String> urls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        /** Per request: the tile meter's count and the incident meter's when it was sent. */
        final List<int[]> countedAtSend = new ArrayList<>();
        MemoryTrafficLedger tiles;
        MemoryTrafficLedger incidents;
        byte[] tile = queueTile();
        /** When set, each tile is [boxedTile] for this box instead of [tile]. */
        TrafficBox tileBox;
        String incidentBody = INCIDENTS;
        int status = 200;
        String error;
        IOException failWith;
        /** Run after the first tile is answered: the settings changing under a fetch. */
        Runnable afterTile;

        @Override
        public TrafficResponse get(String url, Map<String, String> h) throws IOException {
            urls.add(url);
            headers.add(h);
            countedAtSend.add(new int[] {tiles == null ? -1 : tiles.status().getRequests(),
                incidents == null ? -1 : incidents.status().getRequests()});
            if (failWith != null) throw failWith;
            if (status != 200) {
                return new TrafficResponse(status, null, new ByteArrayInputStream(error.getBytes(StandardCharsets.UTF_8)), () -> Unit.INSTANCE);
            }
            if (url.contains(TomTomClient.TILE_PATH + "/")) {
                if (afterTile != null) {
                    afterTile.run();
                    afterTile = null;
                }
                byte[] body = tile;
                if (tileBox != null) {
                    String[] zxy = url.substring(url.indexOf(TomTomClient.TILE_PATH + "/") + TomTomClient.TILE_PATH.length() + 1).split("/");
                    body = boxedTile(new TileId(Integer.parseInt(zxy[0]), Integer.parseInt(zxy[1]), Integer.parseInt(zxy[2])), tileBox);
                }
                return new TrafficResponse(200, "gzip", new ByteArrayInputStream(gzip(body)), () -> Unit.INSTANCE);
            }
            if (url.contains(TomTomClient.INCIDENTS_PATH + "?")) {
                return new TrafficResponse(200, null, new ByteArrayInputStream(incidentBody.getBytes(StandardCharsets.UTF_8)), () -> Unit.INSTANCE);
            }
            throw new AssertionError("TomTomTrafficTest: a request for neither tiles nor incidents: " + url);
        }

        int tileRequests() {
            int n = 0;
            for (String u : urls) if (u.contains(TomTomClient.TILE_PATH + "/")) n++;
            return n;
        }

        int incidentRequests() {
            int n = 0;
            for (String u : urls) if (u.contains(TomTomClient.INCIDENTS_PATH + "?")) n++;
            return n;
        }
    }

    private static byte[] gzip(byte[] bytes) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
                zip.write(bytes);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** TomTom alone, on or off, with [key] and an incident limit of [limit]. */
    private static TrafficSettings tomTom(boolean on, String key, int limit) {
        return new TrafficSettings(on, "", 10, 30, 4500, false, "", key, TrafficSelection.TOMTOM, TrafficTandem.COMBINED, limit, true);
    }

    private static TrafficSettings both(boolean on, String hereKey, String tomTomKey, TrafficSelection selection, TrafficTandem tandem) {
        return new TrafficSettings(on, hereKey, 10, 30, 4500, true, "", tomTomKey, selection, tandem, 2250, true);
    }

    private static TomTomClient client(TrafficSettings s, MemoryTrafficLedger incidents, MemoryTrafficLedger tiles, FakeHttp http, long[] clock) {
        http.incidents = incidents;
        http.tiles = tiles;
        return new TomTomClient(() -> s, incidents, tiles, http, () -> 42L, () -> clock[0], false, ZoneOffset.UTC);
    }

    private static MemoryTrafficLedger ledger() {
        return new MemoryTrafficLedger(new TrafficStatus());
    }

    private static TrafficSnapshot fetched(TrafficFetch result) {
        check(result instanceof TrafficFetch.Fetched, "fetched: " + describe(result));
        return ((TrafficFetch.Fetched) result).getSnapshot();
    }

    private static TrafficFetch.Failed failed(TrafficFetch result) {
        check(result instanceof TrafficFetch.Failed, "failed: " + describe(result));
        return (TrafficFetch.Failed) result;
    }

    private static TrafficFetch.Skipped skipped(TrafficFetch result) {
        check(result instanceof TrafficFetch.Skipped, "skipped: " + describe(result));
        return (TrafficFetch.Skipped) result;
    }

    private static String describe(TrafficFetch result) {
        if (result instanceof TrafficFetch.Failed) return "failed " + ((TrafficFetch.Failed) result).getMessage();
        if (result instanceof TrafficFetch.Skipped) return "skipped " + ((TrafficFetch.Skipped) result).getMessage();
        return "fetched";
    }

    // ---- 1. settings ------------------------------------------------------------------

    private static void settingsForBothProviders() {
        TrafficSettings d = new TrafficSettings();
        check(d.getTomTomKey().isEmpty() && d.getSelection() == TrafficSelection.HERE && d.getTandem() == TrafficTandem.COMBINED,
            "no TomTom key, HERE alone, combined by default");
        check(d.getTomTomLimit() == 2250 && TrafficSettings.DEFAULT_TOMTOM_LIMIT == 2250 && !d.getTomTomDisclosed(),
            "2,250 TomTom incident requests a month, not yet disclosed");
        check(d.keyed().isEmpty() && !d.getUsable(), "nothing keyed, nothing usable");

        TrafficSettings chosen = new TrafficSettings(true, HERE_KEY, 15, 50, 3000, true, "", KEY,
            TrafficSelection.TOMTOM_HERE, TrafficTandem.FAILOVER, 1234, true);
        TrafficSettings back = TrafficSettings.Companion.fromStored(chosen.toStored());
        check(back.equals(chosen), "every new field reads back: " + back);
        check(back.getTomTomKey().equals(KEY) && back.getSelection() == TrafficSelection.TOMTOM_HERE
            && back.getTandem() == TrafficTandem.FAILOVER && back.getTomTomLimit() == 1234 && back.getTomTomDisclosed(), "field by field");
        check(chosen.toStored().get(TrafficSettings.SELECTION).equals("tomtom_here")
            && chosen.toStored().get(TrafficSettings.TANDEM).equals("failover")
            && chosen.toStored().get(TrafficSettings.TOMTOM_KEY).equals(KEY)
            && chosen.toStored().get(TrafficSettings.TOMTOM_LIMIT).equals(1234)
            && chosen.toStored().get(TrafficSettings.TOMTOM_DISCLOSED).equals(true), "stored as their keys");
        check(chosen.keyed().equals(List.of(TrafficProvider.TOMTOM, TrafficProvider.HERE)), "both keyed, in the selection's order");
        check(chosen.key(TrafficProvider.HERE).equals(HERE_KEY) && chosen.key(TrafficProvider.TOMTOM).equals(KEY), "each provider's key");
        check(chosen.disclosed(TrafficProvider.HERE) && chosen.disclosed(TrafficProvider.TOMTOM)
            && !tomTom(true, KEY, 2250).disclosed(TrafficProvider.HERE), "each provider's disclosure");

        TrafficSettings tomTomOnly = TrafficSettings.Companion.fromStored(tomTom(true, KEY, 2250).toStored());
        check(tomTomOnly.getEnabled() && tomTomOnly.getUsable() && tomTomOnly.keyed().equals(List.of(TrafficProvider.TOMTOM)),
            "TomTom alone stays on with only a TomTom key");
        TrafficSettings hereOnly = both(true, "", KEY, TrafficSelection.HERE, TrafficTandem.COMBINED);
        check(!hereOnly.repaired().getEnabled() && !hereOnly.getUsable() && hereOnly.keyed().isEmpty(),
            "HERE alone with only a TomTom key is repaired to off");
        check(!TrafficSettings.Companion.fromStored(hereOnly.toStored()).getEnabled(), "and stored off");
        TrafficSettings oneOfTwo = both(true, "", KEY, TrafficSelection.HERE_TOMTOM, TrafficTandem.COMBINED);
        check(oneOfTwo.repaired().getEnabled() && oneOfTwo.keyed().equals(List.of(TrafficProvider.TOMTOM)),
            "both chosen with one key: on, the keyed one usable");

        Map<String, Object> odd = new HashMap<>();
        odd.put(TrafficSettings.ENABLED, true);
        odd.put(TrafficSettings.API_KEY, HERE_KEY);
        odd.put(TrafficSettings.SELECTION, "bing");
        odd.put(TrafficSettings.TANDEM, 3);
        odd.put(TrafficSettings.TOMTOM_LIMIT, 0);
        odd.put(TrafficSettings.TOMTOM_KEY, "two words");
        TrafficSettings repaired = TrafficSettings.Companion.fromStored(odd);
        check(repaired.getSelection() == TrafficSelection.HERE && repaired.getTandem() == TrafficTandem.COMBINED,
            "an unknown selection or tandem reads as its default");
        check(repaired.getTomTomLimit() == 2250 && repaired.getTomTomKey().isEmpty() && repaired.getEnabled(),
            "a limit out of range and a key that cannot be one are put back; HERE keeps it on");
        Map<String, Object> old = new HashMap<>();
        old.put(TrafficSettings.ENABLED, true);
        old.put(TrafficSettings.API_KEY, HERE_KEY);
        TrafficSettings today = TrafficSettings.Companion.fromStored(old);
        check(today.getEnabled() && today.getSelection() == TrafficSelection.HERE && today.keyed().equals(List.of(TrafficProvider.HERE)),
            "a file from before TomTom is HERE alone, as it was");

        check(TrafficSelection.Companion.of("here_tomtom") == TrafficSelection.HERE_TOMTOM && TrafficSelection.Companion.of("x") == null
            && TrafficTandem.Companion.of("failover") == TrafficTandem.FAILOVER && TrafficTandem.Companion.of(null) == null, "the keys' readers");
        check(Integer.valueOf(1).equals(TrafficSettings.Companion.parseLimit("1", TrafficSettings.Companion.getTOMTOM_LIMIT_RANGE()))
            && TrafficSettings.Companion.parseLimit("1") == null, "TomTom's limit may be 1, HERE's may not");
        check(TrafficSettings.Companion.parseLimit("1,000,001", TrafficSettings.Companion.getTOMTOM_LIMIT_RANGE()) == null, "nor over a million");
        check(!chosen.toString().contains(KEY) && !chosen.toString().contains(HERE_KEY) && chosen.toString().contains("tomTomKey=set")
            && d.toString().contains("tomTomKey=none"), "toString never shows either key: " + chosen);
        check(TrafficSkip.NO_KEY.message(TrafficProvider.TOMTOM).equals("No TomTom API key is saved")
            && TrafficSkip.NO_KEY.message(TrafficProvider.HERE).equals("No HERE API key is saved")
            && TrafficSkip.OFF.message(TrafficProvider.TOMTOM).equals("Live traffic is off"), "the skip's words name the provider");
    }

    // ---- 2. tiles ---------------------------------------------------------------------

    private static void tilesCoverTheBox() {
        List<TileId> tiles = TomTomTiles.INSTANCE.covering(DALLAS, TomTomClient.FLOW_ZOOM);
        check(TomTomClient.FLOW_ZOOM == 12, "zoom 12");
        check(tiles.equals(List.of(new TileId(12, 946, 1652), new TileId(12, 947, 1652), new TileId(12, 946, 1653), new TileId(12, 947, 1653))),
            "x 946..947 west to east, rows north to south: " + tiles);
        double[][] corners = {{DALLAS.getSouth(), DALLAS.getWest()}, {DALLAS.getSouth(), DALLAS.getEast()},
            {DALLAS.getNorth(), DALLAS.getWest()}, {DALLAS.getNorth(), DALLAS.getEast()}};
        for (double[] c : corners) {
            check(tiles.contains(new TileId(12, tileX(c[1]), tileY(c[0]))), "corner " + c[0] + "," + c[1] + " is in a tile");
        }
        List<TileId> pole = TomTomTiles.INSTANCE.covering(new TrafficBox(89.0, 179.5, 90.0, 180.0), 2);
        check(pole.equals(List.of(new TileId(2, 3, 0))), "latitude and x clamped at the edge of the world: " + pole);
    }

    // ---- 3. the fetch -----------------------------------------------------------------

    private static void aFetchIsItsTilesThenItsIncidents() {
        long[] clock = {SEPTEMBER};
        FakeHttp http = new FakeHttp();
        http.tileBox = DALLAS;
        MemoryTrafficLedger incidents = ledger();
        MemoryTrafficLedger tiles = ledger();
        TomTomClient c = client(tomTom(true, KEY, 2250), incidents, tiles, http, clock);
        check(c.getProvider() == TrafficProvider.TOMTOM, "the TomTom client");
        TrafficSnapshot snap = fetched(c.fetch(DALLAS));
        List<TileId> covering = TomTomTiles.INSTANCE.covering(DALLAS, TomTomClient.FLOW_ZOOM);
        int n = covering.size();
        check(http.urls.size() == n + 1, "exactly its tiles and one incidents request: " + http.urls);
        for (int i = 0; i < n; i++) {
            TileId t = covering.get(i);
            check(http.urls.get(i).equals("https://api.tomtom.com/maps/orbis/traffic/flow/vector/tile/12/" + t.getX() + "/" + t.getY()),
                "tile " + i + ": " + http.urls.get(i));
        }
        check(http.urls.get(n).equals("https://api.tomtom.com/maps/orbis/traffic/incidents/details"
            + "?bbox=-96.85000,32.75000,-96.75000,32.80000&timeValidity=present"), "incidents: " + http.urls.get(n));
        check(TomTomClient.Companion.query(DALLAS).equals("-96.85000,32.75000,-96.75000,32.80000"), "the bbox: west, south, east, north");
        for (int i = 0; i <= n; i++) {
            String url = http.urls.get(i);
            Map<String, String> h = http.headers.get(i);
            boolean isTile = i < n;
            check(!url.contains(KEY) && !url.contains("key="), "no key in the URL: " + url);
            check(KEY.equals(h.get("TomTom-Api-Key")), "the key in its header");
            check("2".equals(h.get("TomTom-Api-Version")) && TomTomClient.API_VERSION.equals("2"), "version 2");
            check((isTile ? TomTomClient.FLOW_ATTRIBUTES : TomTomClient.INCIDENT_ATTRIBUTES).equals(h.get("Attributes")),
                "the Attributes asked for: " + h.get("Attributes"));
            check((isTile ? "application/vnd.mapbox-vector-tile" : "application/json").equals(h.get("Accept")), "Accept: " + h.get("Accept"));
            check("gzip".equals(h.get("Accept-Encoding")), "gzip asked for");
            int[] counted = http.countedAtSend.get(i);
            check(isTile ? counted[0] == i + 1 && counted[1] == 0 : counted[0] == n && counted[1] == 1,
                "request " + i + " counted in its own meter before it went: " + counted[0] + "/" + counted[1]);
        }
        check(TomTomClient.FLOW_ATTRIBUTES.equals("tags(road_category,relative_speed,absolute_speed,road_closure),"
            + "roadCategories(motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary,secondary_link)"), "the flow Attributes");
        check(TomTomClient.INCIDENT_ATTRIBUTES.equals(
            "incidents(type,geometry(type,coordinates),properties(id,iconCategory,magnitudeOfDelay,events(description)))"), "the incident Attributes");
        check(snap.getBox().equals(DALLAS) && snap.getReceivedAtMs() == 42L, "the snapshot is the box asked for, stamped");
        check(snap.getProviders().equals(EnumSet.of(TrafficProvider.TOMTOM)), "and TomTom's: " + snap.getProviders());
        check(snap.getFlow().size() == n && snap.getIncidents().size() == 3,
            "a queue per tile, the ones outside the box dropped, and three incidents read: " + snap.getFlow().size());
        for (FlowStretch f : snap.getFlow()) {
            for (int i = 0; i < f.getLatitudes().length; i++) {
                double la = f.getLatitudes()[i];
                double lo = f.getLongitudes()[i];
                check(la >= DALLAS.getSouth() - EPS && la <= DALLAS.getNorth() + EPS && lo >= DALLAS.getWest() - EPS
                    && lo <= DALLAS.getEast() + EPS, "the fetch clips flow to its box: " + la + "," + lo);
            }
        }
        check(tiles.status().getRequests() == n && tiles.status().getMonth().equals("2026-09") && tiles.status().getLastFetchMs() == 0L,
            "the tiles counted in September in their own meter");
        check(incidents.status().getRequests() == 1 && incidents.status().getLastFetchMs() == SEPTEMBER
            && incidents.status().getLastError() == null, "one incidents request; the fetch recorded there");

        // Off, or on with no TomTom key: no request at all.
        for (TrafficSettings s : new TrafficSettings[] {
            tomTom(false, KEY, 2250), tomTom(true, "", 2250), tomTom(true, "  ", 2250),
            both(true, HERE_KEY, "", TrafficSelection.TOMTOM, TrafficTandem.COMBINED), new TrafficSettings(),
        }) {
            FakeHttp none = new FakeHttp();
            MemoryTrafficLedger in = ledger();
            MemoryTrafficLedger ti = ledger();
            TomTomClient off = client(s, in, ti, none, clock);
            TrafficFetch.Skipped why = skipped(off.fetch(DALLAS));
            check(why.getWhy() == (s.getEnabled() ? TrafficSkip.NO_KEY : TrafficSkip.OFF), "skipped for the right reason: " + s);
            check(why.getWhy() != TrafficSkip.NO_KEY || why.getMessage().equals("No TomTom API key is saved"), "in TomTom's words");
            check(!off.test().getWorks(), "Test does nothing either");
            check(none.urls.isEmpty() && in.status().getRequests() == 0 && ti.status().getRequests() == 0, "nothing sent or counted");
        }

        // Turned off after the first tile: nothing more goes.
        TrafficSettings[] now = {tomTom(true, KEY, 2250)};
        FakeHttp mid = new FakeHttp();
        MemoryTrafficLedger in = ledger();
        MemoryTrafficLedger ti = ledger();
        mid.incidents = in;
        mid.tiles = ti;
        TomTomClient turning = new TomTomClient(() -> now[0], in, ti, mid, () -> 1L, () -> clock[0], false, ZoneOffset.UTC);
        mid.afterTile = () -> now[0] = tomTom(false, KEY, 2250);
        check(skipped(turning.fetch(DALLAS)).getWhy() == TrafficSkip.OFF && mid.urls.size() == 1 && ti.status().getRequests() == 1
            && in.status().getRequests() == 0, "turned off mid-fetch: no more tiles and no incidents");

        // TomTom not chosen, or its terms not accepted: off for TomTom, whatever key is saved.
        for (TrafficSettings s : new TrafficSettings[] {
            both(true, HERE_KEY, KEY, TrafficSelection.HERE, TrafficTandem.COMBINED),
            new TrafficSettings(true, "", 10, 30, 4500, false, "", KEY, TrafficSelection.TOMTOM, TrafficTandem.COMBINED, 2250, false),
        }) {
            FakeHttp none = new FakeHttp();
            TomTomClient away = client(s, ledger(), ledger(), none, clock);
            check(skipped(away.fetch(DALLAS)).getWhy() == TrafficSkip.OFF && !away.test().getWorks() && none.urls.isEmpty(),
                "TomTom off for its client, nothing sent: " + s);
        }
        now[0] = tomTom(true, KEY, 2250);
        FakeHttp switched = new FakeHttp();
        MemoryTrafficLedger si = ledger();
        MemoryTrafficLedger st = ledger();
        switched.incidents = si;
        switched.tiles = st;
        TomTomClient switching = new TomTomClient(() -> now[0], si, st, switched, () -> 1L, () -> clock[0], false, ZoneOffset.UTC);
        switched.afterTile = () -> now[0] = both(true, HERE_KEY, KEY, TrafficSelection.HERE, TrafficTandem.COMBINED);
        check(skipped(switching.fetch(DALLAS)).getWhy() == TrafficSkip.OFF && switched.urls.size() == 1
            && st.status().getRequests() == 1 && si.status().getRequests() == 0, "switched to HERE mid-fetch: TomTom asked nothing more");

        // The lab's stand-in only in a debuggable build.
        TrafficSettings lab = new TrafficSettings(true, "", 10, 30, 4500, false, "http://10.0.2.2:8765", KEY,
            TrafficSelection.TOMTOM, TrafficTandem.COMBINED, 2250, true);
        for (boolean debuggable : new boolean[] {false, true}) {
            FakeHttp h = new FakeHttp();
            MemoryTrafficLedger li = ledger();
            MemoryTrafficLedger lt = ledger();
            h.incidents = li;
            h.tiles = lt;
            fetched(new TomTomClient(() -> lab, li, lt, h, () -> 1L, () -> clock[0], debuggable, ZoneOffset.UTC).fetch(DALLAS));
            String base = debuggable ? "http://10.0.2.2:8765" : TomTomClient.TOMTOM_BASE;
            check(TomTomClient.TOMTOM_BASE.equals("https://api.tomtom.com"), "TomTom's host");
            for (String u : h.urls) check(u.startsWith(base + "/maps/orbis/traffic/"), (debuggable ? "debug: the stand-in " : "release: TomTom ") + u);
        }
    }

    // ---- 4. tiles read ----------------------------------------------------------------

    private static void flowTilesAreRead() throws IOException {
        check(TomTomTiles.LAYER.equals(FLOW_LAYER), "the flow layer's name: " + TomTomTiles.LAYER);
        Layer flow = new Layer(FLOW_LAYER)
            // A queue on a motorway, its last point repeated.
            .feature(2, pts(100, 100, 1000, 100, 1000, 100), "road_category", "motorway", "relative_speed", 0.14, "absolute_speed", 12.6)
            // Across the east edge into the margin, speeds as a float and a sint.
            .feature(2, pts(2000, 3000, 5000, 3000), "road_category", "primary", "relative_speed", 0.5f, "absolute_speed", 36)
            // Closed with no speeds.
            .feature(2, pts(500, 2000, 500, 2500), "road_category", "street", "road_closure", true)
            // No relative speed and not closed: no data.
            .feature(2, pts(600, 600, 900, 900), "road_category", "trunk", "absolute_speed", 50.0)
            // A point, whatever its tags.
            .feature(1, pts(700, 700), "road_category", "motorway", "relative_speed", 0.1)
            // Wholly in TomTom's margin, west of the tile.
            .feature(2, pts(-300, 500, -100, 600), "road_category", "motorway", "relative_speed", 0.2)
            // Out of the west edge and back in: two pieces.
            .feature(2, pts(200, 4000, -200, 4000, -200, 3800, 200, 3800), "road_category", "secondary_link", "relative_speed", 1.0,
                "absolute_speed", 50L);
        Layer other = new Layer("Traffic incidents").feature(2, pts(100, 200, 900, 200), "road_category", "motorway", "relative_speed", 0.1);
        List<FlowStretch> read = TomTomTiles.INSTANCE.flow(tile(other, flow), TILE);
        check(read.size() == 5, "the queue, the edge, the closure and two pieces, from the flow layer only: " + read.size());

        FlowStretch queue = read.get(0);
        FlowSpan q = queue.getSpans().get(0);
        check(queue.getDescription() == null && queue.getFunctionalClass() == 1, "a motorway is class 1, with no name");
        check(queue.getLatitudes().length == 2 && queue.getLongitudes().length == 2, "the repeated point dropped");
        check(close(queue.getLongitudes()[0], lon(TILE, 100)) && close(queue.getLongitudes()[1], lon(TILE, 1000))
            && close(queue.getLatitudes()[0], lat(TILE, 100)) && close(queue.getLatitudes()[1], lat(TILE, 100)),
            "tile units to degrees: " + queue.getLatitudes()[0] + "," + queue.getLongitudes()[0]);
        double length = RouteFollower.Companion.distance(queue.getLatitudes()[0], queue.getLongitudes()[0],
            queue.getLatitudes()[1], queue.getLongitudes()[1]);
        check(queue.getLengthM() > 0 && close(queue.getLengthM(), length), "its length: " + queue.getLengthM());
        check(queue.getSpans().size() == 1 && q.getFromM() == 0.0 && q.getToM() == queue.getLengthM(), "one span over all of it");
        check(q.getSpeedMps() != null && close(q.getSpeedMps(), 3.5), "12.6 km/h is 3.5 m/s: " + q.getSpeedMps());
        check(q.getFreeFlowMps() != null && close(q.getFreeFlowMps(), 25.0), "free flow 12.6 / 0.14 km/h is 25 m/s: " + q.getFreeFlowMps());
        check(close(q.getJamFactor(), 8.6) && q.getTraversability() == Traversability.OPEN, "jam 8.6, open: " + q.getJamFactor());

        FlowStretch edge = read.get(1);
        FlowSpan e = edge.getSpans().get(0);
        double east = lon(new TileId(12, TILE.getX() + 1, TILE.getY()), 0);
        check(edge.getLongitudes().length == 2 && close(edge.getLongitudes()[1], east) && close(edge.getLongitudes()[0], lon(TILE, 2000)),
            "clipped at the tile's east edge: " + edge.getLongitudes()[1] + " for " + east);
        check(edge.getFunctionalClass() == 3, "a primary road is class 3");
        check(e.getSpeedMps() != null && close(e.getSpeedMps(), 10.0) && e.getFreeFlowMps() != null && close(e.getFreeFlowMps(), 20.0)
            && close(e.getJamFactor(), 5.0), "a float and a sint read as numbers");

        FlowStretch closed = read.get(2);
        FlowSpan cs = closed.getSpans().get(0);
        check(closed.getFunctionalClass() == 5 && cs.getTraversability() == Traversability.CLOSED && cs.getJamFactor() == 10.0
            && cs.getSpeedMps() == null && cs.getFreeFlowMps() == null, "closed with no speeds: class 5, jam 10");

        double west = lon(TILE, 0);
        FlowStretch out = read.get(3);
        FlowStretch in = read.get(4);
        check(out.getFunctionalClass() == 4 && in.getFunctionalClass() == 4, "a secondary link is class 4");
        check(close(out.getLongitudes()[0], lon(TILE, 200)) && close(out.getLongitudes()[out.getLongitudes().length - 1], west)
            && close(in.getLongitudes()[0], west) && close(in.getLongitudes()[in.getLongitudes().length - 1], lon(TILE, 200)),
            "split where it leaves and comes back, the margin dropped");
        check(close(out.getLatitudes()[0], lat(TILE, 4000)) && close(in.getLatitudes()[0], lat(TILE, 3800)), "each piece on its own row");
        check(close(out.getSpans().get(0).getJamFactor(), 0.0) && out.getSpans().get(0).getSpeedMps() != null
            && close(out.getSpans().get(0).getSpeedMps(), 50 / 3.6), "free flow, its speed an int64");

        check(TomTomTiles.INSTANCE.flow(new byte[0], TILE).isEmpty(), "an empty tile is no flow");

        // A speed as a uint64 Value: 72 km/h is 20 m/s, free flow 72 / 0.5 km/h is 40 m/s.
        List<FlowStretch> unsigned = TomTomTiles.INSTANCE.flow(tile(new Layer(FLOW_LAYER)
            .feature(2, pts(100, 100, 900, 100), "road_category", "trunk", "relative_speed", 0.5, "absolute_speed", new Uint(72))), TILE);
        FlowSpan u = unsigned.get(0).getSpans().get(0);
        check(unsigned.size() == 1 && u.getSpeedMps() != null && close(u.getSpeedMps(), 20.0) && u.getFreeFlowMps() != null
            && close(u.getFreeFlowMps(), 40.0), "a uint speed reads as a number: " + u.getSpeedMps());

        // Clipped to the box as well as the tile: a box whose east edge is inside the tile.
        byte[] across = tile(new Layer(FLOW_LAYER)
            .feature(2, pts(2000, 3000, 3500, 3000), "road_category", "primary", "relative_speed", 0.5)
            .feature(2, pts(3200, 1000, 3600, 1000), "road_category", "primary", "relative_speed", 0.5));
        TrafficBox upTo3000 = new TrafficBox(lat(TILE, 4096) - 1, lon(TILE, 0) - 1, lat(TILE, 0) + 1, lon(TILE, 3000));
        List<FlowStretch> inBox = TomTomTiles.INSTANCE.flow(across, TILE, upTo3000);
        check(inBox.size() == 1, "a line wholly east of the box is dropped: " + inBox.size());
        double[] lons = inBox.get(0).getLongitudes();
        check(close(lons[0], lon(TILE, 2000)) && close(lons[lons.length - 1], lon(TILE, 3000)),
            "a line across the box's east edge ends at it: " + lons[lons.length - 1]);
        List<FlowStretch> wholeTile = TomTomTiles.INSTANCE.flow(across, TILE);
        check(wholeTile.size() == 2 && close(wholeTile.get(0).getLongitudes()[1], lon(TILE, 3500)), "without a box, the tile only");

        // Repeated fields one by one, or packed over several records, read as packed.
        int[][] reenter = pts(200, 4000, -200, 4000, -200, 3800, 200, 3800);
        Object[] tags = {"road_category", "secondary_link", "relative_speed", 0.25, "absolute_speed", 50.0};
        List<FlowStretch> packed = TomTomTiles.INSTANCE.flow(tile(new Layer(FLOW_LAYER).feature(2, reenter, tags)), TILE);
        List<FlowStretch> split = TomTomTiles.INSTANCE.flow(tile(new Layer(FLOW_LAYER).featureSplit(2, reenter, tags)), TILE);
        check(packed.size() == 2 && split.size() == 2, "both read whole: " + packed.size() + "/" + split.size());
        for (int i = 0; i < 2; i++) {
            FlowStretch a = packed.get(i);
            FlowStretch b = split.get(i);
            FlowSpan sa = a.getSpans().get(0);
            FlowSpan sb = b.getSpans().get(0);
            check(Arrays.equals(a.getLatitudes(), b.getLatitudes()) && Arrays.equals(a.getLongitudes(), b.getLongitudes())
                && a.getFunctionalClass() == b.getFunctionalClass() && sa.getJamFactor() == sb.getJamFactor()
                && sa.getSpeedMps().equals(sb.getSpeedMps()) && sa.getFreeFlowMps().equals(sb.getFreeFlowMps()),
                "unpacked and split tags and geometry read the same as packed, piece " + i);
        }

        // A ClosePath in a linestring is not TomTom's tile.
        List<Integer> closePath = List.of(1 | (1 << 3), zigzag(100), zigzag(100), 2 | (2 << 3), zigzag(400), 0, 0, zigzag(400), 7 | (1 << 3));
        String closeWhy = null;
        try {
            TomTomTiles.INSTANCE.flow(tile(new Layer(FLOW_LAYER).featureRaw(2, closePath, "relative_speed", 0.5)), TILE);
        } catch (TrafficParseException ex) {
            closeWhy = ex.getMessage();
        }
        check(closeWhy != null && closeWhy.startsWith("not TomTom's tile: "), "a ClosePath in a linestring is refused: " + closeWhy);
        for (byte[] garbage : new byte[][] {
            {(byte) 0xff, (byte) 0xff, (byte) 0xff},
            {0x1a, 0x10, 0x01},
            {0x19, 0x01, 0x02},
            // A tenth varint byte above 1: past 64 bits.
            {0x08, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x02},
        }) {
            String why = null;
            try {
                TomTomTiles.INSTANCE.flow(garbage, TILE);
            } catch (TrafficParseException ex) {
                why = ex.getMessage();
            }
            check(why != null && why.startsWith("not TomTom's tile: "), "garbage is a parse error: " + why);
        }

        String[][] classes = {
            {"motorway", "1"}, {"motorway_link", "1"}, {"trunk", "2"}, {"trunk_link", "2"}, {"primary", "3"}, {"primary_link", "3"},
            {"secondary", "4"}, {"secondary_link", "4"}, {"tertiary", "4"}, {"tertiary_link", "4"}, {"street", "5"}, {"", "5"},
        };
        for (String[] c : classes) {
            check(TomTomTiles.INSTANCE.functionalClass(c[0]) == Integer.parseInt(c[1]), "road_category " + c[0]);
        }
        check(TomTomTiles.INSTANCE.functionalClass(null) == 5, "no road_category is class 5");
    }

    // ---- 5. incidents -----------------------------------------------------------------

    private static void incidentsAreRead() throws IOException {
        List<TrafficIncident> read = TomTomJson.INSTANCE.incidents(new StringReader(INCIDENTS));
        check(read.size() == 3, "null and geometry-less entries dropped: " + read.size());
        TrafficIncident closure = read.get(0);
        check(closure.getId().equals("tt-1") && closure.getType().equals("roadClosed") && closure.getRoadClosed(), "a closure");
        check("major".equals(closure.getCriticality()) && "Closed".equals(closure.getSummary()), "its delay and first description that says something");
        check(closure.getLatitudes().length == 3 && closure.getLongitudes()[0] == -96.80 && closure.getLatitudes()[2] == 32.782,
            "a LineString's points, the repeat and the one off the globe dropped");
        TrafficIncident jam = read.get(1);
        check(jam.getLatitudes().length == 1 && jam.getLatitudes()[0] == 32.77 && jam.getLongitudes()[0] == -96.79, "a Point is one point");
        check(jam.getType().equals("jam") && !jam.getRoadClosed() && "moderate".equals(jam.getCriticality())
            && "Stationary traffic".equals(jam.getSummary()), "a jam");
        TrafficIncident legacy = read.get(2);
        check(legacy.getId().equals("tt-4") && legacy.getType().equals("8") && !legacy.getRoadClosed(),
            "a numeric iconCategory reads as its digits and is no closure");
        check(legacy.getCriticality() == null && legacy.getSummary() == null, "no delay or events: null");
        check(TomTomJson.INSTANCE.incidents(new StringReader("{\"incidents\":[]}")).isEmpty(), "none");
        for (String notTomTom : new String[] {"{}", "{\"incidents\":null}", "{\"results\":[]}"}) {
            String said = null;
            try {
                TomTomJson.INSTANCE.incidents(new StringReader(notTomTom));
            } catch (TrafficParseException e) {
                said = e.getMessage();
            }
            check(said != null && said.startsWith("not TomTom's JSON"), "no incidents array is not TomTom's answer: " + notTomTom);
        }
        String why = null;
        try {
            TomTomJson.INSTANCE.incidents(new StringReader("{\"incidents\":[{\"geometry\":"));
        } catch (IOException e) {
            why = e.getMessage();
        }
        check(why != null && why.startsWith("not TomTom's JSON"), "a body cut short is an error in TomTom's name: " + why);
    }

    // ---- 6. errors --------------------------------------------------------------------

    private static void errorsAreReadWithoutTheKey() {
        check("Invalid API key (FORBIDDEN)".equals(TomTomJson.INSTANCE.error(FORBIDDEN)), "detailedError: " + TomTomJson.INSTANCE.error(FORBIDDEN));
        check("Over quota (TOO_MANY)".equals(TomTomJson.INSTANCE.error("{\"detailedError \":{\"code\":\"TOO_MANY\",\"message\":\"Over quota\"}}")),
            "TomTom's own spelling with a trailing space");
        check("Bad request".equals(TomTomJson.INSTANCE.error("{\"detailedError\":{\"message\":\"Bad request\"}}")), "no code");
        check("Forbidden".equals(TomTomJson.INSTANCE.error("{\"error\":\"Forbidden\"}"))
            && "Nope".equals(TomTomJson.INSTANCE.error("{\"errorText\":\"Nope\"}"))
            && "Gone".equals(TomTomJson.INSTANCE.error("{\"message\":\"Gone\"}")), "a top-level error, errorText or message");
        check(TomTomJson.INSTANCE.error("<html>Bad gateway</html>") == null && TomTomJson.INSTANCE.error("{\"other\":1}") == null
            && TomTomJson.INSTANCE.error("{\"detailedError\":") == null, "neither shape, or not JSON: null");

        long[] clock = {SEPTEMBER};
        FakeHttp http = new FakeHttp();
        http.status = 403;
        http.error = FORBIDDEN;
        MemoryTrafficLedger incidents = ledger();
        MemoryTrafficLedger tiles = ledger();
        TrafficFetch.Failed f = failed(client(tomTom(true, KEY, 2250), incidents, tiles, http, clock).fetch(DALLAS));
        check(f.getMessage().equals("TomTom 403: Invalid API key (FORBIDDEN)"), "TomTom's words: " + f.getMessage());
        check(Integer.valueOf(403).equals(f.getHttpStatus()) && f.getCause() == TrafficFailure.HTTP, "an HTTP failure, with its status");
        check(http.urls.size() == 1, "nothing more after the first tile failed");
        check(f.getMessage().equals(incidents.status().getLastError()) && tiles.status().getLastError() == null,
            "the error recorded with the incidents, where the card shows TomTom's status");
        check(incidents.status().getLastFetchMs() == 0L && tiles.status().getRequests() == 1, "no fetch; the tile still counted");
        TrafficCheck t = client(tomTom(true, KEY, 2250), incidents, tiles, http, clock).test();
        check(!t.getWorks() && t.getMessage().equals("TomTom 403: Invalid API key (FORBIDDEN)"), "Test says: " + t.getMessage());

        http.status = 503;
        http.error = "Service Unavailable";
        check(failed(client(tomTom(true, KEY, 2250), ledger(), ledger(), http, clock).fetch(DALLAS)).getMessage()
            .equals("TomTom 503: no explanation given"), "an error body of neither shape");

        FakeHttp down = new FakeHttp();
        down.failWith = new IOException("refused: https://api.tomtom.com/x?key=" + KEY);
        MemoryTrafficLedger in = ledger();
        TrafficFetch.Failed n = failed(client(tomTom(true, KEY, 2250), in, ledger(), down, clock).fetch(DALLAS));
        check(n.getMessage().startsWith("Could not reach TomTom: IOException") && !n.getMessage().contains(KEY), "redacted: " + n.getMessage());
        check(n.getCause() == TrafficFailure.NETWORK && n.getHttpStatus() == null, "a network failure with no status");
        check(!in.status().getLastError().contains(KEY), "and never stored with the key");

        FakeHttp garbage = new FakeHttp();
        garbage.tile = new byte[] {0x1a, 0x10, 0x01};
        TrafficFetch.Failed p = failed(client(tomTom(true, KEY, 2250), ledger(), ledger(), garbage, clock).fetch(DALLAS));
        check(p.getMessage().startsWith("TomTom's answer could not be read (not TomTom's tile") && p.getCause() == TrafficFailure.PARSE,
            "a tile that cannot be read: " + p.getMessage());
        FakeHttp cut = new FakeHttp();
        cut.incidentBody = "{\"incidents\":[{";
        TrafficFetch.Failed j = failed(client(tomTom(true, KEY, 2250), ledger(), ledger(), cut, clock).fetch(DALLAS));
        check(j.getMessage().startsWith("TomTom's answer could not be read (not TomTom's JSON") && j.getCause() == TrafficFailure.PARSE,
            "incidents that cannot be read: " + j.getMessage());
        FakeHttp here = new FakeHttp();
        here.incidentBody = "{\"results\":[]}";
        TrafficFetch.Failed shape = failed(client(tomTom(true, KEY, 2250), ledger(), ledger(), here, clock).fetch(DALLAS));
        check(shape.getCause() == TrafficFailure.PARSE && shape.getMessage().contains("no incidents array"),
            "an answer without incidents fails the fetch, so Failover moves on: " + shape.getMessage());
    }

    // ---- 7. limits --------------------------------------------------------------------

    private static void bothCapsStopBeforeSending() {
        long[] clock = {SEPTEMBER};
        int n = TomTomTiles.INSTANCE.covering(DALLAS, TomTomClient.FLOW_ZOOM).size();
        check(TomTomClient.TILE_LIMIT == 180_000, "180,000 tiles a month");

        FakeHttp http = new FakeHttp();
        MemoryTrafficLedger full = new MemoryTrafficLedger(new TrafficStatus("2026-09", 2250, 0L, null));
        TomTomClient c = client(tomTom(true, KEY, 2250), full, ledger(), http, clock);
        TrafficFetch.Skipped s = skipped(c.fetch(DALLAS));
        check(s.getWhy() == TrafficSkip.LIMIT && s.getMessage().equals("Limit reached until 1 October"), "incidents at their limit: " + s.getMessage());
        check(!c.test().getWorks() && http.urls.isEmpty(), "Test too, and nothing sent");
        check(full.status().atLimit(YearMonth.of(2026, 9), 2250, 1), "the card says so at one request a fetch");

        FakeHttp room = new FakeHttp();
        MemoryTrafficLedger one = new MemoryTrafficLedger(new TrafficStatus("2026-09", 2249, 0L, null));
        fetched(client(tomTom(true, KEY, 2250), one, ledger(), room, clock).fetch(DALLAS));
        check(one.status().getRequests() == 2250 && room.incidentRequests() == 1, "room for one incidents request is room for a fetch");

        FakeHttp tilesFull = new FakeHttp();
        MemoryTrafficLedger nearly = new MemoryTrafficLedger(new TrafficStatus("2026-09", TomTomClient.TILE_LIMIT - n + 1, 0L, null));
        TomTomClient t = client(tomTom(true, KEY, 2250), ledger(), nearly, tilesFull, clock);
        check(skipped(t.fetch(DALLAS)).getWhy() == TrafficSkip.LIMIT && tilesFull.urls.isEmpty(),
            "one tile short of a whole fetch: nothing sent");
        FakeHttp cardTest = new FakeHttp();
        MemoryTrafficLedger exhausted = new MemoryTrafficLedger(new TrafficStatus("2026-09", TomTomClient.TILE_LIMIT, 0L, null));
        TomTomClient noTiles = client(tomTom(true, KEY, 2250), ledger(), exhausted, cardTest, clock);
        check(skipped(noTiles.fetch(DALLAS)).getWhy() == TrafficSkip.LIMIT && cardTest.urls.isEmpty(), "no tiles left: no fetch");
        TrafficCheck works = noTiles.test();
        check(works.getWorks() && works.getMessage().equals("works") && cardTest.urls.size() == 1 && cardTest.incidentRequests() == 1
            && exhausted.status().getRequests() == TomTomClient.TILE_LIMIT,
            "Test needs no tiles, so it works with every tile of the month used");
        check(cardTest.urls.get(0).contains("?bbox=" + TomTomClient.Companion.query(TrafficClient.Companion.getTEST_BOX()) + "&timeValidity=present"),
            "Test's fixed box: " + cardTest.urls.get(0));

        FakeHttp exact = new FakeHttp();
        MemoryTrafficLedger justEnough = new MemoryTrafficLedger(new TrafficStatus("2026-09", TomTomClient.TILE_LIMIT - n, 0L, null));
        fetched(client(tomTom(true, KEY, 2250), ledger(), justEnough, exact, clock).fetch(DALLAS));
        check(justEnough.status().getRequests() == TomTomClient.TILE_LIMIT && exact.tileRequests() == n, "exactly enough tiles left: the fetch goes");

        clock[0] = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();
        FakeHttp october = new FakeHttp();
        fetched(client(tomTom(true, KEY, 2250), full, nearly, october, clock).fetch(DALLAS));
        check(full.status().getMonth().equals("2026-10") && full.status().getRequests() == 1
            && nearly.status().getRequests() == n, "both meters start again on the 1st");
    }

    // ---- 8. incident boxes ------------------------------------------------------------

    private static double km2(TrafficBox b) {
        double equatorward = b.getSouth() < 0 && b.getNorth() > 0 ? 0 : Math.min(Math.abs(b.getSouth()), Math.abs(b.getNorth()));
        return (b.getNorth() - b.getSouth()) * 111.195 * (b.getEast() - b.getWest()) * 111.195 * Math.cos(Math.toRadians(equatorward));
    }

    private static void largeBoxesAreSplitForIncidents() {
        check(TomTomClient.MAX_INCIDENT_KM2 == 9_000.0, "9,000 km², under TomTom's 10,000");
        check(TomTomClient.Companion.incidentBoxes(DALLAS).equals(List.of(DALLAS)), "Dallas is one box");
        List<TrafficBox> cells = TomTomClient.Companion.incidentBoxes(BIG);
        int k = (int) Math.ceil(Math.sqrt(km2(BIG) / TomTomClient.MAX_INCIDENT_KM2));
        check(k == 2 && cells.size() == k * k, "a 0.999 degree box at 30 N is 2 by 2: " + cells.size());
        for (TrafficBox cell : cells) check(km2(cell) <= TomTomClient.MAX_INCIDENT_KM2, "each cell under the limit: " + km2(cell));
        check(close(cells.get(0).getSouth(), BIG.getSouth()) && close(cells.get(0).getWest(), BIG.getWest())
            && close(cells.get(1).getEast(), BIG.getEast()) && close(cells.get(1).getSouth(), BIG.getSouth())
            && close(cells.get(2).getNorth(), BIG.getNorth()) && close(cells.get(2).getWest(), BIG.getWest())
            && close(cells.get(3).getNorth(), BIG.getNorth()) && close(cells.get(3).getEast(), BIG.getEast()),
            "rows south to north, columns west to east, covering the box");
        check(close(cells.get(0).getEast(), cells.get(1).getWest()) && close(cells.get(0).getNorth(), cells.get(2).getSouth()),
            "edge to edge");
        double cellArea = km2(cells.get(0));
        check(Math.abs(cellArea - km2(BIG) / 4) < km2(BIG) / 4 * 0.05, "equal cells: " + cellArea);
        // 12,364 km² measured at the equator, 7,948 at 50 degrees: only the equator splits it.
        TrafficBox straddling = new TrafficBox(-50.0, 10.0, 50.0, 10.01);
        check(TomTomClient.Companion.incidentBoxes(straddling).size() == 4, "a box across the equator is measured at the equator");

        long[] clock = {SEPTEMBER};
        FakeHttp http = new FakeHttp();
        http.tile = tile();
        MemoryTrafficLedger incidents = ledger();
        MemoryTrafficLedger tiles = ledger();
        int tileCount = TomTomTiles.INSTANCE.covering(BIG, TomTomClient.FLOW_ZOOM).size();
        TrafficSnapshot snap = fetched(client(tomTom(true, KEY, 2250), incidents, tiles, http, clock).fetch(BIG));
        check(http.tileRequests() == tileCount && http.incidentRequests() == 4 && incidents.status().getRequests() == 4,
            "every tile then the four incident cells: " + http.tileRequests() + "/" + http.incidentRequests());
        for (int j = 0; j < 4; j++) {
            check(http.urls.get(tileCount + j).contains("?bbox=" + TomTomClient.Companion.query(cells.get(j)) + "&timeValidity=present"),
                "cell " + j + " in order");
        }
        check(snap.getIncidents().size() == 12 && snap.getBox().equals(BIG), "each cell's incidents, under the whole box");
        FakeHttp short3 = new FakeHttp();
        MemoryTrafficLedger three = new MemoryTrafficLedger(new TrafficStatus("2026-09", 2250 - 3, 0L, null));
        check(skipped(client(tomTom(true, KEY, 2250), three, ledger(), short3, clock).fetch(BIG)).getWhy() == TrafficSkip.LIMIT
            && short3.urls.isEmpty(), "room for three cells of four: nothing sent");
    }

    // ---- 9. combining providers -------------------------------------------------------

    private static final class FakeClient implements TrafficProviderClient {
        final TrafficProvider provider;
        TrafficFetch answer;
        RuntimeException throwWith;
        TrafficCheck check = new TrafficCheck(true, "works");
        int fetches;
        int tests;

        FakeClient(TrafficProvider provider) {
            this.provider = provider;
        }

        @Override
        public TrafficProvider getProvider() {
            return provider;
        }

        @Override
        public TrafficFetch fetch(TrafficBox box) {
            fetches++;
            if (throwWith != null) throw throwWith;
            return answer;
        }

        @Override
        public TrafficCheck test() {
            tests++;
            return check;
        }
    }

    private static FlowStretch stretch(double lat) {
        return new FlowStretch(null, 100.0, new double[] {lat, lat + 0.001}, new double[] {-96.8, -96.8}, 1,
            List.of(new FlowSpan(0.0, 100.0, 5.0, 25.0, 8.0, Traversability.OPEN)));
    }

    private static TrafficIncident incident(String id) {
        return new TrafficIncident(id, "roadClosed", null, true, null, new double[] {32.78}, new double[] {-96.8});
    }

    private static TrafficFetch.Fetched snapshot(TrafficProvider p, long at, FlowStretch flow, TrafficIncident incident) {
        return new TrafficFetch.Fetched(new TrafficSnapshot(DALLAS, at, List.of(flow), List.of(incident), EnumSet.of(p)));
    }

    private static TrafficSources sources(TrafficSettings[] s, FakeClient here, FakeClient tomTom) {
        Map<TrafficProvider, TrafficProviderClient> clients = new EnumMap<>(TrafficProvider.class);
        clients.put(TrafficProvider.HERE, here);
        clients.put(TrafficProvider.TOMTOM, tomTom);
        return new TrafficSources(() -> s[0], clients);
    }

    private static void sourcesCombineAndFailOver() {
        FlowStretch hereFlow = stretch(32.78);
        FlowStretch tomTomFlow = stretch(32.79);
        TrafficIncident hereIncident = incident("here-1");
        TrafficIncident tomTomIncident = incident("tt-1");
        TrafficFetch.Fetched hereGot = snapshot(TrafficProvider.HERE, 10L, hereFlow, hereIncident);
        TrafficFetch.Fetched tomTomGot = snapshot(TrafficProvider.TOMTOM, 20L, tomTomFlow, tomTomIncident);
        TrafficFetch.Failed broken = new TrafficFetch.Failed("HERE 500: no explanation given", 500, TrafficFailure.HTTP);
        TrafficFetch.Failed down = new TrafficFetch.Failed("Could not reach TomTom: IOException", null, TrafficFailure.NETWORK);
        TrafficFetch.Skipped limit = new TrafficFetch.Skipped(TrafficSkip.LIMIT, "Limit reached until 1 October");
        TrafficFetch.Skipped noKey = new TrafficFetch.Skipped(TrafficSkip.NO_KEY, "No HERE API key is saved");
        TrafficFetch.Skipped noTomTomKey = new TrafficFetch.Skipped(TrafficSkip.NO_KEY, "No TomTom API key is saved");
        TrafficSettings[] s = {both(true, HERE_KEY, KEY, TrafficSelection.HERE_TOMTOM, TrafficTandem.COMBINED)};
        FakeClient here = new FakeClient(TrafficProvider.HERE);
        FakeClient tomTom = new FakeClient(TrafficProvider.TOMTOM);
        TrafficSources sources = sources(s, here, tomTom);

        // Combined, both answer: the preferred one's flow, everyone's incidents.
        here.answer = hereGot;
        tomTom.answer = tomTomGot;
        TrafficSnapshot combined = fetched(sources.fetch(DALLAS));
        check(here.fetches == 1 && tomTom.fetches == 1, "combined asks both");
        check(combined.getFlow().size() == 1 && combined.getFlow().get(0) == hereFlow, "flow from the first provider only");
        check(combined.getIncidents().equals(List.of(hereIncident, tomTomIncident)), "incidents from both, in order");
        check(combined.getProviders().equals(EnumSet.of(TrafficProvider.HERE, TrafficProvider.TOMTOM)), "both credited");
        check(combined.getBox().equals(DALLAS) && combined.getReceivedAtMs() == 20L, "the box, at the later arrival");

        s[0] = both(true, HERE_KEY, KEY, TrafficSelection.TOMTOM_HERE, TrafficTandem.COMBINED);
        TrafficSnapshot reversed = fetched(sources.fetch(DALLAS));
        check(reversed.getFlow().get(0) == tomTomFlow && reversed.getIncidents().equals(List.of(tomTomIncident, hereIncident)),
            "TomTom first: TomTom's flow");

        // Combined, the first fails: the second's answer as it is.
        s[0] = both(true, HERE_KEY, KEY, TrafficSelection.HERE_TOMTOM, TrafficTandem.COMBINED);
        here.answer = broken;
        check(fetched(sources.fetch(DALLAS)) == tomTomGot.getSnapshot(), "the first failed: the second's snapshot");

        // Failover: the second only when the first cannot answer.
        s[0] = both(true, HERE_KEY, KEY, TrafficSelection.HERE_TOMTOM, TrafficTandem.FAILOVER);
        here.answer = hereGot;
        here.fetches = 0;
        tomTom.fetches = 0;
        check(fetched(sources.fetch(DALLAS)) == hereGot.getSnapshot() && here.fetches == 1 && tomTom.fetches == 0,
            "failover, the first answered: the second never asked");
        here.answer = limit;
        check(fetched(sources.fetch(DALLAS)) == tomTomGot.getSnapshot() && tomTom.fetches == 1, "failover, the first at its limit: the second asked");
        here.answer = broken;
        check(fetched(sources.fetch(DALLAS)) == tomTomGot.getSnapshot() && tomTom.fetches == 2, "failover, the first failed: the second asked");

        // Nothing answers: a failure over a limit over a missing key.
        s[0] = both(true, HERE_KEY, KEY, TrafficSelection.HERE_TOMTOM, TrafficTandem.COMBINED);
        here.answer = noKey;
        tomTom.answer = down;
        check(sources.fetch(DALLAS) == down, "a failure, so the feed retries");
        here.answer = broken;
        check(sources.fetch(DALLAS) == broken, "the first failure");
        here.answer = noKey;
        tomTom.answer = limit;
        check(sources.fetch(DALLAS) == limit, "a limit over a missing key, so the feed waits");
        here.answer = noKey;
        tomTom.answer = noTomTomKey;
        check(sources.fetch(DALLAS) == noKey, "otherwise the first");
        s[0] = both(true, HERE_KEY, KEY, TrafficSelection.HERE_TOMTOM, TrafficTandem.FAILOVER);
        here.answer = limit;
        tomTom.answer = down;
        check(sources.fetch(DALLAS) == down, "failover with nothing: the failure");

        // A client that throws is a failure: Failover moves on, Combined keeps the other's answer.
        here.throwWith = new IllegalStateException("boom");
        tomTom.answer = tomTomGot;
        tomTom.fetches = 0;
        check(fetched(sources.fetch(DALLAS)) == tomTomGot.getSnapshot() && tomTom.fetches == 1,
            "failover, the first threw: the second asked and its answer kept");
        s[0] = both(true, HERE_KEY, KEY, TrafficSelection.HERE_TOMTOM, TrafficTandem.COMBINED);
        check(fetched(sources.fetch(DALLAS)) == tomTomGot.getSnapshot(), "combined, the first threw: the second's snapshot");
        tomTom.throwWith = new IllegalArgumentException("bang");
        TrafficFetch.Failed threw = failed(sources.fetch(DALLAS));
        check(threw.getMessage().equals("traffic fetch threw IllegalStateException") && threw.getCause() == TrafficFailure.OTHER
            && threw.getHttpStatus() == null, "both threw: the first, in words without its message: " + threw.getMessage());
        here.throwWith = null;
        tomTom.throwWith = null;

        // Off: no client asked.
        s[0] = both(false, HERE_KEY, KEY, TrafficSelection.TOMTOM_HERE, TrafficTandem.COMBINED);
        here.fetches = 0;
        tomTom.fetches = 0;
        TrafficFetch.Skipped off = skipped(sources.fetch(DALLAS));
        check(off.getWhy() == TrafficSkip.OFF && off.getMessage().equals("Live traffic is off") && here.fetches == 0 && tomTom.fetches == 0,
            "off: skipped, no client asked");

        // One provider chosen: only it is asked.
        s[0] = both(true, HERE_KEY, KEY, TrafficSelection.TOMTOM, TrafficTandem.COMBINED);
        tomTom.answer = tomTomGot;
        check(fetched(sources.fetch(DALLAS)) == tomTomGot.getSnapshot() && here.fetches == 0, "TomTom alone: HERE never asked");

        // Test.
        s[0] = both(true, HERE_KEY, KEY, TrafficSelection.HERE_TOMTOM, TrafficTandem.FAILOVER);
        TrafficCheck t = sources.test();
        check(t.getWorks() && t.getMessage().equals("HERE: works; TomTom: works"), "both tested: " + t.getMessage());
        tomTom.check = new TrafficCheck(false, "TomTom 403: Invalid API key (FORBIDDEN)");
        t = sources.test();
        check(!t.getWorks() && t.getMessage().equals("HERE: works; TomTom: TomTom 403: Invalid API key (FORBIDDEN)"), "one fails: " + t.getMessage());
        s[0] = both(true, HERE_KEY, "", TrafficSelection.TOMTOM_HERE, TrafficTandem.COMBINED);
        here.tests = 0;
        tomTom.tests = 0;
        t = sources.test();
        check(t.getWorks() && t.getMessage().equals("HERE: works") && tomTom.tests == 0, "only the keyed one tested: " + t.getMessage());
        s[0] = both(true, HERE_KEY, KEY, TrafficSelection.TOMTOM, TrafficTandem.COMBINED);
        t = sources.test();
        check(!t.getWorks() && t.getMessage().equals("TomTom 403: Invalid API key (FORBIDDEN)") && here.tests == 1,
            "one provider chosen: its own Test, as it says it");
        s[0] = both(false, HERE_KEY, KEY, TrafficSelection.HERE_TOMTOM, TrafficTandem.COMBINED);
        t = sources.test();
        check(!t.getWorks() && t.getMessage().equals("Live traffic is off"), "off: " + t.getMessage());
        s[0] = both(true, "", "", TrafficSelection.HERE_TOMTOM, TrafficTandem.COMBINED);
        here.tests = 0;
        tomTom.tests = 0;
        t = sources.test();
        check(!t.getWorks() && t.getMessage().equals("No API key is saved") && here.tests == 0 && tomTom.tests == 0, "no key: " + t.getMessage());
    }

    // ---- 10. the credit ---------------------------------------------------------------

    private static void theCreditNamesWhoseDataIsShown() {
        check(TrafficDisplay.Companion.attribution(EnumSet.of(TrafficProvider.HERE)).equals("Traffic \u00a9 HERE"), "HERE's credit");
        check(TrafficDisplay.Companion.attribution(EnumSet.of(TrafficProvider.TOMTOM)).equals("Traffic \u00a9 TomTom"), "TomTom's credit");
        Set<TrafficProvider> both = new LinkedHashSet<>();
        both.add(TrafficProvider.TOMTOM);
        both.add(TrafficProvider.HERE);
        check(TrafficDisplay.Companion.attribution(both).equals("Traffic \u00a9 HERE, TomTom"), "both, HERE first though TomTom came first");
        check(TrafficDisplay.LONGEST_ATTRIBUTION.equals(TrafficDisplay.Companion.attribution(both)), "the longest credit is both");
        check(new TrafficDisplay(null, 0, null, false, EnumSet.of(TrafficProvider.TOMTOM)).getAttribution().equals("Traffic \u00a9 TomTom"),
            "a display credits its own providers");
        check(new TrafficDisplay(null, 0, null).getAttribution().equals("Traffic \u00a9 HERE"), "HERE by default");
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= EPS;
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("TomTomTrafficTest: " + what);
        passed++;
    }
}
