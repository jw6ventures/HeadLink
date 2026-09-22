//! JVM-independent session registry behind the JNI bridge (spec §5.3).
//!
//! * `start` takes ownership of the descriptor on invocation and closes it on every
//!   path: rejected starts drop it immediately, and a started session closes it when
//!   its I/O threads have been joined.
//! * Handles are positive session generations supplied by the caller and required to
//!   increase; no pointer crosses the boundary. At most one session exists between a
//!   successful `start` and the matching `stop`, which serializes native work.
//! * The session worker thread owns the protocol and delivers immutable events tagged
//!   with the generation. `stop` requests cancellation and joins that worker (which
//!   joins its I/O threads) before releasing the handle.
use aap_session::{
    accessory::{self, Options, Stop, Teardown},
    discovery::VideoPreference,
    media::{BufferFlags, MediaLink, DEFAULT_AUDIO_LIMIT},
    navigation::{GuidanceLink, RouteState, Source, Units},
    now_playing::{NowPlaying, NowPlayingLink},
    projection::{AppEvent, SensorSet, SetupField},
    version::Profile,
    Config, KeepaliveProfile, Report, Session, Stage, StopAfter,
};
use aap_tls::{identity::parse_fingerprint, Identity, PeerPolicy, ServerConfig, TlsProfile};
use std::{
    fmt::Write as _,
    os::fd::{FromRawFd, OwnedFd, RawFd},
    panic::{catch_unwind, AssertUnwindSafe},
    path::{Path, PathBuf},
    sync::{Arc, Mutex, MutexGuard, PoisonError},
    thread::{self, JoinHandle, ThreadId},
    time::{Duration, Instant},
};

/// `start` results below zero. The Kotlin bridge mirrors these values.
pub const START_INVALID_DESCRIPTOR: i64 = -1;
pub const START_INVALID_CONFIG: i64 = -2;
pub const START_BUSY: i64 = -3;
pub const START_STALE_IO: i64 = -4;
pub const START_SPAWN_FAILED: i64 = -5;
pub const START_CALLBACK_INVALID: i64 = -6;
pub const START_INTERNAL_ERROR: i64 = -7;
/// The configured source identity could not be loaded.
pub const START_IDENTITY_UNAVAILABLE: i64 = -8;

/// `submit_video` results below zero; zero and above are `aap_session::media::Submit`
/// values.
pub const SUBMIT_UNKNOWN_HANDLE: i32 = -1;
pub const SUBMIT_INVALID_BUFFER: i32 = -2;
pub const SUBMIT_INTERNAL_ERROR: i32 = -3;

/// `submit_now_playing` results. Zero and one are both successful publications; they are
/// distinguished so an app polling an unchanged player can tell it sent nothing new.
pub const NOW_PLAYING_CHANGED: i32 = 0;
pub const NOW_PLAYING_UNCHANGED: i32 = 1;
pub const NOW_PLAYING_UNKNOWN_HANDLE: i32 = -1;
/// The playback state was not one of the five defined values, or a string was unreadable.
pub const NOW_PLAYING_INVALID: i32 = -2;
pub const NOW_PLAYING_INTERNAL_ERROR: i32 = -3;

/// `submit_guidance` states (P6.5), mirrored by the Kotlin bridge. Results reuse the
/// `NOW_PLAYING_*` codes: 0 changed, 1 unchanged, negative for an unknown handle, an
/// invalid state, or an internal error.
pub const GUIDANCE_IDLE: i32 = 0;
pub const GUIDANCE_GUIDING: i32 = 1;
pub const GUIDANCE_REROUTING: i32 = 2;
pub const GUIDANCE_ARRIVED: i32 = 3;

/// Longest accepted configuration text.
pub const CONFIG_LIMIT: usize = 4096;
/// Most pinned head-unit fingerprints accepted.
pub const PIN_LIMIT: usize = 8;

