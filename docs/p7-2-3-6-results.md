# P7.2, P7.3 and P7.6 (chrome) — zoom buttons, marker colour, the header, day/night: implementation record — 24 September 2026

Dashboard part 1 of the [P7 plan](p7-plan.md): §4.1 (zoom buttons) and §4.2 (marker
colour), which make P7.2; §5.1–5.2 (the header), which make P7.3; the chrome half of §5.6
(day/night palette and the ☀/☾ toggle); and the session wiring for the wave-1 backends
([p7-backends-results](p7-backends-results.md) §7). The map half of P7.6 is
[p7-6-map-results](p7-6-map-results.md).

Status: **implemented, host-tested and lab-verified on the emulator.** Nothing has run on
the Pixel or the truck. Dashboard part 2 (turn card, trip panel, media players pane, flex
tile, call card, keypad) is not built here; §5 lists the hooks it builds on.

---

## 1. What was built

### 1.1 Zoom buttons (P7.2, plan §4.1)

- **`DashboardLayout.zoomBubble(index)` / `hitZoomBubble`**, with `ZOOM_ACTIONS =
  ["zoom-in", "zoom-out"]`: a vertical pair, + above −, at the map pane's bottom-right.
  They are `bubbleSize`, a margin apart, and a margin above the ODbL credit.
- The credit's size and top are now laid out (`attributionTextSize`, `attributionTop`),
  so the stack and the plate cannot disagree. The drawing reads both from the layout.
- **`MapControl.zoomBy(levels)`**: ×2 or ÷2 per press, clamped to `MIN_SCALE..MAX_SCALE`,
  about the pane centre. It leaves `following` alone: while following, the vehicle stays
  under the marker, as it does for a pinch.
- Press and release on the same bubble, like every other control. A finger that lands on
  a zoom bubble is never a map gesture, so sliding off one does not pan.
- The plates, rims and pressed colour are the left stack's, through one `drawBubble`.
- Report: `dashboard_zoom_presses=N` beside `dashboard_map_presses`, which counts only
  recentre and compass, as before.

### 1.2 Marker colour (P7.2, plan §4.2)

- The dot and the arrow take `MarkerColour.fill` and `.rim`. The dot now has a rim too;
  without one, a white or yellow dot on the day map has no edge.
- The colour is read from `AppSettings` when projection starts and carried in the
  `Snapshot` (`markerColour`), like `speedUnit`, so a different colour is a chrome change.
- Report: `dashboard_marker=<key>`.

### 1.3 The header (P7.3, plan §5.1–5.2)

Left to right: clock, speed, compass needle, compass point and bearing (kept, as James
asked), then the turn text in whatever room is left, as before. Right to left: battery,
signal, ☀/☾, Ⓟ.

- **Layout** (`DashboardLayout`):
  - `headerBattery`: 1.3 × header wide, a margin from the right edge.
  - `headerSignal`: 1.1 × header wide, left of the battery.
  - `headerIcon(index)`: two header-height squares, a margin apart, left of the signal,
    in `HEADER_ACTIONS = ["pause", "day-night"]` order. `hitHeaderIcon(px, py)` hits
    them.
  - `headerTextRight`: where the left-hand text must stop.

  At 800x480 the header is 53 px tall. Each icon is then a 53 px square, and the right
  side takes 250 px of 726.
- **Battery**: a glyph with a terminal nub that fills with the level, and a bolt over it
  while charging. The bolt is in the accent colour with a header-coloured edge, so it
  shows over a full glyph and an empty one alike. Then the percentage. The glyph and text
  take the warning colour at ≤ 15% when not charging, as before. An unread level is an
  empty glyph with `—%`.
- **Signal**: four bars for the primary SIM, lit to `signalBars`; unlit bars are faint,
  so the four always read as a scale. Beside them goes `wifi` when `network == "wifi"`,
  else `cellNetworkType`, or `—` with no reading. Under the bars there is one row of four
  dots per secondary SIM (at most `SimSignal.MAX_SECONDARY`): filled to its level, faint
  above it, and hollow rings for -1.
- **Ⓟ**: a thin grey ring and "P" while notifications are allowed; a bold accent ring and
  bold "P" while paused.
- **☀/☾**: a drawn sun or crescent (not a font glyph) for the palette actually in use.
  A small accent "A" is added while the mode is Automatic.
- **Header taps**: press and release on the same icon, with the pressed icon lit.
  - Ⓟ toggles `notificationsPaused`.
  - ☀/☾ moves to `DayNightMode.next()`.
  - Both hand the new value to `DashboardSaves` (§1.5).
