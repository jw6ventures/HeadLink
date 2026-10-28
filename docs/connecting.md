# Connecting to the head unit

A session needs one bidirectional byte stream to the head unit. HeadLink gets it one of two
ways:

- **USB:** the head unit is the USB host and the phone is an Android Open Accessory. Android
  hands the app a file descriptor for the accessory.
- **Wireless:** the phone sets things up over Bluetooth, joins the head unit's own Wi-Fi,
  and opens a TCP connection to it.

Everything after that is the same: the same Android Auto session, the same pins and the
same saved settings (see [protocol.md](protocol.md)). Only the transport differs, and
reports name it as `transport=accessory` or `transport=wireless`.

## The common hand-off

`ProjectionService.openAndStart` (`app/src/main/kotlin/com/jw6ventures/headlink/ProjectionService.kt`)
runs on the service's single control thread. It gets a `ParcelFileDescriptor` from
`UsbManager.openAccessory`, or from `ParcelFileDescriptor.fromSocket(WirelessLink.socket())`
for wireless. It duplicates it and passes the duplicate's raw number to
`NativeBridge.start`. Ownership of that number passes to native on the call. The service
keeps the original and closes it only after `NativeBridge.stop` has returned (spec.md §5.3).
For wireless, `WirelessLink` owns the socket and closes it when the session ends.

Native runs every transport through one threaded transport,
`core/aap-session/src/accessory.rs`. The accessory driver (`f_accessory`) has no `poll` and
blocks in `read` and `write` whatever `O_NONBLOCK` says. So one reader thread (`aap-read`)
and one writer thread (`aap-write`) are the only threads that touch the descriptor. The
protocol thread owns timers and output order, and gives the writer one chunk at a time.

To stop, native sets a flag, then interrupts any blocked call by sending the thread a
signal that has an empty handler and no `SA_RESTART`, so the call fails with `EINTR`. A
signal that lands just before the thread enters `read` is lost, so it is re-sent every
10 ms, for up to 2 s. Each I/O thread holds the descriptor through an `Arc`, so it is never
closed while a thread could still be blocked on it. A thread that won't stop in time is
detached and keeps the descriptor until it returns. The session report records which
happened: `io_reader` and `io_writer` (`joined` or `detached`), `io_signals_sent`,
`io_stop_ms`, and `io_descriptor`. TCP sockets and the lab's local socket use the same code,
which is also how it is tested off the phone, over FIFOs and sockets.

## USB

### Accessory mode and enumeration

The head unit switches the phone into accessory mode itself. The app sends no AOA control
transfers and doesn't touch the phone's USB identity (spec.md §5.2).

- `AndroidManifest.xml` routes `USB_ACCESSORY_ATTACHED` to `ConnectionActivity`, filtered by
  `res/xml/accessory_filter.xml` (manufacturer `Android`, model `Android Auto`).
  `Accessories.enumerate` reads the same filter and sorts `UsbManager.accessoryList` into
  `Enumeration.None`, `Mismatch` or `Match`.
- The app acts only on an accessory that is enumerated right now, never on a stale
  intent extra (`Accessories.current`). `Accessories.same` compares accessories by their
  fields. It never calls `UsbAccessory.equals`, which can read the serial and throw before
  permission is granted. Reports never include the serial, only its length.
- A mismatch is shown, never claimed automatically. The app offers **Use listed accessory**
  as a manual override for that one attempt.

### Permission

`ConnectionActivity.acquire` checks `UsbManager.hasPermission`. Without it, it asks with a
package-scoped `PendingIntent` (`ACTION_USB_PERMISSION`, mutable so Android can add the
result) and a receiver registered `RECEIVER_NOT_EXPORTED`. The result is checked against
the accessory that is enumerated now. A denial ends the attempt as `USB_PERMISSION_DENIED`,
and the app asks only once per attempt. If the user ticks "always open" in Android's
"Open HeadLink when this accessory is connected?" prompt, later attaches arrive with
permission already granted.

