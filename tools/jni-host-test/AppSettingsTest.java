// P7.1's saved settings and plug-in start on a desktop JVM: that a first launch builds the
// same attempt the settings screen built before anything was saved, that every stage
// condition connect() applied survives the move into SavedSettings.attempt, that the
// stored form reads back whole and repairs what a later build no longer offers, the
// dashboard's marker colours and day/night cycle, and every branch of the auto-start rule.
// SavedSettings, PerAttempt, AutoStart, MarkerColour and DayNightMode are pure Kotlin; the
// SharedPreferences file around them, the attach intent, and the foreground-service start
// need a device.
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.openautoandroid.source.AlertLevel;
import org.openautoandroid.source.AttemptSettings;
import org.openautoandroid.source.AutoStart;
import org.openautoandroid.source.ClockFormat;
import org.openautoandroid.source.DayNightMode;
import org.openautoandroid.source.MarkerColour;
import org.openautoandroid.source.PerAttempt;
import org.openautoandroid.source.PromptDetail;
import org.openautoandroid.source.PromptTiming;
import org.openautoandroid.source.SavedSettings;
import org.openautoandroid.source.SessionSettings;
import org.openautoandroid.source.SpeechMedia;
import org.openautoandroid.source.SpeedUnit;
import org.openautoandroid.source.Stage;
import org.openautoandroid.source.VoiceSettings;
import org.openautoandroid.source.WeatherSettings;

public final class AppSettingsTest {
    private static int passed;
    private static final List<String> PINS = Collections.singletonList("pin");

    public static void main(String[] args) {
        aFirstLaunchBuildsTodaysAttempt();
        theNewSettingsStartWhereThePlanSays();
        stageConditionsSurviveTheMove();
        perAttemptOptionsAreOnlyEverOneShot();
        theStoredFormReadsBackWhole();
        aStoredValueNoLongerOfferedReadsAsTheDefault();
        voiceGuidanceIsStoredWhole();
        weatherIsStoredWhole();
        markerColoursHaveStableKeysAndARim();
        clockFormatWritesTimes();
        dayNightCyclesAndResolves();
        autoStartNeedsEveryCondition();
        autoStartSaysWhyNotInOrder();
        System.out.println("jni-host-test: ok " + passed + " saved settings and auto-start checks");
    }

    private static SavedSettings stored(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return SavedSettings.Companion.fromStored(map);
    }

    /**
     * Nothing saved must mean exactly what the screen showed before P7.1: each spinner on
     * the head of its list, sensors on all, media info, the cluster and "send regardless"
     * ticked. Written out field by field rather than compared with a second builder, so a
     * changed default shows up here as the line that changed.
     */
    private static void aFirstLaunchBuildsTodaysAttempt() {
        SavedSettings fresh = SavedSettings.Companion.fromStored(new HashMap<String, Object>());
        check(fresh.equals(new SavedSettings()), "an empty store reads as the defaults");
        AttemptSettings a = fresh.attempt(PINS, PerAttempt.Companion.getNONE());
        check("match-1.7".equals(a.getProfile()), "profile match-1.7");
        check(SessionSettings.STOP_AFTER_PROJECTION.equals(a.getStopAfter()), "stops after projection");
        check("tls12-aead".equals(a.getTlsProfile()), "TLS profile tls12-aead");
        check(!a.getArmedFirstUse(), "first use not armed");
        check(a.getPins().equals(PINS), "the pins passed in are the attempt's");
        check(!a.getCaptureDiscovery() && !a.getCaptureUnhandled() && !a.getCaptureVideo(), "no capture");
        check(!a.getOpenAllChannels(), "open all channels off");
        check(a.getRebuildOnRegain(), "encoder rebuilt on regain");
        check(a.getRequestFocus(), "video focus requested");
        check(a.getKeyframeSeconds() == 1, "keyframe every second");
        check(SessionSettings.SETUP_FIELD_CODEC.equals(a.getSetupField()), "setup field codec-type");
        check(SessionSettings.KEEPALIVE_MIRROR.equals(a.getKeepalive()), "keepalive mirror");
        check(a.getProjectionMs() == null, "projection until Stop");
        check(SessionSettings.SENSORS_ALL.equals(a.getSensors()), "every advertised sensor");
        check(!a.getSensorLocation(), "no position in the report");
        check(a.getMediaInfo(), "media info on");
        check(!a.getAudio(), "audio out off");
        check(a.getNavigation(), "cluster turn-by-turn on");
        check(SessionSettings.NAVIGATION_UNITS_IMPERIAL.equals(a.getNavigationUnits()), "imperial cluster");
        check(a.getNavigationForce(), "send regardless on (Ford never announces a cluster)");
        check(!a.getNavigationScript(), "the route, not the script");
        check(!a.getSoftwareEncoder(), "hardware encoder");
        check(SessionSettings.VIDEO_PREFERENCE_LARGEST.equals(a.getVideoPreference()), "largest video");
        check(a.getEncoderFps() == 0, "negotiated frame rate");
        check(SessionSettings.SCENE_AREA_CONTENT.equals(a.getSceneArea()), "content area");
        check(SessionSettings.SCENE_DASHBOARD.equals(a.getScene()), "the dashboard");
        check(a.getMapOnLeft(), "map on the left");
        check(a.getSpeedUnit() == SpeedUnit.MPH, "mph");
        check(a.getMapFile() == null && a.getLabSocket() == null, "newest map, USB transport");
    }

