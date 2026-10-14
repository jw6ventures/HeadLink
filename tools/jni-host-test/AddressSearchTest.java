// P11.4's typed address search (docs/p11-plan.md §4, §5): the parser's readings of US-style
// input, the canonical forms of street and road names, and the search over a synthetic
// ObfSource - every SuggestionKind, odd/even/step/alphabetic ranges, the closest number on
// another stretch of a split street, the order, the state filter, cancellation, a file that
// fails to read, and a report line with no place in it.
//
// With a directory holding Us_texas_austin_northamerica.obf as the argument (OBF_DIR), it
// also searches the real Austin file through ObfFile and prints distances and host timings;
// without it those checks are skipped and the summary line says so.
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.openautoandroid.source.AddressQuery;
import org.openautoandroid.source.AddressSearch;
import org.openautoandroid.source.AddressText;
import org.openautoandroid.source.CityType;
import org.openautoandroid.source.GeoBox;
import org.openautoandroid.source.HouseNumber;
import org.openautoandroid.source.Interpolation;
import org.openautoandroid.source.ObfBuilding;
import org.openautoandroid.source.ObfCity;
import org.openautoandroid.source.ObfFile;
import org.openautoandroid.source.ObfPoi;
import org.openautoandroid.source.ObfSource;
import org.openautoandroid.source.ObfStreet;
import org.openautoandroid.source.PoiKind;
import org.openautoandroid.source.SearchStats;
import org.openautoandroid.source.Suggestion;
import org.openautoandroid.source.SuggestionKind;

public final class AddressSearchTest {
    private static int passed;

    /** Downtown Austin, where the fake's Austin is and the phone is. */
    private static final double NEAR_LAT = 30.2747;
    private static final double NEAR_LON = -97.7404;

    public static void main(String[] args) throws Exception {
        houseNumbersAreRead();
        streetNamesFoldBothWays();
        theParserOffersEveryReading();
        anExactNumberComesFirst();
        aRangeGivesAnEstimate();
        theClosestNumberKeepsToItsSide();
        aSplitStreetIsOneStreet();
        streetsCitiesAndPostcodes();
        theOrderIsThePlans();
        theStateDropsOtherStatesFiles();
        plainNamesFindPlaces();
        aNewerKeystrokeCancels();
        aFileThatFailsIsCountedAndSkipped();
        theReportHasCountsOnly();
        String dir = args.length > 0 ? args[0] : "";
        File austin = new File(dir, "Us_texas_austin_northamerica.obf");
        if (!dir.isEmpty() && austin.isFile()) {
            theRealAustinFile(austin);
            System.out.println("jni-host-test: ok " + passed + " address search checks");
        } else {
            System.out.println("jni-host-test: ok " + passed
                + " address search checks (real-file checks skipped: need OBF_DIR with Us_texas_austin_northamerica.obf)");
        }
    }

    // ---- parser ----------------------------------------------------------------------------

    private static void houseNumbersAreRead() {
        HouseNumber n = HouseNumber.Companion.parse("123");
        check(n != null && n.getValue() == 123 && n.getSuffix().isEmpty() && "123".equals(n.getText()), "123");
        n = HouseNumber.Companion.parse("123B");
        check(n != null && n.getValue() == 123 && "b".equals(n.getSuffix()) && "123B".equals(n.getText()), "123B");
        n = HouseNumber.Companion.parse("123 1/2");
        check(n != null && n.getValue() == 123 && "1/2".equals(n.getSuffix()), "123 1/2");
        n = HouseNumber.Companion.parse("123½");
        check(n != null && n.getValue() == 123 && "1/2".equals(n.getSuffix()), "123½");
        n = HouseNumber.Companion.parse("111-400");
        check(n != null && n.getValue() == 111 && n.getSuffix().isEmpty(), "a stored range reads as its first number");
        check(HouseNumber.Companion.parse("Rear") == null, "no digits is no number");
    }

    private static void streetNamesFoldBothWays() {
        same("Main St", "MAIN STREET");
        same("N Lamar Blvd", "North Lamar Boulevard");
        same("N. Lamar Blvd.", "north lamar boulevard");
        same("FM 1431", "Farm to Market Road 1431");
        same("FM 1431", "Farm-to-Market Road 1431");
        same("RM 2222", "Ranch to Market Road 2222");
        same("RM 2222", "Ranch Road 2222");
        same("RR 2222", "RM 2222");
        same("SH 71", "State Highway 71");
        same("TX 71", "SH 71");
        same("US 183", "U.S. Highway 183");
        same("US 183", "US-183");
        same("US 183", "US Highway 183");
        same("I-35", "IH 35");
        same("I-35", "Interstate Highway 35");
        same("I-35 Frontage Rd", "Interstate 35 Frontage Road");
        same("CR 123", "County Road 123");
        same("Loop 360", "State Loop 360");
        same("Pkwy", "Parkway");
        same("Fwy", "Freeway");
        same("Expy", "Expressway");
        same("Ct", "Court");
        same("Cir", "Circle");
        same("Trl", "Trail");
        same("Pl", "Place");
        same("Ln", "Lane");
        same("Hwy", "Highway");
        same("Dr", "Drive");
        same("Rd", "Road");
        same("Ave", "Avenue");
        same("NE Loop", "Northeast Loop");
        same("O'Henry Rd", "Ohenry Road");
        same("Café Way", "Cafe Way");
        check("us".equals(AddressText.INSTANCE.normalize("Us")), "a route prefix alone is left as typed");
    }

