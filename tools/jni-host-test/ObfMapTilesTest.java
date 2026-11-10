// The map pane's tiles from OsmAnd's map data (docs/navigation.md, "Drawing the map from
// OsmAnd maps"): ObfMapTiles over BinaryMapDataObjects built in memory, the way OsmAnd's
// reader hands them over.
//
// What it shows: a tile's box in OsmAnd's 31-bit numbers; a road's tags (its type, the
// additional types and its names) and its points in tile pixels, clipped; an area with a
// hole, without its closing point; a building kept whole at building zoom; points inside and
// outside the tile; OsmAnd's layer; and the sea from coastlines - a coast across the tile
// with the water on its right as OSM draws it, an island in the open sea, and a tile of open
// sea as a whole-tile polygon. It proves nothing about a real .obf file, VTM's drawing or the
// phone's speed.
import java.util.List;
import com.jw6ventures.headlink.ObfMapTiles;
import net.osmand.binary.BinaryMapDataObject;
import net.osmand.binary.BinaryMapIndexReader.MapIndex;
import org.oscim.core.MapElement;
import org.oscim.core.Tile;
import org.oscim.utils.geom.TileClipper;
import org.oscim.utils.geom.TileSeparator;

public final class ObfMapTilesTest {
    private static int passed;

    private static final int HIGHWAY = 1, NAME = 2, REF = 3, ONEWAY = 4, WATER = 5, BUILDING = 6,
        COASTLINE = 7, SHOP = 8;

    private static MapIndex index;

    public static void main(String[] args) throws Exception {
        index = new MapIndex();
        index.initMapEncodingRule(0, HIGHWAY, "highway", "motorway");
        index.initMapEncodingRule(0, NAME, "name", "");
        index.initMapEncodingRule(0, REF, "ref", "");
        index.initMapEncodingRule(0, ONEWAY, "oneway", "yes");
        index.initMapEncodingRule(0, WATER, "natural", "water");
        index.initMapEncodingRule(0, BUILDING, "building", "yes");
        index.initMapEncodingRule(0, COASTLINE, "natural", "coastline");
        index.initMapEncodingRule(0, SHOP, "shop", "supermarket");
        index.finishInitializingTags();

        theBoxIsTheTileIn31BitNumbers();
        aRoadKeepsItsTagsAndPoints();
        anAreaKeepsItsHoleAndDropsItsClosingPoint();
        aBuildingIsKeptWholeAtBuildingZoom();
        pointsOutsideTheTileAreLeftToTheirOwnTile();
        aCoastAcrossTheTilePutsTheSeaOnItsRight();
        anIslandIsLandInTheSea();
        openSeaIsTheWholeTile();
        noMapDataLeavesTheMapsforgeFile();
        System.out.println("jni-host-test: ok " + passed + " OsmAnd map tile checks");
    }

    /** Tile 1000, 2000 at zoom 12: 2^19 31-bit units a side. */
    private static final int Z = 12, TX = 1000, TY = 2000;
    private static final int SIDE = 1 << (31 - Z);
    private static final int LEFT = TX * SIDE, TOP = TY * SIDE;

    private static int x(double fraction) {
        return LEFT + (int) Math.round(fraction * SIDE);
    }

    private static int y(double fraction) {
        return TOP + (int) Math.round(fraction * SIDE);
    }

    private static void theBoxIsTheTileIn31BitNumbers() {
        ObfMapTiles.Box box = ObfMapTiles.INSTANCE.box(TX, TY, Z);
        check(box.getLeft() == LEFT && box.getTop() == TOP && box.getRight() == LEFT + SIDE && box.getBottom() == TOP + SIDE,
            "a tile's box is its 31-bit square");
        check(box.x(LEFT) == 0f && box.x(LEFT + SIDE) == Tile.SIZE && box.y(TOP + SIDE / 2) == Tile.SIZE / 2f,
            "and its edges are the tile's pixel edges");
        ObfMapTiles.Box last = ObfMapTiles.INSTANCE.box(1, 1, 1);
        check(last.getRight() == Integer.MAX_VALUE && last.getBottom() == Integer.MAX_VALUE, "the world's last tile stays in range");
    }

    private static BinaryMapDataObject object(long id, int[] coordinates, boolean area, int[] types, int[] additional) {
        BinaryMapDataObject o = new BinaryMapDataObject(id, coordinates, new int[0][], 0, area, types, additional, 0, 0);
        o.setMapIndex(index);
        return o;
    }

