//! Sans-IO TLS, authentication, discovery, keepalive, and shutdown against the test head
//! unit (a plain OpenSSL client with its own identity). TLS records travel inside AA
//! 0x0003 messages and encrypted frames exactly as on the wire.
use aap_proto::{
    decode, encode_message, ids,
    messages::{PingResponse, ServiceDiscoveryRequest, ServiceDiscoveryResponse},
};
use aap_session::{
    discovery::{self, VideoPreference},
    Category, Config, KeepaliveProfile, Session, Stage, StopAfter,
};
use aap_tls::{identity::hex, Identity, PeerPolicy, ServerConfig, TlsProfile};
use aap_wire::Parser;
use hu_sim::{Auth, HeadUnit};
use prost::Message as _;
use std::{
    sync::{Arc, OnceLock},
    time::{Duration, Instant},
};

const UUID: &str = "0f6b3a52-9a0c-4c1b-8d7e-2f7a1b3c4d5e";

fn identity() -> Arc<Identity> {
    static IDENTITY: OnceLock<Arc<Identity>> = OnceLock::new();
    IDENTITY
        .get_or_init(|| Arc::new(Identity::generate().unwrap()))
        .clone()
}

fn config(peer: PeerPolicy) -> Config {
    Config {
        stop_after: StopAfter::Discovery,
        tls: Some(ServerConfig {
            profile: TlsProfile::Aead,
            identity: identity(),
            peer,
        }),
        session_uuid: UUID.into(),
        hold: Duration::from_secs(10),
        ..Config::default()
    }
}

fn category(s: &Session) -> Option<Category> {
    s.report().outcome.as_ref().map(|o| o.category)
}

fn detail(s: &Session) -> String {
    s.report()
        .outcome
        .as_ref()
        .map_or(String::new(), |o| o.detail.clone())
}

/// Exchanges bytes until neither side has output. `chunk` bounds each delivery to the
/// session, splitting frames and TLS records arbitrarily.
fn pump_chunked(s: &mut Session, hu: &mut HeadUnit, now: Instant, chunk: usize) {
    loop {
        let out = s.pending_output().to_vec();
        if !out.is_empty() {
            s.output_written(out.len(), now);
            hu.receive(&out);
        }
        let back = hu.take_output();
        for part in back.chunks(chunk) {
            s.receive(part, now);
        }
        if out.is_empty() && back.is_empty() {
            return;
        }
    }
}

fn pump(s: &mut Session, hu: &mut HeadUnit, now: Instant) {
    pump_chunked(s, hu, now, usize::MAX);
}

/// A session and head unit that have completed discovery.
fn ready(hu_config: hu_sim::Config) -> (Session, HeadUnit, Instant) {
    let (s, hu, t0) = ready_with(hu_config, |_| {});
    assert_eq!(s.report().stage, Stage::Ready, "{}", s.report());
    (s, hu, t0)
}

fn ready_with(
    hu_config: hu_sim::Config,
    tweak: impl FnOnce(&mut Config),
) -> (Session, HeadUnit, Instant) {
    let t0 = Instant::now();
    let mut hu = HeadUnit::new(hu_config);
    let mut cfg = config(PeerPolicy::Pinned(vec![hu.fingerprint]));
    tweak(&mut cfg);
    let mut s = Session::new(cfg, 5, t0);
    hu.start();
    pump(&mut s, &mut hu, t0);
    (s, hu, t0)
}

