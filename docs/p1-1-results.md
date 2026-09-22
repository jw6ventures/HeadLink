# P1.1 results — 16 September 2026

Implemented the software side of Android acquisition: USB accessory enumeration and
permission, the connectedDevice foreground service and notification, the JNI
owned-descriptor start/stop/report contract, and a blocking-thread accessory transport
with signal-based cancellation that reuses `Session` for the version exchange. Built a
controlled lab receiver from the pinned Prodigy protocol library and ran the source
harness against it. Prepared the [G0/G1 setup record](g1-setup-record.md).

**No phone was attached, so nothing ran on Android.** The APK has not been installed,
G0–G5 remain open, and cancellation has not been tested on the actual accessory
descriptor. Everything below was verified on Linux x86_64 in Podman with networking
disabled, except the image build and the receiver's source fetch.

## Verification

| Check | Result |
| --- | --- |
| `tools/check.sh`: rustfmt, Clippy `-D warnings`, tests, release build | Pass |
| Rust tests | 47 passed: wire 8, session 7, poll transport 6, accessory transport 9, JNI manager 9, JNI probe 1, CLI 7 |
| Whole-workspace test suite repeated 40 times | 0 failures (after fixing two test races, below) |
| `tools/android-build.sh`: Android Clippy, `.so`, debug/release APKs, lint (warnings as errors), APK verification, JNI host test | Pass; lint "No issues found" |
| JNI host test (`tools/jni-host-test.sh`, HotSpot 17, `-Xcheck:jni`) | 51 checks pass; repeated 10 times, 10 passes; no JNI warnings |
| Report store host test (same script) | 18 checks pass; repeated 10 times, 10 passes |
| Two clean offline builds (`target/` and `app/build/` deleted each time) | Both APKs, Android `libaap_jni.so`, host `libaap_jni.so`, and harness bit-for-bit identical; about 35 s per clean build of both scripts |
| APK verifier against rebuilt mutant APKs | Rejects: INTERNET added; service exported; service type `connectedDevice\|dataSync` (with and without the extra permission); `uses-feature` not required; filter model changed; extra native export |
| Mutation: handler installed with `SA_RESTART` | 8 of 9 accessory transport tests fail (blocked threads are not interrupted and end detached after the 2 s budget); the zero-budget detach test does not depend on interruption |
| Mutation: callback exception not cleared after `CallVoidMethodA` | JNI host test fails on the `-Xcheck:jni` warning |
| Controlled receiver, version stage, both harness I/O modes | Pass (below) |

Artifact SHA-256 after report persistence was added (two further clean builds, identical):

| Artifact | SHA-256 |
| --- | --- |
| `app-debug.apk` (3,032,227 bytes) | `2864ad1c07d4388b90758d8751eda3c54123d279f72e8eeb9c3858fa7a099097` |
| `app-release-unsigned.apk` (2,634,359 bytes) | `59293d4fc06a53714e21aa5e4b6bb314a6fdd1224ebf6df6e6813793ddf86b72` |
| `aarch64-linux-android/release/libaap_jni.so` | `a6f23303fc98f87d3215440f1c6b6506ff051316a464bc4c45f90d7f64ce1bb0` |
| `release/source-harness` | `70218ef94d5413764d6c3d1371ef6af3b0b88950642dbddb4b917a81f2a50326` |

The debug APK hash depends on the local debug key in the build cache.

## Accessory transport (`aap_session::accessory`)

