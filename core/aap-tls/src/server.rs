//! Sans-IO TLS server over in-memory buffers (spec §7.3). The session owns framing: it
//! passes the bytes of each inbound 0x0003 message to [`TlsServer::handshake`], sends the
//! returned records in plaintext 0x0003 messages, and after completion encrypts and
//! decrypts one frame payload at a time through the same session.
use crate::{
    client_hello::{self, ClientHello},
    identity::{hex, Identity},
    records::RecordTracker,
};
use foreign_types::ForeignTypeRef;
use openssl::{
    error::ErrorStack,
    ex_data::Index,
    hash::MessageDigest,
    nid::Nid,
    pkey::Id,
    ssl::{
        ErrorCode, Ssl, SslContext, SslMethod, SslOptions, SslRef, SslSessionCacheMode, SslStream,
        SslVerifyMode, SslVersion,
    },
    x509::{X509NameRef, X509Ref, X509StoreContextRef, X509VerifyResult},
};
use std::{
    ffi::{c_int, CStr},
    fmt::{self, Write as _},
    io::{self, Read, Write},
    mem::take,
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{Arc, Mutex, MutexGuard, OnceLock, PoisonError},
};

/// Largest plaintext accepted by [`TlsServer::encrypt`]: one maximum-size TLS record,
/// which is also the outbound frame fragment ceiling.
pub const PLAINTEXT_LIMIT: usize = 16 * 1024;
/// Peer certificate chain depth accepted by the verifier.
pub const VERIFY_DEPTH: u32 = 4;
const ALERT_LIMIT: usize = 8;
const VERIFY_ERROR_LIMIT: usize = 8;
const DETAIL_LIMIT: usize = 512;

/// Explicit TLS 1.2 configurations. Only [`TlsProfile::Aead`] is the baseline.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TlsProfile {
    /// ECDHE-RSA with AES-GCM or ChaCha20-Poly1305, security level 2.
    Aead,
    /// Adds CBC-mode and static-RSA/DHE key exchange suites at security level 1. Not
    /// supported by evidence: select it only after a head unit's recorded ClientHello
    /// shows no baseline suite, and report it as the compatibility relaxation it is.
    CbcCompat,
}

impl TlsProfile {
    pub const ALL: [TlsProfile; 2] = [TlsProfile::Aead, TlsProfile::CbcCompat];
    pub fn name(self) -> &'static str {
        match self {
            TlsProfile::Aead => "tls12-aead",
            TlsProfile::CbcCompat => "tls12-cbc-compat",
        }
    }
    pub fn from_name(name: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|p| p.name() == name)
    }
    fn cipher_list(self) -> &'static str {
        match self {
            TlsProfile::Aead => {
                "ECDHE-RSA-AES128-GCM-SHA256:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-RSA-CHACHA20-POLY1305"
            }
            TlsProfile::CbcCompat => {
                "ECDHE-RSA-AES128-GCM-SHA256:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-RSA-CHACHA20-POLY1305:\
                 DHE-RSA-AES128-GCM-SHA256:DHE-RSA-AES256-GCM-SHA384:\
                 ECDHE-RSA-AES128-SHA256:ECDHE-RSA-AES256-SHA384:ECDHE-RSA-AES128-SHA:ECDHE-RSA-AES256-SHA:\
                 DHE-RSA-AES128-SHA256:DHE-RSA-AES256-SHA256:DHE-RSA-AES128-SHA:DHE-RSA-AES256-SHA:\
                 AES128-GCM-SHA256:AES256-GCM-SHA384:AES128-SHA256:AES256-SHA256:AES128-SHA:AES256-SHA"
            }
        }
    }
    fn security_level(self) -> u32 {
        match self {
            TlsProfile::Aead => 2,
            TlsProfile::CbcCompat => 1,
        }
    }
}

/// How the head unit's (TLS client's) certificate is judged (spec §7.4). Possession of
/// the matching private key is always verified by the handshake itself.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PeerPolicy {
    /// Accept only these SHA-256 leaf fingerprints (C1 lab pin, C3 repeat).
    Pinned(Vec<[u8; 32]>),
    /// Explicitly armed for this session only: accept any leaf and report its
    /// fingerprint so the caller can pin it afterwards (C2).
    FirstUse,
}

