# Weather from Breezy Weather — research record — 28 September 2026

This answers the three questions of `docs/p7-plan.md` §5.9. That section holds weather
back from design until they are answered. **Nothing is built.**
It ends with a proposed shape and the decisions it needs from James. **James decided on
28 September 2026** (provider; alerts take the tile and speak when unmuted; P10; no
upstream ask). The plan is `docs/p10-plan.md`.

Evidence is Breezy Weather's own source, not its documentation:

- `breezy-weather/breezy-weather` at `d587328ae9755c8801f8cbc252a16ead7ebaea5a`
  (13 September 2026, version 6.2.3 unreleased; 6.2.2 is the latest release)
- `breezy-weather/breezy-weather-data-sharing-lib` at
  `628de1cfd5395e4501da3470e95b1d3fe59ee907`, the commit Breezy itself pins

Nothing here has been run against Breezy on the Pixel yet.

---

## 1. How Breezy shares weather

Breezy has two routes out, and both are opt-in per app on the Breezy side.

### 1.1 The Gadgetbridge broadcast: not addressed to Gadgetbridge only

`GadgetbridgeService` is a `BroadcastSource` with action
`nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER`.
`RefreshHelper.broadcastDataIfNecessary` does not hard-code a package. Breezy's
**Settings → Modules → "Send Gadgetbridge data"** screen lists every installed package
that `queryBroadcastReceivers` finds for that action, the user ticks the ones they want,
and Breezy sends one explicit broadcast to each (`setPackage(it)`,
`FLAG_INCLUDE_STOPPED_PACKAGES`).

So this app would appear in that list under its own name if its manifest declared a
receiver for the action. It would not be posing as Gadgetbridge. It would be using a
share list that Breezy offers to any app declaring the receiver.

It sends after every refresh, and whenever the user changes the ticked list.

### 1.2 Something else: a content provider plus an "update notifier" broadcast

Added in Breezy 6.1.0 (1 February 2026):

- **`WeatherContentProvider`**, authority `org.breezyweather.provider.weather`
  (`org.breezyweather.debug.provider.weather` for debug builds). It is exported and
  guarded by `org.breezyweather.READ_PROVIDER`, a **`dangerous`** permission. The reading
  app declares `<uses-permission>`, and the user grants it at runtime like location. Its
  `isAllowedPackage()` currently returns `true` for everyone who holds the permission.
  URIs:
  - `content://…/version`: columns `major`, `minor`. Currently **0.2**.
  - `content://…/locations?limit=N`: one row per Breezy location (id, lat/lon,
    `is_current_position`, timezone, place-name columns); the `weather` column is always
    null here.
  - `content://…/weather?withDaily=&withHourly=&withMinutely=&withAlerts=&withNormals=&temperatureUnit=&speedUnit=…`
    with `selection = "id=<id>"`: one row whose `weather` column is a **gzipped JSON
    blob** (`BreezyWeather`). `id=CURRENT_POSITION` is accepted directly. Units default
    to the user's Breezy preferences, and each value comes back tagged with its unit.
- **`org.breezyweather.ACTION_UPDATE_NOTIFIER`**, a second `BroadcastSource` on the same
  ticked-package mechanism as §1.1. Its extras are only `UpdatedLocationIds` and
  `AllLocationIds` (string arrays), with no weather. It tells a reader when to query the
  provider again.

