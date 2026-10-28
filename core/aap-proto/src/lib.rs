//! Protobuf types generated from the pinned open-android-auto subset, and [`decode`],
//! which checks required fields and wire types on the raw bytes before prost decodes
//! them. prost alone would turn a missing proto2 `required` field into its default value;
//! spec §7.5 forbids treating, for example, a missing AUTH_COMPLETE status as success.
//! Enum-typed fields stay raw `i32`: callers validate supported values explicitly.
pub mod schema;

#[allow(clippy::all, clippy::pedantic)]
pub mod oaa {
    pub mod proto {
        pub mod data {
            include!("generated/oaa.proto.data.rs");
        }
        pub mod enums {
            include!("generated/oaa.proto.enums.rs");
        }
        pub mod ids {
            include!("generated/oaa.proto.ids.rs");
        }
        pub mod messages {
            include!("generated/oaa.proto.messages.rs");
        }
    }
}

pub use oaa::proto::{data, enums, messages};
pub use schema::{validate, ValidationError};

/// Message IDs (first two bytes of a plaintext message): control-channel IDs at the top
/// level, service-channel IDs in per-service modules.
pub mod ids {
    pub const VERSION_REQUEST: u16 = 0x0001;
    pub const VERSION_RESPONSE: u16 = 0x0002;
    pub const SSL_HANDSHAKE: u16 = 0x0003;
    pub const AUTH_COMPLETE: u16 = 0x0004;
    pub const SERVICE_DISCOVERY_REQUEST: u16 = 0x0005;
    pub const SERVICE_DISCOVERY_RESPONSE: u16 = 0x0006;
    pub const CHANNEL_OPEN_REQUEST: u16 = 0x0007;
    pub const CHANNEL_OPEN_RESPONSE: u16 = 0x0008;
    pub const PING_REQUEST: u16 = 0x000b;
    pub const PING_RESPONSE: u16 = 0x000c;
    pub const SHUTDOWN_REQUEST: u16 = 0x000f;
    pub const SHUTDOWN_RESPONSE: u16 = 0x0010;
    pub const CHANNEL_CLOSE_NOTIFICATION: u16 = 0x0009;
    /// Audio focus, on the **control channel** rather than the audio AV channel (P5.3).
    /// Audio focus is independent of video focus, which is `av::VIDEO_FOCUS_REQUEST` on
    /// the video channel.
    ///
    /// **Two corrections to the vendored `AudioFocus*` schemas, both established against
    /// the vehicle and the Android Auto 17.6 dex** (docs/references.md). Neither can be
    /// fixed in the generated types, because the `.proto` files are vendored unmodified:
    ///
    /// * `AudioFocusResponse` field 2 is **`unsolicited`**, not `granted`. Ford SYNC 4.0
    ///   sends it false both when refusing and when granting, and Android Auto logs the
    ///   same value as `unsolicited`. Only the `required` state field says whether focus
    ///   was given. A source that reads field 2 as a grant discards real grants.
    /// * `AudioFocusRequest.audio_focus_type` is **required** in 17.6, where the vendored
    ///   copy makes it optional. Harmless here — this source always sets it.
    pub const AUDIO_FOCUS_REQUEST: u16 = 0x0012;
    pub const AUDIO_FOCUS_RESPONSE: u16 = 0x0013;
    /// Navigation focus, on the **control channel** rather than the `navigation` channel
    /// (P6.2), and the session's third focus type after video and audio.
    ///
    /// `NavigationFocusRequestMessage.proto` states the placement in as many words —
    /// "This is a CONTROL CHANNEL message (GAL service type 1, hzh.java), NOT a
    /// navigation channel message" — and the pinned `ControlMessageIdsEnum.proto` carries
    /// the same two values, so both halves of the generated `ids` enum agree with it.
    ///
    /// There is **no release value**: `NavigationFocusType` has only `NAV_FOCUS_NATIVE`
    /// (1) and `NAV_FOCUS_PROJECTED` (2), where audio focus has an explicit `RELEASE`.
    /// Handing focus back is therefore a request for `NAV_FOCUS_NATIVE` — the head unit's
    /// own navigation — which is what this source sends when it stops. That is a reading
    /// of the enum, not an observation; no head unit has answered one yet.
    pub const NAVIGATION_FOCUS_REQUEST: u16 = 0x000d;
    pub const NAVIGATION_FOCUS_RESPONSE: u16 = 0x000e;

