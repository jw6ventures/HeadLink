# P7.6 (map half) — day and night map themes: implementation record — 24 September 2026

The map part of slice P7.6 in the [P7 plan](p7-plan.md) §5.6, with §1 item 16 and §9
item 7: a day map theme, `MapEngine.setNight` switching between the two whenever the head
unit's `is_night` changes, and a switch that does not stutter. The dashboard chrome's
palette and the ☀/☾ toggle are the other half of P7.6 and are not in this record.

Status: **implemented, host-tested, and lab-verified on the emulator** against the lab
receiver, which now sends NIGHT_DATA `is_night=false` and flips it mid-session. **Not seen
on the vehicle.** No recorded Ford session has sent a second NIGHT_DATA, because every one
was with the truck's display pinned to dark (§1 item 16), so Automatic is proven only on
the lab receiver.

---

## 1. What was built

### 1.1 `dashboard-light.xml`

`app/src/main/resources/assets/oaa/dashboard-light.xml`: an original day theme against the
VTM rendertheme schema, like `dashboard-dark.xml`. No VTM theme is vendored or modified.

- **Ground** `#e9e7e1`, a warm light grey rather than white: the pane sits beside dark
  chrome in a cabin, and white would be the brightest thing in it.
- **Carriageways** white with a mid-grey casing; **arterials** warm yellow `#f7d57e`;
  **motorway/trunk** red, **toll** darker red, as at night, so either theme reads the same.
- **Labels** near-black on white haloes, with the dark theme's sizes and priorities
  unchanged, since size decides which street names survive at all.
- **Rule coverage is the dark theme's, exactly**: the same match tree, instructions and
  order; only colours differ. That includes the `tl_yes|yes|true` toll match and its exact
  complement (the Mapsforge writer stores `toll=yes` as `tl_yes`).
- **Nothing uses the route cyan** `#22C7F0`, which `tools/route-line-pixels.py` counts by
  value.

### 1.2 `MapEngine`

- **`setNight(night)` switches every time the value changes.** The per-frame no-change
  path is one integer compare (`appliedMode`, an `Int`, so nothing is boxed). The
  `layered` guard is kept and runs first on every change, so a map with no data is still
  never themed. The long comments that said Ford "sends true once and never updates it"
  now say what really happened: the display was pinned to dark (§1 item 16).
- **Each theme is parsed once and kept** (`ThemeLoader.load` then
  `Map.setTheme(IRenderTheme)`, which is all `setTheme(ThemeFile)` does). The **NEWTRON
  fallback is per theme**: a day file that will not parse costs the day map only.
- **The switch holds the old picture until the new one is loaded.** A theme change clears
  every tile (`Map.setTheme` → `clearMap`), and VTM draws a tile with no data as bare
  ground. While a switch waits, `drawFrame` still runs VTM's per-frame update, which fires
  the clear and queues the tiles, but rasterises nothing, so the texture keeps the last
  finished picture. It watches the visible tile set (`TileManager.getActiveTiles`) until
  every tile is `NEW_DATA` or `READY`, then draws the new theme in enough passes to upload
  all of it (VTM uploads 8 tiles a pass). The hold is capped at 1.5 s. While held, a tile a
  failed read dropped back to `NONE` gets a position event every 250 ms so it is queued
  again. A parked vehicle makes no position changes, so without that the tile would wait
  for the cap.
- **Day route casing.** On the day ground the cyan route line is only about 2:1 against
  white roads and grey land. A second `PathLayer` under it, a deep teal `#0B4F6C` 4 px
  wider, is enabled with the day theme only. At night the picture is unchanged, and the
  route colour is unchanged in both.
- **Clear colour fixed (a pre-existing defect).** See §4.1.
- **Report**, a new line: `map_theme_switches`, `map_theme_call_ms_max`,
  `map_theme_parse_ms_max`, `map_theme_held_frames`, `map_theme_hold_ms_last`/`_max`,
  `map_theme_hold_timeouts`, `map_theme_hold_requeues`, `map_loader_reopened`,
  `map_loader_reopen_failed`. It records counts and times only, never a position.