- **Diagnostics line**: drawn only when `AppSettings.diagnostics` is on. It holds `gear`,
  the head unit's own `driving=`/`night=` words, `sats n/m`, `scale inferred`, and
  `src vehicle|phone|none`. `src` used to sit at the bottom right of the header.
  - "scale inferred" was removed from the header in P6.7 at James's request. It is back
    only here, from `Dashboard.scaleInferred(vehicle)`: a validated bearing or speed whose
    divisor is not in its `ScaleRule.confirmed`. Ford's 1e6 bearing is confirmed; the
    lab's 1e3 is not.
  - With the line off, the one text line moves to the vertical middle of the header.
- **The turn text stays in the header**, fitted to `headerTextRight`. Part 2 moves it to
  the turn card.

### 1.4 Day/night chrome (P7.6, plan §5.6)

- **`DashboardPalette`** (new, pure Kotlin) replaces the palette that was nested in
  `DashboardDrawing`. It has the same fourteen roles.
  - **`DAY` is new and light.** Tiles `#F8F9FA` over a cool grey background `#D5DAE0`,
    header `#E6E9ED`, text `#14181C`, dim `#505B66`. The accent `#1D64C0` and warning
    `#A35300` are darker than at night, so they hold 4.5:1 on light grounds. Nothing is
    paper-white next to the day map's warm `#e9e7e1` ground.
  - **`NIGHT` is the pre-P7.6 `DAY` values, as the plan says**, with one exception: it
    takes the old `NIGHT`'s dark attribution plate and light credit text. The old day
    set's near-white plate is the brightest thing on a night map.
  - **Why the old `NIGHT` was not kept.** It is what the truck has actually shown on
    every drive (the display is pinned dark, so `is_night` was always true). But its
    secondary text is about 3.6:1 on the tiles, below what a glance at arm's length
    needs, and the review found nothing too bright in the frames it read. The marker's
    default `BLUE` is the pre-P7.6 `DAY` accent (`#4E9AF1`), which is now the night
    accent, so the default marker is unchanged at night.
- **Resolution.** `Dashboard.resolveNight(mode, headUnit, remembered)` is
  `mode.resolve(headUnit ?: remembered ?: true)`:
  - Day and Night hold.
  - Automatic follows the head unit's `is_night` on **every** change.
  - Before the first NIGHT_DATA, Automatic uses the palette the last session ended in,
    else night.
  - `Dashboard.restriction` treats only `true`/`false` as a night signal. A `-` (a
    driving status arriving on its own) is not day. It keeps the raw words in the
    restriction string for the diagnostics line.
- **The one value drives both.** `Dashboard.isNight()` (which `ProjectionPipeline` passes
  to `MapEngine.start` and `setNight`) and `Snapshot.night` (which picks the chrome
  palette) both come from `resolveNight`. The chrome no longer reads `night=true` out of
  the restriction string.
- **No day-then-night flash at the start of a session.**
  - `ProjectionService.startProjection` now hands the held restriction to the pipeline
    *before* `pipeline.start()`. NIGHT_DATA normally arrives before video start, so the
    map's first theme is the right one.
  - Before any NIGHT_DATA the fallback is night, or the remembered palette, never day.
  - Together these fix the start-of-session reload in
    [p7-6-map-results](p7-6-map-results.md) §3.2 and §6.
- **Remembered palette**: `AppSettings.lastNight` / `saveLastNight`, key
  `last_resolved_night` in the same preferences file. It is deliberately not a
  `SavedSettings` field: it is something observed, not a choice, and it stays out of the
  stored form that `AppSettingsTest` pins at 31 keys. The dashboard records it at session
  start (if not yet recorded) and on every palette change.
