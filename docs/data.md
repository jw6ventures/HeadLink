# Setting up maps, routing and the optional extras

[← Back to the README](../README.md)

The APK contains no map or routing data. The app never downloads any either: it reads the
maps you download in OsmAnd, in place and read-only. Projection and the test scene work
without them, but the dashboard's map pane, routes, address search and favorites do not.
The **Setup** tab lists anything missing.

| What | File | Needed for | Required? |
| --- | --- | --- | --- |
| [OsmAnd maps](#osmand-maps-obf) | OsmAnd `.obf`, read where they are | The map pane, routes and guidance, address search, map places, search ahead | For the map and navigation |
| [Breezy Weather](#weather-breezy-weather) | An app | Weather in the dashboard header | Optional |
| [HERE API key](#live-traffic-here-api-key) | A key | Live traffic | Optional |
| [Source identity](#source-identity-pem) | A PEM file | Head units that reject the generated identity | Only if needed |

## OsmAnd maps: `.obf`

The map, routes and turn-by-turn guidance, address search, map places (POIs), search
ahead and highway exit numbers and signs all come from OsmAnd's own map files, read where
OsmAnd keeps them. Nothing is copied, and HeadLink never writes to them.

1. Install OsmAnd (`net.osmand.plus` or `net.osmand`) and use it to download the regions
   you need, for example `Us_texas_austin_northamerica.obf`. Download every region a trip
   passes through.
2. In OsmAnd, go to **Settings → OsmAnd settings → Data storage folder → Manually
   specified** and choose a shared folder, for example `Documents/osmand`. OsmAnd's
   default folder is private to OsmAnd, so other apps can't open it.
3. In HeadLink, go to the **Maps** tab, find **OsmAnd maps** (the first card), tap
   **Choose folder**, and pick that folder. The grant is read-only.

The app reads only `.obf` files directly inside that folder. It skips contour lines
(`.srtm.obf`, `.srtmf.obf`), Wikipedia (`.wiki.obf`), the world basemap, and downloads
that are still in progress. Every map in the folder is drawn and routed across together,
so a trip that crosses regions needs no switching. To add or update a map, do it in
OsmAnd.

A destination that none of the maps covers is refused rather than searched for: the
phone names the reason, and the car's turn card says "No map covers this place"; see
[Navigation](navigation.md#coverage-refusal). Once a map is added in OsmAnd, the next
time HeadLink reads the folder it asks for that trip again.

### Upgrading from 0.11 or earlier

Versions before 0.12 also used Mapsforge `.map` files for the map and BRouter `.rd5`
tiles for routing, imported into the app or swept in from a drop folder. Those are gone.
On startup 0.12 attempts the cleanup: it deletes the imported files from its own storage,
releases the drop-folder permission, and forgets the old map and routing engine
switches. It runs on every start and does nothing once there is nothing left. The files
in your old drop folder, if any are left, are yours to delete. The
OsmAnd maps folder grant is kept.

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

