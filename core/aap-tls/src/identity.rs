//! Locally generated source (TLS server) identity, spec §7.4: RSA-2048, SHA-256 signed,
//! self-signed non-CA leaf for server authentication with a neutral project subject,
//! or an operator-supplied source identity and ordered certificate chain.
//! It is a prototype identity, not an identity-management design. The certificate and
//! private key are stored together in one app-private file so a replacement is atomic.
use openssl::{
    asn1::{Asn1Integer, Asn1Time},
    bn::{BigNum, MsbOption},
    hash::MessageDigest,
    nid::Nid,
    pkey::{Id, PKey, Private},
    rsa::Rsa,
    x509::{
        extension::{BasicConstraints, ExtendedKeyUsage, KeyUsage, SubjectKeyIdentifier},
        X509NameBuilder, X509,
    },
};
use std::{
    fmt::{self, Write as _},
    fs,
    io::{self, Write as _},
    os::unix::fs::OpenOptionsExt,
    path::Path,
};

pub const KEY_BITS: u32 = 2048;
pub const SUBJECT_COMMON_NAME: &str = "OpenAutoAndroid source";
pub const SUBJECT_ORGANIZATION: &str = "OpenAutoAndroid project";
/// Validity starts at a fixed past date rather than at generation, so a head unit whose
/// clock lags (no network time) does not see a not-yet-valid certificate. Design choice;
/// see docs/references.md. 2024-01-01T00:00:00Z.
pub const NOT_BEFORE_UNIX: i64 = 1_704_067_200;
/// 2049-01-01T00:00:00Z, the last year encodable as UTCTime.
pub const NOT_AFTER_UNIX: i64 = 2_493_072_000;
/// File holding the PEM certificate followed by the PEM PKCS#8 private key.
pub const FILE_NAME: &str = "source-identity.pem";
pub const PEM_LIMIT: usize = 64 * 1024;

#[derive(Debug)]
pub struct Error(pub String);
impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}
impl std::error::Error for Error {}
impl From<openssl::error::ErrorStack> for Error {
    fn from(e: openssl::error::ErrorStack) -> Self {
        Error(format!("openssl: {e}"))
    }
}

/// Lowercase hex without separators (fingerprints are SHA-256 over DER).
pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().fold(String::new(), |mut out, b| {
        let _ = write!(out, "{b:02x}");
        out
    })
}

/// A random (version 4) lowercase hyphenated UUID from OpenSSL's CSPRNG, for a fresh
/// session UUID where no platform generator is available (the lab harness).
pub fn random_uuid() -> Result<String, Error> {
    let mut bytes = [0u8; 16];
    openssl::rand::rand_bytes(&mut bytes)?;
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let hex = hex(&bytes);
    Ok(format!(
        "{}-{}-{}-{}-{}",
        &hex[..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..]
    ))
}

/// Parses a 64-character hex SHA-256 fingerprint.
pub fn parse_fingerprint(text: &str) -> Option<[u8; 32]> {
    if text.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for (i, chunk) in text.as_bytes().chunks(2).enumerate() {
        out[i] = u8::from_str_radix(std::str::from_utf8(chunk).ok()?, 16).ok()?;
    }
    Some(out)
}

pub struct Identity {
    certificate: X509,
    chain: Vec<X509>,
    key: PKey<Private>,
    fingerprint: [u8; 32],
}

impl fmt::Debug for Identity {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        // Never prints key material.
        write!(f, "Identity({})", hex(&self.fingerprint))
    }
}

impl Identity {
    pub fn generate() -> Result<Self, Error> {
        let key = PKey::from_rsa(Rsa::generate(KEY_BITS)?)?;
        let mut name = X509NameBuilder::new()?;
        name.append_entry_by_nid(Nid::COMMONNAME, SUBJECT_COMMON_NAME)?;
        name.append_entry_by_nid(Nid::ORGANIZATIONNAME, SUBJECT_ORGANIZATION)?;
        let name = name.build();
        let mut serial = BigNum::new()?;
        // 127 random bits with the top bit clear: positive and at most 16 bytes.
        serial.rand(127, MsbOption::MAYBE_ZERO, false)?;
        let mut builder = X509::builder()?;
        builder.set_version(2)?;
        let serial = Asn1Integer::from_bn(&serial)?;
        builder.set_serial_number(&serial)?;
        builder.set_subject_name(&name)?;
        builder.set_issuer_name(&name)?;
        let (not_before, not_after) = (
            Asn1Time::from_unix(NOT_BEFORE_UNIX)?,
            Asn1Time::from_unix(NOT_AFTER_UNIX)?,
        );
        builder.set_not_before(&not_before)?;
        builder.set_not_after(&not_after)?;
        builder.set_pubkey(&key)?;
        builder.append_extension(BasicConstraints::new().critical().build()?)?;
        builder.append_extension(
            KeyUsage::new()
                .critical()
                .digital_signature()
                .key_encipherment()
                .build()?,
        )?;
        builder.append_extension(ExtendedKeyUsage::new().server_auth().build()?)?;
        let subject_key_id =
            SubjectKeyIdentifier::new().build(&builder.x509v3_context(None, None))?;
        builder.append_extension(subject_key_id)?;
        builder.sign(&key, MessageDigest::sha256())?;
        Self::checked(builder.build(), key)
    }

