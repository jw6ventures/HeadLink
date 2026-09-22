# Ford SYNC 4 projection attempts — 17 September 2026

Evidence: the three 0.3.0-lab projection attempts in
`openautoandroid-saved-reports.txt`, with the operator reporting no picture on the
vehicle display. G4 and G5 remain open. `last_completed=projecting` means the first
keyframe was written to the transport; it does not establish that the head unit
decoded or displayed it.

| Attempt | Sensors | Media units sent (configuration + IDR + other frames) | Units acknowledged | Outcome |
| --- | --- | --- | --- | --- |
| fcd0251c, 19:59:43 UTC | off | 1 + 1 + 2 | 0 | FLOW_CONTROL_TIMEOUT |
| 54264afe, 20:00:39 UTC | off | 1 + 1 + 2 | 0 | FLOW_CONTROL_TIMEOUT |
| eff6933d, 20:01:04 UTC | on | 1 + 1 + 6 | 4 | FLOW_CONTROL_TIMEOUT |

All three successfully negotiated version 1.5 in response to 1.6, established TLS,
authenticated, discovered usable video/input, opened the channels, received AV setup
OK with a window of four, and received PROJECTED focus. They selected configuration
2, 800×480 at 60 fps, with a 726×480 content rectangle. The Pixel selected
`c2.exynos.h264.encoder`; its reported SPS is baseline (profile 66), constraints
0x00, level 31, 800×480. No encoder error or invalid submitted buffer was reported.
These checks validate framing and selected SPS fields, not picture decoding.

The media window filled and remained full for five seconds in every attempt. The
last attempt's acknowledgements show some AV traffic was accepted, not that a
picture was displayed. The sensors difference is insufficient evidence to conclude
that sensors caused the acknowledgements. There was no focus-loss indication.

## Confirmed keepalive problem and correction

None of the attempts received a head-unit ping. The `mirror` profile therefore sent
its two own pings in plaintext, and neither received a response. Earlier Ford
discovery experiments established that plaintext pings go unanswered and encrypted
pings work. The previous mirror implementation depended on receiving a Ford ping
before sending its own; that condition did not hold during these projection runs.

In 0.3.1-lab, mirror initially sends encrypted pings after TLS. Once a head-unit
ping arrives, it still mirrors that ping for responses and subsequent requests.
Explicit plaintext/encrypted overrides remain available, and pre-TLS pings stay
plaintext. Regression tests cover a silent-until-ping encrypted peer, a plaintext
pinging peer, explicit overrides, and actual silence.

The first-keyframe timestamp now occupies its own report line so the app's
line-based field parser can display it rather than printing `null`.

This corrects a known keepalive problem, **not a proven cause of the blank display**.
The media stall began before the first source ping, and the session ended on media
flow control before the keepalive deadline. Do not increase the media window or
ignore acknowledgements to hide this failure.

## Next vehicle check

Install the 0.3.1-lab debug APK as an update, retaining the source identity and pin.
Use `legacy-1.5-any-minor`, `tls12-aead`, stop after `projection`, and mirror (or
explicit encrypted) keepalive. Leave sensors off for the first comparison. Reconnect
and export the report, recording separately whether a picture appeared.

Check `keepalive_own_encrypted` and `keepalive_own_responses` first. If keepalive works
but video still ends in FLOW_CONTROL_TIMEOUT, the remaining investigation is the
Pixel hardware encoder's actual bitstream and Ford AV/decoder buffering behavior.
The current reports contain no encoded access-unit capture and cannot distinguish
those causes. A controlled receiver run using this Pixel's encoder, with H.264
capture and decoding, is the next useful isolation test; prior P4 lab video used an
emulator software encoder and does not validate the Pixel encoder.

## Validation of 0.3.1-lab

Both `tools/check.sh` and `tools/android-build.sh` passed in Podman: 117 Rust tests,
formatting, Clippy, generated-protocol checks, debug/release APK builds, Android
lint, APK verification, and the JNI/store/scene host checks. This is lab validation;
no vehicle run of the new build has occurred.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (versionCode 9).
SHA-256: `3b820749ebe421f9dde1d8b8fb9467d8511f3a8ef4454d9816a46ee638aa8d64`.