### 1.3 `MapLoaderInterrupts` (new)

`app/src/main/kotlin/org/openautoandroid/source/MapLoaderInterrupts.kt` wraps the tile
source, outside `MapReadGuard`. See §4.2 for why it exists. Around each loader query it
does two things. It clears a stale interrupt before the query. After a query that ended
interrupted, it opens a fresh data source, with a new file channel, before that loader's
next tile.

### 1.4 Lab

- **Receiver** (`tools/lab-receiver/receiver.cpp`):
  - `--night night|day|flip:<seconds>`. The default is `night`, which is byte-for-byte
    what it sent before, so existing scripts are unchanged. The option needs
    `--sensors all`.
  - `LabSensorHandler::sendNight(bool)` sends a NIGHT_DATA event on its own, as a head
    unit does at dusk.
  - `--script daynight` parks the vehicle and starts in day. Once the map pane has
    settled, it flips to night, then day, then night. It watches the pane in **every
    decoded picture** across each flip. It records when the new theme first appeared,
    when it settled, and how each picture compares with the old picture and the settled
    one: old, new, between (small differences), half-drawn (>25 % from both), or blank
    (luma spread < 4). It also records the longest gap between pictures.
  - Checks per flip: it settled; >50 % of the pane changed; the pane's mean luma moved
    >40 levels the right way; no blank or half-drawn picture; no gap ≥ 1 s. It also
    checks that the second day and second night each differ from the first by < 5 %, so
    no tile was left in the wrong theme.
  - Each in-between picture is printed (`receiver_daynight_picture=`), so a failure says
    what it saw.
- **`tools/emulator-lab.sh`**:
  - The `daynight` script (needs `LAB_MAP`) checks the app side: ≥ 3 switches, no hold
    timeout, the run ends on `theme=oaa-dark`, and no `vtm-fallback`.
  - `LAB_NIGHT=day|night|flip:<s>` works under any other script; unset leaves the
    receiver's default.
- **Host test** `tools/jni-host-test/MapThemeTest.java` (203 checks):
  - The two themes' rule trees match in shape and order.
  - Every `use=` resolves, and both themes define the same style ids.
  - Every toll rule that lists `yes` also lists `tl_yes`.
  - Neither file contains the route cyan.
  - The day ground is light but below paper white; the night ground is dark.
  - Every label has ≥ 4.5:1 contrast against the ground and against its halo, in both
    themes.
  - The route alone is weak on the day ground (< 3:1), and the day casing is ≥ 3:1
    against the ground, a white road and the line itself.

## 2. Verification

All commands ran under `flock ~/.cache/openauto-android/p7-build.lock`.

- `tools/android-build.sh`: exit 0. Lint, the three `verify-apk.sh` passes, and all host
  tests passed, including `jni-host-test: ok 203 map theme checks`.
- `tools/lab-receiver.sh build`: exit 0.
- `LAB_MAP=…/massachusetts.map LAB_VIDEO_CONFIG=1920x1080 tools/emulator-lab.sh daynight`,
  final run `emulator-lab/20260924T052852Z`: **pass**, `receiver_checks=29
  receiver_check_failures=0`.
- `… tools/emulator-lab.sh track` with the default night, `emulator-lab/20260924T052752Z`:
  **pass**. This is the regression check for existing scripts (see §3.2 for what it also
  showed).
- `LAB_NIGHT=day LAB_DESTINATION=1 LAB_ROUTING=~/.cache/openauto-android/rd5 … track`,
  `emulator-lab/20260924T051634Z`: **pass**, including `route-line: pass` (route cyan
  1 px at most in the first 3 frames, at least 10,243 px in the last 3) and
  `theme=oaa-light`. The run used the build before §1.3; the theme and drawing code were
  the same.

### 2.1 Frames looked at

