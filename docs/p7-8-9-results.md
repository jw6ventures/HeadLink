# P7.8 and P7.9 — the flex tile, notifications, Ⓟ, the call card, mute and the keypad: implementation record — 24 September 2026

Dashboard part 2b, the last dashboard slice of the [P7 plan](p7-plan.md): §5.8 (the flex
tile, its notifications with the read-aloud stub and the Ⓟ pause, P7.8; the call card, mute
and the pop-up DTMF keypad, P7.9, as corrected on 24 September 2026) and §1.1's answer on
what the second tile becomes. It builds on the backends
([p7-backends-results](p7-backends-results.md) §3, §4, §6.3, §6.4) and on part 2a's hooks
([p7-4-5-results](p7-4-5-results.md) §6). It also fixes the idle media pane's player list
that the part-2a review found listing system services (§1.5).

Status: **implemented, host-tested, and lab-verified on the emulator on both call routes**,
in both palettes, at 1080p and 800x480. **Nothing has run on the Pixel or the truck.**

The lab found three things that change what was believed (§3):

- `MANAGE_ONGOING_CALLS` is never granted to this app. Telecom binds anyway, on the
  app-op alone. `CallControl` was checking the permission too, and has been fixed.
- The emulator's dialer does not post `CallStyle`.
- `MediaControl` treated "nothing playing" as "no notification access", and showed
  Telecom's headset session as now-playing during a call. Both are fixed (§7).

---

## 1. What was built

### 1.1 The flex tile (plan §5.8, §1.1)

`DashboardLayout.FLEX_TILE` is no longer "vehicle, not configured". It shows whichever
source has something to say, from the pure `FlexRules.source(call, paused, notifications)`:

1. **A call**, in any phase but `NONE`. Ⓟ does not hide it: plan §10 question 2 is
   answered the way §5.8 reads it, and the code says so where it decides.
2. **Notifications**, while not paused and there are any.
3. **Otherwise the empty state.** It says "No notifications", or "Notifications paused"
   while Ⓟ is on. It is centred at the empty favourites' size, so the two tiles' quiet
   states match. "Not configured" is gone from the code.

Weather (§5.9) is not a source yet; it would slot in between 2 and 3.

- **The label follows what is shown** (`FlexRules.label`). It says "incoming call",
  "calling", "call", "on hold" or "call ended" for a call. It says "Muted" while the
  microphone is off, bold, larger and in the warning colour, because a forgotten mute is
  the mistake §5.8 names. Otherwise it says "notifications".
  `DashboardLayout.TILE_LABELS[1]` is "notifications".
- **A trip never displaces it.** The trip panel takes the favourites tile (P7.4). A call
  and a route can be on screen together, and Answer and End route stay separate targets
  (host-tested).
- The whole flex tile no longer lights when pressed. Its rows and buttons light on their
  own.

### 1.2 Notifications (P7.8)

- **Rows, newest first**, in `NotificationFeed`'s order. Two rows show at once
  (`NOTIFICATION_ROWS`, `notificationRowHeight`), because each row carries two lines:
  - **Line 1**, bold and large at 0.28 of the row: the sender or title, across the
    whole row.
  - **Line 2**, small at 0.2: "App · text". The app's name is dimmed, the text follows in
    the text colour, and the line is cut at the end. When the row fell back to the app's
    name for want of a sender, the name is not drawn twice.
  - At 1080p the lines are 48 and 34 px; at 800x480 they are 21 and 15 px.
  - **Why this layout.** The lab first drew the app's name and the sender side by side on
    line 1. On a 455 px tile that read "Lab Play… Sender…", with both cut. After the
    coordinator's review (§7) the sender has the line to itself.
- **Scrolling reuses the favourites' code.** P6.6's gesture moved out of `Dashboard` into
  **`TouchList`** (`FlexTile.kt`): press and release on a row is a tap, and past the
  slop the rest of the gesture is a scroll that follows the finger. The favourites and
  the notifications each own one. The layout gained the generic
  `listRow`/`hitListRow`/`listMaxScroll`, and `favoriteRow`, `hitFavorite` and
  `favoritesMaxScroll` now delegate to them. The drawing's scrollbar, clip, hairlines and
  pressed row became one `drawList`, which both lists use. `DashboardTest`'s favourites
  checks, 587 of them in all, pass unchanged.
