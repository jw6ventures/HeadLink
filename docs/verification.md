# Verification

[← Back to the README](../README.md)

How HeadLink is tested, what each kind of test can and cannot prove, and where every
feature stands today. The commands live in [development.md](development.md); this page
says what their results mean.

Everything has been tested against one car and one phone: a 2024 Ford F-150 with SYNC 4.0
("the truck") and a Pixel 9 on GrapheneOS. James drives with the app daily, so the basic
paths (projection, map, media, wired and wireless start) get used on every drive. The
status table below records only what was checked and written down, not what has simply
not gone wrong yet.

## What counts as evidence

From strongest to weakest:

1. **Truck**: a saved session report from the truck, or James watching the car's own
   screen or cluster. Only this can pass a gate.
2. **Pixel**: the real phone, with its own codecs, GPU, ART and Android services, but no
   head unit.
3. **Lab**: the app or the Rust engine against the lab receiver, in the emulator or over
   adb to the Pixel.
4. **Host**: Rust tests and JVM tests on the desktop.

Three rules came from mistakes:

- **A schema field name is not evidence.** The vendored protobuf schemas are reverse
  engineered. Their structure has always been right, but their names have been wrong at
  least four times (`AudioFocusResponse` field 2 is `unsolicited`, not `granted`; the
  satellite counts look swapped; two files at the same pin disagree on `width`/`height`).
  Record a value and only act on it once a head unit's behaviour settles it (spec.md §8.1).
- **A simulator written from the same reading as the source cannot check that reading.**
  `hu-sim` and the source agreed on every early mistake. The Prodigy receiver is the
  independent reader, and the truck is the only authority.
- **A lab pass about an absence is only as good as the time it watched.** "The source sent
  nothing" is now timed from the first script tick, not from receiver start, which in the
  emulator is minutes before Android boots.

## Test layers

### Rust tests and `tools/check.sh`

`check.sh` builds the pinned host OpenSSL, then runs `cargo fmt --check`,
`clippy -D warnings`, `cargo test` and a release build with networking off, and checks the
generated protobuf code is current (`tools/proto-gen.sh --check`).

The tests cover framing and fragmentation (`core/aap-wire`), TLS against independently
generated identities (`core/aap-tls`), required-field validation (`core/aap-proto`), the
session from version request through projection and shutdown against `hu-sim`
(`core/aap-session/tests/`), and the JNI start/stop/report contract (`core/aap-jni`).
Failure paths the lab cannot reach live here, for example
`a_panic_while_driving_still_stops_the_io_threads`.

**Proves:** the engine's logic, deterministically. **Cannot prove:** that a head unit reads
any message the way the code does.

### `tools/hu-sim`

A test-only head-unit peer (`tools/hu-sim/src/lib.rs`): its own TLS client on plain
OpenSSL, discovery, ping, shutdown, and every projection service the source uses, with
switchable misbehaviour. It shares only framing and generated message types with the source.
It is also the only peer that sends a mid-stream `UPDATE_UI_CONFIG`
(`ui_config_after_media`); the Prodigy lab receiver never does. Treat anything checked only
against `hu-sim` as unproven on a real head unit.

### JVM host tests (`tools/jni-host-test/`)

`tools/jni-host-test.sh` compiles every `*Test.java` against the app's **release** Kotlin
classes, the vendored OsmAnd-java classes and VTM, and runs them on HotSpot. `android-build.sh`
runs them after every build, and so does CI. They cover the app's pure logic: dashboard
layout and hit-testing at 1080p, 800x480 and Ford's 726x480 content area, settings and
their repair, routing, guidance, voice prompts, traffic, weather, OsmAnd reading and
search, favorites, and the wireless state machines. Many also check that no place, name,
key or digit reaches a report.

`JniContractTest` runs under `-Xcheck:jni` against a host build of `libaap_jni`. Any
`WARNING in native method` or `FATAL ERROR` fails the build.

Some tests take real data when you point them at it, and skip that part otherwise (the
summary line says so): `OBF_DIR=<dir of OsmAnd .obf>`, the only real data since 0.12
(`ROUTING_SEGMENTS` and `MAP_FILE` went with BRouter and Mapsforge). The files are too big for the repository, so CI runs only
the data-free checks. `LabDrive.java` and `LabTraffic.java` are compiled but not run: they
generate lab drive fixtures and HERE stand-in scenarios.

