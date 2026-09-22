# G0–G3 setup record and device test procedure

For the projection demonstration (G4 and G5) continue with the
[G4/G5 procedure](g4-g5-procedure.md), which builds on this record.

Prepared in P1.1 (16 September 2026) and extended before the first hardware test for
TLS, authentication, and encrypted discovery (app 0.2.0-lab). **Nothing here has been
run on the phone or in the vehicle at the time this procedure was prepared.** Initial
hardware results and the 0.2.1-lab credential trial are now recorded in
[Ford authentication investigation](ford-auth-investigation.md). Fill in one copy per test session (for example
`docs/evidence/YYYY-MM-DD-g1/setup.md`, sanitized) and attach the app's exported report
for each attempt. Spec references: §5.1 (record before testing), §5.2, §7.4 (certificate
trials), §10, §11.

Do not infer head-unit firmware or screen resolution from the truck model. Record what
the vehicle and phone actually show. Redact VINs, serials, MAC addresses, and names (the
app already reports head-unit name, car serial, vehicle ID, Bluetooth address, and
certificate common names only as lengths).

## 1. Setup record

| Field | Value | How obtained |
| --- | --- | --- |
| Date, local time zone | | |
| Operator | | |
| Vehicle make/model/year (expected 2023 F-150; unverified) | | Vehicle documentation |
| Head unit / SYNC generation | | Vehicle settings screen |
| Head-unit software version | | Vehicle settings → software/about; compare with report `hu_sw_version` |
| USB port used (location, label, data-capable?) | | Physical inspection |
| Cable (make, length, USB-C↔A/C, data-rated?) | | |
| Phone model | | App report `[device] model` |
| GrapheneOS build number | | Settings → About; app report `os_incremental` |
| Android release / API level | | App report `android_release`, `sdk_int` |
| Security patch level | | App report `security_patch` |
| Active user profile (owner?) | | App report `owner_user=true` means owner |
| GrapheneOS USB-C port setting | | Settings → Security → USB-C port |
| Phone unlocked during test? | | |
| Bluetooth paired with vehicle? (name redacted) | | |
| Official Android Auto / GMS installed in this profile? (expected: no) | | |
| APK file and SHA-256 | | `sha256sum app-debug.apk` |
| Build: `versionName`, source revision | | App report `[app]` |
| Source identity SHA-256 | | App screen or report `[trust] source_identity_fingerprint_sha256` |
| Head-unit pins before the session | | App screen or report `pinned_head_units` |
| Engine stationary, parking brake set | | Required for every vehicle test |
| Known-working baseline phone available? | | Optional (spec §11.3) |

## 2. G0 — sideload, native load, TLS self-test (no vehicle)

1. On the build host: `./tools/check.sh && ./tools/android-build.sh`; record
   `sha256sum app/build/outputs/apk/debug/app-debug.apk`. Optionally run
   `./tools/emulator-smoke.sh` first to catch app crashes on ART (not G0 evidence).
2. Install: `adb install app/build/outputs/apk/debug/app-debug.apk` (USB ADB on a
   workstation is fine for G0; it is not the car connection).
3. Launch **OpenAutoAndroid POC**. Expected: `Stage: IDLE (last completed: -)`, and in the
   report:
   - `[native_probe]`: `self_test=pass`, `abi=aarch64-android`,
     `tls_library=OpenSSL 3.5.8 25 Aug 2026`, `final_category=STAGE_LIMIT`.
   - `[trust]` (after a few seconds; the screen shows "preparing…" meanwhile):
     `identity_created=true` on first launch, `source_identity_key=RSA-2048`, and
     `tls_self_test=pass version=TLSv1.2 cipher=…`. Record how long "preparing…" lasted
     on first launch (RSA key generation on the phone is unmeasured).
   `native_load=failed`, `identity_error=`, or `tls_self_test=FAIL` fails G0.
4. Relaunch the app: `identity_created=false` and the same fingerprint (the identity
   persists).
5. Confirm in Settings → Apps → OpenAutoAndroid POC → Permissions that only
   Notifications is listed (foreground-service permissions are install-time).
6. Open **Third-party notices** and confirm OpenSSL 3.5.8 is listed.
7. Export the report (Export report → choose a local file) and keep it with this record.
8. Check that "Saved reports" reads 0 before any Connect. After a Connect/Stop at the
   desk it should read 1, and **Export saved reports** should include that attempt with
   `attempt_complete=true`.

