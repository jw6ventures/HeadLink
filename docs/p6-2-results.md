# P6.2 — the navigation channel, driven by a scripted maneuver sequence: implementation record — 21 September 2026

The slice the [P6 plan](p6-plan.md) §7 puts second, and the one that exists to answer §2's
contradiction: **Ford has advertised a `navigation` channel since the first vehicle run,
asking for turn images at a 400 ms floor and giving no dimensions for them.** More reading
will not settle that. Sending on the channel and watching the cluster will.

Status: **implemented, lab-verified, and verified on the vehicle the same day.** Ford SYNC
4.0 rendered a scripted maneuver on its own instrument cluster — icon, road name and
distance countdown — with navigation focus acquired and released cleanly, which **meets
G9 as the P6 plan §8 words it**. §3.6 is the pair of runs and §6 is what that still does
not establish. G9 remains a *proposed* gate; spec §11.1 is governed and unamended.

**§2's contradiction is answered.** Ford advertises `NEXT_TURN_IMAGE` and sends no image
dimensions; it turns out not to need any. A purely semantic stream — no image bytes
anywhere — renders on the cluster, with Ford drawing the maneuver icon itself.

---

## 1. What this slice is, and what it deliberately is not

It is the **head-unit half** of navigation, which the plan's §1 lever says is independent
of the owned half: the `navigation` channel puts maneuvers on the truck's own instrument
cluster, and there is no routing in it at all.

The maneuvers are **scripted**. No route exists yet — P6.3 is the routing engine — so the
sequence is a fixed demonstration route, which is the same move P5.3 made when it sent a
generated tone rather than capturing another application's playback, and for the same
reason: it makes the gate checkable without first solving the harder problem behind it.
P6.5 replaces the script with a guidance loop over a computed route and changes nothing on
the wire.

Three things are left out on purpose and should not be read as oversights:

- **No turn images.** §2 of the plan says start semantic, not pictorial, and the only wire
  path that ever carried image bytes is the deprecated `0x8004`. Ford asks for images and
  declines to say how big; this source sends none and records that it sent none.
- **No lanes.** `NavigationLane` is in the schema; plan §0 puts lane guidance out of scope
  because BRouter's model has no lane data to fill it with. Filling it from a script would
  make the gate evidence claim a capability this project has no way to produce for real.
- **No estimated time of arrival string.** The field is a `string` of unrecorded format and
  this source does not know the vehicle's local clock. It sends `time_to_arrival_seconds`
  and leaves the text absent rather than putting an invented clock time on a driver's
  cluster.

## 2. What was built

### 2.1 The schema, vendored (9 files)

`protocol/upstream/open-android-auto/oaa/navigation/` gains `InstrumentClusterMessages`,
`Navigation{State,Notification,TurnEvent,FocusRequest,FocusResponse}Message` and
`{ManeuverType,LaneShape,TurnSide}Enum`, all from the same pin (`61eab61`) and all held
unmodified. Nothing outside the vendored tree is newly imported, so no pin bump and no new
upstream audit: `NavigationChannel`, the descriptor P6.0 already reads, was in the closure
through `ChannelDescriptorData.proto` all along. `tools/proto-gen` gains the six roots;
`docs/licenses.md` carries the count.

### 2.2 The message ids, and where focus lives

`ids::navigation` (`core/aap-proto/src/lib.rs`) carries all eight channel ids including the
two deprecated ones, which are defined and unused so a capture showing them is
recognisable. **Navigation focus is on the control channel**, ids 13 and 14 — the session's
third focus type after video and audio, and like audio focus it lives on the control
channel rather than the channel it governs. The schema says so in as many words, and the
pinned Prodigy receiver independently answers on `0x000e`
([references.md](references.md)).

`NavigationFocusType` has **no release value**. Handing the cluster back is therefore a
request for `NAV_FOCUS_NATIVE` — the head unit's own navigation — which is a reading of the
enum and is marked as one in the source; no head unit has ever answered one.

### 2.3 The content (`core/aap-session/src/navigation.rs`)

`Script`, `Leg`, `Guidance`, `Units`, `Distance`. **Everything is derived from one
number**: at any instant the script knows only how far along the route it is, and the
distance to the maneuver, the time to it, the distance to the destination and the time to
that are all arithmetic on that one position. This is P6.1's rule for position, speed and
bearing carried over — a source reading four independent tables could put a maneuver 300 m
away and 40 s away in the same message and have neither be wrong on its own.

