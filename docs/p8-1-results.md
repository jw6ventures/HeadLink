# P8.1 — the speech engine, `Speaker`, and notification read-aloud: implementation record — 24 September 2026

The first slice of the [P8 plan](p8-plan.md) (§11): the `TextToSpeech` binding and its
`<queries>` entry, engine status, `Speaker` as the single owner of spoken output (§4) with
audio focus, the Bluetooth gate and the silence lead-in, notification read-aloud in place
of P7.8's stub (§8), Settings → Read aloud when tapped and Settings → Speech → While
speaking (§7), Home's speech line, and the report fields (§9). `Speaker.guide` exists
with its priority rules host-tested, for P8.2 to call; nothing calls it yet.

Status: **implemented, host-tested, and lab-verified on the emulator** with a silent
stand-in engine and with none. **Nothing has been heard, and nothing has run on the Pixel
or the truck.**

What the lab found (§3):

- The AOSP emulator image has **no speech engine**: no Pico, nothing answering
  `TTS_SERVICE`. eSpeak NG is not in the operator cache, so the lab gained a silent
  stand-in engine in the lab player, which exercises the whole `TextToSpeech` path.
- Emulator playback runs even with `-no-audio`: an utterance's `onDone` arrives at about
  real time, so completion, the second tap's stop and the focus grant's return are all
  observed, not assumed.

---

## 1. What was built

### 1.1 The engine (plan §3)

- **`<queries>`** gains `android.intent.action.TTS_SERVICE` beside P7.5's
  `MediaBrowserService` entry. No permission is added. `tools/verify-apk.sh` now requires
  the entry and still refuses any `<queries>` that names a package.
- **`SpeechEngines`** (`Speaker.kt`) lists the visible `TTS_SERVICE` services with each
  one's label and whether its package holds `INTERNET`. It predicts the engine
  `TextToSpeech` will bind: the system default (`Settings.Secure.TTS_DEFAULT_SYNTH`) when
  it is installed, else the first system engine, else the first. The app never picks.
- **`Speaker.bind(context, settings)`** creates `TextToSpeech` with the default engine and
  registers an `AudioDeviceCallback`. It is called from `ProjectionService` when a
  dashboard projection starts. **`Speaker.release()`** is called first thing in
  `stopProjection`, and in `onDestroy`'s emergency path. It stops speech, gives focus back
  at once, shuts the engine down and unregisters the callback.
- **Init.** On success the language is set to the device locale if it is English, else
  `Locale.US`. `ready` means the engine answered at least `LANG_AVAILABLE`. Until then
  every request is dropped and counted `no_engine`, not queued (§3). The engine actually
  bound (`getDefaultEngine`) replaces the prediction when they differ.
- **Event log:** "speech engine ready", "speech engine has no English voice" or "speech
  engine unavailable". No engine name is logged.

### 1.2 `Speaker` and `SpeechQueue` (plan §4)

The split follows P7's pattern. Every decision is in the pure **`SpeechQueue`**
(`SpeechRules.kt`), which never sees the words. **`Speaker`** (`Speaker.kt`) is the
Android glue, main thread only.

