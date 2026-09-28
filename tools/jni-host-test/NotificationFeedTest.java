// P7.8's notification filter and feed on a desktop JVM: which notifications reach the
// flex tile, what one row carries, newest-first order and its bound, removal, the pause,
// the exclusion list, and that the report holds counts only. Reading a row aloud (P8.1)
// is SpeechQueueTest's.
// NotificationFeed is pure Kotlin; what a real phone's notifications look like, and the
// listener that describes them (MediaAccessService), need the Pixel.
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;
import org.openautoandroid.source.DashNotification;
import org.openautoandroid.source.NotificationFacts;
import org.openautoandroid.source.NotificationFeed;
import org.openautoandroid.source.NotificationRules;
import org.openautoandroid.source.NotificationVerdict;

public final class NotificationFeedTest {
    private static int passed;
    private static final NotificationRules RULES = NotificationRules.INSTANCE;
    private static final NotificationFeed FEED = NotificationFeed.INSTANCE;
    private static final String OWN = "org.openautoandroid.source";

    public static void main(String[] args) {
        onlyAlertingMessagesPass();
        aRowIsWhoAndOneLine();
        offReadsNothing();
        newestFirstAndBounded();
        updatesAndRemovals();
        pauseAndExclusions();
        theReportHoldsNoContent();
        System.out.println("jni-host-test: ok " + passed + " notification feed checks");
    }

    /** An ordinary alerting chat notification; tests change one thing at a time. */
    private static NotificationFacts facts(String key, String pkg, long posted) {
        return new NotificationFacts(key, pkg, "Chat", 4, false, false, "msg", null, false,
                "Alice", "Are you close?", null, null, posted);
    }

    private static NotificationFacts with(NotificationFacts f, int importance, boolean ongoing, boolean summary,
            String category, String template, boolean media) {
        return new NotificationFacts(f.getKey(), f.getPackageName(), f.getAppLabel(), importance, ongoing, summary,
                category, template, media, f.getTitle(), f.getText(), f.getSender(), f.getMessageText(), f.getPostedMillis());
    }

    private static NotificationVerdict verdict(NotificationFacts f) {
        return RULES.verdict(f, OWN, Set.of("com.excluded"));
    }

    private static void onlyAlertingMessagesPass() {
        NotificationFacts base = facts("k", "com.chat", 1);
        check(verdict(base) == NotificationVerdict.SHOWN, "an alerting message is shown");
        check(verdict(with(base, 3, false, false, "msg", null, false)) == NotificationVerdict.SHOWN, "default importance alerts");
        check(verdict(with(base, 2, false, false, "msg", null, false)) == NotificationVerdict.NOT_ALERTING, "low (silent) does not");
        check(verdict(with(base, 1, false, false, "msg", null, false)) == NotificationVerdict.NOT_ALERTING, "nor minimum");
        check(verdict(with(base, -1000, false, false, "msg", null, false)) == NotificationVerdict.NOT_ALERTING, "nor unknown");
        check(verdict(with(base, 4, true, false, "msg", null, false)) == NotificationVerdict.ONGOING, "ongoing is not a message");
        check(verdict(with(base, 4, false, true, "msg", null, false)) == NotificationVerdict.GROUP_SUMMARY, "a group summary is not shown");
        check(verdict(with(base, 4, false, false, "transport", null, true)) == NotificationVerdict.MEDIA, "a media session is the media pane's");
        check(verdict(with(base, 4, false, false, null, NotificationRules.TEMPLATE_MEDIA, false)) == NotificationVerdict.MEDIA, "so is MediaStyle");
        check(verdict(with(base, 5, false, false, "call", null, false)) == NotificationVerdict.CALL, "a call category is the call tile's");
        check(verdict(with(base, 5, false, false, null, NotificationRules.TEMPLATE_CALL, false)) == NotificationVerdict.CALL, "so is CallStyle");
        check(verdict(facts("k", OWN, 1)) == NotificationVerdict.OWN, "never this app's own");
        check(verdict(facts("k", "com.excluded", 1)) == NotificationVerdict.EXCLUDED, "an excluded app is not shown");
        NotificationFacts empty = new NotificationFacts("k", "com.chat", "Chat", 4, false, false, null, null, false,
                " ", "\n", null, null, 1);
        check(verdict(empty) == NotificationVerdict.EMPTY, "nothing to draw is not a row");
    }