## Follow-up: timeout persists; frame credits corrected in 0.3.2-lab

The operator still reports the four-unit FLOW_CONTROL_TIMEOUT after the keepalive
change. No new full report was supplied for this follow-up, so its keepalive counters
and encoder details are not inferred.

A protocol audit found an independent accounting bug: SPS/PPS configuration consumed
a frame credit. The Android Auto video sender consumes credits for timestamped
frames only (see the audit in `references.md`). A four-frame batching receiver
could receive configuration plus only three frames before our sender stopped, so it
could never reach its acknowledgement threshold. Repeated configuration could also
gradually exhaust the window. This is a demonstrated sender bug; the available Ford
reports do not establish its precise ACK batching or decoder policy.

0.3.2-lab sends configuration without consuming a frame credit. It continues to
bound actual frames to the advertised window of four and retains the five-second
stall timeout. The simulator and controlled decoder receiver no longer acknowledge
configuration. The regression sends five batches of four frames, each preceded by
configuration, and checks all 20 frames are acknowledged with zero leaked or excess
credits. Existing congestion, focus recovery, and timeout tests remain in place.

Next vehicle attempt: install 0.3.2-lab as an update and keep the settings from the
previous encrypted-keepalive attempt. Export the full report whether it succeeds
or fails. G4 still requires observing the picture on the vehicle display.

Validation of 0.3.2-lab: `tools/check.sh` passed all 118 Rust tests, formatting,
Clippy, release builds, and generated-protocol checks. `tools/android-build.sh`
passed debug/release builds, lint, APK checks, and JNI/store/scene host tests.
The rebuilt controlled receiver, acknowledging frames only, decoded 60 frames
without errors and passed all 11 touch/key checks. Its forced ACK-stall run decoded
52 frames with two configuration units, no decode errors, and all three recovery
checks passed. Both captured streams also decoded with `ffmpeg_exit=0`.

0.3.2 debug APK SHA-256:
`eb4da60c9e10dddc3c0351411d85b59d9aae4ed9c34634e9dabbede1aca9c7bd`.
These are controlled-receiver results, not confirmation of Ford display output.

## 25eb3bcc: corrected sender still stalls; encoder comparison in 0.3.3

The newly supplied `report-20260917T203803Z-25eb3bcc.txt` confirms 0.3.2-lab.
Video setup and unsolicited PROJECTED focus succeeded. The sender wrote one
configuration plus eight video frames; Ford acknowledged four frames, leaving
four outstanding. Both source pings were encrypted and neither was answered.
There was no focus loss, source encoder error, or invalid submitted video buffer.
This does not prove that Ford decoded the acknowledged frames or isolate why it
stopped responding. Sensors were enabled; authentication and first-use pinning
succeeded.

0.3.3 adds an explicit software-encoder comparison. The checkbox defaults off.
When selected, only a software H.264 encoder advertising support for the same
negotiated format is eligible; there is no hardware fallback or resolution/fps
change. An unavailable software encoder produces ENCODER_FAILED with an explicit
reason. Reports include `software_encoder` and the selected implementation's
`software` flag. This follows Android's MediaCodecInfo.isSoftwareOnly and
CodecCapabilities.isFormatSupported APIs:
https://developer.android.com/reference/android/media/MediaCodecInfo
https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities

Next test: install the update, enable **Use software video encoder (diagnostic
comparison)**, and keep the other settings from 25eb3bcc. Export the report and
record whether a picture appeared. A successful software run implicates the
hardware-encoder path; another failure leaves protocol/decoder behavior unresolved.
This is an isolation experiment, not a claimed fix for Ford projection.

## 0.3.4: the transport re-sent bytes; a replayed TLS record silences the peer

Before the vehicle retry, 0.3.3-lab was exercised on the Pixel 9 itself against the
controlled lab receiver (receiver on host loopback, the phone's abstract socket
reverse-forwarded by adbd; scratch procedure, not a repository script). Both runs with
the software encoder selected `c2.android.avc.encoder` (800x480@30, Constrained
Baseline level 4.1; the receiver advertises 30 fps), the receiver decoded 43 and 44
frames without errors and acknowledged every unit, and the captured stream decoded
with `ffmpeg_exit=0`. About 1.3 s into streaming the receiver failed with
`SSL_read ... decryption failed or bad record mac` and closed; the phone recorded
`IO_EOF`. Reports: `report-20260917T214822Z-0ef3f57e.txt` (Work user, by mistake)
and `report-20260917T215211Z-29d55cab.txt`.

