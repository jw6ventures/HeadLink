// The Home Assistant button on a desktop JVM: the settings' cleaning, repair and stored
// form, with the token hidden from toString; the client's guarantees (nothing sent while the
// button is off or not set up, a press is exactly one POST with the token only in its
// Authorization header, Check sends GETs only) and its words for each answer, none of which
// carries the token; the button left of the weather in the header where it fits, else a
// bubble over the map, at 1080p, 726x480 and 800x480, clear of every other control; and a press on the dashboard handed out once, shown as sending, then green
// or red, and counted in the report without the server, service or target. The preferences
// file, the Keystore and HttpURLConnection against a real server need a phone.
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.jw6ventures.headlink.AlertLevel;
import com.jw6ventures.headlink.Box;
import com.jw6ventures.headlink.ClockFormat;
import com.jw6ventures.headlink.Dashboard;
import com.jw6ventures.headlink.DashboardChoices;
import com.jw6ventures.headlink.DashboardLayout;
import com.jw6ventures.headlink.DayNightMode;
import com.jw6ventures.headlink.HomeAssistantButton;
import com.jw6ventures.headlink.HomeAssistantClient;
import com.jw6ventures.headlink.HomeAssistantHttp;
import com.jw6ventures.headlink.HomeAssistantReply;
import com.jw6ventures.headlink.HomeAssistantResult;
import com.jw6ventures.headlink.HomeAssistantSettings;
import com.jw6ventures.headlink.HomeAssistantState;
import com.jw6ventures.headlink.MapOrientation;
import com.jw6ventures.headlink.MarkerColour;
import com.jw6ventures.headlink.PhoneSnapshot;
import com.jw6ventures.headlink.SpeedUnit;

public final class HomeAssistantTest {
    private static int passed;
    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.secret-token_0123456789";
    private static final String SERVICES = "[{\"domain\":\"cover\",\"services\":{\"open_cover\":{\"name\":\"Open\",\"fields\":{}},"
        + "\"close_cover\":{}}},{\"domain\":\"switch\",\"services\":{\"turn_on\":{},\"turn_off\":{}}},"
        + "{\"domain\":\"automation\",\"services\":{\"trigger\":{\"fields\":{\"skip_condition\":{\"x\":[1,2]}}}}}]";

    /** One recorded request. */
    private static final class Sent {
        final String method;
        final String url;
        final Map<String, String> headers;
        final String body;

        Sent(String method, String url, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body == null ? null : new String(body, StandardCharsets.UTF_8);
        }
    }

    /** A stand-in server: records every request and answers from [answer]. */
    private interface Answer {
        HomeAssistantReply answer(String method, String url);
    }

    private static final class Recorder implements HomeAssistantHttp {
        final List<Sent> sent = new ArrayList<>();
        final Answer answer;

        Recorder(Answer answer) {
            this.answer = answer;
        }

        @Override
        public HomeAssistantReply send(String method, String url, Map<String, String> headers, byte[] body) {
            sent.add(new Sent(method, url, new HashMap<>(headers), body));
            return answer.answer(method, url);
        }
    }

    public static void main(String[] args) {
        cleaning();
        storedForm();
        nothingSentUnlessSetUp();
        aPress();
        pressAnswers();
        theCheck();
        for (DashboardLayout l : List.of(
                new DashboardLayout(0, 0, 1920, 1080, true),
                new DashboardLayout(0, 0, 1920, 1080, false),
                new DashboardLayout(74, 0, 726, 480, true),
                new DashboardLayout(74, 0, 726, 480, false),
                new DashboardLayout(0, 0, 800, 480, true),
                new DashboardLayout(0, 0, 800, 480, false))) {
            theButtonFits(l);
        }
        onTheDashboard();
        System.out.println("jni-host-test: ok " + passed + " home assistant checks");
    }

    private static HomeAssistantSettings settings(boolean on, String url, String token, String service, String target) {
        return new HomeAssistantSettings(on, url, token, service, target);
    }

    private static HomeAssistantSettings garage() {
        return settings(true, "https://ha.example.com:8123", TOKEN, "cover.open_cover", "cover.garage_door");
    }

