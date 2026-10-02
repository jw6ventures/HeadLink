//! Threaded blocking-descriptor transport over real blocking socket pairs and FIFOs.
//! These prove signal interruption of socket and pipe waits on the host kernel only;
//! the Android accessory driver must be tested on the target phone.
#![cfg(any(target_os = "linux", target_os = "android"))]
use aap_session::{
    accessory::{interrupt_signal, run, Options, Stop, Teardown, ThreadEnd, WRITE_CHUNK},
    transport::Protocol,
    Category, Config, Session, Stage,
};
use std::{
    ffi::CString,
    fs::OpenOptions,
    io::{self, Read, Write},
    os::{
        fd::{AsRawFd, OwnedFd},
        unix::net::UnixStream,
    },
    sync::mpsc,
    thread,
    time::{Duration, Instant},
};

fn hex(s: &str) -> Vec<u8> {
    s.split_whitespace()
        .map(|b| u8::from_str_radix(b, 16).unwrap())
        .collect()
}
fn request() -> Vec<u8> {
    hex(include_str!(
        "../../../protocol/fixtures/version-request.hex"
    ))
}
fn response() -> Vec<u8> {
    hex(include_str!(
        "../../../protocol/fixtures/version-response.hex"
    ))
}
fn tls_message() -> Vec<u8> {
    hex("00 03 00 07 00 03 16 03 01 00 04")
}

type Worker = thread::JoinHandle<(Session, Teardown)>;

/// Runs a fresh session on its own thread, as the JNI worker does.
fn spawn_session(fd: OwnedFd, config: Config, options: Options) -> (Stop, Worker) {
    let stop = Stop::new();
    let worker_stop = stop.clone();
    let worker = thread::spawn(move || {
        let mut session = Session::new(config, 1, Instant::now());
        let teardown = run(fd, &mut session, &worker_stop, &options, |_| {});
        (session, teardown)
    });
    (stop, worker)
}

fn joined(teardown: &Teardown) -> bool {
    teardown.reader == ThreadEnd::Joined
        && teardown.writer == ThreadEnd::Joined
        && teardown.descriptor_closed()
}

/// The descriptor handed to `run` is closed once the peer reads EOF.
fn assert_peer_sees_eof(peer: &mut UnixStream) {
    peer.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    let mut rest = Vec::new();
    peer.read_to_end(&mut rest).unwrap();
}

#[test]
fn version_exchange_then_tls_stop_over_blocking_socket() {
    let (local, mut peer) = UnixStream::pair().unwrap();
    // A nonblocking descriptor is switched to blocking rather than spinning.
    local.set_nonblocking(true).unwrap();
    let (_, worker) = spawn_session(local.into(), Config::default(), Options::default());
    for part in request().chunks(3) {
        peer.write_all(part).unwrap();
        thread::sleep(Duration::from_millis(2));
    }
    peer.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    let mut got = [0u8; 12];
    peer.read_exact(&mut got).unwrap();
    assert_eq!(got.to_vec(), response());
    let tls = tls_message();
    peer.write_all(&tls[..4]).unwrap();
    thread::sleep(Duration::from_millis(5));
    peer.write_all(&tls[4..]).unwrap();
    let (session, teardown) = worker.join().unwrap();
    let r = session.report();
    assert_eq!(
        r.outcome.as_ref().unwrap().category,
        Category::StageLimit,
        "{r}"
    );
    assert_eq!(r.last_completed, Some(Stage::Version));
    assert_eq!((r.counters.bytes_in, r.counters.bytes_out), (21, 12));
    assert!(joined(&teardown), "{teardown}");
    assert_eq!(teardown.interrupt_signal, Some(interrupt_signal().unwrap()));
    assert_peer_sees_eof(&mut peer);
}

#[test]
fn a_panic_while_driving_still_stops_the_io_threads() {
    let (local, mut peer) = UnixStream::pair().unwrap();
    let stop = Stop::new();
    let worker_stop = stop.clone();
    let worker = thread::spawn(move || {
        let mut session = Session::new(Config::default(), 1, Instant::now());
        run(
            local.into(),
            &mut session,
            &worker_stop,
            &Options::default(),
            |_| panic!("progress callback failed"),
        )
    });
    // The panic reaches the caller, as the JNI worker's catch_unwind expects ...
    assert!(worker.join().is_err());
    // ... but only after the reader, blocked on a silent peer, and the writer were
    // stopped and the descriptor closed: nothing is left to refuse the next session.
    assert!(!stop.io_threads_alive());
    assert_peer_sees_eof(&mut peer);
}