The receiver's read log shows the cause. In one run it parsed the same plaintext
ping (identical timestamp) from two consecutive reads of 11 and 427 bytes, then failed
on a 416-byte read: 427 = 11 + 416, so the ping and the following media frame were each
sent twice. In the other run the failing 326-byte frame followed a 326-byte frame that
decrypted correctly. The phone's own counters show one ping and one frame each. A
replayed TLS 1.2 AES-GCM record has a stale sequence number, so the receiver's MAC
check fails on it.

The defect is in `core/aap-session/src/accessory.rs`. The protocol thread credits bytes
the writer thread finished at the top of each iteration, but cuts the next chunk from
the session's pending output at the bottom of the iteration. When a write completes in
between (typically while the iteration is busy with a received acknowledgement and the
app callback), the pending output still begins with the finished bytes and they are
handed out again. This needs a wake-up during an in-flight write, which is the normal
state while video frames are acknowledged; the emulator rarely hit it, the phone did
within two seconds in both runs. The fix hands out a chunk only when no finished write
is waiting to be credited; the next iteration is immediate in that case. The regression
test `a_write_finishing_during_an_iteration_is_not_sent_twice` blocks the writer on a
small socket buffer, wakes the protocol thread, drains the chunk during `receive`, and
checks the peer receives every byte exactly once; it failed on the previous code with
the whole chunk sent twice.

Relation to Ford: on `25eb3bcc` Ford acknowledged four frames and then answered
nothing, including two encrypted pings, without a TLS alert or a close. A head unit
whose TLS layer rejected a replayed record and stopped reading would look exactly like
that, and every prior Ford projection attempt streamed with this transport. This is a
demonstrated sender defect that is sufficient to explain the stall; it is not proof
that it is Ford's actual failure, which only a vehicle run can show.

Next vehicle attempt: install 0.3.4-lab as an update and keep the settings from
25eb3bcc, hardware encoder first (checkbox off). Export the report. If it still stalls,
repeat with the software encoder checked. G4 still requires the picture on the display.

## 0.3.4 on the vehicle: G4 passed, and the touch space was not what we assumed

Report `da9398a5`, 0.3.4-lab on the Ford. **The scene appeared on the head unit**: 800x480@60
on channel 1, 3553 frames and 62 keyframes sent, every unit acknowledged, 61 s of
projection, one congestion episode recovered at an IDR, graceful stop. The transport fix
held — no stall, no `IO_EOF`. That is G4.

Touch reached the source but landed in the wrong place. 719 input events, 0 rejected, and
`scene_taps=21,0,0,0`: every one of 21 taps registered on button 1 whatever the operator
pressed. Photographs of the display show a finger on button 4 while the crosshair sits in
button 1, with the scene's own printout reading `235,198 raw 525,364`, and the report has
`scene_last_touch=228,206 scene_last_touch_raw=506,379`.

Those raw numbers are the answer. Ford advertises `touch=1920x882`, and the source stretched
that onto the 726x480 content rectangle — but the coordinates Ford actually sends are inside
the selected **800x480 frame**. Interpreted that way, 525,364 is button 4, exactly where the
finger was. Interpreted as 1920x882 they are 27% and 41% of the way across, which is why the
reachable region collapsed to x ≤ 339, y ≤ 260: buttons 2, 3, and 4 could not be hit at all,
matching `21,0,0,0`.

Ford's 1920x882 looks like its native 1080p layout — full frame width by content height
(1920x1080 with 586x198 margins gives a 1334x882 content rectangle) — advertised once on the
input channel and not restated for the configuration actually selected. So the advertised
size is not a scale factor. `TouchSpace::Frame` is now the default and the mapping is the
identity; `ProjectionConfig::touch_space` (and `source-harness --touch-space`) still select
the documented `Content` and `Advertised` behaviours, and `lab-receiver --touch-space`
reproduces all three. With the Ford geometry the lab now passes on `frame`, fails on
`advertised` — the vehicle symptom reproduced — and passes on `content` when both sides agree.

