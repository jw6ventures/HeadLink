# P6.3 — the routing engine: implementation record — 21 September 2026

The slice the [P6 plan](p6-plan.md) §7 puts third: **`.rd5` + profile import, route
computation off the render thread**, over the BRouter tree vendored in §4.1. Its gate is
"none; evidence", and the evidence it had to produce is the answer to two §10 questions —
*does a 119 MB `.rd5` import fit the `MapData` path?* and *how long does a cross-town route
take to compute, and on which thread?*

Status: **implemented, host-tested against real data, and lab-verified on the emulator.**
A route is computed on the phone's own routing thread from an imported tile while a
1080p dashboard is being projected, with the projection's own checks passing beside it.
**Not measured on the Pixel** — see §5. Nothing consumes a route yet: drawing it is P6.4,
following it P6.5.

Taken as the plan words it — import, routing and threading in one slice — on James's
instruction (21 September 2026), rather than split into "route from a file already on the
phone" and "streaming import" as was offered.

---

## 1. What was built

### 1.1 `RoutingData` — the data, and its import (`RoutingData.kt`)

- **Where it lives**: `files/routing/segments/*.rd5` and `files/routing/profiles/`
  (`car-vario.brf` + `lookups.dat`), app-private, no `INTERNET` permission.
- **The profile copy.** The two vendored files travel in the APK as classpath resources
  (§4.1 of the plan) and BRouter reads them from a `File`, with `lookups.dat` required to
  sit *beside* the profile. `installProfile` writes both **only when they differ** from
  what is packaged: BRouter's `ProfileCache` keys on the file's modification time, so an
  unconditional copy would re-parse the profile on every start. It runs on the routing
  thread, not the main thread.
- **The import streams.** 64 KiB at a time into `<tile>.part`, then a rename; the tile is
  never held in memory. The plan's question — does a 119 MB import fit the `MapData`
  path? — is **yes**: it is the same staged-and-renamed shape, with a 512 MiB mis-pick
  ceiling instead of 4 GiB.
- **The name is load-bearing, so it is enforced.** BRouter finds a segment by its file name
  and nothing else; the header does not say which 5°×5° square it covers. A picked file
  keeps its tile name wherever it appears (`W75_N40 (1).rd5` from a browser's second
  download installs as `W75_N40.rd5`), and a file whose name carries no tile is refused
  rather than installed under a made-up name BRouter would silently never read. The host
  test caught the first version of this refusing the browser case — §3.1.
- **The import is checked by BRouter's own header read** against the installed
  `lookups.dat`, before the rename: a file too short for its own index, a corrupt index
  checksum, and — the one that matters — **a tile built for a different lookup table**.
  Segments are regenerated upstream every few weeks (the tile downloaded for this slice is
  15,458 bytes longer than the one measured for the plan the day before), and a
  lookup-version mismatch otherwise surfaces on the first route, far from the import that
  caused it. A refused import leaves the installed tile untouched and no staging file.

### 1.2 `Router` and `RoutePlanner` — the engine and its thread (`Router.kt`)

Both pure JVM, so the host test runs the same classes the app does.

- `Router.compute` builds a `RoutingContext` over the profile, runs `RoutingEngine`, and
  returns a `Route`: geometry, BRouter's distance and time, and **the positions** of the
  maneuvers it derived. Three settings are decisions:
  - **`quite = true`.** Without an output file BRouter prints every route to stdout as GPX.
  - **`turnInstructionMode = 3`.** `car-vario` asks for mode 1, "let the client choose",
    which leaves the value at zero — and at zero BRouter derives **no maneuvers at all**.
    3 is the `osmand` style, the one its own parameter collector maps car clients to.
    Which style the cluster wants is P6.5's question.
  - **`memoryclass = 64`** MiB of node cache, upstream's own default for the service it
    offers other apps.
