// P10's weather on a desktop JVM (docs/dashboard.md, "Weather"): Breezy's gzipped JSON read into
// WeatherData (every field, every optional field missing, unknown fields, a blob that is not
// gzip, JSON of the wrong shape), the location chosen, the stale and far labels at their
// limits, alert filtering and order, the provider-owner check, the read loop (nothing read
// with weather off, one read at a time, a refused provider, an unsupported major version,
// each alert asked to be spoken once), the flex tile's order with every source, the header
// item's box at 1080p, Ford's 726x480 and 800x480, the view opened and closed by a tap, a tap
// on it, the 30 s timeout and a call, the alert dismissed and a new id shown again, the
// alert speech rules, and what the report may carry. The fixtures are hand-written in the
// shape of Breezy's source (provider 0.2), not captured from a phone: the field
// names are unconfirmed until P10.0 reads a real blob. ContentResolver and the drawing need
// a phone or the lab.
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import com.jw6ventures.headlink.AlertLevel;
import com.jw6ventures.headlink.Box;
import com.jw6ventures.headlink.CallPhase;
import com.jw6ventures.headlink.CallSource;
import com.jw6ventures.headlink.CallState;
import com.jw6ventures.headlink.ClockFormat;
import com.jw6ventures.headlink.Dashboard;
import com.jw6ventures.headlink.DashboardChoices;
import com.jw6ventures.headlink.DashboardLayout;
import com.jw6ventures.headlink.DayNightMode;
import com.jw6ventures.headlink.FlexRules;
import com.jw6ventures.headlink.FlexSource;
import com.jw6ventures.headlink.MapOrientation;
import com.jw6ventures.headlink.MarkerColour;
import com.jw6ventures.headlink.PhoneSnapshot;
import com.jw6ventures.headlink.SpeechAction;
import com.jw6ventures.headlink.SpeechConditions;
import com.jw6ventures.headlink.SpeechQueue;
import com.jw6ventures.headlink.SpeechSkip;
import com.jw6ventures.headlink.SpeedUnit;
import com.jw6ventures.headlink.WeatherAlert;
import com.jw6ventures.headlink.WeatherCode;
import com.jw6ventures.headlink.WeatherCue;
import com.jw6ventures.headlink.WeatherData;
import com.jw6ventures.headlink.WeatherFeed;
import com.jw6ventures.headlink.WeatherJson;
import com.jw6ventures.headlink.WeatherPlace;
import com.jw6ventures.headlink.WeatherReadException;
import com.jw6ventures.headlink.WeatherReading;
import com.jw6ventures.headlink.WeatherRules;
import com.jw6ventures.headlink.WeatherSettings;
import com.jw6ventures.headlink.WeatherSource;
import com.jw6ventures.headlink.WeatherState;
import com.jw6ventures.headlink.WeatherStatus;
import com.jw6ventures.headlink.WeatherText;
import kotlin.Pair;

public final class WeatherTest {
    private static int passed;

    private static final long NOON = Instant.parse("2026-09-28T17:00:00Z").toEpochMilli();
    private static final long MIN = 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final String HEADLINE = "Severe Thunderstorm Warning until 9:45 PM CDT";
    private static final String PLACE = "Placemarker Town";
    private static final Pair<Double, Double> LEXINGTON = new Pair<>(42.4495, -71.2310);

    public static void main(String[] args) throws Exception {
        everyFieldIsRead();
        missingFieldsAreNull();
        unknownFieldsAreSkipped();
        badBlobsAreUnreadable();
        theLocationIsChosen();
        theLabelsMeetTheirLimits();
        alertsAreFilteredAndOrdered();
        onlyBreezyOwnsTheProvider();
        offMeansNoReadAtAll();
        theFeedReadsAndRefuses();
        oneReadAtATime();
        eachAlertIsAskedForOnce();
        theTileOrder();
        for (DashboardLayout layout : layouts()) theHeaderItemFits(layout);
        theViewOpensAndCloses();
        aTapDuringACallDoesNothing();
        theAlertTakesTheTileAndIsDismissed();
        theViewsSayWhereAndHowOld();
        alertsAreSpokenByTheRules();
        theReportsHoldCountsOnly();
        if (args.length > 0 && !args[0].isEmpty()) theLabScenariosParse(Path.of(args[0]));
        else System.out.println("weather-test: lab scenarios not given; skipped");
        System.out.println("jni-host-test: ok " + passed + " weather checks");
    }

    // --- Fixtures, in Breezy's shape ------------------------------------------------------

    private static String unit(double value, String unit) {
        return "{\"value\":" + value + ",\"unit\":\"" + unit + "\"}";
    }

    private static String hour(long date, boolean daylight, String code, double temp, double rain) {
        return "{\"date\":" + date + (daylight ? "" : ",\"isDaylight\":false") + ",\"weatherText\":\"x\",\"weatherCode\":\"" + code + "\"," +
            "\"temperature\":{\"temperature\":" + unit(temp, "f") + ",\"computedApparent\":null}," +
            "\"precipitation\":{\"total\":" + unit(0.1, "in") + "}," +
            "\"precipitationProbability\":{\"total\":" + unit(rain, "percent") + ",\"thunderstorm\":null}," +
            "\"wind\":{\"degree\":180.0,\"speed\":" + unit(5, "mph") + "},\"uV\":" + unit(3, "uvi") + "}";
    }

    private static String alert(String id, long start, long end, int severity, String colour, String headline) {
        return "{\"alertId\":\"" + id + "\",\"startDate\":" + start + ",\"endDate\":" + end + ",\"headline\":\"" + headline + "\"," +
            "\"description\":\"Descriptionmarker text never read\",\"instruction\":\"Instructionmarker text\",\"source\":\"NWS\"" +
            (severity == 0 ? "" : ",\"severity\":" + severity) + (colour == null ? "" : ",\"color\":\"" + colour + "\"") + "}";
    }

    /** A full blob at [now]: refreshed 30 min ago, current, today and tomorrow, hours and alerts. */
    private static String full(long now, String alerts) {
        long hour0 = now - now % HOUR;
        long day0 = now - now % (24 * HOUR);
        StringBuilder hours = new StringBuilder();
        String[] codes = {"partly_cloudy", "cloudy", "rain", "thunderstorm", "clear", "CLEAR", "snow", "fog", "haze", "sleet", "hail", "wind", "thunder"};
        for (int i = -3; i < codes.length - 3; i++) {
            if (hours.length() > 0) hours.append(',');
            hours.append(hour(hour0 + i * HOUR, i < 5, codes[i + 3], 70 + i, i == 1 ? 40 : i == 2 ? 19.6 : 10));
        }
        return "{\"refreshTime\":" + (now - 30 * MIN) + ",\"bulletin\":{\"dailySummary\":\"x\"}," +
            "\"current\":{\"weatherText\":\"  Partly cloudy \",\"weatherCode\":\"partly_cloudy\"," +
            "\"temperature\":{\"temperature\":" + unit(78, "f") + ",\"sourceFeelsLike\":" + unit(84.4, "f") + ",\"computedApparent\":" + unit(80, "f") + "}," +
            "\"wind\":{\"degree\":200.0,\"speed\":" + unit(9, "mph") + ",\"gusts\":" + unit(15, "mph") + "}," +
            "\"relativeHumidity\":" + unit(60, "percent") + ",\"airQuality\":{\"index\":" + unit(42, "aqi") + "}}," +
            "\"daily\":[{\"date\":" + (day0 - 24 * HOUR) + ",\"day\":{\"temperature\":{\"temperature\":" + unit(60, "f") + "}}}," +
            "{\"date\":" + day0 + ",\"day\":{\"weatherCode\":\"clear\",\"temperature\":{\"temperature\":" + unit(91, "f") + "}}," +
            "\"night\":{\"temperature\":{\"temperature\":" + unit(74, "f") + "}},\"pollen\":{\"grass\":{\"index\":" + unit(1, "x") + "}}}," +
            "{\"date\":" + (day0 + 24 * HOUR) + ",\"day\":{\"temperature\":{\"temperature\":" + unit(88, "f") + "}}}]," +
            "\"hourly\":[" + hours + "],\"minutely\":null," +
            "\"alerts\":[" + alerts + "]," +
            "\"sources\":{\"forecast\":{\"id\":\"openmeteo\",\"name\":\"Open-Meteo\"}}}";
    }

