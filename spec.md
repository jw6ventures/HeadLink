OpenAuto Source application and feasibility POC specification

Version 0.1 · 15 September 2026 · Prepared for James

1 Purpose and decision

Build an open-source Android application that acts as the phone/source endpoint of the Android Auto Projection protocol over USB. It must run as an ordinary application in James’s GrapheneOS owner profile without Google Play Services, Google’s Android Auto application, root, a privileged system service, or an OS modification. A compatible factory head unit supplies the display and input; the application supplies its own driving interface.

The initial deliverable is a feasibility POC, followed by a small projection demonstration. The POC must establish USB transport, negotiate the protocol version, complete TLS, receive a successful authentication indication, and exchange encrypted service-discovery messages. The demonstration must display a changing test screen on the actual factory head unit and receive input that changes that screen.

Current conclusion: this is a plausible implementation path, not demonstrated compatibility. No APK has been built or vehicle test performed for this specification. Passing the gates below establishes viability for the tested phone, OS build, vehicle head unit, firmware, and wired transport. It does not establish compatibility with every car, wireless support, navigation, third-party audio, or calls.

MUST and MUST NOT identify required behavior. SHOULD identifies a default that may change with recorded evidence. “Design choice” means a requirement proposed here rather than a fact established by a protocol reference.

2 Scope and product requirements

2.1 Intended application

The product provides a privacy-respecting driving shell with a home screen, navigation, media controls, and optional phone integrations. Settings and extensive text entry live on the phone. Projection settings are specific to the head unit rather than a global assumption about screen size.

The POC and projection demonstration exclude maps, routing, and media libraries; those belong to P5 and are bounded by §12 rather than by this section. “Media controls” means controlling and describing playback that another application owns, through the public media-button and media-session APIs — never mirroring that application’s user interface, which §2.1’s exclusion of arbitrary app mirroring and §5.2’s MediaProjection prohibition both rule out.

ID

Requirement

Delivery stage

APP01

Implement the projection source role and connect to an existing wired Android Auto receiver.

POC

APP02

Operate without GMS, gearhead, Google account, root, hidden APIs, or signature permissions.

POC onward

APP03

Show connection stage, failure reason, selected compatibility profile, and an exportable local diagnostic report.

POC

APP04

Render an owned interface into an H.264 encoder and handle head-unit touch and buttons within that interface.

Projection demo

APP05

Remain connected when the phone activity backgrounds; expose a visible session notification with Stop.

POC onward

APP06

Support disconnect, ignition interruption, and reconnect without restarting the phone.

Projection demo

APP07

Provide offline-capable OSM navigation using Android’s normal location APIs.

Useful MVP

APP08

Play audio generated or owned by the application through negotiated projection audio channels.

Useful MVP

APP09

Offer integrations individually and explain the Android permission each needs.

Useful MVP

APP10

Require no network access for the handshake or projection demonstration. From P5.5 on, map and routing data are likewise never fetched by the app; the operator supplies them (§2.2, §5.2). **Amended 28 September 2026:** the app declares `INTERNET` for one opt-in integration, live traffic (P9, `docs/p9-plan.md`). It is off by default; with it off, the app makes no network connection. With it on, it contacts only the traffic provider's API host, with the operator's own key, only while a route is being followed, and stores nothing it receives beyond the response's cache lifetime. The integration is named in the APP09 list with what it sends.

POC and demo

The POC excludes maps, accounts, routing, media libraries, audio capture, microphone capture, notifications, contacts, messaging, call control, voice assistants, wireless bootstrap, arbitrary application mirroring, dashboard/HUD output, and vehicle control. These exclusions keep the feasibility test focused; they are not a claim that the later features are easy.

Voice assistance is deferred, not rejected. A later stage intends an optional voice assistant, and the exclusion above is a staging decision about microphone capture rather than a decision that the product has no assistant. When that stage is specified, the assistant MUST NOT be Google Assistant and MUST NOT require GMS, a Google account, or any Google-operated service, consistent with APP02. In its default configuration it MUST run wake-word detection, speech recognition, and command handling on-device and require no network access; any off-device recognizer, any cloud command backend, and any retention of audio or transcripts beyond the utterance MUST be individually opt-in, off by default, and named in the user-visible integration list required by APP09. Microphone capture, the `RECORD_AUDIO` permission, and any advertised receiver microphone service stay closed until that stage under §5.2 and §8.1; boundaries are §2.2 and the package is not scheduled in §12.

2.2 Integration boundaries for later stages

Navigation SHOULD start with a small renderer/routing spike before selecting an engine. MapLibre is a candidate renderer, not a complete offline routing stack. Organic Maps is a reference/candidate, not an assumed embeddable SDK. Engine choice, attribution, offline data distribution, and resource use require a separate decision. Decided (amended 22 September 2026): the renderer is VTM 0.25.0 from Maven Central over Mapsforge `.map` data (P5.5), and routing is BRouter, vendored as unmodified source over `.rd5` data because it publishes no Maven artifact (P6.3; docs/p6-plan.md §4). Both datasets are supplied by the operator — imported, or picked up from a granted drop folder — and never downloaded by the app.

Media controls and media audio are separate capabilities. An Android MediaSession controller can control an authorized session, but this does not imply access to another app’s PCM output. Notification-listener authorization or another documented route may be needed to enumerate third-party sessions. Route audio owned by this app first; treat arbitrary-player audio capture/routing as a separate platform feasibility gate.

APP08’s projection audio channel is **not** the route for the phone’s general audio output (amended 22 September 2026). Audio the application does not own reaches the vehicle over ordinary Bluetooth A2DP, which is a platform routing decision rather than a projection feature, needs no permission and no code in this project, and was shown on 19 September 2026 to survive a live USB projection session in both connection orders (docs/a2dp-coexistence-procedure.md). The projection audio channel exists for audio this application generates, and is off by default. The arbitrary-player capture gate above is therefore not pursued.

Calls initially remain an independent normal Bluetooth pairing between phone and vehicle. Do not assume that a vehicle will retain Bluetooth call behavior during custom USB projection; test coexistence later. In-call UI and call control require a separately specified permission/role model. Wireless requires a separate Bluetooth/Wi-Fi bootstrap investigation.

3 Evidence and implementation strategy

Use existing implementations as references and test peers rather than porting a Linux USB gadget daemon into an Android app.

Reference

Role and use

Important limit

AACS AAServer

Existing source-role behavior for version, TLS, discovery, channel opens, and video.

Older implementation; its blocking waits and parser shortcuts are not the desired design.

open-android-auto

Protocol schemas and audit sidecars; generate only the transitive schema subset initially needed.

Reverse-engineered material; prose and schema comments sometimes disagree.

OpenAuto Prodigy

Instrumentable receiver and modern framing/encryption reference.

Emulator success does not demonstrate factory receiver acceptance.

aasdk

Additional receiver implementation and comparison point.

Receiver role, not the application foundation.

aabox

Source-role architecture reference.

Its README targets custom AOSP on CM5 and currently includes GApps in its platform foundation; this is not evidence for an ordinary de-Googled phone app.

Reference baselines retrieved for this spec:

Repository

Commit

tomasz-grobelny/AACS

faa1cf208feb5dfe1cb9535be16daeac4f08da0c

mrmees/open-android-auto

61eab61c5f9968154ff1a80faa8c0a427b208479

mrmees/openauto-prodigy

3afc5dd8bbacb5ce68e109189b18b14b251fabef

f1xpl/aasdk

9ee5283245a630afce3a441696b9dde484ecbcd9

Pin dependencies and generated definitions to actual commits. Record every local protocol adjustment with its evidence and test fixture. A primary code reference outranks an inconsistent prose diagram, but a validated vehicle capture can reveal a different compatibility profile.

3.1 Corrections to the earlier landscape discussion

The phone is the TLS server; the head unit is the TLS client. Completing TLS alone is insufficient: the two endpoints also need a functioning encrypted application-message path.

The source-role AACS handler sends ServiceDiscoveryRequest after AUTH_COMPLETE, then receives ServiceDiscoveryResponse. Its channel handlers initiate channel opens. The pinned open-android-auto schema comments agree with the phone-to-HU open-request direction, even though its discovery and lifecycle prose diagrams reverse directions. See AACS session implementation, AACS channel implementation, and ChannelOpenRequest schema.

