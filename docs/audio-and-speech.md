# Audio and speech

Sound reaches the truck by three routes, and only one of them is HeadLink's own work on
the wire:

| What | Route | Default | Code |
| --- | --- | --- | --- |
| Everything the phone plays (music, podcasts, calls, other apps' navigation, alarms) | Ordinary Bluetooth A2DP (and HFP for calls), set up by Android | Always | None in this project |
| HeadLink's spoken output: read-aloud notifications, turn prompts, weather alerts, search-ahead results | Android `TextToSpeech`, played over the A2DP route | On, Bluetooth only | `Speaker.kt`, `SpeechRules.kt`, `VoicePrompts.kt`, `SpokenText.kt` |
| Audio HeadLink generates itself, sent inside the Android Auto session | The projection's PCM `MEDIA` channel over USB or Wi-Fi | **Off** ("Audio out over USB") | `AudioSource.kt`, `core/aap-session` |

The rule is in spec.md §2.2: the projection audio channel is not the route for the
phone's general audio.

## Phone audio goes over Bluetooth A2DP

**What.** The phone stays paired to the truck as an ordinary Bluetooth audio device.
Android routes media to it, so all of the phone's audio plays in the truck and the phone
speaker stays quiet. HeadLink adds no code, permission or setting for this. Calls use the
truck's own Bluetooth hands-free profile in the same way.

**Why A2DP and not the USB audio channel.** The goal was "all of the phone's audio, in the
truck and only in the truck". Three routes were weighed:

| Route | What it does | Why it lost or won |
| --- | --- | --- |
| Bluetooth A2DP | A real output route: the phone speaker goes silent | Covers every audio category Android routes there. No code, no permission. **Chosen.** |
| `AudioPlaybackCapture` over the USB audio channel | Copies some of the audio: playback continues on the phone too | Captures only `USAGE_MEDIA`/`GAME`/`UNKNOWN`, so navigation prompts, notifications, alarms and calls can't be captured. Any app can opt out. Needs MediaProjection, which spec.md §5.2 forbids, plus a per-session consent prompt. Closed; don't re-propose it without new information. |
| Privileged audio-policy routing | What real Android Auto does | Needs signature/privileged permissions, which an ordinary app can't hold |

The Android Auto `bluetooth` service in discovery carries no audio. Its descriptor is an
adapter address and a list of pairing methods, used to start an ordinary pairing. It
stays closed (spec.md §8.1).

**Coexistence with projection.** A2DP keeps playing through a live projection session:

- **Wired**: both orders work. A2DP connected first and then projection, or projection
  first and then A2DP.
- **Wireless**: on the truck, HFP and A2DP stayed connected through wireless projection
  sessions and music kept playing through the truck (operator: "It worked perfectly").
  They dropped only at the orderly power-down when the engine was turned off. The
  wireless link's report logs every A2DP and HFP change for the head unit while it is up
  (see [connecting.md](connecting.md)).

**Limits worth knowing.**

- SYNC 4 plays A2DP only while **Bluetooth audio is its selected source**. With the radio
  or another source selected, the phone, including its spoken prompts, is not heard. The
  phone can't tell the difference: A2DP is still connected. The owner accepts this. The
  AAP speech channel (below) would be the only fix.
- "The phone speaker is silent" and per-category coverage (media, other apps' navigation
  prompts, notifications, alarms, UI sounds) were never checked one by one. A2DP routing
  makes them near-certain, but they are assumed rather than measured.
- Nobody has tested whether a USB audio focus request (with "Audio out over USB" on)
  takes the head unit's source away from Bluetooth. That doesn't matter while the option
  is off by default, but it matters again if anyone turns it on for real use.

**Verified:** on the truck, wired (both orders) and wireless, by ear.

## Media controls

The head unit's transport buttons and the now-playing text control and describe whatever
player is running on the phone. HeadLink owns no media session and plays nothing for
this: buttons go out through `AudioManager.dispatchMediaKeyEvent` (no permission), and the
now-playing text needs the notification listener (`MediaAccessService`). Without that
grant, the head unit is sent a stopped, empty state. The playback itself is A2DP's. See
`MediaControl.kt` and [dashboard.md](dashboard.md) for the media tile.

## The projection audio channel (app-owned audio)

Older code comments call this P5.3.

**What.** With **Settings → Media → Audio out over USB** on (saved setting `audio`,
default off, available only when the attempt runs to projection), the session opens one
advertised audio channel, takes audio focus and streams PCM. The only thing it ever
sends is `AudioSource`'s test signal: 440 Hz on the left channel and 880 Hz on the right,
at a quarter of full scale. An operator hears a chord, and a swapped pair of channels is
audible. HeadLink has no other app-owned audio to send. Speech uses Bluetooth, not this
channel. The name says USB, but the session is the same over wireless.

**Why it is kept although nothing uses it.** It works and has passed on the truck, and
the session code that runs it is the same multi-stream code video runs on
(`ProjectionState` holds a `Vec<StreamState>` with video at index 0; `MediaLink` is
generic over a codec). Removing it would mean refactoring the video path for no gain. It
is the starting point for an AAP speech channel if one is ever built.

**Where.**

| Piece | Location |
| --- | --- |
| Channel choice and format check | `core/aap-session/src/discovery.rs`: `AudioChoice`, `AudioConfigChoice::usable`, `audio_channel` |
| Open, setup, focus, start, pause, stop, underflow | `core/aap-session/src/projection.rs`: `open_audio`, `request_audio_setup`, `request_audio_focus`, `audio_focus_response`, `start_audio`, `pause_audio`, `stop_audio`, `audio_underflow`, `audio_buffer_bytes` |
| PCM link and size limit | `core/aap-session/src/media.rs`: `AUDIO_UNIT_LIMIT`, `DEFAULT_AUDIO_LIMIT` |
| JNI config keys | `core/aap-jni/src/manager.rs`: `audio` (default false), `audio_focus_wait_ms` (default 10000) |
| App side | `AudioSource.kt` (`AudioStartParams`, the tone thread `aap-audio`), `ProjectionService.startAudio`/`stopAudio` |
| Message ids | `core/aap-proto/src/lib.rs`: `AUDIO_FOCUS_REQUEST`/`RESPONSE` (0x0012/0x0013, control channel), `av::AUDIO_UNDERFLOW` (0x800B) |

**Channel choice and format.** Ford SYNC 4.0 advertises four audio channels: PCM and
AAC, each for `MEDIA` and `SPEECH`. Media channels are 48000/16/2 and speech channels are
48000/16/1, not the conventional 16 kHz. HeadLink takes at most one: PCM carrying
`MEDIA`, because PCM needs no encoder. The phone checks the format itself and refuses
rather than guesses: sample rate 48000 or 16000, 16-bit only, one or two channels. Any
other format is recorded as a discovery issue, and the channel is never opened. Buffers
are 2048 frames at 48 kHz (8192 bytes, about 42.7 ms at stereo) or 1024 frames at 16 kHz.
The app paces itself from those figures on its own thread. Timestamps come from the
sample counter, not the wall clock.

**Audio focus** is a control-channel exchange, separate from video focus (spec.md §8.1):

- HeadLink sends `AUDIO_FOCUS_REQUEST(GAIN)` once the channel is open and set up. Only a
  grant (`GAIN`, `GAIN_MEDIA_ONLY`, `GAIN_TRANSIENT`) sends the start indication. A head
  unit's mixer may have no route open before it grants.
  `GAIN_TRANSIENT_GUIDANCE_ONLY` is not treated as a grant, because HeadLink has no
  guidance audio on this channel.
- **Only the response's required state field decides.** The vendored schema names
  field 2 `granted`, but it is the `unsolicited` flag: Ford sends it false both when
  granting and when refusing. Gating on it once threw away a real grant. Field 2 is used
  only to tell a refusal (`audio_focus_denials`) apart from a state the head unit
  volunteered (`audio_focus_volunteered`). The lab simulator was written from the same
  misreading and agreed with the bug, which is why spec.md §8.1 now says a field must be
  settled by behaviour, not by its name.
- **A loss pauses; it doesn't end.** `LOSS` or a refusal stops production and discards
  the queue, but keeps the stream set up and the request standing, so a later grant
  resumes without renegotiating.
- **HeadLink never ducks its own media.** `LOSS_TRANSIENT_CAN_DUCK` is counted, and the
  PCM keeps flowing at full amplitude. The head unit lowers the channel in its own mixer.
- **`RELEASE` belongs to a grant.** It is sent on any stop (operator, projection limit, or
  head-unit shutdown) only if focus was actually held. It is also sent if a focus
  response failed to decode (`audio_focus_uncertain`), because then "never granted"
  can't be proved. SYNC 3.4 owners report a focus/duck state that wedges until the cable
  is pulled, so a grant must never be left dangling.
- The report keeps the whole focus sequence (`audio_focus_sequence`,
  `elapsed_ms:state:unsolicited`), not just the last state. A denial and "a loss, then a
  grant" can look the same in the last state alone (spec.md §10).
- Android Auto itself asks for audio focus only when an app on the phone takes Android
  audio focus. HeadLink asks at the streaming step, with nothing playing yet. Ford
  granted it anyway.

**Underflow.** `0x800B AUDIO_UNDERFLOW` comes from the head unit when its mixer runs dry.
It is accepted, counted (`audio_underflows`) and never answered. Its body is not decoded,
because schemas disagree on whether it has one, so only the body length is kept. It is
evidence about HeadLink's pacing, not about the truck. Ford has never sent one.

**Interleaving.** Video keyframes span several fragments, and some head units abort a
message if a frame from another channel arrives in the middle of it. Two things make this
safe. `Session::send_on` queues every fragment of a message before it returns, so nothing
can be queued in between. And `AUDIO_UNIT_LIMIT` refuses any PCM buffer that wouldn't fit
in one outbound fragment, so an audio message is always a single frame. Keep both if this
code changes.

**Every audio failure is non-fatal**: a refused open, a refused setup, a denied or
unanswered focus request, a stalled window, or an app-side failure (`nativeAudioFailed`)
each stop the audio and leave the picture running.

**Verified:** on the truck: the tone was heard from the vehicle speakers for 40.8 s, 958
buffers, zero underflows, with focus acquired and released cleanly. Ford's audio
acknowledgement window is 40 (its video window is 4). Lab: the lab receiver checks the
received PCM with a Goertzel filter at both frequencies on both channels, and an
injected underflow, and the interleaving check passes with fragmenting keyframes. Host
tests in `core/aap-session/tests/projection.rs` (the `audio_*` and `a_*audio*` tests).
Open: whether a head unit runs "ackless" audio can't be detected (`HeadUnitInfo` carries
no version), and pacing under real load is unmeasured.

