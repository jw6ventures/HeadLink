# Reference verification

Pins for all four spec baselines are in
[references.toml](../protocol/upstream/references.toml). No upstream code or schemas
are copied into this repository. Audited from fresh checkouts at the exact commits:
framing in P0.1, then directions, version negotiation, schemas, licenses, and the
accessory driver in P0.2 (16 September 2026). A primary code reference outranks
prose; a validated vehicle capture can still require a separate profile.

Link prefixes used below:
[AACS]: `tomasz-grobelny/AACS@faa1cf2`,
[OAA]: `mrmees/open-android-auto@61eab61`,
[Prodigy]: `mrmees/openauto-prodigy@3afc5dd` (`libs/prodigy-oaa-protocol/`),
[aasdk]: `f1xpl/aasdk@9ee5283`.

## Framing (P0.1)

- [Prodigy FrameType.hpp](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/include/oaa/Messenger/FrameType.hpp):
  MIDDLE=0, FIRST=1, LAST=2, BULK=3; category=0x04, encryption=0x08.
- [Prodigy FrameParser.cpp](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Messenger/FrameParser.cpp):
  uint16 big-endian payload length and FIRST-only uint32 big-endian total.
- [Prodigy FrameSerializer.cpp](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Messenger/FrameSerializer.cpp):
  complete or FIRST/MIDDLE/LAST serialization; total excludes frame headers.
