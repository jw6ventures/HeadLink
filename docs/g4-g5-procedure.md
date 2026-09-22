# G4/G5 projection test procedure (vehicle)

Prepared in the P4 lab slice (17 September 2026, app 0.3.0-lab). **Nothing here has been
run in a vehicle.** It continues [the G0–G3 setup record](g1-setup-record.md), which stays
the reference for the vehicle, phone, cable, port, and report handling; fill in one copy of
its §1 setup table per session and attach every exported report.

Spec references: §8 (projection demonstration), §9 (states and errors), §10 (evidence),
§11.1 (gates G4 and G5), §11.3 (physical test matrix).

Gate reminder, from spec §11.1:

| Gate | Required evidence |
| --- | --- |
| G4 display | The independently encoded scene appears on the factory display with a fresh session token and a moving counter, for 60 seconds |
| G5 interaction | Factory touch and buttons change the projected scene, the session runs 5 minutes, and it reconnects successfully 5 times |

Counter output from the phone alone is not evidence: G4 and G5 need an operator
observation with a photo or video of the head-unit display showing the token and counter,
correlated with the exported report of the same attempt (its `scene_token`).

## 1. Before driving anywhere

1. Build and install as in the setup record §2, then confirm at the desk:
   - `[native_probe] self_test=pass` and `[trust] tls_self_test=pass`.
   - Optionally `tools/emulator-lab.sh taps`, which runs the whole projection path on ART
     against the controlled receiver. It is not device evidence and cannot pass G4.
   - Better, with the phone on adb: `tools/pixel-lab.sh`, which runs the *installed* APK
     against that receiver with the Ford geometry, so the touch arithmetic the vehicle will
     do is exercised before the drive. Expect `receiver_check_failures=0` and a report whose
     `scene_calibration` reads `press=186,196 … 540,379` — target minus the 37px inset in x.
     Still not vehicle evidence, but it is the cheapest way to not waste a trip.
2. Confirm the head unit is already pinned from the G2/G3 visit (**Pinned head units** on
   the app screen). Projection attempts should run with first use **unchecked**, so a
   changed certificate is a recorded failure rather than a silent re-trust.
3. Decide the attempt plan below; each attempt is one Connect, one report.

## 2. What a projection attempt does

Choose **Stop after** `projection`. After the G3 sequence the app opens the discovered
video and input channels, negotiates AV setup and video focus, starts the stream, and
renders its own scene until you tap **Stop** (or the head unit ends the session).

The scene shows, inside the content rectangle the head unit advertised: the project name
and a fresh six-digit session token, a frame counter and elapsed seconds, a bar that moves
every frame, four numbered buttons with their tap counts, a crosshair at the last touch
with its mapped and raw coordinates, and the last head-unit keycode.

Stage sequence on the app screen: ARMED → PERMISSION → VERSION → TLS → AUTH → DISCOVERY →
SETUP → PROJECTING → (Stop) → IDLE.

The screen starts on **Vehicle sensors** `all` and **Media controls** checked (spec §8.1),
and G4 and G5 run that way: leave both alone. Nothing in either gate depends on them, a
head unit that refuses either channel has the refusal recorded and skipped, and running
the gates on the shipped defaults means the reports are evidence about the build the
vehicle will actually see. Every G4/G5 report then also carries the sensor harvest lines
(§6), which is evidence that costs nothing once the truck is already stationary. Narrow
the set only to isolate a failure (§6).

## 3. G4 — the scene on the factory display

1. Stationary vehicle, parking brake set, phone unlocked, head unit on its own interface.
2. In the app: profile as recorded in the G1–G3 session, **Stop after** `projection`,
   first use unchecked, **Vehicle sensors** and **Media controls** left as they start
   (`all`, checked). Tap **Connect**, then plug in.
3. If the head unit asks whether to start Android Auto (or shows a projection icon),
   accept it: the source waits up to 30 s for projected focus and does not ask again.
4. When the scene appears, **record the token shown on the head unit** and photograph or
   film the display for at least 60 seconds, so the moving counter is visible. Note the
   time on the app screen at the start and end of the recording.
5. Tap **Stop** in the app (or on the notification). Export the report.

