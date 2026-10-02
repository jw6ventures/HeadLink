package org.breezyweather.standin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * The stand-in Breezy's scenarios (P10 plan §8), shared by the stand-in's provider and the
 * host tests. Plain Java with no android imports: tools/jni-host-test compiles this same
 * file, so the app's parser is tested against exactly the bytes the lab serves.
 *
 * <p>Layout, one directory per scenario under tools/breezy-standin/scenarios/ (packaged
 * as the APK's assets under scenarios/, so the relative paths below work for both):
 *
 * <pre>
 *   scenarios/&lt;name&gt;/version.json      {"major": 0, "minor": 2}
 *   scenarios/&lt;name&gt;/locations.json    [ {one object per location: Breezy's columns by
 *                                           name, all but "weather", which the locations
 *                                           cursor always leaves null} ]
 *   scenarios/&lt;name&gt;/weather-&lt;i&gt;.json   the BreezyWeather JSON for locations[i], as Breezy's
 *                                        kotlinx encoding writes it; no file means Breezy
 *                                        has no weather for it (it then serves "null")
 * </pre>
 *
 * <p>Location ids are in Breezy's own format: {@code CURRENT_POSITION}, or its
 * {@code String.format(Locale.US, "%f&%f&%s", lat, lon, forecastSource)}, e.g.
 * {@code 43.020600&-71.600300&openmeteo}.
 *
 * <p>Times in a weather file are written relative to the query, since fixed blobs go stale:
 * a JSON string that is exactly one of these tokens is replaced by an epoch-millisecond
 * number by {@link #resolve}:
 *
 * <pre>
 *   "@now"  "@now-240m"  "@now+90m"   now, plus or minus whole minutes
 *   "@hour+3"  "@hour-2"              the start of the current hour in the location's zone, plus N hours
 *   "@day+0"  "@day+1"                local midnight today in the location's zone, plus N days
 * </pre>
 *
 * <p>A host test does what the provider does: read {@code weather-<i>.json}, resolve it with
 * a fixed now and the location's "timezone", and either parse the text or gzip it with
 * {@link #gzip} for the blob path. {@link #parse} is a small JSON reader for version.json and
 * locations.json, since neither side has a JSON library it may use here.
 */
public final class Scenarios {
    /** What the provider serves until the lab picks a scenario. */
    public static final String DEFAULT = "show";

    /** Breezy's locations and weather cursor columns, in its order (ProviderLocation). */
    public static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "id", "latitude", "longitude", "is_current_position", "timezone", "custom_name",
            "country", "country_code", "admin1", "admin1_code", "admin2", "admin2_code",
            "admin3", "admin3_code", "admin4", "admin4_code", "city", "district", "weather"));

    /** Breezy's Location.CURRENT_POSITION_ID. */
    public static final String CURRENT_POSITION = "CURRENT_POSITION";

    // Breezy's own selection parsing (WeatherContentProvider.queryWeather), tried in this
    // order. find(), not matches(): Breezy's Regex.find accepts the pattern anywhere.
    private static final Pattern SELECTION_ID = Pattern.compile("id *= *(-?[0-9.]+)&(-?[0-9.]+)&([a-z]+)");
    private static final Pattern SELECTION_CURRENT = Pattern.compile("id *= *" + CURRENT_POSITION);

    private static final Pattern TOKEN = Pattern.compile("\"@([^\"\\\\]*)\"");
    private static final Pattern NOW = Pattern.compile("now(?:([+-][0-9]+)m)?");
    private static final Pattern HOUR = Pattern.compile("hour([+-][0-9]+)?");
    private static final Pattern DAY = Pattern.compile("day([+-][0-9]+)?");

    private Scenarios() {
    }

    public static String versionPath(String scenario) {
        return "scenarios/" + scenario + "/version.json";
    }

    public static String locationsPath(String scenario) {
        return "scenarios/" + scenario + "/locations.json";
    }

    public static String weatherPath(String scenario, int index) {
        return "scenarios/" + scenario + "/weather-" + index + ".json";
    }

    /**
     * {@code json} with every time token replaced by epoch milliseconds, relative to
     * {@code nowMillis} and, for hours and days, local to {@code zone}. Any other string
     * that starts with "@" is refused, so a mistyped token fails loudly instead of reaching
     * the app as a string.
     */
    public static String resolve(String json, long nowMillis, ZoneId zone) {
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(zone);
        Matcher m = TOKEN.matcher(json);
        StringBuilder out = new StringBuilder(json.length());
        int last = 0;
        while (m.find()) {
            String token = m.group(1);
            long value;
            Matcher t;
            if ((t = NOW.matcher(token)).matches()) {
                value = nowMillis + offset(t.group(1)) * 60_000L;
            } else if ((t = HOUR.matcher(token)).matches()) {
                value = now.truncatedTo(ChronoUnit.HOURS).plusHours(offset(t.group(1))).toInstant().toEpochMilli();
            } else if ((t = DAY.matcher(token)).matches()) {
                value = now.toLocalDate().plusDays(offset(t.group(1))).atStartOfDay(zone).toInstant().toEpochMilli();
            } else {
                throw new IllegalArgumentException("unknown time token \"@" + token + "\"");
            }
            out.append(json, last, m.start()).append(value);
            last = m.end();
        }
        return out.append(json, last, json.length()).toString();
    }

    private static long offset(String signed) {
        return signed == null ? 0 : Long.parseLong(signed.startsWith("+") ? signed.substring(1) : signed);
    }

    /** {@code json} gzipped as UTF-8, as Breezy's String.gzipCompress() does. */
    public static byte[] gzip(String json) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(bytes), StandardCharsets.UTF_8)) {
            writer.write(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * The location id a weather query's selection names, parsed with Breezy's regexes, or
     * null when neither matches (Breezy then returns an empty cursor).
     */
    public static String selectedId(String selection) {
        if (selection == null) return null;
        Matcher m = SELECTION_ID.matcher(selection);
        if (m.find()) return m.group(1) + "&" + m.group(2) + "&" + m.group(3);
        return SELECTION_CURRENT.matcher(selection).find() ? CURRENT_POSITION : null;
    }

    /** The index in {@code locations} whose "id" is {@code id}, or -1. */
    public static int indexOf(List<Map<String, Object>> locations, String id) {
        for (int i = 0; i < locations.size(); i++) {
            if (id != null && id.equals(locations.get(i).get("id"))) return i;
        }
        return -1;
    }

    /** version.json as {major, minor}. */
    public static int[] version(String json) {
        Map<String, Object> v = object(parse(json));
        return new int[] {((Number) v.get("major")).intValue(), ((Number) v.get("minor")).intValue()};
    }

    /** locations.json as one field map per location, in file order. */
    public static List<Map<String, Object>> locations(String json) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object parsed = parse(json);
        if (!(parsed instanceof List)) throw new IllegalArgumentException("locations.json is not an array");
        for (Object item : (List<?>) parsed) out.add(object(item));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) value;
    }

    /**
     * A small strict JSON reader: objects become LinkedHashMaps, arrays ArrayLists, numbers
     * Long when written without a fraction or exponent and Double otherwise, and null null.
     */
    public static Object parse(String json) {
        Reader r = new Reader(json);
        Object value = r.value();
        r.space();
        if (r.pos != json.length()) throw r.error("trailing text");
        return value;
    }

    private static final class Reader {
        final String s;
        int pos;

        Reader(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException("JSON: " + what + " at " + pos);
        }

        void space() {
            while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) pos++;
        }

        char peek() {
            space();
            if (pos >= s.length()) throw error("unexpected end");
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) throw error("expected '" + c + "'");
            pos++;
        }

        boolean word(String w) {
            if (!s.startsWith(w, pos)) return false;
            pos += w.length();
            return true;
        }

        Object value() {
            char c = peek();
            if (c == '{') {
                pos++;
                Map<String, Object> map = new LinkedHashMap<>();
                if (peek() == '}') {
                    pos++;
                    return map;
                }
                do {
                    if (peek() != '"') throw error("expected a field name");
                    String key = string();
                    expect(':');
                    map.put(key, value());
                } while (comma('}'));
                return map;
            }
            if (c == '[') {
                pos++;
                List<Object> list = new ArrayList<>();
                if (peek() == ']') {
                    pos++;
                    return list;
                }
                do {
                    list.add(value());
                } while (comma(']'));
                return list;
            }
            if (c == '"') return string();
            if (word("true")) return Boolean.TRUE;
            if (word("false")) return Boolean.FALSE;
            if (word("null")) return null;
            return number();
        }

        /** After an item: true for a comma, false for the closing bracket. */
        boolean comma(char close) {
            char c = peek();
            pos++;
            if (c == ',') return true;
            if (c == close) return false;
            pos--;
            throw error("expected ',' or '" + close + "'");
        }

        String string() {
            pos++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw error("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (pos >= s.length()) throw error("unterminated escape");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"': case '\\': case '/': b.append(e); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) throw error("short \\u escape");
                        b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: throw error("bad escape");
                }
            }
        }

        Object number() {
            int start = pos;
            boolean integral = true;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                if (".eE".indexOf(s.charAt(pos)) >= 0) integral = false;
                pos++;
            }
            if (start == pos) throw error("unexpected character");
            String text = s.substring(start, pos);
            try {
                if (integral) return Long.valueOf(text);
                return Double.valueOf(text);
            } catch (NumberFormatException e) {
                pos = start;
                throw error("bad number " + text);
            }
        }
    }
}
