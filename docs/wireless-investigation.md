# Wireless projection investigation, 11 October 2026

**Status: W0 passed on the truck, 14 October 2026: SYNC 4 advertises the bootstrap service
([results](wireless-w0-results.md)). W1 passed 15 October: with a reply in Android Auto 17.6's
shape, SYNC sends where to connect (192.168.100.1:30515) and its Wi-Fi details (WPA2,
static AP, not Wi-Fi Direct) ([W1 results](wireless-w1-results.md)). W2 (join and project)
is built and lab-checked over TCP, and awaits the truck ([procedure](wireless-w2-procedure.md)).** Research, plus the W0 probe on branch `wireless`.
The probe was built 13 October 2026 in the debug build only, and moved into release builds
on 14 October at James's request; spec §5.2 is amended for its `BLUETOOTH_CONNECT`. The
procedure is [wireless-w0-procedure.md](wireless-w0-procedure.md). There is no plan
change. Spec §2.2
says "Wireless requires a separate Bluetooth/Wi-Fi bootstrap investigation"; this is that
investigation. Going past W0 needs James's go-ahead and further spec amendments: more
permissions, and §8.1's "Bluetooth … and wifi stay closed" would need to say these are
bootstrap transports, not opened AA services.

## Short answer

**Probably possible without root on GrapheneOS.** Every step of the wireless bootstrap
has a public-API route. The privileged access GrapheneOS grants Google's Android Auto for
wireless covers conveniences, not the core path. Google's own Android Auto 17.6 uses the
same unprivileged Wi-Fi API we would use.

The real unknowns are on the truck side, not the phone side. Will SYNC 4 run the
bootstrap for a phone app that isn't Google's? Does it rotate its Wi-Fi credentials each
drive? Does it keep A2DP up during a wireless session? Only a probe on the truck can
answer those.

## How wireless Android Auto starts

