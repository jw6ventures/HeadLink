//! Encoder output on its way to the head unit (spec §8.2). [`MediaLink`] is shared by the
//! encoder thread, which submits codec buffers, and the session thread, which sends them
//! as flow control allows. Every buffer is checked and normalized to an Annex B byte
//! stream here, so the wire never carries a format the encoder happened to choose.
//!
//! Congestion policy: at most [`MediaLink::frame_limit`] access units wait for the
//! head unit's acknowledgement window. A frame that finds the queue full is dropped, and
//! so is every later frame until the next IDR, which is preceded by the latest codec
//! configuration; a keyframe request is raised once for the episode. Queued frames are
//! never discarded individually, so the stream the head unit decodes has no holes.
//!
//! P5.3 generalises the link over a [`Codec`]. A [`Codec::Pcm`] link carries linear audio
//! and bypasses the NAL parser entirely: every buffer is independently playable, so there
//! is no keyframe, no codec configuration, and no drop episode — a congested buffer is
//! simply dropped and counted. PCM also has a tighter size limit than video, because a
//! buffer that fits one outbound fragment never fragments and so can never be interleaved
//! with a fragmenting video keyframe on the wire.
use std::{
    collections::VecDeque,
    fmt,
    sync::{Arc, Mutex, MutexGuard, PoisonError},
};

/// Largest normalized access unit: an AV message also carries a two-byte ID and an
/// eight-byte timestamp, and must fit the 4 MiB AV assembly limit (spec §6).
pub const UNIT_LIMIT: usize = aap_wire::AV_LIMIT - 10;
/// Largest PCM buffer: one that still fits a single outbound fragment once the two-byte
/// ID and eight-byte timestamp are added (P5.3).
///
/// This is a deliberate policy limit, not a protocol one. Audio at the conventional
/// 2048 samples = 8192 bytes is well inside it, and holding every audio message to one
/// BULK frame means no audio message can ever be the *other* half of an interleaving:
/// a fragmenting video keyframe and a whole audio buffer cannot overlap on the wire.
pub const AUDIO_UNIT_LIMIT: usize = aap_wire::OUTBOUND_FRAGMENT - 10;
/// Default number of frames waiting for the acknowledgement window.
pub const DEFAULT_FRAME_LIMIT: usize = 4;
/// Default number of PCM buffers waiting for the window. Audio windows are typically
/// small — openauto and mikereidis both hardcode 1 — and a deep phone-side queue only
/// adds latency the head unit's own buffer already provides, so this is shallow.
pub const DEFAULT_AUDIO_LIMIT: usize = 4;

/// What a [`MediaLink`] carries, which decides how a submitted buffer is checked.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Codec {
    /// H.264, parsed into NAL units and normalized to Annex B.
    #[default]
    H264,
    /// Signed 16-bit little-endian linear PCM, passed through unparsed. Sample rate and
    /// channel count are the head unit's advertised configuration and are not repeated
    /// here: nothing in this module inspects the samples.
    Pcm,
}

impl Codec {
    /// Report prefix, so one counter set serves both streams without either borrowing the
    /// other's vocabulary.
    fn prefix(self) -> &'static str {
        match self {
            Codec::H264 => "video",
            Codec::Pcm => "audio",
        }
    }
}

const NAL_SLICE: u8 = 1;
const NAL_IDR: u8 = 5;
const NAL_SEI: u8 = 6;
const NAL_SPS: u8 = 7;
const NAL_PPS: u8 = 8;
const NAL_AUD: u8 = 9;
const NAL_END_SEQUENCE: u8 = 10;
const NAL_END_STREAM: u8 = 11;
const NAL_FILLER: u8 = 12;

/// How a submitted buffer delimited its NAL units.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NalFormat {
    AnnexB,
    /// Four-byte big-endian lengths, converted to start codes.
    LengthPrefixed,
}

/// Sequence parameter set fields reported as encoder evidence.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Sps {
    pub profile_idc: u8,
    pub constraint_flags: u8,
    pub level_idc: u8,
    /// Cropped picture size; `None` when the SPS uses features this parser skips.
    pub size: Option<(u32, u32)>,
}