| Frame | What it shows |
| --- | --- |
| `~/.cache/openauto-android/emulator-lab/20260924T052852Z/frames/003.png` | **Day.** Light ground, white roads with grey edges, yellow arterial, green parks, blue vehicle dot clearly visible, street names legible. |
| `~/.cache/openauto-android/emulator-lab/20260924T052852Z/frames/005.png` | **Night**, the same place two seconds later. The navy ground is now actually navy (§4.1). |
| `~/.cache/openauto-android/emulator-lab/20260924T051634Z/frames/026.png` | **Day, with a route and a moving vehicle.** The cyan route with its teal casing stands out from white roads and grey land; the blue arrow and a water label (`#1f4a6b`) are legible. |

The header and tiles still follow today's palette logic, which reads night from the
restriction string (`night=false`/`true` in the debug line). That is expected: the chrome
palette and the ☀/☾ toggle are the other half of P7.6.

## 3. The switch cost, measured (emulator, 1920x1080)

### 3.1 A switch while the loaders are idle (`daynight`, three flips per run, two runs)

| | Flip 1 → night | Flip 2 → day | Flip 3 → night |
| --- | --- | --- | --- |
| Flag sent → first new-theme picture | 589 ms | 981 ms | 439 ms |
| → settled, labels included | 717 ms | 1103 ms | 641 ms |
| Half-drawn / blank pictures | 0 / 0 | 0 / 0 | 0 / 0 |
| Longest gap between pictures (median 34 ms) | 55 ms | 242 ms | 163 ms |

- **App side, same run:**
  - Hold (tiles loading, old picture kept): 329 ms last, 583 ms worst.
  - Scene-thread time in the switching call: **100 ms** worst.
  - Theme parse: 14 ms, once per theme.
  - No hold timeout.
- **The earlier passing run (`20260924T051415Z`) agreed:**
  - Holds 312–394 ms.
  - Call ≤ 106 ms.
  - Picture gaps 103–173 ms.
- **The flag-to-picture latency includes the lab's own path.** That is receiver →
  session → app event → the next scene frame, then encode and decode. The first new
  picture follows the end of the hold.
- **What the pictures between old and settled are.** In every flip, 6–9 pictures were
  neither the old picture nor the settled one. The printed per-picture lines show all of
  them within 7–24 % of the settled picture, never far from both. Their spread rises and
  their difference falls frame by frame over about 250 ms. That is the new theme arriving
  whole, then the H.264 stream sharpening after a full-frame change, plus the labels,
  which VTM places a few frames after the tiles. No picture was blank or half-drawn.
- **The ~100 ms call is VTM's.** `Map.setTheme` pauses the tile loaders, and
  `PausableThread.awaitPausing` polls with `wait(100)`, so even idle loaders cost about
  one poll. It is a stall of about three frames at 30 fps on the scene thread. It is the
  only stutter left, and it is visible as the 163–242 ms picture gaps. It happens only
  when the flag changes: at most a few times a drive, plus presses of the future ☀/☾
  toggle.
- **Repeat checks.**
  - The second day picture differed from the first by 0.004 %, and the second night
    from the first by 0 %.
  - No tile stayed in the wrong theme or empty after two switches.

### 3.2 A switch while the loaders are busy (default `track`)

The dashboard reads night as `false` until the first NIGHT_DATA arrives. So on a session
that starts in night, the map is themed day at start and switches to night a moment
later, while the first tiles are still loading.

- **Before §1.3:**
  - The call took 238–407 ms.
  - The hold hit its 1.5 s cap.
  - One loader's file channel was closed for the rest of the session: 108 failed reads
    (§4.2).
- **After (`20260924T052752Z`):**
  - The call took 113 ms and the hold 977 ms, with no timeout.
  - One interrupted read; `map_loader_reopened=1`, and nothing failed afterwards.

This start-of-session switch is avoidable, and the fix is not in a file this slice owns
(§6).

## 4. What the runs caught

### 4.1 Every map frame since P5.5 has had a black ground

The first `daynight` frame showed the day theme's roads, buildings and parks on **black**.
The night frames before it had also been black, not navy: the same pixel read (0,0,0) in
the P7 review's own reference frame, `emulator-lab/20260923T181846Z/frames/200.png`.

