//! Projection (spec §8) against the test head unit: dynamic channel opens and routing,
//! AV setup, focus, flow control and congestion recovery, input mapping, sensors,
//! keepalive during setup, and shutdown. Media comes from the test-pattern encoder, so
//! parameter sets and IDRs are real H.264.
use aap_proto::{
    decode, encode_message, ids,
    messages::{
        AvChannelSetupRequest, AvChannelStartIndication, BindingRequest, ChannelOpenRequest,
        ChannelOpenResponse, VideoFocusRequest,
    },
};
use aap_session::{
    discovery::VideoPreference,
    input::TouchSpace,
    media::{self, BufferFlags, Submit},
    navigation::{Guidance, Leg, RouteState, Script, Source as NavSource},
    now_playing::{NowPlaying, PlaybackState},
    projection::{AppEvent, AudioStart, SensorSet, SetupField},
    Category, Config, Session, Stage, StopAfter,
};
use aap_tls::{Identity, PeerPolicy, ServerConfig, TlsProfile};
use hu_sim::{Ack, Cluster, Focus, HeadUnit, NavFocus, Open};
use std::{
    os::fd::OwnedFd,
    sync::{Arc, OnceLock},
    time::{Duration, Instant},
};
use test_pattern::{Canvas, Encoder, Yuv};

const UUID: &str = "0f6b3a52-9a0c-4c1b-8d7e-2f7a1b3c4d5e";
const CONFIG: BufferFlags = BufferFlags {
    codec_config: true,
    key_frame: false,
    partial_frame: false,
};

fn identity() -> Arc<Identity> {
    static IDENTITY: OnceLock<Arc<Identity>> = OnceLock::new();
    IDENTITY
        .get_or_init(|| Arc::new(Identity::generate().unwrap()))
        .clone()
}