    /// Message IDs on an AV (video) service channel. IDs are scoped to the channel type:
    /// 0x8001 is a start indication here and an input event on an input channel.
    pub mod av {
        pub const MEDIA_WITH_TIMESTAMP: u16 = 0x0000;
        pub const MEDIA: u16 = 0x0001;
        pub const SETUP_REQUEST: u16 = 0x8000;
        pub const START_INDICATION: u16 = 0x8001;
        pub const STOP_INDICATION: u16 = 0x8002;
        pub const SETUP_RESPONSE: u16 = 0x8003;
        pub const ACK_INDICATION: u16 = 0x8004;
        pub const VIDEO_FOCUS_REQUEST: u16 = 0x8007;
        pub const VIDEO_FOCUS_INDICATION: u16 = 0x8008;
        /// Head unit → phone: a new UI configuration for the running stream. Ford SYNC 4.0
        /// pushes one a second or two into projection whenever the selected configuration
        /// has an expanded variant, carrying the content rectangle it is switching to.
        /// `AVChannelMessageIdsEnum.proto` names it; no schema for the body is published.
        pub const UPDATE_UI_CONFIG_REQUEST: u16 = 0x8009;
        /// Phone → head unit, answering [`UPDATE_UI_CONFIG_REQUEST`]. Not in
        /// `AVChannelMessageIdsEnum.proto`, which jumps from 0x8009 to 0x800B: recovered
        /// from Android Auto 17.6, whose dex names `MEDIA_MESSAGE_UPDATE_UI_CONFIG_REPLY`
        /// and carries 0x800A as a literal twice, filling the only hole beside the request
        /// (docs/references.md). No schema for the body is published; an empty one is sent.
        pub const UPDATE_UI_CONFIG_REPLY: u16 = 0x800A;
        /// Head unit → phone on an **audio** channel: the head unit's mixer ran out of
        /// buffered samples. One per underflow event, only between the first audio packet
        /// and the stop indication, and never answered (P5.3). Modern Android Auto parses
        /// no payload for it while older schemas define `required int32 session_id = 1`,
        /// so nothing here decodes the body; its length is recorded instead.
        pub const AUDIO_UNDERFLOW: u16 = 0x800B;
        /// Head unit → phone, answering a phone-initiated `UPDATE_HU_UI_CONFIG_REQUEST`
        /// (0x8011, which the vendored enum calls `UI_CONFIG_REQUEST`). **Not** the answer
        /// to [`UPDATE_UI_CONFIG_REQUEST`]: sending it in that role made Ford SYNC 4.0
        /// answer with 0x00ff, an id the phone never references, and withhold the picture
        /// (reports 2d24d540, 8b0a178e, 2f5cadde). Unused; kept so the pairing is recorded.
        pub const UPDATE_HU_UI_CONFIG_RESPONSE: u16 = 0x8012;
    }

    /// Message IDs on an input service channel.
    pub mod input {
        pub const EVENT_INDICATION: u16 = 0x8001;
        pub const BINDING_REQUEST: u16 = 0x8002;
        pub const BINDING_RESPONSE: u16 = 0x8003;
    }

    /// Message IDs on a `media_info` service channel (P5.1).
    ///
    /// No id enum for this channel is published: unlike `av`, `input` and `sensor`, the
    /// vendored subset has no `MediaChannelMessageIdsEnum.proto`. The three values are
    /// stated in the headers of the vendored messages themselves, which name the wire id
    /// each was traced at, and they agree with the pinned OpenAuto Prodigy receiver's
    /// `MediaStatusMessageId` (`PLAYBACK_STATUS` 0x8001, `PLAYBACK_STATUS_EVENT` 0x8002,
    /// `PLAYBACK_METADATA` 0x8003). The channel is otherwise phone → head unit only.
    pub mod media {
        /// Phone → head unit: playback state, source app, and position.
        pub const PLAYBACK_STATUS: u16 = 0x8001;
        /// Head unit → phone: a cluster d-pad action on the now-playing display. Never
        /// answered; accepted so it is not counted as an unhandled message.
        pub const PLAYBACK_STATUS_EVENT: u16 = 0x8002;
        /// Phone → head unit: title, artist, album, and (unused here) album art.
        pub const PLAYBACK_METADATA: u16 = 0x8003;
    }

