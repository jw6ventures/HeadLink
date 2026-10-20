// P8.1's speech decisions on a desktop JVM (docs/p8-plan.md sections 4, 8 and 9): the
// engine and truck-only gate, the priority between a tapped notification and a turn
// prompt, the second tap that stops a read, the silence lead-in, one focus grant shared by
// utterances close together and always given back, the section 14.1 call rule, the counts
// the report carries and what it never carries, and what a tapped notification is read as.
// SpeechQueue and ReadAloudText are pure Kotlin; Speaker, the Android glue around them
// (TextToSpeech, audio focus, the A2DP device list), needs a phone and a Bluetooth sink.
import com.jw6ventures.headlink.DashNotification;
import com.jw6ventures.headlink.NotificationWords;
import com.jw6ventures.headlink.ReadAloudText;
import com.jw6ventures.headlink.SampleOutcome;
import com.jw6ventures.headlink.SpeechAction;
import com.jw6ventures.headlink.SpeechConditions;
import com.jw6ventures.headlink.SpeechEnd;
import com.jw6ventures.headlink.SpeechKind;
import com.jw6ventures.headlink.SpeechMedia;
import com.jw6ventures.headlink.SpeechQueue;
import com.jw6ventures.headlink.SpeechSkip;

public final class SpeechQueueTest {
    private static int passed;
    private static final SpeechConditions OK = new SpeechConditions(true, true, false);
    private static final SpeechConditions NO_ENGINE = new SpeechConditions(false, true, false);
    private static final SpeechConditions NO_BLUETOOTH = new SpeechConditions(true, false, false);
    private static final SpeechConditions IN_CALL = new SpeechConditions(true, true, true);

    public static void main(String[] args) {
        theGateComesFirst();
        aSecondTapStops();
        theNewestTapWins();
        guidanceInterruptsAndIsNeverQueuedBehind();
        noPromptDuringACall();
        oneGrantForUtterancesCloseTogether();
        theGrantIsAlwaysGivenBack();
        theLeadInFollowsSilenceOnly();
        refusedFocusCutsNothing();
        staleCallbacksAreIgnored();
        theReportHoldsCountsOnly();
        whatATappedNotificationSays();
        theMediaSetting();
        aSampleTakesAPromptsPathAndIsCountedApart();
        System.out.println("jni-host-test: ok " + passed + " speech checks");
    }

    private static SpeechAction.Speak speak(SpeechAction action, String what) {
        check(action instanceof SpeechAction.Speak, what + ": " + action);
        return (SpeechAction.Speak) action;
    }

    private static void skipped(SpeechAction action, SpeechSkip reason, String what) {
        check(action instanceof SpeechAction.Skip && ((SpeechAction.Skip) action).getReason() == reason, what + ": " + action);
    }

    private static boolean has(SpeechQueue queue, String entry) {
        return (" " + queue.report().replace('\n', ' ') + " ").contains(" " + entry + " ");
    }

    private static void theGateComesFirst() {
        SpeechQueue queue = new SpeechQueue();
        skipped(queue.read("a", 0, NO_ENGINE), SpeechSkip.NO_ENGINE, "no engine, or one still starting: nothing");
        skipped(queue.read("a", 0, NO_BLUETOOTH), SpeechSkip.NO_BLUETOOTH, "no A2DP output: nothing (decision 4)");
        skipped(queue.read("a", 0, new SpeechConditions(false, false, false)), SpeechSkip.NO_ENGINE, "the engine is checked first");
        skipped(queue.guide("prepare", 0, NO_BLUETOOTH), SpeechSkip.NO_BLUETOOTH, "prompts have the same gate");
        queue.setReadAloud(false);
        skipped(queue.read("a", 0, OK), SpeechSkip.OFF, "read aloud off: nothing");
        queue.setReadAloud(true);
        queue.setGuidanceOn(false);
        skipped(queue.guide("prepare", 0, OK), SpeechSkip.TOGGLE_OFF, "the toggle off: no prompt");
        check(has(queue, "voice_prompts_toggle=off"), "P8.2: the report says the toggle is off");
        queue.setGuidanceOn(true);
        check(has(queue, "voice_prompts_toggle=on"), "and on again");
        for (String stage : new String[] {"early", "prepare", "at_turn", "reroute", "arrival"}) {
            check(has(queue, "voice_prompts_spoken_" + stage + "=0"), "every stage listed at 0 before any is spoken: " + stage);
        }
        check(queue.getSpeaking() == null && !queue.getFocusHeld(), "a skip takes no focus");
        check(has(queue, "notifications_read_taps=4") && has(queue, "notifications_read_aloud=0"),
                "taps are counted, reads started only when started: " + queue.report());
        check(has(queue, "notifications_read_skipped_no_engine=2") && has(queue, "notifications_read_skipped_no_bluetooth=1")
                && has(queue, "notifications_read_skipped_off=1"), "each skip under its reason");
        check(has(queue, "voice_prompts_skipped_no_bluetooth=1") && has(queue, "voice_prompts_skipped_toggle_off=1"),
                "and a prompt's too");
        check(!queue.report().contains("notifications_read_skipped_toggle_off")
                && !queue.report().contains("voice_prompts_skipped_guidance_playing"), "no reason that cannot happen to that kind");
    }

