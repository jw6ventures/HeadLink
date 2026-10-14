// W1's wireless bootstrap read on a desktop JVM (docs/wireless-investigation.md): framing
// across arbitrary RFCOMM chunks, the protobuf reader, the redaction rule, and the session
// against scripted head units in the shapes the two head-unit-side projects describe -
// WifiStartRequest first (WirelessAndroidAutoDongle), WifiVersionRequest first with head
// unit details (aa-proxy-rs's MBUX traces), an endpoint only in the version request, pings,
// and silence. Asserts that no key, SSID, BSSID or VIN reaches the report. The RFCOMM
// socket and the truck's real messages need a phone and the truck.
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.openautoandroid.source.ReportStore;
import org.openautoandroid.source.WirelessBootstrap;
import org.openautoandroid.source.WirelessProbe;

public final class WirelessBootstrapTest {
    private static int passed;
    private static final WirelessBootstrap WB = WirelessBootstrap.INSTANCE;
    private static final Instant AT = Instant.parse("2026-10-15T15:04:05Z");
    private static final byte[] SALT = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String KEY = "correct-horse-battery";
    private static final String SSID = "SYNC-1A2B";
    private static final String BSSID = "a6:5e:60:12:34:56";
    private static final String VIN = "1FTFW1E50PFA12345";

    public static void main(String[] args) {
        framing();
        protobuf();
        redaction();
        startFirst();
        versionFirst();
        versionEndpointOnly();
        repeatedStart();
        ping();
        silent();
        failures();
        System.out.println("jni-host-test: ok " + passed + " wireless bootstrap checks");
    }

    // ---- protobuf helpers for scripting the head unit ----

