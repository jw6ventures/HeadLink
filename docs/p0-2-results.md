# P0.2 results — 16 September 2026

Audited the pinned references, pinned the Android and native toolchain, packaged
the Rust engine in an arm64-v8a APK, and introduced the shared session engine with
a cancellable lab transport and version-only exchange. Full P0 remains incomplete.
**No APK has run on a phone or emulator, and no receiver or head unit has been
contacted.** G0–G5 remain open.

## Verification

All on Linux x86_64 in Podman with networking disabled unless stated.

| Check | Result |
| --- | --- |
| `tools/check.sh`: rustfmt, Clippy `-D warnings`, tests, release build | Pass |
| Tests | 28 passed: wire 8, session 7, transport 6, JNI probe 1, CLI 6 |
| Transport and CLI tests repeated 25 times | 0 failures |
| `tools/android-build.sh`: wrapper jar checksum, Android Clippy, `.so`, debug and release APKs, lint (warnings as errors), APK verification | Pass |
| Clean rebuild (all build outputs deleted) of both scripts | Pass; 92 of 92 Gradle tasks executed |
| Reproducibility across two clean offline builds | Release APK, debug APK, `libaap_jni.so`, and harness bit-for-bit identical |
| `tools/android-build.sh --online` and `tools/fetch-deps.sh` | Pass |
| Gradle dependency verification with one corrupted checksum | Build fails, naming `kotlin-stdlib-2.2.10.jar` (metadata restored afterwards) |
| APK verifier against deliberately broken APKs | Rejects missing library, compressed library, extra library, misaligned ZIP, injected GMS reference, injected gearhead reference |

The first Gradle build (online, writing `gradle/verification-metadata.xml`, 192
components) took 1 min 41 s. Subsequent clean offline builds take about 20 s.

## Reference audit

Recorded in [references.md](references.md). Key outcomes:

- Discovery and channel-open directions and the plaintext exceptions match spec
  §3.1; the OAA prose diagrams that contradict them are linked with line numbers.
- AACS sends ping responses encrypted, unlike the receiver policy. Recorded as a P3
  profile question, not adopted.
- Phone behavior appends an opaque WireConfig for raw requests ≥ 1.6. The only
  profile, `legacy-1.5`, therefore answers 1.0–1.5 and reports 1.6+ as
  `VERSION_UNSUPPORTED` without sending a MISMATCH.
- **Prodigy requests 1.7 by default.** A controlled Prodigy receiver must be
  configured for a 1.1 request, or a new profile needs evidence.
- **The `android14-6.1` accessory driver has no `poll` and ignores `O_NONBLOCK`.**
  The P0.2 poll loop cannot cancel a blocked accessory read; P1 must choose and
  prove another strategy on the target phone.
- Initial protobuf closure is 52 files (all GPL-3.0-or-later headers but one).
- License inventory updated in [licenses.md](licenses.md).

## Toolchain pins

| Component | Pin |
| --- | --- |
| Rust | 1.85.1 + `aarch64-linux-android`, base image digest `bf7d8766…3cf0` |
| JDK | Temurin 17.0.20.1+1 (SHA-256 checked) |
| Android SDK | cmdline-tools 23.0 (SHA-256 checked, image-build only); Platform 36 r2; Build-Tools 36.1.0; Platform-Tools 37.0.1; NDK r29 29.0.14206865 (revisions asserted) |
| Gradle | 9.7.1 wrapper, distribution SHA-256 and wrapper-jar SHA-256 checked |
| AGP / Kotlin | 9.4.0 / 2.2.10, all resolved artifacts SHA-256 verified |
| Crates | `libc` 0.2.189, `jni-sys` =0.3.0 (Cargo.lock checksums) |
| App | minSdk 33, compileSdk/targetSdk 36, arm64-v8a only |

Not yet pinned: prost/protoc and OpenSSL (introduced with P2/P3 work).

## APK facts

- `org.openautoandroid.source` 0.1.0-p0.2 (provisional application ID). Debug APK
  2,849,435 bytes; unsigned release APK 2,468,067 bytes (unminified).
- Badging: minSdk 33, targetSdk 36, `native-code: 'arm64-v8a'`, **no
  `uses-permission`**. Backups disabled; data-extraction rules exclude every domain.
- Runtime classpath: `kotlin-stdlib` 2.2.10 and `org.jetbrains:annotations` 13.0
  only. No AndroidX, Google Play services, or gearhead references in dex.
- `lib/arm64-v8a/libaap_jni.so`: stored uncompressed, 16 KiB page-aligned ZIP entry,
  AArch64 ELF, all LOAD segments aligned to 0x4000, `NEEDED` only `libc.so` and
  `libdl.so`. Its only dynamic export is `Java_org_openautoandroid_source_NativeBridge_nativeProbe`.
- `ConnectionActivity` loads the library and shows the in-memory self-test report.
  The self-test logic passes on the host test; **it has not been run on a device**.

## Session engine and lab transport

- `aap-session::Session` is sans-IO. It validates a complete plaintext channel-0
  version request, records up to 64 request bytes, the requested version, and the
  chosen response, and queues the 1.5 MATCH frame. It starts the 5 s TLS wait only
  once the response is fully written, and stops with `TLS_NOT_IMPLEMENTED` on the
  first SSL_HANDSHAKE message.
- Tested outcomes: byte-at-a-time and coalesced input; TLS message pipelined before
  the response write (flushed, or abandoned after a 1 s linger); every unsupported
  version shape; version, TLS, and 30 s establishment deadlines; assembly expiry; wrong
  channel or category bit; encrypted frame before TLS; ping or TLS before a version
  request; duplicate request; reserved flags; missing start; EOF; I/O error with OS
  code; cancellation; input ignored after the outcome.
- `transport::run` writes before reading, uses a 64 KiB buffer, handles EINTR,
  WouldBlock, short reads and writes, and waits in `poll` on the descriptor plus a
  socket-pair wakeup. Socket-pair tests prove cancellation while waiting to read
  and while blocked on a full write buffer (8 MiB pending), each returning in under
  1 s, and EPIPE classification when the peer vanishes before the response is written.

Manual demonstration against an independent Python peer that sends the spec fixture
bytes (release harness, loopback, networking disabled):

```text
== peer requests 1.1
peer: sent 00030006000100010001 received 000300080002000100050000 matches_fixture=True
harness: last_completed_stage=version
harness: requested_version=1.1
harness: response_version=1.5
harness: final_category=TLS_NOT_IMPLEMENTED
harness exit=0
== peer requests 1.7
peer: sent 00030006000100010007 received EOF matches_fixture=False
harness: requested_version=1.7
harness: final_category=VERSION_UNSUPPORTED
harness: final_detail=requested 1.7 is at or above the 1.6 WireConfig gate; no implemented profile covers it
harness exit=2
```

This is a scripted fixture peer, **not a controlled receiver**.

## Limitations and open items

- G0 unverified: the APK has not been installed on the GrapheneOS phone; native
  loading on a device is unproven.
- No controlled receiver was built or run; no receiver-side trace exists.
- The accessory descriptor transport, JNI owned-FD contract, USB permission, and
  foreground service are not implemented (P1).
- No TLS, protobuf generation, authentication, discovery, or keepalive.
- APK notices for linked MIT/Apache components are not packaged yet; required before
  any distribution.
- The debug APK uses a local debug key from the build cache; the release APK is
  unsigned.
- `VERSION_TIMEOUT`, `UNEXPECTED_MESSAGE`, and `TLS_NOT_IMPLEMENTED` are local error
  categories beyond the spec §9 list (documented in references.md).
