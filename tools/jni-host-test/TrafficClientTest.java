// P9.1's live traffic client and P9.2's response parsing on a desktop JVM (docs/p9-plan.md
// section 9, client items): no request at all while the integration is off or keyless, exactly the
// two GETs the plan names when it is on, every request counted before it is sent, the
// monthly cap and its reset on the 1st, both of HERE's error shapes, gzip or not, the
// base-URL override honoured only in a debuggable build, and that a snapshot is only ever
// the box it was fetched for. The flow and incident bodies are hand-written in the shape the
// P9.0 captures showed, never copied from them (plan section 1, 6.4 h). TrafficSettings,
// TrafficStatus, TrafficClient and TrafficJson are pure Kotlin; the preferences file, the
// card and HttpURLConnection against HERE need a phone (docs/p9-1-results.md).
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import kotlin.Unit;
import com.jw6ventures.headlink.FlowSpan;
import com.jw6ventures.headlink.FlowStretch;
import com.jw6ventures.headlink.MemoryTrafficLedger;
import com.jw6ventures.headlink.TrafficBox;
import com.jw6ventures.headlink.TrafficCheck;
import com.jw6ventures.headlink.TrafficClient;
import com.jw6ventures.headlink.TrafficFetch;
import com.jw6ventures.headlink.TrafficHttp;
import com.jw6ventures.headlink.TrafficIncident;
import com.jw6ventures.headlink.TrafficJson;
import com.jw6ventures.headlink.TrafficResponse;
import com.jw6ventures.headlink.TrafficSettings;
import com.jw6ventures.headlink.TrafficSkip;
import com.jw6ventures.headlink.TrafficSnapshot;
import com.jw6ventures.headlink.TrafficStatus;
import com.jw6ventures.headlink.Traversability;

public final class TrafficClientTest {
    private static int passed;
    private static final String KEY = "Test-Key_0123456789abcdefghijklmnopqrstuv";
    private static final TrafficBox DALLAS = new TrafficBox(32.75, -96.85, 32.80, -96.75);
    private static final TrafficBox AUSTIN = new TrafficBox(30.25, -97.78, 30.30, -97.70);
    /** 28 September 2026, 15:00 UTC. */
    private static final long SEPTEMBER = Instant.parse("2026-09-28T15:00:00Z").toEpochMilli();

    /**
     * A flow response in HERE's shape: a stretch of three links (joins repeated, as HERE
     * sends them) with sub-segments, the second closed with no speed; a closed stretch with
     * no speed at all; a reversible one with no description or confidence; a result with no
     * location and one with no currentFlow, both dropped; and fields the parser has never
     * heard of, nested and escaped, which must be skipped whole.
     */
    private static final String FLOW = "{\"sourceUpdated\":\"2026-09-28T19:57:16Z\",\"newTopLevel\":{\"a\":[1,2,{\"b\":null}]},"
        + "\"results\":["
        + "{\"location\":{\"description\":\"I-35E\",\"length\":300.0,\"shape\":{\"links\":["
        + "{\"points\":[{\"lat\":32.78,\"lng\":-96.80},{\"lat\":32.781,\"lng\":-96.801}],\"length\":140.0,\"functionalClass\":2},"
        + "{\"points\":[{\"lat\":32.781,\"lng\":-96.801},{\"lat\":32.782,\"lng\":-96.802},{\"lat\":32.7825,\"lng\":-96.8025}],\"length\":110.0,\"functionalClass\":1},"
        + "{\"points\":[{\"lat\":32.7825,\"lng\":-96.8025},{\"lat\":32.783,\"lng\":-96.803}],\"length\":50.0,\"functionalClass\":3}]}},"
        + "\"currentFlow\":{\"speed\":12.5,\"speedUncapped\":13.0,\"freeFlow\":25.0,\"jamFactor\":5.5,\"jamTendency\":-1,"
        + "\"confidence\":0.97,\"traversability\":\"open\",\"subSegments\":["
        + "{\"length\":100.0,\"speed\":20.0,\"speedUncapped\":20.0,\"freeFlow\":25.0,\"jamFactor\":2.0,\"confidence\":0.9,\"traversability\":\"open\"},"
        + "{\"length\":150.0,\"jamFactor\":10.0,\"traversability\":\"closed\",\"junctionTraversability\":\"intermediateClosedEdgeOpen\"},"
        + "{\"length\":50.0,\"speed\":8.0,\"traversability\":\"open\"}]}},"
        + "{\"location\":{\"description\":\"Elm St \\\"Main\\\" \\u00e9\",\"length\":80.0,\"shape\":{\"links\":["
        + "{\"points\":[{\"lat\":32.79,\"lng\":-96.79},{\"lat\":32.7905,\"lng\":-96.7905}],\"length\":80.0,\"functionalClass\":3}]}},"
        + "\"currentFlow\":{\"freeFlow\":12.5,\"jamFactor\":10.0,\"traversability\":\"closed\",\"junctionTraversability\":\"intermediateClosedEdgeOpen\"}},"
        + "{\"location\":{\"length\":40.0,\"shape\":{\"links\":[{\"points\":[{\"lat\":32.77,\"lng\":-96.78},{\"lat\":32.7703,\"lng\":-96.7803}],\"length\":40.0}]}},"
        + "\"currentFlow\":{\"speed\":10.0,\"freeFlow\":11.0,\"jamFactor\":0.0,\"traversability\":\"reversibleNotRoutable\",\"somethingNew\":[true,false]}},"
        + "{\"currentFlow\":{\"speed\":10.0,\"jamFactor\":1.0,\"traversability\":\"open\"}},"
        + "{\"location\":{\"description\":\"no flow\",\"length\":10.0,\"shape\":{\"links\":[{\"points\":[{\"lat\":32.7,\"lng\":-96.7}],\"length\":10.0,\"functionalClass\":4}]}}}"
        + "]}";