One thing the drive did not measure: whether Ford counts from the frame origin or the
content origin, a 37px horizontal difference at 800x480. No observed tap distinguishes them.
The report now prints `input_touch_space` and `input_raw_range`, so an edge press settles it
— minimum x near 37 means the frame, near 0 means the content rectangle (see
`docs/g4-g5-procedure.md` §4).

Next vehicle attempt: install 0.3.5-lab as an update and run G5 — each button, the four
corners pressed as close to the edge as the picture allows, the centre, the hardware keys,
five minutes with a background and screen-off interval, then five reconnections.

## 5c9ac430: the grid starts at the content rectangle, and the last 37px are the inset

That last paragraph was wrong twice over, and both corrections matter.

The edge-press test does not work. Ford normalises to the frame *dimensions* whichever
origin it counts from, so both hypotheses predict the same 0..800 span; report 363a4d96 read
`input_raw_range=x=8..791`, which fits either. What settles it is a known pixel, so 0.3.6-lab
drew a target cross at each button centre and paired it with the raw coordinate Ford sent:

```
scene_calibration=1:target=223,196:raw=192,203 2:target=577,196:raw=533,204
                  3:target=223,379:raw=178,388 4:target=577,379:raw=541,386
```

Fit those four: x is `0.994 × target − 36.8`, y is `1.003 × target + 7.7`. Unit scale on both
axes, which reconfirms the frame dimensions. In x, a uniform leftward offset of 39 ± 6 px
with **no sign flip between the left and right columns** — so not the content-rectangle
stretch, which would have put button 1 at 205 and button 2 at 595, 18px out in opposite
directions. 37 is the left inset. Ford's grid has the frame's dimensions and the content
rectangle's origin.

That pairing is peculiar, and the enum could not express it: `TouchSpace::Content` sized the
grid to the content rectangle (726x480) as well as moving it, which would have rejected the
x up to 791 Ford demonstrably sends. It now means frame dimensions at the content origin,
and is the default. Its far end addresses pixels past the frame — the strip of head unit
that shows nothing of our picture — and those clamp onto the frame's last pixel.

The vertical residual, +7.7px, has no geometric explanation: the margin height is 0, so
every candidate mapping is the identity in y. It is about 2mm on the vehicle's screen, which
is the size of the offset between a fingertip's perceived position and the centre of its
contact patch, and it is the same magnitude as the scatter in x (±6px). Treating it as the
operator rather than the map is a judgement, not a measurement — so 0.3.7-lab also records
the raw coordinate at the **release**, after the operator drags the live crosshair onto the
target cross. Both features are then in the picture, so parallax and the contact patch cancel
and what is left is the map alone. If the release pairs still read `target + 8` in y, the
model is missing something; if they read `target`, it was the operator.

Next vehicle attempt: install 0.3.7-lab as an update and run G5 with the drag-and-release
calibration in `docs/g4-g5-procedure.md` §4.

## 617af5ce: confirmed on the vehicle, and the vertical residual is left alone

0.3.7-lab on the truck: 78 seconds projecting, 4663 frames, `scene_taps=7,7,7,7`, 199 input
events with none rejected or invalid, `input_touch_space=content size=800x480
target=37,0+800x480`. The operator reports the alignment correct. The run ends
`IO_ERROR (os error 5)` with `video_stop_indication_sent=false`, which is the cable being
pulled, not a defect. Touch alignment is closed.

The +7.7px vertical residual is **accepted, not explained**. It is about 2mm on that screen,
the size of the offset between where a fingertip appears to be and the centre of the patch it
presses, and the margin height is 0 so no candidate mapping produces it. It was not measured
on this drive either: the calibration capture records a pair for every press inside a button,
so the drive's ordinary taps overwrote the aimed ones and left four merely-interior points
scattered over 82px (a deliberate pass scatters over 14px). That property is documented on
`DemoScene.targetPress`, including the trap of "fixing" it by keeping the press nearest the
target, which would bias the estimate toward zero.

Anyone reopening this needs: the capture gated on a gesture with a move, and a run that
presses the four crosses and then stops touching the screen. At 2mm it did not earn another
drive.
