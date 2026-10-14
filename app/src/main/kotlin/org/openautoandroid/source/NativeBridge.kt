package org.openautoandroid.source

/**
 * Kotlin side of the native session contract (spec §5.3). Pure JVM with no Android
 * API, so tools/jni-host-test loads this exact class against a host build of the
 * library. `JNI_OnLoad` registers the natives with explicit signatures: a mismatch
 * fails loading instead of misbehaving at call time.
 */
object NativeBridge {
    const val START_INVALID_DESCRIPTOR = -1L
    const val START_INVALID_CONFIG = -2L
    const val START_BUSY = -3L
    const val START_STALE_IO = -4L
    const val START_SPAWN_FAILED = -5L
    const val START_CALLBACK_INVALID = -6L
    const val START_INTERNAL_ERROR = -7L
    const val START_IDENTITY_UNAVAILABLE = -8L

    const val EVENT_PROGRESS = 1
    const val EVENT_FINISHED = 2
    const val EVENT_VIDEO_START = 3
    const val EVENT_VIDEO_STOP = 4
    const val EVENT_KEYFRAME_REQUEST = 5
    const val EVENT_FOCUS = 6
    const val EVENT_TOUCH = 7
    const val EVENT_KEY = 8
    const val EVENT_RESTRICTION = 9
    const val EVENT_VIDEO_GEOMETRY = 10
    const val EVENT_TRANSPORT = 11
    const val EVENT_AUDIO_START = 12
    const val EVENT_AUDIO_STOP = 13
    const val EVENT_VEHICLE = 14
    const val EVENT_VIDEO_RESTART = 15

    /** [submitVideo] results; negative values are contract errors. */
    const val SUBMIT_ACCEPTED = 0
    const val SUBMIT_UNKNOWN_HANDLE = -1
    const val SUBMIT_INVALID_BUFFER = -2
    const val SUBMIT_INTERNAL_ERROR = -3

    /** [submitNowPlaying] results; both non-negative values are successful publications. */
    const val NOW_PLAYING_CHANGED = 0
    const val NOW_PLAYING_UNCHANGED = 1
    const val NOW_PLAYING_UNKNOWN_HANDLE = -1
    const val NOW_PLAYING_INVALID = -2
    const val NOW_PLAYING_INTERNAL_ERROR = -3

    /** [submitGuidance] states, mirroring `GUIDANCE_*` in the native manager (P6.5). */
    const val GUIDANCE_IDLE = 0
    const val GUIDANCE_GUIDING = 1
    const val GUIDANCE_REROUTING = 2
    const val GUIDANCE_ARRIVED = 3
    /**
     * The route was ended (P7.4): `INACTIVE` like [GUIDANCE_IDLE], and then the cluster is
     * handed back to the head unit's own navigation until the next route asks for it again.
     */
    const val GUIDANCE_ENDED = 4

    /** Wire values of `PlaybackState`; see core/aap-session/src/now_playing.rs. */
    const val PLAYBACK_UNKNOWN = 0
    const val PLAYBACK_STOPPED = 1
    const val PLAYBACK_PLAYING = 2
    const val PLAYBACK_PAUSED = 3
    const val PLAYBACK_ERROR = 4

    /** Null once the library is loaded; otherwise why loading failed. Loads once. */
    val loadError: String? by lazy {
        try {
            System.loadLibrary("aap_jni")
            null
        } catch (e: UnsatisfiedLinkError) {
            e.message ?: e.toString()
        }
    }

    /** In-memory engine self-test report. Never throws. */
    fun probe(): String =
        loadError?.let { "native_load=failed\nerror=$it" } ?: (nativeProbe() ?: "native_probe=null")

    /**
     * Loads the source identity in [directory], creating it if absent or replacing it when
     * [replace] is set, then runs the in-memory TLS self-test. Returns nonsecret report
     * lines; failures are reported as `identity_error=`. Blocking (RSA key generation):
     * never call on the UI thread or while a head unit is waiting. Never throws.
     */
    fun ensureIdentity(directory: String, replace: Boolean): String =
        loadError?.let { "identity_error=native library not loaded: $it\n" }
            ?: (nativeEnsureIdentity(directory, replace) ?: "identity_error=native returned null\n")

