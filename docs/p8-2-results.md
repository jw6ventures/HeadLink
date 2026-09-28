# P8.2 — turn prompts and the trip-panel voice toggle: implementation record — 24 September 2026

The second slice of the [P8 plan](p8-plan.md) (§11): speed into `Guide.onFix` (§5.1),
guidance through `VoicePrompts` to `Speaker` (§5), the voice settings saved in full for
P8.3's screens (§7), the voice toggle on the trip panel beside End route (§6), the report
fields (§9), and a `voice` lab script (§10). `VoicePrompts` and `SpokenText`, the timing
and wording, were built and host-tested before this slice by another agent and are used
unchanged. P8.1's `Speaker` gains only the saved toggle at `bind`.

Status: **implemented, host-tested, and lab-verified on the emulator** with the lab
player's silent stand-in engine and the Bluetooth override. **Nothing has been heard, and
nothing has run on the Pixel or the truck.** The §5.2 defaults are hard-wired until P8.3's
screens exist, as §11 allows: the stored settings are read, and nothing yet writes them
but the toggle.

What the lab run showed (§2.2):

- Over the P6.5 drive the app spoke **12 prompts**: 1 early, 4 prepare, 4 at the turn, 2
  "Rerouting" (one per re-route) and 1 "You have arrived". Two more were held back as
  `toggle_off` while the toggle was off. Focus was asked for 12 times and given back 12.
- Each toggle press changed about a tenth of the toggle's picture, and it was saved at
  once: `voice_guidance=false`, then `true`. Turning it back on spoke the current step's
  prepare prompt 6 ms after the press, as confirmation.

---

## 1. What was built

### 1.1 Speed into `Guide.onFix` (plan §5.1)

- **`Guide.onFix(context, lat, lon, source, speedMps)`.** The speed is kept for prompt
  timing and never reported. A null leaves `VoicePrompts` on the step's own pace.
- **Where it comes from**, in the plan's order:
  1. the head unit's `car_speed`;
  2. its `gps_speed`;
  3. the phone's `Location.speed`;
  4. null.
- **At the scale the drive supports.** `Dashboard.guidanceSpeed()` reads the vehicle
  reading *after* `ScaleEvidence` has re-read it (P6.1). A single message cannot tell
  0.05 m/s at 1e3 from 50 m/s at 1. Before a dashboard exists (the sensor channel opens
  before the video), `VehicleReading.speed()` reads the one message alone.
- **Precedence differs slightly from the header's.** The header takes `car_speed` when
  it is present, even if unvalidated, and then gives up. Guidance falls through to
  `gps_speed`, as §5.1 says. The header is unchanged.
- **Phone fixes** (no vehicle position) get the same function, which falls to the phone's
  own speed.
- **No odometer.** No head unit this project has met sends `ODOMETER`; Ford's nine
  sensors do not include it. `VoicePrompts` keeps its dead reckoning from speed, which
  its re-route matching is written for.

### 1.2 Guide → `VoicePrompts` → `Speaker` (plan §5)

As the core's author asked:

- **`Guide.publish`**:
  - last branch: `voice.onStep(step, speed, elapsedRealtime)`;
  - while re-routing: `onRerouting()` on every publish, which speaks once per re-route;
  - on `ARRIVED`: `onArrived()`.
- **`Guide.clear`** calls `reset()`. A new route through `onRoute` publishes its first
  step at once, so its first prompt can follow "Rerouting" under the same focus grant.
- **Where prompts go.** A non-null result goes to `Speaker.guide(GuidePrompt(text,
  stage.label))`. The stage labels are `early`, `prepare`, `at_turn`, `reroute` and
  `arrival`.
- **Settings.** `Guide.voiceSettings(saved.voice, speedUnit)` runs when a dashboard
  projection starts. The unit is the attempt's `SpeedUnit`, the one the header uses.

**Design choice: the toggle is `Speaker`'s gate, not `VoicePrompts`'.**

