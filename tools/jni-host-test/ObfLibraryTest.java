// P11.1's maps folder rules and Settings -> OsmAnd maps on a desktop JVM: which files in
// the folder are opened, what identifies one version of a file, which persisted grant is
// the maps folder and which the drop folder, the card's status line, and PoiSettings'
// defaults, repair and stored form. ObfRules and PoiValues are pure Kotlin; the document
// API, the grants themselves and the preferences file need a device.
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.openautoandroid.source.ObfRules;
import org.openautoandroid.source.PoiValues;

public final class ObfLibraryTest {
    private static int passed;
    private static final ZoneId UTC = ZoneOffset.UTC;

    public static void main(String[] args) {
        onlyMapFilesDirectlyInTheFolderAreRead();
        aReplacedFileIsADifferentKey();
        regionsComeFromOsmAndsNames();
        eachFolderKeepsItsOwnGrant();
        theStatusLineSaysWhatThePlanSays();
        poiSettingsDefaultsAndRepair();
        System.out.println("jni-host-test: ok " + passed + " maps folder checks");
    }

    private static void onlyMapFilesDirectlyInTheFolderAreRead() {
        ObfRules r = ObfRules.INSTANCE;
        check(r.readable("Us_texas_austin_northamerica.obf"), "a region map");
        check(r.readable("US_TEXAS_DALLAS_NORTHAMERICA.OBF"), "case does not matter");
        check(!r.readable("Us_texas_austin_northamerica.obf.download"), "OsmAnd's download in progress");
        check(!r.readable("Us_texas_austin_northamerica_2.obf.zip"), "a zipped download");
        check(!r.readable("Us_texas_austin_northamerica.srtmf.obf"), "contour lines");
        check(!r.readable("Us_texas_austin_northamerica.srtm.obf"), "contour lines, feet");
        check(!r.readable("Us_texas_austin_northamerica.wiki.obf"), "Wikipedia");
        check(!r.readable("World_basemap.obf"), "the world basemap holds no addresses");
        check(!r.readable("world_basemap_mini.obf"), "nor its small version");
        check(!r.readable(".obf"), "a bare extension");
        check(!r.readable(".hidden.obf"), "a hidden file");
        check(!r.readable("notes.txt"), "anything else");
    }

    private static void aReplacedFileIsADifferentKey() {
        ObfRules.FileKey a = new ObfRules.FileKey("Us_texas_austin_northamerica.obf", 300_000_000L, 1_000L);
        check(a.equals(new ObfRules.FileKey("Us_texas_austin_northamerica.obf", 300_000_000L, 1_000L)),
            "the same file listed again keeps its key, so it is not reopened");
        check(!a.equals(new ObfRules.FileKey("Us_texas_austin_northamerica.obf", 300_000_001L, 1_000L)),
            "a different size is a different file");
        check(!a.equals(new ObfRules.FileKey("Us_texas_austin_northamerica.obf", 300_000_000L, 2_000L)),
            "a newer modified time is a different file");
        check(!a.equals(new ObfRules.FileKey("Us_texas_dallas_northamerica.obf", 300_000_000L, 1_000L)),
            "a different name is a different file");
    }

    private static void regionsComeFromOsmAndsNames() {
        ObfRules r = ObfRules.INSTANCE;
        check("Texas".equals(r.region("Us_texas_austin_northamerica.obf")), "a US state");
        check("Texas".equals(r.region("Us_texas_san-antonio_northamerica.obf")), "a two-word region");
        check("New Mexico".equals(r.region("Us_new-mexico_northamerica.obf")), "a two-word state");
        check("Netherlands".equals(r.region("Netherlands_noord-holland_europe.obf")), "a country");
        check(r.region("lab.obf") == null, "a name OsmAnd did not give");
    }

    private static void eachFolderKeepsItsOwnGrant() {
        ObfRules r = ObfRules.INSTANCE;
        // DropFolder.choose gives up only drop grants; ObfLibrary.choose only maps grants.
        check(r.isDropGrant(true, true) && !r.isMapsGrant(true, true), "read+write is the drop folder");
        check(r.isMapsGrant(true, false) && !r.isDropGrant(true, false), "read-only is the maps folder");
        check(!r.isMapsGrant(false, true) && !r.isDropGrant(false, true), "write-only is neither");
    }