    /** Incidents in HERE's shape: a closure with every field, and one with only what is required. */
    private static final String INCIDENTS = "{\"sourceUpdated\":\"2026-09-28T19:56:08Z\",\"results\":["
        + "{\"location\":{\"length\":120.0,\"shape\":{\"links\":["
        + "{\"points\":[{\"lat\":32.805,\"lng\":-96.815},{\"lat\":32.8055,\"lng\":-96.8145}],\"length\":60.0,\"functionalClass\":5},"
        + "{\"points\":[{\"lat\":32.8055,\"lng\":-96.8145},{\"lat\":32.806,\"lng\":-96.814}],\"length\":60.0,\"functionalClass\":5}]}},"
        + "\"incidentDetails\":{\"id\":\"1111\",\"hrn\":\"here:traffic:incident:1111\",\"originalId\":\"1110\","
        + "\"startTime\":\"2026-09-24T15:06:22Z\",\"endTime\":\"2026-09-29T15:06:22Z\",\"roadClosed\":true,"
        + "\"criticality\":\"critical\",\"type\":\"roadClosure\",\"typeDescription\":{\"value\":\"Road closure\",\"language\":\"en-US\"},"
        + "\"codes\":[401],\"description\":{\"value\":\"Closed between A and B\",\"language\":\"en-US\"},"
        + "\"summary\":{\"value\":\"Closed\",\"language\":\"en-US\"},\"junctionTraversability\":\"intermediateClosedEdgeOpen\"}},"
        + "{\"location\":{\"shape\":{\"links\":[{\"points\":[{\"lat\":32.76,\"lng\":-96.76},{\"lat\":32.761,\"lng\":-96.761}]}]}},"
        + "\"incidentDetails\":{\"id\":\"2222\",\"type\":\"construction\",\"description\":{\"value\":\"Lane narrows\"}}},"
        + "{\"incidentDetails\":{\"id\":\"3333\",\"type\":\"congestion\"}}"
        + "]}";

    private static final String BAD_REQUEST = "{\"title\":\"Malformed request\",\"status\":400,\"code\":\"E608006\","
        + "\"cause\":\"maximum width and height of a bounding box can be 1 degree\","
        + "\"action\":\"request must adhere to API specification\",\"correlationId\":\"00000000-aaaa-bbbb-cccc-000000000000\"}";
    private static final String BAD_KEY = "{\"error\":\"Unauthorized\",\"error_description\":\"apiKey invalid. apiKey not found.\"}";

    public static void main(String[] args) throws IOException {
        settingsStartOffAndRepair();
        offOrKeylessSendsNothing();
        aFetchIsExactlyTheTwoRequests();
        everyRequestIsCountedBeforeItIsSent();
        flowIsReadIntoStretchesAndSpans();
        incidentsAreRead();
        bothErrorShapesAreRead();
        failuresNeverShowTheKey();
        gzipIsReadWithOrWithoutTheHeader();
        theMonthlyCapStopsAndResetsOnTheFirst();
        aSnapshotIsOnlyItsOwnBox();
        testMakesOneRequestForTheFixedBox();
        theBaseUrlOverrideIsDebugOnly();
        reviewFixes();
        System.out.println("jni-host-test: ok " + passed + " traffic client and parsing checks");
    }

    // ---- fakes ------------------------------------------------------------------------

    /** One canned answer, and every request it was asked for. */
    private static final class FakeHttp implements TrafficHttp {
        final List<String> urls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        final List<Integer> countedAtSend = new ArrayList<>();
        MemoryTrafficLedger ledger;
        int status = 200;
        String flow = FLOW;
        String incidents = INCIDENTS;
        String error;
        boolean gzipFlow = true;
        boolean sayGzip = true;
        IOException failWith;
        RuntimeException failRuntime;
        /** Run after the flow request is answered: the settings changing under a fetch. */
        Runnable afterFlow;

        @Override
        public TrafficResponse get(String url, Map<String, String> h) throws IOException {
            urls.add(url);
            headers.add(h);
            if (ledger != null) countedAtSend.add(ledger.status().getRequests());
            if (failWith != null) throw failWith;
            if (failRuntime != null) throw failRuntime;
            if (afterFlow != null && url.contains("/v7/flow?")) afterFlow.run();
            String body = status != 200 ? error : url.contains("/v7/flow?") ? flow : incidents;
            boolean zip = status == 200 && url.contains("/v7/flow?") && gzipFlow;
            byte[] bytes = zip ? gzip(body) : body.getBytes(StandardCharsets.UTF_8);
            return new TrafficResponse(status, zip && sayGzip ? "gzip" : null, new ByteArrayInputStream(bytes), () -> Unit.INSTANCE);
        }
    }

