# HeadLink

**Drive your car's Android Auto screen from your phone, without Google's Android Auto app.**

HeadLink is an independent, open-source Android app that talks to an Android Auto head
unit directly, wired or wireless, and puts its own dashboard on the car's screen: an
offline map with turn-by-turn directions, your media, notifications and calls. It needs no
Google Play services and no account, and it works on de-Googled phones. The only internet
connection it ever makes is for optional HERE live traffic, which is off by default.

![The HeadLink dashboard on a car screen: a map with a highlighted route and a turn card, media controls, the arrival time, and notifications](docs/images/p9/p9-traffic-route-800-day.png)

> HeadLink is not made by or affiliated with Google, Ford, or any car maker. "Android
> Auto" is used only to describe the head units it connects to. Do not operate the phone
> while driving.

## What it does

- **Projects to the car's screen** over USB, or wirelessly over the head unit's own Wi-Fi.
  A wireless session can start from the phone or automatically when the car connects.
  Starting from the car's own Android Auto button is built but not yet proven.
- **Offline maps and navigation.** Your own map files (Mapsforge) and routing data
  (BRouter), with no network needed. Turn-by-turn directions on the dashboard, spoken
  turn prompts, automatic re-routing, and route preferences (avoid highways, tolls,
  unpaved roads).
- **Turn-by-turn on the instrument cluster**, on cars whose cluster shows Android Auto
  directions.
- **Destinations** shared from OsmAnd or any other app, address search from OsmAnd's map
  files, places of interest on the map, saved favorites, and a route preview with
  alternatives on the phone.
- **Media controls** and now-playing for whatever is playing on the phone.
- **Notifications on the dashboard**, optionally read aloud when tapped, and **call
  controls** (answer, decline, hang up).
- **Day and night** themes that follow the car's own day/night signal, or a fixed choice.
- **Optional extras, off by default:** live traffic using your own free HERE key, and
  weather from the Breezy Weather app.

Phone audio (music, calls, spoken directions) stays on **Bluetooth**, the way it already
works in your car. HeadLink does not send audio over USB unless you turn that on.

## Will it work for me?

Honestly: maybe. HeadLink has been built and tested against one car.

| | Tested | Requirement |
| --- | --- | --- |
| Head unit | 2024 Ford SYNC 4.0 (800x480 and 1080p) | An Android Auto head unit |
| Phone | Pixel 9 running GrapheneOS | Android 13 or newer, arm64 |
| Connection | USB and wireless | Wireless needs a head unit that offers wireless Android Auto |