- **Attributes.** Notifications use `USAGE_ASSISTANT` and prompts use
  `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, both `CONTENT_TYPE_SPEECH`. They are set on the
  engine before each utterance.
- **Focus.** It is requested just before `speak`, with the same attributes.
  - **While speaking** decides the kind: `AUDIOFOCUS_GAIN_TRANSIENT` for Pause media,
    `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` for Lower volume.
  - It is always requested with the same listener, so a second request replaces the
    grant rather than stacking another.
  - It is requested only when no grant is held, or when the grant is held for the other
    kind's attributes.
  - A refused request is a skip, `no_focus`, and cuts nothing off.
- **Always given back.**
  - **After an utterance.** `onDone`, `onStop` and `onError` each schedule the abandon
    `FOCUS_LINGER_MS` = 1000 ms later. A new utterance in that second cancels it and
    speaks under the same grant (§4's "within about a second").
  - **When stopped by the app.** The second tap, A2DP loss and P8.2's toggle schedule it
    the same way.
  - **At projection's end**, at once.
  - **When lost.** `AUDIOFOCUS_LOSS` or `LOSS_TRANSIENT` stops speech and gives the grant
    back.
- **The gate.** Before each utterance, `getDevices(GET_DEVICES_OUTPUTS)` must contain a
  `TYPE_BLUETOOTH_A2DP` device. It is read fresh every time. The `AudioDeviceCallback`
  stops an utterance the moment the last A2DP output is removed, counted
  `bluetooth_lost`.
- **The lead-in.** `LEAD_IN_MS` = 300 ms of `playSilentUtterance` (`QUEUE_FLUSH`), then
  the words (`QUEUE_ADD`). It plays only when nothing is being said and nothing has been
  for `LEAD_IN_AFTER_IDLE_MS` = 3000 ms. Both are constants, not settings, as §4 says.
- **Priority** (decision 7, as `SpeechQueue.read`/`guide` order it):
  - A notification tap:
    1. the row being read is tapped again: stop it;
    2. Read aloud off: `off`;
    3. a prompt is playing: `guidance_playing`, dropped, not queued;
    4. the gate;
    5. otherwise speak with `QUEUE_FLUSH`, so a different row being read is `replaced`.
  - A prompt: toggle off (`toggle_off`), then **in a call** (`in_call`), then the gate,
    then speak with `QUEUE_FLUSH`. Whatever it cuts off is counted
    `interrupted_by_guidance`, a notification or an older prompt.
  - The engine's own `onStop` for an utterance the queue has already moved past is
    ignored, so nothing is counted twice. So are the lead-in's callbacks.
- **§14.1, taken as the plan proposes.** No prompt during a call; it is counted
  `skipped_in_call`.
  - It is one commented decision in `SpeechQueue.guide`.
  - `Speaker` only says whether a call is on: P7.9's `CallControl.state` in any phase but
    `NONE`/`ENDED`, else an audio mode of ringtone, in-call, in-communication or call
    screening. The audio mode needs no permission and covers a call with Call controls
    off, when P7.9's state is not fed.
  - Notification reading is not affected by a call.
- **For P8.2:**
  - `guide(GuidePrompt(text, stage))`;
  - `guidanceOn(on)`, which also stops a prompt being spoken;
  - `stopGuidance()`;
  - `canSpeak()`, for the toggle's warning mark.

  The stage is a free label ("early", "prepare", …). It is counted and logged, and the
  text is not.

### 1.3 Reading a notification (plan §8)

- **The tap.** It comes through `Dashboard.takeReadAloud()` as before. `ProjectionService`
  now hands it straight to `Speaker.read`.
- **Only a spoken read fetches the text.** The words are fetched only once the queue has
  decided to speak. A skip or a second-tap stop reads nothing from the phone.
- **The fetch.** `MediaAccessService` sets `Speaker.textSource` while connected. It
  answers with `getActiveNotifications(arrayOf(key))`, and only while the feed is active.
  What comes back is `NotificationWords`: title, `EXTRA_BIG_TEXT`, the last
  `MessagingStyle` message and its sender, and `EXTRA_TEXT`.
- **`ReadAloudText.compose`** (pure, host-tested) makes the sentence:
  - **The text:** `EXTRA_BIG_TEXT`, else the last message, else `EXTRA_TEXT` (§8's
    order).
  - **Who:** the message's sender when the text is the message, else the title.
  - **Gone from the phone:** the row's own title and first line.
  - **Never the app's name:** a row that fell back to the app's name for its title says
    no name.
  - Whitespace is collapsed. The text is cut at a word at or under `MAX` = 400
    characters and ends "Message truncated."
- **The row stays lit** while it is read. `Speaker.readingListener` → `ProjectionPipeline.reading`
  → `Dashboard.reading(key)`, and the snapshot lights `flashKey ?: readingKey`.
- **Event log:** "notification read aloud", "notification read aloud stopped",
  "notification read aloud skipped (<reason>)". None names a row, sender, app or text.

### 1.4 Settings and Home (plan §7)

- **`SavedSettings`** gains two fields; 33 are stored, up from 31:
  - `readAloud` (`read_aloud`, default on);
  - `speechMedia` (`speech_media`, `pause` or `duck`, stored by key, default `pause`).
- **Settings → Notifications and calls → Read aloud when tapped.** A switch with its ⓘ.
  It is shown only while "Notifications on the dashboard" is on, like the hidden-apps
  row.
- **Settings → Speech → While speaking**: Pause media / Lower volume, with its ⓘ.
  - The spinner is stacked under its label. Beside it, the lab's 360 dp screen showed
    "Pause me…".
  - Settings are read at `bind`. A change made during a drive applies from the next
    projection, as with the other session settings.
- **Home.** A card reads "Speech: <engine label>", "Speech: <label> (this engine can use
  the network)" or "Speech: no engine installed", with a **Text-to-speech settings**
  button (`com.android.settings.TTS_SETTINGS`). It is looked up again on every
  `onResume`.

### 1.5 Report (plan §9)

`NotificationFeed.report()` no longer carries `notifications_read_aloud`. `Speaker.report()`
follows it in `backendsReport` with four lines:

    speech_engine=<package|none> speech_engine_internet=… speech_ready=… speech_media=pause|duck speech_read_aloud=… speech_gate=bluetooth|lab
    notifications_read_aloud=<started> notifications_read_taps=N notifications_read_full_text=N notifications_read_first_line=N notifications_read_truncated=N notifications_read_<end>=N … notifications_read_skipped_<reason>=N …
    voice_prompts_requests=N voice_prompts_started=N voice_prompts_spoken_<stage>=N … voice_prompts_<end>=N … voice_prompts_skipped_<reason>=N …
    speech_focus_requests=N speech_focus_denied=N speech_focus_abandons=N speech_focus_held=… speech_lead_ins=N

- **Ends:** `completed`, `stopped_by_tap`, `replaced`, `interrupted_by_guidance`,
  `bluetooth_lost`, `focus_lost`, `stopped`, `error` and `session_end`.
- **Skip reasons**, only where they can happen to that kind:
  - notifications: `off`, `guidance_playing`, `no_engine`, `no_bluetooth` and
    `no_focus`;
  - prompts: `toggle_off`, `in_call`, `no_engine`, `no_bluetooth` and `no_focus`.
- `speech_ready` is whether the engine became ready at any point in the session. The
  report is written after `release`, when it is no longer ready.
- The report never holds what was said, a sender, an app, a key or a speed. The engine's
  package is the one name, as §9 asks.

### 1.6 Lab hooks

- **`lab_speech_bluetooth`**: a debug-build-only `ConnectionActivity` extra. It sets
  `Speaker.labBluetooth`, so the gate passes on any output, and notes "lab speech
  Bluetooth gate overridden (debug build)". The report says `speech_gate=lab`, so a run
  with the override can never pass for a truck run.
- **`LabSpeechEngine`** in the lab player (`tools/lab-player/`), described in §2.2.

## 2. Verification

Every command below ran under `flock ~/.cache/openauto-android/p8-build.lock`. Logs are
in `~/.cache/openauto-android/p8-1-*.log`.

### 2.1 Build and host tests

- **`tools/android-build.sh`: exit 0** (`p8-1-build.log`, final run after the last code
  change). It covers clippy, both APKs, `lintDebug` with warnings as errors,
  `verify-apk` on all three (now requiring the TTS `<queries>` entry), and every host
  test.
- **`tools/check.sh`: exit 0** (`p8-1-check.log`).
- **New `SpeechQueueTest`: 121 checks**, registered in `tools/jni-host-test.sh`:
  - **The gate.** No engine and no A2DP output each skip, the engine is checked first,
    and a skip takes no focus.
  - **Taps.** Read aloud off skips. A second tap stops, and the engine's later `onStop`
    is not counted again. A third tap reads again. The newest tap wins, as `replaced`,
    under the grant already held and with no lead-in.
  - **Priority.**
    - A prompt cuts a read off (`interrupted_by_guidance`) and asks for focus again with
      its own attributes.
    - A tap during a prompt is dropped, on any row.
    - A prompt cuts an older prompt, under the same grant.
    - Per-stage counts.
  - **Calls.** A prompt in a call is `in_call` even with the gate closed. A notification
    read in a call is not affected.
  - **Focus coalescing.** Two reads half a second apart share one request. There is no
    abandon while speaking, nor before the 1 s linger. Exactly one abandon follows, and a
    later read asks again.
  - **Focus always returned:**
    - after `bluetooth_lost` and after `error`, one second later;
    - on focus loss, at once;
    - at session end, at once, and only once.
    - Four requests, four abandons, and `speech_focus_held=false` at the end.
  - **Lead-in.** 300 ms first. None at 2999 ms of silence, and again at 3000 ms.
  - **Other callbacks.** A refused focus request cuts nothing and counts a skip, not a
    start. Stale ids, including the lead-in's own `onDone`, are ignored.
  - **The report.** The text-source counts are there. No key, package or utterance id
    is, and it is three lines.
  - **`ReadAloudText`.**
    - Sender and text with no app. Big text over text. The last message with its sender
      over the conversation summary. Big text first under the title.
    - Gone from the phone: the tile's line.
    - A fallback-to-app row says no name. Whitespace is collapsed.
    - Who alone. No double stop after "?".
    - The cut is at a word, ≤ 400 and near it, ending "Message truncated.", and exactly
      400 is not cut.
    - An empty notification says nothing.
  - **`SpeechMedia`.** Default, keys, unknown and the screen's order.
- **`AppSettingsTest`** now covers the two new keys (defaults on and `pause`, round trip,
  33 stored, stored by name, and an unknown `speech_media` read as the default).
- **`NotificationFeedTest`** loses its stub check, which is now `SpeechQueueTest`'s. The
  feed's report no longer names `Speaker`, so the test loads no Android class.
- All existing suites pass unchanged: `DashboardTest` 587, `DashboardTripTest` 466,
  `DashboardFlexTest` 1386, and the rest. The other agent's `VoicePromptsTest` also ran
  green in the same build (246 checks); it is not this slice's.

### 2.2 Emulator lab

**The new `speech` script** (`tools/emulator-lab.sh speech`, and `--script speech` in
`tools/lab-receiver/receiver.cpp`).

- **Receiver.**
  1. Waits for the flex tile to fill.
  2. Taps the first row three times, 2.5 s apart: read, stop, read again.
  3. After each tap, reports how lit the row still is, measured after the 800 ms flash is
     over.
  4. Waits 30 s.
- **App side.**
  - **Setup.**
    - Seeds `notifications_on_dashboard`, `read_aloud` and `speech_media=pause`.
    - Grants notification access.
    - Sends `lab_speech_bluetooth`.
    - Lists the image's `TTS_SERVICE` services.
    - Posts two notifications from the lab player. The newer, tapped one has an
      831-character text.
  - **The engine** is chosen by `LAB_SPEECH_ENGINE`:
    - `espeak`: `$OPENAUTO_CACHE/tts/espeak-ng.apk`, installed and made the default. It
      is never downloaded.
    - `lab`: the lab player's `LabSpeechEngine`, enabled with `pm`. This is the default
      without eSpeak.
    - `none`.
  - **Checks, always:** taps 3, `speech_gate=lab`, `speech_media=pause`,
    `speech_focus_held=false`, and no Sendermarker, Bodymarker or "Lab Player" in the
    report. The poster's package appears nowhere but `speech_engine`.
  - **With an engine:**
    - `notifications_read_aloud=2`, `stopped_by_tap=1`, `full_text=2`, `truncated=2`,
      `skipped_no_engine=0` and the "stopped" note;
    - focus requests ≥ 1 and equal to abandons;
    - the last read `completed` or `session_end`;
    - with the stand-in, every utterance handed to the engine ≤ 460 characters, by
      length alone from the lab player's log.
  - **With none:** `notifications_read_aloud=0`, `skipped_no_engine=3`,
    `speech_focus_requests=0` and the skip note.
  - **Picture check, host side.** With an engine the row is still lit 2.5 s after the
    first tap (≥ 0.05) and dark after the stop (< 0.01). With none it is dark after the
    first tap.
- **`LabSpeechEngine`** is lab only and never shipped, like the rest of the lab player.
  - It speaks 25 ms of silence per character in 100 ms buffers and honours `onStop`.
  - It logs "speech N characters" and never the words.
  - It is disabled in its manifest, so no other run has an engine.

| Run | Result |
| --- | --- |
| `LAB_VIDEO_CONFIG=1920x1080 speech` before the stand-in engine existed, `emulator-lab/20260924T185618Z` | **pass**, degraded path. The image has **no** engine: "speech engines on the image: 0". `speech_engine=none speech_ready=false`, three `skipped (no_engine)` notes, focus requests 0 |
| same, first run with the stand-in, `…/20260924T185945Z` | **fail, the check's fault.** The privacy check refused `labplayer`, which is the engine's own package in `speech_engine`. The check now looks at every line but that one |
| `LAB_SPEECH_ENGINE=lab … speech`, `…/20260924T190428Z` | **pass**, 5/5 receiver checks; the row lit 0.301 while read and 0.000 after the stop |
| `LAB_SPEECH_ENGINE=none … speech`, `…/20260924T190546Z` | **pass**, 5/5; the row 0.000 after the first tap |
| `LAB_VIDEO_CONFIG=1920x1080 LAB_SCREENS=1 speech` (lab engine), final build, `…/20260924T191241Z` | **pass**, 5/5; 0.301 / 0.000 |

The stand-in run's event log and counters (`…/20260924T190428Z`):

- **Timeline.** +1.1 s "speech engine ready". +9.2 s "notification read aloud". +11.7 s
  "notification read aloud stopped". +14.3 s "notification read aloud".
- **Counters.**
  - `speech_engine=org.openautoandroid.labplayer speech_ready=true speech_gate=lab`
  - `notifications_read_aloud=2 notifications_read_taps=3 notifications_read_full_text=2 notifications_read_truncated=2 notifications_read_completed=1 notifications_read_stopped_by_tap=1`
  - `speech_focus_requests=2 speech_focus_abandons=2 speech_focus_held=false speech_lead_ins=1`
- **The engine was handed 432 characters twice.** That is "Sendermarker 2. ", ≤ 400 of
  text, and ". Message truncated.", not the 831 posted.
- **One lead-in, not two.** The third tap came 2.6 s after the stop, inside the 3 s
  window.
- **The second read completed** about 10 s after it started, as 432 × 25 ms of silence
  should.

**Regression, same code:** `LAB_VIDEO_CONFIG=1920x1080 calls` (notification route),
`…/20260924T190718Z`: **pass**, 11/11.

- The script's expectations moved from the stub's `notifications_read_aloud=1` and
  "dashboard notification read aloud (stub)" to `notifications_read_taps=1` and
  "notification read aloud".
- It ran with no engine, and logged "notification read aloud skipped (no_engine)".

### 2.3 Frames and screens looked at

| File | What it shows |
| --- | --- |
| `20260924T190428Z/frames/011.png` | **Night, 1080p, 1.3 s after the first tap**, past the flash: "Sendermarker 2" lit in the notifications tile while it is read, "Sendermarker 1" under it unlit. |
| `20260924T190852Z/screen-settings-lower.png` | Before the fix: "While speaking" beside its spinner, cut to "Pause me…". |
| `20260924T191241Z/screen-settings-lower.png` | After: Notifications and calls with **Read aloud when tapped** on; the **Speech** card with "While speaking" and "Pause media" stacked under it. |
| `20260924T190852Z/screen-home-lower.png` | Home's new card: "Speech: Lab speech" and **Text-to-speech settings**. |

## 3. Findings

### 3.1 The emulator image has no speech engine

- **Checked two ways:**
  - `system.img` lists no Pico in `/system/app`, and the image's NOTICE file names no
    SVOX or Pico;
  - on the booted image, `cmd package query-services --user 0 -a
    android.intent.action.TTS_SERVICE` finds nothing.
- **eSpeak NG is not in the cache.** There is no `~/.cache/openauto-android/tts/`.
- **For James: to run the lab against a real engine, place the eSpeak NG Android APK at
  `~/.cache/openauto-android/tts/espeak-ng.apk`.**
  - The app is `com.reecedunn.espeak`, from F-Droid or its GitHub releases, and must be
    an x86_64-capable (universal) build for the emulator.
  - `tools/emulator-lab.sh speech` then uses it by default (`LAB_SPEECH_ENGINE=espeak`).
  - Whether eSpeak initialises headlessly without first opening its own app, which
    unpacks its voice data, is untested. If it does not, the run fails with "the espeak
    engine never became ready".

### 3.2 Emulator playback runs with `-no-audio`

Utterances complete at about real time. The stand-in's 10.8 s of silence gave `onDone`
about 10 s after `speak`. The first utterance's synthesis ran ahead of playback: its
engine log says "synthesized" 2.6 s in, and the app still stopped it mid-playback. That
is the normal engine shape: synthesis fills the framework's buffer and playback drains
it. So the lab observes completion, stop and focus return rather than assuming them. It
proves nothing about routing: there is no A2DP device, and the gate was overridden.

### 3.3 The plan's "same precedence as `MediaAccessService`" is not the same

§8 orders the read text `EXTRA_BIG_TEXT` > last `MessagingStyle` message > `EXTRA_TEXT`,
and says that is the precedence `MediaAccessService` already uses. It is not: the
tile's row uses message > `EXTRA_TEXT` > big text. P8.1 implements §8's explicit order.
For a `MessagingStyle` notification, which rarely carries big text, the two agree. For a
`BigTextStyle` one, the tile shows the short text and the read gives the long one, which
is what reading aloud is for. Noted in the plan's deviations (§15).

## 4. Not verified: needs the Pixel or the truck

- **Anything heard.** No utterance has reached a speaker; the lab engine speaks silence.
- **GrapheneOS's engine** (`app.grapheneos.speechservices`): found through the
  `<queries>` entry, initialised, and holding an English voice. Also what
  `speech_engine_internet` says for it and for the Sherpa engine, and after GrapheneOS's
  Network toggle is revoked (§14.3).
- **The A2DP gate on the phone.**
  - That `getDevices` lists `TYPE_BLUETOOTH_A2DP` while connected to the truck or any
    A2DP speaker, and not otherwise (`no_bluetooth`).
  - That `onAudioDevicesRemoved` fires in time to stop an utterance before it reaches
    the phone speaker. §4's race is not measured.
- **Focus with real players.** On Pause media: that the players the operator uses pause
  and resume by themselves. On Lower volume: that they duck. Any player that ignores
  the pause is to be recorded by name.
- **The 300 ms lead-in and the 1 s linger.** Both are starting figures for the truck (§4).
- **Real notifications.**
  - `getActiveNotifications(arrayOf(key))` answering for a real messaging app's key;
  - `EXTRA_BIG_TEXT` versus `MessagingStyle` in practice;
  - a read of a notification dismissed since it was drawn (`first_line`).
- **The call rule on a phone.** P7.9's state and the audio-mode fallback during a real
  call. Nothing calls `guide` until P8.2.
- **The Home line and settings on the Pixel's screen.** They were seen only at the
  emulator's 360 dp.

## 5. Needed from files this slice does not own

- **P8.2**, when it wires guidance:
  - call `Speaker.guide(GuidePrompt(text, stage))`, with stage labels that are safe as
    report keys (lower case, underscores);
  - mirror the saved toggle into `Speaker.guidanceOn`;
  - draw the toggle's warning mark from `Speaker.canSpeak()`.

  `VoicePrompts.kt` and `SpokenText*.kt` were not touched.
- **`PhoneViews.labelled`'s KDoc** says `stacked` is for Diagnostics. Settings → Speech
  now uses it too.

## 6. Amendments this needs — proposed, not applied

1. **spec §5.2 / P8 plan §13 item 2**: the `<queries>` entry for
   `android.intent.action.TTS_SERVICE`, listed with P7.5's. No permission.
2. **spec §10** (reports): the `speech_*`, `notifications_read_*` and `voice_prompts_*`
   keys of §1.5, with "never the spoken text, a sender, an app or a key".
   `notifications_read_aloud` changes meaning from taps to reads started;
   `notifications_read_taps` is the old meaning.
3. **APP09 / the notification-access consent** (P8 plan §13 item 3): a **tapped**
   notification's full text is fetched by key at the tap, spoken and not kept. The
   listener answers only while dashboard notifications are on.
4. **APP09 integration list** (§13 item 4): the speech engine by package. The report
   carries it, and Home flags one holding `INTERNET`.
5. **P8 plan §9's key names**, as built:
   - `voice_prompts_` rather than a bare "guidance" prefix, which `Guide`'s own
     `guidance_state` already uses;
   - the added ends (`replaced`, `bluetooth_lost`, `focus_lost`, `stopped`, `error`,
     `session_end`);
   - the added skips (`off`, `guidance_playing`, `no_focus`);
   - guidance's "interrupted" as `voice_prompts_interrupted_by_guidance`.
6. **P8 plan §14.1**: implemented as proposed (`skipped_in_call`, ringing counts as in a
   call, with the audio-mode fallback). James to confirm.
7. **P8 plan §8**: the precedence sentence (§3.3).
8. **plan.md P8 row**: P8.1 "implemented, host-tested, lab-verified with a silent
   stand-in engine and with none; nothing heard; Pixel and truck unverified".