/// Event kinds delivered to the app. The Kotlin bridge mirrors these values; every
/// payload is ASCII `key=value` text.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EventKind {
    /// A session milestone changed; the report is a snapshot.
    Progress = 1,
    /// Final report, including transport teardown. The last event of a session.
    Finished = 2,
    /// Start the encoder: `session`, `channel`, `width`, `height`, `fps`, the content
    /// rectangle, and the touch screen size.
    VideoStart = 3,
    /// Stop and release the encoder: `reason`.
    VideoStop = 4,
    /// Produce an IDR now (no payload fields).
    KeyframeRequest = 5,
    /// Projected focus changed: `projected`, `mode`.
    Focus = 6,
    /// A touch event in video frame coordinates: `action`, `pointer`, `pointers`.
    Touch = 7,
    /// A head-unit button: `keycode`, `down`, `long_press`, `meta_state`.
    Key = 8,
    /// Sensor restriction data: `driving_status`, `night`.
    Restriction = 9,
    /// The content rectangle moved inside an unchanged frame; lay the scene out again.
    /// Same fields as [`EventKind::VideoStart`], so one parser serves both.
    VideoGeometry = 10,
    /// A head-unit transport button: `action` (a [`aap_session::now_playing::
    /// TransportAction`] name) and `keycode`. Delivered on the press only.
    Transport = 11,
    /// Start producing PCM (P5.3): `session`, `channel`, `sample_rate`, `bit_depth`,
    /// `channels`, `buffer_bytes`. Audio focus is already granted when this arrives.
    AudioStart = 12,
    /// Stop producing PCM: `reason`.
    AudioStop = 13,
    /// Latest vehicle position and motion (P5.5): `latitude`, `longitude`, `accuracy`,
    /// `altitude`, `gps_speed`, `bearing`, `car_speed`, `gear`, `parking_brake`,
    /// `satellite_count`, `satellites_used`. Every value is the **raw wire integer** or
    /// `-`; nothing here is scaled into units. See
    /// [`aap_session::projection::VehicleState`] for why.
    Vehicle = 14,
}

/// The payload delivered with an app event: one `key=value` field per line, like a
/// report, so the Kotlin bridge parses both the same way. The single-line `Display` forms
/// of a video start and a touch have no spaces inside their values, so splitting on spaces
/// yields those lines.
pub fn event_payload(event: &AppEvent) -> (EventKind, String) {
    match event {
        AppEvent::VideoStart(start) => (
            EventKind::VideoStart,
            format!("{start}\n").replace(' ', "\n"),
        ),
        AppEvent::VideoGeometry(start) => (
            EventKind::VideoGeometry,
            format!("{start}\n").replace(' ', "\n"),
        ),
        AppEvent::VideoStop { reason } => (EventKind::VideoStop, format!("reason={reason}\n")),
        AppEvent::AudioStart(start) => (
            EventKind::AudioStart,
            format!("{start}\n").replace(' ', "\n"),
        ),
        AppEvent::AudioStop { reason } => (EventKind::AudioStop, format!("reason={reason}\n")),
        AppEvent::KeyframeRequest => (EventKind::KeyframeRequest, String::new()),
        AppEvent::Focus { projected, mode } => (
            EventKind::Focus,
            format!("projected={projected}\nmode={mode}\n"),
        ),
        AppEvent::Touch(touch) => (EventKind::Touch, format!("{touch}\n").replace(' ', "\n")),
        AppEvent::Key {
            keycode,
            down,
            long_press,
            meta_state,
        } => (
            EventKind::Key,
            format!(
                "keycode={keycode}\ndown={down}\nlong_press={long_press}\nmeta_state={meta_state}\n"
            ),
        ),
        AppEvent::Transport { action, keycode } => (
            EventKind::Transport,
            format!("action={}\nkeycode={keycode}\n", action.name()),
        ),
        AppEvent::Vehicle(state) => (EventKind::Vehicle, format!("{state}\n").replace(' ', "\n")),
        AppEvent::Restriction {
            driving_status,
            night,
        } => (
            EventKind::Restriction,
            format!(
                "driving_status={}\nnight={}\n",
                driving_status.map_or("-".into(), |s| s.to_string()),
                night.map_or("-".into(), |n| n.to_string())
            ),
        ),
    }
}

/// Receives session events on the worker thread. Implementations must not block for
/// long; calling `stop` for the same session from here only requests cancellation.
pub trait Events: Send + 'static {
    /// Returns whether the event was delivered.
    fn event(&mut self, generation: u64, kind: EventKind, report: &[u8]) -> bool;
}

#[derive(Debug, Clone)]
pub struct StartConfig {
    pub generation: u64,
    pub session_uuid: String,
    /// `session.tls` is filled from this directory by [`Manager::start`].
    pub identity_dir: Option<PathBuf>,
    pub tls_profile: TlsProfile,
    pub peer: PeerPolicy,
    pub session: Config,
}