**Cannot prove:** anything about ART, Android APIs (storage access, Telecom, telephony,
TextToSpeech, Bluetooth, HTTP), GL rendering, or timing on the phone.

### `tools/verify-apk.sh`

Run on the debug, unsigned release and debug-signed release APKs by every build. It does
not run the APK. It fails the build if any of these change:

- package name, minSdk 33, targetSdk 36, arm64-v8a only;
- **the exact permission list** (adding one has to be a visible act; spec.md §5.2);
- one launcher activity, `ConnectionActivity`; the wireless probe activity unexported;
- `allowBackup=false` with data-extraction rules; no cleartext; no network security config
  in a release build;
- exactly five services, each with its export flag, guard permission and foreground type
  pinned (`ProjectionService`, `WirelessLinkService`, `WirelessCompanionService`,
  `MediaAccessService`, and `CallCompanionService`, which is disabled in the manifest);
- `<queries>` limited to media browsers, TTS engines and Breezy's one provider authority;
- the accessory filter (`Android` / `Android Auto`);
- exactly `libaap_jni.so` and `libvtm-jni.so`, stored uncompressed, 16 KiB aligned;
  `libaap_jni.so` needs only libc/libdl/libm/liblog, exports only `JNI_OnLoad`, and carries
  OpenSSL 3.5.8;
- no `com/google/android/gms` or `gearhead` reference anywhere in the dex.

`android-build.sh` also checks that the debug-signed release APK is not debuggable.

### CI

`.github/workflows/ci.yml` runs `fetch-deps.sh`, `check.sh` and `android-build.sh --online`
from an empty cache; the signed release workflow is in [release.md](release.md). A cold cache fetches some parent POMs and BOMs that a warm local cache
never does. Those fail Gradle dependency verification only in CI, and
`--write-verification-metadata` does not add them. After changing dependencies, run the
Gradle step against a fresh `OPENAUTO_CACHE` and add any reported artifacts by hand, after
checking their hashes against Maven Central.

### Emulator smoke (`tools/emulator-smoke.sh`)

The debug APK, with an x86_64 `libaap_jni.so` added, in a headless AOSP API 36 emulator
with CheckJNI: launch, identity creation and TLS self-test, Connect and Stop without an
accessory, saved report, relaunch, no crash in logcat. It proves the native library loads on
ART and nothing more. Its UI checks look for `Stage: ARMED` on the screen it opens, and that
line now lives on the hidden Diagnostics tab. Since the phone app was rebuilt it has been
reported failing at "no button Connect". That is a stale check, not a regression.

### The lab receiver (`tools/lab-receiver.sh`)

A head unit built from the pinned OpenAuto Prodigy protocol library
(`tools/lab-receiver/receiver.cpp`) with freshly generated credentials. It decodes the
video with libavcodec and judges the **decoded picture**, so it shares no code with the
source. It can face `source-harness` over TCP, the app in the emulator, or the app on the
Pixel.

| Script | What it checks |
| --- | --- |
| `taps` | taps each demo-scene button; the picture changes to that button's colour |
| `focus` | drops and restores video focus, then a regain and loss back to back; the app must stop drawing while the head unit holds the screen |
| `stall` | withholds acknowledgements; the source must wait and recover |
| `transport` | media buttons; the now-playing channel and the picture both follow |
| `audio` | measures received PCM against the 440/880 Hz tone the source says it sent; `LAB_UNDERFLOW_AFTER=N` injects underflow reports |
| `navigation` | decodes the cluster messages and times them against the advertised floor |
| `none` | projection only |

`LAB_MARGINS=74x0 LAB_TOUCH=1920x882` gives Ford's geometry. `LAB_SENSORS=all` advertises
Ford's nine sensor types and fills seven of them.

**Where it is not a car:**

- **It grants navigation focus by reflex.** It answers a focus request by echoing the
  request's payload, so it grants anything, and a hand-back (`NAV_FOCUS_NATIVE`) comes back
  looking like a refusal. What it *records* (requests sent) is evidence; what it *answers*
  is not.
- **It never sends `InstrumentClusterStart`**, unless the lab adds one. Ford doesn't send
  it either, which is why the app's "send regardless" box is on by default.
  `LAB_NAVIGATION_CLUSTER=none` reproduces Ford; `LAB_NAVIGATION_FORCE=1` ticks the box.
