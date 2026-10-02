# P9.2 — Route boxes, matching, delay and nogo placement: implementation record — 28 September 2026

The geometry of the [P9 plan](p9-plan.md): the box asked of HERE around the route ahead
(§4), HERE's roads laid on BRouter's route (§5), the delay they add to the arrival time
(§6), and where BRouter's no-go points go and how heavy they are (§7). Three pure-Kotlin
files, no Android, host-tested with `TrafficMatchTest`.

Status: **implemented and host-tested**, on synthetic routes and on BRouter's real route up
Stemmons Freeway (I-35E) in Dallas from `W100_N30.rd5`, with hand-written HERE stretches
laid along it. **Also run, outside the tests, against P9.0's real 1° Dallas capture** (§3):
matching found HERE data on 82% and 91% of two real routes, in about 50 ms on the host.
**Nothing is wired in yet**: the traffic loop, `Guide`, routing and the dashboard call
these functions in later slices. Nothing has run on the Pixel.

---

## 1. What was built

### 1.1 `TrafficArea` — the box (plan §4)

- **`boxes(route, alongM, lookAheadKm = 30.0): List<TrafficBox>`** — the box around the
  route from `alongM` metres along it to `lookAheadKm` further, or to the destination if
  nearer, padded by 2 km (`PAD_M`) on every side. The ends are interpolated where they fall
  between two points. The east–west padding is worked out at the box's most poleward edge,
  so it is never less than 2 km anywhere in it.
- **HERE's 1° limit.** The box is cut into an even grid of cells no bigger than
  `MAX_SPAN_DEG` = 0.999° either way, a thousandth under HERE's limit so that
  `TrafficBox.query()`'s five decimals can never round a cell over it. Only the cells the
  padded route passes through are kept, so a long diagonal does not ask for the empty
  corners. In every case the plan expects this is one box: 50 km on a diagonal near
  Dallas is about 0.4° × 0.4° with the padding. It first needs two cells for 50 km due
  east at about 62°N.
- **`around(...)`** is the single padded box before cutting; **`cells(box)`** the cutting;
  **`acceptable(box)`** says whether HERE would take a box as `query()` writes it.
- **`covers(boxes, route, alongM, aheadM)`** — whether the boxes a fetch was for still hold
  the route from `alongM` to `aheadM` further. For the traffic loop after a re-route: when
  the new route leaves the last fetch's box within, say, half the look-ahead, fetch again
  now rather than at the next refresh.
- **`metres(route)`** — metres along to each point, measured exactly as `RouteFollower`
  measures them, so a `FollowResult.alongM` can be passed straight in and run metres agree
  with the follower's.

### 1.2 `TrafficMatch` — HERE's roads on the route (plan §5, §6)

**`match(route, snapshot)`** (or `match(route, flow, incidents)`) → `RouteTraffic`.

- The route is cut into pieces of at most 10 m (`PIECE_M`). Each piece takes the flow of
  the **closest HERE stretch within 15 m** (`NEAR_M`) of its middle **whose heading there
  agrees within 30°** (`HEADING_DEG`), and of the `subSegments` span it falls in. HERE's
  span lengths are scaled onto the drawn shape's length (they differ by rounding).
- Heading is measured over 10 m each side of the place (`HEADING_WINDOW_M`), on the route
  and on the stretch. HERE's points have five decimals, about a metre, so a 2 m segment's
  own heading can be tens of degrees out.
- A piece nothing agrees with has no data and makes **no run**, as `RouteTraffic` says
  ("points in no run have no data"). The dashboard draws it in the normal colour. It is
  never FREE.
- **Runs** are consecutive pieces on the same stretch at the same `Congestion`. `fromM`
  and `toM` are exact; `fromIndex` is the route point at or before `fromM` and `toIndex`
  the point at or after `toM`, so the points may reach a little past each end.
  **`line(route, fromM, toM)`** gives a run's own line, cut exactly, for drawing.