    private static void cleaning() {
        HomeAssistantSettings.Companion c = HomeAssistantSettings.Companion;
        check("https://ha.example.com:8123".equals(c.cleanUrl("  https://ha.example.com:8123/ ")), "a trailing slash goes");
        check("https://ha.example.com".equals(c.cleanUrl("https://ha.example.com/api/")), "and a trailing /api");
        check("https://ha.example.com".equals(c.cleanUrl("ha.example.com")), "a bare host is https");
        check("https://x.ui.nabu.casa".equals(c.cleanUrl("https://x.ui.nabu.casa")), "a cloud address");
        check(c.cleanUrl("http://192.168.1.5:8123") == null, "plain http is refused");
        check(c.cleanUrl("https://user:pw@ha.example.com") == null && c.cleanUrl("https://a@b.example.com") == null, "no user part");
        check(c.cleanUrl("https://ha.example.com/?x=1") == null && c.cleanUrl("https://ha.example.com/#a") == null, "no query or fragment");
        check(c.cleanUrl("") == null && c.cleanUrl("https://ha example.com") == null, "empty or spaced");
        check(TOKEN.equals(c.cleanToken("  Bearer " + TOKEN + "\n")), "a pasted Bearer and whitespace go");
        check(c.cleanToken("two words") == null && c.cleanToken("") == null, "a token with a space or none");
        check("cover.open_cover".equals(c.cleanService(" Cover.Open_Cover ")), "a service, lower case");
        check(c.cleanService("cover") == null && c.cleanService("cover.open cover") == null && c.cleanService("a.b.c") == null,
            "not domain.service");
        check("cover.garage_door, switch.gate".equals(c.cleanTarget("cover.garage_door,  Switch.Gate ,cover.garage_door,")),
            "targets trimmed, lower case, once each");
        check("".equals(c.cleanTarget("  ")), "no target is allowed");
        check(c.cleanTarget("cover.garage door") == null && c.cleanTarget("garage") == null, "not an entity id");
    }

    private static void storedForm() {
        HomeAssistantSettings d = new HomeAssistantSettings(false, "", "", "", "");
        check(!d.getEnabled() && !d.getConfigured() && !d.getUsable(), "off and not set up by default");
        HomeAssistantSettings g = garage();
        check(g.getUsable(), "on and set up is usable");
        check(settings(true, "https://ha.example.com", TOKEN, "script.leave", "").getUsable(), "a target is optional");
        check(!settings(true, "https://ha.example.com", "", "cover.open_cover", "").repaired().getEnabled(), "off without a token");
        check(!settings(true, "http://ha.example.com", TOKEN, "cover.open_cover", "").repaired().getEnabled(), "off without a usable address");
        check(!settings(true, "https://ha.example.com", TOKEN, "cover", "").repaired().getEnabled(), "off without a service");
        Map<String, Object> stored = g.toStored();
        check(stored.keySet().equals(Set.of("ha_enabled", "ha_server_url", "ha_token", "ha_service", "ha_target")), "stored keys");
        check(HomeAssistantSettings.Companion.fromStored(stored).equals(g), "round trip");
        Map<String, Object> wrong = new HashMap<>(stored);
        wrong.put("ha_enabled", "yes");
        wrong.put("ha_service", 7);
        HomeAssistantSettings read = HomeAssistantSettings.Companion.fromStored(wrong);
        check(!read.getEnabled() && read.getService().isEmpty(), "a mistyped value reads as its default");
        check(!g.toString().contains(TOKEN) && g.toString().contains("token=set"), "toString hides the token");
    }

    private static void nothingSentUnlessSetUp() {
        Recorder r = new Recorder((m, u) -> new HomeAssistantReply(200, "[]"));
        HomeAssistantSettings off = settings(false, "https://ha.example.com", TOKEN, "cover.open_cover", "cover.garage_door");
        HomeAssistantResult result = new HomeAssistantClient(() -> off, r).press();
        check(!result.getOk() && r.sent.isEmpty(), "off: a press sends nothing");
        HomeAssistantSettings bare = settings(true, "https://ha.example.com", "", "cover.open_cover", "");
        check(!new HomeAssistantClient(() -> bare, r).press().getOk() && r.sent.isEmpty(), "no token: a press sends nothing");
        check(!new HomeAssistantClient(() -> bare, r).check().getOk() && r.sent.isEmpty(), "and neither does Check");
    }

