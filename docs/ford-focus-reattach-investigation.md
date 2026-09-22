# Ford SYNC 4 re-attach investigation: why the "Android Auto" tile switches to Gearhead but not to us

**Resolved 2026-09-19. H3 confirmed; H1, H2, and H4 killed.** The tile was a no-op
because this source only *paused* the stream on focus loss instead of sending a
formal AV stop indication, so Ford went on believing the projection was foreground
and its own tile had nothing to switch to. The fix is `stop_video_stream` /
`restart_video_stream` in `core/aap-session/src/projection.rs` (0.4.5-lab,
versionCode 21); see §7 for the resolution and §8 for the closing provenance
update. `spec.md` §8.2 has already been amended to describe it. The sections below
are preserved as written during the investigation, including the hypotheses that
turned out to be wrong — they record real evidence and the reasoning that
correctly triangulated H3 as the answer.

Date: 2026-09-19. Evidence: `~/.cache/openauto-android/gearhead-2026-09-19/`
(`gh-session.log`, `gearhead-car.log`, `gearhead-channel-opens.txt`, four
`report-*.txt`), plus the dex extract
`~/.cache/openauto-android/gearhead-2026-09-19/dex-video-focus-stop-start.txt`
added when H3 was settled, and vehicle report `c3ea4fd6` (0.4.5-lab) added when
the fix was confirmed. No code or default behaviour was changed by the original
investigation; the fix itself was implemented and confirmed afterward, as
recorded in §7.

## 1. The question

After Ford takes the screen (Audio page, Settings, reversing camera), our
projection never returns; the taskbar "Android Auto" tile is a no-op until a
replug. Gearhead 17.4 on the same vehicle, same day, same gesture, recovers.
Facts 1–7 and the two refuted fixes (refocus; encoder rebuild on regain,
`report-3f9688b7-truck-rebuild.txt`) are taken as established from the
hand-off and are not re-derived here.

## 2. Gearhead's session, reconstructed from `gh-session.log`

All times 09-19, pid 8756. USB attach 16:10:27.854; version negotiated
1.6→1.7 at 28.153; TLS peer `OU=10,O=Ford` serial 0x208 at 28.205.

Between connect and first display (the window the brief asked to mine):

| time | line | note |
|---|---|---|
| 28.528 | `CAR.GAL.GAL.LITE: onChannelOpened: 0` | control |
| 28.660 | `CAR.GAL.AUDIO.LITE: sent audio focus request: AUDIO_FOCUS_RELEASE` | unsolicited RELEASE at startup; Ford's reply is garbage and times out at 29.162, Gearhead force-continues |
| 28.705–.714 | `onChannelOpened: 3,1,2,4,8,6,9,10,9,11,12` | ten channels up front (9 appears twice); 5 and 7 (codec-4 audio) skipped |
| 28.713 | `CAR.GAL.VIDEO.LITE: Initialize video source flow controller with max unacked allowed: 4` | once per connection (fact 5) |
| 28.722–.755 | `Creating MediaSourceFlowController for AUDIO_STREAM_MEDIA/GUIDANCE`, `startSystemSoundStreaming MEDIA` and `TTS`, `Start capturing system audio ... 48000` | **real audio streams start immediately and run for the whole session**, capturing silence |
| 29.156 | `CAR.WM: Setting display configuration id ... to 0bf170cc-...` | Ford's first display-config id exists before any focus or video |
| 29.492 | `CAR.SERVICE: InitialVideoFocus: Disabled by setting.` | Gearhead never requests video focus (matches docs/references.md) |
| 29.726 | `CAR.BT.SVC.LITE: Sending pairing request using method: 2` | **a pairing request goes out on channel 9**; `CAR.BT.LITE` walks its FSM to `STATE_HFP_CONNECTED` at 31.200 |
| 41.662 | `CAR.GAL.VIDEO.LITE: VideoFocus gained unsolicited=true` + `Input Focus Gained` | first grant, **13.5 s after connect**; no video had been sent before this |
| 41.664–42.270 | `doHandleVideoFocusGained` → new MediaCodec → `codec config 30` | first video bytes at 42.270 |
| 42.656 | `UpdateUiConfigRequest # xmu@4497dbfd` | Ford's 0x8009, ~0.4 s **after** the codec config |
| 42.666 | `Setting display configuration id ... to 5621c9f5-...`; `sendUpdateUiConfigReply` | Ford mints id #2; compositor refuses to draw windows carrying the old id until they re-attach |