/// Strict ASCII `key=value` lines. Required: `generation` (1..=i64::MAX), `session_uuid`
/// (lowercase hyphenated UUID), `profile`. Optional: `stop_after` (`version` by default,
/// `auth`, `discovery`, or `projection`); `version_wait_ms`, `client_hello_wait_ms`,
/// `tls_wait_ms`, `auth_wait_ms`, `discovery_wait_ms` (1..=60000); `hold_ms` (0..=30000);
/// `capture_discovery` (`true`/`false`, default `false`; keeps the raw discovery
/// response bytes, which are sensitive); `capture_unhandled` (`true`/`false`, default
/// `false`; keeps a bounded body for each message no profile interprets);
/// `video_preference` (`largest` by default,
/// `smallest`, or a `WIDTHxHEIGHT` frame size to pin); `keepalive` (`mirror` by default: pings follow the
/// head unit's encryption; `plaintext` or `encrypted` force one). Beyond
/// `version`, also required: `identity_dir` (absolute), `tls_profile`, and `peer_policy`
/// (`first-use`, or `pinned` with optional `peer_pins`, up to eight comma-separated
/// SHA-256 hex fingerprints). Only with `stop_after=projection`: `projection_ms`
/// (1..=3600000; absent means until Stop), `sensors` (`none`/`false` by default,
/// `restriction`/`true`, `all`, or a comma-separated list of `SensorType` names or
/// numbers), `open_all_channels` and `request_focus` (`true`/`false`; Android Auto opens every
/// advertised channel and does not request focus), `setup_field`
/// (`codec-type` by default, or `config-index` for a head unit that reads field 1 of the
/// AV setup request as an index),
/// `focus_wait_ms` and `ack_timeout_ms` (1..=60000). Unknown, duplicate, missing, or
/// inapplicable keys fail.
///
/// P5.1 adds two more projection-only keys: `media_info` (`true`/`false`, default
/// `false`) opens the advertised now-playing channel, and `media_interval_ms`
/// (1..=60000, default 1000) is the shortest gap between messages on it.
///
/// P5.3 adds `audio` (`true`/`false`, default `false`), which opens one advertised PCM
/// `MEDIA` audio channel, takes audio focus on the control channel and streams app-owned
/// PCM on it, and `audio_focus_wait_ms` (1..=60000, default 10000). Every audio failure —
/// a refused channel, a refused setup, no focus response — is recorded and skipped.
///
/// P6.2 adds five projection-only keys for the head unit's own instrument cluster:
/// `navigation` (`true`/`false`, default `false`) opens the advertised `navigation`
/// channel and drives it with a scripted maneuver sequence; `navigation_units`
/// (`imperial` by default, or `metric`) decides how its distances are written;
/// `navigation_interval_ms` (1..=60000, default 1000) is the shortest gap between updates
/// on it, **raised to the head unit's advertised `minimum_interval_ms`** when that is
/// larger; `navigation_focus_wait_ms` (1..=60000, default 10000) bounds both halves of the
/// cluster handshake, the wait for `InstrumentClusterStart` and the wait for a navigation
/// focus response; and `navigation_force` (`true`/`false`, default `false`) sends the
/// sequence anyway when the head unit announces no cluster or grants no focus, which the
/// report records. Every navigation failure is recorded and skipped, never fatal.
///
/// P6.5 adds `navigation_source` (`script` by default, or `route`): where the maneuvers
/// come from. `route` plays what the app publishes through `submit_guidance` instead of
/// the scripted demonstration.
///
/// P5.2 adds `sensor_location` (`true`/`false`, default `false`), which puts head-unit
/// latitude and longitude in the report. Those identify the vehicle, so it is off by
/// default and belongs with the other capture keys under spec §10; without it the
/// position sensors are still subscribed and counted, so a report says whether position
/// arrives without saying where.
pub fn parse_config(bytes: &[u8]) -> Result<StartConfig, String> {
    if bytes.len() > CONFIG_LIMIT {
        return Err(format!(
            "config is {} bytes; limit {CONFIG_LIMIT}",
            bytes.len()
        ));
    }
    let text = std::str::from_utf8(bytes)
        .ok()
        .filter(|t| t.is_ascii())
        .ok_or("config is not ASCII")?;
    let mut generation = None;
    let mut session_uuid = None;
    let mut profile = None;
    let mut identity_dir = None;
    let mut tls_profile = None;
    let mut peer_policy = None;
    let mut peer_pins = None;
    let mut session = Config::default();
    let mut projection_keys = false;
    let mut seen = Vec::new();
    for line in text.strip_suffix('\n').unwrap_or(text).split('\n') {
        let (key, value) = line
            .split_once('=')
            .ok_or_else(|| format!("config line without '=': {line:?}"))?;
        if seen.contains(&key) {
            return Err(format!("duplicate config key {key}"));
        }
        seen.push(key);
        match key {
            "generation" => {
                generation = Some(
                    value
                        .parse::<u64>()
                        .ok()
                        .filter(|g| (1..=i64::MAX as u64).contains(g))
                        .ok_or_else(|| format!("invalid generation {value:?}"))?,
                )
            }
            "session_uuid" if is_uuid(value) => session_uuid = Some(value.to_owned()),
            "session_uuid" => return Err(format!("invalid session_uuid {value:?}")),
            "profile" => {
                profile = Some(
                    Profile::from_name(value)
                        .ok_or_else(|| format!("unknown profile {value:?}"))?,
                )
            }
            "stop_after" => {
                session.stop_after = StopAfter::from_name(value)
                    .ok_or_else(|| format!("unknown stop_after {value:?}"))?
            }
            "version_wait_ms" => session.version_wait = wait(key, value, 1)?,
            "client_hello_wait_ms" => session.client_hello_wait = wait(key, value, 1)?,
            "tls_wait_ms" => session.tls_wait = wait(key, value, 1)?,
            "auth_wait_ms" => session.auth_wait = wait(key, value, 1)?,
            "discovery_wait_ms" => session.discovery_wait = wait(key, value, 1)?,
            "hold_ms" => {
                session.hold = wait(key, value, 0)?;
                if session.hold > Duration::from_secs(30) {
                    return Err(format!("invalid {key} {value:?}"));
                }
            }
            "capture_discovery" => session.capture_discovery_response = boolean(key, value)?,
            "capture_unhandled" => session.capture_unhandled = boolean(key, value)?,
            // Applies to discovery as well as projection, so it is not a projection key.
            "video_preference" => {
                session.video_preference = VideoPreference::from_name(value)
                    .ok_or_else(|| format!("unknown video_preference {value:?}"))?
            }
            "projection_ms" => {
                // Its own bound rather than wait()'s, which caps every millisecond value
                // at a minute. This one is a drive timer, not a response deadline: the
                // documented range is an hour, and the guard below it was written for an
                // hour, but nothing between 60001 and that hour ever reached the guard —
                // it failed the whole configuration as invalid instead. Found by a 90 s
                // lab drive (P6.1), which is a perfectly ordinary length for one.
                let limit = value
                    .parse::<u64>()
                    .ok()
                    .filter(|ms| (1..=3_600_000).contains(ms))
                    .map(Duration::from_millis)
                    .ok_or_else(|| format!("invalid {key} {value:?}"))?;
                session.projection.limit = Some(limit);
                projection_keys = true;
            }
            "sensors" => {
                session.projection.sensors = SensorSet::from_name(value)
                    .ok_or_else(|| format!("unknown sensors {value:?}"))?;
                projection_keys = true;
            }
            "sensor_location" => {
                session.projection.sensor_location = boolean(key, value)?;
                projection_keys = true;
            }
            "media_info" => {
                session.projection.media_info = boolean(key, value)?;
                projection_keys = true;
            }
            "media_interval_ms" => {
                session.projection.media_interval = wait(key, value, 1)?;
                projection_keys = true;
            }
            "audio" => {
                session.projection.audio = boolean(key, value)?;
                projection_keys = true;
            }
            "navigation" => {
                session.projection.navigation = boolean(key, value)?;
                projection_keys = true;
            }
            "navigation_units" => {
                session.projection.navigation_units = Units::from_name(value)
                    .ok_or_else(|| format!("unknown navigation_units {value:?}"))?;
                projection_keys = true;
            }
            "navigation_interval_ms" => {
                session.projection.navigation_interval = wait(key, value, 1)?;
                projection_keys = true;
            }
            "navigation_focus_wait_ms" => {
                session.projection.navigation_focus_wait = wait(key, value, 1)?;
                projection_keys = true;
            }
            "navigation_source" => {
                session.projection.navigation_source = Source::from_name(value)
                    .ok_or_else(|| format!("unknown navigation_source {value:?}"))?;
                projection_keys = true;
            }
            "navigation_force" => {
                session.projection.navigation_force = boolean(key, value)?;
                projection_keys = true;
            }
            "audio_focus_wait_ms" => {
                session.projection.audio_focus_wait = wait(key, value, 1)?;
                projection_keys = true;
            }
            "open_all_channels" => {
                session.projection.open_all_channels = boolean(key, value)?;
                projection_keys = true;
            }
            "request_focus" => {
                session.projection.request_focus = boolean(key, value)?;
                projection_keys = true;
            }
            "setup_field" => {
                session.projection.setup_field = SetupField::from_name(value)
                    .ok_or_else(|| format!("unknown setup_field {value:?}"))?;
                projection_keys = true;
            }
            "focus_wait_ms" => {
                session.projection.focus_wait = wait(key, value, 1)?;
                projection_keys = true;
            }
            "ack_timeout_ms" => {
                session.projection.ack_timeout = wait(key, value, 1)?;
                projection_keys = true;
            }
            "keepalive" => {
                session.keepalive = KeepaliveProfile::from_name(value)
                    .ok_or_else(|| format!("unknown keepalive {value:?}"))?
            }
            "identity_dir" if value.starts_with('/') && !value.contains("/../") => {
                identity_dir = Some(PathBuf::from(value))
            }
            "identity_dir" => return Err(format!("invalid identity_dir {value:?}")),
            "tls_profile" => {
                tls_profile = Some(
                    TlsProfile::from_name(value)
                        .ok_or_else(|| format!("unknown tls_profile {value:?}"))?,
                )
            }
            "peer_policy" if value == "first-use" || value == "pinned" => {
                peer_policy = Some(value.to_owned())
            }
            "peer_policy" => return Err(format!("unknown peer_policy {value:?}")),
            "peer_pins" => {
                let pins = if value.is_empty() {
                    Vec::new()
                } else {
                    value
                        .split(',')
                        .map(|pin| {
                            parse_fingerprint(pin)
                                .filter(|_| pin.bytes().all(|b| !b.is_ascii_uppercase()))
                                .ok_or_else(|| format!("invalid peer pin {pin:?}"))
                        })
                        .collect::<Result<Vec<_>, _>>()?
                };
                if pins.len() > PIN_LIMIT {
                    return Err(format!("more than {PIN_LIMIT} peer pins"));
                }
                peer_pins = Some(pins)
            }
            _ => return Err(format!("unknown config key {key:?}")),
        }
    }
    session.profile = profile.ok_or("missing profile")?;
    let session_uuid: String = session_uuid.ok_or("missing session_uuid")?;
    session.session_uuid = session_uuid.clone();
    let tls = session.stop_after != StopAfter::Version;
    let peer = match (peer_policy.as_deref(), peer_pins) {
        (Some("first-use"), None) => PeerPolicy::FirstUse,
        (Some("pinned"), pins) => PeerPolicy::Pinned(pins.unwrap_or_default()),
        (Some(_), Some(_)) => return Err("peer_pins requires peer_policy=pinned".into()),
        (None, None) if !tls => PeerPolicy::Pinned(Vec::new()),
        _ => return Err("missing peer_policy".into()),
    };
    if tls && (identity_dir.is_none() || tls_profile.is_none() || peer_policy.is_none()) {
        return Err(
            "stop_after beyond version requires identity_dir, tls_profile, and peer_policy".into(),
        );
    }
    if !tls && (identity_dir.is_some() || tls_profile.is_some() || peer_policy.is_some()) {
        return Err("TLS keys are not applicable with stop_after=version".into());
    }
    if projection_keys && session.stop_after != StopAfter::Projection {
        return Err("projection keys require stop_after=projection".into());
    }
    Ok(StartConfig {
        generation: generation.ok_or("missing generation")?,
        session_uuid,
        identity_dir,
        tls_profile: tls_profile.unwrap_or(TlsProfile::Aead),
        peer,
        session,
    })
}

