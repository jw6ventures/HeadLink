use aap_wire::*;
use std::{
    io::{self, Write},
    time::{Duration, Instant},
};

fn hex(s: &str) -> Vec<u8> {
    s.split_whitespace()
        .map(|b| u8::from_str_radix(b, 16).unwrap())
        .collect()
}
fn parse_chunks(bytes: &[u8], chunk: usize) -> Vec<Frame> {
    let mut parser = Parser::new();
    let mut frames = Vec::new();
    for part in bytes.chunks(chunk) {
        let mut offset = 0;
        while offset < part.len() {
            let (used, frame) = parser.consume(&part[offset..]).unwrap();
            assert!(used > 0);
            offset += used;
            if let Some(frame) = frame {
                frames.push(frame);
            }
        }
    }
    parser.finish().unwrap();
    frames
}
#[test]
fn independent_spec_packets_and_every_split() {
    let fixtures = [
        (
            include_str!("../../../protocol/fixtures/version-request.hex"),
            1,
            vec![0, 1, 0, 1, 0, 1],
        ),
        (
            include_str!("../../../protocol/fixtures/version-response.hex"),
            2,
            vec![0, 2, 0, 1, 0, 5, 0, 0],
        ),
        (
            include_str!("../../../protocol/fixtures/auth-complete.hex"),
            4,
            vec![0, 4, 8, 0],
        ),
    ];
    let mut combined = Vec::new();
    for (text, id, payload) in fixtures {
        let bytes = hex(text);
        for chunk in 1..=bytes.len() {
            let frames = parse_chunks(&bytes, chunk);
            assert_eq!(frames.len(), 1);
            assert_eq!(frames[0].payload, payload);
            assert_eq!(
                frames[0].header,
                Header {
                    channel: 0,
                    flags: 3,
                    total: None
                }
            );
            assert_eq!(frames[0].encode().unwrap(), bytes);
            let msg = Assembler::default()
                .push_plaintext(frames[0].header, &payload, Instant::now())
                .unwrap()
                .unwrap();
            assert_eq!(msg.id().unwrap(), id);
        }
        combined.extend(bytes);
    }
    assert_eq!(parse_chunks(&combined, combined.len()).len(), 3);
}
fn h(ch: u8, flags: u8, total: Option<u32>) -> Header {
    Header {
        channel: ch,
        flags,
        total,
    }
}
#[test]
fn independent_fragment_headers_interleaving_and_plaintext_totals() {
    // Manually specified FIRST/MIDDLE/LAST, interleaved with a complete message.
    let wire = hex("03 05 00 01 00 00 00 04 00 00 03 00 02 00 0b 03 04 00 01 07 03 06 00 02 08 00");
    let mut assembler = Assembler::default();
    let mut result = Vec::new();
    for frame in parse_chunks(&wire, 1) {
        if let Some(m) = assembler
            .push_plaintext(frame.header, &frame.payload, Instant::now())
            .unwrap()
        {
            result.push(m);
        }
    }
    assert_eq!(result[0].channel, 0);
    assert_eq!(result[1].bytes, [0, 7, 8, 0]);
    assert_eq!(result[1].flags, 4);
    assert_eq!(assembler.reserved_bytes(), 0);
    // Model a TLS adapter's decrypted fragments; ciphertext length exceeds total.
    let encrypted = Frame {
        header: h(0, 9, Some(4)),
        payload: vec![0xaa; 40],
    };
    let decoded = parse_chunks(&encrypted.encode().unwrap(), 1);
    let mut a = Assembler::default();
    a.push_plaintext(decoded[0].header, &[0], Instant::now())
        .unwrap();
    let m = a
        .push_plaintext(h(0, 10, None), &[5, 8, 0], Instant::now())
        .unwrap()
        .unwrap();
    assert_eq!(m.bytes, [0, 5, 8, 0]);
    assert_eq!(m.flags, 8);
}
#[test]
fn malformed_frames_and_truncation() {
    for flags in 16..=255 {
        assert_eq!(
            Parser::new().consume(&[0, flags, 0, 1]),
            Err(Error::ReservedFlags(flags))
        );
    }
    assert_eq!(
        Parser::new().consume(&[0, 3, 0, 0]),
        Err(Error::EmptyPayload)
    );
    for total in [0u32, 1] {
        let mut bytes = vec![0, 1, 0, 1];
        bytes.extend(total.to_be_bytes());
        assert_eq!(Parser::new().consume(&bytes), Err(Error::InvalidTotal));
    }
    let bytes = hex(include_str!(
        "../../../protocol/fixtures/version-request.hex"
    ));
    for n in 1..bytes.len() {
        let mut parser = Parser::new();
        parser.consume(&bytes[..n]).unwrap();
        assert_eq!(parser.finish(), Err(Error::Truncated));
    }
    let frame = Frame {
        header: h(0, 3, None),
        payload: vec![1; WIRE_PAYLOAD],
    };
    assert_eq!(parse_chunks(&frame.encode().unwrap(), 17)[0], frame);
    assert_eq!(
        Frame {
            payload: vec![1; WIRE_PAYLOAD + 1],
            ..frame
        }
        .encode(),
        Err(Error::WireTooLarge)
    );
    assert_eq!(
        Frame {
            header: h(0, 3, Some(4)),
            payload: vec![0, 1]
        }
        .encode(),
        Err(Error::InvalidTotal)
    );
}
#[test]
fn fragment_sequence_failures_poison_and_release() {
    let now = Instant::now();
    for (next, bytes, expected) in [
        (h(1, 1, Some(4)), vec![0], Error::DuplicateStart(1)),
        (h(1, 3, None), vec![0, 1], Error::DuplicateStart(1)),
        (h(1, 8, None), vec![1], Error::MetadataChanged(1)),
        (h(1, 4, None), vec![1], Error::MetadataChanged(1)),
        (h(1, 0, None), vec![1, 2, 3], Error::InvalidTotal),
        (h(1, 2, None), vec![1], Error::InvalidTotal),
        (h(1, 2, None), vec![1, 2, 3, 4], Error::InvalidTotal),
    ] {
        let mut a = Assembler::default();
        a.push_plaintext(h(1, 1, Some(4)), &[0], now).unwrap();
        assert_eq!(a.push_plaintext(next, &bytes, now), Err(expected));
        assert_eq!(a.reserved_bytes(), 0);
        assert_eq!(
            a.push_plaintext(h(2, 3, None), &[0, 1], now),
            Err(Error::Failed)
        );
    }
    for flags in [0, 2] {
        assert_eq!(
            Assembler::default().push_plaintext(h(2, flags, None), &[0], now),
            Err(Error::MissingStart(2))
        );
    }
    assert_eq!(
        Assembler::default().push_plaintext(h(0, 3, None), &[0], now),
        Err(Error::MessageTooShort)
    );
    assert_eq!(
        Assembler::default().push_plaintext(h(0, 1, Some(2)), &[0, 1], now),
        Err(Error::InvalidTotal)
    );
}
#[test]
fn limits_and_absolute_expiry() {
    let now = Instant::now();
    for (ch, total) in [(0, CONTROL_LIMIT + 1), (1, AV_LIMIT + 1)] {
        assert_eq!(
            Assembler::new(&[0, 1]).push_plaintext(h(ch, 1, Some(total as u32)), &[0], now),
            Err(Error::MessageTooLarge)
        );
    }
    let mut a = Assembler::new(&[1, 2]);
    for ch in [1, 2] {
        a.push_plaintext(h(ch, 1, Some(AV_LIMIT as u32)), &[0], now)
            .unwrap();
    }
    assert_eq!(a.reserved_bytes(), AGGREGATE_LIMIT);
    assert_eq!(
        a.push_plaintext(h(3, 1, Some(4)), &[0], now),
        Err(Error::AggregateLimit)
    );
    assert_eq!(a.reserved_bytes(), 0);
    let mut a = Assembler::default();
    assert_eq!(a.next_expiry(), None);
    a.push_plaintext(h(0, 1, Some(4)), &[0], now).unwrap();
    a.push_plaintext(h(0, 0, None), &[1], now + ASSEMBLY_LIFETIME / 2)
        .unwrap();
    a.push_plaintext(h(1, 1, Some(4)), &[0], now + ASSEMBLY_LIFETIME / 4)
        .unwrap();
    assert_eq!(a.next_expiry(), Some(now + ASSEMBLY_LIFETIME));
    a.expire(now + ASSEMBLY_LIFETIME - Duration::from_millis(1))
        .unwrap();
    assert_eq!(a.finish(), Err(Error::Truncated));
    assert_eq!(a.expire(now + ASSEMBLY_LIFETIME), Err(Error::Expired(0)));
    assert_eq!(a.reserved_bytes(), 0);
}
#[test]
fn outbound_fragment_boundaries_and_roundtrip() {
    for len in [
        2,
        OUTBOUND_FRAGMENT,
        OUTBOUND_FRAGMENT + 1,
        OUTBOUND_FRAGMENT * 3,
        CONTROL_LIMIT,
        AV_LIMIT,
    ] {
        let bytes = vec![0x55; len];
        let frames = fragment(1, true, &bytes, true).unwrap();
        assert!(frames.iter().all(|f| f.payload.len() <= OUTBOUND_FRAGMENT));
        if len > OUTBOUND_FRAGMENT {
            assert_eq!(frames[0].header, h(1, 5, Some(len as u32)));
            assert_eq!(frames.last().unwrap().header.flags, 6);
        } else {
            assert_eq!(frames[0].header.flags, 7);
        }
        let mut a = Assembler::new(&[1]);
        let mut done = None;
        for f in frames {
            done = a
                .push_plaintext(f.header, &f.payload, Instant::now())
                .unwrap();
        }
        assert_eq!(done.unwrap().bytes, bytes);
    }
    assert_eq!(fragment(0, false, &[0], false), Err(Error::MessageTooShort));
    assert_eq!(
        fragment(0, false, &vec![0; CONTROL_LIMIT + 1], true),
        Err(Error::MessageTooLarge)
    );
}
#[test]
fn partial_writes_eintr_zero_and_broken_pipe() {
    struct Short {
        out: Vec<u8>,
        interrupted: bool,
    }
    impl Write for Short {
        fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
            self.interrupted = !self.interrupted;
            if self.interrupted {
                return Err(io::ErrorKind::Interrupted.into());
            }
            let n = bytes.len().min(2);
            self.out.extend(&bytes[..n]);
            Ok(n)
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }
    let frame = &parse_chunks(
        &hex(include_str!(
            "../../../protocol/fixtures/version-response.hex"
        )),
        1,
    )[0];
    let mut short = Short {
        out: Vec::new(),
        interrupted: false,
    };
    write_frame(&mut short, frame).unwrap();
    assert_eq!(
        short.out,
        hex(include_str!(
            "../../../protocol/fixtures/version-response.hex"
        ))
    );
    assert_eq!(
        write_frame(&mut &mut [0u8; 0][..], frame)
            .unwrap_err()
            .kind(),
        io::ErrorKind::WriteZero
    );
    struct Broken;
    impl Write for Broken {
        fn write(&mut self, _: &[u8]) -> io::Result<usize> {
            Err(io::ErrorKind::BrokenPipe.into())
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }
    assert_eq!(
        write_frame(&mut Broken, frame).unwrap_err().kind(),
        io::ErrorKind::BrokenPipe
    );
}
#[test]
fn bounded_randomized_streams_and_assemblies() {
    // Fixed seed for reproducibility. Bounded mutation testing, not a fuzzing proof.
    let mut seed = 0x12345678u32;
    let mut random = || {
        seed ^= seed << 13;
        seed ^= seed >> 17;
        seed ^= seed << 5;
        seed
    };
    for _ in 0..2000 {
        let len = random() as usize % 256;
        let bytes: Vec<u8> = (0..len).map(|_| random() as u8).collect();
        let mut parser = Parser::new();
        let mut offset = 0;
        while offset < len {
            match parser.consume(&bytes[offset..]) {
                Ok((used, _)) => {
                    assert!(used > 0);
                    offset += used;
                }
                Err(_) => break,
            }
            assert!(parser.buffered_len() <= WIRE_PAYLOAD + 8);
        }
        let mut a = Assembler::default();
        for _ in 0..10 {
            let flags = (random() % 16) as u8;
            let total = if flags & 3 == 1 {
                Some(random() % 128)
            } else {
                None
            };
            let _ = a.push_plaintext(
                h((random() % 4) as u8, flags, total),
                &bytes,
                Instant::now(),
            );
            assert!(a.reserved_bytes() <= AGGREGATE_LIMIT);
        }
    }
}
