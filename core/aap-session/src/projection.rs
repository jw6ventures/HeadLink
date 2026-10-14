//! Projection demonstration (spec §8): after validated discovery, open the discovered
//! video channel N and input channel M (and, only when configured, the audio, `media_info`
//! and sensor channels), set up H.264 video, bind input, obtain projected focus, start the
//! stream, and then send encoder output within the head unit's acknowledgement window
//! while delivering its input to the app.
//!
//! Order, one request at a time: open N → AV setup → open M → input binding → (open audio
//! → audio AV setup) → (open `media_info`) → (open sensor → one sensor request per
//! configured type) → video focus → start indication → (audio focus → audio start
//! indication). Which sensor types those are is [`SensorSet`], the whole advertisement
//! included (P5.2); position values are redacted unless
//! [`ProjectionConfig::sensor_location`] asks for them, and every sensor failure stays
//! non-fatal. Channel opens are sent on the target channel with flags 0x0F and their
//! response must come back on that channel with the 0x04 bit (AACS and Prodigy at the
//! pins); any other response is counted and not accepted. Service messages use 0x0B.
//! Keepalive stays live throughout. Nothing here opens the microphone, Bluetooth, or
//! other advertised services.
//!
//! **Streams (P5.3).** [`ProjectionState`] holds a [`StreamState`] per AV channel, video
//! first and at most one audio channel after it, and inbound AV messages are routed by a
//! channel → stream lookup rather than a video/input chain. Each stream keeps its own
//! acknowledgement window, session id and stop state; the two share only the output queue.
//! Audio is opt-in ([`ProjectionConfig::audio`]) and every audio failure is non-fatal, so
//! a head unit that refuses the channel, the setup, or audio focus costs the integration
//! and not the drive.
use crate::{
    discovery::{
        self, AudioChoice, InputChoice, MediaInfoChoice, NavigationChoice, Rect, SensorChoice,
        VideoChoice,
    },
    input::{Touch, TouchAction, TouchMap, TouchSpace},
    media::{MediaCounters, UnitKind},
    navigation::{Guidance, RouteState, Script, Source, Units},
    now_playing::{NowPlaying, PlaybackState, TransportAction},
    status_name, Category, Marker, Session, ShutdownCause, Stage, FLAG_CATEGORY, FLAG_ENCRYPTED,
};
use aap_proto::{
    data::AdditionalVideoConfig,
    decode, encode_message,
    enums::{
        audio_focus_state, audio_focus_type, av_channel_setup_status, sensor_type, status,
        video_focus_mode, video_focus_reason,
    },
    ids,
    messages::{
        AudioFocusRequest, AudioFocusResponse, AvChannelSetupRequest, AvChannelSetupResponse,
        AvChannelStartIndication, AvChannelStopIndication, AvMediaAckIndication, BindingRequest,
        BindingResponse, ChannelOpenRequest, ChannelOpenResponse, DestinationDistance,
        InputEventIndication, InstrumentClusterStart, InstrumentClusterStop, MediaPlaybackMetadata,
        MediaPlaybackStatus, MediaPlaybackStatusEvent, NavigationFocusRequest,
        NavigationFocusResponse, NavigationFocusType, NavigationManeuver,
        NavigationNextTurnDistanceEvent, NavigationNotification, NavigationRoadInfo,
        NavigationState, NavigationStateType, NavigationStep, NavigationStepDistance,
        NavigationText, NavigationTurnDistance, SensorError, SensorEventIndication, SensorRequest,
        SensorStartResponseMessage, VideoFocusIndication, VideoFocusRequest,
    },
};
use aap_wire::Message;
use std::{
    collections::VecDeque,
    fmt,
    time::{Duration, Instant},
};

/// MediaCodecType H.264 baseline, requested in the AV setup.
const CODEC_H264_BP: i32 = 3;
/// MediaCodecType linear PCM, requested in the audio AV setup (P5.3). This is the same
/// field Ford already proved it validates, by dropping the request without answering when
/// a configuration index was sent instead (report bcf4184b).
const CODEC_AUDIO_PCM: i32 = 1;
/// Index of the video stream in [`ProjectionState::streams`]; it is always present and
/// always first, because a projection without video is not a projection.
const VIDEO: usize = 0;
/// Audio focus states kept individually in the report; all are counted.
const AUDIO_FOCUS_SEQUENCE_LIMIT: usize = 16;
/// Video focus indications kept individually in the report; all are counted.
///
/// Larger than the audio limit because this sequence is the evidence for a head unit that
/// stops displaying the stream without saying so: the question it answers is "what did Ford
/// send, and when did it stop sending", and a session may lose and regain the screen many
/// times over a drive.
const VIDEO_FOCUS_SEQUENCE_LIMIT: usize = 64;
/// Ten hours of per-minute rows; a longer projection keeps its first ten.
pub const TIMELINE_LIMIT: usize = 600;

/// One minute of the video stream, counted from the start of projection
/// ([`ProjectionReport::timeline`]). Reported as
/// `minute:frames/keyframes/episodes/dropped/requests/restarts`: frames and keyframes sent,
/// congestion episodes begun, frames dropped for congestion or while waiting for the IDR
/// that ends one, keyframes asked for after an episode drained, and stream restarts.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct MinuteRow {
    pub minute: u32,
    pub frames: u32,
    pub keyframes: u32,
    pub episodes: u32,
    pub dropped: u32,
    pub requests: u32,
    pub restarts: u32,
}

impl fmt::Display for MinuteRow {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}:{}/{}/{}/{}/{}/{}",
            self.minute,
            self.frames,
            self.keyframes,
            self.episodes,
            self.dropped,
            self.requests,
            self.restarts
        )
    }
}
/// Sensors subscribed by [`SensorSet::Restriction`], in request order.
const RESTRICTION_SENSORS: [i32; 2] = [
    sensor_type::Enum::DrivingStatus as i32,
    sensor_type::Enum::NightData as i32,
];
/// App events retained between deliveries; input beyond this is dropped and counted.
pub const APP_EVENT_LIMIT: usize = 1024;

#[derive(Debug, Clone)]
pub struct ProjectionConfig {
    /// From a channel open request until its response.
    pub channel_open_wait: Duration,
    /// From an AV setup, input binding, or sensor request until its response.
    pub setup_wait: Duration,
    /// From the video focus request until projected focus. Long enough for an operator to
    /// select the projection on the head unit; focus is never requested twice.
    pub focus_wait: Duration,
    /// From the start indication until the first encoder frame is sent.
    pub first_unit_wait: Duration,
    /// With the acknowledgement window full and focus held, how long without an ack
    /// before `FLOW_CONTROL_TIMEOUT`.
    pub ack_timeout: Duration,
    /// After a congestion episode drains, how long since the last keyframe went out (or
    /// was asked for) before another is asked for. The encoder's own interval usually
    /// supplies one first; see the [`crate::media`] congestion policy.
    pub keyframe_spacing: Duration,
    /// Restart the video stream — stop and start indications under a new session id, and
    /// a rebuilt encoder, as a screen switch does — when the head unit keeps falling
    /// behind: [`Self::congestion_restart_episodes`] episodes within
    /// [`Self::congestion_restart_window`], at most once per
    /// [`Self::congestion_restart_cooldown`]. A screen switch was the only thing that
    /// cleared Ford SYNC 4.0's smeared, frozen picture on 2026-10-11 (report d11eec1e).
    pub congestion_restart: bool,
    pub congestion_restart_episodes: usize,
    pub congestion_restart_window: Duration,
    pub congestion_restart_cooldown: Duration,
    /// Projection length before this source requests shutdown; `None` runs until Stop.
    pub limit: Option<Duration>,
    /// Which advertised sensor types to subscribe to; [`SensorSet::None`] when the config
    /// key is absent. That is what an unset key means, not the product default: spec §8.1
    /// has the source subscribe to every advertised type, and the app writes `sensors=`
    /// on any attempt that wants them.
    pub sensors: SensorSet,
    /// Put head-unit latitude and longitude in the report (P5.2).
    ///
    /// Off by default and kept that way in ordinary evidence: position identifies the
    /// vehicle, so spec §10 treats it exactly like the raw-capture options — opt-in,
    /// bounded, app-private, and left out of a shared report. With this clear the
    /// position sensors are still subscribed and counted, so a report says *whether*
    /// Ford populates LOCATION and GPS without saying where the truck is.
    pub sensor_location: bool,
    /// Open the advertised `media_info` channel and publish now-playing on it (false when
    /// the config key is absent; spec §8.1 has the app default it to on and write
    /// `media_info=true`, and APP09 still has each integration offered on its own).
    /// A refusal is recorded and skipped, never fatal — Ford SYNC 4.0 advertises this
    /// channel and has never been asked to open it, so refusal is a live possibility.
    pub media_info: bool,
    /// Open one advertised PCM `MEDIA` audio channel and stream app-owned audio on it
    /// (P5.3; off by default — spec §8.1 keeps audio opt-in, unlike `media_info`).
    ///
    /// Audio is opened only where discovery found a channel this source can actually
    /// feed: `MEDIA_CODEC_AUDIO_PCM` carrying `AudioType.MEDIA`, with an advertised
    /// configuration at 16-bit and 48000 or 16000 Hz. Refusal of the channel, of the AV
    /// setup, or of audio focus is recorded and skipped, never fatal.
    pub audio: bool,
    /// From the audio focus request until a response, after which audio is skipped for
    /// the rest of the session. Audio focus is not on the critical path — video is
    /// already streaming by then — so this bounds evidence, not the drive.
    pub audio_focus_wait: Duration,
    /// Shortest gap between two messages on the `media_info` channel. The wire position
    /// field is whole seconds, so a player reporting its position cannot usefully say
    /// anything new more often than this.
    pub media_interval: Duration,
    /// How often the current status and track are restated even when nothing changed, so
    /// a head unit that was not listening when they last changed still learns them.
    pub media_refresh: Duration,
    /// Pending output above which no further media is taken from the queue, so control
    /// and input traffic are never stuck behind video.
    pub output_limit: usize,
    /// Coordinate space head-unit touches arrive in; [`TouchSpace::Content`] by default,
    /// which is what Ford SYNC 4.0 sends and is the identity for a head unit that advertises
    /// no margins (spec §8.3).
    pub touch_space: TouchSpace,
    /// Open every advertised channel after the ones this source uses, as Android Auto
    /// 17.6 does on this vehicle (channels 1,2,3,4,6,8,9,10,11,12; logcat 2026-09-18).
    /// Refusals are recorded and skipped: nothing here uses those services, so the point
    /// is only the session shape the head unit sees.
    pub open_all_channels: bool,
    /// Ask for projected video focus. Android Auto does not: it waits for the head unit's
    /// unsolicited grant, which Ford sends anyway. Clearing this waits instead.
    pub request_focus: bool,
    /// Open the advertised `navigation` channel and drive the head unit's own
    /// instrument cluster with a scripted maneuver sequence (P6.2; off by default, like
    /// audio, because it puts messages on a display that is not ours).
    ///
    /// Every failure on this path is recorded and skipped — a refused channel, a cluster
    /// that never announces itself, a focus request nobody answers — for the same reason
    /// audio's are: turn-by-turn on the cluster is an integration, and losing the picture
    /// over it would be the wrong trade.
    pub navigation: bool,
    /// How distances on the cluster are written (P6.2). Imperial by default: the vehicle
    /// under test is a US truck, and whether its cluster will display one and not the
    /// other is a question only a run can answer.
    pub navigation_units: Units,
    /// Where the maneuvers come from: the scripted demonstration (P6.2, the default) or
    /// the app's guidance loop over a computed route, published through
    /// [`crate::navigation::GuidanceLink`] (P6.5). Everything else on the channel — the
    /// cluster wait, the focus exchange, the interval, the hand-back — is the same for
    /// both.
    pub navigation_source: Source,
    /// The scripted route to play; `None` uses [`Script::demo`]. Ignored when
    /// [`ProjectionConfig::navigation_source`] is [`Source::Route`].
    ///
    /// There is no configuration key for this — a route does not fit on a line — so it is
    /// a Rust-level knob that tests set to a short route and the app never touches.
    pub navigation_script: Option<Script>,
    /// Shortest gap between two updates on the `navigation` channel, where an update is
    /// at most one notification and one distance event.
    ///
    /// **The head unit's advertised `minimum_interval_ms` is a floor under this, not a
    /// default for it**: the descriptor states the fastest the head unit is willing to be
    /// told anything, so the interval actually used is the larger of the two. Ford SYNC
    /// 4.0 advertises 400 ms (P6.0); this source's own default is slower still, because a
    /// countdown does not need to be faster than a driver can read it.
    pub navigation_interval: Duration,
    /// How often the current maneuver is restated even when it has not changed, so a
    /// cluster that was not listening when it last changed still learns it. The same rule
    /// as [`ProjectionConfig::media_refresh`], and for the same reason: Ford accepted the
    /// `media_info` open at 1.3 s and had its one and only metadata message by 1.4 s.
    pub navigation_refresh: Duration,
    /// From the channel open until `InstrumentClusterStart`, and from the navigation
    /// focus request until a response. Both bound evidence rather than the drive: video
    /// is already streaming by the time either matters.
    pub navigation_focus_wait: Duration,
    /// Send the scripted sequence even if the head unit never announces its cluster or
    /// never grants navigation focus (P6.2).
    ///
    /// Off by default, which is the P6 plan §3 sequence: wait for `InstrumentClusterStart`
    /// rather than assuming the cluster is there, and send maneuvers only to a cluster
    /// that has given this source focus. Setting it turns one drive into two experiments
    /// — a head unit that opens the channel, accepts the messages and displays nothing is
    /// the degraded pass G9 explicitly allows — and the report says in as many words that
    /// nothing was waited for.
    pub navigation_force: bool,
    /// What to put in field 1 of `AVChannelSetupRequest`.
    ///
    /// The vendored schema calls it `media_codec_type` and this source has always sent the
    /// H.264 BP value 3, as AACS does. [aasdk] calls the same field `config_index`, and the
    /// setup *response* answers with accepted configuration **indices**, which reads like a
    /// reply to a proposed one.
    ///
    /// It matters because Ford SYNC 4.0 displays only the configuration at index 2 of the
    /// three it advertises (largest first, so index 2 is 800x480) while acknowledging every
    /// unit of any other: a head unit reading field 1 as an index and clamping the
    /// out-of-range 3 onto its last configuration would behave exactly that way, and
    /// 800x480 would have been working by coincidence all along.
    ///
    /// [aasdk]: https://github.com/f1xpl/aasdk/blob/9ee5283245a630afce3a441696b9dde484ecbcd9/aasdk_proto/AVChannelSetupRequestMessage.proto
    pub setup_field: SetupField,
}

/// Which advertised sensor types this source subscribes to (spec §8.1: sensors are
/// opt-in and every sensor failure is non-fatal).
///
/// The head unit's advertisement is always the upper bound — a type that is not in the
/// sensor channel's descriptor is never requested, whichever variant asks for it.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum SensorSet {
    /// The sensor channel is not opened at all.
    #[default]
    None,
    /// DRIVING_STATUS and NIGHT_DATA, the pair P4 used for restrictions and the night
    /// scene.
    Restriction,
    /// Every advertised type, in the order the head unit advertised them. This is the
    /// P5.2 harvest: the question it answers is which advertised sensors carry values.
    All,
    /// Exactly these types, in this order, intersected with the advertisement.
    Only(Vec<i32>),
}

impl SensorSet {
    /// `none`, `restriction` (also `true`, the P4 spelling), `all`, or a comma-separated
    /// list of `SensorType` names or numbers. `false` is [`SensorSet::None`].
    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "none" | "false" => Some(SensorSet::None),
            "restriction" | "true" => Some(SensorSet::Restriction),
            "all" => Some(SensorSet::All),
            list if !list.is_empty() => {
                let types: Option<Vec<i32>> = list
                    .split(',')
                    .map(|item| match item.parse::<i32>() {
                        Ok(value) => sensor_type::Enum::try_from(value).ok(),
                        Err(_) => sensor_type::Enum::from_str_name(item),
                    })
                    .map(|t| t.map(|t| t as i32).filter(|t| *t != 0))
                    .collect();
                types.filter(|t| !t.is_empty()).map(SensorSet::Only)
            }
            _ => None,
        }
    }

    pub fn name(&self) -> String {
        match self {
            SensorSet::None => "none".into(),
            SensorSet::Restriction => "restriction".into(),
            SensorSet::All => "all".into(),
            SensorSet::Only(types) => types
                .iter()
                .map(|t| t.to_string())
                .collect::<Vec<_>>()
                .join(","),
        }
    }

    /// Whether a sensor channel is wanted at all.
    pub fn enabled(&self) -> bool {
        *self != SensorSet::None
    }

    /// Types to request, in request order: this set intersected with what the head unit
    /// advertised, without duplicates and without the placeholder `NONE` (0).
    fn select(&self, advertised: &[i32]) -> Vec<i32> {
        let mut wanted: Vec<i32> = match self {
            SensorSet::None => return Vec::new(),
            SensorSet::Restriction => RESTRICTION_SENSORS
                .into_iter()
                .filter(|t| advertised.contains(t))
                .collect(),
            SensorSet::All => advertised.to_vec(),
            SensorSet::Only(types) => types
                .iter()
                .copied()
                .filter(|t| advertised.contains(t))
                .collect(),
        };
        wanted.retain(|t| *t != 0);
        let mut seen = Vec::with_capacity(wanted.len());
        wanted.retain(|t| {
            let fresh = !seen.contains(t);
            seen.push(*t);
            fresh
        });
        wanted
    }
}

/// See [`ProjectionConfig::setup_field`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum SetupField {
    /// The H.264 BP codec value 3, as the vendored schema and AACS have it.
    #[default]
    CodecType,
    /// The index of the selected configuration, as aasdk has it.
    ConfigIndex,
}

impl SetupField {
    pub fn name(self) -> &'static str {
        match self {
            SetupField::CodecType => "codec-type",
            SetupField::ConfigIndex => "config-index",
        }
    }
    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "codec-type" => Some(SetupField::CodecType),
            "config-index" => Some(SetupField::ConfigIndex),
            _ => None,
        }
    }
}

impl Default for ProjectionConfig {
    fn default() -> Self {
        Self {
            channel_open_wait: Duration::from_secs(10),
            setup_wait: Duration::from_secs(10),
            focus_wait: Duration::from_secs(30),
            first_unit_wait: Duration::from_secs(10),
            ack_timeout: Duration::from_secs(5),
            keyframe_spacing: Duration::from_secs(1),
            congestion_restart: true,
            congestion_restart_episodes: 5,
            congestion_restart_window: Duration::from_secs(10),
            congestion_restart_cooldown: Duration::from_secs(60),
            limit: None,
            sensors: SensorSet::None,
            sensor_location: false,
            audio: false,
            audio_focus_wait: Duration::from_secs(10),
            media_info: false,
            media_interval: Duration::from_secs(1),
            media_refresh: Duration::from_secs(5),
            output_limit: 256 * 1024,
            touch_space: TouchSpace::default(),
            open_all_channels: false,
            request_focus: true,
            navigation: false,
            navigation_units: Units::default(),
            navigation_source: Source::default(),
            navigation_script: None,
            navigation_interval: Duration::from_millis(1000),
            navigation_refresh: Duration::from_secs(5),
            navigation_focus_wait: Duration::from_secs(10),
            navigation_force: false,
            setup_field: SetupField::default(),
        }
    }
}

/// What the app needs to start producing PCM (P5.3).
///
/// The format is the head unit's own advertised configuration, not a phone-side
/// preference: the app resamples or refuses, it does not renegotiate.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AudioStart {
    pub session: i32,
    pub channel: u8,
    pub sample_rate: u32,
    pub bit_depth: u32,
    pub channel_count: u32,
    /// Bytes in the buffer size this source expects, so the app paces itself rather than
    /// discovering the limit by having buffers refused. 2048 samples at 48 kHz stereo is
    /// 8192 bytes ≈ 42.7 ms, which two independent sources agree on (P5 §3).
    pub buffer_bytes: usize,
}

impl fmt::Display for AudioStart {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "session={} channel={} sample_rate={} bit_depth={} channels={} buffer_bytes={}",
            self.session,
            self.channel,
            self.sample_rate,
            self.bit_depth,
            self.channel_count,
            self.buffer_bytes
        )
    }
}

/// What the app needs to start its encoder.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VideoStart {
    pub session: i32,
    pub video: VideoChoice,
    /// Advertised touch screen size, if any.
    pub touch: Option<(i32, i32)>,
}

impl fmt::Display for VideoStart {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let v = &self.video;
        let c = v.content;
        write!(
            f,
            "session={} channel={} display={} config={} width={} height={} fps={} \
             content_x={} content_y={} content_width={} content_height={} touch=",
            self.session,
            v.channel,
            v.display_id,
            v.config_index,
            v.width,
            v.height,
            v.fps,
            c.x,
            c.y,
            c.width,
            c.height
        )?;
        match self.touch {
            Some((w, h)) => write!(f, "{w}x{h}"),
            None => write!(f, "-"),
        }
    }
}

/// Session → app notifications, taken with [`Session::take_app_events`] on the session
/// thread.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AppEvent {
    /// Start the encoder at this configuration; the first frame must be an IDR.
    VideoStart(VideoStart),
    /// Stop and release the encoder.
    VideoStop {
        reason: String,
    },
    /// Projected focus changed after the stream started. While lost, submissions are
    /// dropped; on regain the stream restarts at an IDR (a `KeyframeRequest` follows).
    Focus {
        projected: bool,
        mode: i32,
    },
    /// The head unit moved the content rectangle inside the unchanged frame; lay the
    /// scene out again. The encoder keeps running, since the frame size is the same.
    VideoGeometry(VideoStart),
    /// Produce an IDR as soon as possible.
    KeyframeRequest,
    /// The source restarted the video stream itself because the head unit kept falling
    /// behind ([`ProjectionConfig::congestion_restart`]): rebuild the encoder as on a focus
    /// regain. Focus did not change. A `KeyframeRequest` follows.
    VideoRestart {
        reason: String,
    },
    Touch(Touch),
    Key {
        keycode: u32,
        down: bool,
        long_press: bool,
        meta_state: u32,
    },
    /// Latest sensor restriction data (sensors enabled only).
    Restriction {
        driving_status: Option<i32>,
        night: Option<bool>,
    },
    /// A head-unit transport button, delivered on the press only (P5.1). The app applies
    /// it to whatever is playing on the phone; nothing here assumes this source owns that
    /// playback, and nothing here changes the published state in response — the state the
    /// head unit is shown always comes back from the app through
    /// [`crate::now_playing::NowPlayingLink`], so the head unit is never told a button
    /// worked when it did not.
    Transport {
        action: TransportAction,
        keycode: u32,
    },
    /// Start producing PCM at this format (P5.3). Sent once audio focus has been granted
    /// and the audio start indication is out, so the head unit's mixer already has a
    /// route open for it.
    AudioStart(AudioStart),
    /// Stop producing PCM. Also sent when audio focus is lost, so the app stops feeding
    /// a stream the head unit is not listening to.
    AudioStop {
        reason: String,
    },
    /// Latest vehicle position and motion (P5.5), accumulated across sensor events so a
    /// message carrying only speed does not erase a known position.
    Vehicle(VehicleState),
}

/// What the head unit has told us about where the vehicle is and how it is moving.
///
/// **Every value is the raw integer from the wire, unscaled and uninterpreted.** P5.2
/// measured this vehicle sending `bearing=103553459`, `satellites_used=32` against
/// `satellite_count=21`, and `gear=101`, none of which reads sensibly against the
/// schema's own field names — and the project has already been wrong three times about
/// what a vendored name means. Converting to degrees or m/s here would bake one reading
/// of an unsettled question into the protocol layer and put it out of reach of the place
/// that can actually check it. The app decides what is plausible, what to show, and when
/// to fall back to a phone-derived value instead.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct VehicleState {
    pub latitude: Option<i32>,
    pub longitude: Option<i32>,
    pub accuracy: Option<u32>,
    pub altitude: Option<i32>,
    /// Speed as reported inside the GPS fix, distinct from [`Self::car_speed`], which is
    /// the vehicle's own CAR_SPEED sensor. They need not agree.
    pub gps_speed: Option<i32>,
    pub bearing: Option<i32>,
    pub car_speed: Option<i32>,
    pub gear: Option<i32>,
    pub parking_brake: Option<bool>,
    pub satellite_count: Option<i32>,
    pub satellites_used: Option<i32>,
}

