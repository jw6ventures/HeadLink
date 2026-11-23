// Voice input's speech recognizer on a desktop JVM (docs/audio-and-speech.md, "Speech
// recognition"): the release Kotlin WhisperEngine over a host x86_64 build of
// libwhisper_jni.so (tools/whisper-build.sh), from the same pinned whisper.cpp source and
// JNI file as the phone's arm64 build.
//
// No real model is in the repository or downloaded: the operator supplies one (spec §2.1
// as amended). Instead this test writes a small whisper.cpp model of its own, the real
// file format and tensor set at tiny sizes, whose weights make the decoder say one word
// whatever it hears and then stop. That runs the whole path (the descriptor loader, mel,
// encoder, decoder, prompt, text out, cancel, free) and checks the refusals: a file that is
// not a model, one cut short, audio too short or too long, use after close.
//
// WHISPER_MODEL=<a real ggml model file> adds the load time and the time to recognise 5 s
// of silence, which should come back as heard-nothing; without it the summary says so.
// SpeechModel, the Android half (the Storage Access Framework grant, /proc/cpuinfo), needs
// a phone.
import com.jw6ventures.headlink.SpeechCpu;
import com.jw6ventures.headlink.Recognizer;
import com.jw6ventures.headlink.SpeechModelInfo;
import com.jw6ventures.headlink.SpeechProblem;
import com.jw6ventures.headlink.SpeechStats;
import com.jw6ventures.headlink.SpeechText;
import com.jw6ventures.headlink.Transcript;
import com.jw6ventures.headlink.WhisperEngine;
import com.jw6ventures.headlink.WhisperNative;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class RecognizerTest {
    private static int passed;

    // The token the test model says: in the English vocabulary's text range, below EOT.
    private static final int WORD = 1000;
    private static final int EOT = 50256;
    private static final String SAID = " hello";

    public static void main(String[] args) throws Exception {
        String realModel = args.length > 0 ? args[0] : "";
        theTextIsCleaned();
        theProcessorIsChecked();
        theModelIsDescribed();
        theThreadCount();
        theReportHoldsCountsOnly();

        String loadError = WhisperNative.INSTANCE.getLoadError();
        check(loadError == null, "libwhisper_jni loads: " + loadError);
        check(WhisperNative.INSTANCE.version().equals("1.8.3"), "whisper.cpp 1.8.3: " + WhisperNative.INSTANCE.version());

        File dir = Files.createTempDirectory("speech-test").toFile();
        try {
            notAModelIsRefused(dir);
            aModelCutShortIsRefused(dir);
            theTestModelSpeaks(dir);
            theRefusals(dir);
            closingFrees(dir);
            String real = realModel.isEmpty() ? "real model skipped (set WHISPER_MODEL)" : aRealModel(new File(realModel));
            System.out.println("jni-host-test: ok " + passed + " speech recognizer checks; " + real);
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void theTextIsCleaned() {
        check(SpeechText.INSTANCE.clean(" Hello there.").equals("Hello there."), "leading space dropped");
        check(SpeechText.INSTANCE.clean("[BLANK_AUDIO]").isEmpty(), "blank audio is nothing");
        check(SpeechText.INSTANCE.clean(" (music) ").isEmpty(), "a sound tag is nothing");
        check(SpeechText.INSTANCE.clean("*coughs* .").isEmpty(), "a tag and a full stop are nothing");
        check(SpeechText.INSTANCE.clean("♪ ♪").isEmpty(), "music notes are nothing");
        check(SpeechText.INSTANCE.clean(" On my way [BLANK_AUDIO] home.").equals("On my way home."), "a tag mid-text goes");
        check(SpeechText.INSTANCE.clean("Call  me\nat 5").equals("Call me at 5"), "runs of space become one");
        check(SpeechText.INSTANCE.clean("Yes!").equals("Yes!"), "punctuation after words stays");
    }

    private static void theProcessorIsChecked() {
        String modern = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp\n"
            + "processor\t: 1\nFeatures\t: fp asimd asimdhp asimddp\n";
        check(SpeechCpu.INSTANCE.missing(modern).isEmpty(), "a Cortex-A55-class phone is supported");
        String a53 = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid\n";
        check(SpeechCpu.INSTANCE.missing(a53).equals(List.of("asimdhp", "asimddp")), "a Cortex-A53 phone lacks both");
        String mixed = "Features\t: fp asimd asimdhp asimddp\nFeatures\t: fp asimd asimdhp\n";
        check(SpeechCpu.INSTANCE.missing(mixed).equals(List.of("asimddp")), "every core must have them");
        check(SpeechCpu.INSTANCE.missing("").size() == 2, "unreadable cpuinfo is unsupported");
    }

    private static void theModelIsDescribed() {
        check(SpeechModelInfo.Companion.parse("base en").getKey().equals("base.en"), "base en is base.en");
        check(SpeechModelInfo.Companion.parse("small multi").getKey().equals("small"), "a multilingual small is small");
        check(SpeechModelInfo.Companion.parse(null).getSize().equals("unknown"), "no description is unknown");
    }

    private static void theThreadCount() {
        check(WhisperEngine.Companion.defaultThreads(8) == 4, "8 cores use 4 threads");
        check(WhisperEngine.Companion.defaultThreads(6) == 3, "6 cores use 3");
        check(WhisperEngine.Companion.defaultThreads(1) == 1, "1 core uses 1");
        check(WhisperEngine.Companion.defaultThreads(16) == 4, "never more than 4");
    }

    private static void theReportHoldsCountsOnly() {
        SpeechStats stats = new SpeechStats();
        check(stats.report().contains("speech_model=- ") && stats.report().contains("speech_model_load_ms=-"), "nothing loaded yet");
        stats.loaded(new SpeechModelInfo("base", true), 900);
        stats.record(new Transcript.Text("Text Alice I am running late", 3000, 800));
        stats.record(new Transcript.Silence(2000, 600));
        stats.record(Transcript.Cancelled.INSTANCE);
        stats.record(new Transcript.Failed(Transcript.TOO_SHORT));
        stats.record(new Transcript.Failed(Transcript.TOO_SHORT));
        stats.unavailable(SpeechProblem.NOT_A_MODEL);
        String report = stats.report();
        check(report.contains("speech_model=base.en ") && report.contains("speech_model_load_ms=900"), "model and load time: " + report);
        check(report.contains("speech_heard=1 speech_nothing=1 speech_cancelled=1"), "outcomes counted: " + report);
        check(report.contains("speech_failed=too_short:2"), "failures by reason: " + report);
        check(report.contains("speech_audio_ms=5000 speech_recognise_ms=1400 speech_slowest_ms=800"), "timings: " + report);
        check(report.contains("speech_unavailable=1 speech_unavailable_last=not_a_model"), "why not loaded: " + report);
        check(!report.contains("Alice") && !report.contains("late"), "no words in the report");
        check(!new Transcript.Text("Alice", 1, 1).toString().contains("Alice"), "a transcript's toString hides the words");
    }

    private static void notAModelIsRefused(File dir) throws IOException {
        File junk = new File(dir, "notes.txt");
        Files.write(junk.toPath(), "not a speech model at all".getBytes(StandardCharsets.UTF_8));
        check(load(junk) == null, "a text file is refused");
        File empty = new File(dir, "empty.bin");
        Files.write(empty.toPath(), new byte[0]);
        check(load(empty) == null, "an empty file is refused");
        // The right magic and absurd sizes: a refusal, not an allocation failure that crashes.
        File absurd = new File(dir, "absurd.bin");
        ByteBuffer header = ByteBuffer.allocate(4 * 14).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x67676d6c);
        for (int i = 0; i < 11; i++) header.putInt(i == 10 ? 0 : 1);
        header.putInt(Integer.MAX_VALUE).putInt(Integer.MAX_VALUE);
        Files.write(absurd.toPath(), header.array());
        check(load(absurd) == null, "a header asking for absurd sizes is refused");
        File f32 = writeModel(dir, "f32.bin", 1.0f, 0.5f);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f32, "rw")) {
            raf.seek(44);
            raf.write(new byte[4]);
        }
        check(load(f32) == null, "an all-F32 model is refused (ggml would abort on its convolutions)");
    }

    private static void aModelCutShortIsRefused(File dir) throws IOException {
        File whole = writeModel(dir, "whole.bin", 1.0f, 0.5f);
        File cut = new File(dir, "cut.bin");
        byte[] bytes = Files.readAllBytes(whole.toPath());
        Files.write(cut.toPath(), java.util.Arrays.copyOf(bytes, bytes.length - 100));
        check(load(cut) == null, "a model cut short (a half-copied file) is refused");
        Files.write(cut.toPath(), java.util.Arrays.copyOf(bytes, 600_000));
        check(load(cut) == null, "a model cut inside its vocabulary is refused");
        File hollow = writeModel(dir, "hollow.bin", 1.0f, 0.5f, false);
        check(load(hollow) == null, "a header and vocabulary with no weights (whisper.cpp's empty test model) is refused");
    }

    private static void theTestModelSpeaks(File dir) throws IOException {
        File model = writeModel(dir, "hello.bin", 1.0f, 0.5f);
        AtomicLong now = new AtomicLong(1000);
        WhisperEngine engine = WhisperEngine.Companion.load(descriptor(model), 2, () -> now.addAndGet(250));
        check(engine != null, "the test model loads");
        check(engine.getModel().getKey().equals("tiny.en"), "four layers and the English vocabulary is tiny.en: " + engine.getModel());
        short[] samples = tone(3 * Recognizer.SAMPLE_RATE);
        Transcript heard = engine.transcribe(samples, samples.length, null);
        check(heard instanceof Transcript.Text, "words come back: " + heard);
        Transcript.Text text = (Transcript.Text) heard;
        check(text.getText().equals(SAID.trim()), "the model's word, cleaned: '" + text.getText() + "'");
        check(text.getAudioMs() == 3000, "audio length from the sample count");
        check(text.getElapsedMs() == 250, "elapsed from the clock");
        Transcript primed = engine.transcribe(samples, 2 * Recognizer.SAMPLE_RATE, "Alice, Bob, Carol. Yes. No, change it.");
        check(primed instanceof Transcript.Text && ((Transcript.Text) primed).getAudioMs() == 2000, "a prompt and a partial count work: " + primed);
        engine.cancel();
        Transcript after = engine.transcribe(samples, samples.length, null);
        check(after instanceof Transcript.Text, "a cancel while idle does not stop the next one: " + after);
        engine.close();

        // Whisper's own word for a silent window.
        File quiet = writeModel(dir, "quiet.bin", 1.0f, 0.5f, "[BLANK_AUDIO]", true);
        WhisperEngine silent = WhisperEngine.Companion.load(descriptor(quiet), 2, System::currentTimeMillis);
        check(silent != null, "a model that hears silence loads");
        Transcript nothing = silent.transcribe(samples, samples.length, null);
        check(nothing instanceof Transcript.Silence, "no words is heard-nothing, not empty text: " + nothing);
        silent.close();
    }

    private static void theRefusals(File dir) throws IOException {
        WhisperEngine engine = WhisperEngine.Companion.load(descriptor(new File(dir, "hello.bin")), 1, System::currentTimeMillis);
        short[] tap = new short[Recognizer.MIN_SAMPLES - 1];
        check(failed(engine.transcribe(tap, tap.length, null), Transcript.TOO_SHORT), "a tap is too short");
        short[] tooLong = new short[Recognizer.MAX_SAMPLES + 1];
        check(failed(engine.transcribe(tooLong, tooLong.length, null), Transcript.TOO_LONG), "over 30 s is refused, not cut");
        check(engine.transcribe(tooLong, Recognizer.MAX_SAMPLES, null) instanceof Transcript.Text, "exactly 30 s is taken");
        boolean threw = false;
        try {
            engine.transcribe(tap, tap.length + 1, null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "a count past the array is a caller's error");
        engine.close();
    }

    private static void closingFrees(File dir) throws IOException {
        WhisperEngine engine = WhisperEngine.Companion.load(descriptor(new File(dir, "hello.bin")), 1, System::currentTimeMillis);
        engine.close();
        engine.close();
        short[] samples = tone(Recognizer.SAMPLE_RATE);
        check(failed(engine.transcribe(samples, samples.length, null), Transcript.CLOSED), "closed engines refuse");
        engine.cancel();
        // Loading many times frees each: a leak of a few MB each would show in a long drive.
        for (int i = 0; i < 20; i++) {
            WhisperEngine again = WhisperEngine.Companion.load(descriptor(new File(dir, "hello.bin")), 1, System::currentTimeMillis);
            check(again != null, "reloads");
            again.close();
        }
    }

    private static String aRealModel(File file) throws IOException {
        long started = System.currentTimeMillis();
        WhisperEngine engine = WhisperEngine.Companion.load(descriptor(file), WhisperEngine.Companion.defaultThreads(Runtime.getRuntime().availableProcessors()), System::currentTimeMillis);
        long loadMs = System.currentTimeMillis() - started;
        check(engine != null, "WHISPER_MODEL loads");
        Transcript silence = engine.transcribe(new short[5 * Recognizer.SAMPLE_RATE], 5 * Recognizer.SAMPLE_RATE, null);
        check(silence instanceof Transcript.Silence, "5 s of silence is heard-nothing: " + silence);
        engine.close();
        return engine.getModel().getKey() + " loaded in " + loadMs + " ms, 5 s silence in " + ((Transcript.Silence) silence).getElapsedMs() + " ms";
    }

    private static boolean failed(Transcript t, String reason) {
        return t instanceof Transcript.Failed && ((Transcript.Failed) t).getReason().equals(reason);
    }

    private static WhisperEngine load(File file) throws IOException {
        return WhisperEngine.Companion.load(descriptor(file), 1, System::currentTimeMillis);
    }

    // The descriptor's number, as Android's ParcelFileDescriptor.getFd() gives it. The stream
    // stays open for the test's life; the loader never closes it.
    private static int descriptor(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        OPEN.add(in);
        try {
            Field fd = FileDescriptor.class.getDeclaredField("fd");
            fd.setAccessible(true);
            return fd.getInt(in.getFD());
        } catch (ReflectiveOperationException e) {
            throw new IOException(e);
        }
    }

    private static final java.util.ArrayList<FileInputStream> OPEN = new java.util.ArrayList<>();

    private static short[] tone(int n) {
        short[] samples = new short[n];
        for (int i = 0; i < n; i++) samples[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * i / Recognizer.SAMPLE_RATE));
        return samples;
    }

    /**
     * A whisper.cpp model in the real file format (src/whisper.cpp, whisper_model_load):
     * English vocabulary, 1500 audio and 448 text positions, 80 mels, four encoder and four
     * decoder layers, at width 16, F16 weights. Every weight is zero except the decoder's final
     * layer norm bias and two rows of the token embedding, so the logits are the same at
     * every step: [eot] for EOT and [word] for [said]. Whisper never ends on its first
     * token, so it says the word once and then ends.
     */
    private static File writeModel(File dir, String name, float eot, float word) throws IOException {
        return writeModel(dir, name, eot, word, SAID, true);
    }

    private static File writeModel(File dir, String name, float eot, float word, boolean withTensors) throws IOException {
        return writeModel(dir, name, eot, word, SAID, withTensors);
    }

    private static File writeModel(File dir, String name, float eot, float word, String said, boolean withTensors)
            throws IOException {
        final int vocab = 51864, audioCtx = 1500, textCtx = 448, state = 16, heads = 2, layers = 4, mels = 80;
        final float scale = 100f;
        File file = new File(dir, name);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file), 1 << 16))) {
            int[] header = {0x67676d6c, vocab, audioCtx, state, heads, layers, textCtx, state, heads, layers, mels, 1};
            for (int v : header) le(out, v);
            le(out, mels);
            le(out, 201);
            for (int i = 0; i < mels * 201; i++) le(out, 0f);
            le(out, EOT);
            for (int i = 0; i < EOT; i++) {
                // whisper.cpp looks up " ", " -" and " '" by text when it filters logits.
                String text = i == WORD ? said : i == 220 ? " " : i == 532 ? " -" : i == 705 ? " '" : "w" + i;
                byte[] token = text.getBytes(StandardCharsets.UTF_8);
                le(out, token.length);
                out.write(token);
            }
            if (!withTensors) return file;
            tensor(out, "encoder.positional_embedding", null, state, audioCtx);
            weight(out, "encoder.conv1.weight", null, 3, mels, state);
            tensor(out, "encoder.conv1.bias", null, 1, state);
            weight(out, "encoder.conv2.weight", null, 3, state, state);
            tensor(out, "encoder.conv2.bias", null, 1, state);
            tensor(out, "encoder.ln_post.weight", null, state);
            tensor(out, "encoder.ln_post.bias", null, state);
            for (int l = 0; l < layers; l++) block(out, "encoder.blocks." + l + ".", state, false);
            tensor(out, "decoder.positional_embedding", null, state, textCtx);
            float[] embedding = new float[state * vocab];
            embedding[EOT * state] = eot;
            embedding[WORD * state] = word;
            weight(out, "decoder.token_embedding.weight", embedding, state, vocab);
            tensor(out, "decoder.ln.weight", null, state);
            float[] bias = new float[state];
            bias[0] = scale;
            tensor(out, "decoder.ln.bias", bias, state);
            for (int l = 0; l < layers; l++) block(out, "decoder.blocks." + l + ".", state, true);
        }
        return file;
    }

    private static void block(DataOutputStream out, String p, int s, boolean cross) throws IOException {
        tensor(out, p + "mlp_ln.weight", null, s);
        tensor(out, p + "mlp_ln.bias", null, s);
        weight(out, p + "mlp.0.weight", null, s, 4 * s);
        tensor(out, p + "mlp.0.bias", null, 4 * s);
        weight(out, p + "mlp.2.weight", null, 4 * s, s);
        tensor(out, p + "mlp.2.bias", null, s);
        attention(out, p + "attn", s);
        if (cross) attention(out, p + "cross_attn", s);
    }

    private static void attention(DataOutputStream out, String p, int s) throws IOException {
        tensor(out, p + "_ln.weight", null, s);
        tensor(out, p + "_ln.bias", null, s);
        weight(out, p + ".query.weight", null, s, s);
        tensor(out, p + ".query.bias", null, s);
        weight(out, p + ".key.weight", null, s, s);
        weight(out, p + ".value.weight", null, s, s);
        tensor(out, p + ".value.bias", null, s);
        weight(out, p + ".out.weight", null, s, s);
        tensor(out, p + ".out.bias", null, s);
    }

    private static void tensor(DataOutputStream out, String name, float[] data, int... ne) throws IOException {
        write(out, name, data, false, ne);
    }

    // The weights whisper.cpp keeps in the model's ftype (here F16); biases, norms and
    // positions stay F32.
    private static void weight(DataOutputStream out, String name, float[] data, int... ne) throws IOException {
        write(out, name, data, true, ne);
    }

    private static void write(DataOutputStream out, String name, float[] data, boolean half, int... ne) throws IOException {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        le(out, ne.length);
        le(out, bytes.length);
        le(out, half ? 1 : 0);
        int n = 1;
        for (int d : ne) {
            le(out, d);
            n *= d;
        }
        out.write(bytes);
        ByteBuffer buffer = ByteBuffer.allocate((half ? 2 : 4) * n).order(ByteOrder.LITTLE_ENDIAN);
        if (data != null) {
            for (int i = 0; i < data.length; i++) {
                if (half) buffer.putShort(2 * i, half(data[i]));
                else buffer.putFloat(4 * i, data[i]);
            }
        }
        out.write(buffer.array());
    }

    // IEEE half precision for the few exact values the test model uses (JDK 17 has no
    // Float.floatToFloat16). Zero, and normal numbers whose mantissa fits 10 bits.
    private static short half(float v) {
        if (v == 0f) return 0;
        int bits = Float.floatToRawIntBits(v);
        int sign = (bits >>> 16) & 0x8000;
        int exponent = ((bits >>> 23) & 0xff) - 127 + 15;
        int mantissa = bits & 0x7fffff;
        if (exponent <= 0 || exponent >= 31 || (mantissa & 0x1fff) != 0) throw new IllegalArgumentException("not exact in F16: " + v);
        return (short) (sign | (exponent << 10) | (mantissa >>> 13));
    }

    private static void le(DataOutputStream out, int v) throws IOException {
        out.writeInt(Integer.reverseBytes(v));
    }

    private static void le(DataOutputStream out, float v) throws IOException {
        le(out, Float.floatToRawIntBits(v));
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
