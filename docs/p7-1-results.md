# P7.1 — plug-in-and-go and saved settings: implementation record — 23 September 2026

The slice the [P7 plan](p7-plan.md) §7 calls P7.1: §2 (an attach with no attempt in
progress starts one) and §3 (every settings-screen choice outlives the app). It also lays
down the settings model P7.2–P7.9 write into.

Status: **implemented and host-tested. Nothing has run on the Pixel or the truck.** The
emulator lab reaches the app over a socket, not USB, so it cannot deliver an attach; the
whole of §2 on a device is unverified, and §3 lists the first experiments. The lab was
run once as a regression check on the unchanged socket path.

---

## 1. What was built

### 1.1 `DisplayChoices.kt` — the dashboard choices later slices draw (pure JVM)

- **`MarkerColour`**: `BLUE` (default), `WHITE`, `YELLOW`, `ORANGE`, `GREEN`, `RED`,
  `MAGENTA`. Each has an opaque `fill`, a `rim`, a stored `key` and a screen `label`.
  `BLUE` is today's marker exactly: fill `0xFF4E9AF1` (the day palette's accent) and
  today's translucent grey rim `0xB39AA5B1`. The review found that outline visible enough
  (§1 item 12), so the default look is unchanged. The new swatches have a clearly
  contrasting rim: dark on the light fills, light on red and magenta.
- **`DayNightMode`**: `AUTO` (default), `DAY`, `NIGHT`. `resolve(headUnitNight)` gives the
  palette to draw, and `next()` cycles Automatic → Day → Night → Automatic for the ☀/☾
  icon (§5.6).
- Both have `of(key)`, which falls back to the default for a missing or unknown key, and
  `LABELS` in screen order.

### 1.2 `AppSettings.kt` — the settings model

- **`SavedSettings`** (pure JVM data class) holds every choice the screen makes. Each
  default is what the screen showed on a fresh launch before P7.1: the head of each
  spinner's list, sensors on `all`, and media info, cluster turn-by-turn and "send
  regardless" ticked. Those are the screen's defaults, not `AttemptSettings`' (whose
  cluster flags are off because the lab intent relies on that). The P7 fields follow:
  `autoStart` true, `diagnostics` false, `markerColour` BLUE, `dayNight` AUTO,
  `notificationsOnDashboard` false, `notificationExclusions` empty, `notificationsPaused`
  false, `callControls` false.
  - Also saved, beyond the brief's list, because they are on today's screen and §3 names
    only first-use trust and the captures as one-shot (position, below, is kept per
    attempt too): **open all channels**, **resume in
    place** and **wait for focus**.
  - `attempt(pins, once)` is the **only** place outside lab runs that builds an
    `AttemptSettings`. Both Connect and the plug-in start use it. Every stage condition
    from the old `connect()` is carried over unchanged: a capture only for its stage;
    sensors, media, audio, cluster, encoder options and frame rate only for projection;
    position only with a sensor set; force only with the cluster; the script following
    the cluster box but not the stage. `AttemptSettings`' shape is unchanged.
  - `toStored()` / `fromStored(map)` are the stored form, keys included. Choices are
    stored by name (spinner value, `SpeedUnit.label`, `MarkerColour.key`,
    `DayNightMode.key`), never by list position. A missing key, a key of the wrong type,
    or a value a later build no longer offers reads as the default (`repaired()`).
- **`PerAttempt`** holds first-use trust, the three captures and **head-unit position in
  the report** (`sensorLocation`), none of which is saved. APP09 and spec §10 say
  position is "never on unless the operator turned it on for this attempt", so Connect
  clears that box after each attempt, as it does first-use trust. The plug-in start
  always passes `PerAttempt.NONE`, so it never sets position.
