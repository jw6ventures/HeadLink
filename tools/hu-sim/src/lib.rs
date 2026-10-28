//! Test-only head-unit simulator: the receiver side of the version request, a TLS 1.2
//! client (plain OpenSSL, independent of `aap-tls`), AUTH_COMPLETE, the service
//! discovery response, ping, shutdown, and the projection services (channel opens, AV
//! setup, video focus, media acknowledgements, input binding and events, sensors, and the
//! `media_info` now-playing channel, and the `navigation` instrument cluster), with
//! switchable misbehavior. It shares only framing and generated message types with the
//! source, so it is a convenience peer for deterministic tests; the OpenAuto Prodigy lab
//! receiver is the independent check.
use aap_proto::{
    data::{
        AudioConfig, AvChannel, BluetoothChannel, ButtonEvent, ButtonEvents, ChannelDescriptor,
        DrivingStatus, GpsLocation, GpsSatelliteData, GpsSatelliteInfo, HeadUnitInfo,
        InputChannelConfig, MediaInfoChannel, NavigationChannel, NavigationImageOptions, NightMode,
        ParkingBrake, SensorChannel, SensorTypeEntry, Speed, TouchEvent, TouchLocation,
        TouchScreenConfig, TrailerData, VideoConfig,
    },
    encode_message, ids,
    messages::{
        AudioFocusRequest, AudioFocusResponse, AuthCompleteIndication, AvChannelSetupResponse,
        AvChannelStartIndication, AvMediaAckIndication, BindingResponse, ByeByeResponse,
        ChannelOpenRequest, ChannelOpenResponse, InputEventIndication, InstrumentClusterStart,
        InstrumentClusterStop, MediaPlaybackMetadata, MediaPlaybackStatus,
        MediaPlaybackStatusEvent, NavigationFocusRequest, NavigationFocusResponse,
        NavigationNextTurnDistanceEvent, NavigationNotification, NavigationState, PingRequest,
        PingResponse, SensorEventIndication, SensorRequest, SensorStartResponseMessage,
        ServiceDiscoveryResponse, ShutdownRequest, VideoFocusIndication, VideoFocusRequest,
    },
};
use aap_wire::{fragment, Assembler, Frame, Header, Parser};
use openssl::{
    asn1::Asn1Time,
    bn::BigNum,
    hash::MessageDigest,
    nid::Nid,
    pkey::PKey,
    rsa::Rsa,
    ssl::{ErrorCode, Ssl, SslContext, SslMethod, SslStream, SslVerifyMode, SslVersion},
    x509::{X509NameBuilder, X509},
};
use prost::Message as _;
use std::{
    io::{self, Read, Write},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

/// Runs `hu` over a blocking stream whose reads time out (configured by the caller)
/// until the source closes the stream or `limit` elapses, then returns it for
/// inspection. Sends the version request first.
pub fn serve<S: Read + Write>(mut stream: S, mut hu: HeadUnit, limit: Duration) -> HeadUnit {
    hu.start();
    let deadline = Instant::now() + limit;
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        let output = hu.take_output();
        if !output.is_empty() && stream.write_all(&output).is_err() {
            break;
        }
        if Instant::now() >= deadline {
            hu.errors.push("serve time limit reached".into());
            break;
        }
        match stream.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => hu.receive(&buffer[..n]),
            Err(e)
                if matches!(
                    e.kind(),
                    io::ErrorKind::WouldBlock
                        | io::ErrorKind::TimedOut
                        | io::ErrorKind::Interrupted
                ) => {}
            Err(_) => break,
        }
    }
    hu
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Auth {
    /// Finish TLS, then report the source certificate verification result at AA level.
    VerifySource,
    /// Plaintext AUTH_COMPLETE with this status after the handshake (baseline).
    Plain(i32),
    /// Encrypted AUTH_COMPLETE with this status.
    Encrypted(i32),
    /// Plaintext AUTH_COMPLETE with no status field.
    MissingStatus,
    /// Plaintext status 0 sent together with the ClientHello, before TLS.
    Premature,
    /// Never sent.
    Never,
}

#[derive(Debug, Clone)]
pub struct Config {
    /// Test-only trust anchor for source authentication; no system trust store is loaded.
    pub source_root: Option<X509>,
    pub request_version: (u16, u16),
    pub client_certificate: bool,
    pub max_tls: Option<SslVersion>,
    pub cipher_list: Option<String>,
    pub auth: Auth,
    /// Protobuf bytes of the discovery response; `None` never answers.
    pub discovery_response: Option<Vec<u8>>,
    pub discovery_plaintext: bool,
    /// Frame each encrypted message's ciphertext in chunks of this many bytes, so TLS
    /// records span frames.
    pub split_ciphertext: Option<usize>,
    pub answer_pings: bool,
    /// Sends one ping request with the bug-report flag after discovery.
    pub ping_after_discovery: bool,
    /// Models Ford SYNC 4 (reports c1265b54, c2407cff): its own ping is encrypted, pings
    /// are answered encrypted, and plaintext pings go unanswered. Otherwise pings are
    /// sent and answered plaintext.
    pub encrypted_pings: bool,
    pub answer_shutdown: bool,
    /// Sends an encrypted shutdown request with this reason after discovery.
    pub shutdown_after_discovery: Option<i32>,
    /// Encrypted messages (channel, message bytes) sent right after AUTH_COMPLETE.
    pub extra_after_auth: Vec<(u8, Vec<u8>)>,
    pub projection: Projection,
}

/// How the simulator answers a channel open request.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Open {
    /// Status OK on the target channel with the 0x04 bit (baseline).
    Accept,
    Reject(i32),
    Silent,
    /// Status OK on channel 0 with the 0x04 bit.
    OnControl,
    /// Status OK on the target channel without the 0x04 bit.
    WithoutCategory,
    /// Status OK on another channel, then nothing.
    WrongChannel(u8),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Focus {
    /// Answers a focus request with the requested mode (Prodigy).
    Echo,
    /// Sends PROJECTED right after the setup response and echoes requests (Prodigy's
    /// setup path).
    UnsolicitedThenEcho,
    /// Answers every request with NATIVE.
    Native,
    /// Sends PROJECTED right after the setup response and then ignores every request.
    ///
    /// This is the head unit the operator described on 2026-09-19: projection starts on an
    /// unsolicited grant, the head unit takes the screen for its own UI, and asking for it
    /// back changes nothing. Whether Ford SYNC 4.0 actually behaves this way is the open
    /// question — the mode exists so the re-request path has a receiver that does not
    /// answer, not as a claim about the vehicle.
    UnsolicitedThenSilent,
    Silent,
}

/// How the simulator answers `AUDIO_FOCUS_REQUEST` (0x0012) on the control channel
/// (P5.3).
///
/// Field 2 of `AudioFocusResponse` is the **`unsolicited`** flag, not `granted` — the
/// vendored schema's name for it is wrong (references.md). A reply to a request therefore
/// carries false, and only a state the head unit volunteers carries true. This enum models
/// that, and deliberately also models the shapes that would catch a source reading the
/// field as a grant, because the earlier simulator shared the misreading with the source
/// and so agreed with the bug.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AudioFocus {
    /// `GAIN` in reply to the request: field 2 false. What Ford SYNC 4.0 sends (reports
    /// 5cb646ec and 9eb3a396), and the baseline.
    Grant,
    /// `GAIN` with field 2 absent, which aasdk's copy of the message and the pinned
    /// Prodigy receiver both produce.
    GrantWithoutFlag,
    /// `GAIN` marked unsolicited, which a reply should never be. A source must still treat
    /// it as a grant.
    GrantMarkedUnsolicited,
    /// Answers every request with this `AudioFocusState`, field 2 false.
    State(i32),
    /// Grant, then volunteer a `LOSS_TRANSIENT_CAN_DUCK` after this many audio buffers.
    /// The source must keep sending at full amplitude.
    GrantThenDuck(u32),
    /// Grant, then volunteer a `LOSS` after this many audio buffers.
    GrantThenLoss(u32),
    Silent,
}

/// When the simulated head unit announces its instrument cluster (P6.2).
///
/// `InstrumentClusterStart` is the head unit saying the cluster display exists. A source
/// that assumed it rather than waiting for it would pass against a simulator that always
/// sent one, so the silent and the late cases are both here.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Cluster {
    /// Immediately after accepting the channel open (baseline).
    OnOpen,
    /// After this many media units, so a source has to cope with a cluster that appears
    /// mid-projection rather than during setup.
    AfterMedia(u32),
    /// Start on open, then `InstrumentClusterStop` after this many navigation messages.
    StopAfter(u32),
    /// Never. The channel opens and the cluster never announces itself — which is what a
    /// head unit that advertises the service and has no cluster behind it looks like.
    Silent,
}