| Report content | Meaning |
| --- | --- |
| `last_completed=setup` or later, `projection_step=streaming` | Channels opened, AV setup accepted, focus projected, start indication sent |
| `video_selected=…`, `av_setup_status=2 (OK)`, `av_setup_window=…` | The configuration and acknowledgement window the head unit accepted |
| `video_first_keyframe_written_ms=…` and `video_frames_sent` > 0 with `video_acks` > 0 | The head unit acknowledged media: it consumed the stream |
| `[projection] scene_token=…` equal to the token on the display, `projecting_ms` ≥ 60000 | **G4 evidence**, together with the photo or video |
| `projection_encoder=…` | Which MediaCodec encoder ran, whether it is hardware, the bitrate and frame rate |

Failures, in the order spec §12 says to investigate (open/setup/focus, Annex B, parameter
sets, timestamps, flow control):

| Final category | What to record and try next |
| --- | --- |
| `CAPABILITIES_INSUFFICIENT` | Discovery was valid but had no usable video or input; keep `discovery_channel_*` and `discovery_issue_*` (this is a G3-level result, not a projection bug) |
| `CHANNEL_REJECTED` | `projection_open_…` lines name the channel and status; a refusal of the input channel ends the attempt even though video might have worked |
| `SETUP_TIMEOUT` | The step is in `final_detail` and `projection_step`. Check `projection_open_responses_misrouted` and `…_unexpected`: a nonzero value means the head unit answered somewhere else, which is the evidence needed before adding a routing profile |
| `AV_SETUP_FAILED` | Record `av_setup_status` and `av_setup_accepted_configs`; a head unit that accepts no advertised configuration cannot be projected to |
| `VIDEO_FOCUS_DENIED` | The head unit never gave projected focus within 30 s (`video_focus_mode` shows its last answer). Try selecting the phone projection on the head unit *before* the deadline on the next attempt |
| `ENCODER_FAILED` | `projection_encoder_error` and `video_last_invalid` say whether MediaCodec failed or produced something the session refused; this is a phone-side failure |
| `FLOW_CONTROL_TIMEOUT` | The head unit stopped acknowledging: record `video_outstanding`, `av_setup_window`, and `video_acks` |
| `KEEPALIVE_TIMEOUT` | Pings stopped; compare `keepalive_hu_encrypted` with `keepalive_responses_encrypted` as in the G3 procedure |
| `INPUT_BINDING_FAILED` | `input_binding_status`; the head unit refused the advertised keycodes |

Nothing appearing on the display while the report shows frames sent and acknowledged is
itself evidence: record it with `video_selected`, `video_sps` (the encoder's profile,
level, and size), and the head unit's behavior, then investigate parameter sets and
timestamps before changing anything else.

## 4. G5 — interaction, five minutes, and five reconnections

1. With the scene up, tap each of the four buttons on the head-unit screen and check that
   its count rises **on the display** (not only in the report). Then tap:
   - each corner of the projected area, **pressing as close to each edge as the picture
     allows**,
   - the center,
   and note where the crosshair lands each time. The scene prints the mapped and raw
   coordinates; photograph one of them.

   The first drive (report da9398a5) established that Ford sends coordinates in the
   selected configuration's frame pixels rather than the 1920x882 touch screen it
   advertises; the calibration below (report 5c9ac430) established that it counts them from
   the content rectangle's origin. Both are in the default now (spec §8.3).

2. **Calibrate against the target cross at the centre of each of the four buttons.** For
   each one: press it as precisely as you can, then — without lifting — **slide your finger
   until the large live crosshair sits exactly on the small target cross, and lift there.**

   Both coordinates are recorded, and they answer different questions:

   ```
   scene_calibration=1:target=223,196:press=203,190:release=223,196 2:target=577,196:...
   ```

   `press` is the head unit's coordinate for where you aimed, so it carries your aim,
   parallax, and the contact patch of your fingertip along with the map. `release` is the
   coordinate for a point you have just confirmed *on the screen* is the target, so it is
   the map alone — the two features you are lining up are both in the picture, and every
   bias that displaces your finger relative to the picture cancels. **`release` is the
   measurement**; take the time to line it up.

   Four pairs solve a scale and an offset per axis. Expect `release ≈ target - 37` in x and
   `release ≈ target` in y on Ford, which is what the current map inverts (spec §8.3): the
   mapped crosshair should now sit under your finger. A residual constant difference is an
   origin offset still unaccounted for; a difference that grows across the screen is a
   scale error. This replaces reading `input_raw_range` to tell the frame origin from the
   content origin — that test does not work, because the head unit normalises to the frame
   *dimensions* either way and both answers predict the same 0..800 span (report 363a4d96
   read `x=8..791`, which fits both).