    // P8.3: Play a sample. The prompt's gate, priority, focus and lead-in, but not the
    // toggle, and its counts on a line of their own so no drive's prompt count includes one.
    private static void aSampleTakesAPromptsPathAndIsCountedApart() {
        SpeechQueue queue = new SpeechQueue();
        check(has(queue, "voice_samples_requests=0") && has(queue, "voice_samples_skipped_no_bluetooth=0"),
                "listed at 0 before any sample: " + queue.report());
        skipped(queue.sample(0, NO_BLUETOOTH), SpeechSkip.NO_BLUETOOTH, "the truck-only gate holds for a sample");
        skipped(queue.sample(0, NO_ENGINE), SpeechSkip.NO_ENGINE, "and the engine");
        skipped(queue.sample(0, IN_CALL), SpeechSkip.IN_CALL, "and the call rule");
        check(has(queue, "voice_samples_requests=3") && has(queue, "voice_samples_skipped_no_bluetooth=1")
                && has(queue, "voice_samples_skipped_no_engine=1") && has(queue, "voice_samples_skipped_in_call=1"),
                "each skip counted as a sample's: " + queue.report());
        check(has(queue, "voice_prompts_requests=0") && has(queue, "voice_prompts_skipped_no_bluetooth=0"),
                "and never as a prompt's");

        queue.setGuidanceOn(false);
        SpeechAction.Speak read = speak(queue.read("a", 10_000, OK), "a notification being read");
        queue.began(read, 10_000);
        final SpeechEnd[] told = new SpeechEnd[1];
        queue.setSampleEnded(end -> {
            told[0] = end;
            return kotlin.Unit.INSTANCE;
        });
        SpeechAction.Speak sample = speak(queue.sample(11_000, OK), "the toggle off does not gate a sample");
        check(sample.getSample() && sample.getKind() == SpeechKind.GUIDANCE && sample.getStage() == null,
                "guidance, with no stage: " + sample);
        check(sample.getRequestFocus(), "focus asked with guidance's attributes");
        queue.began(sample, 11_000);
        check(has(queue, "notifications_read_interrupted_by_guidance=1"), "it cuts a read off, as a prompt does");
        skipped(queue.read("b", 11_500, OK), SpeechSkip.GUIDANCE_PLAYING, "a tap while it plays is dropped");
        check(queue.finished(sample.getId(), SpeechEnd.COMPLETED, 14_000) && told[0] == SpeechEnd.COMPLETED,
                "its end is told, for the phone to say");
        check(has(queue, "voice_samples_started=1") && has(queue, "voice_samples_completed=1"), "counted as a sample");
        check(has(queue, "voice_prompts_started=0") && has(queue, "voice_prompts_completed=0")
                && has(queue, "voice_prompts_skipped_toggle_off=0"), "not as a prompt: " + queue.report());
        for (String stage : new String[] {"early", "prepare", "at_turn", "reroute", "arrival"}) {
            check(has(queue, "voice_prompts_spoken_" + stage + "=0"), "no stage counted: " + stage);
        }
        check(!queue.report().contains("voice_prompts_spoken_null") && !queue.report().contains("sample="),
                "and no stray stage key");
        check(queue.abandonDue(14_000 + SpeechQueue.FOCUS_LINGER_MS), "the grant is given back after it");
        check(has(queue, "speech_focus_requests=2") && has(queue, "speech_focus_abandons=1"),
                "its focus request is counted with the audio system's, and the grant given back");

        queue.setGuidanceOn(true);
        SpeechAction.Speak again = speak(queue.sample(20_000, OK), "a second sample");
        queue.began(again, 20_000);
        SpeechAction.Speak prompt = speak(queue.guide("prepare", 21_000, OK), "a prompt due while it plays");
        queue.began(prompt, 21_000);
        check(told[0] == SpeechEnd.INTERRUPTED_BY_GUIDANCE, "the prompt cuts the sample off, and it is told");
        check(has(queue, "voice_prompts_interrupted_by_guidance=0") && has(queue, "voice_prompts_started=1"),
                "the cut sample is not a prompt interrupted");
        check(queue.sample(21_500, OK) instanceof SpeechAction.Speak, "a sample during a prompt still speaks, as a prompt would");

        SpeechQueue refused = new SpeechQueue();
        SpeechAction.Speak denied = speak(refused.sample(0, OK), "a sample");
        refused.refused(denied);
        check(has(refused, "voice_samples_skipped_no_focus=1") && has(refused, "voice_prompts_skipped_no_focus=0")
                && has(refused, "speech_focus_denied=1"), "refused focus counted as the sample's");

        check(SampleOutcome.Companion.of(SpeechEnd.COMPLETED) == SampleOutcome.SPOKEN, "completed is spoken");
        check(SampleOutcome.Companion.of(SpeechEnd.INTERRUPTED_BY_GUIDANCE) == SampleOutcome.INTERRUPTED, "cut off by a prompt");
        check(SampleOutcome.Companion.of(SpeechEnd.SESSION_END) == SampleOutcome.STOPPED, "a projection ending stops it");
        check(SampleOutcome.Companion.of(SpeechSkip.NO_BLUETOOTH) == SampleOutcome.NO_BLUETOOTH, "no Bluetooth, said so");
        check(SampleOutcome.Companion.of(SpeechSkip.IN_CALL) == SampleOutcome.IN_CALL, "in a call, said so");
    }