- **`AutoStart.decide(stage, enabled, attached, pins, projection, identity)`** is the
  pure §2 rule. It returns `START`, `WAIT`, or the first reason not to. The checks, in
  order:
  1. `BUSY`: not idle.
  2. `OFF`: the setting is off.
  3. `NO_ACCESSORY`.
  4. `MISMATCH`: the accessory doesn't match the filter.
  5. `NO_PINS`.
  6. `NOT_PROJECTION`: the saved stop-after is a diagnostic stage. Plugging in must never
     start a discovery-only or auth-only hold; Connect still runs whatever is selected.
  7. `WAIT`: the identity is still loading.
  8. `IDENTITY_FAILED`: the identity loaded and failed its self-test.

  `WAIT` comes only when every other condition allows a start, and `identity` is
  `READY`, `LOADING` (busy, or not yet reported) or `FAILED`. Each reason is a fixed
  phrase and never names an accessory, a pin or a head unit.
- **`AppSettings`** (Android) moves that map in and out of the `settings`
  `SharedPreferences` file: `load`, `save`, and a synchronized `update(context) { it.copy(…) }`.
  The dashboard will write ☀/☾ and Ⓟ from its own thread while the screen writes from the
  main thread, and two unsynchronized read-modify-writes could undo each other. Writes
  use `apply()`, so they are safe from under the dashboard lock, as `MapPreferences` is.

### 1.3 `ConnectionActivity`

- **Saved settings.** `loadSaved()` puts every saved value on screen, then gives each
  control a listener that writes **only its own field** through `AppSettings.update`.
  Per-field writes matter because the dashboard saves day/night and the pause while this
  screen sits behind it, and a whole-screen write would put back what the screen last
  showed. `onResume` re-reads the day/night row for the same reason. The listeners are
  attached after loading, so loading writes nothing. The hard-coded `isChecked = true`
  and sensor-spinner defaults moved into `SavedSettings`.
- `connect()` now reads `AppSettings.load` plus the five one-shot boxes and calls
  `SavedSettings.attempt`. Its identity check, notification prompt and first-use reset
  are unchanged, and it now clears the position box too.
- **New rows** beside the speed unit, in the screen's plain style: "Start projecting when
  plugged in", "Vehicle marker colour", "Day/night", "Notifications on the dashboard",
  "Call controls", "Diagnostics". Only the first has an effect in P7.1. The others are
  saved and wait for their slices (P7.2, P7.6, P7.8, P7.9, P7.3).
- `PROFILES` moved to `SavedSettings.PROFILES`, and `ConnectionActivity.PROFILES` points
  to it, so the lab intent's default is unchanged.
- **Lab intents are untouched.** `handleLabIntent` still builds its `AttemptSettings`
  from extras and never reads or writes `AppSettings`.

### 1.4 Plug-in-and-go (§2)

`handleAttachIntent` with the stage at `IDLE` now calls `autoStart()`. Before P7.1 it only
wrote the note and returned. The flow:

1. Note `attach intent while IDLE; enumerated=…`, as before.
2. Collect the inputs: the saved `autoStart`, how `Accessories.enumerate` classifies what
   is attached (none, mismatch, match), how many pins `IdentityWork.trust` holds,
   whether the saved stop-after is projection, and the identity's state.
3. **Hold for the identity (`WAIT`).** This is the usual case, not an edge one. Between
   drives the process has usually been killed, so the attach starts a fresh one, and
   `IdentityWork` loads the identity on its worker thread only after the activity is
   already handling the attach. Without a hold, a cold plug-in would never start.
   - The first `WAIT` notes `auto-start held: …` and posts a 30 s give-up (`AutoStart.WAIT_MS`).
   - `retryAutoStart()` runs from the session listener, which the identity's arrival
     notifies. Once the identity is no longer loading, or the stage has left IDLE, it
     decides again from scratch: the accessory still has to be enumerated and matching.
   - A detach ends the hold with a note, and so does the 30 s timeout
     (`… not ready within 30 s; gave up`).
4. Note `auto-start yes|no: <reason>`. On anything but `START`, stop there, which is
   today's behaviour.
