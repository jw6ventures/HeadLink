# P5.5 — the owned map and the dashboard: implementation record — 19 September 2026

Implements [P5 plan](p5-plan.md) §5, and replaces the projected POC test scene with a
dashboard: an owned map on one half, four tiles on the other, and a status header.

**The map renders, in the emulator lab, from real OpenStreetMap data.** It is not vehicle
evidence and **G8 remains unmet** — no head unit was involved — but the owned map is no
longer a reading of VTM's sources: it has been driven into the encoder surface and
decoded back out of the H.264 stream. See §9 for the run and the four defects it caught.

## 1. The decision that was blocking the slice — answered

The plan's §8 lists one decision as blocking the renderer choice: *"Can VTM be pinned in
a way `verification-metadata.xml` accepts — Maven Central 0.25.0, or a vendored and
checksummed JitPack build of 0.30.0?"*

**Maven Central 0.25.0, and it needed no special handling at all.** `--write-verification-metadata sha256`
added exactly four components and removed none:

| Component | Why |
| --- | --- |
| `org.mapsforge:vtm:0.25.0` | the renderer core |
| `org.mapsforge:vtm-android:0.25.0` | the `AndroidGL` shim, plus the `natives-arm64-v8a` classifier |
| `org.mapsforge:vtm-themes:0.25.0` | the day and night render themes |
| `com.caverock:androidsvg:1.4` | VTM's only transitive dependency |

No vendoring, no JitPack, no manual checksum step. The cost of that choice is being five
releases behind (0.30.0 is current); the benefit is that the dependency posture is
unchanged in kind.

## 2. Measurements

Against the plan's own selection criteria for the spike.

| Question | Measured |
| --- | --- |
| Offline **reproducible** build survives? | **Yes.** Two clean builds (`rm -rf app/build`, full `tools/android-build.sh`) produced byte-identical release APKs, SHA-256 `f70c8e59ab9536149c9f9afa35140fcf5ed97a97bd4f361009c27f90f508ac9d`. |
| Transitive **AndroidX** pull-in? | **Zero.** No `Landroidx/` or `Lcom/google/` class in `classes.dex`. |
| **ABI** count? | **One**, `arm64-v8a`, unchanged. `verify-apk.sh` now pins the native set to exactly `libaap_jni.so` + `libvtm-jni.so`. |
| **APK size**? | 8.7 MB release APK. VTM-attributable content is **1,292,236 bytes uncompressed**: themes 483,417, symbols 429,427, textures 244,597, native 45,872, patterns 45,397, shaders 43,526. |
| Native library size? | **45,872 bytes**, matching the plan's 45 KB figure exactly. |
| Network in the read path? | `vtm-http` is not a dependency. Still no `INTERNET` permission; `verify-apk.sh` now asserts its absence explicitly. |

## 3. What the source reading got right, and the one thing it missed

§5 of the plan was accurate about the architecture. `MapRenderer` has no Android imports,
exposes `onSurfaceCreated()`, `onSurfaceChanged(w, h)` and `onDrawFrame()`, and reaches
the GPU only through `GLAdapter`. The integration is original glue with **no VTM source
changes**: a `Map` subclass, an `AssetAdapter`, and the three hooks driven by hand.

Three things the reading did not cover, each found by reading the 0.25.0 sources:

- **`AndroidAssets` is the wrong adapter for how we package VTM.** `vtm`, `vtm-android`
  and `vtm-themes` are plain JARs, not AARs, so AGP puts their asset entries in the APK
  as *classpath resources*; the `AssetManager` namespace that `AndroidAssets` reads is
  filled from `src/main/assets` and AAR assets only. With `AndroidAssets` installed,
  every shader load returns null and the map draws nothing — a failure that presents as
  a broken GL context rather than a missing file. `MapAssets` reads through the
  classloader instead. **This is the single most likely thing to have gone wrong
  silently**, and it is the reason `MapEngine.start` checks for `assets/shaders/base_shader.glsl`
  before building anything.