AUTH_COMPLETE and ping are plaintext exceptions in the pinned Prodigy encryption policy. The handshake prose incorrectly describes AUTH_COMPLETE as encrypted. The baseline here follows the receiver code; receiving an encrypted variant after TLS can be supported explicitly. Never infer encryption solely from connection stage. See Prodigy encryption policy. Update 17 September 2026: Ford SYNC 4.0 sends encrypted pings and stops pinging after a plaintext response, so pings follow the head unit's observed encryption (§7.6).

4 Architecture

Use Kotlin for Android lifecycle, USB discovery/permission, diagnostics UI, and MediaCodec. Use Rust for transport I/O, framing, TLS adaptation, message routing, protocol state, and a desktop source test harness. Use generated protobuf types through prost, with explicit validation of required fields and supported values.

Component

Owns

Must not own

ConnectionActivity

User-initiated connection, permission UI, profiles, test controls.

Blocking protocol I/O.

ProjectionService

Session lifecycle, visible notification, native handle, encoder lifecycle.

A second reader or writer for the accessory FD.

Native transport

Sole USB FD reader/writer; cancellation and outbound ordering.

Android activity state.

Framer and assembler

Byte accumulation, header validation, fragmentation, per-channel assembly.

Protobuf feature logic.

TLS adapter

One TLS session, memory BIOs, record processing, certificate policy.

Direct socket TLS or USB ownership.

Protocol controller

Version, auth, discovery, keepalive, channel state.

Dashboard rendering.

Projection renderer

App-owned scene, head-unit coordinate mapping, encoder Surface.

Injecting events into other Android applications.

Proposed repository layout:

app/                         Kotlin Android application
app/src/main/res/xml/         Accessory filters
core/                        Rust workspace
core/aap-wire/               Frames and assembly
core/aap-tls/                TLS memory transport
core/aap-session/            Source role and state machines
core/aap-proto/              Generated types and validation
core/aap-jni/                Android native bridge
tools/source-harness/        Same source engine over lab TCP
tools/capture-tools/         Trace decoding and result comparison
protocol/upstream/           Pinned definitions and licenses
protocol/fixtures/           Sanitized wire and plaintext fixtures
docs/                        Spec, implementation decisions, test results

The TCP harness is a lab transport adapter to an instrumented HU listener, not a wireless-AA implementation. Verify the receiver’s TCP path can be enabled independently. If not, use its wired USB-host mode. A normal workstation USB port cannot automatically replace the phone/source USB device role.

5 Android and GrapheneOS platform contract

5.1 Build and runtime baseline

Design choice: initial APK uses Kotlin, minSdk 33, compileSdk/targetSdk 36, and arm64-v8a. Test on the actual current GrapheneOS build regardless of whether its platform API is newer. Pin Gradle wrapper, AGP, Kotlin, JDK, Rust, NDK, prost/protoc, and OpenSSL versions in the implementation repository. Raise targetSdk before broad distribution as appropriate; a baseline here is not a store-publication policy.

The APK MUST NOT contain Play Services or gearhead dependencies. It MUST load native libraries packaged in the APK rather than execute a separately downloaded daemon. Its runtime handshake needs no remote certificate issuance, attestation, OAuth, or network service.

The initial target vehicle is James’s factory head unit, expected to be the 2023 F-150. Before vehicle testing, record the actual model, SYNC/head-unit software version, phone model, GrapheneOS build, Android API, selected USB port, cable, active user profile, and whether Bluetooth is paired. Do not infer head-unit firmware or screen resolution from the truck model.

5.2 USB accessory acquisition

In wired accessory mode the HU is USB host. Android provides the application an accessory object and bidirectional descriptor through public APIs. The application does not send AOA host control transfers, choose the phone USB VID/PID, or manage gadget/configfs. Accessory availability on this particular stock-HU/GrapheneOS combination remains the first experimental gate. See Android accessory guide.

Manifest baseline:

<uses-feature android:name="android.hardware.usb.accessory"
              android:required="true" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Inside application; standard launcher intent omitted here. -->
<activity android:name=".ConnectionActivity" android:exported="true">
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_ACCESSORY_ATTACHED" />
    </intent-filter>
    <meta-data android:name="android.hardware.usb.action.USB_ACCESSORY_ATTACHED"
               android:resource="@xml/accessory_filter" />
</activity>
<service android:name=".ProjectionService" android:exported="false"
         android:foregroundServiceType="connectedDevice" />

Accessory-filter baseline:

<resources>
    <usb-accessory manufacturer="Android" model="Android Auto" />
</resources>

That filter is a starting compatibility profile, not a guaranteed identity for every receiver. Always offer a manual path using UsbManager.accessoryList so a filter miss is distinguishable from no accessory enumeration. Inspect actual manufacturer/model/version values. Do not add a broad automatic claim of unrelated accessories merely to hide a mismatch.

Connection sequence:

User launches the app with phone unlocked, selects the profile, and taps Connect before plugging into the vehicle’s data-capable port.

Inspect attach intent and enumerate current accessories. Accept only a currently enumerated accessory, not an untrusted stale intent extra.

Obtain USB permission. For the explicit permission path, use a package-scoped custom action/PendingIntent and a receiver confined to the application; validate permission result and current accessory. Use current Android USB guidance for PendingIntent mutability and receiver registration.

Call openAccessory; null or an exception is a reported platform/acquisition failure. There is no separate android.permission.USB_PERMISSION manifest permission.

Start a connectedDevice foreground service from the visible activity after meeting the USB-permission prerequisite; promote promptly with a notification. Handle API exceptions as failures rather than adding unrelated permissions. The POC does not use MediaProjection screen capture. See foreground service type requirements.

Transfer exactly one duplicated FD to the native engine and start I/O immediately. Avoid work such as generating a key while a HU is already waiting.

Stop on user request, detach, fatal parser/TLS failure, or session deadline. Reconnection creates a new native/TLS session.

Request notification permission for normal visibility; denial must have a defined user-visible behavior rather than being confused with USB denial. POC should not request Bluetooth, location, microphone, contacts, notification-listener, accessibility, or MediaProjection privileges. INTERNET is permitted only in a lab harness flavor if needed; the wired demonstration works offline.

Media controls (P5.1) narrow the notification-listener exclusion rather than lifting it. MediaProjection and accessibility stay forbidden outright. P5.1 adds no `<uses-permission>`.

The owned dashboard (P5.5) moves the manifest baseline from three permissions to seven (amended 22 September 2026). Each addition is separately optional and degrades to a placeholder (`—`) when not granted, per APP09:

<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />

`ACCESS_NETWORK_STATE` (normal) shows the network type. The two location permissions are the position **fallback** only: the vehicle’s own LOCATION sensor is preferred and needs no permission, and `ACCESS_FINE_LOCATION` is not grantable without `ACCESS_COARSE_LOCATION` beside it, so the fallback follows whichever was granted. `READ_PHONE_STATE` is for signal bars only. There is still no `INTERNET`, and MediaProjection and accessibility stay forbidden.

Live traffic (P9, amended 28 September 2026) adds `INTERNET`, the eighth permission, under APP10 as amended. It is install-time and cannot be withheld on stock Android, so the guarantee is in the code and host-tested: no socket opens unless the operator has turned Live traffic on and saved their own HERE key. With it on, the app sends only the box around the next stretch of the route being followed, and the key, to HERE's traffic API host (`docs/p9-plan.md` §3–§4); HERE also sees the phone's IP address and when it asked. What HERE returns is held in memory only and replaced by the next response.

Weather (P10, amended 30 September 2026, `docs/p10-plan.md` §9) adds `org.breezyweather.READ_PROVIDER`, the ninth permission. It is Breezy Weather's own `dangerous` permission, which lets Breezy's content provider answer. It is asked for only when the operator turns Settings → Weather → Show weather on (off by default), and without it the header's weather item degrades to `—` per APP09. It is not a network permission: weather reads Breezy's data on the phone, sends nothing anywhere, and keeps only the latest reading in memory until the projection ends, so APP10 is unaffected. Before each read the app checks that the provider's authority belongs to the `org.breezyweather` package, since another app could declare the permission name if Breezy were absent. Two manifest additions come with it: one exported `<receiver>` for Breezy's update notifier (`org.breezyweather.ACTION_UPDATE_NOTIFIER`), guarded by no permission because Breezy sends it with none and it carries only a cue to read again; and `<queries><provider android:authorities="org.breezyweather.provider.weather"/></queries>`, which names Breezy's authority and no package. In the integration list APP09 requires: **Weather** (off by default) reads Breezy Weather's data on the phone through its content provider, with a permission you grant. No network. Nothing kept after the drive.