- **Tap a row: read aloud, stubbed.** The row flashes for `READ_FLASH_MS` = 800 ms. The
  press comes back through `Dashboard.takeReadAloud()`, polled like `takeEndRoute`.
  `ProjectionService` notes "dashboard notification read aloud (stub)", which names none,
  and calls `Speaker.read`, which counts.
- **Gone from the phone, gone from the tile.** The feed drops the row and sends the rest.
  `Dashboard.notifications()` keeps the scroll where it can and drops a finger resting on
  the old rows, because the row under it may now be a different one. A call arriving
  under a finger drops it too.
- **Ⓟ.** The header icon from P7.3 toggles `notificationsPaused`, which
  `ProjectionService` hands to `NotificationFeed.paused`. Un-pausing makes the feed
  active again, and becoming active calls `reread()`: the shade is read again, so nothing
  that arrived during the pause is lost. The lab counts two re-reads: the one at start
  and the one after un-pausing.
- **Wiring** (`ProjectionService`, dashboard scene only):
  - A `NotificationFeed` listener and a `CallControl` listener are registered **before**
    the feed is turned on, so the first read of the shade reaches the tile. Both post to
    main.
  - Both are removed in `stopProjection` and in `onDestroy`'s emergency path.
  - The call listener notes each phase change, phase and route only: "call ringing
    (telecom)". The lab polls for these notes.

### 1.3 The call card (P7.9)

`FlexRules.actions(call)` decides the buttons. The layout is `callName`, `callDuration`,
`ringButton(i)`, `callRowButton(i, n)`, `callActionBox`, `hitCallAction`.

| Phase | Card |
| --- | --- |
| Ringing | The caller, large; **Answer** (green) and **Decline** (red) under it, each half the tile's width and the full height left |
| Connected, Telecom route | The caller, a running duration (`0:42`, `12:05`, `1:02:03`), and a row of **Mute** (lit in the warning colour while muted), **Keypad**, **Hang up** (red) |
| Connected, notification route | The caller and duration; **Hang up** alone, across the row. `canMute`/`canDial` are false, so Mute and Keypad are not drawn and not targets |
| Dialling, on hold | Hang up alone |
| Ended | The caller and "call ended", no buttons |

- **Sizes.** At 1080p Answer and Decline are 197 × 230 px, and the in-call buttons
  125 × 180. At 800x480 they are 83 × 105 and 52 × 83.
  - Following "each half the tile's width and the full height below the name" literally
    makes Answer larger in area than a media button at 1080p (173 × 205). The plan also
    calls them "the biggest targets after the media buttons", so the two phrases
    disagree slightly. The geometry the plan spelled out was kept.
- **Colours.** New palette roles `answer`, `decline` and `onAction`, in both palettes, as
  required fields, so neither set can borrow the other's:
  - **Day:** `#1B7F3B` and `#B3261E` with white words.
  - **Night:** `#45B86B` and `#EF6A60` with near-black words. A day-strength green on the
    night tiles is under 3:1 and reads as a hole in the tile.
  - **Tests:** the words are ≥ 4.5:1 on both fills, both fills are ≥ 3:1 against the
    tile, a lit Mute's word is ≥ 4.5:1, green is green and red is red, and all are opaque.
  - A pressed call button gets a thick rim in the text colour rather than a change of
    fill, so green stays green under a finger.
- **Every action goes through `CallControl` from `ProjectionService`.** A press is queued
  as a `CallAction` in order, and `takeCallActions()` is polled after `pipeline.touch`,
  like `takeEndRoute`. The service calls `answer()`, `decline()`, `hangUp()`,
  `setMuted(true/false)`, `dtmfDown(key)` or `dtmfUp()`, on main and never from the
  render thread.
- **Mute follows Telecom.** It asks for the opposite of what is drawn, and what is drawn
  comes back from Telecom. A mute pressed on the phone therefore shows on the tile too.

### 1.4 The keypad (P7.9)

- **Where.** `DashboardLayout.keypad` covers the whole half the map does not take: the
  media pane and both tiles. The map stays in view.
  - Along its top: the **echo strip**, with **Close** (a quarter of the width) at its
    right.
  - Under that, **1–9, *, 0, #** in three columns.
  - Keys are 283 × 171 px at 1080p (the plan estimated about 300 × 200) and 118 × 77 at
    800x480.