- `Guide` always gives `VoicePrompts` its settings with `enabled = true`.
- The saved on/off goes to `SpeechQueue.guidanceOn`. `Speaker.bind` now sets it from the
  saved settings, and each press sets it again.
- So a prompt due while the toggle is off still reaches `Speaker`, which counts it
  `skipped_toggle_off`. Plan §9 asks for that count, and it could not exist if
  `VoicePrompts` swallowed the prompt.
- Stage progress is tracked the same either way, so turning the toggle on releases no
  backlog. Only `confirm()` speaks.
- The trade-off is the once-per-route flags: a re-route or arrival while muted uses up its
  announcement, and it is counted `toggle_off`. That is what "muted" means.

**Speech only while a dashboard projects.** `Speaker` is bound for the projection's
lifetime (P8.1). A fix outside one reaches `Speaker` and is counted `no_engine` in a
queue whose report was already written. Guidance on the cluster alone, with no
projection, speaks nothing.

### 1.3 The saved voice settings (plan §6, §7)

`SavedSettings.voice: VoiceSettings`. It is stored under nine keys, so 42 are stored, up
from 33. Every default is the model's own:

| Key | Type | Default |
| --- | --- | --- |
| `voice_guidance` | boolean | on (the toggle; decision 6) |
| `voice_early`, `voice_prepare`, `voice_at_turn` | boolean | on |
| `voice_timing` | `earlier` / `normal` / `later` | `normal` |
| `voice_detail` | `brief` / `full` | `full` |
| `voice_street_names` | boolean | on |
| `voice_announce_reroute`, `voice_announce_arrival` | boolean | on |

- Timing and detail are stored by label, never by position. An unknown label, or a key
  of the wrong type, reads as the default.
- **`SavedSettings.withVoiceGuidance(on)`** changes that one field. The toggle writes
  through `AppSettings.update` on the main thread at every press, as day/night does.
- **P8.3** needs only to show and write these keys. `Speaker` and `Guide` read them when
  a projection starts, as the other session settings are read.

### 1.4 The trip-panel toggle (plan §6)

- **Layout** (`DashboardLayout.voiceToggle`, `endRoute`, `hitVoiceToggle`):
  - The toggle sits at the right end of End route's row, at End route's height, a margin
    clear of it.
  - It is a third of the row wide. Plan §6's square does not fit (deviation, §4.1).

  | Display | End route | Toggle | "End route" text |
  | --- | --- | --- | --- |
  | 1920x1080 | 264x205 | 131x205 | 45 px (was 57) |
  | Ford's 726x480 | 101x95 | 50x95 | 17 px (was 27) |
  | 800x480 | 111x93 | 55x93 | 19 px (was 26) |

  - End route's text is now the smaller of 0.28 of its height and a fifth of its width
    less the margins.
  - The receiver's `DashboardPane` mirrors both boxes.
- **Drawing** (`DashboardDrawing.drawVoiceToggle`):
  - A speaker drawn from strokes on the button's plate: a box and a cone.
  - **On:** accent colour, with two sound waves.
  - **Off:** dim, with a slash in the text colour.
  - **Pressed:** the plate lights in the accent, as End route's does.
  - **Speech impossible:** a small triangle with "!" in the warning colour at its top
    right, whichever way it is set.
- **Warning mark.**
  - `Dashboard.speechAvailable(Speaker.canSpeak())` updates it once a second while
    projecting, and at once after a press.
  - It starts on until the first update, because speech is not assumed.
  - `PhoneStatus`'s 5 s poll was too slow for an engine that readies about a second
    into the session.
- **Touch.** It uses End route's rule: press and release on the toggle. A slide either
  way between the toggle and End route is neither, and so is a cancel.
  - `Dashboard.takeVoiceToggle(): Boolean?` hands over the new state once.
  - Several presses between reads hand over the state they left, and all are counted.
  - `ProjectionService` reads it on the touch event, beside `takeEndRoute`, then:
    1. notes "dashboard voice guidance on/off";
    2. saves it;
    3. calls `Guide.voiceToggled(on)`, which calls `Speaker.guidanceOn(on)`. Off stops a
       prompt at once. On speaks `confirm()` for the current step, if there is one.