Navigation data and destinations add no permission. Map and routing files arrive by explicit import or through a folder the operator grants with the Storage Access Framework (P6.3a); the grant is persisted, and nothing outside the granted folder is read. The app is a share target for `text/plain` and `geo:` so that a destination can be sent from OsmAnd or any other application (P6.4); a share carries only what its sender chose to send.

Sending a head-unit transport button to whatever is playing MUST use `AudioManager.dispatchMediaKeyEvent`, which declares no permission and routes through the platform’s own media-button path — the same one a Bluetooth headset button takes. This is not event injection into another application (§4): the app holds no `INJECT_EVENTS`, runs no accessibility service, and does not choose which session receives the key.

Reading what another application is playing has exactly one route open to an ordinary app. `MediaSessionManager.getActiveSessions`, `getMediaKeyEventSession`, and `getMediaKeyEventSessionPackageName` all accept only `MEDIA_CONTENT_CONTROL` — signature|privileged, so barred by APP02 — or an enabled `NotificationListenerService`, and throw `SecurityException` otherwise. A notification-listener binding is therefore permitted for this purpose under all of the following, and for no other purpose:

The listener MUST implement no callback and MUST NOT read, retain, or report any notification. It exists solely to be named as the `ComponentName` argument to `getActiveSessions`.

The integration MUST be offered on its own and MUST explain that the grant exposes every notification on the phone, not only media ones (APP09). The grant is made and withdrawn in system Settings; the app MUST re-read it rather than cache it, and MUST NOT treat withdrawal as an error.

The feature MUST degrade rather than fail. Without the grant, transport buttons still work and the head unit is told a stopped, empty state. The source MUST NOT synthesise a playback state, a track, or an application name it was not given.

Reports MUST record which of the two cases was in force, so evidence from a drive says whether the now-playing path was exercised at all. Track text MUST stay out of ordinary reports for the same reason as the head-unit name (§10): it is the operator’s listening history.

GrapheneOS USB-C restrictions may prevent data access while locked. First test unlocked with a USB data policy that permits the connection, then record the actual settings and test screen-off behavior. A failure before accessory enumeration is not evidence of a protocol or certificate problem. See GrapheneOS USB controls.

5.3 JNI and descriptor ownership

Proposed bridge contract, expressed as interfaces rather than implementation code:

nativeStart(ownedFd: Int, config: ByteArray, callback: SessionCallback): Long
nativeStop(handle: Long)
nativeSubmitAccessUnit(handle: Long, bytes: ByteArray, ptsUs: Long, flags: Int): Int
nativeGetReport(handle: Long): ByteArray

Kotlin duplicates the accessory ParcelFileDescriptor and detaches the duplicate for nativeStart. Ownership transfers on invocation: native MUST close that FD on every success/failure path. The service retains and closes its original descriptor. Do not create additional Java stream readers on it.

Use one native event loop to serialize TLS operations and outbound messages. Poll accessory I/O and a cancellation wakeup FD/eventfd; support partial writes and EINTR. Stop signals cancellation, waits for worker completion, and then frees the handle. Do not close a worker-owned descriptor from another thread where FD reuse could race with I/O. Prove cancellation works on the actual accessory FD implementation, including removal during a blocked write.

Callbacks are immutable events tagged with a session generation. JNI workers attach/detach correctly; Kotlin posts UI state to the main thread. No panics, exceptions, stale callbacks, or arbitrary pointers cross the JNI boundary. nativeSubmitAccessUnit copies or takes explicitly documented ownership and reports bounded-queue rejection; it never blocks the MediaCodec callback thread.

6 Wire framing requirements

Use the numeric bits below; libraries name the message-category bit differently. This baseline follows the pinned receiver serializer and AACS wire values rather than relying on the words “Control” and “Specific.” See Prodigy frame types and AACS enums.

6.1 Frame layout

Offset

Width

Field

0

1 byte

Transport channel ID; 0 is session control.

1

1 byte

Flags.

2

2 bytes

Big-endian frame payload length; excludes header and FIRST extension.

4

4 bytes, FIRST only

Big-endian total assembled plaintext message size.

4 or 8

Payload length

Plain bytes or TLS ciphertext according to flags.

Bits

Values and baseline use

flags & 0x03

MIDDLE 0x00, FIRST 0x01, LAST 0x02, complete/BULK 0x03.

flags & 0x04

Cross-channel/control-category bit; 0 for normal channel-0 messages and ordinary AV/input messages; set for the baseline channel-open request/response on target channel N.

flags & 0x08

Payload encrypted through the established TLS session.

flags & 0xF0

Reserved for this implementation profile; reject with a structured reason until evidence defines them.

After decryption and assembly, the first two bytes are the big-endian message ID. Protobuf decoding starts after those two bytes. Version messages and AV payload data have their own binary formats.

For encrypted fragmented messages, fragment plaintext first, encrypt each fragment payload, then write frames. Frame length reflects ciphertext size; FIRST total reflects the full plaintext message size, including its message ID. Receive in the inverse order: parse a complete frame, decrypt its payload through the shared TLS session, then assemble plaintext fragments. Never assemble ciphertext against the plaintext total. See Prodigy messenger.

6.2 Stream and allocation rules

USB reads are not message boundaries. A read can contain a partial header, multiple frames, or a complete frame plus the start of another. Use a generously sized read buffer, initially 64 KiB, and an incremental accumulator. Do not read only four bytes from an accessory transfer and assume remaining bytes will be preserved by every platform implementation.

Design-choice bounds: outbound plaintext fragment ceiling 16 KiB subject to the pinned WireConfig/receiver constraints; inbound wire payload ceiling 65,535 bytes; control-message assembly ceiling 1 MiB; AV assembly ceiling 4 MiB; aggregate assembly reservation ceiling 8 MiB. Outbound ciphertext must still fit uint16 length. These are local resource limits, not protocol maxima. Raise only after a recorded compatibility test.

Track partial assembly per channel, with consistent category/encryption flags. Reject continuation without FIRST, impossible totals, oversized allocation, size overflow, or LAST with an incorrect final plaintext total. Treat a duplicate FIRST/BULK during an unfinished message as a diagnosed protocol violation in the initial strict profile. Expire incomplete assembly with a bounded timer. Validate minimum payload sizes before extracting IDs and reject missing required protobuf fields. Preserve unknown protobuf fields in diagnostic fixtures where the library permits it.

Maintain a single physical receive order through TLS: there is one TLS session across the multiplexed channel frames, not one session per channel. Do not process encrypted frames in parallel or reorder prepared ciphertext. Prioritize control before creating ciphertext; do not rearrange records once TLS sequence numbers have been assigned.

7 Handshake and post-handshake POC

7.1 Baseline sequence

Step

Direction

Channel and bytes before outer encryption

Expected action

Version request

HU → phone

0, 0x0001, raw version shorts

Validate and choose supported profile.

Version response

Phone → HU

0, 0x0002, version/status shorts

Send match or close after mismatch.

TLS exchange

Both; HU ClientHello first

0, 0x0003 plus TLS record bytes

Drive TLS server over memory transport.

Authentication

HU → phone

0, 0x0004, status protobuf

Require local TLS completion and status success.

Service request

Phone → HU

0, 0x0005, device-info protobuf; encrypted

Send exactly once after authentication.

Service response

HU → phone

0, 0x0006, capability protobuf; encrypted

Validate and summarize descriptors.

Keepalive

Either

0, 0x000B request / 0x000C response

Echo timestamp and sustain session.

This sequence is a baseline wired profile. Do not invent binding credentials or automatically interpret a conflicting protobuf as a successful auth message. If production firmware requires an additional binding exchange, capture and specify it as a new milestone; the current test remains incomplete.

7.2 Version profiles and complete sample packets

Default design choice: implement a legacy 1.5 response profile first, matching the source-side AACS reference for a major-1 legacy HU request. Start lab negotiation with a 1.1 request. Do not advertise 6.1 or another modern version merely because an upstream enum lists it. Implement only the request ranges and follow-on behavior validated for a profile.

