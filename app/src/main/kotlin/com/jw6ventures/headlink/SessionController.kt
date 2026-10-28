package com.jw6ventures.headlink

import android.hardware.usb.UsbAccessory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.time.Instant
import java.util.UUID

/**
 * Spec §9 states reachable in this build. VERSION through PROJECTING mirror the native
 * session: TLS once the version response is written, AUTH once the peer Finished is
 * verified, DISCOVERY once authentication succeeded, READY once capabilities are
 * validated (the discovery-only hold), SETUP while channels, AV setup, input binding, and
 * video focus are negotiated, and PROJECTING while video and input run.
 */
enum class Stage { IDLE, ARMED, PERMISSION, VERSION, TLS, AUTH, DISCOVERY, READY, SETUP, PROJECTING, STOPPING }

/** Operator choices for one attempt, fixed when it begins. */
data class AttemptSettings(
    val profile: String,
    val stopAfter: String,
    val tlsProfile: String,
    /** "Trust the next wired head unit" was armed for this attempt only (C2). */
    val armedFirstUse: Boolean,
    /** Head-unit pins in force when the attempt began (C3). */
    val pins: List<String>,
    /** Keep the raw discovery response bytes in the report (sensitive; diagnosis only). */
    val captureDiscovery: Boolean = false,
    /** Keep a bounded body for each message no profile interprets (diagnosis only). */
    val captureUnhandled: Boolean = false,
    /** Write the encoded Annex B stream to app-private storage (diagnosis only). */
    val captureVideo: Boolean = false,
    /** Open every advertised channel, as Android Auto does. */
    val openAllChannels: Boolean = false,
    /** Ask for video focus; Android Auto waits for the unsolicited grant. */
    val requestFocus: Boolean = true,
    /** Seconds between IDRs; Android Auto uses 60 where this source used 1. */
    val keyframeSeconds: Int = 1,
    /**
     * Rebuild the encoder when the head unit hands the screen back, as Android Auto does.
     *
     * On by default because resuming in place leaves Ford SYNC 4.0 showing native UI while
     * it acknowledges every frame; clearing it restores the old behaviour for comparison.
     * App-side only — it never reaches the native configuration.
     */
    val rebuildOnRegain: Boolean = true,
    /**
     * Restart the video stream when the head unit keeps falling behind, as a screen switch
     * does (spec §8.2). On by default: on 2026-10-11 a screen switch was the only thing
     * that cleared Ford SYNC 4.0's smeared, frozen picture (report d11eec1e).
     */
    val congestionRestart: Boolean = true,
    /** What field 1 of the AV setup request carries: one of [SessionSettings.SETUP_FIELDS]. */
    val setupField: String = SessionSettings.SETUP_FIELD_CODEC,
    /** Ping encryption: one of [SessionSettings.KEEPALIVE_PROFILES]. */
    val keepalive: String = SessionSettings.KEEPALIVE_MIRROR,
    /** Projection length in milliseconds; null projects until Stop. */
    val projectionMs: Long? = null,
    /**
     * Advertised sensor types to subscribe to during projection: [SessionSettings.SENSOR_SETS].
     *
     * Every advertised type by default: a stationary drive is expensive evidence, and the
     * types the head unit populates are only learned by asking for all of them. The set is
     * always bounded by the advertisement, and every sensor failure stays non-fatal.
     */
    val sensors: String = SessionSettings.SENSORS_ALL,
    /** Put head-unit latitude and longitude in the report; sensitive, so off by default (spec §10). */
    val sensorLocation: Boolean = false,
    /**
     * Open the advertised now-playing channel during projection (P5.1): head-unit
     * transport buttons reach whatever is playing on the phone, and what is playing is
     * reported to the head unit while notification access is also granted.
     *
     * On by default. It needs no Android permission of its own — without the notification
     * grant the channel carries a stopped, empty state — and a head unit that does not
     * advertise the channel, or refuses it, is recorded and skipped rather than failing.
     */
    val mediaInfo: Boolean = true,
    /**
     * Open one advertised PCM `MEDIA` audio channel during projection and send app-owned
     * audio on it (P5.3). Adds no Android permission; every audio failure is non-fatal.
     */
    val audio: Boolean = false,
    /**
     * Open the advertised `navigation` channel during projection and drive the head
     * unit's own instrument cluster (P6.2): with the maneuvers of the route being
     * followed (P6.5), or with the scripted demonstration when [navigationScript] is set.
     * Adds no Android permission; every navigation failure is non-fatal.
     */
    val navigation: Boolean = false,
    /**
     * Play P6.2's scripted demonstration on the cluster instead of the route being
     * followed. Off by default: with it off, the cluster shows nothing until a
     * destination is set, which is what a driver expects of a navigation app.
     */
    val navigationScript: Boolean = false,
    /** How cluster distances are written: one of [SessionSettings.NAVIGATION_UNITS]. */
    val navigationUnits: String = SessionSettings.NAVIGATION_UNITS_IMPERIAL,
    /**
     * Send the scripted sequence even when the head unit announces no cluster and grants
     * no focus (P6.2). Off by default, which waits for both.
     *
     * It exists for one question a drive cannot otherwise answer twice: a head unit that
     * opens the channel and never says a cluster is there leaves an ordinary attempt with
     * a real finding and no maneuver evidence at all. With this set the same trip also
     * shows what it does when told anyway, and the report says nothing was waited for.
     */
    val navigationForce: Boolean = false,
    /** Diagnostic comparison; never silently falls back to hardware. */
    val softwareEncoder: Boolean = false,
    /** Which advertised video configuration to take: one of [SessionSettings.VIDEO_PREFERENCES]. */
    val videoPreference: String = SessionSettings.VIDEO_PREFERENCE_LARGEST,
    /** Encode at this rate instead of the negotiated one; 0 keeps the negotiated rate. */
    val encoderFps: Int = 0,
    /** Rectangle of the frame the scene is drawn on: one of [SessionSettings.SCENE_AREAS]. */
    val sceneArea: String = SessionSettings.SCENE_AREA_CONTENT,
    /** Which scene is projected: one of [SessionSettings.SCENES]. App-side only. */
    val scene: String = SessionSettings.SCENE_DASHBOARD,
    /** Which half of the dashboard the owned map occupies (P5.5). */
    val mapOnLeft: Boolean = true,
    /**
     * The unit the dashboard header spells the speed in. App-side only: every source
     * reports metres per second, so nothing about this reaches the native session.
     */
    val speedUnit: SpeedUnit = SpeedUnit.MPH,
    /** Mapsforge file to render, or null to take the newest one the app has installed. */
    val mapFile: String? = null,
    /**
     * Lab transport instead of the USB accessory: an abstract local socket name. Debug
     * builds only, set by an explicit intent extra for the emulator lab run; never used
     * in a vehicle.
     */
    val labSocket: String? = null,
    /**
     * W2 (docs/connecting.md, "The companion-device link and auto-start"): the session runs over the TCP connection
     * [WirelessLink] has already opened to the head unit, instead of the USB accessory.
     */
    val wireless: Boolean = false,
) {
    /** The transport, as reports name it. */
    val transport: String
        get() = labSocket?.let { "lab-socket:$it" } ?: if (wireless) "wireless" else "accessory"
}

