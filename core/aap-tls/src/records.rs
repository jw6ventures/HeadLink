//! TLS record boundaries in an inbound ciphertext stream. OpenSSL buffers a partial
//! record internally, so the session tracks boundaries itself to know whether an
//! encrypted frame's plaintext is complete (spec §7.3: records may span input chunks).

/// Largest TLS 1.2 ciphertext record body (RFC 5246 §6.2.3: 2^14 + 2048).
pub const RECORD_BODY_LIMIT: usize = 16384 + 2048;
const HEADER: usize = 5;

#[derive(Debug, Default, Clone)]
pub struct RecordTracker {
    header: [u8; HEADER],
    header_len: usize,
    body_remaining: usize,
    records: u64,
}

impl RecordTracker {
    /// Consumes ciphertext bytes. Fails on a record length no TLS 1.2 peer may send.
    pub fn feed(&mut self, mut bytes: &[u8]) -> Result<(), String> {
        while !bytes.is_empty() {
            if self.header_len < HEADER {
                let take = (HEADER - self.header_len).min(bytes.len());
                self.header[self.header_len..self.header_len + take]
                    .copy_from_slice(&bytes[..take]);
                self.header_len += take;
                bytes = &bytes[take..];
                if self.header_len == HEADER {
                    let length = u16::from_be_bytes([self.header[3], self.header[4]]) as usize;
                    if length > RECORD_BODY_LIMIT {
                        return Err(format!(
                            "TLS record length {length} exceeds {RECORD_BODY_LIMIT}"
                        ));
                    }
                    self.body_remaining = length;
                    self.finish_if_complete();
                }
            } else {
                let take = self.body_remaining.min(bytes.len());
                self.body_remaining -= take;
                bytes = &bytes[take..];
                self.finish_if_complete();
            }
        }
        Ok(())
    }

    fn finish_if_complete(&mut self) {
        if self.header_len == HEADER && self.body_remaining == 0 {
            self.header_len = 0;
            self.records += 1;
        }
    }

    /// Whether every byte fed so far belongs to a complete record.
    pub fn at_boundary(&self) -> bool {
        self.header_len == 0
    }

    /// Complete records seen.
    pub fn records(&self) -> u64 {
        self.records
    }
}
