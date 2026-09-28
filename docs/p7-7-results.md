# P7.7 — the phone app rebuilt: implementation record — 24 September 2026

The slice the [P7 plan](p7-plan.md) §7 calls P7.7: §6 (Home, Setup, Maps & routing,
Settings and Diagnostics; status in words; short labels with help; favorites as a list;
polish), with §5.8's settings (dashboard notifications, the per-app exclusions, call
controls) and §1 items 4–8.

Status: **implemented, host-tested, and looked at in the emulator lab.** Every tab has been
photographed on the emulator and checked by eye (§2.3). **Nothing has run on the Pixel.**
The lab reaches the app over a socket, so it never shows a USB prompt, an attach, a
certificate-policy failure, or a real notification grant, and those states of the status
card have been checked only by the host test (§3).

---

## 1. What was built

### 1.1 One activity, five tabs (§6.1)

`ConnectionActivity` keeps its controls, but they now sit on five pages behind a tab bar
along the bottom of the screen (`PhoneViews.tabBar`). Separate activities would have meant
passing session state around, which the plan rules out. One `SessionController` listener
still renders every tab, as before.

- **Home**: the status card (§1.2), one large Connect/Stop button, the Route card (the
  route in words, the destination box, Compute route and Clear route), and the
  Favorites card (§1.4).
- **Setup**: the checklist (§1.3). The tab's label carries the count, e.g. `Setup (4)`.
- **Maps** (the plan's "Maps & routing"): the installed maps and Import map, the routing
  tiles and Import routing data, the drop folder and its two buttons, and Remove a map
  or tile.
- **Settings** (§1.5).
- **Diagnostics**, shown only with the Diagnostics switch on. It holds the version (§6.5);
  the old `Stage: X (last completed: Y)` and `Last result:` lines word for word, with the
  `source_auth_rejected` note; the accessory and filter; Connect, Stop, Scan accessories
  and Use listed accessory. Then every protocol option (version profile, stop after, TLS
  profile, keepalive, first-use trust, the three captures, open all channels, resume in
  place, wait for focus, keyframe, setup field), the projection options (scene, scene
  area, sensors, sensor location, software encoder, video configuration, frame rate), the
  route details the old routing line carried (destination source and search time), saved
  reports and both exports, the projection report, identity and pins with their three
  buttons, third-party notices, and the live report.

**Nothing was removed.** Four things changed place or shape, and each is recorded here:

- The favorites text list and its Remove picker became the Home list. Remove is now a long
  press on the row.
- The map line and Import map used to hide when the scene was the demo. On their own
  tab they always show; the scene still hides the map side and speed unit rows.
- The live report is composed only while Diagnostics is the open tab. It is the one costly
  part of a render, and every session event renders.
- **Open Diagnostics** on the status card shows the tab for this screen without saving the
  switch. Saving it would also turn on the dashboard header's debug line. Turning the
  switch either way ends the reveal.

**P7.1's behaviour is untouched.** `handleLabIntent`, `handleAttachIntent`, `autoStart` and
its identity hold, `retryAutoStart`, `leaveAfterAutoStart`, `connect`, `scan`, `acquire`,
the permission receiver and `permissionGranted`, `useListed`, `stop`, the detach receiver,
the imports and exports: none was edited. `loadSaved` is the same code, except that its
checkbox extension now takes a `CompoundButton` (the Settings choices are switches), and
the Diagnostics switch also ends a reveal. It still writes one field per change, and the
per-attempt boxes (first use, the captures, sensor location) are still cleared by
`connect()`. Two small additions sit beside the untouched code: a plug-in attach selects
Home first (plan §2: "the activity then shows the Home status"), and the selected tab
survives a rotation.

### 1.2 The status in words — `StatusText.kt` (§6.2, pure JVM)

`StatusText.of(stage, finalCategory, authStatus, autoStart, destination, unplugged,
mismatch)` returns one sentence, at most one `StatusAction`, and a tone, which colours the
card. Every row of the plan's table is implemented as written:

| State | Says | Action |
| --- | --- | --- |
| `IDLE`, auto-start on | Plug in to start projecting | — |
| `IDLE`, auto-start off | Ready to connect | Connect |
| `ARMED` | Waiting for the head unit — plug in the USB cable | Stop |
| `PERMISSION` | Allow USB access on the prompt | — |
| `VERSION`…`SETUP` (incl. `READY`) | Connecting to the head unit… | Stop |
| `PROJECTING` | Projecting · routing to *destination* (or just Projecting) | Stop |
| `USB_PERMISSION_DENIED` | USB access was refused | Try again |
| `AUTH_FAILED` with `auth_status=-3` | The head unit didn't accept this phone's identity | Open Diagnostics |
| `NO_ACCESSORY` | Unplugged | — |
| `TLS_CERT_POLICY` | This head unit isn't trusted yet | Trust and connect |

Decisions beyond the table:

- **How the two failures are recognised.** `-3` is detected the same way the screen has
  flagged it since P3: `finalCategory == "AUTH_FAILED"` and the native report's
  `auth_status == -3`. The certificate-policy failure is the native category
  `TLS_CERT_POLICY` (`Category::TlsCertPolicy` in `aap-session`), which is what an
  unpinned head unit ends with.
- **Ordinary endings** (`CANCELLED`, `PROJECTION_COMPLETE`, `PEER_SHUTDOWN`,
  `HOLD_COMPLETE`, `STAGE_LIMIT`) go back to the idle sentence rather than showing
  a raw name. They are Stop, a planned end, or the vehicle closing the session.
- **Unplugged.** A detach mid-session ends as a stop (`CANCELLED`) or an I/O error, not as
  `NO_ACCESSORY`. So the activity also passes `unplugged` when this attempt's event log
  holds `accessory detached`, which the service writes on a detach. The event log is
  cleared when an attempt begins.
- **Every other category** shows its raw name (`Ended: TLS_TIMEOUT`) with Open Diagnostics,
  so nothing is less visible than before.
- **`ARMED` with a non-matching accessory** says so and offers **Use it anyway**, which is
  `useListed()`. It is still the operator's choice, never automatic (§2's mismatch rule).