/**
 * One user-initiated connection attempt. Identity is immutable; progress fields are
 * main-thread only. Every attempt gets a fresh generation and session UUID; nothing
 * is reused by a later attempt, and nothing reconnects automatically.
 */
class Attempt(val generation: Long, val settings: AttemptSettings) {
    val profile: String get() = settings.profile
    val sessionUuid: String = UUID.randomUUID().toString()
    val startedElapsedMs: Long = SystemClock.elapsedRealtime()

    /** Wall-clock start, for report metadata and the saved report's name only. */
    val startedUtc: Instant = Instant.now()

    /** Accessory chosen for this attempt, always taken from a current enumeration. */
    var accessory: UsbAccessory? = null

    /** Enumerated accessory that did not match the filter, offered for manual use. */
    var mismatchCandidate: UsbAccessory? = null
    var filterMatch: Boolean? = null
    var manualOverride = false
    var notificationPrompted = false
    var permissionRequested = false

    /** The foreground service now owns the rest of this attempt. */
    var serviceRequested = false
}

/**
 * Main-thread state shared by [ConnectionActivity] and [ProjectionService]. Holds no
 * Context. Listeners are notified synchronously after each change. [note] alone may be
 * called from any thread; everything else is main-thread only.
 */
object SessionController {
    const val EVENT_LIMIT = 200