    private static void same(String a, String b) {
        String na = AddressText.INSTANCE.normalize(a);
        String nb = AddressText.INSTANCE.normalize(b);
        check(na.equals(nb), "'" + a + "' and '" + b + "' fold the same (" + na + " / " + nb + ")");
    }

    private static void theParserOffersEveryReading() {
        List<AddressQuery> r = AddressQuery.Companion.parse("123 Main St Austin TX 78701");
        check(has(r, 123, "main st", "austin", "texas", "78701"), "number, street, city, state and ZIP: " + r);
        r = AddressQuery.Companion.parse("123 MAIN ST., AUSTIN, Texas");
        check(has(r, 123, "main st", "austin", "texas", null), "case, dots and commas: " + r);
        check(r.stream().anyMatch(q -> q.getCityFromComma()), "a comma marks the city");
        r = AddressQuery.Companion.parse("123 Main St Round Rock");
        check(has(r, 123, "main st", "round rock", null, null), "two-word city: " + r);
        check(has(r, 123, "main st round", "rock", null, null), "one-word city reading too");
        check(has(r, 123, "main st round rock", null, null, null), "and no city at all (cities near the phone)");
        r = AddressQuery.Companion.parse("123 Main St Au");
        check(has(r, 123, "main st", "au", null, null), "a city being typed: " + r);
        r = AddressQuery.Companion.parse("123B Main Street");
        check(r.get(0).getHouseNumber() != null && "b".equals(r.get(0).getHouseNumber().getSuffix()), "123B");
        r = AddressQuery.Companion.parse("123 1/2 Main St");
        check("1/2".equals(r.get(0).getHouseNumber().getSuffix()) && "main st".equals(r.get(0).getStreet()), "123 1/2: " + r);
        r = AddressQuery.Companion.parse("123½ Main St");
        check("1/2".equals(r.get(0).getHouseNumber().getSuffix()) && "main st".equals(r.get(0).getStreet()), "123½: " + r);
        r = AddressQuery.Companion.parse("1st St");
        check(r.stream().allMatch(q -> q.getHouseNumber() == null) && has(r, -1, "1st st", null, null, null), "1st is a street: " + r);
        r = AddressQuery.Companion.parse("Oak Ct");
        check(has(r, -1, "oak", null, "connecticut", null) && has(r, -1, "oak ct", null, null, null),
            "a trailing code is a state and a word both: " + r);
        r = AddressQuery.Companion.parse("I-35 Frontage Rd Austin");
        check(has(r, -1, "i 35 frontage rd", "austin", null, null), "a numbered road: " + r);
        r = AddressQuery.Companion.parse("500 FM 1431 Cedar Park");
        check(has(r, 500, "fm 1431", "cedar park", null, null), "a house number on a farm road: " + r);
        r = AddressQuery.Companion.parse("Austin");
        check(r.stream().anyMatch(AddressQuery::getCityOnly), "a city alone: " + r);
        r = AddressQuery.Companion.parse("78701");
        check(r.size() == 1 && "78701".equals(r.get(0).getZip()) && r.get(0).getStreet().isEmpty(), "a ZIP alone: " + r);
        r = AddressQuery.Companion.parse("Austin, New York");
        check(r.stream().anyMatch(q -> "new_york".equals(q.getState())), "a two-word state: " + r);
        check(AddressQuery.Companion.parse("  ,, ").isEmpty(), "nothing typed is no reading");
        r = AddressQuery.Companion.parse("1100 Congress Ave Austin T");
        check(has(r, 1100, "congress ave", "austin", null, null), "a state being typed is also read without it: " + r);
        r = AddressQuery.Companion.parse("1100 Congress Ave Austin Tex");
        check(has(r, 1100, "congress ave", "austin", null, null), "and its third letter: " + r);
        check(AddressQuery.Companion.parse("11").isEmpty(), "two digits are no reading yet");
        r = AddressQuery.Companion.parse("787");
        check(r.size() == 1 && "787".equals(r.get(0).getCity()), "three are a ZIP's start: " + r);
    }

    /** A reading with these parts; number -1 for none, null city/state/zip for none. */
    private static boolean has(List<AddressQuery> r, int number, String street, String city, String state, String zip) {
        for (AddressQuery q : r) {
            HouseNumber n = q.getHouseNumber();
            if (number < 0 ? n != null : n == null || n.getValue() != number) continue;
            if (!street.equals(q.getStreet())) continue;
            if (city == null ? q.getCity() != null : !city.equals(q.getCity())) continue;
            if (state == null ? q.getState() != null : !state.equals(q.getState())) continue;
            if (zip == null ? q.getZip() != null : !zip.equals(q.getZip())) continue;
            return true;
        }
        return false;
    }

    // ---- the fake file ---------------------------------------------------------------------

    /** A hand-built file: Austin, Round Rock, Aubrey and the ZIP 78701 in Texas. */
    private static final class Fake implements ObfSource {
        final String name;
        final List<ObfCity> cities = new ArrayList<>();
        final Map<ObfCity, List<ObfStreet>> streets = new HashMap<>();
        final Map<ObfStreet, List<ObfBuilding>> buildings = new HashMap<>();
        final List<ObfPoi> pois = new ArrayList<>();
        final AtomicInteger reads = new AtomicInteger();
        Runnable onStreets = () -> { };
        boolean failCities;
        long handles;

        Fake(String name) {
            this.name = name;
        }

        @Override public String getName() {
            return name;
        }