    private static String standardAlerts(long now) {
        return alert("expired", now - 3 * HOUR, now - HOUR, 3, "#ff0000", "Old warning") + "," +
            alert("minor", now - HOUR, now + HOUR, 1, "#FFFF00", "Minor advisory") + "," +
            alert("future", now + HOUR, now + 2 * HOUR, 4, "#800080", "Not yet") + "," +
            alert("severe-late", now - 10 * MIN, now + 3 * HOUR, 3, "#ff8c00", "Later severe") + "," +
            alert("severe-soon", now - 10 * MIN, now + 75 * MIN, 3, "ff8c00", HEADLINE) + "," +
            alert("extreme", now - 5 * MIN, now + 4 * HOUR, 4, "not a colour", "Tornado Warning") + "," +
            alert("unknown", now - 5 * MIN, now + 4 * HOUR, 0, null, "Something");
    }

    private static byte[] gzip(String json) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static WeatherData parse(String json, long now) {
        try {
            return WeatherJson.INSTANCE.parse(gzip(json), now);
        } catch (WeatherReadException e) {
            throw new AssertionError("a fixture did not parse", e);
        }
    }

    // --- The parser -----------------------------------------------------------------------

    private static void everyFieldIsRead() {
        WeatherData d = parse(full(NOON, standardAlerts(NOON)), NOON + 20 * MIN);
        check(d.getRefreshMillis() == NOON - 30 * MIN, "refreshTime in ms");
        check("Partly cloudy".equals(d.getText()), "weatherText, trimmed");
        check(d.getCode() == WeatherCode.PARTLY_CLOUDY, "weatherCode");
        check(d.getTemperature().getValue() == 78 && "f".equals(d.getTemperature().getUnit()), "temperature and its tag");
        check(d.getFeelsLike().getValue() == 84.4, "sourceFeelsLike wins over computedApparent");
        check(d.getWind().getValue() == 9 && "mph".equals(d.getWind().getUnit()), "wind speed");
        check(d.getHigh().getValue() == 91 && d.getLow().getValue() == 74, "today's day and night, not yesterday's or tomorrow's");
        check(d.getDaylight(), "the current hour is daylight");
        check(d.getHours().size() == WeatherJson.HOURS_KEPT, "hours kept: " + d.getHours().size());
        check(d.getHours().get(0).getTimeMillis() == NOON, "the first hour kept is the one now falls in");
        check(d.getHours().get(1).getRain().getValue() == 40, "precipitationProbability.total");
        check(d.getHours().get(2).getCode() == WeatherCode.CLEAR, "a code's case ignored");
        check(d.getHours().get(4).getDaylight() && !d.getHours().get(5).getDaylight(), "isDaylight left out is day; false is read");
        check(d.getAlerts().size() == 7, "every alert kept for filtering");
        WeatherAlert soon = find(d.getAlerts(), "severe-soon");
        check(soon.getSeverity() == 3 && soon.getColour() == 0xFFFF8C00 && HEADLINE.equals(soon.getHeadline()), "an alert: severity, colour without #, headline");
        check(find(d.getAlerts(), "extreme").getColour() == null, "a colour that does not parse is null");
        check(find(d.getAlerts(), "unknown").getSeverity() == 0 && find(d.getAlerts(), "unknown").getColour() == null,
            "severity left out at its default 0");
        check(WeatherJson.INSTANCE.colour("#00ff0000") == 0xFFFF0000, "alpha 0 as Breezy formats it, made opaque");
        WeatherData night = parse(full(NOON, ""), NOON + 5 * HOUR + 5 * MIN);
        check(!night.getDaylight(), "the current hour's isDaylight false is night");
    }

    private static void missingFieldsAreNull() {
        WeatherData d = WeatherJson.INSTANCE.parse(new StringReader("{}"), NOON);
        check(d.getRefreshMillis() == null && d.getText() == null && d.getCode() == null && d.getTemperature() == null
            && d.getFeelsLike() == null && d.getWind() == null && d.getHigh() == null && d.getLow() == null, "an empty object: all null");
        check(d.getHours().isEmpty() && d.getAlerts().isEmpty() && d.getDaylight(), "no hours, no alerts, day");
        WeatherData nulls = WeatherJson.INSTANCE.parse(new StringReader(
            "{\"refreshTime\":null,\"current\":{\"temperature\":{\"temperature\":{\"value\":null,\"unit\":\"f\"},\"sourceFeelsLike\":null}," +
                "\"weatherCode\":\"volcano\"},\"daily\":[{\"date\":" + NOON + "}],\"hourly\":[{\"isDaylight\":false}]," +
                "\"alerts\":[{\"headline\":\"no id\"},{\"alertId\":\"a\",\"headline\":\"  \"}]}"), NOON);
        check(nulls.getTemperature() == null && nulls.getCode() == null, "a null value and an unknown code are null");
        check(nulls.getHigh() == null && nulls.getLow() == null, "a day with no halves");
        check(nulls.getHours().isEmpty(), "an hour with no date is dropped");
        check(nulls.getAlerts().size() == 1 && "Weather alert".equals(nulls.getAlerts().get(0).getHeadline())
            && nulls.getAlerts().get(0).getStartMillis() == null, "an alert with no id is dropped; a blank headline is named");
        check(WeatherText.INSTANCE.feels(nulls) == null && WeatherText.INSTANCE.highLow(nulls) == null, "no feels, no high or low");
    }

    private static void unknownFieldsAreSkipped() {
        WeatherData d = WeatherJson.INSTANCE.parse(new StringReader(
            "{\"new\":{\"deep\":[1,{\"x\":[true,null]}]},\"current\":{\"future\":[],\"temperature\":{\"temperature\":{\"value\":21.4,\"unit\":\"c\",\"color\":\"#fff\"}}}," +
                "\"normals\":{\"9\":{\"x\":1}},\"pollenLevels\":[{\"a\":1}]}"), NOON);
        check(d.getTemperature().getValue() == 21.4 && "21°C".equals(WeatherText.INSTANCE.temperature(d.getTemperature())), "read past what is unknown");
    }

    private static void badBlobsAreUnreadable() {
        unreadable(new byte[] {1, 2, 3}, "not gzip");
        unreadable(gzip("[1,2]"), "an array, not an object");
        unreadable(gzip("{\"current\":"), "cut short");
        unreadable(gzip("{} trailing"), "trailing text");
        unreadable(gzip("\"text\""), "a string");
        byte[] ok = gzip(full(NOON, ""));
        unreadable(java.util.Arrays.copyOf(ok, ok.length / 2), "a truncated gzip stream");
        try {
            WeatherJson.INSTANCE.parse(gzip("{\"hourly\":[{\"date\":\"x\"}],\"current\":{\"temperature\":7}}"), NOON);
            passed++;
        } catch (WeatherReadException e) {
            throw new AssertionError("values of the wrong type are skipped, not fatal");
        }
    }

