//! Independent ClientHello decoder for diagnostics (spec §7.3: inspect the actual head
//! unit's offered suites and signature algorithms). It reads the handshake bytes the
//! peer sends inside 0x0003 messages, never influences negotiation, and works even when
//! this build stops before TLS. Only identifiers are kept; the random, session ID, and
//! any server name are not.
use std::fmt::{self, Write as _};

/// Record-stream bytes buffered while looking for the complete ClientHello.
pub const INPUT_LIMIT: usize = 64 * 1024;
const RECORD_HANDSHAKE: u8 = 22;
const HANDSHAKE_CLIENT_HELLO: u8 = 1;
/// Longest list kept per field; longer lists are truncated.
const LIST_LIMIT: usize = 128;

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ClientHello {
    pub record_version: u16,
    pub legacy_version: u16,
    pub session_id_len: usize,
    pub cipher_suites: Vec<u16>,
    pub compression_methods: Vec<u8>,
    /// Extension types in offered order.
    pub extensions: Vec<u16>,
    /// `supported_versions` (43), if offered.
    pub supported_versions: Vec<u16>,
    /// `signature_algorithms` (13), if offered.
    pub signature_algorithms: Vec<u16>,
    /// `supported_groups` (10), if offered.
    pub groups: Vec<u16>,
    /// `server_name` (0) offered; the name itself is not kept.
    pub server_name: bool,
    /// Handshake message body length.
    pub length: usize,
}

impl ClientHello {
    /// TLS 1.2 is offered: directly by the legacy version when no `supported_versions`
    /// extension is present, otherwise by that extension.
    pub fn offers_tls12(&self) -> bool {
        if self.extensions.contains(&43) {
            self.supported_versions.contains(&0x0303)
        } else {
            self.legacy_version >= 0x0303
        }
    }
}

fn hex16(f: &mut fmt::Formatter<'_>, values: &[u16]) -> fmt::Result {
    if values.is_empty() {
        return f.write_str("-");
    }
    let mut text = String::new();
    for (i, v) in values.iter().enumerate() {
        if i > 0 {
            text.push(',');
        }
        let _ = write!(text, "{v:04x}");
    }
    f.write_str(&text)
}

impl fmt::Display for ClientHello {
    /// `key=value` report lines, each prefixed `tls_client_hello_`.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(f, "tls_client_hello_length={}", self.length)?;
        writeln!(
            f,
            "tls_client_hello_record_version={:04x}",
            self.record_version
        )?;
        writeln!(
            f,
            "tls_client_hello_legacy_version={:04x}",
            self.legacy_version
        )?;
        write!(f, "tls_client_hello_supported_versions=")?;
        hex16(f, &self.supported_versions)?;
        writeln!(f)?;
        writeln!(f, "tls_client_hello_offers_tls12={}", self.offers_tls12())?;
        write!(f, "tls_client_hello_cipher_suites=")?;
        hex16(f, &self.cipher_suites)?;
        writeln!(f)?;
        write!(f, "tls_client_hello_signature_algorithms=")?;
        hex16(f, &self.signature_algorithms)?;
        writeln!(f)?;
        write!(f, "tls_client_hello_groups=")?;
        hex16(f, &self.groups)?;
        writeln!(f)?;
        write!(f, "tls_client_hello_extensions=")?;
        hex16(f, &self.extensions)?;
        writeln!(f)?;
        let compression: Vec<u16> = self.compression_methods.iter().map(|&m| m as u16).collect();
        write!(f, "tls_client_hello_compression=")?;
        hex16(f, &compression)?;
        writeln!(f)?;
        writeln!(f, "tls_client_hello_session_id_len={}", self.session_id_len)?;
        writeln!(f, "tls_client_hello_server_name={}", self.server_name)
    }
}

/// Accumulates peer handshake bytes until the first handshake message can be decoded.
#[derive(Debug, Default)]
pub struct Reader {
    input: Vec<u8>,
    handshake: Vec<u8>,
    record_version: Option<u16>,
    result: Option<Result<ClientHello, String>>,
}

impl Reader {
    pub fn new() -> Self {
        Self::default()
    }

    /// The decoded ClientHello, or why decoding failed; `None` while incomplete.
    pub fn result(&self) -> Option<&Result<ClientHello, String>> {
        self.result.as_ref()
    }

    pub fn feed(&mut self, bytes: &[u8]) {
        if self.result.is_some() {
            return;
        }
        if self.input.len() + bytes.len() > INPUT_LIMIT {
            self.result = Some(Err(format!(
                "no complete ClientHello within {INPUT_LIMIT} bytes"
            )));
            return;
        }
        self.input.extend_from_slice(bytes);
        if let Some(result) = self.advance() {
            self.result = Some(result);
            self.input = Vec::new();
            self.handshake = Vec::new();
        }
    }

