# HeadLink

HeadLink (formerly OpenAutoAndroid) is published by [JW6 Ventures](https://jw6ventures.com) at
<https://github.com/jw6ventures/headlink>.

Ordinary-app Android Auto source feasibility project, governed by [spec.md](spec.md)
and tracked in [plan.md](plan.md). **G0–G3 passed on the Pixel 9 with a 2024 Ford
SYNC 4.0 head unit** (report c2407cff: usable 800x480 video and input advertised, a 30 s
hold, encrypted keepalive), using an imported source identity after the generated one was
rejected with AUTH_COMPLETE -3; see the
[authentication investigation](docs/ford-auth-investigation.md). Since then **G4 and G6–G10 have
passed on the same head unit**: 1080p projection, media controls, app-owned audio, the
owned map, turn-by-turn on the instrument cluster, and offline navigation with a re-route
(spec §11.1 has the reports). **G5 is open**: the five-minute run and five reconnections
have not been done. See [P4 lab results](docs/p4-lab-results.md) and the
[G4/G5 procedure](docs/g4-g5-procedure.md).

## Getting started

This section is for using the app. Everything after it is the project's build, lab and
test record.

1. **Check the phone.** Android 13 or later, with USB accessory support. Pair it with the
   car over Bluetooth as usual: phone audio stays on Bluetooth.
2. **Install.** Download `HeadLink-<version>.apk` from
   [Releases](https://github.com/jw6ventures/headlink/releases), check it against
   `SHA256SUMS`, and install it.
3. **Connect the first time.** Open HeadLink and tap **Trust and connect** on Home, then
   plug the phone into the car's USB port. The car's certificate is pinned, so later
   drives start on their own when you plug in. Once that has worked, **Connect
   wirelessly** and Settings' wireless link become available.
4. **If the car refuses the phone's identity.** Some head units, the 2024 Ford SYNC 4.0
   among them, reject the identity the app generates. Home then offers **Import
   identity**, which is also on the **Settings** tab under **Source identity**. You need a source identity of your own for that; this project does not
   provide one and cannot help you obtain one. See [Source identity](#source-identity-pem).
5. **Add maps and routing** for the dashboard map and navigation. Projection works
   without them. The **Maps** tab has links to where the files are published, and
   [Data to download and import](#data-to-download-and-import) explains each one.

No certification or vehicle compatibility is claimed. Do not operate the phone while
driving.

## Project status

**Phone audio goes over Bluetooth A2DP**, not over USB. The USB audio channel (P5.3) is for
audio this app generates, and it is off by default. See spec §2.2 and the
[A2DP proposal](docs/a2dp-supersedes-usb-audio-proposal.md).

Current state:

- `core/aap-wire`: bounded framing, fragmentation, and per-channel assembly.
- `core/aap-tls`: TLS 1.2 server over memory buffers with the pinned, statically linked
  OpenSSL 3.5.8 LTS; generated RSA-2048 source identity; pinned or armed first-use
  head-unit certificate policy; ClientHello and alert diagnostics.
- `core/aap-proto`: prost types generated from a vendored open-android-auto schema
  subset, with wire-level required-field validation.
- `core/aap-session`: shared sans-IO source session — version (`legacy-1.5`), TLS,
  AUTH_COMPLETE, one encrypted service discovery request, validated capabilities, a
  bounded hold with keepalive, and shutdown, stoppable after `version`, `auth`,
  `discovery`, or `projection`; a poll loop for pollable descriptors; and `accessory`,
  blocking I/O threads with signal-based cancellation for the Android accessory descriptor
  (clean teardown observed on the actual accessory).
- `core/aap-session` projection (`stop_after=projection`): channel opens on the discovered
  video and input channels, AV setup with configuration reselection, one video focus
  request, the start indication, media with the head unit's acknowledgement window and
  IDR-based congestion recovery, input mapped into video frame pixels, an opt-in sensor
  subscription (P5.2: the restriction pair, every advertised type, or a named list, with
  per-type harvest and position redacted unless asked for), and a graceful shutdown.
- `core/aap-jni`: `JNI_OnLoad`-registered owned-descriptor start/stop/report contract
  with generation-tagged callbacks, identity preparation with a TLS self-test, and the
  in-memory probe.
- `app/`: USB accessory enumeration and permission, connectedDevice foreground service
  with a Stop notification, the session over the accessory, certificate-trial controls
  (armed first use, pins, identity replacement), third-party notices, and a local
  diagnostic report that is saved per attempt (crash-safe) and exportable. Projection adds
  a `MediaCodec` H.264 encoder, an EGL/GLES scene renderer, and the owned test scene
  (session token, counters, four buttons, touch crosshair). Permissions: foreground
  service, connectedDevice, notifications, location, phone state and ongoing calls,
  Bluetooth connect, companion-device presence, wake lock, network state, Breezy
  Weather's provider, and INTERNET. INTERNET is for opt-in live traffic and the opt-in
  Home Assistant button only: with both off, nothing connects, and maps and routing data
  are never downloaded (see
  [Data to download and import](#data-to-download-and-import)).
- `tools/source-harness`: offline plaintext inspector plus a TCP lab adapter
  (`--io poll|threads`) using the same engine, including a projection mode that renders the
  same test scene with the `tools/test-pattern` encoder. TCP is a lab transport, not
  wireless Android Auto.
- `tools/lab-receiver.sh`: controlled receiver built from the pinned OpenAuto Prodigy
  protocol library with generated credentials; version, TLS, authentication, discovery,
  keepalive, and shutdown verified against it, and, with `LAB_STOP_AFTER=projection`, its
  head-unit video, input, and sensor handlers with FFmpeg decoding and scripted taps.
  `LAB_SENSORS=all` advertises the nine types Ford does and populates seven of them, so a
  per-type harvest can be checked against the receiver's own record.
- `tools/hu-sim`: test-only head-unit simulator for deterministic tests.
- `tools/test-pattern`: lab-only I_PCM/P_Skip H.264 encoder and YUV canvas.

See [P2/P3 lab results](docs/p2-p3-lab-results.md), [P4 lab results](docs/p4-lab-results.md),
[P5.2 lab results](docs/p5-2-lab-results.md),
[reference verification and decisions](docs/references.md), the [G0–G3 setup record and
device procedure](docs/g1-setup-record.md), and the
[G4/G5 projection procedure](docs/g4-g5-procedure.md).

## Build and test

Use Podman (not Docker). Caches live under `~/.cache/openauto-android` (override with
`OPENAUTO_CACHE`), outside the workspace. The only networked steps are image builds,
`tools/fetch-deps.sh`, and the first `tools/android-build.sh --online`:

```sh
podman build -f tools/Containerfile.rust -t localhost/openauto-rust:1.85.1 .
podman build -f tools/Containerfile.android -t localhost/openauto-android:p0.2 .
./tools/fetch-deps.sh               # crates (Cargo.lock checksums), OpenSSL and protoc
                                    # archives (SHA-256)
./tools/android-build.sh --online   # once: fills the Gradle cache (verified)
```

Then, with networking disabled:

```sh
./tools/check.sh          # host OpenSSL build, rustfmt, Clippy -D warnings, tests,
                          # release build, generated protobuf code check
./tools/android-build.sh  # host and arm64 OpenSSL, Android Clippy, .so, debug/release
                          # APKs, lint, APK checks, and the JVM host tests (-Xcheck:jni)
./tools/proto-gen.sh      # only after changing the vendored schemas or generator
```

OpenSSL is built from the pinned tarball into `target/openssl/<target>` and linked
statically (`tools/openssl-build.sh`, `tools/openssl-env.sh`); no system OpenSSL is
used. Building the Android image accepts the Android SDK License. Pinned versions and
checksums are listed in [licenses](docs/licenses.md). APKs are written to
`app/build/outputs/apk/`. Signed releases are built by CI from a version tag; see
[releases](docs/release.md). The debug APK is signed with a local debug key kept in the
cache directory; it is not a project identity. To prove a clean build, delete (or move
aside) `target/` and `app/build/` first.

`rust-toolchain.toml` selects Rust 1.85.1 for native rustup use with the same
`cargo fmt`/`clippy`/`test`/`build --offline --locked` commands, after
`tools/openssl-build.sh x86_64-unknown-linux-gnu` in a compatible environment and
sourcing `tools/openssl-env.sh`.

## Install and test on a phone (G0–G3, manual)

```sh
adb install app/build/outputs/apk/debug/app-debug.apk
```

Launch **HeadLink**. Choose **Stop after** `projection` for the video and input
demonstration (G4/G5; see [its procedure](docs/g4-g5-procedure.md)) or `discovery` for the
handshake POC. For G0, the report's `[native_probe]` section must show
`self_test=pass`, `abi=aarch64-android`, and `tls_library=OpenSSL 3.5.8 …`, and its
`[trust]` section `tls_self_test=pass`; that establishes packaging, native loading, and
local TLS only. For the vehicle, follow [the setup record](docs/g1-setup-record.md):
choose **Stop after** (`version` for G1 alone, `discovery` for G1–G3), arm **Trust the
next wired head unit** for the first attempt with a new head unit, tap Connect before
plugging in, then export the report. Attempts are also saved automatically (including a
stack trace on a JVM crash); relaunch and use **Export saved reports** if the app died.

## Data to download and import

The APK contains no map or routing data. The app never downloads any either: you get the
files yourself and give them to the app on the phone. Projection and the test scene work
without them, but the dashboard's map pane, routes and favorites do not. The **Setup** tab
lists anything missing.

| What | File | Needed for | Required? |
| --- | --- | --- | --- |
| [Map](#map-mapsforge-map) | Mapsforge `.map` | The map pane | For the dashboard map |
| [Routing data](#routing-data-brouter-rd5) | BRouter `.rd5` tiles | Routes, favorites, guidance on the cluster | For navigation |
| [OsmAnd maps](#osmand-maps-obf) | OsmAnd `.obf`, read where they are | Address search, map places, search ahead | Optional |
| [Breezy Weather](#weather-breezy-weather) | An app | Weather in the dashboard header | Optional |
| [HERE API key](#live-traffic-here-api-key) | A key | Live traffic | Optional |
| [Source identity](#source-identity-pem) | A PEM file | Head units that reject the generated identity | Only if needed |

### Getting files onto the phone

You can install a `.map` or `.rd5` file in either of two ways:

- **Import map** / **Import routing data** on the **Maps** tab opens the system file
  picker. Pick one file. It is copied into the app's private storage and checked there.
- **Choose drop folder** (also on the **Maps** tab) grants the app one folder, for
  example `Download/HeadLink`. Each time HeadLink's screen comes to the front, it checks any `.map` or `.rd5` file directly in
  that folder, moves it into its own storage, and deletes it from the folder, so the
  phone never keeps two copies. A file changed in the last 30 seconds is left alone until
  the next check, which covers a download that is still running. A file that fails its
  check stays in the folder. **Check folder now** runs a check straight away. This is
  the easier route for a large file copied over USB or synced from a computer.

A file replaces an installed one with the same name. Different names are kept side by
side. The app draws every installed map together and routes across every installed
tile, so a trip that crosses regions needs no switching. **Remove a map or tile**
lists the installed files and deletes the one you pick.

### Map: Mapsforge `.map`

A Mapsforge binary map: `mapsforge binary OSM`, rendered by VTM 0.25. Up to 4 GiB per
file.

- **Download:** ready-made maps by country and US state are at
  <https://download.mapsforge.org/maps/v5/>, for example
  `north-america/us/texas.map`.
- **Or build one** from an OpenStreetMap `.osm.pbf` extract (for example from
  <https://download.geofabrik.de/>) with Osmosis and the `mapsforge-map-writer` plugin.

The import reads the header and refuses a file that is not a Mapsforge map, or one
shorter than its header says it should be (a download that was cut off). The data is
© OpenStreetMap contributors under ODbL 1.0. The dashboard shows that credit on screen.

### Routing data: BRouter `.rd5`

Each BRouter segment file covers one 5°×5° square. Download them from
<https://brouter.de/brouter/segments4/>. Each tile is named after its south-west corner:
`W100_N30.rd5` covers longitude 100°W–95°W and latitude 30°N–35°N, which includes
Austin and Dallas–Fort Worth. Download every tile a trip passes through. All of Texas
is eleven tiles, about 307 MB: `W110_N25`, `W110_N30`, `W105_N25`, `W105_N30`,
`W105_N35`, `W100_N25`, `W100_N30`, `W100_N35`, `W95_N25`, `W95_N30` and `W95_N35`.
The app accepts up to 512 MiB per tile.

- **Keep the file name.** The name is the only thing that tells BRouter which square a
  tile covers. The app finds the tile name anywhere in the file name, so
  `W100_N30 (1).rd5` still works. It refuses a file whose name has no tile name in it.
- The car profile (`car-vario.brf` and `lookups.dat`) comes with the APK. An import
  checks the tile against that `lookups.dat`. BRouter regenerates its segments every few
  weeks, and the app refuses a tile built for a different lookup version. This build
  vendors lookup version 11
  ([lookups.dat](vendor/brouter/src/main/resources/btools/profiles/lookups.dat)). If
  segments4 moves to a newer version, the vendored BRouter has to be re-synced first
  (see [vendor/brouter](vendor/brouter/README.md)).
- If no installed tile covers the phone's last known position, the setup card says
  "No routing data for where you are".

### OsmAnd maps: `.obf`

Address search, map places (POIs) and search ahead read OsmAnd's own map files where
OsmAnd keeps them. Nothing is copied.

1. Install OsmAnd (`net.osmand.plus` or `net.osmand`) and use it to download the regions
   you need, for example `Us_texas_austin_northamerica.obf`.
2. In OsmAnd, go to **Settings → OsmAnd settings → Data storage folder → Manually
   specified** and choose a shared folder, for example `Documents/osmand`. OsmAnd's
   default folder is private to OsmAnd, so other apps can't open it.
3. In HeadLink, go to the **Settings** tab, find **OsmAnd maps**, tap **Choose folder**,
   and pick that folder.
   The grant is read-only.

The app reads only `.obf` files directly inside that folder. It skips contour lines
(`.srtm.obf`, `.srtmf.obf`), Wikipedia (`.wiki.obf`), the world basemap, and downloads
that are still in progress. To update a map, update it in OsmAnd. The drop folder is a
separate grant: pick a different folder for it.

### Weather: Breezy Weather

Weather is off by default. It comes from Breezy Weather's data on the same phone. HeadLink
itself makes no network connection for it.

1. Install Breezy Weather (`org.breezyweather`), add your location, and let it refresh
   once.
2. In HeadLink's **Settings** tab, turn on **Show weather**. Breezy then asks you to grant its provider
   permission. If you skip that, use **Grant**.

HeadLink can't make Breezy refresh. The weather shown is as recent as Breezy's last
refresh.

### Live traffic: HERE API key

Live traffic is off by default. It and the Home Assistant button below are the only
features that use the network.

1. Create a HERE platform account at <https://platform.here.com/> and make an API key.
   The free Base Plan includes 5,000 transactions a month. HeadLink stays well under that.
2. In the **Live traffic** card on HeadLink's **Maps** tab, paste the key into **HERE API key**, read what traffic
   sends and HERE's terms, then turn traffic on.

The key belongs to you, and you are responsible for how it's used. It is stored in the
app's private preferences and is left out of backups and diagnostic reports. It is never
in the APK or this repository. Requests go to `data.traffic.hereapi.com`, and only while
a route is being followed.

### Home Assistant button

An optional button on the car's dashboard that calls one service on your own Home
Assistant server, such as `cover.open_cover` on `cover.garage_door`,
`switch.turn_on` on `switch.gate_opener`, or `automation.trigger` on
`automation.im_home`. It is off by default.

1. In Home Assistant, open your profile, then **Security**, and create a long-lived
   access token. A token for a separate, non-admin user is safer.
2. In the **Home Assistant** card on HeadLink's **Settings** tab, enter the server's
   https address (the one that works away from home), the token, the service as
   `domain.service`, and the target entity (or several, separated by commas, or none).
   Tap **Save**, then **Check**. Check only reads from the server: it confirms the token,
   that the service exists and that each target is a known entity, without calling
   anything.
3. Turn on **Show the Home Assistant button**.

The button sits in the dashboard's header, just left of the weather, where the header has
room for it (1080p and 800x480 screens). On narrower headers, such as Ford's 726x480, it
sits in the map pane's bottom row instead, right of the recentre button. One tap
calls the service once; the button turns green with a tick or red with a cross to say
how it went. The token is stored encrypted with a key held in the Android Keystore, is
left out of backups and diagnostic reports, and is sent only to the address you saved.
Plain http is not supported: Android blocks it in release builds.

### Source identity: PEM

The app generates its own source identity. Some head units accept it and some reject it: the 2024 Ford SYNC 4.0 answered with `AUTH_COMPLETE -3` (see the
[authentication investigation](docs/ford-auth-investigation.md)). For a head unit like
that, use **Import source identity** in the **Source identity** card on the **Settings** tab. Pick one PEM file that contains the source
certificate, then any intermediate certificates in issuer order, then the matching
unencrypted RSA-2048 private key. A valid import replaces the current identity and keeps
the head-unit pins. Use only an identity you are authorized to use. This repository
does not provide one, and importing one does not guarantee that a vehicle will accept
it.

## Optional emulator smoke test

```sh
./tools/emulator-image.sh    # networked, once: emulator and AOSP API 36 x86_64 image
./tools/emulator-smoke.sh    # offline; needs /dev/kvm and a debug APK
```

Runs a copy of the debug APK with an added x86_64 library in a headless emulator with
CheckJNI: launch, identity and TLS self-test, Connect/Stop without an accessory, saved
report, relaunch, and a crash scan. Results go to `~/.cache/openauto-android/emulator-smoke/`.
It is not device evidence and cannot pass G0.

## Lab TCP session

After `./tools/check.sh`, on a compatible Linux host:

```sh
target/release/source-harness listen 127.0.0.1:5277   # receiver connects to us
target/release/source-harness connect HOST:PORT       # we connect to a receiver
target/release/source-harness connect HOST:PORT --stop-after discovery \
    --identity-dir DIR --peer-pin RECEIVER_SHA256 --hold-ms 10000
```

Add `--io threads` to use the accessory transport. The default is version-only: the
session answers a 1.0–1.5 version request with 1.5 MATCH and stops with `STAGE_LIMIT`
when the peer sends its first TLS handshake message. Beyond version, `--identity-dir`
(created if absent) and exactly one of `--peer-pin` or `--trust-first-use` are
required. Exit status 0 means the configured stop was reached cleanly (for `discovery`:
`HOLD_COMPLETE` with usable capabilities). A receiver should request 1.1: Prodigy requests
1.7 by default, which the default `legacy-1.5` profile reports as `VERSION_UNSUPPORTED`
without responding; the opt-in `--profile legacy-1.5-any-minor` answers it with 1.5, and
the investigative `--profile match-1.6` answers a 1.6-or-later request at 1.6 instead
(no WireConfig extension; see docs/references.md).

## Controlled lab receiver

```sh
podman build -f tools/lab-receiver/Containerfile -t localhost/openauto-lab-receiver:p4 tools/lab-receiver
./tools/lab-receiver.sh fetch              # networked: pinned Prodigy and protos into the cache
./tools/lab-receiver.sh build              # offline
./tools/lab-receiver.sh demo --io threads  # offline: full discovery run, receiver pinned
LAB_STOP_AFTER=auth LAB_PEER=first-use ./tools/lab-receiver.sh demo
LAB_PEER=mismatch ./tools/lab-receiver.sh demo      # negative: wrong pin
LAB_REQUEST_VERSION=1.7 ./tools/lab-receiver.sh demo
```

The demo prints the receiver's states, log lines, and JSONL trace, then the harness
report. It needs `target/release/source-harness` from `tools/check.sh`.

With `LAB_STOP_AFTER=projection` the receiver registers OpenAuto Prodigy's head-unit
video, input, sensor, now-playing, audio and navigation handlers, decodes the H.264 it
receives with libavcodec, and runs `LAB_SCRIPT`: `taps` (tap each scene button and check
the decoded picture changes to that button's color), `focus` (drop and restore video
focus), `stall` (withhold acknowledgements), `transport` (press media buttons and check
the now-playing channel and the picture both follow), `audio` (measure the received PCM
against the tone the source says it is sending), `navigation` (decode the scripted
maneuver sequence on the head unit's own instrument cluster and time it against the
advertised floor), or `none`:

```sh
LAB_STOP_AFTER=projection ./tools/lab-receiver.sh demo --fps 10
LAB_STOP_AFTER=projection LAB_SCRIPT=focus ./tools/lab-receiver.sh demo --fps 10
LAB_STOP_AFTER=projection LAB_SCRIPT=stall ./tools/lab-receiver.sh demo --fps 10
# A Ford-like geometry: margins, insets, and a larger touch area
LAB_STOP_AFTER=projection LAB_MARGINS=74x0 LAB_TOUCH=1920x882 LAB_SENSORS=restriction \
    ./tools/lab-receiver.sh demo --fps 10
# P5.2: subscribe to every advertised sensor and harvest each type. The receiver
# advertises the nine Ford advertises and answers seven, so sensor_populated and
# sensor_silent can be checked against its own receiver_sensors_populated line.
# Add LAB_SENSOR_LOCATION=1 to put latitude and longitude in the report; without it
# they are redacted and only the fact of a fix is recorded (spec §10).
LAB_STOP_AFTER=projection LAB_SENSORS=all ./tools/lab-receiver.sh demo --fps 10
# P5.3: open one PCM MEDIA audio channel, take audio focus on the control channel, and
# stream the lab tone (440 Hz left, 880 Hz right). The receiver measures both channels,
# so a swapped pair or a wrong sample rate fails rather than passing as noise, and writes
# the PCM to $OPENAUTO_CACHE/receiver/audio.pcm. LAB_UNDERFLOW_AFTER=N injects 0x800B,
# which no open-source head unit sends on its own.
LAB_STOP_AFTER=projection LAB_SCRIPT=audio LAB_UNDERFLOW_AFTER=3 \
    ./tools/lab-receiver.sh demo --fps 10
# Audio alongside any other script: video keyframes fragment and audio does not, so this
# is also the interleaving regression a second AV channel makes possible.
LAB_STOP_AFTER=projection LAB_AUDIO=1 ./tools/lab-receiver.sh demo --fps 10
# P6.2: open the instrument cluster channel, take navigation focus on the control channel,
# and send a scripted maneuver sequence. The receiver decodes it with Prodigy's own
# handler — a second reader that shares no code with the source — and times the updates on
# its own clock against the minimum_interval_ms it advertised.
LAB_STOP_AFTER=projection LAB_SCRIPT=navigation ./tools/lab-receiver.sh demo --fps 10
# Prodigy never sends InstrumentClusterStart on its own, so this is the head unit that
# advertises the service with no cluster behind it: the source must send nothing at all.
# LAB_NAVIGATION_FORCE=1 is the other experiment, where it sends regardless and says so.
LAB_STOP_AFTER=projection LAB_SCRIPT=navigation LAB_NAVIGATION_CLUSTER=none \
    ./tools/lab-receiver.sh demo --fps 10
```

A nonzero exit means a decode, input, or audio check failed.

## Optional lab projection of the app itself

```sh
./tools/emulator-lab.sh taps    # also: focus, stall, audio, track, navigation, none
```

Runs the debug APK in the headless emulator and the lab receiver in one Podman pod whose
only interface is loopback; the app reaches the receiver through an abstract local socket
forwarded by adbd (`adb reverse`), so the APK keeps its permission set and has no network
transport. The receiver decodes the app's own MediaCodec output and taps its scene. Results,
including pictures decoded from the stream, go to
`~/.cache/openauto-android/emulator-lab/`. It is not device evidence and cannot pass G4.

## Optional lab projection on the phone

```sh
./tools/pixel-lab.sh              # the installed debug APK, over adb
```

The same run against the *installed* APK on a phone attached to adb, with the Ford SYNC 4.0
geometry as the default (`LAB_MARGINS=74x0 LAB_TOUCH=1920x882 LAB_TOUCH_SPACE=content`), so
the vehicle's touch arithmetic is reproduced: a tap aimed at frame pixel 577,379 goes out as
540,379 and has to map back. Real hardware and real codecs — this is where the transport
duplicate-send defect (0.3.4) showed up and the emulator never did. It pulls the app's own
report off the phone afterwards. Results go to `~/.cache/openauto-android/pixel-lab/`. Still
not vehicle evidence: no head unit is involved, so it cannot pass G4 or G5. Use it as the
last check before a drive.

## Inspect a fixture

```sh
python3 -c 'from pathlib import Path; import sys; sys.stdout.buffer.write(bytes.fromhex(Path("protocol/fixtures/version-request.hex").read_text()))' | target/release/source-harness
```

Expected output: `channel=0 flags=0x00 id=0x0001 plaintext_bytes=6`. Input is a finite
plaintext stream; output is metadata only, and truncation or ciphertext fails.

See [reference verification](docs/references.md), [license inventory](docs/licenses.md),
[P0.1 results](docs/p0-1-results.md), [P0.2 results](docs/p0-2-results.md), and
[P1.1 results](docs/p1-1-results.md).
Project implementation is GPL-3.0-only; see [LICENSE](LICENSE). No certification or
universal vehicle compatibility is claimed.
