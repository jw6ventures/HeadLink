# P9.3, P9.4 and P9.6 on the dashboard — traffic colours, HERE's credit, "+N min traffic", the divided turn card: implementation record — 28 September 2026

The projected-dashboard side of the [P9 plan](p9-plan.md): §6 (the route line coloured by
congestion ahead, "Traffic © HERE", and the "+N min traffic" label on the arrival time) and
§7 "The offer" (the divided turn card with Take and Keep, the map zoomed out to both
routes, and its withdrawal). It is built against the shared types in `TrafficModel.kt`
(`TrafficDisplay`, `RouteTraffic`, `TrafficRun`, `TrafficOffer`) and waits for the traffic
loop in `ProjectionService` to publish them (§5).

Status: **implemented and host-tested. Nothing has been rendered.** No lab run was made:
the lab agent does every lab run, once the traffic loop that feeds this exists (§4 lists
what it must check). Nothing has run on the Pixel or the truck. The VTM side (the layers,
the closure's stipple, the offer's proposal line) is compiled but has never drawn a frame.

---

## 1. What was built

### 1.1 The route line ahead, coloured (plan §6, P9.3)

- **Which runs get a colour.** Only `SLOW`, `QUEUING` and `CLOSED`. `NONE` (HERE has no
  data) and `FREE` are left to the route line itself, in its normal cyan: the plan says
  "no data" is never drawn as free-flowing (§12), and free-flowing is not a colour of its
  own (§6: "normal where HERE says free-flowing or has no data").
- **Colours** (`TrafficLines`, all pure constants the host test reads):

  | Level | Line | Why |
  | --- | --- | --- |
  | Slow | amber `#F59A00` | More orange than either theme's arterial yellow (`#f7d57e` day, `#c9b583` night). 7.8:1 on the night ground. |
  | Queuing | red `#F2362B` | Red, as every traffic map has taught. Light enough for 4.4:1 on the night ground. It is near both themes' motorway red, which the edge below deals with. |
  | Closed | dark red `#7A0A0A` dashes on near-white `#F4EFEA` | Dark red alone is 1.7:1 on the night ground; the light gaps make it a visible broken line at night and a red-and-white barrier by day. |
  | Edge, every piece | near-black `#140A08`, 4 px wider than the line | Gives amber its edge on the day theme's white roads (15:1 on the day ground), and separates a red piece from a red motorway under it. |

  All three are more than 150 apart in RGB from the route's cyan and the alternatives'
  grey, and amber, red and dark red are each more than 100 apart (host-tested).
- **Closure dashes.** VTM 0.25's `LineStyle` has `stipple`, `stippleColor` and
  `stippleWidth`, and `PathLayer` switches to its textured-line bucket when `stipple` is
  set (`PathLayer.Worker.doWork`). The shader (`linetex_layer.glsl`) mixes `stippleColor`
  and the line's own colour along the line, so a VTM stipple is two-colour stripes, not
  dashes over holes. That is why the "gaps" are near-white rather than transparent: a
  transparent gap would show the route's cyan through it. Both owned themes already draw
  paths and railways this way, so the shader path runs on the phone. Dash length 16 px at
  the map's 240 dpi, about two line widths. **Never rendered** (§4).
- **One `PathLayer` per piece**, plus an edge layer each, as the plan says. Edges all go
  below lines, straight above the route line and below the labels, so street names stay
  readable. Layers are made as they are first needed and then kept: a piece that goes has
  its layers emptied and disabled, which VTM's renderer skips, so the layer list changes
  only when it grows.
- **Bounded.** Adjacent runs of one level (within 5 m, since the matcher's metres are not
  exact) merge into one piece. At most `TrafficLines.MAX_PIECES` = 8 pieces are drawn,
  nearest first, so traffic adds at most 16 layers. Pieces shorter than 1 m are dropped.
- **Only on the route it was matched to.** `TrafficLines.update` draws a `RouteTraffic`
  only when its `route` is, by identity, the route `MapEngine.setRoute` last put on the
  map (`Routing.line`, which is the object `Guide` follows). Traffic for any other route,
  even one over the same roads, draws nothing and is counted as `map_traffic_stale`. A new
  route on the map takes the old route's pieces off in the same frame.