After permission, the activity starts `ProjectionService` as a `connectedDevice` foreground
service. `ProjectionService.start` promotes itself straight away, before any other check.
A detach broadcast (`ACTION_USB_ACCESSORY_DETACHED`) for that accessory stops the session.

### Start when plugged in

**Settings → Start when plugged in** (`SavedSettings.autoStart`, key `auto_start`, on by
default): an attach while nothing is running starts an attempt with the saved settings, as
**Connect** would. `AutoStart.decide` in `AppSettings.kt` is the pure rule, host-tested on
every input combination. In order, it refuses when:

| Reason | Why |
| --- | --- |
| An attempt is already running | It takes the attach its own way |
| The setting is off | |
| No accessory, or a filter mismatch | A mismatch is never claimed automatically |
| No head unit is pinned | First use stays manual: an unknown USB host must never be trusted or pinned on its own. First use is **Trust and connect** on Setup |
| The saved stop-after isn't projection | Plugging in must never run a diagnostic hold that leaves the car's screen dark |
| The source identity failed its self-test | Waiting won't fix it |

Only when everything above allows a start but the identity is still loading does it return
`WAIT`; `IDENTITY_FAILED` is checked after that.

**The identity wait.** Between drives the process has usually been killed. The attach then
starts a fresh process, and the attach is handled before `IdentityWork` has loaded the source
identity. So `AutoStart.WAIT` holds the start, and `ConnectionActivity.retryAutoStart`
decides again once the identity settles. It gives up after 30 s (`AutoStart.WAIT_MS`).

An automatic start never arms first-use trust or any other one-shot option (`PerAttempt.NONE`),
and never asks for the notification permission: nobody would see a prompt behind the lock
screen. Only once the attempt is past `PERMISSION` (the service has promoted itself) does the activity
move itself to the back (`leaveAfterAutoStart`), so a failure before then stays on screen.

### Teardown

Stop (Home or the notification), or a detach, goes through `ProjectionService.requestStop`.
In `READY`, `SETUP` or `PROJECTING` it first asks the head unit to shut down and forces the
stop after 3 s. Then `NativeBridge.stop` joins the session worker and the original descriptor
is closed. Pulling the cable usually ends a wired session as `IO_ERROR` (`os error 5`). Every
connection is a new native session with a new TLS session.

**Verified:** wired projection on the truck, in daily use. Start when plugged in has started
sessions on the truck on its own. Whether it starts with the phone **locked** isn't recorded.

## Wireless

### Why it works without root

Every step has a public API: a `CompanionDeviceManager` link to be woken, an RFCOMM client
socket for the bootstrap, a local-only `WifiNetworkSpecifier` request for the Wi-Fi, and a
socket from `Network.socketFactory` for the transport. Google's Android Auto 17.6 uses the same
unprivileged Wi-Fi API. The privileged access GrapheneOS gives Android Auto for wireless covers
conveniences only: legacy `addNetwork` and `setWifiEnabled`, pairing helpers, and the companion
profile `REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION`. HeadLink needs none of them, but
Wi-Fi must already be on. SYNC 4.0 offers the bootstrap service to a phone that has no Google
Android Auto.

### The sequence

`WirelessLink.kt` runs one link from end to end. It uses an RFCOMM thread, a `wireless-link`
handler thread for the network and TCP, and the main thread for state.

1. **RFCOMM** to the head unit's bootstrap service, UUID
   `4de17a00-52cb-11e6-bdf4-0800200c9a66` (`WirelessProbe.BOOTSTRAP_UUID`). The phone is
   always the client. Each connect is bounded at 15 s by a watchdog.
2. **The bootstrap conversation** (`WirelessBootstrap.Session`), until the head unit hands
   over its Wi-Fi details.