- **Saved.** `DashboardChoices.voiceGuidance` starts the dashboard as the setting was
  left, and `Speaker.bind` starts the gate the same way. A route started muted stays
  muted.

### 1.5 Report (plan §9)

P8.1's `voice_prompts_` line already counted requests, starts, per-stage spoken counts,
each end and each skip (`toggle_off`, `in_call`, `no_engine`, `no_bluetooth`,
`no_focus`). Reroute and arrival announcements are its `spoken_reroute` and
`spoken_arrival`. Added:

- `voice_prompts_toggle=on|off`: the gate's state when the report was written.
- Every `PromptStage`'s `voice_prompts_spoken_<stage>`, at 0 when none was spoken, so a
  drive's report says "no early prompt" rather than leaving the key out.
- `dashboard_voice_toggle_presses=N dashboard_voice_guidance=on|off`, beside
  `dashboard_end_route_presses`.

The event log gains "dashboard voice guidance on|off", beside P8.1's "voice prompt
(stage)". There is still no text, street, destination or speed.

## 2. Verification

Every command below ran under `flock ~/.cache/openauto-android/p8-build.lock`. Logs are
in `~/.cache/openauto-android/p8-2-*.log`.

### 2.1 Build and host tests

- **`tools/android-build.sh`: exit 0** (`p8-2-build-final.log`, after the last code
  change). It covers lint, both APKs, `verify-apk` and every host test.
- **`tools/check.sh`: exit 0** (`p8-2-check.log`).
- **`tools/lab-receiver.sh build`: exit 0** (`p8-2-receiver-build.log`).
- **`DashboardTripTest`: 2,332 checks**, up from 466. At 1080p, 726x480 and 800x480,
  **each with the map on the left and on the right**:
  - The toggle is in the tile, at End route's height and on its row, at the row's right
    end.
  - It is no wider than tall, at least 40 px wide, and End route is at least twice its
    width.
  - They never overlap, and a margin separates them.
  - Every pixel column of the row hits at most one of the two.
  - The toggle's corners hit; outside it misses. It clears the later-maneuver cells, the
    summary and the flex tile.
  - "End route" fits its button and is at least 16 px.

  Its press, at every display:
  - No route, no toggle.
  - It lights while pressed and does nothing until the release, which turns it off in
    the next frame and hands over `false` once. End route is untouched.
  - A second press hands over `true`.
  - A slide toggle→End route or End route→toggle is neither, and neither is a cancel.
  - Three presses hand over the state they left. End route still works beside it.
  - `presses=5 … guidance=off` is counted.
  - With the route gone, the toggle goes too.

  Its start and warning:
  - A dashboard started with `voiceGuidance=false` draws it off and reports it so.
  - The warning shows until `speechAvailable(true)`, and shows again with the toggle off.
  - An unchanged panel compares equal, so the chrome is not repainted.
- **`AppSettingsTest`: 2,628 checks.**
  - The defaults, and that they are stored on a first launch.
  - All nine keys read, stored and round-tripped, with timing and detail stored by name.
  - `withVoiceGuidance(false)` stores off and keeps the timing, and reads back off.
  - An unknown timing or detail, or a wrong type, reads as the default.
  - 42 fields are stored.
- **`SpeechQueueTest`: 128 checks.** It adds `voice_prompts_toggle=off`, then `on`, and
  every stage listed at 0 before any is spoken.
- **Unchanged and green:** `VoicePromptsTest` 246, `DashboardTest` 587,
  `DashboardFlexTest` 1386, `GuidanceTest` 136 and the rest.

### 2.2 Emulator lab

**The new `voice` script** (`tools/emulator-lab.sh voice`, and `--script voice` in the
receiver):

