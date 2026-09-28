package org.openautoandroid.source

import android.content.Context
import android.content.SharedPreferences

/**
 * Every choice the settings screen makes, as it would be saved (P7 plan §3).
 *
 * Each default is what the screen showed on a fresh launch before P7.1 — the head of each
 * spinner's list, the sensor spinner on `all`, and media info, cluster turn-by-turn and
 * "send regardless" ticked — so the first launch after P7.1 behaves exactly as the build
 * before it did. Those are the *screen's* defaults, which are not all [AttemptSettings]'
 * own: the cluster boxes are ticked here and off there, because the lab intent relies on
 * the latter.
 *
 * What is deliberately not here: "Trust the next head unit", the three capture options,
 * and head-unit position in the report. They are one-shot by design (C2, APP09, spec
 * §10), so they live in [PerAttempt] and are never written anywhere. A saved first-use
 * trust would pin whatever the phone was next plugged into, and a saved position option
 * would put where the vehicle is into every later report without anyone choosing it.
 *
 * Pure Kotlin with no Android API, stored keys included: [AppSettings] only moves
 * [toStored]'s map in and out of [SharedPreferences], so the host test checks the
 * defaults, the stored form's round trip, the repair of stored values, and the one
 * [attempt] builder.
 */
data class SavedSettings(
    val profile: String = PROFILES[0],
    val stopAfter: String = SessionSettings.STOP_AFTER[0],
    val tlsProfile: String = SessionSettings.TLS_PROFILES[0],
    val keepalive: String = SessionSettings.KEEPALIVE_PROFILES[0],
    val openAllChannels: Boolean = false,
    val resumeInPlace: Boolean = false,
    val waitForFocus: Boolean = false,
    val keyframeSeconds: Int = SessionSettings.KEYFRAME_SECONDS[0],
    val setupField: String = SessionSettings.SETUP_FIELDS[0],
    val sensors: String = SessionSettings.SENSORS_ALL,
    val mediaInfo: Boolean = true,
    val audio: Boolean = false,
    val navigation: Boolean = true,
    val navigationUnits: String = SessionSettings.NAVIGATION_UNITS[0],
    val navigationForce: Boolean = true,
    val navigationScript: Boolean = false,
    val softwareEncoder: Boolean = false,
    val videoPreference: String = SessionSettings.VIDEO_PREFERENCES[0],
    val encoderFps: Int = SessionSettings.ENCODER_RATES[0],
    val sceneArea: String = SessionSettings.SCENE_AREAS[0],
    val scene: String = SessionSettings.SCENES[0],
    val mapOnLeft: Boolean = true,
    val speedUnit: SpeedUnit = SpeedUnit.entries[0],
    // P7 settings from here down; none reaches AttemptSettings.
    /** Start an attempt when a matching, already-pinned head unit is plugged in (§2). */
    val autoStart: Boolean = true,
    /** The dashboard header's debug line and, from P7.7, the Diagnostics tab (§6.1). */
    val diagnostics: Boolean = false,
    val markerColour: MarkerColour = MarkerColour.DEFAULT,
    /** Also cycled from the dashboard's ☀/☾ icon, so it is saved from there too (§5.6). */
    val dayNight: DayNightMode = DayNightMode.DEFAULT,
    /** Alerting notifications in the flex tile (§5.8). Off: the listener reads none. */
    val notificationsOnDashboard: Boolean = false,
    /** Package names whose notifications never reach the tile. Never written to a report. */
    val notificationExclusions: Set<String> = emptySet(),
    /** The dashboard's Ⓟ: notifications held back from the tile (§5.8). */
    val notificationsPaused: Boolean = false,
    /** The companion in-call service may be enabled while projecting (§5.8, P7.9). */
    val callControls: Boolean = false,
    // P8 settings (docs/p8-plan.md §7).
    /** A tapped dashboard notification is read aloud; on, though the tile itself is off by default. */
    val readAloud: Boolean = true,
    /** **While speaking**: pause media (the default) or lower its volume (decision 10). */
    val speechMedia: SpeechMedia = SpeechMedia.DEFAULT,
    /**
     * Voice guidance (§5, §7): [VoiceSettings.enabled] is the trip panel's toggle, written
     * on every press like [dayNight]; the rest are P8.3's Maps & routing → Voice guidance
     * screen, stored now with the model's defaults so that screen only has to show them.
     */
    val voice: VoiceSettings = VoiceSettings(),
    /** Maps & routing → Routing: road choices and time estimates ([RouteOptions]). */
    val routing: RouteOptions = RouteOptions(),
) {
    /**
     * This value with every stored choice a list no longer offers put back to its
     * default. A later build can drop a profile or a frame size; the saved one must not
     * then reach the native session as a string it has never been tested with, nor leave
     * a spinner with nothing selected.
     */
    fun repaired(): SavedSettings {
        val d = SavedSettings()
        fun <T> pick(value: T, choices: List<T>, default: T) = if (value in choices) value else default
        return copy(
            profile = pick(profile, PROFILES, d.profile),
            stopAfter = pick(stopAfter, SessionSettings.STOP_AFTER, d.stopAfter),
            tlsProfile = pick(tlsProfile, SessionSettings.TLS_PROFILES, d.tlsProfile),
            keepalive = pick(keepalive, SessionSettings.KEEPALIVE_PROFILES, d.keepalive),
            keyframeSeconds = pick(keyframeSeconds, SessionSettings.KEYFRAME_SECONDS, d.keyframeSeconds),
            setupField = pick(setupField, SessionSettings.SETUP_FIELDS, d.setupField),
            sensors = pick(sensors, SessionSettings.SENSOR_SETS, d.sensors),
            navigationUnits = pick(navigationUnits, SessionSettings.NAVIGATION_UNITS, d.navigationUnits),
            videoPreference = pick(videoPreference, SessionSettings.VIDEO_PREFERENCES, d.videoPreference),
            encoderFps = pick(encoderFps, SessionSettings.ENCODER_RATES, d.encoderFps),
            sceneArea = pick(sceneArea, SessionSettings.SCENE_AREAS, d.sceneArea),
            scene = pick(scene, SessionSettings.SCENES, d.scene),
            notificationExclusions = notificationExclusions.filterTo(HashSet()) { it.isNotBlank() },
            routing = routing.repaired(),
        )
    }

    /** The trip panel's voice toggle pressed (§6): that one field, every other choice kept. */
    fun withVoiceGuidance(on: Boolean): SavedSettings = copy(voice = voice.copy(enabled = on))

    /**
     * The one place outside lab runs that turns saved choices into an attempt, used by
     * both the Connect button and the plug-in start.
     *
     * Every condition is the one `connect()` applied before P7.1, and for the same reason:
     * an option that only means something at a stage the attempt will not reach is sent
     * as off, so a discovery-only or version-only run changes nothing it cannot report on.
     * The saved value itself is kept, so moving the stop-after back restores it.
     */
    fun attempt(pins: List<String>, once: PerAttempt = PerAttempt.NONE): AttemptSettings {
        val projection = stopAfter == SessionSettings.STOP_AFTER_PROJECTION
        val pastVersion = stopAfter != SessionSettings.STOP_AFTER_VERSION
        return AttemptSettings(
            profile = profile,
            stopAfter = stopAfter,
            tlsProfile = tlsProfile,
            armedFirstUse = once.firstUse && pastVersion,
            pins = pins,
            // The discovery response only arrives when the attempt runs discovery.
            captureDiscovery = once.captureDiscovery && stopAfter == SessionSettings.STOP_AFTER_DISCOVERY,
            captureUnhandled = once.captureUnhandled && pastVersion,
            captureVideo = once.captureVideo && projection,
            openAllChannels = openAllChannels && projection,
            rebuildOnRegain = !resumeInPlace,
            requestFocus = !(waitForFocus && projection),
            keyframeSeconds = keyframeSeconds,
            setupField = setupField,
            keepalive = keepalive,
            sensors = if (projection) sensors else SessionSettings.SENSORS_NONE,
            sensorLocation = once.sensorLocation && projection && sensors != SessionSettings.SENSORS_NONE,
            mediaInfo = mediaInfo && projection,
            audio = audio && projection,
            navigation = navigation && projection,
            navigationUnits = navigationUnits,
            navigationForce = navigationForce && navigation && projection,
            navigationScript = navigationScript && navigation,
            softwareEncoder = softwareEncoder && projection,
            videoPreference = videoPreference,
            encoderFps = if (projection) encoderFps else 0,
            sceneArea = sceneArea,
            scene = scene,
            mapOnLeft = mapOnLeft,
            speedUnit = speedUnit,
        )
    }

    /**
     * Every field under its stored key, as a String, Boolean, Int or Set<String>: the
     * types [SharedPreferences] holds. Choices go by their string forms, never by list
     * position (see [AppSettings]).
     */
    fun toStored(): Map<String, Any> {
        val s = repaired()
        return linkedMapOf(
            PROFILE to s.profile,
            STOP_AFTER to s.stopAfter,
            TLS_PROFILE to s.tlsProfile,
            KEEPALIVE to s.keepalive,
            OPEN_ALL_CHANNELS to s.openAllChannels,
            RESUME_IN_PLACE to s.resumeInPlace,
            WAIT_FOR_FOCUS to s.waitForFocus,
            KEYFRAME_SECONDS to s.keyframeSeconds,
            SETUP_FIELD to s.setupField,
            SENSORS to s.sensors,
            MEDIA_INFO to s.mediaInfo,
            AUDIO to s.audio,
            NAVIGATION to s.navigation,
            NAVIGATION_UNITS to s.navigationUnits,
            NAVIGATION_FORCE to s.navigationForce,
            NAVIGATION_SCRIPT to s.navigationScript,
            SOFTWARE_ENCODER to s.softwareEncoder,
            VIDEO_PREFERENCE to s.videoPreference,
            ENCODER_FPS to s.encoderFps,
            SCENE_AREA to s.sceneArea,
            SCENE to s.scene,
            MAP_ON_LEFT to s.mapOnLeft,
            SPEED_UNIT to s.speedUnit.label,
            AUTO_START to s.autoStart,
            DIAGNOSTICS to s.diagnostics,
            MARKER_COLOUR to s.markerColour.key,
            DAY_NIGHT to s.dayNight.key,
            NOTIFICATIONS_ON_DASHBOARD to s.notificationsOnDashboard,
            NOTIFICATION_EXCLUSIONS to s.notificationExclusions.toSet(),
            NOTIFICATIONS_PAUSED to s.notificationsPaused,
            CALL_CONTROLS to s.callControls,
            READ_ALOUD to s.readAloud,
            SPEECH_MEDIA to s.speechMedia.key,
            VOICE_GUIDANCE to s.voice.enabled,
            VOICE_EARLY to s.voice.early,
            VOICE_PREPARE to s.voice.prepare,
            VOICE_AT_TURN to s.voice.atTurn,
            VOICE_TIMING to s.voice.timing.label,
            VOICE_DETAIL to s.voice.detail.label,
            VOICE_STREET_NAMES to s.voice.streetNames,
            VOICE_ANNOUNCE_REROUTE to s.voice.announceReroute,
            VOICE_ANNOUNCE_ARRIVAL to s.voice.announceArrival,
            ROUTE_AVOID_HIGHWAYS to s.routing.avoidHighways,
            ROUTE_AVOID_TOLLS to s.routing.avoidTolls,
            ROUTE_AVOID_UNPAVED to s.routing.avoidUnpaved,
            ROUTE_BYPASS_TOWNS to s.routing.bypassTowns,
            ROUTE_ALLOW_TRACKS to s.routing.allowTracks,
            ROUTE_TOP_SPEED_KMH to s.routing.topSpeedKmh,
            ROUTE_ETA_PERCENT to s.routing.etaPercent,
            ROUTE_LEARN_PACE to s.routing.learnPace,
            ROUTE_SHOW_ALTERNATIVES to s.routing.showAlternatives,
        )
    }

    companion object {
        /**
         * `legacy-1.5-any-minor` only after `legacy-1.5` refused a recorded 1.6+ request;
         * `match-1.7` answers a 1.6-or-later request at 1.7, as Google's Android Auto does
         * and as Ford SYNC 4.0 accepts, and is the default. The legacy profiles downgrade
         * to 1.5; `match-1.6` answers at 1.6.
         */
        val PROFILES = listOf("match-1.7", "legacy-1.5", "legacy-1.5-any-minor", "match-1.6")

        /**
         * Reads what [toStored] wrote, from the preferences' own map. A missing key, or one
         * holding the wrong type (a hand-edited or downgraded file), reads as the default
         * rather than throwing, and the result is [repaired].
         */
        fun fromStored(stored: Map<String, *>): SavedSettings {
            val d = SavedSettings()
            fun text(key: String, default: String) = stored[key] as? String ?: default
            fun flag(key: String, default: Boolean) = stored[key] as? Boolean ?: default
            fun number(key: String, default: Int) = stored[key] as? Int ?: default
            return SavedSettings(
                profile = text(PROFILE, d.profile),
                stopAfter = text(STOP_AFTER, d.stopAfter),
                tlsProfile = text(TLS_PROFILE, d.tlsProfile),
                keepalive = text(KEEPALIVE, d.keepalive),
                openAllChannels = flag(OPEN_ALL_CHANNELS, d.openAllChannels),
                resumeInPlace = flag(RESUME_IN_PLACE, d.resumeInPlace),
                waitForFocus = flag(WAIT_FOR_FOCUS, d.waitForFocus),
                keyframeSeconds = number(KEYFRAME_SECONDS, d.keyframeSeconds),
                setupField = text(SETUP_FIELD, d.setupField),
                sensors = text(SENSORS, d.sensors),
                mediaInfo = flag(MEDIA_INFO, d.mediaInfo),
                audio = flag(AUDIO, d.audio),
                navigation = flag(NAVIGATION, d.navigation),
                navigationUnits = text(NAVIGATION_UNITS, d.navigationUnits),
                navigationForce = flag(NAVIGATION_FORCE, d.navigationForce),
                navigationScript = flag(NAVIGATION_SCRIPT, d.navigationScript),
                softwareEncoder = flag(SOFTWARE_ENCODER, d.softwareEncoder),
                videoPreference = text(VIDEO_PREFERENCE, d.videoPreference),
                encoderFps = number(ENCODER_FPS, d.encoderFps),
                sceneArea = text(SCENE_AREA, d.sceneArea),
                scene = text(SCENE, d.scene),
                mapOnLeft = flag(MAP_ON_LEFT, d.mapOnLeft),
                speedUnit = SpeedUnit.entries.firstOrNull { it.label == stored[SPEED_UNIT] } ?: d.speedUnit,
                autoStart = flag(AUTO_START, d.autoStart),
                diagnostics = flag(DIAGNOSTICS, d.diagnostics),
                markerColour = MarkerColour.of(stored[MARKER_COLOUR] as? String),
                dayNight = DayNightMode.of(stored[DAY_NIGHT] as? String),
                notificationsOnDashboard = flag(NOTIFICATIONS_ON_DASHBOARD, d.notificationsOnDashboard),
                notificationExclusions = (stored[NOTIFICATION_EXCLUSIONS] as? Set<*>)
                    ?.filterIsInstance<String>()?.toSet() ?: d.notificationExclusions,
                notificationsPaused = flag(NOTIFICATIONS_PAUSED, d.notificationsPaused),
                callControls = flag(CALL_CONTROLS, d.callControls),
                readAloud = flag(READ_ALOUD, d.readAloud),
                speechMedia = SpeechMedia.of(stored[SPEECH_MEDIA] as? String),
                voice = VoiceSettings(
                    enabled = flag(VOICE_GUIDANCE, d.voice.enabled),
                    early = flag(VOICE_EARLY, d.voice.early),
                    prepare = flag(VOICE_PREPARE, d.voice.prepare),
                    atTurn = flag(VOICE_AT_TURN, d.voice.atTurn),
                    timing = PromptTiming.entries.firstOrNull { it.label == stored[VOICE_TIMING] } ?: d.voice.timing,
                    detail = PromptDetail.entries.firstOrNull { it.label == stored[VOICE_DETAIL] } ?: d.voice.detail,
                    streetNames = flag(VOICE_STREET_NAMES, d.voice.streetNames),
                    announceReroute = flag(VOICE_ANNOUNCE_REROUTE, d.voice.announceReroute),
                    announceArrival = flag(VOICE_ANNOUNCE_ARRIVAL, d.voice.announceArrival),
                ),
                routing = RouteOptions(
                    avoidHighways = flag(ROUTE_AVOID_HIGHWAYS, d.routing.avoidHighways),
                    avoidTolls = flag(ROUTE_AVOID_TOLLS, d.routing.avoidTolls),
                    avoidUnpaved = flag(ROUTE_AVOID_UNPAVED, d.routing.avoidUnpaved),
                    bypassTowns = flag(ROUTE_BYPASS_TOWNS, d.routing.bypassTowns),
                    allowTracks = flag(ROUTE_ALLOW_TRACKS, d.routing.allowTracks),
                    topSpeedKmh = number(ROUTE_TOP_SPEED_KMH, d.routing.topSpeedKmh),
                    etaPercent = number(ROUTE_ETA_PERCENT, d.routing.etaPercent),
                    learnPace = flag(ROUTE_LEARN_PACE, d.routing.learnPace),
                    showAlternatives = flag(ROUTE_SHOW_ALTERNATIVES, d.routing.showAlternatives),
                ),
            ).repaired()
        }

        // Stored keys. Never renamed once shipped: a renamed key reads back as the default.
        private const val PROFILE = "profile"
        private const val STOP_AFTER = "stop_after"
        private const val TLS_PROFILE = "tls_profile"
        private const val KEEPALIVE = "keepalive"
        private const val OPEN_ALL_CHANNELS = "open_all_channels"
        private const val RESUME_IN_PLACE = "resume_in_place"
        private const val WAIT_FOR_FOCUS = "wait_for_focus"
        private const val KEYFRAME_SECONDS = "keyframe_seconds"
        private const val SETUP_FIELD = "setup_field"
        private const val SENSORS = "sensors"
        private const val MEDIA_INFO = "media_info"
        private const val AUDIO = "audio"
        private const val NAVIGATION = "navigation"
        private const val NAVIGATION_UNITS = "navigation_units"
        private const val NAVIGATION_FORCE = "navigation_force"
        private const val NAVIGATION_SCRIPT = "navigation_script"
        private const val SOFTWARE_ENCODER = "software_encoder"
        private const val VIDEO_PREFERENCE = "video_preference"
        private const val ENCODER_FPS = "encoder_fps"
        private const val SCENE_AREA = "scene_area"
        private const val SCENE = "scene"
        private const val MAP_ON_LEFT = "map_on_left"
        private const val SPEED_UNIT = "speed_unit"
        private const val AUTO_START = "auto_start"
        private const val DIAGNOSTICS = "diagnostics"
        private const val MARKER_COLOUR = "marker_colour"
        private const val DAY_NIGHT = "day_night"
        private const val NOTIFICATIONS_ON_DASHBOARD = "notifications_on_dashboard"
        private const val NOTIFICATION_EXCLUSIONS = "notification_exclusions"
        private const val NOTIFICATIONS_PAUSED = "notifications_paused"
        private const val CALL_CONTROLS = "call_controls"
        private const val READ_ALOUD = "read_aloud"
        private const val SPEECH_MEDIA = "speech_media"
        private const val VOICE_GUIDANCE = "voice_guidance"
        private const val VOICE_EARLY = "voice_early"
        private const val VOICE_PREPARE = "voice_prepare"
        private const val VOICE_AT_TURN = "voice_at_turn"
        private const val VOICE_TIMING = "voice_timing"
        private const val VOICE_DETAIL = "voice_detail"
        private const val VOICE_STREET_NAMES = "voice_street_names"
        private const val VOICE_ANNOUNCE_REROUTE = "voice_announce_reroute"
        private const val VOICE_ANNOUNCE_ARRIVAL = "voice_announce_arrival"
        private const val ROUTE_AVOID_HIGHWAYS = "route_avoid_highways"
        private const val ROUTE_AVOID_TOLLS = "route_avoid_tolls"
        private const val ROUTE_AVOID_UNPAVED = "route_avoid_unpaved"
        private const val ROUTE_BYPASS_TOWNS = "route_bypass_towns"
        private const val ROUTE_ALLOW_TRACKS = "route_allow_tracks"
        private const val ROUTE_TOP_SPEED_KMH = "route_top_speed_kmh"
        private const val ROUTE_ETA_PERCENT = "route_eta_percent"
        private const val ROUTE_LEARN_PACE = "route_learn_pace"
        private const val ROUTE_SHOW_ALTERNATIVES = "route_show_alternatives"
    }
}

