# P9.4, P9.5 and P9.6 at run time — the traffic loop: implementation record — 28 September 2026

The part of the [P9 plan](p9-plan.md) that joins the others up: the loop that fetches
HERE's traffic while a route is followed (§4), lays it on the route and adds its delay to
the time to the destination (§6, P9.4), hands its closures and jams to every route search
as no-go points and re-routes at once round a closure ahead (§7, P9.5), and offers a
faster way round a jam on the divided turn card (§7, P9.6). It calls what the earlier
slices built and host-tested — `TrafficClient` (P9.1), `TrafficArea`, `TrafficMatch` and
`TrafficNogos` (P9.2), `Router`'s points and `TrafficCost` (P9.5), and the dashboard's
`pipeline.traffic`/`offerListener` (P9.3, P9.6) — and redesigns none of them.

Status: **implemented and host-tested, including on BRouter's real route up I-35E in
Dallas** with a hand-written closure and queue laid on it. **Nothing has run in the
emulator lab, on the Pixel or in the truck.** No request has been made to HERE: there was
no network, no key and no phone. The lab agent's `LAB_TRAFFIC` run is next (§5).

---

## 1. What was built

### 1.1 The loop — `TrafficFeed.kt` (plan §4)

`TrafficFeed` is main-thread state, like `Guide` and `LiveAlternatives`. Everything it
needs from the app comes through `TrafficHost`: the settings, `TrafficClient.fetch`, the
month's request count, a monotonic clock, three threads (network, compute, main), the
routing top speed, and the four things it asks the app to do (re-route, look for ways
round, take an offer, publish guidance again). The host test drives it with fakes; the app
with the real ones (`Traffic`, §1.6). It holds no Android type.

**When it fetches.**

- **Only while a route is followed** (plan §4, decision 5): a route, and a fix that placed
  the vehicle on it or joining it. A route with no fix yet (a route restored on the couch,
  one set on the phone before the drive) fetches nothing. Neither does one arrived at.
- **Only with traffic on and a key.** The loop checks `TrafficSettings.usable` before it
  does anything, and `TrafficClient` checks its own gate again before any socket. With
  either missing, the injected HTTP layer is never called (host-tested end to end through
  the loop, §2.1).
- **The first fetch** is at the first such fix. Then every `refreshMinutes`.
- **The box** is `TrafficArea.boxes(route, alongM, lookAheadKm)`, from where the vehicle is
  at that fix. One flow and one incidents request per box: two requests a refresh in every
  case the plan expects.
- **After the followed route changes** (a re-route, a new destination, an alternative or an
  offer taken, all alike): the response held is laid on the new route at once, without a
  fetch. `TrafficArea.covers` then asks whether the last fetch's boxes still hold the new
  route's next half of the look-ahead (`COVER_SHARE` = 0.5, 15 km at the default). If they
  do, nothing is fetched until the next refresh. If they do not, one fetch is made, but no
  sooner than `REFETCH_GAP_MS` = 60 s after the last fetch began. A burst of re-routes out
  of the box is one fetch, for whichever route is followed when it is made.

**What is kept** (plan §1, §4).

- One response set: a `TrafficSnapshot` per box, flow and incidents concatenated. Each new
  response replaces it whole, and the points and traffic made from the old one are
  cleared the moment it lands, not merged or carried over.
- A snapshot is only ever for the box it was fetched for. Laying it on a new route inside
  that box is using it for its own box. Outside the box the new route has no data, and a
  fetch for the new box follows as above.
- **Nothing is written anywhere.** HERE's data lives in the loop's fields and the
  `RouteTraffic`/`Nogo` values made from it, all in memory.

**A failed refresh.** The last good response is kept, but only until **twice the refresh
interval after it arrived** (`staleMs`: 20 minutes at the default 10). Then it is dropped:
old traffic is worse than none. This follows plan §4 ("used until the next replaces it")
with an upper bound. Retries:

| Result | Next attempt |
| --- | --- |
| No connection, HERE 5xx, an answer that could not be read, any non-HTTP failure | after `RETRY_MS` = 2 min (never later than the refresh) |
| HERE 4xx (a bad key, a refused box) | at the next refresh: a bad key costs one counted request a refresh, not one every 2 minutes |
| `Skipped(LIMIT)` | at the next refresh; the report says `traffic_cap_reached=true` |
| `Skipped(OFF)` or `Skipped(NO_KEY)` (the setting changed under the fetch) | everything dropped |