    private static byte[] gzip(String text) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
                zip.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static TrafficSettings settings(boolean on, String key, int limit) {
        return new TrafficSettings(on, key, 10, 30, limit, true, "");
    }

    private static TrafficClient client(TrafficSettings s, MemoryTrafficLedger ledger, FakeHttp http, long[] clock) {
        http.ledger = ledger;
        return new TrafficClient(() -> s, ledger, http, () -> 42L, () -> clock[0], false, ZoneOffset.UTC);
    }

    private static TrafficSnapshot fetched(TrafficFetch result) {
        check(result instanceof TrafficFetch.Fetched, "fetched: " + describe(result));
        return ((TrafficFetch.Fetched) result).getSnapshot();
    }

    private static String describe(TrafficFetch result) {
        if (result instanceof TrafficFetch.Failed) return "failed " + ((TrafficFetch.Failed) result).getMessage();
        if (result instanceof TrafficFetch.Skipped) return "skipped " + ((TrafficFetch.Skipped) result).getMessage();
        return "fetched";
    }

    // ---- settings ---------------------------------------------------------------------

    /** Plan section 3's defaults, the stored round trip, and the repair that keeps it off without a key. */
    private static void settingsStartOffAndRepair() {
        TrafficSettings d = new TrafficSettings();
        check(!d.getEnabled() && d.getApiKey().isEmpty() && !d.getUsable(), "off and keyless by default");
        check(d.getRefreshMinutes() == 10 && d.getLookAheadKm() == 30 && d.getMonthlyLimit() == 4500,
            "10 minutes, 30 km, 4,500 a month");
        check(!d.getDisclosed() && d.getBaseUrl().isEmpty(), "not yet disclosed; HERE's own host");
        check(TrafficSettings.Companion.fromStored(new HashMap<String, Object>()).equals(d), "an empty store reads as the defaults");
        TrafficSettings chosen = new TrafficSettings(true, KEY, 5, 50, 1200, true, "http://10.0.2.2:8765/");
        TrafficSettings back = TrafficSettings.Companion.fromStored(chosen.toStored());
        check(back.getEnabled() && back.getApiKey().equals(KEY) && back.getRefreshMinutes() == 5
            && back.getLookAheadKm() == 50 && back.getMonthlyLimit() == 1200 && back.getDisclosed(), "the stored form reads back");
        check(back.getBaseUrl().equals("http://10.0.2.2:8765"), "a trailing slash is dropped from the base URL");
        check(chosen.toStored().keySet().toString().equals("[traffic_enabled, traffic_api_key, traffic_refresh_minutes, "
            + "traffic_look_ahead_km, traffic_monthly_limit, traffic_disclosed, traffic_base_url]"), "the stored keys");

        Map<String, Object> odd = new HashMap<>();
        odd.put(TrafficSettings.ENABLED, true);
        odd.put(TrafficSettings.API_KEY, "  ");
        odd.put(TrafficSettings.REFRESH, 7);
        odd.put(TrafficSettings.LOOK_AHEAD, "30");
        odd.put(TrafficSettings.LIMIT, 1);
        odd.put(TrafficSettings.BASE_URL, "ftp://nowhere");
        TrafficSettings repaired = TrafficSettings.Companion.fromStored(odd);
        check(!repaired.getEnabled(), "on with a blank key is repaired to off");
        check(repaired.getRefreshMinutes() == 10 && repaired.getLookAheadKm() == 30 && repaired.getMonthlyLimit() == 4500,
            "values no list offers, or of the wrong type, read as defaults");
        check(repaired.getBaseUrl().isEmpty(), "a base URL that is not http(s) is dropped");
        check(TrafficSettings.Companion.cleanKey("  " + KEY + "\n").equals(KEY), "a pasted key is trimmed");
        check(TrafficSettings.Companion.cleanKey("two words") == null, "a key with a space is refused");
        check(TrafficSettings.Companion.cleanKey("") == null, "an empty key is refused");
        check(new TrafficSettings(true, "a b", 10, 30, 4500, true, "").repaired().getApiKey().isEmpty(),
            "a stored key that cannot be one is cleared, and traffic with it");
        check(!new TrafficSettings(true, "a b", 10, 30, 4500, true, "").repaired().getEnabled(), "and turned off");
        check(Integer.valueOf(4500).equals(TrafficSettings.Companion.parseLimit("4,500")), "the limit reads with a comma");
        check(TrafficSettings.Companion.parseLimit("1") == null && TrafficSettings.Companion.parseLimit("") == null
            && TrafficSettings.Companion.parseLimit("99999999999") == null, "a limit out of range is refused");
        check(!chosen.toString().contains(KEY) && chosen.toString().contains("apiKey=set"), "toString never shows the key");
    }

    // ---- the gate ---------------------------------------------------------------------

