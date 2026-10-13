// W0's wireless probe on a desktop JVM (docs/wireless-investigation.md): the verdict for
// each way an SDP query can end, the UUID labels, and what the saved report may contain.
// WirelessProbe is pure Kotlin; the SDP query itself, the broadcast it answers with and
// the permission prompt need a phone (WirelessProbeActivity, debug builds only).
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.openautoandroid.source.ReportStore;
import org.openautoandroid.source.WirelessProbe;

public final class WirelessProbeTest {
    private static int passed;
    private static final WirelessProbe PROBE = WirelessProbe.INSTANCE;
    private static final String AA = WirelessProbe.BOOTSTRAP_UUID;
    private static final String HFP_AG = "0000111f-0000-1000-8000-00805f9b34fb";
    private static final String A2DP_SINK = "0000110b-0000-1000-8000-00805f9b34fb";
    private static final String VENDOR = "12345678-9abc-def0-1234-56789abcdef0";
    /** An SDP answer that carried no UUIDs. */
    private static final List<String> NOTHING = null;
    private static final Instant AT = Instant.parse("2026-10-13T15:04:05Z");

    public static void main(String[] args) {
        verdicts();
        labels();
        report();
        System.out.println("jni-host-test: ok " + passed + " wireless probe checks");
    }

    /** SDP answers as the activity collects them; a null entry is an answer with no UUIDs. */
    private static List<List<String>> answers(List<String> first, List<String> second) {
        List<List<String>> all = new ArrayList<>();
        all.add(first);
        all.add(second);
        return all;
    }

    private static List<List<String>> answers(List<String> only) {
        List<List<String>> all = new ArrayList<>();
        all.add(only);
        return all;
    }

    private static List<List<String>> answers() {
        return new ArrayList<>();
    }

    private static void verdicts() {
        List<String> truck = List.of(HFP_AG, A2DP_SINK, AA);
        List<String> plain = List.of(HFP_AG, A2DP_SINK);
        is(PROBE.verdict(plain, answers(truck)), WirelessProbe.Verdict.ADVERTISED, "fresh answer lists it");
        // Upper case, as some stacks print UUIDs, still matches.
        is(PROBE.verdict(null, answers(List.of(AA.toUpperCase()))), WirelessProbe.Verdict.ADVERTISED, "upper-case UUID");
        // A second answer is enough: Android may answer from its cache first, then the query.
        is(PROBE.verdict(plain, answers(plain, truck)), WirelessProbe.Verdict.ADVERTISED, "second answer lists it");
        // A fresh answer without it outweighs a cache that had it: the head unit stopped offering it.
        is(PROBE.verdict(truck, answers(plain)), WirelessProbe.Verdict.ABSENT, "fresh answer lacks it");
        is(PROBE.verdict(truck, answers()), WirelessProbe.Verdict.CACHED_ONLY, "no answer, cache has it");
        // An answer that carried no UUIDs at all is not evidence of absence.
        is(PROBE.verdict(truck, answers(NOTHING)), WirelessProbe.Verdict.CACHED_ONLY, "empty-handed answer, cache has it");
        is(PROBE.verdict(null, answers(NOTHING)), WirelessProbe.Verdict.NO_ANSWER, "nothing anywhere");
        is(PROBE.verdict(plain, answers()), WirelessProbe.Verdict.NO_ANSWER, "no answer, cache lacks it");
    }

    private static void labels() {
        same(PROBE.describe(HFP_AG), "111f:hfp_gateway", "SIG UUID shortened and named");
        same(PROBE.describe("0000ABCD-0000-1000-8000-00805F9B34FB"), "abcd:unknown", "unnamed SIG UUID");
        same(PROBE.describe(AA), AA + ":aa_wireless_bootstrap", "bootstrap UUID");
        same(PROBE.describe(VENDOR), VENDOR + ":unknown", "vendor UUID kept whole");
    }

    private static void report() {
        WirelessProbe.Device truck = new WirelessProbe.Device(0x0420, 1, 2, 2, List.of(HFP_AG, A2DP_SINK));
        WirelessProbe.Sdp sdp = new WirelessProbe.Sdp(true, answers(List.of(HFP_AG, A2DP_SINK), List.of(HFP_AG, A2DP_SINK, AA, VENDOR)), 3_210L);
        String text = PROBE.report(AT, "0.4.5-lab", 36, truck, sdp);
        line(text, "[wireless_probe]");
        line(text, "probe_utc=2026-10-13T15:04:05Z");
        line(text, "device_class=0x0420 (car_audio)");
        line(text, "device_type=classic");
        line(text, "hfp=connected");
        line(text, "a2dp=connected");
        line(text, "cached_uuids=111f:hfp_gateway 110b:a2dp_sink");
        line(text, "sdp_answers=2");
        line(text, "sdp_answer_1_matches_cache=true");
        line(text, "sdp_answer_2=111f:hfp_gateway 110b:a2dp_sink " + AA + ":aa_wireless_bootstrap " + VENDOR + ":unknown");
        line(text, "sdp_answer_2_matches_cache=false");
        line(text, "aa_wireless_in_cache=false");
        line(text, "aa_wireless_in_sdp=true");
        line(text, "verdict=advertised");
        // The main screen treats a newest report without this line as an attempt cut short.
        check(text.endsWith("attempt_complete=true\n"), "report ends complete");

        WirelessProbe.Device unknown = new WirelessProbe.Device(null, 0, null, null, null);
        String empty = PROBE.report(AT, "x", 33, unknown, new WirelessProbe.Sdp(false, answers(), 0L));
        line(empty, "device_class=unknown");
        line(empty, "hfp=unknown");
        line(empty, "cached_uuids=none");
        line(empty, "sdp_started=false");
        line(empty, "sdp_answers=0");
        line(empty, "verdict=no_answer");

        // Spec §10: no address can appear, because none is ever passed in.
        check(!Pattern.compile("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matcher(text + empty).find(), "no MAC-shaped text");
        check(ReportStore.Companion.getNAME().matches(PROBE.reportName(AT, "ABCDEF12-3456")), "report name fits the store");
    }

    private static void line(String text, String expected) {
        check(text.lines().anyMatch(expected::equals), "report line " + expected + " in:\n" + text);
    }

    private static void is(WirelessProbe.Verdict actual, WirelessProbe.Verdict expected, String what) {
        check(actual == expected, what + ": " + actual);
    }

    private static void same(String actual, String expected, String what) {
        check(expected.equals(actual), what + ": " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
}
