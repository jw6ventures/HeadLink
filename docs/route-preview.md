# Route preview and alternatives (phone)

Asked for 2026-09-27: a route preview in the app, zoomed out to the whole route, with
zoom and pan, and ideally several route options to pick from. Built on the phone: a
**Preview route and alternatives** button in Home's route card opens a map of the route
with up to two alternatives listed under it. Code: `PreviewActivity.kt`, `RoutePreview.kt`,
`Routing.adopt`/`previewRequest`, `RouteRequest.alternative`. Host test: `RoutePreviewTest`.

## 1. What it does

- Opens fitted to the whole route with a margin, north up. Pinch, drag and double-tap work
  (VTM's own gesture handling). **+**/**−** buttons zoom, and **Whole route** fits again.
- Lists the route on show as **Current route**. **Alternative 1** and **2** are added as
  BRouter finds them. Each row gives the time, how much longer than the fastest option,
  the distance in the dashboard's unit, and the road it drives furthest on ("via I 35").
- Tapping a row draws that route in the route cyan, on top with a dark edge. The others
  are grey underneath. **Use this route** follows it; **Keep this route** or **Close**
  leaves everything as it was.
- An alternative that shares more than 80% of its points with an option already listed
  is dropped as the same route.

## 2. How the alternatives are found

BRouter's own alternatives (`alternativeIdx`) search again with every road an earlier
route used costing its length over again. That penalty is fixed in the engine
(`OsmPath.addAddionalPenalty`, in the `final` kinematic path), so it cannot be lowered
without editing the vendored tree. It is strong: around Austin its alternatives shared
under 10% of the best route's points, and ran 30 to 100 minutes longer (Temple: 91 and
101 min against 65).

James wanted alternatives that may split off later or rejoin the same highway, so
`AlternativeFinder` uses each of BRouter's alternatives only to find **a different
corridor**:

1. Take the points 30%, 50% and 70% of the way along BRouter's alternative *k* (for *k* = 1
   and 2), keeping those at least 1 km (or 2% of the trip) off the best route.
2. Compute the ordinary best route **through** each point (`RouteRequest.viaLat/viaLon`).
3. Drop any that turn back at the via point (over 150° across 150 m either side) or
   repeat a route already offered (over 80% of points shared). Keep the fastest.
4. If none survive, offer BRouter's own alternative, unless it is a repeat.

Measured on the Texas tiles, desktop JVM, release classes:

| Trip | Best | BRouter's own | Offered (share of best's points) | Time to find |
|---|---|---|---|---|
| Austin → Temple | 65 min | 91, 101 min | 71 min (55%), 92 min (35%) | 10.6 s |
| Austin → Dallas | 176 min | 247, 279 min | 204 min (45%), 204 min (70%) | 8 s, 24 s |
| Austin → College Station | 131 min | 147, 147 min | 134 min (58%), 143 min (38%) | 7 s, 18 s |
| Austin → Round Rock | 22 min | 30, 34 min | 27 min, 27 min | 2 s, 4 s |

Each alternative is shown as soon as it is chosen.

## 3. Why a separate process

VTM keeps its settings in statics: the display density every line and label is scaled
by, the tile size, the thread it treats as its own, and the GL state it caches. The head
unit's map (`MapEngine`) sets all of them for a 240 dpi panel drawn on the scene thread.
A phone map in the same process would reset them under a running projection. So
`PreviewActivity` runs in `:preview`, and `SourceApplication` does nothing there: no
identity load, report persistence or call-service reset.

The two processes share files only:

1. `ConnectionActivity` writes `files/preview/request.bin`: the route on show, the trip's
   two ends and bearing, the routing options, and `Routing.token`.
2. The preview computes the alternatives itself, on its own thread, so a re-route in the
   main process never waits behind them.
3. On **Use this route** it writes `files/preview/choice.bin` (token and route) and
   returns the token. `Routing.adopt` follows the route **only if the token still names
   the route on show**. After a re-route or a new destination the choice is refused and
   the phone says so.

Both files are versioned, written to a `.part` file and renamed, and bounds-checked on
read (`RoutePreviewTest`).

## 4. Costs and limits

- Up to 11 searches for two alternatives: BRouter's alternative *k* is *k*+1 searches,
  plus up to three via routes each. See the timings in §2.
- It does not need a projection. The route on show is whatever the phone has, including
  one set from the phone's own position before going to the truck.
- **Re-routes keep to a chosen alternative's via point while it is ahead** (§6). An
  alternative with no via point is dropped by a re-route, as before.
- The preview's map uses the dashboard's own day or night theme, following the phone's
  dark mode. With no map installed the lines are drawn on a plain background, with a
  note saying so.
- The head unit shows the live alternatives (§5), not the full preview.

## 5. Alternatives on the projected map (2026-09-28)

While a route is followed on the head unit, `LiveAlternatives` looks for other routes
**from a point about 45 seconds ahead of the vehicle** (at least 400 m) and draws them on
the dashboard map. Each is a grey line (`MapEngine.setAlternatives`, under the route's
casing) with a bubble such as "+6 min" 300 m into the stretch it does not share, near
where it would turn off. **Tapping a bubble takes that way**: its via point becomes the
chosen route's, and a route is asked for from the vehicle through it
(`LiveAlternatives.choose`).

Why ahead, and why near the turn: the first lab drives found alternatives from the
vehicle's own position. Both split at the very next junction, which the vehicle had
passed before the search finished, so they were removed as soon as they appeared. At the
midpoint of the alternative, the Boston trip's bubbles were 5–6 km off a street-zoom
pane.