impl VehicleState {
    /// Overlays whatever `other` carries, leaving the rest untouched.
    fn merge(&mut self, other: &VehicleState) -> bool {
        let before = *self;
        macro_rules! take {
            ($($field:ident),* $(,)?) => {$(
                if other.$field.is_some() {
                    self.$field = other.$field;
                }
            )*};
        }
        take!(
            latitude,
            longitude,
            accuracy,
            altitude,
            gps_speed,
            bearing,
            car_speed,
            gear,
            parking_brake,
            satellite_count,
            satellites_used,
        );
        *self != before
    }
}

impl fmt::Display for VehicleState {
    /// One space-separated line; absent values are `-`, so the app parses a fixed shape.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fn v<T: fmt::Display>(value: Option<T>) -> String {
            value.map_or_else(|| "-".into(), |v| v.to_string())
        }
        write!(
            f,
            "latitude={} longitude={} accuracy={} altitude={} gps_speed={} bearing={} \
car_speed={} gear={} parking_brake={} satellite_count={} satellites_used={}",
            v(self.latitude),
            v(self.longitude),
            v(self.accuracy),
            v(self.altitude),
            v(self.gps_speed),
            v(self.bearing),
            v(self.car_speed),
            v(self.gear),
            v(self.parking_brake),
            v(self.satellite_count),
            v(self.satellites_used),
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Step {
    OpenVideo,
    AvSetup,
    OpenInput,
    Binding,
    OpenAudio,
    AudioSetup,
    OpenMediaInfo,
    OpenNavigation,
    OpenSensor,
    SensorStart(usize),
    /// Opening advertised channels this source does not use, by index into
    /// [`ProjectionState::extra`].
    OpenExtra(usize),
    AwaitFocus,
    Streaming,
}

impl Step {
    fn name(self) -> &'static str {
        match self {
            Step::OpenVideo => "open-video",
            Step::AvSetup => "av-setup",
            Step::OpenInput => "open-input",
            Step::Binding => "input-binding",
            Step::OpenAudio => "open-audio",
            Step::AudioSetup => "audio-setup",
            Step::OpenMediaInfo => "open-media-info",
            Step::OpenNavigation => "open-navigation",
            Step::OpenSensor => "open-sensor",
            Step::SensorStart(_) => "sensor-start",
            Step::OpenExtra(_) => "open-extra",
            Step::AwaitFocus => "video-focus",
            Step::Streaming => "streaming",
        }
    }
}

/// Which stream a [`StreamState`] carries.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum StreamKind {
    Video,
    Audio,
}

/// One AV stream: its channel, its own acknowledgement window, and its own lifecycle
/// (P5.3).
///
/// Video and audio share the session, the output queue and the keepalive, and nothing
/// else. In particular each has its own `session_id`, its own `window` from its own setup
/// response, and its own focus — the head unit grants video focus on the video channel
/// and audio focus on the control channel, and one says nothing about the other.
struct StreamState {
    kind: StreamKind,
    channel: u8,
    /// The head unit accepted a channel open for this stream.
    open: bool,
    /// The head unit answered the AV setup for this stream.
    setup: bool,
    window: u32,
    outstanding: u32,
    stall_since: Option<Instant>,
    session_id: i32,
    /// The id this stream carried before the last focus-driven restart, kept so units that
    /// were in flight when the stop indication went out are recognised when their
    /// acknowledgements land. Android Auto keeps the same value (`Ljdr;.d`).
    prev_session_id: Option<i32>,
    started: bool,
    stop_emitted: bool,
    /// Whether the head unit's focus for this stream is held: projected video focus for
    /// video, an audio focus GAIN for audio.
    focused: bool,
    first_unit_deadline: Option<Instant>,
}

impl StreamState {
    fn new(kind: StreamKind, channel: u8) -> Self {
        Self {
            kind,
            channel,
            open: false,
            setup: false,
            prev_session_id: None,
            // "A setup response without max_unacked means 1" is the existing default and
            // is right: audio windows are typically smaller than video ones, and openauto
            // and mikereidis both hardcode 1.
            window: 1,
            outstanding: 0,
            stall_since: None,
            session_id: 0,
            started: false,
            stop_emitted: false,
            focused: false,
            first_unit_deadline: None,
        }
    }
}

/// The `media_info` channel while projection runs (P5.1). Present only when the channel
/// was advertised and [`ProjectionConfig::media_info`] asked for it; `open` stays false
/// until the head unit accepts, and a refusal simply leaves it false forever.
struct MediaChannel {
    channel: u8,
    open: bool,
    /// Earliest time another message may go out, so an app publishing continuously
    /// cannot flood a channel that shares the output queue with video.
    next_send: Option<Instant>,
    /// When anything last went out, for the periodic restatement.
    last_send: Option<Instant>,
    /// The status last put on the wire; an unchanged republication sends nothing.
    sent_status: Option<(PlaybackState, u32, String)>,
    /// The track last put on the wire, as title, artist, and album.
    sent_track: Option<(String, String, String)>,
    /// The update currently held back by the rate limit, so it is counted once rather
    /// than once per timer tick.
    #[allow(clippy::type_complexity)]
    deferred: Option<((PlaybackState, u32, String), (String, String, String))>,
}

/// Where the navigation focus exchange has got to (P6.2).
///
/// Focus is a state the head unit owns, exactly as audio focus is, so this is a state
/// machine rather than the answer to a question. It is smaller than the audio one because
/// `NavigationFocusType` has no `LOSS` and no `RELEASE`: the head unit can only say that
/// navigation is projected or native, and native arriving unasked is the loss.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum NavFocus {
    /// Nothing asked for yet: the cluster has not announced itself. Also where a live
    /// route's hand-back leaves it (P7.4, [`NavigationChannel::handed_back`]), so the next
    /// route asks exactly as the first one did.
    Idle,
    Requested,
    /// `NAV_FOCUS_PROJECTED`: the cluster is showing this source's maneuvers.
    Projected,
    /// `NAV_FOCUS_NATIVE`, or a response that could not be read. The request is left
    /// standing — a head unit may hand the cluster over later, exactly as one may grant
    /// audio focus late — so a `PROJECTED` arriving after this still starts the sequence.
    Native,
}

/// The `navigation` channel while projection runs (P6.2).
///
/// Present only when the channel was advertised and [`ProjectionConfig::navigation`]
/// asked for it. Unlike `media_info`, an accepted open is not the whole of it: the cluster
/// has to announce itself and then grant focus before a single maneuver goes out.
struct NavigationChannel {
    channel: u8,
    open: bool,
    /// The interval actually used: the larger of the head unit's advertised
    /// `minimum_interval_ms` and this source's own. The descriptor's own value is in the
    /// report, beside which of the two decided this one.
    interval: Duration,
    /// `InstrumentClusterStart` arrived, so the cluster exists and is listening.
    cluster_started: bool,
    focus: NavFocus,
    /// Waiting for `InstrumentClusterStart`, and then for a focus response. One deadline
    /// because the two waits are consecutive halves of one handshake and never overlap.
    deadline: Option<Instant>,
    /// Focus is held (or forced) and the sequence has started. With a scripted source
    /// that is also `NavigationState(ACTIVE)` on the wire; with a route it is only
    /// permission, and [`NavigationChannel::wire_state`] follows the app's guidance.
    active: bool,
    /// When the sequence became active, which is the script's own zero.
    started: Option<Instant>,
    script: Script,
    source: Source,
    /// The last `NavigationState` sent, so a route's state changes go out once each and
    /// the teardown knows whether there is anything to withdraw.
    wire_state: Option<NavigationStateType>,
    /// Earliest time another update may go out.
    next_send: Option<Instant>,
    /// The leg last put on the wire as a notification, and when, for the restatement.
    sent_leg: Option<usize>,
    sent_leg_at: Option<Instant>,
    /// The route reached its destination, so nothing more is owed on this channel.
    finished: bool,
    /// `NavigationState(INACTIVE)` has been sent, so the teardown is not repeated.
    stopped: bool,
    /// A live route ended ([`RouteState::Ended`]) and the cluster was handed back with
    /// `NAV_FOCUS_NATIVE` mid-session (P7.4). Until the next route this source wants
    /// nothing from the cluster: it does not ask for focus again when the cluster
    /// announces itself or a wait runs out, and a focus response that arrives meanwhile
    /// is the head unit's answer to the hand-back, not a refusal. Cleared by the next
    /// request.
    handed_back: bool,
}

impl NavigationChannel {
    /// Whether an update may be sent: the channel is open, the sequence is active, and the
    /// route has not finished.
    fn sending(&self) -> bool {
        self.open && self.active && !self.finished && !self.stopped
    }
}

pub(crate) struct ProjectionState {
    pub(crate) step: Step,
    /// Video first, then at most one audio stream. Inbound AV messages find their stream
    /// by channel rather than by a chain of comparisons.
    streams: Vec<StreamState>,
    video: VideoChoice,
    candidates: Vec<VideoChoice>,
    /// The audio channel discovery selected, present only when
    /// [`ProjectionConfig::audio`] asked for it and the head unit advertised one this
    /// source can feed. Its presence is what puts a second entry in `streams`.
    audio: Option<AudioChoice>,
    input: InputChoice,
    touch: Option<TouchMap>,
    sensor: Option<SensorChoice>,
    sensor_types: Vec<i32>,
    media: Option<MediaChannel>,
    /// The head unit's own instrument cluster, present only when
    /// [`ProjectionConfig::navigation`] asked for it and the head unit advertised one.
    navigation: Option<NavigationChannel>,
    /// Advertised channels this source does not use, opened only to match Android Auto's
    /// session shape when [`ProjectionConfig::open_all_channels`] is set.
    extra: Vec<u8>,
    pub(crate) limit_deadline: Option<Instant>,
    /// From the audio focus request until the head unit answers; audio is skipped if it
    /// never does.
    audio_focus_deadline: Option<Instant>,
    /// Whether the head unit has granted audio focus at any point. It is what decides
    /// whether a `RELEASE` is owed: focus never granted is nothing to give up.
    audio_focus_held_ever: bool,
    projecting_since: Option<Instant>,
    first_keyframe_sent: bool,
    /// When a video keyframe last went out, and when one was last asked for after a
    /// congestion episode: the spacing of the next request runs from the later.
    last_keyframe_sent: Option<Instant>,
    last_keyframe_asked: Option<Instant>,
    /// The link's congestion totals as last read, so each pump sees only what is new.
    congestion_seen: (u64, u64),
    /// When recent congestion episodes were first seen, within the restart window.
    congestion_recent: VecDeque<Instant>,
    last_congestion_restart: Option<Instant>,
    input_open: bool,
    sensor_open: bool,
    /// Accumulated vehicle position and motion, kept out of the report: it is position
    /// data, which spec §10 keeps out of ordinary evidence, and the app needs the
    /// running value rather than whichever fields the last message happened to carry.
    vehicle: VehicleState,
}

impl ProjectionState {
    fn video(&self) -> &StreamState {
        &self.streams[VIDEO]
    }

    fn video_mut(&mut self) -> &mut StreamState {
        &mut self.streams[VIDEO]
    }

    fn index_of(&self, kind: StreamKind) -> Option<usize> {
        self.streams.iter().position(|s| s.kind == kind)
    }

    /// The stream a message on `channel` belongs to, if any.
    fn stream_at(&self, channel: u8) -> Option<usize> {
        self.streams.iter().position(|s| s.channel == channel)
    }
}

/// One channel open request and its outcome.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpenRecord {
    pub channel: u8,
    pub service: &'static str,
    pub sent_at: Duration,
    pub response_at: Option<Duration>,
    pub status: Option<i32>,
}

/// What one sensor type actually delivered (P5.2).
///
/// The slice exists to separate advertised from populated: a type with `count` 0 was
/// advertised, requested and accepted, and then never sent a value.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SensorHarvest {
    pub sensor_type: i32,
    /// Whether this source asked for the type. A value for a type never requested is
    /// still harvested, and counted separately in
    /// [`ProjectionReport::sensor_unrequested`].
    pub requested: bool,
    /// Values received, counting every entry of every indication.
    pub count: u64,
    /// The latest value, field by field. Position is redacted unless
    /// [`ProjectionConfig::sensor_location`] was set.
    pub last: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct InputStats {
    pub events: u64,
    pub touch_events: u64,
    pub down: u64,
    pub up: u64,
    pub moves: u64,
    pub cancel: u64,
    pub pointer_down: u64,
    pub pointer_up: u64,
    pub pointers_max: usize,
    pub key_events: u64,
    pub keys_down: u64,
    /// Touchpad, absolute, and relative events (not used by the demonstration).
    pub other_events: u64,
    /// Decodable events that failed validation (range, pointers, action).
    pub rejected: u64,
    pub invalid: u64,
    /// Events before the stream started or while projected focus was lost.
    pub dropped_unfocused: u64,
    pub last_rejection: Option<String>,
    pub last_touch: Option<String>,
    /// Extremes of the raw coordinates accepted, as min x, max x, min y, max y. Evidence
    /// for the touch space (spec §8.3): touching each edge of the picture shows which
    /// rectangle the head unit's coordinates actually span.
    pub raw_range: Option<[u32; 4]>,
    /// How touches were mapped, once a configuration is selected.
    pub touch_map: Option<TouchMap>,
}