fn config(hu: &HeadUnit) -> Config {
    Config {
        stop_after: StopAfter::Projection,
        tls: Some(ServerConfig {
            profile: TlsProfile::Aead,
            identity: identity(),
            peer: PeerPolicy::Pinned(vec![hu.fingerprint]),
        }),
        session_uuid: UUID.into(),
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

fn pump(s: &mut Session, hu: &mut HeadUnit, now: Instant) {
    loop {
        s.on_timer(now);
        let out = s.pending_output().to_vec();
        if !out.is_empty() {
            s.output_written(out.len(), now);
            hu.receive(&out);
        }
        let back = hu.take_output();
        if !back.is_empty() {
            s.receive(&back, now);
        }
        if out.is_empty() && back.is_empty() {
            return;
        }
    }
}

fn start(
    hu_config: hu_sim::Config,
    tweak: impl FnOnce(&mut Config),
) -> (Session, HeadUnit, Instant) {
    let t0 = Instant::now();
    let mut hu = HeadUnit::new(hu_config);
    let mut cfg = config(&hu);
    tweak(&mut cfg);
    let mut s = Session::new(cfg, 9, t0);
    hu.start();
    pump(&mut s, &mut hu, t0);
    (s, hu, t0)
}

fn video_start(events: &[AppEvent]) -> Option<&aap_session::projection::VideoStart> {
    events.iter().find_map(|e| match e {
        AppEvent::VideoStart(v) => Some(v),
        _ => None,
    })
}

/// A test-pattern encoder for the negotiated size and its first two pictures.
struct Source {
    encoder: Encoder,
    canvas: Canvas,
    count: u8,
}

impl Source {
    fn new(width: u32, height: u32) -> Self {
        Self {
            encoder: Encoder::new(width, height),
            canvas: Canvas::new(width, height, Yuv::from_rgb(40, 40, 40)),
            count: 0,
        }
    }
    fn config(&self) -> Vec<u8> {
        self.encoder.parameter_sets()
    }
    /// Next picture (a moving bar), forced to an IDR when asked.
    fn frame(&mut self, idr: bool) -> (Vec<u8>, BufferFlags) {
        self.count = self.count.wrapping_add(1);
        self.canvas.fill(
            u32::from(self.count) * 16 % 640,
            32,
            16,
            16,
            Yuv::from_rgb(200, 30, 30),
        );
        let (bytes, key) = self.encoder.encode(&self.canvas, idr);
        (
            bytes,
            BufferFlags {
                key_frame: key,
                ..BufferFlags::default()
            },
        )
    }
}

/// The test head unit accepting only its 800x480 configuration. The synthetic frames the
/// test-pattern encoder makes at 1280x720 outgrow the session's pending-output limit, so the
/// tests that assert exact media traffic pin the small one; selection itself is covered by
/// the Ford fixture tests.
fn small_frame_hu() -> hu_sim::Config {
    hu_sim::Config {
        projection: hu_sim::Projection {
            setup_configs: Some(vec![1]),
            ..Default::default()
        },
        ..hu_sim::Config::default()
    }
}

#[test]
fn projection_opens_discovered_channels_streams_maps_input_and_stops_gracefully() {
    let (mut s, mut hu, t0) = start(small_frame_hu(), |_| {});
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    assert_eq!(s.report().last_completed, Some(Stage::Setup));

    // Channel IDs come from discovery (video 3, input 1); opens go on the target channel
    // with the 0x04 bit, service messages without it, all encrypted.
    let service: Vec<(u8, u8, u16)> = hu
        .received
        .iter()
        .filter(|m| m.channel != 0)
        .map(|m| (m.channel, m.flags, m.id))
        .collect();
    assert_eq!(
        service,
        vec![
            (3, 0x0c, ids::CHANNEL_OPEN_REQUEST),
            (3, 0x08, ids::av::SETUP_REQUEST),
            (1, 0x0c, ids::CHANNEL_OPEN_REQUEST),
            (1, 0x08, ids::input::BINDING_REQUEST),
            (3, 0x08, ids::av::VIDEO_FOCUS_REQUEST),
            (3, 0x08, ids::av::START_INDICATION),
        ]
    );
    let body = |channel: u8, id: u16| {
        hu.received
            .iter()
            .find(|m| m.channel == channel && m.id == id)
            .unwrap()
            .bytes[2..]
            .to_vec()
    };
    // Spec §8.1 decrypted request bytes for target 3.
    assert_eq!(
        encode_message(
            ids::CHANNEL_OPEN_REQUEST,
            &decode::<ChannelOpenRequest>(&body(3, ids::CHANNEL_OPEN_REQUEST)).unwrap()
        ),
        vec![0x00, 0x07, 0x08, 0x00, 0x10, 0x03]
    );
    let setup: AvChannelSetupRequest = decode(&body(3, ids::av::SETUP_REQUEST)).unwrap();
    assert_eq!(setup.media_codec_type, 3);
    let binding: BindingRequest = decode(&body(1, ids::input::BINDING_REQUEST)).unwrap();
    // Every advertised keycode is bound, transport ones included: binding is what makes
    // the head unit send them at all, and routing them is a separate decision (P5.1).
    assert_eq!(binding.scan_codes, vec![3, 4, 84, 85, 87, 88]);
    let focus: VideoFocusRequest = decode(&body(3, ids::av::VIDEO_FOCUS_REQUEST)).unwrap();
    assert_eq!((focus.focus_mode, focus.focus_reason), (Some(1), Some(4)));
    let start: AvChannelStartIndication = decode(&body(3, ids::av::START_INDICATION)).unwrap();
    assert_eq!((start.session, start.config), (0, 1));
    // No sensor, audio, or Bluetooth channel was opened.
    assert_eq!(hu.opened, vec![3, 1]);

    let events = s.take_app_events();
    let v = video_start(&events).expect("video start event").clone();
    assert_eq!(
        (
            v.video.width,
            v.video.height,
            v.video.fps,
            v.video.config_index
        ),
        (800, 480, 30, 1)
    );
    assert_eq!(v.touch, Some((800, 480)));

    let link = s.media();
    let mut source = Source::new(800, 480);
    assert_eq!(link.submit(CONFIG, 0, &source.config()), Submit::Accepted);
    for i in 0..5 {
        let (bytes, flags) = source.frame(false);
        assert_eq!(link.submit(flags, 33_333 * i, &bytes), Submit::Accepted);
        pump(&mut s, &mut hu, t0);
    }
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);
    let kinds: Vec<(u16, Option<u64>)> = hu.media.iter().map(|m| (m.id, m.timestamp_us)).collect();
    assert_eq!(
        kinds,
        vec![
            (ids::av::MEDIA, None),
            (ids::av::MEDIA_WITH_TIMESTAMP, Some(0)),
            (ids::av::MEDIA_WITH_TIMESTAMP, Some(33_333)),
            (ids::av::MEDIA_WITH_TIMESTAMP, Some(66_666)),
            (ids::av::MEDIA_WITH_TIMESTAMP, Some(99_999)),
            (ids::av::MEDIA_WITH_TIMESTAMP, Some(133_332)),
        ]
    );
    // Annex B with four-byte start codes; configuration before the first IDR.
    assert!(hu.media[0].bytes.starts_with(&[0, 0, 0, 1, 0x67]));
    assert!(hu.media[1].bytes.starts_with(&[0, 0, 0, 1, 0x65]));
    assert_eq!(s.report().last_completed, Some(Stage::Projecting));

    // Touch on the input channel reaches the app in video coordinates.
    hu.send_touch(0, 0, &[(400, 240, 7)]);
    hu.send_touch(1, 0, &[(400, 240, 7)]);
    hu.send_key(84, true);
    pump(&mut s, &mut hu, t0);
    let events = s.take_app_events();
    let touches: Vec<String> = events
        .iter()
        .filter_map(|e| match e {
            AppEvent::Touch(t) => Some(t.to_string()),
            _ => None,
        })
        .collect();
    assert_eq!(
        touches,
        vec![
            "action=down pointer=7 pointers=7:400:240:400:240",
            "action=up pointer=7 pointers=7:400:240:400:240"
        ]
    );
    assert!(events.contains(&AppEvent::Key {
        keycode: 84,
        down: true,
        long_press: false,
        meta_state: 0
    }));

    // Keepalive continues while projecting.
    let later = t0 + Duration::from_secs(3);
    pump(&mut s, &mut hu, later);
    assert!(s.report().keepalive.own_requests >= 1);
    assert_eq!(
        s.report().keepalive.own_responses,
        s.report().keepalive.own_requests
    );

    // Stop: stop indication, shutdown request, ByeByeResponse.
    s.on_shutdown_requested(later);
    pump(&mut s, &mut hu, later);
    assert_eq!(category(&s), Some(Category::Cancelled), "{}", detail(&s));
    assert_eq!(
        detail(&s),
        "stop requested; shutdown request sent; ByeByeResponse received"
    );
    let tail: Vec<u16> = hu.received.iter().rev().take(2).map(|m| m.id).collect();
    assert_eq!(tail, vec![ids::SHUTDOWN_REQUEST, ids::av::STOP_INDICATION]);
    assert!(s
        .take_app_events()
        .iter()
        .any(|e| matches!(e, AppEvent::VideoStop { .. })));
    assert_eq!(link.submit(CONFIG, 0, &source.config()), Submit::Closed);

    let text = s.report().to_string();
    for line in [
        "stop_after=projection",
        "last_completed_stage=projecting",
        "projection_step=streaming",
        "video_first_keyframe_written_ms=0",
        "video_selected=channel=3 display=0 config=1 800x480@30 margins=0x0 insets=- content=0,0+800x480",
        "input_binding_keycodes=3,4,84,85,87,88 input_binding_status=0 (OK) input_binding_ms=0",
        "input_last_touch=action=up pointer=7 pointers=7:400:240:400:240",
        "final_category=CANCELLED",
    ] {
        assert!(text.lines().any(|l| l == line), "missing {line} in\n{text}");
    }
    assert!(text.contains("video_configs_sent=1 video_keyframes_sent=1 video_frames_sent=4"));
    assert!(text.contains("video_acks=5 video_acked_units=5"));
    assert!(text.contains("video_sps=profile=66 constraints=0xc0 level=31 size=800x480"));
    assert!(text.contains("input_down=1 input_up=1"));
}

fn ford_hu() -> hu_sim::Config {
    let bytes: Vec<u8> =
        include_str!("../../../protocol/fixtures/ford-sync4-discovery-response.hex")
            .split_whitespace()
            .map(|b| u8::from_str_radix(b, 16).unwrap())
            .collect();
    assert_eq!(&bytes[..2], &[0x00, 0x06]);
    hu_sim::Config {
        discovery_response: Some(bytes[2..].to_vec()),
        encrypted_pings: true,
        ..hu_sim::Config::default()
    }
}

/// Touch coordinates mapped from the events this head unit sent, in order.
fn mapped_touches(s: &mut Session) -> Vec<(u32, u32)> {
    s.take_app_events()
        .iter()
        .filter_map(|e| match e {
            AppEvent::Touch(t) => Some((t.pointers[0].x, t.pointers[0].y)),
            _ => None,
        })
        .collect()
}

#[test]
fn ford_fixture_selects_1920x1080_and_maps_touch_through_its_insets() {
    let (mut s, mut hu, t0) = start(ford_hu(), |c| c.video_preference = VideoPreference::Largest);
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    assert_eq!(hu.opened, vec![1, 2]);
    let caps = s.report().capabilities.clone().unwrap();
    let video = caps.video.unwrap();
    // The largest of the three configurations Ford advertises: its insets put the picture
    // on the left, below the status bar, and its 1334x882 content rectangle carries 3.4x
    // the pixels of the 726x480 one at 800x480 in the same window.
    assert_eq!(
        video.geometry(),
        "margins=586x198 insets=top=99,bottom=99,left=0,right=586 content=0,99+1334x882"
    );
    let small = caps
        .video_candidates
        .iter()
        .find(|v| v.width == 800)
        .unwrap();
    assert_eq!(small.content.to_string(), "37,0+726x480");

    // What Ford's navigation descriptor actually carries, decoded from the one sanitized
    // vehicle capture: a 400 ms update floor and presentation 2, which the pinned schema
    // reads as NEXT_TURN_IMAGE — with no image options at all. A head unit asking for
    // turn images without saying how big they must be is the finding, and it is recorded
    // rather than resolved (P6.1). Nothing branches on the name.
    let nav = caps.navigation.expect("Ford advertises navigation");
    assert_eq!(
        (nav.channel, nav.minimum_interval_ms, nav.nav_type),
        (11, 400, 2)
    );
    assert_eq!(nav.image, None);
    assert_eq!(
        s.report()
            .capabilities
            .as_ref()
            .unwrap()
            .navigation
            .unwrap()
            .to_string(),
        "channel=11 min_interval_ms=400 type=2 (NEXT_TURN_IMAGE) image=-"
    );
    let binding: BindingRequest = decode(
        &hu.received
            .iter()
            .find(|m| m.id == ids::input::BINDING_REQUEST && m.channel == 2)
            .unwrap()
            .bytes[2..],
    )
    .unwrap();
    assert_eq!(
        binding.scan_codes,
        vec![84, 85, 87, 88, 126, 127, 65537, 65538, 65540]
    );
    let events = s.take_app_events();
    let v = video_start(&events).unwrap();
    assert_eq!(
        (v.video.channel, v.video.config_index, v.video.fps),
        (1, 0, 60)
    );
    assert_eq!(v.touch, Some((1920, 882)));

    // The same content space as at 800x480, at this configuration's geometry: the grid
    // keeps the frame's dimensions and starts at the content origin, which here is 99 rows
    // down rather than 37 columns right. The bottom edge therefore addresses rows past the
    // frame and clamps onto its last one.
    hu.send_touch(0, 0, &[(0, 0, 0)]);
    hu.send_touch(0, 0, &[(1919, 1079, 0)]);
    hu.send_touch(0, 0, &[(960, 540, 0)]);
    hu.send_touch(0, 0, &[(1921, 0, 0)]);
    pump(&mut s, &mut hu, t0);
    assert_eq!(
        mapped_touches(&mut s),
        vec![(0, 99), (1919, 1079), (960, 639)],
        "the content touch space moves the frame's grid onto the content origin"
    );
    let input = &s.report().projection.as_ref().unwrap().input;
    assert_eq!(input.rejected, 1);
    assert_eq!(
        input.last_rejection.as_deref(),
        Some("touch 1921x0 outside the 1920x1080 content touch space")
    );
    // The report carries the evidence for the space: how it mapped, and how far the raw
    // coordinates actually reached.
    assert_eq!(input.raw_range, Some([0, 1919, 0, 1079]));
    let m = input.touch_map.unwrap();
    assert_eq!(
        (m.space, m.size, m.target.to_string().as_str()),
        (TouchSpace::Content, (1920, 1080), "0,99+1920x1080")
    );
    // Ford pings encrypted; responses mirror that during projection.
    let k = &s.report().keepalive;
    assert_eq!(k.responses_encrypted, k.responses_written);

    // Both report lines, pinned as text: they were quoted as the finding,
    // and the channel dump line carried nothing after the service name before P6.0.
    let text = s.report().to_string();
    for line in [
        "discovery_channel_011=navigation min_interval_ms=400 type=2 (NEXT_TURN_IMAGE) image=-",
        "discovery_navigation=channel=11 min_interval_ms=400 type=2 (NEXT_TURN_IMAGE) image=-",
    ] {
        assert!(text.lines().any(|l| l == line), "missing {line} in\n{text}");
    }
}

/// Field 1 of the AV setup request is `media_codec_type` in the vendored schema and
/// `config_index` in aasdk. Ford SYNC 4.0 displays only its index-2 configuration while
/// acknowledging every unit of any other, which is what a head unit clamping the
/// out-of-range constant 3 onto its last index would do, so the value is selectable.
#[test]
fn the_av_setup_request_carries_either_the_codec_type_or_the_configuration_index() {
    for (field, expected) in [
        (SetupField::CodecType, 3),
        // Ford's largest configuration is index 0.
        (SetupField::ConfigIndex, 0),
    ] {
        let (mut s, hu, _) = start(ford_hu(), |c| {
            c.video_preference = VideoPreference::Largest;
            c.projection.setup_field = field;
        });
        assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
        let setup: AvChannelSetupRequest = decode(
            &hu.received
                .iter()
                .find(|m| m.id == ids::av::SETUP_REQUEST)
                .unwrap()
                .bytes[2..],
        )
        .unwrap();
        assert_eq!(setup.media_codec_type, expected, "{}", field.name());
        let p = s.report().projection.clone().unwrap();
        assert_eq!(
            (p.setup_field, p.setup_value),
            (field.name(), Some(expected))
        );
        assert!(s.report().to_string().lines().any(|l| l
            == format!(
                "projection_setup_field={} projection_setup_value={expected}",
                field.name()
            )));
        let _ = s.take_app_events();
    }
}

/// Android Auto 17.6 opens every advertised channel on this vehicle (1,2,3,4,6,8,9,10,11,12)
/// and never asks for video focus, waiting for Ford's unsolicited grant. Both are session
/// shape only: nothing here drives those services.
#[test]
fn the_imitation_options_open_every_channel_and_wait_for_focus() {
    let mut config = ford_hu();
    // Ford volunteers focus; the simulator only echoes a request unless told to.
    config.projection.focus = Focus::UnsolicitedThenEcho;
    let (mut s, hu, _) = start(config, |c| {
        c.projection.open_all_channels = true;
        c.projection.request_focus = false;
    });
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    // Ford advertises 1..=12; video and input are opened for their own sake and the rest
    // only to match the shape, lowest first.
    assert_eq!(hu.opened, (1..=12).collect::<Vec<u8>>());
    let p = s.report().projection.clone().unwrap();
    assert_eq!((p.extra_opened, p.extra_refused), (10, 0));
    // Focus was never asked for; the head unit volunteered it.
    assert_eq!(p.focus_requests, 0);
    assert!(p.focus_unrequested >= 1);
    assert!(s
        .report()
        .to_string()
        .lines()
        .any(|l| l == "projection_extra_opened=10 projection_extra_refused=0"));
    let _ = s.take_app_events();
}

/// A head unit that refuses or ignores one of those channels must not cost the drive:
/// nothing in this source needs them.
#[test]
fn a_refused_extra_channel_is_recorded_and_projection_continues() {
    let mut config = ford_hu();
    config.projection.opens = vec![(9, Open::Reject(-5)), (11, Open::Silent)];
    let (mut s, hu, t0) = start(config, |c| {
        c.projection.open_all_channels = true;
    });
    // Channel 11 never answers, so the setup deadline moves past it.
    s.on_timer(t0 + Duration::from_secs(10));
    pump(&mut s, &mut { hu }, t0 + Duration::from_secs(10));
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    let p = s.report().projection.clone().unwrap();
    assert_eq!((p.extra_opened, p.extra_refused), (8, 2));
    assert_eq!(category(&s), None);
    let _ = s.take_app_events();
}

/// Ford SYNC 4.0 moves the content rectangle a second or two into projection with
/// UPDATE_UI_CONFIG_REQUEST (0x8009) and withholds the picture until it is answered; every
/// configuration with an expanded variant does it, which is why 800x480 never did (reports
/// 07ba8ae0, 5336c065, e9326eea). The update's insets contradict the margins from
/// discovery on purpose, so they are taken literally.
#[test]
fn a_mid_stream_ui_config_update_moves_the_content_rectangle_and_is_answered() {
    let mut config = ford_hu();
    // Ford's own bytes: 99/99/0/0, full width against an advertised margin_width of 586.
    // Pushed after the codec configuration, which is a few bytes: the test-pattern
    // encoder's synthetic 1080p pictures outgrow the simulator's assembly limit.
    config.projection.ui_config_after_media = Some((1, [99, 99, 0, 0]));
    let (mut s, mut hu, t0) = start(config, |c| c.video_preference = VideoPreference::Largest);
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    let events = s.take_app_events();
    let start = video_start(&events).unwrap();
    assert_eq!(start.video.content.to_string(), "0,99+1334x882");

    // Start streaming; the head unit pushes the update once the stream is live.
    let link = s.media();
    let source = Source::new(start.video.width, start.video.height);
    assert_eq!(link.submit(CONFIG, 0, &source.config()), Submit::Accepted);
    pump(&mut s, &mut hu, t0);
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);

    // The rectangle widens to the whole frame, and the head unit got its response.
    let geometry = s
        .take_app_events()
        .into_iter()
        .find_map(|e| match e {
            AppEvent::VideoGeometry(g) => Some(g),
            _ => None,
        })
        .expect("a geometry event");
    assert_eq!(geometry.video.content.to_string(), "0,99+1920x882");
    assert_eq!(
        (geometry.video.width, geometry.video.height),
        (1920, 1080),
        "the frame is unchanged, so the encoder keeps running"
    );
    assert_eq!(hu.ui_config_responses, 1);

    let p = s.report().projection.clone().unwrap();
    assert_eq!(
        (
            p.ui_config_updates,
            p.ui_config_responses,
            p.ui_config_invalid
        ),
        (1, 1, 0)
    );
    assert_eq!(
        p.ui_config_last.as_deref(),
        Some("insets=top=99,bottom=99,left=0,right=0 content=0,99+1920x882")
    );
    // Touches now land against the new rectangle, and nothing was recorded as unhandled.
    let m = p.input.touch_map.unwrap();
    assert_eq!(m.target.to_string(), "0,99+1920x1080");
    assert_eq!(s.report().unhandled_count, 0, "{}", s.report());
    assert!(s.report().to_string().lines().any(|l| l
        == "ui_config_updates=1 ui_config_responses=1 ui_config_invalid=0 \
                ui_config_reply_len=12"
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ")));
    // `{1: {1: {top, bottom, left, right}}}` in, the inner message back out as field 1.
    assert_eq!(p.ui_config_reply_len, Some(12));
}

/// An update this source cannot use is refused without ending the session: the stream is
/// running and a rectangle with no picture in it is not worth dropping the drive for.
#[test]
fn an_unusable_ui_config_update_is_recorded_and_the_stream_continues() {
    for (insets, detail) in [
        (
            [0, 0, 1920, 0],
            "insets=top=0,bottom=0,left=1920,right=0 leave no picture in 1920x1080",
        ),
        (
            [540, 540, 0, 0],
            "insets=top=540,bottom=540,left=0,right=0 leave no picture in 1920x1080",
        ),
    ] {
        let mut config = ford_hu();
        config.projection.ui_config_after_media = Some((1, insets));
        let (mut s, mut hu, t0) = start(config, |c| c.video_preference = VideoPreference::Largest);
        let link = s.media();
        let start = video_start(&s.take_app_events()).unwrap().clone();
        let source = Source::new(start.video.width, start.video.height);
        assert_eq!(link.submit(CONFIG, 0, &source.config()), Submit::Accepted);
        pump(&mut s, &mut hu, t0);

        assert_eq!(s.report().stage, Stage::Projecting, "{insets:?}");
        let p = s.report().projection.clone().unwrap();
        assert_eq!(
            (
                p.ui_config_updates,
                p.ui_config_invalid,
                p.ui_config_responses
            ),
            (1, 1, 0),
            "{insets:?}"
        );
        assert_eq!(p.ui_config_last.as_deref(), Some(detail), "{insets:?}");
        // The rectangle from discovery still stands.
        assert_eq!(p.selected.unwrap().content.to_string(), "0,99+1334x882");
    }
}

/// The operator's escape hatch for a head unit that advertises a configuration it will
/// not display: pinning a frame size drives the whole session at it, including the touch
/// map, without the head unit narrowing the choice for us.
#[test]
fn a_pinned_video_preference_drives_the_ford_session_at_that_size() {
    let (s, _, _) = start(ford_hu(), |c| {
        c.video_preference = VideoPreference::Pinned(1280, 720)
    });
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    let mut s = s;
    let events = s.take_app_events();
    let v = video_start(&events).unwrap();
    assert_eq!(
        (v.video.config_index, v.video.width, v.video.height),
        (1, 1280, 720)
    );
    assert_eq!(
        v.video.geometry(),
        "margins=390x132 insets=top=66,bottom=66,left=0,right=390 content=0,66+890x588"
    );
    let m = s
        .report()
        .projection
        .as_ref()
        .unwrap()
        .input
        .touch_map
        .unwrap();
    assert_eq!(
        (m.space, m.size, m.target.to_string().as_str()),
        (TouchSpace::Content, (1280, 720), "0,66+1280x720")
    );
    // The start indication names the pinned index, so the head unit encodes that one.
    assert!(s
        .report()
        .to_string()
        .lines()
        .any(|l| l == "video_preference=1280x720"));
}

/// The G4 and G5 calibration was driven at 800x480; it is pinned here by accepting only
/// that index, so the recorded coordinates keep their meaning however the preference
/// default moves.
#[test]
fn ford_800x480_maps_the_recorded_g4_touches() {
    let mut config = ford_hu();
    config.projection.setup_configs = Some(vec![2]);
    let (mut s, mut hu, t0) = start(config, |_| {});
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    let events = s.take_app_events();
    let v = video_start(&events).unwrap();
    assert_eq!(
        (v.video.config_index, v.video.width, v.video.height),
        (2, 800, 480)
    );
    assert_eq!(
        v.video.geometry(),
        "margins=74x0 insets=top=0,bottom=0,left=37,right=37 content=37,0+726x480"
    );

    // Ford advertises 1920x882 but sends coordinates in the selected 800x480 frame (G4,
    // report da9398a5), so the advertised size is not a scale factor. The grid starts at the
    // content rectangle though (the calibration in report 5c9ac430), so each coordinate moves
    // right by the 37px inset: the vehicle's own button-4 tap still lands on button 4 rather
    // than being compressed into button 1, and the far corner clamps onto the last pixel
    // instead of addressing 836 on an 800-wide frame.
    hu.send_touch(0, 0, &[(0, 0, 0)]);
    hu.send_touch(0, 0, &[(799, 479, 0)]);
    hu.send_touch(0, 0, &[(400, 240, 0)]);
    hu.send_touch(0, 0, &[(525, 364, 0)]);
    hu.send_touch(0, 0, &[(801, 0, 0)]);
    pump(&mut s, &mut hu, t0);
    assert_eq!(
        mapped_touches(&mut s),
        vec![(37, 0), (799, 479), (437, 240), (562, 364)],
        "the content touch space moves the frame's grid onto the content origin"
    );
    let input = &s.report().projection.as_ref().unwrap().input;
    assert_eq!(input.rejected, 1);
    assert_eq!(
        input.last_rejection.as_deref(),
        Some("touch 801x0 outside the 800x480 content touch space")
    );
    assert_eq!(input.raw_range, Some([0, 799, 0, 479]));
    let m = input.touch_map.unwrap();
    assert_eq!(
        (m.space, m.size, m.target.to_string().as_str()),
        (TouchSpace::Content, (800, 480), "37,0+800x480")
    );
}

#[test]
fn refused_or_silent_opens_end_setup_with_their_categories() {
    let refuse = |channel, open| hu_sim::Config {
        projection: hu_sim::Projection {
            opens: vec![(channel, open)],
            ..Default::default()
        },
        ..hu_sim::Config::default()
    };
    let (s, _, _) = start(refuse(3, Open::Reject(-5)), |_| {});
    assert_eq!(category(&s), Some(Category::ChannelRejected));
    assert_eq!(
        detail(&s),
        "head unit refused to open video channel 3: status -5 (INVALID_CHANNEL)"
    );
    assert_eq!(s.report().last_completed, Some(Stage::Discovery));

    let (s, _, _) = start(refuse(1, Open::Reject(-4)), |_| {});
    assert_eq!(category(&s), Some(Category::ChannelRejected));
    assert!(detail(&s).contains("input channel 1"));

    let (mut s, mut hu, t0) = start(refuse(3, Open::Silent), |_| {});
    assert_eq!(s.report().stage, Stage::Setup);
    assert_eq!(s.next_deadline(), Some(t0 + Duration::from_secs(2)));
    // Keepalive is live while waiting for the open response.
    pump(&mut s, &mut hu, t0 + Duration::from_secs(2));
    assert_eq!(s.report().keepalive.own_requests, 1);
    assert_eq!(s.report().keepalive.own_responses, 1);
    s.on_timer(t0 + Duration::from_secs(10));
    assert_eq!(category(&s), Some(Category::SetupTimeout));
    assert_eq!(
        detail(&s),
        "no ChannelOpenResponse on channel 3 within 10000 ms (open-video)"
    );
}

#[test]
fn misrouted_open_responses_are_counted_and_never_accepted() {
    for (open, misrouted, unexpected) in [
        (Open::OnControl, 1, 0),
        (Open::WithoutCategory, 1, 0),
        (Open::WrongChannel(1), 0, 1),
    ] {
        let hu_config = hu_sim::Config {
            projection: hu_sim::Projection {
                opens: vec![(3, open)],
                ..Default::default()
            },
            ..hu_sim::Config::default()
        };
        let (mut s, _, t0) = start(hu_config, |_| {});
        assert_eq!(s.report().stage, Stage::Setup, "{open:?}");
        let p = s.report().projection.clone().unwrap();
        assert_eq!(
            (p.open_responses_misrouted, p.open_responses_unexpected),
            (misrouted, unexpected),
            "{open:?}"
        );
        assert_eq!(p.opens[0].status, None);
        s.on_timer(t0 + Duration::from_secs(10));
        assert_eq!(category(&s), Some(Category::SetupTimeout), "{open:?}");
    }
}

#[test]
fn av_setup_failure_window_default_and_config_reselection() {
    let with = |p: hu_sim::Projection| hu_sim::Config {
        projection: p,
        ..hu_sim::Config::default()
    };
    let (s, _, _) = start(
        with(hu_sim::Projection {
            setup_status: 1,
            ..Default::default()
        }),
        |_| {},
    );
    assert_eq!(category(&s), Some(Category::AvSetupFailed));
    assert_eq!(detail(&s), "AV setup status 1");

    // Preferring the largest takes 1280x720 at discovery, but only 800x480 (index 1) is
    // accepted, so it is given up for it.
    let (mut s, _, _) = start(
        with(hu_sim::Projection {
            setup_configs: Some(vec![1]),
            max_unacked: None,
            ..Default::default()
        }),
        |c| c.video_preference = VideoPreference::Largest,
    );
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    let v = video_start(&s.take_app_events()).unwrap().clone();
    assert_eq!((v.video.config_index, v.video.width), (1, 800));
    let p = s.report().projection.clone().unwrap();
    assert_eq!((p.window, p.window_defaulted), (Some(1), true));
    assert!(s
        .report()
        .to_string()
        .contains("av_setup_window=1 (defaulted)"));

    let (s, _, _) = start(
        with(hu_sim::Projection {
            setup_configs: Some(vec![7]),
            ..Default::default()
        }),
        |_| {},
    );
    assert_eq!(category(&s), Some(Category::AvSetupFailed));
    assert!(detail(&s).starts_with("head unit accepted configurations [7]"));

    let (s, _, t0) = start(
        with(hu_sim::Projection {
            answer_binding: false,
            ..Default::default()
        }),
        |_| {},
    );
    let mut s = s;
    s.on_timer(t0 + Duration::from_secs(10));
    assert_eq!(category(&s), Some(Category::SetupTimeout));
    assert!(detail(&s).contains("input binding response on channel 1"));

    let (s, _, _) = start(
        with(hu_sim::Projection {
            binding_status: Some(-18),
            ..Default::default()
        }),
        |_| {},
    );
    assert_eq!(category(&s), Some(Category::InputBindingFailed));
    assert_eq!(detail(&s), "input binding status -18 (KEYCODE_NOT_BOUND)");
}

#[test]
fn focus_is_requested_once_waits_for_projection_and_can_be_denied() {
    let with = |focus| hu_sim::Config {
        projection: hu_sim::Projection {
            focus,
            ..Default::default()
        },
        ..hu_sim::Config::default()
    };
    // Unsolicited PROJECTED after setup: no request is needed.
    let (s, hu, _) = start(with(Focus::UnsolicitedThenEcho), |_| {});
    assert_eq!(s.report().stage, Stage::Projecting);
    assert!(!hu.ids().contains(&ids::av::VIDEO_FOCUS_REQUEST));
    assert_eq!(s.report().projection.as_ref().unwrap().focus_requests, 0);

    // NATIVE answer: wait; a later unrequested PROJECTED starts video.
    let (mut s, mut hu, t0) = start(with(Focus::Native), |_| {});
    assert_eq!(s.report().stage, Stage::Setup);
    pump(&mut s, &mut hu, t0 + Duration::from_secs(20));
    assert_eq!(s.report().stage, Stage::Setup);
    hu.send_focus(1);
    pump(&mut s, &mut hu, t0 + Duration::from_secs(21));
    assert_eq!(s.report().stage, Stage::Projecting);
    let requests = hu
        .ids()
        .iter()
        .filter(|&&id| id == ids::av::VIDEO_FOCUS_REQUEST)
        .count();
    assert_eq!(requests, 1);

    let (mut s, _, t0) = start(with(Focus::Native), |_| {});
    s.on_timer(t0 + Duration::from_secs(30));
    assert_eq!(category(&s), Some(Category::VideoFocusDenied));
    assert_eq!(
        detail(&s),
        "no projected video focus on channel 3 within 30000 ms (last mode 2)"
    );
}

#[test]
fn decoder_configuration_does_not_use_the_four_frame_ack_window() {
    let mut hu_config = ford_hu();
    hu_config.projection.ack = Ack::Withhold;
    hu_config.projection.max_unacked = Some(4);
    let (mut s, mut hu, t0) = start(hu_config, |_| {});
    let link = s.media();
    let mut source = Source::new(800, 480);

    // A receiver may batch acknowledgements until all four frame slots arrive.
    // Repeat SPS/PPS to also detect leaked credits across stream recovery.
    for batch in 0..5 {
        assert_eq!(link.submit(CONFIG, 0, &source.config()), Submit::Accepted);
        pump(&mut s, &mut hu, t0);
        assert_eq!(s.report().projection.as_ref().unwrap().outstanding, 0);
        for frame in 0..4 {
            let (bytes, flags) = source.frame(frame == 0);
            assert_eq!(
                link.submit(flags, (batch * 4 + frame) * 16_667, &bytes),
                Submit::Accepted
            );
            pump(&mut s, &mut hu, t0);
        }
        assert_eq!(hu.media.len(), (batch as usize + 1) * 5);
        assert_eq!(s.report().projection.as_ref().unwrap().outstanding, 4);
        hu.release_acks();
        pump(&mut s, &mut hu, t0);
        let p = s.report().projection.as_ref().unwrap();
        assert_eq!(p.outstanding, 0);
        assert_eq!(p.acks_excess, 0);
        assert_eq!(category(&s), None, "{}", detail(&s));
    }
    let p = s.report().projection.as_ref().unwrap();
    assert_eq!(p.configs_sent, 5);
    assert_eq!(p.acked_units, 20);
    assert_eq!(p.outstanding_max, 4);
}

#[test]
fn window_bounds_outstanding_units_and_congestion_recovers_at_an_idr() {
    let hu_config = hu_sim::Config {
        projection: hu_sim::Projection {
            ack: Ack::Withhold,
            max_unacked: Some(2),
            ..Default::default()
        },
        ..hu_sim::Config::default()
    };
    let (mut s, mut hu, t0) = start(hu_config, |_| {});
    s.take_app_events();
    let link = s.media();
    let mut source = Source::new(800, 480);
    assert_eq!(link.submit(CONFIG, 0, &source.config()), Submit::Accepted);
    let mut results = Vec::new();
    for i in 0..7 {
        let (bytes, flags) = source.frame(false);
        results.push(link.submit(flags, i, &bytes));
    }
    use Submit::*;
    assert_eq!(
        results,
        vec![
            Accepted,
            Accepted,
            Accepted,
            Accepted,
            DroppedCongested,
            DroppedAwaitingKeyframe,
            DroppedAwaitingKeyframe
        ]
    );
    pump(&mut s, &mut hu, t0);
    // Configuration takes no credit: IDR and one other frame fill the window of 2. No
    // keyframe is asked for while frames from before the drop still wait.
    assert_eq!(hu.media.len(), 3);
    assert_eq!(s.take_app_events(), vec![]);
    let p = s.report().projection.clone().unwrap();
    assert_eq!((p.outstanding, p.outstanding_max), (2, 2));

    // Acks release the queued frames; the next IDR brings the configuration again.
    hu.release_acks();
    pump(&mut s, &mut hu, t0);
    hu.release_acks();
    pump(&mut s, &mut hu, t0);
    assert_eq!(hu.media.len(), 5);
    let (bytes, flags) = source.frame(true);
    assert_eq!(link.submit(flags, 100, &bytes), Accepted);
    hu.release_acks();
    pump(&mut s, &mut hu, t0);
    let ids_sent: Vec<(u16, bool)> = hu
        .media
        .iter()
        .map(|m| (m.id, m.bytes.get(4) == Some(&0x65)))
        .collect();
    assert_eq!(
        ids_sent,
        vec![
            (ids::av::MEDIA, false),
            (ids::av::MEDIA_WITH_TIMESTAMP, true),
            (ids::av::MEDIA_WITH_TIMESTAMP, false),
            (ids::av::MEDIA_WITH_TIMESTAMP, false),
            (ids::av::MEDIA_WITH_TIMESTAMP, false),
            (ids::av::MEDIA, false),
            (ids::av::MEDIA_WITH_TIMESTAMP, true),
        ]
    );
    let m = link.counters();
    assert_eq!(
        (
            m.congestion_events,
            m.dropped_congested,
            m.dropped_awaiting_keyframe,
            m.configs_repeated
        ),
        (1, 1, 2, 1)
    );

    // Without acks the window stays full: FLOW_CONTROL_TIMEOUT after the ack timeout.
    let (bytes, flags) = source.frame(false);
    assert_eq!(link.submit(flags, 101, &bytes), Accepted);
    pump(&mut s, &mut hu, t0);
    let stalled = t0 + Duration::from_secs(1);
    pump(&mut s, &mut hu, stalled);
    assert_eq!(s.report().stage, Stage::Projecting);
    s.on_timer(t0 + Duration::from_secs(5));
    assert_eq!(
        category(&s),
        Some(Category::FlowControlTimeout),
        "{}",
        detail(&s)
    );
    assert_eq!(
        detail(&s),
        "2 media units unacknowledged (window 2) for 5000 ms"
    );
    assert!(s
        .take_app_events()
        .iter()
        .any(|e| matches!(e, AppEvent::VideoStop { .. })));
}

/// A head unit that withholds acknowledgements, with a window of two.
fn withholding_hu() -> hu_sim::Config {
    hu_sim::Config {
        projection: hu_sim::Projection {
            ack: Ack::Withhold,
            max_unacked: Some(2),
            ..Default::default()
        },
        ..hu_sim::Config::default()
    }
}

/// One congestion episode at `at`: frames until the queue overflows, then the head unit
/// catches up and an IDR ends it, as the encoder's own interval would supply.
fn congestion_episode(
    s: &mut Session,
    hu: &mut HeadUnit,
    source: &mut Source,
    at: Instant,
    pts: &mut i64,
) {
    let link = s.media();
    loop {
        let (bytes, flags) = source.frame(false);
        *pts += 16_667;
        if link.submit(flags, *pts, &bytes) == Submit::DroppedCongested {
            break;
        }
    }
    for _ in 0..4 {
        hu.release_acks();
        pump(s, hu, at);
    }
    let (bytes, flags) = source.frame(true);
    *pts += 16_667;
    assert_eq!(link.submit(flags, *pts, &bytes), Submit::Accepted);
    pump(s, hu, at);
    hu.release_acks();
    pump(s, hu, at);
}

/// 2026-10-11 (report d11eec1e): asking for an IDR the instant the queue overflowed kept
/// Ford SYNC 4.0 behind — 5,200 episodes in an hour, two keyframes a second. The IDR that
/// ends an episode now waits for the queue to drain, and is asked for only a spacing after
/// the last keyframe went out.
#[test]
fn congestion_waits_for_the_queue_to_drain_and_spaces_its_keyframe_request() {
    let (mut s, mut hu, t0) = start(withholding_hu(), |_| {});
    s.take_app_events();
    let link = s.media();
    let mut source = Source::new(800, 480);
    assert_eq!(link.submit(CONFIG, 0, &source.config()), Submit::Accepted);
    for i in 0..4 {
        let (bytes, flags) = source.frame(i == 0);
        assert_eq!(link.submit(flags, i * 16_667, &bytes), Submit::Accepted);
    }
    let (bytes, flags) = source.frame(false);
    assert_eq!(
        link.submit(flags, 4 * 16_667, &bytes),
        Submit::DroppedCongested
    );
    pump(&mut s, &mut hu, t0);
    assert!(s.take_app_events().is_empty());

    // An IDR while two frames from before the drop still wait is dropped too.
    let (bytes, flags) = source.frame(true);
    assert_eq!(
        link.submit(flags, 5 * 16_667, &bytes),
        Submit::DroppedAwaitingKeyframe
    );
    assert_eq!(link.counters().dropped_draining, 1);

    // The head unit catches up; the queue drains, and the request waits for the spacing
    // from the keyframe that went out at t0.
    for _ in 0..2 {
        hu.release_acks();
        pump(&mut s, &mut hu, t0);
    }
    assert!(s.take_app_events().is_empty());
    assert!(s.next_deadline().unwrap() <= t0 + Duration::from_secs(1));
    s.on_timer(t0 + Duration::from_millis(999));
    assert!(!s
        .take_app_events()
        .iter()
        .any(|e| matches!(e, AppEvent::KeyframeRequest)));
    s.on_timer(t0 + Duration::from_secs(1));
    assert_eq!(
        s.take_app_events()
            .iter()
            .filter(|e| matches!(e, AppEvent::KeyframeRequest))
            .count(),
        1
    );
    s.on_timer(t0 + Duration::from_secs(2));
    assert!(s.take_app_events().is_empty(), "asked once per episode");
    let p = s.report().projection.clone().unwrap();
    assert_eq!((p.keyframe_requests, p.keyframe_requests_drained), (1, 1));

    let (bytes, flags) = source.frame(true);
    assert_eq!(link.submit(flags, 6 * 16_667, &bytes), Submit::Accepted);
    let m = link.counters();
    assert_eq!((m.congestion_events, m.dropped_congested), (1, 1));
    assert_eq!(m.dropped_awaiting_keyframe, 1);
}

/// A head unit that keeps falling behind has its stream restarted as a screen switch does
/// — stop indication, start under a new session id, rebuilt encoder — and no more than
/// once a cooldown. Counted apart from focus transitions.
#[test]
fn repeated_congestion_restarts_the_stream_once_per_cooldown() {
    let (mut s, mut hu, t0) = start(withholding_hu(), |_| {});
    s.take_app_events();
    let link = s.media();
    let mut source = Source::new(800, 480);
    assert_eq!(link.submit(CONFIG, 0, &source.config()), Submit::Accepted);
    let mut pts = 0;
    let first_session = s.report().projection.clone().unwrap().start_session;
    // 0x8002 is also used on other channels, so stop indications are counted, not looked for.
    let stops = |hu: &HeadUnit| {
        hu.ids()
            .iter()
            .filter(|&&id| id == ids::av::STOP_INDICATION)
            .count()
    };
    let stops_before = stops(&hu);

    for n in 0..4 {
        congestion_episode(
            &mut s,
            &mut hu,
            &mut source,
            t0 + Duration::from_secs(n),
            &mut pts,
        );
    }
    assert!(!s
        .take_app_events()
        .iter()
        .any(|e| matches!(e, AppEvent::VideoRestart { .. })));
    assert_eq!(stops(&hu), stops_before);

    congestion_episode(
        &mut s,
        &mut hu,
        &mut source,
        t0 + Duration::from_secs(4),
        &mut pts,
    );
    let events = s.take_app_events();
    let restart = events
        .iter()
        .position(|e| matches!(e, AppEvent::VideoRestart { .. }))
        .expect("five episodes within ten seconds restart the stream");
    assert!(
        matches!(&events[restart], AppEvent::VideoRestart { reason } if reason == "5 congestion episodes within 10000 ms"),
        "{events:?}"
    );
    let keyframe = events
        .iter()
        .position(|e| matches!(e, AppEvent::KeyframeRequest))
        .expect("the restarted stream asks for its IDR");
    assert!(restart < keyframe, "{events:?}");
    assert_eq!(stops(&hu), stops_before + 1);
    let p = s.report().projection.clone().unwrap();
    assert_ne!(p.start_session, first_session, "a new session id");
    assert_eq!(p.congestion_restarts, 1);
    assert_eq!((p.focus_stop_indications, p.focus_restarts), (0, 0));
    assert_eq!(category(&s), None, "{}", detail(&s));

    // Five more inside the cooldown: due, held, and counted.
    for n in 5..10 {
        congestion_episode(
            &mut s,
            &mut hu,
            &mut source,
            t0 + Duration::from_secs(n),
            &mut pts,
        );
    }
    let p = s.report().projection.clone().unwrap();
    assert_eq!((p.congestion_restarts, p.congestion_restarts_held), (1, 1));

    // After it, the next run of five restarts again.
    for n in 70..75 {
        congestion_episode(
            &mut s,
            &mut hu,
            &mut source,
            t0 + Duration::from_secs(n),
            &mut pts,
        );
    }
    let p = s.report().projection.clone().unwrap();
    assert_eq!(p.congestion_restarts, 2);

    // The timeline names the minutes it happened in.
    let rows: Vec<(u32, u32, u32)> = p
        .timeline
        .iter()
        .map(|r| (r.minute, r.episodes, r.restarts))
        .collect();
    assert_eq!(rows, vec![(0, 10, 1), (1, 5, 1)]);
    let text = p.to_string();
    assert!(text.contains("video_timeline=0:"), "{text}");
    assert!(
        text.contains("video_congestion_restarts=2 video_congestion_restarts_held=1"),
        "{text}"
    );
}

#[test]
fn congestion_restart_can_be_turned_off() {
    let (mut s, mut hu, t0) = start(withholding_hu(), |c| {
        c.projection.congestion_restart = false;
    });
    s.take_app_events();
    let link = s.media();
    let mut source = Source::new(800, 480);
    assert_eq!(link.submit(CONFIG, 0, &source.config()), Submit::Accepted);
    let mut pts = 0;
    for n in 0..8 {
        congestion_episode(
            &mut s,
            &mut hu,
            &mut source,
            t0 + Duration::from_secs(n),
            &mut pts,
        );
    }
    assert!(!s
        .take_app_events()
        .iter()
        .any(|e| matches!(e, AppEvent::VideoRestart { .. })));
    let p = s.report().projection.clone().unwrap();
    assert_eq!((p.congestion_restarts, p.congestion_restarts_held), (0, 0));
    assert_eq!(p.timeline.first().map(|r| r.episodes), Some(8));
}

/// Spec §8.2: a focus transition stops and restarts the stream. The head unit must see the
/// stop indication, and the restart must carry a **new** session id — a source that pauses
/// in place leaves Ford SYNC 4.0 believing the projection is still foreground, so its own
/// control cannot re-enter it (docs/protocol.md, "Video focus").
#[test]
fn focus_loss_stops_the_stream_and_regain_restarts_it_under_a_new_session() {
    let (mut s, mut hu, t0) = start(hu_sim::Config::default(), |_| {});
    s.take_app_events();
    let link = s.media();
    let mut source = Source::new(800, 480);
    link.submit(CONFIG, 0, &source.config());
    let (bytes, flags) = source.frame(false);
    assert_eq!(link.submit(flags, 0, &bytes), Submit::Accepted);
    pump(&mut s, &mut hu, t0);
    assert_eq!(hu.media.len(), 2);
    let first_session = s.report().projection.clone().unwrap().start_session;

    hu.send_focus(2);
    pump(&mut s, &mut hu, t0);
    assert_eq!(
        s.take_app_events(),
        vec![AppEvent::Focus {
            projected: false,
            mode: 2
        }]
    );
    // The stop indication is on the wire, not just a local pause.
    assert!(
        hu.ids().contains(&ids::av::STOP_INDICATION),
        "the head unit must be told the stream stopped: {:?}",
        hu.ids()
    );
    let (bytes, flags) = source.frame(false);
    assert_eq!(link.submit(flags, 1, &bytes), Submit::DroppedNotStreaming);
    hu.send_touch(0, 0, &[(1, 1, 0)]);
    pump(&mut s, &mut hu, t0);
    assert!(s.take_app_events().is_empty());
    // Focus is never requested again by the source.
    let requests = hu
        .ids()
        .iter()
        .filter(|&&id| id == ids::av::VIDEO_FOCUS_REQUEST)
        .count();
    assert_eq!(requests, 1);

    hu.send_focus(1);
    pump(&mut s, &mut hu, t0);
    // The restart hands the app a fresh VideoStart so it rebuilds its encoder, then asks
    // for the IDR that the new session opens with.
    let events = s.take_app_events();
    assert!(
        matches!(
            events.first(),
            Some(AppEvent::Focus {
                projected: true,
                mode: 1
            })
        ),
        "{events:?}"
    );
    assert!(
        events.iter().any(|e| matches!(e, AppEvent::VideoStart(_))),
        "a regain restarts the stream: {events:?}"
    );
    assert!(
        events
            .iter()
            .any(|e| matches!(e, AppEvent::KeyframeRequest)),
        "{events:?}"
    );

    let p = s.report().projection.clone().unwrap();
    assert_ne!(
        p.start_session, first_session,
        "the restart must carry a new session id"
    );
    assert_eq!((p.focus_stop_indications, p.focus_restarts), (1, 1));

    let (bytes, flags) = source.frame(false);
    assert_eq!(
        link.submit(flags, 2, &bytes),
        Submit::DroppedAwaitingKeyframe
    );
    let (bytes, flags) = source.frame(true);
    assert_eq!(link.submit(flags, 3, &bytes), Submit::Accepted);
    pump(&mut s, &mut hu, t0);
    assert_eq!(hu.media[hu.media.len() - 1].bytes[4], 0x65);
    let p = s.report().projection.clone().unwrap();
    assert_eq!(
        (p.focus_losses, p.focus_regains, p.input.dropped_unfocused),
        (1, 1, 1)
    );
}

/// Every indication the head unit sends is in the report with what this source did about
/// it, so "the head unit went quiet" is distinguishable from "this source dropped one".
///
/// Forced by report 6d6fbb29: Ford's last word was PROJECTED at +15.8 s while its screen
/// showed native UI for the next 26 s, and the counters could not say whether a later
/// indication had arrived and been discarded.
#[test]
fn the_focus_sequence_records_every_indication_and_its_action() {
    let (mut s, mut hu, t0) = start(hu_sim::Config::default(), |_| {});
    s.take_app_events();
    let link = s.media();
    let mut source = Source::new(800, 480);
    link.submit(CONFIG, 0, &source.config());
    let (bytes, flags) = source.frame(false);
    link.submit(flags, 0, &bytes);
    pump(&mut s, &mut hu, t0);

    hu.send_focus(2);
    pump(&mut s, &mut hu, t0 + Duration::from_secs(1));
    hu.send_focus(1);
    pump(&mut s, &mut hu, t0 + Duration::from_secs(2));
    // A second PROJECTED while already projected changes nothing — and must still appear,
    // because a head unit repeating itself is evidence about the head unit.
    hu.send_focus(1);
    pump(&mut s, &mut hu, t0 + Duration::from_secs(3));

    let p = s.report().projection.clone().unwrap();
    let actions: Vec<&str> = p.focus_sequence.iter().map(|(_, _, _, a)| *a).collect();
    assert_eq!(actions, vec!["started", "paused", "resumed", "no-change"]);
    let modes: Vec<i32> = p.focus_sequence.iter().map(|(_, m, _, _)| *m).collect();
    assert_eq!(modes, vec![1, 2, 1, 1]);
    assert_eq!(p.focus_sequence.len() as u64, p.focus_indications);

    let text = s.report().to_string();
    assert!(text.contains("video_focus_sequence="), "{text}");
    // The simulator volunteers these, so they carry unrequested=true.
    assert!(text.contains("1000:2:true:paused"), "{text}");
    assert!(text.contains("3000:1:true:no-change"), "{text}");
}

#[test]
fn encoder_failures_end_the_session() {
    let (mut s, mut hu, t0) = start(hu_sim::Config::default(), |_| {});
    s.media().fail("MediaCodec.CodecException: 0x80001001");
    pump(&mut s, &mut hu, t0);
    assert_eq!(category(&s), Some(Category::EncoderFailed));
    assert_eq!(detail(&s), "MediaCodec.CodecException: 0x80001001");

    let (mut s, _, t0) = start(hu_sim::Config::default(), |_| {});
    s.on_timer(t0 + Duration::from_secs(10));
    assert_eq!(category(&s), Some(Category::EncoderFailed));
    assert_eq!(
        detail(&s),
        "no encoder frame within 10000 ms of the start indication"
    );

    // Invalid buffers are refused without ending the session.
    let (s, _, _) = start(hu_sim::Config::default(), |_| {});
    let link = s.media();
    assert_eq!(
        link.submit(CONFIG, 0, &[0, 0, 0, 1, 0x65, 0x88]),
        Submit::Invalid
    );
    assert_eq!(
        link.submit(BufferFlags::default(), 0, &[1, 2, 3]),
        Submit::Invalid
    );
    let c = link.counters();
    assert_eq!(c.invalid, 2);
    assert_eq!(
        c.last_invalid.as_deref(),
        Some("neither Annex B nor exactly length-prefixed NAL units")
    );
    assert_eq!(category(&s), None);
}

#[test]
fn invalid_touches_are_rejected_without_ending_the_session() {
    // Pinned so the frame edge under test does not move with the preference default.
    let (mut s, mut hu, t0) = start(hu_sim::Config::default(), |c| {
        c.video_preference = VideoPreference::Pinned(800, 480)
    });
    s.take_app_events();
    hu.send_touch(4, 0, &[(10, 10, 0)]);
    hu.send_touch(0, 0, &[(10, 10, 0), (20, 20, 0)]);
    hu.send_touch(5, 2, &[(10, 10, 0), (20, 20, 1)]);
    // 800 is the inclusive far edge of an 800-wide frame; 801 is past it.
    hu.send_touch(0, 0, &[(801, 10, 0)]);
    hu.send_touch(5, 1, &[(10, 10, 0), (20, 20, 1)]);
    pump(&mut s, &mut hu, t0);
    let p = s.report().projection.clone().unwrap();
    assert_eq!((p.input.rejected, p.input.pointer_down), (4, 1));
    let events = s.take_app_events();
    assert_eq!(events.len(), 1);
    assert!(
        matches!(&events[0], AppEvent::Touch(t) if t.action_pointer == 1 && t.pointers.len() == 2)
    );
    assert_eq!(category(&s), None);
}

#[test]
fn inbound_opens_are_refused_and_unrelated_traffic_is_recorded() {
    let (mut s, mut hu, t0) = start(hu_sim::Config::default(), |_| {});
    let request = ChannelOpenRequest {
        priority: 0,
        channel_id: 4,
    };
    hu.send_raw(
        4,
        true,
        true,
        encode_message(ids::CHANNEL_OPEN_REQUEST, &request),
    );
    hu.send_raw(4, false, true, vec![0x80, 0x03, 0x08, 0x02]);
    pump(&mut s, &mut hu, t0);
    let reply = hu
        .received
        .iter()
        .find(|m| m.channel == 4 && m.id == ids::CHANNEL_OPEN_RESPONSE)
        .unwrap();
    assert_eq!(reply.flags, 0x0c);
    let response: ChannelOpenResponse = decode(&reply.bytes[2..]).unwrap();
    assert_eq!(response.status, -5);
    assert_eq!(
        s.report()
            .projection
            .as_ref()
            .unwrap()
            .inbound_opens_rejected,
        1
    );
    assert_eq!(s.report().unhandled_count, 1);
    assert_eq!(category(&s), None);
}

#[test]
fn sensors_are_opt_in_and_only_restriction_types_are_requested() {
    let (mut s, hu, _) = start(
        hu_sim::Config {
            projection: hu_sim::Projection {
                driving_status: 31,
                ..Default::default()
            },
            ..hu_sim::Config::default()
        },
        |c| c.projection.sensors = SensorSet::Restriction,
    );
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    assert_eq!(hu.opened, vec![3, 1, 2]);
    // The head unit advertises Ford's nine and DRIVING_STATUS is seventh of them, so
    // this also shows the set chooses the types, not the advertisement's order.
    let requested: Vec<(i32, i64)> = hu
        .sensor_requests
        .iter()
        .map(|r| (r.sensor_type, r.refresh_interval))
        .collect();
    assert_eq!(requested, vec![(13, 0), (10, 0)]);
    let events = s.take_app_events();
    assert!(events.contains(&AppEvent::Restriction {
        driving_status: Some(31),
        night: Some(true)
    }));
    let text = s.report().to_string();
    assert!(
        text.contains("sensor_set=restriction sensor_advertised=1,3,6,7,8,10,13,21,24"),
        "{text}"
    );
    assert!(
        text.contains(
            "sensor_requests=13:0,10:0 sensor_errors=0 sensor_events=2 sensor_invalid=0 \
             sensor_empty=0 sensor_unrequested=0 sensor_driving_status=31 sensor_night=true"
        ),
        "{text}"
    );
}

/// P6.1. A head unit that is *moving*: every fix is a new position, and one in five
/// messages carries a speed with no position beside it.
///
/// The lab has only ever reported one fixed fix, so until now nothing had ever shown
/// that this path produces a *stream* of vehicle states rather than a single one. Two
/// things are checked that a stationary head unit cannot show: that each new position
/// reaches the app, and that a message carrying only speed leaves the fix alone instead
/// of replacing it with an empty one — which would put the map in the Gulf of Guinea
/// once a second on a real drive.
#[test]
fn a_moving_head_unit_delivers_every_fix_and_speed_alone_keeps_the_position() {
    let mut config = small_frame_hu();
    // 13.4 m/s in millimetres per second, a fix every 200 ms: the lab receiver's cruise
    // and the vehicle's own measured LOCATION cadence.
    config.projection.track = Some(hu_sim::Track {
        every_media: 1,
        speed_mm_s: 13_400,
        interval_ms: 200,
    });
    let (mut s, mut hu, t0) = start(config, |c| {
        c.projection.sensors = SensorSet::All;
        c.projection.sensor_location = true;
    });
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    // The subscriptions themselves answered with a position, a speed and a parking
    // brake before the stream existed, so the driving starts from that count.
    let _ = s.take_app_events();
    let parked = s.report().projection.as_ref().unwrap().vehicle_events;

    let link = s.media();
    let mut source = Source::new(800, 480);
    assert_eq!(link.submit(CONFIG, 0, &source.config()), Submit::Accepted);
    pump(&mut s, &mut hu, t0);
    let mut vehicles = Vec::new();
    for i in 0..20 {
        let (bytes, flags) = source.frame(i == 0);
        assert_eq!(link.submit(flags, 33_333 * i, &bytes), Submit::Accepted);
        pump(&mut s, &mut hu, t0);
        vehicles.extend(s.take_app_events().into_iter().filter_map(|e| match e {
            AppEvent::Vehicle(v) => Some(v),
            _ => None,
        }));
    }
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);
    assert!(
        hu.track_fixes >= 20,
        "the head unit drove {}",
        hu.track_fixes
    );
    assert!(hu.track_speed_events >= 4, "{}", hu.track_speed_events);

    // Every fix reached the app, and each one is further east than the last: a source
    // that coalesced or dropped positions would still pass a count.
    assert!(
        vehicles.len() as u32 >= hu.track_fixes,
        "{} events for {} fixes",
        vehicles.len(),
        hu.track_fixes
    );
    let longitudes: Vec<i32> = vehicles.iter().filter_map(|v| v.longitude).collect();
    assert_eq!(
        longitudes.len(),
        vehicles.len(),
        "every event carries a fix"
    );
    assert!(
        longitudes.windows(2).all(|w| w[1] >= w[0]),
        "the vehicle went backwards: {longitudes:?}"
    );
    assert!(
        longitudes.last() > longitudes.first(),
        "the vehicle never moved: {longitudes:?}"
    );
    // The position-less speed message: the state it produces still has the fix from
    // before it, and now carries the speed as well.
    let with_speed = vehicles
        .iter()
        .find(|v| v.car_speed == Some(13_400))
        .expect("a speed event");
    assert!(
        with_speed.latitude.is_some() && with_speed.longitude.is_some(),
        "speed alone erased the position: {with_speed:?}"
    );
    // The speed inside the fix and the vehicle's own speed sensor agree here because
    // the same number was sent to both; they need not, and the source keeps them apart.
    assert_eq!(with_speed.gps_speed, Some(13_400));
    assert_eq!(with_speed.bearing, Some(90_000));

    let p = s.report().projection.clone().unwrap();
    assert_eq!(p.vehicle_events - parked, vehicles.len() as u64);
    let text = s.report().to_string();
    assert!(
        text.contains(&format!("sensor_events={}", p.sensor_events)),
        "{text}"
    );
}

/// P5.2. `sensors=all` subscribes to every advertised type in the advertised order, and
/// the report separates the types that carried values from the types that were accepted
/// and then said nothing. That separation is the whole question the slice exists to
/// answer on the factory head unit.
#[test]
fn the_whole_advertised_sensor_set_can_be_harvested_per_type() {
    let (s, hu, _) = start(small_frame_hu(), |c| c.projection.sensors = SensorSet::All);
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    let requested: Vec<i32> = hu.sensor_requests.iter().map(|r| r.sensor_type).collect();
    assert_eq!(requested, hu_sim::ADVERTISED_SENSORS.to_vec());

    let text = s.report().to_string();
    assert!(text.contains("sensor_set=all"), "{text}");
    // Every advertised type was asked for; seven of the nine answered.
    assert!(
        text.contains("sensor_populated=1,3,7,10,13,21,24 sensor_silent=6,8"),
        "{text}"
    );
    // Fields beyond the two restriction types are extracted, not discarded.
    assert!(
        text.contains(
            "sensor_value_003=CAR_SPEED requested=true count=1 \
             last=speed=0 cruise_engaged=false unknown_4=-"
        ),
        "{text}"
    );
    assert!(
        text.contains(
            "sensor_value_007=PARKING_BRAKE requested=true count=1 last=parking_brake=true"
        ),
        "{text}"
    );
    assert!(
        text.contains(
            "sensor_value_021=GPS requested=true count=1 \
             last=satellite_count=11 satellites_used=7 entries=2 in_use=1"
        ),
        "{text}"
    );
    // `TrailerData` is an empty message, so its presence is the whole value.
    assert!(
        text.contains("sensor_value_024=TRAILER requested=true count=1 last=present=true"),
        "{text}"
    );
    // Advertised, accepted, and then silent: not the same as never asked for.
    assert!(
        text.contains("sensor_value_006=FUEL_LEVEL requested=true count=0 last=-"),
        "{text}"
    );
}

/// P5.2, spec §10. Position identifies the vehicle, so a report says whether LOCATION
/// arrived and never where — unless the operator opted in, exactly like the raw-capture
/// options.
#[test]
fn head_unit_position_is_redacted_unless_it_was_asked_for() {
    let coordinates = hu_sim::Config {
        projection: hu_sim::Projection {
            latitude: 424_242_424,
            longitude: -712_121_212,
            ..Default::default()
        },
        ..small_frame_hu()
    };
    let (s, _, _) = start(coordinates.clone(), |c| {
        c.projection.sensors = SensorSet::Only(vec![1])
    });
    let text = s.report().to_string();
    assert!(text.contains("sensor_location=redacted"), "{text}");
    // The fix is still counted and its quality still reported: that is what answers
    // "does Ford populate LOCATION at all".
    assert!(
        text.contains(
            "sensor_value_001=LOCATION requested=true count=1 \
             last=latitude=redacted longitude=redacted accuracy=8 altitude=213 speed=0 bearing=1790"
        ),
        "{text}"
    );
    assert!(!text.contains("424242424"), "{text}");
    assert!(!text.contains("712121212"), "{text}");

    let (s, _, _) = start(coordinates, |c| {
        c.projection.sensors = SensorSet::Only(vec![1]);
        c.projection.sensor_location = true;
    });
    let text = s.report().to_string();
    assert!(text.contains("sensor_location=reported"), "{text}");
    assert!(
        text.contains("last=latitude=424242424 longitude=-712121212 accuracy=8"),
        "{text}"
    );
}

/// P5.2. A value for a type nobody subscribed to is harvested and counted separately,
/// not dropped and not confused with a requested type that stayed silent.
#[test]
fn an_unrequested_sensor_value_is_counted_and_kept() {
    let volunteering = hu_sim::Config {
        projection: hu_sim::Projection {
            volunteer_sensor: Some(7),
            ..Default::default()
        },
        ..small_frame_hu()
    };
    let (s, _, _) = start(volunteering, |c| {
        c.projection.sensors = SensorSet::Only(vec![13])
    });
    let text = s.report().to_string();
    assert!(text.contains("sensor_unrequested=1"), "{text}");
    assert!(
        text.contains("sensor_value_007=PARKING_BRAKE requested=false count=1"),
        "{text}"
    );
    assert!(
        text.contains("sensor_populated=13,7 sensor_silent=-"),
        "{text}"
    );
}

/// P5.2. A set naming types the head unit does not advertise asks for nothing extra, and
/// a set that intersects nothing leaves the sensor channel closed rather than opening it
/// to say nothing (spec §8.1).
#[test]
fn a_sensor_set_never_exceeds_the_advertisement() {
    let (s, hu, _) = start(small_frame_hu(), |c| {
        // RPM and ODOMETER are not in Ford's nine; CAR_SPEED is.
        c.projection.sensors = SensorSet::Only(vec![4, 3, 5])
    });
    assert_eq!(hu.opened, vec![3, 1, 2]);
    let requested: Vec<i32> = hu.sensor_requests.iter().map(|r| r.sensor_type).collect();
    assert_eq!(requested, vec![3]);
    assert!(
        s.report().to_string().contains("sensor_set=4,3,5"),
        "{}",
        s.report()
    );

    let (s, hu, _) = start(small_frame_hu(), |c| {
        c.projection.sensors = SensorSet::Only(vec![4, 5])
    });
    assert_eq!(
        hu.opened,
        vec![3, 1],
        "nothing to ask for, so nothing opened"
    );
    assert!(hu.sensor_requests.is_empty());
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
}

/// P5.2 keeps P4's rule: sensors are opt-in and every sensor failure is non-fatal. A
/// refused subscription costs that type, not the drive.
#[test]
fn a_refused_sensor_subscription_is_recorded_and_projection_continues() {
    let refusing = hu_sim::Config {
        projection: hu_sim::Projection {
            // INVALID_SENSOR for every type.
            sensor_status: -9,
            ..Default::default()
        },
        ..small_frame_hu()
    };
    let (s, hu, _) = start(refusing, |c| c.projection.sensors = SensorSet::All);
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    let requested: Vec<i32> = hu.sensor_requests.iter().map(|r| r.sensor_type).collect();
    assert_eq!(requested, hu_sim::ADVERTISED_SENSORS.to_vec());
    let text = s.report().to_string();
    // Refused is not silent: a type that never started cannot be read as unpopulated.
    assert!(text.contains("sensor_silent=-"), "{text}");
    assert!(text.contains("sensor_requests=1:-9,3:-9,"), "{text}");
    assert_eq!(category(&s), None);
}

/// P5.1. The channel is opt-in like the sensor channel, and sits between input binding
/// and the sensor step, so a head unit sees it only when something is going to use it
/// (spec §8.1).
#[test]
fn the_media_info_channel_is_opt_in_and_opens_after_input_binding() {
    let (s, hu, _) = start(small_frame_hu(), |_| {});
    assert_eq!(hu.opened, vec![3, 1], "advertised but not asked for");
    // `media_channel` is the channel this session uses, so it is absent when the
    // integration was not asked for; that the head unit advertised one is already
    // recorded by discovery.
    let text = s.report().to_string();
    assert!(text.contains("media_channel=- media_open=false"), "{text}");
    assert!(text.contains("discovery_media_info=channel=10"), "{text}");

    let (s, hu, _) = start(small_frame_hu(), |c| c.projection.media_info = true);
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    assert_eq!(hu.opened, vec![3, 1, 10]);
    assert!(s
        .report()
        .to_string()
        .contains("media_channel=10 media_open=true"));

    // With both integrations on, now-playing precedes the sensor subscription.
    let (_, hu, _) = start(small_frame_hu(), |c| {
        c.projection.media_info = true;
        c.projection.sensors = SensorSet::Restriction;
    });
    assert_eq!(hu.opened, vec![3, 1, 10, 2]);
}

/// A refused now-playing channel costs the integration, not the drive — the same rule as
/// the sensor channel. Ford advertises this channel and has never been asked to open it,
/// so a refusal is a live possibility.
#[test]
fn a_refused_media_info_channel_is_recorded_and_projection_continues() {
    let (s, hu, _) = start(
        hu_sim::Config {
            projection: hu_sim::Projection {
                opens: vec![(10, Open::Reject(-5))],
                setup_configs: Some(vec![1]),
                ..Default::default()
            },
            ..hu_sim::Config::default()
        },
        |c| c.projection.media_info = true,
    );
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    assert_eq!(hu.opened, vec![3, 1]);
    let text = s.report().to_string();
    assert!(
        text.contains("media_channel=10 media_open=false media_refused=true"),
        "{text}"
    );
    assert!(
        text.contains("media_status_sent=0 media_metadata_sent=0"),
        "{text}"
    );
}

/// Nothing is published until the app says what is playing: the session owns no playback
/// and never invents a state. A head unit that opened the channel and was told nothing is
/// told nothing.
#[test]
fn now_playing_reaches_the_head_unit_only_once_the_app_publishes_it() {
    let (mut s, mut hu, t0) = start(small_frame_hu(), |c| c.projection.media_info = true);
    assert!(hu.playback_status.is_empty() && hu.playback_metadata.is_empty());

    let link = s.now_playing();
    link.publish(NowPlaying::new(
        PlaybackState::Playing,
        42,
        "Example Player",
        "Title",
        "Artist",
        "Album",
    ));
    pump(&mut s, &mut hu, t0);

    let status = hu.playback_status.last().expect("a status was sent");
    assert_eq!(status.playback_state, Some(PlaybackState::Playing.wire()));
    assert_eq!(status.source_app.as_deref(), Some("Example Player"));
    assert_eq!(status.position_seconds, Some(42));
    // Not known, so not claimed: proto2 tells absent apart from false.
    assert_eq!(
        (status.shuffle, status.repeat, status.repeat_one),
        (None, None, None)
    );
    let metadata = hu.playback_metadata.last().expect("metadata was sent");
    assert_eq!(metadata.title.as_deref(), Some("Title"));
    assert_eq!(metadata.artist.as_deref(), Some("Artist"));
    assert_eq!(metadata.album.as_deref(), Some("Album"));
    // The P5.1 open risk: unbounded bytes on a 16 KiB-fragment transport.
    assert_eq!(metadata.album_art, None);
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);
    let text = s.report().to_string();
    assert!(
        text.contains("media_status_sent=1 media_metadata_sent=1"),
        "{text}"
    );
    assert!(text.contains("media_album_art=omitted"), "{text}");
    assert!(
        text.contains("media_last_status=playing:42:Example Player"),
        "{text}"
    );
    // The track itself stays out of the report (spec §10): it is listening history.
    assert!(!text.contains("Title"), "{text}");
}

