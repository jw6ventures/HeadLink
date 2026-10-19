# W2: wireless projection on the truck

Branch `wireless`. This follows [W1](wireless-w1-results.md), where SYNC 4.0 sent its Wi-Fi
details. W2 joins that Wi-Fi, connects to SYNC, and runs the ordinary projection session
(dashboard, map, guidance, media) over it instead of the USB cable. Spec §5.2 has the W2
amendment (15 October 2026).

## What it does

When you tap **Connect wirelessly** on Home, or **Connect wirelessly to … (W2)** on the
wireless probe screen:

1. **Bluetooth:** the W1 conversation with SYNC. It also sends the start response Android
   Auto sends. This link stays open during the session.
2. **Wi-Fi:** a request for SYNC's own Wi-Fi, local-only. The first time, Android shows its
   own prompt: pick the truck's network and tap **Connect**. Android remembers that answer for
   this app and network. Your phone keeps mobile data for everything else, including traffic.
3. **TCP:** a connection to SYNC at the address it gave (W1 saw `192.168.100.1:30515`). Then a
   "connected" status to SYNC over Bluetooth.
4. **Projection:** an ordinary session with your saved settings and pins, over that
   connection. Stop it as usual, from the notification or the main screen. When the session
   ends, the phone leaves SYNC's Wi-Fi.

The Wi-Fi password is used for the one request and never stored. The `[wireless_connect]`
report records each step with the same redaction as W1, plus every Bluetooth A2DP and HFP
change for the truck while wireless is up.

### From Home (16 October 2026)

Home has a **Connect wirelessly** button under the large Connect (spec §5.2, the Home
amendment). It skips the probe's SDP check and goes straight to step 1, to the paired device
used last time, whether it was chosen on Home or on the probe. The first time it asks for
Nearby devices, then which paired device, unless only one is a car or hands-free device.
**Long-press** it to choose again. While Bluetooth and Wi-Fi are setting up it reads
**Cancel wireless**; once projecting it hides, and the large **Stop** ends the session. The
line under it shows each step and, after the link ends, its outcome.

## Checked before the truck

- **Host:** `WirelessBootstrapTest`, 117 checks. They cover the start response bytes, the
  connected status, keeping the link open past W1's deadline, the credentials never reaching
  the report, and W1 still sending no start response.
- **Emulator lab:** `LAB_WIRELESS_TCP=10.0.2.2:5288 tools/emulator-lab.sh`, run
  `20261015T181127Z`, `emulator-lab: pass`. The app opened TCP to the lab receiver through
  `WirelessLink`, and `ProjectionService` ran the session over that socket
  (`transport=wireless`, 564 frames decoded). The link ended with the session
  (`ended=session ended: PEER_SHUTDOWN`). The Bluetooth bootstrap and the Wi-Fi join can't
  run in the emulator.
- **Lab note:** the default `taps` script's eight button checks fail the same way on the
  unchanged lab-socket transport, because the dashboard replaced the demo scene they expect.
  That is pre-existing and not a W2 failure.

## In the truck

1. **Phone Wi-Fi on** (no need to be connected to anything). Bluetooth connected to the truck
   as usual. **USB cable unplugged.** If a VPN is on, OpenAutoAndroid must be excluded from
   it, or Android refuses the socket on SYNC's network (run 1, [results](wireless-w2-results.md)).
2. On Home, tap **Connect wirelessly** (the first time, pick the truck). Or, the long way:
   Diagnostics → Wireless probe → tap the truck, wait for `Verdict: advertised`.
3. On the probe, tap **Connect wirelessly to … (W2)**. Either way, answer the prompts:
   - **SYNC:** "allow Android Auto?" Tap **Allow**, if it asks.
   - **Android:** the network prompt. Choose the truck's network and tap **Connect**. The
     first time only, if all goes to plan.
4. Watch the truck's screen. The dashboard should appear as it does over USB.
5. **While it projects**, check and note:
   - Does touch work?
   - Does the map follow?
   - **Does music still play through the truck over Bluetooth?** This is the big unknown.
   - Does a phone call still work?
6. Stop from the notification, or just turn the truck off. Then plug in USB and check that
   wired still starts.
7. Export saved reports.

## Reading the result

The `[wireless_connect]` report's `outcome=`:

| outcome | Meaning |
| --- | --- |
| `session_started` | Wi-Fi and TCP worked and a session started. Its own session report (`transport=wireless`) says how projection went |
| `network_unavailable` | Android's prompt was declined, or the network didn't appear within 60 s |
| `tcp_failed` | Joined the Wi-Fi, but SYNC refused the TCP connection or didn't answer |
| `talked` / `silent` / `connect_failed` | The Bluetooth step didn't get as far as W1 did |
| `session_busy` / `identity_not_ready` | The app wasn't ready for a session (for example, a wired one was running) |

`ended=` says why the link closed. The `event_` lines include any `bluetooth a2dp …` and
`bluetooth hfp …` changes, which answer the audio question.
