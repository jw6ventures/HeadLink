# The phone app

[← Back to the README](../README.md)

This page covers HeadLink's screen on the phone: what each tab holds, what every setting
does and its default, the Setup checklist, diagnostic reports, and the permissions you
are asked for. How the features work underneath is covered elsewhere:
[connecting.md](connecting.md), [protocol.md](protocol.md), [dashboard.md](dashboard.md),
[navigation.md](navigation.md), [audio-and-speech.md](audio-and-speech.md). Getting map,
routing, OsmAnd, weather, HERE and identity files onto the phone is in [data.md](data.md).

## Shape of the screen

There is one activity, `ConnectionActivity`, with five tabs in a bar along the bottom:
**Home**, **Setup**, **Maps**, **Settings** and **Diagnostics**. Diagnostics shows only
while its switch in Settings is on. When Setup has items, its tab reads `Setup (N)`.

- **Where.** `app/src/main/kotlin/com/jw6ventures/headlink/ConnectionActivity.kt` builds
  every page (`buildViews`) and renders them all from one `SessionController` listener
  (`renderState`). `PhoneViews.kt` has the cards, rows, ⓘ buttons, the large button and
  the tab bar. `StatusText.kt` has the Home sentence and the Setup checklist, both pure
  JVM. `OsmAndMapsViews.kt` has the search suggestions and the OsmAnd maps card.
  `AppSettings.kt` stores the choices.
- **Why one activity.** Separate activities would have had to pass session state around.
  The tabs change where controls sit, not what they do.
- **Why platform views.** The app uses no AndroidX, AppCompat or Material libraries
  (spec.md §2.2). The theme is `Theme.HeadLink`, a child of
  `Theme.DeviceDefault.DayNight`, drawn edge to edge. The content clears the status bar
  and cutout. The tab bar clears the navigation bar, or the keyboard while it is up.
- A USB attach always switches to Home, because the status card says what the attach did.
  The selected tab survives a rotation.
- The live report at the foot of Diagnostics is built only while that tab is open. It is
  the one costly part of a render, and every session event triggers a render.

**The ⓘ pattern.** Every label is a few words. The longer explanation each control used to
show inline is kept as a `*_help` string in `res/values/strings.xml` and opens in a dialog
from an ⓘ beside the control (`PhoneViews.helpButton`, `withHelp`, `labelled`). The ⓘ is a
48 dp target with the content description "About *label*". Rows with no help still keep
the 48 dp slot, so labels and values line up down a card. The help texts are the
authoritative user-facing description of each setting. This page summarises them and does
not repeat them.

## Home

From top to bottom: the status card, the large button, **Connect wirelessly**, the Route
card, the Speech card and the Favorites card.

### Status card and the large button

`StatusText.of` turns the session stage and final category into one sentence, at most one
action, and a colour (quiet, busy, good, problem). The session notification uses the same
sentence (`StatusText.notification`) and has a **Stop** action.

| State | Card says | Action |
| --- | --- | --- |
| Idle, no head unit trusted yet | First time with this car? Tap Trust and connect, then plug in the USB cable | Trust and connect |
| Idle, Start when plugged in on | Plug in to start projecting | — |
| Idle, Start when plugged in off | Ready to connect | Connect |
| Armed, nothing plugged in | Waiting for the head unit — plug in the USB cable | Stop |
| Armed, accessory doesn't match the filter | A USB device is connected, but it doesn't look like a head unit | Use it anyway |
| Waiting for USB permission | Allow USB access on the prompt | — |
| Handshake stages | Connecting to the head unit… | Stop |
| Projecting | Projecting · routing to *destination* (or just Projecting) | Stop |
| `USB_PERMISSION_DENIED` | USB access was refused | Try again |
| `AUTH_FAILED` with `auth_status=-3` | The head unit didn't accept this phone's identity | Import identity |
| `TLS_CERT_POLICY` (head unit not pinned) | This head unit isn't trusted yet | Trust and connect |
| Unplugged, or `NO_ACCESSORY` | Unplugged | — |
| Any other category | Ended: *CATEGORY* | Open Diagnostics |