```
LAB_ROUTING=~/.cache/openauto-android/rd5 LAB_MAP=~/.cache/oaa-vtm/maps/massachusetts.map \
  LAB_VIDEO_CONFIG=1920x1080 tools/emulator-lab.sh voice
```

- **The drive.** It is the P6.5 guidance drive (`lexington.csv`, 4,645 m, a missed turn,
  two re-routes, arrival), with every guidance check, app side and receiver side.
- **Setup.** Speech is set up as the `speech` script sets it up: the stand-in engine by
  default (`LAB_SPEECH_ENGINE`), `lab_speech_bluetooth`, and `speech_media=pause` and
  `voice_guidance=true` seeded.
- **Receiver.**
  1. Waits for `ACTIVE` and 3 s for the panel to settle, then records the toggle's
     picture and pulls away.
  2. Presses the toggle 15 s later. The picture must change by at least 0.03 of the box.
  3. Presses it again 20 s after that, before the drive leaves the route. It must change
     again, and differ from the first picture by at most 0.02.
  4. Drives on to arrival.
  - It prints each state's decoded frame number. `frames/NNN.png` is frame / 25 + 1.
- **App side.**
  - After each press is noted, the saved `settings.xml` must say `voice_guidance`
    false, then true.
  - The report must say:
    - two presses, the toggle on, `speech_gate=lab` and `speech_ready=true`;
    - `skipped_no_engine=0` and `skipped_no_bluetooth=0`;
    - exactly one arrival announcement;
    - at least 2 prepare and 2 at-the-turn prompts;
    - between 1 and `guidance_reroutes` re-route announcements;
    - no more prompts started than requested;
    - focus requests equal to abandons, and none held;
    - notes for each stage.
  - **Privacy.** No `Guidemarker`, destination coordinates, "Turn ", "turn left/right",
    " onto ", " feet", " mile", "Rerouting", "You have arrived", "Street", "Road" or
    "Avenue" anywhere in the report.
  - **Bounds, not expected counts.** The counts are what any pass of this drive must
    reach. They are logged for comparison with the route.

| Run | Result |
| --- | --- |
| `voice`, `emulator-lab/20260924T192941Z` | **pass**. Receiver 27/27. Toggle off changed 0.103, on 0.103, back to the first picture 0.000 |
| regression `endroute`, `…/20260924T193532Z` | **pass**, §2.3 |
| regression `guidance`, `…/20260924T193634Z` | **pass**, §2.3 |

The voice run's own account (`…/20260924T192941Z`):

```
+1486ms speech engine ready
+5565ms route computed in 3937 ms on aap-route: 4061 m, 516 s, 193 points, 6 maneuvers
+13883ms voice prompt (prepare)          the first turn, 590 ft from the start
+18880ms voice prompt (at_turn)
+23713ms dashboard voice guidance off
+43983ms dashboard voice guidance on
+43989ms voice prompt (prepare)          the confirmation
+47882ms voice prompt (at_turn)
+54483ms voice prompt (at_turn)
+64891ms left the route by 115 m; re-routing
+64921ms voice prompt (reroute)
+71294ms left the route by 112 m; re-routing
+71316ms voice prompt (reroute)
+214493ms voice prompt (early)
+249487ms voice prompt (prepare)
+260483ms voice prompt (at_turn)
+266708ms voice prompt (prepare)         the final approach
+270114ms arrived at the destination
+270141ms voice prompt (arrival)
dashboard_voice_toggle_presses=2 dashboard_voice_guidance=on
voice_prompts_requests=14 voice_prompts_started=12 voice_prompts_toggle=on voice_prompts_spoken_early=1
  voice_prompts_spoken_prepare=4 voice_prompts_spoken_at_turn=4 voice_prompts_spoken_reroute=2
  voice_prompts_spoken_arrival=1 voice_prompts_completed=12 … voice_prompts_skipped_toggle_off=2 …
speech_focus_requests=12 speech_focus_denied=0 speech_focus_abandons=12 speech_focus_held=false speech_lead_ins=10
receiver_guidance=states=1,3,1,3,1,2,0 maneuvers=6,8,5,7,5,36,39 countdown_falls=45 …
```

