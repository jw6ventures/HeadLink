# P7 plan — usability — 23 September 2026

Draft. P0–P6 were built to produce evidence: every control on the phone exists because a
gate or an investigation needed it, and the dashboard's header carries whatever a drive
had to record. That was the right shape for a proof of concept, and the concept is proven
— G0–G10 pass on the Ford except G5 (see `plan.md`). P7 makes the same system pleasant to
use every day, without giving up any of the evidence paths.

The plan comes from a UI review on 23 September 2026 (the settings screen, the dashboard
source, and lab frame `emulator-lab/20260923T181846Z/frames/200.png`) and James's
responses to it, which are recorded as decisions in §1.

**Status, 24 September 2026: every slice is implemented and lab-verified; none is
verified on the Pixel or the truck.** The results records are:
[P7.1](p7-1-results.md) (saved settings and plug-in-and-go),
[backends](p7-backends-results.md) (SIM signal, players, notification feed, call controls),
[P7.6 map](p7-6-map-results.md) (the day map theme and switching),
[P7.2/P7.3/P7.6](p7-2-3-6-results.md) (zoom, marker colour, header, day/night chrome),
[P7.7](p7-7-results.md) (the phone app),
[P7.4/P7.5](p7-4-5-results.md) (turn card, trip panel, players),
[P7.4a](p7-4a-focus-results.md) (navigation focus handed back when a route ends) and
[P7.8/P7.9](p7-8-9-results.md) (flex tile, notifications, calls and keypad).
§9's amendments, and those each record proposes, are **proposed, not applied**. James
answered the first round of §10's questions (§1.1), and chose MANAGE_ONGOING_CALLS for
call controls (§5.8).

---

## 0. Scope, and what is deliberately outside it

In scope: how the phone app is laid out and what it remembers; how a session starts;
what the projected dashboard shows, and how it can be controlled from the driver's seat.

Out of scope, stated so it is not assumed:

- **Protocol behaviour.** No P7 slice changes what goes on the wire, other than *when* a
  session starts (§2). Every diagnostic option stays reachable (§6.1); it just stops being
  the first thing on the screen.
- **AndroidX, Compose, Material Components.** spec §2.2's "AndroidX is zero" still holds.
  The phone UI is rebuilt from platform views and a platform theme.
- **Address search, voice guidance, lanes** — still P6 §0's exclusions. Reading a
  notification aloud (§5.8) is **stubbed** in P7: the tap is wired up and recorded, and
  nothing is spoken yet.
- **Weather.** Wanted as a flex-tile source later (§5.9), not built in P7.
- **Speed limits.** A reasonable thing to want in the header, but the Mapsforge files carry
  no `maxspeed`, so it needs a data decision first. §10 keeps it as a question.

## 1. Decisions from the review — 23 September 2026

Numbered as in the review. "Agreed" means the review's suggestion stands as written.

| # | Item | Decision |
| --- | --- | --- |
| 1 | Plug-in-and-go | **Agreed.** Welcome as long as it costs next to nothing in the background. §2 shows it needs no background work and no new permission. |
| 2 | Settings persist | **Agreed.** |
| 3 | Map zoom | **Pinch works on the truck** (this corrects the earlier "Ford sends one pointer" finding). Add **+ and − buttons** anyway. |
| 4 | Split the phone screen | **Agreed.** |
| 5 | Status in plain words | **Agreed.** |
| 6 | Short labels, long text in help | **Agreed.** |
| 7 | Favourites as a real list | **Agreed.** |
| 8 | Polish: name, theme, route summary | **Agreed.** |
| 9 | Header | **Keep the compass, the heading and the bearing.** The rest of the debug text goes behind a feature flag. **Show cell signal and battery**; with more than one SIM/eSIM, **bars for the primary SIM and a row of dots underneath for each additional one (up to 3)**. |
| 10 | Trip panel | **Agreed, but it takes the favourites tile's place while a trip is active.** |
| 11 | Undo banner for new routes | **Dropped.** The trip panel's End route button covers a mistaken route. |
| 12 | Vehicle marker colour | The outline is visible enough. **Make the arrow and dot colour a setting.** |
| 13 | Idle media pane | **List the player apps, each with a Resume button.** |
| 14 | Empty favourites text truncated | **Fix it.** |
| 15 | Map side | Already a setting. No change. |
| 16 | Day/night | **The truck is pinned to dark mode, which is why `is_night` never changed** — this is not true of all Fords. **Support flipping between day and night.** |

Item 16 changes a standing finding: `MapEngine.setNight` deliberately ignores `is_night` on
the grounds that Ford "sends true once per session and never updates it, even in
daylight". Those sessions were all with the truck pinned to dark, so they are evidence of
the pin, not of the protocol. §5.6 plans the day theme on that footing.