## Speech

Older code comments call this P8.

HeadLink speaks through Android's public `TextToSpeech` API, over the Bluetooth route
that already carries the phone's audio. It speaks only while a dashboard projection is
running, wired or wireless. It never speaks on the phone's own speaker.

### The engine

- The app binds whatever engine is the system default and never picks or bundles one.
  Voice, rate and pitch belong to Android's text-to-speech settings.
  `SpeechEngines.default` predicts which engine will bind (system default, else the
  first system engine, else the first), and the engine that actually binds replaces the
  prediction.
- The manifest's `<queries>` has an `android.intent.action.TTS_SERVICE` entry. On
  targetSdk 30 and later the app can't see any engine without it, and initialisation
  fails silently. It is a visibility declaration, not a permission.
  `tools/verify-apk.sh` checks that it is there.
- It needs an English voice: the device locale if that is English, else `Locale.US`.
  "Ready" means the engine answered at least `LANG_AVAILABLE`.
- **Lifetime.** `Speaker.bind` runs when a dashboard projection starts, and
  `Speaker.release` runs first thing in `stopProjection`. Initialisation is asynchronous.
  A prompt that arrives before the engine is ready is dropped (`no_engine`), not queued,
  because a late turn prompt is wrong.
- **Offline.** HeadLink can't stop an engine from using the network, so it shows whether
  the engine holds `INTERNET`. Home shows "Speech: <engine>", "Speech: <engine> (this
  engine can use the network)" or "Speech: no engine installed", with a **Text-to-speech
  settings** button. On the GrapheneOS Pixel the default engine,
  `app.grapheneos.speechservices`, holds no `INTERNET` permission.

