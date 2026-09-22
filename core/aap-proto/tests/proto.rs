//! Required-field validation and decoding against hand-encoded protobuf bytes, written
//! from the vendored schemas rather than produced by the generated encoder.
use aap_proto::{
    data::ChannelDescriptor,
    decode, encode_message, ids,
    messages::{
        AuthCompleteIndication, AvChannelSetupResponse, AvMediaAckIndication, ChannelOpenRequest,
        ChannelOpenResponse, InputEventIndication, MediaPlaybackMetadata, MediaPlaybackStatus,
        MediaPlaybackStatusEvent, PingRequest, PlaybackState, ServiceDiscoveryResponse,
    },
    oaa::proto::ids::{av_channel_message, input_channel_message, sensor_channel_message},
    schema::{self, Kind, MESSAGES},
    validate, DecodeError,
};

fn hex(text: &str) -> Vec<u8> {
    text.split_whitespace()
        .map(|b| u8::from_str_radix(b, 16).unwrap())
        .collect()
}

fn invalid<T: prost::Message + prost::Name + Default + std::fmt::Debug>(bytes: &[u8]) -> String {
    match decode::<T>(bytes) {
        Err(DecodeError::Invalid(e)) => e.to_string(),
        other => panic!("expected a validation error, got {other:?}"),
    }
}

#[test]
fn spec_auth_complete_fixture_decodes_and_missing_status_is_rejected() {
    // protocol/fixtures/auth-complete.hex: frame header, message ID 0x0004, protobuf.
    let frame = hex(include_str!("../../../protocol/fixtures/auth-complete.hex"));
    assert_eq!(&frame[4..6], &[0x00, 0x04]);
    let auth: AuthCompleteIndication = decode(&frame[6..]).unwrap();
    assert_eq!(auth.status, 0);
    // prost alone would decode an empty message as status 0.
    assert_eq!(
        invalid::<AuthCompleteIndication>(&[]),
        "AuthCompleteIndication.status: required field missing"
    );
    let failed: AuthCompleteIndication = decode(&hex("08 fd ff ff ff ff ff ff ff ff 01")).unwrap();
    assert_eq!(failed.status, -3);
}

#[test]
fn hand_encoded_discovery_response_decodes() {
    // channels { channel_id: 3 av_channel { stream_type: 3 video_configs { video_resolution: 1
    // video_fps: 2 } } } head_unit_name: "HU"
    let bytes = hex("0a 0c 08 03 1a 08 08 03 22 04 08 01 10 02 12 02 48 55");
    let response: ServiceDiscoveryResponse = decode(&bytes).unwrap();
    assert_eq!(response.head_unit_name.as_deref(), Some("HU"));
    let channel = &response.channels[0];
    assert_eq!(channel.channel_id, 3);
    let av = channel.av_channel.as_ref().unwrap();
    assert_eq!(av.stream_type, Some(3));
    assert_eq!(av.video_configs[0].video_resolution, Some(1));
    assert_eq!(av.video_configs[0].video_fps, Some(2));
}

#[test]
fn missing_nested_required_fields_report_their_path() {
    // Second channel descriptor lacks channel_id.
    let bytes = hex("0a 02 08 03 0a 02 3a 00");
    assert_eq!(
        invalid::<ServiceDiscoveryResponse>(&bytes),
        "ServiceDiscoveryResponse.channels[1].channel_id: required field missing"
    );
    // channels { channel_id: 1 input_channel { touch_screen_configs { width: 800 } } }
    let bytes = hex("0a 09 08 01 22 05 12 03 08 a0 06");
    assert_eq!(
        invalid::<ServiceDiscoveryResponse>(&bytes),
        "ServiceDiscoveryResponse.channels[0].input_channel.touch_screen_configs[0].height: required field missing"
    );
    let ping = invalid::<PingRequest>(&hex("10 01"));
    assert_eq!(ping, "PingRequest.timestamp: required field missing");
}