- Ordinary endings go back to the idle sentence: Stop, a planned end of a diagnostic run,
  or the head unit closing the session (`CANCELLED`, `PROJECTION_COMPLETE`,
  `PEER_SHUTDOWN`, `HOLD_COMPLETE`, `STAGE_LIMIT`).
- An unplug mid-session ends as a stop or an I/O error, not `NO_ACCESSORY`. Home detects
  it from `accessory detached` in this attempt's event log.
- An unknown category is shown by its raw name, never hidden. Diagnostics still shows the
  raw `Stage:` and `Last result:` lines.
- The large button is **Connect** (or **Try again** after a USB refusal) while idle, and
  **Stop** otherwise. It is enabled under the same conditions as Diagnostics' own Connect
  and Stop. The card's own button carries only what the large one can't: Open
  Diagnostics, Trust and connect, Use it anyway.
- **Open Diagnostics** shows the Diagnostics tab for this visit without saving the switch,
  because the saved switch also puts a debug line in the dashboard header.
- **Trust and connect** asks for confirmation, then ticks the per-attempt "Trust the next
  head unit" box and connects. If no attempt starts (the identity was still loading), the
  box is cleared straight away so it can't arm a later Connect. Pinning is covered in
  [protocol.md](protocol.md).
- **Connect** asks for the notification permission if it isn't held, at most once per
  attempt, and arms either way. A plug-in start never asks. It may run behind the lock
  screen where nobody would see a prompt.

The plug-in start (**Start when plugged in**) and its rules (filter match, a pinned head
unit, saved stop-after = projection, waiting up to 30 s for the source identity) are in
[connecting.md](connecting.md). Once an auto-started session is past the USB permission
step, the screen moves itself to the back. A failed start leaves it showing.

**Verified:** host tests (`tools/jni-host-test/StatusTextTest.java`) and the emulator lab.
The wired plug-in start with the phone locked in a pocket has no truck run on record.

### Connect wirelessly

Shown while idle. Until a head unit has been trusted over USB it is hidden, and a line
says to connect over USB first. It goes to the head unit used last (saved as `wireless_device`, outside
`SavedSettings`, and never reported). The first time, it goes to the only paired vehicle;
if there is more than one, or on a **long press**, it asks you to pick from the paired
devices, vehicles listed first. The first tap asks for **Nearby devices**. While the link is
being set up, the button reads **Cancel wireless**. The line under it shows
`WirelessLink.lastStatus`, or a hint when there is none yet. In order, the status reads:
"Reading the truck's wireless setup over Bluetooth…", a retry countdown if the car didn't
finish its setup, "Joining the truck's Wi-Fi…", "Connecting to the truck over its Wi-Fi…",
"Projecting wirelessly", and "Wireless ended (…)". While standing by it reads "Waiting for
the truck. Press Android Auto on the truck's screen…", and once that button is pressed,
"The truck's Android Auto button was pressed. Setting up over Bluetooth…". The same text
appears in the wireless notification (`res/values/wireless_probe.xml`). Once projecting, the large Stop ends a wireless session as it ends a wired one. A tap while
the app is standing by for the car's Android Auto button replaces the standby. The
transport is covered in [connecting.md](connecting.md). **Verified:** truck.

### Route card

- **The route in words** (`StatusText.routeSummary`): "Routing to Home · 12 mi · 18 min ·
  arrive 6:40 · +7 min traffic". While a route is being followed, the distance and time
  are what is left to go (`RouteClock.figures`); otherwise they are the route's totals. The
  arrival uses the saved Clock setting and is left out when it isn't known. Waiting for a
  position, finding a route, and no route found each have their own wording.
- **Destination box.** It takes `lat,lon`, two points `lat,lon lat,lon` (a route between
  them), a `geo:` URI or an OsmAnd link. From three characters, typed text that isn't
  coordinates searches the OsmAnd maps (250 ms after the last keystroke) and lists up to
  eight suggestions. Tapping one routes there. The search is ranked from the vehicle's
  position while projecting, otherwise the phone's last cached fix. With no OsmAnd folder
  chosen, it says where to choose one.
- **Compute route** (needs routing data), **Clear route**, and **Preview route and
  alternatives**, which opens `PreviewActivity` in its own `:preview` process. That
  process keeps VTM's statics apart from the car's map. If the route changed while the
  preview was open (a re-route or a new destination), the choice is not applied.
  Routing, search and preview are covered in [navigation.md](navigation.md).