- **VTM's `GLState` is a static cache that assumes it owns the context.** Three of its
  entry points save us: `bindFramebuffer` and `viewport` are public and issue their GL
  call unconditionally, and `MapRenderer.draw()` invalidates its program, texture and
  buffer cache at the top of every frame. Two do not, and are handled in
  `DashboardRenderer`: vertex-attribute-array enables are saved and restored around the
  compositing pass, and blending is explicitly disabled after it. Leaving either wrong
  makes the *next* map frame draw nothing, which is a hard failure to attribute.
- **`ThreadUtils.init()` simply records the calling thread**, so the scene thread can
  legally become VTM's "main thread" and call the protected `prepareFrame()`.

The map renders into its own framebuffer rather than the window surface for three
reasons: `MapRenderer` issues an unscoped `glClear` that would wipe the chrome; it
rasterises only the pane's pixels; and the window surface asks for neither depth nor
stencil, which VTM needs. A packed `GL_DEPTH24_STENCIL8_OES` renderbuffer supplies both.

## 4. Two of the three sensor "units" anomalies were misreadings

The plan makes P5.5's position source conditional on settling the units P5.2 flagged as
not reading sensibly. Working through them:

- **`gear=101` is `Park`.** The enum is `Neutral=0`, `First..Tenth=1..10`, **`Drive=100`,
  `Park=101`, `Reverse=102`**. 101 is exactly right for a stationary truck; it looked
  wrong only against an assumed 1..10 range.
- **`bearing=103553459` is 103.55° at a 1e6 scale.** No other candidate divisor puts it
  in 0..360.
- **`satellites_used=32` against `satellite_count=21` reads sensibly if the two names are
  swapped** — 32 visible, 21 in use. 21 visible and 32 in use is impossible. This is the
  fourth time a vendored field name has been wrong; see references.md.

**None of this is measured.** It is schema reading plus arithmetic on one stationary run.
The code therefore treats a scale as *inferred and range-checked*, never as known:
`Scaled` carries the divisor it used and whether the result validated, `VehicleReading`
keeps the raw integers, and the header draws "scale inferred" beside any value resting on
a divisor other than 1. Settling it needs a drive.

## 5. What was built

- **`Dashboard`, `DashboardLayout`** — pure JVM state and geometry: header, map pane on
  a configurable half, four tiles. Tile 0 is media; the other three are named and empty.
- **`DashboardDrawing`** — the chrome, with the map pane punched transparent. Draws the
  clock, speed, a compass needle, gear, restriction and satellites; phone battery,
  network, signal and the position source; and the ODbL attribution inside the map pane.
- **`MapEngine`** — VTM lifecycle and the off-screen framebuffer.
- **`DashboardRenderer`** — composites the map texture under the chrome. The chrome is
  repainted and re-uploaded **only when it changes**: the old test scene moved a bar every
  frame and so pushed 8 MB per frame at 1080p, where a dashboard changes about once a
  second.
- **`VehicleReading`, `Scaled`** — the inference described in §4.
- **`PhoneStatus`** — battery, network type, signal bars, position fallback.
- **`MapData`** — locating and importing operator-supplied `.map` files.
- **`AppEvent::Vehicle`** / `EventKind::Vehicle = 14` — raw sensor integers to the app.
  Latitude and longitude reach the app whatever `sensor_location` says, because that
  option governs what a *report* may contain and the map cannot be drawn from a redacted
  fix; the value stays app-private and out of the report either way.

**The P4 test scene is kept, selectable, not replaced.** Its numbered buttons, fixed
colour patches and calibration crosses are what `tools/lab-receiver` samples in decoded
frames and what the G5 touch calibration aims at, and **G5 has not passed**. Retiring a
gate's instrument before the gate would have been the wrong trade; `scene=dashboard` is
the default and `scene=demo` restores the old picture.

## 6. Verification actually performed

- `tools/android-build.sh` green: clippy, cargo, debug and release APKs, lint
  (`warningsAsErrors`), `verify-apk.sh` on both APKs, host tests.
