//! Threaded transport for stream descriptors that cannot be polled or made
//! nonblocking. Written for the Android accessory descriptor: the audited
//! `f_accessory` driver has no `poll` and blocks in `read` and `write` regardless of
//! `O_NONBLOCK` (docs/references.md). It also runs over blocking sockets and FIFOs,
//! which is how it is tested off-device.
//!
//! * A reader thread and a writer thread are the only threads that call `read` and
//!   `write` on the descriptor. The caller of [`run`] owns the protocol, its timers,
//!   and output order, and hands the writer one ordered chunk at a time.
//! * Stopping sets a shutdown flag, then interrupts blocked system calls with
//!   `pthread_kill` of a signal whose handler does nothing and is installed without
//!   `SA_RESTART`, so the call fails with `EINTR`. A signal that arrives just before a
//!   thread enters `read` is lost, so it is re-sent every `signal_interval` until each
//!   thread has exited or `stop_budget` has elapsed.
//! * Each I/O thread holds the descriptor through an `Arc`, so it is closed when the
//!   last holder releases it and never while a thread may still be blocked on it. A
//!   thread that is not interrupted within the budget is detached and keeps the
//!   descriptor open until it returns. [`Teardown`] records which happened.
//!
//! Signal interruption of the actual accessory driver on the target phone is
//! unproven until it is tested on that descriptor.
use crate::transport::Protocol;
use aap_wire::READ_BUFFER;
use std::{
    collections::VecDeque,
    fmt, io,
    mem::take,
    os::{
        fd::{AsRawFd, OwnedFd, RawFd},
        unix::thread::JoinHandleExt,
    },
    ptr::null_mut,
    sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock, PoisonError},
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

/// Received bytes queued for the protocol thread before the reader stops reading.
pub const INBOX_LIMIT: usize = 4 * READ_BUFFER;
/// Largest chunk of pending output handed to the writer at once.
pub const WRITE_CHUNK: usize = READ_BUFFER;

#[derive(Debug, Clone)]
pub struct Options {
    /// From shutdown until I/O threads not yet exited are detached.
    pub stop_budget: Duration,
    /// Interval between repeated interrupt signals to a live I/O thread.
    pub signal_interval: Duration,
}
impl Default for Options {
    fn default() -> Self {
        Self {
            stop_budget: Duration::from_secs(2),
            signal_interval: Duration::from_millis(10),
        }
    }
}

/// Stop request for one [`run`]. Create one per attempt; clones share it. A request
/// made before `run` starts is honored as soon as it does.
#[derive(Clone, Default)]
pub struct Stop(Arc<Shared>);

impl Stop {
    pub fn new() -> Self {
        Self::default()
    }
    /// Idempotent and nonblocking; safe from any thread.
    pub fn request(&self) {
        lock(&self.0.state).stop_requested = true;
        self.0.events.notify_all();
    }
    /// Graceful stop, delivered once to the protocol as `on_shutdown_requested`; the run
    /// then ends when the protocol finishes. Nonblocking; safe from any thread.
    pub fn request_shutdown(&self) {
        lock(&self.0.state).shutdown_requested = true;
        self.0.events.notify_all();
    }
    /// Runs the protocol thread's loop once more (timers and queued media). Nonblocking.
    pub fn wake(&self) {
        lock(&self.0.state).woken = true;
        self.0.events.notify_all();
    }
    /// Whether an I/O thread started for this attempt has not yet returned, as after
    /// a detached teardown. Its descriptor is still open while this is true.
    pub fn io_threads_alive(&self) -> bool {
        let state = lock(&self.0.state);
        state.reader.alive() || state.writer.alive()
    }
}

#[derive(Default)]
struct Shared {
    state: Mutex<State>,
    /// Wakes the protocol thread and the teardown wait.
    events: Condvar,
    /// Wakes the reader (inbox space) and the writer (new chunk or shutdown).
    io: Condvar,
}

#[derive(Default, Clone, Copy)]
struct Life {
    spawned: bool,
    exited: bool,
}
impl Life {
    fn alive(self) -> bool {
        self.spawned && !self.exited
    }
}