**Verified:** emulator lab. The preview is emulator-only.

### Speech card

It names the default text-to-speech engine and flags one that holds the network
permission: "Speech: X (this engine can use the network)". **Text-to-speech settings**
opens Android's own screen, where the engine, voice and rate are chosen. The engine is
looked up again whenever the screen returns. See [audio-and-speech.md](audio-and-speech.md).

### Favorites

The card heading reads "Favorites · N of 50" (`Favorite.MAX`). Each row shows the name
large and the coordinates small.

- **Tap** routes there, the same as tapping the place on the dashboard tile.
- **Drag ≡** reorders the list. The row follows the finger by translation and is moved
  once, on release, because removing the view mid-gesture would end the gesture.
- **Touch and hold** offers Rename, Move to top or Remove. Remove asks for confirmation.
  Each action finds the place again by value when it is confirmed, so a list that changed
  behind the dialog never loses the wrong entry.
- **Save current destination** appears while a destination is set. It uses the
  destination's label, or its coordinates, and won't save the same point twice.
- **Add by coordinates** opens a name and location dialog that accepts the same forms as
  the destination box. It is disabled at 50.

Names and coordinates are shown on the phone and the car only. The event log records
counts only ("favorite added (12 saved)"). The dashboard tile and the "recent" row are
covered in [dashboard.md](dashboard.md) and [navigation.md](navigation.md).
**Verified:** emulator lab.

## Setup

A checklist of what a drive still needs. Each item has one button that fixes it
(`SetupItem` and `SetupCheck.items` in `StatusText.kt`, `renderSetup` and `fix` in the
activity). The head unit's trust comes first, since nothing connects without it. Items
marked optional are listed last under their own heading and don't keep Setup from showing
**Ready**: a drive works without them. The items are listed in this order:

| Item | Listed when | Button |
| --- | --- | --- |
| No head unit trusted yet | No head unit is pinned | Trust and connect |
| No map installed | No Mapsforge map is installed | Import map |
| No routing data installed | No `.rd5` tile is installed | Import routing data |
| No routing data for where you are | Tiles are installed, but none covers the 5° square of the phone's last cached fix. The fix can be any age, since the square only changes over hundreds of km. The detail names the tile needed. Needs location. | Import routing data |
| Notification access is off | Something you turned on reads notifications (Media controls, Notifications on the dashboard, or Call controls without the adb grant) and access isn't granted | Grant access: the consent text, then Android's notification-access screen |
| Weather permission not granted | Show weather is on and Breezy Weather's permission isn't held | Grant |
| Location is off | Neither location grant is held. Optional. | Allow location: an explanation, then Android's prompt |
| Notifications are blocked | `POST_NOTIFICATIONS` is not granted. The session still runs, but its Stop may be hidden. Optional. | Opens the app's notification settings |

- The import and trust buttons are enabled only when their own controls are (idle, no
  route being computed).
- Location is asked for only from this button. The app never asks for it on its own
  (APP09).
- If Android won't show a prompt again because it was refused for good, Allow location,
  weather's Grant and the Nearby devices request open the app's system settings instead.

**Verified:** host tests and the emulator lab.

## Maps

| Card | What it holds |
| --- | --- |
| Maps | The installed maps and their total size; **Import map**, **Where to get maps** (opens the Mapsforge download page in the browser) |
| Routing data | The installed tiles; **Import routing data**, **Where to get routing data** (opens the BRouter tile page) |
| Drop folder | The chosen folder and its last check; **Choose drop folder**, **Check folder now** |
| Route preferences | Below |
| Live traffic | Below |
| Voice guidance | Below |
| (last) | **Remove a map or tile** |

- Import, the drop folder and what each file is are covered in [data.md](data.md).
- The drop folder is checked every time the screen comes to the front.
- Import map is disabled during a session.
- **Remove a map or tile** lists every installed file with its size and deletes the one
  you pick, after a confirmation that names it. It is one file at a time, because each is
  a long transfer to put back. It is enabled only while idle, with no route being
  computed and no drop-folder check running: a session reads its maps and the router
  reads the tiles.