3. Press any head-unit hardware or steering-wheel buttons the report listed in
   `input_binding_keycodes`; each press taps a button in the scene and shows the keycode.
4. Keep projecting for **five minutes**. During it:
   - background the app (Home) for at least a minute, then reopen it,
   - turn the phone screen off for at least a minute, then on,
   and record whether the projection continued (spec §11.3 background/screen-off test).
5. Tap **Stop**, export the report, and note the counters:
   `input_down`, `input_up`, `input_move`, `input_key_events`, `input_rejected`,
   `input_touch_space`, `input_raw_range`, `scene_calibration`, `scene_taps`, `scene_last_touch`,
   `scene_last_touch_raw`, and `projecting_ms`.
6. **Five reconnect cycles**: unplug, wait a few seconds, plug back in, Connect, and let
   the scene come up each time, without restarting the app or the phone. Export a report
   per cycle and check each has a new `generation`, a new `session_uuid`, a new
   `scene_token`, `io_descriptor=closed`, and no `NATIVE_START_FAILED (BUSY or STALE_IO)`.

G5 passes only with: the operator observation that head-unit input changed the projected
scene, a five-minute projection run, and five clean reconnections, each with its report.

## 5. Interruption tests (spec §11.3)

| Test | Steps | Pass evidence |
| --- | --- | --- |
| Stop from every state | Stop during SETUP, during PROJECTING, and from the notification | `final_category=CANCELLED`; when TLS was up, `final_detail` says the shutdown request was sent, `video_stop_indication_sent=true`, and the head unit returns to its own interface |
| Detach while projecting | Unplug during PROJECTING | `IO_ERROR`/`IO_EOF` or `CANCELLED`, `io_descriptor=closed`, app back at IDLE; next attempt works |
| Detach during TLS | As in the G1–G3 procedure §7 | Unchanged from that procedure |
| Head unit ends the session | Use the head unit's own control to leave projection, or switch it to another source | `final_category=PEER_SHUTDOWN` or a focus change to NATIVE recorded in `video_focus_losses`; the app returns to IDLE without a reconnect loop |
| Ignition or receiver interruption | Power-cycle the receiver with the vehicle's normal controls, stationary | Clean stop and a successful next connection, or a precisely recorded firmware limitation |
| Focus handover | While projecting, use the head unit to show its own screen, then return to projection | `video_focus_losses` and `video_focus_regains` both ≥ 1, `video_configs_repeated` ≥ 1, and the scene resumes without artefacts |

## 6. Narrowing the sensor set

G4 and G5 run with **Vehicle sensors** on `all`, so the question is no longer whether to
turn sensors on but whether they are implicated in a failure. If the head unit will not
give projected focus, shows the projection but refuses input, or behaves differently from
a G1–G3 attempt, repeat once with the set narrowed to `restriction` — the DRIVING_STATUS
and NIGHT_DATA pair, which is what P4 subscribed to — and, if that still fails, once with
`none`. The reports show `sensor_requests=13:0,10:0` (type and status) and
`sensor_driving_status`/`sensor_night`; the three together say whether the sensor channel
changed the head unit's behavior at all, in either direction.

### The P5.2 harvest

Any G4 or G5 attempt that projects for a minute is the harvest, because `all` is what
the screen starts on; no separate run is needed unless a gate was narrowed under §6. The
report carries one `sensor_value_NNN=` line per type, and the two lines that are the
finding:

```
sensor_advertised=1,3,6,7,8,10,13,21,24
sensor_populated=…    sensor_silent=…
```

`sensor_populated` is which of Ford's nine actually carry values; `sensor_silent` is
which were accepted and then said nothing. Types the head unit refused appear in
`sensor_requests` with a negative status and in neither list, because a refusal says
nothing about whether the vehicle has that sensor. This is what P5.5 needs before its
position source is anything but an assumption.

