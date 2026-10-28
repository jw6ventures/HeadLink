# Architecture

HeadLink is one Android app with a Rust protocol engine inside it. The engine
(`core/`) speaks the Android Auto source role over a file descriptor; the Kotlin app
(`app/`) finds the head unit, hands the engine that descriptor, and draws everything
the car shows. Desktop tools (`tools/`) run the same engine against lab receivers.

This page covers the shape of the system and the rules it is built under. The protocol
itself is in [protocol.md](protocol.md), transports in [connecting.md](connecting.md),
the car screen in [dashboard.md](dashboard.md), maps and routing in
[navigation.md](navigation.md), and how each part is tested in
[verification.md](verification.md). Build commands are in
[development.md](development.md).

```text
                      phone (main process)                                  head unit
 ┌──────────────────────────────────────────────────────────────────┐
 │ ConnectionActivity (Home/Setup/Maps/Settings/Diagnostics tabs)    │
 │      │ start attempt                    SessionController (main)  │
 │      v                                       ^ state, reports      │
 │ ProjectionService (connectedDevice FGS) ─────┘                    │
 │   │ open USB accessory / take WirelessLink's TCP socket           │
 │   │ dup + detachFd ──> NativeBridge.start(fd, config, callback)    │
 │   │                         │ JNI (libaap_jni.so)                 │
 │   │                         v                                     │
 │   │        aap-jni manager ── aap-session ── aap-tls ── aap-wire ──┼── USB / TCP ──>
 │   │             │ events (generation-tagged) ^ submit video/audio/ │
 │   │             v                            │ now-playing/guidance│
 │   └─> ProjectionPipeline: Dashboard ─> DashboardRenderer (EGL)     │
 │         MapEngine (VTM) ─┘        └─> VideoEncoder (MediaCodec) ───┘
 │       AudioSource, MediaControl, Guide/Routing (BRouter), Traffic   │
 │                                                                    │
 │ WirelessLinkService, WirelessCompanionService, MediaAccessService, │
 │ CallCompanionService, DestinationActivity, WeatherNotifierReceiver │
 └──────────────────────────────────────────────────────────────────┘
 ┌ :preview process ───────────┐
 │ PreviewActivity (phone map) │  shares only files with the main process
 └─────────────────────────────┘
```

## Rust crates (`core/`)

One Cargo workspace (`Cargo.toml`), Rust 1.85.1 pinned in `rust-toolchain.toml`,
GPL-3.0-only. The engine is sans-IO where it can be: protocol state takes bytes in and
gives bytes out, and only the transport layer touches a descriptor.

| Crate | Owns |
| --- | --- |
| `aap-wire` | Framing: the frame header, fragmentation of outbound messages (16 KiB fragments), and per-channel reassembly with hard limits (1 MiB control, 4 MiB AV, 8 MiB in total, 10 s to finish a message). Every error is fatal to the stream. No dependencies. |
| `aap-tls` | The source's TLS 1.2 *server* over memory buffers, using OpenSSL 3.5.8 linked statically. Profiles `tls12-aead` and `tls12-cbc-compat`; head-unit certificates judged by SHA-256 pin or one armed first use; ClientHello decoding and alert capture for diagnosis; the RSA-2048 source identity (`identity.rs`); an in-process self-test (`selftest.rs`). |
| `aap-proto` | Prost types generated from the vendored open-android-auto schema subset (`src/generated/`), plus `decode`, which checks proto2 `required` fields on the raw bytes first, because prost alone turns a missing field into a default (a missing AUTH_COMPLETE status must not read as success). Enum fields stay raw `i32`. |
| `aap-session` | The session: version, TLS, AUTH_COMPLETE, discovery, keepalive, shutdown (`lib.rs`, `version.rs`, `discovery.rs`), and projection (`projection.rs`: channel opens, AV setup, video focus, sensors), `media.rs` (the bounded video/PCM queue and congestion policy), `input.rs`, `now_playing.rs`, `navigation.rs` (instrument cluster). Two transports: `accessory::run` and `transport::run`, below. |
| `aap-jni` | `libaap_jni.so`. `lib.rs` adapts the JNI calls; `manager.rs` is the JVM-independent session registry (config parsing, generations, one session at a time, join on stop) and `ensure_identity`. Also the in-memory `probe` against the spec's version fixtures. |

