# P6.4 — destinations and the route line: implementation record — 21 September 2026

The slice the [P6 plan](p6-plan.md) §7 calls P6.4: destination entry (§5, with addresses
borrowed from OsmAnd per §5.1) and the route line on the owned map. Built on P6.3's
`Routing`/`RoutePlanner` and P6.3a's multi-tile data.

Status: **implemented, host-tested, lab-verified on the emulator.** Nothing has run on the
Pixel, so **no real OsmAnd share has been read yet**. The parser is written against
OsmAnd's source, and §5.1 already says that is not evidence. §4 has the whole list.

---

## 1. What was built

### 1.1 `DestinationParser` — reading a share (`DestinationParser.kt`, pure JVM)

- **`parseUri`** (`ACTION_VIEW`): a `geo:` URI. `q=lat,lon(label)` wins over the path,
  which is Android's documented labelled pin and what OsmAnd's "Geo" item sends. RFC 5870
  altitude and `;crs=`/`;u=` parameters are read past. Labels are percent-decoded, with
  `+` as a space.
- **`parseText`** (`ACTION_SEND`, and the connection screen's box): the first `geo:` in
  the text, read up to the end of its line. Failing that, an `osmand.net/map?pin=` link or
  the older `/go?lat=&lon=`. Failing that, **exactly one** bare `lat,lon` with a decimal
  point in both halves, so "1,2" in a sentence and a phone number broken by a comma are
  not places.
- **Refusals** carry a reason and nothing else:
  - `address-only`: `geo:0,0?q=<text>` from Contacts or Calendar, and also
    `geo:lat,lon?q=<text>`. Android defines the second as a *search near* that point, so
    the point is not the destination either. An address-only `geo:` line does not end the
    search when an OsmAnd pin follows on the next line.
  - `no-location`: nothing readable, out-of-range numbers, `NaN`/`Infinity`/hex floats
    (which `toDouble` would accept), and `0,0`.
- The OsmAnd link's `#zoom/lat/lon` fragment is **deliberately not read**. It records
  where the sender's map was looking, and that is the pin only when they happened to
  centre on it.
- Labels are stripped of control characters and capped at 200 characters.
- **`mirroredLongitude`**: the swapped-sign hint from P6.3a. If no installed tile covers
  the destination but one covers its mirrored longitude, the phone asks "did you mean
  −97.74310?". It never assumes the answer.

### 1.2 `Navigation` — destination and start (`Navigation.kt`)

Main-thread state that feeds `Routing`.

- **Start**: the vehicle's own fix while a dashboard projects, which is the same fix the
  map follows. Without one, it uses a phone fix no older than **120 s**. When there is
  none, it makes a single `getCurrentLocation` request, and only if the operator has
  already granted location. As before, this app never asks for location itself.
- **With no start available** the destination is held (`routing_destination_waiting=true`).
  It routes as soon as a vehicle fix arrives. A parked vehicle sends no second fix, so it
  also routes when a pipeline starts and receives the fix held from before video start.
- **A destination no installed tile covers** is refused (`no-routing-data`) before
  BRouter starts. The phone names the tile to import, or gives the mirrored-longitude
  hint.
- A new destination replaces the route; `RoutePlanner` already stops a running search.
- **Clear route** drops the destination and takes the line off the map. A search that is
  still running finishes and is counted as superseded.

### 1.3 Where destinations come from

| Source | Path | Report label |
| --- | --- | --- |
| Share sheet text (OsmAnd "Message") | `DestinationActivity`, `ACTION_SEND text/plain` | `send` |
| `geo:` (OsmAnd "Geo", Contacts, Calendar) | `DestinationActivity`, `ACTION_VIEW geo:` | `view` |
| Connection screen box: one point, a `geo:` or an OsmAnd link | `ConnectionActivity` | `typed` |
| Two points, as in P6.3 | `ConnectionActivity` | `typed`, start `typed` |
| Long press on the projected map | `MapControl` → `ProjectionService` | `map` |
| P6.3's debug `lab_route` | unchanged | `lab` |

`DestinationActivity` has no window (`Theme.NoDisplay`, `noHistory`, excluded from
recents). It parses the intent, reports the outcome in a toast and finishes, so the driver
stays in OsmAnd. A separate activity keeps a share from stacking a second connection
screen onto the sharing app's task. **The manifest gains no permission.**

**Long press**: one finger held for **800 ms** within the 24 px pan slop, inside the map
pane, and not starting on a map control. The pick is read when the finger lifts, because
the head unit sends nothing while a finger rests. The point is the ground under where the
finger landed, on the camera actually drawn: `MapControl`'s pan arithmetic, run in
reverse. The route line appearing is the only feedback on the head unit.

### 1.4 The route line (`MapEngine.kt`)

- A VTM `PathLayer`, ordered **above the roads and buildings and below the labels**, so
  street names stay readable along the route (§2.2 shows it). It is present even with no
  map data.
- Colour `#22C7F0`, width 9 px at the map's 240 dpi. No road in `dashboard-dark.xml` and
  nothing in the dashboard chrome uses that cyan; the chrome's accent blue is `#4E9AF1`,
  with green 154 against 199.
- `Routing.line` is a `@Volatile` value published on the main thread. The scene thread
  compares it by reference once per frame, so an unchanged route costs nothing. A
  pipeline started after the route was computed picks it up on its first frame, and a
  rebuilt renderer is given it again.
- A failed route for a new destination **clears** the old line: after the driver asks
  for somewhere else, the old line points somewhere they no longer want to go.
- **Not thinned.** VTM's path worker already clips to the view and drops sub-pixel steps
  on its own thread. Whether Austin–Denver's 8,677 points need more than that is still a
  measurement to make (§4).

### 1.5 Report (spec §10, P6 plan §9.6)

- **New**:
  - `routing_start=vehicle|phone|typed|lab`
  - `routing_destination_source`
  - `routing_destination_waiting`
  - `routing_destinations_refused`
  - `routing_last_refusal`
  - `route_points` and `route_changes` on the `map_engine=` line
  - `long_presses` on `dashboard_map_view=`
- **Extended**: `routing_origin` keeps its name, so the lab's existing grep still
  matches. Its values are now the destination source labels in §1.3 rather than
  `screen|lab`.
- **Excluded**: the report and the event log record **no label, no coordinates and no
  geometry**. The lab checks this with marker strings (§2.2).

### 1.6 Tools

- `tools/jni-host-test/DestinationTest.java`: 50 checks, using OsmAnd's strings exactly
  as §5.1 quotes them.
- `DashboardTest` gains the long-press test.
- `tools/emulator-lab.sh`: **`LAB_DESTINATION=1`** (needs `LAB_ROUTING`) sends real
  `VIEW` and `SEND` intents during projection.
- `tools/route-line-pixels.py`: the picture-side check, run on the host over the frames
  the receiver decoded. It needs Pillow.

## 2. The runs — 21 September 2026

### 2.1 Build container — pass

`tools/android-build.sh`: lint, the three `verify-apk.sh` passes (so still no `INTERNET`)
and the host tests:

```
jni-host-test: ok 52 scene checks
jni-host-test: ok 208 dashboard checks
jni-host-test: ok 57 routing checks (real-route checks skipped: no W75_N40.rd5)
jni-host-test: ok 23 drop folder checks
jni-host-test: ok 50 destination checks
jni-host-test: ok 90 checks
```

The first build failed in `javac`. The container compiles the tests as US-ASCII, and
`DestinationTest.java` contained a `→` and a `§`. Both were replaced.

### 2.2 Emulator, during projection — pass

```
LAB_DESTINATION=1 LAB_ROUTING=~/.cache/openauto-android/rd5 \
  LAB_MAP=~/.cache/oaa-vtm/maps/massachusetts.map LAB_VIDEO_CONFIG=1920x1080 \
  tools/emulator-lab.sh track
```

Results are in `~/.cache/openauto-android/emulator-lab/20260921T202340Z/`. After the app
reached PROJECTING, the lab sent three intents to `DestinationActivity`:

1. `geo:0,0?q=1100+Labmarker+Street`: refused, `address-only`.
2. `geo:42.35510,-71.06560?z=16&q=42.35510,-71.06560(Viewmarker Common)`: Boston Common.
3. Once that route had come back, OsmAnd's two-line "Message" text for Lexington's
   Battle Green, labelled `Sendmarker Green`, with a real newline between the lines.

The app's event log for those three intents:

```
+4027ms destination from view refused: address-only
+4295ms route requested from view (request 1)
+12504ms route computed in 8197 ms on aap-route: 19251 m, 1905 s, 644 points, 19 maneuvers
+13190ms route requested from send (request 2)
+13801ms route computed in 610 ms on aap-route: 4008 m, 506 s, 192 points, 6 maneuvers
```

The report:

```
routing_requests=2 routing_origin=send routing_start=vehicle routing_pending=false routing_superseded=0
routing_destination_source=send routing_destination_waiting=false routing_destinations_refused=1 routing_last_refusal=address-only
map_engine=vtm-0.25.0 pane=930x920 theme=oaa-dark data=region.map drawn=294 reused=502 moved=123 route_points=192 route_changes=2 error=-
```

- **Start**: both routes started from the vehicle. The first is P6.3's 19,251 m, 644-point
  Boston route to within 2 m. The start was the moving vehicle rather than a fixed point.
- **Privacy**: none of `Labmarker`, `Viewmarker`, `Sendmarker`, `42.3551`, `71.0656`,
  `42.4495` or `71.231` appears anywhere in the saved report.
- **In the picture**: the receiver's 1 fps frames show **0** route pixels in frames 1–12
  and **12,271** in frame 13, as the first route landed. The count drops to ~9,400 in
  frame 15, when the shorter second route replaced the first, and ends at **10,201–10,399**.
  I looked at frames 14 and 31. The line follows the streets and runs under the "Fern
  Street" label.
- **Cost**: `projection_frames_rendered=796`, with none dropped. The P6.3 run had 797 and
  no route line, so a 644-point line costs nothing measurable here.
- **Receiver**: its own track checks passed (12 checks, 0 failures), with the map pane
  changing 49% and 50% per leg as before.

The line starts about 100 px from the vehicle marker. The lab's position is not on a road,
BRouter starts the route at the nearest way, and the vehicle keeps moving after the
request. That is expected: **P6.5** owns snapping the vehicle to the route.

### 2.3 What the run caught

The first lab run failed in the lab script, not the app, which had routed correctly. The
script waited for "computed in" on the connection screen, but `uiautomator` dumps only
views on screen, and the route summary sits below the fold. It now polls the saved report,
which is rewritten on every event. If a future check waits on text lower down that screen,
it will hit the same trap.

## 3. Verification actually performed

- `tools/android-build.sh`, twice; green on the second (§2.1).
- One passing emulator run (§2.2), plus the first run that found the lab-script fault
  (§2.3).
- **Not run**:
  - `tools/check.sh`: no Rust changed.
  - P6.3's plain `LAB_ROUTING` run without `LAB_DESTINATION`. Its branch of the script is
    unchanged, and `routing_origin=lab` is still written. **Code the plain run relies on
    did change**: `Routing.request` gained a start parameter, and the scene loop now
    applies `Routing.line`.
  - `RoutingTest` with the tile: `Router` and `RoutePlanner` did not change.

## 4. Not verified — the honest list

- **No real share.** OsmAnd has not shared to this app on the Pixel. Four things are
  unknown:
  1. The real intent strings. The §5.1 table is source-read.
  2. Whether OsmAnd URL-encodes the label. Both forms are handled.
  3. Whether OpenAutoAndroid appears in OsmAnd's "Geo" chooser at all.
  4. What Organic Maps sends.

  **This is still the check that matters.** It needs no drive: install the release build
  and share one place both ways.
- **The long press has only been tested on the host.** The lab receiver sends taps but
  not holds, and Ford has never been seen sending a held touch. Nobody knows whether a
  finger resting on SYNC 4's screen reaches us as down…(nothing)…up or is broken into
  taps. **No driving-status gate**: a long press sets a destination while moving. Whether
  it should is a question for James.
- **The phone start path has not run.** The emulator always has a vehicle fix. That covers
  last-known with its age limit, the single fresh-fix request, and the waiting destination
  woken by a later fix.
- **Thinning is still open.** 644 points cost nothing. Austin–Denver's 8,677 points across
  four tiles have not been drawn.
- **The toast texts, the destination line and the Clear button** have not been looked at
  on a phone screen.
- **The mirrored-longitude hint** has host tests only.

## 5. Amendments this needs — proposed, not applied

These queue behind those already proposed by P5.5, the P6 plan §9, and P6.1–P6.3a.

1. **plan.md**: a **P6.4** row. Destination entry by share, typed and long press, plus the
   route line. Implemented, host-tested and lab-verified; no gate.
2. **spec §10**: the §9.6 rule now covers a fourth producer. A destination's label and
   coordinates stay out of the report and the event log; only its source label is
   recorded.
3. **spec §5 / APP09**: the app is now a share target for `text/plain` and `geo:`. No
   permission was added, and the manifest comment says why.

## 6. Research status

§2 is **measured on this machine**, 21 September 2026. The log and report lines are quoted
from `app-report.txt` of the run named, the pixel counts are `tools/route-line-pixels.py`
output over that run's frames, and the two frames were inspected by eye. The OsmAnd strings
in `DestinationTest` are still **source-read**, as §5.1 says.