#[derive(Default)]
struct State {
    stop_requested: bool,
    shutdown_requested: bool,
    shutdown_delivered: bool,
    woken: bool,
    shutdown: bool,
    inbox: VecDeque<Vec<u8>>,
    inbox_bytes: usize,
    /// Reader end: `Some(None)` for EOF, `Some(Some(error))` for a read error.
    read_closed: Option<Option<io::Error>>,
    chunk: Option<Vec<u8>>,
    writing: bool,
    written: usize,
    write_error: Option<io::Error>,
    reader: Life,
    writer: Life,
}
impl State {
    fn has_events(&self) -> bool {
        self.stop_requested
            || self.woken
            || (self.shutdown_requested && !self.shutdown_delivered)
            || !self.inbox.is_empty()
            || self.written > 0
            || self.write_error.is_some()
            || self.read_closed.is_some()
    }
}

fn lock(state: &Mutex<State>) -> MutexGuard<'_, State> {
    state.lock().unwrap_or_else(PoisonError::into_inner)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ThreadEnd {
    NotStarted,
    /// Exited and joined before `run` returned.
    Joined,
    /// Still running when the stop budget expired; left detached.
    Detached,
}
impl ThreadEnd {
    pub fn name(self) -> &'static str {
        match self {
            ThreadEnd::NotStarted => "not-started",
            ThreadEnd::Joined => "joined",
            ThreadEnd::Detached => "detached",
        }
    }
}

/// Transport-side evidence for the session report.
#[derive(Debug, Clone)]
pub struct Teardown {
    pub interrupt_signal: Option<i32>,
    pub reader: ThreadEnd,
    pub writer: ThreadEnd,
    pub signals_sent: u32,
    /// From shutdown until every started thread was joined or detached.
    pub stop_duration: Duration,
}
impl Teardown {
    /// Every started thread was joined, so the descriptor is closed.
    pub fn descriptor_closed(&self) -> bool {
        self.reader != ThreadEnd::Detached && self.writer != ThreadEnd::Detached
    }
}
impl fmt::Display for Teardown {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(f, "io=blocking-threads")?;
        match self.interrupt_signal {
            Some(signal) => writeln!(f, "io_interrupt_signal={signal}")?,
            None => writeln!(f, "io_interrupt_signal=-")?,
        }
        writeln!(f, "io_reader={}", self.reader.name())?;
        writeln!(f, "io_writer={}", self.writer.name())?;
        writeln!(f, "io_signals_sent={}", self.signals_sent)?;
        writeln!(f, "io_stop_ms={}", self.stop_duration.as_millis())?;
        writeln!(
            f,
            "io_descriptor={}",
            if self.descriptor_closed() {
                "closed"
            } else {
                "open-until-detached-thread-returns"
            }
        )
    }
}

extern "C" fn interrupt_handler(_: libc::c_int) {}

/// Installs, once per process, the no-op handler used to interrupt blocked I/O and
/// returns its signal: the first real-time signal the C library leaves available.
/// Fails rather than replace a handler someone else installed.
pub fn interrupt_signal() -> io::Result<i32> {
    static INSTALLED: OnceLock<Result<i32, String>> = OnceLock::new();
    INSTALLED
        .get_or_init(|| {
            let signal = libc::SIGRTMIN();
            // SAFETY: sigaction reads and writes only the zero-initialized records passed
            // here; the handler is async-signal-safe because it does nothing.
            unsafe {
                let mut previous: libc::sigaction = std::mem::zeroed();
                if libc::sigaction(signal, std::ptr::null(), &mut previous) != 0 {
                    return Err(format!(
                        "sigaction({signal}) query failed: {}",
                        io::Error::last_os_error()
                    ));
                }
                if previous.sa_sigaction != libc::SIG_DFL {
                    return Err(format!("signal {signal} already has a handler"));
                }
                let mut action: libc::sigaction = std::mem::zeroed();
                action.sa_sigaction = interrupt_handler as extern "C" fn(libc::c_int) as usize;
                libc::sigemptyset(&mut action.sa_mask);
                // No SA_RESTART: an interrupted read or write fails with EINTR.
                action.sa_flags = 0;
                if libc::sigaction(signal, &action, null_mut()) != 0 {
                    return Err(format!(
                        "sigaction({signal}) install failed: {}",
                        io::Error::last_os_error()
                    ));
                }
            }
            Ok(signal)
        })
        .clone()
        .map_err(io::Error::other)
}

