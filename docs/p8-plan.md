# P8 plan — speech — 24 September 2026

Draft. P7 stubbed reading aloud (`Speaker.read` counts a tap and says nothing) and kept
voice guidance out of scope with P6 §0. P8 makes the phone speak, for two things only:

1. **A notification tapped on the dashboard is read aloud** (P7 §5.8's stub, made real).
2. **Turn-by-turn prompts** while a route is being followed, with a button on the trip
   panel to turn them on and off, and settings for when prompts come and what they say.

The design comes from the speech evaluation of 24 September 2026 and James's answers to
it, recorded as decisions in §1. Numbered P8 because it lifts an exclusion both P6 §0 and
P7 §0 state; rename it P7.10 if it should sit under usability instead.

---

## 0. Scope, and what is deliberately outside it

In scope: a speech engine the operator installs, reached through Android's public
`TextToSpeech` API; spoken output over the Bluetooth (A2DP) route that already carries the
phone's audio to the truck; notification read-aloud; turn prompts; the trip-panel toggle;
phone settings for both.

Out of scope, stated so it is not assumed:

- **The AAP speech channel** (Ford ch6, 48000/16/1 PCM). It is what real Android Auto uses,
  and it is the route that would reach the speakers whatever source SYNC has selected
  (§2). It is **not** in P8: it needs a second concurrent audio stream in the session core,
  speech-channel focus (type 3, then `RELEASE`, with SYNC's reported ducking wedge), and
  coexistence run C, which never ran. Not scheduled; §2 accepts the limitation instead.
- **Voice input.** No microphone, no `RECORD_AUDIO`, no assistant; spec §2.1's voice
  assistance stage is untouched.
- **Reading notifications automatically** as they arrive. Only a tap reads one.
- **Choosing a voice, rate or pitch in this app.** The system's TTS settings own those;
  the app links to them (§7).
- **Lane guidance, speed-limit and hazard warnings.** Nothing in the route data to say.
- **Any network.** APP10 stands: the app still declares no `INTERNET`, and §3 asks for an
  offline engine.

## 1. Decisions — 24 September 2026

| # | Question | Decision |
| --- | --- | --- |
| 1 | Where the voice-guidance toggle goes | **Trip panel, beside End route.** End route keeps most of the row; the toggle takes a square at its end, the same height. |
| 2 | How prompt timing is decided | **Speed-scaled, with settings.** Up to three prompts per maneuver (early, prepare, at the turn), lead distance grows with speed; settings pick which stages are heard and an earlier / normal / later lead. |
| 3 | How much control over wording | **Presets plus toggles.** Brief / Full verbosity, street names on/off, announce reroute on/off, announce arrival on/off. No user-editable templates. |
| 4 | Phone not connected to the truck's Bluetooth | **Stay silent.** Truck-only is the requirement; skipped prompts are counted. |
| 5 | What a tapped notification says | **Sender and text only.** No app name. |
| 6 | Does the toggle remember its state | **Yes, saved** like the compass orientation and Ⓟ. A route started muted stays muted. |
| 7 | A turn prompt due while a notification is being read | **The turn prompt interrupts.** The notification is not resumed; tap it again to hear it. |
| 8 | Speech channel (route B) in P8 | **No. Bluetooth only** (§2). |
| 9 | Prompts are silent unless Bluetooth audio is SYNC's selected source | **Accepted** (§2). No truck check first. |
| 10 | What happens to media while the phone speaks | **A setting: Pause media (default) or Lower volume**, one setting shared by notifications and turn prompts (§4, §7). |

## 2. Only while Bluetooth audio is the selected source — accepted

SYNC 4 plays A2DP audio only while **Bluetooth audio is its selected source**. With the
radio or any other source selected, spoken prompts and read-aloud notifications are not
heard. James knows this from the truck and accepted it on 24 September 2026. P8 does not
try to work around it, and the truck check an earlier draft planned (P8.0) is dropped.

What follows from it:

- A prompt the truck does not play is still "spoken" as far as the phone knows, because
  A2DP is connected. The report cannot tell the two apart, and says nothing about it.
- The phone speaker still stays silent, because the gate in §4 checks for an A2DP output,
  not for what SYNC is playing.
- If it stops being acceptable, the AAP speech channel (§0) is the fix. It would need its
  own slice, starting with coexistence run C.

## 3. The speech engine

**`android.speech.tts.TextToSpeech`**, bound once per process and shared by both uses. It
is a public API, needs no permission, adds no AndroidX and no GMS dependency (APP02).

- **Package visibility.** On targetSdk ≥ 30 the app cannot see an engine without
  `<intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>` in the
  manifest's `<queries>`, next to the `MediaBrowserService` entry P7.5 added. Without it,
  initialisation reports no engine and fails silently. A package-visibility declaration,
  not a permission; §13 lists it for the baseline.
- **The Pixel already has two engines** (checked 24 September 2026 with
  `adb shell cmd package query-services --user 0 -a android.intent.action.TTS_SERVICE`):
  - `app.grapheneos.speechservices` — GrapheneOS's own engine, a system app in
    `/product`, **the system default** (`tts_default_synth`). It **holds no `INTERNET`
    permission**, so it is offline by construction, not by promise.
  - `org.woheller69.ttsengine` — a Sherpa-ONNX engine the operator installed. It **has
    `INTERNET` granted** (it downloads voices).

  The app uses whatever the system default is and does not pick or bundle one.
- **Degrades, never fails.** No engine, an engine still initialising, or an engine without
  an English voice: nothing is spoken, the toggle and the phone's status say why (§6, §7),
  and every other feature is untouched. Init is asynchronous; prompts that arrive before it
  completes are dropped, not queued, because a late turn prompt is wrong.
- **Offline.** The app cannot stop an engine from using the network itself, but it can
  see whether the engine holds `INTERNET`. It records the engine's package, and whether
  it holds `INTERNET`, in the report and on the phone's status line. The status line
  flags an engine that can reach the network. The engine goes on the APP09 integration
  list.
- **Lifetime.** Bound when projection starts (or when a sample is played from Settings),
  shut down when projection ends. It holds no state worth keeping across sessions.

## 4. Getting the sound to the truck, and only the truck

**`Speaker`** (today's stub in `NotificationFeed.kt`) becomes the single owner of spoken
output, with two entry points — `read(notification)` and `guide(prompt)` — and one queue.

- **Audio attributes.** Guidance prompts use `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`;
  notification reading uses `USAGE_ASSISTANT`. Both `CONTENT_TYPE_SPEECH`.
- **Audio focus is ours to take.** The system's TTS service does not request focus for the
  caller, so `Speaker` requests it just before calling `speak`, with the same attributes.
  The kind of focus it asks for is the **While speaking** setting (decision 10, §7):
  - **Pause media (the default):** `AUDIOFOCUS_GAIN_TRANSIENT`. The playing app is told
    it has lost focus for a moment. Well-behaved players pause, and resume by themselves
    when focus is abandoned, so the app never presses play and never un-pauses something
    the driver paused. A player that ignores the loss keeps playing under the voice at
    full volume. Android does not enforce a pause, and the plan accepts that rather
    than reaching for the media controls.
  - **Lower volume:** `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. The system ducks the playing
    app, which it does enforce. Spoken-word players (podcasts, audiobooks) often pause
    anyway, because Android doesn't duck speech content.

  `Speaker` **always abandons focus** on `onDone`, `onStop` and `onError`, and when
  projection ends. A dangling grant would leave the phone's music paused or ducked, which
  is the phone-side version of the SYNC wedge P5 warns about. Utterances that follow each
  other within about a second, such as "Rerouting" and the new route's first prompt, are
  spoken under **one** grant. Abandoning in between would make the music restart for a
  moment and stop again. The silence lead-in (below) also gives a player time to pause
  before the first word.
- **Truck-only gate (decision 4).** Before each utterance, `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`
  must include a `TYPE_BLUETOOTH_A2DP` device. If not, the utterance is dropped and counted
  as `skipped_no_bluetooth`. It is checked per utterance, not cached, because Bluetooth
  comes and goes during a drive. There is a small race — A2DP disconnecting mid-utterance
  would finish the utterance on the phone speaker — and `Speaker` stops speaking when
  an `AudioDeviceCallback` reports the A2DP device removed.
- **The first syllable.** An idle A2DP stream takes a moment to start, which can clip the
  start of a prompt. Each utterance after more than a few seconds of silence is preceded by
  `playSilentUtterance` of about 300 ms. The exact figure is measured on the truck, not
  guessed; it is a constant, not a setting.
- **Priority (decision 7).** A guidance prompt uses `QUEUE_FLUSH`: it cuts off whatever is
  being said, including a notification. A notification uses `QUEUE_FLUSH` against another
  notification (the newest tap wins) but is **dropped, not queued**, if a guidance prompt
  is playing. A second tap on the notification being read stops it.
- **Fallback, only if the truck shows routing problems:** synthesise to PCM
  (`synthesizeToFile` with `onAudioAvailable`) and play it through our own `AudioTrack`
  with `setPreferredDevice` set to the A2DP device. That pins the route rather than
  trusting the default, and it is also the first half of any later speech-channel work,
  which needs the PCM in hand. Not built unless needed.

## 5. Turn prompts

### 5.1 Where they come from

`Guide.publish` already produces, on every fix, the step the driver is heading for: its
id, maneuver type and exit, instruction, `onto` and `road`, `metres` and `seconds` to it,
and the `later` steps with their distances. A new **`VoicePrompts`** class — pure Kotlin,
no Android, host-tested like `RouteFollower` — is fed each `UpcomingTurn` plus the current
speed, and returns at most one prompt to speak. `Guide` hands that prompt to
`Speaker.guide`. Nothing on the wire changes.

**Speed.** `Guide.onFix` takes latitude and longitude only today. It gains a speed
argument: the head unit's `car_speed` when the vehicle sends it, else `gps_speed`, else the
phone's `Location.speed`, else none. With no speed, the step's own pace (`metres / seconds`
from BRouter) stands in. Speed is used only for timing and is never reported.

### 5.2 When (decision 2)

Each maneuver has up to three stages, each spoken **at most once per step id**:

| Stage | Lead time (normal) | Minimum distance | Spoken as |
| --- | --- | --- | --- |
| Early | 50 s | 800 m | "In 1 mile, turn right onto Main Street" |
| Prepare | 15 s | 150 m | "In 500 feet, turn right onto Main Street" |
| At the turn | 4 s | 30 m | "Turn right onto Main Street" |

A stage fires on the first fix where the distance to the maneuver is at or under
`max(minimum, speed × lead)`. At 70 mph (31 m/s) that puts Early at about 0.97 mi and
Prepare at about 470 m; at 30 mph, Early is 800 m and Prepare 150 m. Fixes come about once
a second, so a stage is heard within one second's travel of its threshold.

> **P8.2 deviation (24 September 2026):** the 30 mph example is wrong by the formula it
> illustrates: 13.4 m/s × 15 s puts Prepare at about 200 m, not 150 m. The formula is kept.

Rules that keep it from talking too much:

- **Early is skipped on short legs.** If the step is shorter than twice the Prepare
  distance when first seen, the maneuver gets Prepare and At-the-turn only. On city streets
  that is most turns.
- **A stage first seen late collapses.** A step whose first fix is already inside Prepare
  gets Prepare, not Early and then Prepare a second later; inside At-the-turn, it gets only
  that.
- **Straight is silent.** `Continue straight` is never spoken unless it is a road-name
  change the driver needs (a `STRAIGHT` whose `onto` differs from `road`), and then at
  Prepare only.
- **Close turns chain (Full only).** When the next `later` step is within 150 m after this
  one, the At-the-turn prompt adds "then turn left" and the next step's Prepare is
  suppressed.
- **Reroutes.** "Rerouting" when the follower leaves the route (if announce reroute is on),
  once per reroute. The new route's first step then starts fresh, except that a stage
  already spoken within the last 20 s for a maneuver within 30 m of the same point is not
  repeated, because a reroute that lands on the same turn gets new step ids.
- **Arrival.** "You have arrived" once, on `ARRIVED`, if announce arrival is on. The final
  approach gets Prepare as "In 300 feet, arrive at the destination".
- **Timing setting.** Earlier / normal / later multiplies every lead time by 1.3 / 1.0 /
  0.75. Minimum distances do not scale.
- **Stages setting.** Each stage can be turned off. At the turn off, with Full, also
  removes the chained "then".

These numbers are a starting point for a drive, not a finding. The P8.2 results record
what they were on the truck and what they became.

### 5.3 What (decision 3)

The spoken sentence is built from the same instruction the cluster and turn card show
(`Maneuvers.instruction`), so what is heard, what is on the card, and what is on the
cluster cannot disagree about the maneuver.

- **Brief:** distance and instruction. "In 500 feet, turn right onto Main Street."
- **Full:** adds the chained "then …" above, and at the prepare stage for motorway exits,
  "keep right" before "take the exit on the right" when the step before was a keep.
- **Street names off:** `Maneuvers.instruction(maneuver, onto = null, …)`, "Turn right".
- **Distances in words**, in the speed unit's system (`SpeedUnit`): under 0.1 mi as feet
  rounded to 100 ("500 feet"), then "a quarter mile", "half a mile", "three quarters of a
  mile", "1 mile", then to the half mile ("1 and a half miles"). Metric likewise in metres
  rounded to 50, then kilometres to the half.

  > **P8.2 deviation (24 September 2026):** "under 0.1 mi as feet" became **up to and
  > including 1000 feet**, with quarter-mile steps above that, so a 30 mph Prepare (about
  > 200 m, 0.12 mi) is "In 600 feet", not "In a quarter mile". Metric is unchanged.
- **Road names spoken, not read.** OSM names that a TTS engine would read letter by letter
  are expanded for speech only, never on the card or cluster: `I 35` → "Interstate 35",
  `US 290` → "U S 290", `SH 71` / `TX 71` → "State Highway 71", `FM 1826` → "Farm to Market
  1826", `RM` → "Ranch to Market", `CR` → "County Road", `Loop 1` stays "Loop 1", and
  trailing `N/S/E/W` → "North" and so on. Texas prefixes are listed because the real map is
  Texas; the table is small and host-tested, and unknown text passes through unchanged.
- **Roundabouts:** "At the roundabout, take the 2nd exit" — ordinals, not "exit 2".

## 6. The trip-panel toggle (decisions 1 and 6)

- **Layout.** `endRoute` narrows by one square plus a margin; a new `voiceToggle` box of
  End route's height sits at the row's right end. End route is still the widest target on
  the tile; its hit test is unchanged in shape. Both layouts are host-tested so a finger on
  one can never reach the other.
- **Drawing.** A speaker glyph drawn on the canvas like the maneuver icons: lit accent
  while on, a slashed speaker while off. When speech **cannot** happen — no engine, or no
  A2DP output — the glyph is drawn with a small warning mark whichever way it is set, so
  "why is it silent" is answerable from the driver's seat.
- **Touch.** The favourites' press-and-release-on-the-same-target rule, a `takeVoiceToggle()`
  like `takeEndRoute()`, read by `ProjectionService`.
- **Behaviour.** Off stops any prompt being spoken at once and speaks nothing more.
  On speaks the current step's appropriate prompt straight away, as confirmation, then
  carries on normally. Notification read-aloud ignores this toggle; it has its own setting.
- **Saved.** A new `voice_guidance` key in `AppSettings`, written on every toggle,
  like `day_night`. Its value when first installed is the phone setting's default (§7), on.

## 7. Phone settings (P7.7's structure)

Under **Maps & routing → Voice guidance**:

- **Voice guidance** — on/off (the same saved value the trip-panel toggle flips).
- **Prompts** — Early, Prepare, At the turn: three checkboxes, all on.
- **Timing** — Earlier / Normal / Later.
- **Detail** — Brief / Full.
- **Say street names** — on.
- **Announce rerouting** — on. **Announce arrival** — on.
- **Play a sample** — speaks "In 500 feet, turn right onto Main Street" through exactly the
  path a prompt uses, including the Bluetooth gate, so the operator can check the truck
  without a route. Says why if it cannot.

Under **Settings → Notifications**, next to "Show notifications on the dashboard":

- **Read aloud when tapped** — on by default once dashboard notifications are on (they
  are themselves off by default, P7 §5.8).

Under **Settings → Speech**, because it applies to both uses:

- **While speaking** — **Pause media** (default) / **Lower volume** (§4). Saved in
  `AppSettings` as `speech_media`. P8.1 builds it, since notification read-aloud needs it
  before P8.3's guidance screens exist.

Under **Home / status**: one line, "Speech: RHVoice" or "Speech: no engine installed", with
a button to Android's text-to-speech settings (`com.android.settings.TTS_SETTINGS`), where
the engine, voice and rate are chosen.

## 8. Reading a notification (decision 5)

- **What is said:** the sender (or title, as `DashNotification.title` already resolves it)
  and the text. "Sarah. Running ten minutes late." No app name.
- **The full text is fetched at tap time, and only then.** `DashNotification` keeps only
  the first line, by design (P7 §5.8). On a tap, `Speaker` asks the listener for that one
  notification by key (`getActiveNotifications(arrayOf(key))`) and reads `EXTRA_BIG_TEXT`,
  else the last `MessagingStyle` message, else `EXTRA_TEXT` — the same precedence
  `MediaAccessService` already uses. The text is handed to the engine and not stored,
  logged or reported.
- **Gone already:** if the notification has been dismissed since it was drawn, the first
  line the tile holds is read instead, because that is what the driver tapped.
- **Length:** capped at about 400 characters (roughly 30 s of speech), ending
  "message truncated".
- **The row** flashes as today, and stays highlighted while it is being read. A second tap
  stops it.

## 9. Reports and privacy

The spec §10 rule holds: counts, never content.

- `speech_engine` (package name, or `none`), `speech_ready`, and the **While speaking**
  setting in force (`pause` or `duck`).
- Guidance: prompts spoken per stage, prompts skipped per reason (`toggle_off`,
  `no_bluetooth`, `no_engine`, `interrupted`), reroute and arrival announcements, toggle
  presses and the final toggle state.
- Notifications: reads started, completed, stopped by tap, interrupted by guidance,
  skipped. The existing `notifications_read_aloud` becomes `reads started`.
- Never: the spoken text, the street, the destination, the sender, the app, the speed.
- The event log says "voice prompt (prepare)" and "notification read aloud", nothing more.

## 10. Verification

- **Host tests (P8.1–P8.3):** `VoicePrompts` fed synthetic `UpcomingTurn` traces — a city
  leg at 30 mph, a highway exit at 70 mph, two turns 100 m apart, a reroute landing on the
  same turn, arrival, and a route joined inside Prepare — asserting exactly which prompts
  come out and at what distance. The distance-in-words and road-name tables, one assertion
  per row. Trip-panel layout: the toggle and End route never overlap, at every display the
  project renders.
- **Lab (`tools/emulator-lab.sh`):** the emulator has no A2DP, so a lab-only override lets
  `Speaker` pass its Bluetooth gate. The AOSP image may carry Pico TTS; P8.1 checks, and if
  it has none, the lab installs eSpeak NG from the operator cache (never downloaded by the
  script). A `voice` script runs the P6.5 guidance route and asserts the report's per-stage
  prompt counts and the toggle's state after a scripted tap; a frame shows the toggle both
  ways. Nothing in the lab listens to audio.
- **Pixel:** engine installed and found; Play a sample heard over the truck's or any
  A2DP speaker; silent with Bluetooth off, counted as `no_bluetooth`. With music playing:
  on Pause media, the player pauses for the sample and resumes after it; on Lower volume,
  it ducks. Checked with the players the operator actually uses, and any player that
  ignores the pause is recorded by name in the results.
- **Truck:** one drive (§12), with Bluetooth audio as SYNC's source.

## 11. Slice decomposition

| Slice | What it delivers | Depends on | Gate |
| --- | --- | --- | --- |
| **P8.1** | `TextToSpeech` binding, `<queries>` entry, engine status, `Speaker` with focus, the Bluetooth gate and the silence lead-in; notification read-aloud replaces the stub; Settings → Read aloud when tapped and While speaking; report fields (§3, §4, §8, §9). | P7.8 | none |
| **P8.2** | `VoicePrompts` (timing, wording, road names), speed into `Guide.onFix`, guidance through `Speaker`; the trip-panel toggle with its saved state (§5, §6). | P8.1, P7.4 | proposed **G12** |
| **P8.3** | Maps & routing → Voice guidance settings and Play a sample (§7). | P8.2 | none |

P8.2 ships with the §5.2 defaults hard-wired, so a drive can happen before P8.3's screens
exist.

## 12. Proposed gate

| Gate | Required evidence | Interpretation |
| --- | --- | --- |
| **G12** voice guidance drive | On a real route in the truck: prompts are heard through the truck's speakers at each stage, the toggle on the trip panel silences and restores them from the driver's seat, a notification tapped on the head unit is read aloud and a turn prompt cuts it off, and the phone speaker stays silent throughout. Operator observation plus the session report's prompt counts. SYNC's source is Bluetooth audio (§2). | Spoken guidance works on the vehicle without the phone in hand. |

## 13. Spec and plan amendments this needs — proposed, not applied

1. **spec §2.1 / P6 §0 / P7 §0** — voice guidance and read-aloud move from excluded to P8.
   Voice *input* stays excluded; spec §2.1's voice-assistant paragraph is unchanged.
2. **spec §5.2** (permission baseline) — P8 adds **no permission**. It adds a `<queries>`
   entry for `android.intent.action.TTS_SERVICE`, listed with P7.5's.
3. **APP09 / the notification-access consent** — P7's item 6 already rewrites the consent
   for reading alerting notifications. It is extended: when Read aloud is on, the full
   text of a **tapped** notification is fetched from the phone and spoken, and not kept.
4. **APP09 integration list** — the speech engine, by package, with the note that the app
   does not control whether the engine uses a network.
5. **spec §10** (reports) — §9's fields, and "never the spoken text".
6. **spec §11.1** — **G12** as §12.
7. **spec §12 / plan.md** — a P8 section, every task unchecked until evidence exists.

## 14. Open questions

1. **HFP.** If the phone is also paired for calls, SYNC may drop A2DP during a call. A turn
   prompt during a call would then be lost (or, if A2DP stays up, spoken over the call).
   The plan's answer is to **not speak guidance during an active call** — the P7.9
   call state already knows — and count it as `skipped_in_call`. Confirm that's wanted.
2. **Notification text while moving.** P7 §10 question 3 asks whether the tile's text line
   should hide while moving. Read-aloud is the natural answer to that, so it is worth
   deciding both together.
3. **Engine choice.** The GrapheneOS engine is the default and has no network access.
   The Sherpa-ONNX engine may sound better, but it holds `INTERNET`, so the network rule
   (APP10) favours the default. Staying on the default costs nothing to try. If its voice
   isn't good enough on a drive, revoking Sherpa's network permission in GrapheneOS
   after its voices are downloaded makes it offline too. James's pick goes in the
   Pixel's recorded configuration.

## 15. Deviations noted while building

**P8.1** ([p8-1-results](p8-1-results.md)), 24 September 2026. None changes a decision in §1.

- **§9's report names.**
  - The keys are `notifications_read_*`, `voice_prompts_*` and `speech_*`.
    `voice_prompts_` avoids `Guide`'s existing `guidance_state`.
  - Ends beyond §9's list are counted: `replaced` (a newer tap), `bluetooth_lost`,
    `focus_lost`, `stopped`, `error` and `session_end`.
  - Skips beyond §9's list are counted too: `off` (Read aloud off), `guidance_playing`
    and `no_focus` (Android refused focus, which skips rather than speaks without it).
  - Guidance's "interrupted" is `voice_prompts_interrupted_by_guidance`.
  - `notifications_read_taps` keeps the stub's old meaning.
  - `speech_ready` is "became ready during the session".
- **§14.1** is implemented as proposed, in one commented place (`SpeechQueue.guide`).
  Ringing counts as a call. Besides P7.9's state, which is fed only with Call controls
  on, the audio mode is used, with no permission.
- **§8's precedence** (big text > last message > text) is implemented as written. It is
  not the tile row's precedence, which the paragraph says it is (results §3.3).
- **§10's lab.** The AOSP image has no engine and eSpeak NG is not in the cache, so the
  lab player gained a silent stand-in engine, disabled except in the `speech` script.
  eSpeak is used when the operator places it at `~/.cache/openauto-android/tts/espeak-ng.apk`.

**P8.2** ([p8-2-results](p8-2-results.md)), 24 September 2026. None changes a decision in §1.

- **§6's square.** The trip panel's row is only about two End-route heights wide (415 px
  beside a 205 px button at 1080p, 174 beside 93 at 800x480). A square toggle of End
  route's height would leave End route narrower than the toggle, against §6's "still the
  widest target on the tile", and "End route" in 12 px type at 800x480. The toggle is End
  route's height, as §1 decision 1 says, and a third of the row wide: 131x205 at 1080p,
  55x93 at 800x480, 50x95 at Ford's 726x480. It is square only where a square leaves End
  route twice its width, which no display the project renders does. "End route" is now
  sized to its width as well: 45 px at 1080p (was 57), 17 px at Ford's 726x480 (was 27).
- **§6's toggle is `Speaker`'s gate, not `VoicePrompts`'.** `VoicePrompts` is always fed
  with guidance enabled, and every prompt goes to `Speaker.guide`, where the toggle skips
  it as `toggle_off`. That is what makes §9's `toggle_off` count possible. Stage progress
  is tracked the same either way, so turning the toggle on still releases no backlog.
- **§9's toggle keys** are `dashboard_voice_toggle_presses` and `dashboard_voice_guidance`
  (beside `dashboard_end_route_presses`), and `voice_prompts_toggle` on the speech line.
  Every stage's `voice_prompts_spoken_<stage>` is listed, at 0 when none was spoken.
- **§5.1's odometer.** No head unit this project has met sends `ODOMETER` (Ford's nine
  sensors do not include it), so `VoicePrompts` keeps its own dead reckoning from speed.

**P8.3** ([p8-3-results](p8-3-results.md)), 24 September 2026. None changes a decision in §1.

- **§7's screen** is a **Voice guidance** card on the Maps & routing tab, since P7.7 has
  no sub-screens. Its on/off switch is labelled **Speak turn prompts** under that title.
  It is still the saved value the trip-panel toggle flips.
- **§7's "exactly the path a prompt uses"**, except the toggle. Play a sample passes the
  call rule, the engine check, the Bluetooth gate, focus, the lead-in and the flush, as a
  prompt does, but not the trip-panel toggle. A muted toggle silencing the sample would
  look like a fault. The phone says the toggle is off beside the outcome instead.
- **§9's counts.** Samples are counted on their own line, `voice_samples_*`, never in
  `voice_prompts_*`, so a drive's prompt counts include no sample. Focus counts include
  them.
- **§3's "dropped, not queued"** applies to prompts only. A sample waits up to 10 s for
  the engine to initialise.
- **§3's lifetime.** An engine bound for a sample outside a projection speaks only the
  sample, and is released once it ends. A projection starting meanwhile stops the sample
  and binds afresh.
- **§7's words** follow the speed unit and Say street names ("In 150 metres, …";
  "In 500 feet, turn right").
- **§6's confirmation** is for the trip panel's press only. Turning guidance on from the
  phone speaks nothing.
- **Event-log order.** "dashboard voice guidance on|off" is noted after the press is
  saved and `Guide` told, so that the phone's switch, redrawn by the note, shows it.
  Turning guidance on therefore logs the confirming prompt first.