/**
 * The options that apply to one attempt and are never saved (P7 plan §3): first-use
 * trust, the three captures, and head-unit position in the report. Only the Connect
 * button sets them, and it clears the trust and position boxes once the attempt begins;
 * a plug-in start always uses [NONE], so an unattended start can neither pin a
 * certificate, write a capture, nor record where the vehicle is.
 */
data class PerAttempt(
    val firstUse: Boolean = false,
    val captureDiscovery: Boolean = false,
    val captureUnhandled: Boolean = false,
    val captureVideo: Boolean = false,
    /** Head-unit latitude and longitude in the report (APP09, spec §10). */
    val sensorLocation: Boolean = false,
) {
    companion object {
        val NONE = PerAttempt()
    }
}

/**
 * Whether a USB attach should start an attempt on its own (P7 plan §2), and if not, why.
 * The [reason] is what the event log records: it names a rule, never an accessory, a pin
 * or a head unit.
 */
enum class AutoStart(val reason: String) {
    START("accessory matches the filter and a head unit is pinned"),
    /**
     * Everything else allows a start but the source identity is still being loaded. The
     * normal case, not a corner: between drives the process is usually gone, the attach
     * starts a fresh one, and the identity loads on a worker thread after the activity
     * is already handling the attach. The caller holds the start and decides again when
     * the identity settles, for a bounded time.
     */
    WAIT("the source identity is still loading; waiting for it"),
    /** Anything but idle: an armed attempt takes the attach its own way, as before. */
    BUSY("an attempt is already in progress"),
    OFF("start when plugged in is off"),
    NO_ACCESSORY("no accessory is enumerated"),
    /** Shown, never claimed automatically, as a mismatch always has been. */
    MISMATCH("the accessory does not match the filter; manual use required"),
    /** First use stays manual: an unknown USB host must never be pinned on its own. */
    NO_PINS("no head unit is pinned yet; first use stays manual"),
    /**
     * The saved stop-after is a diagnostic stage. Plugging into the truck must not
     * silently run a discovery-only or auth-only hold that leaves the screen dark;
     * Connect still runs whatever is selected.
     */
    NOT_PROJECTION("the saved stop-after is not projection; use Connect for a diagnostic run"),
    /** The identity finished loading and failed; waiting would not change that. */
    IDENTITY_FAILED("the source identity failed to load or self-test"),
    ;

