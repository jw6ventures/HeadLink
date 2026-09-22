//! Sans-IO version-stage tests. Expected bytes come from the independently transcribed
//! spec §7.2 fixtures, never from the encoder under test.
use aap_session::{version::Profile, Category, Config, Session, Stage};
use std::{
    io,
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
/// Synthetic plaintext SSL_HANDSHAKE message; the five TLS-looking bytes are not parsed.
fn tls_message() -> Vec<u8> {
    hex("00 03 00 07 00 03 16 03 01 00 04")
}
fn category(s: &Session) -> Option<Category> {
    s.report().outcome.as_ref().map(|o| o.category)
}
fn write_all(s: &mut Session, chunk: usize, now: Instant) -> Vec<u8> {
    let mut out = Vec::new();
    while !s.pending_output().is_empty() {
        let n = chunk.min(s.pending_output().len());
        out.extend_from_slice(&s.pending_output()[..n]);
        s.output_written(n, now);
    }
    out
}

#[test]
fn fixture_request_byte_at_a_time_then_tls_ends_version_only_exchange() {
    let t0 = Instant::now();
    // Pinned to the legacy profile: the expected bytes are the spec §7.2 fixture's.
    let config = Config {
        profile: Profile::Legacy15,
        ..Config::default()
    };
    let mut s = Session::new(config.clone(), 7, t0);
    assert_eq!(s.next_deadline(), Some(t0 + config.version_wait));
    for (i, byte) in request().iter().enumerate() {
        assert!(s.pending_output().is_empty(), "response before byte {i}");
        s.receive(&[*byte], t0);
    }
    assert_eq!(s.pending_output(), response());
    // Decided but unwritten: still the version stage, and no ClientHello deadline yet.
    assert_eq!(s.report().stage, Stage::Version);
    assert_eq!(s.report().last_completed, None);
    assert_eq!(s.next_deadline(), Some(t0 + config.establishment_cap));

    let t1 = t0 + Duration::from_millis(3);
    assert_eq!(write_all(&mut s, 5, t1), response());
    assert_eq!(s.report().last_completed, Some(Stage::Version));
    assert_eq!(s.report().stage, Stage::Tls);
    assert_eq!(s.next_deadline(), Some(t1 + config.client_hello_wait));
    assert!(!s.finished());

    s.receive(&tls_message(), t1 + Duration::from_millis(1));
    let r = s.report();
    assert_eq!(category(&s), Some(Category::StageLimit));
    assert_eq!(r.outcome.as_ref().unwrap().stage, Stage::Tls);
    assert_eq!(r.tls_message_len, Some(5));
    assert!(!r.tls_before_response_written);
    assert_eq!(r.request_prefix, request()[4..]);
    assert_eq!(r.request_len, Some(6));
    assert_eq!(r.requested.unwrap().to_string(), "1.1");
    assert_eq!(r.response.unwrap().to_string(), "1.5");
    assert_eq!(
        (
            r.counters.bytes_in,
            r.counters.bytes_out,
            r.counters.frames_in
        ),
        (21, 12, 2)
    );
    assert!(s.finished());
    assert!(!s.wants_read());
    let text = r.to_string();
    for line in [
        "generation=7",
        "profile=legacy-1.5",
        "last_completed_stage=version",
        "version_request_hex=000100010001",
        "requested_version=1.1",
        "response_version=1.5",
        "final_category=STAGE_LIMIT",
        "final_stage=tls",
    ] {
        assert!(text.lines().any(|l| l == line), "missing {line} in\n{text}");
    }
}

#[test]
fn pipelined_tls_message_waits_for_response_flush_or_linger() {
    let t0 = Instant::now();
    let config = Config::default();
    let mut coalesced = request();
    coalesced.extend(tls_message());
    for flush in [true, false] {
        let mut s = Session::new(config.clone(), 1, t0);
        s.receive(&coalesced, t0);
        assert_eq!(category(&s), Some(Category::StageLimit));
        assert!(s.report().tls_before_response_written);
        assert!(!s.finished(), "decided response must still be flushed");
        assert!(!s.wants_read());
        assert_eq!(s.next_deadline(), Some(t0 + config.linger));
        if flush {
            assert_eq!(write_all(&mut s, 12, t0), response());
            assert_eq!(s.report().last_completed, Some(Stage::Version));
            assert_eq!(s.report().abandoned_output, 0);
        } else {
            s.on_timer(t0 + config.linger);
            assert_eq!(s.report().last_completed, None);
            assert_eq!(s.report().abandoned_output, 12);
        }
        assert!(s.finished());
        assert_eq!(s.next_deadline(), None);
    }
}

#[test]
fn legacy_profile_accepts_only_fixed_1_0_through_1_5() {
    let t0 = Instant::now();
    let legacy = Config {
        profile: Profile::Legacy15,
        ..Config::default()
    };
    for (body, expected) in [
        ("00 01 00 00", Ok("1.0")),
        ("00 01 00 05", Ok("1.5")),
        ("00 01 00 06", Err("1.6")),
        ("00 01 00 07", Err("1.7")),
        ("00 02 00 00", Err("2.0")),
        ("00 00 00 01", Err("0.1")),
        ("00 01 00 01 08 00", Err("1.1")),
        ("00 01 00", Err("-")),
    ] {
        let body = hex(body);
        let mut frame = vec![0, 3, 0, body.len() as u8 + 2, 0, 1];
        frame.extend(&body);
        let mut s = Session::new(legacy.clone(), 1, t0);
        s.receive(&frame, t0);
        let r = s.report();
        assert_eq!(r.request_len, Some(body.len() + 2));
        match expected {
            Ok(requested) => {
                assert_eq!(r.requested.unwrap().to_string(), requested);
                assert_eq!(s.pending_output(), response());
                assert_eq!(category(&s), None);
            }
            Err(requested) => {
                let shown = r.requested.map_or("-".into(), |v| v.to_string());
                assert_eq!(shown, requested);
                assert_eq!(category(&s), Some(Category::VersionUnsupported));
                assert!(
                    s.pending_output().is_empty(),
                    "no mismatch response is sent"
                );
                assert_eq!(r.response, None);
                assert!(s.finished());
            }
        }
    }
    assert_eq!(Profile::from_name("legacy-1.5"), Some(Profile::Legacy15));
    assert_eq!(Profile::from_name("1.7"), None);
}

#[test]
fn any_minor_profile_answers_every_fixed_major_1_request_with_1_5() {
    let t0 = Instant::now();
    let config = Config {
        profile: Profile::Legacy15AnyMinor,
        ..Config::default()
    };
    for (body, answered) in [
        ("00 01 00 01", true),
        ("00 01 00 06", true),
        ("00 01 00 07", true),
        ("00 01 ff ff", true),
        ("00 02 00 00", false),
        ("00 01 00 07 08 00", false),
        ("00 01 00", false),
    ] {
        let body = hex(body);
        let mut frame = vec![0, 3, 0, body.len() as u8 + 2, 0, 1];
        frame.extend(&body);
        let mut s = Session::new(config.clone(), 1, t0);
        s.receive(&frame, t0);
        if answered {
            assert_eq!(s.pending_output(), response(), "{body:02x?}");
            assert_eq!(category(&s), None);
        } else {
            assert_eq!(
                category(&s),
                Some(Category::VersionUnsupported),
                "{body:02x?}"
            );
            assert!(s.pending_output().is_empty());
        }
    }
    let s = Session::new(config, 1, t0);
    assert!(s
        .report()
        .to_string()
        .lines()
        .any(|l| l == "profile=legacy-1.5-any-minor"));
}

/// Google's Android Auto 17.6 answered Ford's 1.6 request with 1.7 and Ford accepted it
/// (logcat 2026-09-18 12:01:47), so this profile mirrors that answer exactly.
#[test]
fn match_1_7_answers_the_wire_config_gate_at_1_7_as_android_auto_does() {
    let t0 = Instant::now();
    let config = Config {
        profile: Profile::Match17,
        ..Config::default()
    };
    for (body, answer) in [
        ("00 01 00 01", Some(5)),
        ("00 01 00 05", Some(5)),
        ("00 01 00 06", Some(7)),
        ("00 01 00 07", Some(7)),
        ("00 01 ff ff", Some(7)),
        ("00 02 00 06", None),
        ("00 01 00 06 08 00", None),
    ] {
        let body = hex(body);
        let mut frame = vec![0, 3, 0, body.len() as u8 + 2, 0, 1];
        frame.extend(&body);
        let mut s = Session::new(config.clone(), 1, t0);
        s.receive(&frame, t0);
        match answer {
            Some(minor) => {
                assert_eq!(
                    s.pending_output(),
                    hex(&format!("00 03 00 08 00 02 00 01 00 0{minor} 00 00")),
                    "{body:02x?}"
                );
                assert_eq!(category(&s), None, "{body:02x?}");
            }
            None => {
                assert_eq!(
                    category(&s),
                    Some(Category::VersionUnsupported),
                    "{body:02x?}"
                );
                assert!(s.pending_output().is_empty());
            }
        }
    }
    let s = Session::new(config, 1, t0);
    assert!(s
        .report()
        .to_string()
        .lines()
        .any(|l| l == "profile=match-1.7"));
}

#[test]
fn match_1_6_answers_the_wire_config_gate_at_1_6_and_below_it_at_1_5() {
    let t0 = Instant::now();
    let config = Config {
        profile: Profile::Match16,
        ..Config::default()
    };
    // Ford SYNC 4.0 requests 1.6 (report 617af5ce); the legacy profiles answer 1.5 and
    // this one answers 1.6, with no WireConfig extension either way.
    for (body, answer) in [
        ("00 01 00 01", Some(5)),
        ("00 01 00 05", Some(5)),
        ("00 01 00 06", Some(6)),
        ("00 01 00 07", Some(6)),
        ("00 02 00 06", None),
        ("00 01 00 06 08 00", None),
    ] {
        let body = hex(body);
        let mut frame = vec![0, 3, 0, body.len() as u8 + 2, 0, 1];
        frame.extend(&body);
        let mut s = Session::new(config.clone(), 1, t0);
        s.receive(&frame, t0);
        match answer {
            Some(minor) => {
                // MATCH at the answered version, and nothing after it: an empty WireConfig
                // is no bytes, so the response is the same 12 as the spec §7.2 fixture.
                assert_eq!(
                    s.pending_output(),
                    hex(&format!("00 03 00 08 00 02 00 01 00 0{minor} 00 00")),
                    "{body:02x?}"
                );
                assert_eq!(category(&s), None, "{body:02x?}");
            }
            None => {
                assert_eq!(
                    category(&s),
                    Some(Category::VersionUnsupported),
                    "{body:02x?}"
                );
                assert!(s.pending_output().is_empty());
            }
        }
    }
    // A 1.0-1.5 request is answered with exactly the bytes the legacy profiles send.
    let mut s = Session::new(config.clone(), 1, t0);
    s.receive(&request(), t0);
    assert_eq!(s.pending_output(), response());

    let s = Session::new(config, 1, t0);
    assert!(s
        .report()
        .to_string()
        .lines()
        .any(|l| l == "profile=match-1.6"));
}

#[test]
fn stage_deadlines_and_establishment_cap() {
    let t0 = Instant::now();
    let config = Config::default();
    let mut s = Session::new(config.clone(), 1, t0);
    s.on_timer(t0 + config.version_wait - Duration::from_millis(1));
    assert_eq!(category(&s), None);
    s.on_timer(t0 + config.version_wait);
    assert_eq!(category(&s), Some(Category::VersionTimeout));
    assert_eq!(s.report().outcome.as_ref().unwrap().stage, Stage::Version);
    assert!(s.finished());

    let mut s = Session::new(config.clone(), 1, t0);
    s.receive(&request(), t0);
    let t1 = t0 + Duration::from_millis(40);
    write_all(&mut s, 12, t1);
    s.on_timer(t1 + config.client_hello_wait - Duration::from_millis(1));
    assert_eq!(category(&s), None);
    s.on_timer(t1 + config.client_hello_wait);
    let outcome = s.report().outcome.clone().unwrap();
    assert_eq!(
        (outcome.category, outcome.stage),
        (Category::TlsTimeout, Stage::Tls)
    );
    assert_eq!(s.report().last_completed, Some(Stage::Version));

    let capped = Config {
        version_wait: Duration::from_secs(60),
        establishment_cap: Duration::from_secs(2),
        ..config
    };
    let mut s = Session::new(capped, 1, t0);
    assert_eq!(s.next_deadline(), Some(t0 + Duration::from_secs(2)));
    s.on_timer(t0 + Duration::from_secs(2));
    let outcome = s.report().outcome.clone().unwrap();
    assert_eq!(outcome.category, Category::VersionTimeout);
    assert!(outcome.detail.contains("establishment cap"));
}

#[test]
fn incomplete_assembly_expires_through_session_timer() {
    let t0 = Instant::now();
    let config = Config {
        version_wait: Duration::from_secs(60),
        establishment_cap: Duration::from_secs(60),
        ..Config::default()
    };
    let mut s = Session::new(config, 1, t0);
    let t1 = t0 + Duration::from_secs(1);
    // FIRST fragment of a six-byte message: one payload byte of the total.
    s.receive(&hex("00 01 00 01 00 00 00 06 00"), t1);
    assert_eq!(s.next_deadline(), Some(t1 + aap_wire::ASSEMBLY_LIFETIME));
    s.on_timer(t1 + aap_wire::ASSEMBLY_LIFETIME);
    let outcome = s.report().outcome.clone().unwrap();
    assert_eq!(outcome.category, Category::FrameInvalid);
    assert!(outcome.detail.contains("Expired(0)"), "{}", outcome.detail);
}

#[test]
fn unexpected_and_malformed_input_is_classified() {
    let t0 = Instant::now();
    let mut duplicate = request();
    duplicate.extend(request());
    for (input, expected) in [
        (
            hex("01 03 00 06 00 01 00 01 00 01"),
            Category::UnexpectedMessage,
        ),
        (
            hex("00 07 00 06 00 01 00 01 00 01"),
            Category::UnexpectedMessage,
        ),
        (hex("00 0b 00 02 aa bb"), Category::UnexpectedMessage),
        (hex("00 03 00 04 00 0b 08 00"), Category::UnexpectedMessage),
        (tls_message(), Category::UnexpectedMessage),
        (duplicate, Category::UnexpectedMessage),
        (hex("00 13 00 01 00"), Category::FrameInvalid),
        (hex("00 02 00 01 00"), Category::FrameInvalid),
    ] {
        let mut s = Session::new(Config::default(), 1, t0);
        s.receive(&input, t0);
        assert_eq!(category(&s), Some(expected), "input {input:02x?}");
        let before = s.report().counters.clone();
        s.receive(&request(), t0);
        assert_eq!(
            s.report().counters,
            before,
            "input after outcome is ignored"
        );
    }
}

#[test]
fn transport_close_and_cancel_are_terminal_without_flush() {
    let t0 = Instant::now();
    let mut s = Session::new(Config::default(), 1, t0);
    s.receive(&request(), t0);
    s.on_transport_closed(None, t0);
    assert_eq!(category(&s), Some(Category::IoEof));
    assert_eq!(s.report().abandoned_output, 12);
    assert!(s.finished());
    assert_eq!(s.next_deadline(), None);

    let mut s = Session::new(Config::default(), 1, t0);
    s.on_transport_closed(Some(&io::Error::from_raw_os_error(32)), t0);
    let outcome = s.report().outcome.clone().unwrap();
    assert_eq!(
        (outcome.category, outcome.os_error),
        (Category::IoError, Some(32))
    );

    let mut s = Session::new(Config::default(), 1, t0);
    s.receive(&request(), t0);
    let mut late = tls_message();
    late.truncate(3);
    s.on_cancelled(t0);
    s.on_cancelled(t0);
    s.on_transport_closed(None, t0);
    s.receive(&late, t0);
    s.on_timer(t0 + Duration::from_secs(60));
    let r = s.report();
    assert_eq!(category(&s), Some(Category::Cancelled));
    assert_eq!(r.counters.bytes_in, 10);
    assert!(s.finished());
}