/// How the simulator answers `NAVIGATION_FOCUS_REQUEST` (0x000d) on the control channel
/// (P6.2).
///
/// `NavigationFocusType` has two values and no refusal: `NAV_FOCUS_PROJECTED` hands the
/// cluster over and `NAV_FOCUS_NATIVE` keeps it. Neither is decoded from the source's own
/// reading of the request — the simulator answers what it is configured to answer, so a
/// source that asked for the wrong thing still gets a grant and is caught by the request
/// this head unit recorded rather than by agreeing with it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NavFocus {
    /// `NAV_FOCUS_PROJECTED` (baseline).
    Projected,
    /// `NAV_FOCUS_NATIVE`: the head unit keeps its own navigation on the cluster.
    Native,
    /// A response with no type field at all, which proto2 allows and a source must not
    /// read as a grant.
    Empty,
    /// `NAV_FOCUS_NATIVE`, then `NAV_FOCUS_PROJECTED` volunteered after this many media
    /// units: a head unit that hands the cluster over late.
    NativeThenProjected(u32),
    Silent,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Ack {
    /// One acknowledgement per timestamped frame, `ack_count` 1; no config ack.
    EachUnit,
    /// One acknowledgement per timestamped frame using `receive_timestamps`.
    Timestamps,
    /// No acknowledgements until [`HeadUnit::release_acks`].
    Withhold,
}

#[derive(Debug, Clone)]
pub struct Projection {
    /// Per-channel open behavior; channels not listed are accepted if advertised and
    /// rejected with INVALID_CHANNEL otherwise.
    pub opens: Vec<(u8, Open)>,
    pub answer_setup: bool,
    pub setup_status: i32,
    pub max_unacked: Option<u32>,
    /// Accepted config indices; `None` lists every advertised index.
    pub setup_configs: Option<Vec<u32>>,
    pub focus: Focus,
    pub ack: Ack,
    /// Added to the session ID in acknowledgements.
    pub ack_session_offset: i32,
    pub answer_binding: bool,
    pub binding_status: Option<i32>,
    /// P5.3 audio: whether the audio channel answers its AV setup, with what status and
    /// what window. Audio windows are typically smaller than video ones, so the default
    /// here is 1 — the hard case, where every buffer must be acknowledged before the next.
    pub answer_audio_setup: bool,
    pub audio_setup_status: i32,
    pub audio_max_unacked: Option<u32>,
    pub audio_focus: AudioFocus,
    /// Send `0x800B AUDIO_UNDERFLOW` on the audio channel after this many buffers. No
    /// open-source head unit sends it, so without this switch the path is never tested.
    /// The payload is the given bytes: modern Android Auto parses none, older schemas
    /// define `required int32 session_id = 1`.
    pub underflow_after_audio: Option<(u32, Vec<u8>)>,
    pub sensor_status: i32,
    /// Driving status sent after each sensor start response for DRIVING_STATUS.
    pub driving_status: i32,
    /// Coordinates sent for LOCATION, in the schema's 1e7-degree units. A report must
    /// never contain these unless `sensor_location` asked for them (spec §10).
    pub latitude: i32,
    pub longitude: i32,
    /// Also push a value for this type after the first accepted subscription, without
    /// having been asked for it.
    pub volunteer_sensor: Option<i32>,
    /// After this many media units, tap (x, y) in touch coordinates once.
    pub tap_after_media: Option<(u32, u32, u32)>,
    /// After this many media units, push an UPDATE_UI_CONFIG_REQUEST carrying these
    /// display insets as top, bottom, left, right, as Ford SYNC 4.0 does a second or two
    /// into projection. Raw bytes, since no schema for the message is published.
    pub ui_config_after_media: Option<(u32, [u32; 4])>,
    /// Drive away rather than sitting at [`Self::latitude`] forever (P6.1).
    pub track: Option<Track>,
    /// P6.2: when the instrument cluster announces itself, and how navigation focus is
    /// answered.
    pub cluster: Cluster,
    pub nav_focus: NavFocus,
}

impl Default for Projection {
    fn default() -> Self {
        Self {
            opens: Vec::new(),
            answer_setup: true,
            setup_status: 2,
            max_unacked: Some(4),
            setup_configs: None,
            focus: Focus::Echo,
            ack: Ack::EachUnit,
            ack_session_offset: 0,
            answer_binding: true,
            binding_status: Some(0),
            answer_audio_setup: true,
            audio_setup_status: 2,
            audio_max_unacked: Some(1),
            audio_focus: AudioFocus::Grant,
            underflow_after_audio: None,
            sensor_status: 0,
            driving_status: 0,
            latitude: 424_242_424,
            longitude: -712_121_212,
            volunteer_sensor: None,
            tap_after_media: None,
            ui_config_after_media: None,
            track: None,
            cluster: Cluster::OnOpen,
            nav_focus: NavFocus::Projected,
        }
    }
}

/// A vehicle driving east, stepped by media units (P6.1).
///
/// The moving position source that matters is the lab receiver's, which plays a
/// realistic drive with corners into a real projection; this is the host-side half, and
/// all it has to produce is the thing the simulator never has: a *sequence* of distinct
/// positions arriving during projection, some of them carrying no position at all.
///
/// It has no clock, so the arithmetic runs the other way round: longitude advances by
/// exactly the distance `speed_mm_s` covers in `interval_ms`, and the bearing is due
/// east because east is where the position is going. A fix whose speed and heading
/// disagreed with its own movement would let a source that followed the wrong one of
/// the three look correct.
#[derive(Debug, Clone, Copy)]
pub struct Track {
    /// Push a position every this many media units.
    pub every_media: u32,
    /// Millimetres per second, the scale the lab receiver sends.
    pub speed_mm_s: i32,
    /// Milliseconds the vehicle is taken to have travelled between two fixes.
    pub interval_ms: u32,
}

/// One media message received on the video channel.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Media {
    pub id: u16,
    pub timestamp_us: Option<u64>,
    pub bytes: Vec<u8>,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            source_root: None,
            request_version: (1, 1),
            client_certificate: true,
            max_tls: None,
            cipher_list: None,
            auth: Auth::Plain(0),
            discovery_response: Some(discovery_response()),
            discovery_plaintext: false,
            split_ciphertext: None,
            answer_pings: true,
            ping_after_discovery: false,
            encrypted_pings: false,
            answer_shutdown: true,
            shutdown_after_discovery: None,
            extra_after_auth: Vec::new(),
            projection: Projection::default(),
        }
    }
}

/// Sensor types the simulated head unit advertises: exactly the nine Ford SYNC 4.0
/// advertises on its channel 8 (report 617af5ce), in that order, so a P5.2 harvest test
/// asks the same questions the vehicle will answer. DRIVING_STATUS and NIGHT_DATA are
/// not first here, which is the point — [`aap_session::projection::SensorSet`] chooses
/// types, advertisement order does not.
pub const ADVERTISED_SENSORS: [i32; 9] = [1, 3, 6, 7, 8, 10, 13, 21, 24];

/// Advertised types the simulator has no value for, so a test can tell "advertised,
/// accepted, and then silent" from "advertised and populated". FUEL_LEVEL and GEAR are
/// the silent pair.
pub const SILENT_SENSORS: [i32; 2] = [6, 8];