- It hardcodes the navigation channel as 9 (Ford uses 11, `hu-sim` 11), which usefully
  catches a source that hardcodes either.
- Its `upcomingRoad` field actually holds the instruction text, and road names land in
  `actionCues`. Same lesson as the schema names.
- It never sends `UPDATE_UI_CONFIG`, so a layout change mid-session is never exercised.
- Its taps land at button centres, so it cannot see a touch offset smaller than a button
  (Ford's 37 px origin error passed the lab).
- It has no stuck state. The congestion restart has no effect there.
- It has no wireless bootstrap: no Bluetooth, no Wi-Fi join.
- It is a fast, forgiving decoder. It says nothing about latency, smoothness, or whether a
  real head unit accepts the bitstream's profile and level.

### Emulator lab (`tools/emulator-lab.sh`)

The debug APK in the emulator, projecting through `adb reverse` to the lab receiver in the
same Podman pod. The pod has no network, so the APK runs with its real permission set. This
is where the dashboard, map and navigation are checked. It catches the failures that leave
no error: an empty map pane, lost chrome, a frozen map. Four such defects in the first map
build were invisible to every check that does not rasterise a frame. Runs take about six
minutes (emulator boot dominates) and about ten for drives; run them in the background.

Besides the receiver scripts above, the app-side scripts are `track` (map follows a moving
position), `guidance`, `daynight`, `controls`, `endroute`, `calls`, `speech`, `voice`,
`traffic`, `weather` and `congestion`. The `LAB_*` switches set up the conditions:

| Switch | Effect |
| --- | --- |
| `LAB_OBF="<file.obf>[:<file.obf>…]"` | the OsmAnd maps to install, colon-separated; mounted read-only and moved into the debug app's `files/lab-obf/`, which a debug build reads beside the maps folder (a folder grant cannot be scripted). Unset, the region's own file goes in for every run that needs a map, a route or places (Massachusetts, or `Us_texas_austin_northamerica.obf` with `LAB_REGION=austin`); `none` runs map-less. With two or more the report must say `data=obf:N` and no map failed |
| `LAB_ROUTE=1` or `LAB_ROUTE="lat,lon lat,lon"` | asks for a cross-town route through the debug intent once projecting (lab position to Boston Common, or downtown Austin to Guadalupe Street by the university); opt-in |
| `LAB_DESTINATION=1` | deliver destinations the real way: a `geo:` VIEW, then an OsmAnd text share; checks the route line appears in the decoded picture |
| `LAB_DRIVE`, `LAB_GUIDE_DEST`, `LAB_DRIVE_SPEED` | drive a recorded street route that misses a turn (default `tools/lab-receiver/drives/lexington.csv`; `austin.csv` with `LAB_REGION=austin`, both written by `LabDrive.java`, which refuses a destination whose last turn is inside twice the arrival radius) |
| `LAB_REROUTE=fail` | guidance only: after the first route, the debug-only `lab_routes_fail` intent extra makes every search fail with an ordinary routing error for the rest of that lab attempt (`Routing.labFailFor`), driving `lexington-return.csv` (`austin-return.csv` in Austin); the cluster must go REROUTING, then ACTIVE on the old route. Once the attempt is over a lab route must be computed again: the run's `LAB_ROUTE`, else the region's parked position to its guidance destination (the Battle Green; Guadalupe Street by the university with `LAB_REGION=austin`). 25 Nov 2026: Massachusetts `20261125T002312Z` and Austin `20261125T004742Z` passed. Austin's destination was the university, `30.2849,-97.7341`, until that day: its route's last turn is 29 m before its end, inside the follower's 30 m arrival radius, so the vehicle arrived before the destination step could be sent and the receiver failed "the last maneuver before arriving was not DESTINATION" (`20261125T002820Z`). Guadalupe Street's (`30.2875,-97.7417`) is about 650 m before; with it Austin guidance `20261125T004355Z`, voice `20261125T005141Z`, osmand `20261125T005530Z` and `LAB_ROUTE=1` under navigation `20261125T010858Z` passed too |
| `LAB_TRACK=1` | adds the synthetic drive to any script, without checks |
| `LAB_REGION=austin`, `LAB_POI`, `LAB_POI_AHEAD` | Texas data, OsmAnd places on the map, search ahead on a route |
| `osmand` script (`LAB_REGION=austin`) | OsmAnd routing with traffic held: a HERE stand-in jam on I-35, then downtown to Taylor (57 km) shared; passes when the route comes back traffic-priced with no coordinate in the report. After the projection ends it presses Home and shares Guadalupe Street again, so nothing holds the maps open; that route must come back too (0.10.0 failed "no OsmAnd maps open" there on the Pixel; the lab does not reproduce that race). `LAB_OSMAND_EXPECT=fail` for a build from before the 2026-11-13 fix |
| `LAB_FAVORITES`, `LAB_RECENT`, `LAB_REORDER=1` | seed the favorites tile and the recent slot; drag a row on the phone |
| `LAB_NIGHT`, `LAB_MARKER`, `LAB_DIAGNOSTICS` | day/night signal and seeded display settings |
| `LAB_NAVIGATION`, `LAB_NAVIGATION_CLUSTER`, `LAB_NAVIGATION_FORCE` | the cluster channel and Ford's no-announcement behaviour |
| `LAB_AUDIO=1` | adds the USB audio channel to any script |
| `LAB_TRAFFIC=offer\|closure\|off\|drive\|render` | live traffic against the HERE stand-in (`tools/here-standin/`, scenarios written by `LabTraffic.java`). Every run, pass or fail, keeps the whole logcat, `top.txt` and the adb server's transport trace (`adb.*.log`, copied by the one exit trap that also stops the sampler, the stand-in and the emulator). `offer` and `render` also check, apart from its saving, that the first offer came from the way round of a look before that look ended. 24 Nov 2026 with the second traffic review's fixes, all five passed: `offer` `20261124T222653Z` (first offer 19 s after the traffic arrived, 5 min against the host's 5; the a2 update stopped look 1 within 140 ms of being laid on the route, and look 2's way round was taken at 14 min against the host's 13 to 16), `closure` `20261124T221147Z` (one re-route round the closure 30–130 m ahead, not through it), `off` `20261124T221442Z`, `drive` `20261124T221828Z`, `render` `20261124T222503Z`. The render failure just before (`20261124T222243Z`) was the first form of the offer-order check, which wanted the look's end in the report although render saves it while look 1 still searches. The earlier `offer` failure (`20261124T194609Z`) was the emulator's adb link dropping (adbd "host-17: connection terminated: write failed" at the same millisecond as the app's IO_EOF), not the app |
| `LAB_ALTERNATIVES=off` | seeds Show alternatives off, so the way round alone decides the traffic offer; the offer run's checks are unchanged and the report must say the bubbles were off. 25 Nov 2026: `LAB_TRAFFIC=offer LAB_ALTERNATIVES=off` passed, `20261125T003201Z` (first offer 5 min from look 1's way round, kept; a2's 14 min taken from look 2's way round, host 13 to 16 min) |
| `LAB_WEATHER=show\|tap\|alert\|stale\|unsupported\|off\|notifier` | weather against the Breezy stand-in (`tools/breezy-standin/`; never install it on a real phone) |
| `LAB_SPEECH_ENGINE` | eSpeak NG if `$OPENAUTO_CACHE/tts/espeak-ng.apk` is there (never downloaded by the script), else the lab player's silent stand-in, or none; nothing in the lab listens to the audio |
| `LAB_CALL_ROUTE` | `telecom` (a real emulator modem call) or `notification` |
| `LAB_CONGESTION_*`, `LAB_APK`, `LAB_NATIVE_TREE` | a head unit that falls behind; A/B against an older build |
| `LAB_WIRELESS_TCP=10.0.2.2:5288` | runs the session over the wireless transport's TCP path (no Bluetooth or Wi-Fi join) |
| `LAB_VIDEO_CONFIG`, `LAB_MARGINS` | screen size and Ford's content area |
| `LAB_SCREENS=1` | photographs each phone tab; the only lab view of the phone's own screen |

