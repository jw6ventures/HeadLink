# P4 lab results (before the projection vehicle test) — 17 September 2026

Implemented everything from P4 (the projection and interaction demonstration) that can
run without the truck: dynamic channel opens, AV setup and video focus, the H.264 encoder
and owned scene, media flow control with acknowledgements, head-unit input, an optional
sensor subscription, and graceful shutdown. App 0.3.0-lab (versionCode 8).

**This is lab evidence only.** No head unit has projected anything: **G4 and G5 remain
open**, and so do the lifecycle items that need the vehicle (background and screen-off
with projection, five reconnect cycles, ignition interruption). The controlled receiver is
OpenAuto Prodigy's head-unit handlers with FFmpeg decoding it in the same process, not a
factory head unit; its acceptance says nothing about Ford SYNC 4. The projection ran on
ART only in an x86_64 emulator with a software encoder, never on the Pixel 9.

## What was added

| Area | Result |
| --- | --- |
| `core/aap-proto` | 65 more vendored open-android-auto schemas (the import closure of channel open/close, AV setup/start/stop/ack, video focus, input binding and events, and the sensor subscription: 116 files), regenerated with the pinned protoc and prost-build, and channel-scoped message ID constants (`ids::av`, `ids::input`, `ids::sensor`) checked against the generated enums. |
| `core/aap-session` | `stop_after=projection`: stages Setup and Projecting (spec §9), channel opens on the target channel with flags 0x0F and strict response routing, AV setup with configuration reselection and the acknowledgement window, one video focus request with unsolicited indications handled, start indication, media send with flow control and congestion recovery, input binding and touch/button events mapped into the video content rectangle, an opt-in driving-status and night sensor subscription, `AVChannelStopIndication` plus a graceful shutdown request, and a projection section in the report. |
| `core/aap-session::media` | The encoder hand-off: buffers are normalized to Annex B (length-prefixed input converted), NAL types and SPS parsed and reported, the codec configuration is remembered and re-sent before a recovery IDR, and a bounded queue drops frames only until the next IDR so the head unit never decodes a hole. |
| `core/aap-session::input` | Touch validation and mapping through the advertised touch size into the content rectangle (margins and insets), pointer identity, and the actions PRESS, RELEASE, DRAG, POINTER_DOWN, POINTER_UP, and CANCEL. |
| `core/aap-jni` | `nativeSubmitVideo` (direct `ByteBuffer`, copied before returning), `nativeVideoFailed`, `nativeRequestShutdown`, event kinds for video start/stop, keyframe requests, focus, touch, keys, and restrictions, and the config keys `stop_after=projection`, `projection_ms`, `sensors`, `focus_wait_ms`, and `ack_timeout_ms`. |
| App (0.3.0-lab) | `VideoEncoder` (MediaCodec `video/avc`, baseline, no B-frames, ~2 Mbps at 800x480, one-second keyframes, drained on its own thread), `SceneRenderer` (EGL 1.4 with `EGL_RECORDABLE_ANDROID` and GLES 2 into the encoder Surface), `DemoScene`/`SceneLayout` (pure JVM: token, frame counter, elapsed time, four buttons with tap counts, crosshair), `ProjectionPipeline`, projection stages and report section, a sensors control, and a graceful Stop that asks the head unit to shut down before the session is released. |
| `tools/test-pattern` | A lab-only I_PCM/P_Skip H.264 encoder and YUV canvas, so session tests and the desktop harness produce real parameter sets and IDRs without a platform codec. |
| `tools/source-harness` | `--stop-after projection` with the same scene layout as the app, `--projection-ms`, `--sensors`, `--fps`, `--keyframe-ms`, `--focus-wait-ms`, and `--ack-timeout-ms`. |
| Lab receiver | Prodigy's head-unit video, input, and sensor handlers, FFmpeg (libavcodec) decoding of the received stream, scripted taps with per-button pixel checks in the decoded picture, a head-unit button press, a focus-loss script, and an acknowledgement-stall script. New image tag `p4` (adds libavcodec/libavutil/ffmpeg from the pinned Debian snapshot). |
| `tools/emulator-lab.sh` | The app itself projecting on ART: the emulator and the receiver share one Podman pod with only loopback, and the app reaches the receiver through an abstract local socket forwarded by adbd, so the APK keeps its permission set (no INTERNET, no network transport). |

## Verification

