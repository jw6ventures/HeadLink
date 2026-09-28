# P7.4a — ending a route hands the cluster back: implementation record — 24 September 2026

The one piece of [P7 plan](p7-plan.md) §5.4 that P7.4 could not build from its own files:
End route "releases navigation focus". [p7-4-5-results](p7-4-5-results.md) §3.1 described
the change; this slice made it, in the session, the JNI bridge and `Guide.clear()`.

Status: **implemented, host-tested (Rust and JNI), and lab-verified on the emulator**
against the Prodigy receiver, including the next route asking for the cluster again.
**Nothing has run on the Pixel or the truck.** How SYNC 4 reacts to being handed its
cluster back mid-session has never been seen (§4).

---

## 1. What was built

### 1.1 The behaviour

When a route **ends** — the dashboard's End route, the phone's Clear route, or the route
ending itself 30 s after arrival (all three go through `Navigation.clear()` →
`Guide.clear()`) — the session now:

1. sends `NavigationState(INACTIVE)` if a route was on the cluster, as before;
2. then sends `NavigationFocusRequest(NAV_FOCUS_NATIVE)` on the control channel, the same
   hand-back it already sent at the end of a session;
3. puts its focus state back to **idle**, not stopped, and stops the sequence.

The **next route** runs the start of the handshake again: it sends `NavigationFocusRequest(
NAV_FOCUS_PROJECTED)`, waits for the grant (or, with "send regardless" on, for the grant or
the focus wait, exactly as at session start), and only then sends `ACTIVE` and the first
maneuver.

What does **not** hand the cluster back, on purpose:

- **Idle**. The app still publishes idle at session start (no route yet) and for a route
  with no fix on it yet. P6.5 kept the cluster across those so a destination needs no new
  handshake, and nothing about them says the driver has finished with it.
- **Arrival**. The cluster goes `INACTIVE` at arrival and is kept, as before. It is handed
  back 30 s later, when the arrival hold ends the route.
- **A new destination while a route exists.** `Navigation.set` does not clear, so a
  favourite tap or a share mid-route replaces the route and keeps the cluster.

### 1.2 Session (`core/aap-session`)