/// An app polling a player it cannot read, or one that has not moved, must not turn into
/// a message per tick behind the video on the same output queue.
#[test]
fn unchanged_publications_and_the_rate_limit_stay_off_the_wire() {
    let (mut s, mut hu, t0) = start(small_frame_hu(), |c| {
        c.projection.media_info = true;
        c.projection.media_interval = Duration::from_millis(500);
    });
    let link = s.now_playing();
    let playing = NowPlaying::new(PlaybackState::Playing, 1, "App", "T", "A", "B");
    link.publish(playing.clone());
    pump(&mut s, &mut hu, t0);
    assert_eq!(hu.playback_status.len(), 1);

    // Republishing the same thing says nothing new, so nothing goes out.
    link.publish(playing);
    pump(&mut s, &mut hu, t0);
    assert_eq!(hu.playback_status.len(), 1);
    assert!(s.report().to_string().contains("media_suppressed=0"));

    // A real change inside the interval is held, then sent once it elapses.
    link.publish(NowPlaying::new(
        PlaybackState::Playing,
        2,
        "App",
        "T",
        "A",
        "B",
    ));
    pump(&mut s, &mut hu, t0 + Duration::from_millis(100));
    assert_eq!(hu.playback_status.len(), 1);
    assert!(s.report().to_string().contains("media_suppressed=1"));
    pump(&mut s, &mut hu, t0 + Duration::from_millis(600));
    assert_eq!(hu.playback_status.len(), 2);
    assert_eq!(hu.playback_status[1].position_seconds, Some(2));
    // The track never changed, so only the status was resent.
    assert_eq!(hu.playback_metadata.len(), 1);
}

