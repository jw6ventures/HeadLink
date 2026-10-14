# P11.5a results — search ahead on the route — 3 October 2026

This records P11.5a of the [P11 plan](p11-plan.md) (§6a). James asked for it in these words: "It'd
be nice to hit a button and have it say, there's a gas station along your route in X miles, it's
an X minute detour."

While a route is followed, a press that lights **Gas**, **Food** or **Shop** now does three things:

- it searches the corridor of the route ahead;
- it routes the detour to the nearest few places with BRouter;
- it says the best one, lists up to three on the flex tile with **Add stop**, and draws the
  corridor's places on the map with the best one larger.

**Add stop** makes the place the route's via point and re-routes to the same destination. A via
point is now let go once the vehicle reaches it, as well as once it is past it.

Status: **built, host-tested and lab-verified in the emulator** at 800x480 and at Ford's 726x480,
against the real Austin OBF file and `W100_N30.rd5`, with a route north on I-35 to Round Rock.

- In the lab the receiver pressed Gas under the turn card. The card came up with three stops
  and their detours, the announcement was spoken by the silent stand-in engine, Add stop
  re-routed through the station, and the via point was let go after the drive passed it.
- **Nothing has run on the Pixel or the truck.**
- All timings are from a debug build in the emulator.
- Nothing is committed. `spec.md` and `plan.md` are untouched.

---

## 1. What was built

### 1.1 App (`app/src/main/kotlin/org/openautoandroid/source/`)