    /// Message IDs on a `navigation` service channel — the head unit's own instrument
    /// cluster, `NAVIGATION_STATUS` (GAL service type 10), not the projected surface
    /// (P6.2).
    ///
    /// No id enum for this channel is published either; the values are the handler trace
    /// in `InstrumentClusterMessages.proto`, which lists each id against the APK class it
    /// dispatches to. Only 0x8007 is marked wire-verified upstream (4 March 2026); the
    /// rest are APK-derived schema reading and nothing on this channel has ever been on
    /// this project's wire.
    ///
    /// Everything except the two cluster signals is phone → head unit.
    pub mod navigation {
        /// Head unit → phone: the cluster display is available. Empty message; this
        /// source waits for it rather than assuming the cluster is there.
        pub const CLUSTER_START: u16 = 0x8001;
        /// Head unit → phone: the cluster display is gone. Empty message.
        pub const CLUSTER_STOP: u16 = 0x8002;
        /// Phone → head unit: unavailable, active, inactive, or rerouting.
        pub const STATE: u16 = 0x8003;
        /// Phone → head unit, **deprecated**: the only path that ever carried turn-image
        /// bytes. Unused — this source sends semantic maneuvers only (P6, docs/navigation.md, "Instrument cluster turn-by-turn") — and
        /// kept so a capture showing it is recognisable.
        pub const TURN_EVENT: u16 = 0x8004;
        /// Phone → head unit, **deprecated**: flat maneuver and distance, for head units
        /// below `CarInfo` PDK 1.6. Unused; there is no way to read a PDK version from
        /// what Ford sends, so this is a fallback to try rather than one to assume.
        pub const LEGACY_TURN_EVENT: u16 = 0x8005;
        /// Phone → head unit: the modern semantic maneuver — steps, maneuver type, lanes,
        /// road names and destinations, with no image bytes anywhere.
        pub const NOTIFICATION: u16 = 0x8006;
        /// Phone → head unit: distance and time to the next maneuver, the destination
        /// distances, and the current road. The one message on this channel with upstream
        /// wire verification behind it.
        pub const NEXT_TURN_DISTANCE: u16 = 0x8007;
        /// Phone → head unit: EV range against the route. Not ours; kept for the same
        /// reason as the deprecated pair.
        pub const ENERGY_FORECAST: u16 = 0x8008;
    }

    /// Message IDs on a sensor service channel.
    pub mod sensor {
        pub const REQUEST: u16 = 0x8001;
        pub const START_RESPONSE: u16 = 0x8002;
        pub const EVENT_INDICATION: u16 = 0x8003;
        pub const ERROR: u16 = 0x8004;
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodeError {
    Invalid(ValidationError),
    Prost(String),
}

impl std::fmt::Display for DecodeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DecodeError::Invalid(e) => write!(f, "{e}"),
            DecodeError::Prost(e) => write!(f, "protobuf decode failed: {e}"),
        }
    }
}

impl std::error::Error for DecodeError {}

/// Validates `bytes` against the schema of `T`, then decodes it.
pub fn decode<T: prost::Message + prost::Name + Default>(bytes: &[u8]) -> Result<T, DecodeError> {
    validate(&T::full_name(), bytes).map_err(DecodeError::Invalid)?;
    T::decode(bytes).map_err(|e| DecodeError::Prost(e.to_string()))
}

/// A complete plaintext control message: big-endian ID followed by the protobuf.
pub fn encode_message(id: u16, message: &impl prost::Message) -> Vec<u8> {
    let mut out = Vec::with_capacity(2 + message.encoded_len());
    out.extend_from_slice(&id.to_be_bytes());
    message
        .encode(&mut out)
        .expect("a Vec grows to fit any encoded message");
    out
}