    /** Milestones in order; [lastCompleted] never moves backwards within an attempt. */
    val MILESTONES =
        listOf(
            "-", "accessory", "permission", "foreground", "descriptor", "version", "tls", "auth",
            "discovery", "setup", "projecting",
        )

    /** Native `last_completed_stage` values, in order. */
    private val NATIVE_MILESTONES = listOf("version", "tls", "auth", "discovery", "setup", "projecting")
    private val NATIVE_STAGES = Stage.VERSION..Stage.PROJECTING

    private var nextGeneration = 1L
    private val listeners = mutableListOf<() -> Unit>()

    var stage = Stage.IDLE
        private set
    var attempt: Attempt? = null
        private set

    /** Last milestone reached by the current or most recent attempt. */
    var lastCompleted = "-"
        private set
    var finalCategory = "-"
        private set
    var finalDetail = "-"
        private set

    /** Latest native report text for the current or most recent attempt. */
    var nativeReport = ""
        private set
    val events = ArrayDeque<String>()

    /**
     * Latest app-side projection report, or empty when no projection ran: the pipeline's
     * section, then the audio source's, then media control's.
     *
     * Each producer's section is captured independently and only ever replaces its own
     * slot ([projectionPipelineReport], [projectionAudioReport], [projectionMediaReport]).
     * Releasing one producer (a normal part of ending a session — see
     * [ProjectionService.releaseIfDone]) used to overwrite this whole string with just
     * that producer's report, silently discarding whatever another producer had already
     * contributed; a session with media control enabled lost the entire pipeline section
     * (encoder, scene, and submission counters) that way. Keeping the three sections in
     * separate fields makes that failure mode impossible rather than merely unlikely.
     */
    val projectionReport: String
        get() = pipelineReport + audioSourceReport + mediaControlSectionReport

    private var pipelineReport = ""
    private var audioSourceReport = ""
    private var mediaControlSectionReport = ""

    /** Latest native identity report (`source_identity_*`, `tls_self_test`), or empty. */
    var identityReport = ""
        private set
    var identityBusy = false
        private set

    /** The source identity loaded and passed the in-memory TLS self-test. */
    val identityReady: Boolean
        get() = !identityBusy && parseReport(identityReport.toByteArray()).let {
            it["source_identity_fingerprint_sha256"] != null && it["tls_self_test"]?.startsWith("pass") == true
        }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Replaces the app-side projection-pipeline report section (encoder, scene, and
     * submission counters); captured the moment [ProjectionPipeline] is released. */
    fun projectionPipelineReport(text: String) {
        pipelineReport = text
        notifyListeners()
    }

    /** Replaces the app-side audio-source report section (PCM submission counters);
     * captured the moment [AudioSource] is released. */
    fun projectionAudioReport(text: String) {
        audioSourceReport = text
        notifyListeners()
    }

    /** Replaces the app-side media-control report section (dispatch and publish
     * counters); captured the moment [MediaControl] is released. */
    fun projectionMediaReport(text: String) {
        mediaControlSectionReport = text
        notifyListeners()
    }

    fun identityWorkStarted() {
        identityBusy = true
        notifyListeners()
    }

    fun identityLoaded(report: String) {
        identityBusy = false
        identityReport = report
        log("identity " + (parseReport(report.toByteArray()).let { it["identity_error"] ?: it["tls_self_test"] } ?: "-"))
    }

    /** Idle → Armed with a fresh attempt. Returns null unless idle. */
    fun begin(settings: AttemptSettings): Attempt? {
        if (stage != Stage.IDLE) return null
        val next = Attempt(nextGeneration++, settings)
        attempt = next
        lastCompleted = "-"
        finalCategory = "-"
        finalDetail = "-"
        nativeReport = ""
        pipelineReport = ""
        audioSourceReport = ""
        mediaControlSectionReport = ""
        events.clear()
        move(
            Stage.ARMED,
            "armed generation=${next.generation} profile=${settings.profile} stop_after=${settings.stopAfter} " +
                "tls_profile=${settings.tlsProfile} first_use_armed=${settings.armedFirstUse} pins=${settings.pins.size} " +
                "capture_discovery=${settings.captureDiscovery} keepalive=${settings.keepalive} " +
                "sensors=${settings.sensors} transport=${settings.transport}",
        )
        return next
    }