#[test]
fn full_handshake_reaches_capabilities_then_hold_ends_with_bye_bye() {
    let (mut s, mut hu, t0) = ready(hu_sim::Config::default());
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);
    assert_eq!(
        *hu.source_fingerprint.lock().unwrap(),
        Some(identity().fingerprint())
    );
    let r = s.report();
    assert_eq!(r.last_completed, Some(Stage::Discovery));
    assert_eq!((r.auth_status, r.auth_encrypted), (Some(0), false));
    let caps = r.capabilities.as_ref().unwrap();
    assert!(caps.usable());
    let video = caps.video.unwrap();
    assert_eq!(
        (
            video.channel,
            video.width,
            video.height,
            video.fps,
            video.config_index
        ),
        (3, 1280, 720, 30, 0)
    );
    let input = caps.input.clone().unwrap();
    assert_eq!(
        (input.channel, input.touch, input.keycodes),
        (1, Some((800, 480)), 6)
    );
    // The `media_info` channel is advertised and recorded, whether or not it is used.
    assert_eq!(caps.media_info.map(|m| m.channel), Some(10));
    // So is `navigation`, and its descriptor is configuration rather than a marker: the
    // update floor and the presentation are read out of it, and the raw enum value is
    // kept rather than the schema's name for it (P6.1).
    let nav = caps.navigation.expect("navigation advertised");
    assert_eq!(
        (nav.channel, nav.minimum_interval_ms, nav.nav_type),
        (11, 300, 1)
    );
    let image = nav.image.expect("image options advertised");
    assert_eq!(
        (image.height, image.width, image.colour_depth_bits),
        (64, 96, 16)
    );
    assert!(r.tls.as_ref().unwrap().established);

    // Exactly one encrypted discovery request with the neutral identity and session UUID.
    let requests: Vec<_> = hu
        .received
        .iter()
        .filter(|m| m.id == ids::SERVICE_DISCOVERY_REQUEST)
        .collect();
    assert_eq!(requests.len(), 1);
    assert!(requests[0].encrypted);
    let request: ServiceDiscoveryRequest = decode(&requests[0].bytes[2..]).unwrap();
    assert_eq!(request.device_name.as_deref(), Some(discovery::DEVICE_NAME));
    assert_eq!(
        request.session_info.unwrap().session_uuid.as_deref(),
        Some(UUID)
    );
    assert!(request.phone_icon_small.is_none());
    // Everything after the handshake except the baseline plaintext exceptions is encrypted.
    for m in &hu.received {
        let plain_allowed = [
            ids::VERSION_RESPONSE,
            ids::SSL_HANDSHAKE,
            ids::PING_REQUEST,
            ids::PING_RESPONSE,
        ];
        assert_eq!(
            m.encrypted,
            !plain_allowed.contains(&m.id),
            "message 0x{:04x}",
            m.id
        );
    }

    // Deadlines in the hold: the next own ping, before the hold ends.
    assert_eq!(s.next_deadline(), Some(t0 + Duration::from_secs(2)));
    let hold_end = t0 + Duration::from_secs(10);
    s.on_timer(hold_end);
    assert_eq!(s.report().stage, Stage::Shutdown);
    pump(&mut s, &mut hu, hold_end);
    assert_eq!(category(&s), Some(Category::HoldComplete), "{}", detail(&s));
    assert!(detail(&s).contains("ByeByeResponse received"));
    assert!(hu.ids().contains(&ids::SHUTDOWN_REQUEST));
    assert!(s.finished());

    let text = s.report().to_string();
    for line in [
        "stop_after=discovery",
        "last_completed_stage=discovery",
        "auth_status=0",
        "auth_status_name=OK",
        "discovery_capabilities=usable",
        "discovery_usable_video=channel=3 display=0 config=0 1280x720@30",
        "discovery_usable_input=channel=1 display=0 touch=800x480 keycodes=6",
        "discovery_channel_003=video codec=3 display=0 display_type=0 configs=1280x720@30,800x480@30",
        // The media_info descriptor is an empty message, so it is recognised by which
        // service field is set rather than by anything inside it.
        "discovery_channel_010=media_info now-playing",
        "discovery_media_info=channel=10",
        // Named by field rather than as WxH: the pinned schema puts height at tag 1 and
        // the reference tree transcribes the same APK class the other way round, so the
        // order is flagged instead of being silently chosen.
        "discovery_channel_011=navigation min_interval_ms=300 type=1 (TURN_BY_TURN) \
image=height=64 width=96 depth=16 (field order unverified)",
        "discovery_navigation=channel=11 min_interval_ms=300 type=1 (TURN_BY_TURN) \
image=height=64 width=96 depth=16 (field order unverified)",
        "hu_manufacturer=Lab Maker",
        "hu_sw_version=1.2.3",
        "hu_car_serial=redacted(16 chars)",
        "hu_name=redacted(16 chars)",
        "tls_version=TLSv1.2",
        "final_category=HOLD_COMPLETE",
    ] {
        assert!(text.lines().any(|l| l == line), "missing {line} in\n{text}");
    }
    assert!(text.contains(&format!(
        "tls_peer_decision=pin-match {}",
        hex(&hu.fingerprint)
    )));
    for secret in [
        "VINLIKE",
        "5813",
        "00:11:22",
        "VEHICLE-ID",
        "31337",
        "PRIVATE KEY",
    ] {
        assert!(!text.contains(secret), "{secret} leaked into\n{text}");
    }
}

#[test]
fn raw_discovery_capture_is_opt_in_and_faithful() {
    let t0 = Instant::now();
    let mut hu = HeadUnit::new(hu_sim::Config::default());
    let mut cfg = config(PeerPolicy::Pinned(vec![hu.fingerprint]));
    cfg.capture_discovery_response = true;
    let mut s = Session::new(cfg, 5, t0);
    hu.start();
    pump(&mut s, &mut hu, t0);
    assert_eq!(s.report().stage, Stage::Ready, "{}", s.report());
    let r = s.report();

    // The captured bytes are exactly the discovery response message, whole (not truncated
    // for this small response), and are the SERVICE_DISCOVERY_RESPONSE message id + body.
    let len = r.discovery_response_len.unwrap();
    assert_eq!(r.discovery_response_prefix.len(), len);
    assert!(len < aap_session::DISCOVERY_RECORD_LIMIT);
    assert_eq!(
        &r.discovery_response_prefix[..2],
        &ids::SERVICE_DISCOVERY_RESPONSE.to_be_bytes()
    );
    let decoded: ServiceDiscoveryResponse = decode(&r.discovery_response_prefix[2..]).unwrap();
    assert!(!decoded.channels.is_empty());

    // The report prints the raw bytes as hex, and that is where the otherwise-redacted
    // head-unit identity now appears in the clear (the point of the sensitivity warning).
    let text = r.to_string();
    let hex_line = format!(
        "discovery_response_hex={}",
        hex(&r.discovery_response_prefix)
    );
    assert!(
        text.lines().any(|l| l == hex_line),
        "missing raw hex in\n{text}"
    );

    // Same session with capture off: the field stays empty and nothing leaks.
    let (plain, ..) = ready(hu_sim::Config::default());
    assert!(plain.report().discovery_response_prefix.is_empty());
    assert!(plain
        .report()
        .to_string()
        .lines()
        .any(|l| l == "discovery_response_hex=-"));
}

