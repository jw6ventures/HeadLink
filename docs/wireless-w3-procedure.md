# W3: wireless projection starts when the truck connects

Branch `wireless`. This follows [W2](wireless-w2-procedure.md), where wireless projection worked
on SYNC 4.0 from a tap. W3 starts the same connection without a tap: when the truck connects to
the phone over Bluetooth, projection starts with the phone still in a pocket. Spec §5.2 has the
W3 amendment (16 October 2026). Since 18 October the app also stands by for the truck's own
Android Auto button, and W3's switch is now **Link the truck for wireless**; see
[Standby](#standby-the-trucks-android-auto-button-spec-52-standby-amendment).

## What it does

**Settings → Start wirelessly when the truck connects** (off by default):

1. Asks for Nearby devices, if not already granted.
2. Picks the head unit as Home's Connect wirelessly does: the one used last, or the only paired
   car or hands-free device, or asks.
3. Shows **Android's own dialog** asking to let OpenAutoAndroid manage that device. This is the
   companion-device link. It's needed once, and it has to be accepted from the screen.
4. Asks Android to watch for that device.

From then on, every time the truck's Bluetooth connects:

1. Android wakes the app (`WirelessCompanionService`), even with the screen off and the app
   closed. If the phone hasn't been unlocked since it booted, Android holds the wake-up until the
   first unlock.
2. The app decides whether to start (`WirelessAutoStart`). It won't if a session or link is
   already running, the USB cable is in (the cable wins), Nearby devices was taken away, no head
   unit is pinned yet, Diagnostics' stop-after isn't projection, or the source identity failed.
   Each wake-up is saved as a `[wireless_auto]` report saying what it decided and why.
3. If it starts: a **Connecting wirelessly** notification appears, with Cancel. This is a
   foreground service, and Android needs it before it will let the app join the truck's Wi-Fi.
   Then it's W2: Bluetooth setup (retried up to four times, 3 s apart, in case SYNC isn't
   listening yet), the Wi-Fi join, TCP, and the session. The notification hands over to the
   usual session notification.

Turning the switch off removes the link, and Android stops waking the app. Removing it in
Android's settings (Connected devices) has the same effect, and the switch shows that.

## What Android was checked to do

From AOSP source (android16-qpr2) and GrapheneOS's branch 17, not yet from a phone:

- **The trigger is the Bluetooth connection itself**: the system's ACL-connected callback, for a
  linked device being watched. No profile and no special permission are needed beyond the normal
  `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`.
- **An already-paired truck can be linked directly**: the dialog finds bonded devices when the
  request names one address. The truck doesn't need to be discoverable.
- **Starting foreground services from the background** is allowed for an app with a companion
  link and `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`, at any time.
- **Being woken isn't enough for the Wi-Fi request.** Android accepts it only from a foreground
  app or foreground service, and a companion wake-up ranks just below that. So the link waits for
  its own foreground service before requesting the network, up to 5 s, and retries once if Android
  refuses within 2 s.
- **Android's network prompt shouldn't come back.** Its approval is kept per app for the same
  network name and security type, survives reboots, and is cleared only if the app is uninstalled.
  The truck's network name doesn't change between drives (W2).
- **Android 16 sends two callbacks per arrival.** Both are handled, and a repeat within 10 s is
  ignored.

## Checked before the truck

- **Host:** `WirelessAutoStartTest`: every reason, their order over every combination of inputs,
  and the `[wireless_auto]` report (no device address).
- **Build:** `verify-apk.sh` pins the two new permissions, the unexported `connectedDevice` link
  service, and the companion service guarded by `BIND_COMPANION_DEVICE_SERVICE`.