- **Two re-routes, two announcements.** This is the P6.5 drive's known pair of re-routes
  6 s apart (p6-5-results §4.2). Each is a separate re-route, so each is announced once.
- **Held back while off.** The two `toggle_off` skips are the prompts due in the 20 s the
  toggle was off.
- **Every prompt completed.** None cut another off, so there were no `interrupted`
  ends. The two at-the-turn prompts 6.6 s apart fit the route's second and third
  maneuvers (8 and 5 on the cluster), which are close together. The run does not record
  which step each prompt was for.
- **The long silence** from 71 s to 214 s is the re-route's first step, over 2 km long,
  where Early fires at 900 m at the drive's 18 m/s cruise.
- **Lead-ins.** Ten of the twelve prompts had one; the other two came within 3 s of the
  utterance before them.

### 2.3 Regressions, same code

The trip panel and `Guide` changed, so the scripts that use them ran again:

| Run | Result |
| --- | --- |
| `LAB_VIDEO_CONFIG=1920x1080 endroute`, `…/20260924T193532Z` | **pass**, 19/19. End route was pressed at its new, narrower centre twice, and the cluster went `1,2,1,2`. The tile changed 0.429 and the card 0.839. Two hand-backs |
| `LAB_VIDEO_CONFIG=1920x1080 guidance`, `…/20260924T193634Z` | **pass**, 23/23. States `1,3,1,3,1,2,0`, maneuvers `6,8,5,7,5,36,39`, the same as P6.5's and P7.4a's runs. The countdown fell 44 times. With no engine on the image and no override, the 13 prompts guidance asked for were all `skipped_no_engine`: the degraded path. The voice run asked for 14, its 13 plus the confirmation |

### 2.4 Frames looked at

| File | What it shows |
| --- | --- |
| `20260924T192941Z/voice-toggle-on-off-on.png` | Crops of `frames/009.png`, `028.png` and `052.png`, night, 1080p: the trip panel with **End route** and the toggle **on** (accent speaker, two waves), **off** (dim speaker, slashed), and **on again**. There is no warning mark, as expected with the stand-in engine and the override. |

## 3. Findings

- **The plan's square toggle does not fit the tile** (§4.1).
- **A route whose first turn is close gets Prepare at once.** The drive starts 590 ft
  (180 m) from its first turn, inside Prepare, so there was no Early, as §5.2's "a stage
  first seen late collapses" intends. A route started in a car park will usually open
  with a prompt.
- **The header's speed and guidance's speed can disagree** when `car_speed` is present
  but out of range (§1.1). Ford's `car_speed` has never been decoded on the truck, so
  whether the difference matters is unknown.

## 4. Deviations (recorded in plan §15)

1. **§6's square.** The toggle is End route's height and a third of the row wide, and
   "End route" is sized to its width too. The measurements are in §1.4. A square would
   leave End route narrower than the toggle.
2. **The toggle gates in `Speaker`, not in `VoicePrompts`** (§1.2), so `toggle_off` can be
   counted.
3. **§9's names:** `dashboard_voice_toggle_presses`, `dashboard_voice_guidance` and
   `voice_prompts_toggle`, and every stage listed at 0.
4. **§5.1's odometer:** none, so dead reckoning.

## 5. Not verified: needs the Pixel or the truck

- **Anything heard.** No prompt has reached a speaker, and the wording has never been
  listened to.
- **The timing on a real road.** §5.2's numbers are a starting point, and the lab's pace
  says nothing about them. The lab is a debuggable build and ran about 30x slower on
  CPU, and it gave no timing evidence.
- **Ford's speed.**
  - Which of `car_speed` and `gps_speed` SYNC 4 fills, at what scale, and whether
    `ScaleEvidence` settles it before the first prompt.
  - The report does not carry the speed source, by design (§9).
- **The toggle from the driver's seat.**
  - Whether 131x205 is easy to hit while driving.
  - Whether "End route" at 45 px still reads.
  - The warning mark's triangle in daylight.