    private static void theNewSettingsStartWhereThePlanSays() {
        SavedSettings d = new SavedSettings();
        check(d.getAutoStart(), "start when plugged in: on by default");
        check(!d.getDiagnostics(), "diagnostics off");
        check(d.getMarkerColour() == MarkerColour.BLUE, "marker blue");
        check(d.getDayNight() == DayNightMode.AUTO, "day/night automatic");
        check(!d.getNotificationsOnDashboard(), "notifications off");
        check(d.getNotificationExclusions().isEmpty(), "nothing excluded");
        check(!d.getNotificationsPaused(), "not paused");
        check(!d.getCallControls(), "call controls off");
        // P8.1 (docs/p8-plan.md section 7).
        check(d.getReadAloud(), "read aloud when tapped: on");
        check(d.getSpeechMedia() == SpeechMedia.PAUSE, "while speaking: pause media");
        // P8.2 (section 6 and 7): the model's own defaults, every stage heard.
        VoiceSettings v = d.getVoice();
        check(v.equals(new VoiceSettings()), "voice guidance: the model's defaults");
        check(v.getEnabled(), "voice guidance: on");
        check(v.getEarly() && v.getPrepare() && v.getAtTurn(), "every stage on");
        check(v.getTiming() == PromptTiming.NORMAL, "timing normal");
        check(v.getDetail() == PromptDetail.FULL, "detail full");
        check(v.getStreetNames() && v.getAnnounceReroute() && v.getAnnounceArrival(), "street names, reroute and arrival on");
        Map<String, Object> form = d.toStored();
        check(Boolean.TRUE.equals(form.get("voice_guidance")) && "normal".equals(form.get("voice_timing"))
                && "full".equals(form.get("voice_detail")), "and stored so on a first launch");
    }

    /**
     * P8.2: every voice field under its own key, round trip, the toggle alone, by name, and
     * an unknown timing or detail read as the default. P8.3's screens build on these keys.
     */
    private static void voiceGuidanceIsStoredWhole() {
        SavedSettings changed = stored(
                "voice_guidance", false, "voice_early", false, "voice_prepare", false, "voice_at_turn", false,
                "voice_timing", "later", "voice_detail", "brief", "voice_street_names", false,
                "voice_announce_reroute", false, "voice_announce_arrival", false);
        VoiceSettings all = new VoiceSettings(false, false, false, false, PromptTiming.LATER, PromptDetail.BRIEF,
                false, false, false);
        check(changed.getVoice().equals(all), "every voice key read: " + changed.getVoice());
        Map<String, Object> form = changed.toStored();
        for (String key : new String[] {"voice_guidance", "voice_early", "voice_prepare", "voice_at_turn", "voice_timing",
                "voice_detail", "voice_street_names", "voice_announce_reroute", "voice_announce_arrival"}) {
            check(form.containsKey(key), "stored under " + key);
        }
        check("later".equals(form.get("voice_timing")) && "brief".equals(form.get("voice_detail")),
                "timing and detail stored by name");
        check(SavedSettings.Companion.fromStored(form).equals(changed), "the voice settings read back whole");
        // The trip panel's press writes the toggle and leaves the rest as they were.
        SavedSettings earlier = stored("voice_timing", "earlier");
        SavedSettings muted = earlier.withVoiceGuidance(false);
        Map<String, Object> mutedForm = muted.toStored();
        check(Boolean.FALSE.equals(mutedForm.get("voice_guidance")) && "earlier".equals(mutedForm.get("voice_timing")),
                "a toggle press stores off and keeps the timing");
        check(!SavedSettings.Companion.fromStored(mutedForm).getVoice().getEnabled(), "and reads back off: a route started muted stays muted");
        SavedSettings odd = stored("voice_timing", "soonish", "voice_detail", "verbose", "voice_guidance", "yes");
        check(odd.getVoice().equals(new VoiceSettings()), "an unknown timing, detail or wrong type reads as the default");
    }