fn boolean(key: &str, value: &str) -> Result<bool, String> {
    match value {
        "true" => Ok(true),
        "false" => Ok(false),
        _ => Err(format!("invalid {key} {value:?}")),
    }
}

fn wait(key: &str, value: &str, minimum: u64) -> Result<Duration, String> {
    value
        .parse::<u64>()
        .ok()
        .filter(|ms| (minimum..=60_000).contains(ms))
        .map(Duration::from_millis)
        .ok_or_else(|| format!("invalid {key} {value:?}"))
}

/// Loads, or creates, the source identity in `dir`, optionally replacing it, then runs
/// the in-memory TLS self-test. Returns nonsecret `key=value` report lines.
pub fn ensure_identity(dir: &Path, replace: bool) -> String {
    let loaded = if replace {
        Identity::generate().and_then(|identity| identity.save(dir).map(|()| (identity, true)))
    } else {
        Identity::load_or_create(dir)
    };
    match loaded {
        Err(e) => format!("identity_error={}\n", e.to_string().replace('\n', " ")),
        Ok((identity, created)) => {
            let identity = Arc::new(identity);
            let test = match aap_tls::self_test(identity.clone()) {
                Ok(summary) => format!("tls_self_test=pass {summary}"),
                Err(e) => format!("tls_self_test=FAIL {}", e.replace('\n', " ")),
            };
            format!(
                "{}identity_created={created}\n{test}\n\
                 tls_self_test_scope=in-memory TLS 1.2 handshake and record round trip with a \
                 local OpenSSL client; no head unit\n",
                identity.summary()
            )
        }
    }
}