impl PeerPolicy {
    fn describe(&self) -> String {
        match self {
            PeerPolicy::Pinned(pins) => format!("pinned({})", pins.len()),
            PeerPolicy::FirstUse => "first-use-armed".into(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct ServerConfig {
    pub profile: TlsProfile,
    pub identity: Arc<Identity>,
    pub peer: PeerPolicy,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FailureKind {
    /// The peer sent a fatal alert (or close_notify).
    PeerAlert,
    /// This side sent a fatal alert for a reason other than those below.
    LocalAlert,
    NoCommonSuite,
    /// Our peer-certificate policy rejected the peer, or it sent no certificate.
    CertPolicy,
    /// Any other TLS or adapter failure.
    Error,
}

#[derive(Debug, Clone)]
pub struct Failure {
    pub kind: FailureKind,
    pub detail: String,
    /// Records produced while failing (normally our alert). During the handshake the
    /// session should still send them inside a 0x0003 message.
    pub output: Vec<u8>,
}

impl Failure {
    fn error(detail: impl Into<String>) -> Self {
        Self {
            kind: FailureKind::Error,
            detail: detail.into(),
            output: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AlertOrigin {
    Peer,
    Local,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Alert {
    pub origin: AlertOrigin,
    pub fatal: bool,
    pub code: u8,
}

impl fmt::Display for Alert {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}:{}:{}({})",
            match self.origin {
                AlertOrigin::Peer => "peer",
                AlertOrigin::Local => "local",
            },
            if self.fatal { "fatal" } else { "warning" },
            alert_name(self.code),
            self.code
        )
    }
}

/// RFC 5246 §7.2 and RFC 8446 §6 alert descriptions.
pub fn alert_name(code: u8) -> &'static str {
    match code {
        0 => "close_notify",
        10 => "unexpected_message",
        20 => "bad_record_mac",
        21 => "decryption_failed_reserved",
        22 => "record_overflow",
        30 => "decompression_failure",
        40 => "handshake_failure",
        41 => "no_certificate",
        42 => "bad_certificate",
        43 => "unsupported_certificate",
        44 => "certificate_revoked",
        45 => "certificate_expired",
        46 => "certificate_unknown",
        47 => "illegal_parameter",
        48 => "unknown_ca",
        49 => "access_denied",
        50 => "decode_error",
        60 => "export_restriction_reserved",
        70 => "protocol_version",
        71 => "insufficient_security",
        80 => "internal_error",
        86 => "inappropriate_fallback",
        90 => "user_canceled",
        100 => "no_renegotiation",
        109 => "missing_extension",
        110 => "unsupported_extension",
        112 => "unrecognized_name",
        116 => "certificate_required",
        120 => "no_application_protocol",
        _ => "unknown",
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PeerDecision {
    PinMatch([u8; 32]),
    PinMismatch([u8; 32]),
    FirstUseAccepted([u8; 32]),
}

impl PeerDecision {
    pub fn accepted(&self) -> bool {
        !matches!(self, PeerDecision::PinMismatch(_))
    }
    pub fn fingerprint(&self) -> [u8; 32] {
        match self {
            PeerDecision::PinMatch(f)
            | PeerDecision::PinMismatch(f)
            | PeerDecision::FirstUseAccepted(f) => *f,
        }
    }
    pub fn name(&self) -> &'static str {
        match self {
            PeerDecision::PinMatch(_) => "pin-match",
            PeerDecision::PinMismatch(_) => "pin-mismatch",
            PeerDecision::FirstUseAccepted(_) => "first-use-accepted",
        }
    }
}

/// Nonsecret summary of the peer leaf certificate.
#[derive(Debug, Clone)]
pub struct PeerCertificate {
    pub fingerprint: [u8; 32],
    pub subject: String,
    pub issuer: String,
    pub not_before: String,
    pub not_after: String,
    pub key: String,
    pub signature: String,
    /// Certificates the peer sent in addition to its leaf.
    pub chain_extra: usize,
}

/// State shared with OpenSSL callbacks, which run synchronously inside TLS calls.
#[derive(Default)]
struct Shared {
    decision: Option<PeerDecision>,
    verify_errors: Vec<(u32, i32, &'static str)>,
    alerts: Vec<Alert>,
}

fn lock(shared: &Mutex<Shared>) -> MutexGuard<'_, Shared> {
    shared.lock().unwrap_or_else(PoisonError::into_inner)
}

#[derive(Default)]
struct Memory {
    inbound: Vec<u8>,
    read: usize,
    outbound: Vec<u8>,
}

impl Read for Memory {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let available = &self.inbound[self.read..];
        if available.is_empty() {
            return Err(io::ErrorKind::WouldBlock.into());
        }
        let n = available.len().min(buf.len());
        buf[..n].copy_from_slice(&available[..n]);
        self.read += n;
        if self.read == self.inbound.len() {
            self.inbound.clear();
            self.read = 0;
        }
        Ok(n)
    }
}

impl Write for Memory {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.outbound.extend_from_slice(buf);
        Ok(buf.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl Memory {
    fn unread(&self) -> usize {
        self.inbound.len() - self.read
    }
}

extern "C" {
    // Declared here because openssl-sys does not bind it; resolved from the pinned libssl.
    fn SSL_set_info_callback(
        ssl: *mut openssl_sys::SSL,
        callback: Option<unsafe extern "C" fn(*const openssl_sys::SSL, c_int, c_int)>,
    );
}
const SSL_CB_READ: c_int = 0x04;
const SSL_CB_ALERT: c_int = 0x4000;

type SharedIndex = Index<Ssl, Arc<Mutex<Shared>>>;

fn shared_index() -> Result<SharedIndex, ErrorStack> {
    static INDEX: OnceLock<Result<SharedIndex, ErrorStack>> = OnceLock::new();
    INDEX.get_or_init(Ssl::new_ex_index).clone()
}

unsafe extern "C" fn info_callback(ssl: *const openssl_sys::SSL, place: c_int, value: c_int) {
    if place & SSL_CB_ALERT == 0 {
        return;
    }
    // A panic must not unwind into OpenSSL; recording an alert is best effort.
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: OpenSSL passes the live SSL object for the duration of the callback.
        let ssl = unsafe { SslRef::from_ptr(ssl as *mut openssl_sys::SSL) };
        let Ok(index) = shared_index() else { return };
        let Some(shared) = ssl.ex_data(index) else {
            return;
        };
        let mut shared = lock(shared);
        if shared.alerts.len() < ALERT_LIMIT {
            shared.alerts.push(Alert {
                origin: if place & SSL_CB_READ != 0 {
                    AlertOrigin::Peer
                } else {
                    AlertOrigin::Local
                },
                fatal: (value >> 8) & 0xff == 2,
                code: (value & 0xff) as u8,
            });
        }
    }));
}

/// Report-safe distinguished name: organization and location attributes are kept; other
/// attribute values (common name, serial number, e-mail) are replaced by their length,
/// because head-unit certificates may carry unit-specific identifiers.
pub(crate) fn describe_name(name: &X509NameRef) -> String {
    let mut out = String::new();
    for entry in name.entries() {
        let nid = entry.object().nid();
        let label = nid.short_name().unwrap_or("OID");
        let value = entry.data().to_string().unwrap_or_default();
        if !out.is_empty() {
            out.push(',');
        }
        let keep = [
            Nid::ORGANIZATIONNAME,
            Nid::ORGANIZATIONALUNITNAME,
            Nid::COUNTRYNAME,
            Nid::STATEORPROVINCENAME,
            Nid::LOCALITYNAME,
        ]
        .contains(&nid);
        if keep {
            let clean: String = value
                .chars()
                .map(|c| if c.is_control() || c == ',' { '_' } else { c })
                .take(64)
                .collect();
            let _ = write!(out, "{label}={clean}");
        } else {
            let _ = write!(out, "{label}=redacted({} chars)", value.chars().count());
        }
    }
    if out.is_empty() {
        out.push('-');
    }
    out
}

fn describe_certificate(cert: &X509Ref, chain_extra: usize) -> Option<PeerCertificate> {
    let digest = cert.digest(MessageDigest::sha256()).ok()?;
    let mut fingerprint = [0u8; 32];
    fingerprint.copy_from_slice(&digest);
    let key = cert.public_key().map_or_else(
        |_| "unreadable".into(),
        |k| {
            let kind = match k.id() {
                Id::RSA => "RSA".to_owned(),
                Id::RSA_PSS => "RSA-PSS".to_owned(),
                Id::EC => k
                    .ec_key()
                    .ok()
                    .and_then(|ec| ec.group().curve_name())
                    .and_then(|nid| nid.short_name().ok())
                    .map_or("EC".to_owned(), |curve| format!("EC-{curve}")),
                Id::ED25519 => "Ed25519".to_owned(),
                Id::ED448 => "Ed448".to_owned(),
                other => format!("pkey{}", other.as_raw()),
            };
            format!("{kind}-{}", k.bits())
        },
    );
    Some(PeerCertificate {
        fingerprint,
        subject: describe_name(cert.subject_name()),
        issuer: describe_name(cert.issuer_name()),
        not_before: cert.not_before().to_string(),
        not_after: cert.not_after().to_string(),
        key,
        signature: cert
            .signature_algorithm()
            .object()
            .nid()
            .long_name()
            .unwrap_or("unknown")
            .to_owned(),
        chain_extra,
    })
}

fn bounded(mut text: String) -> String {
    if text.len() > DETAIL_LIMIT {
        let mut end = DETAIL_LIMIT;
        while !text.is_char_boundary(end) {
            end -= 1;
        }
        text.truncate(end);
        text.push_str("...");
    }
    text.replace('\n', " ")
}

/// OpenSSL reason codes used for classification (ssl/sslerr.h, OpenSSL 3.5).
const SSL_R_NO_SHARED_CIPHER: c_int = 193;
const SSL_R_PEER_DID_NOT_RETURN_A_CERTIFICATE: c_int = 199;
/// Received-alert reasons are this offset plus the alert description.
const SSL_AD_REASON_OFFSET: c_int = 1000;

#[derive(Debug, Clone, Default)]
pub struct Counters {
    pub handshake_bytes_in: u64,
    pub handshake_bytes_out: u64,
    pub ciphertext_bytes_in: u64,
    pub ciphertext_bytes_out: u64,
    pub plaintext_bytes_in: u64,
    pub plaintext_bytes_out: u64,
    pub records_in: u64,
}

pub struct TlsServer {
    stream: SslStream<Memory>,
    shared: Arc<Mutex<Shared>>,
    profile: TlsProfile,
    policy: PeerPolicy,
    source_fingerprint: [u8; 32],
    hello: client_hello::Reader,
    records: RecordTracker,
    established: bool,
    failed: bool,
    version: Option<String>,
    cipher: Option<(String, [u8; 2])>,
    group: Option<String>,
    peer: Option<PeerCertificate>,
    counters: Counters,
}

impl TlsServer {
    pub fn new(config: &ServerConfig) -> Result<Self, Failure> {
        Self::build(config).map_err(|e| Failure::error(bounded(format!("TLS setup failed: {e}"))))
    }

    fn build(config: &ServerConfig) -> Result<Self, ErrorStack> {
        let shared = Arc::new(Mutex::new(Shared::default()));
        let mut builder = SslContext::builder(SslMethod::tls_server())?;
        builder.set_min_proto_version(Some(SslVersion::TLS1_2))?;
        builder.set_max_proto_version(Some(SslVersion::TLS1_2))?;
        builder.set_options(
            SslOptions::NO_TICKET
                | SslOptions::NO_RENEGOTIATION
                | SslOptions::CIPHER_SERVER_PREFERENCE
                | SslOptions::NO_COMPRESSION,
        );
        builder.set_session_cache_mode(SslSessionCacheMode::OFF);
        builder.set_security_level(config.profile.security_level());
        builder.set_cipher_list(config.profile.cipher_list())?;
        builder.set_groups_list("X25519:P-256:P-384")?;
        if config.profile == TlsProfile::CbcCompat {
            // SAFETY: the context pointer is valid for the builder's lifetime.
            unsafe { openssl_sys::SSL_CTX_set_dh_auto(builder.as_ptr(), 1) };
        }
        builder.set_certificate(config.identity.certificate())?;
        for certificate in config.identity.chain() {
            builder.add_extra_chain_cert(certificate.clone())?;
        }
        builder.set_private_key(config.identity.key())?;
        builder.check_private_key()?;
        let policy = config.peer.clone();
        let verify_shared = shared.clone();
        builder.set_verify_callback(
            SslVerifyMode::PEER | SslVerifyMode::FAIL_IF_NO_PEER_CERT,
            move |preverified, context| verify(&verify_shared, &policy, preverified, context),
        );
        builder.set_verify_depth(VERIFY_DEPTH);
        let context = builder.build();
        let mut ssl = Ssl::new(&context)?;
        ssl.set_ex_data(shared_index()?, shared.clone());
        ssl.set_accept_state();
        // SAFETY: the SSL object is valid; the callback only reads ex data set above.
        unsafe { SSL_set_info_callback(ssl.as_ptr(), Some(info_callback)) };
        let stream = SslStream::new(ssl, Memory::default())?;
        Ok(Self {
            stream,
            shared,
            profile: config.profile,
            policy: config.peer.clone(),
            source_fingerprint: config.identity.fingerprint(),
            hello: client_hello::Reader::new(),
            records: RecordTracker::default(),
            established: false,
            failed: false,
            version: None,
            cipher: None,
            group: None,
            peer: None,
            counters: Counters::default(),
        })
    }

    pub fn established(&self) -> bool {
        self.established
    }

    pub fn failed(&self) -> bool {
        self.failed
    }

    pub fn client_hello(&self) -> Option<&Result<ClientHello, String>> {
        self.hello.result()
    }

    pub fn decision(&self) -> Option<PeerDecision> {
        lock(&self.shared).decision.clone()
    }

    pub fn peer_certificate(&self) -> Option<&PeerCertificate> {
        self.peer.as_ref()
    }

    pub fn alerts(&self) -> Vec<Alert> {
        lock(&self.shared).alerts.clone()
    }

    pub fn counters(&self) -> &Counters {
        &self.counters
    }

    fn take_output(&mut self) -> Vec<u8> {
        take(&mut self.stream.get_mut().outbound)
    }

    fn fail(&mut self, kind: FailureKind, detail: String) -> Failure {
        self.failed = true;
        let output = self.take_output();
        self.counters.handshake_bytes_out += if self.established {
            0
        } else {
            output.len() as u64
        };
        Failure {
            kind,
            detail: bounded(detail),
            output,
        }
    }

    /// Feeds the bytes of one inbound 0x0003 message and advances the handshake.
    /// Returns records to send (possibly none) and whether the handshake is complete,
    /// meaning the peer's Finished has been verified.
    pub fn handshake(&mut self, input: &[u8]) -> Result<(Vec<u8>, bool), Failure> {
        if self.failed {
            return Err(Failure::error("TLS session already failed"));
        }
        if self.established {
            return Err(self.fail(
                FailureKind::Error,
                "handshake message after the handshake completed".into(),
            ));
        }
        self.hello.feed(input);
        self.counters.handshake_bytes_in += input.len() as u64;
        self.stream.get_mut().inbound.extend_from_slice(input);
        match self.stream.do_handshake() {
            Ok(()) => {
                self.complete()?;
                let output = self.take_output();
                self.counters.handshake_bytes_out += output.len() as u64;
                Ok((output, true))
            }
            Err(e) if e.code() == ErrorCode::WANT_READ => {
                let output = self.take_output();
                self.counters.handshake_bytes_out += output.len() as u64;
                Ok((output, false))
            }
            Err(e) => Err(self.classify(&e, "handshake")),
        }
    }

    /// Post-handshake checks that do not rely on OpenSSL invoking the verifier for the
    /// leaf: a decision was recorded for exactly the certificate the peer presented.
    fn complete(&mut self) -> Result<(), Failure> {
        let ssl = self.stream.ssl();
        let version = ssl.version_str().to_owned();
        let cipher = ssl.current_cipher().map(|c| {
            (
                c.standard_name().unwrap_or(c.name()).to_owned(),
                c.protocol_id(),
            )
        });
        // SAFETY: SSL_get0_group_name returns a static string or null for a live SSL.
        let group = unsafe {
            let name = openssl_sys::SSL_get0_group_name(ssl.as_ptr());
            (!name.is_null()).then(|| CStr::from_ptr(name).to_string_lossy().into_owned())
        };
        let chain_extra = ssl.peer_cert_chain().map_or(0, |c| c.len());
        let peer = ssl
            .peer_certificate()
            .and_then(|cert| describe_certificate(&cert, chain_extra));
        let unread = self.stream.get_ref().unread();
        self.version = Some(version.clone());
        self.cipher = cipher;
        self.group = group;
        self.peer = peer.clone();
        let decision = lock(&self.shared).decision.clone();
        let problem = match (&peer, &decision) {
            _ if version != "TLSv1.2" => Some(format!("negotiated {version}, not TLSv1.2")),
            (None, _) => Some("handshake completed without a peer certificate".into()),
            (Some(_), None) => Some("peer certificate was never judged by the policy".into()),
            (Some(p), Some(d)) if p.fingerprint != d.fingerprint() || !d.accepted() => {
                Some(format!(
                    "peer certificate {} does not match the accepted decision {}",
                    hex(&p.fingerprint),
                    d.name()
                ))
            }
            _ if unread != 0 => Some(format!(
                "{unread} bytes followed the peer Finished in the same handshake input"
            )),
            _ => None,
        };
        if let Some(problem) = problem {
            let mut failure = self.fail(FailureKind::CertPolicy, problem);
            // The output is our Finished flight: sending it would let the peer complete.
            failure.output.clear();
            return Err(failure);
        }
        self.established = true;
        Ok(())
    }

    fn classify(&mut self, error: &openssl::ssl::Error, during: &str) -> Failure {
        let (decision, alerts) = {
            let shared = lock(&self.shared);
            (shared.decision.clone(), shared.alerts.clone())
        };
        let reasons: Vec<c_int> = error
            .ssl_error()
            .map(|stack| stack.errors().iter().map(|e| e.reason_code()).collect())
            .unwrap_or_default();
        let mut detail = format!("{during} failed ({:?})", error.code());
        if let Some(stack) = error.ssl_error() {
            for e in stack.errors() {
                let _ = write!(detail, "; {}", e.reason().unwrap_or("unknown reason"));
            }
        } else if let Some(io) = error.io_error() {
            let _ = write!(detail, "; {io}");
        }
        let fatal = |origin| {
            alerts
                .iter()
                .find(|a| a.origin == origin && (a.fatal || a.code == 0))
                .copied()
        };
        let (kind, summary) = if let Some(d @ PeerDecision::PinMismatch(_)) = &decision {
            (
                FailureKind::CertPolicy,
                format!("peer certificate {} is not pinned", hex(&d.fingerprint())),
            )
        } else if reasons.contains(&SSL_R_PEER_DID_NOT_RETURN_A_CERTIFICATE) {
            (
                FailureKind::CertPolicy,
                "peer did not return a client certificate".into(),
            )
        } else if reasons.contains(&SSL_R_NO_SHARED_CIPHER) {
            (
                FailureKind::NoCommonSuite,
                format!(
                    "no cipher suite shared with profile {}",
                    self.profile.name()
                ),
            )
        } else if let Some(alert) = fatal(AlertOrigin::Peer).or_else(|| {
            reasons
                .iter()
                .find(|r| (SSL_AD_REASON_OFFSET..SSL_AD_REASON_OFFSET + 256).contains(r))
                .map(|r| Alert {
                    origin: AlertOrigin::Peer,
                    fatal: true,
                    code: (r - SSL_AD_REASON_OFFSET) as u8,
                })
        }) {
            (FailureKind::PeerAlert, format!("peer sent alert {alert}"))
        } else if error.code() == ErrorCode::ZERO_RETURN {
            (
                FailureKind::PeerAlert,
                "peer sent alert peer:warning:close_notify(0)".into(),
            )
        } else if let Some(alert) = fatal(AlertOrigin::Local) {
            (FailureKind::LocalAlert, format!("sent alert {alert}"))
        } else {
            (FailureKind::Error, "TLS error".into())
        };
        self.fail(kind, format!("{summary}: {detail}"))
    }

    /// Encrypts one frame's plaintext into complete TLS records.
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, Failure> {
        if !self.established || self.failed {
            return Err(Failure::error("encrypt before an established TLS session"));
        }
        if plaintext.is_empty() || plaintext.len() > PLAINTEXT_LIMIT {
            return Err(Failure::error(format!(
                "plaintext of {} bytes is outside 1..={PLAINTEXT_LIMIT}",
                plaintext.len()
            )));
        }
        match self.stream.ssl_write(plaintext) {
            Ok(n) if n == plaintext.len() => {}
            Ok(n) => {
                return Err(self.fail(
                    FailureKind::Error,
                    format!("SSL_write accepted {n} of {} bytes", plaintext.len()),
                ))
            }
            Err(e) => return Err(self.classify(&e, "encrypt")),
        }
        let output = self.take_output();
        if output.is_empty() || output.len() > u16::MAX as usize {
            return Err(self.fail(
                FailureKind::Error,
                format!("encryption produced {} bytes", output.len()),
            ));
        }
        self.counters.plaintext_bytes_out += plaintext.len() as u64;
        self.counters.ciphertext_bytes_out += output.len() as u64;
        Ok(output)
    }

    /// Decrypts one encrypted frame payload. Returns the plaintext of every record that
    /// is now complete, and whether the input so far ends on a record boundary. When it
    /// does not, the remaining plaintext arrives with a later frame.
    pub fn decrypt(&mut self, ciphertext: &[u8]) -> Result<(Vec<u8>, bool), Failure> {
        if !self.established || self.failed {
            return Err(Failure::error("decrypt before an established TLS session"));
        }
        if ciphertext.is_empty() {
            return Err(Failure::error("empty encrypted frame"));
        }
        if let Err(e) = self.records.feed(ciphertext) {
            return Err(self.fail(FailureKind::Error, e));
        }
        self.counters.ciphertext_bytes_in += ciphertext.len() as u64;
        self.stream.get_mut().inbound.extend_from_slice(ciphertext);
        let mut plaintext = Vec::new();
        let mut buffer = [0u8; PLAINTEXT_LIMIT];
        loop {
            match self.stream.ssl_read(&mut buffer) {
                Ok(n) => plaintext.extend_from_slice(&buffer[..n]),
                Err(e) if e.code() == ErrorCode::WANT_READ => break,
                Err(e) => return Err(self.classify(&e, "decrypt")),
            }
        }
        if !self.stream.get_ref().outbound.is_empty() {
            // Renegotiation refusal or another post-handshake message this session
            // cannot carry.
            let alerts = self.alerts();
            return Err(self.fail(
                FailureKind::LocalAlert,
                format!(
                    "unexpected TLS output after the handshake (alerts: {})",
                    alerts
                        .iter()
                        .map(Alert::to_string)
                        .collect::<Vec<_>>()
                        .join(";")
                ),
            ));
        }
        self.counters.records_in = self.records.records();
        self.counters.plaintext_bytes_in += plaintext.len() as u64;
        Ok((plaintext, self.records.at_boundary()))
    }

    pub fn summary(&self) -> Summary {
        let shared = lock(&self.shared);
        Summary {
            profile: self.profile,
            policy: self.policy.describe(),
            source_fingerprint: self.source_fingerprint,
            client_hello: self.hello.result().cloned(),
            established: self.established,
            version: self.version.clone(),
            cipher: self.cipher.clone(),
            group: self.group.clone(),
            decision: shared.decision.clone(),
            peer: self.peer.clone(),
            verify_errors: shared.verify_errors.clone(),
            alerts: shared.alerts.clone(),
            counters: self.counters.clone(),
        }
    }
}

fn verify(
    shared: &Mutex<Shared>,
    policy: &PeerPolicy,
    preverified: bool,
    context: &mut X509StoreContextRef,
) -> bool {
    let depth = context.error_depth();
    let mut shared = lock(shared);
    if !preverified && shared.verify_errors.len() < VERIFY_ERROR_LIMIT {
        shared.verify_errors.push((
            depth,
            context.error().as_raw(),
            context.error().error_string(),
        ));
    }
    // No CA store is used: only the leaf is judged, by fingerprint.
    if depth != 0 {
        return true;
    }
    let Some(fingerprint) = context
        .current_cert()
        .and_then(|cert| cert.digest(MessageDigest::sha256()).ok())
        .map(|digest| {
            let mut out = [0u8; 32];
            out.copy_from_slice(&digest);
            out
        })
    else {
        return false;
    };
    let decision = match policy {
        PeerPolicy::Pinned(pins) if pins.contains(&fingerprint) => {
            PeerDecision::PinMatch(fingerprint)
        }
        PeerPolicy::Pinned(_) => PeerDecision::PinMismatch(fingerprint),
        PeerPolicy::FirstUse => PeerDecision::FirstUseAccepted(fingerprint),
    };
    let accepted = decision.accepted();
    // A leaf reported with several verification errors is judged once per error; a
    // rejection is never overwritten.
    if shared.decision.as_ref().is_none_or(PeerDecision::accepted) {
        shared.decision = Some(decision);
    }
    if !accepted {
        context.set_error(X509VerifyResult::APPLICATION_VERIFICATION);
    }
    accepted
}

/// Everything the report needs about one TLS session.
#[derive(Debug, Clone)]
pub struct Summary {
    pub profile: TlsProfile,
    pub policy: String,
    pub source_fingerprint: [u8; 32],
    pub client_hello: Option<Result<ClientHello, String>>,
    pub established: bool,
    pub version: Option<String>,
    pub cipher: Option<(String, [u8; 2])>,
    pub group: Option<String>,
    pub decision: Option<PeerDecision>,
    pub peer: Option<PeerCertificate>,
    pub verify_errors: Vec<(u32, i32, &'static str)>,
    pub alerts: Vec<Alert>,
    pub counters: Counters,
}

impl fmt::Display for Summary {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fn opt(v: &Option<String>) -> &str {
            v.as_deref().unwrap_or("-")
        }
        writeln!(f, "tls_profile={}", self.profile.name())?;
        writeln!(f, "tls_peer_policy={}", self.policy)?;
        writeln!(
            f,
            "tls_source_fingerprint_sha256={}",
            hex(&self.source_fingerprint)
        )?;
        match &self.client_hello {
            None => writeln!(f, "tls_client_hello=-")?,
            Some(Err(e)) => writeln!(f, "tls_client_hello=undecodable: {e}")?,
            Some(Ok(hello)) => write!(f, "{hello}")?,
        }
        writeln!(f, "tls_established={}", self.established)?;
        writeln!(f, "tls_version={}", opt(&self.version))?;
        match &self.cipher {
            Some((name, id)) => writeln!(f, "tls_cipher={name} ({:02x}{:02x})", id[0], id[1])?,
            None => writeln!(f, "tls_cipher=-")?,
        }
        writeln!(f, "tls_group={}", opt(&self.group))?;
        match &self.decision {
            Some(d) => writeln!(
                f,
                "tls_peer_decision={} {}",
                d.name(),
                hex(&d.fingerprint())
            )?,
            None => writeln!(f, "tls_peer_decision=-")?,
        }
        match &self.peer {
            None => writeln!(f, "tls_peer_certificate=-")?,
            Some(p) => {
                writeln!(f, "tls_peer_fingerprint_sha256={}", hex(&p.fingerprint))?;
                writeln!(f, "tls_peer_subject={}", p.subject)?;
                writeln!(f, "tls_peer_issuer={}", p.issuer)?;
                writeln!(f, "tls_peer_validity={}..{}", p.not_before, p.not_after)?;
                writeln!(f, "tls_peer_key={}", p.key)?;
                writeln!(f, "tls_peer_signature={}", p.signature)?;
                writeln!(f, "tls_peer_chain_extra={}", p.chain_extra)?;
            }
        }
        write!(f, "tls_peer_verify_errors=")?;
        if self.verify_errors.is_empty() {
            write!(f, "-")?;
        }
        for (i, (depth, code, text)) in self.verify_errors.iter().enumerate() {
            write!(
                f,
                "{}depth{depth}:{code}:{text}",
                if i > 0 { ";" } else { "" }
            )?;
        }
        writeln!(f)?;
        write!(f, "tls_alerts=")?;
        if self.alerts.is_empty() {
            write!(f, "-")?;
        }
        for (i, alert) in self.alerts.iter().enumerate() {
            write!(f, "{}{alert}", if i > 0 { ";" } else { "" })?;
        }
        writeln!(f)?;
        let c = &self.counters;
        writeln!(
            f,
            "tls_handshake_bytes_in={} tls_handshake_bytes_out={} tls_ciphertext_bytes_in={} \
             tls_ciphertext_bytes_out={} tls_plaintext_bytes_in={} tls_plaintext_bytes_out={} tls_records_in={}",
            c.handshake_bytes_in,
            c.handshake_bytes_out,
            c.ciphertext_bytes_in,
            c.ciphertext_bytes_out,
            c.plaintext_bytes_in,
            c.plaintext_bytes_out,
            c.records_in
        )
    }
}
