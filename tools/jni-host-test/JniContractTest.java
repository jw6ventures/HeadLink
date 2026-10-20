// JNI contract test on a desktop JVM (HotSpot, -Xcheck:jni). Loads the app's compiled
// Kotlin NativeBridge/SessionCallback classes and a host (x86_64) build of libaap_jni,
// then drives nativeStart/nativeStop/nativeGetReport over Unix socket pairs standing in
// for the accessory descriptor. Proves registration signatures, descriptor ownership,
// callback thread attachment, exception containment, and stop/join behavior on this
// JVM. It does not prove ART, Android, or accessory-driver behavior.
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.jw6ventures.headlink.NativeBridge;
import com.jw6ventures.headlink.NativeBridgeKt;
import com.jw6ventures.headlink.SessionCallback;
import com.jw6ventures.headlink.SessionSettings;

public final class JniContractTest {
    private static final NativeBridge BRIDGE = NativeBridge.INSTANCE;
    /** Java channel objects whose descriptors were handed to native; never closed here. */
    private static final List<SocketChannel> TRANSFERRED = new ArrayList<>();
    private static long generation = 1;
    private static int passed;

    private static final class Pair {
        final int fd;
        final SocketChannel peer;

        Pair(int fd, SocketChannel peer) {
            this.fd = fd;
            this.peer = peer;
        }
    }

    private static final class Recorder implements SessionCallback {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch finished = new CountDownLatch(1);
        volatile String finalReport;
        volatile boolean offMainThread = true;
        final Thread main = Thread.currentThread();

