# The Android Auto session

HeadLink speaks the Android Auto protocol in the phone's role: the **source**. The head unit
is the receiver. This page describes the session as the code runs it today, from the first
version byte to shutdown, and the head-unit behaviour that shaped it. Almost all of that
behaviour was learned on one 2024 Ford SYNC 4.0 ("the truck"), so a quirk listed here is a
fact about that unit, not a rule about Android Auto.

How a session gets its byte stream (USB accessory or wireless TCP) is in
[connecting.md](connecting.md). The audio channels are in
[audio-and-speech.md](audio-and-speech.md), and the instrument cluster and `navigation`
channel are in [navigation.md](navigation.md). The upstream sources the engine was audited
against, with pinned commits, are in [references.md](references.md).

## Where it lives

| Piece | Path |
| --- | --- |
| Framing, fragmentation, reassembly | `core/aap-wire/src/lib.rs` |
| Session engine: stages, timers, keepalive, shutdown | `core/aap-session/src/lib.rs` (`Session`, `Config`) |
| Version profiles | `core/aap-session/src/version.rs` |
| Discovery request and capability validation | `core/aap-session/src/discovery.rs` |
| Channel opens, AV setup, focus, flow control, sensors, input dispatch | `core/aap-session/src/projection.rs` |
| Encoder hand-off, Annex B, congestion queue | `core/aap-session/src/media.rs` (`MediaLink`) |
| Touch mapping | `core/aap-session/src/input.rs` (`TouchSpace`, `TouchMap`) |
| Blocking I/O threads for the accessory descriptor | `core/aap-session/src/accessory.rs` |
| TLS 1.2 server over memory buffers, peer policy | `core/aap-tls/src/server.rs` |
| Source identity | `core/aap-tls/src/identity.rs` |
| Vendored schemas and generated types | `protocol/upstream/open-android-auto/`, `core/aap-proto/` (`tools/proto-gen.sh`) |
| Config keys from the app | `core/aap-jni/src/manager.rs` |
| What the app asks for | `SessionSettings` in `NativeBridge.kt`, `SavedSettings.attempt` in `AppSettings.kt` |
| Encoder, renderer, focus rebuilds | `VideoEncoder.kt`, `ProjectionPipeline.kt`, `SceneRenderer.kt` |
| Pins and identity import | `TrustStore.kt`, `IdentityImport.kt` |

`Session` does no I/O. A transport feeds it received bytes and timer wakeups and writes
`pending_output` in order. Every attempt uses a fresh `Session`: no TLS state, channel
state, or media link is reused across connections.

## Schema names are not evidence

The vendored open-android-auto schemas are reverse-engineered. Their **structure** has been
right every time. Their **field names** have been wrong at least four times on this project:

- `AVChannelSetupRequest` field 1 is the codec type, not a configuration index (see
  [AV setup](#av-setup)).
- `AudioFocusResponse` field 2 is named `granted` but means `unsolicited`. Gating audio on it
  threw away a real grant.
- `GpsSatellite`'s `satellite_count` and `satellites_used` only make sense swapped (see
  [Sensors](#sensors)).
- `NavigationImageOptions` has height and width in opposite orders in two files at the same
  pin, so its dimensions are reported by field name, never as `WxH`.

The rule: when a field's meaning matters, act on observed behaviour (a vehicle report, a dex
read of Google's client, or agreement between independent implementations), and until then
record the value without branching on it. The same goes for the test head unit
`tools/hu-sim`: it was written from the same reading of the schema as the source, so it
cannot catch a misreading it shares. Spec.md §8.1 states this rule. The two times Ford
withheld the picture while acknowledging every frame (the 0x800A reply and the missing stop
indication) were both found by reading what Android Auto 17.6 does, at the bytecode level.

## Stages and timers

The stages are Version → Tls → Auth → Discovery, then either Ready (a bounded discovery-only
hold) or Setup → Projecting, then Shutdown. `Config::stop_after` (`version`, `auth`,
`discovery`, `projection`) ends a diagnostic attempt early. The app's default is
`projection`.

| Timer (spec.md §7.6) | Default |
| --- | --- |
| Version request, from descriptor open | 5 s |
| First TLS message, from the version response being fully written | 5 s |
| TLS complete, from the first handshake message | 10 s |
| AUTH_COMPLETE, from TLS completion | 10 s |
| Discovery response, from the request being written | 10 s |
| Establishment cap, until capabilities are ready | 30 s |
| Fragment assembly expiry | 10 s |
| Each channel open, AV setup, binding, sensor request | 10 s |
| Projected video focus | 30 s |
| First encoded frame after the start indication | 10 s |
| Full ack window with no ack (`FLOW_CONTROL_TIMEOUT`) | 5 s |

Every ending has a category (`Category` in `lib.rs`, for example `AUTH_FAILED`,
`SETUP_TIMEOUT`, `PEER_SHUTDOWN`). The report names it along with the last completed stage.
Messages with no role in the session are counted and the first 16 described
(`unhandled_*`). Known control IDs that arrive out of place end the session with
`UNEXPECTED_MESSAGE`.

## Framing

`core/aap-wire` implements the frame: channel byte, flags byte, a big-endian u16 payload
length, and for a FIRST fragment a u32 total. Flag bits: 0–1 frame kind (MIDDLE 0, FIRST 1,
LAST 2, BULK 3), 0x04 the channel-control bit, 0x08 encrypted. Outbound messages are cut into
16 KiB fragments. The total in a FIRST header is the plaintext length, and each fragment is
encrypted on its own, so every encrypted frame carries whole TLS records. Inbound, the
session tracks record boundaries itself: a record split across frames is held and
reassembled before decryption.

The strict-profile limits are reserved flag bits rejected, no empty fragments, duplicate
starts fatal, 1 MiB per control message, 4 MiB per AV message, 8 MiB in assembly, and 10 s
assembly expiry. Any framing error ends the session with `FRAME_INVALID`.

Media is pumped one whole message at a time, so a fragmenting video keyframe can never have
an audio buffer interleaved into it. Audio buffers are capped to fit one fragment
(`AUDIO_UNIT_LIMIT` in `media.rs`).

**A replayed TLS record silences the peer.** The accessory transport once handed the writer
thread a chunk it had already written when a write finished mid-iteration. The receiver saw a
TLS record twice, failed its MAC check on the stale sequence number, and stopped reading. On
the truck that looked exactly like Ford acknowledging four frames and then going silent.
`accessory.rs` now hands out a new chunk only after the finished write is credited (test
`a_write_finishing_during_an_iteration_is_not_sent_twice`).

Verified: truck, every session.

## Version negotiation

The head unit sends a version request on channel 0 and the source answers MATCH. The
profiles are in `version.rs`:

| Profile | Answer to a 1.0–1.5 request | Answer to 1.6 or later |
| --- | --- | --- |
| `match-1.7` (default) | 1.5 | **1.7** |
| `legacy-1.5` | 1.5 | refused (`VERSION_UNSUPPORTED`) |
| `legacy-1.5-any-minor` | 1.5 | 1.5 |
| `match-1.6` | 1.5 | 1.6 |

SYNC 4.0 requests 1.6. Android Auto 17.6 answers it with 1.7 (seen in its logcat on the
truck), and `match-1.7` copies that. No profile appends the WireConfig extension that a real
phone adds at 1.6 and later, because its four parameters are unknown. Ford accepts the
answer without it. A request with extension bytes, or with a major version other than 1,
is refused by every profile, and nothing is sent: MISMATCH's accompanying fields are not
established.

The earlier `legacy-1.5-any-minor` answer also worked on the truck through G4. `match-1.6`
was an experiment to see whether a 1.6 answer changes what Ford advertises. It does not:
Ford's split or full-width layout follows its own expand control, not the version (see
[UI configuration](#mid-stream-ui-configuration-0x8009--0x800a)).

Verified: truck (`match-1.7` daily; the others recorded on earlier drives).

## TLS

The phone is the TLS **server** and requires a client certificate from the head unit.
`aap-tls` drives OpenSSL 3.5.8 over memory buffers. HeadLink builds OpenSSL itself
(`tools/openssl-build.sh`, see [architecture.md](architecture.md)). Handshake records travel
in plaintext 0x0003 messages on channel 0. After the handshake, every other message is
encrypted, except the keepalive messages described below.

- TLS 1.2 only, no tickets, no session cache, no renegotiation, no compression.
- `tls12-aead` (default): ECDHE-RSA with AES-GCM or ChaCha20-Poly1305, groups X25519, P-256
  and P-384, security level 2.
- `tls12-cbc-compat`: adds CBC and static-RSA/DHE suites at security level 1. It exists only
  for a head unit whose recorded ClientHello offers none of the baseline suites, and it is
  reported as a relaxation.
- The ClientHello is decoded independently of OpenSSL (`client_hello.rs`) and reported:
  offered versions, suites, groups and extensions, never the random or SNI.
- Alerts are captured in both directions. A failure is classified as `TLS_CERT_POLICY`,
  `TLS_NO_COMMON_SUITE`, `TLS_ALERT_PEER`, `TLS_ALERT_LOCAL` or `TLS_ERROR`. A pin mismatch
  makes OpenSSL send `handshake_failure` (40), not `bad_certificate`.

SYNC 4.0 negotiates `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` on X25519. Its certificate's
subject has O=Ford.

Verified: truck.

## Source identity

The source identity is the certificate HeadLink presents as the TLS server.
`Identity::load_or_create` (called through `nativeEnsureIdentity`) runs at app start, never
while a head unit waits. It either loads `files/identity/source-identity.pem` or generates
one. The file holds the certificate, any chain, and the PKCS#8 key. It is owner-only,
written atomically, excluded from backup, and never exported. If an identity file exists but
cannot be read, that is an error: the app does not quietly replace an identity a head unit
may already accept. A TLS self-test (`selftest.rs`) runs against every loaded identity.

**Generated.** RSA-2048, SHA-256, self-signed, non-CA, `CN=HeadLink source, O=HeadLink
project`, valid 2024-01-01 to 2049-01-01. The fixed start date is so a head unit with a
lagging clock never sees a not-yet-valid certificate. **Replace source identity** generates
a new one and keeps the pins.

**Imported.** **Import source identity** on the Diagnostics tab takes one PEM of up to
64 KiB: the leaf, optional intermediates in issuer order, and the matching unencrypted
RSA-2048 key. `Identity::from_pem` checks that every certificate is currently valid, that
the chain links and verifies, and that the key matches. `IdentityImport.install` then stages
the file, runs the self-test, and only then renames it into place. A failed import leaves
the active identity alone. The chain is sent during the handshake. How to obtain and load
the file is in [data.md](data.md#source-identity-pem).

**Why SYNC 4.0 needs an imported one.** With the generated identity, TLS completes, and
then Ford sends a plaintext AUTH_COMPLETE with **status −3** and ends the session. Its
screen says "USB Connection Failed". Completing TLS only proves the phone verified Ford's
Finished message. It says nothing about whether Ford's Android Auto layer trusts the phone's
certificate. A currently valid, root-issued CarService identity is accepted on the same unit
with nothing else changed, and the encrypted discovery response that follows decrypts, which
proves the acceptance independently. Another self-signed certificate, a renamed issuer, or
ignoring −3 cannot fix this. The app explains a −3 as "The head unit didn't accept this
phone's identity" (`StatusText.kt`).

**Expiry is a hard stop.** `from_pem` checks validity on every load, not just at import. An
imported certificate that has expired fails to load, and no session can start until a valid
identity is imported or a new one is generated. The identity in use on the truck has its
own expiry date. Check `source_identity_*` in a report.

Verified: truck (−3 with the generated identity; accepted with an imported one).

## Head-unit trust

There is no CA store for head units. The head unit's leaf certificate is judged by its
SHA-256 fingerprint over DER (`PeerPolicy` in `server.rs`). Chain errors are recorded
(`tls_peer_verify_errors`) but do not decide anything.

- **Pinned** (normal). The leaf must match one of the pins in
  `files/identity/head-unit-pins.txt` (`TrustStore`, newest 8 kept). A mismatch is
  `TLS_CERT_POLICY` with `pin-mismatch`.
- **First use, armed for one attempt.** Any leaf is accepted, and the app pins it **only if
  the handshake completed** (`TrustStore.acceptedFirstUse` reads
  `tls_peer_decision=first-use-accepted <fp>`). Completion proves the head unit holds the
  certificate's key.

Users reach first use through **Trust and connect**: the Home status action when no head
unit is trusted yet, or Setup's no-pins item. It asks for confirmation, ticks Diagnostics'
"Trust the next head unit" box for exactly one attempt, and connects. The box is never saved,
because a saved first-use trust would pin whatever the phone was next plugged into.
Automatic starts only connect to an already-pinned head unit (see
[connecting.md](connecting.md)). Lab-socket attempts in debug builds never pin.

Verified: truck (first-use pin, then pin-match on every later session).

## Authentication

AUTH_COMPLETE is accepted only after local TLS completion. It is decoded through the schema,
so a missing status is `AUTH_FAILED`, never success. The status must be 0. SYNC 4.0 sends it
plaintext. An encrypted one is also accepted and reported as `auth_encrypted=true`.

## Service discovery

After authentication the source sends exactly one encrypted ServiceDiscoveryRequest. It
carries `device_name "HeadLink"`, `device_brand "HeadLink project"`, and a fresh session UUID,
with no phone model, account or icons. The response must be encrypted and pass the
required-field schema check. `Capabilities::from_response` then validates it:

- **Rejected outright** (`DISCOVERY_INVALID`): more than 64 channels, a channel ID outside
  1–255, a duplicate ID, or a descriptor with more than one service. The schema check
  covers every nested message, so one descriptor missing a schema-`required` field (for
  example `NavigationChannel.minimum_interval_ms` or `type`) fails the whole response, not
  just that service. Nothing has tripped this on SYNC 4.0, but a head unit that omits a
  field the vendored schema marks `required` would lose discovery entirely.
- **Issues** (make only that service unusable): video with no configurations, an unknown
  resolution or frame rate, a touch size outside 1–16384, insets that disagree with
  margins, input for a display that has no usable video.
- **Usable video**: an AV channel on the main display whose codec is H.264 baseline (3).
  The codec is taken from each `VideoConfig.codec`, falling back to `AVChannel.stream_type`.
  **SYNC 4.0 omits `stream_type`** on its video channel and names the codec in each
  configuration. Requiring `stream_type` made all of Ford's video unusable.
- **Usable input**: an input channel for that display with a valid touch screen or keycodes.
- **Choosing a configuration**: `VideoPreference`, default `largest` (most content pixels,
  then frame pixels, then frame rate). `smallest` and pinned sizes (`1920x1080`, `1280x720`,
  `800x480`) are on the Diagnostics tab. A pinned size the head unit does not advertise
  leaves no usable video rather than falling back.
- **Content rectangle**: the configuration's margins, placed by the
  `AdditionalVideoConfig.display_insets` when the insets add up to the margins, and centred
  otherwise.
- Channel IDs are never assumed. The head-unit name, serials, vehicle ID and Bluetooth
  address are reported only as lengths. **Capture raw discovery response** (one attempt,
  discovery-only stops) adds the unredacted bytes, so a report captured that way is
  sensitive.

What SYNC 4.0 advertises: 12 channels. Video on channel 1 offers three configurations, all
H.264 at 60 fps:

| Config | Frame | Margins | Insets (t, b, l, r) | Content |
| --- | --- | --- | --- | --- |
| 0 | 1920×1080 | 586×198 | 99, 99, 0, 586 | 1334×882 at 0,99 |
| 1 | 1280×720 | 390×132 | 66, 66, 0, 390 | 890×588 at 0,66 |
| 2 | 800×480 | 74×0 | 0, 0, 37, 37 | 726×480 at 37,0 |

All three describe one window (aspect 1.5125). The margins reserve Ford's own UI: the right
panel and the status and tab bars. Input is on channel 2 (touch advertised as 1920×882, plus
9 keycodes). There is a microphone channel, four audio channels, sensors, Bluetooth,
`media_info`, `navigation` (11) and Wi-Fi. When Ford's own expand control has been used, it
advertises a full-width 1920×882 layout at 0,99 instead. The response also has a top-level
field 16 that the schema does not define, `{1: 3000, 2: 1000, 3: 200, 4: 5}`. It looks like
a ping configuration, and the 1000 matches Ford's ping interval, but that reading is
unconfirmed.

Verified: truck.

## Channel opens and setup order

With `stop_after=projection`, the setup runs one step at a time. Each step has its own
deadline, so a report names the step a head unit did not answer:

1. Open video, then send the AV setup.
2. Open input, then the input binding with the advertised keycodes.
3. Optional, each recorded and skipped if refused: the audio channel and its setup,
   `media_info`, `navigation`, then the sensor channel and one request per type.
4. Optional **Open every channel** (Diagnostics, off): open every other advertised channel,
   as Android Auto does. It turned out to matter for nothing.
5. Video focus, then the start indication.

A ChannelOpenRequest goes on the **target** channel with the control bit set (0x0F flags,
encrypted). Only a response on that channel, with the control bit set, while an open is
pending is accepted. Anything else is counted (`projection_open_responses_unexpected`) and
the step times out. A refused or closed video or input channel ends the session with
`CHANNEL_REJECTED`. Every other channel only costs its own integration. Head-unit-initiated
opens are refused with `INVALID_CHANNEL`.

Verified: truck.

## AV setup

The setup request's field 1 is the **codec type**: H.264 baseline, value 3. The vendored
schema calls it `media_codec_type`. aasdk calls the same field `config_index`, and the
response does list accepted configuration *indices*, so the index reading was plausible.
It was tested on the truck with `setup_field=config-index` (still on Diagnostics under
**AV setup field 1**): Ford **dropped the request without answering**, showed a complaint on its
screen, and the session ended `SETUP_TIMEOUT`. So the vendored name is right, and Ford does
not learn which configuration to decode from field 1. It learns it from the start
indication's `config` field.

The response gives a status, `max_unacked` (the ack window; a missing one means 1, reported
as defaulted), and the accepted configurations. The source re-chooses among the accepted
ones by the same preference. SYNC 4.0's video window is **4** (its audio window is 40).

Verified: truck.

## Video focus

Focus is requested once (`VideoFocusRequest` PROJECTED, USER_SELECTION) and never again.
`request_focus` defaults to on. Diagnostics' **Wait for video focus** waits for the
unsolicited grant instead, as Android Auto does. SYNC 4.0 grants PROJECTED unsolicited
either way. Only PROJECTED and PROJECTED_NO_INPUT_FOCUS count as having the screen. NATIVE,
NATIVE_TRANSIENT, NONE, or a missing mode count as lost. No grant within 30 s is
`VIDEO_FOCUS_DENIED`. `video_focus_sequence` in the report logs every indication with what
the source did about it.

**Losing and regaining the screen** (Ford's Audio page, Settings, the reversing and
surround cameras). `stop_video_stream` and `restart_video_stream` in `projection.rs`:

- On loss: send `AV_CHANNEL_STOP_INDICATION` (0x8002), clear the queue, zero the
  outstanding count, and increment the video session id. The old id is kept as
  `prev_session_id`, so acks still in flight count as `video_acks_previous_session`.
- On the next PROJECTED: send `AV_CHANNEL_START_INDICATION` (0x8001) with the **new**
  session id, then configuration and a fresh IDR.
- The channel, its ack window and the flow controller are untouched. This stops a stream,
  not a channel.
- On the phone, `ProjectionPipeline.setFocused` rebuilds the encoder and EGL surface on
  every regain, as Android Auto's `doHandleVideoFocusGained` does. It also stops drawing
  until the rebuild finishes and ignores a rebuild whose regain was already taken back.
  Presentation timestamps keep rising across the rebuild. **Resume the encoder in place** on
  Diagnostics turns the rebuild off.
- Input that arrives while focus is lost is dropped and counted.

Why: a source that only *pauses* leaves Ford believing the projection is still in the
foreground. Its "Android Auto" tile then does nothing, and only a replug brings the picture
back, while every frame goes on being acknowledged. Android Auto 17.6's dex shows exactly
this stop/start pair. With it, Ford re-grants focus about 1.2–1.9 s after a native takeover
without a tap. About 0.6 s later it sends a fresh UI configuration (below) and recomposes,
the same sequence it runs for Google's client. Channel coverage, audio, Bluetooth pairing,
sensors, re-requesting focus, and an encoder rebuild alone were each ruled out first. Note
that a log line saying a flow controller was "initialised once" proves nothing about stream
restarts.

Older code comments call this P4 and the 0.4.5 fix.

Verified: truck (tile and cameras). A regain immediately followed by a loss: lab only.

## Mid-stream UI configuration (0x8009 / 0x800A)

SYNC 4.0 sends `UPDATE_UI_CONFIG_REQUEST` (0x8009) on the video channel a second or two into
projection, and again after each focus regain. It does this for every configuration that has
an expanded variant, so never for 800×480. The body is `{1: AdditionalVideoConfig}` with
only `display_insets` set. At 1080p that is 99/99/0/0: the full-width 0,99+1920×882
rectangle. This is Ford's expand mechanism. The protocol has no message for a phone to ask
for a different geometry.

`Session::ui_config_request` takes the insets literally (not through the margin rule), moves
the content rectangle and the touch map, and tells the app (`VideoGeometry`). The frame size
does not change, so the encoder keeps running. The app relayouts the scene, retargets the
bitrate to the new content area, and asks for an IDR.

**The reply is 0x800A, carrying the received configuration rewrapped byte for byte** as
field 1. The vendored ID enum skips 0x800A. The ID and the body shape were recovered from
Android Auto 17.6's dex (`Ljeu;->I`, class `Lxpx`). Both wrong answers were tried on the
truck, and both fail silently:

- **Answering with 0x8012** (the enum's "response", which actually belongs to the
  phone-initiated pair): Ford replies with an empty message of ID 0x00ff and withholds the
  picture.
- **An empty 0x800A body**: accepted at the framing level, but Ford shows a picture for
  about a second, then withdraws the surface and keeps acknowledging every frame.

Before this was fixed, every configuration above 800×480 "worked" by every wire counter and
displayed nothing. **Acknowledgements are not evidence that a head unit is showing what you
send.**

The comment on `ids::av::UPDATE_UI_CONFIG_REPLY` in `core/aap-proto/src/lib.rs` still says
an empty body is sent. The code sends the echoed configuration.

Verified: truck (1920×1080@60 displays; touch lands at 1080p).

## Video encoding

`VideoEncoder.kt` is MediaCodec `video/avc` fed from an input Surface. `SceneRenderer.kt`
draws into that Surface with EGL 1.4 (`EGL_RECORDABLE_ANDROID`) and GLES 2.

- Baseline profile, no B-frames, `KEY_PRIORITY` 0, the negotiated frame size.
- **Frame rate**: the negotiated one (60 on SYNC 4.0). Diagnostics' encoder frame rate
  (30 or 60) overrides it. The configuration index says nothing about frame rate, so
  encoding slower is legal and lowers the H.264 level and bitrate.
- **Bitrate**: about 2 Mbps at 800×480, scaled by the **content rectangle's** pixels and
  clamped to 0.5–12 Mbps (`VideoEncoder.bitRateFor`). Android Auto sizes its bitrate from
  the content too, because the margins are never seen. Retargeted live on a UI-config
  change.
- **Keyframe interval**: 1 s by default. Diagnostics offers 60 s, which is what Android
  Auto configures.
- The platform picks the encoder (`findEncoderForFormat`). On the Pixel 9 that is
  `c2.exynos.h264.encoder`. **Software video encoder** forces a software-only one, as a
  diagnostic comparison with no fallback.
- The scene is drawn in the content rectangle (`scene_area=content`, default). `frame`
  draws across the whole frame. Which rectangle Ford actually shows was never settled, and
  both look right at 800×480.

`media.rs` normalizes every buffer to Annex B (converting length-prefixed output), checks
the NAL types (a configuration buffer must hold SPS and PPS and no slice, a frame must hold
a slice, and IDR and non-IDR slices must not mix), and parses the SPS for the report.
Configuration goes out as untimestamped 0x0001 messages, and frames as 0x0000 with a u64
microsecond timestamp. **Save the encoded video** (one attempt) writes the Annex B stream to
app-private storage for decoding off the phone. That is the only way to check the encoder's
output, because the head unit acknowledges frames it cannot show.

Verified: truck (hardware encoder, 1920×1080@60 daily). A captured truck stream decoded with
zero errors.

## Flow control and congestion

- At most `max_unacked` **frames** are outstanding. **Codec configuration takes no credit.**
  Android Auto's sender acquires a credit only for timestamped frames. Counting SPS/PPS
  leaked a credit each time and could leave a batching receiver short of its ack threshold
  forever.
- An ack's count is `receive_timestamps` when present and `ack_count` otherwise. Session IDs
  are compared modulo 256.
- A full window with no ack for 5 s ends the session with `FLOW_CONTROL_TIMEOUT`. Raising
  the window or ignoring acks is not a fix.
- At most 4 frames wait in the queue, and media is taken from it only while pending output
  is below 256 KiB, so control and input traffic never wait behind video.

**Congestion** (spec.md §8.2). A frame that finds the queue full is dropped, together with
every later frame until the next IDR, which goes out preceded by the configuration. Queued
frames are never dropped individually, so the head unit never decodes a hole. Two rules came
from a drive where the truck's picture smeared and froze for long stretches (33% of frames
dropped, 5,200 episodes in an hour, two keyframes a second):

- **No keyframe on demand at the drop.** The IDR that ends an episode is taken only once the
  queue has drained, and it is asked for only then and at least 1 s after the last keyframe
  sent or asked for (`keyframe_spacing`). The encoder's own 1 s interval usually supplies
  it. Asking at the drop handed a head unit that had just fallen behind the most expensive
  frame there is, which kept it behind. In the lab this halved the spiral.
- **Stream restart.** Five episodes within 10 s trigger the same stop/start a screen switch
  does, at most once a minute (`watch_congestion`, `restart_congested_stream`), because a
  screen switch was the only thing that cleared Ford's picture. It is on by default, on
  wired and wireless. Diagnostics' "Restart video when the car falls behind" and
  `congestion_restart=false` turn it off. SYNC accepts a phone-initiated stop/start and
  returns to the projection, but the driver sees it as a brief blink. On wireless, congestion
  bunches up in the first minute and the restart often fires once about 6 s in. James chose
  to keep it on there too.

`video_timeline` in the report gives one row per minute: frames, keyframes, episodes,
drops, keyframe requests, restarts. Why Ford sometimes falls behind, and why it shows smear
rather than stutter, is not known. Everything the phone sent in that drive was a valid
stream.

Verified: congestion policy in the lab (`tools/emulator-lab.sh congestion`). The restart has
been seen on the truck. Whether it ends a real spiral is unproven.

## Input

Input events arrive on the input channel (0x8001; binding 0x8002/0x8003) and are mapped
into frame pixels by `TouchMap` (`input.rs`). The action is one of PRESS, RELEASE, DRAG,
POINTER_DOWN, POINTER_UP, or CANCEL (3, accepted though the pinned enum omits it). Up to 10
pointers are accepted, with pointer IDs kept. Unknown actions, duplicate pointer IDs,
coordinates outside the space, and events while unfocused are counted and dropped, never
fatal. Nothing is injected into Android: the app's own scene handles the events.

**Touch space (spec.md §8.3).** SYNC 4.0 advertises a 1920×882 touch screen whatever
configuration is selected. That is its native 1080p layout, not a scale factor. Two
measurements on the truck settled what it actually sends:

- **Scale:** coordinates arrive in the selected configuration's **frame** pixels, one unit
  per pixel. Stretching 1920×882 onto the picture put all 21 taps of the first drive on one
  button.
- **Origin:** the grid starts at the **content rectangle's** origin. Pressing crosses drawn
  at four known pixels read back a uniform 39 ± 6 px to the left at unit scale: the 37 px
  inset, with no sign flip between columns.

So `TouchSpace::Content` (default) is frame-sized and content-origin: `x_video = x +
content.x`, `y_video = y + content.y`, clamped to the frame (Ford sends x up to 791 on an
800-wide frame). For a head unit with no margins this is the identity. `Frame` and
`Advertised` remain for other head units (harness and lab flags only). A +8 px vertical
residual at 800×480 is accepted as finger parallax and not chased. The lab cannot catch an
origin error this size, because its taps land at button centres. `input_raw_range` cannot
settle an origin either.

Multi-touch: pinch-zoom on the projected map works on SYNC 4.0, so it sends more than one
pointer.
Steering-wheel and head-unit keys arrive as button events. Media keys become transport
actions on the press only, so a press and release do not skip two tracks.

Verified: truck (taps at 800×480 and 1080p, pinch, keys).

## Sensors

The sensor channel opens during setup (before the start indication). The source requests
every advertised type (`sensors=all`, the app default; `restriction` and `none` are
Diagnostics options) with refresh interval 0. A refused type, an error, or a decode failure
is recorded and skipped. Sensor failures are never fatal. Because the sensor channel opens
first, the first NIGHT_DATA and fixes usually arrive before the app's pipeline exists.
`ProjectionService` holds the latest restriction and vehicle reading and hands them over when
the pipeline is built.

SYNC 4.0 advertises nine types and accepts all nine:

| Type | Carries values? | Notes |
| --- | --- | --- |
| 1 LOCATION | yes | lat/lon at 1e7 degrees |
| 3 CAR_SPEED | yes | unit not separately confirmed |
| 7 PARKING_BRAKE | yes (once) | |
| 8 GEAR | yes (once) | enum: Neutral 0, 1–10, Drive 100, **Park 101**, Reverse 102 |
| 10 NIGHT_DATA | once per session | `is_night=true` on the truck because its display is pinned to dark mode; a mid-session change has not been seen |
| 13 DRIVING_STATUS | yes | |
| 21 GPS (satellites) | yes | the counts read sensibly only with `satellite_count` and `satellites_used` swapped (32 visible, 21 used); unconfirmed, and the app shows them under their vendored names |
| 6 FUEL_LEVEL, 24 TRAILER | accepted, silent | |

**Units.** The session (`VehicleState`) passes raw integers through and decides nothing.
`VehicleReading.kt` interprets them. `ScaleRule` tries candidate divisors smallest-first
against a plausible range, and `ScaleVotes` settles a scale per drive rather than per
message. The GPS bearing is **1e6** on SYNC 4.0 (103553459 = 103.55°), confirmed by eye on a
drive. A value nobody can explain is still drawn, with a mark, rather than hidden.

**Redaction.** Latitude and longitude always reach the app, because the map needs them. They
appear in a report only when **Record the vehicle's position** is ticked for that one
attempt (`sensor_location`). Otherwise the report keeps counts, accuracy, altitude,
speed, bearing and satellite counts, which say whether a fix arrived but not where. The
report separates `sensor_populated` from `sensor_silent`. A refused type is in neither
list, because a refusal says nothing about the vehicle. Values for unrequested types are
kept, and opaque manufacturer payloads are reported by length only.

Verified: truck.

## Keepalive

Head-unit PingRequests are answered with the timestamp echoed exactly. The bug-report flag is
counted and ignored. The source sends its own ping when the head unit has been silent for
2 s. Three unanswered pings and the next due ping ends the session with `KEEPALIVE_TIMEOUT`.

**SYNC 4.0 requires encrypted pings after TLS.** It pings encrypted about once a second.
After a single plaintext response it stopped pinging and stopped answering, while the same
session with encrypted replies ran cleanly. The upstream receivers send pings plaintext,
so the profile (`KeepaliveProfile`) is configurable:

- `mirror` (default): a response uses the encryption of the ping it answers. Own pings use
  the encryption of the head unit's latest ping, and are **encrypted after TLS until one
  arrives**. That last rule matters: in projection sessions Ford sometimes does not ping
  first.
- `plaintext` and `encrypted` force one form.

Nothing is encrypted before TLS. Wireless sessions also send their own network keep-awake
traffic, which is separate from these pings (see [connecting.md](connecting.md)).

Verified: truck.

## Shutdown

- **Stop on the phone after TLS** is graceful. The source stops audio and navigation, sends
  the video stop indication, then an encrypted ShutdownRequest (USER_SELECTION). It waits up
  to 2 s for ByeByeResponse. The app releases the native session after the response or a 3 s
  grace (`SHUTDOWN_GRACE_MS`), and the head unit returns to its own UI. Before TLS, Stop
  cancels at once.
- **Head-unit ShutdownRequest** is answered with an encrypted ByeByeResponse
  (`PEER_SHUTDOWN`). On wireless, SYNC sends one with USER_SELECTION when the truck powers
  down. On wired, the same power-off usually shows as an I/O error instead.
- A transport close after the source's shutdown request counts as a completed shutdown.
- Unplugging the cable ends with `IO_ERROR`/`IO_EOF` and `video_stop_indication_sent=false`.
  That is expected, not a defect.

The accessory descriptor has no `poll` and ignores `O_NONBLOCK`. `accessory.rs` therefore
uses one blocking reader and one blocking writer thread, interrupted at stop by a real-time
signal installed without `SA_RESTART`, re-sent every 10 ms within a 2 s budget. A thread
still blocked after that is detached and reported (`io_reader`/`io_writer`). The threads
are also stopped when the session panics.

Verified: truck.

## Reports

Every attempt writes a `key=value` session report (`SessionReport.kt` for the app part, the
native `Report` and `ProjectionReport` for the rest). Most fields quoted on this page come
from it: `final_category`, `last_completed_stage`, `auth_status`, `tls_peer_decision`,
`discovery_*`, `video_*`, `keepalive_*`, `sensor_*`, `ui_config_*`. An `AUTH_FAILED` with
`auth_status=-3` means the head unit refused the source identity. Reports hold no key
material or raw wire data. The accessory serial, head-unit name, serials, vehicle ID,
Bluetooth address and certificate common names are reduced to lengths, and position and raw
captures appear only when asked for on one attempt. The first line,
`report_format=openautoandroid-session-2`, keeps the old project name on purpose so older
tooling still recognises it. Saving and exporting reports is in [phone-app.md](phone-app.md).

## Other channels, briefly

- **Audio** (`audio-and-speech.md`): four PCM/AAC channels advertised. HeadLink opens at
  most one PCM MEDIA channel for app-owned audio, off by default. Audio focus is asked on the
  control channel, and every audio failure is non-fatal.
- **`media_info`**: now-playing to Ford's own UI. On by default, with no setup handshake
  beyond the open.
- **`navigation`** (`navigation.md`): the instrument cluster. Navigation focus is control
  messages 13/14. Ford never sends `InstrumentClusterStart`, so "send regardless" is
  required, and Ford answers one focus request with two PROJECTED responses.

## SYNC 4.0 at a glance

| Behaviour | Consequence in the code |
| --- | --- |
| Requests version 1.6 | `match-1.7` answers 1.7, as Android Auto does |
| Rejects the generated identity with AUTH_COMPLETE −3 after TLS completes | Identity import |
| Omits `AVChannel.stream_type`; codec per configuration | Per-config codec in discovery |
| Advertises 1920×882 touch for every configuration; sends frame-sized, content-origin coordinates | `TouchSpace::Content` |
| Pings encrypted about once a second; goes silent after a plaintext reply | `mirror` keepalive, encrypted by default after TLS |
| Video window 4, audio window 40 | Frame-only credits |
| Drops an AV setup whose field 1 is an index | Field 1 is the codec (3) |
| Grants PROJECTED unsolicited; re-grants 1–2 s after its own screen | Stop/start on every focus transition |
| Sends 0x8009 after start and after each regain; wants 0x800A echoing it | `ui_config_request` |
| Acknowledges every frame of a stream it is not showing | Nothing on the wire proves display; check the screen |
| Split vs full-width layout follows its own expand control | Not version-driven; recomposed via 0x8009 |
| Sometimes falls behind for minutes (smear, freeze) | Drained keyframes, congestion restart |
| Night mode sent once per session | Held and replayed to the pipeline |
| Never announces its cluster; two nav-focus responses | See navigation.md |

The OpenAuto Prodigy lab receiver differs in ways that matter when reading a green lab run:
it pings plaintext every 5 s, offers a window of 10, signals focus loss with NONE, never
checks the source certificate, echoes navigation focus requests, and never sends 0x8009.

## Open questions

- What field 16 of Ford's discovery response means.
- Which rectangle Ford actually displays: content or frame (`scene_area`).
- Whether the congestion restart ends a real spiral on the truck, and what starts one.
- The CAR_SPEED unit, and whether the satellite fields really are swapped (needs a moving
  vehicle and a deliberate comparison).
- Whether Ford ever sends a second NIGHT_DATA mid-session (needs a display not pinned
  dark).
- An occasional wired `FLOW_CONTROL_TIMEOUT` (seen twice in October 2026) is unexplained.