A reader thread and a writer thread own blocking `read`/`write` on the descriptor
(`O_NONBLOCK` is cleared first). The caller's thread owns the protocol, deadlines, and
output order, and hands the writer one chunk of up to 64 KiB at a time. The reader
pauses once 256 KiB of received bytes are queued, so the queue never exceeds that plus
one 64 KiB read. Stopping interrupts blocked system calls with a no-op real-time
signal without `SA_RESTART`, re-sent every 10 ms for up to 2 s. A thread still running then is detached, and the descriptor closes only
when the last thread holding it returns. The decision and its limits are in
[references](references.md#cancellation-strategy-p11-decision-unproven-on-the-device).

Tested over blocking Unix socket pairs and a FIFO:

- Fixture version exchange with split input, then TLS stop; the descriptor is closed
  (the peer reads EOF), and both threads are joined.
- Stop interrupts a read blocked on a silent socket peer and on a FIFO, and a write
  blocked on a full socket buffer (8 MiB pending), each in under 1 s.
- Stop requested before `run` starts, or immediately after: 200 iterations, each
  prompt, cancelled, and cleanly joined.
- EOF, version timeout, and an unsupported 1.7 request (nothing sent) are classified
  with clean teardown.
- A zero stop budget detaches the blocked reader: the report says so, the descriptor
  stays open (no EOF at the peer), and it closes once that reader returns.
- Progress callbacks run on the protocol thread and never regress.

`source-harness --io threads` runs this transport over lab TCP. A CLI test covers it.

## JNI contract

`JNI_OnLoad` registers `nativeProbe`, `nativeStart(I[BLSessionCallback;)J`,
`nativeStop(J)V`, and `nativeGetReport(J)[B` with explicit signatures; `JNI_OnLoad` is
the library's only dynamic export. The descriptors match the compiled Kotlin class
(checked with `javap`).

- `nativeStart` takes ownership of the descriptor on invocation. Invalid descriptor
  numbers are not closed; every other rejection closes it. Tests cover invalid config,
  busy, non-increasing generation, and stale I/O threads; the spawn-failure,
  callback-lookup, and panic paths are not exercised.
- Configuration is strict ASCII `key=value`: generation (must increase), session UUID,
  profile, optional waits. The handle is the generation; no pointer crosses JNI.
- One session exists between a successful start and its stop. `nativeStop` requests
  cancellation and joins the worker, which joins its I/O threads and delivers `Finished`
  before returning. Called from the session's own callback, it only requests cancellation.
- Events (`Progress` on milestone change, `Finished` last) carry the generation and the
  report bytes. The worker attaches to the VM on first use and detaches at exit. A
  Java exception from the callback is described, cleared, and counted as
  `callback_failures`.

The JNI host test loads the release-compiled Kotlin `NativeBridge` and `SessionCallback`
classes with an x86_64 build of the library. Over Unix socket pairs it verifies: the
fixture exchange, with generation-tagged callbacks off the calling thread and `Finished`
last; stop of a blocked session in under 1 s, with `Finished` delivered before stop
returns; the invalid-config, busy, and stale-generation rejections closing the
descriptor (peer EOF); contained callback
exceptions; stop from a callback without deadlock; unknown handles; and no remaining
`aap-*` native threads. A null callback cannot reach native code because Kotlin's
non-null check throws first. This is HotSpot, not ART.

## Android app (0.1.0-p1.1, not run)

- Manifest: required `android.hardware.usb.accessory`; permissions exactly
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`;
  `ConnectionActivity` (singleTop) with the attach intent filter and
  `@xml/accessory_filter` (`Android` / `Android Auto`); `ProjectionService` not exported,
  type `connectedDevice`. Backups remain disabled.
- `ConnectionActivity`: profile selector (`legacy-1.5` only), Connect/Stop, stage and
  last completed milestone, final category and detail, accessory identity with the
  serial redacted, notification-denied warning, report view, and export through the
  system document picker. Connect creates a fresh attempt (generation, UUID), asks for
  notification permission at most once, and enumerates `UsbManager.accessoryList`.
  Attach intents are only a trigger; the accessory must be enumerated. A filter mismatch
  is shown with its identity and needs an explicit "Use listed accessory". USB
  permission uses a package-scoped explicit mutable `PendingIntent` and a not-exported
  receiver, is requested once per attempt, and is re-validated on the result.
  `UsbAccessory.equals` is avoided because it can read the permission-guarded serial.
- `ProjectionService`: promotes itself to the foreground immediately with a notification
  that has a Stop action, re-validates the attempt and accessory, then opens the
  accessory, duplicates and detaches the descriptor, and calls `nativeStart` on a single
  control thread (all native calls are serialized there). It keeps the original
  descriptor and closes it after `nativeStop`. Native events are posted to the main
  thread and dropped if their attempt or generation is stale. Detach broadcasts request
  a stop; nothing reconnects automatically.
- States: IDLE → ARMED → PERMISSION → VERSION → TLS, and STOPPING → IDLE from any of
  them. Milestones (`accessory`, `permission`, `foreground`, `descriptor`, `version`)
  never move backwards, because native events can arrive before the service records
  its start.
- Report: app and device metadata (model, OS build, API, patch, owner user,
  notification state), attempt fields, filter and identity, a bounded event log, the
  native session report, and the native probe. No raw wire data, keys, or accessory
  serial is included.

## Controlled lab receiver

`tools/lab-receiver.sh` builds Prodigy's protocol library at `3afc5dd` (proto gitlink
`open-android-auto@5ff4aa2`) in `localhost/openauto-lab-receiver:p1.1`: Debian trixie by
digest, packages from snapshot `20260915T000000Z` (Qt 6.8.2, protobuf 3.21.12, OpenSSL
3.5.7, CMake 3.31.6, GCC 14.2). A lab patch loads freshly generated RSA-2048 receiver
credentials instead of the library's embedded key. Sources, build, credentials, and
traces stay in the cache. The receiver listens on loopback inside a `--network=none`
container, and the release harness connects to it.

Receiver requesting 1.1, harness in poll mode (the threads mode produced the same
session report plus `io_reader=joined io_writer=joined io_signals_sent=3
io_descriptor=closed`):

```text
receiver_requested_version=1.1
receiver_state=Connecting
receiver_state=VersionExchange
receiver_state=TLSHandshake
receiver_state=Disconnected
receiver_disconnected=TransportError
[AASession] Version response requested= 1.1 reported= 1.5 status= 0x0000 trailing_bytes= 0
trace (logger order): HU->Phone VERSION_REQUEST 00010001; HU->Phone SSL_HANDSHAKE (1,533 bytes);
  Phone->HU VERSION_RESPONSE 000100050000
source-harness (exit 0): requested_version=1.1 response_version=1.5 last_completed_stage=version
  tls_message_len=1533 final_category=TLS_NOT_IMPLEMENTED
```

Receiver requesting 1.7 (Prodigy's default): the harness exited 2 with
`VERSION_UNSUPPORTED`, having sent no bytes, and the receiver disconnected with
`TransportError`. The receiver's ClientHello is described in
[references](references.md#controlled-lab-receiver-p11). It is lab evidence only.

## Report persistence (added before first install)

Added after the slice, at the operator's request, so evidence survives a crash in the
truck, where logcat is unavailable:

- `SourceApplication` installs `ReportPersistence` at process start. Every session state
  change (coalesced per main-loop turn) saves the current attempt's full report to
  `files/reports/report-<UTC start>-<session UUID prefix>.txt`. The activity is not
  required. Writes happen on a background thread, go to a temporary file, and are then
  renamed into place. The newest 50 reports are kept.
- An uncaught JVM exception on any thread synchronously writes the latest report plus a
  bounded `[crash]` stack-trace section, then the platform handler runs. Native crashes
  cannot be caught; the last snapshot remains.
- Reports now include `attempt_started_utc`, `attempt_complete`, `report_file`, and
  `report_store_error`. The activity shows the saved count, the newest name, and any
  report left incomplete by a previous run, and **Export saved reports** exports all of
  them, newest first.
- `ReportStore` is pure JVM. `tools/jni-host-test.sh` now also runs `ReportStoreTest`
  (18 checks) against the compiled Kotlin class: name format and validation, coalescing,
  no leftover temporary files, `saveNow` never overwritten by queued snapshots under
  four concurrent writers, retention, export order, crash-section bounds, and a recorded
  (not thrown) write failure. Removing `saveNow`'s drain and lock was caught in 9 of 10
  runs. Android storage and process death are untested.

## Issues found and fixed during verification

- A manager test could see a "detached" reader return on its own, because it had not
  yet entered `read` when the zero-budget teardown ran. Both detach tests now let the
  reader block first.
- A progress test assumed a snapshot between "response written" and the peer's TLS
  bytes. Progress runs once per batch, so that snapshot can legitimately be skipped.
  The test now checks the guaranteed decided-response snapshot and monotonic milestones.
- The first APK verifier draft expected an unindented `uses-feature` line and a
  literal resource path. Release builds shorten resource paths, so the filter is now
  resolved through the resource table.

## Limitations and open items

- G0, G1, and every on-device behavior are unverified: native loading on ART, attach
  intents, USB permission flow, foreground-service promotion (including the
  connectedDevice USB-permission prerequisite and the behavior when promotion fails
  after `startForegroundService`), notification behavior, report export, and signal
  interruption of the actual `f_accessory` read and write.
- Lifecycle tests on the actual accessory (denial and retry, Stop, detach, blocked
  write) are specified in the [setup record](g1-setup-record.md) but not run.
- The receiver exercised only the version stage and ClientHello; its generated
  credentials were loaded, but no TLS handshake completed. No receiver runs in USB host
  mode.
- APK notices for linked MIT/Apache components are still not packaged.
- prost/protoc and OpenSSL remain unpinned (P2/P3).
