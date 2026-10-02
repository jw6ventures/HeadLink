# POC implementation plan

Based on [spec.md](spec.md), version 0.1, 15 September 2026. Slices P0.1 and P0.2 below are complete. The pre-hardware P2/P3 lab slice is complete as lab work only. Update 17 September UTC: saved Pixel 9/Ford reports provide G0/G1 evidence and clean native teardown. With the imported AA-17.6 source identity, G2 passed (report 3812ebff), and 0.2.3-lab passed factory G3 with a full hold and encrypted keepalive (report c2407cff). The P4 projection slice is then implemented and verified in the lab only (0.3.7-lab): channel opens, AV setup, focus, the MediaCodec encoder and owned scene, flow control, input, and sensors, with decoded-video and input evidence from the controlled receiver and from the app running on ART. Update 18 September UTC: 1920x1080 projection now displays on Ford SYNC 4.0 (report 709ffa9c) — the cause was this source answering `UPDATE_UI_CONFIG_REQUEST` with an empty body — and the P5.1 media-control, P5.2 sensor-harvest and P5.3 audio-out slices below are implemented and lab-verified. Update 19 September UTC: **G6 and G7 both pass on the factory head unit** (0.4.1-lab, reports a24db7e0 and 7f869585). It took three attempts: Ford refused audio focus on the first (f9ce185b, dc2c60ee), **granted** it on the second (5cb646ec, 9eb3a396) where this source discarded the grant by gating on an optional response field Ford sets false either way, and on the third the tone was audible from the vehicle speakers with zero underflows over 40.8 s. The same runs carried `sensors=all`, which supplies **P5.2's outstanding vehicle evidence**: LOCATION, CAR_SPEED, PARKING_BRAKE, GEAR, NIGHT_DATA, DRIVING_STATUS and GPS all carry values; FUEL_LEVEL and TRAILER are accepted and silent. **G4 and G5 remain open** (the five-minute, five-reconnection run has not been done), as does G8; **G6 and G7 are met**. Update 19 September UTC (later): the defect that made Ford SYNC 4.0's own "Android Auto" taskbar tile a no-op after the head unit took the screen for its Audio page, Settings, or the reversing camera is fixed and confirmed on the factory head unit (0.4.5-lab, report `c3ea4fd6`) — a video focus loss now sends a formal AV stop indication instead of merely pausing the stream, matching what Android Auto 17.6 itself does, and the tile successfully re-enters the session afterward (`ui_config_updates=2`, previously always 1 in every prior report). See [Ford re-attach investigation](docs/ford-focus-reattach-investigation.md) and docs/references.md. **G5 was blocked specifically because a session could not survive the head unit taking the screen; that blocker is now removed**, though the five-minute, five-reconnection run itself still has not been done. G4 remains open independently. See [P4 lab results](docs/p4-lab-results.md), [P5.3 lab results](docs/p5-3-lab-results.md), the [G4/G5 procedure](docs/g4-g5-procedure.md), and the [P5 plan](docs/p5-plan.md). The historical slice notes below describe their state at completion; remaining lifecycle tests and later gates are still open. See [authentication investigation](docs/ford-auth-investigation.md) for evidence and the supplied-identity trial. Update 22 September UTC: the queued spec and plan amendments from P5.5, the [P6 plan](docs/p6-plan.md) and its slice records, and the [A2DP proposal](docs/a2dp-supersedes-usb-audio-proposal.md) are **applied**. That added the P5.5 and P6 sections below, gates G8–G10 in spec §11.1, and a gate status record; it also fixed a stale claim above: **G4 passed on 17 September** (report da9398a5), not "open". **G9 passed on 21 September** (reports 1f4db62b, ad772b8a), and **G8 and G10 passed by operator observation on a drive the same day**: the route was followed on the projected map and on the cluster, the turn icons all made sense, and a deliberate deviation re-routed quickly enough on both. That drive's report is not yet filed. USB audio is deprioritised in favour of Bluetooth A2DP. **G5 is the only gate still open.**

The required handshake POC ends at **G3: encrypted service discovery on the actual factory head unit**. The subsequent projection demonstration ends at **G5: live video, input, and repeat reconnection on that head unit**. Lab success must be reported separately from factory success. Check off implementation tasks after verification and acceptance gates only after collecting their required evidence.

## Scope and execution order

