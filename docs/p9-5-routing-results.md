# P9.5, routing half — traffic no-go points in BRouter, and the cost calibration — 28 September 2026

This is the part of [P9](p9-plan.md) §10's P9.5 that lives in the router: `RouteRequest`
takes no-go points and `Router` hands them to BRouter (§7). It also measures what a second
of delay costs in BRouter's units (§7, "How heavy"), and runs the overpass and
frontage-road cases of §9 and §12 on real Texas roads. Code: `Router.kt`
(`RouteRequest`, `Route`, `Router`) and the new `TrafficCost.kt`. Host test:
`NogoRoutingTest`.

Status: **implemented, and host-tested on the Texas tiles.** Nothing calls it yet. Putting
the points into real requests and into the live alternatives is another agent's slice
(`Routing.kt`, `LiveAlternatives.kt`), and so is placing them (`TrafficNogos.kt`). Not run
in the emulator lab, on the Pixel or in the truck. The measurements found two ways the
vendored BRouter differs from what the plan assumed (§2), and both are handled here.

## 1. What was built

- **`RouteRequest.nogos: List<Nogo>`**, last in the `@JvmOverloads` constructor, default
  empty. Every existing Kotlin and Java caller compiles unchanged. A point must have finite
  coordinates on the globe and a finite radius above 0. Its weight must be null (a hard
  block) or finite and at least 0. A negative weight would pay the route for entering, and
  NaN is BRouter's own spelling of "hard". `withNogos(list)` copies a request with other
  points.
- **`Router.compute`** passes them to `RoutingContext.nogopoints` as `OsmNodeNamed`. They are
  built the way BRouter's own parser (`RoutingParamCollector.readNogo`) builds them:
  microdegrees offset to be positive, `radius` in metres, `nogoWeight` NaN for hard, and
  `isNogo`. The list and its nodes are made fresh for every search, so nothing carries over
  to the next request. A request with no points runs exactly the code it ran before P9.
  Beyond that, `Router` does three things of its own, for the reasons in §2:
  1. Hard points go last in the list.
  2. Every route found with points is checked for passing inside a hard one
     (`Router.closuresCrossed`). If it does, the search is repeated without the weighted
     points within 2 km of that closure. If it still does, the search is repeated with no
     weighted points at all.
  3. If a search with closures fails for any reason except being stopped (a newer request,
     or the time limit), it is repeated once with every closure made passable at 5 minutes
     a point.
- **The top-speed step-down keeps the points.** The retry at a lower `vmax` after "error
  re-tracking track" (docs/routing-settings.md §4) scales their weights by
  `secondPrice(lower) / secondPrice(asked)`. The weights are BRouter cost, and a second
  costs less at a lower top speed.
- **`Route`** gains `nogoCount` (how many points the search that made it was given) and
  `closuresOpened`. Both are defaulted, and `withTimeScale`, `withRoads` and `remainder`
  carry them. `trafficShaped` (`nogoCount > 0`) is the dashboard's cue for "Traffic ©
  HERE". It is set whether or not the points moved the route, because finding out would
  take a second search. `closuresOpened` tells the "closure ahead, re-route at once" rule
  (plan §7) that this route knowingly goes through one, so re-routing would only find it
  again.
- **`RoutingException.stopped`**: true when BRouter was terminated or timed out, as
  opposed to failing.
- **`TrafficCost`**:
  - `secondPrice(topSpeedKmh)`: BRouter's cost for one second.
  - `linkFactor(functionalClass)`
  - `costPerSecond(functionalClass, topSpeedKmh = 121)`: what `TrafficNogos.of` takes.
  - `pointWeight(delaySeconds, points, functionalClass, topSpeedKmh, radiusM = 8)`
  - `openedClosureWeight(radiusM, topSpeedKmh)`

## 2. What BRouter does with point no-gos

All of this was read in the vendored 1.7.10 source and then confirmed on the tiles. The
first two points are where the plan's assumptions fail.

