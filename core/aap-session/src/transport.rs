//! Ordered, cancellable event loop for **pollable** descriptors (TCP lab adapter,
//! socket pairs). One thread owns the descriptor, writes before reading, and waits in
//! `poll` on the descriptor plus a wakeup used for cancellation, graceful shutdown
//! requests, and encoder submissions.
//!
//! Not valid for the Android accessory descriptor as audited: `f_accessory` defines no
//! `poll` operation and blocks reads/writes regardless of `O_NONBLOCK`, so readiness is
//! always reported and a blocked read cannot observe this wakeup. See docs/references.md.
use crate::Session;
use aap_wire::READ_BUFFER;
use std::{
    io::{self, Read, Write},
    os::{
        fd::{AsRawFd, RawFd},
        unix::net::UnixStream,
    },
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Instant,
};

/// Sans-IO protocol driven by `run`. Implemented by `Session`; tests may substitute.
pub trait Protocol {
    fn receive(&mut self, bytes: &[u8], now: Instant);
    fn pending_output(&self) -> &[u8];
    fn output_written(&mut self, n: usize, now: Instant);
    fn next_deadline(&self) -> Option<Instant>;
    fn on_timer(&mut self, now: Instant);
    fn on_transport_closed(&mut self, error: Option<&io::Error>, now: Instant);
    fn on_cancelled(&mut self, now: Instant);
    /// A graceful stop request; by default the same as cancellation.
    fn on_shutdown_requested(&mut self, now: Instant) {
        self.on_cancelled(now)
    }
    fn wants_read(&self) -> bool;
    fn finished(&self) -> bool;
}

impl Protocol for Session {
    fn receive(&mut self, bytes: &[u8], now: Instant) {
        Session::receive(self, bytes, now)
    }
    fn pending_output(&self) -> &[u8] {
        Session::pending_output(self)
    }
    fn output_written(&mut self, n: usize, now: Instant) {
        Session::output_written(self, n, now)
    }
    fn next_deadline(&self) -> Option<Instant> {
        Session::next_deadline(self)
    }
    fn on_timer(&mut self, now: Instant) {
        Session::on_timer(self, now)
    }
    fn on_transport_closed(&mut self, error: Option<&io::Error>, now: Instant) {
        Session::on_transport_closed(self, error, now)
    }
    fn on_cancelled(&mut self, now: Instant) {
        Session::on_cancelled(self, now)
    }
    fn on_shutdown_requested(&mut self, now: Instant) {
        Session::on_shutdown_requested(self, now)
    }
    fn wants_read(&self) -> bool {
        Session::wants_read(self)
    }
    fn finished(&self) -> bool {
        Session::finished(self)
    }
}

/// Readable, writable, pollable byte stream. `run` switches it to nonblocking mode.
pub trait Transport: Read + Write + AsRawFd {}
impl<T: Read + Write + AsRawFd> Transport for T {}

/// Wakeup owned by the loop thread. Create one per attempt.
pub struct Cancellation {
    flag: Arc<AtomicBool>,
    shutdown: Arc<AtomicBool>,
    wake_rx: UnixStream,
    wake_tx: Arc<UnixStream>,
}

/// Cloneable, thread-safe stop request for one `Cancellation`.
#[derive(Clone)]
pub struct CancelHandle {
    flag: Arc<AtomicBool>,
    shutdown: Arc<AtomicBool>,
    wake_tx: Arc<UnixStream>,
}

impl Cancellation {
    pub fn new() -> io::Result<Self> {
        let (wake_rx, wake_tx) = UnixStream::pair()?;
        wake_rx.set_nonblocking(true)?;
        wake_tx.set_nonblocking(true)?;
        Ok(Self {
            flag: Arc::new(AtomicBool::new(false)),
            shutdown: Arc::new(AtomicBool::new(false)),
            wake_rx,
            wake_tx: Arc::new(wake_tx),
        })
    }
    pub fn handle(&self) -> CancelHandle {
        CancelHandle {
            flag: self.flag.clone(),
            shutdown: self.shutdown.clone(),
            wake_tx: self.wake_tx.clone(),
        }
    }
    fn is_cancelled(&self) -> bool {
        self.flag.load(Ordering::SeqCst)
    }
    fn drain(&self) {
        let mut buffer = [0u8; 64];
        while matches!((&self.wake_rx).read(&mut buffer), Ok(n) if n > 0) {}
    }
}

impl CancelHandle {
    /// Idempotent and nonblocking. A full wakeup buffer already guarantees a wakeup.
    pub fn cancel(&self) {
        self.flag.store(true, Ordering::SeqCst);
        self.wake();
    }
    /// Graceful stop, delivered once as `on_shutdown_requested`. Nonblocking.
    pub fn request_shutdown(&self) {
        self.shutdown.store(true, Ordering::SeqCst);
        self.wake();
    }
    /// Runs the loop once more (timers and queued media) without stopping it.
    pub fn wake(&self) {
        let _ = (&*self.wake_tx).write(&[1]);
    }
}