        @Override public GeoBox getBounds() {
            return new GeoBox(29.0, -99.0, 31.5, -96.0);
        }

        @Override public List<ObfCity> cities() {
            reads.incrementAndGet();
            if (failCities) throw sneaky(new IOException("short read"));
            return cities;
        }

        @Override public List<ObfStreet> streets(ObfCity city) {
            reads.incrementAndGet();
            onStreets.run();
            return streets.getOrDefault(city, List.of());
        }

        @Override public List<ObfBuilding> buildings(ObfStreet street) {
            reads.incrementAndGet();
            return buildings.getOrDefault(street, List.of());
        }

        @Override public List<ObfPoi> pois(GeoBox box, Set<? extends PoiKind> kinds, int limit, double centreLat,
                double centreLon, String nameQuery) {
            reads.incrementAndGet();
            List<ObfPoi> out = new ArrayList<>();
            for (ObfPoi p : pois) {
                if (!box.contains(p.getLatitude(), p.getLongitude())) continue;
                if (nameQuery != null && !p.getName().toLowerCase(Locale.ROOT).contains(nameQuery.toLowerCase(Locale.ROOT))) continue;
                if (nameQuery == null && !kinds.contains(p.getKind())) continue;
                out.add(p);
            }
            return out;
        }

        ObfCity city(String n, CityType type, double lat, double lon) {
            ObfCity c = new ObfCity(n, null, type, lat, lon, name, ++handles);
            cities.add(c);
            streets.put(c, new ArrayList<>());
            return c;
        }

        ObfStreet street(ObfCity c, String n, double lat, double lon, ObfBuilding... bs) {
            ObfStreet s = new ObfStreet(n, null, lat, lon, c, ++handles);
            streets.get(c).add(s);
            buildings.put(s, List.of(bs));
            return s;
        }
    }