/// The first two channel descriptors (video 1, input 2) of the Ford SYNC 4.0 discovery
/// response in report c1265b54, byte for byte. Only these are kept: they hold no names,
/// serials, vehicle id, or addresses. The AV channel has no stream type; each of its three
/// VideoConfigs names H.264 (3) in its codec field instead.
const FORD_VIDEO_AND_INPUT: &str = concat!(
    "0a7e08011a7a22270803100118ca0420c60128f001300038b60740a44e48be0150035a0b",
    "0a0908631063180020ca0422260802100118860320840128a001300038b60740a44e487f",
    "50035a0b0a09084210421800208603222308011001184a2000288301300038b60740a44e",
    "486750035a0a0a080800100018252025300038000a230802221f0a0f545557587e7f8180",
    "04828004848004120a08800f10f206180120002800",
);

fn unhex(text: &str) -> Vec<u8> {
    (0..text.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&text[i..i + 2], 16).unwrap())
        .collect()
}

/// Ford advertises three configurations and displays only the smallest: it acknowledges
/// every unit of 1920x1080@60 and holds projected focus while showing nothing (reports
/// e9326eea, 23f532aa, c6cb973c). Nothing on the wire says so, so the operator picks.
#[test]
fn the_video_preference_chooses_among_advertised_configurations() {
    let ford = || hu_sim::Config {
        discovery_response: Some(unhex(FORD_VIDEO_AND_INPUT)),
        ..hu_sim::Config::default()
    };
    for (preference, expected) in [
        (
            VideoPreference::Largest,
            "channel=1 display=0 config=0 1920x1080@60",
        ),
        (
            VideoPreference::Smallest,
            "channel=1 display=0 config=2 800x480@60",
        ),
        (
            VideoPreference::Pinned(1280, 720),
            "channel=1 display=0 config=1 1280x720@60",
        ),
        (
            VideoPreference::Pinned(800, 480),
            "channel=1 display=0 config=2 800x480@60",
        ),
    ] {
        let (s, ..) = ready_with(ford(), |c| c.video_preference = preference);
        let caps = s.report().capabilities.clone().unwrap();
        assert_eq!(
            caps.video.unwrap().to_string(),
            expected,
            "preference {preference}"
        );
        assert_eq!(caps.issue_count, 0, "preference {preference}");
        // Every usable configuration stays a candidate whatever is preferred, so the
        // head unit can still narrow the choice in its setup response.
        assert_eq!(caps.video_candidates.len(), 3, "preference {preference}");
        assert!(s
            .report()
            .to_string()
            .lines()
            .any(|l| l == format!("video_preference={preference}")));
    }

    // A size the head unit does not advertise leaves no usable video, and says which
    // sizes there were rather than quietly taking one the operator did not ask for.
    let (s, ..) = ready_with(ford(), |c| {
        c.video_preference = VideoPreference::Pinned(640, 480)
    });
    let caps = s.report().capabilities.clone().unwrap();
    assert!(caps.video.is_none());
    assert!(!caps.usable());
    assert_eq!(
        caps.issues,
        vec![
            "no advertised configuration has the pinned 640x480 frame size; \
             usable sizes are 1920x1080,1280x720,800x480",
            "no usable H.264 main-display video configuration",
        ]
    );
    let text = s.report().to_string();
    for line in [
        "video_preference=640x480",
        "discovery_usable_video=-",
        "discovery_capabilities=insufficient",
    ] {
        assert!(text.lines().any(|l| l == line), "missing {line} in\n{text}");
    }
}

#[test]
fn video_preference_names_round_trip_and_reject_nonsense() {
    for (name, expected) in [
        ("largest", Some(VideoPreference::Largest)),
        ("smallest", Some(VideoPreference::Smallest)),
        ("800x480", Some(VideoPreference::Pinned(800, 480))),
        ("1920x1080", Some(VideoPreference::Pinned(1920, 1080))),
        ("16384x16384", Some(VideoPreference::Pinned(16384, 16384))),
        ("16385x480", None),
        ("0x480", None),
        ("800x", None),
        ("800", None),
        ("-800x480", None),
        ("", None),
    ] {
        assert_eq!(VideoPreference::from_name(name), expected, "{name:?}");
        if let Some(preference) = expected {
            assert_eq!(preference.name(), name, "{name:?} does not round trip");
        }
    }
}

#[test]
fn video_named_only_by_config_codecs_is_usable() {
    let ford = unhex(FORD_VIDEO_AND_INPUT);
    let (s, ..) = ready(hu_sim::Config {
        discovery_response: Some(ford.clone()),
        ..hu_sim::Config::default()
    });
    let text = s.report().to_string();
    let caps = s.report().capabilities.as_ref().unwrap();
    assert!(caps.usable(), "{text}");
    assert_eq!(caps.issue_count, 0, "{text}");
    assert_eq!(
        caps.video.unwrap().to_string(),
        "channel=1 display=0 config=0 1920x1080@60"
    );
    assert_eq!(
        caps.input.as_ref().unwrap().to_string(),
        "channel=2 display=0 touch=1920x882 keycodes=9"
    );
    assert!(
        text.lines().any(|l| l
            == "discovery_channel_001=video codec=per-config display=0 display_type=0 \
                configs=1920x1080@60,1280x720@60,800x480@60"),
        "{text}"
    );

    // With neither a stream type nor a video codec the channel stays unusable.
    let mut response = ServiceDiscoveryResponse::decode(ford.as_slice()).unwrap();
    let av = response.channels[0].av_channel.as_mut().unwrap();
    for config in &mut av.video_configs {
        config.codec = None;
    }
    let (s, ..) = ready(hu_sim::Config {
        discovery_response: Some(response.encode_to_vec()),
        ..hu_sim::Config::default()
    });
    let caps = s.report().capabilities.as_ref().unwrap();
    assert!(!caps.usable());
    assert!(
        caps.issues.iter().any(
            |i| i == "av channel 1 has no stream type and no video codec in its configurations"
        ),
        "{:?}",
        caps.issues
    );
}

