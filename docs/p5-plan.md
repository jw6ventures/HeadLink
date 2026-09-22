# P5 plan — media controls, audio, sensors, and an owned map — 18 September 2026

Draft. Scope for the "useful shell" work package (spec §12 `P5`), written after the
projection POC was validated at 1920x1080@60 on Ford SYNC 4.0 (report 709ffa9c).

**P5.1 (§1) is applied as of 18 September 2026**, with the amendments in §7 accepted and
made to `spec.md`, `plan.md`, and `docs/licenses.md`; its §1 constraint was superseded on
implementation and the reasoning is recorded inline there.

**P5.2 (§2) is implemented, lab-verified, and its vehicle evidence is in** (19 September
2026) — see [P5.2 lab results](p5-2-lab-results.md). It adds no dependency and no vendored
file. Ford populates LOCATION, CAR_SPEED, PARKING_BRAKE, GEAR, NIGHT_DATA, DRIVING_STATUS
and GPS; FUEL_LEVEL and TRAILER are accepted and silent. **P5.5's position source is
therefore a measurement rather than an assumption** — but the *units* of those values are
not established and three of them do not read sensibly, so P5.5 still has a calibration
question in front of it.

**P5.3 (§3) is implemented and lab-verified as of 18 September 2026** — see
[P5.3 lab results](p5-3-lab-results.md). Four `.proto` files from the same pinned commit
were vendored (the audio focus exchange), which adds no dependency and no new import.
**G7 is met** (19 September 2026, third attempt, report `7f869585`): the app's own tone was
audible from the vehicle speakers, with 958 buffers, zero underflows and focus released
cleanly. Ford SYNC 4.0 opens the PCM `MEDIA` channel and accepts its AV setup with a window
of 40. Getting there took two failed attempts: the first was refused focus (`LOSS`); the
second was **granted** (`GAIN`) and this source threw the grant away, because it was gating
on the response's optional field 2 — which Ford sends false whether it grants or refuses,
so it cannot mean `granted`. It is the `unsolicited` flag, confirmed against the Android
Auto 17.6 and 15.7 dex. Fixed in 0.4.1-lab. Its §7 amendments **have since been applied** to `spec.md` and `plan.md`,
worded from what the attempt showed rather than from what was planned.

§§4, 5 remain unapplied draft, and their amendments are still to be accepted before those
slices start. Two decisions are already taken and recorded in §5 and §1.

## 0. What the head unit already offers

Ford SYNC 4.0 advertises twelve channels (report 617af5ce). The source opens two.

| ch | service | decoded advertisement |
| --- | --- | --- |
| 1 | `av` video | 1920x1080@60, 1280x720@60, 800x480@60 — **in use** |
| 2 | `input` | touch 1920x882, 9 keycodes — **in use** |
| 3 | `av_input` | microphone, head unit → phone |
| 4 | `av` audio | `MEDIA_CODEC_AUDIO_PCM` (1), `MEDIA` (3) |
| 5 | `av` audio | `MEDIA_CODEC_AUDIO_AAC_LC_ADTS` (4), `MEDIA` (3) |
| 6 | `av` audio | `MEDIA_CODEC_AUDIO_PCM` (1), `SPEECH` (1) |
| 7 | `av` audio | `MEDIA_CODEC_AUDIO_AAC_LC_ADTS` (4), `SPEECH` (1) |
| 8 | `sensor` | LOCATION 1, CAR_SPEED 3, FUEL_LEVEL 6, PARKING_BRAKE 7, GEAR 8, NIGHT_DATA 10, DRIVING_STATUS 13, GPS 21, TRAILER 24 |
| 9 | `bluetooth` | pairing method 2 |
| 10 | `media_info` | empty SDP marker; carries now-playing |
| 11 | `navigation` | turn-by-turn to the cluster |
| 12 | `wifi` | — |

`AVChannel.stream_type` is `MediaCodecType`, not a stream kind, so channels 4–7 are two
codecs × two audio types. Ford advertises no `SYSTEM` (2) or `ALARM` (4) audio type.

**The schemas for all of this already exist at the current spec pin.**
`oaa/audio/AudioFocus{Request,Response,State,Type}*`, `oaa/av/AVInputOpen{Request,Response}*`,
`oaa/media/MediaPlayback{Status,Metadata}Message`, and the full `oaa/navigation/*` set are
present at `open-android-auto@61eab61` — they are simply outside the `ROOTS` closure in
`tools/proto-gen/src/main.rs`. **No pin bump and no new upstream licence audit are
required**; adding a root and running `tools/proto-gen.sh` is the whole schema task.
`MediaPlaybackStatus` carries an upstream wire verification against a 1993-message capture.

## 1. P5.1 — Media controls and now-playing (first slice)

Chosen as the first slice: it needs no change to the single-stream core, and the audio
itself can ride the phone's existing Bluetooth A2DP link to the vehicle while USB audio
(P5.3) is still unbuilt.

**Superseded on implementation (18 September 2026). This slice controls third-party
playback; it owns none of its own.** The paragraph below stood until the two halves of the
problem were checked separately against the platform, which showed they have different
answers:

- *Sending* a transport button needs **no permission at all**.
  `AudioManager.dispatchMediaKeyEvent` declares no `@RequiresPermission` in AOSP and routes
  through the platform's media-button path, exactly as a Bluetooth headset button does.
- *Reading* what is playing needs the notification listener, as stated below.
  `MediaSessionManager.getActiveSessions`, `getMediaKeyEventSession` and
  `getMediaKeyEventSessionPackageName` all carry the same javadoc: `MEDIA_CONTENT_CONTROL`
  (signature|privileged, barred by APP02) **or** an enabled `NotificationListenerService`,
  `SecurityException` otherwise. The same grant is what makes an exact ±30 s `seekTo`
  possible; without it only `KEYCODE_MEDIA_FAST_FORWARD`/`REWIND` are available, whose step
  is the playing application's choice.

The accepted resolution is an **opt-in integration that degrades**: transport controls ship
with no new permission, and now-playing text plus exact seeking are offered separately with
the notification listener explained (APP09). Without the grant the head unit is told a
stopped, empty state rather than a guess. Spec §5.2 was amended to permit the listener for
this purpose only, with the service implementing no callback. An app-owned `MediaPlayer`
was **not** built: with audio still on Bluetooth A2DP until P5.3, it would have demonstrated
nothing the transport path does not.

~~**Hard constraint, decided here: an app-owned session only.** Enumerating or controlling a
third-party player's `MediaSession` requires notification-listener authorisation, which
spec §5.2 forbids. This slice therefore owns its own `android.media.MediaPlayer` and
`MediaSession` over a bundled or user-supplied local audio file. This is exactly what spec
§2.2 already directs: "Route audio owned by this app first; treat arbitrary-player audio
capture/routing as a separate platform feasibility gate."~~

Head unit → phone is **already working and unexercised**. Ford's input binding succeeded
(`input_binding_status=0`) with `KEYCODE_MEDIA_PLAY_PAUSE` 85, `KEYCODE_MEDIA_NEXT` 87,
`KEYCODE_MEDIA_PREVIOUS` 88, `KEYCODE_MEDIA_PLAY` 126, `KEYCODE_MEDIA_PAUSE` 127,
`KEYCODE_SEARCH` 84, `KEYCODE_MEDIA` 65537 and `KEYCODE_NAVIGATION` 65538. Those arrive as
button events on channel 2 today; `scene_keys=0` in every report only because no operator
has pressed them. The work is to route `EventKind::Key` somewhere other than `DemoScene`.

Phone → head unit is new: open the `media_info` channel and send
`MediaPlaybackStatus` (0x8001) and `MediaPlaybackMetadata` (0x8003).

Tasks:

- Add the two media roots to `tools/proto-gen/src/main.rs`; regenerate; add a `ids::media`
  group to the hand-written module in `core/aap-proto/src/lib.rs`.
- Parse the `media_info` descriptor in `discovery.rs` (currently falls through the
  catch-all at `discovery.rs:351`) into a `MediaInfoChoice`, and add it to `Capabilities`.
- Add a `Step::OpenMediaInfo` to the projection sequence after input binding, with its own
  deadline, treated as **optional** exactly like the sensor channel — a refusal is recorded
  and skipped, never fatal. Ford advertises this channel but has never been asked to open
  it, so refusal is a live possibility.
- Send status on transport change and metadata on track change; rate-limit both.
- Add `AppEvent::Transport` and an `EventKind` for it; deliver to the app through the
  existing `event_payload` path; the app maps it onto its `MediaSession`.
- Draw the now-playing state in the owned scene as well, so the demonstration is legible
  even if Ford ignores channel 10.
- Report: `media_open`, `media_status_sent`, `media_metadata_sent`, `media_refused`,
  transport key counts, and the last state sent.

Verification: the pinned Prodigy receiver already ships `MediaStatusChannelHandler`, so
`tools/lab-receiver.sh` can decode and assert both messages; add a deterministic
`tools/hu-sim` case and a `LAB_SCRIPT=transport` that presses each keycode. Vehicle
evidence for **G6** is in §6.

Open risk: album art in `MediaPlaybackMetadata.album_art` is unbounded `bytes` on a
16 KiB-fragment transport. Cap it, or omit it in the first slice.

## 2. P5.2 — Sensor harvest, read-only

**Implemented and lab-verified, 18 September 2026** ([P5.2 lab results](p5-2-lab-results.md)).
The three bullets below were written before the slice and are what it did; two things
are worth adding after the fact. The `SensorEventIndication` field number *is* the
`SensorType` value for all 26, so the field-to-type mapping is the schema's own rather
than a guess. And "silent" had to be defined more narrowly than first written: a type the
head unit **refused** is not evidence that the vehicle lacks that sensor, so
`sensor_silent` lists only types whose subscription was accepted and which then sent
nothing.

The cheapest slice and a prerequisite for §5. All 26 sensor payload messages are *already
generated*; `projection.rs:1191` decodes the whole `SensorEventIndication` and then reads
only `driving_status` and `night_mode`, discarding the rest.

- Replace the fixed `RESTRICTION_SENSORS` pair at `projection.rs:41` with a configurable
  set, defaulting to the advertised intersection when a new `sensors=all` config key is
  given. Keep sensors opt-in and keep every sensor failure non-fatal.
