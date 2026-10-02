# P10 plan — weather from Breezy Weather — 28 September 2026

The route and the decisions in §11 were settled by James on 28 and 29 September 2026.
**Status 30 September 2026:** P10.1–P10.3 built on James's "Implement P10", and the §9
amendments applied. The build is recorded in `docs/p10-results.md` (with the deviations
from this plan) and the lab in `docs/p10-lab-results.md`. P10.0 (a real blob from the Pixel)
and P10.4 are not done, so the field names in §1 are still unconfirmed.

The research behind this plan is `docs/weather-research.md`, which answers
`docs/p7-plan.md` §5.9. In short: Breezy Weather, already on James's phone, exposes a
permission-guarded **content provider**. It also has an **update-notifier** broadcast that
says when a location has new weather. No app can make Breezy refresh. P10 reads the
provider and shows the current weather in the **header**. Tapping it opens current and
upcoming weather in the flex tile. A weather **alert** takes the tile by itself and is
spoken when voice is on. No network is involved.

---

## 0. Scope, and what is deliberately outside it

In scope:

- Reading Breezy's provider: current conditions, the next few hours, and active alerts,
  for one location.
- **Weather in the header**: a condition glyph and the temperature (§5.1).
- **Tapping it** opens current and upcoming weather in the flex tile, except during a call
  (§5.2).
- An **active alert** outranking notifications in the flex tile, with its headline
  **spoken once** when the voice toggle is on (§6).
- A **Weather** settings card, Setup items, report counts and a lab run.

Out of scope, stated so it is not assumed:

- **Asking Breezy to refresh.** It has no way to do that, and decision 4 is not to ask
  upstream. P10 shows the last refresh and says how old it is.
- **The Gadgetbridge broadcast.** Decision 1 chose the provider. That broadcast has no
  alerts, and anyone can forge one.
- **Breezy's data-sharing library.** It is published only through JitPack at a commit,
  brings kotlinx.serialization, and calls itself not ready. P10 reads the cursor and the
  JSON itself, as P9 did for HERE, so AndroidX stays at zero and nothing new is resolved.
- **Weather on the map** (radar, alert polygons), **weather along the route ahead**,
  forecasts beyond today, minute-by-minute rain, air quality, pollen. The provider carries some
  of these; P10 does not show them.
- **Any network access** for weather. APP10's live-traffic amendment is not touched.

## 1. The provider contract P10 is written against

From Breezy's source at `d587328a` and the library at `628de1cf`
(`docs/weather-research.md` §1.2). **Schema names are not evidence**: P10.0 checks each
one against a real blob from the Pixel before anything is drawn from it.

| URI (authority `org.breezyweather.provider.weather`) | Used for |
| --- | --- |
| `content://…/version` → `major`, `minor` | Refuse any `major` other than **0**. Currently 0.2. |
| `content://…/locations?limit=10` | Location ids, lat/lon, `is_current_position`, `city`, `custom_name`. |
| `content://…/weather?withMinutely=false&withNormals=false`, `selection = "id=<id>"` | One row. Its `weather` column is gzipped JSON (`BreezyWeather`). |

Units are left at Breezy's defaults, which are James's own Breezy settings. Every value
arrives tagged with its unit, and P10 prints the tag it was given. It does not convert
units.

What P10 reads from the JSON, and nothing else:

- `refreshTime`
- `current`: `weatherText`, `weatherCode`, `temperature.temperature`,
  `temperature.sourceFeelsLike` / `computedApparent`, `wind.speed`
- `daily[0]`, today only: the high and low (`day` and `night` temperatures)
- `hourly[]`, the next 6 entries after now: `date`, `isDaylight`, `weatherCode`,
  `temperature.temperature`, `precipitationProbability.total`
- `alerts[]`: `alertId`, `startDate`, `endDate`, `headline`, `severity` (0 unknown,
  1 minor, 2 moderate, 3 severe, 4 extreme), `color`. Breezy returns every alert it holds,
  so P10 drops alerts whose `endDate` has passed or whose `startDate` is still ahead.

Unknown fields are ignored and missing ones are null. A blob that fails to parse is shown
as "Weather unreadable" and counted, never thrown.

## 2. Which location

Breezy may hold several locations. P10 picks, in order:

1. `CURRENT_POSITION`, if Breezy has current location turned on.
2. Otherwise, the location nearest the phone's last fix (from the location permission
   P5.5 already asks for).
3. Otherwise, Breezy's first location.

Location ids are only good for one query (the library says ids can change), so P10 lists
locations again on each read. It never saves an id.

**Staleness is shown, not hidden.** Breezy's current position moves only when Breezy
refreshes, so in a moving truck the weather can be for somewhere 50 km back. Every view
shows the **place name** and the reading's **age** ("Waco · 40 min ago"). A reading
older than **3 hours** is drawn dimmed with "old". A location more than **50 km** from
the phone's fix is labelled with the distance ("Waco · 62 km away"). Both figures are
settings defaults (§4), open to change after a drive.

## 3. When it reads

Nothing is read while no projection is attached. While one is, P10 reads:

- **when a projection starts**;
- **when Breezy's update notifier arrives** (`org.breezyweather.ACTION_UPDATE_NOTIFIER`,
  extras `UpdatedLocationIds`, `AllLocationIds`). It carries no weather, so P10 only
  treats it as a cue to re-read. A forged one can only cause a harmless extra read;
- **every 15 minutes** as a backstop, in case the notifier is unticked in Breezy or Breezy
  is set never to refresh on its own. Each read is a few kilobytes from a local
  provider.

Reads run off the main thread, one at a time. A read that is still running when the
next one is due is skipped, not queued.

**Checking whose provider it is.** Before each read P10 resolves the authority with
`PackageManager.resolveContentProvider` and requires its package to be `org.breezyweather`.
Any other package, including Breezy's debug build `org.breezyweather.debug`, is refused
and counted, except in the debug build of this app, for the lab (§8). The permission
only lets Breezy grant access. This check stops another app declaring the same authority
first.

The latest reading is held **in memory only**. It is replaced by the next read and
dropped when the projection ends. Nothing about weather is written to disk except the
settings in §4 and the counts in §7.

## 4. The Weather settings card (P7.7's structure)

Under **Settings → Weather**:

- **Show weather** on/off. **Off by default** (APP09). Turning it on asks for
  `org.breezyweather.READ_PROVIDER`, which Android shows as Breezy's own permission
  prompt ("read your weather data").
- **Status line**: one of
  - "Breezy Weather not installed"
  - "Permission not granted" (with a button)
  - "Breezy Weather version not supported (provider 1.x)"
  - "No locations in Breezy"
  - "Waco · updated 12 min ago"
- **Speak weather alerts** on/off, **on by default**, and heard only while the dashboard
  voice toggle is on (§6).
- **Alerts shown from**: Minor / Moderate / **Severe** (default) / Extreme. Alerts below
  this still appear in the weather view. They just don't take the tile or speak.
- A line of help: "In Breezy Weather, Settings → Modules → tick OpenAuto under
  *Breezy Weather update notifier* so the car hears about new weather straight away."

Setup (P7.7 §6.1) gains **"Weather permission not granted"** (with a Grant button), but
only while **Show weather** is on. With it off, weather adds nothing to Setup.

## 5. On the dashboard

### 5.1 In the header (decision 6)

A **weather item** sits in the header, immediately left of Ⓟ: the current condition
glyph and the temperature ("⛅ 78°F"). It is drawn only while **Show weather** is on.

- **Geometry.** A new `DashboardLayout.headerWeather` box, full header height (a
  header-height target like Ⓟ and ☀/☾, P7.3), a margin left of `headerIcon(0)`, and a
  fixed width of about 2.2 headers, so the clock and speed never move as the
  temperature changes. `headerTextRight` becomes that box's left edge less a margin.
  Its hit test cannot reach Ⓟ, and host tests check both layouts.
- **Glyphs.** Drawn on the canvas like the maneuver icons, one per Breezy code (`clear`,
  `partly_cloudy`, `cloudy`, `rain`, `snow`, `wind`, `fog`, `haze`, `sleet`, `hail`,
  `thunder`, `thunderstorm`), with a moon variant for `clear` and `partly_cloudy` at night.
  The header and the flex tile use the same glyphs.
