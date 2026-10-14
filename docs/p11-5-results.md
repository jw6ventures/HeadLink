# P11.5 results — POI buttons and markers on the projected map, and the Austin lab — 3 October 2026

This records P11.5 of the [P11 plan](p11-plan.md) (§6): the Gas, Food and Shop buttons along the
top of the map pane, the markers from OsmAnd's maps, the card a tapped marker opens in the flex
tile with Go and Close, and the counts in the report. It also records the Austin lab of §9 and
§12 decision 6: `LAB_REGION=austin` and `LAB_POI` in `tools/emulator-lab.sh`.

Status: **built, host-tested and lab-verified in the emulator** at 1920x1080 and at Ford's 726x480
(800x480 with 74x0 margins), against the real updated Austin OBF file read by the P11.2–P11.3
reader. Markers show in both palettes, a tap on one opens its card, Close closes it. **Go was
not pressed in the lab** (it routes, which the host test checks), **nothing has run on the Pixel
or the truck**, and query times are from a debug build in the emulator only. Nothing is committed.

---

## 1. What was built

### 1.1 App (`app/src/main/kotlin/org/openautoandroid/source/`)

- **`PoiMarkers.kt`** (new, pure JVM):
  - `PoiRules`: button order and words, the marker colours, `parseKinds`, distance,
    `nearestFirst` (only the lit kinds, one per id, a place mapped twice drawn once, capped
    per kind nearest the centre), box padding, the kind word ("Gas station", "Fast food",
    "Supermarket"), the card title (name, else English name, else brand, else kind word), and
    `openNow`.
  - `PoiSearch.query(sources, box, kinds, limitPerKind, lat, lon)`: one query over every open
    file. Files whose bounds miss the box are not read. A file that throws is counted and
    skipped.
  - `PoiFeed`: the refresh loop. It is fed by the scene thread every frame, but only decides
    there. Queries run one at a time on its own thread (`aap-poi`), and stale results are
    dropped and counted.
  - The card's model: `PoiEntry`, `PoiCardContent` (up to 3 places, a primary button word and
    a tag), `PoiAction`, `PoiCardView`, `PoiRowView`, `PoiButtons`, `PoiView`, `PoiShown`.
- **`PoiMarkerArt.kt`** (new, Android): the marker bitmaps. Each is a disc in the kind's colour
  with a white glyph (a pump, a fork and knife, a bag), ringed white inside a dark edge.
- **`Dashboard.kt`**:
  - `DashboardLayout` gained `poiButtonsTop`, `poiButton`, `hitPoiButton`, `poiButtonRow`,
    `poiMarkerSize`, `poiHitRadius`, `poiCardButton`, `poiCardList`, `poiCardRow`,
    `hitPoiCardButton`, `hitPoiCardRow` and `POI_CARD_ACTIONS`.
  - `Dashboard` gained the buttons' state, the markers for hit testing, the card, the touch
    rules, the snapshot fields `poiButtons` and `poiCard`, the 60 s card timeout and a `poi_*`
    report line.
- **`FlexTile.kt`**: `FlexSource.POI`. `FlexRules.source` takes `poiCard`, and its order is now
  call, POI card, weather view, weather alert, notifications, empty.
- **`DashboardDrawing.kt`**: `drawPoiButtons` and `drawPoiCard`.
- **`MapEngine.kt`**: `setPois`, using one VTM `ItemizedLayer` inserted **just under the
  `LabelLayer`**. That puts it above the buildings, the route, its casing and the traffic
  pieces, and street names still draw over markers (seen in the day renders). It also adds a
  `map_poi_markers` / `map_poi_changes` report line.
- **`DashboardRenderer.kt`**: `setPois`.
- **`MapControl.kt`**: `lastCamera()`, `zoomLevel()` and `visibleBox(pane)`, the ground the pane
  shows corner to corner.
- **`ProjectionPipeline.kt`**:
  - `poiFeed`, fed in `drawFrame` after the camera is placed. The markers are compared by
    reference, as the route is, and reset with the renderer.
  - `takePoiAction`, `takePoiToggles`, `poiKinds`.
  - The feed's report line.
- **`ProjectionService.kt`**:
  - For the dashboard scene: one `aap-poi` thread, `ObfLibrary.hold("projection")`, and
    `PoiFeed({ ObfLibrary.sources(app) }, { PoiSettings.markersPerKind(app) }, …)`. The thread
    is shut down and the hold let go in `stopProjection`.
  - Debug builds only: `files/lab-poi` lights those buttons at the start.
  - In the touch handler, button presses are noted (`dashboard poi gas on`) and Go calls
    `Navigation.set(Destination(lat, lon, title), DestinationSource.MAP_POI)`.