    private static void aSecondTapStops() {
        SpeechQueue queue = new SpeechQueue();
        SpeechAction.Speak first = speak(queue.read("a", 1000, OK), "a tap reads");
        check(first.getKind() == SpeechKind.NOTIFICATION && "a".equals(first.getKey()), "a notification, by key");
        check(first.getRequestFocus(), "asking for focus first");
        queue.began(first, 1000);
        check("a".equals(queue.getReading()), "the row stays lit while it is read");
        SpeechAction again = queue.read("a", 2000, OK);
        check(again instanceof SpeechAction.Stop && ((SpeechAction.Stop) again).getId().equals(first.getId()),
                "a second tap on it stops it: " + again);
        check(queue.getReading() == null && queue.getSpeaking() == null, "and the row goes out");
        check(has(queue, "notifications_read_aloud=1") && has(queue, "notifications_read_stopped_by_tap=1"), "counted as stopped by tap");
        check(!queue.finished(first.getId(), SpeechEnd.STOPPED, 2100), "the engine's onStop after it is not counted again");
        check(has(queue, "notifications_read_stopped=0"), "not as stopped from elsewhere");
        SpeechAction.Speak third = speak(queue.read("a", 3000, OK), "a third tap reads it again");
        queue.began(third, 3000);
        check("a".equals(queue.getReading()), "from the start");
    }

    private static void theNewestTapWins() {
        SpeechQueue queue = new SpeechQueue();
        SpeechAction.Speak a = speak(queue.read("a", 0, OK), "a");
        queue.began(a, 0);
        SpeechAction.Speak b = speak(queue.read("b", 500, OK), "a different row while one is read");
        check(!b.getRequestFocus(), "under the grant already held");
        check(b.getLeadInMs() == 0, "no lead-in: the stream is already running");
        queue.began(b, 500);
        check("b".equals(queue.getReading()), "the newest tap wins");
        check(has(queue, "notifications_read_replaced=1") && has(queue, "notifications_read_aloud=2"), "the first counted as replaced");
        check(!queue.finished(a.getId(), SpeechEnd.STOPPED, 600), "and the flushed one's onStop ignored");
        check(queue.finished(b.getId(), SpeechEnd.COMPLETED, 5000), "the second completes");
        check(has(queue, "notifications_read_completed=1"), "counted");
    }

