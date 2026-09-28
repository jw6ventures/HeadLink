# P8.3 — Voice guidance settings and Play a sample: implementation record — 24 September 2026

The last slice of the [P8 plan](p8-plan.md) (§11): the phone's **Maps & routing → Voice
guidance** settings (§7), with changes taken by a projection under way, and **Play a
sample**, which speaks "In 500 feet, turn right onto Main Street" through a turn prompt's
path, with or without a projection. P8.2 already saved all nine keys and read them at
projection start; this slice shows them, writes them, and hands each change to the
running prompts.

Status: **implemented, host-tested, and lab-verified on the emulator** with the lab
player's silent stand-in engine, with and without the Bluetooth override. **Nothing has
been heard, and nothing has run on the Pixel or the truck.**

What the lab run showed (§2.2):

- The phone's switch and the trip panel's toggle are one value both ways. Flipped on the
  phone mid-projection, it was saved and reached the projection within a second each way.
  Pressed on the head unit, the phone's switch followed while the screen was up.
- Play a sample during a projection was spoken through the session's engine and counted
  as `voice_samples_started=1 voice_samples_completed=1`, with every `voice_prompts_*`
  count left at 0.
- With no projection, the sample bound the engine, handed it the 40 characters and
  released it: the stand-in service was gone from `dumpsys` afterwards. With the lab
  Bluetooth override withdrawn, the phone said "Not spoken: the phone has no Bluetooth
  audio connection".
- The trip panel's warning mark was seen for the first time, in a guidance run with no
  engine (§2.4).

---

## 1. What was built

### 1.1 The screen (plan §7)

A **Voice guidance** card on the Maps & routing tab, after Drop folder and before Remove.
P7.7's phone UI has no sub-screens: every tab is a column of cards, so the "screen" is a
card, built from `PhoneViews` like its neighbours.

| Row | Control | Saved as |
| --- | --- | --- |
| **Speak turn prompts** | switch, ⓘ | `voice_guidance` |
| **Prompts**: Early, Prepare, At the turn | label and ⓘ, three checkboxes on a row under it | `voice_early`, `voice_prepare`, `voice_at_turn` |
| **Timing** | Earlier / Normal / Later | `voice_timing` |
| **Detail** | Brief / Full | `voice_detail` |
| **Say street names** | switch, ⓘ | `voice_street_names` |
| **Announce rerouting** | switch, ⓘ | `voice_announce_reroute` |
| **Announce arrival** | switch, ⓘ | `voice_announce_arrival` |
| **Play a sample** | button, ⓘ, and a line under it for the outcome | — |

- **Wording.** The card is titled "Voice guidance", so the on/off switch is labelled
  **Speak turn prompts** rather than repeating the title. Its ⓘ says it is the same
  switch as the speaker button beside End route.
- **Strings** are all in `strings.xml`. The spinner choices come from resources too, not
  from the enums' stored labels. The engine-timeout message is a plural, as lint asks.
- **Accessibility** follows the existing rows: every ⓘ has "About …" as its content
  description, and every row is at least 48 dp. The outcome line is a polite live
  region, so TalkBack reads what happened to the sample.
- **Saving** uses P7.1's one-field-per-write rule through `AppSettings.update`, with
  listeners attached only after every control holds its saved value.

### 1.2 Changes reach a projection under way

Each voice control saves its field, then calls **`Guide.voiceChanged(saved.voice)`**:

- The prompts take the new settings at once (`VoicePrompts.settings`), in the unit the
  projection started with. `enabled` stays true inside `VoicePrompts`, as in P8.2, because
  the toggle is `Speaker`'s gate.
- **On/off** goes to `Speaker.guidanceOn` and to the dashboard, only when it differs from
  what `Speaker` already has. The dashboard gets it through a new `Guide.voiceListener`,
  which `ProjectionService` installs beside `turnListener`. The listener:
  1. notes "phone voice guidance on|off";
  2. sets `Dashboard.voiceGuidance(on)`. The toggle is drawn that way from the next frame
     and the final state is reported, but it is not a press, and `takeVoiceToggle()`
     hands nothing back;
  3. refreshes the warning mark.