### `Speaker`: the one owner of spoken output

`Speaker.kt` is the Android glue and runs on the main thread only. Every decision is in
`SpeechQueue` (`SpeechRules.kt`), which is pure, host-tested, and never sees the words.

- **Bluetooth only.** Before every utterance, `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`
  must include a `TYPE_BLUETOOTH_A2DP` device. This is checked fresh each time, never
  cached. With none, nothing is said and it is counted `no_bluetooth`.
- **When Bluetooth drops mid-utterance**, an `AudioDeviceCallback` stops speech the
  moment the last A2DP output is removed (`bluetooth_lost`). Otherwise the rest of the
  sentence would finish on the phone speaker. Weather alerts waiting their turn then meet
  the gate and are counted. The trip panel's speaker button shows a warning mark whenever
  speech can't happen (no ready engine or no A2DP output). It is refreshed every second
  (`SPEECH_CHECK_MS`).
- **Audio attributes.** Everything is spoken as `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`,
  `CONTENT_TYPE_SPEECH`. Read-aloud notifications once used `USAGE_ASSISTANT`. That
  stream's volume sits far below media's, and the volume rocker doesn't reach it, so on
  the truck a read notification was barely audible while turn prompts were fine.
- **Media pause or duck.** Android's TTS service doesn't take audio focus for the
  caller, so `Speaker` requests it just before `speak`. The kind follows **While
  speaking**:
  - **Pause media** (default): `AUDIOFOCUS_GAIN_TRANSIENT`. Well-behaved players pause
    and resume on their own, so HeadLink never presses play and never restarts something
    the driver paused. A player that ignores the focus loss keeps playing under the voice,
    because Android doesn't force a pause.
  - **Lower volume**: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. Android enforces the duck.
    Podcast and audiobook players often pause instead.
