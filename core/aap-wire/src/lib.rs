//! Strict spec v0.1 wire profile. No protocol-state or TLS policy is inferred here.
//! All errors are fatal to the current stream; create fresh state for a new attempt.
use std::{
    collections::BTreeMap,
    fmt, io,
    time::{Duration, Instant},
};

pub const OUTBOUND_FRAGMENT: usize = 16 * 1024;
pub const WIRE_PAYLOAD: usize = u16::MAX as usize;
pub const CONTROL_LIMIT: usize = 1024 * 1024;
pub const AV_LIMIT: usize = 4 * 1024 * 1024;
pub const AGGREGATE_LIMIT: usize = 8 * 1024 * 1024;
pub const READ_BUFFER: usize = 64 * 1024;
pub const ASSEMBLY_LIFETIME: Duration = Duration::from_secs(10);

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    ReservedFlags(u8),
    InvalidTotal,
    EmptyPayload,
    WireTooLarge,
    Truncated,
    MissingStart(u8),
    DuplicateStart(u8),
    MetadataChanged(u8),
    MessageTooShort,
    MessageTooLarge,
    AggregateLimit,
    Expired(u8),
    Failed,
}
impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "FRAME_INVALID: {self:?}")
    }
}
impl std::error::Error for Error {}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Header {
    pub channel: u8,
    pub flags: u8,
    pub total: Option<u32>,
}
impl Header {
    pub fn kind(self) -> u8 {
        self.flags & 3
    }
    pub fn encrypted(self) -> bool {
        self.flags & 8 != 0
    }
    fn validate(self) -> Result<(), Error> {
        if self.flags & 0xf0 != 0 {
            return Err(Error::ReservedFlags(self.flags));
        }
        if (self.kind() == 1) != self.total.is_some() || self.total.is_some_and(|n| n < 2) {
            return Err(Error::InvalidTotal);
        }
        Ok(())
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub header: Header,
    pub payload: Vec<u8>,
}
impl Frame {
    /// Payload is wire bytes (ciphertext when encrypted). Never truncates the length.
    pub fn encode(&self) -> Result<Vec<u8>, Error> {
        self.header.validate()?;
        if self.payload.is_empty() {
            return Err(Error::EmptyPayload);
        }
        let length = u16::try_from(self.payload.len()).map_err(|_| Error::WireTooLarge)?;
        let mut out = Vec::with_capacity(8 + self.payload.len());
        out.extend([self.header.channel, self.header.flags]);
        out.extend(length.to_be_bytes());
        if let Some(total) = self.header.total {
            out.extend(total.to_be_bytes());
        }
        out.extend(&self.payload);
        Ok(out)
    }
}

/// Retains at most one frame, regardless of input size. Returns consumed bytes and
/// at most one frame so callers can decrypt immediately in physical receive order.
#[derive(Default)]
pub struct Parser {
    buffer: Vec<u8>,
    failed: bool,
}
impl Parser {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn buffered_len(&self) -> usize {
        self.buffer.len()
    }
    pub fn consume(&mut self, input: &[u8]) -> Result<(usize, Option<Frame>), Error> {
        if self.failed {
            return Err(Error::Failed);
        }
        let result = self.consume_inner(input);
        if result.is_err() {
            self.failed = true;
            self.buffer.clear();
        }
        result
    }
    fn consume_inner(&mut self, input: &[u8]) -> Result<(usize, Option<Frame>), Error> {
        let mut used = 0;
        loop {
            let target = if self.buffer.len() < 4 {
                4
            } else {
                let flags = self.buffer[1];
                if flags & 0xf0 != 0 {
                    return Err(Error::ReservedFlags(flags));
                }
                let len = u16::from_be_bytes([self.buffer[2], self.buffer[3]]) as usize;
                if len == 0 {
                    return Err(Error::EmptyPayload);
                }
                if flags & 3 == 1 {
                    if self.buffer.len() < 8 {
                        8
                    } else {
                        8 + len
                    }
                } else {
                    4 + len
                }
            };
            let take = (target - self.buffer.len()).min(input.len() - used);
            self.buffer.extend_from_slice(&input[used..used + take]);
            used += take;
            if self.buffer.len() < target {
                return Ok((used, None));
            }
            if target == 4 {
                continue;
            }
            let first = self.buffer[1] & 3 == 1;
            let header_len = if first { 8 } else { 4 };
            let header = Header {
                channel: self.buffer[0],
                flags: self.buffer[1],
                total: if first {
                    Some(u32::from_be_bytes(self.buffer[4..8].try_into().unwrap()))
                } else {
                    None
                },
            };
            header.validate()?;
            let length = u16::from_be_bytes([self.buffer[2], self.buffer[3]]) as usize;
            if self.buffer.len() == header_len + length {
                let payload = self.buffer[header_len..].to_vec();
                self.buffer.clear();
                return Ok((used, Some(Frame { header, payload })));
            }
        }
    }
    pub fn finish(&self) -> Result<(), Error> {
        if self.failed {
            Err(Error::Failed)
        } else if self.buffer.is_empty() {
            Ok(())
        } else {
            Err(Error::Truncated)
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub struct Message {
    pub channel: u8,
    pub flags: u8,
    pub bytes: Vec<u8>,
}
impl Message {
    pub fn id(&self) -> Result<u16, Error> {
        let bytes = self.bytes.get(..2).ok_or(Error::MessageTooShort)?;
        Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
    }
}
struct Pending {
    flags: u8,
    total: usize,
    bytes: Vec<u8>,
    started: Instant,
}
/// Channels default to control limits. Mark AV channels only after validated discovery.
/// The owner must call `expire` on timer wakeups even when no input arrives.
pub struct Assembler {
    pending: BTreeMap<u8, Pending>,
    av: [bool; 256],
    reserved: usize,
    failed: bool,
}
impl Default for Assembler {
    fn default() -> Self {
        Self {
            pending: BTreeMap::new(),
            av: [false; 256],
            reserved: 0,
            failed: false,
        }
    }
}
impl Assembler {
    pub fn new(av_channels: &[u8]) -> Self {
        let mut value = Self::default();
        for &channel in av_channels {
            if channel != 0 {
                value.av[channel as usize] = true;
            }
        }
        value
    }
    pub fn reserved_bytes(&self) -> usize {
        self.reserved
    }
    /// Earliest instant at which `expire` fails, so an event loop can schedule its wakeup.
    pub fn next_expiry(&self) -> Option<Instant> {
        self.pending
            .values()
            .map(|p| p.started + ASSEMBLY_LIFETIME)
            .min()
    }
    fn fail(&mut self) {
        self.pending.clear();
        self.reserved = 0;
        self.failed = true;
    }
    pub fn expire(&mut self, now: Instant) -> Result<(), Error> {
        if self.failed {
            return Err(Error::Failed);
        }
        let expired = self
            .pending
            .iter()
            .find(|(_, p)| now.saturating_duration_since(p.started) >= ASSEMBLY_LIFETIME)
            .map(|(&ch, _)| ch);
        if let Some(ch) = expired {
            self.fail();
            return Err(Error::Expired(ch));
        }
        Ok(())
    }
    /// Supply decrypted plaintext for encrypted frames; retain original metadata.
    /// A TLS adapter may delay this call until a whole fragment has been decrypted.
    pub fn push_plaintext(
        &mut self,
        header: Header,
        bytes: &[u8],
        now: Instant,
    ) -> Result<Option<Message>, Error> {
        self.expire(now)?;
        let result = self.push_inner(header, bytes, now);
        if result.is_err() {
            self.fail();
        }
        result
    }
    fn push_inner(
        &mut self,
        h: Header,
        bytes: &[u8],
        now: Instant,
    ) -> Result<Option<Message>, Error> {
        h.validate()?;
        if bytes.is_empty() {
            return Err(Error::EmptyPayload);
        }
        let limit = if self.av[h.channel as usize] {
            AV_LIMIT
        } else {
            CONTROL_LIMIT
        };
        if bytes.len() > limit {
            return Err(Error::MessageTooLarge);
        }
        let metadata = h.flags & 0x0c;
        match h.kind() {
            1 | 3 => {
                if self.pending.contains_key(&h.channel) {
                    return Err(Error::DuplicateStart(h.channel));
                }
                if h.kind() == 3 {
                    if bytes.len() < 2 {
                        return Err(Error::MessageTooShort);
                    }
                    if self.reserved + bytes.len() > AGGREGATE_LIMIT {
                        return Err(Error::AggregateLimit);
                    }
                    return Ok(Some(Message {
                        channel: h.channel,
                        flags: metadata,
                        bytes: bytes.to_vec(),
                    }));
                }
                let total = h.total.unwrap() as usize;
                if total > limit {
                    return Err(Error::MessageTooLarge);
                }
                if bytes.len() >= total {
                    return Err(Error::InvalidTotal);
                }
                if self.reserved + total > AGGREGATE_LIMIT {
                    return Err(Error::AggregateLimit);
                }
                self.reserved += total;
                // Allocate exactly the reserved total; growth cannot exceed the budget.
                let mut payload = Vec::with_capacity(total);
                payload.extend_from_slice(bytes);
                self.pending.insert(
                    h.channel,
                    Pending {
                        flags: metadata,
                        total,
                        bytes: payload,
                        started: now,
                    },
                );
                Ok(None)
            }
            _ => {
                let p = self
                    .pending
                    .get_mut(&h.channel)
                    .ok_or(Error::MissingStart(h.channel))?;
                if metadata != p.flags {
                    return Err(Error::MetadataChanged(h.channel));
                }
                let size = p
                    .bytes
                    .len()
                    .checked_add(bytes.len())
                    .ok_or(Error::InvalidTotal)?;
                if size > p.total
                    || (h.kind() == 0 && size >= p.total)
                    || (h.kind() == 2 && size != p.total)
                {
                    return Err(Error::InvalidTotal);
                }
                p.bytes.extend_from_slice(bytes);
                if h.kind() == 2 {
                    let p = self.pending.remove(&h.channel).unwrap();
                    self.reserved -= p.total;
                    return Ok(Some(Message {
                        channel: h.channel,
                        flags: metadata,
                        bytes: p.bytes,
                    }));
                }
                Ok(None)
            }
        }
    }
    pub fn finish(&self) -> Result<(), Error> {
        if self.failed {
            Err(Error::Failed)
        } else if self.pending.is_empty() {
            Ok(())
        } else {
            Err(Error::Truncated)
        }
    }
}

/// Produces plaintext fragments. For encryption, encrypt each payload in order,
/// set bit 0x08, then encode; FIRST totals must remain plaintext totals.
pub fn fragment(channel: u8, category: bool, bytes: &[u8], av: bool) -> Result<Vec<Frame>, Error> {
    if bytes.len() < 2 {
        return Err(Error::MessageTooShort);
    }
    let limit = if av && channel != 0 {
        AV_LIMIT
    } else {
        CONTROL_LIMIT
    };
    if bytes.len() > limit {
        return Err(Error::MessageTooLarge);
    }
    let count = bytes.len().div_ceil(OUTBOUND_FRAGMENT);
    Ok(bytes
        .chunks(OUTBOUND_FRAGMENT)
        .enumerate()
        .map(|(i, part)| {
            let kind = if count == 1 {
                3
            } else if i == 0 {
                1
            } else if i + 1 == count {
                2
            } else {
                0
            };
            Frame {
                header: Header {
                    channel,
                    flags: kind | if category { 4 } else { 0 },
                    total: if kind == 1 {
                        Some(bytes.len() as u32)
                    } else {
                        None
                    },
                },
                payload: part.to_vec(),
            }
        })
        .collect())
}

/// Blocking desktop utility only. The future USB event loop must handle
/// WouldBlock and cancellation with poll/wakeup rather than calling this helper.
pub fn write_frame(writer: &mut impl io::Write, frame: &Frame) -> io::Result<()> {
    let bytes = frame
        .encode()
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    writer.write_all(&bytes)
}