    private static void unreadable(byte[] blob, String what) {
        try {
            WeatherJson.INSTANCE.parse(blob, NOON);
            throw new AssertionError(what + " parsed");
        } catch (WeatherReadException e) {
            check(e.getStatus() == WeatherStatus.UNREADABLE, what);
        }
    }

    // --- Rules and words ------------------------------------------------------------------

    private static WeatherPlace place(String id, double lat, double lon, boolean current, String name) {
        return new WeatherPlace(id, lat, lon, current, name);
    }

    private static void theLocationIsChosen() {
        WeatherPlace waco = place("31.5&-97.1&openmeteo", 31.5493, -97.1467, false, "Waco");
        WeatherPlace austin = place("30.2&-97.7&openmeteo", 30.2672, -97.7431, false, "Austin");
        WeatherPlace here = place("CURRENT_POSITION", 42.4, -71.2, true, "Lexington");
        check(WeatherRules.INSTANCE.choose(List.of(waco, here, austin), new Pair<>(30.3, -97.7)) == here, "current position first, however far");
        check(WeatherRules.INSTANCE.choose(List.of(waco, austin), new Pair<>(30.3, -97.7)) == austin, "else the nearest to the fix");
        check(WeatherRules.INSTANCE.choose(List.of(waco, austin), new Pair<>(31.6, -97.2)) == waco, "nearest, the other way");
        check(WeatherRules.INSTANCE.choose(List.of(waco, austin), null) == waco, "no fix: the first");
        check(WeatherRules.INSTANCE.choose(List.of(), LEXINGTON) == null, "no locations: none");
    }

    private static void theLabelsMeetTheirLimits() {
        WeatherData d = parse(full(NOON, ""), NOON);
        long refresh = NOON - 30 * MIN;
        check(!WeatherText.INSTANCE.stale(d, refresh + WeatherRules.STALE_MS), "exactly 3 h: not yet stale");
        check(WeatherText.INSTANCE.stale(d, refresh + WeatherRules.STALE_MS + 1), "past 3 h: stale");
        check("just now".equals(WeatherText.INSTANCE.age(refresh, refresh + 59_999)), "under a minute");
        check("1 min ago".equals(WeatherText.INSTANCE.age(refresh, refresh + MIN)), "a minute");
        check("59 min ago".equals(WeatherText.INSTANCE.age(refresh, refresh + 60 * MIN - 1)), "59 min");
        check("1 h ago".equals(WeatherText.INSTANCE.age(refresh, refresh + 60 * MIN)), "an hour");
        check("4 h ago".equals(WeatherText.INSTANCE.age(refresh, refresh + 4 * HOUR + 59 * MIN)), "whole hours");
        check("just now".equals(WeatherText.INSTANCE.age(refresh, refresh - HOUR)), "a clock behind the refresh");
        // 50 km: one degree of latitude is 111.2 km, so 0.4497° is about 50.0 km.
        WeatherPlace near = place("a", 42.4495 + 0.449, -71.2310, false, "Near");
        WeatherPlace far = place("b", 42.4495 + 0.4505, -71.2310, false, "Far");
        check(WeatherText.INSTANCE.farKm(near, LEXINGTON) == null, "just under 50 km: no label");
        check(WeatherText.INSTANCE.farKm(far, LEXINGTON) == 50, "just over 50 km: labelled");
        check(WeatherText.INSTANCE.farKm(far, null) == null, "no fix: no label");
        WeatherReading reading = new WeatherReading(place("c", 42.97, -71.46, false, PLACE), d, NOON);
        String line = WeatherText.INSTANCE.placeLine(reading, LEXINGTON, refresh + 4 * HOUR);
        check(line.equals("old · 61 km away · " + PLACE + " · 4 h ago"), "the stale, far line, flags first: " + line);
        check(WeatherText.INSTANCE.placeLine(new WeatherReading(place("d", 42.45, -71.23, true, ""), d, NOON), LEXINGTON, refresh + 12 * MIN)
            .equals("Weather · 12 min ago"), "a nameless place near by");
        check("78°F".equals(WeatherText.INSTANCE.temperature(d.getTemperature())) && "78°".equals(WeatherText.INSTANCE.shortTemperature(d.getTemperature())),
            "78°F and its short form");
        check("Feels 84°".equals(WeatherText.INSTANCE.feels(d)) && "H 91° L 74°".equals(WeatherText.INSTANCE.highLow(d)), "feels, high and low");
        check("40%".equals(WeatherText.INSTANCE.rain(d.getHours().get(1).getRain())) && "20%".equals(WeatherText.INSTANCE.rain(d.getHours().get(2).getRain()))
            && WeatherText.INSTANCE.rain(d.getHours().get(3).getRain()) == null, "rain from 20% up, 19.6 rounding to 20; 10% not");
        check("20%".equals(WeatherText.INSTANCE.rain(new com.jw6ventures.headlink.Tagged(19.6, "percent"))), "19.6 rounds to 20%");
        check("—".equals(WeatherText.INSTANCE.temperature(null)), "no reading: a dash");
        check("295 K".equals(WeatherText.INSTANCE.temperature(new com.jw6ventures.headlink.Tagged(295.2, "k"))), "Kelvin printed as tagged");
        check("20:00".equals(WeatherText.INSTANCE.hour(NOON + 3 * HOUR, UTC)), "an hour in the dashboard clock's style");
        check("8 PM".equals(WeatherText.INSTANCE.hour(NOON + 3 * HOUR, UTC, ClockFormat.H12)), "and in 12-hour style");
        check(WeatherText.INSTANCE.feels(parse(full(NOON, "").replace("84.4", "80.4"), NOON)) == null, "feels 2° off: not shown");
    }

    private static void alertsAreFilteredAndOrdered() {
        List<WeatherAlert> all = parse(full(NOON, standardAlerts(NOON)), NOON).getAlerts();
        List<String> severe = ids(WeatherRules.INSTANCE.qualifying(all, NOON, AlertLevel.SEVERE, java.util.Set.of()));
        check(severe.equals(List.of("extreme", "severe-soon", "severe-late")), "severe and up, most severe then ending soonest: " + severe);
        check(ids(WeatherRules.INSTANCE.qualifying(all, NOON, AlertLevel.MINOR, java.util.Set.of())).equals(List.of("extreme", "severe-soon", "severe-late", "minor")),
            "minor and up; unknown severity never qualifies");
        check(ids(WeatherRules.INSTANCE.qualifying(all, NOON, AlertLevel.SEVERE, java.util.Set.of("extreme"))).equals(List.of("severe-soon", "severe-late")),
            "a dismissed one is left out");
        check(ids(WeatherRules.INSTANCE.active(all, NOON)).equals(List.of("extreme", "severe-soon", "severe-late", "minor", "unknown")),
            "the strip's list: in force, any severity");
        check(ids(WeatherRules.INSTANCE.qualifying(all, NOON + HOUR, AlertLevel.EXTREME, java.util.Set.of())).equals(List.of("future", "extreme")),
            "one starts on the hour; the end is exclusive");
        check(WeatherRules.INSTANCE.qualifying(all, NOON + 5 * HOUR, AlertLevel.MINOR, java.util.Set.of()).isEmpty(), "all ended");
        check(AlertLevel.Companion.of("bogus") == AlertLevel.SEVERE && AlertLevel.Companion.of("minor") == AlertLevel.MINOR, "stored level keys");
    }

