//! Service discovery (spec §7.5): the request this source sends, and validation of the
//! head unit's response into a capability registry. Transport channel IDs, service
//! types, and logical display IDs are kept distinct, and nothing here hardcodes channel
//! numbers. Structural violations reject the response; problems confined to one service
//! are recorded as issues and make only that service unusable.
use aap_proto::{
    data::{
        AudioConfig, AvChannel, ChannelDescriptor, InputChannelConfig, SessionInfo, VideoConfig,
    },
    enums::navigation_type,
    messages::{ServiceDiscoveryRequest, ServiceDiscoveryResponse},
};
use std::{
    collections::BTreeSet,
    fmt::{self, Write as _},
};

/// Neutral identity sent in the request: no phone model, account, or user name.
pub const DEVICE_NAME: &str = "OpenAutoAndroid";
pub const DEVICE_BRAND: &str = "OpenAutoAndroid project";

const ISSUE_LIMIT: usize = 16;
const CHANNEL_LIMIT: usize = 64;
const TEXT_LIMIT: usize = 64;
/// Supported keycodes kept for input binding; more are reported as an issue.
pub const KEYCODE_LIMIT: usize = 64;
/// Sensor types kept per sensor channel.
const SENSOR_TYPE_LIMIT: usize = 64;
/// Largest touch or video dimension treated as plausible.
const DIMENSION_LIMIT: i32 = 16384;

/// MediaCodecType values (open-android-auto `MediaCodecTypeEnum.proto`). Older schemas
/// call AVChannel field 1 a stream type where VIDEO is also 3.
const CODEC_H264_BP: i32 = 3;
const VIDEO_CODECS: [i32; 4] = [3, 5, 6, 7];
/// `MEDIA_CODEC_AUDIO_PCM`: the only audio codec this source produces (P5.3). PCM needs
/// no encoder, which is what keeps the multi-stream refactor separate from `MediaCodec`.
const CODEC_AUDIO_PCM: i32 = 1;
/// `AudioType.MEDIA`, the stream the app's own playback belongs on.
const AUDIO_TYPE_MEDIA: i32 = 3;
/// Audio configurations at most, per channel; Ford SYNC 4.0 advertises one per channel.
const AUDIO_CONFIG_LIMIT: usize = 16;

pub fn request(session_uuid: &str) -> ServiceDiscoveryRequest {
    ServiceDiscoveryRequest {
        phone_icon_small: None,
        phone_icon_medium: None,
        phone_icon_large: None,
        device_name: Some(DEVICE_NAME.into()),
        device_brand: Some(DEVICE_BRAND.into()),
        session_info: Some(SessionInfo {
            session_uuid: Some(session_uuid.into()),
            unknown_2: None,
            unknown_3: None,
            unknown_4: None,
        }),
    }
}

fn resolution(value: i32) -> Option<(u32, u32)> {
    Some(match value {
        1 => (800, 480),
        2 => (1280, 720),
        3 => (1920, 1080),
        4 => (2560, 1440),
        5 => (3840, 2160),
        6 => (720, 1280),
        7 => (1080, 1920),
        8 => (1440, 2560),
        9 => (2160, 3840),
        _ => return None,
    })
}

fn frame_rate(value: i32) -> Option<u32> {
    match value {
        1 => Some(60),
        2 => Some(30),
        _ => None,
    }
}

/// Report-safe text from the head unit: control characters removed, length bounded.
fn clean(text: &str) -> String {
    let mut out: String = text
        .chars()
        .map(|c| if c.is_control() { '_' } else { c })
        .take(TEXT_LIMIT)
        .collect();
    if text.chars().count() > TEXT_LIMIT {
        out.push_str("...");
    }
    out
}

fn shown(value: &Option<String>) -> String {
    value.as_deref().map_or("-".into(), clean)
}

/// Unit-specific strings are reduced to their length.
fn redacted(value: &Option<String>) -> String {
    value.as_ref().map_or("-".into(), |v| {
        format!("redacted({} chars)", v.chars().count())
    })
}

/// A pixel rectangle inside a video frame.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rect {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
}

impl fmt::Display for Rect {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{},{}+{}x{}", self.x, self.y, self.width, self.height)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VideoChoice {
    pub channel: u8,
    pub display_id: u32,
    pub config_index: usize,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    /// Advertised `margin_width` and `margin_height` (absent counts as 0).
    pub margins: (u32, u32),
    /// Advertised `additional_config.display_insets` as top, bottom, left, right.
    pub insets: Option<[u32; 4]>,
    /// Where the head unit shows the frame: the insets when they agree with the margins,
    /// otherwise the margins split evenly (spec §8.3 maps touches into this rectangle).
    pub content: Rect,
}