    private static MapElement draw(BinaryMapDataObject o, int zoom, int tx, int ty) {
        MapElement e = new MapElement();
        TileClipper clipper = new TileClipper(-16, -16, Tile.SIZE + 16, Tile.SIZE + 16);
        TileSeparator separator = new TileSeparator(0, 0, Tile.SIZE, Tile.SIZE);
        boolean drawn = ObfMapTiles.INSTANCE.element(o, ObfMapTiles.INSTANCE.box(tx, ty, zoom), e, clipper, separator);
        return drawn ? e : null;
    }

    private static void aRoadKeepsItsTagsAndPoints() {
        BinaryMapDataObject road = object(10, new int[] {x(-0.5), y(0.25), x(0.5), y(0.25), x(0.5), y(0.75)},
            false, new int[] {HIGHWAY}, new int[] {ONEWAY});
        road.putObjectName(NAME, "Interstate 35");
        road.putObjectName(REF, "I 35");
        MapElement e = draw(road, Z, TX, TY);
        check(e != null && e.isLine(), "a road is a line");
        check(e.tags.containsKey("highway") && "motorway".equals(e.tags.getValue("highway")), "with its type as a tag");
        check("yes".equals(e.tags.getValue("oneway")), "its additional types too");
        check("Interstate 35".equals(e.tags.getValue("name")) && "I 35".equals(e.tags.getValue("ref")), "and its names");
        check(e.points[0] == -16f && Math.abs(e.points[1] - Tile.SIZE / 4f) < 0.01f,
            "cut where it enters the tile's margin: " + e.points[0] + "," + e.points[1]);
        int last = e.index[0] - 2;
        check(Math.abs(e.points[last] - Tile.SIZE / 2f) < 0.01f && Math.abs(e.points[last + 1] - 3 * Tile.SIZE / 4f) < 0.01f,
            "and ending where it ends, in tile pixels");
        check(e.layer == ObfMapTiles.LAYER_GROUND, "on the ground layer: " + e.layer);
    }

    private static void anAreaKeepsItsHoleAndDropsItsClosingPoint() {
        int[] outer = {x(0.1), y(0.1), x(0.9), y(0.1), x(0.9), y(0.9), x(0.1), y(0.9), x(0.1), y(0.1)};
        int[] hole = {x(0.4), y(0.4), x(0.6), y(0.4), x(0.6), y(0.6), x(0.4), y(0.6), x(0.4), y(0.4)};
        BinaryMapDataObject lake = new BinaryMapDataObject(11, outer, new int[][] {hole}, 0, true, new int[] {WATER}, null, 0, 0);
        lake.setMapIndex(index);
        MapElement e = draw(lake, Z, TX, TY);
        check(e != null && e.isPoly(), "an area is a polygon");
        check(e.index[0] == 8 && e.index[1] == 8, "its ring and hole without closing points: " + e.index[0] + "," + e.index[1]);
        check("water".equals(e.tags.getValue("natural")), "tagged as the themes expect");
    }

    private static void aBuildingIsKeptWholeAtBuildingZoom() {
        int z = 17, tx = TX << 5, ty = TY << 5;
        int side = 1 << (31 - z), left = tx * side, top = ty * side;
        // Mostly in this tile, poking out past its right edge.
        int[] ring = {left + side / 2, top + side / 4, left + side + side / 8, top + side / 4,
            left + side + side / 8, top + side / 2, left + side / 2, top + side / 2, left + side / 2, top + side / 4};
        BinaryMapDataObject building = object(12, ring, true, new int[] {BUILDING}, null);
        MapElement e = draw(building, z, tx, ty);
        check(e != null, "a building is drawn in the tile holding most of it");
        float maxX = 0;
        for (int i = 0; i < e.index[0]; i += 2) maxX = Math.max(maxX, e.points[i]);
        check(maxX > Tile.SIZE, "whole, not cut at the tile's edge: " + maxX);
    }

    private static void pointsOutsideTheTileAreLeftToTheirOwnTile() {
        MapElement in = draw(object(13, new int[] {x(0.5), y(0.5)}, false, new int[] {SHOP}, null), Z, TX, TY);
        check(in != null && in.type == org.oscim.core.GeometryBuffer.GeometryType.POINT, "a point in the tile is drawn");
        check(draw(object(14, new int[] {x(1.2), y(0.5)}, false, new int[] {SHOP}, null), Z, TX, TY) == null,
            "a point beyond it is not");
    }

    /** The share of [ring]'s area in the tile's lower half, by sampling. */
    private static double southShare(int[] ring) {
        int inside = 0, south = 0;
        for (int i = 1; i < 40; i++) {
            for (int j = 1; j < 40; j++) {
                double px = LEFT + SIDE * i / 40.0, py = TOP + SIDE * j / 40.0;
                if (contains(ring, px, py)) {
                    inside++;
                    if (py > TOP + SIDE / 2.0) south++;
                }
            }
        }
        return inside == 0 ? -1 : (double) south / inside;
    }

