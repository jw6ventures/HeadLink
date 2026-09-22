// The drop folder's rules and the map import's completeness check on a desktop JVM.
// DropRules and MapData.install are pure Kotlin; Android's document API, which lists,
// copies and deletes the files, is not exercised here and needs a device.
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.openautoandroid.source.DropAction;
import org.openautoandroid.source.DropKind;
import org.openautoandroid.source.DropRules;
import org.openautoandroid.source.MapData;

public final class DropFolderTest {
    private static int passed;
    private static final long NOW = 1_800_000_000_000L;

    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("drop-folder-test").toFile();
        try {
            onlyMapsAndTilesAreTaken();
            aFileStillArrivingWaits();
            aCopiedFileWhoseDeleteFailedIsNotCopiedAgain(work);
            aMapThatIsAllThereInstalls(work);
            aMapCutShortIsRefusedAndReplacesNothing(work);
            System.out.println("jni-host-test: ok " + passed + " drop folder checks");
        } finally {
            delete(work);
        }
    }

    private static void onlyMapsAndTilesAreTaken() {
        check(DropRules.INSTANCE.kind("texas.map") == DropKind.MAP, "a map");
        check(DropRules.INSTANCE.kind("Colorado.MAP") == DropKind.MAP, "case does not matter");
        check(DropRules.INSTANCE.kind("W100_N30.rd5") == DropKind.ROUTING, "a tile");
        check(DropRules.INSTANCE.kind("W100_N30.rd5.crdownload") == null, "a browser download in progress");
        check(DropRules.INSTANCE.kind("texas.osm.pbf") == null, "raw OpenStreetMap data");
        check(DropRules.INSTANCE.decide("notes.txt", 10, NOW - 3_600_000, NOW, null) == DropAction.IGNORE,
            "anything else is left alone");
        check("W100_N30.rd5".equals(DropRules.INSTANCE.installedName(DropKind.ROUTING, "W100_N30 (1).rd5")),
            "a tile installs under its tile name");
        check(DropRules.INSTANCE.installedName(DropKind.ROUTING, "texas.rd5") == null, "and one without cannot");
        check("texas.map".equals(DropRules.INSTANCE.installedName(DropKind.MAP, "texas.map")), "a map keeps its name");
    }

    private static void aFileStillArrivingWaits() {
        check(DropRules.INSTANCE.decide("texas.map", 1 << 20, NOW - 5_000, NOW, null) == DropAction.WAIT,
            "modified five seconds ago");
        check(DropRules.INSTANCE.decide("texas.map", 1 << 20, NOW - DropRules.QUIET_MS, NOW, null) == DropAction.IMPORT,
            "quiet for the whole interval");
        check(DropRules.INSTANCE.decide("texas.map", 1 << 20, 0, NOW, null) == DropAction.IMPORT,
            "a provider that gives no time is not made to wait forever");
    }

    private static void aCopiedFileWhoseDeleteFailedIsNotCopiedAgain(File work) throws IOException {
        File installed = new File(work, "W100_N30.rd5");
        Files.write(installed.toPath(), new byte[1000]);
        long dropped = NOW - 3_600_000;
        check(installed.setLastModified(dropped + 60_000), "installed after the drop");
        check(DropRules.INSTANCE.decide("W100_N30.rd5", 1000, dropped, NOW, installed) == DropAction.ALREADY,
            "same size, installed after it last changed");
        check(DropRules.INSTANCE.decide("W100_N30.rd5", 1001, dropped, NOW, installed) == DropAction.IMPORT,
            "a different size is a different file");
        check(installed.setLastModified(dropped - 60_000), "installed before the drop");
        check(DropRules.INSTANCE.decide("W100_N30.rd5", 1000, dropped, NOW, installed) == DropAction.IMPORT,
            "a newer drop of the same size replaces it");
    }

    private static void aMapThatIsAllThereInstalls(File work) throws IOException {
        File maps = new File(work, "maps");
        File installed = MapData.INSTANCE.installInto(maps, "texas.map", new ByteArrayInputStream(map(4096, 4096)));
        check(installed.getName().equals("texas.map") && installed.length() == 4096, "installed whole");
        check(!new File(maps, "texas.map.part").exists(), "no staging file left");
    }

    private static void aMapCutShortIsRefusedAndReplacesNothing(File work) throws IOException {
        File maps = new File(work, "maps");
        byte[] whole = map(8192, 8192);
        String cut = refused(maps, "texas.map", Arrays.copyOf(whole, 5000));
        check(cut != null && cut.contains("incomplete: 5000 of 8192"), "a cut-short map says how short: " + cut);
        String notMap = refused(maps, "texas.map", "not a map at all, just some text that is long enough to pass"
            .repeat(4).getBytes(StandardCharsets.US_ASCII));
        check(notMap != null && notMap.contains("not a Mapsforge map file"), "text is refused: " + notMap);
        check(new File(maps, "texas.map").length() == 4096, "the installed map is untouched");
        check(!new File(maps, "texas.map.part").exists(), "and no staging file is left");
    }

    /** The first 36 bytes of a Mapsforge header, declaring [declared] as the file size. */
    private static byte[] map(int length, long declared) {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.put("mapsforge binary OSM".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(64);
        buffer.putInt(5);
        buffer.putLong(declared);
        return buffer.array();
    }

    private static String refused(File maps, String name, byte[] bytes) {
        try {
            MapData.INSTANCE.installInto(maps, name, new ByteArrayInputStream(bytes));
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("drop folder: " + what);
        passed++;
    }
}