- **States:**
  - No reading yet, or the provider refused: a dimmed "—" where the temperature goes.
  - A reading older than the stale limit (§2): drawn dimmed.
  - An alert qualifies (§5.3): the glyph gets the voice toggle's small warning mark (P8
    §6), in the alert's colour.
- **Fit on the truck.** The 800×480 frame leaves about 150 px between the bearing text and
  Ⓟ. The lab renders the header at that size (§8). If it does not fit, the unit letter
  goes first ("78°"). The compass, heading and bearing never go (P7 §1, item 9).

### 5.2 Tapped: the weather view (decision 6)

A completed press on the header item (press and release on the same target, as for the
other header icons) **opens the weather view in the flex tile**:

- **Not during a call.** While a call is in any phase but `NONE`, the press does nothing:
  the item doesn't highlight or open anything, and the press is counted
  (`weather_taps_in_call`). The call keeps the tile.
- **Closing.** Tapping the header item again closes it, and so does tapping the view
  itself. With no tap, it closes by itself after **30 s**, so notifications are never
  hidden for long by a view someone forgot. A call arriving closes it too, and it doesn't
  come back after the call.
- **With Show weather on but no reading**, the view opens with the status line from §4
  ("Permission not granted", "Breezy Weather not installed", …), so the tap always says
  something.

**What it shows**, sized for the flex tile at the truck's pane size (about 190 px
square on the 800×480 frame):

- **Now:** the glyph, the temperature (large), the condition text, "Feels 84°" when it
  differs by 3° or more, and today's high and low ("H 91° L 74°").
- **Upcoming:** a row of the next **4 hours**: hour, glyph and temperature, with a rain
  percentage when it is 20 % or more.
- **An alert strip**, if any alert is active, including ones below the "Alerts shown
  from" level: the most severe one's colour and a one-line headline above the "Now"
  block. The full alert view, for alerts at or above the level, is §5.3.
- The place and age line from §2 along the bottom. The tile label reads "weather".

### 5.3 Flex tile order

`FlexRules.source` gains two sources, and becomes:

1. **A call**, in any phase but `NONE` (unchanged; nothing outranks it).
2. **The weather view**, while opened from the header (§5.2). **New in P10.** The user
   asked for it, so it goes above an alert. It carries the alert strip, so nothing is
   hidden.
3. **A weather alert**: active, at or above the "Alerts shown from" level, and not
   dismissed. **New in P10** (decision 2). It outranks notifications, and Ⓟ does not hide
   it, since Ⓟ pauses notifications, not safety information.
4. **Notifications**, while not paused and there are any (unchanged).
5. **The empty state** (unchanged).

Weather no longer fills an otherwise empty tile by itself, as the 28 September draft
had it. The header now shows the weather at all times, and the tile shows it on request.

**The alert view** (source 3): the alert's `color` as a bar down the left side, the
headline wrapped to at most three lines, and "until 9:45 PM" from `endDate`. The place
and age line runs along the bottom, and the label reads "weather alert". When more than
one alert qualifies, the most severe shows first, then the one ending soonest, with "+1
more". **Tapping** the alert dismisses it for this session and returns the tile to
whatever source is next. A dismissed alert that comes back with a new `alertId` (an
upgrade or extension) shows again.

The header item and both views are rendered at the truck's frame size, day and night,
in the lab (§8) for James to judge before the drive.

## 6. Speaking an alert (decision 2: "yes, and if unmuted")

- **"Unmuted" is read as the trip panel's voice toggle** (P8.2, `voice_guidance`), the
  one speaker control on the dashboard. It is saved, so it holds with no route up.
  The P7 microphone mute during calls is a different thing and plays no part. **To
  confirm with James** (§11, question 5).
- An alert is spoken **once per `alertId` per session**: the first time it qualifies
  (§5), if **Speak weather alerts** is on and the voice toggle is on. What is said is
  "Weather alert. " followed by the headline, capped like notifications (P8 §8, about 400
  characters). The description and instructions are not read.
- It goes through `Speaker` as a new `SpeechKind.ALERT` (report prefix
  `weather_alerts`), with the same Bluetooth gate, focus handling and media pause or duck
  as the rest of P8. When the gate is shut (no engine, not on A2DP), it is counted as
  skipped, **and not retried later**. The tile still shows the alert.