/// Projection evidence for the session report (spec §10).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ProjectionReport {
    pub step: &'static str,
    pub opens: Vec<OpenRecord>,
    /// ChannelOpenResponses on a channel with no open pending.
    pub open_responses_unexpected: u64,
    /// ChannelOpenResponses without the 0x04 bit or on channel 0, never accepted.
    pub open_responses_misrouted: u64,
    /// Head-unit channel open requests, rejected with INVALID_CHANNEL.
    pub inbound_opens_rejected: u64,
    pub setup_status: Option<i32>,
    pub setup_at: Option<Duration>,
    pub window: Option<u32>,
    /// The setup response had no `max_unacked`; a window of 1 was used.
    pub window_defaulted: bool,
    pub accepted_configs: Vec<u32>,
    pub selected: Option<VideoChoice>,
    pub binding_keycodes: Vec<i32>,
    pub binding_status: Option<i32>,
    pub binding_at: Option<Duration>,
    /// The set asked for, as [`SensorSet::name`]; `none` when sensors were off.
    pub sensor_set: String,
    /// Every type the head unit advertised on its sensor channel, whether or not it was
    /// requested. Recorded even when the set is `none`, so a discovery-only comparison
    /// is possible.
    pub sensor_advertised: Vec<i32>,
    pub sensor_requests: Vec<(i32, Option<i32>)>,
    pub sensor_errors: u64,
    /// Which value field 1 of the AV setup request carried, and what it was.
    pub setup_field: &'static str,
    pub setup_value: Option<i32>,
    /// Advertised channels opened and refused under
    /// [`ProjectionConfig::open_all_channels`]; neither is fatal.
    pub extra_opened: u64,
    pub extra_refused: u64,
    /// UPDATE_UI_CONFIG_REQUESTs received, answered, and refused as unusable.
    pub ui_config_updates: u64,
    pub ui_config_responses: u64,
    pub ui_config_invalid: u64,
    /// Body length of the latest reply, which carries the configuration received.
    pub ui_config_reply_len: Option<usize>,
    /// The latest update: its insets and the content rectangle they produced, or why it
    /// was refused.
    pub ui_config_last: Option<String>,
    pub sensor_events: u64,
    /// Vehicle position/motion events handed to the app (P5.5). Distinguishes a sensor
    /// channel that delivered nothing from an app that drew nothing.
    pub vehicle_events: u64,
    pub sensor_invalid: u64,
    /// Indications that decoded but carried no value in any of the 26 payload fields.
    pub sensor_empty: u64,
    /// Values for a type this source never requested.
    pub sensor_unrequested: u64,
    /// Per-type harvest, in request order and then arrival order (P5.2).
    pub sensor_values: Vec<SensorHarvest>,
    /// Whether latitude and longitude were put in the report, which needs
    /// [`ProjectionConfig::sensor_location`]. A report with this false says whether
    /// position arrived, never where (spec §10).
    pub sensor_location_reported: bool,
    pub driving_status: Option<i32>,
    pub night: Option<bool>,
    /// P5.3 audio out. Everything here is absent, zero or false when audio was not asked
    /// for, which is the default.
    ///
    /// The three questions this half of the report exists to answer are whether Ford
    /// opens an audio channel at all, what acknowledgement window it gives audio (its
    /// video window is 4; audio is typically smaller), and whether `AUDIO_UNDERFLOW`
    /// arrives — the one signal in the whole AV path that says the pacing is wrong.
    pub audio_requested: bool,
    /// The channel and format discovery selected, as [`AudioChoice`] renders it.
    pub audio_selected: Option<String>,
    pub audio_channel: Option<u8>,
    pub audio_open: bool,
    /// The head unit refused the channel open, the AV setup, or audio focus.
    pub audio_refused: bool,
    pub audio_setup_status: Option<i32>,
    pub audio_setup_at: Option<Duration>,
    pub audio_window: Option<u32>,
    /// The audio setup response had no `max_unacked`; a window of 1 was used.
    pub audio_window_defaulted: bool,
    pub audio_accepted_configs: Vec<u32>,
    pub audio_focus_requests: u64,
    pub audio_focus_responses: u64,
    pub audio_focus_invalid: u64,
    /// The latest `AudioFocusState` the head unit sent, and field 2 of the same message.
    ///
    /// **Field 2 is `unsolicited`, not `granted`.** The vendored schema calls it
    /// `optional bool granted = 2`, and that name is wrong: Ford SYNC 4.0 sends it false
    /// both when it refuses and when it grants, and Android Auto logs the same value as
    /// `"Received focus change from HU: %s, unsolicited: %s"` (references.md). True means
    /// the head unit volunteered this state; false means it is the reply to a request.
    /// aasdk's copy of the message lacks the field entirely.
    pub audio_focus_state: Option<i32>,
    pub audio_focus_unsolicited: Option<bool>,
    pub audio_focus_at: Option<Duration>,
    /// Every focus state the head unit sent, in order, as (elapsed, state, unsolicited),
    /// at most [`AUDIO_FOCUS_SEQUENCE_LIMIT`]. The final state alone cannot tell a denial
    /// apart from a loss followed by a grant (spec §10).
    pub audio_focus_sequence: Vec<(Duration, i32, Option<bool>)>,
    /// Non-grants **in reply to a request** while this source held no focus — the head
    /// unit saying no — as distinct from `audio_focus_losses`, which is focus taken back
    /// after a grant, and from `audio_focus_volunteered`, which is a state nobody asked
    /// for.
    pub audio_focus_denials: u64,
    /// States the head unit sent with `unsolicited` true: it changed its mind rather than
    /// answering anything.
    pub audio_focus_volunteered: u64,
    pub audio_focus_regains: u64,
    /// A focus response failed to decode, so "never granted" cannot be proved and a
    /// `RELEASE` is owed defensively.
    pub audio_focus_uncertain: bool,
    pub audio_focus_losses: u64,
    /// `LOSS_TRANSIENT_CAN_DUCK` pushed at this source. Informational only: this source
    /// never ducks its own media, because a head unit asking for a duck lowers the media
    /// channel in its own mixer.
    pub audio_focus_ducks: u64,
    /// A `RELEASE` was sent, so no transient grant is left dangling.
    pub audio_focus_released: bool,
    pub audio_start_session: Option<i32>,
    pub audio_start_config: Option<u32>,
    pub audio_started_at: Option<Duration>,
    pub audio_stop_indication_sent: bool,
    pub audio_buffers_sent: u64,
    pub audio_bytes_sent: u64,
    pub audio_acks: u64,
    pub audio_acked_units: u64,
    pub audio_acks_wrong_session: u64,
    pub audio_acks_invalid: u64,
    pub audio_acks_excess: u64,
    pub audio_outstanding: u32,
    pub audio_outstanding_max: u32,
    pub audio_window_full_events: u64,
    /// `0x800B AUDIO_UNDERFLOW` from the head unit, and the body lengths seen. No
    /// open-source head unit sends it, so a nonzero count here is a genuine finding.
    pub audio_underflows: u64,
    pub audio_underflow_body_len: Option<usize>,
    /// The audio window stayed full past the acknowledgement timeout. Recorded and the
    /// audio stream stopped; unlike video this never ends the session, because audio is
    /// an opt-in integration.
    pub audio_stalled: bool,
    /// Submission evidence from the PCM link, present only once an audio stream exists.
    /// A defaulted [`MediaCounters`] would render itself with the video prefix, so the
    /// absence is carried rather than papered over.
    pub audio: Option<MediaCounters>,
    /// P5.1 `media_info`: the advertised channel, whether the head unit opened it, and
    /// whether it refused. All three are absent or false when the integration was not
    /// asked for, which is the default.
    pub media_channel: Option<u8>,
    pub media_open: bool,
    pub media_refused: bool,
    pub media_status_sent: u64,
    pub media_metadata_sent: u64,
    /// Publications the rate limit or an unchanged value kept off the wire.
    pub media_suppressed: u64,
    /// The last status put on the wire, as `state:position:source_app`.
    pub media_last_status: Option<String>,
    /// Whether a track was ever named on the wire. The text itself is not reported: it
    /// is the operator's listening history, which spec §10 keeps out of ordinary
    /// evidence for the same reason as the head-unit name.
    pub media_track_sent: bool,
    /// Cluster d-pad actions the head unit sent back (0x8002), and any that failed to
    /// decode. Never answered.
    pub media_events: u64,
    pub media_events_invalid: u64,
    /// P6.2 navigation: the head unit's own instrument cluster. Everything here is
    /// absent, zero or false when the integration was not asked for, which is the default.
    ///
    /// The questions this half of the report exists to answer are whether Ford opens the
    /// channel it has advertised since the first vehicle run, whether it ever announces a
    /// cluster, whether it grants navigation focus, and — the one only an operator can
    /// answer — whether anything appears on the cluster when it does (G9).
    pub navigation_requested: bool,
    pub navigation_channel: Option<u8>,
    pub navigation_open: bool,
    /// The head unit refused the channel open, or closed it afterwards.
    pub navigation_refused: bool,
    /// The descriptor's `minimum_interval_ms`, the interval actually used, and which of
    /// the two decided it.
    pub navigation_min_interval_ms: Option<u128>,
    pub navigation_interval_ms: u128,
    pub navigation_floor: &'static str,
    /// `InstrumentClusterStart` and `InstrumentClusterStop` from the head unit, and when
    /// the first start arrived. A start that never comes is the difference between "the
    /// cluster showed nothing" and "there was no cluster to show anything".
    pub navigation_cluster_starts: u64,
    pub navigation_cluster_stops: u64,
    pub navigation_cluster_at: Option<Duration>,
    /// The cluster never announced itself within the wait.
    pub navigation_cluster_timeout: bool,
    /// The sequence was driven without waiting for the cluster or for focus
    /// ([`ProjectionConfig::navigation_force`]). Recorded because it changes what a
    /// silent cluster means.
    pub navigation_forced: bool,
    pub navigation_focus_requests: u64,
    pub navigation_focus_responses: u64,
    /// The latest `NavigationFocusType` the head unit sent, and when.
    pub navigation_focus_type: Option<i32>,
    pub navigation_focus_at: Option<Duration>,
    /// Responses naming `NAV_FOCUS_NATIVE` — the head unit keeping its own navigation —
    /// as distinct from one that could not be read at all.
    pub navigation_focus_denials: u64,
    pub navigation_focus_invalid: u64,
    /// The head unit never answered the focus request within the wait.
    pub navigation_focus_timeout: bool,
    /// `NAV_FOCUS_NATIVE` was sent back at the end. There is no `RELEASE` on this
    /// exchange, so handing the cluster back is a request for the head unit's own
    /// navigation (P6 plan §3, `ids::NAVIGATION_FOCUS_REQUEST`).
    pub navigation_focus_released: bool,
    /// Mid-session hand-backs: a live route ended and `NAV_FOCUS_NATIVE` went out then,
    /// rather than at the end of the session (P7.4). Each later route's request is in
    /// `navigation_focus_requests` beside the first.
    pub navigation_focus_handbacks: u64,
    /// Focus responses that arrived while handed back, and the last one's type. Not
    /// denials: nothing was being asked for. What a head unit says to a hand-back has
    /// never been seen from a vehicle, which is why it is recorded rather than dropped.
    pub navigation_focus_handback_answers: u64,
    pub navigation_focus_handback_type: Option<i32>,
    /// `NavigationState` messages sent, by state, in the order the states were first
    /// sent.
    pub navigation_states_sent: Vec<(&'static str, u64)>,
    pub navigation_notifications_sent: u64,
    pub navigation_distances_sent: u64,
    /// Updates the rate limit held back, counted once per distinct maneuver held rather
    /// than once per timer tick.
    pub navigation_suppressed: u64,
    /// The script played, its legs and its length, and the units its distances were
    /// written in.
    pub navigation_script: Option<String>,
    /// `script` or `route` ([`ProjectionConfig::navigation_source`]); absent when no
    /// navigation channel was driven.
    pub navigation_source: Option<&'static str>,
    /// Distinct maneuvers put on the wire: each change of maneuver counted once, however
    /// often it was restated. For a route this is the number a drive's maneuver list is
    /// checked against.
    pub navigation_maneuvers: u64,
    /// Times the app's guidance reported the destination reached (P6.5).
    pub navigation_arrivals: u64,
    /// The route reached its destination inside the session, so the whole lifecycle —
    /// active, maneuvers, arrival, inactive, focus handed back — is in this one report.
    pub navigation_completed: bool,
    /// The last maneuver put on the wire, as [`Guidance::summary`] renders it: the
    /// maneuver and how far away it was, and nothing that says where anybody was going.
    pub navigation_last_step: Option<String>,
    /// Messages the head unit sent on this channel that this source has no role for.
    pub navigation_unhandled: u64,
    /// Transport buttons delivered to the app, in total and by action.
    pub transport_keys: u64,
    pub transport_by_action: Vec<(&'static str, u64)>,
    pub focus_requests: u64,
    /// Every video focus indication the head unit sent, in order, as (elapsed, raw mode,
    /// unrequested, what this source did), at most [`VIDEO_FOCUS_SEQUENCE_LIMIT`].
    ///
    /// The counters alone cannot tell "the head unit never sent another indication" apart
    /// from "it sent one this source discarded", and on 2026-09-19 that was the whole
    /// question: Ford's last word was PROJECTED at +15.8 s while its screen showed native
    /// UI for the following 26 s (report 6d6fbb29). The action is recorded with the mode so
    /// an indication that arrived outside `Step::Streaming` is visible as ignored rather
    /// than absent.
    pub focus_sequence: Vec<(Duration, i32, Option<bool>, &'static str)>,
    pub focus_indications: u64,
    pub focus_unrequested: u64,
    pub focus_mode: Option<i32>,
    pub focus_projected_at: Option<Duration>,
    pub focus_losses: u64,
    pub focus_regains: u64,
    /// Stop indications sent because the head unit took the screen, and stream restarts
    /// sent when it handed it back (spec §8.2). These are the treatment check for the
    /// re-attach fix: a loss with no stop indication is the old pause-in-place behaviour.
    pub focus_stop_indications: u64,
    pub focus_restarts: u64,
    /// Acknowledgements that arrived for a stream session a focus loss had already stopped.
    /// Expected in small numbers after every transition (at most the window), and counted
    /// apart from `acks_wrong_session` and `acks_invalid` so neither reads as a fault.
    pub acks_previous_session: u64,
    pub start_session: Option<i32>,
    pub start_config: Option<u32>,
    pub started_at: Option<Duration>,
    pub first_keyframe_written_at: Option<Duration>,
    pub projecting: Option<Duration>,
    pub stop_indication_sent: bool,
    pub configs_sent: u64,
    pub keyframes_sent: u64,
    pub frames_sent: u64,
    pub media_bytes_sent: u64,
    pub acks: u64,
    pub acked_units: u64,
    pub acks_wrong_session: u64,
    pub acks_invalid: u64,
    pub acks_excess: u64,
    pub outstanding: u32,
    pub outstanding_max: u32,
    pub window_full_events: u64,
    pub keyframe_requests: u64,
    /// Of those, the ones asked for after a congestion episode drained.
    pub keyframe_requests_drained: u64,
    /// Stream restarts this source made itself because the head unit kept falling behind
    /// ([`ProjectionConfig::congestion_restart`]), counted apart from `focus_restarts`.
    pub congestion_restarts: u64,
    /// Restarts that were due but held back by the cooldown.
    pub congestion_restarts_held: u64,
    /// One row per minute of projection that had any video traffic, oldest first, at most
    /// [`TIMELINE_LIMIT`]: totals only can hide a few bad minutes in an hour.
    pub timeline: Vec<MinuteRow>,
    pub timeline_truncated: bool,
    pub media: MediaCounters,
    pub input: InputStats,
    pub app_events_dropped: u64,
}

impl fmt::Display for ProjectionReport {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fn ms(d: Option<Duration>) -> String {
            d.map_or("-".into(), |d| d.as_millis().to_string())
        }
        fn opt<T: fmt::Display>(v: &Option<T>) -> String {
            v.as_ref().map_or("-".into(), T::to_string)
        }
        fn status(v: Option<i32>) -> String {
            v.map_or("-".into(), |v| format!("{v} ({})", status_name(v)))
        }
        writeln!(f, "projection_step={}", self.step)?;
        for o in &self.opens {
            writeln!(
                f,
                "projection_open_{:03}={} sent_ms={} response_ms={} status={}",
                o.channel,
                o.service,
                o.sent_at.as_millis(),
                ms(o.response_at),
                status(o.status)
            )?;
        }
        writeln!(
            f,
            "projection_open_responses_unexpected={} projection_open_responses_misrouted={} \
             projection_inbound_opens_rejected={}",
            self.open_responses_unexpected,
            self.open_responses_misrouted,
            self.inbound_opens_rejected
        )?;
        let setup = self.setup_status.map_or("-".into(), |s| {
            let name =
                av_channel_setup_status::Enum::try_from(s).map_or("unknown", |e| e.as_str_name());
            format!("{s} ({name})")
        });
        let accepted: Vec<String> = self.accepted_configs.iter().map(u32::to_string).collect();
        writeln!(
            f,
            "av_setup_status={setup} av_setup_ms={} av_setup_window={}{} av_setup_accepted_configs={}",
            ms(self.setup_at),
            opt(&self.window),
            if self.window_defaulted { " (defaulted)" } else { "" },
            if accepted.is_empty() { "-".into() } else { accepted.join(",") }
        )?;
        match &self.selected {
            Some(v) => writeln!(f, "video_selected={v} {}", v.geometry())?,
            None => writeln!(f, "video_selected=-")?,
        }
        let codes: Vec<String> = self.binding_keycodes.iter().map(i32::to_string).collect();
        writeln!(
            f,
            "input_binding_keycodes={} input_binding_status={} input_binding_ms={}",
            if codes.is_empty() {
                "-".into()
            } else {
                codes.join(",")
            },
            status(self.binding_status),
            ms(self.binding_at)
        )?;
        let sensors: Vec<String> = self
            .sensor_requests
            .iter()
            .map(|(t, s)| format!("{t}:{}", s.map_or("-".into(), |s| s.to_string())))
            .collect();
        writeln!(
            f,
            "projection_setup_field={} projection_setup_value={}",
            if self.setup_field.is_empty() {
                "-"
            } else {
                self.setup_field
            },
            opt(&self.setup_value)
        )?;
        writeln!(
            f,
            "projection_extra_opened={} projection_extra_refused={}",
            self.extra_opened, self.extra_refused
        )?;
        writeln!(
            f,
            "ui_config_updates={} ui_config_responses={} ui_config_invalid={} \
             ui_config_reply_len={}",
            self.ui_config_updates,
            self.ui_config_responses,
            self.ui_config_invalid,
            opt(&self.ui_config_reply_len)
        )?;
        writeln!(
            f,
            "ui_config_last={}",
            self.ui_config_last.as_deref().unwrap_or("-")
        )?;
        let types = |values: &[i32]| -> String {
            if values.is_empty() {
                return "-".into();
            }
            values
                .iter()
                .map(i32::to_string)
                .collect::<Vec<_>>()
                .join(",")
        };
        writeln!(
            f,
            "sensor_set={} sensor_advertised={} sensor_location={}",
            if self.sensor_set.is_empty() {
                "none"
            } else {
                &self.sensor_set
            },
            types(&self.sensor_advertised),
            if self.sensor_location_reported {
                "reported"
            } else {
                "redacted"
            }
        )?;
        writeln!(
            f,
            "sensor_requests={} sensor_errors={} sensor_events={} sensor_invalid={} \
             sensor_empty={} sensor_unrequested={} sensor_driving_status={} sensor_night={} \
             vehicle_events={}",
            if sensors.is_empty() {
                "-".into()
            } else {
                sensors.join(",")
            },
            self.sensor_errors,
            self.sensor_events,
            self.sensor_invalid,
            self.sensor_empty,
            self.sensor_unrequested,
            opt(&self.driving_status),
            opt(&self.night),
            self.vehicle_events
        )?;
        // The P5.2 harvest: one line per type, then the summary that answers the question
        // the slice exists for — which advertised sensors carry values, and which are
        // advertised, accepted and silent.
        for h in &self.sensor_values {
            let name = sensor_type::Enum::try_from(h.sensor_type)
                .map_or("unknown", |t| t.as_str_name())
                .to_owned();
            writeln!(
                f,
                "sensor_value_{:03}={name} requested={} count={} last={}",
                h.sensor_type,
                h.requested,
                h.count,
                h.last.as_deref().unwrap_or("-")
            )?;
        }
        let populated: Vec<i32> = self
            .sensor_values
            .iter()
            .filter(|h| h.count > 0)
            .map(|h| h.sensor_type)
            .collect();
        // Silent means accepted and then wordless. A type the head unit refused, errored
        // on, or never answered is not silent — it never started — so it is left out
        // rather than read as a populated/unpopulated verdict.
        let silent: Vec<i32> =
            self.sensor_values
                .iter()
                .filter(|h| {
                    h.requested
                        && h.count == 0
                        && self.sensor_requests.iter().any(|(t, s)| {
                            *t == h.sensor_type && *s == Some(status::Enum::Ok as i32)
                        })
                })
                .map(|h| h.sensor_type)
                .collect();
        writeln!(
            f,
            "sensor_populated={} sensor_silent={}",
            types(&populated),
            types(&silent)
        )?;
        // P5.3 audio out, in the order the slice happens: what was selected, whether the
        // channel opened, what the setup gave back, the focus exchange, and then the
        // stream itself.
        writeln!(
            f,
            "audio_requested={} audio_channel={} audio_open={} audio_refused={}",
            self.audio_requested,
            opt(&self.audio_channel),
            self.audio_open,
            self.audio_refused
        )?;
        writeln!(
            f,
            "audio_selected={}",
            self.audio_selected.as_deref().unwrap_or("-")
        )?;
        let accepted: Vec<String> = self
            .audio_accepted_configs
            .iter()
            .map(u32::to_string)
            .collect();
        let setup = self.audio_setup_status.map_or("-".into(), |s| {
            let name =
                av_channel_setup_status::Enum::try_from(s).map_or("unknown", |e| e.as_str_name());
            format!("{s} ({name})")
        });
        writeln!(
            f,
            "audio_setup_status={setup} audio_setup_ms={} audio_setup_window={}{} \
             audio_setup_accepted_configs={}",
            ms(self.audio_setup_at),
            opt(&self.audio_window),
            if self.audio_window_defaulted {
                " (defaulted)"
            } else {
                ""
            },
            if accepted.is_empty() {
                "-".into()
            } else {
                accepted.join(",")
            }
        )?;
        let state = self.audio_focus_state.map_or("-".into(), |s| {
            let name = audio_focus_state::Enum::try_from(s).map_or("unknown", |e| e.as_str_name());
            format!("{s} ({name})")
        });
        writeln!(
            f,
            "audio_focus_requests={} audio_focus_responses={} audio_focus_invalid={} \
             audio_focus_state={state} audio_focus_unsolicited={} audio_focus_ms={} \
             audio_focus_denials={} audio_focus_volunteered={} audio_focus_losses={} \
             audio_focus_regains={} audio_focus_ducks={} audio_focus_released={} \
             audio_focus_uncertain={}",
            self.audio_focus_requests,
            self.audio_focus_responses,
            self.audio_focus_invalid,
            opt(&self.audio_focus_unsolicited),
            ms(self.audio_focus_at),
            self.audio_focus_denials,
            self.audio_focus_volunteered,
            self.audio_focus_losses,
            self.audio_focus_regains,
            self.audio_focus_ducks,
            self.audio_focus_released,
            self.audio_focus_uncertain
        )?;
        // The sequence, as `elapsed_ms:state:unsolicited`. A head unit may volunteer a
        // state before or after answering, so the order is the evidence and the last one
        // is not.
        let sequence: Vec<String> = self
            .audio_focus_sequence
            .iter()
            .map(|(at, state, unsolicited)| {
                format!(
                    "{}:{state}:{}",
                    at.as_millis(),
                    unsolicited.map_or("-".into(), |u| u.to_string())
                )
            })
            .collect();
        writeln!(
            f,
            "audio_focus_sequence={}",
            if sequence.is_empty() {
                "-".into()
            } else {
                sequence.join(",")
            }
        )?;
        writeln!(
            f,
            "audio_start_session={} audio_start_config={} audio_started_ms={} \
             audio_stop_indication_sent={}",
            opt(&self.audio_start_session),
            opt(&self.audio_start_config),
            ms(self.audio_started_at),
            self.audio_stop_indication_sent
        )?;
        writeln!(
            f,
            "audio_buffers_sent={} audio_media_bytes_sent={} audio_acks={} \
             audio_acked_units={} audio_acks_wrong_session={} audio_acks_invalid={} \
             audio_acks_excess={} audio_outstanding={} audio_outstanding_max={} \
             audio_window_full_events={} audio_stalled={} audio_underflows={} \
             audio_underflow_body_len={}",
            self.audio_buffers_sent,
            self.audio_bytes_sent,
            self.audio_acks,
            self.audio_acked_units,
            self.audio_acks_wrong_session,
            self.audio_acks_invalid,
            self.audio_acks_excess,
            self.audio_outstanding,
            self.audio_outstanding_max,
            self.audio_window_full_events,
            self.audio_stalled,
            self.audio_underflows,
            opt(&self.audio_underflow_body_len)
        )?;
        if let Some(audio) = &self.audio {
            write!(f, "{audio}")?;
        }
        // Album art is `bytes` with no upper bound in the schema, on a transport whose
        // fragment payload is 16 KiB; this slice never sends it (P5.1 open risk).
        writeln!(
            f,
            "media_channel={} media_open={} media_refused={} media_status_sent={} \
             media_metadata_sent={} media_suppressed={} media_track_sent={} \
             media_album_art=omitted media_events={} media_events_invalid={}",
            opt(&self.media_channel),
            self.media_open,
            self.media_refused,
            self.media_status_sent,
            self.media_metadata_sent,
            self.media_suppressed,
            self.media_track_sent,
            self.media_events,
            self.media_events_invalid
        )?;
        writeln!(
            f,
            "media_last_status={}",
            self.media_last_status.as_deref().unwrap_or("-")
        )?;
        let focus_type = self.navigation_focus_type.map_or("-".into(), |t| {
            let name = NavigationFocusType::try_from(t).map_or("unknown", |e| e.as_str_name());
            format!("{t} ({name})")
        });
        let states: Vec<String> = self
            .navigation_states_sent
            .iter()
            .map(|(name, count)| format!("{name}:{count}"))
            .collect();
        writeln!(
            f,
            "navigation_requested={} navigation_channel={} navigation_open={} \
             navigation_refused={} navigation_min_interval_ms={} navigation_interval_ms={} \
             navigation_floor={}",
            self.navigation_requested,
            opt(&self.navigation_channel),
            self.navigation_open,
            self.navigation_refused,
            opt(&self.navigation_min_interval_ms),
            self.navigation_interval_ms,
            self.navigation_floor
        )?;
        writeln!(
            f,
            "navigation_cluster_starts={} navigation_cluster_stops={} navigation_cluster_ms={} \
             navigation_cluster_timeout={} navigation_forced={}",
            self.navigation_cluster_starts,
            self.navigation_cluster_stops,
            ms(self.navigation_cluster_at),
            self.navigation_cluster_timeout,
            self.navigation_forced
        )?;
        writeln!(
            f,
            "navigation_focus_requests={} navigation_focus_responses={} \
             navigation_focus_type={focus_type} navigation_focus_ms={} \
             navigation_focus_denials={} navigation_focus_invalid={} \
             navigation_focus_timeout={} navigation_focus_released={}",
            self.navigation_focus_requests,
            self.navigation_focus_responses,
            ms(self.navigation_focus_at),
            self.navigation_focus_denials,
            self.navigation_focus_invalid,
            self.navigation_focus_timeout,
            self.navigation_focus_released
        )?;
        let handback_type = self.navigation_focus_handback_type.map_or("-".into(), |t| {
            let name = NavigationFocusType::try_from(t).map_or("unknown", |e| e.as_str_name());
            format!("{t} ({name})")
        });
        writeln!(
            f,
            "navigation_focus_handbacks={} navigation_focus_handback_answers={} \
             navigation_focus_handback_type={handback_type}",
            self.navigation_focus_handbacks, self.navigation_focus_handback_answers
        )?;
        writeln!(
            f,
            "navigation_states_sent={} navigation_notifications_sent={} \
             navigation_distances_sent={} navigation_suppressed={} navigation_completed={} \
             navigation_unhandled={}",
            if states.is_empty() {
                "-".into()
            } else {
                states.join(",")
            },
            self.navigation_notifications_sent,
            self.navigation_distances_sent,
            self.navigation_suppressed,
            self.navigation_completed,
            self.navigation_unhandled
        )?;
        // No road name, no instruction text and no destination: the P6 plan §9 puts a
        // destination on the same footing as `sensor_location`, and these being scripted
        // is not a reason to establish the habit.
        writeln!(
            f,
            "navigation_source={} navigation_maneuvers={} navigation_arrivals={}",
            self.navigation_source.unwrap_or("-"),
            self.navigation_maneuvers,
            self.navigation_arrivals
        )?;
        writeln!(
            f,
            "navigation_script={} navigation_last_step={}",
            self.navigation_script.as_deref().unwrap_or("-"),
            self.navigation_last_step.as_deref().unwrap_or("-")
        )?;
        let actions: Vec<String> = self
            .transport_by_action
            .iter()
            .map(|(name, count)| format!("{name}:{count}"))
            .collect();
        writeln!(
            f,
            "transport_keys={} transport_actions={}",
            self.transport_keys,
            if actions.is_empty() {
                "-".into()
            } else {
                actions.join(",")
            }
        )?;
        let mode = self.focus_mode.map_or("-".into(), |m| {
            let name = video_focus_mode::Enum::try_from(m).map_or("unknown", |e| e.as_str_name());
            format!("{m} ({name})")
        });
        writeln!(
            f,
            "video_focus_requests={} video_focus_indications={} video_focus_unrequested={} \
             video_focus_mode={mode} video_focus_projected_ms={} video_focus_losses={} \
             video_focus_regains={} video_focus_stop_indications={} video_focus_restarts={}",
            self.focus_requests,
            self.focus_indications,
            self.focus_unrequested,
            ms(self.focus_projected_at),
            self.focus_losses,
            self.focus_regains,
            self.focus_stop_indications,
            self.focus_restarts,
        )?;
        // The sequence, as `elapsed_ms:mode:unrequested:action`. The counters cannot say
        // whether a head unit stopped sending indications or sent one this source had no
        // use for; the order and the actions can.
        let focus_sequence: Vec<String> = self
            .focus_sequence
            .iter()
            .map(|(at, mode, unrequested, action)| {
                format!(
                    "{}:{mode}:{}:{action}",
                    at.as_millis(),
                    unrequested.map_or("-".into(), |u| u.to_string())
                )
            })
            .collect();
        writeln!(
            f,
            "video_focus_sequence={}",
            if focus_sequence.is_empty() {
                "-".into()
            } else {
                focus_sequence.join(",")
            }
        )?;
        writeln!(
            f,
            "video_start_session={} video_start_config={} video_started_ms={}\n\
             video_first_keyframe_written_ms={}\n\
             projecting_ms={} video_stop_indication_sent={}",
            opt(&self.start_session),
            opt(&self.start_config),
            ms(self.started_at),
            ms(self.first_keyframe_written_at),
            ms(self.projecting),
            self.stop_indication_sent
        )?;
        writeln!(
            f,
            "video_configs_sent={} video_keyframes_sent={} video_frames_sent={} \
             video_media_bytes_sent={} video_acks={} video_acked_units={} \
             video_acks_wrong_session={} video_acks_previous_session={} video_acks_invalid={} video_acks_excess={} \
             video_outstanding={} video_outstanding_max={} video_window_full_events={} \
             video_keyframe_requests={} video_keyframe_requests_drained={} \
             video_congestion_restarts={} video_congestion_restarts_held={}",
            self.configs_sent,
            self.keyframes_sent,
            self.frames_sent,
            self.media_bytes_sent,
            self.acks,
            self.acked_units,
            self.acks_wrong_session,
            self.acks_previous_session,
            self.acks_invalid,
            self.acks_excess,
            self.outstanding,
            self.outstanding_max,
            self.window_full_events,
            self.keyframe_requests,
            self.keyframe_requests_drained,
            self.congestion_restarts,
            self.congestion_restarts_held,
        )?;
        let timeline: Vec<String> = self.timeline.iter().map(MinuteRow::to_string).collect();
        writeln!(
            f,
            "video_timeline={}{}",
            if timeline.is_empty() {
                "-".into()
            } else {
                timeline.join(",")
            },
            if self.timeline_truncated {
                " (truncated)"
            } else {
                ""
            }
        )?;
        write!(f, "{}", self.media)?;
        let i = &self.input;
        writeln!(
            f,
            "input_events={} input_touch_events={} input_down={} input_up={} input_move={} \
             input_cancel={} input_pointer_down={} input_pointer_up={} input_pointers_max={} \
             input_key_events={} input_keys_down={} input_other_events={} input_rejected={} \
             input_invalid={} input_dropped_unfocused={} app_events_dropped={}",
            i.events,
            i.touch_events,
            i.down,
            i.up,
            i.moves,
            i.cancel,
            i.pointer_down,
            i.pointer_up,
            i.pointers_max,
            i.key_events,
            i.keys_down,
            i.other_events,
            i.rejected,
            i.invalid,
            i.dropped_unfocused,
            self.app_events_dropped
        )?;
        match i.touch_map {
            Some(m) => writeln!(
                f,
                "input_touch_space={} size={}x{} target={}",
                m.space.name(),
                m.size.0,
                m.size.1,
                m.target
            ),
            None => writeln!(f, "input_touch_space=-"),
        }?;
        match i.raw_range {
            Some([x0, x1, y0, y1]) => {
                writeln!(f, "input_raw_range=x={x0}..{x1} y={y0}..{y1}")
            }
            None => writeln!(f, "input_raw_range=-"),
        }?;
        writeln!(
            f,
            "input_last_touch={}",
            i.last_touch.as_deref().unwrap_or("-")
        )?;
        writeln!(
            f,
            "input_last_rejection={}",
            i.last_rejection.as_deref().unwrap_or("-")
        )
    }
}

fn focus_projected(mode: i32) -> bool {
    mode == video_focus_mode::Enum::Projected as i32
        || mode == video_focus_mode::Enum::ProjectedNoInputFocus as i32
}

impl Session {
    fn projection_report(&mut self) -> &mut ProjectionReport {
        self.report
            .projection
            .get_or_insert_with(ProjectionReport::default)
    }

    fn projection_mut(&mut self) -> &mut ProjectionState {
        self.projection
            .as_mut()
            .expect("projection state exists in projection stages")
    }

    pub(crate) fn push_app_event(&mut self, event: AppEvent) {
        let droppable = matches!(event, AppEvent::Touch(_) | AppEvent::Key { .. });
        if droppable && self.app_events.len() >= APP_EVENT_LIMIT {
            self.projection_report().app_events_dropped += 1;
            return;
        }
        self.app_events.push_back(event);
    }

    /// Validated discovery with `stop_after=projection`: choose the plan and open video.
    pub(crate) fn begin_projection(&mut self, now: Instant) {
        let caps = self
            .report
            .capabilities
            .clone()
            .expect("capabilities recorded before projection");
        let (Some(video), Some(input)) = (caps.video, caps.input.clone()) else {
            let detail = format!(
                "no usable {} for projection",
                match (caps.video.is_some(), caps.input.is_some()) {
                    (false, false) => "video or input",
                    (false, true) => "video",
                    _ => "input",
                }
            );
            return self.finish(Category::CapabilitiesInsufficient, detail, now);
        };
        let sensor = caps
            .sensor
            .clone()
            .filter(|_| self.config.projection.sensors.enabled());
        let sensor_types: Vec<i32> = sensor.as_ref().map_or(Vec::new(), |s| {
            self.config.projection.sensors.select(&s.types)
        });
        let requested_types = sensor_types.clone();
        let media: Option<MediaInfoChoice> = caps
            .media_info
            .filter(|_| self.config.projection.media_info);
        let navigation: Option<NavigationChoice> = caps
            .navigation
            .filter(|_| self.config.projection.navigation);
        // Discovery has already refused an audio channel this source cannot feed, so
        // reaching here means the format is one the app can produce.
        let audio: Option<AudioChoice> =
            caps.audio.clone().filter(|_| self.config.projection.audio);
        let candidates = caps
            .video_candidates
            .iter()
            .copied()
            .filter(|v| v.channel == video.channel)
            .collect();
        // Every advertised channel this source does not drive itself, lowest first. The
        // sensor channel is excluded only when it is actually being used.
        let used: Vec<u8> = [
            Some(video.channel),
            Some(input.channel),
            sensor.as_ref().map(|s| s.channel),
            media.map(|m| m.channel),
            navigation.as_ref().map(|n| n.channel),
            audio.as_ref().map(|a| a.channel),
        ]
        .into_iter()
        .flatten()
        .collect();
        let extra: Vec<u8> = if self.config.projection.open_all_channels {
            caps.channels
                .iter()
                .map(|c| c.id)
                .filter(|id| !used.contains(id))
                .collect()
        } else {
            Vec::new()
        };
        // The head unit's advertised floor and this source's own interval, resolved once:
        // the descriptor states the fastest it is willing to be told anything, so the
        // interval used is the larger of the two and the report says which won.
        let nav_min_interval = navigation.map(|n| {
            // A head unit advertising a negative floor is asking for nothing coherent;
            // zero is the honest reading of it and leaves the configured interval in
            // charge.
            Duration::from_millis(u64::from(n.minimum_interval_ms.max(0).unsigned_abs()))
        });
        let nav_configured = self.config.projection.navigation_interval;
        let nav_interval = nav_min_interval.map_or(nav_configured, |m| m.max(nav_configured));
        let nav_floor = match nav_min_interval {
            Some(m) if m > nav_configured => "head-unit",
            Some(_) => "source",
            None => "-",
        };
        let nav_script = self
            .config
            .projection
            .navigation_script
            .clone()
            .unwrap_or_default();
        let nav_units = self.config.projection.navigation_units;
        let nav_source = self.config.projection.navigation_source;
        let nav_summary = (navigation.is_some() && nav_source == Source::Script).then(|| {
            format!(
                "legs={} route_m={} pace_s={} units={}",
                nav_script.legs(),
                nav_script.route_metres().round() as i64,
                nav_script.duration().as_secs(),
                nav_units.name()
            )
        });
        let touch = input.touch.and_then(|t| {
            TouchMap::new(
                self.config.projection.touch_space,
                t,
                (video.width, video.height),
                video.content,
            )
        });
        self.projection_report().input.touch_map = touch;
        let mut streams = vec![StreamState::new(StreamKind::Video, video.channel)];
        if let Some(audio) = &audio {
            streams.push(StreamState::new(StreamKind::Audio, audio.channel));
        }
        self.projection = Some(ProjectionState {
            step: Step::OpenVideo,
            streams,
            video,
            candidates,
            audio: audio.clone(),
            touch,
            input,
            sensor: sensor.filter(|_| !sensor_types.is_empty()),
            sensor_types,
            media: media.map(|m| MediaChannel {
                channel: m.channel,
                open: false,
                next_send: None,
                last_send: None,
                sent_status: None,
                sent_track: None,
                deferred: None,
            }),
            navigation: navigation.as_ref().map(|n| NavigationChannel {
                channel: n.channel,
                open: false,
                interval: nav_interval,
                cluster_started: false,
                focus: NavFocus::Idle,
                deadline: None,
                active: false,
                started: None,
                script: nav_script,
                source: nav_source,
                wire_state: None,
                next_send: None,
                sent_leg: None,
                sent_leg_at: None,
                finished: false,
                stopped: false,
                handed_back: false,
            }),
            extra,
            limit_deadline: None,
            audio_focus_deadline: None,
            audio_focus_held_ever: false,
            projecting_since: None,
            first_keyframe_sent: false,
            last_keyframe_sent: None,
            last_keyframe_asked: None,
            congestion_seen: (0, 0),
            congestion_recent: VecDeque::new(),
            last_congestion_restart: None,
            input_open: false,
            sensor_open: false,
            vehicle: VehicleState::default(),
        });
        let sensor_set = self.config.projection.sensors.name();
        let sensor_location = self.config.projection.sensor_location;
        let audio_requested = self.config.projection.audio;
        // Advertised comes from discovery, so it is recorded even when the set is `none`:
        // it is the other half of the P5.2 question, advertised against populated.
        let advertised = caps.sensor.as_ref().map_or(Vec::new(), |s| s.types.clone());
        let report = self.projection_report();
        report.selected = Some(video);
        report.media_channel = media.map(|m| m.channel);
        report.navigation_requested = navigation.is_some();
        report.navigation_channel = navigation.as_ref().map(|n| n.channel);
        report.navigation_min_interval_ms = nav_min_interval.map(|m| m.as_millis());
        report.navigation_interval_ms = nav_interval.as_millis();
        report.navigation_floor = nav_floor;
        report.navigation_script = nav_summary;
        report.navigation_source = navigation.as_ref().map(|_| nav_source.name());
        report.audio_requested = audio_requested;
        report.audio_channel = audio.as_ref().map(|a| a.channel);
        report.audio_selected = audio.as_ref().map(ToString::to_string);
        report.step = Step::OpenVideo.name();
        report.sensor_set = sensor_set;
        report.sensor_location_reported = sensor_location;
        report.sensor_advertised = advertised;
        // One entry per requested type, before anything arrives: a type that stays at
        // count 0 was accepted and then never sent a value.
        report.sensor_values = requested_types
            .into_iter()
            .map(|sensor_type| SensorHarvest {
                sensor_type,
                requested: true,
                ..SensorHarvest::default()
            })
            .collect();
        self.report.stage = Stage::Setup;
        self.stage_deadline = None;
        self.next_ping = Some(now + self.config.ping_interval);
        self.open_channel(video.channel, "video", now);
    }

    fn set_step(&mut self, step: Step) {
        self.projection_mut().step = step;
        self.projection_report().step = step.name();
    }

    fn open_channel(&mut self, channel: u8, service: &'static str, now: Instant) {
        let at = self.elapsed(now);
        self.projection_report().opens.push(OpenRecord {
            channel,
            service,
            sent_at: at,
            response_at: None,
            status: None,
        });
        let request = ChannelOpenRequest {
            priority: 0,
            channel_id: i32::from(channel),
        };
        self.stage_deadline = Some(now + self.config.projection.channel_open_wait);
        let _ = self.send_on(
            channel,
            true,
            false,
            encode_message(ids::CHANNEL_OPEN_REQUEST, &request),
            true,
            now,
        );
    }

    /// Timer expiry of a setup step (stage `Setup`).
    pub(crate) fn setup_deadline(&mut self, now: Instant) {
        let Some(p) = &self.projection else { return };
        let step = p.step;
        let channel = match step {
            Step::OpenVideo | Step::AvSetup | Step::AwaitFocus => p.video.channel,
            Step::OpenInput | Step::Binding => p.input.channel,
            Step::OpenAudio | Step::AudioSetup => p.audio.as_ref().map_or(0, |a| a.channel),
            Step::OpenMediaInfo => p.media.as_ref().map_or(0, |m| m.channel),
            Step::OpenNavigation => p.navigation.as_ref().map_or(0, |n| n.channel),
            Step::OpenSensor | Step::SensorStart(_) => p.sensor.as_ref().map_or(0, |s| s.channel),
            Step::OpenExtra(i) => p.extra.get(i).copied().unwrap_or(0),
            Step::Streaming => 0,
        };
        match step {
            Step::AwaitFocus => {
                let detail = format!(
                    "no projected video focus on channel {channel} within {} ms (last mode {})",
                    self.config.projection.focus_wait.as_millis(),
                    self.projection_report()
                        .focus_mode
                        .map_or("-".into(), |m| m.to_string())
                );
                self.finish(Category::VideoFocusDenied, detail, now)
            }
            // An extra channel is opened only to match a session shape; a head unit that
            // never answers for one must not cost the drive.
            Step::OpenExtra(_) => {
                self.projection_report().extra_refused += 1;
                self.open_next_extra(now)
            }
            // Optional exactly like the sensor channel: a head unit that never answers
            // for audio must not cost the drive. This is the live possibility the slice
            // is built around — Ford advertises four audio channels and has never been
            // asked to open one.
            Step::OpenAudio | Step::AudioSetup => {
                self.projection_report().audio_refused = true;
                self.drop_audio_stream();
                self.open_media_info(now)
            }
            // Optional exactly like the sensor channel: a head unit that never answers
            // for now-playing must not cost the drive.
            Step::OpenMediaInfo => {
                self.projection_report().media_refused = true;
                self.open_navigation(now)
            }
            // Optional for the same reason, and the more likely of the two to happen:
            // Ford has advertised this channel since the first vehicle run and has never
            // been asked to open it.
            Step::OpenNavigation => {
                self.projection_report().navigation_refused = true;
                self.drop_navigation();
                self.open_sensor_channel(now)
            }
            Step::OpenSensor | Step::SensorStart(_) => {
                // Sensors are optional: record the missing response and continue.
                if let Step::SensorStart(i) = step {
                    let t = self.projection_mut().sensor_types.get(i).copied();
                    if let Some(t) = t {
                        let report = self.projection_report();
                        if !report.sensor_requests.iter().any(|(r, _)| *r == t) {
                            report.sensor_requests.push((t, None));
                        }
                    }
                }
                self.open_next_extra(now)
            }
            _ => {
                let what = match step {
                    Step::OpenVideo | Step::OpenInput => "ChannelOpenResponse",
                    Step::AvSetup => "AV setup response",
                    _ => "input binding response",
                };
                let detail = format!(
                    "no {what} on channel {channel} within {} ms ({})",
                    match step {
                        Step::OpenVideo | Step::OpenInput =>
                            self.config.projection.channel_open_wait,
                        _ => self.config.projection.setup_wait,
                    }
                    .as_millis(),
                    step.name()
                );
                self.finish(Category::SetupTimeout, detail, now)
            }
        }
    }

    /// Projection deadlines other than setup steps.
    pub(crate) fn projection_deadlines(&self) -> [Option<Instant>; 9] {
        let Some(p) = &self.projection else {
            return [None; 9];
        };
        // One stall deadline per stream, both with the same timeout but with different
        // consequences: video failing the session, audio only stopping itself.
        let stall = |kind: StreamKind| {
            p.index_of(kind).and_then(|i| {
                let s = &p.streams[i];
                s.stall_since
                    .filter(|_| s.focused)
                    .map(|since| since + self.config.projection.ack_timeout)
            })
        };
        let (stall, audio_stall) = (stall(StreamKind::Video), stall(StreamKind::Audio));
        let first_unit = p.video().first_unit_deadline;
        // Only when there is something to wake *for*. `next_send` is set after every send
        // and never clears, so returning it unconditionally left a permanently past
        // deadline once the last one elapsed — a zero timeout every time round the poll
        // loop, spinning the session thread for the rest of the projection.
        //
        // Two things are genuinely pending: an update the rate limit is holding back (the
        // app publishes once and does not publish again, so nothing else would carry it),
        // and the next periodic restatement.
        let media = p.media.as_ref().filter(|m| m.open).and_then(|m| {
            if m.deferred.is_some() {
                m.next_send
            } else {
                m.last_send
                    .map(|last| last + self.config.projection.media_refresh)
            }
        });
        // The cluster handshake's wait, and the next update once the sequence is running.
        // The same rule `media` follows above: only a deadline there is something to wake
        // *for*, so a finished route leaves no permanently past instant behind to spin the
        // poll loop on.
        let navigation = p.navigation.as_ref().and_then(|n| match n.sending() {
            true => n.next_send,
            false => None,
        });
        let navigation_wait = p.navigation.as_ref().and_then(|n| n.deadline);
        [
            first_unit,
            stall,
            p.limit_deadline,
            media,
            audio_stall,
            p.audio_focus_deadline,
            navigation,
            navigation_wait,
            // The drained congestion episode's keyframe; asked for in `pump_media`.
            self.drained_keyframe_due(),
        ]
    }

    pub(crate) fn projection_timers(&mut self, now: Instant) {
        let [first_unit, stall, limit, _media, audio_stall, audio_focus, _navigation, nav_wait, _keyframe] =
            self.projection_deadlines();
        if first_unit.is_some_and(|d| now >= d) {
            let detail = format!(
                "no encoder frame within {} ms of the start indication",
                self.config.projection.first_unit_wait.as_millis()
            );
            return self.finish(Category::EncoderFailed, detail, now);
        }
        if stall.is_some_and(|d| now >= d) {
            let (outstanding, window) = {
                let s = self.projection_mut().video();
                (s.outstanding, s.window)
            };
            let detail = format!(
                "{outstanding} media units unacknowledged (window {window}) for {} ms",
                self.config.projection.ack_timeout.as_millis()
            );
            return self.finish(Category::FlowControlTimeout, detail, now);
        }
        // An audio stall is recorded and the stream stopped. It is not
        // FLOW_CONTROL_TIMEOUT: audio is an opt-in integration, and losing the picture
        // because the head unit stopped acknowledging sound would be the wrong trade.
        if audio_stall.is_some_and(|d| now >= d) {
            self.projection_report().audio_stalled = true;
            self.stop_audio("audio acknowledgement window stalled", now);
        }
        // Audio focus that never arrives skips audio for the rest of the session.
        if audio_focus.is_some_and(|d| now >= d) {
            self.projection_mut().audio_focus_deadline = None;
            self.projection_report().audio_refused = true;
            self.stop_audio("no audio focus response", now);
        }
        // The cluster never announced itself, or never answered the focus request.
        // Recorded, and then either the sequence is driven anyway or nothing more happens
        // on the channel — which of the two is [`ProjectionConfig::navigation_force`].
        if nav_wait.is_some_and(|d| now >= d) {
            let waiting_for_cluster = self
                .projection
                .as_ref()
                .and_then(|p| p.navigation.as_ref())
                .is_some_and(|n| !n.cluster_started);
            if let Some(nav) = self.projection_mut().navigation.as_mut() {
                nav.deadline = None;
            }
            let report = self.projection_report();
            match waiting_for_cluster {
                true => report.navigation_cluster_timeout = true,
                false => report.navigation_focus_timeout = true,
            }
            if self.config.projection.navigation_force {
                self.navigation_progress(now);
            }
        }
        if limit.is_some_and(|d| now >= d) {
            self.begin_shutdown(ShutdownCause::ProjectionLimit, now);
        }
    }

    /// A message on a service channel, or a channel-0 message with the 0x04 bit, after TLS
    /// in a projection stage.
    pub(crate) fn service_message(&mut self, message: &Message, id: u16, now: Instant) {
        let encrypted = message.flags & FLAG_ENCRYPTED != 0;
        let category = message.flags & FLAG_CATEGORY != 0;
        if !encrypted {
            return self.unhandled(message, id, self.report.stage, now);
        }
        if category {
            return match id {
                ids::CHANNEL_OPEN_RESPONSE if message.channel != 0 => {
                    self.open_response(message.channel, &message.bytes, now)
                }
                ids::CHANNEL_OPEN_RESPONSE => {
                    self.projection_report().open_responses_misrouted += 1;
                }
                ids::CHANNEL_OPEN_REQUEST => self.inbound_open(message.channel, now),
                ids::CHANNEL_CLOSE_NOTIFICATION => self.channel_closed(message.channel, now),
                _ => self.unhandled(message, id, self.report.stage, now),
            };
        }
        if id == ids::CHANNEL_OPEN_RESPONSE {
            self.projection_report().open_responses_misrouted += 1;
            return;
        }
        let Some(p) = &self.projection else {
            return self.unhandled(message, id, self.report.stage, now);
        };
        let channel = message.channel;
        let body = &message.bytes[2..];
        // AV channels are keyed by a channel → stream lookup (P5.3), so 0x8003 means the
        // video setup response on one channel and the audio setup response on another
        // without either side having to know which is which.
        // Each stream is dispatchable only once its own open has been accepted: video
        // from the moment its open step is past, audio from the moment the head unit said
        // yes. Anything earlier on those channels stays an unhandled message.
        let stream = p.stream_at(channel).filter(|i| match p.streams[*i].kind {
            StreamKind::Video => p.step != Step::OpenVideo,
            StreamKind::Audio => p.streams[*i].open,
        });
        if let Some(index) = stream {
            match (p.streams[index].kind, id) {
                (_, ids::av::SETUP_RESPONSE) => self.setup_response(index, body, now),
                (_, ids::av::ACK_INDICATION) => self.media_ack(index, body, now),
                (StreamKind::Video, ids::av::VIDEO_FOCUS_INDICATION) => {
                    self.focus_indication(body, now)
                }
                (StreamKind::Video, ids::av::UPDATE_UI_CONFIG_REQUEST) => {
                    self.ui_config_request(body, now)
                }
                // Accepted on an audio channel without counting as unhandled: it is one
                // per underflow event, never answered, and the most valuable diagnostic
                // in the audio path.
                (StreamKind::Audio, ids::av::AUDIO_UNDERFLOW) => self.audio_underflow(body),
                _ => self.unhandled(message, id, self.report.stage, now),
            }
        } else if channel == p.input.channel && p.input_open {
            match id {
                ids::input::BINDING_RESPONSE => self.binding_response(body, now),
                ids::input::EVENT_INDICATION => self.input_event(body),
                _ => self.unhandled(message, id, self.report.stage, now),
            }
        } else if p.sensor.as_ref().is_some_and(|s| s.channel == channel) && p.sensor_open {
            match id {
                ids::sensor::START_RESPONSE => self.sensor_start_response(body, now),
                ids::sensor::EVENT_INDICATION => self.sensor_event(body),
                ids::sensor::ERROR => self.sensor_error(body, now),
                _ => self.unhandled(message, id, self.report.stage, now),
            }
        } else if p
            .media
            .as_ref()
            .is_some_and(|m| m.channel == channel && m.open)
        {
            match id {
                ids::media::PLAYBACK_STATUS_EVENT => self.media_event(body),
                _ => self.unhandled(message, id, self.report.stage, now),
            }
        } else if p
            .navigation
            .as_ref()
            .is_some_and(|n| n.channel == channel && n.open)
        {
            // The only two messages the head unit sends on this channel. Anything else is
            // counted here rather than among the unhandled: it arrived on a channel this
            // source asked for, which is a different fact from a message on a channel
            // nobody opened.
            match id {
                ids::navigation::CLUSTER_START => self.navigation_cluster_start(body, now),
                ids::navigation::CLUSTER_STOP => self.navigation_cluster_stop(body),
                _ => self.projection_report().navigation_unhandled += 1,
            }
        } else {
            self.unhandled(message, id, self.report.stage, now)
        }
    }

    fn open_response(&mut self, channel: u8, bytes: &[u8], now: Instant) {
        let Some(p) = &self.projection else { return };
        let (expected, service) = match p.step {
            Step::OpenVideo => (Some(p.video.channel), "video"),
            Step::OpenInput => (Some(p.input.channel), "input"),
            Step::OpenAudio => (p.audio.as_ref().map(|a| a.channel), "audio"),
            Step::OpenMediaInfo => (p.media.as_ref().map(|m| m.channel), "media_info"),
            Step::OpenNavigation => (p.navigation.as_ref().map(|n| n.channel), "navigation"),
            Step::OpenSensor => (p.sensor.as_ref().map(|s| s.channel), "sensor"),
            Step::OpenExtra(i) => (p.extra.get(i).copied(), "extra"),
            _ => (None, ""),
        };
        if expected != Some(channel) {
            self.projection_report().open_responses_unexpected += 1;
            return;
        }
        let at = self.elapsed(now);
        let response = decode::<ChannelOpenResponse>(&bytes[2..]);
        let status = response.as_ref().ok().map(|r| r.status);
        if let Some(record) = self
            .projection_report()
            .opens
            .iter_mut()
            .rev()
            .find(|o| o.channel == channel)
        {
            record.response_at = Some(at);
            record.status = status;
        }
        let failure = match response {
            Err(e) => Some(format!(
                "invalid ChannelOpenResponse on channel {channel}: {e}"
            )),
            Ok(r) if r.status != status::Enum::Ok as i32 => Some(format!(
                "head unit refused to open {service} channel {channel}: status {} ({})",
                r.status,
                status_name(r.status)
            )),
            Ok(_) => None,
        };
        self.stage_deadline = None;
        if service == "extra" {
            let report = self.projection_report();
            if failure.is_some() {
                report.extra_refused += 1;
            } else {
                report.extra_opened += 1;
            }
            return self.open_next_extra(now);
        }
        if service == "audio" {
            // Optional: a refused audio channel is recorded and skipped. An accepted one
            // goes straight into its own AV setup, on its own channel and with its own
            // window, before the rest of the optional services.
            if failure.is_some() {
                self.projection_report().audio_refused = true;
                self.drop_audio_stream();
                return self.open_media_info(now);
            }
            self.projection_report().audio_open = true;
            if let Some(index) = self.projection_mut().index_of(StreamKind::Audio) {
                self.projection_mut().streams[index].open = true;
            }
            return self.request_audio_setup(now);
        }
        if service == "media_info" {
            // Optional: a refused now-playing channel is recorded and skipped. There is
            // no setup handshake on this channel — an accepted open is the whole of it,
            // and the first message may go out immediately.
            let report = self.projection_report();
            match failure.is_none() {
                true => report.media_open = true,
                false => report.media_refused = true,
            }
            if failure.is_none() {
                if let Some(media) = self.projection_mut().media.as_mut() {
                    media.open = true;
                }
            }
            self.open_navigation(now);
            return self.pump_now_playing(now);
        }
        if service == "navigation" {
            // Optional: a refused cluster channel is recorded and skipped. An accepted
            // open is only the beginning here — the cluster still has to announce itself
            // and grant focus — so this starts the wait for `InstrumentClusterStart` and
            // moves the setup chain on without it.
            let report = self.projection_report();
            match failure.is_none() {
                true => report.navigation_open = true,
                false => report.navigation_refused = true,
            }
            match failure.is_none() {
                true => {
                    let wait = self.config.projection.navigation_focus_wait;
                    if let Some(nav) = self.projection_mut().navigation.as_mut() {
                        nav.open = true;
                        nav.deadline = Some(now + wait);
                    }
                }
                false => self.drop_navigation(),
            }
            self.open_sensor_channel(now);
            // A head unit that announced its cluster the instant it accepted the open, or
            // a source told not to wait for one, must not sit until the next timer.
            return self.navigation_progress(now);
        }
        if service == "sensor" {
            // Optional: a refused sensor channel is recorded and skipped.
            if failure.is_none() {
                self.projection_mut().sensor_open = true;
                return self.request_sensor(0, now);
            }
            return self.open_next_extra(now);
        }
        if let Some(detail) = failure {
            return self.finish(Category::ChannelRejected, detail, now);
        }
        if service == "video" {
            self.set_step(Step::AvSetup);
            self.stage_deadline = Some(now + self.config.projection.setup_wait);
            let selected = self.projection_mut().video.config_index as i32;
            let request = AvChannelSetupRequest {
                media_codec_type: match self.config.projection.setup_field {
                    SetupField::CodecType => CODEC_H264_BP,
                    SetupField::ConfigIndex => selected,
                },
            };
            let video = self.projection_mut().video.channel;
            let field = self.config.projection.setup_field.name();
            let value = request.media_codec_type;
            let report = self.projection_report();
            report.setup_field = field;
            report.setup_value = Some(value);
            let _ = self.send_on(
                video,
                false,
                false,
                encode_message(ids::av::SETUP_REQUEST, &request),
                true,
                now,
            );
        } else {
            let p = self.projection_mut();
            p.input_open = true;
            let (input, keycodes) = (p.input.channel, p.input.supported_keycodes.clone());
            self.set_step(Step::Binding);
            self.projection_report().binding_keycodes = keycodes.clone();
            self.stage_deadline = Some(now + self.config.projection.setup_wait);
            let request = BindingRequest {
                scan_codes: keycodes,
            };
            let _ = self.send_on(
                input,
                false,
                false,
                encode_message(ids::input::BINDING_REQUEST, &request),
                true,
                now,
            );
        }
    }

    fn inbound_open(&mut self, channel: u8, now: Instant) {
        self.projection_report().inbound_opens_rejected += 1;
        let response = ChannelOpenResponse {
            status: status::Enum::InvalidChannel as i32,
        };
        let _ = self.send_on(
            channel,
            true,
            false,
            encode_message(ids::CHANNEL_OPEN_RESPONSE, &response),
            true,
            now,
        );
    }

    fn channel_closed(&mut self, channel: u8, now: Instant) {
        let Some(p) = &self.projection else { return };
        if channel == p.video.channel || channel == p.input.channel {
            let detail = format!("head unit closed channel {channel}");
            return self.finish(Category::ChannelRejected, detail, now);
        }
        if p.sensor.as_ref().is_some_and(|s| s.channel == channel) {
            self.projection_mut().sensor_open = false;
            self.projection_report().sensor_errors += 1;
        }
        // Closing audio costs the integration, not the drive, for the same reason a
        // refused open does.
        if self
            .projection
            .as_ref()
            .is_some_and(|p| p.audio.as_ref().is_some_and(|a| a.channel == channel))
        {
            self.projection_report().audio_open = false;
            self.stop_audio("head unit closed the audio channel", now);
            self.drop_audio_stream();
        }
        // Closing now-playing costs the integration, not the drive: the head unit simply
        // stops being told what is playing.
        if self
            .projection
            .as_ref()
            .is_some_and(|p| p.media.as_ref().is_some_and(|m| m.channel == channel))
        {
            if let Some(media) = self.projection_mut().media.as_mut() {
                media.open = false;
            }
            self.projection_report().media_open = false;
        }
        // Closing the cluster channel, likewise. The state and the focus hand-back go out
        // first, while the channel is still notionally ours: a head unit that took it away
        // has still been told the route is over.
        if self
            .projection
            .as_ref()
            .is_some_and(|p| p.navigation.as_ref().is_some_and(|n| n.channel == channel))
        {
            self.stop_navigation("head unit closed the navigation channel", now);
            let report = self.projection_report();
            report.navigation_open = false;
            report.navigation_refused = true;
            self.drop_navigation();
        }
    }

    fn setup_response(&mut self, index: usize, body: &[u8], now: Instant) {
        match self.projection_mut().streams[index].kind {
            StreamKind::Video => self.video_setup_response(body, now),
            StreamKind::Audio => self.audio_setup_response(index, body, now),
        }
    }

    fn video_setup_response(&mut self, body: &[u8], now: Instant) {
        let step = self.projection_mut().step;
        if step != Step::AvSetup {
            let detail = format!("AV setup response in step {}", step.name());
            return self.finish(Category::AvSetupFailed, detail, now);
        }
        let at = self.elapsed(now);
        let response = match decode::<AvChannelSetupResponse>(body) {
            Ok(response) => response,
            Err(e) => {
                let detail = format!("invalid AV setup response: {e}");
                return self.finish(Category::AvSetupFailed, detail, now);
            }
        };
        let report = self.projection_report();
        report.setup_at = Some(at);
        report.setup_status = Some(response.media_status);
        report.accepted_configs = response.configs.iter().copied().take(64).collect();
        report.window_defaulted = response.max_unacked.is_none();
        if response.media_status != av_channel_setup_status::Enum::Ok as i32 {
            let detail = format!("AV setup status {}", response.media_status);
            return self.finish(Category::AvSetupFailed, detail, now);
        }
        let window = response.max_unacked.unwrap_or(1);
        if window == 0 {
            return self.finish(Category::AvSetupFailed, "AV setup window 0".into(), now);
        }
        let space = self.config.projection.touch_space;
        let preference = self.config.video_preference;
        let p = self.projection_mut();
        if !response.configs.is_empty() {
            let accepted: Vec<VideoChoice> = p
                .candidates
                .iter()
                .copied()
                .filter(|v| response.configs.contains(&(v.config_index as u32)))
                .collect();
            let Some(video) = discovery::preferred(&accepted, preference) else {
                let detail = format!(
                    "head unit accepted configurations {:?}, none a usable H.264 main-display one",
                    response.configs
                );
                return self.finish(Category::AvSetupFailed, detail, now);
            };
            p.video = video;
            p.touch = p
                .input
                .touch
                .and_then(|t| TouchMap::new(space, t, (video.width, video.height), video.content));
        }
        let stream = p.video_mut();
        stream.window = window;
        stream.setup = true;
        let (video, input, touch) = (p.video, p.input.channel, p.touch);
        let report = self.projection_report();
        report.window = Some(window);
        report.selected = Some(video);
        report.input.touch_map = touch;
        self.set_step(Step::OpenInput);
        self.open_channel(input, "input", now);
    }

    fn binding_response(&mut self, body: &[u8], now: Instant) {
        let step = self.projection_mut().step;
        if step != Step::Binding {
            return self.projection_report().input.invalid += 1;
        }
        let at = self.elapsed(now);
        let response = match decode::<BindingResponse>(body) {
            Ok(response) => response,
            Err(e) => {
                let detail = format!("invalid input binding response: {e}");
                return self.finish(Category::InputBindingFailed, detail, now);
            }
        };
        let report = self.projection_report();
        report.binding_at = Some(at);
        report.binding_status = response.status;
        // The pinned schema makes status optional with OK as its default value.
        if let Some(code) = response.status.filter(|s| *s != status::Enum::Ok as i32) {
            let detail = format!("input binding status {code} ({})", status_name(code));
            return self.finish(Category::InputBindingFailed, detail, now);
        }
        self.stage_deadline = None;
        self.open_audio(now);
    }

    /// After input binding: the optional audio channel and its AV setup, then the optional
    /// now-playing channel, then the optional sensor channel, then the advertised channels
    /// this source does not use.
    ///
    /// Audio comes first among the optional services because it is the only one with a
    /// handshake of its own, and doing it next to the video setup keeps the two AV
    /// negotiations adjacent in a capture.
    fn open_audio(&mut self, now: Instant) {
        let audio = self.projection_mut().audio.as_ref().map(|a| a.channel);
        match audio {
            Some(channel) => {
                self.set_step(Step::OpenAudio);
                self.open_channel(channel, "audio", now);
            }
            None => self.open_media_info(now),
        }
    }

    /// `AVChannelSetupRequest` on the audio channel, declaring PCM.
    fn request_audio_setup(&mut self, now: Instant) {
        let Some(channel) = self.projection_mut().audio.as_ref().map(|a| a.channel) else {
            return self.open_media_info(now);
        };
        self.set_step(Step::AudioSetup);
        self.stage_deadline = Some(now + self.config.projection.setup_wait);
        let request = AvChannelSetupRequest {
            media_codec_type: CODEC_AUDIO_PCM,
        };
        let _ = self.send_on(
            channel,
            false,
            false,
            encode_message(ids::av::SETUP_REQUEST, &request),
            true,
            now,
        );
    }

    /// The head unit's answer to the audio AV setup. Every failure here is recorded and
    /// skipped: audio is opt-in and its refusal must not cost the picture.
    fn audio_setup_response(&mut self, index: usize, body: &[u8], now: Instant) {
        if self.projection_mut().step != Step::AudioSetup {
            self.projection_report().audio_refused = true;
            return;
        }
        let at = self.elapsed(now);
        self.stage_deadline = None;
        let response = match decode::<AvChannelSetupResponse>(body) {
            Ok(response) => response,
            Err(_) => {
                self.projection_report().audio_refused = true;
                self.drop_audio_stream();
                return self.open_media_info(now);
            }
        };
        let window = response.max_unacked.unwrap_or(1);
        let ok = response.media_status == av_channel_setup_status::Enum::Ok as i32 && window > 0;
        let report = self.projection_report();
        report.audio_setup_at = Some(at);
        report.audio_setup_status = Some(response.media_status);
        report.audio_window_defaulted = response.max_unacked.is_none();
        report.audio_accepted_configs = response.configs.iter().copied().take(64).collect();
        if !ok {
            report.audio_refused = true;
            self.drop_audio_stream();
            return self.open_media_info(now);
        }
        report.audio_window = Some(window);
        let stream = &mut self.projection_mut().streams[index];
        stream.window = window;
        stream.setup = true;
        self.open_media_info(now)
    }

    /// Forgets the audio stream after a refusal, so nothing later sends on a channel the
    /// head unit did not give us.
    fn drop_audio_stream(&mut self) {
        let Some(p) = self.projection.as_mut() else {
            return;
        };
        p.audio = None;
        p.streams.retain(|s| s.kind != StreamKind::Audio);
        p.audio_focus_deadline = None;
        self.audio.close();
    }

    /// After the video start indication: ask for audio focus on the **control channel**.
    ///
    /// The order is fixed — request `GAIN`, wait for the response, then send the audio
    /// start indication — because a head unit's mixer may have no route open until it has
    /// granted focus.
    fn request_audio_focus(&mut self, now: Instant) {
        let ready = self
            .projection
            .as_ref()
            .and_then(|p| p.index_of(StreamKind::Audio).map(|i| &p.streams[i]))
            .is_some_and(|s| s.open && s.setup);
        if !ready {
            return;
        }
        let wait = self.config.projection.audio_focus_wait;
        self.projection_mut().audio_focus_deadline = Some(now + wait);
        self.projection_report().audio_focus_requests += 1;
        let request = AudioFocusRequest {
            audio_focus_type: Some(audio_focus_type::Enum::Gain as i32),
        };
        let _ = self.send(
            encode_message(ids::AUDIO_FOCUS_REQUEST, &request),
            true,
            now,
        );
    }

    /// `AUDIO_FOCUS_RESPONSE` (0x0013) on the control channel.
    ///
    /// Focus is a state the head unit owns and may change at any time, so this is a state
    /// machine rather than an answer to a question. A head unit may volunteer a state
    /// before or after answering a request, may deny now and grant later when the operator
    /// selects the projection as the audio source, and may take focus back mid-stream.
    ///
    /// Two rules the first vehicle attempt forced (19 September, reports f9ce185b and
    /// dc2c60ee, Ford SYNC 4.0 answering `LOSS`):
    ///
    /// * **A loss is not a refusal.** It pauses the stream and leaves the request
    ///   standing; a later grant starts or resumes it. Giving up permanently on the first
    ///   non-grant would make a head unit that grants late indistinguishable from one that
    ///   never grants.
    /// * **`RELEASE` belongs to a grant.** It is sent only for focus actually held; after
    ///   a denial there is nothing to release, and claiming otherwise puts a state on the
    ///   wire this source does not hold.
    ///
    /// This source never ducks its own media: a `LOSS_TRANSIENT_CAN_DUCK` is counted and
    /// the PCM keeps flowing at full amplitude, because the head unit lowers the media
    /// channel in its own mixer.
    pub(crate) fn audio_focus_response(&mut self, body: &[u8], now: Instant) {
        use audio_focus_state::Enum as State;
        if self.projection.is_none() {
            return;
        }
        let at = self.elapsed(now);
        let response = match decode::<AudioFocusResponse>(body) {
            Ok(response) => response,
            Err(_) => {
                let report = self.projection_report();
                report.audio_focus_responses += 1;
                report.audio_focus_invalid += 1;
                // An unreadable response might have been a grant, so a later RELEASE is
                // no longer provably unnecessary.
                report.audio_focus_uncertain = true;
                return;
            }
        };
        let state = response.audio_focus_state;
        // The generated field is named `granted` because the vendored schema names it
        // that; it is the `unsolicited` flag. See `ProjectionReport::audio_focus_state`.
        let unsolicited = response.granted;
        let report = self.projection_report();
        report.audio_focus_responses += 1;
        report.audio_focus_state = Some(state);
        report.audio_focus_unsolicited = unsolicited;
        report.audio_focus_volunteered += u64::from(unsolicited == Some(true));
        report.audio_focus_at.get_or_insert(at);
        // The whole sequence, not just the latest: "what the last response said" cannot
        // tell a denial apart from a loss followed by a grant (spec §10).
        if report.audio_focus_sequence.len() < AUDIO_FOCUS_SEQUENCE_LIMIT {
            report.audio_focus_sequence.push((at, state, unsolicited));
        }
        self.projection_mut().audio_focus_deadline = None;
        if state == State::LossTransientCanDuck as i32 {
            self.projection_report().audio_focus_ducks += 1;
            return;
        }
        // **Only `audio_focus_state` decides.** It is the message's one `required` field,
        // and field 2 is the `unsolicited` flag rather than a grant — Ford sends it false
        // both with `LOSS`, a refusal (reports f9ce185b and dc2c60ee), and with `GAIN`
        // (5cb646ec and 9eb3a396). Gating on it cost a real grant: both of those last two
        // runs were counted as denials and no audio started. Android Auto is built the
        // same way, enabling its media and TTS streams on a GAIN before it so much as
        // loads the boolean (references.md).
        //
        // GAIN_TRANSIENT_GUIDANCE_ONLY is deliberately not a grant here: it is focus for a
        // guidance prompt, and this source has none to play.
        let gained = state == State::Gain as i32
            || state == State::GainMediaOnly as i32
            || state == State::GainTransient as i32;
        if gained {
            self.projection_mut().audio_focus_held_ever = true;
            return self.start_audio(now);
        }
        // INVALID, LOSS, LOSS_TRANSIENT, or a guidance-only grant this source cannot use.
        let stream = self
            .projection
            .as_ref()
            .and_then(|p| p.index_of(StreamKind::Audio).map(|i| &p.streams[i]));
        let Some((held, stopped)) = stream.map(|s| (s.focused, s.stop_emitted)) else {
            return;
        };
        // The answer to our own `RELEASE` arrives after the stream is already stopped and
        // is not a refusal of anything: a clean shutdown ends with exactly this.
        if stopped {
            return;
        }
        let report = self.projection_report();
        if held {
            report.audio_focus_losses += 1;
        } else if unsolicited != Some(true) {
            // A refusal of something this source asked for. A state the head unit
            // volunteered while we held nothing refuses nothing and is only counted as
            // volunteered, which is what `unsolicited` is for.
            report.audio_focus_denials += 1;
        }
        self.pause_audio("audio focus not held", now);
    }

    /// Focus lost or never granted: stop producing, keep the stream set up.
    ///
    /// The mirror of what video focus loss does — pause the link, discard what is queued,
    /// tell the app to stop — and deliberately *not* a stop indication or a `RELEASE`, so
    /// a later grant can resume without renegotiating anything.
    fn pause_audio(&mut self, reason: &str, now: Instant) {
        let _ = now;
        let Some(p) = self.projection.as_mut() else {
            return;
        };
        let Some(index) = p.index_of(StreamKind::Audio) else {
            return;
        };
        let stream = &mut p.streams[index];
        if !stream.focused && !stream.started {
            return;
        }
        let was_producing = stream.focused && stream.started;
        stream.focused = false;
        stream.stall_since = None;
        // `outstanding` is deliberately left alone, as the video focus-loss path leaves
        // it: those units are already on the wire and the head unit may still acknowledge
        // them. Zeroing it here would turn every one of those acknowledgements into an
        // excess.
        self.audio.pause();
        if was_producing {
            self.push_app_event(AppEvent::AudioStop {
                reason: reason.to_owned(),
            });
        }
    }

    /// Focus is granted: send the audio start indication and let the app produce PCM.
    fn start_audio(&mut self, now: Instant) {
        let at = self.elapsed(now);
        let Some(p) = self.projection.as_mut() else {
            return;
        };
        let (Some(index), Some(config)) = (
            p.index_of(StreamKind::Audio),
            p.audio.as_ref().and_then(|a| a.selected),
        ) else {
            return;
        };
        if p.streams[index].stop_emitted {
            return;
        }
        if p.streams[index].started {
            // Focus regained rather than granted for the first time. The stream is still
            // set up, so no start indication goes out — but the app stopped producing on
            // the loss, so it has to be told to start again or the resumed stream would
            // carry nothing.
            let channel = p.streams[index].channel;
            let session = p.streams[index].session_id;
            p.streams[index].focused = true;
            self.projection_report().audio_focus_regains += 1;
            self.audio.resume();
            self.push_app_event(AppEvent::AudioStart(AudioStart {
                session,
                channel,
                sample_rate: config.sample_rate,
                bit_depth: config.bit_depth,
                channel_count: config.channel_count,
                buffer_bytes: audio_buffer_bytes(config),
            }));
            return self.pump_media(now);
        }
        let stream = &mut p.streams[index];
        stream.started = true;
        stream.focused = true;
        let start = AvChannelStartIndication {
            session: stream.session_id,
            config: config.index as u32,
            session_type: None,
            media_config: None,
        };
        let channel = stream.channel;
        let report = self.projection_report();
        report.audio_start_session = Some(start.session);
        report.audio_start_config = Some(start.config);
        report.audio_started_at = Some(at);
        if !self.send_on(
            channel,
            false,
            false,
            encode_message(ids::av::START_INDICATION, &start),
            true,
            now,
        ) {
            return;
        }
        self.audio.resume();
        self.push_app_event(AppEvent::AudioStart(AudioStart {
            session: start.session,
            channel,
            sample_rate: config.sample_rate,
            bit_depth: config.bit_depth,
            channel_count: config.channel_count,
            buffer_bytes: audio_buffer_bytes(config),
        }));
    }

    /// Stops the audio stream: the stop indication, then `RELEASE`, then the app.
    ///
    /// `RELEASE` is always sent once focus has been asked for, whatever the reason for
    /// stopping. SYNC 3.4 owners report a focus/duck state machine that wedges until the
    /// cable is pulled, so no transient grant is ever left dangling.
    pub(crate) fn stop_audio(&mut self, reason: &str, now: Instant) {
        let Some(p) = self.projection.as_mut() else {
            return;
        };
        let Some(index) = p.index_of(StreamKind::Audio) else {
            return;
        };
        let stream = &mut p.streams[index];
        if stream.stop_emitted {
            return;
        }
        stream.stop_emitted = true;
        stream.focused = false;
        stream.stall_since = None;
        let (channel, started) = (stream.channel, stream.started);
        p.audio_focus_deadline = None;
        self.audio.close();
        if started
            && self.send_on(
                channel,
                false,
                false,
                encode_message(ids::av::STOP_INDICATION, &AvChannelStopIndication {}),
                true,
                now,
            )
        {
            self.projection_report().audio_stop_indication_sent = true;
        }
        // Only for focus actually held. After a request that was never granted there is
        // nothing to release, and sending one anyway claims a state this source does not
        // hold; an unreadable response is the one case where "never granted" cannot be
        // proved, so it is released defensively.
        let held_ever = self
            .projection
            .as_ref()
            .is_some_and(|p| p.audio_focus_held_ever)
            || self.projection_report().audio_focus_uncertain;
        if held_ever && !self.projection_report().audio_focus_released {
            let release = AudioFocusRequest {
                audio_focus_type: Some(audio_focus_type::Enum::Release as i32),
            };
            if self.send(
                encode_message(ids::AUDIO_FOCUS_REQUEST, &release),
                true,
                now,
            ) {
                self.projection_report().audio_focus_released = true;
            }
        }
        if started {
            self.push_app_event(AppEvent::AudioStop {
                reason: reason.to_owned(),
            });
        }
    }

    /// `0x800B AUDIO_UNDERFLOW` from the head unit: its mixer ran dry.
    ///
    /// Never answered, and the body is not decoded — modern Android Auto parses no
    /// payload for it while older schemas define `required int32 session_id = 1`, so the
    /// length is recorded and the bytes are left alone.
    fn audio_underflow(&mut self, body: &[u8]) {
        let report = self.projection_report();
        report.audio_underflows += 1;
        report.audio_underflow_body_len = Some(body.len());
    }

    /// After input binding and audio: the optional now-playing channel, then the optional
    /// navigation channel, then the optional sensor channel, then the advertised channels
    /// this source does not use.
    fn open_media_info(&mut self, now: Instant) {
        let media = self.projection_mut().media.as_ref().map(|m| m.channel);
        match media {
            Some(channel) => {
                self.set_step(Step::OpenMediaInfo);
                self.open_channel(channel, "media_info", now);
            }
            None => self.open_navigation(now),
        }
    }

    /// The `navigation` channel (P6.2), opened in the setup chain and then left alone.
    ///
    /// Only the open is part of setup. Everything after it — the cluster announcing
    /// itself, focus, the state and the maneuvers — happens on its own timers during
    /// projection, because none of it may hold up the picture.
    fn open_navigation(&mut self, now: Instant) {
        let navigation = self.projection_mut().navigation.as_ref().map(|n| n.channel);
        match navigation {
            Some(channel) => {
                self.set_step(Step::OpenNavigation);
                self.open_channel(channel, "navigation", now);
            }
            None => self.open_sensor_channel(now),
        }
    }

    /// Forgets the navigation channel after a refusal, so nothing later sends on a
    /// channel the head unit did not give us.
    fn drop_navigation(&mut self) {
        if let Some(p) = self.projection.as_mut() {
            p.navigation = None;
        }
    }

    /// `InstrumentClusterStart` (0x8001): the cluster display exists and is listening.
    ///
    /// The message is empty, and it is still decoded rather than assumed — a body where
    /// the schema says there is none would mean this is not the message we think it is.
    fn navigation_cluster_start(&mut self, body: &[u8], now: Instant) {
        if decode::<InstrumentClusterStart>(body).is_err() {
            return self.projection_report().navigation_unhandled += 1;
        }
        let at = self.elapsed(now);
        let report = self.projection_report();
        report.navigation_cluster_starts += 1;
        report.navigation_cluster_at.get_or_insert(at);
        if let Some(nav) = self.projection_mut().navigation.as_mut() {
            nav.cluster_started = true;
            nav.deadline = None;
        }
        self.navigation_progress(now)
    }

    /// `InstrumentClusterStop` (0x8002): the cluster is gone.
    ///
    /// Recorded and obeyed — the maneuvers stop — but the focus is not handed back and the
    /// script is not abandoned, because a head unit that stops the cluster may start it
    /// again (the operator changed the display, or a reverse camera took the screen). A
    /// later start resumes the sequence where the script's clock has got to, which is what
    /// a real route would do.
    fn navigation_cluster_stop(&mut self, body: &[u8]) {
        if decode::<InstrumentClusterStop>(body).is_err() {
            return self.projection_report().navigation_unhandled += 1;
        }
        self.projection_report().navigation_cluster_stops += 1;
        if let Some(nav) = self.projection_mut().navigation.as_mut() {
            nav.cluster_started = false;
        }
    }

    /// Moves the cluster handshake on as far as it can go right now: ask for focus once
    /// the cluster is there, and start the sequence once focus is held.
    fn navigation_progress(&mut self, now: Instant) {
        let forced = self.config.projection.navigation_force;
        let Some(nav) = self.projection.as_ref().and_then(|p| p.navigation.as_ref()) else {
            return;
        };
        if !nav.open || nav.stopped {
            return;
        }
        let (cluster, focus) = (nav.cluster_started, nav.focus);
        if !cluster && !forced {
            return;
        }
        // Handed back after a route ended (P7.4): the cluster is the head unit's again
        // until there is a route to put on it. A cluster that announces itself meanwhile,
        // or a wait that runs out, is no reason to take it back.
        if nav.handed_back && !matches!(self.guidance.snapshot(), Some(RouteState::Guiding(_))) {
            return;
        }
        match focus {
            NavFocus::Idle => self.request_navigation_focus(now),
            NavFocus::Projected => self.start_navigation(now),
            // Forced, and the head unit has either said no or not answered: the sequence
            // goes out anyway, which is the whole point of the switch.
            NavFocus::Requested | NavFocus::Native if forced => self.start_navigation(now),
            NavFocus::Requested | NavFocus::Native => {}
        }
    }

    /// `NavigationFocusRequest(NAV_FOCUS_PROJECTED)` on the **control channel**.
    ///
    /// The third focus type in this session after video and audio, and like audio focus it
    /// lives on the control channel rather than the channel it governs. Asked for once:
    /// the request stands until the head unit answers, and a `NAV_FOCUS_NATIVE` does not
    /// retire it, so a head unit that hands the cluster over later still finds a source
    /// waiting for it.
    fn request_navigation_focus(&mut self, now: Instant) {
        let wait = self.config.projection.navigation_focus_wait;
        if let Some(nav) = self.projection_mut().navigation.as_mut() {
            nav.focus = NavFocus::Requested;
            nav.deadline = Some(now + wait);
            nav.handed_back = false;
        }
        // Asked for again after a hand-back (P7.4): held, or about to be, so the
        // teardown owes the head unit a hand-back again.
        let report = self.projection_report();
        report.navigation_focus_requests += 1;
        report.navigation_focus_released = false;
        let request = NavigationFocusRequest {
            r#type: Some(NavigationFocusType::NavFocusProjected as i32),
        };
        let _ = self.send(
            encode_message(ids::NAVIGATION_FOCUS_REQUEST, &request),
            true,
            now,
        );
    }

    /// `NAVIGATION_FOCUS_RESPONSE` (0x000e) on the control channel.
    ///
    /// `NavigationFocusType` has two values and neither is a refusal in as many words:
    /// `NAV_FOCUS_PROJECTED` is the cluster handed over, and `NAV_FOCUS_NATIVE` is the
    /// head unit keeping its own navigation on it. A response that cannot be read is
    /// counted and treated as native — the conservative reading, since the alternative is
    /// putting maneuvers on a cluster that may not have asked for them.
    pub(crate) fn navigation_focus_response(&mut self, body: &[u8], now: Instant) {
        if self
            .projection
            .as_ref()
            .and_then(|p| p.navigation.as_ref())
            .is_none()
        {
            return;
        }
        // After the hand-back, the echo of our own `NAV_FOCUS_NATIVE` is not a denial:
        // the pinned Prodigy receiver answers a focus request by echoing its payload, so
        // reading it as one would end every lab run with a refusal that never happened.
        if self
            .projection
            .as_ref()
            .and_then(|p| p.navigation.as_ref())
            .is_some_and(|n| n.stopped)
        {
            return;
        }
        // Handed back mid-session and not asking (P7.4): whatever arrives is the head
        // unit's answer to the hand-back — Prodigy's echo of `NAV_FOCUS_NATIVE`, or
        // whatever a vehicle says — and not a refusal of a request nobody made. Kept, not
        // obeyed: a `PROJECTED` here does not start anything, because there is no route.
        if self
            .projection
            .as_ref()
            .and_then(|p| p.navigation.as_ref())
            .is_some_and(|n| n.handed_back && n.focus == NavFocus::Idle)
        {
            let answer = decode::<NavigationFocusResponse>(body)
                .ok()
                .and_then(|r| r.r#type);
            let report = self.projection_report();
            report.navigation_focus_handback_answers += 1;
            report.navigation_focus_handback_type = answer;
            return;
        }
        let at = self.elapsed(now);
        let response = decode::<NavigationFocusResponse>(body);
        let focus_type = response.as_ref().ok().and_then(|r| r.r#type);
        let report = self.projection_report();
        report.navigation_focus_responses += 1;
        report.navigation_focus_at = Some(at);
        report.navigation_focus_type = focus_type;
        if response.is_err() {
            report.navigation_focus_invalid += 1;
        }
        let projected = focus_type == Some(NavigationFocusType::NavFocusProjected as i32);
        if !projected {
            self.projection_report().navigation_focus_denials += 1;
        }
        if let Some(nav) = self.projection_mut().navigation.as_mut() {
            nav.focus = match projected {
                true => NavFocus::Projected,
                false => NavFocus::Native,
            };
            nav.deadline = None;
        }
        self.navigation_progress(now)
    }

    /// `NavigationState(ACTIVE)`, and the script's clock starts here.
    ///
    /// Zero is this moment rather than the start of the session: the cluster is being told
    /// about a route that begins when it can see it, and a sequence that had already
    /// driven half its distance before the first message would be a worse demonstration
    /// and a worse test.
    fn start_navigation(&mut self, now: Instant) {
        let already = self
            .projection
            .as_ref()
            .and_then(|p| p.navigation.as_ref())
            .is_some_and(|n| n.active);
        if already {
            return;
        }
        let forced = self.config.projection.navigation_force
            && !self
                .projection
                .as_ref()
                .and_then(|p| p.navigation.as_ref())
                .is_some_and(|n| n.cluster_started && n.focus == NavFocus::Projected);
        if let Some(nav) = self.projection_mut().navigation.as_mut() {
            nav.active = true;
            nav.started = Some(now);
            nav.deadline = None;
        }
        if forced {
            self.projection_report().navigation_forced = true;
        }
        // A script is a route from this instant, so it is active at once. A live source
        // is active only while the app has a route to guide along: focus held with no
        // destination set is a cluster this source may use and has nothing to put on,
        // and telling it `ACTIVE` would be a claim with no maneuver behind it.
        if self.config.projection.navigation_source == Source::Script {
            self.send_navigation_state(NavigationStateType::NavStateActive, now);
        }
        self.pump_navigation(now)
    }

    /// One `NavigationState` (0x8003), counted by state name.
    fn send_navigation_state(&mut self, state: NavigationStateType, now: Instant) {
        let Some(channel) = self
            .projection
            .as_ref()
            .and_then(|p| p.navigation.as_ref())
            .filter(|n| n.open)
            .map(|n| n.channel)
        else {
            return;
        };
        let message = NavigationState {
            state: Some(state as i32),
        };
        if self.send_on(
            channel,
            false,
            false,
            encode_message(ids::navigation::STATE, &message),
            true,
            now,
        ) {
            if let Some(nav) = self.projection_mut().navigation.as_mut() {
                nav.wire_state = Some(state);
            }
            let name = state.as_str_name();
            let states = &mut self.projection_report().navigation_states_sent;
            match states.iter_mut().find(|(n, _)| *n == name) {
                Some((_, count)) => *count += 1,
                None => states.push((name, 1)),
            }
        }
    }

    /// Plays the scripted sequence, as the interval allows (P6.2).
    ///
    /// One update is at most one `NavigationNotification` and one
    /// `NavigationNextTurnDistanceEvent`: two messages about the same instant, which is
    /// why the interval is enforced across the pair rather than per message. Whether a
    /// head unit that advertises `minimum_interval_ms` counts messages or updates is not
    /// recorded anywhere and no head unit has answered it; this is the reading, and the
    /// report carries both counts so a vehicle can contradict it.
    pub(crate) fn pump_navigation(&mut self, now: Instant) {
        if self.report.outcome.is_some() {
            return;
        }
        let refresh = self.config.projection.navigation_refresh;
        let units = self.config.projection.navigation_units;
        if self.route_focus(now) {
            return;
        }
        let Some(nav) = self.projection.as_ref().and_then(|p| p.navigation.as_ref()) else {
            return;
        };
        if !nav.sending() {
            return;
        }
        let (channel, interval) = (nav.channel, nav.interval);
        let elapsed = now.saturating_duration_since(nav.started.unwrap_or(now));
        // The cluster said it was gone. Nothing is sent to a display that is not there,
        // and the script's clock keeps running, so a cluster that comes back finds the
        // route where it would have been.
        if !nav.cluster_started && !self.config.projection.navigation_force {
            return;
        }
        let guidance = match nav.source {
            Source::Script => nav.script.at(elapsed),
            // Not "arrived" when there is nothing to guide: a live route that ends leaves
            // the channel waiting for the next one, with the cluster still held.
            Source::Route => match self.route_guidance(now) {
                Some(guidance) => Some(guidance),
                None => return,
            },
        };
        let Some(guidance) = guidance else {
            // Arrived. The whole lifecycle — active, maneuvers, arrival, inactive, focus
            // handed back — then fits inside one session's evidence.
            self.projection_report().navigation_completed = true;
            if let Some(nav) = self.projection_mut().navigation.as_mut() {
                nav.finished = true;
            }
            return self.stop_navigation("route completed", now);
        };
        let Some(nav) = self.projection.as_ref().and_then(|p| p.navigation.as_ref()) else {
            return;
        };
        if nav.next_send.is_some_and(|d| now < d) {
            // Counted once per distinct maneuver held back rather than once per visit,
            // for the reason P5.1 recorded: this runs on every timer tick, and counting
            // each visit reported a flood that never happened. Recording the leg here is
            // what makes that "once", and clearing `sent_leg_at` is what stops it from
            // reading as sent — the pair is how the update still goes out when the
            // interval expires.
            if nav.sent_leg != Some(guidance.leg) {
                if let Some(nav) = self.projection_mut().navigation.as_mut() {
                    nav.sent_leg = Some(guidance.leg);
                    nav.sent_leg_at = None;
                }
                self.projection_report().navigation_suppressed += 1;
            }
            return;
        }
        // The maneuver goes out when it changes, and is restated periodically so a cluster
        // that was not listening when it last changed still learns it.
        let restate = nav
            .sent_leg_at
            .is_none_or(|last| now.saturating_duration_since(last) >= refresh);
        let changed = nav.sent_leg != Some(guidance.leg) || nav.sent_leg_at.is_none();
        if changed || restate {
            if changed {
                self.projection_report().navigation_maneuvers += 1;
            }
            self.send_navigation_notification(channel, &guidance, now);
            if let Some(nav) = self.projection_mut().navigation.as_mut() {
                nav.sent_leg = Some(guidance.leg);
                nav.sent_leg_at = Some(now);
            }
        }
        self.send_navigation_distance(channel, &guidance, units, now);
        if let Some(nav) = self.projection_mut().navigation.as_mut() {
            nav.next_send = Some(now + interval);
        }
        self.projection_report().navigation_last_step = Some(guidance.summary(units));
    }

    /// A live route's side of [`Session::pump_navigation`] (P6.5): brings the wire's
    /// `NavigationState` into line with what the app last published, and returns the
    /// maneuver to send when there is one.
    ///
    /// Each state goes out once, on the change. `REROUTING` is only ever sent from
    /// `ACTIVE` — a vehicle that was never on a route has nothing to be re-routed from —
    /// and both idle and arrived withdraw the state to `INACTIVE` **without** handing the
    /// cluster back: the next destination should not have to ask for focus again, and a
    /// head unit that took focus back would say so with `NAV_FOCUS_NATIVE`.
    fn route_guidance(&mut self, now: Instant) -> Option<Guidance> {
        use NavigationStateType as S;
        let wire = self
            .projection
            .as_ref()
            .and_then(|p| p.navigation.as_ref())
            .and_then(|n| n.wire_state);
        let guiding = matches!(wire, Some(S::NavStateActive | S::NavStateRerouting));
        let state = self.guidance.snapshot();
        let guidance = match state {
            Some(RouteState::Guiding(guidance)) => {
                if wire != Some(S::NavStateActive) {
                    self.send_navigation_state(S::NavStateActive, now);
                    // A cluster coming back from `REROUTING` or `INACTIVE` is told the
                    // maneuver at the next update rather than at the next restatement —
                    // but not sooner than the interval allows. The first version cleared
                    // the interval here too, and the P6.5 lab receiver measured two
                    // updates 490 ms apart against its 1500 ms floor after a re-route.
                    if let Some(nav) = self.projection_mut().navigation.as_mut() {
                        nav.sent_leg_at = None;
                    }
                }
                return Some(guidance);
            }
            Some(RouteState::Rerouting) if wire == Some(S::NavStateActive) => {
                self.send_navigation_state(S::NavStateRerouting, now);
                None
            }
            Some(RouteState::Arrived) if guiding => {
                self.projection_report().navigation_arrivals += 1;
                self.projection_report().navigation_completed = true;
                self.send_navigation_state(S::NavStateInactive, now);
                None
            }
            // Ended is handed back by `route_focus` before this runs; it is here so an
            // ended route that somehow reaches it still withdraws the state.
            Some(RouteState::Idle | RouteState::Ended) | None if guiding => {
                self.send_navigation_state(S::NavStateInactive, now);
                None
            }
            _ => None,
        };
        // Nothing to send, so an interval that has already run out is forgotten rather
        // than left as a deadline in the past for the poll loop to spin on — the defect
        // P5.1 found on the now-playing channel. One still in the future is kept: it is
        // what stops the next maneuver going out sooner than the head unit allows.
        if let Some(nav) = self.projection_mut().navigation.as_mut() {
            nav.next_send = nav.next_send.filter(|d| *d > now);
        }
        guidance
    }

    /// `NavigationNotification` (0x8006): the modern semantic maneuver.
    ///
    /// No lanes and no images. `NavigationLane` is in the schema and P6 plan §0 puts lane
    /// guidance out of scope — nothing here could fill it from a real route — and the only
    /// path that ever carried turn images is the deprecated 0x8004 (P6 plan §2).
    fn send_navigation_notification(&mut self, channel: u8, guidance: &Guidance, now: Instant) {
        let step = NavigationStep {
            maneuver: Some(NavigationManeuver {
                r#type: Some(guidance.maneuver),
                roundabout_exit_number: guidance.roundabout_exit,
                // Absent rather than zero: proto2 tells the two apart, and this source
                // does not know the angle of a roundabout it invented.
                roundabout_turn_angle: None,
            }),
            instruction: Some(NavigationText {
                text: Some(guidance.instruction.clone()),
            }),
            lanes: Vec::new(),
            road_info: Some(NavigationRoadInfo {
                road_names: guidance.onto.clone(),
            }),
        };
        let message = NavigationNotification {
            steps: vec![step],
            // A destination with no address says nothing a cluster can show, and this
            // source has no address to give: the script's route ends at a point, not at a
            // place. P6.4 is where a destination starts existing.
            destinations: Vec::new(),
        };
        if self.send_on(
            channel,
            false,
            false,
            encode_message(ids::navigation::NOTIFICATION, &message),
            true,
            now,
        ) {
            self.projection_report().navigation_notifications_sent += 1;
        }
    }

    /// `NavigationNextTurnDistanceEvent` (0x8007): how far and how long to the maneuver,
    /// and to the destination past it.
    ///
    /// `estimated_time_of_arrival` is left absent. It is a `string` of unrecorded format
    /// and this source does not know the vehicle's local clock; `time_to_arrival_seconds`
    /// beside it says the same thing without inventing one.
    fn send_navigation_distance(
        &mut self,
        channel: u8,
        guidance: &Guidance,
        units: Units,
        now: Instant,
    ) {
        let distance = |metres: f64| {
            let rendered = units.render(metres);
            NavigationTurnDistance {
                distance_value: Some(rendered.value),
                display_text: Some(rendered.text),
                distance_unit: Some(rendered.unit),
            }
        };
        let message = NavigationNextTurnDistanceEvent {
            step_distance: Some(NavigationStepDistance {
                distance: Some(distance(guidance.step_metres)),
                time_to_step_seconds: Some(guidance.step_seconds),
            }),
            destination_distances: vec![DestinationDistance {
                distance: Some(distance(guidance.destination_metres)),
                estimated_time_of_arrival: None,
                time_to_arrival_seconds: Some(guidance.destination_seconds),
            }],
            // A live route has no road names to give (BRouter's data carries none), and
            // an empty string is a claim that the road has no name.
            current_road: (!guidance.current_road.is_empty()).then(|| NavigationText {
                text: Some(guidance.current_road.clone()),
            }),
        };
        if self.send_on(
            channel,
            false,
            false,
            encode_message(ids::navigation::NEXT_TURN_DISTANCE, &message),
            true,
            now,
        ) {
            self.projection_report().navigation_distances_sent += 1;
        }
    }

    /// A live route's focus between routes (P7.4): hands the cluster back when the app
    /// says the route ended, and asks for it again when the next route arrives. Returns
    /// whether it acted, in which case nothing else is due on this visit.
    ///
    /// Runs ahead of [`NavigationChannel::sending`] because both halves happen while the
    /// sequence is not sending: an ended route may find focus asked for and never granted,
    /// and after a hand-back the sequence is stopped until focus comes back.
    ///
    /// **Edge-triggered on the focus state, not on the published one.** The link keeps
    /// the last thing published, so `Ended` is seen on every visit until the next route;
    /// the hand-back puts focus back to idle, and idle with `Ended` is nothing to do.
    /// A route set so soon after the end that `Ended` was overwritten before this saw it
    /// keeps the cluster, which is right: the driver has not finished with it.
    fn route_focus(&mut self, now: Instant) -> bool {
        if self.config.projection.navigation_source != Source::Route {
            return false;
        }
        let Some(nav) = self.projection.as_ref().and_then(|p| p.navigation.as_ref()) else {
            return false;
        };
        if !nav.open || nav.stopped {
            return false;
        }
        let (focus, handed_back) = (nav.focus, nav.handed_back);
        match self.guidance.snapshot() {
            Some(RouteState::Ended) if focus != NavFocus::Idle => {
                self.hand_back_navigation(now);
                true
            }
            Some(RouteState::Guiding(_)) if handed_back && focus == NavFocus::Idle => {
                // Exactly the session-start path: ask, wait for the grant (or, forced,
                // for the wait), and only then `ACTIVE` and the first maneuver.
                self.navigation_progress(now);
                true
            }
            _ => false,
        }
    }

    /// `INACTIVE` if a route was on the cluster, then `NavigationFocusRequest(
    /// NAV_FOCUS_NATIVE)`: the same hand-back [`Session::stop_navigation`] sends at the end
    /// of a session, sent when the driver ends a route instead (P7.4).
    ///
    /// Unlike the teardown, the channel is not stopped. Focus goes back to idle and the
    /// sequence to inactive, so the next route runs the start of the handshake again —
    /// request, grant, `ACTIVE` — rather than finding a claim this source gave up. The
    /// interval's deadline is kept, so a quick new route still waits out the head unit's
    /// floor.
    ///
    /// Only for focus actually asked for, for the teardown's reason: a head unit told to
    /// take back something it never gave is being told about a state this source never
    /// touched. That `NAV_FOCUS_NATIVE` means "the head unit's own navigation" is a
    /// reading of the enum (`ids::NAVIGATION_FOCUS_REQUEST`); what SYNC 4 does on hearing
    /// it mid-session has not been seen.
    fn hand_back_navigation(&mut self, now: Instant) {
        let Some(nav) = self.projection.as_ref().and_then(|p| p.navigation.as_ref()) else {
            return;
        };
        let was_active = matches!(
            nav.wire_state,
            Some(NavigationStateType::NavStateActive | NavigationStateType::NavStateRerouting)
        );
        if was_active {
            self.send_navigation_state(NavigationStateType::NavStateInactive, now);
        }
        if let Some(nav) = self.projection_mut().navigation.as_mut() {
            nav.focus = NavFocus::Idle;
            nav.handed_back = true;
            nav.active = false;
            nav.started = None;
            nav.deadline = None;
            nav.next_send = nav.next_send.filter(|d| *d > now);
            // The next route's first maneuver is news, whatever its identity.
            nav.sent_leg = None;
            nav.sent_leg_at = None;
        }
        let release = NavigationFocusRequest {
            r#type: Some(NavigationFocusType::NavFocusNative as i32),
        };
        if self.send(
            encode_message(ids::NAVIGATION_FOCUS_REQUEST, &release),
            true,
            now,
        ) {
            let report = self.projection_report();
            report.navigation_focus_handbacks += 1;
            report.navigation_focus_released = true;
        }
    }

    /// Ends the sequence: `NavigationState(INACTIVE)`, then the cluster handed back.
    ///
    /// Always sent once the sequence was active, whatever the reason for stopping, for the
    /// same reason audio's `RELEASE` is: a focus state left standing is the one thing that
    /// outlives the session. There is no `RELEASE` value on this exchange —
    /// `NavigationFocusType` has only `NAV_FOCUS_NATIVE` and `NAV_FOCUS_PROJECTED` — so
    /// handing back is a request for the head unit's own navigation, which is a reading of
    /// the enum and not an observation (`ids::NAVIGATION_FOCUS_REQUEST`).
    pub(crate) fn stop_navigation(&mut self, _reason: &str, now: Instant) {
        let Some(nav) = self.projection.as_ref().and_then(|p| p.navigation.as_ref()) else {
            return;
        };
        if nav.stopped {
            return;
        }
        let was_active = matches!(
            nav.wire_state,
            Some(NavigationStateType::NavStateActive | NavigationStateType::NavStateRerouting)
        );
        let asked = nav.focus != NavFocus::Idle;
        if let Some(nav) = self.projection_mut().navigation.as_mut() {
            nav.stopped = true;
            nav.active = false;
            nav.deadline = None;
            nav.next_send = None;
        }
        if was_active {
            self.send_navigation_state(NavigationStateType::NavStateInactive, now);
        }
        // Only for focus actually asked for. After no request there is nothing to hand
        // back, and sending one anyway would tell a head unit to change a state this
        // source never touched.
        if asked && !self.projection_report().navigation_focus_released {
            let release = NavigationFocusRequest {
                r#type: Some(NavigationFocusType::NavFocusNative as i32),
            };
            if self.send(
                encode_message(ids::NAVIGATION_FOCUS_REQUEST, &release),
                true,
                now,
            ) {
                self.projection_report().navigation_focus_released = true;
            }
        }
    }

    fn open_sensor_channel(&mut self, now: Instant) {
        let sensor = self.projection_mut().sensor.as_ref().map(|s| s.channel);
        match sensor {
            Some(channel) => {
                self.set_step(Step::OpenSensor);
                self.open_channel(channel, "sensor", now);
            }
            None => self.open_next_extra(now),
        }
    }

    fn request_sensor(&mut self, index: usize, now: Instant) {
        let p = self.projection_mut();
        let (Some(sensor_type), Some(channel)) = (
            p.sensor_types.get(index).copied(),
            p.sensor.as_ref().map(|s| s.channel),
        ) else {
            return self.open_next_extra(now);
        };
        self.set_step(Step::SensorStart(index));
        self.stage_deadline = Some(now + self.config.projection.setup_wait);
        let request = SensorRequest {
            sensor_type,
            refresh_interval: 0,
        };
        let _ = self.send_on(
            channel,
            false,
            false,
            encode_message(ids::sensor::REQUEST, &request),
            true,
            now,
        );
    }

    fn sensor_start_response(&mut self, body: &[u8], now: Instant) {
        let Step::SensorStart(index) = self.projection_mut().step else {
            return self.projection_report().sensor_invalid += 1;
        };
        let sensor_type = self.projection_mut().sensor_types[index];
        let status = decode::<SensorStartResponseMessage>(body)
            .ok()
            .map(|r| r.status);
        let report = self.projection_report();
        if status.is_none() {
            report.sensor_invalid += 1;
        }
        report.sensor_requests.push((sensor_type, status));
        self.stage_deadline = None;
        self.request_sensor(index + 1, now);
    }

    fn sensor_error(&mut self, body: &[u8], now: Instant) {
        self.projection_report().sensor_errors += 1;
        if let (Step::SensorStart(index), Ok(error)) =
            (self.projection_mut().step, decode::<SensorError>(body))
        {
            let sensor_type = self.projection_mut().sensor_types[index];
            if error.sensor_type == sensor_type {
                self.projection_report()
                    .sensor_requests
                    .push((sensor_type, None));
                self.stage_deadline = None;
                self.request_sensor(index + 1, now);
            }
        }
    }

    /// Harvest one indication (P5.2): every payload field the head unit populated is
    /// counted and rendered, not just the two restriction types P4 read.
    fn sensor_event(&mut self, body: &[u8]) {
        let event = match decode::<SensorEventIndication>(body) {
            Ok(event) => event,
            Err(_) => return self.projection_report().sensor_invalid += 1,
        };
        let location = self.config.projection.sensor_location;
        let values = harvest(&event, location);
        let report = self.projection_report();
        report.sensor_events += 1;
        if values.is_empty() {
            report.sensor_empty += 1;
        }
        for (sensor_type, rendered) in values {
            match report
                .sensor_values
                .iter_mut()
                .find(|h| h.sensor_type == sensor_type)
            {
                Some(entry) => {
                    entry.count += 1;
                    entry.last = Some(rendered);
                }
                None => {
                    // A type that was never requested: the head unit volunteered it.
                    report.sensor_unrequested += 1;
                    report.sensor_values.push(SensorHarvest {
                        sensor_type,
                        requested: false,
                        count: 1,
                        last: Some(rendered),
                    });
                }
            }
        }
        // Position and motion for the owned map (P5.5). Taken before the restriction
        // early-return below, because a message carrying only a GPS fix has neither a
        // driving status nor a night mode and would otherwise never reach the app.
        //
        // Latitude and longitude are delivered whatever `sensor_location` says: that
        // option governs what a *report* may contain (spec §10), and the map cannot be
        // drawn from a redacted fix. The value stays app-private either way; what the
        // option still controls is whether it is ever written down.
        let fix = event.gps_location.last();
        let incoming = VehicleState {
            latitude: fix.map(|v| v.latitude),
            longitude: fix.map(|v| v.longitude),
            accuracy: fix.and_then(|v| v.accuracy),
            altitude: fix.and_then(|v| v.altitude),
            gps_speed: fix.and_then(|v| v.speed),
            bearing: fix.and_then(|v| v.bearing),
            car_speed: event.speed.last().map(|v| v.speed),
            gear: event.gear.last().map(|v| v.gear),
            parking_brake: event.parking_brake.last().map(|v| v.parking_brake),
            satellite_count: event.gps_satellite.last().map(|v| v.satellite_count),
            satellites_used: event.gps_satellite.last().and_then(|v| v.satellites_used),
        };
        if let Some(projection) = self.projection.as_mut() {
            if projection.vehicle.merge(&incoming) {
                let vehicle = projection.vehicle;
                self.projection_report().vehicle_events += 1;
                self.push_app_event(AppEvent::Vehicle(vehicle));
            }
        }

        let driving = event.driving_status.last().map(|d| d.status);
        let night = event.night_mode.last().and_then(|n| n.is_night);
        if driving.is_none() && night.is_none() {
            return;
        }
        let report = self.projection_report();
        if driving.is_some() {
            report.driving_status = driving;
        }
        if night.is_some() {
            report.night = night;
        }
        let (driving_status, night) = (report.driving_status, report.night);
        self.push_app_event(AppEvent::Restriction {
            driving_status,
            night,
        });
    }

    /// A cluster d-pad action on the head unit's now-playing display (0x8002).
    ///
    /// The channel is otherwise phone → head unit, and this message is never answered.
    /// It is decoded only so it is counted as traffic with a known meaning rather than
    /// landing among the unhandled messages; nothing acts on it, because this source
    /// draws no now-playing UI on the head unit for a d-pad to move around in.
    fn media_event(&mut self, body: &[u8]) {
        let event = decode::<MediaPlaybackStatusEvent>(body);
        let report = self.projection_report();
        report.media_events += 1;
        if event.is_err() {
            report.media_events_invalid += 1;
        }
    }

    /// Publishes what the app says is playing, as the rate limit allows (P5.1).
    ///
    /// Called from every timer and from the channel open, so a track change reaches the
    /// head unit as soon as the interval permits. Metadata goes first: a head unit that
    /// shows a state and a position for a track it has not been told about yet would
    /// briefly attribute the position to the previous one.
    pub(crate) fn pump_now_playing(&mut self, now: Instant) {
        if self.report.outcome.is_some() {
            return;
        }
        let interval = self.config.projection.media_interval;
        let Some(p) = &self.projection else { return };
        let Some(media) = p.media.as_ref().filter(|m| m.open) else {
            return;
        };
        let channel = media.channel;
        // Nothing published at all is different from an empty publication: the app has
        // not spoken yet, so there is nothing to say.
        let Some(playing) = self.now_playing.snapshot() else {
            return;
        };
        let track_changed = media
            .sent_track
            .as_ref()
            .map(|(t, a, b)| (t.as_str(), a.as_str(), b.as_str()))
            != Some(playing.track());
        let status_changed = media
            .sent_status
            .as_ref()
            .map(|(s, p, a)| (*s, *p, a.as_str()))
            != Some(playing.status());
        // Restated periodically rather than only on a change, so a head unit whose
        // now-playing display starts listening after the channel opened is still told.
        // Ford SYNC 4.0 accepted the open at 1.3 s and had its one and only metadata
        // message by 1.4 s (report 25150d54); a client that never repeats itself has no
        // answer to a receiver that was not ready at that instant.
        let refresh = media.last_send.is_none_or(|last| {
            now.saturating_duration_since(last) >= self.config.projection.media_refresh
        });
        // A metadata message with three empty strings tells the head unit nothing it can
        // show, so an app with no access to the track only ever sends status.
        let send_track = (track_changed || refresh) && playing.has_track();
        let send_status = status_changed || refresh;
        if !send_track && !send_status {
            return;
        }
        if media.next_send.is_some_and(|d| now < d) {
            // Counted once per distinct update held back, not once per visit: this runs
            // on every timer tick, so counting each one reported 401 suppressions against
            // 31 publications (report 25150d54) and read as a flood that never happened.
            let pending = Some((playing.status_owned(), playing.track_owned()));
            if media.deferred != pending {
                self.projection_mut()
                    .media
                    .as_mut()
                    .expect("media channel present")
                    .deferred = pending;
                self.projection_report().media_suppressed += 1;
            }
            return;
        }
        if send_track {
            self.send_media_metadata(channel, &playing, now);
        }
        if send_status {
            self.send_media_status(channel, &playing, now);
        }
        let Some(media) = self.projection_mut().media.as_mut() else {
            return;
        };
        media.next_send = Some(now + interval);
        media.last_send = Some(now);
        media.deferred = None;
        if send_track {
            media.sent_track = Some(playing.track_owned());
        }
        if send_status {
            media.sent_status = Some(playing.status_owned());
        }
    }

    /// `MediaPlaybackMetadata` (0x8003), without album art.
    ///
    /// `album_art` is unbounded `bytes` in the schema and the transport's fragment
    /// payload is 16 KiB, so carrying it would be the only message this source sends that
    /// could fragment unpredictably behind video. Ford's size limit for it is unknown
    /// (P5 §8), so this slice sends none and records the omission.
    fn send_media_metadata(&mut self, channel: u8, playing: &NowPlaying, now: Instant) {
        let metadata = MediaPlaybackMetadata {
            title: Some(playing.title.clone()),
            artist: Some(playing.artist.clone()),
            album: Some(playing.album.clone()),
            album_art: None,
            unknown_5: None,
            unknown_6: None,
            unknown_7: None,
        };
        if self.send_on(
            channel,
            false,
            false,
            encode_message(ids::media::PLAYBACK_METADATA, &metadata),
            true,
            now,
        ) {
            let report = self.projection_report();
            report.media_metadata_sent += 1;
            report.media_track_sent = true;
        }
    }

    /// `MediaPlaybackStatus` (0x8001). Shuffle and repeat are left absent rather than
    /// sent as false: this source does not know them, and proto2 distinguishes the two.
    fn send_media_status(&mut self, channel: u8, playing: &NowPlaying, now: Instant) {
        let status = MediaPlaybackStatus {
            playback_state: Some(playing.state.wire()),
            source_app: Some(playing.source_app.clone()),
            position_seconds: Some(playing.position_seconds),
            shuffle: None,
            repeat: None,
            repeat_one: None,
        };
        if self.send_on(
            channel,
            false,
            false,
            encode_message(ids::media::PLAYBACK_STATUS, &status),
            true,
            now,
        ) {
            let shown = format!(
                "{}:{}:{}",
                playing.state.name(),
                playing.position_seconds,
                if playing.source_app.is_empty() {
                    "-"
                } else {
                    &playing.source_app
                }
            );
            let report = self.projection_report();
            report.media_status_sent += 1;
            report.media_last_status = Some(shown);
        }
    }

    /// Opens the next advertised channel this source does not use, or moves to focus.
    fn open_next_extra(&mut self, now: Instant) {
        let p = self.projection_mut();
        let index = match p.step {
            Step::OpenExtra(i) => i + 1,
            _ => 0,
        };
        match p.extra.get(index).copied() {
            Some(channel) => {
                self.set_step(Step::OpenExtra(index));
                self.open_channel(channel, "extra", now);
            }
            None => self.request_focus(now),
        }
    }

    fn request_focus(&mut self, now: Instant) {
        self.set_step(Step::AwaitFocus);
        if !self.config.projection.request_focus {
            // Android Auto waits for the head unit's unsolicited grant instead.
            if self.projection_mut().video().focused {
                return self.start_video(now);
            }
            self.stage_deadline = Some(now + self.config.projection.focus_wait);
            return;
        }
        if self.projection_mut().video().focused {
            return self.start_video(now);
        }
        self.stage_deadline = Some(now + self.config.projection.focus_wait);
        self.send_focus_request(now);
    }

    /// Put one `VideoFocusRequest(PROJECTED, USER_SELECTION)` on the video channel.
    ///
    /// Only the message: the caller owns the step and the stage deadline, since the setup
    /// stage is waiting on the answer to decide whether projection starts at all, while a
    /// mid-stream re-request is not a stage and must not move one.
    fn send_focus_request(&mut self, now: Instant) {
        self.projection_report().focus_requests += 1;
        let request = VideoFocusRequest {
            focus_mode: Some(video_focus_mode::Enum::Projected as i32),
            focus_reason: Some(video_focus_reason::Enum::UserSelection as i32),
        };
        let video = self.projection_mut().video.channel;
        let _ = self.send_on(
            video,
            false,
            false,
            encode_message(ids::av::VIDEO_FOCUS_REQUEST, &request),
            true,
            now,
        );
    }

    fn focus_indication(&mut self, body: &[u8], now: Instant) {
        let indication = match decode::<VideoFocusIndication>(body) {
            Ok(indication) => indication,
            Err(e) => {
                let detail = format!("invalid video focus indication: {e}");
                return self.finish(Category::UnexpectedMessage, detail, now);
            }
        };
        // Absent or NONE counts as not projected: the Prodigy receiver signals focus loss
        // with NONE.
        let mode = indication.focus_mode.unwrap_or(0);
        let projected = focus_projected(mode);
        let at = self.elapsed(now);
        let report = self.projection_report();
        report.focus_indications += 1;
        report.focus_unrequested += u64::from(indication.unrequested == Some(true));
        report.focus_mode = Some(mode);
        if projected && report.focus_projected_at.is_none() {
            report.focus_projected_at = Some(at);
        }
        let p = self.projection_mut();
        let was = p.video().focused;
        p.video_mut().focused = projected;
        // What this indication actually did, recorded with it. An indication the state
        // machine had no use for is the interesting case, so it is named rather than
        // dropped: "ignored" next to a step is the evidence that the head unit spoke and
        // this source did nothing.
        let action = match (p.step, was, projected) {
            (Step::AwaitFocus, _, true) => "started",
            (Step::Streaming, true, false) => "paused",
            (Step::Streaming, false, true) => "resumed",
            (Step::Streaming, _, _) => "no-change",
            (step, _, _) => step.name(),
        };
        let unrequested = indication.unrequested;
        let report = self.projection_report();
        if report.focus_sequence.len() < VIDEO_FOCUS_SEQUENCE_LIMIT {
            report.focus_sequence.push((at, mode, unrequested, action));
        }
        let p = self.projection_mut();
        match p.step {
            Step::AwaitFocus if projected => self.start_video(now),
            Step::Streaming if was && !projected => {
                self.stop_video_stream(true, now);
                self.projection_report().focus_losses += 1;
                self.push_app_event(AppEvent::Focus {
                    projected: false,
                    mode,
                });
            }
            Step::Streaming if !was && projected => {
                self.projection_report().focus_regains += 1;
                self.push_app_event(AppEvent::Focus {
                    projected: true,
                    mode,
                });
                self.restart_video_stream(true, now);
            }
            _ => {}
        }
    }

    /// The head unit took the screen: stop the stream, do not merely stop feeding it.
    ///
    /// Spec §8.2. The stop indication is what tells the head unit the projection is no
    /// longer foreground; Ford SYNC 4.0 will not let its own "Android Auto" control
    /// re-enter a session it still believes is running. The session id is incremented here
    /// so the restart carries a fresh one, as Android Auto's `Ljdr;->x()V` does.
    ///
    /// The channel, its acknowledgement window and the per-connection flow control are all
    /// left alone: this stops a stream, not a channel.
    ///
    /// `focus` is false for a congestion restart ([`Self::restart_congested_stream`]),
    /// which is counted apart from focus transitions.
    fn stop_video_stream(&mut self, focus: bool, now: Instant) {
        let p = self.projection_mut();
        let video = p.video_mut();
        if !video.started {
            return;
        }
        video.started = false;
        video.stall_since = None;
        video.first_unit_deadline = None;
        // Units still outstanding belong to the session being stopped; acknowledgements for
        // them are matched against the id they were sent under, which is why the id moves
        // only after the counter is cleared.
        video.outstanding = 0;
        video.prev_session_id = Some(video.session_id);
        video.session_id = video.session_id.wrapping_add(1);
        let channel = video.channel;
        self.media.pause();
        let sent = self.send_on(
            channel,
            false,
            false,
            encode_message(ids::av::STOP_INDICATION, &AvChannelStopIndication {}),
            true,
            now,
        );
        let report = self.projection_report();
        if focus {
            report.focus_stop_indications += u64::from(sent);
        }
    }

    /// The head unit handed the screen back: start the stream again under the new session
    /// id, then configuration and a fresh IDR (spec §8.2).
    ///
    /// Deliberately not [`Session::start_video`], which owns the first start: that one moves
    /// the stage out of Setup, completes it, and asks for audio focus. None of those happen
    /// again on a regain — only the AV stream restarts.
    fn restart_video_stream(&mut self, focus: bool, now: Instant) {
        let first_unit_wait = self.config.projection.first_unit_wait;
        let at = self.elapsed(now);
        let p = self.projection_mut();
        let video = p.video_mut();
        if video.started {
            return;
        }
        video.started = true;
        video.first_unit_deadline = Some(now + first_unit_wait);
        let session_id = video.session_id;
        let start = AvChannelStartIndication {
            session: session_id,
            config: p.video.config_index as u32,
            session_type: None,
            media_config: None,
        };
        let event = VideoStart {
            session: session_id,
            video: p.video,
            touch: p.input.touch,
        };
        let channel = p.video.channel;
        let report = self.projection_report();
        report.start_session = Some(start.session);
        report.started_at = Some(at);
        if focus {
            report.focus_restarts += 1;
        }
        report.keyframe_requests += 1;
        if !self.send_on(
            channel,
            false,
            false,
            encode_message(ids::av::START_INDICATION, &start),
            true,
            now,
        ) {
            return;
        }
        self.media.resume();
        // The app rebuilds its encoder for the new session, which supplies the configuration
        // and the IDR the restarted stream needs.
        self.push_app_event(AppEvent::VideoStart(event));
        self.push_app_event(AppEvent::KeyframeRequest);
    }

    fn start_video(&mut self, now: Instant) {
        let first_unit_wait = self.config.projection.first_unit_wait;
        let limit = self.config.projection.limit;
        let at = self.elapsed(now);
        let p = self.projection_mut();
        p.step = Step::Streaming;
        p.limit_deadline = limit.map(|l| now + l);
        p.projecting_since = Some(now);
        let video = p.video_mut();
        video.started = true;
        video.first_unit_deadline = Some(now + first_unit_wait);
        let session_id = video.session_id;
        let start = AvChannelStartIndication {
            session: session_id,
            config: p.video.config_index as u32,
            session_type: None,
            media_config: None,
        };
        let event = VideoStart {
            session: session_id,
            video: p.video,
            touch: p.input.touch,
        };
        let channel = p.video.channel;
        self.set_step(Step::Streaming);
        let report = self.projection_report();
        report.start_session = Some(start.session);
        report.start_config = Some(start.config);
        report.started_at = Some(at);
        self.stage_deadline = None;
        if !self.send_on(
            channel,
            false,
            false,
            encode_message(ids::av::START_INDICATION, &start),
            true,
            now,
        ) {
            return;
        }
        self.report.stage = Stage::Projecting;
        self.complete(Stage::Setup);
        self.media.resume();
        self.push_app_event(AppEvent::VideoStart(event));
        // Audio focus is independent of video focus and is asked for on the control
        // channel, so it follows the video start rather than sharing its step.
        self.request_audio_focus(now);
    }

    /// A head unit moving the content rectangle inside the running stream (0x8009).
    ///
    /// No schema for this message is published; only its name is traced. Ford SYNC 4.0
    /// sends `{1: {1: {top, bottom, left, right}}}`, whose inner message is exactly the
    /// vendored `AdditionalVideoConfig` with its `display_insets` set, so only the outer
    /// one-field wrapper is parsed here and the payload goes through the schema as usual.
    ///
    /// Answered with UPDATE_UI_CONFIG_REPLY (0x800A), which the vendored id enum does not
    /// list; see [`ids::av::UPDATE_UI_CONFIG_REPLY`] for how it was recovered.
    ///
    /// The insets are taken literally rather than through the advertised-margin rule of
    /// spec §8.3: an update deliberately disagrees with the margins from discovery, which
    /// is the whole point of it. Ford's 1080p update carries 99/99/0/0 against an
    /// advertised `margin_width` of 586, giving the full-width 0,99+1920x882 rectangle it
    /// advertises at discovery once expanded (reports 07ba8ae0 and 7fcc0aec).
    fn ui_config_request(&mut self, body: &[u8], now: Instant) {
        self.projection_report().ui_config_updates += 1;
        let refuse = |s: &mut Self, detail: String| {
            let report = s.projection_report();
            report.ui_config_invalid += 1;
            report.ui_config_last = Some(detail);
        };
        let Some(inner) = first_field(body).map(<[u8]>::to_vec) else {
            return refuse(self, "no wrapped configuration".into());
        };
        let inner = inner.as_slice();
        let config = match decode::<AdditionalVideoConfig>(inner) {
            Ok(config) => config,
            Err(e) => return refuse(self, format!("invalid configuration: {e}")),
        };
        let Some(insets) = config.display_insets.as_ref() else {
            return refuse(self, "configuration carries no display insets".into());
        };
        let (t, b, l, r) = (
            insets.top.unwrap_or(0),
            insets.bottom.unwrap_or(0),
            insets.left.unwrap_or(0),
            insets.right.unwrap_or(0),
        );
        let shown = format!("insets=top={t},bottom={b},left={l},right={r}");
        let video = self.projection_mut().video;
        let (fw, fh) = (video.width, video.height);
        // The rectangle has to leave a picture inside the frame this stream is encoding.
        if u64::from(l) + u64::from(r) >= u64::from(fw)
            || u64::from(t) + u64::from(b) >= u64::from(fh)
        {
            return refuse(self, format!("{shown} leave no picture in {fw}x{fh}"));
        }
        let content = Rect {
            x: l,
            y: t,
            width: fw - l - r,
            height: fh - t - b,
        };
        let space = self.config.projection.touch_space;
        let p = self.projection_mut();
        p.video.content = content;
        p.video.insets = Some([t, b, l, r]);
        p.touch = p
            .input
            .touch
            .and_then(|advertised| TouchMap::new(space, advertised, (fw, fh), content));
        let (video, touch, advertised) = (p.video, p.touch, p.input.touch);
        let session = p.video().session_id;
        let report = self.projection_report();
        report.ui_config_last = Some(format!("{shown} content={content}"));
        report.selected = Some(video);
        report.input.touch_map = touch;
        // The frame size is unchanged, so the encoder keeps running and only the scene
        // moves. Answered whatever the app does with it: the head unit is waiting.
        self.push_app_event(AppEvent::VideoGeometry(VideoStart {
            session,
            video,
            touch: advertised,
        }));
        // The reply carries the configuration just received, as field 1 of its own
        // one-field message: Android Auto 17.6 builds it that way (class Lxpx, whose only
        // field is an AdditionalVideoConfig, set straight from the request and marked
        // present with `bitField0_ |= 1`; see docs/references.md). Since the request has
        // the same shape, the reply body is the request's field 1 rewrapped.
        let mut reply = ids::av::UPDATE_UI_CONFIG_REPLY.to_be_bytes().to_vec();
        reply.push((1 << 3) | 2);
        let mut len = inner.len() as u64;
        loop {
            let byte = (len & 0x7f) as u8;
            len >>= 7;
            reply.push(if len == 0 { byte } else { byte | 0x80 });
            if len == 0 {
                break;
            }
        }
        reply.extend_from_slice(inner);
        let body_len = reply.len() - 2;
        let channel = video.channel;
        if self.send_on(channel, false, true, reply, true, now) {
            let report = self.projection_report();
            report.ui_config_responses += 1;
            report.ui_config_reply_len = Some(body_len);
        }
    }

    /// An acknowledgement on one stream's channel. Each stream has its own session id and
    /// its own window, so an audio ack never frees a video credit or the other way round.
    fn media_ack(&mut self, index: usize, body: &[u8], now: Instant) {
        let ack = decode::<AvMediaAckIndication>(body);
        let stream = &self.projection_mut().streams[index];
        let (kind, started, session, window, previous) = (
            stream.kind,
            stream.started,
            stream.session_id,
            stream.window,
            stream.prev_session_id,
        );
        // Units still in flight when a focus loss stopped the stream are acknowledged under
        // the id they were sent with. That is neither an invalid ack nor a wrong-session
        // one: it is the expected tail of a session this source closed on purpose.
        if let (Ok(a), Some(prev)) = (&ack, previous) {
            if a.session_id & 0xff == prev & 0xff && prev & 0xff != session & 0xff {
                self.projection_report().acks_previous_session += 1;
                return;
            }
        }
        let Ok(ack) = ack
            .map_err(|_| ())
            .and_then(|a| if started { Ok(a) } else { Err(()) })
        else {
            return invalid_ack(self.projection_report(), kind);
        };
        // Session IDs are compared modulo 256 (open-android-auto AVMediaAckIndication).
        if ack.session_id & 0xff != session & 0xff {
            let report = self.projection_report();
            return match kind {
                StreamKind::Video => report.acks_wrong_session += 1,
                StreamKind::Audio => report.audio_acks_wrong_session += 1,
            };
        }
        let acked = if ack.receive_timestamps.is_empty() {
            ack.ack_count.unwrap_or(0)
        } else {
            u32::try_from(ack.receive_timestamps.len()).unwrap_or(u32::MAX)
        };
        if acked == 0 {
            return invalid_ack(self.projection_report(), kind);
        }
        let stream = &mut self.projection_mut().streams[index];
        let excess = acked.saturating_sub(stream.outstanding);
        stream.outstanding = stream.outstanding.saturating_sub(acked);
        if stream.outstanding < window {
            stream.stall_since = None;
        }
        let outstanding = stream.outstanding;
        let report = self.projection_report();
        match kind {
            StreamKind::Video => {
                report.acks += 1;
                report.acked_units += u64::from(acked);
                report.acks_excess += u64::from(excess);
                report.outstanding = outstanding;
            }
            StreamKind::Audio => {
                report.audio_acks += 1;
                report.audio_acked_units += u64::from(acked);
                report.audio_acks_excess += u64::from(excess);
                report.audio_outstanding = outstanding;
            }
        }
        self.pump_media(now);
    }

    fn input_event(&mut self, body: &[u8]) {
        let event = match decode::<InputEventIndication>(body) {
            Ok(event) => event,
            Err(_) => return self.projection_report().input.invalid += 1,
        };
        let p = self.projection_mut();
        let deliver = p.video().started && p.video().focused;
        let touch = p.touch;
        let stats = &mut self.projection_report().input;
        stats.events += 1;
        if !deliver {
            stats.dropped_unfocused += 1;
            return;
        }
        let mut events = Vec::new();
        if let Some(touch_event) = &event.touch_event {
            stats.touch_events += 1;
            let mapped = match touch {
                Some(map) => map.touch(touch_event, event.timestamp),
                None => Err("touch event without an advertised touch screen".into()),
            };
            match mapped {
                Ok(t) => {
                    match t.action {
                        TouchAction::Down => stats.down += 1,
                        TouchAction::Up => stats.up += 1,
                        TouchAction::Move => stats.moves += 1,
                        TouchAction::Cancel => stats.cancel += 1,
                        TouchAction::PointerDown => stats.pointer_down += 1,
                        TouchAction::PointerUp => stats.pointer_up += 1,
                    }
                    stats.pointers_max = stats.pointers_max.max(t.pointers.len());
                    for p in &t.pointers {
                        let r = stats
                            .raw_range
                            .get_or_insert([p.raw_x, p.raw_x, p.raw_y, p.raw_y]);
                        r[0] = r[0].min(p.raw_x);
                        r[1] = r[1].max(p.raw_x);
                        r[2] = r[2].min(p.raw_y);
                        r[3] = r[3].max(p.raw_y);
                    }
                    if t.action != TouchAction::Move {
                        stats.last_touch = Some(t.to_string());
                    }
                    events.push(AppEvent::Touch(t));
                }
                Err(reason) => {
                    stats.rejected += 1;
                    stats.last_rejection = Some(reason);
                }
            }
        }
        let mut transport = Vec::new();
        if let Some(buttons) = &event.button_event {
            for b in &buttons.button_events {
                stats.key_events += 1;
                stats.keys_down += u64::from(b.is_pressed);
                // A transport button is delivered as a transport action instead of a raw
                // key, and only on the press: a media control is an edge, and dispatching
                // it on the release as well would skip every other track (P5.1).
                match TransportAction::from_keycode(b.keycode) {
                    Some(action) if b.is_pressed => {
                        transport.push(action);
                        events.push(AppEvent::Transport {
                            action,
                            keycode: b.keycode,
                        });
                    }
                    Some(_) => {}
                    None => events.push(AppEvent::Key {
                        keycode: b.keycode,
                        down: b.is_pressed,
                        long_press: b.long_press == Some(true),
                        meta_state: b.meta_state,
                    }),
                }
            }
        }
        if event.absolute_input_event.is_some()
            || event.relative_input_event.is_some()
            || event.touchpad_event.is_some()
        {
            stats.other_events += 1;
        }
        for action in transport {
            let report = self.projection_report();
            report.transport_keys += 1;
            match report
                .transport_by_action
                .iter_mut()
                .find(|(name, _)| *name == action.name())
            {
                Some((_, count)) => *count += 1,
                None => report.transport_by_action.push((action.name(), 1)),
            }
        }
        for e in events {
            self.push_app_event(e);
        }
    }

    /// Sends queued output for every stream while its window, focus, and the shared
    /// output bound allow, and picks up encoder failures and keyframe requests. Called on
    /// every timer or wakeup.
    ///
    /// Each stream is pumped one whole message at a time, and `send_on` appends every
    /// fragment of a message to the output queue before returning, so a fragmenting video
    /// keyframe can never have an audio buffer wedged into the middle of it — the
    /// `MESSENGER_INTERTWINED_CHANNELS` hazard a second AV channel introduces (P5 §3).
    pub(crate) fn pump_media(&mut self, now: Instant) {
        if self.report.outcome.is_some() || self.report.stage != Stage::Projecting {
            return;
        }
        if let Some(detail) = self.media.take_failure() {
            return self.finish(Category::EncoderFailed, detail, now);
        }
        if self.media.take_keyframe_request() {
            self.projection_report().keyframe_requests += 1;
            self.push_app_event(AppEvent::KeyframeRequest);
        }
        // An audio failure stops audio; it never ends a session that still has a picture.
        if let Some(detail) = self.audio.take_failure() {
            self.stop_audio(&format!("audio source failed: {detail}"), now);
        }
        if self.projection.is_none() {
            return;
        }
        self.watch_congestion(now);
        let streams = self.projection.as_ref().map_or(0, |p| p.streams.len());
        for index in 0..streams {
            self.pump_stream(index, now);
        }
        // Sending may just have drained a congestion episode's queue.
        self.ask_drained_keyframe(now);
    }

    /// When the IDR a drained congestion episode waits for may be asked for: spaced from
    /// the last keyframe sent or asked for, so a head unit that has only just caught up is
    /// not handed the most expensive frame there is straight away. `None` when nothing is
    /// waiting.
    pub(crate) fn drained_keyframe_due(&self) -> Option<Instant> {
        let p = self.projection.as_ref()?;
        if !self.media.wants_keyframe() {
            return None;
        }
        let spacing = self.config.projection.keyframe_spacing;
        // With no keyframe yet there is nothing to space from: due at once.
        Some(
            [p.last_keyframe_sent, p.last_keyframe_asked]
                .into_iter()
                .flatten()
                .max()
                .map_or(p.projecting_since.unwrap_or(self.started), |at| {
                    at + spacing
                }),
        )
    }

    fn ask_drained_keyframe(&mut self, now: Instant) {
        if self.drained_keyframe_due().is_none_or(|due| now < due) {
            return;
        }
        self.media.keyframe_asked();
        self.projection_mut().last_keyframe_asked = Some(now);
        let report = self.projection_report();
        report.keyframe_requests += 1;
        report.keyframe_requests_drained += 1;
        if let Some(row) = self.timeline_row(now) {
            row.requests += 1;
        }
        self.push_app_event(AppEvent::KeyframeRequest);
    }

    /// Records new congestion episodes and drops in the timeline, and restarts the stream
    /// when the head unit keeps falling behind ([`ProjectionConfig::congestion_restart`]).
    fn watch_congestion(&mut self, now: Instant) {
        let (episodes, dropped) = self.media.congestion_totals();
        let (seen_episodes, seen_dropped) = self.projection_mut().congestion_seen;
        if (episodes, dropped) == (seen_episodes, seen_dropped) {
            return;
        }
        let new_episodes = episodes.saturating_sub(seen_episodes);
        let new_dropped = dropped.saturating_sub(seen_dropped);
        if let Some(row) = self.timeline_row(now) {
            row.episodes += new_episodes as u32;
            row.dropped += new_dropped as u32;
        }
        let config = &self.config.projection;
        let (restart, limit, window, cooldown) = (
            config.congestion_restart,
            config.congestion_restart_episodes,
            config.congestion_restart_window,
            config.congestion_restart_cooldown,
        );
        let p = self.projection_mut();
        p.congestion_seen = (episodes, dropped);
        // Bounded: only the last `limit` matter to the decision.
        for _ in 0..new_episodes.min(limit as u64) {
            p.congestion_recent.push_back(now);
        }
        while p.congestion_recent.len() > limit.max(1) {
            p.congestion_recent.pop_front();
        }
        while p
            .congestion_recent
            .front()
            .is_some_and(|&at| now.saturating_duration_since(at) > window)
        {
            p.congestion_recent.pop_front();
        }
        if !restart || limit == 0 || p.congestion_recent.len() < limit {
            return;
        }
        let video = p.video();
        if !video.started || !video.focused {
            return;
        }
        if p.last_congestion_restart
            .is_some_and(|at| now.saturating_duration_since(at) < cooldown)
        {
            // Counted once per due restart: the window starts over either way.
            p.congestion_recent.clear();
            self.projection_report().congestion_restarts_held += 1;
            return;
        }
        let count = p.congestion_recent.len();
        self.restart_congested_stream(
            format!(
                "{count} congestion episodes within {} ms",
                window.as_millis()
            ),
            now,
        );
    }

    /// Stops and restarts the video stream as a screen switch does, without the head unit
    /// having moved focus: stop indication, start indication under a new session id, then
    /// a rebuilt encoder's configuration and IDR. Spec §8.2.
    fn restart_congested_stream(&mut self, reason: String, now: Instant) {
        {
            let p = self.projection_mut();
            p.congestion_recent.clear();
            p.last_congestion_restart = Some(now);
        }
        self.stop_video_stream(false, now);
        self.projection_report().congestion_restarts += 1;
        if let Some(row) = self.timeline_row(now) {
            row.restarts += 1;
        }
        // Before the restart's own VideoStart and KeyframeRequest, so the app has begun
        // rebuilding before it is asked for the IDR.
        self.push_app_event(AppEvent::VideoRestart { reason });
        self.restart_video_stream(false, now);
    }

    /// The timeline row for `now`, appending rows as minutes pass; `None` before projection
    /// starts or once [`TIMELINE_LIMIT`] rows are kept.
    fn timeline_row(&mut self, now: Instant) -> Option<&mut MinuteRow> {
        let since = self.projection.as_ref()?.projecting_since?;
        let minute = (now.saturating_duration_since(since).as_secs() / 60) as u32;
        let report = self.projection_report();
        if report.timeline.last().map(|r| r.minute) != Some(minute) {
            if report.timeline.len() >= TIMELINE_LIMIT {
                report.timeline_truncated = true;
                return None;
            }
            report.timeline.push(MinuteRow {
                minute,
                ..MinuteRow::default()
            });
        }
        report.timeline.last_mut()
    }

    fn pump_stream(&mut self, index: usize, now: Instant) {
        loop {
            let Some(p) = &self.projection else { return };
            let Some(stream) = p.streams.get(index) else {
                return;
            };
            let kind = stream.kind;
            if !stream.focused || stream.outstanding >= stream.window {
                break;
            }
            // Control and input traffic are never stuck behind media, whichever stream
            // produced it: the bound is on the shared queue.
            if self.pending_output().len() >= self.config.projection.output_limit {
                break;
            }
            let link = match kind {
                StreamKind::Video => &self.media,
                StreamKind::Audio => &self.audio,
            };
            let Some(unit) = link.pop() else { break };
            let mut message = Vec::with_capacity(unit.bytes.len() + 10);
            match unit.kind {
                UnitKind::Config => {
                    message.extend_from_slice(&ids::av::MEDIA.to_be_bytes());
                }
                UnitKind::Frame { .. } => {
                    message.extend_from_slice(&ids::av::MEDIA_WITH_TIMESTAMP.to_be_bytes());
                    message.extend_from_slice(&unit.timestamp_us.to_be_bytes());
                }
            }
            message.extend_from_slice(&unit.bytes);
            let channel = self.projection_mut().streams[index].channel;
            if !self.send_on(channel, false, true, message, true, now) {
                return;
            }
            let p = self.projection_mut();
            let stream = &mut p.streams[index];
            // Decoder configuration (0x0001) is not a timestamped video frame and
            // takes no frame credit. Counting it leaks a credit whenever SPS/PPS
            // is sent to a receiver that acknowledges only 0x0000 frames.
            if matches!(unit.kind, UnitKind::Frame { .. }) {
                stream.outstanding += 1;
                stream.first_unit_deadline = None;
            }
            let outstanding = stream.outstanding;
            let full = outstanding >= stream.window;
            if full {
                stream.stall_since.get_or_insert(now);
            }
            let mark_keyframe = kind == StreamKind::Video
                && matches!(unit.kind, UnitKind::Frame { keyframe: true })
                && !p.first_keyframe_sent;
            if mark_keyframe {
                p.first_keyframe_sent = true;
            }
            let report = self.projection_report();
            match kind {
                StreamKind::Video => {
                    report.media_bytes_sent += unit.bytes.len() as u64;
                    match unit.kind {
                        UnitKind::Config => report.configs_sent += 1,
                        UnitKind::Frame { keyframe: true } => report.keyframes_sent += 1,
                        UnitKind::Frame { keyframe: false } => report.frames_sent += 1,
                    }
                    report.outstanding = outstanding;
                    report.outstanding_max = report.outstanding_max.max(outstanding);
                    report.window_full_events += u64::from(full);
                }
                StreamKind::Audio => {
                    report.audio_bytes_sent += unit.bytes.len() as u64;
                    report.audio_buffers_sent += 1;
                    report.audio_outstanding = outstanding;
                    report.audio_outstanding_max = report.audio_outstanding_max.max(outstanding);
                    report.audio_window_full_events += u64::from(full);
                }
            }
            if mark_keyframe {
                self.mark(Marker::FirstKeyframe);
            }
            if kind == StreamKind::Video {
                if let UnitKind::Frame { keyframe } = unit.kind {
                    if keyframe {
                        self.projection_mut().last_keyframe_sent = Some(now);
                    }
                    if let Some(row) = self.timeline_row(now) {
                        if keyframe {
                            row.keyframes += 1;
                        } else {
                            row.frames += 1;
                        }
                    }
                }
            }
        }
    }

    /// Before a shutdown request: stop both streams and their sources.
    pub(crate) fn stop_video(&mut self, now: Instant) {
        self.stop_audio("projection stopping", now);
        self.stop_navigation("projection stopping", now);
        let Some(p) = &self.projection else { return };
        let video = p.video();
        if !video.started || video.stop_emitted {
            return;
        }
        let channel = video.channel;
        self.media.close();
        if self.send_on(
            channel,
            false,
            false,
            encode_message(ids::av::STOP_INDICATION, &AvChannelStopIndication {}),
            true,
            now,
        ) {
            self.projection_report().stop_indication_sent = true;
        }
        self.video_stopped("stop indication sent", now);
    }

    /// Records the end of the stream and tells the app once.
    pub(crate) fn video_stopped(&mut self, reason: &str, now: Instant) {
        self.media.close();
        self.audio.close();
        let Some(p) = self.projection.as_mut() else {
            return;
        };
        p.limit_deadline = None;
        p.audio_focus_deadline = None;
        for stream in &mut p.streams {
            stream.first_unit_deadline = None;
            stream.stall_since = None;
        }
        // An abrupt ending — an I/O failure, a frame error — sends nothing, but the app's
        // audio thread still has to be told to stop producing.
        let audio = p
            .index_of(StreamKind::Audio)
            .map(|i| &mut p.streams[i])
            .filter(|s| s.started && !s.stop_emitted);
        if let Some(audio) = audio {
            audio.stop_emitted = true;
            audio.focused = false;
            self.app_events.push_back(AppEvent::AudioStop {
                reason: reason.to_owned(),
            });
        }
        let Some(p) = self.projection.as_mut() else {
            return;
        };
        let video = p.video_mut();
        if !video.started || video.stop_emitted {
            return;
        }
        video.stop_emitted = true;
        let projecting = p
            .projecting_since
            .map(|since| now.saturating_duration_since(since));
        self.projection_report().projecting = projecting;
        self.app_events.push_back(AppEvent::VideoStop {
            reason: reason.to_owned(),
        });
    }

    pub(crate) fn first_keyframe_written(&mut self, now: Instant) {
        let at = self.elapsed(now);
        self.projection_report().first_keyframe_written_at = Some(at);
        self.complete(Stage::Projecting);
    }

    /// Snapshot of encoder-side counters for the report.
    pub(crate) fn refresh_projection(&mut self) {
        if self.projection.is_some() {
            let counters = self.media.counters();
            let audio = self
                .projection
                .as_ref()
                .is_some_and(|p| p.index_of(StreamKind::Audio).is_some())
                .then(|| self.audio.counters());
            let report = self.projection_report();
            report.media = counters;
            if let Some(audio) = audio {
                report.audio = Some(audio);
            }
        }
    }
}

/// Counts an undecodable or empty acknowledgement against the stream it arrived on.
fn invalid_ack(report: &mut ProjectionReport, kind: StreamKind) {
    match kind {
        StreamKind::Video => report.acks_invalid += 1,
        StreamKind::Audio => report.audio_acks_invalid += 1,
    }
}

/// The PCM buffer size this source asks the app for.
///
/// 2048 sample frames at 48 kHz stereo is 8192 bytes ≈ 42.7 ms, which an empirical 2018
/// openauto observation and a 2026 APK trace arrive at independently; 1024 frames at
/// 16 kHz mono is 2048 bytes = 64 ms. Both are one BULK frame at a 16 KiB fragment
/// payload, so an audio message never fragments.
fn audio_buffer_bytes(config: discovery::AudioConfigChoice) -> usize {
    let frames = if config.sample_rate >= 48_000 {
        2048
    } else {
        1024
    };
    frames * config.frame_bytes()
}

/// One sensor value rendered as `name=value` pairs, with an absent optional written `-`.
///
/// Sensor payloads are small and flat, so the report keeps them as text rather than
/// growing 26 typed fields nothing else reads. Nothing here formats free text from the
/// head unit: every field in these messages is a number, a boolean, or opaque bytes
/// summarised by length.
#[derive(Default)]
struct Fields(String);

impl Fields {
    fn add<T: fmt::Display>(&mut self, name: &str, value: T) -> &mut Self {
        if !self.0.is_empty() {
            self.0.push(' ');
        }
        self.0.push_str(name);
        self.0.push('=');
        self.0.push_str(&value.to_string());
        self
    }

    fn opt<T: fmt::Display>(&mut self, name: &str, value: Option<T>) -> &mut Self {
        match value {
            Some(value) => self.add(name, value),
            None => self.add(name, "-"),
        }
    }

    fn list<T: fmt::Display>(&mut self, name: &str, values: &[T]) -> &mut Self {
        if values.is_empty() {
            return self.add(name, "-");
        }
        let joined: Vec<String> = values.iter().map(T::to_string).collect();
        self.add(name, joined.join("/"))
    }

    fn take(&mut self) -> String {
        std::mem::take(&mut self.0)
    }
}

/// Every value in one `SensorEventIndication` as (sensor type, rendered fields), in the
/// schema's field order (P5.2).
///
/// The field number of each payload is the same integer as its `SensorType`, all the way
/// to 26, so the pairing below is the schema's own and not a guess — the one place it
/// reads oddly is field 14, `steering_wheel`, which is `DEAD_RECONING`.
///
/// A type appears once per entry, so a head unit batching three speeds into one
/// indication is counted as three values. Latitude and longitude are written `redacted`
/// unless `location` is set: they identify the vehicle, and spec §10 keeps that out of an
/// ordinary report. Everything else GPS carries — accuracy, altitude, speed, bearing,
/// satellite counts — says whether the fix is real without saying where it is.
fn harvest(event: &SensorEventIndication, location: bool) -> Vec<(i32, String)> {
    use sensor_type::Enum as T;
    let mut out = Vec::new();
    let mut f = Fields::default();
    for v in &event.gps_location {
        if location {
            f.add("latitude", v.latitude).add("longitude", v.longitude);
        } else {
            f.add("latitude", "redacted").add("longitude", "redacted");
        }
        f.opt("accuracy", v.accuracy)
            .opt("altitude", v.altitude)
            .opt("speed", v.speed)
            .opt("bearing", v.bearing);
        out.push((T::Location as i32, f.take()));
    }
    for v in &event.compass {
        f.add("bearing", v.bearing)
            .opt("pitch", v.pitch)
            .opt("roll", v.roll);
        out.push((T::Compass as i32, f.take()));
    }
    for v in &event.speed {
        f.add("speed", v.speed)
            .opt("cruise_engaged", v.cruise_engaged)
            .opt("unknown_4", v.unknown_4);
        out.push((T::CarSpeed as i32, f.take()));
    }
    for v in &event.rpm {
        f.add("rpm", v.rpm);
        out.push((T::Rpm as i32, f.take()));
    }
    for v in &event.odometer {
        f.add("total_mileage", v.total_mileage)
            .opt("trip_mileage", v.trip_mileage);
        out.push((T::Odometer as i32, f.take()));
    }
    for v in &event.fuel_level {
        f.opt("fuel_level", v.fuel_level)
            .opt("range", v.range)
            .opt("low_fuel", v.low_fuel);
        out.push((T::FuelLevel as i32, f.take()));
    }
    for v in &event.parking_brake {
        f.add("parking_brake", v.parking_brake);
        out.push((T::ParkingBrake as i32, f.take()));
    }
    for v in &event.gear {
        f.add("gear", v.gear);
        out.push((T::Gear as i32, f.take()));
    }
    for v in &event.diagnostics {
        // Manufacturer bytes with no schema here to read them by: counted, never printed,
        // for the same reason `capture_unhandled` bounds unexamined bodies.
        f.add("bytes", v.diagnostics.as_ref().map_or(0, Vec::len));
        out.push((T::Diagnostics as i32, f.take()));
    }
    for v in &event.night_mode {
        f.opt("is_night", v.is_night);
        out.push((T::NightData as i32, f.take()));
    }
    for v in &event.enviorment {
        f.opt("temperature", v.temperature)
            .opt("pressure", v.pressure)
            .opt("rain", v.rain);
        out.push((T::Environment as i32, f.take()));
    }
    for v in &event.hvac {
        f.opt("target_temperature", v.target_temperature)
            .opt("current_temperature", v.current_temperature);
        out.push((T::Hvac as i32, f.take()));
    }
    for v in &event.driving_status {
        f.add("status", v.status);
        out.push((T::DrivingStatus as i32, f.take()));
    }
    for v in &event.steering_wheel {
        f.opt("steering_angle", v.steering_angle)
            .list("wheel_speed", &v.wheel_speed);
        out.push((T::DeadReconing as i32, f.take()));
    }
    for v in &event.passenger {
        f.opt("passenger_present", v.passenger_present);
        out.push((T::Passenger as i32, f.take()));
    }
    for v in &event.door {
        f.opt("hood_open", v.hood_open)
            .opt("boot_open", v.boot_open)
            .list("door_open", &v.door_open);
        out.push((T::Door as i32, f.take()));
    }
    for v in &event.light {
        f.opt("headlight", v.headlight)
            .opt("indicator", v.indicator)
            .opt("hazard_light_on", v.hazard_light_on);
        out.push((T::Light as i32, f.take()));
    }
    for v in &event.tire_pressure {
        f.list("tire_pressures", &v.tire_pressures);
        out.push((T::Tire as i32, f.take()));
    }
    for v in &event.accel {
        f.opt("x", v.acceleration_x)
            .opt("y", v.acceleration_y)
            .opt("z", v.acceleration_z);
        out.push((T::Accel as i32, f.take()));
    }
    for v in &event.gyro {
        f.opt("x", v.rotation_speed_x)
            .opt("y", v.rotation_speed_y)
            .opt("z", v.rotation_speed_z);
        out.push((T::Gyro as i32, f.take()));
    }
    for v in &event.gps_satellite {
        // Satellite identities and signal strengths are not position, so they need no
        // redaction; the count is what says whether the vehicle has a real fix.
        let in_use = v.satellites.iter().filter(|s| s.in_use).count();
        f.add("satellite_count", v.satellite_count)
            .opt("satellites_used", v.satellites_used)
            .add("entries", v.satellites.len())
            .add("in_use", in_use);
        out.push((T::Gps as i32, f.take()));
    }
    for v in &event.toll_card {
        f.add("toll_card_present", v.toll_card_present);
        out.push((T::TollCard as i32, f.take()));
    }
    for v in &event.vehicle_energy_model {
        match &v.vehicle_info {
            Some(info) => {
                f.opt("battery_level_wh", info.battery_level_wh.map(|v| v.value))
                    .opt(
                        "battery_capacity_wh",
                        info.battery_capacity_wh.map(|v| v.value),
                    )
                    .add("hvac_status", info.hvac_status)
                    .add("external_temp_celsius", info.external_temp_celsius)
                    .add("battery_temp_celsius", info.battery_temp_celsius);
            }
            None => {
                f.add("vehicle_info", "-");
            }
        }
        out.push((T::VehicleEnergyModel as i32, f.take()));
    }
    for _ in &event.trailer {
        // `TrailerData` is an empty message: its presence is the whole signal.
        f.add("present", true);
        out.push((T::Trailer as i32, f.take()));
    }
    for v in &event.raw_vehicle_energy_model {
        f.add("bytes", v.raw_data.as_ref().map_or(0, Vec::len));
        out.push((T::RawVehicleEnergyModel as i32, f.take()));
    }
    for v in &event.raw_ev_trip_settings {
        f.add("bytes", v.raw_data.as_ref().map_or(0, Vec::len));
        out.push((T::RawEvTripSettings as i32, f.take()));
    }
    out
}

/// Bytes of the first length-delimited field 1 of a protobuf message, skipping any other
/// field. For the undocumented wrappers this source has to open by hand; every nested
/// payload still goes through the vendored schema.
fn first_field(body: &[u8]) -> Option<&[u8]> {
    let mut i = 0;
    while i < body.len() {
        let (tag, next) = varint(body, i)?;
        i = next;
        let (field, wire) = ((tag >> 3) as u32, (tag & 7) as u8);
        match wire {
            0 => i = varint(body, i)?.1,
            1 => i = i.checked_add(8)?,
            5 => i = i.checked_add(4)?,
            2 => {
                let (len, next) = varint(body, i)?;
                let len = usize::try_from(len).ok()?;
                let end = next.checked_add(len)?;
                let bytes = body.get(next..end)?;
                if field == 1 {
                    return Some(bytes);
                }
                i = end;
            }
            _ => return None,
        }
        if i > body.len() {
            return None;
        }
    }
    None
}

/// A base-128 varint at `at`, with the offset just past it.
fn varint(body: &[u8], at: usize) -> Option<(u64, usize)> {
    let (mut value, mut shift, mut i) = (0u64, 0u32, at);
    loop {
        let byte = *body.get(i)?;
        i += 1;
        value |= u64::from(byte & 0x7f).checked_shl(shift)?;
        if byte & 0x80 == 0 {
            return Some((value, i));
        }
        shift += 7;
        if shift > 63 {
            return None;
        }
    }
}