The demonstration route is six legs and 3220 m: an ordinary turn, a roundabout with an exit
number, a slight turn, a motorway entrance, a motorway exit, and an arrival. Six maneuver
*classes*, not six of one, because the question the gate asks is whether the cluster
renders a maneuver stream. The pace is 20 m/s, chosen so each maneuver holds the cluster
for 14–45 s — long enough to photograph, short enough that the whole route including the
destination fits in a three-minute drive. It is not a plausible speed for the streets it
names and is not meant to be; nothing downstream reads it as one.

### 2.4 The channel (`core/aap-session/src/projection.rs`)

Opened in the setup chain after `media_info` and before the sensor channel, opt-in and
non-fatal exactly as `OpenMediaInfo` and `OpenAudio` are. **Only the open is part of
setup**; everything after it runs on its own timers during projection, because none of it
may hold up the picture. The sequence, which is the plan §3 hypothesis followed exactly:

1. Open channel N, non-fatal. A refusal is recorded and skipped.
2. **Wait for `0x8001 InstrumentClusterStart`** rather than assuming a cluster is there.
3. `NavigationFocusRequest(NAV_FOCUS_PROJECTED)` on the control channel, asked for once.
4. On `NAV_FOCUS_PROJECTED`: `NavigationState(ACTIVE)`, then stream `0x8006` and `0x8007`.
5. On stop, arrival, or shutdown: `NavigationState(INACTIVE)` and the cluster handed back.

Four rules worth stating because each one is a decision:

- **The advertised `minimum_interval_ms` is a floor, not a default.** The interval used is
  the larger of the descriptor's and this source's own, and the report says which won. Ford
  advertises 400 ms; the source's default is 1000 ms, so on the truck the source's own
  interval binds.
- **An update is one notification and one distance**, and the interval is enforced across
  the pair rather than per message. Whether a head unit counts messages or updates is not
  recorded anywhere and no head unit has answered it; the report carries both counts so a
  vehicle can contradict the reading.
- **A `NAV_FOCUS_NATIVE` is not a permanent refusal.** The request stands and a later grant
  starts the sequence — the rule audio focus learned on the vehicle, where giving up on the
  first non-grant would make a head unit that grants late indistinguishable from one that
  never grants.
- **The cluster going away pauses without abandoning.** `InstrumentClusterStop` stops the
  maneuvers; the script's clock keeps running, so a cluster that comes back finds the route
  where it would have been. No state is withdrawn and no focus handed back, because a head
  unit may take the cluster for a reverse camera and give it back.

### 2.5 `navigation_force`, and why it exists

Off by default, which is the plan's sequence: wait for the cluster, send only to one that
granted focus. Setting it sends the sequence anyway — after still asking, and still waiting
out the bounded wait — and the report says in as many words that nothing was waited for.

Reachable from **all three** entry points: the app (a checkbox that appears only when the
cluster box is ticked, since on its own it governs nothing), `source-harness`
(`--navigation-force`), and the lab (`LAB_NAVIGATION_FORCE=1`). The app was the one that
mattered and the one it was initially missing from — see §4.5.

It exists because of what a drive costs. If Ford opens the channel and never announces a
cluster, the unforced run yields "Ford never sent `InstrumentClusterStart`", which is a real
finding and no maneuver evidence at all. G9 explicitly allows a degraded pass for a head
unit that "opens the channel, accepts the messages and displays nothing"; this flag is how
one drive can produce that instead of a second drive being needed for it.

### 2.6 Both sides of the lab

- `tools/hu-sim`: `Cluster` (announce on open, after N media units, stop after N messages,
  or never) and `NavFocus` (projected, native, an empty response, native-then-projected,
  silent). The simulator decodes each message from the pinned schema rather than comparing
  against what the source believes it sent.
- `tools/lab-receiver`: `LabNavigationHandler`, wrapping **Prodigy's own**
  `NavigationChannelHandler` — a second reader that shares no code with this source, which
  is the point of checking there rather than only in `hu-sim` (spec §8.1: both ends of the
  simulator were once written from one reading of one schema, and agreed with the bug).
  Prodigy never sends `InstrumentClusterStart`, so the subclass adds one behind
  `--navigation-cluster start`; `none` leaves the receiver as the head unit that advertises
  the service with nothing behind it, and `--navigation-expect` says which outcome is the
  pass. Gaps are timed on the receiver's own clock, so a source ignoring the advertised
  floor cannot pass by reporting the interval it believes it used.
- `tools/emulator-lab.sh navigation`: the same thing driven by the **app** rather than
  `source-harness`, which is the configuration a vehicle attempt actually uses.