    /**
     * P10 (docs/p10-plan.md section 4): Show weather off by default (APP09), alerts spoken
     * and shown from Severe; the three keys round trip, the level by name, and an unknown
     * level or a wrong type reads as the default.
     */
    private static void weatherIsStoredWhole() {
        WeatherSettings d = new SavedSettings().getWeather();
        check(d.equals(new WeatherSettings()), "weather: the model's defaults");
        check(!d.getEnabled(), "show weather: off");
        check(d.getSpeakAlerts(), "speak weather alerts: on");
        check(d.getAlertLevel() == AlertLevel.SEVERE, "alerts shown from severe");
        Map<String, Object> defaults = new SavedSettings().toStored();
        check(Boolean.FALSE.equals(defaults.get("weather_enabled")) && Boolean.TRUE.equals(defaults.get("weather_speak_alerts"))
                && "severe".equals(defaults.get("weather_alert_level")), "and stored so on a first launch");
        SavedSettings changed = stored("weather_enabled", true, "weather_speak_alerts", false, "weather_alert_level", "minor");
        check(changed.getWeather().equals(new WeatherSettings(true, false, AlertLevel.MINOR)),
                "every weather key read: " + changed.getWeather());
        Map<String, Object> form = changed.toStored();
        check("minor".equals(form.get("weather_alert_level")), "the level stored by name");
        check(SavedSettings.Companion.fromStored(form).equals(changed), "the weather settings read back whole");
        for (AlertLevel level : AlertLevel.values()) {
            check(stored("weather_alert_level", level.getKey()).getWeather().getAlertLevel() == level,
                    "the key round-trips: " + level);
        }
        SavedSettings odd = stored("weather_alert_level", "apocalyptic", "weather_enabled", "yes", "weather_speak_alerts", 1);
        check(odd.getWeather().equals(new WeatherSettings()), "an unknown level or wrong type reads as the default");
        check(AlertLevel.Companion.getLABELS().size() == 4
                && AlertLevel.Companion.getLABELS().get(AlertLevel.SEVERE.ordinal()).equals("Severe"),
                "the card offers four, in AlertLevel's order");
    }

