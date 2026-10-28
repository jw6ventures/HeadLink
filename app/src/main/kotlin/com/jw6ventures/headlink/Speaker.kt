package com.jw6ventures.headlink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * An installed text-to-speech engine, as the phone's status line and the report name it
 * (docs/audio-and-speech.md, "The engine"). [internet] is whether that package holds `INTERNET`: the app cannot stop
 * an engine using the network, but it can say that one could.
 */
data class SpeechEngine(val packageName: String, val label: String, val internet: Boolean)

/**
 * The engines on the phone, found without binding one: the `TTS_SERVICE` services this
 * app can see through its `<queries>` entry. Without that entry the list is empty on
 * targetSdk ≥ 30, whatever is installed.
 */
object SpeechEngines {
    /** Android's text-to-speech settings, where the engine, voice and rate are chosen. */
    const val SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"

    fun installed(context: Context): List<SpeechEngine> {
        val pm = context.packageManager
        val services = try {
            pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.ResolveInfoFlags.of(0))
        } catch (e: RuntimeException) {
            emptyList()
        }
        return services.mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            SpeechEngine(
                packageName = service.packageName,
                label = info.loadLabel(pm).toString(),
                internet = pm.checkPermission(Manifest.permission.INTERNET, service.packageName) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }.distinctBy { it.packageName }
    }

    /**
     * The engine `TextToSpeech` binds with no engine named: the system default when it is
     * installed, else the first system engine, else the first. The app never picks one;
     * this only predicts which the platform will use, for Home.
     */
    fun default(context: Context): SpeechEngine? {
        val all = installed(context)
        val chosen = Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        return all.firstOrNull { it.packageName == chosen }
            ?: all.firstOrNull { isSystem(context, it.packageName) }
            ?: all.firstOrNull()
    }

    private fun isSystem(context: Context, packageName: String): Boolean =
        try {
            context.packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
}

/**
 * The listener side of reading aloud: one notification's words, fetched by key at
 * the moment it is tapped, or null when it is gone from the phone. Set by
 * [MediaAccessService] while it is connected.
 */
fun interface NotificationTextSource {
    fun words(key: String): NotificationWords?
}

/**
 * The single owner of spoken output (docs/audio-and-speech.md, "`Speaker`: the one owner of spoken output"): `TextToSpeech`, audio focus, the
 * truck-only gate and the silence lead-in. Every decision is [SpeechQueue]'s, which is
 * host-tested; this object only carries them out on Android.
 *
 * **Lifetime.** [bind] when projection starts, [release] when it ends. P8.3's Play a
 * sample binds outside a session when nothing is bound, and then releases as soon as the
 * sample has ended, been skipped, or waited [SpeechQueue.SAMPLE_INIT_TIMEOUT_MS] for an
 * engine that never started. While it holds the engine it speaks only the sample, so
 * guidance with no projection stays silent as before. A projection starting meanwhile
 * stops the sample and binds afresh, so the session's counts start at zero.
 *
 * **Threads.** Main thread only. The engine's callbacks arrive on its binder thread and
 * are posted here.
 *
 * **Privacy.** Words go from [ReadAloudText] or a [GuidePrompt] straight to the engine.
 * They are not kept, logged or reported; the event log says "notification read aloud" or
 * "voice prompt (stage)" and nothing more.
 */
object Speaker {
    private val main = Handler(Looper.getMainLooper())
    private var queue = SpeechQueue()
    private var tts: TextToSpeech? = null
    private var appContext: Context? = null
    private var audio: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Bumped on every bind and release, so a late init callback cannot revive a released engine. */
    private var generation = 0

    /** The engine initialised with an English voice, now. */
    private var ready = false

    /** Whether [ready] was ever true while bound this session: what the report says. */
    private var readySeen = false
    private var engine: SpeechEngine? = null

    /** The **While speaking** setting in force. */
    private var media = SpeechMedia.DEFAULT

    /** Why the bound engine cannot speak, once its init has said so; null while starting or ready. */
    private var unavailable: SampleOutcome? = null

    // P8.3: Play a sample. At most one at a time; the phone's button waits for [sampleDone].
    private var sampleText: String? = null
    private var sampleDone: ((SampleOutcome) -> Unit)? = null
    private var sampleId: String? = null

    /** The engine was bound for the sample alone, outside a projection, and goes with it. */
    private var sampleOwnsEngine = false
    private val sampleTimeout = Runnable { if (sampleId == null) sampleOver(SampleOutcome.ENGINE_TIMEOUT) }

