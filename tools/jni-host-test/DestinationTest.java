// P6.4's destination parser on a desktop JVM: what OsmAnd's two share paths send, read
// from its source (ShareMenu.java, GeoParsedPoint.java, 21 September 2026), the geo: forms
// other apps use, the address-only refusal, malformed input, and the swapped-sign hint.
// DestinationParser is pure Kotlin, so this is the class the app runs. It proves nothing
// about what OsmAnd actually puts in an intent on the Pixel; only a real share does.
import java.util.Set;
import org.openautoandroid.source.Destination;
import org.openautoandroid.source.DestinationParser;
import org.openautoandroid.source.ParsedDestination;
import org.openautoandroid.source.Refusal;

public final class DestinationTest {
    private static int passed;
    private static final DestinationParser PARSER = DestinationParser.INSTANCE;

    public static void main(String[] args) {
        osmAndsGeoItemIsAPointWithALabel();
        osmAndsMessageTextIsReadFromItsGeoLine();
        osmAndsLinkIsReadWhenThereIsNoGeoLine();
        otherGeoFormsAreRead();
        anAddressWithNoCoordinatesIsRefused();
        malformedInputIsRefusedNotGuessed();
        aBarePairIsReadOnlyWhenItIsTheOnlyOne();
        aMissingMinusSignIsSuggestedNotAssumed();
        System.out.println("jni-host-test: ok " + passed + " destination checks");
    }

    /** Share -> Geo: ACTION_VIEW with this data URI, five decimals. */
    private static void osmAndsGeoItemIsAPointWithALabel() {
        Destination d = found(PARSER.parseUri("geo:30.26720,-97.74310?z=16&q=30.26720,-97.74310(Texas State Capitol)"));
        near(d, 30.26720, -97.74310, "the OsmAnd geo item");
        check("Texas State Capitol".equals(d.getLabel()), "its label is kept for the phone");

        Destination encoded = found(PARSER.parseUri("geo:30.26720,-97.74310?z=16&q=30.26720,-97.74310(1100+Congress+Ave%2C+Austin)"));
        check("1100 Congress Ave, Austin".equals(encoded.getLabel()), "a URL-encoded label is decoded: " + encoded.getLabel());

        Destination unlabelled = found(PARSER.parseUri("geo:30.2672,-97.7431?z=16"));
        near(unlabelled, 30.2672, -97.7431, "no q= at all");
        check(unlabelled.getLabel() == null, "and no label");
    }

    /** Share -> Message and the system sheet: ACTION_SEND text/plain, two lines. */
    private static void osmAndsMessageTextIsReadFromItsGeoLine() {
        String text = "Location: geo:30.26720,-97.74310?z=16&q=30.26720,-97.74310(Capitol)\n"
            + "https://osmand.net/map?pin=30.26720,-97.74310#16/30.26720/-97.74310";
        Destination d = found(PARSER.parseText(text));
        near(d, 30.26720, -97.74310, "the OsmAnd message text");
        check("Capitol".equals(d.getLabel()), "the geo line's label, not the link");

        String spaced = "Location: geo:30.26720,-97.74310?z=16&q=30.26720,-97.74310(1100 Congress Ave)\r\n"
            + "https://osmand.net/map?pin=30.26720,-97.74310#16/30.26720/-97.74310";
        check("1100 Congress Ave".equals(found(PARSER.parseText(spaced)).getLabel()),
            "an unencoded label with spaces runs to the end of its line, CRLF or not");
    }

    private static void osmAndsLinkIsReadWhenThereIsNoGeoLine() {
        near(found(PARSER.parseText("https://osmand.net/map?pin=30.26720,-97.74310#16/30.26720/-97.74310")),
            30.26720, -97.74310, "the pin");
        near(found(PARSER.parseText("See https://osmand.net/map?pin=30.26720%2C-97.74310#16/1/1 there")),
            30.26720, -97.74310, "an encoded comma, and the view fragment ignored");
        near(found(PARSER.parseText("https://osmand.net/go?lat=30.2672&lon=-97.7431&z=16")),
            30.2672, -97.7431, "the older /go link");
        check(refused(PARSER.parseText("https://osmand.net/map#16/30.26720/-97.74310")) == Refusal.NO_LOCATION,
            "a link with only a view is where the map was looking, not a pin");

        String addressThenPin = "Location: geo:0,0?q=1100%20Congress%20Ave\n"
            + "https://osmand.net/map?pin=30.26720,-97.74310#16/30.26720/-97.74310";
        near(found(PARSER.parseText(addressThenPin)), 30.26720, -97.74310,
            "an address-only geo line does not hide a pin on the next");
    }

    private static void otherGeoFormsAreRead() {
        near(found(PARSER.parseUri("geo:0,0?q=30.2672,-97.7431(Capitol)")), 30.2672, -97.7431,
            "Android's documented labelled pin");
        near(found(PARSER.parseUri("GEO:30.2672,-97.7431")), 30.2672, -97.7431, "the scheme is case-insensitive");
        near(found(PARSER.parseUri("geo:30.2672,-97.7431,149;u=35;crs=wgs84")), 30.2672, -97.7431,
            "RFC 5870 altitude and parameters");
        near(found(PARSER.parseUri("  geo:+30.2672,-97.7431  ")), 30.2672, -97.7431, "whitespace and a plus sign");
        near(found(PARSER.parseUri("geo:30.2672,-97.7431?q=%2030.2672%20,%20-97.7431%20")), 30.2672, -97.7431,
            "spaces around the q= pair");
    }

