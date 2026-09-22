# P5.3 lab results — audio out over USB — 18 September 2026

> **G7 IS MET** (19 September 2026, report `7f869585`). The operator heard the app's own
> 440/880 Hz chord from the vehicle speakers: 958 buffers, 40.8 s, **zero underflows**,
> zero drops, focus acquired and released cleanly. It took three attempts and two source
> defects; the thread is below and in
> [references.md](references.md#g6-and-g7-pass-and-p52s-vehicle-answer-19-september-2026).
> The same runs supplied **P5.2's outstanding vehicle evidence** as a by-product.

Lab evidence only below this line. **G7 was not met by anything in the lab**: the gate is
"app-owned audio is audible through the vehicle speakers over USB, with focus acquired and
released cleanly", and that needed an operator in the truck. What the lab shows is that the multi-stream
refactor works, that the audio handshake is the one the plan described, that the PCM
arriving at an independent receiver is the PCM this source sent, and that the two hazards
the slice introduces — fragment interleaving and `0x800B AUDIO_UNDERFLOW` — are handled.

> **Update 3, 19 September 2026 — third vehicle attempt: G7 met.** See the banner above.
>
> **Update 2, 19 September 2026 — second vehicle attempt: Ford granted focus and this
> source refused it.** `audio_focus_state=1 (GAIN)` with the response's optional field 2
> set false — the same value Ford sends when it *refuses*, so that field cannot mean
> `granted`, and gating on it cost a real grant. It is the **`unsolicited`** flag, since
> confirmed against the Android Auto 17.6 and 15.7 dex. Fixed in 0.4.1-lab: only the
> required state field decides. See
> [references.md](references.md#audiofocusresponse-field-2-is-not-granted-p53-19-september-2026).
> G7 was still unmet at that point, but for the first time the next attempt had a
> reachable pass — and took it.
>
> **Update, 19 September 2026 — first vehicle attempt, G7 still not met.** Ford SYNC 4.0
> opened the PCM `MEDIA` channel and accepted its AV setup with a window of **40**, then
> **refused audio focus** (`LOSS`, `granted=false`). Nothing was audible; nothing else in
> the session was affected. The advertised audio formats are now recorded for the first
> time. The full finding is in [references.md](references.md#audio-out-on-ford-sync-40-p53-first-vehicle-attempt-19-september-2026);
> the two source defects it exposed are in "Corrections from the vehicle" below.

Scope is [P5 plan §3](p5-plan.md). The §7 amendments **have since been applied** to
`spec.md` and `plan.md`; see "Spec and plan amendments" below.

## What was added

| Where | Change |
| --- | --- |
| `protocol/upstream/open-android-auto/oaa/audio/` | 4 vendored files from the pin: `AudioFocus{Request,Response}Message.proto`, `AudioFocus{State,Type}Enum.proto`. No new imports, so no pin bump and no new upstream audit |
| `core/aap-proto` | the two audio-focus roots; `ids::AUDIO_FOCUS_{REQUEST,RESPONSE}` (0x0012/0x0013, **control channel**) and `ids::av::AUDIO_UNDERFLOW` (0x800B) |
| `core/aap-session/src/discovery.rs` | `AudioChoice`/`AudioConfigChoice`, every advertised audio configuration spelled out, and selection of one PCM `MEDIA` channel |
| `core/aap-session/src/media.rs` | `MediaLink` generalised over a `Codec`; a `Codec::Pcm` link bypasses the NAL parser and has its own size limit and report prefix |
| `core/aap-session/src/projection.rs` | `ProjectionState` holds a `Vec<StreamState>`; inbound AV messages route by a channel → stream lookup; `Step::{OpenAudio,AudioSetup}`; the audio focus exchange; `stop_audio` |
| `core/aap-jni` | `nativeSubmitAudio`, `nativeAudioFailed`, `EventKind::Audio{Start,Stop}`, config keys `audio` and `audio_focus_wait_ms` |
| `tools/source-harness` | `--audio`, `--audio-focus-wait-ms`, and `tone.rs` — a 440 Hz/880 Hz lab tone on its own thread |
| `tools/hu-sim` | two PCM audio channels (SPEECH on 4, MEDIA on 5), the control-channel focus exchange, `AudioFocus::{Grant,GrantWithoutGranted,State,GrantThenDuck,GrantThenLoss,Silent}`, and underflow injection |
| `tools/lab-receiver` | `LabAudioHandler` over Prodigy's `AudioChannelHandler`, a PCM dump, a Goertzel tone check, and `--underflow-after`; `--script audio` |
| `app/` | `AudioSource.kt` (app-owned tone on its own thread, paced by the head unit's own buffer size) and an "audio out" checkbox |

## Verification

`tools/check.sh` and `tools/android-build.sh` both pass. Rust tests: **167 passed**, of
which projection **48** (twelve new):

- `advertised_audio_configurations_are_reported_and_pcm_media_is_selected`
- `an_audio_configuration_the_phone_cannot_produce_is_refused_at_discovery`
- `audio_opens_sets_up_takes_focus_on_the_control_channel_and_streams_pcm`
- `stopping_sends_the_audio_stop_indication_and_always_releases_focus`
- `a_head_unit_initiated_shutdown_still_releases_audio_focus`
- `a_refused_audio_channel_setup_or_focus_costs_only_the_audio`
- `a_denied_audio_focus_can_still_be_granted_later` *(added after the vehicle attempt)*
- `a_silent_audio_focus_response_skips_audio_after_its_wait`
- `a_duck_keeps_the_audio_flowing_and_a_loss_stops_it`
- `an_audio_underflow_is_accepted_recorded_and_never_answered`
- `the_pcm_link_validates_buffers_without_a_keyframe_or_configuration`
- `a_fragmenting_video_keyframe_is_never_interleaved_with_audio`

plus the JVM host contract test (89 checks) carrying `audio=true`.

## Controlled receiver run

`LAB_STOP_AFTER=projection LAB_SCRIPT=audio LAB_UNDERFLOW_AFTER=3 tools/lab-receiver.sh
demo`, against the pinned OpenAuto Prodigy protocol library. Prodigy already has an
`AudioChannelHandler` and already answers `AUDIO_FOCUS_REQUEST` on the control channel, so
the decode on the receiver side is genuinely independent of our types; the lab subclass
adds only the underflow injection and the measurement.

Receiver side:

```
receiver_channel_opened=3
receiver_channel_opened=1
receiver_channel_opened=4
receiver_audio_started=session=0 config=0
receiver_audio_tone=left_440=0.247875 left_880=0.00396428 right_880=0.250279 right_440=0.0015766
receiver_audio_buffers=32 receiver_audio_bytes=262144 receiver_audio_largest=8192 receiver_audio_session=0 receiver_audio_config=0 receiver_audio_stopped=true receiver_audio_underflows_sent=1
receiver_checks=11 receiver_check_failures=0
```

Source side, same run:

```
discovery_audio_channel_004=channel=4 codec=1 audio_type=3 configs=48000/16/2 selected=48000/16/2 index=0
projection_open_004=audio sent_ms=6 response_ms=6 status=0 (OK)
audio_requested=true audio_channel=4 audio_open=true audio_refused=false
audio_setup_status=2 (OK) audio_setup_ms=6 audio_setup_window=10 audio_setup_accepted_configs=0
audio_focus_requests=1 audio_focus_responses=1 audio_focus_state=1 (GAIN) audio_focus_granted=- audio_focus_ms=6 audio_focus_denials=0 audio_focus_losses=0 audio_focus_regains=0 audio_focus_ducks=0 audio_focus_released=true audio_focus_uncertain=false
audio_focus_sequence=6:1:-
audio_start_session=0 audio_start_config=0 audio_started_ms=6 audio_stop_indication_sent=true
audio_buffers_sent=32 audio_media_bytes_sent=262144 audio_acks=32 audio_acked_units=32 audio_outstanding_max=1 audio_stalled=false audio_underflows=1 audio_underflow_body_len=0
audio_submitted=32 audio_accepted_buffers=32 audio_largest_unit=8192 audio_invalid=0
unhandled_messages=0
```

**The round-trip check the plan asked for.** The tone is 440 Hz in the left channel and
880 Hz in the right, both at a quarter of full scale; the receiver runs a Goertzel at both
frequencies in both channels on the PCM it was handed. Left reads 0.248 at 440 Hz against
0.004 at 880 Hz, right reads 0.250 at 880 Hz against 0.002 at 440 Hz. That is the sample
rate, the sample format, the byte order, the channel count *and* the channel order
confirmed at once — and it is something the video path has no equivalent of, since no wire
signal distinguishes a displayed frame from a discarded one (references.md).

`audio_focus_granted=-` is the interesting absence: Prodigy sends `AudioFocusResponse`
with `audio_focus_state` only, exactly as aasdk's copy of the message does. That absence
should have been the warning — two independent implementations in the family omit the field
entirely, so nothing that matters can depend on it. This source depended on it anyway until
the vehicle said otherwise.

### Interleaving

`LAB_STOP_AFTER=projection LAB_SCRIPT=taps LAB_AUDIO=1` runs the P4 tap script with audio
flowing: 13 checks, 0 failures, 59 decoded pictures, 0 decode errors, 46 audio buffers.
That is the regression the plan asked for, run against an aasdk-family assembler that
aborts a partially assembled message the moment a frame from another channel arrives
(`MESSENGER_INTERTWINED_CHANNELS`). Video keyframes really do fragment here —
`video_largest_unit=579008`, 36 fragments at a 16 KiB payload — so audio was interleaving
with fragmenting video throughout.

It holds by construction rather than by luck, and the reason is worth stating because it
is easy to break later: `Session::send_on` fragments a whole message and appends **every**
fragment to the output queue before returning, so nothing can be queued between them. The
audio side is belt and braces on top of that: `AUDIO_UNIT_LIMIT` refuses a PCM buffer that
would not fit one outbound fragment, so an audio message is always a single BULK frame.

## Decisions taken in this slice

- **Streams, not a second special case.** `ProjectionState` holds a `Vec<StreamState>`
  with video always at index 0, and inbound AV messages find their stream by channel. A
  message id means what the channel says it means: 0x8003 is the video setup response on
  one channel and the audio setup response on another, and neither half knows about the
  other. Each stream keeps its own window, its own session id and its own focus.
- **Audio is opt-in and every audio failure is non-fatal.** A refused channel open, a
  refused AV setup, a denied or silent focus response, a closed channel, a stalled
  acknowledgement window and a failed app-side source each stop audio and leave the
  picture running. Losing the drive because the head unit would not make a noise would be
  the wrong trade.
- **The phone validates the format, and refuses rather than guesses.** Sample rate ∈
  {48000, 16000}, bit depth 16 only, channels ∈ {1, 2}. A head unit advertising anything
  else leaves `discovery_audio=-` and an issue line, and the channel is never opened.
- **`RELEASE` belongs to a grant.** Any stop that gives up focus actually held — the
  projection limit, an operator stop, a head-unit shutdown request — sends
  `AUDIO_FOCUS_REQUEST(RELEASE)`. SYNC 3.4 owners report a focus/duck state machine that
  wedges until the cable is pulled, and a dangling grant is the one thing that outlives the
  session. The head-unit-initiated case needed adding on purpose: the video stop indication
  is still *not* sent there (the head unit is tearing down), but the focus release is.
  *Corrected after the vehicle attempt:* it was originally sent after **any** stop,
  including one following a refusal, which claimed a state this source did not hold. A
  focus response that failed to decode is the one case where "never granted" cannot be
  proved, and is released defensively (`audio_focus_uncertain`).
- **This source never ducks its own media.** `LOSS_TRANSIENT_CAN_DUCK` is counted in
  `audio_focus_ducks` and the PCM keeps flowing at full amplitude, because a head unit
  asking for a duck lowers the media channel in its own mixer. A real `LOSS` **pauses** the
  stream — the mirror of what video focus loss does — and leaves the request standing, so a
  later grant resumes on the same session without renegotiating the channel.
  *Corrected after the vehicle attempt:* a loss used to end the audio permanently, which
  would make a head unit that grants late indistinguishable from one that never grants.
- **The underflow body is not decoded.** Modern Android Auto parses no payload for 0x800B
  while older schemas define `required int32 session_id = 1`; both shapes are accepted and
  only the body length is recorded.
- **The app owns the audio it sends.** Capturing another application's playback is a
  separate platform question this project has not answered, and spec §5.2 forbids the
  MediaProjection route to it. `AudioSource.kt` generates its own tone, which needs no
  Android permission and makes G7 checkable by ear and by receiver dump at the same time.
- **PCM has no keyframe, no configuration and no drop episode.** Every buffer is
  independently playable, so a congested audio queue drops the newest buffer and takes the
  next one as soon as the window frees a slot; there is nothing to resynchronise to.

## Limitations and open items

- ~~**No vehicle evidence.**~~ *Answered 19 September, partly.* Ford does open the PCM
  `MEDIA` channel and does accept its AV setup, its audio window is **40** (against 4 for
  video), and it advertises 48000/16/2 on both media channels and 48000/16/1 — not the
  conventional 16 kHz — on both speech channels. Whether it ever sends `AUDIO_UNDERFLOW` is
  still unknown, because no audio ever flowed: **it refuses audio focus**, and why is the
  open question. See [references.md](references.md#audio-out-on-ford-sync-40-p53-first-vehicle-attempt-19-september-2026).
- **Prodigy's audio window is 10, hardcoded** (`AudioChannelHandler::MAX_UNACKED`), so the
  lab never exercised a window of 1 against a real receiver. `hu-sim` defaults to 1 and
  the unit tests do cover it, but that is our own simulator on both sides. Ford's is 40, so
  a small window may turn out to be a case no real head unit presents.
- **Pacing is untested under load.** The lab receiver acknowledges immediately over
  loopback TCP. The ~42.7 ms per-buffer round-trip budget the plan worried about assumed a
  window of 1; Ford gives 40, which is 1.7 seconds of credit, so that particular worry does
  not bind on this vehicle. `audio_underflows` remains the signal, and has never fired.
- **"Ackless mode" is still undetectable.** `HeadUnitInfo` carries no PDK or protocol
  version and Ford sends none, so if real Android Auto runs ackless audio on this vehicle
  a strict window-of-1 source will sound worse than it does. Nothing speculative was
  implemented; settle by measurement.
- **One audio channel, PCM, `MEDIA` only.** No speech channel, no AAC, no encoder. The
  `av_input` microphone (P5.4) stays deferred.
- The tone is a tone. It proves the transport; it says nothing about how a real music
  stream would sound, because there is no real music stream on this path yet.

## Corrections from the vehicle (0.4.0-lab)

The first vehicle attempt did not change what the head unit does, but it exposed two
defects in this source, both in the focus path and both invisible in the lab because the
lab receiver always grants.

0. **The response's field 2 was treated as `granted` and gated the stream.** The vendored
   schema names it that; Ford sends it false whether it grants or refuses, so a `GAIN` was
   discarded as self-contradictory on both 0.4.0-lab runs. It is the **`unsolicited`**
   flag: true when the head unit volunteers a state, false when it replies. Only
   `audio_focus_state`, the one `required` field, decides now, and the flag is used the way
   Android Auto uses it — to separate a refusal of something we asked for
   (`audio_focus_denials`) from a state nobody asked for (`audio_focus_volunteered`).
   **The lab could not have caught this** — both ends of `hu-sim` were written from the
   same reading of the same schema, so the simulator agreed with the bug. That is the
   general hazard with a reverse-engineered schema, and spec §8.1 now states it as a rule.
   `hu-sim` now sends the flag false on every reply and true only on a volunteered state.
1. **The report recorded only the latest focus state.** Ford sent two responses to one
   request and the report said `audio_focus_state=3 (LOSS)` — which is equally consistent
   with an outright denial and with an unsolicited loss followed by a grant. The report now
   carries `audio_focus_sequence` (`elapsed_ms:state:granted`, in order, bounded), and
   separates `audio_focus_denials` from `audio_focus_losses`. Spec §10 now requires the
   sequence rather than the final state.
2. **A non-grant tore the audio down for good, and released a grant that never existed.**
   Any state that was not a gain set `stop_emitted` — so a later grant could never start
   the stream — and sent `AUDIO_FOCUS_REQUEST(RELEASE)`, claiming to give up focus this
   source had been refused. That was a misreading of the plan's "always send `RELEASE`":
   the rule is about never leaving a *grant* dangling. Now a loss **pauses** the stream and
   leaves the request standing, exactly as video focus loss does — the app is told to stop
   producing, the queue is discarded, `outstanding` is left alone, and a later grant
   resumes on the same session without a second start indication. `RELEASE` is sent only
   for focus actually held, or when a focus response failed to decode and "never granted"
   cannot be proved (`audio_focus_uncertain`).

The first one is why the second attempt failed; the third matters for the drive after that,
because a head unit that grants late would otherwise go unnoticed.
Regression tests: `a_gain_is_a_grant_whatever_field_two_says` (all three shapes of field 2),
`a_denied_audio_focus_can_still_be_granted_later`, and the extended
`a_duck_keeps_the_audio_flowing_and_a_loss_stops_it`. Rust tests are now **168 passed**,
projection **49**.

## What the Android Auto dex says

Two reads, both after a vehicle attempt, both in
[references.md](references.md#audio-focus-in-android-auto-176-from-the-dex-p53-19-september-2026).
The second one settled the field-2 question above; the first established that the request
itself was never the problem:

- The body Google's client sends is the same one-field `{1: GAIN}`. So unlike the 0x800A
  case, this is not a malformed-request problem and no field is missing.
- Android Auto also withholds the audio start indication until focus is granted, which
  confirms the ordering the P5 plan took from aasdk — and which this source already does.
- It treats a `LOSS` as authoritative and stops rather than arguing, which is what the
  correction above makes this source do.
- It models unsolicited head-unit focus states explicitly, which is why treating focus as a
  state machine rather than as an answer is the right shape.

**The one substantive difference is *when*.** Android Auto sends `AUDIO_FOCUS_REQUEST` only
when an app on the phone takes Android audio focus — never at session setup — and the
message carries no channel id, so there is no separate media-versus-speech request. This
source asks once the projection reaches its streaming step, with nothing playing yet.

One behaviour worth recording and not implemented: Android Auto retries an **unanswered**
GAIN every 500 ms up to 10 times (once for other types) before giving itself a loss. Ford
answered, so it would not have helped here.

The second read also corrected two things about the vendored `AudioFocus*` schemas, neither
fixable in the generated types because the `.proto` files are vendored unmodified — both
are recorded on `ids::AUDIO_FOCUS_REQUEST`:

- `AudioFocusResponse` field 2 is `unsolicited`, not `granted`.
- `AudioFocusRequest.audio_focus_type` is `required` in 17.6 and 15.7, where the vendored
  copy makes it optional. Harmless here: this source always sets it.

## Spec and plan amendments

Applied on 19 September, after the vehicle attempt, so the wording reflects what was
learned rather than what was planned:

- **spec §8.1** — audio focus is a control-channel exchange independent of video focus; a
  grant MUST precede the audio start indication; this source MUST NOT duck its own media;
  `RELEASE` MUST be sent when and only when a grant is being given up; a loss MUST NOT be
  treated as a permanent refusal. Plus the phone-side format validation, and "optional"
  extended to every stage of the handshake rather than just the channel open.
- **spec §10** — `audio_underflows` is evidence about this source's pacing, not about the
  vehicle; the focus exchange MUST be reported as a sequence.
- **spec §11.1** — **G7** added, with the same degraded-pass shape as G6: a refusal is a
  recorded outcome rather than a failed gate, but it leaves the gate *unmet* rather than
  passed.
- **spec §12** — the `P5.3` row expanded.
- **plan.md** — a `### P5.3` section with the slice's tasks, the first vehicle attempt, and
  its exit criterion.

What was already applied before the attempt:

- **docs/licenses.md** — 119 → 123 vendored `.proto` files, with the four audio-focus
  files named. The slice adds no dependency.

Still outstanding:

- **[docs/p4-lab-results.md](p4-lab-results.md) line 106** — still says `max_unacked=10`
  in a way that reads as Ford's. It is the Prodigy lab receiver's value (and, as this
  slice shows, Prodigy's audio handler hardcodes the same 10). Ford's video window is 4
  and its *audio* window is 40, both now measured on the vehicle. Correcting that line is
  a one-line fix the P5 plan already called for and it is *not* done here.