    /** How the attached accessory enumerated, without the Android type that holds it. */
    enum class Attached { NONE, MISMATCH, MATCH }

    /** Where the source identity is: loaded and self-tested, still loading, or failed. */
    enum class Identity { READY, LOADING, FAILED }

    companion object {
        /**
         * The rule, in the order the reasons are checked. Pure, so the host test covers
         * every branch; the activity only gathers the inputs. The identity comes last so
         * that [WAIT] is only ever returned for a start that would otherwise go ahead.
         */
        fun decide(
            stage: Stage,
            enabled: Boolean,
            attached: Attached,
            pins: Int,
            projection: Boolean,
            identity: Identity,
        ): AutoStart =
            when {
                stage != Stage.IDLE -> BUSY
                !enabled -> OFF
                attached == Attached.NONE -> NO_ACCESSORY
                attached == Attached.MISMATCH -> MISMATCH
                pins <= 0 -> NO_PINS
                !projection -> NOT_PROJECTION
                identity == Identity.LOADING -> WAIT
                identity == Identity.FAILED -> IDENTITY_FAILED
                else -> START
            }

        /** How long a held start waits for the identity before giving up. */
        const val WAIT_MS = 30_000L
    }
}

/**
 * [SavedSettings] kept in the app's private preferences, the way [MapPreferences] keeps
 * the map's orientation (P7 plan §3).
 *
 * The single source of [AttemptSettings] outside lab runs. `handleLabIntent` builds its
 * own from the intent's extras and never reads or writes these, so a lab run cannot
 * leave the truck with lab settings.
 *
 * Choices are stored by their string forms — the spinner values, [SpeedUnit.label],
 * [MarkerColour.key], [DayNightMode.key], [SpeechMedia.key], [PromptTiming.label],
 * [PromptDetail.label] — never by list position, so reordering a list in a later build
 * cannot silently turn one saved choice into another.
 * Whatever reads back is [SavedSettings.repaired].
 *
 * Writes use [SharedPreferences.Editor.apply], which returns at once, so the dashboard
 * may save from under its own lock as it does the orientation.
 */