| Behaviour | Where | Consequence |
| --- | --- | --- |
| **One charge per link, the last circle wins.** `calcDistance` *sets* `nogoCost` for each circle a piece of way passes through, and `OsmPath` adds it once, at the end of the link (the way between two graph nodes). | `RoutingContext.calcDistance`, `OsmPath.addAddionalPenalty` | Weighted points do not add up. On I-35, 7 points at weight 1 across 900 m charged the cost of 2, because the stretch spanned 2 links. §3.4 |
| **A later weighted circle overwrites a hard one** on the same link. | same | Closure points at 10.4 to 10.7 km on I-35 northbound, then jam points further along the same 1.7 km link: BRouter drove straight through the closure, whichever way the list was ordered. |
| A nogo whose circle contains the start or destination *as given* is dropped for that leg. | `RoutingContext.cleanNogoList` | Hard or weighted. |
| A destination whose snapped point lies inside a hard circle is refused: "last wpt in restricted area". A via point there is silently dropped. | `checkMatchedWaypointAgainstNogos` | §4 |
| A start between closure points on a one-way road finds nothing: "no track found at pass=0", in 0.16 s. | search | §4 |
| Circles have no direction. | `calcDistance` | On an undivided road, a jam or closure in one direction also charges or blocks the other. |
| `RoutingContext` is `final`, and the model's path class is `final` and package-private. | | Summing the charges in our own code is not possible without editing the vendored tree. |
| Nogos play no part in the profile cache key. `continueStraight` appends to the context's list (for via points). | `ProfileCache`, `RoutingEngine` | A fresh context and list for each search is enough. `NogoRoutingTest` confirms nothing leaks. |

## 3. The calibration

All of this was run on the host JVM with the vendored jar against `W100_N30.rd5` (and
`W105_N30` for Lubbock), with the app's default options (`vmax` 121, no switches). The
scratch harness is in `~/.cache/openauto-android/p9/nogo/src/btools/router/` and is not in
the repository.

### 3.1 Cost per second over whole tracks, by road class

Ten routes: I-35E Dallas–Denton, US-75 Dallas–Plano, two Dallas arterial trips, I-35
Austin–Round Rock, MoPac, Burnet Rd, Austin–Johnson City, Bastrop–Giddings and
Georgetown–Florence. Cost and time are summed per `highway=` class from the track's own
per-node cost and time.

| Class | 121 km/h: cost/s | km/h | 90 km/h: cost/s |
| --- | --- | --- | --- |
| motorway | 29.4 | 103 | 23.4 |
| trunk | 27.0 | 83 | 20.5 |
| primary | 24.8 | 66 | 16.4 |
| secondary | 21.9 | 39 | 16.7 |
| tertiary | 22.3 | 47 | 17.9 |
| residential | 19.9 | 15 | 13.7 |

Every class sits above a floor that tracks the top speed: about 19 at 121 km/h and about
13.5 at 90. That floor is the kinematic model's price of time alone. A piece of road costs
(pw × seconds + energy) ÷ cost0, where `pw = 2·f_air·v³ − p_standby` and
`cost0 = (pw + p_standby)/v + f_roll + f_air·v²`, with v the top speed in m/s
(`KinematicModel.init`). A stop or a turn's extra seconds cost exactly pw ÷ cost0 each
(`KinematicPath.processWaySection`). Everything above the floor is the energy of the
distance driven.

### 3.2 BRouter's exchange rate between two real routes

Block a 1.5 km stretch of the route with hard points, then compare the way round. The
exchange rate is (cost_B − cost_A) ÷ (time_B − time_A): the price per second of delay at
which BRouter would be indifferent.