### 2.7 One fix outside the slice

`MapEngine.setNight` now returns early on a map with no vector layers. It is not
navigation code and it is in this slice because this slice's lab run is what found it —
see §4.2.

### 2.8 The report, and what it does not say

Fifteen new lines under `navigation_*`: the channel and whether it opened, the two
intervals and which bound, cluster starts and stops and whether one ever arrived, the focus
exchange in full, the states sent by name, the message counts, and the last maneuver.

**No road name, no instruction text and no destination.** The plan §9.6 puts a destination
on the same footing as `sensor_location`, and the script's names being invented is not a
reason to establish the habit of printing them — P6.5 inherits this code with real ones in
it. The last-maneuver line is the maneuver, the exit number, the distance and the two times.

## 3. The runs — 21 September 2026

### 3.1 Against the independent receiver, cluster announced — pass

`LAB_STOP_AFTER=projection LAB_SCRIPT=navigation tools/lab-receiver.sh demo`.

The source's account:

```
navigation_requested=true navigation_channel=9 navigation_open=true navigation_refused=false navigation_min_interval_ms=1500 navigation_interval_ms=1500 navigation_floor=head-unit
navigation_cluster_starts=1 navigation_cluster_stops=0 navigation_cluster_ms=6 navigation_cluster_timeout=false navigation_forced=false
navigation_focus_requests=1 navigation_focus_responses=1 navigation_focus_type=2 (NAV_FOCUS_PROJECTED) navigation_focus_ms=6 navigation_focus_denials=0 navigation_focus_invalid=0 navigation_focus_timeout=false navigation_focus_released=true
navigation_states_sent=NAV_STATE_ACTIVE:1,NAV_STATE_INACTIVE:1 navigation_notifications_sent=1 navigation_distances_sent=3 navigation_suppressed=0 navigation_completed=false navigation_unhandled=0
navigation_script=legs=6 route_m=3220 pace_s=161 units=imperial navigation_last_step=maneuver=8 (TURN_NORMAL_RIGHT) exit=- step=value=850 unit=6 (DISTANCE_UNIT_FEET) text="850 ft" to_step_s=13 to_destination_s=158
```

The receiver's own decode of the same session:

```
receiver_navigation_state=1
receiver_navigation_step=maneuver=8 steps=1 lanes=0 destinations=0
receiver_navigation_active=states=1 cluster_announced=true maneuver=8 step_distance=0.2 mi unit=5 value=2 to_step_s=16 to_arrival_s=161
receiver_navigation_rate=updates=3 smallest_gap_ms=1500 advertised_floor_ms=1500
receiver_navigation=states=1,2,0 notifications=1 distances=3 updates=3 cluster=announced maneuver=8 lanes=0 destinations=0 expect=sequence unit=6 value=850 to_step_s=13 to_arrival_s=158 eta_string=false unexpected=0
receiver_checks=11 receiver_check_failures=0
```

Two things in there are worth naming. The **smallest gap was 1500 ms against an advertised
floor of 1500 ms** — measured on the receiver's clock, not reported by the source — and the
receiver advertises a floor *slower* than the source's own default for exactly that reason.
And the run crossed a unit boundary by itself: the first distance was `0.2 mi` as
`DISTANCE_UNIT_MILES_P1` value 2, the last `850 ft` as `DISTANCE_UNIT_FEET` value 850, so
both branches of the rendering and the `_P1` reading were on the wire in one session.

### 3.2 Against a head unit with no cluster — pass, by staying silent

`LAB_NAVIGATION_CLUSTER=none`, the receiver left as Prodigy actually behaves:

```
receiver_navigation_silence=observed_ms=12031 states=0 notifications=0 distances=0
receiver_navigation=states= notifications=0 distances=0 updates=0 cluster=none ... expect=silence ... unexpected=0
receiver_checks=6 receiver_check_failures=0
```

and the source, having opened the channel and then waited:

```
navigation_cluster_starts=0 navigation_cluster_stops=0 navigation_cluster_ms=- navigation_cluster_timeout=true navigation_forced=false
navigation_focus_requests=0 ... navigation_states_sent=- navigation_notifications_sent=0
```

Focus was never even asked for. This is the plan §3 rule holding: wait for the cluster
rather than assuming it.

### 3.3 The same head unit, forced — pass

`LAB_NAVIGATION_CLUSTER=none LAB_NAVIGATION_FORCE=1`: `navigation_forced=true`,
`navigation_cluster_starts=0`, and the full sequence on the wire with
`receiver_checks=11 receiver_check_failures=0`. This is the shape a degraded G9 pass would
have.

