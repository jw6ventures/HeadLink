# P7 phone-side backends — multi-SIM signal, players with Resume, the notification feed, call controls: implementation record — 23 September 2026

The data sources and control APIs behind four [P7 plan](p7-plan.md) items: §5.2 (signal for
every SIM, P7.3), §5.5 (media players with Resume, P7.5), §5.8 (the flex tile's
notifications, P7.8, and call controls, P7.9), and §9 item 6 (the consent text). **Nothing
here draws.** The dashboard slices code against §6's API; nothing is wired to settings,
the session or the report yet (§7).

Status: **implemented and host-tested.** Built with `tools/android-build.sh` (lint and
`verify-apk` pass). **Nothing here has run on a phone.** Every Android half — telephony
callbacks, `MediaBrowser`, the notification listener, Telecom — is unverified until the
Pixel runs it. §4.2 records a finding from Telecom's source: `CALL_COMPANION_APP` no
longer gets an in-call service bound. On James's decision (23 September 2026), P7.9
therefore declares `MANAGE_ONGOING_CALLS` instead, allowed once over adb, with the call
notification as the route when it is not.

---

## 1. Multi-SIM signal (plan §5.2)

- **`PhoneStatus`**, with `READ_PHONE_STATE`: an `OnSubscriptionsChangedListener`, and for
  each active subscription a `TelephonyManager.createForSubscriptionId(id)` with its own
  `TelephonyCallback` (signal strength, service state, display info), all on the main
  executor. Subscriptions that go away are unregistered; new ones registered; `release()`
  removes everything. Without the grant nothing is registered and the header shows `—`, as
  before. A phone with no `SubscriptionManager` falls back to the old single callback.
- **`SimSignal`** (pure, host-tested): the primary is `getDefaultDataSubscriptionId()`, else
  the lowest slot. Primary out of service → **0** bars (empty, as the phone's status bar);
  no SIM or nothing read → **-1** (`—`). Secondaries, up to 3 in slot order: 0..4, or **-1**
  for out of service / unread, drawn hollow. `networkLabel` reads `TelephonyDisplayInfo`'s
  override first, so LTE + NR_NSA is `5G`, as the status bar shows it.
- **`PhoneSnapshot`** gains `secondarySignalBars: List<Int>` and `cellNetworkType: String`
  (`5G`, `LTE`, `H+`, `H`, `3G`, `E`, `2G`, `-`). `signalBars` stays the primary's.
  `network` (`wifi`/`cell`/`eth`/`other`/`none`) is unchanged; the header shows `wifi` from
  it when on wifi, else `cellNetworkType`.
- **Found and fixed on the way:** nothing ever called `PhoneStatus.refresh()`, so battery
  and the wifi/cell transport were read once when projection started and never again. The
  header's battery sat at its starting level for a whole drive. `PhoneStatus` now refreshes
  itself every 5 s on the main thread.
- Report: `phone_sim_count`, `phone_sim_secondary_bars` (levels), `phone_cell_type`,
  `phone_sim_changes`. No carrier, number or subscription id.

## 2. Media players with Resume (plan §5.5)

- **`Player`** (pure): `key` (package name), `label`, `hasSession`, `hasBrowser`,
  `lastActiveMillis` (elapsed realtime; 0 = never seen playing). **`Players.merge`**: one row
  per package, most recently active first; ties put a live session first, then the label
  (case-insensitive), so the order is stable; at most `Players.MAX` = 12.
- **`MediaPlayers`** (Android, own `aap-players` thread). Source 1: `getActiveSessions`,
  needing notification access, plus an `OnActiveSessionsChangedListener` so the list
  follows players starting and stopping. Source 2: `queryIntentServices` for
  `android.media.browse.MediaBrowserService`, made possible by the manifest's new
  `<queries>` entry; exported services only, never this app.
- **Resume**, following SystemUI's `ResumeMediaBrowser.restart()` (read from AOSP main,
  23 September 2026): a live session gets `transportControls.play()`. Otherwise a
  `MediaBrowser` connects with `BrowserRoot.EXTRA_RECENT = true` in the root hints, and on
  `onConnected` a `MediaController` on `getSessionToken()` gets `prepare()` then `play()`.
  The connection is held for 10 s afterwards, as SystemUI holds it until its player
  updates, so a service is not unbound while its app is starting playback. A service
  that returns a null root (no resumption) arrives as `onConnectionFailed` → `REFUSED`.
  Bounded by `RESUME_TIMEOUT_MS` = 5 s → `TIMED_OUT`. A second press cancels a pending
  connect.