- [ ] Preserve the ordinary-app constraints throughout: GrapheneOS owner profile, public APIs, no GMS/gearhead, Google account, root, hidden APIs, signature permissions, proprietary runtime libraries, or copied OEM/Google private keys.
- [ ] Keep handshake and wired demonstration offline; allow INTERNET only in a separate lab flavor if necessary.
- [ ] Defer maps, routing, media libraries, third-party audio capture, microphones, contacts, messaging, calls, voice assistants, wireless, arbitrary app mirroring, HUD/dashboard output, and vehicle control. *(P5.1 adds media **controls** — transport buttons and now-playing text for playback another app owns — which is not audio capture, not a media library, and not app mirroring; see spec §2.1 and §5.2. Maps and routing were deferred from the POC and delivered in P5.5 and P6 below. Third-party audio capture is not pursued: Bluetooth A2DP already carries the phone's audio to the truck, spec §2.2.)*
- [ ] Execute P0/P1 far enough to obtain a native build and first factory accessory/version trace before estimating dates or investing in projection UI. Then complete P2 → P3 → P4, validating each stage with an independent lab receiver before its factory trial.
- [ ] Record protocol deviations as explicit compatibility profiles with reference commits, supporting captures, and regression fixtures; do not silently broaden compatibility behavior.

## Completed slice — P0.1: bounded wire foundation

Completed 16 September 2026: layout, reference pins, dependency-free Rust framing,
bounded incremental parsing/assembly, independent fixtures, and an offline fixture
inspector. See [P0.1 results](docs/p0-1-results.md).

## Completed slice — P0.2: audits, pinned Android build, version-only lab session

Completed 16 September 2026. Does not complete P0: the controlled receiver, protobuf
generation, prost/protoc/OpenSSL pins, and device loading remain open.

- [x] Audit message directions, encryption exceptions, version/WireConfig behavior,
      initial schema closure, reference licenses, and accessory-driver `poll`
      support at pinned commits ([references](docs/references.md)).
- [x] Pin JDK, Android SDK/NDK, Gradle wrapper, AGP, Kotlin, Rust Android target, and
      crates in a Podman image and lockfiles; verify Gradle artifacts by SHA-256.
- [x] Build a permission-free arm64-v8a APK packaging `libaap_jni.so` with an
      in-memory engine probe; lint with warnings as errors; verify APK invariants
      and test the verifier against broken APKs.
- [x] Add the sans-IO `aap-session` version stage (`legacy-1.5`), structured
      outcomes/report, and a cancellable poll loop for pollable descriptors.
- [x] Extend the harness with TCP lab `listen`/`connect` over the same engine; test
      against fixture peers; prove clean, offline, reproducible builds.
- [x] Document results, license inventory, and the next slice.

Verification: `tools/check.sh` (28 tests) and `tools/android-build.sh` passed offline
from clean outputs, and two clean builds produced bit-for-bit identical APKs and
binaries. See [P0.2 results](docs/p0-2-results.md).

Findings that change later work: a stock Prodigy receiver requests 1.7, which
`legacy-1.5` rejects without responding; the `android14-6.1` accessory driver has no
`poll` and ignores `O_NONBLOCK`, so the lab poll loop cannot cancel a blocked
accessory read; AACS encrypts ping responses, unlike the receiver policy.

## Current slice — P1.1: Android acquisition software, accessory transport, lab receiver

Completed 16 September 2026 as far as possible without the phone. **No phone was
attached: the APK was not installed, G0 and G1 were not attempted, and cancellation was
not proven on the actual accessory descriptor.** Those items move to P1.2.

- [ ] Sideload for G0 on the GrapheneOS phone. *(Not attempted: no device attached.)*
- [x] Implement accessory enumeration, filter-mismatch diagnostics, package-scoped USB
      permission, the connectedDevice foreground service with a Stop notification, the
      attempt state machine, and local report export. *(Builds and lints clean, and the
      manifest invariants are verified in both APKs. Not run on a device.)*
- [x] Implement the JNI owned-descriptor start/stop/report contract with
      `JNI_OnLoad` signature registration, generation handles and callbacks, serialized
      sessions, and join-on-stop. *(Rust host tests plus a HotSpot `-Xcheck:jni`
      contract test of the compiled Kotlin bridge. Not run on ART.)*
- [x] Build a blocking-thread accessory transport with signal-based cancellation,
      reusing `Session` for the version exchange. *(Host-proven on blocked socket
      reads, FIFO reads, and full-buffer writes.)*
- [ ] Prove that cancellation works on the actual accessory descriptor. *(Procedure in
      the setup record.)*
- [x] Prepare the G1 setup record and device test procedure ([record](docs/g1-setup-record.md)).
- [x] Establish a controlled receiver configured for a 1.1 request and run the TCP
      harness against it. *(Prodigy protocol library at the pin, generated credentials,
      loopback TCP, receiver-side JSONL traces; version stage and ClientHello only.)*
- [x] Document results, references, licenses, and the next slice.
- [x] Persist each attempt's report in app-private storage as it progresses, with JVM
      crash capture and export of saved reports, so truck evidence survives a crash.
      *(18-check host JVM test of the store; not run on a device.)*

Verification: `tools/check.sh` (47 tests) and `tools/android-build.sh` (lint, APK
verification, 51-check JNI host test, 18-check report store test) passed offline from
clean outputs. Two clean
builds were bit-for-bit identical. The workspace tests passed 40 repeated runs, the JNI
test 10. The APK verifier rejects six rebuilt mutant APKs, and mutations of signal
flags and JNI exception handling are caught. See [P1.1 results](docs/p1-1-results.md).

Findings that change later work: the controlled receiver accepts `1.5 MATCH` for its
1.1 request and immediately sends a 1,533-byte ClientHello offering TLS 1.3 and 1.2.
Its default 1.7 request is refused without a response, as audited. Prodigy's
library otherwise loads an embedded third-party TLS key; the lab build patches that
out. Each G1 attempt that reaches `TLS_NOT_IMPLEMENTED` ends while the head unit waits,
so it also tests interrupting a blocked read on the real accessory driver.

The P2 lab work named here was started without the phone and extended through P3;
see the next section.

## Completed slice — P2/P3 lab work before the first hardware test

Completed 16 September 2026 as lab work only, so that one vehicle visit can attempt
G1 through G3. **No phone or head unit was used: G0–G5 remain open, and the lab receiver
does not verify the source certificate.** See [P2/P3 lab results](docs/p2-p3-lab-results.md).

- [x] Pin and build OpenSSL 3.5.8 LTS from the upstream tarball as static libraries for
      the host and arm64 Android, reproducibly and offline.
- [x] Vendor the 51-file open-android-auto schema closure; generate prost types with
      pinned protoc and prost-build; enforce required fields and wire types from a
      generated schema table; check generated code in `tools/check.sh`.
- [x] Implement the TLS 1.2 memory-transport server, peer policies, ClientHello and
      alert diagnostics, record-boundary handling, and the generated source identity
      with an in-process self-test (`aap-tls`).
- [x] Extend the session through AUTH_COMPLETE, one encrypted discovery request,
      validated capabilities, keepalive, the bounded hold, and shutdown, with
      `stop_after` limits and spec §7.6 deadlines.
- [x] Extend the JNI contract, harness, and controlled receiver; pass lab C1 and the lab
      G2/G3 mechanics against the Prodigy receiver in both I/O modes, plus negative runs.
- [x] Add the app's identity preparation, stop-after and TLS-profile selectors, armed
      first use with pinning after a completed handshake, pin clearing, identity
      replacement, report trust section, and packaged notices. *(Builds and lints clean;
      host JVM tests only. Not run on a device.)*
- [x] Extend the G0 procedure and write the G2/G3 and C2/C3/C4 procedures in the
      [setup record](docs/g1-setup-record.md).
- [x] Add an optional x86_64 emulator smoke test of the app on ART with CheckJNI
      (`tools/emulator-smoke.sh`); it passed and is not device evidence.
- [x] Prepare opt-in, reported fallbacks for evidence recorded during a visit:
      `tls12-cbc-compat` (no common baseline suite) and `legacy-1.5-any-minor` (a 1.6+
      version request). Neither is a default; each needs the recorded evidence first.
- [x] Find what withholds Ford SYNC 4.0's expand affordance. *(Nothing we send: its own
      expand control switches its advertised margins between 586x198 and 0x198, under
      either version profile, and the state does not survive a session. The `match-1.6`
      profile was built to test the version answer and ruled it out; see docs/references.md.)*
- [x] Find why this source displayed nothing above `800x480` on Ford SYNC 4.0 while every
      unit was acknowledged and projected focus held. *(Root cause: Ford pushes
      `UPDATE_UI_CONFIG_REQUEST` (0x8009) with a new content rectangle a second or two into
      projection and withholds the picture until it is answered with 0x8012; we recorded it
      as an unhandled message and dropped it. Found by diffing a working report against a
      failing one, decoded in report 07ba8ae0, documented in docs/references.md. Ruled out
      along the way: the H.264 level, bitrate, and frame rate — 1280x720@30 is level 3.1 at
      4.8 Mbps, as the working 800x480@60 is, and still blank (report 3c1908e0); the
      `VideoResolution` enum mapping, since all three of Ford's configurations agree on a
      1.5125 content aspect only under the current mapping; and the margin interpretation,
      since `scene_area=frame` changed nothing (report 5336c065).)*
- [x] Answer 0x8009 with UPDATE_UI_CONFIG_REPLY (0x800A). *(Accepted: Ford stopped
      replying with the empty 0x00ff message and `unhandled_messages=0` for the first time
      above 800x480, reports b3d46ae3 and 8aefa9a3. The picture still does not appear, so
      the resize conversation was a real bug but not the only one.)*