fn is_uuid(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(i, b)| match i {
            8 | 13 | 18 | 23 => b == b'-',
            _ => b.is_ascii_digit() || (b'a'..=b'f').contains(&b),
        })
}

/// Takes ownership of `fd` if it is an open descriptor. An invalid number is not
/// closed, because this process does not own it.
pub fn take_descriptor(fd: RawFd) -> Option<OwnedFd> {
    // SAFETY: F_GETFD only queries descriptor flags.
    if fd < 0 || unsafe { libc::fcntl(fd, libc::F_GETFD) } < 0 {
        return None;
    }
    // SAFETY: the caller transferred ownership of this open descriptor.
    Some(unsafe { OwnedFd::from_raw_fd(fd) })
}

struct Entry {
    generation: u64,
    stop: Stop,
    media: Arc<MediaLink>,
    audio: Arc<MediaLink>,
    now_playing: Arc<NowPlayingLink>,
    guidance: Arc<GuidanceLink>,
    worker: Option<JoinHandle<()>>,
    worker_thread: ThreadId,
    report: Arc<Mutex<Vec<u8>>>,
}

#[derive(Default)]
struct Inner {
    last_generation: u64,
    current: Option<Entry>,
    /// Stop handle of the last released session, for detached I/O thread detection.
    previous: Option<Stop>,
}

