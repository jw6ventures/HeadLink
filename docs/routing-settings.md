# Route preferences (Maps & routing tab)

Asked for 2026-09-27: a routing settings page with road-type options, a knob for the
estimated times ("not very accurate right now"), and other navigation settings that make
sense. Built as a **Route preferences** card on the Maps & routing tab, above Voice
guidance. Code: `RouteOptions.kt` (options and `PaceTracker`), `Router.kt`, `Navigation.kt`,
`Guide.kt`, `AppSettings.kt`, `ConnectionActivity.kt`. Host test: `RouteOptionsTest`.

## 1. Why the times were long

BRouter's `car-vario` profile times a route with a kinematic model capped at its `vmax`
parameter, which ships as **90 km/h (56 mph)**. Any road without a lower mapped limit,
which includes every untagged interstate, is timed at 56 mph. Measured on the Texas tiles
(desktop JVM, vendored BRouter 1.7.10, 2026-09-27):

| Trip | 56 mph (old) | 65 mph | 70 mph | 75 mph | 80 mph | Real drive, no traffic (approx.) |
|---|---|---|---|---|---|---|
| Austin → Dallas, 315 km | 3 h 42 | 3 h 11 | 3 h 01 | 2 h 56 | 2 h 56 | ≈ 3 h 05 |
| Austin → Temple, 109 km | 79 min | 69 | 66 | 65 | 65 | ≈ 65 min |
| Austin → Round Rock, 31 km | 25 min | 23 | 22 | 22 | 22 | ≈ 22 min |
| Austin → College Station | 154 min (164 km) | 136 (171 km) | 133 | 131 | 131 | ≈ 110 min |

Above 75 mph nothing moves: mapped speed limits cap the rest. The last column is a
rough real-world number, not a measurement. College Station is still about 20% long at
any top speed, which is what the time adjustment and Learn my pace are for. A faster top
speed also makes highways more attractive to the router: that trip moved from 164 km to
171 km.

## 2. The settings

| Setting | Default | What it does |
|---|---|---|
| Avoid highways | off | `avoid_motorways`: motorways and their ramps made much more costly |
| Avoid toll roads | off | `avoid_toll`: toll-tagged ways and toll booths all but closed |
| Avoid unpaved roads | off | `avoid_unpaved`: unpaved surfaces timed slower still |
| Go around towns | off | `consider_town`: prefers roads outside cities |
| Allow tracks | off | `use_offroad`: opens `highway=track` and `highway=road` |
| Top speed | **75 mph** (121 km/h) | `vmax`; 56 to 85 mph, 56 being the profile's own value |
| Time adjustment | as estimated | −15% to +30% on every time the route carries |
| Learn my pace | **on** | corrects the time still to go from the drive so far |

The five road switches are the profile's own parameters, passed as BRouter key values;
the vendored profile is unchanged. Each was checked on the Texas tiles:

- Avoid toll roads: Pflugerville → Mustang Ridge takes SH 130 by default (east to
  −97.570) and stays west of it (−97.614) with the switch on, 42 → 56 min at 56 mph.
- Avoid highways: Austin → Temple 79 → 117 min, 692 → 1,582 points (off I 35).
- Go around towns: Austin → Temple 109 → 132 km.
- Avoid unpaved and Allow tracks changed neither Austin route, as expected on paved
  city-to-city trips. Their effect is read from the profile, not measured.

**Defaults changed on purpose.** The top speed default is 75 mph, not the profile's 56,
because 56 was the measured cause of the long times. This changes routes as well as times
(College Station above). Learn my pace is on by default. Everything else defaults to what
the app did before. Both are one tap to undo.

### Time adjustment

`Route.withTimeScale` multiplies the route's total and every point's time once, where the
route is made. The cluster, the trip panel, Home and the prompts' fallback pace all read
the same times.

### Learn my pace

`PaceTracker` compares, between consecutive on-route fixes, how far BRouter's estimate of
the time still to go fell with how much time actually passed. It starts counting at the
first fix where the estimate falls, so parking with a route up does not count. After
that, stops at lights and jams count in full. It counts nothing off the route or across a
fix gap over 30 s. The factor is 1 under 2 min of estimated driving, is phased in fully
by 10 min, and is held to 0.67–1.5. A re-route keeps what was learned, and a new
destination resets it. It applies to the time to the destination (cluster, trip panel,
Home), not to the time to the next turn.