- **Priority**: an alert waits for a turn prompt being spoken, then goes before the next
  one. It never cuts a prompt off. A notification being read aloud is cut off by an alert.
- Turning the voice toggle on does not replay alerts already skipped. Tapping the alert
  view does not speak it either: tapping dismisses (§5).

## 7. Reports and privacy

The report carries counts only, never a place name, coordinate, headline, temperature or
condition:

- `weather_enabled`, `weather_permission`, `weather_provider` (`ok`, `missing`,
  `wrong_package`, `unsupported_major`)
- `weather_reads`, `weather_read_failures`, `weather_notifier_cues`
- `weather_reading_age_min` (at session end), `weather_far` (the 50 km label was shown)
- `weather_view_opens`, `weather_view_closes` by tap and by timeout,
  `weather_taps_in_call`
- `weather_alerts_shown`, `weather_alerts_dismissed`, plus the `weather_alerts_*` speech
  counts from `SpeechQueue`

The APP09 integration list gains: "**Weather** (off by default): reads Breezy Weather's
data on the phone through its content provider, with a permission you grant. No network.
Nothing kept after the drive."

## 8. The lab

The emulator has no Breezy. The lab needs a stand-in Breezy that the real code path
accepts:

- **`tools/breezy-standin/`**: a tiny APK, package **`org.breezyweather`**, that
  declares the `READ_PROVIDER` permission (`dangerous`) and serves the three URIs from
  fixed gzipped blobs. It has no Breezy code. Blobs are written by
  `tools/jni-host-test/LabWeather.java` from the scenarios below, in the shapes P10.0
  captured. It is installed only on the emulator, never on the Pixel, where the real
  package name belongs to the real Breezy.
- **`LAB_WEATHER=show|tap|alert|stale|off|notifier`** in `tools/emulator-lab.sh`:
  1. **show**: the header draws the fixture's glyph and temperature. The receiver taps
     it, and the flex tile draws the weather view with the fixture's temperature, high
     and low, four hours and place. Rendered at the truck's 800×480 frame and at 1080p,
     day and night, into `docs/images/p10/`. The header is checked for overlap between
     the bearing text and the weather item.
  2. **tap**: a second tap closes the view, a tap on the view closes it, and an untouched
     view closes at 30 s. Then a modem call is placed (`adb emu gsm call`, as the `calls`
     script does), and a header tap during it leaves the call on the tile and counts
     `weather_taps_in_call=1`.
  3. **alert**: a severe alert takes the tile over a showing notification, and the
     header glyph carries the warning mark. With the
     voice toggle on, `weather_alerts_started=1` using the silent stand-in engine
     (`LAB_SPEECH_ENGINE`). With the toggle off, it is counted `toggle_off` and still
     shown. A tap dismisses it and the notification returns.
  4. **stale**: a 4-hour-old reading 70 km away draws dimmed with "old" and "70 km
     away", in the header and in the view.
  5. **off**: with Show weather off, the header has no weather item, the text runs as
     it does today, and the stand-in's query log shows **no** query at all.
  6. **notifier**: the stand-in changes its blob, `adb shell am broadcast -a
     org.breezyweather.ACTION_UPDATE_NOTIFIER -n <our receiver>` is sent, and the header
     shows the new temperature within 2 s.

## 9. Spec and plan amendments this needs (proposed, not applied)

- **spec.md §5.2**: the manifest gains a **ninth** permission,
  `org.breezyweather.READ_PROVIDER`. It is a third-party `dangerous` permission, asked
  for only when Show weather is turned on, and degrades to "—" per APP09. It is not a
  network permission, and APP10 is unaffected. The sentence about `tools/verify-apk.sh`
  gains it in the pinned permission set.
- **spec.md APP09** integration list: the Weather entry in §7.
- The same §5.2 paragraph names the two manifest additions that come with it, since
  spec.md does not otherwise list receivers or `<queries>`. The first is one exported
  `<receiver>` for the update notifier. It is guarded by no permission, since Breezy
  sends none and the receiver's input is only a cue. The second is
  `<queries><provider android:authorities="org.breezyweather.provider.weather"/></queries>`.
- **plan.md**: a P10 entry pointing here.
- **docs/p7-plan.md §5.9**: "Built as P10".

## 10. Host tests