**`SearchAhead.kt`** (new, pure JVM; the host test's way in):

- `AheadCorridor(route, fromAlongM, lengthM)`: the route ahead of the vehicle, to the look-ahead
  or to the destination if that is nearer. It uses `RouteFollower`'s flat metres, so it agrees
  with guidance.
  - `point`, `secondsAt` (BRouter's `pointSeconds`, interpolated) and `bearingAt`.
  - `pieces(pieceM, padM)`: the query boxes.
  - `project(lat, lon)`: metres ahead, metres off the line, and the side, from the sign of the
    cross product of the route's direction and the place's offset.
- `AheadSearch`:
  - `hits`: queries each 3 km piece's box, padded by the corridor width, at most 200 places a
    piece. Places more than the width off the line or less than 300 m ahead are dropped. One
    is kept per place (by id, or by name and kind within 75 m), nearest first.
  - `leg`: A and B, 2 km either side of the projection, clamped to the vehicle and the
    destination, with the route's own seconds between them and its direction at A.
  - `detours`: A → place, heading the route's way, then place → B. A place whose routing
    fails is dropped and counted. A cancel is honoured between routes.
  - `detourSeconds`: (there + on) − the route's own A→B, never below 0.
  - `best`, `cardStops`, `minutes`, `detail`, `spoken` and `spokenName`.
- `ViaRelease.passed`: within 50 m of the via point, or on the route more than 50 m past it.
- `AheadCounts`: the report line.

**`AheadSearcher.kt`** (new, Android, one per dashboard projection) runs the search:

- The corridor query runs on the markers' own `aap-poi` thread.
- The detours run through `Routing.explore` on the routing thread. Like the live alternatives,
  that work only runs while no route request is waiting, and any request stops it.
- The result comes back to the main thread. It is dropped if `Routing.token` changed, the button
  went off or a newer press replaced it.
- Turning the button off stops the search (the running BRouter engine is terminated) and takes
  away its markers and card.

Changes to existing files:

- **`Navigation.kt`**: `addStop(context, lat, lon)`. It sets the via point, notes whether one was
  replaced, and routes again from the vehicle with its heading.
- **`Guide.kt`**:
  - `aheadPosition()`: the follower and the metres along it, on the route or joining it (0
    then), with no re-route in flight.
  - The via point is released through `ViaRelease`, which adds the reach rule.
- **`Speaker.kt`**: `announce(prompt)`. It takes the turn prompt's path (the voice toggle, no
  speech in a call, the gate) under stage `poi_ahead`. It **waits** up to 10 s for a turn prompt
  being said to finish instead of cutting it off.
- **`PoiMarkers.kt`**: `PoiCardContent.TAG_AHEAD` and `REPLACE_STOP`.
- **`ProjectionService.kt`**:
  - It makes the searcher with the session's speed unit.
  - It hands it every button press. **Add stop** (tag `ahead`) goes to `addStop`.
  - It stops the searcher with the projection and adds its report line.

### 1.2 Tools

- **`tools/jni-host-test/SearchAheadTest.java`** (new). It is wired into `tools/jni-host-test.sh`
  with one run line (`ROUTING_SEGMENTS`, `OBF_DIR`) and one header bullet.
  `tools/android-build.sh` already mounts both directories read-only, so it needed no change.
- **`tools/lab-receiver/receiver.cpp`**: `--poi-ahead gas|food|shop` (track script), steps 520–523
  (§3).
- **`tools/emulator-lab.sh`**: `LAB_POI_AHEAD`, described in §3.

## 2. How it behaves

| Situation | What happens |
| --- | --- |
| Button lit, route followed (on it or joining) | Search ahead for that kind, from where the vehicle is |
| Button lit, no route, re-routing or arrived | As P11.5: markers only |
| Button turned off | Its search is stopped; its corridor markers and card go |
| Second press while one runs | The first is cancelled and counted |
| Route changes before the answer | The answer is dropped (`poi_ahead_cancelled`) |

**Settings.** The search reads Look ahead (50 mi), Corridor width (0.5 mi) and Candidates to
check (5) at each press. The detours use the Routing settings in force and live traffic's no-go
points (`Traffic.nogos`), as the live alternatives do.

**Best stop.** The smallest detour wins, and of equal detours the nearer. A stop **at least 1 km
nearer** whose detour is within 2 minutes of the smallest comes first instead (§5, first row).

**Spoken.**

| Case | What is said |
| --- | --- |
| A stop | "Gas along your route in 12 miles: Buc-ee's, about a 3-minute detour." |
| Food, no name | "Food along your route in 2 miles: a restaurant, about a 4-minute detour." |
| Shop, no name | "Shopping along your route in 2 miles: a store, …" |
| No stop | "No gas along your route in the next 50 miles." (or "… before your destination.") |

- Distances come from P8's `SpokenText`, in the header's unit system (miles or kilometres).
- Minutes are rounded, and at least 1.
- With no name, the brand is used, then "a gas station".

**Card.** It is labelled "gas ahead" and shows up to three stops: the best first, then the others
nearest first. Each row reads "0.2 mi ahead · 1 min detour · right" ("19 km ahead" in km/h). The
primary button reads **Add stop**, or **Replace stop** while the route already has a via point.

**No stop.** The answer is shown as well as said, so a driver with the voice off still gets it.
The card becomes a notice (`PoiCardContent.message`, `notice`): the label ("gas ahead"), one line
and **Close** alone in its usual place. A tap where the primary button would be does nothing. It
times out after 60 s as any card does.

| Case | Card line | Spoken |
| --- | --- | --- |
| Nothing in the corridor | "None in the next 50 mi" ("80 km"), or "None before your destination" | "No gas along your route in the next 50 miles." / "… before your destination." |
| Places found, every detour failed | "No detour could be worked out" | "Gas along your route, but no detour could be worked out." |

**Add stop.** `Navigation.addStop` routes from the vehicle to the same destination through the
place. The note says "stop added as the route's via point" or "…, replacing the route's via
point".

**Via release.** Before this, the via point was let go only once the vehicle was on the route
more than 50 m past it. That missed the gas-stop case: a vehicle leaving a station by another
road is off the route, and the re-route that follows kept the via point and sent it back.
Reaching within 50 m now releases it too.

**Report** (counts only): `poi_ahead_presses`, `_searches`, `_cancelled`, `_queries`,
`_query_failures`, `_candidates`, `_detours_routed`, `_detours_failed`, `_found`, `_none`,
`_announced`, `_ms_p50` / `_p95` / `_max` (press to announcement), `_corridor_ms_p50`,
`_add_stops` and `_via_replaced`. A notice counts in `poi_cards` like any card.

- The speech line gains `voice_prompts_spoken_poi_ahead`.
- The event log says "search ahead for gas: 63 in the corridor, 5 detours routed, 0 failed, best
  1 min detour; announced 1738 ms after the press".
- **No name, place or coordinate** appears in any of them.

## 3. Lab: `LAB_POI_AHEAD`

```
LAB_REGION=austin LAB_ROUTING=$OPENAUTO_CACHE/rd5 LAB_POI_AHEAD=gas tools/emulator-lab.sh track
```

It needs the track script, `LAB_REGION=austin`, `LAB_ROUTING` and the lab player, since the
stand-in speech engine and the Bluetooth override are turned on.

1. Once projecting, Round Rock (30.5086,−97.6788, label "Aheadmarker Rock") is shared as an
   OsmAnd text share, from the vehicle parked downtown by I-35.
2. When the route is computed, the script waits 5 s and writes `/out/poi-ahead-go`.
3. The receiver, parked, presses the button where `DashboardLayout` puts it during a route: a
   margin under the turn card. It waits for the flex tile to change ≥ 10% (the card) and renders
   `renders/poi-ahead-<palette>.png`.
4. The receiver presses Add stop (`poiCardButton(0)`), then drives as the track script does.
5. The phone side waits for the search's note, for "stop added", and for a second route.
6. The app report must show at least 1 for each of: press, search, detour routed, stop found,
   announcement, `poi_cards` and Add stop.
7. With an engine, `voice_prompts_spoken_poi_ahead` must be ≥ 1.
8. No `Aheadmarker`, destination coordinate, `.obf`, `lab-obf` or `Us_texas` may appear in the
   report.

## 4. Results

### 4.1 Host (`SearchAheadTest`, 170 checks with real data)

`DashboardPoiTest` gained the notice at 1080p, 726x480 and 800x480, with the map on either side.
It checks the label and line, that there are no rows, that a printed card holds no text, that a
tap on the primary's place does nothing, that Close sits inside the tile and closes the card, and
the timeout. It now has 1011 checks, up from 903. `DashboardFlexTest` is unchanged at 1386. The
full `tools/android-build.sh` run passed (`p11-5a-build4.log`, exit 0).

The synthetic checks cover:

- projection: ahead, behind (0 ahead), 100 m off, left and right on north-bound and east-bound
  roads;
- the corridor filter: duplicate ids, one station mapped twice, another name across the road
  kept, behind, too close, off the corridor, past the end, the wrong kind, unnamed places;
- four 3 km pieces over 10 km, and one piece to a destination 1.9 km off;
- A and B, with their clamping at the vehicle and the destination;
- the detour arithmetic, and the two requests per place, including the bearing on the first, a
  failure dropped and a cancel;
- the best-stop rule;
- every sentence in miles and kilometres, and the notice's line for nothing found and for every
  detour failing;
- via release;
- that a report field is only a count or a time.

**The real search** (`tools/android-build.sh` with `ROUTING_SEGMENTS` and `OBF_DIR`, exit 0,
`p11-5a-build3.log`) routes downtown Austin → Round Rock: 29.7 km in 0.53 s on the desktop JVM.

| | Result |
| --- | --- |
| Gas in the 0.5 mi corridor | 63, from 10 queries: 43 ms cold, 10 ms warm |
| Food in the same corridor | 302, in 54 ms |
| 5 detours | 5 routed, 0 failed, in 570 ms |
| Detours (ahead, off, side) | 0.3 km 99 m right **107 s**; 0.3 km 23 m right **52 s**; 0.6 km 92 m left **116 s**; 2.2 km 710 m right **354 s**; 2.7 km 548 m right **275 s** |
| Best | 52 s ("1 min"), 0.3 km ahead |

- The frontage-road stations are 1–2 minutes; those 550–710 m off are 4.5–6 minutes. Nothing is
  near 20.
- The route's own A→B time matched BRouter's own A→B route to within 3 s for four of the five,
  and 26 s for the last. The first short route starts from rest, which costs a few seconds.

### 4.2 Lab

| Run | Display | Result | Press → card (receiver) | Press → announcement (app) | Corridor | Then |
| --- | --- | --- | --- | --- | --- | --- |
| `20261003T233022Z` | 800x480 | **pass**, 13 receiver checks | 2.2 s | **1738 ms** | 272 ms | Add stop pressed; the route through the stop computed in 1.4 s; the synthetic drive then left the route (re-routes kept the stop) and "passed the chosen route's via point" about 26 s after Add stop |
| `20261003T233139Z` | 726x480 (74x0 margins) | **pass**, 13 receiver checks | 2.1 s | **1698 ms** | 255 ms | same |

- Each run: 63 in the corridor, 5 detours routed, 0 failed, `voice_prompts_spoken_poi_ahead=1`,
  `poi_ahead_add_stops=1`, and no marker or file name in the report.
- The card listed Shell (0.2 mi, 1 min, right), Chevron (0.2 mi, 2 min, right) and Valero
  (0.4 mi, 2 min, left), the best marker larger: `docs/images/p11/p11-5a-ahead-card-night-726x480.png`
  (Ford's content rectangle, 256 colours, 71 KB).
- **The press-to-announcement time (about 1.7 s) is a debug build in the x86_64 emulator, not the
  release number.** Plan §6a's target of under 3 s is for a release build on the Pixel (P11.7).

## 5. Deviations and decisions

- **"Nearer" means at least 1 km nearer.** The first lab run, `20261003T232225Z` (pass, trimmed),
  applied the plan's rule literally. It named a 2-minute Chevron over a 1-minute Shell at the
  same exit, because the Chevron was a few metres nearer. The rule now asks for a nearer stop to
  be ≥ 1 km nearer (`NEARER_BY_M`) before it beats a smaller detour. Both numbers are constants,
  open to change after a drive.
- **"The tile says so" is the button.** While the route has a via point, the card's primary
  button reads **Replace stop**. There is no separate toast.
- **Places under 300 m ahead are dropped** as too close to turn off for. The plan only said
  "behind".
- **The search also runs while joining the route** (from its start). The plan said "while
  following".
- **No stop: a notice card as well** (asked for by the lead after the first report). With every
  detour failed, the card and the voice say so rather than "none".
- **The announcement never cuts off a turn prompt.** It waits up to 10 s for one to finish.
- **The corridor markers** are all the places in the corridor, capped at Markers per category,
  with the best first and larger.
- **Via release on reaching** (50 m) is new. The pass rule already existed in `Guide`.

## 6. Open issues (Pixel and truck)

- **Release timing on the Pixel** (P11.7). The corridor query and ten short routes on the phone's
  cores are unmeasured. The plan's fallback (say the nearest first, the detour after) is not
  built, since the debug emulator run is already under 3 s.
- **Nothing heard.** The lab engine is a silent stand-in. The words and their timing beside turn
  prompts need SYNC 4 over A2DP.
- **On the truck**: pressing Gas under the turn card at speed; whether three rows read on the
  726x480 tile while driving; Add stop on a real drive. Via release when pulling into and out of
  a real station was seen once in the lab and is host-tested; it needs a drive.
- **Not run in the lab**:
  - Food and Shop, km wording, the no-stop sentence, the notice card and Replace stop (all
    host-tested only). The notice's drawing branch in `DashboardDrawing.drawPoiCard` has not been
    rendered;
  - 1080p and the day palette;
  - a press while live alternatives are being looked for.
- **Routing thread.** The detours queue behind a live-alternatives look that is already running.
  If a route request arrives before the detour work is picked up, that search ends with nothing
  said; a second press starts it again.