#[test]
fn stop_interrupts_a_read_blocked_on_a_silent_peer() {
    let (local, mut peer) = UnixStream::pair().unwrap();
    let (stop, worker) = spawn_session(local.into(), Config::default(), Options::default());
    thread::sleep(Duration::from_millis(100));
    assert!(!worker.is_finished());
    let requested = Instant::now();
    stop.request();
    stop.request();
    let (session, teardown) = worker.join().unwrap();
    assert!(requested.elapsed() < Duration::from_secs(1));
    let outcome = session.report().outcome.clone().unwrap();
    assert_eq!(
        (outcome.category, outcome.stage),
        (Category::Cancelled, Stage::Version)
    );
    assert!(joined(&teardown), "{teardown}");
    // The reader was blocked in read(2), so at least one signal was needed.
    assert!(teardown.signals_sent >= 1, "{teardown}");
    assert!(!stop.io_threads_alive());
    assert_peer_sees_eof(&mut peer);
}

#[test]
fn stop_interrupts_a_read_blocked_on_a_fifo() {
    let path = std::env::temp_dir().join(format!(
        "aap-accessory-test-{}-{:?}.fifo",
        std::process::id(),
        thread::current().id()
    ));
    let c_path = CString::new(path.as_os_str().as_encoded_bytes()).unwrap();
    // SAFETY: `c_path` is a valid NUL-terminated path.
    assert_eq!(unsafe { libc::mkfifo(c_path.as_ptr(), 0o600) }, 0);
    // Read-write open does not wait for a peer; nothing is ever written.
    let fifo = OpenOptions::new()
        .read(true)
        .write(true)
        .open(&path)
        .unwrap();
    std::fs::remove_file(&path).unwrap();
    let (stop, worker) = spawn_session(fifo.into(), Config::default(), Options::default());
    thread::sleep(Duration::from_millis(100));
    assert!(!worker.is_finished());
    let requested = Instant::now();
    stop.request();
    let (session, teardown) = worker.join().unwrap();
    assert!(requested.elapsed() < Duration::from_secs(1));
    assert_eq!(
        session.report().outcome.as_ref().unwrap().category,
        Category::Cancelled
    );
    assert!(joined(&teardown), "{teardown}");
    assert!(teardown.signals_sent >= 1, "{teardown}");
}

/// Produces far more output than a socket buffer holds and never reads.
struct Flood {
    output: Vec<u8>,
    written: usize,
    progress: mpsc::Sender<usize>,
    cancelled: bool,
}
impl Protocol for Flood {
    fn receive(&mut self, _: &[u8], _: Instant) {}
    fn pending_output(&self) -> &[u8] {
        &self.output[self.written..]
    }
    fn output_written(&mut self, n: usize, _: Instant) {
        self.written += n;
        let _ = self.progress.send(self.written);
    }
    fn next_deadline(&self) -> Option<Instant> {
        None
    }
    fn on_timer(&mut self, _: Instant) {}
    fn on_transport_closed(&mut self, error: Option<&io::Error>, _: Instant) {
        panic!("unexpected transport close: {error:?}");
    }
    fn on_cancelled(&mut self, _: Instant) {
        self.cancelled = true;
    }
    fn wants_read(&self) -> bool {
        false
    }
    fn finished(&self) -> bool {
        self.written == self.output.len()
    }
}