pub struct Manager {
    inner: Mutex<Inner>,
    options: Options,
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

impl Default for Manager {
    fn default() -> Self {
        Self::new(Options::default())
    }
}

impl Manager {
    pub fn new(options: Options) -> Self {
        Self {
            inner: Mutex::new(Inner::default()),
            options,
        }
    }

    /// Starts a session on `fd`, which is owned from this call on. Returns the
    /// positive handle (the generation) or a negative `START_*` code.
    pub fn start(&self, fd: OwnedFd, config: &[u8], events: Box<dyn Events>) -> i64 {
        let mut config = match parse_config(config) {
            Ok(config) => config,
            Err(_) => return START_INVALID_CONFIG,
        };
        if let Some(dir) = &config.identity_dir {
            // Loaded before the session starts; a missing identity is never generated
            // here, while a head unit may already be waiting.
            match Identity::load(dir) {
                Ok(identity) => {
                    config.session.tls = Some(ServerConfig {
                        profile: config.tls_profile,
                        identity: Arc::new(identity),
                        peer: config.peer.clone(),
                    })
                }
                Err(_) => return START_IDENTITY_UNAVAILABLE,
            }
        }
        let mut inner = lock(&self.inner);
        if inner.current.is_some() {
            return START_BUSY;
        }
        if inner.previous.as_ref().is_some_and(Stop::io_threads_alive) {
            return START_STALE_IO;
        }
        if config.generation <= inner.last_generation {
            return START_INVALID_CONFIG;
        }
        let generation = config.generation;
        let stop = Stop::new();
        let media = Arc::new(MediaLink::default());
        config.session.media = Some(media.clone());
        // Encoder submissions wake the session thread's blocking wait.
        let waker = stop.clone();
        media.set_waker(Arc::new(move || waker.wake()));
        let audio = Arc::new(MediaLink::audio(DEFAULT_AUDIO_LIMIT));
        config.session.audio = Some(audio.clone());
        let waker = stop.clone();
        audio.set_waker(Arc::new(move || waker.wake()));
        let now_playing = Arc::new(NowPlayingLink::default());
        config.session.now_playing = Some(now_playing.clone());
        // A track change reaches the head unit without waiting for the next timer.
        let waker = stop.clone();
        now_playing.set_waker(Arc::new(move || waker.wake()));
        let guidance = Arc::new(GuidanceLink::default());
        config.session.guidance = Some(guidance.clone());
        // A maneuver change reaches the cluster as soon as the interval allows, not at
        // the next restatement.
        let waker = stop.clone();
        guidance.set_waker(Arc::new(move || waker.wake()));
        let report = Arc::new(Mutex::new(Vec::new()));
        let worker = Worker {
            config,
            options: self.options.clone(),
            stop: stop.clone(),
            report: report.clone(),
            events,
        };
        let spawned = thread::Builder::new()
            .name("aap-session".to_owned())
            .spawn(move || worker.run(fd));
        match spawned {
            Err(_) => START_SPAWN_FAILED,
            Ok(handle) => {
                inner.last_generation = generation;
                inner.current = Some(Entry {
                    generation,
                    stop,
                    media,
                    audio,
                    now_playing,
                    guidance,
                    worker_thread: handle.thread().id(),
                    worker: Some(handle),
                    report,
                });
                generation as i64
            }
        }
    }