/// A plausible head unit: H.264 video on channel 3, touch input on 1 for the same
/// display, plus sensor (Ford's nine advertised types), two PCM audio channels (SPEECH on
/// 4 and MEDIA on 5), `media_info` on 10, and Bluetooth services with unit-specific
/// strings that reports must redact.
///
/// The input channel advertises the transport keycodes Ford SYNC 4.0 binds
/// (`KEYCODE_MEDIA_PLAY_PAUSE` 85, `KEYCODE_MEDIA_NEXT` 87, `KEYCODE_MEDIA_PREVIOUS` 88)
/// alongside ordinary ones, so a test can check that only the transport codes become
/// transport actions.
pub fn discovery_response() -> Vec<u8> {
    let video = |resolution, fps| VideoConfig {
        video_resolution: Some(resolution),
        video_fps: Some(fps),
        margin_width: Some(0),
        margin_height: Some(0),
        dpi: Some(160),
        ..Default::default()
    };
    let response = ServiceDiscoveryResponse {
        channels: vec![
            ChannelDescriptor {
                channel_id: 3,
                av_channel: Some(AvChannel {
                    stream_type: Some(3),
                    video_configs: vec![video(2, 2), video(1, 2)],
                    display_id: Some(0),
                    ..Default::default()
                }),
                ..Default::default()
            },
            ChannelDescriptor {
                channel_id: 1,
                input_channel: Some(InputChannelConfig {
                    supported_keycodes: vec![3, 4, 84, 85, 87, 88],
                    touch_screen_configs: vec![TouchScreenConfig {
                        width: 800,
                        height: 480,
                        display_type: None,
                    }],
                    ..Default::default()
                }),
                ..Default::default()
            },
            ChannelDescriptor {
                channel_id: 2,
                sensor_channel: Some(SensorChannel {
                    sensors: ADVERTISED_SENSORS
                        .into_iter()
                        .map(|sensor_type| SensorTypeEntry { sensor_type })
                        .collect(),
                    ..Default::default()
                }),
                ..Default::default()
            },
            // Two audio channels, as Ford advertises four: PCM SPEECH first, so a test
            // proves the source takes MEDIA rather than the lowest-numbered audio
            // channel, and PCM MEDIA at the conventional 48000/16/2 second.
            ChannelDescriptor {
                channel_id: 4,
                av_channel: Some(AvChannel {
                    stream_type: Some(1),
                    audio_type: Some(1),
                    audio_configs: vec![AudioConfig {
                        sample_rate: 16_000,
                        bit_depth: 16,
                        channel_count: 1,
                    }],
                    ..Default::default()
                }),
                ..Default::default()
            },
            ChannelDescriptor {
                channel_id: 5,
                av_channel: Some(AvChannel {
                    stream_type: Some(1),
                    audio_type: Some(3),
                    audio_configs: vec![AudioConfig {
                        sample_rate: 48_000,
                        bit_depth: 16,
                        channel_count: 2,
                    }],
                    ..Default::default()
                }),
                ..Default::default()
            },
            // Now-playing, on the channel the pinned Prodigy receiver hardcodes for it
            // (`ChannelId::MediaStatus`) and the one Ford SYNC 4.0 advertises it on.
            // `MediaInfoChannel` is an empty message, so this is just a marker.
            ChannelDescriptor {
                channel_id: 10,
                media_info_channel: Some(MediaInfoChannel {}),
                ..Default::default()
            },
            // Turn-by-turn to the head unit's own cluster, on the channel Ford SYNC 4.0
            // advertises it on. The values are deliberately not Ford's: it advertises no
            // image options, so the simulator carries some to cover the branch the one
            // vehicle fixture cannot, and a 300 ms floor that a source hardcoding 400
            // would fail against. The dimensions are unequal for the same reason — a
            // square pair would hide the tag-order question the schema leaves open.
            ChannelDescriptor {
                channel_id: 11,
                navigation_channel: Some(NavigationChannel {
                    minimum_interval_ms: 300,
                    r#type: 1,
                    image_options: Some(NavigationImageOptions {
                        height: 64,
                        width: 96,
                        colour_depth_bits: 16,
                    }),
                }),
                ..Default::default()
            },
            ChannelDescriptor {
                channel_id: 8,
                bluetooth_channel: Some(BluetoothChannel {
                    adapter_address: "00:11:22:33:44:55".into(),
                    supported_pairing_methods: vec![4],
                }),
                ..Default::default()
            },
        ],
        head_unit_name: Some("Owner Truck 5813".into()),
        car_model: Some("Lab Model".into()),
        car_year: Some("2023".into()),
        car_serial: Some("VINLIKE123456789".into()),
        driver_position: Some(0),
        headunit_manufacturer: Some("Lab Maker".into()),
        headunit_model: Some("Sim HU".into()),
        sw_build: Some("build-7".into()),
        sw_version: Some("1.2.3".into()),
        headunit_info: Some(HeadUnitInfo {
            make: Some("Lab".into()),
            vehicle_id: Some("VEHICLE-ID-777".into()),
            ..Default::default()
        }),
        ..Default::default()
    };
    response.encode_to_vec()
}