- [ ] Find why a picture above `800x480` still does not appear when every message is now
      handled and answered, the geometry matches what Android Auto computes for the same
      head unit, and the stream is acknowledged frame for frame. Reports are exhausted: a
      working 800x480 run and a blank 1280x720 run differ only in the fields added for
      this investigation. Remaining differences against Android Auto 17.6
      (docs/references.md): it answers 1.6 with **1.7**, opens **all ten** advertised
      channels, never requests video focus, and encodes with a 60-second I-frame interval.
      `match-1.7` was implemented and **ruled out**: Ford accepted 1.7 and the session ran
      to projection with no picture (report 5b103cc4). The content-sized bitrate is in.
      The emitted stream is also ruled out: 1820 frames decode with zero errors and the
      composition follows Ford's resizes correctly (report e00fe2f2, docs/references.md).
      Imitation is exhausted: the version answer (1.5/1.6/1.7), all ten advertised channels
      opened and accepted, unsolicited focus, a 60-second I-frame interval, and the
      content-sized bitrate all change nothing **for this blank-picture question**, and
      `AVChannelSetupRequest` field 1 is confirmed to be the codec type rather than a
      configuration index (report bcf4184b). (Channel coverage was independently retested
      against a different symptom — the taskbar tile not re-entering a session after the
      head unit took the screen — during the re-attach investigation below, and again was
      not the cause: the confirmed-fix run had `projection_extra_opened=0`. See the P4
      focus-handling note and docs/ford-focus-reattach-investigation.md.)
      A clean same-session A/B (reports 027917d8 and 3eec0846) shows 800x480 displaying and
      1920x1080 blank under an otherwise identical session, so the split is the video
      configuration itself and nothing this source changed. The dex was then traced: Android
      Auto's video-channel message set on this head unit is the same as this source's —
      0x8011, 0x800C, the overlays and 0x8015 exist in its code but never appear in the
      vehicle logcat — so no missing message explains the blank picture. What the trace did
      settle is the **0x800A reply body**: `{1: AdditionalVideoConfig}` echoing the
      configuration received. **That was the root cause** — with it, Ford displays
      1920x1080@60 and its touches land (report 709ffa9c, docs/references.md). Defaults are
      now `match-1.7` and `largest`. Not needed: the extra channels, unsolicited focus, or
      the 60-second I-frame interval.
- [ ] Re-run the G4/G5 evidence at 1920x1080 now that it displays, and record the vertical
      content origin as settled (`content.y = 99`, taps within 15px, report 709ffa9c);
      spec §8.3 had only ever been exercised at `content.y = 0`.
- [ ] Report upstream to open-android-auto: `AVChannelMessageIdsEnum.proto` is missing
      `UPDATE_UI_CONFIG_REPLY = 0x800A`, lists only two of the four
      `MEDIA_MESSAGE_UPDATE_*UI_CONFIG_*` names, and has no schema for 0x8009/0x800A —
      both are `{1: AdditionalVideoConfig}`, and a source that answers 0x8009 with an empty
      body or with 0x8012 gets a silently blank screen. Then lift the pinned-800x480 default of `video_preference`, and
      report the missing 0x800A enum entry and the four MEDIA_MESSAGE_UPDATE_*UI_CONFIG_*
      names upstream to open-android-auto.

Verification: `tools/check.sh` (91 Rust tests, generated code current) and
`tools/android-build.sh` (lint, APK verification, 66-check JNI host test, 18- and
17-check store tests) passed offline from clean outputs; two clean builds were
bit-for-bit identical; the workspace tests passed 25 repeated runs; 13 of 14 mutations
were caught.

Next slice (P1.2, hardware): record the setup; sideload and run G0, including
`tls_self_test=pass`; on the factory head unit run one `version` attempt (G1 and the
ClientHello), then `discovery` with first use armed (G2/G3, trial C2), then a pinned
repeat (C3); run the lifecycle tests on the actual descriptor. Fix only what that
evidence shows. If acquisition or cancellation is blocked, classify it precisely before
interpreting any TLS result. Lab success above must not be reported as factory progress.

## P0 — References, build foundation, and lab harness

References: spec §§3–4, 5.1, 6, 12–13.

- [x] Establish the proposed layout: Kotlin `app/`; Rust `core/aap-{wire,tls,session,proto,jni}/`; `tools/source-harness/`; `tools/capture-tools/`; `protocol/upstream/`; `protocol/fixtures/`; and `docs/`.
- [x] Pin the reference baselines: AACS `faa1cf208feb5dfe1cb9535be16daeac4f08da0c`, open-android-auto `61eab61c5f9968154ff1a80faa8c0a427b208479`, Prodigy `3afc5dd8bbacb5ce68e109189b18b14b251fabef`, and aasdk `9ee5283245a630afce3a441696b9dde484ecbcd9`.
- [x] Recover precise source-file/document links for the spec's prose references; verify contradictory message directions and encryption policies against pinned implementation code before coding. *(P0.2; aabox has no pinned commit and TLS messenger internals are audited with P2.)*
- [x] Inventory licenses for copied code, schema inputs, generated code, dependencies, and fixtures; record the project license decision and preserve required notices. Do not copy code from unlicensed references. *(Inventory current through the P2/P3 lab slice; notices packaged in the APK and checked by the verifier.)*
- [x] Pin Gradle wrapper, AGP, Kotlin, JDK, Rust, NDK, prost/protoc, and bundled OpenSSL; configure minSdk 33, compileSdk/targetSdk 36, and arm64-v8a. *(P0.2; protoc 36.1, prost 0.14.4, and OpenSSL 3.5.8 LTS in the P2/P3 lab slice.)*
- [ ] Build/package/load native libraries from the APK and document repeatable clean build, install, and test commands. Use Podman for any containerized build/test environment. *(Build, packaging, and commands verified in P0.2; JNI registration exercised on a host JVM in P1.1; loading on a device awaits G0.)*
- [x] Generate only the transitive protobuf subset required for version-related extensions, authentication, discovery, ping, and shutdown initially; add explicit required-field and supported-value validation rather than relying on protobuf defaults. *(P2/P3 lab slice: 51 files; required fields checked on the wire; enum values validated where used.)*
- [x] Establish a controlled receiver using generated credentials and receiver-side traces. Verify that its lab TCP listener works independently; otherwise document and use its wired USB-host mode. *(P1.1: `tools/lab-receiver.sh`, Prodigy protocol library at the pin with a generated-credentials lab patch; TCP listener verified through the version exchange and ClientHello. TLS completion is P2.)*
- [x] Build a desktop source harness using the same Rust session engine behind a transport abstraction. Label TCP as a lab adapter, not wireless Android Auto; do not assume a workstation USB port can act as the phone accessory. *(P0.2; version stage only.)*
- [x] Implement incremental frame parsing, serialization, and per-channel plaintext assembly using numeric flags, big-endian lengths, FIRST totals, message IDs, and category/encryption consistency from spec §6.
- [x] Enforce the initial limits: 16 KiB outbound plaintext fragments, 65,535-byte inbound wire payloads, 1 MiB control assemblies, 4 MiB AV assemblies, and 8 MiB aggregate reservations; bound incomplete-assembly lifetime and ensure ciphertext fits the wire length field.
- [x] Implement one ordered transport/event loop with a 64 KiB initial read buffer, partial reads/writes, EINTR handling, and cancellation wakeup. Reject malformed/reserved flags, overflow, invalid fragment sequences, duplicate starts, and incorrect totals with structured errors. *(P0.2, for pollable descriptors. P1.1 added the blocking-thread accessory transport with signal cancellation, host-tested; proof on the actual descriptor is a P1 task.)*
- [x] Add independent fixture tests for the complete version/auth packets in §7.2; byte-at-a-time headers; coalesced frames; FIRST/MIDDLE/LAST; interleaved channels; bounds; invalid flags/lengths/totals; and partial writes. Add bounded parser/assembler property or fuzz tests.

Exit: reproducible native/APK builds, usable lab transport, and independently checked wire fixtures. Factory compatibility remains unproven.

## P1 — Android acquisition, lifecycle, and version exchange

References: spec §§5, 7.2, 9–11.