G0 passes only with the exported report from the target phone showing the probe pass
and `tls_self_test=pass`.

## 3. Choosing what one vehicle visit attempts

The **Stop after stage** selector bounds each attempt:

| Stop after | Ends | Gate evidence it can produce |
| --- | --- | --- |
| `version` | When the head unit sends its first TLS message (`STAGE_LIMIT`); the ClientHello is still decoded | G1 only; also the blocked-read teardown test |
| `auth` | After TLS and a successful AUTH_COMPLETE (`STAGE_LIMIT`), before discovery | G1, G2 |
| `discovery` (default) | After a validated encrypted discovery response, a 30 s hold with keepalive, and a shutdown request (`HOLD_COMPLETE`) | G1, G2, G3 |
| `projection` | Opens the discovered video and input channels, sets up AV, takes video focus, and projects the owned scene until Stop | G4, G5 ([procedure](g4-g5-procedure.md)) |

Suggested order for the first visit, one exported report per attempt:

1. `version` once, to capture G1 and the head unit's ClientHello with the least new code.
2. `discovery` with first use armed (trial C2, §5).
3. `discovery` again without arming (trial C3, §6).
4. Lifecycle tests (§7).

Leave **TLS profile** at `tls12-aead` unless §8 applies.

## 4. G1 — actual accessory, version request, response transmitted

Preconditions: stationary vehicle; phone unlocked; USB-C setting recorded above; ADB not
connected through the car cable; `[trust]` shows `tls_self_test=pass`.

1. Launch the app. Leave the profile at `legacy-1.5` and choose **Stop after** `version`.
   Tap **Connect** *before* plugging in. Allow notifications if asked (record the choice
   either way).
2. Plug the cable into the recorded vehicle port.
3. Observe and note, with timestamps:
   - Whether Android shows "Open OpenAutoAndroid POC when this accessory is connected?"
     (record the choice; "always" grants USB permission implicitly).
   - Whether a USB permission dialog appears (grant it for G1).
   - The stage sequence on screen: ARMED → PERMISSION → VERSION → TLS → IDLE.
   - Any head-unit message.
4. If the stage stays ARMED, tap **Scan accessories** and record the events: `no
   accessory enumerated` (enumeration block) versus `accessory does not match filter`
   (identity shown; use **Use listed accessory** only as a recorded manual override).
5. After the attempt returns to IDLE, **Export report**. Every attempt is also saved
   automatically as it progresses (newest 50 kept), so if the app crashes or is killed,
   relaunch it and use **Export saved reports**. The screen names the newest saved
   report left incomplete by a previous run. In a saved report, `attempt_complete=false`
   means the app ended mid-attempt, and a `[crash]` section holds the Java stack trace.
   A native crash leaves only the last saved snapshot.

| Report content | Meaning |
| --- | --- |
| `last_completed=descriptor` and native `version_request_hex=…` present | The head unit's version request arrived on the actual accessory |
| native `last_completed_stage=version`, `response_version=1.5` | Our response was completely written to the descriptor: **G1 evidence** |
| `final_category=STAGE_LIMIT`, `tls_message_len=…`, `tls_client_hello_*` lines | The head unit continued to TLS; record the offered versions and suites |
| `final_category=VERSION_UNSUPPORTED` with `requested_version` ≥ 1.6 or extension bytes | Accessory and request work; profile gap (record the full request hex; see §9) |
| `VERSION_TIMEOUT`, `IO_ERROR`, `IO_EOF` | Transport reached, no valid exchange; keep `final_detail` and `final_os_error` |
| `NO_ACCESSORY`, `USB_PERMISSION_DENIED`, `USB_OPEN_FAILED`, `FGS_START_FAILED`, `NATIVE_START_FAILED` | Acquisition blocker before any protocol exchange; not a protocol or certificate result |

G1 passes only when the report from the actual head unit shows the version request
bytes and `last_completed_stage=version`. Record the full `version_request_hex` and
accessory identity (serial is redacted by the app).

## 5. G2 and G3 with trial C2 — first use of this head unit

Preconditions as for G1. The head unit's certificate is unknown, so it cannot be pinned
yet.

1. Choose **Stop after** `discovery`, TLS profile `tls12-aead`, and check **Trust the next
   wired head unit for this attempt**. The box clears itself when the attempt starts;
   first use applies to this attempt only.
