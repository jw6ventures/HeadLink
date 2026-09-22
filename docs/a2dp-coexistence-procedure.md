# Bluetooth A2DP / USB projection coexistence (vehicle procedure)

> **Runs A and B both passed** (19 September 2026, operator report). Bluetooth A2DP keeps
> playing through a live USB projection session in **both** orderings — Bluetooth first
> then project, and project then connect Bluetooth. The phone-audio goal is therefore met
> with no code, and USB audio is superseded as the route for it; see
> [the de-scope proposal](a2dp-supersedes-usb-audio-proposal.md).
>
> **The detailed evidence this procedure asks for was not captured.** What is on record is
> the operator's "A and B both worked". Not recorded: the report ids, the output-switcher
> photographs, the head-unit source display, the four separate measurements of §2, and the
> §5 coverage checklist. So the conclusion "A2DP survives projection" is sound, but
> **"the phone is silent" and the per-category coverage are assumed, not measured** — and
> the whole point of §5 was that truck-*only* is the actual requirement. If that
> distinction ever matters, §5 is a ten-minute run and is still worth doing.
>
> **Run C was not run.** Whether a USB audio focus request would displace A2DP is still
> unknown. It is moot while USB audio stays off by default, and becomes relevant again only
> if anything re-enables it.

Prepared 19 September 2026, app 0.4.1-lab. It continues
[the G0–G3 setup record](g1-setup-record.md) §1 for the vehicle, phone, cable,
port, and report handling, and assumes a projection session that already works
([G4/G5 procedure](g4-g5-procedure.md)). Fill in one copy of the setup table per session
and attach every exported report.

Spec references: §2.2 (what audio this source owns), §5.2 (permission baseline), §8.1
(audio focus), §9 (states and errors), §10 (evidence), §11.3 (physical test matrix).

**This is not a gate.** No G-number is claimed and no spec amendment depends on the
outcome. It answers one question that decides whether a much larger piece of work is worth
proposing at all.

## 1. Why this is being run

The goal behind it is "as much of the phone's audio as possible, audible in the truck and
**only** in the truck". Three routes could serve it, and the cheap one has never been
tested against a live projection session:

| Route | Mechanism | Coverage | Cost |
| --- | --- | --- | --- |
| Bluetooth A2DP | A real output **route**; the phone speaker goes silent | Everything the platform routes to it | No code, no permission, no amendment |
| `AudioPlaybackCapture` over USB | A **duplicate** of a tap point; original playback continues | `USAGE_MEDIA`/`GAME`/`UNKNOWN` only, and any app may opt out | MediaProjection, a new foreground-service type, and a spec §5.2 amendment |
| Privileged audio-policy routing | What real Android Auto does | Everything | Signature/privileged — **barred by APP02** |

A2DP wins on every axis that matters here *provided the head unit will hold it while the
USB session is running*. That proviso is the whole experiment. Spec §99 already refuses to
assume it — "Do not assume that a vehicle will retain Bluetooth call behavior during custom
USB projection; test coexistence later" — and [the G4/G5 procedure §6a](g4-g5-procedure.md)
records the leading explanation for the early G7 refusals: that SYNC "grants media focus
only to the source the driver has chosen".

If A2DP survives, the answer to the original request is "already solved, no code". If it
does not, the capture amendment becomes worth writing, knowing it buys strictly less.

**The AAP `bluetooth` service is not a candidate and is not tested here.** It carries no
audio: [`BluetoothChannelData.proto`](../protocol/upstream/open-android-auto/oaa/bluetooth/BluetoothChannelData.proto)
is an `adapter_address` and a list of pairing methods — an SDP descriptor whose only job is
to let the phone start an ordinary Bluetooth pairing. It stays closed under spec §8.1.

## 2. What is being measured

Four things, and they are independent — record each separately rather than collapsing them
into "it worked":

1. **Link** — is A2DP still *connected* phone-side?
2. **Route** — is the phone still *sending* media to it, rather than to its own speaker?
3. **Source** — is the head unit still *playing* the Bluetooth source?
4. **Picture** — is the projection unaffected?

A failure of 3 with 1 and 2 intact is a head-unit source-selection problem and may be
recoverable by the driver. A failure of 2 is a phone-side routing problem. They look
identical from the driver's seat, which is why both get recorded.

**adb is not available during these runs.** The USB port is carrying the accessory link to
the head unit, so the phone's own UI and the app's exported report are the entire
instrument set. Evidence is therefore:

- The phone's **output switcher** (volume panel → output picker) — photograph it. It names
  the active media route, which answers 1 and 2 at once.
- The head unit's **source display** — photograph or note it. Answers 3.
- The exported **session report** — answers 4, and timestamps the run.
- The operator's ear, which answers "is this actually usable" and nothing else.

## 3. Before driving anywhere

