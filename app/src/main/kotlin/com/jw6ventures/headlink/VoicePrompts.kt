package com.jw6ventures.headlink

import kotlin.math.abs
import kotlin.math.max

/** Which prompt of a maneuver, or which route event, a spoken prompt is: for counting. */
enum class PromptStage(val label: String) {
    EARLY("early"),
    PREPARE("prepare"),
    AT_TURN("at_turn"),
    REROUTE("reroute"),
    ARRIVAL("arrival"),
}

/** The **Timing** setting (P8 plan §7): a factor on every lead time, never on a minimum. */
enum class PromptTiming(val label: String, val factor: Double) {
    EARLIER("earlier", 1.3),
    NORMAL("normal", 1.0),
    LATER("later", 0.75),
}

/** The **Detail** setting: Full adds the chained "then …" and "keep right" before an exit. */
enum class PromptDetail(val label: String) {
    BRIEF("brief"),
    FULL("full"),
}

/**
 * Voice guidance's settings (P8 plan §7), with its defaults. Pure, so [VoicePrompts] and its
 * host tests need nothing saved; where they are saved is `AppSettings`' business.
 *
 * [detail] defaults to [PromptDetail.FULL]: the plan gives no default for it, and P8.2's
 * drive is meant to hear every §5.2 rule, the close-turn chaining among them.
 */
data class VoiceSettings(
    /** The trip panel's toggle and the phone's Voice guidance switch: one saved value. */
    val enabled: Boolean = true,
    val early: Boolean = true,
    val prepare: Boolean = true,
    val atTurn: Boolean = true,
    val timing: PromptTiming = PromptTiming.NORMAL,
    val detail: PromptDetail = PromptDetail.FULL,
    val streetNames: Boolean = true,
    val announceReroute: Boolean = true,
    val announceArrival: Boolean = true,
)

/** One thing to say, and which [stage] it counts as. Never reported: the text names roads. */
data class VoicePrompt(val text: String, val stage: PromptStage)

/**
 * When turn prompts are spoken, and what they say (P8 plan §5.1–5.3).
 *
 * Fed each [NextStep] as [Guide] publishes it, with the current speed, plus the route
 * events that have no step ([onRerouting], [onArrived]); each call returns at most one
 * [VoicePrompt] to hand to the speaker. Pure JVM and single-threaded, driven from the main
 * thread like [RouteFollower], and host-tested the same way.
 *
 * **Stages.** A maneuver has up to three (the table in [threshold]); a stage fires on the
 * first step update at or under its threshold, and each at most once per step id. The
 * stage a step has *reached* only moves forward: a step first seen inside Prepare gets
 * Prepare and never a late Early, and one first seen inside At the turn gets only that.
 * This also runs while voice guidance is off, so turning it on mid-step does not release a
 * backlog; [confirm] says the one prompt that fits.
 *
 * **Speed.** The speed given, else the step's own pace (metres over BRouter's seconds to
 * it), else the last pace this step had (its seconds round to 0 in the last metres), else
 * standing still, which leaves every threshold at its minimum distance.
 *
 * **Reroutes land on the same turn with new ids** (plan §5.2). A reroute is a new route,
 * so its steps are new ids and would say everything again. To tell "the same turn", each
 * spoken stage is kept for [DEDUPE_MS] with *where its maneuver is along the distance
 * driven*: an odometer, the caller's when it passes one, else this class's own integral of
 * speed over time. A step first seen on a new route whose maneuver is the same type and
 * within [DEDUPE_M] of a kept one starts as having reached that stage. Dead reckoning
 * from the vehicle's speed drifts by about 2% of the distance, some 12 m over 20 s at
 * 70 mph, inside [DEDUPE_M]; a pace-only drive drifts more, and a missed match only
 * repeats a prompt.
 */