    private static void onlyBreezyOwnsTheProvider() {
        check(WeatherRules.INSTANCE.ownerAccepted("org.breezyweather", false), "Breezy");
        check(!WeatherRules.INSTANCE.ownerAccepted("org.breezyweather.debug", false), "Breezy's debug build refused in a release build");
        check(WeatherRules.INSTANCE.ownerAccepted("org.breezyweather.debug", true), "and accepted in a debug build");
        check(!WeatherRules.INSTANCE.ownerAccepted("com.evil.weather", true), "anyone else refused");
        check(!WeatherRules.INSTANCE.ownerAccepted(null, true), "nobody refused");
    }

    // --- The read loop --------------------------------------------------------------------

    /** A provider in memory; [calls] counts every question asked of it. */
    private static final class FakeSource implements WeatherSource {
        String owner = "org.breezyweather";
        boolean permitted = true;
        Pair<Integer, Integer> version = new Pair<>(0, 2);
        List<WeatherPlace> places = new ArrayList<>(List.of(place("CURRENT_POSITION", 42.45, -71.23, true, PLACE)));
        byte[] blob = gzip(full(NOON, standardAlerts(NOON)));
        RuntimeException throwOnBlob;
        int calls;
        final List<String> asked = new ArrayList<>();

        @Override public String owner() { calls++; return owner; }
        @Override public boolean permitted() { calls++; return permitted; }
        @Override public Pair<Integer, Integer> version() { calls++; return version; }
        @Override public List<WeatherPlace> places() { calls++; return places; }
        @Override public byte[] blob(String id) {
            calls++;
            asked.add(id);
            if (throwOnBlob != null) throw throwOnBlob;
            return blob;
        }
    }

    /** A feed whose worker and main thread are queues this test runs by hand. */
    private static final class Loop {
        final FakeSource source = new FakeSource();
        final List<Runnable> work = new ArrayList<>();
        final List<Runnable> main = new ArrayList<>();
        final List<WeatherState> states = new ArrayList<>();
        final List<String> spoken = new ArrayList<>();
        long now = NOON + 20 * MIN;
        final WeatherFeed feed = new WeatherFeed(source, false,
            r -> { work.add(r); return kotlin.Unit.INSTANCE; }, r -> { main.add(r); return kotlin.Unit.INSTANCE; }, () -> now);

        Loop() {
            feed.setListener(s -> { states.add(s); return kotlin.Unit.INSTANCE; });
            feed.setSpeak(a -> { spoken.add(a.getId()); return kotlin.Unit.INSTANCE; });
        }

        void run() {
            while (!work.isEmpty() || !main.isEmpty()) {
                if (!work.isEmpty()) work.remove(0).run();
                if (!main.isEmpty()) main.remove(0).run();
            }
        }

        WeatherState last() {
            return states.get(states.size() - 1);
        }
    }

    private static void offMeansNoReadAtAll() {
        Loop loop = new Loop();
        loop.feed.start(new WeatherSettings(false, true, AlertLevel.SEVERE));
        for (WeatherCue cue : WeatherCue.values()) loop.feed.cue(cue);
        loop.feed.tick();
        loop.run();
        check(loop.source.calls == 0 && loop.work.isEmpty(), "weather off: the provider is never asked anything");
        check(loop.last().getReading() == null, "and nothing is held");
        loop.feed.stop();
        loop.feed.cue(WeatherCue.NOTIFIER);
        check(loop.source.calls == 0, "and nothing after the projection either");
        check(loop.feed.report().contains("weather_enabled=false") && loop.feed.report().contains("weather_reads=0"), "counted as off");
    }

    private static void theFeedReadsAndRefuses() {
        Loop loop = new Loop();
        loop.feed.start(new WeatherSettings(true, true, AlertLevel.SEVERE));
        loop.run();
        WeatherState state = loop.last();
        check(state.getStatus() == WeatherStatus.OK && state.getReading() != null, "a read at the start");
        check(PLACE.equals(state.getReading().getPlace().getName()) && loop.source.asked.equals(List.of("CURRENT_POSITION")), "the current position asked for");
        // The refusals, each on a fresh read, in the order they are checked.
        loop.source.throwOnBlob = new IllegalStateException("gone");
        loop.feed.cue(WeatherCue.NOTIFIER);
        loop.run();
        check(loop.last().getStatus() == WeatherStatus.UNREADABLE && loop.last().getReading() != null, "a failed read keeps the last good reading");
        loop.source.throwOnBlob = new SecurityException("revoked");
        loop.feed.cue(WeatherCue.TIMER);
        loop.run();
        check(loop.last().getStatus() == WeatherStatus.NO_PERMISSION && loop.last().getReading() == null, "a revoked grant drops it");
        loop.source.throwOnBlob = null;
        loop.source.version = new Pair<>(1, 0);
        loop.feed.cue(WeatherCue.TIMER);
        loop.run();
        check(loop.last().getStatus() == WeatherStatus.UNSUPPORTED_MAJOR && loop.last().getMajor() == 1, "major 1 refused");
        check(WeatherText.INSTANCE.status(loop.last(), NOON).equals("Breezy Weather version not supported (provider 1.x)"), "and said so");
        loop.source.version = new Pair<>(0, 3);
        loop.source.permitted = false;
        loop.feed.cue(WeatherCue.TIMER);
        loop.run();
        check(loop.last().getStatus() == WeatherStatus.NO_PERMISSION, "no permission");
        check("Permission not granted".equals(WeatherText.INSTANCE.status(loop.last(), NOON)), "said so");
        loop.source.owner = "com.evil.weather";
        int before = loop.source.calls;
        loop.feed.cue(WeatherCue.TIMER);
        loop.run();
        check(loop.last().getStatus() == WeatherStatus.WRONG_PACKAGE && loop.source.calls == before + 1, "another owner: refused before the permission or any query");
        loop.source.owner = null;
        loop.feed.cue(WeatherCue.TIMER);
        loop.run();
        check("Breezy Weather not installed".equals(WeatherText.INSTANCE.status(loop.last(), NOON)), "not installed");
        loop.source.owner = "org.breezyweather";
        loop.source.permitted = true;
        loop.source.places = List.of();
        loop.feed.cue(WeatherCue.TIMER);
        loop.run();
        check("No locations in Breezy".equals(WeatherText.INSTANCE.status(loop.last(), NOON)), "no locations");
        loop.source.places = List.of(place("31.5&-97.1&openmeteo", 31.5, -97.1, false, "Waco"), place("42.4&-71.2&openmeteo", 42.4, -71.2, false, PLACE));
        loop.feed.setFix(() -> LEXINGTON);
        loop.feed.cue(WeatherCue.NOTIFIER);
        loop.run();
        check(loop.source.asked.get(loop.source.asked.size() - 1).equals("42.4&-71.2&openmeteo"), "the nearest to the fix read");
        check((PLACE + " · updated 50 min ago").equals(WeatherText.INSTANCE.status(loop.last(), NOON + 20 * MIN)), "the card's line");
        String report = loop.feed.report();
        check(report.contains("weather_reads=9 ") && report.contains("weather_read_failures=7 ") && report.contains("weather_notifier_cues=2 ")
            && report.contains("weather_provider=ok ") && report.contains("weather_reading_age_min=50 "), "the counts: " + report);
        loop.feed.stop();
        check(loop.last().getReading() == null, "the projection's end drops the reading");
    }

