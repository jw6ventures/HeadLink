//! Binary version exchange (spec §7.2). Profiles are explicit; nothing is inferred
//! from upstream enums. See docs/references.md for the audited evidence.
use std::fmt;

pub const VERSION_REQUEST: u16 = 0x0001;
pub const VERSION_RESPONSE: u16 = 0x0002;
pub const SSL_HANDSHAKE: u16 = 0x0003;
/// VERSION_RESPONSE status MATCH. MISMATCH is never sent by the legacy profile.
pub const STATUS_MATCH: u16 = 0x0000;
/// Raw requests at or above this version gain a WireConfig response extension in
/// the audited phone behavior; no profile here implements that extension yet.
pub const WIRE_CONFIG_GATE: Version = Version { major: 1, minor: 6 };
/// What Android Auto 17.6 answers a request at or above the gate with, observed on this
/// vehicle; [`Profile::Match17`] mirrors it.
pub const ANDROID_AUTO_ANSWER: Version = Version { major: 1, minor: 7 };

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Version {
    pub major: u16,
    pub minor: u16,
}
impl fmt::Display for Version {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}.{}", self.major, self.minor)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Profile {
    /// Responds 1.5 MATCH to a fixed four-byte 1.0–1.5 request, as the AACS source
    /// does for legacy major-1 head units. Everything else is unsupported.
    Legacy15,
    /// Also answers four-byte major-1 requests at or above the WireConfig gate with
    /// 1.5 MATCH and no WireConfig, as AACS does for any major-1 request. Not supported
    /// by evidence: select it only after a head unit's recorded request was refused by
    /// `legacy-1.5` for that reason, and report it as the relaxation it is.
    Legacy15AnyMinor,
    /// Answers 1.5 MATCH below the WireConfig gate, as `legacy-1.5` does, and
    /// [`WIRE_CONFIG_GATE`] MATCH at or above it, so a head unit that asks for 1.6 is
    /// answered at 1.6 instead of being downgraded. No WireConfig extension is appended;
    /// an empty `ControlChannelConfigWrapper` is zero bytes, so this is the same wire
    /// response a phone sends when it has nothing to configure, but it is *not* the
    /// audited populated extension and the parameters it would carry are unknown.
    ///
    /// Investigative: Ford SYNC 4.0 requests 1.6, is answered 1.5 by both legacy
    /// profiles, and then advertises no `resize_actions` or `margin_configs` in any
    /// video configuration, which is what withholds its full-width layout. Select this
    /// to record whether a 1.6 answer changes the discovery response, and report it as
    /// the unaudited guess it is.
    Match16,
    /// Answers 1.5 MATCH below the [`WIRE_CONFIG_GATE`] and **1.7** at or above it, which
    /// is what Google's Android Auto 17.6 does: against this vehicle it answered Ford's
    /// 1.6 request with 1.7 and Ford accepted it (`negotiated protocol version
    /// jaj[majorVersion=1, minorVersion=7] (STATUS_SUCCESS)`, logcat 2026-09-18 12:01:47).
    /// No WireConfig extension is appended, and Android Auto may append one at this
    /// version; that part is unmatched.
    ///
    /// The reason to claim it: Ford only opens the mid-stream resize conversation
    /// (`UPDATE_UI_CONFIG_REQUEST`) for configurations above 800x480, and 800x480 is the
    /// only one this source has ever displayed. A version answer is the clearest
    /// difference left between this source and the client that works (docs/references.md).
    Match17,
}
impl Profile {
    pub const ALL: [Profile; 4] = [
        Profile::Legacy15,
        Profile::Legacy15AnyMinor,
        Profile::Match16,
        Profile::Match17,
    ];
    pub fn name(self) -> &'static str {
        match self {
            Profile::Legacy15 => "legacy-1.5",
            Profile::Legacy15AnyMinor => "legacy-1.5-any-minor",
            Profile::Match16 => "match-1.6",
            Profile::Match17 => "match-1.7",
        }
    }
    pub fn from_name(name: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|p| p.name() == name)
    }
    /// `body` is the message after its two-byte ID.
    pub fn respond(self, body: &[u8]) -> Result<Version, Unsupported> {
        let requested = parse_request(body).ok_or(Unsupported::BodyLength(body.len()))?;
        if body.len() != 4 {
            return Err(Unsupported::Extension(body.len() - 4));
        }
        match self {
            _ if requested.major != 1 => Err(Unsupported::Major(requested)),
            Profile::Legacy15 if requested >= WIRE_CONFIG_GATE => {
                Err(Unsupported::WireConfigGate(requested))
            }
            Profile::Match16 if requested >= WIRE_CONFIG_GATE => Ok(WIRE_CONFIG_GATE),
            Profile::Match17 if requested >= WIRE_CONFIG_GATE => Ok(ANDROID_AUTO_ANSWER),
            Profile::Legacy15 | Profile::Legacy15AnyMinor | Profile::Match16 | Profile::Match17 => {
                Ok(Version { major: 1, minor: 5 })
            }
        }
    }
}

/// Major/minor from a request body, ignoring any trailing extension bytes.
pub fn parse_request(body: &[u8]) -> Option<Version> {
    let b = body.get(..4)?;
    Some(Version {
        major: u16::from_be_bytes([b[0], b[1]]),
        minor: u16::from_be_bytes([b[2], b[3]]),
    })
}

/// Complete plaintext message bytes, including the message ID.
pub fn encode_response(version: Version) -> Vec<u8> {
    [VERSION_RESPONSE, version.major, version.minor, STATUS_MATCH]
        .iter()
        .flat_map(|v| v.to_be_bytes())
        .collect()
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Unsupported {
    BodyLength(usize),
    Extension(usize),
    Major(Version),
    WireConfigGate(Version),
}
impl fmt::Display for Unsupported {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Unsupported::BodyLength(n) => write!(f, "request body is {n} bytes; expected 4"),
            Unsupported::Extension(n) => write!(
                f,
                "request carries {n} extension bytes; no profile defines them"
            ),
            Unsupported::Major(v) => write!(f, "requested {v}; only major 1 is supported"),
            Unsupported::WireConfigGate(v) => write!(
                f,
                "requested {v} is at or above the {WIRE_CONFIG_GATE} WireConfig gate; \
                 legacy-1.5 answers only below it, legacy-1.5-any-minor downgrades to 1.5, \
                 match-1.6 answers {WIRE_CONFIG_GATE}, and match-1.7 answers \
                 {ANDROID_AUTO_ANSWER} as Android Auto does, all without the extension"
            ),
        }
    }
}
