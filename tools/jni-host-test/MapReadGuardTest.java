// MapReadGuard on a desktop JVM: a tile VTM read badly is failed so it is queued again,
// a clean one passes through, and a block that never reads is given up on. With a real
// Mapsforge file as the argument it also drives VTM's own MapFile reader, which is what
// shows the guard listens on the loggers VTM actually warns through. Nothing here
// rasterises; whether the retried tile then draws needs the lab or the vehicle.
import java.io.File;
import java.util.logging.Logger;
import org.openautoandroid.source.MapReadGuard;
import org.oscim.backend.canvas.Bitmap;
import org.oscim.core.MapElement;
import org.oscim.layers.tile.MapTile;
import org.oscim.tiling.ITileDataSink;
import org.oscim.tiling.ITileDataSource;
import org.oscim.tiling.QueryResult;
import org.oscim.tiling.TileSource;
import org.oscim.tiling.source.mapfile.MapFile;
import org.oscim.tiling.source.mapfile.MapFileTileSource;
import org.oscim.utils.Parameters;

public final class MapReadGuardTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        // The warnings are the input here, not news; keep them off the build log. Only
        // the console handler goes, so the loggers still pass them to the guard.
        Logger root = Logger.getLogger("");
        for (java.util.logging.Handler h : root.getHandlers()) root.removeHandler(h);
        aCleanTilePassesThrough();
        aBadReadIsRetriedThenGivenUp();
        aTileThatRecoversStartsAgain();
        aWarningOutsideAQueryIsIgnored();
        otherResultsAreUntouched();
        String map = args.length > 0 ? args[0] : "";
        if (map.isEmpty()) {
            System.out.println("jni-host-test: ok " + passed + " map read checks (no MAP_FILE; VTM reader skipped)");
        } else {
            vtmsOwnReaderIsCaught(new File(map));
            System.out.println("jni-host-test: ok " + passed + " map read checks, VTM reader over " + new File(map).getName());
        }
    }

    private static void aCleanTilePassesThrough() {
        MapReadGuard guard = new MapReadGuard(new Fake(false, QueryResult.SUCCESS));
        check(query(guard, 16, 1, 2) == QueryResult.SUCCESS, "a clean read is a success");
        check(guard.getFailures().get() == 0 && guard.getReads().get() == 1, "and is counted as a read only");
        check(guard.report().endsWith("map_read_first=-"), "no warning to report");
        guard.close();
    }

    private static void aBadReadIsRetriedThenGivenUp() {
        MapReadGuard guard = new MapReadGuard(new Fake(true, QueryResult.SUCCESS));
        check(query(guard, 16, 1, 2) == QueryResult.FAILED, "a warned read is failed, so VTM queues it again");
        check(query(guard, 16, 1, 2) == QueryResult.FAILED, "and again");
        check(query(guard, 16, 1, 2) == QueryResult.SUCCESS, "the third try is let through");
        check(guard.getFailures().get() == 2 && guard.getGaveUp().get() == 1, "two retries, one tile given up");
        check(query(guard, 16, 1, 3) == QueryResult.FAILED, "a neighbouring tile has its own tries");
        check(guard.report().contains("map_read_first=reading current block has failed: 42"),
                "the first warning is reported");
        guard.close();
    }

    private static void aTileThatRecoversStartsAgain() {
        Fake fake = new Fake(true, QueryResult.SUCCESS);
        MapReadGuard guard = new MapReadGuard(fake);
        check(query(guard, 16, 5, 5) == QueryResult.FAILED, "first read fails");
        fake.warn = false;
        check(query(guard, 16, 5, 5) == QueryResult.SUCCESS, "second read is clean");
        fake.warn = true;
        check(query(guard, 16, 5, 5) == QueryResult.FAILED, "a clean load resets the tile's tries");
        guard.close();
    }

    private static void aWarningOutsideAQueryIsIgnored() {
        MapReadGuard guard = new MapReadGuard(new Fake(false, QueryResult.SUCCESS));
        Logger.getLogger(MapFile.class.getName()).warning("between queries");
        check(query(guard, 16, 1, 2) == QueryResult.SUCCESS, "an earlier warning does not taint a tile");
        check(guard.getFirstWarning().get() == null, "and is not reported");
        guard.close();
        // Every guard so far has been closed; a rebuilt renderer must not pile them up.
        check(Logger.getLogger(MapFile.class.getName()).getHandlers().length == 0,
                "closing a guard takes its handler off VTM's logger");
    }

    private static void otherResultsAreUntouched() {
        MapReadGuard failing = new MapReadGuard(new Fake(true, QueryResult.FAILED));
        check(query(failing, 16, 1, 2) == QueryResult.FAILED, "a failure stays a failure");
        check(failing.getFailures().get() == 0, "and is VTM's own retry, not counted here");
        failing.close();
        MapReadGuard delayed = new MapReadGuard(new Fake(true, QueryResult.DELAYED));
        check(query(delayed, 16, 1, 2) == QueryResult.DELAYED, "DELAYED passes through");
        delayed.close();
    }

    /**
     * Shrinks VTM's block limit so every block is skipped with a warning, which is the
     * same silent-success path a short read takes, then restores it.
     */
    private static void vtmsOwnReaderIsCaught(File file) {
        MapFileTileSource source = new MapFileTileSource();
        check(source.setMapFile(file.getAbsolutePath()), "map file accepted");
        check(source.open().isSuccess(), "map file opens");
        MapReadGuard guard = new MapReadGuard(source);
        // The lab receiver's position, which the lab's Massachusetts extract covers.
        int zoom = 16;
        int x = (int) Math.floor((-71.2121 + 180) / 360 * (1 << zoom));
        double lat = Math.toRadians(42.4242);
        int y = (int) Math.floor((1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2 * (1 << zoom));

        Recorder clean = new Recorder();
        guard.getDataSource().query(new MapTile(x, y, zoom), clean);
        check(clean.result == QueryResult.SUCCESS && clean.elements > 0,
                "a real tile reads cleanly with " + clean.elements + " elements");
        check(guard.getFailures().get() == 0, "and is not flagged");

        int limit = Parameters.MAXIMUM_BUFFER_SIZE;
        Parameters.MAXIMUM_BUFFER_SIZE = 1;
        try {
            Recorder bare = new Recorder();
            new MapFile(source).query(new MapTile(x, y, zoom), bare);
            check(bare.result == QueryResult.SUCCESS && bare.elements == 0,
                    "unguarded, VTM calls a tile it could not read a success with nothing in it");
            Recorder guarded = new Recorder();
            guard.getDataSource().query(new MapTile(x, y, zoom), guarded);
            check(guarded.result == QueryResult.FAILED, "guarded, the same read is failed");
            check(String.valueOf(guard.getFirstWarning().get()).startsWith("current block size too large"),
                    "from VTM's own warning: " + guard.getFirstWarning().get());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            Parameters.MAXIMUM_BUFFER_SIZE = limit;
        }
        guard.close();
    }

    private static QueryResult query(TileSource source, int zoom, int x, int y) {
        Recorder sink = new Recorder();
        source.getDataSource().query(new MapTile(x, y, zoom), sink);
        return sink.result;
    }

    /** A data source that warns the way VTM's reader does, then completes with [result]. */
    private static final class Fake extends TileSource {
        boolean warn;
        final QueryResult result;

        Fake(boolean warn, QueryResult result) {
            this.warn = warn;
            this.result = result;
        }

        @Override
        public ITileDataSource getDataSource() {
            return new ITileDataSource() {
                @Override
                public void query(MapTile tile, ITileDataSink sink) {
                    if (warn) Logger.getLogger(MapFile.class.getName()).warning("reading current block has failed: 42");
                    sink.completed(result);
                }

                @Override
                public void dispose() {}

                @Override
                public void cancel() {}
            };
        }

        @Override
        public OpenResult open() {
            return OpenResult.SUCCESS;
        }

        @Override
        public void close() {}
    }

    private static final class Recorder implements ITileDataSink {
        QueryResult result;
        int elements;

        @Override
        public void process(MapElement element) {
            elements++;
        }

        @Override
        public void setTileImage(Bitmap bitmap) {}

        @Override
        public void completed(QueryResult result) {
            this.result = result;
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("map read guard: " + what);
        passed++;
    }
}
