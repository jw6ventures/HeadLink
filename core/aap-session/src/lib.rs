//! Shared source-role session engine. `Session` performs no I/O: a transport owner
//! feeds received bytes and timer wakeups, and writes `pending_output` in order.
//!
//! Stages follow spec §9: Version → Tls → Auth → Discovery, then either Ready (a bounded
//! discovery-only hold with keepalive) or, with `stop_after=projection`, Setup (channel
//! opens, AV setup, input binding, video focus) → Projecting (spec §8); both end in
//! Shutdown. [`Config::stop_after`] ends the session deliberately after an earlier stage.
//! Every attempt must use a fresh `Session`; nothing, including TLS state, the media link,
//! or channel state, is reused across connections.
//!
//! Transports: `transport` (poll loop, pollable descriptors) and `accessory` (blocking
//! I/O threads with signal interruption, for the Android accessory descriptor).
use aap_proto::{
    decode, encode_message,
    enums::{shutdown_reason, status},
    ids,
    messages::{
        AuthCompleteIndication, ByeByeResponse, PingRequest, PingResponse,
        ServiceDiscoveryResponse, ShutdownRequest,
    },
};
use aap_tls::{client_hello, FailureKind, ServerConfig, TlsServer};
use aap_wire::{fragment, Assembler, Frame, Header, Message, Parser};
use std::{
    collections::VecDeque,
    fmt,
    sync::Arc,
    time::{Duration, Instant},
};

#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod accessory;
pub mod discovery;
pub mod input;
pub mod media;
pub mod navigation;
pub mod now_playing;
pub mod projection;
#[cfg(unix)]
pub mod transport;
pub mod version;

use discovery::{Capabilities, VideoPreference};
use media::MediaLink;
use navigation::GuidanceLink;
use now_playing::NowPlayingLink;
use projection::{AppEvent, ProjectionConfig, ProjectionReport, ProjectionState};
use version::{Profile, Version};

/// Bytes of an inbound version request kept in the report; totals are always recorded.
pub const REQUEST_RECORD_LIMIT: usize = 64;
/// Bytes of the decrypted discovery response kept when raw capture is enabled
/// ([`Config::capture_discovery_response`]); the response is a few hundred bytes.
pub const DISCOVERY_RECORD_LIMIT: usize = 8192;
/// Unhandled messages described individually in the report; all are counted.
pub const UNHANDLED_LIMIT: usize = 16;
/// Bytes kept per unhandled message body under [`Config::capture_unhandled`].
pub const UNHANDLED_BODY_LIMIT: usize = 64;
/// Control message IDs with a defined role in this session; out of place they are errors
/// rather than unhandled traffic.
const STATE_IDS: [u16; 10] = [
    ids::VERSION_REQUEST,
    ids::VERSION_RESPONSE,
    ids::SSL_HANDSHAKE,
    ids::AUTH_COMPLETE,
    ids::SERVICE_DISCOVERY_REQUEST,
    ids::SERVICE_DISCOVERY_RESPONSE,
    ids::PING_REQUEST,
    ids::PING_RESPONSE,
    ids::SHUTDOWN_REQUEST,
    ids::SHUTDOWN_RESPONSE,
];
const KIND_MIDDLE: u8 = 0;
const KIND_FIRST: u8 = 1;
const KIND_LAST: u8 = 2;
const KIND_BULK: u8 = 3;
const FLAG_CATEGORY: u8 = 0x04;
const FLAG_ENCRYPTED: u8 = 0x08;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Stage {
    Version,
    Tls,
    Auth,
    Discovery,
    /// Capabilities ready: the discovery-only hold with keepalive.
    Ready,
    /// Capabilities ready for projection: channel opens, AV setup, input binding, focus.
    /// Completed once the start indication is queued.
    Setup,
    /// Video started. Completed once the first IDR is completely written.
    Projecting,
    /// Our shutdown request is out; waiting for the ByeByeResponse.
    Shutdown,
}
impl Stage {
    pub fn name(self) -> &'static str {
        match self {
            Stage::Version => "version",
            Stage::Tls => "tls",
            Stage::Auth => "auth",
            Stage::Discovery => "discovery",
            Stage::Ready => "ready",
            Stage::Setup => "setup",
            Stage::Projecting => "projecting",
            Stage::Shutdown => "shutdown",
        }
    }
}

/// The last stage a session completes before ending deliberately.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StopAfter {
    /// Version response written; ends with `STAGE_LIMIT` when the peer begins TLS.
    Version,
    /// TLS complete and AUTH_COMPLETE accepted; ends before discovery.
    Auth,
    /// Full handshake POC: validated discovery, the bounded hold, then shutdown.
    Discovery,
    /// Projection demonstration: discovery, then video and input until Stop, the
    /// configured projection limit, or the head unit ends the session.
    Projection,
}
impl StopAfter {
    pub const ALL: [StopAfter; 4] = [
        StopAfter::Version,
        StopAfter::Auth,
        StopAfter::Discovery,
        StopAfter::Projection,
    ];
    pub fn name(self) -> &'static str {
        match self {
            StopAfter::Version => "version",
            StopAfter::Auth => "auth",
            StopAfter::Discovery => "discovery",
            StopAfter::Projection => "projection",
        }
    }
    pub fn from_name(name: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|s| s.name() == name)
    }
}

/// How ping requests and responses are protected after TLS (spec §7.6).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeepaliveProfile {
    /// Default. A response uses the encryption of the ping it answers, and own pings use
    /// that of the head unit's latest ping (encrypted after TLS until one arrives). Ford SYNC 4
    /// pings encrypted and stops pinging after a plaintext response (reports c1265b54,
    /// c2407cff); the pinned Prodigy receiver pings plaintext.
    Mirror,
    /// Always plaintext: the pinned Prodigy receiver policy.
    Plaintext,
    /// Encrypted once TLS is established, whatever the head unit sends.
    Encrypted,
}
impl KeepaliveProfile {
    pub const ALL: [KeepaliveProfile; 3] = [
        KeepaliveProfile::Mirror,
        KeepaliveProfile::Plaintext,
        KeepaliveProfile::Encrypted,
    ];
    pub fn name(self) -> &'static str {
        match self {
            KeepaliveProfile::Mirror => "mirror",
            KeepaliveProfile::Plaintext => "plaintext",
            KeepaliveProfile::Encrypted => "encrypted",
        }
    }
    pub fn from_name(name: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|p| p.name() == name)
    }
}