All on Linux x86_64 in Podman with networking disabled, except image builds.

| Check | Result |
| --- | --- |
| `tools/check.sh`: rustfmt, Clippy `-D warnings`, tests, release build, `proto-gen --check` | Pass |
| Rust tests | 117 passed: wire 8, proto 9, TLS 16, session 8, protocol 19, **projection 16**, poll transport 6, accessory transport 9, JNI manager 15, JNI probe 1, CLI 10 |
| Whole-workspace test suite repeated 20 times | 0 failures |
| `tools/android-build.sh`: OpenSSL and Rust cross-builds, Clippy, APKs, lint (warnings as errors), APK verification, JVM host tests | Pass; lint "No issues found"; JNI contract 82 checks (`-Xcheck:jni`), **scene 29 checks**, report store 18, trust store 17 |
| Two clean offline builds (`target/` and `app/build/` deleted each time, about 2 min each) | Bit-for-bit identical: both APKs, both `libaap_jni.so`, the harness, and the OpenSSL archives |
| APK invariants | Unchanged permission set (foreground service, connectedDevice, notifications; no INTERNET), one `connectedDevice` service, only `lib/arm64-v8a/libaap_jni.so`, only `JNI_OnLoad` exported |
| Mutation spot checks (each applied, tested, restored) | 11 of 11 caught |

Artifact SHA-256 (both clean builds):

| Artifact | SHA-256 |
| --- | --- |
| `app-debug.apk` (7,789,375 bytes) | `d8a216640210303aa879fbb87fb72274ac297e66b29127ef07f07762e489320f` |
| `app-release-unsigned.apk` (7,358,203 bytes) | `646fae43cfe89e539556a5e8c3c9e31b4d7792ea6ccec4beaf4a514f974df17f` |
| `aarch64-linux-android/release/libaap_jni.so` | `8670f4d2838ba516463ebe67abe8e4eaa3fca6f5fea8caa87fad03f63ebf16a8` |
| `release/libaap_jni.so` (host) | `36618edd90f0bf3ce350f7e85a4fb0dd64898b8ccfb02dde309cedf3a61928b6` |
| `release/source-harness` | `e1561bb6612c59830273808d91cc2d59feec92a07d5a8909270f6026a051a51d` |

The debug APK is signed with the local debug key kept in the build cache; it is not a
project identity.

Mutations and the test that caught them: a channel-open response without the 0x04 bit
accepted; the acknowledgement window ignored; configuration not re-sent before a recovery
IDR; touch mapping without the content offset; AV setup status ignored; focus NONE treated
as projected; media still queued while focus is lost; head-unit channel-open requests not
refused; frames sent without their timestamp; sensors opened without being asked for;
submitted buffers not checked for Annex B. Each failed its projection test.

## Controlled receiver runs (lab video decode and input)

`tools/lab-receiver.sh demo` with `LAB_STOP_AFTER=projection`; the receiver requests 1.1,
generates fresh credentials, registers Prodigy's head-unit handlers, and decodes every
received access unit with libavcodec (`AV_EF_EXPLODE`), then the whole captured stream is
decoded again by the `ffmpeg` binary. The source is the desktop harness with the
test-pattern encoder at 10 fps.

| Run | Receiver | Source result |
| --- | --- | --- |
| `LAB_SCRIPT=taps` | 20 pictures decoded, 0 decode errors, 800x480; each of the four buttons sampled before and after its tap; 11 checks passed | `PROJECTION_COMPLETE`, `last_completed_stage=projecting`, `scene_taps=1,2,1,1` |
| `LAB_SCRIPT=focus` | Focus dropped to NONE, no pictures while it was lost, focus restored, 17 pictures, 0 decode errors, 4 checks passed | 1 focus loss, 1 regain, configuration re-sent once before the recovery IDR |
| `LAB_SCRIPT=stall` | Acknowledgements withheld about two seconds then released in one message, 28 pictures, 0 decode errors, 3 checks passed | 1 congestion episode, 1 frame dropped congested, 5 dropped awaiting the keyframe, configuration re-sent once, stream continued |
| `LAB_MARGINS=74x0 LAB_TOUCH=1920x882` (Ford-like) | Content rectangle 37,0+726x480; taps sent in the 1920x882 touch space; 11 checks passed | Touch 1428,696 mapped to 576,378 inside button 4; `discovery_usable_video_geometry=margins=74x0 insets=top=0,bottom=0,left=37,right=37 content=37,0+726x480` |

