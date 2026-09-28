# P7.4 and P7.5 — turn card, trip panel with End route, players with Resume, empty favourites: implementation record — 24 September 2026

Dashboard part 2a of the [P7 plan](p7-plan.md): §5.3 (the turn card) and §5.4 (the trip
panel and End route), which make P7.4; §5.5 (the idle media pane's players with Resume) and
§5.7 (the empty favourites text), which make P7.5; and §1 items 10, 11, 13 and 14. It
builds on part 1 ([p7-2-3-6-results](p7-2-3-6-results.md) §5) and the players backend
([p7-backends-results](p7-backends-results.md) §2, §6.2). Part 2b (the flex tile, call
card and keypad) is not built here; §6 lists what it can use.

Status: **implemented, host-tested, and lab-verified on the emulator**, End route included,
in both palettes and at 1080p and 800x480. **Nothing has run on the Pixel or the truck.**
One thing the plan asks for is **not** true yet: End route does not hand navigation focus
back (§3.1). That needs a session change in files this slice does not own.

---

## 1. What was built

### 1.1 The turn card (plan §5.3)

- **Where.** `DashboardLayout.turnCard` runs across the top of the map pane, inset by the
  margin like the floating controls, and is a fifth of the pane tall: 184 px at 1080p,
  82 px at 800x480. `turnIcon` is the square at its left.
- **What.** Three things:
  - The maneuver icon.
  - The distance, bold, at 0.4 of the card, through `SpeedUnit.distance` as before.
  - The street on its own line (P6.7's `onto`). When the map names no street, the
    instruction goes there instead ("Turn left" says more than an empty line).
- **Other states.** `Rerouting…` (in the warning colour) and `You have arrived` (under the
  destination pin) take the text line with no distance. A route with no fix on it yet has
  no card at all, because there is nothing honest to say on one.
- **The card goes with the route**, through `Guide.clear()`.
- **Plate.** Solid, in the palette's `tile` colour with a `bubbleRim` edge, drawn after the
  map controls. It is drawn outside the map's `ok` branch: a driver following a route with
  no map installed still needs the next turn.
- **The header no longer carries the turn.** `Snapshot.turn` is gone. The room it leaves
  stays empty, as §5.1 says.
- **Palette roles only.** The icon is `accent`, the faint branches `dim`, the text `text`,
  so both palettes work (§2.3 has both).

**The icon is drawn from the value the cluster is sent.** `Maneuvers.glyph(type, exit)` is
a pure, total map from the raw `ManeuverType` (the same `step.maneuver.type` that goes to
`NativeBridge.submitGuidance`) to a `ManeuverGlyph(shape, degrees, exit)`:

| Shape | Values | Drawn as |
| --- | --- | --- |
| `TURN` | straight 0, slight ±45, normal ±90, sharp ±135 | a stem from the bottom, a branch at that angle, an arrowhead |
| `KEEP` | keep left/right, ±30 | a fork: the kept branch drawn, the other faint |
| `RAMP` | off-ramp slight left/right, ±45 | the branch, with the road carrying straight on faint |
| `U_TURN` | U-turn left/right | up one leg, over, down the other |
| `ROUNDABOUT` | 34 (CCW) and 32 (CW) | the ring, an arrowhead for the direction round it, the exit number inside |
| `DESTINATION` | 39 | a map pin |
| `NONE` | 0 and anything unnamed | nothing |

Negative is left. The instruction the cluster gets and the icon agree on a side for every
BRouter command (host-tested). A roundabout's exit angle is not drawn, because BRouter
gives none, and the wire leaves `roundabout_turn_angle` absent for the same reason.

**The vehicle marker moves down while the card shows.** Decided: yes, by half the card's
height (`DashboardLayout.markerBelow`, 102 px at 1080p, 45 at 800x480).

- The follow view already centres the vehicle in the pane. Under a card that covers the
  top fifth, the marker was a card's height nearer the top of the visible map than its
  bottom. In direction-of-travel-up, that fifth is the road ahead.
- The offset puts the marker at the middle of the uncovered map. It stays above the zoom
  stack's top at every checked size (1080p: arrow bottom 736 px, stack top 757).
- **How.** `MapControl.camera(follow, bearing, below)` centres the camera on the ground
  `below` pixels up the screen from the vehicle. It uses the same `worldOffset` the
  gestures use, so `marker()` puts the vehicle back exactly `below` under the centre, in
  any rotation. `MapEngine` needed no change.
- It applies only while following. A map the driver has panned stays where they put it.
  The long press still picks the ground under the finger, from the same camera.
- **A finger on the card** is on the card, not the map: it starts no pan.
- When a route starts or ends, the map jumps by half a card rather than easing. That is
  the one visual cost.

### 1.2 The trip panel and End route (plan §5.4, §1 items 10 and 11)

- **The panel takes the favourites tile** for exactly as long as `UpcomingTurn` is
  non-null, which is as long as `Guide` has a route. That includes re-routing, arrival, and
  a route with no fix yet, so **End route is never hidden while a route exists**.
- **The favourites are untouched underneath.** Their scroll position is kept, and they
  come back where they were. A finger resting on a favourite when a trip takes the tile
  is dropped, so it cannot lift onto the panel as a tap on a place. While the panel shows,
  the favourites take no presses.
- **Layout** (`DashboardLayout`):
  - `endRoute`: full width along the bottom of the tile, **exactly a media button's
    height** (205 px at 1080p, 93 at 800x480).
  - `tripSummary`: 11/20 of what is left. It holds "arrive", then the arrival time large
    at the left, and the time and distance remaining right-aligned.
  - `tripLater(0..1)`: the next two maneuvers after the current one, side by side, each an
    icon (from the same `glyph`) and its distance.
- **The figures.**
  - **Arrival** is the clock plus `destinationSeconds` from the last fix, rounded to the
    nearest minute. The first lab frame truncated it and said 15:23 beside "9 min" at
    15:15, so it now rounds, as the time remaining does. Parked, the route time stands
    still and the arrival slips with the clock, as it should.
  - **Remaining** uses `StatusText.duration`, so the car and the phone word it the same.
  - **Later maneuvers** show the distance **from the vehicle**, measured from the same
    position as the card. `RouteFollower` now puts `NextStep.later` (up to
    `LATER_STEPS = 2`) in each result. Never sent on the wire.
  - Everything is in words (minutes, rounded distances), so the chrome repaints about
    once a minute.
  - While re-routing, or with no fix on the route yet, the figures are dashes rather than
    stale numbers.
- **End route** is press-and-release on the same target, lit while pressed, and follows
  the same rules as every other button (a slide off, a cancel, and a drag that ends on it
  are not presses). A press comes back through `Dashboard.takeEndRoute()`, the way
  `takeFavoritePick` does. `ProjectionService` then notes `dashboard end route pressed`
  and calls **`Navigation.clear()`**, the path the phone's Clear route button takes
  (`ConnectionActivity`). That clears the destination, takes the line off the map
  (`Routing.clear`), and publishes idle through `Guide.clear()`. The session then sends
  `NavigationState(INACTIVE)`. §3.1 covers focus.
- **Arrival ends the route after 30 s** (`Guide.ARRIVAL_HOLD_MS`). This decision is new
  here and needs James's confirmation. The plan says three things:
  - the favourites come back when a trip is **ended or reached**;
  - the card goes with the route;
  - End route is never hidden while a route exists.

  Guidance kept an arrived route forever, so all three could not hold together. Now
  arrival shows "You have arrived" on the card and "Arrived" on the panel, with End
  route, for 30 s. Then `Navigation.clear()` ends the route, noted as `route ended after
  arriving` and counted as `guidance_arrival_ends`. Nothing changes on the wire: the
  cluster went `INACTIVE` at arrival already. A new route or a clear during the hold
  cancels it.
- **Report**, one new line with no destination, time or street:
  `dashboard_trip=<bool> dashboard_trips=N dashboard_turn_card=<bool>
  dashboard_end_route_presses=N`.

### 1.3 The idle media pane: players with Resume (plan §5.5, §1 item 13)

- **When it is idle.** When now-playing is absent, `no media` or the no-access line
  (`DemoScene.NO_MEDIA` / `NO_ACCESS`), the media pane lists `Snapshot.players`, in
  `MediaPlayers`' order.
- **When it is not.** Playing, or paused with a session (a title, or at least a state),
  the pane is unchanged. The transport buttons are neither drawn nor pressable while the
  pane is idle.
- **Rows.** `DashboardLayout.playerRow(index, hint)` gives `PLAYER_ROWS = 2` rows filling
  the pane, full width with no cover art. Each row has the app's name at the left and a
  **Resume** button (a play triangle and the word) in its right three-eighths. The whole
  row is the target.
  - **Why two, and not "media-button height" literally.** A media button is 205 px of a
    410 px pane at 1080p, so a pane holds one such row and a sliver, and one player is not
    a list. Two rows come to 95% of a media button without the hint and 84% with it. That
    is still larger than a favourite's row, for the same reason: it is pressed from the
    driver's seat. There is no scrolling. The two most recently active players are the
    likely ones.
- **Feedback.** A press comes back through `takeResumePick()`, and `ProjectionService`
  calls `MediaPlayers.resume(player)`. The result is posted to main and handed to
  `Dashboard.resumeResult(key, result)`. On the row:
  - While pending, the name greys and the button says "Starting…".
  - On failure (refused, timed out, not found), the button says "Couldn't resume" in the
    warning colour for 4 s (`RESUME_FAILED_MS`). The lab caught this ellipsized at 1080p;
    it is now scaled down to fit.
  - A resume that works shows itself: the pane turns into now-playing when the player's
    session says so.
- **Without notification access**, one small line along the bottom: "The phone can list
  more players with notification access on". The rows make room for it. Access is taken
  from `MediaAccessService.enabled` at projection start and from every now-playing update
  (`Dashboard.mediaAccess`). The old `no media · grant n…` headline is gone from the
  dashboard. The demo scene still uses its string.
- **No players at all**: "No media players found", centred.
- **Report**, one new line with counts only, never an app name or package:
  `dashboard_media_idle dashboard_media_access dashboard_player_rows
  dashboard_player_rows_max dashboard_resume_presses dashboard_resume_played
  dashboard_resume_failures`. `MediaPlayers.report()` still carries its own
  `media_resume_*`.

### 1.4 Empty favourites (plan §5.7, §1 item 14)

- Two centred lines, "No favourites yet" and "Add them on the phone", at
  `DashboardLayout.emptyFavoritesTextSize`. That is the smaller of the tile's height ÷ 11
  and the size at which the longer line fits the list's width at 0.62 em a character.
- The drawing then measures the real text and shrinks further if either line would
  overrun, so it is never cut. At 800x480 the size comes to 13.4 px.

### 1.5 Structure for part 2b

- `drawTiles` now dispatches by tile.
  - `FAVORITES_TILE` is the trip panel during a trip, else the label and the favourites.
  - `DashboardLayout.FLEX_TILE` (1) is drawn by `drawFlexTile(canvas, paint, tile,
    palette)`, which still says "not configured".
- A trip never touches the flex tile.

### 1.6 The session notification (plan §6.2)

- `ProjectionService.buildNotification` now says
  `StatusText.notification(SessionController.stage, Navigation.destination?.let(Navigation::describe))`,
  the Home card's sentence, instead of `Stage: X`.
- It is re-posted when the sentence changes, not only the stage. A new or cleared
  destination changes it, and every `SessionController.note` reaches the listener.
- The unused `notification_stage` string is removed from `strings.xml`.

### 1.7 For the phone's Home route line

`Guide.remaining(): Pair<Double, Int>?` gives the live metres and seconds still to go, from
the last fix on the route. It is null with no route, no fix yet, while re-routing, or once
arrived. Main thread. It uses the same position as the card, the panel and the cluster.
`RouteClock.kt` (the phone agent's) can switch to it.

## 2. Verification

All under `flock ~/.cache/openauto-android/p7-build.lock`.

### 2.1 Build and host tests

`tools/android-build.sh`: **exit 0**. That covers clippy, both APKs, `lintDebug` with
warnings as errors, `verify-apk` on all three, and every host test.

**New `DashboardTripTest`** (registered in `tools/jni-host-test.sh`, with the BRouter jar
for the commands) passes. It runs at 1920x1080 on both sides, Ford's 726x480 content
rectangle, and 800x480.

- **Turn card layout**:
  - Inset, full width, a fifth of the pane, with a square icon.
  - Clear of all four bubbles.
  - A seven-character distance fits beside the icon, at ≥ 18 px.
  - The marker offset is half the card, the arrow is below the card and above the zoom
    stack, and the marker is centred in the uncovered map.
- **Trip panel layout**:
  - End route is a media button tall, full width and at the bottom.
  - Its hit test works at the centre and corners, misses above it, and misses in the flex
    tile.
  - The summary and the later cells are inside the tile and above End route.
  - "18:40" beside "12 min", an icon beside "0.8 mi", and "End route" in its button all
    fit, with text sizes checked.
- **Player rows**, with and without the hint:
  - Inside the pane, a margin apart, ≥ 80% of a media button and ≥ a favourite's row.
  - Resume sits at the right, and "Resume" fits.
  - The name end of a row hits too; the gap and an empty row miss; with the hint, the
    rows sit above it.
- **Empty favourites**: both lines fit uncut, two lines fit in the height, ≥ 10 px, in the
  plan's wording.
- **Glyphs**:
  - Every BRouter command's maneuver has a glyph.
  - Left instructions draw left and right ones right.
  - Straight is up, a turn is square, slight < 90 < sharp.
  - Fork, ramp and U-turn keep their side.
  - The roundabout carries its exit and its direction.
  - The destination is a pin.
  - Unnamed values draw nothing.
- **Card and panel state**:
  - Guiding: the cluster's value, "0.3 mi", the street, and the instruction without one.
  - Re-routing and arrived: the line, no distance, no stale figures.
  - Waiting: a panel but no card.
  - Cleared: neither.
  - km/h works.
- **Arrival**:
  - Noon plus 600 s is 12:10.
  - Five minutes parked is 12:15.
  - Rounding to the nearest minute.
  - A second and 8 m later reads the same card and panel.
  - "under 1 min", "1 h 5 min".
- **End route**, at every size:
  - Nothing without a route.
  - Lit while pressed, nothing until release, once.
  - A slide off, a cancel, or a drag onto it does nothing.
  - Counted.
- **Favourites**:
  - Scrolled a row and a half.
  - A resting finger is dropped when the trip arrives.
  - No pick during a trip.
  - The same scroll afterwards, and taps work again.
- **Marker**:
  - The pane centre without a card; half a card lower with one.
  - The camera looks north of the vehicle, north-up.
  - No offset while waiting.
  - In heading-up at 0°, 90°, 200° and 333°, the marker is 150 px under the centre
    (±1 px).
  - A panned map ignores the offset.
  - A drag from the card leaves the map following; the same drag without a card pans.
- **Idle pane**:
  - Players listed, two rows counted.
  - A press on the transport button's spot goes to the row drawn there.
  - Lit while pressed, picked once, pending until answered.
  - A refusal shows for 4 s then clears; played clears.
  - A slide off does nothing.
  - Without access, the hinted rows are the ones hit.
  - Playing turns the rows off and the transport buttons on; stopped with nothing is
    idle again.
  - Paused with a title, or with only a state, keeps now-playing.
- **Report**: has every new key; never the street, an app label, a package, the arrival
  time, a distance or the instruction.

**Existing tests**, updated where the behaviour moved:

- `DashboardTest.mediaPressesReturnTheirAction` now sets something playing first, because
  an idle pane has no transport buttons. It is still 587 checks.
- `StreetNamesTest`'s header turn-line check now reads the card: the distance, the street
  on its own line, the same card 10 m closer, re-routing, cleared, and km/h.
- `GuidanceTest` checks `NextStep.later`: the destination 1200 m on after the turn, and
  nothing after the destination.

### 2.2 Emulator lab

The new **`endroute` script**:

- **Receiver** (`--script endroute`):
  - Waits for the cluster to be `ACTIVE` with the vehicle parked, then 3 s.
  - Presses End route at the mirrored `DashboardLayout.endRoute` centre. `DashboardPane`
    now mirrors the trip tile, the turn card, the media pane, End route and the first
    hinted player row.
  - Checks the cluster's next state is `INACTIVE` and that no `ACTIVE` or `REROUTING`
    follows.
  - Checks the trip tile changed by ≥ 15% and the card's area by ≥ 50%.
  - Watches 3 s more, then presses the first Resume row and logs what the media pane did.
- **App side** (`tools/emulator-lab.sh endroute`, needs `LAB_ROUTING` and `LAB_MAP`):
  - It shares the guidance destination as OsmAnd's text while parked.
  - It then checks the report: `dashboard_trips=1`, `dashboard_end_route_presses=1`,
    `dashboard_trip=false`, `guidance_state=idle`, `guidance_arrivals=0`, the notes
    `dashboard end route pressed` and `route cleared`, `NAV_STATE_INACTIVE`,
    `dashboard_resume_presses=1` and `media_resume_presses=1`.
  - No destination marker or coordinate appears in the report.

| Run | Result |
| --- | --- |
| `LAB_ROUTING=… LAB_MAP=…massachusetts.map LAB_VIDEO_CONFIG=1920x1080 endroute` (night), `emulator-lab/20260924T151424Z` | **pass**, `receiver_checks=16 receiver_check_failures=0` |

What that run showed:

- **Receiver.** `receiver_endroute=states=1,2,0 tile_changed=0.448 card_changed=0.839`.
  The trailing `0` is the session's teardown after the run.
- **App.**
  - Event log: `+5448ms route computed … 6 maneuvers`, `+8501ms dashboard end route
    pressed`, `+8506ms route cleared`.
  - `navigation_states_sent=NAV_STATE_ACTIVE:1,NAV_STATE_INACTIVE:1`.
  - `dashboard_trip=false dashboard_trips=1 dashboard_turn_card=false
    dashboard_end_route_presses=1`.
- **Players in the emulator.** The image does list players, without any help:
  `media_players_listed=2 media_players_browsers=2`. Those are its two exported browser
  services. There is no notification access, so `dashboard_media_access=false` and the
  hint shows.
- **Resume.** Pressing the first row went to `MediaPlayers.resume`
  (`media_resume_presses=1`). The service refused the recent-media hint
  (`media_resume_refused=1`, `dashboard_resume_failures=1`), and the row said so.

Second round, after the two fixes the first round found (§2.3), on the rebuilt APK
(`tools/android-build.sh` exit 0, `DashboardTripTest` 466 checks):

| Run | Result |
| --- | --- |
| `LAB_ROUTING=… LAB_MAP=… LAB_VIDEO_CONFIG=1920x1080 guidance` (night), `emulator-lab/20260924T151759Z` | **pass**, `receiver_checks=23 receiver_check_failures=0`. Cluster states `1,3,1,3,1,2,0`, maneuvers `6,8,5,7,5,36,39`, named steps 47/48. `guidance_arrivals=1 guidance_arrival_ends=0` (the receiver stops 2 s after arrival, inside the 30 s hold) |
| `LAB_NIGHT=day LAB_ROUTING=… LAB_MAP=… guidance` (day, 800x480), `emulator-lab/20260924T152323Z` | **pass**, `receiver_checks=23 receiver_check_failures=0` |
| `LAB_NIGHT=day LAB_ROUTING=… LAB_MAP=… endroute` (day, 800x480), `emulator-lab/20260924T152949Z` | **pass**, `receiver_checks=16 receiver_check_failures=0`; `receiver_endroute=states=1,2,0 tile_changed=0.459 card_changed=0.943` |
| `LAB_MAP=… LAB_VIDEO_CONFIG=1920x1080 track`, `emulator-lab/20260924T153037Z` | **pass**, `receiver_checks=12`; vehicle events 135, camera moves 124, rasterised 302 |
| `LAB_MAP=… LAB_VIDEO_CONFIG=1920x1080 controls`, `emulator-lab/20260924T153138Z` | **pass**, `receiver_checks=14` |

The guidance runs are the existing P6.5 checks, unchanged. They pass with the turn text
gone from the header, the card over the map and the marker moved under it. The track
script's check that the tile row does not move still holds, because there is no route in
that script.

### 2.3 Frames looked at

| Frame | What it shows |
| --- | --- |
| `emulator-lab/20260924T151424Z/frames/008.png` | **Night, 1080p, trip active (End route before).** Card: blue slight-right arrow, "600 ft", "Moreland Avenue"; the route line; the marker (blue dot) half a card below centre; bubbles clear. Trip panel: "arrive 15:23" (the truncating build; now rounded), "9 min", "2.5 mi", right-turn "0.2 mi", sharp-left "0.4 mi", End route. Media pane idle: two player rows with Resume, the access hint along the bottom. Flex tile unchanged. |
| `emulator-lab/20260924T151424Z/frames/012.png` | **After End route.** No card, no route line, marker back at the centre; the favourites tile back, reading "No favourites yet / Add them on the phone" centred. |
| `emulator-lab/20260924T151424Z/frames/015.png` | **Resume refused**: the first row's button reads "Couldn't resu…" in the warning colour. The ellipsis was the defect fixed after this run (§1.3). |
| `emulator-lab/20260924T151759Z/frames/040.png` | **Night, 1080p, driving at 40 mph**: arrow marker half a card low, left-turn card "850 ft / Pleasant Street", panel "15:26 · 7 min · 2.2 mi", later "0.2 mi" and "2.2 mi". |
| `emulator-lab/20260924T151759Z/frames/073.png` | **Night, "Rerouting…"** on the card in the warning colour, the panel's figures dashed. |
| `emulator-lab/20260924T151759Z/frames/290.png` | **Night, arrived**: the pin and "You have arrived" on the card, "Arrived" on the panel with End route. |
| `emulator-lab/20260924T152323Z/frames/045.png` | **Day, 800x480, guiding**: card "500 ft / Pleasant Street" legible at 82 px; panel "15:32 · 7 min · 2.2 mi", later "900 ft" and "2.1 mi"; End route 93 px tall; player rows and the hint. |
| `emulator-lab/20260924T152323Z/frames/071.png` | **Day, 800x480, "Rerouting…"**: the card's warning line and the panel's "rerouting…" with dashes. |
| `emulator-lab/20260924T152949Z/frames/005.png` | **Day, 800x480, End route before**: the arrival reads 15:39 beside "9 min" at 15:30, the rounding fix. |
| `emulator-lab/20260924T152949Z/frames/009.png` | **Day, 800x480, End route after**: no card or line, and the empty favourites' two lines centred and uncut at 13 px. |
| `emulator-lab/20260924T152949Z/frames/012.png` | **Day, 800x480, Resume refused**: "Couldn't resume" in full, scaled to fit. |

## 3. Not verified, and not true yet

### 3.1 End route does not release navigation focus — needs a session change

The plan (§5.4) says End route "releases navigation focus, and clears the cluster". The
second half is true, and the first is not.

`Navigation.clear()` → `Guide.clear()` → `submitGuidance(GUIDANCE_IDLE)`. In
`core/aap-session/src/projection.rs` `route_guidance`, `Idle` while guiding sends
`NavigationState(INACTIVE)` and deliberately keeps the cluster. P6.5 decided that so the
next destination needs no new handshake. Focus is handed back (`NavigationFocusRequest(
NAV_FOCUS_NATIVE)`) only in `stop_navigation`, at session end. That also sets `stopped`,
after which nothing more is sent this session. The lab run shows `ACTIVE:1,INACTIVE:1`
and no focus release until teardown.

To make End route hand the cluster back, the change needs files this slice does not own:

1. **`core/aap-session/src/navigation.rs`**: a `RouteState::Ended`, distinct from `Idle`.
2. **`core/aap-jni/src/lib.rs`** and **`NativeBridge.kt`**: a `GUIDANCE_ENDED = 4` state
   through `nativeSubmitGuidance`.
3. **`projection.rs` `route_guidance`**: on `Ended` while focus is `Projected`, send
   `INACTIVE`, then `NavigationFocusRequest(NAV_FOCUS_NATIVE)`. Set the focus state back
   to `Idle`, **not** `stopped`. Then the next `Guiding` re-requests focus through
   `request_navigation_focus` (with the cluster already announced), as at session start.
4. A route-mode test in `tests/projection.rs`: guide, end, the focus request goes out,
   guide again, focus is re-requested and the next maneuver waits for `PROJECTED`.

Once that exists, `Guide.clear()` would publish `ENDED` where it publishes `IDLE` today.
It is a one-line change in a file this slice owns. Whether handing Ford's cluster back
between routes is *wanted* is itself a question: P6.5 kept focus on purpose, and Ford
never announces a cluster, so the force box is on. It is flagged rather than decided.

### 3.2 Needs the Pixel or the truck

- **The card and the panel on SYNC 4**: legibility at arm's length, whether 0.4 of a
  184 px card is big enough, and the icons against Ford's own cluster icons for the same
  value. That is P6 plan §10's question, now answerable on both screens at once.
- **End route pressed on the truck** (G11 asks for it), and the cluster clearing there.
- **The 30 s arrival hold**: not lab-run, because the guidance receiver stops 2 s after
  arrival. It is also not host-testable, since `Guide` needs a looper. It is a
  `postDelayed` guarded by the route's generation.
- **Resume on the Pixel**: a live session's `play()`, a cold resume, and the pane turning
  into now-playing after a success. The emulator's two services only refused.
- **Arrival time on a real drive**: BRouter's time model against real traffic.
- **The notification sentence on the Pixel**: the lab runs without a foreground
  notification (the lab socket has no connected device).

## 4. Needed from files this slice does not own

- **Session (Rust, JNI)**: §3.1, if focus release on End route is wanted.
- **`RouteClock.kt`** (phone agent): switch Home's route line to `Guide.remaining()` (§1.7).
- **`docs/p7-plan.md`** (coordinator): §5.5's "the row targets are the media buttons'
  height" to read "two rows filling the pane (95% of a media button, 84% with the hint)".
  §5.4 should gain the arrival hold (§1.2) and the focus question (§3.1).

## 5. Amendments this proposes — proposed, not applied

- **spec §8.2** (dashboard):
  - The turn card over the map pane, with the marker half a card lower while it shows.
  - The trip panel in the favourites tile while a route exists.
  - End route.
  - The route ending 30 s after arrival.
  - The idle media pane's two player rows.
  - The header no longer carries the turn.
- **spec §10** (reports): `dashboard_trip*`, `dashboard_turn_card`,
  `dashboard_end_route_presses`, `dashboard_media_*`, `dashboard_player_rows*`,
  `dashboard_resume_*` and `guidance_arrival_ends`. Counts and booleans only, never a
  destination, a street, an arrival time or an app.
- **plan.md P7 rows**: P7.4 "implemented, host- and lab-verified; End route does not yet
  release focus (§3.1); truck unverified (G11)". P7.5 "implemented, host- and
  lab-verified; Resume unverified on the Pixel".

## 6. For dashboard part 2b

- **The flex tile** is `DashboardLayout.tiles[DashboardLayout.FLEX_TILE]`. Replace
  `DashboardDrawing.drawFlexTile(canvas, paint, tile, palette)`. The tile's background and
  its label strip (`drawTileLabel`) are drawn before it. A trip never touches it.
- **Touch**: follow End route's pattern in `Dashboard.touch`:
  1. A `pressed…` field set on `down`.
  2. Compared on `up`, and cleared on `up` and `cancel`.
  3. A `take…()` the service polls after `pipeline.touch`, next to `takeEndRoute` and
     `takeResumePick`.
- **The keypad over the right half** will cover the media pane and both tiles:
  - While it is up, `mediaIdle` player rows, End route and the favourites must not take
    presses.
  - The simplest route is an early branch at the top of `touch` that claims the gesture,
    as `mapGesture` does.
  - `drawMedia`/`drawTiles` are separate functions, so an overlay drawn last in `draw()`
    (before the map punch, or after it if it may cover the map) needs no change to them.
- **Snapshot**: add fields with defaults at the end, as this slice did, so the Java tests
  keep compiling.
- **Palette**: `DashboardPalette.of(snapshot.night)`, roles only. `warning` is the one
  "bad" colour; there is no red or green role. Answer and Decline will need either new
  roles (both palettes, with contrast tests) or text.
- **Receiver**: `DashboardPane` now has `tripTile`, `turnCard`, `media`, `endRoute` and
  `resumeRow`. The flex tile's box is `tripTile` offset by `tileWidth + margin`.