#[derive(Default)]
struct Pipe {
    inbound: Vec<u8>,
    outbound: Vec<u8>,
}
impl Read for Pipe {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.inbound.is_empty() {
            return Err(io::ErrorKind::WouldBlock.into());
        }
        let n = buf.len().min(self.inbound.len());
        buf[..n].copy_from_slice(&self.inbound[..n]);
        self.inbound.drain(..n);
        Ok(n)
    }
}
impl Write for Pipe {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.outbound.extend_from_slice(buf);
        Ok(buf.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// One message the head unit received: channel, frame flags (without the frame kind),
/// message ID, whether it was encrypted, and the complete plaintext bytes including the ID.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Received {
    pub channel: u8,
    pub flags: u8,
    pub id: u16,
    pub encrypted: bool,
    pub bytes: Vec<u8>,
}

/// Service channels found in this head unit's own discovery response.
#[derive(Debug, Clone, Default)]
struct Services {
    video: Option<u8>,
    video_configs: u32,
    input: Option<u8>,
    sensor: Option<u8>,
    media_info: Option<u8>,
    /// The instrument cluster channel (P6.2).
    navigation: Option<u8>,
    /// The PCM `MEDIA` audio channel, which is the one the source drives (P5.3).
    audio: Option<u8>,
    /// Every channel in the response, whatever its service: a real head unit accepts an
    /// open on any of them, which a source imitating Android Auto's session shape does.
    all: Vec<u8>,
}

pub struct HeadUnit {
    source_verified: Arc<Mutex<bool>>,
    config: Config,
    parser: Parser,
    assembler: Assembler,
    ssl: SslStream<Pipe>,
    tls_started: bool,
    tls_done: bool,
    output: Vec<u8>,
    /// SHA-256 of this head unit's client certificate.
    pub fingerprint: [u8; 32],
    /// SHA-256 of the source certificate seen during the handshake.
    pub source_fingerprint: Arc<Mutex<Option<[u8; 32]>>>,
    pub received: Vec<Received>,
    /// UPDATE_UI_CONFIG_REPLYs the source sent back, echoing the request.
    pub ui_config_responses: u32,
    /// Body of the latest UPDATE_UI_CONFIG_REQUEST sent, for comparing the echo.
    ui_config_sent: Option<Vec<u8>>,
    /// Protocol problems the simulator hit; tests assert on them.
    pub errors: Vec<String>,
    services: Services,
    /// Channels the source opened and this head unit accepted.
    pub opened: Vec<u8>,
    /// Session ID from the latest start indication.
    pub video_session: Option<i32>,
    pub media: Vec<Media>,
    /// P5.3: the audio session, the PCM buffers received on the audio channel, the focus
    /// types asked for, and whether a stop indication arrived.
    pub audio_session: Option<i32>,
    pub audio_config: Option<u32>,
    pub audio: Vec<Media>,
    pub audio_focus_requests: Vec<i32>,
    pub audio_stopped: bool,
    withheld_acks: u32,
    pub sensor_requests: Vec<SensorRequest>,
    /// P6.1: where the track has driven to, in 1e7 degrees, and how many positions and
    /// position-less speed events it has pushed.
    track_longitude: i32,
    pub track_fixes: u32,
    pub track_speed_events: u32,
    /// P5.1: what the source published on the `media_info` channel, in order.
    pub playback_status: Vec<MediaPlaybackStatus>,
    pub playback_metadata: Vec<MediaPlaybackMetadata>,
    /// P6.2: what the source put on the `navigation` channel, in order, and the focus
    /// types it asked for on the control channel.
    pub nav_states: Vec<i32>,
    pub nav_notifications: Vec<NavigationNotification>,
    pub nav_distances: Vec<NavigationNextTurnDistanceEvent>,
    pub nav_focus_requests: Vec<i32>,
    /// Navigation messages counted for the [`Cluster::StopAfter`] trigger.
    nav_messages: u32,
    pub cluster_started: bool,
}

impl HeadUnit {
    pub fn new(config: Config) -> Self {
        let key = PKey::from_rsa(Rsa::generate(2048).unwrap()).unwrap();
        let mut name = X509NameBuilder::new().unwrap();
        name.append_entry_by_nid(Nid::COMMONNAME, "hu-sim unit 31337")
            .unwrap();
        name.append_entry_by_nid(Nid::ORGANIZATIONNAME, "hu-sim")
            .unwrap();
        let name = name.build();
        let mut cert = X509::builder().unwrap();
        cert.set_version(2).unwrap();
        let serial = BigNum::from_u32(1).unwrap().to_asn1_integer().unwrap();
        cert.set_serial_number(&serial).unwrap();
        cert.set_subject_name(&name).unwrap();
        cert.set_issuer_name(&name).unwrap();
        cert.set_not_before(&Asn1Time::days_from_now(0).unwrap())
            .unwrap();
        cert.set_not_after(&Asn1Time::days_from_now(7).unwrap())
            .unwrap();
        cert.set_pubkey(&key).unwrap();
        cert.sign(&key, MessageDigest::sha256()).unwrap();
        let cert = cert.build();
        let mut fingerprint = [0u8; 32];
        fingerprint.copy_from_slice(&cert.digest(MessageDigest::sha256()).unwrap());

        let source_fingerprint = Arc::new(Mutex::new(None));
        let source_verified = Arc::new(Mutex::new(true));
        let verified = source_verified.clone();
        let seen = source_fingerprint.clone();
        let mut builder = SslContext::builder(SslMethod::tls_client()).unwrap();
        if let Some(root) = &config.source_root {
            builder.cert_store_mut().add_cert(root.clone()).unwrap();
        }
        builder.set_verify_callback(SslVerifyMode::PEER, move |preverified, context| {
            if !preverified {
                *verified.lock().unwrap() = false;
            }
            if context.error_depth() == 0 {
                if let Some(cert) = context.current_cert() {
                    let mut out = [0u8; 32];
                    out.copy_from_slice(&cert.digest(MessageDigest::sha256()).unwrap());
                    *seen.lock().unwrap() = Some(out);
                }
            }
            true
        });
        if config.client_certificate {
            builder.set_certificate(&cert).unwrap();
            builder.set_private_key(&key).unwrap();
        }
        if let Some(max) = config.max_tls {
            builder.set_max_proto_version(Some(max)).unwrap();
        }
        if let Some(list) = &config.cipher_list {
            builder.set_cipher_list(list).unwrap();
        }
        let mut ssl = Ssl::new(&builder.build()).unwrap();
        ssl.set_connect_state();
        let services = config
            .discovery_response
            .as_deref()
            .and_then(|bytes| ServiceDiscoveryResponse::decode(bytes).ok())
            .map_or(Services::default(), |response| {
                let mut services = Services::default();
                for c in &response.channels {
                    let id = c.channel_id as u8;
                    services.all.push(id);
                    if let Some(av) = c
                        .av_channel
                        .as_ref()
                        .filter(|a| !a.video_configs.is_empty())
                    {
                        services.video.get_or_insert(id);
                        services.video_configs = av.video_configs.len() as u32;
                    }
                    // The same rule the source applies: PCM carrying MEDIA.
                    if c.av_channel.as_ref().is_some_and(|a| {
                        a.stream_type == Some(1)
                            && a.audio_type == Some(3)
                            && !a.audio_configs.is_empty()
                    }) {
                        services.audio.get_or_insert(id);
                    }
                    if c.input_channel.is_some() {
                        services.input.get_or_insert(id);
                    }
                    if c.sensor_channel.is_some() {
                        services.sensor.get_or_insert(id);
                    }
                    if c.media_info_channel.is_some() {
                        services.media_info.get_or_insert(id);
                    }
                    if c.navigation_channel.is_some() {
                        services.navigation.get_or_insert(id);
                    }
                }
                services
            });
        // The built-in response is generated, not decoded, so list its channels too.
        let services = if services.all.is_empty() {
            let mut services = services;
            services.all = vec![1, 2, 3, 4, 5, 8, 10, 11];
            services
        } else {
            services
        };
        Self {
            source_verified,
            config,
            parser: Parser::new(),
            assembler: Assembler::default(),
            ssl: SslStream::new(ssl, Pipe::default()).unwrap(),
            tls_started: false,
            tls_done: false,
            output: Vec::new(),
            fingerprint,
            source_fingerprint,
            received: Vec::new(),
            ui_config_responses: 0,
            ui_config_sent: None,
            errors: Vec::new(),
            services,
            opened: Vec::new(),
            video_session: None,
            media: Vec::new(),
            audio_session: None,
            audio_config: None,
            audio: Vec::new(),
            audio_focus_requests: Vec::new(),
            audio_stopped: false,
            withheld_acks: 0,
            sensor_requests: Vec::new(),
            track_longitude: 0,
            track_fixes: 0,
            track_speed_events: 0,
            playback_status: Vec::new(),
            playback_metadata: Vec::new(),
            nav_states: Vec::new(),
            nav_notifications: Vec::new(),
            nav_distances: Vec::new(),
            nav_focus_requests: Vec::new(),
            nav_messages: 0,
            cluster_started: false,
        }
    }

    /// Transport channel of the advertised video, input, and sensor services.
    pub fn service_channels(&self) -> (Option<u8>, Option<u8>, Option<u8>) {
        (
            self.services.video,
            self.services.input,
            self.services.sensor,
        )
    }

    /// Transport channel of the advertised `media_info` service, if any.
    pub fn media_info_channel(&self) -> Option<u8> {
        self.services.media_info
    }

    /// Transport channel of the advertised PCM `MEDIA` audio service, if any (P5.3).
    pub fn audio_channel(&self) -> Option<u8> {
        self.services.audio
    }

    /// Transport channel of the advertised `navigation` service, if any (P6.2).
    pub fn navigation_channel(&self) -> Option<u8> {
        self.services.navigation
    }

    /// `InstrumentClusterStart` or `InstrumentClusterStop` on the navigation channel.
    pub fn send_cluster(&mut self, start: bool) {
        let Some(channel) = self.services.navigation else {
            return;
        };
        if !self.opened.contains(&channel) {
            return;
        }
        self.cluster_started = start;
        let message = match start {
            true => encode_message(ids::navigation::CLUSTER_START, &InstrumentClusterStart {}),
            false => encode_message(ids::navigation::CLUSTER_STOP, &InstrumentClusterStop {}),
        };
        self.send_encrypted(channel, message);
    }

