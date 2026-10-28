// The two owned map themes (P7.6) on a desktop JVM, read as XML rather than through VTM:
// that the day theme has exactly the night theme's rules, so no feature class appears or
// disappears when the head unit's night flag changes; that every style a rule uses exists;
// that toll matching still carries the Mapsforge writer's `tl_yes`; that neither theme uses
// the route line's colour; and that the labels are legible against the ground they sit on.
//
// VTM's own parser needs a canvas backend this JVM does not have, so whether VTM accepts
// both files is proven where it runs: the lab report's `theme=oaa-light` / `theme=oaa-dark`
// rather than `vtm-fallback` (docs/dashboard.md, "Map pane").
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class MapThemeTest {
    private static int passed;

    private static final String DIR = "app/src/main/resources/assets/oaa/";

    /**
     * MapEngine.ROUTE_COLOR. Restated rather than read from the class: MapEngine cannot be
     * loaded off Android, and tools/route-line-pixels.py restates it the same way.
     */
    private static final String ROUTE_COLOR = "22c7f0";

    /** MapEngine.ROUTE_CASING_COLOR, drawn under the route with the day theme only. */
    private static final int ROUTE_CASING = 0x0b4f6c;

    /** WCAG AA for normal text: what a label must reach against its ground and its halo. */
    private static final double TEXT_CONTRAST = 4.5;

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 && !args[0].isEmpty() ? args[0] : DIR;
        Element night = read(new File(dir, "dashboard-dark.xml"));
        Element day = read(new File(dir, "dashboard-light.xml"));
        theRulesAreTheSameShape(night, day);
        everyStyleUsedExists("dark", night);
        everyStyleUsedExists("light", day);
        bothDefineTheSameStyles(night, day);
        tollMatchesTheWritersValue("dark", night);
        tollMatchesTheWritersValue("light", day);
        neitherUsesTheRouteColour(new File(dir, "dashboard-dark.xml"));
        neitherUsesTheRouteColour(new File(dir, "dashboard-light.xml"));
        theGroundsAreDayAndNight(night, day);
        labelsAreLegible("dark", night);
        labelsAreLegible("light", day);
        theDayRouteHasAnEdge(day);
        System.out.println("jni-host-test: ok " + passed + " map theme checks");
    }

    private static Element read(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        Element root = factory.newDocumentBuilder().parse(file).getDocumentElement();
        check("rendertheme".equals(root.getTagName()), file.getName() + " is a rendertheme");
        return root;
    }

    /**
     * The whole match tree and every instruction in it, in document order, with colours
     * and widths left out. Order matters as well as membership: VTM gives each
     * instruction its draw level by position, so a rule moved in one theme would change
     * which road paints over which at dusk.
     */
    private static void theRulesAreTheSameShape(Element night, Element day) {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        shape(night, "", a);
        shape(day, "", b);
        check(!a.isEmpty(), "the dark theme has rules");
        check(a.size() == b.size(), "the two themes have the same number of rules and instructions: "
                + a.size() + " against " + b.size());
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            check(a.get(i).equals(b.get(i)), "rule " + i + " differs: dark " + a.get(i) + " / light " + b.get(i));
        }
    }

    private static void shape(Element element, String path, List<String> out) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element)) continue;
            Element child = (Element) node;
            String tag = child.getTagName();
            if (tag.startsWith("style-")) continue; // definitions, compared by id elsewhere
            if ("m".equals(tag)) {
                String here = path + "/m[" + attr(child, "e") + "|" + attr(child, "k") + "|" + attr(child, "v")
                        + "|" + attr(child, "zoom-min") + "|" + attr(child, "zoom-max") + "|"
                        + attr(child, "closed") + "|" + attr(child, "select") + "]";
                out.add(here);
                shape(child, here, out);
            } else {
                // An instruction: which kind, and which style it takes, but not its colour.
                out.add(path + "/" + tag + "[use=" + attr(child, "use") + "]");
            }
        }
    }

    private static void everyStyleUsedExists(String name, Element root) {
        Map<String, String> styles = styles(root);
        int uses = 0;
        for (Element e : all(root)) {
            String use = e.getAttribute("use");
            if (use.isEmpty()) continue;
            uses++;
            String kind = e.getTagName();
            String want = "text".equals(kind) || "caption".equals(kind) || "style-text".equals(kind)
                    ? "style-text" : "style-line";
            check(want.equals(styles.get(use)), name + ": <" + kind + " use=" + use + "> names a " + want + " that exists");
        }
        check(uses > 0, name + " theme uses its styles");
    }

    private static void bothDefineTheSameStyles(Element night, Element day) {
        check(styles(night).equals(styles(day)), "both themes define the same styles: "
                + styles(night).keySet() + " / " + styles(day).keySet());
    }

    /**
     * The stock Mapsforge writer stores toll=yes and toll=true as `tl_yes`
     * (memory: mapsforge-writer-rewrites-values). A toll rule that lists yes without it
     * matches nothing on a real file, and the road draws no line at all.
     */
    private static void tollMatchesTheWritersValue(String name, Element root) {
        int tolls = 0;
        for (Element e : all(root)) {
            if (!"m".equals(e.getTagName()) || !"toll".equals(e.getAttribute("k"))) continue;
            tolls++;
            List<String> values = List.of(e.getAttribute("v").split("\\|"));
            check(!values.contains("yes") || values.contains("tl_yes"), name + ": toll rule " + values + " carries tl_yes");
        }
        check(tolls > 0, name + " theme has toll rules");
    }

    private static void neitherUsesTheRouteColour(File file) throws Exception {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        check(!text.contains("#" + ROUTE_COLOR) && !text.contains("#ff" + ROUTE_COLOR),
                file.getName() + " leaves the route cyan to the route line");
    }

    private static void theGroundsAreDayAndNight(Element night, Element day) {
        double dark = luminance(colour(night.getAttribute("map-background")));
        double light = luminance(colour(day.getAttribute("map-background")));
        check(dark < 0.05, "the night ground is dark: " + dark);
        // Light, but not paper white: the pane sits beside dark chrome in a cabin.
        check(light > 0.6 && light < 0.9, "the day ground is light and not glaring: " + light);
    }

    /** Each label style against the theme's ground and against its own halo. */
    private static void labelsAreLegible(String name, Element root) {
        int ground = colour(root.getAttribute("map-background"));
        for (Element e : all(root)) {
            if (!"style-text".equals(e.getTagName())) continue;
            int fill = colour(e.getAttribute("fill"));
            int halo = colour(e.getAttribute("stroke"));
            String id = e.getAttribute("id");
            check(contrast(fill, ground) >= TEXT_CONTRAST,
                    name + ": " + id + " against the ground is " + contrast(fill, ground));
            check(contrast(fill, halo) >= TEXT_CONTRAST,
                    name + ": " + id + " against its halo is " + contrast(fill, halo));
        }
    }

    /**
     * The route's cyan is about 2:1 on the day ground and on a white carriageway, so the
     * day theme relies on MapEngine's casing for its edge. That casing has to stand out
     * from both, at the 3:1 WCAG asks of graphics.
     */
    private static void theDayRouteHasAnEdge(Element day) {
        int ground = colour(day.getAttribute("map-background"));
        int route = Integer.parseInt(ROUTE_COLOR, 16);
        check(contrast(route, ground) < 3.0, "the route alone is weak on the day ground, which is why it has a casing");
        check(contrast(ROUTE_CASING, ground) >= 3.0, "the casing stands out from the day ground: " + contrast(ROUTE_CASING, ground));
        check(contrast(ROUTE_CASING, 0xffffff) >= 3.0, "and from a white carriageway: " + contrast(ROUTE_CASING, 0xffffff));
        check(contrast(ROUTE_CASING, route) >= 3.0, "and from the line it edges: " + contrast(ROUTE_CASING, route));
    }

    private static Map<String, String> styles(Element root) {
        Map<String, String> out = new TreeMap<>();
        for (Element e : all(root)) {
            String tag = e.getTagName();
            if (tag.startsWith("style-")) out.put(e.getAttribute("id"), tag);
        }
        return out;
    }

    private static List<Element> all(Element root) {
        List<Element> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Element element, List<Element> out) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element) {
                out.add((Element) node);
                collect((Element) node, out);
            }
        }
    }

    private static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : "";
    }

    /** `#rrggbb` or `#aarrggbb` to 0xrrggbb. */
    private static int colour(String value) {
        check(value.startsWith("#") && (value.length() == 7 || value.length() == 9), "a colour: " + value);
        return (int) (Long.parseLong(value.substring(value.length() - 6), 16) & 0xffffff);
    }

    /** WCAG relative luminance of an sRGB colour. */
    private static double luminance(int rgb) {
        double r = channel((rgb >> 16) & 0xff);
        double g = channel((rgb >> 8) & 0xff);
        double b = channel(rgb & 0xff);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
