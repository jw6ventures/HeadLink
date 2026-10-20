//! In-process TLS self-test for packaging evidence (G0): the linked OpenSSL and the
//! stored source identity complete a TLS 1.2 handshake and an encrypted round trip with a
//! local OpenSSL client holding an ephemeral P-256 identity. It proves nothing about any
//! head unit.
use crate::{Identity, PeerPolicy, ServerConfig, TlsProfile, TlsServer};
use openssl::{
    asn1::Asn1Time,
    bn::BigNum,
    ec::{EcGroup, EcKey},
    error::ErrorStack,
    hash::MessageDigest,
    nid::Nid,
    pkey::PKey,
    ssl::{ErrorCode, Ssl, SslContext, SslMethod, SslStream, SslVerifyMode},
    x509::{X509NameBuilder, X509},
};
use std::{
    io::{self, Read, Write},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
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

fn client(
    identity: &Identity,
    server_matched: Arc<AtomicBool>,
) -> Result<(SslStream<Pipe>, [u8; 32]), ErrorStack> {
    let group = EcGroup::from_curve_name(Nid::X9_62_PRIME256V1)?;
    let key = PKey::from_ec_key(EcKey::generate(&group)?)?;
    let mut name = X509NameBuilder::new()?;
    name.append_entry_by_nid(Nid::COMMONNAME, "HeadLink self-test client")?;
    let name = name.build();
    let mut builder = X509::builder()?;
    builder.set_version(2)?;
    let serial = BigNum::from_u32(1)?.to_asn1_integer()?;
    builder.set_serial_number(&serial)?;
    builder.set_subject_name(&name)?;
    builder.set_issuer_name(&name)?;
    let (not_before, not_after) = (Asn1Time::days_from_now(0)?, Asn1Time::days_from_now(1)?);
    builder.set_not_before(&not_before)?;
    builder.set_not_after(&not_after)?;
    builder.set_pubkey(&key)?;
    builder.sign(&key, MessageDigest::sha256())?;
    let cert = builder.build();
    let mut fingerprint = [0u8; 32];
    fingerprint.copy_from_slice(&cert.digest(MessageDigest::sha256())?);

    let expected = identity.fingerprint();
    let mut context = SslContext::builder(SslMethod::tls_client())?;
    context.set_verify_callback(SslVerifyMode::PEER, move |_, store| {
        if store.error_depth() != 0 {
            return true;
        }
        let matched = store
            .current_cert()
            .and_then(|c| c.digest(MessageDigest::sha256()).ok())
            .is_some_and(|d| d.as_ref() == expected);
        server_matched.store(matched, Ordering::SeqCst);
        matched
    });
    context.set_certificate(&cert)?;
    context.set_private_key(&key)?;
    let mut ssl = Ssl::new(&context.build())?;
    ssl.set_connect_state();
    Ok((SslStream::new(ssl, Pipe::default())?, fingerprint))
}

/// Runs the self-test; on success returns a one-line summary of the negotiation.
pub fn self_test(identity: Arc<Identity>) -> Result<String, String> {
    let matched = Arc::new(AtomicBool::new(false));
    let (mut client, client_fingerprint) =
        client(&identity, matched.clone()).map_err(|e| format!("client setup: {e}"))?;
    let mut server = TlsServer::new(&ServerConfig {
        profile: TlsProfile::Aead,
        identity,
        peer: PeerPolicy::Pinned(vec![client_fingerprint]),
    })
    .map_err(|f| f.detail)?;
    let mut done = false;
    for _ in 0..8 {
        match client.do_handshake() {
            Ok(()) if done => break,
            Ok(()) => {}
            Err(e) if e.code() == ErrorCode::WANT_READ => {}
            Err(e) => return Err(format!("client handshake: {e}")),
        }
        let flight = std::mem::take(&mut client.get_mut().outbound);
        if flight.is_empty() {
            continue;
        }
        let (output, complete) = server.handshake(&flight).map_err(|f| f.detail)?;
        client.get_mut().inbound.extend(output);
        done = complete;
    }
    if !done || !client.ssl().is_init_finished() {
        return Err("handshake did not complete".into());
    }
    if !matched.load(Ordering::SeqCst) {
        return Err("client did not see the stored source certificate".into());
    }
    let probe = b"\x00\x0bself-test";
    let ciphertext = server.encrypt(probe).map_err(|f| f.detail)?;
    client.get_mut().inbound.extend(ciphertext);
    let mut buffer = [0u8; 64];
    let n = client
        .ssl_read(&mut buffer)
        .map_err(|e| format!("client read: {e}"))?;
    if &buffer[..n] != probe {
        return Err("server-to-client plaintext mismatch".into());
    }
    client
        .ssl_write(probe)
        .map_err(|e| format!("client write: {e}"))?;
    let reply = std::mem::take(&mut client.get_mut().outbound);
    let (plaintext, boundary) = server.decrypt(&reply).map_err(|f| f.detail)?;
    if plaintext != probe || !boundary {
        return Err("client-to-server plaintext mismatch".into());
    }
    let summary = server.summary();
    Ok(format!(
        "version={} cipher={} group={}",
        summary.version.unwrap_or_default(),
        summary.cipher.map(|c| c.0).unwrap_or_default(),
        summary.group.unwrap_or_default()
    ))
}