    private static void oneReadAtATime() {
        Loop loop = new Loop();
        loop.feed.start(new WeatherSettings(true, true, AlertLevel.SEVERE));
        loop.feed.cue(WeatherCue.NOTIFIER);
        loop.feed.cue(WeatherCue.TIMER);
        check(loop.work.size() == 1, "a cue while a read runs starts none");
        loop.run();
        check(loop.feed.report().contains("weather_reads_skipped=2 ") && loop.feed.report().contains("weather_reads=1 "), "skipped, not queued");
        // A read still running when the projection ends is ignored when it comes back.
        loop.feed.cue(WeatherCue.NOTIFIER);
        Runnable pending = loop.work.remove(0);
        loop.feed.stop();
        pending.run();
        loop.run();
        check(loop.last().getReading() == null, "a late answer after the end is dropped");
        // Turned off mid-session: dropped at once; back on: read again.
        loop.feed.start(new WeatherSettings(true, true, AlertLevel.SEVERE));
        loop.run();
        loop.feed.settings(new WeatherSettings(false, true, AlertLevel.SEVERE));
        check(loop.last().getReading() == null, "off drops the reading");
        loop.feed.settings(new WeatherSettings(true, true, AlertLevel.SEVERE));
        loop.run();
        check(loop.last().getReading() != null, "on reads again");
    }

    private static void eachAlertIsAskedForOnce() {
        Loop loop = new Loop();
        loop.now = NOON;
        loop.feed.start(new WeatherSettings(true, true, AlertLevel.SEVERE));
        loop.run();
        check(loop.spoken.equals(List.of("extreme", "severe-soon", "severe-late")), "each qualifying alert asked for: " + loop.spoken);
        loop.feed.cue(WeatherCue.NOTIFIER);
        loop.run();
        loop.feed.tick();
        check(loop.spoken.size() == 3, "once per id per session");
        loop.now = NOON + HOUR;
        loop.feed.tick();
        check(loop.spoken.equals(List.of("extreme", "severe-soon", "severe-late", "future")), "one that starts later, on the minute's tick");
        loop.source.blob = gzip(full(NOON, standardAlerts(NOON) + "," + alert("upgrade", NOON, NOON + 2 * HOUR, 4, null, "Upgraded")));
        loop.feed.cue(WeatherCue.NOTIFIER);
        loop.run();
        check(loop.spoken.get(loop.spoken.size() - 1).equals("upgrade"), "a new id is asked for again");
        Loop quiet = new Loop();
        quiet.now = NOON;
        quiet.feed.start(new WeatherSettings(true, false, AlertLevel.MINOR));
        quiet.run();
        check(quiet.spoken.isEmpty(), "Speak weather alerts off: never asked");
        check(quiet.feed.report().contains("weather_alerts_qualified=4 ") && quiet.feed.report().contains("weather_alerts_asked=0 "), "but counted as qualifying");
        Loop next = new Loop();
        next.now = NOON;
        next.feed.start(new WeatherSettings(true, true, AlertLevel.SEVERE));
        next.run();
        next.feed.stop();
        next.feed.start(new WeatherSettings(true, true, AlertLevel.SEVERE));
        next.run();
        check(next.spoken.size() == 6, "a new session asks again");
    }

    // --- The dashboard --------------------------------------------------------------------

    private static final CallState RINGING = new CallState(CallPhase.RINGING, "Callermarker", 0L, false, CallSource.TELECOM);

    private static void theTileOrder() {
        CallState none = CallState.Companion.getNONE();
        FlexRules r = FlexRules.INSTANCE;
        for (boolean paused : new boolean[] {false, true}) {
            for (int n : new int[] {0, 3}) {
                for (boolean view : new boolean[] {false, true}) {
                    for (boolean alert : new boolean[] {false, true}) {
                        FlexSource want = view ? FlexSource.WEATHER : alert ? FlexSource.WEATHER_ALERT
                            : (!paused && n > 0) ? FlexSource.NOTIFICATIONS : FlexSource.EMPTY;
                        check(r.source(none, paused, n, view, alert) == want, "order: paused=" + paused + " n=" + n + " view=" + view + " alert=" + alert);
                        check(r.source(RINGING, paused, n, view, alert) == FlexSource.CALL, "a call outranks everything");
                    }
                }
            }
        }
        check(r.source(none, false, 0) == FlexSource.EMPTY, "weather never fills an empty tile by itself");
        check("weather".equals(r.label(FlexSource.WEATHER, none)) && "weather alert".equals(r.label(FlexSource.WEATHER_ALERT, none)), "labels");
    }

    private static List<DashboardLayout> layouts() {
        return List.of(
            new DashboardLayout(0, 0, 1920, 1080, true),
            new DashboardLayout(0, 0, 1920, 1080, false),
            new DashboardLayout(74, 0, 726, 480, true),
            new DashboardLayout(0, 0, 800, 480, true),
            new DashboardLayout(0, 0, 800, 480, false));
    }

    private static void theHeaderItemFits(DashboardLayout l) {
        Box w = l.getHeaderWeather();
        Box p = l.headerIcon(0);
        String at = " at " + l.getWidth() + "x" + l.getHeight();
        check(w.getY() == l.getY() && w.getHeight() == l.getHeader(), "full header height" + at);
        check(w.getRight() + l.getMargin() == p.getX(), "a margin left of Ⓟ" + at);
        check(!overlaps(w, p) && !overlaps(w, l.headerIcon(1)), "never over Ⓟ or ☀/☾" + at);
        check(l.hitHeaderIcon(w.getRight() - 1, w.getCenterY()) == null && !l.hitHeaderWeather(p.getX(), p.getCenterY()), "the hit tests do not reach each other" + at);
        check(l.headerTextRight(true) == w.getX() - l.getMargin() && l.headerTextRight(false) == l.getHeaderTextRight(), "the text stops short of it only while shown" + at);
        // What the text left of it needs: "23:59" (or "12:59": the 12-hour header clock has
        // no marker, which would not fit here), "100 mph", the needle and "NNW 359°", at the
        // sizes DashboardDrawing uses and the generous em estimate.
        float big = l.getHeader() * 0.52f;
        float small = l.getHeader() * 0.24f;
        float em = DashboardLayout.EM_PER_CHARACTER;
        float text = l.getMargin() + em * big * 5 + 2 * l.getMargin() + em * big * 7 + 2 * l.getMargin() + big + em * small * 8;
        check(l.getX() + text <= l.headerTextRight(true), "clock, speed, compass and bearing fit left of it" + at + ": " + text + " > " + (l.headerTextRight(true) - l.getX()));
        check("78°F".equals(l.headerWeatherText("78°F", "78°")) && "-12°C".equals(l.headerWeatherText("-12°C", "-12°")), "the unit fits" + at);
        check("104°".equals(l.headerWeatherText("104°F with more", "104°")), "and goes first when it does not" + at);
    }

    private static Dashboard dashboard(boolean weather, AlertLevel level) {
        DashboardChoices choices = new DashboardChoices(MarkerColour.BLUE, DayNightMode.AUTO, false, false, null, true, weather, level);
        Dashboard d = new Dashboard(new DashboardLayout(0, 0, 800, 480, true), "t", UTC, SpeedUnit.MPH,
            MapOrientation.NORTH_UP, o -> kotlin.Unit.INSTANCE, choices);
        d.phone(new PhoneSnapshot(80, false, 3, "lte", 42.4495, -71.2310, null, null, null, true));
        return d;
    }