- **Emulator (API 36), 16 October 2026.** There's no Bluetooth head unit in the emulator. So
  the link was made with `adb shell cmd companiondevice associate 0 org.openautoandroid.source
  02:11:22:33:44:55`, and the debug build's `lab_wireless_observe` intent extra started watching
  it. Then `cmd companiondevice simulate-device-event 1 2` faked "Bluetooth connected". That
  event runs the same system path as a real ACL connection (`onBluetoothCompanionDeviceConnected`).
  With the app in the background, or force-stopped so no process was left:
  - **No pin:** Android woke the app. It saved `[wireless_auto]` with `decision=no_pins` and
    started nothing.
  - **A pin, process killed first:** Android started a fresh process for
    `WirelessCompanionService` and the app decided `start`, with `identity=loading`. ActivityManager
    logged `Background started FGS: Allowed … uidState: IMPF … code:COMPANION_DEVICE_MANAGER`.
    `WirelessLinkService` was foreground (`types=0x10`) with the Connecting wirelessly
    notification. RFCOMM failed four times, as it must with no truck. The link ended with
    `ended=rfcomm connect failed`, and the service and its notification went away.
  - **Callbacks:** one wake-up arrived as `device appeared` (the older callback) and another as
    `Bluetooth connected`. One report was saved per wake-up, so the deduplication held.
  - **Not covered in the emulator:** the Wi-Fi request waiting for the foreground service, and
    the companion dialog itself. Nor an address with letters in it: `02:11:22:33:44:55` is all
    digits, which hid the run 1 failure below.

## Truck run 1, 16 October 2026

James turned the setting on and accepted the dialog. It did not start on the next arrival.
The saved reports show the wake-up worked: three `[wireless_auto]` reports (`device appeared`,
then two `Bluetooth connected`), each `decision=start` with `identity=ready` and one pin. Each
was followed at once by `wireless auto-start no: the linked address is not usable`.

Cause: the link's `MacAddress` prints in lower case, and `BluetoothAdapter.getRemoteDevice`
accepts only upper-case hex, so it threw. Fixed by upper-casing the address before the call.
The `[wireless_auto]` report's `decision=start` is the rule's answer only; a failure after it
shows in the session's events, not in that report.
- **W2 lab, unchanged:** `LAB_WIRELESS_TCP=10.0.2.2:5288 tools/emulator-lab.sh` still reaches
  PROJECTING over the wireless transport and ends with the session (`emulator-lab: pass`). The
  eight demo-button checks fail exactly as on the W2 baseline.

## In the truck

Before the drive, at home with the truck off:

1. Install the APK. Open the app, go to **Settings**, and turn on **Start wirelessly when the
   truck connects**. Accept Android's dialog naming the truck.
2. Phone Wi-Fi on. Bluetooth on. VPN still excluding OpenAutoAndroid.

The drive:

1. Phone in your pocket, cable unplugged. Start the truck.
2. Note roughly how long it takes from engine start to the dashboard on the truck's screen.
3. Check touch, the map, and music over Bluetooth, as in W2.
4. Turn the truck off as usual.
5. Optional: start the truck again with the cable plugged in. Wired should start, not wireless.

Afterwards, export saved reports. Things to look for:

| What happened | Where to look |
| --- | --- |
| Nothing on the truck's screen | Is there a `[wireless_auto]` report? None means Android never woke the app. If there is one, its `decision=` and `reason=` say why it didn't start |
| A "Connecting wirelessly" notification that never projected | The `[wireless_connect]` report's `outcome=`, as in W2. `rfcomm connect … failed; trying again` events mean SYNC wasn't listening yet |
| Android's Wi-Fi prompt appeared | Note it: the approval should have been remembered from W2 |
| It projected | Note the time from engine start, and whether anything differed from W2 |

## Truck run 2, 16 October 2026: it starts, but screen-off video falls apart

Export `saved-reports_9`, truck in accessory mode. James restarted the truck between the two
sessions.

