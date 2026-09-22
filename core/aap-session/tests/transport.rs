//! Event-loop tests over real Unix socket pairs. These prove poll/wakeup behavior for
//! pollable descriptors only; they say nothing about the Android accessory descriptor.
#![cfg(unix)]
use aap_session::{
    transport::{run, Cancellation, Protocol},
    Category, Config, Session, Stage,
};
use std::{
    io::{self, Read, Write},
    os::{fd::AsRawFd, unix::net::UnixStream},
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

/// Runs a fresh session on its own thread, as the JNI worker and harness will.
fn spawn_session<T: aap_session::transport::Transport + Send + 'static>(
    mut transport: T,
    config: Config,
) -> (
    aap_session::transport::CancelHandle,
    thread::JoinHandle<Session>,
) {
    let cancellation = Cancellation::new().unwrap();
    let handle = cancellation.handle();
    let worker = thread::spawn(move || {
        let mut session = Session::new(config, 1, Instant::now());
        run(&mut transport, &mut session, &cancellation);
        session
    });
    (handle, worker)
}

fn read_exact_timeout(peer: &mut UnixStream, n: usize) -> Vec<u8> {
    peer.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    let mut out = vec![0; n];
    peer.read_exact(&mut out).unwrap();
    out
}

#[test]
fn split_writes_produce_fixture_response_and_tls_stop() {
    let (local, mut peer) = UnixStream::pair().unwrap();
    let (_, worker) = spawn_session(local, Config::default());
    for byte in request() {
        peer.write_all(&[byte]).unwrap();
        thread::sleep(Duration::from_millis(2));
    }
    assert_eq!(read_exact_timeout(&mut peer, 12), response());
    let tls = tls_message();
    peer.write_all(&tls[..3]).unwrap();
    thread::sleep(Duration::from_millis(5));
    peer.write_all(&tls[3..]).unwrap();
    let session = worker.join().unwrap();
    let r = session.report();
    let outcome = r.outcome.as_ref().unwrap();
    assert_eq!(outcome.category, Category::StageLimit, "{r}");
    assert_eq!(r.last_completed, Some(Stage::Version));
    assert!(!r.tls_before_response_written);
    assert_eq!((r.counters.bytes_in, r.counters.bytes_out), (21, 12));
}

#[test]
fn coalesced_input_still_flushes_response_before_finishing() {
    let (local, mut peer) = UnixStream::pair().unwrap();
    let mut both = request();
    both.extend(tls_message());
    peer.write_all(&both).unwrap();
    let (_, worker) = spawn_session(local, Config::default());
    assert_eq!(read_exact_timeout(&mut peer, 12), response());
    let session = worker.join().unwrap();
    let r = session.report();
    assert_eq!(r.outcome.as_ref().unwrap().category, Category::StageLimit);
    assert_eq!(r.last_completed, Some(Stage::Version));
    assert_eq!(r.abandoned_output, 0);
}