/// Drive `protocol` over the owned blocking descriptor until it finishes, the
/// transport fails, or `stop` is requested. `progress` runs on this thread after each
/// batch of protocol updates, and may take events from the protocol. The descriptor is
/// closed before returning unless an I/O thread had to be detached (see [`Teardown`]).
pub fn run<P, F>(
    fd: OwnedFd,
    protocol: &mut P,
    stop: &Stop,
    options: &Options,
    mut progress: F,
) -> Teardown
where
    P: Protocol + ?Sized,
    F: FnMut(&mut P),
{
    let mut teardown = Teardown {
        interrupt_signal: None,
        reader: ThreadEnd::NotStarted,
        writer: ThreadEnd::NotStarted,
        signals_sent: 0,
        stop_duration: Duration::ZERO,
    };
    let signal = match interrupt_signal().and_then(|s| set_blocking(fd.as_raw_fd()).map(|()| s)) {
        Ok(signal) => signal,
        Err(e) => {
            protocol.on_transport_closed(Some(&e), Instant::now());
            progress(protocol);
            return teardown;
        }
    };
    teardown.interrupt_signal = Some(signal);
    let shared = stop.0.clone();
    let fd = Arc::new(fd);
    let mut reader = None;
    let mut writer = None;
    let spawned = spawn_io(&shared, &fd, signal, Role::Reader).and_then(|handle| {
        reader = Some(handle);
        spawn_io(&shared, &fd, signal, Role::Writer).map(|handle| writer = Some(handle))
    });
    drop(fd);
    match spawned {
        Err(e) => protocol.on_transport_closed(Some(&e), Instant::now()),
        Ok(()) => drive(&shared, protocol, &mut progress),
    }
    progress(protocol);
    stop_io(&shared, signal, options, reader, writer, &mut teardown);
    teardown
}

fn drive<P, F>(shared: &Shared, protocol: &mut P, progress: &mut F)
where
    P: Protocol + ?Sized,
    F: FnMut(&mut P),
{
    loop {
        let mut state = lock(&shared.state);
        if state.stop_requested {
            drop(state);
            return protocol.on_cancelled(Instant::now());
        }
        state.woken = false;
        let shutdown = state.shutdown_requested && !state.shutdown_delivered;
        state.shutdown_delivered |= shutdown;
        let inbox = take(&mut state.inbox);
        if state.inbox_bytes > 0 {
            state.inbox_bytes = 0;
            shared.io.notify_all();
        }
        let written = take(&mut state.written);
        let write_error = state.write_error.take();
        let read_closed = state.read_closed.take();
        drop(state);

        let now = Instant::now();
        if written > 0 {
            protocol.output_written(written, now);
        }
        for bytes in &inbox {
            protocol.receive(bytes, now);
        }
        if let Some(e) = write_error {
            return protocol.on_transport_closed(Some(&e), now);
        }
        if let Some(closed) = read_closed {
            return protocol.on_transport_closed(closed.as_ref(), now);
        }
        if shutdown {
            protocol.on_shutdown_requested(now);
        }
        protocol.on_timer(Instant::now());
        if protocol.finished() {
            return;
        }
        progress(protocol);

        let mut state = lock(&shared.state);
        let pending = protocol.pending_output();
        // Bytes the writer finished since the top of this iteration are credited to the
        // protocol only on the next one, so `pending` still begins with them; handing out
        // a chunk now would send them twice. `has_events` makes the next iteration
        // immediate instead.
        if !state.writing && state.written == 0 && !pending.is_empty() {
            state.chunk = Some(pending[..pending.len().min(WRITE_CHUNK)].to_vec());
            state.writing = true;
            shared.io.notify_all();
        }
        let deadline = protocol.next_deadline();
        while !state.has_events() {
            let Some(deadline) = deadline else {
                state = shared
                    .events
                    .wait(state)
                    .unwrap_or_else(PoisonError::into_inner);
                continue;
            };
            let now = Instant::now();
            if now >= deadline {
                break;
            }
            state = shared
                .events
                .wait_timeout(state, deadline - now)
                .unwrap_or_else(PoisonError::into_inner)
                .0;
        }
    }
}