- **Focus is always given back.** The grant is abandoned `FOCUS_LINGER_MS` (1 s) after
  the last utterance ends, whether it finished, was stopped or failed. An utterance that
  starts within that second shares the grant. So "Rerouting" and the new route's first
  prompt don't make the music restart and stop again. At projection end, focus is given
  back at once. If another app takes focus away (`LOSS` or `LOSS_TRANSIENT`), speech
  stops (`focus_lost`). If Android refuses focus, nothing is said (`no_focus`), and
  nothing already playing is cut off. A dangling grant would leave the phone's music
  paused, so every stop path gives it back.
- **Lead-in.** After 3 s of quiet (`LEAD_IN_AFTER_IDLE_MS`), each utterance starts with
  300 ms of `playSilentUtterance` (`LEAD_IN_MS`). An idle A2DP stream takes a moment to
  start and would clip the first syllable. Both figures are constants and haven't been
  tuned on the truck.
- **Held during calls.** Turn prompts, weather alerts and the sample are skipped while a
  call is ringing or connected (`in_call`). SYNC may drop A2DP for HFP, which would lose
  the prompt. Or it may keep A2DP up and speak the prompt over the call. Silence is
  better than either. A call is `CallControl.state` in any phase but `NONE`/`ENDED`, or
  else an `AudioManager` mode of ringtone, in-call, in-communication or call screening.
  That covers calls with Call controls off and needs no permission. Notification
  read-aloud is not held during a call. The rule lives in one place, `SpeechQueue.guide`.