/// The now-playing deadline must describe pending work, not the last send. Reporting
/// `next_send` unconditionally left a permanently past deadline once it elapsed, so the
/// poll loop woke with a zero timeout for the rest of the projection — found on Ford
/// (report 25150d54) with every test green, because nothing here had looked at what the
/// session asks to be woken for.
#[test]
fn an_idle_now_playing_channel_never_leaves_a_past_deadline() {
    let (mut s, mut hu, t0) = start(small_frame_hu(), |c| {
        c.projection.media_info = true;
        c.projection.media_interval = Duration::from_millis(100);
        c.projection.media_refresh = Duration::from_secs(5);
    });
    // A live stream, so the jump below is not mistaken for a stalled encoder.
    let link = s.media();
    let mut source = Source::new(800, 480);
    link.submit(CONFIG, 0, &source.config());
    let (frame, flags) = source.frame(true);
    link.submit(flags, 1_000, &frame);
    s.now_playing().publish(NowPlaying::new(
        PlaybackState::Playing,
        1,
        "App",
        "T",
        "A",
        "B",
    ));
    pump(&mut s, &mut hu, t0);
    let first = hu.playback_status.len();
    assert_eq!(first, 1);

    // Far enough ahead that the refresh is due, so something is sent and `next_send` is
    // pushed out again.
    let later = t0 + Duration::from_secs(30);
    pump(&mut s, &mut hu, later);
    assert!(
        hu.playback_status.len() > first,
        "the status was never restated: {}",
        s.report()
    );

    // Now step just past that `next_send` but nowhere near the next refresh. This is the
    // state the bug lived in: the rate limit has expired with nothing waiting to go out,
    // so a deadline derived from it is already in the past and the loop never sleeps.
    let idle = later + Duration::from_millis(200);
    pump(&mut s, &mut hu, idle);
    let deadline = match s.next_deadline() {
        Some(deadline) => deadline,
        None => panic!("no deadline at all: {}", s.report()),
    };
    assert!(
        deadline > idle,
        "deadline is not after {idle:?}; the poll loop would spin"
    );
}