fn stop_io(
    shared: &Shared,
    signal: i32,
    options: &Options,
    reader: Option<JoinHandle<()>>,
    writer: Option<JoinHandle<()>>,
    teardown: &mut Teardown,
) {
    let started = Instant::now();
    let deadline = started + options.stop_budget;
    let mut state = lock(&shared.state);
    state.shutdown = true;
    state.chunk = None;
    shared.io.notify_all();
    loop {
        let live = [
            (state.reader.alive(), &reader),
            (state.writer.alive(), &writer),
        ];
        if !live.iter().any(|(alive, _)| *alive) {
            break;
        }
        let now = Instant::now();
        if now >= deadline {
            break;
        }
        for (_, handle) in live.iter().filter(|(alive, _)| *alive) {
            if let Some(handle) = handle {
                // SAFETY: a thread marks itself exited under this lock before returning,
                // so while the lock is held and it is not marked, it is still running
                // and its pthread_t is valid.
                unsafe { libc::pthread_kill(handle.as_pthread_t(), signal) };
                teardown.signals_sent += 1;
            }
        }
        let wait = options.signal_interval.min(deadline - now);
        state = shared
            .events
            .wait_timeout(state, wait)
            .unwrap_or_else(PoisonError::into_inner)
            .0;
    }
    let (reader_exited, writer_exited) = (state.reader.exited, state.writer.exited);
    drop(state);
    teardown.reader = finish_thread(reader, reader_exited);
    teardown.writer = finish_thread(writer, writer_exited);
    teardown.stop_duration = started.elapsed();
}

fn finish_thread(handle: Option<JoinHandle<()>>, exited: bool) -> ThreadEnd {
    match handle {
        None => ThreadEnd::NotStarted,
        Some(handle) if exited => {
            // The thread has marked itself exited, so this join returns promptly. A
            // panic in it was already recorded as an exit.
            let _ = handle.join();
            ThreadEnd::Joined
        }
        Some(_) => ThreadEnd::Detached,
    }
}

#[derive(Clone, Copy)]
enum Role {
    Reader,
    Writer,
}

fn life(state: &mut State, role: Role) -> &mut Life {
    match role {
        Role::Reader => &mut state.reader,
        Role::Writer => &mut state.writer,
    }
}

fn spawn_io(
    shared: &Arc<Shared>,
    fd: &Arc<OwnedFd>,
    signal: i32,
    role: Role,
) -> io::Result<JoinHandle<()>> {
    let (thread_shared, fd) = (shared.clone(), fd.clone());
    let name = match role {
        Role::Reader => "aap-read",
        Role::Writer => "aap-write",
    };
    let handle = thread::Builder::new()
        .name(name.to_owned())
        .spawn(move || {
            let _exit = ExitGuard {
                shared: &thread_shared,
                role,
            };
            unblock(signal);
            match role {
                Role::Reader => read_loop(fd.as_raw_fd(), &thread_shared),
                Role::Writer => write_loop(fd.as_raw_fd(), &thread_shared),
            }
            // The captured `fd` is released after the exit mark, closing the descriptor
            // if this thread is its last holder.
        })?;
    life(&mut lock(&shared.state), role).spawned = true;
    Ok(handle)
}