    private static void guidanceInterruptsAndIsNeverQueuedBehind() {
        SpeechQueue queue = new SpeechQueue();
        SpeechAction.Speak read = speak(queue.read("a", 0, OK), "a read");
        queue.began(read, 0);
        SpeechAction.Speak prompt = speak(queue.guide("prepare", 2000, OK), "a turn prompt while it is read");
        check(prompt.getKind() == SpeechKind.GUIDANCE && "prepare".equals(prompt.getStage()), "guidance, by stage");
        check(prompt.getRequestFocus(), "focus asked again, with guidance's attributes");
        queue.began(prompt, 2000);
        check(queue.getSpeaking() == SpeechKind.GUIDANCE && queue.getReading() == null, "the prompt cuts the read off (decision 7)");
        check(has(queue, "notifications_read_interrupted_by_guidance=1"), "counted as interrupted by guidance");
        skipped(queue.read("a", 2500, OK), SpeechSkip.GUIDANCE_PLAYING, "a tap during a prompt is dropped, not queued");
        skipped(queue.read("b", 2600, OK), SpeechSkip.GUIDANCE_PLAYING, "any row");
        check(queue.getSpeaking() == SpeechKind.GUIDANCE, "and the prompt plays on");
        SpeechAction.Speak next = speak(queue.guide("at_turn", 3000, OK), "a prompt during a prompt");
        check(!next.getRequestFocus(), "same attributes, same grant");
        queue.began(next, 3000);
        check(has(queue, "voice_prompts_interrupted_by_guidance=1"), "the older prompt counted as interrupted");
        check(has(queue, "voice_prompts_spoken_prepare=1") && has(queue, "voice_prompts_spoken_at_turn=1")
                && has(queue, "voice_prompts_started=2"), "prompts counted per stage");
        check(queue.finished(next.getId(), SpeechEnd.COMPLETED, 4000), "done");
        SpeechAction.Speak after = speak(queue.read("a", 4200, OK), "once it is over, a tap reads again");
        check(after.getRequestFocus(), "asking for the assistant's attributes back");
    }

    private static void noPromptDuringACall() {
        SpeechQueue queue = new SpeechQueue();
        skipped(queue.guide("prepare", 0, IN_CALL), SpeechSkip.IN_CALL, "no prompt during a call (section 14.1)");
        skipped(queue.guide("prepare", 0, new SpeechConditions(false, false, true)), SpeechSkip.IN_CALL,
                "counted as in_call before the gate");
        check(has(queue, "voice_prompts_skipped_in_call=2"), "counted: " + queue.report());
        speak(queue.read("a", 0, IN_CALL), "reading a tapped notification is not the call rule's business");
    }

    private static void oneGrantForUtterancesCloseTogether() {
        SpeechQueue queue = new SpeechQueue();
        SpeechAction.Speak a = speak(queue.read("a", 0, OK), "a");
        queue.began(a, 0);
        check(queue.finished(a.getId(), SpeechEnd.COMPLETED, 2000), "a done");
        check(queue.getFocusHeld() && !queue.abandonDue(2000 + SpeechQueue.FOCUS_LINGER_MS - 1), "the grant lingers");
        SpeechAction.Speak b = speak(queue.read("b", 2500, OK), "b, half a second later");
        check(!b.getRequestFocus(), "shares the grant: no second request");
        queue.began(b, 2500);
        check(!queue.abandonDue(9000), "never given back while speaking");
        check(queue.finished(b.getId(), SpeechEnd.COMPLETED, 4000), "b done");
        check(!queue.abandonDue(4999), "not before the linger");
        check(queue.abandonDue(5000), "given back a second after the last");
        check(!queue.abandonDue(6000) && !queue.getFocusHeld(), "once");
        check(has(queue, "speech_focus_requests=1") && has(queue, "speech_focus_abandons=1"), "one request, one abandon: " + queue.report());
        SpeechAction.Speak c = speak(queue.read("c", 20000, OK), "later");
        check(c.getRequestFocus(), "asks again once it was given back");
    }