impl fmt::Display for Sps {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "profile={} constraints=0x{:02x} level={} size=",
            self.profile_idc, self.constraint_flags, self.level_idc
        )?;
        match self.size {
            Some((w, h)) => write!(f, "{w}x{h}"),
            None => write!(f, "-"),
        }
    }
}

/// A buffer split into NAL units and re-emitted with four-byte start codes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Parsed {
    pub annex_b: Vec<u8>,
    pub format: NalFormat,
    /// Bit `n` is set when a NAL unit of type `n` is present.
    pub types: u32,
    pub nal_units: usize,
    pub sps: Option<Sps>,
}

impl Parsed {
    pub fn has(&self, nal_type: u8) -> bool {
        self.types & (1 << nal_type) != 0
    }
}

/// Splits `bytes` into H.264 NAL units. Annex B input must begin with a start code;
/// otherwise the buffer must be exactly covered by four-byte length prefixes.
pub fn parse(bytes: &[u8]) -> Result<Parsed, String> {
    if bytes.is_empty() {
        return Err("empty buffer".into());
    }
    let (units, format) = if starts_with_start_code(bytes) {
        (split_annex_b(bytes)?, NalFormat::AnnexB)
    } else {
        (split_length_prefixed(bytes)?, NalFormat::LengthPrefixed)
    };
    let mut annex_b = Vec::with_capacity(bytes.len() + 4 * units.len());
    let mut types = 0u32;
    let mut sps = None;
    for unit in &units {
        let header = unit[0];
        if header & 0x80 != 0 {
            return Err("NAL unit with forbidden_zero_bit set".into());
        }
        let nal_type = header & 0x1f;
        match nal_type {
            NAL_SLICE | NAL_IDR | NAL_SEI | NAL_SPS | NAL_PPS | NAL_AUD | NAL_END_SEQUENCE
            | NAL_END_STREAM | NAL_FILLER => {}
            other => return Err(format!("unsupported NAL unit type {other}")),
        }
        if nal_type == NAL_SPS && sps.is_none() {
            sps = Some(parse_sps(&unit[1..])?);
        }
        types |= 1 << nal_type;
        annex_b.extend_from_slice(&[0, 0, 0, 1]);
        annex_b.extend_from_slice(unit);
    }
    if types & (1 << NAL_SLICE) != 0 && types & (1 << NAL_IDR) != 0 {
        return Err("access unit mixes IDR and non-IDR slices".into());
    }
    Ok(Parsed {
        annex_b,
        format,
        types,
        nal_units: units.len(),
        sps,
    })
}

fn starts_with_start_code(bytes: &[u8]) -> bool {
    bytes.starts_with(&[0, 0, 1]) || bytes.starts_with(&[0, 0, 0, 1])
}

fn split_annex_b(bytes: &[u8]) -> Result<Vec<&[u8]>, String> {
    // Positions just after each three-byte start code.
    let mut starts = Vec::new();
    let mut i = 0;
    while i + 3 <= bytes.len() {
        if bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1 {
            starts.push(i + 3);
            i += 3;
        } else {
            i += 1;
        }
    }
    let mut units = Vec::with_capacity(starts.len());
    for (n, &start) in starts.iter().enumerate() {
        let end = starts.get(n + 1).map_or(bytes.len(), |next| next - 3);
        // Zero bytes before the next start code are its zero_byte or trailing zeros.
        let mut unit = &bytes[start..end];
        while let [rest @ .., 0] = unit {
            unit = rest;
        }
        if unit.is_empty() {
            return Err("empty NAL unit".into());
        }
        units.push(unit);
    }
    Ok(units)
}

fn split_length_prefixed(bytes: &[u8]) -> Result<Vec<&[u8]>, String> {
    let mut units = Vec::new();
    let mut at = 0;
    while at < bytes.len() {
        let Some(prefix) = bytes.get(at..at + 4) else {
            return Err("neither Annex B nor exactly length-prefixed NAL units".into());
        };
        let length = u32::from_be_bytes(prefix.try_into().expect("four bytes")) as usize;
        at += 4;
        if length == 0 || length > bytes.len() - at {
            return Err("neither Annex B nor exactly length-prefixed NAL units".into());
        }
        units.push(&bytes[at..at + length]);
        at += length;
    }
    Ok(units)
}

