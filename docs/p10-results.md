# P10 results — weather from Breezy Weather — 30 September 2026

This records the build of P10.1–P10.3 of `docs/p10-plan.md`, started when James said
"Implement P10". The lab runs are recorded in `docs/p10-lab-results.md`. Nothing here has
run on the Pixel or the truck. Nothing is committed.

## 1. Status by slice

| Slice | State |
| --- | --- |
| P10.0 capture on the Pixel | **Not done.** No phone was on adb. The plan's `adb shell content query` would not have worked anyway (§3.1). A debug-build capture hook stands in its place. |
| P10.1 manifest, card, owner check, reader, parser | Built and host-tested. The card has not been seen on the Pixel. |
| P10.2 header item, tapped view, tile order, Setup, report | Built and host-tested. Lab: see `docs/p10-lab-results.md`. |
| P10.3 alerts in the tile, dismissal, speech | Built and host-tested. Lab: see `docs/p10-lab-results.md`. |
| P10.4 Pixel, then truck | Not started. |

**The field names are still unconfirmed.** The parser and every fixture follow Breezy's
source (`d587328a` and the library at `628de1cf`), not a real blob. This is the
schema-names risk the plan names in §1. P10.0 is the check.

## 2. What was built

App (`app/src/main/kotlin/org/openautoandroid/source/`):

- **`Weather.kt`** (pure):
  - `WeatherSettings` and `AlertLevel`: the §4 card's values, stored as `weather_enabled`, `weather_speak_alerts` and `weather_alert_level`.
  - The model: `WeatherData`, `WeatherHour`, `WeatherAlert`, `WeatherPlace`, `WeatherState` and `WeatherStatus`.
  - `WeatherJson`: gunzip, then P9's `JsonStream`. A blob is capped at 4 MB inflated.
  - `WeatherRules`: the owner check, the location choice, alert filtering and order, and the constants: 3 h stale, 50 km far, 15 min backstop, 30 s view, 4 hours shown, 20 % rain and 3° feels-like thresholds.
  - `WeatherText`: every word shown or spoken.
- **`WeatherFeed.kt`** (pure): the §3 read loop, host-testable through the injected `WeatherSource`, worker, main thread and clock.
- **`WeatherProvider.kt`**:
  - The ContentResolver `WeatherSource`.
  - `WeatherNotifierReceiver`.
  - `object Weather`: start and stop with the projection, a 15 min timer, a one-minute alert tick, and `readOnce` for the card.
  - `capture` for P10.0.
- **`WeatherDisplay.kt`** (pure) and **`WeatherDrawing.kt`**: the header item, the view, the alert card, and twelve drawn glyphs with moon variants.
- **`Dashboard.kt`**:
  - `DashboardLayout.headerWeather`, `headerTextRight(weather)` and `headerWeatherText`.
  - The weather state, taps, the 30 s timeout, a call closing the view, dismissal, and report lines.
- **`FlexTile.kt`**: `FlexSource.WEATHER` and `WEATHER_ALERT`, in plan §5.3's order.
- **`SpeechRules.kt` and `Speaker.kt`**: `SpeechKind.ALERT` (`weather_alerts_*`), `SpeechAction.Wait`, and `Speaker.alert`.
- **`ProjectionService.kt` and `ProjectionPipeline.kt`**: `Weather.start` and `Weather.stop` with the projection, plus report wiring.
- **`ConnectionActivity.kt`, `StatusText.kt` and `strings.xml`**:
  - The Weather card on the Settings tab, between Notifications and Speech.
  - `SetupItem.WEATHER_PERMISSION`, after Location, shown only while Show weather is on.
  - The debug lab extras `lab_weather`, `lab_weather_level`, `lab_weather_speak`, `lab_weather_cue` and `lab_weather_capture`.
- **`AndroidManifest.xml`**:
  - `org.breezyweather.READ_PROVIDER`.
  - The exported, unguarded notifier receiver. Lint's `ExportedReceiver` is waived with the reason written beside it.
  - `<queries><provider android:authorities="org.breezyweather.provider.weather"/>`.

Tools:

- `tools/verify-apk.sh`:
  - pins the ninth permission;
  - requires Breezy's authority to be the only one;
  - requires the notifier's action.
- `tools/jni-host-test/WeatherTest.java` (274 checks since the lab fixes; 259 at first) runs in `jni-host-test.sh`. It also parses the stand-in's own scenario blobs. Other tests changed:
  - `SpeechQueueTest`: the report now has five lines.
  - `StatusTextTest` and `AppSettingsTest`: the Setup item and the three stored keys.
- `tools/breezy-standin/` and `tools/breezy-standin.sh`:
  - The lab-only stand-in Breezy, package `org.breezyweather`.
  - Six scenarios, whose times are resolved at query time.
  - It must never be installed on a phone.
- `tools/emulator-lab.sh` and `tools/lab-receiver/receiver.cpp`: `LAB_WEATHER`. See `docs/p10-lab-results.md`.

The full `tools/android-build.sh` passes, with lint, `verify-apk` on all three APKs, and
every host test.

## 3. Deviations from the plan, for James

### 3.1 P10.0 cannot use `adb shell content query`

The shell user cannot hold Breezy's `dangerous` permission, and `pm grant` only grants to
apps. The debug build therefore has `--ez lab_weather_capture true`. It queries the
provider as this app and writes each blob to `files/weather-capture/`, for `run-as` to
fetch. The event-log summary names no place. The procedure for P10.0 on the Pixel:

1. Install the debug build.
2. Grant Breezy's permission from the card.
3. `adb shell am start --user 0 -n org.openautoandroid.source/.ConnectionActivity --ez lab_weather_capture true`
4. Fetch the files with `run-as` and keep them under `~/.cache`, not git.

