# W1 results: SYNC 4.0 answers, then waits, 14 October 2026

## Run 1 (report `3964af1f`, build `1dcdf07`)

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

## Run 2

Not yet recorded. The 15 October export (written about a minute after a fresh W0, `9f516c9a`,
`advertised`) has no new `[wireless_bootstrap]` report.