impl VideoChoice {
    /// `key=value` detail of the frame geometry, for reports.
    pub fn geometry(&self) -> String {
        let insets = self.insets.map_or("-".into(), |[t, b, l, r]| {
            format!("top={t},bottom={b},left={l},right={r}")
        });
        format!(
            "margins={}x{} insets={insets} content={}",
            self.margins.0, self.margins.1, self.content
        )
    }
}

impl fmt::Display for VideoChoice {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "channel={} display={} config={} {}x{}@{}",
            self.channel, self.display_id, self.config_index, self.width, self.height, self.fps
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InputChoice {
    pub channel: u8,
    pub display_id: u32,
    pub touch: Option<(i32, i32)>,
    pub keycodes: usize,
    /// Advertised keycodes, at most [`KEYCODE_LIMIT`], for the binding request.
    pub supported_keycodes: Vec<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SensorChoice {
    pub channel: u8,
    /// Advertised sensor types, at most 64.
    pub types: Vec<i32>,
}

/// One advertised `AudioConfig` and where it sits in the channel's advertised order
/// (P5.3).
///
/// The index is kept because `AVChannelStartIndication.config` is an index into this
/// list, in a **different numbering space** from the `media_codec_type` of the setup
/// request: two small integers that mean different things.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AudioConfigChoice {
    pub index: usize,
    pub sample_rate: u32,
    pub bit_depth: u32,
    pub channel_count: u32,
}

impl AudioConfigChoice {
    /// Bytes per sample frame, so a buffer length can be checked against the format.
    pub fn frame_bytes(&self) -> usize {
        (self.bit_depth as usize / 8) * self.channel_count as usize
    }

    /// Whether the phone side can produce this configuration. The hard validation is the
    /// phone's, not the head unit's: sample rate ∈ {48000, 16000}, **bit depth 16 only**,
    /// channels ∈ {1, 2}. A head unit advertising anything else leaves the channel
    /// unusable rather than being guessed at.
    pub fn usable(&self) -> bool {
        matches!(self.sample_rate, 48_000 | 16_000)
            && self.bit_depth == 16
            && matches!(self.channel_count, 1 | 2)
    }
}

impl fmt::Display for AudioConfigChoice {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}/{}/{}",
            self.sample_rate, self.bit_depth, self.channel_count
        )
    }
}

/// An advertised audio AV channel (P5.3).
///
/// Ford SYNC 4.0 advertises four of them — two codecs × two audio types — and this source
/// takes at most one: `MEDIA_CODEC_AUDIO_PCM` carrying `MEDIA`, because PCM needs no
/// encoder at all.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AudioChoice {
    pub channel: u8,
    /// `MediaCodecType` from `AVChannel.stream_type`.
    pub codec: i32,
    /// `AudioType` from `AVChannel.audio_type`; absent is recorded as 0 (`NONE`).
    pub audio_type: i32,
    /// Every advertised configuration, in advertised order, at most
    /// [`AUDIO_CONFIG_LIMIT`].
    pub configs: Vec<AudioConfigChoice>,
    /// The first advertised configuration this source can produce, or `None` when the
    /// head unit advertises none it can.
    pub selected: Option<AudioConfigChoice>,
}

impl AudioChoice {
    /// Everything but the channel, for the channel-summary line that already names it.
    pub fn summary(&self) -> String {
        let advertised: Vec<String> = self.configs.iter().map(ToString::to_string).collect();
        let selected = self
            .selected
            .map_or("-".into(), |c| format!("{c} index={}", c.index));
        format!(
            "codec={} audio_type={} configs={} selected={selected}",
            self.codec,
            self.audio_type,
            if advertised.is_empty() {
                "-".into()
            } else {
                advertised.join(",")
            }
        )
    }
}

impl fmt::Display for AudioChoice {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "channel={} {}", self.channel, self.summary())
    }
}

/// The `media_info` channel, which carries now-playing to the head unit (P5.1).
///
/// `MediaInfoChannel` is an empty message, so the descriptor says nothing beyond which
/// transport channel the service is on: there is no configuration to validate and no
/// capability to negotiate. Ford SYNC 4.0 advertises it as channel 10 with an empty SDP
/// marker (report 617af5ce).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MediaInfoChoice {
    pub channel: u8,
}

impl fmt::Display for MediaInfoChoice {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "channel={}", self.channel)
    }
}