- [AACS enums.h L5-L19](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/include/enums.h#L5-L19)
  uses the same numeric values, naming the 0x04 bit `Specific`.
- [Prodigy Version.hpp L9](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/include/oaa/Version.hpp#L9):
  receiver `FRAME_MAX_PAYLOAD = 0x4000`, consistent with the 16 KiB outbound fragment.

Local strict-profile choices from spec §6 are unchanged: reserved bits rejected, no
empty fragments, duplicate starts fatal, consistent per-channel metadata, bounded
control/AV/aggregate reservations, and 10-second absolute assembly expiry.

## Encryption exceptions

- [Prodigy EncryptionPolicy.cpp L11-L24](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Messenger/EncryptionPolicy.cpp#L11-L24):
  channel-0 version, SSL handshake, AUTH_COMPLETE, and ping request/response are
  sent plaintext. The OAA handshake prose calls AUTH_COMPLETE encrypted; the code wins.
- [Prodigy Messenger.cpp L238-L245](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Messenger/Messenger.cpp#L238-L245):
  the receiver decrypts according to each frame's 0x08 bit, so it would also accept
  an encrypted ping.
- **Discrepancy for P3:** [AACS AaCommunicator.cpp L252-L268](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/AaCommunicator.cpp#L252-L268)
  sends its ping *response* encrypted. The spec baseline (plaintext) stays; an
  encrypted response needs an explicit, evidence-backed profile.

## Message directions

| Exchange | Code evidence | Contradicting prose |
| --- | --- | --- |
| Discovery: phone sends 0x0005 after AUTH_COMPLETE; HU answers 0x0006 | [AACS AaCommunicator.cpp L237-L239](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/AaCommunicator.cpp#L237-L239) → [L95-L104](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/AaCommunicator.cpp#L95-L104) (encrypted, channel 0); receiver side [Prodigy AASession.cpp L467](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Session/AASession.cpp#L467) | [OAA 03-service-discovery.md L21, L34-L36](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/docs/interactions/03-service-discovery.md?plain=1#L21-L36) shows response first |
| Channel open: phone sends 0x0007 on the **target** channel, flags 0x0F | [AACS ChannelHandler.cpp L34-L46](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/ChannelHandler.cpp#L34-L46); [Prodigy AASession.cpp L566](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Session/AASession.cpp#L566); response [Prodigy ControlChannel.cpp L189](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Channel/ControlChannel.cpp#L189); schema comment "Direction: Phone->HU" in [ChannelOpenRequestMessage.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/control/ChannelOpenRequestMessage.proto) | [OAA 04-channel-lifecycle.md L40-L43, L67](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/docs/interactions/04-channel-lifecycle.md?plain=1#L40-L67) shows HU→phone on channel 0 |
| AUTH_COMPLETE: HU → phone, plaintext, required status | [OAA AuthCompleteIndicationMessage.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/control/AuthCompleteIndicationMessage.proto) (`required int32 status`); AACS ignores the status ([L237-L239](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/AaCommunicator.cpp#L237-L239)), which spec §7.5 forbids | [OAA 02-version-ssl-auth.md](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/docs/interactions/02-version-ssl-auth.md) shows `optional` and "Encrypted" |

These confirm spec §3.1. Only the version stage is implemented in P0.2; discovery,
auth, and channel opens remain unimplemented.

## Version negotiation

Evidence:

- Source role: [AACS AaCommunicator.cpp L85-L93](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/AaCommunicator.cpp#L85-L93)
  answers **any** major-1 request with 1.5 MATCH (`L75-L83`), does not check the body
  length, and throws on other majors **without sending a response**.
- Phone behavior notes: [OAA 02-version-ssl-auth.md](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/docs/interactions/02-version-ssl-auth.md)
  records Android Auto 17.3 appending WireConfig to its response when the *raw
  request* is ≥ 1.6, preferring a 1.7 response, and MISMATCH as 0xFFFF.
- WireConfig is [ControlChannelConfigWrapper](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/control/ControlChannelConfigMessage.proto)
  containing four [uint32 parameters whose purpose is "TBD"](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/control/ControlChannelConfigData.proto).
  Nothing supports generating meaningful values.
- MISMATCH: [OAA VersionResponseStatusEnum.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/control/VersionResponseStatusEnum.proto)
  (ProtocolStatus `NO_COMPATIBLE_VERSION = -1`, confidence unverified) and
  [aasdk VersionResponseStatusEnum.proto](https://github.com/f1xpl/aasdk/blob/9ee5283245a630afce3a441696b9dde484ecbcd9/aasdk_proto/VersionResponseStatusEnum.proto)
  (`MISMATCH = 0xFFFF`) agree on the status value. Neither establishes the version
  fields that should accompany it or how a factory HU reacts.
- Receiver acceptance: [Prodigy AASession.cpp L384-L392](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Session/AASession.cpp#L384-L392)
  requires status 0 and requires response ≥ request only when the request is ≥ 4.3
  ([SessionProtocolPolicy.hpp L57-L60](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/include/oaa/Session/SessionProtocolPolicy.hpp#L57-L60)).
  Trailing response bytes are optional ([ControlChannel.cpp L64-L78](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Channel/ControlChannel.cpp#L64-L78)).
  [aasdk ControlServiceChannel.cpp L174-L184](https://github.com/f1xpl/aasdk/blob/9ee5283245a630afce3a441696b9dde484ecbcd9/src/Channel/Control/ControlServiceChannel.cpp#L174-L184)
  treats a missing status as MISMATCH.
- **Lab consequence:** Prodigy requests **1.7 by default**
  ([SessionConfig.hpp L18-L19](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/include/oaa/Session/SessionConfig.hpp#L18-L19)).
  A stock Prodigy receiver therefore gets `VERSION_UNSUPPORTED` from the baseline
  profile. The controlled receiver must be configured to request 1.1 (the spec's lab
  request), or a new profile must be justified.

Implemented profile `legacy-1.5` (the only profile):

| Request body (after ID 0x0001) | Result |
| --- | --- |
| exactly 4 bytes, major 1, minor 0–5 | send `00 02 00 01 00 05 00 00` (1.5 MATCH) |
| major 1, minor ≥ 6 | `VERSION_UNSUPPORTED` (WireConfig gate); nothing sent |
| other major | `VERSION_UNSUPPORTED`; nothing sent |
| more than 4 bytes | `VERSION_UNSUPPORTED` (extension bytes); nothing sent |
| fewer than 4 bytes | `VERSION_UNSUPPORTED` (body length); nothing sent |

The request must be a complete plaintext channel-0 message without the 0x04 bit.
The full request prefix (up to 64 bytes), its total length, the parsed requested
version, and the chosen response are reported separately.

Decision: no MISMATCH response is sent. Its status value is corroborated, but its
accompanying fields and HU handling are not, and for requests of 1.6 or later the
limitation is ours rather than an incompatibility. Closing after a recorded
`VERSION_UNSUPPORTED` classifies a G1 attempt without advertising behavior.

Candidate profile, AACS-compatible "any major-1 request → 1.5 MATCH, no WireConfig".
Supporting evidence is AACS L89-L90 and Prodigy's acceptance of a lower response below
4.3. **Implemented before the first hardware test as the opt-in profile
`legacy-1.5-any-minor`** (fixed four-byte major-1 requests only; extension bytes and other
majors stay unsupported), so a vehicle visit whose recorded request `legacy-1.5` refuses
at the WireConfig gate can try it at once. In the lab the Prodigy receiver requesting its
default 1.7 accepted 1.5 and completed TLS, authentication, and discovery with it. A
factory head unit that requires a response at least equal to its request, or a
WireConfig, may still refuse it; `legacy-1.5` remains the default and the profile is
reported in every report.

Investigative profile `match-1.6`, "major-1 request at or above the gate → 1.6 MATCH, no
WireConfig; below it → 1.5 MATCH". No evidence supports the 1.6 answer: it is a guess at
why Ford SYNC 4.0, which requests 1.6 (report 617af5ce) and is answered 1.5, then
advertises no `resize_actions` or `margin_configs` in any video configuration and offers
no expand affordance beside its Android Auto tab, where Google's Android Auto 17.6 gets
one. An empty `ControlChannelConfigWrapper` is zero bytes, so the response is the same 12
bytes the fixture has with the minor changed, and the populated extension a real phone
sends at 1.6 is still unimplemented — its four `ControlChannelParams` values are unknown.
A head unit may answer a 1.6 claim by expecting that extension and closing.

**Result: negative.** Ford SYNC 4.0 accepts a 1.6 answer and completes every stage with
it, and the answer changes nothing about what it advertises or displays. Under a constant
1.6 answer it advertised the split layout (report 48d04cb1), then the full-width one
(7fcc0aec, 28a181f4), then the split one again (56e20986); under `legacy-1.5-any-minor` it
advertised the full-width layout too (c6cb973c). A constant cannot explain a variable, so
the version answer does not gate the layout — that is head-unit state behind its own
expand control, and it does not survive a session. The profile stays as an opt-in
experiment with a recorded answer; it is not a route to anything.

## Session timing and error categories

Timers follow spec §7.6: 5 s version wait from descriptor open, 5 s TLS wait from
the moment the version response is **completely written**, 30 s establishment cap,
10 s assembly expiry. A TLS message that arrives after the response is decided but
before it is written is accepted and flagged (`tls_before_response_written`); the
decided response is still flushed, bounded by a 1 s linger.

Categories used beyond the spec §9 list, documented as local additions:

- `VERSION_TIMEOUT`: no version request before the version deadline (spec §9 has no
  category for this).
- `UNEXPECTED_MESSAGE`: a well-formed message that is invalid in the current stage,
  including encrypted frames before TLS and nonzero channel or category bits.
- `TLS_NOT_IMPLEMENTED` (P0.2–P1.1 only): the version-only build's end when the peer
  began TLS. Replaced by `STAGE_LIMIT` with `stop_after=version`; see "Local categories
  added before the first hardware test" below.

## Android accessory descriptor

[Android common kernel `android14-6.1` f_accessory.c @ 22764d9](https://android.googlesource.com/kernel/common/+/22764d91f0efd150423a3a489be434ac184c3b78/drivers/usb/gadget/function/f_accessory.c)
(GPL-2.0, read only; not the GrapheneOS device kernel):

- `acc_fops` (L930-L938) has no `.poll`. `poll()` therefore reports the default
  readable/writable mask without waiting.
- `acc_read` blocks in `wait_event_interruptible` for online state (L712) and
  request completion (L753); a signal dequeues the request and returns (L754-L762).
  `acc_write` waits the same way for an idle request (L804). Neither consults
  `O_NONBLOCK`. Reads are clamped to `BULK_BUFFER_SIZE` 16384 (L49).
- [AACS AaCommunicator.cpp L551, L594-L600](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/AaCommunicator.cpp#L594-L600)
  interrupts blocking gadget reads with `pthread_kill(SIGUSR1)`, a gadget-side
  analogue rather than proof for Android.

Consequence: the P0.2 poll/wakeup loop is correct for TCP and socket pairs only.
On the accessory it would see constant readiness and then block in `read`, where
the wakeup cannot reach it. P1 must choose and prove a cancellation strategy on
the target phone, such as a dedicated native reader interrupted by a signal, or
detach/close semantics. The same paths returned HTTP 404 on `android15-6.6` and
`android16-6.12`; the target device kernel's behavior is unverified.

### Cancellation strategy (P1.1 decision, unproven on the device)

`aap_session::accessory` gives the descriptor to one reader thread and one writer
thread doing blocking `read`/`write`; the session thread owns protocol state, timers,
and output order. Stop sets a shutdown flag and sends the first C-library-available
real-time signal (`SIGRTMIN()`) with `pthread_kill`, handled by a no-op installed
without `SA_RESTART`, so `wait_event_interruptible` in `acc_read`/`acc_write`
(L712, L753, L804) returns `-ERESTARTSYS` and userspace sees `EINTR`. The signal is
re-sent every 10 ms until each thread exits (a signal arriving just before `read`
would otherwise be lost), within a 2 s budget; a thread still running after that is
detached and reported, and the descriptor closes only when the last thread holding it
returns. No thread ever closes a descriptor another thread may be blocked on.

Choice rationale and limits:

- Signals are the only interruption mechanism the audited driver offers besides USB
  detach (`acc_disable` clears `online` and wakes waiters). This mirrors AACS's
  gadget-side `SIGUSR1` use, but uses a real-time signal because ART's signal catcher
  uses `SIGQUIT`/`SIGUSR1`, and bionic keeps its internal real-time signals below the
  value `SIGRTMIN()` reports. Those platform facts come from general platform knowledge
  and were not re-audited at a pinned commit. The handler install refuses to replace an
  existing handler, and I/O threads explicitly unblock the signal because they inherit
  the JNI caller's mask.
- Host proof only: blocked socket reads, FIFO reads, and full-buffer socket writes are
  interrupted in < 1 s, and a `SA_RESTART` mutation fails 8 of 9 transport tests. The
  JNI path was also exercised under HotSpot. **Neither ART nor the target phone's
  accessory driver has been tested**; `acc_write` may lose track of partially queued
  bytes when interrupted, which is acceptable only because the session is ending.
- An interrupted `acc_read` whose request dequeue fails may retain data for a later
  read; nothing reads again after shutdown, and the descriptor is then closed.

## Controlled lab receiver (P1.1)

`tools/lab-receiver.sh` builds the OpenAuto Prodigy protocol library at the spec pin
(`3afc5dd`) with its proto submodule gitlink (`open-android-auto@5ff4aa2`, dist branch)
and a GPL-3.0-only driver (`tools/lab-receiver/receiver.cpp`) that listens on loopback
TCP and sets `SessionConfig::protocolMajor/Minor` (1.1 by default; no source change
needed). One lab patch replaces the library's embedded third-party TLS identity with
generated PEM files: [Messenger.cpp startHandshake](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Messenger/Messenger.cpp#L215-L223)
otherwise always calls `Cryptor::init(Role::Client)`, which loads the certificate and
private key compiled into
[Cryptor.cpp](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Messenger/Cryptor.cpp#L373)
(certificate from L373, private key from L393).
The patched build fails the handshake instead of falling back to that key.

Receiver-side observations (lab only, not factory evidence):

- Requesting 1.1, the receiver accepted `1.5 MATCH` with no trailing bytes, entered
  `TLSHandshake`, and sent a 1,533-byte ClientHello inside one plaintext 0x0003 message.
- That ClientHello (OpenSSL 3.5.7) offers TLS 1.3 and 1.2 (`supported_versions`
  0304/0303), 30 cipher suites including TLS 1.2 ECDHE-GCM/CHACHA20 and CBC suites, and
  an `X25519MLKEM768` key share. P2's TLS 1.2-only server must negotiate down from this
  offer; it says nothing about the factory head unit's suites.
- Requesting 1.7 (Prodigy's default), the source reported `VERSION_UNSUPPORTED` and sent
  nothing; the receiver reported `TransportError` when the source closed. This confirms
  the P0.2 audit against the running receiver.
- Later (before the first hardware test) the driver advertises H.264 800x480@30 video on
  channel 3 and an 800x480 touch screen on channel 1, and the demo pins the receiver's
  generated certificate. Full TLS, authentication, discovery, keepalive, and shutdown
  results are in [P2/P3 lab results](p2-p3-lab-results.md).

## Local categories added in P1.1

- `NATIVE_START_FAILED` (Kotlin): the native library was not loaded or `nativeStart`
  returned a negative code (`INVALID_DESCRIPTOR`, `INVALID_CONFIG`, `BUSY`, `STALE_IO`,
  `SPAWN_FAILED`, `CALLBACK_INVALID`, `INTERNAL_ERROR`) before the session thread ran.
- `INTERNAL_ERROR` (native report): the session worker panicked (not observed).


## Protobuf subset

The transitive import closure of the P3 control messages (AUTH_COMPLETE, discovery
request/response, ping request/response, shutdown request, ByeByeResponse,
ControlChannelConfig, ProtocolStatus, control message IDs) is **52 `.proto` files**
at the OAA pin, mostly because ServiceDiscoveryResponse imports every channel
descriptor. All are proto2 or proto3 schemas with `GPL-3.0-or-later` SPDX headers,
except `oaa/control/ChannelDescriptorData.proto`, which has no header and is covered
only by the repository LICENSE. `ShutdownResponseMessage.proto` is retracted
upstream in favor of `ByeByeResponseMessage.proto`. Several enums use negative
values (ProtocolStatus), so status fields must be validated as raw `int32`, not
closed enums. Vendored and generated before the first hardware test; see "Protobuf
generation" below (51 files: the retracted `ShutdownResponseMessage.proto` is not part
of the closure). The projection slice adds the import closure of channel open and close,
AV setup/start/stop/ack, video focus, input binding and events, and the sensor
subscription: **116 files**, all with `GPL-3.0-or-later` SPDX headers except the
unheadered `oaa/control/ChannelDescriptorData.proto`, and all byte-identical at the spec
pin and at the receiver's proto gitlink.

## TLS: audit and decisions (lab work before the first hardware test)

Evidence at the pinned commits:

- Source role, AACS: [`SSLv23_server_method`, verify `SSL_VERIFY_PEER` with a callback
  that accepts everything, `SSL_OP_NO_TLSv1_3`, ECDH auto, DH parameters](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/AaCommunicator.cpp#L302-L337);
  the handshake is driven by one `SSL_accept` per inbound 0x0003 message and drained in
  512-byte `BIO_read` calls ([L270-L290](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/AaCommunicator.cpp#L270-L290)); encrypted application data is
  cut into 2,000-byte plaintext frames, each passed to `SSL_write` ([L372](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/AaCommunicator.cpp#L372)).
  Its server certificate and key are the openauto head-unit pair that must never be used.
- Receiver role, Prodigy: [TLS client with `SSL_VERIFY_NONE` over memory BIOs](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Messenger/Cryptor.cpp#L49-L135);
  it rejects an encrypted frame that does not hold only complete TLS records
  ([L262](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Messenger/Cryptor.cpp#L262)); after its handshake completes it sends
  AUTH_COMPLETE with status OK ([AASession.cpp L418-L428](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Session/AASession.cpp#L418-L428))
  through the plaintext policy; it pings every 5 s with a 15 s deadline
  ([SessionConfig.hpp L45-L46](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/include/oaa/Session/SessionConfig.hpp#L45-L46)).
- Phone behavior notes, OAA `02-version-ssl-auth.md`: TLS 1.2, phone as server, client
  authentication required, a phone trust manager that accepts almost any head-unit
  certificate. That is the phone judging the head unit, the opposite of the open
  question (spec §7.4).

Decisions (design choices, not established protocol facts):

- **OpenSSL 3.5.8 LTS, built by the project.** `tools/openssl-build.sh` builds the
  upstream tarball (SHA-256 from two sources, release signature verified) as static PIC
  archives per target with `no-shared no-module no-dso no-engine no-tests no-apps
  no-docs no-autoload-config no-filenames no-legacy no-ssl3 no-weak-ssl-ciphers no-md2
  no-rc5 no-idea no-seed no-camellia no-comp no-zlib no-ec2m no-psk no-srp no-srtp
  no-quic no-cms no-ocsp no-nextprotoneg` (plus `no-stdio` for Android, as
  `openssl-src` does), `SOURCE_DATE_EPOCH` set to the release date. `openssl-sys`
  links it through `<TARGET>_OPENSSL_DIR` and `OPENSSL_STATIC` (`tools/openssl-env.sh`).
  Not chosen: the `openssl-sys` vendored build (it accepts only `openssl-src` 300.x,
  whose 3.5 releases lag three patch versions and whose 3.6 line ends support on
  1 November 2026), Android's private libssl (spec §7.3), and rustls (no TLS 1.2 CBC
  suites if the head unit needs one).
- **Server configuration.** TLS 1.2 minimum and maximum; client certificate required
  (`SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT`); server cipher preference; no
  session tickets, session cache, renegotiation, or compression. `tls12-aead` (baseline):
  ECDHE-RSA with AES-128/256-GCM or ChaCha20-Poly1305, groups X25519, P-256, P-384,
  security level 2. `tls12-cbc-compat`: adds ECDHE/DHE/static-RSA CBC suites and
  DHE-RSA-GCM at security level 1, and exists only so a truck visit does not end if the
  recorded ClientHello proves the baseline has no common suite. It is reported as
  `tls_profile` and must not be used without that evidence.
- **Peer policy.** No CA store: chain errors are recorded (`tls_peer_verify_errors`) and
  only the leaf is judged, by SHA-256 over DER, either against pins (C1 lab pin, C3) or
  accepted once when first use is armed for that attempt (C2). A rejection sets
  `X509_V_ERR_APPLICATION_VERIFICATION`, so OpenSSL sends `handshake_failure`. After the
  handshake the leaf presented must equal the recorded accepted decision (defense in
  depth; unreachable in tests). The app pins a first-use fingerprint only when the report
  shows `tls_established=true`.
- **Classification.** Policy rejection or a missing client certificate →
  `TLS_CERT_POLICY`; OpenSSL `SSL_R_NO_SHARED_CIPHER` → `TLS_NO_COMMON_SUITE`; a received
  fatal alert or close_notify → `TLS_ALERT_PEER`; any other sent fatal alert (for
  example `protocol_version`) → `TLS_ALERT_LOCAL`; everything else → `TLS_ERROR`. Alerts
  are captured in both directions with `SSL_set_info_callback` (declared locally;
  `openssl-sys` does not bind it) and reported as `tls_alerts`.
- **Framing.** Handshake output is sent in plaintext 0x0003 messages, and during the
  handshake a failure's alert is still sent. After completion each outbound plaintext
  fragment (at most 16 KiB) is one `SSL_write`, so each encrypted frame carries complete
  records and fits the 16-bit length. Inbound, the session tracks record boundaries
  itself: frames whose ciphertext ends inside a record are held, must continue on the
  same channel with the same metadata as MIDDLE or LAST frames, and their plaintext is
  delivered to the assembler as one fragment (FIRST…LAST becomes a complete message whose
  length must equal the FIRST total). Any other frame on that channel meanwhile is
  `FRAME_INVALID`. TLS output that appears while decrypting (for example a refused
  renegotiation) fails the session.
- **Source identity.** RSA-2048, SHA-256, self-signed, version 3, random 127-bit serial,
  subject and issuer `CN=OpenAutoAndroid source, O=OpenAutoAndroid project`,
  basicConstraints critical CA:FALSE, keyUsage critical digitalSignature and
  keyEncipherment, extendedKeyUsage serverAuth, subject key identifier. **Validity is
  fixed to 2024-01-01T00:00:00Z – 2049-01-01T00:00:00Z** so a head unit with a lagging
  clock does not see a not-yet-valid certificate (the status enum has
  `CERT_NOT_YET_VALID`); 2049 is the last UTCTime year. The certificate and PKCS#8 key
  are one owner-only file, `files/identity/source-identity.pem`, written atomically,
  excluded from backup, never exported. It is created at app start, not while a head
  unit waits; a present but unreadable identity is an error rather than silently
  replaced. `nativeEnsureIdentity` then runs an in-process TLS 1.2 handshake and round
  trip against a local OpenSSL client with an ephemeral P-256 identity
  (`tls_self_test`), which is packaging evidence only.
- **Reports.** The ClientHello is decoded independently of OpenSSL (versions, suites,
  signature algorithms, groups, extension types; never the random, session ID, or server
  name) and is available even with `stop_after=version`. Peer certificate names keep O,
  OU, C, ST, and L; common name, serial number, and other attributes are replaced by
  their length because head-unit certificates may carry unit identifiers.

## Authentication, discovery, keepalive, and shutdown (lab work before the first hardware test)

- **AUTH_COMPLETE.** Accepted only after local TLS completion; decoded through the
  required-field schema, so a missing status is `AUTH_FAILED`, never success. Status must
  be 0 (reported with its enum name). Baseline plaintext; an encrypted AUTH_COMPLETE is
  accepted and reported as `auth_encrypted=true`. Before TLS completion it is
  `AUTH_FAILED` (premature).
- **Discovery request.** Exactly one, encrypted, after authentication:
  `device_name "OpenAutoAndroid"`, `device_brand "OpenAutoAndroid project"`,
  `session_info.session_uuid` (fresh per attempt), no icons, no phone model or account.
- **Discovery response.** Must be encrypted and decode through the schema (all nested
  required fields). Rejected (`DISCOVERY_INVALID`): more than 64 descriptors, a channel ID
  outside 1–255, a duplicate channel ID, or a descriptor with several service fields.
  Recorded as issues that only make that service unusable: video channels without
  configurations, unknown resolution or frame-rate values, invalid touch sizes (outside
  1–16384), input for a display without usable video. Usable video: an AV channel whose
  stream type is H.264 BP (3; also AVStreamType VIDEO in older schemas), config codec
  absent or 3, main display type, known resolution and frame rate; the most content pixels
  are preferred, then the most frame pixels, then the highest frame rate, with ties broken
  on the lowest channel and configuration index — but that is not the default.
  `Config::video_preference` chooses, and it defaults to a **pinned 800x480**, the only
  size observed to display on the one head unit available (see "A configuration can be
  advertised and not displayed" below); `largest`, `smallest`, and any other pinned
  `WIDTHxHEIGHT` are selectable, and a pinned size the head unit does not advertise leaves
  no usable video and records which sizes there were. Usable input: an input channel for
  that video's
  display (logical display IDs, defaulting to 0) with a valid touch screen or keycodes.
  `discovery_capabilities=usable` requires both; an empty but valid response is
  `insufficient`. Channel IDs are never assumed. Head-unit name, display name, car
  serial, vehicle ID, and Bluetooth address are reported only as lengths.
- **Keepalive.** A head-unit PingRequest (plaintext, or encrypted after TLS) is answered
  with a plaintext PingResponse echoing the int64 timestamp exactly; the bug-report flag
  is counted, never honored; response write latency is reported. During the hold, when
  the head unit has not pinged for 2 s, the source sends its own plaintext ping with a
  session-relative microsecond timestamp; a head-unit ping clears outstanding pings;
  when three are unanswered the next due ping fails with `KEEPALIVE_TIMEOUT`. AACS's
  encrypted ping responses are still not adopted.
- **Hold and shutdown.** After validated discovery the session holds (30 s by default,
  configurable up to 30 s), then sends an encrypted ShutdownRequest (reason
  USER_SELECTION) and waits 2 s for ByeByeResponse. Either the response, the timeout, or
  a transport close after the request ends with `HOLD_COMPLETE`. A head-unit
  ShutdownRequest (encrypted, required reason) is answered with an encrypted
  ByeByeResponse and ends with `PEER_SHUTDOWN`.
- **Other traffic.** After TLS, messages with no role in this session (service channels,
  unknown control IDs) are counted, the first 16 described, and skipped. Known control
  IDs out of place remain `UNEXPECTED_MESSAGE`.
- **Timers** (spec §7.6 defaults, configurable): version 5 s from descriptor open;
  ClientHello 5 s from the version response being written; TLS 10 s from the first
  handshake message; AUTH 10 s from TLS completion; discovery 10 s from the request being
  written; 30 s establishment cap until capabilities are ready; 10 s assembly expiry.

## Protobuf generation

`protocol/upstream/open-android-auto/` holds the 51-file closure, byte-identical at the
spec pin `61eab61` and the receiver's gitlink `5ff4aa2`. `tools/proto-gen.sh` runs
protoc 36.1 (archive SHA-256 pinned; binary SHA-1 equal to Maven Central
`com.google.protobuf:protoc:4.36.1`) and prost-build 0.14.4 (own lockfile) with
comments disabled and type names enabled, then writes `generated/schema.rs`, a table of
every message's fields (number, name, label, wire kind, nested type) from the descriptor
set. `aap_proto::decode` walks the raw bytes with that table (required fields present,
known fields with an allowed wire type, groups rejected, unknown fields skipped, nesting
at most 32) before prost decodes. Enum fields stay raw `i32`.

## Projection: audit and decisions (P4 lab slice)

Evidence at the pinned commits, for the messages P4 adds:

- **Channel open on the target channel.** [AACS ChannelHandler.cpp L34-L46](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/ChannelHandler.cpp#L34-L46)
  sends `ChannelOpenRequest` on the target channel with
  `FrameType::Bulk | EncryptionType::Encrypted | MessageTypeFlags::Specific` (0x0F).
  [Prodigy AASession.cpp L566-L610](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Session/AASession.cpp#L566-L610)
  accepts 0x0007 on the target channel only when its message type is Control (the 0x04
  bit) and answers on that same channel
  ([ControlChannel.cpp sendChannelOpenResponse](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Channel/ControlChannel.cpp#L189),
  [Messenger.cpp L100-L108](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Messenger/Messenger.cpp#L100-L108)).
  The spec §8.1 sample bytes (`00 07 08 00 10 03`, `00 08 08 00`) match the vendored
  schemas, and a proto test encodes them.
- **AV setup, start, stop, ack.** [AACS VideoChannelHandler.cpp](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/VideoChannelHandler.cpp)
  opens the channel, sends setup request `08 03` (codec H.264 BP), waits for the setup
  response, sends the start indication when a **focus indication** arrives, and streams
  0x0000 with a microsecond timestamp (`buffer->pts / 1000`) or 0x0001 without one.
  [Prodigy VideoChannelHandler.cpp](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/HU/Handlers/VideoChannelHandler.cpp)
  answers setup with `media_status OK`, `max_unacked`, and the accepted configuration
  indices, sends an unsolicited PROJECTED focus indication right after it, echoes focus
  requests, and acknowledges every media unit with `ack_count 1`. This is a lab receiver behavior, not the source frame-credit rule; see the correction below.
- **Mid-stream UI configuration (0x8009 / 0x8012).**
  [AVChannelMessageIdsEnum.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/av/AVChannelMessageIdsEnum.proto)
  names `UPDATE_UI_CONFIG_REQUEST = 0x8009` and `UPDATE_HU_UI_CONFIG_RESPONSE = 0x8012`
  at `confidence: bronze [apk_deep_trace]`; no schema for either body is published.
  **Settled on the vehicle (report 07ba8ae0).** Ford SYNC 4.0 sends 0x8009 on the video
  channel 2746 ms into projection — after projected focus at 953 ms and the first keyframe
  at 1624 ms — with the twelve bytes `0a0a0a080863106318002000`, which decode as
  `{1: {1: {top: 99, bottom: 99, left: 0, right: 0}}}`: a one-field wrapper around exactly
  the vendored `AdditionalVideoConfig` with its `display_insets` set. On the 1920x1080
  frame those insets give `0,99+1920x882`, the full-width rectangle Ford *advertises at
  discovery* once its expand control has been used (report 7fcc0aec) — the same rectangle
  reached two independent ways, which is what identifies the message.
  **This is Ford's expand mechanism**, not the version answer and not `resize_actions`.
  Every configuration with an expanded variant sends it, which is why 800x480 never did:
  its advertised insets already match the only layout it has.
  Consequences for this source: the insets are taken **literally**, not through the
  advertised-margin rule of spec §8.3, because an update contradicts the margins from
  discovery on purpose (0+0 horizontal against an advertised `margin_width` of 586); and
  the frame size does not change, so the encoder and EGL surface keep running and only the
  scene moves. Leaving it unanswered is what made every configuration above 800x480
  acknowledge every unit, hold projected focus, and display nothing: the picture appears
  for about a second and the head unit then withdraws the surface it is waiting to resize
  (reports e9326eea, 23f532aa, c6cb973c, 5336c065).
- **The reply is 0x800A, not 0x8012, and the id enum is incomplete.** Answering 0x8009
  with `UPDATE_HU_UI_CONFIG_RESPONSE` (0x8012) made Ford reply with an **empty message of
  id 0x00ff** — once per resize, in none of the eleven reports that preceded it — and still
  withhold the picture (reports 2d24d540, 8b0a178e, 2f5cadde). Android Auto 17.6's dex
  names **four** messages in this family, not the two the vendored enum lists:
  `MEDIA_MESSAGE_UPDATE_UI_CONFIG_REQUEST`, `..._UPDATE_UI_CONFIG_REPLY`,
  `..._UPDATE_HU_UI_CONFIG_REQUEST`, and `..._UPDATE_HU_UI_CONFIG_RESPONSE` — two pairs, in
  opposite directions. The vendored enum assigns only 0x8009 to the first and 0x8012 to the
  last, so 0x8012 belongs to the **phone-initiated** pair (with 0x8011, which the enum calls
  `UI_CONFIG_REQUEST`) and is not an answer to 0x8009.
  Every id in that enum appears in the dex as a 32-bit `const` — 0x8009 exceeds a signed
  16-bit literal, so `const/16` never holds it — and **0x800A appears twice while being
  absent from the enum**, which jumps 0x8009 → 0x800B. It is the only hole beside the
  request, request/reply pairs elsewhere in the enum are adjacent (0x8005/0x8006,
  0x8007/0x8008), and `UPDATE_UI_CONFIG_REPLY` is the only name in the family left without
  an id. So **0x800A is the reply**, and it is what this source sends. Its body is still a
  guess (empty); no schema is published. 0x00ff appears **zero** times as a literal in the
  phone's dex, so it is Ford's own rejection path rather than an Android Auto message.
- **What Android Auto 17.6 does on this vehicle** (logcat, 2026-09-18 12:01:47–12:02:18,
  `CAR.VIDEO`/`CAR.GAL`/`CAR.WM` at verbose). It takes **configuration index 0**
  (`Checking video config at index 0 codec:3 fps:60. isAllowed: true`), so Ford does
  display 1920x1080@60 and any failure to is this source's. Its geometry is the same as
  this source computes: `DisplayParams(selectedIndex=0, codecWidth=1920, codecHeight=1080,
  fps=60, dispWidth=1334, dispHeight=882, dispLeft=0, dispTop=99, dpi=240,
  pixelAspectRatio=1.002, scaledDimensions=1337x882)` — the frame is the resolution enum
  and the content rectangle is the margins applied to it, confirming spec §8.3 against a
  working client. On `UpdateUiConfigRequest` it moves `dispWidth` 1334 → 1920, keeps the
  codec at 1920x1080, logs `Updating H264 codec with bitrate: 12313920 based on width:
  1920, height: 882`, and then `sendUpdateUiConfigReply` — so the reply is a distinct
  message (this source sends 0x800A), the frame size does not change, and the bitrate is
  sized from the **content** rectangle. What it does that this source does not:
  **answers Ford's 1.6 request with 1.7** (`negotiated protocol version 1.7
  (STATUS_SUCCESS)`, which `match-1.7` now mirrors); opens **every** advertised channel
  (1,2,3,4,6,8,9,10,11,12) where this source opens video and input; never requests video
  focus, waiting for Ford's unsolicited grant; and configures its encoder with a 60-second
  I-frame interval and explicit QP bounds. Not involved: Material You theming, since Ford
  advertises palette version 0 and Android Auto logs `Not updating theme, palette version
  doesn't match. Current version: 2, HU version: 0`, so 0x8011 never fires here.
- **The stream this source emits is valid and correctly composed** (report e00fe2f2,
  captured with the app's video-capture option and decoded off the phone). 1820 frames of
  H.264 Baseline level 4.2 1920x1080 yuv420p decoded with **zero** errors or warnings under
  ffmpeg 8.1.2. Extracted frames show the scene inside the content rectangle with black
  only where nothing is drawn, and they track Ford's mid-stream resizes live: frame 900
  spans the full 1920 width after an expand push, frame 1750 spans 1334 after the push back
  to the split layout, matching `ui_config_last` in the same report. So a head unit can
  acknowledge every unit of a stream it is not displaying, and neither the encoder output
  nor the content-rectangle geometry can be inferred from its acknowledgements.
- **Field 1 of `AVChannelSetupRequest` is the codec type, not a configuration index.**
  The vendored schema calls it `media_codec_type`; [aasdk] calls the same field
  `config_index`, and the setup response answers with accepted configuration indices, which
  reads like a reply to a proposed one. Tested on the vehicle: sending the selected index
  (0) instead of the H.264 BP value 3 makes Ford SYNC 4.0 **drop the request without
  answering** — `av_setup_status=-`, `SETUP_TIMEOUT` after 10 s, and a complaint on its own
  screen (report bcf4184b, against 92aa4694 in the same session). So the vendored name is
  right, Ford validates the field, and it is not how a head unit learns which configuration
  to decode. `ProjectionConfig::setup_field` keeps both values selectable and the report
  records which went out.
- **Android Auto's video-channel message set, from the 17.6 dex.** Disassembled with a
  dex walker over `base.apk` (`dexdump` is in the pinned Android image; the classes were
  found by the log-tag strings they reference). `Lruz;` carries the `CAR.GAL.MEDIA` tag and
  touches 0x8000, 0x8001, 0x8002, 0x8004, 0x8005 and 0x800C; `Ljeu;` handles the resize —
  method `a` dispatches 0x8008, 0x8009, 0x800E, 0x800F and 0x8012, and method `I` logs
  `"UpdateUiConfigRequest %s"` and sends **0x800A**, independently confirming the reply id
  recovered earlier. `Livn;` holds `sendUiConfigRequest` (0x8011),
  `sendActionTakenNotification` (0x800C), the overlay messages and 0x8015.
  **None of 0x8011, 0x800C, the overlays or 0x8015 appear in the vehicle logcat**, so the
  working client did not send them here and they are not required for video. Google's
  post-TLS message set on this head unit is therefore the same as this source's, and no
  missing message explains the blank picture. What could not be read: `Ljeu;->I` performs
  several field reads before sending 0x800A, suggesting a body, but every call inside it
  goes through R8's `La$$ExternalSyntheticApiModelOutline0;->m` outlining stub, so the
  builder calls are not recoverable statically.
- **The 0x800A reply body, read from the dex.** `Ljeu;->I` disassembles cleanly once the
  invoke operand is taken from the right offset (the method index is at +2 in format 35c;
  reading +4 yields the register word and makes every call look like the same synthetic
  stub). With `registers=4 ins=2`, so `v2` is `this` and `v3` the parameter, it logs
  `"UpdateUiConfigRequest %s"` with `v3`, takes `Lxpx`'s default instance, calls
  `newBuilder`/`copyOnWrite`, does `iput-object instance.c = v3`, then
  `bitField0_ |= 1`, builds, and sends with id 0x800A. `Lxpx` (classes3.dex) has exactly
  one field besides the presence bitfield — `c: Lxpq` — and `Lxpq` is `AdditionalVideoConfig`
  by shape: three `Lxky` (`VideoInsets`) messages, an int theme, an int list, two lists, and
  a message, matching fields 1-8 of the vendored schema.
  So the reply is **`{1: AdditionalVideoConfig}` carrying the configuration just received**,
  with no status or ack field, and `|= 1` fixes it as field number 1. Since the request has
  the same shape, the reply body is the request's field 1 rewrapped — byte-identical. This
  source sent an empty body until that was read; Ford accepted the empty one at the framing
  level (no 0x00ff, `unhandled_messages=0`) but it was not what a working client sends.
- **That was the root cause** (report 709ffa9c). With the reply carrying the received
  configuration, Ford SYNC 4.0 displays `1920x1080@60`: `ui_config_reply_len=12`,
  `ui_config_updates=3 ui_config_responses=3`, and the operator confirmed the picture. So a
  head unit can accept a malformed reply at the framing level and silently withhold the
  surface, while acknowledging every media unit — nothing in the acknowledgements,
  counters, focus mode, or stream distinguishes it. Three things were *not* needed, and
  the working run had none of them: opening the extra channels
  (`projection_extra_opened=0`), waiting for unsolicited focus
  (`video_focus_requests=1`), or the 60-second I-frame interval.
  **Touch also lands at 1080p**, which the blank screen had made untestable: with the
  content rectangle at `0,99+1920x882`, `scene_taps=1,1,1,1` and the calibration pairs
  (targets 492,463 / 1428,463 / 492,792 / 1428,792 against raw presses 483,369 / 1434,379 /
  486,690 / 1419,697) put every mapped press within 15px of its target once `content.y`
  is added. That settles the vertical half of spec §8.3's origin rule, which until now had
  only ever been exercised at `content.y = 0`.
  Defaults changed accordingly: `Profile::Match17` and `VideoPreference::Largest`.
- **Acknowledgement semantics.** [AVMediaAckIndicationMessage.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/av/AVMediaAckIndicationMessage.proto):
  `session_id` is required and compared modulo 256; `receive_timestamps` takes priority
  over `ack_count`, which must be greater than zero.
- **Video focus.** [VideoFocusRequestMessage.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/video/VideoFocusRequestMessage.proto)
  has no field 1: `focus_mode` is field 2 and `focus_reason` field 3.
  [VideoFocusModeEnum.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/video/VideoFocusModeEnum.proto)
  defines NONE 0, PROJECTED 1, NATIVE 2, NATIVE_TRANSIENT 3, PROJECTED_NO_INPUT_FOCUS 4.
  Prodigy signals focus loss with NONE, so anything that is not PROJECTED or
  PROJECTED_NO_INPUT_FOCUS counts as lost here.
- **Input.** [InputEventIndicationMessage.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/input/InputEventIndicationMessage.proto)
  (required `timestamp`; touch at field 3, buttons at field 4),
  [TouchLocationData.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/input/TouchLocationData.proto)
  (x, y, and pointer_id all required), and
  [TouchActionEnum.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/input/TouchActionEnum.proto)
  (PRESS 0, RELEASE 1, DRAG 2, POINTER_DOWN 5, POINTER_UP 6). The enum mirrors Android's
  MotionEvent actions, so ACTION_CANCEL (3) is accepted as a cancel although the pinned
  enum omits it; anything else is rejected and counted.
  [AACS InputChannelHandler.cpp](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/InputChannelHandler.cpp)
  and [Prodigy InputChannelHandler.cpp](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/HU/Handlers/InputChannelHandler.cpp)
  agree on binding request 0x8002 → response 0x8003 and events 0x8001, scoped to the input
  channel.
- **Touch coordinate space.** [OAA display-rendering.md L99-L120](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/docs/display-rendering.md?plain=1#L99-L120)
  states that a head unit sets `touch_screen_config` to the **content** dimensions and
  sends coordinates in that space, and
  [video-resolution.md L30-L120](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/docs/video-resolution.md?plain=1#L30-L120)
  describes margins as a centered content region. Ford SYNC 4 (report c2407cff) instead
  advertises 1920x882 touch for a 1334x882 content rectangle at 1080p and 74x0 margins with
  left/right insets at 800x480. **Settled at G4 (report da9398a5): Ford sends frame pixels,
  not the space it advertises.** With 800x480 selected, a press on button 4 arrived as
  525,364 — inside the frame, nowhere near 1920x882 — and stretching the advertised area
  onto the content rectangle mapped it to 235,198, which is why all 21 taps of that drive
  registered on button 1. The advertised size is ignored by default; 1920x882 appears to
  describe Ford's native 1080p layout (full frame width by content height) rather than the
  selected configuration. **The origin is the content rectangle's, settled by the
  calibration in report 5c9ac430**, so `x_video = x + content.x`: pressing a cross drawn at
  four known frame pixels reported all four a uniform 39 ± 6 px left at unit scale (0.994),
  which is the 37px inset and not the ±18px opposite-signed error a grid stretched over the
  content rectangle would give. The two findings are independent and the combination is
  peculiar to Ford — the grid's *dimensions* are the frame's (it sends x up to 791 on an
  800-wide frame, past the 726-wide content rectangle) while its *origin* is the content
  rectangle's — so `TouchSpace::Content` means exactly that pairing and is the default.
  `ProjectionConfig::touch_space` keeps the documented `Frame` and `Advertised` behaviours
  available, and `lab-receiver --touch-space` reproduces any of the three. `input_raw_range`
  records how far the raw coordinates reach; note that it *cannot* settle the origin, since
  Ford normalises to the frame dimensions under either hypothesis and both predict the same
  0..800 span (docs/g4-g5-procedure.md §4).
- **Margins and insets.** [VideoConfigData.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/video/VideoConfigData.proto)
  fields 3 and 4 are the margins and field 11 carries `AdditionalVideoConfig`, whose
  `display_insets` gave Ford's asymmetric layout (1080p: left 0, right 586). The content
  rectangle uses the insets when they add up to the margins, otherwise the margins split
  evenly, and a disagreement is recorded as a discovery issue. Ford's three configurations
  describe one window at three resolutions: 1334x882 at 0,99, 890x588 at 0,66, and 726x480
  at 37,0, all within 0.1% of a 1.5125 aspect. What the margins reserve is its native UI —
  the 586px right panel that shows its nav or media widget, and the 99px status and tab
  bars at 1080p — so no configuration projects over the whole screen. Neither
  `resize_actions` nor `margin_configs` (fields 6 and 7 of `AdditionalVideoConfig`, the
  only resize signal in the protocol, and head unit → phone only) appears in any of them,
  and the protocol has no phone → head unit message that asks for a different geometry.
- **A configuration can be advertised and not displayed.** Ford advertises
  `1920x1080@60`, accepts it in its AV setup response, sends projected video focus, and
  then acknowledges every unit of it for the whole session while showing nothing: reports
  e9326eea (1596 frames, 1622 acks, 0 focus losses, 28 s), 23f532aa, and c6cb973c, across
  both layouts and both version profiles, where the 800x480 it also advertises displays
  correctly (report da9398a5). **No wire signal distinguishes the two cases** — the
  acknowledgements, the focus mode, and the counters are identical — so a source cannot
  detect this and fall back, and `Config::video_preference` exists for an operator to
  choose instead (`largest` by default, `smallest`, or a pinned `WIDTHxHEIGHT`). Untested
  at the time of writing: whether the limit is the frame size or the level, since
  1920x1080@60 is H.264 level 4.2 at the ~10.8 Mbps the pixel-scaled bitrate asks for,
  while the configuration index says nothing about frame rate, so encoding the same
  configuration at 30fps emits level 4.0 at half the bitrate. The app's encoder-rate
  control separates those.
- **What that failure is not.** Google's Android Auto displays correctly on the same
  vehicle from the same phone, so the limitation is in this source. Ruled out by
  measurement: the **H.264 level, the bitrate, and the frame rate** — 1280x720 at 30fps
  emits level 3.1 at 4.8 Mbps, the same level as the working 800x480@60, and still shows
  nothing (report 3c1908e0). Ruled out by arithmetic: the **`VideoResolution` enum
  mapping**, which the vendored schema marks `confidence: unverified`. Ford's three
  configurations agree on a 1.5125 content aspect to within 0.1% (726x480, 890x588,
  1334x882) only if 1, 2, and 3 mean 800x480, 1280x720, and 1920x1080; three independent
  margin pairs would not land there by coincidence.
- **The open suspect: what the margins mean for rendering.** The scene is drawn into the
  content rectangle, treating the margins as picture the head unit will not show. That
  costs 9% of the width at 800x480 and 43% of the **frame area** at both larger
  configurations, which is the right order of magnitude for a picture that is reported as
  not filling the screen and rendering off it. The two touch measurements do not settle
  which rectangle the head unit displays: the cross calibration (report 5c9ac430) found a
  pixel drawn at frame x=223 reported at ~186, an origin at the **content** rectangle,
  while `input_raw_range=x=8..791 y=8..479` on an 800x480 frame reaches outside that
  726-wide rectangle in both directions, suggesting a surface spanning nearly the whole
  **frame**. `AttemptSettings::sceneArea` (`content` or `frame`, app-side only, reported as
  `scene_area=`) exists to decide it on the vehicle; both readings predict the same
  near-correct picture at 800x480, which is why this survived G4 and G5.
- **Sensors.** [SensorRequestMessage.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/sensor/SensorRequestMessage.proto)
  (required type and refresh interval, phone → head unit, 0x8001) with
  [Prodigy SensorChannelHandler.cpp](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/HU/Handlers/SensorChannelHandler.cpp)
  answering 0x8002 and pushing 0x8003 events. The channel ID enum comments in
  `SensorChannelMessageIdsEnum.proto` name the opposite directions; the handler code wins,
  as spec §3.1 requires.

Decisions (design choices, not established protocol facts):

- Channel opens are **sequential**: video, AV setup, input, binding, optional sensor
  channel and one request per advertised restriction sensor, then focus. Each step has its
  own deadline so a report names the step a head unit did not answer.
- Only a `ChannelOpenResponse` **on the target channel with the 0x04 bit** is accepted. One
  on channel 0, one without the bit, or one with no open pending is counted
  (`projection_open_responses_misrouted`, `…_unexpected`) and ignored; the step then ends
  `SETUP_TIMEOUT`. A factory variation needs that evidence and an explicit profile, as spec
  §8.1 demands.
- **One** focus request (PROJECTED, USER_SELECTION), never repeated; an unsolicited
  PROJECTED indication starts the stream without one. NATIVE, NATIVE_TRANSIENT, or NONE
  pause the stream and clear the queue; the next PROJECTED resumes it with the codec
  configuration and a fresh IDR. `VIDEO_FOCUS_DENIED` after 30 s.
- **Flow control**: at most `max_unacked` units outstanding (a missing `max_unacked` means
  one, recorded as defaulted), a full window without an acknowledgement for 5 s is
  `FLOW_CONTROL_TIMEOUT`, and media is queued only while pending output stays below 256 KiB
  so control and input never wait behind video.
- **Congestion** drops a frame that finds the queue full and every later frame until the
  next IDR, which is preceded by the codec configuration; the app is asked for that IDR.
  Queued frames are never discarded individually.
- **Encoder output** is normalized to Annex B (length-prefixed input is converted), NAL
  types are validated, and the SPS is parsed for the report. A codec configuration buffer
  must hold SPS and PPS and no slice; a frame must hold a slice; an access unit mixing IDR
  and non-IDR slices is refused.
- **Sensors are opt-in** and limited to advertised DRIVING_STATUS and NIGHT_DATA with
  refresh interval 0. Sensor failures are recorded, never fatal.
- **Stop after TLS is graceful**: stop indication, shutdown request, then release after the
  ByeByeResponse or a 3 s grace period, so the head unit returns to its own interface.
- **Lab transport** (debug builds only): an abstract local socket named by an intent extra,
  forwarded to the controlled receiver by adbd. The platform refuses a `connectedDevice`
  foreground service without a connected device, so that path runs as an ordinary started
  service; it never pins a certificate and is reported as `transport=lab-socket:<name>`.

## Local categories added in the projection slice

- `CAPABILITIES_INSUFFICIENT`: discovery was valid but has no usable video or input for
  projection (spec §9 lists "missing configuration" only as failure evidence).
- `SETUP_TIMEOUT`: a channel open, AV setup, or input binding response did not arrive.
- `INPUT_BINDING_FAILED`: the head unit refused the advertised keycodes.
- `PROJECTION_COMPLETE`: the configured projection limit ran out and a shutdown request was
  sent (the projection counterpart of `HOLD_COMPLETE`).
- Spec §9 categories first used here: `CHANNEL_REJECTED`, `AV_SETUP_FAILED`,
  `VIDEO_FOCUS_DENIED`, `ENCODER_FAILED`, `FLOW_CONTROL_TIMEOUT`.

## Local categories added before the first hardware test

- `STAGE_LIMIT`: the session ended after the configured `stop_after` stage (replaces
  `TLS_NOT_IMPLEMENTED`).
- `HOLD_COMPLETE`: the discovery-only hold ended with a shutdown request.
- `PEER_SHUTDOWN`: the head unit requested shutdown and was answered.
- `TLS_ERROR`: a TLS failure without an alert, suite, or policy explanation, including
  a missing identity.
- `AUTH_TIMEOUT`, `DISCOVERY_TIMEOUT`: stage deadlines (spec §9 lists timeouts only as
  failure evidence).
- `IDENTITY_UNAVAILABLE` (`nativeStart` result, reported as `NATIVE_START_FAILED`): the
  configured source identity could not be loaded.

## Remaining source links for spec §15

- AACS [README](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/README.md)
  (states that only the head-unit key/certificate came from openauto; the
  `AAServer/ssl/android_auto.key` private key must never be used or copied);
  [video source VideoChannelHandler.cpp](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/VideoChannelHandler.cpp).
- OAA [schema corpus](https://github.com/mrmees/open-android-auto/tree/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa),
  [video](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/docs/channels/video.md)
  and [input](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/docs/channels/input.md)
  channel notes, [ServiceDiscoveryRequest](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/control/ServiceDiscoveryRequestMessage.proto)
  (all fields optional; `session_info` field 6), and
  [PingRequest](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/control/PingRequestMessage.proto)
  (`required int64 timestamp`, optional `bugreport_request`).
- Prodigy [Messenger.cpp](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Messenger/Messenger.cpp)
  and [FrameAssembler.cpp](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Messenger/FrameAssembler.cpp).
- AACS [video source VideoChannelHandler.cpp](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/VideoChannelHandler.cpp)
  and [InputChannelHandler.cpp](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/InputChannelHandler.cpp);
  Prodigy's head-unit [VideoChannelHandler.cpp](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/HU/Handlers/VideoChannelHandler.cpp),
  [InputChannelHandler.cpp](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/HU/Handlers/InputChannelHandler.cpp),
  and [SensorChannelHandler.cpp](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/HU/Handlers/SensorChannelHandler.cpp),
  which the lab receiver registers unchanged.
- Not audited: aabox (no pinned commit in the spec) and Android SSLEngine documentation
  (not used). The TLS messenger internals of AACS and Prodigy are audited above.

## Video frame-credit correction (0.3.2-lab)

The local 17.6 APK protocol audit distinguishes decoder configuration from frames.
In `ivl.a`, codec configuration goes through `jbq.n`/`jbq.q` as untimestamped
0x0001 without acquiring a flow-control permit. In `ivl.b`, frame output first
calls `jdr.E` (the flow-controller gate), then `jdr.D` to send timestamped 0x0000.
These paths were inspected in the existing `/tmp/openauto-aa176/decompiled/sources/defpackage`
audit tree. No proprietary implementation was copied into the project.

Our P4 sender incorrectly consumed a credit for both paths. A receiver that batches
four frame acknowledgements could receive only three frames plus configuration and
then wait forever while our four-unit window was full. Even if it acknowledges frames
one at a time, configuration resends leak further credits. Count only timestamped
frames; leave the negotiated window and timeout intact. The deterministic receiver
now acknowledges only frames, and a regression exercises five batches with repeated
configuration and a window of four. The Prodigy lab adapter similarly passes
configuration to its decoder without acknowledging it (using the presence of VCL
slices because its callback discards the original message ID).

## Audio out on Ford SYNC 4.0 (P5.3, first vehicle attempt, 19 September 2026)

Reports `f9ce185b` and `dc2c60ee` (0.3.7-lab, F-150 2024, SYNC 4.0 `25221_PRODUCT.2025`),
two attempts a minute apart. Both agree, and both leave **G7 unmet**.

- **Ford's audio advertisement, recorded for the first time.** The `audio_configs` contents
  had gone unrecorded for four connections because the report only counted them. All four
  channels carry exactly one configuration:

  | ch | codec | audio type | config |
  | --- | --- | --- | --- |
  | 4 | 1 `MEDIA_CODEC_AUDIO_PCM` | 3 `MEDIA` | 48000/16/2 |
  | 5 | 4 `MEDIA_CODEC_AUDIO_AAC_LC_ADTS` | 3 `MEDIA` | 48000/16/2 |
  | 6 | 1 `MEDIA_CODEC_AUDIO_PCM` | 1 `SPEECH` | 48000/16/1 |
  | 7 | 4 `MEDIA_CODEC_AUDIO_AAC_LC_ADTS` | 1 `SPEECH` | 48000/16/1 |

  Media is the conventional 48000/16/2. **Speech is not the conventional 16000/16/1**: Ford
  advertises 48 kHz mono for it. The P5 plan's "1024 samples = 2048 bytes = 64 ms at 16 kHz
  mono" figure therefore does not apply to this vehicle, and a speech channel here would be
  48 kHz. Both media channels are the same format and differ only in codec, so choosing PCM
  over AAC costs nothing in fidelity on this head unit.
- **The channel opens and the AV setup succeeds.** `projection_open_004=audio … status=0
  (OK)` at 67 ms, `audio_setup_status=2 (OK)`. So the answer to "will Ford open an audio
  channel on request" is yes, for PCM `MEDIA` at least.
- **Ford's audio window is 40**, against a video window of 4 (`av_setup_window=4`,
  `audio_setup_window=40`, both runs). That is neither the 1 that openauto and mikereidis
  hardcode nor the 24 that "ackless mode" is reported to pin, and it is ten times the video
  window on the same head unit in the same session. A window of 40 at 42.7 ms a buffer is
  1.7 seconds of credit, so the per-buffer round-trip budget the P5 plan worried about does
  not bind here.
- **Ford refuses audio focus.** `AUDIO_FOCUS_REQUEST` with `audio_focus_type=1` (GAIN) on
  the control channel is answered with `AudioFocusResponse { audio_focus_state = 3 (LOSS),
  granted = false }`. Nothing was audible. Everything else in the session was unaffected:
  50.7 s of 1920x1080 projection, `unhandled_messages=0`, now-playing publishing normally
  (29 status and 4 metadata messages in the second run), graceful shutdown.
  **Unlike the video-focus case there is no ambiguity about what the head unit did** — it
  said no explicitly, with both the state and the boolean.
- **Why it refused is not established.** The candidates, none of them tested:
  the request is sent ~47 ms after the video start indication and Ford may simply not be
  ready; Ford may grant media focus only once the operator selects the projection as the
  audio source on its own interface; Ford may want the audio channel's start indication
  first; or an ordinary source may not be granted media focus at all on this firmware.
  Distinguishing them needs another drive, not more reading.
- **Two defects in this source were found by the attempt, and fixed** (0.4.0-lab). The
  report recorded only the *latest* focus state, so a denial and a loss-then-grant were
  indistinguishable — it now records the whole sequence with timings. And a non-grant tore
  the audio down permanently and sent a `RELEASE` for a grant that was never held; a loss
  now pauses the stream and leaves the request standing, so a head unit that grants late
  starts the audio, and `RELEASE` is sent only for focus actually held. Neither fix is
  known to change what Ford does — they are what makes the next attempt interpretable.

## Audio focus in Android Auto 17.6, from the dex (P5.3, 19 September 2026)

Read after Ford refused focus, to find out whether the request itself was wrong. Method as
for the 0x800A work: `base.apk` unpacked from the 17.6 APKM, `dexdump -d` over all four
`classes*.dex` in the pinned Android image, classes located by their own log-tag and
log-format strings rather than by assuming the class names upstream recorded for 16.2.
Nothing was copied into the project. The upstream `oaa/audio/*.audit.yaml` sidecars pin the
schema but mark their sequence diagrams "Unverified", so they do not answer any of this.

Classes, each identified by a literal it contains: `Lxii;` `AudioFocusRequest`, `Lxij;`
`AudioFocusType`, `Lxik;` `AudioFocusStateType`, `Lirf;` the phone-side focus handler,
`Lird;` its timeout handler, `Lisy;` the external-app focus dispatcher, `Lisw;` AA's own
Android focus request, `Ljab;` the control channel (`CAR.GAL.AUDIO`), `Ljdr;`
MediaSourceEndPoint, `Liqu;` the stream allocator (`CAR.AUDIO.CHANNEL`), `Liqd;` the
capture thread.

- **The request body is byte-for-byte what this source sends.** `Lirf;->l` writes the enum
  value into `Lxii.c`, sets `bitField0_ |= 1` (fixing it as field 1), and sends id 18.
  `Lxii;` has no second payload field. For a media app taking focus, `Lirf;->m` maps
  Android `AUDIOFOCUS_GAIN` → `AudioFocusType.GAIN`, so the wire body is `{1: 1}` — exactly
  ours. **Ford's denial is therefore not a malformed-request problem**, unlike the 0x800A
  case, and no field is missing.
- **The trigger is an app taking Android audio focus on the phone, not session setup.** The
  only path to 0x0012 is `Lirf;->m(int)`, whose log line is `"Sending %s to HU for app %s
  due to Android focus change: %s"`, reached from `Lisy;->run()` — the
  `onExternalAppGainAudioFocus` runnable. Audio-channel registration (`Lirf;->o`) starts the
  focus threads and asks the *phone's* `AudioManager` for focus (`"Requesting initial
  Android audio focus"`) but sends no GAL message. **This is the one substantive difference
  from this source**, which asks once the projection reaches its streaming step.
- **The message carries no channel or stream id.** Focus is a single state per connection;
  which stream it applies to is decided locally by `Liqu` (`"requestBottomHalfForStream
  granted to stream %s"`). So there is no separate media-versus-speech focus request, and
  nothing in the request tells the head unit which channel it is for.
- **Android Auto gates the audio start indication behind the grant, which this source
  already does.** `Liqd` buffers captured PCM while focus is missing (`"No bottom half
  immediately available for stream %s. Waiting %d ms for focus response."`, then `"Saved
  audio data to system capture queue until focus received"`, then `"discarding frame due to
  not having focus"`), and only on the grant does `Ljdr;->F` log `"start MediaSourceEndPoint
  sessionId: %d"` and send 0x8001. That independently confirms the P5 plan's ordering claim,
  which until now rested on aasdk alone. This source sends 0x8001 only from `start_audio`,
  i.e. only on a grant — report dc2c60ee has `audio_start_session=-`, so none went out.
- **A LOSS is authoritative and is not retried.** `Ljab;` case 19 logs `"Got audio focus
  state from car:%s"` and hands to `Lirf;->i`, which on a loss logs `"Request Android audio
  focus upon loss from HU."` and takes focus away from the playing app. Google's client does
  not argue with a refusal either; it stops. That is what 0.4.0-lab now does.
- **Unsolicited head-unit focus states are explicitly modelled** —
  `Lirf;->i`'s format string carries `unsolicitedResponse=%b` — which corroborates treating
  a focus state as a state machine rather than as an answer to a question.
- **What this source does not do: retry a GAIN that is never answered.** `Lird;` sets a
  **500 ms** timeout per request and on expiry logs `"Car focus response time-out for focus
  request: %s, attempt#: %s"` and resends the identical request, up to **10 attempts for
  GAIN** and **1 for every other type**, then gives itself `AUDIO_FOCUS_STATE_LOSS`. This
  source asks once and waits 10 s. It did not matter here — Ford answered — so it is
  recorded rather than implemented.
- Other guards in `Lirf;->m`, none of which apply to a single request from an idle source:
  one outstanding request at a time (`"Received new focus while waiting for HU response"`),
  deferral during a voice session, suppression when the held state is already sufficient
  (`"Not sending focus request to HU as MD already hold sufficient focus: %s"`), and an
  in-call guard.

**Not determined: why Ford refused.** Nothing in the dex is vehicle-specific about focus,
and this is a static read of the phone's code, not a capture of Google's client being
granted focus on this truck. The finding narrows the candidates rather than settling them:
the request is right, the ordering is right, and the remaining difference is *when* it is
asked — Android Auto only ever asks on behalf of an app that is playing, which on this head
unit is plausibly also the point at which the driver has selected the projection as the
audio source.

## `AudioFocusResponse` field 2 is not `granted` (P5.3, 19 September 2026)

Second vehicle attempt, 0.4.0-lab, reports `5cb646ec` and `9eb3a396`, half an hour after
the first pair. The operator went looking for an audio source selector on SYNC's own
interface and **could not select the projection** — it was not offered as a source. The
head unit's answer to the focus request changed anyway:

| run | build | `audio_focus_state` | field 2 |
| --- | --- | --- | --- |
| f9ce185b, dc2c60ee | 0.3.7-lab | 3 `LOSS` | false |
| 5cb646ec, 9eb3a396 | 0.4.0-lab | **1 `GAIN`** | **false** |

**Field 2 is false in both, so it cannot be what distinguishes a refusal from a grant.**
That is a two-sided observation from this vehicle alone and does not depend on reading any
decompiled code: if the field meant `granted`, a head unit answering `GAIN` would have set
it true. The vendored schema's name for it —
[`optional bool granted = 2`](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/audio/AudioFocusResponseMessage.proto),
confidence "silver" — is therefore not something a source may act on. **What the field means was then settled against the 17.6 and 15.7 dex: it is
`unsolicited`.** True means the head unit volunteered the state, false means it is the
reply to a request. The evidence:

- The payload class is `Lxih;` in 17.6 (`Luuj;` in 15.7), parsed in `Ljab;` case 19. Its
  generated `RawMessageInfo` descriptor decodes to exactly two payload fields —
  `required enum = 1`, `optional bool = 2` — and the string is **byte-identical** between
  the two versions, so the structure our vendored copy has is right and there is no
  version skew. (The same descriptor also shows 17.6 marks
  `AudioFocusRequest.audio_focus_type` **required** where the vendored copy says optional.
  Harmless: this source always sets it.)
- `Ljab;` reads field 2 straight off the message (`iget-boolean v12, v12, Lxih;.c:Z`) and
  hands it to `Lirf;->c`, which logs it under its own name:
  `"Received focus change from HU: %s, unsolicited: %s"`. Two more strings corroborate —
  `unsolicitedResponse=%b` in the big `CarAudioFocusEvent` line, and `"Received unsolicited
  GAIN_TRANSIENT_GUIDANCE_ONLY focus change from HU"` on the flag-set branch. The
  companion `gearheadForcedResponse` is *not* from the wire: it is a local constant, true
  only on the client's own synthesized timeout path.
- **Nothing gates on it.** `Lirf;->i` switches on the state ordinal, and the GAIN branch
  calls `Liqu;->b(true, true, …)` — enabling the media and TTS streams — at the
  instruction *before* the boolean is loaded. The flag is read afterwards only to skip a
  latency metric and to skip a "does this match the request I sent" sanity check
  (`"Focus GAIN from HU does not match focus request sent to HU: %s"`), both of which are
  meaningful only under an "unsolicited" reading and nonsensical under a "granted" one.

The field *name* itself is obfuscated to `c` in both APKs, so "unsolicited" is Google's own
word for the value in three log strings rather than a retained symbol. That is the
strongest static evidence available, and it agrees with the vehicle.

**This source gated on it and threw a real grant away.** `gained` required
`granted != Some(false)`, so both of the 0.4.0-lab runs recorded `audio_focus_denials=1`
and `audio_started_ms=-` against a head unit that had said `GAIN`. Fixed in 0.4.1-lab:
only `audio_focus_state`, the message's one `required` field, decides. Field 2 is reported
as `audio_focus_unsolicited` and used the way Android Auto uses it — to tell a reply apart
from a volunteered state change, which is what separates `audio_focus_denials` (the head
unit refusing something we asked for) from `audio_focus_volunteered`. aasdk's copy of the message has no such field at all and the pinned
Prodigy receiver never sends one, so nothing else in the family treats it as load-bearing
either.

The lab could not have caught this. Both ends of the simulator were written from the same
reading of the same schema, so `hu-sim` sent `granted=true` with a grant and the source
required it — the simulator agreed with the bug. **This is the general hazard in a
reverse-engineered schema: a field whose meaning comes from a name rather than from
behaviour will be modelled identically wrong on both sides of a test.** `hu-sim` now sends
field 2 false on every reply and true only on a state it volunteers, and
`a_gain_is_a_grant_whatever_field_two_says` asserts all three shapes of the field start the
stream.

**Why the state changed from LOSS to GAIN between the two attempts is not established.**
The differences between the runs were: sensors off versus `all`, and one video focus
loss/regain in the later pair as the operator moved through the head unit's menus. Neither
is a plausible cause on its own, and the vehicle's own audio state was not recorded. The
honest reading is that Ford's focus answer is not a constant and at least one of its inputs
is outside this source's view — which is a reason to treat `audio_focus_sequence` as the
evidence and not to infer a policy from any single run.

## G6 and G7 pass, and P5.2's vehicle answer (19 September 2026)

Third audio attempt, 0.4.1-lab, reports `a24db7e0` (12.4 s) and `7f869585` (41.7 s). Both
audible. Sensors were set to `all` in the same runs, which answered P5.2's outstanding
question as a by-product.

### G7 audio — **met**

Operator: a steady two-tone chord from the vehicle speakers, which is the app's own
440 Hz/880 Hz signal. Report side, `7f869585`:

```
audio_focus_sequence=104:1:false                     GAIN, as a reply
audio_started_ms=104  audio_buffers_sent=958  audio_media_bytes_sent=7847936
audio_underflows=0  audio_dropped_congested=0  audio_invalid=0  audio_acks=955
audio_outstanding_max=5 (window 40)
audio_stop_indication_sent=true  audio_focus_released=true
```

40.8 s of PCM, **zero underflows**, zero drops, focus acquired and released cleanly. The
pacing worry in the P5 plan does not materialise on this head unit: at a window of 40 the
source never came close to filling it (`audio_outstanding_max=5`), and the head unit's
mixer never ran dry. The tone is app-generated; **no other application's audio travels on
this channel**, so a music player responding to a button is coming over Bluetooth A2DP and
is not evidence about this path.

### G6 media — **met**

Operator: the track and owning application appeared on **Ford's own interface**, outside
the projected scene, with notification access granted. Report side:

```
transport_keys=6 transport_actions=play-pause:4,previous:1,next:1
media_control_access=granted media_control_dispatched=6 media_control_refused=0
media_control_source=Spotify media_control_last_action=play-pause
media_status_sent=16 media_metadata_sent=7 media_track_sent=true
```

**Correction to a first impression: the transport buttons were not unlocked by audio.**
They already arrived and dispatched with no audio channel streaming — `5cb646ec` has
1 key / 1 dispatched / 0 refused and `9eb3a396` has 2 / 2 / 0, both from runs where audio
never started. The operator had simply not pressed them before that, and with nothing
audible the result of a press was imperceptible. Nothing about the input or `media_info`
path is conditional on audio focus, and no such dependency should be inferred.

### P5.2 — which of Ford's nine advertised sensors carry values

All nine subscriptions accepted (`sensor_requests=…:0` throughout), 296 events, no errors:

```
sensor_populated=1,3,7,8,10,13,21    sensor_silent=6,24
sensor_value_001=LOCATION      count=208
sensor_value_003=CAR_SPEED     count=42   speed=0
sensor_value_007=PARKING_BRAKE count=1    parking_brake=false
sensor_value_008=GEAR          count=1    gear=101
sensor_value_010=NIGHT_DATA    count=1    is_night=true
sensor_value_013=DRIVING_STATUS count=1   status=0
sensor_value_021=GPS           count=42   satellite_count=21
sensor_value_006=FUEL_LEVEL    count=0    (accepted, silent)
sensor_value_024=TRAILER       count=0    (accepted, silent)
```

**This is the input P5.5 was waiting for: LOCATION, CAR_SPEED and GPS all carry real
values**, so the owned map's position source can be the vehicle's rather than Android's.
Position stayed redacted (`sensor_location=redacted`), which is why the coordinates are
absent here and the fix is still evidenced by its accuracy, altitude and satellite counts.

**Units and encodings are *not* established, and three values do not read sensibly:**

- `bearing=103553459` in `GpsLocation`. A bearing cannot be that in degrees, tenths of a
  degree, or anything else obvious; either the field carries something other than a
  bearing on this head unit or its scaling is unknown.
- `satellites_used=32` against `satellite_count=21`, with 32 entries and 21 `in_use`. The
  two fields disagree, so at least one does not mean what its name says.
- `gear=101`, which is not a value in any gear enum in the vendored schema.

`accuracy=2421` and `altitude=11490` are plausible in some scaling but unverified. The
slice's question was *which types carry values*, and that is answered; **how to interpret
them is a separate, open question**, and P5.5 must not assume a unit it has not checked.
Stationary vehicle throughout, which is why CAR_SPEED is 0 and most types sent one value.

## Ford SYNC 4.0's "Android Auto" tile was a no-op after a native takeover — a second silent-withholding bug, found the same way as 0x800A (19 September 2026)

**Symptom.** When Ford took the screen for its own UI — the Audio page, Settings, the
reversing camera — this source's projection never came back. The taskbar "Android Auto"
tile, which is how the driver hands the screen back, was a no-op no matter how many times
it was tapped; only unplugging and replugging the accessory restored projection. Google's
Gearhead 17.4 on the same vehicle, same day, same gesture, recovers in about two seconds.
The investigation that narrowed this down by elimination — channel coverage, audio-source
arbitration, Bluetooth pairing linkage, and stream continuity — is
[docs/ford-focus-reattach-investigation.md](ford-focus-reattach-investigation.md); this
section records the confirmed cause and fix.

**The root cause.** On video focus loss (NATIVE, NATIVE_TRANSIENT, or NONE) this source
*paused* the stream — stopped feeding the encoder — but sent nothing on the wire to say so.
Ford's GAL layer therefore never learned the projection had left the foreground, so its own
"Android Auto" tile had nothing to switch *to*, and it went on acknowledging every video
unit of a stream that had stopped moving. This is the same shape as the 0x800A reply-body
bug above: nothing in the acknowledgements, the counters, the focus mode, or the stream
itself distinguished a head unit that is displaying a session from one that is merely
willing to keep accepting its bytes.

**The dex chain, read the same way as the 0x800A reply body and the P5.3 audio-focus work**
(`dexdump -d` over `classes.dex` in the Android Auto 17.6 `base.apk` in the project root,
classes and methods located by the literal strings they log rather than by assumed names;
full disassembly at
`~/.cache/openauto-android/gearhead-2026-09-19/dex-video-focus-stop-start.txt`):

- `Livn;->x(ZZ)V` is `doHandleVideoFocusLoss` (two booleans: unsolicited, transient). It
  tears down the display sink — posting to a handler, waiting on a semaphore with a 15 s
  timeout, logging `"handling display removal timed-out"` if that fires — and
  unconditionally ends by calling `Ljdr;->x()V`, `Ljdr` being `MediaSourceEndPoint`.
- `Ljdr;->x()V` is the stop path; it carries the literal
  `"Trying to stop Media before starting, ch:%d"` on its early-return branch. On the real
  path it saves the current session id (`Ljdr;.f`) into `Ljdr;.d`, **increments** `Ljdr;.f`
  for next time, calls `Ljdr;->w(Lxpj;)V`, and clears its started flag (`Ljdr;.g`).
- `Ljdr;->w(Lxpj;)V` sends message id **0x8002** (`AV_CHANNEL_STOP_INDICATION`) — the
  constant loads as `#00008002` straight into the same send primitive (`Ljbq;.k`) that the
  start path uses.
- The regain path logs `"start MediaSourceEndPoint sessionId: %d"`, reads the **new**
  (already-incremented) session id out of `Ljdr;.f`, writes it into `Lxpi;.c` with
  `bitField0_ |= 1` (field 1), and calls `Ljdr;->v(Lxpi;)V`, which sends **0x8001**
  (`AV_CHANNEL_START_INDICATION`) — the sibling send in the same disassembly.

**The flow-controller misreading, and why it mattered.** An earlier reading of a working
Gearhead session log had found `"Initialize video source flow controller with max unacked
allowed: 4"` logged exactly once per connection, and concluded from that single
initialisation that Gearhead never restarts the AV stream around a focus transition — only
pauses it, the same as this source did. That does not follow: the per-connection flow
controller — the negotiated window and acknowledgement accounting — is a channel-level
object with no reason to be torn down by a stream-level stop indication, and the dex
confirms it survives untouched; this source's own fix likewise leaves the channel and its
acknowledgement window alone (only the stream session restarts). A single flow-controller
initialisation is consistent with any number of stop/start indication pairs during the
session — it excludes nothing. Treating "initialised once" as "never restarted" cost a
round of investigation chasing channel coverage, audio arbitration, and Bluetooth pairing
before the dex was read. **The general lesson: a resource's lifecycle log line proves
things only about that resource, not about the higher-level protocol event it happens to
survive.**

**The fix (0.4.5-lab, versionCode 21).** `stop_video_stream` and `restart_video_stream` in
`core/aap-session/src/projection.rs`, mirroring the dex chain above. On NATIVE /
NATIVE_TRANSIENT / NONE: send 0x8002, clear the queue, zero `outstanding`, and increment the
video session id — matching `Ljdr;->x()V` bumping `Ljdr;.f`. On the next PROJECTED: send
0x8001 carrying the new session id, re-arm the first-unit deadline, and emit fresh
configuration plus an IDR — matching `Ljdr;->v` reading the incremented id back out. The
previous session id is kept as `prev_session_id` (named after `Ljdr;.d`, where Android Auto
stashes the outgoing id before bumping it) so that acknowledgements for units already in
flight when the stop went out are recognised on arrival and counted as
`video_acks_previous_session` rather than as wrong-session or invalid. `spec.md` §8.2 is
amended accordingly: "A focus transition stops and restarts the stream rather than pausing
it."

**Vehicle confirmation, report `c3ea4fd6` (2026-09-19T22:35Z, 0.4.5-lab, same F-150/SYNC
4.0 as every report above):**

```
ui_config_updates=2 ui_config_responses=2
video_focus_stop_indications=1 video_focus_restarts=1
video_start_session=1 video_start_config=0 video_started_ms=14205
video_focus_sequence=10366:1:true:started,13036:2:true:paused,14205:1:true:resumed
video_acks_wrong_session=0 video_acks_previous_session=0 video_timestamp_regressions=0
input_events=2 input_dropped_unfocused=2
audio_focus_state=1 (GAIN) audio_focus_unsolicited=true audio_focus_volunteered=1
projection_extra_opened=0
```

`ui_config_updates` had been 1 in every report ever taken of this bug — Ford's HMI never
recomposed after a regain. Here it is 2: the taskbar tile worked. `video_start_session=1`
is likewise the first nonzero value in any report cited in the investigation; previously
the stream was formally started once per connection and never again. From the `[events]`
log, session-relative: focus lost `+18469ms` (mode=2, NATIVE), focus regained `+19639ms`,
and Ford's second `UpdateUiConfigRequest` — the recompose — landed at `+20212ms`, **573 ms**
after the regain. A captured working Gearhead session on the same vehicle had shown the
equivalent gap at **632 ms** (its second `UpdateUiConfigRequest` 632 ms after its focus
grant). Ford ran the same post-regain re-attach sequence for this source that it runs for
Google's own client — the two numbers are close enough, on unrelated implementations, to
call it the same head-unit-side procedure rather than a coincidence. `video_focus_sequence`
still shows Ford re-granting focus automatically about 1.2 s after the loss, with no tap in
between, same as before the fix: the fix does not depend on whether that auto-regrant is a
Ford policy or a side effect of something else, only on a formal stop/start pair existing
for it to trigger against. `video_acks_wrong_session=0 video_acks_previous_session=0` and
no timestamp regressions — the session-id bookkeeping did not misattribute anything, though
no acknowledgement happened to still be in flight when the stop went out in this run, so the
`_previous_session` counter is shown not to mis-fire rather than shown to fire correctly
under contention. Not recorded in the report: which of Ford's own UI states the operator
had exercised before the loss; what the run's own counters do establish is that neither
`open_all_channels` nor the (now-removed) video-refocus experiment was active
(`projection_extra_opened=0`, `video_refocus=off`), so the fix was confirmed in isolation.

**Negative results, ruled out along the way:**

- *Re-requesting video focus after a loss.* Implemented behind a flag, then removed:
  unreachable in practice, because Ford restores focus in ~1.5 s on its own — faster than
  any reasonable re-request deadline — and real Android Auto never requests video focus on
  this head unit at all (`InitialVideoFocus: Disabled by setting.`, from the captured
  Gearhead session).
- *Rebuilding the encoder on focus regain.* Mirrors what Android Auto's own
  `doHandleVideoFocusGained` does (it re-creates the codec on every grant) and is retained
  in this source's code, but on its own did not fix the bug: report `3f9688b7` had a clean
  rebuild (`projection_rebuilds=1`) and `ui_config_updates` was still 1, tile still dead.
- *Channel coverage.* Android Auto opens ten channels on this head unit; this source opens
  far fewer. The confirmed-fix run had `projection_extra_opened=0` — no extra channels,
  bare or otherwise — so channel coverage was not the cause of the tile bug (a related but
  separate question, from the earlier 800x480-vs-1920x1080 blank-picture investigation, had
  already ruled channel coverage out for that symptom too).
- *Sensor coverage.* A report with all nine advertised sensor types streaming (86 events)
  still had the tile dead, so sensor subscriptions were never a candidate either.

**The generalisable lesson.** This is the second time a head unit has acknowledged
everything while withholding the display: first the 0x800A reply body (§"Mid-stream UI
configuration" above), where Ford accepted a malformed reply at the framing level and
withheld the surface while acknowledging every media unit; now this, where Ford accepted a
stream it no longer considered foreground and kept acknowledging it regardless. In both
cases every counter a source can see from the wire — acks, flow-control state, focus
mode — looked identical between the working and the broken case. **Acknowledgements cannot
be used as evidence that a head unit is showing what you send.** The only way either bug
was found was by reading what a client known to work actually does, at the bytecode level
when the logs alone were ambiguous or silent.

## The `navigation` channel and navigation focus (P6.2, 21 September 2026)

The head unit's own instrument cluster, `NAVIGATION_STATUS` (GAL service type 10) — a
display separate from the projected surface. Nothing in this section is wire observation
from a vehicle: **no navigation message has ever been on this project's wire with a head
unit at the other end of it**, and what follows is schema reading plus an independent
implementation agreeing with it.

| Fact | Evidence | Independent agreement |
| --- | --- | --- |
| Focus is a **control-channel** exchange, ids 13 and 14 | [OAA NavigationFocusRequestMessage.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/navigation/NavigationFocusRequestMessage.proto) says so in as many words ("This is a CONTROL CHANNEL message (GAL service type 1, hzh.java), NOT a navigation channel message"), and `ControlMessageIdsEnum.proto` carries both values | Prodigy's `ControlChannel` emits `navigationFocusRequested` from `MSG_NAV_FOCUS_REQUEST` and answers on **0x000e** ([ControlChannel.cpp L130, L233](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/src/Channel/ControlChannel.cpp)) |
| `NavigationFocusType` has **no release value**: `NAV_FOCUS_NATIVE` 1, `NAV_FOCUS_PROJECTED` 2 | Same file. Audio focus by contrast has an explicit `RELEASE` | — |
| Channel message ids: 0x8001 start, 0x8002 stop, 0x8003 state, 0x8004/0x8005 deprecated, 0x8006 notification, 0x8007 distance, 0x8008 energy | [OAA InstrumentClusterMessages.proto](https://github.com/mrmees/open-android-auto/blob/61eab61c5f9968154ff1a80faa8c0a427b208479/oaa/navigation/InstrumentClusterMessages.proto) handler trace of `ian.java` | Prodigy's `NavigationMessageId` names 0x8003, 0x8004, 0x8006, 0x8007 and 0x8008 at the same values ([MessageIds.hpp L65](https://github.com/mrmees/openauto-prodigy/blob/3afc5dd8bbacb5ce68e109189b18b14b251fabef/libs/prodigy-oaa-protocol/include/oaa/Channel/MessageIds.hpp)) |
| The modern path is **semantic, not pictorial** | `NavigationNotificationMessage.proto`: "Source-backed 16.1/16.2 native traces keep this payload semantic-only … with no raw turn-image bytes", and `NavigationTurnEventMessage.proto` records that current 16.2 work found no reachable `NEXT_TURN_IMAGE` sender path | Prodigy decodes 0x8006 into a snapshot with no image field at all |

**Two things Prodigy does that this source must not read as protocol.** It answers a
navigation focus request by **echoing the request payload back**, so a source that asked
for `NAV_FOCUS_PROJECTED` always gets it granted, and a source handing the cluster back
with `NAV_FOCUS_NATIVE` gets that echoed too — which would read as a refusal if it were
counted as a response (this source ignores focus responses after the hand-back for exactly
that reason). And it **never sends `InstrumentClusterStart`**, so the lab's default
receiver is a head unit that advertises the service with no cluster behind it;
`--navigation-cluster start` makes it announce one so the ordinary path can be exercised
at all.

**What no source settles.** `DistanceDisplayUnit` has `_P1` variants documented only as a
"one-decimal precision variant", with nothing anywhere about what the accompanying value
counts. Tenths is the only reading that makes the variant worth having and is what this
source sends; `display_text` carries the same distance as a string beside it, so a cluster
that reads the value as whole miles disagrees with the text visibly rather than silently.

## The cluster on the vehicle (P6.2 / G9, 21 September 2026)

The first navigation messages this project has ever put on a wire with a head unit at the
other end. Reports `report-20260921T154316Z-1f4db62b.txt` (unforced) and
`report-20260921T154434Z-ad772b8a.txt` (forced); Ford SYNC 4.0, versionCode 21.

| Behaviour | Measured |
| --- | --- |
| Channel 11 open | accepted in **5 ms**, `status=0` |
| `InstrumentClusterStart` (0x8001) | **never sent**, in either run |
| `NavigationFocusRequest(PROJECTED)` → response | **`NAV_FOCUS_PROJECTED`**, 15 ms after the request |
| Responses per request | **two**, both `PROJECTED`, both within 79 ms |
| `minimum_interval_ms` | advertised 400; the source's own 1000 bound instead |
| Maneuvers accepted | 11 × `0x8006`, 46 × `0x8007` over 45.3 s, `navigation_unhandled=0` |
| On the cluster | maneuver **icon**, **road name**, **distance countdown** (operator) |

**Three corrections to the reading this project was working from.**

*The `NEXT_TURN_IMAGE` advertisement does not require images.* §2 of the P6 plan treated
"Ford asks for turn images and does not say how big" as a contradiction needing a
resolution. It needed none: a stream carrying no image bytes at all renders, with Ford
supplying the iconography. The missing dimensions never mattered because nothing was ever
going to send a picture.

*Waiting for the cluster to announce itself is the wrong default here.* The vendored
`InstrumentClusterMessages.proto` documents 0x8001 as "HU tells phone the cluster display
is available", and the natural reading — wait for it — produces a source that sends nothing
to this truck forever. The unforced run is that source, working exactly as designed and
achieving nothing. **The announcement is not a precondition for the grant**, and on this
head unit it never arrives at all.

*A focus response is not necessarily an answer.* Ford sent two for one request.
`AudioFocusResponse` carries an `unsolicited` flag for exactly this situation and
`NavigationFocusResponse` has no such field, so on this exchange there is no way to tell a
volunteered state from a reply — only to be idempotent about it.

**What the drive did not settle**: whether the icon matched the maneuver sent
(`ManeuverType` is `confidence: unverified` upstream and nobody compared), whether Ford
reads `DISTANCE_UNIT_MILES_P1`'s value as tenths, which road `road_info` names, and what
the cluster does when a route ends — both runs were stopped about a third of the way
through a 161 s route.