Every run ended with the receiver's `ffmpeg_exit=0` over the captured Annex B stream.

> **The Ford-like row is superseded by G4 and again by the 5c9ac430 calibration.** It
> assumed the head unit sends coordinates in the touch space it advertises. The vehicle does
> not: Ford sends frame pixels (report da9398a5) counted from the content rectangle's origin
> (report 5c9ac430), so the advertised size is not a scale factor and `LAB_TOUCH_SPACE` now
> defaults to `content` (spec §8.3, docs/references.md). Re-run with the same 74x0 margins
> and 1920x882 advertised touch: taps for 223,196 / 577,196 / 223,379 / 577,379 go out at
> 186,196 / 540,196 / 186,379 / 540,379, map back to the pixel they were aimed at, and each
> lands on its own button (`scene_taps=1,2,1,1`, `input_touch_space=content size=800x480
> target=37,0+800x480`, `input_raw_range=x=186..540 y=196..379`).
> `LAB_TOUCH_SPACE=advertised` against the default source fails the receiver's check
> (exit 4) — the truck's original symptom reproduced. Pairing `LAB_TOUCH_SPACE=frame` or
> `content` with a matching `source-harness --touch-space` passes as well, so the other
> spaces are exercised.
>
> **What this lab cannot catch.** Its taps land at button centres, so a 37px horizontal
> error does not push a tap out of its button: `LAB_TOUCH_SPACE=frame` against the current
> default (a real 37px mismatch) still passes 11 checks and exits 0. That is exactly the bug
> the vehicle showed. Only an edge press or the target-cross calibration measures the origin.

## The app projecting on ART (emulator lab, not device evidence)

`tools/emulator-lab.sh taps`, AOSP API 36 x86_64 emulator with CheckJNI, the debug APK
with an added x86_64 library, the receiver in the same loopback-only pod:

- The app opened video channel 3 and input channel 1 (the receiver's advertised IDs),
  AV setup returned OK with `max_unacked=10` (the Prodigy lab receiver's value, not Ford's:
  Ford's video window is 4 and its audio window 40, both measured on the vehicle), the receiver's unsolicited PROJECTED focus
  arrived before any request (so the source sent none), and the start indication went out
  22 ms after discovery.
- Encoder: `c2.android.avc.encoder` (software, `hardware=false`), baseline, 2,000,000 bps,
  30 fps, size and rate supported. 91 frames rendered, 92 buffers submitted, **92
  accepted, none dropped or refused**, 4 keyframes, 135,544 bytes sent, 91
  acknowledgements, no congestion, no timestamp regressions.
- The receiver decoded **91 pictures with 0 decode errors** at 800x480 and verified all
  four taps: each button's sampled patch changed from the idle gray to that button's color
  (for example button 1 `109,128,128` → `79,111,198`). A head-unit button press (keycode
  84) also reached the scene. 11 checks passed.
- The app's report shows `scene_taps=1,2,1,1`, `scene_last_touch=595,379`,
  `scene_last_key=84`, and `input_rejected=0`.
- `focus` and `stall` scripts against the app passed as well: focus loss paused the
  renderer (3 submissions dropped as not streaming) and the regain re-sent configuration
  with a new IDR; the acknowledgement stall produced 1 congestion episode, 4 frames dropped
  congested and 69 dropped awaiting the keyframe, then the stream recovered — 0 decode
  errors in both.
- No JNI error or app crash in logcat.

A picture decoded from the app's own stream (`frames/003.png` in the run directory) shows
the session token, frame counter, elapsed seconds, the four buttons with their tap counts,
the touch crosshair, and the last keycode.

## Decisions taken in this slice

- **Channel opens are sequential**: video, then AV setup, then input, then the binding,
  then (only if configured) the sensor channel and one request per sensor type, then the
  focus request. Each request has its own deadline, so a report names exactly which step a
  head unit did not answer.
- **Only the responses the spec describes are accepted**: a `ChannelOpenResponse` on the
  target channel with the 0x04 bit. One on channel 0, one without the bit, or one for a
  channel with no open pending is counted (`projection_open_responses_misrouted`,
  `…_unexpected`) and never accepted; the step then ends with `SETUP_TIMEOUT`, which is
  the evidence a factory variation would need before a new profile is written.
- **Focus is requested once.** An unsolicited PROJECTED indication starts the stream
  without a request; a NATIVE answer is not re-requested, the source waits (30 s by
  default, so an operator can select projection on the head unit) and then ends with
  `VIDEO_FOCUS_DENIED`. Losing focus pauses the stream and clears the queue; regaining it
  re-sends the codec configuration and the next IDR.
- **Flow control is enforced**: at most `max_unacked` media units are outstanding, the
  acknowledgement count comes from `receive_timestamps` when present and `ack_count`
  otherwise, and a full window with no acknowledgement for 5 s ends with
  `FLOW_CONTROL_TIMEOUT` rather than stalling silently.
- **Congestion never sends a broken stream.** A frame that finds the queue full is dropped
  together with every later frame until the next IDR, which is preceded by the codec
  configuration; the app is asked for that IDR through a keyframe request.
- **Touch coordinates are assumed to span the content rectangle** (the part of the frame
  the head unit shows). That is what the pinned receiver and the open-android-auto
  head-unit notes describe, but Ford advertises a 1920x882 touch area for a 1334x882
  content rectangle at 1080p, so the assumption is unproven for that unit. The scene draws
  a crosshair at the mapped point and reports the raw coordinates, so one vehicle visit
  produces the data to settle it.
- **Sensors are opt-in** (spec §8.1: add them only if the receiver needs them). When
  enabled, only advertised driving-status and night data are requested, and the values are
  shown in the scene and the report. An unknown restriction state is left as is; the
  demonstration is a stationary test scene either way.
- **Stop is graceful after TLS**: the app asks the session to stop the stream and send a
  shutdown request, so the head unit returns to its own interface, and releases the native
  session after the ByeByeResponse or a 3 s grace period.
- **Stop after TLS is graceful for every stop stage**, not only projection: a discovery
  attempt that is stopped during its hold now sends a shutdown request too (the P2/P3
  limitation "Stop from the UI cancels immediately" is closed). Before TLS, Stop still
  cancels at once.
- **The lab transport is debug-only.** A `lab_socket` intent extra makes a debuggable build
  connect to an abstract local socket instead of a USB accessory; release builds ignore it,
  such an attempt never pins a head-unit certificate, and the report records
  `transport=lab-socket:<name>`.

## Findings that change later work

- A `connectedDevice` foreground service is refused without a connected device: the
  platform requires the USB accessory (or another connected-device permission) at the
  moment of promotion. The lab-socket path therefore runs as an ordinary started service
  while its activity is visible; a vehicle attempt is unaffected.
- Prodigy's head-unit video handler sends an **unsolicited PROJECTED focus indication right
  after the setup response**, so a source that waits for its own focus response would still
  start, and one that insists on sending a request first is not exercising that path. Both
  orders are handled and reported.
- Prodigy signals focus loss with `VideoFocusMode` **NONE (0)**, not NATIVE. Anything that
  is not PROJECTED or PROJECTED_NO_INPUT_FOCUS counts as lost, and the mode is reported.
- The I_PCM test-pattern encoder produces very large access units (about 579 KB for an
  800x480 IDR). That exercised the 4 MiB AV assembly limit and fragmentation over the lab
  transport, and it is why the lab numbers show far more bytes than the app's real encoder
  (135 KB for 3.5 s of video).
- FFmpeg reports "no frame" for a codec configuration unit; the receiver counts those
  separately (`receiver_video_configs`) so a re-sent configuration is not a decode error.

## Limitations and open items

- **G4 and G5 are untouched.** Nothing has been projected onto a head-unit display, no
  vehicle input has been received, and the five reconnect cycles, the five-minute
  interaction run, the background and screen-off tests, and ignition interruption are all
  still to be done in the truck: see [the G4/G5 procedure](g4-g5-procedure.md).
- The phone has not run projection at all: MediaCodec on the Pixel 9 (a hardware encoder),
  EGL on its GPU, and the encoder's colour conversion are unverified. The emulator used a
  software encoder at 800x480.
- The receiver decodes and checks colours; it does not judge latency, smoothness, or
  whether a real head unit would accept this bitstream's profile and level.
- The touch-mapping assumption above is the main protocol risk for G5.
- The spec's JSON evidence bundle (`summary.json`, `events.jsonl`, `capabilities.json`) is
  still not produced; reports remain `key=value` text with the same content.
- Audio, microphone, navigation, and the other advertised services are still never opened.