struct Bits {
    data: Vec<u8>,
    bit: usize,
}

impl Bits {
    /// RBSP bits with emulation prevention bytes removed.
    fn new(ebsp: &[u8]) -> Self {
        let mut data = Vec::with_capacity(ebsp.len());
        let mut zeros = 0;
        for &byte in ebsp {
            if zeros >= 2 && byte == 3 {
                zeros = 0;
                continue;
            }
            zeros = if byte == 0 { zeros + 1 } else { 0 };
            data.push(byte);
        }
        Self { data, bit: 0 }
    }

    fn u(&mut self, count: usize) -> Result<u32, String> {
        let mut value = 0u32;
        for _ in 0..count {
            let byte = self
                .data
                .get(self.bit / 8)
                .ok_or("truncated sequence parameter set")?;
            value = (value << 1) | u32::from((byte >> (7 - self.bit % 8)) & 1);
            self.bit += 1;
        }
        Ok(value)
    }

    fn ue(&mut self) -> Result<u32, String> {
        let mut zeros = 0;
        while self.u(1)? == 0 {
            zeros += 1;
            if zeros > 31 {
                return Err("invalid Exp-Golomb code in sequence parameter set".into());
            }
        }
        let rest = self.u(zeros)?;
        Ok(((1u64 << zeros) - 1 + u64::from(rest)) as u32)
    }

    fn se(&mut self) -> Result<i32, String> {
        let k = self.ue()?;
        Ok(if k % 2 == 1 {
            (k / 2 + 1) as i32
        } else {
            -((k / 2) as i32)
        })
    }
}

fn parse_sps(ebsp: &[u8]) -> Result<Sps, String> {
    let mut b = Bits::new(ebsp);
    let profile_idc = b.u(8)? as u8;
    let constraint_flags = b.u(8)? as u8;
    let level_idc = b.u(8)? as u8;
    let mut sps = Sps {
        profile_idc,
        constraint_flags,
        level_idc,
        size: None,
    };
    b.ue()?; // seq_parameter_set_id
    let mut chroma_format_idc = 1;
    let mut separate_colour_plane = false;
    if matches!(
        profile_idc,
        100 | 110 | 122 | 244 | 44 | 83 | 86 | 118 | 128 | 138 | 139 | 134 | 135
    ) {
        chroma_format_idc = b.ue()?;
        if chroma_format_idc == 3 {
            separate_colour_plane = b.u(1)? == 1;
        }
        b.ue()?; // bit_depth_luma_minus8
        b.ue()?; // bit_depth_chroma_minus8
        b.u(1)?; // qpprime_y_zero_transform_bypass_flag
        if b.u(1)? == 1 {
            // Scaling matrices are not parsed; the size is left unknown.
            return Ok(sps);
        }
    }
    b.ue()?; // log2_max_frame_num_minus4
    match b.ue()? {
        0 => {
            b.ue()?;
        }
        1 => {
            b.u(1)?;
            b.se()?;
            b.se()?;
            let cycle = b.ue()?;
            if cycle > 255 {
                return Err("invalid num_ref_frames_in_pic_order_cnt_cycle".into());
            }
            for _ in 0..cycle {
                b.se()?;
            }
        }
        2 => {}
        other => return Err(format!("invalid pic_order_cnt_type {other}")),
    }
    b.ue()?; // max_num_ref_frames
    b.u(1)?; // gaps_in_frame_num_value_allowed_flag
    let width_mbs = u64::from(b.ue()?) + 1;
    let height_map_units = u64::from(b.ue()?) + 1;
    let frame_mbs_only = u64::from(b.u(1)?);
    if frame_mbs_only == 0 {
        b.u(1)?; // mb_adaptive_frame_field_flag
    }
    b.u(1)?; // direct_8x8_inference_flag
    let (mut left, mut right, mut top, mut bottom) = (0u64, 0u64, 0u64, 0u64);
    if b.u(1)? == 1 {
        left = b.ue()?.into();
        right = b.ue()?.into();
        top = b.ue()?.into();
        bottom = b.ue()?.into();
    }
    let (sub_width, sub_height) = match (chroma_format_idc, separate_colour_plane) {
        (0, _) | (3, true) => (1, 1),
        (1, _) => (2, 2),
        (2, _) => (2, 1),
        _ => (1, 1),
    };
    let crop_x = if chroma_format_idc == 0 || separate_colour_plane {
        1
    } else {
        sub_width
    };
    let crop_y = if chroma_format_idc == 0 || separate_colour_plane {
        2 - frame_mbs_only
    } else {
        sub_height * (2 - frame_mbs_only)
    };
    let width = (width_mbs * 16).checked_sub(crop_x * (left + right));
    let height =
        ((2 - frame_mbs_only) * height_map_units * 16).checked_sub(crop_y * (top + bottom));
    sps.size = match (width, height) {
        (Some(w), Some(h)) if w > 0 && h > 0 && w <= 16384 && h <= 16384 => {
            Some((w as u32, h as u32))
        }
        _ => return Err("sequence parameter set crops away the picture".into()),
    };
    Ok(sps)
}