    /**
     * Starts a native session on [ownedFd]. Ownership transfers on invocation: native
     * code closes the descriptor on every path, so the caller must not use or close it
     * afterwards. Check [loadError] before detaching a descriptor: this throws without
     * taking ownership if the library is not loaded.
     *
     * Returns the positive handle, equal to the configured generation, or a negative
     * `START_*` code. [callback] runs on the native session thread and must only post
     * work elsewhere; it must not call [stop] and wait.
     */
    fun start(ownedFd: Int, config: ByteArray, callback: SessionCallback): Long {
        check(loadError == null) { "native library not loaded: $loadError" }
        return nativeStart(ownedFd, config, callback)
    }

    /** Cancels and joins the session, then releases [handle]. Blocks; never call on the UI thread. */
    fun stop(handle: Long) {
        if (loadError == null) nativeStop(handle)
    }

    /** Latest report snapshot, or null for an unknown or released handle. */
    fun report(handle: Long): ByteArray? = if (loadError == null) nativeGetReport(handle) else null

    /**
     * Hands one encoder output buffer to the session. The native side copies the bytes
     * before returning, so the caller may release the `MediaCodec` output buffer at once;
     * it never blocks on the transport. [buffer] must be the direct buffer `MediaCodec`
     * returned. Returns a submit result (0 accepted, 1..5 dropped or refused) or a
     * negative `SUBMIT_*` code. Never throws.
     */
    fun submitVideo(
        handle: Long,
        flags: Int,
        presentationUs: Long,
        buffer: java.nio.ByteBuffer,
        offset: Int,
        size: Int,
    ): Int =
        if (loadError == null) {
            nativeSubmitVideo(handle, flags, presentationUs, buffer, offset, size)
        } else {
            SUBMIT_INTERNAL_ERROR
        }

    /** The encoder failed; the session ends with `ENCODER_FAILED`. Never throws. */
    fun videoFailed(handle: Long, detail: String) {
        if (loadError == null) nativeVideoFailed(handle, detail)
    }

    /**
     * Hands one PCM buffer to the session (P5.3). [buffer] must be a direct
     * `ByteBuffer` of signed 16-bit little-endian samples at the format the audio start
     * event named; the native side copies the bytes before returning and never blocks on
     * the transport. Returns a submit result or a negative `SUBMIT_*` code. Never throws.
     */
    fun submitAudio(
        handle: Long,
        presentationUs: Long,
        buffer: java.nio.ByteBuffer,
        offset: Int,
        size: Int,
    ): Int =
        if (loadError == null) {
            nativeSubmitAudio(handle, presentationUs, buffer, offset, size)
        } else {
            SUBMIT_INTERNAL_ERROR
        }

    /**
     * The audio source failed. Audio stops; the session continues, because audio is an
     * opt-in integration and the picture is not its hostage. Never throws.
     */
    fun audioFailed(handle: Long, detail: String) {
        if (loadError == null) nativeAudioFailed(handle, detail)
    }

    /**
     * Publishes what is playing on the phone; the session sends it on the `media_info`
     * channel once the head unit has opened that channel (P5.1). Returns
     * [NOW_PLAYING_CHANGED], [NOW_PLAYING_UNCHANGED], or a negative `NOW_PLAYING_*` code.
     *
     * This app plays nothing. [state] and the strings describe another application's
     * session when the operator has granted this app that access, and are the stopped,
     * empty state otherwise: nothing here ever reports a track it was not told about.
     * Never throws.
     */
    fun submitNowPlaying(
        handle: Long,
        state: Int,
        positionSeconds: Int,
        sourceApp: String,
        title: String,
        artist: String,
        album: String,
    ): Int =
        if (loadError == null) {
            nativeSubmitNowPlaying(handle, state, positionSeconds, sourceApp, title, artist, album)
        } else {
            NOW_PLAYING_INTERNAL_ERROR
        }