2. Tap **Connect**, then plug in. Expected stages: ARMED → PERMISSION → VERSION → TLS →
   AUTH → DISCOVERY → READY (about 30 s) → IDLE. Note anything the head unit displays.
3. Export the report. If the handshake completed, the events show `head-unit certificate
   <fingerprint> pinned`, and the screen lists it under **Pinned head units**.

| Report content | Meaning |
| --- | --- |
| `tls_peer_decision=first-use-accepted <fp>` and `tls_established=true` | Local TLS completed: we verified the head unit's Finished (possession of its key) |
| `auth_status=0` and `last_completed_stage=auth` or later | The head unit accepted our generated identity and signalled success: **G2 evidence** (C2) |
| `auth_encrypted=true` | Encrypted AUTH_COMPLETE variant; record it (a profile question) |
| `last_completed_stage=discovery`, `discovery_capabilities=usable`, `discovery_usable_video=…`, `discovery_usable_input=…` | Our encrypted request produced a decrypted, validated response with usable H.264 video and input: **G3 evidence** |
| `discovery_capabilities=insufficient` | The encrypted path works but G3 is not passed; record `discovery_channel_*` and `discovery_issue_*`. To diagnose a channel the summary cannot explain (for example an AV channel with no stream type), check **Capture raw discovery response** (0.2.2-lab, discovery only) and repeat: the report then adds `discovery_response_hex`. Those bytes are unredacted (head-unit name, serials, vehicle id, Bluetooth address) — treat the report as sensitive and sanitize before sharing |
| `final_category=HOLD_COMPLETE` | The bounded hold ended normally (with or without ByeByeResponse; see `final_detail`) |
| `PEER_SHUTDOWN` after discovery | The head unit ended the session; G3 stands if discovery completed (it may need channel activation, P4) |
| `KEEPALIVE_TIMEOUT` during the hold | Record `keepalive_*` and `keepalive_profile`; G3 stands if discovery completed. Under the default `mirror` profile, compare `keepalive_hu_encrypted` with `keepalive_responses_encrypted` and `keepalive_own_encrypted`. If the head unit never pinged (`keepalive_hu_requests=0`), app versions through 0.3.0 pinged plaintext: repeat with **Keepalive** `encrypted` or upgrade to 0.3.1, whose mirror fallback is encrypted after TLS |

Failures, in the order to investigate (spec §12):

| Final category | Meaning and what to record |
| --- | --- |
| `TLS_TIMEOUT` with `tls_alerts=-` | Head unit went silent mid-handshake. A timeout alone does not prove it rejected our certificate |
| `TLS_ALERT_PEER` right after our flight (`tls_alerts=peer:fatal:…`) | The head unit rejected something we sent; the alert code (for example `bad_certificate`, `unsupported_certificate`, `certificate_unknown`, `handshake_failure`) is the key evidence about identity acceptance |
| `TLS_NO_COMMON_SUITE` | See §8; record `tls_client_hello_cipher_suites` and `tls_client_hello_supported_versions` |
| `TLS_ALERT_LOCAL` with `protocol_version` | The head unit offered no TLS 1.2 (`tls_client_hello_offers_tls12=false`) |
| `TLS_CERT_POLICY` | Our policy rejected the head unit or it sent no client certificate; with first use armed only the latter is possible |
| `AUTH_FAILED` | Record `auth_status` and `auth_status_name`; a failure after a completed handshake means the head unit rejected the session |
| `AUTH_TIMEOUT` | TLS completed but no AUTH_COMPLETE arrived; the head unit may expect something else (record `unhandled_*`) |
| `DISCOVERY_TIMEOUT`, `DISCOVERY_INVALID`, `UNEXPECTED_MESSAGE` | Record `final_detail` and `unhandled_*`; spec §12 says inspect encryption policy, fragmentation, schema, and direction before revisiting certificates |

## 6. Trial C3 — repeat with the pin, and optional C4

1. Leave first use unchecked, Stop after `discovery`, Connect, plug in.
2. Expected `tls_peer_decision=pin-match <same fingerprint>` and the same stages as §5.
   `TLS_CERT_POLICY` with `pin-mismatch` means the head unit presented a different
   certificate: record both fingerprints and do not re-arm until that is understood.
3. Optional C4: **Replace source identity** (the head unit keeps its pin), then repeat.
   Acceptance shows the head unit is not limited to the first generated leaf.