    /** Kotlin declares no checked exceptions, as the reader's IOException arrives. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    private static ObfBuilding house(String number, double lat, double lon) {
        return new ObfBuilding(number, lat, lon, null, null, null, Interpolation.NONE, 0, null);
    }

    private static ObfBuilding house(String number, double lat, double lon, String postcode) {
        return new ObfBuilding(number, lat, lon, null, null, null, Interpolation.NONE, 0, postcode);
    }

    private static ObfBuilding range(String from, double lat, double lon, String to, double lat2, double lon2,
            Interpolation kind, int step) {
        return new ObfBuilding(from, lat, lon, to, lat2, lon2, kind, step, null);
    }

    private static Fake texas() {
        Fake f = new Fake("Us_texas_test_northamerica.obf");
        ObfCity austin = f.city("Austin", CityType.CITY, 30.2711, -97.7437);
        // Main Street in two stretches: north has 101..200, south 1001 and 1050.
        f.street(austin, "Main Street (North)", 30.300, -97.740,
            house("101", 30.3001, -97.7401), house("103", 30.3003, -97.7401), house("105", 30.3005, -97.7401),
            house("200", 30.3020, -97.7402));
        f.street(austin, "Main Street (South)", 30.250, -97.740,
            house("1001", 30.2501, -97.7401), house("1050", 30.2550, -97.7402));
        // Oak Lane: TIGER-style odd and even ranges, 101..199 and 100..198.
        f.street(austin, "Oak Lane", 30.285, -97.750,
            range("101", 30.280, -97.750, "199", 30.290, -97.750, Interpolation.ODD, 0),
            range("100", 30.280, -97.7502, "198", 30.290, -97.7502, Interpolation.EVEN, 0));
        // Elm Court: every 4th number from 10 to 50.
        f.street(austin, "Elm Court", 30.260, -97.760,
            range("10", 30.260, -97.760, "50", 30.264, -97.760, Interpolation.STEP, 4));
        // Pine Road: 12A to 12F.
        f.street(austin, "Pine Road", 30.270, -97.770,
            range("12A", 30.270, -97.770, "12F", 30.275, -97.770, Interpolation.ALPHABETIC, 0));
        // Cedar Way: an all-numbers range, and a descending one.
        f.street(austin, "Cedar Way", 30.240, -97.730,
            range("300", 30.240, -97.730, "310", 30.241, -97.730, Interpolation.ALL, 0),
            range("420", 30.245, -97.730, "400", 30.244, -97.730, Interpolation.EVEN, 0));
        f.street(austin, "Quiet Trail", 30.231, -97.721);
        f.street(austin, "Congress Avenue", 30.276, -97.740, house("1100", 30.2747, -97.7404, "78701"),
            house("1099", 30.2726, -97.7411), house("1301", 30.2765, -97.7396));
        // As in the real file: a range on South Congress holds 1101, downtown Congress has none.
        f.street(austin, "South Congress Avenue", 30.250, -97.749, house("1100", 30.2520, -97.7490),
            range("1001", 30.2540, -97.7480, "1199", 30.2500, -97.7500, Interpolation.ODD, 0));
        f.street(austin, "Congressional Circle", 30.282, -97.842, house("1100", 30.2820, -97.8420));
        f.street(austin, "Farm to Market Road 1431", 30.500, -97.900, house("500", 30.5001, -97.9001));
        f.street(austin, "Austin Avenue", 30.264, -97.730);
        ObfCity roundRock = f.city("Round Rock", CityType.CITY, 30.5083, -97.6789);
        f.street(roundRock, "Main Street", 30.508, -97.679, house("123", 30.5081, -97.6791));
        f.street(roundRock, "Oak Lane", 30.510, -97.680, house("123", 30.5101, -97.6801));
        ObfCity aubrey = f.city("Aubrey", CityType.VILLAGE, 33.30, -96.98);
        f.street(aubrey, "Main Street", 33.30, -96.98, house("123", 33.3001, -96.9801));
        // The file's own point for a postcode can be far off, as the real 78701's is.
        ObfCity zip = f.city("78701", CityType.POSTCODE, 30.3410, -97.7045);
        f.street(zip, "Congress Avenue", 30.276, -97.740, house("1100", 30.2747, -97.7404, "78701"));
        f.street(zip, "East 6th Street", 30.267, -97.738);
        f.street(zip, "North Lamar Boulevard", 30.421, -97.674);
        f.pois.add(new ObfPoi(1, 30.2657, -97.7497, "Torchy's Tacos", null, "sustenance", "fast_food", PoiKind.FOOD,
            "Torchy's Tacos", null, f.name));
        f.pois.add(new ObfPoi(2, 30.2591, -97.7117, "H-E-B", null, "shop", "supermarket", PoiKind.SHOP, "H-E-B", null, f.name));
        f.pois.add(new ObfPoi(3, 30.2394, -97.7546, "Oltorf (HEB)", null, "transportation", "bus_stop", null, null, null, f.name));
        f.pois.add(new ObfPoi(4, 32.0, -97.0, "Torchy's Tacos", null, "sustenance", "fast_food", PoiKind.FOOD, null, null, f.name));
        return f;
    }

    private static List<Suggestion> ask(AddressSearch s, String text) {
        return s.suggest(text, NEAR_LAT, NEAR_LON, 8, () -> false);
    }

    // ---- search ----------------------------------------------------------------------------

    private static void anExactNumberComesFirst() {
        Fake f = texas();
        AddressSearch s = new AddressSearch(() -> List.of(f));
        List<Suggestion> r = ask(s, "1100 Congress Ave Austin");
        check(r.get(0).getKind() == SuggestionKind.EXACT && r.get(0).getLabel().equals("1100 Congress Avenue, Austin"),
            "exact first: " + show(r));
        check(r.get(0).getDetail().equals("78701"), "an exact number's line two is its ZIP: " + show(r));
        check(near(r.get(0), 30.2747, -97.7404, 1), "at the building");
        r = ask(s, "123 Main St Round Rock");
        check(r.get(0).getKind() == SuggestionKind.EXACT && r.get(0).getLabel().equals("123 Main Street, Round Rock"),
            "a two-word city: " + show(r));
        r = ask(s, "123 Main St Au");
        check(r.stream().noneMatch(x -> x.getLabel().contains("Round Rock")), "a city being typed narrows to Au...: " + show(r));
        check(r.stream().anyMatch(x -> x.getLabel().contains("Main Street") && x.getDetail().startsWith("Austin")),
            "'123 Main St Au' already suggests Main Street in Austin: " + show(r));
        r = ask(s, "500 FM 1431");
        check(r.get(0).getKind() == SuggestionKind.EXACT && r.get(0).getLabel().startsWith("500 Farm to Market Road 1431"),
            "FM 1431 finds Farm to Market Road 1431: " + show(r));
        r = ask(s, "1100 Congress Ave 78701");
        check(r.get(0).getKind() == SuggestionKind.EXACT && r.get(0).getLabel().equals("1100 Congress Avenue, 78701"),
            "a ZIP stands for the city: " + show(r));
    }

    private static void aRangeGivesAnEstimate() {
        Fake f = texas();
        AddressSearch s = new AddressSearch(() -> List.of(f));
        List<Suggestion> r = ask(s, "123 Oak Ln Austin");
        Suggestion e = r.get(0);
        check(e.getKind() == SuggestionKind.INTERPOLATED && e.getLabel().equals("123 Oak Lane (estimated)")
            && e.getDetail().equals("Austin"), "odd range: " + show(r));
        check(near(e, 30.280 + 0.010 * 22 / 98.0, -97.750, 1), "placed along the odd line, not the even one: " + show(r));
        r = ask(s, "124 Oak Ln Austin");
        check(r.get(0).getKind() == SuggestionKind.INTERPOLATED && near(r.get(0), 30.280 + 0.010 * 24 / 98.0, -97.7502, 1),
            "even range: " + show(r));
        r = ask(s, "22 Elm Ct Austin");
        check(r.get(0).getKind() == SuggestionKind.INTERPOLATED && near(r.get(0), 30.260 + 0.004 * 12 / 40.0, -97.760, 1),
            "a step range holds 22: " + show(r));
        r = ask(s, "20 Elm Ct Austin");
        check(r.get(0).getKind() == SuggestionKind.CLOSEST, "a step range doesn't hold 20: " + show(r));
        check(labels(r).contains("No 20 on Elm Court — 18 Elm Court") && labels(r).contains("No 20 on Elm Court — 22 Elm Court"),
            "its neighbours on the range instead: " + show(r));
        check(r.stream().allMatch(x -> x.getKind() != SuggestionKind.CLOSEST || x.getDetail().contains("estimated")),
            "a neighbour worked out from a range says so: " + show(r));
        r = ask(s, "12C Pine Rd Austin");
        check(r.get(0).getKind() == SuggestionKind.INTERPOLATED && r.get(0).getLabel().equals("12C Pine Road (estimated)")
            && near(r.get(0), 30.270 + 0.005 * 2 / 5.0, -97.770, 1), "letters: " + show(r));
        r = ask(s, "12G Pine Rd Austin");
        check(r.stream().noneMatch(x -> x.getKind() == SuggestionKind.INTERPOLATED)
            && labels(r).contains("No 12G on Pine Road — 12F Pine Road"), "12G is past 12F: " + show(r));
        r = ask(s, "305 Cedar Way Austin");
        check(r.get(0).getKind() == SuggestionKind.INTERPOLATED && near(r.get(0), 30.2405, -97.730, 1), "all numbers: " + show(r));
        r = ask(s, "416 Cedar Way Austin");
        check(r.get(0).getKind() == SuggestionKind.INTERPOLATED && near(r.get(0), 30.245 - 0.001 * 4 / 20.0, -97.730, 1),
            "a range numbered downwards: " + show(r));
    }

    private static void theClosestNumberKeepsToItsSide() {
        Fake f = texas();
        AddressSearch s = new AddressSearch(() -> List.of(f));
        List<Suggestion> r = ask(s, "102 Main St Austin");
        check(r.size() >= 2 && r.get(0).getKind() == SuggestionKind.CLOSEST && r.get(1).getKind() == SuggestionKind.CLOSEST,
            "two closest: " + show(r));
        // Nothing even below 102, so 101 (other side); above, 200 is even.
        check(labels(r).contains("No 102 on Main Street — 101 Main Street"), "below, other side: " + show(r));
        check(labels(r).contains("No 102 on Main Street — 200 Main Street"), "above, same side over 103: " + show(r));
        Suggestion below = find(r, "— 101 ");
        Suggestion above = find(r, "— 200 ");
        check(below.getDetail().equals("Austin · below, other side") && above.getDetail().equals("Austin · above"),
            "line two says which: " + below.getDetail() + " / " + above.getDetail());
        check(near(above, 30.3020, -97.7402, 1), "at 200's own position");
        r = ask(s, "104 Main St Austin");
        check(labels(r).contains("No 104 on Main Street — 103 Main Street"), "nearest below of either side when none on this one");
        r = ask(s, "50 Main St Austin");
        check(r.stream().filter(x -> x.getKind() == SuggestionKind.CLOSEST && x.getLabel().startsWith("No 50 on Main Street"))
            .count() == 1 && labels(r).contains("No 50 on Main Street — 200 Main Street"), "only above when below is empty: " + show(r));
        r = ask(s, "103B Main St Austin");
        check(labels(r).contains("No 103B on Main Street — 103 Main Street"), "123B falls back to 123: " + show(r));
        r = ask(s, "5 Quiet Trl Austin");
        check(r.get(0).getKind() == SuggestionKind.STREET && r.get(0).getLabel().equals("Quiet Trail, Austin (no house numbers)")
            && near(r.get(0), 30.231, -97.721, 1), "a street with no numbers: " + show(r));
    }

    private static void aSplitStreetIsOneStreet() {
        Fake f = texas();
        AddressSearch s = new AddressSearch(() -> List.of(f));
        List<Suggestion> r = ask(s, "107 Main St Austin");
        // Above 107 on the same side, nothing odd on the north stretch: 1001 on the south one.
        check(labels(r).contains("No 107 on Main Street — 105 Main Street")
            && labels(r).contains("No 107 on Main Street — 1001 Main Street"), "across stretches: " + show(r));
        check(near(find(r, "— 1001 "), 30.2501, -97.7401, 1), "at the other stretch's building");
        r = ask(s, "Main St Austin");
        long streets = r.stream().filter(x -> x.getKind() == SuggestionKind.STREET && x.getLabel().equals("Main Street, Austin")).count();
        check(streets == 1, "two stretches, one suggestion: " + show(r));
        check(find(r, "Main Street, Austin").getDetail().equals("South"), "the stretch nearest the phone, named on line two: " + show(r));
    }

    private static void streetsCitiesAndPostcodes() {
        Fake f = texas();
        AddressSearch s = new AddressSearch(() -> List.of(f));
        List<Suggestion> r = ask(s, "Au");
        check(r.get(0).getKind() == SuggestionKind.CITY && r.get(0).getLabel().equals("Austin"), "Au is Austin first: " + show(r));
        check(r.stream().anyMatch(x -> x.getKind() == SuggestionKind.CITY && x.getLabel().equals("Aubrey")), "and Aubrey");
        r = ask(s, "Austin");
        check(r.get(0).getKind() == SuggestionKind.CITY && r.get(0).getLabel().equals("Austin"),
            "a full city name before Austin Avenue: " + show(r));
        r = ask(s, "78701");
        check(r.get(0).getKind() == SuggestionKind.POSTCODE && r.get(0).getLabel().equals("78701") && near(r.get(0), 30.276, -97.738, 1),
            "a ZIP, at the middle of its streets: " + show(r));
        r = ask(s, "787");
        check(r.size() == 1 && r.get(0).getKind() == SuggestionKind.POSTCODE, "a ZIP being typed: " + show(r));
        r = ask(s, "Congress Ave Au");
        check(r.get(0).getKind() == SuggestionKind.STREET && r.get(0).getLabel().equals("Congress Avenue, Austin"),
            "a street in a city being typed: " + show(r));
        r = ask(s, "Oak Ln");
        check(r.get(0).getLabel().equals("Oak Lane, Austin") && r.get(1).getLabel().equals("Oak Lane, Round Rock"),
            "no city typed: cities near the phone, nearest first: " + show(r));
    }

    private static void theOrderIsThePlans() {
        Fake f = texas();
        AddressSearch s = new AddressSearch(() -> List.of(f));
        // Exact (Round Rock, a nearby city) before estimated (Austin's Oak Lane would be) before closest.
        List<Suggestion> r = ask(s, "123 Oak Ln");
        check(r.get(0).getKind() == SuggestionKind.EXACT && r.get(0).getLabel().equals("123 Oak Lane, Round Rock")
            && r.get(1).getKind() == SuggestionKind.INTERPOLATED, "exact, then estimated: " + show(r));
        r = ask(s, "1100 Congress Ave");
        check(r.get(0).getLabel().equals("1100 Congress Avenue, Austin"), "the name as typed first: " + show(r));
        check(r.get(1).getLabel().equals("1100 South Congress Avenue, Austin"), "then the name with a direction: " + show(r));
        check(r.stream().noneMatch(x -> x.getLabel().contains("Congressional")), "'Congress Ave' is not 'Congressional Cir'");
        r = ask(s, "1101 Congress Ave");
        check(r.get(0).getKind() == SuggestionKind.CLOSEST && r.get(1).getKind() == SuggestionKind.CLOSEST
            && r.get(0).getLabel().startsWith("No 1101 on Congress Avenue — ") && r.get(1).getLabel().startsWith("No 1101 on Congress Avenue — ")
            && r.get(2).getKind() == SuggestionKind.INTERPOLATED && r.get(2).getLabel().equals("1101 South Congress Avenue (estimated)"),
            "the street as typed before the kind: closest on Congress Avenue, then the estimate on South Congress: " + show(r));
        r = ask(s, "1100 Congress");
        check(r.get(0).getLabel().equals("1100 Congress Avenue, Austin") && r.get(1).getLabel().equals("1100 South Congress Avenue, Austin"),
            "full names (type word left off) by distance: " + show(r));
        check(r.get(2).getLabel().equals("1100 Congressional Circle, Austin"), "then a prefix: " + show(r));
        r = ask(s, "123 Main St Round Rock");
        check(r.get(0).getLabel().contains("Round Rock"), "the typed city before nearby ones: " + show(r));
        r = ask(s, "Main St");
        check(r.get(0).getLabel().equals("Main Street, Austin") && r.get(1).getLabel().equals("Main Street, Round Rock"),
            "then distance: " + show(r));
        check(ask(s, "1100 congress").size() <= 8 && s.suggest("1100 congress", NEAR_LAT, NEAR_LON, 2, () -> false).size() == 2,
            "the limit holds");
        r = s.suggest("1100 Congress Ave Austin", null, null, 8, () -> false);
        check(r.get(0).getKind() == SuggestionKind.EXACT, "no position is fine: " + show(r));
    }

    private static void theStateDropsOtherStatesFiles() {
        Fake tx = texas();
        Fake ok = new Fake("Us_oklahoma_test_northamerica.obf");
        ObfCity ada = ok.city("Ada", CityType.CITY, 34.77, -96.68);
        ok.street(ada, "Main Street", 34.77, -96.68, house("123", 34.7701, -96.6801));
        AddressSearch s = new AddressSearch(() -> List.of(tx, ok));
        List<Suggestion> r = ask(s, "123 Main St Ada OK");
        check(!r.isEmpty() && r.get(0).getKind() == SuggestionKind.EXACT && r.get(0).getFile().equals(ok.name), "OK: " + show(r));
        int before = ok.reads.get();
        r = ask(s, "123 Main St Ada Texas");
        check(r.stream().noneMatch(x -> x.getFile().equals(ok.name)) && ok.reads.get() == before,
            "Texas never opens Oklahoma's file: " + show(r));
        Fake ny = new Fake("Us_new-york_test_northamerica.obf");
        ObfCity albany = ny.city("Albany", CityType.CITY, 42.65, -73.75);
        ny.street(albany, "State Street", 42.65, -73.75, house("1", 42.6501, -73.7501));
        r = new AddressSearch(() -> List.of(tx, ny)).suggest("1 State St Albany New York", null, null, 8, () -> false);
        check(!r.isEmpty() && r.get(0).getFile().equals(ny.name), "a hyphenated region name: " + show(r));
    }

    private static void plainNamesFindPlaces() {
        Fake f = texas();
        AddressSearch s = new AddressSearch(() -> List.of(f));
        List<Suggestion> r = ask(s, "Torchy");
        check(r.size() == 1 && r.get(0).getKind() == SuggestionKind.POI && r.get(0).getLabel().equals("Torchy's Tacos")
            && r.get(0).getDetail().equals("fast food"), "a place by name, near the phone only: " + show(r));
        r = ask(s, "HEB");
        check(r.size() == 2 && r.get(0).getLabel().equals("H-E-B") && r.get(1).getLabel().equals("Oltorf (HEB)"),
            "HEB finds H-E-B, the store before its bus stop: " + show(r));
        r = ask(s, "123 Torchy");
        check(r.stream().noneMatch(x -> x.getKind() == SuggestionKind.POI), "a house number is never a place");
        r = s.suggest("Torchy", null, null, 8, () -> false);
        check(r.isEmpty(), "no position, no place search");
        r = ask(s, "Congress Ave");
        check(r.stream().noneMatch(x -> x.getKind() == SuggestionKind.POI), "a street found, no place search");
    }

    private static void aNewerKeystrokeCancels() {
        Fake f = texas();
        AtomicBoolean stale = new AtomicBoolean();
        AtomicInteger readsAfter = new AtomicInteger(-1);
        f.onStreets = () -> {
            if (!stale.get()) {
                stale.set(true);
                readsAfter.set(f.reads.get());
            }
        };
        AddressSearch s = new AddressSearch(() -> List.of(f));
        List<Suggestion> r = s.suggest("1100 Congress Ave", NEAR_LAT, NEAR_LON, 8, stale::get);
        check(r.isEmpty(), "a cancelled search returns nothing");
        check(f.reads.get() == readsAfter.get(), "and reads nothing after the keystroke: " + f.reads.get() + " vs " + readsAfter.get());
        check(s.getStats().snapshot().getCancelled() == 1, "counted as cancelled");
        f.onStreets = () -> { };
        check(!ask(s, "1100 Congress Ave").isEmpty(), "the next search runs whole");
        Fake g = texas();
        AddressSearch w = new AddressSearch(() -> List.of(g));
        w.warm(NEAR_LAT, NEAR_LON, () -> false);
        int warmed = g.reads.get();
        check(warmed == 1 + 3, "warming reads the cities and the nearest places' streets: " + warmed);
        check(ask(w, "Main St").get(0).getLabel().equals("Main Street, Austin"), "and a search after it answers as before");
    }

    private static void aFileThatFailsIsCountedAndSkipped() {
        Fake good = texas();
        Fake bad = new Fake("Us_texas_broken_northamerica.obf");
        bad.failCities = true;
        AddressSearch s = new AddressSearch(() -> List.of(bad, good));
        List<Suggestion> r = ask(s, "1100 Congress Ave Austin");
        check(!r.isEmpty() && r.get(0).getKind() == SuggestionKind.EXACT, "the other file still answers: " + show(r));
        check(s.getStats().snapshot().getSourcesFailed() == 1, "the failure is counted");
        AddressSearch none = new AddressSearch(() -> {
            throw new IllegalStateException("folder gone");
        });
        check(none.suggest("Austin", NEAR_LAT, NEAR_LON, 8, () -> false).isEmpty(), "no files: no answer, no throw");
        none.warm(NEAR_LAT, NEAR_LON, () -> false);
        new AddressSearch(() -> List.of(bad)).warm(NEAR_LAT, NEAR_LON, () -> false);
        check(true, "warming never throws either");
    }

    private static void theReportHasCountsOnly() {
        Fake f = texas();
        AddressSearch s = new AddressSearch(() -> List.of(f));
        ask(s, "1100 Congress Ave Austin");
        ask(s, "123 Oak Ln Austin");
        ask(s, "Torchy");
        ask(s, "zzzz");
        SearchStats.Snapshot snap = s.getStats().snapshot();
        check(snap.getQueries() == 4 && snap.getEmpty() == 1, "queries and empties: " + snap);
        check(snap.getResults().get(SuggestionKind.EXACT) >= 1 && snap.getResults().get(SuggestionKind.INTERPOLATED) >= 1
            && snap.getResults().get(SuggestionKind.POI) == 1, "results by kind: " + snap);
        String line = snap.line().toLowerCase(Locale.ROOT);
        for (String word : new String[] {"austin", "congress", "oak", "torchy", "zzzz", "1100", "30.", "97."}) {
            check(!line.contains(word), "no '" + word + "' in the report: " + line);
        }
    }

    // ---- the real file ---------------------------------------------------------------------

    private static void theRealAustinFile(File file) throws Exception {
        long t0 = System.nanoTime();
        try (ObfFile obf = ObfFile.Companion.open(file)) {
            double openMs = (System.nanoTime() - t0) / 1e6;
            AddressSearch s = new AddressSearch(() -> List.of(obf));
            double capLat = 30.2747;
            double capLon = -97.7404;

            long q0 = System.nanoTime();
            List<Suggestion> r = s.suggest("1100 Congress Ave Austin", capLat, capLon, 8, () -> false);
            double coldMs = (System.nanoTime() - q0) / 1e6;
            check(!r.isEmpty() && r.get(0).getKind() == SuggestionKind.EXACT
                && r.get(0).getLabel().startsWith("1100 Congress Avenue"), "real: 1100 Congress Ave: " + show(r));
            double capitolM = metres(r.get(0), capLat, capLon);
            check(capitolM < 400, "real: 1100 Congress Ave within 400 m of the Capitol: " + Math.round(capitolM) + " m");
            System.out.printf(Locale.ROOT, "address-search: open %.0f ms, first query (cold) %.0f ms; 1100 Congress Ave %s %.5f,%.5f is %d m from the Capitol%n",
                openMs, coldMs, r.get(0).getKind(), r.get(0).getLatitude(), r.get(0).getLongitude(), Math.round(capitolM));

            r = s.suggest("Congress Ave Au", capLat, capLon, 8, () -> false);
            check(r.stream().limit(3).anyMatch(x -> x.getKind() == SuggestionKind.STREET && x.getLabel().equals("Congress Avenue, Austin")),
                "real: 'Congress Ave Au' suggests Congress Avenue, Austin: " + show(r));
            Suggestion congress = find(r, "Congress Avenue, Austin");
            System.out.printf(Locale.ROOT, "address-search: Congress Ave Au -> %s at %d m from the Capitol%n",
                congress.getLabel(), Math.round(metres(congress, capLat, capLon)));

            r = s.suggest("78701", capLat, capLon, 8, () -> false);
            check(!r.isEmpty() && r.get(0).getKind() == SuggestionKind.POSTCODE && r.get(0).getLabel().equals("78701"),
                "real: 78701 is a postcode: " + show(r));
            check(metres(r.get(0), capLat, capLon) < 2000, "real: 78701 is downtown, within 2 km of the Capitol");
            System.out.printf(Locale.ROOT, "address-search: 78701 at %.5f,%.5f, %d m from the Capitol%n",
                r.get(0).getLatitude(), r.get(0).getLongitude(), Math.round(metres(r.get(0), capLat, capLon)));

            r = s.suggest("1101 Congress Ave Austin", capLat, capLon, 8, () -> false);
            check(!r.isEmpty() && r.get(0).getKind() == SuggestionKind.CLOSEST
                && r.get(0).getLabel().startsWith("No 1101 on Congress Avenue — "),
                "real: 1101 is not on Congress Avenue; its closest numbers come before an estimate on Congress Avenue South: " + show(r));
            for (Suggestion x : r.subList(0, Math.min(4, r.size()))) {
                System.out.printf(Locale.ROOT, "address-search: 1101 Congress Ave -> %s %s [%s] %d m from the Capitol%n",
                    x.getKind(), x.getLabel(), x.getDetail(), Math.round(metres(x, capLat, capLon)));
            }

            r = s.suggest("1100 Congress Ave 78701", capLat, capLon, 8, () -> false);
            check(!r.isEmpty() && r.get(0).getKind() == SuggestionKind.EXACT, "real: street with a ZIP: " + show(r));

            r = s.suggest("123 Main St Au", capLat, capLon, 8, () -> false);
            check(r.stream().anyMatch(x -> x.getLabel().contains("Main Street") && (x.getLabel().contains("Austin") || x.getDetail().contains("Austin"))),
                "real: '123 Main St Au' already suggests Austin: " + show(r));

            r = s.suggest("I-35 Frontage Rd Austin", capLat, capLon, 8, () -> false);
            check(r.stream().anyMatch(x -> x.getKind() == SuggestionKind.STREET && x.getLabel().contains("35 Frontage Road")),
                "real: I-35 Frontage Rd: " + show(r));
            r = s.suggest("RM 2222", capLat, capLon, 8, () -> false);
            check(r.stream().anyMatch(x -> x.getKind() == SuggestionKind.STREET && x.getLabel().contains("2222")), "real: RM 2222: " + show(r));

            // Typing it a key at a time, warm: what the phone's 250 ms debounce would ask.
            String typed = "1100 Congress Ave Austin TX";
            double worst = 0;
            double total = 0;
            int n = 0;
            for (int i = 1; i <= typed.length(); i++) {
                long k0 = System.nanoTime();
                s.suggest(typed.substring(0, i), capLat, capLon, 8, () -> false);
                double ms = (System.nanoTime() - k0) / 1e6;
                worst = Math.max(worst, ms);
                total += ms;
                n++;
            }
            String[] warm = {"1100 Congress Ave Austin", "Congress Ave Au", "78701", "1101 Congress Ave", "Au", "HEB", "Torchy"};
            StringBuilder times = new StringBuilder();
            for (String q : warm) {
                long w0 = System.nanoTime();
                List<Suggestion> out = s.suggest(q, capLat, capLon, 8, () -> false);
                times.append(String.format(Locale.ROOT, " '%s'=%.0fms/%d", q, (System.nanoTime() - w0) / 1e6, out.size()));
            }
            System.out.printf(Locale.ROOT, "address-search: keystrokes of '%s': mean %.1f ms, worst %.0f ms; warm:%s%n",
                typed, total / n, worst, times);
            System.out.println("address-search: " + s.getStats().snapshot().line());

            // A fresh engine warmed as the search screen opens, then its first keystroke.
            AddressSearch fresh = new AddressSearch(() -> List.of(obf));
            long wa = System.nanoTime();
            fresh.warm(capLat, capLon, () -> false);
            double warmMs = (System.nanoTime() - wa) / 1e6;
            long fk = System.nanoTime();
            r = fresh.suggest("1100 C", capLat, capLon, 8, () -> false);
            double firstMs = (System.nanoTime() - fk) / 1e6;
            check(!r.isEmpty() && r.get(0).getKind() == SuggestionKind.EXACT, "real: '1100 C' already finds 1100 Congress: " + show(r));
            System.out.printf(Locale.ROOT, "address-search: warm() %.0f ms, then the first keystroke '1100 C' %.0f ms%n", warmMs, firstMs);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static List<String> labels(List<Suggestion> r) {
        List<String> out = new ArrayList<>();
        for (Suggestion s : r) out.add(s.getLabel());
        return out;
    }

    private static Suggestion find(List<Suggestion> r, String part) {
        for (Suggestion s : r) if (s.getLabel().contains(part)) return s;
        throw new AssertionError("no suggestion with '" + part + "' in " + show(r));
    }

    private static boolean near(Suggestion s, double lat, double lon, double metres) {
        return metres(s, lat, lon) <= metres;
    }

    private static double metres(Suggestion s, double lat, double lon) {
        double p1 = Math.toRadians(lat);
        double p2 = Math.toRadians(s.getLatitude());
        double dp = p2 - p1;
        double dl = Math.toRadians(s.getLongitude() - lon);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6_371_000.0 * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    private static String show(List<Suggestion> r) {
        StringBuilder sb = new StringBuilder("[");
        for (Suggestion s : r) {
            sb.append(String.format(Locale.ROOT, "%n  %s '%s' / '%s' %.5f,%.5f", s.getKind(), s.getLabel(), s.getDetail(),
                s.getLatitude(), s.getLongitude()));
        }
        return sb.append(" ]").toString();
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
    }
}