    private static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static final class Proto {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Proto num(int field, long value) {
            varint(out, ((long) field << 3));
            varint(out, value);
            return this;
        }

        Proto bytes(int field, byte[] value) {
            varint(out, ((long) field << 3) | 2);
            varint(out, value.length);
            out.writeBytes(value);
            return this;
        }

        Proto str(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        Proto msg(int field, Proto value) {
            return bytes(field, value.build());
        }

        byte[] build() {
            return out.toByteArray();
        }
    }

    private static WirelessBootstrap.Frame frame(int id, Proto payload) {
        return new WirelessBootstrap.Frame(id, payload.build());
    }

    private static WirelessBootstrap.Session session() {
        return new WirelessBootstrap.Session(new WirelessBootstrap.Redactor(SALT));
    }

    private static Proto infoResponse() {
        return new Proto().str(1, SSID).str(2, KEY).str(3, BSSID).num(4, 8).num(5, 1);
    }

    private static String report(WirelessBootstrap.Session s) {
        return WB.report(AT, "0.4.5-lab", 37, 0x0408, 1234L, null, s);
    }

    // ---- checks ----

    private static void framing() {
        byte[] payload = new Proto().str(1, "192.168.1.1").num(2, 5288).build();
        byte[] wire = WB.encode(new WirelessBootstrap.Frame(WirelessBootstrap.START_REQUEST, payload));
        check(wire.length == payload.length + 4, "frame length");
        check(wire[0] == 0 && wire[1] == payload.length && wire[2] == 0 && wire[3] == 1, "frame header big-endian");
        // One byte at a time, as a slow RFCOMM read might deliver it.
        WirelessBootstrap.Deframer d = new WirelessBootstrap.Deframer();
        int frames = 0;
        for (int i = 0; i < wire.length; i++) {
            List<WirelessBootstrap.Frame> got = d.push(new byte[] {wire[i]}, 1);
            frames += got.size();
            if (!got.isEmpty()) {
                check(got.get(0).getId() == 1, "deframed id");
                check(java.util.Arrays.equals(got.get(0).getPayload(), payload), "deframed payload");
            }
        }
        check(frames == 1 && d.pending() == 0, "one frame from single bytes");
        // Two frames and a partial third in one read; the read buffer is longer than the data.
        byte[] empty = WB.encode(new WirelessBootstrap.Frame(WirelessBootstrap.INFO_REQUEST, new byte[0]));
        byte[] chunk = new byte[64];
        System.arraycopy(wire, 0, chunk, 0, wire.length);
        System.arraycopy(empty, 0, chunk, wire.length, empty.length);
        System.arraycopy(wire, 0, chunk, wire.length + empty.length, 3);
        List<WirelessBootstrap.Frame> got = d.push(chunk, wire.length + empty.length + 3);
        check(got.size() == 2 && got.get(1).getId() == 2 && got.get(1).getPayload().length == 0, "two frames in one read");
        check(d.pending() == 3, "partial third held back");
    }

    private static void protobuf() {
        check(WB.fields(new byte[] {(byte) 0x08}) == null, "truncated varint is malformed");
        check(WB.fields(new byte[] {0x0a, 0x05, 0x41}) == null, "overlong length is malformed");
        check(WB.fields(new byte[0]).isEmpty(), "empty message has no fields");
        WirelessBootstrap.Redactor r = new WirelessBootstrap.Redactor(SALT);
        String dump = WB.dump(new Proto().num(1, 1).msg(4, new Proto().str(1, "Ford").str(4, VIN)).build(), r, 0);
        check(dump.startsWith("1=1 4={1=\"Ford\" 4=h:"), "nested dump: " + dump);
        check(!dump.contains(VIN), "VIN hashed in nested dump");
        check(WB.dump(new byte[] {(byte) 0xff}, r, 0).equals("malformed len=1"), "malformed dump");
        check(WB.dump(new byte[0], r, 0).equals("empty"), "empty dump");
    }

    private static void redaction() {
        for (String yes : List.of(VIN, "4de17a00-52cb-11e6-bdf4-0800200c9a66", BSSID, "R58N12ABCDE", "356938035643809")) {
            check(WB.looksIdentifying(yes), "identifying: " + yes);
        }
        for (String no : List.of("SYNC4_22198", "3.4.1", "Ford", "F-150", "2023", "SYNC 4", "192.168.1.1")) {
            check(!WB.looksIdentifying(no), "not identifying: " + no);
        }
        WirelessBootstrap.Redactor a = new WirelessBootstrap.Redactor(SALT);
        WirelessBootstrap.Redactor b = new WirelessBootstrap.Redactor("fedcba9876543210".getBytes(StandardCharsets.UTF_8));
        check(a.hash(SSID).equals(a.hash(SSID)), "hash stable for one salt");
        check(!a.hash(SSID).equals(b.hash(SSID)), "hash differs between salts");
        check(a.hash(SSID).matches("h:[0-9a-f]{12}"), "hash shape");
    }

    /** WirelessAndroidAutoDongle's order: WifiStartRequest, our WifiInfoRequest, WifiInfoResponse. */
    private static void startFirst() {
        WirelessBootstrap.Session s = session();
        check(s.tick(50).isEmpty(), "nothing to say before the head unit speaks");
        List<WirelessBootstrap.Frame> out = s.received(frame(1, new Proto().str(1, "192.168.1.1").num(2, 5288)), 100);
        check(out.size() == 1 && out.get(0).getId() == WirelessBootstrap.INFO_REQUEST && out.get(0).getPayload().length == 0,
                "WifiStartRequest answered with an empty WifiInfoRequest");
        check(s.received(frame(3, infoResponse()), 300).isEmpty(), "nothing sent after WifiInfoResponse");
        check(s.tick(2000).isEmpty() && !s.getDone(), "lingers after WifiInfoResponse");
        s.tick(2300);
        check(s.getDone(), "done after lingering");
        check(s.outcome() == WirelessBootstrap.Outcome.WIFI_DETAILS, "outcome wifi_details");
        String text = report(s);
        line(text, "start_endpoint=192.168.1.1:5288");
        line(text, "wifi_ssid=" + new WirelessBootstrap.Redactor(SALT).hash(SSID));
        line(text, "wifi_ssid_length=9");
        line(text, "wifi_ssid_wifi_direct=false");
        line(text, "wifi_bssid=" + new WirelessBootstrap.Redactor(SALT).hash(BSSID));
        line(text, "wifi_bssid_locally_administered=true");
        line(text, "wifi_key_length=" + KEY.length());
        line(text, "wifi_security=wpa2_personal");
        line(text, "wifi_ap_type=dynamic");
        line(text, "wifi_other_fields=none");
        line(text, "frames=in_WifiInfoResponse=1 in_WifiStartRequest=1 out_WifiInfoRequest=1");
        line(text, "outcome=wifi_details");
        secretsAbsent(text);
        check(text.endsWith("attempt_complete=true\n"), "report ends complete");
    }

    /** aa-proxy-rs's MBUX shape: WifiVersionRequest with head unit details first. */
    private static void versionFirst() {
        WirelessBootstrap.Session s = session();
        Proto hu = new Proto().str(1, "Ford").str(2, "F-150").str(3, "2023").str(4, VIN).str(6, "SYNC 4").str(8, "SYNC4_22198");
        List<WirelessBootstrap.Frame> out = s.received(frame(4, new Proto().num(1, 1).num(2, 2).num(3, 149).msg(5, hu)), 40);
        check(out.size() == 1 && out.get(0).getId() == WirelessBootstrap.VERSION_RESPONSE, "WifiVersionRequest answered");
        check(java.util.Arrays.equals(out.get(0).getPayload(), new byte[] {0x08, 0x01, 0x10, 0x02, 0x20, 0x00}),
                "WifiVersionResponse echoes 1.2 with status 0");
        out = s.received(frame(1, new Proto().str(1, "192.168.4.1").num(2, 5288)), 500);
        check(out.size() == 1 && out.get(0).getId() == WirelessBootstrap.INFO_REQUEST, "then WifiStartRequest answered");
        s.received(frame(3, infoResponse()), 700);
        s.tick(2800);
        String text = report(s);
        line(text, "hu_version=1.2");
        line(text, "version_endpoint=none");
        check(text.contains("\"Ford\"") && text.contains("\"SYNC4_22198\"") && text.contains("\"F-150\""), "head unit details readable");
        check(text.contains("hex=080110022000"), "our version response recorded in hex");
        check(!text.contains(VIN), "VIN not in the report");
        secretsAbsent(text);
    }

    /** No WifiStartRequest: the endpoint only inside WifiVersionRequest, so we ask anyway after the wait. */
    private static void versionEndpointOnly() {
        WirelessBootstrap.Session s = session();
        s.received(frame(4, new Proto().num(1, 1).num(2, 0).msg(5, new Proto().str(1, "10.0.0.1").num(2, 5000))), 100);
        check(s.tick(100 + WirelessBootstrap.START_WAIT_MS - 1).isEmpty(), "waits for WifiStartRequest");
        List<WirelessBootstrap.Frame> out = s.tick(100 + WirelessBootstrap.START_WAIT_MS);
        check(out.size() == 1 && out.get(0).getId() == WirelessBootstrap.INFO_REQUEST, "asks after the wait");
        s.tick(WirelessBootstrap.DEADLINE_MS);
        check(s.getDone() && s.outcome() == WirelessBootstrap.Outcome.START_ONLY, "start_only without Wi-Fi details");
        line(report(s), "version_endpoint=10.0.0.1:5000");
    }

    /** A head unit that repeats WifiStartRequest still gets at most INFO_ATTEMPTS WifiInfoRequests (spec 5.2, W1). */
    private static void repeatedStart() {
        WirelessBootstrap.Session s = session();
        int asked = 0;
        for (long t = 0; t <= WirelessBootstrap.DEADLINE_MS; t += 100) {
            List<WirelessBootstrap.Frame> out = t % 1000 == 0
                    ? s.received(frame(1, new Proto().str(1, "192.168.1.1").num(2, 5288)), t)
                    : s.tick(t);
            for (WirelessBootstrap.Frame f : out) if (f.getId() == WirelessBootstrap.INFO_REQUEST) asked++;
        }
        check(asked == WirelessBootstrap.INFO_ATTEMPTS, "capped WifiInfoRequests: " + asked);
    }

    private static void ping() {
        WirelessBootstrap.Session s = session();
        byte[] payload = new Proto().num(1, 1_760_000_000_000L).build();
        List<WirelessBootstrap.Frame> out = s.received(new WirelessBootstrap.Frame(WirelessBootstrap.PING_REQUEST, payload), 10);
        check(out.size() == 1 && out.get(0).getId() == WirelessBootstrap.PING_RESPONSE
                && java.util.Arrays.equals(out.get(0).getPayload(), payload), "ping echoed");
        check(s.outcome() == WirelessBootstrap.Outcome.TALKED, "a ping alone is talked");
    }

    /** A head unit that says nothing: one nudge at 5 s, two retries, then the deadline. */
    private static void silent() {
        WirelessBootstrap.Session s = session();
        int asked = 0;
        for (long t = 0; t <= WirelessBootstrap.DEADLINE_MS; t += 100) {
            for (WirelessBootstrap.Frame f : s.tick(t)) {
                check(f.getId() == WirelessBootstrap.INFO_REQUEST, "only WifiInfoRequest when silent");
                asked++;
            }
        }
        check(asked == WirelessBootstrap.INFO_ATTEMPTS, "nudge plus retries: " + asked);
        check(s.getDone() && s.outcome() == WirelessBootstrap.Outcome.SILENT, "silent outcome");
        String text = report(s);
        check(text.contains("nudge: nothing received"), "nudge recorded");
        line(text, "outcome=silent");
    }

    private static void failures() {
        WirelessBootstrap.Session s = session();
        s.received(frame(1, new Proto().str(1, "192.168.1.1").num(2, 5288)), 100);
        s.closed(900, "read failed: java.io.IOException: bt socket closed");
        check(s.getDone() && s.received(frame(3, infoResponse()), 950).isEmpty(), "nothing after close");
        check(s.outcome() == WirelessBootstrap.Outcome.START_ONLY, "closed after start: start_only");
        // A WifiInfoResponse that is not protobuf is recorded, never dumped.
        WirelessBootstrap.Session m = session();
        m.received(new WirelessBootstrap.Frame(WirelessBootstrap.INFO_RESPONSE, KEY.getBytes(StandardCharsets.UTF_8)), 10);
        String bad = report(m);
        check(bad.contains("not well-formed") && !bad.contains(KEY), "malformed WifiInfoResponse kept out");
        String failed = WB.report(AT, "0.4.5-lab", 37, null, 15000L, "java.io.IOException: read failed", session());
        line(failed, "rfcomm_connect_error=java.io.IOException: read failed");
        line(failed, "device_class=unknown");
        line(failed, "outcome=connect_failed");
        check(ReportStore.Companion.getNAME().matches(WirelessProbe.INSTANCE.reportName(AT, "w1abcdef")), "report name fits the store");
    }

    private static void secretsAbsent(String text) {
        check(!text.contains(KEY), "no key");
        check(!text.contains(SSID), "no SSID");
        check(!text.contains(BSSID) && !text.contains("a6:5e"), "no BSSID");
        check(!Pattern.compile("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matcher(text).find(), "no MAC-shaped text");
    }

    private static void line(String text, String expected) {
        check(text.lines().anyMatch(expected::equals), "report line " + expected + " in:\n" + text);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
}