        @Override
        public void onNativeEvent(long gen, int kind, byte[] report) {
            if (Thread.currentThread() == main) offMainThread = false;
            String text = new String(report, StandardCharsets.UTF_8);
            events.add(gen + ":" + kind);
            if (kind == NativeBridge.EVENT_FINISHED) {
                finalReport = text;
                finished.countDown();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        check(BRIDGE.getLoadError() == null, "library loads and registers natives: " + BRIDGE.getLoadError());
        check(BRIDGE.probe().lines().anyMatch("self_test=pass"::equals), "probe self-test passes");
        check(BRIDGE.probe().lines().anyMatch(l -> l.startsWith("tls_library=OpenSSL 3.5.8 ")),
                "probe reports the pinned OpenSSL");
        byte[] request = fixture("protocol/fixtures/version-request.hex");
        byte[] response = fixture("protocol/fixtures/version-response.hex");

        versionExchange(request, response);
        stopCancelsBlockedSession();
        rejectedStartsCloseDescriptor();
        callbackExceptionsAreContained();
        stopFromCallbackDoesNotDeadlock();
        identityAndTlsConfiguration();
        videoSubmissionContract();

        BRIDGE.stop(987654321L);
        check(BRIDGE.report(987654321L) == null, "unknown handle: stop ignored, report null");
        check(nativeThreads().isEmpty(), "no aap-* native threads remain: " + nativeThreads());
        System.out.println("jni-host-test: ok " + passed + " checks");
    }

    static void versionExchange(byte[] request, byte[] response) throws Exception {
        Pair pair = pair();
        Recorder recorder = new Recorder();
        long gen = generation++;
        long handle = BRIDGE.start(pair.fd, config(gen), recorder);
        check(handle == gen, "start returns the generation as handle: " + handle);
        write(pair.peer, request);
        check(Arrays.equals(read(pair.peer, response.length), response), "fixture version response");
        write(pair.peer, HexFormat.of().parseHex("0003000700031603010004"));
        check(recorder.finished.await(5, TimeUnit.SECONDS), "Finished delivered");
        String report = recorder.finalReport;
        for (String line : List.of("final_category=STAGE_LIMIT", "last_completed_stage=version",
                "callback_failures=0", "io_descriptor=closed", "generation=" + gen)) {
            check(report.lines().anyMatch(line::equals), "report has " + line + "\n" + report);
        }
        check(recorder.offMainThread, "callbacks run on the native session thread");
        check(recorder.events.get(recorder.events.size() - 1).equals(gen + ":2"), "Finished is last");
        check(recorder.events.stream().allMatch(e -> e.startsWith(gen + ":")), "events carry generation");
        check(NativeBridgeKt.parseReport(BRIDGE.report(handle)).get("final_category").equals("STAGE_LIMIT"),
                "report snapshot before stop");
        expectEof(pair.peer);
        BRIDGE.stop(handle);
        check(BRIDGE.report(handle) == null, "handle released by stop");
        BRIDGE.stop(handle);
        pair.peer.close();
    }

    static void stopCancelsBlockedSession() throws Exception {
        Pair pair = pair();
        Recorder recorder = new Recorder();
        long gen = generation++;
        check(BRIDGE.start(pair.fd, config(gen), recorder) == gen, "silent-peer start");
        Thread.sleep(200);
        check(recorder.finished.getCount() == 1, "session still running while peer is silent");
        long started = System.nanoTime();
        BRIDGE.stop(gen);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        check(elapsedMs < 1000, "stop interrupts the blocked read within 1 s: " + elapsedMs + " ms");
        check(recorder.finished.getCount() == 0, "Finished delivered before stop returned");
        check(recorder.finalReport.contains("final_category=CANCELLED"), "cancelled report");
        check(recorder.finalReport.contains("io_reader=joined"), "reader joined");
        expectEof(pair.peer);
        pair.peer.close();
    }

    static void rejectedStartsCloseDescriptor() throws Exception {
        check(BRIDGE.start(-1, config(generation++), new Recorder()) == NativeBridge.START_INVALID_DESCRIPTOR,
                "negative descriptor rejected");

        Pair bad = pair();
        check(BRIDGE.start(bad.fd, "generation=1\n".getBytes(StandardCharsets.US_ASCII), new Recorder())
                == NativeBridge.START_INVALID_CONFIG, "invalid config rejected");
        expectEof(bad.peer);

        Pair active = pair();
        long gen = generation++;
        check(BRIDGE.start(active.fd, config(gen), new Recorder()) == gen, "active session");
        Pair busy = pair();
        check(BRIDGE.start(busy.fd, config(generation++), new Recorder()) == NativeBridge.START_BUSY,
                "second session rejected as busy");
        expectEof(busy.peer);
        BRIDGE.stop(gen);
        expectEof(active.peer);

        Pair stale = pair();
        check(BRIDGE.start(stale.fd, config(gen), new Recorder()) == NativeBridge.START_INVALID_CONFIG,
                "non-increasing generation rejected");
        expectEof(stale.peer);
        // A null callback cannot reach native: Kotlin's non-null check throws first.
        for (Pair pair : List.of(bad, active, busy, stale)) pair.peer.close();
    }

    static void callbackExceptionsAreContained() throws Exception {
        Pair pair = pair();
        CountDownLatch finished = new CountDownLatch(1);
        long gen = generation++;
        SessionCallback throwing = (g, kind, report) -> {
            if (kind == NativeBridge.EVENT_FINISHED) finished.countDown();
            throw new IllegalStateException("expected test exception from callback (kind " + kind + ")");
        };
        check(BRIDGE.start(pair.fd, config(gen), throwing) == gen, "throwing-callback start");
        pair.peer.close();
        check(finished.await(5, TimeUnit.SECONDS), "session finishes despite callback exceptions");
        String report = new String(BRIDGE.report(gen), StandardCharsets.UTF_8);
        check(report.contains("final_category=IO_EOF"), "EOF classified\n" + report);
        check(!report.contains("callback_failures=0"), "callback failures counted\n" + report);
        BRIDGE.stop(gen);
    }

    static void stopFromCallbackDoesNotDeadlock() throws Exception {
        Pair pair = pair();
        CountDownLatch finished = new CountDownLatch(1);
        long gen = generation++;
        SessionCallback selfStop = (g, kind, report) -> {
            if (kind == NativeBridge.EVENT_PROGRESS) BRIDGE.stop(g);
            else finished.countDown();
        };
        check(BRIDGE.start(pair.fd, config(gen), selfStop) == gen, "self-stop start");
        check(finished.await(5, TimeUnit.SECONDS), "stop from callback cancels without deadlock");
        check(new String(BRIDGE.report(gen), StandardCharsets.UTF_8).contains("final_category=CANCELLED"),
                "self-stop cancelled");
        expectEof(pair.peer);
        BRIDGE.stop(gen);
        pair.peer.close();
    }

    /** Identity preparation, the in-memory TLS self-test, and TLS start configuration. */
    /**
     * The encoder-facing natives refuse anything they cannot copy safely, and stale handles
     * never reach a session. Streaming itself is covered by the Rust session tests.
     */
    static void videoSubmissionContract() {
        ByteBuffer direct = ByteBuffer.allocateDirect(64);
        direct.put(new byte[] {0, 0, 0, 1, 0x65, 0x11});
        check(BRIDGE.submitVideo(987654321L, 1, 0L, direct, 0, 6) == NativeBridge.SUBMIT_UNKNOWN_HANDLE,
                "submitVideo on an unknown handle is refused");
        check(BRIDGE.submitVideo(987654321L, 1, 0L, ByteBuffer.allocate(64), 0, 6)
                == NativeBridge.SUBMIT_INVALID_BUFFER, "a buffer that is not direct is refused");
        check(BRIDGE.submitVideo(987654321L, 1, 0L, direct, 0, 0) == NativeBridge.SUBMIT_INVALID_BUFFER,
                "an empty range is refused");
        check(BRIDGE.submitVideo(987654321L, 1, 0L, direct, 60, 8) == NativeBridge.SUBMIT_INVALID_BUFFER,
                "a range beyond the buffer is refused");
        check(BRIDGE.submitVideo(987654321L, 1, 0L, direct, -1, 6) == NativeBridge.SUBMIT_INVALID_BUFFER,
                "a negative offset is refused");
        // Neither call may throw or crash for a handle that no longer exists.
        BRIDGE.videoFailed(987654321L, "encoder gone");
        BRIDGE.requestShutdown(987654321L);
        check(BRIDGE.report(987654321L) == null, "stale handle stays unknown after video calls");

        // P5.1 now-playing: the registration signature takes seven arguments across two
        // JNI types, so a Kotlin/native mismatch would fail at load rather than here.
        check(BRIDGE.submitNowPlaying(987654321L, NativeBridge.PLAYBACK_PLAYING, 0, "app", "t", "a", "b")
                == NativeBridge.NOW_PLAYING_UNKNOWN_HANDLE, "submitNowPlaying on an unknown handle is refused");
        // Validated before the handle is looked up: a state outside the five defined
        // values would otherwise reach the wire as an unknown enum.
        check(BRIDGE.submitNowPlaying(987654321L, 99, 0, "", "", "", "")
                == NativeBridge.NOW_PLAYING_INVALID, "an undefined playback state is refused");
        check(BRIDGE.submitNowPlaying(987654321L, NativeBridge.PLAYBACK_PLAYING, -1, "", "", "", "")
                == NativeBridge.NOW_PLAYING_INVALID, "a negative position is refused");
        // P6.5: the guidance hand-off has the same contract, and its signature is checked
        // at library load like every other registration.
        check(BRIDGE.submitGuidance(987654321L, NativeBridge.GUIDANCE_GUIDING, 1L, 8, 0, "Turn right onto Main Street",
                "Main Street", "Elm Avenue", 100.0, 10, 200.0, 20) == NativeBridge.NOW_PLAYING_UNKNOWN_HANDLE,
                "submitGuidance on an unknown handle is refused");
        check(BRIDGE.submitGuidance(987654321L, 99, 0L, 0, 0, "", "", "", 0.0, 0, 0.0, 0)
                == NativeBridge.NOW_PLAYING_INVALID, "an undefined guidance state is refused");
        // P7.4: ended is a state the native side knows, so it reaches the handle lookup
        // rather than being refused as undefined, like idle beside it.
        check(NativeBridge.GUIDANCE_ENDED == 4, "GUIDANCE_ENDED is 4, as in the native manager");
        check(BRIDGE.submitGuidance(987654321L, NativeBridge.GUIDANCE_ENDED, 0L, 0, 0, "", "", "", 0.0, 0, 0.0, 0)
                == NativeBridge.NOW_PLAYING_UNKNOWN_HANDLE, "an ended route is a defined guidance state");
        check(BRIDGE.submitGuidance(987654321L, NativeBridge.GUIDANCE_IDLE, 0L, 0, 0, "", "", "", 0.0, 0, 0.0, 0)
                == NativeBridge.NOW_PLAYING_UNKNOWN_HANDLE, "idle is a defined guidance state");
        check(BRIDGE.submitGuidance(987654321L, NativeBridge.GUIDANCE_GUIDING, -1L, 8, 0, "", "", "", 0.0, 0, 0.0, 0)
                == NativeBridge.NOW_PLAYING_INVALID, "a negative maneuver identity is refused");
    }

    static void identityAndTlsConfiguration() throws Exception {
        Path dir = Files.createTempDirectory("jni-host-identity");
        Path identity = dir.resolve("identity");
        String created = BRIDGE.ensureIdentity(identity.toString(), false);
        check(created.lines().anyMatch("identity_created=true"::equals), "identity created\n" + created);
        check(created.lines().anyMatch(l -> l.startsWith("tls_self_test=pass version=TLSv1.2 ")),
                "TLS self-test passes on this JVM\n" + created);
        check(!created.contains("PRIVATE"), "no key material crosses JNI");
        String loaded = BRIDGE.ensureIdentity(identity.toString(), false);
        check(loaded.lines().anyMatch("identity_created=false"::equals), "identity reloaded");
        check(fingerprint(created).equals(fingerprint(loaded)), "same identity after reload");
        check(!fingerprint(BRIDGE.ensureIdentity(identity.toString(), true)).equals(fingerprint(created)),
                "replacement changes the identity");

        // Candidate imports are validated before replacement and never export key material.
        Path candidate = dir.resolve("candidate");
        String supplied = BRIDGE.ensureIdentity(candidate.toString(), false);
        byte[] suppliedPem = Files.readAllBytes(candidate.resolve("source-identity.pem"));
        String imported = com.jw6ventures.headlink.IdentityImport.INSTANCE.install(
                identity.toFile(), new java.io.ByteArrayInputStream(suppliedPem));
        check(fingerprint(imported).equals(fingerprint(supplied)), "supplied identity installed");
        check(!imported.contains("PRIVATE KEY"), "import report excludes key");
        for (byte[] invalid : List.of(new byte[0], new byte[65537], "not PEM".getBytes(StandardCharsets.US_ASCII))) {
            try {
                com.jw6ventures.headlink.IdentityImport.INSTANCE.install(
                        identity.toFile(), new java.io.ByteArrayInputStream(invalid));
                throw new AssertionError("invalid identity imported");
            } catch (IOException expected) {
                check(fingerprint(BRIDGE.ensureIdentity(identity.toString(), false)).equals(fingerprint(supplied)),
                        "failed import preserves active identity");
                check(!Files.exists(identity.resolve("import-candidate/source-identity.pem")), "candidate cleaned up");
            }
        }
        Arrays.fill(suppliedPem, (byte) 0);

        // A session beyond version whose identity is missing is refused and closes the descriptor.
        Pair missing = pair();
        long gen = generation++;
        SessionSettings settings = new SessionSettings("legacy-1.5", "discovery", "tls12-aead",
                dir.resolve("absent").toString(), false, List.of("ab".repeat(32)), 5000L, true, "encrypted",
                null, SessionSettings.SENSORS_NONE, false, false, false,
                false, SessionSettings.NAVIGATION_UNITS_IMPERIAL, false,
                SessionSettings.VIDEO_PREFERENCE_LARGEST, 0,
                SessionSettings.SCENE_AREA_CONTENT, false, false, false, true, SessionSettings.SETUP_FIELD_CODEC, false, true);
        String settingsText = new String(
                BRIDGE.config(gen, UUID.randomUUID().toString(), settings), StandardCharsets.US_ASCII);
        check(settingsText.contains("capture_discovery=true\n") && settingsText.contains("keepalive=encrypted\n"),
                "capture_discovery and keepalive in config text\n" + settingsText);
        check(BRIDGE.start(missing.fd, settingsText.getBytes(StandardCharsets.US_ASCII), new Recorder())
                == NativeBridge.START_IDENTITY_UNAVAILABLE, "missing identity refused");
        expectEof(missing.peer);
        missing.peer.close();

        // The same settings with the prepared identity start, wait for a version request, and stop.
        Pair pair = pair();
        Recorder recorder = new Recorder();
        long next = generation++;
        SessionSettings ready = new SessionSettings("legacy-1.5", "auth", "tls12-cbc-compat",
                identity.toString(), true, List.of(), null, false, SessionSettings.KEEPALIVE_MIRROR, null,
                SessionSettings.SENSORS_NONE, false, false, false,
                false, SessionSettings.NAVIGATION_UNITS_IMPERIAL, false,
                SessionSettings.VIDEO_PREFERENCE_LARGEST, 0, SessionSettings.SCENE_AREA_CONTENT, false, false, false, true, SessionSettings.SETUP_FIELD_CODEC, false, true);
        String text = new String(BRIDGE.config(next, UUID.randomUUID().toString(), ready), StandardCharsets.US_ASCII);
        check(text.contains("stop_after=auth\n") && text.contains("peer_policy=first-use\n")
                && !text.contains("peer_pins") && !text.contains("capture_discovery") && text.contains("keepalive=mirror\n"),
                "first-use configuration text\n" + text);
        check(BRIDGE.start(pair.fd, text.getBytes(StandardCharsets.US_ASCII), recorder) == next,
                "TLS-capable session starts with a loaded identity");
        // Projection settings reach the native configuration only with that stop stage.
        SessionSettings projecting = new SessionSettings("legacy-1.5", SessionSettings.STOP_AFTER_PROJECTION,
                "tls12-aead", identity.toString(), true, List.of(), 5000L, false,
                SessionSettings.KEEPALIVE_MIRROR, 60000L, SessionSettings.SENSORS_ALL, true, false, false,
                false, SessionSettings.NAVIGATION_UNITS_IMPERIAL, false,
                SessionSettings.VIDEO_PREFERENCE_LARGEST, 0, SessionSettings.SCENE_AREA_CONTENT, false, false, false, true, SessionSettings.SETUP_FIELD_CODEC, false, true);
        String projectionText = new String(
                BRIDGE.config(generation, UUID.randomUUID().toString(), projecting), StandardCharsets.US_ASCII);
        check(projectionText.contains("stop_after=projection\n") && projectionText.contains("projection_ms=60000\n")
                && projectionText.contains("sensors=all\n")
                // Position is its own opt-in, so it only reaches native with the set.
                && projectionText.contains("sensor_location=true\n") && !projectionText.contains("hold_ms")
                // Each integration is separately opt-in, so an unasked-for one is absent
                // rather than sent as false (spec 8.1).
                && !projectionText.contains("media_info")
                // P5.3 audio is opt-in the same way, so it is absent rather than false.
                && !projectionText.contains("audio")
                // P6.2 likewise, and the units go with it rather than on their own: a
                // unit for a cluster nobody is driving says nothing.
                && !projectionText.contains("navigation"),
                "projection configuration text\n" + projectionText);
        // The video preference is always sent, so the app never depends on matching the
        // native default. The encoder rate and scene area never reach native: they only
        // change what we encode and where we draw.
        check(projectionText.contains("video_preference=largest\n"),
                "the video preference is always sent\n" + projectionText);
        // So is the congestion restart (spec 8.2), on unless cleared, and only with projection.
        check(projectionText.contains("congestion_restart=true\n") && !text.contains("congestion_restart"),
                "the congestion restart is always sent with projection\n" + projectionText);
        SessionSettings pinned = new SessionSettings("legacy-1.5", SessionSettings.STOP_AFTER_PROJECTION,
                "tls12-aead", identity.toString(), true, List.of(), 5000L, false,
                SessionSettings.KEEPALIVE_MIRROR, 60000L, SessionSettings.SENSORS_RESTRICTION, false, true, true,
                true, "metric", true,
                "800x480", 30,
                SessionSettings.SCENE_AREA_FRAME, true, false, false, true, SessionSettings.SETUP_FIELD_CODEC, false, true);
        String pinnedText = new String(
                BRIDGE.config(generation, UUID.randomUUID().toString(), pinned), StandardCharsets.US_ASCII);
        check(pinnedText.contains("video_preference=800x480\n") && !pinnedText.contains("encoder_fps")
                && !pinnedText.contains("scene_area") && pinnedText.contains("capture_unhandled=true\n")
                && pinnedText.contains("media_info=true\n") && pinnedText.contains("audio=true\n")
                // P6.2: the cluster keys travel together, and the unit is always stated
                // rather than left to match a native default.
                && pinnedText.contains("navigation=true\n")
                && pinnedText.contains("navigation_units=metric\n")
                && pinnedText.contains("navigation_force=true\n")
                // P6.5: the route drives the cluster unless the script is asked for.
                && pinnedText.contains("navigation_source=route\n"),
                "pinned video preference text\n" + pinnedText);
        // Android Auto's session shape: both keys only appear when they differ from the
        // native defaults, and neither reaches a non-projection configuration.
        SessionSettings imitating = new SessionSettings("legacy-1.5", SessionSettings.STOP_AFTER_PROJECTION,
                "tls12-aead", identity.toString(), true, List.of(), 5000L, false,
                SessionSettings.KEEPALIVE_MIRROR, 60000L, SessionSettings.SENSORS_NONE, false, false, false,
                true, SessionSettings.NAVIGATION_UNITS_IMPERIAL, false,
                "1920x1080", 0,
                SessionSettings.SCENE_AREA_CONTENT, false, false, true, false, "config-index", true, false);
        String imitatingText = new String(
                BRIDGE.config(generation, UUID.randomUUID().toString(), imitating), StandardCharsets.US_ASCII);
        check(imitatingText.contains("congestion_restart=false\n"),
                "a cleared congestion restart reaches native\n" + imitatingText);
        check(imitatingText.contains("open_all_channels=true\n")
                && imitatingText.contains("request_focus=false\n")
                && imitatingText.contains("setup_field=config-index\n"),
                "imitation configuration text\n" + imitatingText);
        check(!projectionText.contains("setup_field"),
                "the default setup field is left to native\n" + projectionText);
        // Forcing is off unless it was asked for, so an ordinary cluster attempt waits.
        check(imitatingText.contains("navigation=true\n")
                && imitatingText.contains("navigation_units=imperial\n")
                && !imitatingText.contains("navigation_force")
                && imitatingText.contains("navigation_source=script\n"),
                "cluster configuration text\n" + imitatingText);
        BRIDGE.stop(next);
        check(recorder.finalReport.contains("final_category=CANCELLED")
                && recorder.finalReport.contains("stop_after=auth"), "stopped while waiting\n" + recorder.finalReport);
        expectEof(pair.peer);
        pair.peer.close();
    }

    static String fingerprint(String report) {
        return report.lines().filter(l -> l.startsWith("source_identity_fingerprint_sha256="))
                .findFirst().orElse("missing");
    }

    static byte[] config(long gen) {
        return NativeBridge.INSTANCE.config(gen, UUID.randomUUID().toString(), "legacy-1.5");
    }

    static byte[] fixture(String path) throws IOException {
        return HexFormat.of().parseHex(Files.readString(Path.of(path)).replaceAll("\\s+", ""));
    }

    /** A connected Unix socket pair; the local end's descriptor number goes to native. */
    static Pair pair() throws Exception {
        Path path = Files.createTempDirectory("jni-host-test").resolve("s");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(path));
            SocketChannel peer = SocketChannel.open(UnixDomainSocketAddress.of(path));
            SocketChannel local = server.accept();
            Files.delete(path);
            Files.delete(path.getParent());
            Method fdVal = local.getClass().getDeclaredMethod("getFDVal");
            fdVal.setAccessible(true);
            TRANSFERRED.add(local);
            peer.configureBlocking(false);
            return new Pair((Integer) fdVal.invoke(local), peer);
        }
    }