/// Turn image geometry a head unit asks for, when its navigation descriptor carries any.
///
/// The two dimensions are reported by name rather than as `WxH` on purpose. The pinned
/// schema declares `height` at tag 1 and `width` at tag 2, which is the opposite of every
/// other geometry in this protocol, and the same APK class is transcribed the other way
/// round (`width` at tag 1) in the reference tree's `NavigationChannelConfigData.proto`.
/// One of those two readings is wrong and nothing here can say which: Ford sends no image
/// options at all, so there is no measurement to settle it against. Naming the fields in
/// the report keeps a reader from taking an order this source cannot justify.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NavigationImage {
    /// Tag 1 of `NavigationImageOptions`, which the pinned schema calls the height.
    pub height: i32,
    /// Tag 2, which the pinned schema calls the width.
    pub width: i32,
    pub colour_depth_bits: i32,
}

impl fmt::Display for NavigationImage {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "height={} width={} depth={}",
            self.height, self.width, self.colour_depth_bits
        )?;
        // Square images make the tag order moot; anything else rests on it.
        if self.height != self.width {
            write!(f, " (field order unverified)")?;
        }
        Ok(())
    }
}

/// The `navigation` channel, which carries turn-by-turn to the head unit's own cluster
/// display rather than into the projected picture.
///
/// Unlike `media_info`, this descriptor is configuration and not just a marker: how often
/// the head unit will accept an update, which of the two presentations it wants, and —
/// when it wants images — how big they must be. Ford SYNC 4.0 advertises it on channel 11
/// with `minimum_interval_ms=400`, `nav_type=2` and **no image options at all** (fixture
/// `ford-sync4-discovery-response.hex`). The schema reads 2 as `NEXT_TURN_IMAGE`, so that
/// advertisement asks for images without saying what size they must be. That contradiction
/// is recorded, not resolved: `nav_type` keeps the integer the head unit sent, because a
/// schema name is a reading and this project has had four of them turn out wrong.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NavigationChoice {
    pub channel: u8,
    /// Shortest interval between updates the head unit will accept, in milliseconds.
    pub minimum_interval_ms: i32,
    /// The raw `NavigationType` value, not a name for it.
    pub nav_type: i32,
    pub image: Option<NavigationImage>,
}

impl NavigationChoice {
    /// What the pinned schema calls [`Self::nav_type`], for the report only. The enum is
    /// marked unverified upstream, so this is never what anything branches on.
    pub fn type_name(&self) -> &'static str {
        navigation_type::Enum::try_from(self.nav_type).map_or("unknown", |t| t.as_str_name())
    }
}

impl fmt::Display for NavigationChoice {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "channel={} min_interval_ms={} type={} ({})",
            self.channel,
            self.minimum_interval_ms,
            self.nav_type,
            self.type_name()
        )?;
        match &self.image {
            Some(i) => write!(f, " image={i}"),
            None => write!(f, " image=-"),
        }
    }
}

