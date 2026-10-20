//! TLS adapter tests against independently configured OpenSSL client peers with their
//! own generated identities (spec §11.2). The client is plain OpenSSL, not this crate.
use aap_tls::{
    client_hello, identity, FailureKind, Identity, PeerDecision, PeerPolicy, ServerConfig,
    TlsProfile, TlsServer, PLAINTEXT_LIMIT,
};
use openssl::{
    asn1::Asn1Time,
    bn::BigNum,
    ec::{EcGroup, EcKey},
    hash::MessageDigest,
    nid::Nid,
    pkey::{PKey, Private},
    rsa::Rsa,
    ssl::{
        ErrorCode, Ssl, SslContext, SslContextBuilder, SslMethod, SslStream, SslVerifyMode,
        SslVersion,
    },
    x509::{X509NameBuilder, X509},
};
use std::{
    io::{self, Read, Write},
    sync::{Arc, Mutex, OnceLock},
};

#[derive(Default)]
struct Pipe {
    inbound: Vec<u8>,
    outbound: Vec<u8>,
}
impl Read for Pipe {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.inbound.is_empty() {
            return Err(io::ErrorKind::WouldBlock.into());
        }
        let n = buf.len().min(self.inbound.len());
        buf[..n].copy_from_slice(&self.inbound[..n]);
        self.inbound.drain(..n);
        Ok(n)
    }
}
impl Write for Pipe {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.outbound.extend_from_slice(buf);
        Ok(buf.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

fn server_identity() -> Arc<Identity> {
    static IDENTITY: OnceLock<Arc<Identity>> = OnceLock::new();
    IDENTITY
        .get_or_init(|| Arc::new(Identity::generate().unwrap()))
        .clone()
}

/// The lab client's common name, which stands in for a unit-identifying one.
const CLIENT_COMMON_NAME: &str = "Lab head unit VIN-ZQ42";
/// The identifying part of [`CLIENT_COMMON_NAME`] that must never reach a report. It
/// holds characters outside `[0-9a-f]` deliberately: a report is mostly random hex
/// fingerprints, so searching one for a hex-only token (this used to be `0042`) matches
/// by chance every few hundred runs.
const CLIENT_UNIT_IDENTIFIER: &str = "VIN-ZQ42";

/// A head-unit-like client identity made directly with OpenSSL.
fn client_credentials(ec: bool) -> (X509, PKey<Private>) {
    let key = if ec {
        PKey::from_ec_key(
            EcKey::generate(&EcGroup::from_curve_name(Nid::X9_62_PRIME256V1).unwrap()).unwrap(),
        )
        .unwrap()
    } else {
        PKey::from_rsa(Rsa::generate(2048).unwrap()).unwrap()
    };
    let mut name = X509NameBuilder::new().unwrap();
    name.append_entry_by_nid(Nid::COMMONNAME, CLIENT_COMMON_NAME)
        .unwrap();
    name.append_entry_by_nid(Nid::ORGANIZATIONNAME, "Test Receiver Works")
        .unwrap();
    let name = name.build();
    let mut builder = X509::builder().unwrap();
    builder.set_version(2).unwrap();
    let serial = BigNum::from_u32(7).unwrap().to_asn1_integer().unwrap();
    builder.set_serial_number(&serial).unwrap();
    builder.set_subject_name(&name).unwrap();
    builder.set_issuer_name(&name).unwrap();
    builder
        .set_not_before(&Asn1Time::days_from_now(0).unwrap())
        .unwrap();
    builder
        .set_not_after(&Asn1Time::days_from_now(30).unwrap())
        .unwrap();
    builder.set_pubkey(&key).unwrap();
    builder.sign(&key, MessageDigest::sha256()).unwrap();
    (builder.build(), key)
}

fn fingerprint(cert: &X509) -> [u8; 32] {
    let mut out = [0u8; 32];
    out.copy_from_slice(&cert.digest(MessageDigest::sha256()).unwrap());
    out
}

struct Client {
    stream: SslStream<Pipe>,
    seen_server: Arc<Mutex<Option<[u8; 32]>>>,
    fingerprint: [u8; 32],
}

fn client(certificate: bool, ec: bool, configure: impl FnOnce(&mut SslContextBuilder)) -> Client {
    let (cert, key) = client_credentials(ec);
    let seen_server = Arc::new(Mutex::new(None));
    let seen = seen_server.clone();
    let mut builder = SslContext::builder(SslMethod::tls_client()).unwrap();
    builder.set_verify_callback(SslVerifyMode::PEER, move |_, context| {
        if context.error_depth() == 0 {
            *seen.lock().unwrap() = context.current_cert().map(|c| fingerprint(&c.to_owned()));
        }
        true
    });
    if certificate {
        builder.set_certificate(&cert).unwrap();
        builder.set_private_key(&key).unwrap();
    }
    configure(&mut builder);
    let mut ssl = Ssl::new(&builder.build()).unwrap();
    ssl.set_connect_state();
    Client {
        stream: SslStream::new(ssl, Pipe::default()).unwrap(),
        seen_server,
        fingerprint: fingerprint(&cert),
    }
}

fn server(profile: TlsProfile, peer: PeerPolicy) -> TlsServer {
    TlsServer::new(&ServerConfig {
        profile,
        identity: server_identity(),
        peer,
    })
    .unwrap()
}

/// Drives both sides until the server completes or fails. `chunk` bounds the size of
/// each server input, simulating fragmented 0x0003 messages.
fn run_handshake(
    client: &mut Client,
    server: &mut TlsServer,
    chunk: usize,
) -> Result<(), aap_tls::Failure> {
    for _ in 0..16 {
        match client.stream.do_handshake() {
            Ok(()) => {}
            Err(e) if e.code() == ErrorCode::WANT_READ => {}
            Err(_) if !client.stream.get_ref().outbound.is_empty() => {}
            Err(e) => panic!("client handshake failed first: {e}"),
        }
        let bytes = std::mem::take(&mut client.stream.get_mut().outbound);
        if bytes.is_empty() {
            continue;
        }
        for part in bytes.chunks(chunk) {
            let (output, done) = server.handshake(part)?;
            client.stream.get_mut().inbound.extend(output);
            if done {
                client
                    .stream
                    .do_handshake()
                    .expect("client completes after server Finished");
                return Ok(());
            }
        }
    }
    panic!("handshake did not converge");
}

#[test]
fn pinned_client_completes_tls12_with_aead_suite() {
    let mut c = client(true, false, |_| {});
    let mut s = server(
        TlsProfile::Aead,
        PeerPolicy::Pinned(vec![[9; 32], c.fingerprint]),
    );
    run_handshake(&mut c, &mut s, usize::MAX).unwrap();
    assert!(s.established());
    assert_eq!(s.decision(), Some(PeerDecision::PinMatch(c.fingerprint)));
    assert_eq!(
        *c.seen_server.lock().unwrap(),
        Some(server_identity().fingerprint())
    );
    let summary = s.summary();
    assert_eq!(summary.version.as_deref(), Some("TLSv1.2"));
    let cipher = summary.cipher.clone().unwrap().0;
    assert!(
        cipher.contains("ECDHE_RSA") && (cipher.contains("GCM") || cipher.contains("CHACHA20")),
        "{cipher}"
    );
    let peer = s.peer_certificate().unwrap();
    assert_eq!(peer.fingerprint, c.fingerprint);
    assert_eq!(peer.key, "RSA-2048");
    // Organization is kept; the common name (which may identify a unit) is not.
    assert_eq!(peer.subject, "CN=redacted(22 chars),O=Test Receiver Works");
    let text = summary.to_string();
    assert!(
        text.contains("tls_client_hello_offers_tls12=true"),
        "{text}"
    );
    assert!(text.contains("tls_peer_decision=pin-match"), "{text}");
    assert!(
        !text.contains(CLIENT_UNIT_IDENTIFIER),
        "unit identifier leaked: {text}"
    );
    assert_eq!(c.stream.ssl().version_str(), "TLSv1.2");
}

#[test]
fn first_use_accepts_an_unknown_ec_client_and_reports_it() {
    let mut c = client(true, true, |_| {});
    let mut s = server(TlsProfile::Aead, PeerPolicy::FirstUse);
    run_handshake(&mut c, &mut s, usize::MAX).unwrap();
    assert_eq!(
        s.decision(),
        Some(PeerDecision::FirstUseAccepted(c.fingerprint))
    );
    assert!(s
        .summary()
        .to_string()
        .contains("tls_peer_policy=first-use-armed"));
    assert_eq!(s.peer_certificate().unwrap().key, "EC-prime256v1-256");
    let uuid = identity::random_uuid().unwrap();
    assert_eq!(uuid.len(), 36);
    assert_eq!(&uuid[14..15], "4");
    assert_ne!(uuid, identity::random_uuid().unwrap());
}

#[test]
fn pin_mismatch_is_a_certificate_policy_failure_with_an_alert() {
    let mut c = client(true, false, |_| {});
    let mut s = server(TlsProfile::Aead, PeerPolicy::Pinned(vec![[1; 32]]));
    let failure = run_handshake(&mut c, &mut s, usize::MAX).unwrap_err();
    assert_eq!(failure.kind, FailureKind::CertPolicy, "{}", failure.detail);
    assert!(
        failure.detail.contains(&identity::hex(&c.fingerprint)),
        "{}",
        failure.detail
    );
    assert!(!failure.output.is_empty(), "alert record for the peer");
    assert_eq!(failure.output[0], 21, "plaintext alert record");
    assert!(!s.established());
    // The client sees the alert.
    c.stream.get_mut().inbound.extend(failure.output);
    assert!(c.stream.do_handshake().is_err());
    assert!(s.handshake(&[0]).is_err(), "failed sessions stay failed");
}

#[test]
fn missing_client_certificate_is_rejected() {
    let mut c = client(false, false, |_| {});
    let mut s = server(TlsProfile::Aead, PeerPolicy::FirstUse);
    let failure = run_handshake(&mut c, &mut s, usize::MAX).unwrap_err();
    assert_eq!(failure.kind, FailureKind::CertPolicy, "{}", failure.detail);
    assert!(
        failure
            .detail
            .contains("did not return a client certificate"),
        "{}",
        failure.detail
    );
}

#[test]
fn tls13_only_client_is_refused_with_protocol_version() {
    let mut c = client(true, false, |b| {
        b.set_min_proto_version(Some(SslVersion::TLS1_3)).unwrap();
    });
    let mut s = server(TlsProfile::Aead, PeerPolicy::FirstUse);
    let failure = run_handshake(&mut c, &mut s, usize::MAX).unwrap_err();
    assert_eq!(failure.kind, FailureKind::LocalAlert, "{}", failure.detail);
    assert!(
        failure.detail.contains("protocol_version(70)"),
        "{}",
        failure.detail
    );
    let hello = s.client_hello().unwrap().as_ref().unwrap();
    assert_eq!(hello.supported_versions, vec![0x0304]);
    assert!(!hello.offers_tls12());
}

#[test]
fn cbc_only_client_has_no_common_suite_in_baseline_but_matches_compat_profile() {
    let cbc_only = |b: &mut SslContextBuilder| {
        b.set_max_proto_version(Some(SslVersion::TLS1_2)).unwrap();
        b.set_cipher_list("ECDHE-RSA-AES128-SHA").unwrap();
    };
    let mut c = client(true, false, cbc_only);
    let mut s = server(TlsProfile::Aead, PeerPolicy::FirstUse);
    let failure = run_handshake(&mut c, &mut s, usize::MAX).unwrap_err();
    assert_eq!(
        failure.kind,
        FailureKind::NoCommonSuite,
        "{}",
        failure.detail
    );
    let hello = s.client_hello().unwrap().as_ref().unwrap();
    assert!(
        hello.cipher_suites.contains(&0xc013),
        "{:x?}",
        hello.cipher_suites
    );

    let mut c = client(true, false, cbc_only);
    let mut s = server(TlsProfile::CbcCompat, PeerPolicy::FirstUse);
    run_handshake(&mut c, &mut s, usize::MAX).unwrap();
    assert_eq!(s.summary().cipher.unwrap().1, [0xc0, 0x13]);
}

#[test]
fn byte_at_a_time_handshake_input_completes() {
    let mut c = client(true, false, |_| {});
    let mut s = server(TlsProfile::Aead, PeerPolicy::Pinned(vec![c.fingerprint]));
    run_handshake(&mut c, &mut s, 1).unwrap();
    assert!(s.established());
    let hello = s.client_hello().unwrap().as_ref().unwrap();
    assert!(hello.offers_tls12());
}

fn established() -> (Client, TlsServer) {
    let mut c = client(true, false, |_| {});
    let mut s = server(TlsProfile::Aead, PeerPolicy::Pinned(vec![c.fingerprint]));
    run_handshake(&mut c, &mut s, usize::MAX).unwrap();
    (c, s)
}

fn client_read_all(c: &mut Client) -> Vec<u8> {
    let mut out = Vec::new();
    let mut buffer = [0u8; 4096];
    loop {
        match c.stream.ssl_read(&mut buffer) {
            Ok(n) => out.extend_from_slice(&buffer[..n]),
            Err(e) if e.code() == ErrorCode::WANT_READ => return out,
            Err(e) => panic!("client read: {e}"),
        }
    }
}

#[test]
fn application_data_round_trips_with_multiple_records() {
    let (mut c, mut s) = established();
    // Server to client: one full-size fragment and a small one, each complete records.
    let big: Vec<u8> = (0..PLAINTEXT_LIMIT).map(|i| i as u8).collect();
    let first = s.encrypt(&big).unwrap();
    let second = s.encrypt(b"\x00\x05hello").unwrap();
    assert!(first.len() <= u16::MAX as usize);
    c.stream.get_mut().inbound.extend(&first);
    c.stream.get_mut().inbound.extend(&second);
    let mut expected = big.clone();
    expected.extend_from_slice(b"\x00\x05hello");
    assert_eq!(client_read_all(&mut c), expected);
    assert!(s.encrypt(&vec![0; PLAINTEXT_LIMIT + 1]).is_err());
    assert!(s.encrypt(&[]).is_err());

    // Client to server: 40 KiB written at once is three records in one input.
    let payload: Vec<u8> = (0..40_000u32).map(|i| (i * 7) as u8).collect();
    c.stream.ssl_write(&payload).unwrap();
    let ciphertext = std::mem::take(&mut c.stream.get_mut().outbound);
    let (plaintext, boundary) = s.decrypt(&ciphertext).unwrap();
    assert!(boundary);
    assert_eq!(plaintext, payload);
    assert_eq!(s.counters().records_in, 3);
}

#[test]
fn records_split_across_inputs_report_boundaries() {
    let (mut c, mut s) = established();
    c.stream.ssl_write(b"\x00\x06discovery-response").unwrap();
    let ciphertext = std::mem::take(&mut c.stream.get_mut().outbound);
    let (head, tail) = ciphertext.split_at(3);
    assert_eq!(s.decrypt(head).unwrap(), (Vec::new(), false));
    let (mid, rest) = tail.split_at(tail.len() / 2);
    assert_eq!(s.decrypt(mid).unwrap(), (Vec::new(), false));
    assert_eq!(
        s.decrypt(rest).unwrap(),
        (b"\x00\x06discovery-response".to_vec(), true)
    );
}

#[test]
fn peer_alert_during_handshake_is_classified() {
    let mut c = client(true, false, |b| {
        // The client rejects our certificate.
        b.set_verify_callback(SslVerifyMode::PEER, |_, _| false);
    });
    let mut s = server(TlsProfile::Aead, PeerPolicy::FirstUse);
    c.stream.do_handshake().unwrap_err();
    let hello = std::mem::take(&mut c.stream.get_mut().outbound);
    let (flight, done) = s.handshake(&hello).unwrap();
    assert!(!done);
    c.stream.get_mut().inbound.extend(flight);
    assert!(c.stream.do_handshake().is_err());
    let alert = std::mem::take(&mut c.stream.get_mut().outbound);
    let failure = s.handshake(&alert).unwrap_err();
    assert_eq!(failure.kind, FailureKind::PeerAlert, "{}", failure.detail);
    assert!(failure.detail.contains("peer:fatal:"), "{}", failure.detail);
    assert!(s.summary().to_string().contains("tls_alerts=peer:fatal:"));
}

#[test]
fn close_notify_after_handshake_is_a_peer_alert() {
    let (mut c, mut s) = established();
    let _ = c.stream.shutdown();
    let bytes = std::mem::take(&mut c.stream.get_mut().outbound);
    let failure = s.decrypt(&bytes).unwrap_err();
    assert_eq!(failure.kind, FailureKind::PeerAlert, "{}", failure.detail);
    assert!(
        failure.detail.contains("close_notify"),
        "{}",
        failure.detail
    );
}

#[test]
fn tampered_record_fails_decryption() {
    let (mut c, mut s) = established();
    c.stream.ssl_write(b"\x00\x06x").unwrap();
    let mut ciphertext = std::mem::take(&mut c.stream.get_mut().outbound);
    let last = ciphertext.len() - 1;
    ciphertext[last] ^= 1;
    let failure = s.decrypt(&ciphertext).unwrap_err();
    assert!(
        matches!(failure.kind, FailureKind::LocalAlert | FailureKind::Error),
        "{failure:?}"
    );
    assert!(s.decrypt(b"x").is_err());
}

#[test]
fn oversized_record_header_is_rejected_before_openssl() {
    let (_c, mut s) = established();
    let failure = s.decrypt(&[23, 3, 3, 0xff, 0xff]).unwrap_err();
    assert!(failure.detail.contains("exceeds"), "{}", failure.detail);
}

#[test]
fn client_hello_reader_matches_configured_offer() {
    let mut c = client(true, false, |b| {
        b.set_max_proto_version(Some(SslVersion::TLS1_2)).unwrap();
        b.set_cipher_list("ECDHE-RSA-AES128-GCM-SHA256:AES128-SHA")
            .unwrap();
        b.set_sigalgs_list("RSA+SHA256:ECDSA+SHA256").unwrap();
        b.set_groups_list("P-256").unwrap();
    });
    c.stream.do_handshake().unwrap_err();
    let bytes = std::mem::take(&mut c.stream.get_mut().outbound);
    let mut reader = client_hello::Reader::new();
    for part in bytes.chunks(7) {
        reader.feed(part);
    }
    let hello = reader.result().unwrap().as_ref().unwrap();
    assert_eq!(hello.legacy_version, 0x0303);
    assert!(hello.supported_versions.is_empty());
    assert!(hello.offers_tls12());
    assert_eq!(&hello.cipher_suites[..2], &[0xc02f, 0x002f]);
    assert_eq!(hello.signature_algorithms, vec![0x0401, 0x0403]);
    assert_eq!(hello.groups, vec![0x0017]);
    assert_eq!(hello.compression_methods, vec![0]);

    let mut reader = client_hello::Reader::new();
    reader.feed(&[23, 3, 3, 0, 1, 0]);
    assert!(reader.result().unwrap().is_err());
}

#[test]
fn identity_is_rsa2048_sha256_non_ca_server_auth_and_persists() {
    let id = Identity::generate().unwrap();
    let text = String::from_utf8(id.certificate().to_text().unwrap()).unwrap();
    for expected in [
        "Public-Key: (2048 bit)",
        "sha256WithRSAEncryption",
        "CA:FALSE",
        "TLS Web Server Authentication",
        "Digital Signature, Key Encipherment",
        "Subject: CN=HeadLink source, O=HeadLink project",
        "Not Before: Jan  1 00:00:00 2024 GMT",
        "Not After : Jan  1 00:00:00 2049 GMT",
    ] {
        assert!(text.contains(expected), "missing {expected:?} in\n{text}");
    }
    assert_eq!(
        id.certificate().subject_name().to_der().unwrap(),
        id.certificate().issuer_name().to_der().unwrap()
    );

    let dir = std::env::temp_dir().join(format!("aap-tls-identity-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let (created, new) = Identity::load_or_create(&dir).unwrap();
    assert!(new);
    let (loaded, new) = Identity::load_or_create(&dir).unwrap();
    assert!(!new);
    assert_eq!(created.fingerprint(), loaded.fingerprint());
    use std::os::unix::fs::PermissionsExt;
    let mode = std::fs::metadata(dir.join(identity::FILE_NAME))
        .unwrap()
        .permissions()
        .mode();
    assert_eq!(mode & 0o777, 0o600);
    assert!(!format!("{loaded:?}").contains("PRIVATE"));

    // A certificate paired with another key is refused rather than replaced.
    let mut mismatched = id.certificate().to_pem().unwrap();
    mismatched.extend(created_key_pem(&dir));
    std::fs::write(dir.join(identity::FILE_NAME), mismatched).unwrap();
    let error = Identity::load_or_create(&dir).unwrap_err();
    assert!(error.to_string().contains("does not match"), "{error}");
    std::fs::write(dir.join(identity::FILE_NAME), b"garbage").unwrap();
    assert!(Identity::load(&dir).is_err());
    std::fs::remove_dir_all(&dir).unwrap();

    assert_eq!(
        identity::parse_fingerprint(&id.fingerprint_hex()),
        Some(id.fingerprint())
    );
    assert_eq!(identity::parse_fingerprint("zz"), None);
}

fn created_key_pem(dir: &std::path::Path) -> Vec<u8> {
    let pem = std::fs::read(dir.join(identity::FILE_NAME)).unwrap();
    let text = String::from_utf8(pem).unwrap();
    let start = text.find("-----BEGIN PRIVATE KEY-----").unwrap();
    text[start..].as_bytes().to_vec()
}

#[test]
fn supplied_identity_preserves_and_transmits_intermediate_chain() {
    use openssl::x509::extension::{BasicConstraints, ExtendedKeyUsage, KeyUsage};
    fn issue(
        name: &str,
        ca: bool,
        issuer: Option<(&X509, &PKey<Private>)>,
    ) -> (X509, PKey<Private>) {
        let key = PKey::from_rsa(Rsa::generate(2048).unwrap()).unwrap();
        let mut subject = X509NameBuilder::new().unwrap();
        subject.append_entry_by_nid(Nid::COMMONNAME, name).unwrap();
        subject
            .append_entry_by_nid(Nid::ORGANIZATIONNAME, "Import test")
            .unwrap();
        let subject = subject.build();
        let mut cert = X509::builder().unwrap();
        cert.set_version(2).unwrap();
        cert.set_serial_number(
            &BigNum::from_u32(name.len() as u32)
                .unwrap()
                .to_asn1_integer()
                .unwrap(),
        )
        .unwrap();
        cert.set_subject_name(&subject).unwrap();
        cert.set_issuer_name(issuer.map_or(&*subject, |(c, _)| c.subject_name()))
            .unwrap();
        cert.set_pubkey(&key).unwrap();
        cert.set_not_before(&Asn1Time::days_from_now(0).unwrap())
            .unwrap();
        cert.set_not_after(&Asn1Time::days_from_now(30).unwrap())
            .unwrap();
        let mut constraints = BasicConstraints::new();
        constraints.critical();
        if ca {
            constraints.ca();
        }
        cert.append_extension(constraints.build().unwrap()).unwrap();
        let mut usage = KeyUsage::new();
        usage.digital_signature();
        if ca {
            usage.key_cert_sign();
        } else {
            usage.key_encipherment();
        }
        cert.append_extension(usage.build().unwrap()).unwrap();
        if !ca {
            cert.append_extension(ExtendedKeyUsage::new().server_auth().build().unwrap())
                .unwrap();
        }
        cert.sign(issuer.map_or(&key, |(_, k)| k), MessageDigest::sha256())
            .unwrap();
        (cert.build(), key)
    }
    let (root, root_key) = issue("Local root", true, None);
    let (intermediate, intermediate_key) =
        issue("Local intermediate", true, Some((&root, &root_key)));
    let (leaf, key) = issue(
        "Supplied source",
        false,
        Some((&intermediate, &intermediate_key)),
    );
    let mut pem = leaf.to_pem().unwrap();
    pem.extend(intermediate.to_pem().unwrap());
    pem.extend(key.private_key_to_pem_pkcs8().unwrap());
    let identity = Identity::from_pem(&pem).unwrap();
    assert_eq!(identity.chain().len(), 1);
    assert!(identity
        .summary()
        .contains("source_identity_self_signed=false"));
    assert!(!identity.summary().contains("HeadLink project"));
    assert!(!identity.summary().contains("PRIVATE KEY"));
    let dir = std::env::temp_dir().join(format!("aap-chain-{}", identity::random_uuid().unwrap()));
    identity.save(&dir).unwrap();
    let loaded = Identity::load(&dir).unwrap();
    assert_eq!(
        loaded.chain()[0].to_der().unwrap(),
        intermediate.to_der().unwrap()
    );
    let mut c = client(true, false, |builder| {
        builder.cert_store_mut().add_cert(root.clone()).unwrap();
        // Enforce CA validation, overriding the permissive capture callback.
        builder.set_verify(SslVerifyMode::PEER);
    });
    let mut s = TlsServer::new(&ServerConfig {
        profile: TlsProfile::Aead,
        identity: Arc::new(loaded),
        peer: PeerPolicy::FirstUse,
    })
    .unwrap();
    run_handshake(&mut c, &mut s, usize::MAX).unwrap();
    assert_eq!(
        c.stream.ssl().verify_result(),
        openssl::x509::X509VerifyResult::OK
    );
    assert_eq!(c.stream.ssl().peer_cert_chain().unwrap().len(), 2);
    // A wrong chain must be rejected before it can replace an active identity.
    let mut wrong = leaf.to_pem().unwrap();
    wrong.extend(root.to_pem().unwrap());
    wrong.extend(key.private_key_to_pem_pkcs8().unwrap());
    assert!(Identity::from_pem(&wrong)
        .unwrap_err()
        .to_string()
        .contains("leaf-to-issuer"));
    assert!(Identity::from_pem(&vec![b'x'; identity::PEM_LIMIT + 1]).is_err());
    let mut expired = X509::builder().unwrap();
    expired.set_version(2).unwrap();
    expired.set_serial_number(leaf.serial_number()).unwrap();
    expired.set_subject_name(leaf.subject_name()).unwrap();
    expired.set_issuer_name(leaf.subject_name()).unwrap();
    expired.set_pubkey(&key).unwrap();
    expired
        .set_not_before(&Asn1Time::from_unix(1).unwrap())
        .unwrap();
    expired
        .set_not_after(&Asn1Time::from_unix(2).unwrap())
        .unwrap();
    expired.sign(&key, MessageDigest::sha256()).unwrap();
    let mut expired_pem = expired.build().to_pem().unwrap();
    expired_pem.extend(key.private_key_to_pem_pkcs8().unwrap());
    assert!(Identity::from_pem(&expired_pem)
        .unwrap_err()
        .to_string()
        .contains("not currently valid"));
    let encrypted_key = key
        .private_key_to_pem_pkcs8_passphrase(openssl::symm::Cipher::aes_256_cbc(), b"test password")
        .unwrap();
    let mut encrypted_pem = leaf.to_pem().unwrap();
    encrypted_pem.extend(encrypted_key);
    assert!(Identity::from_pem(&encrypted_pem)
        .unwrap_err()
        .to_string()
        .contains("encrypted identity"));
    std::fs::remove_dir_all(dir).unwrap();
}