    /** Plan section 2 and section 9: off, or on with no key, and the HTTP layer is never called. */
    private static void offOrKeylessSendsNothing() {
        long[] clock = {SEPTEMBER};
        for (TrafficSettings s : new TrafficSettings[] {
            settings(false, KEY, 4500), settings(false, "", 4500), settings(true, "", 4500), settings(true, "   ", 4500),
            new TrafficSettings(),
        }) {
            FakeHttp http = new FakeHttp();
            MemoryTrafficLedger ledger = new MemoryTrafficLedger(new TrafficStatus());
            TrafficClient c = client(s, ledger, http, clock);
            TrafficFetch f = c.fetch(DALLAS);
            TrafficCheck t = c.test();
            check(f instanceof TrafficFetch.Skipped, "nothing fetched: " + s);
            TrafficSkip why = ((TrafficFetch.Skipped) f).getWhy();
            check(why == (s.getEnabled() ? TrafficSkip.NO_KEY : TrafficSkip.OFF), "skipped for the right reason: " + why);
            check(!t.getWorks(), "Test does nothing either: " + t.getMessage());
            check(c.gate(2) == why, "the gate says the same");
            check(http.urls.isEmpty(), "the HTTP layer was never called");
            check(ledger.status().getRequests() == 0 && ledger.status().getLastError() == null, "nothing counted or recorded");
        }
    }

    /** Plan section 4: flow then incidents, for the box, with the key and gzip asked for. */
    private static void aFetchIsExactlyTheTwoRequests() {
        long[] clock = {SEPTEMBER};
        FakeHttp http = new FakeHttp();
        MemoryTrafficLedger ledger = new MemoryTrafficLedger(new TrafficStatus());
        TrafficSnapshot snap = fetched(client(settings(true, KEY, 4500), ledger, http, clock).fetch(DALLAS));
        check(http.urls.size() == 2, "exactly two requests");
        check(http.urls.get(0).equals("https://data.traffic.hereapi.com/v7/flow?in=bbox:-96.85000,32.75000,-96.75000,32.80000"
            + "&locationReferencing=shape&functionalClasses=1,2,3&apiKey=" + KEY), "flow: " + http.urls.get(0));
        check(http.urls.get(1).equals("https://data.traffic.hereapi.com/v7/incidents?in=bbox:-96.85000,32.75000,-96.75000,32.80000"
            + "&locationReferencing=shape&apiKey=" + KEY), "incidents: " + http.urls.get(1));
        for (Map<String, String> h : http.headers) check("gzip".equals(h.get("Accept-Encoding")), "gzip asked for");
        check(snap.getBox().equals(DALLAS) && snap.getReceivedAtMs() == 42L, "the snapshot is the box asked for, stamped");
        check(snap.getFlow().size() == 3 && snap.getIncidents().size() == 2, "three stretches and two incidents read");
        TrafficStatus st = ledger.status();
        check(st.getRequests() == 2 && st.getMonth().equals("2026-09"), "two requests counted in September");
        check(st.getLastFetchMs() == SEPTEMBER && st.getLastError() == null, "the last fetch recorded");
    }

    /** Plan section 9: each request is in the count when it is sent, not after. */
    private static void everyRequestIsCountedBeforeItIsSent() {
        long[] clock = {SEPTEMBER};
        FakeHttp http = new FakeHttp();
        MemoryTrafficLedger ledger = new MemoryTrafficLedger(new TrafficStatus("2026-09", 10, 0L, null));
        TrafficClient c = client(settings(true, KEY, 4500), ledger, http, clock);
        fetched(c.fetch(DALLAS));
        check(http.countedAtSend.equals(List.of(11, 12)), "counted before each send: " + http.countedAtSend);
        http.failWith = new IOException("connection reset");
        TrafficFetch failed = c.fetch(DALLAS);
        check(failed instanceof TrafficFetch.Failed, "a request that fails is a failure");
        check(ledger.status().getRequests() == 13 && http.urls.size() == 3, "a request that failed is still counted, and incidents not sent");
    }

    // ---- parsing ----------------------------------------------------------------------

