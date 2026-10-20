// The wireless keep-awake's bookkeeping on a desktop JVM (spec 5.2, the keep-awake
// amendment): echo replies kept apart by screen state, the bucketed percentiles, and the
// Wi-Fi link readings, with Android's "unknown" values skipped. KeepAwakeStats and
// WifiLinkStats are pure Kotlin; the ICMP socket, the screen and the truck need a phone.
import java.util.List;
import com.jw6ventures.headlink.KeepAwakeStats;
import com.jw6ventures.headlink.WifiLinkStats;

public final class WirelessKeepAwakeTest {
    private static int passed;

    public static void main(String[] args) {
        empty();
        splitByScreen();
        percentiles();
        failures();
        wifiLink();
        System.out.println("jni-host-test: ok " + passed + " wireless keep-awake checks");
    }

    private static void empty() {
        List<String> lines = new KeepAwakeStats(true, KeepAwakeStats.ON_INTERVAL_MS).lines();
        is(lines.get(0), "keep_awake=on interval_ms=50", "on, every 50 ms");
        is(lines.get(1), "keep_awake_error=none", "no error");
        is(lines.get(3), "keep_awake_screen_on=sent=0 replies=0 p50_ms=- p95_ms=- max_ms=- over_100ms=0",
                "nothing sent reads as dashes");
        is(lines.get(4), "keep_awake_screen_on_buckets=<=5:0 <=10:0 <=20:0 <=50:0 <=100:0 <=200:0 <=500:0 <=1000:0"
                + " <=2000:0 >2000:0", "every bucket listed, the last open-ended");
        is(lines.size(), 7, "header, error, failures, then two lines per screen state");
        is(new KeepAwakeStats(false, KeepAwakeStats.OFF_INTERVAL_MS).lines().get(0), "keep_awake=off interval_ms=1000",
                "off measures once a second");
    }

    private static void splitByScreen() {
        KeepAwakeStats stats = new KeepAwakeStats(true, 50);
        for (int i = 0; i < 4; i++) stats.sent(true);
        for (int i = 0; i < 6; i++) stats.sent(false);
        stats.replied(true, 3);
        stats.replied(false, 700);
        is(stats.replies(true), 1, "screen-on replies");
        is(stats.replies(false), 1, "screen-off replies, by the screen when the echo went");
        List<String> lines = stats.lines();
        is(lines.get(3), "keep_awake_screen_on=sent=4 replies=1 p50_ms=<=5 p95_ms=<=5 max_ms=3 over_100ms=0", "on side");
        is(lines.get(5), "keep_awake_screen_off=sent=6 replies=1 p50_ms=<=1000 p95_ms=<=1000 max_ms=700 over_100ms=1",
                "off side");
    }

    private static void percentiles() {
        KeepAwakeStats stats = new KeepAwakeStats(true, 50);
        // 90 quick replies and 10 slow ones: the median is quick, the 95th is slow.
        for (int i = 0; i < 90; i++) stats.replied(false, 8);
        for (int i = 0; i < 10; i++) stats.replied(false, 900);
        is(stats.lines().get(5), "keep_awake_screen_off=sent=0 replies=100 p50_ms=<=10 p95_ms=<=1000 max_ms=900"
                + " over_100ms=10", "a slow tenth shows at the 95th percentile");
        is(stats.lines().get(6), "keep_awake_screen_off_buckets=<=5:0 <=10:90 <=20:0 <=50:0 <=100:0 <=200:0 <=500:0"
                + " <=1000:10 <=2000:0 >2000:0", "the buckets");
        // Boundaries are inclusive, and anything past the last bound lands in the open bucket.
        KeepAwakeStats edges = new KeepAwakeStats(true, 50);
        edges.replied(true, 5);
        edges.replied(true, 2001);
        is(edges.lines().get(4), "keep_awake_screen_on_buckets=<=5:1 <=10:0 <=20:0 <=50:0 <=100:0 <=200:0 <=500:0"
                + " <=1000:0 <=2000:0 >2000:1", "5 ms is <=5, 2001 ms is >2000");
        is(edges.lines().get(3), "keep_awake_screen_on=sent=0 replies=2 p50_ms=<=5 p95_ms=>2000 max_ms=2001"
                + " over_100ms=1", "the open bucket's label");
        // 100 ms itself is not over 100 ms.
        KeepAwakeStats hundred = new KeepAwakeStats(true, 50);
        hundred.replied(true, 100);
        check(hundred.lines().get(3).endsWith("over_100ms=0"), "100 ms is not over 100 ms");
    }

    private static void failures() {
        KeepAwakeStats stats = new KeepAwakeStats(true, 50);
        stats.sendFailed("ENETUNREACH (Network is unreachable)");
        stats.sendFailed("EPERM (Operation not permitted)");
        stats.unmatched();
        stats.setError("socket: EACCES (Permission denied)");
        List<String> lines = stats.lines();
        is(lines.get(1), "keep_awake_error=socket: EACCES (Permission denied)", "a socket error is reported");
        is(lines.get(2), "keep_awake_send_failures=2 last=EPERM (Operation not permitted) unmatched_replies=1",
                "send failures keep the last reason");
    }

    private static void wifiLink() {
        WifiLinkStats wifi = new WifiLinkStats();
        is(wifi.describe(), "wifi: no reading", "nothing read yet");
        is(wifi.lines().get(0), "wifi_link=samples=0 iface=- frequency_mhz=- standard=-", "empty link line");
        is(wifi.lines().get(1), "wifi_link_rssi_dbm=last=- min=- max=-", "empty signal");
        wifi.sample("wlan1", 5745, 6, -48, 866, 780);
        wifi.sample("wlan1", 5745, 6, -61, 433, 650);
        // Android's unknowns: no signal yet, no rates yet.
        wifi.sample("wlan1", 5745, 6, WifiLinkStats.INVALID_RSSI, -1, 0);
        is(wifi.describe(), "wifi wlan1 5745 MHz rssi -61 tx 433 rx 650 Mbps", "the latest valid reading");
        is(wifi.lines().get(0), "wifi_link=samples=3 iface=wlan1 frequency_mhz=5745 standard=11ax", "link line");
        is(wifi.lines().get(1), "wifi_link_rssi_dbm=last=-61 min=-61 max=-48", "unknown RSSI skipped");
        is(wifi.lines().get(2), "wifi_link_tx_mbps=last=433 min=433 max=866", "unknown rate skipped");
        is(wifi.lines().get(3), "wifi_link_rx_mbps=last=650 min=650 max=780", "zero rate skipped");
        wifi.sampleInterfaceOnly("wlan0");
        check(wifi.lines().get(0).startsWith("wifi_link=samples=4 iface=wlan0 "), "an interface-only reading counts");
        is(WifiLinkStats.Companion.standardName(8), "11be", "Wi-Fi 7");
        is(WifiLinkStats.Companion.standardName(9), "standard_9", "an unknown standard by number");
        for (String line : wifi.lines()) {
            check(!line.contains(":") || line.startsWith("wifi_link"), "no address-shaped value: " + line);
        }
    }

    private static void is(Object got, Object want, String what) {
        check(got.equals(want), what + ": got " + got + ", want " + want);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
    }
}