### Applying a change

Each change is saved as it is made. A change that can alter the route (any road switch,
the top speed, the time adjustment) recomputes the route being shown from the current
position (`Navigation.optionsChanged`). Learn my pace applies at once. Re-routes always
read the saved options.

## 3. A BRouter trap: its profile cache mixes up switches

BRouter caches the parsed profile process-wide under the file's timestamp plus the *sum*
of the key values' hash codes (`ProfileCache.parseProfile`). A sum cannot tell
`avoid_toll=1 avoid_motorways=0` from `avoid_toll=0 avoid_motorways=1`, so the second
route would silently reuse the first route's switches. `Router` resets the cache
(`ProfileCache.setSize(1)`) whenever the parameters differ from the last route's.
`RouteOptionsTest.switchesReachBRouterEveryTime` routes avoid-tolls, avoid-highways, then
avoid-tolls again, and checks the two differ and the third matches the first.

## 4. A second BRouter trap: long trips fail above 90 km/h

BRouter's last pass re-walks the route it found in detail, and gives up with `error
re-tracking track` when that costs more than 5,000 over the search's own figure
(`RoutingEngine`: `maxTotalCost = guideTrack.cost + 5000`). Above the profile's 90 km/h,
the kinematic model's two figures drift apart over long trips:

| Trip | 90 | 97 | 105 | 113 | 121 | 129 | 137 km/h |
|---|---|---|---|---|---|---|---|
| Austin → Dallas, 314 km | ok | | | | ok | | |
| Dallas → Amarillo, 585 km | ok | ok | ok | ok | ok | ok | ok |
| Austin → Lubbock, 598 km | ok | ok | ok | fail | fail | fail | fail |
| Austin → Amarillo, 779 km | ok | ok | ok | fail | fail | fail | fail |
| Austin → Tulsa, 726 km / OKC, 622 km | ok | | | | ok | | |
| Austin → Denver, 1,459 km | ok | ok | fail | fail | fail | fail | fail |

So `Router.compute` retries **that failure only** one speed lower each time, down to 90,
which never failed. The route records the speed it was timed at (`Route.topSpeedKmh`,
`routing_top_speed_used_kmh`), and the event log says when the speed was lowered. The cost
is extra searches: Austin → Denver went from about 15 s to 63 s on the desktop (121, 113
and 105 failed before 97 finished). Trips under about 500 km were not seen to fail.

## 5. Report

`[routing]` gains one line of the options in force
(`routing_avoid_highways=… routing_top_speed_kmh=… routing_eta_percent=… routing_learn_pace=…`).
`[guidance]` gains `guidance_learn_pace`, `guidance_pace_estimated_s`,
`guidance_pace_actual_s` and `guidance_pace_factor`. These are choices and durations only,
with no places.

## 6. Not done, and why

- **Avoid ferries.** `car-vario` has no ferry switch, and adding one means editing the
  vendored profile.
- **Shortest route.** The kinematic profile has no distance-only mode.
- **Turning automatic re-routing off.** With it off, an off-route vehicle would be shown
  a stale instruction. Left out rather than built half-right.

## 7. Verified so far (2026-09-27)

- `tools/android-build.sh`: debug and release APKs, lint and APK checks pass. Its host
  suite at first stopped at `DashboardTest` ("minus stops at the pinch's furthest"): a
  separate change to `MapControl.kt` had moved `MIN_SCALE` from 4096 to 16 (zoom level
  12 to 4). The test was updated to the new floor on 2026-09-28, and the whole suite
  now passes.
- Every other host test, run on the host JDK 17 against the build's release classes: all
  pass. That includes `RouteOptionsTest` (54 checks, with the Texas tiles: SH 130, 56
  against 75 mph, the cache collision, Austin → Lubbock timed at 105 km/h),
  `AppSettingsTest` (50 stored keys), `RoutingTest` (105, real routes including Austin →
  Denver), `GuidanceTest` (1,543), `StreetNamesTest` (50, real map) and
  `MapReadGuardTest` (25, real map).
- Not run in the emulator lab, not on the Pixel, not on the truck. No real drive has
  checked Learn my pace or the arrival times.