/// Marks the thread exited under the lock, including when it unwinds.
struct ExitGuard<'a> {
    shared: &'a Shared,
    role: Role,
}
impl Drop for ExitGuard<'_> {
    fn drop(&mut self) {
        life(&mut lock(&self.shared.state), self.role).exited = true;
        self.shared.events.notify_all();
    }
}

fn unblock(signal: i32) {
    // SAFETY: the set is initialized by sigemptyset before use; pthread_sigmask only
    // changes this thread's mask. Threads inherit masks (ART blocks some signals), so
    // the interrupt signal is explicitly unblocked. Failure (EINVAL only) would leave
    // the thread uninterruptible, which teardown reports as a detached thread.
    unsafe {
        let mut set: libc::sigset_t = std::mem::zeroed();
        libc::sigemptyset(&mut set);
        libc::sigaddset(&mut set, signal);
        libc::pthread_sigmask(libc::SIG_UNBLOCK, &set, null_mut());
    }
}

fn read_loop(fd: RawFd, shared: &Shared) {
    let mut buffer = vec![0u8; READ_BUFFER];
    loop {
        let mut state = lock(&shared.state);
        while !state.shutdown && state.inbox_bytes >= INBOX_LIMIT {
            state = shared
                .io
                .wait(state)
                .unwrap_or_else(PoisonError::into_inner);
        }
        if state.shutdown {
            return;
        }
        drop(state);
        // SAFETY: `buffer` is valid for writes of its length, and the descriptor stays
        // open while this thread holds its `Arc`.
        let n = unsafe { libc::read(fd, buffer.as_mut_ptr().cast(), buffer.len()) };
        let error = (n < 0).then(io::Error::last_os_error);
        let mut state = lock(&shared.state);
        if state.shutdown {
            return;
        }
        match error {
            Some(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Some(e) => state.read_closed = Some(Some(e)),
            None if n == 0 => state.read_closed = Some(None),
            None => {
                let n = n as usize;
                state.inbox.push_back(buffer[..n].to_vec());
                state.inbox_bytes += n;
            }
        }
        shared.events.notify_all();
        if state.read_closed.is_some() {
            return;
        }
    }
}

fn write_loop(fd: RawFd, shared: &Shared) {
    loop {
        let mut state = lock(&shared.state);
        let chunk = loop {
            if state.shutdown {
                return;
            }
            if let Some(chunk) = state.chunk.take() {
                break chunk;
            }
            state = shared
                .io
                .wait(state)
                .unwrap_or_else(PoisonError::into_inner);
        };
        drop(state);
        let mut offset = 0;
        while offset < chunk.len() {
            let rest = &chunk[offset..];
            // SAFETY: `rest` is valid for reads of its length, and the descriptor stays
            // open while this thread holds its `Arc`.
            let n = unsafe { libc::write(fd, rest.as_ptr().cast(), rest.len()) };
            let error = (n < 0).then(io::Error::last_os_error);
            let mut state = lock(&shared.state);
            if state.shutdown {
                return;
            }
            match error {
                Some(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Some(e) => state.write_error = Some(e),
                None if n == 0 => state.write_error = Some(io::ErrorKind::WriteZero.into()),
                None => {
                    offset += n as usize;
                    state.written += n as usize;
                    state.writing = offset < chunk.len();
                }
            }
            shared.events.notify_all();
            if state.write_error.is_some() {
                return;
            }
        }
    }
}

/// Clears `O_NONBLOCK` so a descriptor handed over in nonblocking mode cannot make the
/// I/O threads spin. The accessory driver ignores the flag either way.
fn set_blocking(fd: RawFd) -> io::Result<()> {
    // SAFETY: fcntl with F_GETFL/F_SETFL only reads and updates descriptor status flags.
    unsafe {
        let flags = libc::fcntl(fd, libc::F_GETFL);
        if flags < 0 {
            return Err(io::Error::last_os_error());
        }
        if flags & libc::O_NONBLOCK != 0
            && libc::fcntl(fd, libc::F_SETFL, flags & !libc::O_NONBLOCK) < 0
        {
            return Err(io::Error::last_os_error());
        }
    }
    Ok(())
}