- **The warning mark in a picture.** It is host-tested as state; its drawing has not been
  seen. In the lab the engine and the override make speech possible, so the mark never
  shows.
- **Notification read-aloud cut off by a prompt.** It is host-tested in P8.1 and has not
  been seen in a drive.
- **P8.1's items**: GrapheneOS's engine, the A2DP gate, focus with real players, the
  lead-in length.

## 6. Proposed gate: G12 (plan §12)

| Gate | Required evidence | Interpretation |
| --- | --- | --- |
| **G12** voice guidance drive | On a real route in the truck, with Bluetooth audio as SYNC's selected source (§2): prompts are heard through the truck's speakers at each stage; the trip panel's toggle silences them and brings them back from the driver's seat (off stops a prompt mid-sentence; on speaks one at once); a notification tapped on the head unit is read aloud and a turn prompt cuts it off; the phone's own speaker stays silent throughout. Evidence: operator observation plus the session report's `voice_prompts_spoken_*`, `voice_prompts_skipped_*`, `dashboard_voice_toggle_presses` and `notifications_read_interrupted_by_guidance`. | Spoken guidance works on the vehicle without the phone in hand. |

## 7. First drive in the truck: checklist

Before leaving:

1. Install the release build. Home says "Speech: <engine>" with no network warning.
2. Pair the phone to SYNC for audio, and select **Bluetooth audio** as SYNC's source.
3. Settings → Notifications on the dashboard on, and Read aloud when tapped on, if the
   notification part of G12 is to be done on this drive.
4. Share a destination from OsmAnd about 5–10 km away, with a highway stretch and at
   least one turn in town. On the trip panel the speaker glyph is lit, with **no
   warning triangle**. A triangle means no engine or no A2DP; stop and check.

On the drive (a passenger notes what is heard):

5. Each maneuver: note whether early, prepare and at-the-turn were heard, roughly how
   far out, and whether the first syllable was clipped (§4's 300 ms lead-in).
6. Once, in town: press the speaker. It goes slashed, and a prompt being spoken stops at
   once. Drive past one turn silent. Press again: a prompt is spoken straight away.
7. Once: have someone send a message, tap it on the head unit, and let a turn prompt cut
   it off.
8. Miss a turn on purpose once: "Rerouting" is heard, once.
9. Arrive: "You have arrived".
10. Throughout, the phone's own speaker is silent. With music playing, note whether it
    paused (Pause media) and resumed after each prompt.

After:

11. Pull the report. Note `voice_prompts_spoken_*` against what was heard,
    `voice_prompts_skipped_*` (expected all 0 but `toggle_off`),
    `dashboard_voice_toggle_presses=2`, `speech_focus_requests` equal to `abandons`, and
    `speech_gate=bluetooth` (never `lab`).
12. Record what the §5.2 numbers felt like: too early, too late, too chatty. They go into
    this document, and into the plan's §5.2 if changed.

## 8. Amendments this needs — proposed, not applied

1. **spec §11.1** — **G12** as §6 above. Plan §12's wording, with the evidence keys
   named.
2. **spec §10** (reports) — the new keys: `dashboard_voice_toggle_presses`,
   `dashboard_voice_guidance` and `voice_prompts_toggle`, and every
   `voice_prompts_spoken_<stage>` always listed. Still never the text, street,
   destination or speed.
3. **P8 plan §6** — "a square at its end" becomes "End route's height, a third of the
   row" (§4.1). Decision 1's wording, "a square", would need James's say-so. The
   alternative is to keep the square and accept End route narrower than the toggle.
4. **P8 plan §5.1** — the speed's order is taken at the scale the drive supports. No
   odometer is available.
5. **P8 plan §7** — the stored keys of §1.3, for P8.3's screens.
6. **plan.md P8 row** — P8.2: "implemented, host-tested, lab-verified with a silent
   stand-in engine; nothing heard; G12 not run".
