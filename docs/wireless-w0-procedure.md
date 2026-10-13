# W0: does the truck advertise wireless Android Auto?

Branch `wireless`. The first step from [the wireless investigation](wireless-investigation.md).
One sitting in the truck, about ten minutes. Read-only. The phone asks the truck which
Bluetooth services it offers (an SDP query) and records the answer. Nothing connects to the
truck and nothing is sent to it beyond that question.

## What it is

- **OAA wireless probe**, a second launcher icon. It exists in the **debug build only**
  (`app/src/debug`). The release build stays without the probe and without any Bluetooth
  permission; `tools/verify-apk.sh` checks both.
- It needs **Nearby devices** (`BLUETOOTH_CONNECT`), asked for when the probe opens.
- Each run is saved as an ordinary report. **Export saved reports** on the main screen takes
  it off the phone with the others. Device names appear only on the probe's own screen,
  where you pick the truck. The report has no names or addresses (spec §10).
- The logic is `WirelessProbe.kt`, host-tested by `tools/wireless-probe-test.sh`.

## Before the truck

1. `tools/android-build.sh`, then
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`. The debug build is
   signed with the same key as `app-release-debugsigned.apk`, so it installs over it and
   keeps the app's data (pins, settings, maps).
2. Open **OAA wireless probe** once at home and allow Nearby devices. Probing any paired
   headphones is a fine dry run. Expect `verdict=absent` or `no_answer`.
3. Afterwards, reinstall the release build for driving
   (`adb install -r app/build/outputs/apk/release/app-release-debugsigned.apk`).
   Routing runs about 30× slower in the debug build.

## In the truck

1. Engine or accessory power on, so SYNC is up. **USB cable unplugged.** The phone
   connected to the truck over Bluetooth as usual (calls and audio working).
2. On SYNC: Phone → Phone List → this phone. Note whether it shows an Android Auto option
   or setting, and what it says. Don't change it yet.
3. Open **OAA wireless probe** and tap the truck (car-audio devices sort first). Wait for
   the verdict, up to 15 s.
4. If SYNC offered "Connect to Android Auto" (or similar) for this phone in step 2 and it was
   off: turn it on, then probe again. Note any message SYNC shows.
5. Export saved reports on the main screen as usual.

## Reading the result

The report section is `[wireless_probe]`. The useful lines:

| Line | Meaning |
| --- | --- |
| `hfp=connected`, `a2dp=connected` | The truck really was up and connected during the probe |
| `cached_uuids=` | What the phone kept from pairing |
| `sdp_answer_N=` | What the truck said this time. `…_matches_cache=true` on every answer means Android may have answered from its cache |
| `aa_wireless_in_sdp` | Whether `4de17a00-…:aa_wireless_bootstrap` was in a fresh answer |
| `verdict=` | One of the four below |

- **`advertised`**: SYNC serves the bootstrap service to this phone. Build W1 next: connect
  RFCOMM, record what SYNC sends, read its Wi-Fi details with the key redacted, and don't join.
- **`absent`**: SYNC answered without it. Before concluding, check that the run had the cable
  out and `hfp=connected`, and repeat after step 4 if SYNC has an Android Auto toggle for the
  phone. If it's still absent, SYNC isn't offering wireless to this phone. That probably ends
  the wireless track, unless it appears after something only Google's app does, which we
  can't reproduce.
- **`cached_only`** or **`no_answer`**: inconclusive. The truck wasn't reachable. Repeat with
  it on and connected.

Every answer's full UUID list is in the report, unlabelled ones included, so they can be
read later.