    /** Each condition connect() applied before P7.1, one stop-after at a time. */
    private static void stageConditionsSurviveTheMove() {
        PerAttempt all = new PerAttempt(true, true, true, true, true);
        SavedSettings everything = stored(
                "open_all_channels", true, "wait_for_focus", true,
                "audio", true, "navigation_script", true, "software_encoder", true, "encoder_fps", 30);

        AttemptSettings projection = everything.attempt(PINS, all);
        check(projection.getArmedFirstUse() && projection.getCaptureUnhandled() && projection.getCaptureVideo(),
                "projection: trust, unhandled and video captures pass");
        check(!projection.getCaptureDiscovery(), "projection: the discovery capture needs a discovery-only run");
        check(projection.getOpenAllChannels() && !projection.getRequestFocus(), "projection: channels and focus pass");
        check(projection.getSensorLocation() && projection.getAudio() && projection.getSoftwareEncoder(),
                "projection: position, audio and software encoder pass");
        check(projection.getNavigationForce() && projection.getNavigationScript(), "projection: cluster options pass");
        check(projection.getEncoderFps() == 30, "projection: the frame rate passes");

        SavedSettings discoveryOnly = stored(
                "stop_after", SessionSettings.STOP_AFTER_DISCOVERY, "open_all_channels", true, "wait_for_focus", true,
                "audio", true, "navigation_script", true, "software_encoder", true, "encoder_fps", 30);
        AttemptSettings discovery = discoveryOnly.attempt(PINS, all);
        check(discovery.getArmedFirstUse() && discovery.getCaptureDiscovery() && discovery.getCaptureUnhandled(),
                "discovery: trust, discovery and unhandled captures pass");
        check(!discovery.getCaptureVideo() && !discovery.getOpenAllChannels() && discovery.getRequestFocus(),
                "discovery: no video capture, no extra channels, focus requested");
        check(SessionSettings.SENSORS_NONE.equals(discovery.getSensors()) && !discovery.getSensorLocation(),
                "discovery: no sensors and so no position");
        check(!discovery.getMediaInfo() && !discovery.getAudio() && !discovery.getNavigation(),
                "discovery: no media, audio or cluster");
        check(!discovery.getNavigationForce(), "discovery: nothing to force");
        // As before: the script follows the cluster box, not the stage.
        check(discovery.getNavigationScript(), "discovery: the script box follows the cluster box only");
        check(!discovery.getSoftwareEncoder() && discovery.getEncoderFps() == 0, "discovery: no encoder options");

        AttemptSettings version = stored("stop_after", SessionSettings.STOP_AFTER_VERSION).attempt(PINS, all);
        check(!version.getArmedFirstUse() && !version.getCaptureUnhandled() && !version.getCaptureDiscovery(),
                "version: no trust and no capture, since there is no TLS");

        AttemptSettings noSensors = stored("sensors", SessionSettings.SENSORS_NONE)
                .attempt(PINS, new PerAttempt(false, false, false, false, true));
        check(!noSensors.getSensorLocation(), "no sensors: position stays off");

        AttemptSettings noCluster = stored("navigation", false, "navigation_script", true)
                .attempt(PINS, PerAttempt.Companion.getNONE());
        check(!noCluster.getNavigation() && !noCluster.getNavigationForce() && !noCluster.getNavigationScript(),
                "cluster off: force and script go with it");

        AttemptSettings inPlace = stored("resume_in_place", true).attempt(PINS, PerAttempt.Companion.getNONE());
        check(!inPlace.getRebuildOnRegain(), "resume in place clears the rebuild");

        // Spec 8.2: restarting the stream when the head unit keeps falling behind is on
        // unless cleared.
        check(new SavedSettings().attempt(PINS, PerAttempt.Companion.getNONE()).getCongestionRestart(),
                "the congestion restart is on by default");
        check(!stored("congestion_restart", false).attempt(PINS, PerAttempt.Companion.getNONE()).getCongestionRestart(),
                "and can be cleared");

        // The saved value is kept even while the stage hides it.
        check(discoveryOnly.getAudio() && discoveryOnly.getEncoderFps() == 30, "a hidden choice is still saved");
    }

    /** The plug-in start passes NONE; nothing one-shot is ever in the stored form. */
    private static void perAttemptOptionsAreOnlyEverOneShot() {
        AttemptSettings plugIn = new SavedSettings().attempt(PINS, PerAttempt.Companion.getNONE());
        check(!plugIn.getArmedFirstUse(), "a plug-in start never arms first use");
        check(!plugIn.getCaptureDiscovery() && !plugIn.getCaptureUnhandled() && !plugIn.getCaptureVideo(),
                "nor any capture");
        check(!plugIn.getSensorLocation(), "nor position in the report");
        for (String key : new SavedSettings().toStored().keySet()) {
            check(!key.contains("first_use") && !key.contains("trust") && !key.contains("capture")
                    && !key.contains("location"), "no one-shot option is stored: " + key);
        }
        // APP09, spec section 10: a stale key from an earlier build turns nothing on.
        check(!stored("sensor_location", true).attempt(PINS, PerAttempt.Companion.getNONE()).getSensorLocation(),
                "a stored position option is ignored");
    }