- **Priority.**

  | Arriving | While this is being said | Result |
  | --- | --- | --- |
  | Turn prompt | Anything | Cuts it off (`QUEUE_FLUSH`). A cut-off notification isn't resumed |
  | Notification tap | A turn prompt or weather alert | Dropped, not queued |
  | Notification tap | Another notification | Newest tap wins (`replaced`) |
  | Notification tap | The same notification | Stops it (`stopped_by_tap`) |
  | Weather alert | A turn prompt or another alert | Waits its turn, then is said. Never cuts either off |
  | Weather alert | A notification | Cuts it off |
  | Search-ahead result | A turn prompt | Waits up to 10 s, then is said anyway |

### Reading a tapped notification aloud

Tapping a notification row on the dashboard reads it aloud. Notifications are never read
automatically as they arrive.

- **What is said:** the sender and the text, never the app's name: "Sarah. Running ten
  minutes late." `ReadAloudText.compose` takes `EXTRA_BIG_TEXT`, else the last
  `MessagingStyle` message (with its sender), else `EXTRA_TEXT`. This order differs from
  the tile's on purpose: the tile shows the short text, and reading aloud gives the long
  one. If the row's title fell back to the app's name, no name is said.
- **The full text is fetched only at the moment of the tap**, and only once the queue has
  decided to speak. `MediaAccessService` answers `getActiveNotifications(arrayOf(key))`
  through `Speaker.textSource`. If the notification has been dismissed since, the tile's
  own first line is read, because that is what the driver tapped.
- Whitespace is collapsed. Text over 400 characters (about 30 s) is cut at a word and
  ends with "Message truncated."
- The row stays lit while it is read (`Speaker.readingListener` →
  `Dashboard.reading`). Tapping it again stops the reading.
- Governed by **Settings → Notifications and calls → Read aloud when tapped** (default
  on). It shows only while dashboard notifications are on.

### Turn prompts

`Guide.publish` feeds every step update to `VoicePrompts` (pure, host-tested). It returns
at most one prompt, which goes to `Speaker.guide`. The words are built by `SpokenText`
from the same `Maneuvers.instruction` that the turn card and cluster show, so the voice
and the screen can't disagree about the maneuver. Routing and the step data are described
in [navigation.md](navigation.md).

**Speed** comes from the head unit's validated `car_speed`, else its validated
`gps_speed`, else the phone's `Location.speed` (`Dashboard.guidanceSpeed`). With none,
the step's own pace (BRouter's metres over seconds) is used. Speed is used for timing
only and never reported.

**When.** Each maneuver has up to three stages. Each is said at most once per step. A
stage fires on the first update where the distance to the maneuver is at or under
`max(minimum, speed × lead × timing)`:

| Stage | Lead at Normal | Minimum | Example |
| --- | --- | --- | --- |
| Early | 50 s | 800 m | "In 1 mile, turn right onto Main Street" |
| Prepare | 15 s | 150 m | "In 500 feet, turn right onto Main Street" |
| At the turn | 4 s | 30 m | "Turn right onto Main Street" |

Timing **Earlier / Normal / Later** multiplies the lead by 1.3 / 1.0 / 0.75. The minimums
never scale. These numbers are a starting point and haven't been tuned on a drive.

Rules that keep it quiet:

- **Early is skipped on short legs**: a step shorter than twice the Prepare distance
  when first seen gets Prepare and At the turn only.
- **A stage first seen late collapses.** A step first seen inside Prepare gets Prepare
  only, never a late Early as well. A route started near its first turn opens with
  Prepare.
- **Straight is silent**, except when the road name changes (with street names on), and
  then only at Prepare.