- Extract and report every field the head unit actually sends, with per-type counts.
- Redact per spec §10: position is vehicle-identifying. Latitude/longitude must be
  **off by default** and behave like the existing raw-capture option — opt-in, bounded,
  app-private, excluded from ordinary exports.

This answers the question everything in §5 depends on: **does Ford populate LOCATION,
CAR_SPEED and GPS, or merely advertise them?** Advertised is not populated, and the project
has already been bitten once by a head unit advertising a capability it does not honour
(references.md, "A configuration can be advertised and not displayed").

Verification: Prodigy's `SensorChannelHandler` can be scripted to emit each type; vehicle
evidence is a report showing which of the nine arrive with values.

*Corrected on implementation:* Prodigy's handler models only night mode, driving status
and parking brake, so it cannot emit the other six. The lab uses a handler written for
it (`LabSensorHandler` in `tools/lab-receiver/receiver.cpp`) which still encodes with
Prodigy's own protobuf classes, so the decode on our side stays independent of our
generated Rust types. Vehicle evidence is still outstanding.

## 3. P5.3 — Audio out over USB

**Implemented and lab-verified, 18 September 2026** ([P5.3 lab results](p5-3-lab-results.md)).
What follows is what the slice was written from; five things are worth adding after the
fact, and the rest held.

- **The interleaving hazard was already closed, by construction rather than by luck.**
  `Session::send_on` fragments a whole message and appends every fragment to the output
  queue before returning, so nothing can be queued between them; the "queue media only
  while pending output stays below 256 KiB" policy was never the thing protecting this.
  The audio side adds a second guarantee anyway — a PCM buffer larger than one outbound
  fragment is refused — so an audio message is always a single BULK frame. Verified
  against Prodigy's own assembler with 36-fragment video keyframes and audio flowing.
- **The audio focus exchange needed four more vendored `.proto` files.** §0 said the
  schemas were all at the pin and inside the closure; the `oaa/audio/AudioFocus*` set was
  at the pin but *outside* it, since nothing imported it. Adding two roots pulled in four
  files and no new dependency.
- **Prodigy sends `AudioFocusResponse` without `granted`**, exactly as aasdk's copy of the
  message does, so the tolerance §3 asks for is exercised rather than theoretical.
- **`RELEASE` on a head-unit-initiated shutdown had to be added deliberately.** Every
  other stop path already funnelled through one place; a peer shutdown did not, and that
  is precisely the case where a dangling grant outlives the session.
- **Ford's audio configuration, measured 19 September.** All four channels carry one
  configuration: media (ch4 PCM, ch5 AAC) at 48000/16/2, speech (ch6 PCM, ch7 AAC) at
  **48000/16/1** — not the conventional 16 kHz this section assumed, so the "1024 samples =
  2048 bytes = 64 ms at 16 kHz mono" figure does not apply to this vehicle.
- **Ford's audio window is 40**, against 4 for video on the same head unit in the same
  session. Neither the 1 that openauto and mikereidis hardcode nor the 24 of "ackless
  mode". At 42.7 ms a buffer that is 1.7 s of credit, so §3's worry about a hard
  per-buffer round-trip budget does not bind here.
- **Ford refuses audio focus** (`LOSS`, `granted=false`), which is why G7 is unmet. The
  section assumed the request was the easy part and the pacing was the risk; it is the
  other way round on this vehicle. Two source defects the attempt exposed — a report that
  kept only the latest focus state, and a non-grant that tore the audio down permanently
  and released a grant never held — are fixed in 0.4.0-lab; see the lab results.
- **The request itself is right, read from the Android Auto 17.6 dex** (references.md,
  "Audio focus in Android Auto 17.6"). Google's client sends the same one-field body
  `{1: GAIN}`, and it gates the audio start indication behind the grant exactly as the
  ordering claim below says — which until now rested on aasdk alone and is now confirmed
  against a working client. It also treats a `LOSS` as authoritative rather than retrying,
  as 0.4.0-lab now does. **The one substantive difference left is *when*:** Android Auto
  asks only when an app on the phone takes Android audio focus, never at session setup.
  The focus message carries no channel id, so there is no separate media/speech request.

This is the slice that costs a core refactor. Audio channels reuse the AV machinery
exactly — `0x8000` setup, `0x8001` start, `0x8002` stop, `0x8004` ack — but the session
assumes a single stream throughout:

- `ProjectionState` holds one `video`, one `window`, one `outstanding`, one `session_id`
  (`projection.rs:246`).
- `Step` is a flat linear enum with one `AvSetup` and one `AwaitFocus` (`projection.rs:216`).
- Inbound dispatch is an `if channel == video … else if channel == input` chain
  (`projection.rs:879`).
- `pump_media` hardcodes the video channel as destination (`projection.rs:1598`).
- `MediaLink` is H.264-specific end to end; `parse()` would reject PCM (`media.rs:84`).
- `Manager` holds one `Arc<MediaLink>` per handle (`manager.rs:473`), and
  `nativeSubmitVideo` has no stream argument (`lib.rs:140`).

Shape of the refactor: `ProjectionState` holds a `Vec<StreamState>`; `Step` carries a
stream index; dispatch keys off a channel → stream map; `MediaLink` is generalised over a
unit codec so PCM bypasses the NAL parser. Start with **one** audio channel — Ford ch4,
PCM + MEDIA — because PCM needs no encoder at all and isolates the refactor from
`MediaCodec`.