    private static boolean contains(int[] ring, double px, double py) {
        boolean in = false;
        int n = ring.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring[2 * i], yi = ring[2 * i + 1], xj = ring[2 * j], yj = ring[2 * j + 1];
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    private static void aCoastAcrossTheTilePutsTheSeaOnItsRight() {
        // West to east across the middle: OSM keeps land on a coastline's left, so north
        // (the top, smaller y) is land and the sea is to the south.
        BinaryMapDataObject coast = object(15, new int[] {x(-0.2), y(0.5), x(0.5), y(0.45), x(1.2), y(0.5)},
            false, new int[] {COASTLINE}, null);
        List<ObfMapTiles.SeaRing> rings = ObfMapTiles.INSTANCE.coast(List.of(coast), ObfMapTiles.INSTANCE.box(TX, TY, Z));
        check(rings != null && !rings.isEmpty(), "a coastline across the tile gives rings");
        double waterSouth = -1;
        double landSouth = -1;
        for (ObfMapTiles.SeaRing ring : rings) {
            if (ring.getWater()) waterSouth = Math.max(waterSouth, southShare(ring.getCoordinates()));
            else landSouth = Math.max(landSouth, southShare(ring.getCoordinates()));
        }
        check(waterSouth > 0.9, "the water is to the south, on the coast's right: " + waterSouth + " (land " + landSouth + ")");
        check(landSouth < 0.1, "and any land ring is to the north: " + landSouth);
    }

    private static void anIslandIsLandInTheSea() {
        // Counter-clockwise on the ground (north up), land on the left: in OsmAnd's y-down
        // numbers that is east, north, west, south from the south-east corner.
        int[] island = {x(0.6), y(0.6), x(0.6), y(0.4), x(0.4), y(0.4), x(0.4), y(0.6), x(0.6), y(0.6)};
        BinaryMapDataObject coast = object(16, island, false, new int[] {COASTLINE}, null);
        List<ObfMapTiles.SeaRing> rings = ObfMapTiles.INSTANCE.coast(List.of(coast), ObfMapTiles.INSTANCE.box(TX, TY, Z));
        check(rings != null && rings.size() == 2, "an island gives the sea and the island: " + (rings == null ? null : rings.size()));
        check(rings.get(0).getWater() && contains(rings.get(0).getCoordinates(), x(0.1), y(0.1)),
            "the sea first, the whole tile");
        check(!rings.get(1).getWater() && contains(rings.get(1).getCoordinates(), x(0.5), y(0.5)), "then the island on it");
    }

    private static void openSeaIsTheWholeTile() {
        ObfMapTiles.Box box = ObfMapTiles.INSTANCE.box(TX, TY, Z);
        check(ObfMapTiles.INSTANCE.coast(List.of(), box) == null, "no coastline gives no rings, so the index decides");
        MapElement e = new MapElement();
        boolean drawn = ObfMapTiles.INSTANCE.seaElement(new ObfMapTiles.SeaRing(true, ObfMapTiles.INSTANCE.wholeTile(box)), box, e,
            new TileClipper(-16, -16, Tile.SIZE + 16, Tile.SIZE + 16));
        check(drawn && "sea".equals(e.tags.getValue("natural")) && e.index[0] == 8, "open sea is one square of natural=sea");
        check(e.layer == 0, "under everything else");
    }

    private static void noMapDataLeavesTheMapsforgeFile() throws java.io.IOException {
        com.jw6ventures.headlink.ObfMapSource none = new com.jw6ventures.headlink.ObfMapSource(List::of, 0, 20, 17);
        check(!none.open().isSuccess(), "no OsmAnd maps: the source does not open, so the map falls back");
        // ExitSignsTest's file has a routing section and no map section.
        java.nio.file.Path file = java.nio.file.Files.createTempFile("obf-map", ".obf");
        try {
            java.nio.file.Files.write(file, ExitSignsTest.syntheticObf());
            com.jw6ventures.headlink.ObfMapSource routingOnly = new com.jw6ventures.headlink.ObfMapSource(
                () -> List.of(com.jw6ventures.headlink.OsmAndRouter.MapFile.of(file.toFile())), 0, 20, 17);
            check(!routingOnly.open().isSuccess() && routingOnly.getFiles().isEmpty(), "nor does a file with no map section");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("OsmAnd map tiles: " + what);
        passed++;
    }
}