impl fmt::Display for InputChoice {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "channel={} display={} touch=",
            self.channel, self.display_id
        )?;
        match self.touch {
            Some((w, h)) => write!(f, "{w}x{h}")?,
            None => write!(f, "-")?,
        }
        write!(f, " keycodes={}", self.keycodes)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChannelSummary {
    pub id: u8,
    pub service: &'static str,
    pub detail: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Capabilities {
    pub head_unit: Vec<(&'static str, String)>,
    pub channels: Vec<ChannelSummary>,
    pub video: Option<VideoChoice>,
    /// Every usable H.264 main-display configuration, in advertised order; the
    /// projection may reselect among them after the setup response.
    pub video_candidates: Vec<VideoChoice>,
    pub input: Option<InputChoice>,
    /// Every advertised audio channel, in advertised order (P5.3).
    pub audio_channels: Vec<AudioChoice>,
    /// The audio channel this source would drive: the first PCM `MEDIA` one with a
    /// configuration the phone side can produce.
    pub audio: Option<AudioChoice>,
    /// The first advertised sensor channel, if any.
    pub sensor: Option<SensorChoice>,
    /// The first advertised `media_info` channel, if any.
    pub media_info: Option<MediaInfoChoice>,
    /// The first advertised `navigation` channel, if any (P6.1).
    pub navigation: Option<NavigationChoice>,
    pub issues: Vec<String>,
    pub issue_count: usize,
}

impl Capabilities {
    /// At least one usable H.264 configuration and an input channel for its display.
    pub fn usable(&self) -> bool {
        self.video.is_some() && self.input.is_some()
    }

    fn issue(&mut self, text: String) {
        self.issue_count += 1;
        if self.issues.len() < ISSUE_LIMIT {
            self.issues.push(text);
        }
    }

    pub fn from_response(
        response: &ServiceDiscoveryResponse,
        preference: VideoPreference,
    ) -> Result<Self, String> {
        let mut caps = Capabilities::default();
        let r = response;
        let info = r.headunit_info.as_ref();
        caps.head_unit = vec![
            ("hu_name", redacted(&r.head_unit_name)),
            ("hu_display_name", redacted(&r.display_name)),
            ("hu_manufacturer", shown(&r.headunit_manufacturer)),
            ("hu_model", shown(&r.headunit_model)),
            ("hu_sw_build", shown(&r.sw_build)),
            ("hu_sw_version", shown(&r.sw_version)),
            ("hu_car_model", shown(&r.car_model)),
            ("hu_car_year", shown(&r.car_year)),
            ("hu_car_serial", redacted(&r.car_serial)),
            (
                "hu_driver_position",
                r.driver_position.map_or("-".into(), |v| v.to_string()),
            ),
            (
                "hu_session_configuration",
                r.session_configuration.map_or("-".into(), |v| v.to_string()),
            ),
            (
                "hu_can_play_native_media_during_vr",
                r.can_play_native_media_during_vr
                    .map_or("-".into(), |v| v.to_string()),
            ),
            (
                "hu_probe_for_support",
                r.probe_for_support.map_or("-".into(), |v| v.to_string()),
            ),
            (
                "hu_info",
                info.map_or("-".into(), |i| {
                    format!(
                        "make={} model={} year={} vehicle_id={} hu_make={} hu_model={} hu_build={} hu_version={} vehicle_type={}",
                        shown(&i.make),
                        shown(&i.model),
                        shown(&i.year),
                        redacted(&i.vehicle_id),
                        shown(&i.head_unit_make),
                        shown(&i.head_unit_model),
                        shown(&i.head_unit_software_build),
                        shown(&i.head_unit_software_version),
                        i.vehicle_type.map_or("-".into(), |v| v.to_string()),
                    )
                }),
            ),
        ];

        if r.channels.len() > CHANNEL_LIMIT {
            return Err(format!(
                "{} channel descriptors exceed the limit of {CHANNEL_LIMIT}",
                r.channels.len()
            ));
        }
        let mut ids = BTreeSet::new();
        let mut videos: Vec<VideoChoice> = Vec::new();
        let mut audios: Vec<AudioChoice> = Vec::new();
        let mut inputs: Vec<(u8, &InputChannelConfig)> = Vec::new();
        for descriptor in &r.channels {
            let id = u8::try_from(descriptor.channel_id)
                .ok()
                .filter(|id| *id != 0)
                .ok_or_else(|| {
                    format!(
                        "channel id {} is not a service transport channel (1..=255)",
                        descriptor.channel_id
                    )
                })?;
            if !ids.insert(id) {
                return Err(format!("channel id {id} is advertised twice"));
            }
            let services = services(descriptor);
            if services.len() > 1 {
                return Err(format!(
                    "channel {id} advertises several services: {}",
                    services.join(",")
                ));
            }
            let (service, detail) = match services.first().copied() {
                None => ("unknown", "no service known to the pinned schema".into()),
                Some("av") => {
                    let av = descriptor.av_channel.as_ref().expect("service present");
                    caps.av(id, av, &mut videos, &mut audios)
                }
                Some("input") => {
                    let input = descriptor.input_channel.as_ref().expect("service present");
                    inputs.push((id, input));
                    ("input", caps.input_detail(id, input))
                }
                Some("bluetooth") => {
                    let bt = descriptor
                        .bluetooth_channel
                        .as_ref()
                        .expect("service present");
                    (
                        "bluetooth",
                        format!(
                            "address=redacted({} bytes) pairing_methods={:?}",
                            bt.adapter_address.len(),
                            bt.supported_pairing_methods
                        ),
                    )
                }
                Some("sensor") => {
                    let sensor = descriptor.sensor_channel.as_ref().expect("service present");
                    if caps.sensor.is_none() {
                        caps.sensor = Some(SensorChoice {
                            channel: id,
                            types: sensor
                                .sensors
                                .iter()
                                .map(|s| s.sensor_type)
                                .take(SENSOR_TYPE_LIMIT)
                                .collect(),
                        });
                    }
                    (
                        "sensor",
                        format!(
                            "types={:?}",
                            sensor
                                .sensors
                                .iter()
                                .map(|s| s.sensor_type)
                                .collect::<Vec<_>>()
                        ),
                    )
                }
                Some("media_info") => {
                    if caps.media_info.is_none() {
                        caps.media_info = Some(MediaInfoChoice { channel: id });
                    }
                    // `MediaInfoChannel` is empty, so there is nothing else to report.
                    ("media_info", "now-playing".into())
                }
                Some("navigation") => {
                    let nav = descriptor
                        .navigation_channel
                        .as_ref()
                        .expect("service present");
                    let choice = NavigationChoice {
                        channel: id,
                        minimum_interval_ms: nav.minimum_interval_ms,
                        nav_type: nav.r#type,
                        image: nav.image_options.map(|i| NavigationImage {
                            height: i.height,
                            width: i.width,
                            colour_depth_bits: i.colour_depth_bits,
                        }),
                    };
                    let detail = choice.to_string();
                    if caps.navigation.is_none() {
                        caps.navigation = Some(choice);
                    }
                    // The channel is already in the line; the rest of the descriptor is
                    // what distinguishes this one from the empty `media_info` marker.
                    (
                        "navigation",
                        detail
                            .split_once(' ')
                            .map_or(String::new(), |(_, rest)| rest.to_string()),
                    )
                }
                Some(other) => (other, String::new()),
            };
            caps.channels.push(ChannelSummary {
                id,
                service,
                detail,
            });
        }
        caps.channels.sort_by_key(|c| c.id);

        // At most one audio channel is driven, and it is chosen rather than searched for:
        // PCM carrying MEDIA, with a configuration the phone side can produce. P5.3
        // starts with one channel deliberately, so an unusable advertisement leaves the
        // audio integration off rather than falling back to a codec with an encoder.
        caps.audio = audios
            .iter()
            .find(|a| {
                a.codec == CODEC_AUDIO_PCM
                    && a.audio_type == AUDIO_TYPE_MEDIA
                    && a.selected.is_some()
            })
            .cloned();
        if caps.audio.is_none() && !audios.is_empty() {
            let advertised: Vec<String> = audios
                .iter()
                .map(|a| format!("{}:codec{}/type{}", a.channel, a.codec, a.audio_type))
                .collect();
            caps.issue(format!(
                "no advertised audio channel is PCM MEDIA with a producible configuration; \
                 advertised are {}",
                advertised.join(",")
            ));
        }
        caps.audio_channels = audios;
        caps.video = preferred(&videos, preference);
        caps.video_candidates = videos.clone();
        if caps.video.is_none() && !videos.is_empty() {
            let advertised: Vec<String> = videos
                .iter()
                .map(|v| format!("{}x{}", v.width, v.height))
                .collect();
            caps.issue(format!(
                "no advertised configuration has the pinned {preference} frame size; usable sizes are {}",
                advertised.join(",")
            ));
        }
        if let Some(video) = caps.video {
            for (id, input) in &inputs {
                let display = input.display_id.unwrap_or(0);
                if display != video.display_id {
                    continue;
                }
                let touch = input
                    .touch_screen_configs
                    .iter()
                    .map(|t| (t.width, t.height))
                    .find(|(w, h)| valid_dimension(*w) && valid_dimension(*h));
                if touch.is_some() || !input.supported_keycodes.is_empty() {
                    if input.supported_keycodes.len() > KEYCODE_LIMIT {
                        caps.issue(format!(
                            "input channel {id} advertises {} keycodes; only the first {KEYCODE_LIMIT} are bound",
                            input.supported_keycodes.len()
                        ));
                    }
                    caps.input = Some(InputChoice {
                        channel: *id,
                        display_id: display,
                        touch,
                        keycodes: input.supported_keycodes.len(),
                        supported_keycodes: input
                            .supported_keycodes
                            .iter()
                            .copied()
                            .take(KEYCODE_LIMIT)
                            .collect(),
                    });
                    break;
                }
            }
            if caps.input.is_none() {
                caps.issue(format!(
                    "no input channel with a valid touch screen or keycodes for display {}",
                    video.display_id
                ));
            }
        } else {
            caps.issue("no usable H.264 main-display video configuration".into());
        }
        let video_displays: BTreeSet<u32> = videos.iter().map(|v| v.display_id).collect();
        for (id, input) in &inputs {
            let display = input.display_id.unwrap_or(0);
            if !video_displays.contains(&display) {
                caps.issue(format!(
                    "input channel {id} maps to display {display}, which has no usable video"
                ));
            }
        }
        Ok(caps)
    }

    fn av(
        &mut self,
        id: u8,
        av: &AvChannel,
        videos: &mut Vec<VideoChoice>,
        audios: &mut Vec<AudioChoice>,
    ) -> (&'static str, String) {
        // Ford SYNC 4 (report c1265b54) omits the channel stream type on its main video
        // channel and names the codec in each VideoConfig instead; either marks video.
        let config_video = av
            .video_configs
            .iter()
            .any(|c| c.codec.is_some_and(|codec| VIDEO_CODECS.contains(&codec)));
        let stream = av.stream_type;
        if stream.is_none() && !config_video {
            self.issue(format!(
                "av channel {id} has no stream type and no video codec in its configurations"
            ));
            return ("av", "stream_type=-".into());
        }
        if let Some(stream) = stream.filter(|s| !VIDEO_CODECS.contains(s)) {
            // P5.3 closes the unknown this line used to leave open: the report counted
            // `audio_configs` without saying what was in them, so Ford's sample rate, bit
            // depth and channel count went unrecorded for four connections.
            let choice = audio_channel(id, stream, av);
            let detail = choice.summary();
            audios.push(choice);
            return ("audio", detail);
        }
        let display_id = av.display_id.unwrap_or(0);
        let display_type = av.display_type.unwrap_or(0);
        let mut configs = String::new();
        if av.video_configs.is_empty() {
            self.issue(format!("video channel {id} advertises no configurations"));
        }
        for (index, config) in av.video_configs.iter().enumerate() {
            let codec = config.codec.or(stream);
            let size = config.video_resolution.and_then(resolution);
            let fps = config.video_fps.and_then(frame_rate);
            if !configs.is_empty() {
                configs.push(',');
            }
            match (size, fps) {
                (Some((w, h)), Some(fps)) => {
                    let _ = write!(configs, "{w}x{h}@{fps}");
                    if codec != Some(CODEC_H264_BP) {
                        let _ = match codec {
                            Some(codec) => write!(configs, "/codec{codec}"),
                            None => write!(configs, "/codec-"),
                        };
                    } else if display_type == 0 {
                        match self.geometry(id, index, config, w, h) {
                            Some((margins, insets, content)) => videos.push(VideoChoice {
                                channel: id,
                                display_id,
                                config_index: index,
                                width: w,
                                height: h,
                                fps,
                                margins,
                                insets,
                                content,
                            }),
                            None => configs.push_str("/margins-invalid"),
                        }
                    }
                }
                _ => {
                    configs.push_str("invalid");
                    self.issue(format!(
                        "video channel {id} config {index} has resolution {:?} fps {:?}",
                        config.video_resolution, config.video_fps
                    ));
                }
            }
        }
        (
            "video",
            format!(
                "codec={} display={display_id} display_type={display_type} configs={}",
                stream.map_or("per-config".into(), |s| s.to_string()),
                if configs.is_empty() { "-" } else { &configs }
            ),
        )
    }

    /// Margins, insets, and the content rectangle of a `w`x`h` configuration, or `None`
    /// (with an issue) when the margins leave no picture.
    #[allow(clippy::type_complexity)]
    fn geometry(
        &mut self,
        id: u8,
        index: usize,
        config: &VideoConfig,
        w: u32,
        h: u32,
    ) -> Option<((u32, u32), Option<[u32; 4]>, Rect)> {
        let (mw, mh) = (
            config.margin_width.unwrap_or(0),
            config.margin_height.unwrap_or(0),
        );
        if mw >= w || mh >= h {
            self.issue(format!(
                "video channel {id} config {index} margins {mw}x{mh} leave no picture in {w}x{h}"
            ));
            return None;
        }
        let insets = config
            .additional_config
            .as_ref()
            .and_then(|a| a.display_insets.as_ref())
            .map(|i| {
                [
                    i.top.unwrap_or(0),
                    i.bottom.unwrap_or(0),
                    i.left.unwrap_or(0),
                    i.right.unwrap_or(0),
                ]
            });
        let centered = Rect {
            x: mw / 2,
            y: mh / 2,
            width: w - mw,
            height: h - mh,
        };
        let content = match insets {
            None => centered,
            Some([t, b, l, r])
                if u64::from(l) + u64::from(r) == u64::from(mw)
                    && u64::from(t) + u64::from(b) == u64::from(mh) =>
            {
                Rect {
                    x: l,
                    y: t,
                    width: w - mw,
                    height: h - mh,
                }
            }
            Some([t, b, l, r]) => {
                self.issue(format!(
                    "video channel {id} config {index} insets top={t} bottom={b} left={l} right={r} \
                     disagree with margins {mw}x{mh}; margins split evenly are used"
                ));
                centered
            }
        };
        Some(((mw, mh), insets, content))
    }

    fn input_detail(&mut self, id: u8, input: &InputChannelConfig) -> String {
        let mut touch = String::new();
        for (index, t) in input.touch_screen_configs.iter().enumerate() {
            if !touch.is_empty() {
                touch.push(',');
            }
            let _ = write!(touch, "{}x{}", t.width, t.height);
            if !valid_dimension(t.width) || !valid_dimension(t.height) {
                self.issue(format!(
                    "input channel {id} touch screen {index} has invalid size {}x{}",
                    t.width, t.height
                ));
            }
        }
        format!(
            "display={} touch={} touchpads={} keycodes={}",
            input.display_id.unwrap_or(0),
            if touch.is_empty() { "-" } else { &touch },
            input.touchpad_configs.len(),
            input.supported_keycodes.len()
        )
    }
}

/// Which advertised configuration this source takes.
///
/// A head unit can advertise a configuration it will not display. Ford SYNC 4.0 offers
/// 1920x1080@60, acknowledges every unit of it and holds projected video focus, and shows
/// nothing (reports e9326eea, 23f532aa, c6cb973c, under both layouts and both version
/// profiles), while the 800x480 it offers displays correctly. **Nothing on the wire
/// distinguishes those two cases**, so a source cannot detect it and fall back; the choice
/// is either conservative or made by an operator.
///
/// The default is [`VideoPreference::Largest`]. It was pinned to 800x480 for as long as
/// that was the only size Ford SYNC 4.0 would display; the cause turned out to be this
/// source answering `UPDATE_UI_CONFIG_REQUEST` with an empty body instead of the
/// configuration it received, and with that fixed 1920x1080 displays and its touches land
/// (report 709ffa9c). A pinned size is still the escape hatch for a head unit that
/// advertises a configuration it will not show.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum VideoPreference {
    /// Most content pixels, then most frame pixels, then the highest frame rate. A head
    /// unit scales the content rectangle onto a window whose size it never states, so the
    /// picture is the same size whichever configuration is taken and only its sharpness
    /// differs: Ford advertises the same 1.5125 content aspect at all three of its
    /// configurations, and its 1334x882 rectangle at 1080p carries 3.4x the pixels of the
    /// 726x480 one at 800x480.
    #[default]
    Largest,
    /// The reverse: fewest content pixels, then fewest frame pixels, then the lowest frame
    /// rate. What this source preferred through G5, and the safest choice on an unknown
    /// head unit.
    Smallest,
    /// Only configurations with exactly this frame size, largest-first among them. No
    /// video is usable when none is advertised, which is recorded as an issue rather than
    /// quietly falling back to a size the operator did not ask for.
    Pinned(u32, u32),
}

impl VideoPreference {
    pub fn name(self) -> String {
        match self {
            VideoPreference::Largest => "largest".into(),
            VideoPreference::Smallest => "smallest".into(),
            VideoPreference::Pinned(w, h) => format!("{w}x{h}"),
        }
    }

    /// `largest`, `smallest`, or a `WIDTHxHEIGHT` frame size within the dimension limit.
    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "largest" => Some(VideoPreference::Largest),
            "smallest" => Some(VideoPreference::Smallest),
            _ => {
                let (w, h) = name.split_once('x')?;
                let (w, h): (u32, u32) = (w.parse().ok()?, h.parse().ok()?);
                let limit = DIMENSION_LIMIT as u32;
                (w >= 1 && w <= limit && h >= 1 && h <= limit)
                    .then_some(VideoPreference::Pinned(w, h))
            }
        }
    }
}