    fn advance(&mut self) -> Option<Result<ClientHello, String>> {
        loop {
            if self.input.first().is_some_and(|b| b & 0x80 != 0) {
                return Some(Err("SSLv2-format record".into()));
            }
            if self.input.len() < 5 {
                return None;
            }
            let length = u16::from_be_bytes([self.input[3], self.input[4]]) as usize;
            if self.input.len() < 5 + length {
                return None;
            }
            if self.input[0] != RECORD_HANDSHAKE {
                return Some(Err(format!(
                    "record content type {} before a complete ClientHello",
                    self.input[0]
                )));
            }
            self.record_version
                .get_or_insert(u16::from_be_bytes([self.input[1], self.input[2]]));
            self.handshake.extend_from_slice(&self.input[5..5 + length]);
            self.input.drain(..5 + length);
            if self.handshake.len() >= 4 {
                if self.handshake[0] != HANDSHAKE_CLIENT_HELLO {
                    return Some(Err(format!(
                        "first handshake message type is {}, not ClientHello",
                        self.handshake[0]
                    )));
                }
                let body = u32::from_be_bytes([
                    0,
                    self.handshake[1],
                    self.handshake[2],
                    self.handshake[3],
                ]) as usize;
                if self.handshake.len() >= 4 + body {
                    let record_version = self.record_version.unwrap_or_default();
                    return Some(parse_body(&self.handshake[4..4 + body]).map(|mut hello| {
                        hello.record_version = record_version;
                        hello
                    }));
                }
            }
        }
    }
}

struct Cursor<'a> {
    bytes: &'a [u8],
    what: &'static str,
}

impl<'a> Cursor<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], String> {
        if self.bytes.len() < n {
            return Err(format!("ClientHello truncated in {}", self.what));
        }
        let (head, tail) = self.bytes.split_at(n);
        self.bytes = tail;
        Ok(head)
    }
    fn u8(&mut self) -> Result<u8, String> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16, String> {
        let b = self.take(2)?;
        Ok(u16::from_be_bytes([b[0], b[1]]))
    }
    fn vec8(&mut self) -> Result<&'a [u8], String> {
        let n = self.u8()? as usize;
        self.take(n)
    }
    fn vec16(&mut self) -> Result<&'a [u8], String> {
        let n = self.u16()? as usize;
        self.take(n)
    }
}

fn u16_list(bytes: &[u8], what: &'static str) -> Result<Vec<u16>, String> {
    if bytes.len() % 2 != 0 {
        return Err(format!("odd-length {what} list"));
    }
    Ok(bytes
        .chunks_exact(2)
        .take(LIST_LIMIT)
        .map(|c| u16::from_be_bytes([c[0], c[1]]))
        .collect())
}

fn parse_body(body: &[u8]) -> Result<ClientHello, String> {
    let mut c = Cursor {
        bytes: body,
        what: "version",
    };
    let mut hello = ClientHello {
        length: body.len(),
        legacy_version: c.u16()?,
        ..ClientHello::default()
    };
    c.what = "random";
    c.take(32)?;
    c.what = "session_id";
    hello.session_id_len = c.vec8()?.len();
    c.what = "cipher_suites";
    hello.cipher_suites = u16_list(c.vec16()?, "cipher suite")?;
    c.what = "compression_methods";
    hello.compression_methods = c.vec8()?.iter().copied().take(LIST_LIMIT).collect();
    if c.bytes.is_empty() {
        return Ok(hello);
    }
    c.what = "extensions";
    let mut extensions = Cursor {
        bytes: c.vec16()?,
        what: "extension",
    };
    if !c.bytes.is_empty() {
        return Err("trailing bytes after ClientHello extensions".into());
    }
    while !extensions.bytes.is_empty() {
        let kind = extensions.u16()?;
        let data = extensions.vec16()?;
        if hello.extensions.len() < LIST_LIMIT {
            hello.extensions.push(kind);
        }
        let mut field = Cursor {
            bytes: data,
            what: "extension data",
        };
        match kind {
            0 => hello.server_name = true,
            10 => hello.groups = u16_list(field.vec16()?, "supported_groups")?,
            13 => hello.signature_algorithms = u16_list(field.vec16()?, "signature_algorithms")?,
            43 => hello.supported_versions = u16_list(field.vec8()?, "supported_versions")?,
            _ => {}
        }
    }
    Ok(hello)
}