    /// Requests cancellation, waits for the worker (which joins its I/O threads and
    /// delivers `Finished`), then releases the handle. Unknown handles are ignored.
    /// Called from the session's own callback, it only requests cancellation.
    pub fn stop(&self, handle: i64) {
        let worker = {
            let mut inner = lock(&self.inner);
            let Some(entry) = inner
                .current
                .as_mut()
                .filter(|e| e.generation as i64 == handle)
            else {
                return;
            };
            entry.stop.request();
            if thread::current().id() == entry.worker_thread {
                return;
            }
            entry.worker.take()
        };
        // A concurrent stop already joining leaves `worker` empty and returns; the entry
        // stays registered, so `start` remains busy until that join completes.
        let Some(worker) = worker else { return };
        let _ = worker.join();
        let mut inner = lock(&self.inner);
        if inner
            .current
            .as_ref()
            .is_some_and(|e| e.generation as i64 == handle)
        {
            inner.previous = inner.current.take().map(|e| e.stop);
        }
    }

    /// Encoder thread: hands one encoder output buffer to the session identified by
    /// `handle`. The bytes are copied before this returns. Returns a `Submit` value or
    /// `SUBMIT_UNKNOWN_HANDLE`.
    pub fn submit_video(
        &self,
        handle: i64,
        flags: BufferFlags,
        presentation_us: i64,
        bytes: &[u8],
    ) -> i32 {
        let Some(media) = self.media(handle) else {
            return SUBMIT_UNKNOWN_HANDLE;
        };
        media.submit(flags, presentation_us, bytes) as i32
    }

    /// Audio thread: hands one PCM buffer to the session identified by `handle` (P5.3).
    /// The bytes are copied before this returns. Returns a `Submit` value or
    /// `SUBMIT_UNKNOWN_HANDLE`.
    ///
    /// Submitting before the head unit granted audio focus returns
    /// `DroppedNotStreaming`, so an app that starts its source early loses buffers rather
    /// than having them queue up and arrive stale.
    pub fn submit_audio(&self, handle: i64, presentation_us: i64, bytes: &[u8]) -> i32 {
        let Some(audio) = self.audio(handle) else {
            return SUBMIT_UNKNOWN_HANDLE;
        };
        audio.submit(BufferFlags::default(), presentation_us, bytes) as i32
    }

    /// Audio thread: the audio source failed. Audio stops; the session does not end,
    /// because audio is an opt-in integration and the picture is not its hostage.
    pub fn audio_failed(&self, handle: i64, detail: &str) {
        if let Some(audio) = self.audio(handle) {
            audio.fail(detail);
        }
    }

    /// App thread: publish what is currently playing on the phone, which the session
    /// sends on the `media_info` channel when the head unit has opened it (P5.1).
    ///
    /// Nothing here plays anything. The app reports another application's session when
    /// the operator has granted it that access, and publishes a stopped, empty state
    /// otherwise; this source never invents a state it was not told. The caller has
    /// already bounded the strings through [`NowPlaying::new`].
    pub fn submit_now_playing(&self, handle: i64, now_playing: NowPlaying) -> i32 {
        let inner = lock(&self.inner);
        let Some(entry) = inner
            .current
            .as_ref()
            .filter(|e| e.generation as i64 == handle)
        else {
            return NOW_PLAYING_UNKNOWN_HANDLE;
        };
        if entry.now_playing.publish(now_playing) {
            NOW_PLAYING_CHANGED
        } else {
            NOW_PLAYING_UNCHANGED
        }
    }

