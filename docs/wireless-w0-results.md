# W0 results: the truck advertises wireless Android Auto, 14 October 2026

**Verdict: `advertised`.** SYNC 4 lists the wireless Android Auto bootstrap service
(`4de17a00-52cb-11e6-bdf4-0800200c9a66`) to James's Pixel 9. There is no Google Android
Auto on that phone, so SYNC offers the service whether or not Google's app is present.
This clears the first and largest risk in [the investigation](wireless-investigation.md).
The probe ran per the [W0 procedure](wireless-w0-procedure.md).

## Run

Report `report-20261014T204105Z-a783868a` (saved-reports export, 14 October 2026):

| | |
| --- | --- |
| Build | 0.4.5-lab release (`debuggable=false`), branch `wireless` at `d3a77ee` |
| Phone | Pixel 9, GrapheneOS 2026090701, Android 17 (SDK 37) |
| Truck link | `hfp=connected`, `a2dp=connected`: the truck was up and connected as usual |
| Device | `device_class=0x0408 (handsfree)`, `device_type=dual` (classic and LE) |
| Services | A2DP sink, AVRCP, HFP, MAP notification server, **AA wireless bootstrap** |
| SDP | One answer after 8,040 ms; `aa_wireless_in_sdp=true`, `aa_wireless_in_cache=true` |

## Reading it

- **`sdp_answer_1_matches_cache=true`.** Android may report its stored list when an SDP
  query fails, so on its own this line can't prove a fresh query. It doesn't matter here,
  because the stored list also came from an SDP answer from this truck. Either way the
  truck advertised the service to this phone. The 8 s wait looks like a real round trip
  rather than an immediate cached reply. That is a reading, not a measurement.
- **SYNC presents itself as a hands-free device (`0x0408`), not car audio (`0x0420`).** The
  probe sorted car-audio devices first, so the truck wasn't guaranteed to top the list.
  Hands-free devices now sort first too.
- **There was no Android Auto toggle to test.** Whether SYNC's Phone List shows an Android
  Auto setting for this phone wasn't recorded. The advertisement shows the service is up
  regardless.

## What this does not show

The truck advertising the service doesn't mean it will run the bootstrap with this app,
and doesn't mean the Wi-Fi and TCP steps will work. Those are W1 and W2.

## Next

W1, the RFCOMM bootstrap read, as proposed on 14 October. It needs James's go-ahead and a
further spec §5.2 amendment, because the current one says the probe opens no Bluetooth
connection.