/// Injects EINTR, WouldBlock, one-byte reads, and one-byte writes around a real socket.
struct Flaky {
    inner: UnixStream,
    reads: u32,
    writes: u32,
}
impl Read for Flaky {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.reads += 1;
        match self.reads % 3 {
            0 => Err(io::ErrorKind::Interrupted.into()),
            1 => Err(io::ErrorKind::WouldBlock.into()),
            _ => self.inner.read(&mut buf[..1]),
        }
    }
}
impl Write for Flaky {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.writes += 1;
        if self.writes % 2 == 0 {
            return Err(io::ErrorKind::Interrupted.into());
        }
        self.inner.write(&buf[..1])
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
impl AsRawFd for Flaky {
    fn as_raw_fd(&self) -> std::os::fd::RawFd {
        self.inner.as_raw_fd()
    }
}

#[test]
fn eintr_wouldblock_and_one_byte_io_preserve_order() {
    let (local, mut peer) = UnixStream::pair().unwrap();
    let flaky = Flaky {
        inner: local,
        reads: 0,
        writes: 0,
    };
    let (_, worker) = spawn_session(flaky, Config::default());
    peer.write_all(&request()).unwrap();
    assert_eq!(read_exact_timeout(&mut peer, 12), response());
    peer.write_all(&tls_message()).unwrap();
    let session = worker.join().unwrap();
    let r = session.report();
    assert_eq!(
        r.outcome.as_ref().unwrap().category,
        Category::StageLimit,
        "{r}"
    );
    assert_eq!((r.counters.bytes_in, r.counters.bytes_out), (21, 12));
}

#[test]
fn eof_timeout_and_write_failure_are_classified() {
    let (local, peer) = UnixStream::pair().unwrap();
    drop(peer);
    let (_, worker) = spawn_session(local, Config::default());
    let session = worker.join().unwrap();
    let outcome = session.report().outcome.clone().unwrap();
    assert_eq!(
        (outcome.category, outcome.stage),
        (Category::IoEof, Stage::Version)
    );

    let (local, _peer) = UnixStream::pair().unwrap();
    let config = Config {
        version_wait: Duration::from_millis(50),
        ..Config::default()
    };
    let started = Instant::now();
    let (_, worker) = spawn_session(local, config);
    let session = worker.join().unwrap();
    assert_eq!(
        session.report().outcome.as_ref().unwrap().category,
        Category::VersionTimeout
    );
    let elapsed = started.elapsed();
    assert!(
        elapsed >= Duration::from_millis(50) && elapsed < Duration::from_secs(2),
        "{elapsed:?}"
    );

    // The peer sends a request then disappears: the response write fails with EPIPE
    // before EOF is read, so the response is not counted as written.
    let (local, mut peer) = UnixStream::pair().unwrap();
    peer.write_all(&request()).unwrap();
    drop(peer);
    let (_, worker) = spawn_session(local, Config::default());
    let session = worker.join().unwrap();
    let r = session.report();
    let outcome = r.outcome.as_ref().unwrap();
    assert_eq!(outcome.category, Category::IoError, "{r}");
    assert_eq!(outcome.os_error, Some(libc::EPIPE));
    assert_eq!(r.last_completed, None);
    assert_eq!(r.abandoned_output, 12);
}

#[test]
fn cancel_wakes_a_loop_waiting_to_read() {
    let (local, _peer) = UnixStream::pair().unwrap();
    let (cancel, worker) = spawn_session(local, Config::default());
    thread::sleep(Duration::from_millis(100));
    assert!(!worker.is_finished());
    let requested = Instant::now();
    cancel.cancel();
    cancel.cancel();
    let session = worker.join().unwrap();
    assert!(requested.elapsed() < Duration::from_secs(1));
    let outcome = session.report().outcome.clone().unwrap();
    assert_eq!(
        (outcome.category, outcome.stage),
        (Category::Cancelled, Stage::Version)
    );
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
fn cancel_wakes_a_loop_blocked_on_a_full_write_buffer() {
    let (mut local, peer) = UnixStream::pair().unwrap();
    let (progress, progress_rx) = mpsc::channel();
    let cancellation = Cancellation::new().unwrap();
    let cancel = cancellation.handle();
    let worker = thread::spawn(move || {
        let mut flood = Flood {
            output: vec![0x5a; 8 * 1024 * 1024],
            written: 0,
            progress,
            cancelled: false,
        };
        run(&mut local, &mut flood, &cancellation);
        flood
    });
    let first = progress_rx.recv_timeout(Duration::from_secs(5)).unwrap();
    assert!(first > 0);
    // Stable progress means the socket buffer is full and the loop is waiting in poll.
    let mut last = first;
    while let Ok(n) = progress_rx.recv_timeout(Duration::from_millis(200)) {
        last = n;
    }
    assert!(last < 8 * 1024 * 1024);
    assert!(!worker.is_finished());
    let requested = Instant::now();
    cancel.cancel();
    let flood = worker.join().unwrap();
    assert!(requested.elapsed() < Duration::from_secs(1));
    assert!(flood.cancelled);
    assert_eq!(flood.written, last);
    drop(peer);
}