    fun isCurrent(candidate: Attempt): Boolean = attempt === candidate && stage != Stage.IDLE

    fun move(to: Stage, note: String) {
        stage = to
        log("stage=${to.name} $note")
    }

    fun completed(which: Attempt, milestone: String) {
        if (!isCurrent(which) || MILESTONES.indexOf(milestone) <= MILESTONES.indexOf(lastCompleted)) return
        lastCompleted = milestone
        log("completed=$milestone")
    }

    /**
     * Adds [text] to the event log. Any thread: the media, speech and feed threads note
     * failures too, and the log and its listeners (which update views) are main-thread
     * state, so a note from elsewhere is posted there rather than racing them.
     */
    fun note(text: String) {
        val looper = Looper.getMainLooper()
        if (looper.isCurrentThread) log(text) else Handler(looper).post { log(text) }
    }

    /** Applies a native report for [which]; stale attempts are ignored by the caller. */
    fun nativeProgress(which: Attempt, report: ByteArray) {
        if (attempt !== which) return
        nativeReport = report.toString(Charsets.UTF_8)
        val fields = parseReport(report)
        val reached = NATIVE_MILESTONES.indexOf(fields["last_completed_stage"])
        for (milestone in NATIVE_MILESTONES.take(reached + 1)) {
            if (!isCurrent(which) || MILESTONES.indexOf(milestone) <= MILESTONES.indexOf(lastCompleted)) continue
            log(
                when (milestone) {
                    "version" -> "version requested=${fields["requested_version"]} response=${fields["response_version"]}"
                    "tls" -> "tls ${fields["tls_version"]} ${fields["tls_cipher"]} peer=${fields["tls_peer_decision"]}"
                    "auth" -> "auth status=${fields["auth_status"]} encrypted=${fields["auth_encrypted"]}"
                    "discovery" -> "discovery capabilities=${fields["discovery_capabilities"]} " +
                        "video=${fields["discovery_usable_video"]} input=${fields["discovery_usable_input"]}"
                    "setup" -> "channels open, video started ${fields["video_selected"]}"
                    else -> "first keyframe sent at ${fields["video_first_keyframe_written_ms"]} ms"
                },
            )
            completed(which, milestone)
        }
        if (!syncNativeStage(which)) notifyListeners()
    }

    /**
     * Moves forward to the native stage. Native events can arrive before the service
     * records its start, so the service calls this again then.
     */
    fun syncNativeStage(which: Attempt): Boolean {
        if (!isCurrent(which) || stage !in NATIVE_STAGES) return false
        val nativeStage = parseReport(nativeReport.toByteArray(Charsets.UTF_8))["stage"]
        val target =
            when (nativeStage) {
                "tls" -> Stage.TLS
                "auth" -> Stage.AUTH
                "discovery" -> Stage.DISCOVERY
                "ready" -> Stage.READY
                "setup" -> Stage.SETUP
                "projecting" -> Stage.PROJECTING
                // A shutdown request keeps whichever stage the session reached.
                "shutdown" -> return false
                else -> return false
            }
        if (target <= stage) return false
        move(target, "native stage $nativeStage")
        return true
    }

    /** Any state → Stopping. */
    fun stopping(which: Attempt, reason: String) {
        if (!isCurrent(which) || stage == Stage.STOPPING) return
        move(Stage.STOPPING, reason)
    }

    /** Stopping → Idle, recording the final result. */
    fun finish(which: Attempt, category: String, detail: String) {
        if (!isCurrent(which)) return
        if (stage != Stage.STOPPING) move(Stage.STOPPING, "finishing: $category")
        finalCategory = category
        finalDetail = detail
        move(Stage.IDLE, "final_category=$category")
    }

    private fun log(text: String) {
        val base = attempt?.startedElapsedMs ?: SystemClock.elapsedRealtime()
        events.addLast("+${SystemClock.elapsedRealtime() - base}ms $text")
        while (events.size > EVENT_LIMIT) events.removeFirst()
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }
}