- **Delay** (plan §6): Σ piece length ÷ HERE's speed, less BRouter's own time for the same
  metres (interpolated from `pointSeconds`, as `RouteFollower` does), never below 0. An
  open span without a speed takes BRouter's time. `RouteTraffic.delayAheadSeconds` then
  sums what is ahead.
- **Closures.** A piece on a span HERE marks `closed` or `reversibleNotRoutable`, or
  matched by an incident that is `roadClosed` or of type `roadClosure`
  (**`closes(incident)`**), is CLOSED. A closed run has speed null and **delay 0**: it is
  a road that cannot be driven, not a wait, so routing handles it
  (`RouteTraffic.closureAhead`, the hard nogos).
- **Incidents** are matched against their own shapes by the same 15 m / 30° rule. Those
  matching any piece are `RouteTraffic.incidents`, in route order.
- **Speed.** HERE's segments are filed in a grid of 100 m squares (`ShapeIndex`, shared
  with `TrafficNogos`), so each piece looks only at the segments near it. The whole 1°
  Dallas cell, 3,929 stretches and 690 incidents, matched against a 15.6 km route in
  **52 ms** on the host, index included (§3).

### 1.3 `TrafficNogos` — where the points go, how heavy (plan §7)

**`of(snapshot, route = null, costPerSecond = { 20.0 }, limit = 300)`** (or
`of(flow, incidents, …)`) → `List<Nogo>`, from the whole snapshot, not only what matched.

- **Hard** (weight null): closure incidents (`closes`), and spans `closed` or
  `reversibleNotRoutable`. **Weighted**: SLOW and QUEUING spans with a delay.
- **Placement:** along the HERE shape, evenly, no more than 150 m apart (`SPACING_M`),
  radius 8 m (`RADIUS_M`), none within 30 m of either end of the shape (`END_MARGIN_M`).
  n = ⌈usable length ÷ 150⌉ points at the middles of n equal shares.
- **Weight:** each of n points weighs delay × costPerSecond(functional class) ÷ (n × 16),
  so the points, each crossed through its middle (16 m inside an 8 m circle), charge the
  span's delay in BRouter cost. Off the route there is no BRouter time, so a span's delay
  is **`delaySeconds(span)`** = length ÷ speed − length ÷ free flow, positive only, 0
  when either speed is missing.
- **`costPerSecond` is a placeholder**, `PLACEHOLDER_COST_PER_SECOND` = 20, awaiting P9.5's
  measurement. 20 is what `KinematicModel`'s code gives for a second of extra time
  (`pw / cost0`) at 121 km/h. It is read from the code, not measured.

Three choices the plan left open, or that its aims needed:

1. **The short-closure rule.** When the margins leave no room (a closed stretch under
   60 m, or a closed span within 30 m of an end), a closure still gets **one point, at
   the middle of the closed part**. A 40 m closure gets one point 20 m from each end. A
   closure must block, and blocking a road beside it is the safer mistake. A jam in the
   same place gets no point. A closure given as a single point blocks at that point.