### Audio focus

On the **control channel (0), not the AV channel** — `AUDIO_FOCUS_REQUEST` 0x0012 and
`AUDIO_FOCUS_RESPONSE` 0x0013, both already in our vendored `ControlMessageIdsEnum.proto`.
Audio focus is independent of video focus. The order to match is: request `GAIN`, wait for
the response, *then* send the start indication. A head unit's mixer may have no route open
until it has granted focus.

Enum values, read from the files at the pin:

- `AudioFocusType` (request, field 1, optional): `GAIN` 1, `GAIN_TRANSIENT` 2,
  `GAIN_TRANSIENT_MAY_DUCK` 3, `RELEASE` 4. Value 0 does not exist in the APK and is
  synthetic. **aasdk calls 3 `GAIN_NAVI`; same wire value, wrong name.**
- `AudioFocusState` (response, field 1, **required**): `INVALID` 0, `GAIN` 1,
  `GAIN_TRANSIENT` 2, `LOSS` 3, `LOSS_TRANSIENT_CAN_DUCK` 4, `LOSS_TRANSIENT` 5,
  `GAIN_MEDIA_ONLY` 6, `GAIN_TRANSIENT_GUIDANCE_ONLY` 7.
- `AudioFocusResponse` carries `optional bool granted = 2`, which aasdk lacks. Our
  validate-then-prost path tolerates it; the report should record it.

**We never duck our own media.** To play a guidance prompt on a speech channel, request
focus type 3 and the head unit lowers the media channel itself; our media PCM keeps flowing
at full amplitude and gets no stop indication. A `LOSS_TRANSIENT_CAN_DUCK` pushed at us is
informational. `RELEASE` is what restores the volume — and SYNC 3.4 owners report a
focus/duck state machine that wedges until the cable is pulled, so **always send `RELEASE`
and never leave a transient grant dangling.**

### Formats, pacing and framing

The phone side hard-validates audio configs: sample rate ∈ {48000, 16000}, **bit depth 16
only**, channels ∈ {1, 2}. Conventional assignments are media 48000/16/2 and speech
16000/16/1, S16LE. Buffer sizes converge from two independent sources — an empirical 2018
openauto observation and a 2026 APK trace: **2048 samples = 8192 bytes ≈ 42.7 ms at 48 kHz
stereo**, 1024 samples = 2048 bytes = 64 ms at 16 kHz mono.

At a 16 KiB maximum frame payload an 8192-byte media chunk **never fragments** — audio is
one BULK frame per indication, so no fragmenting audio path is needed. Do not batch past
~85 ms.

Send `media_codec_type = 1` (`MEDIA_CODEC_AUDIO_PCM`) in the setup request. This is the
same field Ford already proved it validates, by dropping the request without answering when
a configuration index was sent instead (report bcf4184b, `SETUP_TIMEOUT` after 10 s). Note
also that `AVChannelStartIndication.config` is an **internal format index in a different
numbering space** from `media_codec_type` — two small integers that mean different things.

### Two new hazards this slice introduces

**Fragment interleaving becomes possible for the first time.** Receivers in the aasdk
family abort a partially assembled message the moment a frame from another channel arrives
mid-fragment (`MESSENGER_INTERTWINED_CHANNELS`); our own inbound rule is the same
(references.md: any other frame on that channel meanwhile is `FRAME_INVALID`). Our video
keyframes *do* fragment — `video_largest_unit=20989` exceeds the 16 KiB payload — and today
nothing else competes for the output queue. Once a third AV channel exists, **a multi-frame
message must be atomic on the wire**. The existing "queue media only while pending output
stays below 256 KiB" policy is not sufficient. Ford's tolerance is untested; assume strict.

**`0x800B AUDIO_UNDERFLOW` is inbound.** HU → phone, one per underflow event, only between
the first audio packet and the stop indication, never replied to. It must be accepted on an
audio channel **without counting as an unhandled message**. It is also the most valuable
diagnostic in the whole audio path — unlike video, where references.md records that no wire
signal distinguishes a displayed stream from a discarded one, audio tells us when our
pacing is wrong. Caveat: modern Android Auto parses no payload for it while older schemas
define `required int32 session_id = 1`, so parse defensively. **No open-source head unit
sends it**, so the lab receiver needs an injection switch or the path will never be tested.

### Flow control, with one correction to our own record

Ford's real window is **4**: every vehicle report shows `av_setup_window=4` and
`video_outstanding_max=4`. The `max_unacked=10` at `docs/p4-lab-results.md:106` is the
**Prodigy lab receiver**, not the vehicle — worth correcting there, since it has already
been misread once. Audio windows are typically smaller still; openauto and mikereidis both
hardcode 1, and at a window of 1 every buffer must be acknowledged before the next goes
out, i.e. a hard ~42.7 ms round-trip budget per buffer over USB and TLS. Our video path
never had to meet a latency deadline because four frames of credit absorbed it. Our
existing "missing `max_unacked` means 1" default is right.