    /** P6 plan section 5.1: geocoding we do not have, so say so rather than guess. */
    private static void anAddressWithNoCoordinatesIsRefused() {
        check(refused(PARSER.parseUri("geo:0,0?q=1100+Congress+Ave,+Austin,+TX")) == Refusal.ADDRESS_ONLY,
            "Contacts' and Calendar's address-only form");
        check(refused(PARSER.parseUri("geo:30.2672,-97.7431?q=coffee")) == Refusal.ADDRESS_ONLY,
            "a search around a point is not that point");
        check(refused(PARSER.parseText("Meet at geo:0,0?q=Zilker%20Park")) == Refusal.ADDRESS_ONLY,
            "the same, shared as text");
    }

    private static void malformedInputIsRefusedNotGuessed() {
        String[] uris = {
            "geo:", "geo:0,0", "geo:91,0", "geo:30,-181", "geo:NaN,1", "geo:Infinity,1", "geo:0x1p3,1",
            "geo:30.2672", "geo:30.2672,-97.7431,1,2", "geo:abc,def", "https://osmand.net/map?pin=1,2", "",
            "geo:0,0?q=0,0(Nowhere)", "geo:1,1?q=95,1",
        };
        for (String uri : uris) {
            check(refused(PARSER.parseUri(uri)) == Refusal.NO_LOCATION, "refused as no location: \"" + uri + "\"");
        }
        check(refused(PARSER.parseUri("geo:30.2672,-97.7431?q=%ZZ")) == Refusal.ADDRESS_ONLY,
            "a malformed escape is kept as text, which is then not a coordinate");
        check(refused(PARSER.parseText("")) == Refusal.NO_LOCATION, "empty text");
        check(refused(PARSER.parseText("Dinner at 7, bring 2 chairs")) == Refusal.NO_LOCATION, "a sentence");
        check(refused(PARSER.parseText("Call 512,555.1234")) == Refusal.NO_LOCATION, "a number with a comma");

        Destination control = found(PARSER.parseUri("geo:0,0?q=30.2672,-97.7431(AB\n)"));
        check("AB".equals(control.getLabel()), "control characters are stripped from a label");
        Destination empty = found(PARSER.parseUri("geo:0,0?q=30.2672,-97.7431(   )"));
        check(empty.getLabel() == null, "a blank label is no label");
        StringBuilder longLabel = new StringBuilder();
        for (int i = 0; i < 300; i++) longLabel.append('x');
        check(found(PARSER.parseUri("geo:0,0?q=30.2672,-97.7431(" + longLabel + ")")).getLabel().length() == 200,
            "a label is bounded");
    }

    private static void aBarePairIsReadOnlyWhenItIsTheOnlyOne() {
        near(found(PARSER.parseText("30.26720, -97.74310")), 30.26720, -97.74310, "a pair copied out of a map");
        near(found(PARSER.parseText("Parking: -33.8568,151.2153 near the gate")), -33.8568, 151.2153,
            "a pair inside a sentence");
        check(refused(PARSER.parseText("30.2672,-97.7431 or 30.2700,-97.7400")) == Refusal.NO_LOCATION,
            "two pairs are ambiguous");
        check(refused(PARSER.parseText("30,-97")) == Refusal.NO_LOCATION, "integers are not taken as a pair");
    }

    /**
     * The P6.3a mistake: Austin's longitude typed without its minus sign lands in China,
     * where no tile is installed; its mirror is, so the phone suggests it.
     */
    private static void aMissingMinusSignIsSuggestedNotAssumed() {
        Set<String> tiles = Set.of("W100_N30.rd5", "W75_N40.rd5");
        Double mirrored = PARSER.mirroredLongitude(30.2672, 97.7431, tiles);
        check(mirrored != null && mirrored == -97.7431, "97.7 E with only W100_N30 installed suggests 97.7 W");
        check(PARSER.mirroredLongitude(30.2672, -97.7431, tiles) == null, "a covered point needs no hint");
        check(PARSER.mirroredLongitude(51.5, 0.5, tiles) == null, "no hint when the mirror is not covered either");
        check(PARSER.mirroredLongitude(30.2672, 0.0, tiles) == null, "zero has no mirror");
    }

    private static Destination found(ParsedDestination parsed) {
        if (!(parsed instanceof ParsedDestination.Found)) {
            throw new AssertionError("destination: expected a destination, got refusal "
                + ((ParsedDestination.Refused) parsed).getReason());
        }
        return ((ParsedDestination.Found) parsed).getDestination();
    }

    private static Refusal refused(ParsedDestination parsed) {
        if (!(parsed instanceof ParsedDestination.Refused)) {
            Destination d = ((ParsedDestination.Found) parsed).getDestination();
            throw new AssertionError("destination: expected a refusal, got " + d.getLatitude() + "," + d.getLongitude());
        }
        return ((ParsedDestination.Refused) parsed).getReason();
    }

    private static void near(Destination d, double lat, double lon, String what) {
        check(Math.abs(d.getLatitude() - lat) < 1e-9 && Math.abs(d.getLongitude() - lon) < 1e-9,
            what + ": " + d.getLatitude() + "," + d.getLongitude());
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("destination: " + what);
        passed++;
    }
}