2. **Crossings** (for §7's stated aim, which the end margins alone do not meet). An
   overpass is not at the end of a stretch. A jammed street's points land every 150 m
   along it, so about one crossing in ten would have a point on the freeway beneath and
   charge it. A point within **23 m** (`CLEAR_M`, the radius plus the matching tolerance)
   of another HERE road crossing its own at over 30° is slid along its own share of the
   stretch to a clear place. A jam's point is dropped if there is none. A closure keeps
   its roomiest point if none of its places is clear. Roads alongside, either way, are not
   crossings: the next stretch, the other direction of an undivided road, a frontage road.
   Only roads HERE sent are known, and classes 4 and 5 are not fetched (§4), so a side
   street crossing mid-stretch can still be charged, as it can at any junction.
3. **The cap, and which points it keeps.** At most 300 (`MAX_NOGOS`): BRouter checks every
   nogo for every segment it weighs. The cap is reached in practice. The whole Dallas cell
   gives about 2,800 points, 1,200 of them hard: 333 closure incidents (263 on class 5
   side streets) and 77 km of HERE spans marked closed or reversible, the managed lanes
   (§3). So:
   - **With `route`** (the followed route; pass it), spans are kept nearest the route
     first, closures before jams at the same distance, then heavier jams first. The jam on
     the route is what an alternative has to beat, and the roads beside it are where
     alternatives go.
   - **Without a route**, every closure first, then jams by delay, heaviest first.
   - A jam is kept whole or not at all, because part of one would charge part of its
     delay. A closure may be cut short, since each of its points blocks on its own.
   - Two hard points closer than 8 m are one: a closed span and a closure incident on the
     same road would otherwise double up.

**What a point cannot tell.** A BRouter circle has no direction. On a divided highway each
carriageway is its own stretch, 20 to 50 m from the other, beyond 8 m. On an undivided road
both directions share the line, so a jam or closure one way also charges or blocks the
other.

---

## 2. Host tests — `TrafficMatchTest`

`tools/jni-host-test/TrafficMatchTest.java`, run with brouter.jar on the classpath and
`ROUTING_SEGMENTS` as its argument. Every HERE stretch is hand-written in HERE's shape:
points to five decimals about 20 m apart, spans laid end to end.

**The scenario** runs on a curving synthetic route (400 points 25 to 300 m apart, turning
0.8° at each) always, and on the real Dallas route when the tile is there. From 3 km of
freeway on it:

| HERE stretch | Where | Expected |
| --- | --- | --- |
| I-35E, 1,200 m, spans free (40 m/s) / queuing (2 m/s) / slow (10 m/s) | 3 m right of the route | three runs at the span boundaries (±25 m) |
| — | 300 m with no data | no run, a frontage road beside it or not |
| I-35E, 1,000 m, slow (12 m/s) | 4 m left | one slow run across the overpass |
| closure incident, 100 m | 2 m right, on the second stretch | a CLOSED run; the slow run split around it |
| construction incident, 200 m | on the first stretch | matched, levels unchanged |
| I-35E SB, queuing, reversed | 30 m left | never matched |
| Frontage, closed | 25 m right, the whole length | never matched |
| Overpass, queuing, 420 m | across the route, perpendicular | never matched |
| closure incident, reversed | 30 m left | not matched |

Checked:

- The six runs, levels and ends. Every run's road is "I-35E". Runs do not overlap, and
  each run's points bracket its metres.
- Delay = length at HERE's speed less BRouter's time for each run, to 0.01 s. The run
  faster than BRouter has delay 0, never negative. The closure has no speed and no delay.
  `delayAheadSeconds` from the start, past the end, and half a run in.
  `closureAhead` before and after it. The matched incidents are `works` then `shut`.
- `line` starts and ends at a run's metres.
- The same response's nogos: exactly 14 within 8 m of the route (3 + 3 + 7 for the jams on
  it, 1 hard for the closure on it), all on those stretches. None from the other
  carriageway, the frontage road or the overpass reaches it. The overpass's points keep
  their distance from the road beneath.

**Also:**

- **Boxes:**
  - 2 km of padding each way, at the box's northern edge too.
  - The look-ahead cut at the destination, and ends placed between points.
  - A 2.5° box cut into three cells edge to edge. A box of exactly 1° cut too; one over
    1° refused by `acceptable`, one just under taken.
  - 50 km on a diagonal near Dallas is one box. 50 km east at 62°N is two cells, each
    one HERE takes, holding the whole stretch.
  - 400 km diagonally: every cell acceptable, the route covered, the empty corners left
    out.
  - `covers`: a fetch covers what it was for and the rest of it 20 km on, not 30 km from
    there, and not a route that turns away.