- `cargo test -p aap-session` green.
- **57 new host checks** (`tools/jni-host-test/DashboardTest.java`) covering layout
  geometry, media hit testing, the scale inference against Ford's measured values, the
  0,0-is-not-a-fix rule, press-and-release-in-the-same-target, and the report carrying no
  position and no track text. Writing them caught a real defect: tile taps were counted
  from the release alone, so a drag that began outside a tile registered as a tap on it.
- Lint's `CoarseFineLocation` caught that `ACCESS_FINE_LOCATION` alone is not grantable;
  `ACCESS_COARSE_LOCATION` is declared beside it and the fallback follows whichever was
  granted.

## 7. Not verified — the honest list

- **No vehicle run. G8 unmet.** The emulator is not a head unit: software encoder,
  SwiftShader GL, 800x480 rather than 1920x1080, and a scripted receiver.
- **Frame rate on the vehicle is still unmeasured**, though §11 now has a 1080p
  emulator figure of ~29 fps with zero drops. A stationary map costs almost nothing:
  48 rasterisations against 830 reused frames.
- **The map is north-up at a fixed zoom.** Rotation is plumbed through `setPosition` and
  passed 0; nothing follows bearing yet, and zoom is not a setting.
- **The pane is not fully covered.** The rendered tiles leave a black margin inside the
  map pane rather than filling it (visible in the capture). Unexplained — it may be
  tile coverage at that zoom or a viewport/tile-size mismatch, and it is the first thing
  to look at next.
- **Only one position was ever rendered.** The lab reports a single fixed fix, so
  nothing has exercised a *moving* map, which is what G8 asks for and what would settle
  the bearing scale.
- **Nothing tested the day theme with data**, only NEWTRON: the lab sends
  `is_night=true`.

## 8. Spec and plan amendments this needs

**Applied 22 September 2026** to `spec.md` and `plan.md` (or to the non-governed file named), with the rest of the queue.

`spec.md` and `plan.md` are governed; these are proposals.