| Stretch blocked | Way round | Cost/s at 90 | at 121 | at 137 |
| --- | --- | --- | --- | --- |
| I-35 N, 10 km along | +25 s, frontage | 10.8 | 17.4 | 21.1 |
| I-35 N, 18 km along | +80 s, frontage | 10.4 | 14.6 | 18.5 |
| US-75 N, Dallas | +259 s | 12.6 | 16.3 | 20.2 |
| I-35E N, Dallas | +32 s | 18.1 | 19.1 | 22.4 |
| US-290 Bastrop–Giddings (trunk) | +441 s | 19.7 | 18.1 | 21.3 |
| Georgetown–Florence (trunk) | +579 s | 9.5 | 15.6 | 27.8 |
| MoPac (way round 4.3 km longer) | +130 s | 13.4 | 33.7 | 34.2 |

Four more pairs are left out. In two the way round was quicker but cost more: I-35E at
Lewisville, and US-290 to Johnson City. The other two are arterials, where a few seconds of
rounding swamp the rate: Burnet Rd (+28 s, 12.7) and Lakewood to Oak Lawn (+8 s, 11.3).
The rate is **pw ÷ cost0** again: 12.5 at 90, 19.0 at 121 and 22.3 at 137 km/h. Only a
way round much longer in distance pays for its energy as well.

**Decision:** a second of delay costs `TrafficCost.secondPrice(topSpeedKmh)` = pw ÷ cost0
with `car-vario`'s values (f_roll 232, f_air 0.4, p_standby 250). That is how BRouter
prices its own stops. It depends on the top speed, not on the road class. A queue is time,
not distance.

### 3.3 Link lengths (why the plan's sum rule loses points)

Links are the pieces of way between graph nodes, found from the track's per-section
`vnode0`, on six of the routes:

| Class | Links | Mean | Median | 90th pct | Longest | Under 150 m |
| --- | --- | --- | --- | --- | --- | --- |
| motorway | 480 | 270 m | 179 m | 640 m | 1,638 m | 43% |
| trunk | 332 | 137 m | 93 m | 309 m | 918 m | 67% |
| tertiary | 410 | 96 m | 59 m | 229 m | 1,167 m | 80% |
| secondary | 300 | 72 m | 29 m | 165 m | 783 m | 88% |

### 3.4 When BRouter actually leaves a jam

Take a stretch, jam it with points placed as the plan places them (every 150 m, 30 m end
margins, 8 m radius), and give the points weights that sum to delay × pw ÷ cost0. Then
bisect for the delay at which BRouter leaves the road. The ratio of that delay to the time
the way round really costs (§3.2) would be 1 if the sum rule held.

| Stretch | Class | Way round | Sum rule | ×1.6 | One point per stretch |
| --- | --- | --- | --- | --- | --- |
| US-75 N, Dallas | motorway | 259 s | ×2.67 | ×1.67 | ×0.18 |
| US-290 Bastrop–Giddings | trunk | 441 s | ×1.38 | ×0.86 | ×0.96 |
| Georgetown–Florence | trunk | 579 s | ×2.05 | ×1.28 | ×0.82 |
| I-35 N, 18 km | motorway | 80 s | ×2.20 | ×1.41 | ×0.26 |
| I-35 N, 10 km (1 km) | motorway | 25 s | ×3.23 | ×2.11 | ×0.98 |
| MoPac | motorway | 130 s | ×1.65 | ×1.03 | ×1.87 |
| I-35E N, Dallas | motorway | 32 s | ×1.32 | ×0.88 | ×1.10 |
| Greenville Ave trip (US-75) | motorway | 44 s | ×1.04 | ×0.64 | |
| Preston Rd trip (Tollway) | motorway | 177 s | ×1.53 | ×0.95 | |
| Airport Blvd trip (US-183) | motorway | 129 s | ×0.63 | ×0.41 | |
| N Lamar Blvd | secondary | 153 s | ×0.97 | | |
| S Congress Ave | secondary | 61 s | ×1.15 | | |
| Burnet Rd | tertiary | 28 s | ×0.75 | | |

- On classes 1 and 2 the sum rule under-charges: the median is ×1.6 across ten stretches.
  Their links are longer than the spacing, so some points are never charged. On arterials,
  whose links are mostly shorter than 150 m, it holds (×0.75 to ×1.15).