- **Nogos on plain geometry:**
  - 7 points over 1,000 m, none within 30 m of an end, none more than 150 m apart, all
    8 m.
  - No point for a jam only in a stretch's last 20 m, for free flow, or for a slow level
    faster than free flow.
  - `delaySeconds`, including with a speed missing.
  - Weights summing to delay × cost per second by road class.
  - Closed and reversible spans hard, `roadClosed` and `roadClosure` incidents hard,
    roadworks nothing. Closures first.
  - The short-closure rule: 40 m closed → one hard point at 20 m, a 40 m jam → none, a
    closure in the last 20 m → its middle, a one-point closure → there.
  - A closed span and a closure incident on one road → one set of points.
  - A jam point on a crossing slid 23 m off it; the same road's other direction not
    treated as a crossing.
  - The cap keeps every closure and the heaviest jams, whole.
  - With the route, a light jam on it is kept over heavy ones 55 km away; without it, not.
- **Speed:** a 30 km route of 3,001 points against 500 stretches either way, 0 to 60 m
  off it, must match in under a second on the host.

### 2.1 The runs

The test class compiled with `-Xlint:all -Werror` and was run on its own inside the Android image, after
`./gradlew :app:compileReleaseKotlin`, 28 September 2026:

```
traffic-match-test: busy route points=3001 stretches=500 runs=122 match_ms=25 nogos=300 nogo_ms=24
jni-host-test: ok 227 traffic match checks; real route skipped (no W100_N30.rd5)
```

and with the tile (`/rd5` is `~/.cache/openauto-android/rd5` mounted):

```
traffic-match-test: busy route points=3001 stretches=500 runs=122 match_ms=24 nogos=300 nogo_ms=21
traffic-match-test: real route points=233 distance_m=15591 seconds=904 scenario_from_m=1100
jni-host-test: ok 341 traffic match checks; real route over W100_N30 (Dallas, I-35E)
```

The real route is BRouter's 15.6 km from downtown (32.7800, −96.8050) toward Love Field
(32.8450, −96.8850). The scenario was laid from 1.1 km along, the first 3 km BRouter drives
at freeway speed.