Version payload fields are uint16 big-endian: ID, major, minor; response also includes status. MATCH is 0x0000; mismatch behavior must use the pinned status definition. The following are complete unfragmented baseline AA frames, not payload-only examples:

HU version request 1.1
00 03 00 06 00 01 00 01 00 01

Phone version response 1.5 with MATCH
00 03 00 08 00 02 00 01 00 05 00 00

HU successful plaintext AUTH_COMPLETE after TLS
00 03 00 04 00 04 08 00

A 1.7 profile may be added after the baseline. Requests at or above the upstream WireConfig gate need inspection of the pinned version/WireConfig definitions and matching response extensions; an eight-byte fixed version payload must not be assumed for every request. Capture the full vehicle request, including extension bytes, and record raw requested and chosen versions separately. Unknown major/extension behavior yields a controlled unsupported-profile result, not a false compatibility success. See version negotiation reference.

7.3 TLS implementation

Use a bundled, maintained OpenSSL build behind a small Rust adapter with memory BIOs. Do not link Android private platform libssl or depend on the Google TLS provider. An Android SSLEngine adapter is an alternative if native packaging proves costly, but keep the same memory-transport boundary. See OpenSSL Rust configuration API.

Baseline configuration: server role, TLS 1.2 minimum and maximum, a source/server certificate and matching private key loaded before attachment, and peer certificate requested/required. Support RSA-2048/SHA-256 credentials initially and inspect the actual HU ClientHello cipher suites and signature algorithms. Enable suitable mutually supported TLS-1.2 suites explicitly, including a documented CBC compatibility profile if the HU requires it. “AES/CBC/PKCS5Padding” in reverse-engineering notes is not a TLS cipher-suite identifier; do not translate it into a guessed single suite.

Do not lower security globally to make a connection work. A rejected old HU certificate/key or lack of a common suite produces a specific diagnostic. A separate legacy profile can relax only the demonstrated constraint and must report that choice. No TLS 1.0, SSLv3, or guessed credential substitution in the baseline.

TLS adapter behavior, original pseudocode:

When a complete 0x0003 message arrives:
    feed bytes after the message ID to the inbound TLS memory buffer
    advance the TLS server handshake until it needs more input or completes
    drain all generated TLS records
    transmit nonempty drained output inside plaintext 0x0003 AA messages
    report completion only after local verification of peer Finished

When an encrypted application frame arrives:
    feed its ciphertext into the same TLS session
    drain validated plaintext belonging to that frame
    pass the plaintext fragment and outer metadata to the assembler

Handle WANT_READ/WANT_WRITE without busy looping or recreating the session. Drain every generated record, not just one 512-byte read. A TLS message/record may span input chunks; multiple records may arrive together. Retain partial record state and corresponding frame metadata. Handshake flights are not necessarily one ClientHello-sized AA message. Never hardcode a packet count or a presumed 51-byte reply.

After the handshake, TLS encryption applies to frame payloads, not to the raw USB stream or outer AA header. Do not prepend 0x0003 to ordinary encrypted service messages. An encrypted service-request frame is channel 0, flags 0x0B, ciphertext length, ciphertext; its message ID is inside TLS.

Expose negotiated TLS version/cipher, handshake progress, peer fingerprint, certificate verification decisions, and alerts with origin and code. Log certificate summaries rather than private keys. A TLS alert from the car after our certificate is evidence of a peer rejection; a timeout alone is not proof of pinning.

7.4 Certificate compatibility experiments

The major open question is what the factory HU validates about our source certificate. Google’s permissive validation of HU certificates describes the opposite direction and cannot answer this.

Generate a source identity locally before plugging in: RSA-2048, SHA-256 signed, self-signed, non-CA leaf, suitable server-auth usage, neutral project subject, and a currently valid time window. Store the generated key in app-private storage for the prototype; exclude it from exports/backups. It is not a production identity-management design.

Hardware investigation amendment (17 September 2026 UTC): retain the generated identity
as the baseline, but allow an explicitly imported, operator-authorized source identity
and ordered intermediate chain for a controlled credential trial. Validate key matching,
validity, chain adjacency/signatures, and a local TLS self-test before atomic replacement.
Report the actual issuer, fingerprint, and chain length. Import success does not imply
factory acceptance; require successful AUTH_COMPLETE. Do not bundle third-party private
keys. See [Ford authentication investigation](docs/ford-auth-investigation.md).

Trial

Source identity

HU client-certificate policy

What success establishes

C1 lab

Locally generated source certificate.

Lab CA or pinned test HU identity.

Our TLS framing/engine works with a controlled peer.

C2 factory

Same generated identity.

Explicitly armed first-use trust for this wired test; pin HU fingerprint afterward.

This HU accepts this independently generated source identity.

C3 repeat

Same identity and HU pin.

Require pin match.

Repeat acceptance without per-session trust reset.

C4 optional

A second independently generated source identity.

Same HU pin.

Acceptance is not limited to the first generated leaf on this HU.

For C2, the user pre-arms “Trust the next wired head unit for this test” before attachment so a certificate prompt does not stall a short handshake deadline. First-use trust is scoped to the armed session; persist the accepted fingerprint afterward with a clear UI. Cryptographic handshake possession checks still run. Subsequent fingerprint changes fail unless explicitly re-armed. Do not silently implement perpetual trust-all behavior.

If generated credentials fail, inspect alert, suite overlap, source chain, key/signature support, validity clock, and engine output. Modifying our verification of the HU cannot cure the HU rejecting our server identity. Do not ship copied OEM/Google private keys. A certificate-dependent block means this HU is unproven or incompatible with the present design; it does not justify switching to a receiver-role certificate and declaring success.

7.5 Authentication and service discovery

Require both local TLS completion and an AUTH_COMPLETE status of 0. The pinned schema makes status required; do not treat missing status as zero. Baseline AUTH_COMPLETE is plaintext, but only accepted in the expected post-TLS state. Support an encrypted AUTH_COMPLETE variant only after TLS and label it in the report. A premature or unsuccessful auth indication ends the session.

Generate ServiceDiscoveryRequest from the pinned schema. Populate a neutral device name, project brand, and fresh session UUID; omit optional PNG icons initially. Do not send old AACS manufacturer/model field layouts merely because they worked in its older build. See current request schema.

Parse encrypted ServiceDiscoveryResponse and build a capability registry. Validate unique nonzero transport channel IDs fitting the outer byte field, descriptor types, video configurations, input display mapping, and known required fields. Retain unknown optional services for diagnostics and skip them. An empty but decodable response does not establish projection viability. Report whether at least one usable H.264 video configuration and input channel are present.

Transport channel IDs, descriptor service types, and logical display IDs are different things. Select IDs/configurations from the response; never hardcode video=3/input=1 in operational logic. The demonstration below uses N/M placeholders for this reason.

The POC MUST send the encrypted request and successfully decrypt/validate its actual HU response. Plaintext auth/ping traffic, a TLS library success callback, or a locally manufactured response cannot substitute for this evidence.

7.6 Keepalive and shutdown

Handle both plaintext baseline ping and explicitly observed encrypted ping after TLS. Echo the signed int64 timestamp exactly; do not replace it with phone time. Validate any response payload fields against the pinned schema. Do not honor an HU bugreport flag by exposing unrelated phone/system data. See ping request schema.

Ping encryption follows per-message evidence, not connection stage. The default `mirror` profile answers each ping with the encryption of that ping, and sends own pings with the encryption of the head unit's latest ping, encrypted after TLS until one arrives. The initial encrypted default covers the Ford projection attempts eff6933d, 54264afe, and fcd0251c, which sent no head-unit ping and ignored our plaintext requests. `plaintext` and `encrypted` profiles force one form as recorded overrides. Nothing is encrypted before TLS is established. Evidence: the Prodigy receiver pings plaintext; Ford SYNC 4.0 pings encrypted about once per second, stopped after a plaintext response (report c1265b54), and completed a 30-second hold with encrypted responses (report c2407cff). Reports record the profile and how many responses and own pings were encrypted.