### 1.1 Answers to the first open questions — 23 September 2026

| Question | Decision |
| --- | --- |
| What the second tile becomes | **A flex tile** (§5.8). Short term: **call management**, if Android allows it, and **notifications** when turned on, each with a stub for reading it aloud when tapped. A **P-in-a-circle icon** on the dashboard, bold while active, stops notifications appearing in the flex tile. Later, possibly **weather** from an on-device app such as Breezy Weather, the way Gadgetbridge gets it (§5.9). |
| A day/night toggle on the dashboard | **Yes** (§5.6). |
| Network type next to the bars | **Yes** (§5.1). |
| Zoom button placement | **Bottom-right** (§4.1). |

## 2. Plug-in-and-go (P7.1)

**Today.** An attach intent only matters while an attempt is already waiting for one
(`handleAttachIntent`: `stage != ARMED` → note and return). So every drive is: open the
app, press Connect, plug in.

**Proposed.** When the attached accessory matches the filter and **at least one head-unit
certificate is already pinned**, an attach with no attempt in progress starts one straight
away, using the saved settings (§3). The activity then shows the Home status (§6) and
moves itself to the back.

- **First use stays manual.** With no pins, or with "Trust the next head unit" needed, the
  attach does nothing more than it does today. Plugging a phone into an unknown USB host
  must never pin its certificate on its own.
- **A setting, on by default:** "Start projecting when plugged in".
- **Mismatches stay manual**, as they are now: an accessory that does not match the filter
  is shown, never claimed automatically.

**Background cost: none while not driving.** No service runs and nothing polls between
drives. The system already launches `ConnectionActivity` for `USB_ACCESSORY_ATTACHED`
(it is in the manifest), and once "Use by default for this accessory" is ticked in
Android's prompt, the USB permission comes with it. What P7.1 adds is a few lines in the
attach path, which runs once per plug-in.

**No new permission is expected**, and this is the part to prove first, because Android
limits when a foreground service may start:

1. With the phone **unlocked**, the activity is in the foreground when it calls
   `startForegroundService`, so the `connectedDevice` promotion is allowed. Expected to
   just work.
2. With the phone **locked in a pocket**, which is the normal case in the truck, it is not
   certain the attach-launched activity counts as foreground behind the keyguard. The
   first experiment is to plug the locked Pixel into the truck and read the report. If it
   is refused (`FGS_START_FAILED`), the options, in order of preference, are
   `setShowWhenLocked(true)` on the launch path only, then a `USB_ACCESSORY_ATTACHED`
   receiver route. Neither adds a permission. `SYSTEM_ALERT_WINDOW` or anything like it is
   not on the list.

**Unplug.** A detach already ends the session. No change, except that the notification
and the Home status say "Unplugged" in words (§6.2).

## 3. Saved settings (P7.1)

Today only the favourites (`Favorites`) and the map orientation (`MapPreferences`) outlive
the app. Every spinner and checkbox resets on each launch.

**Proposed.** One `AppSettings` object over `SharedPreferences` holds every choice the
settings screen makes, written as it changes and read by both `connect()` and the new
attach path. It is the single source of `AttemptSettings` outside lab runs.

- **Lab intents still win.** `handleLabIntent` builds its `AttemptSettings` from extras,
  as now, and never reads or writes the saved ones, so a lab run cannot leave the truck
  with lab settings.
- **Per-attempt options stay per-attempt.** "Trust the next head unit" and the capture
  options (discovery, unhandled, video) are one-shot by design and are not saved.
- **Defaults are today's defaults**, so the first launch after P7.1 behaves exactly as the
  current build does.

## 4. Map controls (P7.2)

### 4.1 Zoom buttons

Pinch works on the truck, so this is for convenience and one-handed use rather than a
fix. Two more bubbles, **+** and **−**, each one zoom level, clamped to `MapControl`'s
existing range.

- **Placement: bottom-right** (decided, §1.1): a vertical pair, + above −, above the
  attribution plate. The bottom-left stack keeps recentre and compass. Four bubbles of
  `bubbleSize` (a little over 110 px on a 920 px pane at 1080p) in one stack would have
  climbed more than half the pane, and the top of the pane is kept for the turn card
  (§5.3).
- Zooming **does not stop following.** Only panning does, as now.
- The report gains `zoom_presses=N` next to `map_presses`.

### 4.2 Marker colour