Loss and regain (the tile cycle):

| time | line | note |
|---|---|---|
| 52.409 | `VideoFocus lost unsolicited=true transient=false` + `Input Focus Lost` | user on Ford UI |
| 52.410–.502 | `doHandleVideoFocusLoss`, `stopComposition`, `surface changed null`, windows torn down | encoder stops; **no GAL stop message is logged** (absence of a log line is not proof none was sent — Gearhead does not log per-message sends on ch 1) |
| 54.321 | `VideoFocus gained unsolicited=true` | **1.91 s after the loss** |
| 54.322–54.750 | `doHandleVideoFocusGained` → new encoder → `codec config 30` at 54.750 | fact 6 |
| 54.953 | `UpdateUiConfigRequest # xmu@...` | Ford's second 0x8009, 632 ms after the grant, 203 ms after the fresh codec config |
| 54.995–55.020 | new display config id `4884587a-...`; `sendUpdateUiConfigReply`; `GLCOMPOSER ... Skipping draw` until windows adopt the id | recompose completes; picture back |

Session ends 16:11:00.909 with EIO (unplug). Whole session ≈ 33 s.

## 3. Our sessions against that

From the four reports (all `profile=match-1.7`, `video_focus_requests=0`):

- `report-34c4a067-truck.txt` (0.4.3, **sensors=all**):
  `video_focus_sequence=45:1:true:open-input,5263:2:true:paused,6918:1:true:resumed`,
  `ui_config_updates=1`, tile dead. Loss→regain gap **1655 ms**.
- `report-6d6fbb29-truck.txt` (0.4.2, sensors=none): losses=1 regains=1,
  `ui_config_updates=1`, refocus armed (20 s) and never fired.
- `report-3f9688b7-truck-rebuild.txt` (0.4.4, rebuild on regain **on**):
  gap **1495 ms** (6044→7539), `projection_rebuilds=1`, fresh SPS/PPS+IDR
  after the regain (`video_configs_sent=4`, `video_dropped_awaiting_keyframe=34`
  ≈ 570 ms of rebuild latency), all acked, `ui_config_updates=1`, tile dead.
- `report-38e24996-drive-leg2.txt` (10-minute drive, zero focus events):
  steady-state projection is healthy for 596 s — decode/ack/keepalive paths
  are not suspect.

What is now demonstrably identical between us and Gearhead:

- No video before the unsolicited grant; no video focus requests ever.
- Silence on the video channel during a loss (we `pause()`; Gearhead tears
  the encoder down).
- Fresh encoder + fresh SPS/PPS + IDR on the regain (since 0.4.4).
- Keepalives answered throughout; `unhandled_messages=0`; every ack accounted.

And the sensor hypothesis is dead: 34c4a067 had all nine advertised sensor
types subscribed and streaming (86 events) and the tile was still dead.

## 4. An ambiguity that matters: is our 1.5 s regain automatic, or the first tap?

The hand-off treats fact 1 ("Ford volunteers focus back on its own") as
established from the two 1.5–1.7 s gaps. Note, however, that **Gearhead's gap
was 1.91 s** — the same order. Two readings fit all the data we have:

- **Reading A (fact 1 as stated):** Ford's focus layer auto-restores
  PROJECTED ~1.5–2 s after a native takeover, to Gearhead and to us alike.
  For Gearhead the restore is followed by a recompose (0x8009, new display
  config id) and the panel returns; for us the recompose never starts. The
  tile is then a no-op because Ford's model already says AA is foreground.
- **Reading B:** the regain *is* the first tile press (or Ford's Audio page
  is transient by design). Then Ford's focus layer heard the tap and granted
  — and the failure is again that the recompose step never runs for us.

Both readings converge on the same discriminating question — **why does
Ford's HMI recompose (0x8009 + new display-config id + surface switch) after
a regain for Gearhead and not for us** — but they differ in what to log next
time. The next vehicle run should settle it for free: after inducing the
loss, do not touch the tile for 30 s. If `video_focus_sequence` still shows a
~1.5 s regain, Reading A is proven; if the regain waits for the first tap,
Reading B is. (Costs nothing; see §6.)

## 5. What is left that differs — hypotheses, ranked (as written during the investigation)

The video path is exhausted: stream, acks, focus modes, encoder lifecycle,
configs and keyframes are now equivalent and the bug persists. What remains
different is the **shape of the session outside the video channel**:

| # | Gearhead does | We do |
|---|---|---|
| D1 | Opens 3, 4, 6, 9, 10, 11, 12 in addition to 0/1/2/8 | open 1, 2 (+8, 10 sometimes) |
| D2 | Runs real audio services: AV setup on 4 and 6, flow controllers, continuous 48 kHz PCM (silence) for the entire session | no audio at all (`audio_requested=false`) |
| D3 | Sends `AUDIO_FOCUS_RELEASE` on the control channel at startup | never touches audio focus |
| D4 | Sends a Bluetooth pairing request (method 2) on channel 9; ties the session to the HFP link | never opens 9 |
| D5 | (possibly) formally stops/starts the AV stream around focus — **not observed**, merely not excluded: fact 5 only proves the flow controller and ack window survive, which a stop/start *indication* pair would not disturb | hold the stream "started" across the loss; `video_stop_indication_sent=false` until teardown |

**H1 — service-coverage gating (channels), prior ~40%.** Ford's GAL/video
subsystem is promiscuous (acks anything, re-grants focus), but the HMI layer
that owns the taskbar tile and the display-config recompose treats a session
without the expected services as not re-enterable; the *initial* display
after connect goes through a different (auto-launch) path, which is why first
projection works. Explains facts 2, 3, 4 directly; neutral on fact 1;
consistent with 5–7 and both refuted fixes. The `docs/plan.md` note that
opening all channels "changed nothing" was against the blank-picture bug
(and the working 709ffa9c run needed no extra channels *for first display*)
— it says nothing about tile re-entry.
*Confirm:* tile works with `open_all_channels=true` → `ui_config_updates>=2`.
*Kill (partially):* tile still dead with `projection_extra_opened>=7` — this
kills only the bare-open variant; D2/D4 (services that *do* something) survive.

**Killed outright, 2026-09-19.** The confirmed-fix run (report `c3ea4fd6`, §7) has
`projection_extra_opened=0` — no extra channels, bare or otherwise — and the tile
worked (`ui_config_updates=2`). D2/D4 were never exercised either. Channel coverage
explains nothing here; the working run's channel set is identical to every failing
run's.

**H2 — audio-source arbitration, prior ~25%.** The canonical failing gesture
starts on Ford's **Audio** page. On SYNC, "Android Auto" is also an audio
source; Ford's source manager may refuse to complete the switch back to a
projection session that exposes no audio endpoints, wedging the HMI flow that
the tile triggers. Weaker for the reversing-camera variant of the bug (unless
Ford funnels every native takeover through the same source-restore flow).
*Confirm/kill:* enable the existing `audio_out` checkbox (real ch-4 setup +
start + PCM), reproduce the gesture; `audio_open=true audio_buffers_sent>0`
is the treatment check, `ui_config_updates>=2` / the panel is the outcome.
Note references.md: Ford refused our `AUDIO_FOCUS_REQUEST` GAIN before —
Gearhead's model is capture-driven and sends only RELEASE at idle; the test
should not gate audio streaming on a granted GAIN.