#[test]
fn stop_interrupts_a_write_blocked_on_a_full_buffer() {
    let (local, peer) = UnixStream::pair().unwrap();
    let (progress, progress_rx) = mpsc::channel();
    let stop = Stop::new();
    let worker_stop = stop.clone();
    let worker = thread::spawn(move || {
        let mut flood = Flood {
            output: vec![0x5a; 8 * 1024 * 1024],
            written: 0,
            progress,
            cancelled: false,
        };
        let teardown = run(
            local.into(),
            &mut flood,
            &worker_stop,
            &Options::default(),
            |_| {},
        );
        (flood, teardown)
    });
    let first = progress_rx.recv_timeout(Duration::from_secs(5)).unwrap();
    assert!(first > 0);
    // Stable progress means the socket buffer is full and the writer is blocked.
    let mut last = first;
    while let Ok(n) = progress_rx.recv_timeout(Duration::from_millis(200)) {
        last = n;
    }
    assert!(last < 8 * 1024 * 1024);
    assert!(!worker.is_finished());
    let requested = Instant::now();
    stop.request();
    let (flood, teardown) = worker.join().unwrap();
    assert!(requested.elapsed() < Duration::from_secs(1));
    assert!(flood.cancelled);
    assert_eq!(flood.written, last);
    assert!(joined(&teardown), "{teardown}");
    assert!(teardown.signals_sent >= 2, "{teardown}");
    drop(peer);
}

#[test]
fn stop_requested_around_start_always_returns_promptly() {
    for i in 0..200 {
        let (local, _peer) = UnixStream::pair().unwrap();
        let (stop, worker) = if i % 2 == 0 {
            let stop = Stop::new();
            // Requested before `run` begins.
            stop.request();
            let worker_stop = stop.clone();
            let worker = thread::spawn(move || {
                let mut session = Session::new(Config::default(), 1, Instant::now());
                let teardown = run(
                    local.into(),
                    &mut session,
                    &worker_stop,
                    &Options::default(),
                    |_| {},
                );
                (session, teardown)
            });
            (stop, worker)
        } else {
            let pair = spawn_session(local.into(), Config::default(), Options::default());
            if i % 4 == 1 {
                thread::yield_now();
            }
            pair.0.request();
            pair
        };
        let requested = Instant::now();
        let (session, teardown) = worker.join().unwrap();
        assert!(
            requested.elapsed() < Duration::from_secs(1),
            "iteration {i}"
        );
        assert_eq!(
            session.report().outcome.as_ref().unwrap().category,
            Category::Cancelled,
            "iteration {i}"
        );
        assert!(joined(&teardown), "iteration {i}: {teardown}");
        assert!(!stop.io_threads_alive());
    }
}

#[test]
fn eof_and_timeout_are_classified_with_clean_teardown() {
    let (local, peer) = UnixStream::pair().unwrap();
    drop(peer);
    let (_, worker) = spawn_session(local.into(), Config::default(), Options::default());
    let (session, teardown) = worker.join().unwrap();
    let outcome = session.report().outcome.clone().unwrap();
    assert_eq!(
        (outcome.category, outcome.stage),
        (Category::IoEof, Stage::Version)
    );
    assert!(joined(&teardown), "{teardown}");

    let (local, mut peer) = UnixStream::pair().unwrap();
    let config = Config {
        version_wait: Duration::from_millis(50),
        ..Config::default()
    };
    let started = Instant::now();
    let (_, worker) = spawn_session(local.into(), config, Options::default());
    let (session, teardown) = worker.join().unwrap();
    assert_eq!(
        session.report().outcome.as_ref().unwrap().category,
        Category::VersionTimeout
    );
    let elapsed = started.elapsed();
    assert!(
        elapsed >= Duration::from_millis(50) && elapsed < Duration::from_secs(2),
        "{elapsed:?}"
    );
    assert!(joined(&teardown), "{teardown}");
    assert_peer_sees_eof(&mut peer);
}

#[test]
fn unsupported_version_sends_nothing_and_closes() {
    let (local, mut peer) = UnixStream::pair().unwrap();
    // The 1.7 request is refused only by the legacy profile; the default answers it.
    let config = Config {
        profile: aap_session::version::Profile::Legacy15,
        ..Config::default()
    };
    let (_, worker) = spawn_session(local.into(), config, Options::default());
    peer.write_all(&hex("00 03 00 06 00 01 00 01 00 07"))
        .unwrap();
    let (session, teardown) = worker.join().unwrap();
    let r = session.report();
    assert_eq!(
        r.outcome.as_ref().unwrap().category,
        Category::VersionUnsupported
    );
    assert_eq!(r.counters.bytes_out, 0);
    assert!(joined(&teardown), "{teardown}");
    peer.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    let mut rest = Vec::new();
    peer.read_to_end(&mut rest).unwrap();
    assert!(rest.is_empty(), "{rest:02x?}");
}