class VoicePrompts(
    var settings: VoiceSettings = VoiceSettings(),
    /** Whose distances the prompts speak in: the header's speed unit. */
    var unit: SpeedUnit = SpeedUnit.MPH,
) {
    private class StepState(
        val id: Long,
        val generation: Long,
        val maneuver: Maneuver,
        val earlyAllowed: Boolean,
        /** Its Prepare was said already, as the chained "then" of the step before it. */
        val prepareSuppressed: Boolean,
        /** The maneuver of the step before it on the same route, or null. */
        val previousType: Int?,
    ) {
        /** 0 none, then [EARLY], [PREPARE], [AT_TURN] as levels: only moves forward. */
        var reached = 0
        var pace: Double? = null
    }

    /** A spoken stage, and where its maneuver is along [odometer]. */
    private class Spoken(val atMs: Long, val positionM: Double, val type: Int, val level: Int)

    private var current: StepState? = null
    private var suppressNextPrepare = false
    private var rerouteAnnounced = false
    /** The route generation arrival was announced for; "You have arrived" once per route. */
    private var arrivedFor: Long? = null
    private var odometer = 0.0
    private var lastMs: Long? = null
    private var lastSpeed = 0.0
    private val spoken = ArrayDeque<Spoken>()

    /**
     * The step [Guide] is publishing, [speedMps] (null when nothing reports one) and
     * [nowMs] on any monotonic clock. [odometerM], when given, is metres driven so far by
     * any fixed origin, and is used instead of this class's dead reckoning.
     */
    @JvmOverloads
    fun onStep(step: NextStep, speedMps: Double?, nowMs: Long, odometerM: Double? = null): VoicePrompt? {
        val state = enter(step, speedMps, nowMs, odometerM)
        val speed = speedFor(step, speedMps, state)
        val zone = zone(step.metres, speed)
        if (zone <= state.reached) return null
        state.reached = zone
        if (!settings.enabled || !allowed(state, step, zone)) return null
        return say(state, step, zone, nowMs)
    }

    /**
     * The follower left the route and a re-route is in flight. Said once per re-route
     * however often [Guide] publishes it; the next route's first step re-arms it.
     */
    fun onRerouting(): VoicePrompt? {
        if (rerouteAnnounced) return null
        rerouteAnnounced = true
        if (!settings.enabled || !settings.announceReroute) return null
        return VoicePrompt(REROUTING, PromptStage.REROUTE)
    }

    /** The follower arrived. Said once per route. */
    fun onArrived(): VoicePrompt? {
        val generation = current?.generation ?: -1L
        if (arrivedFor == generation) return null
        arrivedFor = generation
        if (!settings.enabled || !settings.announceArrival) return null
        return VoicePrompt(ARRIVED, PromptStage.ARRIVAL)
    }

    /**
     * Voice guidance was just turned on (plan §6): the current step's prompt at once, as
     * confirmation, whatever the stage toggles say, then prompts carry on as normal. Inside
     * a stage's threshold it is that stage's prompt, and counts as it; further out it is
     * the distance and instruction, counted as Early without using Early up. Null with no
     * step, or with guidance still off.
     */
    fun confirm(step: NextStep?, speedMps: Double?, nowMs: Long): VoicePrompt? {
        if (step == null || !settings.enabled) return null
        val state = enter(step, speedMps, nowMs, null)
        val zone = zone(step.metres, speedFor(step, speedMps, state))
        state.reached = max(state.reached, zone)
        return say(state, step, max(zone, EARLY), nowMs)
    }

    /** The route ended. Forgets everything but the odometer, which only runs forward. */
    fun reset() {
        current = null
        suppressNextPrepare = false
        rerouteAnnounced = false
        arrivedFor = null
        spoken.clear()
    }

    /** Moves the odometer to [nowMs] and returns [step]'s state, new if its id is. */
    private fun enter(step: NextStep, speedMps: Double?, nowMs: Long, odometerM: Double?): StepState {
        val known = current?.takeIf { it.id == step.id }
        val speed = speedFor(step, speedMps, known)
        if (odometerM != null) {
            odometer = odometerM
        } else {
            // A gap (a re-route, a lost fix) is taken at the mean of the speeds either side.
            lastMs?.let { odometer += (lastSpeed + speed) / 2.0 * (nowMs - it).coerceAtLeast(0L) / 1000.0 }
        }
        lastMs = nowMs
        lastSpeed = speed
        while (spoken.isNotEmpty() && nowMs - spoken.first().atMs > DEDUPE_MS) spoken.removeFirst()
        if (known != null) return known

        val previous = current
        val generation = step.id / RouteFollower.STEP_ID_STRIDE
        val newRoute = previous == null || previous.generation != generation
        if (newRoute) {
            rerouteAnnounced = false
            suppressNextPrepare = false
        }
        val state = StepState(
            id = step.id,
            generation = generation,
            maneuver = step.maneuver,
            earlyAllowed = step.metres >= 2.0 * threshold(PREPARE, speed, settings.timing),
            prepareSuppressed = suppressNextPrepare,
            previousType = if (newRoute) null else previous?.maneuver?.type,
        )
        suppressNextPrepare = false
        if (newRoute) {
            // The same turn on a new route: what was said about it stands.
            val at = odometer + step.metres
            state.reached = spoken.filter {
                it.type == step.maneuver.type && abs(it.positionM - at) <= DEDUPE_M
            }.maxOfOrNull { it.level } ?: 0
        }
        current = state
        return state
    }

    private fun speedFor(step: NextStep, speedMps: Double?, state: StepState?): Double {
        val given = speedMps?.takeIf { it.isFinite() && it >= 0.0 }
        if (given != null) return given
        val pace = if (step.seconds > 0) step.metres / step.seconds else null
        if (pace != null && state != null) state.pace = pace
        return pace ?: state?.pace ?: 0.0
    }

    /** The furthest-on stage whose threshold [metres] is inside, as a level; 0 for none. */
    private fun zone(metres: Double, speed: Double): Int {
        val timing = settings.timing
        return when {
            metres <= threshold(AT_TURN, speed, timing) -> AT_TURN
            metres <= threshold(PREPARE, speed, timing) -> PREPARE
            metres <= threshold(EARLY, speed, timing) -> EARLY
            else -> 0
        }
    }

    /** Whether the stage at [level] is spoken for this step at all (§5.2's quiet rules). */
    private fun allowed(state: StepState, step: NextStep, level: Int): Boolean {
        val type = step.maneuver.type
        return when {
            // Straight is heard only as a name change the driver needs, and only at Prepare.
            type == Maneuvers.STRAIGHT -> level == PREPARE && settings.prepare && nameChange(step)
            // The final approach gets Prepare; arriving is [onArrived]'s.
            type == Maneuvers.DESTINATION -> level == PREPARE && settings.prepare && !state.prepareSuppressed
            level == EARLY -> settings.early && state.earlyAllowed
            level == PREPARE -> settings.prepare && !state.prepareSuppressed
            else -> settings.atTurn
        }
    }

    /** A road-name change worth saying: street names on, and a named road that is new. */
    private fun nameChange(step: NextStep): Boolean {
        val onto = step.onto ?: return false
        return settings.streetNames && onto != step.road && !step.instruction.endsWith(" to stay on $onto")
    }

    private fun say(state: StepState, step: NextStep, level: Int, nowMs: Long): VoicePrompt {
        val full = settings.detail == PromptDetail.FULL
        var instruction = SpokenText.instruction(step, settings.streetNames)
        val text = when (level) {
            AT_TURN -> {
                val next = step.later.firstOrNull()
                if (full && settings.atTurn && next != null && next.maneuver.type != Maneuvers.STRAIGHT &&
                    next.metres - step.metres <= CHAIN_M
                ) {
                    suppressNextPrepare = true
                    "$instruction, then ${SpokenText.lowerFirst(SpokenText.laterInstruction(next.maneuver))}"
                } else {
                    instruction
                }
            }
            else -> {
                if (full && level == PREPARE && isExit(step.maneuver.type) && isKeep(state.previousType)) {
                    val side = if (state.previousType == Maneuvers.KEEP_LEFT) "keep left" else "keep right"
                    instruction = "$side, then ${SpokenText.lowerFirst(instruction)}"
                }
                SpokenText.inDistance(step.metres, unit, instruction)
            }
        }
        spoken.addLast(Spoken(nowMs, odometer + step.metres, step.maneuver.type, level))
        return VoicePrompt(text, STAGES[level])
    }

    private fun isExit(type: Int) = type == Maneuvers.OFF_RAMP_SLIGHT_LEFT || type == Maneuvers.OFF_RAMP_SLIGHT_RIGHT

    private fun isKeep(type: Int?) = type == Maneuvers.KEEP_LEFT || type == Maneuvers.KEEP_RIGHT

    companion object {
        const val EARLY = 1
        const val PREPARE = 2
        const val AT_TURN = 3
        private val STAGES = arrayOf(PromptStage.EARLY, PromptStage.EARLY, PromptStage.PREPARE, PromptStage.AT_TURN)

        /**
         * The §5.2 table at normal timing, by level: lead seconds and minimum metres.
         * A starting point for a drive, not a finding.
         */
        private val LEAD_S = doubleArrayOf(0.0, 50.0, 15.0, 4.0)
        private val MINIMUM_M = doubleArrayOf(0.0, 800.0, 150.0, 30.0)

        /** A later maneuver this close after the next one is chained onto its At-the-turn. */
        const val CHAIN_M = 150.0
        /** How long, and how near, a spoken stage survives a re-route onto the same turn. */
        const val DEDUPE_MS = 20_000L
        const val DEDUPE_M = 30.0

        const val REROUTING = "Rerouting"
        const val ARRIVED = "You have arrived"

        /**
         * Metres before the maneuver at which the stage at [level] fires:
         * `max(minimum, speed × lead × timing)`. Timing scales the lead, never the minimum.
         */
        fun threshold(level: Int, speedMps: Double, timing: PromptTiming): Double =
            max(MINIMUM_M[level], speedMps * LEAD_S[level] * timing.factor)
    }
}
