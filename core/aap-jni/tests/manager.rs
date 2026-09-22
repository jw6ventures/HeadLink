//! JVM-independent tests of the owned-descriptor start/stop/report contract, using
//! blocking socket pairs in place of the accessory descriptor. The JNI adapter itself
//! is exercised on a JVM by tools/jni-host-test.
use aap_jni::manager::{
    ensure_identity, parse_config, take_descriptor, EventKind, Events, Manager, START_BUSY,
    START_IDENTITY_UNAVAILABLE, START_INVALID_CONFIG, START_STALE_IO, SUBMIT_UNKNOWN_HANDLE,
};
use aap_session::{
    accessory::Options, media::BufferFlags, projection::SensorSet, KeepaliveProfile,
};
use std::{
    io::{Read, Write},
    os::{
        fd::{IntoRawFd, OwnedFd},
        unix::net::UnixStream,
    },
    sync::{mpsc, Arc},
    thread,
    time::{Duration, Instant},
};

const UUID: &str = "0f5a4c1e-8b2d-4e6f-9a3c-5d7e1b2c3d4e";

fn config(generation: u64) -> Vec<u8> {
    format!("generation={generation}\nsession_uuid={UUID}\nprofile=legacy-1.5\n").into_bytes()
}

fn hex(s: &str) -> Vec<u8> {
    s.split_whitespace()
        .map(|b| u8::from_str_radix(b, 16).unwrap())
        .collect()
}

type Event = (u64, EventKind, String, thread::ThreadId);

struct Channel {
    tx: mpsc::Sender<Event>,
    deliver: bool,
}
impl Events for Channel {
    fn event(&mut self, generation: u64, kind: EventKind, report: &[u8]) -> bool {
        let text = String::from_utf8(report.to_vec()).unwrap();
        let _ = self
            .tx
            .send((generation, kind, text, thread::current().id()));
        self.deliver
    }
}

fn recorder() -> (Box<Channel>, mpsc::Receiver<Event>) {
    let (tx, rx) = mpsc::channel();
    (Box::new(Channel { tx, deliver: true }), rx)
}

fn pair() -> (OwnedFd, UnixStream) {
    let (local, peer) = UnixStream::pair().unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    (local.into(), peer)
}

fn finished(rx: &mpsc::Receiver<Event>) -> Event {
    loop {
        let event = rx.recv_timeout(Duration::from_secs(5)).unwrap();
        if event.1 == EventKind::Finished {
            return event;
        }
    }
}

fn has_line(text: &str, line: &str) -> bool {
    text.lines().any(|l| l == line)
}

fn assert_eof(peer: &mut UnixStream) {
    let mut rest = Vec::new();
    peer.read_to_end(&mut rest).unwrap();
}

#[test]
fn config_parsing_is_strict() {
    let parsed = parse_config(
        format!(
            "generation=9\nsession_uuid={UUID}\nprofile=legacy-1.5\nversion_wait_ms=250\n\
             client_hello_wait_ms=60000"
        )
        .as_bytes(),
    )
    .unwrap();
    assert_eq!(parsed.generation, 9);
    assert_eq!(parsed.session.version_wait, Duration::from_millis(250));
    assert_eq!(parsed.session.client_hello_wait, Duration::from_secs(60));
    for bad in [
        String::new(),
        "generation=1\nprofile=legacy-1.5\n".to_owned(),
        format!("generation=0\nsession_uuid={UUID}\nprofile=legacy-1.5\n"),
        format!("generation=9223372036854775808\nsession_uuid={UUID}\nprofile=legacy-1.5\n"),
        format!(
            "generation=1\nsession_uuid={}\nprofile=legacy-1.5\n",
            UUID.to_uppercase()
        ),
        format!("generation=1\nsession_uuid={UUID}\nprofile=modern-1.7\n"),
        format!("generation=1\nsession_uuid={UUID}\nprofile=legacy-1.5\nprofile=legacy-1.5\n"),
        format!("generation=1\nsession_uuid={UUID}\nprofile=legacy-1.5\nextra=1\n"),
        format!("generation=1\nsession_uuid={UUID}\nprofile=legacy-1.5\nversion_wait_ms=0\n"),
        format!("generation=1\nsession_uuid={UUID}\nprofile=legacy-1.5\n\n"),
        format!("generation=1\r\nsession_uuid={UUID}\nprofile=legacy-1.5\n"),
        format!("generation=1\nsession_uuid={UUID}\nprofile=legacy-1.5\nnote=caf\u{e9}\n"),
    ] {
        assert!(parse_config(bad.as_bytes()).is_err(), "{bad:?}");
    }
    assert!(parse_config(&vec![b'a'; 4097]).is_err());
}