    /**
     * Publishes what the guidance loop says the cluster should show (P6.5); the session
     * sends it on the `navigation` channel when that channel is route-driven and the
     * cluster is held, at the head unit's interval. Returns the `NOW_PLAYING_*` codes.
     *
     * [step] identifies the maneuver and must never repeat across routes: a new identity
     * is what puts a new notification on the wire. The maneuver fields are read only for
     * [GUIDANCE_GUIDING]; [maneuver] is a raw `ManeuverType` value and [exit] a
     * roundabout exit, 0 for none. [onto] is the road the maneuver turns onto and [road]
     * the one being driven, each empty when unknown. Never throws.
     */
    fun submitGuidance(
        handle: Long,
        state: Int,
        step: Long = 0,
        maneuver: Int = 0,
        exit: Int = 0,
        instruction: String = "",
        onto: String = "",
        road: String = "",
        stepMetres: Double = 0.0,
        stepSeconds: Int = 0,
        destinationMetres: Double = 0.0,
        destinationSeconds: Int = 0,
    ): Int =
        if (loadError == null) {
            nativeSubmitGuidance(
                handle, state, step, maneuver, exit, instruction, onto, road,
                stepMetres, stepSeconds, destinationMetres, destinationSeconds,
            )
        } else {
            NOW_PLAYING_INTERNAL_ERROR
        }

    /**
     * Asks for a graceful stop: the stream is stopped and a shutdown request sent, so the
     * head unit returns to its own interface. Returns at once; the session must still be
     * released with [stop].
     */
    fun requestShutdown(handle: Long) {
        if (loadError == null) nativeRequestShutdown(handle)
    }

    fun submitResultName(code: Int): String =
        when (code) {
            SUBMIT_ACCEPTED -> "accepted"
            1 -> "dropped-congested"
            2 -> "dropped-awaiting-keyframe"
            3 -> "dropped-not-streaming"
            4 -> "invalid"
            5 -> "closed"
            SUBMIT_UNKNOWN_HANDLE -> "unknown-handle"
            SUBMIT_INVALID_BUFFER -> "invalid-buffer"
            SUBMIT_INTERNAL_ERROR -> "internal-error"
            else -> "unknown($code)"
        }

    fun startResultName(code: Long): String =
        when (code) {
            START_INVALID_DESCRIPTOR -> "INVALID_DESCRIPTOR"
            START_INVALID_CONFIG -> "INVALID_CONFIG"
            START_BUSY -> "BUSY"
            START_STALE_IO -> "STALE_IO"
            START_SPAWN_FAILED -> "SPAWN_FAILED"
            START_CALLBACK_INVALID -> "CALLBACK_INVALID"
            START_INTERNAL_ERROR -> "INTERNAL_ERROR"
            START_IDENTITY_UNAVAILABLE -> "IDENTITY_UNAVAILABLE"
            else -> if (code > 0) "STARTED" else "UNKNOWN($code)"
        }

    /** Version-only native configuration text; see core/aap-jni/src/manager.rs. */
    fun config(generation: Long, sessionUuid: String, profile: String): ByteArray =
        config(generation, sessionUuid, SessionSettings(profile = profile))

