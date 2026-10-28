# The dashboard: what the car screen shows

HeadLink does not mirror the phone or project another app. It draws its own picture, the
**dashboard**, and encodes it as the H.264 stream the head unit displays. The picture has
three parts:

- a **header** across the top: clock, speed, compass, weather, two icons, signal and battery;
- a **map pane** filling one half;
- the other half: the **media pane** on top, and two **tiles** under it. The left tile holds
  favourites, or the trip panel during a route. The right tile is the **flex tile**: a call,
  a POI card, weather, or notifications.

![The dashboard at Ford's 726x480 content rectangle, day palette, with the Gas, Food and
Shop buttons down the map's left edge](images/p11/p11-5-poi-left-day-726x480.png)

![During a route at 800x480: the turn card over the map, the trip panel in the favourites
tile, traffic on the line. This render is older than the POI buttons, which now also
appear at the map's left edge](images/p9/p9-traffic-route-800-day.png)

Routing, guidance, traffic and search logic are covered in [navigation.md](navigation.md).
Speech is covered in [audio-and-speech.md](audio-and-speech.md). This document covers what
those features put on the screen.

## How a frame is made

**Where:** `ProjectionPipeline.kt` (scene thread `aap-scene`), `Dashboard.kt` (state and
layout), `DashboardDrawing.kt` (chrome), `DashboardRenderer.kt` (EGL/GLES compositing),
`MapEngine.kt` (VTM map).

- `Dashboard` is pure JVM and `@Synchronized`. Head-unit touches, sensor events, phone
  status and media updates arrive on other threads. Once per frame the scene thread calls
  `Dashboard.mapCamera()` and then `nextFrame(now)`, which returns an immutable `Snapshot`.
  Pure JVM means `tools/jni-host-test` checks layout, hit testing and every rule without a
  device.
- `DashboardRenderer.draw` composites two textures into the encoder's input surface (an
  `EGL_RECORDABLE_ANDROID` window surface):
  1. **The map.** `MapEngine` drives VTM's `MapRenderer` by hand into its own framebuffer,
     sized to the pane. It uses its own framebuffer because VTM issues an unscoped
     `glClear`, needs depth and stencil, and only has to rasterise the pane. A parked
     vehicle re-uses the last texture without redrawing.
  2. **The chrome.** A full-frame ARGB `Canvas` bitmap with the map pane punched
     transparent, blended over the map with premultiplied alpha. It is repainted and
     uploaded **only when the snapshot differs** from the last one drawn. Every value is
     rounded or put into words for this reason: whole degrees of rotation, distances in
     steps, the arrival time in minutes. A 1080p dashboard changes about once a second, so
     the map is the only per-frame cost.
- Frames run at the negotiated rate. The emulator held about 29 fps at 1920x1080. 30 fps
  is the accepted target for the map.
- **Why not Android views:** spec §8.2 forbids ordinary views on an encoder Surface. The
  chrome is painted with `Canvas`, and every glyph (sun, moon, maneuver arrows, weather
  icons, the history clock) is drawn with paths rather than taken from a font, so it can
  never fall back to a missing glyph.
- **Why VTM:** its `MapRenderer` has no Android imports and draws into a caller-supplied
  EGL context. Its core has no dependencies, its native tessellator is 45 KB, and its
  licence (LGPL-3.0 with relinking waived) fits GPL-3.0-only. MapLibre would have needed a
  fork plus AndroidX and OkHttp. OsmAndCore and Organic Maps were far heavier. Projecting
  OsmAnd's own UI is off the table: it would need MediaProjection or trusted-display and
  injection permissions (spec §4, §5.2). The integration is original glue, with no VTM
  source changed.
- **A map that will not start does not stop the dashboard.** `MapEngine.start` failures are
  kept. The pane then draws `map: <reason>` (no map data, an unreadable file, missing
  shaders), and the clock, sensors and media keep working. A frame that throws ends the
  attempt with a named reason (`failScene`) rather than crashing the process.

**GL state traps.** VTM shares the context and assumes it owns it. Each of these failed
silently and is now handled in `DashboardRenderer`:

- **Blend function.** VTM's layers change blend, depth, cull and scissor. Setting blend
  once at start-up made the chrome vanish, but only once tiles had data.
  `resetCompositorState` re-establishes all of it every frame.
- **Array buffer.** The compositor's quads use client-side vertex pointers, so the array
  buffer is unbound *before* they draw. If VTM's last VBO stays bound, the pointer is read
  as an offset into it. The result was a whole frame of clear colour on every frame the
  map rasterised: a flicker.
- **Vertex attribute arrays.** Their enable state is saved and restored exactly, because
  VTM caches it and skips calls its cache thinks are redundant.
- **Clear colour.** `MapEngine.drawFrame` sets the clear colour from the theme's
  `map-background` itself, every frame. VTM's cached clear colour does not survive the
  compositor's own clear, and for a long time every map was drawn on black.

`MapEngine` has three more traps of the same kind, each of which once gave an empty pane
with no error:

- **The `assets/` prefix.** VTM's jars are plain JARs, so their assets are classpath
  resources, not Android assets. `MapAssets` reads them through the classloader and adds
  the `assets/` prefix VTM leaves off. `MapEngine.start` checks that the shaders are
  present before building anything.
- **Leave the tile source open.** `openMapFile` keeps the source open after its header
  check, because the tile layer reads through it.
- **Check redraws before the not-dirty return.** `drawFrame` takes VTM's redraw request
  *before* its early return. Otherwise tiles that finish on loader threads never appear.

**The test scene.** The setting `scene=demo` (`DemoScene.kt`, `SceneRenderer.kt`) replaces
the dashboard with the old numbered-button test pattern. Its colour patches and
calibration crosses are what `tools/lab-receiver` samples and the touch calibration aims
at. `scene=dashboard` is the default.

**When the head unit takes the screen** (SYNC's own pages, the reversing and parking
cameras): `ProjectionPipeline.setFocused(false)` stops drawing, and the `Dashboard` keeps
all its state. On regain the encoder, EGL surface and renderer, map engine included, are
rebuilt before drawing resumes, and presentation times keep rising. The map reloads its
tiles; the route, favourites, open cards and keypad are as they were. Why the rebuild is
needed is in [protocol.md](protocol.md).

**Privacy.** Caller names, notification text, player names, keypad digits, POI names and
positions are drawn and nowhere else. `Snapshot.toString()` prints only the frame number,
the flex source and whether the keypad is open. Every `report()` line is counts and states.

## Layout

**Where:** `DashboardLayout` in `Dashboard.kt`.

The scene is laid out in the head unit's **content rectangle**. That is the default
`scene_area=content`; `frame` uses the whole video frame. Every size is a proportion of
that rectangle, so one layout serves every screen:

- `margin` = width / 96, `header` = height / 9;
- two equal panes below the header, each `(width − 3·margin) / 2` wide;
- the map takes one pane. The other pane is the media pane over two equal tiles.

| Display | Header | Map pane | Map bubble | Media pane | Each tile | Turn card |
| --- | --- | --- | --- | --- | --- | --- |
| Ford 726x480 (800x480, 37 px side insets) | 53 | 352x413 | 44 | 352x203 | 172x203 | 82 tall |
| 800x480 | 53 | 388x411 | 48 | 388x201 | 190x201 | 82 tall |
| 1920x1080 | 120 | 930x920 | 115 | 930x450 | 455x450 | 184 tall |

- **Tightest case.** The 726x480 rectangle is the one the host tests hold every element
  to: no overlap, nothing cut, at every size, with the map on either side.
- **Map side.** The map is on the left by default; the *Map on left* setting moves it.
  Which side falls to hand depends on the vehicle, and only the driver's seat can tell.
- **Relayout.** The head unit can change the content rectangle mid-session. SYNC 4 sends
  `UPDATE_UI_CONFIG` with new insets a second or two in. `ProjectionPipeline.relayout`
  swaps the layout, sets the bitrate to the new rectangle and asks for a keyframe.
  `DashboardRenderer.fitMap` resizes the map framebuffer and quad on the same frame.
  Before that fix, the map kept its start-up size. The chrome punched a wider hole, and a
  black strip fixed to the screen showed past the map's edge; panning or zooming never
  moved it, because it was not part of the map. The report's `map_engine=… resizes=N`
  counts these.

**Touch.** Head-unit touches arrive already mapped to frame pixels (see
[protocol.md](protocol.md)), so hit testing uses the layout directly.

- **Press and release.** Every button acts on press and release on the **same target**.
  A slide off, a cancel, or a drag that ends on a button is not a press. The pressed
  target is lit.
- **Hit areas.** Targets are square even when drawn round, and a margin apart, so a
  finger at arm's length can hit the corner of a round bubble without reaching the next
  one.
- **Ownership.** A gesture belongs to whatever it came down on until the last finger
  lifts. The open keypad claims presses first. A finger on a card or button over the map
  is never a pan. Lists (favourites, notifications) use `TouchList` (`FlexTile.kt`): moving
  more than a fifth of a row turns the gesture into a scroll for good.

## Header

**Where:** `DashboardDrawing.drawHeader`, `DashboardLayout.header*`.

Left to right: **clock**, **speed**, then the **compass**: a needle, the compass point and
the bearing, as in `NE 30°`. The compass, heading and bearing always stay, because they
are the quick check on the map's arrow.

Right to left: **battery**, **signal**, **☀/☾**, **Ⓟ**, then the **weather** item while
*Show weather* is on. The middle of the header stays empty; the turn moved to the turn card.

- **Clock.** Setting *Clock*, `ClockFormat`: 24-hour, the default, or 12-hour. The 12-hour
  header clock is written `6:40`, with no AM/PM. The time now is never ambiguous, and
  726x480 with the weather item leaves only about 5 px spare after `23:59`, the speed and
  the bearing; `WeatherTest` fails if anything widens it. The trip panel's arrival time
  draws a small `PM`. The same setting formats weather hours, alert end times and the
  phone's route line.
- **Speed.** Setting *Speed unit*, mph (default) or km/h. A missing speed is drawn as
  `— mph`, so it reads as "no reading" rather than a failed drawing.
- **Speed and bearing sources.** Each is taken independently:
  - **Speed:** the head unit's `car_speed`, else its `gps_speed`, each only at a validated
    scale; otherwise the phone's.
  - **Bearing:** the head unit's, when its scale validated; otherwise the phone's.

  Ford's bearing arrives as degrees × 1e6, and that scale is confirmed on a drive.
  [protocol.md](protocol.md) covers sensor scales.
- **Signal** (`PhoneStatus`, `SimSignal`). Four bars for the primary SIM, which is the
  default data subscription. Beside them goes the network type (`5G`, `LTE`, …), or `wifi`.
  Under the bars there is one row of four dots per extra SIM, up to 3. Unlit bars and dots
  are drawn faint, so four bars always read as a scale. A SIM with no service is a row of
  hollow rings; a missing row means no such SIM. This needs the optional
  `READ_PHONE_STATE` permission.
- **Battery.** The glyph fills with the level, with a bolt while charging, then the
  percentage. At 15% or less, and not charging, it turns the warning colour.
- **Ⓟ: notification pause.** Thin and grey while notifications may show. Bold and in the
  accent colour while paused, because a pause left on is the mistake worth seeing. Saved
  across sessions. It does not hide calls or weather alerts, and does not touch the phone's
  Do Not Disturb.
- **☀/☾: day/night.** See [Day and night](#day-and-night).
- **Diagnostics line.** Setting *Diagnostics*, off by default. A small second line:
  - `gear` (Ford sends Park as 101, Drive 100, Reverse 102);
  - the head unit's own `driving=` and `night=` words;
  - `sats used/visible`;
  - `scale inferred` when a shown value rests on an unconfirmed divisor;
  - `src vehicle|phone|none`, the position source.

  With the line off, the main line moves to the header's middle.

Verified: the header has been on the truck since the dashboard became the default. The
multi-SIM dots, the battery crossing 15% and real network types have been seen only in
host tests and the emulator, which shows `wifi`. The 12-hour clock is host-tested only.

## Day and night

**Where:** `DayNightMode` in `DisplayChoices.kt`, `Dashboard.resolveNight`,
`DashboardPalette.kt`, `MapEngine.setNight`, map themes in
`app/src/main/resources/assets/oaa/dashboard-{dark,light}.xml`.

- **The setting.** *Day/night* is Automatic (default), Day or Night. Tapping ☀/☾ on the
  dashboard cycles Automatic → Day → Night → Automatic and saves the result. The icon
  shows the palette in use, with a small accent "A" while Automatic chose it.
- **Automatic** follows the head unit's `NIGHT_DATA is_night` **every time it changes**.
  `Dashboard.restriction` counts only `true` or `false` as a night signal. A `-` (a
  driving status arriving alone) is not day.
- **Before the head unit says anything,** Automatic draws the palette the last session
  ended in (`last_resolved_night`), else night. Guessing day used to theme the map day and
  then reload every tile for night a moment later. The service also hands any night
  signal already held to the pipeline before it starts.
- **One value for both.** `resolveNight` sets the chrome palette and the map theme alike,
  so they never disagree.
- **The Ford's "always night" was a pin.** Every recorded SYNC 4 session sent
  `is_night=true` once, even at noon. That was the truck's display pinned to dark mode,
  not SYNC behaviour, so Automatic is right to follow the flag. Whether SYNC sends a second
  `NIGHT_DATA` at dusk is still unseen; it needs a drive with the display unpinned.
- **Palettes** (`DashboardPalette.DAY`, `NIGHT`). They have the same roles, and host tests
  hold both to contrast rules (4.5:1 for text). Day is light grey, not white: in sunlight a
  dark screen is a mirror, but nothing should be paper-bright beside the map. Its accent
  and warning colours are darker than night's so they still read on light tiles. Answer
  green and Decline red are separate roles in each palette.
- **Map themes.** Both are original work against VTM's rendertheme schema; no VTM theme is
  vendored. They share the same rule tree and differ only in colour, and `MapThemeTest`
  checks this.
  - **Night:** navy ground `#151a2b`.
  - **Day:** warm light grey ground `#e9e7e1`.
  - **Day route casing:** the route's cyan is weak on the day ground, so a dark teal
    casing is drawn under it with the day theme only.
  - **Fallback:** a theme that fails to parse falls back to VTM's NEWTRON for that theme
    alone.
- **The switch.**
  - **Cost.** A theme change drops every tile. Each theme is parsed once and kept.
    `MapEngine` holds the old picture until the visible tiles have reloaded, capped at
    1.5 s, so no half-drawn map is shown. VTM's loader pause costs about 100 ms on the
    scene thread per switch.
  - **Interrupted reads.** Pausing interrupts loader threads, and an interrupted
    `FileChannel` read closes that channel for good. `MapLoaderInterrupts` reopens the
    data source before that loader's next tile.

Verified: on the truck, Automatic has only ever seen the pinned "night". Real day/night
switching, the switch timings, and the day palette in sunlight are lab-only (emulator,
1920x1080, `daynight` script).

## Media pane

**Where:** `DashboardDrawing.drawMedia`, `drawPlayers`; `MediaControl.kt`,
`MediaPlayers.kt`, `Player.kt`.

The media pane takes the whole top row of its half. A quarter of the half made the
transport buttons smaller than a fingertip.

- **Now playing.** Cover art at the left, capped at a third of the pane's width. Title and
  artist on two lines, truncated with an ellipsis. Then three transport buttons: previous,
  play/pause, next.
  - **Buttons** go through `AudioManager.dispatchMediaKeyEvent` to whatever is playing.
    That needs no permission and always works.
  - **Reading** what plays needs the notification-listener grant
    (`MediaAccessService`); without it the head unit is told a stopped, empty state rather
    than a guess. What is published on the head unit's `media_info` channel, and the
    wheel buttons, are covered in [protocol.md](protocol.md) and
    [audio-and-speech.md](audio-and-speech.md).
  - **Cover art** is copied and scaled to at most 512 px when the track changes, because
    the framework may recycle its own bitmap mid-frame. It is drawn on the dashboard only,
    never sent on `media_info`: the art field is unbounded, and Ford's limit is unknown.
- **Idle** (nothing playing or paused with a session). The pane lists up to two **player
  apps**, each row a **Resume** target.
  - **Sources:** apps with a live media session, and apps exporting a
    `MediaBrowserService` that have a launcher activity. The launcher requirement drops
    system services such as Bluetooth.
  - **Order:** by real use; packages seen playing are remembered in app-private
    preferences.
  - **Resume** plays a live session, or uses Android's media resumption
    (`EXTRA_RECENT`) for a killed app. The row greys with "Starting…" while it waits, or
    shows "Couldn't resume" for 4 s.
  - **Excluded:** Telecom's `HeadsetMediaButton` session, which has no playback state. It
    used to show as "Phone Calls" during every call.
  - **Without notification access** a small line says more players could be listed.

Verified: transport buttons and the now-playing text are verified on the truck, with
Spotify. Real cover art and Resume have been exercised only in the emulator, with a lab
player app.

## Favourites tile and trip panel

**Where:** `DashboardDrawing.drawFavorites`, `drawTrip`; `Favorites.kt`, `Favorite.kt`.

- **Favourites.** The left tile lists saved places, three rows at a time, scrolling.
  - **Why three rows:** a row is pressed from the driver's seat, so fewer and bigger rows
    beat more and smaller ones. Even so, each is about 50 px tall at 480-line displays,
    the smallest target on the dashboard.
  - **Tap a row** to route there.
  - **Recent destination:** the most recent one sits on top, with a drawn history icon. It
    occupies one slot that each arrival replaces, and it is never added to the saved list.
  - **Empty:** the tile shows "No favorites yet / Add them on the phone", sized to fit.
  - **Editing:** the list is edited on the phone and reaches a running dashboard live.
- **Trip panel.** While a route exists, the panel takes this tile's place. The favourites
  keep their scroll position underneath. It shows:
  - the arrival time, large, from the clock plus the route's remaining time, rounded to
    the minute;
  - the time and distance remaining, with "+7 min traffic" when traffic is in the figure;
  - the next two maneuvers after the current one, each an icon and a distance;
  - **End route**, a media button's height.
  - **The voice toggle**, a speaker glyph at End route's right, slashed when off. A
    warning mark shows when speech cannot happen now (no engine, or no A2DP output). It
    is End route's height but only a third of the row wide (131x205 at 1080p, 50x95 at
    726x480), not square: End route must stay the widest target there, with readable
    text. What it switches: [audio-and-speech.md](audio-and-speech.md).

  While re-routing, or with no fix yet on the route, the figures are dashes rather than
  stale numbers.
- **End route is never hidden while a route exists.** It is the only way to cancel from
  the car, and the fix for a mistaken tap, so no undo banner exists. Arrival ends the
  route by itself 30 s after "You have arrived".

What a route does, arrival, recents and favourites storage: [navigation.md](navigation.md).

Verified: the tile and panel are host-tested and rendered in the lab. Tapping a favourite
and End route have not been recorded on the truck.

## Flex tile

**Where:** `FlexRules` and `FlexSource` in `FlexTile.kt`; `DashboardDrawing.drawFlexTile`.

The right tile shows the highest-priority source that has something to say. Its label
follows what it shows:

| Priority | Source | Label |
| --- | --- | --- |
| 1 | A call, in any phase | incoming call / calling / call / **Muted** / on hold / call ended |
| 2 | A POI card (tapped marker, or search ahead) | gas, food, shop, places, gas ahead… |
| 3 | The weather view, opened from the header | weather |
| 4 | A weather alert at or above the chosen level, not dismissed | weather alert |
| 5 | Notifications, when turned on, not paused, and any present | notifications |
| 6 | Empty: "No notifications" or "Notifications paused" | notifications |

- **What wins.** A call outranks everything, because a missed call costs more than a
  missed message. Ⓟ hides neither calls nor alerts: it pauses notifications, not safety
  information.
- **A call closes any open POI card or weather view, and they do not come back.**
- **One tapped view at a time.** Opening the weather view closes the POI card, and the
  reverse.
- **Auto-close.** An untouched POI card closes after 60 s, and an untouched weather view
  after 30 s.
- **The trip panel never displaces the flex tile.** During a route, one tile is the trip
  and the other is whatever is happening.

### Notifications

**Where:** `NotificationFeed.kt`, `MediaAccessService.kt`.

*Notifications on the dashboard* is off by default, and off means the listener reads
nothing.

- **Which ones:** only alerting notifications. Not ongoing, silent, minimum-importance,
  group summaries, media, calls, this app's own, or apps the driver excluded.
- **How they are held:** in memory for the session, newest first, never written or logged.
- **What a row shows:** two rows show at once. Each has the sender or title, large, and
  "App · text", small. Who and which app is what a glance needs; the rest is for reading
  aloud.
- **Tap a row** to have the notification read aloud. The row flashes for 800 ms and stays
  lit while it is spoken ([audio-and-speech.md](audio-and-speech.md)).
- **Removed on the phone** means removed from the tile.
- **Un-pausing Ⓟ** re-reads the phone's shade, so nothing that arrived during the pause is
  lost.

### Calls and the keypad

**Where:** `CallControl.kt`, `CallCompanionService.kt`, `CallState.kt`.

*Call controls* is off by default. Two routes are chosen per session:

- **Telecom (full controls).** A no-UI `InCallService`, declared disabled in the manifest.
  - **Enabled** only while the setting is on and a session is projecting. Disabled at
    session end, and again at launch in case a crash left it on.
  - **Binding.** Telecom binds a third-party no-UI in-call service only with
    `MANAGE_ONGOING_CALLS`. `CALL_COMPANION_APP` stopped working in Android 11. The
    app-op is granted once over adb:
    `adb shell appops set --user 0 com.jw6ventures.headlink MANAGE_ONGOING_CALLS allow`.
    The app-op alone is what Telecom honours.
  - **What it adds:** answer, decline, hang up, **mute** (state read back from Telecom, so
    a mute pressed on the phone shows too) and **keypad tones**.
- **Call notification (fallback).** Without the app-op, the dialer's `CallStyle`
  notification actions give answer, decline and hang up only. This route also covers a
  call already ringing before the companion is bound.

On the tile:

- **Ringing:** the caller, then **Answer** (green) and **Decline** (red), each half the
  tile and full height: the biggest targets the tile can give. The words are drawn on
  them too.
- **Connected:** the caller and a running duration, over **Mute**, **Keypad** and
  **Hang up**. Mute is lit while muted, and the tile label reads "Muted", because a
  forgotten mute is the common mistake. A button that cannot work on this route is not
  drawn.
- **The keypad** pops up over the whole non-map half; inside the tile the keys would be
  too small. It has 1–9, *, 0, #, an echo strip, and Close.
  - **Tones:** a tone starts on press and stops on release, so a held key is a held tone.
  - **Closing:** it closes itself when the call can no longer take tones.
  - **Digits** are often PINs. They are drawn in the echo strip and held nowhere else. Key
    presses are not even written to the event log.
- **Where actions run:** the service carries out every action on the main thread, never
  from the render thread.

Verified: emulator only, with the lab player app's calls, on both routes. What SYNC shows
over the projection when the phone is also paired for calls is unknown.

## Weather

**Where:** `Weather.kt` (model, parser, rules, words; pure), `WeatherFeed.kt` (read loop;
pure), `WeatherProvider.kt` (ContentResolver, notifier receiver, `object Weather`),
`WeatherDisplay.kt`, `WeatherDrawing.kt`.

**Source: Breezy Weather's content provider.** HeadLink has no `INTERNET` permission, so
weather has to come from another app on the phone.

- **Why not the Gadgetbridge broadcast:** it has no alerts and anyone can forge it.
- **Why not Breezy's sharing library:** it is JitPack-only and brings kotlinx.serialization.
- **What is read:** the provider (`org.breezyweather.provider.weather`, guarded by
  `org.breezyweather.READ_PROVIDER`) returns gzipped JSON. HeadLink reads it with the same
  streaming JSON reader as the traffic feed, capped at 4 MB inflated.
- **Refusals.** It refuses a provider owned by any package but `org.breezyweather`; a
  debug build of HeadLink also accepts `org.breezyweather.debug`, for the lab. It also
  refuses provider major versions other than 0.
- **No refresh requests.** Breezy offers no way to ask for a refresh, so HeadLink shows
  how old the reading is instead.

**When it reads:** only while a projection is attached and *Show weather* is on. It reads
at projection start, on Breezy's update-notifier broadcast (a cue with no data in it, so a
forged one costs only an extra read), and every 15 min as a backstop. Reads run one at a
time off the main thread. The reading is held in memory only and dropped at session end.
A failed read keeps the last good reading, so its age still shows. A refusal drops it.