/// `MediaCodec.BufferInfo` flags that matter here.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct BufferFlags {
    pub codec_config: bool,
    pub key_frame: bool,
    pub partial_frame: bool,
}

impl BufferFlags {
    /// From `MediaCodec.BUFFER_FLAG_*`: KEY_FRAME 1, CODEC_CONFIG 2, PARTIAL_FRAME 8.
    pub fn from_android(flags: i32) -> Self {
        Self {
            key_frame: flags & 1 != 0,
            codec_config: flags & 2 != 0,
            partial_frame: flags & 8 != 0,
        }
    }
}

/// Result of [`MediaLink::submit`]. The numeric values are part of the JNI contract.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Submit {
    Accepted = 0,
    /// The queue was full; frames are dropped until the next IDR, and a keyframe is
    /// requested.
    DroppedCongested = 1,
    /// A non-IDR frame after a drop, focus loss, or stream start.
    DroppedAwaitingKeyframe = 2,
    /// Video is not started or the head unit's projected focus is lost. Codec
    /// configuration is still remembered.
    DroppedNotStreaming = 3,
    /// The buffer is not a supported H.264 unit; see the report's last invalid reason.
    Invalid = 4,
    /// The session has ended.
    Closed = 5,
}

impl Submit {
    pub fn name(self) -> &'static str {
        match self {
            Submit::Accepted => "accepted",
            Submit::DroppedCongested => "dropped-congested",
            Submit::DroppedAwaitingKeyframe => "dropped-awaiting-keyframe",
            Submit::DroppedNotStreaming => "dropped-not-streaming",
            Submit::Invalid => "invalid",
            Submit::Closed => "closed",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UnitKind {
    /// Codec configuration (SPS/PPS): sent as 0x0001 without a timestamp.
    Config,
    /// An access unit: sent as 0x0000 with a timestamp.
    Frame { keyframe: bool },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaUnit {
    pub kind: UnitKind,
    /// Microseconds, from the encoder's presentation time; 0 for configuration.
    pub timestamp_us: u64,
    /// Annex B bytes.
    pub bytes: Vec<u8>,
}

/// Submission evidence, counted on the encoder thread.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MediaCounters {
    /// Which link these came from; it selects the report prefix and which of the
    /// H.264-only counters are worth printing.
    pub codec: Codec,
    pub submitted: u64,
    pub accepted_configs: u64,
    pub accepted_keyframes: u64,
    pub accepted_frames: u64,
    pub accepted_bytes: u64,
    pub largest_unit: usize,
    pub dropped_congested: u64,
    pub dropped_awaiting_keyframe: u64,
    pub dropped_not_streaming: u64,
    pub invalid: u64,
    pub closed: u64,
    /// Drop episodes that started because the queue was full.
    pub congestion_events: u64,
    /// Configuration re-sent in front of an IDR after a drop, focus change, or start.
    pub configs_repeated: u64,
    /// IDRs that carried their own SPS and PPS.
    pub inline_parameter_sets: u64,
    pub length_prefixed_inputs: u64,
    /// The encoder's key-frame flag disagreed with the NAL units.
    pub key_flag_mismatches: u64,
    pub timestamp_regressions: u64,
    pub first_timestamp_us: Option<u64>,
    pub last_timestamp_us: Option<u64>,
    pub last_invalid: Option<String>,
    pub sps: Option<Sps>,
}

impl fmt::Display for MediaCounters {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fn opt<T: fmt::Display>(v: &Option<T>) -> String {
            v.as_ref().map_or("-".into(), T::to_string)
        }
        let p = self.codec.prefix();
        // A PCM link has no codec configuration, no keyframe and no sequence parameter
        // set, so those counters are left out rather than printed as zeroes that invite a
        // reader to conclude something about audio nothing ever measured.
        match self.codec {
            Codec::H264 => writeln!(
                f,
                "{p}_submitted={} {p}_accepted_configs={} {p}_accepted_keyframes={} \
                 {p}_accepted_frames={} {p}_accepted_bytes={} {p}_largest_unit={} \
                 {p}_dropped_congested={} {p}_dropped_awaiting_keyframe={} \
                 {p}_dropped_not_streaming={} {p}_invalid={} {p}_closed={} \
                 {p}_congestion_events={} {p}_configs_repeated={} {p}_inline_parameter_sets={} \
                 {p}_length_prefixed_inputs={} {p}_key_flag_mismatches={} \
                 {p}_timestamp_regressions={} {p}_first_timestamp_us={} {p}_last_timestamp_us={}",
                self.submitted,
                self.accepted_configs,
                self.accepted_keyframes,
                self.accepted_frames,
                self.accepted_bytes,
                self.largest_unit,
                self.dropped_congested,
                self.dropped_awaiting_keyframe,
                self.dropped_not_streaming,
                self.invalid,
                self.closed,
                self.congestion_events,
                self.configs_repeated,
                self.inline_parameter_sets,
                self.length_prefixed_inputs,
                self.key_flag_mismatches,
                self.timestamp_regressions,
                opt(&self.first_timestamp_us),
                opt(&self.last_timestamp_us),
            )?,
            Codec::Pcm => writeln!(
                f,
                "{p}_submitted={} {p}_accepted_buffers={} {p}_accepted_bytes={} \
                 {p}_largest_unit={} {p}_dropped_congested={} {p}_dropped_not_streaming={} \
                 {p}_invalid={} {p}_closed={} {p}_timestamp_regressions={} \
                 {p}_first_timestamp_us={} {p}_last_timestamp_us={}",
                self.submitted,
                self.accepted_frames,
                self.accepted_bytes,
                self.largest_unit,
                self.dropped_congested,
                self.dropped_not_streaming,
                self.invalid,
                self.closed,
                self.timestamp_regressions,
                opt(&self.first_timestamp_us),
                opt(&self.last_timestamp_us),
            )?,
        }
        if self.codec == Codec::H264 {
            writeln!(f, "{p}_sps={}", opt(&self.sps))?;
        }
        writeln!(
            f,
            "{p}_last_invalid={}",
            self.last_invalid.as_deref().unwrap_or("-")
        )
    }
}

pub type Waker = Arc<dyn Fn() + Send + Sync>;

struct LinkState {
    accepting: bool,
    closed: bool,
    need_keyframe: bool,
    need_config: bool,
    keyframe_request: bool,
    failure: Option<String>,
    queue: VecDeque<MediaUnit>,
    queued_frames: usize,
    latest_config: Option<Vec<u8>>,
    last_timestamp: Option<u64>,
    counters: MediaCounters,
}

/// Shared between the encoder and session threads; see the module documentation.
pub struct MediaLink {
    codec: Codec,
    frame_limit: usize,
    state: Mutex<LinkState>,
    waker: Mutex<Option<Waker>>,
}

impl fmt::Debug for MediaLink {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("MediaLink")
            .field("codec", &self.codec)
            .field("frame_limit", &self.frame_limit)
            .finish_non_exhaustive()
    }
}