- **`DestinationParser.kt`**: `MAP_POI("map-poi")`, added beside the Android agent's `SEARCH`.

### 1.2 Tools

- **`tools/jni-host-test/DashboardPoiTest.java`** (new, 903 checks), wired into
  `tools/jni-host-test.sh` with one run line and one header bullet.
- **`tools/jni-host-test/DashboardTripTest.java`**: two test points moved, each with the reason
  in a comment. The buttons now own the middle of the pane's top when no turn card is up, and
  they are drawn over a live alternative's bubble. One drag started on the turn card's centre,
  and one alternative lay under the button row at 726x480.
- **`tools/lab-receiver/receiver.cpp`**: `--position lat,lon` moves the fixed fix and the
  track's start. `--poi gas,food,shop` (track script) adds steps 510–515, described in §3.
- **`tools/emulator-lab.sh`**: `LAB_REGION=austin` and `LAB_POI`, described in §3. The routing
  tile name is now `$RD5_TILE` everywhere (W75_N40, or W100_N30 for Austin). The render
  extraction takes `receiver_poi=render` lines as well as weather's.

## 2. How it behaves

### 2.1 Buttons

**Where they are.** The three buttons sit in a row along the top of the map pane, centred,
**a map bubble tall and twice as wide**. Each has its marker colour as a dot before the word.

| Display | Buttons | Row |
| --- | --- | --- |
| 1080p | 230x115 | 730 px wide |
| 726x480 | 88x44 | 278 px wide |
| 800x480 | 96x48 | 304 px wide |

**During a route the row moves down.** It sits a margin under the turn card (or under the
divided card while an offer is up), because the top of the pane is the card's. It stays above
the vehicle marker, which is moved down under the card. The host test checks that it is clear
of both bubble stacks, the ODbL and HERE credits, and both cards, at every display with the map
on either side.

**How they look.**

- Lit: the accent fill with `onAction` words, the pair already held to 4.5:1 for the call
  buttons.
- Pressed: the pressed fill.
- Off at every projection start, and never saved.

**How a press works.** A press and a release on the same button flips it, as with every button
here. A press on a button is never a pan or a long press of the map. The buttons win over an
alternative's bubble, which is drawn under them.

### 2.2 Markers

**Size.** A marker is two thirds of a bubble across: 76 px at 1080p, 29 px at 726x480. A press
lands on a marker within half a bubble of its centre.

**Colours.** The colours are the same in both palettes. They are chosen to be far from the map
themes' own colours, the route's cyan and both accents, which the host test checks.

| Kind | Colour | YUV (BT.601) |
| --- | --- | --- |
| Gas | violet `#D500F9` | U 206, V 204 |
| Food | orange `#FF6D00` | U 59, V 200 |
| Shop | green `#00B050` | U 112, V 58 |

The first green, `#00A040`, was only 137 RGB units from the night ground, and the host test
refused it.

**When the markers are asked for again** (`PoiFeed`):

- at once, when a button flips;
- 500 ms after the pane stops moving, if it has left the last query's box, its centre has moved
  a quarter of the pane, or the zoom has changed by a whole level;
- while following and still moving, no more often than every 10 s.

**What is asked for.** The query box is the pane's ground grown by half its size on each side,
so a drive does not empty the map between refreshes. At most 25 markers per kind are drawn
(`PoiSettings.markersPerKind`), nearest the pane's centre. A place of the same kind and name
within 75 m of one already kept is dropped as a duplicate (§4.3). A button turned off takes its
markers away at once, before the next query returns.

### 2.3 The card

**Opening it.** Tapping a marker opens the card. A tap is a press and release within 24 px,
shorter than a long press, nearest the same marker. A drag that starts on a marker still pans
the map, and a long press is still the map's destination pick.

**What it shows.**

