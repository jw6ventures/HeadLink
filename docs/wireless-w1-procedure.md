# W1: what does the truck send to start wireless Android Auto?

Branch `wireless`. This follows [W0](wireless-w0-results.md), which found SYNC 4
advertising the bootstrap service. W1 connects to that service over Bluetooth, records
what SYNC sends, answers only as far as SYNC's Wi-Fi details, and disconnects. It does
**not** join the truck's Wi-Fi or start projection. Spec §5.2 has the W1 amendment
(14 October 2026).

## What it does

When you tap **Read wireless setup from … (W1)**:

1. It opens one RFCOMM connection to the truck's wireless Android Auto service.
2. It answers what SYNC sends:
   - a version request with a version response (SYNC's own version echoed, status 0);
   - a ping with the same ping;
   - a start request (where to connect) with a request for the Wi-Fi details.
3. If SYNC says nothing for 5 s, it asks for the Wi-Fi details once anyway, to learn
   whether that wakes SYNC. It asks at most three times in all.
4. Once the Wi-Fi details arrive it listens 2 more seconds, then disconnects. Everything
   stops within 35 s, Bluetooth connect included.

**The report (`[wireless_bootstrap]`)** records every message in both directions:
- **SYNC's messages:** listed by field number. Any string shaped like a serial number,
  VIN, UUID or MAC address is hashed.
- **Our messages:** the exact bytes sent.
- **Wi-Fi details:**
  - the network name (SSID) and access-point hardware address (BSSID) as `h:` hashes;
  - the SSID length, and whether it looks like Wi-Fi Direct (`DIRECT-…`);
  - whether the BSSID is a randomised address;
  - the password's **length only**;
  - the security type, and whether SYNC calls the access point static or dynamic.

The hashes use a random value private to this phone's install. Two runs can be compared
("same network as last time?") without the name or address ever appearing.

## In the truck

Same setup as W0: SYNC up, **USB cable unplugged**, phone connected over Bluetooth as usual.

1. Diagnostics → Wireless probe → tap the truck. Wait for `Verdict: advertised`.
2. Tap **Read wireless setup from … (W1)** and wait for `W1 outcome: …` (up to 35 s).
3. Note anything SYNC shows on its screen while it runs, such as "Connecting Android
   Auto", an error, or a prompt. Answering a SYNC prompt is fine; write down what it said.
4. Afterwards, plug the USB cable in as usual and check that wired projection still starts.
5. **Repeat steps 1–3 on a second drive** (another day, or after the truck has been off).
   Comparing `wifi_ssid` and `wifi_bssid` between the two reports shows whether SYNC's
   network stays the same between drives.
6. Export saved reports under Diagnostics.

## Reading the outcome

| `outcome=` | Meaning | Next |
| --- | --- | --- |
| `wifi_details` | SYNC sent its Wi-Fi details | W2: join that network (local-only) and open TCP to `start_endpoint` |
| `start_only` | SYNC said where to connect, but sent no Wi-Fi details | Read the events: SYNC may want something different in reply |
| `talked` | SYNC sent other messages | Read the events: compare our replies with what it seems to expect |
| `silent` | Connected, but SYNC said nothing, even to the nudge | SYNC may expect the phone to be set up first, or a different opening |
| `connect_failed` | RFCOMM connect failed | The error is in `rfcomm_connect_error` |

Other lines worth reading:
- **`wifi_ap_type`:** `static` suggests the same network every drive, which means
  Android's Wi-Fi approval appears once. `dynamic` suggests SYNC may change it.
- **`wifi_bssid_locally_administered`:** `true` suggests a randomised address.
- **`wifi_ssid_wifi_direct`:** whether this is the Wi-Fi Direct group you asked about at
  the start.

Two runs on separate drives settle `wifi_ssid` and `wifi_bssid`.