Unresolved: "ackless mode" (window pinned to 24, acks advisory, paced from a wall clock) is
reported to activate at PDK ≥ 5. **We cannot detect it** — `HeadUnitInfo` has no PDK or
protocol-version field and Ford sends none. If real Android Auto runs ackless on this
truck, a strict window-of-1 source will sound worse than it does. Settle by measurement;
do not implement speculatively.

### Unknown to close first

The report records `audio_configs.len()` but not its contents, so **Ford's sample rate, bit
depth and channel count are unrecorded**. Add them to the discovery detail line
(`discovery.rs:448`) — a few lines, no protocol risk — and harvest them on the next
connection before designing the audio pipeline.

### Verification

Prodigy's `AudioChannelHandler` accepts setup, start and stop and can dump received PCM;
registering it in `tools/lab-receiver/receiver.cpp` is roughly the same shape as the
existing video and sensor registration (~30 lines in a 774-line file). A round-trip check
that received PCM matches a known tone is achievable in the lab, as are an underflow
injection switch and an interleaving regression that fragments a video keyframe while audio
is flowing.

## 4. P5.4 — Microphone, deferred

`av_input` (Ford ch3) is the head unit's microphone streamed **to** the phone, so it needs
no Android `RECORD_AUDIO` permission — the audio never comes from a phone sensor. Schemas
are at the pin. Deferred anyway: there is nothing to consume it until there is a voice
feature, and spec §2.1 excludes voice assistants. Recorded here only so the direction is
not misremembered later.

## 5. P5.5 — An owned map renderer

**Decision: render our own map into the existing scene. OsmAnd's own UI is not projected.**

Projecting a third-party app's UI is blocked twice over. On the platform, an ordinary app
cannot place another app's activity on a virtual display (that needs signature-level
`ADD_TRUSTED_DISPLAY`) nor inject touch into it (`INJECT_EVENTS`, also signature). On this
project's own terms, spec §5.2 forbids MediaProjection, spec §4 forbids injecting events
into other applications, and spec §2.1 and plan line 11 exclude arbitrary app mirroring.

This matches APP07 as already written — "offline-capable OSM navigation using Android's
normal location APIs" — which never named OsmAnd.

Two alternatives were considered and rejected for now, recorded so they are not
re-litigated:

- **A Car App Library host driving real OsmAnd.** OsmAnd's `createHostValidator()` returns
  `ALLOW_ALL_HOSTS_VALIDATOR` when the application is flagged debuggable and an allowlist
  of Google-signed hosts otherwise, so a self-built debuggable OsmAnd would accept a
  third-party host, and binding an exported service needs no special permission. Our app
  would stay ordinary. Rejected on cost: it requires implementing a templates host and
  maintaining a private OsmAnd build.
- **An adb/Shizuku-granted trusted display with input injection.** Smallest code and
  fastest demonstration, but it breaks APP02 and spec §1 outright. Available as a
  timeboxed feasibility spike only, never as a shipped path.

Selection criteria for the renderer, to be settled by a spike before any engine is adopted
(spec §2.2 already requires this):

- renders offline vector data into a **caller-supplied EGL context or off-screen surface**,
  not only into a `View` in the window hierarchy — the target is the encoder's input
  surface, not the phone screen;
- sustains the selected configuration, currently 1920x1080@60;
- licence compatible with GPL-3.0-only distribution, and OSM/ODbL attribution satisfied
  on screen;
- **no runtime network access** (APP10), with map data distributed out of band;
- bounded dependency cost. This is the sharpest constraint and deserves stating plainly:
  `app/build.gradle.kts` currently declares **zero runtime dependencies** and the build is
  verified bit-for-bit reproducible offline. Any renderer breaks that property. The spike
  must measure APK size, ABI count, transitive AndroidX pull-in, and whether offline
  reproducible builds survive.

### Engine comparison (spike research, 18 September 2026)

| Engine | Licence | Off-screen into our EGL surface | Dependency cost | Verdict |
| --- | --- | --- | --- | --- |
| **VTM** (mapsforge/vtm) | LGPL-3.0 with §4(d)/(e) waived | **Yes, by design** | core has **zero** deps; native lib **45 KB** | **Recommended** |
| Organic Maps drape | Apache-2.0 | Possible but unbuilt | one monolithic `.so`, ~25 submodules | Months of surgery |
| MapLibre Native | BSD-2-Clause | Only by forking | Kotlin + AndroidX + OkHttp + Timber | Rejected |
| OsmAndCore | GPL-3.0-or-later | No public path | Qt5 + Skia + ICU + GDAL | Rejected |

**VTM is the recommendation, and the margin is large.**

- `org.oscim.renderer.MapRenderer` has **no Android imports at all**. It reaches the GPU
  only through the `GLAdapter.gl` interface and exposes exactly three hooks meant to be
  driven by hand: `onSurfaceCreated()`, `onSurfaceChanged(w, h)`, `onDrawFrame()`.
  `org.oscim.android.gl.AndroidGL` is a 1:1 shim over static `GLES20`/`GLES30` with no
  dependency on `GLSurfaceView`, `EGL`, `Activity` or `Context` — it needs only a current
  EGL context on the calling thread, which is exactly what the scene thread already has.
  `org.oscim.android.MapView` is a ~250-line convenience wrapper, not load-bearing
  architecture, and the working LWJGL desktop and WebGL backends prove the core is
  genuinely backend-agnostic.