- `navigation::RouteState::Ended` (`"ended"`), documented against `Idle`.
- `projection.rs`:
  - `NavigationChannel::handed_back`. Set by the hand-back, cleared by the next request.
  - `route_focus()`, called first in `pump_navigation` for a route source. It runs ahead of
    the `sending()` check because both halves happen while the sequence is not sending: an
    ended route may find focus asked for and never granted, and after a hand-back the
    sequence is stopped. It acts on the **focus state**, not on the published one. The link
    keeps the last thing published, so `Ended` is seen on every visit until the next route,
    and the hand-back is still sent once. Handing back is done only for focus actually asked
    for (the teardown's rule).
  - `hand_back_navigation()`: `INACTIVE` if active or rerouting, then `NAV_FOCUS_NATIVE`.
    It sets focus idle, `active=false` and clears the sent maneuver, so the next route's
    first maneuver is news. It **keeps a future interval deadline**, so a quick new route
    still waits out the head unit's floor.
  - `navigation_progress` does not ask again while handed back unless the app is guiding. A
    cluster announcing itself again, or a focus wait running out, is no reason to take the
    cluster back.
  - **Focus responses while handed back are not denials.** Prodigy echoes the
    `NAV_FOCUS_NATIVE` ([references.md](references.md), navigation section), and before this change
    only the session-end hand-back was guarded against that echo. They are counted and their
    type kept, and never obeyed: a `PROJECTED` arriving then starts nothing, because there is
    no route.
  - `navigation_focus_released` now means "not holding the cluster". It is set by either
    hand-back and cleared by any request, so the session end still hands back a cluster the
    second route took, and does not hand back one that was already returned.
  - Report, one new line:
    `navigation_focus_handbacks=N navigation_focus_handback_answers=N
    navigation_focus_handback_type=T (NAME)`. Counts and an enum only.

### 1.3 Bridge and app

- `core/aap-jni/src/manager.rs`: `GUIDANCE_ENDED = 4`. **This file was not on this slice's
  list.** The `GUIDANCE_*` constants live there rather than in `lib.rs`, in the same crate;
  it is a one-line addition beside the other four.
- `core/aap-jni/src/lib.rs`: `GUIDANCE_ENDED => RouteState::Ended` in
  `native_submit_guidance`. The JNI signature is unchanged.
- `NativeBridge.kt`: `GUIDANCE_ENDED = 4`.
- `Guide.kt`: `clear()` publishes with `ended = true`, and with no route `publish` sends
  `GUIDANCE_ENDED` rather than `GUIDANCE_IDLE`. The brief's "one line" became three,
  because `publish()` is also what `attach()` calls at session start, and that must stay
  idle. Nothing else in `Guide` changed, and its report still says `guidance_state=idle`
  with no route.

### 1.4 Lab receiver and lab script

- `tools/lab-receiver/receiver.cpp` records **every navigation focus request the source
  makes**, from Prodigy's `ControlChannel::navigationFocusRequested`, decoded with the
  vendored `NavigationFocusRequest`. Each is recorded with the cluster states seen so far,
  as `receiver_navigation_focus_request=type=T states=…`. Before this, the receiver only
  answered focus requests (by echo) and recorded none.
- The **`endroute` script** grows from four steps to seven:
  1. End route.
  2. The 3 s watch, which now ends before the next route.
  3. Resume.
  4. Wait up to 60 s for a second `ACTIVE`.
  5. Wait 3 s, then press End route again.
  6. Wait for `INACTIVE` and the second hand-back.

  It then checks three things:
  - A `NAV_FOCUS_NATIVE` request between the first press and the end of the watch.
  - A `NAV_FOCUS_PROJECTED` request after that, arriving before the second route's
    `ACTIVE`.
  - A `NAV_FOCUS_NATIVE` after the second press.

  The existing "no route came back" check now covers only the watch window.
- `tools/emulator-lab.sh endroute`:
  - Once the report notes `dashboard end route pressed`, it waits 10 s and shares the same
    destination again, then waits for the second route.
  - The app-side expectations are now `dashboard_trips=2` and
    `dashboard_end_route_presses=2`.
  - It also expects `navigation_focus_requests=2`, `navigation_focus_denials=0` and
    `navigation_focus_handbacks=2`.
  - The header comment describes the addition.

## 2. What "native" means, and how sure that is

**Inferred, not observed.**

- **The schema.** The vendored `NavigationFocusRequestMessage.proto` (open-android-auto
  `61eab61`) calls the message "sent by phone to request/release navigation focus". It
  names two senders in Gearhead (`hwm.java` lines 143 and 169), triggered by a nav app's
  `startNavigation()`. `NavigationFocusType` has only `NAV_FOCUS_NATIVE = 1` and
  `NAV_FOCUS_PROJECTED = 2`, marked `confidence: silver [apk_static, cross_version]`.
- **What that implies.** There is no release value. The only reading that makes "release"
  possible is a request for the head unit's own (native) navigation.
- **What was not found.** No captured trace shows Android Auto sending one:
  - The 19 September Gearhead logs (`~/.cache/openauto-android/gearhead-2026-09-19/`) have
    no navigation-focus line.
  - None of the truck reports has a mid-session hand-back, because none was ever sent.
  - The session-end hand-back has gone to Ford on the G9 drives
    (`navigation_focus_released=true`), but nothing records what Ford did with it.
- **What the lab shows.** The receiver shows only that the requests go out in the right
  order. Prodigy echoes whatever it is asked, so its answers are no evidence.
- **Consistent with Android's own model.** `androidx.car.app`'s `NavigationManager` pairs
  `navigationStarted()` with `navigationEnded()`, and two senders in Gearhead fits a start
  and an end. That pairing is recalled from the public API, not traced in the APK, so it is
  a reason to expect this behaviour, not evidence for it.

The project's rule that schema names are not evidence applies. This change acts on the only wire
behaviour the project controls (what it sends, and when), and it records what comes back
so the truck can correct the reading.

## 3. Verification

All under `flock ~/.cache/openauto-android/p7-build.lock`.

### 3.1 Rust

`tools/check.sh` (fmt, clippy `-D warnings`, `cargo test --workspace`, release build,
proto-gen check): **exit 0**. `tests/projection.rs` has 68 tests, all passing. Two are
new:

- **`an_ended_route_hands_the_cluster_back_and_the_next_route_asks_again`.** The simulator
  is `Silent`, so every grant and every answer is sent by hand. The test checks:
  - Guiding gives `ACTIVE`.
  - Ended gives `INACTIVE` then `NAV_FOCUS_NATIVE`, once, across three more visits, with no
    past deadline left behind.
  - Echoing `NATIVE` counts as `handback_answers=1 handback_type=1 (NAV_FOCUS_NATIVE)` with
    `denials=0` and `released=true`.
  - A cluster start and a published idle do not ask again.
  - The next route asks (`[2,1,2]`), and nothing (no `ACTIVE`, no maneuver) is sent before
    the grant, even 600 ms later.
  - After the grant come `ACTIVE` and the new maneuver.
  - The session limit then gives `INACTIVE` and a second hand-back (`[2,1,2,1]`), with
    `requests=2 responses=2 denials=0`.
- **`an_ended_route_hands_back_nothing_that_was_never_asked_for`.** With a silent cluster,
  Ended sends no focus request and no state.

The existing route tests are unchanged and pass. That includes the P6.5 test in which
arrival keeps the cluster and a second destination needs no new handshake.

### 3.2 Android build and host tests

`tools/android-build.sh`: **exit 0**. That covers:

- clippy for aarch64;
- both APKs;
- `lintDebug`;
- `verify-apk` on all three;
- every host test.

`JniContractTest` (96 checks) has four new ones:

- `GUIDANCE_ENDED == 4`.
- Ended and idle, on an unknown handle, get `UNKNOWN_HANDLE` rather than `INVALID`, so the
  native side knows the state.

The first attempt failed on a syntax error in `DashboardDrawing.kt`, which was another
agent's work in progress. The retry a few minutes later passed.

### 3.3 Emulator lab

`LAB_ROUTING=~/.cache/openauto-android/rd5 LAB_MAP=…/massachusetts.map
LAB_VIDEO_CONFIG=1920x1080 tools/emulator-lab.sh endroute`, run as
`emulator-lab/20260924T154708Z`.

**Receiver:** `receiver_checks=19 receiver_check_failures=0`, and its trace, in order:

```
receiver_navigation_focus_request=type=2 states=
receiver_navigation_state=1
receiver_navigation_state=2
receiver_navigation_focus_request=type=1 states=1,2
receiver_navigation_focus_request=type=2 states=1,2
receiver_navigation_state=1
receiver_navigation_state=2
receiver_navigation_focus_request=type=1 states=1,2,1,2
receiver_navigation_state=0
receiver_endroute=focus requests=2@0,1@2,2@2,1@4
```

That is:

1. Focus asked.
2. Route 1 `ACTIVE`.
3. End route: `INACTIVE`, then the hand-back.
4. Route 2 asks again **before** its `ACTIVE`.
5. The second End route: `INACTIVE`, then a second hand-back.
6. At teardown, no third hand-back, because the cluster was already returned.

**App report** (the same run):

```
+9081ms dashboard end route pressed
+20609ms route computed in 1164 ms …
+23695ms dashboard end route pressed
navigation_focus_requests=2 navigation_focus_responses=2 … navigation_focus_denials=0 … navigation_focus_released=true
navigation_focus_handbacks=2 navigation_focus_handback_answers=2 navigation_focus_handback_type=1 (NAV_FOCUS_NATIVE)
navigation_states_sent=NAV_STATE_ACTIVE:2,NAV_STATE_INACTIVE:2
dashboard_trip=false dashboard_trips=2 dashboard_turn_card=false dashboard_end_route_presses=2
```

The two `handback_answers` are Prodigy's echoes. They are counted, and are not denials.

**The run as a whole reported FAIL**, on one line that has nothing to do with focus:
`app report lacks dashboard_resume_presses=1`.

- The emulator's player list is now empty (`media_players_listed=0
  media_players_no_launcher=2`, and frame `frames/028.png` reads "No media players
  found").
- So the P7.5 Resume press lands on no row (`media_changed=0`).
- The P7.4/P7.5 runs this morning listed two players (`media_players_browsers=2`), so the
  list has changed since. That comes from the players backend's filtering (`MediaPlayers`),
  which this slice does not own.

Every focus, state and End route expectation in the same run passed, and the receiver's
own checks all passed. Frame 028, after the second End route, shows no turn card and no
route line, with the favourites tile back.

### 3.4 Guidance regression run

`LAB_ROUTING=… LAB_MAP=…/massachusetts.map LAB_VIDEO_CONFIG=1920x1080 tools/emulator-lab.sh
guidance`, run as `emulator-lab/20260924T154826Z`: **pass**, with `receiver_checks=23
receiver_check_failures=0`.

- Cluster states `1,3,1,3,1,2,0`; maneuvers `6,8,5,7,5,36,39`; named steps 47/48; smallest
  gap 1478 ms. The same results as P7.4's runs.
- Re-routing and arrival keep the cluster, as P6.5 intended:
  - `navigation_focus_requests=1`;
  - `navigation_focus_handbacks=0`;
  - the receiver saw exactly two focus requests: `PROJECTED` at the start, and `NATIVE`
    only at teardown, after arrival.
- The receiver stops 2 s after arrival, inside the 30 s hold (`guidance_arrival_ends=0`),
  so the hold's own hand-back is not exercised here.

## 4. Not verified — needs the truck (or the Pixel)

- **How SYNC 4 reacts to getting the cluster back mid-session.** This is the main
  question, and the lab cannot answer it. Ford grants `PROJECTED` 15 ms after a request
  and answers once with two responses. What it says to a `NAV_FOCUS_NATIVE` request, and
  what its cluster then shows, has never been seen:
  - its own navigation;
  - a blank turn area;
  - our last maneuver frozen.

  The report now records the answer (`navigation_focus_handback_answers` and `_type`). A
  drive that ends a route and then sets another would answer the question. **The
  operator's eye on the cluster at the moment of End route is the evidence.**
- **Whether Ford grants the second request.** With "send regardless" on (the truck
  default), the second route's maneuvers go out either way, after the grant or after the
  focus wait. Without it, a head unit that will not re-grant would leave the second route
  off the cluster. `navigation_focus_requests=2` with `navigation_focus_responses=1` in a
  truck report would say so.
- **Whether handing back between routes is wanted at all.** P6.5 kept the cluster on
  purpose, and p7-4-5 §3.1 flagged this as a question rather than a decision. The plan
  (§5.4) asks for it, so it is built. If Ford's own navigation takes the cluster and
  something is lost, the fix is to publish `GUIDANCE_IDLE` again from `Guide.clear()`,
  which is one line. The session keeps both paths.
- **The 30 s arrival hold's hand-back** is exercised only by code reading and the Rust
  test. The lab's guidance script stops 2 s after arrival.
- **A new route set within one session tick of the clear** keeps the cluster, because
  `Ended` is overwritten before the session sees it. That is deliberate (§1.2) and untested
  on a device.

## 5. Needed from files this slice does not own

- **Players backend / `MediaPlayers`** (and whoever owns the P7.5 lab expectation): the
  emulator now lists no players, so `endroute` fails on `dashboard_resume_presses=1` (§3.3).
  Either the emulator's two browser services should still be listed, or the `endroute`
  Resume expectation in `tools/emulator-lab.sh` needs to accept a pane with no rows. This
  slice changed neither.
- **`core/aap-jni/src/manager.rs`**: one constant was added there (§1.3). It is in the
  crate this slice owns, but was not on its list.
- **`docs/p7-plan.md`** (coordinator): §5.4's "releases navigation focus" is now true.
  p7-4-5-results §3.1 can point here.

## 6. Amendments — proposed, not applied

- **spec §10** (reports): `navigation_focus_handbacks`, `navigation_focus_handback_answers`
  and `navigation_focus_handback_type`. Counts and an enum.
- **spec** (navigation): a route the driver ends hands the cluster back
  (`NAV_FOCUS_NATIVE`), and the next route requests it again. Idle and arrival keep it.
- **plan.md P7 rows**: P7.4 "End route releases navigation focus: implemented, host- and
  lab-verified; SYNC 4's reaction unverified (truck)".