    static void write(SocketChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    static byte[] read(SocketChannel channel, int n) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(n);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (Selector selector = Selector.open()) {
            channel.register(selector, SelectionKey.OP_READ);
            while (buffer.hasRemaining()) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                check(left > 0, "read " + n + " bytes before timeout");
                selector.select(left);
                selector.selectedKeys().clear();
                check(channel.read(buffer) >= 0, "unexpected EOF");
            }
        }
        return buffer.array();
    }

    /** EOF on the peer proves native closed the only descriptor for the local end. */
    static void expectEof(SocketChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (Selector selector = Selector.open()) {
            channel.register(selector, SelectionKey.OP_READ);
            while (true) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                check(left > 0, "peer sees EOF (descriptor closed) before timeout");
                selector.select(left);
                selector.selectedKeys().clear();
                buffer.clear();
                if (channel.read(buffer) < 0) break;
            }
        }
        passed++;
    }

    static List<String> nativeThreads() throws IOException {
        List<String> names = new ArrayList<>();
        try (var tasks = Files.list(Path.of("/proc/self/task"))) {
            for (Path task : (Iterable<Path>) tasks::iterator) {
                try {
                    String name = Files.readString(task.resolve("comm")).trim();
                    if (name.startsWith("aap-")) names.add(name);
                } catch (IOException e) {
                    // Thread exited while listing.
                }
            }
        }
        return names;
    }

    static void check(boolean condition, String what) {
        if (!condition) {
            System.err.println("jni-host-test: FAIL " + what);
            System.exit(1);
        }
        passed++;
    }
}