    /// App thread: publish what the guidance loop says the cluster should show (P6.5),
    /// which the session sends on the `navigation` channel when it is route-driven and
    /// the cluster is held. Publishing to a session that plays the script, or has no
    /// navigation channel, is accepted and never read.
    pub fn submit_guidance(&self, handle: i64, state: RouteState) -> i32 {
        let inner = lock(&self.inner);
        let Some(entry) = inner
            .current
            .as_ref()
            .filter(|e| e.generation as i64 == handle)
        else {
            return NOW_PLAYING_UNKNOWN_HANDLE;
        };
        if entry.guidance.publish(state) {
            NOW_PLAYING_CHANGED
        } else {
            NOW_PLAYING_UNCHANGED
        }
    }

    /// Encoder thread: the encoder failed; the session ends with `ENCODER_FAILED`.
    pub fn video_failed(&self, handle: i64, detail: &str) {
        if let Some(media) = self.media(handle) {
            media.fail(detail);
        }
    }

    /// Requests a graceful stop: the stream is stopped and a shutdown request sent. The
    /// session still has to be released with [`Manager::stop`].
    pub fn request_shutdown(&self, handle: i64) {
        let inner = lock(&self.inner);
        if let Some(entry) = inner
            .current
            .as_ref()
            .filter(|e| e.generation as i64 == handle)
        {
            entry.stop.request_shutdown();
        }
    }

    fn media(&self, handle: i64) -> Option<Arc<MediaLink>> {
        let inner = lock(&self.inner);
        let entry = inner
            .current
            .as_ref()
            .filter(|e| e.generation as i64 == handle)?;
        Some(entry.media.clone())
    }

    fn audio(&self, handle: i64) -> Option<Arc<MediaLink>> {
        let inner = lock(&self.inner);
        let entry = inner
            .current
            .as_ref()
            .filter(|e| e.generation as i64 == handle)?;
        Some(entry.audio.clone())
    }

    /// Latest report snapshot for a started, not yet stopped session.
    pub fn report(&self, handle: i64) -> Option<Vec<u8>> {
        let inner = lock(&self.inner);
        let entry = inner
            .current
            .as_ref()
            .filter(|e| e.generation as i64 == handle)?;
        let report = lock(&entry.report).clone();
        Some(report)
    }
}

struct Worker {
    config: StartConfig,
    options: Options,
    stop: Stop,
    report: Arc<Mutex<Vec<u8>>>,
    events: Box<dyn Events>,
}

type Milestone = (Stage, Option<Stage>, bool, bool);

impl Worker {
    fn run(mut self, fd: OwnedFd) {
        let generation = self.config.generation;
        let mut failures = 0u32;
        let result = catch_unwind(AssertUnwindSafe(|| {
            let mut session = Session::new(self.config.session.clone(), generation, Instant::now());
            let mut last: Option<Milestone> = None;
            let teardown = accessory::run(fd, &mut session, &self.stop, &self.options, |s| {
                for event in s.take_app_events() {
                    let (kind, payload) = event_payload(&event);
                    if !self.events.event(generation, kind, payload.as_bytes()) {
                        failures += 1;
                    }
                }
                let r = s.report();
                let text = render(&self.config, r, None, failures);
                lock(&self.report).clone_from(&text);
                let milestone = (
                    r.stage,
                    r.last_completed,
                    r.response.is_some(),
                    r.outcome.is_some(),
                );
                if last != Some(milestone) {
                    last = Some(milestone);
                    if !self.events.event(generation, EventKind::Progress, &text) {
                        failures += 1;
                    }
                }
            });
            render(&self.config, session.report(), Some(&teardown), failures)
        }));
        let text = result.unwrap_or_else(|_| {
            // Unexpected: I/O threads may still be running, which `STALE_IO` reports.
            self.stop.request();
            format!(
                "session_uuid={}\ngeneration={generation}\nfinal_category=INTERNAL_ERROR\n\
                 final_detail=native session worker panicked\n",
                self.config.session_uuid
            )
            .into_bytes()
        });
        lock(&self.report).clone_from(&text);
        self.events.event(generation, EventKind::Finished, &text);
    }
}

fn render(
    config: &StartConfig,
    report: &Report,
    teardown: Option<&Teardown>,
    callback_failures: u32,
) -> Vec<u8> {
    let mut text = String::new();
    let _ = writeln!(text, "session_uuid={}", config.session_uuid);
    let _ = writeln!(text, "transport_owner=native");
    let _ = write!(text, "{report}");
    let _ = writeln!(text, "callback_failures={callback_failures}");
    match teardown {
        Some(teardown) => {
            let _ = write!(text, "{teardown}");
        }
        None => {
            let _ = writeln!(text, "io=running");
        }
    }
    text.into_bytes()
}