3. **The Wi-Fi join**: a local-only network request.
4. **TCP** to the address and port the head unit named, over that network only (10 s
   connect timeout). Then `WifiConnectStatus` (status 0) goes over RFCOMM.
5. **The session**: `SessionController.begin` with the saved settings and pins,
   `.copy(wireless = true)`, then `ProjectionService` as for USB.

RFCOMM stays open through the session, answering pings; if the head unit closes it after the
Wi-Fi details, the session carries on. The link ends with the session, or when the network or
TCP is lost, and releases the network request, so the phone leaves the head unit's Wi-Fi.

Wireless never arms first-use trust. **A head unit has to be pinned over USB first.**

### The Bluetooth bootstrap

Each frame is `u16 BE payload length | u16 BE message id | protobuf` (`WirelessBootstrap.encode`,
`Deframer`). Message ids: 1 WifiStartRequest, 2 WifiInfoRequest, 3 WifiInfoResponse,
4 WifiVersionRequest, 5 WifiVersionResponse, 6 WifiConnectStatus, 7 WifiStartResponse,
8/9 ping request/response (echoed), 11 WifiSetupInfo (recognised, unused). The head unit sends
1, 3 and 4; the phone sends 2, 5, 6 and 7.

**What SYNC 4.0 sends.** First a `WifiVersionRequest` for protocol 1.6. It lists two 5 GHz
frequencies (5180 and 5745 MHz) and head-unit information ("SYNC 4.0", software
`25221_PRODUCT.2025`). It uses the classic RFCOMM bootstrap, not Android Auto's newer
bootstrap over TCP. After our reply comes a `WifiStartRequest`: `192.168.100.1`, port
**30515** (the open-source dongle projects use 5288), and field 3, the start reason. Then
a `WifiInfoResponse` answers our info request within about 60 ms. Its contents: an SSID of
10 characters (an ordinary access point, not a `DIRECT-` Wi-Fi Direct group), a locally
administered BSSID, WPA2-Personal, and AP type `static`. The SSID and BSSID stay the same
from one drive to the next.

**What the phone sends** (`WirelessBootstrap.versionResponse`, `startResponse`, `connectStatus`)
copies what Android Auto 17.6 sends, as read from its dex:

- `WifiVersionResponse`: fields 1 and 2 echo the head unit's version. Field 3 is the serial
  `unknown`, which is what 17.6 sends without the privileged phone-state grant. Field 4 is
  status 0. Above version 1.0, field 6 carries device info: {1 a random UUID kept per install
  in `files/wireless-device-id`, 2 lifetime id `UNKNOWN`}. **Without field 6, SYNC accepts
  the reply and then goes silent.** With it, SYNC shows an on-screen "allow Android Auto"
  prompt the first time, and afterwards answers at once. The Bluetooth name and address
  aren't sent: 17.6 adds them only from version 4.0.
- `WifiInfoRequest`, sent as soon as a start request arrives. If no start request has come
  3 s after the version reply, it's sent anyway. If nothing at all has arrived after 5 s, it's
  sent once as a nudge. It's repeated every 3 s, at most 3 times.
- `WifiStartResponse` (`18 00`, field 3 status 0), once, on the first start request.
- `WifiConnectStatus` (`08 00`) once TCP is up.

**The start reason**, field 3 of `WifiStartRequest`, decoded from 17.6 (`WirelessStandby`):

| Value | Name | Meaning |
| --- | --- | --- |
| 0 | `user_request` | The driver asked on the head unit (its Android Auto button) |
| 1 | `auto_launch` | The head unit is starting projection by itself |
| 2 | `automatic_restart` | The head unit is restarting after a drop |

SYNC has sent 1 on its own starts, and 2 once, right after it reset a session. A request
without field 3 is treated as 0. A conversation that isn't standing by gives up after 20 s
(`DEADLINE_MS`).