    private static void aPress() {
        Recorder r = new Recorder((m, u) -> new HomeAssistantReply(200, "[]"));
        HomeAssistantResult result = new HomeAssistantClient(HomeAssistantTest::garage, r).press();
        check(result.getOk() && result.getMessage().equals("Called cover.open_cover on cover.garage_door"), "a press works: " + result.getMessage());
        check(r.sent.size() == 1, "exactly one request");
        Sent s = r.sent.get(0);
        check(s.method.equals("POST") && s.url.equals("https://ha.example.com:8123/api/services/cover/open_cover"), "the service's URL: " + s.url);
        check(("Bearer " + TOKEN).equals(s.headers.get("Authorization")), "the token in Authorization");
        check(!s.url.contains(TOKEN) && !s.body.contains(TOKEN), "and nowhere else");
        check("application/json".equals(s.headers.get("Content-Type")), "a JSON body");
        check("{\"entity_id\":[\"cover.garage_door\"]}".equals(s.body), "the target: " + s.body);
        check("{\"entity_id\":[\"switch.a\",\"switch.b\"]}".equals(new String(HomeAssistantClient.Companion.body("switch.a, switch.b"), StandardCharsets.UTF_8)),
            "several targets");
        check("{}".equals(new String(HomeAssistantClient.Companion.body(""), StandardCharsets.UTF_8)), "no target");
    }

    private static void pressAnswers() {
        check(press(401, "").getMessage().contains("refused the token"), "401");
        HomeAssistantResult bad = press(400, "{\"message\":\"Service cover.open not found.\"}");
        check(!bad.getOk() && bad.getMessage().contains("Service cover.open not found") && bad.getMessage().contains("Check the service"), "400: " + bad.getMessage());
        check(press(500, "<html>oops</html>").getMessage().equals("Home Assistant answered 500"), "an error with no JSON");
        HomeAssistantClient unreachable = new HomeAssistantClient(HomeAssistantTest::garage, (m, u, h, b) -> {
            throw new java.net.UnknownHostException("ha.example.com " + TOKEN);
        });
        HomeAssistantResult result = unreachable.press();
        check(!result.getOk() && result.getMessage().equals("The server's address could not be found"), "unreachable, without the exception's text");
    }

    private static HomeAssistantResult press(int status, String body) {
        return new HomeAssistantClient(HomeAssistantTest::garage, (m, u, h, b) -> new HomeAssistantReply(status, body)).press();
    }

    private static Recorder server(int states) {
        return new Recorder((m, u) -> {
            if (u.endsWith("/api/")) return new HomeAssistantReply(200, "{\"message\":\"API running.\"}");
            if (u.endsWith("/api/services")) return new HomeAssistantReply(200, SERVICES);
            return new HomeAssistantReply(states, "{}");
        });
    }

    private static void theCheck() {
        Set<String> names = null;
        try {
            names = HomeAssistantClient.Companion.serviceNames(SERVICES);
        } catch (java.io.IOException e) {
            check(false, "services parse: " + e);
        }
        check(names.equals(Set.of("cover.open_cover", "cover.close_cover", "switch.turn_on", "switch.turn_off", "automation.trigger")),
            "the services listed: " + names);

        Recorder r = server(200);
        HomeAssistantResult ok = new HomeAssistantClient(HomeAssistantTest::garage, r).check();
        check(ok.getOk() && ok.getMessage().startsWith("Works"), "Check works: " + ok.getMessage());
        check(r.sent.stream().allMatch(s -> s.method.equals("GET") && s.body == null), "GETs only");
        check(r.sent.size() == 3 && r.sent.get(2).url.equals("https://ha.example.com:8123/api/states/cover.garage_door"), "the target's state");
        HomeAssistantSettings off = settings(false, "https://ha.example.com", TOKEN, "cover.open_cover", "");
        check(new HomeAssistantClient(() -> off, server(200)).check().getOk(), "Check works with the button still off");

        HomeAssistantSettings typo = settings(true, "https://ha.example.com", TOKEN, "cover.open", "cover.garage_door");
        HomeAssistantResult missing = new HomeAssistantClient(() -> typo, server(200)).check();
        check(!missing.getOk() && missing.getMessage().equals("Home Assistant has no service cover.open. Did you mean cover.open_cover?"),
            "an unknown service, with the nearest: " + missing.getMessage());
        HomeAssistantResult gone = new HomeAssistantClient(HomeAssistantTest::garage, server(404)).check();
        check(!gone.getOk() && gone.getMessage().equals("Home Assistant has no entity cover.garage_door"), "an unknown target");
        HomeAssistantResult refused = new HomeAssistantClient(HomeAssistantTest::garage, (m, u, h, b) -> new HomeAssistantReply(401, "")).check();
        check(!refused.getOk() && refused.getMessage().contains("refused the token"), "a refused token");
    }