`tools/route-line-pixels.py` counts route-coloured pixels in the decoded frames, which is how
the destination run proves the line was drawn.

Lessons that keep paying:

- **Sometimes run without map data** (`LAB_OBF=none`; until 0.12, without `LAB_MAP`). A dashboard with no map data is what a new user sees,
  and it once crashed on the first night signal.
- **Auto-start in the emulator** (not part of `emulator-lab.sh`): link with
  `cmd companiondevice associate`, wake with `cmd companiondevice simulate-device-event <id> 2`,
  and launch the debug build with `--ez lab_wireless_observe true`. **Use an address with
  letters** (for example `02:AB:CD:EF:12:34`, entered lower case): an all-digit address hid
  a lower-case MAC bug that the truck found.
- **The emulator's CPU timings are not speed evidence.** It always runs the debuggable APK,
  which ART runs largely unoptimised.
- uiautomator sees only views on screen. Poll the saved report for app state instead.
- The emulator APK needs an x86_64 `libvtm-jni.so` injected (the lab script does it), or the
  phone's route preview crashes.
- The receiver's 1500 ms navigation-floor check can fail on emulator jitter behind 1080p
  video. Rerun before chasing it.
- The `taps` script presses demo-scene buttons, but the app now projects the dashboard by
  default, so its button checks fail on any build. That failure is not a regression.