fn tls_config(generation: u64, dir: &str, extra: &str) -> Vec<u8> {
    format!(
        "generation={generation}\nsession_uuid={UUID}\nprofile=legacy-1.5\nstop_after=discovery\n\
         identity_dir={dir}\ntls_profile=tls12-aead\n{extra}"
    )
    .into_bytes()
}

#[test]
fn tls_config_keys_are_strict_and_consistent() {
    let pin = "ab".repeat(32);
    let parsed = parse_config(&tls_config(
        1,
        "/data/x",
        &format!("peer_policy=pinned\npeer_pins={pin},{pin}\nhold_ms=0\ntls_wait_ms=900\n"),
    ))
    .unwrap();
    assert_eq!(parsed.session.stop_after.name(), "discovery");
    assert_eq!(parsed.session.hold, Duration::ZERO);
    assert_eq!(parsed.session.tls_wait, Duration::from_millis(900));
    assert_eq!(parsed.session.session_uuid, UUID);
    assert_eq!(
        parsed.peer,
        aap_tls::PeerPolicy::Pinned(vec![[0xab; 32], [0xab; 32]])
    );
    assert!(parsed.session.tls.is_none(), "loaded by start, not parsing");
    let first_use = parse_config(&tls_config(1, "/d", "peer_policy=first-use\n")).unwrap();
    assert_eq!(first_use.peer, aap_tls::PeerPolicy::FirstUse);
    assert!(parse_config(&tls_config(1, "/d", "peer_policy=pinned\npeer_pins=\n")).is_ok());

    // Opt-in raw discovery capture: off by default, `true`/`false` only.
    assert!(!parsed.session.capture_discovery_response);
    let capturing = parse_config(&tls_config(
        1,
        "/d",
        "peer_policy=first-use\ncapture_discovery=true\n",
    ))
    .unwrap();
    assert!(capturing.session.capture_discovery_response);
    let off = parse_config(&tls_config(
        1,
        "/d",
        "peer_policy=first-use\ncapture_discovery=false\n",
    ))
    .unwrap();
    assert!(!off.session.capture_discovery_response);

    // Keepalive profile: mirror by default; each profile by name.
    assert_eq!(parsed.session.keepalive, KeepaliveProfile::Mirror);
    for profile in KeepaliveProfile::ALL {
        let text = format!("peer_policy=first-use\nkeepalive={}\n", profile.name());
        let keepalive = parse_config(&tls_config(1, "/d", &text)).unwrap();
        assert_eq!(keepalive.session.keepalive, profile);
    }

    let version_only = format!("generation=1\nsession_uuid={UUID}\nprofile=legacy-1.5\n");
    for bad in [
        tls_config(1, "/d", ""),
        tls_config(1, "relative", "peer_policy=first-use\n"),
        tls_config(1, "/d/../etc", "peer_policy=first-use\n"),
        tls_config(1, "/d", "peer_policy=trust-all\n"),
        tls_config(
            1,
            "/d",
            &format!("peer_policy=first-use\npeer_pins={pin}\n"),
        ),
        tls_config(
            1,
            "/d",
            &format!("peer_policy=pinned\npeer_pins={}\n", pin.to_uppercase()),
        ),
        tls_config(1, "/d", "peer_policy=pinned\npeer_pins=abcd\n"),
        tls_config(
            1,
            "/d",
            &format!(
                "peer_policy=pinned\npeer_pins={}\n",
                [pin.as_str(); 9].join(",")
            ),
        ),
        tls_config(1, "/d", "peer_policy=first-use\nhold_ms=30001\n"),
        tls_config(1, "/d", "peer_policy=first-use\ncapture_discovery=maybe\n"),
        tls_config(1, "/d", "peer_policy=first-use\nkeepalive=true\n"),
        tls_config(1, "/d", "peer_policy=first-use\ntls_profile=tls12-aead\n"),
        format!("{version_only}stop_after=everything\n").into_bytes(),
        format!("{version_only}identity_dir=/d\n").into_bytes(),
        format!("{version_only}stop_after=auth\npeer_policy=first-use\n").into_bytes(),
    ] {
        assert!(
            parse_config(&bad).is_err(),
            "{:?}",
            String::from_utf8_lossy(&bad)
        );
    }
}

