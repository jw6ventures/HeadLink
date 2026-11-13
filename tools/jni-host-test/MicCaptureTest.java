// Voice input's microphone rules on a desktop JVM (spec.md 2.1 and 5.2 as amended
// 11 November 2026; docs/audio-and-speech.md, "Voice input: the car's microphone"): only
// the car's hands-free link is ever chosen and a recorder on any other microphone is
// refused, the clip format the speech engine takes, levels in dBFS, the end of an
// utterance after speech and quiet, the no-speech timeout, road noise that never counts
// as speech, and a report of counts and milliseconds only. MicRules, UtteranceDetector
// and MicTally are pure Kotlin; BluetoothMic, the Android glue (AudioManager's
// communication device, AudioRecord), needs a phone and a car.
import java.util.List;
import com.jw6ventures.headlink.MicEnd;
import com.jw6ventures.headlink.MicFailure;
import com.jw6ventures.headlink.MicPort;
import com.jw6ventures.headlink.MicResult;
import com.jw6ventures.headlink.MicRules;
import com.jw6ventures.headlink.MicStats;
import com.jw6ventures.headlink.MicTally;
import com.jw6ventures.headlink.UtteranceDetector;
import com.jw6ventures.headlink.UtteranceTuning;
import com.jw6ventures.headlink.VoiceClip;

public final class MicCaptureTest {
    private static int passed;
    private static final MicRules RULES = MicRules.INSTANCE;
    private static final int SCO = 7;
    private static final int BUILTIN_MIC = 15;
    private static final int WIRED_HEADSET = 3;
    private static final int BLE_HEADSET = 26;

    public static void main(String[] args) {
        onlyTheCarsLinkIsChosen();
        theRecorderMustBeOnTheCarsLink();
        theClipIsWhatTheSpeechEngineTakes();
        levels();
        speechThenQuietEndsTheUtterance();
        shortBlipsAreNotSpeech();
        nothingSaidStopsWithNoSpeech();
        roadNoiseIsNotSpeech();
        speechOverRoadNoiseIsHeard();
        theReportHoldsCountsOnly();
        System.out.println("jni-host-test: ok " + passed + " microphone checks");
    }

    private static void onlyTheCarsLinkIsChosen() {
        MicPort car = new MicPort(SCO, 12);
        check(RULES.pickRoute(List.of(new MicPort(BUILTIN_MIC, 1), car)) == car, "the car's hands-free link is chosen");
        check(RULES.pickRoute(List.of(new MicPort(BUILTIN_MIC, 1))) == null, "the phone's own microphone never is");
        check(RULES.pickRoute(List.of(new MicPort(WIRED_HEADSET, 2), new MicPort(BLE_HEADSET, 3))) == null,
                "nor a wired or LE headset");
        check(RULES.pickRoute(List.of()) == null, "nothing connected, nothing chosen");
        check(MicRules.TYPE_BLUETOOTH_SCO == SCO && MicRules.TYPE_BUILTIN_MIC == BUILTIN_MIC,
                "the mirrored constants are AudioDeviceInfo's");
    }

    private static void theRecorderMustBeOnTheCarsLink() {
        check(RULES.routedToCar(SCO), "a recorder on the car's link is kept");
        check(!RULES.routedToCar(BUILTIN_MIC), "one on the phone's microphone is refused");
        check(!RULES.routedToCar(null), "an unknown route is refused");
        check(RULES.getSOURCES().equals(List.of(MicRules.SOURCE_RECOGNITION, MicRules.SOURCE_COMMUNICATION)),
                "recognition first, then communication");
    }

    private static void theClipIsWhatTheSpeechEngineTakes() {
        check(MicRules.SAMPLE_RATE == 16000, "16 kHz");
        check(MicRules.MAX_SAMPLES == 480000, "at most 30 s, the speech engine's own cap");
        check(MicRules.FRAME_SAMPLES == 320 && MicRules.FRAME_MS == 20, "20 ms frames");
        VoiceClip clip = new VoiceClip(new short[24000], MicRules.SAMPLE_RATE);
        check(clip.getDurationMs() == 1500, "1.5 s of samples is 1500 ms");
        check(clip.getSampleRate() == 16000, "the clip says its rate");
    }

    private static void levels() {
        check(RULES.dbfs(new short[320], 0, 320) == MicRules.SILENCE_DB, "digital silence");
        double full = RULES.dbfs(square(32767, 320), 0, 320);
        check(Math.abs(full) < 0.01, "a full-scale square is 0 dBFS, got " + full);
        double half = RULES.dbfs(square(16384, 320), 0, 320);
        check(Math.abs(half + 6.02) < 0.05, "half scale is -6 dBFS, got " + half);
        check(Math.abs(RULES.peakDbfs(square(16384, 320), 0, 320) + 6.02) < 0.05, "peak of half scale");
        short[] mixed = new short[640];
        System.arraycopy(square(32767, 320), 0, mixed, 320, 320);
        check(RULES.dbfs(mixed, 0, 320) == MicRules.SILENCE_DB, "an offset frame is read on its own");
        check(RULES.peakDbfs(new short[] {Short.MIN_VALUE}, 0, 1) == 0.0, "the most negative sample does not overflow");
    }

