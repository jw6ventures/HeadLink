// P7.5's player list on a desktop JVM: one row per app, most recently active first, live
// sessions ahead on a tie, and a stable order; since part 2b's fix, a cold list ordered by
// the players remembered as having really played, and apps never seen to play capped.
// Players is pure Kotlin; finding the apps (with a launcher activity, never this one) and
// resuming them (MediaPlayers) needs a phone or the emulator lab.
import java.util.ArrayList;
import java.util.List;
import com.jw6ventures.headlink.Player;
import com.jw6ventures.headlink.Players;

public final class PlayersTest {
    private static int passed;
    private static final Players RULES = Players.INSTANCE;

    public static void main(String[] args) {
        mostRecentlyActiveFirst();
        oneRowPerApp();
        tiesAreStable();
        theListIsBounded();
        aColdListFollowsRealUse();
        appsNeverSeenPlayingAreCapped();
        System.out.println("jni-host-test: ok " + passed + " player list checks");
    }

    private static Player session(String key, String label, long active) {
        return new Player(key, label, true, false, active);
    }

    private static Player browser(String key, String label, long active) {
        return new Player(key, label, false, true, active);
    }

    private static List<String> keys(List<Player> players) {
        List<String> keys = new ArrayList<>();
        for (Player p : players) keys.add(p.getKey());
        return keys;
    }

    private static void mostRecentlyActiveFirst() {
        List<Player> merged = RULES.merge(
                List.of(session("a.pod", "Podcasts", 5_000)),
                List.of(browser("b.music", "Music", 9_000), browser("c.books", "Books", 1_000)));
        check(keys(merged).equals(List.of("b.music", "a.pod", "c.books")), "newest first, whichever source: " + keys(merged));
    }

    private static void oneRowPerApp() {
        List<Player> merged = RULES.merge(
                List.of(session("a.music", "a.music", 7_000)),
                List.of(browser("a.music", "Music", 3_000)));
        check(merged.size() == 1, "an app with a session and a browser is one row");
        Player row = merged.get(0);
        check(row.getHasSession() && row.getHasBrowser(), "and keeps both routes");
        check(row.getLastActiveMillis() == 7_000, "and the later activity");
        check("Music".equals(row.getLabel()), "and a resolved name over a package name");
    }

    private static void tiesAreStable() {
        List<Player> merged = RULES.merge(
                List.of(session("z.radio", "Radio", 0)),
                List.of(browser("m.books", "books", 0), browser("k.audio", "Audio", 0)));
        check(keys(merged).equals(List.of("z.radio", "k.audio", "m.books")),
                "never-seen apps: a live session first, then by name, ignoring case: " + keys(merged));
        List<Player> again = RULES.merge(
                List.of(session("z.radio", "Radio", 0)),
                List.of(browser("k.audio", "Audio", 0), browser("m.books", "books", 0)));
        check(keys(again).equals(keys(merged)), "the same apps in another order give the same list");
    }

    private static void theListIsBounded() {
        List<Player> many = new ArrayList<>();
        for (int i = 0; i < Players.MAX + 8; i++) many.add(browser("app" + i, "App " + i, i));
        List<Player> merged = RULES.merge(new ArrayList<>(), many);
        check(merged.size() == Players.MAX, "bounded to " + Players.MAX);
        check(merged.get(0).getKey().equals("app" + (Players.MAX + 7)), "keeping the most recent");
    }

    private static void aColdListFollowsRealUse() {
        // The start of a drive: nothing seen playing in this process, no live session.
        List<Player> cold = List.of(browser("a.books", "Audiobooks", 0), browser("m.music", "Music", 0),
                browser("p.pod", "Podcasts", 0));
        List<Player> merged = RULES.merge(new ArrayList<>(), cold, List.of("p.pod", "m.music"));
        check(keys(merged).equals(List.of("p.pod", "m.music", "a.books")),
                "remembered players first, most recently played first, then the rest: " + keys(merged));
        // Something playing now beats anything remembered.
        List<Player> live = RULES.merge(List.of(session("a.books", "Audiobooks", 5_000)), cold, List.of("p.pod", "m.music"));
        check(keys(live).get(0).equals("a.books"), "what is playing now comes first");
        // A live session that has never played still ranks ahead of a cold remembered one on a tie.
        List<Player> tie = RULES.merge(List.of(session("z.radio", "Radio", 0)), cold, List.of("p.pod"));
        check(keys(tie).get(0).equals("z.radio") && keys(tie).get(1).equals("p.pod"), "a live session, then the remembered: " + keys(tie));
        check(RULES.merge(new ArrayList<>(), cold).size() == 3, "with nothing remembered the old two-argument form still lists them");
    }

    private static void appsNeverSeenPlayingAreCapped() {
        List<Player> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) many.add(browser("app" + i, "App " + i, 0));
        List<Player> merged = RULES.merge(new ArrayList<>(), many);
        check(merged.size() == Players.UNPLAYED_MAX, "never seen playing: at most " + Players.UNPLAYED_MAX + ", got " + merged.size());
        List<Player> known = RULES.merge(new ArrayList<>(), many, List.of("app6", "app7"));
        check(known.size() == 2 + Players.UNPLAYED_MAX, "remembered ones are not counted against the cap: " + known.size());
        check(keys(known).subList(0, 2).equals(List.of("app6", "app7")), "and lead the list");
        List<Player> sessions = new ArrayList<>();
        for (int i = 0; i < 5; i++) sessions.add(session("live" + i, "Live " + i, 0));
        check(RULES.merge(sessions, many).size() == 5 + Players.UNPLAYED_MAX, "live sessions are real use, never capped");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
