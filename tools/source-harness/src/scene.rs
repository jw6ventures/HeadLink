//! The lab test scene (spec §8.2): project name band, a fresh session token, a frame
//! counter, elapsed seconds, four numbered buttons with their tap counts, and the last
//! touch position. It runs on its own thread, like the app's encoder, and submits to the
//! session through [`MediaLink`].
//!
//! The layout is the one the app's scene uses, so a receiver can check the same sample
//! points: a header of one fifth of the content height, then a 2x2 button grid inset by
//! `content width / 40`. Tapping a button changes its color, which is what the lab
//! receiver verifies in decoded frames.
use crate::tone;
use aap_session::{
    media::{BufferFlags, MediaLink, Submit},
    now_playing::{NowPlaying, NowPlayingLink, PlaybackState, TransportAction},
    projection::{AppEvent, VideoStart},
};
use std::{
    fmt,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{Receiver, RecvTimeoutError},
        Arc,
    },
    thread::JoinHandle,
    time::{Duration, Instant},
};
use test_pattern::{Canvas, Encoder, Yuv};

/// Where each element sits inside the content rectangle.
pub struct Layout {
    pub content: (u32, u32, u32, u32),
    pub header: u32,
    pub margin: u32,
    pub button: (u32, u32),
}

impl Layout {
    pub fn new(x: u32, y: u32, width: u32, height: u32) -> Self {
        let header = height / 5;
        let margin = (width / 40).max(2);
        let button = (
            (width.saturating_sub(3 * margin)) / 2,
            (height.saturating_sub(header + 3 * margin)) / 2,
        );
        Self {
            content: (x, y, width, height),
            header,
            margin,
            button,
        }
    }

    /// Top-left corner of button `index` (0..4) in frame coordinates.
    pub fn button_origin(&self, index: u32) -> (u32, u32) {
        let (x, y, _, _) = self.content;
        let (col, row) = (index % 2, index / 2);
        (
            x + self.margin + col * (self.button.0 + self.margin),
            y + self.header + self.margin + row * (self.button.1 + self.margin),
        )
    }

    pub fn button_center(&self, index: u32) -> (u32, u32) {
        let (bx, by) = self.button_origin(index);
        (bx + self.button.0 / 2, by + self.button.1 / 2)
    }

    /// Where a receiver samples a button's color: the middle of its lower-left quadrant,
    /// which the scene keeps free of text and of the touch crosshair.
    pub fn sample_point(&self, index: u32) -> (u32, u32) {
        let (bx, by) = self.button_origin(index);
        (bx + self.button.0 / 4, by + self.button.1 * 3 / 4)
    }

    /// The now-playing swatch, in the free band between the header and the first row of
    /// buttons. The lab canvas draws digits but no letters, so playback state is shown as
    /// a colour and the track as a number; a receiver checks the colour at
    /// [`Layout::transport_sample`] exactly as it checks a tapped button.
    pub fn transport_swatch(&self) -> (u32, u32, u32, u32) {
        let (x, y, w, _) = self.content;
        (x + self.margin, y + self.header, w / 8, self.margin)
    }

    pub fn transport_sample(&self) -> (u32, u32) {
        let (sx, sy, sw, sh) = self.transport_swatch();
        (sx + sw / 2, sy + sh / 2)
    }

    /// The button containing a point, if any.
    pub fn hit(&self, x: u32, y: u32) -> Option<u32> {
        (0..4).find(|&index| {
            let (bx, by) = self.button_origin(index);
            x >= bx && x < bx + self.button.0 && y >= by && y < by + self.button.1
        })
    }
}

const BACKGROUND: Yuv = Yuv(40, 128, 128);
const HEADER: Yuv = Yuv(70, 128, 128);
const TEXT: Yuv = Yuv(230, 128, 128);
const IDLE_BUTTON: Yuv = Yuv(110, 128, 128);

/// Distinct button colors, checked by the lab receiver in decoded frames.
pub fn button_color(index: u32) -> Yuv {
    match index {
        0 => Yuv::from_rgb(200, 40, 40),
        1 => Yuv::from_rgb(40, 180, 60),
        2 => Yuv::from_rgb(50, 90, 220),
        _ => Yuv::from_rgb(220, 190, 40),
    }
}