    private static short[] square(int amplitude, int n) {
        short[] out = new short[n];
        for (int i = 0; i < n; i++) out[i] = (short) (i % 2 == 0 ? amplitude : -amplitude);
        return out;
    }

    /** Feeds [frames] frames at [db]; returns the first end, or null. */
    private static MicEnd feed(UtteranceDetector detector, double db, int frames) {
        for (int i = 0; i < frames; i++) {
            MicEnd end = detector.feed(db);
            if (end != null) return end;
        }
        return null;
    }

    private static void speechThenQuietEndsTheUtterance() {
        UtteranceDetector detector = new UtteranceDetector(new UtteranceTuning());
        check(feed(detector, -70, 25) == null, "quiet before speech keeps listening");
        check(feed(detector, -25, 60) == null, "1.2 s of speech keeps listening");
        check(detector.getStarted(), "speech has started");
        check(feed(detector, -70, 40) == null, "800 ms of quiet is a pause, not the end");
        check(feed(detector, -25, 10) == null, "speech again");
        MicEnd end = feed(detector, -70, 44);
        check(end == null, "quiet restarts from the last speech");
        check(detector.feed(-70) == MicEnd.UTTERANCE_END, "900 ms of quiet ends it");
        check(detector.getSpeechFrames() == 70, "speech frames counted, got " + detector.getSpeechFrames());
    }

    private static void shortBlipsAreNotSpeech() {
        UtteranceDetector detector = new UtteranceDetector(new UtteranceTuning());
        feed(detector, -70, 10);
        for (int i = 0; i < 20; i++) {
            detector.feed(-25);
            detector.feed(-25);
            feed(detector, -70, 5);
        }
        check(!detector.getStarted(), "40 ms clicks are not speech");
    }

    private static void nothingSaidStopsWithNoSpeech() {
        UtteranceDetector detector = new UtteranceDetector(new UtteranceTuning());
        check(feed(detector, -70, 299) == null, "still waiting at 5.98 s");
        check(detector.feed(-70) == MicEnd.NO_SPEECH, "no speech by 6 s");
        UtteranceDetector shorter = new UtteranceDetector(new UtteranceTuning(10.0, -50.0, 3, 900, 2000));
        check(feed(shorter, -70, 100) == MicEnd.NO_SPEECH, "the timeout is tunable");
    }

    private static void roadNoiseIsNotSpeech() {
        // A loud cabin: steady -40 dBFS with a few dB of wander is above the -50 floor, but
        // the noise floor follows it, so it never reads as speech.
        UtteranceDetector detector = new UtteranceDetector(new UtteranceTuning());
        MicEnd end = null;
        for (int i = 0; i < 300 && end == null; i++) end = detector.feed(-40 + (i % 7) - 3);
        check(end == MicEnd.NO_SPEECH, "steady road noise ends with no speech, got " + end);
        check(Math.abs(detector.getNoiseDb() + 41) < 3, "the floor settles near the noise, got " + detector.getNoiseDb());
    }

    private static void speechOverRoadNoiseIsHeard() {
        UtteranceDetector detector = new UtteranceDetector(new UtteranceTuning());
        feed(detector, -40, 30);
        check(feed(detector, -22, 50) == null, "speech 18 dB over the road");
        check(detector.getStarted(), "is heard as speech");
        check(detector.getNoiseDb() < -36, "and barely lifts the floor, got " + detector.getNoiseDb());
        check(feed(detector, -40, 60) == MicEnd.UTTERANCE_END, "back to road noise ends it");
    }

    private static void theReportHoldsCountsOnly() {
        MicTally tally = new MicTally();
        String empty = tally.report();
        check(empty.contains("mic_captures=0") && empty.contains("mic_outcomes=-") && empty.contains("mic_route_ms_last=-"),
                "an empty report");
        MicStats stats = new MicStats(850, false, MicRules.SOURCE_RECOGNITION, 2000, 1500, 40, -12.0, -48.0);
        tally.record(new MicResult.Heard(new VoiceClip(new short[32000], 16000), MicEnd.UTTERANCE_END, stats));
        tally.record(new MicResult.Failed(MicFailure.NO_BLUETOOTH_MIC, null));
        MicStats slow = new MicStats(2400, true, "-", 0, 0, -1, MicRules.SILENCE_DB, MicRules.SILENCE_DB);
        tally.record(new MicResult.Failed(MicFailure.ROUTE_TIMEOUT, slow));
        String report = tally.report();
        check(report.contains("mic_captures=3"), "three captures");
        check(report.contains("mic_outcomes=no_bluetooth_mic:1,route_timeout:1,utterance_end:1"), "each outcome counted: " + report);
        check(report.contains("mic_route_ms_last=2400") && report.contains("mic_route_ms_max=2400"), "route times");
        check(report.contains("mic_call_mode_needed=1"), "call mode counted");
        check(report.contains("mic_source_last=voice_recognition"), "the source that delivered");
        check(report.contains("mic_heard_ms_total=2000"), "heard time");
        for (String line : report.split("\n")) {
            check(line.startsWith("mic_"), "only mic_ lines: " + line);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