/// Spec §9 error categories, plus local additions documented in docs/references.md:
/// VERSION_TIMEOUT, UNEXPECTED_MESSAGE, TLS_ERROR, AUTH_TIMEOUT, DISCOVERY_TIMEOUT,
/// CAPABILITIES_INSUFFICIENT, SETUP_TIMEOUT, INPUT_BINDING_FAILED, and the deliberate
/// endings STAGE_LIMIT, HOLD_COMPLETE, PROJECTION_COMPLETE, and PEER_SHUTDOWN.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Category {
    IoEof,
    IoError,
    FrameInvalid,
    VersionUnsupported,
    VersionTimeout,
    UnexpectedMessage,
    TlsAlertPeer,
    TlsAlertLocal,
    TlsNoCommonSuite,
    TlsCertPolicy,
    TlsTimeout,
    TlsError,
    AuthFailed,
    AuthTimeout,
    DiscoveryInvalid,
    DiscoveryTimeout,
    KeepaliveTimeout,
    /// Discovery was valid but lacks usable video or input for projection.
    CapabilitiesInsufficient,
    /// The head unit refused or closed the video or input channel.
    ChannelRejected,
    /// A channel open, AV setup, or input binding response did not arrive in time.
    SetupTimeout,
    AvSetupFailed,
    InputBindingFailed,
    VideoFocusDenied,
    EncoderFailed,
    FlowControlTimeout,
    /// Ended after the configured `stop_after` stage.
    StageLimit,
    /// The discovery-only hold ran to its end and a shutdown request was sent.
    HoldComplete,
    /// The configured projection limit ran out and a shutdown request was sent.
    ProjectionComplete,
    /// The head unit requested shutdown and was answered.
    PeerShutdown,
    Cancelled,
}
impl Category {
    pub fn name(self) -> &'static str {
        match self {
            Category::IoEof => "IO_EOF",
            Category::IoError => "IO_ERROR",
            Category::FrameInvalid => "FRAME_INVALID",
            Category::VersionUnsupported => "VERSION_UNSUPPORTED",
            Category::VersionTimeout => "VERSION_TIMEOUT",
            Category::UnexpectedMessage => "UNEXPECTED_MESSAGE",
            Category::TlsAlertPeer => "TLS_ALERT_PEER",
            Category::TlsAlertLocal => "TLS_ALERT_LOCAL",
            Category::TlsNoCommonSuite => "TLS_NO_COMMON_SUITE",
            Category::TlsCertPolicy => "TLS_CERT_POLICY",
            Category::TlsTimeout => "TLS_TIMEOUT",
            Category::TlsError => "TLS_ERROR",
            Category::AuthFailed => "AUTH_FAILED",
            Category::AuthTimeout => "AUTH_TIMEOUT",
            Category::DiscoveryInvalid => "DISCOVERY_INVALID",
            Category::DiscoveryTimeout => "DISCOVERY_TIMEOUT",
            Category::KeepaliveTimeout => "KEEPALIVE_TIMEOUT",
            Category::CapabilitiesInsufficient => "CAPABILITIES_INSUFFICIENT",
            Category::ChannelRejected => "CHANNEL_REJECTED",
            Category::SetupTimeout => "SETUP_TIMEOUT",
            Category::AvSetupFailed => "AV_SETUP_FAILED",
            Category::InputBindingFailed => "INPUT_BINDING_FAILED",
            Category::VideoFocusDenied => "VIDEO_FOCUS_DENIED",
            Category::EncoderFailed => "ENCODER_FAILED",
            Category::FlowControlTimeout => "FLOW_CONTROL_TIMEOUT",
            Category::StageLimit => "STAGE_LIMIT",
            Category::HoldComplete => "HOLD_COMPLETE",
            Category::ProjectionComplete => "PROJECTION_COMPLETE",
            Category::PeerShutdown => "PEER_SHUTDOWN",
            Category::Cancelled => "CANCELLED",
        }
    }
    /// Pending output (such as an already-decided response or an alert) is still
    /// flushed for protocol outcomes, but not after I/O failure or cancellation.
    fn flushes(self) -> bool {
        !matches!(
            self,
            Category::IoEof | Category::IoError | Category::Cancelled
        )
    }
    fn tls(kind: FailureKind) -> Self {
        match kind {
            FailureKind::PeerAlert => Category::TlsAlertPeer,
            FailureKind::LocalAlert => Category::TlsAlertLocal,
            FailureKind::NoCommonSuite => Category::TlsNoCommonSuite,
            FailureKind::CertPolicy => Category::TlsCertPolicy,
            FailureKind::Error => Category::TlsError,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Outcome {
    pub category: Category,
    pub stage: Stage,
    /// Monotonic offset from session start.
    pub at: Duration,
    pub detail: String,
    /// Underlying OS error code where available.
    pub os_error: Option<i32>,
}

#[derive(Debug, Clone)]
pub struct Config {
    pub profile: Profile,
    pub stop_after: StopAfter,
    /// Server identity and peer policy; required unless `stop_after` is `Version`.
    pub tls: Option<ServerConfig>,
    /// Sent in the discovery request's session info.
    pub session_uuid: String,
    /// From descriptor open until a version request arrives.
    pub version_wait: Duration,
    /// From the version response being fully written until the peer's first TLS message.
    pub client_hello_wait: Duration,
    /// From the first TLS message until the peer's Finished is verified.
    pub tls_wait: Duration,
    /// From TLS completion until AUTH_COMPLETE.
    pub auth_wait: Duration,
    /// From the discovery request being fully written until the response.
    pub discovery_wait: Duration,
    /// From session start until capabilities are ready.
    pub establishment_cap: Duration,
    /// Discovery-only hold before this source requests shutdown (spec: at most 30 s).
    pub hold: Duration,
    /// During the hold, a ping is sent when the head unit has not pinged for this long.
    pub ping_interval: Duration,
    /// Unanswered own pings tolerated; the next due ping fails the session instead.
    pub keepalive_misses: usize,
    /// Opt-in diagnostic: keep the raw decrypted discovery response bytes in the report
    /// (`discovery_response_hex`). Off by default because those bytes carry the
    /// head-unit name, serials, vehicle id, and Bluetooth address that the report
    /// otherwise reduces to lengths; a report captured this way is sensitive.
    pub capture_discovery_response: bool,
    /// Keep a bounded body for each unhandled message. Off by default: these are bytes
    /// no profile here interprets, so nothing vouches for what is in them.
    pub capture_unhandled: bool,
    /// Which advertised video configuration to take; [`VideoPreference::Largest`] by
    /// default. A head unit can advertise one it will not display, and nothing on the wire
    /// says so, so this is the only way to choose another.
    pub video_preference: VideoPreference,
    /// Ping encryption; [`KeepaliveProfile::Mirror`] by default.
    pub keepalive: KeepaliveProfile,
    /// From the shutdown request until the session ends without a ByeByeResponse.
    pub shutdown_wait: Duration,
    /// Bound on flushing already-decided output after a protocol outcome.
    pub linger: Duration,
    /// Most unwritten output the session may hold. Media stops queueing at
    /// `projection.output_limit`, but replies (pings above all) are always queued, so a
    /// head unit that keeps sending while it has stopped reading would otherwise grow
    /// the queue without end. Past this the session ends with `IO_ERROR`.
    pub output_cap: usize,
    /// Projection timing and options (`stop_after=projection`).
    pub projection: ProjectionConfig,
    /// Encoder hand-off shared with the app; a private link is created when absent.
    pub media: Option<Arc<MediaLink>>,
    /// PCM hand-off shared with the app (P5.3); a private link is created when absent,
    /// which simply means nothing is ever submitted and the audio channel carries
    /// silence. Must be a [`media::Codec::Pcm`] link.
    pub audio: Option<Arc<MediaLink>>,
    /// Now-playing hand-off shared with the app (P5.1); a private link is created when
    /// absent, which simply means nothing is ever published and the `media_info` channel
    /// carries no messages.
    pub now_playing: Option<Arc<NowPlayingLink>>,
    /// Guidance hand-off shared with the app (P6.5); a private link is created when
    /// absent, which simply means a route-driven navigation channel never has anything to
    /// say. Read only when `projection.navigation_source` is `route`.
    pub guidance: Option<Arc<GuidanceLink>>,
}
impl Default for Config {
    /// Version-only: no TLS identity is needed.
    fn default() -> Self {
        Self {
            profile: Profile::Match17,
            stop_after: StopAfter::Version,
            tls: None,
            session_uuid: "00000000-0000-0000-0000-000000000000".into(),
            version_wait: Duration::from_secs(5),
            client_hello_wait: Duration::from_secs(5),
            tls_wait: Duration::from_secs(10),
            auth_wait: Duration::from_secs(10),
            discovery_wait: Duration::from_secs(10),
            establishment_cap: Duration::from_secs(30),
            hold: Duration::from_secs(30),
            ping_interval: Duration::from_secs(2),
            keepalive_misses: 3,
            capture_discovery_response: false,
            capture_unhandled: false,
            video_preference: VideoPreference::default(),
            keepalive: KeepaliveProfile::Mirror,
            shutdown_wait: Duration::from_secs(2),
            linger: Duration::from_secs(1),
            output_cap: 16 * 1024 * 1024,
            projection: ProjectionConfig::default(),
            media: None,
            audio: None,
            now_playing: None,
            guidance: None,
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Counters {
    pub bytes_in: u64,
    pub bytes_out: u64,
    pub frames_in: u64,
    pub messages_in: u64,
    pub messages_out: u64,
    pub encrypted_frames_in: u64,
    pub encrypted_frames_out: u64,
    /// Encrypted frames whose payload ended inside a TLS record.
    pub split_record_frames: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Keepalive {
    pub hu_requests: u64,
    pub hu_requests_encrypted: u64,
    /// Requests asking for a bug report; never honored.
    pub hu_bugreport_flags: u64,
    pub responses_written: u64,
    /// Written responses that were encrypted.
    pub responses_encrypted: u64,
    /// Longest time from receiving a ping request to fully writing its response.
    pub response_max: Option<Duration>,
    pub own_requests: u64,
    /// Own requests sent encrypted.
    pub own_requests_encrypted: u64,
    pub own_responses: u64,
    pub own_unmatched: u64,
    pub own_rtt_max: Option<Duration>,
    pub own_outstanding_max: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Unhandled {
    pub stage: Stage,
    pub channel: u8,
    pub flags: u8,
    pub id: u16,
    pub len: usize,
    /// Monotonic offset from session start, so an unanswered request can be placed
    /// against the focus and streaming timeline.
    pub at: Duration,
    /// Body after the two-byte id, at most [`UNHANDLED_BODY_LIMIT`]; empty unless
    /// [`Config::capture_unhandled`] is set, since these bytes are unexamined.
    pub body: Vec<u8>,
}

/// Sanitized, nonsecret session summary. Raw request bytes are protocol metadata only.
#[derive(Debug, Clone)]
pub struct Report {
    pub generation: u64,
    pub profile: Profile,
    /// Which advertised video configuration the session was told to take.
    pub video_preference: VideoPreference,
    pub stop_after: StopAfter,
    pub stage: Stage,
    /// The latest completed stage: version once its response is written, tls once the
    /// peer Finished is verified, auth on a successful AUTH_COMPLETE, discovery on a
    /// validated response.
    pub last_completed: Option<Stage>,
    pub request_prefix: Vec<u8>,
    pub request_len: Option<usize>,
    pub requested: Option<Version>,
    pub response: Option<Version>,
    pub request_at: Option<Duration>,
    pub response_written_at: Option<Duration>,
    pub tls_message_len: Option<usize>,
    pub tls_before_response_written: bool,
    pub tls_first_message_at: Option<Duration>,
    pub tls_complete_at: Option<Duration>,
    /// Refreshed after each input batch and timer while TLS runs.
    pub tls: Option<aap_tls::Summary>,
    /// ClientHello decoded without TLS (stop_after=version).
    pub client_hello: Option<Result<client_hello::ClientHello, String>>,
    pub auth_status: Option<i32>,
    pub auth_encrypted: bool,
    pub auth_at: Option<Duration>,
    pub discovery_request_len: Option<usize>,
    pub discovery_request_written_at: Option<Duration>,
    pub discovery_response_len: Option<usize>,
    pub discovery_response_at: Option<Duration>,
    /// Raw decrypted discovery response (message id and proto body), truncated to
    /// [`DISCOVERY_RECORD_LIMIT`]. Empty unless [`Config::capture_discovery_response`]
    /// is set; see that field for why it is off by default.
    pub discovery_response_prefix: Vec<u8>,
    pub capabilities: Option<Capabilities>,
    pub hold_started_at: Option<Duration>,
    pub shutdown_request_written_at: Option<Duration>,
    pub bye_bye_at: Option<Duration>,
    pub peer_shutdown_reason: Option<i32>,
    /// [`Config::keepalive`], recorded for provenance.
    pub keepalive_profile: KeepaliveProfile,
    pub keepalive: Keepalive,
    /// Present once projection has begun.
    pub projection: Option<ProjectionReport>,
    pub unhandled: Vec<Unhandled>,
    pub unhandled_count: u64,
    pub abandoned_output: usize,
    pub counters: Counters,
    pub outcome: Option<Outcome>,
}

pub(crate) fn status_name(value: i32) -> &'static str {
    status::Enum::try_from(value).map_or("unknown", |e| e.as_str_name())
}

fn shutdown_reason_name(value: i32) -> &'static str {
    shutdown_reason::Enum::try_from(value).map_or("unknown", |e| e.as_str_name())
}

impl fmt::Display for Report {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fn ms(d: Option<Duration>) -> String {
            d.map_or("-".into(), |d| d.as_millis().to_string())
        }
        fn us(d: Option<Duration>) -> String {
            d.map_or("-".into(), |d| d.as_micros().to_string())
        }
        fn opt<T: fmt::Display>(v: &Option<T>) -> String {
            v.as_ref().map_or("-".into(), T::to_string)
        }
        writeln!(f, "generation={}", self.generation)?;
        writeln!(f, "profile={}", self.profile.name())?;
        writeln!(f, "video_preference={}", self.video_preference)?;
        writeln!(f, "stop_after={}", self.stop_after.name())?;
        writeln!(f, "stage={}", self.stage.name())?;
        writeln!(
            f,
            "last_completed_stage={}",
            self.last_completed.map_or("-", Stage::name)
        )?;
        writeln!(f, "version_request_len={}", opt(&self.request_len))?;
        write!(f, "version_request_hex=")?;
        if self.request_prefix.is_empty() {
            write!(f, "-")?;
        }
        for byte in &self.request_prefix {
            write!(f, "{byte:02x}")?;
        }
        writeln!(f)?;
        writeln!(f, "requested_version={}", opt(&self.requested))?;
        writeln!(f, "response_version={}", opt(&self.response))?;
        writeln!(f, "version_request_ms={}", ms(self.request_at))?;
        writeln!(
            f,
            "version_response_written_ms={}",
            ms(self.response_written_at)
        )?;
        writeln!(f, "tls_message_len={}", opt(&self.tls_message_len))?;
        writeln!(
            f,
            "tls_before_response_written={}",
            self.tls_before_response_written
        )?;
        writeln!(f, "tls_first_message_ms={}", ms(self.tls_first_message_at))?;
        writeln!(f, "tls_complete_ms={}", ms(self.tls_complete_at))?;
        match (&self.tls, &self.client_hello) {
            (Some(tls), _) => write!(f, "{tls}")?,
            (None, Some(Ok(hello))) => write!(f, "{hello}")?,
            (None, Some(Err(e))) => writeln!(f, "tls_client_hello=undecodable: {e}")?,
            (None, None) => writeln!(f, "tls_client_hello=-")?,
        }
        writeln!(f, "auth_status={}", opt(&self.auth_status))?;
        writeln!(
            f,
            "auth_status_name={}",
            self.auth_status.map_or("-", status_name)
        )?;
        writeln!(f, "auth_encrypted={}", self.auth_encrypted)?;
        writeln!(f, "auth_ms={}", ms(self.auth_at))?;
        writeln!(
            f,
            "discovery_request_len={}",
            opt(&self.discovery_request_len)
        )?;
        writeln!(
            f,
            "discovery_request_written_ms={}",
            ms(self.discovery_request_written_at)
        )?;
        writeln!(
            f,
            "discovery_response_len={}",
            opt(&self.discovery_response_len)
        )?;
        writeln!(
            f,
            "discovery_response_ms={}",
            ms(self.discovery_response_at)
        )?;
        write!(f, "discovery_response_hex=")?;
        if self.discovery_response_prefix.is_empty() {
            write!(f, "-")?;
        }
        for byte in &self.discovery_response_prefix {
            write!(f, "{byte:02x}")?;
        }
        writeln!(f)?;
        match &self.capabilities {
            Some(caps) => write!(f, "{caps}")?,
            None => writeln!(f, "discovery_capabilities=-")?,
        }
        writeln!(f, "hold_started_ms={}", ms(self.hold_started_at))?;
        writeln!(
            f,
            "shutdown_request_written_ms={}",
            ms(self.shutdown_request_written_at)
        )?;
        writeln!(f, "bye_bye_ms={}", ms(self.bye_bye_at))?;
        match self.peer_shutdown_reason {
            Some(reason) => writeln!(
                f,
                "peer_shutdown_reason={reason} ({})",
                shutdown_reason_name(reason)
            )?,
            None => writeln!(f, "peer_shutdown_reason=-")?,
        }
        writeln!(f, "keepalive_profile={}", self.keepalive_profile.name())?;
        let k = &self.keepalive;
        writeln!(
            f,
            "keepalive_hu_requests={} keepalive_hu_encrypted={} keepalive_hu_bugreport_flags={} \
             keepalive_responses_written={} keepalive_responses_encrypted={} keepalive_response_max_us={} \
             keepalive_own_requests={} keepalive_own_encrypted={} keepalive_own_responses={} \
             keepalive_own_unmatched={} keepalive_own_rtt_max_us={} keepalive_own_outstanding_max={}",
            k.hu_requests,
            k.hu_requests_encrypted,
            k.hu_bugreport_flags,
            k.responses_written,
            k.responses_encrypted,
            us(k.response_max),
            k.own_requests,
            k.own_requests_encrypted,
            k.own_responses,
            k.own_unmatched,
            us(k.own_rtt_max),
            k.own_outstanding_max
        )?;
        if let Some(projection) = &self.projection {
            write!(f, "{projection}")?;
        }
        writeln!(f, "unhandled_messages={}", self.unhandled_count)?;
        for (i, u) in self.unhandled.iter().enumerate() {
            writeln!(
                f,
                "unhandled_{i}=stage={} channel={} flags=0x{:02x} id=0x{:04x} len={} \
                 at={}ms body={}",
                u.stage.name(),
                u.channel,
                u.flags,
                u.id,
                u.len,
                u.at.as_millis(),
                if u.body.is_empty() {
                    "-".into()
                } else {
                    aap_tls::identity::hex(&u.body)
                }
            )?;
        }
        let c = &self.counters;
        writeln!(
            f,
            "bytes_in={} bytes_out={} frames_in={} messages_in={} messages_out={} \
             encrypted_frames_in={} encrypted_frames_out={} split_record_frames={} abandoned_output={}",
            c.bytes_in,
            c.bytes_out,
            c.frames_in,
            c.messages_in,
            c.messages_out,
            c.encrypted_frames_in,
            c.encrypted_frames_out,
            c.split_record_frames,
            self.abandoned_output
        )?;
        match &self.outcome {
            None => writeln!(f, "final_category=-"),
            Some(o) => {
                writeln!(f, "final_category={}", o.category.name())?;
                writeln!(f, "final_stage={}", o.stage.name())?;
                writeln!(f, "final_ms={}", o.at.as_millis())?;
                writeln!(f, "final_os_error={}", opt(&o.os_error))?;
                writeln!(f, "final_detail={}", o.detail)
            }
        }
    }
}

/// Output positions whose complete write matters to the protocol or the report.
#[derive(Debug, Clone, Copy)]
enum Marker {
    VersionResponse,
    DiscoveryRequest,
    /// Received at, and whether the response was encrypted.
    PingResponse(Instant, bool),
    ShutdownRequest,
    FirstKeyframe,
}

/// Why this source requested shutdown.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ShutdownCause {
    Hold,
    ProjectionLimit,
    StopRequested,
}

/// Encrypted frames whose ciphertext ends inside a TLS record, awaiting the frames that
/// complete it (spec §7.3). Their plaintext is delivered to the assembler as one piece.
struct PendingUnit {
    first: Header,
    last_kind: u8,
    frames: u64,
    plaintext: Vec<u8>,
    started: Instant,
}

impl PendingUnit {
    /// The most plaintext a unit may hold before its record boundary: a prefix of one
    /// message, so never more than a FIRST frame's total or the largest message the
    /// assembler would take. Without this a peer that never ends a frame on a record
    /// boundary grows the buffer until the phone runs out of memory.
    fn limit(&self) -> usize {
        self.first
            .total
            .map_or(aap_wire::AV_LIMIT, |t| (t as usize).min(aap_wire::AV_LIMIT))
    }
}

pub struct Session {
    config: Config,
    started: Instant,
    parser: Parser,
    assembler: Assembler,
    output: Vec<u8>,
    written: usize,
    queued: u64,
    markers: VecDeque<(u64, Marker)>,
    stage_deadline: Option<Instant>,
    hold_deadline: Option<Instant>,
    next_ping: Option<Instant>,
    linger_deadline: Option<Instant>,
    closed: bool,
    tls: Option<TlsServer>,
    hello: client_hello::Reader,
    pending: Option<PendingUnit>,
    outstanding: VecDeque<(i64, Instant)>,
    last_ping_timestamp: i64,
    /// Whether the head unit's latest ping was encrypted; `None` before its first.
    hu_ping_encrypted: Option<bool>,
    projection: Option<ProjectionState>,
    media: Arc<MediaLink>,
    audio: Arc<MediaLink>,
    now_playing: Arc<NowPlayingLink>,
    guidance: Arc<GuidanceLink>,
    app_events: VecDeque<AppEvent>,
    shutdown_cause: ShutdownCause,
    report: Report,
}

impl Session {
    /// Create at descriptor open: the version wait starts at `now`.
    pub fn new(config: Config, generation: u64, now: Instant) -> Self {
        let report = Report {
            generation,
            profile: config.profile,
            video_preference: config.video_preference,
            stop_after: config.stop_after,
            stage: Stage::Version,
            last_completed: None,
            request_prefix: Vec::new(),
            request_len: None,
            requested: None,
            response: None,
            request_at: None,
            response_written_at: None,
            tls_message_len: None,
            tls_before_response_written: false,
            tls_first_message_at: None,
            tls_complete_at: None,
            tls: None,
            client_hello: None,
            auth_status: None,
            auth_encrypted: false,
            auth_at: None,
            discovery_request_len: None,
            discovery_request_written_at: None,
            discovery_response_len: None,
            discovery_response_at: None,
            discovery_response_prefix: Vec::new(),
            capabilities: None,
            hold_started_at: None,
            shutdown_request_written_at: None,
            bye_bye_at: None,
            peer_shutdown_reason: None,
            keepalive_profile: config.keepalive,
            keepalive: Keepalive::default(),
            projection: None,
            unhandled: Vec::new(),
            unhandled_count: 0,
            abandoned_output: 0,
            counters: Counters::default(),
            outcome: None,
        };
        let media = config.media.clone().unwrap_or_default();
        let audio = config
            .audio
            .clone()
            .unwrap_or_else(|| Arc::new(MediaLink::audio(media::DEFAULT_AUDIO_LIMIT)));
        let now_playing = config.now_playing.clone().unwrap_or_default();
        let guidance = config.guidance.clone().unwrap_or_default();
        Self {
            stage_deadline: Some(now + config.version_wait),
            config,
            started: now,
            parser: Parser::new(),
            assembler: Assembler::default(),
            output: Vec::new(),
            written: 0,
            queued: 0,
            markers: VecDeque::new(),
            hold_deadline: None,
            next_ping: None,
            linger_deadline: None,
            closed: false,
            tls: None,
            hello: client_hello::Reader::new(),
            pending: None,
            outstanding: VecDeque::new(),
            last_ping_timestamp: 0,
            hu_ping_encrypted: None,
            projection: None,
            media,
            audio,
            now_playing,
            guidance,
            app_events: VecDeque::new(),
            shutdown_cause: ShutdownCause::Hold,
            report,
        }
    }

    pub fn report(&self) -> &Report {
        &self.report
    }

    /// The encoder hand-off for this session (from [`Config::media`] or private).
    pub fn media(&self) -> Arc<MediaLink> {
        self.media.clone()
    }

    /// The PCM hand-off for this session (from [`Config::audio`] or private).
    pub fn audio(&self) -> Arc<MediaLink> {
        self.audio.clone()
    }

    /// The now-playing hand-off for this session (from [`Config::now_playing`] or
    /// private).
    pub fn now_playing(&self) -> Arc<NowPlayingLink> {
        self.now_playing.clone()
    }

    /// The guidance hand-off for this session (from [`Config::guidance`] or private).
    pub fn guidance(&self) -> Arc<GuidanceLink> {
        self.guidance.clone()
    }

    /// Session → app events since the last call, in order (session thread).
    pub fn take_app_events(&mut self) -> Vec<AppEvent> {
        self.app_events.drain(..).collect()
    }

    pub fn wants_read(&self) -> bool {
        self.report.outcome.is_none()
    }

    pub fn finished(&self) -> bool {
        self.closed || (self.report.outcome.is_some() && self.pending_output().is_empty())
    }

    pub fn pending_output(&self) -> &[u8] {
        &self.output[self.written..]
    }

    pub fn output_written(&mut self, n: usize, now: Instant) {
        let n = n.min(self.pending_output().len());
        self.written += n;
        self.report.counters.bytes_out += n as u64;
        if self.written == self.output.len() {
            self.output.clear();
            self.written = 0;
        }
        while let Some(&(end, marker)) = self.markers.front() {
            if self.report.counters.bytes_out < end {
                break;
            }
            self.markers.pop_front();
            self.marker_written(marker, now);
        }
    }

    fn marker_written(&mut self, marker: Marker, now: Instant) {
        let at = self.elapsed(now);
        let live = self.report.outcome.is_none();
        match marker {
            Marker::VersionResponse => {
                self.complete(Stage::Version);
                self.report.response_written_at = Some(at);
                if live && self.report.stage == Stage::Version {
                    self.report.stage = Stage::Tls;
                    self.stage_deadline = Some(now + self.config.client_hello_wait);
                }
            }
            Marker::DiscoveryRequest => {
                self.report.discovery_request_written_at = Some(at);
                if live && self.report.stage == Stage::Discovery {
                    self.stage_deadline = Some(now + self.config.discovery_wait);
                }
            }
            Marker::PingResponse(received, encrypted) => {
                let k = &mut self.report.keepalive;
                k.responses_written += 1;
                k.responses_encrypted += u64::from(encrypted);
                let latency = now.saturating_duration_since(received);
                k.response_max = Some(k.response_max.map_or(latency, |m| m.max(latency)));
            }
            Marker::ShutdownRequest => self.report.shutdown_request_written_at = Some(at),
            Marker::FirstKeyframe => self.first_keyframe_written(now),
        }
    }

    pub fn next_deadline(&self) -> Option<Instant> {
        if self.closed {
            return None;
        }
        if self.report.outcome.is_some() {
            return self
                .linger_deadline
                .filter(|_| !self.pending_output().is_empty());
        }
        let cap = (self.report.stage < Stage::Ready)
            .then(|| self.started + self.config.establishment_cap);
        [
            self.stage_deadline,
            cap,
            self.assembler.next_expiry(),
            self.pending
                .as_ref()
                .map(|p| p.started + aap_wire::ASSEMBLY_LIFETIME),
            self.hold_deadline,
            self.next_ping,
        ]
        .into_iter()
        .chain(self.projection_deadlines())
        .flatten()
        .min()
    }

    pub fn on_timer(&mut self, now: Instant) {
        if self.closed {
            return;
        }
        if self.report.outcome.is_some() {
            if self.linger_deadline.is_some_and(|d| now >= d) {
                self.report.abandoned_output = self.pending_output().len();
                self.closed = true;
            }
            return;
        }
        self.timers(now);
        self.pump_media(now);
        self.pump_now_playing(now);
        self.pump_navigation(now);
        self.refresh_tls();
    }

    fn timers(&mut self, now: Instant) {
        let stage = self.report.stage;
        if stage < Stage::Ready && now >= self.started + self.config.establishment_cap {
            let category = Self::timeout_category(stage);
            return self.finish(category, "establishment cap reached".into(), now);
        }
        if self.stage_deadline.is_some_and(|d| now >= d) {
            let detail = match stage {
                Stage::Version => "no version request before stage deadline",
                Stage::Tls if self.report.tls_message_len.is_none() => {
                    "no TLS handshake message after version response before stage deadline"
                }
                Stage::Tls => "TLS handshake incomplete before stage deadline",
                Stage::Auth => "no AUTH_COMPLETE after TLS before stage deadline",
                Stage::Discovery => "no service discovery response before stage deadline",
                Stage::Setup => return self.setup_deadline(now),
                Stage::Shutdown => {
                    let (category, what) = self.shutdown_outcome();
                    let detail = format!(
                        "{what}; no ByeByeResponse within {} ms",
                        self.config.shutdown_wait.as_millis()
                    );
                    return self.finish(category, detail, now);
                }
                Stage::Ready | Stage::Projecting => "unexpected stage deadline",
            };
            return self.finish(Self::timeout_category(stage), detail.into(), now);
        }
        if let Err(e) = self.assembler.expire(now) {
            return self.finish(Category::FrameInvalid, e.to_string(), now);
        }
        if let Some(p) = self
            .pending
            .as_ref()
            .filter(|p| now.saturating_duration_since(p.started) >= aap_wire::ASSEMBLY_LIFETIME)
        {
            let detail = format!(
                "TLS record split across frames on channel {} not completed within {} s",
                p.first.channel,
                aap_wire::ASSEMBLY_LIFETIME.as_secs()
            );
            return self.finish(Category::FrameInvalid, detail, now);
        }
        if stage == Stage::Ready && self.hold_deadline.is_some_and(|d| now >= d) {
            return self.begin_shutdown(ShutdownCause::Hold, now);
        }
        if stage == Stage::Projecting {
            self.projection_timers(now);
            if self.report.outcome.is_some() || self.report.stage != Stage::Projecting {
                return;
            }
        }
        if matches!(stage, Stage::Ready | Stage::Setup | Stage::Projecting)
            && self.next_ping.is_some_and(|d| now >= d)
        {
            self.ping_tick(now);
        }
    }

    fn timeout_category(stage: Stage) -> Category {
        match stage {
            Stage::Version => Category::VersionTimeout,
            Stage::Tls => Category::TlsTimeout,
            Stage::Auth => Category::AuthTimeout,
            Stage::Discovery => Category::DiscoveryTimeout,
            Stage::Setup => Category::SetupTimeout,
            Stage::Ready | Stage::Projecting | Stage::Shutdown => Category::KeepaliveTimeout,
        }
    }

    pub fn receive(&mut self, bytes: &[u8], now: Instant) {
        if self.report.outcome.is_some() || self.closed {
            return;
        }
        self.report.counters.bytes_in += bytes.len() as u64;
        let mut offset = 0;
        while offset < bytes.len() && self.report.outcome.is_none() {
            match self.parser.consume(&bytes[offset..]) {
                Err(e) => {
                    self.finish(Category::FrameInvalid, e.to_string(), now);
                    break;
                }
                Ok((0, None)) => break,
                Ok((used, frame)) => {
                    offset += used;
                    if let Some(frame) = frame {
                        self.report.counters.frames_in += 1;
                        self.frame(frame, now);
                    }
                }
            }
        }
        self.refresh_tls();
    }

    fn refresh_tls(&mut self) {
        if let Some(tls) = &self.tls {
            self.report.tls = Some(tls.summary());
        }
        self.refresh_projection();
    }

    fn frame(&mut self, frame: Frame, now: Instant) {
        let header = frame.header;
        if !header.encrypted() {
            if self
                .pending
                .as_ref()
                .is_some_and(|p| p.first.channel == header.channel)
            {
                let detail = format!(
                    "plaintext frame on channel {} inside an incomplete TLS record",
                    header.channel
                );
                return self.finish(Category::FrameInvalid, detail, now);
            }
            return self.assemble(header, &frame.payload, now);
        }
        let Some(tls) = self.tls.as_mut().filter(|t| t.established()) else {
            let detail = format!(
                "encrypted frame on channel {} before TLS is established",
                header.channel
            );
            return self.finish(Category::UnexpectedMessage, detail, now);
        };
        self.report.counters.encrypted_frames_in += 1;
        let (plaintext, boundary) = match tls.decrypt(&frame.payload) {
            Ok(result) => result,
            Err(failure) => return self.tls_failure(failure, now),
        };
        let unit = match self.pending.take() {
            None => PendingUnit {
                first: header,
                last_kind: header.kind(),
                frames: 1,
                plaintext,
                started: now,
            },
            Some(mut unit) => {
                let continues = header.channel == unit.first.channel
                    && header.flags & 0x0c == unit.first.flags & 0x0c
                    && matches!(header.kind(), KIND_MIDDLE | KIND_LAST);
                if !continues {
                    let detail = format!(
                        "TLS record continues into an unrelated frame (channel {}, flags 0x{:02x})",
                        header.channel, header.flags
                    );
                    return self.finish(Category::FrameInvalid, detail, now);
                }
                unit.plaintext.extend_from_slice(&plaintext);
                unit.last_kind = header.kind();
                unit.frames += 1;
                unit
            }
        };
        if unit.plaintext.len() > unit.limit() {
            let detail = format!(
                "TLS records split across frames on channel {} exceed {} plaintext bytes",
                unit.first.channel,
                unit.limit()
            );
            return self.finish(Category::FrameInvalid, detail, now);
        }
        if !boundary {
            self.report.counters.split_record_frames += 1;
            if matches!(unit.last_kind, KIND_LAST | KIND_BULK) {
                let detail = format!(
                    "message on channel {} ended inside a TLS record",
                    header.channel
                );
                return self.finish(Category::FrameInvalid, detail, now);
            }
            self.pending = Some(unit);
            return;
        }
        if unit.plaintext.is_empty() {
            let detail = format!(
                "encrypted frame on channel {} carried no application data",
                header.channel
            );
            return self.finish(Category::FrameInvalid, detail, now);
        }
        let merged = if unit.frames == 1 {
            unit.first
        } else {
            let first_kind = unit.first.kind();
            let kind = match (first_kind, unit.last_kind) {
                (KIND_FIRST, KIND_LAST) => KIND_BULK,
                (KIND_FIRST, _) => KIND_FIRST,
                (_, KIND_LAST) => KIND_LAST,
                _ => KIND_MIDDLE,
            };
            if kind == KIND_BULK
                && unit.first.total.map(|t| t as usize) != Some(unit.plaintext.len())
            {
                let detail = format!(
                    "FIRST total {:?} does not match {} plaintext bytes",
                    unit.first.total,
                    unit.plaintext.len()
                );
                return self.finish(Category::FrameInvalid, detail, now);
            }
            Header {
                channel: unit.first.channel,
                flags: (unit.first.flags & !3) | kind,
                total: if kind == KIND_FIRST {
                    unit.first.total
                } else {
                    None
                },
            }
        };
        self.assemble(merged, &unit.plaintext, now);
    }

    fn assemble(&mut self, header: Header, plaintext: &[u8], now: Instant) {
        match self.assembler.push_plaintext(header, plaintext, now) {
            Err(e) => self.finish(Category::FrameInvalid, e.to_string(), now),
            Ok(None) => {}
            Ok(Some(message)) => {
                self.report.counters.messages_in += 1;
                self.message(message, now);
            }
        }
    }

    /// EOF (`None`) or a transport error. Pending output is abandoned; an earlier
    /// outcome is kept.
    pub fn on_transport_closed(&mut self, error: Option<&std::io::Error>, now: Instant) {
        if !self.closed && self.report.stage == Stage::Shutdown {
            // The head unit may close or reset the link instead of answering shutdown.
            let how = error.map_or("closed".into(), |e| format!("failed with {e}"));
            let (category, what) = self.shutdown_outcome();
            let detail = format!("{what}; transport {how} before a ByeByeResponse");
            self.finish_os(category, detail, error.and_then(|e| e.raw_os_error()), now);
        }
        if !self.closed {
            match error {
                None => self.finish(Category::IoEof, "peer closed the transport".into(), now),
                Some(e) => self.finish_os(Category::IoError, e.to_string(), e.raw_os_error(), now),
            }
            self.close();
            self.refresh_tls();
        }
    }

    pub fn on_cancelled(&mut self, now: Instant) {
        if !self.closed {
            self.finish(Category::Cancelled, "stop requested".into(), now);
            self.close();
            self.refresh_tls();
        }
    }

    /// A graceful stop: after TLS, in the hold or a projection stage, the stream is stopped
    /// and a shutdown request sent (ending `CANCELLED` once answered or timed out);
    /// otherwise the same as [`Session::on_cancelled`].
    pub fn on_shutdown_requested(&mut self, now: Instant) {
        if self.closed || self.report.outcome.is_some() || self.report.stage == Stage::Shutdown {
            return;
        }
        let established = self.tls.as_ref().is_some_and(TlsServer::established);
        if established
            && matches!(
                self.report.stage,
                Stage::Ready | Stage::Setup | Stage::Projecting
            )
        {
            self.begin_shutdown(ShutdownCause::StopRequested, now);
            self.refresh_tls();
        } else {
            self.on_cancelled(now);
        }
    }

    fn close(&mut self) {
        self.report.abandoned_output = self.pending_output().len();
        self.closed = true;
    }

    fn message(&mut self, message: Message, now: Instant) {
        // Message::id cannot fail: the assembler rejects messages shorter than two bytes.
        let id = message.id().unwrap_or_default();
        let encrypted = message.flags & FLAG_ENCRYPTED != 0;
        let stage = self.report.stage;
        let decided = self.report.response.is_some();
        let established = self.tls.as_ref().is_some_and(TlsServer::established);
        if message.channel != 0 || message.flags & FLAG_CATEGORY != 0 {
            if established && self.projection.is_some() {
                return self.service_message(&message, id, now);
            }
            if established {
                return self.unhandled(&message, id, stage, now);
            }
            let detail = format!(
                "message 0x{id:04x} on channel {} with flags 0x{:02x} in {} stage",
                message.channel,
                message.flags,
                stage.name()
            );
            return self.finish(Category::UnexpectedMessage, detail, now);
        }
        match id {
            ids::VERSION_REQUEST if !decided && !encrypted => {
                self.version_request(&message.bytes, now)
            }
            // A decided response moves the peer on even before our write completes, so a
            // pipelined TLS message is accepted and recorded rather than rejected.
            ids::SSL_HANDSHAKE if decided && !encrypted && !established => {
                self.ssl_handshake(&message.bytes, now)
            }
            ids::AUTH_COMPLETE if stage == Stage::Auth => {
                self.auth_complete(&message.bytes, encrypted, now)
            }
            ids::AUTH_COMPLETE if stage == Stage::Tls => self.finish(
                Category::AuthFailed,
                "AUTH_COMPLETE before local TLS completion".into(),
                now,
            ),
            ids::PING_REQUEST if decided => self.ping_request(&message.bytes, encrypted, now),
            ids::PING_RESPONSE if established => self.ping_response(&message.bytes, now),
            ids::SERVICE_DISCOVERY_RESPONSE if stage == Stage::Discovery && encrypted => {
                self.discovery_response(&message.bytes, now)
            }
            ids::SHUTDOWN_REQUEST if established && encrypted => {
                self.peer_shutdown(&message.bytes, now)
            }
            ids::SHUTDOWN_RESPONSE if stage == Stage::Shutdown && encrypted => {
                self.shutdown_response(&message.bytes, now)
            }
            // Audio focus is a control-channel exchange, so it arrives here rather than
            // on the audio channel (P5.3). It is only meaningful once a projection
            // exists; outside one it stays an unhandled message.
            ids::AUDIO_FOCUS_RESPONSE if established && self.projection.is_some() => {
                self.audio_focus_response(&message.bytes[2..], now)
            }
            // Navigation focus is a control-channel exchange for the same reason audio
            // focus is, and arrives here rather than on the `navigation` channel (P6.2).
            ids::NAVIGATION_FOCUS_RESPONSE if established && self.projection.is_some() => {
                self.navigation_focus_response(&message.bytes[2..], now)
            }
            _ if established && !STATE_IDS.contains(&id) => {
                self.unhandled(&message, id, stage, now)
            }
            _ => {
                let detail = format!(
                    "message 0x{id:04x} ({}) in {} stage (response decided: {decided}, TLS established: {established})",
                    if encrypted { "encrypted" } else { "plaintext" },
                    stage.name()
                );
                self.finish(Category::UnexpectedMessage, detail, now);
            }
        }
    }

    /// After TLS, messages without a role in this session are recorded and skipped.
    fn unhandled(&mut self, message: &Message, id: u16, stage: Stage, now: Instant) {
        self.report.unhandled_count += 1;
        let at = self.elapsed(now);
        if self.report.unhandled.len() < UNHANDLED_LIMIT {
            let body = if self.config.capture_unhandled {
                let body = message.bytes.get(2..).unwrap_or_default();
                body[..body.len().min(UNHANDLED_BODY_LIMIT)].to_vec()
            } else {
                Vec::new()
            };
            self.report.unhandled.push(Unhandled {
                stage,
                channel: message.channel,
                flags: message.flags,
                id,
                at,
                body,
                len: message.bytes.len(),
            });
        }
    }

    fn version_request(&mut self, bytes: &[u8], now: Instant) {
        let body = &bytes[2..];
        self.report.request_len = Some(bytes.len());
        self.report.request_prefix = bytes[..bytes.len().min(REQUEST_RECORD_LIMIT)].to_vec();
        self.report.requested = version::parse_request(body);
        self.report.request_at = Some(self.elapsed(now));
        let response = match self.config.profile.respond(body) {
            Ok(response) => response,
            Err(unsupported) => {
                return self.finish(Category::VersionUnsupported, unsupported.to_string(), now)
            }
        };
        if self.send(version::encode_response(response), false, now) {
            self.mark(Marker::VersionResponse);
            self.report.response = Some(response);
            // The ClientHello wait starts once the response is completely written.
            self.stage_deadline = None;
        }
    }

    fn ssl_handshake(&mut self, bytes: &[u8], now: Instant) {
        let body = &bytes[2..];
        if self.report.tls_message_len.is_none() {
            self.report.tls_message_len = Some(body.len());
            self.report.tls_before_response_written = self.report.last_completed.is_none();
            self.report.tls_first_message_at = Some(self.elapsed(now));
            self.report.stage = Stage::Tls;
            self.stage_deadline = Some(now + self.config.tls_wait);
        }
        if self.config.stop_after == StopAfter::Version {
            self.hello.feed(body);
            self.report.client_hello = self.hello.result().cloned();
            let detail = format!(
                "stopped after version by configuration; peer sent {} TLS handshake bytes",
                body.len()
            );
            return self.finish(Category::StageLimit, detail, now);
        }
        if self.tls.is_none() {
            let Some(config) = &self.config.tls else {
                let detail = "no TLS identity configured for a session beyond version".into();
                return self.finish(Category::TlsError, detail, now);
            };
            match TlsServer::new(config) {
                Ok(tls) => self.tls = Some(tls),
                Err(failure) => return self.tls_failure(failure, now),
            }
        }
        let Some(tls) = self.tls.as_mut() else { return };
        match tls.handshake(body) {
            Err(failure) => self.tls_failure(failure, now),
            Ok((output, done)) => {
                if !output.is_empty() {
                    let mut message = ids::SSL_HANDSHAKE.to_be_bytes().to_vec();
                    message.extend_from_slice(&output);
                    if !self.send(message, false, now) {
                        return;
                    }
                }
                if done {
                    self.complete(Stage::Tls);
                    self.report.tls_complete_at = Some(self.elapsed(now));
                    self.report.stage = Stage::Auth;
                    self.stage_deadline = Some(now + self.config.auth_wait);
                }
            }
        }
    }

    fn tls_failure(&mut self, failure: aap_tls::Failure, now: Instant) {
        // During the handshake our alert is still meaningful to the peer.
        if !failure.output.is_empty() && self.tls.as_ref().is_some_and(|t| !t.established()) {
            let mut message = ids::SSL_HANDSHAKE.to_be_bytes().to_vec();
            message.extend_from_slice(&failure.output);
            let _ = self.send(message, false, now);
        }
        self.refresh_tls();
        self.finish(Category::tls(failure.kind), failure.detail, now);
    }

    fn auth_complete(&mut self, bytes: &[u8], encrypted: bool, now: Instant) {
        let auth = match decode::<AuthCompleteIndication>(&bytes[2..]) {
            Ok(auth) => auth,
            Err(e) => {
                return self.finish(
                    Category::AuthFailed,
                    format!("invalid AUTH_COMPLETE: {e}"),
                    now,
                )
            }
        };
        self.report.auth_status = Some(auth.status);
        self.report.auth_encrypted = encrypted;
        self.report.auth_at = Some(self.elapsed(now));
        if auth.status != 0 {
            let detail = format!(
                "AUTH_COMPLETE status {} ({})",
                auth.status,
                status_name(auth.status)
            );
            return self.finish(Category::AuthFailed, detail, now);
        }
        self.complete(Stage::Auth);
        if self.config.stop_after == StopAfter::Auth {
            let detail = "stopped after authentication by configuration".into();
            return self.finish(Category::StageLimit, detail, now);
        }
        self.report.stage = Stage::Discovery;
        // The response wait starts once the request is written; the cap bounds the queue.
        self.stage_deadline = None;
        let request = encode_message(
            ids::SERVICE_DISCOVERY_REQUEST,
            &discovery::request(&self.config.session_uuid),
        );
        self.report.discovery_request_len = Some(request.len());
        if self.send(request, true, now) {
            self.mark(Marker::DiscoveryRequest);
        }
    }

    fn discovery_response(&mut self, bytes: &[u8], now: Instant) {
        self.report.discovery_response_len = Some(bytes.len());
        self.report.discovery_response_at = Some(self.elapsed(now));
        if self.config.capture_discovery_response {
            // Captured before decoding, so a response that fails to parse (the reason
            // to enable this) is still recoverable byte for byte.
            let end = bytes.len().min(DISCOVERY_RECORD_LIMIT);
            self.report.discovery_response_prefix = bytes[..end].to_vec();
        }
        let capabilities = decode::<ServiceDiscoveryResponse>(&bytes[2..])
            .map_err(|e| e.to_string())
            .and_then(|response| {
                Capabilities::from_response(&response, self.config.video_preference)
            });
        match capabilities {
            Err(e) => self.finish(Category::DiscoveryInvalid, e, now),
            Ok(capabilities) => {
                self.report.capabilities = Some(capabilities);
                self.complete(Stage::Discovery);
                if self.config.stop_after == StopAfter::Projection {
                    return self.begin_projection(now);
                }
                self.report.stage = Stage::Ready;
                self.report.hold_started_at = Some(self.elapsed(now));
                self.stage_deadline = None;
                self.hold_deadline = Some(now + self.config.hold);
                self.next_ping = Some(now + self.config.ping_interval);
                if self.config.hold.is_zero() {
                    self.begin_shutdown(ShutdownCause::Hold, now);
                }
            }
        }
    }

    fn ping_request(&mut self, bytes: &[u8], encrypted: bool, now: Instant) {
        let request = match decode::<PingRequest>(&bytes[2..]) {
            Ok(request) => request,
            Err(e) => {
                let detail = format!("invalid PingRequest: {e}");
                return self.finish(Category::UnexpectedMessage, detail, now);
            }
        };
        let k = &mut self.report.keepalive;
        k.hu_requests += 1;
        k.hu_requests_encrypted += u64::from(encrypted);
        k.hu_bugreport_flags += u64::from(request.bugreport_request == Some(true));
        self.hu_ping_encrypted = Some(encrypted);
        // The head unit is alive: our own pings are neither needed nor overdue.
        self.outstanding.clear();
        if matches!(
            self.report.stage,
            Stage::Ready | Stage::Setup | Stage::Projecting
        ) {
            self.next_ping = Some(now + self.config.ping_interval);
        }
        let response = PingResponse {
            timestamp: request.timestamp,
            payload: None,
        };
        let encrypted = self.keepalive_encrypted();
        if self.send(
            encode_message(ids::PING_RESPONSE, &response),
            encrypted,
            now,
        ) {
            self.mark(Marker::PingResponse(now, encrypted));
        }
    }

    fn ping_response(&mut self, bytes: &[u8], now: Instant) {
        let response = match decode::<PingResponse>(&bytes[2..]) {
            Ok(response) => response,
            Err(e) => {
                let detail = format!("invalid PingResponse: {e}");
                return self.finish(Category::UnexpectedMessage, detail, now);
            }
        };
        let k = &mut self.report.keepalive;
        match self
            .outstanding
            .iter()
            .position(|(timestamp, _)| *timestamp == response.timestamp)
        {
            Some(index) => {
                let sent = self.outstanding[index].1;
                self.outstanding.drain(..=index);
                k.own_responses += 1;
                let rtt = now.saturating_duration_since(sent);
                k.own_rtt_max = Some(k.own_rtt_max.map_or(rtt, |m| m.max(rtt)));
            }
            None => k.own_unmatched += 1,
        }
    }

    fn ping_tick(&mut self, now: Instant) {
        if self.outstanding.len() >= self.config.keepalive_misses {
            let detail = format!(
                "{} ping requests unanswered and no head-unit ping",
                self.outstanding.len()
            );
            return self.finish(Category::KeepaliveTimeout, detail, now);
        }
        let elapsed = i64::try_from(self.elapsed(now).as_micros()).unwrap_or(i64::MAX);
        let timestamp = elapsed.max(self.last_ping_timestamp.saturating_add(1));
        self.last_ping_timestamp = timestamp;
        self.outstanding.push_back((timestamp, now));
        let k = &mut self.report.keepalive;
        k.own_requests += 1;
        k.own_outstanding_max = k.own_outstanding_max.max(self.outstanding.len());
        self.next_ping = Some(now + self.config.ping_interval);
        let request = PingRequest {
            timestamp,
            bugreport_request: None,
            payload: None,
        };
        let encrypted = self.keepalive_encrypted();
        self.report.keepalive.own_requests_encrypted += u64::from(encrypted);
        let _ = self.send(encode_message(ids::PING_REQUEST, &request), encrypted, now);
    }

    /// Whether the next ping message (a response to the latest head-unit ping, or our own
    /// request) is encrypted under the configured profile. Nothing is encrypted before
    /// TLS is established; a ping then stays plaintext rather than failing the session.
    fn keepalive_encrypted(&self) -> bool {
        let wanted = match self.config.keepalive {
            KeepaliveProfile::Mirror => self.hu_ping_encrypted.unwrap_or(true),
            KeepaliveProfile::Plaintext => false,
            KeepaliveProfile::Encrypted => true,
        };
        wanted && self.tls.as_ref().is_some_and(TlsServer::established)
    }

    fn begin_shutdown(&mut self, cause: ShutdownCause, now: Instant) {
        self.stop_video(now);
        self.shutdown_cause = cause;
        self.report.stage = Stage::Shutdown;
        self.hold_deadline = None;
        self.next_ping = None;
        self.stage_deadline = Some(now + self.config.shutdown_wait);
        let request = ShutdownRequest {
            reason: shutdown_reason::Enum::UserSelection as i32,
        };
        if self.send(encode_message(ids::SHUTDOWN_REQUEST, &request), true, now) {
            self.mark(Marker::ShutdownRequest);
        }
    }

    fn shutdown_response(&mut self, bytes: &[u8], now: Instant) {
        if let Err(e) = decode::<ByeByeResponse>(&bytes[2..]) {
            let detail = format!("invalid ByeByeResponse: {e}");
            return self.finish(Category::UnexpectedMessage, detail, now);
        }
        self.report.bye_bye_at = Some(self.elapsed(now));
        let (category, what) = self.shutdown_outcome();
        self.finish(category, format!("{what}; ByeByeResponse received"), now);
    }

    /// Outcome category and description of this source's shutdown request.
    fn shutdown_outcome(&self) -> (Category, String) {
        match self.shutdown_cause {
            ShutdownCause::Hold => (
                Category::HoldComplete,
                format!(
                    "discovery hold of {} ms completed",
                    self.config.hold.as_millis()
                ),
            ),
            ShutdownCause::ProjectionLimit => (
                Category::ProjectionComplete,
                format!(
                    "projection limit of {} ms reached",
                    self.config.projection.limit.unwrap_or_default().as_millis()
                ),
            ),
            ShutdownCause::StopRequested => (
                Category::Cancelled,
                "stop requested; shutdown request sent".into(),
            ),
        }
    }

    fn peer_shutdown(&mut self, bytes: &[u8], now: Instant) {
        let request = match decode::<ShutdownRequest>(&bytes[2..]) {
            Ok(request) => request,
            Err(e) => {
                let detail = format!("invalid ShutdownRequest: {e}");
                return self.finish(Category::UnexpectedMessage, detail, now);
            }
        };
        self.report.peer_shutdown_reason = Some(request.reason);
        // Release audio focus before answering. The video stop indication is deliberately
        // not sent here — the head unit is the one tearing the session down — but a
        // dangling audio focus grant is the one thing that outlives the session: SYNC 3.4
        // owners report a focus state machine that wedges until the cable is pulled.
        self.stop_audio("head unit requested shutdown", now);
        self.stop_navigation("head unit requested shutdown", now);
        if !self.send(
            encode_message(ids::SHUTDOWN_RESPONSE, &ByeByeResponse {}),
            true,
            now,
        ) {
            return;
        }
        let detail = format!(
            "head unit requested shutdown with reason {} ({}) in {} stage",
            request.reason,
            shutdown_reason_name(request.reason),
            self.report.stage.name()
        );
        self.finish(Category::PeerShutdown, detail, now);
    }

    /// Fragments a complete plaintext control message, encrypting each fragment through
    /// the session TLS when requested, and queues the frames. Returns false after
    /// finishing the session on failure.
    fn send(&mut self, message: Vec<u8>, encrypted: bool, now: Instant) -> bool {
        self.send_on(0, false, false, message, encrypted, now)
    }

    /// [`Session::send`] on any channel: `category` sets the 0x04 bit (channel opens), and
    /// `av` applies the AV message size limit (media).
    fn send_on(
        &mut self,
        channel: u8,
        category: bool,
        av: bool,
        message: Vec<u8>,
        encrypted: bool,
        now: Instant,
    ) -> bool {
        let frames = match fragment(channel, category, &message, av) {
            Ok(frames) => frames,
            Err(e) => {
                self.finish(Category::FrameInvalid, e.to_string(), now);
                return false;
            }
        };
        for mut frame in frames {
            if encrypted {
                let Some(tls) = self.tls.as_mut().filter(|t| t.established()) else {
                    let detail = "encrypted send without an established TLS session".into();
                    self.finish(Category::TlsError, detail, now);
                    return false;
                };
                match tls.encrypt(&frame.payload) {
                    Ok(ciphertext) => frame.payload = ciphertext,
                    Err(failure) => {
                        self.tls_failure(failure, now);
                        return false;
                    }
                }
                frame.header.flags |= FLAG_ENCRYPTED;
                self.report.counters.encrypted_frames_out += 1;
            }
            match frame.encode() {
                Ok(bytes) => {
                    self.output.extend_from_slice(&bytes);
                    self.queued += bytes.len() as u64;
                }
                Err(e) => {
                    self.finish(Category::FrameInvalid, e.to_string(), now);
                    return false;
                }
            }
        }
        self.report.counters.messages_out += 1;
        let unwritten = self.pending_output().len();
        if unwritten > self.config.output_cap && self.report.outcome.is_none() {
            let detail = format!(
                "{unwritten} bytes of output unwritten (cap {}): the head unit stopped reading",
                self.config.output_cap
            );
            self.finish(Category::IoError, detail, now);
            return false;
        }
        true
    }

    /// Records that everything queued so far must be written for `marker`.
    fn mark(&mut self, marker: Marker) {
        self.markers.push_back((self.queued, marker));
    }

    fn complete(&mut self, stage: Stage) {
        if self.report.last_completed.is_none_or(|last| last < stage) {
            self.report.last_completed = Some(stage);
        }
    }

    fn elapsed(&self, now: Instant) -> Duration {
        now.saturating_duration_since(self.started)
    }

    fn finish(&mut self, category: Category, detail: String, now: Instant) {
        self.finish_os(category, detail, None, now)
    }

    fn finish_os(&mut self, category: Category, detail: String, os: Option<i32>, now: Instant) {
        if self.report.outcome.is_some() {
            return;
        }
        self.report.outcome = Some(Outcome {
            category,
            stage: self.report.stage,
            at: self.elapsed(now),
            detail,
            os_error: os,
        });
        self.stage_deadline = None;
        self.hold_deadline = None;
        self.next_ping = None;
        self.video_stopped(category.name(), now);
        if !self.pending_output().is_empty() {
            if category.flushes() {
                self.linger_deadline = Some(now + self.config.linger);
            } else {
                self.close();
            }
        }
    }
}