/// The head unit's media buttons become named transport actions; everything else stays an
/// ordinary key. Ford binds both kinds on the same channel.
#[test]
fn transport_keycodes_become_transport_events_and_other_keys_stay_keys() {
    let (mut s, mut hu, t0) = start(small_frame_hu(), |c| c.projection.media_info = true);
    let link = s.media();
    let mut source = Source::new(800, 480);
    link.submit(CONFIG, 0, &source.config());
    let (frame, flags) = source.frame(true);
    link.submit(flags, 1_000, &frame);
    pump(&mut s, &mut hu, t0);
    let _ = s.take_app_events();

    for (keycode, down) in [(85, true), (85, false), (87, true), (84, true), (84, false)] {
        hu.send_key(keycode, down);
    }
    pump(&mut s, &mut hu, t0);
    let events = s.take_app_events();
    let transport: Vec<_> = events
        .iter()
        .filter_map(|e| match e {
            AppEvent::Transport { action, keycode } => Some((action.name(), *keycode)),
            _ => None,
        })
        .collect();
    // Only the presses: a media control is an edge, and acting on the release too would
    // skip every other track.
    assert_eq!(transport, vec![("play-pause", 85), ("next", 87)]);
    let keys: Vec<_> = events
        .iter()
        .filter_map(|e| match e {
            AppEvent::Key { keycode, down, .. } => Some((*keycode, *down)),
            _ => None,
        })
        .collect();
    assert_eq!(keys, vec![(84, true), (84, false)]);
    let text = s.report().to_string();
    assert!(
        text.contains("transport_keys=2 transport_actions=play-pause:1,next:1"),
        "{text}"
    );
    // Both edges of every button are still counted as input.
    assert!(text.contains("input_key_events=5"), "{text}");
}

/// 0x8002 is the one message this channel carries head unit → phone. Nothing acts on it,
/// but it must not show up as traffic this source cannot explain.
#[test]
fn a_cluster_dpad_event_is_accepted_without_counting_as_unhandled() {
    let (mut s, mut hu, t0) = start(small_frame_hu(), |c| c.projection.media_info = true);
    hu.send_media_event(5);
    pump(&mut s, &mut hu, t0);
    let text = s.report().to_string();
    assert!(
        text.contains("media_events=1 media_events_invalid=0"),
        "{text}"
    );
    assert_eq!(s.report().unhandled_count, 0, "{text}");
    assert_eq!(s.report().stage, Stage::Projecting);

    // A malformed one is counted as invalid, not fatal: the required event_type is
    // missing, which the schema check rejects before prost sees it.
    let channel = hu.media_info_channel().unwrap();
    hu.send_raw(
        channel,
        false,
        true,
        ids::media::PLAYBACK_STATUS_EVENT.to_be_bytes().to_vec(),
    );
    pump(&mut s, &mut hu, t0);
    assert!(s
        .report()
        .to_string()
        .contains("media_events=2 media_events_invalid=1"));
    assert_eq!(s.report().stage, Stage::Projecting);
}

// ---------------------------------------------------------------------------------
// P5.3 audio out over USB.
// ---------------------------------------------------------------------------------

/// A buffer of silence at the format the simulated head unit advertises: 2048 stereo
/// 16-bit frames, the conventional 8192 bytes ≈ 42.7 ms at 48 kHz.
fn pcm(frames: usize) -> Vec<u8> {
    vec![0u8; frames * 4]
}

/// Drives a session to the point where audio focus has been granted and the audio start
/// indication is out, returning the `AudioStart` the app was handed.
fn start_audio(
    hu_config: hu_sim::Config,
    tweak: impl FnOnce(&mut Config),
) -> (Session, HeadUnit, Instant, Option<AudioStart>) {
    let (mut s, hu, t0) = start(hu_config, |c| {
        c.projection.audio = true;
        tweak(c);
    });
    let start = s.take_app_events().into_iter().find_map(|e| match e {
        AppEvent::AudioStart(start) => Some(start),
        _ => None,
    });
    (s, hu, t0, start)
}

/// Discovery spells out every advertised audio configuration, which is the unknown P5.3
/// had to close first: four connections recorded `audio_configs.len()` and never its
/// contents. Selection then takes PCM MEDIA, not the lowest-numbered audio channel.
#[test]
fn advertised_audio_configurations_are_reported_and_pcm_media_is_selected() {
    let (s, hu, _) = start(small_frame_hu(), |_| {});
    let text = s.report().to_string();
    assert!(
        text.contains("discovery_audio_channel_004=channel=4 codec=1 audio_type=1 configs=16000/16/1 selected=16000/16/1 index=0"),
        "{text}"
    );
    assert!(
        text.contains("discovery_audio_channel_005=channel=5 codec=1 audio_type=3 configs=48000/16/2 selected=48000/16/2 index=0"),
        "{text}"
    );
    // Channel 4 is PCM but SPEECH, so the MEDIA channel is taken even though it is
    // advertised second.
    assert!(
        text.contains("discovery_audio=channel=5 codec=1 audio_type=3"),
        "{text}"
    );
    // Advertised is not opened: audio is opt-in like sensors and now-playing.
    assert_eq!(hu.opened, vec![3, 1]);
    assert!(
        text.contains("audio_requested=false audio_channel=- audio_open=false"),
        "{text}"
    );
    assert!(text.contains("audio_selected=-"), "{text}");
}

/// A head unit whose only audio configuration the phone side cannot produce leaves audio
/// unusable and says so, rather than being guessed at: the hard validation is sample rate
/// ∈ {48000, 16000}, bit depth 16 only, channels ∈ {1, 2}.
#[test]
fn an_audio_configuration_the_phone_cannot_produce_is_refused_at_discovery() {
    use aap_proto::data::{AudioConfig, AvChannel, ChannelDescriptor};
    use prost::Message as _;
    let mut response = aap_proto::messages::ServiceDiscoveryResponse::decode(
        hu_sim::discovery_response().as_ref(),
    )
    .unwrap();
    response.channels.retain(|c| c.channel_id != 5);
    response.channels.push(ChannelDescriptor {
        channel_id: 5,
        av_channel: Some(AvChannel {
            stream_type: Some(1),
            audio_type: Some(3),
            audio_configs: vec![AudioConfig {
                sample_rate: 44_100,
                bit_depth: 24,
                channel_count: 6,
            }],
            ..Default::default()
        }),
        ..Default::default()
    });
    let (s, hu, _) = start(
        hu_sim::Config {
            discovery_response: Some(response.encode_to_vec()),
            projection: hu_sim::Projection {
                setup_configs: Some(vec![1]),
                ..Default::default()
            },
            ..hu_sim::Config::default()
        },
        |c| c.projection.audio = true,
    );
    let text = s.report().to_string();
    assert!(
        text.contains("discovery_audio_channel_005=channel=5 codec=1 audio_type=3 configs=44100/24/6 selected=-"),
        "{text}"
    );
    assert!(text.contains("discovery_audio=-"), "{text}");
    assert!(
        text.contains("no advertised audio channel is PCM MEDIA"),
        "{text}"
    );
    // Asking for audio anyway costs nothing: the channel is never opened and the picture
    // is unaffected.
    assert_eq!(s.report().stage, Stage::Projecting, "{text}");
    assert_eq!(hu.opened, vec![3, 1]);
    assert!(
        text.contains("audio_requested=true audio_channel=- audio_open=false"),
        "{text}"
    );
}

/// The whole audio sequence: the channel opens after input binding, its AV setup declares
/// PCM and gets its own window, audio focus is asked for on the **control channel** after
/// the video start, and only then does the audio start indication go out.
#[test]
fn audio_opens_sets_up_takes_focus_on_the_control_channel_and_streams_pcm() {
    let (mut s, mut hu, t0, start) = start_audio(small_frame_hu(), |_| {});
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);
    assert_eq!(hu.opened, vec![3, 1, 5]);

    let service: Vec<(u8, u16)> = hu
        .received
        .iter()
        .filter(|m| m.channel != 0 || m.id == ids::AUDIO_FOCUS_REQUEST)
        .map(|m| (m.channel, m.id))
        .collect();
    assert_eq!(
        service,
        vec![
            (3, ids::CHANNEL_OPEN_REQUEST),
            (3, ids::av::SETUP_REQUEST),
            (1, ids::CHANNEL_OPEN_REQUEST),
            (1, ids::input::BINDING_REQUEST),
            (5, ids::CHANNEL_OPEN_REQUEST),
            (5, ids::av::SETUP_REQUEST),
            (3, ids::av::VIDEO_FOCUS_REQUEST),
            (3, ids::av::START_INDICATION),
            // Audio focus is on channel 0, not on channel 5.
            (0, ids::AUDIO_FOCUS_REQUEST),
            (5, ids::av::START_INDICATION),
        ]
    );
    let setup: AvChannelSetupRequest = decode(
        &hu.received
            .iter()
            .find(|m| m.channel == 5 && m.id == ids::av::SETUP_REQUEST)
            .unwrap()
            .bytes[2..],
    )
    .unwrap();
    assert_eq!(setup.media_codec_type, 1, "MEDIA_CODEC_AUDIO_PCM");
    assert_eq!(hu.audio_focus_requests, vec![1], "GAIN");
    // The start indication's config is an index into the advertised audio configurations,
    // a different numbering space from the setup request's codec.
    assert_eq!((hu.audio_session, hu.audio_config), (Some(0), Some(0)));

    let start = start.expect("audio start event");
    assert_eq!(
        (
            start.channel,
            start.sample_rate,
            start.bit_depth,
            start.channel_count,
            start.buffer_bytes
        ),
        (5, 48_000, 16, 2, 8192)
    );

    // The window the simulated head unit gives audio is 1, so each buffer must be
    // acknowledged before the next goes out.
    let text = s.report().to_string();
    assert!(
        text.contains("audio_setup_window=1 audio_setup_accepted_configs=-"),
        "{text}"
    );
    assert!(
        text.contains("audio_focus_state=1 (GAIN) audio_focus_unsolicited=false"),
        "{text}"
    );

    let audio = s.audio();
    for i in 0..4 {
        assert_eq!(
            audio.submit(BufferFlags::default(), 42_666 * i, &pcm(2048)),
            Submit::Accepted
        );
        pump(&mut s, &mut hu, t0);
    }
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);
    assert_eq!(hu.audio.len(), 4);
    // Audio is one BULK frame per indication: 8192 bytes plus the id and timestamp is
    // inside a 16 KiB fragment, so nothing about it can interleave with video.
    assert!(hu
        .audio
        .iter()
        .all(|m| m.id == ids::av::MEDIA_WITH_TIMESTAMP
            && m.bytes.len() == 8192
            && m.timestamp_us.is_some()));
    let text = s.report().to_string();
    assert!(
        text.contains("audio_buffers_sent=4 audio_media_bytes_sent=32768 audio_acks=4"),
        "{text}"
    );
    assert!(text.contains("audio_outstanding_max=1"), "{text}");
    assert!(
        text.contains("audio_submitted=4 audio_accepted_buffers=4"),
        "{text}"
    );
    // The PCM link never borrows the H.264 vocabulary.
    assert!(!text.contains("audio_sps="), "{text}");
    assert!(!text.contains("audio_accepted_keyframes"), "{text}");
    // Video is untouched by any of it.
    assert_eq!(s.report().unhandled_count, 0, "{text}");
}

/// Stopping always sends `RELEASE`, whatever ended the stream: SYNC 3.4 owners report a
/// focus state machine that wedges until the cable is pulled, so no transient grant is
/// ever left dangling.
#[test]
fn stopping_sends_the_audio_stop_indication_and_always_releases_focus() {
    let (mut s, mut hu, t0, _) = start_audio(small_frame_hu(), |_| {});
    let audio = s.audio();
    audio.submit(BufferFlags::default(), 0, &pcm(2048));
    pump(&mut s, &mut hu, t0);
    assert_eq!(hu.audio.len(), 1);

    s.on_shutdown_requested(t0);
    pump(&mut s, &mut hu, t0);
    assert!(hu.audio_stopped, "audio stop indication");
    assert_eq!(hu.audio_focus_requests, vec![1, 4], "GAIN then RELEASE");
    let text = s.report().to_string();
    assert!(text.contains("audio_stop_indication_sent=true"), "{text}");
    assert!(text.contains("audio_focus_released=true"), "{text}");
    // The app is told to stop producing, once.
    let stops: Vec<String> = s
        .take_app_events()
        .into_iter()
        .filter_map(|e| match e {
            AppEvent::AudioStop { reason } => Some(reason),
            _ => None,
        })
        .collect();
    assert_eq!(stops, vec!["projection stopping".to_owned()]);
}

/// A head unit that ends the session itself gets a `RELEASE` too. The video stop
/// indication is deliberately not sent on this path — the head unit is the one tearing
/// down — but a dangling focus grant is the one thing that outlives the session.
#[test]
fn a_head_unit_initiated_shutdown_still_releases_audio_focus() {
    let (mut s, mut hu, t0, _) = start_audio(small_frame_hu(), |_| {});
    s.audio().submit(BufferFlags::default(), 0, &pcm(2048));
    pump(&mut s, &mut hu, t0);
    hu.send_raw(
        0,
        false,
        true,
        encode_message(
            ids::SHUTDOWN_REQUEST,
            &aap_proto::messages::ShutdownRequest { reason: 1 },
        ),
    );
    pump(&mut s, &mut hu, t0);
    assert_eq!(category(&s), Some(Category::PeerShutdown), "{}", detail(&s));
    assert_eq!(hu.audio_focus_requests, vec![1, 4], "GAIN then RELEASE");
    assert!(hu.audio_stopped);
    let text = s.report().to_string();
    assert!(text.contains("audio_focus_released=true"), "{text}");
    // The video path is unchanged: this source still sends no stop indication when the
    // head unit is the one shutting down.
    assert!(text.contains("video_stop_indication_sent=false"), "{text}");
    assert!(s
        .take_app_events()
        .iter()
        .any(|e| matches!(e, AppEvent::AudioStop { .. })));
}

/// Every audio failure is non-fatal. Ford advertises four audio channels and has never
/// been asked to open one, so each of these is a live possibility.
#[test]
fn a_refused_audio_channel_setup_or_focus_costs_only_the_audio() {
    // Refused channel open.
    let (s, hu, _) = start(
        hu_sim::Config {
            projection: hu_sim::Projection {
                opens: vec![(5, Open::Reject(-5))],
                setup_configs: Some(vec![1]),
                ..Default::default()
            },
            ..hu_sim::Config::default()
        },
        |c| c.projection.audio = true,
    );
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    assert_eq!(hu.opened, vec![3, 1]);
    let text = s.report().to_string();
    assert!(
        text.contains("audio_channel=5 audio_open=false audio_refused=true"),
        "{text}"
    );
    assert!(text.contains("audio_setup_status=-"), "{text}");

    // Refused AV setup.
    let (s, hu, _) = start(
        hu_sim::Config {
            projection: hu_sim::Projection {
                audio_setup_status: 1,
                setup_configs: Some(vec![1]),
                ..Default::default()
            },
            ..hu_sim::Config::default()
        },
        |c| c.projection.audio = true,
    );
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    assert_eq!(hu.opened, vec![3, 1, 5]);
    let text = s.report().to_string();
    assert!(
        text.contains("audio_open=true audio_refused=true"),
        "{text}"
    );
    assert!(text.contains("audio_setup_status=1 (FAIL)"), "{text}");
    assert_eq!(hu.audio_focus_requests, Vec::<i32>::new(), "no focus asked");

    // Focus denied outright.
    let (s, hu, _) = start(
        hu_sim::Config {
            projection: hu_sim::Projection {
                audio_focus: hu_sim::AudioFocus::State(3),
                setup_configs: Some(vec![1]),
                ..Default::default()
            },
            ..hu_sim::Config::default()
        },
        |c| c.projection.audio = true,
    );
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    let text = s.report().to_string();
    assert!(
        text.contains("audio_focus_state=3 (LOSS) audio_focus_unsolicited=false"),
        "{text}"
    );
    assert!(text.contains("audio_started_ms=-"), "{text}");
    // A denial is counted as one, not as a loss: nothing was ever held. It is a reply, so
    // field 2 is false and it is not counted as volunteered either.
    assert!(
        text.contains("audio_focus_denials=1 audio_focus_volunteered=0 audio_focus_losses=0"),
        "{text}"
    );
    assert!(text.contains("audio_focus_unsolicited=false"), "{text}");
    // No RELEASE. There is no grant to give up, and claiming one puts a state on the wire
    // this source does not hold.
    assert_eq!(hu.audio_focus_requests, vec![1]);
    assert!(text.contains("audio_focus_released=false"), "{text}");
    assert!(!hu.audio_stopped, "the stream never started");
}