- Parsing real captured blobs (P10.0) and hand-cut ones: all fields present; every
  optional field missing; unknown fields; a blob that fails to gunzip; valid JSON of the
  wrong shape; `major` = 1.
- Location choice (§2): current position present; absent, with nearest by fix; no fix;
  no locations.
- Staleness and distance labels at their boundaries.
- Alert filtering: expired, not yet started, below the level, dismissed, new id after
  dismissal; ordering by severity then end time.
- `FlexRules.source` with every combination of call, opened view, alert,
  notifications and Ⓟ.
- Header layout at 800×480 and 1920×1080: the weather box never overlaps Ⓟ or the text
  limit, and the unit letter drops when the text would not fit.
- The view's opening and closing: a tap during a call is ignored and counted; a second
  tap, a tap on the view and the 30 s timeout each close it; a call closes it, and it
  does not come back afterwards.
- Speech: once per id; the toggle off gives `toggle_off`; setting off → not asked;
  a prompt being spoken is not cut off; a notification being read is.
- Provider-owner check: `org.breezyweather` accepted; any other package refused, and
  `org.breezyweather.debug` refused outside the debug build.
- With Show weather off, the reader is never called (injected, like P9's HTTP layer).

## 11. Decisions — 28 and 29 September 2026

| # | Question | Decision |
| --- | --- | --- |
| 1 | Provider or Gadgetbridge broadcast | **Provider.** |
| 2 | May an alert take the tile over notifications, and be read aloud | **Yes, and read aloud if unmuted** (§5, §6). |
| 3 | Numbering | **P10.** |
| 4 | Ask Breezy upstream for a refresh request | **No.** |
| 5 | What "unmuted" means | *Open.* This plan reads it as the P8.2 voice toggle (§6). |
| 6 | Weather in the header; tapping it shows current and upcoming weather in the flex tile, except during a call (29 September) | **Yes** (§5.1, §5.2). Consequently, weather no longer fills an empty tile by itself. |

Defaults open to change after the drive: the 3 h stale and 50 km far labels, the Severe
alert level, the 15-minute backstop, 4 hours in the row, and the view's 30 s timeout.

## 12. Slices

| Slice | What | Done when |
| --- | --- | --- |
| **P10.0** | Breezy's provider read on the Pixel with `adb shell content query` (permission granted by `pm grant`). One blob per location captured into `~/.cache`, not git. P10.0 also records Breezy's version and whether the update notifier's list shows the app once it declares the receiver. | The fields in §1 are confirmed or corrected from real data, and fixtures are hand-written from what the captures showed. |
| **P10.1** | §9 amendments applied; permission, receiver and `<queries>` in the manifest; `verify-apk.sh`; the Weather card; the provider-owner check; the reader and parser. | §10's parser, location and owner tests pass; the Pixel's card shows "Waco · updated N min ago" from real Breezy. |
| **P10.2** | The header item, the tapped weather view, the new tile order; Setup item; report counts. | `LAB_WEATHER` show, tap, stale, off and notifier pass; header and view renders at 800×480 for James. |
| **P10.3** | Alerts in the tile, dismissal, and speaking them. | `LAB_WEATHER=alert` passes; speech host tests pass. |
| **P10.4** | Pixel, then truck. | The header shows real weather on SYNC 4, and a tap opens the view. James judges both, the stale label on a real drive, and (if the weather obliges) a real alert. |

## 13. Risks

- **Breezy changes the provider.** It is at 0.x, with breaking changes expected. The
  major check turns a break into a plain status line rather than wrong numbers. Parsing
  is lenient about everything that is not needed.
- **Stale weather in a moving truck** (§2). It is the main limitation, and the reason age
  and distance are always on screen.
- **The permission is Breezy's.** If Breezy is uninstalled and something else claims the
  permission name, the owner check refuses its provider.
- **The update notifier is opt-in on Breezy's side**, and the `pm grant`/tick steps are
  easy to miss. The status line, the Setup item and the 15-minute backstop cover it.
- **Header room on the truck.** About 150 px at 800×480 is a first estimate from the
  layout. The lab render decides it, and the unit letter is the first thing to go.
- **The stand-in shares Breezy's package name.** It must never reach the Pixel. The lab
  script installs it on the emulator only, and it is not built by `android-build.sh`.