| UTC | Report | What happened |
| --- | --- | --- |
| 17:24:21 | `e23690b9`, `9912ea14` | Woken (`Bluetooth connected`). RFCOMM connected in 245 ms, but SYNC sent nothing for 20 s, so the link gave up (`outcome=silent`). SYNC was probably still starting |
| 17:25:06 | `3618a0da`, `4f343b10` | Woken again. The first RFCOMM try failed and the second worked. Wi-Fi took 3.4 s, TCP 18 ms |
| 17:25:13 | `f1ff3d33` | Session 1. Healthy: 539 of 581 frames accepted, 14 window-full events in 10 s. Ended at 11 s by the notification's Stop, which James tapped to test it. SYNC shut down cleanly (`ByeByeResponse`) |
| 17:25:37 | `d738a2aa`, `6a418dc2` | Woken by the truck's restart (`device appeared`). Wi-Fi took 7.8 s |
| 17:25:48 | `fcb11a3a` | Session 2. **Screen off, phone locked, in James's hand.** 250 window-full events in 18 s, 581 of 1,029 frames dropped waiting for a keyframe, a congestion restart at 6.7 s, 39 messages never sent, our keepalive ping unanswered. Then `IO_EOF`: SYNC closed the TCP connection. James: it "felt like it crashed" |

The phone kept drawing frames at 57 fps in session 2, so it wasn't the CPU. The truck stopped
acknowledging video quickly enough, and the TCP send queue backed up. Bluetooth stayed up
throughout.

### Why the screen matters: Wi-Fi sleep on the Pixel 9

Read from source, not yet measured on the phone:

- **Android with the screen off** turns on the Wi-Fi driver's suspend mode (AOSP
  `ClientModeImpl`, `SUSPEND_DUE_TO_SCREEN`). An app can't turn that off.
- **The Pixel 9's Broadcom driver** (`kernel/google-modules/wlan/bcmdhd/bcm4390`, branch
  `android-gs-caimito-6.1-android16`) keeps power-save mode PM2 through suspend
  (`SUPPORT_PM2_ONLY`). But it stretches the beacon listen interval to as much as 925 ms
  (`ENABLE_MAX_DTIM_IN_SUSPEND`, `MAX_DTIM_ALLOWED_INTERVAL=925`). In PM2 the chip stays awake
  while it transmits and dozes after a short idle. Asleep, it collects traffic waiting at the
  access point only at that interval.
- **Android's own DTIM multiplier** for an IPv4-only network such as the truck's is 9 (AOSP
  NetworkStack `IpClient.deriveDtimMultiplier`).
- So when the video window fills (four unacknowledged frames, SYNC's limit), the phone stops
  sending and waits for acknowledgements. It can doze before they arrive and then not collect
  them for most of a second. That fits session 2.

### What doesn't work for this app

- **A Wi-Fi lock.** Google's Android Auto 17.6 holds one ("Car wifi lock",
  `WIFI_MODE_FULL_HIGH_PERF`; flag `wifi_lock_selected_option_in_network_request_manager`, default
  `high_performance`). Since Android 14, Android turns that mode into a low-latency lock
  (`high_perf_lock_deprecated`, default true). A low-latency lock applies only with the screen on
  and the app in the foreground, unless the app holds
  `REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION`, which only Android Auto has (AOSP
  `WifiLockManager`).
- **A multicast lock.** Holding one sets Android's DTIM multiplier to 1, but only on the primary
  Wi-Fi station (`WifiMulticastLockManager`). Android puts this app's local-only network on a
  secondary station when the phone supports two (`ActiveModeWarden`). Only car-mode apps such as
  Android Auto are kept on the primary.
- **adb.** `cmd wifi force-low-latency-mode` needs root.

### The keep-awake (spec §5.2, keep-awake amendment)

While the wireless TCP connection is up, the app pings the truck's own address
(`192.168.100.1`) over the truck's Wi-Fi every 50 ms. The chip never goes 50 ms without
transmitting, so it never dozes, and acknowledgements arrive at once. **Diagnostics → Keep
Wi-Fi awake during wireless projection** is on by default. Cleared, it pings once a second, only
to measure.

`[wireless_connect]` now also records:

- `keep_awake=on|off`, sent and answered pings, and reply times (p50, p95, max, a histogram),
  kept apart for the screen on and off. If SYNC never answers pings, the pings still keep the chip
  awake, but the reply times will be empty.
- Screen off, screen on and phone unlocked, each with a Wi-Fi reading, and a reading every 30 s.
  The same screen changes go into the session's events.
- `wifi_link…`: interface (`wlan1` would confirm the secondary station), frequency, standard,
  and signal and link-rate ranges. Android polls the signal only on the primary station, so a
  flat signal on `wlan1` is expected.