/// Ford SYNC 4.0 answers a granted focus request with `{state: GAIN, field 2: false}`
/// (reports 5cb646ec and 9eb3a396), and an earlier refusal with `{state: LOSS, field 2:
/// false}` (f9ce185b, dc2c60ee). **Field 2 is the same in both**, so it cannot be the
/// `granted` flag the vendored schema names it — it is `unsolicited`, confirmed against
/// the Android Auto 17.6 dex — and a source that gates on it throws a real grant away,
/// which is exactly what happened on the vehicle.
///
/// Only `audio_focus_state`, the message's one required field, decides.
#[test]
fn a_gain_is_a_grant_whatever_field_two_says() {
    for (focus, name) in [
        (hu_sim::AudioFocus::Grant, "unsolicited=false"),
        (hu_sim::AudioFocus::GrantWithoutFlag, "absent"),
        (
            hu_sim::AudioFocus::GrantMarkedUnsolicited,
            "unsolicited=true",
        ),
    ] {
        let (mut s, mut hu, t0, start) = start_audio(
            hu_sim::Config {
                projection: hu_sim::Projection {
                    audio_focus: focus,
                    setup_configs: Some(vec![1]),
                    ..Default::default()
                },
                ..hu_sim::Config::default()
            },
            |_| {},
        );
        let text = s.report().to_string();
        assert!(start.is_some(), "field 2 {name} refused the grant\n{text}");
        assert_eq!(
            hu.audio_session,
            Some(0),
            "field 2 {name}: no start indication"
        );
        assert!(
            text.contains("audio_focus_denials=0"),
            "field 2 {name} was counted as a denial\n{text}"
        );
        s.audio().submit(BufferFlags::default(), 0, &pcm(2048));
        pump(&mut s, &mut hu, t0);
        assert_eq!(hu.audio.len(), 1, "field 2 {name}: no audio flowed");
    }
}

/// A head unit may deny now and grant later — when the operator selects the projection as
/// the audio source, say. The request stands, and the grant starts the stream.
///
/// This is the behaviour the first vehicle attempt needed and did not have: Ford SYNC 4.0
/// answered `LOSS` with `granted=false`, and giving up permanently on that would make a
/// head unit that grants late indistinguishable from one that never grants.
#[test]
fn a_denied_audio_focus_can_still_be_granted_later() {
    let (mut s, mut hu, t0, start) = start_audio(
        hu_sim::Config {
            projection: hu_sim::Projection {
                audio_focus: hu_sim::AudioFocus::State(3),
                setup_configs: Some(vec![1]),
                ..Default::default()
            },
            ..hu_sim::Config::default()
        },
        |_| {},
    );
    assert!(start.is_none(), "denied, so nothing started");
    assert!(!hu.audio_focus_requests.contains(&4), "nothing to release");

    // The head unit changes its mind, unprompted.
    hu.send_audio_focus(1, Some(true));
    pump(&mut s, &mut hu, t0);
    let start = s.take_app_events().into_iter().find_map(|e| match e {
        AppEvent::AudioStart(start) => Some(start),
        _ => None,
    });
    assert!(start.is_some(), "the late grant starts the stream");
    assert_eq!(
        hu.audio_session,
        Some(0),
        "an audio start indication went out"
    );

    let audio = s.audio();
    audio.submit(BufferFlags::default(), 0, &pcm(2048));
    pump(&mut s, &mut hu, t0);
    assert_eq!(hu.audio.len(), 1);
    let text = s.report().to_string();
    assert!(
        text.contains("audio_focus_denials=1 audio_focus_volunteered=1 audio_focus_losses=0"),
        "{text}"
    );
    // The sequence is the evidence: a solicited denial and then a volunteered grant, in
    // that order. The final state alone would say only "GAIN" and hide the refusal.
    assert!(
        text.contains("audio_focus_sequence=") && text.contains(":3:false,"),
        "{text}"
    );
    assert!(text.contains(":1:true"), "the volunteered grant\n{text}");
    // Now a grant *is* held, so stopping owes a RELEASE.
    s.on_shutdown_requested(t0);
    pump(&mut s, &mut hu, t0);
    assert_eq!(hu.audio_focus_requests, vec![1, 4]);
}

/// A head unit that never answers the focus request skips audio once the wait elapses,
/// and the picture keeps running.
#[test]
fn a_silent_audio_focus_response_skips_audio_after_its_wait() {
    let (mut s, mut hu, t0, start) = start_audio(
        hu_sim::Config {
            projection: hu_sim::Projection {
                audio_focus: hu_sim::AudioFocus::Silent,
                setup_configs: Some(vec![1]),
                ..Default::default()
            },
            ..hu_sim::Config::default()
        },
        |c| c.projection.audio_focus_wait = Duration::from_secs(2),
    );
    assert!(start.is_none(), "no grant, no start");
    let later = t0 + Duration::from_secs(3);
    pump(&mut s, &mut hu, later);
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    let text = s.report().to_string();
    assert!(
        text.contains("audio_focus_requests=1 audio_focus_responses=0"),
        "{text}"
    );
    assert!(text.contains("audio_refused=true"), "{text}");
    // Nothing was granted, so nothing is released.
    assert_eq!(hu.audio_focus_requests, vec![1]);
    assert!(text.contains("audio_focus_released=false"), "{text}");
}

/// `LOSS_TRANSIENT_CAN_DUCK` is informational: the head unit lowers the media channel in
/// its own mixer, so this source keeps sending at full amplitude and gets no stop
/// indication. A real `LOSS` does stop the stream.
#[test]
fn a_duck_keeps_the_audio_flowing_and_a_loss_stops_it() {
    let duck_hu = |focus| hu_sim::Config {
        projection: hu_sim::Projection {
            audio_focus: focus,
            audio_max_unacked: Some(4),
            setup_configs: Some(vec![1]),
            ..Default::default()
        },
        ..hu_sim::Config::default()
    };
    let (mut s, mut hu, t0, _) = start_audio(duck_hu(hu_sim::AudioFocus::GrantThenDuck(2)), |_| {});
    let audio = s.audio();
    for i in 0..4 {
        audio.submit(BufferFlags::default(), 42_666 * i, &pcm(2048));
        pump(&mut s, &mut hu, t0);
    }
    assert_eq!(hu.audio.len(), 4, "the duck never interrupted the stream");
    let text = s.report().to_string();
    assert!(text.contains("audio_focus_ducks=1"), "{text}");
    assert!(text.contains("audio_focus_losses=0"), "{text}");
    assert!(!hu.audio_stopped);

    let (mut s, mut hu, t0, _) = start_audio(duck_hu(hu_sim::AudioFocus::GrantThenLoss(2)), |_| {});
    let audio = s.audio();
    for i in 0..4 {
        audio.submit(BufferFlags::default(), 42_666 * i, &pcm(2048));
        pump(&mut s, &mut hu, t0);
    }
    assert_eq!(hu.audio.len(), 2, "the stream paused at the loss");
    let text = s.report().to_string();
    assert!(text.contains("audio_focus_losses=1"), "{text}");
    // A loss pauses, exactly as video focus loss does: no stop indication and no RELEASE,
    // so a later grant resumes without renegotiating the channel.
    assert!(!hu.audio_stopped, "{text}");
    assert!(!hu.audio_focus_requests.contains(&4), "{text}");
    assert_eq!(s.report().stage, Stage::Projecting, "the picture survives");
    // The app is told to stop producing, so it is not feeding a stream nobody hears.
    assert!(s
        .take_app_events()
        .iter()
        .any(|e| matches!(e, AppEvent::AudioStop { .. })));

    // Focus comes back: the app is told to start again, and the flow resumes on the same
    // session without a second start indication.
    hu.send_audio_focus(1, Some(true));
    pump(&mut s, &mut hu, t0);
    assert!(s
        .take_app_events()
        .iter()
        .any(|e| matches!(e, AppEvent::AudioStart(_))));
    for i in 4..6 {
        audio.submit(BufferFlags::default(), 42_666 * i, &pcm(2048));
        pump(&mut s, &mut hu, t0);
    }
    assert_eq!(hu.audio.len(), 4, "the stream resumed");
    let text = s.report().to_string();
    assert!(text.contains("audio_focus_regains=1"), "{text}");
}

/// `0x800B AUDIO_UNDERFLOW` is accepted on an audio channel, never answered, and never
/// counted as an unhandled message. No open-source head unit sends it, so the lab
/// receiver and the simulator both need an injection switch or the path is never tested.
#[test]
fn an_audio_underflow_is_accepted_recorded_and_never_answered() {
    // Modern Android Auto parses no payload for it; older schemas define a required
    // session id. Both shapes have to be tolerated, so neither is decoded.
    for body in [Vec::new(), vec![0x08, 0x00]] {
        let (mut s, mut hu, t0, _) = start_audio(
            hu_sim::Config {
                projection: hu_sim::Projection {
                    underflow_after_audio: Some((1, body.clone())),
                    setup_configs: Some(vec![1]),
                    ..Default::default()
                },
                ..hu_sim::Config::default()
            },
            |_| {},
        );
        let audio = s.audio();
        audio.submit(BufferFlags::default(), 0, &pcm(2048));
        pump(&mut s, &mut hu, t0);
        let text = s.report().to_string();
        assert!(
            text.contains(&format!(
                "audio_underflows=1 audio_underflow_body_len={}",
                body.len()
            )),
            "{text}"
        );
        assert_eq!(s.report().unhandled_count, 0, "{text}");
        assert_eq!(s.report().stage, Stage::Projecting);
        // Nothing was sent back on the audio channel in reply.
        assert!(hu.errors.is_empty(), "{:?}", hu.errors);
    }
}

/// The PCM link refuses what it cannot carry and never waits for a keyframe, because
/// every buffer is independently playable.
#[test]
fn the_pcm_link_validates_buffers_without_a_keyframe_or_configuration() {
    let (mut s, mut hu, t0, _) = start_audio(small_frame_hu(), |_| {});
    let audio = s.audio();
    // No IDR to wait for: the very first buffer is accepted.
    assert_eq!(
        audio.submit(BufferFlags::default(), 0, &pcm(2048)),
        Submit::Accepted
    );
    // A codec-configuration buffer has no meaning for PCM.
    assert_eq!(
        audio.submit(CONFIG, 0, &pcm(16)),
        Submit::Invalid,
        "PCM has no codec configuration"
    );
    // Not whole 16-bit samples.
    assert_eq!(
        audio.submit(BufferFlags::default(), 0, &[0u8; 3]),
        Submit::Invalid
    );
    // Larger than one outbound fragment, which is what keeps audio unfragmented.
    assert_eq!(
        audio.submit(
            BufferFlags::default(),
            0,
            &vec![0u8; media::AUDIO_UNIT_LIMIT + 2]
        ),
        Submit::Invalid
    );
    assert_eq!(
        audio.submit(BufferFlags::default(), -1, &pcm(16)),
        Submit::Invalid
    );
    pump(&mut s, &mut hu, t0);
    assert_eq!(hu.audio.len(), 1);
    let text = s.report().to_string();
    assert!(text.contains("audio_invalid=4"), "{text}");
    assert!(
        text.contains(&format!("audio_largest_unit={}", 2048 * 4)),
        "{text}"
    );
}

// ---------------------------------------------------------------------------------
// P6.2 — the head unit's own instrument cluster
// ---------------------------------------------------------------------------------

/// Two legs at 10 m/s: the first maneuver at 20 s, the destination at 40 s, so a whole
/// route fits inside a test without a four-minute drive.
fn short_script() -> Script {
    Script::new(
        vec![
            Leg::new(
                "First Road",
                200.0,
                8,
                "Turn right onto Second Road",
                &["Second Road"],
            ),
            Leg::new("Second Road", 200.0, 39, "You have arrived", &[]),
        ],
        10.0,
    )
    .expect("a driveable script")
}

fn navigation_hu(cluster: Cluster, nav_focus: NavFocus) -> hu_sim::Config {
    hu_sim::Config {
        projection: hu_sim::Projection {
            setup_configs: Some(vec![1]),
            cluster,
            nav_focus,
            ..Default::default()
        },
        ..hu_sim::Config::default()
    }
}

/// The channel is opt-in, opens after now-playing and before sensors, and is never
/// touched when nobody asked for it.
#[test]
fn the_navigation_channel_is_opt_in_and_opens_after_now_playing() {
    let (s, hu, _) = start(small_frame_hu(), |_| {});
    assert_eq!(hu.opened, vec![3, 1], "advertised but not asked for");
    let text = s.report().to_string();
    assert!(
        text.contains("navigation_requested=false navigation_channel=-"),
        "{text}"
    );
    // Discovery still read the descriptor: advertised and driven are different facts.
    assert!(text.contains("discovery_navigation=channel=11"), "{text}");

    let (s, hu, _) = start(small_frame_hu(), |c| {
        c.projection.media_info = true;
        c.projection.navigation = true;
        c.projection.sensors = SensorSet::Restriction;
    });
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    assert_eq!(hu.opened, vec![3, 1, 10, 11, 2]);
    let text = s.report().to_string();
    assert!(
        text.contains("navigation_requested=true navigation_channel=11 navigation_open=true"),
        "{text}"
    );
}

/// The whole handshake and one update, decoded by the head unit rather than compared
/// against what the source believes it sent: the cluster announces itself, focus is asked
/// for on the **control** channel, the state goes active, and a maneuver and its distance
/// follow.
#[test]
fn a_scripted_maneuver_sequence_reaches_the_cluster_once_focus_is_granted() {
    let (s, hu, _) = start(navigation_hu(Cluster::OnOpen, NavFocus::Projected), |c| {
        c.projection.navigation = true;
        c.projection.navigation_script = Some(short_script());
    });
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);

    // Focus: `NAV_FOCUS_PROJECTED` (2), and exactly once.
    assert_eq!(hu.nav_focus_requests, vec![2]);
    // State: active, and nothing else yet.
    assert_eq!(hu.nav_states, vec![1], "{}", s.report());

    let notification = hu.nav_notifications.last().expect("a maneuver was sent");
    assert_eq!(notification.steps.len(), 1);
    let step = &notification.steps[0];
    let maneuver = step.maneuver.as_ref().expect("a maneuver");
    assert_eq!(maneuver.r#type, Some(8));
    // Not known, so not claimed: proto2 tells absent apart from zero.
    assert_eq!(maneuver.roundabout_turn_angle, None);
    assert_eq!(maneuver.roundabout_exit_number, None);
    assert_eq!(
        step.instruction.as_ref().and_then(|t| t.text.as_deref()),
        Some("Turn right onto Second Road")
    );
    assert_eq!(
        step.road_info.as_ref().map(|r| r.road_names.clone()),
        Some(vec!["Second Road".to_owned()])
    );
    // Out of scope for P6 and never filled from a script: nothing here could produce lane
    // data from a real route.
    assert!(step.lanes.is_empty());
    // A destination with no address says nothing a cluster can show.
    assert!(notification.destinations.is_empty());

    let distance = hu.nav_distances.last().expect("a distance was sent");
    let step_distance = distance.step_distance.as_ref().expect("a step distance");
    let rendered = step_distance.distance.as_ref().expect("a distance");
    // 200 m at the start of the route, imperial by default: 656 ft, rounded to ten.
    assert_eq!(rendered.distance_value, Some(660));
    assert_eq!(rendered.distance_unit, Some(6)); // DISTANCE_UNIT_FEET
    assert_eq!(rendered.display_text.as_deref(), Some("660 ft"));
    assert_eq!(step_distance.time_to_step_seconds, Some(20));
    let destination = distance
        .destination_distances
        .first()
        .expect("a destination distance");
    assert_eq!(destination.time_to_arrival_seconds, Some(40));
    // The clock format is unrecorded and this source does not know the vehicle's, so it
    // sends the seconds and invents no time of day.
    assert_eq!(destination.estimated_time_of_arrival, None);
    assert_eq!(
        distance
            .current_road
            .as_ref()
            .and_then(|t| t.text.as_deref()),
        Some("First Road")
    );

    let text = s.report().to_string();
    assert!(
        text.contains("navigation_cluster_starts=1 navigation_cluster_stops=0"),
        "{text}"
    );
    assert!(
        text.contains("navigation_focus_type=2 (NAV_FOCUS_PROJECTED)"),
        "{text}"
    );
    assert!(
        text.contains("navigation_states_sent=NAV_STATE_ACTIVE:1"),
        "{text}"
    );
    assert!(text.contains("navigation_forced=false"), "{text}");
    assert!(text.contains("TURN_NORMAL_RIGHT"), "{text}");
    // Spec §10: no road name and no destination in ordinary evidence,
    // scripted or not.
    assert!(!text.contains("Second Road"), "{text}");
    assert!(!text.contains("First Road"), "{text}");
}

/// The cluster is waited for, not assumed (docs/navigation.md, "Instrument cluster turn-by-turn"). A head unit that opens the
/// channel and never announces one is told nothing at all, and the report says which of
/// the two happened.
#[test]
fn nothing_is_sent_to_a_cluster_that_never_announces_itself() {
    let (mut s, mut hu, t0) = start(navigation_hu(Cluster::Silent, NavFocus::Projected), |c| {
        c.projection.navigation = true;
        c.projection.navigation_script = Some(short_script());
        c.projection.navigation_focus_wait = Duration::from_secs(2);
    });
    assert_eq!(hu.opened, vec![3, 1, 11]);
    assert!(
        hu.nav_focus_requests.is_empty(),
        "focus asked for with no cluster"
    );
    assert!(hu.nav_states.is_empty());
    assert!(hu.nav_notifications.is_empty() && hu.nav_distances.is_empty());

    // Past the wait, the silence is recorded and still nothing is sent.
    pump(&mut s, &mut hu, t0 + Duration::from_secs(3));
    let text = s.report().to_string();
    assert!(text.contains("navigation_cluster_timeout=true"), "{text}");
    assert!(text.contains("navigation_focus_requests=0"), "{text}");
    assert!(text.contains("navigation_notifications_sent=0"), "{text}");
    assert!(hu.nav_states.is_empty(), "{text}");
}

/// `navigation_force` turns the same drive into the other experiment: the sequence goes
/// out with no cluster announcement and no focus answer at all, which is the degraded
/// pass G9 allows, and the report says nothing was waited for.
#[test]
fn a_forced_sequence_is_sent_past_a_silent_cluster_and_says_so() {
    let (mut s, mut hu, t0) = start(navigation_hu(Cluster::Silent, NavFocus::Silent), |c| {
        c.projection.navigation = true;
        c.projection.navigation_force = true;
        c.projection.navigation_script = Some(short_script());
        c.projection.navigation_focus_wait = Duration::from_secs(2);
    });
    // Forcing does not mean skipping the exchange: the cluster is still asked for, and
    // the wait is still waited out. It means not giving up when nobody answers.
    assert_eq!(hu.nav_focus_requests, vec![2], "focus is still asked for");
    assert!(hu.nav_states.is_empty(), "sent before the wait elapsed");
    pump(&mut s, &mut hu, t0 + Duration::from_secs(3));
    assert_eq!(hu.nav_states, vec![1]);
    assert_eq!(hu.nav_notifications.len(), 1);
    assert_eq!(hu.nav_distances.len(), 1);
    let text = s.report().to_string();
    assert!(text.contains("navigation_forced=true"), "{text}");
    assert!(text.contains("navigation_cluster_starts=0"), "{text}");
    assert!(text.contains("navigation_cluster_timeout=true"), "{text}");
}