#[test]
fn transport_close_after_shutdown_request_completes_the_hold() {
    let (mut s, _hu, t0) = ready(hu_sim::Config {
        answer_shutdown: false,
        ..hu_sim::Config::default()
    });
    s.on_timer(t0 + Duration::from_secs(10));
    s.on_transport_closed(None, t0 + Duration::from_secs(10));
    assert_eq!(category(&s), Some(Category::HoldComplete));
    assert!(
        detail(&s).contains("transport closed before a ByeByeResponse"),
        "{}",
        detail(&s)
    );
    assert!(s.finished());

    // Before shutdown, the same close is an I/O outcome.
    let (mut s, _hu, t0) = ready(hu_sim::Config::default());
    s.on_transport_closed(None, t0);
    assert_eq!(category(&s), Some(Category::IoEof));
}

#[test]
fn byte_at_a_time_delivery_completes_the_same_handshake() {
    let t0 = Instant::now();
    let mut hu = HeadUnit::new(hu_sim::Config::default());
    let mut s = Session::new(config(PeerPolicy::Pinned(vec![hu.fingerprint])), 1, t0);
    hu.start();
    pump_chunked(&mut s, &mut hu, t0, 1);
    assert_eq!(
        s.report().last_completed,
        Some(Stage::Discovery),
        "{}",
        s.report()
    );
}

#[test]
fn records_spanning_frames_are_reassembled() {
    let (s, _hu, _) = ready(hu_sim::Config {
        split_ciphertext: Some(7),
        auth: Auth::Encrypted(0),
        ..hu_sim::Config::default()
    });
    let r = s.report();
    assert!(r.counters.split_record_frames > 10, "{:?}", r.counters);
    assert!(r.auth_encrypted);
}

#[test]
fn plaintext_frame_inside_a_split_record_is_rejected() {
    let t0 = Instant::now();
    let mut hu = HeadUnit::new(hu_sim::Config {
        split_ciphertext: Some(5),
        ..hu_sim::Config::default()
    });
    let mut s = Session::new(config(PeerPolicy::Pinned(vec![hu.fingerprint])), 1, t0);
    hu.start();
    // Run until the discovery request is out, then intercept the response frames.
    loop {
        let out = s.pending_output().to_vec();
        s.output_written(out.len(), t0);
        hu.receive(&out);
        let back = hu.take_output();
        if hu.ids().contains(&ids::SERVICE_DISCOVERY_REQUEST) {
            let mut parser = Parser::new();
            let (used, frame) = parser.consume(&back).unwrap();
            assert!(frame.unwrap().header.encrypted());
            s.receive(&back[..used], t0);
            assert_eq!(category(&s), None);
            let ping = encode_message(
                ids::PING_REQUEST,
                &aap_proto::messages::PingRequest {
                    timestamp: 1,
                    bugreport_request: None,
                    payload: None,
                },
            );
            s.receive(&plain_frame(ping), t0);
            break;
        }
        s.receive(&back, t0);
    }
    assert_eq!(category(&s), Some(Category::FrameInvalid), "{}", detail(&s));
    assert!(detail(&s).contains("inside an incomplete TLS record"));
}

#[test]
fn split_record_plaintext_is_bounded() {
    let (mut s, mut hu, t0) = ready_with(hu_sim::Config::default(), |c| {
        c.hold = Duration::from_secs(60)
    });
    // 16 KiB records cut every 40,000 ciphertext bytes: frames never end on a boundary,
    // so without a bound the unit would keep growing past the 64 KiB the FIRST claims.
    hu.send_unaligned_records(0, 16 * 1024, 12, 40_000, 64 * 1024);
    s.receive(&hu.take_output(), t0);
    assert_eq!(category(&s), Some(Category::FrameInvalid), "{}", detail(&s));
    assert!(
        detail(&s).contains("exceed 65536 plaintext bytes"),
        "{}",
        detail(&s)
    );
}

#[test]
fn split_record_plaintext_is_bounded_without_a_total() {
    let (mut s, mut hu, t0) = ready_with(hu_sim::Config::default(), |c| {
        c.hold = Duration::from_secs(60)
    });
    // A FIRST claiming more than any message may be: the unit stops at the largest
    // message the assembler accepts rather than at the claim.
    let records = aap_wire::AV_LIMIT / (16 * 1024) + 2;
    hu.send_unaligned_records(0, 16 * 1024, records, 40_000, u32::MAX);
    s.receive(&hu.take_output(), t0);
    assert_eq!(category(&s), Some(Category::FrameInvalid), "{}", detail(&s));
    assert!(
        detail(&s).contains(&format!("exceed {} plaintext bytes", aap_wire::AV_LIMIT)),
        "{}",
        detail(&s)
    );
}