Other head units may behave differently, and some may not connect at all. If you try it
on another car, a report (see [When something goes wrong](#when-something-goes-wrong)) is
very welcome, whether it works or not.

### The source identity

Before a head unit will talk to a phone, the phone has to present a certificate the head
unit trusts. HeadLink makes its own, but many head units (including Ford's) reject it.
For those, you import a **source identity** you have obtained yourself, as a PEM file.
**HeadLink provides no identity other than the one it generates.** Use only an
identity you are authorized to use. Without an identity your head unit accepts, HeadLink
cannot connect to it. See [Source identity](docs/data.md#source-identity-pem) for the file
format.

## Getting started

### 1. Install the app

Download `HeadLink-<version>.apk` from the
[Releases page](https://github.com/jw6ventures/headlink/releases) and open it on your
phone. Each release also has a `SHA256SUMS` file if you want to check the download.

Launch **HeadLink**. It has four tabs: **Home**, **Setup**, **Maps** and **Settings**. The
**Setup** tab lists anything that still needs doing and has a button for each.

### 2. Add maps and routing data

The app contains no map data and never downloads any. You download the files once on a
computer or the phone and import them:

| What | Where to get it | Needed for |
| --- | --- | --- |
| A Mapsforge `.map` file | <https://download.mapsforge.org/maps/v5/> (for example `north-america/us/texas.map`) | The map |
| BRouter `.rd5` tiles | <https://brouter.de/brouter/segments4/>, every 5°×5° tile your trips pass through | Routes and directions |
| OsmAnd `.obf` maps (optional) | Downloaded in the OsmAnd app | Address search and places |

Import them from the **Maps** tab, either one at a time with **Import map** and
**Import routing data**, or by choosing a **drop folder** that HeadLink empties into its
own storage. Keep the original `.rd5` file names: the name says which area a tile covers.
The **Maps** tab also has **Where to get maps** and **Where to get routing data**, which
open those download pages in the phone's browser.

[Setting up maps, routing and the optional extras](docs/data.md) has the details,
including how to pick tiles, how to point HeadLink at OsmAnd's maps, and how to turn on
weather and traffic.

### 3. Import your source identity (if your head unit needs one)

In **Settings**, under **Source identity**, use **Import source identity** and pick your
PEM file. If the car rejects the phone, Home also offers **Import identity**.

### 4. Pair the phone with the car over Bluetooth

Do this the usual way in the car's and the phone's Bluetooth settings. Bluetooth carries
the audio, and it is how wireless projection finds the car.

### 5. Connect

**Wired:** the first time, tap **Trust and connect** on **Home** (Setup offers it too), then
plug the phone into the car's USB port. **Trust and connect**, which trusts this head unit and remembers it, so later drives start
on their own when you plug in (**Start when plugged in** in Settings).

**Wireless:** in **Settings**, turn on **Link the car for wireless** and pick the car.
With **Start wirelessly when the car connects** on, projection starts when the phone
and car connect over Bluetooth. You can also use **Connect wirelessly** on **Home**, which appears once the car has been
trusted over USB.
HeadLink also listens for the Android Auto button on the car's screen, but that hasn't
been confirmed to work yet.

To set a destination, share a place to HeadLink from OsmAnd or a maps app, search on the
**Home** tab, or tap a favorite on the car's dashboard.

## Known issues

- **Wireless start is not always reliable.** The head unit sometimes ignores or drops the
  first attempt. HeadLink retries on its own, though the retry is new and not yet proven in
  the car; **Connect wirelessly** on Home works if it doesn't. If a VPN is running on the phone, exclude HeadLink from it, or Android blocks
  the connection to the car's Wi-Fi.
- **Mute and the keypad during calls** need a one-time permission granted from a computer
  with `adb`; the app shows the command in **Settings → Notifications and calls**.
- **Spoken prompts need a text-to-speech engine** with an English voice installed on the
  phone, and they play only over Bluetooth, never the phone's speaker.
- **Long-run stability on other cars is unproven.** The formal five-minute, five-reconnect
  test has not been done, though hour-long drives on the test truck run cleanly.

## Privacy

- No ads, no analytics, no account, no crash reporting service.
- Nothing connects to the internet unless you turn on live traffic. Then the app talks
  only to HERE, only while a route is being followed, using your own key.
- Weather is read from the Breezy Weather app on the same phone; HeadLink itself makes no
  connection for it.
- Favorites, destinations and your location stay on the phone. Diagnostic reports stay on
  the phone too, unless you export them, and leave out your position and your HERE key.

## When something goes wrong

HeadLink saves a diagnostic report for every connection attempt, even if the app crashed.
To share one, turn on **Diagnostics** in **Settings**, then use **Export saved reports**
on the Diagnostics tab. Attach it to an
[issue](https://github.com/jw6ventures/headlink/issues) with your phone, Android version,
and car (make, model, year and head unit). Read it first if you like: it is plain text.

## Building from source

HeadLink is a Rust protocol engine inside a Kotlin Android app. The build runs in Podman
containers with pinned, checksummed dependencies:

```sh
podman build -f tools/Containerfile.rust -t localhost/openauto-rust:1.85.1 .
podman build -f tools/Containerfile.android -t localhost/openauto-android:p0.2 .
./tools/fetch-deps.sh
./tools/android-build.sh --online   # once, to fill the Gradle cache; offline after that
```

The APKs land in `app/build/outputs/apk/`. [Developing HeadLink](docs/development.md)
covers the full build, tests, the repository layout, and the lab tools for testing without
a car. [Releases](docs/release.md) describes how signed releases are made.

The project is governed by [spec.md](spec.md) and tracked in [plan.md](plan.md); the
`docs/` folder holds the investigation records and test results behind each feature.

## License

HeadLink is licensed under GPL-3.0-only; see [LICENSE](LICENSE). Third-party components
and their licenses are listed in [docs/licenses.md](docs/licenses.md). Map data is ©
OpenStreetMap contributors under ODbL 1.0.

No certification or vehicle compatibility is claimed. Use HeadLink at your own risk.
