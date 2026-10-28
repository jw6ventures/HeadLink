# HeadLink documentation

These pages describe HeadLink as it is today: what is built, where it lives in the code,
and why it works the way it does. The [project README](../README.md) is the place to start
if you just want to install and use the app.

## How it works

| Page | Covers |
| --- | --- |
| [Architecture](architecture.md) | The Rust crates, the JNI boundary, the Android services and processes, threads, the tools, and the ordinary-app constraints (no root, no Google libraries, offline by default) |
| [Protocol](protocol.md) | The Android Auto session: versions, TLS, source identity and head-unit trust, discovery, channels, video and congestion recovery, input, sensors, and what SYNC 4.0 does differently |
| [Connecting](connecting.md) | Getting a transport: USB accessory and plug-in start; wireless bootstrap over Bluetooth, the car's Wi-Fi, auto-start, standby and keep-awake |
| [Dashboard](dashboard.md) | What the car screen shows and how it is drawn: layouts, header, media, favorites, notifications, calls, weather, day and night, the map pane |
| [Navigation](navigation.md) | Maps, offline routing with BRouter, destinations and favorites, guidance and re-routing, the instrument cluster, route preferences and preview, live traffic, OsmAnd search and places |
| [Audio and speech](audio-and-speech.md) | Why phone audio goes over Bluetooth, the USB audio channel, spoken turn prompts and read-aloud |
| [Phone app](phone-app.md) | The phone screen: every tab and setting, the Setup checklist, diagnostic reports, and permissions |
| [Verification](verification.md) | How each layer is tested, what is proven on the truck versus only in the lab, the gates, and open items |

## Doing things

| Page | Covers |
| --- | --- |
| [Maps and data](data.md) | Getting map, routing and OsmAnd files onto the phone; weather, live traffic and a source identity |
| [Development](development.md) | Building, testing, and the lab tools for working without a car |
| [Releases](release.md) | CI, signed releases, and the F-Droid and Play situation |
| [Licenses](licenses.md) | Third-party components, pins and licenses |
| [References](references.md) | The external sources the protocol work relied on, and how each was checked |

The rules the project holds itself to are in [spec.md](../spec.md), and the original build
plan is [plan.md](../plan.md). Both are older than these pages and still cite the phase
documents that used to live here; those are in the git history.

## Old labels in code comments

Many code comments still name the phase or slice that added something. This is where
each one is now described.

| Label | What it was | Now in |
| --- | --- | --- |
| P0, P1 | Toolchain, packaging, native loading | [Architecture](architecture.md) |
| P2, P3 | Handshake: version, TLS, authentication, discovery | [Protocol](protocol.md) |
| P4 | Projection: video and touch | [Protocol](protocol.md) |
| P5.1 | Media controls and now-playing | [Dashboard](dashboard.md) |
| P5.2 | Sensors | [Protocol](protocol.md) |
| P5.3 | Audio out over USB | [Audio and speech](audio-and-speech.md) |
| P5.5 | The owned map and dashboard | [Dashboard](dashboard.md), [Navigation](navigation.md) |
| P6.x | Navigation: map tracking, cluster, routing, destinations, guidance, favorites tile, street names | [Navigation](navigation.md) |
| P7.1 | Start when plugged in, saved settings | [Connecting](connecting.md), [Phone app](phone-app.md) |
| P7.2–P7.6 | Map controls, header, turn card, trip panel, media pane, day and night | [Dashboard](dashboard.md) |
| P7.7 | The phone screen rebuilt into tabs | [Phone app](phone-app.md) |
| P7.8, P7.9 | Notifications and calls on the dashboard | [Dashboard](dashboard.md) |
| P8.x | Speech: read-aloud and turn prompts | [Audio and speech](audio-and-speech.md) |
| P9.x | Live traffic | [Navigation](navigation.md) |
| P10.x | Weather | [Dashboard](dashboard.md) |
| P11.x | OsmAnd address search, places, search ahead | [Navigation](navigation.md) |
| W0–W3 | Wireless projection | [Connecting](connecting.md) |
| G0–G10 | Acceptance gates on the truck | [Verification](verification.md) |