Design-choice timers: version wait 5 seconds after FD open; ClientHello wait 5 seconds after successful version response; TLS/auth/discovery each 10 seconds with a 30-second absolute establishment cap. Respond to received pings promptly, aiming below 100 ms. If the HU sends no pings after discovery, initiate a timestamped ping every 2 seconds; three unanswered requests cause a keepalive failure. These are configurable development defaults, not normative OEM timing claims. Record actual latency and tune only with evidence.

Discovery-only hold is at most 30 seconds. If the HU requires channel activation sooner and closes cleanly, record G3 discovery success separately and proceed to the projection demo; do not interpret a missing-video timeout as a failed TLS handshake. Handle shutdown request/response using generated definitions; return to idle without reconnect loops or a persistent spinner.

8 Projection viability demonstration

This is the smallest useful extension beyond the handshake POC. It exists to show that the factory HU accepts independently generated video and returns input, not to build navigation early.

8.1 Channel activation and flags

Use channel IDs from discovery. Open video N and input M, then add sensor subscription only if the receiver needs it for activation/restrictions. Do not automatically open all advertised audio, Bluetooth, or microphone services.

Beyond the demonstration, a work package may open one further advertised service when something in that package uses it: `media_info` for now-playing (P5.1), audio for wired output (P5.3), and `navigation` for the vehicle’s instrument cluster (P6.2). Each MUST be individually selectable, and each MUST be optional in the sense that a refusal or a missing response is recorded and skipped rather than ending the session. `media_info` defaults to on: it adds no Android permission of its own, and without the notification grant the channel carries a stopped, empty state rather than a guess. `navigation` also defaults to on (22 September 2026): it adds no Android permission, and a head unit that refuses it affects nothing else. Audio stays off by default. Anything under §10 redaction — the head-unit position in particular — MUST stay opt-in and off by default, whatever the service it rides on. Bluetooth, microphone, and wifi stay closed. Opening a service this source does not otherwise drive, purely to imitate Android Auto’s session shape, remains a diagnostic option rather than a default.

Audio focus is a **control-channel** exchange (AUDIO_FOCUS_REQUEST 0x0012, AUDIO_FOCUS_RESPONSE 0x0013) and is independent of video focus, which is on the video channel. **The response’s required state field is the only one that says whether focus was given.** The vendored schema gives `AudioFocusResponse` a second, optional field named `granted`; that name is wrong. Ford SYNC 4.0 sends it false both when refusing and when granting, and the field is the **`unsolicited`** flag — true for a state the head unit volunteered, false for a reply to a request. It MUST NOT gate the stream; it MAY be used to tell a reply apart from a volunteered state change. More generally, a field in a reverse-engineered schema whose meaning rests on its name rather than on observed behaviour MUST be recorded rather than acted on until behaviour settles it, and a simulator written from the same reading as the source cannot settle it — both sides will be wrong together. The same holds for a **scale**: a divisor or unit is a property of the head unit and is settled by a run, not by a message, so a single value that happens to fall in range is not a behaviour (P6.1; Ford’s bearing reads at 1e6 only because a drive confirmed it). And a peer’s convenience can become a source’s bug: the lab receiver grants focus by echoing the request, so this source’s own hand-back came back as a grant-shaped response (P6.2). Act on what a head unit answers, **and** on which question it was answering. A grant MUST precede the audio channel’s start indication: a head unit’s mixer may have no route open until it has granted focus. This source MUST NOT duck its own media — a head unit asking for a duck lowers the media channel in its own mixer, so `LOSS_TRANSIENT_CAN_DUCK` is recorded and the audio keeps flowing at full amplitude. RELEASE MUST be sent when, and only when, a grant is being given up; sending it after a request that was never granted claims a state this source does not hold. A denied, lost, or unanswered focus is recorded and stops the audio without ending the session, and a head unit MAY grant focus later in the session, so a loss MUST NOT be treated as a permanent refusal unless the source has given up asking.

Audio adds a constraint the other services do not have: **the phone hard-validates the format before the channel is opened at all.** A head unit may advertise an audio channel this source cannot feed, so an advertised channel is not a usable one. At most one audio channel is driven, selected by codec and audio type rather than by channel number, and only when one of its advertised `AudioConfig` entries is a format the phone can produce — sample rate 48000 or 16000, bit depth 16, one or two channels. A channel that fails that test is recorded as a discovery issue and never opened; nothing is negotiated down and no format is guessed. “Optional” extends to every stage of the audio handshake and not only to the channel open: a refused open, a refused AV setup, a denied or unanswered audio focus request, a closed channel, and a stalled acknowledgement window each stop the audio and leave video and input running.

Navigation focus (P6.2, amended 22 September 2026) is the third focus type, and like audio focus it is a **control-channel** exchange: NAVIGATION_FOCUS_REQUEST 13 and NAVIGATION_FOCUS_RESPONSE 14, not a message on the navigation channel itself. Once the navigation channel is open, the source asks for focus; `0x8001 InstrumentClusterStart` is treated as confirmation when it arrives, and MUST NOT be a precondition. Ford SYNC 4.0 never sends it, in any recorded run, and grants focus within 100 ms of being asked, so a source that waits for the announcement sends nothing to the only cluster this project has. The app’s `navigation_force` option implements this, and on the connection screen it and the navigation box are both **on by default** (22 September 2026); lab runs still set it explicitly. A report MUST distinguish *no cluster was announced* from *the cluster was announced and displayed nothing*. `NavigationFocusResponse` has no `unsolicited` flag, and Ford answers one request with two `PROJECTED` responses; neither is treated as an echo. Updates on the channel MUST NOT be sent faster than the advertised `minimum_interval_ms`. Focus is released at the end of the session, and the channel stays open across destinations.

Baseline source flow:

Operation

Direction

Outer channel

Complete-frame flags

ChannelOpenRequest 0x0007

Phone → HU

Target N or M

0x0F

ChannelOpenResponse 0x0008

HU → phone

Same target

0x0F

AV setup/start/data

Phone → HU

Video N

0x0B

AV response/ack/focus

HU → phone

Video N

0x0B

Input bind and events

Both

Input M

0x0B

Request fields: priority is required sint32, field 1, encoded with zigzag; channel_id is required int32, field 2. For priority 0 and target 3, decrypted message bytes are 00 07 08 00 10 03; successful response bytes are 00 08 08 00. The outer encrypted lengths depend on TLS and cannot be fixed hexadecimal fixtures.

AACS and the pinned receiver exchange channel-open request/response on the target transport channel with bit 0x04 set. This contradicts lifecycle prose claiming all opens occur on channel 0. A future factory variation must be an explicit profile with a capture and matching transaction routing; do not spam both forms or accept any status message as the pending response. See receiver channel response implementation.

Wait asynchronously for each open response; keep the reader/event loop alive. Validate status, target channel, state, and deadline. Unknown/unsupported inbound opens are rejected with the appropriate protocol status; never claim a service works merely to progress.

Implemented sequence (P4 lab slice, design choice): open video N, AV setup, open input M, input binding, then — only when sensors are enabled — open the sensor channel and send one SensorRequest per configured type, then one video focus request, then the start indication. Each step has its own deadline, so a report names the step a head unit did not answer (SETUP_TIMEOUT with `projection_step`). A ChannelOpenResponse is accepted only on the target channel with the 0x04 bit; one on channel 0, one without the bit, or one with no open pending is counted (`projection_open_responses_misrouted`, `projection_open_responses_unexpected`) and ignored. Inbound opens are refused with INVALID_CHANNEL (-5), as the pinned receiver does.

Sensors default to every advertised type: a stationary vehicle run is expensive evidence, and which of the advertised types a head unit actually populates is only learned by asking for all of them. The set is a configuration choice — the DRIVING_STATUS and NIGHT_DATA restriction pair, every advertised type, a named list, or none — and is always bounded by what the head unit advertises; head-unit position stays opt-in and off by default under §10 whichever set is chosen; a set that intersects nothing leaves the channel closed rather than opening it to say nothing. Every sensor failure stays non-fatal: a refused channel, a refused type, a decode failure and a missing response are each recorded and skipped. The restriction pair's values are shown in the test scene and reported, and an unknown restriction state does not change the scene, which is a stationary test pattern either way.

8.2 Video protocol and encoding

Generate AV setup/start/ack and video-focus messages from the pinned schemas. The main baseline IDs are setup request 0x8000, start 0x8001, stop 0x8002, setup response 0x8003, ack 0x8004, focus request 0x8007, and focus indication 0x8008. Select only an advertised H.264 configuration; prefer 800×480 at 30 fps if advertised. Otherwise select the lowest supported advertised configuration that the phone encoder can produce. These are not promises about the F-150 display.