#[test]
fn wire_type_and_framing_errors_are_rejected() {
    // channel_id encoded as length-delimited.
    let e = invalid::<ChannelDescriptor>(&hex("0a 01 03"));
    assert!(e.contains("channel_id: wire type 2"), "{e}");
    let e = invalid::<ChannelDescriptor>(&hex("08"));
    assert!(e.contains("truncated varint"), "{e}");
    let e = invalid::<ChannelDescriptor>(&hex("08 01 1a 05 08"));
    assert!(e.contains("length exceeds"), "{e}");
    let e = invalid::<ChannelDescriptor>(&hex("08 01 0b 0c"));
    assert!(e.contains("group"), "{e}");
    let e = invalid::<ChannelDescriptor>(&hex("00 01"));
    assert!(e.contains("invalid field number 0"), "{e}");
    let e = invalid::<ChannelDescriptor>(&hex("08 ff ff ff ff ff ff ff ff ff 7f"));
    assert!(e.contains("overflows"), "{e}");
}

#[test]
fn unknown_fields_are_skipped_and_packed_repeated_scalars_accepted() {
    // channel_id 2, unknown field 99 (varint), input_channel with keycodes packed [3, 4]
    // then one unpacked keycode 5.
    let bytes = hex("08 02 98 06 07 22 06 0a 02 03 04 08 05");
    let descriptor: ChannelDescriptor = decode(&bytes).unwrap();
    assert_eq!(
        descriptor.input_channel.unwrap().supported_keycodes,
        vec![3, 4, 5]
    );
}

#[test]
fn generated_table_is_sorted_and_closed() {
    assert!(MESSAGES.windows(2).all(|w| w[0].name < w[1].name));
    for message in MESSAGES {
        assert!(message.fields.windows(2).all(|w| w[0].number < w[1].number));
        for field in message.fields {
            if let Kind::Message(target) = field.kind {
                assert!(schema::schema(target).is_some(), "{target}");
            }
        }
    }
    assert!(validate("oaa.proto.messages.NotGenerated", &[]).is_err());
}

#[test]
fn encode_message_prefixes_the_big_endian_id() {
    let ping = PingRequest {
        timestamp: -2,
        bugreport_request: None,
        payload: None,
    };
    assert_eq!(
        encode_message(0x000b, &ping),
        hex("00 0b 08 fe ff ff ff ff ff ff ff ff 01")
    );
}

#[test]
fn spec_channel_open_bytes_and_required_fields() {
    // Spec §8.1: priority 0 (sint32, zigzag) and target channel 3 give 00 07 08 00 10 03;
    // a successful response is 00 08 08 00.
    let request = ChannelOpenRequest {
        priority: 0,
        channel_id: 3,
    };
    assert_eq!(
        encode_message(ids::CHANNEL_OPEN_REQUEST, &request),
        hex("00 07 08 00 10 03")
    );
    // sint32 zigzag: priority -1 is encoded as 1.
    let negative = ChannelOpenRequest {
        priority: -1,
        channel_id: 3,
    };
    assert_eq!(
        encode_message(ids::CHANNEL_OPEN_REQUEST, &negative),
        hex("00 07 08 01 10 03")
    );
    let response: ChannelOpenResponse = decode(&hex("08 00")).unwrap();
    assert_eq!(response.status, 0);
    assert_eq!(
        invalid::<ChannelOpenResponse>(&[]),
        "ChannelOpenResponse.status: required field missing"
    );
    assert_eq!(
        invalid::<AvChannelSetupResponse>(&hex("10 0a")),
        "AVChannelSetupResponse.media_status: required field missing"
    );
    assert_eq!(
        invalid::<AvMediaAckIndication>(&hex("10 01")),
        "AVMediaAckIndication.session_id: required field missing"
    );
    // Touch location x, y, and pointer_id are required inside an input event.
    // timestamp 5, touch_event { touch_location { x 1 y 2 } touch_action 0 }
    assert_eq!(
        invalid::<InputEventIndication>(&hex("08 05 1a 08 0a 04 08 01 10 02 18 00")),
        "InputEventIndication.touch_event.touch_location[0].pointer_id: required field missing"
    );
}