1. **spec §5.2** — the manifest baseline moves from three permissions to seven.
   `ACCESS_NETWORK_STATE` (normal), `ACCESS_COARSE_LOCATION` + `ACCESS_FINE_LOCATION`
   (the position *fallback*; the vehicle's own LOCATION is preferred and needs none), and
   `READ_PHONE_STATE` (signal bars only). Each is separately optional and degrades to
   `—`, per APP09. The plan anticipated `ACCESS_FINE_LOCATION`; the other three are new
   and want recording with their reasons. MediaProjection and accessibility stay
   forbidden; there is still no `INTERNET`.
2. **spec §11.1** — add **G8** with a degraded pass condition, as G6 has: a head unit
   that projects the dashboard while the map pane reports "no map data" is a recorded
   outcome, not a failed gate. G7's wording is still unapplied from P5.3.
3. **spec §2.2 / §13** — the zero-runtime-dependency property is gone; P5.5 is the first
   slice to add one. What replaced it and should be stated as the new invariant:
   reproducible offline builds still hold, the ABI count is still one, AndroidX is still
   zero, and `verify-apk.sh` pins the native library set and asserts no `INTERNET`.
4. **spec §8.1 / §10** — position now reaches the app rather than only the report, for
   the map to follow. Worth stating explicitly that `sensor_location` governs *reports*
   and not the app's own use, since the name suggests otherwise.
5. **spec §12 / plan.md** — a `P5.5` row and section. The slice is implemented and
   **unverified**; its task list should stay unchecked until a run exists, per the
   standing rule that implementation tasks wait on evidence.
6. **docs/p5-plan.md §8** — three open questions can be closed or rewritten: VTM pinning
   (answered, §1), and the sensor units (explained but unmeasured, §4). The frame-rate
   question stands untouched.

## 9. The emulator lab run, 19 September 2026

`LAB_MAP=<region>.map tools/emulator-lab.sh none`, with a 201 MB Massachusetts extract
from download.mapsforge.org covering the fixed position `LabSensorHandler` reports
(42.4242, -71.2121). Result: **pass**, 858 decoded frames, zero decode errors, zero
dropped or refused submissions.

```
projection_scene=dashboard chrome_uploads=2
map_engine=vtm-0.25.0 pane=388x411 theme=newtron data=region.map drawn=19 reused=840 error=-
dashboard_position_source=vehicle dashboard_vehicle_events=1 dashboard_vehicle_fix=true
dashboard_bearing_divisor=1000.0 dashboard_bearing_validated=true
```

Both design bets paid off in the numbers: the chrome was uploaded **twice** across 859
frames, and the map rasterised **19** times against 840 reuses, so a parked vehicle costs
almost nothing.

The captured frame is [docs/images/p5-5-dashboard-lab.png](images/p5-5-dashboard-lab.png),
decoded by the lab receiver out of the stream the app encoded — not a phone screenshot.

### What the run caught that no amount of reading did

Four defects, none of which the build, the lint or the 60 host checks could see. Every
one of them failed *silently* — an empty pane or missing chrome with no error anywhere:

1. **`MapAssets` did not prefix `assets/`.** VTM asks for `shaders/line_aa_proj.glsl`,
   relative to the assets root, because `AndroidAssets` hands paths to an `AssetManager`
   rooted there. As classpath resources they keep the `assets/` segment. The first run
   threw `shader file not found` from inside `RenderBuckets.initRenderer` **after**
   loading the native library and building the framebuffer. This is §3's predicted
   hazard, and the prediction was right about the cause and wrong about the symptom.
2. **The tile source was opened for validation and then closed** before being handed to
   the layer, so every tile read failed silently over perfectly good data.
3. **The redraw request was only checked after a rasterised frame**, never before the
   early return, so tiles finishing on VTM's loader threads never woke the pane. The map
   drew three times and then went quiet forever.
4. **The compositor set `glBlendFunc` once at start-up.** VTM owns global GL state and
   its layers change the blend function, so the chrome vanished entirely — but only once
   the map had real data to draw, because a VTM with no tiles never reaches the layer
   that changes it. `DashboardRenderer.resetCompositorState` now re-establishes blend,
   depth, stencil, cull, scissor and colour mask every frame.

A fifth defect was in the delivery path rather than the map, and it would have bitten a
vehicle exactly as hard: **the sensor channel opens and the head unit answers it before
the video start indication**, so the first vehicle readings arrived while there was still
no pipeline and were dropped. Because the native side only emits on *change*, nothing
resent them — a stationary vehicle would have left the map blank for the whole drive.
`Running` now holds the latest reading and replays it when the pipeline is built, which
is what `nowPlaying` already did for the same reason.

The run also confirmed the smallest-divisor-first fix from §4 on real data: the lab sends
`bearing=1790`, and the report records `dashboard_bearing_divisor=1000.0` — 1.79°, drawn
as "N 2°". Under the original largest-first ordering that would have read as 0.0018°.

### Lab tooling changed

- `tools/fetch-deps.sh` pins VTM's x86_64 tessellator (SHA-256 matching Maven Central).
- `tools/emulator-lab.sh` injects it into the emulator APK copy, installs a `LAB_MAP`
  region into app-private storage the way an operator would, turns the receiver's
  sensors on, and dumps the app report on failure. Two pre-existing bugs were fixed
  there: `AUDIO` was referenced inside the container but never exported, and a comment
  containing an apostrophe silently truncated the single-quoted container script.

## 10. Media pane and map theme, 19 September 2026

Two changes after the first lab run, both verified in it.

**The media pane takes the whole top row** of the half the map does not occupy, with the
two configurable tiles beneath it. A quarter of a half-screen made the transport buttons
smaller than a fingertip deserves at arm's length, and left no room for cover art. The
pane now carries square art on the left, title and artist on two lines, and three
targets sized for a moving vehicle. `DashboardLayout.TILES` is 2, and `media` and
`albumArt` are boxes of their own.

Cover art is read from the same `MediaController` metadata P5.1 already uses
(`METADATA_KEY_ALBUM_ART`, then `ART`, then `DISPLAY_ICON`), so it needs **no new
permission** — it degrades with the same notification grant everything else in the media
integration degrades with, and draws a note glyph when there is none. Two deliberate
constraints:

- **It is copied and scaled to 512 px, not referenced.** The framework owns the metadata
  bitmap and may recycle it, and a recycled bitmap on the renderer thread throws
  mid-frame. It is rescaled only when the track changes, not on every one-second poll.
- **It is still not sent on the `media_info` channel.** `album_art` is unbounded `bytes`
  over a 16 KiB-fragment transport and Ford's limit is unknown (P5.1). Drawing it into
  our own frame carries none of that risk; putting it on the wire carries all of it.

The art itself never reaches the report — only `dashboard_album_art=true|false`. An album
cover is as much the operator's listening history as the track title is (spec §10).

**The map uses the project's own theme**, `app/src/main/resources/assets/oaa/dashboard-dark.xml`,
loaded through `StreamRenderTheme` and the same `MapAssets` resolution VTM's shaders use.
VTM's bundled themes are drawn for a phone in the hand — NEWTRON is high-contrast neon,
DEFAULT is a paper map — and neither reads as a panel a driver glances at. The owned
theme is a dark navy ground with the carriageway as a light sage ribbon over a dark
casing, warm tan arterials, buildings a shade above the background with a quiet outline,
and dark label haloes so text survives crossing a road. It is original work against the
rendertheme schema: **no VTM theme is vendored or modified**, and a theme that fails to
parse falls back to NEWTRON rather than projecting a blank pane.

`night` currently changes only the clear colour. That is deliberate: the one signal
available is Ford's `is_night`, sent true once per session and never updated even in
daylight, so switching a whole theme on it would leave a driver with a night map at noon.
A day variant belongs with a signal worth trusting.

### Junction artifacts, and the ordering rule behind them

The first version of the theme declared each road class's casing and surface together,
which reads naturally and is wrong. **VTM assigns every rendering instruction a draw
level from its position in the theme file** (`XmlThemeBuilder`, `mLevels++`) and sorts
render buckets by that level, so a trunk road's casing landed *above* a residential
road's surface and every junction in the network grew a dark scar.

The theme is now two passes over the same match tree — every casing, then every surface
— with rail and paths below both and labels above. That ordering is load-bearing and is
commented as such at the top of the file.

Motorways and trunk roads are red (`#d2453a`) over a dark red casing; `toll=yes` gets a
darker red (`#8e2f26`).

**Corrected 21 September 2026 after the first Texas drive:** toll highways drew no line at
all. The stock Mapsforge writer's tag-mapping rewrites `toll=yes`/`toll=true` to
`toll=tl_yes`, so the original pair of rules (`yes|true` and `~|no|false`) matched
neither branch for every toll way in `texas.map`. The rules are now `tl_yes|yes|true` and
its exact complement `-|tl_yes|yes|true`; a check against VTM 0.25.0's own `RuleBuilder`
shows every value (absent, `tl_yes`, `yes`, `true`, `no`, `false`, anything else) lands
in exactly one branch. Street names still appeared on those roads because the label pass
has no toll condition, which is what made the gap look like a rendering fault.

Verified by zooming out to the Route 2 / I-95 corridor for one run
([docs/images/p5-5-map-highways.png](images/p5-5-map-highways.png)): red motorway, tan
arterials, water, green space and place labels, with clean junctions throughout.

### Blank tile on the first Texas drive (21 September 2026)

Once on the drive, the pane showed nothing but the route line until the vehicle crossed
into the next tile. VTM 0.25.0's `MapFile.processBlocks` handles every failed block read
(short read, bad block pointer, buffer it could not allocate, block over its 10 MB
limit) by logging a warning and returning, and `query` then completes the tile with
`SUCCESS`. The tile manager caches that as a loaded, empty tile and never asks again.
A tile completed with `FAILED` is instead cleared and queued again on the next position
update. `MapReadGuardTest`, run with `MAP_FILE` over the Massachusetts extract, shows both
halves on VTM's real reader.

`MapReadGuard` wraps the tile source, listens on the reader's `java.util.logging`
loggers, and turns a warned `SUCCESS` into `FAILED`, up to three tries per tile, after
which the partial tile is let through. The report's `map_engine` line now carries
`map_reads`, `map_read_failures`, `map_read_gave_up` and the first warning. **The cause
of the drive's failed read is unknown**: real blocks are around 95 KB, so the size limit
is an unlikely culprit, and `texas.map` has not been swept for bad blocks. A non-zero
`map_read_gave_up` on a later drive would point at the data rather than a transient read.

### Still unverified here

- **Real cover art has never rendered.** The emulator has no media session and no
  notification grant, so every run reports `dashboard_album_art=false` and draws the
  placeholder. The scaling, the centre-crop and the recycled-bitmap guard are untested.
- **Toll roads have never rendered.** No toll way was in view in any lab run. The first
  Texas drive showed they drew nothing at all (see the correction above); the fix is
  checked against VTM's rule matching, but the dark red has still not been seen on a panel.
- **A small artifact remains on the motorway at low zoom**: dark quadrilaterals sit on
  the red carriageway in the zoomed-out capture. Areas are declared below roads so they
  should not paint over them; unexplained, and only seen at zoom 12, well outside the
  driving zoom.
- ~~The theme was tuned against a 388x411 pane at 800x480.~~ **Closed**: the lab now
  runs at 1920x1080 (§11) and the theme is tuned there.

## 11. Road labels, and why the lab was lying about them, 19 September 2026

Most roads showed no name. The cause was not the theme.

**VTM drops a path label whose text is wider than the way it runs along** —
`LabelPlacement:213`, `if (ti.width > ti.length * scale) continue`. Two things were
making that bite:

1. **`CanvasAdapter.dpi` was 320**, chosen as "a dashboard panel is about xhdpi". It
   scales fonts *and* line widths, so every label was drawn at double size and almost no
   suburban street was long enough to carry its own name. Now 240.
2. **The lab pane was 388x411**, because the receiver hardcoded an 800x480 advertisement.
   The vehicle projects 1920x1080, where the same pane is **930x920** — every way is
   about 2.4x longer in pixels while the text is the same size.

The second is the important one, and it means **the lab had been misrepresenting this
slice all along**. `tools/lab-receiver/receiver.cpp` now takes `--video-config`
(800x480, 1280x720 or 1920x1080; it already had a latent 1280x720 branch and no way to
reach it), and `tools/emulator-lab.sh` passes `LAB_VIDEO_CONFIG`. At 1920x1080 the same
theme labels the network properly — see
[docs/images/p5-5-dashboard-1080p.png](images/p5-5-dashboard-1080p.png), with fourteen
street names, the tan arterial and a red motorway at the corner.

Label sizes were also reduced (road 11, major 12) and given explicit VTM priorities —
**lower wins**, and the default is `Integer.MAX_VALUE / 2`, so without them every label
competed at the same value and collisions were settled arbitrarily. Street names now
outrank place and water names, because at driving speed the road under the vehicle
matters more than the pond beside it.

One thing that looked like a defect and is not: at a downscaled view the labels appear to
run together ("MorelandAvenue"). At native resolution the word spaces are there.

### What the 1080p run adds

The plan says to assume 30 fps for the map until measured. At 1920x1080 the run rendered
and submitted **878 frames in 30 s — about 29 fps — with zero drops, zero congestion and
zero decode errors**, at `drawn=48 reused=830`. That is a software encoder and
SwiftShader in an emulator, so it is not the vehicle's answer, but it is the first
evidence that the owned map sustains the target rate at the target resolution rather
than the 8-11 fps the MapLibre experiments the plan cites reported.