    private static void theStoredFormReadsBackWhole() {
        Set<String> excluded = new HashSet<>(Arrays.asList("com.example.a", "com.example.b"));
        SavedSettings changed = stored(
                "profile", "legacy-1.5", "stop_after", SessionSettings.STOP_AFTER_DISCOVERY,
                "tls_profile", "tls12-cbc-compat", "keepalive", "encrypted", "open_all_channels", true,
                "resume_in_place", true, "congestion_restart", false, "wait_for_focus", true, "keyframe_seconds", 60,
                "setup_field", "config-index", "sensors", SessionSettings.SENSORS_RESTRICTION,
                "media_info", false, "audio", true, "navigation", false,
                "navigation_units", "metric", "navigation_force", false, "navigation_script", true,
                "software_encoder", true, "video_preference", "800x480", "encoder_fps", 60,
                "scene_area", SessionSettings.SCENE_AREA_FRAME, "scene", SessionSettings.SCENE_DEMO,
                "map_on_left", false, "speed_unit", "km/h", "auto_start", false, "diagnostics", true,
                "marker_colour", "magenta", "clock_format", "12", "day_night", "night", "notifications_on_dashboard", true,
                "notification_exclusions", excluded, "notifications_paused", true, "call_controls", true,
                "read_aloud", false, "speech_media", "duck");
        SavedSettings d = new SavedSettings();
        // Every field moved off its default, so a key that failed to read would show.
        check(!changed.getProfile().equals(d.getProfile()) && changed.getKeyframeSeconds() == 60
                && changed.getSpeedUnit() == SpeedUnit.KMH && changed.getMarkerColour() == MarkerColour.MAGENTA
                && changed.getClockFormat() == ClockFormat.H12
                && changed.getDayNight() == DayNightMode.NIGHT && changed.getNotificationExclusions().equals(excluded)
                && !changed.getAutoStart() && changed.getCallControls() && !changed.getMapOnLeft()
                && !changed.getReadAloud() && changed.getSpeechMedia() == SpeechMedia.DUCK
                && !changed.getCongestionRestart(),
                "every key read: " + changed);
        Map<String, Object> form = changed.toStored();
        check(form.size() == 56, "56 fields stored, one key each: " + form.size());
        check(SavedSettings.Companion.fromStored(form).equals(changed), "the stored form reads back whole");
        check(SavedSettings.Companion.fromStored(d.toStored()).equals(d), "and so do the defaults");
        for (Object value : form.values()) {
            check(value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Set,
                    "only types SharedPreferences holds: " + value);
        }
        check("km/h".equals(form.get("speed_unit")) && "magenta".equals(form.get("marker_colour"))
                && "night".equals(form.get("day_night")) && "duck".equals(form.get("speech_media"))
                && "12".equals(form.get("clock_format")), "choices are stored by name, not by position");
    }

    private static void aStoredValueNoLongerOfferedReadsAsTheDefault() {
        SavedSettings d = new SavedSettings();
        SavedSettings odd = stored(
                "profile", "legacy-9", "stop_after", "moon", "keyframe_seconds", 7, "encoder_fps", 45,
                "video_preference", "4000x3000", "sensors", "some", "speed_unit", "knots",
                "marker_colour", "plaid", "clock_format", "13", "day_night", "dusk", "speech_media", "shout");
        check(odd.equals(d), "unknown choices read as the defaults: " + odd);
        SavedSettings wrongTypes = stored("profile", 3, "audio", "yes", "keyframe_seconds", "60",
                "notification_exclusions", "com.example.a");
        check(wrongTypes.equals(d), "a key holding the wrong type reads as the default: " + wrongTypes);
        Set<String> withBlank = new HashSet<>(Arrays.asList("com.example.a", " ", ""));
        check(stored("notification_exclusions", withBlank).getNotificationExclusions()
                .equals(Collections.singleton("com.example.a")), "a blank exclusion is dropped");
    }

    private static void markerColoursHaveStableKeysAndARim() {
        check(MarkerColour.values().length == 7, "seven swatches");
        check(MarkerColour.Companion.getDEFAULT() == MarkerColour.BLUE, "blue is the default");
        check(MarkerColour.BLUE.getFill() == 0xFF4E9AF1, "blue is today's accent");
        check(MarkerColour.BLUE.getRim() == 0xB39AA5B1, "and keeps today's rim");
        Set<String> keys = new HashSet<>();
        for (MarkerColour c : MarkerColour.values()) {
            check(MarkerColour.Companion.of(c.getKey()) == c, "the key round-trips: " + c);
            check(keys.add(c.getKey()), "keys are distinct: " + c);
            check((c.getFill() >>> 24) == 0xFF, "the fill is opaque: " + c);
            check(c.getFill() != c.getRim(), "the rim differs from the fill: " + c);
            // Blue keeps today's translucent grey rim, which the review found visible
            // enough (P7 plan section 1 item 12); every new swatch gets one of clear contrast.
            if (c != MarkerColour.BLUE) {
                check(Math.abs(luminance(c.getFill()) - luminance(c.getRim())) > 0.25, "the rim contrasts: " + c);
            }
        }
        check(MarkerColour.Companion.of(null) == MarkerColour.BLUE, "nothing stored is blue");
        check(MarkerColour.Companion.of("plaid") == MarkerColour.BLUE, "an unknown key is blue");
        check(MarkerColour.Companion.getLABELS().size() == 7
                && MarkerColour.Companion.getLABELS().get(0).equals(MarkerColour.BLUE.getLabel()),
                "the screen offers all seven, blue first");
    }