### 3.2 Other deviations

- **Times follow the dashboard clock.** The hour row reads "15:00" and alerts "until
  21:45", not the plan's "3 PM" and "9:45 PM". The header clock and the trip panel's arrival
  time are 24-hour, and mixing styles on one screen reads badly. This is a one-line change
  in `WeatherText` if James prefers 12-hour.
- **The header item's spacing.** It is 2.2 headers wide as planned, but the glyph and text
  sit half a margin in rather than a whole one:
  - With whole margins, "-12°C" did not fit by the host test's generous width estimate.
  - A 2.4-header box instead left Ford's 726x480 header too little room for the clock, speed and bearing.

  The unit letter is dropped ("78°") only when the text would not fit.
- **The stand-in builds its blobs at query time.** Scenario files carry `@now-30m`-style
  tokens, because fixed blobs would go stale. The plan (§8) had
  `tools/jni-host-test/LabWeather.java` writing fixed blobs. The host test reads the same
  scenario files.
- **A tap dismisses one alert.** It dismisses the alert shown; the next qualifying one
  follows, which is what its "+1 more" promised. The header's warning mark stays while any
  qualifying alert is in force, dismissed or not.
- **Speech details the plan left open:**
  - A turn prompt arriving while an alert is spoken cuts the alert off, as it cuts off anything. The alert is not retried.
  - **An alert never cuts another off** (changed after the lab, `docs/p10-lab-results.md` §4.1, §6.1). Alerts wait in a line, in the order asked, behind a turn prompt or an alert being said, and go one at a time as each utterance ends. Two that qualify in one read are both said in full. The first build let the second cut the first off, and kept only the newest waiting for a prompt (`superseded`). Alerts still waiting when the projection ends are counted `skipped_session_end`.
  - An alert heeds the call rule (`in_call`), as turn prompts do.
  - Turning the voice toggle off stops an alert being spoken.
  - A notification tapped during an alert is dropped (`alert_playing`), as during a prompt.
- **The weather view's layout** (changed after the lab, `docs/p10-lab-results.md` §6.2, §6.3):
  - The hour row has a fixed share of the view's height, and each hour's rain percentage has a line of its own under its temperature.
  - Under an alert strip the condition text is left out; the glyph says it.
  - The place line leads with its flags: "old · 62 km away · Waco · 4 h ago". The view shrinks it, or breaks it onto two lines, before it cuts anything.
  - With no reading, the header draws only the dimmed "—", with no glyph.
- **What a failed read keeps.** A read that fails as unreadable or with no locations keeps
  the last good reading, so its age still shows. A refusal drops it, because what it said
  can no longer be trusted to be Breezy's. The refusals are: not installed, the wrong owner,
  no permission, or an unsupported major version.
- **The wrong-owner status line** is "Weather provider is not Breezy Weather's". The plan
  gave no wording.
- **Distance.** "62 km away" is measured from where the dashboard's map is following: the
  vehicle's fix, else the phone's. The location is chosen by the same fix at read time.
- **The card's own read.** The card reads once for its status line, outside any projection,
  with its own counts. With Show weather off it reads nothing.

### 3.3 Open question 5 is still open

"Unmuted" is implemented as the trip panel's voice toggle (`voice_guidance`), as the plan
proposed. James has not confirmed it.

## 4. Report keys (counts only)

- **Feed:**
  - `weather_enabled`, `weather_permission`, `weather_provider`
  - `weather_reads`, `weather_read_failures`, `weather_reads_skipped`
  - `weather_notifier_cues`, `weather_timer_cues`, `weather_lab_cues`
  - `weather_reading`, `weather_reading_age_min`, `weather_hours`
  - `weather_alerts_held`, `weather_alerts_qualified`, `weather_alerts_asked`
  - `weather_speak_alerts`, `weather_alert_level`
- **Dashboard:**
  - `weather_shown`, `weather_view_open`, `weather_view_opens`
  - `weather_view_closes_tap`, `_view`, `_timeout`, `_call`
  - `weather_taps_in_call`
  - `weather_alerts_shown`, `weather_alerts_dismissed`
  - `weather_far`
- **Speech:**
  - `weather_alerts_requests`, `_started`, `_waited`
  - the usual end and skip counts, including `skipped_toggle_off`, `skipped_in_call` and `skipped_session_end`.

WeatherTest checks that no place, temperature, condition, headline, description or
coordinate reaches these lines.

## 5. Amendments

"Implement P10" was taken as the go-ahead the plan's §9 waited for, as "Implement P9" was
for APP10. The following were applied on 30 September 2026:

- **spec.md §5.2**: the ninth permission, the receiver and `<queries>`, and the APP09
  integration-list entry.
- **spec.md**, the `verify-apk.sh` sentence in the third-party-code paragraph.
- **plan.md**: a P10 entry.
- **docs/p7-plan.md §5.9**: "Built as P10".

James may want to review the spec wording.

## 6. Before the drive (P10.4)

1. **P10.0 on the Pixel** (§3.1): confirm or correct §1's field names from the captures, including:
   - that `refreshTime` and the dates are milliseconds;
   - that the temperature tag is `f`;
   - that `isDaylight` is left out when true.
2. **The card on the Pixel**: turn Show weather on, grant Breezy's prompt, and read "<place> · updated N min ago".
3. **The notifier**: in Breezy, Settings → Modules, see whether OpenAuto appears under the update notifier, and tick it.
4. **On the truck**: judge the header item, the view at the pane's real size, and the stale label after a long drive.