Only start after channel-open/setup success and appropriate projected focus. Request focus using the generated schema; handle unsolicited focus indications. Never fake parked status, disable receiver restrictions, or seize the screen repeatedly when the HU is showing native UI. See video channel reference.

MediaCodec generates video/avc from an input Surface. Render directly with EGL/OpenGL into this Surface; do not try to draw ordinary Compose Views directly onto an encoder Surface. The POC renderer is a simple owned scene; choose a production UI renderer after this works.

Design-choice encoder starting settings: supported H.264 baseline profile, negotiated dimensions/framerate, roughly 2 Mbps for 800×480, no B-frames where configurable, approximately one-second keyframe interval. Query encoder capability and report selected implementation. Set EGL presentation timestamps and pass MediaCodec presentationTimeUs as monotonic stream timestamps. Drain and release every output buffer promptly.

Normalize encoder output to the receiver-required Annex-B byte stream. Send SPS/PPS before the first IDR and resend decoder configuration on restart. Distinguish codec-configuration buffers from timestamped access units, and do not assume Android’s output is already Annex B. Timestamped AV data uses ID 0x0000, uint64 big-endian timestamp in microseconds, then access-unit bytes; untimestamped configuration uses ID 0x0001 with the codec bytes. Validate against the instrumented receiver before factory testing. See AACS video source implementation.

Honor setup-response flow control and AV ack session/count fields. Only timestamped video frames consume acknowledgement credits; untimestamped SPS/PPS configuration does not. Bound outstanding frames to the negotiated window; do not treat ack as merely a log message. Queue no more than a small number of complete access units. If congested, stop accepting encoder output or recover at a new IDR with decoder configuration; avoid dropping arbitrary dependent frames and sending a broken stream. Control traffic and input state must continue during congestion.

Implemented policy (P4, design choices): the acknowledgement count is `receive_timestamps` when present and `ack_count` otherwise, session ids are compared modulo 256, a missing `max_unacked` means a window of one (reported as defaulted), and a full window with no acknowledgement for 5 seconds ends the session with FLOW_CONTROL_TIMEOUT. At most four access units wait for the window, and media is taken from that queue only while pending output is below 256 KiB, so control and input never wait behind video. A frame that finds the queue full is dropped along with every later frame until the next IDR, which is preceded by the codec configuration; the encoder is asked for that IDR. Focus is requested once (PROJECTED, USER_SELECTION) and never re-requested; an unsolicited PROJECTED indication starts the stream without a request. **A focus transition stops and restarts the stream rather than pausing it.** NATIVE, NATIVE_TRANSIENT, or NONE send an `AV_CHANNEL_STOP_INDICATION` (0x8002), clear the queue, and increment the video session id; the next PROJECTED sends an `AV_CHANNEL_START_INDICATION` (0x8001) carrying that **new** session id, followed by configuration and a fresh IDR. The head unit must see the stream stop: a source that only pauses leaves Ford SYNC 4.0 believing the projection is still foreground, so its own "Android Auto" control becomes a no-op and the session can be re-entered only by reconnecting, while every media unit goes on being acknowledged — the same silent-withholding shape as the 0x800A reply-body bug, and invisible in the acknowledgements. Android Auto 17.6 does exactly this: `Livn;->x(ZZ)V` (`doHandleVideoFocusLoss`) calls `Ljdr;->x()V`, which bumps the session id and sends 0x8002 through `Ljdr;->w`, and the regain sends 0x8001 with the new id through `Ljdr;->v` (dex, 19 September 2026; see docs/ford-focus-reattach-investigation.md). The per-connection flow controller and its acknowledgement window survive the transition — only the stream session restarts. The pinned receiver signals focus loss with NONE, so anything that is not PROJECTED or PROJECTED_NO_INPUT_FOCUS counts as lost.

Test scene: project name, fresh random session token, changing frame counter, elapsed time, four large numbered buttons, last input coordinates, and tap count. A recording/cached display cannot pass the fresh-token/counter check.

8.3 Input and sensor minimum

Open input M and complete its binding/handshake using advertised keycodes and generated input messages. Baseline input event 0x8001, binding request 0x8002, response 0x8003 are scoped to the input channel, not global message IDs. See input reference.

Map touch coordinates through the negotiated input dimensions, logical display ID, video dimensions, and margins. Handle down/move/up, pointer identity, cancellation, and available button events. Update only our own scene; no OS-level input injection. Reject events for unmatched displays or nonsensical dimensions. A tap increments the intended numbered button’s count and appears in the next encoded scene. Test every button, a corner, and a center point to reveal scale/inset errors.
Mapping (P4, settled on the vehicle in two steps). **Scale, settled at G4:** touch coordinates arrive in the selected video configuration's **frame** pixels — one touch unit per frame pixel — and the advertised touch area is not a scale factor. The earlier baseline stretched that advertised area onto the content rectangle, as the pinned receiver and the open-android-auto head-unit notes describe, but Ford SYNC 4.0 advertises 1920x882 while sending coordinates inside the selected 800x480 frame: report da9398a5 has a press on button 4 arriving as 525,364 and being mapped to 235,198, and all 21 taps of that drive landing on button 1 because the stretch compresses the whole picture into its top-left corner. Ford's advertised size describes its native 1080p layout (full frame width by content height) and is not restated per configuration. **Origin, settled by the calibration in report 5c9ac430:** that grid starts at the **content rectangle's** origin, not the frame's, so `x_video = x + content.x` and `y_video = y + content.y`. The scene drew a cross at four known frame pixels and the head unit reported all four a uniform 39 ± 6 px to the left at unit scale (0.994) — the 37px left inset, with no sign flip between the left and right columns that a grid stretched over the content rectangle would show. The grid keeps the **frame's** dimensions while taking the content rectangle's origin (Ford sends x up to 791 on an 800-wide frame), so its far end addresses pixels past the frame; those clamp onto the frame's last pixel. This is `TouchSpace::Content`, the default, and it is the identity for a head unit that advertises no margins, the spec-compliant case; `ProjectionConfig::touch_space` still selects `Frame` or `Advertised` for a unit that needs them. Coordinates outside the touch space, unknown actions, duplicate pointer ids, and events before the stream starts or while focus is lost are counted and dropped, never fatal.

If sensor setup is needed, open its advertised channel, subscribe only to supported types, and accept real driving-status/night data. Unknown driving restriction state must keep the demonstration conservative; all vehicle tests take place stationary. Sensor support is not a prerequisite for declaring G2 TLS success, but may be required for G4 display/input success on a particular HU.

Advertised is not populated. A report MUST therefore separate, per sensor type, the types that carried values from the types whose subscription was accepted and which then sent nothing; a type the head unit refused, errored on, or never answered belongs to neither list, because a refusal is not evidence about the vehicle's sensors. A value for a type never subscribed to MUST be recorded rather than dropped. Payload fields with no schema here to read them by — manufacturer diagnostics and the raw energy-model messages — are reported by length only, under the §10 rule for unexamined bodies.

9 State machine and failures

State

Success transition

Principal failure evidence

Idle / Armed

Accessory enumerated → Permission

No accessory; filter mismatch; USB data blocked.

Permission

Grant and FD open → Version

Denial, null descriptor, foreground-service exception.

Version

Supported response sent → TLS

Truncated frame, unsupported version/extension.

TLS

Verified Finished exchange → AwaitAuth

Alert, unsupported suite, certificate rejection, record error.

AwaitAuth

Success indication → Discovery

Missing/failed status, unexpected binding, timeout.

Discovery

Encrypted request and validated response → CapabilitiesReady

Decryption, schema, descriptor, or response timeout failure.

CapabilitiesReady

Open/setup/focus → Projecting

Missing configuration, rejected open, setup/focus failure.

Projecting

Input updates scene; remain live

Encoder error, flow-control stall, keepalive failure.

Stopping

Worker and resources closed → Idle

Cancellation failure or leaked descriptor.

Detach/Stop can reach Stopping from any state. Every attempt has a fresh generation, TLS engine, message assembler, encoder state, and session UUID. Repeat protocol messages do not trigger duplicate initialization. Discard late callbacks from previous attempts. Report last successful gate independently of final failure.