    private static void flowIsReadIntoStretchesAndSpans() throws IOException {
        List<FlowStretch> flow = TrafficJson.INSTANCE.flow(new StringReader(FLOW));
        check(flow.size() == 3, "results without a location or a currentFlow are dropped: " + flow.size());
        FlowStretch i35 = flow.get(0);
        check("I-35E".equals(i35.getDescription()) && i35.getLengthM() == 300.0, "description and length");
        check(i35.getLatitudes().length == 5 && i35.getLongitudes().length == 5, "links joined with the repeated joins dropped");
        check(i35.getLatitudes()[2] == 32.782 && i35.getLongitudes()[4] == -96.803, "points in order");
        check(i35.getFunctionalClass() == 1, "the lowest functional class of its links");
        List<FlowSpan> spans = i35.getSpans();
        check(spans.size() == 3, "one span per sub-segment");
        check(spans.get(0).getFromM() == 0 && spans.get(0).getToM() == 100 && spans.get(1).getFromM() == 100
            && spans.get(1).getToM() == 250 && spans.get(2).getFromM() == 250 && spans.get(2).getToM() == 300,
            "sub-segments laid end to end by their lengths");
        check(Double.valueOf(20.0).equals(spans.get(0).getSpeedMps()) && spans.get(0).getJamFactor() == 2.0
            && spans.get(0).getTraversability() == Traversability.OPEN, "a sub-segment's own figures");
        check(spans.get(1).getSpeedMps() == null && spans.get(1).getTraversability() == Traversability.CLOSED
            && spans.get(1).getJamFactor() == 10.0, "a closed sub-segment has no speed");
        check(Double.valueOf(25.0).equals(spans.get(1).getFreeFlowMps()), "a sub-segment without freeFlow takes its stretch's");
        check(spans.get(2).getJamFactor() == 5.5, "a sub-segment without a jam factor takes its stretch's");

        FlowStretch elm = flow.get(1);
        check("Elm St \"Main\" \u00e9".equals(elm.getDescription()), "escapes read: " + elm.getDescription());
        check(elm.getSpans().size() == 1 && elm.getSpans().get(0).getFromM() == 0 && elm.getSpans().get(0).getToM() == 80,
            "no sub-segments: one span over the whole stretch");
        check(elm.getSpans().get(0).getSpeedMps() == null && elm.getSpans().get(0).getTraversability() == Traversability.CLOSED,
            "speed missing on a closed stretch reads as null");
        FlowStretch reversible = flow.get(2);
        check(reversible.getDescription() == null, "a missing description reads as null");
        check(reversible.getFunctionalClass() == 5, "no functional class reads as 5, the most minor");
        check(reversible.getSpans().get(0).getTraversability() == Traversability.REVERSIBLE_NOT_ROUTABLE, "reversibleNotRoutable");
        check(TrafficJson.INSTANCE.traversability("somethingNew") == Traversability.OPEN, "an unknown traversability reads as open");
        check(TrafficJson.INSTANCE.flow(new StringReader("{\"results\":[]}")).isEmpty(), "no results");
        check(TrafficJson.INSTANCE.flow(new StringReader("{}")).isEmpty(), "no results array");
        boolean threw = false;
        try {
            TrafficJson.INSTANCE.flow(new StringReader("{\"results\":[{\"location\":"));
        } catch (IOException e) {
            threw = true;
        }
        check(threw, "a body cut short is an error, not an empty answer");
    }

    private static void incidentsAreRead() throws IOException {
        List<TrafficIncident> incidents = TrafficJson.INSTANCE.incidents(new StringReader(INCIDENTS));
        check(incidents.size() == 2, "an incident without a location is dropped");
        TrafficIncident closure = incidents.get(0);
        check(closure.getId().equals("1111") && closure.getType().equals("roadClosure"), "id and type");
        check("critical".equals(closure.getCriticality()) && closure.getRoadClosed(), "criticality and roadClosed");
        check("Closed".equals(closure.getSummary()), "summary preferred to description");
        check(closure.getLatitudes().length == 3, "its links joined");
        TrafficIncident works = incidents.get(1);
        check(works.getCriticality() == null && !works.getRoadClosed(), "missing criticality and roadClosed");
        check("Lane narrows".equals(works.getSummary()), "description when there is no summary");
    }

    /** Plan section 4: `{title, status, code, cause, action, correlationId}` and a 401's `{error, error_description}`. */
    private static void bothErrorShapesAreRead() {
        check("Malformed request: maximum width and height of a bounding box can be 1 degree (E608006)"
            .equals(TrafficJson.INSTANCE.error(BAD_REQUEST)), "the bad-request shape: " + TrafficJson.INSTANCE.error(BAD_REQUEST));
        check("Unauthorized: apiKey invalid. apiKey not found.".equals(TrafficJson.INSTANCE.error(BAD_KEY)), "the bad-key shape");
        check(TrafficJson.INSTANCE.error("<html>Bad gateway</html>") == null, "neither shape");
        check(TrafficJson.INSTANCE.error("{\"message\":\"x\"}") == null, "JSON of neither shape");

        long[] clock = {SEPTEMBER};
        FakeHttp http = new FakeHttp();
        http.status = 400;
        http.error = BAD_REQUEST;
        MemoryTrafficLedger ledger = new MemoryTrafficLedger(new TrafficStatus());
        TrafficFetch f = client(settings(true, KEY, 4500), ledger, http, clock).fetch(DALLAS);
        check(f instanceof TrafficFetch.Failed, "a 400 fails the fetch");
        TrafficFetch.Failed failed = (TrafficFetch.Failed) f;
        check(failed.getMessage().equals("HERE 400: Malformed request: maximum width and height of a bounding box can be 1 degree (E608006)"),
            "HERE's words: " + failed.getMessage());
        check(Integer.valueOf(400).equals(failed.getHttpStatus()), "and its status");
        check(http.urls.size() == 1, "incidents not asked for after flow failed");
        check(failed.getMessage().equals(ledger.status().getLastError()), "the last error recorded");
        check(ledger.status().getLastFetchMs() == 0L, "and no fetch");

        http.status = 401;
        http.error = BAD_KEY;
        TrafficCheck t = client(settings(true, KEY, 4500), ledger, http, clock).test();
        check(!t.getWorks() && t.getMessage().equals("HERE 401: Unauthorized: apiKey invalid. apiKey not found."), "Test says: " + t.getMessage());

        http.status = 503;
        http.error = "Service Unavailable";
        TrafficFetch plain = client(settings(true, KEY, 4500), ledger, http, clock).fetch(DALLAS);
        check(((TrafficFetch.Failed) plain).getMessage().equals("HERE 503: no explanation given"), "an error body of neither shape");

        http.status = 200;
        http.gzipFlow = false;
        http.flow = "{\"results\":[{\"location\":{\"shape\":";
        TrafficFetch cut = client(settings(true, KEY, 4500), ledger, http, clock).fetch(DALLAS);
        check(cut instanceof TrafficFetch.Failed && ((TrafficFetch.Failed) cut).getMessage().startsWith("HERE's answer could not be read"),
            "a body that is not JSON: " + describe(cut));
        TrafficStatus last = ledger.status();
        check(!last.describe(YearMonth.of(2026, 9), 4500, null).contains(KEY), "no status line holds the key");
    }