    private static WeatherState state(long now, String alerts) {
        WeatherData data = parse(full(now, alerts), now);
        return new WeatherState(WeatherStatus.OK, new WeatherReading(place("CURRENT_POSITION", 42.45, -71.23, true, PLACE), data, now), null);
    }

    private static void tap(Dashboard d, Box box, long now) {
        d.touch("down", box.getCenterX(), box.getCenterY(), now);
        d.touch("up", box.getCenterX(), box.getCenterY(), now);
    }

    private static void theViewOpensAndCloses() {
        Dashboard off = dashboard(false, AlertLevel.SEVERE);
        off.weather(state(NOON, ""));
        Dashboard.Snapshot s = off.nextFrame(NOON);
        check(s.getWeatherHeader() == null, "Show weather off: no item");
        tap(off, off.getLayout().getHeaderWeather(), NOON);
        check(!off.weatherOpen() && off.nextFrame(NOON).getFlex() == FlexSource.EMPTY, "and its box is no target");

        Dashboard d = dashboard(true, AlertLevel.SEVERE);
        s = d.nextFrame(NOON);
        check(s.getWeatherHeader() != null && "—".equals(s.getWeatherHeader().getTemperature()) && s.getWeatherHeader().getDim(), "no reading yet: a dim dash");
        d.weather(state(NOON, ""));
        s = d.nextFrame(NOON);
        check("78°F".equals(s.getWeatherHeader().getTemperature()) && !s.getWeatherHeader().getDim()
            && s.getWeatherHeader().getCode() == WeatherCode.PARTLY_CLOUDY && s.getWeatherHeader().getAlertColour() == null, "the item: glyph and 78°F");
        check(s.getFlex() == FlexSource.EMPTY, "the tile left as it was");
        Box item = d.getLayout().getHeaderWeather();
        d.touch("down", item.getCenterX(), item.getCenterY(), NOON);
        check(d.nextFrame(NOON).getWeatherHeader().getPressed(), "pressed, lit");
        d.touch("up", item.getCenterX(), item.getCenterY(), NOON);
        s = d.nextFrame(NOON);
        check(s.getFlex() == FlexSource.WEATHER && "weather".equals(s.getFlexLabel()) && s.getWeatherView() != null, "a tap opens the view");
        tap(d, item, NOON + 1000);
        check(d.nextFrame(NOON + 1000).getFlex() == FlexSource.EMPTY, "a second tap closes it");
        tap(d, item, NOON + 2000);
        tap(d, d.getLayout().getFlexTile(), NOON + 3000);
        check(!d.weatherOpen(), "a tap on the view closes it");
        // Press on the item, lift elsewhere: nothing, as for every button.
        d.touch("down", item.getCenterX(), item.getCenterY(), NOON);
        d.touch("up", item.getX() - 5, item.getCenterY(), NOON);
        check(!d.weatherOpen(), "a press that leaves the item does nothing");
        tap(d, item, NOON + 4000);
        check(d.nextFrame(NOON + 4000 + WeatherRules.VIEW_TIMEOUT_MS - 1).getFlex() == FlexSource.WEATHER, "open for just under 30 s");
        check(d.nextFrame(NOON + 4000 + WeatherRules.VIEW_TIMEOUT_MS).getFlex() == FlexSource.EMPTY, "closed by itself at 30 s");
        tap(d, item, NOON + 50_000);
        d.call(RINGING);
        check(d.nextFrame(NOON + 51_000).getFlex() == FlexSource.CALL, "a call takes the tile");
        d.call(CallState.Companion.getNONE());
        check(d.nextFrame(NOON + 52_000).getFlex() == FlexSource.EMPTY, "and the view does not come back after it");
        String report = d.report();
        check(report.contains("weather_view_opens=4 ") && report.contains("weather_view_closes_tap=1 ") && report.contains("weather_view_closes_view=1 ")
            && report.contains("weather_view_closes_timeout=1 ") && report.contains("weather_view_closes_call=1 "), "the counts: " + report);
        d.weatherChoices(false, AlertLevel.SEVERE);
        check(d.nextFrame(NOON + 53_000).getWeatherHeader() == null, "turned off mid-session: gone");
    }

    private static void aTapDuringACallDoesNothing() {
        Dashboard d = dashboard(true, AlertLevel.SEVERE);
        d.weather(state(NOON, ""));
        d.call(RINGING);
        Box item = d.getLayout().getHeaderWeather();
        d.touch("down", item.getCenterX(), item.getCenterY(), NOON);
        check(!d.nextFrame(NOON).getWeatherHeader().getPressed(), "no highlight during a call");
        d.touch("up", item.getCenterX(), item.getCenterY(), NOON);
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check(s.getFlex() == FlexSource.CALL && !d.weatherOpen(), "the call keeps the tile");
        check(d.report().contains("weather_taps_in_call=1 ") && d.report().contains("weather_view_opens=0 "), "counted, not opened");
    }

    private static void theAlertTakesTheTileAndIsDismissed() {
        Dashboard d = dashboard(true, AlertLevel.SEVERE);
        d.notifications(List.of(new com.jw6ventures.headlink.DashNotification("k", "App", "Sender", "Text", NOON, "org.example")));
        d.weather(state(NOON, standardAlerts(NOON)));
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check(s.getFlex() == FlexSource.WEATHER_ALERT && "weather alert".equals(s.getFlexLabel()), "a severe alert over a notification");
        check("extreme".equals(s.getWeatherAlert().getId()) && s.getWeatherAlert().getMore() == 2 && "Tornado Warning".equals(s.getWeatherAlert().getHeadline()),
            "the most severe first, +2 more");
        check(s.getWeatherAlert().getColour() == 0xFFFF8C00, "an alert with no usable colour gets the warning orange");
        check(s.getWeatherHeader().getAlertColour() != null, "the header's glyph carries the mark");
        tap(d, d.getLayout().getFlexTile(), NOON);
        s = d.nextFrame(NOON);
        check("severe-soon".equals(s.getWeatherAlert().getId()) && "until 18:15".equals(s.getWeatherAlert().getUntil()) && s.getWeatherAlert().getMore() == 1,
            "dismissed: the next, ending sooner, with its end time");
        tap(d, d.getLayout().getFlexTile(), NOON);
        tap(d, d.getLayout().getFlexTile(), NOON);
        s = d.nextFrame(NOON);
        check(s.getFlex() == FlexSource.NOTIFICATIONS, "all dismissed: the notification returns");
        check(s.getWeatherHeader().getAlertColour() != null, "the header still marks it");
        d.weather(state(NOON, standardAlerts(NOON) + "," + alert("upgrade", NOON, NOON + 2 * HOUR, 4, "#aa0000", "Upgraded")));
        s = d.nextFrame(NOON);
        check(s.getFlex() == FlexSource.WEATHER_ALERT && "upgrade".equals(s.getWeatherAlert().getId()), "a new id shows again");
        // Ⓟ pauses notifications, not alerts.
        Box pause = d.getLayout().headerIcon(0);
        tap(d, pause, NOON);
        check(d.nextFrame(NOON).getFlex() == FlexSource.WEATHER_ALERT, "Ⓟ does not hide an alert");
        // The opened view outranks the alert, and carries it in its strip.
        tap(d, d.getLayout().getHeaderWeather(), NOON);
        s = d.nextFrame(NOON);
        check(s.getFlex() == FlexSource.WEATHER && "Upgraded".equals(s.getWeatherView().getStrip().getHeadline()), "the view, with the most severe, soonest-ending alert in its strip");
        // Shown counts alerts drawn in a frame: the two taps above had none between them, so
        // severe-late was dismissed without being drawn (on the truck, frames come at 30 fps).
        check(d.report().contains("weather_alerts_shown=3 ") && d.report().contains("weather_alerts_dismissed=3 "), "counted: " + d.report());
        Dashboard extreme = dashboard(true, AlertLevel.EXTREME);
        extreme.weather(state(NOON, standardAlerts(NOON).replace("\"severity\":4", "\"severity\":3")));
        check(extreme.nextFrame(NOON).getFlex() == FlexSource.EMPTY && extreme.nextFrame(NOON).getWeatherHeader().getAlertColour() == null,
            "below the level: no tile, no mark");
        extreme.weather(state(NOON, standardAlerts(NOON)));
        tap(extreme, extreme.getLayout().getHeaderWeather(), NOON);
        check(extreme.nextFrame(NOON).getWeatherView().getStrip() != null, "the strip still shows alerts below the level");
    }