**Forget pinned head units** clears all pins; afterwards only an armed attempt can
complete TLS.

## 7. Required negative and lifecycle tests (P1)

Run each on the actual accessory and export a report after each. Use Stop after
`version` unless noted, so the head unit is left waiting when the session ends.

| Test | Steps | Pass evidence |
| --- | --- | --- |
| USB permission denied, then retry | Revoke default (Settings → Apps → OpenAutoAndroid POC → Open by default → clear), Connect, deny the USB prompt; then Connect again and grant | First report `USB_PERMISSION_DENIED`; no repeated prompt; second attempt proceeds with a new generation and session UUID |
| Blocked read interrupted at teardown | Every attempt ending `STAGE_LIMIT` after version or auth, or `VERSION_UNSUPPORTED`: the head unit then waits for us, so the reader is blocked in `acc_read` when the session ends | `io_reader=joined`, `io_signals_sent` ≥ 1, `io_stop_ms` < 1000, `io_descriptor=closed`; repeat on several attempts |
| Stop while waiting | Tap Stop during VERSION/TLS, or during READY with Stop after `discovery` | `final_category=CANCELLED`, `io_reader=joined`, `io_writer=joined`, `io_descriptor=closed`, `io_stop_ms` small; app back at IDLE |
| Stop from the notification | Same as above using the notification's Stop | Same evidence; notification removed |
| Detach at version | Unplug during VERSION/TLS stage | `IO_ERROR`/`IO_EOF` (driver saw detach first, with OS error) or `CANCELLED` (detach broadcast first); events show `accessory detached`; `io_descriptor=closed`; IDLE |
| Detach during TLS or the hold | Stop after `discovery`; unplug during TLS or READY | As above; a detach after the shutdown request is `HOLD_COMPLETE` |
| Fresh attempt after each of the above | Replug and Connect | New generation/UUID; no `NATIVE_START_FAILED (BUSY or STALE_IO)` |
| Notification permission denied | Deny notifications at Connect | Warning shown in the app; session still runs; Stop in the app works |
| Blocked write (if reproducible) | Head unit not reading while output is pending | `CANCELLED` with `io_writer=joined`; if `detached`, record as a platform limitation |

Any `io_reader=detached` or `io_writer=detached`, or a `STALE_IO` start result, means
signal-based cancellation did not work on this descriptor; record it as a precise
platform limitation.

## 8. When to use `tls12-cbc-compat`

Only if a report from this head unit ends `TLS_NO_COMMON_SUITE` and its
`tls_client_hello_cipher_suites` contains none of `c02f`, `c030`, `cca8` (the baseline
ECDHE-RSA AES-GCM and ChaCha20 suites), select `tls12-cbc-compat` for the next attempt.
The report records `tls_profile=tls12-cbc-compat` and the negotiated `tls_cipher`; note in
the session record that the relaxation was used and why. Never use it otherwise.

## 9. When to use `legacy-1.5-any-minor`

Only if a report from this head unit ends `VERSION_UNSUPPORTED` with a `final_detail`
naming the 1.6 WireConfig gate (a four-byte request for major 1, minor 6 or higher),
select version profile `legacy-1.5-any-minor` for the next attempt. It answers 1.5 MATCH
without WireConfig, as AACS does. Record in the session notes that the relaxation was
used; the report shows `profile=legacy-1.5-any-minor`. A head unit that then closes or
times out right after our response may require a response of at least its request.
Requests with extension bytes or another major version remain unsupported by every
profile.

`match-1.6` answers such a request at 1.6 rather than downgrading it, with no WireConfig
extension. It is investigative, not a fallback: use it only to record whether a head unit
that requests 1.6 advertises anything different — `resize_actions`, `margin_configs`, or
other video configurations — when it is answered at its own version, and note in the
session record that an unaudited response was sent. Compare the discovery response with
the one already captured for this head unit.

## 10. Retrieving saved reports without the UI

Reports live in the app's private `files/reports/` directory and are excluded from
backups. With the debug APK and ADB authorized at a desk (not through the car cable):

```sh
adb shell run-as org.openautoandroid.source ls files/reports
adb exec-out run-as org.openautoandroid.source cat files/reports/<name> > <name>
```

Never copy `files/identity/source-identity.pem` off the phone: it holds the private key.
Pins are in `files/identity/head-unit-pins.txt`.

## 11. Operator notes

Free text: what the head unit displayed, timings, anything unexpected.