#[test]
fn split_record_left_incomplete_expires() {
    let (mut s, mut hu, t0) = ready_with(hu_sim::Config::default(), |c| {
        c.hold = Duration::from_secs(60)
    });
    // One record in frames of 40 ciphertext bytes; every frame but the last ends inside
    // the record, so hold the last back.
    hu.send_unaligned_records(0, 100, 1, 40, 4096);
    let out = hu.take_output();
    let mut parser = Parser::new();
    let mut held = 0;
    while held < out.len() {
        let (used, _) = parser.consume(&out[held..]).unwrap();
        if held + used == out.len() {
            break;
        }
        held += used;
    }
    s.receive(&out[..held], t0);
    assert_eq!(category(&s), None, "{}", detail(&s));
    let expiry = t0 + aap_wire::ASSEMBLY_LIFETIME;
    assert!(s.next_deadline().is_some_and(|d| d <= expiry));
    s.on_timer(expiry);
    assert_eq!(category(&s), Some(Category::FrameInvalid), "{}", detail(&s));
    assert!(
        detail(&s).contains("not completed within 10 s"),
        "{}",
        detail(&s)
    );
}

#[test]
fn output_a_head_unit_never_reads_is_capped() {
    let (mut s, mut hu, t0) = ready_with(hu_sim::Config::default(), |c| {
        c.hold = Duration::from_secs(60);
        c.output_cap = 4096;
    });
    assert!(s.pending_output().is_empty());
    // Pings keep coming while nothing the session queues is written.
    for timestamp in 0..2000 {
        let ping = encode_message(
            ids::PING_REQUEST,
            &aap_proto::messages::PingRequest {
                timestamp,
                bugreport_request: None,
                payload: None,
            },
        );
        hu.send_raw(0, false, true, ping);
        s.receive(&hu.take_output(), t0);
        if category(&s).is_some() {
            break;
        }
    }
    assert_eq!(category(&s), Some(Category::IoError), "{}", detail(&s));
    assert!(
        detail(&s).contains("the head unit stopped reading"),
        "{}",
        detail(&s)
    );
}

/// Wraps a complete plaintext control message in one channel-0 frame.
fn plain_frame(message: Vec<u8>) -> Vec<u8> {
    let mut frame = vec![0, 3];
    frame.extend((message.len() as u16).to_be_bytes());
    frame.extend(message);
    frame
}

#[test]
fn keepalive_answers_head_unit_pings_and_detects_silence() {
    let (mut s, mut hu, t0) = ready(hu_sim::Config {
        ping_after_discovery: true,
        ..hu_sim::Config::default()
    });
    let responses: Vec<PingResponse> = hu
        .received
        .iter()
        .filter(|m| m.id == ids::PING_RESPONSE)
        .map(|m| {
            assert!(!m.encrypted, "a plaintext ping is answered plaintext");
            PingResponse::decode(&m.bytes[2..]).unwrap()
        })
        .collect();
    assert_eq!(responses.len(), 1);
    assert_eq!(
        responses[0].timestamp, -1_234_567_890_123,
        "timestamp echoed exactly"
    );
    let k = &s.report().keepalive;
    assert_eq!(
        (k.hu_requests, k.hu_bugreport_flags, k.responses_written),
        (1, 1, 1)
    );

    // Own pings after the head unit has been quiet, answered by the simulator.
    let t1 = t0 + Duration::from_secs(2);
    s.on_timer(t1);
    pump(&mut s, &mut hu, t1);
    let k = &s.report().keepalive;
    assert_eq!(
        (k.own_requests, k.own_responses, k.own_unmatched),
        (1, 1, 0)
    );
    assert_eq!(category(&s), None);

    // A silent head unit: three unanswered pings, then failure at the next due ping.
    let (mut s, _hu, t0) = ready(hu_sim::Config {
        answer_pings: false,
        ..hu_sim::Config::default()
    });
    for i in 1..=3 {
        s.on_timer(t0 + Duration::from_secs(2 * i));
        assert_eq!(category(&s), None);
        let written = s.pending_output().len();
        s.output_written(written, t0 + Duration::from_secs(2 * i));
    }
    s.on_timer(t0 + Duration::from_secs(8));
    assert_eq!(
        category(&s),
        Some(Category::KeepaliveTimeout),
        "{}",
        detail(&s)
    );
    assert_eq!(s.report().keepalive.own_outstanding_max, 3);
    assert_eq!(s.report().keepalive.own_requests_encrypted, 3);
}

