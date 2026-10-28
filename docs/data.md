# Setting up maps, routing and the optional extras

[← Back to the README](../README.md)

The APK contains no map or routing data. The app never downloads any either: you get the
files yourself and give them to the app on the phone. Projection and the test scene work
without them, but the dashboard's map pane, routes and favorites do not. The **Setup** tab
lists anything missing.

| What | File | Needed for | Required? |
| --- | --- | --- | --- |
| [Map](#map-mapsforge-map) | Mapsforge `.map` | The map pane | For the dashboard map |
| [Routing data](#routing-data-brouter-rd5) | BRouter `.rd5` tiles | Routes, favorites, guidance on the cluster | For navigation |
| [OsmAnd maps](#osmand-maps-obf) | OsmAnd `.obf`, read where they are | Address search, map places, search ahead | Optional |
| [Breezy Weather](#weather-breezy-weather) | An app | Weather in the dashboard header | Optional |
| [HERE API key](#live-traffic-here-api-key) | A key | Live traffic | Optional |
| [Source identity](#source-identity-pem) | A PEM file | Head units that reject the generated identity | Only if needed |

## Getting files onto the phone

You can install a `.map` or `.rd5` file in either of two ways:

- **Import map** / **Import routing data** on the **Maps** tab opens the system file
  picker. Pick one file. It is copied into the app's private storage and checked there.
- **Choose drop folder** (also on the **Maps** tab) grants the app one folder, for
  example `Download/HeadLink`. Each time HeadLink's screen comes to the front, it checks any `.map` or `.rd5` file directly in
  that folder, moves it into its own storage, and deletes it from the folder, so the
  phone never keeps two copies. A file changed in the last 30 seconds is left alone until
  the next check, which covers a download that is still running. A file that fails its
  check stays in the folder. **Check folder now** runs a check straight away. This is
  the easier route for a large file copied over USB or synced from a computer.

A file replaces an installed one with the same name. Different names are kept side by
side. The app draws every installed map together and routes across every installed
tile, so a trip that crosses regions needs no switching. **Remove a map or tile**
lists the installed files and deletes the one you pick.

## Map: Mapsforge `.map`

A Mapsforge binary map: `mapsforge binary OSM`, rendered by VTM 0.25. Up to 4 GiB per
file.

- **Download:** ready-made maps by country and US state are at
  <https://download.mapsforge.org/maps/v5/>, for example
  `north-america/us/texas.map`.
- **Or build one** from an OpenStreetMap `.osm.pbf` extract (for example from
  <https://download.geofabrik.de/>) with Osmosis and the `mapsforge-map-writer` plugin.

The import reads the header and refuses a file that is not a Mapsforge map, or one
shorter than its header says it should be (a download that was cut off). The data is
© OpenStreetMap contributors under ODbL 1.0. The dashboard shows that credit on screen.

## Routing data: BRouter `.rd5`

Each BRouter segment file covers one 5°×5° square. Download them from
<https://brouter.de/brouter/segments4/>. Each tile is named after its south-west corner:
`W100_N30.rd5` covers longitude 100°W–95°W and latitude 30°N–35°N, which includes
Austin and Dallas–Fort Worth. Download every tile a trip passes through. All of Texas
is eleven tiles, about 307 MB: `W110_N25`, `W110_N30`, `W105_N25`, `W105_N30`,
`W105_N35`, `W100_N25`, `W100_N30`, `W100_N35`, `W95_N25`, `W95_N30` and `W95_N35`.
The app accepts up to 512 MiB per tile.

- **Keep the file name.** The name is the only thing that tells BRouter which square a
  tile covers. The app finds the tile name anywhere in the file name, so
  `W100_N30 (1).rd5` still works. It refuses a file whose name has no tile name in it.
- The car profile (`car-vario.brf` and `lookups.dat`) comes with the APK. An import
  checks the tile against that `lookups.dat`. BRouter regenerates its segments every few
  weeks, and the app refuses a tile built for a different lookup version. This build
  vendors lookup version 11
  ([lookups.dat](../vendor/brouter/src/main/resources/btools/profiles/lookups.dat)). If
  segments4 moves to a newer version, the vendored BRouter has to be re-synced first
  (see [vendor/brouter](../vendor/brouter/README.md)).
- If no installed tile covers the phone's last known position, the setup card says
  "No routing data for where you are".

## OsmAnd maps: `.obf`

Address search, map places (POIs) and search ahead read OsmAnd's own map files where
OsmAnd keeps them. Nothing is copied.

1. Install OsmAnd (`net.osmand.plus` or `net.osmand`) and use it to download the regions
   you need, for example `Us_texas_austin_northamerica.obf`.
2. In OsmAnd, go to **Settings → OsmAnd settings → Data storage folder → Manually
   specified** and choose a shared folder, for example `Documents/osmand`. OsmAnd's
   default folder is private to OsmAnd, so other apps can't open it.
3. In HeadLink, go to the **Settings** tab, find **OsmAnd maps**, tap **Choose folder**,
   and pick that folder.
   The grant is read-only.

The app reads only `.obf` files directly inside that folder. It skips contour lines
(`.srtm.obf`, `.srtmf.obf`), Wikipedia (`.wiki.obf`), the world basemap, and downloads
that are still in progress. To update a map, update it in OsmAnd. The drop folder is a
separate grant: pick a different folder for it.

## Weather: Breezy Weather

Weather is off by default. It comes from Breezy Weather's data on the same phone. HeadLink
itself makes no network connection for it.

1. Install Breezy Weather (`org.breezyweather`), add your location, and let it refresh
   once.
2. In HeadLink's **Settings** tab, turn on **Show weather**. Breezy then asks you to grant its provider
   permission. If you skip that, use **Grant**.

HeadLink can't make Breezy refresh. The weather shown is as recent as Breezy's last
refresh.

## Live traffic: HERE API key

Live traffic is off by default. It and the [Home Assistant button](#home-assistant-button) are
the only features that use the network.

1. Create a HERE platform account at <https://platform.here.com/> and make an API key.
   The free Base Plan includes 5,000 transactions a month. HeadLink stays well under that.
2. In the **Live traffic** card on HeadLink's **Maps** tab, paste the key into **HERE API key**, read what traffic
   sends and HERE's terms, then turn traffic on.

The key belongs to you, and you are responsible for how it's used. It is stored in the
app's private preferences and is left out of backups and diagnostic reports. It is never
in the APK or this repository. Requests go to `data.traffic.hereapi.com`, and only while
a route is being followed.

## Home Assistant button

An optional button on the car's dashboard that calls one service on your own Home
Assistant server, such as `cover.open_cover` on `cover.garage_door`, `switch.turn_on` on
`switch.gate_opener`, or `automation.trigger` on `automation.im_home`. It is off by default.

1. In Home Assistant, open your profile, then **Security**, and create a long-lived access
   token. A token for a separate, non-admin user is safer.
2. In the **Home Assistant** card on HeadLink's **Settings** tab, enter the server's https
   address (the one that works away from home), the token, the service as
   `domain.service`, and the target entity (or several, separated by commas, or none). Tap
   **Save**, then **Check**. Check only reads from the server: it confirms the token, that
   the service exists and that each target is a known entity, without calling anything.
3. Turn on **Show the Home Assistant button**.

One tap on the button calls the service once; it turns green with a tick or red with a
cross to say how it went. Where it sits is under [the dashboard's header](dashboard.md#header).
The token is stored encrypted with a key held in the Android Keystore, is left out of
backups and diagnostic reports, and is sent only to the address you saved. Plain http is
not supported: Android blocks it in release builds.

## Source identity: PEM

The app generates its own source identity, and most testing needs nothing more. Some
head units reject it: the 2024 Ford SYNC 4.0 answered with `AUTH_COMPLETE -3` (see the
[protocol notes](protocol.md)). For a head unit like
that, use **Import source identity** in the **Source identity** card on the **Settings** tab. Pick one PEM file that contains the source
certificate, then any intermediate certificates in issuer order, then the matching
unencrypted RSA-2048 private key. A valid import replaces the current identity and keeps
the head-unit pins. Use only an identity you are authorized to use. This repository
does not provide one, and importing one does not guarantee that a vehicle will accept
it.