    private static void theGrantIsAlwaysGivenBack() {
        SpeechQueue queue = new SpeechQueue();
        SpeechAction.Speak a = speak(queue.read("a", 0, OK), "a");
        queue.began(a, 0);
        check(queue.interrupt(SpeechEnd.BLUETOOTH_LOST, 100), "A2DP gone: stopped");
        check(has(queue, "notifications_read_bluetooth_lost=1"), "counted");
        check(queue.abandonDue(100 + SpeechQueue.FOCUS_LINGER_MS), "and the grant goes a second later");

        SpeechAction.Speak b = speak(queue.read("b", 10000, OK), "b");
        queue.began(b, 10000);
        check(queue.finished(b.getId(), SpeechEnd.ERROR, 10100), "an engine error ends it");
        check(has(queue, "notifications_read_error=1"), "counted");
        check(queue.abandonDue(10100 + SpeechQueue.FOCUS_LINGER_MS), "and still gives the grant back");

        SpeechAction.Speak c = speak(queue.read("c", 20000, OK), "c");
        queue.began(c, 20000);
        check(queue.focusLost(20100), "another app took focus: ours is given back");
        check(queue.getSpeaking() == null && !queue.getFocusHeld(), "and nothing is being said");
        check(has(queue, "notifications_read_focus_lost=1"), "counted");

        SpeechAction.Speak d = speak(queue.guide("early", 30000, OK), "d");
        queue.began(d, 30000);
        check(queue.end(30100), "projection ends mid-prompt: the grant must go now");
        check(!queue.getFocusHeld() && queue.getSpeaking() == null, "nothing held after the end");
        check(has(queue, "voice_prompts_session_end=1") && has(queue, "speech_focus_held=false"), "counted: " + queue.report());
        check(!queue.end(30200), "a second end gives nothing back twice");
        check(has(queue, "speech_focus_requests=4") && has(queue, "speech_focus_abandons=4"), "every request given back");
    }

    private static void theLeadInFollowsSilenceOnly() {
        SpeechQueue queue = new SpeechQueue();
        SpeechAction.Speak first = speak(queue.read("a", 0, OK), "first");
        check(first.getLeadInMs() == SpeechQueue.LEAD_IN_MS && SpeechQueue.LEAD_IN_MS == 300, "the first utterance gets 300 ms of silence");
        queue.began(first, 0);
        queue.finished(first.getId(), SpeechEnd.COMPLETED, 1000);
        SpeechAction.Speak soon = speak(queue.read("b", 1000 + SpeechQueue.LEAD_IN_AFTER_IDLE_MS - 1, OK), "soon after");
        check(soon.getLeadInMs() == 0, "none just after speech: the stream is still up");
        queue.began(soon, 3999);
        queue.finished(soon.getId(), SpeechEnd.COMPLETED, 5000);
        SpeechAction.Speak later = speak(queue.guide("prepare", 5000 + SpeechQueue.LEAD_IN_AFTER_IDLE_MS, OK), "after a few seconds");
        check(later.getLeadInMs() == SpeechQueue.LEAD_IN_MS, "gets it again");
        queue.began(later, 8000);
        check(has(queue, "speech_lead_ins=2"), "counted");
    }

    private static void refusedFocusCutsNothing() {
        SpeechQueue queue = new SpeechQueue();
        SpeechAction.Speak read = speak(queue.read("a", 0, OK), "a read");
        queue.began(read, 0);
        SpeechAction.Speak prompt = speak(queue.guide("prepare", 100, OK), "a prompt");
        queue.refused(prompt);
        check("a".equals(queue.getReading()), "refused focus: the read goes on");
        check(has(queue, "voice_prompts_skipped_no_focus=1") && has(queue, "speech_focus_denied=1")
                && has(queue, "voice_prompts_started=0"), "counted as a skip, not a start");
    }

    private static void staleCallbacksAreIgnored() {
        SpeechQueue queue = new SpeechQueue();
        SpeechAction.Speak a = speak(queue.read("a", 0, OK), "a");
        queue.began(a, 0);
        check(!queue.finished(a.getId() + "-lead", SpeechEnd.COMPLETED, 300), "the lead-in's own onDone is not the read's");
        check(!queue.finished("oaa-999", SpeechEnd.COMPLETED, 300), "nor an unknown id");
        check("a".equals(queue.getReading()), "still reading");
        check(!queue.abandonDue(99999), "and the grant held");
    }