impl fmt::Display for VideoPreference {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.name())
    }
}

/// The configuration `preference` selects, or `None` when none qualifies. Ties break on
/// the lowest channel and configuration index so the choice is deterministic.
pub fn preferred(videos: &[VideoChoice], preference: VideoPreference) -> Option<VideoChoice> {
    // Maximised in every arm, so `Smallest` negates its own terms rather than using a
    // separate minimising pass that would break ties the other way.
    let rank = |v: &VideoChoice| {
        let (content, frame, fps) = (
            i64::from(v.content.width) * i64::from(v.content.height),
            i64::from(v.width) * i64::from(v.height),
            i64::from(v.fps),
        );
        let (content, frame, fps) = match preference {
            VideoPreference::Smallest => (-content, -frame, -fps),
            VideoPreference::Largest | VideoPreference::Pinned(..) => (content, frame, fps),
        };
        (
            content,
            frame,
            fps,
            -i64::from(v.channel),
            -(v.config_index as i64),
        )
    };
    videos
        .iter()
        .filter(|v| match preference {
            VideoPreference::Pinned(w, h) => v.width == w && v.height == h,
            _ => true,
        })
        .copied()
        .max_by_key(rank)
}

fn valid_dimension(value: i32) -> bool {
    (1..=DIMENSION_LIMIT).contains(&value)
}