#[test]
fn service_channel_ids_match_the_generated_enums() {
    use av_channel_message::Enum as Av;
    use input_channel_message::Enum as Input;
    use sensor_channel_message::Enum as Sensor;
    for (constant, generated) in [
        (
            ids::av::MEDIA_WITH_TIMESTAMP,
            Av::AvMediaWithTimestampIndication as i32,
        ),
        (ids::av::MEDIA, Av::AvMediaIndication as i32),
        (ids::av::SETUP_REQUEST, Av::SetupRequest as i32),
        (ids::av::START_INDICATION, Av::StartIndication as i32),
        (ids::av::STOP_INDICATION, Av::StopIndication as i32),
        (ids::av::SETUP_RESPONSE, Av::SetupResponse as i32),
        (ids::av::ACK_INDICATION, Av::AvMediaAckIndication as i32),
        (ids::av::VIDEO_FOCUS_REQUEST, Av::VideoFocusRequest as i32),
        (
            ids::av::VIDEO_FOCUS_INDICATION,
            Av::VideoFocusIndication as i32,
        ),
        (
            ids::input::EVENT_INDICATION,
            Input::InputEventIndication as i32,
        ),
        (ids::input::BINDING_REQUEST, Input::BindingRequest as i32),
        (ids::input::BINDING_RESPONSE, Input::BindingResponse as i32),
        (ids::sensor::REQUEST, Sensor::SensorRequest as i32),
        (
            ids::sensor::START_RESPONSE,
            Sensor::SensorStartResponse as i32,
        ),
        (
            ids::sensor::EVENT_INDICATION,
            Sensor::SensorEventIndication as i32,
        ),
        (ids::sensor::ERROR, Sensor::SensorError as i32),
    ] {
        assert_eq!(i32::from(constant), generated);
    }
}

/// The media_info channel has no published id enum, so its three constants are checked
/// against the wire capture the vendored schema documents instead. The bytes are the
/// 1993-message capture quoted in `MediaPlaybackStatusMessage.proto`; decoding them is
/// what establishes that field 2 is a string rather than the int32 an earlier revision
/// of that schema had.
#[test]
fn media_playback_status_decodes_the_captured_message() {
    let bytes = hex("08 02 12 0d 59 6f 75 54 75 62 65 20 4d 75 73 69 63 18 50 20 00 28 00 30 00");
    let status: MediaPlaybackStatus = decode(&bytes).unwrap();
    assert_eq!(status.playback_state, Some(PlaybackState::Playing as i32));
    assert_eq!(status.source_app.as_deref(), Some("YouTube Music"));
    assert_eq!(status.position_seconds, Some(80));
    assert_eq!(
        (status.shuffle, status.repeat, status.repeat_one),
        (Some(false), Some(false), Some(false))
    );
    // Every field is optional, so an empty body is a valid (if useless) status.
    assert_eq!(
        decode::<MediaPlaybackStatus>(&[]).unwrap().playback_state,
        None
    );
    assert_eq!(
        (
            ids::media::PLAYBACK_STATUS,
            ids::media::PLAYBACK_STATUS_EVENT,
            ids::media::PLAYBACK_METADATA
        ),
        (0x8001, 0x8002, 0x8003)
    );
}

/// The one required field on the channel: an event without it is rejected rather than
/// decoded as the zero value, which would read as `MEDIA_EVENT_UNKNOWN`.
#[test]
fn media_playback_status_event_requires_its_type() {
    assert_eq!(
        invalid::<MediaPlaybackStatusEvent>(&[]),
        "MediaPlaybackStatusEvent.event_type: required field missing"
    );
    let event: MediaPlaybackStatusEvent = decode(&hex("08 05")).unwrap();
    assert_eq!(event.event_type, 5);
}

/// Album art is unbounded `bytes` on a 16 KiB-fragment transport, so the source omits it
/// (see `Session::send_media_metadata`). The field still has to survive a round trip, so
/// that decision stays a policy rather than a schema gap.
#[test]
fn media_playback_metadata_round_trips_without_album_art() {
    let metadata = MediaPlaybackMetadata {
        title: Some("Title".into()),
        artist: Some("Artist".into()),
        album: Some("Album".into()),
        ..Default::default()
    };
    let encoded = encode_message(ids::media::PLAYBACK_METADATA, &metadata);
    assert_eq!(&encoded[..2], &[0x80, 0x03]);
    let decoded: MediaPlaybackMetadata = decode(&encoded[2..]).unwrap();
    assert_eq!(decoded, metadata);
    assert_eq!(decoded.album_art, None);
}