### 3.4 Driven by the app, on the emulator — pass, after §4.2

`tools/emulator-lab.sh navigation`: the same channel driven by the APK through the JNI
configuration rather than by `source-harness`, which is the path a vehicle attempt takes.

```
emulator-lab: app reached PROJECTING
receiver_navigation_active=states=1 cluster_announced=true maneuver=8 step_distance=0.2 mi unit=5 value=2 to_step_s=16 to_arrival_s=161
receiver_navigation_rate=updates=3 smallest_gap_ms=1499 advertised_floor_ms=1500
receiver_navigation=states=1,2,0 notifications=1 distances=4 updates=4 cluster=announced maneuver=8 lanes=0 destinations=0 expect=sequence unit=6 value=750 to_step_s=11 to_arrival_s=156 eta_string=false unexpected=0
receiver_video_decoded_frames=141 receiver_video_configs=1 receiver_video_decode_errors=0
receiver_checks=12 receiver_check_failures=0
```

and the app's own account of the same session:

```
navigation_requested=true navigation_channel=9 navigation_open=true navigation_refused=false navigation_min_interval_ms=1500 navigation_interval_ms=1500 navigation_floor=head-unit
navigation_cluster_starts=1 navigation_cluster_stops=0 navigation_cluster_ms=13 navigation_cluster_timeout=false navigation_forced=false
navigation_focus_requests=1 navigation_focus_responses=1 navigation_focus_type=2 (NAV_FOCUS_PROJECTED) navigation_focus_ms=14 navigation_focus_denials=0 navigation_focus_invalid=0 navigation_focus_timeout=false navigation_focus_released=true
navigation_states_sent=NAV_STATE_ACTIVE:1,NAV_STATE_INACTIVE:1 navigation_notifications_sent=1 navigation_distances_sent=4 navigation_suppressed=0 navigation_completed=false navigation_unhandled=0
```

A gap of 1499 ms against a 1500 ms floor is the emulator's scheduling, not a source
sending early: the check allows a tenth, for the same reason the whole measurement is on
the receiver's clock rather than the source's account of its own interval.

**The first attempt at this run crashed the app before any of it**, and what stopped it is
§4.2.

### 3.5 The vehicle's own question, both ways, through the app

The pair a drive has to choose between, run end to end through the APK and its own
checkbox against a receiver that announces no cluster — which is what Prodigy does unaided
and may be what Ford does.

**Forced** (`LAB_NAVIGATION_CLUSTER=none LAB_NAVIGATION_FORCE=1`):

```
navigation_cluster_starts=0 navigation_cluster_stops=0 navigation_cluster_ms=- navigation_cluster_timeout=false navigation_forced=true
navigation_states_sent=NAV_STATE_ACTIVE:1,NAV_STATE_INACTIVE:1 navigation_notifications_sent=1 navigation_distances_sent=4
receiver_navigation=states=1,2,0 notifications=1 distances=4 updates=4 cluster=none ... expect=sequence ... unexpected=0
receiver_checks=12 receiver_check_failures=0
emulator-lab: navigation: the cluster channel was driven without a go-ahead, and says so
```

**Unforced** (`LAB_NAVIGATION_CLUSTER=none`), the default:

```
navigation_cluster_starts=0 navigation_cluster_ms=- navigation_cluster_timeout=true navigation_forced=false
navigation_focus_requests=0 navigation_focus_responses=0 navigation_focus_type=-
navigation_states_sent=- navigation_notifications_sent=0 navigation_distances_sent=0
receiver_navigation_silence=observed_ms=12000 states=0 notifications=0 distances=0
emulator-lab: navigation: nothing was sent to a cluster that never announced itself
```

Nothing is sent, focus is never even asked for, and the report distinguishes *this*
silence from a refused channel and from a denied focus. §3.2 is the same case through
`source-harness`; this pair is through the app, which is the path a vehicle attempt takes.

### 3.6 The vehicle — 21 September 2026, Ford SYNC 4.0, G9 met

Two runs 78 seconds apart, versionCode 21, differing only in the second checkbox. Reports
`report-20260921T154316Z-1f4db62b.txt` (unforced) and
`report-20260921T154434Z-ad772b8a.txt` (forced), both in the repository root.

| | unforced | forced |
| --- | --- | --- |
| Channel 11 open | **yes, in 5 ms** (`status=0`) | yes, in 5 ms |
| `InstrumentClusterStart` | **never** | **never** |
| Navigation focus requested | 0 — correctly never asked | 1 |
| Focus answered | — | **`NAV_FOCUS_PROJECTED`** at 79 ms |
| Maneuvers | 0 | **11 notifications, 46 distances** |
| Focus released | — | `true` |
| Projecting | 35.7 s | 45.3 s |