    /// One `NavigationFocusResponse` on the control channel, with the given type or with
    /// none at all.
    pub fn send_nav_focus(&mut self, focus_type: Option<i32>) {
        self.send_encrypted(
            0,
            encode_message(
                ids::NAVIGATION_FOCUS_RESPONSE,
                &NavigationFocusResponse { r#type: focus_type },
            ),
        );
    }

    /// One step of the track (P6.1): a new position, and every fifth time a speed with
    /// no position beside it.
    ///
    /// The five-to-one ratio is the vehicle's own: the G6/G7 runs recorded 208 LOCATION
    /// events against 42 CAR_SPEED in one session. The position-less message is the
    /// interesting one — a source that treated each indication as the whole vehicle
    /// state would blank the fix it already had every time speed arrived alone, and a
    /// simulator that always sent the two together could never show it.
    fn play_track_fix(&mut self, track: Track, p: &Projection) {
        let Some(channel) = self.services.sensor else {
            return;
        };
        if !self.sensor_requests.iter().any(|r| r.sensor_type == 1) {
            return; // nothing subscribed to a position
        }
        if self.track_fixes == 0 {
            self.track_longitude = p.longitude;
        }
        // Metres between two fixes, as degrees of longitude at this latitude. Flat
        // rather than spherical: a few hundred metres of straight line, and the point is
        // that the number agrees with the speed beside it.
        let metres = f64::from(track.speed_mm_s) / 1000.0 * f64::from(track.interval_ms) / 1000.0;
        let latitude = f64::from(p.latitude) / 1e7;
        let degrees = metres / (111_320.0 * latitude.to_radians().cos());
        self.track_longitude += (degrees * 1e7).round() as i32;
        self.track_fixes += 1;
        let event = SensorEventIndication {
            gps_location: vec![GpsLocation {
                latitude: p.latitude,
                longitude: self.track_longitude,
                accuracy: Some(8),
                altitude: Some(213),
                speed: Some(track.speed_mm_s),
                // Thousandths of a degree, and due east because that is the way the
                // longitude above is moving.
                bearing: Some(90_000),
            }],
            ..Default::default()
        };
        self.send_encrypted(
            channel,
            encode_message(ids::sensor::EVENT_INDICATION, &event),
        );
        if self.track_fixes % 5 != 0 {
            return;
        }
        if !self.sensor_requests.iter().any(|r| r.sensor_type == 3) {
            return;
        }
        self.track_speed_events += 1;
        let event = SensorEventIndication {
            speed: vec![Speed {
                speed: track.speed_mm_s,
                cruise_engaged: Some(false),
                unknown_4: None,
            }],
            ..Default::default()
        };
        self.send_encrypted(
            channel,
            encode_message(ids::sensor::EVENT_INDICATION, &event),
        );
    }

    /// An audio focus state on the control channel. `unsolicited` is field 2: true for a
    /// state the head unit volunteers, false for a reply to a request, absent for the
    /// aasdk/Prodigy shape. (The generated field is named `granted` because the vendored
    /// schema names it that; see [`AudioFocus`].)
    pub fn send_audio_focus(&mut self, state: i32, unsolicited: Option<bool>) {
        let response = AudioFocusResponse {
            audio_focus_state: state,
            granted: unsolicited,
        };
        self.send_encrypted(0, encode_message(ids::AUDIO_FOCUS_RESPONSE, &response));
    }

    /// `0x800B AUDIO_UNDERFLOW` on the audio channel, which no open-source head unit
    /// sends. Never answered by the source.
    pub fn send_audio_underflow(&mut self, body: &[u8]) {
        let Some(channel) = self.services.audio else {
            return;
        };
        let mut message = ids::av::AUDIO_UNDERFLOW.to_be_bytes().to_vec();
        message.extend_from_slice(body);
        self.send_encrypted(channel, message);
    }

    /// A cluster d-pad action on the now-playing display (0x8002), the one message this
    /// channel carries head unit → phone. Never answered by the source.
    pub fn send_media_event(&mut self, event_type: i32) {
        let channel = self
            .services
            .media_info
            .expect("media_info channel advertised");
        let event = MediaPlaybackStatusEvent { event_type };
        self.send_encrypted(
            channel,
            encode_message(ids::media::PLAYBACK_STATUS_EVENT, &event),
        );
    }

    /// Sends a touch event on the input channel: action (wire value), action index, and
    /// pointers as (x, y, id) in touch-screen coordinates.
    pub fn send_touch(&mut self, action: i32, index: u32, pointers: &[(u32, u32, u32)]) {
        let event = InputEventIndication {
            timestamp: 1_000,
            touch_event: Some(TouchEvent {
                touch_location: pointers
                    .iter()
                    .map(|&(x, y, pointer_id)| TouchLocation { x, y, pointer_id })
                    .collect(),
                action_index: Some(index),
                touch_action: Some(action),
            }),
            ..Default::default()
        };
        let channel = self.services.input.expect("input channel advertised");
        self.send_encrypted(
            channel,
            encode_message(ids::input::EVENT_INDICATION, &event),
        );
    }

    pub fn send_key(&mut self, keycode: u32, down: bool) {
        let event = InputEventIndication {
            timestamp: 2_000,
            button_event: Some(ButtonEvents {
                button_events: vec![ButtonEvent {
                    keycode,
                    is_pressed: down,
                    meta_state: 0,
                    long_press: None,
                }],
            }),
            ..Default::default()
        };
        let channel = self.services.input.expect("input channel advertised");
        self.send_encrypted(
            channel,
            encode_message(ids::input::EVENT_INDICATION, &event),
        );
    }

    /// An unsolicited video focus indication.
    pub fn send_focus(&mut self, mode: i32) {
        let channel = self.services.video.expect("video channel advertised");
        let indication = VideoFocusIndication {
            focus_mode: Some(mode),
            unrequested: Some(true),
        };
        self.send_encrypted(
            channel,
            encode_message(ids::av::VIDEO_FOCUS_INDICATION, &indication),
        );
    }

    /// Acknowledges every withheld media unit in one message.
    pub fn release_acks(&mut self) {
        let count = std::mem::take(&mut self.withheld_acks);
        if count > 0 {
            self.send_ack(count, false);
        }
    }

    /// Sends a message with explicit frame flags (0x04 category bit and encryption).
    pub fn send_raw(&mut self, channel: u8, category: bool, encrypted: bool, message: Vec<u8>) {
        if encrypted {
            self.send_encrypted_flags(channel, category, message);
        } else {
            for frame in fragment(channel, category, &message, false).unwrap() {
                self.output.extend(frame.encode().unwrap());
            }
        }
    }

    /// Sends `count` encrypted records of `record` plaintext bytes each on `channel` as one
    /// FIRST frame (claiming `total`) and then MIDDLE frames, cut every `chunk` ciphertext
    /// bytes so that no frame ends on a record boundary. No LAST frame follows: a peer
    /// that never lets the source finish a split record.
    pub fn send_unaligned_records(
        &mut self,
        channel: u8,
        record: usize,
        count: usize,
        chunk: usize,
        total: u32,
    ) {
        for _ in 0..count {
            self.ssl.ssl_write(&vec![0u8; record]).unwrap();
        }
        let ciphertext = std::mem::take(&mut self.ssl.get_mut().outbound);
        for (i, part) in ciphertext.chunks(chunk).enumerate() {
            let kind = if i == 0 { 1 } else { 0 };
            let frame = Frame {
                header: Header {
                    channel,
                    flags: kind | 0x08,
                    total: (kind == 1).then_some(total),
                },
                payload: part.to_vec(),
            };
            self.output.extend(frame.encode().unwrap());
        }
    }

    /// `{1: {1: {top, bottom, left, right}}}` — the shape Ford SYNC 4.0 sends (report
    /// 07ba8ae0: `0a0a0a080863106318002000`), built by hand because the message has no
    /// published schema. Zero-valued fields are still written, as Ford writes them.
    fn send_ui_config(&mut self, channel: u8, [top, bottom, left, right]: [u32; 4]) {
        let mut insets = Vec::new();
        for (field, value) in [(1u8, top), (2, bottom), (3, left), (4, right)] {
            insets.push(field << 3);
            let mut v = value;
            loop {
                let byte = (v & 0x7f) as u8;
                v >>= 7;
                insets.push(if v == 0 { byte } else { byte | 0x80 });
                if v == 0 {
                    break;
                }
            }
        }
        let config = [&[0x0a, insets.len() as u8][..], &insets].concat();
        let body = [&[0x0a, config.len() as u8][..], &config].concat();
        let mut message = ids::av::UPDATE_UI_CONFIG_REQUEST.to_be_bytes().to_vec();
        message.extend_from_slice(&body);
        self.ui_config_sent = Some(body);
        self.send_encrypted(channel, message);
    }

    fn send_ack(&mut self, count: u32, timestamps: bool) {
        let Some(channel) = self.services.video else {
            return;
        };
        let ack = AvMediaAckIndication {
            session_id: self.video_session.unwrap_or(0) + self.config.projection.ack_session_offset,
            ack_count: (!timestamps).then_some(count),
            receive_timestamps: if timestamps {
                (0..u64::from(count)).collect()
            } else {
                Vec::new()
            },
        };
        self.send_encrypted(channel, encode_message(ids::av::ACK_INDICATION, &ack));
    }

    /// Queues the version request, as a head unit does once the transport exists.
    pub fn start(&mut self) {
        let (major, minor) = self.config.request_version;
        let mut message = ids::VERSION_REQUEST.to_be_bytes().to_vec();
        message.extend(major.to_be_bytes());
        message.extend(minor.to_be_bytes());
        self.send_plain(message);
    }

    pub fn take_output(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.output)
    }

    pub fn tls_done(&self) -> bool {
        self.tls_done
    }

    pub fn ids(&self) -> Vec<u16> {
        self.received.iter().map(|r| r.id).collect()
    }

    fn send_plain(&mut self, message: Vec<u8>) {
        for frame in fragment(0, false, &message, false).unwrap() {
            self.output.extend(frame.encode().unwrap());
        }
    }

    fn send_ping(&mut self, message: Vec<u8>) {
        if self.config.encrypted_pings {
            self.send_encrypted(0, message);
        } else {
            self.send_plain(message);
        }
    }

    fn send_encrypted(&mut self, channel: u8, message: Vec<u8>) {
        self.send_encrypted_flags(channel, false, message)
    }

