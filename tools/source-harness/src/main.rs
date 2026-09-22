//! Desktop harness for the shared source engine.
//!
//! * No arguments: offline metadata inspector for a plaintext capture on stdin.
//! * `listen`/`connect`: a session over the lab TCP adapter, version-only by default or
//!   through TLS, authentication, discovery, and projection with `--stop-after`. TCP is a
//!   lab transport to an instrumented receiver, not wireless Android Auto. `--io threads`
//!   runs the blocking-thread transport used for the Android accessory descriptor.
//!   `--stop-after projection` renders the lab test scene (see `scene`) with the
//!   test-pattern encoder; the app uses MediaCodec instead.
use aap_session::{
    accessory,
    discovery::VideoPreference,
    input::TouchSpace,
    media::MediaLink,
    navigation::Units,
    now_playing::NowPlayingLink,
    projection::{SensorSet, SetupField},
    transport::{run_with_progress, Cancellation},
    version::Profile,
    Category, Config, KeepaliveProfile, Session, Stage, StopAfter,
};
use aap_tls::{identity::parse_fingerprint, Identity, PeerPolicy, ServerConfig, TlsProfile};
use aap_wire::{Assembler, Parser, READ_BUFFER};
use std::{
    error::Error,
    io::{self, Read, Write},
    net::{TcpListener, TcpStream},
    os::fd::OwnedFd,
    path::PathBuf,
    process::ExitCode,
    sync::{mpsc, Arc},
    thread,
    time::{Duration, Instant},
};

mod scene;
mod tone;

const USAGE: &str = "usage:
  source-harness < capture.bin                  inspect plaintext frames (metadata only)
  source-harness listen ADDR [options]          accept one lab receiver connection
  source-harness connect ADDR [options]         connect to a lab receiver listener