- [ ] Implement `ConnectionActivity` with Connect/Stop, selected profile, connection stage, failure reason, and local report export; keep blocking work off the UI thread. *(Implemented in P1.1; not run on a device.)*
- [ ] Declare USB accessory support and the specified foreground-service/notification permissions; add the initial Android/Android Auto accessory filter and attach handling. *(P1.1: declarations and filter verified in both APKs; attach handling not run on a device.)*
- [ ] Provide manual `UsbManager.accessoryList` enumeration; validate attach extras against currently enumerated accessories and distinguish filter mismatch from no accessory. *(Implemented in P1.1; not run on a device.)*
- [ ] Implement package-scoped USB permission requests and a confined result receiver using current public API rules; validate grant/accessory state and handle denial, null descriptors, and API exceptions without prompt loops. *(Implemented in P1.1; not run on a device.)*
- [ ] Start/promote a `connectedDevice` foreground service from the visible activity after USB permission; supply a visible session notification with Stop and define user-visible behavior when notification permission is denied. *(Implemented in P1.1; not run on a device.)*
- [ ] Implement the JNI start/stop/report contract. Transfer one duplicated/detached FD to native ownership on invocation, close it on every native success/failure path, and retain/close the service's original descriptor separately. Do not add another Java reader/writer. *(P1.1: native contract and Kotlin bridge verified on a host JVM; the service's descriptor duplication and close are not run on a device.)*
- [ ] Serialize native work; implement cancellation via wakeup, join before handle release, safe JNI thread attachment, immutable generation-tagged callbacks, main-thread UI updates, and protection against stale callbacks or panics/exceptions crossing JNI. *(P1.1: native parts verified on HotSpot; ART, main-thread posting, and signal interruption of the accessory not run on a device.)*
- [ ] Implement Idle/Armed → Permission → Version and universal Stopping → Idle transitions, with a fresh session UUID/generation/assembler on each attempt and no automatic reconnect loop. *(Implemented in P1.1; not run on a device.)*
- [ ] Receive/validate the HU version request and emit the baseline legacy 1.5 response for supported major-1 requests; test with the lab 1.1 request. Record full request/extension bytes and chosen version separately. *(Engine and scripted 1.1 fixture test done in P0.2; the controlled receiver's 1.1 exchange passed over lab TCP in both I/O modes in P1.1; the accessory path is pending.)*
- [x] Return a controlled unsupported-profile result for unknown versions/extensions. Inspect WireConfig requirements before adding 1.7 or modern profiles; do not advertise unsupported follow-on behavior. *(Shared engine, P0.2.)*
- [ ] Build and sideload on the target GrapheneOS phone; verify launch, native loading, dependency/permission constraints, and report export (**G0**).
- [ ] Prepare stationary factory testing: record actual vehicle/head unit and firmware, phone/OS/API, owner profile, USB policy, port, cable, and Bluetooth pairing. Treat the expected 2023 F-150 as unverified until recorded. *(Template and procedure prepared in P1.1: [setup record](docs/g1-setup-record.md); not filled.)*
- [ ] Test unlocked with USB data permitted; prove actual accessory acquisition, incoming HU version request, and valid response transmission (**G1**). Use app-local export or authorized wireless ADB for evidence.
- [ ] Test USB denial/retry, Stop, detach at version, and cancellation during blocked reads/writes on the actual accessory FD. Confirm clean return to idle and a successful fresh attempt without descriptor leaks. *(Procedure written in P1.1; not run.)*

Exit: G0/G1 evidence on the target setup, or a precisely classified acquisition/version blocker. If G1 fails, isolate cable/port, enumeration, filter/permission, profile/USB policy, and receiver mode before investigating TLS.

## P2 — TLS, identity experiments, and authentication

Dependencies: tested framing, version exchange, and lab peer. References: spec §§7.1–7.4, 9, 11.

- [x] Bundle OpenSSL behind a Rust memory-BIO adapter; use the phone as TLS server, the HU as client, and one TLS session shared across all transport channels. Do not use Android private libssl or direct socket TLS on the accessory stream. *(Lab-verified; not yet run on the phone.)*
- [ ] Generate/load a local RSA-2048/SHA-256 self-signed non-CA server-auth identity before attachment. Store its private key app-privately and exclude it from backups and all exports. *(Implemented: generated at app start in `files/identity/`, backup excluded, never in reports; host-tested only.)*
- [ ] Configure TLS 1.2 only, require a client certificate, and select explicit supported suites after inspecting the peer ClientHello. Put any evidence-required CBC/legacy relaxation in a separately reported profile. *(TLS 1.2 only, client certificate required, baseline `tls12-aead`, and a reported `tls12-cbc-compat` profile implemented; the suite choice awaits the factory ClientHello.)*
- [x] Carry handshake records inside plaintext channel-0 `0x0003` messages; handle fragmented input, multiple output records, WANT_READ/WANT_WRITE, alerts, and verified peer Finished without fixed packet counts or busy loops.
- [x] Encrypt/decrypt application fragment payloads with the same TLS session. Fragment plaintext before encryption, assemble after decryption, preserve frame metadata across partial records, and never reorder ciphertext or include handshake wrappers in application messages.
- [ ] Implement certificate trials: C1 with lab CA/pin; explicitly pre-armed, session-scoped first-use trust for factory C2; persisted HU fingerprint and mandatory pin match for repeat C3; optional second generated source identity for C4. Fingerprint changes require explicit re-arming. *(C1 pin passed in the lab; C2 arming, pin persistence, C3 pin match, and C4 replacement implemented in the app; not run on a device.)*
- [x] Require both local TLS completion and explicit AUTH_COMPLETE status 0 in the expected state; reject missing status, premature auth, failures, and unsupported binding. Accept baseline plaintext auth; support encrypted auth only as an explicit recorded variant. *(No binding exchange exists to support; other messages before discovery are recorded.)*
- [x] Add TLS tests with independently generated peers for missing client certificates, pin mismatch, wrong TLS version, no common suite, fragmented input, multi-record output, peer alerts, encrypted protobuf round trips/fragmentation, and plaintext auth/ping exceptions.
- [ ] Pass C1 in the lab, then C2 on the actual HU; collect Finished/auth evidence and certificate-policy/suite/fingerprint diagnostics for **G2**. Repeat with the same identity and HU pin for C3. *(C1 passed in the lab; factory C2/C3 pending.)*

Exit: lab and factory G2, or a report separating TLS mechanics from source-identity rejection. A timeout does not prove certificate pinning; changing our HU verification cannot fix HU rejection of our source certificate. Unsupported additional binding is a new milestone, not a pass.

## P3 — Encrypted discovery and handshake POC delivery

Dependencies: P2. References: spec §§7.5–7.6, 9–11, 14.

- [x] Send exactly one encrypted ServiceDiscoveryRequest after successful authentication using current pinned fields, neutral project identity, and a fresh session UUID; omit optional icons.
- [x] Decrypt and validate the actual ServiceDiscoveryResponse; reject missing required fields, duplicate/zero/out-of-byte-range channels, invalid descriptors/configurations, and invalid input display mappings. *(Structural violations reject the response; invalid configurations and mappings make that service unusable and are reported. Lab receiver only.)*
- [x] Build a capability registry that distinguishes transport channels, service types, and logical displays; retain unknown optional services for diagnostics and skip them operationally.
- [x] Identify usable advertised H.264 and input capabilities dynamically. An empty or merely decodable response cannot pass G3; do not hardcode channel IDs.
- [x] Implement ping request/response with exact signed int64 timestamp echo, pinned-schema validation, baseline plaintext responses, and intentional profiles for observed encrypted variants. Do not expose unrelated system data for a bugreport flag. *(Ford SYNC 4 pings encrypted and stops after a plaintext response (report c1265b54), but completed a hold with encrypted responses (c2407cff). Since 0.2.4-lab the default `mirror` profile follows the head unit's latest ping encryption. `plaintext` and `encrypted` override it (spec §7.6).)*
- [x] Implement configurable defaults: version/ClientHello waits 5 seconds each; TLS/auth/discovery waits 10 seconds each; absolute establishment cap 30 seconds; ping response target below 100 ms; own pings every 2 seconds when needed; failure after three unanswered requests.
- [x] Bound discovery-only hold to 30 seconds and handle generated shutdown request/response messages. Preserve G3 success if a subsequent missing-video timeout requires moving to projection activation.
- [x] Complete state/deadline/error handling from spec §9, preserving the last completed gate independently of the final failure and resetting all state after disconnect. *(All states including Setup and Projecting; every attempt builds fresh session, TLS, assembler, channel, encoder, and scene state.)*
- [x] Add session tests for discovery direction/order, exactly-once dispatch, required-field validation, invalid capabilities, deadlines/error categories, keepalive, repeated messages, late callbacks, and fresh-session reset. *(Late callbacks: the P1.1 JNI tests of generation-tagged events and released handles; fresh sessions: every native start builds a new session and TLS state, exercised by sequential starts in the JNI tests.)*
- [ ] Produce sanitized evidence bundles: `summary.json`, `events.jsonl`, `capabilities.json`, and `operator-notes.md`; include build/dependency revisions, setup metadata, profiles, version/TLS/auth details, capabilities, counters, monotonic durations, UTC metadata, last gate, and final reason. *(The content exists in the sanitized key=value report; the JSON bundle format is not produced.)*
- [ ] Keep raw wire/plaintext capture opt-in, bounded, and app-private; exclude it and debug-only TLS key logs from ordinary exports. Redact unnecessary vehicle/device identifiers and never export private keys. *(No raw capture or key log exists; reports redact head-unit names, serials, vehicle IDs, Bluetooth addresses, and certificate common names; keys never leave native code.)*
- [x] Pass encrypted discovery with receiver-side traces in the lab, then demonstrate that our encrypted request produces a decrypted, validated actual factory response with usable video/input capabilities (**G3**). *(Lab part passed with the Prodigy receiver's log and trace. Factory G3 passed on Ford SYNC 4.0 with 0.2.3-lab, report c2407cff: usable 800x480@60 video on channel 1 and input on channel 2, then a 30 s hold ending HOLD_COMPLETE.)*
- [ ] Verify background and screen-off lifecycle behavior with the actual USB policy. Record platform limitations explicitly; complete the five-minute projection/input version of this test in P4. *(Vehicle item; the procedure is in docs/g4-g5-procedure.md §4.)*
- [ ] Deliver the reproducible APK and lab runner, source/build/test instructions, compatibility decisions, automated results, and sanitized G0–G3 report or a specific blocker report.

Exit: **handshake POC complete only when factory G3 passes**. For G3 failures, investigate encryption policy, fragmentation, schema/version mismatch, and message direction before revisiting certificates.

## P4 — Small projection and interaction demonstration

Dependencies: P3 with usable capabilities. References: spec §§8, 10–11, 14.

Lab slice completed 17 September 2026 (app 0.3.0-lab); see [P4 lab results](docs/p4-lab-results.md).
*(Written at lab completion. Since then **G4 has passed**: 17 September, 0.3.4-lab, report
da9398a5, [Ford P4 investigation](docs/ford-p4-investigation.md). **G5 is open**, and the
vehicle lifecycle items below are unstarted.)* The procedure for the visit is [G4/G5](docs/g4-g5-procedure.md).

- [x] Extend generated types/validation for channel open, AV setup/start/stop/ack/focus, input binding/events, and sensors only if necessary. *(65 more vendored schemas, the import closure of those messages; channel-scoped ID constants checked against the generated enums.)*
- [x] Open discovered video/input channels asynchronously. Use baseline target-channel `0x0007`/`0x0008` routing with category bit `0x04` (complete encrypted flags `0x0F`), generated signed priority fields, and strict status/channel/state/deadline validation. *(Sequential requests, each with its own deadline; the event loop stays live, keepalive included.)*
- [x] Test the supplied decrypted channel-open fixtures, dynamic IDs, wrong-channel/late responses, unsupported inbound opens, and live keepalive during setup. Do not open unrelated advertised services. *(Spec §8.1 bytes `00 07 08 00 10 03` and `00 08 08 00` in the proto tests; misrouted responses counted and never accepted; inbound opens refused with INVALID_CHANNEL; only the discovered video, input, and — when asked — sensor channels are opened.)*
- [x] Select an advertised H.264 configuration supported by the phone encoder; prefer 800×480 at 30 fps only if advertised, otherwise the lowest mutually supported configuration. *(Selection at discovery, reselected among the configurations the AV setup response accepts; the encoder's capabilities are queried and reported. Whether the phone encoder can produce a chosen configuration is only reported, not yet used to reselect.)*
- [x] Implement AV setup/start/stop and focus handling with baseline complete encrypted flags `0x0B`; wait for successful open/setup and appropriate focus. Respect native HU UI and driving restrictions. *(One focus request, unsolicited indications handled. **Superseded 19 September UTC (0.4.5-lab):** a focus transition now stops and restarts the AV stream rather than merely pausing it — NATIVE/NATIVE_TRANSIENT/NONE send an `AV_CHANNEL_STOP_INDICATION` (0x8002) and bump the video session id; the next PROJECTED sends `AV_CHANNEL_START_INDICATION` (0x8001) with the new id, fresh configuration, and an IDR. A stop indication is sent on every focus loss, not only before shutdown. This is what Android Auto 17.6 itself does (`Livn;->x(ZZ)V` / `Ljdr;->x()V`, dex-read 19 September) and is why Ford SYNC 4.0's own "Android Auto" taskbar tile could not re-enter a session after the head unit took the screen — see [Ford re-attach investigation](docs/ford-focus-reattach-investigation.md) and docs/references.md, and spec.md §8.2. Re-requesting focus after a loss was tried behind a flag first and removed: unreachable in practice, since Ford restores focus in ~1.5 s on its own and real Android Auto never requests video focus on this head unit at all.)*
- [x] Configure `MediaCodec` video/avc with an input Surface and query actual encoder capabilities. Start with supported baseline profile, approximately 2 Mbps at 800×480, no B-frames where configurable, and a roughly one-second keyframe interval. *(Run on ART in the emulator only; the phone's encoder is untested.)*
- [x] Render an owned EGL/OpenGL scene directly into the encoder Surface: project name, fresh random session token, moving counter, elapsed time, four numbered buttons, last coordinates, and tap counts.
- [x] Set EGL presentation timestamps, use monotonic MediaCodec microsecond timestamps, and promptly drain/release every output buffer.
- [x] Normalize output to Annex B; send SPS/PPS before first IDR and after restart. Separate untimestamped configuration (`0x0001`) from timestamped access units (`0x0000`, uint64 big-endian microseconds, bytes); validate receiver decoding in the lab. *(Length-prefixed input converted; the controlled receiver decoded every run with libavcodec and zero errors.)*
- [x] Implement nonblocking JNI access-unit submission with documented copy/ownership, bounded queues, negotiated outstanding-unit window, and AV ack session/count validation. Keep control/input live under congestion and recover at a new IDR with decoder configuration rather than dropping arbitrary dependent frames.
- [x] Complete input binding using advertised keycodes; route channel-scoped events and map coordinates through display IDs, negotiated dimensions, video dimensions, and margins. Handle pointer identity, down/move/up/cancel, and available button events without OS input injection. *(Settled at G4, report da9398a5: Ford sends coordinates in the selected video frame's pixels, not the 1920x882 area it advertises, so `TouchSpace::Frame` is the default. A 37px frame-versus-content origin question is left for an edge press on the next drive. **Settled later** by the calibration in report 5c9ac430: the grid starts at the content rectangle's origin, so `TouchSpace::Content` is now the default — spec §8.3.)*
- [x] Test all four buttons, corners, and center mapping; reject unmatched displays and invalid dimensions. Confirm each accepted input changes the next encoded scene. *(Lab: the receiver taps each button and checks the decoded picture; corners and center are checked against the sanitized Ford geometry in the session tests. On a head unit this is part of G5.)*
- [x] If required for activation, subscribe only to advertised supported sensor types and use real restriction/night data. Keep unknown restriction state conservative and all vehicle tests stationary. *(Opt-in driving-status and night subscription, reported; no vehicle test yet. P5.2 generalised the pair into a configurable set without changing the opt-in default.)*
- [x] Obtain controlled-receiver video decoding and input-exchange evidence before factory projection testing; test focus loss, encoder failures, congestion/ack stalls, and clean resource reset. *(Prodigy handlers with libavcodec: taps, focus loss, and an acknowledgement stall, from the harness and from the app on ART.)*
- [x] Show the independently encoded scene on the factory display with fresh token and changing counter for 60 seconds; capture operator observation/photo/video plus session evidence (**G4**). *(17 September, report da9398a5: 800x480@60, 61 s of projection, every unit acknowledged, and photographs of the display. 1920x1080 followed on 18 September, report 709ffa9c.)*
- [ ] Demonstrate factory touch/button input changing the scene for five minutes; background the activity and test screen-off for five minutes with active projection/input and recorded USB policy.
- [ ] Complete five cable detach/reattach cycles with fresh sessions/tokens and no app/phone restart, stale callbacks, or FD/encoder leaks; collect evidence together with the interaction run for **G5**.
- [ ] Test detach during TLS, Stop from every state, and normal ignition/receiver interruption; verify the next connection works or document the precise firmware/platform limitation.
- [ ] Deliver G4/G5 evidence with HU observation correlated to input events and resulting scene state. Label lab-only success “lab pass, factory unproven.”

Exit: **concept-viability demonstration complete only when factory G5 passes**. If G4 fails after G3, investigate channel open/setup/focus, Annex B, parameter sets, timestamps, and flow control before changing unrelated layers.

## P5 useful shell

Scope and research for the whole package are in the [P5 plan](docs/p5-plan.md); the slices below are its §1–§5 as accepted. Each slice is separately opt-in and off by default, and none of them may change handshake behaviour (spec §13).

### P5.1 media controls and now-playing

Implemented and **lab-verified only**. This source owns no playback: it drives whatever application is already playing on the phone, and reports what that application says. The split between the two halves is an Android constraint, not a choice — see spec §5.2.

- [x] Vendor `MediaPlaybackStatusMessage`, `MediaPlaybackMetadataMessage`, and `MediaPlaybackStatusEventMessage` from the pinned open-android-auto tree, add them as proto-gen roots, and add `ids::media`. *(No pin bump and no new upstream licence audit: the three files are at `61eab61`, the pin already recorded in `protocol/upstream/references.toml`. The channel publishes no id enum, so the constants are checked against the wire capture the schema documents instead.)*
- [x] Parse the `media_info` descriptor in discovery into a `MediaInfoChoice` and report it, whether or not the channel is used. *(`MediaInfoChannel` is an empty message, so the descriptor says only which channel the service is on.)*
- [x] Add `Step::OpenMediaInfo` after input binding, opt-in and optional: a refusal or a missing response is recorded and skipped, never fatal. *(Ford advertises this channel and has never been asked to open it, so refusal is a live possibility.)*
- [x] Publish `MediaPlaybackStatus` on state change and `MediaPlaybackMetadata` on track change, both rate-limited, and both only from what the app supplied. Album art is omitted entirely. *(Unbounded `bytes` on a 16 KiB-fragment transport, and Ford's limit for it is unknown.)*
- [x] Route head-unit transport keycodes to `AppEvent::Transport` on the press only, leaving every other keycode an ordinary key, and deliver it through the existing `event_payload` path.
- [x] Dispatch transport buttons on the phone with `AudioManager.dispatchMediaKeyEvent`, which needs no permission, and read now-playing through `MediaSessionManager.getActiveSessions`, which needs the opt-in notification listener. Degrade to transport-only without that grant. *(Rewind and fast-forward seek exactly 30 s when the session can be seeked, and fall back to the media key otherwise, whose step is the playing app's choice.)*
- [x] Draw the transport state in the owned scene, so a press is visible on the factory display even while the head unit shows the projection rather than its own now-playing screen.
- [x] Report `media_open`, `media_status_sent`, `media_metadata_sent`, `media_refused`, `media_suppressed`, transport counts by action, and the last state sent; keep track text out of the report (spec §10).
- [x] Obtain controlled-receiver evidence: `hu-sim` decodes both messages, and `tools/lab-receiver.sh` with `LAB_SCRIPT=transport` presses media buttons and checks that both the now-playing channel and the decoded picture follow. *(Prodigy ships `MediaStatusChannelHandler`, so this is an independent decode.)*
- [x] Press a factory transport button and observe it change what the phone is playing; with notification access granted, observe the track and owning application on the head unit's own interface (**G6**). Record which case the run was. *(19 September, report 7f869585, notification access **granted**: six wheel presses arrived and were dispatched to Spotify with none refused, and the operator saw the track and owning application on Ford's own interface, outside the projected scene. The transport half was never conditional on audio — it also worked in runs where no audio streamed (5cb646ec, 9eb3a396) — so no dependency between the two should be inferred.)*

Exit: **G6 on the factory head unit — met** (19 September 2026, report 7f869585).

### P5.2 sensor harvest, read-only

Implemented and **lab-verified only** ([P5.2 lab results](docs/p5-2-lab-results.md)). The slice reads sensors and reports them; nothing acts on them beyond the restriction pair the test scene already drew. It exists to answer one question on the vehicle — which of the nine types Ford advertises carry real values — and that answer does not exist yet.

- [x] Replace the fixed DRIVING_STATUS/NIGHT_DATA pair with a configurable `SensorSet`: `none`, `restriction`, `all`, or a named list, always bounded by the advertisement. *(A set that intersects nothing leaves the channel closed rather than opening it to say nothing; `sensors=true` still parses as the restriction pair, so the P4 spelling keeps working. `none` was the default through P5.2; on 20 September 2026 the app default became `all`, with spec §8.1 amended to match.)*
- [x] Extract every field of all 26 `SensorEventIndication` payloads, with per-type counts and the latest value. *(The payload field number is the `SensorType` value for all 26, so the mapping is the schema's own; field 14, `steering_wheel`, is `DEAD_RECONING`.)*
- [x] Report `sensor_populated` against `sensor_silent`, so advertised is visibly not populated. *(Silent means accepted and then wordless. A refused type is in neither list: a refusal says nothing about whether the vehicle has that sensor.)*
- [x] Count a value for a type that was never subscribed to rather than dropping it, and report opaque manufacturer payloads by length only.
- [x] Keep every sensor failure non-fatal — refused channel, refused type, decode failure, missing response — unchanged from P4.
- [x] Redact latitude and longitude by default (spec §10): opt-in, app-private, and out of ordinary reports, with the case recorded either way. *(Without the opt-in the fix is still counted and its accuracy, altitude, speed, bearing and satellite counts still reported, which is what answers whether the head unit populates LOCATION at all.)*
- [x] Obtain controlled-receiver evidence: the lab receiver advertises the nine types Ford does and populates seven, and its own `receiver_sensors_populated` line matches the source's `sensor_populated` type for type. *(Prodigy's `SensorChannelHandler` models only three types, so the wider set needs a handler written for the lab; it encodes with Prodigy's protobuf classes, so the decode stays independent of our generated Rust types.)*
- [x] Project on the factory head unit with `sensors=all` and record which of Ford's nine carry values, which are accepted and silent, and which are refused. Leave position redacted. *(19 September, reports a24db7e0 and 7f869585, stationary, position redacted. All nine subscriptions accepted, 296 events, no errors: `sensor_populated=1,3,7,8,10,13,21` and `sensor_silent=6,24` — FUEL_LEVEL and TRAILER are the silent pair. **Units are a separate and still-open question**: `bearing=103553459`, `satellites_used=32` against `satellite_count=21`, and `gear=101` do not read sensibly, so P5.5 must verify a scaling before relying on one.)*

Exit: **a factory report whose `sensor_populated` line is the finding — obtained.** There is no gate here; the evidence is the input P5.5 needs, and it says the vehicle's own position source is viable.

### P5.3 audio out over USB

Implemented and **vehicle-verified**: G7 met on the third attempt (report `7f869585`;
[P5.3 lab results](docs/p5-3-lab-results.md)). **Superseded as the route for the phone's
general audio** by Bluetooth A2DP, which covers that goal with no code (spec §2.2,
[A2DP proposal](docs/a2dp-supersedes-usb-audio-proposal.md)); this slice remains the route
for audio the application owns, ships off by default, and gets no further investment. This
is the slice that cost the multi-stream refactor: the session had assumed one AV stream
throughout.

- [x] Vendor `AudioFocus{Request,Response}Message` and `AudioFocus{State,Type}Enum` from the pinned open-android-auto tree and add the two message roots. *(No pin bump and no new upstream licence audit: the files are at `61eab61`. They were at the pin but outside the import closure, since nothing imported them; `AudioConfig`, `AudioType` and the AV setup/start/stop/ack messages were already in it.)*
- [x] Hold a `StreamState` per AV stream — channel, window, outstanding, session id, focus, stop state — and route inbound AV messages by a channel → stream lookup instead of a video/input chain. *(A message id means what its channel says it means: 0x8003 is the video setup response on one channel and the audio setup response on another.)*
- [x] Generalise `MediaLink` over a codec so PCM bypasses the NAL parser: no keyframe, no codec configuration, and no drop episode, because every buffer is independently playable.
- [x] Report every advertised `AudioConfig`, and select at most one audio channel by codec and audio type rather than by number. *(The phone hard-validates the format — 48000 or 16000 Hz, 16 bit, 1 or 2 channels — so an advertised channel can still be unusable; that is a recorded discovery issue and the channel is never opened.)*
- [x] Add `Step::OpenAudio` and `Step::AudioSetup` after input binding, declaring `MEDIA_CODEC_AUDIO_PCM` in field 1 of the setup request, and keep every audio failure non-fatal.
- [x] Take audio focus on the **control channel** (0x0012/0x0013), which is independent of video focus, and send the audio start indication only once focus is granted.
- [x] Accept `0x800B AUDIO_UNDERFLOW` on an audio channel without counting it as an unhandled message, and record its body length without decoding it. *(Modern Android Auto parses no payload for it; older schemas define a required session id.)*
- [x] Keep a multi-frame message atomic on the wire, so a fragmenting video keyframe can never be interleaved with audio. *(It already was: `send_on` appends every fragment of a message before returning. An audio buffer larger than one outbound fragment is refused as well, so an audio message is always a single BULK frame.)*
- [x] Produce app-owned audio rather than capturing another application's. *(Spec §5.2 forbids the MediaProjection route, and routing another app's playback is a separate platform question. A generated tone needs no Android permission and makes the gate checkable by ear and by receiver dump at once.)*
- [x] Obtain controlled-receiver evidence: Prodigy's `AudioChannelHandler` accepts the stream, and the lab measures the received PCM against the tone the source says it is sending. *(A Goertzel at both fundamentals in both channels confirms sample rate, format, byte order, channel count and channel order together — the round-trip check video has no equivalent of. `--underflow-after` injects 0x800B, which no open-source head unit sends.)*
- [x] Record the audio focus exchange as a sequence rather than a final state, and send RELEASE only for a grant actually held (spec §8.1, §10). *(Forced by the first vehicle attempt: Ford answered with LOSS/`granted=false` and the report could not distinguish that from a loss followed by a grant.)*
- [x] Act only on the focus response's required state field, and read field 2 as `unsolicited` rather than `granted`. *(Forced by the second vehicle attempt: Ford answers `GAIN` with field 2 false — the same value it sends when refusing — so gating on the name the vendored schema gives it discarded a real grant. Confirmed against the Android Auto 17.6 and 15.7 dex, which log the value as `unsolicited` and enable their audio streams on a GAIN before reading it. The lab could not catch it: both ends of `hu-sim` were written from the same reading of the same schema, so the simulator agreed with the bug — now stated as a rule in spec §8.1.)*
- [x] Hear app-owned audio through the vehicle speakers, with focus acquired and released cleanly (**G7**). *(Met 19 September, report 7f869585: the operator heard the app's own 440/880 Hz chord from the vehicle speakers. 958 buffers and 40.8 s of PCM, **zero underflows**, zero drops, `audio_outstanding_max=5` against a window of 40, stop indication sent and focus released. Two earlier attempts were not audible — Ford refused focus on the first, and this source discarded a real grant on the second.)*

Exit: **G7 on the factory head unit — met** (19 September 2026, report 7f869585).

### P5.4 microphone

Deferred, and not scheduled. `av_input` is the head unit's microphone streamed to the phone. It belongs with the voice-assistance stage (spec §2.1), and nothing else in the shell needs it. It stays closed under spec §5.2 and §8.1, and `RECORD_AUDIO` is not declared.

### P5.5 owned map and dashboard

Implemented, lab-verified, and **vehicle-verified** ([P5.5 results](docs/p5-5-results.md)). The dashboard replaces the POC test scene as the default: an owned map in one half, four tiles in the other, and a status header. `scene=demo` keeps the old scene, because its buttons and crosses are what the G5 touch calibration aims at.

- [x] Pin VTM 0.25.0 from Maven Central in `verification-metadata.xml`: four components, with no vendoring and no JitPack. *(Reproducible builds, zero AndroidX and one ABI were re-measured; `verify-apk.sh` now pins the native set as `libaap_jni.so` + `libvtm-jni.so` and asserts that `INTERNET` is absent.)*
- [x] Render VTM off-screen into the encoder surface, with no VTM source changes, and composite the chrome over it. The chrome is re-uploaded only when it changes. *(Assets are read through the classloader, since plain JARs put them on the classpath rather than in the AssetManager, and VTM's global GL state is saved and restored around the composite.)*
- [x] Draw the dashboard chrome: clock, speed, compass, gear, restriction, satellites, and phone battery, network, signal and position source, plus ODbL attribution inside the map pane.
- [x] Take position from the vehicle's LOCATION sensor first, falling back to the phone's location. Treat Ford's scales as inferred and range-checked rather than known. *(`gear=101` is Park; bearing reads at 1e6, confirmed on a drive; the satellite field names look swapped.)*
- [x] Import operator-supplied Mapsforge `.map` files, add no network path, and record the four new permissions with their reasons (spec §5.2). *(A failed tile read is retried rather than cached empty; see `MapReadGuard`.)*
- [x] Obtain controlled-receiver evidence: the map decoded from the H.264 stream in the emulator lab. *(The first run caught four defects no reading had: the missing `assets/` prefix, a tile source closed after validation, a redraw check that never ran before the first frame, and blend state lost to VTM.)*
- [x] See the owned map render, pan and follow the vehicle's position on the factory display (**G8**). *(21 September, by operator observation on the G10 drive; the report is not yet filed.)*

Exit: **G8 on the factory head unit — met** (21 September 2026).

## P6 navigation

Scope, research and slices are in the [P6 plan](docs/p6-plan.md). Every slice below has its own record. Each integration is opt-in and non-fatal, and none of them changes handshake behaviour (spec §13).

### P6.0 navigation descriptor

- [x] Parse and report the `navigation` descriptor. *(Ford advertises channel 11, type `NEXT_TURN_IMAGE`, and a 400 ms floor, and gives no image dimensions.)*

### P6.1 a moving position in the lab

Lab-verified ([P6.1 results](docs/p6-1-results.md)).

- [x] Add a synthetic track player to the lab receiver, and a `track` script that checks the decoded map follows it by reading pixels. *(Its first run caught two defects: any drive longer than a minute was an invalid configuration, and a drive ending pointing north reported the wrong scale.)*

### P6.2 navigation channel, scripted

Lab- and vehicle-verified ([P6.2 results](docs/p6-2-results.md)).

- [x] Vendor the navigation schema (9 files at the existing pin), and open channel 11 after input binding, opt-in and non-fatal.
- [x] Request navigation focus on the control channel (13/14), send `NavigationState` and modern `0x8006`/`0x8007` no faster than the advertised interval, and release focus at the end.
- [x] Add `navigation_force` to ask for focus without an `InstrumentClusterStart`, which Ford never sends (spec §8.1). *(On by default on the connection screen since 22 September 2026, together with the navigation box; lab runs still set it explicitly.)*
- [x] Obtain controlled-receiver evidence both with and without an announced cluster. *(This caught Prodigy granting focus by echoing the request, the hand-back included, and a dashboard with no map data dying on its first frame.)*
- [x] See a scripted maneuver on Ford's own cluster, with focus acquired and released cleanly (**G9**). *(21 September, reports 1f4db62b and ad772b8a: icon, road name and distance countdown, drawn with no image bytes.)*

### P6.3 routing engine

Host- and lab-verified ([P6.3 results](docs/p6-3-results.md)).

- [x] Vendor BRouter 1.7.10 unmodified (`vendor/brouter/`), import `.rd5` tiles and the `car-vario` profile, and route on a background-priority thread of its own.
- [x] Keep the report to totals (spec §10), and keep the keep rules for BRouter's path model ready in case minification is ever turned on. *(`app/proguard-rules.pro`, 22 September; checked against a minified build.)*
- [x] Compute routes on the phone quickly enough to re-route mid-drive. *(Judged by the operator on the G10 drive: re-routing was quick enough. No compute time from the phone has been recorded. Time CPU work on the release build only; debug ran about 30× slower, P6.3a §3.5.)*

### P6.3a drop folder and seamless regions

Added at the operator's request; host- and lab-verified ([P6.3a results](docs/p6-3a-results.md)).

- [x] Pick up map and routing data from a persisted SAF folder grant, draw every installed map as one, and route across tiles. *(Whether a border between two extracts draws cleanly has not been seen, because the lab's two maps do not touch.)*

### P6.4 destinations and the route line

Host- and lab-verified ([P6.4 results](docs/p6-4-results.md)).

- [x] Accept destinations as `text/plain` and `geo:` shares (addresses via OsmAnd), typed coordinates, or a long press, and draw the route on the owned map. *(No real OsmAnd share from the Pixel has been read yet.)*

### P6.5 guidance

Host-, lab- and vehicle-verified ([P6.5 results](docs/p6-5-results.md)).

- [x] Snap the vehicle to the route, count down to the next maneuver, detect deviation, re-route, and drive the cluster. Instruction text goes to the head unit only (spec §10).
- [x] Follow a route offline on the factory display for a drive, with the map following position, correct maneuvers on the cluster, and a successful re-route after a deliberate deviation (**G10**). *(21 September, by operator observation: every turn icon made sense, and after the deliberate deviation both the cluster and the dashboard followed the new route. The report is not yet filed. The operator reported that the re-route "seemed to follow along pretty well". Whether it first routed back the way it came, as in the lab (P6.5 §4.2), was not reported.)*

Exit: **G9 and G10 on the factory head unit — met** (21 September 2026).

Standing job, from P6.2 §4.2: include a lab run **without** `LAB_MAP` in the routine set of runs before a release. That is the first-run operator's configuration, and it once hid a crash on the first frame.

## P10 weather

Scope, decisions and slices are in the [P10 plan](docs/p10-plan.md) (added 30 September 2026, when implementation began). The research behind it is [docs/weather-research.md](docs/weather-research.md). Weather is read from Breezy Weather's content provider on the phone, off by default, with no network access.

- [ ] P10.0: read Breezy's provider on the Pixel and confirm the plan's field names from a real blob. *(Not done: no phone was attached when P10.1–P10.3 were built. The fixtures come from Breezy's source.)*
- [x] P10.1–P10.3: the permission, receiver and `<queries>`; the Weather card; the provider-owner check, reader and parser; the header item and tapped view; alerts in the flex tile, dismissed and spoken. *(Host-tested and lab-verified against a stand-in Breezy. See the P10 results record.)*
- [ ] P10.4: see real weather on the Pixel, then on SYNC 4.

## Final review and stopping decisions

- [ ] Review the spec's APP01–APP06 and APP10 requirements against implementation and physical evidence; record unmet requirements and tested setup limitations explicitly.
- [ ] Publish corresponding source/build instructions and notices with any distributed binary; describe Android Auto compatibility as a goal without claiming certification or universal vehicle support.
- [ ] If ordinary-app accessory access is unavailable, stop this architecture and document whether a platform change would be required. If the HU requires unavailable proprietary source credentials, report that compatibility block without relaxing the project constraints.
- [ ] Keep hardware-dependent tasks open until physical evidence exists; do not substitute mock responses, local TLS callbacks, phone-only counters, or emulator results for factory gates.
- [ ] After P4 evidence, write a separate useful-MVP acceptance specification for the owned dashboard, navigation-engine spike, app-owned audio, optional integrations, and further lifecycle hardening. Do not include these features in POC completion criteria. *(Started: [docs/p5-plan.md](docs/p5-plan.md) and [docs/p6-plan.md](docs/p6-plan.md) scope the packages, and the P5 and P6 sections above track the accepted slices. P5.1–P5.3, P5.5 and P6.0–P6.5 are implemented; P5.4 is deferred. G6–G10 are met; the acceptance specification itself is not yet written.)*