Error categories: NO_ACCESSORY, USB_PERMISSION_DENIED, USB_OPEN_FAILED, FGS_START_FAILED, IO_EOF, IO_ERROR, FRAME_INVALID, VERSION_UNSUPPORTED, TLS_ALERT_PEER, TLS_ALERT_LOCAL, TLS_NO_COMMON_SUITE, TLS_CERT_POLICY, TLS_TIMEOUT, AUTH_FAILED, BINDING_UNSUPPORTED, DISCOVERY_INVALID, CHANNEL_REJECTED, AV_SETUP_FAILED, VIDEO_FOCUS_DENIED, ENCODER_FAILED, FLOW_CONTROL_TIMEOUT, KEEPALIVE_TIMEOUT, and CANCELLED. Each includes state, timestamp, reason, and underlying API/TLS code where available.

10 Observability and test evidence

Each session report contains app/build/dependency revisions; phone/OS/HU test metadata; accessory identity; requested/responded protocol versions; profile; TLS suite and certificate fingerprints; auth status; discovery descriptor summary; selected channel/display/config IDs; packet/byte counters; timing; keepalive statistics; encoder/access-unit/ack statistics; input counts; last completed gate; and final reason.

Use monotonic timestamps for durations and UTC only for report metadata. Default logs contain lengths, IDs, state changes, and nonsecret summaries. Raw frames and plaintext traces are opt-in, bounded, app-private, and excluded from ordinary shared reports. Debug-only TLS key logging is off by default and is never included in a standard export. Redact vehicle serials, MACs, names, and other unnecessary identifiers. Never export source private keys.

Audio evidence carries nothing vehicle-identifying and needs no redaction, but it does need reading correctly. `audio_underflows` counts head-unit reports that its mixer ran dry; it is evidence about **this source’s pacing**, not about the vehicle, and it is the only signal in the AV path that distinguishes a stream the receiver could consume from one it could not — video has no equivalent, since a head unit acknowledges every unit whether or not it displays it. The audio focus exchange MUST be reported as a sequence rather than as a final state: a head unit may send an unsolicited state before or after answering a request, so “what the last response said” cannot distinguish a denial from a loss followed by a grant.

Head-unit latitude and longitude are treated the same way as raw frames: opt-in, app-private, and excluded from ordinary shared reports, because a position identifies the vehicle. Without that opt-in a report still records whether a position arrived and its accuracy, altitude, speed, bearing and satellite counts, so the presence and quality of the sensor are evidence without the position being disclosed. Reports MUST record which of the two cases was in force, for the same reason as the now-playing cases (§5.2). That opt-in, `sensor_location`, governs **reports** and not the application’s own use: the map cannot follow a redacted fix, so latitude and longitude reach the app whatever it says, and stay app-private either way (P5.5).

A destination is at least as much the operator’s business as a track title or a position (amended 22 September 2026). Navigation evidence therefore carries no destination, no route geometry, no road names and no maneuver instruction text by default, on the same footing as `sensor_location`. This covers every producer: the navigation channel’s `[navigation]` lines (P6.2), `[routing]`, which records totals — distance, time, compute duration, point and maneuver counts — and which 5° data tiles are installed, as the map line names its map file, but no coordinates (P6.3), destination entry, which records only the source of a destination (share, typed, long press) and never its label or coordinates (P6.4), and guidance, whose instruction text goes to the head unit and to no report or event log (P6.5).

Proposed per-session evidence bundle in the implementation project:

summary.json                 Gates, test metadata, selected profiles
events.jsonl                 State and message metadata
capabilities.json            Sanitized parsed service response
wire.bin                     Optional ordered raw data plus direction index
plaintext.jsonl              Optional decoded diagnostic data
operator-notes.md            What appeared on HU and physical test steps

A G4/G5 result additionally includes an operator observation/photo/video showing the fresh session token and counter, plus input events and resulting scene state. Counter output from the phone alone is insufficient evidence that the HU decoded it.

11 Acceptance gates and test plan

11.1 Gates

Gate

Required evidence

Interpretation

G0 build

Sideloaded APK launches on GrapheneOS; no GMS/gearhead dependency; native engine loads.

Packaging only.

G1 transport

Actual factory accessory opens; HU version request arrives; valid response is transmitted.

Public USB access works for this setup.

G2 security

Local Finished verified, peer accepted our identity, auth status success received.

Basic factory handshake works.

G3 encrypted services

Our encrypted request produces a decrypted, validated actual HU service response with usable video/input capabilities.

Handshake POC passes; encrypted application path works.

G4 display

Independently encoded scene appears on factory display with fresh token and moving counter for 60 seconds.

Wired video projection works.

G5 interaction

Factory touchscreen/button inputs change projected scene; runs 5 minutes and reconnects successfully 5 times.

Concept viable for a basic owned wired driving shell on this setup.

G6 media

A factory transport button changes what the phone is playing, and — with notification access granted — the track and owning application appear on the head unit’s own interface.

Bidirectional media integration works. Without notification access the first half alone is the pass condition, and the report says which case it was.

G7 audio

App-owned audio is audible through the vehicle speakers over USB, with audio focus acquired and released cleanly.

Wired audio projection works. A head unit that refuses the audio channel, its AV setup, or audio focus is a recorded outcome rather than a failed gate, provided the refusal is in the report, `audio_focus_released` shows no grant was left dangling, and the projected picture was unaffected — the same degraded-pass shape as G6. A refusal leaves the gate unmet rather than passed: it says the integration is unavailable on this head unit, not that wired audio works. G7 is not, and never was, a gate about the phone’s general audio: that is served by Bluetooth A2DP (§2.2). G7 says the wired audio transport works for audio this application owns.

G8 map

An owned map renders, pans and follows vehicle position on the factory display for 60 seconds.

The useful shell is viable. A head unit that projects the dashboard while the map pane reports “no map data” is a recorded outcome rather than a failed gate — the same degraded-pass shape as G6 — but it leaves the gate unmet.

G9 navigation channel

A maneuver sequence from this source appears on the head unit’s own cluster or display, with navigation focus acquired and released cleanly.

Turn-by-turn to the vehicle’s own instruments works. A head unit that opens the channel, accepts the messages and displays nothing is a recorded outcome, provided the silence is in the report and focus was not left dangling; the report must say whether a cluster was announced at all (§8.1). A refusal leaves the gate unmet rather than passed.

G10 navigation

A route computed offline on the phone is followed on the factory display for a drive, with the map following position, correct maneuvers on the cluster, and at least one successful re-route after a deliberate deviation.

Offline navigation works on this setup — APP07 demonstrated rather than argued.

Results on the factory head unit (Ford SYNC 4.0, Pixel 9, GrapheneOS), recorded 22 September 2026: G0 and G1 on the saved reports of 17 September; G2 report 3812ebff; G3 report c2407cff; G4 report da9398a5; G6 and G7 report 7f869585; G9 reports 1f4db62b and ad772b8a; **G8 and G10 by operator observation on a drive on 21 September 2026** — a route followed on the projected map and on the cluster, every turn icon on the cluster making sense for the route, and a deliberate deviation re-routed quickly enough, with both displays following the new route — with that drive’s report not yet filed in the repository. **G5 is open**: the five-minute run and five reconnections have not been done.

G3 is the required handshake POC delivery gate. G5 is the concept-viability demonstration gate. A lab-only G5 is “lab pass, factory unproven.” An implementation dependent on copied proprietary source credentials, a Google runtime, or OS privileges fails the stated concept constraints even if its TLS connection succeeds.

11.2 Meaningful automated checks

Wire tests MUST cover the complete sample packets above; byte-at-a-time headers; coalesced frames; FIRST/MIDDLE/LAST; interleaved channels; encrypted fragmentation with plaintext totals; invalid lengths/flags/totals; allocation bounds; and partial writes. Property/fuzz testing targets the parser and assembler, not an invented broad UI test suite.

TLS tests MUST use independently generated server/client identities with TLS records inside AA messages. Cover missing client certificate, pin mismatch, wrong TLS version, lack of common suite, fragmented TLS input, multiple-record output, encrypted protobuf round trip, peer alerts, and plaintext auth/ping exceptions. Tests compare known fixtures or an independent receiver, not only two copies of our encoder/decoder.