- **Close turns chain** (Full detail): when the next maneuver is within 150 m, At the turn
  adds "then turn left", and the next step's Prepare is suppressed.
- **Keep before an exit** (Full detail): an exit's Prepare that follows a keep says "keep
  right, then take the exit on the right".
- **Reroutes**: "Rerouting" once per re-route (if Announce rerouting is on). A new route
  gives every step a new id, which would repeat everything. So each spoken stage is kept
  for 20 s with its position along the distance driven. A new route's step of the same
  maneuver type within 30 m starts as already announced. Distance driven is dead
  reckoning from speed, because no head unit seen sends `ODOMETER`.
- **Arrival**: the final approach gets a Prepare (for example "In 300 feet, arrive at
  the destination"), then "You have arrived" once per route (if Announce arrival is on).
- **Turning the toggle on while driving** (from the trip panel) at once says the current
  step's prompt as confirmation. Stage progress is tracked while muted, so no backlog
  plays. Turning it on from the phone says nothing; the phone has Play a sample instead.

**What is said.**

- **Distances in words**, in the header speed unit's system. Imperial: feet rounded to
  100 up to and including 1000 ft, then "a quarter mile", "half a mile", "three quarters
  of a mile", "1 mile", then half miles ("1 and a half miles"). Feet go up to 1000 so that
  a 30 mph Prepare (about 200 m) is "600 feet", not "a quarter mile". Metric: metres
  rounded to 50, then half kilometres. Never zero.
- **Street names in words** (`SpokenText.road`), for the voice only. The card and cluster
  keep OSM's text. `I 35`/`IH 35` → "Interstate 35", `US 290` → "U S 290", `SH`/`TX` →
  "State Highway", `FM` → "Farm to Market", `RM` → "Ranch to Market", `CR` → "County
  Road", and a trailing `N/S/E/W` → "North" and so on. The prefixes are Texas ones because
  the real map is Texas. Unknown text passes through unchanged.
- **Roundabouts** use ordinals: "At the roundabout, take the 2nd exit".
- **Say street names** off gives "Turn right".

**The trip-panel toggle.** A speaker button sits beside End route on the dashboard. It
saves `voice_guidance` on every press, so a route started muted stays muted. The phone's
**Speak turn prompts** switch is the same saved value, and each follows the other. The
mute is applied in `Speaker`, not in `VoicePrompts`, so a prompt due while muted is
counted `toggle_off`. A re-route or arrival while muted uses up its one announcement.
Layout and drawing are in [dashboard.md](dashboard.md).

### Other things spoken

These go through the same `Speaker` path, Bluetooth gate, call rule and trip-panel
toggle:

- **Weather alerts** (Settings → Weather → Speak weather alerts): "Weather alert." and
  the headline, once per alert per drive. See [dashboard.md](dashboard.md).
- **Search ahead results** (`AheadSearcher` → `Speaker.announce`). See
  [navigation.md](navigation.md).

### Settings

| Setting | Where | Saved key | Default | Takes effect |
| --- | --- | --- | --- | --- |
| Speak turn prompts (= trip-panel toggle) | Maps & routing → Voice guidance | `voice_guidance` | On | At once |
| Prompts: Early, Prepare, At the turn | same | `voice_early`, `voice_prepare`, `voice_at_turn` | All on | At once |
| Timing: Earlier / Normal / Later | same | `voice_timing` | Normal | At once |
| Detail: Brief / Full | same | `voice_detail` | Full | At once |
| Say street names | same | `voice_street_names` | On | At once |
| Announce rerouting / Announce arrival | same | `voice_announce_reroute`, `voice_announce_arrival` | On | At once |
| Read aloud when tapped | Settings → Notifications and calls | `read_aloud` | On | Next projection |
| While speaking: Pause media / Lower volume | Settings → Speech | `speech_media` | Pause media | Next projection |

Changes on the Voice guidance card reach a running drive through `Guide.voiceChanged`.
Choices are stored by label, never by position, and an unknown value reads as the default.

**Play a sample** (on the Voice guidance card) says "In 500 feet, turn right onto Main
Street", or "In 150 metres, …" in km/h, through the prompt's path: call rule, engine,
Bluetooth gate, focus, lead-in and flush. It answers "can the truck hear the phone?", so
two things differ from a prompt:

- The trip-panel toggle doesn't silence it. The phone notes it when the toggle is off.
- It is counted on its own report line (`voice_samples_*`), never as a prompt.

With no projection running, it binds the engine for the sample alone. It waits up to 10 s
for the engine to start, then releases it. The phone shows the outcome in words
(`SampleOutcome`): spoken, no engine, no English voice, no Bluetooth, in a call, and so
on.

### Report and privacy

Counts only, never content (spec.md §10). The report has four speech lines (from
`Speaker.report`/`SpeechQueue.report`), plus weather alerts and samples:

- `speech_engine` (package or `none`), `speech_engine_internet`, `speech_ready`,
  `speech_media`, `speech_read_aloud`, `speech_gate` (`bluetooth`, or `lab` when the
  debug override was on).
- `notifications_read_*` and `voice_prompts_*`: requests, starts, spoken per stage (every
  stage listed, 0 when none), each way an utterance ended, and each skip reason that can
  apply to that kind.
- `speech_focus_requests/denied/abandons/held`, `speech_lead_ins`.

Never logged or reported: the spoken text, sender, app, notification key, street,
destination or speed. The event log says only "notification read aloud" or "voice prompt
(prepare)".

### Testing and verified state

- **Host tests**: `tools/jni-host-test/SpeechQueueTest.java` covers priority, gating,
  focus linger, lead-in and counts. `VoicePromptsTest.java` covers stage timing, the quiet
  rules, reroute dedupe, distances in words and road names.
- **Emulator lab** (`tools/emulator-lab.sh speech`, `voice`; see
  [development.md](development.md)): the emulator has no A2DP device and no TTS engine.
  So a debug-only intent extra (`lab_speech_bluetooth`) passes the Bluetooth gate, and
  the report says `speech_gate=lab`. The lab player supplies a silent stand-in engine.
  eSpeak NG is used instead if it is placed at `$OPENAUTO_CACHE/tts/espeak-ng.apk`.
  `LAB_SPEECH_ENGINE=none` checks the no-engine path. The lab checks counts, the toggle,
  the phone card and sample, and that no text reaches the report. Nothing in the lab
  listens to the audio.
- **Truck**: turn prompts have been heard through the truck's speakers in normal use, and
  the read-aloud volume problem above was found there. Not on record: a structured drive
  check of every stage, the toggle silencing and restoring prompts from the driver's
  seat, a prompt cutting off a notification, and the phone speaker staying silent. Also
  unconfirmed on the truck: read-aloud at the corrected volume, real players' pause
  versus duck behaviour, the 300 ms lead-in, the 1 s linger, the prompt timing at speed,
  and which of Ford's speed sensors feeds it.

### Not built

- **The AAP speech channel** (Ford's 48000/16/1 PCM `SPEECH` channel). It is what real
  Android Auto uses, and it would be heard whatever source SYNC has selected. It needs a
  second concurrent audio stream in the session, speech-type focus
  (`GAIN_TRANSIENT_MAY_DUCK`, then `RELEASE`, with SYNC's reported duck wedge in mind),
  and the A2DP-versus-USB-focus check above. Not scheduled.
- **Synthesis to PCM played through HeadLink's own `AudioTrack`** pinned to the A2DP
  device. This is the fallback if default routing ever misbehaves, and it would be the
  first half of speech-channel work. Not needed so far.
- **Voice input.** No microphone and no `RECORD_AUDIO` permission. The head unit's
  `av_input` microphone channel is not opened. A voice assistant is deferred
  (spec.md §2.1).