### Route preferences

A change saves at once and recomputes the route being shown, from where you are now
(`Navigation.optionsChanged`). Avoided roads cost more but are never closed outright, so
a place that can only be reached that way is still reached. Mechanics and BRouter traps
are in [navigation.md](navigation.md).

| Setting | Default | Effect |
| --- | --- | --- |
| Avoid highways | off | Motorways and their ramps made much more costly |
| Avoid toll roads | off | Toll-tagged roads and booths all but closed |
| Avoid unpaved roads | off | Unpaved surfaces and rough tracks timed even slower |
| Bypass towns | off | Prefers roads outside cities and large towns |
| Allow tracks | off | Opens farm and forest tracks and unclassified roads |
| Top speed | 75 mph (121 km/h) | The profile's top speed, from 56 to 85 mph. It changes routes as well as times. |
| Time adjustment | as estimated | −15% to +30% on every estimated time; changes no route |
| Learn my pace | on | Corrects the time left by how the drive so far compares with the estimate |
| Show other routes on the map | on | Up to two grey alternatives with "+N min" while a route is followed |

The 75 mph default was chosen because BRouter's own 56 mph timed Austin to Dallas at
3 h 42 min. James has not confirmed it. **Verified:** host tests and the emulator lab.

### Live traffic

Off by default. It is the app's only network use (APP10 as amended, spec.md §5.2). The
settings live in their own `traffic` preferences file (`TrafficSettings.kt`,
`TrafficPrefs`), because the key is a secret.

| Control | Default | Notes |
| --- | --- | --- |
| Use live traffic | off | Disabled until a key is saved. The first turn-on shows what is sent, with HERE's terms, and only **Turn on** enables it. |
| HERE API key | none | A masked field, kept out of autofill. Once saved, the card only says "A key is saved." **Remove key** also turns traffic off. |
| Test | — | One incidents request for a fixed area of downtown Dallas. It counts toward the limit and works only with traffic on. |
| Refresh every | 10 min | 5, 10, 15 or 30 min |
| Look ahead | 30 km | 15, 30 or 50 km |
| Monthly request limit | 4,500 | 2 to 1,000,000; HERE's free allowance is 5,000 |

Under the controls, a status line shows the month's count, the last fetch and the last
error. A debug build pointed at the lab's stand-in server says so on the card. Behaviour
is covered in [navigation.md](navigation.md). **Verified:** Test works on the Pixel with a
real key. Everything else is lab-only.

### Voice guidance

| Setting | Default | Effect |
| --- | --- | --- |
| Speak turn prompts | on | The same saved value as the speaker toggle beside End route on the dashboard. Either one changes both, and it is re-read whenever the screen returns. |
| Prompts: Early, Prepare, At the turn | all on | Which of the three prompts per turn are spoken |
| Timing | normal | Earlier (about a third more lead) or later (about a quarter less) |
| Detail | full | Brief: the distance and the turn. Full adds "then …" and "keep right" before an exit. |
| Say street names | on | Spoken only; the dashboard and cluster are unchanged |
| Announce rerouting | on | "Rerouting", once |
| Announce arrival | on | "You have arrived", once |
| Play a sample | — | Speaks a Prepare-style prompt the way a real prompt is spoken, even with prompts off, and then says why if nothing was heard (no engine, no English voice, no Bluetooth audio, in a call, focus lost…) |

Changes go to a running projection immediately (`Guide.voiceChanged`). See
[audio-and-speech.md](audio-and-speech.md). **Verified:** emulator lab with a silent
stand-in engine. Speech has been heard on the truck by ear: a read-aloud notification was
barely audible while turn prompts were fine, which is why both now use
`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` (`Speaker.attributes`). No drive check is recorded.

## Settings

Each change saves at once, but most of these choices are read when a projection starts
(`ProjectionService.startProjection`, `Speaker.bind`), so they take effect at the next
connection. That includes Read aloud and While speaking. Four things reach a running
projection at once: Hidden apps, the Weather card (`Weather.settings`), the Home Assistant
button's switch, and Day/night
when changed from the dashboard's own ☀/☾. The Maps tab's Route preferences and Voice
guidance also apply at once. Controls that configure the session itself (media, audio,
cluster) are enabled only while idle with Stop after = projection.

