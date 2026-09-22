//! TLS for the source role (spec §7.3–7.4). The phone is the TLS 1.2 server and the head
//! unit the client; handshake records travel in plaintext 0x0003 messages, and afterwards
//! each frame payload is encrypted through the same session. OpenSSL is the pinned LTS
//! build linked statically (tools/openssl-build.sh); Android's platform TLS is never used.
//! No I/O happens here and nothing is shared between sessions.
pub mod client_hello;
pub mod identity;
pub mod records;
mod selftest;
mod server;

pub use identity::Identity;

/// The linked OpenSSL's version text, for reports (expected: the pinned 3.5 LTS build).
pub fn library_version() -> &'static str {
    openssl::version::version()
}
pub use selftest::self_test;
pub use server::{
    alert_name, Alert, AlertOrigin, Counters, Failure, FailureKind, PeerCertificate, PeerDecision,
    PeerPolicy, ServerConfig, Summary, TlsProfile, TlsServer, PLAINTEXT_LIMIT, VERIFY_DEPTH,
};