**Killed outright, 2026-09-19.** The confirmed-fix run (report `c3ea4fd6`, §7) has
`audio_requested=false audio_open=false` throughout — no audio channel was ever
opened — yet the tile worked. Whatever gated the tile, it was not audio-source
arbitration. (Ford did volunteer an unsolicited `audio_focus_state=1 (GAIN)` in
that same run, `audio_focus_volunteered=1`, which it had never done before — an
interesting new fact, but downstream of the fix, not a precondition for it.)

**H3 — no formal stream stop on focus loss, prior ~20%. CONFIRMED, 2026-09-19.**
From Ford's side, the only wire difference between our "paused" and Gearhead's
loss handling would be an AV stop indication (and matching start on regain) —
*if* Gearhead sends one; its logs neither show nor exclude it. If Ford aligns
video focus to the stream state, a never-stopped stream explains the
spurious 1.5 s re-grant (Reading A), the tile no-op (Ford: "already
foreground"), and the missing 0x8009 (no transition to display). Requires a
small core change (send `av::STOP_INDICATION` on NATIVE, `START_INDICATION`
on the next PROJECTED, same session id): **not implemented — do not build
this until H1/H2 are measured and the §4 ambiguity is resolved**, because
Reading B would leave this hypothesis with nothing to explain.
*Confirm:* after the change, Ford stops re-granting while the user stays on
its UI (`video_focus_regains=0` during the no-touch window), and the first
tap produces a fresh grant plus `ui_config_updates>=2`.
*Decisive kill available without a vehicle:* the Gearhead 17.6 APK in the
project root — the P5.3 dex-reading method (references.md §"Audio focus in
Android Auto 17.6, from the dex") applied to `doHandleVideoFocusLoss` will
show whether any GAL message is sent on video focus loss. If none, H3 dies
at the desk.

**This is exactly what settled it — see §7 for the full dex chain and the
vehicle confirmation.** `doHandleVideoFocusLoss` (`Livn;->x(ZZ)V`) does call a
stop path that sends 0x8002, and the regain sends 0x8001 with a new session id;
the fix that mirrors this on our side made the tile work
(`ui_config_updates=2`, report `c3ea4fd6`). One correction to the framing above:
the confirm condition as stated (`video_focus_regains=0` during a no-touch
window) was never tested directly — the vehicle run did not include a
deliberate no-touch wait — but the tile itself now works, which is the outcome
that mattered, and it does so with a video focus sequence
(`10366:1:true:started,13036:2:true:paused,14205:1:true:resumed`) that still
shows Ford re-granting focus automatically (Reading A) after only ~1.2 s, same
as before the fix. The fix does not depend on which reading of §4 is right;
it only requires that the head unit eventually re-grants focus and expects a
formal stream restart at that point, which the fix now supplies either way.

**H4 — Bluetooth channel / pairing linkage, prior ~10%.** SYNC couples
projection to the HFP device; Gearhead's ch-9 pairing request is how the
session names its phone. The phone *is* already paired and HFP/A2DP-linked to
the truck, so the marginal claim is only that the association must be made
inside the GAL session. Bare-opening ch 9 (H1's test) does not send the
pairing request; a negative H1 result leaves this alive but it needs code.

**Killed outright, 2026-09-19.** The confirmed-fix run (report `c3ea4fd6`, §7)
never opened channel 9 or sent a pairing request (`projection_extra_opened=0`),
and the tile still worked. Bluetooth/pairing linkage was never the gate.

**Killed already, do not revisit:** sensor coverage (34c4a067); focus
re-request (unreachable + real AA never does); encoder rebuild alone
(3f9688b7); reply-to-0x8009 correctness at steady state (the one 0x8009 we
get per session is answered and acted on — the +18980 ms content-rectangle
move worked).

## 6. Recommended next vehicle test (single, cheapest, decisive) — superseded

**Not run as planned.** The desk work flagged as available "independently of
the truck" at the end of this section — the H3 dex check on
`doHandleVideoFocusLoss` — was done first and was decisive by itself, so
Replug 1 and Replug 2 below were never needed and the §4 no-touch
disambiguation was never separately tested. Kept for the record; see §7 for
what was actually done instead.

One parking session, no code change, no install. App as shipped (0.4.4-lab).

**Replug 1 — H1 plus the §4 disambiguation in the same run:**

1. In `ConnectionActivity`, tick **"open all channels"** (config key
   `open_all_channels`; requires stop-after=projection, as wired).
2. Connect, confirm projection, go to Ford's Audio page.
3. **Touch nothing for 30 s.** Then tap the Android Auto tile a few times.
4. Pull the report. Read, in order:
   - `projection_extra_opened` — must be ≥ 7 (treatment applied; `_refused`
     tells us which channels Ford would not open, which is evidence either way).
   - `video_focus_sequence` — a regain ~1.5 s after the loss despite the
     no-touch window proves Reading A (Ford's re-grant is automatic); a
     regain only after the first tap proves Reading B.
   - `ui_config_updates` — **the answer**: ≥ 2 means Ford recomposed for us
     (bug fixed or fixable by channel coverage); still 1 with the tile dead
     kills bare channel opens.

**Replug 2 (same visit, ~2 min) — H2:** untick open-all, tick **`audio_out`**,
repeat the gesture and the no-touch window. Read `audio_open`,
`audio_buffers_sent`, then `ui_config_updates` / the panel. (If replug 1
fixed it, use replug 2 to bisect: open-all off, audio on.)

Desk work that can proceed independently of the truck: the H3 dex check on
`doHandleVideoFocusLoss` in the Gearhead 17.6 APK.

## 7. Resolution: the dex chain and the vehicle confirmation (2026-09-19)

**Method.** Same as the P5.3 audio-focus dex work and the 0x800A reply-body work
(docs/references.md): `dexdump -d` over `classes.dex` in the Android Auto 17.6
`base.apk` in the project root, classes and methods located by the literal
strings they log rather than by assumed names. Nothing proprietary was copied
into the project. Full disassembly saved at
`~/.cache/openauto-android/gearhead-2026-09-19/dex-video-focus-stop-start.txt`.

**The chain.**

- `Livn;->x(ZZ)V` is `doHandleVideoFocusLoss` (parameters: unsolicited,
  transient, both booleans). It tears down the display sink (posts to a
  handler, waits on a semaphore with a 15 s timeout, logs "handling display
  removal timed-out" if it fires), then unconditionally calls
  `Ljdr;->x()V` at the end — `Ljdr` being `MediaSourceEndPoint`.
- `Ljdr;->x()V` is the stop path. It carries the literal
  `"Trying to stop Media before starting, ch:%d"` on the early-return branch
  (nothing to stop). On the real path it: saves the current session id
  (`Ljdr;.f`) into `Ljdr;.d`, **increments** `Ljdr;.f` for next time, calls
  `Ljdr;->w(Lxpj;)V`, and clears its "started" flag (`Ljdr;.g`).
- `Ljdr;->w(Lxpj;)V` sends message id **0x8002** — the constant loads as
  `#float 4.59206e-41 // #00008002` and is passed straight to
  `Ljbq;.k:(ILabpy;)V`, the same send primitive `Ljdr;->v` uses for the start
  message. This is `AV_CHANNEL_STOP_INDICATION`.
- The regain path logs `"start MediaSourceEndPoint sessionId: %d"`, reads the
  current (**new**) session id out of `Ljdr;.f`, writes it into `Lxpi;.c` with
  `bitField0_ |= 1` (field 1, `session`), and calls `Ljdr;->v(Lxpi;)V`, which
  sends **0x8001** (`AV_CHANNEL_START_INDICATION`) — the sibling of `->w` in
  the same disassembly, `const v0, #00008001` into the same `Ljbq;.k` send.
- Distinct from both: `Livn;->x` waits on `Livn;.w`, a semaphore, and reads
  `Livn;.I` for its own timeout logging — that machinery belongs to display
  teardown, not to the AV stream, and is orthogonal to the stop/start pair.

**The flow-controller misreading, and why it mattered.** An earlier reading of
`gh-session.log` (this file's §2, fact 5 as inherited from the hand-off) found
`"Initialize video source flow controller with max unacked allowed: 4"` logged
exactly once per connection, and concluded from that single initialisation
that Gearhead never restarts the AV stream around a focus transition — only
pauses it, same as this source did. That conclusion does not follow: the
per-connection flow controller (window size, acknowledgement accounting) is a
channel-level object that has no reason to be torn down by a stream-level stop
indication, and the dex confirms it survives untouched — `stop_video_stream` in
this source's own fix likewise leaves the channel and its acknowledgement
window alone (see the function doc comment: "this stops a stream, not a
channel"). A single flow-controller initialisation is consistent with any
number of stop/start indication pairs during the session; it excludes nothing.
Treating "initialized once" as "never restarted" cost a round of investigation
built on H1/H2/H4 before the dex was read. The general lesson: a resource's
lifecycle log line proves things only about that resource, not about the
higher-level protocol event it happens to survive.

**The fix (0.4.5-lab, versionCode 21).** `stop_video_stream` and
`restart_video_stream` in `core/aap-session/src/projection.rs`, mirroring the
dex chain above: on video focus NATIVE / NATIVE_TRANSIENT / NONE, send 0x8002
(`AvChannelStopIndication`), clear the queue, zero `outstanding`, and increment
the video session id — matching `Ljdr;->x()V` incrementing `Ljdr;.f`. On the
next PROJECTED, send 0x8001 (`AvChannelStartIndication`) carrying the new
session id, re-arm the first-unit deadline, and emit fresh configuration plus
an IDR — matching `Ljdr;->v` reading the incremented id out of `Ljdr;.f`. The
previous session id is kept as `prev_session_id` (the field name is a direct
echo of `Ljdr;.d`, which is where Android Auto stashes the outgoing id before
bumping it) so that acknowledgements for units already in flight when the stop
went out are recognised on arrival and counted as
`video_acks_previous_session` rather than as wrong-session or invalid. `spec.md`
§8.2 has already been amended to describe this behaviour ("A focus transition
stops and restarts the stream rather than pausing it").

**Vehicle confirmation.** Report `c3ea4fd6` (2026-09-19T22:35Z, 0.4.5-lab,
same F-150/SYNC 4.0 as every report above):

- `ui_config_updates=2 ui_config_responses=2` — previously always 1 in every
  report in §3. The tile worked: Ford recomposed for us on the regain, the
  thing that never happened before this fix.
- `video_focus_stop_indications=1 video_focus_restarts=1` — the stop/start
  pair fired exactly once, matching the one loss/regain cycle in the session.
- `video_start_session=1` — nonzero for the first time in any report cited in
  this investigation; previously always `-` or 0 for the whole connection,
  because the stream was never formally started twice.
- `video_focus_sequence=10366:1:true:started,13036:2:true:paused,14205:1:true:resumed`
  — loss at +13036 (relative to native-session start), regain at +14205, a gap
  of 1169 ms. This is the same order as every earlier gap in this
  investigation (§3's 1495–1911 ms) and confirms Reading A from §4 still
  holds under the fix: Ford re-grants focus automatically, without a tap, and
  the fix does not depend on which reading was correct — see the note added
  to H3 in §5.
- Event log (session-relative from `[events]`): focus lost `+18469ms` (mode=2,
  NATIVE), focus regained `+19639ms`, and Ford's second
  `UpdateUiConfigRequest` (the recompose) landed at `+20212ms` — **573 ms
  after the regain**. Gearhead's equivalent gap in §2 was `54.953 - 54.321 =
  632 ms`. Ford ran the same post-regain re-attach sequence for this source
  that it runs for Google's own client — the discriminating question §4
  posed ("why does Ford's HMI recompose after a regain for Gearhead and not
  for us") now has its answer: it does, once the stream is properly
  stopped and restarted, not merely paused.
- `video_acks_wrong_session=0 video_acks_previous_session=0` and
  `video_timestamp_regressions=0` (in `[projection]` of the same report) — the
  session-id bookkeeping worked cleanly; nothing was misattributed across the
  stop/start boundary, though in this particular run no acknowledgement
  happened to still be in flight when the stop went out, so
  `video_acks_previous_session=0` demonstrates the counter did not
  mis-fire rather than that it fired correctly under contention.
- Also new in that run, incidental to the fix rather than caused by it:
  `input_events=2` (both correctly dropped as unfocused,
  `input_dropped_unfocused=2`) and `audio_focus_volunteered=1` — Ford
  volunteered an unsolicited audio-focus GAIN, which it had never done in any
  earlier report.

**What is not established from this run.** Which of Ford's own UI checkboxes
or state the operator had exercised before the loss/regain cycle is not
recorded in the report (the report records protocol events, not head-unit
screen state). What *is* proven from the run's own counters is that neither
`open_all_channels` nor the (now-removed) video-refocus experiment was active:
`projection_extra_opened=0` and `video_refocus=off video_refocus_requests=0`.
So the fix was confirmed in isolation, not in combination with either of the
other two variables this investigation considered and ruled out.

**Ruled out along the way, recorded for completeness:**

- *Re-requesting video focus after a loss.* Implemented behind a flag at one
  point, but never reachable in practice — Ford restores focus in roughly
  1.5 s on its own, faster than any reasonable re-request deadline, and real
  Android Auto never requests video focus on this head unit at all (§2,
  `InitialVideoFocus: Disabled by setting.`). The flag has since been removed.
- *Rebuilding the encoder on focus regain.* Mirrors what Gearhead's
  `doHandleVideoFocusGained` does (it re-creates the codec on every grant) and
  is retained in this source's code, but on its own it did not fix the bug:
  report `3f9688b7` (§3) had `projection_rebuilds=1` and the encoder rebuilt
  cleanly, yet `ui_config_updates` was still 1 and the tile was still dead.
  The stop/start indication pair was the missing piece, not the encoder.

## 8. Proposed amendments (governed documents — not edited)

- `plan.md`: the note that opening all channels "changed nothing" should be
  scoped to the blank-picture investigation, and a line added that channel
  coverage vs. tile re-entry is under test (this file). **Update:** channel
  coverage was retested against tile re-entry in the confirmed-fix run
  (`projection_extra_opened=0`, tile worked) and is not the cause; see the H1
  entry in §5 and the amendment text drafted alongside this investigation.
- `spec.md` §8.2: if H3 is ever confirmed, the focus rules need an amendment
  ("on NATIVE, the source sends an AV stop indication...") before any default
  changes; nothing to amend today. **Update:** H3 is confirmed and `spec.md`
  §8.2 has already been amended accordingly.

## 9. Provenance

- Proven (cited above): everything in §2–§3, and the dex chain and vehicle
  report in §7.
- Inferred: the two-subsystem model of Ford (GAL video layer vs. HMI
  recompose), the §4 ambiguity (still not independently re-tested after the
  fix — see §7's confirmation note on Reading A), and the hypothesis ranking.
- Guessed: the priors in §5, now moot since the outcome is settled.