- **What a maneuver is, is not read.** `VoiceHint`'s command is package-private in the
  vendored tree, which is held unmodified; `OsmTrack.getVoiceHint(i)` and
  `getExitNumber()` are public, so the route carries each maneuver's index into the
  geometry and its roundabout exit, and nothing else. P6.5 has to choose how to get at the
  command — `FormatJson` output, or a class of our own in package `btools.router` — and
  that is a decision about how "unmodified" is kept, not one to make in passing here.
- `RoutePlanner` is **one daemon thread, `aap-route`**, lowered to
  `THREAD_PRIORITY_BACKGROUND` by the app. One thread because BRouter's parsed profile is
  a process-wide static cache. **A newer request stops the running search**
  (`RoutingEngine.terminate()`) rather than queueing behind it: a second destination does
  not want the first route, and a P6.5 re-route that waits for a stale one is late by
  exactly that long. Every request is answered exactly once, superseded ones included, and
  a queued request overtaken before it starts never builds an engine.
- A 60 s ceiling per route, BRouter's own service default. **Raised to 300 s by P6.3a**,
  after Austin–Denver took 15 s on the desktop — see [P6.3a](p6-3a-results.md) §3.1.

### 1.3 In the app

- **Connection screen**: "Import routing data" beside "Import map", a line naming the
  installed tiles, and a `lat,lon lat,lon` field with "Compute route". Routing is **not tied
  to an attempt or a scene** — a route can be computed with no head unit attached, which is
  how its cost on the phone gets measured. The screen shows distance, minutes, maneuvers
  and compute time; it is the operator's own screen.
- **Report**: a new `[routing]` section, present in every report:

  ```
  routing_engine=brouter-1.7.10 profile=car-vario.brf turn_instruction_mode=3
  routing_profile_installed=true routing_segments=W75_N40.rd5:119403810
  routing_requests=1 routing_origin=lab routing_pending=false routing_superseded=0
  routing_result=ok routing_thread=aap-route routing_compute_ms=12930
  routing_distance_m=19253 routing_seconds=1893 routing_points=644 routing_maneuvers=19 routing_roundabout_exits=3
  ```

  **No coordinates, no geometry and no destination** — the P6 plan §9.6 proposal,
  implemented ahead of the amendment as P6.2 did. The event line says the same totals.
  BRouter's own error messages were read for this: none carries a coordinate, but "datafile
  W75_N40.rd5 not found" names a tile, which says which 5° square a destination was in. That
  is the same granularity as the installed-tiles line and is left as it is.
- **Lab intent**: `lab_route` (debug builds only), on its own or with `lab_socket`. On its
  own is how the lab asks for a route **mid-projection**, which is the case the slice is
  about.

### 1.4 In the tools

- `tools/jni-host-test/RoutingTest.java`, run by `jni-host-test.sh` with the vendored jar
  on the classpath. `ROUTING_SEGMENTS=<dir>` adds real routes; the 119 MB tile is not in
  the repository, so by default those are skipped **and the summary line says so**.
- `tools/emulator-lab.sh`: `LAB_ROUTING=<dir holding W75_N40.rd5>` installs the tile the way
  `LAB_MAP` installs a map (and deletes the 119 MB staged copy once it is on the device),
  then sends `lab_route` once the app reports `PROJECTING`. Works under any script;
  `LAB_ROUTE` overrides the pair.

## 2. The runs — 21 September 2026

Data: `W75_N40.rd5` from `brouter.de/brouter/segments4/`, **119,403,810 bytes**, kept at
`~/.cache/openauto-android/rd5/` with its SHA-256 beside it. The route is the lab position
(42.4242, −71.2121) to Boston Common (42.3551, −71.0656): 14.3 km as the crow flies.

### 2.1 Host, HotSpot JDK 17 — 101 checks, pass

```
routing-test: import bytes=119403810 ms=58
routing-test: cross-town compute_ms=998 distance_m=19253 seconds=1893 points=644 maneuvers=19
routing-test: warm compute_ms=569
routing-test: superseded after ms=300 error=operation killed by thread-priority-watchdog after 0 seconds
jni-host-test: ok 101 routing checks
```

