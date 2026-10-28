# Developing HeadLink

[← Back to the README](../README.md)

HeadLink is a Rust protocol engine (`core/`) inside a Kotlin Android app (`app/`), with
host tools and a lab receiver for testing without a car. The project is governed by
[spec.md](../spec.md) and tracked in [plan.md](../plan.md); the topic pages are listed in
the [docs index](README.md).

Which gates have passed, and what is proven on the truck versus only in the lab, is in
[verification](verification.md).

## Repository layout

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
  Weather's provider, and INTERNET. INTERNET is for opt-in live traffic and the opt-in Home
  Assistant button only: with both off, nothing connects, and maps and routing data are never downloaded (see
  [setting up maps and data](data.md)).
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

How these pieces fit together is in [architecture](architecture.md), and what each test
layer can prove is in [verification](verification.md).

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
checksums are listed in [licenses](licenses.md). APKs are written to
`app/build/outputs/apk/`. Signed releases are built by CI from a version tag; see
[releases](release.md). The debug APK is signed with a local debug key kept in the
cache directory; it is not a project identity. To prove a clean build, delete (or move
aside) `target/` and `app/build/` first.

`rust-toolchain.toml` selects Rust 1.85.1 for native rustup use with the same
`cargo fmt`/`clippy`/`test`/`build --offline --locked` commands, after
`tools/openssl-build.sh x86_64-unknown-linux-gnu` in a compatible environment and
sourcing `tools/openssl-env.sh`.

## Debug builds on a phone (G0–G3, manual)

```sh
adb install app/build/outputs/apk/debug/app-debug.apk
```

Launch **HeadLink**. Choose **Stop after** `projection` for the video and input
demonstration (G4/G5; see [verification](verification.md)) or `discovery` for the
handshake POC. For G0, the report's `[native_probe]` section must show
`self_test=pass`, `abi=aarch64-android`, and `tls_library=OpenSSL 3.5.8 …`, and its
`[trust]` section `tls_self_test=pass`; that establishes packaging, native loading, and
local TLS only. For the vehicle (see [protocol](protocol.md) on trust and identity):
choose **Stop after** (`version` for G1 alone, `discovery` for G1–G3), arm **Trust the
next wired head unit** for the first attempt with a new head unit, tap Connect before
plugging in, then export the report. Attempts are also saved automatically (including a
stack trace on a JVM crash); relaunch and use **Export saved reports** if the app died.

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

See [reference verification](references.md) and the [license inventory](licenses.md).
Project implementation is GPL-3.0-only; see [LICENSE](../LICENSE). No certification or
universal vehicle compatibility is claimed.