5. On `START`: `SessionController.begin(saved.attempt(pins))`, with first-use trust off.
   If `POST_NOTIFICATIONS` is not granted, note it and **don't prompt**: a prompt behind
   the keyguard that nobody sees would hold the attempt at ARMED. The on-screen
   `notifications_denied` line already covers the missing notification. Then `scan()` →
   `acquire()` → permission → `startForegroundService`, exactly as Connect does.
6. **Moving to the back.** When an auto-started attempt gets past `PERMISSION` (the
   service has promoted itself and started the native session), the activity notes it
   and calls `moveTaskToBack(true)`. It does this then, not straight after
   `startForegroundService`: the locked-phone question (§2 item 2) is whether the
   platform treats this activity as foreground at the moment the service promotes
   itself, and stepping back earlier would add a second unknown to that experiment. If
   the attempt fails, the screen stays up so the failure is visible.

An `ARMED` attempt still takes the attach its own way (scan), and any other stage still
only writes the note.

## 2. Verification

### 2.1 Build and host tests

After the coordinator's review changes (the identity hold, position kept per attempt,
projection-only auto-start), the full `flock … tools/android-build.sh` passed on
24 September 2026 with exit 0:

- clippy, both APKs, lint (warnings as errors), and `verify-apk.sh` on all three APKs.
- The whole `jni-host-test.sh`, including every other P7 slice's suites and
  `JniContractTest` (`ok 93 checks`).
- **`AppSettingsTest`: 2582 checks, pass.** The number grew from 594 because the
  exhaustive auto-start check now also varies projection and the identity's three states.

Earlier runs that day were blocked by other slices' work in progress, not by P7.1: a lint
`InlinedApi` error in `MediaAccessService.kt`, a lint run that hung in an endless PSI
walk and was stopped, and non-ASCII characters in three new test files. All of those are
cleared.

`tools/jni-host-test/AppSettingsTest.java` (registered in `tools/jni-host-test.sh`):

- **A first launch builds today's attempt.** Every `AttemptSettings` field is spelled out
  against the pre-P7.1 screen defaults.
- The P7 fields start where the plan says.
- **Stage conditions**, one stop-after at a time (projection, discovery, version), plus
  sensors `none` (position requested through `PerAttempt`), cluster off and resume in
  place.
- The plug-in start never arms trust, a capture or position. No one-shot option is in
  the stored form, and a stored `sensor_location` key turns nothing on.
- The stored form reads back whole with every field off its default. There are 31 keys,
  with values only of `SharedPreferences` types, and choices are stored by name.
- Unknown values and wrong types read back as the defaults, and a blank exclusion is
  dropped.