- **No confirming prompt from the phone.** Turning guidance on from the head unit speaks
  the current step's prompt (plan §6). Turning it on from the phone does not: the phone
  has Play a sample for checking, and whoever holds the phone is often not the driver.

**The other way: a head-unit press shows on the phone.**

- `onResume` re-reads `voice_guidance`, as it does day/night.
- `renderSettings` also compares the switch with the saved value on every render. Each
  session note triggers a render, so the switch follows a press while the screen is up.
  Setting the switch stores the value it already has, and `Guide.voiceChanged` finds
  `Speaker` already told, so nothing is passed back and nothing extra is noted.
- For that to work, `ProjectionService` now notes "dashboard voice guidance on|off" after
  saving the press and telling `Guide`, not before. Noted first, the render would have
  found the old value saved.
- The event log changes because of this: turning guidance on now shows the confirming
  "voice prompt (prepare)" about 20 ms *before* "dashboard voice guidance on". In P8.2 it
  came after.

### 1.3 Play a sample (plan §7)

**The words.** `SpokenText.sample(unit, streetNames)`, built the way a Prepare prompt is:

| Settings | Spoken |
| --- | --- |
| mph, street names on | "In 500 feet, turn right onto Main Street" |
| street names off | "In 500 feet, turn right" |
| km/h | "In 150 metres, turn right onto Main Street" |

**The path.** `Speaker.sample(context, settings, text, done)`, main thread only:

1. **`SpeechQueue.sample`** applies the call rule (§14.1), the engine check and the
   truck-only Bluetooth gate, in `guide`'s order.
2. Then `Speaker.say` speaks it exactly as a prompt:
   - focus of the **While speaking** kind, with guidance's attributes
     (`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`);
   - the 300 ms lead-in after silence;
   - `QUEUE_FLUSH`.
3. It is `SpeechKind.GUIDANCE`, so it takes a prompt's place in the queue:
   - it cuts off a notification being read;
   - a notification tapped while it plays is dropped;
   - a turn prompt cuts it off.
4. `done` is told the outcome once, as a `SampleOutcome`.

**Design choice: the trip panel's toggle does not gate the sample.**

- Plan §7 says "exactly the path a prompt uses", and the toggle is part of that path
  since P8.2 made it `Speaker`'s gate.
- But the operator pressing Play a sample is asking whether the truck can hear the
  phone. If muted guidance silenced the sample, it would answer a different question and
  look like a fault.
- So the sample passes the toggle, and every other gate is the prompt's. When guidance
  is off, the phone adds under the outcome: "Turn prompts are switched off, so a drive
  stays silent until they are switched on."

**Design choice: samples are counted apart.** A sample during a projection is counted as
`voice_samples_*`, on a line of its own, and never in `voice_prompts_*`:

- A sample played mid-drive is not a prompt the drive asked for.
- A sample skipped for want of Bluetooth is not a prompt the driver missed.
- Otherwise a G12 report could show a stage or a skip that no route caused.

The line:

```
voice_samples_requests=N voice_samples_started=N voice_samples_completed=N
voice_samples_skipped_in_call=N voice_samples_skipped_no_engine=N
voice_samples_skipped_no_bluetooth=N voice_samples_skipped_no_focus=N
```

- **What stays shared.** Focus requests, abandons, denials and lead-ins are the audio
  system's and do include samples, so focus requests still equal abandons.
- **What a sample cuts off is still counted.** A notification a sample cuts off counts
  as `notifications_read_interrupted_by_guidance`, because it was.
- **The event log** says "voice sample spoken" when one starts, and "voice sample
  <outcome>" when one ends any other way. There are no words, as for prompts.

**Design choice: an engine bound for a sample, and released after it.** With no
projection, nothing is bound (`Speaker` is bound for a dashboard projection only), so:

- **Bound for the sample.** `Speaker.sample` binds the default engine with the saved
  settings (While speaking among them) and marks it the sample's.
  - **No engine installed:** it says so at once, without binding.