    fn checked(certificate: X509, key: PKey<Private>) -> Result<Self, Error> {
        if key.id() != Id::RSA || key.bits() != KEY_BITS {
            return Err(Error(format!(
                "identity key is not RSA-{KEY_BITS} ({:?}, {} bits)",
                key.id(),
                key.bits()
            )));
        }
        if !certificate.public_key()?.public_eq(&key) {
            return Err(Error(
                "identity certificate does not match its private key".into(),
            ));
        }
        let algorithm = certificate.signature_algorithm().object().nid();
        if algorithm != Nid::SHA256WITHRSAENCRYPTION {
            return Err(Error(format!(
                "identity certificate signature is {}, not sha256WithRSAEncryption",
                algorithm.short_name().unwrap_or("unknown")
            )));
        }
        let digest = certificate.digest(MessageDigest::sha256())?;
        let mut fingerprint = [0u8; 32];
        fingerprint.copy_from_slice(&digest);
        Ok(Self {
            certificate,
            chain: Vec::new(),
            key,
            fingerprint,
        })
    }

    /// Certificate PEM followed by PKCS#8 private-key PEM, as written by [`Identity::save`].
    pub fn from_pem(pem: &[u8]) -> Result<Self, Error> {
        if pem.len() > PEM_LIMIT {
            return Err(Error("identity PEM exceeds 64 KiB".into()));
        }
        // No password callback or terminal prompt is available in the Android process.
        if pem.windows(b"ENCRYPTED".len()).any(|w| w == b"ENCRYPTED") {
            return Err(Error("encrypted identity PEM is unsupported".into()));
        }
        let mut certificates = X509::stack_from_pem(pem)?;
        if certificates.is_empty() || certificates.len() > 8 {
            return Err(Error(
                "identity requires a leaf and at most seven chain certificates".into(),
            ));
        }
        let now = Asn1Time::days_from_now(0)?;
        for certificate in &certificates {
            if certificate.not_before() > now || certificate.not_after() < now {
                return Err(Error("identity certificate is not currently valid".into()));
            }
        }
        let certificate = certificates.remove(0);
        let key = PKey::private_key_from_pem(pem)?;
        let mut identity = Self::checked(certificate, key)?;
        let mut child = &identity.certificate;
        for issuer in &certificates {
            let issuer_key = issuer.public_key()?;
            if child.issuer_name().to_der()? != issuer.subject_name().to_der()?
                || !child.verify(&issuer_key)?
            {
                return Err(Error(
                    "identity chain is not in verified leaf-to-issuer order".into(),
                ));
            }
            child = issuer;
        }
        identity.chain = certificates;
        Ok(identity)
    }

    pub fn certificate(&self) -> &X509 {
        &self.certificate
    }

    pub fn chain(&self) -> &[X509] {
        &self.chain
    }

    pub(crate) fn key(&self) -> &PKey<Private> {
        &self.key
    }

    pub fn fingerprint(&self) -> [u8; 32] {
        self.fingerprint
    }

    pub fn fingerprint_hex(&self) -> String {
        hex(&self.fingerprint)
    }

    /// Nonsecret `key=value` report lines.
    pub fn summary(&self) -> String {
        format!(
            "source_identity_fingerprint_sha256={}\nsource_identity_subject={}\n\
             source_identity_issuer={}\nsource_identity_self_signed={}\nsource_identity_chain_extra={}\n\
             source_identity_validity={}..{}\nsource_identity_key=RSA-{}\n",
            self.fingerprint_hex(),
            crate::server::describe_name(self.certificate.subject_name()),
            crate::server::describe_name(self.certificate.issuer_name()),
            self.certificate.subject_name().to_der().ok() == self.certificate.issuer_name().to_der().ok()
                && self.certificate.verify(&self.key).unwrap_or(false),
            self.chain.len(),
            self.certificate.not_before(),
            self.certificate.not_after(),
            self.key.bits(),
        )
    }

    /// Loads `dir/source-identity.pem`.
    pub fn load(dir: &Path) -> Result<Self, Error> {
        let path = dir.join(FILE_NAME);
        if fs::metadata(&path).map_err(|e| Error(e.to_string()))?.len() > PEM_LIMIT as u64 {
            return Err(Error("identity PEM exceeds 64 KiB".into()));
        }
        let pem = fs::read(&path).map_err(|e| Error(format!("{}: {e}", path.display())))?;
        Self::from_pem(&pem).map_err(|e| Error(format!("{}: {e}", path.display())))
    }

    /// Writes the identity with owner-only permissions, atomically replacing any file.
    pub fn save(&self, dir: &Path) -> Result<(), Error> {
        let io = |e: io::Error| Error(format!("{}: {e}", dir.display()));
        fs::create_dir_all(dir).map_err(io)?;
        let mut pem = self.certificate.to_pem()?;
        for certificate in &self.chain {
            pem.extend(certificate.to_pem()?);
        }
        pem.extend(self.key.private_key_to_pem_pkcs8()?);
        let temporary = dir.join(format!("{FILE_NAME}.tmp"));
        let _ = fs::remove_file(&temporary);
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&temporary)
            .map_err(io)?;
        file.write_all(&pem)
            .and_then(|()| file.sync_all())
            .map_err(io)?;
        drop(file);
        fs::rename(&temporary, dir.join(FILE_NAME)).map_err(io)
    }

    /// Loads the stored identity, or generates and saves one if none exists. A present
    /// but unreadable identity is an error: replacing it silently would change the
    /// identity a head unit may already have accepted. Returns whether it was created.
    pub fn load_or_create(dir: &Path) -> Result<(Self, bool), Error> {
        if dir.join(FILE_NAME).exists() {
            return Self::load(dir).map(|identity| (identity, false));
        }
        let identity = Self::generate()?;
        identity.save(dir)?;
        Ok((identity, true))
    }
}