/// Drive `protocol` over `transport` until it finishes, the transport fails, or
/// cancellation is observed. Returns when the protocol has a final outcome.
pub fn run<T, P>(transport: &mut T, protocol: &mut P, cancellation: &Cancellation)
where
    T: Transport + ?Sized,
    P: Protocol + ?Sized,
{
    run_with_progress(transport, protocol, cancellation, |_| {})
}

/// [`run`], calling `progress` on this thread after each batch of protocol updates.
pub fn run_with_progress<T, P, F>(
    transport: &mut T,
    protocol: &mut P,
    cancellation: &Cancellation,
    mut progress: F,
) where
    T: Transport + ?Sized,
    P: Protocol + ?Sized,
    F: FnMut(&mut P),
{
    if let Err(e) = set_nonblocking(transport.as_raw_fd()) {
        protocol.on_transport_closed(Some(&e), Instant::now());
        return progress(protocol);
    }
    let mut buffer = vec![0u8; READ_BUFFER];
    loop {
        progress(protocol);
        if cancellation.is_cancelled() {
            protocol.on_cancelled(Instant::now());
            return progress(protocol);
        }
        if cancellation.shutdown.swap(false, Ordering::SeqCst) {
            protocol.on_shutdown_requested(Instant::now());
        }
        protocol.on_timer(Instant::now());
        if protocol.finished() {
            return progress(protocol);
        }
        // Flush first so already-decided output precedes processing of later input.
        if let Err(e) = flush(transport, protocol) {
            protocol.on_transport_closed(Some(&e), Instant::now());
            return progress(protocol);
        }
        if protocol.finished() {
            return progress(protocol);
        }
        let wants_read = protocol.wants_read();
        let mut events = 0;
        if wants_read {
            events |= libc::POLLIN;
        }
        if !protocol.pending_output().is_empty() {
            events |= libc::POLLOUT;
        }
        let mut fds = [
            libc::pollfd {
                // A negative descriptor is ignored, so an idle transport cannot spin on HUP.
                fd: if events == 0 {
                    -1
                } else {
                    transport.as_raw_fd()
                },
                events,
                revents: 0,
            },
            libc::pollfd {
                fd: cancellation.wake_rx.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            },
        ];
        let timeout = poll_timeout(protocol.next_deadline(), Instant::now());
        // SAFETY: `fds` is a valid, exclusively borrowed array of two pollfd records.
        let rc = unsafe { libc::poll(fds.as_mut_ptr(), fds.len() as libc::nfds_t, timeout) };
        if rc < 0 {
            let e = io::Error::last_os_error();
            if e.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            protocol.on_transport_closed(Some(&e), Instant::now());
            return progress(protocol);
        }
        if fds[1].revents != 0 {
            cancellation.drain();
            continue;
        }
        let revents = fds[0].revents;
        if revents & libc::POLLNVAL != 0 {
            let e = io::Error::from_raw_os_error(libc::EBADF);
            protocol.on_transport_closed(Some(&e), Instant::now());
            return progress(protocol);
        }
        // Errors and hangups surface through the next read or write attempt.
        if wants_read && revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) != 0 {
            match transport.read(&mut buffer) {
                Ok(0) => {
                    protocol.on_transport_closed(None, Instant::now());
                    return progress(protocol);
                }
                Ok(n) => protocol.receive(&buffer[..n], Instant::now()),
                Err(e)
                    if matches!(
                        e.kind(),
                        io::ErrorKind::Interrupted | io::ErrorKind::WouldBlock
                    ) => {}
                Err(e) => {
                    protocol.on_transport_closed(Some(&e), Instant::now());
                    return progress(protocol);
                }
            }
        }
    }
}

fn flush<T, P>(transport: &mut T, protocol: &mut P) -> io::Result<()>
where
    T: Transport + ?Sized,
    P: Protocol + ?Sized,
{
    loop {
        let pending = protocol.pending_output();
        if pending.is_empty() {
            return Ok(());
        }
        match transport.write(pending) {
            Ok(0) => return Err(io::ErrorKind::WriteZero.into()),
            Ok(n) => protocol.output_written(n, Instant::now()),
            Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => return Ok(()),
            Err(e) => return Err(e),
        }
    }
}

fn poll_timeout(deadline: Option<Instant>, now: Instant) -> libc::c_int {
    match deadline {
        None => -1,
        Some(deadline) => {
            let nanos = deadline.saturating_duration_since(now).as_nanos();
            // Round up so a wakeup never precedes its deadline.
            nanos
                .div_ceil(1_000_000)
                .try_into()
                .unwrap_or(libc::c_int::MAX)
        }
    }
}

fn set_nonblocking(fd: RawFd) -> io::Result<()> {
    // SAFETY: fcntl with F_GETFL/F_SETFL only reads and updates descriptor status flags.
    unsafe {
        let flags = libc::fcntl(fd, libc::F_GETFL);
        if flags < 0 {
            return Err(io::Error::last_os_error());
        }
        if flags & libc::O_NONBLOCK == 0
            && libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) < 0
        {
            return Err(io::Error::last_os_error());
        }
    }
    Ok(())
}