- One place: its name (or what it is), then "Gas station · 0.2 mi" from the vehicle (else the
  map's centre) in the header's distance unit, then "Open now" / "Closed now" / "Open 24 hours"
  if the hours parse.
- **Several markers under the finger:** up to three, nearest first, as a list. The label is
  "places" if their kinds differ (§4.2). A row tap chooses another place.
- Go (in the accent) and Close, a bubble tall, across the tile's bottom.

**The open-now line** is shown only for the plain shapes: `24/7`, `Mo-Fr 07:00-19:00; Sa,Su
10:00-16:00`, time ranges with no days, split hours, and ranges past midnight. Holidays, `off`,
sunrise, open ends and comments give no line at all.

**Where the card sits among the flex tile's sources.** A call still wins: a ringing call takes
the tile and closes the card, which does not come back, as the weather view does not. Opening
the weather view closes the card, and a marker tap closes the weather view. An untouched card
closes after 60 s, and a touch on it restarts the clock.

**Go** hands `PoiAction` to the service. The service calls `Navigation.set(…, MAP_POI)` with the
place's title as the label, and the card closes.

### 2.4 Report (counts only)

| Line | Fields |
| --- | --- |
| `poi_on=…` | `poi_button_presses=gas:N,food:N,shop:N`, `poi_markers_shown[_max]`, `poi_taps`, `poi_cards`, `poi_primary_presses` (Go), `poi_close_presses`, `poi_row_picks`, `poi_card_timeouts`, `poi_card_closes_call`, `poi_buttons=x,y+wxh` (layout, for the lab) |
| `poi_queries=…` | `poi_query_failures`, `poi_source_failures`, `poi_stale_dropped`, `poi_query_ms_p50` / `_p95` / `_max`, `poi_markers[_max]`, `poi_sources_max`, `poi_extra` |
| `map_poi_markers=…` | `map_poi_changes` |

The event log gets `dashboard poi gas on|off`, `dashboard poi go pressed` and `lab poi buttons
on: …`. **No name, place, coordinate or file name.** The host test checks the dashboard and feed
reports for those, and the lab checks the app report for `.obf`, `lab-obf` and `Us_texas`.

## 3. The Austin lab

### 3.1 `LAB_REGION=austin`

- `LAB_MAP` defaults to `$OPENAUTO_CACHE/maps/texas.map` (575 MB).
- The routing tile is `W100_N30.rd5`, from `LAB_ROUTING`'s directory when routing is asked for.
- **The position is downtown Austin by I-35, `30.2660,-97.7360`.** It is passed to the receiver
  as `--position` (fixed fix and track start). It is not 30.2672,-97.7431: a grid search over the
  real file (scratch, `ObfFile.pois` on the host) found this the nearest spot to downtown with
  5 gas stations, 10+ food and 9 shops inside an 800x480 pane. Downtown proper has 5 gas
  stations in 2 km², and a pane at Congress and 6th might show none.
- `LAB_ROUTE` defaults to that point and the university (30.2849,-97.7341), and so does the
  guidance destination.
- **The OBF file is mounted read-only**, as `/lab-data/obf/Us_texas_austin_northamerica.obf`. It
  is pushed to `/data/local/tmp` and **moved** (not copied) into `files/lab-obf/`, so the 2 GB
  data partition holds one copy beside the map. Nothing big is copied into the run's `$out`.

### 3.2 `LAB_POI=gas,food,shop`

`LAB_POI` needs OBF data, so it needs `LAB_REGION=austin` or `LAB_OBF`.

**How the buttons are lit.** The lab writes `files/lab-poi`, and a debug build lights those
buttons at the projection start. It is a file and not a lab-intent extra because the intent
handler is `ConnectionActivity`, which another agent owns. With the track script the receiver
gets `--poi` and the projection runs 180 s. The receiver does this, parked:

1. **Waits** at least 10 s after the parked check, then until each lit kind has ≥ 150 pixels of
   its colour in the map pane under the button row. It gives up after 120 s.
2. **Counts the markers and renders** (`renders/poi-<palette>.png`). It flips NIGHT_DATA, waits
   10 s, and counts and renders again. Then it flips back and waits 10 s.
3. **Taps a marker.** It finds the first 3x3 window wholly in a lit kind's colour, scanning gas
   first, and taps there. 2.5 s later the flex tile must have changed ≥ 10%. It renders
   `poi-card-<palette>.png`.
4. **Presses Close.** 2 s later the tile must match its picture from before the tap (< 5%).
5. **Departs.** The usual track checks then run.

**What the app side checks:**

- `poi_queries ≥ 1`, `poi_markers_max ≥ 1`, `poi_markers_shown_max ≥ 1`;
- the lab note lit the buttons;
- `poi_taps=1 poi_cards=1` and `poi_close_presses=1`;
- no `.obf`, `lab-obf` or `Us_texas` in the report.

## 4. Lab results

### 4.1 Final runs (current code)

Final runs: `LAB_REGION=austin LAB_POI=gas,food,shop tools/emulator-lab.sh track`, once with
`LAB_VIDEO_CONFIG=1920x1080` and once with `LAB_MARGINS=74x0` (Ford's 726x480).

| | 1920x1080 (`20261003T225850Z`) | 726x480 (`20261003T225711Z`) |
| --- | --- | --- |
| Result | **pass**, 19 receiver checks, 0 failed | **pass**, 19 receiver checks, 0 failed |
| Marker pixels, night (gas / food / shop) | 6508 / 27904 / 24524 | 752 / 1524 / 1532 |
| Marker pixels, day | 6504 / 27568 / 24160 | 756 / 1524 / 1532 |
| Waited for markers after parking | 10.1 s (the 10 s floor) | 10.3 s |
| Tap | gas window at 617,329; card opened, tile changed 0.47 | gas window at 385,199; card opened, tile changed 0.24 |
| Card | "places": a convenience shop and the Valero station on one spot, 0.2 mi | "gas": Shell, Gas station · 800 ft |
| Close | tile back, 0.00 from before | tile back, 0.00 from before |
| Then the drive | 2 legs, map changed 0.64 / 0.63, static half 0 | 2 legs, as before |
| App report | `poi_queries=1`, `poi_markers=57`, `map_poi_markers=57`, `poi_taps=1 poi_cards=1 poi_close_presses=1` | `poi_queries=2`, `poi_markers=50`, `poi_taps=1 poi_cards=1 poi_close_presses=1` |
| Query time (debug, emulator) | first 963 ms | p50 29 ms, first 189 ms |
| No `.obf` / `Us_texas` in the report | yes | yes |

"Waited" is the receiver's own 10 s floor (§4.2, first run), not the app: in every run the
markers were already drawn when the floor ended. The 726x480 gas count fell from 1028 to 752
after the duplicate fix (§4.2, last row): one of two overlapping "Gulf" markers is gone.

### 4.2 Earlier runs and what they found

| Run | Result | Found |
| --- | --- | --- |
| `20261003T224001Z` 1080p | pass | **The night render showed bare ground.** The parked check had passed after 2 s on a map whose 575 MB of tiles had not loaded: a still pane, not a settled one. The day render 10 s later was full. Fixed in the receiver: the POI step waits at least 10 s after parking. (The track script's own parked check has the same blind spot on large maps; noted, not changed.) |
| `20261003T224251Z` 726x480 | pass | Markers in both palettes (gas 1028, food 1524, shop 1532 px). The run ended with a shell syntax error *after* "pass", because `tools/emulator-lab.sh` was edited while it ran. sh reads a script as it goes. **Do not edit the lab script during anyone's run.** |
| `20261003T224514Z` 1080p | pass | **The first tap opened a convenience shop (Signature Austin) under the Valero gas marker it aimed at.** A fuel station and its convenience shop are two OSM places on one spot. The card showed the shop, and VTM draws whichever it likes on top. Fixed: a tap now lists everything within the hit radius, nearest first, up to three. |
| `20261003T224654Z` 726x480 | **fail** | "no marker found to tap". At 29 px, a marker's coloured ring between the white glyph and the rim is 3–4 px wide, and the receiver looked for a 6x6 window. It now looks for a 3x3 window. |
| `20261003T225107Z` 1080p | pass | The pile list: "places", with Signature Austin (Convenience, 0.2 mi) and Valero (Gas station, 0.2 mi). |
| `20261003T225246Z` 726x480 | pass | **The card listed "Gulf" twice (350 and 400 ft).** One station is mapped as a point and as an outline. Fixed in `PoiRules.nearestFirst`: the same kind and name within 75 m is drawn once. The final runs are after this fix. |

### 4.3 Query time

Query time is from a **debug build in the x86_64 emulator** only:

- **The first query of a projection is the slow one**: 0.96–1.3 s at 1080p (four runs) and
  189–312 ms at 726x480 (three runs), whose pane and box are smaller. It opens the 249 MB file,
  reads its table of contents and the POI tables, then walks the padded box.
- The second query, at 726x480 once the vehicle moved the view, took 26–41 ms.

The plan's target of a marker refresh within 500 ms is for a **release build on the Pixel**
(P11.7), and that has not been measured. The first query could be taken off the first button
press by opening the files when the projection starts.

## 5. What is not done or not known

- **Go was not pressed in the lab.** The host test checks that it hands on the place and that
  the service routes with `MAP_POI`. A lab press would route through BRouter with W100_N30, and
  the guidance script's Lexington drive does not fit Austin.
- **Nothing on the Pixel or the truck.** Missing from both: touch targets and marker clutter on
  SYNC 4 (plan §13), the 25-per-kind default, and Go from the car. Those are P11.7.
- **The query time on a release build is unmeasured.**
- **Which marker VTM draws on top** when markers overlap is VTM's (`MarkerRenderer` sorts by
  screen y). The card lists the pile, so the tap does not depend on it.
- **The real `LAB_POI` path is a debug file, not an intent extra** (§3.2). A lab-intent extra
  would be a small addition to `ConnectionActivity` if the owner prefers it.

## 6. Hooks for P11.5a (search ahead)

- **POIs of a kind on the map now:** `ProjectionPipeline.poiFeed?.current(PoiKind.GAS)`, nearest
  the pane's centre first.
- **POIs of a kind along a box** (a corridor piece): `PoiSearch.query(ObfLibrary.sources(context),
  GeoBox(s, w, n, e), setOf(PoiKind.GAS), limitPerKind, centreLat, centreLon)`. It is synchronous,
  so call it off the main and scene threads. It returns `Result(pois, sourcesAsked, failures)`,
  capped per kind, nearest the given centre, de-duplicated.
- **Markers along the route and the best one larger:** `poiFeed.extra(pois, emphasisedId)`.
  These are drawn beside the pane's own markers until replaced; `extra(emptyList())` removes
  them. `MapEngine.setPois` draws the emphasised marker at 1.5×.
- **The card with a list and "Add stop":**
  `dashboard.showPoiCard(PoiCardContent("gas ahead", entries, PoiCardContent.ADD_STOP, "ahead"))`.
  Each entry is `PoiEntry(poi, detail = "12 mi ahead · 3 min detour · right", openNow = false)`,
  up to 3. The first is chosen, and a row tap chooses another.
- **Reading the press:** `pipeline.takePoiAction()` returns `PoiAction(entry, "Add stop",
  "ahead")` for the chosen row. The service's touch handler currently routes only
  `tag == PoiCardContent.TAG_MAP`, so add a branch for your tag (`Navigation.via`). Close and
  the 60 s timeout need nothing.
- **The press that starts a search:** `pipeline.takePoiToggles()` returns `(kind, on)` pairs.
  The service currently only notes them, in the touch handler next to `takePoiAction`.

## 7. Evidence

- Final renders in `docs/images/p11/`: `p11-5-poi-night-726x480.png`, `p11-5-poi-day-726x480.png`,
  `p11-5-poi-card-night-726x480.png` (Ford's content rectangle inside the 800x480 frame), and
  `p11-5-poi-day-1080p.png`, `p11-5-poi-card-night-1080p.png` (scaled to 960x540). All are
  quantized to 256 colours, 75–170 KB..
- Runs in `$OPENAUTO_CACHE/emulator-lab/`. Superseded runs are trimmed to their text, renders and
  a gzipped trace; video, frames and APKs are deleted.
- Host: `DashboardPoiTest` 903 checks. The whole of `tools/jni-host-test.sh` passed in
  `tools/android-build.sh` on the final code (`p11-5-build3.log`, exit 0).

## 6. Buttons moved to the left, icons for words (3 October 2026, evening)

James saw P11 on the phone and asked for the Gas, Food and Shop buttons on the map's left
side, showing icons instead of words.

**What changed:**

- The buttons are now round map bubbles in the left bubbles' column, going down from a
  margin under the top of the pane, or under the turn card or divided card.
- Each holds the kind's own marker icon (`PoiMarkerArt`). The icon is dimmed while the
  button is off and drawn full on the accent while it is lit.
- Where the column would reach the compass bubble (the stacked divided card at 1080p),
  the three sit side by side under the card, from the same left edge.
- The receiver's search-ahead press follows the new geometry.

**Checks:**

- `DashboardPoiTest`: 1071 checks pass. The full `tools/android-build.sh` passes.
- The lab run `20261004T031721Z` (`LAB_REGION=austin LAB_POI=gas,food,shop LAB_MARGINS=74x0
  … track`) passes with 19 receiver checks. Render: `docs/images/p11/p11-5-poi-left-day-726x480.png`.