19.3 km of road for 14.3 km of crow flight (1.35×), 31.5 minutes, 36.6 km/h average — a
plausible car across Greater Boston. The route starts and ends within 500 m of the two
points, its 19 maneuvers are in order on its geometry, and the same request twice gives
the same answer. A Boston–New Haven search was stopped 300 ms in by a second request and
reported superseded, with the second answered normally.

The 58 ms import is a page-cache copy on the same volume and says nothing about a phone
reading from a document provider.

### 2.2 Build container — pass

`tools/android-build.sh`: lint as errors, `verify-apk.sh` on both APKs (one ABI, no
`INTERNET`), and host checks 18 + 17 + 52 + 196 + **57 routing** + 90. The container does
not mount the tile, so it runs the 57 data-free checks and says the rest were skipped.
Release APK 9,449,347 bytes.

### 2.3 Emulator, during projection — pass

`LAB_ROUTING=~/.cache/openauto-android/rd5 LAB_MAP=…/massachusetts.map LAB_VIDEO_CONFIG=1920x1080 tools/emulator-lab.sh track`
— the P6.1 moving-map script, the heaviest thing the lab runs, with a route asked for on
top of it. Results in `emulator-lab/20260921T162028Z`.

```
+1400ms stage=PROJECTING native stage projecting
+3998ms route requested from lab (request 1)
+16930ms route computed in 12930 ms on aap-route: 19253 m, 1893 s, 644 points, 19 maneuvers
+29233ms stage=IDLE final_category=PEER_SHUTDOWN
routing_result=ok routing_thread=aap-route routing_compute_ms=12930
projection_frames_rendered=797 projection_submitted=797 projection_accepted=796 projection_dropped_congested=0
receiver_track_moved=1 metres=123 map_changed=0.483857 tiles_changed=0
receiver_track_moved=2 metres=122 map_changed=0.497085 tiles_changed=0
receiver_video_decoded_frames=795 receiver_video_decode_errors=0 receiver_video_size=1920x1080
receiver_checks=12 receiver_check_failures=0
emulator-lab: pass
```

- **The same route on ART as on HotSpot**: 19,253 m, 1,893 s, 644 points, 19 maneuvers.
  The vendored tree behaves identically on the device runtime.
- **12.9 s, entirely inside projection**, on `aap-route`. Thirteen times the desktop's
  time, on an emulator whose CPUs were also running a software H.264 encoder at 1080p and
  rasterising a moving map, with the routing thread deliberately below both.