/// One advertised audio channel, with every configuration spelled out (P5.3).
///
/// `AudioConfig`'s three fields are all `required`, so a descriptor that decoded at all
/// has them; nothing here is defaulted.
fn audio_channel(id: u8, codec: i32, av: &AvChannel) -> AudioChoice {
    let configs: Vec<AudioConfigChoice> = av
        .audio_configs
        .iter()
        .take(AUDIO_CONFIG_LIMIT)
        .enumerate()
        .map(|(index, c): (usize, &AudioConfig)| AudioConfigChoice {
            index,
            sample_rate: c.sample_rate,
            bit_depth: c.bit_depth,
            channel_count: c.channel_count,
        })
        .collect();
    AudioChoice {
        channel: id,
        codec,
        audio_type: av.audio_type.unwrap_or(0),
        selected: configs.iter().copied().find(AudioConfigChoice::usable),
        configs,
    }
}

/// Names of the service fields set in a descriptor.
fn services(d: &ChannelDescriptor) -> Vec<&'static str> {
    [
        (d.sensor_channel.is_some(), "sensor"),
        (d.av_channel.is_some(), "av"),
        (d.input_channel.is_some(), "input"),
        (d.av_input_channel.is_some(), "av_input"),
        (d.bluetooth_channel.is_some(), "bluetooth"),
        (d.radio_channel.is_some(), "radio"),
        (d.navigation_channel.is_some(), "navigation"),
        (d.media_info_channel.is_some(), "media_info"),
        (d.phone_status_channel.is_some(), "phone_status"),
        (d.media_browser_channel.is_some(), "media_browser"),
        (d.vendor_extension_channel.is_some(), "vendor_extension"),
        (d.notification_channel.is_some(), "notification"),
        (d.wifi_channel.is_some(), "wifi"),
        (d.car_control_channel.is_some(), "car_control"),
        (d.car_local_media_channel.is_some(), "car_local_media"),
        (d.buffered_media_channel.is_some(), "buffered_media"),
        (d.car_intent_channel.is_some(), "car_intent"),
    ]
    .into_iter()
    .filter_map(|(set, name)| set.then_some(name))
    .collect()
}