**Redaction** (spec.md §10). The Wi-Fi key lives only in `WirelessBootstrap.Credentials`,
for the one network request, and is never written anywhere. Reports keep only its length.
The SSID, the BSSID, and any string shaped like a serial, VIN, UUID or MAC are stored as
hashes salted with a random per-install value (`files/wireless-salt`). They can be compared
between runs on this phone and nowhere else. Every inbound field is recorded by number, since
the field names come from other projects' readings.

### Joining the head unit's Wi-Fi

`Link.joinWifi` builds a `WifiNetworkSpecifier` from the SSID and BSSID. Open networks (mode 1)
and WPA/WPA2-Personal (modes 4, 8, 12) are supported. Anything else ends the link with
`unsupported_security`. The `NetworkRequest` drops the internet capability, so the network is
**local-only**: the phone's default network (mobile data) carries live traffic and everything
else, and nothing but this link uses the head unit's network. The request times out after 60 s,
which leaves room for the operator to read Android's prompt.

- **Android's approval prompt** appears the first time. Android remembers the approval for
  this app and the same SSID and security type, across reboots, until the app is
  uninstalled. SYNC's network doesn't change, so in practice the prompt appears once.
- **The foreground requirement.** `WifiNetworkFactory` accepts a specifier request only from
  a foreground app or a foreground service (importance 125 or better). A process woken by the
  companion-device service is only importance 200. So `WirelessLinkService`, a
  `connectedDevice` foreground service with a **Connecting wirelessly** notification and
  **Cancel**, holds the foreground while a link sets up. The join waits up to 5 s for it
  (`foregroundHeld`), and retries once if Android refuses within 2 s. This trap doesn't show
  when starting from Home, because the visible screen already counts as foreground.
- **The secondary station.** On a phone that supports two Wi-Fi stations at once (the Pixel 9
  does), Android puts an ordinary app's local-only network on the second interface (`wlan1`),
  and the house Wi-Fi can stay up on the first. Only car-mode apps like Android Auto get the
  primary. Android doesn't poll signal or link rate on the secondary, so those numbers in
  reports are stale and mean nothing.

### TCP and a VPN (the EPERM trap)

`Link.connectTcp` creates the socket from `Network.socketFactory`, which binds it to the head
unit's network.

**A VPN on the phone blocks this.** If a non-bypassable VPN covers the app, netd refuses to
bind any of the app's sockets to another network. The connection fails with
`Binding socket to network … failed: EPERM`, before a single packet reaches the head unit,
even though Android has already reported the network as available. There's no exception for
local-only networks, and the app can't work around it. The fix is to exclude HeadLink from the
VPN (split tunnelling). The app's only internet use is opt-in live traffic and the opt-in Home Assistant
button, which would then bypass the VPN. Whether Android's "Block connections without VPN" setting also blocks an
excluded app's local-only bind is untested. When this fails, logcat shows netd's
`uid … can't select networks other than …`.

### Ways to start

| Start | Where | What it does |
| --- | --- | --- |
| **Connect wirelessly** | Home, under Connect | Starts a link to the paired device used last (`wireless_device` in the settings prefs, kept outside `SavedSettings`). The first time, or on a long press, it asks which device, unless only one paired device is a vehicle. Vehicles are car-audio or hands-free devices: SYNC reports itself as hands-free (0x0408). The button reads **Cancel wireless** while a link sets up, and hides during a session |
| The truck connects | **Settings → Link the car for wireless** plus **Start wirelessly when the car connects** | See below |
| The truck's Android Auto button | **Settings → Link the car for wireless** | See [Standby](#standby-the-trucks-android-auto-button) |

Nearby devices (`BLUETOOTH_CONNECT`) is asked for on first use of any of these. It's the
only runtime permission wireless needs. The rest are install-time: `CHANGE_NETWORK_STATE`,
`REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`,
`REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` and `WAKE_LOCK` (spec.md §5.2).

### The companion-device link and auto-start