/// A head unit keeping its own navigation is not a permanent refusal: the request stands,
/// and a grant that arrives later starts the sequence. The same rule audio focus learned
/// on the vehicle — a non-grant is not a no forever.
#[test]
fn native_focus_holds_the_sequence_and_a_later_grant_starts_it() {
    let (mut s, mut hu, t0) = start(
        navigation_hu(Cluster::OnOpen, NavFocus::NativeThenProjected(3)),
        |c| {
            c.projection.navigation = true;
            c.projection.navigation_script = Some(short_script());
        },
    );
    assert_eq!(hu.nav_focus_requests, vec![2]);
    assert!(hu.nav_states.is_empty(), "sent without focus");
    let text = s.report().to_string();
    assert!(text.contains("navigation_focus_denials=1"), "{text}");

    // Three media units later the head unit volunteers the cluster.
    let link = s.media();
    let mut source = Source::new(800, 480);
    link.submit(CONFIG, 0, &source.config());
    for unit in 0..4i64 {
        let (frame, flags) = source.frame(unit == 0);
        link.submit(flags, unit * 1_000, &frame);
        pump(
            &mut s,
            &mut hu,
            t0 + Duration::from_millis(unit as u64 * 10),
        );
    }
    assert_eq!(hu.nav_states, vec![1], "a late grant never started it");
    assert!(!hu.nav_notifications.is_empty());
    assert!(s
        .report()
        .to_string()
        .contains("navigation_focus_type=2 (NAV_FOCUS_PROJECTED)"));
}

/// The head unit's advertised `minimum_interval_ms` is a floor under this source's own
/// interval, not a default for it. The simulator advertises 300 ms and a source asking
/// for 1 ms still sends no faster than that.
#[test]
fn the_advertised_interval_is_a_floor_under_the_configured_one() {
    let (mut s, mut hu, t0) = start(navigation_hu(Cluster::OnOpen, NavFocus::Projected), |c| {
        c.projection.navigation = true;
        c.projection.navigation_script = Some(short_script());
        c.projection.navigation_interval = Duration::from_millis(1);
    });
    let text = s.report().to_string();
    assert!(
        text.contains(
            "navigation_min_interval_ms=300 navigation_interval_ms=300 navigation_floor=head-unit"
        ),
        "{text}"
    );
    assert_eq!(hu.nav_distances.len(), 1);
    // Well inside the floor: nothing new goes out.
    for step in 1..=5 {
        pump(&mut s, &mut hu, t0 + Duration::from_millis(step * 50));
    }
    assert_eq!(hu.nav_distances.len(), 1, "{}", s.report());
    // Past it, exactly one more update.
    pump(&mut s, &mut hu, t0 + Duration::from_millis(350));
    assert_eq!(hu.nav_distances.len(), 2);

    // And the other way round: a source slower than the head unit keeps its own interval,
    // so the floor never speeds anything up.
    let (mut s, mut hu, t0) = start(navigation_hu(Cluster::OnOpen, NavFocus::Projected), |c| {
        c.projection.navigation = true;
        c.projection.navigation_script = Some(short_script());
        c.projection.navigation_interval = Duration::from_millis(900);
    });
    assert!(s
        .report()
        .to_string()
        .contains("navigation_interval_ms=900 navigation_floor=source"));
    assert_eq!(hu.nav_distances.len(), 1);
    pump(&mut s, &mut hu, t0 + Duration::from_millis(500));
    assert_eq!(hu.nav_distances.len(), 1);
    pump(&mut s, &mut hu, t0 + Duration::from_millis(950));
    assert_eq!(hu.nav_distances.len(), 2);
}

/// A refused cluster channel costs the integration, not the drive — the same rule as the
/// sensor and now-playing channels. Ford has advertised this one since the first vehicle
/// run and has never been asked to open it, so a refusal is a live possibility.
#[test]
fn a_refused_navigation_channel_is_recorded_and_projection_continues() {
    let (s, hu, _) = start(
        hu_sim::Config {
            projection: hu_sim::Projection {
                opens: vec![(11, Open::Reject(-5))],
                setup_configs: Some(vec![1]),
                ..Default::default()
            },
            ..hu_sim::Config::default()
        },
        |c| c.projection.navigation = true,
    );
    assert_eq!(s.report().stage, Stage::Projecting, "{}", s.report());
    assert_eq!(hu.opened, vec![3, 1]);
    let text = s.report().to_string();
    assert!(
        text.contains("navigation_open=false navigation_refused=true"),
        "{text}"
    );
    assert!(text.contains("navigation_focus_requests=0"), "{text}");
}

/// Stopping always withdraws the state and hands the cluster back, whatever the reason —
/// the rule audio focus was given after SYNC 3.4 owners reported a focus machine that
/// wedges until the cable is pulled. There is no `RELEASE` here, so handing back is a
/// request for the head unit's own navigation.
#[test]
fn stopping_goes_inactive_and_hands_the_cluster_back() {
    let (mut s, mut hu, t0) = start(navigation_hu(Cluster::OnOpen, NavFocus::Projected), |c| {
        c.projection.navigation = true;
        c.projection.navigation_script = Some(short_script());
        c.projection.limit = Some(Duration::from_millis(50));
    });
    assert_eq!(hu.nav_states, vec![1]);
    pump(&mut s, &mut hu, t0 + Duration::from_millis(60));
    assert_eq!(hu.nav_states, vec![1, 2], "{}", s.report());
    assert_eq!(
        hu.nav_focus_requests,
        vec![2, 1],
        "the cluster was not handed back"
    );
    let text = s.report().to_string();
    assert!(text.contains("navigation_focus_released=true"), "{text}");
    assert!(
        text.contains("navigation_states_sent=NAV_STATE_ACTIVE:1,NAV_STATE_INACTIVE:1"),
        "{text}"
    );
    // The echo of our own hand-back is not a refusal.
    assert!(text.contains("navigation_focus_denials=0"), "{text}");
}

/// A head unit tearing the session down still gets the state withdrawn and the cluster
/// handed back, for the same reason audio focus is released there.
#[test]
fn a_head_unit_initiated_shutdown_still_hands_the_cluster_back() {
    let (mut s, mut hu, t0) = start(navigation_hu(Cluster::OnOpen, NavFocus::Projected), |c| {
        c.projection.navigation = true;
        c.projection.navigation_script = Some(short_script());
    });
    assert_eq!(hu.nav_states, vec![1]);
    hu.send_raw(
        0,
        false,
        true,
        encode_message(
            ids::SHUTDOWN_REQUEST,
            &aap_proto::messages::ShutdownRequest { reason: 1 },
        ),
    );
    pump(&mut s, &mut hu, t0 + Duration::from_millis(10));
    assert_eq!(hu.nav_states, vec![1, 2]);
    assert_eq!(hu.nav_focus_requests, vec![2, 1]);
}

/// A route that arrives ends itself: inactive, cluster handed back, and nothing more on
/// the channel. The whole lifecycle then fits inside one session's evidence.
#[test]
fn a_completed_route_ends_the_sequence_by_itself() {
    let (mut s, mut hu, t0) = start(navigation_hu(Cluster::OnOpen, NavFocus::Projected), |c| {
        c.projection.navigation = true;
        c.projection.navigation_script = Some(short_script());
        c.projection.navigation_interval = Duration::from_millis(500);
    });
    // A live stream, so the route outlives the first-unit deadline: this test runs for
    // forty-five seconds of session time and a silent encoder ends one in ten.
    let link = s.media();
    let mut source = Source::new(800, 480);
    link.submit(CONFIG, 0, &source.config());
    let (frame, flags) = source.frame(true);
    link.submit(flags, 1_000, &frame);
    // Drive past the second leg's maneuver and then past the destination: 40 s of route.
    for step in 1..=90u64 {
        pump(&mut s, &mut hu, t0 + Duration::from_millis(step * 500));
    }
    let arrived = hu.nav_notifications.iter().any(|n| {
        n.steps
            .first()
            .and_then(|s| s.maneuver.as_ref())
            .and_then(|m| m.r#type)
            == Some(39)
    });
    assert!(arrived, "the second maneuver never went out");
    assert_eq!(hu.nav_states, vec![1, 2], "{}", s.report());
    assert_eq!(hu.nav_focus_requests, vec![2, 1]);
    let text = s.report().to_string();
    assert!(text.contains("navigation_completed=true"), "{text}");
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);

    // Nothing more is owed: an ended route leaves no deadline for the poll loop to spin
    // on, the defect P5.1 found on the now-playing channel.
    let idle = t0 + Duration::from_secs(60);
    pump(&mut s, &mut hu, idle);
    assert!(
        s.next_deadline().is_none_or(|d| d > idle),
        "a finished route left a past deadline: {}",
        s.report()
    );
}

/// The cluster going away stops the maneuvers without abandoning the route, and it coming
/// back resumes where the script's clock has got to.
#[test]
fn a_cluster_that_stops_and_starts_again_pauses_and_resumes() {
    let (mut s, mut hu, t0) = start(
        navigation_hu(Cluster::StopAfter(3), NavFocus::Projected),
        |c| {
            c.projection.navigation = true;
            c.projection.navigation_script = Some(short_script());
            c.projection.navigation_interval = Duration::from_millis(500);
        },
    );
    pump(&mut s, &mut hu, t0 + Duration::from_millis(600));
    let stopped_at = hu.nav_distances.len();
    assert!(
        s.report()
            .to_string()
            .contains("navigation_cluster_stops=1"),
        "{}",
        s.report()
    );
    pump(&mut s, &mut hu, t0 + Duration::from_millis(1200));
    assert_eq!(
        hu.nav_distances.len(),
        stopped_at,
        "sent to a cluster that is gone"
    );
    // The route is not abandoned: no INACTIVE, no hand-back.
    assert_eq!(hu.nav_states, vec![1]);
    assert_eq!(hu.nav_focus_requests, vec![2]);

    hu.send_cluster(true);
    pump(&mut s, &mut hu, t0 + Duration::from_millis(1800));
    assert!(
        hu.nav_distances.len() > stopped_at,
        "the cluster came back and was told nothing: {}",
        s.report()
    );
}

// ---------------------------------------------------------------------------------
// P6.5 — the cluster driven by the app's guidance loop
// ---------------------------------------------------------------------------------

fn maneuver_of(n: &aap_proto::messages::NavigationNotification) -> Option<i32> {
    n.steps
        .first()
        .and_then(|s| s.maneuver.as_ref())
        .and_then(|m| m.r#type)
}

/// The road names the app read from its map reach the cluster: the road turned onto in
/// the notification's `road_info` beside the instruction that names it, and the road being
/// driven as the distance message's `current_road`.
#[test]
fn a_live_route_puts_its_road_names_on_the_cluster() {
    let (mut s, mut hu, t0) = start(navigation_hu(Cluster::OnOpen, NavFocus::Projected), |c| {
        c.projection.navigation = true;
        c.projection.navigation_source = NavSource::Route;
        c.projection.navigation_interval = Duration::from_millis(500);
    });
    let media = s.media();
    let mut source = Source::new(800, 480);
    media.submit(CONFIG, 0, &source.config());
    let (frame, flags) = source.frame(true);
    media.submit(flags, 1_000, &frame);

    s.guidance().publish(RouteState::Guiding(
        Guidance::live(
            1,
            8,
            None,
            "Turn right onto Main Street",
            300.0,
            30,
            1000.0,
            100,
        )
        .with_roads("Main Street", "Elm Avenue"),
    ));
    pump(&mut s, &mut hu, t0 + Duration::from_millis(10));
    let step = &hu.nav_notifications.last().expect("a maneuver").steps[0];
    assert_eq!(
        step.instruction.as_ref().and_then(|t| t.text.as_deref()),
        Some("Turn right onto Main Street")
    );
    assert_eq!(
        step.road_info.as_ref().map(|r| r.road_names.clone()),
        Some(vec!["Main Street".to_owned()])
    );
    let d = hu.nav_distances.last().expect("a distance");
    assert_eq!(
        d.current_road.as_ref().and_then(|t| t.text.as_deref()),
        Some("Elm Avenue")
    );
    // The report says what was sent, never where.
    let report = s.report().to_string();
    assert!(
        !report.contains("Main Street") && !report.contains("Elm Avenue"),
        "{report}"
    );
}

/// A live route's whole life on the wire: nothing before there is a route, `ACTIVE` and
/// a maneuver once there is, a countdown held to the interval, a new notification only
/// when the maneuver changes, `REROUTING` and back, and `INACTIVE` on arrival — with the
/// cluster kept for the next destination rather than handed back.
#[test]
fn a_live_route_drives_the_cluster_through_rerouting_to_arrival() {
    let (mut s, mut hu, t0) = start(navigation_hu(Cluster::OnOpen, NavFocus::Projected), |c| {
        c.projection.navigation = true;
        c.projection.navigation_source = NavSource::Route;
        c.projection.navigation_interval = Duration::from_millis(500);
    });
    // A live stream, so the session outlives the first-unit deadline.
    let media = s.media();
    let mut source = Source::new(800, 480);
    media.submit(CONFIG, 0, &source.config());
    let (frame, flags) = source.frame(true);
    media.submit(flags, 1_000, &frame);
    // Focus is still asked for and granted: the cluster is claimed as soon as it is
    // there, so the first destination does not wait on a handshake.
    assert_eq!(hu.nav_focus_requests, vec![2]);
    // No route yet, so no state at all: `ACTIVE` with nothing behind it is a claim.
    assert!(hu.nav_states.is_empty(), "{}", s.report());
    assert!(hu.nav_notifications.is_empty() && hu.nav_distances.is_empty());

    let link = s.guidance();
    let at = |ms: u64| t0 + Duration::from_millis(ms);
    link.publish(RouteState::Guiding(Guidance::live(
        1,
        8,
        None,
        "Turn right",
        300.0,
        30,
        1000.0,
        100,
    )));
    pump(&mut s, &mut hu, at(10));
    assert_eq!(hu.nav_states, vec![1], "{}", s.report());
    let n = hu.nav_notifications.last().expect("a maneuver");
    assert_eq!(maneuver_of(n), Some(8));
    let step = &n.steps[0];
    assert_eq!(
        step.instruction.as_ref().and_then(|t| t.text.as_deref()),
        Some("Turn right")
    );
    // BRouter's data has no road names; none is invented.
    assert!(step
        .road_info
        .as_ref()
        .is_none_or(|r| r.road_names.is_empty()));
    assert_eq!(step.maneuver.as_ref().unwrap().roundabout_exit_number, None);
    let d = hu.nav_distances.last().expect("a distance");
    assert_eq!(d.current_road, None, "an empty road name was sent");
    let rendered = d.step_distance.as_ref().unwrap().distance.as_ref().unwrap();
    // 300 m is 984 ft, rounded to ten.
    assert_eq!(rendered.display_text.as_deref(), Some("980 ft"));

    // The countdown moves faster than the interval: the newest value waits for it.
    let (notes, dists) = (hu.nav_notifications.len(), hu.nav_distances.len());
    link.publish(RouteState::Guiding(Guidance::live(
        1,
        8,
        None,
        "Turn right",
        250.0,
        25,
        950.0,
        95,
    )));
    pump(&mut s, &mut hu, at(100));
    assert_eq!(hu.nav_distances.len(), dists, "sent inside the interval");
    link.publish(RouteState::Guiding(Guidance::live(
        1,
        8,
        None,
        "Turn right",
        200.0,
        20,
        900.0,
        90,
    )));
    pump(&mut s, &mut hu, at(600));
    assert_eq!(hu.nav_distances.len(), dists + 1);
    assert_eq!(
        hu.nav_notifications.len(),
        notes,
        "an unchanged maneuver was resent"
    );
    let rendered = hu
        .nav_distances
        .last()
        .unwrap()
        .step_distance
        .clone()
        .unwrap();
    assert_eq!(
        rendered.time_to_step_seconds,
        Some(20),
        "not the newest value"
    );

    // The next maneuver.
    link.publish(RouteState::Guiding(Guidance::live(
        2,
        7,
        None,
        "Turn left",
        400.0,
        40,
        700.0,
        70,
    )));
    pump(&mut s, &mut hu, at(1200));
    assert_eq!(maneuver_of(hu.nav_notifications.last().unwrap()), Some(7));

    // Off the route and back on a new one.
    link.publish(RouteState::Rerouting);
    pump(&mut s, &mut hu, at(1300));
    assert_eq!(hu.nav_states, vec![1, 3], "{}", s.report());
    link.publish(RouteState::Guiding(Guidance::live(
        1_000_001,
        32,
        Some(2),
        "At the roundabout, take exit 2",
        150.0,
        15,
        600.0,
        60,
    )));
    pump(&mut s, &mut hu, at(1400));
    assert_eq!(hu.nav_states, vec![1, 3, 1]);
    // The state goes at once; the maneuver waits for the interval the last update
    // started at 1200 ms. The first version cleared it here, and the P6.5 lab receiver
    // measured 490 ms between updates against a 1500 ms floor.
    assert_eq!(maneuver_of(hu.nav_notifications.last().unwrap()), Some(7));
    pump(&mut s, &mut hu, at(1650));
    assert_eq!(
        maneuver_of(hu.nav_notifications.last().unwrap()),
        Some(7),
        "inside the interval"
    );
    pump(&mut s, &mut hu, at(1750));
    let n = hu.nav_notifications.last().unwrap();
    assert_eq!(maneuver_of(n), Some(32), "the new route's maneuver waited");
    assert_eq!(
        n.steps[0].maneuver.as_ref().unwrap().roundabout_exit_number,
        Some(2)
    );

    // Arrival withdraws the state and keeps the cluster.
    link.publish(RouteState::Arrived);
    pump(&mut s, &mut hu, at(2000));
    assert_eq!(hu.nav_states, vec![1, 3, 1, 2]);
    assert_eq!(hu.nav_focus_requests, vec![2], "handed back on arrival");
    let text = s.report().to_string();
    assert!(
        text.contains("navigation_source=route navigation_maneuvers=3 navigation_arrivals=1"),
        "{text}"
    );
    assert!(text.contains("NAV_STATE_REROUTING:1"), "{text}");
    assert!(text.contains("navigation_completed=true"), "{text}");
    assert!(text.contains("navigation_script=-"), "{text}");
    // Spec §10: the instruction text is on the cluster and never in the report.
    assert!(!text.contains("Turn left"), "{text}");
    assert!(!text.contains("roundabout, take"), "{text}");
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);

    // Nothing to send leaves no past deadline for the poll loop to spin on.
    let idle = at(5000);
    pump(&mut s, &mut hu, idle);
    assert!(
        s.next_deadline().is_none_or(|d| d > idle),
        "an arrived route left a past deadline: {}",
        s.report()
    );

    // A second destination after arriving needs no new handshake.
    link.publish(RouteState::Guiding(Guidance::live(
        2_000_001,
        6,
        None,
        "Bear right",
        90.0,
        9,
        90.0,
        9,
    )));
    pump(&mut s, &mut hu, at(5100));
    assert_eq!(hu.nav_states, vec![1, 3, 1, 2, 1]);
    assert_eq!(hu.nav_focus_requests, vec![2]);
}