    fn send_encrypted_flags(&mut self, channel: u8, category: bool, message: Vec<u8>) {
        if let Some(chunk) = self.config.split_ciphertext {
            assert!(message.len() <= 16 * 1024);
            self.ssl.ssl_write(&message).unwrap();
            let ciphertext = std::mem::take(&mut self.ssl.get_mut().outbound);
            let parts: Vec<&[u8]> = ciphertext.chunks(chunk).collect();
            for (i, part) in parts.iter().enumerate() {
                let kind = match (i, parts.len()) {
                    (_, 1) => 3,
                    (0, _) => 1,
                    (i, n) if i + 1 == n => 2,
                    _ => 0,
                };
                let frame = Frame {
                    header: Header {
                        channel,
                        flags: kind | 0x08 | if category { 0x04 } else { 0 },
                        total: (kind == 1).then_some(message.len() as u32),
                    },
                    payload: part.to_vec(),
                };
                self.output.extend(frame.encode().unwrap());
            }
            return;
        }
        for mut frame in fragment(channel, category, &message, false).unwrap() {
            self.ssl.ssl_write(&frame.payload).unwrap();
            frame.payload = std::mem::take(&mut self.ssl.get_mut().outbound);
            frame.header.flags |= 0x08;
            self.output.extend(frame.encode().unwrap());
        }
    }

    fn drive_tls(&mut self) {
        match self.ssl.do_handshake() {
            Ok(()) => {}
            Err(e) if e.code() == ErrorCode::WANT_READ => {}
            Err(e) => self.errors.push(format!("TLS client: {e}")),
        }
        let records = std::mem::take(&mut self.ssl.get_mut().outbound);
        if !records.is_empty() {
            let mut message = ids::SSL_HANDSHAKE.to_be_bytes().to_vec();
            message.extend(records);
            self.send_plain(message);
        }
        if !self.tls_done && self.ssl.ssl().is_init_finished() {
            self.tls_done = true;
            let auth = |status: Option<i32>| {
                let mut out = ids::AUTH_COMPLETE.to_be_bytes().to_vec();
                if let Some(status) = status {
                    out.extend(AuthCompleteIndication { status }.encode_to_vec());
                }
                out
            };
            match self.config.auth {
                Auth::VerifySource => {
                    let status = if *self.source_verified.lock().unwrap() {
                        0
                    } else {
                        -3
                    };
                    self.send_plain(auth(Some(status)));
                }
                Auth::Plain(status) => self.send_plain(auth(Some(status))),
                Auth::Encrypted(status) => self.send_encrypted(0, auth(Some(status))),
                Auth::MissingStatus => self.send_plain(auth(None)),
                Auth::Premature | Auth::Never => {}
            }
            for (channel, message) in self.config.extra_after_auth.clone() {
                self.send_encrypted(channel, message);
            }
        }
    }

    pub fn receive(&mut self, bytes: &[u8]) {
        let mut offset = 0;
        while offset < bytes.len() {
            let (used, frame) = match self.parser.consume(&bytes[offset..]) {
                Ok(result) => result,
                Err(e) => {
                    self.errors.push(format!("frame: {e}"));
                    return;
                }
            };
            offset += used;
            let Some(frame) = frame else { continue };
            let encrypted = frame.header.encrypted();
            let payload = if encrypted {
                self.ssl.get_mut().inbound.extend(&frame.payload);
                let mut plaintext = Vec::new();
                let mut buffer = [0u8; 16 * 1024];
                loop {
                    match self.ssl.ssl_read(&mut buffer) {
                        Ok(n) => plaintext.extend_from_slice(&buffer[..n]),
                        Err(e) if e.code() == ErrorCode::WANT_READ => break,
                        Err(e) => {
                            self.errors.push(format!("decrypt: {e}"));
                            return;
                        }
                    }
                }
                plaintext
            } else {
                frame.payload
            };
            match self
                .assembler
                .push_plaintext(frame.header, &payload, Instant::now())
            {
                Err(e) => {
                    self.errors.push(format!("assemble: {e}"));
                    return;
                }
                Ok(None) => {}
                Ok(Some(message)) => {
                    self.message(message.channel, message.flags, encrypted, message.bytes)
                }
            }
        }
    }

    fn message(&mut self, channel: u8, flags: u8, encrypted: bool, bytes: Vec<u8>) {
        let id = u16::from_be_bytes([bytes[0], bytes[1]]);
        self.received.push(Received {
            channel,
            flags,
            id,
            encrypted,
            bytes: bytes.clone(),
        });
        if channel != 0 {
            return self.service(channel, flags, bytes);
        }
        match id {
            ids::VERSION_RESPONSE if !self.tls_started => {
                self.tls_started = true;
                self.drive_tls();
                if self.config.auth == Auth::Premature {
                    self.send_plain(encode_message(
                        ids::AUTH_COMPLETE,
                        &AuthCompleteIndication { status: 0 },
                    ));
                }
            }
            ids::SSL_HANDSHAKE => {
                self.ssl.get_mut().inbound.extend(&bytes[2..]);
                self.drive_tls();
            }
            ids::SERVICE_DISCOVERY_REQUEST => {
                let Some(response) = self.config.discovery_response.clone() else {
                    return;
                };
                let mut message = ids::SERVICE_DISCOVERY_RESPONSE.to_be_bytes().to_vec();
                message.extend(response);
                if self.config.discovery_plaintext {
                    self.send_plain(message);
                } else {
                    self.send_encrypted(0, message);
                }
                if self.config.ping_after_discovery {
                    self.send_ping(encode_message(
                        ids::PING_REQUEST,
                        &PingRequest {
                            timestamp: -1_234_567_890_123,
                            bugreport_request: Some(true),
                            payload: None,
                        },
                    ));
                }
                if let Some(reason) = self.config.shutdown_after_discovery {
                    self.send_encrypted(
                        0,
                        encode_message(ids::SHUTDOWN_REQUEST, &ShutdownRequest { reason }),
                    );
                }
            }
            ids::PING_REQUEST
                if self.config.answer_pings && (encrypted || !self.config.encrypted_pings) =>
            {
                match PingRequest::decode(&bytes[2..]) {
                    Ok(request) => self.send_ping(encode_message(
                        ids::PING_RESPONSE,
                        &PingResponse {
                            timestamp: request.timestamp,
                            payload: None,
                        },
                    )),
                    Err(e) => self.errors.push(format!("ping: {e}")),
                }
            }
            ids::SHUTDOWN_REQUEST if self.config.answer_shutdown => {
                self.send_encrypted(
                    0,
                    encode_message(ids::SHUTDOWN_RESPONSE, &ByeByeResponse {}),
                );
            }
            // Audio focus is a control-channel exchange, not an AV-channel one (P5.3).
            ids::AUDIO_FOCUS_REQUEST => self.audio_focus(&bytes[2..]),
            // Navigation focus likewise (P6.2).
            ids::NAVIGATION_FOCUS_REQUEST => self.navigation_focus(&bytes[2..]),
            _ => {}
        }
    }

    /// `NAVIGATION_FOCUS_REQUEST` (0x000d) on the control channel.
    ///
    /// A request for `NAV_FOCUS_NATIVE` is the source handing the cluster back, and is not
    /// answered: it withdraws a claim rather than making one.
    fn navigation_focus(&mut self, body: &[u8]) {
        let request = match NavigationFocusRequest::decode(body) {
            Ok(request) => request,
            Err(e) => return self.errors.push(format!("navigation focus request: {e}")),
        };
        let focus_type = request.r#type.unwrap_or(0);
        self.nav_focus_requests.push(focus_type);
        if focus_type == 1 {
            return;
        }
        let answer = match self.config.projection.nav_focus {
            NavFocus::Projected => Some(2),
            NavFocus::Native | NavFocus::NativeThenProjected(_) => Some(1),
            NavFocus::Empty => None,
            NavFocus::Silent => return,
        };
        self.send_nav_focus(answer);
    }