**Settings → Link the car for wireless** (`WirelessAuto.link`, off by default) picks the head
unit the way Home's button does. It then shows Android's own companion-device dialog for that
Bluetooth address (`AssociationRequest`, single device; an already-paired truck can be linked
directly). Once that's accepted, it calls `startObservingDevicePresence`. The app keeps one
truck link at a time. The switch shows whether the link exists. Turning it off disassociates,
and so does removing the link in Android's Connected devices settings.

From then on, Android binds `WirelessCompanionService` whenever the truck's Bluetooth connects,
even with the app closed and the screen off. If the phone hasn't been unlocked since boot,
Android holds the event until the first unlock. `WirelessAuto.appeared` then:

1. Marks the truck present and clears the last drive's stop and loop counters.
2. Decides with `WirelessAutoStart.decide` (pure, host-tested). In order, it refuses when a
   session or link is already running, a USB accessory is attached (the cable wins), Nearby
   devices isn't granted, no head unit is pinned, the saved stop-after isn't projection, or
   the identity failed. An identity that is still loading doesn't block: the link waits for it,
   for 30 s at most.
3. Saves a `[wireless_auto]` report for every wake. The report records the rule's decision
   only. A failure after the rule shows up in the session's events.
4. Starts `WirelessLink.connect(auto = true)`. An automatic link tries the RFCOMM connect up to
   4 times, 3 s apart, because SYNC's service may not be listening yet when Bluetooth connects.

**Start wirelessly when the car connects** (`SavedSettings.wirelessAutoStart`, key
`wireless_auto_start`, on by default) decides whether the truck's own start requests (reasons 1
and 2) are taken. When it's off, only the button starts projection.

Two traps live here:

- **Lower-case addresses.** `AssociationInfo.deviceMacAddress.toString()` prints in lower case,
  and `BluetoothAdapter.getRemoteDevice` rejects lower-case hex. `appeared` upper-cases the
  address first. Use an address with letters in it (for example `02:AB:CD:EF:12:34`) when
  faking a link in the emulator, or the bug hides.
- **Two callbacks per arrival.** Android 16 delivers both the legacy `onDeviceAppeared` and the
  newer `onDevicePresenceEvent`. The emulator fired one, the Pixel fired both. Both are
  handled, and a repeat within 10 s is dropped (`REPEAT_MS`, process-wide).

### Standby: the truck's Android Auto button

Android Auto never listens for the head unit: its dex has `createRfcommSocketToServiceRecord`
and no `listenUsing*Rfcomm*`. So the button on SYNC's screen can only reach a phone that
already holds the RFCOMM link, and it arrives as a `WifiStartRequest` over that link. Android
Auto keeps the link up the whole time the car is connected. HeadLink does the same for the
linked truck.

When the linked truck is present, with Nearby devices granted and Bluetooth on
(`WirelessAuto.standbyFor`), a link **stands by** (`WirelessStandby.kt`, `WirelessLink.Link.waiting`):

- It answers the version request and pings, and sends nothing on its own. It has no deadline,
  no foreground service and no notification. Home reads "Waiting for the truck. Press Android
  Auto on the truck's screen to project wirelessly."
- A start request with reason 0, or no reason, always starts. Reasons 1 and 2 start only if
  all of these hold (`WirelessStandby.acceptAuto`):
  - Start wirelessly when the car connects is on.
  - Nobody has stopped or cancelled a wireless session from the phone since the truck
    connected.
  - Fewer than 3 automatic starts in a row have each ended within 2 minutes. This loop guard
    stops a failing start from repeating all drive.

  A request that isn't accepted gets no reply and is recorded as `held`.
- An accepted start takes the normal path. The foreground service starts at that point, and a
  partial wake lock is held for up to 90 s until the session starts. While standing by, each
  frame from the truck takes a 5 s wake lock so a dozing phone can answer it.
