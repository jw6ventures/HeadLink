# P6.5 — the guidance loop, feeding real maneuvers into P6.2's channel: implementation record — 21 September 2026

The last slice in the [P6 plan](p6-plan.md) §7: snap the vehicle to the route, count down to
the next maneuver, notice when the vehicle leaves the route, re-route, and put all of it on
the vehicle's own cluster through the channel P6.2 opened.

Status: **implemented, host-tested, and lab-verified on the emulator** against the
independent receiver, over a drive along real streets that misses a turn. **Not run on the
vehicle.** G10 asks for a drive on the factory display with correct maneuvers and a
successful re-route after a deliberate deviation; nothing here substitutes for that, and
the gate is not claimed.

---

## 1. The decision P6.3 left open: how to read what a maneuver *is*

P6 plan §10 asked it: `VoiceHint`'s command is package-private in the vendored BRouter tree,
which is held byte-identical to its pin. The two ways in were `FormatJson`/`FormatGpx`
output, or a class of our own in package `btools.router`.

**Taken: a class of our own**, `app/src/main/kotlin/btools/router/VoiceHintReader.kt`. It
lives in the app's source tree, not in `vendor/`, and only reads `cmd` and re-exports
BRouter's command constants. The vendored tree is untouched, so provenance is still a diff
against the pin. The reason for choosing it over the formatter: the formatter reads the same
field through a string format and fails at **run time** if upstream changes it; this fails
at **compile time**, which is where a re-sync should find out. Android has no module system
to object to a split package, and the host tests put the vendored jar and this class on one
classpath. The Kotlin compiler accepted the package-private access across the module
boundary. That was checked by building, not assumed.

`GuidanceTest` pins BRouter's command numbers (`TL=2`, `TR=5`, `RNDB=13`, `END=100`), so a
re-sync that renumbers them fails a host check rather than a drive.

## 2. What was built

### 2.1 App (Kotlin)

- **`Route`** (`Router.kt`) now carries each maneuver's BRouter command and BRouter's own
  cumulative time at every point (`OsmPathElement.getTime()`). Times to a maneuver are
  differences of two of those, so they move with the route's speed model rather than the
  vehicle's momentary speed.
- **`Maneuvers`**: BRouter command → raw `ManeuverType` value plus a plain instruction. It
  is a reading of two schemas, recorded as one. Choices: `RNDB` (right-hand traffic) →
  `ROUNDABOUT_ENTER_AND_EXIT_CCW` (34) with its exit number, 180° U-turn → left, `EL`/`ER`
  → slight off-ramps, `OFFR`/`BL` are not maneuvers. **No road names:** BRouter's
  `lookups.dat` carries no `name` tag, so the notification has an instruction and no
  `road_info`, and `current_road` is left off the wire rather than sent blank.
- **`RouteFollower`** (pure JVM): snaps a fix to the route (flat projection, a 1 km window
  ahead and 50 m behind while on the route, the whole route otherwise), tracks progress, and
  names the next maneuver with distance and time to it and to the destination. All of these
  derive from one position, P6.2's rule. States: `JOINING` → `ON_ROUTE` ⇄ `OFF_ROUTE`, and
  `ARRIVED` 30 m from the end. Thresholds: on the route within **40 m**; off it after **4 s
  and 3 fixes** beyond that; while joining, only beyond 250 m. The step cursor only moves
  forward while on the route, so GPS jitter back across a turn just taken does not put that
  turn back on the cluster.
- **`Guide`** (main thread): drives the follower from every vehicle fix (the phone's while
  the vehicle has none) and re-routes through the existing `Navigation`/`Routing` path when
  the vehicle is off the route (`routing_origin=reroute`). It retries after 15 s if still
  off. It publishes to the session through the new JNI entry. A new route is placed at once
  from the fix already in hand, so the cluster goes straight from `REROUTING` to the first
  maneuver.