    fn audio_focus(&mut self, body: &[u8]) {
        let request = match AudioFocusRequest::decode(body) {
            Ok(request) => request,
            Err(e) => return self.errors.push(format!("audio focus request: {e}")),
        };
        let focus_type = request.audio_focus_type.unwrap_or(0);
        self.audio_focus_requests.push(focus_type);
        // RELEASE is not answered: it withdraws a grant rather than asking for one.
        if focus_type == 4 {
            return;
        }
        // Every one of these is a reply, so field 2 is false unless the case is explicitly
        // about a malformed or absent flag.
        let (state, unsolicited) = match self.config.projection.audio_focus {
            AudioFocus::Grant | AudioFocus::GrantThenDuck(_) | AudioFocus::GrantThenLoss(_) => {
                (1, Some(false))
            }
            AudioFocus::GrantWithoutFlag => (1, None),
            AudioFocus::GrantMarkedUnsolicited => (1, Some(true)),
            AudioFocus::State(state) => (state, Some(false)),
            AudioFocus::Silent => return,
        };
        self.send_audio_focus(state, unsolicited);
    }
}

impl HeadUnit {
    fn service(&mut self, channel: u8, flags: u8, bytes: Vec<u8>) {
        let id = u16::from_be_bytes([bytes[0], bytes[1]]);
        let body = &bytes[2..];
        if flags & 0x04 != 0 {
            if id != ids::CHANNEL_OPEN_REQUEST {
                return self
                    .errors
                    .push(format!("0x{id:04x} with the 0x04 bit on {channel}"));
            }
            return self.open(channel, body);
        }
        if !self.opened.contains(&channel) {
            return self
                .errors
                .push(format!("0x{id:04x} on unopened channel {channel}"));
        }
        let p = self.config.projection.clone();
        if Some(channel) == self.services.video {
            match id {
                ids::av::SETUP_REQUEST if p.answer_setup => {
                    let response = AvChannelSetupResponse {
                        media_status: p.setup_status,
                        max_unacked: p.max_unacked,
                        configs: p
                            .setup_configs
                            .clone()
                            .unwrap_or_else(|| (0..self.services.video_configs).collect()),
                    };
                    self.send_encrypted(
                        channel,
                        encode_message(ids::av::SETUP_RESPONSE, &response),
                    );
                    if matches!(
                        p.focus,
                        Focus::UnsolicitedThenEcho | Focus::UnsolicitedThenSilent
                    ) {
                        let focus = VideoFocusIndication {
                            focus_mode: Some(1),
                            // Volunteered, so it is flagged as such: Ford SYNC 4.0 sends
                            // `unsolicited=true` here (Android Auto logcat 2026-09-18).
                            unrequested: Some(true),
                        };
                        self.send_encrypted(
                            channel,
                            encode_message(ids::av::VIDEO_FOCUS_INDICATION, &focus),
                        );
                    }
                }
                ids::av::VIDEO_FOCUS_REQUEST => {
                    let request = match VideoFocusRequest::decode(body) {
                        Ok(request) => request,
                        Err(e) => return self.errors.push(format!("focus request: {e}")),
                    };
                    let mode = match p.focus {
                        Focus::Echo | Focus::UnsolicitedThenEcho => request.focus_mode,
                        Focus::Native => Some(2),
                        Focus::Silent | Focus::UnsolicitedThenSilent => return,
                    };
                    let focus = VideoFocusIndication {
                        focus_mode: mode,
                        unrequested: Some(false),
                    };
                    self.send_encrypted(
                        channel,
                        encode_message(ids::av::VIDEO_FOCUS_INDICATION, &focus),
                    );
                }
                ids::av::START_INDICATION => {
                    match aap_proto::messages::AvChannelStartIndication::decode(body) {
                        Ok(start) => self.video_session = Some(start.session),
                        Err(e) => self.errors.push(format!("start indication: {e}")),
                    }
                }
                ids::av::MEDIA | ids::av::MEDIA_WITH_TIMESTAMP => {
                    if self.video_session.is_none() {
                        self.errors.push("media before start indication".into());
                    }
                    let (timestamp_us, payload) = if id == ids::av::MEDIA_WITH_TIMESTAMP {
                        if body.len() < 8 {
                            return self.errors.push("media without timestamp".into());
                        }
                        (
                            Some(u64::from_be_bytes(body[..8].try_into().unwrap())),
                            body[8..].to_vec(),
                        )
                    } else {
                        (None, body.to_vec())
                    };
                    self.media.push(Media {
                        id,
                        timestamp_us,
                        bytes: payload,
                    });
                    // Codec configuration does not consume a video frame credit.
                    if timestamp_us.is_some() {
                        match p.ack {
                            Ack::EachUnit => self.send_ack(1, false),
                            Ack::Timestamps => self.send_ack(1, true),
                            Ack::Withhold => self.withheld_acks += 1,
                        }
                    }
                    if let Some((after, x, y)) = p.tap_after_media {
                        if self.media.len() as u32 == after {
                            self.send_touch(0, 0, &[(x, y, 0)]);
                            self.send_touch(1, 0, &[(x, y, 0)]);
                        }
                    }
                    if let Some((after, insets)) = p.ui_config_after_media {
                        if self.media.len() as u32 == after {
                            self.send_ui_config(channel, insets);
                        }
                    }
                    // Fixes arrive while the stream is running, which is when a vehicle
                    // sends them and when the source has a pipeline to hand them to.
                    if let Some(track) = p.track {
                        if track.every_media > 0 && self.media.len() as u32 % track.every_media == 0
                        {
                            self.play_track_fix(track, &p);
                        }
                    }
                    // P6.2: a cluster that appears mid-projection, and a head unit that
                    // hands it over after first keeping it.
                    let units = self.media.len() as u32;
                    if let Cluster::AfterMedia(after) = p.cluster {
                        if units == after {
                            self.send_cluster(true);
                        }
                    }
                    if let NavFocus::NativeThenProjected(after) = p.nav_focus {
                        if units == after {
                            self.send_nav_focus(Some(2));
                        }
                    }
                }
                ids::av::UPDATE_UI_CONFIG_REPLY => {
                    // Android Auto echoes the configuration back as field 1, so the reply
                    // body is byte-identical to the request's (docs/references.md).
                    match self.ui_config_sent.as_deref() {
                        Some(sent) if sent == body => self.ui_config_responses += 1,
                        Some(_) => self.errors.push(format!(
                            "UI config reply body {} does not echo the request",
                            body.len()
                        )),
                        None => self.errors.push("UI config reply without a request".into()),
                    }
                }
                ids::av::STOP_INDICATION => {}
                _ => {}
            }
        } else if Some(channel) == self.services.audio {
            self.audio_message(channel, id, body.to_vec());
        } else if Some(channel) == self.services.input {
            if id == ids::input::BINDING_REQUEST && p.answer_binding {
                let response = BindingResponse {
                    status: p.binding_status,
                    already_paired: None,
                };
                self.send_encrypted(
                    channel,
                    encode_message(ids::input::BINDING_RESPONSE, &response),
                );
            }
        } else if Some(channel) == self.services.media_info {
            // Phone → head unit only, and neither message is answered.
            match id {
                ids::media::PLAYBACK_STATUS => match MediaPlaybackStatus::decode(body) {
                    Ok(status) => self.playback_status.push(status),
                    Err(e) => self.errors.push(format!("playback status: {e}")),
                },
                ids::media::PLAYBACK_METADATA => match MediaPlaybackMetadata::decode(body) {
                    Ok(metadata) => self.playback_metadata.push(metadata),
                    Err(e) => self.errors.push(format!("playback metadata: {e}")),
                },
                _ => self
                    .errors
                    .push(format!("0x{id:04x} on the media_info channel {channel}")),
            }
        } else if Some(channel) == self.services.navigation {
            self.navigation_message(id, body, &p);
        } else if Some(channel) == self.services.sensor && id == ids::sensor::REQUEST {
            let request = match SensorRequest::decode(body) {
                Ok(request) => request,
                Err(e) => return self.errors.push(format!("sensor request: {e}")),
            };
            let sensor_type = request.sensor_type;
            self.sensor_requests.push(request);
            let response = SensorStartResponseMessage {
                status: p.sensor_status,
            };
            self.send_encrypted(
                channel,
                encode_message(ids::sensor::START_RESPONSE, &response),
            );
            // A refused subscription sends no values; the source must tell that apart
            // from an accepted type that stays silent (P5.2).
            let Some(event) = sensor_value(sensor_type, &p).filter(|_| p.sensor_status == 0) else {
                return;
            };
            self.send_encrypted(
                channel,
                encode_message(ids::sensor::EVENT_INDICATION, &event),
            );
            // A head unit that sends a type nobody subscribed to. Ford has not been seen
            // doing it, but P5.2 counts it rather than dropping it.
            if let Some(volunteered) = p
                .volunteer_sensor
                .filter(|_| self.sensor_requests.len() == 1)
            {
                if let Some(event) = sensor_value(volunteered, &p) {
                    self.send_encrypted(
                        channel,
                        encode_message(ids::sensor::EVENT_INDICATION, &event),
                    );
                }
            }
        }
    }
}

/// The value this simulated head unit reports for one sensor type, or `None` for a type
/// it advertises and accepts without ever sending anything ([`SILENT_SENSORS`]) — the
/// case a P5.2 harvest has to tell apart from populated.
impl HeadUnit {
    /// Phone → head unit on the instrument cluster channel (P6.2). None of it is
    /// answered; the head unit's own two messages go the other way.
    ///
    /// Each message is decoded here from the pinned schema rather than compared against
    /// what the source believes it sent, which is the point of having a second reader at
    /// all — spec §8.1's rule exists because both ends of this simulator were once written
    /// from one reading of one schema and agreed with the bug.
    fn navigation_message(&mut self, id: u16, body: &[u8], p: &Projection) {
        match id {
            ids::navigation::STATE => match NavigationState::decode(body) {
                Ok(state) => self.nav_states.push(state.state.unwrap_or(-1)),
                Err(e) => self.errors.push(format!("navigation state: {e}")),
            },
            ids::navigation::NOTIFICATION => match NavigationNotification::decode(body) {
                Ok(notification) => self.nav_notifications.push(notification),
                Err(e) => self.errors.push(format!("navigation notification: {e}")),
            },
            ids::navigation::NEXT_TURN_DISTANCE => {
                match NavigationNextTurnDistanceEvent::decode(body) {
                    Ok(distance) => self.nav_distances.push(distance),
                    Err(e) => self.errors.push(format!("navigation distance: {e}")),
                }
            }
            // Deprecated or not ours; a source sending one would be a finding.
            _ => self
                .errors
                .push(format!("0x{id:04x} on the navigation channel")),
        }
        self.nav_messages += 1;
        if let Cluster::StopAfter(after) = p.cluster {
            if self.nav_messages == after {
                self.send_cluster(false);
            }
        }
    }
}

fn sensor_value(sensor_type: i32, p: &Projection) -> Option<SensorEventIndication> {
    Some(match sensor_type {
        13 => SensorEventIndication {
            driving_status: vec![DrivingStatus {
                status: p.driving_status,
            }],
            ..Default::default()
        },
        10 => SensorEventIndication {
            night_mode: vec![NightMode {
                is_night: Some(true),
            }],
            ..Default::default()
        },
        1 => SensorEventIndication {
            gps_location: vec![GpsLocation {
                latitude: p.latitude,
                longitude: p.longitude,
                accuracy: Some(8),
                altitude: Some(213),
                speed: Some(0),
                bearing: Some(1790),
            }],
            ..Default::default()
        },
        3 => SensorEventIndication {
            speed: vec![Speed {
                speed: 0,
                cruise_engaged: Some(false),
                unknown_4: None,
            }],
            ..Default::default()
        },
        7 => SensorEventIndication {
            parking_brake: vec![ParkingBrake {
                parking_brake: true,
            }],
            ..Default::default()
        },
        21 => SensorEventIndication {
            gps_satellite: vec![GpsSatelliteData {
                satellite_count: 11,
                satellites_used: Some(7),
                satellites: vec![
                    GpsSatelliteInfo {
                        prn: 3,
                        snr: 41,
                        in_use: true,
                        azimuth: None,
                        elevation: None,
                    },
                    GpsSatelliteInfo {
                        prn: 9,
                        snr: 17,
                        in_use: false,
                        azimuth: None,
                        elevation: None,
                    },
                ],
            }],
            ..Default::default()
        },
        24 => SensorEventIndication {
            trailer: vec![TrailerData {}],
            ..Default::default()
        },
        _ => return None,
    })
}

impl HeadUnit {
    /// The audio AV channel (P5.3): the same four messages as video, with its own session
    /// and its own window, plus the underflow injection that tests ask for.
    fn audio_message(&mut self, channel: u8, id: u16, body: Vec<u8>) {
        let p = self.config.projection.clone();
        match id {
            ids::av::SETUP_REQUEST if p.answer_audio_setup => {
                match aap_proto::messages::AvChannelSetupRequest::decode(body.as_slice()) {
                    // The audio setup declares a codec, never a configuration index.
                    Ok(request) if request.media_codec_type == 1 => {}
                    Ok(request) => self.errors.push(format!(
                        "audio setup asked for codec {}",
                        request.media_codec_type
                    )),
                    Err(e) => self.errors.push(format!("audio setup request: {e}")),
                }
                let response = AvChannelSetupResponse {
                    media_status: p.audio_setup_status,
                    max_unacked: p.audio_max_unacked,
                    configs: Vec::new(),
                };
                self.send_encrypted(channel, encode_message(ids::av::SETUP_RESPONSE, &response));
            }
            ids::av::START_INDICATION => match AvChannelStartIndication::decode(body.as_slice()) {
                Ok(start) => {
                    self.audio_session = Some(start.session);
                    self.audio_config = Some(start.config);
                }
                Err(e) => self.errors.push(format!("audio start indication: {e}")),
            },
            ids::av::MEDIA_WITH_TIMESTAMP => {
                if self.audio_session.is_none() {
                    self.errors.push("audio before start indication".into());
                }
                if body.len() < 8 {
                    return self.errors.push("audio without timestamp".into());
                }
                self.audio.push(Media {
                    id,
                    timestamp_us: Some(u64::from_be_bytes(body[..8].try_into().unwrap())),
                    bytes: body[8..].to_vec(),
                });
                let count = self.audio.len() as u32;
                self.send_audio_ack(channel, 1);
                match p.audio_focus {
                    AudioFocus::GrantThenDuck(after) if count == after => {
                        self.send_audio_focus(4, Some(true))
                    }
                    AudioFocus::GrantThenLoss(after) if count == after => {
                        self.send_audio_focus(3, Some(true))
                    }
                    _ => {}
                }
                if let Some((after, payload)) = p.underflow_after_audio.clone() {
                    if count == after {
                        self.send_audio_underflow(&payload);
                    }
                }
            }
            // Audio is never PCM configuration: a 0x0001 here is a source bug.
            ids::av::MEDIA => self
                .errors
                .push("untimestamped media on the audio channel".into()),
            ids::av::STOP_INDICATION => self.audio_stopped = true,
            _ => self
                .errors
                .push(format!("0x{id:04x} on the audio channel {channel}")),
        }
    }