    /** Strict native configuration text; see core/aap-jni/src/manager.rs. */
    fun config(generation: Long, sessionUuid: String, settings: SessionSettings): ByteArray =
        buildString {
            append("generation=$generation\nsession_uuid=$sessionUuid\nprofile=${settings.profile}\n")
            append("stop_after=${settings.stopAfter}\n")
            if (settings.stopAfter != SessionSettings.STOP_AFTER_VERSION) {
                append("identity_dir=${settings.identityDir}\n")
                append("tls_profile=${settings.tlsProfile}\n")
                if (settings.firstUse) {
                    append("peer_policy=first-use\n")
                } else {
                    append("peer_policy=pinned\npeer_pins=${settings.pins.joinToString(",")}\n")
                }
                if (settings.stopAfter == SessionSettings.STOP_AFTER_PROJECTION) {
                    settings.projectionMs?.let { append("projection_ms=$it\n") }
                    if (settings.sensors != SessionSettings.SENSORS_NONE) {
                        append("sensors=${settings.sensors}\n")
                        if (settings.sensorLocation) append("sensor_location=true\n")
                    }
                    if (settings.mediaInfo) append("media_info=true\n")
                    if (settings.audio) append("audio=true\n")
                    if (settings.navigation) {
                        append("navigation=true\n")
                        append("navigation_units=${settings.navigationUnits}\n")
                        append("navigation_source=${if (settings.navigationScript) "script" else "route"}\n")
                        if (settings.navigationForce) append("navigation_force=true\n")
                    }
                    if (settings.openAllChannels) append("open_all_channels=true\n")
                    if (!settings.requestFocus) append("request_focus=false\n")
                    if (settings.setupField != SessionSettings.SETUP_FIELD_CODEC) {
                        append("setup_field=${settings.setupField}\n")
                    }
                    append("congestion_restart=${settings.congestionRestart}\n")
                } else {
                    settings.holdMs?.let { append("hold_ms=$it\n") }
                }
                if (settings.captureDiscovery) append("capture_discovery=true\n")
                if (settings.captureUnhandled) append("capture_unhandled=true\n")
                // Always sent, so the app never depends on matching the native default.
                append("video_preference=${settings.videoPreference}\n")
                append("keepalive=${settings.keepalive}\n")
            }
        }.toByteArray(Charsets.US_ASCII)

    @JvmStatic
    private external fun nativeProbe(): String?

    @JvmStatic
    private external fun nativeStart(ownedFd: Int, config: ByteArray, callback: SessionCallback): Long

    @JvmStatic
    private external fun nativeStop(handle: Long)