- `tools/emulator-lab.sh`'s container block is one single-quoted string: no apostrophes in
  it, comments included.

### Pixel lab (`tools/pixel-lab.sh`)

The same receiver against the **installed** debug APK on the Pixel, over `adb reverse`,
with Ford's geometry by default. It exercises real codecs, the GPU and real timing, and it
caught a transport duplicate-send bug the emulator never hit. Use it as the last check
before a drive.

- **Always pass `--user 0`.** The Pixel has several Android users. `am start` without it
  targets the foreground user, and `run-as` reads user 0, so lab intents go astray.
- The lab intent is refused unless the session is idle and the identity has loaded. Start
  the activity plainly, wait, then start it again with the extras; `am` must say the intent
  was "delivered to currently running top-most instance".
- Lab-socket attempts never pin a certificate, so they leave the truck's pin alone.
- Install with `adb install -r --user 0` and never uninstall: that drops the imported
  identity and pins.

### On the truck

Each connection attempt saves a report on the phone. Release builds ignore lab intents and
block `run-as`, so reports come off through Diagnostics → Export saved reports.
`android-build.sh` also writes `app-release-debugsigned.apk`: a non-debuggable build that
installs over the debug one and keeps the app's data. Time CPU-bound work only on it: a
94 km route took 23.9 s on the debug APK and 0.8 s on release.

## Feature status

"Truck" means checked and recorded there. "Lab" means the emulator lab unless it says
Pixel lab. Most features also have host tests for their logic.