impl Default for MediaLink {
    fn default() -> Self {
        Self::new(DEFAULT_FRAME_LIMIT)
    }
}

impl MediaLink {
    /// An H.264 link, the video path.
    pub fn new(frame_limit: usize) -> Self {
        Self::with_codec(Codec::H264, frame_limit)
    }

    /// A PCM link, the P5.3 audio path.
    pub fn audio(frame_limit: usize) -> Self {
        Self::with_codec(Codec::Pcm, frame_limit)
    }

    pub fn with_codec(codec: Codec, frame_limit: usize) -> Self {
        Self {
            codec,
            frame_limit: frame_limit.max(1),
            state: Mutex::new(LinkState {
                accepting: false,
                closed: false,
                // PCM never waits for anything: every buffer is playable on its own.
                need_keyframe: codec == Codec::H264,
                need_config: codec == Codec::H264,
                keyframe_request: false,
                failure: None,
                queue: VecDeque::new(),
                queued_frames: 0,
                latest_config: None,
                last_timestamp: None,
                counters: MediaCounters {
                    codec,
                    ..MediaCounters::default()
                },
            }),
            waker: Mutex::new(None),
        }
    }

    pub fn codec(&self) -> Codec {
        self.codec
    }

    pub fn frame_limit(&self) -> usize {
        self.frame_limit
    }