#[test]
fn a_thread_not_interrupted_in_budget_is_detached_and_keeps_the_descriptor() {
    let (local, mut peer) = UnixStream::pair().unwrap();
    // A zero budget detaches without signalling, standing in for a driver wait that
    // signals cannot interrupt.
    let options = Options {
        stop_budget: Duration::ZERO,
        ..Options::default()
    };
    let (stop, worker) = spawn_session(local.into(), Config::default(), options);
    // Let the reader block in read(2) before the zero-budget teardown.
    thread::sleep(Duration::from_millis(100));
    stop.request();
    let (session, teardown) = worker.join().unwrap();
    assert_eq!(
        session.report().outcome.as_ref().unwrap().category,
        Category::Cancelled
    );
    assert_eq!(teardown.reader, ThreadEnd::Detached, "{teardown}");
    assert!(!teardown.descriptor_closed());
    assert!(teardown
        .to_string()
        .lines()
        .any(|l| l == "io_descriptor=open-until-detached-thread-returns"));
    assert!(stop.io_threads_alive());
    // Still open: the peer sees no EOF yet.
    peer.set_read_timeout(Some(Duration::from_millis(100)))
        .unwrap();
    let mut byte = [0u8; 1];
    let pending = peer.read(&mut byte).unwrap_err();
    assert_eq!(pending.kind(), io::ErrorKind::WouldBlock);
    // The detached reader returns after its next read and releases the descriptor.
    peer.write_all(&[0]).unwrap();
    let deadline = Instant::now() + Duration::from_secs(5);
    while stop.io_threads_alive() {
        assert!(Instant::now() < deadline, "detached thread did not return");
        thread::sleep(Duration::from_millis(5));
    }
    assert_peer_sees_eof(&mut peer);
}

#[test]
fn progress_runs_on_the_protocol_thread_and_never_regresses() {
    let (local, mut peer) = UnixStream::pair().unwrap();
    let stop = Stop::new();
    let caller = thread::current().id();
    let (seen_tx, seen_rx) = mpsc::channel();
    let worker = thread::spawn(move || {
        let worker_thread = thread::current().id();
        assert_ne!(worker_thread, caller);
        let mut session = Session::new(Config::default(), 1, Instant::now());
        let teardown = run(
            local.into(),
            &mut session,
            &stop,
            &Options::default(),
            |s: &mut Session| {
                assert_eq!(thread::current().id(), worker_thread);
                let r = s.report();
                let _ = seen_tx.send((
                    r.response.is_some(),
                    r.last_completed,
                    r.outcome.as_ref().map(|o| o.category),
                ));
            },
        );
        (session, teardown)
    });
    peer.write_all(&request()).unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    let mut got = [0u8; 12];
    peer.read_exact(&mut got).unwrap();
    peer.write_all(&tls_message()).unwrap();
    let (_, teardown) = worker.join().unwrap();
    assert!(joined(&teardown));
    let seen: Vec<_> = seen_rx.try_iter().collect();
    // Progress runs once per batch, so "response written" can share a batch with the
    // peer's TLS bytes. A decided response is always observed before the writer gets it.
    assert!(seen.contains(&(true, None, None)), "{seen:?}");
    assert!(
        seen.windows(2)
            .all(|w| (w[0].0, w[0].1.is_some(), w[0].2.is_some())
                <= (w[1].0, w[1].1.is_some(), w[1].2.is_some())),
        "milestones regressed: {seen:?}"
    );
    assert_eq!(
        seen.last(),
        Some(&(true, Some(Stage::Version), Some(Category::StageLimit)))
    );
}