| Feature | Verified | Note |
| --- | --- | --- |
| Wired USB session, TLS, discovery | Truck | Ford rejects a self-generated identity (auth status −3); an imported one works |
| First-use trust and pinning | Truck | pin survives lab runs |
| Start when plugged in | Truck | wired auto-launch seen on the October wireless builds |
| Video at 800x480 and 1080p | Truck | 1080p needed the correct `UPDATE_UI_CONFIG` reply |
| Touch input | Truck | Ford sends content-origin, frame-sized coordinates; a ~8 px vertical residual is accepted |
| Pinch zoom on the map | Truck | James, by eye |
| Vehicle sensors (position, speed, gear, night) | Truck | bearing scale 1e6 confirmed by eye on a drive; satellite fields look swapped, unconfirmed |
| Owned map following position | Truck | by operator observation on a drive, drawn from Mapsforge; the OsmAnd-drawn map is not yet recorded on the truck |
| Turn-by-turn on the instrument cluster | Truck | no image bytes needed; "send regardless" is required on Ford |
| Offline routing and re-routing | Truck | by operator observation with BRouter; release routing timed on the Pixel. OsmAnd routing, the only router since 0.12, is host- and lab-tested and not yet recorded on the truck |
| Media controls and now-playing on Ford's UI | Truck | wheel buttons work without any audio streaming |
| App-owned audio over USB | Truck | off by default |
| Phone audio over Bluetooth A2DP | Truck | survives USB projection in both connection orders, and wireless; "phone stays silent" is assumed, not measured |
| Wireless: Connect wirelessly on Home | Truck | 58 min and 15 min drives ended cleanly; needs the app excluded from any VPN |
| Wireless: auto-start when the truck connects | Truck | about 9 s from Bluetooth connect to picture; SYNC sometimes ignores or drops the bootstrap |
| Wireless keep-awake (screen off) | Truck | a screen-off drive held 45–53 fps; the keep-awake-off comparison is not done |
| Wireless bootstrap retry | Host | the emulator has no Bluetooth |
| Standby for the truck's Android Auto button | Host | whether SYNC sends the button over a held link is unknown |
| Congestion recovery and stream restart | Lab, truck (partly) | SYNC accepts a phone-sent restart, but the driver sees it as a blink; whether it cures a wired spiral is unknown |
| Map pane resize after a layout change | Rust (`hu-sim`) | the lab receiver never sends the change; check `resizes>=1` in a truck report |
| Toll roads drawn | Host | theme rule checked against VTM's own rule builder; matches `yes\|true` since 0.12 dropped Mapsforge's `tl_yes` |
| Several maps drawn as one | Lab | two OsmAnd files, `data=obf:2` with no map failing (20261124T191525Z); before 0.12, Mapsforge files |
| Coverage refusal for a destination outside the maps | Host | turn card, trip panel and Home text not yet seen in the lab; the card in place of the old route's turns off a held route only in `GuidanceTest`; a held trip asked for again when maps change in the same folder, or changed while its request ran, has no test (Android-bound) |
| `NOT_CONNECTED`, track-only ends and stops, Allow tracks, Go around towns | Host | `OsmAndRoutingTest` on the Austin, Waco and Dallas maps, and the synthetic file for a stop only a track reaches and a place with no road but a track near it; the track-only turn card and the Home texts (`start_track_only`, `stop_track_only`, `route_not_connected` too) not yet seen in the lab |
| Maps, guidance and routing in the emulator lab on OsmAnd maps | Lab | 2026-11-24: track 20261124T183639Z, guidance 20261124T182715Z, guidance with `LAB_REROUTE=fail` 20261124T191016Z, navigation 20261124T185705Z, endroute 20261124T180643Z, daynight 20261124T183548Z (`map_theme_hold_timeouts=0`, longest hold 225 ms), `LAB_DESTINATION` 20261124T182501Z, osmand 20261124T185751Z, Austin places 20261124T180930Z and search ahead 20261124T181116Z |
| Upgrade cleanup of 0.11 data (`LegacyData`) | — | not yet run on a phone that had imported files or a drop folder |
| Day/night themes and toggle | Lab | the truck's display is pinned dark, so Automatic has never flipped on it |
| Header: compass, signal, battery | Lab | |
| 12/24-hour clock | Host | not rendered anywhere |
| Zoom buttons, marker colour | Lab | |
| Turn card, trip panel, End route | Lab | how SYNC reacts to getting its cluster back mid-session is unseen |
| Street names on maneuvers | Lab | which cluster line Ford shows them on is unknown |
| Failed re-route recovery | Lab | `LAB_REROUTE=fail` |
| Re-route avoids an instant U-turn | Host | OsmAnd's car profile charges 300 s for setting off backwards; until 0.12 BRouter's 180 s no-go circle, a guess to tune |
| Route preferences | Host | 75 mph default and Learn my pace not signed off |
| Destinations shared from OsmAnd | Lab | a real OsmAnd share on the Pixel has not been read |
| Long press on the map to route | Host | the receiver cannot hold a press |
| Address search, places, search ahead | Lab | release timings on the Pixel not measured |
| Favorites tile, recent destination | Lab (rendered) | a row has never been tapped outside host tests; rows are ~52 px at 800x480 |
| Route preview and alternatives (phone) | Emulator | |
| Live alternatives on the dashboard | Lab | |
| Saved route across a restart | Emulator | |
| Players with Resume (idle media pane) | Lab | |
| Dashboard notifications and pause | Lab | |
| Call card, mute, keypad | Lab | both call routes |
| Read-aloud and spoken turn prompts | Lab, truck (by ear) | heard on the truck: turn prompts fine, read notifications too quiet until both moved to the navigation-guidance stream (`Speaker.attributes`); no recorded drive check |
| Live traffic (HERE) | Lab, Pixel (partly) | Test button works with a real key; no drive |
| Weather (Breezy) | Lab | stand-in only; the Pixel capture of real Breezy data (debug extra `lab_weather_capture`) has never been done, so field names are unconfirmed |
| Phone app tabs, diagnostics export | Pixel | in daily use |

## Gates

Gate names appear only on this page. G0–G10 are defined in spec.md §11.1. The W gates are
the wireless track. All ran on the Ford SYNC 4.0 and Pixel 9.