```
navigation_requested=true navigation_channel=11 navigation_open=true navigation_refused=false navigation_min_interval_ms=400 navigation_interval_ms=1000 navigation_floor=source
navigation_cluster_starts=0 navigation_cluster_stops=0 navigation_cluster_ms=- navigation_cluster_timeout=false navigation_forced=true
navigation_focus_requests=1 navigation_focus_responses=2 navigation_focus_type=2 (NAV_FOCUS_PROJECTED) navigation_focus_ms=79 navigation_focus_denials=0 navigation_focus_invalid=0 navigation_focus_timeout=false navigation_focus_released=true
navigation_states_sent=NAV_STATE_ACTIVE:1,NAV_STATE_INACTIVE:1 navigation_notifications_sent=11 navigation_distances_sent=46 navigation_suppressed=0 navigation_completed=false navigation_unhandled=0
navigation_script=legs=6 route_m=3220 pace_s=161 units=imperial navigation_last_step=maneuver=5 (TURN_SLIGHT_LEFT) exit=- step=value=790 unit=6 (DISTANCE_UNIT_FEET) text="790 ft" to_step_s=12 to_destination_s=116
```

**The operator's observation, which is the gate evidence**: on the **instrument cluster**
behind the wheel, a maneuver **icon**, the **road name**, and a **distance countdown**.
Distances looked sensible, with the caveat that simulated data is hard to judge — see §6.

#### Three findings about Ford, and one about this source

**Ford never announces a cluster.** `navigation_cluster_starts=0` in both runs, on a head
unit that advertises the service and opens the channel in five milliseconds. **The P6 plan
§3 sequence, followed literally, would never have sent anything to this truck** — the
unforced run is the proof, and it is a correct implementation of a rule that is wrong for
this vehicle. §7.6 proposes the amendment.

**It grants focus without having offered a cluster.** Asked at 64 ms, answered
`NAV_FOCUS_PROJECTED` by 79 ms. The announcement and the grant are independent here, so
"no cluster announced" does not mean "no cluster".

**It answers one request with two responses.** `navigation_focus_requests=1
navigation_focus_responses=2`, `denials=0` — both `PROJECTED`, both inside 79 ms, and
neither is our own hand-back echo (the teardown guard returns before the counter). Unlike
`AudioFocusResponse` there is no `unsolicited` flag, so nothing distinguishes a volunteered
state from a reply. Harmless — the handling is idempotent — and recorded because a source
that treated a second response as a state change would have something to explain.

**And the source agrees with itself.** 46 distances over 45.3 s is one per 985 ms against
the configured 1000 ms; 11 notifications is nine five-second restatements plus two leg
changes; and `to_destination_s=116` against a 161 s route puts the script at 45 s, which
is `projecting_ms` to within a second. Ford's advertised 400 ms floor never bound
(`navigation_floor=source`).

## 4. What the lab caught

Five, and **the second has nothing to do with navigation** — it is a crash on the drive
that this slice's lab run happened to be the first to walk into.

### 4.1 Prodigy grants navigation focus by echoing the request — including the hand-back

Prodigy's `AASession` answers a focus request by sending the **request's own payload** back
as the response. That is convenient for a grant, and wrong at the end of a session: the
source hands the cluster back with `NAV_FOCUS_NATIVE`, Prodigy echoes `NAV_FOCUS_NATIVE`,
and a source counting that as a response records a focus denial on every clean shutdown.

Found by reading the receiver's source before running it, not by a failing run. The fix is
one guard — focus responses after the hand-back are ignored — and the test
`stopping_goes_inactive_and_hands_the_cluster_back` asserts `navigation_focus_denials=0`
precisely so a future change cannot reintroduce it.

### 4.2 A dashboard with no map data died on its first frame

The emulator run failed with `the app never reached PROJECTING`, and the receiver had
already seen the channel opened, the cluster announced, focus granted and one maneuver go
out. The app had **crashed**:

```
E AndroidRuntime: FATAL EXCEPTION: aap-scene
E AndroidRuntime: java.lang.IllegalStateException
E AndroidRuntime: 	at org.oscim.map.Map.setTheme(Map.java:222)
E AndroidRuntime: 	at org.openautoandroid.source.MapEngine.setNight(MapEngine.kt:187)
E AndroidRuntime: 	at org.openautoandroid.source.DashboardRenderer.updateMap(DashboardRenderer.kt:161)
E AndroidRuntime: 	at org.openautoandroid.source.ProjectionPipeline.frame(ProjectionPipeline.kt:246)
```