    @JvmStatic
    private external fun nativeGetReport(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeEnsureIdentity(directory: String, replace: Boolean): String?

    @JvmStatic
    private external fun nativeSubmitVideo(
        handle: Long,
        flags: Int,
        presentationUs: Long,
        buffer: java.nio.ByteBuffer,
        offset: Int,
        size: Int,
    ): Int

    @JvmStatic
    private external fun nativeVideoFailed(handle: Long, detail: String)

    @JvmStatic
    private external fun nativeSubmitAudio(
        handle: Long,
        presentationUs: Long,
        buffer: java.nio.ByteBuffer,
        offset: Int,
        size: Int,
    ): Int

    @JvmStatic
    private external fun nativeAudioFailed(handle: Long, detail: String)

    @JvmStatic
    private external fun nativeRequestShutdown(handle: Long)

    @JvmStatic
    private external fun nativeSubmitGuidance(
        handle: Long,
        state: Int,
        step: Long,
        maneuver: Int,
        exit: Int,
        instruction: String,
        onto: String,
        road: String,
        stepMetres: Double,
        stepSeconds: Int,
        destinationMetres: Double,
        destinationSeconds: Int,
    ): Int

    @JvmStatic
    private external fun nativeSubmitNowPlaying(
        handle: Long,
        state: Int,
        positionSeconds: Int,
        sourceApp: String,
        title: String,
        artist: String,
        album: String,
    ): Int
}

/**
 * What one attempt asks the native session to do. Beyond version, [identityDir] must
 * hold the source identity, and exactly one peer policy applies: first-use trust armed
 * for this attempt only, or the pinned head-unit fingerprints (possibly none).
 */
data class SessionSettings(
    val profile: String = "legacy-1.5",
    val stopAfter: String = STOP_AFTER_VERSION,
    val tlsProfile: String = "tls12-aead",
    val identityDir: String = "",
    val firstUse: Boolean = false,
    val pins: List<String> = emptyList(),
    val holdMs: Long? = null,
    /** Keep the raw discovery response bytes in the report (sensitive; diagnosis only). */
    val captureDiscovery: Boolean = false,
    /** Ping encryption: one of [KEEPALIVE_PROFILES]. */
    val keepalive: String = KEEPALIVE_MIRROR,
    /** Projection length in milliseconds; null runs until Stop (projection only). */
    val projectionMs: Long? = null,
    /**
     * Which advertised sensor types to subscribe to: one of [SENSOR_SETS] (projection
     * only). `all` is the P5.2 harvest, which answers which of the types the head unit
     * advertises actually carry values, and is the default: a stationary drive is
     * expensive evidence, and a set that intersects nothing leaves the channel closed.
     */
    val sensors: String = SENSORS_ALL,
    /**
     * Put head-unit latitude and longitude in the report (projection only, P5.2). Off by
     * default: position identifies the vehicle, so spec §10 treats it like the other
     * capture options. Without it the position sensors are still subscribed and counted,
     * so the report says whether position arrives and never where.
     */
    val sensorLocation: Boolean = false,
    /**
     * Open the advertised now-playing channel (projection only, P5.1). Head-unit
     * transport buttons then reach the phone's current media session through
     * [MediaControl], which needs no permission; what is playing is reported on the
     * channel only while the operator has also granted notification access, and the
     * channel carries a stopped, empty state otherwise.
     *
     * On by default: it adds no Android permission of its own, and a head unit that does
     * not advertise the channel, or refuses it, is recorded and skipped.
     */
    val mediaInfo: Boolean = true,
    /**
     * Open one advertised PCM `MEDIA` audio channel, take audio focus, and send app-owned
     * PCM on it (projection only, P5.3). Needs no Android permission: the audio is
     * generated by this app and goes out over USB, so no microphone, no media projection
     * and no other application's stream is involved. Every audio failure is non-fatal, so
     * a head unit that refuses leaves the picture untouched.
     */
    val audio: Boolean = false,
    /**
     * Open the advertised `navigation` channel and drive the head unit's own instrument
     * cluster (projection only, P6.2): with the maneuvers [Guide] publishes for the route
     * being followed (P6.5), or with the session's scripted sequence when
     * [navigationScript] is set. Needs no Android permission.
     *
     * Off by default, like audio: it puts messages on a display that is not the projected
     * one. Every navigation failure — a refused channel, a cluster that never announces
     * itself, a focus request nobody answers — is recorded and skipped.
     */
    val navigation: Boolean = false,
    /** How cluster distances are written: one of [NAVIGATION_UNITS] (projection only). */
    val navigationUnits: String = NAVIGATION_UNITS_IMPERIAL,
    /**
     * Send the scripted sequence even when the head unit announces no cluster and grants
     * no focus (projection only, P6.2). Off by default, which waits for both; setting it
     * turns one drive into the second experiment G9 allows as a degraded pass, and the
     * report says that nothing was waited for.
     */
    val navigationForce: Boolean = false,
    /**
     * Which advertised video configuration to take: one of [VIDEO_PREFERENCES]. A head
     * unit can advertise a configuration it will not display while still acknowledging
     * every unit of it, and nothing on the wire says so, so this is an operator choice.
     */
    val videoPreference: String = VIDEO_PREFERENCE_LARGEST,
    /**
     * Frame rate to encode at, overriding the negotiated one; 0 keeps the negotiated rate.
     * Lowering it also lowers the H.264 level and bitrate the encoder emits, which is how
     * a decoder limit is told apart from a resolution limit.
     */
    val encoderFps: Int = 0,
    /**
     * Which rectangle of the frame the scene is drawn on: one of [SCENE_AREAS]. Purely a
     * rendering choice, so it never reaches the native session.
     *
     * `content` letterboxes the scene into the advertised content rectangle, treating the
     * margins as picture the head unit will not show. `frame` draws across the whole
     * frame, treating the margins as a safe area the head unit overlays instead. The two
     * differ by 9% of the width at 800x480 and by 43% of the frame area at 1280x720 and
     * 1920x1080, which is the size of the open question.
     */
    val sceneArea: String = SCENE_AREA_CONTENT,

    /** Keep a bounded body for each message no profile interprets (diagnosis only). */
    val captureUnhandled: Boolean = false,
    /**
     * Write the encoded Annex B stream to app-private storage so it can be decoded off the
     * phone. The only link in the projection chain that nothing else observes: the head
     * unit acknowledges every unit whether or not it can decode one.
     */
    val captureVideo: Boolean = false,
    /** Open every advertised channel, as Android Auto does (projection only). */
    val openAllChannels: Boolean = false,
    /** Ask for video focus; Android Auto waits for the unsolicited grant instead. */
    val requestFocus: Boolean = true,
    /**
     * What field 1 of the AV setup request carries: one of [SETUP_FIELDS]. The vendored
     * schema calls it the codec type; aasdk calls it the configuration index, and a head
     * unit reading it as an index would decode the wrong configuration.
     */
    val setupField: String = SETUP_FIELD_CODEC,
    /**
     * Play the scripted demonstration on the cluster rather than the maneuvers of the
     * route being followed (projection only; P6.5 made the route the default).
     */
    val navigationScript: Boolean = false,
    /**
     * Restart the video stream when the head unit keeps falling behind (projection only,
     * spec §8.2), as a screen switch does. Always written, so the app never depends on
     * the native default.
     */
    val congestionRestart: Boolean = true,
) {
    companion object {
        const val STOP_AFTER_VERSION = "version"
        const val STOP_AFTER_DISCOVERY = "discovery"
        const val STOP_AFTER_PROJECTION = "projection"
        val STOP_AFTER = listOf(STOP_AFTER_PROJECTION, STOP_AFTER_DISCOVERY, "auth", STOP_AFTER_VERSION)
        val TLS_PROFILES = listOf("tls12-aead", "tls12-cbc-compat")
        const val KEEPALIVE_MIRROR = "mirror"
        val KEEPALIVE_PROFILES = listOf(KEEPALIVE_MIRROR, "plaintext", "encrypted")
        /** The native default: the most content pixels the head unit advertises. */
        const val VIDEO_PREFERENCE_LARGEST = "largest"
        /** `largest`, `smallest`, or a pinned `WIDTHxHEIGHT` frame size. */
        val VIDEO_PREFERENCES =
            listOf(VIDEO_PREFERENCE_LARGEST, "1920x1080", "1280x720", "800x480", "smallest")
        /** 0 keeps the negotiated frame rate. */
        val ENCODER_RATES = listOf(0, 30, 60)
        /**
         * Which scene the app draws. App-side only: the projected picture is just
         * H.264 to the head unit, so none of this reaches the native configuration.
         *
         * The P4 test scene is kept rather than replaced because it is still
         * load-bearing evidence — its numbered buttons, fixed colour patches and
         * calibration crosses are what `tools/lab-receiver` samples in decoded frames
         * and what the G5 touch calibration aims at, and G5 has not passed. A
         * dashboard has no clean colour patch to sample and no cross to aim at.
         */
        const val SCENE_DEMO = "demo"
        const val SCENE_DASHBOARD = "dashboard"
        val SCENES = listOf(SCENE_DASHBOARD, SCENE_DEMO)

        const val SCENE_AREA_CONTENT = "content"
        const val SCENE_AREA_FRAME = "frame"
        val SCENE_AREAS = listOf(SCENE_AREA_CONTENT, SCENE_AREA_FRAME)
        /** Seconds between IDRs; 1 is what this source used, 60 is Android Auto's. */
        val KEYFRAME_SECONDS = listOf(1, 60)
        const val SETUP_FIELD_CODEC = "codec-type"
        val SETUP_FIELDS = listOf(SETUP_FIELD_CODEC, "config-index")
        const val NAVIGATION_UNITS_IMPERIAL = "imperial"
        val NAVIGATION_UNITS = listOf(NAVIGATION_UNITS_IMPERIAL, "metric")
        const val SENSORS_NONE = "none"
        /** Driving status and night data: what P4 subscribed to. */
        const val SENSORS_RESTRICTION = "restriction"
        /** Every advertised type: the P5.2 harvest. */
        const val SENSORS_ALL = "all"
        val SENSOR_SETS = listOf(SENSORS_NONE, SENSORS_RESTRICTION, SENSORS_ALL)
    }
}

/** Receives immutable native session events tagged with the session generation. */
fun interface SessionCallback {
    /** [kind] is [NativeBridge.EVENT_PROGRESS] or [NativeBridge.EVENT_FINISHED] (always last). */
    fun onNativeEvent(generation: Long, kind: Int, report: ByteArray)
}

/** Fields of an event payload or report; later duplicates win. */
fun parseReport(report: ByteArray): Map<String, String> =
    report.toString(Charsets.UTF_8).lineSequence()
        .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.take(it) to line.drop(it + 1) } }
        .toMap()