object AppSettings {
    private const val FILE = "settings"

    /** Everything saved, with today's defaults for anything never saved. */
    fun load(context: Context): SavedSettings = SavedSettings.fromStored(prefs(context).all)

    /** Stores every field of [settings]. Asynchronous; see the class comment. */
    fun save(context: Context, settings: SavedSettings) {
        val editor = prefs(context).edit()
        for ((key, value) in settings.toStored()) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toHashSet())
            }
        }
        editor.apply()
    }

    /**
     * Reads, changes one thing, and stores: how every caller should write, e.g.
     * `AppSettings.update(context) { it.copy(dayNight = it.dayNight.next()) }`.
     *
     * Synchronized because the settings screen (main thread) and the dashboard (its own
     * thread, for ☀/☾ and Ⓟ) both write, and two unsynchronized read-modify-writes would
     * let one undo the other. [SharedPreferences] serves reads from memory after the
     * first, so this holds the lock for no I/O.
     */
    @Synchronized
    fun update(context: Context, change: (SavedSettings) -> SavedSettings): SavedSettings {
        val next = change(load(context)).repaired()
        save(context, next)
        return next
    }

    /**
     * The palette the last projected session ended in (P7.6), or null when none has been
     * recorded: what Automatic draws until the head unit's first NIGHT_DATA
     * ([Dashboard.resolveNight]).
     *
     * Deliberately not a [SavedSettings] field: it is something the dashboard observed,
     * not a choice the screen makes, so it has no control, is never reset by a screen
     * write, and stays out of the stored form the host test pins.
     */
    fun lastNight(context: Context): Boolean? {
        val prefs = prefs(context)
        return if (prefs.contains(LAST_NIGHT)) prefs.getBoolean(LAST_NIGHT, true) else null
    }

    /** Records [lastNight]. Asynchronous, so safe from under the dashboard lock. */
    fun saveLastNight(context: Context, night: Boolean) {
        prefs(context).edit().putBoolean(LAST_NIGHT, night).apply()
    }

    private const val LAST_NIGHT = "last_resolved_night"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