- **Decision:** `linkFactor` = **1.6 for HERE functional classes 1 and 2, 1 for 3 to 5**. The
  median then comes back to about ×1. The spread (×0.4 to ×2.1) is the variation in link
  length, and no single factor removes it.
- **One point per stretch, rejected.** It carries the whole delay and is charged exactly
  once, but a route can hop round a single 16 m circle on a ramp pair. That is how US-75
  and I-35 left at a fifth of the real delay (×0.18, ×0.26). Points every 150 m cannot be
  hopped round that way.

### 3.5 The result

`costPerSecond(fc, v) = secondPrice(v) × linkFactor(fc)`. At the default 121 km/h that is
**30.4** for classes 1–2 and **19.0** for 3–5 (12.5 × 1.6 = 20.0 and 12.5 at 90 km/h; 22.3
× 1.6 = 35.6 and 22.3 at 137).

**The plan's typical jam**, a 1 km interstate stretch with 3 minutes of delay, has 7 points
(every 150 m, none within 30 m of either end). A road through the middle of an 8 m circle
has 16 m inside it, so each point weighs 180 × 30.4 ÷ (7 × 16) = **48.8**. On an arterial it
is **30.5**. `TrafficNogos` spaces points evenly at *no more than* 150 m, which gives 8
points on the same stretch: 42.7 and 26.7. `pointWeight` takes the count.

## 4. A closure at the start or the destination

Measured on a closure laid on I-35 northbound from 10 to 13 km, with hard points every
150 m:

| Case | BRouter alone | Opened at 60 / 300 / 600 / 1,800 s a point |
| --- | --- | --- |
| Start between two closure points | "no track found at pass=0", 0.16 s | same 19.5 km route every time, 0.25–0.30 s |
| Destination between points (snapped inside a circle) | "last wpt in restricted area", 0.02 s | same route; **0.24 / 0.65 / 1.2 / 3.5 s** |
| Whole route, from outside | way round | same way round; 0.5–0.7 s |

- **Decision: open the closures only when there is otherwise no route.** On failure,
  every hard point becomes weighted at 5 minutes of delay a point
  (`TrafficCost.OPENED_CLOSURE_SECONDS`) and the search runs once more. This covers a
  vehicle already on the closed stretch, a destination on it, and anything else that
  leaves no way round, with no distance threshold to tune.
- The price only has to beat every open road. Its size costs search time, because BRouter
  spreads until it has spent that much cost: 1,800 s took 3.5 s. So it is 300 s.
- A failure costs at most one extra search, which fails fast in the cases above. A search
  stopped by a newer request or by the time limit is not repeated.
- A route through a lost closure (§2, row 2) is caught by `closuresCrossed` and searched
  again. The first retry drops the weighted points within 2 km of the closure, because the
  longest interstate link measured was 1.6 km. The second drops every weighted point.
  Checked on the real case.

## 5. The overpass and frontage-road cases (plan §7, §12)

On I-35 northbound through central Austin (`NogoRoutingTest`):

- **Overpasses.** Two streets cross over or under the main lanes, 2,319 m and 3,309 m along
  the route, with no shared node (grade-separated). They are found as the crossings of
  east–west routes at 30.28 and 30.29° N. A jam stretch ending at each crossing, with 3
  minutes of delay placed under the 30 m rule, leaves both streets **uncharged** (no
  route point inside any circle). Both are routed identically with and without it.
- **A point on the crossing does charge the street**, because BRouter is 2D. A single
  3-minute point there did not divert it, since there was no cheaper street. Across 150
  offsets of a stretch running *through* the crossing, **16 in 150 (11%)** put a point
  within 8 m of the street. So the 30 m rule holds only where a HERE stretch ends at the
  crossing. `TrafficNogos` adds its own rule, sliding points clear of HERE roads that
  cross. Streets HERE does not send (classes 4 and 5) can still be hit at about that rate
  per mid-stretch crossing.