    private static void theButtonFits(DashboardLayout l) {
        String at = " at " + l.getWidth() + "x" + l.getHeight() + (l.getMapOnLeft() ? " map left" : " map right");
        boolean ford = l.getWidth() == 726;
        // James, 28 October 2026: beside the weather where the header has room, else over the map.
        check(l.getHomeAssistantInHeader() == !ford, "in the header except at Ford's 726x480" + at);
        for (boolean weather : new boolean[] {true, false}) {
            Box h = l.headerHomeAssistant(weather);
            Box p = l.headerIcon(0);
            Box w = l.getHeaderWeather();
            check(h.getY() == l.getY() && h.getHeight() == l.getHeader() && h.getWidth() == l.getHeader(), "a header-height square" + at);
            check(h.getRight() + l.getMargin() == (weather ? w.getX() : p.getX()), "a margin left of the weather item, or of Ⓟ" + at);
            check(!overlaps(h, p) && !overlaps(h, l.headerIcon(1)) && (!weather || !overlaps(h, w)), "never over Ⓟ, ☀/☾ or the weather" + at);
            check(l.headerTextRight(weather, false) == l.headerTextRight(weather), "no change to the text while hidden" + at);
            Box shown = l.homeAssistant(weather);
            check(shown.equals(l.getHomeAssistantInHeader() ? h : l.getHomeAssistantBubble()), "the box it is drawn in" + at);
            check(l.hitHomeAssistant(shown.getCenterX(), shown.getCenterY(), weather), "and its target" + at);
            if (l.getHomeAssistantInHeader()) {
                check(l.headerTextRight(weather, true) == h.getX() - l.getMargin(), "the text stops short of it" + at);
                // With weather off its item is no target (Dashboard checks weatherOn first), so the
                // button may take its place.
                check(l.hitHeaderIcon(h.getCenterX(), h.getCenterY()) == null && (!weather || !l.hitHeaderWeather(h.getCenterX(), h.getCenterY())),
                    "the other targets do not reach it" + at);
            }
        }
        if (l.getHomeAssistantInHeader()) {
            // The same estimate WeatherTest makes for the text left of the weather item.
            float big = l.getHeader() * 0.52f;
            float small = l.getHeader() * 0.24f;
            float em = DashboardLayout.EM_PER_CHARACTER;
            float text = l.getMargin() + em * big * 5 + 2 * l.getMargin() + em * big * 7 + 2 * l.getMargin() + big + em * small * 8;
            check(l.getX() + text <= l.headerTextRight(true, true), "clock, speed, compass and bearing fit left of it" + at);
        }
        Box b = l.getHomeAssistantBubble();
        Box map = l.getMap();
        check(b.getX() >= map.getX() && b.getRight() <= map.getRight() && b.getY() >= map.getY() && b.getBottom() <= map.getBottom(), "the bubble inside the pane" + at);
        check(b.getWidth() == l.getBubbleSize() && b.getHeight() == l.getBubbleSize(), "a bubble's size" + at);
        check(b.getX() - l.mapBubble(0).getRight() == l.getMargin() && b.getY() == l.mapBubble(0).getY(), "a margin right of recentre" + at);
        check(!overlaps(b, l.mapBubble(1)) && !overlaps(b, l.zoomBubble(0)) && !overlaps(b, l.zoomBubble(1)), "clear of the other bubbles" + at);
        check(!overlaps(b, l.getTrafficAttribution()), "clear of the traffic credit" + at);
        // The ODbL credit, at the generous em estimate DashboardDrawing's text never exceeds.
        int credit = (int) (DashboardLayout.EM_PER_CHARACTER * l.getAttributionTextSize() * "© OpenStreetMap contributors (ODbL)".length());
        check(b.getRight() < map.getRight() - l.getMargin() - credit, "clear of the map credit" + at);
        int[] tops = {l.poiButtonsTop(false, false), l.poiButtonsTop(true, false), l.poiButtonsTop(true, true)};
        for (int top : tops) check(!overlaps(b, l.poiButtonRow(top)), "clear of Gas, Food and Shop" + at);
        check(!overlaps(b, l.getTurnCard()) && !overlaps(b, l.getOfferCard()), "clear of the turn card" + at);
        check(l.hitMapBubble(b.getCenterX(), b.getCenterY()) == null, "not a map bubble's target" + at);
    }

    private static Dashboard dashboard(boolean shown) {
        return dashboard(shown, new DashboardLayout(0, 0, 800, 480, true));
    }

    private static Dashboard dashboard(boolean shown, DashboardLayout layout) {
        DashboardChoices choices = new DashboardChoices(MarkerColour.BLUE, DayNightMode.AUTO, false, false, null, true, false,
            AlertLevel.SEVERE, ClockFormat.H24, shown);
        Dashboard d = new Dashboard(layout, "t", ZoneId.of("UTC"), SpeedUnit.MPH,
            MapOrientation.NORTH_UP, o -> kotlin.Unit.INSTANCE, choices);
        d.phone(new PhoneSnapshot(80, false, 3, "lte", 42.4495, -71.2310, null, null, null, true));
        return d;
    }