### Next truck run

1. Install the APK. Keep-awake is on by default.
2. Start the truck with the phone locked, screen off, in a pocket or hand. Leave it at least
   two minutes, then use it. Note anything that felt laggy, roughly when.
3. Turn the truck off. In Diagnostics, clear **Keep Wi-Fi awake during wireless projection**.
4. Start again the same way. Same two minutes.
5. Export saved reports.

Compare the two `fcb11a3a`-style session reports (`video_window_full_events`,
`projection_dropped_awaiting_keyframe`, `final_category`). Compare the two `[wireless_connect]`
reports' `keep_awake_screen_off` lines. With keep-awake off, screen-off reply times near
100–900 ms would confirm the doze. Turn the switch back on afterwards.

## Truck run 3, 17 October 2026: a 58-minute drive, and a bad start in the driveway

Export `saved-reports_10`. **This run used the build before the keep-awake**: no report has
`keep_awake…` or `wifi_link…` lines, and no session has screen events. So the A/B above hasn't
been run yet, and screen state isn't recorded for any of these sessions.

| UTC | Report | What happened |
| --- | --- | --- |
| 15:51:45 | `51523d4c`, `9b9f70db` | Woken (`Bluetooth connected`). The first RFCOMM try failed while HFP and A2DP connected; the second worked at 3.5 s. Wi-Fi took 3.3 s, TCP 53 ms. About 9 s from wake to picture |
| 15:51:53 | `3842c9a5` | Session 1, pulling away (gear D, about 7.6 mph). **The phone was still on the house Wi-Fi** (`phone_network=wifi`). Congestion restart at 5.5 s, 3 more held, 510 window-full events, 1,133 of 2,196 frames dropped waiting for a keyframe, our keepalive ping unanswered. SYNC took the screen for its own display from 13.3 s to 25.6 s. Ended at 51 s with `Connection reset by peer`: SYNC reset the TCP connection |
| 15:53:35 | `e6fa815f` | Home's Connect wirelessly, 50 s after the reset. SYNC sent its version request, took our reply, then nothing for 20 s (`done: deadline`). Same silence as `9912ea14` on run 2 |
| 15:54:01 | `8ffdd8ea` | Connect wirelessly again. Worked: 4 s from tap to picture. SYNC's start request had field 3 = **2**; all nine earlier start requests had 1 |
| 15:54:04 | `80feaeb8` | Session 2, the drive. 58 min 21 s, ended by turning the truck off (`USER_SELECTION`, clean). Details below |

### Session 2

- About 49 fps for the whole drive: 2,800–3,200 frames a minute until parking. 3.36 GB sent,
  7.7 Mbit/s on average. All 3,498 SYNC keepalives answered.
- 12,126 window-full events, 3.5 a second. Session 1 had 10 a second, run 2's screen-off session
  14, run 2's healthy one 1.4.
- 5,872 of 181,789 frames (3.2%) dropped waiting for a keyframe.
- **No congestion restart at startup**, the first wireless session without one. Four later: at
  38.5, 41.0, 49.8 and 53.2 min. Congestion episodes ran 0–6 a minute for the first 20 minutes and
  2–9 a minute after that. The first restart came 5 s after the routing settings were changed on
  the phone.
- Two re-routes, an arrival, guidance to the cluster throughout. After arriving, SYNC took the
  screen three times in 17 s: the cameras while parking (James confirmed).
- James: the phone screen was **on** for this drive.

### The house Wi-Fi

`phone_network=wifi` appears in two wireless sessions, and they are the two worst sessions with
the screen state unknown:

| Session | Phone's own network | Frames per second | Congestion episodes |
| --- | --- | --- | --- |
| `d88d3cec` (run 1, in the driveway) | house Wi-Fi | about 12 | 18 in 19 s |
| `3842c9a5` (today, pulling away) | house Wi-Fi | about 26 | 26 in 38 s of video |
| `fcb11a3a` (run 2, screen off) | mobile data | about 22 | 11 in 18 s |
| `b5cbfdd2` (W2 run 2, second session) | mobile data | about 31 in its first minute | 33 in that minute |
| `f1ff3d33`, `2ea3281e`, `80feaeb8` | mobile data | about 50 | low |