- **Only ahead of the vehicle.** Each piece starts no earlier than the vehicle's place on
  the route, found by snapping the dashboard's follow position (vehicle, else phone) onto
  the drawn route: near the last cut first, the whole route when that finds nothing within
  40 m, and not at all beyond 250 m (a fix that far off says nothing about where on the
  route the vehicle is). The line behind the vehicle is the plain route line.
- **When layers are rebuilt.** Not per frame. The scene thread hands the traffic and the
  follow position to `MapEngine.setTraffic` every frame, and `TrafficLines` returns null
  (touch nothing) unless:
  - the `RouteTraffic` object or the drawn route changed; or
  - the cut has moved at least `CUT_STEP_M` = 50 m **and** it reaches a drawn piece (or it
    moved backwards that far, a new start on the route).

  So driving towards a jam rebuilds nothing, and driving through one rebuilds its layers
  every 50 m, about every 2 s at 55 mph. The cost of the step is that a jam's colour may
  linger up to 50 m behind the marker. It never goes missing ahead of it.

### 1.2 "Traffic © HERE" (plan §1 clause 6.4 a, §6)

- **When.** Whenever `TrafficDisplay.attributed` is true: a traffic colour is drawn, the
  time includes a delay, an offer is up, or the route was shaped by traffic nogos. The rule
  itself is `TrafficModel.kt`'s; the dashboard carries it per frame as
  `Snapshot.trafficAttributed`.
- **Where.** `DashboardLayout.trafficAttribution`: a line above the ODbL credit, ending a
  margin short of the − bubble, in the ODbL credit's size and on the same plate and
  colours (`attribution` on `attributionPlate`), so it reads in both palettes. Not beside
  the ODbL credit: at 726x480 the two are wider together than the room between the corner
  stacks. Not at the pane's right edge above it either, where the zoom stack is.

  | Display | Box (frame px) | Text |
  | --- | --- | --- |
  | 1920x1080, map left | 584,982 211x35 | 23 px |
  | 726x480 (Ford) | 283,441 92x16 | 10 px |
  | 800x480 | 240,438 92x16 | 10 px |

  It clears both corner stacks, the ODbL credit, the turn card and the divided card at all
  three, with the map on either side (host-tested). The drawing measures the real text and
  right-aligns it in the box; the box's width is the generous 0.62 em estimate.
- **Drawn over a pane with no map too**, outside the map's `ok` branch, because the arrival
  time owes the credit whether or not a map is installed.

### 1.3 "+N min traffic" on the trip panel (plan §6, the dashboard half of P9.4)

- `TripPanel.traffic` is `Dashboard.trafficDelayLabel(display.delaySeconds)`: the delay
  rounded to the nearest minute, as the time remaining is, and null under half a minute
  ("+0 min" says nothing). Null too while re-routing or arrived, when the panel has no
  figures. The loop has already added the delay into the time it gives `Guide`; this only
  labels it.
- **Where.** On the caption line above the arrival time, in the warning colour, left of
  the time remaining: "arrive · +7 min traffic" where both fit, and "+7 min traffic" in the
  caption's place where they do not (the big clock time under it reads as an arrival either
  way). Its size, `tripTrafficTextSize`, is the caption's own 0.22 of the summary, brought
  down so "+12 min traffic" fits beside a six-character time remaining: 22 px at 1080p,
  10.3 px at 726x480, 10.1 px at 800x480. The drawing shrinks it further, to three quarters
  at most, if the real remaining time is wider ("1 h 5 min"), then ellipsizes.
- End route, the voice toggle and the later maneuvers keep exactly the boxes they had.

### 1.4 The offer (plan §7 "The offer", P9.6)

**The divided card.** While `display.offer` is non-null (and not answered, below), the
turn card becomes `DashboardLayout.offerCard`: at the turn card's place and width, taller.
Its part `offerTurn` keeps the next turn; its part `offerPart` holds "Faster: −7 min"
(`offer.label`, bold), the reason (`offer.reason`, dim, under it) and **Take** and
**Keep**, left to right. Take is filled in the accent, as the press that does something;
Keep is a plain button. Guidance never disappears: re-routing and arrived draw in the
turn part as they do on the whole card, and with no turn at all (a route with no fix yet)
the turn part is left empty rather than given to the offer.