preceded by VTM's own `E org.oscim.map.Map: No vector layers set`.

**Nothing in P6.2 is anywhere near this.** `MapEngine.start` deliberately keeps running
when there is no map data — "a degraded dashboard, not a failed session" — and themes the
map only inside the branch that installed a tile source. But `DashboardRenderer.updateMap`
calls `setNight` on **every frame**, and `setNight`'s only guard was `theme.isNotEmpty()`,
which on a map with no layers is empty forever. VTM's `Map.setTheme` throws on a map with
no vector layer, so the first frame killed the scene thread and the process with it.

It went unnoticed because **every emulator run since the dashboard became the default
scene has supplied `LAB_MAP`** — P5.5's and P6.1's needed real data for what they were
measuring. This slice's lab run needs no map at all, so it was the first to ask the app to
project a dashboard with none, which is also what a first-run operator does.

The consequence on the drive is not small: this source subscribes to `NIGHT_DATA`, Ford
sends `is_night=true` once per session, and the dashboard scene is the default. **An
operator who had not imported map data would have lost the app on the first frame of every
projection**, with the pane drawing the very "no map data" background the code goes out of
its way to keep showing.

The fix is one guard: `MapEngine` records whether a tile source was installed and
`setNight` returns early when none was.

**Confirmed pre-existing and confirmed fixed.** `tools/emulator-lab.sh taps` — no
navigation anywhere in it — crashed identically on the same build, which is what rules
P6.2 out as the cause. After the guard, both `taps` and `navigation` reach `PROJECTING`
and the navigation run decodes 141 frames with `receiver_check_failures=0` (§3.4).

This is the third defect in three slices that no build, lint or host check could see, and
the second — after P5.5's redraw ordering — that only appeared because something *else*
changed about how the app was being run. The lab keeps earning its cost by being the only
place the app is asked to do something nobody wrote a test for.

### 4.3 Two defects in the lab harness itself

Neither is a source defect; both cost a run, and the second was measuring the wrong thing.

**An apostrophe inside a single-quoted container script.** The emulator lab's whole body is
a single-quoted argument to `sh -c`, so a comment containing "the receiver's decode" ends
the string early. `tools/lab-receiver.sh` already carried a note saying so;
`tools/emulator-lab.sh` now carries one too.

**The silence window started at the wrong zero.** Proving a source sends *nothing* needs a
watched interval, and the receiver timed its twelve seconds from its own process start.
That is fine on the host, where `source-harness` connects immediately — and worthless in
the emulator lab, which launches the receiver and then spends minutes booting Android, so
the window was already satisfied the instant projection began. The run reported a silence
nobody had watched for, and the app-side check caught it only because it also asserts the
*source* reached its cluster deadline: `navigation_cluster_timeout=false` against a
receiver claiming twelve seconds of quiet. Now timed from the first script tick, which is
after the first decoded picture.

Worth keeping: **an assertion about an absence is only as good as the interval behind it**,
and the interval has to start when the thing being watched could first have happened.

### 4.4 The switch the vehicle needs was not reachable from the vehicle

`navigation_force` reached `SessionSettings` and the configuration writer but never
`AttemptSettings`, so `ProjectionService` could not set it and no app attempt could ever
produce `navigation_force=true`. Every host test passed, because the test constructs
`SessionSettings` directly and the configuration text was right.

It matters for exactly the case the switch exists for. If Ford opens channel 11 and never
announces a cluster, the only entry point that can reach the truck sends nothing, and the
drive yields one finding instead of two — and no setting could change it, only a rebuild.

Now wired through, with the emulator run in §3.5 driving the whole path the app takes.
The general shape is worth naming: **a flag is not implemented until something a user can
reach can set it**, and a host test that constructs the settings object itself steps over
precisely the wiring that was missing.

### 4.5 `Cluster::StopAfter` announced no cluster at all

The simulator's "start on open, then stop after N messages" mode checked for
`Cluster::OnOpen` by equality when deciding whether to announce, so the stop-after mode
never started. The test that exercises it failed with zero maneuvers sent, which read at
first as a source that would not send to a cluster — the opposite of the truth. Cheap, and
a reminder that a simulator's own bugs look exactly like the source's.

## 5. Verification actually performed