/// Stopping mid-route still withdraws the state and hands the cluster back — the same
/// rule the script follows, driven by what is on the wire rather than by the source.
#[test]
fn stopping_a_live_route_goes_inactive_and_hands_the_cluster_back() {
    let (mut s, mut hu, t0) = start(navigation_hu(Cluster::OnOpen, NavFocus::Projected), |c| {
        c.projection.navigation = true;
        c.projection.navigation_source = NavSource::Route;
        c.projection.limit = Some(Duration::from_millis(50));
    });
    s.guidance().publish(RouteState::Guiding(Guidance::live(
        1,
        8,
        None,
        "Turn right",
        300.0,
        30,
        1000.0,
        100,
    )));
    pump(&mut s, &mut hu, t0 + Duration::from_millis(10));
    assert_eq!(hu.nav_states, vec![1]);
    pump(&mut s, &mut hu, t0 + Duration::from_millis(60));
    assert_eq!(hu.nav_states, vec![1, 2], "{}", s.report());
    assert_eq!(hu.nav_focus_requests, vec![2, 1]);
}

/// A route-driven channel that never got a route has nothing to withdraw: no state was
/// ever claimed, so none is sent at the end, and only the focus goes back.
#[test]
fn a_live_channel_with_no_route_withdraws_nothing_it_never_claimed() {
    let (mut s, mut hu, t0) = start(navigation_hu(Cluster::OnOpen, NavFocus::Projected), |c| {
        c.projection.navigation = true;
        c.projection.navigation_source = NavSource::Route;
        c.projection.limit = Some(Duration::from_millis(50));
    });
    // Rerouting from nowhere is not a state change either.
    s.guidance().publish(RouteState::Rerouting);
    pump(&mut s, &mut hu, t0 + Duration::from_millis(10));
    assert!(hu.nav_states.is_empty());
    pump(&mut s, &mut hu, t0 + Duration::from_millis(60));
    assert!(hu.nav_states.is_empty(), "{}", s.report());
    assert_eq!(hu.nav_focus_requests, vec![2, 1]);
}

/// P7.4: a route the driver ends (End route, Clear route, or the hold after arriving)
/// withdraws the state and hands the cluster back **then**, not at the end of the
/// session, so the vehicle's own navigation can have it between routes. The next route
/// runs the start of the handshake again: it asks, and nothing goes on the cluster until
/// the head unit grants it.
///
/// The simulator answers no request by itself here (`Silent`); every grant and every
/// answer to the hand-back is sent by hand, so the test says exactly which one the source
/// waited for.
#[test]
fn an_ended_route_hands_the_cluster_back_and_the_next_route_asks_again() {
    let (mut s, mut hu, t0) = start(navigation_hu(Cluster::OnOpen, NavFocus::Silent), |c| {
        c.projection.navigation = true;
        c.projection.navigation_source = NavSource::Route;
        c.projection.navigation_interval = Duration::from_millis(500);
        c.projection.limit = Some(Duration::from_millis(5_000));
    });
    let media = s.media();
    let mut source = Source::new(800, 480);
    media.submit(CONFIG, 0, &source.config());
    let (frame, flags) = source.frame(true);
    media.submit(flags, 1_000, &frame);
    let at = |ms: u64| t0 + Duration::from_millis(ms);
    assert_eq!(hu.nav_focus_requests, vec![2]);
    hu.send_nav_focus(Some(2));
    pump(&mut s, &mut hu, at(1));

    let link = s.guidance();
    let route = |id: usize, maneuver: i32| {
        RouteState::Guiding(Guidance::live(
            id, maneuver, None, "Turn", 300.0, 30, 1000.0, 100,
        ))
    };
    link.publish(route(1, 8));
    pump(&mut s, &mut hu, at(10));
    assert_eq!(hu.nav_states, vec![1], "{}", s.report());
    assert_eq!(hu.nav_notifications.len(), 1);

    // Ended: `INACTIVE`, then `NAV_FOCUS_NATIVE`, once, although the link says Ended on
    // every visit until the next route.
    link.publish(RouteState::Ended);
    pump(&mut s, &mut hu, at(100));
    assert_eq!(hu.nav_states, vec![1, 2], "{}", s.report());
    assert_eq!(hu.nav_focus_requests, vec![2, 1], "not handed back");
    pump(&mut s, &mut hu, at(200));
    pump(&mut s, &mut hu, at(900));
    assert_eq!(hu.nav_states, vec![1, 2]);
    assert_eq!(hu.nav_focus_requests, vec![2, 1], "handed back twice");
    let idle = at(900);
    assert!(
        s.next_deadline().is_none_or(|d| d > idle),
        "an ended route left a past deadline: {}",
        s.report()
    );

    // Prodigy echoes the hand-back, and a vehicle may answer it some way: the answer is
    // recorded, and it is not a refusal of anything.
    hu.send_nav_focus(Some(1));
    pump(&mut s, &mut hu, at(950));
    let text = s.report().to_string();
    assert!(text.contains("navigation_focus_denials=0"), "{text}");
    assert!(
        text.contains(
            "navigation_focus_handbacks=1 navigation_focus_handback_answers=1 \
             navigation_focus_handback_type=1 (NAV_FOCUS_NATIVE)"
        ),
        "{text}"
    );
    assert!(text.contains("navigation_focus_released=true"), "{text}");

    // Nothing takes the cluster back but a route: not the cluster announcing itself
    // again, and not idle, which is what the app says for a route with no fix yet.
    hu.send_cluster(true);
    pump(&mut s, &mut hu, at(1_000));
    link.publish(RouteState::Idle);
    pump(&mut s, &mut hu, at(1_100));
    assert_eq!(
        hu.nav_focus_requests,
        vec![2, 1],
        "asked again with no route"
    );
    assert_eq!(hu.nav_states, vec![1, 2]);

    // The next route asks again, and waits for the grant: no `ACTIVE`, no maneuver.
    link.publish(route(2, 7));
    pump(&mut s, &mut hu, at(1_200));
    assert_eq!(hu.nav_focus_requests, vec![2, 1, 2], "{}", s.report());
    pump(&mut s, &mut hu, at(1_800));
    assert_eq!(hu.nav_states, vec![1, 2], "sent before the grant");
    assert_eq!(hu.nav_notifications.len(), 1, "sent before the grant");
    hu.send_nav_focus(Some(2));
    pump(&mut s, &mut hu, at(1_900));
    assert_eq!(hu.nav_states, vec![1, 2, 1], "{}", s.report());
    assert_eq!(hu.nav_notifications.len(), 2);
    assert_eq!(maneuver_of(hu.nav_notifications.last().unwrap()), Some(7));

    // Held again, so the end of the session owes a hand-back again.
    pump(&mut s, &mut hu, at(5_100));
    assert_eq!(hu.nav_states, vec![1, 2, 1, 2], "{}", s.report());
    assert_eq!(hu.nav_focus_requests, vec![2, 1, 2, 1]);
    let text = s.report().to_string();
    assert!(
        text.contains("navigation_focus_requests=2 navigation_focus_responses=2"),
        "{text}"
    );
    assert!(text.contains("navigation_focus_denials=0"), "{text}");
    assert!(text.contains("navigation_focus_released=true"), "{text}");
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);
}

/// An ended route with nothing on the cluster and no focus ever asked for hands nothing
/// back: the head unit is not told to take back something it never gave.
#[test]
fn an_ended_route_hands_back_nothing_that_was_never_asked_for() {
    let (mut s, mut hu, t0) = start(navigation_hu(Cluster::Silent, NavFocus::Projected), |c| {
        c.projection.navigation = true;
        c.projection.navigation_source = NavSource::Route;
    });
    s.guidance().publish(RouteState::Ended);
    pump(&mut s, &mut hu, t0 + Duration::from_millis(10));
    assert!(hu.nav_focus_requests.is_empty(), "{}", s.report());
    assert!(hu.nav_states.is_empty());
    assert!(s
        .report()
        .to_string()
        .contains("navigation_focus_handbacks=0"));
}

/// A fragmenting video keyframe and an audio buffer never overlap on the wire. Receivers
/// in the aasdk family abort a partially assembled message the moment a frame from
/// another channel arrives mid-fragment (`MESSENGER_INTERTWINED_CHANNELS`), and our own
/// inbound rule is the same; a second AV channel makes that possible for the first time.
#[test]
fn a_fragmenting_video_keyframe_is_never_interleaved_with_audio() {
    let (mut s, mut hu, t0, _) = start_audio(
        hu_sim::Config {
            projection: hu_sim::Projection {
                audio_max_unacked: Some(4),
                setup_configs: Some(vec![1]),
                ..Default::default()
            },
            ..hu_sim::Config::default()
        },
        |_| {},
    );
    let (video, audio) = (s.media(), s.audio());
    let source = Source::new(800, 480);
    video.submit(CONFIG, 0, &source.config());
    // A frame larger than one 16 KiB fragment, so the video message really does fragment.
    let mut big = Canvas::new(800, 480, Yuv::from_rgb(0, 0, 0));
    for x in 0..800 {
        for y in 0..480 {
            let v = ((x * 7 + y * 13) % 255) as u8;
            big.fill(x, y, 1, 1, Yuv(v, v ^ 0x5a, v ^ 0xa5));
        }
    }
    let mut encoder = Encoder::new(800, 480);
    let (bytes, key) = encoder.encode(&big, true);
    assert!(
        bytes.len() > aap_wire::OUTBOUND_FRAGMENT,
        "{} bytes does not fragment",
        bytes.len()
    );
    video.submit(
        BufferFlags {
            key_frame: key,
            ..BufferFlags::default()
        },
        0,
        &bytes,
    );
    for i in 0..3 {
        audio.submit(BufferFlags::default(), 42_666 * i, &pcm(2048));
    }
    pump(&mut s, &mut hu, t0);
    // The simulator's assembler fails the moment a frame from another channel lands
    // inside an incomplete message, so an empty error list *is* the assertion.
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);
    assert!(hu.media.len() >= 2 && hu.audio.len() == 3, "{}", s.report());
}

#[test]
fn projection_limit_and_peer_shutdown_end_the_stream() {
    let (mut s, mut hu, t0) = start(hu_sim::Config::default(), |c| {
        c.projection.limit = Some(Duration::from_secs(60))
    });
    let link = s.media();
    let mut source = Source::new(800, 480);
    link.submit(CONFIG, 0, &source.config());
    let (bytes, flags) = source.frame(false);
    link.submit(flags, 0, &bytes);
    pump(&mut s, &mut hu, t0);
    let end = t0 + Duration::from_secs(60);
    // Own pings answered along the way keep the session alive.
    for second in 1..=60 {
        pump(&mut s, &mut hu, t0 + Duration::from_secs(second));
    }
    pump(&mut s, &mut hu, end);
    assert_eq!(
        category(&s),
        Some(Category::ProjectionComplete),
        "{}",
        detail(&s)
    );
    assert_eq!(
        detail(&s),
        "projection limit of 60000 ms reached; ByeByeResponse received"
    );
    assert!(s
        .report()
        .to_string()
        .contains("projecting_ms=60000 video_stop_indication_sent=true"));

    let (mut s, mut hu, t0) = start(
        hu_sim::Config {
            shutdown_after_discovery: None,
            ..hu_sim::Config::default()
        },
        |_| {},
    );
    s.take_app_events();
    hu.send_raw(
        0,
        false,
        true,
        encode_message(
            ids::SHUTDOWN_REQUEST,
            &aap_proto::messages::ShutdownRequest { reason: 1 },
        ),
    );
    pump(&mut s, &mut hu, t0);
    assert_eq!(category(&s), Some(Category::PeerShutdown));
    assert_eq!(
        s.take_app_events(),
        vec![AppEvent::VideoStop {
            reason: "PEER_SHUTDOWN".into()
        }]
    );
}

#[test]
fn each_session_has_fresh_projection_state() {
    let (s1, _, _) = start(hu_sim::Config::default(), |_| {});
    let (s2, _, _) = start(hu_sim::Config::default(), |_| {});
    assert!(!Arc::ptr_eq(&s1.media(), &s2.media()));
    let mut source = Source::new(800, 480);
    drop(s1);
    assert_eq!(
        s2.media().submit(CONFIG, 0, &source.config()),
        Submit::Accepted
    );
    let (bytes, flags) = source.frame(false);
    assert_eq!(s2.media().submit(flags, 0, &bytes), Submit::Accepted);
    assert_eq!(s2.media().counters().accepted_keyframes, 1);
}

#[test]
fn annex_b_parsing_normalizes_and_rejects_malformed_units() {
    let encoder = Encoder::new(1920, 1080);
    let sets = encoder.parameter_sets();
    let parsed = media::parse(&sets).unwrap();
    assert_eq!(parsed.nal_units, 2);
    assert_eq!(
        parsed.sps.unwrap().to_string(),
        "profile=66 constraints=0xc0 level=31 size=1920x1080"
    );
    // Three-byte start codes and trailing zeros normalize to four-byte start codes.
    let short: Vec<u8> = [&[0u8, 0, 1][..], &sets[4..], &[0, 0]].concat();
    assert_eq!(media::parse(&short).unwrap().annex_b[..4], [0, 0, 0, 1]);
    // Length-prefixed input converts to the same Annex B bytes.
    let second = sets[4..]
        .windows(4)
        .position(|w| w == [0, 0, 0, 1])
        .unwrap()
        + 4;
    let mut prefixed = Vec::new();
    for unit in [&sets[4..second], &sets[second + 4..]] {
        prefixed.extend((unit.len() as u32).to_be_bytes());
        prefixed.extend_from_slice(unit);
    }
    let converted = media::parse(&prefixed).unwrap();
    assert_eq!(converted.format, media::NalFormat::LengthPrefixed);
    assert_eq!(converted.annex_b, parsed.annex_b);

    for (bytes, reason) in [
        (vec![], "empty buffer"),
        (vec![0, 0, 1], "empty NAL unit"),
        (vec![0, 0, 1, 0x80], "NAL unit with forbidden_zero_bit set"),
        (vec![0, 0, 1, 0x14], "unsupported NAL unit type 20"),
        (
            vec![0, 0, 1, 0x65, 0x88, 0, 0, 1, 0x41, 0x9a],
            "access unit mixes IDR and non-IDR slices",
        ),
        (
            vec![0, 0, 0, 9, 0x65],
            "neither Annex B nor exactly length-prefixed NAL units",
        ),
        (
            vec![0, 0, 1, 0x67, 0x42],
            "truncated sequence parameter set",
        ),
    ] {
        assert_eq!(media::parse(&bytes).unwrap_err(), reason, "{bytes:02x?}");
    }
}

/// End to end over the blocking-thread transport the Android accessory uses: an encoder
/// thread submits frames and wakes the session, and Stop ends the session gracefully.
#[cfg(any(target_os = "linux", target_os = "android"))]
#[test]
fn projection_over_the_accessory_transport_with_an_encoder_thread() {
    use aap_session::accessory::{run, Options, Stop};
    use std::{os::unix::net::UnixStream, sync::mpsc, thread};

    let (local, peer) = UnixStream::pair().unwrap();
    peer.set_read_timeout(Some(Duration::from_millis(20)))
        .unwrap();
    let hu = HeadUnit::new(small_frame_hu());
    let fingerprint = hu.fingerprint;
    let receiver = thread::spawn(move || hu_sim::serve(peer, hu, Duration::from_secs(20)));

    let mut cfg = Config {
        stop_after: StopAfter::Projection,
        tls: Some(ServerConfig {
            profile: TlsProfile::Aead,
            identity: identity(),
            peer: PeerPolicy::Pinned(vec![fingerprint]),
        }),
        session_uuid: UUID.into(),
        ..Config::default()
    };
    let link = Arc::new(media::MediaLink::default());
    cfg.media = Some(link.clone());
    let stop = Stop::new();
    let (events_tx, events) = mpsc::channel();
    let (session_stop, worker_stop) = (stop.clone(), stop.clone());
    // The transport owner wakes the session thread when the encoder submits.
    link.set_waker(Arc::new(move || session_stop.wake()));

    let worker = thread::spawn(move || {
        let mut session = Session::new(cfg, 3, Instant::now());
        run(
            OwnedFd::from(local),
            &mut session,
            &worker_stop,
            &Options::default(),
            |s| {
                for event in s.take_app_events() {
                    let _ = events_tx.send(event);
                }
            },
        );
        session
    });

    // Wait for the start event, then stream from this thread as the encoder does.
    let start = loop {
        match events.recv_timeout(Duration::from_secs(10)).unwrap() {
            AppEvent::VideoStart(start) => break start,
            _ => continue,
        }
    };
    assert_eq!((start.video.width, start.video.height), (800, 480));
    let mut source = Source::new(start.video.width, start.video.height);
    assert_eq!(link.submit(CONFIG, 0, &source.config()), Submit::Accepted);
    for i in 0..10i64 {
        let (bytes, flags) = source.frame(false);
        assert_eq!(link.submit(flags, i * 33_333, &bytes), Submit::Accepted);
        thread::sleep(Duration::from_millis(5));
    }
    stop.request_shutdown();
    let session = worker.join().unwrap();
    let hu = receiver.join().unwrap();

    let report = session.report();
    assert_eq!(
        report.outcome.as_ref().map(|o| o.category),
        Some(Category::Cancelled),
        "{report}"
    );
    assert_eq!(report.last_completed, Some(Stage::Projecting));
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);
    // Every media unit reached the head unit in order, configuration first.
    assert_eq!(hu.media[0].id, ids::av::MEDIA);
    assert!(hu.media.len() >= 5, "{} units", hu.media.len());
    assert!(hu.media[1..]
        .windows(2)
        .all(|w| w[0].timestamp_us < w[1].timestamp_us));
    assert!(hu.ids().contains(&ids::av::STOP_INDICATION));
    assert!(hu.ids().contains(&ids::SHUTDOWN_REQUEST));
}