**Full build:**
`flock ~/.cache/openauto-android/p9-build.lock tools/android-build.sh` passed with every
other agent's work in the tree at the time (exit 0; `jni-host-test: ok 227 traffic match
checks; real route skipped (no W100_N30.rd5)`). `android-build.sh` does not pass
`ROUTING_SEGMENTS` into the container, so the real-route part runs only as above.

---

## 3. Against the real Dallas capture (not a test)

P9.0's full-cell captures (`flow_cell`, 3,929 stretches; `incidents_cell`, 690 incidents)
were turned into a plain text file in `~/.cache/openauto-android/p9/`, and a scratch
program there ran the real routes through `TrafficMatch` and `TrafficNogos`. Nothing from
the captures is in the repository; only these counts are.

| Route (BRouter, `W100_N30.rd5`) | Length | Matched | Match | Incidents on it | Delay |
| --- | --- | --- | --- | --- | --- |
| Downtown → Stemmons (I-35E) toward Love Field | 15.6 km | 12.7 km, 26 runs, all free | 52 ms | 0 | 17 s |
| Downtown → I-30 west toward Grand Prairie | 23.7 km | 21.6 km, 26 runs, 30 m closed | 51 ms | 1 | 36 s |

- **Road names are not always roads.** HERE's `description` is sometimes the road
  ("I-35E NORTHBOUND", "I-635") and often the exit or cross street at the stretch's end
  ("Continental Ave/Exit 429", "Oak Lawn Ave/Exit 430"). That is 820 of 3,929 stretches in
  the cell and 71 of 207 downtown. Every run on the Stemmons route above was named that
  way. **"Queue on I-35E" would read "Queue on Oak Lawn Ave/Exit 430".** Whoever writes
  the offer's reason (P9.6) should know it. The road's own name is better taken from the
  map (`StreetNames`, `Route.roadAt`).
- **How far HERE's lines are from OSM's.** Every 10 m of each route was measured to the
  nearest HERE stretch running the same way within 40 m:

  | | I-35E | I-30 |
  | --- | --- | --- |
  | median | 1.5 m | 1.2 m |
  | 90th percentile | 12.7 m | 4.0 m |
  | 95th percentile | 20.6 m | 5.9 m |
  | 99th percentile | 28.1 m | 11.4 m |
  | pieces between 15 and 40 m | 128 of 1,672 | 20 of 2,504 |

  Most of the I-35E pieces between 15 and 40 m are one stretch of 1.1 km (11.2 to 12.3 km
  along). There BRouter's speeds of 14 to 23 m/s say the route is on a ramp or frontage
  road, with the main lanes, the nearest HERE road running the same way, 18 to 35 m off.
  Rejecting them is right. So 15 m holds on these two routes; the drive has the last word.
- **The formula's free-flow delay is small.** All of it, 17 s and 36 s, came from runs
  HERE calls free-flowing but a little slower than BRouter's model. The plan's formula
  counts them; §5 of this record lists it as a question.
- **Nogos:**
  - 2,795 over the whole cell uncapped, 1,216 of them hard, in about 100 ms.
  - Capped at 300 without a route, they are all closures and no jam survives. That is
    why `of` takes the route.
  - With the route, the 300 nearest it were 132 hard and 168 weighted for the I-35E
    route, and 229 and 71 for I-30.
  - None lay within 8 m of the I-35E route (it had no jam that afternoon). One lay on the
    I-30 route: its real 30 m closed span.

---

## 4. Deviations from the plan

- **Crossing rule and route-aware cap** (§1.3): not in the plan's text. Both are for its
  stated aims: no charge on an overpass's road beneath, and the jam on the route surviving
  the cap.
- **No-data pieces make no run** rather than a NONE run, following `RouteTraffic`'s own
  definition.
- **Free-flowing runs carry delay** when HERE's free-flow speed is below BRouter's. This is
  the plan's formula as written (§6); see §5.

## 5. Open questions and what the other slices need

- **BRouter charges one nogo per segment.** In the vendored
  `RoutingContext.calcDistance`, each nogo a segment passes *overwrites* `nogoCost`. A
  segment inside several circles is charged for the last in the list only, and OSM
  segments on a Texas interstate can be hundreds of metres long. P9.5's calibration should
  measure the cost of points placed by `TrafficNogos`, not work it out from the formula.
- **8 m and real geometry.** HERE's lines were within 6 m of OSM's for 95% of the I-30
  route but 20 m on Stemmons. A hard point 8 m wide on a HERE line 10 m off the OSM way
  **does not block it**. The lab's closure check (plan §8, check 4) and the drive will
  say whether closures need a larger radius than jams.
- **"+N min traffic" on free-flowing roads.** 17 to 36 s on these routes, from HERE's free
  flow being a little below BRouter's model. If James finds a "+1 min" on an empty road
  wrong, the fix is one line in `TrafficMatch` (count only SLOW, QUEUING). It is the
  plan's decision, not changed here.
- **The traffic loop** should call `match` and `of` off the main thread (tens of ms on the
  host for a whole cell; the phone is slower), and pass the followed route to `of`.
- **Thresholds** (15 m, 30°, 150 m, 8 m, 30 m, 300) are named constants, first guesses for
  the lab and the drive (plan §12).

## 6. How to run

```
flock ~/.cache/openauto-android/p9-build.lock tools/android-build.sh
```

`TrafficMatchTest` runs with the other host tests; without `ROUTING_SEGMENTS` it skips the
Dallas route and says so. With it, as run here: a Podman run of the Android image with
`~/.cache/openauto-android/rd5` mounted and `ROUTING_SEGMENTS` pointing at it, after
`./gradlew :app:compileReleaseKotlin`.