    fn lock(&self) -> MutexGuard<'_, LinkState> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Called after work arrives for the session thread; set by the transport owner.
    pub fn set_waker(&self, waker: Waker) {
        *self.waker.lock().unwrap_or_else(PoisonError::into_inner) = Some(waker);
    }

    fn wake(&self) {
        let waker = self
            .waker
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone();
        if let Some(waker) = waker {
            waker();
        }
    }

    pub fn counters(&self) -> MediaCounters {
        self.lock().counters.clone()
    }

    /// Encoder thread: copies, checks, and queues one encoder output buffer. Never blocks
    /// on the transport.
    pub fn submit(&self, flags: BufferFlags, presentation_us: i64, bytes: &[u8]) -> Submit {
        let result = self.submit_locked(flags, presentation_us, bytes);
        if result == Submit::Accepted {
            self.wake();
        }
        result
    }

    fn submit_locked(&self, flags: BufferFlags, presentation_us: i64, bytes: &[u8]) -> Submit {
        let mut s = self.lock();
        s.counters.submitted += 1;
        if s.closed {
            s.counters.closed += 1;
            return Submit::Closed;
        }
        if self.codec == Codec::Pcm {
            return self.submit_pcm(&mut s, flags, presentation_us, bytes);
        }
        let parsed = if flags.partial_frame {
            Err("partial frames are not supported".to_owned())
        } else if bytes.len() > UNIT_LIMIT {
            Err(format!("{} byte buffer exceeds {UNIT_LIMIT}", bytes.len()))
        } else {
            parse(bytes).and_then(|p| {
                if p.annex_b.len() > UNIT_LIMIT {
                    Err(format!(
                        "{} byte unit exceeds {UNIT_LIMIT}",
                        p.annex_b.len()
                    ))
                } else {
                    Ok(p)
                }
            })
        };
        let parsed = match parsed.and_then(|p| check_kind(flags, p)) {
            Ok(parsed) => parsed,
            Err(reason) => return invalid(&mut s, reason),
        };
        if parsed.format == NalFormat::LengthPrefixed {
            s.counters.length_prefixed_inputs += 1;
        }
        if let Some(sps) = parsed.sps {
            s.counters.sps = Some(sps);
        }
        if flags.codec_config {
            s.latest_config = Some(parsed.annex_b.clone());
            if !s.accepting {
                s.counters.dropped_not_streaming += 1;
                return Submit::DroppedNotStreaming;
            }
            push(&mut s, UnitKind::Config, 0, parsed.annex_b);
            s.need_config = false;
            return Submit::Accepted;
        }
        let keyframe = parsed.has(NAL_IDR);
        if flags.key_frame != keyframe {
            s.counters.key_flag_mismatches += 1;
        }
        if presentation_us < 0 {
            return invalid(
                &mut s,
                format!("negative presentation time {presentation_us}"),
            );
        }
        if !s.accepting {
            s.counters.dropped_not_streaming += 1;
            return Submit::DroppedNotStreaming;
        }
        if s.need_keyframe && !keyframe {
            s.counters.dropped_awaiting_keyframe += 1;
            return Submit::DroppedAwaitingKeyframe;
        }
        if s.queued_frames >= self.frame_limit {
            s.counters.dropped_congested += 1;
            if !s.need_keyframe {
                s.counters.congestion_events += 1;
                s.keyframe_request = true;
            }
            s.need_keyframe = true;
            s.need_config = true;
            return Submit::DroppedCongested;
        }
        if keyframe {
            let inline = parsed.has(NAL_SPS) && parsed.has(NAL_PPS);
            if inline {
                s.counters.inline_parameter_sets += 1;
            } else if s.need_config {
                let Some(config) = s.latest_config.clone() else {
                    return invalid(&mut s, "IDR before any codec configuration".into());
                };
                push(&mut s, UnitKind::Config, 0, config);
                s.counters.configs_repeated += 1;
            }
            s.need_keyframe = false;
            s.need_config = false;
        }
        let timestamp = presentation_us as u64;
        if s.last_timestamp.is_some_and(|last| timestamp < last) {
            s.counters.timestamp_regressions += 1;
        }
        s.last_timestamp = Some(timestamp);
        s.counters.first_timestamp_us.get_or_insert(timestamp);
        s.counters.last_timestamp_us = Some(timestamp);
        push(
            &mut s,
            UnitKind::Frame { keyframe },
            timestamp,
            parsed.annex_b,
        );
        Submit::Accepted
    }