1. Confirm the phone is paired with the vehicle and A2DP works **with no USB cable
   attached**. Play the reference track; confirm it comes from the vehicle speakers and
   that the phone speaker is silent. This is the baseline — without it, a later failure
   cannot be attributed to the projection.
2. Pick a **reference track** that is long, continuous, and instantly recognisable when it
   drops out. Anything with silence in it wastes a run.
3. Have the sound checklist in §5 ready on the phone, with each app already installed and
   its audio confirmed working over A2DP at the desk.
4. Confirm the head unit is pinned from the earlier visit, and run with first use
   **unchecked**, as for any projection attempt.
5. **Leave "Audio out over USB" unticked** for runs A and B. The point is to let A2DP be
   the audio route; requesting audio focus over USB would actively compete with it, and
   that competition is run C's job, not run A's.

## 4. The runs

Stationary vehicle, parking brake set, phone unlocked. One Connect per run, one report per
run. **Order matters and is part of the experiment** — Ford may treat a projection that
arrives after Bluetooth differently from one that arrives before it.

### Run A — Bluetooth first, then projection

1. With no cable attached, start the reference track. Confirm audio from the vehicle.
2. In the app: **Stop after** `projection`, audio out **unticked**. Tap **Connect**, then
   plug in.
3. Let the session reach PROJECTING and the scene appear.
4. **At the moment the projection appears, note whether the music stopped.** This is the
   single most informative observation in the whole procedure; everything after it is
   detail.
5. Record all four measurements from §2.
6. If the music stopped, try to recover it from the head unit's own source control: select
   Bluetooth Audio while the projection is up. Record whether it comes back, and whether
   the picture survives the source change.
7. Run the §5 checklist if audio is alive.
8. Stop from the app. Confirm the music returns to its pre-session state, and record if it
   does not — a session that leaves the vehicle's audio wedged is a finding in its own
   right, and the SYNC focus/duck state machine has form here (see
   [p5-3-lab-results.md](p5-3-lab-results.md)).

### Run B — projection first, then Bluetooth

1. Start from Bluetooth **disconnected** on the phone.
2. Connect and project as above; let the scene settle.
3. Now connect Bluetooth to the vehicle from the phone, and start the reference track.
4. Record the same four measurements. In particular: does the head unit *offer* Bluetooth
   Audio as a selectable source while a projection is running, or has the projection taken
   the source list over?

### Run C — both routes at once (diagnostic)

Only if A or B produced audible A2DP audio. Repeat run A with **Audio out over USB
ticked**, so the source requests audio focus on the control channel while A2DP is playing.

This is deliberately the awkward case, and the question is narrow: **does the focus request
displace Bluetooth as the head unit's source?** Watch `audio_focus_sequence` in the report
against what the operator hears.

Expected outcomes, none of them yet observed:

| What happens | Reading |
| --- | --- |
| Focus granted, tone plays, music stops | The two routes are mutually exclusive on this head unit; USB audio and A2DP are an either/or the user must choose between |
| Focus refused, music continues | The head unit protects the driver-selected source — which would also retrospectively explain the first G7 attempt |
| Both audible, mixed | The head unit mixes sources; unexpected, and would want a second run to confirm |
| Focus granted, tone plays, music *also* continues | Best case for a future design, and the one worth knowing about |

Record either way. Nothing here can cost the picture; if the projection stops when audio is
ticked, that is a defect and the report is the evidence.

## 5. Coverage checklist — "how much of the phone's audio"

Run this only once audio is confirmed alive over A2DP during a projection. It answers the
actual question behind the request, and it is the part that capture-over-USB could never
match, since three of these five categories are structurally uncapturable.

| Sound | How to produce it | Audible in truck? | Phone silent? |
| --- | --- | --- | --- |
| Media (music) | The reference track | | |
| Navigation prompt | A turn instruction from the offline map app | | |
| Notification | Send a message to the phone | | |
| Alarm / timer | Set a 1-minute timer | | |
| UI / keypress sound | Phone keyboard or lock sound | | |

For each, record **both** columns. "Audible in truck" alone is not the goal — the request
was truck **only**, so a sound that plays in both places is a partial failure and needs
saying so.

## 6. What would change as a result

- **A2DP survives projection, checklist mostly green** → the original request is already
  satisfied with no code. Record it, close the capture question, and note the arrangement
  in the README so it is not re-litigated.
- **A2DP survives but coverage is poor** → worth understanding *why* before proposing
  anything; the gap is more likely phone-side routing policy than the head unit.
- **A2DP does not survive** → the capture amendment becomes worth writing, and this
  procedure's report is the evidence justifying it. It still buys less than A2DP would
  have, and the proposal must say so plainly.
- **Any run leaves the vehicle audio wedged after a stop** → that outranks the feature
  question and gets fixed first.

## 7. What to keep

Per run: the exported report (sanitized as usual), the photograph of the phone's output
switcher, the head-unit source display, the filled §5 checklist where it was run, and the
operator's note of what was audible and from where. Keep the report id next to each
photograph so they can be matched later.