**Dashboard**

| Setting | Default | Effect |
| --- | --- | --- |
| Speed unit | mph | km/h is the other choice. Sets the header speed and the distances shown on the phone. Hidden with the demo scene. |
| Clock | 24-hour | 12-hour is the other choice. Covers the header clock, trip-panel arrival, weather times and Home's route line. The 12-hour header clock drops AM/PM for lack of room at 726×480. Takes effect at the next projection. |
| Map side | map left | Map right is the other choice. Hidden with the demo scene. |
| Vehicle marker colour | blue | White, yellow, orange, green, red or magenta |
| Day/night | automatic | Day and night are the other choices. Automatic follows the head unit's night signal. The dashboard's ☀/☾ button writes the same setting, so this row is re-read whenever the screen returns. |

The Clock setting is host-tested only and has not been seen rendered.

**Source identity**

What the source identity is, the one in use (its fingerprint, or that it is still being
prepared), and **Import source identity** (a PEM; pins kept; file format in
[data.md](data.md#source-identity-pem)). Enabled only while idle with no identity work
running.

**Starting**

| Setting | Default | Effect |
| --- | --- | --- |
| Start when plugged in | on | A USB attach starts projecting, but only to a head unit already trusted |
| Link the car for wireless | not linked | Shows whether Android's companion-device link exists rather than a saved value. Turning it on asks for Nearby devices, picks the head unit and opens Android's companion-device dialog. Turning it off removes the link. Re-read on every return, since the link can also be removed in Android's settings. |
| Start wirelessly when the car connects | on | Enabled only with the link. On, the car's own automatic start offers are accepted as well as its Android Auto button. |

See [connecting.md](connecting.md) for the start rules, standby and the loop guard.
**Verified:** truck (wireless link and automatic start).

**Media**

| Setting | Default | Effect |
| --- | --- | --- |
| Media controls | on | The head unit's transport buttons control what's playing on the phone. Showing the track needs notification access. A line under the switch says whether access is granted, with a **Grant notification access** button when it isn't. |
| Audio out over USB | off | Sends the app's own audio (a test tone) over the USB audio channel. Phone audio goes over Bluetooth instead; see [audio-and-speech.md](audio-and-speech.md). |

**Instrument cluster**

| Setting | Default | Effect |
| --- | --- | --- |
| Turn-by-turn on the cluster | on | Sends the followed route's next maneuver to the cluster |
| Cluster distance units | imperial | Metric is the other choice. Shown only with turn-by-turn on, as are the next two. |
| Send even if the cluster stays silent | on | Sends without waiting for the head unit to offer the cluster. Required for Ford SYNC 4, which never offers one ([navigation.md](navigation.md)). |
| Demonstration sequence | off | Plays a fixed maneuver script instead of the route |

**Notifications and calls**

| Setting | Default | Effect |
| --- | --- | --- |
| Notifications on the dashboard | off | Alerting notifications in the flex tile. Needs notification access. Off, none is read. |
| Hidden apps · Choose | none | Shown with the above. A multi-choice list of the apps that have posted this session, plus those already hidden. Applies to a running session at once. The list is never reported. |
| Read aloud when tapped | on | Shown with the above. A tapped dashboard notification is spoken. |
| Call controls | off | Answer, decline and hang up from the dashboard. A line under it says whether mute and keypad work too, or shows the adb command that enables them (see Permissions). |

Notification and call content is held in memory for the drive only and never saved or
reported. See [dashboard.md](dashboard.md). **Verified:** emulator lab with the lab
player and Telecom. Neither the Pixel nor the truck is recorded.

**Weather** (off by default)

| Setting | Default | Effect |
| --- | --- | --- |
| Show weather | off | Header weather from Breezy Weather on the phone. Turning it on asks for Breezy's permission. With it off, nothing is read. |
| Status line, **Grant** | — | What the last read found, such as "Waco · updated 12 min ago". Grant appears when Breezy is installed but its permission is missing. |
| Speak weather alerts | on | An alert at or above the level is spoken once per drive, only while the dashboard's voice toggle is on |
| Alerts shown from | Severe | Minor, Moderate, Severe or Extreme |

A caption explains how to tick HeadLink under Breezy's own "update notifier" module so new
weather arrives straight away. That is Breezy's notifier. HeadLink has no update checker of
its own: it makes no network connection except for live traffic and the Home Assistant
button. See
[dashboard.md](dashboard.md) and [data.md](data.md). **Verified:** host tests and the lab
stand-in. Breezy's real field names have not been checked on the Pixel.

**Home Assistant** (`HomeAssistantCard`, its own `home_assistant` preferences file)

One dashboard button that calls a Home Assistant service. Server address (https only),
long-lived access token (masked, never shown again, stored AES-GCM sealed with an Android
Keystore key), service as `domain.service`, and an optional target (entity ids, comma
separated). **Save** checks every field and saves them together; **Check** sends only GETs
(the API, the service list, each target's state) and says what is wrong, with a nearest
match for a mistyped service. *Show the Home Assistant button* can be on only while the
address, token and service are saved, and reaches a running projection at once. See
[data.md](data.md#home-assistant-button) and [dashboard.md](dashboard.md#header).
**Verified:** host tests (`HomeAssistantTest`). Not yet tried against a real server.

**OsmAnd maps** (`OsmAndMapsCard`, its own `poi` preferences file)

| Setting | Default | Effect |
| --- | --- | --- |
| Choose folder | none | A read-only folder grant to OsmAnd's maps, kept apart from the drop folder's read and write grant. The same folder can't be both. A caption says where OsmAnd keeps them (Documents/osmand when set to "Manually specified"). A status line counts the readable maps. |
| Markers per category | 25 | Gas, Food and Shop markers drawn at once (5–100) |
| Look ahead | 50 miles | How far along the route search-ahead looks |
| Corridor width | 0.5 mile | How far either side of the route still counts |
| Candidates to check | 5 | Places whose detour is routed (1–10) |

Distances are stored in miles and shown in the Speed unit. A value set elsewhere (the lab)
that isn't in the list is added to the list rather than lost. **Verified:** emulator lab.
The folder picker on the real phone has not been tried.

**Speech**

| Setting | Default | Effect |
| --- | --- | --- |
| While speaking | Pause media | Lower volume is the other choice. Covers read-aloud, turn prompts and weather alerts. |

**Diagnostics** (off). Shows the Diagnostics tab and adds the debug line to the
dashboard header.

## Diagnostics

The protocol and test options, the identity, and the reports live here. Nothing was
removed when the screen was reorganised; it was moved here.

- **Top card:** the version (`versionName (versionCode)`), the raw `Stage: X (last
  completed: Y)` and `Last result:` lines, with an explanation when the head unit
  rejected the identity, the notification warning, the accessory and filter, **Connect**,
  **Stop**, **Scan accessories** and **Use listed accessory**.
- **Session** and **Projection** cards: the options below. Most are enabled only while
  idle, and only at the stop-after stage where they mean something. `SavedSettings.attempt`
  sends an option as off when the attempt won't reach its stage, but keeps the saved value.

| Option | Default | Notes |
| --- | --- | --- |
| Version profile | `match-1.7` | Also `legacy-1.5`, `legacy-1.5-any-minor`, `match-1.6` |
| Stop after | `projection` | Also `discovery`, `auth`, `version`. A plug-in start won't run a stage other than projection; Connect will. |
| TLS profile | `tls12-aead` | Also `tls12-cbc-compat` |
| Keepalive | `mirror` | Also `plaintext`, `encrypted` |
| Open every channel | off | |
| Resume the encoder in place | off | Off rebuilds the encoder when focus returns |
| Restart video when the car falls behind | on | Congestion recovery ([protocol.md](protocol.md)) |
| Keep Wi-Fi awake during wireless projection | on | 50 ms pings. Off pings once a second, to measure only. |
| Wait for video focus | off | |
| Keyframe interval | 1 s | Or 60 s, which is what Android Auto uses |
| AV setup field 1 | `codec-type` | Or `config-index` |
| Projected scene | `dashboard` | Or `demo`, the numbered test scene |
| Scene area | `content` | Or `frame` |
| Vehicle sensors | `all` | Or `restriction`, `none` |
| Software video encoder | off | |
| Video configuration | `largest` | Or 1920x1080, 1280x720, 800x480, `smallest` |
| Encoder frame rate | negotiated | Or 30, 60 |

**Per-attempt options** are never saved. Only Connect sets them, and a plug-in start
never does. Trust and position are cleared as soon as an attempt begins.

- **Trust the next head unit**: first-use pinning (spec.md §7.4).
- **Capture discovery response**: discovery runs only. The report then holds the head
  unit's name, serials, vehicle ID and Bluetooth address unredacted, so treat it as
  sensitive.
- **Keep unhandled messages**.
- **Save the encoded video**: projection only, tens of MB.
- **Record the vehicle's position**: puts latitude and longitude in the report. It is
  hidden when Vehicle sensors is `none`.

Saving these would let an unattended start pin an unknown head unit, or put the vehicle's
position into every later report without anyone choosing it.

Further cards:

- **Route**: the destination, where it came from, and the routing line.
- **Reports**: below.
- **Wireless probe**: opens `WirelessProbeActivity`. It asks for Nearby devices when
  opened, checks whether a paired device offers wireless Android Auto, can read its
  wireless setup without joining the Wi-Fi, and can connect. Each run is saved as a
  report. See [connecting.md](connecting.md).
- **Identity and trust**: the source identity's SHA-256 fingerprint, the TLS self-test
  result and the pinned head units. **Forget pinned head units** (confirmed; later
  attempts fail until first use is armed again), **Replace source identity** (generates a
  new self-signed one; pins kept), and **Third-party notices**. Importing an identity is
  in Settings. The identity buttons work only while idle with no identity work running. Why some head
  units need an imported identity is covered in [protocol.md](protocol.md).
- **The live report**: the full text of the current report, at the bottom.

## Settings storage

- **`SavedSettings`** (`AppSettings.kt`) is every choice above except traffic, the OsmAnd
  card, favorites and the wireless link. It lives in the private `settings` preferences
  file.
- **One field per write.** Each control writes only its own field, through
  `AppSettings.update`, which is synchronized. The dashboard writes day/night and the
  notification pause from its own thread while this screen sits behind it, and a
  whole-screen write would undo those. Listeners are attached only after the controls hold
  their saved values, so loading writes nothing.
- **Stored by string, not list position** (`"match-1.7"`, `ClockFormat.key`, and so on),
  so reordering a list in a later build can't turn one choice into another.
- **Repaired on read.** A stored value that a list no longer offers, a missing key, or a
  key of the wrong type reads back as the default (`SavedSettings.repaired`,
  `fromStored`). Keys are never renamed once shipped, because a renamed key would read as
  the default. `tools/jni-host-test/AppSettingsTest.java` pins the stored form.
- **Not settings fields**, so no screen write resets them:
  - `last_resolved_night`: the palette the last projection ended in.
  - `wireless_device`: the car's Bluetooth address.
- **Separate files:** `traffic` (holds the key), `home_assistant` (holds the sealed
  token), `poi`, `favorites`, `map` (map
  orientation) and `played_players`.
- **Lab intents never touch saved settings.** In debug builds `handleLabIntent` builds its
  attempt from intent extras, so a lab run can't leave the truck with lab settings. The
  extras are listed in `ConnectionActivity`'s companion object and used by
  [development.md](development.md)'s lab scripts. Release builds ignore them.
- **No backup.** `allowBackup="false"`, and `res/xml/data_extraction_rules.xml` excludes
  every domain from cloud backup and device transfer. A new install, including the move
  to the `com.jw6ventures.headlink` package, starts with defaults. The identity, pins,
  folder grants, favorites and settings all have to be set up again.

## Diagnostic reports

Every connection attempt, wired or wireless, and every wireless probe run is saved as a
plain-text report. Saving doesn't depend on the screen being open.

- **Where.** `ReportPersistence` (`SourceApplication.kt`) saves the current attempt's
  report (`SessionReport.compose`) to `files/reports/` on each session state change. A
  burst of changes is coalesced into one snapshot. `ReportStore` writes to a temporary
  file and renames it over the report, so a reader never sees a half-written file. The
  newest 50 are kept, named `report-<UTC start>-<8 id chars>.txt`.
- **Crash-safe.** An uncaught JVM exception appends a `[crash]` section (stack trace,
  capped at 32 KB) before the process dies. A native crash can't be recorded, but the last
  snapshot remains. At startup the newest report is checked, and Diagnostics' Reports card
  shows "Incomplete from a previous run" if it has a crash section or
  `attempt_complete=false`.
- **Export.** **Export report** writes the current attempt's report. **Export saved
  reports** writes all of them, newest first, into one `headlink-saved-reports.txt`. Both
  use Android's document picker, so the file goes wherever you choose. Nothing leaves the
  phone otherwise.
- **Sections:** app, device, attempt, trust, events, projection, routing, traffic, native
  session, native probe.
- **Never in a report:**
  - The accessory serial.
  - Raw wire data, unless a capture option was ticked.
  - Keys, including the HERE key. The traffic section is counts, minutes and booleans
    only.
  - Vehicle position, unless Record the vehicle's position was ticked for that attempt.
    Otherwise only whether a fix arrived.
  - Destination and favorite names; only which source set the destination.
  - Typed search text; counts only.
  - Notification and call content, and the hidden-apps list.
  - Weather places.
  - Wireless device names and addresses, and the Wi-Fi password.
- **What a report does include:** the source identity fingerprint and the pinned head-unit
  fingerprints, which are certificate hashes.

The rules are in spec.md §10. **Verified:** host tests (`ReportStoreTest.java`). Saved
reports exported from the Pixel have been the evidence for truck runs.

## Permissions, in the order you meet them

Each optional permission is asked for only when you turn on the feature that needs it,
with what it is for explained first (APP09). The manifest's full list and reasons are in
spec.md §5.2 and `app/src/main/AndroidManifest.xml`.

| What you see | When | Effect of refusing |
| --- | --- | --- |
| Android's USB prompt for HeadLink | The first connection to a head unit. Ticking "use by default" lets later plug-in starts through without the prompt. | `USB access was refused` · Try again |
| Notifications (`POST_NOTIFICATIONS`) | The first **Connect**. Never on a plug-in start. | The session runs, but its notification and its Stop may be hidden. Setup lists it. |
| Notification access (a Settings screen, not a prompt) | **Grant notification access** under Media controls, or from Setup. The consent text says it exposes every notification on the phone. | Transport buttons still work. No track info, no dashboard notifications, and no call fallback. |
| Location (fine and coarse, so "approximate" works) | Setup's **Allow location** only | The map and routes use the vehicle's position. Without either, nothing can be routed before projection. |
| Nearby devices (`BLUETOOTH_CONNECT`) | The first **Connect wirelessly**, **Link the car for wireless**, or opening the wireless probe | No wireless |
| Android's companion-device dialog | Turning on **Link the car for wireless** | No wake-up when the car connects |
| Android's Wi-Fi network approval | The first wireless connection to a car's Wi-Fi ([connecting.md](connecting.md)) | No wireless |
| Breezy Weather's `READ_PROVIDER` | Turning on **Show weather**, or Grant | The weather item shows a dash |
| `MANAGE_ONGOING_CALLS` app-op | Never prompted. Run once from a computer: `adb shell appops set --user 0 com.jw6ventures.headlink MANAGE_ONGOING_CALLS allow` (`CallControl.GRANT_COMMAND`, shown under Call controls) | Calls fall back to the dialer's notification: answer, decline and hang up, but no mute or keypad |

About `MANAGE_ONGOING_CALLS`:

- It is a `signature|appop` permission. `pm grant` refuses it, and the permission itself
  is never granted to this app.
- What Telecom honours is the app-op alone, so `CallControl.fullControlsGranted` checks
  the op, not `checkSelfPermission`.
- `--user 0` names the phone's owner on a multi-user phone.
- `CALL_COMPANION_APP` is not used: Telecom hasn't bound on it since Android 11.

## Other entry points

- **Share to HeadLink.** "Route here with HeadLink" appears in the share sheet for text,
  and HeadLink handles `geo:` links. `DestinationActivity` has no window: it sets the
  destination, shows a toast and finishes, leaving you in the app you shared from. See
  [navigation.md](navigation.md).
- **The session notification** opens Home and has **Stop**. While a wireless link is being
  set up, its own notification has **Cancel wireless**.