**The catch:** the library README opens with "THIS LIBRARY IS NOT YET READY TO BE USED".
The provider is version **0.x**, and the announcement (discussion #2089) calls the
library snapshots "which may contain breaking changes". The library is published only
through JitPack at a commit hash, and it brings kotlinx.serialization. This app would
read the cursor and the JSON itself and not take the library (no dependency, AndroidX
stays at zero), and it would refuse any major version other than the one it was written
for.

### 1.3 Requesting an update: not possible

Nothing another app can reach starts a Breezy refresh. The only caller of
`WeatherUpdateJob.startNow` is Breezy's own debug-settings screen. The exported
components are activities, widget providers, the boot receiver, the live wallpaper, the
quick-settings tile and the provider. None of them takes a refresh request.

What this app can do instead:

- **Read on demand.** Query the provider when a projection starts, or whenever the tile
  is about to be shown. The answer is Breezy's **last** refresh, not a new one.
- **Be told.** Get the Gadgetbridge or update-notifier broadcast when Breezy refreshes by
  itself. Breezy's background interval is 30 min, 1, 1.5, 2, 3, 6, 12 or 24 h, or never.
- **Ask upstream.** An exported, permission-guarded "refresh now" would be a new Breezy
  feature. Nothing like it exists or has been announced.

This matters in a moving truck. Breezy's `CURRENT_POSITION` location only moves when
Breezy refreshes, so at the 30-minute interval the "current" weather can be a place
50 km behind. The tile has to show **where** and **how old** every time.

## 2. What the payload carries

| | Gadgetbridge broadcast (§1.1) | Content provider (§1.2) |
| --- | --- | --- |
| Current conditions | temp, feels-like, condition text + OWM-style code, humidity, wind, UV, dew point, pressure, cloud, visibility, AQI | the same and more; Breezy's own code (`clear`, `partly_cloudy`, `cloudy`, `rain`, `snow`, `wind`, `fog`, `haze`, `sleet`, `hail`, `thunder`, `thunderstorm`) |
| Hourly | yes (`hourly`: temp, code, precip probability, wind, …) | yes, plus precipitation amount and daylight flag |
| Daily | yes, from tomorrow (`forecasts`) | yes |
| Minute-by-minute rain | no | yes (`minutely`), where the source has it |
| **Alerts** | **no** | **yes**: headline, description, instruction, severity, colour, start/end |
| Units | fixed: **Kelvin**, km/h, hPa, metres | the user's Breezy units, each value tagged |
| Location | place name, lat/lon, `isCurrentLocation` | full place fields, lat/lon, timezone |
| Freshness | `timestamp` (forecast update time) | `refreshTime` |
| Format | extras `WeatherJson` (first location, JSON) and `WeatherGz` (all locations, gzipped JSON array) | gzipped JSON in a cursor blob |
| Stability | long-standing Gadgetbridge `WeatherSpec` contract, many consumers | pre-1.0, may break |
| Our side needs | an exported receiver | a `dangerous` permission granted at runtime, plus an exported receiver if we want the notifier |

For the tile as the plan sketches it ("conditions, a temperature, the next few hours"),
either route is enough. Alerts are only on the provider. For driving in Texas, a
severe-thunderstorm or flash-flood warning is the one weather item worth interrupting
for.

## 3. Proposed shape (not approved)

1. **Read from the provider**, triggered three ways: when a projection starts, when the
   update notifier arrives, and on a slow timer while projecting (Breezy may be set to
   "never" refresh or have the notifier unticked). Query `id=CURRENT_POSITION` if it
   exists, otherwise the first location. Pass `withMinutely=false&withNormals=false` and
   the daily/hourly/alerts flags the tile needs. Check `version` first and show "Breezy
   Weather version not supported" on any major other than 0.
2. **Verify the provider**: resolve the authority and check it belongs to the
   `org.breezyweather` package before reading. The notifier broadcast carries no data,
   so a spoofed one can only cause a harmless re-read.
3. **A pure reader** (`WeatherReading.kt`: gzip → JSON → a small model of current,
   next hours and active alerts), hand-written like the traffic parser so it is
   host-testable without Android. Test fixtures would be blobs captured from Breezy on
   the Pixel.
4. **Flex tile**: weather slots in between notifications and the empty state, where
   `docs/p7-8-9-results.md` §1.1 left room. An **active alert** would rank above
   notifications. Every view shows the place name and the data's age.
5. **Setup checklist** items: "Breezy Weather not installed", "weather permission not
   granted", "tick OpenAuto in Breezy → Settings → Modules → update notifier".
6. **Lab**: a stand-in provider app (or a debug-build provider under the
   `org.breezyweather.debug` authority) serving fixed blobs, plus
   `adb shell am broadcast` for the notifier, so `emulator-lab.sh` can render the tile
   without Breezy.

The Gadgetbridge broadcast is the fallback if the provider's instability proves a
problem. It needs no permission, but it loses alerts, and any app on the phone could
send it fake weather (there is no way to check the sender of a broadcast Breezy sends).

## 4. What this needs amended (proposed, not applied)

- **spec.md §5.2**: a ninth permission, `org.breezyweather.READ_PROVIDER` (a
  third-party permission, runtime-granted, optional, degrading to "—" per APP09). Plus
  the matching change to the permission set pinned in `tools/verify-apk.sh`.
- **spec.md APP09 integration list**: "Weather: reads Breezy Weather's local provider
  on the phone; no network; nothing stored beyond the latest reading in memory."
- **docs/p7-plan.md §5.9**: point here, and give it a slice number.
- The manifest gains `<queries>` for the provider authority and, for the notifier, one
  exported `<receiver>`.

## 5. Decisions for James

1. **Route**: provider (alerts, your units, pre-1.0) or the Gadgetbridge broadcast
   (stable, no permission, no alerts)? This record recommends the provider.
2. **Alerts**: may an active weather alert take the flex tile over notifications, and
   should P8 speech read its headline aloud?
3. **Slice number**: a P7 flex-source slice, or a P10 of its own?
4. **Upstream**: is it worth asking Breezy for an exported refresh request (§1.3)?