- `tools/check.sh` — fmt, clippy `-D warnings`, `cargo test --workspace`, release build,
  generated-protobuf check. Green, offline.
- `tools/android-build.sh` — lint as errors, APK invariants (`verify-apk.sh`: one ABI, no
  `INTERNET`), 18 + 17 + 52 + 196 + 90 host JVM checks, up from 89: the new one is the
  cluster configuration text.
- `tools/lab-receiver.sh build` — the receiver compiles clean against the pinned Prodigy
  library with the navigation handler in it.
- Eleven new host tests in `core/aap-session/tests/projection.rs` (62 in that file, up
  from 51) and six unit tests in `navigation.rs`, covering: the opt-in and the open order; the
  whole handshake decoded by the head unit; silence against an unannounced cluster; the
  forced path; a late grant after `NAV_FOCUS_NATIVE`; the floor in both directions; a
  refused channel; the hand-back on a limit and on a head-unit shutdown; a route that
  arrives and ends itself leaving no past deadline; and a cluster that stops and starts
  again.
- Four `tools/lab-receiver.sh demo` runs (§3.1–3.3 and the re-checked silence case), all
  with `receiver_check_failures=0`, and six emulator runs: a `navigation` run that found
  §4.2, a `taps` control that established §4.2 was not this slice's doing, a `navigation`
  run on the fixed build, one that found §4.3's quoting defect, and the §3.5 pair —
  forced and unforced against a silent cluster — both ending `emulator-lab: pass`.

## 6. Not verified — the honest list

**G9 is passed** (James, 21 September 2026) on §3.6's observation: the maneuver appeared on
the factory instrument cluster with focus acquired and released cleanly. What follows is
what that run did *not* establish, and none of it blocks the gate.

- **The icon appeared; nothing says it was the *right* icon.** Ford drew it from
  `ManeuverType`, an enum marked `confidence: unverified` upstream, and the operator saw
  an icon rather than checking it against the maneuver the source had sent. A wrong enum
  mapping would look like a working cluster showing the wrong arrow. Cheap to settle:
  watch one leg change and see whether a slight left is drawn as a slight left.
- **The units are still a guess, and the run could not settle them.** "Sensible, but hard
  to tell with simulated data" is the honest reading of a countdown that has nothing real
  to be checked against. The `_P1` tenths question is untouched — see the protocol below.
- **Which road `road_info` means is still unestablished.** A name appeared; the names are
  invented, so nothing says whether Ford read it as the road being entered or the road
  being left.
- **The `_P1` value scaling is a reading, and the vehicle has not settled it.** Tenths is
  the only sensible one and it is what goes on the wire, with `display_text` carrying the
  same distance as a string beside it.

  **A protocol that would settle it in one drive**, costing no code: the demo route's first
  leg starts at 320 m, which renders as `DISTANCE_UNIT_MILES_P1` value **2** with text
  `0.2 mi`, and crosses into feet at 305 m. Watch the cluster through that boundary. `0.2
  mi` then `1000 ft` means the tenths reading is right; `2 mi` then `1000 ft` means Ford
  reads the value as whole miles and the source is wrong by a factor of ten.
- **Imperial renders; metric is untested.** The confounder this was flagged as turned out
  not to bite — imperial displayed — but `navigation_units=metric` has never been on a
  vehicle, so nothing says the cluster accepts both.
- **`road_info` is assumed to mean the road being entered.** Android's own `Step` model
  reads that way and nothing records which way the wire means it. A cluster reading it the
  other way shows a name that was true a second ago rather than a wrong one, which is why
  this was not worth guarding further.
- **One update is assumed to be a notification and a distance together.** If Ford counts
  messages rather than updates, this source sends at twice the rate it believes it does at
  each step change. Both counts are in the report so a vehicle can say so.
- **Modern or legacy is still untested.** `0x8006` needs `CarInfo` PDK ≥ 1.6 head-unit-side
  and there is no way to read a PDK version from what Ford sends. This slice sends modern
  only; `0x8005` remains a fallback to try, not one to assume, and nothing here tries it.
- **The script is not a route and never deviates.** No snapping, no re-routing, no
  relationship at all between the maneuvers and the vehicle's actual position — a drive
  with this on will count down to a turn that is not there. P6.5 is where the two meet.
- **The emulator run proves the app's configuration path, not a display.** There is no
  picture check for this slice, because the cluster is not in the projected stream.
- **No route reached its destination.** Both vehicle runs stopped around a third of the
  way through a 161 s route, so `NAV_STATE_INACTIVE` was sent by the teardown rather than
  by arrival. The arrival path is lab-verified only, and what Ford's cluster does when a
  route *ends* has never been seen.