    private static void failuresNeverShowTheKey() {
        long[] clock = {SEPTEMBER};
        FakeHttp http = new FakeHttp();
        http.failWith = new IOException("refused: https://data.traffic.hereapi.com/v7/flow?apiKey=" + KEY + "&in=x");
        MemoryTrafficLedger ledger = new MemoryTrafficLedger(new TrafficStatus());
        TrafficFetch f = client(settings(true, KEY, 4500), ledger, http, clock).fetch(DALLAS);
        String message = ((TrafficFetch.Failed) f).getMessage();
        check(message.startsWith("Could not reach HERE: IOException") && !message.contains(KEY), "redacted: " + message);
        check(TrafficClient.Companion.redact("x apiKey=abc&y", "zzz").equals("x apiKey=[key]&y"), "an apiKey parameter is taken out");
        check(!ledger.status().getLastError().contains(KEY), "and never stored");
    }

    private static void gzipIsReadWithOrWithoutTheHeader() {
        long[] clock = {SEPTEMBER};
        for (boolean say : new boolean[] {true, false}) {
            FakeHttp http = new FakeHttp();
            http.sayGzip = say;
            TrafficSnapshot s = fetched(client(settings(true, KEY, 4500), new MemoryTrafficLedger(new TrafficStatus()), http, clock).fetch(DALLAS));
            check(s.getFlow().size() == 3, "gzipped flow read " + (say ? "by its Content-Encoding" : "by its magic bytes"));
        }
        FakeHttp plain = new FakeHttp();
        plain.gzipFlow = false;
        TrafficSnapshot s = fetched(client(settings(true, KEY, 4500), new MemoryTrafficLedger(new TrafficStatus()), plain, clock).fetch(DALLAS));
        check(s.getFlow().size() == 3 && s.getIncidents().size() == 2, "plain bodies read as they are");
    }

    // ---- the cap ----------------------------------------------------------------------

    /** Plan section 3: fetching stops at the limit until the next calendar month, and the card says so. */
    private static void theMonthlyCapStopsAndResetsOnTheFirst() {
        long[] clock = {SEPTEMBER};
        FakeHttp http = new FakeHttp();
        MemoryTrafficLedger ledger = new MemoryTrafficLedger(new TrafficStatus());
        TrafficClient c = client(settings(true, KEY, 4), ledger, http, clock);
        fetched(c.fetch(DALLAS));
        fetched(c.fetch(DALLAS));
        check(ledger.status().getRequests() == 4, "four requests under a limit of four");
        TrafficFetch third = c.fetch(DALLAS);
        check(third instanceof TrafficFetch.Skipped && ((TrafficFetch.Skipped) third).getWhy() == TrafficSkip.LIMIT, "the third is refused");
        check(((TrafficFetch.Skipped) third).getMessage().equals("Limit reached until 1 October"), "and says until when");
        check(!c.test().getWorks(), "Test is refused at the limit too");
        check(http.urls.size() == 4, "nothing sent at the limit");
        String line = ledger.status().describe(YearMonth.of(2026, 9), 4, "28/09/2026 10:00");
        check(line.equals("Last fetch: 28/09/2026 10:00\nRequests this month: 4 of 4\nLimit reached until 1 October"), "the card's status: " + line);

        clock[0] = Instant.parse("2026-09-30T23:59:59Z").toEpochMilli();
        check(c.fetch(DALLAS) instanceof TrafficFetch.Skipped && http.urls.size() == 4, "still refused on the last second of the month");
        clock[0] = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();
        fetched(c.fetch(DALLAS));
        check(http.urls.size() == 6, "the 1st: fetching resumes");
        check(ledger.status().getMonth().equals("2026-10") && ledger.status().getRequests() == 2, "counting from 0 in October");
        check(!ledger.status().atLimit(YearMonth.of(2026, 10), 4), "not at the limit in October");
        check(ledger.status().requestsIn(YearMonth.of(2026, 11)) == 0, "a later month reads as 0 without a write");
        check(TrafficStatus.Companion.limitReached(YearMonth.of(2026, 12)).equals("Limit reached until 1 January"), "December rolls into January");

        // Room for one request: Test may go, a fetch (two) may not start.
        FakeHttp one = new FakeHttp();
        MemoryTrafficLedger nearly = new MemoryTrafficLedger(new TrafficStatus("2026-09", 4, 0L, null));
        TrafficClient c5 = client(settings(true, KEY, 5), nearly, one, new long[] {SEPTEMBER});
        check(c5.fetch(DALLAS) instanceof TrafficFetch.Skipped && one.urls.isEmpty(), "a fetch never starts without room for both");
        check(c5.test().getWorks() && one.urls.size() == 1 && nearly.status().getRequests() == 5, "Test takes the last one");
        check(nearly.status().atLimit(YearMonth.of(2026, 9), 5), "and then the card says the limit is reached");
    }

