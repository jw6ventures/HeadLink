Kotlin ordinary-app shell (platform APIs only; no AndroidX or Google libraries).
`ConnectionActivity` (Connect/Stop, version profile, stop-after stage, TLS profile,
armed first-use trust, pins and identity replacement, accessory enumeration and filter
diagnostics, USB and notification permission, report view and export, third-party
notices), `ProjectionService` (connectedDevice foreground service, accessory open,
owned-descriptor handoff to native, Stop notification, pinning after a first-use
handshake), `SessionController` (main-thread attempt and identity state), the projection pipeline
(`VideoEncoder` MediaCodec H.264 from an input Surface, `SceneRenderer` EGL/GLES into it,
`DemoScene`/`SceneLayout` the pure-JVM scene model, `ProjectionPipeline` tying them to the
native session),
`SourceApplication`/`ReportPersistence`/`IdentityWork` (saved reports, JVM crash
capture, identity preparation off the main thread), and the pure-JVM `NativeBridge`,
`ReportStore`, and `TrustStore` (tested by tools/jni-host-test). Projection has run on ART only in the emulator lab
(`tools/emulator-lab.sh`), never on a device or a head unit. Build with
`tools/android-build.sh`; see ../README.md.
