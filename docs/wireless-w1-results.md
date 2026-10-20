# W1 results: SYNC 4.0 hands over its Wi-Fi details, 14–15 October 2026

**Run 2 (15 October): `outcome=wifi_details`.** With a reply in Android Auto 17.6's shape,
SYNC 4.0 sent where to connect and its Wi-Fi details. The bootstrap works with this app.

## Run 1 (report `3964af1f`, build `d7d2a2c`)

The [W1 procedure](wireless-w1-procedure.md) ran right after a fresh W0 (`a0affc5f`,
`advertised`, HFP and A2DP connected).

| | |
| --- | --- |
| RFCOMM connect | 2,584 ms, no error |
| SYNC's first message | `WifiVersionRequest`, 292 ms after connecting, 99 bytes |
| Our reply | `WifiVersionResponse` `080110062000`: 1.6 echoed, status 0, nothing else |
| Then | Silence. Our three `WifiInfoRequest`s (+3.3 s, +6.3 s, +9.3 s) got no answer, and SYNC didn't disconnect |
| Outcome | `talked`; the deadline ended it at 20 s |

SYNC's version request, field by field (`event_1`):

| Field | Value | Reading |
| --- | --- | --- |
| 1, 2 | 1, 6 | Wireless projection protocol 1.6 |
| 3 | 0 | An enum. Google's request class keeps it as `e`. Android Auto adds field 5 to its reply only when this is 3 |
| 4 (twice) | 5180, 5745 | 5 GHz frequencies in MHz: channels 36 and 149 |
| 5 | Head unit info | "Ford", "Ford F-150", "2024", a vehicle id (hashed), "Ford", "SYNC 4.0", "multi", "25221_PRODUCT.2025" |

There's no `WifiProjectionProtocolInfo` endpoint, so SYNC uses the classic RFCOMM bootstrap,
not WPP over TCP. It's also the first look at SYNC's software build: `25221_PRODUCT.2025`.
The head unit calls the truck a 2024 F-150, where spec §2.1's setup line expected 2023.

## Why it went quiet: what Android Auto 17.6 sends

`dexdump -d` over the 17.6 APK in the repository. The reply is built in `pgm.call` and the
schema comes from `xrb`'s protobuf-lite message info (classes3 string `0x14b`):

| Field | Type | 17.6 sends for SYNC (major 1, minor 6) | W1 run 1 sent |
| --- | --- | --- | --- |
| 1 | int32 | The head unit's major, echoed | 1 |
| 2 | int32 | The head unit's minor, echoed | 6 |
| 3 | string | Serial: `Build.getSerial()`, or the default `"unknown"` without the grant | — |
| 4 | enum | Status: `v3 − 12`, which is 0 for a major-1 head unit in the normal state | 0 |
| 5 | enum | 0, but only when request field 3 is 3 (SYNC sent 0, so not sent) | — |
| 6 | message `xht` | Device info, added when the version is above 1.0: {1 device id, 2 lifetime id}. {3 Bluetooth name, 4 Bluetooth address} only from 4.0 | — |
| 7 | bytes | A pending `WifiSetupInfo` token, if any | — |

- **Device id** (`ebq.p`): a random UUID, created once and kept in the `DEVICE_ID_SHARED_PERF`
  preferences.
- **Lifetime id**: the `connectivity_lifetime_id` preference, `"UNKNOWN"` until stored.
- **Bluetooth name and address:** these thresholds come from `phn.<clinit>`: `phn.b` and
  `phn.c` are 4.0. SYNC at 1.6 gets neither.

So run 1's reply lacked the serial and the device info that 17.6 always sends to a 1.6 head
unit. That is the leading explanation for SYNC waiting. It isn't proven: SYNC could also
have been waiting on something shown on its own screen.

## Change for run 2

W1 now sends the 17.6 shape: 1.6 echoed, serial `unknown`, status 0, and device info {a random
UUID kept per install in `files/wireless-device-id`, `UNKNOWN`}. The Bluetooth name and
address stay out, as 17.6 leaves them out below 4.0. Outgoing messages are now logged as
redacted field dumps rather than hex, so the device id appears only as a hash. Spec §5.2's W1
paragraph is revised to match. `WirelessBootstrapTest` checks the exact bytes (89 checks).