/// One chunk of output whose write outlives a drive-loop iteration. The peer wakes the
/// protocol thread while the writer is blocked, and `receive` waits until the peer has
/// drained the chunk, so the write completes after this iteration took the writer's
/// progress and before it hands out the next chunk.
struct Drained {
    output: Vec<u8>,
    written: usize,
    received: usize,
    drain: mpsc::Sender<()>,
    drained: mpsc::Receiver<()>,
}
impl Protocol for Drained {
    fn receive(&mut self, _: &[u8], _: Instant) {
        self.received += 1;
        if self.received == 1 {
            self.drain.send(()).unwrap();
            self.drained.recv_timeout(Duration::from_secs(5)).unwrap();
            // Time for the writer to record the completed chunk before this iteration ends.
            thread::sleep(Duration::from_millis(50));
        }
    }
    fn pending_output(&self) -> &[u8] {
        &self.output[self.written..]
    }
    fn output_written(&mut self, n: usize, _: Instant) {
        self.written = (self.written + n).min(self.output.len());
    }
    fn next_deadline(&self) -> Option<Instant> {
        None
    }
    fn on_timer(&mut self, _: Instant) {}
    fn on_transport_closed(&mut self, error: Option<&io::Error>, _: Instant) {
        panic!("unexpected transport close: {error:?}");
    }
    fn on_cancelled(&mut self, _: Instant) {}
    fn wants_read(&self) -> bool {
        true
    }
    fn finished(&self) -> bool {
        // The second message ends the session; until then the writer keeps running.
        self.received >= 2 && self.written == self.output.len()
    }
}

/// Regression: a chunk the writer finished during an iteration was handed out again at
/// the end of that iteration, so the peer received the same bytes twice (a replayed TLS
/// record fails the head unit's MAC check and the session goes silent).
#[test]
fn a_write_finishing_during_an_iteration_is_not_sent_twice() {
    let (local, mut peer) = UnixStream::pair().unwrap();
    // A send buffer far smaller than one chunk, so the writer blocks until the peer reads.
    let small: libc::c_int = 1;
    // SAFETY: setsockopt reads one c_int of the given length from a live local.
    let set = unsafe {
        libc::setsockopt(
            local.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_SNDBUF,
            (&small as *const libc::c_int).cast(),
            size_of::<libc::c_int>() as libc::socklen_t,
        )
    };
    assert_eq!(set, 0, "{}", io::Error::last_os_error());
    let output: Vec<u8> = (0..WRITE_CHUNK).map(|i| (i % 251) as u8).collect();
    let expected = output.clone();
    let (drain_tx, drain_rx) = mpsc::channel();
    let (drained_tx, drained_rx) = mpsc::channel();
    let stop = Stop::new();
    let worker_stop = stop.clone();
    let worker = thread::spawn(move || {
        let mut protocol = Drained {
            output,
            written: 0,
            received: 0,
            drain: drain_tx,
            drained: drained_rx,
        };
        let teardown = run(
            local.into(),
            &mut protocol,
            &worker_stop,
            &Options::default(),
            |_| {},
        );
        (protocol.written, teardown)
    });
    // The writer is blocked in write(2) on the chunk; a received byte starts an iteration.
    thread::sleep(Duration::from_millis(100));
    peer.write_all(&[0x00]).unwrap();
    drain_rx.recv_timeout(Duration::from_secs(5)).unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    let mut got = vec![0u8; expected.len()];
    peer.read_exact(&mut got).unwrap();
    drained_tx.send(()).unwrap();
    // Anything the writer sends after the chunk while the session is still running.
    peer.set_read_timeout(Some(Duration::from_millis(500)))
        .unwrap();
    let mut rest = Vec::new();
    let mut buffer = [0u8; 4096];
    loop {
        match peer.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => rest.extend_from_slice(&buffer[..n]),
            Err(e)
                if matches!(
                    e.kind(),
                    io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) =>
            {
                break
            }
            Err(e) => panic!("{e}"),
        }
    }
    peer.write_all(&[0x01]).unwrap();
    let (written, teardown) = worker.join().unwrap();
    assert_eq!(got, expected);
    assert_eq!(
        rest.len(),
        0,
        "{} bytes of the chunk were sent twice",
        rest.len()
    );
    assert_eq!(written, expected.len());
    assert!(joined(&teardown), "{teardown}");
    assert_peer_sees_eof(&mut peer);
}