- **It claims the gesture first.** An early branch at the top of `Dashboard.touch` takes
  any finger that comes down on the open keypad and keeps it until it lifts. The player
  rows, End route, the favourites and the flex tile's own buttons underneath never see
  the press (host-tested at every size). A second finger landing on the keypad is not a
  press on what it covers. The map beside it is unaffected.
- **Tones.**
  - `KEY_DOWN` with the key on the press, which starts the tone.
  - `KEY_UP` on the release or a cancel, wherever the finger has slid to, so a held key
    is a held tone.
  - The echo takes the key only on a press and release on that same key.
- **It closes itself** when the call can no longer take tones: ended, on hold, or on the
  notification route (`keypad_auto_closes`). A tone still sounding under a finger is
  stopped at that moment.
- **The echo strip.**
  - Holds this call's keys, the latest right-aligned, up to `ECHO_MAX` = 24.
  - Is cleared when the call ends. A hold keeps it, so the keys come back when the
    keypad is reopened.
  - Shows **"Muted"** in the warning colour while the microphone is off, because the
    keypad hides the tile that says it.
- **Digits are drawn and held nowhere else.**
  - `CallAction.toString()` leaves the key out.
  - `CallAction.note` is null for key presses, so no event log line is written per key.
    Even "a key" per press would spell out a PIN's length and rhythm.
  - `Snapshot.toString()` is overridden to print only the frame, the source and whether
    the keypad is open.
  - The report counts presses and not the echo's length.

### 1.5 The player list fix (part-2a review)

The emulator's media pane offered "Bluetooth" and "Android Open Source…", which are
system browser services and not players. `MediaPlayers` now does this:

- **Browser-service apps need a launcher activity.** `getLaunchIntentForPackage` returns
  non-null. That needs no new `<queries>` entry: the `MediaBrowserService` query already
  makes each such package visible, and visibility is per package.
  - Verified: the emulator's two services, `com.android.bluetooth` and
    `com.android.music`, have no launcher. Both are refused
    (`media_players_no_launcher=2`), and the lab player, which has one, is listed.
- **Live sessions** are listed only with a playback state, and only if the app either has
  a launcher or is not visible to this app.
  - The lab caught Telecom's `HeadsetMediaButton` session, which has a null state, being
    listed as a player during a call (`dashboard_player_rows_max=2`).
  - A session from a package this app cannot see is kept, because a real player need not
    be visible to a notification listener.
- **This app is excluded**, as before.
- **Real use is remembered.**
  - `PlayedStore` (app-private preferences `played_players`) keeps each package seen with
    a *playing* session, or resumed successfully, with its time. It holds at most 20.
  - `Players.merge(sessions, browsers, played)` orders by this process's activity, then a
    live session, then the remembered order, then the name.
  - It keeps at most `UNPLAYED_MAX` = 3 apps never seen to play.
  - Package names only. They are never reported: `media_players_remembered` is a count.
- `PlayersTest` (17 checks, was 9) and [p7-backends-results](p7-backends-results.md)
  §2 and §6.2 are updated.

### 1.6 Report

Two new dashboard lines, with counts and phases only:

    dashboard_flex=… dashboard_notifications=N dashboard_notification_rows_max=N dashboard_notification_taps=N dashboard_notification_scrolls=N
    dashboard_calls=N dashboard_call_phase=… dashboard_answer_presses=N dashboard_decline_presses=N dashboard_hang_up_presses=N dashboard_mute_presses=N dashboard_keypad_opens=N dashboard_keypad_closes=N dashboard_keypad_auto_closes=N dashboard_keypad_presses=N

`media_players_no_launcher` and `media_players_remembered` are also new.

The event log gains:

- "dashboard notification read aloud (stub)"
- "dashboard call answer|decline|hang up|mute|unmute pressed"
- "call <phase> (<route>)"

None of these names a caller, an app, a text or a key.

## 2. Verification

All under `flock ~/.cache/openauto-android/p7-build.lock`.

### 2.1 Build and host tests

`tools/android-build.sh`: **exit 0**. That covers clippy, both APKs, `lintDebug` with
warnings as errors, `verify-apk` on all three, and every host test.