`b5cbfdd2` was nearly as bad on mobile data, so the house Wi-Fi can't be the whole story. Working theory, not proven: with the phone still joined to the house network, the chip shares one
radio between two networks. If they're on different channels it time-slices between them, and
as the house signal fades it also scans for a better access point. Either would starve the
truck's link. `phone_network` is read when the report is written, so it says the house Wi-Fi was
up at the end of session 1, not when it came up.

### What this changes for the next run

- Install the keep-awake APK first (`…-w3c-keepawake-uncommitted.apk`).
- Start the A/B away from the house, so the house Wi-Fi isn't in the comparison.
- The silent bootstrap (`9912ea14`, `e6fa815f`) both times came soon after SYNC started or dropped
  a session, and a fresh RFCOMM connection about 25–45 s later worked. A possible fix: when SYNC
  goes quiet after the version reply, close RFCOMM and retry once, instead of waiting 20 s and
  giving up. Built after run 4; see "Bootstrap retry" below.

## Truck run 4, 17 October 2026: keep-awake build, screen off, video holds up

Export `saved-reports_11`, the keep-awake APK, keep-awake on. One 15-minute drive.

| UTC | Report | What happened |
| --- | --- | --- |
| 20:43:23 | `bb8c169c`, `309f4dcc` | Woken (`Bluetooth connected`). RFCOMM connected in 192 ms, HFP connected, then **SYNC closed the RFCOMM socket at 0.7 s** before sending anything (`outcome=silent`). Nothing retried |
| 20:44:39 | `0ce62422` | Started without a wake report, so from Home's Connect wirelessly. Wi-Fi 3.5 s, TCP 17 ms; picture about 5 s after the tap |
| 20:44:43 | `a2c0e6da` | The drive. 15 min 5 s, ended by turning the truck off (`USER_SELECTION`, clean) |

### Session `a2c0e6da`

- **Screen off for about 12 of the 15 minutes** (6.6–21 s, 66–679 s, 734–906 s).
- 45–53 fps in every full minute of driving (2,670–3,185 frames a minute). The exceptions are
  minute 0 (the startup restart), minute 6 (SYNC took the screen for 10 s), and the last two
  minutes (parking, cameras).
- 3,583 window-full events, 4.0 a second: the same as run 3's screen-on drive (3.5) and nothing
  like run 2's screen-off session without keep-awake (14, then SYNC closed the connection).
  Congestion episodes ran 0–7 a minute.
- 2,737 of 43,987 frames (6.2%) dropped waiting for a keyframe, 945 of them in minute 0.
- The startup congestion restart still fired, at 6.2 s, with the screen still on. So the
  startup blink is not about the screen.
- All 904 SYNC keepalives answered. A route, a re-route and an alternative taken on the dashboard
  all worked.

### What the keep-awake recorded

- `iface=wlan1`, 5745 MHz (channel 149), 802.11ac. `wlan1` confirms the truck's network is on
  the secondary station, as `ActiveModeWarden` predicted. Signal (−36 dBm) and link rates (866/6)
  never changed in 36 samples: Android doesn't poll the secondary station, as expected, so these
  numbers mean nothing.
- SYNC answers pings. 18,134 sent, 18,133 answered.
- Screen on: p50 ≤10 ms, p95 ≤100 ms, max 445 ms, 21 over 100 ms.
- Screen off: p50 ≤10 ms, p95 ≤50 ms, max 1,991 ms, 140 over 100 ms. 46 were over 500 ms and
  26 took 1–2 s. Even with a ping every 50 ms, the screen-off link stalls for up to 2 s now and
  then, but not often enough to show in the video.

### What it shows

With keep-awake on, screen-off wireless video was as good as the screen-on drive. That fits
the doze theory, but it isn't proven: the keep-awake-off arm hasn't run. Run 2's screen-off
failure could still have had another cause.