**The cause:**

1. VTM's `MapRenderer.draw` sets the clear colour through `GLState.setClearColor`, which
   skips the GL call when its cache already holds the colour.
2. `DashboardRenderer.draw` clears the window with a raw `glClearColor(0,0,0,1)` between
   map frames.
3. So from the second frame on, VTM's `glClear` painted the ground black whatever the
   theme said.

At night that was nearly invisible (`#151a2b` against black). **Fix:** `MapEngine.drawFrame`
sets the clear colour itself from the applied theme's `map-background` (or the no-data
grey) every rasterised frame. As a result, the night map's ground is now the navy the
dark theme always specified. It is slightly lighter than every earlier frame; the
route-line and track checks still pass.

### 4.2 VTM's loader pause closes map files

`PausableThread.pause()` wakes the loader with `Thread.interrupt()`, and an interrupt
during a `FileChannel` read closes that channel for good (`ClosedByInterruptException`,
then `ClosedChannelException` on every later read). Each loader owns one `MapFile` and its
channel, so a switch that lands mid-read breaks that loader for the session.

Before P7.6 the theme was set once, before any read, so this could not happen. With
day/night it happens on any switch while tiles are loading: at session start (§3.2), and
on the road at dusk. `MapLoaderInterrupts` (§1.3) fixes it without changing VTM. The
first version renewed the source straight after the interrupted query, on the
still-interrupted thread. Opening the new channel failed the same way, and it then kept
querying the disposed source (NPEs in the log). It now renews at the start of the next
query, after the flag is cleared.

## 5. Not verified, and needs the vehicle or the Pixel

- **Ford sending a second NIGHT_DATA mid-session.** This needs one drive with the display
  **not** pinned to dark, with the headlamps toggled or across dusk (plan §5.6). Until
  then Automatic is proven on the lab receiver only.
- **The switch cost on the Pixel** (release build): the hold, the ~100 ms call and the
  picture gaps are emulator numbers. Plan §5.6 asks for it to be timed on the Pixel before
  it ships.
- **The day theme in sunlight on the SYNC 4 panel.** Glare, legibility of the white roads
  against the light ground, and whether the route casing is enough are judgements for the
  real screen, not a lab frame.
- **A switch while driving** was not run in the lab. The track script's static-tile check
  would trip on the chrome palette changing at the same moment. The busy-loader case was
  exercised only at session start (§3.2).

## 6. Needed from files this slice does not own

- **`Dashboard.kt`** (the later P7.6 agent): `night` is `false` until the first NIGHT_DATA,
  so a session that starts at night themes the map day and switches within a second, while
  the first tiles load (§3.2). This is now safe, but it costs a reload and a stall at every
  start. Proposed: keep "no signal yet" distinct and treat it as night, which is what the
  map effectively did before P7.6, or as the saved Day/Night setting once that exists.
- **`docs/licenses.md`**: add a row for `app/src/main/resources/assets/oaa/dashboard-light.xml`
  worded as the `dashboard-dark.xml` row (original work, GPL-3.0-only, no VTM theme copied
  or modified).
- **`tools/jni-host-test.sh`**: `MapThemeTest` was registered there, one run line and two
  header lines, following the coordination rule to register new host tests. The shared
  file was re-read before a targeted edit.

## 7. Amendments this proposes — proposed, not applied

- **§9 item 7 / the P5.2 night-mode finding.** Reword "SYNC 4 sends `is_night=true` once
  per session and never updates" to: *every recorded session had the truck's display
  pinned to dark, so `is_night=true` was the operator's setting; whether SYNC 4.0 sends a
  second NIGHT_DATA when the display follows its light sensor is unrecorded.*
  `MapEngine.setNight`'s rationale now says this. The memory note "Ford night mode always
  true" needs the same correction.
- **spec §8.2** (dashboard): the map follows day/night with two owned themes. The day theme
  draws the route line with a dark casing.
- **P5.5 record**: the ground colour in its frames was black, not the theme's navy (§4.1).