**New `DashboardFlexTest`: 1380 checks**, registered in `tools/jni-host-test.sh`. It runs
at 1920x1080 with the map on either side, at Ford's 726x480 and at 800x480, and covers:

- **Layout.**
  - The notification list is in the flex tile under its label. Two rows fit, and the line
    sizes are at least 18 and 13 px. An app name and a first name fit one line at 1080p.
  - Answer and Decline are each half the tile a margin apart, fill the height under the
    name, hit at centre and corner, and miss in the gap and on the name. "Decline" fits
    at ≥ 30 px at 1080p and ≥ 14 px at 800x480.
  - The three in-call buttons are ≥ 40 px, apart, and hit. Hang up alone spans the row.
  - The keypad starts at the media pane, reaches the tiles' bottom and clears the map. It
    has twelve keys that never overlap and hit at centre and corners, 0 under 8, and are
    ≥ 280 × 170 at 1080p. Close is a quarter of its width.
- **Priority.** Empty, then notifications, then a call. Paused shows nothing. Ⓟ does not
  hide a call. "vehicle" appears nowhere.
- **`TouchList`.** The P6.6 rules one by one: slop, follow, never a tap after a scroll,
  clamp, replace, cancel.
- **Notifications.**
  - A press lights the row and a release reads it once. The flash lasts 800 ms.
  - Lifting on another row does nothing.
  - Scrolling follows the finger, stops at the last row and reads nothing.
  - A cancel reads nothing.
  - A paused tile's empty state is not a row.
  - A removed row goes and the finger on it is dropped.
- **Call card.**
  - Answer and Decline need a press and release, and do nothing on a slide or a cancel.
  - Mute asks and then follows Telecom's state. "Muted" is the label.
  - Hang up hangs up.
  - Dialling and on hold show Hang up only; ended shows no buttons.
  - The notification route shows no Mute and no keypad, and the press where they would
    be is Hang up.
  - Durations are right.
- **Keypad.**
  - Opening it sends nothing.
  - A player row, a favourite and Hang up underneath take no press.
  - The tone starts on the press. The key lights, and the echo waits for the release.
  - A slide off stops the tone and echoes nothing. A cancel stops it too.
  - `*`, `0` and `#` echo.
  - The background does nothing, and a drag on the map sends no key.
  - Close closes it, and a slide off Close does not.
  - On hold under a finger, it closes and the tone stops, the echo is kept, and it
    reopens with the keys.
  - The call over, the echo is forgotten and the next call starts empty.
- **Trip beside a call.** Answer is Answer and End route is End route.
- **Palette.** The call colours' contrast in both palettes (§1.3).
- **Privacy.**
  - A PIN-like sequence (`7294#*0135868`) is shown in the strip.
  - The report never contains the caller, "Sendermarker", "Bodymarker", the app label,
    the package, `7294`, `0135`, `#` or `*`.
  - A printed snapshot contains no caller, sender, text, digits or player label.
  - No `CallAction` note or string contains a key, for every kind with every key.
  - Key presses are never noted.

All existing tests pass: `DashboardTest` 587, `DashboardTripTest` 466, and the rest.

### 2.2 Emulator lab

**The new `calls` script.** It needs the lab player (§2.4).

- **Receiver** (`--script calls --call-controls full|basic`), in this order:
  1. Waits for the flex tile to fill.
  2. Taps the first notification row and checks it flashed.
  3. After the app side removes one notification, presses Ⓟ and checks the tile changed,
     then presses Ⓟ again and checks the rows came back.
  4. Waits for Answer's plate to turn green (BT.601 chroma), presses it, and checks the
     green gave way to the in-call row.
  5. With `full`: presses Mute (the button lit), Keypad (the half changed), the keys 1, 2
     and # (the echo strip changed each time) and Close (the half changed back).
  6. Presses Hang up and checks the tile changed.