    private static void aRowIsWhoAndOneLine() {
        DashNotification row = RULES.row(facts("k", "com.chat", 42));
        check(row != null && "Alice".equals(row.getTitle()) && "Are you close?".equals(row.getText()), "title and text: " + row);
        check("Chat".equals(row.getAppLabel()) && row.getPostedMillis() == 42, "app and time");
        NotificationFacts chat = new NotificationFacts("k", "com.chat", "Chat", 4, false, false, "msg", null, false,
                "Family", "3 new messages", "Bob", "Running late\nsee you at 6", 1);
        row = RULES.row(chat);
        check("Bob".equals(row.getTitle()) && "Running late".equals(row.getText()),
                "a message's sender and its first line win over the conversation's summary: " + row);
        NotificationFacts textOnly = new NotificationFacts("k", "com.x", "App", 4, false, false, null, null, false,
                null, "  lots   of\tspace  ", null, null, 1);
        row = RULES.row(textOnly);
        check("App".equals(row.getTitle()) && "lots of space".equals(row.getText()), "no title: the app's name, whitespace collapsed");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 400; i++) longText.append('x');
        row = RULES.row(new NotificationFacts("k", "com.x", "App", 4, false, false, null, null, false,
                "T", longText.toString(), null, null, 1));
        check(row.getText().length() == NotificationRules.TEXT_MAX && row.getText().endsWith("\u2026"), "long text is cut to the bound");
    }

    private static void offReadsNothing() {
        FEED.setEnabled(false);
        FEED.setPaused(false);
        FEED.clear();
        check(!FEED.getActive(), "off by default");
        check(FEED.posted(facts("a", "com.chat", 1)) == null, "while off a posted notification is not even filtered");
        check(FEED.items().isEmpty() && FEED.posters().isEmpty(), "and nothing is held");
    }

    private static void newestFirstAndBounded() {
        FEED.clear();
        FEED.setEnabled(true);
        FEED.posted(facts("a", "com.chat", 100));
        FEED.posted(facts("b", "com.mail", 300));
        FEED.posted(facts("c", "com.chat", 200));
        check(keys().equals(List.of("b", "c", "a")), "newest first by post time: " + keys());
        check(FEED.posters().equals(Set.of("com.chat", "com.mail")), "the apps that posted are known");
        for (int i = 0; i < NotificationFeed.MAX + 5; i++) FEED.posted(facts("n" + i, "com.chat", 1_000 + i));
        check(FEED.items().size() == NotificationFeed.MAX, "bounded to " + NotificationFeed.MAX);
        check(FEED.items().get(0).getKey().equals("n" + (NotificationFeed.MAX + 4)), "keeping the newest");
        FEED.setEnabled(false);
        check(FEED.items().isEmpty() && FEED.posters().isEmpty(), "turning it off forgets everything");
    }

    private static void updatesAndRemovals() {
        FEED.clear();
        FEED.setEnabled(true);
        AtomicInteger calls = new AtomicInteger();
        kotlin.jvm.functions.Function1<List<DashNotification>, Unit> listener = rows -> {
            calls.incrementAndGet();
            return Unit.INSTANCE;
        };
        FEED.addListener(listener);
        FEED.posted(facts("a", "com.chat", 100));
        FEED.posted(facts("a", "com.chat", 150));
        check(FEED.items().size() == 1 && FEED.items().get(0).getPostedMillis() == 150, "an update replaces its row");
        FEED.posted(with(facts("a", "com.chat", 160), 4, true, false, "msg", null, false));
        check(FEED.items().isEmpty(), "an update that became ongoing takes its row away");
        FEED.posted(facts("b", "com.chat", 200));
        FEED.removed("b");
        check(FEED.items().isEmpty(), "gone from the phone, gone from the tile");
        FEED.removed("never-held");
        check(calls.get() == 5, "listeners hear each change, and not a no-op removal: " + calls.get());
        FEED.removeListener(listener);
        List<NotificationFacts> shade = new ArrayList<>();
        shade.add(facts("x", "com.chat", 10));
        shade.add(with(facts("y", "com.music", 20), 2, true, false, "transport", null, true));
        shade.add(facts("z", "com.mail", 30));
        FEED.replaceAll(shade);
        check(keys().equals(List.of("z", "x")), "a re-read keeps what passes, newest first: " + keys());
        FEED.setEnabled(false);
    }

    private static void pauseAndExclusions() {
        FEED.clear();
        FEED.setEnabled(true);
        FEED.posted(facts("a", "com.chat", 100));
        FEED.posted(facts("b", "com.mail", 200));
        FEED.setPaused(true);
        check(!FEED.getActive() && FEED.items().isEmpty(), "paused shows nothing");
        check(FEED.posted(facts("c", "com.chat", 300)) == null, "and reads nothing");
        check(FEED.posters().size() == 2, "but the apps already seen stay for the settings list");
        FEED.setPaused(false);
        check(FEED.getActive(), "un-paused is active again (and asks the listener to re-read)");
        FEED.posted(facts("a", "com.chat", 100));
        FEED.posted(facts("b", "com.mail", 200));
        FEED.setExclusions(Set.of("com.mail"));
        check(keys().equals(List.of("a")), "excluding an app drops its rows at once");
        check(FEED.posted(facts("d", "com.mail", 400)) == NotificationVerdict.EXCLUDED, "and its new ones");
        check(FEED.posters().contains("com.mail"), "while it stays in the list it can be restored from");
        FEED.setExclusions(Set.of());
        FEED.setEnabled(false);
    }

    private static void theReportHoldsNoContent() {
        FEED.clear();
        FEED.setEnabled(true);
        FEED.posted(facts("secret-key", "com.secret.app", 1));
        FEED.posted(with(facts("s2", "com.secret.app", 2), 1, false, false, "msg", null, false));
        String report = FEED.report();
        check(report.contains("notifications_shown=1") && report.contains("notifications_not_alerting=1"), "counts by verdict: " + report);
        check(report.contains("notifications_posting_apps=1"), "and how many apps posted");
        for (String secret : new String[] {"Alice", "close", "Chat", "com.secret", "secret-key"}) {
            check(!report.contains(secret), "the report never carries " + secret);
        }
        FEED.setEnabled(false);
    }

    private static List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (DashNotification row : FEED.items()) keys.add(row.getKey());
        return keys;
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