- When the projection ends, Wi-Fi and TCP are released, and the link goes back to waiting
  (`Link.rearm`). It reuses the same RFCOMM connection if SYNC kept it, and otherwise makes a
  new one after the usual gap below. A new connection is never opened while a projection is
  being set up or running. Stale work for the old projection is cut off by `epoch`.
- If SYNC closes the idle link, it reconnects 15 s later, or 60 s later after 3 short
  connections (under 30 s each) in a row. It gives up after 10 failed connects in a row.
- Standing by ends when the truck's Bluetooth disconnects (the companion disappear event or
  `ACTION_ACL_DISCONNECTED`), when the link is removed, or when Home's **Connect wirelessly**
  or the probe's buttons start a link of their own.

An automatic arrival waits for the truck's own start request rather than asking for one.
While automatic starts are taken, the first two waits per arrival still end after 20 s and
reconnect, as the bootstrap retry does.

### Bootstrap retry

SYNC sometimes ends the bootstrap before it sends its Wi-Fi details. It either goes silent after
our version reply, or closes RFCOMM within a second without sending anything. Both have
happened soon after SYNC started up or dropped a session, and a fresh connection later has
always worked. So when a conversation ends without the details and nobody cancelled, the link
closes RFCOMM, waits 15 s, and starts over on a new connection, at most twice
(`BOOTSTRAP_RETRIES`, `BOOTSTRAP_RETRY_GAP_MS`). Each retry gets the four connect tries.
`WirelessBootstrap.Session.restart` resets the conversation but keeps its events. Home shows
"The truck didn't finish its wireless setup. Trying again in 15 s (1 of 2)…". One theory is
that the silent case is SYNC waiting for a button press, which standby now covers. That's
unproven.

### Keep-awake

While the wireless TCP connection is up, `WirelessKeepAwake` sends a 16-byte ICMP echo to the
head unit's address over its network every 50 ms. It uses an unprivileged ICMP datagram socket
bound to that network, sends only to an IPv4 literal, and never resolves a name.

**Why.** With the screen off, Android puts the Wi-Fi driver into suspend mode. The Pixel 9's
Broadcom driver then wakes for buffered traffic only every ~925 ms, and Android's DTIM
multiplier for an IPv4-only network is 9. The chip stays awake while it's transmitting. When
the video window fills, the phone stops sending and waits for acknowledgements, and it can doze
before they arrive. With the screen off and no keep-awake, a session fell apart and SYNC closed
the connection. The pings keep the chip transmitting.

**What doesn't work for this app.** A Wi-Fi lock: on Android 14+ a high-performance lock
becomes a low-latency lock, which applies only with the screen on and the app in the
foreground, unless the app holds Android Auto's car-projection permission. A multicast lock:
it lowers the DTIM multiplier only on the primary station, and this network is on the
secondary. `cmd wifi force-low-latency-mode` needs root.

**Diagnostics → Keep Wi-Fi awake during wireless projection** (`wireless_keep_awake`, on by
default). Cleared, the app pings once a second, only to measure. The report keeps replies and
round-trip times separately for screen on and screen off.

**Verified:** with keep-awake on, a 15-minute truck drive with the screen off for 12 minutes
held 45–53 fps, as good as screen on. SYNC answered all but one of 18,134 pings; occasional
screen-off stalls of 1–2 s didn't show in the video. The keep-awake-off comparison hasn't been
run, so the doze as the cause of the original failure is still unproven.

### Known issues and open theories

- **The startup blink.** Most wireless sessions hit the congestion restart about 6 s in, with
  the screen on or off: the video stops and restarts once, which looks like a brief crash.
  It's **Diagnostics → Restart video when the car falls behind**, on by default (5 episodes in
  10 s, at most once a minute; see [protocol.md](protocol.md)). Wireless congestion bunches up
  in the first minute; a wired session on the same build had no restart. SYNC accepts the
  phone's stop and restart and comes back, so the restart stays on for wireless by the
  owner's choice.