#[test]
fn keepalive_mirrors_head_unit_ping_encryption_unless_overridden() {
    /// The Ford SYNC 4 model: encrypted pings only.
    fn ford(ping_first: bool) -> hu_sim::Config {
        hu_sim::Config {
            ping_after_discovery: ping_first,
            encrypted_pings: true,
            ..hu_sim::Config::default()
        }
    }
    fn start(hu_config: hu_sim::Config, profile: KeepaliveProfile) -> (Session, HeadUnit, Instant) {
        let t0 = Instant::now();
        let mut hu = HeadUnit::new(hu_config);
        let mut cfg = config(PeerPolicy::Pinned(vec![hu.fingerprint]));
        cfg.keepalive = profile;
        let mut s = Session::new(cfg, 5, t0);
        hu.start();
        pump(&mut s, &mut hu, t0);
        assert_eq!(s.report().stage, Stage::Ready, "{}", s.report());
        (s, hu, t0)
    }
    /// Four own-ping intervals: enough for three misses and the failing fourth.
    fn hold(s: &mut Session, hu: &mut HeadUnit, t0: Instant) {
        for i in 1..=4 {
            let t = t0 + Duration::from_secs(2 * i);
            s.on_timer(t);
            pump(s, hu, t);
        }
    }
    /// Encryption of each ping message the head unit received, in order.
    fn pings(hu: &HeadUnit) -> Vec<bool> {
        hu.received
            .iter()
            .filter(|m| m.id == ids::PING_REQUEST || m.id == ids::PING_RESPONSE)
            .map(|m| m.encrypted)
            .collect()
    }
    fn has_line(s: &Session, line: &str) -> bool {
        s.report().to_string().lines().any(|l| l == line)
    }

    // Mirror, the default, after an encrypted head-unit ping: the response and own pings
    // are encrypted and answered (report c2407cff).
    assert_eq!(Config::default().keepalive, KeepaliveProfile::Mirror);
    let (mut s, mut hu, t0) = start(ford(true), KeepaliveProfile::Mirror);
    hold(&mut s, &mut hu, t0);
    assert_eq!(category(&s), None, "{}", detail(&s));
    let k = &s.report().keepalive;
    assert_eq!(
        (
            k.hu_requests_encrypted,
            k.responses_written,
            k.responses_encrypted
        ),
        (1, 1, 1)
    );
    assert_eq!(
        (k.own_requests, k.own_requests_encrypted, k.own_responses),
        (4, 4, 4)
    );
    assert_eq!(pings(&hu), [true; 5]);
    assert!(has_line(&s, "keepalive_profile=mirror"));

    // The plaintext override does what 0.2.2-lab did on the Ford: silence, then timeout
    // (report c1265b54).
    let (mut s, mut hu, t0) = start(ford(true), KeepaliveProfile::Plaintext);
    hold(&mut s, &mut hu, t0);
    assert_eq!(
        category(&s),
        Some(Category::KeepaliveTimeout),
        "{}",
        detail(&s)
    );
    assert_eq!(pings(&hu), [false; 4]);
    assert!(has_line(&s, "keepalive_profile=plaintext"));

    // The Ford projection attempts sent no head-unit ping. Default to encrypted
    // after TLS so keepalive still works without that initial message.
    let (mut s, mut hu, t0) = start(ford(false), KeepaliveProfile::Mirror);
    hold(&mut s, &mut hu, t0);
    assert_eq!(category(&s), None, "{}", detail(&s));
    assert_eq!(s.report().keepalive.own_requests_encrypted, 4);
    assert_eq!(s.report().keepalive.own_responses, 4);
    assert_eq!(pings(&hu), [true; 4]);
    let (mut s, mut hu, t0) = start(ford(false), KeepaliveProfile::Encrypted);
    hold(&mut s, &mut hu, t0);
    assert_eq!(category(&s), None, "{}", detail(&s));
    assert_eq!(pings(&hu), [true; 4]);
    assert!(has_line(&s, "keepalive_profile=encrypted"));

    // Receiving a plaintext ping still overrides the encrypted initial default.
    let (mut s, mut hu, t0) = start(
        hu_sim::Config {
            ping_after_discovery: true,
            ..hu_sim::Config::default()
        },
        KeepaliveProfile::Mirror,
    );
    hold(&mut s, &mut hu, t0);
    assert_eq!(category(&s), None, "{}", detail(&s));
    assert_eq!(pings(&hu), [false; 5]);
}

#[test]
fn authentication_must_follow_tls_and_report_success() {
    let run = |auth| {
        let t0 = Instant::now();
        let mut hu = HeadUnit::new(hu_sim::Config {
            auth,
            ..hu_sim::Config::default()
        });
        let mut s = Session::new(config(PeerPolicy::FirstUse), 1, t0);
        hu.start();
        pump(&mut s, &mut hu, t0);
        (s, hu, t0)
    };
    let (s, _, _) = run(Auth::Plain(-3));
    assert_eq!(category(&s), Some(Category::AuthFailed));
    assert!(
        detail(&s).contains("-3 (AUTHENTICATION_FAILURE)"),
        "{}",
        detail(&s)
    );
    assert_eq!(s.report().last_completed, Some(Stage::Tls));

    let (s, _, _) = run(Auth::MissingStatus);
    assert_eq!(category(&s), Some(Category::AuthFailed));
    assert!(
        detail(&s).contains("status: required field missing"),
        "{}",
        detail(&s)
    );

    let (s, _, _) = run(Auth::Premature);
    assert_eq!(category(&s), Some(Category::AuthFailed));
    assert!(
        detail(&s).contains("before local TLS completion"),
        "{}",
        detail(&s)
    );
    assert_eq!(s.report().last_completed, Some(Stage::Version));

    let (mut s, hu, t0) = run(Auth::Never);
    assert_eq!(s.report().stage, Stage::Auth);
    assert!(!hu.ids().contains(&ids::SERVICE_DISCOVERY_REQUEST));
    let deadline = s.next_deadline().unwrap();
    assert_eq!(deadline, t0 + Duration::from_secs(10));
    s.on_timer(deadline);
    assert_eq!(category(&s), Some(Category::AuthTimeout));
}