impl fmt::Display for Capabilities {
    /// `key=value` report lines.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for (key, value) in &self.head_unit {
            writeln!(f, "{key}={value}")?;
        }
        writeln!(f, "discovery_channels={}", self.channels.len())?;
        for c in &self.channels {
            writeln!(
                f,
                "discovery_channel_{:03}={} {}",
                c.id, c.service, c.detail
            )?;
        }
        match &self.video {
            Some(v) => {
                writeln!(f, "discovery_usable_video={v}")?;
                writeln!(f, "discovery_usable_video_geometry={}", v.geometry())?;
            }
            None => writeln!(f, "discovery_usable_video=-")?,
        }
        match &self.input {
            Some(i) => {
                writeln!(f, "discovery_usable_input={i}")?;
                let codes: Vec<String> = i.supported_keycodes.iter().map(i32::to_string).collect();
                writeln!(
                    f,
                    "discovery_usable_input_keycodes={}",
                    if codes.is_empty() {
                        "-".into()
                    } else {
                        codes.join(",")
                    }
                )?;
            }
            None => writeln!(f, "discovery_usable_input=-")?,
        }
        match &self.media_info {
            Some(m) => writeln!(f, "discovery_media_info={m}")?,
            None => writeln!(f, "discovery_media_info=-")?,
        }
        // The navigation descriptor is configuration rather than a marker, and none of it
        // was read before P6.1: the update floor, which presentation the head unit wants,
        // and whether it says how big a turn image must be are all decisions this source
        // has to make against the head unit's own numbers.
        match &self.navigation {
            Some(n) => writeln!(f, "discovery_navigation={n}")?,
            None => writeln!(f, "discovery_navigation=-")?,
        }
        // Every advertised audio channel on its own line, then the one this source would
        // drive. Advertised is not usable: the phone side accepts only 16-bit PCM at
        // 48000 or 16000 Hz, so a head unit offering anything else is recorded here
        // rather than discovered at setup time (P5.3).
        writeln!(f, "discovery_audio_channels={}", self.audio_channels.len())?;
        for a in &self.audio_channels {
            writeln!(f, "discovery_audio_channel_{:03}={a}", a.channel)?;
        }
        match &self.audio {
            Some(a) => writeln!(f, "discovery_audio={a}")?,
            None => writeln!(f, "discovery_audio=-")?,
        }
        // Named on its own line, not only inside the channel dump, because it is one
        // half of what P5.2 compares: advertised against populated (spec §10 evidence).
        match &self.sensor {
            Some(s) => {
                let types: Vec<String> = s.types.iter().map(i32::to_string).collect();
                writeln!(
                    f,
                    "discovery_sensor=channel={} types={}",
                    s.channel,
                    if types.is_empty() {
                        "-".into()
                    } else {
                        types.join(",")
                    }
                )?
            }
            None => writeln!(f, "discovery_sensor=-")?,
        }
        writeln!(
            f,
            "discovery_capabilities={}",
            if self.usable() {
                "usable"
            } else {
                "insufficient"
            }
        )?;
        writeln!(f, "discovery_issue_count={}", self.issue_count)?;
        for (i, issue) in self.issues.iter().enumerate() {
            writeln!(f, "discovery_issue_{i}={issue}")?;
        }
        Ok(())
    }
}