A setting with a small fixed set of swatches, each with a known contrasting rim: blue
(today's accent), white, yellow, orange, green, red, magenta. The snapshot carries the
choice, as it does `speedUnit`, so a change repaints the chrome. The dot and the arrow use
the same colour.

## 5. The dashboard

### 5.1 Header (P7.3)

Kept, always drawn:

- **Clock**
- **Speed**, with its unit
- **Compass needle, compass point and bearing** (`N 343°`)
- **Cell signal** (§5.2), with the **network type** beside the primary SIM's bars
  (`LTE`, `5G`, `wifi`), the way a phone's status bar shows it (§1.1)
- **Battery**: a battery glyph that fills with the level, a charging bolt instead of the
  `+`, and the percentage. Turns the warning colour at 15% or less when not charging, as
  now.
- **Two header icons**, left of the signal, each a header-height touch target:
  - **Ⓟ**, the notification pause (§5.8): drawn thin while notifications are allowed,
    **bold and in the accent colour while paused**.
  - **☀/☾**, day/night (§5.6): shows the palette in use; a tap cycles it.

Behind the **Diagnostics** flag (§6.1), drawn as today's small second line only when it is
on:

- `gear`, `driving=`, `night=`, `sats n/m`
- `scale inferred`
- `src vehicle|phone|none`

The turn text leaves the header for the turn card (§5.3). That frees the middle of the
header, and nothing else is proposed for it; empty space is fine there.

The header is currently not a touch target at all. The icons make it one, and P7.3 adds a
`hitHeaderIcon` to `DashboardLayout`, with the same press-and-release-on-the-same-target
rule every other button follows.

### 5.2 Signal for every SIM (P7.3)

`PhoneStatus` reads one `SignalStrength` today. Proposed:

- `SubscriptionManager.getActiveSubscriptionInfoList()`, then one
  `TelephonyManager.createForSubscriptionId(id)` with its own `TelephonyCallback` per
  active subscription. Both need `READ_PHONE_STATE`, which is **already declared and
  already optional**. Without it, the header shows `—` as now.
- **Primary** is the default *data* subscription (`getDefaultDataSubscriptionId`), because
  that is the one the phone's own status bar leads with. Its level (0–4) is drawn as
  **four bars**.
- **Each additional subscription, up to 3,** is a row of **dots under the bars**: one row
  per SIM, four dots per row, filled up to its level. An inactive or out-of-service
  subscription shows hollow dots rather than disappearing, so a missing row always means
  "no such SIM".
- Subscriptions added or removed mid-session are followed through
  `OnSubscriptionsChangedListener`.
- The report records the **count** of subscriptions and each one's level. Never a carrier
  name or a number.

**Evidence needed:** the Pixel is the only phone in hand. The dot rows are
host-testable, but a real second subscription has to be seen once to trust the
per-subscription callbacks on a DSDS phone.

### 5.3 Turn card (P7.4)

The next maneuver moves from the header onto a card across the **top of the map pane**:

- A **maneuver icon** drawn on the canvas from the same `Maneuvers` type the cluster is
  sent, so the two displays can never disagree.
- The **distance**, large, in the speed unit's system (`SpeedUnit.distance`, as now).
- The **street name** (P6.7), on its own line, truncated only when it has to be.
- `Rerouting…` and `You have arrived` take the card's text line. The card goes away with
  the route.

Drawn with a solid backing plate, like the attribution, because the map under it can be
any colour.

### 5.4 Trip panel (P7.4)

**While a trip is active, the trip panel replaces the favourites tile.** When the route is
ended or reached, the favourites come back, keeping their scroll position.

It shows:

- **Arrival time**, from `RouteFollower`'s `destinationSeconds` against the phone clock
- **Time and distance remaining**
- **The next two maneuvers after the current one**, small, each with its icon and
  distance
- **End route**: a full-width button along the bottom of the tile, the size of a media
  button. It is the only way to cancel a route from the car, and by the §1 item 11
  decision it is also the fix for a mistaken favourite tap or long press, so it is
  never hidden while a route exists.

End route calls the same `Navigation.clear()` path the phone's Clear route button does,
releases navigation focus, and clears the cluster.

**Settled in the build, 24 September 2026** ([p7-4-5-results](p7-4-5-results.md) §4):
- **Arrival ends the route by itself, 30 s after "You have arrived"** (`Guide.ARRIVAL_HOLD_MS`),
  through the same `Navigation.clear()`. Without that, "favourites return when the trip
  is reached" and "End route is never hidden while a route exists" contradict each other.
- **Ending a route hands navigation focus back to the head unit**, so the truck's own
  navigation can have the cluster again; the next route asks for it again. P6.5 kept
  focus until the session ended. The change is in the Rust session code
  ([p7-4a-focus-results](p7-4a-focus-results.md)). How SYNC 4 reacts needs the truck.

The second tile ("vehicle, not configured") becomes the flex tile (§5.8), which a trip
does not displace.

### 5.5 Media pane (P7.5)

**When something is playing or paused with a session**, nothing changes.

**When nothing is playing**, the pane lists **player apps, each with a Resume button**,
instead of `no media · grant n…`:

1. **Players with a live session**: `MediaSessionManager.getActiveSessions`, which needs
   notification access (already offered). Resume calls `transportControls.play()` on
   that session.
2. **Players with no session left**, e.g. after the app was killed: Android's media
   resumption. Apps that support it export a `MediaBrowserService` and answer a
   connection carrying `BrowserRoot.EXTRA_RECENT` with their most recent item; Resume
   connects with that hint and plays it. Finding them needs a `<queries>` entry for
   `android.media.browse.MediaBrowserService`. That is **not a permission**, but it is a
   manifest change that spec §5.2's baseline should mention (§9).

Ordered by most recently active. **Two rows fill the pane** (settled in the build: a row at
exactly media-button height would leave room for only one), and only apps with a launcher
activity are listed, since system browse services such as Bluetooth's are not players. The row targets are the media
buttons' height, the same argument as the favourites rows. Without notification access,
source 2 still works, and the pane says once, small, that the phone can list more with
access turned on. It is no longer the headline.

Player names are listening history: the report records how many were listed and whether
Resume was pressed, never which app.

### 5.6 Day and night (P7.6)

A setting, **Day/night: Automatic · Day · Night**, default Automatic. It is also
**switchable from the dashboard** (§1.1): the ☀/☾ header icon (§5.1) cycles Automatic →
Day → Night, and the choice is saved like the compass's orientation, so it carries into
the next session. In Automatic, the icon shows whichever palette the head unit's signal
has selected, with a small "A" to show it is automatic.

- **Automatic** follows the head unit's `is_night` **every time it changes**, not only at
  subscribe time. Today `Dashboard.restriction()` already updates `night` on every event;
  what is missing is a day palette and a day map theme worth switching to.
- **A real day palette.** Today's `Palette.DAY` is a slightly lighter dark scheme. P7.6
  adds a light one with the same roles and contrast checks, and keeps today's `DAY`
  values as the new `NIGHT` baseline.
- **A day map theme**, `dashboard-light.xml`, original work against the rendertheme schema
  like `dashboard-dark.xml`, so no VTM theme is vendored. `MapEngine.setNight` switches
  between the two instead of applying one theme once, and its long comment explaining
  why it ignores night gets rewritten around §1 item 16.
- **Switching must not stutter.** A theme change drops VTM's tile cache. It happens at
  most a few times a drive, but it is timed in the lab and on the Pixel before it ships.

**Evidence needed:** one session with the truck's display *not* pinned to dark, with the
headlights toggled or across dusk, to see Ford send a second `NIGHT_DATA`. Until then,
Automatic is proven only on the lab receiver.

### 5.7 Small fixes (P7.5)

- **Empty favourites text** ("none saved — add places o…"): two centred lines, "No
  favourites yet" and "Add them on the phone", sized to fit the tile rather than
  truncated.

### 5.8 The flex tile: calls (P7.9) and notifications (P7.8)

The second tile under the media pane is no longer a fixed feature. It shows **whichever
source has something to say**, highest priority first:

1. **A call**, ringing or in progress
2. **Notifications**, when turned on in Settings and not paused with Ⓟ
3. *(later)* **Weather** (§5.9)
4. Otherwise a quiet empty state: "No notifications", or "Notifications paused" while Ⓟ
   is on. Never "not configured".

The trip panel does not displace it. A trip takes the favourites tile (§5.4), so while
driving somewhere, one tile is the trip and the other is whatever is happening.

**Calls: optional call controls (P7.9).** James wants call management as an opt-in
permission, with **mute/unmute** and ideally a **pop-up keypad** (23 September 2026). That
rules out the route this section first proposed, pressing the dialer's call-notification
actions. The notification only offers Answer, Decline and Hang up, and there is no mute
and no DTMF in it. `ANSWER_PHONE_CALLS` doesn't help either: `TelecomManager` gives it
accept and end, nothing more. Mute and tones only exist on Telecom's in-call API.

**The route: a companion `InCallService`.** An `InCallService` that draws no phone UI
receives each `Call` while the real dialer stays the dialer. This is how watch
companion apps control calls. It provides everything asked for:

| Control | API |
| --- | --- |
| Answer / Decline | `Call.answer(VideoProfile.STATE_AUDIO_ONLY)` / `Call.reject(false, null)` |
| Hang up | `Call.disconnect()` |
| Mute / unmute | `InCallService.setMuted(Boolean)`; the state comes back through `onCallAudioStateChanged`, so a mute pressed on the truck or the phone shows too |
| Keypad tones | `Call.playDtmfTone(c)` on press, `Call.stopDtmfTone()` on release, so a held key is a held tone, as on a real keypad |
| Caller | `Call.Details`: the display name, else the number |

**Correction, 24 September 2026: which permission gets the service bound.** This section
first said `CALL_COMPANION_APP` was enough. The backends work read Telecom's
`InCallController` in AOSP ([p7-backends-results](p7-backends-results.md) §4.2) and found
that was true only on Android 10. From Android 11 on, Telecom binds a no-UI in-call
service only for an app holding `CONTROL_INCALL_EXPERIENCE` (privileged) or
`MANAGE_ONGOING_CALLS`. From the API 36 SDK's framework manifest:

| Permission | protectionLevel | Meaning here |
| --- | --- | --- |
| `CALL_COMPANION_APP` | `0x0` normal | Granted at install, but **no longer gets the service bound** (Android 11+). |
| `MANAGE_ONGOING_CALLS` | `0x42` signature + app-op | Not granted to an ordinary app, but the app-op can be allowed **once, from a computer**: `adb shell appops set --user 0 org.openautoandroid.source MANAGE_ONGOING_CALLS allow`. Android also grants it to watch-companion apps, which this app is not and won't claim to be. |
| `ANSWER_PHONE_CALLS` | `0x2001` dangerous + app-op | A runtime prompt; answer and end only. Not needed. |
| `CONTROL_INCALL_EXPERIENCE` | signature/privileged | Out of reach. |

Without the app-op, calls fall back to the dialer's `CallStyle` notification actions
(built; it reads only notifications in `Notification.CallStyle`): answer, decline and hang
up, **no mute and no keypad**. **Found in the lab, 24 September 2026:** the app-op alone is
what Telecom honours; the permission itself is never granted to this app, and does not
need to be ([p7-8-9-results](p7-8-9-results.md) §3.1). **Decided 24 September 2026: P7.9
declares `MANAGE_ONGOING_CALLS`**, and James grants it over adb. Telecom does bind a newly enabled service during a
call already in progress, per the same source reading.

**Making it optional.** The switch is the app's own, and it is enforced by Telecom rather
than by our code. The in-call service is
declared **disabled** in the manifest (`android:enabled="false"`, protected by
`BIND_INCALL_SERVICE` as Telecom requires). The "Call controls" setting (off by default)
explains what turning it on does, and only then does the component get enabled through
`PackageManager.setComponentEnabledSetting`. With the setting off, Telecom has nothing to
bind, so the app never sees a call.

**No background cost.** With the setting on, the component is enabled **only while a
session is projecting** and disabled when the session ends. Between drives, Telecom
doesn't wake the app for a call. On launch, the app disables the component if no session
is running, which covers a crash that left it on. What this costs is to be checked on the
Pixel: whether Telecom binds a companion that was enabled *during* a call already in
progress, or only from the next call.

**On the dashboard:**

- **Ringing:** caller, with **Answer** (green) and **Decline** (red), each half the tile's
  width and the full height below the name. These are the biggest targets on the
  dashboard after the media buttons, on purpose.
- **In a call:** caller and a running duration, over a row of three buttons: **Mute**
  (lit while muted, and the tile's header says "Muted", because a forgotten mute is the
  common mistake), **Keypad**, and **Hang up** (red).
- **The keypad pops up over the right half** of the dashboard (the media pane and both
  tiles), not inside the tile, which is too small. The flex tile is about 455 px square at
  1080p, and twelve keys there would be roughly 140 × 100 px each. Over the half, they are
  about 300 × 200. The map stays visible. The keypad has 1–9, *, 0 and #, a strip along
  the top echoing the keys pressed this call, and **Close**. It closes itself when the
  call ends. The keys follow the press-and-release-on-the-same-target rule for the echo,
  but the tone starts on press and stops on release or cancel.
- **Fallback, only if Telecom won't bind the companion on the Pixel:** the dialer's
  `Notification.CallStyle` actions (`EXTRA_ANSWER_INTENT`, `EXTRA_DECLINE_INTENT`,
  `EXTRA_HANG_UP_INTENT`) through the notification listener. Answer, decline and hang up
  would work; mute and keypad would not.

**Evidence needed, and it could change the design:** the phone is paired to the truck
over Bluetooth for audio (A2DP). If it is also paired for calls (HFP), SYNC shows its own
call screen and carries the call audio, and may cover the projection while it does. Mute
and DTMF still go through Telecom, so both still work, but the card might be hidden behind
SYNC's own screen. P7.9 starts with one call received while projecting, recording what SYNC
shows and whether the companion was bound.

**Privacy.** Keypad digits are often PINs and account numbers. They are drawn in the echo
strip for that call only, held nowhere else, and **never logged**. The report counts key
presses and nothing more. Caller names and numbers stay out of the report too (§9).

**Notifications: off by default, a setting to turn on.**

- **Which notifications:** ones that alert. Not ongoing, not silent or minimum
  importance, not group summaries, not media or call notifications (those have their own
  panes and APIs), and never this app's own. Settings has a per-app exclusion list, filled from
  the apps that have actually posted one.
- **What is shown:** newest first, one row each: the app's name and the sender or title,
  large; one line of text, small. The full text is not drawn. At a glance from the
  driver's seat, "who and which app" is what a driver needs, and the rest is what reading
  aloud is for. The rows scroll with the same gesture and slop rules as the favourites
  list (P6.6), so that code is reused rather than written twice.
- **Tap a row: read it aloud — stubbed.** The tap is detected with the favourites'
  press-and-release rule, the row flashes, and a `Speaker.read(notification)` hook is
  called that does nothing yet except count the tap in the report. When speech is built,
  Android's `TextToSpeech` over the A2DP route is the likely shape (see the A2DP
  proposal), but that is its own slice.