    private static void theViewsSayWhereAndHowOld() {
        Dashboard d = dashboard(true, AlertLevel.SEVERE);
        tap(d, d.getLayout().getHeaderWeather(), NOON);
        Dashboard.Snapshot s = d.nextFrame(NOON);
        check("Not read yet".equals(s.getWeatherView().getStatus()), "open with no reading: the status line");
        d.weather(new WeatherState(WeatherStatus.NO_PERMISSION, null, null));
        check("Permission not granted".equals(d.nextFrame(NOON).getWeatherView().getStatus()), "and why");
        d.weather(state(NOON, ""));
        s = d.nextFrame(NOON + 10_000);
        com.jw6ventures.headlink.WeatherPanel v = s.getWeatherView();
        check(v.getStatus() == null && "78°F".equals(v.getTemperature()) && "Partly cloudy".equals(v.getText()), "now");
        check("Feels 84° · H 91° L 74°".equals(v.getDetail()), "feels, high, low: " + v.getDetail());
        check(v.getHours().size() == WeatherRules.VIEW_HOURS && "18:00".equals(v.getHours().get(0).getHour()) && "40%".equals(v.getHours().get(0).getRain()),
            "four hours after now: " + v.getHours());
        check((PLACE + " · 30 min ago").equals(v.getPlace()) && !v.getDim(), "the place and age: " + v.getPlace());
        check(!d.report().contains("weather_far=true"), "near: no distance");
        // Four hours old and 70 km away.
        WeatherData old = parse(full(NOON - 4 * HOUR, ""), NOON);
        d.weather(new WeatherState(WeatherStatus.OK, new WeatherReading(place("x", 42.97, -71.46, false, PLACE), old, NOON), null));
        s = d.nextFrame(NOON + 20_000);
        check(s.getWeatherView().getDim() && s.getWeatherHeader().getDim(), "stale: dimmed in both");
        check(("old · 61 km away · " + PLACE + " · 4 h ago").equals(s.getWeatherView().getPlace()), "stale and far: " + s.getWeatherView().getPlace());
        check(d.report().contains("weather_far=true"), "the distance label counted");
    }

    // --- Speech ---------------------------------------------------------------------------

    private static final SpeechConditions OK = new SpeechConditions(true, true, false);