- The big button is Connect (or **Try again** after a USB refusal) while idle, and Stop
  otherwise, with exactly the enabled conditions of Diagnostics' own Connect and Stop. The
  card's own button carries only what the big one does not: Open Diagnostics, Trust and
  connect, Use it anyway.
- **Trust and connect** (the card, and Setup's no-pins item) asks for confirmation. It then
  ticks Diagnostics' "Trust the next head unit" box and calls the unchanged `connect()`, so
  it is the same C2 first-use path. `connect()` clears the box once an attempt begins. If
  none began (the identity was still loading), the box is cleared at once, so it cannot arm
  a later Connect.

**The notification.** `StatusText.notification(stage, destination)` returns the same
sentence for the session notification. `ProjectionService` belongs to the dashboard slice
and was not edited, so it still says `Stage: PROJECTING` (§4).

**The route in words** (§6.5). `StatusText.routeSummary(...)` builds "Routing to Home ·
12 mi · 18 min · arrive 6:40 PM":

- The distance comes from `SpeedUnit.distance` in the chosen unit.
- The time comes from `StatusText.duration`.
- The arrival is formatted in the phone's own 12- or 24-hour style (`DateFormat`).
- Waiting, computing and failed each have their own words.
- The arrival is **left out when it is not known**, never guessed.

Where the arrival comes from: `RouteResult` carries no finish time, and `Guide` keeps the
time remaining private. So `RouteClock` (new, main thread) watches `Routing.last` change
through the session listener that every route result already notifies, and records the
wall clock. Both activities install it; a share is the usual way a route starts with the
screen closed. The arrival is that moment plus BRouter's estimate: an ETA as of when the
route was computed. It is dropped once it is in the past. During a drive, the distance
and time are the route's totals, not what remains (§5).

### 1.3 Setup (§6.1)

The pure `SetupCheck.items(...)` returns what is missing, in the plan's order. Each item has
a title, one line of detail, and one button:

| Item | When | Button |
| --- | --- | --- |
| No map installed | `MapData.installed` is empty | Import map (the existing dialog) |
| No routing data installed | `Routing.installed` is empty | Import routing data |
| No routing data for where you are | Tiles are installed, but none is the tile of the phone's newest cached fix (any age; a 5° tile changes over hundreds of km). Needs location; the detail names the tile. | Import routing data |
| Notification access is off | Something wants it (media controls, dashboard notifications, or call controls without the adb grant) and `MediaAccessService` is not enabled | Grant access (the existing APP09 explanation, then Settings) |
| Location is off | Neither location grant is held | Allow location: an explanation, then the runtime prompt for FINE and COARSE. If Android shows no prompt because it was refused for good, the button opens the app's settings. |
| No head unit trusted yet | No pins | Trust and connect |
| Notifications are blocked | `POST_NOTIFICATIONS` is not granted. **Not in the plan's list**: it is the old `notifications_denied` warning, which Diagnostics still shows too. | Opens the app's notification settings |

An empty list shows **Ready**. The import and trust buttons are enabled only when their own
controls are (idle, no route being computed).

The location prompt is **asked only when the operator taps that button**. The app still
never asks on its own (APP09, PhoneStatus's KDoc), and no permission was added.

### 1.4 Favorites (§6.4)

Home lists every saved place as a row: the name large, the coordinates small, the whole
row a 56 dp touch target.

- **Tap** routes there. It calls the same `Navigation.set(…, DestinationSource.FAVORITE)`
  the dashboard tile uses. The event log adds `phone favorite tapped`, and no name.
- **Long press** offers **Rename** or **Remove favorite**.
  - Rename holds the new name to `Favorite.of`'s rules and writes it back in the same
    position through `Favorites.replace`. `Favorites.kt` is unchanged.
  - Remove keeps its confirmation.
  - Both find the place again by value when confirmed, so a list changed behind the dialog
    cannot lose the wrong place.
- **Save current destination** appears while a destination is set. It is one tap and uses
  the destination's own label (else its coordinates). A place already saved at the same
  point is not saved twice.
- **Add by coordinates** is the old Add favorite dialog, now a borderless secondary button.
  It is disabled at `Favorite.MAX`.
- **Privacy is unchanged.** Names and coordinates are shown on the phone. The log gets
  counts only (`favorite added|removed|renamed (n saved)`).

### 1.5 Settings (§6.1, §5.8)

The Settings tab holds:

- **Dashboard**: speed unit, map side, marker colour and day/night.
- **Start when plugged in**.
- **Media**:
  - media controls, with the notification-access line and the grant button, as before
  - audio out
- **Instrument cluster**: turn-by-turn, with its units, force and script options.
- **Notifications and calls**:
  - Notifications on the dashboard, with **Hidden apps · Choose**. This is a multi-choice
    list of the apps in `NotificationFeed.posters()` plus those already excluded, shown by
    app name (else the package name). It is saved through `AppSettings.update`, and set on
    `NotificationFeed.exclusions` at once, so a running session drops those rows. The log
    gets the count only.
  - **Call controls**. When on, a line reads `CallControl.fullControlsGranted` each
    render. It shows "Answer, decline, hang up, mute and keypad work." or "Answer, decline
    and hang up work. For mute and keypad, run once from a computer:" followed by
    `CallControl.GRANT_COMMAND` (selectable). The backends slice had already added both,
    so the real constant is used (it carries `--user 0`).
- The **Diagnostics** switch.

### 1.6 Labels and help (§6.3)

Every control's label is now a few words. Each long explanation is kept **word for word**
as a `*_help` string, and an ⓘ beside the control opens it in a dialog. The ⓘ is a 48 dp
target with a content description "About *label*". The privacy wording is unchanged:
sensor location, the discovery capture, the favorites and the media-access consent.

`strings.xml` (a shared file, edited in place):

- 26 long strings were renamed to `*_help`. The label keys that had held long text
  (`profile_label` and the like) now hold the short label.
- `favorites_line` and `remove_favorite_none` were removed. Their meaning moved to
  `favorites_title`, `favorites_help` and the long-press flow.
- `favorites_none` was reworded.
- The new strings are in one block marked `P7.7`.
- Every string defined is used; lint's `UnusedResources` passes with warnings as errors.

New help text was written for the settings that had none (day/night, dashboard
notifications, call controls, diagnostics). It follows the backends slice's consent text:
notifications are held in memory for the drive only, never saved or reported, and none are
read with the setting off.

### 1.7 Polish (§6.5)

- `app_name` is **OpenAutoAndroid**.
- The theme is `Theme.OpenAutoAndroid`, whose parent is
  `@android:style/Theme.DeviceDefault.DayNight`. It is platform-only, with the accent
  colour and light or dark system-bar icons per mode (`values-night`).
  `DestinationActivity` keeps `Theme.NoDisplay`.
- The colours are in `values/colors.xml` and `values-night/colors.xml`, and the cards are
  `drawable/card_background.xml` and `status_background.xml`.
- Edge-to-edge insets are handled as before. The content clears the status bar and
  cutout, and the tab bar clears the navigation bar, or the keyboard while it is up.
- The version (`versionName (versionCode)`) is at the top of Diagnostics.

### 1.8 Files

- **New:**
  - `StatusText.kt` (pure: `StatusText`, `StatusAction`, `StatusTone`, `Status`,
    `SetupItem`, `SetupCheck`)
  - `PhoneViews.kt` (view helpers)
  - `RouteClock.kt`
  - `res/values/colors.xml`, `res/values/themes.xml`, `res/values-night/colors.xml`,
    `res/values-night/themes.xml`
  - `res/drawable/card_background.xml`, `res/drawable/status_background.xml`
  - `tools/jni-host-test/StatusTextTest.java`
- **Changed:**
  - `ConnectionActivity.kt`
  - `DestinationActivity.kt` (installs `RouteClock`; nothing else)
  - `strings.xml`
  - `AndroidManifest.xml` (the application theme only)
  - `tools/jni-host-test.sh` (registers `StatusTextTest`)
  - `tools/emulator-lab.sh` (§1.9)

### 1.9 The lab (`tools/emulator-lab.sh`)

The lab used to wait for the text `Stage: PROJECTING`, and then `Stage: IDLE`, in a
uiautomator dump of the screen. That line is now on the Diagnostics tab, which a lab run
never opens. **The lab now reads the stage from the saved report**, over `run-as`, as it
already did for `route computed`. The report is rewritten on every session event.

- `PROJECTING` is matched on the event-log line ` stage=PROJECTING `. It is written once
  and kept, so a stage that has already passed by the next poll is still seen.
- `IDLE` is the `[attempt]` section's own line `stage=IDLE` (`grep -x`), which is the
  current stage. The native section's stage lines are lower case and never match.

The report is part of the app's own contract and is independent of the screen layout. Text
on the first screen was the other option offered, but it would tie the lab to a UI string
again, so it was not taken.

**`LAB_SCREENS=1`** (new, optional, any script) photographs the phone screen once the app is
projecting. It finds each tab's label with uiautomator and taps it, and scrolls each page
once:

- `screen-home.png`, `screen-setup.png`, `screen-maps.png` and `screen-settings.png`,
  each with a `-lower` companion
- Diagnostics, after it ticks the switch in Settings
- Home and Settings with the system in dark mode

It checks nothing. It is the only way the lab sees the phone's own screen.

## 2. Verification

### 2.1 Build and host tests

`flock … tools/android-build.sh` passed (exit 0) three times on 24 September 2026:

- once after the first version
- twice after the fixes from looking at the screens (§2.3)

Each run covered clippy, both APKs, `lintDebug` (warnings as errors), `verify-apk` on all
three APKs, and the whole `jni-host-test.sh`, every other slice's suites included.

**`StatusTextTest`: 182 checks, pass.**

- Every row of the §6.2 table, in its wording and action.
- The ordinary endings.
- Seven unknown categories keep their raw name and offer Open Diagnostics, and so does
  `AUTH_FAILED` without `-3`.
- An unplug overrides the category.
- Every stage says something, and none says `Stage:`.
- The notification sentence.
- The route summary: the plan's own example, no arrival when unknown, km, waiting,
  computing, failed, and a lab route with no destination.
- Durations.
- The Setup checklist's order, its conditions, and which features want notification
  access.

### 2.2 Emulator lab

- **`track`**: `LAB_MAP=massachusetts.map LAB_VIDEO_CONFIG=1920x1080 LAB_SCREENS=1`, plus
  three `LAB_FAVORITES`, run `emulator-lab/20260924T144709Z`.
  - **pass**, `receiver exit=0 app exit=0`
  - `track: vehicle events=135 camera moves=124 rasterised=271`
  - "app reached PROJECTING" came from the report-based wait, and the run finished through
    the report-based IDLE wait.
- **`track` on the final APK**, no screens, `emulator-lab/20260924T150144Z`: **pass**,
  `receiver exit=0 app exit=0`, `vehicle events=134 camera moves=123 rasterised=295`.
- **Destination** (`LAB_ROUTING=…/rd5 LAB_DESTINATION=1 LAB_SCREENS=1`, default
  script, final APK), `emulator-lab/20260924T145929Z`: **the app side passes** (`app
  exit=0`, "pass").
  - The address-only geo: was refused.
  - Both shares routed from the vehicle, and the second replaced the first
    (`routing_origin=send routing_start=vehicle`).
  - The route line was drawn, and no marker reached the report.
  - The receiver exited 4, but only on the `taps` script's demo-scene button colours
    ("button 0 decoded as 38,136,125, expected 92,105,198"). The lab intent's default
    scene is the dashboard, which has no such buttons. This is not a P7.7 change.
- **Destination under `track`**, as P6.4 documented it, `emulator-lab/20260924T145625Z`:
  both shares routed. The run then failed its own check, `routing_origin=reroute`: the
  track drive leaves the route, and P6.5's guidance re-routes, which is a third request.
  P6.5 recorded that this pair was not re-run after guidance landed. It needs a fix in the
  script's check, not in the app (§4 item 4).
- An earlier destination attempt had the screens step before the shares, and by the time
  the shares were sent the track receiver had ended the session, so no vehicle position
  was left. `LAB_SCREENS` now runs after every route and destination step.

### 2.3 Looked at by eye

The screens of run `20260924T144709Z` were checked by eye, at 720×1280, 320 dpi, light mode:

- **Home.** The green status card reads "Projecting". The big blue STOP is below it. The
  Route card says "No destination. Share a place…". The Favorites card reads "Favorites · 3
  of 50" with its ⓘ, the hint, the three rows (Home, Work, Grandma's) with coordinates, and
  ADD BY COORDINATES. The tab bar shows Home · Setup (4) · Maps · Settings.
- **Setup** lists four items, each with one button: no routing data, notification access,
  location, and no head unit trusted.
- **Maps** shows region.map (191 MiB), the empty routing data line, and the drop folder.
- **Settings.**
  - The Dashboard rows each have a spinner, and speed unit and day/night have an ⓘ.
    Speed unit and map side are greyed while projecting, as before.
  - Start when plugged in is on.
  - The media and cluster switches are greyed while projecting, as before.
  - Notifications and calls, and Diagnostics, are off.
- **Diagnostics** (reached by the switch) shows the version `0.4.5-lab (21)`, the old
  Stage and Last result lines (`PEER_SHUTDOWN` once the receiver ended the run), the
  accessory and filter, and Connect, Stop and Scan. It also shows the Session and
  Projection cards, the Route details, Reports and the export buttons.

Faults found and fixed from those screens:

- Text built in code came out in the theme's secondary grey. It now uses the primary
  colour, except for captions.
- Diagnostics' spinner rows squeezed their labels to "Vers…". The label and spinner now
  take half the row each.
- The Diagnostics tab label was cut to "Diagnosti…". The tab text is now 12 sp.
- The route box's hint was cut mid-word. The hint now wraps to two lines.

### 2.4 After the fixes

The screens from run `20260924T145929Z` (final APK) were checked by eye:

- **Home** reads "Projecting · routing to Sendmarker Green" on the green card. The Route
  card reads **"Routing to Sendmarker Green · 2.5 mi · 9 min · arrive 3:08 PM"**, so
  `RouteClock` dated a real route. SAVE CURRENT DESTINATION shows under the empty
  favorites, and the hint wraps to two lines.
- **Settings**: the rows are in the primary colour, and "automatic", "mph", "map left"
  and "blue" all fit.
- **Diagnostics**: the spinner rows are stacked, label and ⓘ above a full-width spinner
  ("codec-type", "dashboard", "content", "all"). The tab reads "Diagnostics" in full.

**Dark mode**, from run `20260924T145218Z`:

- Home has a dark card, "Plug in to start projecting" in light text, and CONNECT in the
  dashboard blue with dark text.
- Settings has dark cards, light labels and the blue switch.
- The system bars are dark with light icons.

## 3. Not verified: the list

- **Anything on the Pixel.** The layout at the Pixel's own size and density, its dark mode,
  and GrapheneOS's theme.
- **The status card's failure states on a device**: USB refused and Try again, a
  certificate-policy failure and Trust and connect (which pins a head unit, so it should be
  tried on the truck first), `AUTH_FAILED -3`, and Unplugged after a real detach. The words
  and actions are host-tested; the glue is not.
- **The plug-in start selecting Home.** The lab never sends an attach.
- **The Setup buttons' flows**: the location prompt and its refusal path to app settings,
  the notification-settings shortcut, and the imports from Setup. They open existing
  dialogs, but none was pressed in the lab.
- **Rename, Remove, Save current destination and tap-to-route from the phone list.** They
  are built on the existing `Favorites` and `Navigation` calls, and not pressed in the lab.
- **The exclusion list with real apps.** `NotificationFeed.posters()` fills only while
  dashboard notifications are on and a session runs, and whether app names resolve under
  package visibility is the backends slice's open question too.
- **The call-controls line on a phone**, with and without the adb grant.
- **The arrival time during a real drive.** The lab showed it for a route just
  computed. During a drive it stays the route's estimate as of when it was computed, until
  `Guide` exposes the time remaining (§4 item 2).

## 4. For the coordinator: changes needed in files this slice does not own

1. **The notification (§6.2), `ProjectionService.buildNotification`**, which is the
   dashboard agent's file:

   ```kotlin
   .setContentText(StatusText.notification(SessionController.stage, Navigation.destination?.let(Navigation::describe)))
   ```

   This replaces `getString(R.string.notification_stage, SessionController.stage.name)`.
   `notification_stage` would then be unused and must be deleted from `strings.xml`, or lint
   fails on `UnusedResources`. The notification is re-rendered by the existing
   `renderNotification` listener, so the destination appears in it once it is set.
2. **Time remaining, for an honest arrival during a drive (§5).** In `Guide.kt`:

   ```kotlin
   /** Metres and seconds to the destination from the newest fix on the route, or null. */
   fun remaining(): Pair<Double, Int>? = last?.step?.let { it.destinationMetres to it.destinationSeconds }
   ```

   With that, Home would prefer `Guide.remaining()` over the route's totals, and compute
   the arrival as now plus the seconds. The P7.4 trip panel wants the same numbers.
3. **Live settings for the session wiring** (the backends slice's §7). The exclusion list
   is pushed to `NotificationFeed.exclusions` at once. Nothing on this screen sets
   `NotificationFeed.enabled` or calls `CallControl.setEnabled`. Those belong where a
   session starts and ends, reading `AppSettings`.
4. **`tools/emulator-lab.sh`, `LAB_DESTINATION` under `track`** (§2.2). The P6.4 check
   `routing_origin=send` fails whenever the track drive leaves the route and guidance
   re-routes. Either accept `reroute` as the last origin there, or document
   `LAB_DESTINATION` with a script whose vehicle stays parked. Separately, the `taps`
   script's button-colour checks assume the demo scene, which the lab intent no longer
   selects by default. This is a shared file; neither change was made here.
5. **Settings the dashboard writes** (relayed from the dashboard agent). `dayNight` is
   re-read on resume, as in P7.1. `notificationsPaused` has no control on this screen, so
   it can never be written back stale: every control writes only its own field.

## 5. Amendments this needs — proposed, not applied

- **plan.md P7 rows**: P7.7 "implemented, host-tested, lab screens checked by eye; Pixel
  unverified".
- **docs/p7-plan.md §6.1**: record the Setup tab's extra item (notifications blocked), the
  status card's extra row (ARMED with a non-matching accessory → Use it anyway), and that
  the ordinary endings return to the idle sentence (§1.2).
- **docs/p7-plan.md §6.5**: the arrival is "as of when the route was computed" until `Guide`
  exposes the time remaining (§4 item 2), and the distance and time are the route's totals.
- **spec §5.2**: P7.7 adds no permission. The location prompt uses the already-declared,
  already-optional FINE and COARSE grants, and only when the operator taps Allow location.