Two kinds of failed start are now on record, and nothing retries after either:
- SYNC goes silent after the version reply (`9912ea14`, `e6fa815f`).
- SYNC closes RFCOMM before saying anything (`309f4dcc`).

In each case a later try worked.

## Bootstrap retry (spec §5.2, bootstrap retry amendment)

Built 17 October 2026 at James's request. When the bootstrap ends without SYNC's Wi-Fi details,
and nobody has cancelled, the link closes RFCOMM and waits 15 s. Then it starts the same
conversation over on a new connection, at most twice (`BOOTSTRAP_RETRIES`,
`BOOTSTRAP_RETRY_GAP_MS` in `WirelessLink.kt`). This applies to automatic starts and to Home's
button. Each new connection gets the automatic start's four connect tries, and the conversation
gets its own nudge and 20 s deadline (`WirelessBootstrap.Session.restart`). Home shows "The truck
didn't finish its wireless setup. Trying again in 15 s (1 of 2)…", and Cancel still works.

In `[wireless_connect]`, every conversation shares one timeline: look for
`bootstrap ended before the Wi-Fi details (<outcome>); trying again in 15 s, retry N of 2`, then
`rfcomm connected again after …ms`. `frames=` counts across all of them, and `outcome=` describes
the last one. `WirelessBootstrapTest` covers the start over: deadline and nudge counted from the
new connection, earlier events kept, details still accepted.

On the truck, a start that fails the way `309f4dcc` or `e6fa815f` did should now project about
15–35 s later without a tap.

## Standby: the truck's Android Auto button (spec §5.2, standby amendment)

Built 18 October 2026 at James's request ("Yes build it"; with auto-start off, "still open the
link and listen in standby, if the button is pressed on the dash, start projecting"). Not yet on
the Pixel or the truck. The emulator has no Bluetooth, so this has run only in host tests.

### Why the button did nothing

Android Auto 17.6 never listens for the head unit. A dexdump of all four of its dex files has
`createRfcommSocketToServiceRecord` and no `listenUsing*Rfcomm*`. So SYNC has no way to reach a
phone that isn't already connected to it. With Android Auto the button works because Android Auto
holds the RFCOMM link ("WPP") the whole time the truck is connected, and reconnects it when it
drops. Pressing the button makes SYNC send a `WifiStartRequest` over that link. Our link closed
RFCOMM when a session ended, and gave up 20 s after connecting, so the button had nothing to send
over.

`WifiStartRequest` field 3 is the reason (`pgy.a` with enum map `a.ao`): 0 `USER_REQUEST`, 1
`AUTO_LAUNCH`, 2 `AUTOMATIC_RESTART`. SYNC sent 1 on nine starts, and 2 once, right after it reset
session `3842c9a5` (`8ffdd8ea`).

### What it does

Settings now has two switches in the start card:

- **Link the truck for wireless**: what W3's switch was. It's the companion-device link, and it's
  needed for everything below.
- **Start wirelessly when the truck connects** (on by default, so the W3 behaviour is unchanged):
  whether the truck's own starts (reasons 1 and 2) are taken. Off, only the button (reason 0)
  starts projection.

Each time the linked truck connects (`WirelessAuto.appeared`, same conditions as before), the app
opens RFCOMM and **stands by**:

1. It answers the version request and pings, and otherwise waits. It doesn't send
   `WifiInfoRequest` unprompted, has no deadline, holds no foreground service and shows no
   notification. Home's line reads "Waiting for the truck. Press Android Auto on the truck's
   screen to project wirelessly."
2. A start request with reason 0 (or no reason) always starts. Reasons 1 and 2 start only if the
   second switch is on, and nobody stopped or cancelled a wireless session from the phone since the
   truck connected, and fewer than three automatic starts in a row each ended within 2 minutes
   (`WirelessStandby.acceptAuto`). A held request gets no reply, and the link keeps waiting.
3. An accepted start runs the W2 path unchanged. The "Connecting wirelessly" notification
   (foreground service) appears only now, and a partial wake lock is held for up to 90 s until the
   session starts. While waiting, each frame from SYNC holds a 5 s wake lock so the phone can answer
   it. That's the one new permission, `WAKE_LOCK`. Android Auto has an "rfcomm-wakelock" for the
   same reason.