- **Gone from the phone, gone from the tile:** `onNotificationRemoved` drops the row.

**Ⓟ, the notification pause** (§5.1): a header icon, thin while notifications are
allowed, bold and accent-coloured while paused. While paused, no notification is drawn in
the flex tile, and the tile says "Notifications paused". The pause is **saved** like the
compass orientation. Turning it off shows whatever notifications are still in the phone's
shade, since the listener can read the active ones, so nothing that arrived during the
pause is lost. It doesn't touch the phone's own Do Not Disturb, and it doesn't hide calls
(§10 asks whether it should).

**Privacy.** This is the first feature that makes the notification listener actually
*read* notifications. Today both the manifest comment and the consent text promise the
opposite ("it reads none and stores none", `media_access_prompt`). That promise has to be
rewritten, truthfully, before P7.8 ships, and it has to stay true for anyone who never
turns notifications on:

- Notifications are held in memory for the session only and never written anywhere.
- With the setting off, the listener reads nothing, as today. Calls don't go through it
  (the in-call service above is its own, separate opt-in), except in the fallback.
- The report carries counts only: notifications shown, taps, read-aloud stubs, calls
  answered, declined and hung up, mute toggles, keypad presses. Never an app name, a
  sender, text, a number or a digit.

§9 lists the amendment.