- **Report**, one new line:
  - `dashboard_day_night` (the mode)
  - `dashboard_night` (the palette drawn)
  - `dashboard_head_unit_night` (the head unit's raw word, `-` before any)
  - `dashboard_remembered_night`
  - `dashboard_night_events`, `dashboard_night_changes`
  - `dashboard_day_night_presses`

  The restriction string is unchanged, and the native report still carries the head
  unit's own value.

### 1.5 Saving the choices made on the dashboard

`DashboardSaves(dayNight, notificationsPaused, resolvedNight)` is three callbacks. They are
called under the dashboard lock, on the thread that delivered the touch or event (main),
the same way `onMapOrientation` is. `ProjectionService.dashboardSaves()` posts each to main,
so the write happens after the lock is released:

- **☀/☾**: `AppSettings.update { it.copy(dayNight = mode) }`, which changes one field.
- **Ⓟ**: `NotificationFeed.paused = paused`, then
  `AppSettings.update { it.copy(notificationsPaused = paused) }`.
- **Palette in use**: `AppSettings.saveLastNight`.

`AppSettings.update` is an in-memory read plus `apply()`, like
`MapPreferences.saveOrientation`.

`DashboardChoices(markerColour, dayNight, diagnostics, notificationsPaused,
rememberedNight)` is read from `AppSettings` in `startProjection`. It is read even on a lab
attempt: those are display choices, and the lab intent still builds its own
`AttemptSettings` and never reads or writes the saved attempt settings.

### 1.6 Session wiring for the wave-1 backends

In `ProjectionService`, dashboard scene only, next to `PhoneStatus`:

- **`MediaPlayers`** is created and started with the projection and released (on the
  control thread) in `stopProjection`. Its list goes to `Dashboard.players()` and is
  carried in `Snapshot.players`. It is not drawn yet; `dashboard_players=N` counts it.
- **`NotificationFeed`**:
  - At projection start, `exclusions`, `paused` and `enabled` are set from
    `AppSettings`, in that order, so nothing is read before the exclusions apply.
  - `enabled = false` in `stopProjection` and in `onDestroy`'s emergency path, so the
    listener reads nothing between drives.
- **`CallControl`**:
  - `setEnabled(this, true)` at projection start, only with `callControls` on. The chosen
    route is noted in the event log (`call controls on: telecom|notification|off`).
  - `setEnabled(this, false)` in `releaseIfDone` (session end) and `onDestroy`, whenever
    `CallControl.wanted`.
  - This uses the current API (route-returning `setEnabled`, `fullControlsGranted`
    decides the route inside it).
- **Report.** The pipeline section now ends with `PhoneStatus.report()`,
  `MediaPlayers.report()`, `NotificationFeed.report()` and `CallControl.report()`. They
  are captured at projection start and again just before those sources are released.
  - **`PhoneStatus.report()` was not in the report before.** The backends doc said it
    was; nothing called it. It is now.

## 2. Verification

All under `flock ~/.cache/openauto-android/p7-build.lock`.

### 2.1 Build and host tests

`tools/android-build.sh`: **exit 0**. That covers clippy, both APKs, `lintDebug` with
warnings as errors, `verify-apk` on all three APKs, and every host test. The first attempt
was blocked by another slice's work in progress in `ConnectionActivity`/`PhoneViews`, then
by two mistakes in my own test file (a non-ASCII degree sign, and
`MarkerColour.DEFAULT` from Java).

`DashboardTest` is now **587 checks** (all the pre-P7 ones still pass). New cases:

- **Zoom layout**, at 1920x1080 (both map sides), Ford's 726x480 content rectangle and
  800x480:
  - + above −, `bubbleSize`, a margin apart.
  - In the bottom-right quarter, above `attributionTop`.
  - Clear of the left stack.
  - No other target at their centres.
  - The credit's plate, at a generous 0.65 em a character, ends right of the bottom-left
    stack.
- **Zoom behaviour**:
  - One level a press; still following, marker at the centre.
  - Clamped at both ends.
  - Counted in `dashboard_zoom_presses`, not `map_presses`.
  - Nothing happens when sliding from + to −, or on a cancel.
  - Sliding off a zoom bubble does not pan.
- **Header layout** at the same four sizes:
  - Icons, signal and battery are inside the header and each a margin from the next
    without overlap.
  - Icons are header-height squares.
  - The battery ends a margin from the edge.
  - The kept text (clock, speed, needle, bearing, at 0.62 em a character) fits left of
    `headerTextRight`.
  - The hit tests work at the centres and corners and miss in the gaps, on the signal,
    the battery, the clock and below the header.
- **Header taps**:
  - A press lights an icon and changes nothing until the release.
  - Sliding between icons presses neither.
  - Ⓟ toggles, ☀/☾ cycles Automatic → Day → Night → Automatic.
  - The exact sequence handed to `DashboardSaves` is checked.
  - Presses and palette switches are counted, and no tile is tapped.
- **Night resolution**:
  - `resolveNight` over every mode × head unit {null, true, false} × remembered
    {null, true, false}.
  - Through a `Dashboard`: a first session starts at night and records it; `-` is not a
    signal; every change is followed; repeats are not re-saved; the report keeps the mode,
    the palette and the raw word; Day over a night head unit reports both; a remembered
    day starts in day.
- **Choices**:
  - Colour, diagnostics and the pause reach the snapshot.
  - A colour change alone changes the snapshot.
  - The report names the settings.
  - `scaleInferred` is true for the lab's 1e3 and false for Ford's 1e6.
- **Palettes**:
  - Text is ≥ 7:1 on tile, header, button and background.
  - Dim, accent and warning are ≥ 4.5:1 on tile and header.
  - The credit is ≥ 7:1 on its plate.
  - Day is light and night is dark.

### 2.2 Emulator lab

The new **`controls` script** (`tools/emulator-lab.sh controls`, receiver `--script
controls`) presses, on the projected picture: +, +, −, Ⓟ, ☀/☾. Each press is made on a
settled map, and the receiver checks the picture it should change:

- **Zoom**: ≥ 10% of the map pane.
- **Ⓟ**: ≥ 2% of the pause icon's box.
- **☀/☾**: ≥ 50% of the header's text side, and ≥ 40 luma lighter.

The app side then checks the report:

- `dashboard_zoom_presses=3`, `zoom=17.0`, `following=true`.
- The pause pressed once and on (`dashboard_notifications_paused=true`,
  `notifications_paused=true`).
- `dashboard_day_night=day dashboard_night=false dashboard_head_unit_night=true`.
- The saved settings file holds `day_night=day`, `notifications_paused=true` and
  `last_resolved_night=false`.

`LAB_MARKER=<colour>` and `LAB_DIAGNOSTICS=1` **seed the app's settings file with adb
before the app first starts**, the way `LAB_MAP` installs a map (root `cp` into
`shared_prefs/`, chown/chmod/restorecon). This is how a lab frame shows a non-default
marker. No lab intent extra was added, and the lab intent still does not read attempt
settings from `AppSettings`.

| Run | Result |
| --- | --- |
| `LAB_MARKER=yellow LAB_DIAGNOSTICS=1 LAB_MAP=…massachusetts.map LAB_VIDEO_CONFIG=1920x1080 … controls`, `emulator-lab/20260924T144621Z` | **pass**, `receiver_checks=14 receiver_check_failures=0` |

`controls` results, per press:

| Press | Changed | Mean luma |
| --- | --- | --- |
| + | 17.8% of the map | |
| + | 17.6% | |
| − | 17.4% | |
| Ⓟ | 13.8% of its box | |
| ☀/☾ | 99.7% of the header | 55 → 206 |

Regression runs of the existing scripts, same build:

| Run | Result |
| --- | --- |
| `LAB_MAP=… LAB_VIDEO_CONFIG=1920x1080 … track` (default night), `emulator-lab/20260924T144819Z` | **pass**, `receiver_checks=12 receiver_check_failures=0`; vehicle events 135, camera moves 123 |
| `LAB_MAP=… LAB_VIDEO_CONFIG=1920x1080 … daynight`, `emulator-lab/20260924T144918Z` | **pass**, `receiver_checks=29 receiver_check_failures=0`; 3 switches, hold ≤ 455 ms, no timeout, ends on `oaa-dark` |
| `LAB_NIGHT=day LAB_MAP=… … track` (800x480, nothing seeded), `emulator-lab/20260924T145119Z` | **pass**, `receiver_checks=12 receiver_check_failures=0`; map `theme=oaa-light` |

**The start-of-session theme switch is gone.** Both track runs report
`map_theme_switches=0`:

- **Night run.** Before this slice the map started day and switched to night at start
  (p7-6-map-results §3.2: a call of 113–407 ms and a hold of about 1 s at every night
  start). Now it starts in `oaa-dark`.
- **Day run.** It starts in `oaa-light`, because the held NIGHT_DATA reaches the
  dashboard before `start()`. Its `dashboard_night_changes=1` is that pre-start
  correction from the night fallback, before any frame was drawn.

In `daynight` the three flips are the only switches, as before. `dashboard_night_changes=4`
there counts the same pre-start correction plus the three flips.

The existing scripts' checks read the report and the map pane, not the header, so gating
the debug line broke none of them (plan §7 asked for this to be confirmed, not assumed).
The `guidance` and `favorites` runs were not repeated. Neither reads the header, and
the favorites tile is unchanged.

### 2.3 Frames looked at

| Frame | What it shows |
| --- | --- |
| `~/.cache/openauto-android/emulator-lab/20260924T144621Z/frames/004.png` | **Night, Automatic.** Dark chrome and the night map; yellow dot with its dark rim; + and − bottom-right above the credit, recentre and compass bottom-left; header: clock, `0 mph`, needle and `N 2°`, thin Ⓟ, crescent with "A", four faint bars and `wifi` (the emulator has no SIM), charging battery glyph at 100%; diagnostics line `gear - driving=0 night=true sats 7/11 scale inferred src vehicle`. |
| `~/.cache/openauto-android/emulator-lab/20260924T144621Z/frames/016.png` | **Day, held by ☀/☾, after all five presses.** Light chrome and the day map at zoom 17 (via 18); bold blue Ⓟ (paused); sun with no "A" (Day); the diagnostics line still says `night=true`, the head unit's own word. |

| `~/.cache/openauto-android/emulator-lab/20260924T145119Z/frames/025.png` | **800x480, day by the head unit, nothing seeded.** Default blue arrow moving east; no diagnostics line, so the one text line is centred; thin Ⓟ, sun with "A"; bars, `wifi` and battery all fit right of the icons; zoom stack clear of the credit and of the bottom-left stack at 44 px bubbles. |

## 3. Not verified: needs the Pixel or the truck

- **Every header item against real data.**
  - Real signal bars and `cellNetworkType` against the phone's own status bar.
  - A second SIM's dot row: one SIM is in hand.
  - The battery glyph discharging and crossing 15%.

  The emulator has no modem, so every lab frame shows `wifi` and faint bars.
- **The icons and zoom bubbles pressed on SYNC 4.** Hit testing is host-tested and was
  pressed through the lab receiver's touch path, which Ford's matches in coordinates
  (content-relative frame pixels). Nobody has pressed them on the truck. At 800x480 the
  icons are 53 px squares and the bubbles 44 px.
- **The light palette in the cab**: glare, legibility and whether the day tiles are
  too bright beside a dark SYNC frame. That is a judgement for the real screen.
- **Automatic following a real dusk** needs the unpinned-display drive the plan already
  asks for (§5.6). Here it is proven only on the lab receiver.
- **Notification pause, call controls and players on the phone.** The wiring runs, but
  with notifications off (the default) and no calls in the lab, only the counters and
  the pause flag were exercised. `dashboard_players=2` in the lab shows `MediaPlayers`
  listing the emulator's browser services.
- **Saved choices across a real reconnect**: the settings file was read back in the
  lab. A second session starting in the remembered palette is host-tested, not
  lab-run, because the lab wipes the emulator every run.

## 4. Needed from files this slice does not own

- **`ConnectionActivity`** (phone UI agent): the Day/night row should be re-read on resume
  (P7.1 already does this), because ☀/☾ now writes it from the dashboard. Ⓟ does the same
  to `notificationsPaused`, if the settings screen shows it.
- **`docs/p7-plan.md`** (coordinator): nothing blocks.

## 5. For dashboard part 2

- **Snapshot fields** it can draw from without new plumbing:
  - `players: List<Player>` (the idle media pane)
  - `notificationsPaused` ("Notifications paused" in the flex tile)
  - `night`/`dayNight`
  - `markerColour`
  - `diagnostics`
- **Layout**:
  - The top of the map pane is still free for the turn card.
  - The zoom stack's top is `zoomBubble(0).y`; the left stack's is `mapBubble(1).y`.
  - `headerTextRight` is where the header text stops. When the turn text leaves the
    header, that room simply goes empty (the plan says that is fine).
- **Touch**: `Dashboard.touch` claims header icons and zoom bubbles on `down` with the
  same pressed/was-pressed pattern as the other buttons. New targets (End route, call
  buttons, keypad keys, notification rows) should follow it, and should be excluded from
  `mapGesture` if they sit over the map.
- **Palette**: `DashboardPalette.of(snapshot.night)`. Draw with the roles, never with
  literal colours, so both sets work. `DashboardPalette.contrast` exists for their tests.
- **Resume** needs `MediaPlayers` itself: it lives in `ProjectionService.Running.players`.
  A press found by the dashboard should come back the way `takeFavoritePick` does, and
  the service calls `players.resume(player) { … }`.
- **Receiver**: `DashboardPane` in `tools/lab-receiver/receiver.cpp` mirrors the layout,
  including the zoom and header-icon centres. Extend it with any new target a lab script
  presses.

## 6. Amendments this proposes — proposed, not applied

- **spec §8.2** (dashboard):
  - The header's contents as §1.3.
  - The zoom stack bottom-right.
  - Day/night resolution as §1.4, including the night fallback before the first
    NIGHT_DATA.
- **spec §10** (reports): the new `dashboard_*` keys. Settings names, counts and the
  head unit's own word only.
- **spec §5.2**: no permission change in these slices.
- **plan.md P7 rows**: P7.2 and P7.3 "implemented, host-tested, lab-verified; truck
  unverified". P7.6 "chrome and map implemented, lab-verified; needs the
  unpinned-display drive".
- **p7-6-map-results §6's first item** is done here (no-signal-yet is night, or the
  remembered palette).