Both layouts are built (plan §11 leaves the choice to the lab's renders):

| | 726x480 (Ford) | 800x480 | 1920x1080 |
| --- | --- | --- | --- |
| Pane | 352x413 | 388x411 | 930x920 |
| Turn card alone | 338x82 | 372x82 | 890x184 |
| **Side by side**: card | 338x99 | 372x105 | 890x247 |
| turn icon / street | 59 px / dropped | 63 px / dropped | 148 px / dropped |
| Take, Keep | 91x44 each | 100x48 each | 237x115 each |
| map left under the card | 338x293 | 372x282 | 890x613 |
| **Stacked**: card | 338x143 | 372x146 | 890x339 |
| turn icon / street | 76 px / kept | 74 px / kept | 164 px / kept |
| Take, Keep | 67x47 each | 74x48 each | 178x115 each |
| map left under the card | 338x249 | 372x241 | 890x521 |
| Map bubble (for scale) | 44 | 48 | 115 |

- **Side by side is the default** (`DashboardLayout.defaultOfferLayout`). At the truck's
  pane it takes 99 px of 413 where stacked takes 143, so 293 px of map stay under the card
  for the two routes against 249, and Take and Keep are 91x44 against 67x47. It is also the
  plan's own words: "the next turn keeps the left part: icon and distance, with the street
  name dropped if there is no room". Its cost is the street name and a smaller icon (59 px
  against 76); the distance goes under the icon, bold, 25 px at 726x480. Stacked keeps the
  turn card exactly as it is, street and all, with the offer in a band beneath it.
- **Switching.** Set `DashboardLayout.defaultOfferLayout = OfferLayout.STACKED` before
  projection starts (layouts read it when they are made; a test passes it to the
  constructor instead). The lab needs a way to set it for a run; §5 says where.
