# Ford authentication failure, 17 September 2026 UTC

## Observed result

`report-20260917T040539Z-0acc4e63.txt`, app 0.2.0-lab, Pixel 9:

- Actual accessory acquired, version request `000100010006` (1.6), response 1.5
  under the explicitly selected `legacy-1.5-any-minor` profile.
- TLS completed at 52 ms with TLS 1.2, ECDHE-RSA AES-128-GCM, X25519, no alerts.
- The head unit sent plaintext AUTH_COMPLETE with status -3 at 54 ms.
- Last completed stage: TLS. No discovery was sent. Native teardown completed with
  both threads joined, descriptor closed, and no OS error.
- Operator reports the head-unit message: "USB Connection Failed - Android Auto
  session via USB is stopped. USB device is disconnected."

G0 and G1 have hardware evidence. G2 (successful AUTH_COMPLETE) and G3 remain open.
The screen message does not establish that a physical USB fault caused this attempt
to fail: authentication rejection preceded our descriptor closure. The current
reports do not reveal Ford's exact validation rule. The operator subsequently
confirmed SYNC 4 and that official Android Auto works on their Google-enabled phone.
The exact SYNC software build is not yet known. This strengthens the source-side
compatibility hypothesis; it does not by itself identify the rejected certificate rule.

## Research and interpretation

The strongest hypothesis is source-certificate trust or policy rejection. Local TLS
completion proves receipt and verification of the peer's Finished, not acceptance
of our source identity by the head unit's Android Auto application.

Primary evidence reviewed:

1. [AACS maintainer's certificate explanation](https://github.com/tomasz-grobelny/AACS/issues/3#issuecomment-767168061):
   their production LG/Seat head unit checks the Google automotive root and validity,
   while OpenAuto did not enforce those checks. This is evidence for that receiver,
   not a direct description of this Ford firmware.
2. [AACS source certificate at the pinned revision](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/ssl/android_auto.crt):
   inspection with `openssl x509` shows a Google Automotive Link issuer, a CarService
   subject, RSA-2048, SHA-256 signature, and expiry in August 2022. It is not a
   locally generated, currently valid substitute. No upstream private key was
   imported or bundled in this project.
3. [AACS source-role handler](https://github.com/tomasz-grobelny/AACS/blob/faa1cf208feb5dfe1cb9535be16daeac4f08da0c/AAServer/src/AaCommunicator.cpp):
   TLS server role and source-initiated discovery agree with our engine, but AACS
   proceeds on receipt of AUTH_COMPLETE without checking its status. Consequently,
   an AACS log saying "auth complete" alone does not establish success.
4. [aa-proxy OpenSSL implementation](https://github.com/aa-proxy/aa-proxy-rs/blob/3e2c560c27e1d4be440f43aac9c08fed27e9852a/src/ssl_openssl.rs):
   the head-unit-facing connection uses the mobile-device credential as a TLS
   server. Its externally supplied identities corroborate the role distinction;
   its success does not demonstrate acceptance of self-signed source credentials.

There is no evidence here for a cipher mismatch, USB permission fault, discovery
schema failure, or missing Bluetooth exchange. No unhandled messages were recorded.
Version 1.5 elicited a completed handshake; a firmware-specific version policy is
not completely excluded, but guessing WireConfig values is not an established fix.
The head-unit certificate's local issuer-verification warnings concern the opposite
trust direction and were explicitly accepted by first-use policy.

## Changes in 0.2.1-lab

- **Import source identity** accepts an operator-supplied PEM (maximum 64 KiB):
  source leaf first, optional intermediate certificates in issuer order, and the
  matching unencrypted RSA-2048 private key. The source leaf must use an RSA/SHA-256
  signature. Certificates must be currently valid according to the phone clock.
- Chain adjacency/signatures and key matching are checked before installation. A
  local TLS self-test must pass. Invalid imports preserve the active identity.
  The validated candidate is atomically installed in app-private identity storage;
  head-unit pins stay intact. The identity directory remains excluded from backups.
- TLS sends the supplied intermediate chain. Previously `from_pem` loaded only the
  first certificate and the TLS server had no way to send intermediates.
- Reports describe the actual source subject/issuer (with identifying fields
  redacted), self-signature status, and chain length. Previously the source subject
  was hardcoded to the generated identity's name even when a PEM was loaded.
- The app explains post-TLS authentication rejection beside the result.
- Tests exercise certificate-dependent AUTH_COMPLETE after completed TLS, chain
  delivery to a CA-validating peer, and failed-import preservation through JNI.

An import pass is a local validation result, not an assertion that Ford trusts that
issuer. These changes enable a controlled credential trial; they do not manufacture
a Google CA signature or establish factory compatibility. Generating another
self-signed certificate, renaming its issuer, ignoring -3, or trusting the head unit
again cannot establish the missing source trust.

Validation completed with the existing offline Podman images:

- `tools/check.sh`: formatting, Clippy with warnings denied, 93 native tests,
  release build, and generated protobuf verification passed.
- `tools/android-build.sh`: arm64 build/Clippy, debug and release APKs, Android lint,
  APK verification, 74 JNI checks, 18 report-store checks and 17 trust-store checks passed.
- `tools/emulator-smoke.sh`: the 0.2.1-lab app launched on AOSP API 36, passed the
  native/TLS self-tests, saved Connect/Stop reports and reused its identity after
  relaunch. The script's final global-logcat assertion failed because the emulator's
  `/vendor/bin/hw/android.hardware.radio-service.ranchu` repeatedly aborted with
  `Can't open the host channel`. This is not counted as a passing smoke suite or
  factory evidence. Logs/reports: cache `emulator-smoke/20260917T042105Z/`.
- Debug APK SHA-256:
  `687c48c6868840158175b10ed9a10c0d921ee80cef63660d1d4b887d561eb94e`.

## Next hardware experiment

SYNC 4 and an official-phone success are now confirmed. Record the exact software
build when convenient and preserve the current report as the generated-identity baseline.

If an authorized, currently valid mobile/source identity accepted by this head unit
is available:

1. Install 0.2.1-lab over the existing app; preserve data and pins.
2. With USB unplugged, use **Import source identity** and select its PEM. The
   unencrypted key belongs in private local storage, never a report or source commit.
3. Confirm the source fingerprint, issuer and chain count in the report. A changed
   source fingerprint confirms which credential the trial will use.
4. Select `legacy-1.5-any-minor`, `tls12-aead`, and Stop after `auth`. Leave first-use
   unchecked: this Ford's certificate is already pinned. Tap Connect, then plug in.
5. Require `auth_status=0` and `last_completed_stage=auth`; only then repeat with
   Stop after `discovery` to test G3 and the hold.

If no such identity is available, the credential experiment cannot yet be run.
No public issuance process for independent source apps was established in this
research. The remaining routes depend on obtaining an accepted identity or an
owner-supported head-unit trust configuration. A working-phone handshake capture or
head-unit diagnostic logs can further distinguish certificate policy from
version-specific behavior; a capture of public certificates alone does not provide
the corresponding private key. No firmware modification is part of this change, and
no repeated vehicle runs with the same rejected identity are needed.

## Operator-supplied identity for the hardware trial

The operator later supplied a source identity for the trial: an RSA-2048 leaf with a
SHA-256 signature, issued directly by a root (no intermediates), valid through
**23 December 2026, 22:48:29 UTC**, certificate SHA-256
`2badd925f1b52b257db2d5b52c83ffd4d446f616327e8f8254fd660dc20c381d`. It is a local
import file only: not bundled in the app, a source-code dependency, or a committed
credential.
No private material is included in this document or report output.

Using the compiled 0.2.1-lab Kotlin `IdentityImport` and JNI bridge in the offline
Android build image, importing the file, reloading it, checking its fingerprint,
and completing the native TLS 1.2/AES-128-GCM self-test all pass under `-Xcheck:jni`.
The test runs on a host JVM with the project's native library, not on the phone.

## Credential trial succeeded: G2 on the Ford, 17 September 2026 (report 3812ebff)

The imported source identity was accepted by the Ford SYNC 4.0 head unit.
`report-20260917T145433Z-3812ebff.txt` (app 0.2.1-lab, version code 4, Pixel 9,
session `3812ebff-44e4-40c8-81ad-9c9f6dd77e75`, generation 2) ran
`legacy-1.5-any-minor`, `tls12-aead`, Stop after `discovery`, first use unchecked,
against the already-pinned Ford certificate `16a7552f…`. The active source identity
was the imported leaf `2badd925…` (`identity_created=false`), not the generated
self-signed identity that previously drew status −3.

Progression: version 1.6→1.5; TLS 1.2 `ECDHE-RSA AES-128-GCM` on X25519 with
`tls_peer_decision=pin-match`; **`auth_status=0` (OK), `auth_encrypted=false`**;
then a 581-byte encrypted `ServiceDiscoveryResponse` decoded into 12 channels. That
the encrypted, keyed discovery response decoded at all independently corroborates a
genuine authentication success, not merely a status byte. **This is G2 hardware
evidence on the factory head unit.** The credential hypothesis in this document is
confirmed: a currently valid, root-issued CarService identity is accepted where the
self-signed one is rejected. Preserve report `3812ebff` as the first G2-passing
baseline. Note the imported leaf expires **23 December 2026**, which bounds how long
this exact identity keeps authenticating.

G3 is not yet passed. The report records `discovery_capabilities=insufficient` with
three issues: the main AV channel (channel 1) carried **no stream type**, so the
engine found no usable H.264 main-display video configuration, and the input channel
(touch 1920x882 on display 0) therefore has no backing video. The other eleven
channels parsed cleanly (input, four audio, sensors, Bluetooth, media_info,
navigation, wifi), so decryption, framing, and schema are sound for everything except
the video advertisement. The P2/P3 lab receiver's H.264 video parses correctly, so
this is a Ford-specific difference in how (or when) video is advertised, not a
parser regression on known-good input. The session then ended `KEEPALIVE_TIMEOUT`
during the READY hold: the head unit's one ping was answered (`keepalive_response_max_us=724`),
but our three own pings went unanswered and the session timed out at the configured
three-miss tolerance. That silence is most likely downstream of the insufficient
discovery — with no usable video we open no channels and sit passive — not an
independent fault. Teardown was clean: both I/O threads joined, descriptor closed,
no OS error, no unhandled messages.

Per spec §12 the next step is to read the bytes before changing any schema, so the
question "did Ford omit the video stream type, or does our vendored schema drop a
field it sends?" is answered from evidence rather than guessed.

## Raw discovery capture added in 0.2.2-lab

0.2.2-lab (version code 5) adds an opt-in **Capture raw discovery response for
diagnosis** checkbox, enabled only with Stop after `discovery`. When set, the report
adds one line, `discovery_response_hex=<bytes>`: the raw decrypted
`ServiceDiscoveryResponse` (message id and proto body), captured before decoding so a
response that fails to parse is still recoverable. The cap is 8192 bytes; the Ford
response is ~581, so it is captured whole. The `[events]` armed line and the native
config both record `capture_discovery=…` for provenance.

It is off by default and deliberately so: those raw bytes contain the head-unit name,
serials, vehicle id, and Bluetooth address that every other report field reduces to
lengths (per [G0–G3 setup record](g1-setup-record.md)). **A report captured this way
is sensitive** — the checkbox label says so, and such a report must be sanitized
before it is attached anywhere or committed. The flag threads through the harness
(`--capture-discovery`) and the JNI config key `capture_discovery` as well.

Next hardware run for G3 diagnosis: install 0.2.2-lab over the existing app
(preserve data and pins; the imported identity and the Ford pin remain), keep
`legacy-1.5-any-minor` / `tls12-aead`, Stop after `discovery`, first use unchecked,
and check **Capture raw discovery response**. Then compare channel 1's bytes against
the vendored `ChannelDescriptor`/`AvChannel` schema and the lab receiver's known-good
video advertisement. Either our schema misses a field Ford sends (fix the vendored
`.proto`), or Ford advertises video only after a channel-open (an engine change);
the captured bytes decide which. Handle that report as sensitive.

## Raw discovery bytes: Ford advertises video per configuration (report c1265b54)

`report-20260917T153345Z-c1265b54.txt` (app 0.2.2-lab, version code 5, Pixel 9,
session `c1265b54-7119-4675-b528-fa6ff7a1051b`) repeated the G2-passing setup with
**Capture raw discovery response** checked (`capture_discovery=true` in the armed event).
Handshake as before: version 1.6→1.5, TLS 1.2 AES-128-GCM with `pin-match`,
`auth_status=0`, a 581-byte encrypted discovery response, the same three issues, then
`KEEPALIVE_TIMEOUT`. The report file holds the unredacted bytes and stays local; only
non-identifying structure is recorded here.

**Neither hypothesis from the previous section held: the schema is complete and video
is not deferred.** Decoding `discovery_response_hex` field by field against the vendored
`ChannelDescriptor`/`AVChannel`/`VideoConfig` shows channel 1's `AVChannel` sets fields
4, 6 and 7 only: three `video_configs`, `display_id=0`, `display_type=0` (MAIN). Field 1
(`stream_type`) and field 2 (`audio_type`) are absent. Every `VideoConfig` names H.264
baseline in its own field 10 (`codec=3`):

| Config | Resolution | FPS | Margins w×h | dpi | real_density | Insets (top, bottom, left, right) |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | 1920×1080 | 60 | 586×198 | 240 | 190 | 99, 99, 0, 586 |
| 1 | 1280×720 | 60 | 390×132 | 160 | 127 | 66, 66, 0, 390 |
| 2 | 800×480 | 60 | 74×0 | 131 | 103 | 0, 0, 37, 37 |

All three also carry `decoder_additional_depth=0`, `viewing_distance=950`, and
`pixel_aspect_ratio_e4=10020`. The insets are in `AdditionalVideoConfig.display_insets`
and sum to the margins. Every field in channel 1 is in the vendored schema, so nothing
was dropped. The defect was ours: `Capabilities::av` required `AVChannel.stream_type`
and returned before reading any configuration, although it already honoured a
per-config codec as an override. The lab receiver sets `stream_type`, so it never
exercised this path.

The other channels match the decoded summary: input channel 2 (9 keycodes, touch
1920×882, display 0); microphone `av_input` channel 3 (PCM 16 kHz/16-bit mono); four
audio channels, each audio type offered twice (PCM and AAC-ADTS; 48 kHz stereo for type
3, mono for type 1); and sensor, Bluetooth, media-info, navigation, and wifi. For P4,
note that the touch area (1920×882) matches no advertised video size. The 1080p
config's margins leave a 1334×882 visible area.

### Keepalive: an encrypted ping, then silence

The discovery response also has top-level field 16, which the vendored schema does not
define ("Field 16 not in APK v16.1"). It is 13 bytes: one submessage holding
`{1: 3000, 2: 1000, 3: 200, 4: 5}`. These values look like a ping configuration (3000 ms
timeout, 1000 ms interval), but no pinned reference maps field 16. The upstream
`ConnectionConfiguration` mapping is marked retracted, and its two-field
`PingConfiguration` does not match these four fields. Treat this reading as unconfirmed.

Timing supports it. Our own pings start 2 s after the last head-unit ping (or after the
hold began), and the session fails when the fourth ping comes due, 8 s later. With
`final_ms=9381`, the last reset was about 1381 ms. The hold began at 379 ms, so the
Ford's only ping arrived about **1.0 s after discovery**, which matches the 1000 ms
value. That ping was **encrypted** (`keepalive_hu_encrypted=1`). Our response
and our three own pings went out plaintext, following the pinned receiver baseline
(spec §7). `encrypted_frames_out=1` and `tls_plaintext_bytes_out=84`: only the
discovery request was encrypted. After our plaintext response, the Ford sent no more
pings and answered none of ours. That is consistent with Ford expecting encrypted
keepalive, but it does not prove it. This head unit's pings differ from the 2018
aasdk HU code, which sends pings plaintext.

## Changes in 0.2.3-lab

- **Video from per-config codecs.** An AV channel without `stream_type` counts as
  video when a `VideoConfig` names a video codec. Each config uses its own codec,
  falling back to the channel's. A config with no codec either way is listed as
  `/codec-` and is not usable. A channel with neither is still reported
  (`av channel N has no stream type and no video codec in its configurations`). The
  report shows `codec=per-config` for such channels. Channels that set `stream_type`
  are unchanged.
- **Opt-in encrypted keepalive** (spec §7 allows an intentional, recorded profile for
  an observed encrypted variant). New checkbox **Encrypt keepalive pings**, enabled
  only with Stop after `discovery`. JNI config key: `keepalive=plaintext|encrypted`.
  Harness flag: `--encrypted-keepalive`. Once TLS is established, ping responses and
  our own pings are encrypted. Plaintext stays the default. The report adds
  `keepalive_profile=…`, and the armed event records `encrypted_keepalive=…`.
- Tests: the Ford's first two descriptors, byte for byte, go through the simulator's
  encrypted discovery and must be usable (800×480@60 on channel 1, input on channel 2,
  no issues). With the codecs removed, the channel must be reported as unusable. A
  simulator option models the observation (encrypted pings, plaintext pings ignored).
  Against it, the baseline times out and the encrypted profile keeps four own pings
  answered. The JNI parser accepts `plaintext`/`encrypted` and rejects other values.
- Run through the new code, the complete captured Ford response (all 12 channels) gives
  `discovery_capabilities=usable`, `discovery_usable_video=channel=1 display=0 config=2
  800x480@60`, `discovery_usable_input=channel=2 display=0 touch=1920x882 keycodes=9`,
  and `discovery_issue_count=0`. This used a throwaway local test that was deleted. The
  captured bytes are not committed; only the two non-identifying descriptors are.

`tools/check.sh` passed: formatting, Clippy with warnings denied, 96 native tests, the
release build, and generated protobuf verification. `tools/android-build.sh` passed:
arm64 build and Clippy, debug and release APKs, Android lint, APK verification, 75 JNI
checks (including the new `keepalive=encrypted` config text), 18 report-store checks,
and 17 trust-store checks. The debug APK (`versionName=0.2.3-lab`, `versionCode=6`) has
SHA-256 `77b7830f34f36eac8a330bb9ee502ecdec5ca8d241201ab39223780a6f683e8c`. No emulator
smoke run or hardware run has been done with this build.

## Next hardware run (0.2.3-lab)

Install over 0.2.2-lab, keeping app data: the imported identity and Ford pin stay.
Select `legacy-1.5-any-minor`, `tls12-aead`, and Stop after `discovery`, with first use
unchecked. Raw capture is no longer needed; leave it unchecked so the report is not
sensitive.

1. **Baseline** (Encrypt keepalive unchecked): expect `discovery_capabilities=usable`,
   which is **G3 evidence** under the setup record, even if the hold still ends
   `KEEPALIVE_TIMEOUT`.
2. **Encrypted keepalive** (checked): report `keepalive_profile=encrypted`. If
   `keepalive_own_responses` > 0 or `keepalive_hu_requests` > 1, and the hold ends
   `HOLD_COMPLETE`, the encrypted-keepalive reading is confirmed. If it still times
   out, keepalive encryption is excluded as the sole cause. The field-16 values and
   our 2 s own-ping interval are the next thing to examine.

## G3 passed on the Ford, with encrypted keepalive confirmed (report c2407cff)

`report-20260917T154914Z-c2407cff.txt` (app 0.2.3-lab, version code 6, Pixel 9,
session `c2407cff-5104-428c-844d-ae6b79683b4a`, generation 5) ran
`legacy-1.5-any-minor`, `tls12-aead`, Stop after `discovery`, first use unchecked, and
the pinned Ford certificate `16a7552f…`. The imported source identity `2badd925…` was in
use. The armed event records `capture_discovery=true encrypted_keepalive=true`, so this
report also contains the raw, unredacted discovery bytes and is sensitive.

Progression: version 1.6→1.5; TLS 1.2 AES-128-GCM with `pin-match` at 80 ms;
`auth_status=0` at 83 ms; an encrypted 581-byte discovery response at 86 ms. The report
then shows **`discovery_capabilities=usable`** with:

- `discovery_usable_video=channel=1 display=0 config=2 800x480@60`
- `discovery_usable_input=channel=2 display=0 touch=1920x882 keycodes=9`
- `discovery_issue_count=0`
- `last_completed_stage=discovery`

**This is G3 evidence on the factory head unit** under the setup record: our encrypted
request produced a decrypted, validated response with usable H.264 video and input. The
handshake POC exit gate is met. It does not show projection: no channel was opened and
no video was sent (P4/G4–G5).

The hold then ran its full 30 s and ended **`HOLD_COMPLETE`**. Our shutdown request was
written at 30090 ms and the Ford's ByeByeResponse arrived 2 ms later. Keepalive details:

- The Ford sent **29 pings, all encrypted**, about one per second (29 in about 30 s).
  That fits the 1000 ms value in the unmapped discovery field 16.
- All 29 were answered encrypted; the slowest response took 3.2 ms (target below 100 ms).
- We sent no pings of our own: the Ford's once-per-second pings kept resetting our 2 s timer.
- No unhandled messages. 31 encrypted frames went each way: the discovery request, 29 ping
  responses, and the shutdown request out; the discovery response, 29 pings, and the
  ByeByeResponse in.
- Teardown was clean: both I/O threads joined, descriptor closed, no OS error.

**The encrypted-keepalive reading is confirmed as the cause of the earlier timeout.**
This run's discovery response is byte-identical to report c1265b54's, and the
ClientHello lists match. Everything we sent was the same except ping encryption. With
plaintext replies (c1265b54), the Ford sent one ping and went silent. With encrypted
replies (c2407cff), it pinged steadily and completed an orderly shutdown. The 0.2.3-lab
discovery fix changes only how we evaluate the response locally, not what goes on the
wire. For this head unit, pings after TLS must be encrypted. This departs from the
pinned Prodigy receiver baseline (spec §7), which sends pings plaintext. The meaning of
field 16's other values (3000, 200, 5) is still unconfirmed.

Only the encrypted-keepalive run was reported, not the plaintext baseline. G3 does not
depend on it, since discovery evaluation is the same under both profiles.

### Keepalive default: mirror the head unit (0.2.4-lab)

The operator chose to make keepalive follow the head unit. Since 0.2.4-lab (version code 7):

- **Profile setting:** a **Keepalive** dropdown replaces the 0.2.3-lab checkbox and applies to any
  stop beyond `version`. JNI config key: `keepalive=mirror|plaintext|encrypted`, with
  `mirror` as the default. The app always sends the key. Harness flag:
  `--keepalive mirror|plaintext|encrypted`.
- **`mirror`:** each ping response uses the encryption of the ping it answers. Our own pings
  use that of the head unit's latest ping, and stay plaintext until one arrives. Nothing is
  encrypted before TLS is established. On the Ford, the first encrypted ping arrives about
  1 s after discovery, before our first own ping is due at 2 s.
- **`plaintext` / `encrypted`:** force one form, as recorded overrides. `encrypted` is the
  fallback for a head unit that never pings and ignores plaintext.
- **Report fields:** `keepalive_profile=…`, plus the new counters
  `keepalive_responses_encrypted` and `keepalive_own_encrypted`. The armed event records
  `keepalive=…`.
- **Spec:** §3.1 and §7.6 now describe this rule.

Validation:

- **Simulator test:**
  - A simulated head unit that pings only encrypted gets encrypted responses and encrypted
    own pings under `mirror`, all answered.
  - The `plaintext` override reproduces the c1265b54 timeout.
  - With no head-unit ping, `mirror` pings plaintext (and times out against that head
    unit), while `encrypted` is answered.
  - The existing plaintext-ping test is unchanged.
- **Build suites:**
  - `tools/check.sh`: 96 native tests plus formatting, Clippy, release build and protobuf
    check.
  - `tools/android-build.sh`: lint, APK verification, 75 JNI checks (config text carries
    `keepalive=`), 18 report-store and 17 trust-store checks.
- **Independent receiver:** `tools/lab-receiver.sh demo --io threads` against the pinned
  Prodigy receiver ended `HOLD_COMPLETE` with usable discovery under `mirror`. That receiver
  sent 3 plaintext pings; all 3 responses were plaintext, and all 4 own pings were plaintext
  and answered. The default therefore changes nothing for a plaintext-pinging receiver.
- **Debug APK** (`0.2.4-lab`, version code 7): SHA-256
  `9d26528088505a106d2d55df1bdd97584cc47fb650e3e2912451d61b181dbb30`.
- **Not done:** no emulator smoke or hardware run with 0.2.4-lab yet. On the Ford, expect the
  default settings to reproduce c2407cff: `keepalive_profile=mirror`, and
  `keepalive_responses_encrypted` equal to `keepalive_hu_requests`.