    fn send_audio_ack(&mut self, channel: u8, count: u32) {
        let ack = AvMediaAckIndication {
            session_id: self.audio_session.unwrap_or(0),
            ack_count: Some(count),
            receive_timestamps: Vec::new(),
        };
        self.send_encrypted(channel, encode_message(ids::av::ACK_INDICATION, &ack));
    }

    fn open(&mut self, channel: u8, body: &[u8]) {
        let request = match ChannelOpenRequest::decode(body) {
            Ok(request) => request,
            Err(e) => return self.errors.push(format!("open request: {e}")),
        };
        if request.channel_id != i32::from(channel) {
            self.errors.push(format!(
                "open request for {} on channel {channel}",
                request.channel_id
            ));
        }
        let advertised = self.services.all.contains(&channel)
            || [
                self.services.video,
                self.services.input,
                self.services.sensor,
                self.services.media_info,
                self.services.navigation,
                self.services.audio,
            ]
            .contains(&Some(channel));
        let behavior = self
            .config
            .projection
            .opens
            .iter()
            .find(|(c, _)| *c == channel)
            .map(|(_, b)| *b)
            .unwrap_or(if advertised {
                Open::Accept
            } else {
                Open::Reject(-5)
            });
        let ok = encode_message(
            ids::CHANNEL_OPEN_RESPONSE,
            &ChannelOpenResponse { status: 0 },
        );
        match behavior {
            Open::Accept => {
                self.opened.push(channel);
                self.send_encrypted_flags(channel, true, ok);
                // A real head unit tells the phone its cluster is there once the channel
                // it lives on is open; whether this one does is [`Cluster`].
                if Some(channel) == self.services.navigation
                    && matches!(
                        self.config.projection.cluster,
                        Cluster::OnOpen | Cluster::StopAfter(_)
                    )
                {
                    self.send_cluster(true);
                }
            }
            Open::Reject(status) => self.send_encrypted_flags(
                channel,
                true,
                encode_message(ids::CHANNEL_OPEN_RESPONSE, &ChannelOpenResponse { status }),
            ),
            Open::Silent => {}
            Open::OnControl => {
                self.opened.push(channel);
                self.send_encrypted_flags(0, true, ok);
            }
            Open::WithoutCategory => {
                self.opened.push(channel);
                self.send_encrypted_flags(channel, false, ok);
            }
            Open::WrongChannel(other) => self.send_encrypted_flags(other, true, ok),
        }
    }
}