| Gate | Status | What it proved | Evidence |
| --- | --- | --- | --- |
| G0 build | Passed | sideloaded APK launches on GrapheneOS, native engine loads, no Google dependency | saved reports, 17 Sep 2026 |
| G1 transport | Passed | an ordinary app opens the factory USB accessory and answers the version request | saved reports, 17 Sep 2026 |
| G2 security | Passed | TLS completes and auth succeeds, with an imported identity | report `3812ebff` |
| G3 encrypted services | Passed | encrypted discovery with usable video and input; 30 s hold | report `c2407cff` |
| G4 display | Passed | the scene on the factory display for 60 s, later at 1080p | `da9398a5`, `709ffa9c` |
| G5 interaction | **Open** | touch has worked since the alignment fix; the recorded 5-minute run and five reconnections have not been done | — |
| G6 media | Passed | wheel buttons control the phone's player; track and app on Ford's UI | `7f869585` |
| G7 audio | Passed | app-owned audio audible over USB, focus taken and released, no underflows | `7f869585` |
| G8 map | Passed | owned map follows the vehicle on the factory display | operator, drive of 21 Sep 2026; report not filed |
| G9 navigation channel | Passed | maneuver icon, road name and distance on Ford's cluster | `1f4db62b`, `ad772b8a` |
| G10 navigation | Passed | offline route followed on screen and cluster; deliberate deviation re-routed | operator, drive of 21 Sep 2026; report not filed |
| W0 advertisement | Passed | SYNC advertises the wireless bootstrap service to a phone without Google's app | `a783868a` |
| W1 bootstrap | Passed | with a reply shaped like Android Auto's, SYNC hands over its Wi-Fi details (a normal AP, WPA2; SSID and BSSID hashes matched across drives) | `2d89e925` |
| W2 wireless projection | Passed | local-only Wi-Fi join, TCP and a full session; A2DP keeps playing | `2ea3281e`, `b5cbfdd2` |
| W3 auto-start | Passed | projection starts when the truck connects over Bluetooth, no tap | `e23690b9`–`fcb11a3a`, `a2c0e6da` |

**G5, to close it:** project the demo scene (Diagnostics → Projected scene). Tap all four
buttons and check that the counts rise on the car's screen. Keep projecting for five
minutes, with the app in the background for a minute and the phone screen off for a minute.
Then do five unplug and replug cycles without restarting the app, exporting a report each
time. Each report must have a new `generation`, `session_uuid` and `scene_token`,
`io_descriptor=closed`, and no `NATIVE_START_FAILED (BUSY or STALE_IO)`. Hour-long drives
and daily reconnects suggest it would pass, but nothing has recorded it.

## Open items

Built but not proven on the truck:

- Wireless bootstrap retry, and standby for the truck's Android Auto button. Standby's open
  question is whether SYNC sends the button press over a link the phone already holds, and
  with reason 0.
- Keep-awake: the run with it turned off has not been done, so causation is unproven. The
  "silent after version reply" starts may be SYNC waiting for its button; that is a theory.
- The congestion restart on a wired drive.
- The map pane's resize after Ford's mid-session layout change.
- Toll roads on the map.
- The re-route U-turn penalty.
- Route preferences.
- Day/night Automatic: needs a drive with the truck's display not pinned dark.
- Favorites tap-to-route, the recent slot, End route handing back the cluster.
- Calls and dashboard notifications. Speech has been heard on the truck, but there is no
  recorded check of prompt timing or wording on a drive.
- Live traffic on a drive, weather from the real Breezy, OsmAnd search and search ahead.
- The HeadLink package name. A new application id is a fresh install, and SYNC may treat
  the new device name as a new phone.

Known unknowns:

- Whether Ford's cluster icon matches the maneuver sent, whether it reads tenths of a mile,
  what it shows at a route's end, and which line carries the street name.
- Whether Ford sends a second night signal mid-session.
- Whether Ford keeps sending fixes while the vehicle is stopped. The route is held as
  "followed" while projecting for that reason.
- The satellite count field names (they look swapped).
- Release-build timings on the Pixel for alternatives with traffic no-go points, address
  search and search ahead.
- Whether the phone itself stays silent while A2DP plays in the truck.
- No minified APK has ever run. Minification is off; `app/proguard-rules.pro` is checked
  for name survival only.
- The source identity the truck accepts has a fixed expiry (December 2026). Truck testing
  after that needs a new one.