options:
  --profile legacy-1.5|legacy-1.5-any-minor|match-1.6|match-1.7
                                                version profile (default legacy-1.5)
  --video-preference largest|smallest|WxH       advertised video configuration to take
                                                (default largest; WxH pins a frame size)
  --io poll|threads                             poll loop (default) or the blocking
                                                I/O threads used for the accessory
  --stop-after version|auth|discovery|projection
                                                last stage (default version)
  --identity-dir DIR                            source identity, created if absent
                                                (required beyond version)
  --tls-profile tls12-aead|tls12-cbc-compat     default tls12-aead
  --peer-pin SHA256HEX                          accept this receiver certificate
                                                (repeatable; C1 lab pin)
  --trust-first-use                             accept any receiver certificate for
                                                this session only
  --hold-ms N                                   discovery hold (default 30000, max 30000)
  --capture-discovery                           keep the raw discovery response bytes in
                                                the report (discovery_response_hex; the
                                                bytes are sensitive)
  --capture-unhandled                           keep a bounded body for each message no
                                                profile interprets (unhandled_N body=)
  --keepalive mirror|plaintext|encrypted        ping encryption (default mirror: follow
                                                the receiver's latest ping)
projection options (--stop-after projection):
  --projection-ms N                             stop after this long (default: until the
                                                receiver ends the session)
  --sensors none|restriction|all|LIST           advertised sensor types to subscribe to
                                                (default none; restriction is driving
                                                status and night data; LIST is
                                                SensorType names or numbers)
  --sensor-location                             put head-unit latitude and longitude in
                                                the report; they identify the vehicle, so
                                                without this the position sensors are
                                                still counted but their coordinates are
                                                redacted (spec §10)
  --media-info                                  open the advertised now-playing channel
                                                and publish the lab player on it; head-unit
                                                transport buttons drive that player
  --media-interval-ms N                         shortest gap between now-playing messages
                                                (default 1000)
  --audio                                       open the advertised PCM MEDIA audio channel,
                                                take audio focus, and stream the lab test
                                                tone on it (440 Hz left, 880 Hz right)
  --audio-focus-wait-ms N                       how long to wait for the audio focus
                                                response before skipping audio (default 10000)
  --navigation                                  open the advertised navigation channel and
                                                drive the head unit's own instrument
                                                cluster with a scripted maneuver sequence
  --navigation-units imperial|metric            how cluster distances are written
                                                (default imperial)
  --navigation-force                            send the sequence even when the head unit
                                                announces no cluster and grants no focus
  --navigation-interval-ms N                    shortest gap between cluster updates
                                                (default 1000; raised to the head unit's
                                                advertised minimum_interval_ms)
  --navigation-focus-wait-ms N                  how long to wait for the cluster to
                                                announce itself, and then for a navigation
                                                focus response (default 10000)
  --open-all-channels                           also open advertised channels this source
                                                does not use, as Android Auto does
  --no-focus-request                            wait for the head unit's unsolicited video
                                                focus instead of asking, as Android Auto does
  --setup-field codec-type|config-index         field 1 of the AV setup request
                                                (default codec-type; see ProjectionConfig)
  --touch-space content|frame|advertised        space head-unit touches arrive in
                                                (default content: what Ford SYNC 4.0 sends)
  --focus-wait-ms N --ack-timeout-ms N          projection deadlines
  --fps N                                       scene frame rate (default: negotiated)
  --keyframe-ms N                               scene keyframe interval (default 1000)
  --version-wait-ms N --client-hello-wait-ms N --tls-wait-ms N --auth-wait-ms N
  --discovery-wait-ms N                         stage waits in milliseconds
exit status: 0 only when the configured stop was reached cleanly (version: the
response was written and the peer began TLS; auth: AUTH_COMPLETE accepted; discovery:
the hold completed with usable capabilities; projection: video and input ran to the
configured limit) and (threads) both I/O threads joined";

#[derive(Clone, Copy, PartialEq, Eq)]
enum Io {
    Poll,
    Threads,
}

fn inspect() -> Result<(), Box<dyn Error>> {
    let mut parser = Parser::new();
    let mut assembler = Assembler::default();
    let mut input = io::stdin().lock();
    let mut buffer = [0; READ_BUFFER];
    loop {
        let n = match input.read(&mut buffer) {
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            result => result?,
        };
        if n == 0 {
            break;
        }
        let mut offset = 0;
        while offset < n {
            let (used, frame) = parser.consume(&buffer[offset..n])?;
            offset += used;
            if let Some(frame) = frame {
                if frame.header.encrypted() {
                    return Err(
                        "encrypted frame: the offline inspector has no TLS session and never \
                         treats ciphertext as plaintext"
                            .into(),
                    );
                }
                if let Some(message) =
                    assembler.push_plaintext(frame.header, &frame.payload, Instant::now())?
                {
                    println!(
                        "channel={} flags=0x{:02x} id=0x{:04x} plaintext_bytes={}",
                        message.channel,
                        message.flags,
                        message.id()?,
                        message.bytes.len()
                    );
                }
            }
        }
    }
    parser.finish()?;
    assembler.finish()?;
    Ok(())
}

struct Options {
    config: Config,
    io: Io,
    identity_dir: Option<PathBuf>,
    tls_profile: TlsProfile,
    pins: Vec<[u8; 32]>,
    first_use: bool,
    fps: Option<u32>,
    keyframe: Duration,
}

fn parse_options(args: &[String]) -> Result<Options, Box<dyn Error>> {
    let mut o = Options {
        config: Config::default(),
        io: Io::Poll,
        identity_dir: None,
        tls_profile: TlsProfile::Aead,
        pins: Vec::new(),
        first_use: false,
        fps: None,
        keyframe: Duration::from_secs(1),
    };
    let millis = |value: &str| -> Result<Duration, Box<dyn Error>> {
        Ok(Duration::from_millis(value.parse()?))
    };
    let mut args = args.iter();
    while let Some(flag) = args.next() {
        if flag == "--trust-first-use" {
            o.first_use = true;
            continue;
        }
        if flag == "--capture-discovery" {
            o.config.capture_discovery_response = true;
            continue;
        }
        if flag == "--capture-unhandled" {
            o.config.capture_unhandled = true;
            continue;
        }
        if flag == "--sensor-location" {
            o.config.projection.sensor_location = true;
            continue;
        }
        if flag == "--media-info" {
            o.config.projection.media_info = true;
            continue;
        }
        if flag == "--audio" {
            o.config.projection.audio = true;
            continue;
        }
        if flag == "--navigation" {
            o.config.projection.navigation = true;
            continue;
        }
        if flag == "--navigation-force" {
            o.config.projection.navigation_force = true;
            continue;
        }
        if flag == "--open-all-channels" {
            o.config.projection.open_all_channels = true;
            continue;
        }
        if flag == "--no-focus-request" {
            o.config.projection.request_focus = false;
            continue;
        }
        let value = args.next().ok_or_else(|| format!("{flag} needs a value"))?;
        let c = &mut o.config;
        match flag.as_str() {
            "--profile" => {
                c.profile =
                    Profile::from_name(value).ok_or_else(|| format!("unknown profile {value}"))?
            }
            "--video-preference" => {
                c.video_preference = VideoPreference::from_name(value)
                    .ok_or_else(|| format!("unknown video preference {value}"))?
            }
            "--stop-after" => {
                c.stop_after = StopAfter::from_name(value)
                    .ok_or_else(|| format!("unknown stop-after {value}"))?
            }
            "--keepalive" => {
                c.keepalive = KeepaliveProfile::from_name(value)
                    .ok_or_else(|| format!("unknown keepalive {value}"))?
            }
            "--identity-dir" => o.identity_dir = Some(PathBuf::from(value)),
            "--tls-profile" => {
                o.tls_profile = TlsProfile::from_name(value)
                    .ok_or_else(|| format!("unknown tls profile {value}"))?
            }
            "--peer-pin" => o
                .pins
                .push(parse_fingerprint(value).ok_or_else(|| format!("invalid pin {value}"))?),
            "--hold-ms" => {
                c.hold = millis(value)?;
                if c.hold > Duration::from_secs(30) {
                    return Err("--hold-ms is at most 30000".into());
                }
            }
            "--version-wait-ms" => c.version_wait = millis(value)?,
            "--client-hello-wait-ms" => c.client_hello_wait = millis(value)?,
            "--tls-wait-ms" => c.tls_wait = millis(value)?,
            "--auth-wait-ms" => c.auth_wait = millis(value)?,
            "--discovery-wait-ms" => c.discovery_wait = millis(value)?,
            "--projection-ms" => c.projection.limit = Some(millis(value)?),
            "--sensors" => {
                c.projection.sensors = SensorSet::from_name(value)
                    .ok_or_else(|| format!("unknown sensor set {value}"))?
            }
            "--media-interval-ms" => c.projection.media_interval = millis(value)?,
            "--audio-focus-wait-ms" => c.projection.audio_focus_wait = millis(value)?,
            "--navigation-interval-ms" => c.projection.navigation_interval = millis(value)?,
            "--navigation-focus-wait-ms" => c.projection.navigation_focus_wait = millis(value)?,
            "--navigation-units" => {
                c.projection.navigation_units = Units::from_name(value)
                    .ok_or_else(|| format!("unknown navigation units {value}"))?
            }
            "--focus-wait-ms" => c.projection.focus_wait = millis(value)?,
            "--ack-timeout-ms" => c.projection.ack_timeout = millis(value)?,
            "--setup-field" => {
                c.projection.setup_field = SetupField::from_name(value)
                    .ok_or_else(|| format!("unknown setup field {value}"))?
            }
            "--touch-space" => {
                c.projection.touch_space = match value.as_str() {
                    "frame" => TouchSpace::Frame,
                    "content" => TouchSpace::Content,
                    "advertised" => TouchSpace::Advertised,
                    other => return Err(format!("unknown touch space {other}").into()),
                }
            }
            "--keyframe-ms" => o.keyframe = millis(value)?,
            "--fps" => o.fps = Some(value.parse()?),
            "--io" => {
                o.io = match value.as_str() {
                    "poll" => Io::Poll,
                    "threads" => Io::Threads,
                    _ => return Err(format!("unknown io mode {value}").into()),
                }
            }
            _ => return Err(format!("unknown option {flag}").into()),
        }
    }
    if o.config.stop_after != StopAfter::Projection
        && (o.config.projection.limit.is_some()
            || o.config.projection.sensors.enabled()
            || o.config.projection.sensor_location
            || o.config.projection.media_info
            || o.config.projection.audio
            || o.config.projection.navigation
            || o.fps.is_some())
    {
        return Err("projection options need --stop-after projection".into());
    }
    let tls = o.config.stop_after != StopAfter::Version;
    if tls && (o.identity_dir.is_none() || o.pins.is_empty() != o.first_use) {
        return Err(
            "--stop-after beyond version needs --identity-dir and exactly one of --peer-pin or \
             --trust-first-use"
                .into(),
        );
    }
    if !tls && (o.identity_dir.is_some() || !o.pins.is_empty() || o.first_use) {
        return Err("TLS options need --stop-after auth or discovery".into());
    }
    Ok(o)
}

fn lab_session(mode: &str, addr: &str, mut o: Options) -> Result<bool, Box<dyn Error>> {
    println!("stop_after={}", o.config.stop_after.name());
    o.config.session_uuid = aap_tls::identity::random_uuid()?;
    println!("session_uuid={}", o.config.session_uuid);
    // The identity is ready before any receiver connects, never while one waits.
    if let Some(dir) = &o.identity_dir {
        let (identity, created) = Identity::load_or_create(dir)?;
        println!("{}identity_created={created}", identity.summary());
        o.config.tls = Some(ServerConfig {
            profile: o.tls_profile,
            identity: Arc::new(identity),
            peer: if o.first_use {
                PeerPolicy::FirstUse
            } else {
                PeerPolicy::Pinned(o.pins.clone())
            },
        });
    }
    let mut stream = match mode {
        "listen" => {
            let listener = TcpListener::bind(addr)?;
            println!("listening={}", listener.local_addr()?);
            io::stdout().flush()?;
            listener.accept()?.0
        }
        _ => TcpStream::connect(addr)?,
    };
    stream.set_nodelay(true)?;
    println!("transport=tcp-lab (not wireless Android Auto)");
    println!("peer={}", stream.peer_addr()?);
    let mut clean_io = true;
    let stop_after = o.config.stop_after;
    let projecting = stop_after == StopAfter::Projection;
    // The scene runs on its own thread, as the app's encoder does, and stands in for the
    // phone's media stack on the now-playing link.
    let link = Arc::new(MediaLink::default());
    o.config.media = Some(link.clone());
    let audio = Arc::new(MediaLink::audio(aap_session::media::DEFAULT_AUDIO_LIMIT));
    o.config.audio = Some(audio.clone());
    let now_playing = Arc::new(NowPlayingLink::default());
    o.config.now_playing = Some(now_playing.clone());
    let (events_tx, events_rx) = mpsc::channel();
    let token: u32 = u32::from_str_radix(&o.config.session_uuid[..6], 16).unwrap_or(0) % 1_000_000;
    let fps = o.fps;
    let keyframe = o.keyframe;
    let scene_thread = projecting.then(|| {
        let link = link.clone();
        let audio = audio.clone();
        let now_playing = now_playing.clone();
        thread::spawn(move || {
            // The negotiated frame rate is used unless overridden.
            let fps = fps.unwrap_or(30);
            scene::run(link, audio, now_playing, events_rx, token, fps, keyframe)
        })
    });
    if projecting {
        println!("projection_token={token:06}");
    }
    let deliver = move |session: &mut Session| {
        for event in session.take_app_events() {
            let _ = events_tx.send(event);
        }
    };
    // The session starts when the byte stream exists, matching descriptor open on USB.
    let session = match o.io {
        Io::Poll => {
            let cancellation = Cancellation::new()?;
            let handle = cancellation.handle();
            link.set_waker(Arc::new(move || handle.wake()));
            let handle = cancellation.handle();
            audio.set_waker(Arc::new(move || handle.wake()));
            let handle = cancellation.handle();
            now_playing.set_waker(Arc::new(move || handle.wake()));
            let mut session = Session::new(o.config, 1, Instant::now());
            run_with_progress(&mut stream, &mut session, &cancellation, deliver);
            session
        }
        Io::Threads => {
            let stop = accessory::Stop::new();
            let waker = stop.clone();
            link.set_waker(Arc::new(move || waker.wake()));
            let waker = stop.clone();
            audio.set_waker(Arc::new(move || waker.wake()));
            let waker = stop.clone();
            now_playing.set_waker(Arc::new(move || waker.wake()));
            let mut session = Session::new(o.config, 1, Instant::now());
            let teardown = accessory::run(
                OwnedFd::from(stream),
                &mut session,
                &stop,
                &accessory::Options::default(),
                deliver,
            );
            print!("{teardown}");
            clean_io = teardown.descriptor_closed();
            session
        }
    };
    if let Some(thread) = scene_thread {
        // The session has ended, so the scene thread sees the closed channel.
        match thread.join() {
            Ok(stats) => print!("{stats}"),
            Err(_) => println!("scene_thread=panicked"),
        }
    }
    let report = session.report();
    print!("{report}");
    let category = report.outcome.as_ref().map(|o| o.category);
    let reached = match stop_after {
        StopAfter::Version => {
            report.last_completed == Some(Stage::Version) && category == Some(Category::StageLimit)
        }
        StopAfter::Auth => {
            report.last_completed == Some(Stage::Auth) && category == Some(Category::StageLimit)
        }
        StopAfter::Discovery => {
            report.last_completed == Some(Stage::Discovery)
                && category == Some(Category::HoldComplete)
                && report.capabilities.as_ref().is_some_and(|c| c.usable())
        }
        StopAfter::Projection => {
            report.last_completed == Some(Stage::Projecting)
                && matches!(
                    category,
                    Some(Category::ProjectionComplete | Category::PeerShutdown)
                )
        }
    };
    Ok(clean_io && reached)
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.as_slice() {
        [] => inspect().map(|()| true),
        [mode, addr, rest @ ..] if mode == "listen" || mode == "connect" => {
            parse_options(rest).and_then(|options| lab_session(mode, addr, options))
        }
        _ => Err(USAGE.into()),
    };
    match result {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::from(2),
        Err(e) => {
            eprintln!("{e}");
            ExitCode::FAILURE
        }
    }
}