- The integration is therefore **original glue, not a fork**: make our EGL context current
  on the scene thread, call `GLAdapter.init(new AndroidGL())` once, subclass
  `org.oscim.map.Map` (mirroring `AndroidMap` but posting to our render loop instead of
  `requestRender`), and drive the three `MapRenderer` hooks before `eglSwapBuffers`.
  Roughly the size of `MapView` itself. No VTM source changes.
- Dependency cost is compatible with the zero-dependency posture in a way nothing else is:
  `vtm` core declares **no dependencies**, `vtm-android` adds only `androidsvg`
  (Apache-2.0, no transitive deps, no AndroidX), `vtm-themes` adds only `vtm`, and
  `vtm-http` — the only networked module — is simply not included. The arm64-v8a native
  tessellator is **45 KB**, against roughly 14 MB for the Organic Maps monolith.
- Licence: LGPL-3.0 is GPLv3 plus additional permissions, so combining it into a
  GPL-3.0-only work is explicitly allowed, and VTM waives the §4(d)/(e) relinking
  constraints outright. The bundled tessellator is SGI Free Software License B 2.0, which
  is GPL-compatible.
- Data: reads Mapsforge `.map` offline with no network code in the read path, produced from
  an `.osm.pbf` extract by `mapsforge-map-writer` (an Osmosis plugin) running entirely
  offline.

**VTM's one real problem is distribution.** Maven Central publishing stopped after
**0.25.0** (April 2025) when OSSRH reached end of life; 0.26.0 through the current 0.30.0
(August 2026) are **JitPack-only**, which does not give the centrally hosted, permanently
checksummed artifacts our dependency verification assumes. Two workable answers: pin
0.25.0 from Maven Central, or vendor a JitPack build once and commit its checksums. Either
way this is a one-time manual vendoring step outside plain `mavenCentral()` resolution and
must be agreed with whoever owns `verification-metadata.xml`. It is friction, not a wall —
but it is the single thing to settle before committing.

### Routing: BRouter

Routing is a separate decision from rendering (spec §2.2), and the spike settles it
separately. **BRouter** (MIT) is the recommendation: pure Java with no NDK build, no
transitive dependency graph worth the name, and `.rd5` segment tiles that are by far the
smallest data footprint of the candidates. It is already the routing backend inside Locus,
OruxMaps and OsmAnd's own add-on, and its `.rd5` files can be built offline from an
`.osm.pbf` extract with BRouter's own Java tooling.

Two caveats. BRouter is a point-to-point route calculator, **not** a navigation engine — it
has no GPS snapping and no re-route-on-deviation, so both belong to us (every Android app
using it does the same). And it is historically bicycle- and foot-first, so its car profile
needs validating before commitment. Its `VoiceHint` model (turn command enum, turn angle,
distance to next, roundabout exit, street name via `MessageData`) is thinner than
GraphHopper's `Instruction`/`Sign` or Valhalla's `Maneuver`, so more instruction assembly
falls to us — but it maps onto the navigation channel's `ManeuverType`/`TurnSide` fields
without an impedance mismatch.

Rejected: **GraphHopper** — not on licence (Apache-2.0 is fine) but on architecture. Since
9.0 its default weighting pipeline runs through Janino, which **compiles and loads raw JVM
bytecode at runtime**; ART loads DEX only. Usable only by pinning to ~8.x, which is not a
foundation to build on. **Valhalla** — best maneuver model of the four, including explicit
`verbal_*` instruction fields, but Android support is a third-party wrapper and its
Boost/Protobuf/libcurl/Lua stack is a poor fit for a hermetic offline build. **OSRM** — no
maintained Android port; its one mobile adopter abandoned it.

**Rendering and routing cannot share a dataset.** Every engine re-derives its own
incompatible on-disk format from `.osm.pbf`, so the app ships two processed datasets per
region regardless of what we pick. Mapsforge `.map` plus BRouter `.rd5` is the pairing that
minimises that cost, and both are produced by offline Java tooling from the same source
extract.

### On the frame rate

The 1920x1080@60 target deserves questioning. The only public measurements of a vector map
driven into a car-projection surface come from a November 2025 Android Auto experiment with
MapLibre: ~8 fps copying bitmaps, ~11 fps with a custom no-View renderer, ~35 fps via
`Presentation` + `VirtualDisplay`. None reached 60. Those numbers do not transfer directly
— VTM drawing into our EGL surface avoids both the bitmap copy and the virtual display —
but they are the only evidence available, and a map is a far heavier scene than the current
test pattern. The encoder already separates rate control from configuration selection, so
**treat 30 fps as an acceptable outcome for the map** and measure before assuming
otherwise.

Worth noting as corroboration of the architecture: both MapLibre's maintainers and Organic
Maps' own Android Auto module use `VirtualDisplay` + `Presentation` for car projection
rather than a bare Surface handoff. That route is available to an ordinary app for its
*own* content and needs no MediaProjection — but it requires a `Window`, which a foreground
service cannot straightforwardly host. It is the fallback if driving VTM's `MapRenderer`
directly proves harder than the source reading suggests.