    // ---- no reuse ---------------------------------------------------------------------

    /** Plan section 4, section 9: a response is only ever for the box it was fetched for, and never kept. */
    private static void aSnapshotIsOnlyItsOwnBox() {
        long[] clock = {SEPTEMBER};
        FakeHttp http = new FakeHttp();
        MemoryTrafficLedger ledger = new MemoryTrafficLedger(new TrafficStatus());
        TrafficClient c = client(settings(true, KEY, 4500), ledger, http, clock);
        TrafficSnapshot dallas = fetched(c.fetch(DALLAS));
        http.flow = "{\"results\":[]}";
        http.incidents = "{\"results\":[]}";
        TrafficSnapshot austin = fetched(c.fetch(AUSTIN));
        check(austin.getBox().equals(AUSTIN) && dallas.getBox().equals(DALLAS), "each snapshot carries its own box");
        check(austin.getFlow().isEmpty() && austin.getIncidents().isEmpty(), "Austin's answer is Austin's only");
        check(http.urls.get(2).contains("bbox:-97.78000,30.25000,-97.70000,30.30000"), "Austin's box was asked for");
        check(dallas.getFlow().size() == 3, "the earlier snapshot is untouched");
        http.status = 500;
        http.error = "{}";
        TrafficFetch failed = c.fetch(DALLAS);
        check(failed instanceof TrafficFetch.Failed, "a failed fetch is a failure, never an earlier answer");
        check(!dallas.getBox().contains(30.27, -97.74) && AUSTIN.contains(30.27, -97.74), "the boxes do not overlap");
    }

    private static void testMakesOneRequestForTheFixedBox() {
        long[] clock = {SEPTEMBER};
        FakeHttp http = new FakeHttp();
        MemoryTrafficLedger ledger = new MemoryTrafficLedger(new TrafficStatus());
        TrafficCheck t = client(settings(true, KEY, 4500), ledger, http, clock).test();
        check(t.getWorks() && t.getMessage().equals("works"), "Test works");
        check(http.urls.size() == 1 && http.urls.get(0).startsWith("https://data.traffic.hereapi.com/v7/incidents?in="
            + TrafficClient.Companion.getTEST_BOX().query() + "&locationReferencing=shape&apiKey="), "one incidents request: " + http.urls);
        check(ledger.status().getRequests() == 1 && ledger.status().getLastFetchMs() == 0L, "counted, and not a fetch");
    }

    /** Plan section 4, section 8: the lab's stand-in server only in a debuggable build. */
    private static void theBaseUrlOverrideIsDebugOnly() {
        long[] clock = {SEPTEMBER};
        TrafficSettings lab = new TrafficSettings(true, KEY, 10, 30, 4500, true, "http://10.0.2.2:8765");
        for (boolean debuggable : new boolean[] {false, true}) {
            FakeHttp http = new FakeHttp();
            MemoryTrafficLedger ledger = new MemoryTrafficLedger(new TrafficStatus());
            http.ledger = ledger;
            TrafficClient c = new TrafficClient(() -> lab, ledger, http, () -> 1L, () -> clock[0], debuggable, ZoneOffset.UTC);
            fetched(c.fetch(DALLAS));
            String expected = debuggable ? "http://10.0.2.2:8765/v7/flow?" : TrafficClient.HERE_BASE + "/v7/flow?";
            check(http.urls.get(0).startsWith(expected), (debuggable ? "debug: the stand-in" : "release: HERE, override ignored") + " " + http.urls.get(0));
        }
    }