    private static void onTheDashboard() {
        long now = 1_000_000L;
        Dashboard off = dashboard(false);
        Box b = off.getLayout().homeAssistant(false);
        off.touch("down", b.getCenterX(), b.getCenterY(), now);
        off.touch("up", b.getCenterX(), b.getCenterY(), now);
        check(off.nextFrame(now).getHomeAssistant() == null && !off.takeHomeAssistantPress(), "hidden, and no target, while off");

        Dashboard d = dashboard(true);
        check(new HomeAssistantButton(false, HomeAssistantState.IDLE).equals(d.nextFrame(now).getHomeAssistant()), "shown while on");
        d.touch("down", b.getCenterX(), b.getCenterY(), now);
        check(d.nextFrame(now).getHomeAssistant().getPressed(), "lit while pressed");
        d.touch("up", b.getCenterX(), b.getCenterY(), now);
        check(d.takeHomeAssistantPress() && !d.takeHomeAssistantPress(), "a press handed out once");
        check(d.nextFrame(now).getHomeAssistant().getState() == HomeAssistantState.SENDING, "sending until answered");
        d.touch("down", b.getCenterX(), b.getCenterY(), now + 10);
        d.touch("up", b.getCenterX(), b.getCenterY(), now + 10);
        check(!d.takeHomeAssistantPress(), "a second tap while sending sends nothing");
        d.homeAssistantResult(true, now + 500);
        check(d.nextFrame(now + 600).getHomeAssistant().getState() == HomeAssistantState.OK, "green when it worked");
        check(d.nextFrame(now + 500 + Dashboard.HOME_ASSISTANT_RESULT_MS).getHomeAssistant().getState() == HomeAssistantState.IDLE, "and back to plain");

        d.touch("down", b.getCenterX(), b.getCenterY(), now + 10_000);
        d.touch("up", b.getCenterX() + 10_000, b.getCenterY(), now + 10_000);
        check(!d.takeHomeAssistantPress(), "a press that slides off is not a press");
        d.touch("down", b.getCenterX(), b.getCenterY(), now + 20_000);
        d.touch("up", b.getCenterX(), b.getCenterY(), now + 20_000);
        check(d.takeHomeAssistantPress(), "pressed again");
        d.homeAssistantResult(false, now + 20_100);
        check(d.nextFrame(now + 20_200).getHomeAssistant().getState() == HomeAssistantState.FAILED, "red when it did not");

        d.touch("down", b.getCenterX(), b.getCenterY(), now + 40_000);
        d.touch("up", b.getCenterX(), b.getCenterY(), now + 40_000);
        check(d.takeHomeAssistantPress(), "and again");
        check(d.nextFrame(now + 40_000 + Dashboard.HOME_ASSISTANT_SENDING_MS).getHomeAssistant().getState() == HomeAssistantState.IDLE,
            "a press never answered stops showing as sending");

        d.homeAssistantShown(false);
        check(d.nextFrame(now + 80_000).getHomeAssistant() == null, "hidden mid-session");
        Dashboard ford = dashboard(true, new DashboardLayout(74, 0, 726, 480, true));
        Box bubble = ford.getLayout().getHomeAssistantBubble();
        ford.touch("down", bubble.getCenterX(), bubble.getCenterY(), now);
        ford.touch("up", bubble.getCenterX(), bubble.getCenterY(), now);
        check(ford.takeHomeAssistantPress(), "on the map at Ford's 726x480, and not a pan");
        ford.homeAssistantResult(true, now + 100);
        ford.nextFrame(now + 40_000);
        Box header = ford.getLayout().headerHomeAssistant(false);
        ford.touch("down", header.getCenterX(), header.getCenterY(), now + 50_000);
        ford.touch("up", header.getCenterX(), header.getCenterY(), now + 50_000);
        check(!ford.takeHomeAssistantPress(), "and not in its header");
        String report = d.report();
        check(report.contains("home_assistant_shown=false ") && report.contains("home_assistant_presses=3 ")
            && report.contains("home_assistant_ok=1 ") && report.contains("home_assistant_failed=1"), "counted in the report");
    }

    private static boolean overlaps(Box a, Box b) {
        return a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom();
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("HomeAssistantTest: " + what);
        passed++;
    }
}