Position source: prefer the vehicle's own LOCATION/CAR_SPEED/GPS from P5.2, which needs no
Android location permission, and fall back to Android's location APIs. P5.2 must land first
so this is a measurement rather than an assumption.

Turn-by-turn to Ford's cluster over the `navigation` channel (ch11) is a separate slice
after a route exists; schemas are at the pin.

## 6. Proposed gates

Following spec §11.1. Each needs an operator observation on the factory head unit, not
phone-side counters (spec §10).

| Gate | Required evidence | Interpretation |
| --- | --- | --- |
| G6 media | A factory transport button changes app-owned playback state, and the app's now-playing text appears on the head unit's own interface. | Bidirectional media integration works. |
| G7 audio | App-owned audio is audible through the vehicle speakers over USB, with focus acquired and released cleanly. | Wired audio projection works. |
| G8 map | An owned map renders, pans and follows vehicle position on the factory display for 60 seconds. | The useful shell is viable. |

## 7. Spec and plan amendments this needs

Items 1–8 were written before any slice started. The P5.1 parts of 1, 2, 3, 4, 5, 6, 7 and
8 are **applied**; what remains unapplied is marked, and belongs to §§2–5.

1. **spec §2.1** — the POC exclusion list excludes "maps, routing, media libraries". P5 is
   outside the POC, but the boundary should be stated rather than left implicit.
   *Applied,* including what "media controls" does and does not mean.