    private static void clockFormatWritesTimes() {
        ZoneId utc = ZoneOffset.UTC;
        long evening = Instant.parse("2026-09-30T18:40:59Z").toEpochMilli();
        long morning = Instant.parse("2026-09-30T00:05:00Z").toEpochMilli();
        long noon = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli();
        check(ClockFormat.Companion.getDEFAULT() == ClockFormat.H24 && new SavedSettings().getClockFormat() == ClockFormat.H24,
                "24-hour by default, as the dashboard drew before the choice");
        check("18:40".equals(ClockFormat.H24.time(evening, utc)) && "00:05".equals(ClockFormat.H24.time(morning, utc)),
                "24-hour times: " + ClockFormat.H24.time(evening, utc));
        check("6:40 PM".equals(ClockFormat.H12.time(evening, utc)) && "12:05 AM".equals(ClockFormat.H12.time(morning, utc))
                && "12:00 PM".equals(ClockFormat.H12.time(noon, utc)), "12-hour times: " + ClockFormat.H12.time(evening, utc));
        check("18:40".equals(ClockFormat.H24.clock(evening, utc)) && "6:40".equals(ClockFormat.H12.clock(evening, utc)),
                "the header clock has no marker: " + ClockFormat.H12.clock(evening, utc));
        check("18:00".equals(ClockFormat.H24.hour(evening, utc)) && "6 PM".equals(ClockFormat.H12.hour(evening, utc))
                && "12 AM".equals(ClockFormat.H12.hour(morning, utc)), "hours: " + ClockFormat.H12.hour(evening, utc));
        check(ClockFormat.Companion.split("6:40 PM").equals(new kotlin.Pair<>("6:40", "PM"))
                && ClockFormat.Companion.split("18:40").equals(new kotlin.Pair<String, String>("18:40", null))
                && ClockFormat.Companion.split("—").equals(new kotlin.Pair<String, String>("—", null)),
                "the marker splits off a 12-hour time only");
        check(ClockFormat.Companion.of(null) == ClockFormat.H24 && ClockFormat.Companion.of("12") == ClockFormat.H12,
                "stored keys read back");
        check(ClockFormat.Companion.getLABELS().size() == 2
                && ClockFormat.Companion.getLABELS().get(0).equals(ClockFormat.H24.getLabel()), "the screen offers both, 24-hour first");
    }

    private static double luminance(int argb) {
        return (0.2126 * ((argb >> 16) & 0xFF) + 0.7152 * ((argb >> 8) & 0xFF) + 0.0722 * (argb & 0xFF)) / 255.0;
    }

    private static void dayNightCyclesAndResolves() {
        check(DayNightMode.AUTO.next() == DayNightMode.DAY, "automatic then day");
        check(DayNightMode.DAY.next() == DayNightMode.NIGHT, "day then night");
        check(DayNightMode.NIGHT.next() == DayNightMode.AUTO, "night then automatic again");
        check(DayNightMode.AUTO.resolve(true) && !DayNightMode.AUTO.resolve(false), "automatic follows the head unit");
        check(!DayNightMode.DAY.resolve(true) && !DayNightMode.DAY.resolve(false), "day holds day");
        check(DayNightMode.NIGHT.resolve(true) && DayNightMode.NIGHT.resolve(false), "night holds night");
        for (DayNightMode m : DayNightMode.values()) {
            check(DayNightMode.Companion.of(m.getKey()) == m, "the key round-trips: " + m);
        }
        check(DayNightMode.Companion.of(null) == DayNightMode.AUTO, "nothing stored is automatic");
        check(DayNightMode.Companion.of("dusk") == DayNightMode.AUTO, "an unknown key is automatic");
        check(DayNightMode.Companion.getDEFAULT() == DayNightMode.AUTO, "automatic is the default");
    }