- **App side** (`tools/emulator-lab.sh calls`):
  - Seeds `notifications_on_dashboard` and `call_controls` into the settings file the way
    `LAB_MARKER` does, and grants notification access with `cmd notification
    allow_listener`.
  - With `LAB_CALL_ROUTE=telecom`, runs `CallControl.GRANT_COMMAND`'s
    `appops set --user 0 … MANAGE_ONGOING_CALLS allow` and rings a real modem call with
    `adb emu gsm call`. Otherwise (`notification`, the default) the lab player posts a
    `CallStyle` call.
  - Posts three notifications from the lab player. Once the app notes the read-aloud,
    it cancels one.
  - Then checks the report for these counters:
    - read-aloud 1 and notification taps 1
    - `notifications_removed` ≥ 1 and `notifications_rereads` ≥ 2
    - pause presses 2, and rows max 2
    - the call notes; Answer and Hang up pressed once each; `call_answered=1` and
      `call_hung_up=1`
    - on the telecom route: `call_route=telecom`, `call_companion_bindings` ≥ 1, mute 1
      and `call_mute_toggles=1`, keypad opened and closed once, and 3 keypad presses on
      both sides
    - on the notification route: `call_fallback_calls` ≥ 1, and the lab player's own log
      of `ring`, `answer` and `hang_up`
  - Also checks that no "Sendermarker", "Bodymarker", "Labcaller", "Lab Player",
    `5551234`, `555-1234` or `12#` reached the report.

| Run | Result |
| --- | --- |
| `LAB_CALL_ROUTE=telecom LAB_MAP=…massachusetts.map LAB_VIDEO_CONFIG=1920x1080 calls` (night), `emulator-lab/20260924T161030Z` | **fail, as found**: `call controls on: notification` although the app-op was allowed (§3.1) |
| same, after the `fullControlsGranted` fix, `emulator-lab/20260924T161842Z` | **pass**, `receiver_checks=17 receiver_check_failures=0` |
| same, after the row-size, Muted-strip and access fixes, `emulator-lab/20260924T162555Z` | receiver **1 failure**: the first key's echo changed 0.47% against a 0.5% threshold, because a thin "1" beside "Muted" is a small change. The app side passed. The threshold is now 0.2% |
| same, receiver rebuilt, `emulator-lab/20260924T163118Z` | **pass**, `receiver_checks=17 receiver_check_failures=0` |
| `LAB_NIGHT=day LAB_CALL_ROUTE=notification LAB_MAP=… calls` (day, 800x480), `emulator-lab/20260924T162415Z` | **pass**, `receiver_checks=11 receiver_check_failures=0` |

The final telecom run, from its event log:

- **Timeline.**
  - +8.8 s: read aloud.
  - +25.9 s: `call ringing (telecom)`.
  - +27.0 s: answer pressed.
  - +27.3 s: `call active`.
  - +29.6 s: mute pressed.
  - About 11 s of keypad, then hang up pressed.
  - +0.2 s later: `call ended`, then `call none`.
- **Counters.**
  - `call_route=telecom call_companion_bindings=1 call_calls_seen=1 call_answered=1
    call_hung_up=1 call_mute_toggles=1 call_keypad_presses=3`.
  - `notifications_removed=1 notifications_rereads=2 notifications_read_aloud=1`.
- **Receiver.** `answered=0.376 muted=0.993 keypad-open=0.560 echo=0.005/0.014/0.025
  keypad-closed=0.560 hung-up=0.395`.

**Regressions, same build:**