## 7. Spec and plan amendments this needs

**Applied 22 September 2026** to `spec.md` and `plan.md` (or to the non-governed file named), with the rest of the queue.

`spec.md` and `plan.md` are governed. These sit behind the six P5.5 proposed, the six in
the [P6 plan §9](p6-plan.md), and the three in [P6.1's record](p6-1-results.md) §7, all
still unapplied.

1. **plan.md** — a **P6.2** row under the P6 section P6.1's record already asks for: the
   navigation channel, scripted maneuvers, focus on the control channel, implemented,
   lab-verified and **vehicle-verified**. Its implementation tasks may be checked, and so
   may **G9** — §3.6 is the evidence and the operator observation behind it.
2. **spec §11.1** — **G9** as the P6 plan §8 words it, and **marked passed**
   (21 September 2026, reports `1f4db62b` and `ad772b8a`, operator observation in §3.6).
   One addition learned here: the degraded-pass clause should require the report to
   distinguish *no cluster was announced* from *the cluster was announced and displayed
   nothing*. Ford turns out to be the first, and only `navigation_force` makes the second
   observable at all.
3. **spec §8.1** — the rule this slice adds to the standing one is about **a peer's
   convenience becoming a source's bug**: the lab receiver grants focus by echoing the
   request, so the source's own hand-back comes back as a grant-shaped response. Act on
   what a head unit answers, *and* on which question it was answering.
4. **spec §10** — as the P6 plan §9.6 already proposes: navigation evidence carries no
   destination, no route geometry and no road names by default. This slice implements it
   ahead of the amendment rather than after it, so the amendment is now a description of
   the code rather than a change to it.
5. **spec §12** — the `navigation` integration in the same list as `media_info` and
   `audio`: opt-in, non-fatal, no Android permission of its own.
6. **P6 plan §3, step 2 — the rule is wrong for this vehicle.** "Wait for `0x8001
   InstrumentClusterStart` rather than assuming the cluster is there" is a good rule that
   would have sent nothing to the only head unit this project has. Ford never announces a
   cluster and grants focus anyway. The step should become: *ask for focus once the
   channel is open; treat `InstrumentClusterStart` as confirmation when it comes and not
   as a precondition.* That is a behaviour change with a default change behind it
   (`navigation_force` off means "do nothing" on this truck), so it belongs in an
   amendment rather than in a quiet edit — but it should be made before P6.5 builds on
   the current default.
7. **Not an amendment, a standing job.** §4.2 was found because a lab run happened to use
   a configuration nothing had used before. The emulator lab has never run *without*
   `LAB_MAP` on a dashboard scene until now, and that is the first-run operator's
   configuration. A run with no map data belongs in whatever routine set of lab runs a
   release goes through; it costs one emulator start and it caught a crash on the drive.

## 8. Research status

§3.6 is **measured on the vehicle**, 21 September 2026: two reports pulled off the phone
with `adb run-as`, quoted verbatim, plus one operator observation — icon, road name and
distance countdown on the instrument cluster — which is the only part of this document
that no instrument recorded and the only part G9 turns on. The three Ford findings in it
are read off the reports; the fourth item (the source agreeing with itself) is arithmetic
on the same lines.

§2.2's placement of navigation focus on the control channel is **schema text plus an
independent implementation**: the vendored `.proto` says so in a sentence, and the pinned
Prodigy receiver answers on `0x000e` without having read it from us. The message id table
is upstream's `ian.java` handler trace; Prodigy names five of the eight at the same values
([references.md](references.md)). **Only `0x8007` carries upstream wire verification, and
none of it has been on this project's wire with a vehicle at the other end.**

§2.3's route, pace and units are **chosen by this slice**, and the reasoning for each is in
the source beside it. None of it is measured from anything.

§3 is **measured on this machine**, 21 September 2026: four lab runs, the lines quoted
verbatim from the saved reports and `receiver.out`. The 1500 ms gap is the receiver's own
`QElapsedTimer`, not the source's account of its interval.

§4.1 is **source reading of the Prodigy receiver**, confirmed by the test written for it;
it was never observed as a failing run because it was fixed before one could happen. §4.2
is a failing test and its fix.

Answered by the drive, and previously the only thing standing between this slice and G9:
Ford's cluster has a turn-by-turn area and renders a semantic stream in it. Still not
researched: whether the icon it draws matches the maneuver sent, what it does at a route's
end, and whether it reads the `_P1` value as tenths — §6 has a protocol for the last.