- **"Supports resumption" is learned at Resume, not at list time.** SystemUI probes each
  candidate with the hint when it first sees that app play; probing every browser app on
  the phone during a drive would start every one of their services. So every exporting
  app is listed, and one that refuses says so when pressed.
- Last-active times are held in memory for the process only, not persisted (they are
  listening history). A cold list is ordered by name until something plays.
- **Only real players, since dashboard part 2b** ([p7-8-9-results](p7-8-9-results.md)
  §1.5). The part-2a lab frames offered "Bluetooth" and "Android Open Source…", system
  components that export a browser service and play nothing a driver would pick. Now:
  - A browser-service app is listed only with a launcher activity
    (`getLaunchIntentForPackage` non-null). That needs no `<queries>` entry of its own: the
    `MediaBrowserService` query already makes each such package visible, and visibility
    is per package. In the emulator both of its browser services are refused this way
    (`media_players_no_launcher=2`).
  - This app is never listed, as before.
  - **Packages seen with a *playing* session are remembered** (`PlayedStore`: app-private
    preferences file `played_players`, one entry per package name with the time last seen
    playing, at most 20). Package names only, never a title, and never reported: the
    report carries the count (`media_players_remembered`).
  - `Players.merge(sessions, browsers, played)` orders by this process's last activity,
    then a live session, then the remembered order, then the name; and keeps at most
    `Players.UNPLAYED_MAX` = 3 apps never seen to play (no session, no activity, not
    remembered). A cold list at the start of a drive therefore starts with the players
    actually used. The superseded sentence above ("ordered by name until something
    plays") holds only on a phone that has remembered nothing yet.
- Report: `media_players_listed/_sessions/_browsers`, `media_resume_presses`, and
  `media_resume_session/_recent/_refused/_timeouts/_not_found`. Never an app.

## 3. The notification feed (plan §5.8, P7.8)

- **`MediaAccessService`** now overrides `onListenerConnected`,
  `onListenerDisconnected`, `onNotificationPosted(sbn, rankingMap)` and
  `onNotificationRemoved(sbn, rankingMap)`. A posted notification is described only while
  `NotificationFeed.active` (or, for call notifications only, while the call fallback is
  wanted, §4.3). Otherwise the callback returns before touching the notification.
  Removal passes only the key, which identifies a row already held.
- **`NotificationFacts`** (pure) is the plain description: key, package, app label,
  ranking importance, ongoing (`FLAG_ONGOING_EVENT` or `FLAG_FOREGROUND_SERVICE`), group
  summary, category, `EXTRA_TEMPLATE`, whether `EXTRA_MEDIA_SESSION` is set, title, text,
  and the last `MessagingStyle` message's sender and text.
- **`NotificationRules.verdict`** (pure, host-tested), in this order: own package → `OWN`;
  category `call` or `CallStyle` → `CALL`; media session / `MediaStyle` /
  `DecoratedMediaCustomViewStyle` → `MEDIA`; ongoing → `ONGOING`; group summary →
  `GROUP_SUMMARY`; importance below `IMPORTANCE_DEFAULT` (silent, min, unknown) →
  `NOT_ALERTING`; nothing to draw → `EMPTY`; excluded package → `EXCLUDED`; else `SHOWN`.
  Exclusion is last so an excluded app still counts as one that posted.
- **`DashNotification`** (pure): key, app label, title (the message's sender, else
  `EXTRA_TITLE`, else the app's name), one line of text (first non-blank line, whitespace
  collapsed, cut at 160 with `…`), posted time, and the package (for exclusion; not drawn).
- **`NotificationFeed`** (pure object): newest first by post time, at most 20; an update
  replaces its row, and an update that no longer passes removes it; removal drops the row.
  Off drops everything, including the posting-apps set. Pause drops the rows, reads
  nothing, and keeps the posting-apps set. Becoming active (on, or un-paused) calls
  `reread()`, which asks the connected listener for `getActiveNotifications()` and
  replaces the rows. `posters()` is the set of packages that posted a notification that
  was shown or excluded, for the settings exclusion list; it is in memory, this process
  only.
- **`Speaker.read(notification)`** is the read-aloud stub. It counts and nothing else.
- Report: `notifications_enabled/_paused/_held/_removed/_rereads/_posting_apps/`
  `_excluded_apps/_read_aloud`, and one count per verdict. No app, sender or text. The
  host test posts a notification with known strings and checks none of them appear.
- **App labels are unverified.** `getApplicationInfo` on a posting package needs it to be
  visible to this app under Android 11 package visibility. Whether a notification
  listener sees posting apps is not known here; when it does not, the label falls back to
  the package name. If the Pixel shows package names, the fix is a design decision
  (`<queries>` for launcher apps is broader than §9 allows), not a code change.

## 4. Call controls (plan §5.8, P7.9)

### 4.1 What was built

- **Manifest**: `<uses-permission android:name="android.permission.MANAGE_ONGOING_CALLS"/>`
  (signature|appop: declaring it grants nothing; §4.2), and `CallCompanionService` with `android:enabled="false"`, `android:exported="true"`
  (as the `InCallService` docs require; `BIND_INCALL_SERVICE` is signature|privileged, so
  only Telecom can bind), `android:permission="android.permission.BIND_INCALL_SERVICE"`, and
  one intent filter, `android.telecom.InCallService`. **No meta-data at all.** The
  companion case needs none: Telecom's `getInCallServiceType` reads
  `METADATA_IN_CALL_SERVICE_UI` and `METADATA_IN_CALL_SERVICE_CAR_MODE_UI` only to classify
  UI services, and a non-UI service must declare neither. The optional
  `INCLUDE_EXTERNAL_CALLS` and `INCLUDE_SELF_MANAGED_CALLS` default to false, which is
  what is wanted (SIM calls only).
- **Two routes, chosen per session** by the pure `CallRules.route(settingOn,
  ongoingCallsAllowed, notificationAccess)`: setting off → `OFF`; app-op allowed →
  `TELECOM` (the in-call service is enabled; answer, decline, hang up, mute, keypad);
  else notification access → `NOTIFICATION` (the §4.3 route; answer, decline, hang up);
  else `UNAVAILABLE`. `CallStateTest` covers all eight combinations.
- **`CallControl`** (Android object): `fullControlsGranted(context)` checks the app-op,
  looked up with `AppOpsManager.permissionToOp` because the op's `OPSTR_` constant is not
  public on API 33–36, and read with `unsafeCheckOpNoThrow`. A platform without the op
  counts as not granted. **Corrected in dashboard part 2b**
  ([p7-8-9-results](p7-8-9-results.md) §3.1): it first also required the permission
  itself to be granted, which it never is to an app that is not platform-signed (`pm`
  lists it as requested only, and `pm grant` refuses it). Telecom binds on the op alone,
  as `PermissionChecker` treats an app-op permission: `MODE_ALLOWED` grants, and only
  `MODE_DEFAULT` falls back to the permission. The emulator showed both halves. `GRANT_COMMAND` is the exact adb command for the
  settings screen. `setEnabled(context, on): CallRoute` chooses the route and enables the
  component through `setComponentEnabledSetting(…, DONT_KILL_APP)` **only for `TELECOM`**.
  Off, or any other route, returns it to the manifest's disabled default. `resetAtStartup(context)`, called from `SourceApplication.onCreate`,
  disables the component when no session is running; `state`, listeners, and the actions
  `answer()`, `decline()`, `hangUp()`, `setMuted(Boolean)`, `dtmfDown(Char)`, `dtmfUp()`.
  Mute state comes back from `onCallAudioStateChanged` (API 33) and `onMuteStateChanged`
  (API 34+); `setMuted` never sets it directly. The caller is the contact name, then the
  network's caller name, then the number ("Private number", "Payphone" and "Unknown
  caller" when there is none).
- **`CallState`** (pure, host-tested): phase `NONE/RINGING/DIALING/ACTIVE/HOLDING/ENDED`,
  caller, connected-since (wall clock, -1 before connecting), muted, source
  `NONE/TELECOM/NOTIFICATION`, and `canMute` / `canDial`. **`CallRules`** maps Telecom
  states and `CallStyle` call types to phases, picks the call to show when there are
  several (ringing, then active, dialling, held, ended), and checks keypad keys.
- Report: `call_controls_wanted`, `call_route` (`off`/`telecom`/`notification`/`unavailable`),
  `call_companion_bindings`, `call_calls_seen`,
  `call_fallback_calls`, `call_answered/_declined/_hung_up/_mute_toggles/_keypad_presses`,
  `call_no_call` (a press with nothing to act on), `call_intent_failures`,
  `call_component_changes`, `call_phase`, `call_source`. Never a number, a name or a digit.
  Nothing in `CallControl` or `CallCompanionService` logs anything.

### 4.2 Finding: `CALL_COMPANION_APP` no longer gets a companion bound — and the amendment

The plan says Telecom binds an enabled `InCallService` of any app holding
`CALL_COMPANION_APP` as a companion. **That was true in Android 10 and is not in current
source.** Read from AOSP `packages/services/Telecomm` (`InCallController.java`), 23 September
2026:

- **android-10.0.0_r1**: `getInCallServiceType` computed `isThirdPartyCompanionApp =
  checkPermission(CALL_COMPANION_APP) && !isUIService` and returned
  `IN_CALL_SERVICE_TYPE_COMPANION` for it.
- **android-11.0.0_r1, 12, 13, 16 and main**: that check is gone. `getInCallServiceType`
  never returns `COMPANION`. A service with no UI metadata becomes `NON_UI` only if its app
  holds `CONTROL_INCALL_EXPERIENCE` (signature|privileged) **or** has
  `MANAGE_ONGOING_CALLS` granted through `PermissionChecker` (protection
  `signature|appop`, public since API 31). Otherwise it is logged "Skipping binding" and
  is `INVALID`. The `COMPANION` binding path still exists, but it is fed only by
  `RoleManagerAdapter.getCallCompanionApps()`, which in main returns only a test override
  list (`addOrRemoveTestCallCompanionApp`, reached through `adb shell telecom`), not a
  role holder.

So on the Pixel, `CALL_COMPANION_APP` plus an enabled component would produce **no
binding**. The ways it could bind were:

1. **`MANAGE_ONGOING_CALLS`**, declared as a `<uses-permission>` (appop-controlled, so
   declaring it grants nothing by itself) and its app-op allowed. Android grants that op
   to companion-device apps associated with the watch profile. It can also be set with
   `adb shell appops set --user 0 org.openautoandroid.source MANAGE_ONGOING_CALLS allow`.
   Posing as a watch companion to get it would be a misuse. The adb route is an honest
   operator grant, but it is a new permission that §9 does not list.
2. `adb shell telecom add-or-remove-call-companion-app org.openautoandroid.source 1`,
   a test hook that does not survive a Telecom restart. Useful for one experiment, not
   for use.
3. **The `CallStyle` fallback** (§4.3), which needs no new permission and gives Answer,
   Decline and Hang up but not mute or the keypad.

Telecom does bind a newly enabled non-UI component during a call already in progress: its
`ACTION_PACKAGE_CHANGED` receiver re-queries and `addConnections` for ongoing calls. That
settles plan §5.8's open question in favour, **if** the component is a valid `NON_UI` one.

**Decision (James, 23 September 2026): route (1), with (3) when it is not granted.** P7.9
declares `MANAGE_ONGOING_CALLS` and does **not** declare `CALL_COMPANION_APP`. minSdk is
33, where Telecom no longer binds on it, and declaring a permission that does nothing would
be misleading. The operator allows the op once from a computer:

    adb shell appops set --user 0 org.openautoandroid.source MANAGE_ONGOING_CALLS allow

(`CallControl.GRANT_COMMAND`. On the multi-user Pixel, add `--user 0` after `set` if the
default user is not the one the app runs as; see the Pixel lab note.)

**Proposed amendment to plan §5.8 and §9 item 3 (not applied):** replace "P7.9 adds
`CALL_COMPANION_APP` (normal, install-time)" with: *P7.9 adds `MANAGE_ONGOING_CALLS`
(signature|appop). Declaring it grants nothing. It takes effect only when the operator
allows its app-op once over adb, and only while a session projects with Call controls on,
when the disabled-by-default `InCallService` (behind `BIND_INCALL_SERVICE`) is enabled.
Without that grant, Call controls use the dialer's call notification through the
notification listener: answer, decline and hang up, with no mute and no keypad. It is
optional, and nothing else depends on it.* §5.8's permission table should gain
`MANAGE_ONGOING_CALLS`, and its "the route: a companion `InCallService`" paragraph should
drop the claim that `CALL_COMPANION_APP` is what gets it bound.

### 4.3 The call-notification route — built

This is the `NOTIFICATION` route. It also covers the `TELECOM` route until the companion
binds. While `CallControl.fallbackWanted` (Call controls on, a route available, and no
companion bound), `MediaAccessService` forwards call notifications, and only those,
to `CallControl.fallbackPosted`: `EXTRA_CALL_TYPE` (incoming or screening → RINGING,
ongoing → ACTIVE), the caller (`EXTRA_CALL_PERSON`'s name, else `EXTRA_TITLE`), the
notification's `when` as connected-since, and `EXTRA_ANSWER_INTENT`,
`EXTRA_DECLINE_INTENT` and `EXTRA_HANG_UP_INTENT`. `answer()`, `decline()` and `hangUp()`
send those intents (with background activity starts allowed on API 34+, in case the dialer
opens its screen). `CallState.source = NOTIFICATION` sets `canMute` and `canDial` to false.
A companion binding takes over and drops the fallback's state. Turning Call controls on
asks the listener to re-read the shade once, so a call already ringing is picked up.

Unverified: whether the Pixel's dialer (Google Phone) posts `CallStyle` with those extras,
whether its `when` is the connect time, and whether its intents accept a send from another
app.

## 5. Consent text (plan §9 item 6)

Rewritten to say exactly what is read and when: the manifest comment above
`MediaAccessService`, the class KDoc, and `media_access_prompt`. The new text says
notifications are read only when dashboard notifications are on (alerting ones) or Call
controls are on (call notifications, in the fallback), are held in memory for the drive
only, and are never saved or reported. With those off, none are read.

## 6. API — what the dashboard and settings slices code against

Threads are stated for every callback. "Any thread" means safe to call from the render or
touch thread.

### 6.1 Signal — `PhoneStatus`, `PhoneSnapshot`, `SimSignal`

| Member | Meaning |
| --- | --- |
| `PhoneSnapshot.signalBars: Int` | Primary SIM: 0..4; 0 = out of service (draw empty bars); -1 = no SIM / no grant / unread (`—`) |
| `PhoneSnapshot.secondarySignalBars: List<Int>` | 0..3 rows under the bars, slot order; each 0..4, or -1 = hollow |
| `PhoneSnapshot.cellNetworkType: String` | `5G` `LTE` `H+` `H` `3G` `E` `2G` `-`; primary's |
| `PhoneSnapshot.network: String` | Unchanged: `wifi` `cell` `eth` `other` `none` |
| `SimSignal.MAX_SECONDARY = 3` | |
| `SimSignal.arrange(List<SubscriptionSignal>, defaultData): SignalRows` | Pure |
| `SimSignal.networkLabel(networkType, overrideType): String` | Pure |

`PhoneStatus`'s `onChange(PhoneSnapshot)` arrives on the **main thread**, as before.

### 6.2 Players — `Player`, `Players`, `MediaPlayers`, `ResumeResult`

| Member | Meaning |
| --- | --- |
| `Player(key, label, hasSession, hasBrowser, lastActiveMillis)` | Pure value; `label` is drawn, never logged |
| `Players.merge(sessions, browsers, played = []): List<Player>`, `Players.MAX = 12`, `Players.UNPLAYED_MAX = 3` | Pure ordering; `played` is the remembered players, most recent first |
| `PlayedStore(context)`: `recent()`, `remember(pkg)` | Packages seen playing, app-private preferences; worker thread |
| `MediaPlayers(context, onPlayers: (List<Player>) -> Unit)` | `onPlayers` on the **`aap-players` thread** |
| `start()` / `release()` | Lists once and follows session changes / disconnects and stops the thread |
| `refresh()` | Any thread; lists again (e.g. when the pane goes idle, or access was just granted) |
| `resume(player, onResult: (ResumeResult) -> Unit)` | Any thread; `onResult` on **`aap-players`** within 5 s |
| `ResumeResult` | `PLAYED_SESSION`, `PLAYED_RECENT`, `NOT_FOUND`, `REFUSED`, `TIMED_OUT` |
| `MediaPlayers.RESUME_TIMEOUT_MS = 5000`, `HOLD_MS = 10000` | |
| `report(): String` | Counts only; since part 2b also `media_players_no_launcher` and `media_players_remembered` |

Without notification access, `hasSession` is never true and the pane should say once,
small, that more can be listed with access (`MediaAccessService.enabled(context)`).

### 6.3 Notifications — `NotificationFeed`, `DashNotification`, `Speaker`

| Member | Meaning |
| --- | --- |
| `NotificationFeed.enabled: Boolean` | Setting "Notifications on the dashboard"; default false. Any thread |
| `NotificationFeed.paused: Boolean` | Ⓟ. Any thread. Un-pausing re-reads the shade |
| `NotificationFeed.exclusions: Set<String>` | Excluded packages; setting it drops their rows. Any thread |
| `NotificationFeed.active: Boolean` | `enabled && !paused` |
| `NotificationFeed.items(): List<DashNotification>` | Newest first, ≤ `MAX` = 20; a copy. Any thread |
| `NotificationFeed.posters(): Set<String>` | Packages seen posting a shown or excluded notification, for the exclusion list |
| `NotificationFeed.addListener((List<DashNotification>) -> Unit)` / `removeListener` | Called with the new rows: on the **main thread** for arrivals, removals and re-reads; on the **caller's thread** for a change to `enabled`, `paused` or `exclusions` |
| `NotificationFeed.reread()` | Asks the listener for the active notifications; answer arrives on main |
| `NotificationFeed.report(): String` | Counts only |
| `DashNotification(key, appLabel, title, text, postedMillis, packageName)` | Pure; draw `appLabel` + `title` large, `text` small |
| `Speaker.read(DashNotification)`, `Speaker.reads` | Read-aloud stub: counts |
| `NotificationRules.verdict/row/isCall/clean` | Pure filter, for tests |

Nothing is read until `enabled` is set true, and the listener has to be granted too.

### 6.4 Calls — `CallControl`, `CallState`, `CallRules`

| Member | Meaning |
| --- | --- |
| `CallControl.setEnabled(context, on): CallRoute` | Any thread. On when a session projects with Call controls on; off when it ends or the setting goes off. Enables the in-call service only for `TELECOM` |
| `CallControl.route: CallRoute` | This session's route: `OFF TELECOM NOTIFICATION UNAVAILABLE` |
| `CallControl.fullControlsGranted(context): Boolean` | Whether the app-op is allowed (mute and keypad possible). Settings can show it live |
| `CallControl.GRANT_COMMAND` | `adb shell appops set --user 0 org.openautoandroid.source MANAGE_ONGOING_CALLS allow`, for "To enable mute and keypad, run once from a computer: …" |
| `CallRules.route(settingOn, ongoingCallsAllowed, notificationAccess)` | Pure route choice |
| `CallControl.resetAtStartup(context)` | Already called from `SourceApplication.onCreate` |
| `CallControl.componentEnabled(context)` | Whether Android has it enabled now |
| `CallControl.wanted`, `CallControl.fallbackWanted` | Last `setEnabled`; and whether the notification fallback is the route |
| `CallControl.state: CallState` | The call to draw now. Any thread |
| `CallControl.addListener((CallState) -> Unit)` / `removeListener` | Called on the **main thread** on every change |
| `answer()`, `decline()`, `hangUp()` | Any thread; post to main. Work from Telecom or the fallback |
| `setMuted(Boolean)` | Any thread; only while `state.canMute`. The drawn state follows `state.muted` |
| `dtmfDown(Char)`, `dtmfUp()` | Any thread; `0-9 * #` only, while `state.canDial`. Call `dtmfUp` on release **and** on cancel |
| `CallControl.report(): String` | Counts only |
| `CallState(phase, caller, connectedSinceMillis, muted, source)`, `canMute`, `canDial`, `CallState.NONE` | Pure; `caller` is drawn, never logged |
| `CallPhase` | `NONE RINGING DIALING ACTIVE HOLDING ENDED` |
| `CallSource` | `NONE TELECOM NOTIFICATION` |

The duration is `now - connectedSinceMillis` (wall clock) while `phase == ACTIVE` and it is
not -1. The keypad should close when `phase` leaves `ACTIVE`; `CallControl` stops any tone
itself when a call ends.

## 7. Not done here — for the wiring slices

These need files this slice does not own:

- **Report.** `MediaPlayers.report()`, `NotificationFeed.report()` and `CallControl.report()`
  are not yet in the session report. `PhoneStatus.report()` already is. They need
  appending where `SessionReport`/`ProjectionService` compose it.
- **Lifecycle.** `MediaPlayers` should be created and started with the dashboard session
  (next to `PhoneStatus` in `ProjectionService.startProjection`) and released in
  `stopProjection`. `CallControl.setEnabled(context, settings.callControls)` belongs at
  projection start and `setEnabled(context, false)` at the end.
- **Settings.** `NotificationFeed.enabled/paused/exclusions` from `AppSettings`
  (`notificationsOnDashboard`, `notificationsPaused`, `notificationExclusions`); the
  exclusion list's candidates from `NotificationFeed.posters()`.
- **`tools/verify-apk.sh`** (not in this slice's ownership, edited to keep the shared
  build green, and flagged): the exact permission list gains `MANAGE_ONGOING_CALLS`; the
  service count goes from 2 to 3; `CallCompanionService` is pinned as disabled, guarded by
  `BIND_INCALL_SERVICE`, with no in-call UI meta-data and no foreground type; and the
  `<queries>` entry is pinned, with no `<package>` element. Every assertion is as strict as
  before or stricter; nothing was loosened.
- **`PhoneSnapshot`** (`Dashboard.kt`, whose next owner is the dashboard agent) got
  `@JvmOverloads` on its constructor as well as the two fields. `DashboardTest.java` builds
  it with all ten original arguments, and Java sees no Kotlin defaults, so adding fields
  alone broke that test's compile.

## 8. Verified, and what needs the Pixel or the truck

Verified: `tools/android-build.sh` (clippy, both APKs, `lintDebug` with warnings as
errors, `verify-apk` on all three APKs, and every host test), run under the P7 build lock.
New host tests: `SimSignalTest` (26 checks), `PlayersTest` (9), `NotificationFeedTest` (47),
`CallStateTest` (51, including the route for every combination of setting, grant and access); every existing host test still passes. `EXTRA_CALL_TYPE` is read by
its key string, `android.callType`, because the SDK constant is public only from API 34.
`CallStyle` has written that key since API 31.

Needs the Pixel:

1. Per-subscription callbacks on a real second subscription (only one SIM is in hand;
   plan §5.2's evidence).
2. `cellNetworkType` against the Pixel's own status bar, 5G NSA especially.
3. Resume from a live session, and a cold resume of a killed player (YouTube Music,
   Pocket Casts, …), with the 5 s timeout and 10 s hold.
4. Notification labels (package visibility, §3), the filter against real apps
   (messaging, mail, a chatty ongoing app), removal, and re-read on un-pause.
5. **Whether Telecom binds `CallCompanionService` once the app-op is allowed over adb**
   (§4.2), whether `fullControlsGranted` agrees with Telecom, and the notification
   route's intents from Google Phone.
6. On the truck: a call while projecting with HFP paired (plan §5.8's SYNC question).