| Run | Result |
| --- | --- |
| `LAB_ROUTING=… LAB_MAP=… LAB_VIDEO_CONFIG=1920x1080 endroute` (with the focus agent's second route), `emulator-lab/20260924T162720Z` | **pass**, `receiver_checks=19 receiver_check_failures=0`. `states=1,2,1,2,0`. Resume now **plays**: `media_players_listed=1 media_players_no_launcher=2 media_resume_recent=1 dashboard_resume_played=1`, and the lab player logged `root recent=true`, `prepare`, `play`. The script now also requires `media_resume_recent=1` |
| `LAB_MAP=… LAB_VIDEO_CONFIG=1920x1080 track`, `emulator-lab/20260924T162818Z` | **pass**, `receiver_checks=12` |
| `LAB_MAP=… LAB_VIDEO_CONFIG=1920x1080 controls`, `emulator-lab/20260924T162920Z` | **pass**, `receiver_checks=14`. Its last frame shows "Notifications paused" in the flex tile after the Ⓟ press |

### 2.3 Frames looked at

All under `~/.cache/openauto-android/emulator-lab/`.

| Frame | What it shows |
| --- | --- |
| `20260924T163118Z/frames/007.png` | **Night, 1080p, before any notification.** The flex tile is labelled "notifications" and reads "No notifications"; the idle media pane lists "Lab Player" with Resume. |
| `20260924T163118Z/frames/011.png` | **Three notifications**, newest first. Two rows show "Lab Play… Sender…" over "Bodymarker message 3/2", with the scrollbar, since three are held. |
| `20260924T163118Z/frames/012.png` | **The tapped row flashing** (the read-aloud stub). |
| `20260924T163118Z/frames/022.png` | **Paused.** Ⓟ is bold in the accent colour and the tile says "Notifications paused". |
| `20260924T163118Z/frames/027.png` | **Un-paused.** The re-read shade shows messages 3 and 1: 2 was removed on the phone, and its row is gone. |
| `20260924T163118Z/frames/032.png` | **Ringing.** "incoming call", "5551234" large, green **Answer** and red **Decline** with dark words, filling the tile under the name. |
| `20260924T163118Z/frames/037.png` | **Muted.** The label reads "Muted" in bold warning colour, the duration runs, and Mute is lit in the warning colour beside Keypad and a red Hang up. |
| `20260924T163118Z/frames/044.png` | **Keypad open over the right half** with the map in view. The strip reads "Muted" and "12#", Close is at its right, and the 4 × 3 keys are about 283 × 171. |
| `20260924T163118Z/frames/050.png` | **After Hang up.** The notifications are back in the tile. |
| `20260924T162415Z/frames/013.png` | **Day, 800x480, notification route**: two rows, "Lab Pla… Sender…" over the text, at 18 and 14 px. |
| `20260924T162415Z/frames/022.png` | **Day, 800x480, paused.** |
| `20260924T162415Z/frames/031.png` | **Day, 800x480, ringing.** "Labcaller Person", with Answer (`#1B7F3B`) and Decline (`#B3261E`) in white words. |
| `20260924T162415Z/frames/034.png` | **Day, 800x480, in a call on the notification route.** "call", the caller, "0:01", and **Hang up alone** across the row, with no Mute and no Keypad. |

### 2.4 The lab player

The emulator image has no launchable media player. Its dialer's call notification is not
`CallStyle`, and `cmd notification post` never reached its shade. Each of those blocks
one of this slice's checks, so the lab now has a small stand-in app:

- **What it is.** The source is in `tools/lab-player/`. `tools/lab-player.sh` builds it
  offline in the pinned Android image and signs it with the lab's debug key. The APK
  goes to `~/.cache/openauto-android/lab-player/`, never the workspace.
  `emulator-lab.sh` installs it whenever it is there; `calls` and `endroute` require it.
- **What it provides:**
  - A launcher activity.
  - A `MediaBrowserService` that answers `EXTRA_RECENT` with a root and a session that
    plays silently when told to.
  - A `CallReceiver` that posts `CallStyle.forIncomingCall` and then `forOngoingCall`,
    with its own answer, decline and hang-up `PendingIntent`s, and logs each intent it
    receives.
  - A `MessageReceiver` that posts and cancels default-importance notifications.
- It is lab-only. It is never shipped and never installed on a phone.

## 3. Findings

### 3.1 `MANAGE_ONGOING_CALLS`: Telecom binds on the op; the permission is never granted

- **The failure.** The first telecom run chose the notification route with the op
  allowed.
- **The permission itself.** On the emulator (API 36):
  - `dumpsys package` lists `MANAGE_ONGOING_CALLS` under requested permissions only,
    with no install grant.
  - `pm grant` refuses it: "not a changeable permission type".
  - It is `signature|appop`, and this app is not platform-signed.
- **What Telecom does.** With only the op allowed and the component force-enabled,
  Telecom classified `CallCompanionService` as a requested in-call type, bound it, and
  added the ringing call to it (`InCallController: onConnected … Adding 1 calls`). That
  is how `PermissionChecker` treats an app-op permission: `MODE_ALLOWED` grants, and only
  `MODE_DEFAULT` falls back to the permission.
- **The fix.** `CallControl.fullControlsGranted` required `checkSelfPermission` as well,
  so it could never be true for this app. It now follows the op the same way.
- **What is proven.** The Telecom route works end to end in the lab with nothing but
  `CallControl.GRANT_COMMAND`.
- **The docs.** [p7-backends-results](p7-backends-results.md) §4.1 said the permission
  was "granted at install" and is corrected. Plan §5.8's table ("Not granted to an
  ordinary app, but the app-op can be allowed once") stands. Its sense is now confirmed:
  the op alone is enough.

### 3.2 The emulator's dialer does not post `CallStyle`

- **What it posts.** AOSP Dialer's incoming-call notification has category `call` and
  two plain actions, "Decline" and "Answer", but no `EXTRA_TEMPLATE`, no
  `android.callType` and no answer or decline intents.
- **What that means.** The call-notification route reads `CallStyle` only, so it cannot
  control an emulator modem call. That is why the lab player posts one.
- **Google Phone** is expected to post `CallStyle`, which is what the route was written
  against. That remains unverified (backends §8 item 5).
- **If the Pixel's dialer turns out not to post it**, a fallback that matched plain
  actions by label ("Answer"/"Decline") would be locale-bound. It is not built.

### 3.3 The modem needs an IPv6 address that is not loopback

- **The cause.** The emulator's modem simulator connects to `::1`, which glibc's
  `getaddrinfo` refuses on a host whose only IPv6 address is loopback (`AI_ADDRCONFIG`).
  That describes the lab pod's `--network none`. The modem then reports
  `OUT_OF_SERVICE`, and `adb emu gsm call` says OK and rings nothing. Enabling IPv6 on
  loopback, or using the legacy modem (`-feature -ModemSimulator`), does not help.
- **The workaround.** The `calls` script's telecom run alone puts the pod on
  `oaa-lab-v6`. That is an **internal** podman network, with IPv6 and no route out,
  created on first use.
- **The cost.** This is the one lab run that is not loopback-only. The APK still has no
  `INTERNET` permission. The host's bridge address is reachable from the pod.

### 3.4 `MediaControl` mislabelled access and showed Telecom's session (fixed, §7)

- **Access.** `MediaControl.publish()` sets `access = controller != null`, so with
  nothing playing it tells the dashboard there is no notification access. The idle
  pane's "The phone can list more players with notification access on" appeared with
  access granted (frame `20260924T161842Z/frames/007.png`).
  - This slice's own fix: `ProjectionService` now hands the pane
    `MediaAccessService.enabled()` after every now-playing update.
- **Telecom's session.** During a call, `MediaControl` picks
  `getActiveSessions().firstOrNull()`, which is Telecom's `HeadsetMediaButton` session,
  so the media pane shows now-playing "Phone Calls" with transport buttons (frame
  `…163118Z/frames/037.png`).
- **Both are fixed in `MediaControl.kt`** (§7), and the `ProjectionService` workaround is
  removed.

### 3.5 `MediaAccessService.enabled` read a setting that is not kept

- **What happened.** With access granted by `cmd notification allow_listener`, the
  listener bound and fed the flex tile, but `Settings.Secure.enabled_notification_listeners`
  did not name it.
- **The fix.** `enabled()` now asks
  `NotificationManager.isNotificationListenerAccessGranted(component)` first: the
  notification service's own answer for this app's listener. The setting is the
  fallback.
- **Knock-on.** `CallRules.route`'s notification-access input, `MediaPlayers` and the
  settings screen all read it, so all of them are now right in that case.

## 4. Not verified: needs the Pixel or the truck

- **The Telecom route on the Pixel** after `adb shell appops set --user 0 … allow`:
  - that Telecom binds as it did on the emulator, and on GrapheneOS in particular;
  - mute through `onMuteStateChanged` (API 34+ on the Pixel; the emulator is API 36);
  - DTMF heard by a real far end.
- **The notification route with Google Phone:** whether it posts `CallStyle` with the
  three intents, and whether its intents accept a send from this app.
- **On the truck:** a call while projecting with HFP paired (plan §5.8's SYNC question).
  Whether SYNC's own call screen covers the card, and whether the card's Answer races
  SYNC's.
- **Real notifications:** their labels under package visibility (backends §3), the
  filter against real messaging apps, and one line of text at arm's length.
- **The sizes on SYNC 4.** At 800x480 the in-call buttons are 52 × 83 px and the keys
  118 × 77 px. They were pressed only by the lab receiver.
- **The remembered-players order across drives:** the lab wipes the emulator each run.
- **Read aloud** is a stub by design (plan §0).

## 5. Needed from files this slice does not own

- `MediaControl.kt` and the manifest comment are done (§7).
- **`ConnectionActivity`** (phone UI): the Call controls row can now show
  `CallControl.fullControlsGranted` truthfully, because it no longer says false for
  everyone.
- **`docs/p7-plan.md`** (coordinator):
  - §5.8's "Answer and Decline … the biggest targets on the dashboard after the media
    buttons" is not true at 1080p with the literal geometry (§1.3). Either phrase can
    give.
  - §10 question 2 (does Ⓟ hide calls?) is implemented as "no".
  - §5.8's fallback paragraph should note that it reads `CallStyle` only (§3.2).

## 6. Amendments this proposes — proposed, not applied

- **spec §8.2** (dashboard):
  - The flex tile and its priority.
  - Notification rows with the read-aloud stub.
  - Ⓟ's empty state.
  - The call card by phase and route.
  - The keypad over the non-map half, with its tone and echo rules.
  - The "Muted" label and strip.
- **spec §10** (reports): the `dashboard_flex`/`dashboard_notification_*` and
  `dashboard_call*`/`dashboard_*_presses`/`dashboard_keypad_*` keys,
  `media_players_no_launcher` and `media_players_remembered`, and the event log's call
  phase notes. All are counts and phases. Never a caller, number, sender, text, app or
  digit, nor the echo's length.
- **spec §5.2 / plan §9 item 3**: record that `MANAGE_ONGOING_CALLS` is never granted
  itself to this app. The operator's app-op is the whole grant, and Telecom honours it
  (§3.1).
- **plan.md P7 rows**:
  - P7.8: "implemented, host- and lab-verified; real notifications unverified on the
    Pixel".
  - P7.9: "implemented, host- and lab-verified on both routes in the emulator; Telecom
    route and Google Phone's CallStyle unverified on the Pixel; SYNC with HFP unverified
    on the truck".

## 7. After review — 24 September 2026

The coordinator's review asked for three changes. The slice took `MediaControl.kt` and the
manifest's comments for them.

1. **`MediaControl`.**
   - `publish()` reports `access = MediaAccessService.enabled(context)`, the grant
     itself. The `ProjectionService` workaround is removed.
   - `controller()` picks through the pure **`MediaSessions.pick`**. It skips Telecom's
     session (`com.android.server.telecom`), any session without a playback state, and
     this app. `MediaPlayers.isPlayer` uses the same rule.
   - Host-tested in `DashboardFlexTest`, now 1386 checks:
     - Telecom's headset session before a paused player is skipped for the player.
     - Telecom with a state is still skipped.
     - A stateless session is skipped.
     - This app is skipped.
     - Otherwise the platform's order stands.
     - None gives -1.
2. **Notification rows** as §1.2 now describes: the sender on line 1, "App · text" on
   line 2.
3. **The manifest comment.**
   - It now says Telecom honours the app-op and that the permission itself is never
     granted.
   - It gives the command as `CallControl.GRANT_COMMAND` with the user flag. The two
     hyphens are spelled out, because an XML comment cannot hold them: the first attempt
     at a literal `--user 0` failed the manifest merge.

**Verification.**

- `tools/android-build.sh`: exit 0.
- Lab runs:

| Run | Result |
| --- | --- |
| `LAB_CALL_ROUTE=telecom … LAB_VIDEO_CONFIG=1920x1080 calls`, `emulator-lab/20260924T163915Z` | **pass**, 17/17. `dashboard_media_access=true` |
| `LAB_NIGHT=day LAB_CALL_ROUTE=notification … calls` (800x480), `emulator-lab/20260924T164034Z` | **pass**, 11/11 |

**Frames.**

- `20260924T163915Z/frames/036.png`: **in a call, muted**. The media pane stays the idle
  player list ("Lab Player" and Resume) with no access hint. There is no "Phone Calls" and
  no transport buttons.
- `20260924T163915Z/frames/026.png`: **rows at 1080p**. "Sendermarker 3" in full on
  line 1, and "Lab Player · Bodymark…" on line 2.
- `20260924T164034Z/frames/013.png`: **the same at 800x480 in day**.
