package org.breezyweather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.breezyweather.standin.Scenarios;

/**
 * Breezy's weather provider as the app sees it (WeatherContentProvider at d587328a, the
 * data-sharing library at 628de1cf): the same authority, paths, columns, selection parsing
 * and gzipped JSON, served from the current scenario's files (Scenarios) instead of
 * Breezy's database. The unit query parameters and withMinutely/withNormals are ignored:
 * scenarios are written in the units and with the fields the lab wants.
 *
 * <p>Every query is logged under the tag BreezyStandin as "query version n=1", "query
 * locations n=2" or "query weather CURRENT_POSITION n=3" (a running count per kind since
 * the process started), which the lab greps: with Show weather off there must be none.
 */
public final class WeatherProvider extends ContentProvider {
    static final String TAG = "BreezyStandin";
    static final String AUTHORITY = "org.breezyweather.provider.weather";
    private static final int VERSION = 0;
    private static final int LOCATIONS = 1;
    private static final int WEATHER = 2;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "version", VERSION);
        MATCHER.addURI(AUTHORITY, "locations", LOCATIONS);
        MATCHER.addURI(AUTHORITY, "weather", WEATHER);
    }

    private final AtomicInteger versions = new AtomicInteger();
    private final AtomicInteger locationQueries = new AtomicInteger();
    private final AtomicInteger weatherQueries = new AtomicInteger();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String scenario = ScenarioReceiver.current(getContext());
        try {
            switch (MATCHER.match(uri)) {
                case VERSION:
                    Log.i(TAG, "query version n=" + versions.incrementAndGet());
                    return version(scenario);
                case LOCATIONS:
                    Log.i(TAG, "query locations n=" + locationQueries.incrementAndGet());
                    return locations(scenario, uri.getQueryParameter("limit"));
                case WEATHER:
                    String id = Scenarios.selectedId(selection);
                    Log.i(TAG, "query weather " + (id == null ? "unmatched" : id) + " n=" + weatherQueries.incrementAndGet());
                    return weather(scenario, id);
                default:
                    // Breezy logs and returns null for any other path.
                    Log.w(TAG, "unrecognized URI " + uri);
                    return null;
            }
        } catch (IOException | RuntimeException e) {
            // A broken scenario file: loud in the log, and a null cursor to the app.
            Log.e(TAG, "scenario " + scenario + " unreadable", e);
            return null;
        }
    }

    private Cursor version(String scenario) throws IOException {
        int[] v = Scenarios.version(asset(Scenarios.versionPath(scenario)));
        MatrixCursor cursor = new MatrixCursor(new String[] {"major", "minor"});
        cursor.addRow(new Object[] {v[0], v[1]});
        return cursor;
    }

    private Cursor locations(String scenario, String limitParameter) throws IOException {
        List<Map<String, Object>> locations = Scenarios.locations(asset(Scenarios.locationsPath(scenario)));
        // Breezy: a positive limit takes the first N, anything else (or none) takes all.
        int limit = locations.size();
        try {
            if (limitParameter != null && Integer.parseInt(limitParameter) > 0) {
                limit = Math.min(limit, Integer.parseInt(limitParameter));
            }
        } catch (NumberFormatException e) {
            // Breezy's toIntOrNull(): not a number means no limit.
        }
        MatrixCursor cursor = new MatrixCursor(Scenarios.COLUMNS.toArray(new String[0]));
        for (Map<String, Object> location : locations.subList(0, limit)) cursor.addRow(row(location, null));
        return cursor;
    }

    private Cursor weather(String scenario, String id) throws IOException {
        MatrixCursor cursor = new MatrixCursor(Scenarios.COLUMNS.toArray(new String[0]));
        if (id == null) return cursor;
        List<Map<String, Object>> locations = Scenarios.locations(asset(Scenarios.locationsPath(scenario)));
        int index = Scenarios.indexOf(locations, id);
        if (index < 0) {
            Log.w(TAG, "no location " + id + " in scenario " + scenario);
            return cursor;
        }
        Map<String, Object> location = locations.get(index);
        String json;
        try {
            json = Scenarios.resolve(asset(Scenarios.weatherPath(scenario, index)), System.currentTimeMillis(), zone(location));
        } catch (FileNotFoundException e) {
            // Breezy with a location but no weather encodes a null BreezyWeather.
            json = "null";
        }
        cursor.addRow(row(location, Scenarios.gzip(json)));
        return cursor;
    }

    /** One cursor row in Breezy's column order and types: doubles, an int flag, strings. */
    private static Object[] row(Map<String, Object> location, byte[] weather) {
        Object[] row = new Object[Scenarios.COLUMNS.size()];
        for (int i = 0; i < row.length; i++) {
            String column = Scenarios.COLUMNS.get(i);
            Object value = location.get(column);
            if (column.equals("latitude") || column.equals("longitude")) {
                row[i] = ((Number) value).doubleValue();
            } else if (column.equals("is_current_position")) {
                row[i] = ((Number) value).intValue();
            } else if (column.equals("weather")) {
                row[i] = weather;
            } else {
                row[i] = value == null ? null : value.toString();
            }
        }
        return row;
    }

    private static ZoneId zone(Map<String, Object> location) {
        Object tz = location.get("timezone");
        try {
            return tz == null ? ZoneId.systemDefault() : ZoneId.of(tz.toString());
        } catch (DateTimeException e) {
            return ZoneId.systemDefault();
        }
    }

    private String asset(String path) throws IOException {
        Context context = getContext();
        try (InputStream in = context.getAssets().open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // Read-only, as Breezy's is.
    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