    /// One PCM buffer (P5.3). No parser, no keyframe, no configuration: the checks are
    /// that the buffer is a whole number of 16-bit samples and small enough to travel as
    /// a single unfragmented frame.
    fn submit_pcm(
        &self,
        s: &mut LinkState,
        flags: BufferFlags,
        presentation_us: i64,
        bytes: &[u8],
    ) -> Submit {
        let reason = if flags.codec_config {
            Some("PCM has no codec configuration buffer".to_owned())
        } else if flags.partial_frame {
            Some("partial frames are not supported".to_owned())
        } else if bytes.is_empty() {
            Some("empty buffer".to_owned())
        } else if bytes.len() > AUDIO_UNIT_LIMIT {
            Some(format!(
                "{} byte buffer exceeds {AUDIO_UNIT_LIMIT}",
                bytes.len()
            ))
        } else if bytes.len() % 2 != 0 {
            Some(format!("{} bytes is not whole 16-bit samples", bytes.len()))
        } else if presentation_us < 0 {
            Some(format!("negative presentation time {presentation_us}"))
        } else {
            None
        };
        if let Some(reason) = reason {
            s.counters.invalid += 1;
            s.counters.last_invalid = Some(reason);
            return Submit::Invalid;
        }
        if !s.accepting {
            s.counters.dropped_not_streaming += 1;
            return Submit::DroppedNotStreaming;
        }
        // A congested audio queue drops the newest buffer and nothing else. There is no
        // recovery point to wait for, so unlike video there is no drop episode: the next
        // buffer is taken as soon as the window frees a slot.
        if s.queued_frames >= self.frame_limit {
            s.counters.dropped_congested += 1;
            return Submit::DroppedCongested;
        }
        let timestamp = presentation_us as u64;
        if s.last_timestamp.is_some_and(|last| timestamp < last) {
            s.counters.timestamp_regressions += 1;
        }
        s.last_timestamp = Some(timestamp);
        s.counters.first_timestamp_us.get_or_insert(timestamp);
        s.counters.last_timestamp_us = Some(timestamp);
        push(
            s,
            UnitKind::Frame { keyframe: false },
            timestamp,
            bytes.to_vec(),
        );
        Submit::Accepted
    }