    /**
     * START exactly when idle, on, matching, pinned, projecting and ready; WAIT exactly
     * when all of that holds but the identity is still loading. Every other mix is manual.
     */
    private static void autoStartNeedsEveryCondition() {
        int starts = 0;
        int waits = 0;
        for (Stage stage : Stage.values()) {
            for (boolean on : new boolean[] {false, true}) {
                for (AutoStart.Attached attached : AutoStart.Attached.values()) {
                    for (int pins : new int[] {0, 1, 3}) {
                        for (boolean projection : new boolean[] {false, true}) {
                            for (AutoStart.Identity identity : AutoStart.Identity.values()) {
                                AutoStart d = AutoStart.Companion.decide(stage, on, attached, pins, projection, identity);
                                boolean rest = stage == Stage.IDLE && on && attached == AutoStart.Attached.MATCH
                                        && pins > 0 && projection;
                                String what = stage + " " + on + " " + attached + " " + pins + " " + projection
                                        + " " + identity + " -> " + d;
                                check((d == AutoStart.START) == (rest && identity == AutoStart.Identity.READY),
                                        "start only with every condition: " + what);
                                check((d == AutoStart.WAIT) == (rest && identity == AutoStart.Identity.LOADING),
                                        "wait only for a start held up by the identity alone: " + what);
                                if (d == AutoStart.START) starts++;
                                if (d == AutoStart.WAIT) waits++;
                            }
                        }
                    }
                }
            }
        }
        check(starts == 2 && waits == 2, "one pinned or three, nothing else starts or waits: " + starts + " " + waits);
        check(AutoStart.WAIT_MS == 30_000L, "a held start gives up after 30 s");
    }

    private static void autoStartSaysWhyNotInOrder() {
        AutoStart.Attached match = AutoStart.Attached.MATCH;
        AutoStart.Attached mismatch = AutoStart.Attached.MISMATCH;
        AutoStart.Identity ready = AutoStart.Identity.READY;
        AutoStart.Identity loading = AutoStart.Identity.LOADING;
        AutoStart.Identity failed = AutoStart.Identity.FAILED;
        check(AutoStart.Companion.decide(Stage.ARMED, true, match, 1, true, ready) == AutoStart.BUSY,
                "armed: the attach is the armed attempt's, as before");
        check(AutoStart.Companion.decide(Stage.PROJECTING, false, mismatch, 0, false, loading) == AutoStart.BUSY,
                "busy is checked first");
        check(AutoStart.Companion.decide(Stage.IDLE, false, mismatch, 0, false, loading) == AutoStart.OFF,
                "then the setting");
        check(AutoStart.Companion.decide(Stage.IDLE, true, AutoStart.Attached.NONE, 1, true, ready)
                == AutoStart.NO_ACCESSORY, "then an accessory at all");
        check(AutoStart.Companion.decide(Stage.IDLE, true, mismatch, 0, false, loading) == AutoStart.MISMATCH,
                "then the filter: a mismatch is never claimed automatically");
        check(AutoStart.Companion.decide(Stage.IDLE, true, match, 0, false, loading) == AutoStart.NO_PINS,
                "then a pin: first use stays manual");
        check(AutoStart.Companion.decide(Stage.IDLE, true, match, 1, false, loading) == AutoStart.NOT_PROJECTION,
                "then the stop-after: a diagnostic hold is never started by plugging in");
        check(AutoStart.Companion.decide(Stage.IDLE, true, match, 1, true, loading) == AutoStart.WAIT,
                "then the identity: still loading holds the start (the cold-process case)");
        check(AutoStart.Companion.decide(Stage.IDLE, true, match, 1, true, failed) == AutoStart.IDENTITY_FAILED,
                "a failed identity does not wait");
        check(AutoStart.Companion.decide(Stage.IDLE, true, match, 1, true, ready) == AutoStart.START, "and then start");
        // A held start that is decided again after the identity settles.
        check(AutoStart.Companion.decide(Stage.IDLE, true, AutoStart.Attached.NONE, 1, true, ready)
                == AutoStart.NO_ACCESSORY, "unplugged while held: no start");
        check(AutoStart.Companion.decide(Stage.ARMED, true, match, 1, true, ready) == AutoStart.BUSY,
                "Connect pressed while held: no second attempt");
        for (AutoStart d : AutoStart.values()) {
            check(!d.getReason().isEmpty() && d.getReason().indexOf('=') < 0 && d.getReason().indexOf(':') < 0,
                    "a reason is a plain phrase, not a field: " + d);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