Taken from [WirelessAndroidAutoDongle](https://github.com/nisargjhaveri/WirelessAndroidAutoDongle)
(`aawg/src/bluetoothProfiles.cpp`, `bluetoothHandler.cpp`, `proxyHandler.cpp`). That
project is a head unit as far as the phone can tell. It agrees with the strings in our
`gearhead_17.6` APKM.

1. **Bluetooth pairing and HFP**, done by the OS as now. The head unit registers an RFCOMM
   **server** with SDP UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66`. (The dongle connects
   HSP to the phone just to prompt it.)
2. **The phone is the RFCOMM client.** AA 17.6 contains `createRfcommSocketToServiceRecord`
   and "Creating rfcomm socket for device: %s and uuid: %s". It does not listen.
3. **Framing on RFCOMM:** `u16 BE payload length | u16 BE message id | protobuf`. IDs:
   1 WifiStartRequest, 2 WifiInfoRequest, 3 WifiInfoResponse, 4 WifiVersionRequest,
   5 WifiVersionResponse, 6 WifiConnectStatus, 7 WifiStartResponse.
4. HU → phone `WifiStartRequest{ip_address, port}`. Newer HUs send `WifiVersionRequest`
   first; AA 17.6 logs "Trying to proceed with WifiStartRequest before we received
   WifiVersionRequest".
5. Phone → HU `WifiInfoRequest`. HU → phone `WifiInfoResponse{ssid, key, bssid,
   security_mode, access_point_type STATIC|DYNAMIC}`.
6. The phone joins that network and sends `WifiStartResponse` / `WifiConnectStatus`. The
   dongle reads and ignores both. AA 17.6 says "Skipping WifiConnectStatus for PDK 4.5+".
7. **The phone opens TCP to `ip:port`** (5288 on the dongle). Ordinary AA framing then
   runs over that stream, the same bytes as USB. The dongle forwards it to USB unchanged.

AA 17.6 also has a newer "WifiProjectionProtocolOnTcp" variant (bootstrap over TCP on
the car's network). SYNC 4 may not use it. Handle it only if the truck turns out to.

## Phone-side feasibility on GrapheneOS, without root

| Step | Public API | Cost to us |
| --- | --- | --- |
| Wake when the truck connects | `CompanionDeviceManager` association with the truck's Bluetooth MAC, plus `startObservingDevicePresence` → `CompanionDeviceService`; `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` (a normal permission) | One-time association dialog |
| RFCOMM client | `BluetoothDevice.createRfcommSocketToServiceRecord(UUID)` | `BLUETOOTH_CONNECT` ("Nearby devices") runtime grant |
| Join the truck's Wi-Fi | `WifiNetworkSpecifier` (exact SSID+BSSID) → `ConnectivityManager.requestNetwork`, without `NET_CAPABILITY_INTERNET` (local-only) | System approval dialog the first time (see below) |
| TCP to the HU | `Network.bindSocket` / `network.socketFactory`, then `ParcelFileDescriptor.fromSocket` → `NativeBridge.start` | None |
| Session | Unchanged. `ProjectionService.openAndStart` already passes the native engine a stream fd from the lab `LocalSocket`, so a TCP fd fits the same slot | None |

What GrapheneOS's wireless toggle actually unlocks for Google's app
(`AndroidAutoHooks.java`, plus the Wifi/Bluetooth modules, branch 17):

- `WIFI_PRIVILEGED_ANDROID_AUTO`: the legacy `addNetwork`/`enableNetwork`/`reconnect`
  calls (blocked for targetSdk ≥ 29) and `setWifiEnabled`. **We don't need these.** We
  use the specifier route instead, and Wi-Fi must simply be left on.
- `BLUETOOTH_PRIVILEGED_ANDROID_AUTO`: HFP `connect`, A2DP/HFP connection policy,
  `setPairingConfirmation`, device metadata. These are pairing and audio-routing
  conveniences. The OS already pairs and connects HFP/A2DP for us.
- `LOCAL_MAC_ADDRESS`, `ASSOCIATE_COMPANION_DEVICES_RESTRICTED`,
  `REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION`: identity and CDM profile extras. An
  ordinary CDM association (no profile) is enough to wake us.

AA 17.6 itself contains `WifiNetworkSpecifier`, `isStaConcurrencyForLocalOnlyConnectionsSupported`,
`LocalOnlyConnectionFailureListener` and `LOCAL_ONLY_CONNECTION_FAILURE_REASON_USER_REJECT`,
so it also takes the public local-only route.

### The Wi-Fi approval dialog

From `WifiNetworkFactory.java`:

- A specific request is accepted from a **foreground app or foreground service**. Our
  `connectedDevice` service counts.
- The dialog is skipped when any of these holds:
  - **(a)** a CDM association of ours is linked to the AP's BSSID;
  - **(b)** the user approved this app before for the same SSID+BSSID, **or for the
    same SSID and security type** on a non-open network;
  - **(c)** `adb shell cmd wifi network-requests-set-user-approved <pkg> yes` was run.
    That is held in memory only and lost at reboot.
- So **if Ford's SSID is stable, the dialog appears once, ever.** If the AP is `DYNAMIC`
  and the SSID changes, the dialog comes back every drive. AA 17.6 logs "Won't persist
  Wifi Configuration (%s) since access point is DYNAMIC". A second CDM association
  against the truck's Wi-Fi BSSID would cover that case, if the BSSID is stable.

## Truck-side risks, in order of how badly they could hurt

1. **SYNC 4 may not offer wireless to us at all.** Its "Connect to Android Auto" phone-list
   entry may depend on something only Google's app provides. Likely culprits are an SDP
   record on the phone or a Bluetooth channel in the AA service discovery. The probe
   below tests this first.
2. **A2DP during wireless projection.** Wired runs A and B showed A2DP coexisting. Many
   head units route media through the AA session in wireless mode and drop A2DP. If Ford
   does, the accepted audio route (spec §2.2) breaks. P5.3's audio channel only carries
   our own audio, so there is no cheap fallback.
3. **Credential rotation** (`DYNAMIC`), which brings back the per-drive dialog. See above.
4. **The phone's Wi-Fi is tied up.** On phones without STA+STA local-only concurrency,
   joining the truck's network drops any other Wi-Fi. In the truck that is harmless, and
   cellular stays the default network for traffic and weather.
5. **Reconnect behaviour.** G5-style drop/reconnect over RF is a new failure surface. Wi-Fi
   can drop mid-drive in ways the cable never did.

## Proposed order of work (needs James's go-ahead)

- **W0 — SDP probe, read-only. Built.** Diagnostics → Wireless probe runs
  `fetchUuidsWithSdp()` on the paired truck and records whether `4de17a00-…` is
  advertised. The only spec change is the `BLUETOOTH_CONNECT` grant (§5.2, amended
  14 October 2026). If the UUID is absent, stop here.
- **W1 — bootstrap probe. Built 14 October 2026 ([procedure](wireless-w1-procedure.md)).**
  Connect RFCOMM, record WifiVersion/Start/Info messages, and
  send WifiInfoRequest. Log SSID, BSSID, security and AP type with the **key redacted**
  (spec §10). Do not join Wi-Fi yet. Repeat across two drives to see whether the SSID or
  BSSID changes.
- **W2 — join and TCP. Built 15 October 2026 ([procedure](wireless-w2-procedure.md)).** Make the local-only specifier request, open TCP, and run the
  existing session over the socket. This is the first wireless G2/G3 equivalent. Watch
  A2DP the whole time.
- **W3 — auto-start.** CDM association plus the presence service. No activity tap needed.
- **Lab:** the dongle's `aawgd` (BlueZ + hostapd) on a Linux box with a radio could stand
  in as a wireless HU for W1–W2. The Prodigy lab receiver has no wireless bootstrap.

## Sources

- nisargjhaveri/WirelessAndroidAutoDongle, `main`: `bluetoothProfiles.cpp`,
  `bluetoothHandler.cpp`, `proxyHandler.cpp`, `proto/WifiInfoResponse.proto`
- GrapheneOS `platform_frameworks_base` (branch 17): `services/core/java/com/android/server/pm/ext/AndroidAutoHooks.java`,
  `CompanionDeviceManagerService.isDeviceAssociatedForWifiConnection`
- GrapheneOS `platform_packages_modules_Wifi` (branch 17): `WifiNetworkFactory.java`,
  `WifiServiceImpl.java`, `WifiShellCommand.java`
- GrapheneOS `platform_packages_modules_Bluetooth` (branch 17): `AdapterServiceBinder.java`,
  `HeadsetServiceBinder.java`, `A2dpServiceBinder.java`
- `com.google.android.projection.gearhead_17.6.663453` (repo root): string scan of `classes2.dex`
  only, not decompiled
- [Android Wi-Fi Network Request API](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)
- [Ford: SYNC 4 Android Auto requirements](https://www.ford.com/support/how-tos/sync/getting-started-with-sync/how-does-android-auto-work-with-my-vehicle/)