- **Re-route heading**: `RouteRequest.startBearing`, the direction of travel measured over
  the last 15 m of fixes (not the head unit's bearing field, whose scale is still inferred).
  It is passed to BRouter's `startDirection`/`forceUseStartDirection`. See §4.2 for how
  little that turned out to do.
- **Settings**: the cluster box now plays the followed route by default. A new "play the
  fixed demonstration sequence" box (and the `lab_navigation_script` extra) brings back
  P6.2's script.

### 2.2 Session (Rust) and JNI

- `navigation::Source` (`script` | `route`), config key **`navigation_source`**. The
  default is `script`, so `source-harness` and every existing test are unchanged. The app
  writes `route` unless the script box is ticked.
- `navigation::{RouteState, GuidanceLink}`: the same shape as `NowPlayingLink`. The app
  publishes; the session snapshots and sends at the head unit's interval, and anything
  published in between is overwritten.
- In route mode, focus is still requested as soon as the cluster announces itself, but
  `ACTIVE` is sent only when there is a route: guiding → `ACTIVE`, rerouting → `REROUTING`
  (only from `ACTIVE`), idle/arrived → `INACTIVE`. The cluster is **kept** on arrival so the
  next destination needs no new handshake. It is still handed back at session end, as
  before.
- `NativeBridge.nativeSubmitGuidance(JIJIILjava/lang/String;DIDI)I`, registered at load like
  the others.
- Report: `navigation_source`, `navigation_maneuvers` (distinct maneuvers put on the wire),
  `navigation_arrivals`; the app adds three `guidance_*` lines. **No coordinates, no
  instruction text, no destination** in either. The instruction goes to the cluster and
  nowhere else.

### 2.3 Lab

- `tools/lab-receiver`: `TrackPlayer` can drive a polyline (`--track-file`, `--track-speed`)
  instead of the block circuit, and a new **`guidance`** script. The vehicle waits at the
  kerb until the source says `ACTIVE`, drives the whole file, then waits up to 15 s for the
  source to say it arrived. It checks the states in order (active, rerouting, active again,
  inactive last), that at least three distinct maneuvers arrived and the last was
  `DESTINATION` (39), that the countdown fell at least 20 times and never rose by 50 m or
  more while one maneuver held, and the advertised floor.
- `tools/jni-host-test/LabDrive.java` wrote `tools/lab-receiver/drives/lexington.csv`: a
  real route from the lab position to Lexington's Battle Green, followed for 839 m, then
  275 m the wrong way along another route, then a way back to the same destination. That
  is 219 points and 4,645 m, derived from OSM data (ODbL) and public roads, not anybody's
  trip.
- `tools/emulator-lab.sh guidance` (needs `LAB_ROUTING` and `LAB_MAP`): shares the
  destination as OsmAnd's text share while parked, then checks the app's side (route source,
  `REROUTING`, arrival, at least one re-route asked for and computed, joined, vehicle as the
  fix source, and no `Guidemarker`/coordinates in the report).

## 3. Verification

### 3.1 Host

- `tools/check.sh`: fmt, clippy `-D warnings`, all Rust tests green. New: three unit tests
  in `navigation.rs`, three route-mode channel tests in `tests/projection.rs` (the whole
  life on the wire, stopping mid-route, a channel that never had a route), and config parse
  cases in `tests/manager.rs`.
- `tools/android-build.sh`: lint, debug and release APKs, `verify-apk.sh`, and the
  container's host tests. `GuidanceTest` runs 134 synthetic checks there, and
  `JniContractTest` covers the new JNI entry and the `navigation_source` config text.
- With the real tile, on the host JDK: **1,467 guidance checks**. The 644-point Boston route
  is followed point by point, on the route at every point, and all 19 BRouter maneuvers then
  `DESTINATION` are shown in order: `6,8,5,8,7,6,34,34,34,7,3,4,5,22,4,7,8,4,5,39`.
  `RoutingTest` passes 103.

### 3.2 Emulator lab — pass (second run)

```
LAB_ROUTING=~/.cache/openauto-android/rd5 LAB_MAP=~/.cache/oaa-vtm/maps/massachusetts.map \
  LAB_VIDEO_CONFIG=1920x1080 tools/emulator-lab.sh guidance
```

Results in `~/.cache/openauto-android/emulator-lab/20260921T211331Z/`. The receiver's own
decode:

```
receiver_guidance=departed maneuver=6 step_distance=590 ft path_m=4645 cruise_ms=18
receiver_guidance=states=1,3,1,3,1,2,0 maneuvers=6,8,5,7,5,36,39 countdown_falls=46 countdown_rises=0 worst_rise_m=0 smallest_gap_ms=1480 travelled_m=4645
receiver_checks=21 receiver_check_failures=0
```

The app's account:

```
+5446ms route computed in 1529 ms on aap-route: 4061 m, 516 s, 193 points, 6 maneuvers
+61698ms left the route by 111 m; re-routing
+62286ms route computed in 552 ms on aap-route: 3349 m, 423 s, 159 points, 2 maneuvers
+68298ms left the route by 116 m; re-routing
+68782ms route computed in 453 ms on aap-route: 3468 m, 438 s, 164 points, 2 maneuvers
+267099ms arrived at the destination
guidance_reroutes=2 guidance_reroutes_done=2 guidance_reroute_failures=0 guidance_arrivals=1 guidance_max_on_route_offset_m=40
navigation_states_sent=NAV_STATE_ACTIVE:3,NAV_STATE_REROUTING:2,NAV_STATE_INACTIVE:1 navigation_notifications_sent=48 navigation_distances_sent=175
navigation_source=route navigation_maneuvers=8 navigation_arrivals=1
```

The receiver saw the planned route's first four maneuvers (6, 8, 5, 7), then the re-route's
(5, 36, 39). The decoded 1080p frames show the map following the vehicle along the
re-routed line. 8,081 frames decoded with no errors.

`guidance_max_on_route_offset_m=40` is the threshold itself. The lab drive is geometry
from the same tile, so that is a fix mid-departure snapped at the edge, not GPS noise.
Real noise is for the vehicle to measure.

### 3.3 Regression

`tools/emulator-lab.sh navigation` (P6.2's script, now opt-in through
`lab_navigation_script`) — pass, §5.

## 4. What the runs caught

### 4.1 The first lab run: a floor violation, and three lab and report faults

The first run (`20260921T210017Z`) did the whole loop — the same states and maneuvers as
above — and failed four checks:

1. **The source broke the head unit's interval.** The receiver measured two updates
   **490 ms apart against its 1500 ms floor**. When the cluster went back to `ACTIVE` after a
   re-route, the route path cleared the interval deadline so the new maneuver would go out
   at once. It now clears only the restatement clock; the state goes at once and the
   maneuver waits for the interval. `tests/projection.rs` pins that. This is a genuine
   source defect, and the rule it broke is the one P6.2 was careful about.
2. The receiver's path drive stopped half a metre short of its last point with no speed
   left to cover it, so "finished" never became true (lab fault).
3. `guidance_joined=0`: a join happening inside `Guide.onRoute` was not counted, and the
   "max on-route offset" included fixes that were on their way off the route (112 m).
4. The P6.3 plain-route check ran under the guidance script (lab-script fault).

### 4.2 BRouter does not know which way the vehicle faces

An offline simulation of the drive through the real `RouteFollower` and `Router`, run
before any emulator time, showed it first. The lab then confirmed it: **the first re-route
after the missed turn starts by going back the way the vehicle came.** The vehicle, still
driving on, is off that route within six seconds, and a second re-route from further along
fits. Both lab runs have two re-routes for this reason.

Passing the direction of travel is BRouter's own answer (`startDirection`, which its app
uses for recalculations), and it is now passed. **It did not change this case.**
`car-vario` sets `turncost=90`, and `StdPath` prices a turn at `(1 − cos θ) × turncost`, so
a U-turn costs about 180 m. The way ahead here is longer than that, so BRouter still turns
round. `GuidanceTest` records the angle (180° with the heading) rather than asserting an
improvement that did not happen.