    /// Encoder thread: the encoder failed; the session ends with `ENCODER_FAILED`.
    pub fn fail(&self, detail: &str) {
        {
            let mut s = self.lock();
            if s.closed || s.failure.is_some() {
                return;
            }
            let detail: String = detail.chars().take(512).collect();
            s.failure = Some(detail);
        }
        self.wake();
    }

    /// Session thread: the stream started, or its focus was regained. An H.264 stream
    /// restarts at an IDR preceded by configuration; a PCM stream simply resumes, because
    /// the next buffer is already a valid restart point.
    pub(crate) fn resume(&self) {
        let mut s = self.lock();
        if s.closed {
            return;
        }
        s.accepting = true;
        let h264 = self.codec == Codec::H264;
        s.need_keyframe = h264;
        s.need_config = h264;
    }

    /// Session thread: focus lost. Queued units are discarded — stale audio played late
    /// is worse than a gap, for the same reason a stale video frame is.
    pub(crate) fn pause(&self) {
        let mut s = self.lock();
        s.accepting = false;
        s.queue.clear();
        s.queued_frames = 0;
        let h264 = self.codec == Codec::H264;
        s.need_keyframe = h264;
        s.need_config = h264;
    }

    /// Session thread: the session ended; later submissions return `Closed`.
    pub(crate) fn close(&self) {
        let mut s = self.lock();
        s.closed = true;
        s.accepting = false;
        s.queue.clear();
        s.queued_frames = 0;
    }

    pub(crate) fn pop(&self) -> Option<MediaUnit> {
        let mut s = self.lock();
        let unit = s.queue.pop_front()?;
        if matches!(unit.kind, UnitKind::Frame { .. }) {
            s.queued_frames -= 1;
        }
        Some(unit)
    }

    pub(crate) fn take_keyframe_request(&self) -> bool {
        std::mem::take(&mut self.lock().keyframe_request)
    }

    pub(crate) fn take_failure(&self) -> Option<String> {
        self.lock().failure.take()
    }
}

fn check_kind(flags: BufferFlags, parsed: Parsed) -> Result<Parsed, String> {
    let slices = parsed.has(NAL_SLICE) || parsed.has(NAL_IDR);
    if flags.codec_config {
        if slices || !parsed.has(NAL_SPS) || !parsed.has(NAL_PPS) {
            return Err("codec configuration buffer without exactly SPS and PPS data".into());
        }
    } else if !slices {
        return Err("frame buffer without a slice".into());
    }
    if let Some(Sps {
        size: Some((w, h)), ..
    }) = parsed.sps
    {
        if w == 0 || h == 0 {
            return Err("sequence parameter set with an empty picture".into());
        }
    }
    Ok(parsed)
}

fn invalid(s: &mut LinkState, reason: String) -> Submit {
    s.counters.invalid += 1;
    s.counters.last_invalid = Some(reason);
    // The dropped unit may be referenced by later frames.
    if !s.need_keyframe {
        s.keyframe_request = true;
    }
    s.need_keyframe = true;
    s.need_config = true;
    Submit::Invalid
}

fn push(s: &mut LinkState, kind: UnitKind, timestamp_us: u64, bytes: Vec<u8>) {
    let c = &mut s.counters;
    match kind {
        UnitKind::Config => c.accepted_configs += 1,
        UnitKind::Frame { keyframe: true } => c.accepted_keyframes += 1,
        UnitKind::Frame { keyframe: false } => c.accepted_frames += 1,
    }
    c.accepted_bytes += bytes.len() as u64;
    c.largest_unit = c.largest_unit.max(bytes.len());
    if matches!(kind, UnitKind::Frame { .. }) {
        s.queued_frames += 1;
    }
    s.queue.push_back(MediaUnit {
        kind,
        timestamp_us,
        bytes,
    });
}