fn temp_dir(name: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("aap-jni-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    dir
}

#[test]
fn ensure_identity_creates_loads_replaces_and_self_tests() {
    let dir = temp_dir("identity");
    let created = ensure_identity(&dir, false);
    assert!(has_line(&created, "identity_created=true"), "{created}");
    assert!(
        created
            .lines()
            .any(|l| l.starts_with("tls_self_test=pass version=TLSv1.2 cipher=")),
        "{created}"
    );
    let fingerprint = |text: &str| {
        text.lines()
            .find_map(|l| l.strip_prefix("source_identity_fingerprint_sha256="))
            .unwrap()
            .to_owned()
    };
    let loaded = ensure_identity(&dir, false);
    assert!(has_line(&loaded, "identity_created=false"), "{loaded}");
    assert_eq!(fingerprint(&created), fingerprint(&loaded));
    let replaced = ensure_identity(&dir, true);
    assert_ne!(fingerprint(&created), fingerprint(&replaced));
    assert!(!replaced.contains("PRIVATE"));
    std::fs::write(dir.join(aap_tls::identity::FILE_NAME), b"broken").unwrap();
    let broken = ensure_identity(&dir, false);
    assert!(broken.starts_with("identity_error="), "{broken}");
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn missing_identity_rejects_start_and_closes_the_descriptor() {
    let manager = Manager::default();
    let (fd, mut peer) = pair();
    let (events, _rx) = recorder();
    let dir = temp_dir("missing");
    let config = tls_config(1, dir.to_str().unwrap(), "peer_policy=first-use\n");
    assert_eq!(
        manager.start(fd, &config, events),
        START_IDENTITY_UNAVAILABLE
    );
    assert!(!dir.exists(), "start never generates an identity");
    assert_eof(&mut peer);
}

#[test]
fn discovery_session_over_a_socket_pair_with_the_test_head_unit() {
    let dir = temp_dir("session");
    assert!(ensure_identity(&dir, false).contains("tls_self_test=pass"));
    let (local, remote) = UnixStream::pair().unwrap();
    remote
        .set_read_timeout(Some(Duration::from_millis(20)))
        .unwrap();
    let hu = hu_sim::HeadUnit::new(hu_sim::Config::default());
    let pin = aap_tls::identity::hex(&hu.fingerprint);
    let head_unit = thread::spawn(move || hu_sim::serve(remote, hu, Duration::from_secs(20)));

    let manager = Manager::default();
    let (events, rx) = recorder();
    let config = tls_config(
        4,
        dir.to_str().unwrap(),
        &format!("peer_policy=pinned\npeer_pins={pin}\nhold_ms=300\n"),
    );
    assert_eq!(manager.start(local.into(), &config, events), 4);
    let (_, _, text, _) = finished(&rx);
    for line in [
        "final_category=HOLD_COMPLETE",
        "last_completed_stage=discovery",
        "discovery_capabilities=usable",
        "tls_version=TLSv1.2",
        "auth_status=0",
        "io_descriptor=closed",
        "callback_failures=0",
    ] {
        assert!(has_line(&text, line), "missing {line}:\n{text}");
    }
    assert!(text.contains("tls_peer_decision=pin-match"), "{text}");
    manager.stop(4);
    let hu = head_unit.join().unwrap();
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);
    assert!(hu.ids().contains(&aap_proto::ids::SHUTDOWN_REQUEST));
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn version_exchange_events_report_and_release() {
    let manager = Manager::default();
    let (fd, mut peer) = pair();
    let (events, rx) = recorder();
    let handle = manager.start(fd, &config(3), events);
    assert_eq!(handle, 3);
    let (generation, kind, text, worker) = rx.recv_timeout(Duration::from_secs(5)).unwrap();
    assert_eq!((generation, kind), (3, EventKind::Progress));
    assert_ne!(worker, thread::current().id());
    assert!(has_line(&text, &format!("session_uuid={UUID}")), "{text}");
    assert!(has_line(&text, "io=running"), "{text}");

    peer.write_all(&hex(include_str!(
        "../../../protocol/fixtures/version-request.hex"
    )))
    .unwrap();
    let mut response = [0u8; 12];
    peer.read_exact(&mut response).unwrap();
    assert_eq!(
        response.to_vec(),
        hex(include_str!(
            "../../../protocol/fixtures/version-response.hex"
        ))
    );
    peer.write_all(&hex("00 03 00 07 00 03 16 03 01 00 04"))
        .unwrap();
    let (generation, _, text, finished_on) = finished(&rx);
    assert_eq!(generation, 3);
    assert_eq!(finished_on, worker);
    for line in [
        "generation=3",
        "last_completed_stage=version",
        "final_category=STAGE_LIMIT",
        "callback_failures=0",
        "io=blocking-threads",
        "io_descriptor=closed",
    ] {
        assert!(has_line(&text, line), "missing {line}:\n{text}");
    }
    let progress: Vec<Event> = rx.try_iter().collect();
    assert!(progress.is_empty(), "events after Finished: {progress:?}");
    assert_eq!(manager.report(3).as_deref(), Some(text.as_bytes()));
    assert_eof(&mut peer);
    manager.stop(3);
    assert!(manager.report(3).is_none());
    manager.stop(3);
}

#[test]
fn stop_cancels_a_blocked_session_and_finishes_before_returning() {
    let manager = Manager::default();
    let (fd, mut peer) = pair();
    let (events, rx) = recorder();
    assert_eq!(manager.start(fd, &config(1), events), 1);
    thread::sleep(Duration::from_millis(100));
    let requested = Instant::now();
    manager.stop(1);
    assert!(requested.elapsed() < Duration::from_secs(1));
    // Finished was delivered before stop returned.
    let (_, _, text, _) = rx.try_iter().find(|e| e.1 == EventKind::Finished).unwrap();
    assert!(has_line(&text, "final_category=CANCELLED"), "{text}");
    assert!(has_line(&text, "io_reader=joined"), "{text}");
    assert!(manager.report(1).is_none());
    assert_eof(&mut peer);
}

#[test]
fn rejected_starts_close_the_descriptor_and_keep_state() {
    let manager = Manager::default();
    let (fd, mut peer) = pair();
    let (events, _rx) = recorder();
    assert_eq!(
        manager.start(fd, b"generation=1\n", events),
        START_INVALID_CONFIG
    );
    assert_eof(&mut peer);

    let (fd, _active_peer) = pair();
    let (events, _active_rx) = recorder();
    assert_eq!(manager.start(fd, &config(5), events), 5);

    let (fd, mut busy_peer) = pair();
    let (events, busy_rx) = recorder();
    assert_eq!(manager.start(fd, &config(6), events), START_BUSY);
    assert_eof(&mut busy_peer);
    assert!(busy_rx.recv_timeout(Duration::from_millis(50)).is_err());

    // Unknown handles are ignored and do not disturb the active session.
    manager.stop(4);
    manager.stop(-1);
    assert!(manager.report(4).is_none());
    assert!(manager.report(5).is_some());
    manager.stop(5);

    // Generations must increase across sessions.
    for generation in [5, 2] {
        let (fd, mut peer) = pair();
        let (events, _rx) = recorder();
        assert_eq!(
            manager.start(fd, &config(generation), events),
            START_INVALID_CONFIG
        );
        assert_eof(&mut peer);
    }
    let (fd, _peer) = pair();
    let (events, _rx) = recorder();
    assert_eq!(manager.start(fd, &config(7), events), 7);
    manager.stop(7);
}

#[test]
fn take_descriptor_rejects_numbers_it_does_not_own() {
    assert!(take_descriptor(-1).is_none());
    // Far above any descriptor limit, so never open (a just-closed number could be
    // reused by a parallel test).
    assert!(take_descriptor(i32::MAX).is_none());
    let (local, mut peer) = UnixStream::pair().unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    drop(take_descriptor(local.into_raw_fd()).unwrap());
    assert_eof(&mut peer);
}

/// Calls `stop` for its own session from the callback, as a Kotlin bug might.
struct SelfStop {
    manager: Arc<Manager>,
    tx: mpsc::Sender<EventKind>,
}
impl Events for SelfStop {
    fn event(&mut self, generation: u64, kind: EventKind, _: &[u8]) -> bool {
        if kind == EventKind::Progress {
            self.manager.stop(generation as i64);
        }
        let _ = self.tx.send(kind);
        true
    }
}

#[test]
fn stop_from_the_session_callback_only_requests_cancellation() {
    let manager = Arc::new(Manager::default());
    let (fd, mut peer) = pair();
    let (tx, rx) = mpsc::channel();
    let events = Box::new(SelfStop {
        manager: manager.clone(),
        tx,
    });
    assert_eq!(manager.start(fd, &config(1), events), 1);
    let deadline = Instant::now() + Duration::from_secs(5);
    while rx.try_iter().all(|k| k != EventKind::Finished) {
        assert!(Instant::now() < deadline, "no Finished event (deadlock?)");
        thread::sleep(Duration::from_millis(5));
    }
    let report = String::from_utf8(manager.report(1).unwrap()).unwrap();
    assert!(has_line(&report, "final_category=CANCELLED"), "{report}");
    assert_eof(&mut peer);
    manager.stop(1);
    assert!(manager.report(1).is_none());
}

#[test]
fn undelivered_events_are_counted_in_the_report() {
    let manager = Manager::default();
    let (fd, peer) = pair();
    let (tx, rx) = mpsc::channel();
    let events = Box::new(Channel { tx, deliver: false });
    assert_eq!(manager.start(fd, &config(1), events), 1);
    drop(peer);
    let (_, _, text, _) = finished(&rx);
    assert!(has_line(&text, "final_category=IO_EOF"), "{text}");
    assert!(!has_line(&text, "callback_failures=0"), "{text}");
    manager.stop(1);
}

#[test]
fn detached_io_threads_block_new_starts_until_they_return() {
    let manager = Manager::new(Options {
        stop_budget: Duration::ZERO,
        ..Options::default()
    });
    let (fd, mut peer) = pair();
    let (events, rx) = recorder();
    assert_eq!(manager.start(fd, &config(1), events), 1);
    rx.recv_timeout(Duration::from_secs(5)).unwrap();
    // Let the reader block in read(2); a reader that has not reached it yet would see
    // the shutdown flag and return on its own despite being reported detached.
    thread::sleep(Duration::from_millis(100));
    manager.stop(1);
    let (_, _, text, _) = rx.try_iter().find(|e| e.1 == EventKind::Finished).unwrap();
    assert!(has_line(&text, "io_reader=detached"), "{text}");
    assert!(
        has_line(&text, "io_descriptor=open-until-detached-thread-returns"),
        "{text}"
    );

    let (fd, mut next_peer) = pair();
    let (events, _next_rx) = recorder();
    assert_eq!(manager.start(fd, &config(2), events), START_STALE_IO);
    assert_eof(&mut next_peer);

    // One byte wakes the detached reader, which returns and releases the descriptor.
    peer.write_all(&[0]).unwrap();
    assert_eof(&mut peer);
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let (fd, _peer) = pair();
        let (events, _rx) = recorder();
        match manager.start(fd, &config(3), events) {
            3 => break,
            START_STALE_IO => {
                assert!(Instant::now() < deadline, "detached threads never returned");
                thread::sleep(Duration::from_millis(5));
            }
            other => panic!("unexpected start result {other}"),
        }
    }
    manager.stop(3);
}

#[test]
fn probe_is_unchanged_by_the_session_contract() {
    assert!(aap_jni::probe().lines().any(|l| l == "self_test=pass"));
}

fn projection_config(generation: u64, dir: &str, extra: &str) -> Vec<u8> {
    format!(
        "generation={generation}\nsession_uuid={UUID}\nprofile=legacy-1.5\nstop_after=projection\n\
         identity_dir={dir}\ntls_profile=tls12-aead\n{extra}"
    )
    .into_bytes()
}

#[test]
fn projection_config_keys_are_strict() {
    let parsed = parse_config(&projection_config(
        1,
        "/d",
        "peer_policy=first-use\nprojection_ms=60000\nsensors=true\nfocus_wait_ms=15000\n\
         ack_timeout_ms=3000\n",
    ))
    .unwrap();
    assert_eq!(parsed.session.stop_after.name(), "projection");
    assert_eq!(
        parsed.session.projection.limit,
        Some(Duration::from_secs(60))
    );
    // `true` is the P4 spelling of the restriction pair and still parses as it (P5.2).
    assert_eq!(parsed.session.projection.sensors, SensorSet::Restriction);
    assert_eq!(
        parsed.session.projection.focus_wait,
        Duration::from_secs(15)
    );
    assert_eq!(
        parsed.session.projection.ack_timeout,
        Duration::from_secs(3)
    );
    // A drive is longer than a response deadline. Every other millisecond key stops at
    // a minute; this one is documented to an hour, and until P6.1 asked for 90 s of
    // driving, nothing in between had ever been tried.
    for (value, seconds) in [("90000", 90), ("3600000", 3600)] {
        let drive = parse_config(&projection_config(
            1,
            "/d",
            &format!("peer_policy=first-use\nprojection_ms={value}\n"),
        ))
        .unwrap();
        assert_eq!(
            drive.session.projection.limit,
            Some(Duration::from_secs(seconds)),
            "{value}"
        );
    }

    // Defaults: run until Stop, no sensors, position redacted, the largest advertised
    // configuration.
    let defaults = parse_config(&projection_config(1, "/d", "peer_policy=first-use\n")).unwrap();
    assert_eq!(defaults.session.projection.limit, None);
    assert_eq!(defaults.session.projection.sensors, SensorSet::None);
    assert!(!defaults.session.projection.sensor_location);
    assert_eq!(defaults.session.video_preference.name(), "largest");

    // P5.2 sets: named, numeric, and mixed lists, all bounded by the advertisement later.
    for (value, expected) in [
        ("none", SensorSet::None),
        ("false", SensorSet::None),
        ("all", SensorSet::All),
        ("restriction", SensorSet::Restriction),
        ("CAR_SPEED", SensorSet::Only(vec![3])),
        ("1,CAR_SPEED,21", SensorSet::Only(vec![1, 3, 21])),
    ] {
        let parsed = parse_config(&projection_config(
            1,
            "/d",
            &format!("peer_policy=first-use\nsensors={value}\n"),
        ))
        .unwrap();
        assert_eq!(parsed.session.projection.sensors, expected, "{value}");
    }
    let located = parse_config(&projection_config(
        1,
        "/d",
        "peer_policy=first-use\nsensors=all\nsensor_location=true\n",
    ))
    .unwrap();
    assert!(located.session.projection.sensor_location);

    // P6.2: the cluster keys. Off by default, imperial by default, and the interval is
    // this source's own — the head unit's advertised floor is applied later, against a
    // descriptor this parser has never seen.
    assert!(!defaults.session.projection.navigation);
    assert!(!defaults.session.projection.navigation_force);
    assert_eq!(
        defaults.session.projection.navigation_units.name(),
        "imperial"
    );
    assert_eq!(
        defaults.session.projection.navigation_interval,
        Duration::from_millis(1000)
    );
    let nav = parse_config(&projection_config(
        1,
        "/d",
        "peer_policy=first-use\nnavigation=true\nnavigation_units=metric\n\
         navigation_interval_ms=400\nnavigation_focus_wait_ms=20000\nnavigation_force=true\n",
    ))
    .unwrap();
    assert!(nav.session.projection.navigation);
    assert!(nav.session.projection.navigation_force);
    assert_eq!(nav.session.projection.navigation_units.name(), "metric");
    assert_eq!(
        nav.session.projection.navigation_interval,
        Duration::from_millis(400)
    );
    assert_eq!(
        nav.session.projection.navigation_focus_wait,
        Duration::from_secs(20)
    );
    // P6.5: the script stays the default; the app asks for its own route.
    assert_eq!(nav.session.projection.navigation_source.name(), "script");
    let live = parse_config(&projection_config(
        1,
        "/d",
        "peer_policy=first-use\nnavigation=true\nnavigation_source=route\n",
    ))
    .unwrap();
    assert_eq!(live.session.projection.navigation_source.name(), "route");

    // The video preference applies to discovery too, so it is not a projection-only key.
    for (keys, expected) in [("video_preference=800x480\n", "800x480"), ("", "largest")] {
        let parsed = parse_config(&tls_config(
            1,
            "/d",
            &format!("peer_policy=first-use\n{keys}"),
        ))
        .unwrap();
        assert_eq!(parsed.session.video_preference.name(), expected, "{keys:?}");
    }

    for bad in [
        projection_config(1, "/d", "peer_policy=first-use\nprojection_ms=3600001\n"),
        projection_config(1, "/d", "peer_policy=first-use\nprojection_ms=0\n"),
        projection_config(1, "/d", "peer_policy=first-use\nsensors=yes\n"),
        projection_config(1, "/d", "peer_policy=first-use\nsensors=\n"),
        // NONE (0) is a placeholder, not a subscribable type.
        projection_config(1, "/d", "peer_policy=first-use\nsensors=0\n"),
        projection_config(1, "/d", "peer_policy=first-use\nsensors=27\n"),
        projection_config(1, "/d", "peer_policy=first-use\nsensors=3,bogus\n"),
        projection_config(1, "/d", "peer_policy=first-use\nsensor_location=yes\n"),
        tls_config(1, "/d", "peer_policy=first-use\nsensor_location=true\n"),
        projection_config(1, "/d", "peer_policy=first-use\nfocus_wait_ms=0\n"),
        projection_config(1, "/d", "peer_policy=first-use\nnavigation=yes\n"),
        projection_config(
            1,
            "/d",
            "peer_policy=first-use\nnavigation_units=nautical\n",
        ),
        projection_config(1, "/d", "peer_policy=first-use\nnavigation_interval_ms=0\n"),
        projection_config(
            1,
            "/d",
            "peer_policy=first-use\nnavigation_focus_wait_ms=60001\n",
        ),
        projection_config(1, "/d", "peer_policy=first-use\nnavigation_force=maybe\n"),
        projection_config(1, "/d", "peer_policy=first-use\nnavigation_source=live\n"),
        tls_config(1, "/d", "peer_policy=first-use\nnavigation_source=route\n"),
        // Cluster keys with another stop stage: there is no channel to open before
        // projection.
        tls_config(1, "/d", "peer_policy=first-use\nnavigation=true\n"),
        projection_config(1, "/d", "peer_policy=first-use\nvideo_preference=huge\n"),
        projection_config(1, "/d", "peer_policy=first-use\nvideo_preference=0x480\n"),
        // Projection keys with another stop stage, and without TLS at all.
        tls_config(1, "/d", "peer_policy=first-use\nsensors=true\n"),
        format!("generation=1\nsession_uuid={UUID}\nprofile=legacy-1.5\nprojection_ms=1000\n")
            .into_bytes(),
    ] {
        assert!(
            parse_config(&bad).is_err(),
            "{}",
            String::from_utf8_lossy(&bad)
        );
    }
}

#[test]
fn projection_session_streams_video_and_delivers_input_events() {
    let dir = temp_dir("projection");
    assert!(ensure_identity(&dir, false).contains("tls_self_test=pass"));
    let (local, remote) = UnixStream::pair().unwrap();
    remote
        .set_read_timeout(Some(Duration::from_millis(20)))
        .unwrap();
    // Only the 800x480 configuration is accepted, so the test-pattern frames stay inside
    // the session's pending-output limit; the preference for the largest configuration is
    // covered by the Ford fixture tests in aap-session.
    let hu = hu_sim::HeadUnit::new(hu_sim::Config {
        projection: hu_sim::Projection {
            setup_configs: Some(vec![1]),
            ..Default::default()
        },
        ..hu_sim::Config::default()
    });
    let pin = aap_tls::identity::hex(&hu.fingerprint);
    let head_unit = thread::spawn(move || hu_sim::serve(remote, hu, Duration::from_secs(20)));

    let manager = Manager::default();
    let (events, rx) = recorder();
    let config = projection_config(
        7,
        dir.to_str().unwrap(),
        &format!("peer_policy=pinned\npeer_pins={pin}\n"),
    );
    assert_eq!(manager.start(local.into(), &config, events), 7);

    // The encoder starts on the video start event and submits through the handle.
    let start = loop {
        let (generation, kind, text, _) = rx.recv_timeout(Duration::from_secs(10)).unwrap();
        assert_eq!(generation, 7);
        if kind == EventKind::VideoStart {
            break text;
        }
        assert_ne!(kind, EventKind::Finished, "session ended: {text}");
    };
    // Every event payload is one key=value field per line, like a report.
    for line in [
        "session=0",
        "channel=3",
        "config=1",
        "width=800",
        "height=480",
        "fps=30",
        "content_x=0",
        "content_width=800",
        "content_height=480",
        "touch=800x480",
    ] {
        assert!(has_line(&start, line), "missing {line} in\n{start}");
    }

    let mut encoder = test_pattern::Encoder::new(800, 480);
    let mut canvas = test_pattern::Canvas::new(800, 480, test_pattern::Yuv(128, 128, 128));
    let config_buffer = encoder.parameter_sets();
    assert_eq!(
        manager.submit_video(
            7,
            BufferFlags {
                codec_config: true,
                ..BufferFlags::default()
            },
            0,
            &config_buffer
        ),
        0
    );
    for i in 0..3i64 {
        canvas.fill(i as u32 * 32, 0, 32, 32, test_pattern::Yuv(200, 90, 90));
        let (bytes, key) = encoder.encode(&canvas, false);
        assert_eq!(
            manager.submit_video(
                7,
                BufferFlags {
                    key_frame: key,
                    ..BufferFlags::default()
                },
                i * 33_000,
                &bytes
            ),
            0
        );
    }
    // Unknown handles are refused rather than crashing the encoder thread.
    assert_eq!(
        manager.submit_video(99, BufferFlags::default(), 0, &config_buffer),
        SUBMIT_UNKNOWN_HANDLE
    );

    // Wait for the snapshot report to show every unit sent, then stop gracefully.
    let deadline = Instant::now() + Duration::from_secs(10);
    let sent = loop {
        let text = String::from_utf8(manager.report(7).unwrap()).unwrap();
        if text.contains("video_configs_sent=1 video_keyframes_sent=1 video_frames_sent=2") {
            break text;
        }
        assert!(Instant::now() < deadline, "units not sent:\n{text}");
        thread::sleep(Duration::from_millis(20));
    };
    assert!(sent.contains("projection_step=streaming"), "{sent}");
    manager.request_shutdown(7);
    let (_, _, text, _) = finished(&rx);
    for line in [
        "stop_after=projection",
        "last_completed_stage=projecting",
        "projection_step=streaming",
        "final_category=CANCELLED",
        "io_descriptor=closed",
        "callback_failures=0",
    ] {
        assert!(has_line(&text, line), "missing {line}:\n{text}");
    }
    assert!(text.contains("video_configs_sent=1 video_keyframes_sent=1 video_frames_sent=2"));
    manager.stop(7);
    let hu = head_unit.join().unwrap();
    assert!(hu.errors.is_empty(), "{:?}", hu.errors);
    assert_eq!(hu.media.len(), 4);
    assert!(hu.ids().contains(&aap_proto::ids::av::STOP_INDICATION));
    std::fs::remove_dir_all(&dir).unwrap();
}