On a real road the result is a cluster that says `REROUTING` twice in a few seconds and a
first re-route that asks for a U-turn. That is honest, but not pleasant. The fix would be
a routing decision — a stronger start penalty, or a via point ahead of the vehicle — and it
is left as an open question (§6) rather than made here.

### 4.3 BRouter's `END` never reaches a track

The real-route host check failed on its first run. BRouter's `END` marker is internal to
its voice-hint processing and **no route has it**; on the two Massachusetts routes checked,
the last hint is an ordinary turn. The follower now adds the destination step itself, and
exactly once. The synthetic tests could not have caught this, because they were written
from the source reading.

## 5. Regression: P6.2's script through the app

`tools/emulator-lab.sh navigation`, `20260921T211859Z`: **pass**, `receiver_checks=12
receiver_check_failures=0`. The same result P6.2 recorded, now reached through the new
opt-in:

```
navigation_source=script navigation_maneuvers=1 navigation_arrivals=0
navigation_script=legs=6 route_m=3220 pace_s=161 units=imperial navigation_last_step=maneuver=8 (TURN_NORMAL_RIGHT) ...
```

Not re-run: the other P6.1–P6.4 lab scripts (`track`, `LAB_DESTINATION`). They exercise
code this slice touched only by addition (`Routing.finished` now also calls `Guide`); the
guidance run drives the same map, routing and destination paths.

## 6. Not done, and open

- **The vehicle.** G10 needs a drive: a destination shared from OsmAnd on the Pixel
  (release build), a deliberate wrong turn, and someone watching the cluster. The P6 plan
  §10 question "does the icon Ford draws match the maneuver sent?" is now answerable on
  every turn of a real route rather than on one scripted leg.
- **Thresholds are unmeasured.** 40 m, 4 s and 3 fixes are reasoned, not measured. The
  report's `guidance_max_on_route_offset_m` is how a drive says whether 40 m is generous.
- **Re-route direction** (§4.2): whether to penalise turning round more strongly, or to
  route from a point ahead of the vehicle.
- **The projected map shows no maneuver.** The cluster gets the next turn; the dashboard
  pane does not. Nor is the driven part of the route line trimmed; the line is the whole
  route, as in P6.4.
- **No road names and no lanes**, because BRouter's data has neither.
- **Roundabout direction** is the right-hand-traffic reading (34). Whether Ford's icon for
  34 is the one a US driver expects is part of the icon question.
- The phone as the fix source (`guidance_fix_source=phone`) has never run; the lab always
  has a vehicle fix.

## 7. Amendments this proposes — not applied

1. **P6 plan §7**: P6.5 row to "implemented; host- and lab-verified", G10 still open. §10:
   the `VoiceHint` question answered (§1), and the re-route direction question added.
2. **spec §10 / P6 plan §9.6**: the rule now covers the cluster's instruction text. It goes
   to the head unit and to no report or event log.
3. **P6 plan §4.1's "held unmodified" invariant** should say in as many words that a file of
   ours in a vendored package (`btools.router.VoiceHintReader`, in the app tree) is not a
   modification of the vendored tree.