    /**
     * P10: the words of the alerts waiting their turn ([SpeechQueue.waitingAlerts]), by
     * key. Each is held only until it is said or dropped; never logged.
     */
    private val waitingText = HashMap<String, String>()

    /** Set by [MediaAccessService]; null while the listener is not connected. */
    @Volatile
    var textSource: NotificationTextSource? = null

    /** Told the key being read, or null, so its row stays lit. Main thread. */
    var readingListener: ((String?) -> Unit)? = null

    /**
     * Lab only: the emulator has no A2DP output, so the debug lab intent sets this and
     * the Bluetooth gate passes on any output. Never set on a release build.
     * The report says `speech_gate=lab` while it is on.
     */
    @Volatile
    var labBluetooth = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Called on [main]: the request names it as the handler.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            val speaking = queue.speaking != null
            if (queue.focusLost(now())) abandonFocus()
            if (speaking) {
                tts?.stop()
                readingChanged()
            }
        }
    }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            // The race: an utterance under way when A2DP goes would finish on the phone's
            // speaker, so it is stopped here the moment the output is reported gone.
            if (removedDevices?.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } != true) return
            if (bluetooth()) return
            if (queue.interrupt(SpeechEnd.BLUETOOTH_LOST, now())) {
                tts?.stop()
                lingerThenAbandon()
                // Weather alerts waiting their turn meet the gate now, and are counted.
                releaseWaiting()
                readingChanged()
            }
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            post(utteranceId, SpeechEnd.COMPLETED)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // The app's own stops and flushes are already counted, as the queue moved on
            // before the engine said so; this counts only a stop from elsewhere.
            post(utteranceId, SpeechEnd.STOPPED)
        }

        @Deprecated("Deprecated in the platform; the two-argument form below is the one called")
        override fun onError(utteranceId: String?) {
            post(utteranceId, SpeechEnd.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            post(utteranceId, SpeechEnd.ERROR)
        }

        private fun post(id: String?, end: SpeechEnd) {
            if (id == null) return
            main.post { finished(id, end) }
        }
    }

    private val abandonCheck = Runnable { if (queue.abandonDue(now())) abandonFocus() }

    /**
     * Binds the system's default engine and resets this session's counts. Idempotent
     * while bound, apart from taking [settings]' choices again. Init is asynchronous;
     * anything asked before it completes is dropped as `no_engine`, not queued.
     */
    fun bind(context: Context, settings: SavedSettings) {
        // A projection starting while a sample holds the engine: the sample stops, and the
        // session binds its own, so none of the sample reaches the session's counts.
        if (sampleOwnsEngine) release()
        media = settings.speechMedia
        queue.readAloud = settings.readAloud
        queue.guidanceOn = settings.voice.enabled
        if (tts != null) return
        val app = context.applicationContext
        appContext = app
        queue = SpeechQueue().also {
            it.readAloud = settings.readAloud
            // P8.2: the saved toggle, so a route started muted stays muted (decision 6).
            it.guidanceOn = settings.voice.enabled
            // P8.3: posted, since it is called from inside whichever queue call ended it.
            it.sampleEnded = { end -> main.post { sampleOver(SampleOutcome.of(end)) } }
        }
        ready = false
        unavailable = null
        readySeen = false
        engine = SpeechEngines.default(app)
        val bound = ++generation
        audio = app.getSystemService(AudioManager::class.java)?.also { it.registerAudioDeviceCallback(devices, main) }
        tts = TextToSpeech(app) { status -> main.post { initialised(bound, status) } }.also {
            it.setOnUtteranceProgressListener(progress)
        }
    }

    /** Stops speaking, gives focus back at once, and shuts the engine down. */
    fun release() {
        generation++
        sampleOwnsEngine = false
        main.removeCallbacks(abandonCheck)
        if (queue.end(now())) abandonFocus()
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
        ready = false
        waitingText.clear()
        audio?.unregisterAudioDeviceCallback(devices)
        audio = null
        readingChanged()
        // A sample still waiting for the engine it was bound with goes with it. One being
        // said was ended by queue.end above, and is told so by the post that queued.
        if (sampleId == null) sampleOver(SampleOutcome.STOPPED)
    }

    /**
     * A notification row tapped on the dashboard. The full text is fetched from the
     * listener only once the queue has decided to speak, so a skipped or stopped read
     * reads nothing from the phone.
     */
    fun read(notification: DashNotification) {
        if (sampleOwnsEngine) return
        when (val action = queue.read(notification.key, now(), conditions())) {
            is SpeechAction.Stop -> {
                tts?.stop()
                lingerThenAbandon()
                SessionController.note("notification read aloud stopped")
            }
            is SpeechAction.Skip -> SessionController.note("notification read aloud skipped (${action.reason.key})")
            is SpeechAction.Speak -> {
                val words = try {
                    textSource?.words(notification.key)
                } catch (e: RuntimeException) {
                    null
                }
                val composed = ReadAloudText.compose(notification, words)
                queue.textFetched(full = words != null, cut = composed.truncated)
                if (say(action, composed.text)) SessionController.note("notification read aloud")
            }
            // A read never waits: only a weather alert does.
            is SpeechAction.Wait -> Unit
        }
        readingChanged()
    }

    /**
     * A turn prompt, from [Guide]. It cuts off whatever is being said. The note names
     * the stage, never the words.
     */
    fun guide(prompt: GuidePrompt) {
        if (sampleOwnsEngine) return
        when (val action = queue.guide(prompt.stage, now(), conditions())) {
            is SpeechAction.Speak -> if (say(action, prompt.text)) SessionController.note("voice prompt (${prompt.stage})")
            else -> Unit
        }
        readingChanged()
    }

    /**
     * P11.5a: what search ahead found, said as a turn prompt is, through the same toggle,
     * call rule and gate, and counted under its own stage. It never cuts a turn prompt off:
     * while one is being said it waits, up to [ANNOUNCE_WAIT_MS], and is then said anyway,
     * since the driver asked for it. The note names the stage, never the words.
     */
    fun announce(prompt: GuidePrompt) {
        announceAfter(prompt, 0L)
    }

    private fun announceAfter(prompt: GuidePrompt, waitedMs: Long) {
        if (queue.speaking == SpeechKind.GUIDANCE && waitedMs < ANNOUNCE_WAIT_MS) {
            main.postDelayed({ announceAfter(prompt, waitedMs + ANNOUNCE_RETRY_MS) }, ANNOUNCE_RETRY_MS)
            return
        }
        guide(prompt)
    }

    /** How long an announcement waits for a turn prompt to finish, and how often it looks. */
    private const val ANNOUNCE_WAIT_MS = 10_000L
    private const val ANNOUNCE_RETRY_MS = 250L

    /**
     * P10: a weather alert newly in force, from [WeatherFeed] (docs/audio-and-speech.md, "Other things spoken"):
     * "Weather alert. " and its headline, through the gate and the voice toggle. One that
     * must wait its turn, behind a turn prompt or another alert, is held here and said
     * when its turn comes, in the order asked. The note says what happened, never the
     * headline.
     */
    fun alert(alert: WeatherAlert) {
        if (sampleOwnsEngine || tts == null) return
        val text = WeatherText.spoken(alert)
        when (val action = queue.alert(alert.id, now(), conditions())) {
            is SpeechAction.Speak -> if (say(action, text)) SessionController.note("weather alert spoken")
            is SpeechAction.Skip -> SessionController.note("weather alert not spoken (${action.reason.key})")
            is SpeechAction.Wait -> {
                waitingText[action.key] = text
                SessionController.note("weather alert waiting its turn")
                // Nothing may be being said any more (an earlier release was refused focus),
                // in which case the line goes now rather than at the next utterance's end.
                releaseWaiting()
            }
            is SpeechAction.Stop -> Unit
        }
        readingChanged()
    }

    /**
     * P10: the next alert waiting its turn, once no prompt or alert is being said. One that
     * is skipped (the toggle, a call or the gate changed while it waited) is counted and the
     * next is tried, so the line stops only at one being said or at its end.
     */
    private fun releaseWaiting() {
        while (true) {
            val key = queue.waitingAlerts.firstOrNull() ?: break
            val action = queue.waiting(now(), conditions()) ?: break
            val text = waitingText.remove(key)
            when (action) {
                is SpeechAction.Speak -> if (text != null && say(action, text)) SessionController.note("weather alert spoken")
                is SpeechAction.Skip -> SessionController.note("weather alert not spoken (${action.reason.key})")
                else -> Unit
            }
        }
        if (queue.waitingAlerts.isEmpty()) waitingText.clear()
    }

    /**
     * Stops a turn prompt being spoken, for the toggle going off, and since P10 a
     * weather alert, which the toggle also governs. A notification being read is left alone.
     */
    fun stopGuidance() {
        if (queue.speaking != SpeechKind.GUIDANCE && queue.speaking != SpeechKind.ALERT) return
        if (queue.interrupt(SpeechEnd.STOPPED, now())) {
            tts?.stop()
            lingerThenAbandon()
            releaseWaiting()
        }
    }

    /** The trip panel's toggle, as saved; prompts with it off are counted `toggle_off`. */
    fun guidanceOn(on: Boolean) {
        queue.guidanceOn = on
        if (!on) stopGuidance()
    }

    /** Whether prompts are let through: the toggle's state in the bound queue. */
    val guidanceEnabled: Boolean
        get() = queue.guidanceOn

    /** Whether speech can happen now, for the toggle's warning mark: an engine, and an A2DP output. */
    fun canSpeak(): Boolean = ready && bluetooth()

    /**
     * **Play a sample** (P8.3): [text] through the path a turn prompt takes —
     * [SpeechQueue.sample], then the same focus, lead-in and flush as [guide] — and [done]
     * told on the main thread how it came out, once. Main thread.
     *
     * With nothing bound, binds the default engine with [settings] (the While speaking
     * choice among them) and releases it when the sample is over. Init is asynchronous and
     * P8.1 drops a prompt that arrives before it; a sample instead waits for it, up to
     * [SpeechQueue.SAMPLE_INIT_TIMEOUT_MS]. In a projection it uses the session's engine,
     * waiting the same way if it is still starting, and is counted as `voice_samples_*`.
     *
     * Returns false, and says nothing, while an earlier sample is still pending.
     */
    fun sample(context: Context, settings: SavedSettings, text: String, done: (SampleOutcome) -> Unit): Boolean {
        if (sampleDone != null) return false
        if (tts == null) {
            // Nothing to wait for: TextToSpeech would bind nothing and report an error.
            if (SpeechEngines.default(context) == null) {
                SessionController.note("voice sample skipped (no_engine)")
                done(SampleOutcome.NO_ENGINE)
                return true
            }
            bind(context, settings)
            sampleOwnsEngine = true
        }
        sampleText = text
        sampleDone = done
        when {
            ready -> speakSample()
            unavailable != null -> sampleOver(unavailable ?: SampleOutcome.NO_ENGINE)
            else -> main.postDelayed(sampleTimeout, SpeechQueue.SAMPLE_INIT_TIMEOUT_MS)
        }
        return true
    }

    /** The engine is ready and a sample is waiting: the gate, then the words. */
    private fun speakSample() {
        val text = sampleText ?: return
        main.removeCallbacks(sampleTimeout)
        when (val action = queue.sample(now(), conditions())) {
            is SpeechAction.Speak -> {
                sampleId = action.id
                if (say(action, text)) {
                    SessionController.note("voice sample spoken")
                } else {
                    // Refused focus, or the engine refused the words. The second has already
                    // posted ERROR through sampleEnded, which runs first and wins.
                    main.post { sampleOver(SampleOutcome.NO_FOCUS) }
                }
            }
            is SpeechAction.Skip -> sampleOver(SampleOutcome.of(action.reason))
            is SpeechAction.Stop, is SpeechAction.Wait -> Unit
        }
        readingChanged()
    }

    /**
     * The sample is over, however it went: [done] is told once, and an engine bound for it
     * alone is released. Safe to call when no sample is pending.
     */
    private fun sampleOver(outcome: SampleOutcome) {
        val done = sampleDone ?: return
        main.removeCallbacks(sampleTimeout)
        sampleDone = null
        sampleText = null
        sampleId = null
        if (outcome != SampleOutcome.SPOKEN) SessionController.note("voice sample ${outcome.key}")
        if (sampleOwnsEngine) release()
        done(outcome)
    }

    /**
     * `key=value` lines for the session report: the engine's package, whether it can
     * reach the network and became ready, the setting, the gate, and the queue's counts.
     * Never what was said.
     */
    fun report(): String =
        "speech_engine=${engine?.packageName ?: "none"} speech_engine_internet=${engine?.internet ?: false} " +
            "speech_ready=$readySeen speech_media=${media.key} speech_read_aloud=${queue.readAloud} " +
            "speech_gate=${if (labBluetooth) "lab" else "bluetooth"}\n" + queue.report()

    /** Carries out [action]: focus, lead-in, words. Returns whether the engine took them. */
    private fun say(action: SpeechAction.Speak, text: String): Boolean {
        val engine = tts ?: return false
        val attributes = attributes(action.kind)
        if (action.requestFocus && !requestFocus(attributes)) {
            queue.refused(action)
            return false
        }
        main.removeCallbacks(abandonCheck)
        engine.setAudioAttributes(attributes)
        val now = now()
        var mode = TextToSpeech.QUEUE_FLUSH
        if (action.leadInMs > 0) {
            engine.playSilentUtterance(action.leadInMs, TextToSpeech.QUEUE_FLUSH, "${action.id}-lead")
            mode = TextToSpeech.QUEUE_ADD
        }
        queue.began(action, now)
        if (engine.speak(text, mode, null, action.id) != TextToSpeech.SUCCESS) {
            finished(action.id, SpeechEnd.ERROR)
            return false
        }
        return true
    }

    private fun finished(id: String, end: SpeechEnd) {
        if (!queue.finished(id, end, now())) return
        lingerThenAbandon()
        // P10: the next weather alert waiting its turn goes now, before the next prompt.
        releaseWaiting()
        readingChanged()
    }

    /** Gives focus back [SpeechQueue.FOCUS_LINGER_MS] after the last utterance, unless another starts. */
    private fun lingerThenAbandon() {
        main.removeCallbacks(abandonCheck)
        main.postDelayed(abandonCheck, SpeechQueue.FOCUS_LINGER_MS)
    }

    private fun initialised(bound: Int, status: Int) {
        val engine = tts
        if (bound != generation || engine == null) return
        if (status != TextToSpeech.SUCCESS) {
            SessionController.note("speech engine unavailable")
            unavailable = SampleOutcome.NO_ENGINE
            sampleOver(SampleOutcome.NO_ENGINE)
            return
        }
        // The engine actually bound, which the prediction in bind may have missed.
        val name = engine.defaultEngine
        if (name != null && this.engine?.packageName != name) {
            appContext?.let { context -> SpeechEngines.installed(context).firstOrNull { it.packageName == name } }
                ?.let { this.engine = it }
        }
        // English only, the device's variant where it is English.
        val locale = Locale.getDefault().takeIf { it.language == Locale.ENGLISH.language } ?: Locale.US
        val language = try {
            engine.setLanguage(locale)
        } catch (e: RuntimeException) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        ready = language >= TextToSpeech.LANG_AVAILABLE
        readySeen = readySeen || ready
        SessionController.note(if (ready) "speech engine ready" else "speech engine has no English voice")
        if (!ready) unavailable = SampleOutcome.NO_ENGLISH
        // P8.3: a sample waiting for this engine goes now, or is told why it cannot.
        if (sampleDone != null && sampleId == null) {
            if (ready) speakSample() else sampleOver(SampleOutcome.NO_ENGLISH)
        }
    }

    /**
     * Asks for focus with the **While speaking** setting's kind: transient for Pause
     * media, may-duck for Lower volume. Always with [focusListener], so a second request
     * while one is held replaces it rather than stacking a second grant.
     */
    private fun requestFocus(attributes: AudioAttributes): Boolean {
        val manager = audio ?: return false
        val gain = when (media) {
            SpeechMedia.PAUSE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            SpeechMedia.DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        focusRequest = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        audio?.abandonAudioFocusRequest(request)
        focusRequest = null
    }

    /**
     * Both kinds speak as navigation guidance. An earlier design gave a read notification
     * `USAGE_ASSISTANT`, but that plays on the assistant stream, whose own volume sits far
     * below media's and which the rocker doesn't reach unless an assistant is active: on
     * the truck a read notification was barely audible while turn prompts were fine.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun attributes(kind: SpeechKind): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun conditions() = SpeechConditions(engineReady = ready && tts != null, bluetooth = bluetooth(), inCall = inCall())

    /** The truck-only gate (decision 4): an A2DP output now, checked per utterance, not cached. */
    private fun bluetooth(): Boolean {
        if (labBluetooth) return true
        val manager = audio ?: return false
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }

    /**
     * Whether a call is on, for [SpeechQueue.guide]'s call rule. P7.9's call state first;
     * it is only fed while Call controls are on, so the audio mode, which needs no
     * permission, covers a call with them off.
     */
    private fun inCall(): Boolean {
        val phase = CallControl.state.phase
        if (phase != CallPhase.NONE && phase != CallPhase.ENDED) return true
        return when (audio?.mode) {
            AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_CALL_SCREENING -> true
            else -> false
        }
    }

    private fun readingChanged() {
        readingListener?.invoke(queue.reading)
    }

    private fun now(): Long = SystemClock.elapsedRealtime()
}