2. **spec §5.2** — the manifest baseline is three permissions. P5.5 may add
   `ACCESS_FINE_LOCATION` as a *fallback* position source; P5.1 adds none. Any addition
   must be recorded with the APP09 rule ("offer integrations individually and explain the
   Android permission each needs"). MediaProjection, notification-listener and
   accessibility stay forbidden. *Applied, and changed by what §1 found:* P5.1 still adds
   no `<uses-permission>`, but the notification-listener exclusion is **narrowed rather
   than kept**, because Android offers no other route to another app's media session.
   MediaProjection and accessibility stay forbidden outright. The listener is permitted
   only for `getActiveSessions`, only with a callback-free service, only as a separate
   opt-in, and only where the feature degrades without it.
3. **spec §8.1** — "Do not automatically open all advertised audio, Bluetooth, or
   microphone services" and the sensor limit to DRIVING_STATUS and NIGHT_DATA. Both need
   amending for P5.1–P5.3, keeping the opt-in default and keeping Bluetooth and wifi
   closed. *Applied* as a general rule covering `media_info` and audio; the sensor set
   itself is *now also applied*, as P5.2's half of this item. What went in:

   - **spec §8.1**, the implemented-sequence paragraph: "limited to advertised
     DRIVING_STATUS and NIGHT_DATA" becomes a configuration choice of the restriction
     pair, every advertised type, or a named list, always bounded by the advertisement,
     with a set that intersects nothing leaving the channel closed, and with every sensor
     failure named as non-fatal.
   - **spec §8.3**: "subscribe only to supported types" already said the binding thing and
     was left alone; a paragraph was added that advertised is not populated, so a report
     must separate the types that carried values from the types that were accepted and
     then sent nothing, must leave refused types out of both, must keep an unsubscribed
     value rather than dropping it, and reports unreadable manufacturer payloads by
     length only.
   - **spec §10**: position joins raw frames as opt-in, app-private and out of ordinary
     shared reports, with the case recorded either way, and with what a redacted report
     still says — arrival, accuracy, altitude, speed, bearing, satellite counts.
   - **spec §12**, the P5.2 row already read "Configurable sensor set, per-type reporting,
     position redacted and opt-in", so it needed no change.
4. **spec §11.1** — add G6–G8. *G6 applied,* with the degraded case as an explicit pass
   condition. **G7 and G8 unapplied.** P5.3 shipped without applying G7: the wording in §6
   stands, and it wants the same degraded pass condition G6 has, because a head unit that
   refuses the audio channel, the setup, or focus is a recorded outcome rather than a
   failed gate. Also unapplied for P5.3: the spec §8.1 note that the phone hard-validates
   the audio format (so an advertised channel can still be unusable), the spec §10 note
   that `audio_underflows` is evidence about our pacing rather than about the vehicle, the
   spec §12 `P5.3` row, and a `### P5.3` section in `plan.md`. The full list is at the end
   of [the P5.3 lab results](p5-3-lab-results.md).
5. **spec §12** — expand the `P5 useful shell` row into the slices above. *Applied.*
6. **spec §13** — "Keep protocol compatibility modules separate from navigation/media
   integrations so changes in those integrations cannot silently alter handshake
   behaviour." P5.5's renderer dependency makes this load-bearing rather than advisory.
   *Applied,* naming the hand-off types that enforce it.
7. **plan.md** — add a `## P5` section; the deferred bullet at line 308 becomes this plan.
   Also refresh the stale header: line 3 still says 0.3.0-lab against a 0.3.7-lab build,
   and predates report 709ffa9c. *Applied.*
8. **docs/licenses.md** — every new dependency, and the renderer above all, must be added
   to the inventory with its exact licence. *Applied for P5.1,* which adds no dependency:
   only three more vendored `.proto` files from the commit already pinned. *Applied again
   for P5.3,* on the same terms: four more files from that commit for the audio focus
   exchange, no new import, no dependency. Still the binding constraint for P5.5.

Unchanged and non-negotiable throughout: no GMS, gearhead, root, hidden APIs, signature
permissions, or copied OEM keys (APP02); lab evidence reported separately from factory
evidence; implementation tasks stay unchecked until vehicle evidence exists.

## 8. Open questions

- ~~Will Ford open the `media_info` channel on request?~~ **Yes, and it honours it**
  (19 September): both it and the PCM `MEDIA` audio channel open on request, the audio AV
  setup is accepted, and the operator saw the track and owning application on Ford's own
  interface — **G6 met**.
- ~~Which of the nine advertised sensors carry real values?~~ **Seven of nine**
  (19 September): all but FUEL_LEVEL and TRAILER, which are accepted and silent. New
  question in its place: **what are the units?** `bearing`, `satellites_used` and `gear`
  all carry values that do not read sensibly against the schema's names.
- ~~Does Ford honour `MediaPlaybackMetadata` at all?~~ **Yes** (19 September, G6).
- ~~What `AudioConfig` does Ford actually advertise on channels 4–7?~~ **Answered**
  (19 September): 48000/16/2 on both media channels, 48000/16/1 on both speech channels.
- ~~What audio `max_unacked` does Ford send?~~ **40** (19 September), against 4 for video.
  The guess that audio windows are smaller than video ones is wrong on this head unit.
- ~~**Why does Ford refuse audio focus to this source?**~~ **Answered: it grants.** The
  third attempt was granted and audible. Why the *first* attempt was refused remains
  unexplained and is now a curiosity rather than a blocker — three runs since have all been
  granted, so no workaround is warranted.
- ~~*(earlier reading)*~~ **It does not always.** The
  second attempt was granted; the source's own field-2 gate discarded it. What is *not*
  established is why the answer changed between two attempts half an hour apart, or what
  its inputs are — so `audio_focus_sequence` is the record and no policy should be inferred
  from a single run. The remaining candidates below were written while the answer looked
  constant and are kept only until a run with 0.4.1-lab says whether any of them matter. The dex read eliminated two candidates — the request body is identical to
  Android Auto's, and Android Auto also withholds the start indication until the grant, so
  neither a malformed request nor a wrong order explains it. What remains: Ford grants
  media focus only once the operator selects the projection as the audio source on its own
  interface; the request arrives ~47 ms after the video start and the head unit is not
  ready; or an ordinary source is not granted media focus on this firmware. The first is
  the leading one and costs nothing to test, because Android Auto never asks speculatively
  either — it asks on behalf of an app that is playing, which on this head unit is
  plausibly also the point at which the driver has chosen the source.
- Is real Android Auto running ackless audio on this truck? We have no way to detect PDK
  version from what Ford sends, so this is answerable only by comparing our audio quality
  against Google's client on the same vehicle.
- Does Ford tolerate a fragmented video keyframe interleaved with audio frames, or does it
  abort assembly like the aasdk family does?
- What album-art size limit does Ford accept? Still unasked: this source sends none.
- Can VTM be pinned in a way `verification-metadata.xml` accepts — Maven Central 0.25.0, or
  a vendored and checksummed JitPack build of 0.30.0? This is the one decision blocking the
  renderer choice.
- What frame rate does VTM actually sustain drawing a real map into the encoder surface at
  1920x1080? Assume 30 fps until measured.
- Is BRouter's car profile good enough for turn-by-turn, given its bicycle/foot heritage?
- How much storage does a usable region cost with two datasets — mapsforge `.map` for
  rendering plus BRouter `.rd5` for routing — and how does the user install them, given the
  app has no INTERNET permission?

## 9. Research status

The audio protocol material in §3 is well sourced — independently corroborated across
aasdk, openauto, aa-proxy-rs, open-headunit and the upstream APK traces, and every enum
value quoted here was read directly from the files at the spec pin rather than taken from a
summary. One claim was checked and **rejected**: a secondary source attributed
`max_unacked=10` to this vehicle, which is actually the lab receiver's value.

§5's engine comparison was completed on 18 September 2026 across five parallel
investigations (MapLibre Native, VTM, OsmAndCore, Organic Maps drape, and offline routing).
Findings are sourced to upstream source files, published POMs and licence files rather than
to summaries, and the VTM architecture claims in particular were read out of
`MapRenderer.java`, `AndroidGL.java` and `MapView.java` directly. The 45 KB native library
size was verified by unpacking the published arm64-v8a natives jar.

Still not researched: the precise Android platform limits on virtual displays, activity
launch on secondary displays, and input injection. That work was cut short and never
finished. It does **not** affect the §5 decision, which rests on this project's own
constraints in spec §§1, 4, 5.2 and 2.1 rather than on what the platform would permit. It
*would* matter if the `Presentation` + `VirtualDisplay` fallback noted in §5 is ever taken
seriously — specifically whether a foreground service can host a `Presentation` without
`SYSTEM_ALERT_WINDOW`, which is unresolved here and would need settling first.

Unverified and worth treating as such: the audio buffer sizes and the "ackless mode"
behaviour in §3 come from secondary analysis of decompiled Android Auto, not from our own
captures. BRouter's car-profile maturity in §5 was not assessed in depth.