    private static void theStatusLineSaysWhatThePlanSays() {
        ObfRules r = ObfRules.INSTANCE;
        List<String> none = Collections.emptyList();
        long june30 = LocalDate.of(2026, 6, 30).atStartOfDay(UTC).toInstant().toEpochMilli() + 3_600_000L;
        check("No folder chosen".equals(r.status(new ObfRules.FolderState(false, false, 0, 0, 0, 0, none, 0), UTC)),
            "no folder");
        check("Folder unreadable".equals(r.status(new ObfRules.FolderState(true, true, 0, 0, 0, 0, none, 0), UTC)),
            "unreadable");
        check("No OsmAnd maps in this folder".equals(r.status(new ObfRules.FolderState(true, false, 0, 0, 0, 0, none, 0), UTC)),
            "empty");
        List<String> texas = Collections.nCopies(11, "Texas");
        String eleven = r.status(new ObfRules.FolderState(true, false, 11, 11, 0, 0, texas, june30), UTC);
        check("11 maps · Texas · newest 30 Jun 2026".equals(eleven), "the plan's example: " + eleven);
        String newer = r.status(new ObfRules.FolderState(true, false, 3, 0, 3, 0, none, 0), UTC);
        check("3 maps in a newer OBF format than this app reads".equals(newer), "all newer: " + newer);
        String mixed = r.status(new ObfRules.FolderState(true, false, 3, 1, 1, 1, Arrays.asList("Texas"), june30), UTC);
        check("1 map · Texas · newest 30 Jun 2026 · 1 map in a newer OBF format than this app reads · 1 map could not be read"
            .equals(mixed), "mixed, singular: " + mixed);
        String regions = r.status(new ObfRules.FolderState(true, false, 4, 4, 0, 0,
            Arrays.asList("Texas", "Sweden", "Denmark", "Netherlands"), 0), UTC);
        check("4 maps · 4 regions".equals(regions), "many regions, no date: " + regions);
        String two = r.status(new ObfRules.FolderState(true, false, 2, 2, 0, 0, Arrays.asList("Texas", "Sweden"), 0), UTC);
        check("2 maps · Sweden, Texas".equals(two), "two regions named: " + two);
    }

    private static void poiSettingsDefaultsAndRepair() {
        PoiValues d = new PoiValues();
        check(d.getMarkersPerKind() == 25 && d.getAheadMiles() == 50 && d.getCorridorMiles() == 0.5
            && d.getAheadCandidates() == 5, "the plan's defaults (§6, §6a)");
        Map<String, Object> stored = new HashMap<>(d.toStored());
        check(PoiValues.Companion.fromStored(stored).equals(d), "round trip");
        check(stored.keySet().equals(new java.util.HashSet<>(Arrays.asList(
            "poi_markers_per_kind", "poi_ahead_miles", "poi_corridor_tenths", "poi_ahead_candidates"))),
            "stored keys never renamed");
        Map<String, Object> bad = new HashMap<>();
        bad.put(PoiValues.MARKERS, 4);
        bad.put(PoiValues.AHEAD, 151);
        bad.put(PoiValues.CORRIDOR, 0);
        bad.put(PoiValues.CANDIDATES, "7");
        check(PoiValues.Companion.fromStored(bad).equals(d), "out of range or mistyped reads as the default");
        Map<String, Object> edges = new HashMap<>();
        edges.put(PoiValues.MARKERS, 100);
        edges.put(PoiValues.AHEAD, 10);
        edges.put(PoiValues.CORRIDOR, 20);
        edges.put(PoiValues.CANDIDATES, 1);
        PoiValues e = PoiValues.Companion.fromStored(edges);
        check(e.getMarkersPerKind() == 100 && e.getAheadMiles() == 10 && e.getCorridorMiles() == 2.0
            && e.getAheadCandidates() == 1, "range ends are kept");
        check(PoiValues.Companion.corridorTenths(0.04) == 1, "a corridor below 0.1 mile clamps up");
        check(PoiValues.Companion.corridorTenths(9.0) == 20, "above 2 miles clamps down");
        check(PoiValues.Companion.corridorTenths(0.25) == 3 || PoiValues.Companion.corridorTenths(0.25) == 2,
            "a quarter mile rounds to a tenth");
        check(PoiValues.Companion.corridorTenths(Double.NaN) == 5, "NaN is the default");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
    }
}