- **Frontage road.** Jam points on the main lanes between the interchanges 4.4 and 7.6 km
  along. The northbound frontage road passes **20.7 m** from the nearest point: not
  charged, and routed identically.
- **The other carriageway: 9.1 m, and this is close.** The southbound main lanes pass
  within 9.1 m of the nearest point, 1.1 m outside the 8 m radius. The rule holds on OSM's
  geometry here, but HERE's shape only needs to be 1 m off towards the other carriageway
  for the southbound lanes to be charged for a northbound jam. The drive (P9.7) or a real
  HERE capture laid on OSM should settle whether 8 m is too big for central Austin.

## 6. What many points cost the search

I-35, 30.6 km. The points sit ~70 m beside the route, so they are weighed but never
charged. Host JVM, best of runs. Relative figures only: the phone is slower, and a
debuggable build is ~30× slower again.

| Points | 0 | 50 | 200 | 400 | 800 |
| --- | --- | --- | --- | --- | --- |
| BRouter alone, best of 3 (ms) | 344 | 539 | 1,076 | 1,838 | 2,911 |
| Through `Router`, best of 2 (ms) | 282 | 437 | 882 | 1,506 | |

About 3 ms per point on the host, linear: 200 points roughly triple the search, and 400
roughly quintuple it. `TrafficNogos.MAX_NOGOS` is 300, about ×4. `LiveAlternatives` runs
several searches per update, so this multiplies there. Worth measuring on the Pixel's
release build before the cap is settled.

## 7. Verified, and how

- **Host, JDK 17, all checks pass:**
  - `NogoRoutingTest`: 68 checks with the tiles, 23 without. The tiles run took 37 s.
  - `RoutingTest`, `RouteOptionsTest`, `RoutePreviewTest` and `GuidanceTest` were rerun
    with the tiles against the new classes (§7.1).
- Commands (the real-route checks need the tiles, which the build container does not
  mount):

  ```sh
  # compile the app's release classes under the shared lock, inside the Android image
  flock ~/.cache/openauto-android/p9-build.lock sh -ec '. tools/podman-env.sh; podman_run --network=none \
      -v "$CACHE_ROOT/gradle:/cache/gradle" -e GRADLE_USER_HOME=/cache/gradle \
      -v "$CACHE_ROOT/android-user:/cache/android-user" -e ANDROID_USER_HOME=/cache/android-user \
      "$ANDROID_IMAGE" ./gradlew --no-daemon --offline -q :app:compileReleaseKotlin'
  # copy classes and brouter.jar to ~/.cache/openauto-android/p9/nogo/, then on the host:
  javac -Xlint:all -Werror -d testout -cp "classes:$stdlib:brouter.jar" tools/jni-host-test/NogoRoutingTest.java
  java -cp "testout:classes:$stdlib:brouter.jar" NogoRoutingTest ~/.cache/openauto-android/rd5
  ```

- Its output lines, from the passing run:

  ```
  nogo-routing-test: closure base_s=1461 round_s=1541 round_m=30600 nearest_point_m=21
  nogo-routing-test: jam I-35 north of US-183 class=1 points=11 way_round_s=80 stays_at_s=24 leaves_at_s=240
  nogo-routing-test: jam US-290 Bastrop to Giddings class=2 points=11 way_round_s=441 stays_at_s=132 leaves_at_s=1323
  nogo-routing-test: jam S Congress Ave, Austin class=3 points=8 way_round_s=61 stays_at_s=18 leaves_at_s=183
  nogo-routing-test: jam US-75 north of downtown Dallas class=1 points=11 way_round_s=259 stays_at_s=78 leaves_at_s=777
  nogo-routing-test: stuck out_m=19442 to_m=11154 past_s=1553 base_s=1461
  nogo-routing-test: overpass crossings_m=2319,3309 point_on_crossing_diverts=false stretches_through_it_charging_the_street=16/150
  nogo-routing-test: beside northbound_frontage_road_nearest_m=20.7 southbound_main_lanes_nearest_m=9.1
  nogo-routing-test: timing_ms 0=282 50=437 200=882 400=1506
  nogo-routing-test: lubbock timed_at_kmh=105 plain_s=26211 round_s=26676
  jni-host-test: ok 68 nogo routing checks; real routes over W100_N30 and Austin to Lubbock
  ```