    private static void theReportHoldsCountsOnly() {
        SpeechQueue queue = new SpeechQueue();
        String key = "0|com.secret.app|1|secretkey|10123";
        SpeechAction.Speak a = speak(queue.read(key, 0, OK), "a");
        queue.began(a, 0);
        queue.textFetched(true, true);
        queue.finished(a.getId(), SpeechEnd.COMPLETED, 100);
        queue.textFetched(false, false);
        String report = queue.report();
        check(report.contains("notifications_read_full_text=1") && report.contains("notifications_read_first_line=1")
                && report.contains("notifications_read_truncated=1"), "where the words came from, as counts: " + report);
        for (String secret : new String[] {"secret", "com.", "oaa-"}) {
            check(!report.contains(secret), "the report never carries " + secret);
        }
        check(report.split("\n").length == 5, "five lines: notifications, prompts, P10's weather alerts, P8.3's samples, focus");
    }

    private static DashNotification row(String title, String text) {
        return new DashNotification("k", "Chat", title, text, 1, "com.chat");
    }

    private static String say(DashNotification row, NotificationWords words) {
        return ReadAloudText.INSTANCE.compose(row, words).getText();
    }

    private static void whatATappedNotificationSays() {
        DashNotification row = row("Sarah", "Running ten");
        check("Sarah. Running ten minutes late.".equals(say(row,
                new NotificationWords("Sarah", null, null, null, "Running ten minutes late."))), "sender and text, no app (decision 5)");
        check("Sarah. The whole long story.".equals(say(row,
                new NotificationWords("Sarah", "The whole long story.", null, null, "The whole"))), "big text wins over the text");
        check("Bob. See you at 6".equals(say(row,
                new NotificationWords("Family", null, "Bob", "See you at 6", "3 new messages"))),
                "the last message, with its sender, over the conversation's summary");
        check("Family. Long form".equals(say(row,
                new NotificationWords("Family", "Long form", "Bob", "See you at 6", "3 new messages"))),
                "big text first, under the title, as section 8 orders it");
        check("Sarah. Running ten".equals(say(row, null)), "gone from the phone: the tile's first line");
        check("Running ten".equals(say(new DashNotification("k", "Chat", "Chat", "Running ten", 1, "com.chat"), null)),
                "a row that fell back to the app's name says no name");
        check("Hello there".equals(say(new DashNotification("k", "Chat", "Chat", "x", 1, "com.chat"),
                new NotificationWords(null, null, null, null, "Hello\n\n  there"))), "whitespace collapsed; still no app name");
        check("Sarah!".equals(say(row("Sarah!", ""), new NotificationWords("Sarah!", null, null, null, " "))), "who alone");
        check("Sarah? Hi".equals(say(row, new NotificationWords("Sarah?", null, null, null, "Hi"))), "no second stop after a question");
        StringBuilder longText = new StringBuilder();
        while (longText.length() < 900) longText.append("word ");
        ReadAloudText.Composed cut = ReadAloudText.INSTANCE.compose(row, new NotificationWords("Sarah", longText.toString(), null, null, null));
        String body = cut.getText().substring("Sarah. ".length());
        check(cut.getTruncated() && body.endsWith(". " + ReadAloudText.TRUNCATED), "cut, ending \"message truncated\": " + body);
        String kept = body.substring(0, body.length() - (". " + ReadAloudText.TRUNCATED).length());
        check(kept.length() <= ReadAloudText.MAX && kept.length() > ReadAloudText.MAX - 10 && kept.endsWith("word"),
                "at a word, about " + ReadAloudText.MAX + " characters: " + kept.length());
        String exact = "x".repeat(ReadAloudText.MAX);
        ReadAloudText.Composed whole = ReadAloudText.INSTANCE.compose(row, new NotificationWords("Sarah", null, null, null, exact));
        check(!whole.getTruncated() && whole.getText().endsWith(exact), "exactly the limit is not cut");
        check("".equals(say(row("Chat", ""), new NotificationWords(null, null, null, null, null)).replace("Chat", "")),
                "nothing to say is empty, never the app");
    }

    private static void theMediaSetting() {
        check(SpeechMedia.Companion.getDEFAULT() == SpeechMedia.PAUSE, "pause media by default (decision 10)");
        check(SpeechMedia.Companion.of("duck") == SpeechMedia.DUCK && SpeechMedia.Companion.of("pause") == SpeechMedia.PAUSE, "by key");
        check(SpeechMedia.Companion.of(null) == SpeechMedia.PAUSE && SpeechMedia.Companion.of("shout") == SpeechMedia.PAUSE,
                "unknown reads as the default");
        check(SpeechMedia.Companion.getLABELS().get(0).equals("Pause media") && SpeechMedia.Companion.getLABELS().get(1).equals("Lower volume"),
                "the screen's order");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