#[test]
fn tls_completion_does_not_imply_source_identity_acceptance() {
    let cfg = config(PeerPolicy::FirstUse);
    let identity = cfg.tls.as_ref().unwrap().identity.clone();
    for trusted in [false, true] {
        let mut hu = HeadUnit::new(hu_sim::Config {
            request_version: (1, 6),
            auth: Auth::VerifySource,
            source_root: trusted.then(|| identity.certificate().clone()),
            ..Default::default()
        });
        let mut cfg = config(PeerPolicy::FirstUse);
        cfg.tls.as_mut().unwrap().identity = identity.clone();
        cfg.profile = aap_session::version::Profile::Legacy15AnyMinor;
        let t0 = Instant::now();
        let mut s = Session::new(cfg, 13, t0);
        hu.start();
        pump(&mut s, &mut hu, t0);
        assert!(hu.tls_done());
        assert!(s.report().tls_complete_at.is_some());
        assert!(hu.errors.is_empty(), "{:?}", hu.errors);
        if trusted {
            assert_eq!(s.report().auth_status, Some(0));
            assert_eq!(s.report().last_completed, Some(Stage::Discovery));
        } else {
            assert_eq!(category(&s), Some(Category::AuthFailed));
            assert_eq!(s.report().auth_status, Some(-3));
            assert_eq!(s.report().last_completed, Some(Stage::Tls));
            assert!(!hu.ids().contains(&ids::SERVICE_DISCOVERY_REQUEST));
        }
    }
}

#[test]
fn stop_after_auth_never_sends_discovery() {
    let t0 = Instant::now();
    let mut hu = HeadUnit::new(hu_sim::Config::default());
    let mut s = Session::new(
        Config {
            stop_after: StopAfter::Auth,
            ..config(PeerPolicy::FirstUse)
        },
        1,
        t0,
    );
    hu.start();
    pump(&mut s, &mut hu, t0);
    assert_eq!(category(&s), Some(Category::StageLimit));
    assert_eq!(s.report().last_completed, Some(Stage::Auth));
    assert!(!hu.ids().contains(&ids::SERVICE_DISCOVERY_REQUEST));
    assert!(s
        .report()
        .to_string()
        .contains("tls_peer_decision=first-use-accepted"));
}

#[test]
fn discovery_response_must_be_encrypted_timely_and_valid() {
    let run = |hu_config: hu_sim::Config| {
        let t0 = Instant::now();
        let mut hu = HeadUnit::new(hu_config);
        let mut s = Session::new(config(PeerPolicy::FirstUse), 1, t0);
        hu.start();
        pump(&mut s, &mut hu, t0);
        (s, t0)
    };
    let (s, _) = run(hu_sim::Config {
        discovery_plaintext: true,
        ..hu_sim::Config::default()
    });
    assert_eq!(
        category(&s),
        Some(Category::UnexpectedMessage),
        "{}",
        detail(&s)
    );

    let (mut s, t0) = run(hu_sim::Config {
        discovery_response: None,
        ..hu_sim::Config::default()
    });
    assert_eq!(s.report().stage, Stage::Discovery);
    s.on_timer(t0 + Duration::from_secs(10));
    assert_eq!(category(&s), Some(Category::DiscoveryTimeout));

    let mut duplicate =
        ServiceDiscoveryResponse::decode(hu_sim::discovery_response().as_slice()).unwrap();
    duplicate.channels[1].channel_id = 3;
    let (s, _) = run(hu_sim::Config {
        discovery_response: Some(duplicate.encode_to_vec()),
        ..hu_sim::Config::default()
    });
    assert_eq!(category(&s), Some(Category::DiscoveryInvalid));
    assert!(detail(&s).contains("advertised twice"), "{}", detail(&s));

    // channels { } without the required channel_id.
    let (s, _) = run(hu_sim::Config {
        discovery_response: Some(vec![0x0a, 0x00]),
        ..hu_sim::Config::default()
    });
    assert_eq!(category(&s), Some(Category::DiscoveryInvalid));
    assert!(
        detail(&s).contains("channel_id: required field missing"),
        "{}",
        detail(&s)
    );

    // A decodable but empty response is accepted as insufficient, never as usable.
    let (s, _) = run(hu_sim::Config {
        discovery_response: Some(Vec::new()),
        ..hu_sim::Config::default()
    });
    assert_eq!(s.report().last_completed, Some(Stage::Discovery));
    let caps = s.report().capabilities.as_ref().unwrap();
    assert!(!caps.usable());
    assert!(s
        .report()
        .to_string()
        .contains("discovery_capabilities=insufficient"));
}

#[test]
fn unhandled_messages_after_tls_are_recorded_and_skipped() {
    let (s, _, _) = ready(hu_sim::Config {
        extra_after_auth: vec![(0, vec![0x00, 0x14]), (5, vec![0x80, 0x01, 0x08, 0x01])],
        ..hu_sim::Config::default()
    });
    let r = s.report();
    assert_eq!(r.unhandled_count, 2);
    assert_eq!((r.unhandled[1].channel, r.unhandled[1].id), (5, 0x8001));
    // AUTH_COMPLETE was processed first in the same batch, so the session is in discovery.
    assert!(r
        .to_string()
        .contains("unhandled_0=stage=discovery channel=0 flags=0x08 id=0x0014 len=2"));
}