4. When the projection ends, however it ends, the link goes back to waiting (`Link.rearm`). This
   happens on the same RFCOMM connection if SYNC kept it open, or on a new connection 5 s later if
   SYNC closed it. Wi-Fi and TCP are released as before. If you stopped from the phone, only the
   button restarts it.
5. If SYNC closes RFCOMM while the app is waiting, it reconnects 15 s later. After three short
   connections (under 30 s each) in a row, it waits 60 s instead. After ten failed connects in a row
   it gives up. With automatic starts on, the first two waits still end after 20 s and reconnect,
   which keeps yesterday's bootstrap retry for the silent-SYNC case.
6. Standing by ends when the truck's Bluetooth disconnects (the companion service's disappear event,
   or `ACTION_ACL_DISCONNECTED`), when the link is removed in Settings, when Home's **Connect
   wirelessly** is tapped (it replaces the standby link with its own), or when the probe's W1/W2
   buttons are used.

Home's button reads Connect wirelessly while the app is standing by, and Cancel wireless only while
a start is setting up. A Cancel counts as a stop from the phone.

### Reports

There's one `[wireless_connect]` report per standby link, from the truck's arrival to the end:

- `standby=on`, `standby_projections=N`, `standby_waiting=true|false`
- `start_reason=` for the latest request (`user_request`, `auto_launch`, `automatic_restart`,
  `none`), plus `starts_accepted=` and `starts_held=`
- events: `standby: waiting for the head unit's start request`, `accepted: start request reason
  …` or `held: …`, `standby: the truck asked to start (…)`, `projection ended: …; standby: waiting
  for the truck's next start request`, `rearmed on the same connection`, `standby: bootstrap link
  closed (…); connecting again in N s`, and `bluetooth acl connected/disconnected`
- at most 600 events, then `events_dropped=N`. The keep-awake and Wi-Fi lines are those of the
  latest projection

`[wireless_auto]` gains `auto_start=on|off`. Host tests: `WirelessStandbyTest` for the rules,
and `WirelessBootstrapTest`'s `standby`, `standbyDeadline`, `rearm` and `eventCap` for the
conversation.

### Next truck run

Install the APK. In Settings, check that **Link the truck for wireless** is still on (the
existing link carries over) and that **Start wirelessly when the truck connects** is on.

1. **The button, auto-start off.** Turn off Start wirelessly when the truck connects. Start the
   truck with the phone locked in a pocket. Nothing should project. Wait a minute, then press
   Android Auto on SYNC's screen. Note whether it projects and how long it takes.
2. **Disconnect from SYNC, then the button.** While projecting, end Android Auto from SYNC's own
   screen or phone menu, whichever way it offers. Wait about 30 s, then press Android Auto again.
3. **Stop from the phone, then the button.** Tap Stop in the notification. SYNC should not restart
   it by itself. Press Android Auto on SYNC.
4. **Auto-start on.** Turn the switch back on, and turn the truck off and on. It should project as
   in run 4, without a tap.

Afterwards, export saved reports. Things to look for:

| What happened | Where to look |
| --- | --- |
| The button did nothing | `[wireless_connect]` for that time. With no `in 1 WifiStartRequest` after the press, SYNC sent nothing over the held link. Then check whether the link was up: look for `standby: waiting` with no `closed:` after it. If SYNC sent a request that was held, the `held:` event gives its reason |
| SYNC sends the button as reason 1 or 2, not 0 | Then the reason doesn't separate the button from SYNC's own starts. Note it; the rule needs another signal |
| It restarted on its own after a phone Stop | The `accepted:` event's reason. It should only be `user_request` |
| SYNC keeps closing the idle link | Repeated `standby: bootstrap link closed (…)` events, and how long each connection lasted |
| Battery | Rough battery drop over a drive with the phone standing by |

The open question is whether SYNC sends the button press over a link we already hold, and with
reason 0. Google's code suggests it does: "Start request received, setting user intention". But no
truck run has shown it yet.
