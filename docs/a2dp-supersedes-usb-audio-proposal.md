# Proposal: A2DP supersedes USB audio as the route for phone audio

**Status: proposed, not applied.** `spec.md` and `plan.md` are governed; nothing below has
been written to either. Accept, reject, or amend item by item.

Raised 19 September 2026 after the [A2DP coexistence runs](a2dp-coexistence-procedure.md)
A and B both passed.

## 1. What changed

The goal was "all of the phone's audio in the truck, and only in the truck". Bluetooth
A2DP already does that, and runs A and B showed it keeps doing it through a live USB
projection session in both orderings. No code, no permission, no new gate.

That makes **P5.3's audio channel the wrong tool for that goal** — not broken, just aimed
at a different problem. The comparison, for the record:

| | USB audio (P5.3) | Bluetooth A2DP |
| --- | --- | --- |
| What it carries | Audio this app **owns** | Whatever the platform routes |
| Mechanism for other apps' audio | Would need `AudioPlaybackCapture` — a **duplicate** | A real **route** |
| Phone speaker | Unaffected | Silent |
| Nav prompts, notifications, alarms | Structurally uncapturable | Covered |
| Cost | A built slice | Nothing |

## 2. What is explicitly **not** proposed

- **G7 stays passed.** It was met on the vehicle (report `7f869585`: 958 buffers, 40.8 s,
  `audio_underflows=0`, focus released cleanly). It took three attempts and exposed two
  real source defects. De-scoping a feature does not unmake a measurement.
- **No code is removed.** The audio path is load-bearing for video: `MediaLink` is generic
  over `Codec` and `ProjectionState` holds a `Vec<StreamState>` with video at index 0.
  Un-picking that would be a substantial refactor of the path G4 and G7 both passed with,
  in a project with **no version control**, to delete a working feature. The risk is all
  downside.
- **Spec §8.1's audio rules stay as written.** They describe behaviour the code still has
  and still needs to be correct about: focus is a control-channel exchange, a grant
  precedes the start indication, this source never ducks its own media, `RELEASE` belongs
  to a grant, a loss is not a permanent refusal.

The change is one of **intent and default**, not of capability.

## 3. Proposed amendments

Each names the current text and the proposed replacement. All **unapplied**.

### 3.1 spec §2.2 — capability statement (line 73)

Current:

> Play audio generated or owned by the application through negotiated projection audio
> channels.

Proposed — append:

> This capability is **not** the route for the phone's general audio output. Audio the
> application does not own reaches the vehicle over ordinary Bluetooth A2DP, which is a
> platform routing decision rather than a projection feature, needs no permission and no
> code in this project, and was shown on 19 September 2026 to survive a live USB
> projection session. The projection audio channel exists for audio this application
> generates, and is off by default.

### 3.2 spec §11.1 — G7 (lines 846–850)

Keep the gate and its passed status. Append to its notes:

> G7 remains passed on its own terms. It is not, and never was, a gate about the phone's
> general audio: that is served by Bluetooth A2DP (§2.2). G7 says the wired audio
> transport works for audio this application owns.

### 3.3 spec §12 — P5.3 row (lines 970–974)

Current exit criterion reads `G7 on factory HU`. Proposed — append to the row:

> **Complete, and superseded as the route for phone audio.** No further investment is
> planned. The capability ships off by default; A2DP covers the goal P5.3 was reached for.

### 3.4 plan.md §P5.3 — header (lines 340–342)

Current text is **stale and wrong** independently of this proposal:

> Implemented and **lab-verified**, with the first vehicle attempt made and **G7 not met**

G7 has since been met (report `7f869585`, third attempt). Proposed:

> Implemented and **vehicle-verified**: G7 met on the third attempt (report `7f869585`).
> Superseded as the route for the phone's general audio by Bluetooth A2DP, which covers
> that goal with no code; this slice remains the route for audio the application owns, and
> ships off by default. This is the slice that cost the multi-stream refactor: the session
> had assumed one AV stream throughout.

### 3.5 README — the arrangement worth stating once

Not governed, but the place a future reader will look. Add: phone audio goes over
Bluetooth A2DP; the USB audio channel is for app-owned audio and is off by default. This
is the item that stops the question being re-litigated.

## 4. Evidence gaps, stated plainly

The proposal rests on an operator report of "A and B both worked". It does **not** rest on
the evidence the procedure asked for, none of which was captured: report ids, the
output-switcher photographs, the head unit's source display, §2's four separate
measurements, or §5's per-category coverage checklist.

So one claim above is **assumed rather than measured**: that the phone is silent while the
truck plays. A2DP routing makes it near-certain, and it matches the "only in the truck"
requirement, but this project's own standard is that a plausible reading is not a
measurement — the `AudioFocusResponse` field-2 affair is the house example. If truck-only
coverage ever has to be *stated* rather than assumed, run §5.

Run C was never run, so whether a USB focus request displaces A2DP is unknown. Moot while
audio stays off by default; relevant again the moment anything re-enables it.