- `MarkerColour`: keys round-trip and are distinct, fills are opaque, and rims contrast
  (blue keeps today's rim).
- `DayNightMode`: the cycle, `resolve`, and key round-trip and fallback.
- **`AutoStart`, exhaustively**: every combination of stage × setting × attached × pins
  (0, 1, 3) × projection × identity (ready, loading, failed) is checked:
  - `START` comes only when every condition holds.
  - `WAIT` comes only when everything but the identity holds and the identity is still
    loading.
  - The reasons are checked in order, including `NOT_PROJECTION` before `WAIT`, and
    `IDENTITY_FAILED` never waits. The re-decision after the hold is checked for an
    unplug and for Connect pressed meanwhile. Each reason is a plain phrase.

### 2.2 Emulator lab — regression only

`LAB_MAP=massachusetts.map LAB_VIDEO_CONFIG=1920x1080 tools/emulator-lab.sh track`, run
`emulator-lab/20260924T051242Z` with the debug APK built at 00:10:57, after P7.1's
changes: **`receiver_checks=12 receiver_check_failures=0`**.

- The app report shows `lab socket attempt on abstract socket openauto-lab (debug
  build)`, then projection with 746 frames rendered and none dropped, and the map
  `moved=123`.
- There is no `auto-start` note, as expected: the lab never sends an attach.
- Frame 025, checked by eye: the dashboard is unchanged. The map follows the vehicle,
  and the blue arrow with its grey rim is today's marker.

That run came before the review changes. Those changes touch only the attach path and the
position box, and the lab reaches neither: the lab builds its own attempt, and its
position comes from its own extra. So the run wasn't repeated.

The lab can't exercise §2. The app reaches the lab receiver through `adb reverse` on an
abstract socket, started by `handleLabIntent`. No `UsbAccessory` exists and
`USB_ACCESSORY_ATTACHED` is never sent, so the run only shows that the lab path, which
P7.1 must not change, still projects and passes its checks.

## 3. Not verified: the list

- **Any real attach.** The plug-in start has never run: not on the Pixel, not on the
  truck. The decision function is host-tested. The glue around it (enumeration, pins
  read, `begin`, `scan`, the identity hold and its retry, `moveTaskToBack`) is not.
- **The cold start specifically.** The first drive test should be with the app's
  process killed first. Expect the report to show `auto-start held`, then
  `identity pass…`, then `auto-start yes`. The 30 s bound is a guess; the identity's
  real load time on the Pixel isn't measured.
- **The locked-phone start (§2 item 2). Try this first.** Plug the *locked* Pixel into
  the truck with a head unit already pinned and "Start projecting when plugged in" on,
  then read the report:
  - `auto-start yes`, then `completed=foreground`, then `stage=VERSION` means the
    attach-launched activity counted as foreground behind the keyguard. G11's first
    condition holds.
  - `FGS_START_FAILED` means it did not. The next step, per §2, is `setShowWhenLocked(true)`
    on the attach launch path only. P7.1 leaves `startForegroundService` exactly as it
    was, so the first run shows the platform's own behaviour.
  - Then the same with the phone unlocked (§2 item 1), expected to just work.
- **USB permission on an auto-start.** This relies on "Use by default for this
  accessory" being ticked in Android's prompt once, so that the attach-launched activity
  already holds permission. If it doesn't, `requestPermission` shows the system dialog,
  and on a locked phone nobody sees it. The report would show the attempt stopping at
  `PERMISSION`.
- **`moveTaskToBack` behind the keyguard**, and whether the activity comes back to the
  front on unplug. Not observed.
- **Settings surviving an app update** on the Pixel. `SharedPreferences` does this by
  design; the repair logic is host-tested. Not observed on the device.
- **Saved settings on screen**: the new rows and the restore-on-relaunch have not been
  looked at on a phone. The lab starts the app by intent and captures the projected
  picture, not the phone screen.

## 4. For the coordinator

- **`AttemptSettings` is unchanged.** P7.2 (marker colour) and P7.6 (day/night) will
  want the choice in the dashboard's snapshot. There are two ways to get it there:
  - Add `markerColour: MarkerColour = MarkerColour.DEFAULT` and
    `dayNight: DayNightMode = DayNightMode.DEFAULT` to `AttemptSettings`, filled by
    `SavedSettings.attempt`, the way `speedUnit` is.
  - Or have the dashboard read `AppSettings.load(context)` when the session starts, and
    `update` it on a tap.

  Day/night changes mid-session from the head unit, so it probably wants the second.
  The marker colour could go either way.
- **Lab intents**: a lab run gets `AttemptSettings` defaults for everything not in its
  extras, never the saved ones, as before. A lab extra for the P7 settings, if a later
  slice needs one, belongs in `handleLabIntent`, not in `AppSettings`.
- No manifest change: `USB_ACCESSORY_ATTACHED` was already routed to this activity.

## 5. Amendments this needs — proposed, not applied

- **spec §5.2**: record that P7.1 adds no permission. If the locked-phone experiment
  needs `setShowWhenLocked(true)`, that adds no permission either. Revisit this item only
  if it needs more (plan §9 item 3).
- **plan.md P7 rows**: P7.1 "implemented, host-tested; device and vehicle unverified"
  until the §3 experiments are run.