- **Targets.** Take and Keep are at least a map bubble tall and half as wide again (the
  head unit's target size since P5.5), a margin apart, and clear of the turn part and the
  words, at every display, both layouts, both sides (host-tested). Press and release on the
  same target of the same offer is an answer, as on every button here. A press that slides
  to the other one, a cancel, or an offer replaced under the finger answers nothing.
- **Hit testing does not leak.** A finger on the divided card is on the card: it starts no
  map pan, and a live alternative's bubble that has slid under the card is not hit.
- **Answered at once.** Take or Keep makes the card whole on the next frame and hides the
  answered offer (by id) even if the loop publishes it again before it has reacted. A
  different offer, or a display without one, clears that.

**The map's view of both routes.** When an offer appears **and the map is following the
vehicle**, `MapControl.fit` leaves following (the existing not-following state) and frames
the rest of the followed route from the vehicle, the whole of `offer.route`, and the
vehicle itself, inside `DashboardLayout.offerView`: the pane under the divided card, inset
by a margin. The arithmetic:

- Every point is projected into the screen axes of the rotation on show (Web Mercator, as
  `marker` draws it), so the box is the one the driver sees, in direction-of-travel-up
  too.
- The zoom is the largest that fits both extents, clamped to the pinch's range
  (`MIN_SCALE`..`MAX_SCALE`) and **never closer in than the map already was**: a fit is an
  overview.
- The extent's middle goes to the middle of `offerView`, not of the pane.
- While fitted, the rotation is held where it was measured. Turned with every bend in
  direction-of-travel-up, the two routes would swing out of the pane.
- It is framed once, when the offer appears (and afresh for a replacement), not every
  frame.
- The proposal is drawn by `MapEngine.setOffer` in the alternatives' grey on its darker
  edge, under the route's casing like them. The jam that caused it is already in its
  traffic colour on the followed route (§1.1).

**Back to following.** When the offer goes — Take, Keep, withdrawn by the loop (a display
with `offer = null`), or replaced by another — the card is whole again and
`MapControl.unfit` puts the map back on the vehicle at the zoom it had before the fit.
Decided, not to fight the driver:

- **Only if the offer took the map out of following.** A map the driver had already
  panned when the offer came is not fitted at all (the card still divides), and is left
  where they put it.
- **Only if the driver has not taken the map since.** A drag past the slop, a pinch, a
  twist, a zoom bubble or the compass after the fit makes the view theirs
  (`MapControl.fitted` goes false), and the offer's end leaves it alone.
- The recentre bubble pressed during a fit follows the vehicle and gives the old zoom back
  too, since the fit, not the driver, took it away.

### 1.5 The entry points (for the traffic loop in `ProjectionService`)

All on `ProjectionPipeline`, the way `Guide.turnListener` → `pipeline.upcomingTurn` and
`LiveAlternatives.listener` → `pipeline.alternatives` already work:

```kotlin
/** Main thread: whenever the traffic, the delay or the offer changes, the whole display. */
fun traffic(display: TrafficDisplay)

/** Called on the main thread, from pipeline.touch() (which the service calls on main),
 *  after the dashboard's lock is released: the offer's id, and true for Take. */
var offerListener: ((id: Long, take: Boolean) -> Unit)?
```

- `traffic` stores the display under the dashboard's lock. Nothing else is needed for the
  colours: the scene thread reads it back every frame (`Dashboard.trafficDisplay()`),
  with the follow position, and hands both to `DashboardRenderer.setTraffic` →
  `MapEngine.setTraffic`, after the route, so a new route and traffic matched to it land in
  the same frame. The proposal line follows `Dashboard.shownOffer()` by identity.
- `offerListener` is invoked once per answer. Take should do what a live alternative's
  bubble does (`LiveAlternatives.choose`'s path); Keep should record the refusal for plan
  §7's "not offered again this trip unless its saving grows by 3 more minutes". The card is
  already whole and the map already following when it is called. The loop should then
  publish a display without that offer; until it does, the dashboard keeps it hidden.
- The loop must publish `offer = null` to withdraw an offer (split passed, saving gone):
  that is what refollows the map.
- `RouteTraffic.route` must be the object `Routing.line` holds (the one `Guide.onRoute` was
  given), or nothing is drawn. `delaySeconds` must be the delay already added into the time
  given to `Guide`; the dashboard only labels it.

Lower down, for completeness: `Dashboard.traffic(display)`, `trafficDisplay()`,
`shownOffer()`, `followedRoute(route)` (scene thread, when `Routing.line` changes),
`takeOfferAnswer()`; `DashboardRenderer.setTraffic(traffic, position)` and
`setOffer(route)`; `MapEngine.setTraffic` and `setOffer` (scene thread);
`MapControl.fit(latitudes, longitudes, pane, view)` and `unfit()`; `TrafficLines` (pure).

### 1.6 Report fields

Counts, minutes and booleans only. Never a road, a reason, a label or a place
(host-tested against the reason, the label and the coordinates).

- **Dashboard line** (`Dashboard.report`, new last line):
  `dashboard_traffic_displays` (displays published), `dashboard_traffic_runs` (runs in the
  current one), `dashboard_traffic_attributed` (now), `dashboard_traffic_attributed_frames`,
  `dashboard_traffic_delay_min` (now), `dashboard_offer` (one on show now),
  `dashboard_offer_layout` (`side-by-side` or `stacked`), `dashboard_offers_shown`,
  `dashboard_offers_taken`, `dashboard_offers_kept`, `dashboard_offers_withdrawn` (went
  without an answer, replacements included), `dashboard_offer_fits`,
  `dashboard_offer_refollows`.
- **Map line** (`MapEngine.report`, new last line): `map_traffic_pieces` (drawn now),
  `map_traffic_pieces_max`, `map_traffic_rebuilds`, `map_traffic_stale` (displays for a
  route other than the one drawn), `map_traffic_dropped` (left out by the cap at the last
  rebuild), `map_traffic_layers`, `map_offer_line`, `map_offer_changes`.
- **Map view** (`dashboard_map_view=`): `fits=` and `fitted=` appended.
- `dashboard_layout=` ends with `offer=side-by-side` or `offer=stacked`.

### 1.7 Smaller changes

- `ProjectionPipeline.releaseRenderer` now also forgets the alternatives and the offer's
  proposal it gave the old map engine. Before, only the route was forgotten, so after an
  encoder rebuild on a focus regain the new engine drew no live alternatives until the
  list changed. Found while adding the offer line, which needed the same reset.
- `MapEngine`'s comment that the app "has no INTERNET permission (APP10)" now says it
  never fetches map data, which stays true with P9's `INTERNET`.

## 2. Verification

All under `flock ~/.cache/openauto-android/p9-build.lock`.

### 2.1 Host tests

**New `DashboardTrafficTest`** (registered in `tools/jni-host-test.sh` by the
orchestrator, with the BRouter and VTM jars) passes: 2,084 checks. It runs at 1920x1080,
Ford's 726x480 content rectangle and 800x480, each with the map on both sides, in both
offer layouts, where a check is about layout.

- **Pieces**:
  - None and free draw nothing; slow is amber, queuing red, closed dark red, and only
    closed is dashed.
  - The usual test traffic (none, free, slow twice, queuing, free, closed) is three
    pieces, the two slow runs merged, each with the route's own points and ends
    interpolated at the right metres.
  - Cut at 500 m and at 900 m, nothing behind the cut; past everything, nothing.
  - Runs 3 m apart merge; 60 m apart stay apart; twenty alternating runs are capped at 8,
    the nearest, with 12 counted dropped; half a metre is not a piece; out-of-order runs
    come out in route order.
- **The cut moves in steps**: new traffic is drawn at once; the same again changes
  nothing; towards the jam nothing is rebuilt; into it the piece is cut at the vehicle,
  then not again for 20 m (or a fix a few metres to the side), then again at 55 m; a fix
  9 km off the route moves nothing; back at the start the whole piece returns; new traffic
  replaces the old at once.
- **Stale**: traffic for another route object over the same roads draws nothing and is
  counted; nor with no route; a new route takes the old pieces off at once.
- **Colours**: distances from the route cyan and the alternatives' grey, between the three
  levels, and contrast on the night ground, the edge on the day ground, each line against
  its edge, and the dashes against their gaps.
- **Attribution**: the rule for none/free, a colour, a delay, an offer and a shaped route;
  the dashboard draws it and stops. The box is in the pane, wide and tall enough, above the
  ODbL credit, left of the zoom stack, clear of every bubble and of both cards, at ≥ 10 px.
- **Delay label**: 0, 29 → none; 30, 89 → +1; 90 → +2; 420 → "+7 min traffic"; the arrival
  time is left alone; re-routing has none. "+12 min traffic" fits beside "12 min" at ≥ 9 px
  at every display, and End route and the voice toggle are where they were.
- **Divided card**, every display, both layouts: at the turn card's place and width, in the
  pane; the two parts do not overlap; the icon stays, square, ≥ 40 px; stacked keeps the
  turn card, icon and street exactly; side by side drops the street and the distance under
  the icon is ≥ 18 px; Take and Keep are inside the offer part, ≥ a bubble tall and 1.5
  bubbles wide, a margin apart, clear of the turn, the icon and the words; centres and
  corners hit, the gap, the icon and the words miss; "Faster: −12 min" fits at the
  drawing's smallest size and the reason is ≥ 11 px; the card clears all four bubbles; the
  fitted view is under it and at least half the pane tall.
- **Default**: side by side leaves more map and bigger targets than stacked at 726x480 and
  800x480, at the cost of the street.
- **Fit arithmetic** (`MapControl`): at every display, every point of both routes lands
  inside `offerView` (±1 px), the zoom is between `MIN_SCALE` and the old zoom, and the fit
  is tight on one side. In direction-of-travel-up at 30°, a new heading does not turn the
  fitted view and every point stays inside; after `unfit` the heading turns it again.
  Texas to Alaska clamps to `MIN_SCALE`; two points 10 m apart do not zoom in; nothing to
  frame is no fit. `unfit` follows at the old zoom, once; after a drag or a zoom press it
  leaves the map alone; recentre from a fit gives the old zoom back; fits are counted.
- **The offer on the dashboard**, every display: it divides the card with its label and
  reason, guidance stays, the map leaves following, the vehicle is inside the view, the
  scene thread sees the offer. **Take and Keep**, every display: lit while pressed; the
  answer carries the id and the choice, once; the card is whole and the map following at
  once, at the old zoom and place; no pan; counted; the answered offer stays down when
  republished; a new one shows. **Withdrawal** refollows at the old zoom; a replacement is
  a withdrawal and a new fit. **The driver's pan**: panned during the offer, its end does
  not recentre; panned before it, no fit but the card divides. **Presses**: a drag starting
  on the card moves nothing and is no pan; down on Take and up on Keep, a replaced offer
  under the finger, and a cancel answer nothing; once withdrawn, Take is not a target.
- **Report**: every field above, and never "I-35E", "Queue", "Faster", "HERE", or the
  coordinates.

`DashboardTest` (588 checks), `DashboardTripTest` (2,364) and `DashboardFlexTest` (1,386)
pass unchanged: the whole turn card, the trip panel and the map control behave as before.

Commands (the narrow loop, then the full build):

```sh
flock ~/.cache/openauto-android/p9-build.lock ~/.cache/openauto-android/p9/dash/compile.sh
flock ~/.cache/openauto-android/p9-build.lock tools/android-build.sh
```

The first is a scratch script outside the repository: `:app:compileReleaseKotlin` in the
Android image, then `javac -Xlint:all -Werror` and the four dashboard tests.

### 2.2 Full build

`tools/android-build.sh`, with the other P9 agents' work in the tree as it stood on 28
September 2026: **exit 0**. That covers clippy, both APKs, `lintDebug` with warnings as
errors, `verify-apk` on all three, and every host test, `DashboardTrafficTest` among them
(2,084 checks). Kotlin is compiled with warnings as errors, so the new code has none.

## 3. Deviations from the plan, and why

- **Side by side is the default**, where the orchestrator's brief left it open: the
  numbers in §1.4 favour it at the truck's pane. Stacked is one assignment away for the
  lab's renders.
- **"+N min traffic" is on the caption line above the arrival time**, not after it on the
  same line. At 726x480 the arrival time and the time remaining already fill that line
  (158 px); the caption line has the room.
- **The closure's gaps are near-white stripes, not holes**, because VTM's stipple is a
  two-colour pattern (§1.1). A transparent gap colour would show the route's cyan through
  the closure, which reads as "open".
- **Every traffic piece has a dark edge**, in both palettes. The plan names only the line
  colours. Without the edge, amber is under 2:1 on the day theme's white roads, and the
  queuing red sits on the motorways' red with nothing between them.
- **A fit never zooms in**, and holds its rotation while it lasts. Both are additions to
  "zoom out to show both routes whole" that keep it an overview.
- **A map the driver has panned is not fitted** when an offer comes. The plan says the map
  zooms out; the brief says not to fight a driver's pan. The card still divides, and the
  recentre bubble is one press away.

## 4. Not verified — what the lab must check

Nothing here has drawn a frame. With `LAB_TRAFFIC` and the traffic loop in place:

1. **The colours on the map**, at night and by day: amber, red and the closure's dashes
   where the stand-in server's jam and closure lie, each on its dark edge; the cyan route
   line before and after them; nothing behind the marker beyond 50 m; street labels still
   over the pieces. That the stipple renders as dashes at all, and at a readable length,
   is the first thing to look at. `map_traffic_pieces`, `map_traffic_rebuilds` and
   `map_traffic_stale` in the report.
2. **"Traffic © HERE"** in the pane's corner above the ODbL credit, legible on both
   palettes, clear of the − bubble, at 1080p and at the truck's 726x480 (the Ford lab
   pane), and that it goes when the display stops owing it.
3. **"+7 min traffic"** on the trip panel at 726x480 and 800x480, beside a real time
   remaining, uncut; plan §8 check 2 for the time itself.
4. **The divided card, both layouts, at 726x480 and 800x480, day and night, for James**
   (plan §11). Set `DashboardLayout.defaultOfferLayout` for the stacked render (§5). Look
   at the label and reason sizes, the distance under the icon side by side, and Take's
   accent fill.
5. **The zoom to both routes**: both lines whole under the card, the proposal grey, the
   jam in its colour, the marker inside; then Take switches route and the map follows at
   street zoom; Keep and a withdrawal return to following; a pan during the offer is left
   alone. `dashboard_offer_fits`, `dashboard_offer_refollows`, `dashboard_offers_*`.
6. **Performance** of up to 16 extra `PathLayer`s and a rebuild every 50 m through a jam,
   in `drawn`/`reused` and frame times, on the Pixel eventually.

## 5. Needed from files this work does not own

- **`ProjectionService.kt`** (the traffic loop's owner):
  - After `pipeline.start()`: install `pipeline.offerListener = { id, take -> … }` (Take:
    follow the offer's route as `LiveAlternatives.choose` does for a bubble; Keep: record
    the refusal), and call `pipeline.traffic(display)` on the main thread whenever the
    display changes, starting with the current one. On teardown, clear the listener with
    the others.
  - Take should not also be published as a live alternative's bubble: the pop-up is only
    for a faster way round (plan §7), and the dashboard draws the proposal itself.
- **For the lab's stacked render**: a debug-only way to set
  `DashboardLayout.defaultOfferLayout = OfferLayout.STACKED` before projection starts, for
  example from a lab extra in the service or the connection screen. One line; not added
  here because neither file is this work's.
- **`tools/jni-host-test.sh` header comment** (the orchestrator's): a bullet for
  `DashboardTrafficTest` is in the final report.
