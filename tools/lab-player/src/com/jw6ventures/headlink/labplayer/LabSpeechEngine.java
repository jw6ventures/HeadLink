package com.jw6ventures.headlink.labplayer;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.util.Log;

/**
 * A stand-in text-to-speech engine for the emulator lab's speech script (P8.1), which has
 * no engine of its own: it speaks silence, 25 ms for each character it is given, in 100 ms
 * buffers, and stops when told. That is enough for the app's whole TextToSpeech path to
 * run - bind, language, focus, speak, a second tap's stop, onDone - with nothing to hear.
 *
 * Disabled in the manifest, so no other lab run has an engine; the speech script enables
 * it with pm unless the operator's eSpeak NG is in the cache. It logs how many characters
 * each utterance had, never the words, which is how the lab sees a read was cut to about
 * 400 characters.
 */
public final class LabSpeechEngine extends TextToSpeechService {
    private static final int RATE = 16000;
    private static final int MS_PER_CHARACTER = 25;
    private volatile boolean stopped;

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return "eng".equals(lang) || "en".equals(lang) ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[] {"eng", "USA", ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }

    @Override
    protected void onStop() {
        stopped = true;
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopped = false;
        CharSequence text = request.getCharSequenceText();
        int characters = text == null ? 0 : text.length();
        Log.i(PlayerService.TAG, "speech " + characters + " characters");
        if (callback.start(RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return;
        byte[] silence = new byte[RATE / 10 * 2];
        long buffers = (long) characters * MS_PER_CHARACTER / 100;
        for (long i = 0; i < buffers && !stopped; i++) {
            if (callback.audioAvailable(silence, 0, silence.length) != TextToSpeech.SUCCESS) break;
        }
        if (stopped) {
            Log.i(PlayerService.TAG, "speech stopped");
            return;
        }
        callback.done();
        Log.i(PlayerService.TAG, "speech synthesized");
    }
}