## Answered since

- **SYNC's screen** showed nothing during run 1: no "Connecting", no prompt (James,
  15 October).
- **Wired projection still works afterwards.** Report `9db40b0e` started three minutes after
  the W1 run (21:35 UTC on 14 October) and projected for 27 minutes. It ended in the
  ordinary `IO_ERROR` of an unplug.

## Run 2 (report `2d89e925`, build `0619055`): the Wi-Fi details

W0 (`a3d7d5e1`) gave `advertised` ten seconds before. James: SYNC "prompted and said
connecting".

| Time | Direction | Message |
| --- | --- | --- |
| +0 | | RFCOMM connected (269 ms) |
| +189 ms | in | `WifiVersionRequest`, identical to run 1 |
| +189 ms | out | `WifiVersionResponse`, 64 bytes: `1=1 2=6 3="unknown" 4=0 6={1=h:… 2="UNKNOWN"}` |
| +3,204 ms | out | `WifiInfoRequest` (W1's own ask after 3 s; no answer) |
| **+4,962 ms** | in | **`WifiStartRequest`: `1="192.168.100.1" 2=30515 3=1`** |
| +4,962 ms | out | `WifiInfoRequest` |
| **+5,020 ms** | in | **`WifiInfoResponse`**, 58 ms later |
| +7,033 ms | | W1 disconnects after lingering 2 s |

**What SYNC sends.** The fields marked † are AP labels reported by SYNC itself, not the
phone's interpretation.

| | |
| --- | --- |
| Where to connect | `192.168.100.1`, TCP port **30515** (not the dongle projects' 5288) |
| `WifiStartRequest` field 3 | 1. It is the start reason. Decoded 18 October 2026 from AA 17.6 (`pgy.a`, enum map `a.ao`): 0 `USER_REQUEST`, 1 `AUTO_LAUNCH`, 2 `AUTOMATIC_RESTART`. Run 3's `8ffdd8ea` sent 2, after SYNC had reset the previous session |
| SSID | 10 characters, not `DIRECT-…`. This is an ordinary access point, not a Wi-Fi Direct group |
| BSSID | Locally administered (a virtual or randomised address) |
| Security | WPA2-Personal, key 12 characters |
| AP type† | `static` |

**Reading it:**
- **The ~4.8 s wait before `WifiStartRequest` matches SYNC's on-screen prompt.** SYNC asked,
  and its start request followed. Whether the prompt needed an answer, or only announced
  itself, isn't recorded. Run 1, without the device info, got no prompt at all, so SYNC
  didn't treat that phone as wireless-capable. The device info was the missing piece.
- **`static` points to the same network every drive.** Android's Wi-Fi approval would then
  appear once and be remembered for this SSID and security type. The locally administered
  BSSID needs a second drive to check: it could be fixed per truck or new per start. The
  remembered approval also matches on SSID and security type alone, so a changing BSSID
  alone wouldn't bring the prompt back.
- **SYNC's network is not Wi-Fi Direct.** Joining it is an ordinary station connection,
  which `WifiNetworkSpecifier` covers.

## Other reports in the same export

- **`e0bbc954` (13:59, wired):** ended after 131 s with `FLOW_CONTROL_TIMEOUT` (4 media units
  unacknowledged for 5 s). The retry at 14:01 (`51685c95`) timed out waiting for the version
  request. The one after (`7742c1b6`) projected for 19 minutes and ended with the unplug.
  The same timeout happened on 7 October (`f42a215e`), before any wireless code, so it isn't
  attributed to the probe. It's noted here because it came 28 s after a W0.

## Next

1. **One more W1 run on another drive**, to compare `wifi_ssid` and `wifi_bssid` with
   `h:d087e137b0b0` and `h:6b1b2ce98f52`.
2. **W2, joining SYNC's network and opening TCP to `start_endpoint`.** This needs James's
   go-ahead and a spec amendment.