- **Waits for init.** P8.1 drops a prompt that arrives before init completes (§3). A
  sample waits instead, because the press is the operator asking now.
  - The wait is up to **10 s** (`SpeechQueue.SAMPLE_INIT_TIMEOUT_MS`). The stand-in
    readies in about 1 s; a real engine loading a voice for the first time may take
    longer.
  - A failed init is told as `no_engine`, an engine without English as `no_english`, and
    the timeout as `engine_timeout`.
  - A sample pressed during a projection whose engine is still starting waits the same
    way.
- **Released when it is over.** The engine goes once the sample ends in any way:
  completed, skipped at the gate, refused focus, errored, or timed out.
  - Focus is given back at once rather than after the one-second linger, since nothing
    follows a sample.
- **Speaks only the sample.** While a sample holds the engine, `Speaker.guide` and
  `Speaker.read` return at once. So guidance with no projection, which P8.2 left silent,
  stays silent even if a fix arrives during the sample.
- **A projection starting mid-sample** calls `Speaker.bind`, which releases the sample's
  engine first. The sample is told `stopped`, and the session binds afresh, so its
  counts start at zero with none of the sample in them.
- **A sample outside a session reaches no report.** There is no session report for it
  to go into. Its notes go to the event log that Diagnostics' live report shows.
- **One at a time.** The button is disabled until the outcome arrives, and
  `Speaker.sample` refuses a second while one is pending.

**What the phone says** under the button:

| Outcome | Text |
| --- | --- |
| `spoken` | "Spoken over Bluetooth audio. If the truck was silent, check that Bluetooth audio is its selected source." (With the lab override: "Spoken (lab Bluetooth override: no Bluetooth device was checked).") |
| `no_engine` | "Not spoken: no text-to-speech engine is installed, or it failed to start. …" |
| `no_english` | "Not spoken: the speech engine has no English voice. …" |
| `engine_timeout` | "Not spoken: the speech engine did not start within 10 seconds. Try again." |
| `no_bluetooth` | "Not spoken: the phone has no Bluetooth audio connection. Prompts are spoken only over Bluetooth, never on the phone's own speaker." |
| `in_call` | "Not spoken: a call is on, and prompts are held back during calls." |
| `no_focus` | "Not spoken: Android did not give this app the audio." |
| `interrupted`, `bluetooth_lost`, `focus_lost`, `stopped`, `error` | Cut off by a turn prompt; Bluetooth disconnected; another app took the audio; stopped before it finished; engine error. |

`spoken` means the engine finished it over an A2DP output. As §2 of the plan accepts,
the phone cannot tell whether SYNC played it, so the text says what to check.

### 1.4 Report and privacy (plan §9)

- New keys: the `voice_samples_*` line (§1.3).
- New event notes: "phone voice guidance on|off", "voice sample spoken" and "voice
  sample <outcome>".
- Never the sample's words: the speech lab run checks that "Main Street", "500 feet" and
  "turn right" appear nowhere in the report.

## 2. Verification

Every command below ran under `flock ~/.cache/openauto-android/p8-build.lock`. Logs are
in `~/.cache/openauto-android/p8-3-*.log`.

### 2.1 Build and host tests

- **`tools/android-build.sh`: exit 0** (`p8-3-build-final.log`, after the last change).
  It covers lint, both APKs, `verify-apk` and every host test.
- **`tools/check.sh`: exit 0** (`p8-3-check.log`).
- **`SpeechQueueTest`: 163 checks**, up from 128. `aSampleTakesAPromptsPathAndIsCountedApart`:
  - The Bluetooth gate, the engine and the call rule hold for a sample, and each skip is
    counted as the sample's and never as a prompt's.
  - With the toggle off, a sample still speaks, as guidance with no stage and asking for
    focus. It cuts off a read, and a tap while it plays is dropped.
  - Its completion is told through `sampleEnded`, and counted as
    `voice_samples_started=1 voice_samples_completed=1`, with no prompt count, no stage
    and no stray key. The grant is given back after it.
  - A prompt cuts a second sample off, which is told `interrupted_by_guidance`. The cut
    sample is not counted as a prompt interrupted.
  - Refused focus is counted as the sample's `no_focus`.
  - `SampleOutcome.of` maps ends and skips as §1.3 says.
  - The report is now four lines.