Session tests MUST verify request-before-response discovery; required-field validation; single request dispatch; timeout/failure categorization; dynamic channel IDs; target-channel open response routing; live keepalive during setup; and reset of all state after disconnect. A controlled receiver MUST observe video decode and input exchange before factory testing.

11.3 Physical test matrix

Test

Conditions

Pass evidence

Controlled receiver

Same source core over supported lab TCP or wired mode; generated credentials.

G3, then G5, with receiver-side traces.

Factory first connection

Actual phone owner profile, no Google runtime, unlocked, stationary vehicle, known data cable/port.

G1–G3 and certificate trial C2.

Factory projection

Same setup plus video/input.

G4/G5 physical observation and source logs.

Background/screen off

Background app then screen off after connection; record USB policy.

Session and input remain active for 5 minutes, or precise platform limitation documented.

Reconnect

Five cable removal/reattach cycles.

Five fresh sessions, tokens, no app/phone restart, no FD/encoder leaks.

Mid-handshake removal

Remove at version and during TLS.

Clean return to idle; next attempt works.

Permission denied

Decline USB permission then retry explicitly.

Clear error; no crash or automatic prompt loop.

Ignition/receiver interruption

Stationary vehicle; power-cycle receiver through normal controls.

Clean stop and successful next connection, or recorded firmware limitation.

Use wireless ADB only if available/authorized by the phone configuration, or app-local export. Do not assume USB ADB is accessible through the same car cable. No official Android Auto installation in James’s owner profile is required as a test dependency. A separate known-working phone can establish port/cable/receiver baseline if available; inability to obtain that baseline does not justify installing Google services into the target profile.

12 Work packages and stopping decisions

Package

Concrete output

Exit criterion

P0 reference and harness

Pinned schemas/licenses, generated types, build toolchain, source-core lab runner.

Reproducible local build and independently checked frame fixtures.

P1 Android acquisition

Sideloadable permission/foreground-service/native-FD APK, cancellation, diagnostic export.

G1 on factory HU or a precisely classified transport block.

P2 TLS and authentication

Memory-BIO TLS server, generated identity, certificate profiles, auth handler.

G2 in lab and factory; record actual certificate trial.

P3 encrypted control

Discovery, keepalive, required validation, G0–G3 report.

G3 on factory HU; handshake POC complete.

P4 projection demo

Dynamic channel opens, encoder, focus, ack window, test scene, input.

G5 on factory HU.

P5.1 media controls

`media_info` channel, now-playing published from the phone’s current media session, head-unit transport buttons dispatched to it.

G6 on factory HU.

P5.2 sensor harvest

Configurable sensor set, per-type reporting, position redacted and opt-in.

Which of the advertised sensors carry real values, recorded from the factory HU.

P5.3 audio out over USB

Multi-stream session refactor; one advertised PCM MEDIA channel, chosen only when the phone can produce its format; audio focus on the control channel with RELEASE on any grant given up; AUDIO_UNDERFLOW accepted and reported; every audio failure non-fatal.

G7 on factory HU. **Complete, and superseded as the route for phone audio** (§2.2): no further investment is planned, the capability ships off by default, and A2DP covers the goal P5.3 was reached for.

P5.4 microphone

`av_input`, head-unit microphone to the phone.

Deferred with voice assistance (§2.1); not scheduled.

P5.5 owned map and dashboard

Offline vector renderer into the encoder surface, owned dashboard with vehicle and phone status, on-screen OSM attribution.

G8 on factory HU.

P6.0–P6.2 navigation channel

Navigation descriptor parsed and reported; synthetic track player for the lab; the `navigation` channel opened opt-in and non-fatal, navigation focus on the control channel, maneuvers sent no faster than the advertised interval.

G9 on factory HU.

P6.3–P6.5 offline navigation

Vendored BRouter over imported or drop-folder `.rd5` data, routed on its own thread; destinations by share, typed coordinates or long press; the route on the owned map; a guidance loop that follows the route, re-routes on deviation and feeds the cluster.

G10 on factory HU.

P5 shell remainder

Lifecycle hardening.

Separate MVP acceptance spec after P4 evidence.

Do not assign a calendar estimate until the first vehicle accessory trace and native build spike exist. The main uncertainty is receiver/platform acceptance, not dashboard implementation.

If G1 fails, isolate cable/port, accessory enumeration, filter/permission, user-profile/USB policy, and actual receiver mode. If AOA is inaccessible to an ordinary app, stop this architecture and document whether a platform change would be needed. If G2 fails, distinguish TLS mechanics from identity acceptance; a factory credential requirement may block this head unit. If G3 fails, inspect encryption policy, fragmentation, schema/version mismatch, and message direction before revisiting certificates. If G4 fails after G3, investigate open/setup/focus, Annex-B format, parameter sets, timestamping, and flow control. Do not redesign unrelated layers without evidence.

13 Release and open-source boundaries

Recommended project license is GPL-3.0-only for a straightforward build that may incorporate GPLv3-only AACS material and GPL-3.0-or-later protocol definitions. Record the exact license of every included dependency, generated schema input, fixture, asset, and reference; source-role reference repositories without an explicit license are not code-copy sources. Preserve notices and publish corresponding source/build instructions for distributed binaries. This is an implementation choice based on the inspected notices, not a legal conclusion about all reverse engineering.

Do not include extracted OEM/Google private keys or proprietary runtime libraries. The project uses “Android Auto compatible” as a technical goal and makes no certification claim. Keep protocol compatibility modules separate from navigation/media integrations so changes in those integrations cannot silently alter handshake behavior. From P5 this is load-bearing rather than advisory: a media or map integration lives behind a hand-off type the session owns (`NowPlayingLink`, `MediaLink`) and reaches the wire only through it, so a platform API an integration uses cannot appear in the handshake path.

Third-party runtime code (amended 22 September 2026). Through P5.3 the APK carried no third-party runtime dependency; P5.5 added VTM, and P6.3 compiled vendored BRouter source into the APK. What replaced that property, and MUST keep holding, is: offline builds stay reproducible (two clean builds give byte-identical release APKs); the ABI count stays one, `arm64-v8a`; AndroidX stays at zero; and `tools/verify-apk.sh` pins the native library set (`libaap_jni.so`, `libvtm-jni.so`) and asserts that `INTERNET` is absent (since 28 September 2026, that it is present, for P9's opt-in live traffic under APP10 as amended; still no other network-related addition), and since 30 September 2026 pins `org.breezyweather.READ_PROVIDER` in the permission set, Breezy's provider authority as the one `<queries>` provider, and the update notifier's receiver (P10). **Vendored third-party source is held unmodified**, so its provenance stays a diff against a recorded pin rather than a merge; a file of this project’s own placed in a vendored package, compiled from the app tree (`btools.router.VoiceHintReader`), is not a modification of the vendored tree. Release builds are not minified; if that changes, `app/proguard-rules.pro` already keeps what is looked up by name — BRouter’s path models, which a routing profile names as a class, and the JNI bridge’s natives and callback — and was checked against a minified build on 22 September 2026.

14 Implementation handoff checklist

The engineer receiving this spec should deliver a reproducible APK and lab runner, source and build instructions, protocol/profile decisions with reference commits, meaningful wire/TLS/session tests, a sanitized G0–G3 result, and a G4/G5 vehicle result or a specific blocking report. This specification itself does not claim those deliverables already exist.

Before writing UI features, answer in order: does the factory HU expose an accessory to this app; does it accept our independently generated source identity; can we exchange encrypted service messages; does it decode our video; and does its input operate our projected scene? Only the completed evidence permits marking the corresponding gate as passed.

15 Source index

Primary references below were inspected on 15 September 2026. Repository implementation links are pinned where they define the baseline; general platform documentation is live.

Android USB accessory guide and UsbManager API.

Android connected device foreground services.

GrapheneOS usage and USB controls and official Android Auto integration. Official integration is a different GMS-dependent approach, not this POC’s dependency.

AACS README, source session, channel opens, and video source.

open-android-auto schema corpus, version/TLS notes, discovery notes, and lifecycle notes. Use the corrections in section 3 before implementing these prose sequences.

Prodigy messenger, frame parser, serializer, assembler, and encryption policy.

OpenSSL Rust TLS configuration and Android SSLEngine.

aabox source-daemon concept and custom AOSP target.