- **What it cost the picture**, from the receiver's own arrival times aligned on the first
  keyframe:

  | Window | Frames | fps | Longest gap |
  | --- | --- | --- | --- |
  | before the request (1.5 s) | 46 | 29.8 | 91 ms |
  | **during the search (12.9 s)** | 345 | **26.7** | 96 ms |
  | after it (12.2 s) | 382 | 31.2 | 99 ms |

  About a tenth of the frame rate while it searched, **no longer gap than outside it,
  nothing dropped**, and the P6.1 track checks — the map following a moving vehicle —
  passed across the same run with the same numbers they had without routing
  (`moved=123 rasterised=284` against P6.1's 124 and ≈289). The "after" window's 31.2
  is delivery catching up, not a faster source. This is an emulator with a software
  encoder; §5 says what it does not establish about the phone.

## 3. What the tests caught

### 3.1 A browser's second download would have been refused

The first tile-name matcher required `.rd5` straight after the tile, so
`W75_N40 (1).rd5` — what a browser names the second copy of a download — was refused as
"not a routing tile name". The host test asserted it would be accepted and failed. Now the
tile is found anywhere in a name that ends `.rd5`, bounded so that `XW75_N40.rd5` and
`W75_N405.rd5` are not tiles and `W75_N40.rd5.part` is not either.

### 3.2 A test that was wrong

`-71.2121,42.4242 -71.0656,95.0` was meant to be an impossible latitude and is not: its
second point is latitude −71, longitude 95, which is Antarctica. `parse` accepted it
correctly. Recorded because it is the case `parse` exists for: coordinates pasted in the
wrong order are *valid numbers*, and the only defence is the rule that the text is always
`lat,lon`. A route to a swapped pair near the equator would be computed happily and wrong.

## 4. Verification actually performed

- `tools/android-build.sh`, three times; green on the last (§2.2).
- `RoutingTest` on the host JDK with the tile: 101 checks (§2.1), covering tile naming and
  the grid arithmetic against BRouter's `NodesCache`, request parsing, the profile copy
  and its leave-alone rule, refusals of an unnamed file, a stub, garbage and a truncated
  real tile — each leaving the installed tile untouched — a lookup-version mismatch
  caught at import, a missing tile reported by name rather than thrown, supersession with
  and without data, and the real route.
- One emulator lab run (§2.3), `track` with `LAB_ROUTING` and `LAB_MAP` at 1920×1080.

Not run: `tools/check.sh` — no Rust changed.

## 5. Not verified — the honest list

- **Nothing on the Pixel.** The number this slice most wanted — a cross-town route's cost
  on the phone — is an emulator number. The phone has more and faster cores than the
  emulator's share and a hardware encoder, so the emulator is likely pessimistic, but that
  is a guess until the connection screen's "Compute route" is pressed on the phone with a
  tile imported. That costs no drive: no head unit is needed.
- **The import through the document picker has not been run.** The lab installs the tile
  with `adb`, exactly as `LAB_MAP` does; the picker path is the same `ContentResolver`
  stream the map import uses, and 119 MB through it on the phone is untested.
- **`car-vario` is still unevaluated** as a car profile for turn-by-turn. One route looked
  plausible by distance, time and endpoints. Nobody has looked at it on a map.
- **The maneuvers are counted, not understood.** 19 positions and 3 roundabout exits;
  whether they are the right 19 is P6.5's to establish, and it needs the command §1.2
  says is not reachable yet.
- **Memory.** 64 MiB of node cache was enough for 19 km within one tile. A route crossing
  into a second tile, or a long one, was not tried; BRouter's failure there is a "memory
  limit reached" message, which the report would carry.
- **Minification is still off**, and the `Class.forName` keep-rule question from §4.1 is
  unchanged — now with a caller that would hit it.

## 6. Amendments this needs — proposed, not applied

Behind those already queued by P5.5, the P6 plan §9, P6.1 §7 and P6.2 §7.

1. **plan.md** — a **P6.3** row under the P6 section: routing engine, import and routing
   thread, implemented, host-tested with real data, lab-verified. No gate.
2. **spec §10** — the P6 plan §9.6 rule now covers a third producer: `[routing]` carries no
   coordinates, geometry or destination, and the event log carries the same totals only.
3. **spec §2.2 / §13** — the vendored tree now runs. The invariants measured at vendoring
   (§4.1: reproducible, zero AndroidX, one ABI, no `INTERNET`) were re-checked by
   `verify-apk.sh`; the reproducible-build pair was **not** re-run for this slice.

## 7. Research status

§2 is **measured on this machine**, 21 September 2026: the host lines are `RoutingTest`
output, the lab lines are quoted from `app-report.txt` and `receiver.txt` of the run named,
and the frame-rate table is arithmetic on `receiver-trace.jsonl`'s arrival times, aligned
by taking the app's "first keyframe sent" (+1453 ms) as the first frame's arrival — good to
tens of milliseconds, which is small against 12.9 s windows.

The `VoiceHint` visibility, `turnInstructionMode` behaviour, `quite` and `ProfileCache`
findings in §1 are **source reading** of the vendored tree. The route the tests computed
is consistent with them — 19 maneuvers with mode 3 set, no GPX on stdout — but no run
tried mode 0 to see the maneuvers disappear.