A failed flow request sends no incidents request (the client's rule), so a failure is one
counted request.

**Dropped at once**, with any answer still in flight made worthless (a generation number):

- the route ends: End route, Clear route, a new destination's route replacing it, or the
  hold after arriving (`Guide.clear` → `onRouteEnded`);
- traffic is turned off;
- the key is removed.

The settings are read at every fix and every 5 s tick (`Traffic.TICK_MS`) while a route is
followed, so turning the switch off drops everything within 5 s even with the vehicle
parked.

### 1.2 The time with traffic (P9.4, plan §6)

- **`delaySeconds`** is `RouteTraffic.delayAheadSeconds(alongM)` for the followed route at
  the last fix, rounded to the second. It is 0 before the first fix, after arriving, and
  whenever nothing is held.
- **`ArrivalTime.shown(brouterSeconds, pace, learnPace, delay)`** = the paced BRouter time
  ahead + the delay. `Guide` uses it everywhere the time to the destination is shown:
  - `Guide.remaining()`, and so Home's route line through `RouteClock`;
  - the dashboard's turn card and trip panel (`UpcomingTurn.destinationSeconds`);
  - the cluster's `destination_seconds` (`submitGuidance`).
  The time to the next maneuver is unchanged.
- **No double count.** `PaceTracker.onFix` is still given BRouter's own seconds, never the
  shown ones, and the delay is added after the pace factor, not scaled by it. Ahead of a
  jam, it is in the delay. Once the vehicle is in it, the delay ahead shrinks pro rata and
  the pace sees the slow driving. Past it, the delay is 0 and the pace alone carries it.
  Host-tested by driving through a queue (§2.1).
- **The display's delay is the same number.** `TrafficDisplay.delaySeconds` is
  `delaySeconds`, published whenever it changes, so the trip panel's "+N min traffic"
  labels exactly what `Guide` added.
- **With no fix to carry it** (a response landing while parked), the loop asks `Guide` to
  publish again (`Guide.trafficChanged`), so the cluster, the card and Home never lag the
  display.
- **Home's route line** ends "· +7 min traffic" after the arrival
  (`StatusText.routeSummary`'s new `trafficSeconds`, from `RouteFigures.trafficSeconds`,
  from `Guide.trafficDelay()`). It uses the trip panel's rounding: nearest minute, nothing
  under 30 s (`StatusText.trafficLabel`, the same rule as `Dashboard.trafficDelayLabel`).
  Only the live figures carry it; the route's totals do not.
- **Free-flowing runs add nothing** (orchestrator's change, §4): see §1.7.

### 1.3 The points in every search (P9.5, plan §7)

**`TrafficNogos.forRoute(flow, incidents, traffic, costPerSecond)`**, new, made on the
compute thread once per response and per followed route. It is `TrafficNogos.of` over the
whole response, ordered by the followed route, with the calibrated
`TrafficCost.costPerSecond(fc, topSpeedKmh)` in place of the old placeholder. On top of
that it adds points **on the route's own line**, because HERE's geometry is up to 20 m from
OSM's (docs/p9-2-results.md §3) and an 8 m circle on a line 10 m off the road does
nothing:

- **Closures matched to the route** (`CLOSED` runs) get hard points on the route's line,
  placed by the plan's rule (≤ 150 m apart, 30 m end margins, radius 8 m, one in the
  middle of a closure too short for that, slid clear of HERE roads crossing it). These are
  **in addition to** the points on HERE's own shape. A HERE hard point within 8 m of one
  is left out as a duplicate.
- **Jams matched to the route** (touching `SLOW`/`QUEUING` runs, taken as one) get weighted
  points on the route's line **in place of** HERE's own weighted points within 15 m
  (`TrafficMatch.NEAR_M`) of it, which are the same jam's.
  - The reason is P9.5's finding that BRouter charges one circle per link, the last it
    passed: a second set on the same road would add nothing but search time (~3 ms a
    point a search on the host).
  - Weight: the plan's delay × cost-per-second ÷ (points × 16 m). The delay is the runs'
    own (HERE's time less BRouter's), and the class is that of the HERE stretch matched
    there (for `TrafficCost.linkFactor`).
- **Order and cap.** The route's own closures first when the 300-point cap bites, then its
  jams, then `of`'s. In the list, HERE's come before the route's own of the same kind, so
  on a link both reach, the route's own is the circle BRouter keeps.

**Where the points go.**

- **`Navigation.route`** wraps every request in `Traffic.withNogos`: new routes (a new
  destination while traffic is held gets them from the start), re-routes after leaving the
  route, re-routes round a closure, and a route through a chosen alternative's or an
  offer's via point.
- **The live alternatives' search**: `PreviewRequest` gained `nogos` (§1.8), and
  `alternative(k)` and `through(lat, lon)` pass them, so every BRouter search the finder
  makes carries them.
- **Priced at the request's own top speed.** The points are made at the routing settings'
  top speed and rescaled by `secondPrice(asked) ÷ secondPrice(made)` if a request asks
  another (`TrafficFeed.nogos(options)`).
- Nothing is added with traffic off, keyless, or nothing held: the request goes exactly
  as before P9.
- **`TrafficDisplay.routeShaped`** is the followed route's `trafficShaped`, so "Traffic ©
  HERE" is owed from the moment a route made with the points is shown. It stays while that
  route is shown even if traffic is turned off, since HERE's data still shaped it.

### 1.4 A closure ahead re-routes at once (plan §7)

When the followed route's `RouteTraffic.closureAhead(alongM)` is a closed run, the loop
asks `Guide.rerouteForClosure`, which re-routes exactly as leaving the route does:

- it sets `REROUTING`;
- it asks `Navigation.route(reroute = true, bearing, closure = true)`, with the points, from
  the vehicle;
- it counts `guidance_closure_reroutes`.

It is asked only when all of these hold:

- the vehicle is on the route or joining it;
- the route is not `closuresOpened` (BRouter found it only by opening closures, so
  re-routing would find it again, in a loop);
- that closure has not already been re-routed round this trip. A closure is identified by
  where it is on the route, within 150 m (`SAME_CLOSURE_M`), since the matcher's run ends
  move with the route.

A re-route refused because one is already in flight is asked again at the next fix. It is
recorded only once started.

**If that re-route fails** (BRouter opens closures rather than fail, so it would take a
timeout or an error), the vehicle keeps the route it had. `Routing` leaves it on the map,
and `Guide` goes back from `REROUTING` to guiding along it, rather than the route vanishing
as a failed destination does.

### 1.5 The offer (P9.6, plan §7)

**The searches.**

- **After a traffic update** (a new response matched), when the followed route has at least
  **3 minutes of delay ahead** (`worthLooking`), the loop asks `LiveAlternatives.lookNow`
  for one alternatives search. It runs at once, with the new points, whether or not Show
  alternatives is on. The next timed look is then pushed an interval off. That is the one
  extra search per traffic update, and none is spent when the traffic ahead could not save
  3 minutes.
- **Every look** (timed or traffic's) runs `AlternativeSearch.find`: P6.6's
  `AlternativeFinder` with the points. When the traffic ahead is worth it, it adds one
  search first: **BRouter's own best route over the same trip with the points**. That is
  the way round BRouter itself would take, which the finder's corridors, built to differ
  from the route on show, may not contain. That route is given a via point in the middle of
  its own stretch (`LiveAlternative.throughOwn`), so that Take keeps to it.

**The decision.** Each alternative found is laid on the traffic held (`TrafficFeed.assess`,
on the compute thread). An alternative is offered when all of these hold:

- it saves **≥ 180 s** against the followed route from the same point, each with its own
  traffic delay: (ahead's BRouter time + ahead's delay) − (its BRouter time + its delay);
- traffic is part of why: the followed route's delay ahead is more than its own. A route
  merely BRouter-faster is never called "faster" for traffic;
- it leaves the followed route more than 30 m ahead of the vehicle;
- it has no closure of its own;
- the driver has not kept away from it this trip (Keep, below).

The best is offered as a `TrafficOffer`. Around it:

- **The same way round** (splitting within 200 m and a `RoutePreview.duplicate` either way)
  keeps its offer's id, so the card is not re-fitted when the saving is updated.
- **A different one** replaces it: the old counts as withdrawn, the new as made.
- **The bubbles** count both routes' traffic too: each "+N min" is its BRouter difference
  plus its delay less the followed route's. The offered alternative is left out of them.

**The reason** comes from what lies past the split on the followed route:

| Ahead of the split | Reason |
| --- | --- |
| a closed run | "Closed: *road*" |
| heaviest jam queuing | "Queue on *road*" |
| heaviest jam slow | "Traffic on *road*" |
| the map names no road there | "Traffic ahead" |

The road name is the followed route's own `roadAt` (the map's names, which `StreetNames`
put on the route), never HERE's `description`. The host test lays a queue whose HERE
description is "Oak Lawn Ave/Exit 430" on a route the map calls "I-35E", and the reason
reads "Queue on I-35E".

**Take, Keep and withdrawal.**

- **Take** (`pipeline.offerListener` → `Traffic.answer(id, true)`) follows the offered route
  as a tapped bubble is followed (`LiveAlternatives.take` → `follow`): with a via point,
  `Navigation.chose` and a route from the vehicle through it, with the points; without
  one, `Routing.adopt`.
- **Keep** records where it split, the route and its saving. The same way round is not
  offered again this trip unless it saves **180 s more** than when kept. A new
  destination (`Navigation.set` → `newTrip`) or the route ending clears it.
- **Withdrawn** (the display's offer set to null, which returns the map to following):
  - when the vehicle is 30 m past the split;
  - when it arrives;
  - when the followed route changes;
  - when a later update leaves less than 3 minutes of delay ahead (at once, with no
    search);
  - when a later search finds nothing that still qualifies.
- An answer to an offer no longer up does nothing. Nothing re-routes by itself for a jam
  (decision 3).

### 1.6 Wiring

- **`Traffic.kt`** (new): the app's one `TrafficFeed`, installed by `SourceApplication` in
  the main process only (the preview's process gets no-ops).
  - `TrafficPrefs.client`, `TrafficPrefs.load` and the month's count from `TrafficPrefs`.
  - Two single-thread executors at the routing thread's nice 4: `traffic-fetch`, which
    waits on HERE, and `traffic-match`, which lays responses on routes. Kept apart so a
    slow fetch never delays matching a re-route. Neither is the routing thread.
  - A 5 s tick while a route is followed.
  - The static calls `Guide`, `Navigation`, `LiveAlternatives` and `ProjectionService` make.
- **`Guide`**:
  - `Traffic.onRoute` in `onRoute`, before the fix in hand is placed on the new route.
  - `Traffic.onPosition` for every fix it places.
  - `Traffic.onRouteEnded` in `clear`.
  - `ArrivalTime.shown` for every destination time.
  - `rerouteForClosure`, `trafficChanged`, `trafficDelay`.
- **`ProjectionService.startProjection`**, after `pipeline.start()`:
  - `Traffic.listener = { pipeline.traffic(it) }`;
  - `pipeline.traffic(Traffic.display())`, so the first frame has what is held;
  - `pipeline.offerListener = { id, take -> Traffic.answer(id, take) }`.

  `stopProjection` clears both. The loop runs without a projection, as `Guide` does. Only
  the display needs one.
- **`Routing`**:
  - the `closure` origin, and keeping the route on a failed closure re-route;
  - the event log's "N traffic points" / "through a closure (no way round it)";
  - `routing_traffic_routes`, `routing_closures_opened`, `routing_traffic_points` and
    `routing_through_closure` in the report.

### 1.7 `TrafficMatch`: free flow is no delay

`TrafficMatch` now gives delay to `SLOW` and `QUEUING` runs only. A `FREE` run's
`delaySeconds` is 0 even where HERE's free-flow speed is below BRouter's model. The P9.2
record measured 17 and 36 s of such "delay" on empty Dallas roads, which would have shown
"+1 min traffic" with no traffic at all. `CLOSED` runs were already 0. `delayAheadSeconds`,
the offer's saving and the route-line jam weights all follow.

### 1.8 Report fields (plan §8)

A new `[traffic]` section after `[routing]` (`SessionReport`). It holds counts, minutes,
seconds and booleans only, never a box, a place, a road, a reason, HERE's error text or the
key (host-tested against all of them, and against the event log's lines):

```
traffic_enabled traffic_key traffic_refresh_min traffic_look_ahead_km traffic_limit
traffic_following traffic_fetches traffic_boxes traffic_last_boxes traffic_requests_session
  traffic_requests_month traffic_cap_reached
traffic_held traffic_last_fetch_age_s traffic_response_age_s traffic_failures traffic_skipped
  traffic_stale_drops traffic_last_error
traffic_matched_m traffic_runs traffic_jam_runs traffic_closed_runs traffic_delay_s
  traffic_delay_min traffic_match_ms
traffic_nogos traffic_nogos_hard traffic_nogos_on_route traffic_requests_with_nogos
  traffic_route_shaped traffic_route_closures_opened traffic_closure_reroutes
traffic_assessed traffic_offers_made traffic_offers_taken traffic_offers_kept
  traffic_offers_withdrawn traffic_offer_up traffic_offer_saving_min traffic_attributed
```

- `traffic_last_error` is a class, never HERE's text: `none`, `network`, `parse`, `count`,
  `http_<status>`, `limit`, `off`, `no_key`, `match` or `other`.
- `traffic_requests_session` counts since the app process started, like the other
  sections' counts; `traffic_requests_month` is the card's count.
- The other sections gain `guidance_closure_reroutes` (`[routing]`, from `Guide`),
  `live_alternatives_traffic_looks`, and the `Routing` fields in §1.6.
- The event log gains, among others, "traffic fetched: 1 box, N stretches, M incidents",
  "traffic fetch failed: http_401", "closure ahead on the route; re-routing round it" and
  "traffic offer: 7 min faster".

### 1.9 Edits outside this work's own files

Each is the smallest that wires the loop in:

| File | Change |
| --- | --- |
| `RoutePreview.kt` | `PreviewRequest` gains `nogos: List<Nogo> = emptyList()` (last, `@JvmOverloads`), passed by `alternative()` and `through()`. Not written to the preview's file: the preview's own alternatives stay without traffic. |
| `ConnectionActivity.kt` | One argument: `figures?.trafficSeconds ?: 0` to `StatusText.routeSummary`, for Home's "+N min traffic". |
| `tools/jni-host-test/TrafficMatchTest.java` | The placeholder check replaced by one that the default weights are `TrafficCost`'s, and a new check that a free run slower than BRouter has no delay (345 checks with the tile). |

`TrafficMatch.kt` and `TrafficNogos.kt` were handed to this work: the free-flow rule (§1.7),
and `forRoute` with the calibrated default (§1.3).

---

## 2. Verification

### 2.1 Host test: `TrafficFeedTest`

The loop is run whole:

- an injected clock;
- three queues the test drains in place of the threads;
- the real `TrafficClient` over a fake HTTP layer that serves hand-written flow and
  incidents JSON in HERE's shape, laid along the route (never a captured response);
- a `MemoryTrafficLedger`.

It checks:

- **Nothing sent** (plan §9, first item, end to end):
  - traffic off, on with an empty key, or on with a blank one, over an hour of fixes and
    ticks: the HTTP layer is never called, nothing is counted, requests carry no points,
    there is no delay and no attribution;
  - on with a key and no route, or a route with no fix on it, or a route arrived at:
    nothing.
- **Cadence:**
  - the first fix fetches flow and incidents for the box of the next 30 km;
  - nothing more for 10 minutes, then the refresh, for the box where the vehicle now is;
  - no connection: one request, not again within a minute, again after 2;
  - a 401: one request, not again until the refresh, reported `http_401`;
  - at the limit: nothing sent, `traffic_cap_reached=true`.
- **Re-routes:**
  - a new route inside the box fetches nothing and the held response is laid on the new
    route object itself;
  - one out of the box waits out the minute since the last fetch, then fetches once, for
    its own box;
  - a burst of two more re-routes within the minute is one fetch, for the route followed
    then.
- **Replaced, never kept:**
  - a response with a jam, then one with nothing: no delay, points or runs survive;
  - the next fetch is for the box of its moment;
  - failed refreshes keep the response 19 minutes and drop it at 20 (`traffic_stale_drops=1`);
  - off, keyless and the route ended each drop everything at once, with no fetch after;
  - an answer arriving after its route ended is thrown away.
- **The time:**
  - the display's traffic is the followed route object;
  - the delay is the queue's (3 km at 5 m/s against BRouter's 30);
  - a free-flowing stretch at 20 m/s adds nothing;
  - the display's delay is the one in the time, and only for the followed route;
  - guidance is asked to publish again when a response lands.
  - Then a drive through the queue at 5 m/s, fix by fix with a real `RouteFollower` and
    `PaceTracker`: at every fix the time is paced BRouter plus the delay. Before moving it
    is BRouter's time plus the queue's delay. Past the queue the delay is 0 and the pace
    (factor 1.5, learned from BRouter's estimate alone) carries it.
  - A response of free flow alone gives no delay and no attribution.
- **The points:**
  - a new route or re-route gets them, all else the same;
  - a closure HERE draws 12 m off the road is blocked on the route's own line (≥ 2 hard
    points within 1 m of it) as well as on HERE's shape;
  - a queue's points are on the route's line, with none of HERE's left 2 m off it;
  - hard points come last;
  - a 90 km/h request gets the same points at 90 km/h's price;
  - BRouter's alternatives and every via route of the search carry them;
  - a route made with them is credited to HERE;
  - with traffic off, none.
- **Closure ahead:**
  - one re-route, not one per fix, and none for the same closure on the new route;
  - none on a `closuresOpened` route;
  - none for a closure behind the vehicle;
  - a refused re-route is asked again at the next fix and counted once done.
- **The offer:**
  - a heavy update asks for exactly one search;
  - 170 s saved is no offer, 190 s is, with its route kept out of the bubbles and the
    bubble's delta counting both routes' traffic;
  - the reason is "Queue on I-35E" from the map, not HERE's "Oak Lawn Ave/Exit 430";
  - the split is where it leaves;
  - the same way round keeps its id;
  - Keep takes it down, and it is not offered again at +0 or +2.5 min, but is at +3 min,
    as a new offer;
  - withdrawn 10 m past the split's margin, not before;
  - one leaving behind the vehicle is never offered;
  - an answer to another id does nothing; Take follows it through the host and takes it
    down;
  - the queue clearing withdraws it at once with no search;
  - a merely BRouter-faster route with no traffic is not offered;
  - a way round with its own closure is not offered.
- **The report and the event log** carry every §1.8 field and none of: the key, "I-35E",
  "Oak Lawn", "Queue", "Faster", "Closed", "bbox" or the route's coordinates.
- **Home's route line:**
  - "+N min traffic" by the trip panel's rounding (0 and 29 s none, 30 and 89 s +1, 90 s
    +2, 420 s +7), after the arrival;
  - 20 s says nothing;
  - with none it is as before;
  - the live figures carry the delay and the totals do not.
- **With `ROUTING_SEGMENTS` (W100_N30.rd5)**, BRouter's real 15.6 km route from downtown
  Dallas up I-35E:
  - **A closure** laid on it 1 km into its first 3 km of freeway, HERE's shape 3 m off: the
    loop re-routed once. The re-route carried the points, and found a way round **without
    opening the closure** (`closuresOpened` false, `Router.closuresCrossed` empty, the new
    route more than 8 m from the closed road) in about 0.5 s on the host. The new route was
    not re-routed again, and was credited to HERE.
  - **A queue** laid on the same 3 km at 2 m/s (1,387 s of delay): one search was asked for.
    `AlternativeSearch` with the points found 3 ways round in about 4.6 s on the host. The
    offer saved **1,071 s** (18 min), with 26 s of the queue's delay on its own line. Its
    route has a via point.

Output of the passing run (TrafficFeedTest alone, compiled with the rest):

```
traffic-feed-test: real route distance_m=15591 jam_from_m=2000 closure_reroute_ms=524 delay_s=1387 nogos=20 look_ms=4623 found=3 saving_s=1071 offer_delay_s=26 offer_via=true
jni-host-test: ok 181 traffic feed checks; real routes over W100_N30 (Dallas, I-35E: a closure re-routed round, a queue offered a way round)
```

### 2.2 The other host tests

`TrafficMatchTest` passes with its two changes (§1.9). Every other test passes unchanged
(§2.3). `GuidanceTest`, `RoutePreviewTest`, `RoutingTest`, `RouteOptionsTest`,
`StatusTextTest` and `DashboardTrafficTest` needed no edits: every changed signature
(`RouteFigures`, `RouteFigures.choose`, `StatusText.routeSummary`, `PreviewRequest`,
`LiveAlternative.of`) keeps its old form through `@JvmOverloads`.

### 2.3 Full build

```
ROUTING_SEGMENTS=$HOME/.cache/openauto-android/rd5 flock ~/.cache/openauto-android/p9-build.lock tools/android-build.sh
```

**Exit 0** on 28 September 2026:

- the Gradle build of both APKs (Kotlin with warnings as errors);
- `lintDebug`;
- `verify-apk` on all three APKs;
- every host test, with the tiles mounted:

| Test | Checks |
| --- | --- |
| `TrafficFeedTest` | 181, real routes over W100_N30 |
| `TrafficMatchTest` | 345, real route |
| `NogoRoutingTest` | 68 |
| `DashboardTrafficTest` | 2,084 |
| `TrafficClientTest` | 146 |
| `GuidanceTest` | 1,543 |
| `RoutingTest` | 105 |
| `RouteOptionsTest` | 56 |
| `RoutePreviewTest` | 70, real alternatives |
| `StatusTextTest` | 190 |
| `DashboardTest` | 588 |
| `DashboardTripTest` | 2,364 |
| `DashboardFlexTest` | 1,386 |
| `AppSettingsTest` | 2,646 |
| the JNI contract | passed |
| the rest | as before |

`StreetNamesTest`'s real-map checks and `MapReadGuardTest`'s VTM pass were skipped as
always, for want of `MAP_FILE`.

The narrow loop used while building it, a scratch script outside the repository:
`~/.cache/openauto-android/p9/feed/quick.sh`. It runs `:app:compileReleaseKotlin`, then
`javac -encoding UTF-8 -Xlint:all -Werror` of every host test, then the chosen tests, with
the rd5 tiles mounted.

---

## 3. Not verified

- **Anything on a screen or a wire.** No emulator lab run, no Pixel, no truck:
  - the colours, the credit and "+N min traffic" have not been seen driven by the loop;
  - the divided card has not been seen raised by a real offer;
  - the cluster's `destination_seconds` with the delay has not been read off the lab
    receiver;
  - Home's line has not been seen.
- **`HttpURLConnection` on Android**, and the debug build's cleartext to the lab's stand-in
  (P9.1's open items).
- **Phone timings.** Matching a 1° cell took ~50 ms on the host (P9.2); the phone is
  slower. `traffic_match_ms` is in the report for the lab and the Pixel. The alternatives
  search with points took 4.6 s on the host for this 15 km route. On a phone, and on a long
  route with 300 points, it will be much more, but it runs on the routing thread through
  `Routing.explore`, which any route request stops.
- **`Guide`, `LiveAlternatives`, `Traffic`, `Routing` and `ProjectionService`** are Android
  classes, and the host test drives their pure halves (`TrafficFeed`, `ArrivalTime`,
  `AlternativeSearch`, `TrafficNogos.forRoute`) rather than them. What joins them has only
  been compiled.
- **Real HERE geometry against OSM** for the route-line points: every point here was laid
  on BRouter's own line or a few metres off it.

---

## 4. Deviations from the plan, and choices it left open

1. **Free flow is no delay** (orchestrator's change to plan §6's formula). Only slow and
   queuing runs count (§1.7). The plan's formula counts every matched stretch.
2. **A failed refresh keeps the last response for at most twice the refresh interval**
   from its arrival, then drops it. The plan says only "used until the next replaces it".
   Retries: 2 minutes for failures that may pass, the next refresh for HERE's 4xx (§1.1).
3. **After a re-route, "one more fetch" only when needed.** A new route still inside the
   last box (half the look-ahead covered) fetches nothing. One outside it fetches once,
   no sooner than 60 s after the last fetch began. The plan says "one more after a
   re-route"; the orchestrator asked for the debounce and the `covers` test.
4. **Points on the route's own line**, which the plan does not have (§1.3):
   - hard ones for closures matched to the route, as well as HERE's;
   - weighted ones for jams matched to it, **in place of** HERE's within 15 m, weighed
     from the run's own delay against BRouter's time.
5. **The first route of a trip has no points.** "New routes started while traffic is on
   include the nogos from the start" holds whenever a response is held. Nothing is
   fetched before a route is followed (decision 5), so the very first route cannot have
   them. A closure found by the first fetch re-routes at once; a jam is offered.
6. **One extra BRouter search on heavy-traffic looks**: BRouter's own best route with the
   points, with a via point in the middle of its own stretch so Take keeps to it (§1.5).
   Only when the delay ahead is at least 3 minutes. The traffic-triggered look is one per
   traffic update, as asked; the timed looks (every 5 min) also carry the points.
7. **The bubbles' "+N min" include traffic** on both routes when a response is held, so a
   bubble and the offer never disagree. Before, they were BRouter's times alone.
8. **The offer's reason** has a fourth wording, "Traffic on *road*", for a slow (not
   queuing) jam. "Queue" would overstate it. Names come from the map, never HERE's
   `description`.
9. **"Traffic part of why."** An alternative is offered only when the followed route's
   delay ahead exceeds its own, besides saving 3 minutes. A route merely BRouter-faster
   (after a chosen via, say) is never called "faster" for traffic.
10. **Traffic look regardless of Show alternatives.** The offer is traffic's, not the
    bubbles'. With Show alternatives off, the search runs for the offer and no bubble is
    drawn.
11. **A failed closure re-route keeps the route.** Guide carries on along it, rather than
    the route disappearing as a failed new destination does.

---

## 5. For the lab: `LAB_TRAFFIC`

**Seeding.** P9.1's debug-only lab extras (docs/p9-1-results.md §1.6), on the connection
screen's intent, before the drive:

```
--ez lab_traffic true --es lab_traffic_url http://10.0.2.2:<port> --es lab_traffic_key lab
--ei lab_traffic_refresh 5 --ez lab_traffic_reset true
```

- The stand-in must serve `GET /v7/flow?in=bbox:W,S,E,N&locationReferencing=shape&functionalClasses=1,2,3&apiKey=lab`
  and `GET /v7/incidents?in=bbox:…&locationReferencing=shape&apiKey=lab`, in HERE's shape
  (`TrafficFeedTest.flowJson`/`incidentsJson` show the minimum the parser needs).
- The first fetch happens at the **first vehicle fix placed on the route**. The next comes
  after `lab_traffic_refresh` minutes (5 at the least). A re-route out of the box fetches
  after at most 60 s. To change the stand-in's answer mid-run without waiting, the lab can
  toggle `lab_traffic false` then `true` (the next 5 s tick fetches again), or re-route out
  of the box.
- An offer needs:
  - at least 3 minutes of delay ahead on the lab route;
  - a real way round in the lab tile that saves 3 minutes against it;
  - the route at least 3 km from its end (`LiveAlternatives.MIN_AHEAD_M`);
  - the search's time: seconds in a debug build, and debug builds are ~30× slower at
    routing (memory: "Debug builds are slow").

What to check, with the report fields that say it:

1. **Colour** where the jam is: `traffic_runs`, `traffic_jam_runs`, `map_traffic_pieces`,
   `map_traffic_stale=0` (the display's traffic is `Routing.line` itself).
2. **The arrival grows by the jam's delay ± 1 min**:
   - `traffic_delay_s` against the stand-in's jam (its length ÷ speed less BRouter's time
     for it);
   - the trip panel's "+N min traffic" and time remaining;
   - the lab receiver's ch11 `destination_seconds`, which must equal the dashboard's time
     remaining, delay included;
   - Home's route line on the phone.
3. **The offer:**
   - `traffic_offers_made`, `dashboard_offers_shown`, `live_alternatives_traffic_looks`;
   - the divided card and both routes framed (`dashboard_offer_fits`);
   - **Take** → a new route through the offer's via point (`traffic_offers_taken`, a
     "route requested from …" note, `routing_via=true`);
   - **Keep** → the map back to following (`dashboard_offer_refollows`,
     `traffic_offers_kept`), and the same way round not offered at the next refresh unless
     3 minutes better.
4. **Closure moved onto the route** → a re-route follows:
   - `traffic_closure_reroutes=1`, `guidance_closure_reroutes=1`, `routing_origin=closure`;
   - the new route carries `routing_traffic_points` > 0 and `routing_through_closure=false`;
   - "closure ahead on the route; re-routing round it" in the event log;
   - exactly one re-route for that closure.
5. **Setting off → no connection at all**: the stand-in's log shows nothing,
   `traffic_requests_session=0`, `traffic_fetches=0`. Also with it turned off mid-drive:
   `traffic_held=false` within 5 s and no request after.

Also worth reading: `traffic_match_ms`, `live_alternatives_last_ms` with points,
`traffic_last_error` and `traffic_stale_drops`.

---

## 6. Open

- **The lab's stacked render** of the divided card still has no switch: the dashboard
  record (§5) asked for a debug-only lab extra setting `DashboardLayout.defaultOfferLayout`.
  It needs a lab extra read into the attempt's settings (the connection screen's lab
  intent and `SessionSettings`), which this work did not own.
- **The request count per session** is since the process started, not per attempt, as the
  other sections' counts are.
- **The 150 m "same closure" and 200 m "same way round" tolerances**, the 60 s debounce,
  the 2-minute retry and the 20-minute staleness are first guesses for the lab and the
  drive, named constants in `TrafficFeed`.
- **`jni-host-test.sh`'s header** needs a bullet for `TrafficFeedTest` (the orchestrator's
  file).