**Which place:** Breezy's current-position location, else the one nearest the vehicle's (or
phone's) fix, else the first. Location ids are never saved; Breezy says they can change.

**Staleness is shown, not hidden.** Every view carries a place line, such as
"Waco · 40 min ago":

- older than **3 h**, the line leads with "old" and the item is dimmed;
- a place more than **50 km** from the fix says how far, as in
  "old · 62 km away · Waco · 4 h ago";
- the flags lead, so a line too long for the tile loses the place or the age, never the
  warning.

**On the dashboard** (*Show weather* is off by default):

- **Header item:** a drawn condition glyph and the temperature, `78°F`. Moon variants
  are used at night.
  - **Width:** fixed at 2.2 headers, so the clock and speed never move. The unit letter
    is dropped (`78°`) only if it does not fit.
  - **No reading:** a dimmed `—`.
  - **Alert in force:** a warning mark in the alert's colour.
  - **Units:** Breezy's own, never converted.
- **Weather view:** tap the header item to open it in the flex tile. During a call the tap
  is counted and does nothing. It shows:
  - now: glyph, temperature, condition, "Feels 84°" when it differs by 3° or more, and
    today's high and low;
  - the next 4 hours, with rain chance from 20%;
  - an alert strip if any alert is active, whatever its severity;
  - the place line.

  With no reading it shows why, for example "Permission not granted", so a tap always
  says something.
- **Alert card:** shown for an active alert at or above *Alerts shown from* (Minor,
  Moderate, **Severe** default, Extreme). It has the alert's colour bar, the headline in
  up to three lines, "until 21:45" and "+1 more". Tapping dismisses that alert for the
  session. An upgraded alert, which arrives under a new id, shows again. The description
  and instructions are never read.
- **Speaking alerts:** *Speak weather alerts* (on by default) speaks the headline once per
  alert, only while the trip panel's voice toggle is on
  ([audio-and-speech.md](audio-and-speech.md)).

Setup, the settings card and the permission prompt: [phone-app.md](phone-app.md). Getting
Breezy set up: [data.md](data.md).

Verified: host tests (`WeatherTest`), and the emulator against a stand-in Breezy
(`tools/breezy-standin`, never to be installed on a phone). **The field names follow
Breezy's source and have not been checked against a real blob from the Pixel.** Use the
debug extra `--ez lab_weather_capture true` to capture one. Not seen on the truck.

## Map pane

**Where:** `MapControl.kt` (camera and gestures, pure), `MapEngine.kt`,
`DashboardDrawing.drawMapOverlay`, `PoiMarkers.kt`, `PoiMarkerArt.kt`. Map data, routes,
traffic colours and alternatives: [navigation.md](navigation.md).

- **Gestures.**
  - **One-finger drag** pans and stops following. Panning starts only after 24 px of
    travel, so a pinch's wobble does not drop the vehicle.
  - **Two fingers** pinch to zoom and twist to rotate. Pinch works on SYNC 4.
  - **A still press of 800 ms** picks the place under the finger as a destination. That
    is longer than Android's long press on purpose: a tap lingering over a bump must not
    set a route.
  - **Range.** Zoom runs from about level 16 by default, between level 4 (all of Texas)
    and 19.
- **Floating bubbles.** Each is an eighth of the pane:
  - bottom-left: **recentre**, and the **compass** above it, which flips north-up and
    heading-up. The orientation is saved.
  - bottom-right: **+** above **−**, above the attribution. Zooming does not stop
    following.
  - The top of the pane is kept for the turn card.
- **Heading-up** holds the last heading when the bearing drops out. Spinning the map at a
  junction, where it is being read, is worse than being a few degrees stale.
- **Vehicle marker.** A dot, which becomes an arrow along the bearing above 5 mph and goes
  back to a dot below 4 mph.
  - **Colour:** setting *Marker colour*, one of seven fixed colours, each with a rim that
    shows on both themes. Blue is the default. A free colour picker could choose a colour
    with no workable rim.
  - **Position:** the marker sits at the pane's centre while following; once panned, it
    moves to wherever the vehicle is on screen.
- **Position source.** The vehicle's own fix is used when it has one: it needs no Android
  location permission. The phone's fix is the fallback. The map pane follows whichever
  wins.
- **Turn card.** During a route, a plate across the top fifth of the pane shows:
  - the maneuver icon, drawn from the same maneuver value the instrument cluster is sent;
  - the distance, large;
  - the street, or the instruction if the map names no street;
  - in place of these, `Rerouting…` or `You have arrived`.

  It is drawn even with no map installed. While it shows, the followed vehicle sits half a
  card lower, in the middle of the uncovered map. When a faster route is offered, the card
  divides (`OfferLayout`).
  - **Layout.** Side by side is the default (`defaultOfferLayout`). At 726x480 it takes
    99 px of the pane against stacked's 143, and gives Take and Keep 91x44 against 67x47.
    It costs the street name and a smaller icon. Stacked keeps the turn exactly as it is.
  - **Framing.** The map frames both routes below the card (`MapControl.fit`). It measures
    in the rotation on show and zooms out only, never in. Any drag, pinch, twist, zoom or
    compass press hands the map back to the driver. Take, Keep or recentre returns it to
    following at the old zoom.
- **Lines on the map** (`MapEngine`, `TrafficLines`):
  - **Route:** cyan `#22C7F0`, 9 px at 240 dpi. The day theme adds a `#0B4F6C` casing
    4 px wider underneath.
  - **Traffic on the route ahead:** amber `#F59A00` for slow, red `#F2362B` for queuing,
    and closures dashed `#7A0A0A` on `#F4EFEA`. Each has a near-black edge. At most 8
    pieces are drawn, and a piece is re-cut every 50 m the vehicle moves along it.
  - **Live alternatives:** grey on a darker edge, under the route.
- **POI buttons** (Gas, Food, Shop). They are round bubbles holding each kind's marker
  icon, in the left column from the top of the pane, or below the turn card. Where the
  column would reach the compass, they sit in a row instead.
  - **State:** off at every session start, never saved.
  - **Markers:** a lit button shows that kind's markers from OsmAnd maps. They are
    two-thirds of a bubble across, drawn under the street labels, at most 25 per kind
    nearest the centre.
  - **Colours:** violet `#D500F9`, orange `#FF6D00` and green `#00B050`, chosen away from
    both themes, the route cyan and the accents.
  - **Tapping a marker** opens its card in the flex tile with Go and Close. A pile under
    the finger lists up to three places.
  - **During a route,** a button press searches ahead along the route, and the card
    offers **Add stop**.

  [navigation.md](navigation.md) covers both searches.
- **Attribution.** `© OpenStreetMap contributors (ODbL)` sits on its own plate in the
  bottom-right whenever map data is drawn, as the licence requires. `Traffic © HERE`
  appears whenever traffic is drawn, is counted in the time, or shaped the route, even
  with no map. It sits on its own line above the ODbL credit, ending a margin short of
  the − bubble. At 726x480 the two credits together are wider than the room between the
  corner stacks, so they cannot share a line.
- **Theme lessons** (in the theme files):
  - **Order.** VTM assigns draw levels in file order, so every casing is declared before
    every carriageway. Declaring a road's casing and surface together scars every junction.
  - **Toll tags.** The Mapsforge writer stores `toll=yes` as `tl_yes`, so toll rules match
    `tl_yes|yes|true` and its exact complement. Before that, toll highways vanished.
  - **DPI.** `CanvasAdapter.dpi` is 240, not 320. VTM drops a street label wider than its
    way, and at 320 most residential streets lost their names.
  - **Label priority.** Street labels outrank place and water names.

Verified:

- **Truck:** the map, pinch, the turn card, and guidance on the cluster.
- **Never seen on the truck:** the resize fix, the POI buttons and markers, and the
  divided offer card.
- **Lab and host tests only:** the zoom buttons pressed through the lab receiver, and
  marker colours.