- **`VoicePromptsTest`: 249 checks**, up from 246. `theSample`: the plan's words exactly,
  with street names off, and in metric.
- **`DashboardTripTest`: 2,337 checks**, up from 2,332. `voiceGuidance(on)` from the
  phone:
  - it lights and slashes the toggle;
  - it hands nothing back through `takeVoiceToggle`;
  - it is not counted as a press, and the final state is reported;
  - a press after it flips from what the phone set.
- **Unchanged and green:** `AppSettingsTest` 2,628, `DashboardTest` 587,
  `DashboardFlexTest` 1,386, `GuidanceTest` 136 and the rest.

### 2.2 Emulator lab

The uiautomator helpers that P7.7's `LAB_SCREENS` defined inside its own block are now
defined once, for every script. Two are new:

- `scroll_to` scrolls until a node is in the middle of the screen. The dump lists nodes
  scrolled out of sight too, so being in it was not enough: the first run tapped a
  switch that was off the screen.
- `checked` reads a switch's state.

**The `speech` script gained P8.3's checks:**

```
LAB_SCREENS=1 tools/emulator-lab.sh speech
```

1. **Before the notifications:** the Voice guidance card is found, and Speak turn prompts
   is flipped off, then on. Each flip must be noted by the projection ("phone voice
   guidance off/on") within 20 s, and `voice_guidance` saved false, then true.
2. **13 s after the third tap:** Play a sample is pressed. By then the 432-character read
   is over, so the sample cuts nothing off.
   - It must be noted.
   - The phone must say "Spoken (lab Bluetooth override…".
   - The stand-in must be listed by `dumpsys activity services` while the projection
     holds it. This shows the release probe in step 4 means something.
3. **The report** must show:
   - `voice_prompts_requests=0 voice_prompts_started=0 voice_prompts_toggle=on`;
   - `voice_samples_requests=1 voice_samples_started=1 voice_samples_completed=1
     voice_samples_skipped_no_bluetooth=0`;
   - `notifications_read_interrupted_by_guidance=0`;
   - no "Main Street", "500 feet" or "turn right".
4. **After the session:** Play a sample again.
   - The stand-in must be handed one more 40-character utterance, counted in logcat.
   - The phone must say it was spoken.
   - The stand-in service must be gone from `dumpsys` within 10 s: released.
5. **Then without the override:** the lab intent withdraws it, and Play a sample must
   make the phone say "Not spoken: the phone has no Bluetooth audio connection".

With `LAB_SPEECH_ENGINE=none`, the script expects `voice_samples_skipped_no_engine=1`
and "no text-to-speech engine" instead. That variant was not run.

**The `voice` script** now also puts the phone's Voice guidance card on screen before
the drive. After each trip-panel press is saved, the phone's switch must show it within
10 s.

**`LAB_SCREENS`** now also photographs the Voice guidance card, top and bottom
(`screen-maps-voice.png`, `screen-maps-voice-lower.png`).

| Run | Result |
| --- | --- |
| `speech`, `emulator-lab/20260924T200158Z` | fail: the first `scroll_to` stopped on a switch scrolled out of sight (fixed as above) |
| `speech`, `…/20260924T200351Z` | fail: the in-session outcome was read after `LAB_SCREENS` had left the tab (check moved) |
| `speech`, `…/20260924T200649Z` | pass, before the release probe was added |
| **`LAB_SCREENS=1 speech`, `…/20260924T201018Z`** | **pass**: every check in the list above; the row lit 0.293 while read and 0.000 after the stop, as in P8.1 |
| **`voice`, `…/20260924T201342Z`** | **pass**, receiver 27/27, with the phone following the toggle off and on |
| **`guidance`, `…/20260924T201908Z`** | **pass**, receiver 23/23 (regression, and the warning-mark frame) |

All at 1920x1080 except `speech`, which uses the script's 800x480 default. `voice` and
`guidance` had `LAB_ROUTING=~/.cache/openauto-android/rd5` and
`LAB_MAP=~/.cache/oaa-vtm/maps/massachusetts.map`.

The speech run's own account (`…/20260924T201018Z`):

```
+14303ms phone voice guidance off
+17433ms phone voice guidance on
+25939ms notification read aloud
+28431ms notification read aloud stopped
+30933ms notification read aloud
+47055ms voice sample spoken
voice_prompts_requests=0 voice_prompts_started=0 voice_prompts_toggle=on …
voice_samples_requests=1 voice_samples_started=1 voice_samples_completed=1 voice_samples_skipped_in_call=0
  voice_samples_skipped_no_engine=0 voice_samples_skipped_no_bluetooth=0 voice_samples_skipped_no_focus=0
speech_focus_requests=3 speech_focus_denied=0 speech_focus_abandons=3 speech_focus_held=false speech_lead_ins=2
the stand-in engine was handed utterances of 432 432 40 characters
sample with no projection: bound, spoken, released (1 then 2 samples handed to the engine)
sample with no Bluetooth: not spoken, and the phone says why
```

**The voice run matched P8.2's counts exactly:**

- 12 prompts spoken (1 early, 4 prepare, 4 at the turn, 2 re-route, 1 arrival) and 2
  held back as `toggle_off`;
- `voice_samples_requests=0`;
- focus 12 requested and 12 given back;
- no "phone voice guidance" note, so the phone's switch following a press passed nothing
  back.

### 2.3 Screens looked at

| File | What it shows |
| --- | --- |
| `20260924T201018Z/screen-maps-voice.png` | The card's top under Drop folder: "Voice guidance", Speak turn prompts on, Prompts ⓘ, Early / Prepare / At the turn all ticked, fitting one row at 360 dp |
| `20260924T201018Z/screen-maps-voice-lower.png` | Timing Normal, Detail Full, the three switches on, **PLAY A SAMPLE** with its ⓘ, and "Spoken (lab Bluetooth override: no Bluetooth device was checked)." under it |
| `20260924T201018Z/screen-voice-sample-no-bluetooth.png` | The same card after the override was withdrawn: "Not spoken: the phone has no Bluetooth audio connection. Prompts are spoken only over Bluetooth, never on the phone's own speaker." |
| `20260924T201018Z/screen-voice-sample-session.png`, `screen-voice-sample-idle.png` | The spoken outcome during the projection and with none |
| `20260924T201342Z/screen-voice-phone-off.png` | The phone's Speak turn prompts switch off, after the trip panel's press, during the drive |

### 2.4 The warning mark, seen at last

P8.2 could not show it: the voice run had an engine and the override. The `guidance`
script has neither, so the mark shows throughout its drive. The crop is
`20260924T201908Z/voice-toggle-warning.png`, from `frames/040.png` at night and 1080p.

- **The mark.** An amber, filled, upright triangle, about 35x31 px, at the top-right
  corner of the toggle's 131x205 plate, inset by about 10 px. Its "!" is drawn in the
  dark plate colour.
- **The toggle.** It is drawn on, as saved: the accent-blue speaker with two waves. This
  is §6's "whichever way it is set".
- **Contrast.** At night it is clear against the grey plate, and does not touch the
  speaker glyph.
- **Size.** It is about a quarter of the toggle's width, so it reads as a badge rather
  than the toggle's main sign.
- **In daylight, and at Ford's 726x480**, where the plate is 50x95 and the mark
  proportionally about 13 px, it has not been seen. That is a truck question.

## 3. Findings

- **A sample cannot be gated by the toggle and still answer its question** (§1.3).
- **uiautomator's dump lists nodes scrolled out of sight**, with bounds that still
  resolve to a tap. A lab tap on a label must check that the node is in the viewport.
- **The phone's switch only follows a press because the press is noted last** (§1.2).
  Any future control that the head unit changes must be saved before its note, or the
  phone screen shows the old value until the next note.

## 4. Deviations (recorded in plan §15)

1. **§7's "Voice guidance" switch** is labelled **Speak turn prompts**, under a card
   titled Voice guidance. It is still the same saved value as the trip panel.
2. **The screen is a card on the Maps & routing tab.** P7.7 has no sub-screens.
3. **Play a sample is not gated by the toggle** (§1.3). Every other gate is the
   prompt's.
4. **Samples are counted apart**, as `voice_samples_*` (§1.3), not in §9's prompt counts.
5. **A sample waits for engine init**, up to 10 s, where §3 drops a prompt that arrives
   before init.
6. **The sample follows the speed unit and Say street names**, so metric hears
   "In 150 metres", and street names off hears no "onto Main Street".
7. **Turning guidance on from the phone speaks no confirmation.** §6's confirmation is
   for the trip panel's press.

## 5. Not verified: needs the Pixel or the truck

- **Anything heard**, the sample included. The lab engine is silent.
- **The real Bluetooth gate for a sample**: spoken with A2DP connected, and "no Bluetooth
  audio connection" with it off. The lab has only the override.
- **Whether the GrapheneOS engine readies within 10 s** on a cold start outside a
  session. P8.1 has not yet seen it ready at all.
- **Paths not exercised in the lab:**
  - `no_english`, `engine_timeout`, `in_call`, `no_focus`, `focus_lost` and
    `bluetooth_lost` for a sample;
  - a sample cut off by a turn prompt;
  - a projection starting while a sample holds the engine.

  All of these follow from host-tested queue rules and from Speaker code that was read,
  not run.
- **`LAB_SPEECH_ENGINE=none speech`**, the no-engine variant of the new checks. Not run.
- **Settings changed mid-drive on a real route.** The lab changed only the on/off
  mid-projection. That Timing, Detail and the rest reach `VoicePrompts` at once is by
  construction (`Guide.voiceChanged`), not observed in a drive.
- **The warning mark in daylight and at Ford's size** (§2.4).
- **The dark-theme card.** `LAB_SCREENS` photographs Settings in dark mode, not Maps.

## 6. First check on the Pixel

1. Install the release build. Maps → Voice guidance shows every setting at its default,
   with Speak turn prompts on.
2. **Bluetooth off**, no projection: Play a sample says "no Bluetooth audio connection",
   and nothing plays from the phone's speaker.
3. Connect to any A2DP speaker (or the truck, with Bluetooth audio as SYNC's source), and
   start music in a player the operator uses: Play a sample is heard once, and the music
   pauses (Pause media) and resumes. Try Lower volume too.
4. Turn Say street names off: the sample says "In 500 feet, turn right".
5. Turn Speak turn prompts off: the sample still plays, and the line under it says turn
   prompts are off. During a projection with a route, the trip panel's speaker is
   slashed.
6. Pull a report after a projection in which the sample was played: `voice_samples_*`
   count it, and `voice_prompts_*` do not.

## 7. Amendments this needs — proposed, not applied

1. **P8 plan §7**:
   - the switch's label, Speak turn prompts (§4.1);
   - the card on Maps & routing (§4.2);
   - Play a sample "through a prompt's path except the toggle, which it ignores and
     reports beside its outcome" (§4.3);
   - its outcomes, as in §1.3.
2. **P8 plan §3**: "prompts that arrive before init completes are dropped", with "a
   sample waits up to 10 s" beside it (§4.5).
3. **P8 plan §9 / spec §10** (reports): the `voice_samples_*` line, counted apart from
   prompts, and the event notes "phone voice guidance on|off" and "voice sample …". Still
   never the text.
4. **P8 plan §6**: the toggle is also set from the phone's switch, with no confirmation
   (§4.7).
5. **plan.md P8 row**: P8.3 "implemented, host-tested, lab-verified with a silent
   stand-in engine; nothing heard". P8 as a whole is then built, with G12 not run.
