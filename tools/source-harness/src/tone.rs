//! The lab test tone (P5.3): app-owned PCM for the audio channel, paced on a wall clock
//! on its own thread, exactly as the app's audio source is.
//!
//! The signal is deliberately trivial to verify. The left channel is a 440 Hz sine and the
//! right channel an 880 Hz sine, both at a quarter of full scale; a mono configuration
//! carries the 440 Hz channel alone. A receiver that dumps the PCM it was sent can check
//! the fundamental and, on stereo, that the channels were not swapped — which is the
//! round-trip check the plan asks for, and the one thing the video path has no equivalent
//! of (references.md: no wire signal distinguishes a displayed frame from a discarded
//! one).
//!
//! Nothing here models a real player. On the phone the audio is whatever the app is
//! playing; this exists so the wire path can be exercised without one.
use aap_session::{
    media::{BufferFlags, MediaLink, Submit},
    projection::AudioStart,
};
use std::{
    f64::consts::TAU,
    fmt,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};

/// Left and right fundamentals, in hertz.
const LEFT_HZ: f64 = 440.0;
const RIGHT_HZ: f64 = 880.0;
/// Peak amplitude as a fraction of full scale. Well below clipping, so a head unit that
/// applies its own gain still has headroom.
const AMPLITUDE: f64 = 0.25;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Stats {
    pub buffers: u64,
    pub bytes: u64,
    pub not_accepted: u64,
    /// Buffers whose deadline had already passed when they were produced: the tone
    /// generator itself fell behind, as distinct from the head unit refusing them.
    pub late: u64,
    pub format: Option<String>,
}

impl fmt::Display for Stats {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(
            f,
            "tone_buffers={} tone_bytes={} tone_not_accepted={} tone_late={} tone_format={}",
            self.buffers,
            self.bytes,
            self.not_accepted,
            self.late,
            self.format.as_deref().unwrap_or("-")
        )
    }
}

/// Produces buffers until `stop` is set or the link closes.
pub fn run(link: Arc<MediaLink>, start: AudioStart, stop: Arc<AtomicBool>) -> Stats {
    let mut stats = Stats {
        format: Some(format!(
            "{}/{}/{} buffer={}",
            start.sample_rate, start.bit_depth, start.channel_count, start.buffer_bytes
        )),
        ..Stats::default()
    };
    let channels = start.channel_count.max(1) as usize;
    let bytes_per_frame = channels * 2;
    let frames = (start.buffer_bytes / bytes_per_frame).max(1);
    let rate = f64::from(start.sample_rate.max(1));
    let period = Duration::from_secs_f64(frames as f64 / rate);
    let began = Instant::now();
    let mut next = began;
    // The sample index carries across buffers, so successive buffers are phase-continuous
    // and the tone has no click at every boundary.
    let mut sample = 0u64;
    let mut buffer = vec![0u8; frames * bytes_per_frame];
    while !stop.load(Ordering::Relaxed) {
        let now = Instant::now();
        if now < next {
            std::thread::sleep(next - now);
        } else if now > next + period {
            stats.late += 1;
        }
        next += period;
        for frame in 0..frames {
            let t = (sample + frame as u64) as f64 / rate;
            for channel in 0..channels {
                let hz = if channel == 0 { LEFT_HZ } else { RIGHT_HZ };
                let value = (AMPLITUDE * (TAU * hz * t).sin() * f64::from(i16::MAX)) as i16;
                let at = (frame * channels + channel) * 2;
                buffer[at..at + 2].copy_from_slice(&value.to_le_bytes());
            }
        }
        sample += frames as u64;
        // Presentation time is the tone's own clock rather than the wall clock, so a
        // buffer delayed by scheduling still says when it belongs.
        let presentation_us =
            (sample - frames as u64) * 1_000_000 / start.sample_rate.max(1) as u64;
        let result = link.submit(BufferFlags::default(), presentation_us as i64, &buffer);
        stats.buffers += 1;
        stats.bytes += buffer.len() as u64;
        if result != Submit::Accepted {
            stats.not_accepted += 1;
            if result == Submit::Closed {
                break;
            }
        }
    }
    stats
}