- **`flock ~/.cache/openauto-android/p9-build.lock tools/android-build.sh`: pass** on
  28 September 2026 with the other P9 agents' work in the tree: debug and release APKs,
  lint, `verify-apk` on all three, and every host test. The container does not mount the
  tiles, so there it prints `ok 23 nogo routing checks; real routes skipped`. The 68-check
  run above is the host one.

### 7.1 The other routing tests

These were rerun on the host with the tiles against the same classes. Every test file in
`tools/jni-host-test/` was compiled together with `-Xlint:all -Werror`. All pass:

```
jni-host-test: ok 105 routing checks            (Austin to Denver compute_ms=64061)
jni-host-test: ok 56 route preference checks; real routes over W100_N30 and Austin to Lubbock
jni-host-test: ok 70 route preview checks; real alternatives over W100_N30
jni-host-test: ok 1541 guidance checks
```

## 8. Not verified, and open

- **Nothing has been seen in the lab, on the Pixel or in the truck.** `LAB_TRAFFIC`
  check 4 (re-route onto a closure) needs the wiring slice.
- **Real HERE geometry against OSM.** Every point here was laid on BRouter's own track. How
  far HERE's shapes sit from OSM's decides both the 9.1 m case (§5) and whether an 8 m
  circle reaches the road at all. A point more than 8 m off the OSM line charges nothing.
- **The link factor is a median over thirteen stretches, with a wide spread** (§3.4). The
  offer's own test ("saves at least 3 minutes", in time, from HERE's delays) is what
  protects the driver from a poor candidate. The weights only decide which candidates
  BRouter finds.
- **Undivided roads**: a one-direction jam or closure charges or blocks both directions
  (§2). Not fixable with BRouter circles.
- **A via point inside a closure** is dropped by BRouter without a word (§2). Only the
  preview's alternatives use via points, and they are computed without traffic today.
- **Phone timings for many points** (§6).

## 9. Deviations from the plan, and why

- **The plan's sum rule is not what BRouter does** (§2, §3.4). The weights keep the plan's
  form (each point is delay × rate ÷ (points × 16 m)), but the rate for classes 1–2
  carries the measured ×1.6.
- **The rate is one price per top speed, not a per-class table.** The class averages (§3.1)
  differ only by energy for distance, which a queue does not add. What BRouter trades
  between two routes is pw ÷ cost0 (§3.2).
- **Checks and retries the plan did not have**: the closure-crossed check, and the
  "open the closures" fallback (§4). The plan asked only that the stuck vehicle still get
  a route. The fallback does that without a distance threshold.
- **`Route` gained `nogoCount` and `closuresOpened`**, and `RoutingException` gained
  `stopped`. All are additive and defaulted.

## 10. For the slices that wire this in

- Pass the points on every request that should see traffic, including re-routes and each
  of `LiveAlternatives`' searches: `request.withNogos(nogos)` or the constructor's last
  argument. Build them with
  `TrafficNogos.of(flow, incidents, route, { fc -> TrafficCost.costPerSecond(fc, options.topSpeedKmh) })`,
  using the options that go into the same request.
- `Route.trafficShaped` means HERE's data was in the search: "Traffic © HERE" is owed
  while the route is shown (`TrafficDisplay.routeShaped`).
- `Route.closuresOpened` means the route knowingly passes a closure. A "closure ahead,
  re-route" rule must not fire on it, or it will re-route in a loop.
- A search with points may run BRouter up to three times (§1). `started` is called for
  each, so `RoutePlanner` can still stop whichever is running.