    private static void alertsAreSpokenByTheRules() {
        SpeechQueue q = new SpeechQueue();
        SpeechAction a = q.alert("a1", 0, OK);
        check(a instanceof SpeechAction.Speak && ((SpeechAction.Speak) a).getKind() == com.jw6ventures.headlink.SpeechKind.ALERT, "spoken");
        q.began((SpeechAction.Speak) a, 0);
        check(skipped(q.read("n", 10, OK), SpeechSkip.ALERT_PLAYING), "a notification tap during an alert is dropped");
        q.finished(((SpeechAction.Speak) a).getId(), com.jw6ventures.headlink.SpeechEnd.COMPLETED, 1000);
        // A notification being read is cut off by an alert.
        SpeechAction read = q.read("n", 5000, OK);
        q.began((SpeechAction.Speak) read, 5000);
        SpeechAction cut = q.alert("a2", 6000, OK);
        q.began((SpeechAction.Speak) cut, 6000);
        check(q.report().contains("notifications_read_interrupted_by_alert=1"), "the read, cut off by the alert");
        q.finished(((SpeechAction.Speak) cut).getId(), com.jw6ventures.headlink.SpeechEnd.COMPLETED, 7000);
        // A prompt being spoken is never cut: the alert waits, then goes first.
        SpeechAction prompt = q.guide("prepare", 8000, OK);
        q.began((SpeechAction.Speak) prompt, 8000);
        SpeechAction wait = q.alert("a3", 8100, OK);
        check(wait instanceof SpeechAction.Wait && List.of("a3").equals(q.getWaitingAlerts()), "waits for the prompt");
        check(q.waiting(8200, OK) == null, "not while the prompt is spoken");
        q.finished(((SpeechAction.Speak) prompt).getId(), com.jw6ventures.headlink.SpeechEnd.COMPLETED, 9000);
        SpeechAction after = q.waiting(9000, OK);
        check(after instanceof SpeechAction.Speak && q.getWaitingAlerts().isEmpty(), "then goes");
        q.began((SpeechAction.Speak) after, 9000);
        // A prompt arriving during an alert still wins, as it does over anything.
        SpeechAction over = q.guide("at_turn", 9500, OK);
        q.began((SpeechAction.Speak) over, 9500);
        check(q.report().contains("weather_alerts_interrupted_by_guidance=1"), "a prompt cuts an alert off");
        q.finished(((SpeechAction.Speak) over).getId(), com.jw6ventures.headlink.SpeechEnd.COMPLETED, 10_000);
        // Waiting, then the toggle goes off: counted toggle_off when it would have gone.
        SpeechAction p2 = q.guide("early", 20_000, OK);
        q.began((SpeechAction.Speak) p2, 20_000);
        check(q.alert("a4", 20_100, OK) instanceof SpeechAction.Wait, "waits");
        check(q.alert("a5", 20_200, OK) instanceof SpeechAction.Wait && List.of("a4", "a5").equals(q.getWaitingAlerts()),
            "a second waits behind the first, not in its place: " + q.getWaitingAlerts());
        q.setGuidanceOn(false);
        q.interrupt(com.jw6ventures.headlink.SpeechEnd.STOPPED, 20_300);
        check(skipped(q.waiting(20_300, OK), SpeechSkip.TOGGLE_OFF), "released into a toggle turned off");
        check(skipped(q.waiting(20_300, OK), SpeechSkip.TOGGLE_OFF) && q.waiting(20_300, OK) == null, "and the one behind it, then none");
        check(skipped(q.alert("a6", 21_000, OK), SpeechSkip.TOGGLE_OFF), "toggle off: not spoken");
        q.setGuidanceOn(true);
        check(skipped(q.alert("a7", 22_000, new SpeechConditions(true, true, true)), SpeechSkip.IN_CALL), "not in a call");
        check(skipped(q.alert("a8", 22_000, new SpeechConditions(true, false, false)), SpeechSkip.NO_BLUETOOTH), "not without A2DP");
        check(skipped(q.alert("a9", 22_000, new SpeechConditions(false, true, false)), SpeechSkip.NO_ENGINE), "not without an engine");
        String report = q.report();
        check(report.contains("weather_alerts_requests=9 ") && report.contains("weather_alerts_started=3 ") && report.contains("weather_alerts_waited=3")
            && report.contains("weather_alerts_skipped_toggle_off=3") && report.contains("weather_alerts_skipped_session_end=0")
            && report.contains("weather_alerts_skipped_in_call=1") && report.contains("weather_alerts_interrupted_by_alert=0"), "the counts: " + report);
        check(!report.contains("weather_alerts_skipped_guidance_playing") && !report.contains("voice_prompts_skipped_session_end")
            && !report.contains("notifications_read_skipped_session_end"), "no reason that cannot happen to a kind");
        String words = WeatherText.INSTANCE.spoken(new WeatherAlert("x", null, null, HEADLINE, 3, null));
        check(("Weather alert. " + HEADLINE).equals(words), "what is said: the headline, never the description");
        String longWords = WeatherText.INSTANCE.spoken(new WeatherAlert("x", null, null, "word ".repeat(200).trim(), 3, null));
        check(longWords.length() <= "Weather alert. ".length() + 400 && longWords.endsWith("word"), "capped at 400, at a word");
        // Two alerts in one read (the lab's case): the second never cuts the first off.
        SpeechQueue two = new SpeechQueue();
        SpeechAction b1 = two.alert("b1", 0, OK);
        two.began((SpeechAction.Speak) b1, 0);
        SpeechAction b2 = two.alert("b2", 7, OK);
        check(b2 instanceof SpeechAction.Wait && List.of("b2").equals(two.getWaitingAlerts()), "the second waits behind the first");
        check(two.waiting(10, OK) == null, "not while the first is said");
        two.finished(((SpeechAction.Speak) b1).getId(), com.jw6ventures.headlink.SpeechEnd.COMPLETED, 3000);
        SpeechAction next = two.waiting(3000, OK);
        check(next instanceof SpeechAction.Speak && "b2".equals(((SpeechAction.Speak) next).getKey()) && two.getWaitingAlerts().isEmpty(),
            "then goes once the first completes");
        two.began((SpeechAction.Speak) next, 3000);
        check(two.waiting(3001, OK) == null, "nothing more waits");
        two.finished(((SpeechAction.Speak) next).getId(), com.jw6ventures.headlink.SpeechEnd.COMPLETED, 6000);
        String twoReport = two.report();
        check(twoReport.contains("weather_alerts_started=2 ") && twoReport.contains("weather_alerts_completed=2 ")
            && twoReport.contains("weather_alerts_interrupted_by_alert=0") && twoReport.contains("weather_alerts_waited=1"),
            "both said in full: " + twoReport);
        // Three queued behind a turn prompt: all three go, one at a time, in the order asked.
        SpeechQueue three = new SpeechQueue();
        SpeechAction held = three.guide("prepare", 0, OK);
        three.began((SpeechAction.Speak) held, 0);
        for (String key : List.of("c1", "c2", "c3")) check(three.alert(key, 10, OK) instanceof SpeechAction.Wait, key + " waits for the prompt");
        check(List.of("c1", "c2", "c3").equals(three.getWaitingAlerts()), "in the order asked: " + three.getWaitingAlerts());
        three.finished(((SpeechAction.Speak) held).getId(), com.jw6ventures.headlink.SpeechEnd.COMPLETED, 2000);
        List<String> said = new ArrayList<>();
        long at = 2000;
        for (int i = 0; i < 3; i++) {
            SpeechAction go = three.waiting(at, OK);
            if (!(go instanceof SpeechAction.Speak)) break;
            said.add(((SpeechAction.Speak) go).getKey());
            three.began((SpeechAction.Speak) go, at);
            check(three.waiting(at + 1, OK) == null, "one at a time");
            at += 3000;
            three.finished(((SpeechAction.Speak) go).getId(), com.jw6ventures.headlink.SpeechEnd.COMPLETED, at);
        }
        check(List.of("c1", "c2", "c3").equals(said) && three.waiting(at, OK) == null, "all three, in order: " + said);
        String threeReport = three.report();
        check(threeReport.contains("weather_alerts_completed=3 ") && threeReport.contains("weather_alerts_interrupted_by_alert=0")
            && threeReport.contains("voice_prompts_completed=1 "), "none cut off: " + threeReport);
        // The session ending with alerts still waiting: each is counted, none is said.
        SpeechQueue ending = new SpeechQueue();
        SpeechAction p3 = ending.guide("early", 0, OK);
        ending.began((SpeechAction.Speak) p3, 0);
        ending.alert("z1", 10, OK);
        ending.alert("z2", 11, OK);
        ending.end(20);
        String endReport = ending.report();
        check(ending.getWaitingAlerts().isEmpty() && endReport.contains("weather_alerts_skipped_session_end=2")
            && endReport.contains("weather_alerts_started=0 ") && endReport.contains("voice_prompts_session_end=1"),
            "the session's end drops the waiting alerts, counted: " + endReport);
    }

    private static boolean skipped(SpeechAction action, SpeechSkip reason) {
        return action instanceof SpeechAction.Skip && ((SpeechAction.Skip) action).getReason() == reason;
    }

    // --- Privacy --------------------------------------------------------------------------

    private static void theReportsHoldCountsOnly() {
        Loop loop = new Loop();
        loop.now = NOON;
        loop.feed.start(new WeatherSettings(true, true, AlertLevel.MINOR));
        loop.run();
        Dashboard d = dashboard(true, AlertLevel.MINOR);
        d.weather(loop.last());
        tap(d, d.getLayout().getHeaderWeather(), NOON);
        d.nextFrame(NOON);
        // Words anywhere in either report; numbers in the weather lines, since the dashboard's
        // own lines carry layout coordinates that may hold the same digits.
        String all = loop.feed.report() + d.report();
        for (String secret : new String[] {PLACE, "Placemarker", "Partly", "Tornado", "Severe", "Descriptionmarker", "Instructionmarker", "partly_cloudy"}) {
            check(!all.contains(secret), "the reports never carry " + secret);
        }
        StringBuilder weather = new StringBuilder(loop.feed.report());
        for (String line : d.report().split("\n")) if (line.startsWith("weather_")) weather.append(line).append('\n');
        for (String secret : new String[] {"=78", "=84", "=91", "=74", "42.4", "-71.2"}) {
            check(!weather.toString().contains(secret), "the weather lines never carry " + secret);
        }
    }

    // --- The lab's own scenarios ----------------------------------------------------------

    private static void theLabScenariosParse(Path dir) throws IOException {
        int count = 0;
        try (var files = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.getFileName().toString().startsWith("weather") && p.toString().endsWith(".json"))::iterator) {
                String json = org.breezyweather.standin.Scenarios.resolve(Files.readString(file), NOON, ZoneId.of("America/New_York"));
                WeatherData data = WeatherJson.INSTANCE.parse(gzip(json), NOON);
                check(data.getRefreshMillis() != null && data.getTemperature() != null && data.getCode() != null, "the lab's " + dir.relativize(file) + " reads whole");
                count++;
            }
        }
        check(count > 0, "the lab's scenarios found in " + dir);
        System.out.println("weather-test: " + count + " lab scenario blobs parsed");
    }

    // --- Helpers --------------------------------------------------------------------------

    private static WeatherAlert find(List<WeatherAlert> alerts, String id) {
        for (WeatherAlert a : alerts) if (a.getId().equals(id)) return a;
        throw new AssertionError("no alert " + id);
    }

    private static List<String> ids(List<WeatherAlert> alerts) {
        List<String> out = new ArrayList<>();
        for (WeatherAlert a : alerts) out.add(a.getId());
        return out;
    }

    private static boolean overlaps(Box a, Box b) {
        return a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom();
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