/// Playback-state colors for the now-playing swatch, checked the same way. Deliberately
/// far apart from each other and from [`button_color`], so a decoded sample is
/// unambiguous at the receiver's tolerance.
pub fn transport_color(state: PlaybackState) -> Yuv {
    match state {
        PlaybackState::Playing => Yuv::from_rgb(40, 200, 120),
        PlaybackState::Paused => Yuv::from_rgb(230, 160, 30),
        PlaybackState::Stopped => Yuv::from_rgb(90, 90, 90),
        PlaybackState::Unknown | PlaybackState::Error => Yuv::from_rgb(190, 40, 130),
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Stats {
    pub frames: u64,
    pub keyframes: u64,
    pub taps: [u32; 4],
    pub keys: u64,
    pub touches: u64,
    pub submitted: u64,
    pub not_accepted: u64,
    pub focus_changes: u64,
    pub restricted: Option<i32>,
    /// Vehicle position and motion events delivered to the app layer (P5.5). The
    /// harness scene has nowhere to draw them; the count is what says they arrived.
    pub vehicle: u64,
    /// Content rectangles the head unit pushed mid-stream.
    pub geometry_changes: u64,
    /// Transport buttons the head unit sent, and the action of the last one.
    pub transport: u64,
    pub last_transport: Option<&'static str>,
    /// The lab tone, once the head unit granted audio focus and the stream started
    /// (P5.3). Absent when audio was not asked for or never started.
    pub tone: Option<tone::Stats>,
}

impl fmt::Display for Stats {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(
            f,
            "scene_frames={} scene_keyframes={} scene_touches={} scene_keys={} \
             scene_taps={},{},{},{} scene_submitted={} scene_not_accepted={} \
             scene_focus_changes={} scene_driving_status={} scene_geometry_changes={} \
             scene_transport={} scene_last_transport={} scene_vehicle={}",
            self.frames,
            self.keyframes,
            self.touches,
            self.keys,
            self.taps[0],
            self.taps[1],
            self.taps[2],
            self.taps[3],
            self.submitted,
            self.not_accepted,
            self.focus_changes,
            self.restricted.map_or("-".into(), |s| s.to_string()),
            self.geometry_changes,
            self.transport,
            self.last_transport.unwrap_or("-"),
            self.vehicle
        )?;
        match &self.tone {
            Some(tone) => write!(f, "{tone}"),
            None => writeln!(f, "tone_buffers=- tone_format=-"),
        }
    }
}

/// A stand-in for the phone's media stack, for the lab only.
///
/// On Android the app reports another application's session and applies transport
/// buttons to it; there is no such thing here, so the harness keeps a fixed three-track
/// playlist and applies the buttons to that. It exists so `LAB_SCRIPT=transport` can
/// check the whole path — head-unit button in, `media_info` message out — against the
/// Prodigy receiver without a phone. **Nothing about it models a real player**, and the
/// app must not grow a copy of it: on the phone the state always comes from whatever is
/// actually playing.
struct LabPlayer {
    tracks: [(&'static str, &'static str, &'static str); 3],
    index: usize,
    state: PlaybackState,
    position: u32,
    started: Instant,
}

impl LabPlayer {
    fn new() -> Self {
        Self {
            tracks: [
                ("Lab Track One", "Lab Artist", "Lab Album"),
                ("Lab Track Two", "Lab Artist", "Lab Album"),
                ("Lab Track Three", "Other Artist", "Second Album"),
            ],
            index: 0,
            state: PlaybackState::Playing,
            position: 0,
            started: Instant::now(),
        }
    }

    /// The same edges a real player would apply, so the receiver sees a state machine
    /// rather than a fixed value.
    fn apply(&mut self, action: TransportAction) {
        match action {
            TransportAction::PlayPause => {
                self.state = match self.state {
                    PlaybackState::Playing => PlaybackState::Paused,
                    _ => PlaybackState::Playing,
                }
            }
            TransportAction::Play => self.state = PlaybackState::Playing,
            TransportAction::Pause => self.state = PlaybackState::Paused,
            TransportAction::Stop => {
                self.state = PlaybackState::Stopped;
                self.position = 0;
            }
            TransportAction::Next => {
                self.index = (self.index + 1) % self.tracks.len();
                self.restart();
            }
            TransportAction::Previous => {
                self.index = (self.index + self.tracks.len() - 1) % self.tracks.len();
                self.restart();
            }
            TransportAction::FastForward => self.seek(30),
            TransportAction::Rewind => self.seek(-30),
        }
    }

    fn restart(&mut self) {
        self.position = 0;
        self.started = Instant::now();
    }

    fn seek(&mut self, seconds: i32) {
        let position = i64::from(self.position) + i64::from(seconds);
        self.position = position.clamp(0, u32::MAX.into()) as u32;
        self.started = Instant::now();
    }

    /// What the head unit is told, with the position advanced while playing.
    fn now_playing(&self) -> NowPlaying {
        let elapsed = if self.state == PlaybackState::Playing {
            self.started.elapsed().as_secs() as u32
        } else {
            0
        };
        let (title, artist, album) = self.tracks[self.index];
        NowPlaying::new(
            self.state,
            self.position.saturating_add(elapsed),
            "org.openautoandroid.source (lab)",
            title,
            artist,
            album,
        )
    }

    /// What the scene shows, so an operator watching the head unit can tell whether the
    /// picture and the now-playing channel agree.
    fn label(&self) -> String {
        let (title, _, _) = self.tracks[self.index];
        format!("{} {title}", self.state.name())
    }
}

struct Scene {
    layout: Layout,
    canvas: Canvas,
    token: u32,
    taps: [u32; 4],
    last_touch: Option<(u32, u32)>,
    pressed: Option<u32>,
    frames: u64,
    started: Instant,
    /// Playback state and one-based track number of what the lab player is playing.
    transport: (PlaybackState, u32),
}

impl Scene {
    fn new(start: &VideoStart, token: u32) -> Self {
        let c = start.video.content;
        Self {
            layout: Layout::new(c.x, c.y, c.width, c.height),
            canvas: Canvas::new(start.video.width, start.video.height, Yuv(16, 128, 128)),
            token,
            taps: [0; 4],
            last_touch: None,
            pressed: None,
            frames: 0,
            started: Instant::now(),
            transport: (PlaybackState::Playing, 1),
        }
    }

    fn draw(&mut self) {
        let (x, y, w, h) = self.layout.content;
        let scale = (h / 60).max(1);
        self.canvas.fill(x, y, w, h, BACKGROUND);
        self.canvas.fill(x, y, w, self.layout.header, HEADER);
        // Header: session token, frame counter, elapsed seconds. A glyph is seven cells
        // tall, so the cell size keeps the digits inside the header.
        let head = (self.layout.header / 10).max(1);
        let line = y + self.layout.header / 6;
        self.canvas.number(
            x + self.layout.margin,
            line,
            head,
            u64::from(self.token),
            TEXT,
        );
        self.canvas.number(x + w / 2, line, head, self.frames, TEXT);
        self.canvas.number(
            x + w - 14 * head,
            line,
            head,
            self.started.elapsed().as_secs(),
            TEXT,
        );
        // A bar that moves every frame, so a still image cannot pass for live video.
        let bar = w / 16;
        let position = (self.frames as u32 * bar / 8) % w.max(1);
        self.canvas.fill(
            x + position.min(w.saturating_sub(bar)),
            y + self.layout.header - scale * 4,
            bar,
            scale * 4,
            TEXT,
        );
        // Now-playing: a state-colored swatch with the track number beside it, in the
        // band the buttons leave free. This is what an operator compares against the head
        // unit's own now-playing display for G6.
        let (sx, sy, sw, sh) = self.layout.transport_swatch();
        let (state, track) = self.transport;
        self.canvas.fill(sx, sy, sw, sh, transport_color(state));
        self.canvas.number(
            sx + sw + self.layout.margin,
            sy,
            (sh / 8).max(1),
            u64::from(track),
            TEXT,
        );
        for index in 0..4 {
            let (bx, by) = self.layout.button_origin(index);
            let (bw, bh) = self.layout.button;
            let color = if self.taps[index as usize] > 0 {
                button_color(index)
            } else {
                IDLE_BUTTON
            };
            self.canvas.fill(bx, by, bw, bh, color);
            if self.pressed == Some(index) {
                self.canvas.fill(bx, by, bw, scale * 2, TEXT);
            }
            // The button number and its tap count stay in the top fifth, so the rest of
            // the button is a clean color patch a receiver can sample (`sample_point`).
            let label = (bh / 20).max(1);
            self.canvas.number(
                bx + bw / 16,
                by + bh / 12,
                label,
                u64::from(index + 1),
                TEXT,
            );
            self.canvas.number(
                bx + bw / 2,
                by + bh / 12,
                label,
                u64::from(self.taps[index as usize]),
                TEXT,
            );
        }
        // Crosshair at the last touch, for calibration on the head unit.
        if let Some((tx, ty)) = self.last_touch {
            let arm = scale * 6;
            self.canvas
                .fill(tx.saturating_sub(arm), ty, arm * 2, scale, TEXT);
            self.canvas
                .fill(tx, ty.saturating_sub(arm), scale, arm * 2, TEXT);
        }
        self.frames += 1;
    }

    fn touch(&mut self, action: &str, x: u32, y: u32) {
        self.last_touch = Some((x, y));
        match action {
            "down" | "pointer-down" => self.pressed = self.layout.hit(x, y),
            "up" => {
                if let Some(index) = self.layout.hit(x, y).filter(|i| self.pressed == Some(*i)) {
                    self.taps[index as usize] += 1;
                }
                self.pressed = None;
            }
            "cancel" => self.pressed = None,
            _ => {}
        }
    }
}

/// Renders and encodes until the session stops. Returns what the scene showed.
///
/// `now_playing` is the same link the session publishes from. Supplying it makes this
/// harness stand in for the phone's media stack (see [`LabPlayer`]); the session itself
/// publishes nothing of its own.
pub fn run(
    link: Arc<MediaLink>,
    audio: Arc<MediaLink>,
    now_playing: Arc<NowPlayingLink>,
    events: Receiver<AppEvent>,
    token: u32,
    fps: u32,
    keyframe_interval: Duration,
) -> Stats {
    let mut stats = Stats::default();
    let mut player = LabPlayer::new();
    // The tone runs on its own thread with its own clock: audio is paced at ~42.7 ms a
    // buffer and must not be tied to the video frame rate (P5.3).
    let mut tone: Option<(JoinHandle<tone::Stats>, Arc<AtomicBool>)> = None;
    // Wait for the start event; the session may end first.
    let start = loop {
        match events.recv() {
            Ok(AppEvent::VideoStart(start)) => break start,
            Ok(_) => continue,
            Err(_) => return stats,
        }
    };
    let mut scene = Scene::new(&start, token);
    // The sample points a receiver checks in decoded frames.
    let centers: Vec<String> = (0..4)
        .map(|i| {
            let (x, y) = scene.layout.button_center(i);
            let (sx, sy) = scene.layout.sample_point(i);
            format!("{x}:{y}/{sx}:{sy}")
        })
        .collect();
    println!(
        "scene_layout=content={},{}+{}x{} header={} margin={} button={}x{} centers_samples={}",
        scene.layout.content.0,
        scene.layout.content.1,
        scene.layout.content.2,
        scene.layout.content.3,
        scene.layout.header,
        scene.layout.margin,
        scene.layout.button.0,
        scene.layout.button.1,
        centers.join(",")
    );
    let (tx, ty) = scene.layout.transport_sample();
    println!("scene_transport_sample={tx}:{ty}");
    // The head unit is told what is playing before the first frame, so a receiver that
    // opened the channel has something to show as soon as the picture appears.
    now_playing.publish(player.now_playing());
    let mut encoder = Encoder::new(start.video.width, start.video.height);
    let _ = link.submit(
        BufferFlags {
            codec_config: true,
            ..BufferFlags::default()
        },
        0,
        &encoder.parameter_sets(),
    );
    let frame_time = Duration::from_micros(1_000_000 / u64::from(fps.max(1)));
    let (mut focused, mut keyframe, mut stop) = (true, true, false);
    let (began, mut last_keyframe) = (Instant::now(), Instant::now());
    let mut next = Instant::now();
    while !stop {
        // Drain events up to the next frame deadline.
        loop {
            let now = Instant::now();
            let wait = next.saturating_duration_since(now);
            match events.recv_timeout(wait) {
                Ok(AppEvent::VideoStop { .. }) | Err(RecvTimeoutError::Disconnected) => {
                    stop = true;
                    break;
                }
                Ok(AppEvent::KeyframeRequest) => keyframe = true,
                // A congestion restart: this encoder has no codec to rebuild, so the restarted
                // stream opens with an IDR and the configuration the link already holds.
                Ok(AppEvent::VideoRestart { .. }) => keyframe = true,
                Ok(AppEvent::Focus { projected, .. }) => {
                    focused = projected;
                    stats.focus_changes += 1;
                }
                Ok(AppEvent::Touch(touch)) => {
                    stats.touches += 1;
                    if let Some(p) = touch.pointers.first() {
                        scene.touch(touch.action.name(), p.x, p.y);
                    }
                }
                Ok(AppEvent::Key { down, .. }) => {
                    stats.keys += 1;
                    // A head-unit button press taps the button after the last one tapped.
                    if down {
                        let index = usize::from(stats.keys as u8 % 4);
                        scene.taps[index] += 1;
                    }
                }
                Ok(AppEvent::Restriction { driving_status, .. }) => {
                    stats.restricted = driving_status;
                }
                // The harness draws the P4 test scene, which has no map and no header
                // to put a position in, so this is counted and dropped. Counting it is
                // still worth doing: it is what says the sensor channel reached the app
                // layer at all, which a silent drop would hide.
                Ok(AppEvent::Vehicle(_)) => {
                    stats.vehicle += 1;
                }
                // What the app does on the phone: apply the button to the player, then
                // publish the result. The head unit is told the new state because the
                // player reports it, never because a button was pressed.
                Ok(AppEvent::Transport { action, .. }) => {
                    stats.transport += 1;
                    stats.last_transport = Some(action.name());
                    player.apply(action);
                    now_playing.publish(player.now_playing());
                    println!(
                        "scene_transport={} player={}",
                        action.name(),
                        player.label()
                    );
                }
                Ok(AppEvent::VideoStart(_)) => {}
                // Audio focus was granted and the audio start indication is out.
                Ok(AppEvent::AudioStart(start)) => {
                    println!("scene_audio_start={start}");
                    if tone.is_none() {
                        let stopping = Arc::new(AtomicBool::new(false));
                        let (link, flag) = (audio.clone(), stopping.clone());
                        tone = Some((
                            std::thread::spawn(move || tone::run(link, start, flag)),
                            stopping,
                        ));
                    }
                }
                Ok(AppEvent::AudioStop { reason }) => {
                    println!("scene_audio_stop={reason}");
                    if let Some((handle, stopping)) = tone.take() {
                        stopping.store(true, Ordering::Relaxed);
                        stats.tone = handle.join().ok();
                    }
                }
                // The head unit moved the content rectangle inside the same frame; the
                // encoder keeps running and only the layout changes.
                Ok(AppEvent::VideoGeometry(geometry)) => {
                    let c = geometry.video.content;
                    scene.layout = Layout::new(c.x, c.y, c.width, c.height);
                    stats.geometry_changes += 1;
                }
                Err(RecvTimeoutError::Timeout) => break,
            }
        }
        if stop {
            break;
        }
        next = Instant::now() + frame_time;
        // Now-playing is independent of video focus: a head unit showing its own screen
        // still shows what the phone is playing. Republished every tick so the position
        // advances; the session's own rate limit decides what reaches the wire.
        scene.transport = (player.state, player.index as u32 + 1);
        now_playing.publish(player.now_playing());
        if !focused {
            continue;
        }
        if last_keyframe.elapsed() >= keyframe_interval {
            keyframe = true;
        }
        scene.draw();
        let (bytes, key) = encoder.encode(&scene.canvas, keyframe);
        keyframe = false;
        if key {
            last_keyframe = Instant::now();
            stats.keyframes += 1;
        }
        stats.frames += 1;
        stats.submitted += 1;
        let result = link.submit(
            BufferFlags {
                key_frame: key,
                ..BufferFlags::default()
            },
            began.elapsed().as_micros() as i64,
            &bytes,
        );
        if result != Submit::Accepted {
            stats.not_accepted += 1;
            if result == Submit::Closed {
                break;
            }
        }
        stats.taps = scene.taps;
    }
    stats.taps = scene.taps;
    if let Some((handle, stopping)) = tone.take() {
        stopping.store(true, Ordering::Relaxed);
        stats.tone = handle.join().ok();
    }
    stats
}