- **House Wi-Fi concurrency (unproven).** The two worst wireless sessions were started while
  the phone was still on the house Wi-Fi. One radio serving two networks on different channels,
  plus roaming scans as the house signal fades, could starve the truck's link. But one session
  on mobile data was nearly as bad. `phone_network` in a report is read at the end of the
  session, not the start.
- **Power-off is cleaner than wired.** Turning the truck off ends a wireless session with
  SYNC's own shutdown request (`USER_SELECTION`). Wired shows an I/O error instead.
- **Phone audio stays on Bluetooth A2DP** during wireless projection, and works (see
  [audio-and-speech.md](audio-and-speech.md)). A2DP and HFP changes are logged in
  `[wireless_connect]`.

### Reports

`[wireless_connect]` is one report per link (a standby link's covers the whole arrival),
rewritten at each step: every frame, redacted; `outcome=` (`session_started`,
`network_unavailable`, `tcp_failed`, `silent`, `talked`, `connect_failed`, …) and `ended=`;
timings; A2DP, HFP and ACL changes; screen and lock events; `keep_awake_*` and `wifi_link_*`
lines; and `start_reason`, `starts_accepted`, `starts_held`. It keeps at most 600 events.
`[wireless_auto]` is one per companion wake: callback, decision, reason, inputs and
`auto_start`. Neither ever contains a device name, Bluetooth address, SSID, BSSID or key.

### Diagnostics: the wireless probe

**Diagnostics → Open wireless probe** opens `WirelessProbeActivity` (unexported). Tapping a
paired device runs one SDP query (`fetchUuidsWithSdp`, 15 s) and gives a verdict on whether it
advertises the bootstrap UUID. After an `advertised` verdict it offers **Read wireless setup
from … (W1)**: the bootstrap conversation without joining Wi-Fi, closed within 35 s. It also
offers **Connect wirelessly to … (W2)**, which is the full link without Home's shortcut. Both
end any standby link first. Older code comments label the bootstrap read, the full link,
auto-start and keep-awake W1, W2 and W3.

**Lab** ([development.md](development.md)): the emulator has no Bluetooth or Wi-Fi to join.
`LAB_WIRELESS_TCP=10.0.2.2:5288 tools/emulator-lab.sh` runs a session over `WirelessLink`'s TCP
path to the lab receiver. The debug extra `lab_wireless_observe`, with `cmd companiondevice
associate` and `simulate-device-event <id> 2`, exercises the companion wake.

### Verified

| Feature | Status |
| --- | --- |
| SYNC advertises the bootstrap; RFCOMM bootstrap; Wi-Fi join; TCP; projection | Truck (drives up to 58 min) |
| Android's Wi-Fi prompt remembered | Truck: later starts, including automatic ones, needed no tap |
| VPN exclusion fixes EPERM | Truck. Lockdown mode untested |
| Connect wirelessly on Home | Truck |
| Companion link and auto-start on Bluetooth connect | Truck, about 9 s from wake to picture, after the upper-case fix |
| Keep-awake with the screen off | Truck, keep-awake on only |
| Bootstrap retry | Host tests (`WirelessBootstrapTest`) only |
| Standby and the truck's Android Auto button | Host tests (`WirelessStandbyTest`, `WirelessBootstrapTest`) only. It's still open whether SYNC sends the button press over a held link, and with reason 0 |
| Wireless transport and cleanup | Emulator lab |

## Settings

The settings named here, as the app shows them: **Start when plugged in**, **Link the car
for wireless** and **Start wirelessly when the car connects** (Settings); **Connect
wirelessly** (Home); **Keep Wi-Fi awake during wireless projection**, **Restart video when
the car falls behind** and **Open wireless probe** (Diagnostics). Defaults and the rest of
each screen are in [phone-app.md](phone-app.md).