    /** The code review's fixes to the client, the parser and the count (docs/p9-lab-results.md). */
    private static void reviewFixes() {
        long[] clock = {SEPTEMBER};
        // Fix 2: a debuggable build honours an override naming the lab's hosts only.
        String[][] overrides = {
            {"http://10.0.2.2:8765", "http://10.0.2.2:8765/v7/flow?"},
            {"http://127.0.0.1:8765", "http://127.0.0.1:8765/v7/flow?"},
            {"http://LOCALHOST:8765", "http://LOCALHOST:8765/v7/flow?"},
            {"https://evil.example", TrafficClient.HERE_BASE + "/v7/flow?"},
            {"http://10.0.2.2.evil.example:8765", TrafficClient.HERE_BASE + "/v7/flow?"},
            {"http://evil.example/10.0.2.2", TrafficClient.HERE_BASE + "/v7/flow?"},
            {"http://user@evil.example:80@10.0.2.2", TrafficClient.HERE_BASE + "/v7/flow?"},
        };
        for (String[] o : overrides) {
            FakeHttp http = new FakeHttp();
            MemoryTrafficLedger ledger = new MemoryTrafficLedger(new TrafficStatus());
            TrafficSettings s = new TrafficSettings(true, KEY, 10, 30, 4500, true, o[0]);
            TrafficClient c = new TrafficClient(() -> s, ledger, http, () -> 1L, () -> clock[0], true, ZoneOffset.UTC);
            fetched(c.fetch(DALLAS));
            check(http.urls.get(0).startsWith(o[1]), "override " + o[0] + " goes to " + http.urls.get(0));
            check((TrafficClient.Companion.labBase(s, true) != null) == !o[1].startsWith(TrafficClient.HERE_BASE),
                    "and the card says an override is in force exactly then: " + o[0]);
            check(TrafficClient.Companion.labBase(s, false) == null, "never in a release build");
        }

        // Fix 3: a coordinate that is no number HERE means, or off the globe, is dropped; the
        // rest of the stretch is kept, and a speed of Infinity is no speed.
        String odd = "{\"results\":[{\"location\":{\"length\":100.0,\"shape\":{\"links\":[{\"points\":["
                + "{\"lat\":32.78,\"lng\":-96.80},{\"lat\":\"NaN\",\"lng\":-96.80},{\"lat\":1e999,\"lng\":-96.80},"
                + "{\"lat\":95.0,\"lng\":-96.80},{\"lat\":32.78,\"lng\":-200.0},{\"lat\":\"Infinity\",\"lng\":-96.8},"
                + "{\"lat\":32.781,\"lng\":-96.801}],\"length\":100.0,\"functionalClass\":1}]}},"
                + "\"currentFlow\":{\"speed\":\"Infinity\",\"freeFlow\":25.0,\"jamFactor\":9.0,\"traversability\":\"open\"}}]}";
        try {
            List<FlowStretch> flow = TrafficJson.INSTANCE.flow(new StringReader(odd));
            check(flow.size() == 1 && flow.get(0).getLatitudes().length == 2, "only the two good points are kept: "
                    + (flow.isEmpty() ? 0 : flow.get(0).getLatitudes().length));
            check(flow.get(0).getSpans().get(0).getSpeedMps() == null, "an infinite speed is no speed");
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        // Fix 5: traffic turned off while the flow request is out: no incidents request after it.
        TrafficSettings[] now = {settings(true, KEY, 4500)};
        FakeHttp http = new FakeHttp();
        MemoryTrafficLedger ledger = new MemoryTrafficLedger(new TrafficStatus());
        http.ledger = ledger;
        TrafficClient c = new TrafficClient(() -> now[0], ledger, http, () -> 1L, () -> clock[0], false, ZoneOffset.UTC);
        http.afterFlow = () -> now[0] = settings(false, KEY, 4500);
        TrafficFetch off = c.fetch(DALLAS);
        check(off instanceof TrafficFetch.Skipped && ((TrafficFetch.Skipped) off).getWhy() == TrafficSkip.OFF, "turned off mid-fetch: " + describe(off));
        check(http.urls.size() == 1 && ledger.status().getRequests() == 1, "and the incidents request never went: " + http.urls.size());
        now[0] = settings(true, KEY, 4500);
        http.afterFlow = () -> now[0] = settings(true, "", 4500);
        check(c.fetch(DALLAS) instanceof TrafficFetch.Skipped && http.urls.size() == 2, "nor with the key removed mid-fetch");

        // Fix 10: an unchecked exception from the HTTP layer is a failed request, not a crash.
        now[0] = settings(true, KEY, 4500);
        http.afterFlow = null;
        http.failRuntime = new IllegalStateException("apiKey=" + KEY + " in a message");
        TrafficFetch thrown = c.fetch(DALLAS);
        check(thrown instanceof TrafficFetch.Failed && ((TrafficFetch.Failed) thrown).getMessage().equals("Could not reach HERE: IllegalStateException"),
                "a RuntimeException is a failure, in words without the key: " + describe(thrown));
        check(!c.test().getWorks(), "and Test says it did not work rather than throwing");
        http.failRuntime = null;

        // Fix 13: the clock set back into an earlier month does not give the cap a fresh month.
        TrafficStatus october = new TrafficStatus("2026-10", 4_499, 0L, null);
        check(october.requestsIn(YearMonth.of(2026, 9)) == 4_499, "a stored month ahead of the clock still counts");
        TrafficStatus more = october.reserve(YearMonth.of(2026, 9), 5_000, 1, 1);
        check(more != null && more.getMonth().equals("2026-10") && more.getRequests() == 4_500, "and a request is counted in it, not in September");
        MemoryTrafficLedger full = new MemoryTrafficLedger(new TrafficStatus("2026-10", 4_500, 0L, null));
        FakeHttp none = new FakeHttp();
        TrafficClient back = client(settings(true, KEY, 4_500), full, none, new long[] {SEPTEMBER});
        check(back.fetch(DALLAS) instanceof TrafficFetch.Skipped && none.urls.isEmpty(), "at the limit in October, set back to September: still refused");
        check(new TrafficStatus("2026-08", 4_500, 0L, null).requestsIn(YearMonth.of(2026, 9)) == 0, "an earlier month still resets");
        check(new TrafficStatus("garbage", 9, 0L, null).requestsIn(YearMonth.of(2026, 9)) == 0, "a month that does not parse counts nothing");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("TrafficClientTest: " + what);
        passed++;
    }
}