### 5.9 Weather — future, research first

Wanted later as a flex-tile source (§1.1), without adding `INTERNET`: the forecast has to
come from another app on the phone. James already uses **Breezy Weather**, which sends
weather to **Gadgetbridge**. Before anything is designed, this needs finding out:

1. **How Breezy shares it.** As far as known, its "send Gadgetbridge data" option
   broadcasts Gadgetbridge's generic weather intent with a JSON `WeatherSpec` payload. Is
   that broadcast addressed to Gadgetbridge's package names only, or can any app that
   declares the receiver get it? If it is addressed to Gadgetbridge only, this app can't
   pose as Gadgetbridge (and shouldn't).
2. **Whether Breezy offers anything else**, such as a content provider or an explicit
   share-to-app list, or would accept one upstream.
3. **What the payload carries**: current conditions, hourly and daily forecast, and alerts.
   That decides what the tile can show.

No slice number until those are answered. The tile itself is small: conditions, a
temperature, the next few hours. Getting the data is the hard part.

## 6. The phone app (P7.7)

`ConnectionActivity` builds about 45 controls into one scrolling column, protocol options
first. P7.7 rebuilds it from platform views, with no AndroidX.

### 6.1 Structure

- **Home**: the status card (§6.2), one large **Connect/Stop** button, the current
  destination and route summary with **Clear route**, and the favourites (§6.4). What
  someone who is not debugging sees.
- **Setup**: a checklist of what is missing, each item with the one button that fixes it:
  - no map installed
  - no routing tile covering the current position or destination
  - notification access off ("the car can't show what's playing or list players")
  - location off
  - no head unit pinned yet ("plug in and tap Trust")

  An empty checklist says "Ready". The dashboard stops being where these are discovered.
- **Maps & routing**: import, drop folder, the installed files, removal.
- **Settings**: speed unit, map side, marker colour, day/night, start when plugged in,
  media controls, audio out, cluster turn-by-turn and its units, call management,
  notifications on the dashboard and their per-app exclusions (§5.8), and the
  **Diagnostics** switch.
- **Diagnostics**, shown only with the switch on: version, TLS and keepalive profiles,
  stop-after, video preference, frame rate, keyframe, setup field, scene area, scene,
  sensors and sensor location, the capture options, software encoder, open all channels,
  resume in place, wait for focus, force cluster, scripted cluster, identity and pins,
  report export, the live report. **Nothing is removed; it is moved.** The same switch
  gates the dashboard header's debug line (§5.1).

Implementation: one activity with a small tab bar switching between these views is
enough. Separate activities would mean passing session state around for no gain.

### 6.2 Status in words

The status card turns `Stage` and `finalCategory` into one sentence and, where there is
one, one action:

| State | Says | Action |
| --- | --- | --- |
| `IDLE`, auto-start on | Plug in to start projecting | — |
| `IDLE`, auto-start off | Ready to connect | Connect |
| `ARMED` | Waiting for the head unit — plug in the USB cable | Stop |
| `PERMISSION` | Allow USB access on the prompt | — |
| handshake stages | Connecting to the head unit… | Stop |
| `PROJECTING` | Projecting · routing to *destination* | Stop |
| `USB_PERMISSION_DENIED` | USB access was refused | Try again |
| `AUTH_FAILED` (`-3`) | The head unit didn't accept this phone's identity | Open Diagnostics |
| `NO_ACCESSORY` | Unplugged | — |
| certificate policy failure | This head unit isn't trusted yet | Trust and connect |

Any category not in the table shows its raw name plus "Open Diagnostics", so nothing gets
less visible than it is today. The notification uses the same sentence instead of
"Stage: PROJECTING".

### 6.3 Labels

Every label becomes a few words. The explanation each one carries now (they are careful
and worth keeping) moves behind an ⓘ that opens it in a dialog. The strings stay in
`strings.xml`, split into `*_label` and `*_help`.

### 6.4 Favourites

A real list on Home: tap routes there, long-press offers Rename and Remove, and **Save
current destination** is one tap when a destination is set, since a place just shared from
OsmAnd is the likeliest one to keep. Add-by-coordinates stays, as a secondary button. The
`Favorite.MAX` limit and the privacy rules stay as they are.

### 6.5 Polish

- The app name drops "POC": **OpenAutoAndroid**. The version stays visible in Diagnostics.
- A current platform theme, `Theme.DeviceDefault.DayNight` or the Material platform
  equivalent, with edge-to-edge insets, which already work.
- Route summary in words: "Routing to Home · 12 mi · 18 min · arrive 6:40", not
  coordinates.

## 7. Slice decomposition

Ordered so the daily-drive wins land first and the big rewrite lands on top of a settings
model that already exists.

| Slice | What it delivers | Depends on | Gate |
| --- | --- | --- | --- |
| **P7.1** | Plug-in-and-go (§2) and saved settings (§3). Locked-phone start proven on the truck first. **(done, lab-verified — [results](p7-1-results.md))** | — | proposed **G11** (§8) |
| **P7.2** | Zoom buttons and marker colour setting (§4) **(done, lab-verified — [results](p7-2-3-6-results.md))** | P7.1 (to save the colour) | none |
| **P7.3** | Header: kept items, Diagnostics flag, battery glyph, multi-SIM signal with network type, header touch targets (§5.1–5.2) **(done, lab-verified — [results](p7-2-3-6-results.md))** | P7.1 | none |
| **P7.4** | Turn card and trip panel with End route (§5.3–5.4) **(done, lab-verified — [results](p7-4-5-results.md), [focus](p7-4a-focus-results.md))** | — | proposed **G11** (§8) |
| **P7.5** | Media pane players with Resume, empty-favourites fix (§5.5, §5.7) **(done, lab-verified — [results](p7-4-5-results.md))** | — | none |
| **P7.6** | Day/night setting and ☀/☾ header toggle, light palette, day map theme (§5.6) **(done, lab-verified — [map](p7-6-map-results.md), [chrome](p7-2-3-6-results.md))** | P7.1, P7.3 | none; needs the unpinned-display drive |
| **P7.7** | Phone app rebuilt: Home, Setup, Maps & routing, Settings, Diagnostics; plain-language status and notification; short labels; favourites list; polish (§6) **(done, lab-verified — [results](p7-7-results.md))** | P7.1; best after P7.2–P7.6 add their settings | none |

| **P7.8** | Flex tile: notifications with a read-aloud stub, Ⓟ pause, the empty state; consent text rewritten (§5.8) **(done, lab-verified — [results](p7-8-9-results.md))** | P7.3 (Ⓟ in the header), P7.1 | none |
| **P7.9** | Optional call controls: no-UI `InCallService` under the adb-granted `MANAGE_ONGOING_CALLS` app-op (CallStyle-notification fallback), enabled only by the setting and only while projecting; answer, decline, hang up, mute, pop-up DTMF keypad (§5.8). Starts with one call received while projecting, to see what SYNC does and whether the companion binds. **(done, lab-verified on both routes — [results](p7-8-9-results.md))** | P7.8 (the tile) | none |
| *(later)* | Weather flex source (§5.9) | research | — |

Until P7.7 lands, the settings P7.2, P7.3, P7.6, P7.8 and P7.9 add go into today's screen, next
to the speed unit.

Each slice is host-tested for its drawing and state, like P6.6, and lab-rendered with a
frame from `tools/emulator-lab.sh` checked by eye. The lab's existing checks read the
report, not the header text, so gating the debug line should break none of them. That
gets confirmed on P7.3's first lab run rather than assumed.

## 8. Proposed gate

| Gate | Required evidence | Interpretation |
| --- | --- | --- |
| **G11** hands-off drive | With the phone locked in a pocket: plug in, projection starts without touching the phone; a favourite is tapped **on the head unit**; the route is followed with the turn card and trip panel; **End route** on the head unit ends it (or arrival does); unplugging ends the session cleanly. Operator observation plus the session report. | The system is usable without the phone in hand, which is the point of P7. |

It also closes two P6 open ends as a side effect: the first favourite tapped on a vehicle
(P6.6) and the first street names seen on the truck (P6.7).

## 9. Spec and plan amendments this needs — proposed, not applied

1. **spec §11.1** — add **G11** as above.
2. **spec §12 / plan.md** — a `P7` section and rows, every task unchecked until evidence
   exists.
3. **spec §5.2** (permission baseline) — record that P7.1–P7.8 add **no permission**. P7.5 does
   add a `<queries>` element for `android.media.browse.MediaBrowserService` (§5.5), which
   is a package-visibility declaration, not a permission, and should be listed so the
   baseline stays complete. If P7.1's locked-phone experiment needs anything beyond
   `setShowWhenLocked`, this item is revisited before the slice lands.
   **P7.9 is the exception:** it declares `MANAGE_ONGOING_CALLS` (signature + app-op,
   decided by James on 24 September 2026). It is never granted by the install, only once
   from a computer with
   `adb shell appops set --user 0 org.openautoandroid.source MANAGE_ONGOING_CALLS allow`
   (`--user 0` because the Pixel has more than one user). It also adds a
   disabled-by-default `InCallService` behind `BIND_INCALL_SERVICE`, enabled only by the
   operator's Call controls setting, only while projecting, and only when the app-op is
   allowed (§5.8). `CALL_COMPANION_APP`, proposed earlier, is not declared: it no longer
   gets the service bound on Android 11+. The baseline should record all of this, with
   those conditions.
4. **spec §10** (reports) — the new fields follow the existing rule: subscription count and
   levels but no carrier; player count and Resume presses but no app names; trip panel and
   End route presses but no destination; notification and call counts but no app, sender,
   text or number; keypad press counts but **never a digit**.
5. **spec §8.2** (dashboard) — the header's contents as §5.1, including its two icons; the
   turn card; the trip panel replacing the first tile during a trip; the flex tile;
   day/night.
6. **APP09 / the notification-access consent** — the notification listener goes from
   "reads no notification" to reading alerting notifications when dashboard notifications
   are on (and call notifications only in P7.9's fallback). The manifest comment, the
   `MediaAccessService` KDoc and `media_access_prompt` are rewritten to say exactly that,
   and it stays opt-in per feature (§5.8).
7. **The P5.2 night-mode finding** and the `MapEngine.setNight` rationale — reworded
   around §1 item 16: `is_night=true` was the operator's pinned display setting, not a
   property of SYNC 4.0.

## 10. Open questions

Questions 1–3 and 5 of the first round are answered (§1.1).

1. **Speed limit** in the header needs `maxspeed` in the map data (§0).
2. **Does Ⓟ hide calls too?** Built as **no** ([p7-8-9-results](p7-8-9-results.md)): calls
   still ring through while paused, because a missed call is costlier than a missed
   message. Say if Ⓟ should mean "quiet" for both.
3. **Notification text while moving.** §5.8 draws one line of text whatever the vehicle is
   doing. The head unit's `driving_status` could hide that line while moving and show it
   when parked, as Android Auto does. Worth it, or is one line fine?
4. **Weather source** (§5.9): the three research questions there, before any design.
