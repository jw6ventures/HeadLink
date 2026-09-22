# P0.1 results — 16 September 2026

Implemented the first bounded wire slice of P0. Full P0 remains incomplete.

Verification on Linux x86_64 using the pinned Rust 1.85.1 Podman build environment:

| Check | Result |
| --- | --- |
| `cargo fmt --all -- --check` | Pass |
| Clippy, all targets, `-D warnings` | Pass |
| Wire integration tests | 8 passed |
| Inspector CLI integration tests | 3 passed |
| Offline locked release build | Pass |

The container image setup fetched the compiler tools; checks and builds ran with
networking disabled. No external Cargo dependencies are used. Initial attempts
exposed the host sandbox's Podman storage restriction and the base image's missing
formatter/linter; the documented prepared image and approved Podman execution
resolved both. The workspace has no usable Git repository, so no commit/diff
baseline was available.

Coverage includes independent complete version/auth fixtures from spec §7.2,
byte-at-a-time input and every fixed chunk width for those packets, coalesced
frames, hand-authored FIRST/MIDDLE/LAST with interleaved channels, and outbound
fragment thresholds through 4 MiB. Error tests cover reserved flags, zero payload,
truncated EOF, invalid totals, missing/duplicate starts, category/encryption
changes, control/AV and aggregate reservation limits, and absolute expiry.
Malformed sessions fail closed and release pending reservations.

A deterministic 2,000-case bounded randomized test feeds the parser arbitrary
byte streams and the assembler arbitrary fragment sequences, checking progress
and reservation bounds. This is not a long-running coverage-guided fuzz campaign.
Partial-write tests cover short writes, EINTR, WriteZero, and BrokenPipe. CLI tests
verify exact metadata output and failure on truncation or encrypted frames.

Synthetic encrypted headers test the separation between wire ciphertext lengths
and plaintext assembly totals. **No actual TLS implementation is tested.** The
assembler's 10-second deadline is exposed for the future event loop to drive;
stdin inspection is for finite offline input, not cancellable live transport.
Completed messages leave the assembler's budget; the future session/transport
owner must also bound its queues and TLS buffers.

Remaining: Android toolchain/APK/native loading, upstream schema/license audits,
generated protobuf validation, ordered cancellable I/O, shared session engine,
TCP lab adapter, independently configured receiver, and all hardware evidence.
There is no claim of a lab session pass or factory compatibility. G0–G5 remain open.
