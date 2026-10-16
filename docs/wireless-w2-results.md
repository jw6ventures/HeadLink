# W2 results: joined SYNC's Wi-Fi, but the socket couldn't use it, 15 October 2026

**Run 1 (report `17549b53`, build `293cd27`): `outcome=tcp_failed`.** The Bluetooth bootstrap
and the Wi-Fi join both worked. The TCP socket was refused permission to use the joined
network, by Android rather than by SYNC, so no packet reached the truck. The cause was a VPN
on the phone (below; James confirmed one was on).

## What happened

W0 (`87bc9051`) gave `advertised` 11 s before, with HFP and A2DP connected.

| Time | | |
| --- | --- | --- |
| +0 | | RFCOMM connected (112 ms) |
| +239 ms | in / out | `WifiVersionRequest` (identical to W1) / `WifiVersionResponse`, 17.6 shape |
| **+285 ms** | in | **`WifiStartRequest`: `192.168.100.1:30515`, field 3 = 1**, 46 ms after our reply |
| +285 ms | out | `WifiInfoRequest`, `WifiStartResponse` (`18 00`) |
| +303 ms | in | `WifiInfoResponse` |
| +316 ms | | Local-only network requested |
| **+5,232 ms** | | **Network available after 4,916 ms** |
| +5,242 ms | | `createSocket: java.net.SocketException: Binding socket to network 1070 failed: EPERM` |
| +5,247 ms | | Link ended; the bootstrap socket closed with it |

**Reading it:**
- **SYNC skipped its prompt this time.** In W1 run 2 the start request came ~4.8 s after our
  reply, matching SYNC's on-screen question. Here it came 46 ms after. SYNC seems to remember
  the phone once it has been allowed.
- **The Wi-Fi details didn't change.** `wifi_ssid=h:d087e137b0b0` and
  `wifi_bssid=h:6b1b2ce98f52` match W1 run 2's, 7 h 23 min earlier on the same install.
  The truck was off in between (James), so the BSSID is fixed per truck, not new per start,
  which fits `ap_type=static`. This settles W1's "second drive" comparison.
- **The join took 4.9 s, including Android's network prompt**, which James answered. The
  next run will show whether Android remembers that approval.
- **The failure came 10 ms after the network appeared, before any connect.** `createSocket`
  failed while binding the new socket to the network, so SYNC never saw a SYN. Whether SYNC
  accepts our TCP session is still unknown.

## Why: EPERM on binding means a VPN

`Network.socketFactory` binds each socket to the network through netd's fwmark server
(`SELECT_NETWORK`). That calls `NetworkController::checkUserNetworkAccessLocked` (AOSP
`system/netd`, main). For an ordinary app and a physical network like this Wi-Fi, the only
`-EPERM` it can return is this one:

```cpp
// If a VPN applies to the UID, and the VPN is secure (i.e., not bypassable), then the UID can
// only select a different network if it has the ability to protect its sockets.
VirtualNetwork* virtualNetwork = getVirtualNetworkForUserLocked(uid);
if (virtualNetwork && virtualNetwork->isSecure() && !isProtectableLocked(uid, netId)) {
    ALOGE("uid %u can't select networks other than %u.", uid, virtualNetwork->getNetId());
    return -EPERM;
}
```

The other `-EPERM` branches cover the VPN network itself and the "unreachable" network. A
missing permission gives `-EACCES` instead. `ConnectivityService` applies the same rule:
an unprivileged UID "can bypass the VPN that applies to it only if it can protect its
sockets, i.e., if it is the owner". There is no exception for local-only networks. Android
still delivered `onAvailable`, which is why the request looked like it worked.

So a VPN app that doesn't allow bypassing covers OpenAutoAndroid on this phone. James
confirmed a VPN was on. That
describes most VPN apps' defaults. The app can't work around this itself: only the VPN
owner, or a system app, may use another network while the VPN applies. The lab didn't catch
it because the emulator has no VPN, and the lab path connects over the default network
without binding.

If it recurs, logcat around the attempt should show netd's line `uid <app uid> can't select
networks other than <VPN netId>`.

## Run 2: what to change

No new build is needed.

1. In the VPN app, **exclude OpenAutoAndroid** (split tunnelling or "excluded apps"; Mullvad,
   Proton and WireGuard all have it). Then `getVirtualNetworkForUser` no longer returns the
   VPN for the app's UID, and the bind is allowed.
2. **If Android's "Block connections without VPN" is on**, excluding the app may not be
   enough. Lockdown blocks apps that are outside the VPN, and whether that block also covers
   a local-only network bound explicitly hasn't been checked. If run 2 fails again with that
   switch on, try once with it off to tell the two apart.
3. Rerun the [W2 procedure](wireless-w2-procedure.md) unchanged.

Excluding the app from the VPN changes nothing else it does. It only uses the internet for
opt-in traffic (APP10), and those requests would then skip the VPN.

## Possible app change (not made)

The report could say whether a VPN covers the app: `ConnectivityManager` can report
`TRANSPORT_VPN` on the active network with the permission the app already has. On `EPERM`, the
status line could tell the operator to exclude the app. This is diagnostic only and doesn't
help the connection.

## Run 2 (15 October): it projects

With OpenAutoAndroid excluded from the VPN, James: "Other than a hiccup right when it first
started, it worked beautifully." **Wireless projection works on SYNC 4.0.** This is on
James's word. The reports aren't exported yet, so the hiccup, whether Android prompted again,
and A2DP during the session are still to be read from them.
