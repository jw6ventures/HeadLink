use std::{
    io::Write,
    process::{Command, Stdio},
};
fn run(bytes: &[u8]) -> std::process::Output {
    let mut child = Command::new(env!("CARGO_BIN_EXE_source-harness"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    child.stdin.take().unwrap().write_all(bytes).unwrap();
    child.wait_with_output().unwrap()
}
#[test]
fn metadata_only_fixture_output() {
    let bytes: Vec<u8> = include_str!("../../../protocol/fixtures/version-request.hex")
        .split_whitespace()
        .map(|s| u8::from_str_radix(s, 16).unwrap())
        .collect();
    let output = run(&bytes);
    assert!(output.status.success());
    assert_eq!(
        String::from_utf8(output.stdout).unwrap(),
        "channel=0 flags=0x00 id=0x0001 plaintext_bytes=6\n"
    );
}
#[test]
fn malformed_or_incomplete_input_fails() {
    for bytes in [
        &[0, 3, 0][..],
        &[0, 0, 0, 1, 0][..],
        &[0, 1, 0, 1, 0, 0, 0, 4, 0][..],
    ] {
        let output = run(bytes);
        assert!(!output.status.success());
        assert!(String::from_utf8(output.stderr)
            .unwrap()
            .contains("FRAME_INVALID"));
    }
}
#[test]
fn ciphertext_is_never_treated_as_plaintext() {
    let output = run(&[0, 11, 0, 2, 0, 5]);
    assert!(!output.status.success());
    assert!(output.stdout.is_empty());
    assert!(String::from_utf8(output.stderr)
        .unwrap()
        .contains("encrypted frame"));
}

mod lab_tcp {
    use std::{
        io::{BufRead, BufReader, Read, Write},
        net::{TcpListener, TcpStream},
        process::{Child, Command, Stdio},
        time::Duration,
    };

    fn hex(s: &str) -> Vec<u8> {
        s.split_whitespace()
            .map(|b| u8::from_str_radix(b, 16).unwrap())
            .collect()
    }
    fn harness(args: &[&str]) -> Child {
        Command::new(env!("CARGO_BIN_EXE_source-harness"))
            .args(args)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap()
    }
    fn finish(child: Child) -> (i32, String) {
        let output = child.wait_with_output().unwrap();
        let text = String::from_utf8(output.stdout).unwrap();
        (output.status.code().unwrap(), text)
    }

    /// Runs a listen-mode version-only exchange and returns the report text.
    fn listen_exchange(extra: &[&str]) -> String {
        let mut args = vec!["listen", "127.0.0.1:0", "--client-hello-wait-ms", "3000"];
        args.extend_from_slice(extra);
        let mut child = harness(&args);
        let mut stdout = BufReader::new(child.stdout.take().unwrap());
        let addr = loop {
            let mut line = String::new();
            stdout.read_line(&mut line).unwrap();
            if let Some(addr) = line.trim().strip_prefix("listening=") {
                break addr.to_owned();
            }
            assert!(
                line == "stop_after=version\n" || line.starts_with("session_uuid="),
                "{line}"
            );
        };
        let mut peer = TcpStream::connect(&addr).unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let request = hex(include_str!(
            "../../../protocol/fixtures/version-request.hex"
        ));
        for part in request.chunks(4) {
            peer.write_all(part).unwrap();
            std::thread::sleep(Duration::from_millis(5));
        }
        let mut response = [0u8; 12];
        peer.read_exact(&mut response).unwrap();
        assert_eq!(
            response.to_vec(),
            hex(include_str!(
                "../../../protocol/fixtures/version-response.hex"
            ))
        );
        peer.write_all(&hex("00 03 00 07 00 03 16 03 01 00 04"))
            .unwrap();
        let mut rest = String::new();
        stdout.read_to_string(&mut rest).unwrap();
        let status = child.wait().unwrap();
        assert!(status.success(), "{rest}");
        for expected in [
            "transport=tcp-lab (not wireless Android Auto)",
            "last_completed_stage=version",
            "requested_version=1.1",
            "response_version=1.5",
            "final_category=STAGE_LIMIT",
        ] {
            assert!(
                rest.lines().any(|l| l == expected),
                "missing {expected}:\n{rest}"
            );
        }
        rest
    }

    #[test]
    fn listen_mode_version_only_exchange_with_fixture_peer() {
        let text = listen_exchange(&[]);
        assert!(!text.contains("io=blocking-threads"));
    }

    #[test]
    fn threaded_io_version_only_exchange_joins_both_threads() {
        let text = listen_exchange(&["--io", "threads"]);
        for expected in [
            "io=blocking-threads",
            "io_reader=joined",
            "io_writer=joined",
            "io_descriptor=closed",
        ] {
            assert!(
                text.lines().any(|l| l == expected),
                "missing {expected}:\n{text}"
            );
        }
    }

    #[test]
    fn connect_mode_unsupported_request_sends_nothing_and_fails() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        // Only the legacy profile refuses a 1.7 request; the default answers it.
        let child = harness(&["connect", &addr, "--profile", "legacy-1.5"]);
        let (mut peer, _) = listener.accept().unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        peer.write_all(&hex("00 03 00 06 00 01 00 01 00 07"))
            .unwrap();
        let mut received = Vec::new();
        peer.read_to_end(&mut received).unwrap();
        assert!(received.is_empty(), "unexpected response {received:02x?}");
        let (code, text) = finish(child);
        assert_eq!(code, 2, "{text}");
        assert!(text
            .lines()
            .any(|l| l == "final_category=VERSION_UNSUPPORTED"));
        assert!(text.lines().any(|l| l == "requested_version=1.7"));
        assert!(text.lines().any(|l| l == "last_completed_stage=-"));
    }

    /// Full TLS, authentication, and discovery against the test head unit.
    fn discovery_exchange(io: &str, pin_matches: bool) -> (i32, String) {
        let dir =
            std::env::temp_dir().join(format!("harness-{io}-{pin_matches}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let hu = hu_sim::HeadUnit::new(hu_sim::Config::default());
        let pin = if pin_matches {
            aap_tls::identity::hex(&hu.fingerprint)
        } else {
            "00".repeat(32)
        };
        let mut child = harness(&[
            "listen",
            "127.0.0.1:0",
            "--io",
            io,
            "--stop-after",
            "discovery",
            "--identity-dir",
            dir.to_str().unwrap(),
            "--peer-pin",
            &pin,
            "--hold-ms",
            "300",
        ]);
        let mut stdout = BufReader::new(child.stdout.take().unwrap());
        let mut header = String::new();
        loop {
            let mut line = String::new();
            stdout.read_line(&mut line).unwrap();
            header.push_str(&line);
            if line.starts_with("listening=") {
                let addr = line.trim().strip_prefix("listening=").unwrap().to_owned();
                let stream = TcpStream::connect(&addr).unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_millis(20)))
                    .unwrap();
                let hu = hu_sim::serve(stream, hu, Duration::from_secs(20));
                if pin_matches {
                    assert!(hu.errors.is_empty(), "{:?}", hu.errors);
                }
                break;
            }
        }
        let mut rest = String::new();
        stdout.read_to_string(&mut rest).unwrap();
        let code = child.wait().unwrap().code().unwrap();
        std::fs::remove_dir_all(&dir).unwrap();
        (code, header + &rest)
    }

    #[test]
    fn discovery_over_lab_tcp_with_the_test_head_unit_in_both_io_modes() {
        for io in ["poll", "threads"] {
            let (code, text) = discovery_exchange(io, true);
            assert_eq!(code, 0, "{text}");
            for expected in [
                "identity_created=true",
                "last_completed_stage=discovery",
                "final_category=HOLD_COMPLETE",
                "discovery_capabilities=usable",
                "auth_status=0",
            ] {
                assert!(
                    text.lines().any(|l| l == expected),
                    "missing {expected}:\n{text}"
                );
            }
        }
    }

    #[test]
    fn unpinned_receiver_certificate_fails_with_cert_policy() {
        let (code, text) = discovery_exchange("poll", false);
        assert_eq!(code, 2, "{text}");
        assert!(
            text.lines().any(|l| l == "final_category=TLS_CERT_POLICY"),
            "{text}"
        );
        assert!(
            text.lines().any(|l| l == "last_completed_stage=version"),
            "{text}"
        );
    }

    /// Projection over lab TCP: the scene streams, a tap on button 1 reaches it, and the
    /// configured projection limit ends the session.
    fn projection_exchange(io: &str) -> (i32, String) {
        let dir =
            std::env::temp_dir().join(format!("harness-projection-{io}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        // Button 1's center for the lab receiver's 800x480 configuration; see the scene
        // layout the harness prints. Only that configuration is accepted, so the tap keeps
        // its meaning now that the largest one is preferred.
        let hu = hu_sim::HeadUnit::new(hu_sim::Config {
            projection: hu_sim::Projection {
                tap_after_media: Some((4, 210, 197)),
                setup_configs: Some(vec![1]),
                ..Default::default()
            },
            ..hu_sim::Config::default()
        });
        let pin = aap_tls::identity::hex(&hu.fingerprint);
        let mut child = harness(&[
            "listen",
            "127.0.0.1:0",
            "--io",
            io,
            "--stop-after",
            "projection",
            "--identity-dir",
            dir.to_str().unwrap(),
            "--peer-pin",
            &pin,
            "--projection-ms",
            "1200",
            "--fps",
            "15",
        ]);
        let mut stdout = BufReader::new(child.stdout.take().unwrap());
        let mut header = String::new();
        loop {
            let mut line = String::new();
            stdout.read_line(&mut line).unwrap();
            header.push_str(&line);
            if line.starts_with("listening=") {
                let addr = line.trim().strip_prefix("listening=").unwrap().to_owned();
                let stream = TcpStream::connect(&addr).unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_millis(20)))
                    .unwrap();
                let hu = hu_sim::serve(stream, hu, Duration::from_secs(30));
                assert!(hu.errors.is_empty(), "{:?}", hu.errors);
                assert!(hu.media.len() > 5, "{} media units", hu.media.len());
                break;
            }
        }
        let mut rest = String::new();
        stdout.read_to_string(&mut rest).unwrap();
        let code = child.wait().unwrap().code().unwrap();
        std::fs::remove_dir_all(&dir).unwrap();
        (code, header + &rest)
    }

    #[test]
    fn projection_over_lab_tcp_streams_the_scene_and_receives_input() {
        for io in ["poll", "threads"] {
            let (code, text) = projection_exchange(io);
            assert_eq!(code, 0, "{text}");
            for expected in [
                "stop_after=projection",
                "last_completed_stage=projecting",
                "final_category=PROJECTION_COMPLETE",
                "projection_step=streaming",
                "video_selected=channel=3 display=0 config=1 800x480@30 margins=0x0 insets=- content=0,0+800x480",
            ] {
                assert!(
                    text.lines().any(|l| l == expected),
                    "missing {expected}:\n{text}"
                );
            }
            assert!(text.contains("scene_taps=1,0,0,0"), "{text}");
            assert!(text.contains("input_down=1 input_up=1"), "{text}");
            assert!(text.contains("video_keyframes_sent="), "{text}");
            // The scene layout a receiver uses to pick sample points.
            assert!(
                text.contains(
                    "scene_layout=content=0,0+800x480 header=96 margin=20 button=370x162 \
centers_samples=205:197/112:237,595:197/502:237,205:379/112:419,595:379/502:419"
                ),
                "{text}"
            );
        }
    }

    #[test]
    fn invalid_arguments_fail_before_any_connection() {
        for args in [
            &["listen"][..],
            &["connect", "127.0.0.1:9", "--profile", "modern-6.1"][..],
            &["listen", "127.0.0.1:0", "--version-wait-ms"][..],
            &["listen", "127.0.0.1:0", "--io", "epoll"][..],
            &["listen", "127.0.0.1:0", "--stop-after", "discovery"][..],
            &["listen", "127.0.0.1:0", "--trust-first-use"][..],
            &[
                "listen",
                "127.0.0.1:0",
                "--stop-after",
                "auth",
                "--identity-dir",
                "/tmp/x",
            ][..],
            &[
                "listen",
                "127.0.0.1:0",
                "--stop-after",
                "auth",
                "--identity-dir",
                "/tmp/x",
                "--trust-first-use",
                "--peer-pin",
                &"00".repeat(32),
            ][..],
            &[
                "listen",
                "127.0.0.1:0",
                "--stop-after",
                "auth",
                "--identity-dir",
                "/tmp/x",
                "--peer-pin",
                "abc",
            ][..],
            &[
                "listen",
                "127.0.0.1:0",
                "--stop-after",
                "discovery",
                "--hold-ms",
                "30001",
            ][..],
        ] {
            let (code, text) = finish(harness(args));
            assert_eq!(code, 1, "{args:?}");
            assert!(text.is_empty());
        }
    }
}
