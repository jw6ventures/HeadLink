// The saved places' stored form and bounds on a desktop JVM: what a name may hold, which
// numbers can be a place, and that one bad line in the store does not empty the list.
// Favorite is pure Kotlin with no Android API; the SharedPreferences store around it needs
// a device.
import java.util.ArrayList;
import java.util.List;
import org.openautoandroid.source.Favorite;

public final class FavoritesTest {
    private static int passed;

    public static void main(String[] args) {
        aNameIsPrintableTrimmedAndBounded();
        onlyAPlaceCanBeAFavorite();
        theStoredFormReadsBackWhole();
        aBadLineDropsOnlyItself();
        aPlaceArrivedAtIsSavedOnlyOnce();
        theRecentPlaceTopsTheTileUnlessSaved();
        aMovedPlaceLeavesTheRestInOrder();
        System.out.println("jni-host-test: ok " + passed + " favorites checks");
    }

    private static void aNameIsPrintableTrimmedAndBounded() {
        Favorite home = Favorite.Companion.of("  Home ", 30.2672, -97.7431);
        check(home != null && "Home".equals(home.getName()), "a name is trimmed");
        check(Favorite.Companion.of("", 30.2672, -97.7431) == null, "an empty name is no favorite");
        check(Favorite.Companion.of(" \t\n ", 30.2672, -97.7431) == null, "nor is one that is all whitespace");
        // A tab or a line break would split the stored form; they are removed, not escaped.
        Favorite odd = Favorite.Companion.of("Mom\tand\nDad", 30.2672, -97.7431);
        check(odd != null && "MomandDad".equals(odd.getName()), "control characters are dropped from a name");
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 100; i++) longName.append('x');
        Favorite bounded = Favorite.Companion.of(longName.toString(), 30.2672, -97.7431);
        check(bounded != null && bounded.getName().length() == Favorite.NAME_MAX, "a long name is cut to the bound");
    }

    /** The same rule a shared destination is held to: 0,0 and out-of-range are not places. */
    private static void onlyAPlaceCanBeAFavorite() {
        check(Favorite.Companion.of("Nowhere", 0.0, 0.0) == null, "0,0 is not a place");
        check(Favorite.Companion.of("North", 91.0, 0.0) == null, "a latitude past the pole is not a place");
        check(Favorite.Companion.of("East", 30.0, 181.0) == null, "nor a longitude past the antimeridian");
        check(Favorite.Companion.of("Nan", Double.NaN, -97.7) == null, "nor a number that is not one");
        check(Favorite.Companion.of("Austin", 30.2672, -97.7431) != null, "Austin is");
    }

    private static void theStoredFormReadsBackWhole() {
        check(Favorite.Companion.decode(null).isEmpty(), "nothing stored is an empty list");
        check(Favorite.Companion.decode("").isEmpty(), "so is an empty store");
        check("".equals(Favorite.Companion.encode(new ArrayList<>())), "and an empty list stores as nothing");

        List<Favorite> places = new ArrayList<>();
        places.add(new Favorite("Home", 30.26720, -97.74310));
        places.add(new Favorite("Work, downtown", 30.2711, -97.7437));
        places.add(new Favorite("Mom & Dad's", 29.4241, -98.4936));
        List<Favorite> back = Favorite.Companion.decode(Favorite.Companion.encode(places));
        check(back.equals(places), "three places store and read back exactly, order kept");

        List<Favorite> many = new ArrayList<>();
        for (int i = 0; i < Favorite.MAX + 10; i++) many.add(new Favorite("Place " + i, 30.0 + i * 0.001, -97.0));
        check(Favorite.Companion.decode(Favorite.Companion.encode(many)).size() == Favorite.MAX,
                "a store past the bound reads back only the bound");
    }

    private static void aBadLineDropsOnlyItself() {
        String store = "garbage\n"
                + "abc\t1\t2\n"
                + "30.1\t-97.2\n"
                + "30.1\t-97.2\tOK\n"
                + "0\t0\tNowhere\n"
                + "30.3\t-97.4\t\n";
        List<Favorite> read = Favorite.Companion.decode(store);
        check(read.size() == 1 && "OK".equals(read.get(0).getName()), "one good line survives five bad ones: " + read);
    }

    /**
     * Arriving makes the destination the recent one unless a saved place is already there
     * (Navigation.arrived): the same address shared again lands a few metres off.
     */
    private static void aPlaceArrivedAtIsSavedOnlyOnce() {
        List<Favorite> places = new ArrayList<>();
        check(!Favorite.Companion.saved(places, 30.2672, -97.7431), "nothing is saved in an empty list");
        places.add(new Favorite("Home", 30.2672, -97.7431));
        check(Favorite.Companion.saved(places, 30.2672, -97.7431), "the saved point itself is saved");
        // 0.0003 degrees of latitude is about 33 m; 0.0006 about 67 m.
        check(Favorite.Companion.saved(places, 30.2675, -97.7431), "33 m off is the same place");
        check(!Favorite.Companion.saved(places, 30.2678, -97.7431), "67 m off is another");
        check(!Favorite.Companion.saved(places, 30.2711, -97.7437), "and downtown is not home");
    }

    /**
     * The tile lists the recent destination above the saved places, not among them, and
     * drops it once it is one of them (Favorite.tile).
     */
    private static void theRecentPlaceTopsTheTileUnlessSaved() {
        List<Favorite> places = new ArrayList<>();
        places.add(new Favorite("Home", 30.2672, -97.7431));
        places.add(new Favorite("Work", 30.2711, -97.7437));
        check(Favorite.Companion.tile(places, null) == places, "no recent place lists the saved ones alone");
        Favorite store = new Favorite("H-E-B", 30.3072, -97.7560);
        List<Favorite> tile = Favorite.Companion.tile(places, store);
        check("H-E-BHomeWork".equals(names(tile)), "the recent place is on top: " + names(tile));
        check("HomeWork".equals(names(places)), "and the saved list is left as it was");
        Favorite nearHome = new Favorite("12 Main St", 30.2675, -97.7431);
        check(Favorite.Companion.tile(places, nearHome) == places, "a recent place 33 m from home is home, not another row");
        List<Favorite> full = new ArrayList<>();
        for (int i = 0; i < Favorite.MAX; i++) full.add(new Favorite("Place " + i, 31.0 + i * 0.01, -97.0));
        check(Favorite.Companion.tile(full, store).size() == Favorite.MAX + 1, "a full list still has the recent place on top");
    }

    /** Reordering on the connection screen (Favorites.move): one place moves, the rest keep their order. */
    private static void aMovedPlaceLeavesTheRestInOrder() {
        List<Favorite> places = new ArrayList<>();
        for (String name : new String[] {"A", "B", "C", "D"}) places.add(new Favorite(name, 30.0, -97.0));
        check("BCAD".equals(names(Favorite.Companion.moved(places, 0, 2))), "the first moved down two lands third");
        check("DABC".equals(names(Favorite.Companion.moved(places, 3, 0))), "the last moved to the top pushes the rest down");
        check("ACBD".equals(names(Favorite.Companion.moved(places, 2, 1))), "a move up by one swaps two neighbours");
        check(Favorite.Companion.moved(places, 1, 1) == places, "a move to its own row changes nothing");
        check(Favorite.Companion.moved(places, 4, 0) == places, "nor does a move from a row that is not there");
        check(Favorite.Companion.moved(places, 0, -1) == places, "nor one to a row that is not there");
        check("ABCD".equals(names(places)), "and the list moved from is left as it was");
    }

    private static String names(List<Favorite> places) {
        StringBuilder all = new StringBuilder();
        for (Favorite place : places) all.append(place.getName());
        return all.toString();
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