#[test]
fn repeated_protocol_messages_never_repeat_initialization() {
    // A second AUTH_COMPLETE while discovery is pending is an error, not a new request.
    let t0 = Instant::now();
    let mut hu = HeadUnit::new(hu_sim::Config {
        extra_after_auth: vec![(0, vec![0x00, 0x04, 0x08, 0x00])],
        ..hu_sim::Config::default()
    });
    let mut s = Session::new(config(PeerPolicy::FirstUse), 1, t0);
    hu.start();
    pump(&mut s, &mut hu, t0);
    assert_eq!(
        category(&s),
        Some(Category::UnexpectedMessage),
        "{}",
        detail(&s)
    );
    assert!(detail(&s).contains("message 0x0004"), "{}", detail(&s));
    let requests = hu
        .ids()
        .iter()
        .filter(|id| **id == ids::SERVICE_DISCOVERY_REQUEST)
        .count();
    assert_eq!(requests, 1);

    // A second version request once the response is decided is also refused.
    let t0 = Instant::now();
    let mut hu = HeadUnit::new(hu_sim::Config::default());
    let mut s = Session::new(config(PeerPolicy::FirstUse), 1, t0);
    hu.start();
    let request = hu.take_output();
    s.receive(&request, t0);
    s.receive(&request, t0);
    assert_eq!(
        category(&s),
        Some(Category::UnexpectedMessage),
        "{}",
        detail(&s)
    );
}

#[test]
fn head_unit_shutdown_is_answered() {
    let t0 = Instant::now();
    let mut hu = HeadUnit::new(hu_sim::Config {
        shutdown_after_discovery: Some(7),
        ..hu_sim::Config::default()
    });
    let mut s = Session::new(config(PeerPolicy::FirstUse), 1, t0);
    hu.start();
    pump(&mut s, &mut hu, t0);
    assert_eq!(category(&s), Some(Category::PeerShutdown), "{}", detail(&s));
    assert!(
        detail(&s).contains("reason 7 (POWER_DOWN)"),
        "{}",
        detail(&s)
    );
    let bye = hu
        .received
        .iter()
        .find(|m| m.id == ids::SHUTDOWN_RESPONSE)
        .unwrap();
    assert!(bye.encrypted);
    assert_eq!(s.report().last_completed, Some(Stage::Discovery));
}

#[test]
fn tls_failures_are_classified_and_leave_version_complete() {
    let run = |hu_config: hu_sim::Config, pin_hu: bool| {
        let t0 = Instant::now();
        let mut hu = HeadUnit::new(hu_config);
        let peer = if pin_hu {
            PeerPolicy::Pinned(vec![hu.fingerprint])
        } else {
            PeerPolicy::Pinned(vec![[7; 32]])
        };
        let mut s = Session::new(config(peer), 1, t0);
        hu.start();
        pump(&mut s, &mut hu, t0);
        (s, hu)
    };
    let (s, hu) = run(hu_sim::Config::default(), false);
    assert_eq!(
        category(&s),
        Some(Category::TlsCertPolicy),
        "{}",
        detail(&s)
    );
    assert_eq!(s.report().last_completed, Some(Stage::Version));
    assert!(!hu.tls_done());
    assert!(!hu.errors.is_empty(), "the head unit received our alert");
    assert!(s.report().to_string().contains("tls_alerts=local:fatal:"));

    let (s, _) = run(
        hu_sim::Config {
            client_certificate: false,
            ..hu_sim::Config::default()
        },
        true,
    );
    assert_eq!(
        category(&s),
        Some(Category::TlsCertPolicy),
        "{}",
        detail(&s)
    );

    let (s, _) = run(
        hu_sim::Config {
            cipher_list: Some("AES128-SHA".into()),
            ..hu_sim::Config::default()
        },
        true,
    );
    assert_eq!(
        category(&s),
        Some(Category::TlsNoCommonSuite),
        "{}",
        detail(&s)
    );
    // The OpenSSL client also offers its TLS 1.3 suites, which a TLS 1.2 server ignores.
    assert!(s
        .report()
        .to_string()
        .contains("tls_client_hello_cipher_suites=1302,1303,1301,002f\n"));

    // A head unit that stops after its ClientHello times out in TLS.
    let t0 = Instant::now();
    let mut hu = HeadUnit::new(hu_sim::Config::default());
    let mut s = Session::new(config(PeerPolicy::FirstUse), 1, t0);
    hu.start();
    s.receive(&hu.take_output(), t0);
    let response = s.pending_output().to_vec();
    s.output_written(response.len(), t0);
    hu.receive(&response);
    s.receive(&hu.take_output(), t0);
    assert_eq!(s.report().stage, Stage::Tls);
    assert!(!s.pending_output().is_empty(), "server flight queued");
    let deadline = s.next_deadline().unwrap();
    assert_eq!(deadline, t0 + Duration::from_secs(10));
    s.on_timer(deadline);
    assert_eq!(category(&s), Some(Category::TlsTimeout));
    assert!(detail(&s).contains("TLS handshake incomplete"));
}

#[test]
fn missing_identity_beyond_version_is_a_tls_error() {
    let t0 = Instant::now();
    let mut hu = HeadUnit::new(hu_sim::Config::default());
    let mut s = Session::new(
        Config {
            stop_after: StopAfter::Discovery,
            ..Config::default()
        },
        1,
        t0,
    );
    hu.start();
    pump(&mut s, &mut hu, t0);
    assert_eq!(category(&s), Some(Category::TlsError));
}

#[test]
fn version_only_reports_the_client_hello_without_tls() {
    let t0 = Instant::now();
    let mut hu = HeadUnit::new(hu_sim::Config::default());
    let mut s = Session::new(Config::default(), 1, t0);
    hu.start();
    pump(&mut s, &mut hu, t0);
    assert_eq!(category(&s), Some(Category::StageLimit));
    let text = s.report().to_string();
    assert!(
        text.contains("tls_client_hello_offers_tls12=true"),
        "{text}"
    );
    assert!(text.contains("final_detail=stopped after version by configuration"));
}
