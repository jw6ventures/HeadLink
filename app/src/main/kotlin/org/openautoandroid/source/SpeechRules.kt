package org.openautoandroid.source

/**
 * What the phone's media does while it speaks: the **While speaking** setting (P8 plan
 * decision 10, §4, §7), one choice shared by notification reading and turn prompts.
 *
 * [PAUSE] asks for transient focus, which well-behaved players answer by pausing and
 * then resuming by themselves; [DUCK] asks for may-duck focus, which the system enforces
 * by lowering the player. Stored by [key], never by position.
 */
enum class SpeechMedia(val key: String, val label: String) {
    PAUSE("pause", "Pause media"),
    DUCK("duck", "Lower volume"),
    ;

    companion object {
        val DEFAULT = PAUSE

        /** The choices in the order the settings screen offers them; the first is the default. */
        val LABELS: List<String> = entries.map { it.label }

        /** The choice a stored [key] names, or [DEFAULT] for none or one this build does not know. */
        fun of(key: String?): SpeechMedia = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** The two things the phone says (P8 plan §0), each with its report prefix. */
enum class SpeechKind(val prefix: String) {
    /** A dashboard notification row, tapped (§8). */
    NOTIFICATION("notifications_read"),

    /** A turn prompt from the followed route (§5), spoken through [Speaker.guide]. */
    GUIDANCE("voice_prompts"),
}

/**
 * Why something was not said. Each is counted under the kind it applies to (§9); the
 * [kinds] keep the report free of reasons that can never happen to that kind.
 */
enum class SpeechSkip(val key: String, val kinds: Set<SpeechKind>) {
    /** "Read aloud when tapped" is off. */
    OFF("off", setOf(SpeechKind.NOTIFICATION)),

    /** The trip panel's voice toggle is off (wired by P8.2). */
    TOGGLE_OFF("toggle_off", setOf(SpeechKind.GUIDANCE)),

    /** A phone call is ringing or connected (plan §14.1; see [SpeechQueue.guide]). */
    IN_CALL("in_call", setOf(SpeechKind.GUIDANCE)),

    /** A notification tapped while a turn prompt plays is dropped, not queued (decision 7). */
    GUIDANCE_PLAYING("guidance_playing", setOf(SpeechKind.NOTIFICATION)),

    /** No engine, one still initialising, or one without an English voice (§3). */
    NO_ENGINE("no_engine", SpeechKind.entries.toSet()),

    /** No A2DP output at that moment: truck-only (decision 4). */
    NO_BLUETOOTH("no_bluetooth", SpeechKind.entries.toSet()),

    /** Android refused the audio focus request, as it does during a call. */
    NO_FOCUS("no_focus", SpeechKind.entries.toSet()),
}

/** How an utterance that started came to an end; counted per kind (§9). */
enum class SpeechEnd(val key: String) {
    COMPLETED("completed"),

    /** A second tap on the notification being read (§8). */
    STOPPED_BY_TAP("stopped_by_tap"),

    /** A tap on a different notification: the newest tap wins (§4). */
    REPLACED("replaced"),

    /** Cut off by a turn prompt (decision 7), or a prompt by a newer prompt. */
    INTERRUPTED_BY_GUIDANCE("interrupted_by_guidance"),

    /** The A2DP output went away mid-utterance (§4's race). */
    BLUETOOTH_LOST("bluetooth_lost"),

    /** Another app took audio focus away. */
    FOCUS_LOST("focus_lost"),

    /** Stopped by the app for any other reason: the engine's own stop, or P8.2's toggle. */
    STOPPED("stopped"),
    ERROR("error"),

    /** Projection ended while it was being said. */
    SESSION_END("session_end"),
}

/**
 * A turn prompt to say: the words, and the stage it is counted and logged under ("early",
 * "prepare", "at_turn", "reroute", "arrival" — whatever P8.2's `VoicePrompts` names).
 * The text is handed to the engine only; the stage alone reaches the report.
 */
data class GuidePrompt(val text: String, val stage: String)

/**
 * What pressing **Play a sample** came to (P8.3, plan §7), for the phone to say on screen.
 * Each but [SPOKEN] is a reason nothing, or not all of it, was heard.
 */
enum class SampleOutcome(val key: String) {
    /** The engine said all of it, over an A2DP output (or the lab's override). */
    SPOKEN("spoken"),

    /** No engine is installed, or the one there failed to start. */
    NO_ENGINE("no_engine"),

    /** The engine started without an English voice (§3). */
    NO_ENGLISH("no_english"),

    /** The engine had not started within [SpeechQueue.SAMPLE_INIT_TIMEOUT_MS]. */
    ENGINE_TIMEOUT("engine_timeout"),

    /** No A2DP output: the truck-only gate (decision 4). */
    NO_BLUETOOTH("no_bluetooth"),

    /** A call is ringing or connected (§14.1). */
    IN_CALL("in_call"),

    /** Android refused audio focus. */
    NO_FOCUS("no_focus"),

    /** A turn prompt cut it off (decision 7). */
    INTERRUPTED("interrupted"),

    /** The A2DP output went away while it was being said. */
    BLUETOOTH_LOST("bluetooth_lost"),

    /** Another app took audio focus while it was being said. */
    FOCUS_LOST("focus_lost"),

    /** Stopped some other way: a projection starting or ending, or the engine's own stop. */
    STOPPED("stopped"),
    ERROR("error"),
    ;

    companion object {
        /** The skip a sample met at the gate, as an outcome. */
        fun of(skip: SpeechSkip): SampleOutcome = when (skip) {
            SpeechSkip.NO_ENGINE -> NO_ENGINE
            SpeechSkip.NO_BLUETOOTH -> NO_BLUETOOTH
            SpeechSkip.IN_CALL -> IN_CALL
            SpeechSkip.NO_FOCUS -> NO_FOCUS
            // A sample is not gated by Read aloud or the toggle, and is itself guidance.
            SpeechSkip.OFF, SpeechSkip.TOGGLE_OFF, SpeechSkip.GUIDANCE_PLAYING -> STOPPED
        }

        /** How a sample that started came to an end, as an outcome. */
        fun of(end: SpeechEnd): SampleOutcome = when (end) {
            SpeechEnd.COMPLETED -> SPOKEN
            SpeechEnd.INTERRUPTED_BY_GUIDANCE -> INTERRUPTED
            SpeechEnd.BLUETOOTH_LOST -> BLUETOOTH_LOST
            SpeechEnd.FOCUS_LOST -> FOCUS_LOST
            SpeechEnd.ERROR -> ERROR
            SpeechEnd.STOPPED_BY_TAP, SpeechEnd.REPLACED, SpeechEnd.STOPPED, SpeechEnd.SESSION_END -> STOPPED
        }
    }
}

/** What is true at the moment of a request, read fresh each time by [Speaker] (§4). */
data class SpeechConditions(
    /** The engine initialised with an English voice. */
    val engineReady: Boolean,
    /** An A2DP output is present now, or the lab's override is on. */
    val bluetooth: Boolean,
    /** A call is ringing or connected. */
    val inCall: Boolean,
)

/** [SpeechQueue]'s answer to a request, for [Speaker] to carry out. */
sealed interface SpeechAction {
    /**
     * Say it: ask for focus when [requestFocus] (no grant is held, or it is held for the
     * other kind's attributes), then [leadInMs] of silence when non-zero, then the words,
     * flushing whatever is being said. Confirm with [SpeechQueue.began] or
     * [SpeechQueue.refused].
     */
    data class Speak(
        val id: String,
        val kind: SpeechKind,
        val key: String?,
        val stage: String?,
        val leadInMs: Long,
        val requestFocus: Boolean,
        /** P8.3's Play a sample: a prompt in every way but how it is counted. */
        val sample: Boolean = false,
    ) : SpeechAction

    /** Stop the engine: the second tap on the notification being read. Already counted. */
    data class Stop(val id: String) : SpeechAction

    /** Say nothing. Already counted. */
    data class Skip(val reason: SpeechSkip) : SpeechAction
}

/**
 * P8.1's decisions about speaking, with no Android in them (plan §4, §8, §9): the gate,
 * the priority between notifications and turn prompts, when audio focus is asked for and
 * given back, the silence lead-in, and the counts. [Speaker] is the thin Android glue
 * around one of these; SpeechQueueTest drives it directly.
 *
 * It never sees what is said: requests carry a notification's key or a prompt's stage,
 * and the words stay in [Speaker] on their way to the engine.
 *
 * Not thread-safe: [Speaker] calls it on the main thread only. Times are a monotonic
 * clock in milliseconds.
 */
class SpeechQueue {
    private class Utterance(val id: String, val kind: SpeechKind, val key: String?, val sample: Boolean)

    private var current: Utterance? = null
    private var nextId = 0L

    /** When the last utterance ended, or null before the first. */
    private var lastEndMillis: Long? = null

    /** Whose attributes the focus grant was asked for with, or null while none is held. */
    private var focusFor: SpeechKind? = null

    /** "Read aloud when tapped" (§7); on by default. */
    var readAloud = true

    /**
     * The trip panel's voice toggle (P8.2): [Speaker.bind] sets the saved value, and each
     * press sets it again. While off, every prompt is counted `toggle_off`.
     */
    var guidanceOn = true

    private val requests = IntArray(SpeechKind.entries.size)
    private val started = IntArray(SpeechKind.entries.size)
    private val ends = Array(SpeechKind.entries.size) { IntArray(SpeechEnd.entries.size) }
    private val skips = Array(SpeechKind.entries.size) { IntArray(SpeechSkip.entries.size) }
    private val stages = LinkedHashMap<String, Int>()
    private var focusRequests = 0
    private var focusDenied = 0
    private var focusAbandons = 0
    private var leadIns = 0
    private var fullTexts = 0
    private var fallbackTexts = 0
    private var truncated = 0

    // P8.3: Play a sample, counted apart from the drive's prompts (see [sample]).
    private var sampleRequests = 0
    private var samplesStarted = 0
    private var samplesCompleted = 0
    private val sampleSkips = IntArray(SpeechSkip.entries.size)

    /**
     * Told how a sample that started ended, from inside whichever call ended it, so
     * [Speaker] can say so on the phone. Set by [Speaker]; it posts rather than acting here.
     */
    var sampleEnded: ((SpeechEnd) -> Unit)? = null

    /** The key of the notification being read, for its row to stay lit (§8); else null. */
    val reading: String?
        get() = current?.takeIf { it.kind == SpeechKind.NOTIFICATION }?.key

    /** What is being said, or null. */
    val speaking: SpeechKind?
        get() = current?.kind

    /** Whether a focus grant is held (being spoken under, or lingering). */
    val focusHeld: Boolean
        get() = focusFor != null

    /**
     * A notification row was tapped (§4, §8). In this order:
     *  * the one being read is tapped again: stop it;
     *  * reading is off: nothing;
     *  * a turn prompt is playing: dropped, not queued (decision 7);
     *  * the gate;
     *  * otherwise speak it, cutting off any other notification (the newest tap wins).
     */
    fun read(key: String, nowMillis: Long, conditions: SpeechConditions): SpeechAction {
        requests[SpeechKind.NOTIFICATION.ordinal]++
        val playing = current
        if (playing != null && playing.kind == SpeechKind.NOTIFICATION && playing.key == key) {
            ended(playing, SpeechEnd.STOPPED_BY_TAP, nowMillis)
            return SpeechAction.Stop(playing.id)
        }
        if (!readAloud) return skip(SpeechKind.NOTIFICATION, SpeechSkip.OFF)
        if (playing?.kind == SpeechKind.GUIDANCE) return skip(SpeechKind.NOTIFICATION, SpeechSkip.GUIDANCE_PLAYING)
        gate(conditions)?.let { return skip(SpeechKind.NOTIFICATION, it) }
        return speak(SpeechKind.NOTIFICATION, key, null, nowMillis)
    }

    /**
     * A turn prompt is due (§4, §5). It always wins: QUEUE_FLUSH cuts off whatever is
     * being said, a notification included, and that notification is not resumed.
     */
    fun guide(stage: String, nowMillis: Long, conditions: SpeechConditions): SpeechAction {
        requests[SpeechKind.GUIDANCE.ordinal]++
        if (!guidanceOn) return skip(SpeechKind.GUIDANCE, SpeechSkip.TOGGLE_OFF)
        // Plan §14.1, the plan's proposed answer, taken until James says otherwise: no turn
        // prompt during a call, ringing or connected. SYNC may drop A2DP for HFP, which
        // would lose it, or keep A2DP up, which would speak it over the call; either is
        // worse than silence. Counted as skipped_in_call. This is the one place that
        // decides it; [Speaker] only reports whether a call is on.
        if (conditions.inCall) return skip(SpeechKind.GUIDANCE, SpeechSkip.IN_CALL)
        gate(conditions)?.let { return skip(SpeechKind.GUIDANCE, it) }
        return speak(SpeechKind.GUIDANCE, null, stage, nowMillis)
    }

    /**
     * **Play a sample** (P8.3, plan §7): the path a turn prompt takes — the call rule, the
     * engine, the Bluetooth gate, then focus, lead-in and QUEUE_FLUSH, cutting off a
     * notification as a prompt does and cut off by the next prompt — with two
     * differences, both deliberate:
     *
     *  * **The toggle does not gate it.** The operator pressing Play a sample on the phone
     *    is asking whether the truck can hear the phone; a sample that stayed silent because
     *    guidance is muted would answer a different question, and look like a fault. The
     *    phone says the toggle is off beside the result instead.
     *  * **It is counted apart**, as `voice_samples_*`, never in `voice_prompts_*`: a
     *    sample played on the phone mid-drive is not a prompt the drive asked for, and a
     *    sample skipped for want of Bluetooth is not a prompt the driver missed. Focus
     *    requests, abandons and lead-ins are the audio system's and include it, so they
     *    still balance.
     */
    fun sample(nowMillis: Long, conditions: SpeechConditions): SpeechAction {
        sampleRequests++
        val reason = if (conditions.inCall) SpeechSkip.IN_CALL else gate(conditions)
        if (reason != null) {
            sampleSkips[reason.ordinal]++
            return SpeechAction.Skip(reason)
        }
        return speak(SpeechKind.GUIDANCE, null, null, nowMillis).copy(sample = true)
    }

    /**
     * [speak] was carried out: focus granted (or already held) and the engine told. What
     * was being said ends here, as replaced or interrupted, because QUEUE_FLUSH has cut it
     * off; the engine's own onStop for it arrives later and is ignored.
     */
    fun began(speak: SpeechAction.Speak, nowMillis: Long) {
        current?.let {
            ended(it, if (speak.kind == SpeechKind.GUIDANCE) SpeechEnd.INTERRUPTED_BY_GUIDANCE else SpeechEnd.REPLACED, nowMillis)
        }
        current = Utterance(speak.id, speak.kind, speak.key, speak.sample)
        if (speak.sample) {
            samplesStarted++
        } else {
            started[speak.kind.ordinal]++
            if (speak.stage != null) stages[speak.stage] = (stages[speak.stage] ?: 0) + 1
        }
        if (speak.requestFocus) focusRequests++
        if (speak.leadInMs > 0) leadIns++
        focusFor = speak.kind
    }

    /** Android refused focus for [speak]: nothing is said, and nothing being said is cut. */
    fun refused(speak: SpeechAction.Speak) {
        focusDenied++
        if (speak.sample) sampleSkips[SpeechSkip.NO_FOCUS.ordinal]++ else skip(speak.kind, SpeechSkip.NO_FOCUS)
    }

    /**
     * The engine finished [id] (done, stopped or failed). Returns whether it was the one
     * being said; if so the caller schedules [abandonDue] [FOCUS_LINGER_MS] from now.
     */
    fun finished(id: String, end: SpeechEnd, nowMillis: Long): Boolean {
        val playing = current ?: return false
        if (playing.id != id) return false
        ended(playing, end, nowMillis)
        return true
    }

    /**
     * Stops whatever is being said for [end] — the A2DP output went, focus was lost, the
     * toggle went off. Returns whether anything was.
     */
    fun interrupt(end: SpeechEnd, nowMillis: Long): Boolean {
        val playing = current ?: return false
        ended(playing, end, nowMillis)
        return true
    }

    /**
     * Another app took focus away for good or for a while: what is being said stops, and
     * the grant is no longer ours. Returns whether a grant was held, to be given back
     * cleanly.
     */
    fun focusLost(nowMillis: Long): Boolean {
        interrupt(SpeechEnd.FOCUS_LOST, nowMillis)
        return release()
    }

    /**
     * Whether the grant should be given back now: nothing is being said and nothing has
     * been for [FOCUS_LINGER_MS]. Utterances closer together than that share one grant
     * (§4), so a player is not restarted and stopped again between "Rerouting" and the
     * next prompt. Counts the abandon when it says yes.
     */
    fun abandonDue(nowMillis: Long): Boolean {
        if (current != null || focusFor == null) return false
        val last = lastEndMillis ?: return false
        if (nowMillis - last < FOCUS_LINGER_MS) return false
        return release()
    }

    /**
     * Projection ended: whatever is being said is stopped and counted, and a held grant
     * must be given back at once. Returns whether one was held.
     */
    fun end(nowMillis: Long): Boolean {
        interrupt(SpeechEnd.SESSION_END, nowMillis)
        return release()
    }

    /** Where the words of a read came from (§8): counted, never kept. */
    fun textFetched(full: Boolean, cut: Boolean) {
        if (full) fullTexts++ else fallbackTexts++
        if (cut) truncated++
    }

    /**
     * `key=value` lines: counts, stages and settings only, never what was said, whose,
     * from which app, or where (spec §10, plan §9). The existing `notifications_read_aloud`
     * is now reads *started*; `notifications_read_taps` is every tap that reached here.
     */
    fun report(): String = buildString {
        val n = SpeechKind.NOTIFICATION.ordinal
        append("notifications_read_aloud=${started[n]} notifications_read_taps=${requests[n]}")
        append(" notifications_read_full_text=$fullTexts notifications_read_first_line=$fallbackTexts")
        append(" notifications_read_truncated=$truncated")
        appendOutcomes(SpeechKind.NOTIFICATION)
        append('\n')
        val g = SpeechKind.GUIDANCE.ordinal
        append("voice_prompts_requests=${requests[g]} voice_prompts_started=${started[g]}")
        append(" voice_prompts_toggle=${if (guidanceOn) "on" else "off"}")
        // P8.2: every stage VoicePrompts names, at 0 when none was spoken, so a drive's
        // report says "no early prompt" rather than leaving the key out; then any other.
        for (stage in PromptStage.entries) append(" voice_prompts_spoken_${stage.label}=${stages[stage.label] ?: 0}")
        for ((stage, count) in stages) {
            if (PromptStage.entries.none { it.label == stage }) append(" voice_prompts_spoken_$stage=$count")
        }
        appendOutcomes(SpeechKind.GUIDANCE)
        append('\n')
        // P8.3: samples on a line of their own, so no prompt count includes one.
        append("voice_samples_requests=$sampleRequests voice_samples_started=$samplesStarted")
        append(" voice_samples_completed=$samplesCompleted")
        for (reason in listOf(SpeechSkip.IN_CALL, SpeechSkip.NO_ENGINE, SpeechSkip.NO_BLUETOOTH, SpeechSkip.NO_FOCUS)) {
            append(" voice_samples_skipped_${reason.key}=${sampleSkips[reason.ordinal]}")
        }
        append('\n')
        append("speech_focus_requests=$focusRequests speech_focus_denied=$focusDenied ")
        append("speech_focus_abandons=$focusAbandons speech_focus_held=$focusHeld speech_lead_ins=$leadIns\n")
    }

    private fun StringBuilder.appendOutcomes(kind: SpeechKind) {
        for (end in SpeechEnd.entries) append(" ${kind.prefix}_${end.key}=${ends[kind.ordinal][end.ordinal]}")
        for (reason in SpeechSkip.entries) {
            if (kind in reason.kinds) append(" ${kind.prefix}_skipped_${reason.key}=${skips[kind.ordinal][reason.ordinal]}")
        }
    }

    /** The engine, then the truck-only gate, checked per utterance (§3, §4). */
    private fun gate(conditions: SpeechConditions): SpeechSkip? = when {
        !conditions.engineReady -> SpeechSkip.NO_ENGINE
        !conditions.bluetooth -> SpeechSkip.NO_BLUETOOTH
        else -> null
    }

    private fun speak(kind: SpeechKind, key: String?, stage: String?, nowMillis: Long): SpeechAction.Speak {
        // Only after silence: while something is being said, or has just been, the A2DP
        // stream is already running and a lead-in would only delay the prompt.
        val last = lastEndMillis
        val idle = current == null && (last == null || nowMillis - last >= LEAD_IN_AFTER_IDLE_MS)
        return SpeechAction.Speak(
            id = "oaa-${++nextId}",
            kind = kind,
            key = key,
            stage = stage,
            leadInMs = if (idle) LEAD_IN_MS else 0L,
            requestFocus = focusFor != kind,
        )
    }

    private fun skip(kind: SpeechKind, reason: SpeechSkip): SpeechAction.Skip {
        skips[kind.ordinal][reason.ordinal]++
        return SpeechAction.Skip(reason)
    }

    private fun ended(utterance: Utterance, end: SpeechEnd, nowMillis: Long) {
        if (utterance.sample) {
            if (end == SpeechEnd.COMPLETED) samplesCompleted++
            sampleEnded?.invoke(end)
        } else {
            ends[utterance.kind.ordinal][end.ordinal]++
        }
        if (current === utterance) current = null
        lastEndMillis = nowMillis
    }

    private fun release(): Boolean {
        if (focusFor == null) return false
        focusFor = null
        focusAbandons++
        return true
    }

    companion object {
        /**
         * Silence before an utterance that follows [LEAD_IN_AFTER_IDLE_MS] of quiet, so an
         * idle A2DP stream is running before the first syllable (§4). A starting figure;
         * the truck decides it. A constant, not a setting.
         */
        const val LEAD_IN_MS = 300L

        /** "More than a few seconds of silence" (§4). */
        const val LEAD_IN_AFTER_IDLE_MS = 3_000L

        /** How long a grant outlives the utterance it was asked for, for the next to share (§4). */
        const val FOCUS_LINGER_MS = 1_000L

        /**
         * How long Play a sample waits for an engine it had to start (P8.3). Init is
         * asynchronous and a prompt arriving before it is dropped (§3); a sample waits
         * instead, since the press is the operator asking now. The engines seen so far were
         * ready within about 1.5 s; a slow first start loading a voice may take longer.
         */
        const val SAMPLE_INIT_TIMEOUT_MS = 10_000L
    }
}

/**
 * What a tapped notification says, as the listener reads it at tap time and only then
 * (§8). Held for the length of one call to [ReadAloudText.compose], never stored.
 */
data class NotificationWords(
    /** `EXTRA_TITLE`, else `EXTRA_TITLE_BIG`. */
    val title: String?,
    /** `EXTRA_BIG_TEXT`. */
    val bigText: String?,
    /** The last `MessagingStyle` message's sender; null for the user's own. */
    val messageSender: String?,
    /** The last `MessagingStyle` message's text. */
    val messageText: String?,
    /** `EXTRA_TEXT`. */
    val text: String?,
)

/** The sentence a tapped notification becomes (decision 5, §8): sender and text, no app. */
object ReadAloudText {
    /** About 30 s of speech (§8). The sender is not counted; it is short. */
    const val MAX = 400
    const val TRUNCATED = "Message truncated."

    /** A read's words, and whether they were cut at [MAX]. */
    data class Composed(val text: String, val truncated: Boolean)

    /**
     * [words] as the listener has them now, or null when the notification is gone from
     * the phone; then the tile's own first line is read, because that is what the driver
     * tapped. The text is `EXTRA_BIG_TEXT`, else the last `MessagingStyle` message, else
     * `EXTRA_TEXT` (§8). Who is the message's sender when the text is the message's,
     * else the title; failing both, the row's title unless the row fell back to the
     * app's name, which is never said.
     */
    fun compose(row: DashNotification, words: NotificationWords?): Composed {
        val big = clean(words?.bigText)
        val message = clean(words?.messageText)
        val body = big ?: message ?: clean(words?.text) ?: clean(row.text)
        val sender = clean(words?.messageSender)
        val title = clean(words?.title)
        val rowWho = clean(row.title)?.takeIf { row.title != row.appLabel }
        val who = (if (big == null && message != null) sender ?: title else title ?: sender) ?: rowWho
        var truncated = false
        val what = body?.let {
            if (it.length <= MAX) it else {
                truncated = true
                cut(it) + ". " + TRUNCATED
            }
        }
        val sentence = when {
            who != null && what != null -> sentenceOf(who) + " " + what
            else -> who ?: what ?: ""
        }
        return Composed(sentence, truncated)
    }

    /** Whitespace, newlines included, collapsed to single spaces; null when blank. */
    private fun clean(value: String?): String? =
        value?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** At most [MAX] characters, ending at a word where one is near. */
    private fun cut(text: String): String {
        val head = text.take(MAX)
        val space = head.lastIndexOf(' ')
        val words = if (space >= MAX / 2) head.substring(0, space) else head
        return words.trimEnd(' ', ',', ';', ':', '.', '-', '…')
    }

    /** "Sarah" becomes "Sarah." so the engine pauses; "Sarah!" stays as it is. */
    private fun sentenceOf(who: String): String = if (who.last() in ".!?:") who else "$who."
}