- **When:** only while projecting, with a destination, guidance on the route and not
  re-routing, more than 3 km left, and Route preferences → *Show other routes on the map*
  on (the default). The first look comes 30 s after a route starts being followed, then
  every 5 minutes. Any new route (a re-route, a new destination, a choice) clears what is
  shown and restarts the clock.
- **How:** `AlternativeFinder` over the rest of the route from the lead point
  (`Route.remainder`, `LiveAlternative.leadIndex`), on the routing thread through
  `RoutePlanner.explore`. A route request made meanwhile stops the
  running search and skips the rest, so a re-route waits for one terminated search at most.
- **Delta:** the alternative's time minus the followed route's own time from the same
  point, both BRouter's estimate with the time adjustment applied (not Learn my pace).
  "same" means within half a minute; a faster one shows "−2 min".
- **Not shown:** anything more than 10 minutes longer, or a quarter of the time left when
  that is more.
- **Expiry:** an alternative disappears once the vehicle passes the last point it shares
  with the route, because it is no longer a way to go.

## 6. The destination and route are saved (2026-09-28)

`NavigationStore` keeps the destination (with its label and source), the chosen
alternative's via point, and the route itself in `files/navigation/current.bin`. It uses
the preview's file format (version 2, which added the via point). The file is written off
the main thread whenever the route changes, is deleted when the route is cleared or ends
after arrival, and is read back when the main process starts (`SourceApplication`).

- **The route is kept whole, not as an ID to recompute from.** A route depends on where it
  started, and a recompute from the truck would not start from the couch. A restored route
  that starts somewhere else is joined by `RouteFollower`, or re-routed like any other.
- **The via point is the identifier of a chosen alternative.** `Navigation.via` makes every
  route to the destination pass through it, **re-routes included**, until the vehicle is
  50 m past it (`Guide`, `Navigation.viaPassed`). From there the best route is the chosen
  one. So leaving a chosen alternative no longer drops it, as long as its via point is
  still ahead. An alternative with no via point (BRouter's own, used when no via route
  qualified) still falls back to the best route on a re-route.
- The file holds a place. It is app-private, the app allows no backup, and the report says
  only `routing_restored=route|destination|-` and the event log how old it was.

## 7. Report

`[routing]` gains `routing_alternatives_chosen`, `routing_restored`, `routing_via`,
`routing_show_alternatives` and a `live_alternatives_*` line (looks, found, chosen, shown,
last search time). The map line gains `map_alternatives`, and the dashboard line
`dashboard_alternative_presses`. The event log records each look's count and deltas and a
chosen alternative's figures, never where anything is.

## 8. Verified so far

- `RoutePreviewTest` (40 checks): file round trip and refusals, via names, duplicates,
  bounds, via requests, U-turn detection. With `W100_N30.rd5` it also checks BRouter's
  own alternatives and the finder on Austin → Temple (the first alternative shares part
  of the best route and beats BRouter's own).
- The other host tests still pass, as in `routing-settings.md`.
- **Emulator, not projecting** (x86_64 API 36, 1080x2340, debug APK with x86_64
  `libaap_jni` and `libvtm-jni` added as `emulator-lab.sh` does; Massachusetts map and
  `W75_N40.rd5`; lab route Lexington → Boston Common). Done with a one-off script, not a
  repo lab script, from the phone screen with no head unit:
  - `:preview` ran as its own process beside the main one. The map drew with the app's
    day theme, fitted to the whole route with its labels.
  - Two alternatives appeared (+2 min each, "via Gerry's Landing Road" and "via Huron
    Avenue"). Before the via names excluded roads other options use, both rows said
    "via Gerry's Landing Road".
  - Tapping a row moved the cyan onto it. Drag, **+** and **Whole route** moved the map.
  - **Use this route** brought Home to "13 mi · 33 min" from "12 mi · 31 min", the
    alternative's figures. There was no crash in either process.
  - First attempt: the preview crashed with `libvtm-jni.so not found`, because the lab
    APK lacked VTM's x86_64 library. That is a lab packaging step, not an app bug: the
    phone APK carries the arm64 one the dashboard already loads.
- **Saved route across a forced close** (same emulator, 2026-09-28): a destination shared
  as OsmAnd shares one, routed from an emulated phone position (the couch case), then an
  alternative chosen in the preview. After `am force-stop` and a relaunch, Home still said
  "13 mi · 33 min" (the alternative, not the 12 mi best route), and the preview opened on
  it and looked for others again.
- **Live alternatives on the projected map** (`emulator-lab.sh guidance` with
  `LAB_GUIDE_DEST=42.3551,-71.0656`, a 6 km drive toward Boston Common at 15 m/s, debug
  APK): two looks found "+3 min +1 min", then "+3 min". The map drew them
  (`map_alternatives_max=2`) and the bubbles were on the pane for 1,681 frames. Frame 456
  shows the grey branch leaving the route just ahead with "+3 min" beside it. The
  arrival and re-route checks fail on that drive by design. Searches took 66 s and 35 s
  on the debug emulator build, which is not a speed measurement.
  - Two defects the lab caught, both fixed. BRouter's snapped start made every
    alternative "split" at the vehicle and vanish on the next fix. And alternatives
    searched from the vehicle split at the very next junction, passed before the search
    ended. Hence the 50 m end allowance, the 45 s lead and the bubble near the turn.
  - Not exercised: tapping a bubble on the head unit (the dashboard test presses one; the
    lab receiver does not), and the via-keeping re-route after a real missed turn.
- Not on the Pixel or the truck. Pinch was not exercised (the emulator script can only
  drag and tap). Not timed on a phone: the finder's desktop times are in §2.
