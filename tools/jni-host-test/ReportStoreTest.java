// Host JVM test of the app's compiled Kotlin ReportStore (pure JVM): naming, atomic
// coalesced saves, write ordering with crash-time saveNow, retention, export, and the
// bounded crash section. It does not exercise Android storage or process death.
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.openautoandroid.source.ReportStore;

public final class ReportStoreTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("report-store-test").toFile();
        Instant base = Instant.parse("2026-09-16T21:30:45Z");
        String first = ReportStore.Companion.fileName(base, "0F5A4C1E-8b2d-4e6f");
        check(first.equals("report-20260916T213045Z-0f5a4c1e.txt"), "file name format: " + first);
        check(ReportStore.Companion.fileName(base, "crash").equals("report-20260916T213045Z-crash000.txt"),
                "short ids are padded");

        ReportStore store = new ReportStore(new File(dir, "reports"), 3);
        rejects(store, "../escape.txt");
        rejects(store, "report-20260916T213045Z-0f5a4c1e.txt.tmp");

        store.save(first, "one\n");
        store.save(first, "two\n");
        store.flush();
        check("two\n".equals(store.read(first)), "latest queued text is written");
        check(noTemporaryFiles(dir), "no temporary files remain");

        // Many coalesced saves racing a crash-time saveNow: the saveNow text must win.
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread writer = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 500; i++) store.save(first, "snapshot " + i + "\n");
            });
            writers.add(writer);
            writer.start();
        }
        start.countDown();
        for (Thread writer : writers) writer.join();
        store.saveNow(first, "final\n[crash]\n");
        store.flush();
        check("final\n[crash]\n".equals(store.read(first)), "saveNow is not overwritten by queued snapshots");

        for (int i = 1; i <= 4; i++) {
            store.save(ReportStore.Companion.fileName(base.plusSeconds(i), "attempt" + i), "attempt " + i + "\n");
        }
        store.flush();
        List<String> names = store.list();
        check(names.size() == 3, "retention keeps 3 newest: " + names);
        check(names.get(0).contains("213049Z") && names.get(2).contains("213047Z"), "newest first: " + names);
        check(!names.contains(first), "oldest report pruned");
        check(store.getLastError() == null, "no write error");

        String export = store.exportAll();
        check(export.startsWith("saved_reports=3\n"), "export header");
        check(export.indexOf("attempt 4") < export.indexOf("attempt 2"), "export newest first");

        Throwable deep = recurse(4000);
        String crash = ReportStore.Companion.crashSection("main", new IllegalStateException("boom", deep), base);
        check(crash.startsWith("[crash]\ncrash_utc=2026-09-16T21:30:45Z\ncrash_thread=main\n"), "crash header");
        check(crash.contains("java.lang.IllegalStateException: boom"), "crash exception");
        check(crash.length() <= ReportStore.CRASH_LIMIT + 32 && crash.endsWith("[crash truncated]\n"),
                "crash section bounded: " + crash.length());

        File blocked = new File(dir, "blocked");
        check(blocked.createNewFile(), "blocking file created");
        ReportStore failing = new ReportStore(blocked, 3);
        failing.save(first, "x\n");
        failing.flush();
        check(failing.getLastError() != null, "write failure recorded, not thrown");

        System.out.println("report-store-test: ok " + passed + " checks");
    }

    static Throwable recurse(int depth) {
        return depth == 0 ? new RuntimeException("deep") : recurse(depth - 1);
    }

    static void rejects(ReportStore store, String name) {
        try {
            store.save(name, "x");
        } catch (IllegalArgumentException expected) {
            passed++;
            return;
        }
        fail("invalid name accepted: " + name);
    }

    static boolean noTemporaryFiles(File dir) throws IOException {
        try (var files = Files.walk(dir.toPath())) {
            return files.noneMatch(path -> path.toString().endsWith(".tmp"));
        }
    }

    static void check(boolean condition, String what) {
        if (!condition) fail(what);
        passed++;
    }

    static void fail(String what) {
        System.err.println("report-store-test: FAIL " + what);
        System.exit(1);
    }
}