Dependencies are few and pinned: `openssl`/`openssl-sys` (against the project's own
OpenSSL build, not the crate's `vendored` feature, whose release lags 3.5 LTS),
`prost =0.14.4` (must match `tools/proto-gen`), `jni-sys =0.3.0` (0.3.1 pulls in a
proc-macro chain), `libc`.

### Transports

- **`accessory::run`** (`core/aap-session/src/accessory.rs`) is what the app uses, for
  USB and for wireless TCP alike. The Android accessory driver (`f_accessory`) has no
  `poll` and blocks in `read`/`write` regardless of `O_NONBLOCK`, so a poll loop would
  see constant readiness and then hang where no wakeup reaches it. Instead one reader
  thread (`aap-read`) and one writer thread (`aap-write`) are the only callers of
  `read`/`write`; the session thread owns protocol state, timers and output order. Stop
  sets a flag and interrupts blocked calls with `pthread_kill` of a real-time signal
  (`SIGRTMIN`, because ART uses `SIGQUIT`/`SIGUSR1`) whose no-op handler is installed
  without `SA_RESTART`, re-sent every 10 ms within a 2 s budget. The descriptor is held
  by an `Arc` and closes only when the last I/O thread returns, so nothing closes a
  descriptor another thread may be blocked on. Clean teardown has been observed on the
  real accessory.
- **`transport::run`** (`transport.rs`) is a single-thread `poll` loop with a wakeup, for
  pollable descriptors. Only the desktop harness and tests use it (`--io poll`).

Background on the driver audit is in [references.md](references.md), "Android
accessory descriptor".

## The JNI boundary

`app/src/main/kotlin/com/jw6ventures/headlink/NativeBridge.kt` is the Kotlin half; it is
pure JVM with no Android API, so `tools/jni-host-test` loads the same compiled class
against a host build of the library. Spec §5.3 is the contract.

- **Registration.** `JNI_OnLoad` registers twelve natives with explicit signatures
  (`nativeProbe`, `nativeStart`, `nativeStop`, `nativeGetReport`, `nativeEnsureIdentity`,
  `nativeSubmitVideo`, `nativeVideoFailed`, `nativeSubmitAudio`, `nativeAudioFailed`,
  `nativeSubmitNowPlaying`, `nativeSubmitGuidance`, `nativeRequestShutdown`). A
  Kotlin/native mismatch fails at load, not at call time. `JNI_OnLoad` is the library's
  only dynamic export (`tools/verify-apk.sh` checks).
- **Owned descriptor handoff.** `ProjectionService.openAndStart` opens the transport
  (`UsbManager.openAccessory`, or `ParcelFileDescriptor.fromSocket` on WirelessLink's
  TCP socket, or a debug-only lab `LocalSocket`), duplicates it, and passes
  `duplicate.detachFd()` to `NativeBridge.start`. Ownership transfers on invocation:
  native closes that number on every path, including rejected starts. The service keeps
  the original and closes it only after the native session is released. No Java stream
  ever reads the descriptor.
- **Config** is strict ASCII `key=value` text (`NativeBridge.config`, at most 4096
  bytes): generation, session UUID, profile, identity directory, pins, and every
  per-attempt option.
- **Handles are generations.** Each attempt gets a fresh, strictly increasing generation
  from `SessionController`; `start` returns it as the handle (or a negative
  `START_*` code). No pointer crosses JNI. The manager holds at most one session;
  `stop` requests cancellation and joins the `aap-session` worker, which joins its I/O
  threads and delivers `Finished` before returning. Called from the session's own
  callback, `stop` only requests.
- **Callbacks** are immutable `(generation, kind, report bytes)` events on
  `SessionCallback.onNativeEvent`, delivered from the native worker thread, which
  attaches to the VM on first use. Kinds are mirrored constants (`EVENT_PROGRESS`,
  `EVENT_FINISHED`, `EVENT_VIDEO_START`, ... `EVENT_VIDEO_RESTART`); payloads are
  `key=value` text. ProjectionService only posts them to the main thread, where an event
  whose generation is not the running attempt's is dropped.
- **Submissions** (encoder buffers, PCM, now-playing, guidance) go into shared queues
  (`MediaLink`, `NowPlayingLink`, `GuidanceLink`) and wake the session; they never block
  the caller. A full video queue drops frames until the next keyframe rather than
  stalling MediaCodec (see [protocol.md](protocol.md)).
- **No panics or exceptions cross.** Every entry point is wrapped in `catch_unwind`; a
  Java exception thrown by the callback is cleared and counted.
- **Identity.** `nativeEnsureIdentity` loads or creates the app-private source identity
  and runs the TLS self-test. It is slow (RSA key generation), so `IdentityWork` runs it
  on its own thread at process start, never while a head unit waits.
  `IdentityImport` validates a supplied identity the same way before swapping it in.

Release builds are not minified, so `app/proguard-rules.pro` is inert today. It keeps
everything looked up by name: the natives, the callback, and BRouter's path models,
which profiles name as strings. It also needs `-dontwarn javax.annotation.**`, or R8
refuses to build at all, because VTM references `CheckReturnValue`. A one-off minified
build showed those names survive. That is all it showed: no minified APK has run, and
VTM's own reflection is unaudited. If minification is turned on, test a route and a
projection on a device first.

## The Android app

Package `com.jw6ventures.headlink`, all in
`app/src/main/kotlin/com/jw6ventures/headlink/` plus one file,
`app/src/main/kotlin/btools/router/VoiceHintReader.kt`, which sits in BRouter's package
to reach package-private state without modifying the vendored tree. minSdk 33,
target/compileSdk 36.

### Processes

- **Main process**: everything below. `SourceApplication.onCreate` installs report
  persistence, restores the saved destination (`NavigationStore`), installs the idle
  traffic loop, prepares the identity, and disables the in-call service left on by any
  crash (`CallControl.resetAtStartup`).
- **`:preview`**: `PreviewActivity` alone, the phone-side map of a route and its
  alternatives. VTM keeps its density, tile size, GL state and "main thread" in statics
  that the car's map sets for a 240 dpi panel on the scene thread; a phone map in the
  same process would reset them under a running projection. The two processes share
  only files (a `PreviewRequest` in, the chosen route out), and `SourceApplication`
  returns early in this process so it never touches identity, reports or calls.
  Verified: emulator and lab only.

### Components

| Component | Role |
| --- | --- |
| `ConnectionActivity` | The phone UI: Home, Setup, Maps, Settings, Diagnostics tabs (see [phone-app.md](phone-app.md)). Launcher entry and the `USB_ACCESSORY_ATTACHED` target. Starts attempts; debug builds also accept lab intents, each gated on `FLAG_DEBUGGABLE` at runtime. |
| `SessionController` | Main-thread object holding the current `Attempt` (generation, session UUID, settings fixed at start), its stage (`IDLE` ... `PROJECTING`, `STOPPING`), milestones that never move backwards, the event log, and the latest native and projection reports. Holds no Context. |
| `ProjectionService` | The `connectedDevice` foreground service that owns one session: notification with Stop, transport open and descriptor handoff, the native handle, accessory-detach handling, and while projecting the pipeline, audio, media control, phone status, POIs and search-ahead. It buffers the latest vehicle reading and now-playing because the head unit sends sensors before video starts. |
| `ProjectionPipeline` | Created on the native video-start event, released on stop, so every projection gets fresh encoder, EGL and scene state. Runs the `aap-scene` thread. |
| `VideoEncoder` | MediaCodec H.264 from an input Surface, drained on `aap-encoder` straight into `NativeBridge.submitVideo`; buffers are released as soon as the call returns. |
| `DashboardRenderer`, `Dashboard`, `DashboardDrawing`, `MapEngine` | The car screen: `Dashboard` is the model, layout and touch logic; `DashboardDrawing` paints chrome with Canvas into a bitmap; `MapEngine` drives VTM's `MapRenderer` into its own framebuffer on the scene thread; `DashboardRenderer` composites the two textures into the encoder Surface, re-uploading chrome only when it changes. No Android views touch the encoder Surface (spec §8.2). |
| `SceneRenderer`, `DemoScene` | The original test scene (session token, counters, four buttons). Kept selectable because the lab receiver samples its colour patches and touch calibration aims at its crosses. |
| `AudioSource` | App-owned PCM for the projection audio channel, paced by the head unit's advertised buffer size on `aap-audio` (see [audio-and-speech.md](audio-and-speech.md)). |
| `MediaControl`, `MediaPlayers`, `MediaAccessService` | Now-playing and player list. `MediaAccessService` is a notification listener the operator enables in system Settings; it is the only route an ordinary app has to `getActiveSessions`. |
| `CallControl`, `CallCompanionService` | Optional call controls. The in-call service is disabled in the manifest and enabled only while a session projects with Call controls on. |
| `Routing`, `Router`, `Guide`, `Traffic`, `Favorites`, `NavigationStore` | Navigation (see [navigation.md](navigation.md)). |
| `WirelessLink`, `WirelessLinkService`, `WirelessAuto`/`WirelessCompanionService`, `WirelessStandby`, `WirelessKeepAwake` | The wireless transport (see [connecting.md](connecting.md)). `WirelessLinkService` is the foreground while the link sets up, because Android grants a local-only Wi-Fi request only to a foreground app; `WirelessCompanionService` is bound by Android's companion-device service when the linked truck's Bluetooth connects. |
| `WirelessProbeActivity` | Diagnostics-only probe of a paired device's wireless bootstrap service; not exported, no launcher entry. |
| `DestinationActivity` | Windowless share target for `text/plain` and `geo:`; routes and shows a toast. |
| `WeatherNotifierReceiver` (in `WeatherProvider.kt`) | Breezy Weather's update cue; carries no data. |
| `AppSettings` | Saved choices: one `SharedPreferences` file, `settings`, written through `AppSettings.update` (synchronized, because the settings screen and the dashboard thread both write). `SavedSettings` is pure Kotlin, so the stored form and its repair are host-tested. One-shot choices (trust the next head unit, captures, position in the report) live in `PerAttempt` and are never written. |
| `ReportStore`, `ReportPersistence`, `SessionReport` | Diagnostic reports, below. |
| `TrustStore`, `IdentityWork`, `IdentityImport` | Head-unit pins and the source identity, in `files/identity/` (see [protocol.md](protocol.md)). |

### Report persistence

`ReportPersistence` (in `SourceApplication.kt`) saves the current attempt's full report
to `files/reports/` on every session state change, coalesced per main-loop turn, without
needing the activity. `ReportStore` writes on its own thread to a temporary file and
renames it into place, keeps the newest 50, and is pure JVM (host-tested). An uncaught
JVM exception on any thread synchronously writes the latest report plus a `[crash]`
section before the platform handler runs; a native crash leaves the last snapshot. On
the next start the app flags a report left incomplete. Reports are app-private, excluded
from backup, and carry no keys, raw wire data or accessory serial. This exists because
logcat is not available in the truck. Verified: in daily use on the Pixel.

## Threading model

The rules: Android state changes on the main thread; every native call that starts or
stops a session runs in order on one control thread; protocol work runs on the native
session thread; producers submit to native without blocking; slow work never runs where
a head unit is waiting.

| Thread | Where | Does |
| --- | --- | --- |
| main | app | UI, `SessionController`, ProjectionService state, native events (posted here) |
| `aap-native-control` | `ProjectionService` | transport open, `NativeBridge.start`/`stop`, in order |
| `aap-session` | native `manager` | protocol, TLS, timers, event callbacks |
| `aap-read`, `aap-write` | native `accessory` | the only `read`/`write` calls on the descriptor |
| `aap-scene` | `ProjectionPipeline` | EGL context, dashboard, VTM (its "main thread") |
| `aap-encoder` | `VideoEncoder` | drains MediaCodec into `submitVideo` |
| `aap-audio` | `AudioSource` | PCM pump into `submitAudio` |
| `aap-media`, `aap-players` | `MediaControl`, `MediaPlayers` | now-playing polling, player list |
| `aap-route` | `RoutePlanner` | BRouter searches, at lowered priority; a new request terminates the running one |
| `traffic-fetch`, `traffic-match` | `Traffic` | HERE requests and matching, lowered priority |
| `aap-poi`, `aap-weather`, `obf-library`, `phone-search` | various | POI queries, weather reads, OsmAnd file reads, search |
| `wireless-link` and `wireless-*` | `WirelessLink`, `WirelessKeepAwake` | Bluetooth bootstrap, RFCOMM reader and watchdogs, Wi-Fi keep-awake pings |
| `source-identity` | `IdentityWork` | identity load/create/import and TLS self-test |
| `report-store`, `navigation-store`, `drop-folder`, `map-import`, `routing-import` | various | file I/O |

Routing and traffic run at a lowered priority so they never compete with the scene and
encoder. Time CPU work on the release build only: a debuggable APK ran routing about 30
times slower on the Pixel, because ART runs debuggable apps largely unoptimised. That is
why `tools/android-build.sh` also writes `app-release-debugsigned.apk`: the release
code, signed with the local debug key so it installs over the debug build and keeps the
app's data. Use it on the phone and in the truck; the emulator lab still needs the debug
APK.

## Tools (`tools/`)

**source-harness** (Rust). A desktop binary on the same engine: with no arguments an
offline metadata inspector for a plaintext capture on stdin; with `listen`/`connect` a
full session over lab TCP to an instrumented receiver, stoppable after any stage, with
`--io poll|threads` to choose either transport. In projection mode it renders a test
scene with `test-pattern` and can send a tone. TCP here is a lab transport, not wireless
Android Auto.

**hu-sim** (Rust, test-only, never packaged). A head-unit simulator used by the crate
tests: version request, a plain-OpenSSL TLS client with its own identity, AUTH_COMPLETE,
discovery, the projection channels, and switchable misbehaviour (missing auth,
plaintext discovery, split records, silence, refused opens, withheld acks). It shares
framing and generated types with the source, so it cannot catch a shared misreading of
the schema; the Prodigy-based lab receiver is the independent peer.

**test-pattern** (Rust, lab-only). An H.264 encoder that emits only I_PCM and P_Skip
macroblocks, plus a YUV canvas with rectangles and digits. The stream is large but
trivially correct, so tests and the harness exercise real parameter sets, IDR recovery
and receiver decoding without a platform codec.

**lab-receiver** (C++, `tools/lab-receiver.sh`). The OpenAuto Prodigy protocol library
at a pinned commit, patched only to use generated credentials instead of its embedded
third-party key, driven by `tools/lab-receiver/receiver.cpp` and built in a pinned
Debian container. It decodes the received H.264 with libavcodec and runs scripts (taps,
focus loss, stalls, media buttons, audio, cluster guidance, drives along a track,
day/night flips). Sources, builds and traces live in the cache, never the workspace.

Supporting tools: `jni-host-test/` (JVM tests of the compiled app classes, including the
JNI contract under `-Xcheck:jni`), `proto-gen/` (regenerates `aap-proto`; `check.sh`
fails if the checked-in output is stale), `lab-player/`, `breezy-standin/` and
`here-standin/` (emulator stand-ins for a media player and caller, Breezy Weather, and
HERE), and the build and lab scripts described in [development.md](development.md).

## Constraints, and why

These come from spec.md (APP02, APP09, APP10, §5.1–§5.3, and the third-party runtime
code rule) and are enforced in the build where possible, mostly by
`tools/verify-apk.sh`.

- **An ordinary app.** No root, no system or privileged install, no hidden APIs, no
  signature permissions, no OS modification. It must run in the owner profile of a
  stock GrapheneOS phone. This is why the accessory is acquired through public
  `UsbManager` APIs, why media state comes through a notification listener the operator
  enables, and why full call controls need an app-op granted over adb rather than a
  permission (spec §5.2).
- **No Google or AndroidX libraries.** No Play services, no Google Android Auto
  (gearhead), no AndroidX, no Google account. Platform APIs only. The APK's runtime
  dependencies are VTM, its one transitive dependency `androidsvg`, the vendored
  BRouter, and the Kotlin standard library. `verify-apk.sh` fails on any `gms` or
  `gearhead` reference in the dex.
- **Offline by default.** The handshake and projection need no network, no certificate
  issuance and no attestation. Map, routing and OsmAnd data are supplied by the operator
  (see [data.md](data.md)), never downloaded; VTM's networked module `vtm-http` is
  deliberately not a dependency. Weather reads Breezy Weather's provider on the phone.
- **`INTERNET` only for opt-in live traffic.** It is the one networked integration, off
  by default; with it off no socket opens (host-tested in `TrafficClientTest`). With it
  on it contacts only HERE's traffic host with the operator's own key, only while a route
  is followed. `INTERNET` cannot be revoked on stock Android, so the guarantee lives in
  the code. Cleartext is allowed only in debug builds, for the lab's stand-in
  (`app/src/debug/`); `verify-apk.sh` checks a release build carries no network security
  config. The wireless link's Wi-Fi request is local-only and leaves the default network
  alone.
- **Every permission is separately optional.** Each integration is offered on its own,
  asks for its grant only when turned on, and degrades to `—` without it (APP09). The
  manifest comments in `app/src/main/AndroidManifest.xml` say what each permission is
  for; `verify-apk.sh` pins the exact permission set, service count and exported
  components, so adding one is a deliberate act. Backup is disabled. Wireless alone
  adds five:
  - `BLUETOOTH_CONNECT`, the runtime "Nearby devices" grant, asked for when the probe
    opens or on the first Connect wirelessly;
  - `CHANGE_NETWORK_STATE`, for the local-only Wi-Fi request;
  - `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` and
    `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`, which act only once
    the operator has linked the truck;
  - `WAKE_LOCK`, held for at most 90 s while the truck's own start request is set up,
    and 5 s per Bluetooth frame while standing by.

  All but `BLUETOOTH_CONNECT` are normal install-time permissions. Details are in
  [connecting.md](connecting.md).
- **Pinned OpenSSL, statically linked.** OpenSSL 3.5.8 LTS, built from a checksummed
  tarball by `tools/openssl-build.sh` (with a fixed `SOURCE_DATE_EPOCH`) and linked into
  `libaap_jni.so`. The library may need only `libc`, `libdl`, `libm` and `liblog`, so the
  TLS behaviour does not depend on whatever the platform ships.
- **One ABI, `arm64-v8a`.** The APK contains exactly `libaap_jni.so` and VTM's 45 KB
  `libvtm-jni.so`, stored uncompressed and 16 KiB aligned. Gradle never runs Cargo;
  `tools/android-build.sh` cross-builds the library and stages it in
  `target/android-jniLibs`, and the Gradle build refuses to package without it.
- **Pinned, offline, reproducible builds.** Every toolchain is pinned (Rust, JDK, SDK,
  NDK, Gradle, AGP, Kotlin, protoc, OpenSSL) and builds run in Podman images with
  networking off; Cargo runs `--offline --locked`, and every Gradle artifact is checked
  against `gradle/verification-metadata.xml`. Two clean builds on the build machine give
  byte-identical unsigned release APKs; that APK is the reproducible artifact and the
  signed ones derive from it. Release builds are not minified. Reproducing across
  machines (for F-Droid) is not yet checked; see [release.md](release.md).
- **Third-party source held unmodified.** Vendored code is copied whole at a recorded
  pin and never edited, so provenance stays a diff against that pin and a re-sync is a
  directory copy:
  - `vendor/brouter/`: BRouter v1.7.10 (`4d2639af`), five runtime modules, 101 files,
    compiled as a plain Java library so no Android API can creep in. Vendored because
    BRouter publishes no Maven Central artifact, so there is nothing for
    `verification-metadata.xml` to pin. Warnings are suppressed, not fixed.
  - `protocol/upstream/open-android-auto/`: 132 `.proto` files from open-android-auto
    `61eab61`, the input to `aap-proto`.
  - `protocol/upstream/osmand/OBF.proto`: reference only; nothing is generated from it
    or packaged.
  - VTM is not vendored: it is the pinned 0.25.0 release from Maven Central (the last
    one published there; later releases are JitPack-only), checksummed and used
    unmodified. The integration is glue against its public API, and the map themes are
    original files.

  Licences and pins are listed in [licenses.md](licenses.md).
