// Host JVM test of the app's compiled Kotlin TrustStore (pure JVM): head-unit pin
// persistence, validation, bounded retention, and which native reports may add a pin.
// It does not exercise Android storage.
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import com.jw6ventures.headlink.NativeBridgeKt;
import com.jw6ventures.headlink.TrustStore;

public final class TrustStoreTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        File dir = new File(Files.createTempDirectory("trust-store-test").toFile(), "identity");
        TrustStore store = new TrustStore(dir);
        check(store.pins().isEmpty(), "no pins before any write");
        String a = "a".repeat(64);
        String b = "b".repeat(64);
        check(store.addPin(a), "first pin added");
        check(!store.addPin(a), "duplicate pin not added");
        check(store.addPin(b), "second pin added");
        check(store.pins().equals(List.of(a, b)), "pins persist in order: " + store.pins());
        check(new TrustStore(dir).pins().equals(List.of(a, b)), "pins reload from disk");
        for (String bad : List.of("A".repeat(64), "a".repeat(63), "../x")) {
            try {
                store.addPin(bad);
                check(false, "invalid pin accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                passed++;
            }
        }
        for (int i = 0; i < 10; i++) store.addPin(String.format("%064x", i));
        List<String> pins = store.pins();
        check(pins.size() == TrustStore.PIN_LIMIT && pins.get(pins.size() - 1).equals(String.format("%064x", 9)),
                "newest pins retained: " + pins);
        Files.writeString(new File(dir, TrustStore.PIN_FILE).toPath(), "junk\n" + a + "\n", StandardCharsets.US_ASCII);
        check(store.pins().equals(List.of(a)), "malformed lines ignored");
        store.clearPins();
        check(store.pins().isEmpty(), "pins cleared");
        check(!new File(dir, TrustStore.PIN_FILE + ".tmp").exists(), "no temporary file remains");

        String fp = "c".repeat(64);
        check(fp.equals(accepted("tls_established=true\ntls_peer_decision=first-use-accepted " + fp + "\n")),
                "completed first-use handshake yields its fingerprint");
        check(accepted("tls_established=false\ntls_peer_decision=first-use-accepted " + fp + "\n") == null,
                "no pin without a completed handshake");
        check(accepted("tls_established=true\ntls_peer_decision=pin-match " + fp + "\n") == null,
                "only first-use decisions add pins");
        check(accepted("tls_established=true\ntls_peer_decision=first-use-accepted XYZ\n") == null,
                "malformed fingerprint ignored");
        System.out.println("trust-store-test: ok " + passed + " checks");
    }

    static String accepted(String report) {
        Map<String, String> fields = NativeBridgeKt.parseReport(report.getBytes(StandardCharsets.UTF_8));
        return TrustStore.Companion.acceptedFirstUse(fields);
    }

    static void check(boolean condition, String what) {
        if (!condition) {
            System.err.println("trust-store-test: FAIL " + what);
            System.exit(1);
        }
        passed++;
    }
}