Leave **Also record the vehicle's latitude and longitude** unchecked. Position identifies
the vehicle, so spec §10 keeps it out of a shared report; the run still records whether a
fix arrived, its accuracy, altitude, speed, bearing and satellite counts, which is what
answers the question. Check it only for a deliberately private diagnostic run, and then
do not export that report. Either way the report header names which case the run was
(`sensor_location=true|false`).

All vehicle tests are stationary. Do not fake parked status, disable head-unit
restrictions, or repeatedly seize the display while the head unit shows its own interface.

## 6a. G7 — audio out over USB

A separate attempt, and only once the projection itself is working. Tick **Audio out over
USB**; leave everything else as for a normal projection run. The app sends a two-tone test
signal it generates itself — 440 Hz in the left channel, 880 Hz in the right — so a pass is
audible as a steady chord from both front speakers, and a swapped pair is audible too.

**G7 was met on 19 September** (0.4.1-lab, report 7f869585): a steady two-tone chord from
the vehicle speakers, 958 buffers over 40.8 s, `audio_underflows=0`, focus released
cleanly. The history below is kept because it is how the two source defects were found,
and because a future head unit may behave like the early attempts did.

**The first attempt (19 September, 0.3.7-lab, reports f9ce185b and dc2c60ee) did not pass.**
Ford opened the channel and accepted the AV setup, then refused audio focus, and nothing was
audible. The lines that say so:

```
audio_channel=4 audio_open=true
audio_setup_status=2 (OK) audio_setup_window=40
audio_focus_sequence=…            ← the whole exchange, in order
audio_started_ms=-                ← nothing ever started
audio_buffers_sent=0
```

So the thing to watch on the next attempt is `audio_focus_sequence`, which is new in
0.4.0-lab and did not exist for the first one. Each entry is `elapsed_ms:state:granted`;
state 1 is GAIN and 3 is LOSS. A pass reads `…:1:true` and `audio_started_ms` is set.

**Second attempt (19 September, 0.4.0-lab, reports 5cb646ec and 9eb3a396): Ford answered
`GAIN` and this source refused it.** It was gating on the response's optional second field,
which Ford sends false whether it grants or refuses. Fixed in 0.4.1-lab, so the third
attempt is the first one where a grant can actually start the audio. The head unit's answer
is not a constant — it said `LOSS` in the morning and `GAIN` half an hour later — so treat
`audio_focus_sequence` as the record and do not read a policy into one run.

**While projecting, look for an audio source selector on the head unit's own interface and
select the projection.** (On the second attempt the projection was *not* offered as a
selectable source, and the grant arrived anyway, so this may turn out to be unnecessary.) This is the cheapest test of the leading explanation: that Ford
grants media focus only to the source the driver has chosen, and was on radio or Bluetooth
throughout the first attempt. A read of the Android Auto 17.6 dex supports going this way
first: Google's client sends an identical request and waits for the grant before starting
the stream just as this source does, so neither the message nor the order explains the
refusal — the remaining difference is that Android Auto only ever asks on behalf of an app
that is already playing. 0.4.0-lab handles a grant that arrives late — the earlier
build could not, which is the other reason the first attempt is not conclusive. If a grant
arrives, the tone starts within a second and `audio_focus_sequence` shows the denial
followed by the grant.

Record either way, and record what the head unit's audio source was set to. A refusal is a
recorded outcome rather than a failed gate (spec §11.1), but it leaves G7 **unmet**: it
says wired audio is unavailable on this head unit to an ordinary source, not that it works.
Nothing about audio can cost the picture — if the projection stops when audio is ticked,
that is a defect and the report is the evidence.

This section covers audio **this app owns**. Whether the vehicle will keep playing the
phone's *other* audio over Bluetooth A2DP while a USB session is projecting is a separate,
untested question with its own procedure:
[A2DP coexistence](a2dp-coexistence-procedure.md).

## 7. What to keep

Per attempt: the exported report (sanitized; the app redacts head-unit names, serials,
vehicle id, Bluetooth address, and certificate common names), the operator notes, and for
G4/G5 the photo or video of the display with the token and counter visible. Keep
`scene_token` next to each photo so a report and a picture can be matched later.
